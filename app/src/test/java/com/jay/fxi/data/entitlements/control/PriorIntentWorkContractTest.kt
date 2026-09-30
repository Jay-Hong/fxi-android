package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.PurgeScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P3-c1 contract (purger 설계 v3 final §7 선행 복구 의도: "관련 in-flight가 끝나고 접근이 닫혔으며 …
 * 뒤에만 의도를 해제"; P3c/design_codex.r1.md c1). Work may begin only through an open gate of one prior-intent
 * lifetime; closing publishes before any new begin; closeAndDrain returns only after every begun work has finished;
 * the intent is not handed over while work is still running. Still a test model: nothing in production calls it (K12),
 * and a work lease is not an admission or access token. The implementation reads but does not edit this file.
 */
class PriorIntentWorkContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("P3-c1 cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private val current = FenceV1("A", "u", "k")
    private val life = LifetimeId("new-life")
    private val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")

    private suspend fun open(): Pair<TerminationFixture, PriorIntentLifetime> {
        val f = TerminationFixture(folder.root, opened.size).also { opened += it }
        f.edit { p -> p.clear(); p += ControlLifecycleEvidenceFixtures.raw(); p[MAY_CONTAIN_PREMIUM] = true; p[MAY_CONTAIN_KRX] = true }
        return f to PriorIntentLifetime.prepare(f.store, f.store.newSessionId(), "A", PurgeScope.CAPABILITY, "k")
    }
    private suspend fun confirmed(): Pair<TerminationFixture, PriorIntentLifetime> = open().also { (_, lt) ->
        assertTrue("fixture: confirmed", controlTestTimeout("confirm") { lt.confirmPrior(current) } is PriorIntentGate.ReadyForTest)
    }
    private suspend fun settle(lt: PriorIntentLifetime) = controlTestTimeout("settle") {
        val closure = HoldRecoveryClosure.SameProcess(lt.source, binding.executor, 5, true, setOf("w"), setOf("w"))
        lt.settle(RecoverIntentInput(lt.source, current, binding, closure), LifecycleOrderSource(life, 21),
            AttemptContext("A", 3, life, false, false, intentRecovery = HoldRecoveryRuntime(binding, 5, true, setOf("w"), closure)))
    }
    private fun retained(id: String, s: PriorIntentSettlement, reason: PriorIntentRetainedReason) =
        assertTrue("$id: Retained($reason), got $s", s is PriorIntentSettlement.Retained && s.reason == reason)

    @Test fun W01_noWorkBeforeTheIntentIsConfirmed() = runReleaseTest {
        val (_, lt) = open()
        assertNull("W01", lt.begin(current))
        assertTrue("W01 gate", lt.gate(current) == PriorIntentGate.Closed(PriorIntentClosedReason.AwaitingConfirmation))
    }

    @Test fun W02_workBeginsOnlyForTheConfirmedTarget_andAnotherTargetClosesTheGate() = runReleaseTest {
        val (_, lt) = confirmed()
        assertNotNull("W02", lt.begin(current))
        assertTrue("W02: gate still open", lt.gate(current) is PriorIntentGate.ReadyForTest)
        assertNull("W02 other target", lt.begin(FenceV1("A", "u", "k2")))
        assertEquals("W02: closed for good", PriorIntentGate.Closed(PriorIntentClosedReason.TargetChanged), lt.gate(current))
        assertNull("W02 after", lt.begin(current))
    }

    @Test fun W03_noWorkAfterTheGateCloses() = runReleaseTest {
        val (_, lt) = confirmed()
        lt.closeLocalGate()
        assertNull("W03", lt.begin(current))
    }

    @Test fun W04_closeAndDrainWaitsForBegunWork_andNoWorkBeginsMeanwhile() = runReleaseTest {
        val (_, lt) = confirmed()
        val w = lt.begin(current)
        assertNotNull("W04 begin", w)
        val drain = async(start = CoroutineStart.UNDISPATCHED) { lt.closeAndDrain() }
        yield()
        assertFalse("W04: still waiting", drain.isCompleted)
        assertEquals("W04: closed first", PriorIntentGate.Closed(PriorIntentClosedReason.Closing), lt.gate(current))
        assertNull("W04: no new work", lt.begin(current))
        w!!.finish()
        controlTestTimeout("W04 drain") { drain.await() }
    }

    @Test fun W05_aRepeatedFinish_releasesOnlyItsOwnWork() = runReleaseTest {
        val (_, lt) = confirmed()
        val a = lt.begin(current)!!; val b = lt.begin(current)!!
        a.finish(); a.finish()
        val drain = async(start = CoroutineStart.UNDISPATCHED) { lt.closeAndDrain() }
        yield()
        assertFalse("W05: b still runs", drain.isCompleted)
        b.finish()
        controlTestTimeout("W05 drain") { drain.await() }
    }

    @Test fun W06_theIntentIsNotHandedOverWhileWorkRuns() = runReleaseTest {
        val (f, lt) = confirmed()
        val w = lt.begin(current)!!
        lt.closeLocalGate()
        val before = f.disk()
        retained("W06", settle(lt), PriorIntentRetainedReason.WorkNotJoined)
        assertEquals("W06: record untouched", before, f.disk())
        w.finish()
        controlTestTimeout("W06 drain") { lt.closeAndDrain() }
        assertTrue("W06: settled after the join", settle(lt) is PriorIntentSettlement.Settled)
    }

    @Test fun W07_aCancelledDrain_joinsNothing() = runReleaseTest {
        val (_, lt) = confirmed()
        val w = lt.begin(current)!!
        val drain = async(start = CoroutineStart.UNDISPATCHED) { lt.closeAndDrain() }
        yield()
        drain.cancel()
        try { drain.await(); throw AssertionError("W07: must be cancelled") } catch (_: CancellationException) {}
        retained("W07", settle(lt), PriorIntentRetainedReason.WorkNotJoined)
        w.finish()
    }

    @Test fun W08_withNoWork_closeAndDrainReturnsAtOnce_andTheGateIsClosing() = runReleaseTest {
        val (_, lt) = confirmed()
        controlTestTimeout("W08") { lt.closeAndDrain() }
        assertEquals("W08", PriorIntentGate.Closed(PriorIntentClosedReason.Closing), lt.gate(current))
    }
}
