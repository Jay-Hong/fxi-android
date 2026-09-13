package com.jay.fxi.data.entitlements

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Dedicated store name.
 *
 * D27 requires epoch/journal/capability state to sit in a different physical file from anything
 * backup-eligible, so `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` can
 * exclude exactly this file without also excluding genuine user preferences.
 */
internal const val ACCESS_EPOCH_STORE_NAME = "fxi_access_epoch"

private val Context.accessEpochDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ACCESS_EPOCH_STORE_NAME,
    corruptionHandler = accessEpochCorruptionHandler(EpochIdGenerator.Random)
)

/**
 * What replaces a record DataStore cannot parse.
 *
 * Losing the record loses the journal, and the data those entries stood for is still on disk. So
 * the replacement is not an empty record: it is the record a teardown of an unknown namespace
 * would leave — a fresh namespace to use from here, and one journal entry naming neither an owner
 * nor a past epoch, because neither is knowable any more. The startup purge resume then finds an
 * obligation even with nobody signed in, which an empty journal would not give it.
 *
 * The production handler and the tests share this record-building logic. Whether the production
 * delegate installs the handler at all is checked separately.
 */
internal fun accessEpochRecoveryRecord(ids: EpochIdGenerator): AccessEpochRecord =
    AccessEpochTransitions.rotate(
        AccessEpochRecord(),
        rotateUser = true,
        rotateKrx = true,
        ids = ids
    )

/**
 * Replaces an unparseable file with [accessEpochRecoveryRecord].
 *
 * Being called means corruption was detected and a replacement was **proposed**. DataStore then
 * re-reads the file under the write lock and writes this only if it is still unreadable; a file
 * that reads on the second attempt keeps its own contents. A failed write is added to the original
 * corruption and rethrown, so nothing here can promise the record on disk. Racing readers can also
 * produce more than one replacement while at most one is written, so this must stay free of side
 * effects beyond the log.
 *
 * It does not cover every way the record can be unusable. Only a `CorruptionException` from the
 * serializer arrives here: an existing zero-byte file reads as an empty record, and a value stored
 * under the right name with the wrong type throws where it is read instead. Both are still open.
 */
