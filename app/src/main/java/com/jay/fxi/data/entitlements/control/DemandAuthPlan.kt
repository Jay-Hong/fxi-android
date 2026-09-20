package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RefreshIntent
import kotlinx.serialization.json.*
import java.util.Collections

/** Fixed business input. Only the four named factories produce plans; no arbitrary patch API. */
internal class DemandAuthPlan private constructor(
    val binding: LifecycleBinding,
    val transition: LifecycleTransition,
    val event: LifecycleAuthEvent?,
    val decision: AcceptedQueryDecision?,
    val closure: LifecycleBindingClosure?,
    val replacement: LifecycleBinding?,
    val guardBefore: ControlNode?,
    val guardAfter: ControlNode?,
    val retryBefore: ControlNode?,
    val retryAfter: ControlNode?,
    val minimumRetryIntent: RefreshIntent?,
    val mergeNow: BootReading?,
    val requiredFloorMillis: Long?,
    val existingFloorMillis: Long,
    targets: List<LifecycleFixedTarget>,
    unchanged: List<LifecycleFixedTarget>,
    grants: Map<String, LifecycleOrderGrant>,
    val preparationFailure: String?
) {
    val targets = Collections.unmodifiableList(targets.toList())
    val unchanged = Collections.unmodifiableList(unchanged.toList())
    val grants = Collections.unmodifiableMap(grants.toMap())
    fun descriptor(id: String) = ControlLifecycleDescriptor(id, transition, targets, binding.executor,
        requiredUnchanged = unchanged, demandAuth = this)

    companion object {
        fun rebind(targets: List<ControlNode>, binding: LifecycleBinding, source: LifecycleOrderSource,
            intents: Map<String, RefreshIntent> = emptyMap()): DemandAuthPlan = Builder(binding, source).run {
            for (node in targets) rebind(node, intents[demand(node)?.id])
            finish(LifecycleTransition.REBIND_REQUESTS)
        }
        fun settle(requests: List<ControlNode>, guard: ControlNode?, retry: ControlNode?,
            binding: LifecycleBinding, decision: AcceptedQueryDecision, source: LifecycleOrderSource,
            guardId: String, retryId: String): DemandAuthPlan = Builder(binding, source).run {
            for (node in requests) {
                val value = demand(node)
                if (value == null) fail("InvalidRequestTarget") else changes += fixed(value.id, LifecycleRole.REQUEST, node, null)
            }
            answer(guard, retry, decision, guardId, retryId, requests.isNotEmpty())
            finish(LifecycleTransition.SETTLE_QUERY, decision = decision)
        }
        fun auth(guard: ControlNode?, retry: ControlNode?, binding: LifecycleBinding, event: LifecycleAuthEvent,
            source: LifecycleOrderSource, guardId: String, retryId: String): DemandAuthPlan = Builder(binding, source).run {
            guardBefore = guard
            val before = guard?.let(::guard)
            if (guard != null && before == null) fail("InvalidGuard")
            when (event) {
                LifecycleAuthEvent.Initialize -> {
                    guardAfter = guardNode(guard, guardId, initialAuth(binding), before?.floor)
                }
                is LifecycleAuthEvent.Answer -> answer(guard, retry, event.decision, guardId, retryId, false)
                is LifecycleAuthEvent.Caller -> {
                    val old = before?.auth
                    if (old == null) fail("AuthEventIneligible") else {
                        guardAfter = guardNode(guard, guardId, old.copy(authStopped = false,
                            authStateOrder = event.fact.order.value), before.floor)
                        grants["auth"] = event.fact.order
                        mergeNow = event.fact.now
                        val remaining = before.floor?.remainingAt(event.fact.now)
                        if (before.floor != null && remaining == null) fail("InvalidBootReading")
                        if ((remaining ?: 0) > 0) retry(retry, event.fact.intent, retryId, event.fact.order.value,
                            successorMustBeNew = false, needsFreshOrder = true)
                    }
                }
                is LifecycleAuthEvent.Recovery -> {
                    val old = before?.auth
                    if (old == null) fail("AuthEventIneligible") else guardAfter = guardNode(guard, guardId,
                        old.copy(authStopped = false, authStateOrder = event.fact.recoveredOrder), before.floor)
                }
            }
            // UPDATE_AUTH orders the guard before the optional retry, regardless of construction order.
            changes.removeAll { it.role == LifecycleRole.GUARD || it.role == LifecycleRole.REQUEST }
            unchanged.removeAll { it.role == LifecycleRole.GUARD || it.role == LifecycleRole.REQUEST }
            addGuard(guardId)
            addRetry()
            finish(LifecycleTransition.UPDATE_AUTH, event = event, decision = (event as? LifecycleAuthEvent.Answer)?.decision)
        }
        fun end(guard: ControlNode, requests: List<ControlNode>, binding: LifecycleBinding,
            closure: LifecycleBindingClosure, replacement: LifecycleBinding?, source: LifecycleOrderSource): DemandAuthPlan = Builder(binding, source).run {
            guardBefore = guard
            val before = guard(guard)
            if (before == null) fail("InvalidGuard") else {
                guardAfter = guardNode(guard, before.id, replacement?.let(::initialAuth), before.floor)
                addGuard(before.id)
            }
            if (replacement != null && replacement != binding) fail("AuthInitializationIneligible")
            for (node in requests) rebind(node, null)
            val frozen = closure.copy(capturedWork = Collections.unmodifiableSet(closure.capturedWork.toSet()),
                joinedWork = Collections.unmodifiableSet(closure.joinedWork.toSet()))
            finish(LifecycleTransition.END_AUTH_BINDING, closure = frozen, replacement = replacement)
        }
    }

    private class Builder(val binding: LifecycleBinding, val source: LifecycleOrderSource) {
        val changes = mutableListOf<LifecycleFixedTarget>()
        val unchanged = mutableListOf<LifecycleFixedTarget>()
        val grants = mutableMapOf<String, LifecycleOrderGrant>()
        var failure: String? = null
        var guardBefore: ControlNode? = null
        var guardAfter: ControlNode? = null
        var retryBefore: ControlNode? = null
        var retryAfter: ControlNode? = null
        var minRetry: RefreshIntent? = null
        var mergeNow: BootReading? = null
        var requiredFloor: Long? = null
        var existingFloor: Long = 0
        fun fail(detail: String) { if (failure == null) failure = detail }
        fun issue(key: String, after: Long): LifecycleOrderGrant? {
            val grant = source.issue(binding.startedOrder, after)
            if (grant == null) fail("OrderExhausted") else grants[key] = grant
            return grant
        }
        fun rebind(node: ControlNode, intent: RefreshIntent?) {
            val old = demand(node)
            if (old == null) { fail("InvalidRequestTarget"); return }
            val nextIntent = intent ?: old.intent
            if (!DemandAuthBoundary.rebind(old, binding, nextIntent)) { fail("InvalidRebind"); return }
            val lower = if (old.raisedAt.origin == binding.executor.originLifetimeId) old.raisedAt.value else 0
            val grant = issue(old.id, lower) ?: return
            changes += fixed(old.id, LifecycleRole.REQUEST, node, requestNode(old.id, binding, nextIntent, grant.value, node))
        }
        fun retry(node: ControlNode?, intent: RefreshIntent, id: String, after: Long,
            successorMustBeNew: Boolean, needsFreshOrder: Boolean = successorMustBeNew) {
            minRetry = intent
            retryBefore = node
            val old = node?.let(::demand)
            if (node != null && old == null) { fail("InvalidRetryRequest"); return }
            if (successorMustBeNew && node != null) { fail("SuccessorMustBeNew"); return }
            if (old != null && (old.ownerUid != binding.executor.ownerUid || old.binding != binding.executor.binding ||
                    old.raisedAt.origin != binding.executor.originLifetimeId)) { fail("RetryScopeMismatch"); return }
            if (!needsFreshOrder && old != null && old.intent >= intent) { retryAfter = node; return }
            val actualId = old?.id ?: id
            val grant = issue(actualId, maxOf(after, old?.raisedAt?.value ?: 0)) ?: return
            retryAfter = requestNode(actualId, binding, maxOf(old?.intent ?: intent, intent), grant.value, node)
        }
        fun answer(guard: ControlNode?, retry: ControlNode?, d: AcceptedQueryDecision,
            guardId: String, retryId: String, independent: Boolean) {
            guardBefore = guard
            val before = guard?.let(::guard)
            if (guard != null && before == null) { fail("InvalidGuard"); return }
            val seconds = DemandAuthBoundary.statedSeconds(d.outcome)
            if (!DemandAuthBoundary.seconds(seconds)) { fail("InvalidBootReading"); return }
            if (!DemandAuthBoundary.floorOrigin(d)) { fail("RequiredDecisionEffectMissing"); return }
            if (d.decisionMinDelayMillis < 0) { fail("InvalidBootReading"); return }
            mergeNow = d.mergeNow
            val captured = seconds?.let { FloorV1(d.capture.bootId, d.capture.elapsedMillis, it * 1000, d.floorOrigin) }
            val stated = captured?.remainingAt(d.mergeNow)
            if (captured != null && stated == null) { fail("InvalidBootReading"); return }
            if (d.mergeNow.bootId == "" || d.mergeNow.elapsedMillis < 0) { fail("InvalidBootReading"); return }
            existingFloor = before?.floor?.remainingAt(d.mergeNow) ?: if (before?.floor == null) 0 else {
                fail("InvalidBootReading"); return
            }
            val delay = DemandAuthBoundary.minimumDelay(d)
            requiredFloor = if (captured != null || delay > 0) maxOf(stated ?: 0, delay) else null
            val floor = if (requiredFloor != null && (before?.floor == null || existingFloor < requiredFloor!!))
                FloorV1(d.mergeNow.bootId, d.mergeNow.elapsedMillis, maxOf(existingFloor, requiredFloor!!), binding.executor.originLifetimeId)
                else before?.floor
            var auth = before?.auth
            if (auth != null && DemandAuthBoundary.answerOrder(auth, d.query.order.value)) {
                if ((d.outcome as? EntitlementsOutcome.Indeterminate)?.reason == IndeterminateReason.AUTHENTICATION) {
                    val grant = issue("auth", maxOf(auth.authStopAppliedOrder, d.query.order.value)) ?: return
                    auth = auth.copy(authStopped = true, authStateOrder = d.query.order.value, authStopAppliedOrder = grant.value)
                } else if (auth.authStopped) auth = auth.copy(authStopped = false, authStateOrder = d.query.order.value)
            }
            guardAfter = if (guard != null || floor != null || auth != null) guardNode(guard, guardId, auth, floor) else null
            DemandAuthBoundary.retryIntent(d)?.let { retry(retry, it, retryId, d.query.order.value, independent) }
            addRetry()
            addGuard(guardId)
        }
        fun addGuard(id: String) {
            guardAfter?.let { add(fixed(guard(guardBefore)?.id ?: id, LifecycleRole.GUARD, guardBefore, it)) }
        }
        fun addRetry() { retryAfter?.let { add(fixed(demand(it)!!.id, LifecycleRole.REQUEST, retryBefore, it)) } }
        fun add(target: LifecycleFixedTarget) {
            if (target.before?.toPayloadEntry() == target.after?.toPayloadEntry()) unchanged += target else changes += target
        }
        fun finish(transition: LifecycleTransition, event: LifecycleAuthEvent? = null, decision: AcceptedQueryDecision? = null,
            closure: LifecycleBindingClosure? = null, replacement: LifecycleBinding? = null): DemandAuthPlan =
            DemandAuthPlan(binding, transition, event, decision, closure, replacement, guardBefore, guardAfter,
                retryBefore, retryAfter, minRetry, mergeNow, requiredFloor, existingFloor, changes, unchanged, grants, failure)
    }
}

