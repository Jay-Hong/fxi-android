package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.premium.graph.TetherGraphData.B
import com.jay.fxi.ui.premium.graph.TetherGraphData.A
import com.jay.fxi.ui.premium.graph.TetherGraphData.REFERENCES
import com.jay.fxi.ui.premium.graph.TetherGraphData.KRX
import com.jay.fxi.ui.premium.graph.TetherGraphData.USD_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.TETHER_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.LONG_USD
import com.jay.fxi.ui.premium.graph.TetherGraphData.LONG_TETHER
import com.jay.fxi.ui.premium.graph.TetherGraphData.catalog
import com.jay.fxi.ui.premium.graph.TetherGraphData.series
import com.jay.fxi.ui.premium.graph.TetherGraphData.dayPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.day
import com.jay.fxi.ui.premium.graph.TetherGraphData.seed
import com.jay.fxi.ui.premium.graph.TetherGraphData.longPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.long
import com.jay.fxi.ui.premium.graph.TetherGraphData.linesOf
import com.jay.fxi.ui.graph.GraphProjection
import com.jay.fxi.ui.graph.PreparedSeries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S5-2a (JVM): the premium tether graph holder over the real request coordinator and graph recorder, beside the usd holder.
 *
 * Oracles: ANDROID_V2_PLAN.md :1517-1523 (catalog periods, no synthesized series, T17/T18 for the tether reference rows, T22's
 * non-KRX part, T24); iOS 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :265-266 (T17, T18), :270 (T22), :272 (T24); server
 * graph_v2_intraday.py:88-101/:155-158 and graph_v2.py:119-125 (tether 1d and long-term series, defaults); agreed scope R4c/S5
 * s5_2_scope_proposal.r3.md S5-2a. The tether holder is opened by GraphV2ScreenStateHolder.kt:131 and GraphScreenHost.kt:117.
 *
 * Times: B is the current 10-minute bucket 12:00-12:10 KST (2026-10-05); the original rows' 19:59/20:00/20:01/20:02 are B-1 min,
 * B, B+1 min and B+2 min here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherGraphHolderTest {

    @get:Rule val folder = TemporaryFolder()

    private val opened = mutableListOf<TetherGraphFixture>()

    private fun holderTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    /** A fixture this test closes at its end. */
    private fun Fixture(test: TestScope, krxCapability: Boolean = true, catalogTtlSeconds: Int = 172800) =
        TetherGraphFixture(test, folder, krxCapability = krxCapability, catalogTtlSeconds = catalogTtlSeconds).also { opened += it }

    private fun TetherGraphFixture.openTether() {
        start()
        activate("tether")
        answer(GraphKey("tether", GraphPeriod.ONE_DAY), day("tether", TETHER_1D))
        show("tether")
    }

    private fun PreparedSeries.bandAtEnd() = bandPoints.last().let { it.low to it.high }

    // --- catalog, selection and KRX gate ---------------------------------------------------------------------------------

    /** Distinct per period in both time and price, so one period's points can never pass for another's. */
    /**
     * TH01: the tether holder offers the catalog's four periods; for 1w, 3m and 1y each it asks the tether tab for that period and
     * exposes only that period's catalog series - upbit and KB, absent from the long-term catalog, are not synthesized even when
     * the response carries them.
     */
    @Test fun TH01_catalogPeriodsAndNoSynthesizedSeries() = holderTest {
        val f = Fixture(this)
        f.openTether()
        assertEquals(listOf(GraphPeriod.ONE_DAY, GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR), f.state("tether").periods)
        assertEquals(TETHER_1D.toSet(), f.prepared("tether").bySeries.keys)
        for (period in listOf(GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)) {
            f.holders.getValue("tether").selectPeriod(f.token("tether"), period)
            f.run()
            val key = GraphKey("tether", period)
            assertTrue("asked the tether tab for $period", f.sent.any { it.key == key })
            f.answer(key, long("tether", period, LONG_TETHER + "upbit.usdt-krw" + "kb.usd"))
            val prepared = f.prepared("tether")
            assertEquals("$period: the response adopted", period, prepared.period)
            assertEquals("$period: catalog series only", LONG_TETHER.toSet(), prepared.bySeries.keys)
            for (id in LONG_TETHER) {
                assertEquals("$period $id: that period's server points", linesOf(longPoints(id, period)), prepared.bySeries.getValue(id).linePoints)
            }
        }
    }

    /**
     * TH02: with the KRX capability the first selection is upbit, bithumb and KRX (hana left out); without it KRX is neither
     * exposed nor selected and hana is. dxy and dxy_futures replace each other when toggled on, in both directions.
     */
    @Test fun TH02_defaultsKrxGateAndIndexExclusivity() = holderTest {
        Fixture(this, krxCapability = true).let { f ->
            f.openTether()
            assertEquals(setOf("upbit.usdt-krw", "bithumb.usdt-krw", KRX), f.state("tether").selection?.visibleSeriesIds)
            assertTrue(KRX in f.prepared("tether").bySeries.keys)
            val h = f.holders.getValue("tether")
            fun visible() = f.state("tether").selection!!.visibleSeriesIds
            h.toggleSeries(f.token("tether"), "dxy"); f.run()
            assertTrue("dxy" in visible())
            h.toggleSeries(f.token("tether"), "dxy_futures"); f.run()
            assertTrue("dxy_futures" in visible())
            assertFalse("dxy off once dxy_futures is on", "dxy" in visible())
            h.toggleSeries(f.token("tether"), "dxy"); f.run()
            assertTrue("dxy" in visible())
            assertFalse("dxy_futures off once dxy is on", "dxy_futures" in visible())
        }
        Fixture(this, krxCapability = false).let { f ->
            f.openTether()
            assertEquals(setOf("upbit.usdt-krw", "bithumb.usdt-krw", "hana.usd"), f.state("tether").selection?.visibleSeriesIds)
            assertFalse(KRX in f.prepared("tether").bySeries.keys)
        }
    }

    // --- T24 ---------------------------------------------------------------------------------------------------------------

    /**
     * TH03 (T24): a dxy WS record moves the dxy line to the published now while dxy_futures keeps exactly its REST points; with
     * the holder's own selection moved through a lone KRW series, two KRW series, a KRW series with an index and an index alone,
     * the final plot draws the band only in the first.
     */
    @Test fun TH03_T24_dxyFuturesIsRestOnly() = holderTest {
        val f = Fixture(this)
        f.openTether()
        f.dxy(105.1, B + 2.minutes)
        f.exchange("bithumb", 1490.0, B + 2.minutes)
        f.advance(500)
        val chart = checkNotNull(f.state("tether").chart)
        val now = checkNotNull(chart.rightEdgeNow)
        assertEquals("futures: its REST points only", linesOf(dayPoints("dxy_futures")), chart.prepared.bySeries.getValue("dxy_futures").linePoints)
        assertEquals("spot: a tail at the published now", com.jay.fxi.ui.graph.LinePoint(now, 105.1), chart.prepared.bySeries.getValue("dxy").linePoints.last())

        val h = f.holders.getValue("tether")
        fun toggle(id: String) { h.toggleSeries(f.token("tether"), id); f.run() }
        fun bands(): Map<String, Boolean> {
            val c = checkNotNull(f.state("tether").chart)
            return GraphProjection.plot(c.prepared, c.renderedIds, rightEdgeNow = c.rightEdgeNow)!!.lines.associate { it.seriesId to it.band.isNotEmpty() }
        }
        toggle("upbit.usdt-krw"); toggle(KRX)
        assertEquals("a lone KRW series", mapOf("bithumb.usdt-krw" to true), bands())
        toggle("upbit.usdt-krw")
        assertEquals("two KRW series", mapOf("bithumb.usdt-krw" to false, "upbit.usdt-krw" to false), bands())
        toggle("upbit.usdt-krw"); toggle("dxy")
        assertEquals("KRW with an index", mapOf("bithumb.usdt-krw" to false, "dxy" to false), bands())
        toggle("bithumb.usdt-krw"); toggle("dxy_futures")
        assertEquals("an index alone", mapOf("dxy_futures" to false), bands())
    }

    // --- T22 ---------------------------------------------------------------------------------------------------------------

    private fun TetherGraphFixture.answerAll(tab: String, ids: List<String>, seeds: Map<String, GraphV2InProgressSeed>) {
        sent.filter { it.key == GraphKey(tab, GraphPeriod.ONE_DAY) && !it.answer.isCompleted }.forEach { it.answer.complete(day(tab, ids, seeds)) }
        run()
    }

    /**
     * TH04 (T22, 1d): each shared non-KRX series given the same seed [1341.7, 1341.9] and observation 1342.1 reads [1341.7, 1342.1]
     * with its line ending at 1342.1, and the dollar index ends at 105.1 - in both tabs, read at one fixed now, with the two tabs'
     * series equal point for point. The mount order and the order the two tabs' responses complete vary independently, with a
     * re-fetch in between and the observations delivered again (also by the tether topic).
     */
    @Test fun TH04_T22_sharedSeriesAgreeInBothTabs() = holderTest {
        for (usdMountsFirst in listOf(true, false)) for (firstAnswersFirst in listOf(true, false)) {
            val label = "usdMountsFirst=$usdMountsFirst firstAnswersFirst=$firstAnswersFirst"
            val f = Fixture(this)
            f.start()
            val mount = if (usdMountsFirst) listOf("usd", "tether") else listOf("tether", "usd")
            fun idsOf(tab: String) = if (tab == "usd") USD_1D else TETHER_1D
            val seeds = REFERENCES.associateWith { seed(1341.7, 1341.9, 1341.8) }
            // The catalog arrives with the first tab; then each shared series exists with a demand for the next requests to capture.
            f.activate(mount[0])
            f.answerAll(mount[0], idsOf(mount[0]), emptyMap())
            for (id in REFERENCES) f.fx(id.substringBefore('.'), 1341.0, B - 5.minutes)
            f.dxy(104.9, B - 5.minutes)
            f.coordinator.onRefreshRequested(force = true); f.run()
            f.activate(mount[1])
            val answers = if (firstAnswersFirst) mount else mount.reversed()
            for (tab in answers) f.answerAll(tab, idsOf(tab), seeds)
            for (id in REFERENCES) f.fx(id.substringBefore('.'), 1342.1, B + 1.minutes + 30.seconds)
            f.dxy(105.1, B + 1.minutes + 30.seconds)
            for (id in REFERENCES) {
                f.fx(id.substringBefore('.'), 1342.1, B + 1.minutes + 30.seconds)
                f.tetherRow(id.substringBefore('.'), 1342.1, B + 1.minutes + 30.seconds)
            }
            // Read both at one virtual time: no time passes between the two reads.
            val read = mutableMapOf<String, Map<String, PreparedSeries>>()
            val edges = mutableMapOf<String, Instant?>()
            for (tab in listOf("usd", "tether")) {
                f.activate(tab)
                f.answerAll(tab, idsOf(tab), seeds)
                f.show(tab)
                val chart = checkNotNull(f.state(tab).chart) { "$label: $tab chart" }
                edges[tab] = chart.rightEdgeNow
                read[tab] = (REFERENCES + "dxy").associateWith { chart.prepared.bySeries.getValue(it) }
            }
            assertNotNull("$label: a live edge", edges["usd"])
            assertEquals("$label: one fixed now", edges["usd"], edges["tether"])
            for (id in REFERENCES) {
                val usd = read.getValue("usd").getValue(id)
                val tether = read.getValue("tether").getValue(id)
                assertEquals("$label $id", 1341.7 to 1342.1, usd.bandAtEnd())
                assertEquals("$label $id", 1342.1, usd.linePoints.last().rate, 0.0)
                assertEquals("$label $id: equal in both tabs", usd.linePoints, tether.linePoints)
                assertEquals("$label $id: equal bands", usd.bandPoints, tether.bandPoints)
            }
            for (tab in listOf("usd", "tether")) assertEquals("$label $tab dxy", 105.1, read.getValue(tab).getValue("dxy").linePoints.last().rate, 0.0)
            assertEquals("$label dxy equal", read.getValue("usd").getValue("dxy").linePoints, read.getValue("tether").getValue("dxy").linePoints)
            f.close()
        }
    }

    /**
     * TH05 (T22, long term and a catalog change): on 3m investing.usd, hana.usd and dxy carry exactly the server points in both
     * tabs and KB is not synthesized. With KB selected on tether's 1d, a catalog that drops kb.usd from tether's 1d removes it from
     * the tether holder's toggles, chart and rendered series, while the usd holder keeps it; the remaining shared 1d series still
     * agree.
     */
    @Test fun TH05_T22_longTermIntersectionAndACatalogChange() = holderTest {
        val f = Fixture(this, catalogTtlSeconds = 60)
        f.start()
        for (tab in listOf("usd", "tether")) {
            f.activate(tab)
            f.answer(GraphKey(tab, GraphPeriod.ONE_DAY), day(tab, if (tab == "usd") USD_1D else TETHER_1D))
            f.show(tab)
            f.holders.getValue(tab).selectPeriod(f.token(tab), GraphPeriod.THREE_MONTHS)
            f.run()
            f.answer(GraphKey(tab, GraphPeriod.THREE_MONTHS), long(tab, GraphPeriod.THREE_MONTHS, if (tab == "usd") LONG_USD else LONG_TETHER))
            for (id in listOf("investing.usd", "hana.usd", "dxy")) {
                assertEquals("$tab $id: the server's points", linesOf(longPoints(id, GraphPeriod.THREE_MONTHS)), f.seriesOf(tab, id).linePoints)
            }
            if (tab == "tether") assertFalse("no KB on the long term", "kb.usd" in f.prepared("tether").bySeries.keys)
        }

        val h = f.holders.getValue("tether")
        h.selectPeriod(f.token("tether"), GraphPeriod.ONE_DAY); f.run()
        h.toggleSeries(f.token("tether"), "kb.usd"); f.run()
        assertTrue("premise: KB selected on tether", "kb.usd" in f.state("tether").chart!!.renderedIds)
        f.catalogDto = catalog(tetherOneDay = TETHER_1D - "kb.usd", ttlSeconds = 60)
        f.advance(61_000)
        f.coordinator.onRefreshRequested()
        f.run()
        f.answerAll("tether", TETHER_1D, emptyMap())
        val tether = f.state("tether")
        assertFalse("not in the chart", "kb.usd" in f.prepared("tether").bySeries.keys)
        assertFalse("not rendered", "kb.usd" in tether.chart!!.renderedIds)
        assertFalse("no toggle", tether.toggles.any { it.seriesId == "kb.usd" })
        // The shared non-KRX 1d series after the change, recomputed from the new catalog.
        val catalogNow = checkNotNull(f.coordinator.state.value.catalog)
        fun oneDay(tab: String) = catalogNow.tabs.getValue(tab).periods.getValue(GraphPeriod.ONE_DAY).allSeries.toSet()
        val shared = (oneDay("usd") intersect oneDay("tether")).filterNot { it.startsWith("krx.") }.toSet()
        assertEquals("the shared set after the change", setOf("investing.usd", "hana.usd", "dxy"), shared)
        // Each shared KRW series gets the seed and the observation; then both tabs are read at one fixed now.
        val seeds = listOf("investing.usd", "hana.usd").associateWith { seed(1341.7, 1341.9, 1341.8) }
        for (id in seeds.keys) f.fx(id.substringBefore('.'), 1341.0, B - 5.minutes)
        f.dxy(104.9, B - 5.minutes)
        f.coordinator.onRefreshRequested(force = true); f.run()
        f.answerAll("tether", TETHER_1D - "kb.usd", seeds)
        for (id in seeds.keys) f.fx(id.substringBefore('.'), 1342.1, B + 1.minutes + 30.seconds)
        f.dxy(105.1, B + 1.minutes + 30.seconds)
        // Each tab is read right after its own (re)exposure, which publishes at once, with no time passing between the reads.
        f.activate("usd")
        f.answerAll("usd", USD_1D, seeds)
        f.show("usd")
        if (f.state("usd").activePeriod != GraphPeriod.ONE_DAY) {
            f.holders.getValue("usd").selectPeriod(f.token("usd"), GraphPeriod.ONE_DAY); f.run()
            f.answerAll("usd", USD_1D, seeds)
        }
        val usdChart = checkNotNull(f.state("usd").chart)
        f.activate("tether")
        f.answerAll("tether", TETHER_1D - "kb.usd", seeds)
        f.show("tether")
        val tetherChart = checkNotNull(f.state("tether").chart)
        assertTrue("usd keeps KB", "kb.usd" in usdChart.prepared.bySeries.keys)
        assertNotNull(tetherChart.rightEdgeNow)
        assertEquals("one fixed now", tetherChart.rightEdgeNow, usdChart.rightEdgeNow)
        for (id in shared) {
            val usd = usdChart.prepared.bySeries.getValue(id)
            val tether = tetherChart.prepared.bySeries.getValue(id)
            assertEquals("$id: whole line equal", usd.linePoints, tether.linePoints)
            assertEquals("$id: whole band equal", usd.bandPoints, tether.bandPoints)
            if (id == "dxy") {
                assertEquals(105.1, usd.linePoints.last().rate, 0.0)
            } else {
                assertEquals(id, 1341.7 to 1342.1, usd.bandAtEnd())
                assertEquals(id, 1342.1, usd.linePoints.last().rate, 0.0)
            }
        }
    }

    // --- T17 / T18 for the tether consumer ---------------------------------------------------------------------------------

    /** Reads [id] in both tabs at one virtual time. */
    private fun TetherGraphFixture.bothTabs(id: String): Pair<PreparedSeries, PreparedSeries> {
        activate("usd"); answerAll("usd", USD_1D, emptyMap()); show("usd")
        val usd = seriesOf("usd", id)
        activate("tether"); answerAll("tether", TETHER_1D, emptyMap()); show("tether")
        return usd to seriesOf("tether", id)
    }

    /**
     * TH06 (T17, T18): for each reference row by the tether topic, read in both tabs at one time - an old record (high and low
     * cases) re-sent at B+2 opens no B range and a new record then makes B [1341.7, 1341.7]; a same-price record at B followed by
     * 1341.5 or 1342.5 makes B [1341.5, 1342.0] or [1342.0, 1342.5]. Both tabs agree in each step.
     */
    @Test fun TH06_T17_T18_referenceRowsOnTheTetherConsumer() = holderTest {
        for (id in REFERENCES) {
            val source = id.substringBefore('.')
            for (old in listOf(1345.7, 1337.7)) {
                val f = Fixture(this)
                f.openTether()
                f.tetherRow(source, old, B - 1.minutes)
                f.advance(60_000)
                f.tetherRow(source, old, B - 1.minutes)
                val (usdBefore, tetherBefore) = f.bothTabs(id)
                for ((tab, s) in listOf("usd" to usdBefore, "tether" to tetherBefore)) {
                    assertTrue("$id $old $tab: no B range from an old record", s.bandPoints.none { it.ts > B })
                }
                assertEquals("$id $old: equal before", usdBefore.bandPoints, tetherBefore.bandPoints)
                f.tetherRow(source, 1341.7, B + 2.minutes + 5.seconds)
                f.advance(10_000)
                val (usd, tether) = f.bothTabs(id)
                assertEquals("$id $old usd", 1341.7 to 1341.7, usd.bandAtEnd())
                assertEquals("$id $old tether", 1341.7 to 1341.7, tether.bandAtEnd())
                assertEquals("$id $old: equal after", usd.bandPoints, tether.bandPoints)
                f.close()
            }
            for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
                val f = Fixture(this)
                f.openTether()
                f.tetherRow(source, 1342.0, B - 1.minutes)
                f.tetherRow(source, 1342.0, B)
                f.tetherRow(source, next, B + 1.minutes)
                val (usd, tether) = f.bothTabs(id)
                assertEquals("$id $next usd", expected, usd.bandAtEnd())
                assertEquals("$id $next tether", expected, tether.bandAtEnd())
                assertEquals("$id $next: equal", usd.bandPoints, tether.bandPoints)
                f.close()
            }
        }
    }

    /**
     * TH07 (tether consumer, inline and fullscreen): the tether holder opens its fullscreen from the inline surface and closes it
     * back; both surfaces carry the tether tab and the one shared chart, as for the FX tabs.
     */
    @Test fun TH07_inlineAndFullscreenShowTheOneTetherChart() = holderTest {
        val f = Fixture(this)
        f.openTether()
        val inline = f.prepared("tether")
        val h = f.holders.getValue("tether")
        h.enterFullscreen(f.token("tether")); f.run()
        val open = f.state("tether")
        assertTrue(open.fullscreenOpen)
        assertEquals("tether", open.fullscreenToken?.tab)
        assertEquals("tether", open.inlineToken?.tab)
        assertEquals("one shared chart", inline, f.prepared("tether"))
        h.exitFullscreen(checkNotNull(open.fullscreenToken)); f.run()
        assertFalse(f.state("tether").fullscreenOpen)
        assertEquals(inline, f.prepared("tether"))
    }
}
