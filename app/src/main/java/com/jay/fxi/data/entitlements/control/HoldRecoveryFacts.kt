package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import java.util.Collections

/** Archived source only. No conversion to a live outcome, query, grant or admission permit. */
internal class HoldRecoverySource private constructor(override val original: ControlNode, val hold: RestoredHold) : RecoveryRetirementSource {
    /** Query.started.fence or Topic.context.access, including exact nulls. */
    val subject: FenceV1 = when (val provenance = hold.provenance) {
        is HoldProvenanceV1.Query -> provenance.started.fence
        is HoldProvenanceV1.Topic -> provenance.context.access
    }
    override val axes: Set<PurgeScope> = Collections.unmodifiableSet(hold.axes.toSet())
    override val ownerUid: String? get() = subject.ownerUid
    override fun targetEpoch(axis: PurgeScope): String? {
        require(axis in axes)
        return subject.epoch(axis)
    }

    companion object {
        /** Schema-valid HOLD only; the implementation must detach the archived axis set. */
        fun from(original: ControlNode): HoldRecoverySource? {
            val hold = ControlSchema.read(ControlKind.HOLD, original) as? RestoredHold ?: return null
            return HoldRecoverySource(original, hold.copy(axes = Collections.unmodifiableSet(hold.axes.toSet())))
        }
    }
}

/** Negative qualification, distinct from storage failure and from a successful storage receipt. */
internal sealed interface HoldRecoveryProblem {
    data class Rejected(val reason: RejectionReason) : HoldRecoveryProblem
    data class Conflict(val reason: ConflictReason) : HoldRecoveryProblem
    data class RecoveryRequired(val reason: RecoveryReason) : HoldRecoveryProblem
}

/**
 * Fixed source-specific closure facts, not an allDone token. Actual close/join issuance is D2c/P3.
 * SameProcess captures work under the coordinator lock, joins outside it, then rechecks it.
 * AfterRestart records caller AND storage-scope termination; origin inequality proves neither.
 */
internal sealed interface HoldRecoveryClosure {
    val source: ControlNode
    val executor: SettlementExecutor

    class SameProcess(
        override val source: ControlNode,
        override val executor: SettlementExecutor,
        val generationAtCapture: Long,
        val entriesClosed: Boolean,
        capturedWork: Set<String>,
        joinedWork: Set<String>
    ) : HoldRecoveryClosure {
        val capturedWork: Set<String> = Collections.unmodifiableSet(capturedWork.toSet())
        val joinedWork: Set<String> = Collections.unmodifiableSet(joinedWork.toSet())
    }

    data class AfterRestart(
        override val source: ControlNode,
        override val executor: SettlementExecutor,
        val previousTrackingLifetimeId: String,
        val previousCallerEnded: Boolean,
        val previousStorageScopeEnded: Boolean
    ) : HoldRecoveryClosure
}

/**
 * Fresh attempt facts. registeredWork is the closed source's registered set, retaining joined IDs
 * until handover; it is not just the currently running subset. A new registration invalidates join.
 * These facts are not saved in Applied and are not demanded again by confirmation-only.
 */
internal class HoldRecoveryRuntime(
    val binding: LifecycleBinding,
    val generation: Long,
    val entriesClosed: Boolean,
    registeredWork: Set<String>,
    val closure: HoldRecoveryClosure
) {
    val registeredWork: Set<String> = Collections.unmodifiableSet(registeredWork.toSet())
}

/** Direct predicates are separate from caller gates and from schema/final-candidate validation. */
internal object HoldRecoveryBoundary {
    fun subjectMatches(hold: RestoredHold, subject: FenceV1, axes: Set<PurgeScope>): Boolean {
        val archived = when (val p = hold.provenance) {
            is HoldProvenanceV1.Query -> p.started.fence
            is HoldProvenanceV1.Topic -> p.context.access
        }
        if (subject.ownerUid != archived.ownerUid) return false // HR.subjectOwner
        if (subject.userAccessEpoch != archived.userAccessEpoch) return false // HR.subjectUser
        if (subject.krxCapabilityEpoch != archived.krxCapabilityEpoch) return false // HR.subjectKrx
        if (axes != hold.axes) return false // HR.subjectAxes
        return true
    }

    /** Query: max(axis floor, started.intent); Topic: axis floor only. Empty axes are invalid. */
    fun minimumIntent(hold: RestoredHold): RefreshIntent? {
        if (hold.axes.isEmpty()) return null // HR.emptyAxes
        val axis = if (PurgeScope.USER in hold.axes) RefreshIntent.FORCE_PREMIUM else RefreshIntent.FORCE_ENTITLEMENTS
        return when (val provenance = hold.provenance) {
            is HoldProvenanceV1.Query -> maxOf(axis, provenance.started.intent) // HR.queryMax
            is HoldProvenanceV1.Topic -> axis
        }
    }

