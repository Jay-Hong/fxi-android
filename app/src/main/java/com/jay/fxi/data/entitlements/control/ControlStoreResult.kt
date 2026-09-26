package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import java.util.concurrent.atomic.AtomicReference

internal enum class ControlCommandLifecycle { RETAINED, RELEASE_PENDING, RELEASED, TERMINATION_PENDING, TERMINATED }

internal data class RefCell(val state: ControlCommandLifecycle, val body: ControlCommandBody?)
internal data class RefView(val state: ControlCommandLifecycle, val body: ControlCommandBody?)

/** Prepared identity and fixed inputs, never evidence that an attempt executed. */
internal class CommandRef internal constructor(
    val id: String,
    body: ControlCommandBody,
    val ownerTrackingLifetimeId: OwnerTrackingLifetimeId
) {
    private val diagnostic = AtomicReference<ControlLifecycleDiagnostic?>(null)
    val lastLifecycleDiagnostic: ControlLifecycleDiagnostic? get() = diagnostic.get()
    internal fun observeLifecycleDiagnostic(value: ControlLifecycleDiagnostic) { diagnostic.set(value) }

    private val cell = AtomicReference(RefCell(ControlCommandLifecycle.RETAINED, body))
    val lifecycleState: ControlCommandLifecycle get() = cell.get().state
    val body: ControlCommandBody get() = cell.get().body
        ?: throw IllegalStateException("terminated command has no executable body")
    internal fun captureStateAndBody(): RefView = cell.get().let { RefView(it.state, it.body) }

    // Internal transition sites are pinned to the validated owner release path by source tripwires.
    // The cell itself never escapes; neither operation restores business execution authority.
    internal fun beginRelease() {
        val current = cell.get()
        check(current.state == ControlCommandLifecycle.RETAINED &&
            cell.compareAndSet(current, RefCell(ControlCommandLifecycle.RELEASE_PENDING, current.body)))
    }

    internal fun completeRelease() {
        val current = cell.get()
        check(current.state == ControlCommandLifecycle.RELEASE_PENDING &&
            cell.compareAndSet(current, RefCell(ControlCommandLifecycle.RELEASED, current.body)))
    }

    internal fun beginTermination() {
        val current = cell.get()
        check(current.state == ControlCommandLifecycle.RETAINED &&
            cell.compareAndSet(current, RefCell(ControlCommandLifecycle.TERMINATION_PENDING, current.body)))
    }

    internal fun completeTermination() {
        val current = cell.get()
        check(current.state == ControlCommandLifecycle.TERMINATION_PENDING &&
            cell.compareAndSet(current, RefCell(ControlCommandLifecycle.TERMINATED, null)))
    }

    init {
        require(body !is ControlCommandBody.Lifecycle || id == body.input.operationId) {
            "lifecycle command id must equal operationId"
        }
        require(body !is ControlCommandBody.Handover || id == body.input.operationId) {
            "handover command id must equal operationId"
        }
        require(body !is ControlCommandBody.RotateAndSettle || id == body.input.operationId) {
            "rotation command id must equal operationId"
        }
    }
    internal constructor(id: String, actions: List<ControlMutation>, lifetime: OwnerTrackingLifetimeId) :
        this(id, ControlCommandBody.Mutations(actions), lifetime)
    val actions: List<ControlMutation> get() = (body as? ControlCommandBody.Mutations)?.actions.orEmpty()
}

internal data class TargetExpectation(val command: CommandRef, val effectiveIds: List<String?>)

/**
 * Aggregate effect of an atomic command, not a per-target receipt. Any domain change makes a mixed
 * batch AppliedThisAttempt, including when other targets were only joined or unchanged. Without a
 * domain change, adopting an existing seal before this command has requested storage confirmation
 * is JoinedExisting; a retry after such a request (even a failed one), or a no-op edit, is
 * PostconditionConfirmed. A barrier-only storage write does not count as a domain change.
 * The effect neither attributes every effective id to a new write nor proves historical causation.
 */
internal enum class ConfirmedEffect { AppliedThisAttempt, JoinedExisting, PostconditionConfirmed }

