package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.GraphSeriesSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val BACKUPABLE_USER_INTENT_STORE_NAME = "fxi_backupable_user_intent"
private val Context.backupableUserIntentDataStore by preferencesDataStore(name = BACKUPABLE_USER_INTENT_STORE_NAME)

enum class GraphSelectionAudience(val storageValue: String) {
    FREE("free"),
    PREMIUM("premium")
}

data class GraphSelectionKey(val uid: String, val audience: GraphSelectionAudience, val tab: String) {
    init {
        require(uid.isNotBlank())
        require(tab.isNotBlank())
    }
}

data class GraphSelectionRecord(
    val schemaVersion: Int,
    val uid: String,
    val audience: GraphSelectionAudience,
    val tab: String,
    val visibleSeriesIds: Set<String>,
    val initializedSeries: Set<String>
) {
    val selection: GraphSeriesSelection
        get() = GraphSeriesSelection(visibleSeriesIds, initializedSeries)
}

interface GraphSelectionStore {
    suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult
    suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult
    suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult
}

sealed interface GraphSelectionReadResult {
    data object Absent : GraphSelectionReadResult
    data class Present(val record: GraphSelectionRecord) : GraphSelectionReadResult
    data class Unreadable(
        val reason: GraphSelectionUnreadableReason,
        val cause: Throwable? = null
    ) : GraphSelectionReadResult
}

enum class GraphSelectionUnreadableReason {
    IO,
    CORRUPT_DATASTORE,
    MALFORMED_RECORD,
    UNSUPPORTED_SCHEMA,
    IDENTITY_MISMATCH,
    INVALID_SELECTION
}

sealed interface GraphSelectionWriteResult {
    data object Committed : GraphSelectionWriteResult
    data class NotCommitted(val cause: Throwable) : GraphSelectionWriteResult
    data class Uncertain(val cause: Throwable) : GraphSelectionWriteResult
}

sealed interface GraphSelectionDeleteResult {
    data class Committed(val removedRecords: Int) : GraphSelectionDeleteResult
    data class NotCommitted(val cause: Throwable) : GraphSelectionDeleteResult
    data class Uncertain(val cause: Throwable) : GraphSelectionDeleteResult
}

/** One atomic record per UID/audience/tab. B2 holds this file out of backup until S1 verifies admission. */
class BackupableUserIntentStore internal constructor(private val dataStore: DataStore<Preferences>) : GraphSelectionStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.backupableUserIntentDataStore)

    private val mutex = Mutex()

    override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult = observe(key)

    override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult = observe(key)

    private suspend fun observe(key: GraphSelectionKey): GraphSelectionReadResult = mutex.withLock {
        try {
            val name = GraphSelectionPreferencesCodec.keyName(key)
            // DataStore 1.1.7 reads the file under its write lock, even if its cache is ahead of a failed rename.
            val snapshot = dataStore.updateData { it }
            readRecord(snapshot, key, name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (corrupt: CorruptionException) {
            GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.CORRUPT_DATASTORE, corrupt)
        } catch (io: IOException) {
            GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.IO, io)
        } catch (invalid: IllegalArgumentException) {
            GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.MALFORMED_RECORD, invalid)
        }
    }

    override suspend fun writeGraphSelection(
        key: GraphSelectionKey,
        selection: GraphSeriesSelection
    ): GraphSelectionWriteResult = mutex.withLock {
        val name: String
        val encoded: String
        try {
            val record = GraphSelectionRecord(
                1, key.uid, key.audience, key.tab,
                selection.visibleSeriesIds.toSet(), selection.initializedSeries.toSet()
            )
            name = GraphSelectionPreferencesCodec.keyName(key)
            encoded = GraphSelectionPreferencesCodec.encode(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: Exception) {
            return@withLock GraphSelectionWriteResult.NotCommitted(invalid)
        }
        val result = mutate { snapshot ->
            val existing = readRecord(snapshot, key, name)
            if (existing is GraphSelectionReadResult.Unreadable) {
                throw IllegalStateException("Existing graph record is unreadable: ${existing.reason}", existing.cause)
            }
            snapshot.toMutablePreferences().apply { this[stringPreferencesKey(name)] = encoded }
        }
        when (result) {
            MutationResult.Committed -> GraphSelectionWriteResult.Committed
            is MutationResult.NotCommitted -> GraphSelectionWriteResult.NotCommitted(result.cause)
            is MutationResult.Uncertain -> GraphSelectionWriteResult.Uncertain(result.cause)
        }
    }

    internal suspend fun deleteGraphSelections(uid: String): GraphSelectionDeleteResult {
        require(uid.isNotBlank())
        return mutex.withLock {
            var removed = 0
            val result = mutate { snapshot ->
                // Validate the entire reserved namespace before offering any change, even for another UID.
                val keys = snapshot.asMap().keys.filter { preference ->
                    val name = preference.name
                    if (!GraphSelectionPreferencesCodec.isGraphNamespace(name)) return@filter false
                    GraphSelectionPreferencesCodec.keyFromName(name).uid == uid
                }
                removed = keys.size
                snapshot.toMutablePreferences().apply { keys.forEach { remove(it) } }
            }
            when (result) {
                MutationResult.Committed -> GraphSelectionDeleteResult.Committed(removed)
                is MutationResult.NotCommitted -> GraphSelectionDeleteResult.NotCommitted(result.cause)
                is MutationResult.Uncertain -> GraphSelectionDeleteResult.Uncertain(result.cause)
            }
        }
    }

    private fun readRecord(snapshot: Preferences, key: GraphSelectionKey, name: String): GraphSelectionReadResult {
        val entry = snapshot.asMap().entries.find { it.key.name == name } ?: return GraphSelectionReadResult.Absent
        val raw = entry.value as? String
            ?: return GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.MALFORMED_RECORD)
        return GraphSelectionPreferencesCodec.decode(key, raw)
    }

    private sealed interface MutationResult {
        data object Committed : MutationResult
        data class NotCommitted(val cause: Throwable) : MutationResult
        data class Uncertain(val cause: Throwable) : MutationResult
    }

    /** Caller holds mutex. A candidate is offered only when the transform is about to return it. */
    private suspend fun mutate(transform: (Preferences) -> Preferences): MutationResult {
        var offered = false
        return try {
            dataStore.updateData { snapshot ->
                val candidate = transform(snapshot)
                offered = true
                candidate
            }
            MutationResult.Committed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (offered) MutationResult.Uncertain(failure) else MutationResult.NotCommitted(failure)
        }
    }
}

