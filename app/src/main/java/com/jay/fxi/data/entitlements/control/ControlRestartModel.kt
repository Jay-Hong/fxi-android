package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead

/** The first identity observation of a restarted process, in the existing priority vocabulary (D23, DeletionPending). */
enum class RestartIdentity { SAME_UID, NO_UID, UID_CHANGED, DELETION_PENDING }

/** A restart read either came with durable confirmation or it did not; an unconfirmed read decides nothing. */
sealed interface RestartRead {
    data object Unconfirmed : RestartRead
    data class Confirmed(val snapshot: PurgeControlSnapshotRead) : RestartRead
}

/**
 * The next piece of restart work (purger 설계 v3 final §7 재시작 순서, P2). There is no `Open` step: the best outcome
 * is [AwaitFreshApproval], which still needs a fresh server approval before any protected use.
 */
sealed interface RestartStep {
    data object ReRead : RestartStep
    data class Blocked(val reasons: Set<PurgeControlSnapshotRead.ClosedReason>) : RestartStep
    data class IdentityFirst(val identity: RestartIdentity) : RestartStep
    data class IdentityWithLegacyRecovery(val identity: RestartIdentity) : RestartStep
    data object LegacySchemaAbsentRecovery : RestartStep
    data object SchemaUpgradeV1ToV2 : RestartStep
    data object ReclaimPreviousLifetimeEvidence : RestartStep
    data object RecoverIntent : RestartStep
    data object SettleSeal : RestartStep
    data object RecoverHold : RestartStep
    data object Purge : RestartStep
    data object AwaitFreshApproval : RestartStep
}

/** Test-only restart decision model (P2). Nothing in production calls it; wiring is P3. */
internal object ControlRestartModel {
    fun decide(read: RestartRead, identity: RestartIdentity): RestartStep {
        if (read is RestartRead.Unconfirmed) return RestartStep.ReRead
        val snapshot = (read as RestartRead.Confirmed).snapshot
        val blocked = (snapshot as? PurgeControlSnapshotRead.Closed)?.reasons.orEmpty().filterTo(mutableSetOf()) {
            it == PurgeControlSnapshotRead.ClosedReason.CONTROL_UNREADABLE ||
                it == PurgeControlSnapshotRead.ClosedReason.CONTROL_UNINTERPRETABLE_OBLIGATION ||
                it == PurgeControlSnapshotRead.ClosedReason.CONTROL_UNINTERPRETABLE_METADATA ||
                it == PurgeControlSnapshotRead.ClosedReason.JOURNAL_WRONG_TYPE ||
                it == PurgeControlSnapshotRead.ClosedReason.JOURNAL_UNINTERPRETABLE
        }
        if (blocked.isNotEmpty()) return RestartStep.Blocked(blocked)

        val control = snapshot.control
        if (control is ControlRecordRead.MigrationOrRecoveryRequired) {
            return when (identity) {
                RestartIdentity.NO_UID, RestartIdentity.UID_CHANGED -> RestartStep.IdentityWithLegacyRecovery(identity)
                RestartIdentity.DELETION_PENDING -> RestartStep.IdentityFirst(identity)
                RestartIdentity.SAME_UID -> RestartStep.LegacySchemaAbsentRecovery
            }
        }
        if (identity != RestartIdentity.SAME_UID) return RestartStep.IdentityFirst(identity)
        control as ControlRecordRead.Supported
        if (control.schemaVersion == 1) return RestartStep.SchemaUpgradeV1ToV2

        val evidence = (control.metadata as ControlMetadataRead.V2).evidence.entries
        if (evidence.any {
                it is ControlEvidenceEntryRead.Interpreted &&
                    (it.value is AppliedEvidence.Mutations || it.value is AppliedEvidence.Rotation)
            }) return RestartStep.ReclaimPreviousLifetimeEvidence
        if (control.arrays.getValue(ControlKind.RECOVERY_INTENT).entries.isNotEmpty()) return RestartStep.RecoverIntent
        if (control.arrays.getValue(ControlKind.SEAL).entries.isNotEmpty()) return RestartStep.SettleSeal
        if (control.arrays.getValue(ControlKind.HOLD).entries.isNotEmpty()) return RestartStep.RecoverHold
        if (snapshot.journal is PurgeControlSnapshotRead.Journal.Present) return RestartStep.Purge
        return RestartStep.AwaitFreshApproval
    }
}
