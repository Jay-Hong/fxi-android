package com.jay.fxi.data.entitlements.control

import java.io.IOException
import java.util.Collections

internal enum class CompletionMode { Consumed, NeverSubmitted, ResponsibilityTransferred }
internal enum class TerminationEntry { AbandonBeforeFirstConfirm, ConsumedRotation, ConsumedLifecycle, ConsumedSettlement, UncertainMutations, UncertainRotation }

/** The caller declares both business handoff conditions before evidence can be consumed. */
internal class RotationConsumption(
    val resultConsumed: Boolean,
    val followUpCompletedOrDurablyOwned: Boolean
)

internal enum class NeverConfirmViolation {
    ConfirmationRequested, FirstConfirmBound, Confirmed, ExpectedApplied, ObservedApplied, TerminationDescriptorBound
}

internal enum class ClosureViolation {
    CommandMismatch, LifetimeMismatch, RelatedScopeMismatch, EntriesOpen, CapturedJoinedMismatch,
    RegisteredMismatch, GenerationMismatch, ReceiptsOpen, OwnerMissing, OwnerMismatch, WorkSetChanged
}

internal sealed interface CompletionRejectionReason {
    data object WrongTrackerLifetime : CompletionRejectionReason
    data object NotRegisteredIdentity : CompletionRejectionReason
    data object InFlight : CompletionRejectionReason
    data object OtherManagementPath : CompletionRejectionReason
    data object NotConfirmed : CompletionRejectionReason
    data object Unresolved : CompletionRejectionReason
    data object NotUnresolved : CompletionRejectionReason
    data object NotOnceConfirm : CompletionRejectionReason
    data object DeclarationRefMismatch : CompletionRejectionReason
    data object ConsumptionNotDeclared : CompletionRejectionReason
    data class NotNeverConfirm(val violation: NeverConfirmViolation) : CompletionRejectionReason
    data class ClosureNotSatisfied(val violation: ClosureViolation) : CompletionRejectionReason
    data class DependencyPresent(
        val dependentCommandId: String,
        val dependentLifetimeId: String,
        val atom: DependencyAtom
    ) : CompletionRejectionReason
    data class DependencyUnknown(
        val dependentCommandId: String?,
        val dependentLifetimeId: String?,
        val source: DependencyGapSource?
    ) : CompletionRejectionReason
    data object NotTerminationPending : CompletionRejectionReason
    data object UnsupportedInThisUnit : CompletionRejectionReason
    data class Encoding(val reason: RejectionReason) : CompletionRejectionReason
    data class Clock(val reason: BootReadingRefusal) : CompletionRejectionReason
    class G05(failures: List<G05Failure>) : CompletionRejectionReason {
        val failures: List<G05Failure> = Collections.unmodifiableList(failures.toList())
        override fun equals(other: Any?) = other is G05 && other.failures == failures
        override fun hashCode() = failures.hashCode()
        override fun toString() = "G05(failures=$failures)"
    }
}

/** A caller declaration, retained only as small immutable values at the pending boundary. */
internal class TerminationClosure(
    val command: CommandRef,
    val ownerTrackingLifetimeId: OwnerTrackingLifetimeId,
    val relatedScope: String,
    val generationAtCapture: Long,
    val currentGeneration: Long,
    val entriesClosed: Boolean,
    captured: Set<String>,
    joined: Set<String>,
    registered: Set<String>,
    val receiptsClosed: Boolean,
    val owner: String
) {
    val captured: Set<String> = Collections.unmodifiableSet(captured.toSet())
    val joined: Set<String> = Collections.unmodifiableSet(joined.toSet())
    val registered: Set<String> = Collections.unmodifiableSet(registered.toSet())

    fun violation(command: CommandRef, lifetime: OwnerTrackingLifetimeId,
        binding: TerminationClosureBinding? = null): ClosureViolation? {
        if (this.command !== command) return ClosureViolation.CommandMismatch
        if (ownerTrackingLifetimeId !== lifetime) return ClosureViolation.LifetimeMismatch
        if (relatedScope != command.id) return ClosureViolation.RelatedScopeMismatch
        if (generationAtCapture != currentGeneration) return ClosureViolation.GenerationMismatch
        if (!entriesClosed) return ClosureViolation.EntriesOpen
        if (captured != joined) return ClosureViolation.CapturedJoinedMismatch
        if (registered != captured) return ClosureViolation.RegisteredMismatch
        if (!receiptsClosed) return ClosureViolation.ReceiptsOpen
        if (owner.isBlank()) return ClosureViolation.OwnerMissing
        if (binding != null) {
            if (binding.command !== command) return ClosureViolation.CommandMismatch
            if (binding.ownerTrackingLifetimeId !== lifetime) return ClosureViolation.LifetimeMismatch
            if (relatedScope != binding.relatedScope) return ClosureViolation.RelatedScopeMismatch
            if (currentGeneration != binding.generation) return ClosureViolation.GenerationMismatch
            if (owner != binding.owner) return ClosureViolation.OwnerMismatch
            if (captured != binding.captured || joined != binding.joined || registered != binding.registered)
                return ClosureViolation.WorkSetChanged
        }
        return null
    }

    fun binding(): TerminationClosureBinding = TerminationClosureBinding(command, ownerTrackingLifetimeId,
        relatedScope, currentGeneration, entriesClosed, captured, joined, registered, receiptsClosed, owner)
}

