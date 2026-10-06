package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphObservationOutcome.ADDED
import com.jay.fxi.data.graph.GraphObservationOutcome.DUPLICATE
import com.jay.fxi.data.graph.GraphObservationOutcome.REJECTED
import com.jay.fxi.data.graph.GraphObservationOutcome.SAME_TIME_CONFLICT
import com.jay.fxi.data.graph.GraphRecoveryReason.TIME_ANOMALY
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 E2 contract r1 (JVM): dollar-index candidates mapped to the one `dxy` series, with the fixed source order
 * that decides its close and tip.
 *
 * Oracles: ANDROID_V2_PLAN.md :1295-1297 (DXY has its own bridge from the S3 hand-over), :1300-1303 (DXY uses `timestamp` and
 * its supplying source), :1349-1350 (no DXY shading - C2's part), :1353-1355 (DXY tip: timestamp first, at the same time
 * investing > cnbc > yahoo; a price conflict at the same timestamp and source keeps the first adopted tip and the conflicting
 * observation is not deleted as a duplicate), :1360-1364 (no range or shading cases for DXY - time, duplicate, recovery and line
 * cases only); server dxy_topic_publisher.py:20-38 (`dxy:spot`, sources exactly investing, cnbc, yahoo, others dropped),
 * graph_v2_intraday.py:138-147 (usd 1d lists `dxy`); iOS 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md:268, 392-394 (T20).
 * Design: R4c/S4 e2_design_codex.r1 as trimmed by e2_review_claude.r1:
 *  - DXY_GRAPH_OBSERVATION_ORDER = GraphObservationOrder(listOf("investing", "cnbc", "yahoo")); its sourcePriority is also the
 *    source registry, so the list is written once.
 *  - dxyGraphSeriesIds(topic, catalog) = setOf("dxy") iff topic is exactly `dxy:spot` and the usd tab's 1d allSeries lists
 *    `dxy` (defaultVisible plays no part), else empty. F2 may reuse it as the continuity/loss target set.
 *  - dxyGraphObservations(scope, batch, catalog): per candidate in order, a DollarIndex whose source is in the order's list
 *    exactly (no case or space folding) and whose rate passes RateSanity becomes
 *    GraphObservation((scope, "dxy"), (source, "dxy", timestamp, rate)) - "dxy" as the asset is an internal instrument marker.
 *    Quotes never map. No dedupe, sorting, tip choice or time check: D1 and D3 do those.
 *  - Holding before the catalog is E1's GraphPendingInputs, unchanged; D3 recovery is series-agnostic. Neither is re-tested here.
 * DXY bucket high/low exist in the shared model but are not a display contract: conflicts are checked through identities and
 * outcomes, not ranges. Line, end point, axis and zero shading are C2's.
 * Not applicable: low > high (one rate per candidate), diagnostic log on/off (nothing logged). Parse failure is outside the
 * typed API (TopicFrameDecoderTest covers a missing index, not an invalid timestamp) and stays with S3/F2 integration.
 *
 * The implementation thread reads but does not edit this file.
 */
class DxyGraphObservationAdapterTest {

    private val scope = GraphDataScope("u1", "e1")
    private val otherScope = GraphDataScope("u2", "e2")
    private val suppliers = listOf("investing", "cnbc", "yahoo")

    private fun at(day: Int, hhmmss: String): Instant = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")
    private fun kst(hhmmss: String): Instant = at(7, hhmmss)

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val c = kst("20:10:00")

