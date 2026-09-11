package com.jay.fxi.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Its own file, kept out of backup and device transfer: an entry restored onto another install
 * names that device's token, and resuming it there would DELETE the other device's registration.
 */
internal const val PUSH_REGISTRATION_LEDGER_NAME = "fxi_push_registration_ledger"

private val Context.pushRegistrationLedgerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PUSH_REGISTRATION_LEDGER_NAME
)

/**
 * DataStore-backed [PushRegistrationLedger].
 *
 * `DataStore.edit` writes a temporary file and renames it, so a call that returns has its whole
 * ledger on disk. No corruption handler is installed: DataStore's own parse failure propagates as
 * it is, and this codec's is [PushLedgerCorruptedException]. Neither is read as an empty ledger.
 *
 * Every write leaves `next_id` behind, so a file that exists yet reads as empty — zero bytes, or
 * bytes that happen to parse as an empty message — did not come from here. [fileExists] is how
 * that is told apart from a ledger never written, which has no file at all. Reads happen inside an
 * `edit` too, so the snapshot and the file check see the same moment: a snapshot taken before the
 * first write, judged against the file that write created, would look corrupt.
 */
@Singleton
class DataStorePushRegistrationLedger internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val fileExists: () -> Boolean
) : PushRegistrationLedger {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.pushRegistrationLedgerDataStore,
        context.preferencesDataStoreFile(PUSH_REGISTRATION_LEDGER_NAME)::exists
    )

    override suspend fun entries(): List<PushLedgerEntry> =
        transform { PushLedgerTransitions.Result(it, it.entries) }

    override suspend fun recordMayExist(uid: String, token: String): PushLedgerEntry? =
        transform { PushLedgerTransitions.recordMayExist(it, uid, token) }

    override suspend fun markOwed(uid: String): List<PushLedgerEntry> =
        transform { PushLedgerTransitions.markOwed(it, uid) }

    override suspend fun recordOwed(uid: String, token: String): PushLedgerEntry =
        transform { PushLedgerTransitions.recordOwed(it, uid, token) }

    override suspend fun complete(entry: PushLedgerEntry): Boolean =
        transform { PushLedgerTransitions.complete(it, entry) }

    /** Read, transform and write inside one atomic `edit`. A transition that changes nothing writes nothing. */
    private suspend fun <T> transform(block: (PushLedger) -> PushLedgerTransitions.Result<T>): T {
        var result: PushLedgerTransitions.Result<T>? = null
        dataStore.edit { prefs ->
            val current = prefs.toLedger()
            val next = block(current)
            if (next.ledger != current) prefs.write(next.ledger)
            result = next
        }
        return checkNotNull(result).value
    }

    /** The file holds nothing else, so the whole ledger is rewritten each time. */
    private fun MutablePreferences.write(ledger: PushLedger) {
        clear()
        this[NEXT_ID] = ledger.nextId
        ledger.entries.forEach { entry ->
            this[field(entry.id, UID)] = entry.uid
            this[field(entry.id, TOKEN)] = entry.token
            this[field(entry.id, STATE)] = entry.state.name
        }
    }

    /**
     * No file is a ledger that was never written. Anything else must decode completely: an empty
     * file, an unknown key, a missing field or a broken invariant throws rather than being skipped.
     */
    private fun Preferences.toLedger(): PushLedger {
        val stored = asMap()
        if (stored.isEmpty()) {
            if (fileExists()) throw PushLedgerCorruptedException("the file exists but holds nothing")
            return PushLedger.EMPTY
        }
        return try {
            val nextId = this[NEXT_ID] ?: throw PushLedgerCorruptedException("entries without next_id")
            val fields = mutableMapOf<Long, MutableMap<String, String>>()
            for ((key, value) in stored) {
                if (key == NEXT_ID) continue
                val match = ENTRY_KEY.matchEntire(key.name)
                    ?: throw PushLedgerCorruptedException("unknown key")
                val text = value as? String ?: throw PushLedgerCorruptedException("non-text field")
                fields.getOrPut(match.groupValues[1].toLong()) { mutableMapOf() }[match.groupValues[2]] = text
            }
            val entries = fields.map { (id, entry) ->
                PushLedgerEntry(
                    id = id,
                    uid = entry[UID] ?: throw PushLedgerCorruptedException("entry without uid"),
                    token = entry[TOKEN] ?: throw PushLedgerCorruptedException("entry without token"),
                    state = entry[STATE]?.let { name ->
                        PushRegistrationState.entries.firstOrNull { it.name == name }
                    } ?: throw PushLedgerCorruptedException("entry without a known state")
                )
            }
            PushLedger(entries.sortedBy { it.id }, nextId)
        } catch (invalid: IllegalArgumentException) {
            // An invariant, or an id too long for a Long: NumberFormatException is one of these.
            throw PushLedgerCorruptedException("ledger breaks an invariant", invalid)
        } catch (wrongType: ClassCastException) {
            throw PushLedgerCorruptedException("next_id is not a number", wrongType)
        }
    }

    private fun field(id: Long, name: String) = stringPreferencesKey("entry.$id.$name")

    private companion object {
        val NEXT_ID = longPreferencesKey("next_id")
        const val UID = "uid"
        const val TOKEN = "token"
        const val STATE = "state"
        // No leading zero: `entry.01.uid` would otherwise merge into id 1 and hide a key never written.
        val ENTRY_KEY = Regex("""entry\.([1-9][0-9]*)\.(uid|token|state)""")
    }
}
