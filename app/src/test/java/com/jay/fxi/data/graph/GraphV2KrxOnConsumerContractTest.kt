package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.KrxBetaGraphData.KEY
import com.jay.fxi.data.graph.KrxBetaGraphData.KRX_SERIES
import com.jay.fxi.data.graph.KrxBetaGraphData.ONLINE
import com.jay.fxi.data.graph.KrxBetaGraphData.NOON
import com.jay.fxi.data.graph.KrxBetaGraphData.ok
import com.jay.fxi.data.graph.KrxBetaGraphData.onlineDto
import com.jay.fxi.data.remote.*
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.premium.graph.KrxBetaConsumerHolder
import com.jay.fxi.ui.premium.graph.GraphV2Content
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** β r2 §6. G01–G05/G12 use S-1; G06–G11 use checked real PAC publications.
 * Gate :315–327 remains OFF; α 02-3 owns its ON pair, without a duplicate β row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2KrxOnConsumerContractTest {
    @get:Rule val folder = TemporaryFolder(b1dEvidenceRoot())
    private val x = KrxBetaGraphRigExtras(folder)
    private fun c(message: String) = x.c(message)
    private fun hand(k: String = "K1", cap: Long = 0, allowed: Boolean = true, p: Long = 1,
                     user: Boolean = true) = a1Snap(
        record = KrxHandSnapshots.record(k), issuedRecord = KrxHandSnapshots.RECORD_K1,
        capabilityBlocks = if (allowed) emptySet() else setOf(TopicAccessBlock.KRX_CONTESTED),
        userBlocks = if (user) emptySet() else setOf(TopicAccessBlock.PERSISTENCE_HOLD),
        projectKrx = true, userInvalidations = 3, capabilityInvalidations = cap, krxPermitSeq = p)

    @Test fun G01_memoryKeepsOriginalEpochAndCWithIndependentControls() = x.trial("G01") {
        for ((k, cap) in listOf("K2" to 0L, "K1" to 1L, "K2" to 1L)) {
            val g = x.graph(this)
            g.adopt(this, onlineDto(1500.0, inProgress = true))
            val general = g.exposed()!!.tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }
            assertTrue("premise: G01 K1 memory present", KRX_SERIES in g.ids())
            g.handSnapshot = hand(k, cap, p = 2)
            assertEquals(c("same token/U in epoch=$k C=$cap"), KrxHandSnapshots.LIFETIME7, g.uses.acquire(KrxHandSnapshots.FENCE7))
            assertEquals(c("old memory KRX hidden independently epoch=$k C=$cap"), listOf(ONLINE), g.exposedIds())
            assertEquals(c("GENERAL unchanged"), general, g.exposed()!!.tab.graph.series)
            assertFalse(c("old KRX current bucket hidden"), KRX_SERIES in g.exposed()!!.tab.inProgress)
            g.coordinator.onContextChanged(); runCurrent()
            b1dFresh(g)
        }
    }

    @Test fun G02_sameEpochRoundTripKeepsGeneralAndCompletionStampButNotOldKrxExposure() = x.trial("G02") {
        val g = x.graph(this)
        g.adopt(this, onlineDto(1500.0))
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val old = g.sent.last()
        g.flip(this, hand(cap = 1, allowed = false))
        assertNotNull(c("GENERAL remains exposed during hold"), g.exposed())
        assertEquals(c("hold hides only KRX"), listOf(ONLINE), g.exposedIds())
        g.flip(this, hand(cap = 1, p = 2))
        assertFalse("premise: G02 old response still in window and no newer answer", old.tab.isCompleted)
        old.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(c("old GENERAL adopted"), 1600.0, g.rate())
        assertEquals(c("same completion configuration confirms stamp"), NOON, g.entry().online200At)
        assertEquals(c("old capture KRX protected read zero"), listOf(ONLINE), g.exposedIds())
        // Raw adoption is deliberately distinct from protected exposure; do not require a raw purge.
        b1dFresh(g)
    }

    @Test fun G03_holdKeepsRecordLiveWithOneReadRecordOwnerAndNoSessionBranches() = x.trial("G03") {
        val g = x.graph(this); g.adopt(this, onlineDto(1500.0))
        val before = g.state
        val reads = g.accessReads
        g.atAccessRead(reads + 2) { error("contract(G03): retirement read snapshot twice") }
        assertEquals(c("record live selection refused"), setOf(g.scope("K1")) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { true })
        assertEquals(c("one snapshot read"), reads + 1, g.accessReads); g.clearAccessHooks()
        g.handSnapshot = hand().let { it.copy(facts = it.facts.copy(binding = EntitlementsIdentity("u2", 2))) }
        g.fence = TopicSessionFence(AuthIdentityFence("u2", 2), "U2", TopicGrantToken(9))
        assertEquals(c("live uses record owner"), setOf(GraphCapabilityScope("u1", "K1")) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED,
            g.retire { it == GraphCapabilityScope("u1", "K1") })
        g.fence = null
        assertEquals(c("live without session"), setOf(GraphCapabilityScope("u1", "K1")) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { true })
        g.fence = KrxHandSnapshots.FENCE7; g.handSnapshot = hand()
        assertEquals(c("live refusals preserve state"), before, g.state)
        g.coordinator.onRefreshRequested(force = true); runCurrent(); val old = g.sent.last()
        g.flip(this, hand(cap = 1, allowed = false)); val held = g.state
        assertEquals(c("hold is not retirement"), setOf(g.scope("K1")) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { true })
        assertEquals(c("held state unchanged"), held, g.state)
        g.flip(this, hand(cap = 1, p = 2))
        assertFalse("premise: G03 old request outstanding", old.tab.isCompleted)
        old.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(c("old GENERAL completes"), 1600.0, g.rate())
        assertEquals(c("old capture KRX cannot reopen"), listOf(ONLINE), g.exposedIds())
        b1dFresh(g)
    }

    @Test fun G04_closedBindingStillRetiresK1AndPreservesGeneralWithoutStrip() = x.trial("G04") {
        val g = x.graph(this); g.adopt(this, onlineDto(1500.0)); val before = g.entry()
        assertTrue(c("K1 target present before closed binding retirement"), KRX_SERIES in g.ids())
        g.protectedOpen = false; g.handSnapshot = hand("K2", 1, false)
        g.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: G04 strip did not run in closed binding", before, g.entry())
        assertNull(c("binding actually closed"), g.exposed())
        assertEquals(c("closed binding retires K1"), GraphRuntimeRetirement.REMOVED, g.retire { it.krxCapabilityEpoch == "K1" }.second)
        assertEquals(c("GENERAL and stamp preserved"), before.copy(tab = before.tab.copy(graph = before.tab.graph.copy(
            series = before.tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }))), g.entry())
        g.protectedOpen = true
        assertEquals(c("same U GENERAL remains readable"), listOf(ONLINE), g.exposedIds())
    }

    @Test fun G05_derivedSealWithNoLiveEpochKeepsGeneralUnlessUserAlsoBlocked() = x.trial("G05") {
        for (userOpen in listOf(true, false)) {
            val g = x.graph(this); g.adopt(this, onlineDto(1500.0)); val stamp = g.entry().online200At
            assertTrue(c("K1 target present before derived seal retirement"), KRX_SERIES in g.ids())
            g.handSnapshot = a1Snap(record = KrxHandSnapshots.record(null), issuedRecord = KrxHandSnapshots.RECORD_K1,
                capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
                userBlocks = if (userOpen) emptySet() else setOf(TopicAccessBlock.PERSISTENCE_HOLD),
                userInvalidations = 3, capabilityInvalidations = 1, krxPermitSeq = 1, projectKrx = true)
            assertEquals(c("no live epoch; K1 removable"), setOf(g.scope("K1")) to GraphRuntimeRetirement.REMOVED, g.retire { true })
            assertEquals(c("GENERAL raw preserved"), listOf(ONLINE), g.ids())
            assertEquals(c("stamp preserved"), stamp, g.entry().online200At)
            if (userOpen) {
                assertNotNull(c("KRX-only seal keeps GENERAL protected admission"), g.exposed())
                assertEquals(c("KRX-only seal preserves GENERAL"), listOf(ONLINE), g.exposedIds())
            }
            else assertNull(c("independent USER block enforced"), g.exposed())
        }
    }

    @Test fun G06_retiredRealRequestAdoptsReservesPreparesAndWritesOnlyGeneral() = x.trial("G06") {
        for (open in listOf(true, false)) {
            val p = x.pac(this)
            val calls = mutableListOf<Pair<GraphV2AccessCapture, Boolean>>()
            val issuer = IssuerGraphWritePreparation(p.c::markGraphData)
            val g = x.graph(this, p, GraphV2WritePorts(GraphV2PrepareWrite { capture, wants ->
                calls += capture to wants; issuer.prepare(capture, wants)
            }, { error(c("preparation blocked $it")) }, { error(c("write diagnostic $it")) }))
            g.start(); g.coordinator.onActivated(KEY); runCurrent(); val old = g.sent.single()
            val before = p.snapshot; val k1 = p.store.record.krxCapabilityEpoch
            p.rotate(); if (open) p.reopen()
            assertEquals(c("retire request K1"), GraphRuntimeRetirement.REMOVED, g.retire { it.krxCapabilityEpoch == k1 }.second)
            p.sameGeneral(before)
            assertFalse("premise: G06 K1 response held and no newer result", old.tab.isCompleted)
            old.tab.complete(ok(onlineDto(1500.0))); runCurrent()
            assertNotNull(c("valid GENERAL completion is adopted"), g.state.entries[KEY])
            assertEquals(c("GENERAL alone adopted"), listOf(ONLINE), g.ids())
            assertEquals(c("completion stamp open=$open"), if (open) null else NOON, g.entry().online200At)
            assertEquals(c("GENERAL reservation"), 1, g.store.reservations.size)
            assertEquals(c("one preparation"), 1, calls.size)
            assertEquals(c("original GENERAL fence"), p.fence, calls.single().first.fence)
            assertNull(c("retired capture owns no KRX"), calls.single().first.krxCapabilityEpoch)
            assertFalse(c("wantsKrx false"), calls.single().second)
            assertEquals(c("GENERAL written"), GraphV2ComponentWriteOutcome.Replaced, g.store.writes.single().general)
            assertNull(c("no KRX write candidate"), g.store.writes.single().krx)
            val path = KrxBetaGraphData.generalPath(g.root, g.generalKey())
            assertTrue(c("GENERAL file exists"), path.isFile)
            assertTrue(c("K1 file zero"), g.root.walkTopDown().filter { it.isFile && KrxBetaGraphData.isKrx(g.root, it) }.none())
        }
    }

    @Test fun G07_realUserHoldRejectsOldResponseBeforeAdoptionReservationOrPreparation() = x.trial("G07") {
        val p = x.pac(this); var preparations = 0
        val g = x.graph(this, p, GraphV2WritePorts(GraphV2PrepareWrite { capture, wants ->
            preparations++; IssuerGraphWritePreparation(p.c::markGraphData).prepare(capture, wants)
        }, {}, {}))
        g.start(); g.coordinator.onActivated(KEY); runCurrent(); val old = g.sent.single()
        val use = checkNotNull(g.uses.acquire(p.fence))
        val k1 = checkNotNull(p.store.record.krxCapabilityEpoch)
        p.rotate()
        assertEquals(c("old request K1 retired before USER hold"), GraphRuntimeRetirement.REMOVED,
            g.retire { it.krxCapabilityEpoch == k1 }.second)
        p.userHold()
        assertFalse(c("old lifetime actually denied"), g.uses.admits(use))
        assertFalse("premise: G07 old response is still outstanding", old.tab.isCompleted)
        old.tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertNull(c("USER-held completion adopts nothing"), g.state.entries[KEY])
        assertTrue(c("USER-held completion reserves nothing"), g.store.reservations.isEmpty())
        assertEquals(c("USER-held completion prepares nothing"), 0, preparations)
    }

    @Test fun G08_freshRealUserLifetimeReadsNeitherOldCatalogNorFailureBeforeSync() = x.trial("G08") {
        val p = x.pac(this); val h = KrxBetaConsumerHolder(this, folder, p)
        try { h.freshUserWindow(p) } finally { h.close() }
    }

    @Test fun G09_realRotationKeepsOwnerGeneralCatalogPeriodFullscreenAndRawSelection() = x.trial("G09") {
        val p = x.pac(this); val h = KrxBetaConsumerHolder(this, folder, p)
        try { h.rotation(p) } finally { h.close() }
    }

    @Test fun G10_realUserHoldBlocksHolderInsideRetirementPublication() = x.trial("G10") {
        val p = x.pac(this); val h = KrxBetaConsumerHolder(this, folder, p)
        try { h.blocked(p) } finally { h.close() }
    }

    @Test fun G11_realUserHoldRefusesOldBatchPriceAndKeepsHandoverLoss() = x.trial("G11") {
        val p = x.pac(this); val fence = p.fence
        val use = checkNotNull(p.snapshot.acquireUse(fence))
        val at = NOON - 1.minutes
        val catalog = GraphV2Domain.catalog(com.jay.fxi.ui.premium.graph.TetherGraphData.catalog())
        val input = { value: Double -> TopicGraphInput.Observations(1, "fx:usd-krw", TopicGraphPath.WS,
            TopicUseAttribution(Any(), fence, 1, use), 1,
            listOf(TopicGraphCandidate.Quote("investing", "usd-krw", value, at, null),
                TopicGraphCandidate.Quote("investing", "usd-krw", value + 1, at - 10.minutes, null))) }
        val r = GraphRecorderReducer
        val ready = r.syncAccess(r.empty(), p.snapshot, fence)
        val first = r.observe(ready, input(1400.0), catalog, p.snapshot, fence, true, NOON)
        assertEquals("premise: G11 existing recorder series", 1, first.series.size)
        p.userHold()
        val denied = p.snapshot.admitsUse(use)
        assertFalse(c("real old lifetime denied"), denied)
        val result = r.observe(first, input(1999.0), catalog, p.snapshot, fence, denied, NOON)
        val state = result.series.values.single()
        val expected = requireGraphRecovery(first.series.values.single(), at - 10.minutes, at, GraphRecoveryReason.HANDOVER_LOSS, NOON)
        assertEquals(c("HANDOVER_LOSS covers complete refused span"), expected.pending, state.pending)
        assertEquals(c("refused span gaps retained"), expected.gapHistory, state.gapHistory)
        assertFalse(c("rejected price zero"), state.data.app.observations.any { it.rate == 1999.0 || it.rate == 2000.0 })
        assertTrue(c("HANDOVER_LOSS retained"), state.pending.values.any { GraphRecoveryReason.HANDOVER_LOSS in it.reasons })
        assertEquals(c("old observation preserved"), first.series.values.single().data.app.observations, state.data.app.observations)
    }

    @Test fun G12_sameLifetimeWithDifferentAuthFenceReadsCatalogOnlyAfterSync() = x.trial("G12") {
        val h = KrxBetaConsumerHolder(this, folder, null)
        try { h.changedFence() } finally { h.close() }
    }
}
