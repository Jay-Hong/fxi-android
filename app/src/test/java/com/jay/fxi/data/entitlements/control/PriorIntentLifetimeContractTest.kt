package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.PurgeScope
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P2-K contract: the prior-intent lifetime boundary of [RecoveryIntentV1] (purger 설계 v3 final §7·§9 P2;
 * design by Codex in P2K/design_codex.r1.md, trimmed: the after/afterScope storage faults are the store's own contract,
 * and the restart path belongs to the §7 restart slice; K13~K17 added after the first battery, agreed in P2K/battery_codex.r1.md). A test observer records a request, an observation
 * and a protected use only when the gate is [PriorIntentGate.ReadyForTest]; every other state must record none.
 * The implementation thread reads but does not edit this file.
 */
class PriorIntentLifetimeContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("P2-K cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val current = FenceV1("A", "u", "k")
    private val life = LifetimeId("new-life")
    private val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)

    private suspend fun seeded(hold: String = "[]"): TerminationFixture = fixture().also { f ->
        f.edit { p -> p.clear(); p += ControlLifecycleEvidenceFixtures.raw(hold = hold); p[MAY_CONTAIN_PREMIUM] = true; p[MAY_CONTAIN_KRX] = true }
    }
    private fun intents(p: Preferences) = Json.parseToJsonElement(p[intentKey] ?: "[]").jsonArray
        .map { it.jsonObject.getValue("id").jsonPrimitive.content }
    private fun lifetime(f: TerminationFixture, target: String? = "k", axis: PurgeScope = PurgeScope.CAPABILITY, session: String = f.store.newSessionId()) =
        PriorIntentLifetime.prepare(f.store, session, "A", axis, target)

    /** The test observer: it records only through an open gate. */
    private class Observer { var requests = 0; var observations = 0; var protectedUses = 0
        fun tryAll(g: PriorIntentGate) { if (g is PriorIntentGate.ReadyForTest) { requests++; observations++; protectedUses++ } } }

    private fun sameProcess(lt: PriorIntentLifetime, joined: Set<String> = setOf("w")) =
        HoldRecoveryClosure.SameProcess(lt.source, binding.executor, 5, true, setOf("w"), joined)
    private suspend fun settle(lt: PriorIntentLifetime, closure: HoldRecoveryClosure) = controlTestTimeout("settle") {
        lt.settle(RecoverIntentInput(lt.source, current, binding, closure), LifecycleOrderSource(life, 21),
            AttemptContext("A", 3, life, false, false, intentRecovery = HoldRecoveryRuntime(binding, 5, true, setOf("w"), closure)))
    }
    private fun closed(id: String, g: PriorIntentGate, reason: PriorIntentClosedReason) {
        assertTrue("$id: Closed($reason), got $g", g is PriorIntentGate.Closed && g.reason == reason)
    }

    @Test fun K01_beforeConfirmation_theGateIsClosed_andNothingIsRecorded() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f); val o = Observer()
        val g = lt.gate(current); o.tryAll(g)
        closed("K01", g, PriorIntentClosedReason.AwaitingConfirmation)
        assertEquals(listOf(0, 0, 0), listOf(o.requests, o.observations, o.protectedUses))
        assertEquals("K01: nothing stored yet", emptyList<String>(), intents(f.disk()))
        assertEquals(PurgeScope.CAPABILITY to "k", lt.intent.axis to lt.intent.targetEpoch)
    }

    @Test fun K02_aConfirmedIntentOpensTheGate_andReconfirmingAddsNoRow() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f)
        val g = controlTestTimeout("confirm") { lt.confirmPrior(current) }
        assertTrue("K02: ReadyForTest, got $g", g is PriorIntentGate.ReadyForTest && g.intent == lt.intent)
        assertEquals(listOf(lt.intent.id), intents(f.disk()))
        assertTrue("K02 again", controlTestTimeout("confirm again") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        assertEquals("K02: one row", listOf(lt.intent.id), intents(f.disk()))
        assertTrue("K02: gate stays open", lt.gate(current) is PriorIntentGate.ReadyForTest)
    }

    @Test fun K03_aWriteFaultKeepsTheGateClosed_andTheSameCommandConfirmsOnRetry() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f); val o = Observer()
        f.storage.storage.before = true
        val failed = try { controlTestTimeout("confirm") { lt.confirmPrior(current) } } finally { f.storage.storage.before = false }
        o.tryAll(failed); o.tryAll(lt.gate(current))
        closed("K03", failed, PriorIntentClosedReason.StorageUnconfirmed)
        assertEquals(listOf(0, 0, 0), listOf(o.requests, o.observations, o.protectedUses))
        assertEquals("K03: nothing landed", emptyList<String>(), intents(f.disk()))
        val addition = lt.addition
        assertTrue("K03: retry opens", controlTestTimeout("retry") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        assertTrue("K03: the same command", addition === lt.addition)
        assertEquals(listOf(lt.intent.id), intents(f.disk()))
    }

    @Test fun K06_anUnsafeRecordOrAFenceThatIsNotTheTarget_neverOpens() = runReleaseTest {
        val unsafe = seeded(hold = "[7]"); val a = lifetime(unsafe)
        closed("K06 unsafe", controlTestTimeout("confirm") { a.confirmPrior(current) }, PriorIntentClosedReason.UnsafeRecord)
        val other = seeded(); val b = lifetime(other)
        closed("K06 other fence", controlTestTimeout("confirm") { b.confirmPrior(FenceV1("A", "u", "k2")) }, PriorIntentClosedReason.TargetChanged)
    }

    @Test fun K08_aChangedTargetClosesTheOldGateForGood_andTheNewTargetNeedsItsOwnIntent() = runReleaseTest {
        val f = seeded(); val old = lifetime(f)
        assertTrue(controlTestTimeout("confirm") { old.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        closed("K08 changed", old.gate(FenceV1("A", "u", "k2")), PriorIntentClosedReason.TargetChanged)
        closed("K08 for good", old.gate(current), PriorIntentClosedReason.TargetChanged)
        assertEquals("K08: the old intent stays", listOf(old.intent.id), intents(f.disk()))
        val next = lifetime(f, target = "k2")
        closed("K08 new target", next.gate(FenceV1("A", "u", "k2")), PriorIntentClosedReason.AwaitingConfirmation)
    }

    @Test fun K09_settlementWithoutProvedClosure_orWithTheGateOpen_retainsEverything() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f)
        val unconfirmed = settle(lt, sameProcess(lt))
        assertTrue("K09 unconfirmed: Retained(GateStillOpen), got $unconfirmed", unconfirmed is PriorIntentSettlement.Retained && unconfirmed.reason == PriorIntentRetainedReason.GateStillOpen)
        assertTrue(controlTestTimeout("confirm") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        val open = settle(lt, sameProcess(lt))
        assertTrue("K09 open gate: Retained(GateStillOpen), got $open", open is PriorIntentSettlement.Retained && open.reason == PriorIntentRetainedReason.GateStillOpen)
        lt.closeLocalGate()
        closed("K09 closing", lt.gate(current), PriorIntentClosedReason.Closing)
        val before = f.disk()
        val unproved = settle(lt, sameProcess(lt, joined = emptySet()))
        assertTrue("K09 unjoined: Retained(ClosureUnproved), got $unproved", unproved is PriorIntentSettlement.Retained && unproved.reason == PriorIntentRetainedReason.ClosureUnproved)
        assertEquals("K09: record untouched", before, f.disk())
    }

    @Test fun K10_aProvedSettlementHandsOverOnlyThisIntent_otherSessionsAndAxesStay() = runReleaseTest {
        val f = seeded(); val session = f.store.newSessionId()
        val lt = lifetime(f, session = session); val otherAxis = lifetime(f, axis = PurgeScope.USER, target = "u", session = session)
        val otherSession = lifetime(f)
        for (x in listOf(lt, otherAxis, otherSession)) assertTrue(controlTestTimeout("confirm") { x.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        lt.closeLocalGate()
        val done = settle(lt, sameProcess(lt))
        assertTrue("K10: Settled, got $done", done is PriorIntentSettlement.Settled)
        val disk = f.disk()
        assertEquals("K10: only this intent left the record", setOf(otherAxis.intent.id, otherSession.intent.id), intents(disk).toSet())
        assertNotNull("K10: the journal was handed over", disk[PURGE_JOURNAL])
        closed("K10 settled", lt.gate(current), PriorIntentClosedReason.Settled)
    }

    @Test fun K11_aHandoverWriteFaultRetains_andTheRetryUsesTheSameCommand() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f)
        assertTrue(controlTestTimeout("confirm") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        lt.closeLocalGate()
        f.storage.storage.before = true
        val failed = try { settle(lt, sameProcess(lt)) } finally { f.storage.storage.before = false }
        assertTrue("K11: Retained(StorageUnconfirmed), got $failed", failed is PriorIntentSettlement.Retained && failed.reason == PriorIntentRetainedReason.StorageUnconfirmed)
        val first = checkNotNull((failed as PriorIntentSettlement.Retained).storage).command
        assertTrue("K11: the first handover is unresolved", first in f.tracker.recoverySnapshot().unresolvedCommands)
        assertEquals("K11: the intent stays", listOf(lt.intent.id), intents(f.disk()))
        val done = settle(lt, sameProcess(lt))
        assertTrue("K11 retry: Settled, got $done", done is PriorIntentSettlement.Settled)
        assertTrue("K11: the retry confirmed the same command", (done as PriorIntentSettlement.Settled).confirmation.command === first)
        assertTrue("K11: nothing left unresolved", first !in f.tracker.recoverySnapshot().unresolvedCommands)
        assertEquals(emptyList<String>(), intents(f.disk()))
    }

    @Test fun K13_aRecordTheAdditionCannotUse_isUnsafe_andNothingIsStored() = runReleaseTest {
        val f = fixture(); f.edit { p -> p.clear(); p += ControlLifecycleEvidenceFixtures.raw(schema = 1) }
        val lt = lifetime(f); val o = Observer()
        val g = controlTestTimeout("confirm") { lt.confirmPrior(current) }; o.tryAll(g)
        closed("K13", g, PriorIntentClosedReason.UnsafeRecord)
        assertTrue("K13: the store answered RecoveryRequired", (g as PriorIntentGate.Closed).storage is ControlStoreResult.RecoveryRequired)
        assertEquals(listOf(0, 0, 0), listOf(o.requests, o.observations, o.protectedUses))
        assertEquals(emptyList<String>(), intents(f.disk()))
    }

    @Test fun K14_aStoredFenceThatIsNotTheCurrentOne_keepsTheGateClosed_thoughTheIntentLanded() = runReleaseTest {
        val f = seeded(); f.edit { it[KRX_EPOCH] = "kOld" }
        val lt = lifetime(f); val o = Observer()
        val g = controlTestTimeout("confirm") { lt.confirmPrior(current) }; o.tryAll(g)
        closed("K14", g, PriorIntentClosedReason.UnsafeRecord)
        assertTrue("K14: the addition itself was confirmed", (g as PriorIntentGate.Closed).storage is ControlStoreResult.Confirmed)
        assertEquals(listOf(0, 0, 0), listOf(o.requests, o.observations, o.protectedUses))
        assertEquals("K14: the intent landed", listOf(lt.intent.id), intents(f.disk()))
    }

    @Test fun K15_settlementOfAnotherSource_retainsEverything() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f); val other = lifetime(f)
        assertTrue(controlTestTimeout("confirm") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        lt.closeLocalGate()
        val before = f.disk()
        val wrong = HoldRecoveryClosure.SameProcess(other.source, binding.executor, 5, true, setOf("w"), setOf("w"))
        val r = controlTestTimeout("settle") { lt.settle(RecoverIntentInput(other.source, current, binding, wrong), LifecycleOrderSource(life, 21),
            AttemptContext("A", 3, life, false, false, intentRecovery = HoldRecoveryRuntime(binding, 5, true, setOf("w"), wrong))) }
        assertTrue("K15: Retained(SourceMismatch), got $r", r is PriorIntentSettlement.Retained && r.reason == PriorIntentRetainedReason.SourceMismatch && r.storage == null)
        assertEquals("K15: record untouched", before, f.disk())
    }

    @Test fun K16_aReturnedSnapshotWithoutTheIntent_keepsTheGateClosed_andARetryOpensIt() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f)
        f.boundary.afterReturn = { p -> p.toMutablePreferences().apply { this[intentKey] = "[]" }.toPreferences() }
        val g = controlTestTimeout("confirm") { lt.confirmPrior(current) }
        closed("K16", g, PriorIntentClosedReason.UnsafeRecord)
        assertTrue("K16: the store confirmed", (g as PriorIntentGate.Closed).storage is ControlStoreResult.Confirmed)
        assertEquals("K16: the intent is on disk", listOf(lt.intent.id), intents(f.disk()))
        assertTrue("K16: a retry reads the true snapshot", controlTestTimeout("retry") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
    }

    @Test fun K17_afterATargetChange_theOldIntentCanStillBeSettled() = runReleaseTest {
        val f = seeded(); val lt = lifetime(f); val other = lifetime(f, axis = PurgeScope.USER, target = "u")
        for (x in listOf(lt, other)) assertTrue(controlTestTimeout("confirm") { x.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
        val next = FenceV1("A", "u", "k2")
        f.edit { it[KRX_EPOCH] = "k2" }
        closed("K17 changed", lt.gate(next), PriorIntentClosedReason.TargetChanged)
        val closure = sameProcess(lt)
        val done = controlTestTimeout("settle") { lt.settle(RecoverIntentInput(lt.source, next, binding, closure), LifecycleOrderSource(life, 21),
            AttemptContext("A", 3, life, false, false, intentRecovery = HoldRecoveryRuntime(binding, 5, true, setOf("w"), closure))) }
        assertTrue("K17: Settled, got $done", done is PriorIntentSettlement.Settled)
        val disk = f.disk()
        assertEquals("K17: only the old intent left", listOf(other.intent.id), intents(disk))
        assertNotNull("K17: its journal was handed over", disk[PURGE_JOURNAL])
        assertEquals("K17: the current epoch stays", "k2", disk[KRX_EPOCH])
        closed("K17 settled", lt.gate(next), PriorIntentClosedReason.Settled)
    }

    @Test fun K12_nothingInProductionReachesTheModel() {
        val sources = File("src/main/java/com/jay/fxi").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the walk must see the model", sources.any { it.name == "PriorIntentLifetime.kt" })
        assertEquals(emptyList<String>(), sources.filter { it.name != "PriorIntentLifetime.kt" && it.readText().contains("PriorIntentLifetime") }.map { it.name })
    }
}
