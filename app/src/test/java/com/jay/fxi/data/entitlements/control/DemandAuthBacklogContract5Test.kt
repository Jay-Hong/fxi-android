package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, fifth file (implementation review r2, scope group A): DemandAuth-conditional content inside the shared
 * lifecycle code and the store facade.
 *
 * - LC:182 with its duplicate DT:24 — a failed preparation never becomes a write (design §3.1 123–125, §4.4 292).
 *   Each line alone is masked by the other; the two are one M1 pair target, reported apart from single KILL counts.
 * - validDescriptor role rules for the four DemandAuth transitions (LC:257–267) — each target order the design table
 *   allows (§3.2 162–165) reaches Confirm from a real factory plan. Invalid UPDATE_AUTH and END_AUTH_BINDING inputs
 *   can also produce request-only factory plans. Their role rejection is tested directly; downstream AUTH gates
 *   independently reject them in the writer. Widening exclusions must therefore be stated per transition and domain.
 * - ControlRecordStore DemandAuth facade (CRS:54–73) — each facade passes its arguments to the named factory and
 *   issues fresh, distinct ids (§3.1 125).
 *
 * Expected values come from the design sentences cited on each test. Premise assertions use plain messages; their
 * failure is never a target's kill.
 */
class DemandAuthBacklogContract5Test {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { controlTestTimeout("5e-1 cleanup", 30000) { opened.reversed().forEach { it.close() } } }

    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun closure() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)

    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(before), F.context(runtime), false, false)
    }
    private fun shape(plan: DemandAuthPlan) = plan.targets.map { "${it.role}/${it.target.effect}" }
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result

    // LC.prepPair — M1 pair LC:182 + DT:24. §3.1 123–125 fixes every target at prepare; §4.4 292 forbids re-binding a
    // REQUEST already on the current binding/origin, so the planner fails the whole END (DP:129) and drops that REQUEST
    // from the targets. W.endRequests (DT:139–143) owes only REQUESTs that need handover, so it cannot see the drop:
    // only the preparation-failure checks stand between this ineligible command and Confirm (probe-prep-pair/, P3).

    @Test fun LC_prepPair_endRebind() {
        val g = F.guard(auth = oldAuth); val current = F.request()
        val rt = F.runtime(closure = closure())
        val p = DemandAuthPlan.end(g, listOf(current), F.binding, closure(), F.binding, orders)
        assertEquals("fixture: the planner refuses the re-bind", "InvalidRebind", p.preparationFailure)
        assertEquals("fixture: the refused REQUEST is not a target", listOf("GUARD/REPLACE"), shape(p))
        val raw = F.raw(g, current)
        F.schema(raw)
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(p, raw, "command")))
        assertEquals("truth vector: writer gates", emptySet<String>(), V.falses(V.decideGates(p, F.context(rt), raw)))
        assertEquals("truth vector: eligibility", emptySet<String>(), V.falses(V.eligibility(p, rt, raw)))
        val d = decide(p, raw, rt)
        assertFalse(F.eligible("LC.prepPair.endRebind"), d is RecordTransactionDecision.Confirm)
        val result = negative(d)
        assertTrue("classification: a failed preparation is Rejected", result is ControlStoreResult.Rejected)
        assertEquals("classification: planner reason", RejectionReason.InvalidRequest("InvalidRebind"), (result as ControlStoreResult.Rejected).reason)
    }

    // LC.roles — validDescriptor role orders from the §3.2 table (162–165), one real factory plan per allowed order.

    private fun roleOrder(id: String, expected: List<String>, plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime = F.runtime()) {
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("fixture: target order", expected, shape(plan))
        F.schema(raw)
        assertTrue(F.atomic(id), decide(plan, raw, rt) is RecordTransactionDecision.Confirm)
    }

    /** REBIND_REQUESTS: DEMAND/REPLACE of REQUESTs only (§3.2 162). */
    @Test fun LC_roles_rebind() {
        val old = F.request(binding = 2)
        roleOrder("LC.roles.rebind", listOf("REQUEST/REPLACE"), DemandAuthPlan.rebind(listOf(old), F.binding, orders), F.raw(old))
    }
    /** SETTLE_QUERY with consumption and no selected row (§3.2 163: selected rows 0–2). */
    @Test fun LC_roles_settleRemoveOnly() {
        val r = F.request()
        roleOrder("LC.roles.settleRemoveOnly", listOf("REQUEST/REMOVE"),
            DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(), orders, "g-new", "r-new"), F.raw(r))
    }
    /** SETTLE_QUERY: consumption then the required successor CREATE (§3.2 163 "소비가 있으면 필요한 새 successor CREATE"). */
    @Test fun LC_roles_settleSuccessor() {
        val r = F.request()
        roleOrder("LC.roles.settleSuccessor", listOf("REQUEST/REMOVE", "REQUEST/CREATE"),
            DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(followUp = RefreshIntent.FORCE_PREMIUM), orders, "g-new", "r-new"),
            F.raw(r))
    }
    /** SETTLE_QUERY: consumption then the changed guard (§3.2 163 "마지막 선택 행은 실제 변경되는 guard"). */
    @Test fun LC_roles_settleGuard() {
        val g = F.guard(); val r = F.request()
        roleOrder("LC.roles.settleGuard", listOf("REQUEST/REMOVE", "GUARD/REPLACE"),
            DemandAuthPlan.settle(listOf(r), g, null, F.binding, F.decision(), orders, "g-new", "r-new"), F.raw(g, r))
    }
    /** SETTLE_QUERY: consumption, successor, then guard. */
    @Test fun LC_roles_settleBoth() {
        val g = F.guard(); val r = F.request()
        roleOrder("LC.roles.settleBoth", listOf("REQUEST/REMOVE", "REQUEST/CREATE", "GUARD/REPLACE"),
            DemandAuthPlan.settle(listOf(r), g, null, F.binding, F.decision(followUp = RefreshIntent.FORCE_PREMIUM), orders, "g-new", "r-new"),
            F.raw(g, r))
    }
    /** SETTLE_QUERY without consumption: a strengthening REPLACE of the retry REQUEST is allowed (§3.2 163 "소비가 없으면 … 강화 REPLACE"). */
    @Test fun LC_roles_settleNoRemoveReplace() {
        val g = F.guard(auth = null); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        roleOrder("LC.roles.settleNoRemoveReplace", listOf("REQUEST/REPLACE", "GUARD/REPLACE"),
            F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak, settle = true), F.raw(g, weak))
    }
    /** UPDATE_AUTH: the guard alone (§3.2 164). */
    @Test fun LC_roles_updateGuard() {
        roleOrder("LC.roles.updateGuard", listOf("GUARD/CREATE"),
            DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), F.raw())
    }
    /** UPDATE_AUTH: the guard then the required retry REQUEST (§3.2 164 "두 번째는 필요 caller/retry REQUEST"). */
    @Test fun LC_roles_updateGuardRequest() {
        val g = F.guard(); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        roleOrder("LC.roles.updateGuardRequest", listOf("GUARD/REPLACE", "REQUEST/REPLACE"),
            F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak), F.raw(g, weak))
    }
    /** END_AUTH_BINDING: the guard alone (§3.2 165 "REPLACE 0개 이상"). */
    @Test fun LC_roles_endGuard() {
        val g = F.guard(auth = oldAuth)
        roleOrder("LC.roles.endGuard", listOf("GUARD/REPLACE"),
            DemandAuthPlan.end(g, emptyList(), F.binding, closure(), F.binding, orders), F.raw(g), F.runtime(closure = closure()))
    }
    /** END_AUTH_BINDING: the guard then a same-owner REQUEST handover (§3.2 165). */
    @Test fun LC_roles_endGuardRequest() {
        val g = F.guard(auth = oldAuth); val old = F.request(binding = 2)
        roleOrder("LC.roles.endGuardRequest", listOf("GUARD/REPLACE", "REQUEST/REPLACE"),
            DemandAuthPlan.end(g, listOf(old), F.binding, closure(), F.binding, orders), F.raw(g, old), F.runtime(closure = closure()))
    }

    // Cardinality (review of implementation r4, I20): allowed orders with two REQUESTs. These close only the finite
    // limits they exercise ("at most one" REQUEST); they do not prove every arbitrary numeric limit.

    /** REBIND_REQUESTS with two REQUESTs (§3.2 162 "1개 이상"). */
    @Test fun LC_roles_rebindTwoRequests() {
        val a = F.request(id = "r1", binding = 2); val b = F.request(id = "r2", binding = 2)
        roleOrder("LC.roles.rebindTwoRequests", listOf("REQUEST/REPLACE", "REQUEST/REPLACE"),
            DemandAuthPlan.rebind(listOf(a, b), F.binding, orders), F.raw(a, b))
    }
    /** SETTLE_QUERY consuming two REQUESTs with no selected row (§3.2 163 "REMOVE 0개 이상"). */
    @Test fun LC_roles_settleTwoRequests() {
        val a = F.request(id = "r1"); val b = F.request(id = "r2")
        roleOrder("LC.roles.settleTwoRequests", listOf("REQUEST/REMOVE", "REQUEST/REMOVE"),
            DemandAuthPlan.settle(listOf(a, b), null, null, F.binding, F.decision(), orders, "g-new", "r-new"), F.raw(a, b))
    }
    /** END_AUTH_BINDING: the guard then two same-owner REQUEST handovers (§3.2 165 "REPLACE 0개 이상"). */
    @Test fun LC_roles_endTwoRequests() {
        val g = F.guard(auth = oldAuth); val a = F.request(id = "r1", binding = 2); val b = F.request(id = "r2", binding = 2)
        roleOrder("LC.roles.endTwoRequests", listOf("GUARD/REPLACE", "REQUEST/REPLACE", "REQUEST/REPLACE"),
            DemandAuthPlan.end(g, listOf(a, b), F.binding, closure(), F.binding, orders), F.raw(g, a, b), F.runtime(closure = closure()))
    }

    private fun rejectedFactoryRole(id: String, expected: List<String>, plan: DemandAuthPlan, raw: Preferences) {
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("fixture: target order", expected, shape(plan))
        F.schema(raw)
        assertEquals("truth vector: only descriptor order is invalid", setOf("descriptor.order"),
            V.falses(V.commonPremises(plan, raw, "command")))
        assertEquals("truth vector: writer gates", emptySet<String>(),
            V.falses(V.decideGates(plan, F.context(), raw)))
        assertFalse(F.eligible(id),
            ControlLifecycleConfirmation(F.codec).validDescriptor(plan.descriptor("command")))
    }

    @Test fun LC_roles_updateRequestOnly() {
        val p = DemandAuthPlan.auth(null, null, F.binding,
            LifecycleAuthEvent.Answer(F.decision(followUp = RefreshIntent.FORCE_PREMIUM)),
            orders, "g-new", "r-new")
        rejectedFactoryRole("LC.roles.updateRequestOnly", listOf("REQUEST/CREATE"), p, F.raw())
    }

    @Test fun LC_roles_endRequestOnly() {
        val g = F.guard(auth = null, wait = 1000)
        val old = F.request(binding = 2)
        val p = DemandAuthPlan.end(g, listOf(old), F.binding, closure(), null, orders)
        rejectedFactoryRole("LC.roles.endRequestOnly", listOf("REQUEST/REPLACE"), p, F.raw(g, old))
    }

    // CRS — the DemandAuth facade (CRS:54–73). §3.1 125: prepare fixes the command id and each needed new id once.

    private class Ids { var issued = 0; val generator = ControlIdGenerator { UUID(0, (++issued).toLong()) } }
    private fun facade(source: Preferences, ids: Ids): Pair<ControlStoreTestStorage, ControlRecordStore> = runBlocking {
        val store = ControlStoreTestStorage(folder.newFile()).also { opened += it }
        controlTestTimeout("seed 5e-1") { store.data.updateData { source } }
        store to ControlRecordStore(store.owner, ids.generator)
    }
    private fun run(store: ControlStoreTestStorage, facade: ControlRecordStore, c: CommandRef, rt: DemandAuthRuntime): Pair<ControlStoreResult, Preferences> =
        runBlocking { controlTestTimeout("5e-1 facade") { facade.execute(c, F.context(rt)) } to store.raw() }
    private fun rows(raw: Preferences) = F.read(raw).arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().map { it.value }

    /** SETTLE facade: the new successor and the new guard get two distinct fresh ids (§3.2 "새 successor와 guard의 ID가 같으면 거절"). */
    @Test fun CRS_settle_freshIds() {
        val r = F.request(); val ids = Ids()
        val (store, facade) = facade(F.raw(r), ids)
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM, minDelay = 1000)
        val c = facade.prepareSettleQuery(listOf(r), null, null, F.binding, d, orders)
        val (result, raw) = run(store, facade, c, F.runtime())
        assertTrue(F.atomic("CRS.settle.freshIds"), result is ControlStoreResult.Confirmed)
        val landed = rows(raw); val fresh = (1..3).map { UUID(0, it.toLong()).toString() }.toSet()
        val guardId = landed.filterIsInstance<ScheduleGuardV1>().single().id
        val successorId = landed.filterIsInstance<DemandV1>().single().id
        assertTrue(F.atomic("CRS.settle.freshIds"), guardId in fresh && successorId in fresh && guardId != successorId &&
            guardId != c.id && successorId != c.id)
    }
    /**
     * UPDATE_AUTH facade: guard, retry and event reach the plan (CRS:63–65). The existing guard keeps its id and the
     * weaker retry REQUEST is strengthened in place (§3.2 164; §4.3 282).
     */
    @Test fun CRS_auth_arguments() {
        val g = F.guard(); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS); val ids = Ids()
        val (store, facade) = facade(F.raw(g, weak), ids)
        val c = facade.prepareUpdateAuth(g, weak, F.binding,
            LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30))), orders)
        val (result, raw) = run(store, facade, c, F.runtime())
        assertTrue(F.atomic("CRS.auth.arguments"), result is ControlStoreResult.Confirmed)
        val landed = rows(raw)
        val requests = landed.filterIsInstance<DemandV1>()
        assertTrue(F.atomic("CRS.auth.arguments"), requests.size == 1 && requests[0].id == "r" &&
            requests[0].intent == RefreshIntent.FORCE_PREMIUM)
        val guardRow = landed.filterIsInstance<ScheduleGuardV1>().single()
        assertTrue(F.atomic("CRS.auth.arguments"), guardRow.id == "g" && guardRow.auth?.authStopped == false && guardRow.floor != null)
    }
    /** SETTLE facade: the guard argument reaches the plan — the answered guard resumes and the consumed REQUEST goes. */
    @Test fun CRS_settle_guardArgument() {
        val g = F.guard(); val r = F.request(); val ids = Ids()
        val (store, facade) = facade(F.raw(g, r), ids)
        val c = facade.prepareSettleQuery(listOf(r), g, null, F.binding, F.decision(), orders)
        val (result, raw) = run(store, facade, c, F.runtime())
        assertTrue(F.atomic("CRS.settle.guardArgument"), result is ControlStoreResult.Confirmed)
        val landed = rows(raw)
        assertTrue(F.atomic("CRS.settle.guardArgument"), landed.filterIsInstance<DemandV1>().isEmpty() &&
            landed.filterIsInstance<ScheduleGuardV1>().single().let { it.id == "g" && it.auth?.authStopped == false })
    }
    /** SETTLE facade without consumption: the retry argument is strengthened in place (§3.2 163 "강화 REPLACE"). */
    @Test fun CRS_settle_retryArgument() {
        val g = F.guard(auth = null); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS); val ids = Ids()
        val (store, facade) = facade(F.raw(g, weak), ids)
        val c = facade.prepareSettleQuery(emptyList(), g, weak, F.binding, F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), orders)
        val (result, raw) = run(store, facade, c, F.runtime())
        assertTrue(F.atomic("CRS.settle.retryArgument"), result is ControlStoreResult.Confirmed)
        val requests = rows(raw).filterIsInstance<DemandV1>()
        assertTrue(F.atomic("CRS.settle.retryArgument"), requests.size == 1 && requests[0].id == "r" &&
            requests[0].intent == RefreshIntent.FORCE_PREMIUM)
    }
    /** REBIND facade: the caller's intent map reaches the handover (§4.4 292 "intent는 원래 이상"; CRS:55–56). */
    @Test fun CRS_rebind_intents() {
        val old = F.request(binding = 2, intent = RefreshIntent.FORCE_ENTITLEMENTS); val ids = Ids()
        val (store, facade) = facade(F.raw(old), ids)
        val c = facade.prepareRebindRequests(listOf(old), F.binding, orders, mapOf("r" to RefreshIntent.FORCE_PREMIUM))
        val (result, raw) = run(store, facade, c, F.runtime())
        assertTrue(F.atomic("CRS.rebind.intents"), result is ControlStoreResult.Confirmed)
        val moved = rows(raw).filterIsInstance<DemandV1>().single()
        assertEquals(F.atomic("CRS.rebind.intents"), RefreshIntent.FORCE_PREMIUM, moved.intent)
    }
    /** END facade: the REQUEST list and the replacement binding reach the plan (§3.2 165; CRS:67–69). */
    @Test fun CRS_end_arguments() {
        val g = F.guard(auth = oldAuth); val old = F.request(binding = 2); val ids = Ids()
        val (store, facade) = facade(F.raw(g, old), ids)
        val c = facade.prepareEndAuthBinding(g, listOf(old), F.binding, closure(), F.binding, orders)
        val (result, raw) = run(store, facade, c, F.runtime(closure = closure()))
        assertTrue(F.atomic("CRS.end.arguments"), result is ControlStoreResult.Confirmed)
        val landed = rows(raw)
        assertEquals(F.atomic("CRS.end.arguments"), 3L, landed.filterIsInstance<DemandV1>().single().binding)
        assertEquals(F.atomic("CRS.end.arguments"), initialAuth(F.binding), landed.filterIsInstance<ScheduleGuardV1>().single().auth)
    }
    private fun authFreshId(id: String, initialize: Boolean) {
        val ids = Ids()
        val used = mutableSetOf<String>()
        repeat(2) {
            val g = if (initialize) null else F.guard()
            val source = if (g == null) F.raw() else F.raw(g)
            val (store, facade) = facade(source, ids)
            val issuedBefore = ids.issued
            val event: LifecycleAuthEvent = if (initialize) LifecycleAuthEvent.Initialize
                else LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)))
            val c = facade.prepareUpdateAuth(g, null, F.binding, event, orders)
            val issued = ((issuedBefore + 1)..ids.issued).map { UUID(0, it.toLong()).toString() }.toSet()
            val (result, raw) = run(store, facade, c, F.runtime())
            assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
            val landed = rows(raw)
            val newId = if (initialize) landed.filterIsInstance<ScheduleGuardV1>().singleOrNull()?.id
                else landed.filterIsInstance<DemandV1>().singleOrNull()?.id
            assertTrue(F.atomic(id), newId != null && newId in issued && c.id in issued &&
                newId != c.id && newId !in used && c.id !in used)
            used += checkNotNull(newId)
            used += c.id
        }
    }

    @Test fun CRS_auth_freshGuardId() = authFreshId("CRS.auth.freshGuardId", true)
    @Test fun CRS_auth_freshRetryId() = authFreshId("CRS.auth.freshRetryId", false)

    /** Two prepared commands never share a new id (§3.1 125 "필요한 새 REQUEST ID"). */
    @Test fun CRS_settle_idsAcrossCommands() {
        val r1 = F.request(id = "r1"); val r2 = F.request(id = "r2"); val ids = Ids()
        val (store, facade) = facade(F.raw(r1, r2), ids)
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
        val first = facade.prepareSettleQuery(listOf(r1), null, null, F.binding, d, orders)
        val (one, _) = run(store, facade, first, F.runtime())
        assertTrue("fixture: first settle lands", one is ControlStoreResult.Confirmed)
        val second = facade.prepareSettleQuery(listOf(r2), null, null, F.binding, d, LifecycleOrderSource(F.life, 40))
        val (two, raw) = run(store, facade, second, F.runtime())
        assertTrue(F.atomic("CRS.settle.idsAcrossCommands"), two is ControlStoreResult.Confirmed)
        assertEquals(F.atomic("CRS.settle.idsAcrossCommands"), 2, rows(raw).filterIsInstance<DemandV1>().map { it.id }.toSet().size)
    }

    // DP.descriptor — DP:34 carries the plan's fixed facts into the command unchanged (§3.1 125–127). The descriptor's
    // transition and ordered targets become the Applied evidence (DT:270–271), executor feeds the common currentness gate
    // (LC:220–222), requiredUnchanged feeds the preimage re-check (DT:28–29), and demandAuth selects the writer (LC:224).
    // Expected values are the fixture literals, never the plan's own lists.

    @Test fun DP_descriptor() {
        val g = F.guard(); val strong = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, strong)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val d = p.descriptor("cmd-x")
        fun facts(t: LifecycleFixedTarget) = listOf(t.target.kind, t.target.id, t.target.effect, t.role)
        assertEquals(F.retry("DP.descriptor.operationId"), "cmd-x", d.operationId)
        assertEquals(F.retry("DP.descriptor.operationId"), "cmd-x",
            ControlLifecycleConfirmation(F.codec).receipt(d, F.read(F.raw(g, strong))).commandId)
        assertEquals(F.atomic("DP.descriptor.transition"), LifecycleTransition.UPDATE_AUTH, d.transition)
        assertEquals(F.atomic("DP.descriptor.targets"), listOf(listOf(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE, LifecycleRole.GUARD)),
            d.targets.map(::facts))
        assertEquals(F.atomic("DP.descriptor.targets"), g.toPayloadEntry(), d.targets.single().before?.toPayloadEntry())
        val expectedGuard = F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 21), wait = 30000)
        assertEquals(F.atomic("DP.descriptor.targets"), expectedGuard.toPayloadEntry(),
            d.targets.single().after?.toPayloadEntry())
        assertEquals(F.atomic("DP.descriptor.executor"), SettlementExecutor("A", 3, F.life), d.executor)
        assertEquals(F.atomic("DP.descriptor.unchanged"), listOf(listOf(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST)),
            d.requiredUnchanged.map(::facts))
        assertEquals(F.atomic("DP.descriptor.unchanged"), strong.toPayloadEntry(), d.requiredUnchanged.single().before?.toPayloadEntry())
        assertEquals(F.atomic("DP.descriptor.unchanged"), strong.toPayloadEntry(), d.requiredUnchanged.single().after?.toPayloadEntry())
        assertSame(F.atomic("DP.descriptor.demandAuth"), p, d.demandAuth)
        assertTrue("descriptor: no namespace or other writer", d.namespace == null && d.removeEmptyGuard == null && d.recoverHold == null)
    }

    @Test fun DP_descriptor_targetOrder() {
        val z = F.request(id = "z", binding = 2)
        val a = F.request(id = "a", binding = 2)
        val p = DemandAuthPlan.rebind(listOf(z, a), F.binding, orders)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val d = p.descriptor("cmd-order")
        assertEquals(F.atomic("DP.descriptor.targetOrder"), listOf("z", "a"),
            d.targets.map { it.target.id })
        assertEquals(F.atomic("DP.descriptor.targetOrder"), listOf(z.toPayloadEntry(), a.toPayloadEntry()),
            d.targets.map { it.before?.toPayloadEntry() })
        assertEquals(F.atomic("DP.descriptor.targetOrder"),
            listOf(F.request(id = "z", order = 22).toPayloadEntry(), F.request(id = "a", order = 23).toPayloadEntry()),
            d.targets.map { it.after?.toPayloadEntry() })
    }

    // DP.fixed — DP:201–203: kind, id, role, before and after pass through; the effect follows before/after
    // (§3.2 150–153 wire effects; the approved oracle TV.commonPremises descriptor shape, TV:283–287: CREATE has no
    // before, REMOVE has no after, REPLACE has both). Direct helper boundary; RecoverHoldPlan also calls fixed().

    @Test fun DP_fixed() {
        val a = F.request(id = "a"); val b = F.request(id = "a", order = 9)
        fun check(id: String, before: ControlNode?, after: ControlNode?, effect: LifecycleEffect) {
            val t = fixed("a", LifecycleRole.REQUEST, before, after)
            assertEquals(F.atomic(id), LifecycleTarget(ControlKind.DEMAND, "a", effect), t.target)
            assertEquals(F.atomic(id), LifecycleRole.REQUEST, t.role)
            assertTrue(F.atomic(id), t.before === before && t.after === after)
        }
        check("DP.fixed.create", null, a, LifecycleEffect.CREATE)
        check("DP.fixed.remove", a, null, LifecycleEffect.REMOVE)
        check("DP.fixed.replace", a, b, LifecycleEffect.REPLACE)
        assertEquals(F.atomic("DP.fixed.role"), LifecycleRole.GUARD, fixed("g", LifecycleRole.GUARD, F.guard(), F.guard(wait = 1)).role)
    }
}
