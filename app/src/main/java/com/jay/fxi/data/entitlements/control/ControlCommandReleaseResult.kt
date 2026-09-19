package com.jay.fxi.data.entitlements.control

import java.io.IOException

/** Fixed at the pending boundary, never rebound on retry. No snapshot or owner is retained here. */
internal sealed interface ReleasePendingDescriptor {
    class ExactMutations(val row: AppliedEvidence.Mutations) : ReleasePendingDescriptor
    data object ConfirmedWithoutApplied : ReleasePendingDescriptor
}

internal sealed interface ReleaseRejectionReason {
    data object WrongTrackerLifetime : ReleaseRejectionReason
    data object NotRegisteredIdentity : ReleaseRejectionReason
    data object UnsupportedCommandKind : ReleaseRejectionReason
    data object InFlight : ReleaseRejectionReason
    data object Unresolved : ReleaseRejectionReason
    data object NotConfirmed : ReleaseRejectionReason
    data class Encoding(val reason: RejectionReason) : ReleaseRejectionReason
}

/**
 * Contract for releaseAfterConsumption(command: CommandRef), to be connected in unit 2.
 * Calling it will declare that the single consumer/retry owner has received Confirmed, completed
 * consumption and durable handoff, and joined all business work. It cannot verify that declaration.
 * No facade entry point is exposed until owner-confirmed reclamation can complete this contract.
 * Both local sets enumerate this owner's memory work, never global recovery or admission.
 */
internal sealed interface ControlCommandReleaseResult {
    val command: CommandRef
    val localUnresolvedCommands: Set<CommandRef>
    val localPendingReleases: Set<CommandRef>

    data class Released(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val snapshot: ConfirmedControlSnapshot,
        val proof: ConfirmationProof
    ) : ControlCommandReleaseResult

    /** Idempotent memory response; supplies no new storage evidence. */
    data class AlreadyReleased(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlCommandReleaseResult

    data class Rejected(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: ReleaseRejectionReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead?
    ) : ControlCommandReleaseResult

    data class Conflict(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: ConflictReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead.Supported
    ) : ControlCommandReleaseResult

    data class RecoveryRequired(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RecoveryReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead
    ) : ControlCommandReleaseResult

    data class Unconfirmed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val state: ControlCommandLifecycle,
        val phase: ControlAttemptPhase,
        val lastObservation: ControlRecordRead?,
        val failure: IOException
    ) : ControlCommandReleaseResult
}
