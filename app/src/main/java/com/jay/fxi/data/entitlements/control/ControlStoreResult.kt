package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import java.util.concurrent.atomic.AtomicReference

internal enum class ControlCommandLifecycle { RETAINED, RELEASE_PENDING, RELEASED }

/** Prepared identity and fixed inputs, never evidence that an attempt executed. */
internal class CommandRef internal constructor(
    val id: String,
    val body: ControlCommandBody,
    val ownerTrackingLifetimeId: OwnerTrackingLifetimeId
) {
    private val lifecycle = AtomicReference(ControlCommandLifecycle.RETAINED)
    val lifecycleState: ControlCommandLifecycle get() = lifecycle.get()

    // Internal transition sites are pinned to the validated owner release path by source tripwires.
    // The cell itself never escapes; neither operation restores business execution authority.
    internal fun beginRelease() {
        check(lifecycle.compareAndSet(ControlCommandLifecycle.RETAINED, ControlCommandLifecycle.RELEASE_PENDING))
    }

    internal fun completeRelease() {
        check(lifecycle.compareAndSet(ControlCommandLifecycle.RELEASE_PENDING, ControlCommandLifecycle.RELEASED))
    }

    init {
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
    UninterpretableMetadata, UninterpretableObligations, InconsistentReclamation, CommandEvidenceLost, CommandEvidenceContinuityLost
}
internal enum class UnconfirmedReason { StorageFailure, HistoryUnavailable }
internal enum class ControlAttemptPhase { ReadingSnapshot, PreparingCandidate, ConfirmingStorage }

/**
 * localPendingReleases enumerates confirmed business commands whose management removal is pending.
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
        val settlement: SettlementReceipt? = null
    ) : ControlStoreResult

    data class Rejected(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RejectionReason,
        val observation: ControlRecordRead?
    ) : ControlStoreResult

    data class Conflict(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: ConflictReason,
        val expected: TargetExpectation,
        val observation: ControlRecordRead.Supported
    ) : ControlStoreResult

    data class RecoveryRequired(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: RecoveryReason,
        val observation: ControlRecordRead
    ) : ControlStoreResult

    data class Unconfirmed(
        override val command: CommandRef,
        override val localUnresolvedCommands: Set<CommandRef>,
        override val localPendingReleases: Set<CommandRef>,
        val reason: UnconfirmedReason,
        val phase: ControlAttemptPhase,
        val lastObservation: ControlRecordRead?,
        val failure: java.io.IOException? = null
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
}
