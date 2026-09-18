package com.jay.fxi.data.entitlements

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Atomicity comes from `DataStore.updateData`, which writes a temporary file and renames it, so a crash
 * leaves either the whole previous record or the whole new one. That is what makes the plan's
 * three-step teardown — persist new epoch and journal, purge, then clear that entry — resumable.
 *
 * All record semantics live in [AccessEpochTransitions]; this class only reads, transforms and
 * writes. Keeping the logic out of here is what lets it be unit tested without Android, and what
 * keeps a test fake from drifting away from production behaviour.
 *
 * A write that throws can leave `data` showing a value that is not on disk. DataStore 1.1.7 updates
 * its in-memory copy inside the write scope and renames the scratch file after it
 * (`DataStoreImpl.writeData`, `FileStorage.writeScope`), and `data` serves that copy while its version
 * is current. A failure injected at that point in `DataStoreAccessEpochStoreReadBackTest` leaves `data`
 * returning the rotation the reopened file does not have. An `edit` transforms what it reads from the
 * file under the write lock. If the value changes, it returns only after the write scope, including
 * the rename, completes; an unchanged value skips the write. So after any read or write that did not
 * return normally, [load] answers with the result of an `edit` that changes [READ_BARRIER], and keeps
 * doing so until one returns normally. Every read and write goes through one lock, so no read can
 * reach the in-memory copy of a write still in flight.
 *
 * A completed write scope is as far as this goes. DataStore syncs the scratch file but not its
 * directory, which `FileStorage` leaves as a TODO noting that a badly timed crash could revert to the
 * previous state.
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

    private val lock = Mutex()

    /**
     * Whether `data` may hold a write that never reached disk. Guarded by [lock].
     *
     * Cleared only by an owner-added barrier update that returned (and, for [load], decoded).
     * Legacy mutations and internal observations never clear it: an unchanged update skips the write
     * and leaves the copy as it was. This is cache confirmation, not command or domain confirmation.
     */
    private var readBackUnverified = false

    override suspend fun load(): AccessEpochRecord = locked {
        if (readBackUnverified) {
            val written = updateRecordLocked(
                transform = { it.withReadBarrier() },
                read = { it.toRecord() }
            )
            readBackUnverified = false
            written
        } else {
            dataStore.data.first().toRecord()
        }
    }

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

    override suspend fun journalRetired(obligation: LossObligation): AccessEpochRecord =
        transform { AccessEpochTransitions.journalRetired(it, obligation) }

    override suspend fun markMayContainData(premium: Boolean, krx: Boolean): AccessEpochRecord =
        transform { AccessEpochTransitions.markMayContainData(it, premium, krx) }

    /**
     * The only internal record transaction entry point. Consumers share this owner, never a raw
     * DataStore, a second lock, or a separate cache-confirmation flag. [decide] runs synchronously
     * against a frozen snapshot inside the atomic update; it must not call back into this store.
     *
     * A non-normal return, including cancellation or a corruption-replacement failure before
     * [decide], leaves the same read-back obligation that [load] honours. Results escape only after
     * the update completes. Domain validation remains the caller's responsibility.
     */
    internal suspend fun <T> transactRecord(
        decide: (Preferences) -> RecordTransactionDecision<T>
    ): RecordTransactionResult<T> = locked {
        lateinit var decision: RecordTransactionDecision<T>
        var evidence = RecordTransactionEvidence.LockedFileRead
        var confirmsReadBack = false
        val result = updateRecordLocked(
            transform = { current ->
                val snapshot = current.toPreferences()
                decision = decide(snapshot)
                when (val chosen = decision) {
                    is RecordTransactionDecision.Observe -> current
                    is RecordTransactionDecision.Confirm -> {
                        val candidate = chosen.candidate.toPreferences()
                        // Compare raw values so a mistyped reserved key cannot evade ownership.
                        require(candidate.asMap()[READ_BARRIER] == snapshot.asMap()[READ_BARRIER]) {
                            "read_barrier is owned by DataStoreAccessEpochStore"
                        }
                        confirmsReadBack = readBackUnverified
                        val next = if (confirmsReadBack) candidate.withReadBarrier() else candidate
                        if (next != current) evidence = RecordTransactionEvidence.CompletedWriteScope
                        next
                    }
                }
            },
            read = { RecordTransactionResult(decision.value, it.toPreferences(), evidence) }
        )
        if (confirmsReadBack) readBackUnverified = false
        result
    }

    /** Read, transform and write inside one atomic update; retain the legacy confirmation policy. */
    private suspend fun transform(
        block: (AccessEpochRecord) -> AccessEpochRecord
    ): AccessEpochRecord = locked {
        updateRecordLocked(
            transform = { prefs -> prefs.toMutablePreferences().apply { write(block(toRecord())) } },
            read = { it.toRecord() }
        )
    }

    /** All updates and their result decoding stay inside the owner's [lock]. */
    private suspend fun <T> updateRecordLocked(
        transform: (Preferences) -> Preferences,
        read: (Preferences) -> T
    ): T = read(dataStore.updateData { transform(it) })

    private fun Preferences.withReadBarrier(): Preferences = toMutablePreferences().apply {
        this[READ_BARRIER] = (this[READ_BARRIER] ?: 0L) + 1L
    }

    /**
     * Runs [block] under [lock]; anything but a normal return marks the read-back unverified before the
     * lock is released. A cancelled caller counts: the edit it was waiting on can still be in flight.
     */
    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock {
        var returned = false
        try {
            block().also { returned = true }
        } finally {
            if (!returned) readBackUnverified = true
        }
    }

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

        /** Store-internal: changed only to force a write whose result [load] can trust. Not part of the record. */
        val READ_BARRIER = longPreferencesKey("read_barrier")
    }
}