internal fun accessEpochCorruptionHandler(
    ids: EpochIdGenerator,
    onDetected: (Throwable) -> Unit = { cause ->
        Log.w(TAG, "access epoch record unparseable; replacing with an unknown-target purge obligation", cause)
    }
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { corruption ->
        onDetected(corruption)
        DataStoreAccessEpochStore.recoveryPreferences(ids)
    }

private const val TAG = "AccessEpochStore"

/**
 * DataStore-backed [AccessEpochStore].
 *
 * Atomicity comes from `DataStore.edit`, which writes a temporary file and renames it, so a crash
 * leaves either the whole previous record or the whole new one. That is what makes the plan's
 * three-step teardown — persist new epoch and journal, purge, then clear that entry — resumable.
 *
 * All record semantics live in [AccessEpochTransitions]; this class only reads, transforms and
 * writes. Keeping the logic out of here is what lets it be unit tested without Android, and what
 * keeps a test fake from drifting away from production behaviour.
 */
@Singleton
class DataStoreAccessEpochStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val ids: EpochIdGenerator
) : AccessEpochStore {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        ids: EpochIdGenerator
    ) : this(context.accessEpochDataStore, ids)

    override suspend fun load(): AccessEpochRecord =
        dataStore.data.first().toRecord()

    override suspend fun bindOwner(uid: String): AccessEpochRecord =
        transform { AccessEpochTransitions.bindOwner(it, uid, ids) }

    override suspend fun signOut(): AccessEpochRecord =
        transform { AccessEpochTransitions.signOut(it, ids) }

    override suspend fun retireUnverifiedStart(): AccessEpochRecord =
        transform { AccessEpochTransitions.retireUnverifiedStart(it, ids) }

    override suspend fun beginSignOut(uid: String): AccessEpochRecord =
        transform { AccessEpochTransitions.beginSignOut(it, uid) }

    override suspend fun beginRotation(
        rotateUser: Boolean,
        rotateKrx: Boolean
    ): AccessEpochRecord =
        transform { AccessEpochTransitions.rotate(it, rotateUser, rotateKrx, ids) }

    override suspend fun completePurges(completed: Collection<PendingPurge>): AccessEpochRecord =
        transform { AccessEpochTransitions.completePurges(it, completed) }

    override suspend fun markMayContainData(premium: Boolean, krx: Boolean): AccessEpochRecord =
        transform { AccessEpochTransitions.markMayContainData(it, premium, krx) }

    /** Read, transform and write inside one atomic `edit`. */
    private suspend fun transform(
        block: (AccessEpochRecord) -> AccessEpochRecord
    ): AccessEpochRecord =
        dataStore.edit { prefs -> prefs.write(block(prefs.toRecord())) }
            .toRecord()

    private fun Preferences.toRecord(): AccessEpochRecord = AccessEpochRecord(
        ownerUid = this[OWNER_UID],
        userAccessEpoch = this[USER_EPOCH],
        krxCapabilityEpoch = this[KRX_EPOCH],
        mayContainPremiumData = this[MAY_CONTAIN_PREMIUM] ?: false,
        mayContainKrxData = this[MAY_CONTAIN_KRX] ?: false,
        teardownOwedFor = this[TEARDOWN_OWED_FOR],
        // No filtering before decode: the writer removes the key when there is nothing owed, so a
        // key holding an empty value — or a bare separator — is damage, not an empty journal.
        pendingPurges = this[PURGE_JOURNAL]
            ?.split(ENTRY_SEPARATOR)
            ?.map { decodeEntry(it) }
            .orEmpty()
    )


    /**
     * An entry that cannot be read is an obligation whose target is unknown, not one that is gone.
     *
     * Dropping it would forget a purge the last process owed, and the journal is the only record of
     * it. So a malformed entry — wrong field count, or a scope name this build does not know —
     * becomes [UNKNOWN_OBLIGATION]: both axes, no owner, no past epoch. That is the widest form,
     * and it still reaches only data outside the live namespace.
     *
     * A scope list this build only partly understands is widened rather than narrowed: keeping the
     * names it recognised would silently drop whatever the unrecognised one stood for. An empty name
     * counts as unrecognised for the same reason — `USER,` is not a USER-only entry.
     */
    private fun decodeEntry(raw: String): PendingPurge {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size != 4) return UNKNOWN_OBLIGATION
        val names = parts[3].split(SCOPE_SEPARATOR)
        val scopes = names.mapNotNull { name -> PurgeScope.entries.firstOrNull { it.name == name } }.toSet()
        if (scopes.isEmpty() || scopes.size != names.size) return UNKNOWN_OBLIGATION
        return PendingPurge(
            ownerUid = parts[0].ifEmpty { null },
            userAccessEpoch = parts[1].ifEmpty { null },
            krxCapabilityEpoch = parts[2].ifEmpty { null },
            scopes = scopes
        )
    }

    internal companion object {

        /** The replacement the corruption handler writes, encoded the way this store reads it. */
        fun recoveryPreferences(ids: EpochIdGenerator): Preferences =
            mutablePreferencesOf().apply { write(accessEpochRecoveryRecord(ids)) }

        /**
         * `owner|userEpoch|krxEpoch|scopes`, entries separated by newline.
         *
         * Firebase uids and the generated UUIDs contain neither separator; a value that somehow did
         * would corrupt the journal, so it is rejected rather than written.
         */
        fun PendingPurge.encode(): String {
            val parts = listOf(
                ownerUid.orEmpty(),
                userAccessEpoch.orEmpty(),
                krxCapabilityEpoch.orEmpty(),
                scopes.sortedBy { it.name }.joinToString(SCOPE_SEPARATOR) { it.name }
            )
            require(parts.none { it.contains(FIELD_SEPARATOR) || it.contains(ENTRY_SEPARATOR) }) {
                "purge journal fields must not contain the separators"
            }
            return parts.joinToString(FIELD_SEPARATOR)
        }

        fun MutablePreferences.write(record: AccessEpochRecord) {
            putOrRemove(OWNER_UID, record.ownerUid)
            putOrRemove(USER_EPOCH, record.userAccessEpoch)
            putOrRemove(KRX_EPOCH, record.krxCapabilityEpoch)
            putOrRemove(TEARDOWN_OWED_FOR, record.teardownOwedFor)
            this[MAY_CONTAIN_PREMIUM] = record.mayContainPremiumData
            this[MAY_CONTAIN_KRX] = record.mayContainKrxData
            if (record.pendingPurges.isEmpty()) {
                remove(PURGE_JOURNAL)
            } else {
                this[PURGE_JOURNAL] = record.pendingPurges.joinToString(ENTRY_SEPARATOR) { it.encode() }
            }
        }

        private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
            if (value == null) remove(key) else this[key] = value
        }

        /** Everything a purger owns except the live namespace: no owner, no past epoch, both axes. */
        val UNKNOWN_OBLIGATION = PendingPurge(
            ownerUid = null,
            userAccessEpoch = null,
            krxCapabilityEpoch = null,
            scopes = AccessEpochTransitions.ALL_SCOPES
        )

        const val ENTRY_SEPARATOR = "\n"
        const val FIELD_SEPARATOR = "|"
        const val SCOPE_SEPARATOR = ","
        val OWNER_UID = stringPreferencesKey("owner_uid")
        val USER_EPOCH = stringPreferencesKey("user_access_epoch")
        val KRX_EPOCH = stringPreferencesKey("krx_capability_epoch")
        val MAY_CONTAIN_PREMIUM = booleanPreferencesKey("may_contain_premium_data")
        val MAY_CONTAIN_KRX = booleanPreferencesKey("may_contain_krx_data")
        val PURGE_JOURNAL = stringPreferencesKey("pending_purge_journal")
        val TEARDOWN_OWED_FOR = stringPreferencesKey("teardown_owed_for")
    }
}
