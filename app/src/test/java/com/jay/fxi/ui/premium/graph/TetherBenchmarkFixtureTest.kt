package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSelectionChange
import com.jay.fxi.domain.model.GraphSelectionUniverse
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.GraphSeriesSelectionPolicy
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S5-4 A (JVM): the tether benchmark fixture under app/src/benchmark/assets/s5_4 - the single source the benchmark reads as an
 * asset - is frozen byte for byte, its selection seed survives the real selection policy, and the real tether holder over the
 * real request coordinator draws its 11 one-day series, the catalog's 5 one-week series, then the 11 again from the cache.
 *
 * Agreed plan R4c/S5 s5_4a_plan.r2.md (S5-4 plan r2 §1 A). The 11-series seed shows dxy and dxy_futures together, which the
 * toggle never allows (GraphSeriesSelectionPolicy.toggle): it is a load seed only, and the selection policy is unchanged. The
 * in_progress seeds are frozen payload; this test does not claim the drawn current-bucket load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherBenchmarkFixtureTest {

    @get:Rule val folder = TemporaryFolder()

    private val dir = File("src/benchmark/assets/s5_4")
    private val wire = NetworkModule.provideWireJson()
    private fun bytes(name: String) = File(dir, name).readBytes()
    private val catalog by lazy { wire.decodeFromString<GraphV2CatalogResponse>(bytes("catalog.json").decodeToString()) }
    private val day by lazy { wire.decodeFromString<GraphV2TabResponse>(bytes("tether-1d.json").decodeToString()) }
    private val week by lazy { wire.decodeFromString<GraphV2TabResponse>(bytes("tether-1w.json").decodeToString()) }
    private val bundle by lazy { Json.parseToJsonElement(bytes("bundle.json").decodeToString()).jsonObject }
    private val seed by lazy {
        val selection = bundle.getValue("selection").jsonObject
        fun ids(field: String) = selection.getValue(field).jsonArray.map { it.jsonPrimitive.content }
        GraphSeriesSelection(ids("visible_series_ids").toSet(), ids("initialized_series").toSet())
    }

    private companion object {
        val ONE_DAY = listOf(
            "upbit.usdt-krw", "bithumb.usdt-krw", "coinone.usdt-krw", "korbit.usdt-krw", "gopax.usdt-krw", "krx.usd-krw-futures",
            "investing.usd", "kb.usd", "hana.usd", "dxy", "dxy_futures"
        )
        val ONE_WEEK = listOf("bithumb.usdt-krw", "krx.usd-krw-futures", "investing.usd", "hana.usd", "dxy")
        /** 12:00 KST: the current 10-minute bucket and the one-day domain end. */
        val D: Instant = Instant.parse("2026-10-05T03:00:00Z")
        /** 00:00 KST on Monday 2026-09-28: the one-week domain start. */
        val W0: Instant = Instant.parse("2026-09-27T15:00:00Z")
        val NOW: Instant = D + 1.minutes
        /** Each file's whole-byte SHA-256, copied from the agreed plan's shasum output (never computed from the inputs). */
        val SHA256 = mapOf(
            "catalog.json" to "b18f0798f972f44a6d45f3610f66e43dddc400cc9166d60c6d7fe90b474ebf62",
            "tether-1d.json" to "1982e740c5adb5bc2b470ea256f75051d2581e2eeaa6bf42495cbaa336af8e34",
            "tether-1w.json" to "653ee04152a045bac1ab9e3ec2ed3a9343fd6b6e5cff832d388cb9f55bb132c8",
            "bundle.json" to "8fff6bcd1b301c7d7bcc25bc8d64529e34a120fddb1ad85fe217e37a13eb5333"
        )
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Each series' axis group, unit and decimals as the server labels them (contracts usd-1d-krx-visible, free tether-1d). */
    private fun convention(id: String) = when {
        id.startsWith("dxy") -> Triple("index", "INDEX", 3)
        id == "krx.usd-krw-futures" -> Triple("krw", "KRW", 1)
        else -> Triple("krw", "KRW", 2)
    }

    /** The tab, period, series in order with the server's labels, exact point times at [step] from [start], and OHLC order. */
    private fun checkSeries(response: GraphV2TabResponse, period: String, ids: List<String>, count: Int, start: Instant, step: Duration) {
        assertEquals("tether", response.tab)
        assertEquals(period, response.period)
        assertEquals("$period series", ids, response.series.map { it.id })
        for (series in response.series) {
            val (axis, unit, decimals) = convention(series.id)
            assertEquals("${series.id} axis", axis, series.axisGroup)
            assertEquals("${series.id} unit", unit, series.unit)
            assertEquals("${series.id} decimals", decimals, series.decimals)
            assertEquals("$period ${series.id} times", List(count) { start + step * it }, series.data.map { it.ts })
            assertTrue("$period ${series.id} OHLC order", series.data.all { checkNotNull(it.high) >= it.rate && it.rate >= checkNotNull(it.low) })
        }
    }

    private fun assertExtremes(label: String, response: GraphV2TabResponse, axis: String, low: Double, high: Double) {
        val points = response.series.filter { it.axisGroup == axis }.flatMap { it.data }
        assertEquals("$label $axis lowest low", low, points.minOf { checkNotNull(it.low) }, 0.0)
        assertEquals("$label $axis highest high", high, points.maxOf { checkNotNull(it.high) }, 0.0)
    }

    /** BF1: every file matches its frozen SHA-256. */
    @Test fun BF1_payloadIsFrozen() {
        for ((name, expected) in SHA256) assertEquals("$name bytes", expected, sha256(bytes(name)))
    }

    /**
     * BF1S: what BF1 freezes reads as agreed - the catalog's tether periods, both responses' series, point counts and exact
     * times, metadata, the one-day current-bucket seeds, OHLC order, each axis group's extremes and the bundle's now, tab,
     * capability and seed. Apart from BF1 so a changed file is checked by these assertions too, not stopped at its bytes.
     */
    @Test fun BF1S_payloadReadsAsAgreed() {
        val tether = catalog.tabs.single { it.id == "tether" }
        assertEquals(ONE_DAY, tether.periods.getValue("1d").allSeries)
        assertEquals(ONE_WEEK, tether.periods.getValue("1w").allSeries)

        checkSeries(day, "1d", ONE_DAY, 144, D - 24.hours, 10.minutes)
        checkSeries(week, "1w", ONE_WEEK, 180, W0, 1.hours)
        with(day.metadata) {
            assertEquals(D, fetchedAt); assertEquals("10min", bucketSize); assertEquals(D - 24.hours, domainStartAt)
            assertEquals(D, domainEndAt); assertEquals("rolling", liveDomainMode); assertEquals("2026-10-04" to "2026-10-05", range.start to range.end)
        }
        with(week.metadata) {
            assertEquals(D, fetchedAt); assertEquals("1h", bucketSize); assertEquals(W0, domainStartAt)
            assertEquals(D, domainEndAt); assertEquals("fixed_start", liveDomainMode); assertEquals("2026-09-28" to "2026-10-05", range.start to range.end)
        }
        val seeds = checkNotNull(day.inProgress)
        assertEquals(ONE_DAY.toSet(), seeds.keys)
        for ((id, s) in seeds) {
            assertEquals("$id seed bucket", D, s.bucketStart)
            assertEquals("$id seed sampled", D + 30.seconds, s.sampledAt)
            assertTrue("$id seed order", s.high >= s.close && s.close >= s.low)
        }
        assertEquals(null, week.inProgress)
        assertExtremes("1d", day, "krw", 1382.38, 1403.4)
        assertExtremes("1d", day, "index", 97.834, 98.965)
        assertExtremes("1w", week, "krw", 1382.42, 1403.44)
        assertExtremes("1w", week, "index", 98.04, 98.967)

        assertEquals(NOW, Instant.parse(bundle.getValue("now").jsonPrimitive.content))
        assertEquals("tether", bundle.getValue("tab").jsonPrimitive.content)
        assertEquals(true, bundle.getValue("krx_visible").jsonPrimitive.boolean)
        assertEquals(GraphSeriesSelection(ONE_DAY.toSet(), ONE_DAY.toSet()), seed)
    }


    /**
     * BF2: the seed carries all 11 one-day ids as both visible and initialized - both dollar indexes included - so the real
     * policy's initialization keeps it unchanged, and the one-week projection is the catalog's 5.
     */
    @Test fun BF2_selectionSeedSurvivesThePolicy() {
        assertEquals(ONE_DAY.toSet(), seed.visibleSeriesIds)
        assertEquals(ONE_DAY.toSet(), seed.initializedSeries)
        assertTrue("both dollar indexes", setOf("dxy", "dxy_futures").all { it in seed.visibleSeriesIds })
        val oneDay = catalog.tabs.single { it.id == "tether" }.periods.getValue("1d")
        val universe = GraphSelectionUniverse.Catalog(oneDay.allSeries.toSet(), oneDay.defaultVisibleSeries.toSet())
        assertEquals(GraphSelectionChange.Unchanged, GraphSeriesSelectionPolicy.initialize(seed, "tether", universe, krxVisible = true))
        assertEquals(ONE_WEEK.toSet(), GraphSeriesSelectionPolicy.renderedIds(seed, ONE_WEEK.toSet(), krxVisible = true))
    }

    /**
     * BF3: with the catalog and the seed stored under the exact tether key before start, the real tether holder answers its
     * one-day request with the fixture and draws 11 non-empty lines over 11 non-empty REST series; one week asks once and draws
     * the catalog's 5; returning to one day asks nothing (the online answer is still fresh, no virtual time passed) and draws
     * the first projection again. The raw selection stays the 11 throughout. This is the same session's fresh-cache return, not
     * TTL expiry or a disk restore.
     */
    @Test fun BF3_theRealHolderDrawsElevenThenFiveThenEleven() = runTest {
        val f = TetherGraphFixture(this, folder, start = NOW)
        try {
            f.catalogDto = catalog
            f.selections.committed[GraphSelectionKey("u1", GraphSelectionAudience.PREMIUM, "tether")] = seed
            f.start()
            f.activate("tether")
            val dayKey = GraphKey("tether", GraphPeriod.ONE_DAY)
            val weekKey = GraphKey("tether", GraphPeriod.ONE_WEEK)
            f.answer(dayKey, TetherGraphData.ok(day))
            f.show("tether")
            val holder = f.holders.getValue("tether")

            fun drawn(period: GraphPeriod, ids: List<String>): com.jay.fxi.ui.graph.GraphPlot {
                val state = f.state("tether")
                val chart = checkNotNull(state.chart) { "no chart in $state" }
                assertEquals(period, state.activePeriod)
                assertEquals(period, chart.prepared.period)
                assertEquals(GraphV2Content.READY, state.content)
                assertEquals(ids.toSet(), chart.renderedIds)
                val plot = checkNotNull(GraphV2ScreenPresenter.project(chart))
                assertEquals(ids.toSet(), plot.lines.map { it.seriesId }.toSet())
                assertEquals(ids.size, plot.lines.size)
                assertTrue("$period: every line has points", plot.lines.all { it.points.isNotEmpty() })
                assertEquals("$period raw selection", seed, state.selection)
                return plot
            }

            for (id in ONE_DAY) assertTrue("$id REST points", f.seriesOf("tether", id).linePoints.isNotEmpty())
            val first = drawn(GraphPeriod.ONE_DAY, ONE_DAY)

            holder.selectPeriod(f.token("tether"), GraphPeriod.ONE_WEEK)
            f.run()
            assertEquals("one week asked once", 1, f.sent.count { it.key == weekKey })
            f.answer(weekKey, TetherGraphData.ok(week))
            drawn(GraphPeriod.ONE_WEEK, ONE_WEEK)

            holder.selectPeriod(f.token("tether"), GraphPeriod.ONE_DAY)
            f.run()
            assertEquals("cached return asks nothing", first, drawn(GraphPeriod.ONE_DAY, ONE_DAY))
            assertEquals(listOf(dayKey, weekKey), f.sent.map { it.key })
        } finally {
            f.close()
        }
    }
}
