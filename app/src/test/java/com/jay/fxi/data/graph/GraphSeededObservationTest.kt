package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 D2 contract r1 (JVM): server seeds and server-closed buckets applied beside the app's own observations (D1),
 * kept apart and combined only when a bucket is composed for display.
 *
 * Oracles: ANDROID_V2_PLAN.md :1305-1313 (server range and validated app observations kept apart per (scope, series, bucket
 * start) and combined only for display; one seed-merge rule for storing and rendering; a seed folds only into the bucket with
 * start == floor(now/600)*600; same-bucket seeds keep the union of their ranges; a newer seed or a later sampled_at never
 * removes a server extreme or a valid app observation; a past app bucket keeps its server range and observations until REST
 * provides it; a closed bucket the server provided is never re-composed with later app observations; another bucket's absence
 * is not a replacement), :1311 (close = the bucket's own latest app observation, else the adopted seed close); iOS 89e866d
 * GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :254-258, :274, :282-285 (T06, T07, T08, T10, T26 and the -L variants).
 * Design: R4c/S4 d2_design_codex.r1 as reviewed by d2_review_claude.r1 after a four-lens verification (d2_contract.r1/
 * design_verify_workflow.result.json): no SERVER_CLOSED pre-filter - a late observation still reaches D1, so the tip does not
 * depend on arrival order, and only composition puts the closed record first; a closed point is the server's word - on the
 * 600-second grid exactly, with no device-clock guard, so a slightly slow device clock cannot discard it; the device `now` only
 * decides seed folding; a closed record is never reopened by a seed; with repeated seeds and no app observation the close is the
 * last adopted seed's (iOS a36682f GraphV2PreparedModel.swift:391 decides by the series tip instead - deliberately not followed);
 * applications run in call order - keeping an older response from being applied after a newer one is the recorder's job (plan
 * :1316), not D2's. T08's pending-request wiring belongs to the caller; at this level it is the same rule as T06.
 *
 * r2 (battery r1 survivor N03): E09 also hands a 1d tab with bucketSize "5min" - the server's 1d grid is "10min"
 * (graph_v2_intraday.py:436); a changed size would let points that happen to sit on the 600-second grid pass as closed buckets.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphSeededObservationTest {

    private val scope = GraphDataScope("u1", "e1")
    private val seriesId = "kb.usd"
    private val key = GraphObservationSeriesKey(scope, seriesId)
    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-06T${hhmmss}+09:00")
    private val b1920 = kst("19:20:00")
    private val b1930 = kst("19:30:00")
    private val b1940 = kst("19:40:00")
    private val b1950 = kst("19:50:00")
    private val b2000 = kst("20:00:00")
    private val b2010 = kst("20:10:00")

    private fun id(at: Instant, rate: Double) = GraphObservationId("kb", "usd-krw", at, rate)
    private fun obs(at: Instant, rate: Double) = GraphObservation(key, id(at, rate))
    private fun seed(start: Instant, low: Double, high: Double, close: Double, sampled: Instant = start) =
        GraphV2InProgress(start, high, low, close, sampled)
    private fun point(at: Instant, rate: Double, low: Double? = rate, high: Double? = rate) = FreeGraphPoint(at, rate, high, low)

    private fun tab(
        points: List<FreeGraphPoint> = emptyList(),
        seed: GraphV2InProgress? = null,
        fetchedAt: Instant = kst("20:05:00"),
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        seriesPresent: Boolean = true
    ) = GraphV2Tab(
        tab = "usd", period = period, bucketSize = "10min", fetchedAt = fetchedAt,
        graph = FreeGraph("10min", if (seriesPresent) listOf(FreeGraphSeries(seriesId, points)) else emptyList()),
        inProgress = if (seed != null && seriesPresent) mapOf(seriesId to seed) else emptyMap()
    )

    private fun empty() = GraphSeededObservationState.empty(key, GraphObservationOrder(emptyList()))
    private fun apply(state: GraphSeededObservationState, t: GraphV2Tab, now: Instant) = applyGraphServerTab(state, scope, t, now)
    private fun observe(state: GraphSeededObservationState, vararg o: GraphObservation) =
        reduceSeededGraphObservations(state, o.toList()).state
    private fun unreplaced(state: GraphSeededObservationState, start: Instant) =
        composeGraphBucket(state, start) as GraphComposedBucket.Unreplaced
    private fun rangeOf(state: GraphSeededObservationState, start: Instant) =
        unreplaced(state, start).range.let { it.low to it.high }

    // --- seeds beside app observations (T06, T07, T08) -----------------------------------------------------------------

    /** E01 (T06, T08): an app high above a later seed survives it and stays the close; the app observation is kept. */
    @Test fun E01_aSeedBelowAnAppHighKeepsTheAppHighAndClose() {
        var s = observe(empty(), obs(kst("20:01:00"), 1342.1))
        s = apply(s, tab(seed = seed(b2000, 1341.7, 1341.9, 1341.8)), kst("20:05:00"))
        assertEquals(1341.7 to 1342.1, rangeOf(s, b2000))
        assertEquals(1342.1, unreplaced(s, b2000).close, 0.0)
        assertEquals(setOf(id(kst("20:01:00"), 1342.1)), s.app.observations)
    }

    /** E02 (T06-L, T08-L): an app low below a later seed survives it and stays the close. */
    @Test fun E02_aSeedAboveAnAppLowKeepsTheAppLowAndClose() {
        var s = observe(empty(), obs(kst("20:01:00"), 1341.5))
        s = apply(s, tab(seed = seed(b2000, 1341.7, 1341.9, 1341.8)), kst("20:05:00"))
        assertEquals(1341.5 to 1341.9, rangeOf(s, b2000))
        assertEquals(1341.5, unreplaced(s, b2000).close, 0.0)
    }

    /** E03 (T07) and E04 (T07-L): a flat seed's high, or low, is kept as range once an app observation arrives. */
    @Test fun E03_E04_aFlatSeedIsKeptAsRange() {
        for ((flat, expected) in listOf(1346.4 to (1342.3 to 1346.4), 1338.2 to (1338.2 to 1342.3))) {
            var s = apply(empty(), tab(seed = seed(b2000, flat, flat, flat)), kst("20:02:00"))
            s = observe(s, obs(kst("20:04:00"), 1342.3))
            assertEquals("flat $flat", expected, rangeOf(s, b2000))
            assertEquals("flat $flat", 1342.3, unreplaced(s, b2000).close, 0.0)
        }
    }

    // --- closed buckets (T10, T26) ---------------------------------------------------------------------------------------

    /**
     * E05 (T10) and E06 (T10-L): once the server provides B closed, B composes as that point whatever app observation came
     * before or after it - a later high or a later low changes nothing shown.
     */
    @Test fun E05_E06_aClosedBucketIsNeverRecomposed() {
        val closed = point(b2000, 1341.8, 1341.7, 1341.9)
        for ((before, late) in listOf(1342.1 to 1343.0, 1341.5 to 1337.0)) {
            var s = observe(empty(), obs(kst("20:01:00"), before))
            s = apply(s, tab(points = listOf(closed), fetchedAt = kst("20:10:30")), kst("20:10:30"))
            assertEquals("$before", GraphComposedBucket.Closed(closed), composeGraphBucket(s, b2000))
            val r = reduceSeededGraphObservations(s, listOf(obs(kst("20:09:00"), late)))
            assertEquals("$late still reaches the app observations", listOf(GraphObservationOutcome.ADDED), r.outcomes)
            assertEquals("$late", GraphComposedBucket.Closed(closed), composeGraphBucket(r.state, b2000))
        }
    }

    /**
     * E07 (T26): an unreplaced B keeps its server range and app observations through a response that leaves B out (and one
     * without the series at all); only a response that provides B closes it.
     */
    @Test fun E07_onlyAProvidedBucketIsReplaced() {
        var s = apply(empty(), tab(seed = seed(b2000, 1346.4, 1346.4, 1346.4)), kst("20:05:00"))
        s = observe(s, obs(kst("20:04:00"), 1342.3))
        assertEquals(1342.3 to 1346.4, rangeOf(s, b2000))
        val c10 = point(b2010, 1342.0, 1341.8, 1342.2)
        s = apply(s, tab(points = listOf(c10), fetchedAt = kst("20:22:00")), kst("20:22:00"))
        assertEquals("B left out of the response", 1342.3 to 1346.4, rangeOf(s, b2000))
        assertEquals(GraphComposedBucket.Closed(c10), composeGraphBucket(s, b2010))
        val beforeAbsent = s.serverBuckets
        s = apply(s, tab(seriesPresent = false, fetchedAt = kst("20:23:00")), kst("20:23:00"))
        assertEquals("a response without the series changes nothing", beforeAbsent, s.serverBuckets)
        assertEquals(1342.3 to 1346.4, rangeOf(s, b2000))
        val cB = point(b2000, 1342.3, 1342.3, 1342.3)
        s = apply(s, tab(points = listOf(cB), fetchedAt = kst("20:24:00")), kst("20:24:00"))
        assertEquals("provided B closes", GraphComposedBucket.Closed(cB), composeGraphBucket(s, b2000))
    }

    // --- repeated seeds and the current bucket -------------------------------------------------------------------------------

    /**
     * E08: repeated seeds keep the union of their ranges in the server record, apart from the app observation; the composed
     * bucket is their union with the app's close; the same seeds in the other order store the same range.
     */
    @Test fun E08_repeatedSeedsUnionApartFromTheAppObservation() {
        val s1 = seed(b2000, 1340.0, 1344.0, 1341.0, kst("20:02:00"))
        val s2 = seed(b2000, 1341.0, 1343.0, 1342.0, kst("20:04:00"))
        var s = apply(empty(), tab(seed = s1), kst("20:05:00"))
        s = observe(s, obs(kst("20:03:00"), 1345.0))
        s = apply(s, tab(seed = s2), kst("20:06:00"))
        s = apply(s, tab(seed = s2), kst("20:07:00"))
        val stored = s.serverBuckets.getValue(b2000) as GraphServerBucket.Seeds
        assertEquals("the server record keeps the seeds' union", 1340.0 to 1344.0, stored.range.low to stored.range.high)
        assertEquals("the app record keeps the app observation", 1345.0 to 1345.0,
            s.app.buckets.getValue(b2000).let { it.low.rate to it.high.rate })
        assertEquals(1340.0 to 1345.0, rangeOf(s, b2000))
        assertEquals(1345.0, unreplaced(s, b2000).close, 0.0)

        var r = apply(empty(), tab(seed = s2), kst("20:05:00"))
        r = apply(r, tab(seed = s1), kst("20:06:00"))
        val reversed = r.serverBuckets.getValue(b2000) as GraphServerBucket.Seeds
        assertEquals(1340.0 to 1344.0, reversed.range.low to reversed.range.high)
    }

    /**
     * E09: a seed folds only when its start is exactly the bucket of the device `now` given to the call - not of the response's
     * fetchedAt or sampledAt; other starts make no record; a folded seed stays after the clock moves on; another scope or a
     * period other than 1d, or a 1d tab whose bucket size is not the 600-second grid, changes nothing.
     */
    @Test fun E09_aSeedFoldsOnlyIntoTheCurrentBucketOfNow() {
        assertTrue(apply(empty(), tab(seed = seed(b2000, 1341.0, 1342.0, 1341.5)), kst("20:09:59.999"))
            .serverBuckets[b2000] is GraphServerBucket.Seeds)
        assertNull(apply(empty(), tab(seed = seed(b2000, 1341.0, 1342.0, 1341.5)), kst("20:10:00")).serverBuckets[b2000])
        for (start in listOf(b1950, b2010, kst("20:00:00.001"))) {
            assertTrue("$start", apply(empty(), tab(seed = seed(start, 1341.0, 1342.0, 1341.5)), kst("20:05:00")).serverBuckets.isEmpty())
        }
        val bySampledFuture = tab(seed = seed(b2000, 1341.0, 1342.0, 1341.5, kst("20:10:00.500")), fetchedAt = kst("20:10:01"))
        assertTrue("now decides, not fetchedAt", apply(empty(), bySampledFuture, kst("20:09:59.999")).serverBuckets[b2000] is GraphServerBucket.Seeds)
        val bySampledPast = tab(seed = seed(b2000, 1341.0, 1342.0, 1341.5, kst("20:09:58")), fetchedAt = kst("20:09:59"))
        assertNull("now decides, not fetchedAt", apply(empty(), bySampledPast, kst("20:10:00")).serverBuckets[b2000])

        var s = apply(empty(), tab(seed = seed(b2000, 1341.0, 1342.0, 1341.5)), kst("20:05:00"))
        s = apply(s, tab(fetchedAt = kst("20:15:00")), kst("20:15:00"))
        assertEquals("a folded seed stays after the clock moves on", 1341.0 to 1342.0, rangeOf(s, b2000))

        val before = s
        val other = applyGraphServerTab(s, GraphDataScope("u2", "e1"), tab(seed = seed(b2010, 1.0, 2.0, 1.5)), kst("20:15:00"))
        assertEquals("another scope", before.serverBuckets, other.serverBuckets)
        val week = apply(s, tab(points = listOf(point(b2010, 1342.0)), period = GraphPeriod.ONE_WEEK, fetchedAt = kst("20:16:00")), kst("20:16:00"))
        assertEquals("not 1d", before.serverBuckets, week.serverBuckets)
        val fiveMinutes = apply(s, tab(points = listOf(point(b2010, 1342.0)), fetchedAt = kst("20:16:00")).copy(bucketSize = "5min"), kst("20:16:00"))
        assertEquals("1d with a bucket size other than the 600-second grid", before.serverBuckets, fiveMinutes.serverBuckets)
        val tenMinutes = apply(s, tab(points = listOf(point(b2010, 1342.0)), fetchedAt = kst("20:16:00")), kst("20:16:00"))
        assertTrue("premise: the same tab at 10min is applied", tenMinutes.serverBuckets[b2010] is GraphServerBucket.Closed)
    }

    /**
     * E10: with no app observation in B, repeated seeds close at the last adopted seed - not the highest close, not the first,
     * not the latest sampled_at - even when an earlier bucket has an app observation; once B has its own observations the close
     * is B's latest observation time.
     */
    @Test fun E10_aSeedOnlyBucketClosesAtTheLastAdoptedSeed() {
        val first = seed(b2000, 1341.5, 1342.0, 1341.9, kst("20:04:00"))
        val second = seed(b2000, 1341.7, 1341.9, 1341.8, kst("20:02:00"))
        for (withEarlierApp in listOf(false, true)) {
            var s = if (withEarlierApp) observe(empty(), obs(kst("19:59:00"), 1350.0)) else empty()
            s = apply(s, tab(seed = first), kst("20:05:00"))
            s = apply(s, tab(seed = second), kst("20:06:00"))
            assertEquals("earlier app $withEarlierApp", 1341.8, unreplaced(s, b2000).close, 0.0)
            assertEquals(1341.5 to 1342.0, rangeOf(s, b2000))
            s = observe(s, obs(kst("20:01:00"), 1342.1), obs(kst("20:03:00"), 1341.6), obs(kst("20:02:00"), 1343.0))
            assertEquals("B's own latest observation", 1341.6, unreplaced(s, b2000).close, 0.0)
            assertEquals(1341.5 to 1343.0, rangeOf(s, b2000))
        }
    }

    /**
     * E11: a closed point must sit exactly on the grid, else it records nothing; a closed record is never reopened by a seed,
     * even under a clock that has gone back into B; a closed point counts though the device clock is still inside B, and wins
     * over a seed for B in the same response; a later response that provides B again replaces it.
     */
    @Test fun E11_closedPointsAreTheServersWordOnTheGridOnly() {
        var s = observe(empty(), obs(kst("19:52:00"), 1341.5), obs(kst("19:54:00"), 1342.1))
        s = apply(s, tab(points = listOf(point(kst("19:55:00"), 1338.0), point(kst("19:50:00.500"), 1339.0))), kst("20:05:00"))
        assertTrue("off the grid records nothing", s.serverBuckets.isEmpty())
        assertEquals(1341.5 to 1342.1, rangeOf(s, b1950))

        val closed = point(b2000, 1341.8, 1341.7, 1341.9)
        var c = apply(empty(), tab(points = listOf(closed), fetchedAt = kst("20:10:30")), kst("20:10:30"))
        c = apply(c, tab(seed = seed(b2000, 1340.0, 1345.0, 1344.0), fetchedAt = kst("20:05:00")), kst("20:05:00"))
        assertEquals("a seed never reopens a closed bucket", GraphComposedBucket.Closed(closed), composeGraphBucket(c, b2000))

        val slow = apply(empty(), tab(points = listOf(closed), seed = seed(b2000, 1340.0, 1345.0, 1344.0),
            fetchedAt = kst("20:10:02")), kst("20:09:58"))
        assertEquals("a slow device clock still takes the closed point", GraphComposedBucket.Closed(closed), composeGraphBucket(slow, b2000))

        val again = point(b2000, 1341.9, 1341.7, 1342.0)
        val replaced = apply(c, tab(points = listOf(again), fetchedAt = kst("20:22:00")), kst("20:22:00"))
        assertEquals("the later provided value replaces", GraphComposedBucket.Closed(again), composeGraphBucket(replaced, b2000))
    }

    /** E12: the series tip does not depend on whether a late observation arrived before or after B was closed. */
    @Test fun E12_theTipDoesNotDependOnArrivalOrder() {
        val base = observe(empty(), obs(kst("20:09:50"), 1342.0))
        val closedTab = tab(points = listOf(point(b2000, 1341.8, 1341.7, 1341.9)), fetchedAt = kst("20:10:30"))
        val late = obs(kst("20:09:59"), 1343.0)
        val x = apply(observe(base, late), closedTab, kst("20:10:30"))
        val y = observe(apply(base, closedTab, kst("20:10:30")), late)
        assertEquals(id(kst("20:09:59"), 1343.0), x.app.tip)
        assertEquals(x.app.tip, y.app.tip)
        assertEquals(composeGraphBucket(x, b2000), composeGraphBucket(y, b2000))
    }

    /** E13: every kind of bucket composes as itself - closed, app only, seed only, both, and nothing at all. */
    @Test fun E13_eachKindOfBucketComposesAsItself() {
        val c1930 = point(b1930, 1338.0, 1337.5, 1338.5)
        var s = apply(empty(), tab(points = listOf(c1930), fetchedAt = kst("19:55:00")), kst("19:55:00"))
        s = observe(s, obs(kst("19:41:00"), 1340.0), obs(kst("19:43:00"), 1339.5))
        s = apply(s, tab(seed = seed(b1950, 1339.0, 1339.8, 1339.2), fetchedAt = kst("19:55:00")), kst("19:55:00"))
        s = apply(s, tab(seed = seed(b2000, 1341.7, 1341.9, 1341.8)), kst("20:05:00"))
        s = observe(s, obs(kst("20:01:00"), 1342.1))

        assertEquals(GraphComposedBucket.Closed(c1930), composeGraphBucket(s, b1930))
        assertEquals("app only", GraphComposedBucket.Unreplaced(b1940, GraphBucketRange(high = 1340.0, low = 1339.5), 1339.5),
            composeGraphBucket(s, b1940))
        assertEquals("seed only", GraphComposedBucket.Unreplaced(b1950, GraphBucketRange(high = 1339.8, low = 1339.0), 1339.2),
            composeGraphBucket(s, b1950))
        assertEquals("both", GraphComposedBucket.Unreplaced(b2000, GraphBucketRange(high = 1342.1, low = 1341.7), 1342.1),
            composeGraphBucket(s, b2000))
        assertNull("nothing", composeGraphBucket(s, b1920))
    }
}
