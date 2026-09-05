package com.jay.fxi.data.entitlements

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
    name = ACCESS_EPOCH_STORE_NAME
)

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
class DataStoreAccessEpochStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ids: EpochIdGenerator
) : AccessEpochStore {

    override suspend fun load(): AccessEpochRecord =
        context.accessEpochDataStore.data.first().toRecord()

    override suspend fun bindOwner(uid: String): AccessEpochRecord =
        transform { AccessEpochTransitions.bindOwner(it, uid, ids) }

    override suspend fun signOut(): AccessEpochRecord =
        transform { AccessEpochTransitions.signOut(it, ids) }

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
        context.accessEpochDataStore.edit { prefs -> prefs.write(block(prefs.toRecord())) }
            .toRecord()

    private fun MutablePreferences.write(record: AccessEpochRecord) {
        putOrRemove(OWNER_UID, record.ownerUid)
        putOrRemove(USER_EPOCH, record.userAccessEpoch)
        putOrRemove(KRX_EPOCH, record.krxCapabilityEpoch)
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

    private fun Preferences.toRecord(): AccessEpochRecord = AccessEpochRecord(
        ownerUid = this[OWNER_UID],
        userAccessEpoch = this[USER_EPOCH],
        krxCapabilityEpoch = this[KRX_EPOCH],
        mayContainPremiumData = this[MAY_CONTAIN_PREMIUM] ?: false,
        mayContainKrxData = this[MAY_CONTAIN_KRX] ?: false,
        pendingPurges = this[PURGE_JOURNAL]
            ?.split(ENTRY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { decodeEntry(it) }
            .orEmpty()
    )

    /**
     * `owner|userEpoch|krxEpoch|scopes`, entries separated by newline.
     *
     * Firebase uids and the generated UUIDs contain neither separator; a value that somehow did
     * would corrupt the journal, so it is rejected rather than written.
     */
    private fun PendingPurge.encode(): String {
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

    private fun decodeEntry(raw: String): PendingPurge? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size != 4) return null
        val scopes = parts[3].split(SCOPE_SEPARATOR)
            .filter { it.isNotEmpty() }
            .mapNotNull { name -> PurgeScope.entries.firstOrNull { it.name == name } }
            .toSet()
        if (scopes.isEmpty()) return null
        return PendingPurge(
            ownerUid = parts[0].ifEmpty { null },
            userAccessEpoch = parts[1].ifEmpty { null },
            krxCapabilityEpoch = parts[2].ifEmpty { null },
            scopes = scopes
        )
    }

    private companion object {
        const val ENTRY_SEPARATOR = "\n"
        const val FIELD_SEPARATOR = "|"
        const val SCOPE_SEPARATOR = ","
        val OWNER_UID = stringPreferencesKey("owner_uid")
        val USER_EPOCH = stringPreferencesKey("user_access_epoch")
        val KRX_EPOCH = stringPreferencesKey("krx_capability_epoch")
        val MAY_CONTAIN_PREMIUM = booleanPreferencesKey("may_contain_premium_data")
        val MAY_CONTAIN_KRX = booleanPreferencesKey("may_contain_krx_data")
        val PURGE_JOURNAL = stringPreferencesKey("pending_purge_journal")
    }
}
