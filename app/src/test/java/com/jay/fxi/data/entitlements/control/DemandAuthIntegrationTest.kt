package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemandAuthIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun owner(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("integration seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("integration cleanup", 30000) { opened.forEach { it.close() } } }
    private fun positive(result: ControlStoreResult): ControlStoreResult.Confirmed {
        assertTrue("expected storage confirmation, got $result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    private fun row(raw: Preferences, id: String): ControlNode = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original
    private suspend fun failedUnrelated(o: ControlStoreTestStorage): CommandRef {
        val command = o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            set("id", ControlScalar.Text(id)); set("sessionId", ControlScalar.Text("other-session")); set("ownerUid", ControlScalar.Text("B"))
            set("axis", ControlScalar.Text("CAPABILITY")); set("targetEpoch", ControlScalar.Null)
        })
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        return command
    }
    @Test fun A12_namedInitializationAndFloorFacade() = runReleaseTest {
        val g = F.guard(auth = null, wait = 60000)
        val o = owner(F.raw(g))
        val c = o.control.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life))
        val result = positive(o.control.execute(c, F.context()))
        val installed = row(result.snapshot.record.original, "g")
        assertEquals(F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0), guard(installed)!!.auth)
        assertEquals(60000L, guard(installed)!!.floor!!.waitMillis)
        val floor = o.control.prepare(o.control.recordFloor(installed, F.now, 90000, F.life))
        val changed = positive(o.control.execute(floor)).snapshot.record.original
        assertEquals(installed.toPayloadEntry().fields["auth"], row(changed, "g").toPayloadEntry().fields["auth"])
        assertEquals(90000L, guard(row(changed, "g"))!!.floor!!.waitMillis)
    }
    @Test fun A12_namedCreate() = runReleaseTest {
        val o = owner(F.raw())
        val c = o.control.prepareUpdateAuth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life))
        val result = positive(o.control.execute(c, F.context()))
        assertEquals(1, result.effectiveIds.size)
        assertEquals(0L, guard(row(result.snapshot.record.original, result.effectiveIds.single()))!!.auth!!.authStateOrder)
    }
    private suspend fun genericConsumed(edit: Boolean, id: String) {
        val o = owner(F.raw())
        val add = o.control.prepare(o.control.addition(ControlKind.DEMAND) { uid -> literal(F.request(id = uid).toPayloadEntry().fields.toString()) })
        val added = positive(o.control.execute(add))
        var r = row(added.snapshot.record.original, added.effectiveIds.single())
        val old = if (edit) {
            val c = o.control.prepare(o.control.edit(ControlKind.DEMAND, r) { set("raisedAt", ControlScalar.Integer(5)) })
            r = row(positive(o.control.execute(c)).snapshot.record.original, added.effectiveIds.single())
            c
        } else add
        val consume = o.control.prepareSettleQuery(listOf(r), null, null, F.binding, F.decision(), LifecycleOrderSource(F.life, 21))
        val after = positive(o.control.execute(consume, F.context())).snapshot.record.original
        val other = failedUnrelated(o)
        val retry = o.control.execute(old)
        assertTrue(F.retry(id), retry !is ControlStoreResult.Confirmed && o.raw() == after && retry.localUnresolvedCommands == setOf(other))
    }
    @Test fun R14_add() = runReleaseTest { genericConsumed(false, "R14.add") }
    @Test fun R14_edit() = runReleaseTest { genericConsumed(true, "R14.edit") }
    @Test fun R14_floor() = runReleaseTest {
        val g = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")))
        val o = owner(F.raw(g))
        val floor = o.control.prepare(o.control.recordFloor(g, F.now, 60000, F.life))
        val recorded = row(positive(o.control.execute(floor)).snapshot.record.original, "g")
        val closure = LifecycleBindingClosure(guard(recorded)!!.auth!!, true, emptySet(), emptySet(), 5)
        val end = o.control.prepareEndAuthBinding(recorded, emptyList(), F.binding, closure, F.binding, LifecycleOrderSource(F.life, 21))
        val after = positive(o.control.execute(end, F.context(F.runtime(closure = closure)))).snapshot.record.original
        val other = failedUnrelated(o)
        val retry = o.control.execute(floor)
        assertTrue(F.retry("R14.floor"), retry !is ControlStoreResult.Confirmed && o.raw() == after && retry.localUnresolvedCommands == setOf(other))
    }
    @Test fun genericFacadeRefusesAuthInstallation() = runReleaseTest {
        val o = owner(F.raw())
        val c = o.control.prepare(o.control.addition(ControlKind.DEMAND) { id -> literal(F.guard(id = id).toPayloadEntry().fields.toString()) })
        val before = o.raw()
        assertTrue(F.eligible("A10facade"), o.control.execute(c) is ControlStoreResult.Rejected)
        assertEquals(before, o.raw())
    }
    @Test fun callerRetainsFloorAndCreatesRequest() = runReleaseTest {
        val g = F.guard(wait = 60000); val o = owner(F.raw(g)); val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(1, 21)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        val c = o.control.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Caller(caller), orders)
        val result = positive(o.control.execute(c, F.context(F.runtime(caller = caller))))
        val after = guard(row(result.snapshot.record.original, "g"))!!
        assertEquals(22L, after.auth!!.authStateOrder); assertEquals(20L, after.auth.authStopAppliedOrder)
        assertEquals(60000L, after.floor!!.waitMillis)
        val request = result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>().mapNotNull { it.value as? DemandV1 }.single()
        assertEquals(RefreshIntent.FORCE_PREMIUM, request.intent); assertEquals(23L, request.raisedAt.value)
    }
    @Test fun credentialRecoveryPreservesRequestAndFloor() = runReleaseTest {
        val g = F.guard(wait = 60000); val r = F.request(); val source = F.raw(g, r); val o = owner(source)
        val rec = LifecycleRecovery("rec", F.identity, F.life, 11, 22, 1)
        val c = o.control.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Recovery(rec), LifecycleOrderSource(F.life, 22))
        o.storage.before = true
        val failure = o.control.execute(c, F.context(F.runtime(recovery = rec)))
        assertTrue(failure is ControlStoreResult.Unconfirmed)
        assertEquals(source, o.raw())
        val result = positive(o.control.execute(c, F.context(F.runtime(recovery = rec))))
        assertEquals(r.toPayloadEntry(), row(result.snapshot.record.original, "r").toPayloadEntry())
        assertEquals(g.toPayloadEntry().fields["floor"], row(result.snapshot.record.original, "g").toPayloadEntry().fields["floor"])
        assertEquals(22L, guard(row(result.snapshot.record.original, "g"))!!.auth!!.authStateOrder)
    }
    @Test fun authAnswerStopsWithoutConsumingRequest() = runReleaseTest {
        val g = F.guard(); val r = F.request(); val o = owner(F.raw(g, r))
        val d = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60))
        val c = o.control.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Answer(d), LifecycleOrderSource(F.life, 21))
        val result = positive(o.control.execute(c, F.context()))
        assertEquals(F.auth.copy(authStateOrder = 21, authStopAppliedOrder = 22), guard(row(result.snapshot.record.original, "g"))!!.auth)
        assertEquals(r.toPayloadEntry(), row(result.snapshot.record.original, "r").toPayloadEntry())
        assertEquals(60000L, guard(row(result.snapshot.record.original, "g"))!!.floor!!.waitMillis)
    }
    @Test fun Q16c_successorExhaustionKeepsEverything() = runReleaseTest {
        val r = F.request(); val g = F.guard(); val raw = F.raw(r, g); val o = owner(raw)
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
        val c = o.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, LifecycleOrderSource(F.life, Long.MAX_VALUE))
        val result = o.control.execute(c, F.context())
        assertTrue(F.atomic("Q16c_writer"), result is ControlStoreResult.Rejected && o.raw() == raw)
        assertEquals(RejectionReason.InvalidRequest("OrderExhausted"), (result as ControlStoreResult.Rejected).reason)
    }
    @Test fun Q16b_rebindExhaustionKeepsEverything() = runReleaseTest {
        val r = F.request(binding = 2); val raw = F.raw(r); val o = owner(raw)
        val c = o.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, Long.MAX_VALUE))
        assertTrue(F.atomic("Q16b_writer"), o.control.execute(c, F.context()) is ControlStoreResult.Rejected && o.raw() == raw)
    }
    @Test fun ownDecisionBeforeAfter() = runReleaseTest {
        val after = F.fence.copy(userAccessEpoch = "rotated")
        val r = F.request(); val source = F.raw(r).toMutablePreferences().apply { this[DataStoreAccessEpochStore.USER_EPOCH] = "rotated" }
        val o = owner(source)
        val d = F.decision(after = after, afterGeneration = 6, namespace = ConfirmedControlSnapshot(F.read(source)))
        val c = o.control.prepareSettleQuery(listOf(r), null, null, F.binding, d, LifecycleOrderSource(F.life, 21))
        val result = positive(o.control.execute(c, F.context(F.runtime(generation = 6))))
        assertTrue(result.snapshot.record.locations("r").isEmpty())
        assertEquals("rotated", result.snapshot.record.original[DataStoreAccessEpochStore.USER_EPOCH])
    }
    @Test fun AtoBtoA() = runReleaseTest {
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = 500)
        val other = F.request(id = "other", owner = "B")
        val source = F.raw(r, other); val o = owner(source)
        val b = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
        controlTestTimeout("switch to B") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" } } }
        val refused = o.control.prepareRebindRequests(listOf(r), b, LifecycleOrderSource(F.life, 21))
        assertTrue(o.control.execute(refused, F.context(F.runtime(binding = b, live = b.identity))) is ControlStoreResult.Rejected)
        assertEquals(r.toPayloadEntry(), row(o.raw(), "r").toPayloadEntry())
        controlTestTimeout("return to A") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "A" } } }
        val orders = LifecycleOrderSource(F.life, 21)
        val rebound = positive(o.control.execute(o.control.prepareRebindRequests(listOf(r), F.binding, orders), F.context()))
        val fresh = row(rebound.snapshot.record.original, "r")
        val q = F.query.copy(order = EventOrderV1(F.life, orders.issue(1)!!.value))
        val d = F.decision(q = q)
        val c = o.control.prepareSettleQuery(listOf(fresh), null, null, F.binding, d, orders)
        val result = positive(o.control.execute(c, F.context(F.runtime(registrations = listOf(d.registration)))))
        assertTrue(result.snapshot.record.locations("r").isEmpty())
        assertEquals(other.toPayloadEntry(), row(result.snapshot.record.original, "other").toPayloadEntry())
    }
    private suspend fun successor(landedFailure: Boolean, id: String) {
        val r = F.request(); val o = owner(F.raw(r)); val orders = LifecycleOrderSource(F.life, 21)
        val q1 = o.control.prepareSettleQuery(listOf(r), null, null, F.binding, F.decision(followUp = RefreshIntent.FORCE_PREMIUM), orders)
        o.storage.afterScope = landedFailure
        val first = o.control.execute(q1, F.context())
        if (landedFailure) assertTrue(first is ControlStoreResult.Unconfirmed) else positive(first)
        // DataStore resumes from the physically landed candidate on this same-ref observation.
        if (landedFailure) {
            val read = F.read(o.raw())
            assertTrue(ControlAppliedEvidence.own(read, q1) != null)
        }
        val current = F.read(o.raw()).arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>().single().original
        val q = F.query.copy(order = EventOrderV1(F.life, orders.issue(1)!!.value))
        val d = F.decision(q = q)
        val q2 = o.control.prepareSettleQuery(listOf(current), null, null, F.binding, d, orders)
        val second = positive(o.control.execute(q2, F.context(F.runtime(registrations = listOf(d.registration)))))
        assertEquals(if (landedFailure) setOf(q1) else emptySet<CommandRef>(), second.localUnresolvedCommands)
        val before = o.raw()
        val previous = o.control.execute(q1)
        assertTrue(F.retry(id), previous is ControlStoreResult.Conflict && o.raw() == before)
        assertEquals(F.retry(id), if (landedFailure) setOf(q1) else emptySet<CommandRef>(), previous.localUnresolvedCommands)
        assertEquals(LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, q1.lastLifecycleDiagnostic!!.classification)
    }
    @Test fun R15a() = runReleaseTest { successor(false, "R15a") }
    @Test fun R15b() = runReleaseTest { successor(true, "R15b") }
}
