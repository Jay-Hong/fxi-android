package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.KrxBetaGraphData.KEY
import com.jay.fxi.data.graph.KrxBetaGraphData.ONLINE
import com.jay.fxi.data.graph.KrxBetaGraphData.KRX_SERIES
import com.jay.fxi.data.graph.KrxBetaGraphData.ok
import com.jay.fxi.data.graph.KrxBetaGraphData.onlineDto
import com.jay.fxi.data.graph.KrxBetaGraphData.serverTab
import com.jay.fxi.data.graph.KrxBetaGraphData.put
import com.jay.fxi.data.graph.KrxBetaGraphData.joined
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 03-R is mandatory after the REACHED β-1a probe. Consumer inputs are real PAC snapshots only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2KrxRealPacMemoryContractTest {
    @get:Rule val folder = TemporaryFolder(b1dEvidenceRoot())
    private val x = KrxBetaGraphRigExtras(folder)
    private fun c(message: String) = x.c(message)

    /** Expected S-1 states are derived from the initial state and the specified events, never fed to the consumer. */
    private suspend fun TestScope.roundTrip(p: KrxBetaGraphPac, g: KrxBetaGraphRig) {
        val start = p.snapshot
        val originalState = g.state
        val accessReads = g.accessReads
        val expected = { held: Boolean -> a1Snap(
            token = checkNotNull(start.facts.token).value,
            binding = checkNotNull(start.facts.binding), record = checkNotNull(start.facts.recordFence),
            issuedRecord = checkNotNull(start.facts.recordFence), projectKrx = true,
            userInvalidations = start.userInvalidations,
            capabilityInvalidations = start.capabilityInvalidations + 1,
            krxPermitSeq = start.krxPermitSeq + if (held) 0 else 1,
            capabilityBlocks = if (held) setOf(TopicAccessBlock.KRX_CONTESTED) else emptySet()) }
        p.hold()
        compare("hold", expected(true), p.snapshot)
        advanceTimeBy(5_010); runCurrent()
        p.reopen()
        compare("release", expected(false), p.snapshot)
        assertEquals(c("no coordinator snapshot read during real round trip"), accessReads, g.accessReads)
        assertEquals(c("coordinator state unchanged during unnotified round trip"), originalState, g.state)
        p.publications.forEach { s ->
            println("B1D_PUBLICATION row=${x.row} rev=${s.revision} token=${s.facts.token} U=${s.userInvalidations} " +
                "C=${s.capabilityInvalidations} p=${s.krxPermitSeq} record=${s.facts.recordFence} standing=${s.facts.tokenStanding} " +
                "userBlocks=${s.facts.userBlocks} capabilityBlocks=${s.facts.capabilityBlocks}")
        }
    }
    private fun compare(step: String, expected: TopicAccessSnapshot, actual: TopicAccessSnapshot) {
        assertEquals(c("$step S-1 standing"), expected.facts.tokenStanding, actual.facts.tokenStanding)
        assertEquals(c("$step S-1 token"), expected.facts.token, actual.facts.token)
        assertEquals(c("$step S-1 USER blocks"), expected.facts.userBlocks, actual.facts.userBlocks)
        assertEquals(c("$step S-1 CAPABILITY blocks"), expected.facts.capabilityBlocks, actual.facts.capabilityBlocks)
        assertEquals(c("$step S-1 epoch"), expected.facts.recordFence, actual.facts.recordFence)
        assertEquals(c("$step S-1 U"), expected.userInvalidations, actual.userInvalidations)
        assertEquals(c("$step S-1 C"), expected.capabilityInvalidations, actual.capabilityInvalidations)
        assertEquals(c("$step S-1 p"), expected.krxPermitSeq, actual.krxPermitSeq)
    }

    @Test fun M01_unnotifiedSameEpochRoundTripHidesOldMemoryKrxButKeepsGeneral() = x.trial("M01") {
        val p = x.pac(this); val g = x.graph(this, p)
        g.adopt(this, onlineDto(1500.0, inProgress = true))
        val before = checkNotNull(g.exposed())
        assertTrue("premise: M01 adopted KRX present", KRX_SERIES in g.ids())
        roundTrip(p, g)
        assertEquals(c("memory still present inside protected read window"), 1500.0, g.rate())
        assertEquals(c("old memory KRX hidden"), listOf(ONLINE), g.exposedIds())
        assertFalse(c("old memory KRX bucket hidden"), KRX_SERIES in g.exposed()!!.tab.inProgress)
        assertEquals(c("GENERAL preserved"), before.tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }, g.exposed()!!.tab.graph.series)
    }

    @Test fun M02_oldRequestUnique200AdoptsGeneralWithoutOldKrxExposureAfterRoundTrip() = x.trial("M02") {
        val p = x.pac(this); val g = x.graph(this, p)
        g.start(); g.coordinator.onActivated(KEY); runCurrent(); val old = g.sent.single()
        roundTrip(p, g)
        assertTrue("premise: M02 old request held and no newer answer", !old.tab.isCompleted && g.state.entries[KEY] == null)
        old.tab.complete(ok(onlineDto(1617.0, inProgress = true))); runCurrent()
        assertEquals(c("unique old GENERAL 200 adopted"), 1617.0, g.rate())
        assertNotNull(c("raw entry exists; protected exposure checked separately"), g.state.entries[KEY])
        assertEquals(c("original request C prevents KRX exposure"), listOf(ONLINE), g.exposedIds())
        assertFalse(c("original request C prevents KRX bucket exposure"), KRX_SERIES in g.exposed()!!.tab.inProgress)
    }

    @Test fun M03_newCaptureAfterRealSameEpochReleasePublishesNewKrxAndGeneral() = x.trial("M03") {
        val p = x.pac(this); val g = x.graph(this, p)
        g.adopt(this, onlineDto(1500.0)); roundTrip(p, g)
        assertEquals(c("old memory hidden before new capture"), listOf(ONLINE), g.exposedIds())
        b1dFresh(g, 1739.0)
        assertTrue(c("fresh permit remains OPEN"), p.snapshot.facts.capabilityAllowed && p.snapshot.krxPermitSeq >= 2)
    }

    @Test fun M04_rotatedRealSeedCannotReadOrJoinK1AndRetirementExpiresOwnership() = x.trial("M04") {
        for (retire in listOf(false, true)) {
            val p = x.pac(this); val g = x.graph(this, p)
            val k1 = checkNotNull(p.store.record.krxCapabilityEpoch)
            val disk = g.componentsFor(serverTab(1313.0), k1, "M04-disk-response")
            g.put(disk); g.store.hold[1] = CompletableDeferred()
            g.start(); g.coordinator.onActivated(KEY); runCurrent()
            assertTrue("premise: M04 GENERAL disk read held with empty slot", g.store.generalReads.single() is GraphV2DiskRead.Found && g.state.entries[KEY] == null)
            assertEquals("premise: M04 held read has the disk response ID", "M04-disk-response",
                (g.store.generalReads.single() as GraphV2DiskRead.Found).envelope.responseId)
            val k2 = p.rotate(); p.reopen()
            assertTrue(c("real epoch changed"), k1 != k2)
            val reads = g.store.generalReads.size
            if (retire) {
                assertEquals(c("selected K1 seed retired"), GraphRuntimeRetirement.REMOVED, g.retire { it.krxCapabilityEpoch == k1 }.second)
                assertEquals(c("port never starts seed"), reads, g.store.generalReads.size)
            }
            g.store.release(1); runCurrent()
            assertTrue(c("old K1 KRX never read"), g.store.krxReads.isEmpty())
            if (retire) {
                assertNull(c("selected seed owns neither half after retirement"), g.state.entries[KEY])
                assertEquals(c("selected seed completion expires"), listOf("Seed ownership expired"), g.seedReasons())
                g.coordinator.onRefreshRequested(); runCurrent()
                assertEquals(c("normal start retries GENERAL seed"), reads + 1, g.store.generalReads.size)
            }
            assertEquals(c("only matching disk GENERAL restored"), GraphEntry(joined(disk, false), null), g.entry())
            assertEquals(c("disk GENERAL visible without K1"), joined(disk, false).graph.series.map { it.seriesId }, g.exposedIds())
            assertFalse(c("K1 remains absent at exposure"), KRX_SERIES in g.exposedIds())
            val old = g.sent.single()
            assertFalse("premise: M04 separate HTTP response held", old.tab.isCompleted)
            old.tab.complete(ok(onlineDto(1519.0))); runCurrent()
            b1dFresh(g, 1741.0)
        }
    }
}
