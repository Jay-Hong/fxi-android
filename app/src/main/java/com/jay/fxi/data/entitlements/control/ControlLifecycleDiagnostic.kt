package com.jay.fxi.data.entitlements.control

import java.util.Collections

internal enum class LifecycleClassification {
    CURRENT_POSTCONDITION_CONFIRMED,
    MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE,
    PRECONDITION_CHANGED_WITHOUT_LANDING_PROOF,
    NO_LANDING_PROOF,
    EVIDENCE_UNAVAILABLE
}

internal enum class LifecycleOwnEvidence { Matched, Mismatched, Absent, Uninterpretable, Unknown }

/** One immutable memory observation, never a durable authority or a ref termination decision. */
internal class ControlLifecycleDiagnostic(
    val commandId: String,
    val transition: LifecycleTransition,
    val classification: LifecycleClassification,
    targets: List<LifecycleObservedTarget>,
    val confirmationRequested: Boolean,
    val previouslyConfirmed: Boolean,
    val ownEvidence: LifecycleOwnEvidence
) {
    val targets: List<LifecycleObservedTarget> = Collections.unmodifiableList(targets.toList())
}

internal object ControlLifecycleDiagnostics {
    fun observe(command: CommandRef, tracked: TrackedControlCommand, read: ControlRecordRead?,
        result: ControlStoreResult?, storageConfirmed: Boolean = false): ControlLifecycleDiagnostic? =
        observe(command, command.body, tracked, read, result, storageConfirmed)

    fun observe(command: CommandRef, body: ControlCommandBody, tracked: TrackedControlCommand, read: ControlRecordRead?,
        result: ControlStoreResult?, storageConfirmed: Boolean = false): ControlLifecycleDiagnostic? {
        val input = (body as? ControlCommandBody.Lifecycle)?.input ?: return null
        val supported = read as? ControlRecordRead.Supported
        val own = when {
            supported == null -> LifecycleOwnEvidence.Unknown
            ControlAppliedEvidence.hasOpaqueOwn(supported, command) -> LifecycleOwnEvidence.Uninterpretable
            else -> ControlAppliedEvidence.own(supported, command)?.let {
                if (ControlAppliedEvidence.matches(command, body, tracked, it)) LifecycleOwnEvidence.Matched else LifecycleOwnEvidence.Mismatched
            } ?: LifecycleOwnEvidence.Absent
        }
        val classification = if (storageConfirmed) LifecycleClassification.CURRENT_POSTCONDITION_CONFIRMED
            else classify(result, own, tracked.observedApplied.get(), read)
        return ControlLifecycleDiagnostic(command.id, input.transition, classification,
            input.targets.map { fixed ->
                supported?.let { ControlLifecycleBoundary.observe(it, fixed) }
                    ?: LifecycleObservedTarget(fixed.target, LifecycleTargetObservation.Uninterpretable)
            }, tracked.confirmationRequested.get(), tracked.confirmed.get(), own)
    }

    /** Classification is deliberately separate from eligibility, retries and recovery membership. */
    internal fun classify(result: ControlStoreResult?, own: LifecycleOwnEvidence, observed: Boolean,
        read: ControlRecordRead?): LifecycleClassification {
        if (result is ControlStoreResult.Confirmed) return LifecycleClassification.CURRENT_POSTCONDITION_CONFIRMED
        if (own == LifecycleOwnEvidence.Matched && result is ControlStoreResult.RecoveryRequired &&
            result.reason == RecoveryReason.InconsistentSettlement) return LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE
        if (own == LifecycleOwnEvidence.Mismatched || own == LifecycleOwnEvidence.Uninterpretable ||
            (observed && own == LifecycleOwnEvidence.Absent) ||
            (read != null && read !is ControlRecordRead.Supported) ||
            result is ControlStoreResult.RecoveryRequired) return LifecycleClassification.EVIDENCE_UNAVAILABLE
        if (own == LifecycleOwnEvidence.Matched && result is ControlStoreResult.Conflict) {
            return LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE
        }
        if (own == LifecycleOwnEvidence.Absent && result is ControlStoreResult.Conflict) {
            return LifecycleClassification.PRECONDITION_CHANGED_WITHOUT_LANDING_PROOF
        }
        return LifecycleClassification.NO_LANDING_PROOF
    }

    fun attach(result: ControlStoreResult, diagnostic: ControlLifecycleDiagnostic?): ControlStoreResult = when (result) {
        is ControlStoreResult.Confirmed -> result.copy(lifecycleDiagnostic = diagnostic)
        is ControlStoreResult.Rejected -> result.copy(lifecycleDiagnostic = diagnostic)
        is ControlStoreResult.Conflict -> result.copy(lifecycleDiagnostic = diagnostic)
        is ControlStoreResult.RecoveryRequired -> result.copy(lifecycleDiagnostic = diagnostic)
        is ControlStoreResult.Unconfirmed -> result.copy(lifecycleDiagnostic = diagnostic)
        is ControlStoreResult.ReleasePending, is ControlStoreResult.Released,
        is ControlStoreResult.TerminationPending, is ControlStoreResult.Terminated -> result
    }
}