internal class TerminationClosureBinding(
    val command: CommandRef,
    val ownerTrackingLifetimeId: OwnerTrackingLifetimeId,
    val relatedScope: String,
    val generation: Long,
    val entriesClosed: Boolean,
    captured: Set<String>,
    joined: Set<String>,
    registered: Set<String>,
    val receiptsClosed: Boolean,
    val owner: String
) {
    val captured: Set<String> = Collections.unmodifiableSet(captured.toSet())
    val joined: Set<String> = Collections.unmodifiableSet(joined.toSet())
    val registered: Set<String> = Collections.unmodifiableSet(registered.toSet())
}

internal sealed interface TerminationPendingDescriptor {
    val mode: CompletionMode

    /** 6-4bC1a: an OnceConfirm·U Mutations handoff; expectedOwn null = own absence verified at the first owner read. */
    class MutationsHandoff(
        val closureBinding: TerminationClosureBinding,
        val expectedOwn: AppliedEvidence.Mutations?,
        val deletionIdentity: DependencyAtom.AppliedRow
    ) : TerminationPendingDescriptor {
        override val mode: CompletionMode = CompletionMode.ResponsibilityTransferred
        val entry: TerminationEntry = TerminationEntry.UncertainMutations
    }

    /**
     * 6-4bC2a: an OnceConfirm·U rotation handoff. expectedOwn null = the own row and every seal of the operation were absent at
     * the first owner read (the retry then accepts only whole absence). The seal ids are the fixed input order.
     */
    class RotationHandoff(
        val closureBinding: TerminationClosureBinding,
        val expectedOwn: AppliedEvidence.Rotation?,
        val operationId: String,
        orderedSealIds: List<String>,
        val deletionIdentity: DependencyAtom.AppliedRow
    ) : TerminationPendingDescriptor {
        override val mode: CompletionMode = CompletionMode.ResponsibilityTransferred
        val entry: TerminationEntry = TerminationEntry.UncertainRotation
        val orderedSealIds: List<String> = Collections.unmodifiableList(orderedSealIds.toList())
    }

    data class EvidenceAbsent(
        override val mode: CompletionMode,
        val entry: TerminationEntry,
        val closureBinding: TerminationClosureBinding
    ) : TerminationPendingDescriptor

    class ExactEvidenceAndSeals(
        override val mode: CompletionMode,
        val entry: TerminationEntry,
        val closureBinding: TerminationClosureBinding,
        val expectedRotation: AppliedEvidence.Rotation,
        val operationId: String,
        orderedSealIds: List<String>
    ) : TerminationPendingDescriptor {
        val orderedSealIds: List<String> = Collections.unmodifiableList(orderedSealIds.toList())
    }

    class ExactSettlementEvidenceAndSeals(
        override val mode: CompletionMode,
        val entry: TerminationEntry,
        val closureBinding: TerminationClosureBinding,
        val expectedSettlement: AppliedEvidence.Settlement,
        val transition: HandoverSettlementTransition,
        val operationId: String,
        orderedSealIds: List<String>
    ) : TerminationPendingDescriptor {
        val orderedSealIds: List<String> = Collections.unmodifiableList(orderedSealIds.toList())
    }

    class ExactLifecycleEvidence(
        override val mode: CompletionMode,
        val entry: TerminationEntry,
        val closureBinding: TerminationClosureBinding,
        val expectedLifecycle: AppliedEvidence.Lifecycle
    ) : TerminationPendingDescriptor
}

internal sealed interface ControlCompletionResult {
    val command: CommandRef
    val localUnresolvedCommands: Set<CommandRef>
    val localPendingReleases: Set<CommandRef>

    data class Completed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val mode: CompletionMode,
        val snapshot: ConfirmedControlSnapshot,
        val proof: ConfirmationProof
    ) : ControlCompletionResult

    data class AlreadyTerminated(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlCompletionResult

    data class Rejected(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: CompletionRejectionReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead?
    ) : ControlCompletionResult

    data class Conflict(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: ConflictReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead.Supported
    ) : ControlCompletionResult

    data class RecoveryRequired(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RecoveryReason,
        val state: ControlCommandLifecycle,
        val observation: ControlRecordRead
    ) : ControlCompletionResult

    data class Unconfirmed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val state: ControlCommandLifecycle,
        val phase: ControlAttemptPhase,
        val observation: ControlRecordRead?,
        val failure: IOException
    ) : ControlCompletionResult
}
