package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphObservationOutcome.ADDED
import com.jay.fxi.data.graph.GraphObservationOutcome.DUPLICATE
import com.jay.fxi.data.graph.GraphObservationOutcome.REJECTED
import com.jay.fxi.data.graph.GraphObservationOutcome.SAME_TIME_CONFLICT
import com.jay.fxi.data.graph.GraphRecoveryReason.HANDOVER_LOSS
import com.jay.fxi.data.graph.GraphRecoveryReason.RECEIVE_GAP
import com.jay.fxi.data.graph.GraphRecoveryReason.TIME_ANOMALY
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphOffer
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.TopicSourceEntry
import com.jay.fxi.data.remote.dto.toQuote
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.domain.model.TopicQuoteKey
import com.jay.fxi.domain.model.TopicRates
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-1 (JVM): the tether topic's graph observations — the five exchanges and the USD reference rows it carries — and their
 * hand-over into the recorder.
 *
 * Oracles: ANDROID_V2_PLAN.md :1324-1327 (a bucket is assigned by the observation's server time; the two-time sources keep
 * `(price, change time)` and `(price, re-observation time)` each as a bucket candidate, banks and investing use `timestamp` alone
 * whatever topic carried them), :1345 (evidence dropped for a time anomaly still updates recovery), :1389-1393 (the common test
 * contract per adapter), :1517-1523 (S5 DoD); iOS 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :88, :97, :101, :103 (two
 * candidates, a change time a few seconds after the floored `seen_at` is normal, legacy/fallback duplicates) and :249-286 (T01-T23
 * and the low-symmetric rows, original inputs and expectations); server usdt_topic_payload.py (the tether topic carries
 * `usdt_krw`, `usd_krw_banks` and `usd_krw_reference`), latest_rates_cache.py:495-517 (`timestamp` = floor5(tick), a new price's
 * change time is its tick, the same price keeps it), :216-217/:240 and crud.py:3003 (old schema and DB fallback copy one time
 * into both fields). Agreed rules: R4c/S5 s5_1_rule_correction.r2.md (R1-R5).
 *
 * Here, each exchange through the adapter: T01-T04, T06-T08 and T10 with their low rows, T11 (capture and unanswered part),
 * T12 (recorder part), T14, T15, a boundary crossed during a request, T19 and T23 (tether parts), input before the catalog, the
 * holding bound, future times, and the recorder and sink parts of an asynchronous purge (the registered recovery captures are
 * GraphV2RecoveryRequestContractTest R6t). S5-2 applies per exchange, through the tether holder: T05/T05-L, T09, T16/T16-L and
 * T21 (publication and projection), T11 (failure, cancellation, cache, cooldown, joining, hand-over), T12 (ending retries,
 * per-screen access), and the runtime cleanup after a purge (screen publication, request release, consumer access); T22 per
 * shared non-KRX series. KRX is S6.
 */
class TetherGraphObservationAdapterTest {

    private val scope = GraphDataScope("u1", "e1")
    private val otherScope = GraphDataScope("u2", "e2")
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val references = listOf("investing", "kb", "hana")

    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-06T$hhmmss+09:00")

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val now = kst("20:02:00")

    private fun exchangeId(source: String) = "$source.usdt-krw"
    private val exchangeIds = exchanges.map(::exchangeId)
    private val referenceIds = references.map { "$it.usd" }
    private val tetherOneDay = exchangeIds + "krx.usd-krw-futures" + referenceIds + "dxy" + "dxy_futures"
    private val usdOneDay = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs").map { "$it.usd" } + "dxy"

