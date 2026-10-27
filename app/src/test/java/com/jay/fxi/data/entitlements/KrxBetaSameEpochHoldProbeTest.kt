package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 03-R exploratory test. stdout contains actual publications; REACHED requires every assertion below. */
@OptIn(ExperimentalCoroutinesApi::class)
class KrxBetaSameEpochHoldProbeTest {
    @Test fun probe_H1prime_sameEpochHoldThenFreshTrue() = runTest {
        val job = SupervisorJob()
        var id = 0
        val store = KrxHarnessStore(EpochIdGenerator { "probe-${id++}" })
        val source = KrxHarnessSource({ testScheduler.currentTime }) { EntitlementsOutcome.StableActive(true) }
        val purger = KrxHarnessPurger()
        val pac = krxBetaPac(source, store, purger, purger,
            CoroutineScope(job + StandardTestDispatcher(testScheduler)), RecheckClock { testScheduler.currentTime },
            { source.liveFence }, AccessOrderSequence())
        val c = pac.coordinator
        // Composed observer: never replace the installed checker.
        pac.check.observe { s ->
            println("03-R t=${testScheduler.currentTime} rev=${s.revision} token=${s.facts.token} U=${s.userInvalidations} " +
                "record=${s.facts.recordFence} C=${s.capabilityInvalidations} p=${s.krxPermitSeq} " +
                "standing=${s.facts.tokenStanding} user=${s.facts.userAllowed} cap=${s.facts.capabilityAllowed} " +
                "blocks=${s.facts.capabilityBlocks} phase=${c.krxContestView?.state?.phase}")
        }
        try {
            c.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
            c.refresh(RefreshIntent.FORCE_PREMIUM)
            runCurrent()
            val token = checkNotNull(c.topicGrantResult().fence).grant
            val start = c.accessSnapshot
            assertEquals("contract(03-R): initial OPEN", KrxContestPhase.OPEN, c.krxContestView?.state?.phase)
            assertTrue("contract(03-R): ON allowed/p", start.facts.capabilityAllowed && start.krxPermitSeq >= 1)
            val epoch = store.record.krxCapabilityEpoch
            source.identityNulls = 1
            val r = c.reserveRejection(token, KrxHarnessDefaults.KRX_REFUSAL)
            c.onTopicRejected(r)
            val held = c.accessSnapshot
            assertEquals("premise: 03-R H1prime fault hit", 0, source.identityNulls)
            assertEquals("contract(03-R): hold record unchanged", epoch, store.record.krxCapabilityEpoch)
            assertTrue("contract(03-R): CAPABILITY hold", !held.facts.capabilityAllowed)
            assertEquals("contract(03-R): C increments", start.capabilityInvalidations + 1, held.capabilityInvalidations)
            advanceTimeBy(5_010); runCurrent()
            c.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            runCurrent()
            val end = c.accessSnapshot
            for (s in listOf(held, end)) {
                assertEquals("contract(03-R): same token", token, s.facts.token)
                assertTrue("contract(03-R): standing GENERAL", s.facts.tokenStanding && s.facts.userAllowed)
                assertEquals("contract(03-R): same U", start.userInvalidations, s.userInvalidations)
                assertEquals("contract(03-R): same USER epoch", start.facts.recordFence?.userAccessEpoch, s.facts.recordFence?.userAccessEpoch)
                assertEquals("contract(03-R): same KRX epoch", epoch, s.facts.recordFence?.krxCapabilityEpoch)
            }
            assertTrue("contract(03-R): actual release allowed", end.facts.capabilityAllowed)
            assertEquals("contract(03-R): released OPEN", KrxContestPhase.OPEN, c.krxContestView?.state?.phase)
            assertEquals("contract(03-R): release C unchanged", held.capabilityInvalidations, end.capabilityInvalidations)
            assertTrue("contract(03-R): fresh permit", end.krxPermitSeq > start.krxPermitSeq)
            println("03-R REACHED")
        } finally {
            try { pac.check.assertHeld() } finally { job.cancel() }
        }
    }
}
