package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F

import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.buffer
import okio.source
import java.io.File
import java.math.BigInteger
/** r2 direct boundaries; expected values come from fixed input literals. */
class RecoverHoldR2BoundaryTest {
    @Test fun HR_axisCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertEquals(setOf(PurgeScope.CAPABILITY), source.axes)
        val before = FenceV1("A", "u", null)
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable(source.subject.ownerUid))
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable("k"))
        assertNotNull(F.eligible("HR_axisCall"), RecoveryRetirementBoundary.inputProblem(source, before, F.ids.epochs))
    }

    @Test fun HR_freshInputCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertNull(raw[PURGE_JOURNAL]); assertTrue(F.read(raw).locations("not-uuid").isEmpty())
        assertNotNull(F.eligible("HR_freshInputCall"), RecoveryRetirementBoundary.freshEpochsProblem(source, i.before, RecoveryFreshEpochs(null, "not-uuid"), F.read(raw)))
    }

    @Test fun HR_floorPreimageCall() {
        val i = F.input(g = null); val p = F.plan(i)
        assertNull(p.preparationProblem); assertNull(i.guard)
        val raw = F.before(i); F.assertFixture(i, raw)
        val latest = raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = F.payload(listOf(F.guard(), F.request(), F.request("other", "B", RefreshIntent.FORCE_PREMIUM)))
        }.toPreferences()
        assertEquals(i.source.toPayloadEntry(), F.row(latest, ControlKind.HOLD, "h").toPayloadEntry())
        assertEquals(FloorV1("boot",10000,30000,LifetimeId("life")), (ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold).floor)
        assertFalse(F.read(latest).hasUninterpretable)
        assertNotNull(F.eligible("HR_floorPreimageCall"), p.preimageProblem(F.read(latest)))
    }

    @Test fun HR_intentMinimumCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val original = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val bad = original.copy(axes = emptySet())
        assertEquals(original.provenance, bad.provenance); assertEquals(original.floor, bad.floor)
        assertTrue(bad.axes.isEmpty())
        assertFalse(F.eligible("HR_intentMinimumCall"), HoldRecoveryBoundary.intentSatisfies(bad, RefreshIntent.FORCE_PREMIUM))
    }

    @Test fun RH_descriptorCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i); assertNull(p.preparationProblem)
        val read = F.read(raw)
        val c = F.command(p); val d = p.descriptor()
        assertNull(F.writer.eligibility(p, F.context(i), read, c.ownerTrackingLifetimeId.value))
        val bad = ControlLifecycleDescriptor("different", d.transition, d.targets, d.executor, d.namespace, d.requiredUnchanged, recoverHold = p)
        assertEquals(p.ids.operationId, c.id); assertEquals("different", bad.operationId)
        assertFalse(F.eligible("RH_descriptorCall"), F.writer.decide(c, bad, read, F.context(i)) is RecordTransactionDecision.Confirm)
    }

    @Test fun RH_runtime() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i); assertNull(p.preparationProblem)
        val read = F.read(raw)
        val context = F.context(i).copy(holdRecovery = null)
        assertEquals("A", context.ownerUid); assertEquals(3L, context.binding); assertEquals(LifetimeId("new-life"), context.originLifetimeId)
        assertFalse(context.signOutOpen); assertFalse(context.identityPersistencePending)
        assertNull(p.preimageProblem(read)); assertNull(context.holdRecovery)
        assertNotNull(F.eligible("RH_runtime"), F.writer.eligibility(p, context, read, "current-tracker"))
    }

    @Test fun RH_requestIdCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i); assertNull(p.preparationProblem)
        val read = F.read(raw)
        assertNull(F.writer.eligibility(p, F.context(i), read, "current-tracker"))
        val latest = raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = F.payload(listOf(i.guard!!, F.request(), F.request("other", "B", RefreshIntent.FORCE_PREMIUM), F.request("new-request")))
        }.toPreferences()
        assertFalse(F.read(latest).hasUninterpretable); assertFalse(F.read(latest).hasUninterpretableMetadata)
        assertNull(p.preimageProblem(F.read(latest)))
        assertEquals("new-request", demand(F.row(latest, ControlKind.DEMAND, "new-request"))?.id)
        assertNotNull(F.eligible("RH_requestIdCall"), F.writer.eligibility(p, F.context(i), F.read(latest), "current-tracker"))
    }

    @Test fun RH_departedRequest() {
        val i = F.input(h = F.hold(owner = "B", krx = "retired", floor = false), g = null)
        val before = F.before(i); val source = HoldRecoverySource.from(i.source)!!
        assertEquals(FenceV1("B", "u", "retired"), source.subject); assertEquals(FenceV1("A", "u", "k"), i.before)
        assertEquals(setOf(PurgeScope.CAPABILITY), source.axes); assertNull(source.hold.floor)
        val good = before.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
            this[PURGE_JOURNAL] = "B||retired|CAPABILITY"
        }.toPreferences()
        assertEquals("u", good[USER_EPOCH]); assertEquals("k", good[KRX_EPOCH])
        assertEquals(true, good[MAY_CONTAIN_PREMIUM]); assertEquals(true, good[MAY_CONTAIN_KRX])
        assertTrue(F.writer.requiredEffects(i, F.ids, null, F.read(before), F.read(good)))
        val bad = good.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = F.payload(listOf(F.request(), F.request("other", "B", RefreshIntent.FORCE_PREMIUM), F.request("new-request")))
        }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_departedRequest"), F.writer.requiredEffects(i, F.ids, null, F.read(before), F.read(bad)))
    }

    @Test fun LC_namespaceSelector() {
        val e = ControlLifecycleEvidenceFixtures; val engine = ControlLifecycleConfirmation(F.codec)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, FenceV1("A", "u", "different"), emptyList(), null, null)
        val d = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(e.removeGuard), namespace = ns)
        val c = e.command(d); val raw = e.raw(); val read = e.read(raw)
        assertTrue(engine.validDescriptor(d)); assertNull(ControlLifecycleBoundary.recordProblem(read))
        assertNull(ControlLifecycleBoundary.rawProblem(raw)); assertNull(ControlAppliedEvidence.own(read,c))
        assertNull(ControlLifecycleBoundary.postcondition(read, e.removeGuard))
        assertEquals(FenceV1("A", "u", "k"), F.beforeFence); assertEquals("k", raw[KRX_EPOCH])
        assertFalse(F.retry("LC_namespaceSelector"), engine.decide(c,d,read,null,true,true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_createSelectorCreate() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i); assertNull(p.preparationProblem)
        val read = F.read(raw)
        val c = F.command(p); val d = p.descriptor()
        assertTrue(read.locations("new-request").isEmpty())
        assertNull(ControlLifecycleBoundary.preimage(read, LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleRole.HOLD,i.source,null)))
        assertNull(F.writer.eligibility(p,F.context(i),read,c.ownerTrackingLifetimeId.value))
        assertTrue(F.atomic("LC_createSelectorCreate"), ControlLifecycleConfirmation(F.codec).decide(c,d,read,F.context(i),false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_executorContextNull() {
        val e = ControlLifecycleEvidenceFixtures; val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        val d = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(e.removeGuard), executor = F.executor, removeEmptyGuard = named)
        val raw = e.raw(demand = "[${e.guard.toPayloadEntry().fields}]"); val read = e.read(raw); val c = e.command(d)
        assertTrue(engine.validDescriptor(d)); assertNull(ControlLifecycleBoundary.recordProblem(read))
        assertNull(ControlLifecycleBoundary.rawProblem(raw)); assertNull(ControlLifecycleBoundary.preimage(read, e.removeGuard))
        assertTrue(ControlLifecycleBoundary.commandIdAvailable(read,c.id)); assertEquals(SettlementExecutor("A",3,LifetimeId("new-life")),d.executor)
        assertFalse(F.eligible("LC_executorContextNull"),engine.decide(c,d,read,null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun HR_prepareFloor() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        assertEquals(LifecycleBinding(SettlementExecutor("A",3,LifetimeId("new-life")),IdentityV1("A",2),1,"binding-start"),i.binding)
        assertNull(RecoveryRetirementBoundary.inputProblem(HoldRecoverySource.from(i.source)!!,i.before,F.ids.epochs))
        val bad = i.copy(mergeNow = BootReading("",11000))
        assertEquals(11000L,bad.mergeNow.elapsedMillis); assertEquals("",bad.mergeNow.bootId)
        assertNotNull(F.eligible("HR_prepareFloor"),F.plan(bad).preparationProblem)
    }

    @Test fun LC_recoverHoldPreparationProblem() {
        val i = F.input().copy(binding = F.binding.copy(executor = F.executor.copy(ownerUid = "B")))
        assertEquals("A", i.before.ownerUid); assertEquals("B", i.binding.executor.ownerUid)
        val p = F.plan(i); assertNotNull(p.preparationProblem)
        val d = p.descriptor(); val c = F.command(p); val engine = ControlLifecycleConfirmation(F.codec)
        assertTrue(engine.validDescriptor(d)); assertNull(d.namespace)
        assertEquals(listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE)),d.targets.map { it.target })
        val raw = F.before(i).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }.toPreferences()
        val read = F.read(raw); assertNull(ControlLifecycleBoundary.recordProblem(read)); assertNull(ControlLifecycleBoundary.rawProblem(raw))
        assertNull(ControlAppliedEvidence.own(read,c)); assertNull(ControlLifecycleBoundary.postcondition(read,d.targets.single()))
        assertFalse(F.eligible("LC_recoverHoldPreparationProblem"),engine.decide(c,d,read,null,true,true) is RecordTransactionDecision.Confirm)
    }

    @Test fun HR_changedGuard() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_changedGuard"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), p.targets.map { it.target })
    }

    @Test fun HR_unchangedGuard() {
        val g = F.field(F.guard(), "floor", FloorGuardFixtures.floor(29000,"boot",11000,"new-life"))
        val i = F.input(g = g); val raw = F.before(i); F.assertFixture(i,raw)
        assertEquals(FloorV1("boot",11000,29000,LifetimeId("new-life")),guard(g)?.floor)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_unchangedGuard"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)), p.targets.map { it.target })
    }

    @Test fun HR_requiredUnchanged() {
        val g = F.field(F.guard(), "floor", FloorGuardFixtures.floor(29000,"boot",11000,"new-life"))
        val i = F.input(g = g); val raw = F.before(i); F.assertFixture(i,raw)
        assertEquals(FloorV1("boot",11000,29000,LifetimeId("new-life")),guard(g)?.floor)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_requiredUnchanged"), listOf(LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE),LifecycleRole.GUARD,g,g)), p.requiredUnchanged)
    }

    @Test fun HR_changedNotUnchanged() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_changedNotUnchanged"), emptyList<LifecycleFixedTarget>(), p.requiredUnchanged)
    }

    @Test fun HR_currentRequest() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_currentRequest"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), p.targets.map { it.target })
    }

    @Test fun HR_departedNoRequest() {
        val i = F.input(h = F.hold(owner="B",krx="retired")); val raw = F.before(i)
        val h = HoldRecoverySource.from(i.source)!!
        assertEquals(FenceV1("B","u","retired"),h.subject); assertEquals(setOf(PurgeScope.CAPABILITY),h.axes)
        assertEquals(FenceV1("A","u","k"),i.before); assertEquals(BootReading("boot",11000),i.mergeNow)
        assertEquals(FloorV1("boot",10000,30000,LifetimeId("life")),h.hold.floor)
        assertFalse(F.read(raw).hasUninterpretable)
        val p = F.plan(i)
        assertEquals(F.atomic("HR_departedNoRequest"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE)), p.targets.map { it.target })
    }

    @Test fun RH_expectedCurrentRequest() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        assertEquals(F.atomic("RH_expectedCurrentRequest"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedDepartedRequest() {
        val i = F.input(h = F.hold(owner="B",krx="retired")); val raw = F.before(i)
        val h = HoldRecoverySource.from(i.source)!!
        assertEquals(FenceV1("B","u","retired"),h.subject); assertEquals(setOf(PurgeScope.CAPABILITY),h.axes)
        assertEquals(FenceV1("A","u","k"),i.before); assertEquals(BootReading("boot",11000),i.mergeNow)
        assertEquals(FloorV1("boot",10000,30000,LifetimeId("life")),h.hold.floor)
        assertFalse(F.read(raw).hasUninterpretable)
        assertEquals(F.atomic("RH_expectedDepartedRequest"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE)), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedChanged() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        assertEquals(F.atomic("RH_expectedChanged"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedUnchanged() {
        val g = F.field(F.guard(), "floor", FloorGuardFixtures.floor(29000,"boot",11000,"new-life"))
        val i = F.input(g = g); val raw = F.before(i); F.assertFixture(i,raw)
        assertEquals(FloorV1("boot",11000,29000,LifetimeId("new-life")),guard(g)?.floor)
        assertEquals(F.atomic("RH_expectedUnchanged"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedGuardCreate() {
        val i = F.input(g = null); val raw = F.before(i); F.assertFixture(i,raw)
        assertNull(i.guard)
        assertEquals(F.atomic("RH_expectedGuardCreate"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"new-guard",LifecycleEffect.CREATE), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedGuardReplace() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        assertEquals(F.atomic("RH_expectedGuardReplace"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_expectedFloor() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        assertEquals(F.atomic("RH_expectedFloor"), listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE)) + LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE), F.writer.expectedTargets(i,F.ids))
    }

    @Test fun RH_authVerbatimSelector() {
        val auth = """{"authStopAppliedOrder":20,"authStateOrder":10,"authStopped":false,"originLifetimeId":"life","binding":3,"authGeneration":2,"ownerUid":"A"}"""
        val original = F.node("""{"id":"g","kind":"SCHEDULE_GUARD","auth":$auth,"floor":{"anchorBootId":"boot","anchorElapsedMillis":10000,"waitMillis":10000,"originLifetimeId":"life"}}""")
        val typed = guard(original)!!
        assertEquals(AuthSnapshotV1("A",2,3,LifetimeId("life"),false,10,20),typed.auth)
        assertEquals(FloorV1("boot",10000,10000,LifetimeId("life")),typed.floor)
        val nextFloor = FloorV1("boot",11000,29000,LifetimeId("new-life"))
        val expected = F.field(original,"floor",FloorGuardFixtures.floor(29000,"boot",11000,"new-life"))
        assertEquals(auth,(original.toPayloadEntry().fields["auth"]).toString())
        val actual = guardNode(original,"g",typed.auth,nextFloor)
        assertEquals(F.atomic("RH_authVerbatimSelector"),expected.toPayloadEntry().fields.toString(),actual.toPayloadEntry().fields.toString())
    }
}
