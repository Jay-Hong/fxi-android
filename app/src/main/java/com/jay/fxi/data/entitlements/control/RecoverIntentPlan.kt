package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import java.util.Collections

/** Fixed source-specific facts; closure refers to the archived intent, not its whole session. */
internal data class RecoverIntentInput(
    val source: ControlNode,
    val before: FenceV1,
    val binding: LifecycleBinding,
    val closure: HoldRecoveryClosure
)

internal data class RecoverIntentIds(
    val operationId: String,
    val requestId: String,
    val epochs: RecoveryFreshEpochs
)

/** Preparation fixes IDs and order once; negative plans retain the reason without a storage write. */
internal class RecoverIntentPlan private constructor(
    val input: RecoverIntentInput,
    val ids: RecoverIntentIds,
    val source: IntentRecoverySource?,
    val retirement: RecoveryRetirementPlan?,
    val requestOrder: LifecycleOrderGrant?,
    val requestAfter: ControlNode?,
    val preparationProblem: HoldRecoveryProblem?
) {
    /** RECOVERY_INTENT REMOVE, optional REQUEST CREATE. No guard target or unchanged fake rows. */
    val targets: List<LifecycleFixedTarget> = Collections.unmodifiableList(buildList {
        source?.let { add(LifecycleFixedTarget(
            LifecycleTarget(ControlKind.RECOVERY_INTENT, it.intent.id, LifecycleEffect.REMOVE),
            LifecycleRole.RECOVERY_INTENT, input.source, null)) }
        requestAfter?.let { add(fixed(ids.requestId, LifecycleRole.REQUEST, null, it)) }
    })

    fun descriptor(): ControlLifecycleDescriptor = ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_INTENT,
        targets, input.binding.executor, retirement?.let { plan ->
            LifecycleNamespacePostcondition(input.before, plan.after, plan.journal,
                false.takeIf { plan.axes.any { it.target.axis == PurgeScope.USER && it.action is RecoveryAxisAction.Rotate } },
                false.takeIf { plan.axes.any { it.target.axis == PurgeScope.CAPABILITY && it.action is RecoveryAxisAction.Rotate } })
        }, recoverIntent = this)

    fun preimageProblem(read: ControlRecordRead.Supported): ConflictReason? {
        val actualSource = source ?: return ConflictReason.UninterpretableTarget // RI.preimageSource
        return ControlLifecycleBoundary.preimage(read, LifecycleFixedTarget(
            LifecycleTarget(ControlKind.RECOVERY_INTENT, actualSource.intent.id, LifecycleEffect.REMOVE),
            LifecycleRole.RECOVERY_INTENT, input.source, null))
    }

    companion object {
        /** No storage writes, query starts or runtime grants. The order supplier is not retained. */
        fun prepare(input: RecoverIntentInput, ids: RecoverIntentIds, orders: LifecycleOrderSource): RecoverIntentPlan {
            val source = IntentRecoverySource.from(input.source)
            fun failed(problem: HoldRecoveryProblem) = RecoverIntentPlan(input, ids, source, null, null, null, problem)
            fun reject(detail: String) = failed(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail)))
            if (source == null) return reject("InvalidIntentSource") // RI.prepareSource
            if (ids.operationId.isEmpty()) return reject("InvalidOperationId") // RI.operationEmpty
            if (input.binding.executor.ownerUid != input.before.ownerUid) return reject("DemandScopeMismatch") // RI.prepareOwner
            if (input.binding.executor.binding < 0) return reject("InvalidDemand") // RI.prepareBinding
            if (input.binding.executor.originLifetimeId.value.isEmpty()) return reject("InvalidDemand") // RI.prepareOrigin
            if (input.binding.startedOrder < 0) return reject("InvalidDemand") // RI.prepareStart
            val retirement = when (val result = planRecoveryRetirement(source, input.before, ids.epochs)) {
                is RecoveryRetirementResult.Failed -> return failed(result.problem) // RI.prepareRetirement
                is RecoveryRetirementResult.Planned -> result.plan
            }
            var order: LifecycleOrderGrant? = null
            var request: ControlNode? = null
            if (retirement.requestRequirement == RecoveryRequestRequirement.RequiredCurrentOwner) { // RI.prepareRequestRequired
                if (ids.requestId.isEmpty()) return reject("InvalidRequestTarget") // RI.requestId
                if (orders.origin != input.binding.executor.originLifetimeId) return reject("OrderExhausted") // RI.supplierOrigin
                order = orders.issue(input.binding.startedOrder) ?: return reject("OrderExhausted") // RI.issue
                request = requestNode(ids.requestId, input.binding, RecoveryIntentLowerBound.minimumIntent(source.intent), order.value)
            }
            return RecoverIntentPlan(input, ids, source, retirement, order, request, null)
        }
    }
}
