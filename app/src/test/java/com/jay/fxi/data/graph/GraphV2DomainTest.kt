package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.dto.GraphV2AxisGroup
import com.jay.fxi.data.remote.dto.GraphV2CarryIn
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
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphAxisGroup
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphPeriodSupport
import com.jay.fxi.domain.model.GraphTabAdmission
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 A1 contract r3 (r2 battery survivors K13·K23·K30·K32·K33·K50 closed by fixtures): the premium Graph V2 catalog and tab, turned into domain values.
 *
 * Oracles: ANDROID_V2_PLAN.md S4 (:1286 catalog/tab, :1288 only validated server data reaches disk,
 * :448 D15 TTL, :449 D16 per-tab periods), iOS a36682f GraphV2Models.swift:41-69 and
 * GraphV2ViewModel.swift:133-134 (TTL 3600 when the catalog is absent), :344-361 (catalog
 * series/defaults), :407-413 (a catalog failure is isolated from the tab), :745 (data fallback),
 * GraphV2Section.swift:549 (all four periods offered), and the free sanitizer's series rules
 * (FreeSnapshotSanitizer.kt:146-172, FreeSnapshot.kt:61). Design: R4c/S4 design_codex.r1 §6 as
 * corrected by design_review_claude.r1 R1-R5 and design_verdict_codex.r1 (contract_code kept, carry-in
 * against the earliest point, seeds validated, first duplicate claims the id). No access, disk, live or UI
 * concern belongs here.
 */
class GraphV2DomainTest {

    private val t0 = Instant.parse("2026-09-01T00:00:00+09:00")
    private val krw = GraphV2AxisGroup("KRW", 2, "left")

    private fun catalogDto(
        vararg tabs: GraphV2CatalogTab,
        ttl: Int = 3600,
        top: List<String> = listOf("1w", "3m", "1y")
    ) = GraphV2CatalogResponse(tabs.toList(), "2026-05-27", top, ttl)

    private fun ctab(id: String, periods: Map<String, GraphV2CatalogPeriod>) =
        GraphV2CatalogTab(id, "label-$id", mapOf("krw" to krw), periods)

    private fun cp(all: List<String>, defaults: List<String> = emptyList()) = GraphV2CatalogPeriod(all, defaults)

    private fun catalog(vararg tabs: GraphV2CatalogTab): GraphCatalog = GraphV2Domain.catalog(catalogDto(*tabs))

    private fun point(day: Int, rate: Double, high: Double? = null, low: Double? = null) =
        GraphV2Point(t0 + day.days, rate, "hana", high, low)

    private fun series(
        id: String,
        data: List<GraphV2Point> = listOf(point(0, 1390.0)),
        carryIn: GraphV2CarryIn? = null,
        insufficient: Boolean = false,
        decimals: Int = 2,
        label: String = "L-$id"
    ) = GraphV2Series(id, label, "krw", "KRW", decimals, data, GraphV2Provenance(insufficient, emptyList()), carryIn)

    private fun metadata(
        start: Instant? = t0,
        end: Instant? = t0 + 10.days,
        mode: String? = "fixed_start",
        bucket: String = "1d"
    ) = GraphV2Metadata(t0 + 10.days, bucket, GraphV2Range("2026-09-01", "2026-09-11"), start, end, mode)

    private fun tabDto(
        series: List<GraphV2Series>,
        tab: String = "usd",
        period: String = "3m",
        metadata: GraphV2Metadata = metadata(),
        inProgress: Map<String, GraphV2InProgressSeed>? = null
    ) = GraphV2TabResponse(tab, period, series, metadata, inProgress)

    private fun seed(close: Double = 1391.0, high: Double = 1392.0, low: Double = 1390.0) =
        GraphV2InProgressSeed(t0 + 600.seconds, high, low, close, t0 + 630.seconds)