    fun intentSatisfies(hold: RestoredHold, actual: RefreshIntent): Boolean {
        val minimum = minimumIntent(hold) ?: return false
        if (actual < minimum) return false // HR.intent
        return true
    }

    fun closureProblem(
        source: ControlNode,
        fixed: HoldRecoveryClosure,
        runtime: HoldRecoveryRuntime,
        currentTrackingLifetimeId: String
    ): HoldRecoveryProblem? {
        fun reject(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))
        if (fixed.source.toPayloadEntry() != source.toPayloadEntry()) return reject("BindingNotClosed") // HR.sourcePin
        if (fixed.executor != runtime.binding.executor) return reject("BindingNotClosed") // HR.executorPin
        if (!runtime.entriesClosed) return reject("BindingNotClosed") // HR.entries
        if (!closureMatches(fixed, runtime.closure)) return reject("RelatedWorkNotQuiescent") // HR.closureCall
        when (fixed) {
            is HoldRecoveryClosure.SameProcess -> {
                if (!fixed.entriesClosed) return reject("BindingNotClosed") // HR.captureClosed
                if (fixed.joinedWork != fixed.capturedWork) return reject("RelatedWorkNotQuiescent") // HR.joined
                if (runtime.registeredWork != fixed.capturedWork) return reject("RelatedWorkNotQuiescent") // HR.registered
                if (runtime.generation != fixed.generationAtCapture) return reject("RelatedWorkNotQuiescent") // HR.generation
            }
            is HoldRecoveryClosure.AfterRestart -> {
                if (fixed.previousTrackingLifetimeId.isEmpty()) return reject("BindingNotClosed") // HR.previousEmpty
                if (fixed.previousTrackingLifetimeId == currentTrackingLifetimeId) return reject("BindingNotClosed") // HR.previousSame
                if (!fixed.previousCallerEnded) return reject("RelatedWorkNotQuiescent") // HR.callerEnded
                if (!fixed.previousStorageScopeEnded) return reject("RelatedWorkNotQuiescent") // HR.storageEnded
                if (runtime.registeredWork.isNotEmpty()) return reject("RelatedWorkNotQuiescent") // HR.restartWork
            }
        }
        return null
    }

    internal fun closureMatches(fixed: HoldRecoveryClosure, actual: HoldRecoveryClosure): Boolean {
        if (actual.source.toPayloadEntry() != fixed.source.toPayloadEntry()) return false // HR.actualSource
        if (actual.executor != fixed.executor) return false // HR.actualExecutor
        return when (fixed) {
            is HoldRecoveryClosure.SameProcess -> {
                if (actual !is HoldRecoveryClosure.SameProcess) return false // HR.actualSameKind
                if (actual.generationAtCapture != fixed.generationAtCapture) return false // HR.actualGeneration
                if (actual.entriesClosed != fixed.entriesClosed) return false // HR.actualClosed
                if (actual.capturedWork != fixed.capturedWork) return false // HR.actualCaptured
                if (actual.joinedWork != fixed.joinedWork) return false // HR.actualJoined
                true
            }
            is HoldRecoveryClosure.AfterRestart -> {
                if (actual !is HoldRecoveryClosure.AfterRestart) return false // HR.actualRestartKind
                if (actual.previousTrackingLifetimeId != fixed.previousTrackingLifetimeId) return false // HR.actualPrevious
                if (actual.previousCallerEnded != fixed.previousCallerEnded) return false // HR.actualCaller
                if (actual.previousStorageScopeEnded != fixed.previousStorageScopeEnded) return false // HR.actualStorage
                true
            }
        }
    }
}

/** H07g only: a pure lower-bound comparison. No intent retirement, preparation or writer API. */
internal object RecoveryIntentLowerBound {
    fun minimumIntent(source: RecoveryIntentV1): RefreshIntent = when (source.axis) {
        PurgeScope.USER -> RefreshIntent.FORCE_PREMIUM
        PurgeScope.CAPABILITY -> RefreshIntent.FORCE_ENTITLEMENTS
    }
    fun intentSatisfies(source: RecoveryIntentV1, actual: RefreshIntent): Boolean {
        if (actual < minimumIntent(source)) return false // HR.intentLowerBound
        return true
    }
}
