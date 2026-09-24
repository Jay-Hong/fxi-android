package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, fifty-fourth file — order source and small order/request boundaries left OPEN (group ④), direct:
 * independent truth vector written here (production order), twin = the normal input with the literal result, negative =
 * only the target false, role. Also the store-level exhaustion units (DemandAuthIntegrationTest Q16b/Q16c): the store
 * refuses AND keeps the stored record; twin = the same store with a fresh source is Confirmed.
 *  - LifecycleOrderSource init (DemandAuthFacts.kt:14): initial ≥ 0 · origin non-empty — role: construction refused.
 *  - issue (DF:15–17): lower = max(last, bindingStart, after) must not be Long.MAX_VALUE — Q16b via `last`, Q16c via `after`.
 *  - orderValueMatches (DF:104–106): raisedAt == grant value.
 *  - request (DF:121–128) Q15b: a null REQUEST owner; the normal fixture's full vector is asserted true first.
 */
class DemandAuthBacklogContract54Test {
    private val max = Long.MAX_VALUE
    // LifecycleOrderSource init
    private fun initVec(initial: Long, origin: LifetimeId) = listOf("initial" to (initial >= 0), "origin" to origin.value.isNotEmpty())
    private fun initNo(id: String, only: String, initial: Long, origin: LifetimeId) {
        assertEquals("truth vector: normal source", emptySet<String>(), V.falses(initVec(0, F.life)))
        assertNotNull("positive twin: the normal source is constructed", runCatching { LifecycleOrderSource(F.life, 0) }.getOrNull())
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(initVec(initial, origin)))
        assertTrue(F.eligible(id), runCatching { LifecycleOrderSource(origin, initial) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun P_orderInitial() = initNo("Z.os.initial", "initial", -1, F.life)
    @Test fun P_orderOrigin() = initNo("Z.os.origin", "origin", 0, LifetimeId(""))

    // issue exhaustion (one predicate: lower == MAX); vector = the three terms and the literal lower
    private fun issueVec(last: Long, start: Long, after: Long) = listOf("notExhausted" to (maxOf(last, start, after) != max))
    @Test fun Q16b_direct() {
        assertEquals("truth vector: twin (last = MAX − 1)", emptySet<String>(), V.falses(issueVec(max - 1, 1, 0)))
        assertEquals("positive twin: the last issuable order is MAX", max, LifecycleOrderSource(F.life, max - 1).issue(1)?.value)
        assertEquals("truth vector: only exhaustion (via last)", setOf("notExhausted"), V.falses(issueVec(max, 1, 0)))
        assertNull(F.eligible("Z.os.Q16b"), LifecycleOrderSource(F.life, max).issue(1))
    }
    @Test fun Q16c_direct() {
        assertEquals("truth vector: twin (after = MAX − 1)", emptySet<String>(), V.falses(issueVec(21, 1, max - 1)))
        assertEquals("positive twin: after = MAX − 1 issues MAX", max, LifecycleOrderSource(F.life, 21).issue(1, max - 1)?.value)
        assertEquals("truth vector: only exhaustion (via after)", setOf("notExhausted"), V.falses(issueVec(21, 1, max)))
        assertNull(F.eligible("Z.os.Q16c"), LifecycleOrderSource(F.life, 21).issue(1, max))
    }

    // orderValueMatches
    @Test fun W_orderLink() {
        val g = LifecycleOrderGrant(F.life, 21, 1, 21, 22)
        val ok = demand(F.request(order = 22))!!; val bad = demand(F.request(order = 23))!!
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(listOf("link" to (ok.raisedAt.value == g.value))))
        assertTrue("positive twin: the request carries the grant value", DemandAuthBoundary.orderValueMatches(ok, g))
        assertEquals("truth vector: only the link differs", setOf("link"), V.falses(listOf("link" to (bad.raisedAt.value == g.value))))
        assertFalse(F.eligible("Z.os.orderLink"), DemandAuthBoundary.orderValueMatches(bad, g))
    }

    // request Q15b — null REQUEST owner against the normal query
    private fun reqVec(r: DemandV1, q: StartedQueryV1) = listOf("owner" to (r.ownerUid == q.fence.ownerUid), "binding" to (r.binding == q.binding),
        "origin" to (r.raisedAt.origin == q.order.origin), "start" to (q.order.value > r.raisedAt.value), "intent" to (q.intent >= r.intent))
    @Test fun Q15b_direct() {
        val r0 = demand(F.request())!!; val bad = r0.copy(ownerUid = null)
        assertEquals("truth vector: normal REQUEST", emptySet<String>(), V.falses(reqVec(r0, F.query)))
        assertTrue("positive twin: the owner-A REQUEST is settled by the normal query", DemandAuthBoundary.request(r0, F.query))
        assertEquals("truth vector: only the owner differs", setOf("owner"), V.falses(reqVec(bad, F.query)))
        assertFalse(F.eligible("Z.req.Q15b"), DemandAuthBoundary.request(bad, F.query))
    }

    // store-level exhaustion (DemandAuthIntegrationTest Q16b/Q16c): refused and the stored record unchanged
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c54 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c54 cleanup", 30000) { opened.forEach { it.close() } } }
    @Test fun Q16b_store() = runReleaseTest {
        val old = F.request(binding = 2, order = 50)
        val a = store(F.raw(old))
        val ok = a.control.execute(a.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, 21)), F.context(F.runtime()))
        assertTrue("positive twin: a fresh source re-binds through the store, got $ok", ok is ControlStoreResult.Confirmed)
        assertEquals("truth vector: only exhaustion (via last)", setOf("notExhausted"), V.falses(issueVec(max, 1, 50)))
        val o = store(F.raw(old)); val saved = o.raw()
        val r = o.control.execute(o.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, max)), F.context(F.runtime()))
        assertTrue(F.eligible("Z.st.Q16b.rebind"), r !is ControlStoreResult.Confirmed && o.raw() == saved)
    }
    @Test fun Q16c_store() = runReleaseTest {
        val g = F.guard(F.auth.copy(authStopped = false)); val req = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        val a = store(F.raw(g, req))
        val ok = a.control.execute(a.control.prepareSettleQuery(listOf(req), g, null, F.binding, d, LifecycleOrderSource(F.life, 21)), F.context())
        assertTrue("positive twin: the settle with a fresh source is Confirmed, got $ok", ok is ControlStoreResult.Confirmed)
        assertEquals("truth vector: only exhaustion (via last)", setOf("notExhausted"), V.falses(issueVec(max, 1, 21)))
        val o = store(F.raw(g, req)); val saved = o.raw()
        val r = o.control.execute(o.control.prepareSettleQuery(listOf(req), g, null, F.binding, d, LifecycleOrderSource(F.life, max)), F.context())
        assertTrue(F.eligible("Z.st.Q16c.settle"), r !is ControlStoreResult.Confirmed && o.raw() == saved)
    }
}
