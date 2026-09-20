package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

internal object DemandAuthFixtures {
    val life = LifetimeId("life")
    val identity = IdentityV1("A", 2)
    val binding = LifecycleBinding(SettlementExecutor("A", 3, life), identity, 1, "binding-start")
    val fence = FenceV1("A", "u", "k")
    val now = BootReading("boot", 10000)
    val auth = AuthSnapshotV1("A", 2, 3, life, true, 10, 20)
    val query = StartedQueryV1(fence, 5, identity, EventOrderV1(life, 21), 3, RefreshIntent.FORCE_PREMIUM, 0)
    val registration = LifecycleQueryRegistration("query-21", query)
    val codec = ControlPayloadCodec()
    val transition = DemandAuthTransition(codec)
    fun decision(q: StartedQueryV1 = query, source: LifecycleQuerySource = LifecycleQuerySource.REGISTERED_QUERY,
        answered: IdentityV1? = identity, before: FenceV1 = q.fence, beforeGeneration: Long = q.generation,
        after: FenceV1 = fence, afterGeneration: Long = 5,
        outcome: EntitlementsOutcome = EntitlementsOutcome.StableInactive(false),
        reapproval: LifecycleReapproval = LifecycleReapproval.NOT_REQUIRED, capture: BootReading = now,
        merge: BootReading = now, origin: LifetimeId = life, minDelay: Long = 0,
        followUp: RefreshIntent? = null, effects: List<LifecycleDurableEffect> = emptyList(),
        namespace: ConfirmedControlSnapshot? = null) = AcceptedQueryDecision(
            LifecycleQueryRegistration("query-21", q), source, answered, before, beforeGeneration, after,
            afterGeneration, outcome, reapproval, capture, merge, origin, minDelay, followUp, effects, namespace)
    fun runtime(binding: LifecycleBinding = this.binding, live: IdentityV1? = identity, generation: Long = 5,
        registrations: List<LifecycleQueryRegistration> = listOf(registration), caller: LifecycleCaller? = null,
        recovery: LifecycleRecovery? = null, consumed: Long = 0, closure: LifecycleBindingClosure? = null) =
        DemandAuthRuntime(binding, live, generation, registrations, caller, recovery, consumed, closure)
    fun context(runtime: DemandAuthRuntime = runtime()) = AttemptContext(runtime.binding.executor.ownerUid,
        runtime.binding.executor.binding, runtime.binding.executor.originLifetimeId, false, false, runtime)
    fun request(id: String = "r", owner: String? = "A", binding: Long = 3, origin: LifetimeId = life,
        intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM, order: Long = 4) = node(buildJsonObject {
        put("id", id); put("kind", "REQUEST"); put("ownerUid", owner?.let(::JsonPrimitive) ?: JsonNull)
        put("binding", binding); put("originLifetimeId", origin.value); put("intent", intent.name); put("raisedAt", order)
    }.toString())
    fun guard(auth: AuthSnapshotV1? = this.auth, wait: Long? = null, id: String = "g") = node(buildJsonObject {
        put("id", id); put("kind", "SCHEDULE_GUARD")
        if (auth != null) put("auth", buildJsonObject {
            put("ownerUid", auth.ownerUid); put("authGeneration", auth.authGeneration); put("binding", auth.binding)
            put("originLifetimeId", auth.originLifetimeId.value); put("authStopped", auth.authStopped)
            put("authStateOrder", auth.authStateOrder); put("authStopAppliedOrder", auth.authStopAppliedOrder)
        })
        if (wait != null) put("floor", buildJsonObject {
            put("anchorBootId", "boot"); put("anchorElapsedMillis", 10000); put("waitMillis", wait); put("originLifetimeId", "life")
        })
    }.toString())
    fun node(raw: String) = ControlNode.of(Json.parseToJsonElement(raw) as JsonObject)
    fun raw(vararg nodes: ControlNode): Preferences = ControlLifecycleEvidenceFixtures.raw(
        nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() })
    fun read(raw: Preferences) = ControlRecordReader().read(raw) as ControlRecordRead.Supported
    fun plan(d: AcceptedQueryDecision = decision(outcome = EntitlementsOutcome.Pending(false, 30)),
        guard: ControlNode = guard(), retry: ControlNode? = request(), settle: Boolean = false,
        removes: List<ControlNode> = emptyList(), orders: LifecycleOrderSource = LifecycleOrderSource(life, 21)): DemandAuthPlan =
        if (settle) DemandAuthPlan.settle(removes, guard, retry, binding, d, orders, "g-new", "r-new")
        else DemandAuthPlan.auth(guard, retry, binding, LifecycleAuthEvent.Answer(d), orders, "g-new", "r-new")
    fun command(plan: DemandAuthPlan): CommandRef {
        val input = plan.descriptor("command")
        return CommandRef(input.operationId, ControlCommandBody.Lifecycle(input), OwnerTrackingLifetimeId.issue())
    }
    fun apply(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime = runtime()): Pair<CommandRef, Preferences> {
        val c = command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        val decision = ControlLifecycleConfirmation(codec).decide(c, input, read(before), context(runtime), false, false)
        assertTrue("positive fixture must reach Confirm: $decision", decision is RecordTransactionDecision.Confirm)
        return c to (decision as RecordTransactionDecision.Confirm).candidate
    }
    fun patch(raw: Preferences, id: String, block: (JsonObject) -> JsonObject?): Preferences = raw.toMutablePreferences().apply {
        val key = ControlRecordKeys.payload(ControlKind.DEMAND)
        this[key] = JsonArray((Json.parseToJsonElement(this[key]!!) as JsonArray).mapNotNull {
            val row = it as JsonObject
            if ((row["id"] as JsonPrimitive).content == id) block(row) else row
        }).toString()
    }
    fun mutateChild(row: JsonObject, child: String, name: String, value: JsonElement) =
        JsonObject(row + (child to JsonObject((row[child] as JsonObject) + (name to value))))
    fun eligible(id: String) = "D2B5/$id: ineligible transition reached eligible boundary"
    fun atomic(id: String) = "D2B5/$id: atomic candidate contract violated"
    fun retry(id: String) = "D2B5/$id: retry contract violated"
    fun schema(raw: Preferences) {
        val r = read(raw); assertFalse(r.hasUninterpretable); assertFalse(r.hasUninterpretableMetadata)
    }
    fun onlyFalse(vararg predicates: Boolean) { assertEquals("fixture must violate exactly one independent predicate", 1, predicates.count { !it }) }
}
