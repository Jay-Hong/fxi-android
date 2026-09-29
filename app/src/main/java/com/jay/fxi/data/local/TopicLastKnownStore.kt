package com.jay.fxi.data.local

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.RateSanity
import com.jay.fxi.domain.model.RateSourceSets
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Keep the literal beside the declaration: BackupRulesLedgerTest resolves same-file constants.
private const val TOPIC_LAST_KNOWN_NAME = "fxi_topic_last_known"
private const val WRITE_THROTTLE_MILLIS = 5_000L

private val Context.topicLastKnownDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TOPIC_LAST_KNOWN_NAME
)

/** The user axis only; a KRX capability epoch must never enter this store. */
internal data class TopicLastKnownOwner(val uid: String, val userAccessEpoch: String) {
    init {
        require(uid.isNotBlank())
        require(userAccessEpoch.isNotBlank())
    }
}

/** One JSON string per `(uid, userAccessEpoch, kind)` preferences key. */
internal enum class TopicLastKnownKind { FX_USD, FX_JPY, FX_EUR, TETHER, DXY }

internal fun interface TopicLastKnownClock {
    fun elapsedRealtimeMillis(): Long
}

@Serializable
private data class StoredTopicQuote(val source: String, val asset: String, val rate: Double, val at: String)

@Serializable
private data class StoredDollarIndex(val rate: Double, val at: String, val source: String)

private val lastKnownJson = Json { ignoreUnknownKeys = true }

/**
 * S3-R2 storage boundary. A caller must obtain a fresh server premium approval before [restore].
 * Restored prices are display seeds only, never ACK, delivery, freshness or silence evidence.
 *
 * Values are validated before writing and after reading. Offers coalesce by kind to a deadline
 * measured from the first offer; purge fences queued writes before deleting retired namespaces.
 * This store is deliberately not connected to any session or purger yet.
 */
