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
/** Independent fixtures; official execution is performed outside this implementation task. */
class RecoverHoldLegacyBoundaryTest {
    @Test fun LC_recordCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val oldSchema = e.raw(schema = 1)
        assertEquals(1, e.read(oldSchema).schemaVersion)
        assertFalse(F.eligible("LC_recordCall"), engine.decide(c, d, e.read(oldSchema), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_descriptorCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = ControlLifecycleDescriptor("lc", LifecycleTransition.RECOVER_HOLD, d.targets)
        assertFalse(F.eligible("LC_descriptorCall"), engine.decide(c, bad, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_rawCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = raw.toMutablePreferences().apply { this[USER_EPOCH] = "" }.toPreferences()
        assertFalse(e.read(bad).hasUninterpretable)
        assertFalse(F.eligible("LC_rawCall"), engine.decide(c, d, e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_historyOwn() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        assertFalse(F.retry("LC_historyOwn"), engine.decide(c, d, e.read(raw), null, true, false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_historyConfirmed() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(F.retry("LC_historyConfirmed"), engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_postTargets() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = e.raw(demand = "[${e.guard.toPayloadEntry().fields}]")
        assertFalse(F.retry("LC_postTargets"), engine.decide(c, d, e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_postUnchanged() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val unchanged = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, e.request, e.request)
        val dx = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, requiredUnchanged = listOf(unchanged))
        assertTrue(engine.validDescriptor(dx))
        assertFalse(F.retry("LC_postUnchanged"), engine.decide(c, dx, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_postCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val unchanged = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, e.request, e.request)
        val dx = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, requiredUnchanged = listOf(unchanged))
        assertTrue(engine.validDescriptor(dx))
        assertFalse(F.retry("LC_postCall"), engine.decide(c, dx, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_markerUser() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = good.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = true }.toPreferences()
        assertFalse(F.retry("LC_markerUser"), engine.decide(c, dx(), e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_markerUserNullable() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val nullable = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, ns.journal, null, false)
        assertTrue(F.retry("LC_markerUserNullable"), engine.decide(c, dx(nullable), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_markerKrx() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = true }.toPreferences()
        assertFalse(F.retry("LC_markerKrx"), engine.decide(c, dx(), e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_markerKrxNullable() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val nullable = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, ns.journal, false, null)
        assertTrue(F.retry("LC_markerKrxNullable"), engine.decide(c, dx(nullable), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_namespaceAfter() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = good.toMutablePreferences().apply { this[USER_EPOCH] = "other" }.toPreferences()
        assertFalse(F.retry("LC_namespaceAfter"), engine.decide(c, dx(), e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_journalContains() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = good.toMutablePreferences().apply { remove(PURGE_JOURNAL) }.toPreferences()
        assertFalse(F.retry("LC_journalContains"), engine.decide(c, dx(), e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_journalParse() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val d = e.descriptor(); val c = e.command(d)
        val raw = e.raw()
        assertEquals(2, e.read(raw).schemaVersion)
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        assertEquals(listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE)), d.targets.map { it.target })
        assertNull(ControlAppliedEvidence.own(e.read(raw), c))
        assertTrue(engine.decide(c, d, e.read(raw), null, true, true) is RecordTransactionDecision.Confirm)
        val ns = LifecycleNamespacePostcondition(F.beforeFence, F.beforeFence, listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), false, false)
        fun dx(n: LifecycleNamespacePostcondition = ns) = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, d.targets, namespace = n)
        val good = raw.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        assertTrue(engine.decide(c, dx(), e.read(good), null, true, true) is RecordTransactionDecision.Confirm)
        val bad = good.toMutablePreferences().apply { this[PURGE_JOURNAL] = "A||k|CAPABILITY|future" }.toPreferences()
        assertFalse(F.retry("LC_journalParse"), engine.decide(c, dx(), e.read(bad), null, true, true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_unchangedDetach() {
        val e = ControlLifecycleEvidenceFixtures
        val row = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, e.request, e.request)
        val supplied = mutableListOf(row, row)
        val d = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(e.removeGuard), requiredUnchanged = supplied)
        assertEquals(listOf(row, row), supplied)
        supplied.clear()
        assertEquals(F.atomic("LC_unchangedDetach"), listOf(row, row), d.requiredUnchanged)
    }

    @Test fun LC_unchangedImmutable() {
        val e = ControlLifecycleEvidenceFixtures
        val row = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, e.request, e.request)
        val supplied = mutableListOf(row, row)
        val d = ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(e.removeGuard), requiredUnchanged = supplied)
        assertEquals(listOf(row, row), supplied)
        try { (d.requiredUnchanged as MutableList<LifecycleFixedTarget>).add(row) } catch (_: UnsupportedOperationException) { }
        assertEquals(F.atomic("LC_unchangedImmutable"), listOf(row, row), d.requiredUnchanged)
    }

    @Test fun LC_beforeNamespaceCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val ns=LifecycleNamespacePostcondition(F.beforeFence.copy(userAccessEpoch="other"),F.beforeFence,emptyList())
        val bad=descriptor(ns)
        assertFalse(F.eligible("LC_beforeNamespaceCall"),engine.decide(c,bad,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_beforeNamespaceBranch() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val ns=LifecycleNamespacePostcondition(F.beforeFence.copy(userAccessEpoch="other"),F.beforeFence,emptyList())
        assertFalse(F.eligible("LC_beforeNamespaceBranch"),engine.decide(c,descriptor(ns),e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_executorCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val bad=descriptor(executor=F.executor)
        assertFalse(F.eligible("LC_executorCall"),engine.decide(c,bad,e.read(raw),F.context().copy(signOutOpen=true),false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_executorBranch() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val bad=descriptor(executor=F.executor)
        assertFalse(F.eligible("LC_executorBranch"),engine.decide(c,bad,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_preimageCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val actual=e.raw(demand="[${F.guard(0).toPayloadEntry().fields}]")
        assertNotNull(guard(F.guard(0))?.floor)
        assertFalse(F.eligible("LC_preimageCall"),engine.decide(c,d,e.read(actual),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_createSelectorRemove() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertNull(ControlLifecycleBoundary.preimage(e.read(raw), e.removeGuard))
        val absent=e.raw()
        assertTrue(e.read(absent).locations("g").isEmpty())
        assertFalse(F.eligible("LC_createSelectorRemove"),engine.decide(c,d,e.read(absent),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_commandSealCall() {
        val e = ControlLifecycleEvidenceFixtures
        val engine = ControlLifecycleConfirmation(F.codec)
        val named = RemoveEmptyGuardPlan.prepare(e.guard)
        fun descriptor(ns: LifecycleNamespacePostcondition? = null, executor: SettlementExecutor? = null) =
            ControlLifecycleDescriptor("lc",LifecycleTransition.REMOVE_EMPTY_GUARD,listOf(e.removeGuard),executor,ns,removeEmptyGuard=named)
        val raw=e.raw(demand="[${e.guard.toPayloadEntry().fields}]")
        assertFalse(e.read(raw).hasUninterpretable); assertFalse(e.read(raw).hasUninterpretableMetadata)
        val d=descriptor(); val c=e.command(d)
        assertTrue(engine.decide(c,d,e.read(raw),null,false,false) is RecordTransactionDecision.Confirm)
        val seal=ControlObligationFixtures.settledSeal.replace("\"operationId\":\"op\"", "\"operationId\":\"lc\"")
        val actual=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]="[$seal]" }.toPreferences()
        assertFalse(e.read(actual).hasUninterpretable)
        assertFalse(F.eligible("LC_commandSealCall"),engine.decide(c,d,e.read(actual),null,false,false) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_prepareFailure() {
        val e=ControlLifecycleEvidenceFixtures; val f=DemandAuthFixtures
        val caller=LifecycleCaller("caller",LifecycleCallerOrigin.CALLER,f.binding,LifecycleOrderGrant(f.life,20,1,0,21),RefreshIntent.FORCE_PREMIUM,BootReading("",10000))
        val p=DemandAuthPlan.auth(f.guard(wait=30000),null,f.binding,LifecycleAuthEvent.Caller(caller),LifecycleOrderSource(f.life,21),"g","new-r")
        assertNotNull(p.preparationFailure)
        val d=p.descriptor("lc"); val c=e.command(d)
        val engine=ControlLifecycleConfirmation(F.codec)
        assertTrue(engine.validDescriptor(d))
        val raw=e.raw(demand=F.payload(d.targets.mapNotNull { it.after }))
        assertFalse(e.read(raw).hasUninterpretable)
        assertFalse(F.eligible("LC_prepareFailure"),engine.decide(c,d,e.read(raw),null,true,true) is RecordTransactionDecision.Confirm)
    }

    @Test fun LC_namedDemandAuth() {
        val f=DemandAuthFixtures
        val p=DemandAuthPlan.auth(null,null,f.binding,LifecycleAuthEvent.Initialize,LifecycleOrderSource(f.life,21),"g","r")
        assertNull(p.preparationFailure)
        val c=f.command(p); val d=(c.body as ControlCommandBody.Lifecycle).input
        val raw=f.raw()
        val result=ControlLifecycleConfirmation(F.codec).decide(c,d,f.read(raw),f.context(),false,false)
        assertTrue(F.atomic("LC_namedDemandAuth"),result is RecordTransactionDecision.Confirm)
    }
}