    private fun admit(
        dto: GraphV2TabResponse,
        tab: String = "usd",
        period: GraphPeriod = GraphPeriod.THREE_MONTHS,
        catalog: GraphCatalog? = null
    ) = GraphV2Domain.admit(dto, tab, period, catalog)

    private fun accepted(a: GraphTabAdmission): GraphV2Tab =
        (a as? GraphTabAdmission.Accepted)?.tab ?: throw AssertionError("expected Accepted, got $a")

    private fun ids(t: GraphV2Tab) = t.graph.series.map { it.seriesId }

    // --- catalog -------------------------------------------------------------------------------

    /** C02: the catalog's own TTL, in seconds — not a hard-coded hour and not milliseconds. */
    @Test fun C02_theCatalogTtlOverridesTheDefault() {
        val c = GraphV2Domain.catalog(catalogDto(ctab("usd", mapOf("3m" to cp(listOf("a")))), ttl = 120))
        assertEquals(120.seconds, GraphV2Domain.ttl(c))
    }

    /** C03': no catalog (never fetched, failed, or undecodable) means an hour, as on iOS. The DTO keeps the field required. */
    @Test fun C03_withoutACatalogTheTtlIsAnHour() {
        assertEquals(3600.seconds, GraphV2Domain.ttl(null))
    }