internal fun demand(node: ControlNode?): DemandV1? = node?.let { ControlSchema.read(ControlKind.DEMAND, it) as? DemandV1 }
internal fun guard(node: ControlNode?): ScheduleGuardV1? = node?.let { ControlSchema.read(ControlKind.DEMAND, it) as? ScheduleGuardV1 }
internal fun fixed(id: String, role: LifecycleRole, before: ControlNode?, after: ControlNode?) = LifecycleFixedTarget(
    LifecycleTarget(ControlKind.DEMAND, id, when { before == null -> LifecycleEffect.CREATE; after == null -> LifecycleEffect.REMOVE; else -> LifecycleEffect.REPLACE }),
    role, before, after)
internal fun initialAuth(binding: LifecycleBinding): AuthSnapshotV1? = binding.identity?.let {
    AuthSnapshotV1(it.ownerUid, it.authGeneration, binding.executor.binding, binding.executor.originLifetimeId, false, 0, 0)
}
internal fun requestNode(id: String, binding: LifecycleBinding, intent: RefreshIntent, order: Long, original: ControlNode? = null): ControlNode {
    val values: MutableMap<String, JsonElement> = original?.toPayloadEntry()?.fields?.toMutableMap() ?: linkedMapOf()
    val bindingLiteral = original?.takeIf { demand(it)?.binding == binding.executor.binding }?.toPayloadEntry()?.fields?.get("binding")
    values.putAll(mapOf("id" to JsonPrimitive(id), "kind" to JsonPrimitive("REQUEST"),
        "ownerUid" to (binding.executor.ownerUid?.let(::JsonPrimitive) ?: JsonNull), "binding" to (bindingLiteral ?: JsonPrimitive(binding.executor.binding)),
        "intent" to JsonPrimitive(intent.name), "originLifetimeId" to JsonPrimitive(binding.executor.originLifetimeId.value),
        "raisedAt" to JsonPrimitive(order)))
    return ControlNode.of(JsonObject(values))
}
internal fun guardNode(original: ControlNode?, id: String, auth: AuthSnapshotV1?, floor: FloorV1?): ControlNode {
    val values: MutableMap<String, JsonElement> = original?.toPayloadEntry()?.fields?.toMutableMap() ?: linkedMapOf(
        "id" to JsonPrimitive(id), "kind" to JsonPrimitive("SCHEDULE_GUARD"))
    // Preserve the literal subtree when the typed fact is unchanged.
    if (auth != guard(original)?.auth) {
        val oldAuth = guard(original)?.auth
        val oldFields = original?.toPayloadEntry()?.fields?.get("auth") as? JsonObject
        fun number(name: String, value: Long, old: Long?): JsonElement =
            oldFields?.get(name)?.takeIf { old == value } ?: JsonPrimitive(value)
        if (auth == null) values.remove("auth") else values["auth"] = buildJsonObject {
            put("ownerUid", auth.ownerUid); put("authGeneration", number("authGeneration", auth.authGeneration, oldAuth?.authGeneration))
            put("binding", number("binding", auth.binding, oldAuth?.binding))
            put("originLifetimeId", auth.originLifetimeId.value); put("authStopped", auth.authStopped)
            put("authStateOrder", number("authStateOrder", auth.authStateOrder, oldAuth?.authStateOrder))
            put("authStopAppliedOrder", number("authStopAppliedOrder", auth.authStopAppliedOrder, oldAuth?.authStopAppliedOrder))
        }
    }
    if (floor != guard(original)?.floor) {
        if (floor == null) values.remove("floor") else values["floor"] = buildJsonObject {
            put("anchorBootId", floor.anchorBootId?.let(::JsonPrimitive) ?: JsonNull)
            put("anchorElapsedMillis", floor.anchorElapsedMillis); put("waitMillis", floor.waitMillis)
            put("originLifetimeId", floor.originLifetimeId.value)
        }
    }
    return ControlNode.of(JsonObject(values))
}
