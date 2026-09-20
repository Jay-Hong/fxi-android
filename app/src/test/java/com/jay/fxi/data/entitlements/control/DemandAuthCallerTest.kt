package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemandAuthCallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun cleanup() = runBlocking {
        controlTestTimeout("caller cleanup", 30000) { opened.forEach { it.close() } }
    }
    private suspend fun owner(raw: Preferences) = ControlStoreTestStorage(temp.newFile()).also {
        opened += it
        controlTestTimeout("caller seed") { it.data.updateData { raw } }
    }
    private fun row(raw: Preferences, id: String) =
        (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original
    private fun confirmed(result: ControlStoreResult): ControlStoreResult.Confirmed {
        assertTrue("expected caller storage confirmation, got $result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    private data class Input(val guard: ControlNode, val request: ControlNode, val caller: LifecycleCaller,
        val orders: LifecycleOrderSource) {
        fun plan() = DemandAuthPlan.auth(guard, request, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "unused-new-id")
        fun raw() = F.raw(guard, request)
        fun context() = F.context(F.runtime(caller = caller))
    }
    private fun input(oldIntent: RefreshIntent = RefreshIntent.FORCE_ENTITLEMENTS,
        callerIntent: RefreshIntent = RefreshIntent.FORCE_ENTITLEMENTS, raisedAt: Long = 4): Input {
        // Q21 has already started while AUTH is resumed. The later floor delays CALLER22.
        val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("caller-22", LifecycleCallerOrigin.CALLER, F.binding,
            orders.issue(F.binding.startedOrder)!!, callerIntent, F.now)
        return Input(F.guard(F.auth.copy(authStopped = false), 60000),
            F.request(intent = oldIntent, order = raisedAt), caller, orders)
    }
    private fun fresh(id: String, f: Input): DemandAuthPlan {
        assertEquals(21L, F.query.order.value)
        assertEquals(22L, f.caller.order.value)
        assertEquals(4L, demand(f.request)!!.raisedAt.value)
        assertTrue(guard(f.guard)!!.floor!!.remainingAt(F.now)!! > 0)
        assertEquals(F.binding.executor.ownerUid, demand(f.request)!!.ownerUid)
        assertEquals(F.binding.executor.binding, demand(f.request)!!.binding)
        assertEquals(F.life, demand(f.request)!!.raisedAt.origin)
        val p = f.plan()
        assertNull(p.preparationFailure)
        val next = demand(p.retryAfter)
        assertTrue(F.atomic(id), next?.id == "r" && next.raisedAt.value == 23L)
        return p
    }
    private suspend fun persistedFresh(id: String, f: Input) {
        val p = fresh(id, f)
        val o = owner(f.raw())
        val command = o.control.prepareUpdateAuth(f.guard, f.request, F.binding, LifecycleAuthEvent.Caller(f.caller),
            // Use the same fixed input, with a fresh source at the same captured issuance position.
            LifecycleOrderSource(F.life, 22))
        val saved = confirmed(o.control.execute(command, f.context())).snapshot.record.original
        assertEquals(p.retryAfter!!.toPayloadEntry(), row(saved, "r").toPayloadEntry())
        assertEquals(maxOf(demand(f.request)!!.intent, f.caller.intent), demand(row(saved, "r"))!!.intent)
        assertEquals(22L, guard(row(saved, "g"))!!.auth!!.authStateOrder)
        assertEquals(f.guard.toPayloadEntry().fields["floor"], row(saved, "g").toPayloadEntry().fields["floor"])
        assertNotNull(ControlAppliedEvidence.own(F.read(saved), command))
        // All query predicates except strict start-after-raisedAt are true.
        val r = demand(row(saved, "r"))!!
        F.onlyFalse(r.ownerUid == F.query.fence.ownerUid, r.binding == F.query.binding,
            r.raisedAt.origin == F.query.order.origin, F.query.order.value > r.raisedAt.value,
            F.query.intent >= r.intent)
        assertFalse("Q21 must not consume the later caller demand", DemandAuthBoundary.request(r, F.query))
    }
    @Test fun A04_callerSame() = runReleaseTest { persistedFresh("A04.callerSame", input()) }
    @Test fun A04_callerStronger() = runReleaseTest {
        persistedFresh("A04.callerStronger", input(oldIntent = RefreshIntent.FORCE_PREMIUM))
    }
    @Test fun A04_callerMaxIntent() {
        val f = input(oldIntent = RefreshIntent.FORCE_PREMIUM)
        val p = fresh("A04.callerMaxIntent", f)
        assertEquals(F.atomic("A04.callerMaxIntent"), RefreshIntent.FORCE_PREMIUM, demand(p.retryAfter)!!.intent)
    }
    private suspend fun oldQueryCannotConsume(id: String, f: Input) {
        val o = owner(f.raw())
        val caller = o.control.prepareUpdateAuth(f.guard, f.request, F.binding, LifecycleAuthEvent.Caller(f.caller), f.orders)
        val saved = confirmed(o.control.execute(caller, f.context())).snapshot.record.original
        val r = row(saved, "r"); val g = row(saved, "g")
        assertEquals(23L, demand(r)!!.raisedAt.value)
        assertEquals(maxOf(demand(f.request)!!.intent, f.caller.intent), demand(r)!!.intent)
        assertEquals(22L, guard(g)!!.auth!!.authStateOrder)
        val d = F.decision()
        assertEquals(d.query.fence, F.fence)
        assertEquals(d.query.boundIdentity, F.identity)
        assertEquals(d.query.binding, demand(r)!!.binding)
        assertEquals(d.query.order.origin, demand(r)!!.raisedAt.origin)
        assertTrue(d.query.intent >= demand(r)!!.intent)
        assertEquals(21L, d.query.order.value)
        assertTrue(DemandAuthBoundary.consumes(d.outcome, demand(r)!!.intent, d.reapproval))
        val settle = o.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, f.orders)
        val result = o.control.execute(settle, F.context())
        assertTrue(F.eligible(id), result !is ControlStoreResult.Confirmed && o.raw() == saved)
        assertNull(ControlAppliedEvidence.own(F.read(o.raw()), settle))
    }
    @Test fun Q03_callerSame() = runReleaseTest { oldQueryCannotConsume("Q03.callerSame", input()) }
    @Test fun Q03_callerStronger() = runReleaseTest {
        oldQueryCannotConsume("Q03.callerStronger", input(oldIntent = RefreshIntent.FORCE_PREMIUM))
    }
    @Test fun A04_callerAfterExisting() {
        val f = input(raisedAt = 40)
        assertEquals(22L, f.caller.order.value)
        val p = f.plan()
        assertTrue(F.atomic("A04.callerAfterExisting"), demand(p.retryAfter)?.raisedAt?.value == 41L)
    }
    @Test fun A04_callerAfterLastIssue() {
        val f = input()
        assertEquals(41L, f.orders.issue(F.binding.startedOrder, 40)!!.value)
        val p = f.plan()
        assertTrue(F.atomic("A04.callerAfterLastIssue"), demand(p.retryAfter)?.raisedAt?.value == 42L)
    }
    @Test fun R01_callerPreparedOnce() = runReleaseTest {
        val f = input(); val o = owner(f.raw())
        val command = o.control.prepareUpdateAuth(f.guard, f.request, F.binding, LifecycleAuthEvent.Caller(f.caller), f.orders)
        val p = (command.body as ControlCommandBody.Lifecycle).input.demandAuth!!
        val fixed = p.retryAfter!!.toPayloadEntry()
        assertEquals(23L, demand(p.retryAfter)!!.raisedAt.value)
        assertEquals(24L, f.orders.issue(F.binding.startedOrder)!!.value)
        o.storage.before = true
        assertTrue(o.control.execute(command, f.context()) is ControlStoreResult.Unconfirmed)
        assertEquals(f.raw(), o.raw())
        val saved = confirmed(o.control.execute(command, f.context())).snapshot.record.original
        assertEquals(F.retry("R01.callerPreparedOnce"), fixed, row(saved, "r").toPayloadEntry())
        assertEquals(25L, f.orders.issue(F.binding.startedOrder)!!.value)
    }
    private suspend fun exhausted(initial: Long, raisedAt: Long) {
        val f = input(raisedAt = raisedAt); val raw = f.raw(); val o = owner(raw)
        val c = o.control.prepareUpdateAuth(f.guard, f.request, F.binding, LifecycleAuthEvent.Caller(f.caller),
            LifecycleOrderSource(F.life, initial))
        val result = o.control.execute(c, f.context())
        assertTrue(F.atomic("Q16.callerExhausted"), result is ControlStoreResult.Rejected && o.raw() == raw)
        assertNull(ControlAppliedEvidence.own(F.read(o.raw()), c))
        assertEquals(RejectionReason.InvalidRequest("OrderExhausted"), (result as ControlStoreResult.Rejected).reason)
    }
    @Test fun Q16_callerSequenceExhausted() = runReleaseTest { exhausted(Long.MAX_VALUE, 4) }
    @Test fun Q16_callerExistingExhausted() = runReleaseTest { exhausted(22, Long.MAX_VALUE) }

    // Test the candidate boundary directly so the independent issued-row equality gate cannot mask it.
    private fun candidateNo(id: String, order: Long = 30, bindingStart: Long = 1, previous: Long = 23,
        callerOrder: Long = 22, oldOrder: Long = 4, intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM,
        oldIntent: RefreshIntent = RefreshIntent.FORCE_ENTITLEMENTS, missingGrant: Boolean = false) {
        val request = demand(F.request(intent = intent, order = order))!!
        val before = demand(F.request(intent = oldIntent, order = oldOrder))!!
        val binding = F.binding.copy(startedOrder = bindingStart)
        val caller = input().caller.copy(order = LifecycleOrderGrant(F.life, callerOrder - 1, 1, 0, callerOrder))
        val grant = if (missingGrant) null else LifecycleOrderGrant(F.life, previous, bindingStart, 29, 30)
        F.schema(F.raw(requestNode(request.id, binding, intent, order)))
        assertEquals(before.ownerUid, request.ownerUid)
        assertEquals(before.binding, request.binding)
        assertEquals(before.raisedAt.origin, request.raisedAt.origin)
        assertTrue(request.intent >= caller.intent)
        F.onlyFalse(grant != null, order > bindingStart, order > (grant?.previous ?: 0),
            order > callerOrder, order > oldOrder, intent >= oldIntent)
        assertFalse(F.atomic(id), F.transition.callerRetry(request, before, binding, caller, grant))
    }
    @Test fun A04_callerGrant() = candidateNo("A04.callerGrant", missingGrant = true)
    @Test fun A04_callerBindingStart() = candidateNo("A04.callerBindingStart", bindingStart = 30)
    @Test fun A04_callerPrevious() = candidateNo("A04.callerPrevious", previous = 30)
    @Test fun A04_callerEvent() = candidateNo("A04.callerEvent", callerOrder = 30)
    @Test fun A04_callerRaisedAt() = candidateNo("A04.callerRaisedAt", oldOrder = 30)
    @Test fun A04_callerIntent() = candidateNo("A04.callerIntent", intent = RefreshIntent.FORCE_ENTITLEMENTS,
        oldIntent = RefreshIntent.FORCE_PREMIUM)
    @Test fun A04_candidateRechecksBuilder() {
        val f = input(); val p = f.plan()
        val (_, full) = F.apply(p, f.raw(), F.runtime(caller = f.caller))
        val stale = F.patch(full, "r") { JsonObject(it + ("raisedAt" to JsonPrimitive(22))) }
        F.schema(stale)
        // Model a defective builder whose proposed row agrees with the candidate. The business
        // boundary must independently reject it, without relying on the generic row-equality gate.
        DemandAuthPlan::class.java.getDeclaredField("retryAfter").apply { isAccessible = true }.set(p, row(stale, "r"))
        assertEquals(demand(p.retryAfter), demand(row(stale, "r")))
        assertEquals(row(full, "g").toPayloadEntry(), row(stale, "g").toPayloadEntry())
        assertFalse(F.atomic("A04.candidateRechecksBuilder"), F.transition.requiredEffects(p, F.read(stale)))
    }
    @Test fun A04_answerRetryKeepsExistingOrder() {
        val request = F.request(intent = RefreshIntent.FORCE_PREMIUM)
        val p = F.plan(retry = request)
        assertNull(p.preparationFailure)
        assertEquals(request.toPayloadEntry(), p.retryAfter!!.toPayloadEntry())
        assertTrue(p.unchanged.any { it.target.id == "r" })
    }
}