    /** C04: periods come from the tab, never from the top-level list that omits 1d. Unknown keys are skipped. */
    @Test fun C04_periodsComeFromTheTab_notTheTopLevelList() {
        val c = GraphV2Domain.catalog(catalogDto(
            ctab("usd", linkedMapOf("3m" to cp(listOf("a")), "6m" to cp(listOf("a")), "1d" to cp(listOf("a")))),
            top = listOf("1w", "3m", "1y")
        ))
        assertEquals(listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), GraphV2Domain.periods(c, "usd"))
        assertEquals(GraphPeriodSupport.Supported, GraphV2Domain.support(c, "usd", GraphPeriod.ONE_DAY))
        assertEquals(GraphPeriodSupport.Unsupported, GraphV2Domain.support(c, "usd", GraphPeriod.ONE_WEEK))
    }

    /** C05: one registry per tab and period. A union across tabs or periods would put the wrong lines on screen. */
    @Test fun C05_theRegistryIsPerTabAndPeriod() {
        val c = catalog(
            ctab("usd", mapOf("3m" to cp(listOf("investing.usd-krw", "dxy")), "1y" to cp(listOf("investing.usd-krw")))),
            ctab("jpy", mapOf("3m" to cp(listOf("investing.jpy-krw"))))
        )
        assertEquals(listOf("investing.usd-krw", "dxy"), c.tabs.getValue("usd").periods.getValue(GraphPeriod.THREE_MONTHS).allSeries)
        assertEquals(listOf("investing.usd-krw"), c.tabs.getValue("usd").periods.getValue(GraphPeriod.ONE_YEAR).allSeries)
        assertEquals(listOf("investing.jpy-krw"), c.tabs.getValue("jpy").periods.getValue(GraphPeriod.THREE_MONTHS).allSeries)
        assertEquals(mapOf("krw" to GraphAxisGroup("KRW", 2, "left")), c.tabs.getValue("usd").axisGroups)
        assertEquals("label-usd", c.tabs.getValue("usd").label)
    }

    /** C06: a series id the app has never heard of is still a series; order and the server's label survive. */
    @Test fun C06_dynamicIdsKeepOrderAndServerLabels() {
        val all = listOf("hana.usd-krw", "investing.usd-krw", "brand-new.usd-krw")
        val c = catalog(ctab("usd", mapOf("3m" to cp(all))))
        assertEquals(all, c.tabs.getValue("usd").periods.getValue(GraphPeriod.THREE_MONTHS).allSeries)
        val t = accepted(admit(tabDto(all.map { series(it, label = "서버-$it") }), catalog = c))
        assertEquals(all, ids(t))
        assertEquals(all.map { "서버-$it" }, t.graph.series.map { it.label })
    }

    /** C07: with a catalog, a tab or period it does not list is refused outright — no neighbouring fallback. */
    @Test fun C07_aCatalogRefusesWhatItDoesNotList() {
        val c = catalog(ctab("usd", mapOf("3m" to cp(listOf("a")))))
        assertEquals(GraphPeriodSupport.Unsupported, GraphV2Domain.support(c, "eur", GraphPeriod.THREE_MONTHS))
        assertEquals(GraphPeriodSupport.Unsupported, GraphV2Domain.support(c, "usd", GraphPeriod.ONE_DAY))
        assertEquals(emptyList<GraphPeriod>(), GraphV2Domain.periods(c, "eur"))
    }

    /** C07b: without a catalog every period stays reachable (iOS draws all four and fetches anyway). */
    @Test fun C07b_withoutACatalogAllFourPeriodsRemain() {
        assertEquals(GraphPeriod.entries.toList(), GraphV2Domain.periods(null, "usd"))
        GraphPeriod.entries.forEach { assertEquals(GraphPeriodSupport.Supported, GraphV2Domain.support(null, "usd", it)) }
    }

    /** C09: defaults are only ever candidates from all_series; a default the catalog does not list creates nothing. */
    @Test fun C09_defaultsAreFilteredByAllSeries() {
        val c = catalog(ctab("usd", mapOf("3m" to cp(listOf("A", "B"), listOf("B", "X")))))
        assertEquals(listOf("B"), c.tabs.getValue("usd").periods.getValue(GraphPeriod.THREE_MONTHS).defaultVisible)
    }

    // --- tab -----------------------------------------------------------------------------------

    /** C08: with a catalog, a series outside all_series is dropped — line and in-progress seed alike. */
    @Test fun C08_aCatalogDropsSeriesItDoesNotList() {
        val c = catalog(ctab("usd", mapOf("1d" to cp(listOf("A")))))
        val t = accepted(admit(
            tabDto(listOf(series("A"), series("X")), period = "1d",
                metadata = metadata(mode = "rolling", bucket = "10min"),
                inProgress = mapOf("A" to seed(), "X" to seed())),
            period = GraphPeriod.ONE_DAY, catalog = c
        ))
        assertEquals(listOf("A"), ids(t))
        assertEquals(setOf("A"), t.inProgress.keys)
        // A catalog that does not list this tab and period permits no id at all — not every id.
        val unlisted = accepted(admit(
            tabDto(listOf(series("A")), period = "1d",
                metadata = metadata(mode = "rolling", bucket = "10min"), inProgress = mapOf("A" to seed())),
            period = GraphPeriod.ONE_DAY, catalog = catalog(ctab("usd", mapOf("3m" to cp(listOf("A")))))
        ))
        assertEquals(emptyList<String>(), ids(unlisted))
        assertEquals(emptySet<String>(), unlisted.inProgress.keys)
    }

    /** C08b: without a catalog the response's own series are used; a seed without a series is not a series. */
    @Test fun C08b_withoutACatalogTheResponseSeriesAreUsed() {
        val t = accepted(admit(
            tabDto(listOf(series("A"), series("X")), period = "1d",
                metadata = metadata(mode = "rolling", bucket = "10min"),
                inProgress = mapOf("A" to seed(), "X" to seed(), "Y" to seed())),
            period = GraphPeriod.ONE_DAY, catalog = null
        ))
        assertEquals(listOf("A", "X"), ids(t))
        assertEquals(setOf("A", "X"), t.inProgress.keys)
        assertEquals(GraphV2InProgress(t0 + 600.seconds, 1392.0, 1390.0, 1391.0, t0 + 630.seconds), t.inProgress.getValue("A"))
    }

    /** C10: an answer for another tab or period is refused whole; the request key never relabels it. */
    @Test fun C10_aMismatchedAnswerIsRefusedWhole() {
        val one = metadata(mode = "rolling", bucket = "10min")
        val ok = admit(tabDto(listOf(series("A")), period = "1d", metadata = one), period = GraphPeriod.ONE_DAY)
        assertTrue("positive control", ok is GraphTabAdmission.Accepted)
        listOf(
            tabDto(listOf(series("A")), tab = "jpy", period = "1d", metadata = one),
            tabDto(listOf(series("A")), tab = "usd", period = "1w"),
            tabDto(listOf(series("A")), tab = "usd", period = "xx")
        ).forEach {
            assertTrue("${it.tab}/${it.period}", admit(it, period = GraphPeriod.ONE_DAY) is GraphTabAdmission.Rejected)
        }
    }

    /**
     * C11': the domain, carry-in and provenance pass through untouched, including a domain the
     * shared builder will refuse. Validity is decided in one place (GraphPreparedBuilder), not twice.
     */
    @Test fun C11_serverFieldsPassThroughUntouched() {
        val carry = GraphV2CarryIn(1389.0, t0 - 3.days)
        val data = listOf(
            GraphV2Point(t0 + 1.days, 1391.0, "hana", 1395.0, 1385.25, "hana_observed_eod", "observed_rollup"),
            GraphV2Point(t0, 1390.5, "hana")
        )
        val wire = GraphV2Series("hana.usd-krw", "하나", "krw", "KRW", 2, data,
            GraphV2Provenance(true, listOf("close_basis", "source_method")), carry)
        val krx = GraphV2Series("krx.usd-krw-futures", "KRX 미국달러선물", "krw", "KRW", 1,
            listOf(GraphV2Point(t0, 1391.2, "krx", contractCode = "A75609")),
            GraphV2Provenance(false, listOf("contract_code")), null)
        val domains = listOf(
            Triple<Instant?, Instant?, String?>(t0, t0 + 10.days, "fixed_start"),
            Triple(null, t0 + 10.days, "fixed_start"),
            Triple(t0 + 10.days, t0, "fixed_start"),
            Triple(t0, t0 + 10.days, "rolling"),
            Triple(t0, t0 + 10.days, "sideways"),
            Triple(t0, t0 + 10.days, null)
        )
        domains.forEach { (start, end, mode) ->
            val t = accepted(admit(tabDto(listOf(wire, krx), metadata = metadata(start, end, mode))))
            val expected = FreeGraph(
                bucketSize = "1d",
                series = listOf(FreeGraphSeries(
                    seriesId = "hana.usd-krw",
                    points = listOf(
                        FreeGraphPoint(t0 + 1.days, 1391.0, 1395.0, 1385.25, "hana", "hana_observed_eod", "observed_rollup"),
                        FreeGraphPoint(t0, 1390.5, null, null, "hana")
                    ),
                    label = "하나", axisGroup = "krw", unit = "KRW", decimals = 2,
                    insufficientHistory = true, perPointMetadata = listOf("close_basis", "source_method"),
                    carryIn = FreeGraphCarryIn(1389.0, t0 - 3.days)
                ), FreeGraphSeries(
                    seriesId = "krx.usd-krw-futures",
                    points = listOf(FreeGraphPoint(t0, 1391.2, null, null, "krx", contractCode = "A75609")),
                    label = "KRX 미국달러선물", axisGroup = "krw", unit = "KRW", decimals = 1,
                    insufficientHistory = false, perPointMetadata = listOf("contract_code"), carryIn = null
                )),
                rangeStart = "2026-09-01", rangeEnd = "2026-09-11",
                domainStartAt = start, domainEndAt = end, liveDomainMode = mode
            )
            assertEquals("$start/$end/$mode", expected, t.graph)
            assertEquals(GraphPreparedBuilder.build(expected, GraphPeriod.THREE_MONTHS),
                GraphPreparedBuilder.build(t.graph, GraphPeriod.THREE_MONTHS))
            assertEquals(GraphPeriod.THREE_MONTHS, t.period)
            assertEquals("1d", t.bucketSize)
            assertEquals(t0 + 10.days, t.fetchedAt)
        }
    }

    /** C14: insufficient history is a normal answer, empty or partial — not a transport failure, and nothing is filled in. */
    @Test fun C14_insufficientHistoryIsANormalAnswer() {
        val empty = series("A", data = emptyList(), insufficient = true)
        val partial = series("B", data = listOf(point(9, 1391.0)), insufficient = true)
        val t = accepted(admit(tabDto(listOf(empty, partial))))
        assertEquals(listOf("A", "B"), ids(t))
        assertEquals(listOf(0, 1), t.graph.series.map { it.points.size })
        assertEquals(listOf(true, true), t.graph.series.map { it.insufficientHistory })
        val allEmpty = accepted(admit(tabDto(listOf(empty))))
        assertEquals(listOf(0), allEmpty.graph.series.map { it.points.size })
    }

    /**
     * C15: one bad point drops its whole series, never the answer. The first occurrence of an id
     * claims it even when it is bad — a later duplicate does not resurrect it. Carry-in is compared
     * with the earliest point, not the first one on the wire. A KRX series and its contract code are
     * kept (B1 separates it). A bad seed drops only itself; whether a seed is current is D2's.
     */
    @Test fun C15_aBadSeriesIsDroppedWhole_theRestIsKept() {
        val t = accepted(admit(tabDto(listOf(
            series("A"),
            series("neg", data = listOf(point(0, 1390.0), point(1, -1.0))),
            series("nan", data = listOf(point(0, Double.NaN))),
            series("huge", data = listOf(point(0, 1e9))),
            series("highBelow", data = listOf(point(0, 1390.0, high = 1389.0))),
            series("lowAbove", data = listOf(point(0, 1390.0, low = 1391.0))),
            series("badHigh", data = listOf(point(0, 1390.0, high = Double.POSITIVE_INFINITY))),
            series("badLow", data = listOf(point(0, 1390.0, low = 0.0))),
            series("carryAtFirst", carryIn = GraphV2CarryIn(1389.0, t0)),
            series("carryAfterEarliest", data = listOf(point(2, 1391.0), point(0, 1390.0)),
                carryIn = GraphV2CarryIn(1389.0, t0 + 1.days)),
            series("flat", data = listOf(point(0, 1390.0, high = 1390.0, low = 1390.0))),
            series("dup", data = listOf(point(0, 0.0))),
            series("dup"),
            series("carryBad", carryIn = GraphV2CarryIn(0.0, t0 - 1.days)),
            series("decimals", decimals = 9),
            series("A", label = "second"),
            GraphV2Series("krx.usd-krw-futures", "KRX", "krw", "KRW", 1,
                listOf(GraphV2Point(t0, 1391.2, "krx", contractCode = "A75609")),
                GraphV2Provenance(false, listOf("contract_code")), null),
            series("carryOnly", data = emptyList(), carryIn = GraphV2CarryIn(1389.0, t0 - 1.days), insufficient = true),
            series("seedHighInf")
        ), period = "1d", metadata = metadata(mode = "rolling", bucket = "10min"), inProgress = mapOf(
            "A" to seed(),
            "flat" to seed(close = 1389.5, high = 1392.0, low = 1390.0),
            "krx.usd-krw-futures" to seed(close = 1393.0),
            "carryOnly" to seed(low = -1.0),
            "seedHighInf" to seed(high = Double.POSITIVE_INFINITY),
            "neg" to seed()
        )), period = GraphPeriod.ONE_DAY))
        assertEquals(listOf("A", "flat", "krx.usd-krw-futures", "carryOnly", "seedHighInf"), ids(t))
        assertEquals("L-A", t.graph.series.first().label)
        assertEquals("A75609", t.graph.series.single { it.seriesId == "krx.usd-krw-futures" }.points.single().contractCode)
        assertEquals(setOf("A"), t.inProgress.keys)
    }
}
