package com.jay.fxi.data.entitlements.control

/** Pure admission only. The future facade must acquire the shared lease and re-read identity/state. */
internal object ControlCommandReleaseEligibility {
    sealed interface Decision {
        data object Eligible : Decision
        data object AlreadyReleased : Decision
        data class Rejected(val reason: ReleaseRejectionReason) : Decision
    }

    fun decide(
        command: CommandRef,
        lifetime: OwnerTrackingLifetimeId,
        registered: TrackedControlCommand?,
        inFlight: Boolean,
        unresolved: Boolean
    ): Decision {
        fun reject(reason: ReleaseRejectionReason) = Decision.Rejected(reason)
        if (command.ownerTrackingLifetimeId !== lifetime) return reject(ReleaseRejectionReason.WrongTrackerLifetime)
        if (command.lifecycleState == ControlCommandLifecycle.RELEASED) return Decision.AlreadyReleased
        if (inFlight) return reject(ReleaseRejectionReason.InFlight)
        if (registered?.command !== command) return reject(ReleaseRejectionReason.NotRegisteredIdentity)
        if (command.body !is ControlCommandBody.Mutations) return reject(ReleaseRejectionReason.UnsupportedCommandKind)
        if (command.lifecycleState == ControlCommandLifecycle.RELEASE_PENDING) {
            check(!unresolved) { "pending release is also business unresolved" }
            checkNotNull(registered.releaseDescriptor) { "pending release has no descriptor" }
        } else {
            if (unresolved) return reject(ReleaseRejectionReason.Unresolved)
            if (!registered.confirmed.get()) return reject(ReleaseRejectionReason.NotConfirmed)
        }
        return Decision.Eligible
    }
}

/** Exact persisted linkage, including all write flags. There is deliberately no null wildcard. */
internal object ControlReleaseEvidenceMatch {
    fun matches(command: CommandRef, expected: AppliedEvidence?, actual: AppliedEvidence): Boolean {
        if (command.body !is ControlCommandBody.Mutations) return false
        if (expected !is AppliedEvidence.Mutations) return false
        if (expected.commandId != command.id) return false
        if (expected.ownerTrackingLifetimeId != command.ownerTrackingLifetimeId.value) return false
        if (actual !is AppliedEvidence.Mutations) return false
        if (actual.commandId != expected.commandId) return false
        if (actual.ownerTrackingLifetimeId != expected.ownerTrackingLifetimeId) return false
        if (actual.targets.size != expected.targets.size) return false
        return actual.targets.zip(expected.targets).all { (row, fixed) ->
            row.index == fixed.index && row.kind == fixed.kind && row.id == fixed.id &&
                row.joined == fixed.joined && row.written == fixed.written
        }
    }
}

/**
 * Pure latest-record release judgement, with no candidate construction, observation or mutation.
 * Unit 2 must observe the actual read (including interpretable own evidence) before calling this,
 * then validate/encode a removal candidate before publishing the returned descriptor as pending.
 * Ready is not storage confirmation and must never be exposed as a successful release.
 */
internal object ControlCommandReleaseDecision {
    sealed interface Decision {
        data class Ready(val descriptor: ReleasePendingDescriptor) : Decision
        data class Conflict(val reason: ConflictReason) : Decision
        data class RecoveryRequired(val reason: RecoveryReason) : Decision
    }

    fun decide(read: ControlRecordRead, tracked: TrackedControlCommand): Decision {
        fun recovery(reason: RecoveryReason) = Decision.RecoveryRequired(reason)
        fun mismatch() = Decision.Conflict(ConflictReason.CommandEvidenceMismatch)
        val command = tracked.command
        val state = command.lifecycleState
        check(state != ControlCommandLifecycle.RELEASED) { "released ref needs no record decision" }
        check(command.body is ControlCommandBody.Mutations) { "release requires mutations" }
        val pending = if (state == ControlCommandLifecycle.RELEASE_PENDING) {
            checkNotNull(tracked.releaseDescriptor) { "pending release has no descriptor" }
        } else null
        if (read !is ControlRecordRead.Supported) return recovery(
            if (read is ControlRecordRead.MigrationOrRecoveryRequired) RecoveryReason.MigrationOrRecovery
            else RecoveryReason.UnreadableRecord)
        if (read.schemaVersion != 2) return recovery(RecoveryReason.ControlSchemaMigrationRequired)
        if (read.hasUninterpretableMetadata) return recovery(RecoveryReason.UninterpretableMetadata)
        if (read.hasUninterpretable) return recovery(RecoveryReason.UninterpretableObligations)

        val own = ControlAppliedEvidence.own(read, command)
        if (pending != null) {
            when (pending) {
                is ReleasePendingDescriptor.ExactMutations -> {
                    if (own != null && !ControlReleaseEvidenceMatch.matches(command, pending.row, own)) return mismatch()
                }
                ReleasePendingDescriptor.ConfirmedWithoutApplied -> if (own != null) return mismatch()
            }
            // A pending retry confirms current absence even after a discontinuity or external removal.
            return Decision.Ready(pending)
        }
        if (own != null) {
            if (!ControlReleaseEvidenceMatch.matches(command, tracked.expectedApplied, own)) return mismatch()
            return Decision.Ready(ReleasePendingDescriptor.ExactMutations(own as AppliedEvidence.Mutations))
        }
        if (tracked.observedApplied.get()) return recovery(RecoveryReason.CommandEvidenceLost)
        if (tracked.expectedApplied != null) return recovery(RecoveryReason.CommandEvidenceLost)
        return Decision.Ready(ReleasePendingDescriptor.ConfirmedWithoutApplied)
    }
}
