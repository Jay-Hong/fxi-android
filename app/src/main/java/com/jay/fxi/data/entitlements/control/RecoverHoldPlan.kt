package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import java.util.Collections

/** Fixed external facts; no caller option to omit a required request, journal or floor handover. */
internal data class RecoverHoldInput(
    val source: ControlNode,
    val guard: ControlNode?,
    val before: FenceV1,
    val binding: LifecycleBinding,
    val closure: HoldRecoveryClosure,
    val mergeNow: BootReading
)

/** Issued once. Request/guard IDs may remain unused; they never turn a preserved row into a target. */
internal data class RecoverHoldIds(
    val operationId: String,
    val requestId: String,
    val guardId: String,
    val epochs: RecoveryFreshEpochs
)

/**
 * Named, immutable preparation. Invalid inputs/order exhaustion remain an explicit negative plan;
 * execute must reject before constructing a candidate. Never retain the mutable order supplier.
 * Cached outputs are for construction/inspection, not the final validator's independent oracle.
 */
internal class RecoverHoldPlan private constructor(
    val input: RecoverHoldInput,
    val ids: RecoverHoldIds,
    val source: HoldRecoverySource?,
    val retirement: RecoveryRetirementPlan?,
    val floor: HoldFloorPlan?,
    val requestOrder: LifecycleOrderGrant?,
    val requestAfter: ControlNode?,
    val preparationProblem: HoldRecoveryProblem?
) {
    /** HOLD REMOVE, optional REQUEST CREATE, optional GUARD CREATE/REPLACE; no unchanged fake rows. */
    val targets: List<LifecycleFixedTarget> = Collections.unmodifiableList(buildList {
        source?.let { add(LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, it.hold.id, LifecycleEffect.REMOVE),
            LifecycleRole.HOLD, input.source, null)) }
        requestAfter?.let { add(fixed(ids.requestId, LifecycleRole.REQUEST, null, it)) }
        val after = floor?.guardAfter
        if (after != null && after.toPayloadEntry() != input.guard?.toPayloadEntry()) {
            add(fixed(checkNotNull(guard(after)).id, LifecycleRole.GUARD, input.guard, after))
        }
    })
    val requiredUnchanged: List<LifecycleFixedTarget> = Collections.unmodifiableList(buildList {
        val old = input.guard
        if (old != null && old.toPayloadEntry() == floor?.guardAfter?.toPayloadEntry()) {
            add(fixed(checkNotNull(guard(old)).id, LifecycleRole.GUARD, old, old))
        }
    })

    fun descriptor(): ControlLifecycleDescriptor = ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_HOLD,
        targets, input.binding.executor, retirement?.let { plan ->
            LifecycleNamespacePostcondition(input.before, plan.after, plan.journal,
                false.takeIf { plan.axes.any { it.target.axis == PurgeScope.USER && it.action is RecoveryAxisAction.Rotate } },
                false.takeIf { plan.axes.any { it.target.axis == PurgeScope.CAPABILITY && it.action is RecoveryAxisAction.Rotate } })
        }, requiredUnchanged, recoverHold = this)

    /** Includes the retained HoldFloorPlan preimage/guard-creation race check, even for no-op guards. */
    fun preimageProblem(read: ControlRecordRead.Supported): ConflictReason? {
        val actualSource = source ?: return ConflictReason.UninterpretableTarget
        val fixed = LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, actualSource.hold.id, LifecycleEffect.REMOVE),
            LifecycleRole.HOLD, input.source, null)
        ControlLifecycleBoundary.preimage(read, fixed)?.let { return it } // HR.sourcePreimageCall
        floor?.preimageProblem(read)?.let { return it } // HR.floorPreimageCall
        return null
    }

    companion object {
        /** Fix fresh order and floor anchor once. No storage writes, query starts or runtime grants. */
        fun prepare(input: RecoverHoldInput, ids: RecoverHoldIds, orders: LifecycleOrderSource): RecoverHoldPlan {
            val source = HoldRecoverySource.from(input.source)
            fun failed(problem: HoldRecoveryProblem) = RecoverHoldPlan(input, ids, source, null, null, null, null, problem)
            fun reject(detail: String) = failed(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail)))
            if (source == null) return reject("InvalidHoldSource") // HR.prepareSource
            if (ids.operationId.isEmpty()) return reject("InvalidOperationId") // HR.operationEmpty
            if (input.binding.executor.ownerUid != input.before.ownerUid) return reject("DemandScopeMismatch") // HR.prepareOwner
            if (input.binding.executor.binding < 0) return reject("InvalidDemand") // HR.prepareBinding
            if (input.binding.executor.originLifetimeId.value.isEmpty()) return reject("InvalidDemand") // HR.prepareOrigin
            if (input.binding.startedOrder < 0) return reject("InvalidDemand") // HR.prepareStart
            val retirement = when (val result = planRecoveryRetirement(source, input.before, ids.epochs)) {
                is RecoveryRetirementResult.Failed -> return failed(result.problem)
                is RecoveryRetirementResult.Planned -> result.plan
            }
            val floor = HoldFloorPlan.prepare(HoldFloorInput(input.source, input.guard, input.mergeNow,
                input.binding.executor.originLifetimeId, ids.guardId)) ?: return reject("InvalidBootReading") // HR.prepareFloor
            var order: LifecycleOrderGrant? = null
            var request: ControlNode? = null
            if (retirement.requestRequirement == RecoveryRequestRequirement.RequiredCurrentOwner) {
                if (ids.requestId.isEmpty()) return reject("InvalidRequestTarget") // HR.requestId
                if (orders.origin != input.binding.executor.originLifetimeId) return reject("OrderExhausted") // HR.supplierOrigin
                order = orders.issue(input.binding.startedOrder) ?: return reject("OrderExhausted") // HR.issue
                request = requestNode(ids.requestId, input.binding, checkNotNull(HoldRecoveryBoundary.minimumIntent(source.hold)), order.value)
            }
            return RecoverHoldPlan(input, ids, source, retirement, floor, order, request, null)
        }
    }
}