@Singleton
internal class TopicLastKnownStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: TopicLastKnownClock,
    private val throttleMillis: Long,
    private val scope: CoroutineScope
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.topicLastKnownDataStore,
        TopicLastKnownClock { SystemClock.elapsedRealtime() },
        WRITE_THROTTLE_MILLIS,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private data class OwnerKind(val owner: TopicLastKnownOwner, val kind: TopicLastKnownKind)
    private data class PendingWrite(var json: String, val deadline: Long)

    private val stateLock = Any()
    private val writeMutex = Mutex()
    private val pending = mutableMapOf<OwnerKind, PendingWrite>()
    private val retiredNamespaces = mutableSetOf<String>()
    private var activeNamespace: String? = null

    /** Empty [TopicRates] when absent; invalid entries must never become a display seed. */
    suspend fun restore(owner: TopicLastKnownOwner): TopicRates {
        val namespace = namespace(owner)
        if (synchronized(stateLock) { namespace in retiredNamespaces ||
                (activeNamespace != null && activeNamespace != namespace) }) return TopicRates()

        val preferences = dataStore.data.first()
        val quotes = mutableListOf<TopicQuote>()
        for (kind in TopicLastKnownKind.entries) {
            val raw = preferences[key(owner, kind)] ?: continue
            if (kind == TopicLastKnownKind.DXY) continue
            val entries = try {
                lastKnownJson.decodeFromString<List<StoredTopicQuote>>(raw)
            } catch (_: SerializationException) {
                continue // The kind cannot be parsed; other kinds remain readable.
            } catch (_: IllegalArgumentException) {
                continue
            }
            entries.forEach { entry ->
                if (accepts(kind, entry.source, entry.asset, entry.rate)) {
                    val at = try { Instant.parse(entry.at) } catch (_: IllegalArgumentException) { null }
                    if (at != null) quotes += TopicQuote(entry.source, entry.asset, entry.rate, at)
                }
            }
        }

        val dollarIndex = preferences[key(owner, TopicLastKnownKind.DXY)]?.let { raw ->
            val entry = try {
                lastKnownJson.decodeFromString<StoredDollarIndex>(raw)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            entry?.takeIf { RateSanity.isPlausible(it.rate) }?.let {
                val at = try { Instant.parse(it.at) } catch (_: IllegalArgumentException) { null }
                at?.let { time -> TopicDollarIndex(it.rate, time, it.source) }
            }
        }
        return TopicRates(dollarIndex = dollarIndex).merge(quotes)
    }

    /** Non-suspending ingress; implementation will coalesce changed values within five seconds. */
    fun offer(owner: TopicLastKnownOwner, rates: TopicRates) {
        val namespace = namespace(owner)
        val values = TopicLastKnownKind.entries.mapNotNull { kind ->
            jsonFor(kind, rates)?.let { kind to it }
        }
        synchronized(stateLock) {
            if (namespace in retiredNamespaces ||
                (activeNamespace != null && activeNamespace != namespace)) return
            val deadline = clock.elapsedRealtimeMillis() + throttleMillis
            values.forEach { (kind, json) ->
                val target = OwnerKind(owner, kind)
                val existing = pending[target]
                if (existing != null) {
                    existing.json = json
                } else {
                    pending[target] = PendingWrite(json, deadline)
                    scope.launch { flushAtDeadline(target) }
                }
            }
        }
    }

    /** Remove all retired user namespaces and fence their pending writes before reporting success. */
    suspend fun purgeAllExcept(current: TopicLastKnownOwner) {
        writeMutex.withLock {
            val keep = namespace(current)
            synchronized(stateLock) {
                activeNamespace?.takeIf { it != keep }?.let(retiredNamespaces::add)
                pending.keys.filter { namespace(it.owner) != keep }.forEach {
                    retiredNamespaces += namespace(it.owner)
                    pending.remove(it)
                }
                activeNamespace = keep
            }
            dataStore.edit { preferences ->
                val prefix = "topic_last_known_"
                val keepPrefix = "${keep}_"
                preferences.asMap().keys.map { it.name }
                    .filter { it.startsWith(prefix) && !it.startsWith(keepPrefix) }
                    .forEach { name ->
                        TopicLastKnownKind.entries.firstOrNull { name.endsWith("_${it.name}") }?.let { kind ->
                            synchronized(stateLock) {
                                retiredNamespaces += name.removeSuffix("_${kind.name}")
                            }
                        }
                        preferences.remove(stringPreferencesKey(name))
                    }
            }
        }
    }

    /**
     * S3-P1: removes every namespace of [uid] except `(uid, keepEpoch)` — on disk and still pending — and answers whether
     * anything was there. Returns only after the removal is on disk; a pending write for a removed namespace never lands.
     */
    suspend fun purgeUser(uid: String, keepEpoch: String?): Boolean = writeMutex.withLock {
        require(uid.isNotBlank())
        val uidPrefix = "topic_last_known_${hex(uid)}_"
        val keepPrefix = keepEpoch?.let { "${uidPrefix}${hex(it)}_" }
        fun isRetiredKey(name: String): Boolean =
            name.startsWith(uidPrefix) && (keepPrefix == null || !name.startsWith(keepPrefix))

        val removedPending = synchronized(stateLock) {
            val retired = pending.keys.filter { target ->
                target.owner.uid == uid && target.owner.userAccessEpoch != keepEpoch
            }
            retired.forEach { target ->
                retiredNamespaces += namespace(target.owner)
                pending.remove(target)
            }
            retired.isNotEmpty()
        }

        var removedDisk = false
        dataStore.edit { preferences ->
            val keys = preferences.asMap().keys.map { it.name }.filter(::isRetiredKey)
            synchronized(stateLock) {
                keys.forEach { name ->
                    val epochHex = name.removePrefix(uidPrefix).substringBefore('_')
                    retiredNamespaces += "$uidPrefix$epochHex"
                }
            }
            keys.forEach { name -> preferences.remove(stringPreferencesKey(name)) }
            removedDisk = keys.isNotEmpty()
            check(preferences.asMap().keys.none { isRetiredKey(it.name) }) {
                "retired topic last-known keys remain for owner"
            }
        }
        removedPending || removedDisk
    }

    private suspend fun flushAtDeadline(target: OwnerKind) {
        while (true) {
            val remaining = synchronized(stateLock) { pending[target]?.deadline?.minus(clock.elapsedRealtimeMillis()) }
                ?: return
            if (remaining <= 0) break
            delay(remaining)
        }
        writeMutex.withLock {
            val value = synchronized(stateLock) {
                val namespace = namespace(target.owner)
                if (namespace in retiredNamespaces ||
                    (activeNamespace != null && activeNamespace != namespace)) {
                    pending.remove(target)
                    null
                } else {
                    pending.remove(target)
                }
            } ?: return@withLock
            dataStore.edit { preferences ->
                val key = key(target.owner, target.kind)
                preferences[key] = value.json
            }
        }
    }

    private fun jsonFor(kind: TopicLastKnownKind, rates: TopicRates): String? {
        if (kind == TopicLastKnownKind.DXY) {
            val index = rates.dollarIndex?.takeIf { RateSanity.isPlausible(it.rate) } ?: return null
            return lastKnownJson.encodeToString(StoredDollarIndex(index.rate, index.at.toString(), index.source))
        }
        val entries = rates.quotes.mapNotNull { (key, quote) ->
            if (key != quote.key || !accepts(kind, quote.source, quote.asset, quote.rate)) null
            else StoredTopicQuote(quote.source, quote.asset, quote.rate, quote.at.toString())
        }
        return entries.takeIf { it.isNotEmpty() }?.let { lastKnownJson.encodeToString(it) }
    }

    private fun accepts(kind: TopicLastKnownKind, source: String, asset: String, rate: Double): Boolean {
        if (!RateSanity.isPlausible(rate)) return false
        return when (kind) {
            TopicLastKnownKind.FX_USD -> asset == "usd-krw" && source in RateSourceSets.FX_RATE_SOURCES
            TopicLastKnownKind.FX_JPY -> asset == "jpy-krw" && source in RateSourceSets.FX_RATE_SOURCES
            TopicLastKnownKind.FX_EUR -> asset == "eur-krw" && source in RateSourceSets.FX_RATE_SOURCES
            TopicLastKnownKind.TETHER -> asset == "usdt-krw" && source in RateSourceSets.EXCHANGES
            TopicLastKnownKind.DXY -> false
        }
    }

    private fun namespace(owner: TopicLastKnownOwner): String =
        "topic_last_known_${hex(owner.uid)}_${hex(owner.userAccessEpoch)}"

    private fun key(owner: TopicLastKnownOwner, kind: TopicLastKnownKind): Preferences.Key<String> =
        stringPreferencesKey("${namespace(owner)}_${kind.name}")

    private fun hex(value: String): String = buildString {
        val digits = "0123456789abcdef"
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}