    private val grant = TopicGrantToken(1)
    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1), "e1", grant)
    private val attribution = TopicUseAttribution(Any(), fence, 1, TopicUseLifetime(grant, 0))

    private fun index(rate: Double, t: Instant, source: String) = TopicGraphCandidate.DollarIndex(rate, t, source)
    private fun batch(
        topic: String,
        candidates: List<TopicGraphCandidate>,
        sequence: Long = 1,
        path: TopicGraphPath = TopicGraphPath.WS,
        generation: Long? = 1
    ) = TopicGraphInput.Observations(sequence, topic, path, attribution, generation, candidates)
    private fun batch(topic: String, vararg candidates: TopicGraphCandidate) = batch(topic, candidates.toList())
    private fun spot(vararg candidates: TopicGraphCandidate) = batch("dxy:spot", candidates.toList())

    private fun tab(id: String, oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        GraphCatalogTab(id, id, emptyMap(), buildMap {
            if (oneDay != null) put(GraphPeriod.ONE_DAY, GraphCatalogPeriod(oneDay, visible ?: oneDay.take(3)))
            put(GraphPeriod.ONE_WEEK, GraphCatalogPeriod(oneWeek, oneWeek))
        })
    private val banks = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private val usdIds = banks.map { "$it.usd" } + "dxy" + "krx.usd-krw-futures"
    private val current = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to tab("usd", usdIds),
            "jpy" to tab("jpy", banks.map { "$it.jpy" }),
            "tether" to tab("tether", listOf("bithumb.usdt-krw", "investing.usd", "kb.usd", "hana.usd", "dxy", "dxy_futures"))
        )
    )
    private fun withUsd(tab: GraphCatalogTab) = current.copy(tabs = current.tabs + ("usd" to tab))

    private fun map(input: TopicGraphInput.Observations, catalog: GraphCatalog = current, s: GraphDataScope = scope) =
        dxyGraphObservations(s, input, catalog)
    private fun id(source: String, rate: Double, t: Instant) = GraphObservationId(source, "dxy", t, rate)
    private fun observation(source: String, rate: Double, t: Instant, s: GraphDataScope = scope) =
        GraphObservation(GraphObservationSeriesKey(s, "dxy"), id(source, rate, t))
    private fun state() = GraphRecoverableState.empty(GraphObservationSeriesKey(scope, "dxy"), DXY_GRAPH_OBSERVATION_ORDER)

    /** Maps each batch with E2 and applies it to the dxy series' D3 state in order. */
    private fun feed(s: GraphRecoverableState, now: Instant, vararg inputs: TopicGraphInput.Observations): GraphRecoverableReduction {
        var next = s
        val outcomes = mutableListOf<GraphObservationOutcome>()
        for (input in inputs) {
            val reduction = observeRecoverable(next, map(input), now)
            outcomes += reduction.outcomes
            next = reduction.state
        }
        return GraphRecoverableReduction(next, outcomes)
    }

    // --- mapping ------------------------------------------------------------------------------------------------------

    /** X01: each supplier maps to the one `dxy` series, keeping its source, time and price, keyed by the scope mapped for. */
    @Test fun X01_everySupplierMapsToTheOneDxySeries() {
        val t = kst("20:01:00")
        assertEquals(listOf("investing", "cnbc", "yahoo"), DXY_GRAPH_OBSERVATION_ORDER.sourcePriority)
        assertEquals(setOf("dxy"), dxyGraphSeriesIds("dxy:spot", current))
        for (source in suppliers) for (s in listOf(scope, otherScope)) {
            assertEquals("$source $s", listOf(observation(source, 105.1, t, s)), map(spot(index(105.1, t, source)), s = s))
        }
    }

    /**
     * X02: only the usd tab's 1d allSeries admits `dxy` - not a missing usd tab or 1d period, not the usd 1w list, not the
     * tether tab, not defaultVisible alone; when allSeries lists it, defaultVisible plays no part.
     */
    @Test fun X02_onlyTheUsdOneDayCatalogAdmitsDxy() {
        val t = kst("20:01:00")
        val input = spot(index(105.1, t, "investing"))
        val withoutDxy = usdIds - "dxy"
        val refused = mapOf(
            "no usd tab" to current.copy(tabs = current.tabs - "usd"),
            "no 1d period" to withUsd(tab("usd", oneDay = null, oneWeek = usdIds)),
            "not in 1d" to withUsd(tab("usd", withoutDxy)),
            "only in 1w" to withUsd(tab("usd", withoutDxy, oneWeek = listOf("dxy"))),
            "only in defaultVisible" to withUsd(tab("usd", withoutDxy, visible = listOf("dxy"))),
            "only in the tether tab" to withUsd(tab("usd", withoutDxy))
        )
        for ((label, catalog) in refused) {
            assertEquals(label, emptyList<GraphObservation>(), map(input, catalog))
            assertTrue(label, dxyGraphSeriesIds("dxy:spot", catalog).isEmpty())
        }
        val defaultOff = withUsd(tab("usd", usdIds, visible = listOf("investing.usd")))
        assertEquals(listOf(observation("investing", 105.1, t)), map(input, defaultOff))
        assertEquals(setOf("dxy"), dxyGraphSeriesIds("dxy:spot", defaultOff))
    }

    /**
     * X03: only `dxy:spot` maps; quotes never become `dxy`; an unlisted, empty, upper-case or padded source and an implausible
     * price drop one by one while the plausible neighbours keep their order; an empty batch is empty.
     */
    @Test fun X03_onlyListedSuppliersOnTheSpotTopicMap() {
        val t = kst("20:01:00")
        for (topic in listOf("fx:usd-krw", "usdt:krw", "dxy:futures", "dxy:spot ")) {
            assertEquals(topic, emptyList<GraphObservation>(), map(batch(topic, index(105.1, t, "investing"))))
            assertTrue(topic, dxyGraphSeriesIds(topic, current).isEmpty())
        }
        val mixed = spot(
            TopicGraphCandidate.Quote("kb", "usd-krw", 1342.0, t, null),
            index(105.1, t, "investing"),
            TopicGraphCandidate.Quote("krx", "usd-krw-futures", 1342.0, t, null),
            index(105.2, t, "unknown"),
            index(105.3, t, ""),
            index(105.4, t, "INVESTING"),
            index(105.5, t, " investing"),
            index(Double.NaN, t, "cnbc"),
            index(Double.POSITIVE_INFINITY, t, "cnbc"),
            index(Double.NEGATIVE_INFINITY, t, "cnbc"),
            index(0.0, t, "cnbc"),
            index(-1.0, t, "cnbc"),
            index(1e9, t, "cnbc"),
            index(104.9, t, "yahoo")
        )
        assertEquals(listOf(observation("investing", 105.1, t), observation("yahoo", 104.9, t)), map(mixed))
        assertTrue(map(spot()).isEmpty())
    }

    // --- tip and identity through D1-D3 -------------------------------------------------------------------------------

    /**
     * X04 (T20): investing 19:59 by WS, the same by REST, cnbc 20:01, a late yahoo 19:59 and cnbc again - each keeps its own
     * time and source, so the outcomes are ADDED, DUPLICATE, ADDED, ADDED, DUPLICATE; nothing old lands in B; A's close stays
     * investing (same time, higher rank) and the tip is cnbc 20:01.
     */
    @Test fun X04_T20_eachRecordKeepsItsTimeAndSource() {
        val t59 = kst("19:59:00")
        val t01 = kst("20:01:00")
        val r = feed(
            state(), kst("20:02:00"),
            batch("dxy:spot", listOf(index(105.5, t59, "investing")), sequence = 1),
            batch("dxy:spot", listOf(index(105.5, t59, "investing")), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null),
            batch("dxy:spot", listOf(index(105.1, t01, "cnbc")), sequence = 3),
            batch("dxy:spot", listOf(index(105.5, t59, "yahoo")), sequence = 4),
            batch("dxy:spot", listOf(index(105.1, t01, "cnbc")), sequence = 5)
        )
        assertEquals(listOf(ADDED, DUPLICATE, ADDED, ADDED, DUPLICATE), r.outcomes)
        assertEquals(setOf(a, b), r.state.data.app.buckets.keys)
        assertEquals(id("cnbc", 105.1, t01), r.state.data.app.buckets.getValue(b).close)
        assertEquals(id("investing", 105.5, t59), r.state.data.app.buckets.getValue(a).close)
        assertEquals(id("cnbc", 105.1, t01), r.state.data.app.tip)
    }

    /** X05: a later timestamp wins over a higher-ranked source in either arrival order, and the earlier record is kept. */
    @Test fun X05_timestampComesBeforeSourceRank() {
        val investing = batch("dxy:spot", listOf(index(105.4, kst("20:01:00"), "investing")), sequence = 1)
        val yahoo = batch("dxy:spot", listOf(index(105.1, kst("20:01:01"), "yahoo")), sequence = 2)
        for ((label, order) in listOf("investing first" to listOf(investing, yahoo), "yahoo first" to listOf(yahoo, investing))) {
            val s = feed(state(), kst("20:02:00"), *order.toTypedArray()).state
            assertEquals(label, id("yahoo", 105.1, kst("20:01:01")), s.data.app.tip)
            assertEquals(label, id("yahoo", 105.1, kst("20:01:01")), s.data.app.buckets.getValue(b).close)
            assertEquals(label, setOf(id("investing", 105.4, kst("20:01:00")), id("yahoo", 105.1, kst("20:01:01"))), s.data.app.observations)
        }
    }

    /**
     * X06: at the same time the higher-ranked supplier is close and tip whatever the arrival order - investing over cnbc, cnbc
     * over yahoo, investing over yahoo - with equal or different prices; different suppliers are separate observations.
     */
    @Test fun X06_atTheSameTimeTheRankDecides() {
        val t = kst("20:01:00")
        for ((winner, loser) in listOf("investing" to "cnbc", "cnbc" to "yahoo", "investing" to "yahoo")) {
            for ((winnerRate, loserRate) in listOf(105.1 to 105.1, 105.1 to 105.4)) {
                val w = batch("dxy:spot", listOf(index(winnerRate, t, winner)), sequence = 1)
                val l = batch("dxy:spot", listOf(index(loserRate, t, loser)), sequence = 2)
                for (order in listOf(listOf(w, l), listOf(l, w))) {
                    val label = "$winner>$loser $winnerRate/$loserRate ${order.first().sequence}"
                    val r = feed(state(), kst("20:02:00"), *order.toTypedArray())
                    assertEquals(label, listOf(ADDED, ADDED), r.outcomes)
                    assertEquals(label, id(winner, winnerRate, t), r.state.data.app.tip)
                    assertEquals(label, id(winner, winnerRate, t), r.state.data.app.buckets.getValue(b).close)
                }
            }
        }
    }

    /**
     * X07: a different price at the same supplier and time is a conflict kept as an identity, not deleted; close and tip stay
     * with the first price in each order, and repeats of either price are duplicates.
     */
    @Test fun X07_aSameTimeConflictKeepsTheFirstTip() {
        val t = kst("20:01:00")
        for ((first, second) in listOf(105.1 to 105.4, 105.4 to 105.1)) {
            val r = feed(state(), kst("20:02:00"), spot(index(first, t, "cnbc"), index(second, t, "cnbc"), index(second, t, "cnbc"), index(first, t, "cnbc")))
            assertEquals("$first", listOf(ADDED, SAME_TIME_CONFLICT, DUPLICATE, DUPLICATE), r.outcomes)
            assertEquals("$first", setOf(id("cnbc", first, t), id("cnbc", second, t)), r.state.data.app.observations)
            assertEquals("$first", id("cnbc", first, t), r.state.data.app.tip)
            assertEquals("$first", id("cnbc", first, t), r.state.data.app.buckets.getValue(b).close)
        }
    }

    /**
     * X08: times are kept to the microsecond and placed by them (19:59:59.999 in A, 20:00:00 in B); the same price a
     * millisecond apart is two observations; the same record by WS, REST and a later connection is one identity.
     */
    @Test fun X08_precisionAndRedelivery() {
        val micro = kst("20:01:00.000250")
        assertEquals(listOf(observation("investing", 105.123456, micro)), map(spot(index(105.123456, micro, "investing"))))

        val edges = feed(state(), kst("20:02:00"), spot(index(105.1, kst("19:59:59.999"), "investing"), index(105.2, kst("20:00:00"), "investing")))
        assertEquals(id("investing", 105.1, kst("19:59:59.999")), edges.state.data.app.buckets.getValue(a).close)
        assertEquals(id("investing", 105.2, kst("20:00:00")), edges.state.data.app.buckets.getValue(b).close)

        val close = feed(state(), kst("20:02:00"), spot(index(105.1, kst("20:01:00.001"), "cnbc"), index(105.1, kst("20:01:00.002"), "cnbc")))
        assertEquals(listOf(ADDED, ADDED), close.outcomes)

        val record = index(105.1, kst("20:01:00"), "yahoo")
        val ws = batch("dxy:spot", listOf(record), sequence = 1, path = TopicGraphPath.WS, generation = 1)
        val rest = batch("dxy:spot", listOf(record), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        val later = batch("dxy:spot", listOf(record), sequence = 3, path = TopicGraphPath.WS, generation = 2)
        assertEquals(map(ws), map(rest))
        assertEquals(map(ws), map(later))
        assertEquals(listOf(ADDED, DUPLICATE, DUPLICATE), feed(state(), kst("20:02:00"), ws, rest, later).outcomes)
    }

    /**
     * X09 (E2 -> D3): at device 20:07, 20:10:00 enters C while 20:20:00 is rejected with TIME_ANOMALY on B, its time
     * uncorrected; a record exactly at the data bound L (10/06 19:00) is kept and one a slot older is rejected without a demand.
     */
    @Test fun X09_mappedTimesAreJudgedByD3() {
        val now = kst("20:07:00")
        val future = spot(index(105.1, c, "investing"), index(105.2, kst("20:20:00"), "investing"))
        assertEquals(kst("20:20:00"), map(future)[1].id.observedAt)
        val r = feed(state(), now, future)
        assertEquals(listOf(ADDED, REJECTED), r.outcomes)
        assertEquals(setOf(c), r.state.data.app.buckets.keys)
        assertEquals(mapOf(b to setOf(TIME_ANOMALY)), r.state.pending.mapValues { it.value.reasons })

        val edge = feed(state(), now, spot(index(104.8, at(6, "19:00:00"), "investing"), index(104.7, at(6, "18:50:00"), "investing")))
        assertEquals(listOf(ADDED, REJECTED), edge.outcomes)
        assertEquals(setOf(at(6, "19:00:00")), edge.state.data.app.buckets.keys)
        assertTrue(edge.state.pending.isEmpty())
    }
}
