package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.purge.JournalEntry
import com.jay.fxi.data.entitlements.purge.PurgeJournalCodec

/** Explicit, test-only recovery of the ambiguous all-control-keys-absent record. */
internal object RecoverSchemaAbsentLegacy {
    fun decide(read: ControlRecordRead, freshEpochs: RecoveryFreshEpochs): RecordTransactionDecision<RecoveryReason?> {
        if (read is ControlRecordRead.Unreadable) return recovery(RecoveryReason.UnreadableRecord)
        if (read is ControlRecordRead.Supported) return when (read.schemaVersion) {
            1 -> recovery(RecoveryReason.ControlSchemaMigrationRequired)
            2 -> if (retryIsConfirmable(read)) RecordTransactionDecision.Confirm(read.original, null)
                else recovery(RecoveryReason.MigrationOrRecovery)
            else -> recovery(RecoveryReason.UnreadableRecord)
        }
        val original = read.original
        if (!original.validType<String>(OWNER_UID) || !original.validType<String>(USER_EPOCH) ||
            !original.validType<String>(KRX_EPOCH) || !original.validType<Boolean>(MAY_CONTAIN_PREMIUM) ||
            !original.validType<Boolean>(MAY_CONTAIN_KRX) || !original.validType<String>(PURGE_JOURNAL))
            return recovery(RecoveryReason.UnreadableEpochState)

        val owner = original[OWNER_UID]
        val user = original[USER_EPOCH]
        val capability = original[KRX_EPOCH]
        if (!RecoveryRetirementBoundary.journalFieldRepresentable(owner) ||
            !RecoveryRetirementBoundary.journalFieldRepresentable(user) ||
            !RecoveryRetirementBoundary.journalFieldRepresentable(capability))
            return recovery(RecoveryReason.UnreadableEpochState)
        val prior = original[PURGE_JOURNAL]
        val entries = prior?.let(PurgeJournalCodec::decodeAll).orEmpty()
        if (entries.any { it is JournalEntry.Uninterpretable }) return recovery(RecoveryReason.JournalMigrationRequired)

        val nextUser = freshEpochs.user
        val nextCapability = freshEpochs.capability
        val reserved = listOfNotNull(user, capability) + entries.filterIsInstance<JournalEntry.Owed>().flatMap {
            listOfNotNull(it.pending.userAccessEpoch, it.pending.krxCapabilityEpoch)
        }
        if (nextUser == null || nextCapability == null ||
            !RecoveryRetirementBoundary.canonicalUuid(nextUser) ||
            !RecoveryRetirementBoundary.canonicalUuid(nextCapability) ||
            nextUser == nextCapability || nextUser in reserved || nextCapability in reserved)
            return recovery(RecoveryReason.UnreadableEpochState)

        val missing = PurgeScope.entries.filter { axis ->
            entries.filterIsInstance<JournalEntry.Owed>().none { covers(it.pending, owner, user, capability, axis) }
        }.toSet()
        val candidate = original.toMutablePreferences()
        candidate[USER_EPOCH] = nextUser
        candidate[KRX_EPOCH] = nextCapability
        candidate[MAY_CONTAIN_PREMIUM] = false
        candidate[MAY_CONTAIN_KRX] = false
        if (missing.isNotEmpty()) {
            val added = PendingPurge(owner, user, capability, missing).encode()
            candidate[PURGE_JOURNAL] = listOfNotNull(prior, added).joinToString(ENTRY_SEPARATOR)
        }
        candidate[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 2
        for (key in ControlRecordKeys.required(2)) candidate[ControlRecordKeys.payload(key)] = "[]"
        return RecordTransactionDecision.Confirm(candidate, null)
    }

    private fun covers(entry: PendingPurge, owner: String?, user: String?, capability: String?, axis: PurgeScope): Boolean {
        if (axis !in entry.scopes) return false
        if (entry.ownerUid != null && entry.ownerUid != owner) return false
        val recordedEpoch = if (axis == PurgeScope.USER) entry.userAccessEpoch else entry.krxCapabilityEpoch
        val targetEpoch = if (axis == PurgeScope.USER) user else capability
        return recordedEpoch == null || (targetEpoch != null && recordedEpoch == targetEpoch)
    }

    private fun retryIsConfirmable(read: ControlRecordRead.Supported): Boolean {
        val original = read.original
        if (!ControlRecordKeys.required(2).all { original[ControlRecordKeys.payload(it)] == "[]" }) return false
        if (!original.validType<String>(OWNER_UID) || !original.validType<String>(USER_EPOCH) ||
            !original.validType<String>(KRX_EPOCH) || !original.validType<Boolean>(MAY_CONTAIN_PREMIUM) ||
            !original.validType<Boolean>(MAY_CONTAIN_KRX) || !original.validType<String>(PURGE_JOURNAL)) return false
        val user = original[USER_EPOCH] ?: return false
        val capability = original[KRX_EPOCH] ?: return false
        val owner = original[OWNER_UID]
        if (!RecoveryRetirementBoundary.journalFieldRepresentable(owner)) return false
        if (!RecoveryRetirementBoundary.canonicalUuid(user) ||
            !RecoveryRetirementBoundary.canonicalUuid(capability) || user == capability ||
            original[MAY_CONTAIN_PREMIUM] != false || original[MAY_CONTAIN_KRX] != false) return false
        val journal = original[PURGE_JOURNAL] ?: return false
        val entries = PurgeJournalCodec.decodeAll(journal)
        if (entries.any { it is JournalEntry.Uninterpretable }) return false
        return PurgeScope.entries.all { axis ->
            entries.filterIsInstance<JournalEntry.Owed>().any { entry ->
                val pending = entry.pending
                val retiredEpoch = if (axis == PurgeScope.USER) pending.userAccessEpoch else pending.krxCapabilityEpoch
                val currentEpoch = if (axis == PurgeScope.USER) user else capability
                axis in pending.scopes && (pending.ownerUid == null || pending.ownerUid == owner) &&
                    (retiredEpoch == null || retiredEpoch != currentEpoch)
            }
        }
    }

    private fun recovery(reason: RecoveryReason): RecordTransactionDecision<RecoveryReason?> =
        RecordTransactionDecision.Observe(reason)
}