internal object GraphSelectionPreferencesCodec {
    private const val NAMESPACE = "graph_selection"

    fun keyName(key: GraphSelectionKey): String =
        "$NAMESPACE/v1/${encodeComponent(key.uid)}/${key.audience.storageValue}/${encodeComponent(key.tab)}"

    fun encode(record: GraphSelectionRecord): String {
        require(record.schemaVersion == 1)
        require(record.uid.isNotBlank() && record.tab.isNotBlank())
        require(record.initializedSeries.containsAll(record.visibleSeriesIds))
        strictUtf8(record.uid)
        strictUtf8(record.tab)
        (record.visibleSeriesIds + record.initializedSeries).forEach { strictUtf8(it) }
        return buildJsonObject {
            put("schemaVersion", record.schemaVersion)
            put("uid", record.uid)
            put("audience", record.audience.storageValue)
            put("tab", record.tab)
            put("visibleSeriesIds", JsonArray(record.visibleSeriesIds.sorted().map(::JsonPrimitive)))
            put("initializedSeries", JsonArray(record.initializedSeries.sorted().map(::JsonPrimitive)))
        }.toString()
    }

    fun decode(key: GraphSelectionKey, raw: String): GraphSelectionReadResult {
        return try {
            val fields = Json.parseToJsonElement(raw) as? JsonObject ?: error("Expected a JSON object")
            val schema = fields["schemaVersion"] as? JsonPrimitive ?: error("Missing schemaVersion")
            require(!schema.isString)
            val version = schema.intOrNull ?: error("Expected an integer schemaVersion")
            val uid = fields.string("uid")
            val audience = fields.string("audience")
            val tab = fields.string("tab")
            val visible = fields.stringSet("visibleSeriesIds")
            val initialized = fields.stringSet("initializedSeries")
            when {
                version != 1 -> GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.UNSUPPORTED_SCHEMA)
                uid != key.uid || audience != key.audience.storageValue || tab != key.tab ->
                    GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.IDENTITY_MISMATCH)
                !initialized.containsAll(visible) ->
                    GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.INVALID_SELECTION)
                else -> GraphSelectionReadResult.Present(
                    GraphSelectionRecord(version, uid, key.audience, tab, visible, initialized)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: Exception) {
            GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.MALFORMED_RECORD, invalid)
        }
    }

    internal fun isGraphNamespace(name: String): Boolean = name == NAMESPACE || name.startsWith("$NAMESPACE/")

    internal fun keyFromName(name: String): GraphSelectionKey {
        val parts = name.split('/')
        require(parts.size == 5 && parts[0] == NAMESPACE && parts[1] == "v1") { "Unsupported graph key format" }
        val audience = GraphSelectionAudience.entries.find { it.storageValue == parts[3] }
            ?: throw IllegalArgumentException("Unsupported graph audience")
        return GraphSelectionKey(decodeComponent(parts[2]), audience, decodeComponent(parts[4]))
    }

    private fun JsonObject.string(field: String): String {
        val value = this[field] as? JsonPrimitive ?: error("Missing string field: $field")
        require(value.isString) { "Expected string field: $field" }
        return value.content.also { strictUtf8(it) }
    }

    private fun JsonObject.stringSet(field: String): Set<String> {
        val array = this[field] as? JsonArray ?: error("Missing array field: $field")
        return array.map { item ->
            val value = item as? JsonPrimitive ?: error("Expected string in $field")
            require(value.isString) { "Expected string in $field" }
            value.content.also { strictUtf8(it) }
        }.toSet()
    }

    private fun encodeComponent(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(strictUtf8(value))

    private fun decodeComponent(value: String): String {
        val bytes = Base64.getUrlDecoder().decode(value)
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require(encodeComponent(decoded) == value) { "Noncanonical base64url component" }
        return decoded
    }

    private fun strictUtf8(value: String): ByteArray {
        return try {
            val bytes = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(bytes.remaining()).also { bytes.get(it) }
        } catch (invalid: CharacterCodingException) {
            throw IllegalArgumentException("String is not valid UTF-8", invalid)
        }
    }
}