    private fun tab(id: String, oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        GraphCatalogTab(id, id, emptyMap(), buildMap {
            if (oneDay != null) put(GraphPeriod.ONE_DAY, GraphCatalogPeriod(oneDay, visible ?: oneDay.take(3)))
            put(GraphPeriod.ONE_WEEK, GraphCatalogPeriod(oneWeek, oneWeek))
        })
    private val current = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to tab("usd", usdOneDay),
            "jpy" to tab("jpy", listOf("investing.jpy", "hana.jpy")),
            "eur" to tab("eur", listOf("investing.eur", "hana.eur")),
            "tether" to tab("tether", tetherOneDay)
        )
    )
    private fun withTether(oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        current.copy(tabs = current.tabs + ("tether" to tab("tether", oneDay, oneWeek, visible)))

    private val grant = TopicGrantToken(7L)
    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", grant)
    private val lifetime = TopicUseLifetime(grant, 3L)
    private val attribution = TopicUseAttribution(Any(), fence, 1L, lifetime)

    private fun quote(source: String, asset: String, rate: Double, t: Instant, changed: Instant? = null) =
        TopicGraphCandidate.Quote(source, asset, rate, t, changed)
    private fun ex(source: String, rate: Double, t: Instant, changed: Instant? = null) = quote(source, "usdt-krw", rate, t, changed)
    private fun usd(source: String, rate: Double, t: Instant) = quote(source, "usd-krw", rate, t)

    private fun batch(
        candidates: List<TopicGraphCandidate>,
        topic: String = "usdt:krw",
        sequence: Long = 1,
        path: TopicGraphPath = TopicGraphPath.WS,
        generation: Long? = 1
    ) = TopicGraphInput.Observations(sequence, topic, path, attribution, generation, candidates)
    private fun batch(vararg candidates: TopicGraphCandidate) = batch(candidates.toList())

    private fun map(input: TopicGraphInput.Observations, catalog: GraphCatalog = current, s: GraphDataScope = scope) =
        tetherGraphMapping(s, input, catalog)
    private fun key(id: String, s: GraphDataScope = scope) = GraphObservationSeriesKey(s, id)
    private fun id(source: String, asset: String, rate: Double, t: Instant) = GraphObservationId(source, asset, t, rate)
    private fun exObs(source: String, rate: Double, t: Instant, s: GraphDataScope = scope) =
        GraphObservation(key(exchangeId(source), s), id(source, "usdt-krw", rate, t))
    private fun usdObs(source: String, rate: Double, t: Instant, s: GraphDataScope = scope) =
        GraphObservation(key("$source.usd", s), id(source, "usd-krw", rate, t))

    private fun state(seriesId: String) = GraphRecoverableState.empty(key(seriesId), GraphObservationOrder(emptyList()))

    /** Maps each batch and applies only [seriesId]'s observations to its D3 state, so the outcomes are that series' alone. */
    private fun feed(s: GraphRecoverableState, at: Instant, vararg inputs: TopicGraphInput.Observations): GraphRecoverableReduction {
        var next = s
        val outcomes = mutableListOf<GraphObservationOutcome>()
        for (input in inputs) {
            val mine = map(input).observations.filter { it.seriesKey == s.data.app.seriesKey }
            val reduction = observeRecoverable(next, mine, at)
            outcomes += reduction.outcomes
            next = reduction.state
        }
        return GraphRecoverableReduction(next, outcomes)
    }
    private fun range(s: GraphRecoverableState, start: Instant) =
        s.data.app.buckets.getValue(start).let { it.low.rate to it.high.rate }

    // --- mapping ------------------------------------------------------------------------------------------------------

    /** N01 (R1): each exchange maps to its exact id, keyed by the scope; the two times come out in time order, precision kept. */
    @Test fun N01_everyExchangeMapsBothTimesInTimeOrder() {
        val t = kst("20:01:00")
        val later = kst("20:01:02.250250")
        val earlier = kst("19:58:03.500000")
        for (source in exchanges) {
            val forward = map(batch(ex(source, 1342.0, t, later)))
            assertEquals(source, listOf(exObs(source, 1342.0, t), exObs(source, 1342.0, later)), forward.observations)
            assertTrue(source, forward.anomalies.isEmpty())
            assertEquals(
                "$source other scope", listOf(exObs(source, 1342.0, t, otherScope), exObs(source, 1342.0, later, otherScope)),
                map(batch(ex(source, 1342.0, t, later)), s = otherScope).observations
            )
            assertEquals(
                "$source earlier change", listOf(exObs(source, 1342.0, earlier), exObs(source, 1342.0, t)),
                map(batch(ex(source, 1342.0, t, earlier))).observations
            )
        }
    }

    /** N02 (R4): the tether topic's reference rows map to `<source>.usd` by `timestamp` alone, whatever change time they carry. */
    @Test fun N02_referenceRowsUseTheirTimestampAlone() {
        val t = kst("20:01:00")
        for (source in references) for (changed in listOf(null, kst("19:59:00"), kst("20:01:03"), kst("21:00:00"))) {
            val mapped = map(batch(quote(source, "usd-krw", 1342.0, t, changed)))
            assertEquals("$source $changed", listOf(usdObs(source, 1342.0, t)), mapped.observations)
            assertTrue("$source $changed", mapped.anomalies.isEmpty())
        }
    }

    /**
     * N03: only the tether tab's 1d allSeries admits - a missing tab, a missing 1d, an id absent from 1d or present only in 1w
     * maps nothing, the usd tab's list does not count for this topic, and defaultVisible plays no part.
     */
    @Test fun N03_onlyTheTetherTabsOneDayCatalogAdmits() {
        val t = kst("20:01:00")
        val input = batch(ex("upbit", 1342.0, t), usd("kb", 1341.0, t))
        val both = listOf(exObs("upbit", 1342.0, t), usdObs("kb", 1341.0, t))
        assertEquals("current", both, map(input).observations)
        assertTrue("no tether tab", map(input, current.copy(tabs = current.tabs - "tether")).observations.isEmpty())
        assertTrue("no 1d", map(input, withTether(oneDay = null, oneWeek = tetherOneDay)).observations.isEmpty())
        assertEquals("upbit absent", listOf(usdObs("kb", 1341.0, t)), map(input, withTether(tetherOneDay - "upbit.usdt-krw")).observations)
        assertEquals(
            "upbit only in 1w", listOf(usdObs("kb", 1341.0, t)),
            map(input, withTether(tetherOneDay - "upbit.usdt-krw", oneWeek = listOf("upbit.usdt-krw"))).observations
        )
        assertEquals(
            "kb absent from tether though the usd tab lists it", listOf(exObs("upbit", 1342.0, t)),
            map(input, withTether(tetherOneDay - "kb.usd")).observations
        )
        val usdListsUpbit = withTether(tetherOneDay - "upbit.usdt-krw").let {
            it.copy(tabs = it.tabs + ("usd" to tab("usd", usdOneDay + "upbit.usdt-krw")))
        }
        assertEquals("the usd tab does not admit", listOf(usdObs("kb", 1341.0, t)), map(input, usdListsUpbit).observations)
        assertEquals("not default visible", both, map(input, withTether(tetherOneDay, visible = listOf("dxy"))).observations)
    }

    /** N04: the topic and asset decide; KRX, the dollar index, other topics and case variants map nothing. */
    @Test fun N04_theTopicAndAssetDecide() {
        val t = kst("20:01:00")
        val cases = listOf(
            "exchange on fx" to map(batch(listOf(ex("upbit", 1342.0, t)), topic = "fx:usd-krw")),
            "reference on fx" to map(batch(listOf(usd("kb", 1342.0, t)), topic = "fx:usd-krw")),
            "krx topic" to map(batch(listOf(ex("upbit", 1342.0, t)), topic = "krx:usd-krw-futures")),
            "dollar index topic" to map(batch(listOf(ex("upbit", 1342.0, t)), topic = "dxy:spot")),
            "krx on tether" to map(batch(quote("krx", "usd-krw-futures", 1342.0, t, t))),
            "exchange as usd" to map(batch(usd("upbit", 1342.0, t))),
            "bank as usdt" to map(batch(ex("kb", 1342.0, t))),
            "jpy reference" to map(batch(quote("hana", "jpy-krw", 912.0, t))),
            "source case" to map(batch(ex("UPBIT", 1342.0, t))),
            "asset case" to map(batch(quote("upbit", "USDT-KRW", 1342.0, t))),
            "dotted source" to map(batch(ex("foo.bar", 1342.0, t)), withTether(tetherOneDay + "foo.bar.usdt-krw")),
            "dollar index candidate" to map(batch(TopicGraphCandidate.DollarIndex(104.5, t, "investing")))
        )
        for ((label, result) in cases) {
            assertTrue(label, result.observations.isEmpty())
            assertTrue(label, result.anomalies.isEmpty())
        }
        for (topic in listOf("fx:usd-krw", "krx:usd-krw-futures", "dxy:spot", "usdt:krw-x")) {
            assertTrue(topic, tetherGraphSeriesIds(topic, current).isEmpty())
        }
    }

    /**
     * N05: the series set is exact - the five exchanges as `.usdt-krw`, kb, hana and investing as `.usd`, nothing else: other
     * registered banks listed in the tether catalog (citi, shinhan) are neither mapped nor routed.
     */
    @Test fun N05_theSeriesSetIsExact() {
        val extras = withTether(
            tetherOneDay + "foo.bar.usdt-krw" + "investing.usdt-krw" + "hacker.usdt-krw" + "upbit.usd" + "toss.usd" + "kb.usdt" +
                "hana.jpy" + "citi.usd" + "shinhan.usd",
            visible = emptyList()
        )
        assertEquals((exchangeIds + referenceIds).toSet(), tetherGraphSeriesIds("usdt:krw", extras))
        val others = map(batch(usd("citi", 1342.0, kst("20:01:00")), usd("shinhan", 1342.0, kst("20:01:00"))), extras)
        assertTrue(others.observations.isEmpty())
        assertEquals((exchangeIds + referenceIds).toSet(), tetherGraphSeriesIds("usdt:krw", current))
        assertTrue(tetherGraphSeriesIds("usdt:krw", current.copy(tabs = emptyMap())).isEmpty())
    }

    /** N06: implausible prices and non-quotes drop alone; the rest keeps the batch order. An empty batch is empty. */
    @Test fun N06_implausiblePricesDropAloneAndOrderIsKept() {
        val t = kst("20:01:00")
        val input = batch(
            ex("gopax", 1342.0, t),
            ex("upbit", Double.NaN, t, kst("20:01:09")),
            ex("bithumb", Double.POSITIVE_INFINITY, t),
            ex("coinone", 0.0, t),
            ex("korbit", -1.0, t),
            usd("kb", 1e9, t),
            TopicGraphCandidate.DollarIndex(104.5, t, "investing"),
            usd("hana", 1341.0, t),
            ex("upbit", 1343.0, t)
        )
        val mapped = map(input)
        assertEquals(listOf(exObs("gopax", 1342.0, t), usdObs("hana", 1341.0, t), exObs("upbit", 1343.0, t)), mapped.observations)
        assertTrue("an implausible quote reports no anomaly", mapped.anomalies.isEmpty())
        assertTrue(map(batch()).observations.isEmpty())
    }

    /**
     * N07 (R2, R3): a change time equal to `timestamp` or absent gives one candidate (old schema, DB fallback, an exact 5-second
     * tick); one below `timestamp` + 5 s - hours earlier or a few seconds later - gives two; one at or past it is dropped and
     * reported while `timestamp` stays. WS and REST bootstrap map the same.
     */
    @Test fun N07_changeTimeConsistency() {
        val t = kst("20:01:00")
        val fallback = kst("20:01:03.123456")
        for (source in exchanges) for ((path, generation) in listOf(TopicGraphPath.WS to 1L, TopicGraphPath.REST_BOOTSTRAP to null)) {
            fun mapped(q: TopicGraphCandidate.Quote) = map(batch(listOf(q), path = path, generation = generation))
            val label = "$source $path"
            for ((case, q, at) in listOf(
                Triple("null", ex(source, 1342.0, t), t),
                Triple("old schema", ex(source, 1342.0, t, t), t),
                Triple("DB fallback", ex(source, 1342.0, fallback, fallback), fallback)
            )) {
                val single = mapped(q)
                assertEquals("$label $case", listOf(exObs(source, 1342.0, at)), single.observations)
                assertTrue("$label $case", single.anomalies.isEmpty())
            }
            val inside = t + 5.seconds - 1.milliseconds
            assertEquals(
                "$label just inside", listOf(exObs(source, 1342.0, t), exObs(source, 1342.0, inside)),
                mapped(ex(source, 1342.0, t, inside)).observations
            )
            assertEquals(
                "$label hours earlier", listOf(exObs(source, 1342.0, t - 3.hours), exObs(source, 1342.0, t)),
                mapped(ex(source, 1342.0, t, t - 3.hours)).observations
            )
            for (bad in listOf(t + 5.seconds, t + 1.hours)) {
                val result = mapped(ex(source, 1342.0, t, bad))
                assertEquals("$label $bad", listOf(exObs(source, 1342.0, t)), result.observations)
                assertEquals("$label $bad", setOf(key(exchangeId(source))), result.anomalies)
            }
            assertTrue("$label clean", mapped(ex(source, 1342.0, t, inside)).anomalies.isEmpty())
        }
        assertEquals(
            "anomalies are keyed by the scope", setOf(key("upbit.usdt-krw", otherScope)),
            map(batch(ex("upbit", 1342.0, t, t + 6.seconds)), s = otherScope).anomalies
        )
    }

    // --- original T rows through D1-D3, each exchange ------------------------------------------------------------------

    /**
     * T01: the display merger keeps the 19:59:58 change for both frames, so the 20:00:00 re-observation is lost there; the graph
     * feed keeps it in B, and the same frame again is duplicates that change nothing.
     */
    @Test fun T01_aReObservationLostByTheMergerIsKeptByTheFeed() {
        for (source in exchanges) {
            val first = TopicSourceEntry(source, "usdt-krw", 1342.0, kst("19:59:55"), kst("19:59:58"))
            val second = TopicSourceEntry(source, "usdt-krw", 1342.0, b, kst("19:59:58"))
            val shown = TopicRates().merge(listOf(first.toQuote()!!))
            assertSame("$source merger drops the second", shown, shown.merge(listOf(second.toQuote()!!)))
            assertEquals(source, kst("19:59:58"), shown.quotes.getValue(TopicQuoteKey(source, "usdt-krw")).at)

            fun frame(e: TopicSourceEntry, sequence: Long) =
                batch(listOf(quote(e.source, e.asset, e.rate, e.timestamp, e.rateChangedAt)), sequence = sequence)
            val s1 = feed(state(exchangeId(source)), kst("20:00:30"), frame(first, 1)).state
            val r2 = feed(s1, kst("20:00:30"), frame(second, 2))
            assertEquals(source, listOf(DUPLICATE, ADDED), r2.outcomes)
            assertEquals(source, id(source, "usdt-krw", 1342.0, b), r2.state.data.app.buckets.getValue(b).close)
            val again = feed(r2.state, kst("20:00:40"), frame(second, 3))
            assertEquals(source, listOf(DUPLICATE, DUPLICATE), again.outcomes)
            assertEquals(source, r2.state.data.app.buckets, again.state.data.app.buckets)
        }
    }

    /** T02: a 20:02 snapshot of a 19:59:58 change re-observed at 19:59:55 adds nothing to B and no time of its receipt. */
    @Test fun T02_anOldSnapshotStaysOutOfB() {
        for (source in exchanges) {
            val s = feed(state(exchangeId(source)), now, batch(ex(source, 1342.0, kst("19:59:55"), kst("19:59:58")))).state
            assertEquals(source, setOf(a), s.data.app.buckets.keys)
            assertEquals(source, setOf(kst("19:59:55"), kst("19:59:58")), s.data.app.observations.map { it.observedAt }.toSet())
        }
    }

    /** T03 and T03-L: the 20:00:00 re-observation of 1342.0 is in B, so the 20:01 price widens B on its side. */
    @Test fun T03_T03L_theReObservedPriceIsPartOfB() {
        for (source in exchanges) for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
            val s = feed(
                state(exchangeId(source)), kst("20:01:30"),
                batch(ex(source, 1342.0, b, kst("19:59:58"))),
                batch(listOf(ex(source, next, kst("20:01:00"), kst("20:01:00"))), sequence = 2)
            ).state
            assertEquals("$source $next", expected, range(s, b))
            assertEquals("$source $next", id(source, "usdt-krw", next, kst("20:01:00")), s.data.app.buckets.getValue(b).close)
        }
    }

    /** T04: a change at 19:59:59.999 stays in the earlier bucket and the 20:00:00 price in B; neither is copied across. */
    @Test fun T04_theBoundaryAssignsEachTimeToItsOwnBucket() {
        for (source in exchanges) {
            val s = feed(
                state(exchangeId(source)), kst("20:00:30"),
                batch(ex(source, 1342.0, kst("19:59:55"), kst("19:59:59.999"))),
                batch(listOf(ex(source, 1341.5, b, b)), sequence = 2)
            ).state
            assertEquals(source, 1342.0 to 1342.0, range(s, a))
            assertEquals(source, 1341.5 to 1341.5, range(s, b))
        }
    }

    /**
     * The same five-second interval (iOS :101, :288): two price changes floored to one `timestamp` keep their order by change
     * time - the later price closes B - and the second price's floored time is a same-time conflict kept as range.
     */
    @Test fun X01_changesInOneFiveSecondIntervalKeepTheirOrder() {
        for (source in exchanges) {
            val r = feed(
                state(exchangeId(source)), kst("20:00:30"),
                batch(ex(source, 1342.0, b, kst("20:00:01.200"))),
                batch(listOf(ex(source, 1342.3, b, kst("20:00:03.400"))), sequence = 2)
            )
            assertEquals(source, listOf(ADDED, ADDED, SAME_TIME_CONFLICT, ADDED), r.outcomes)
            assertEquals(source, 1342.0 to 1342.3, range(r.state, b))
            assertEquals(source, id(source, "usdt-krw", 1342.3, kst("20:00:03.400")), r.state.data.app.buckets.getValue(b).close)
        }
    }

    /**
     * X02: a change time outside the retained window is rejected by D3 while its `timestamp` is added; the same record by WS, REST
     * bootstrap and a later connection is one identity; reversed arrival keeps the same bucket.
     */
    @Test fun X02_retentionRedeliveryAndReversal() {
        for (source in exchanges) {
            val old = feed(state(exchangeId(source)), now, batch(ex(source, 1342.0, kst("20:01:00"), kst("20:01:00") - 30.hours)))
            assertEquals(source, listOf(REJECTED, ADDED), old.outcomes)

            val q = ex(source, 1342.0, kst("20:01:00"), kst("20:01:02"))
            val ws = batch(listOf(q), sequence = 1)
            val rest = batch(listOf(q), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
            val later = batch(listOf(q), sequence = 3, generation = 2)
            val all = feed(state(exchangeId(source)), now, ws, rest, later)
            assertEquals(source, listOf(ADDED, ADDED, DUPLICATE, DUPLICATE, DUPLICATE, DUPLICATE), all.outcomes)

            val high = batch(listOf(ex(source, 1345.7, kst("20:04:30"), kst("20:04:30"))), sequence = 1)
            val low = batch(listOf(ex(source, 1341.7, kst("20:04:50"), kst("20:04:50"))), sequence = 2)
            for (order in listOf(listOf(high, low), listOf(low, high))) {
                val s = feed(state(exchangeId(source)), kst("20:05:00"), *order.toTypedArray()).state
                assertEquals(source, 1341.7 to 1345.7, range(s, b))
                assertEquals(source, id(source, "usdt-krw", 1341.7, kst("20:04:50")), s.data.app.tip)
            }
        }
    }

    // --- seeds and closed buckets (T06, T07, T10 and their low rows), each exchange --------------------------------------

    private fun seededTab(
        seriesId: String,
        seed: GraphV2InProgress? = null,
        points: List<FreeGraphPoint> = emptyList(),
        fetchedAt: Instant,
        present: Boolean = true
    ) = GraphV2Tab(
        "tether", GraphPeriod.ONE_DAY, "10min", fetchedAt,
        FreeGraph("10min", if (present) listOf(FreeGraphSeries(seriesId, points)) else emptyList()),
        if (seed != null && present) mapOf(seriesId to seed) else emptyMap()
    )
    private fun seeded(source: String) = GraphSeededObservationState.empty(key(exchangeId(source)), GraphObservationOrder(emptyList()))
    private fun observed(s: GraphSeededObservationState, input: TopicGraphInput.Observations) =
        reduceSeededGraphObservations(s, map(input).observations.filter { it.seriesKey == s.app.seriesKey }).state
    private fun unreplaced(s: GraphSeededObservationState, start: Instant) =
        composeGraphBucket(s, start) as GraphComposedBucket.Unreplaced

    /** T06 and T06-L: an app extreme in B survives a later seed `[1341.7, 1341.9]` and stays the close (T08 is P08). */
    @Test fun T06_anAppExtremeSurvivesALaterSeed() {
        val cases = listOf("T06" to (1342.1 to (1341.7 to 1342.1)), "T06-L" to (1341.5 to (1341.5 to 1341.9)))
        for (source in exchanges) for ((row, case) in cases) {
            val (app, expected) = case
            var s = observed(seeded(source), batch(ex(source, app, kst("20:01:00"), kst("20:01:00"))))
            s = applyGraphServerTab(
                s, scope, seededTab(exchangeId(source), GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:05:00")), fetchedAt = kst("20:05:00")),
                kst("20:05:00")
            )
            val bucket = unreplaced(s, b)
            assertEquals("$source $row", expected, bucket.range.low to bucket.range.high)
            assertEquals("$source $row", app, bucket.close, 0.0)
        }
    }

    /** T07 and T07-L: a flat seed of 1346.4 or 1338.2 is kept as range once the app observes 1342.3. */
    @Test fun T07_aFlatSeedIsKeptAsRange() {
        for (source in exchanges) for ((flat, expected) in listOf(1346.4 to (1342.3 to 1346.4), 1338.2 to (1338.2 to 1342.3))) {
            var s = applyGraphServerTab(
                seeded(source), scope,
                seededTab(exchangeId(source), GraphV2InProgress(b, flat, flat, flat, kst("20:02:00")), fetchedAt = kst("20:02:00")),
                kst("20:02:00")
            )
            s = observed(s, batch(ex(source, 1342.3, kst("20:04:00"), kst("20:04:00"))))
            val bucket = unreplaced(s, b)
            assertEquals("$source $flat", expected, bucket.range.low to bucket.range.high)
        }
    }

    /** T10 and T10-L: once the server provides B closed `[1341.7, 1341.9]`, B is that point whatever the app saw. */
    @Test fun T10_aClosedBucketReplacesTheAppRange() {
        val closed = FreeGraphPoint(b, 1341.8, 1341.9, 1341.7)
        for (source in exchanges) for (app in listOf(1342.1, 1341.5)) {
            var s = observed(seeded(source), batch(ex(source, app, kst("20:01:00"), kst("20:01:00"))))
            s = applyGraphServerTab(s, scope, seededTab(exchangeId(source), points = listOf(closed), fetchedAt = kst("20:10:30")), kst("20:10:30"))
            assertEquals("$source $app", GraphComposedBucket.Closed(closed), composeGraphBucket(s, b))
        }
    }

    // --- the recorder ---------------------------------------------------------------------------------------------------

    private val R = GraphRecorderReducer
    private fun snap(
        revision: Long,
        userEnd: Long? = null,
        capabilityEnd: Long? = null,
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        floor: Long = 0L
    ) =
        TopicAccessSnapshot(
            revision = revision,
            facts = TopicAccessFacts.NONE.copy(
                token = grant, binding = EntitlementsIdentity("u1", 1L), tokenStanding = true,
                userBlocks = emptySet(), capabilityBlocks = capabilityBlocks
            ),
            userInvalidations = 3L,
            lastUserEnd = userEnd?.let {
                TopicAccessEnd(it, TopicAccessEndReason.AUTHORITATIVE_LOSS, EntitlementsIdentity("u1", 1L), "u1", "e1")
            },
            lastCapabilityEnd = capabilityEnd?.let {
                TopicAccessEnd(it, TopicAccessEndReason.CAPABILITY_REVOKED, EntitlementsIdentity("u1", 1L), "u1", "K1")
            },
            userEndInvalidationsFloor = floor
        )
    private fun ready() = R.syncAccess(R.empty(), snap(1), fence)
    private fun observe(s: GraphRecorderState, input: TopicGraphInput.Observations, admission: Boolean = true, at: Instant = now) =
        R.observe(s, input, current, snap(1), fence, admission, at)
    private fun resumed(topic: String) = TopicGraphInput.Continuity(
        9L, TopicGraphEventKind.DELIVERY_RESUMED, null, setOf(topic), setOf(TopicGraphPath.WS),
        TopicGraphAuthority(Any(), fence, 1L, lifetime), 1L, 1_000L
    )
    /** The state classes have no value equality, so series are compared by their readable fields. */
    private fun print(s: GraphRecoverableState) = listOf(
        s.data.app.buckets, s.data.app.tip, s.data.app.observations, s.data.serverBuckets, s.pending, s.gapHistory, s.generation
    )
    private fun reasons(s: GraphRecorderState, id: String, start: Instant = b) =
        s.series.getValue(key(id)).pending[start]?.reasons.orEmpty()

    /**
     * P01 (R4, R5): a tether frame creates its exchange and reference series; the same reference record by `fx:usd-krw` is a
     * duplicate that leaves the series as the tether frame made it.
     */
    @Test fun P01_aTetherFrameReachesExchangeAndReferenceSeries() {
        val t = kst("20:01:00")
        val s = observe(ready(), batch(ex("upbit", 1342.0, t, kst("20:01:02")), usd("kb", 1341.0, t)))
        assertEquals(setOf(key("upbit.usdt-krw"), key("kb.usd")), s.series.keys)
        assertEquals(setOf(id("upbit", "usdt-krw", 1342.0, t), id("upbit", "usdt-krw", 1342.0, kst("20:01:02"))),
            s.series.getValue(key("upbit.usdt-krw")).data.app.observations)
        val fx = TopicGraphInput.Observations(2L, "fx:usd-krw", TopicGraphPath.WS, attribution, 1L, listOf(usd("kb", 1341.0, t)))
        val both = observe(s, fx)
        assertEquals(print(s.series.getValue(key("kb.usd"))), print(both.series.getValue(key("kb.usd"))))
    }

    /** P02 (R2, plan :1345): a dropped change time leaves TIME_ANOMALY on that series' current bucket; its timestamp is kept. */
    @Test fun P02_aDroppedChangeTimeLeavesATimeAnomaly() {
        val t = kst("20:01:00")
        val s = observe(ready(), batch(ex("upbit", 1342.0, t, t + 7.seconds), ex("bithumb", 1342.0, t, kst("20:01:02"))))
        assertTrue(TIME_ANOMALY in reasons(s, "upbit.usdt-krw"))
        assertEquals(setOf(id("upbit", "usdt-krw", 1342.0, t)), s.series.getValue(key("upbit.usdt-krw")).data.app.observations)
        assertFalse(TIME_ANOMALY in reasons(s, "bithumb.usdt-krw"))
        // on an existing series too, and only on the current bucket
        val later = observe(s, batch(listOf(ex("bithumb", 1342.5, kst("20:11:00"), kst("20:11:09"))), sequence = 2), at = kst("20:12:00"))
        assertTrue(TIME_ANOMALY in reasons(later, "bithumb.usdt-krw", kst("20:10:00")))
        assertFalse(TIME_ANOMALY in reasons(later, "bithumb.usdt-krw", b))
        // the input's own bucket is not where the anomaly goes: timestamp 19:59:55 stays in A, the demand is on the current B
        for (source in exchanges) {
            val cross = observe(ready(), batch(ex(source, 1342.0, kst("19:59:55"), b)))
            val series = cross.series.getValue(key(exchangeId(source)))
            assertEquals(source, setOf(id(source, "usdt-krw", 1342.0, kst("19:59:55"))), series.data.app.observations)
            assertEquals(source, setOf(a), series.data.app.buckets.keys)
            assertTrue(source, TIME_ANOMALY in reasons(cross, exchangeId(source), b))
            assertFalse(source, TIME_ANOMALY in reasons(cross, exchangeId(source), a))
        }
    }

    /**
     * P03 (R5): a tether resume marks RECEIVE_GAP on the existing exchange and reference series it maps, and an FX resume does not
     * reach the exchange series.
     */
    @Test fun P03_aTetherResumeReachesItsWholeSet() {
        val t = kst("20:01:00")
        val fx = TopicGraphInput.Observations(2L, "fx:jpy-krw", TopicGraphPath.WS, attribution, 1L, listOf(quote("hana", "jpy-krw", 912.0, t)))
        val s = observe(observe(ready(), batch(ex("upbit", 1342.0, t), usd("hana", 1341.0, t))), fx)
        val tether = R.continuity(s, resumed("usdt:krw"), current, snap(1), fence, now)
        assertTrue(RECEIVE_GAP in reasons(tether, "upbit.usdt-krw"))
        assertTrue(RECEIVE_GAP in reasons(tether, "hana.usd"))
        assertFalse(RECEIVE_GAP in reasons(tether, "hana.jpy"))
        val fxOnly = R.continuity(s, resumed("fx:usd-krw"), current, snap(1), fence, now)
        assertFalse(RECEIVE_GAP in reasons(fxOnly, "upbit.usdt-krw"))
        assertTrue(RECEIVE_GAP in reasons(fxOnly, "hana.usd"))
    }

    /**
     * P04: a refused tether batch keeps no price, creates no series and leaves HANDOVER_LOSS over the span of its mapped times,
     * the change time included; a topic loss reaches existing series and waits for absent ones.
     */
    @Test fun P04_refusedAndLostTetherInputLeaveRecovery() {
        val s = observe(ready(), batch(ex("upbit", 1342.0, kst("20:01:00"))))
        val refused = observe(s, batch(listOf(ex("upbit", 1343.0, b, kst("19:58:00")), ex("bithumb", 1343.0, b)), sequence = 2), admission = false)
        assertEquals(setOf(key("upbit.usdt-krw")), refused.series.keys)
        assertEquals(s.series.getValue(key("upbit.usdt-krw")).data.app.observations,
            refused.series.getValue(key("upbit.usdt-krw")).data.app.observations)
        assertTrue(HANDOVER_LOSS in reasons(refused, "upbit.usdt-krw", a))
        assertTrue(HANDOVER_LOSS in reasons(refused, "upbit.usdt-krw", b))

        val lost = R.loseTopics(s, scope, mapOf("usdt:krw" to 3L), current, snap(1), fence, now)
        assertTrue(HANDOVER_LOSS in reasons(lost, "upbit.usdt-krw"))
        assertEquals((exchangeIds + referenceIds - "upbit.usdt-krw").toSet(), lost.untransferredSeries)
    }

    /**
     * P05 (T19, tether part): repeated tether snapshots carry every exchange's new input with kb, hana and investing's old USD
     * records, while hana's JPY and EUR arrive by their own topics. No reference series gets a B bucket; each keeps its old record
     * as tip and only its own asset; every exchange gets B; hana's USD, JPY and EUR stay apart.
     */
    @Test fun P05_T19_exchangeUpdatesDoNotPullOldReferenceRecordsIntoB() {
        val old = kst("19:59:00")
        val refRates = mapOf("investing" to 1341.0, "kb" to 1342.0, "hana" to 1343.0)
        val oldRefs = references.map { usd(it, refRates.getValue(it), old) }
        val first = batch(exchanges.map { ex(it, 1490.0, old, old) } + oldRefs)
        val second = batch(exchanges.map { ex(it, 1491.0, kst("20:02:00"), kst("20:02:00")) } + oldRefs, sequence = 2)
        fun fx(topic: String, asset: String, rate: Double) =
            TopicGraphInput.Observations(3L, topic, TopicGraphPath.WS, attribution, 1L, listOf(quote("hana", asset, rate, kst("20:01:00"))))
        var s = observe(ready(), first, at = kst("19:59:30"))
        s = observe(s, second, at = kst("20:02:30"))
        s = observe(s, fx("fx:jpy-krw", "jpy-krw", 912.0), at = kst("20:02:30"))
        s = observe(s, fx("fx:eur-krw", "eur-krw", 1561.0), at = kst("20:02:30"))
        for (source in references) {
            val series = s.series.getValue(key("$source.usd"))
            val rate = refRates.getValue(source)
            assertEquals(source, rate to rate, range(series, a))
            assertEquals(source, setOf(a), series.data.app.buckets.keys)
            assertEquals(source, id(source, "usd-krw", refRates.getValue(source), old), series.data.app.tip)
            assertEquals(source, setOf(id(source, "usd-krw", refRates.getValue(source), old)), series.data.app.observations)
        }
        for (source in exchanges) {
            val series = s.series.getValue(key(exchangeId(source)))
            assertEquals(source, setOf(a, b), series.data.app.buckets.keys)
            assertEquals(source, 1490.0 to 1490.0, range(series, a))
            assertEquals(source, 1491.0 to 1491.0, range(series, b))
            assertEquals(source, setOf(id(source, "usdt-krw", 1490.0, old), id(source, "usdt-krw", 1491.0, kst("20:02:00"))), series.data.app.observations)
            assertEquals(source, id(source, "usdt-krw", 1491.0, kst("20:02:00")), series.data.app.tip)
        }
        for ((ccy, rate) in listOf("jpy" to 912.0, "eur" to 1561.0)) {
            val series = s.series.getValue(key("hana.$ccy"))
            assertEquals(ccy, setOf(b), series.data.app.buckets.keys)
            assertEquals(ccy, rate to rate, range(series, b))
            assertEquals(ccy, setOf(id("hana", "$ccy-krw", rate, kst("20:01:00"))), series.data.app.observations)
            assertEquals(ccy, id("hana", "$ccy-krw", rate, kst("20:01:00")), series.data.app.tip)
        }
    }

    /** P06 (T17, T18, tether part): each reference row by the tether topic keeps its record time, for highs and lows. */
    @Test fun P06_T17_T18_referenceRowsByTheTetherTopic() {
        for (source in references) {
            for (old in listOf(1345.7, 1337.7)) {
                var s = observe(ready(), batch(usd(source, old, kst("19:59:00"))), at = kst("19:59:30"))
                s = observe(s, batch(listOf(usd(source, old, kst("19:59:00"))), sequence = 2), at = kst("20:02:00"))
                assertNull("$source $old", s.series.getValue(key("$source.usd")).data.app.buckets[b])
                s = observe(s, batch(listOf(usd(source, 1341.7, kst("20:02:05"))), sequence = 3), at = kst("20:02:10"))
                assertEquals("$source $old", 1341.7 to 1341.7, s.series.getValue(key("$source.usd")).data.app.buckets.getValue(b).let { it.low.rate to it.high.rate })
            }
            for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
                var s = observe(ready(), batch(usd(source, 1342.0, kst("19:59:00"))), at = kst("19:59:30"))
                s = observe(s, batch(listOf(usd(source, 1342.0, b)), sequence = 2), at = kst("20:00:30"))
                s = observe(s, batch(listOf(usd(source, next, kst("20:01:00"))), sequence = 3), at = kst("20:01:30"))
                assertEquals("$source $next", expected, s.series.getValue(key("$source.usd")).data.app.buckets.getValue(b).let { it.low.rate to it.high.rate })
            }
        }
    }

    /**
     * P07 (T23, tether part): a temporary interruption and a KRX-only cancel keep every tether series' observations, tip, range
     * and recovery state as they were - for each exchange and the reference rows. Ends of access are P11.
     */
    @Test fun P07_T23_interruptionsAndAKrxCancelKeepTheTetherSeries() {
        val t0 = kst("20:01:00")
        var s = observe(ready(), batch(exchanges.map { ex(it, 1342.0, t0, kst("20:01:02")) } + references.map { usd(it, 1341.0, t0) }))
        s = R.captureRequests(s, s.series.keys, snap(1), fence, true).first
        val before = s.series.mapValues { (_, v) -> print(v) }
        val interrupted = TopicGraphInput.Continuity(
            9L, TopicGraphEventKind.DELIVERY_INTERRUPTED, null, setOf("usdt:krw"), setOf(TopicGraphPath.WS),
            TopicGraphAuthority(Any(), fence, 1L, lifetime), 1L, 1_000L
        )
        val paused = R.continuity(s, interrupted, current, snap(1), fence, now)
        val krxCancel = R.syncAccess(s, snap(2, capabilityEnd = 1, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED)), fence)
        for ((label, kept) in listOf("interrupted" to paused, "krx cancel" to krxCancel)) {
            assertEquals(label, (exchangeIds + referenceIds).map { key(it) }.toSet(), kept.series.keys)
            for ((k, print) in before) assertEquals("$label ${k.seriesId}", print, print(kept.series.getValue(k)))
        }
    }

    private fun respond(s: GraphRecorderState, request: GraphRecoveryRequest, tab: GraphV2Tab, at: Instant) =
        R.applyResponse(s, request, tab, snap(1), fence, true, at)
    private fun capture(s: GraphRecorderState, source: String) = R.captureRequests(s, setOf(key(exchangeId(source))), snap(1), fence, true)
    private fun pending(s: GraphRecorderState, source: String) = s.series.getValue(key(exchangeId(source))).pending.keys

    /**
     * P08 (T08, T08-L): with a recovery request waiting, B's verified 1342.2 (or 1341.4) is collected; once the response brings the
     * seed `[1341.7, 1341.9]`, B is `[1341.7, 1342.2]` (or `[1341.4, 1341.9]`) and the waiting observation is kept.
     */
    @Test fun P08_T08_anObservationWhileARequestWaitsSurvivesTheResponse() {
        for (source in exchanges) for ((app, expected) in listOf(1342.2 to (1341.7 to 1342.2), 1341.4 to (1341.4 to 1341.9))) {
            val label = "$source $app"
            val created = observe(ready(), batch(ex(source, 1341.8, kst("19:59:00"), kst("19:59:00"))), at = kst("20:01:00"))
            val (captured, requests) = capture(created, source)
            val waiting = observe(captured, batch(listOf(ex(source, app, kst("20:01:30"), kst("20:01:30"))), sequence = 2))
            val during = waiting.series.getValue(key(exchangeId(source))).data
            assertEquals(label, app to app, during.app.buckets.getValue(b).let { it.low.rate to it.high.rate })
            val applied = respond(
                waiting, requests.single(),
                seededTab(exchangeId(source), GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:05:00")), fetchedAt = kst("20:05:00")),
                kst("20:05:00")
            )
            val data = applied.series.getValue(key(exchangeId(source))).data
            val bucket = composeGraphBucket(data, b) as GraphComposedBucket.Unreplaced
            assertEquals(label, expected, bucket.range.low to bucket.range.high)
            assertTrue(label, id(source, "usdt-krw", app, kst("20:01:30")) in data.app.observations)
        }
    }

    /**
     * P09 (T11 capture part; T15; a boundary crossed during the request): a recovery demand stays for a captured request without
     * an applied response, and through a 200 response without the series, one without B, one whose seed is the previous bucket's
     * - that seed is not moved into B - and one that brings B's in-progress seed after 20:10, which is neither moved into the new
     * current bucket nor taken as B closed. Only a response providing B closed ends B's demand. Failure, cancellation, cache,
     * cooldown, joining and the hand-over to the next active owner belong to the request coordinator (S5-2).
     */
    @Test fun P09_T11_T15_aDemandEndsOnlyWithItsActualAnswer() {
        val closedA = FreeGraphPoint(a, 1341.0, 1341.2, 1340.8)
        val closedB = FreeGraphPoint(b, 1341.8, 1341.9, 1341.7)
        for (source in exchanges) {
            val created = observe(ready(), batch(ex(source, 1342.0, kst("20:01:00"))), at = kst("20:01:30"))
            val (captured, requests) = capture(created, source)
            val request = requests.single()
            assertTrue(source, b in pending(captured, source))
            val noSeries = respond(captured, request, seededTab(exchangeId(source), fetchedAt = kst("20:05:00"), present = false), kst("20:05:00"))
            assertTrue("$source without the series", b in pending(noSeries, source))
            val withoutB = respond(captured, request, seededTab(exchangeId(source), points = listOf(closedA), fetchedAt = kst("20:05:00")), kst("20:05:00"))
            assertTrue("$source without B", b in pending(withoutB, source))
            val oldSeed = respond(
                captured, request,
                seededTab(exchangeId(source), GraphV2InProgress(a, 1350.0, 1350.0, 1350.0, kst("19:59:00")), fetchedAt = kst("20:05:00")),
                kst("20:05:00")
            )
            assertTrue("$source previous-bucket seed", b in pending(oldSeed, source))
            assertNull("$source the old seed is not B's", oldSeed.series.getValue(key(exchangeId(source))).data.serverBuckets[b])
            val crossed = respond(
                captured, request,
                seededTab(exchangeId(source), GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:09:50")), fetchedAt = kst("20:10:30")),
                kst("20:10:30")
            )
            val crossedData = crossed.series.getValue(key(exchangeId(source))).data
            assertTrue("$source B still owed after the boundary", b in pending(crossed, source))
            assertNull("$source B's seed is not B closed", crossedData.serverBuckets[b])
            assertNull("$source nor the new current bucket's", crossedData.serverBuckets[kst("20:10:00")])
            val answered = respond(captured, request, seededTab(exchangeId(source), points = listOf(closedB), fetchedAt = kst("20:10:30")), kst("20:10:30"))
            assertFalse(source, b in pending(answered, source))
        }
    }

    /**
     * P10 (T14): a 19:58-20:02 gap marks both 19:50 and 20:00; a partial answer providing only 19:50 ends 19:50 alone; and after a
     * further gap during the request, the earlier request's answer does not end the new gap's demand on 19:50.
     */
    @Test fun P10_T14_eachGapBucketIsTrackedOnItsOwn() {
        val closedA = FreeGraphPoint(a, 1341.0, 1341.2, 1340.8)
        for (source in exchanges) {
            var s = observe(ready(), batch(ex(source, 1342.0, kst("19:57:00"), kst("19:57:00"))), at = kst("19:57:30"))
            s = R.continuity(s, resumed("usdt:krw"), current, snap(1), fence, kst("20:02:00"))
            assertTrue(source, RECEIVE_GAP in reasons(s, exchangeId(source), a) && RECEIVE_GAP in reasons(s, exchangeId(source), b))
            val (captured, requests) = capture(s, source)
            val partial = respond(captured, requests.single(), seededTab(exchangeId(source), points = listOf(closedA), fetchedAt = kst("20:05:00")), kst("20:05:00"))
            assertFalse("$source 19:50 answered", a in pending(partial, source))
            assertTrue("$source 20:00 still owed", b in pending(partial, source))
            val again = R.continuity(captured, resumed("usdt:krw"), current, snap(1), fence, kst("20:03:00"))
            val stale = respond(again, requests.single(), seededTab(exchangeId(source), points = listOf(closedA), fetchedAt = kst("20:05:00")), kst("20:05:00"))
            assertTrue("$source the new gap's 19:50 is not ended by the earlier request", a in pending(stale, source))
            assertTrue(source, b in pending(stale, source))
        }
    }

    /**
     * P11 (T12, recorder part): a user end, a new epoch of the same user, another user and a purge of the retired scope each leave
     * no series, held input, lost topic or waiting series - right after, after an old frame and after the answer to a request
     * captured before. Ending retries and the per-screen access check belong to S5-2.
     */
    @Test fun P11_T12_nothingComesBackAfterAnEndANewScopeOrAPurge() {
        val epochFence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e2", grant)
        val otherUser = TopicSessionFence(AuthIdentityFence("u2", 1L), "e9", grant)
        fun assertEmpty(label: String, s: GraphRecorderState) {
            assertTrue("$label series", s.series.isEmpty())
            assertTrue("$label inputs", s.pending.inputs.isEmpty())
            assertTrue("$label lost topics", s.pending.lostTopics.isEmpty())
            assertTrue("$label waiting", s.untransferredSeries.isEmpty())
        }
        for (source in exchanges) {
            var created = observe(ready(), batch(ex(source, 1342.0, kst("20:01:00"))), at = kst("20:01:30"))
            created = R.loseTopics(
                created, scope, mapOf("usdt:krw" to lifetime.invalidations),
                current, snap(1), fence, now
            )
            created = R.offerPending(
                created, batch(listOf(ex(source, 1342.1, kst("20:01:30"))), sequence = 2),
                snap(1), fence, true
            )
            created = R.offerPending(
                created, batch(listOf(ex(source, 1342.2, kst("20:01:40"))), sequence = 3),
                snap(1), fence, false
            )
            val (captured, requests) = capture(created, source)
            assertEquals(source, 1, requests.size)
            assertTrue("$source premise: series", captured.series.isNotEmpty())
            assertTrue("$source premise: held input", captured.pending.inputs.isNotEmpty())
            assertEquals("$source premise: lost topic", setOf("usdt:krw"), captured.pending.lostTopics)
            assertEquals(
                "$source premise: waiting series",
                (exchangeIds + referenceIds - exchangeId(source)).toSet(),
                captured.untransferredSeries
            )
            val oldFrame = batch(listOf(ex(source, 1343.0, kst("20:02:00"), kst("20:02:01"))), sequence = 2)
            val tab = seededTab(exchangeId(source), points = listOf(FreeGraphPoint(b, 1341.8, 1341.9, 1341.7)), fetchedAt = kst("20:10:30"))
            val afterEnd = snap(2, userEnd = 1, floor = 4L)
            data class End(val label: String, val state: GraphRecorderState, val access: TopicAccessSnapshot, val live: TopicSessionFence?)
            val ends = listOf(
                End("user end", R.syncAccess(captured, afterEnd, fence), afterEnd, fence),
                End("new epoch", R.syncAccess(captured, snap(2), epochFence), snap(2), epochFence),
                End("other user", R.syncAccess(captured, snap(2), otherUser), snap(2), otherUser),
                End("purge", R.purge(R.syncAccess(captured, snap(2), null), { it == scope }), snap(2), null)
            )
            for (end in ends) {
                val label = "$source ${end.label}"
                assertEmpty("$label right after", end.state)
                val framed = R.observe(end.state, oldFrame, current, end.access, end.live, false, now)
                assertEmpty("$label old frame", framed)
                val answered = R.applyResponse(framed, requests.single(), tab, end.access, end.live, true, kst("20:10:30"))
                assertEmpty("$label old answer", answered)
            }
        }
    }

    /**
     * P12 (input before the catalog): tether input held before the catalog replays through the adapter once it arrives; an input
     * too large to hold is lost by its topic and, once the catalog arrives, waits on every tether series as a hand-over loss.
     */
    @Test fun P12_tetherInputBeforeTheCatalog() {
        for (source in exchanges) {
            val input = batch(ex(source, 1342.0, kst("20:01:00"), kst("20:01:02")))
            val held = R.observe(ready(), input, null, snap(1), fence, true, now)
            assertTrue(source, held.series.isEmpty())
            assertEquals(source, 1, held.pending.inputs.size)
            val replayed = R.replayPending(held, current, snap(1), fence, listOf(true), now)
            assertEquals(
                source, setOf(id(source, "usdt-krw", 1342.0, kst("20:01:00")), id(source, "usdt-krw", 1342.0, kst("20:01:02"))),
                replayed.series.getValue(key(exchangeId(source))).data.app.observations
            )
        }
        for (source in exchanges) {
            val huge = batch((0 until 513).map { ex(source, 1342.0 + it * 0.01, kst("20:01:00")) })
            val lost = R.observe(ready(), huge, null, snap(1), fence, true, now)
            assertTrue(source, lost.pending.inputs.isEmpty())
            assertEquals(source, setOf("usdt:krw"), lost.pending.lostTopics)
            val transferred = R.replayPending(lost, current, snap(1), fence, emptyList(), now)
            assertTrue("$source no dropped price or series", transferred.series.isEmpty())
            assertEquals(source, (exchangeIds + referenceIds).toSet(), transferred.untransferredSeries)
            val next = observe(transferred, batch(listOf(ex(source, 1350.0, kst("20:01:30"))), sequence = 2))
            val series = next.series.getValue(key(exchangeId(source)))
            assertEquals(source, setOf(id(source, "usdt-krw", 1350.0, kst("20:01:30"))), series.data.app.observations)
            assertTrue("$source the loss is handed over", HANDOVER_LOSS in reasons(next, exchangeId(source), b))
            assertFalse(source, exchangeId(source) in next.untransferredSeries)
        }
    }

    /**
     * P13 (future time, device clock difference): an exchange record in the bucket after the current one is within D3's one-bucket
     * clock allowance and adopted; one further ahead is not adopted and leaves TIME_ANOMALY on the current bucket.
     */
    @Test fun P13_aFutureRecordIsAnAnomaly() {
        for (source in exchanges) {
            val near = observe(ready(), batch(ex(source, 1342.0, kst("20:15:00"), kst("20:15:00"))))
            val nearSeries = near.series.getValue(key(exchangeId(source)))
            assertEquals(source, setOf(id(source, "usdt-krw", 1342.0, kst("20:15:00"))), nearSeries.data.app.observations)
            assertEquals(source, setOf(kst("20:10:00")), nearSeries.data.app.buckets.keys)
            assertEquals(source, 1342.0 to 1342.0, range(nearSeries, kst("20:10:00")))
            assertEquals(source, id(source, "usdt-krw", 1342.0, kst("20:15:00")), nearSeries.data.app.tip)
            assertFalse(source, TIME_ANOMALY in reasons(near, exchangeId(source), b))
            val far = observe(ready(), batch(ex(source, 1342.0, kst("20:25:00"), kst("20:25:00"))))
            val farData = far.series.getValue(key(exchangeId(source))).data
            assertTrue(source, farData.app.observations.isEmpty())
            assertTrue(source, farData.app.buckets.isEmpty())
            assertNull(source, farData.app.tip)
            assertTrue(source, TIME_ANOMALY in reasons(far, exchangeId(source), b))
        }
    }

    /**
     * P14 (asynchronous purge, sink part): queued tether inputs of the retired scope are removed by the sink's purge and never
     * reach the recorder, while the current scope's tether input still does. The registered captures are R6t of
     * GraphV2RecoveryRequestContractTest.
     */
    @Test fun P14_aSinkPurgeRemovesTheRetiredScopesTetherInput() = runTest {
        val calls = mutableListOf<TopicGraphInput>()
        val consumer = object : GraphTopicInputConsumer {
            override fun observe(input: TopicGraphInput.Observations) { calls += input }
            override fun observe(input: TopicGraphInput.Continuity) { calls += input }
            override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) = Unit
            override fun close() = Unit
        }
        val sinkScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val sink = GraphRecorderTopicSink(sinkScope, consumer)
            val nextFence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e2", grant)
            val retired = batch(exchanges.map { ex(it, 1342.0, kst("20:01:00"), kst("20:01:02")) })
            val kept = TopicGraphInput.Observations(
                2L, "usdt:krw", TopicGraphPath.WS, TopicUseAttribution(Any(), nextFence, 1L, lifetime), 2L,
                exchanges.map { ex(it, 1343.0, kst("20:01:30")) }
            )
            assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(retired))
            assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(kept))
            assertTrue(sink.purge { it == scope })
            assertFalse(sink.purge { it == scope })
            runCurrent()
            assertEquals(listOf<TopicGraphInput>(kept), calls)
        } finally {
            sinkScope.cancel()
        }
    }
}
