package com.jay.fxi.data.entitlements.control

import java.io.IOException
import java.util.Collections

/** Caller-declared closure of work related to the selected previous-lifetime commands. */
internal class PreviousReclamationClosure(
    commandIds: Set<String>,
    val ownerTrackingLifetimeId: OwnerTrackingLifetimeId,
    val owner: String,
    val generationAtCapture: Long,
    val currentGeneration: Long,
    captured: Set<String>,
    joined: Set<String>,
    registered: Set<String>,
    val entriesClosed: Boolean,
    val receiptsClosed: Boolean,
    val oldScopeClosed: Boolean,
    val recoveryOwned: Boolean
) {
    val commandIds: Set<String> = Collections.unmodifiableSet(commandIds.toSet())
    val captured: Set<String> = Collections.unmodifiableSet(captured.toSet())
    val joined: Set<String> = Collections.unmodifiableSet(joined.toSet())
    val registered: Set<String> = Collections.unmodifiableSet(registered.toSet())

    fun violation(selection: PreviousEvidenceSelection,
        currentLifetime: OwnerTrackingLifetimeId): PreviousReclamationClosureViolation? {
        if (commandIds != selection.items.map { it.commandId }.toSet())
            return PreviousReclamationClosureViolation.SelectionMismatch
        if (ownerTrackingLifetimeId !== currentLifetime)
            return PreviousReclamationClosureViolation.LifetimeMismatch
        if (generationAtCapture != currentGeneration)
            return PreviousReclamationClosureViolation.GenerationMismatch
        if (!entriesClosed) return PreviousReclamationClosureViolation.EntriesOpen
        if (captured != joined) return PreviousReclamationClosureViolation.CapturedJoinedMismatch
        if (registered != captured) return PreviousReclamationClosureViolation.RegisteredMismatch
        if (!receiptsClosed) return PreviousReclamationClosureViolation.ReceiptsOpen
        if (!oldScopeClosed) return PreviousReclamationClosureViolation.OldScopeOpen
        if (!recoveryOwned) return PreviousReclamationClosureViolation.RecoveryUnowned
        if (owner.isBlank()) return PreviousReclamationClosureViolation.OwnerMissing
        return null
    }
}

internal enum class PreviousReclamationClosureViolation {
    SelectionMismatch, LifetimeMismatch, GenerationMismatch, EntriesOpen, CapturedJoinedMismatch,
    RegisteredMismatch, ReceiptsOpen, OldScopeOpen, RecoveryUnowned, OwnerMissing
}

internal enum class PreviousReclamationDisposition { RemovedNow, AlreadyAbsent }

internal sealed interface PreviousReclamationRejectionReason {
    data class InvalidSelection(val detail: String) : PreviousReclamationRejectionReason
    data object UnsupportedInThisUnit : PreviousReclamationRejectionReason
    data class ClosureNotSatisfied(val violation: PreviousReclamationClosureViolation) : PreviousReclamationRejectionReason
    data class DependencyPresent(val dependentCommandId: String, val dependentLifetimeId: String,
        val atom: DependencyAtom) : PreviousReclamationRejectionReason
    data class DependencyUnknown(val dependentCommandId: String?, val dependentLifetimeId: String?,
        val source: DependencyGapSource?) : PreviousReclamationRejectionReason
    data class Encoding(val reason: RejectionReason) : PreviousReclamationRejectionReason
}

internal sealed interface PreviousEvidenceReclamationResult {
    val localUnresolvedCommands: Set<CommandRef>
    val localPendingReleases: Set<CommandRef>

    data class Reclaimed(val snapshot: ConfirmedControlSnapshot, val proof: ConfirmationProof,
        val selectedOperationIds: List<String>, val disposition: PreviousReclamationDisposition,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>) : PreviousEvidenceReclamationResult

    data class Rejected(val reason: PreviousReclamationRejectionReason, val observation: ControlRecordRead?,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>) : PreviousEvidenceReclamationResult

    data class Conflict(val reason: ConflictReason, val observation: ControlRecordRead.Supported,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>) : PreviousEvidenceReclamationResult

    data class RecoveryRequired(val reason: RecoveryReason, val observation: ControlRecordRead,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>) : PreviousEvidenceReclamationResult

    data class Unconfirmed(val observation: ControlRecordRead?, val failure: IOException,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>) : PreviousEvidenceReclamationResult
}
