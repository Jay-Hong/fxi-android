package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.KrxBetaSessionRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxBetaSessionRig.Companion.USD
import com.jay.fxi.data.remote.KrxBetaSessionRig.Companion.tether
import com.jay.fxi.data.remote.KrxBetaSessionRig.Companion.usd
import com.jay.fxi.domain.model.TopicQuoteKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** β §3: capability-only changes preserve the GENERAL session; OFF TSCT remains untouched. */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSessionKrxOnPreservationTest {
    private fun sameGeneral(row: String, before: TopicAccessSnapshot, after: TopicAccessSnapshot) {
        assertNotEquals("contract($row): K1 to K2 record rotation", before.facts.recordFence?.krxCapabilityEpoch,
            after.facts.recordFence?.krxCapabilityEpoch)
        assertEquals("contract($row): token", before.facts.token, after.facts.token)
        assertTrue("contract($row): standing and user allowed", after.facts.tokenStanding && after.facts.userAllowed)
        assertEquals("contract($row): U", before.userInvalidations, after.userInvalidations)
        assertEquals("contract($row): USER epoch", before.facts.recordFence?.userAccessEpoch, after.facts.recordFence?.userAccessEpoch)
        assertEquals("contract($row): USER end", before.lastUserEnd, after.lastUserEnd)
        assertEquals("contract($row): USER floor", before.userEndInvalidationsFloor, after.userEndInvalidationsFloor)
        assertEquals("contract($row): first capability withdrawal C+1", before.capabilityInvalidations + 1, after.capabilityInvalidations)
    }

    @Test fun ON_T_realSessionRigOpensAndCountsCapabilityWithdrawal() = krxBetaSessionTest { r ->
        val fence = r.approve()
        r.goLive()
        val before = r.snapshot
        val use = checkNotNull(r.uses.acquire(fence)) { "contract(ON-T): initial GENERAL use" }
        assertTrue("contract(ON-T): initial C recorded", before.capabilityInvalidations >= 0)
        r.rotate()
        sameGeneral("ON-T", before, r.snapshot)
        assertTrue("contract(ON-T): old GENERAL use remains", r.uses.admits(use))
        assertFalse("contract(ON-T): hidden capability", r.snapshot.facts.capabilityAllowed)
        assertTrue("contract(ON-T): permit retained", r.snapshot.krxPermitSeq >= 1)
        assertNotEquals("contract(ON-T): hidden is not OPEN", KrxContestPhase.OPEN, r.pac.krxContestView?.state?.phase)
    }

    @Test fun S01_capabilityRotationKeepsWireSessionStoreAndBootstrapPlan() = krxBetaSessionTest { r ->
        val fence = r.approve()
        r.goLive()
        val wire = r.wires.single()
        wire.deliver(tether(1390.0)); r.step(1)
        val key = TopicQuoteKey("upbit", "usdt-krw")
        val quote = checkNotNull(r.session.rates.value.quotes[key]) { "premise: tether frame adopted" }
        val owner = r.session.display.value.owner
        val observation = r.observations().last { it.topic == TETHER }
        val store = r.store.snapshot
        val lastKnown = r.live.last()
        val before = r.snapshot
        val deliveryMark = r.deliveries.size
        val revisions = r.revisions
        val connections = r.connectGenerations.toList()
        val handshakes = r.requests.toList()
        assertEquals("premise: original USD first batch, tether still paced", listOf(USD), r.bootstraps.map { it.topic })
        r.rotate()
        sameGeneral("S01", before, r.snapshot)
        assertTrue("contract(S01): revision reached session", r.revisions > revisions)
        assertEquals("contract(S01): same issued F1", fence, r.issuer.topicGrantResult().fence)
        assertTrue("contract(S01): no access end", r.deliveries.drop(deliveryMark).none { !it.allowed })
        assertEquals("contract(S01): one wire", 1, r.wires.size)
        assertSame("contract(S01): original wire", wire, r.wires.single())
        assertFalse("contract(S01): no cancel", wire.cancelled)
        assertEquals("contract(S01): no connect", connections, r.connectGenerations)
        assertEquals("contract(S01): no handshake", handshakes, r.requests)
        assertEquals("contract(S01): quote kept", quote, r.session.rates.value.quotes[key])
        assertEquals("contract(S01): display owner kept", owner, r.session.display.value.owner)
        assertEquals("contract(S01): canonical store and receive generations kept", store, r.store.snapshot)
        assertEquals("contract(S01): last-known offer kept", lastKnown, r.live.last())
        assertEquals("contract(S01): no bootstrap caused by rotation", listOf(USD), r.bootstraps.map { it.topic })
        wire.deliver(usd(1517.0)); r.step(1)
        assertEquals("contract(S01): same wire GENERAL frame adopted", 1517.0,
            r.session.rates.value.quotes[TopicQuoteKey("kb", "usd-krw")]?.rate)
        val next = r.observations().last { it.topic == USD }
        assertSame("contract(S01): session key", observation.attribution.sessionKey, next.attribution.sessionKey)
        assertEquals("contract(S01): grant generation", observation.attribution.grantEpoch, next.attribution.grantEpoch)
        assertEquals("contract(S01): connection generation", observation.connectionGeneration, next.connectionGeneration)
        assertEquals("contract(S01): attribution F1", fence, next.attribution.owner)
        assertEquals("contract(S01): GENERAL lifetime", observation.attribution.lifetime, next.attribution.lifetime)
        assertEquals("contract(S01): tether receive generation kept", store.stateFor(TETHER).receiveGeneration,
            r.store.snapshot.stateFor(TETHER).receiveGeneration)
        r.step(500)
        assertEquals("contract(S01): original plan issues each topic once", listOf(USD, TETHER), r.bootstraps.map { it.topic })
        assertEquals("contract(S01): original issue gap", 500L, r.bootstraps[1].at - r.bootstraps[0].at)
        // Count actual sink calls after the mark, never a retained last-origin value. Kept last so a red origin
        // contract does not hide the independent wire, GENERAL and pacing observations above.
        val rotationDeliveries = r.deliveries.drop(deliveryMark)
        assertTrue("contract(S01): rotation delivered to session", rotationDeliveries.isNotEmpty())
        rotationDeliveries.forEach { delivery ->
            assertTrue("contract(S01): delivered access allowed", delivery.allowed)
            assertEquals("contract(S01): delivered fence remains F1", fence, delivery.fence)
        }
        println("S01 same-fence redeliveries=${rotationDeliveries.size}")
    }

    @Test fun S02_inFlightGeneralBootstrapSurvivesRotationAndKeepsIssueGap() = krxBetaSessionTest { r ->
        val fence = r.approve()
        r.goLive()
        val old = r.bootstraps.single()
        val before = r.snapshot
        assertEquals("premise: old bootstrap is non-KRX USD", USD, old.topic)
        assertTrue("premise: old exchange admitted before rotation", old.admitted())
        assertFalse("premise: old 200 is held", old.answer.isCompleted || old.returned)
        r.rotate()
        sameGeneral("S02", before, r.snapshot)
        assertFalse("premise: old bootstrap still held after rotation", old.answer.isCompleted || old.returned)
        assertTrue("contract(S02): old exchange admission survives", old.admitted())
        assertEquals("contract(S02): no repeated first-batch exemption", listOf(USD), r.bootstraps.map { it.topic })
        old.answer.complete(r.delivered(usd(1631.0))); r.step()
        assertTrue("premise: held unique 200 reached session", old.returned)
        assertEquals("contract(S02): old GENERAL 200 adopted", 1631.0,
            r.session.rates.value.quotes[TopicQuoteKey("kb", "usd-krw")]?.rate)
        val accepted = r.observations().last { it.path == TopicGraphPath.REST_BOOTSTRAP }
        assertEquals("contract(S02): old result still belongs to F1", fence, accepted.attribution.owner)
        r.step(old.at + 499 - r.clock.nowMillis())
        assertEquals("contract(S02): still inside original issue-gap window", old.at + 499, r.clock.nowMillis())
        assertEquals("contract(S02): no early extra bootstrap", listOf(USD), r.bootstraps.map { it.topic })
        r.step(1)
        assertEquals("contract(S02): one original paced issue", listOf(USD, TETHER), r.bootstraps.map { it.topic })
        assertEquals("contract(S02): unchanged 500ms gap", 500L, r.bootstraps[1].at - old.at)
    }
}