/** Storage confirmation of this snapshot only; no admission, settlement or causal-history claim. */
internal class ConfirmedControlSnapshot internal constructor(val record: ControlRecordRead.Supported)
internal data class ConfirmationProof(val storage: RecordTransactionEvidence)

internal sealed interface RejectionReason {
    data class TooLarge(val payloadKey: ControlPayloadKey, val bytes: Int, val limit: Int) : RejectionReason
    data class InvalidRequest(val detail: String) : RejectionReason
}

internal enum class ConflictReason {
    IdCollision, TargetChanged, TargetMissing, UninterpretableTarget, AmbiguousSealKey, OperationIdCollision, IdentityTransitionPending,
    CommandEvidenceMismatch
}

internal enum class RecoveryReason {
    MigrationOrRecovery, UnreadableRecord, ControlSchemaMigrationRequired, UnreadableEpochState, InconsistentSettlement, JournalMigrationRequired,
    UninterpretableMetadata, UninterpretableObligations, InconsistentReclamation, CommandEvidenceLost, CommandEvidenceContinuityLost,
    ExpectedRotationEvidenceUnavailable
}
internal enum class UnconfirmedReason { StorageFailure, HistoryUnavailable }
internal enum class ControlAttemptPhase { ReadingSnapshot, PreparingCandidate, ConfirmingStorage }

/**
 * localPendingReleases enumerates refs whose release or termination storage confirmation is pending.
 * Pending membership does not imply business confirmation; a ref may belong to both local sets.
 * It is separate from localUnresolvedCommands; business success never clears pending releases.
 * Both sets are immutable memberships from one local recovery snapshot, not a lifecycle snapshot.
 * localUnresolvedCommands covers only this store owner's in-memory tracking lifetime. Empty does
 * not prove absence of earlier commands, clean continuity, settlement or permission to enter.
 * A rejection/conflict may coexist with an earlier unconfirmed attempt of the same command.
 * Rejected, Conflict and RecoveryRequired preserve membership from before the call. A checkpoint's
 * requested flag is not evidence of an unresolved attempt: successful previous-lifetime commands
 * carry it too.
 */
internal sealed interface ControlStoreResult {
    val command: CommandRef
    val lifecycleDiagnostic: ControlLifecycleDiagnostic? get() = null
    val localUnresolvedCommands: Set<CommandRef>
    val localPendingReleases: Set<CommandRef>

    data class Confirmed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val effect: ConfirmedEffect,
        val effectiveIds: List<String>,
        val snapshot: ConfirmedControlSnapshot,
        val proof: ConfirmationProof,
        val receipt: ControlSettlementReceipt? = null,
        override val lifecycleDiagnostic: ControlLifecycleDiagnostic? = null
    ) : ControlStoreResult {
        val lifecycleReceipt: ControlLifecycleReceipt? = receipt as? ControlLifecycleReceipt
        val settlement: SettlementReceipt? = receipt as? SettlementReceipt
        val handoverSettlement: HandoverSettlementReceipt? = receipt as? HandoverSettlementReceipt
    }

    data class Rejected(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RejectionReason,
        val observation: ControlRecordRead?,
        override val lifecycleDiagnostic: ControlLifecycleDiagnostic? = null
    ) : ControlStoreResult

    data class Conflict(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: ConflictReason,
        val expected: TargetExpectation,
        val observation: ControlRecordRead.Supported,
        override val lifecycleDiagnostic: ControlLifecycleDiagnostic? = null
    ) : ControlStoreResult

    data class RecoveryRequired(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RecoveryReason,
        val observation: ControlRecordRead,
        override val lifecycleDiagnostic: ControlLifecycleDiagnostic? = null
    ) : ControlStoreResult

    data class Unconfirmed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: UnconfirmedReason,
        val phase: ControlAttemptPhase,
        val lastObservation: ControlRecordRead?,
        val failure: java.io.IOException? = null,
        override val lifecycleDiagnostic: ControlLifecycleDiagnostic? = null
    ) : ControlStoreResult

    /** Execution is closed; these results issue no business snapshot, effect or storage proof. */
    data class ReleasePending(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlStoreResult

    data class Released(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlStoreResult

    data class TerminationPending(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlStoreResult

    data class Terminated(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>
    ) : ControlStoreResult
}
