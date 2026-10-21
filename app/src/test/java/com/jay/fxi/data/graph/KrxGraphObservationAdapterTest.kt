package com.jay.fxi.data.graph

import com.jay.fxi.contract.ContractCorpusVerifier
import com.jay.fxi.contract.ContractResourceLoader
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphObservationOutcome.ADDED
import com.jay.fxi.data.graph.GraphObservationOutcome.DUPLICATE
import com.jay.fxi.data.graph.GraphObservationOutcome.REJECTED
import com.jay.fxi.data.graph.GraphObservationOutcome.SAME_TIME_CONFLICT
import com.jay.fxi.data.graph.GraphRecoveryReason.TIME_ANOMALY
import com.jay.fxi.data.remote.DecodedTopicFrame
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.KrxTopicData
import com.jay.fxi.data.remote.dto.KrxTopicMessage
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-1 (JVM): the KRX topic's graph observations through the pure adapter, D1-D3 and the seed composition (S6C7a and the pure
 * part of S6C7b). The adapter is not connected to the recorder here (S6-3).
 *
 * Oracles: ANDROID_V2_PLAN.md :236 and :1330-1335 (per-source time meaning and legacy/fallback are fixed by fixture before the
 * adapter; the two-time source `usd-krw-futures` keeps `(price, change time)` and `(price, re-observation time)` each as a bucket
 * candidate); s6_1_contracts.r5.md 2 (K-F1..K-F6) and p3.r3.md 6 (K01-K27); iOS GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :83-84,
 * :92, :103, :105, :107 (a single-time form cannot be told apart, no new server field), :117, :253-256, :258-262, :284,
 * :286-287, :289; server latest_rates_cache.py:620-674 (3-field writer, no `rate_changed_at`), :795-852 (tick writer: `timestamp`
 * = floor5(tick), a new price's change time is its tick, the same price keeps it), :854-912 (read fills `rate_changed_at =
 * timestamp`), krx_kis.py:1610 and :1852 (tick time = `received_at`).
 *
 * P3 r3 fixture map (4.1.1): K01 = KT01 (f1 now 20:00:30 instead of 19:59:59, same 19:50 bucket admission); K02 = KT02 (WS
 * instead of REST, K-R5 and KX02 2); K03/K03-L = KT03/KT03-L; K03v = KT03v; K04 = KT04 1; K04-legacy = KT04 2; K05 = KX01;
 * K06 = KN01 b + KX08 b; K07 = KN05 5/7/8/9; K08 = KX02 1 (30 h instead of ~65 h, same 25 h lower bound); K09 = KX02 2;
 * K10 = KX02 3; K11 = KN05 2; K12 = KX07; K13 = KT04-K1 + KN05 4; K14 = KN05 2 + 3; K14-a = KT04-K2; K14-b = KT04-K3;
 * K15 = KX08; K16 = KX09 1; K17 = KX09 2 (now from K12 and K16); K18 = KN05 2; K19 = KN05 3 + KX10 second input; K20 = KX10;
 * K21 = KX11 + KS10; K22 = KT04-K4 (now from K13 and 08:30:06); K23 a/b = KN03 + KX05 1/2, K23 c (legacy `usdt:krw` frame key)
 * is S6-2b/S6-5, not here; K24 = KN02 (hidden = 6, 1w only = 4); K25 = KN04; K26 = KX06 (an exception, not empty candidates);
 * K27 = KX03 + KX04. Dates: `kst()` is 2026-10-06 (the same weekday as P3's 2026-10-20); the adapter and D3 read no calendar.
 *
 * Not here: T05, T08, T09, T11, T14-T16, T21 and their low rows and the P rows (recorder path, S6-3/S6-5); low>high (seed,
 * S6-3/S6-5); frame isolation D11 (TSC); diagnostics logging (the adapter has none).
 */
class KrxGraphObservationAdapterTest {

    private val scope = GraphDataScope("u1", "e1")
    private val otherScope = GraphDataScope("u2", "e2")

    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-06T$hhmmss+09:00")
    private fun d21(hhmmss: String): Instant = Instant.parse("2026-10-21T$hhmmss+09:00")

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val now = kst("20:02:00")

    private val K = "krx.usd-krw-futures"
    private val TOPIC = TopicCatalogue.KRX_FUTURES

    // server graph_v2_intraday.py:90-102 and :116-135, :146-147
    private val tetherOneDay = listOf("upbit", "bithumb", "coinone", "korbit", "gopax").map { "$it.usdt-krw" } + K +
        listOf("investing", "kb", "hana").map { "$it.usd" } + "dxy" + "dxy_futures"
    private val usdOneDay = listOf(
        "investing.usd", K, "kb.usd", "hana.usd", "shinhan.usd", "woori.usd", "ibk.usd", "nh.usd", "sc.usd", "bs.usd", "dxy"
    )

    private fun tab(id: String, oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        GraphCatalogTab(id, id, emptyMap(), buildMap {
            if (oneDay != null) put(GraphPeriod.ONE_DAY, GraphCatalogPeriod(oneDay, visible ?: oneDay.take(3)))
            put(GraphPeriod.ONE_WEEK, GraphCatalogPeriod(oneWeek, oneWeek))
        })
    private fun catalogOf(
        tether1d: List<String>?,
        usd1d: List<String>?,
        tether1w: List<String> = emptyList(),
        jpy1d: List<String> = listOf("investing.jpy", "hana.jpy"),
        visible: List<String>? = null
    ) = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to tab("usd", usd1d, visible = visible),
            "jpy" to tab("jpy", jpy1d),
            "eur" to tab("eur", listOf("investing.eur", "hana.eur")),
            "tether" to tab("tether", tether1d, tether1w, visible)
        )
    )
    private val current = catalogOf(tetherOneDay, usdOneDay)

    private val grant = TopicGrantToken(7L)
    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", grant)
    private val lifetime = TopicUseLifetime(grant, 3L)
    private val attribution = TopicUseAttribution(Any(), fence, 1L, lifetime)

    private fun krx(rate: Double, t: Instant, changed: Instant? = null, source: String = "krx", asset: String = "usd-krw-futures") =
        TopicGraphCandidate.Quote(source, asset, rate, t, changed)

    private fun batch(
        candidates: List<TopicGraphCandidate>,
        topic: String = TOPIC,
        sequence: Long = 1,
        path: TopicGraphPath = TopicGraphPath.WS,
        generation: Long? = 1L
    ) = TopicGraphInput.Observations(sequence, topic, path, attribution, generation, candidates)
    private fun batch(vararg candidates: TopicGraphCandidate) = batch(candidates.toList())

    private fun map(input: TopicGraphInput.Observations, catalog: GraphCatalog = current, s: GraphDataScope = scope) =
        krxGraphMapping(s, input, catalog)
    private fun key(id: String, s: GraphDataScope = scope) = GraphObservationSeriesKey(s, id)
    private fun id(rate: Double, t: Instant) = GraphObservationId("krx", "usd-krw-futures", t, rate)
    private fun obs(rate: Double, t: Instant, s: GraphDataScope = scope) = GraphObservation(GraphObservationSeriesKey(s, K), id(rate, t))

    private fun state() = GraphRecoverableState.empty(key(K), GraphObservationOrder(emptyList()))

    /** Maps each batch and applies only the KRX series' observations to its D3 state (tether test :145-156). */
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
    private fun times(s: GraphRecoverableState) = s.data.app.observations.map { it.observedAt }.toSet()
    private fun close(s: GraphRecoverableState, start: Instant) = s.data.app.buckets.getValue(start).close

    /** The state classes have no value equality, so series are compared by their readable fields (tether test :520-522). */
    private fun print(s: GraphRecoverableState) = listOf(
        s.data.app.buckets, s.data.app.tip, s.data.app.observations, s.data.serverBuckets, s.pending, s.gapHistory, s.generation
    )

    private fun seededTab(
        tabId: String = "tether",
        seriesId: String = K,
        seed: GraphV2InProgress? = null,
        points: List<FreeGraphPoint> = emptyList(),
        fetchedAt: Instant,
        present: Boolean = true
    ) = GraphV2Tab(
        tabId, GraphPeriod.ONE_DAY, "10min", fetchedAt,
        FreeGraph("10min", if (present) listOf(FreeGraphSeries(seriesId, points)) else emptyList()),
        if (seed != null && present) mapOf(seriesId to seed) else emptyMap()
    )
    private fun seeded() = GraphSeededObservationState.empty(key(K), GraphObservationOrder(emptyList()))
    private fun observed(s: GraphSeededObservationState, input: TopicGraphInput.Observations) =
        reduceSeededGraphObservations(s, map(input).observations.filter { it.seriesKey == s.app.seriesKey }).state
    private fun unreplaced(s: GraphSeededObservationState, start: Instant) =
        composeGraphBucket(s, start) as GraphComposedBucket.Unreplaced

    // ContractPayloadTest.kt:27-33
    private val wireJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
        isLenient = false
        explicitNulls = true
    }
    private val decoder = TopicFrameDecoder(wireJson)

    private fun TopicSourceEntry.candidate() = TopicGraphCandidate.Quote(source, asset, rate, timestamp, rateChangedAt)

    // --- mapping (S6C7a) ------------------------------------------------------------------------------------------------

    /**
     * KN01: the two times come out in time order with their precision, keyed by the scope.
     * Mutations: MA-09 unsorted, MA-10 change time ignored, MA-14 wrong id, MA-15 scope ignored, MA-16 wrong observation asset.
     */
    @Test fun KN01_bothTimesInTimeOrder() {
        val t = kst("20:01:00")
        val later = kst("20:01:02.250250")
        val earlier = kst("19:58:03.500000")
        val forward = map(batch(krx(1342.0, t, later)))
        assertEquals(listOf(obs(1342.0, t), obs(1342.0, later)), forward.observations)
        assertTrue(forward.anomalies.isEmpty())
        assertEquals(listOf(obs(1342.0, earlier), obs(1342.0, t)), map(batch(krx(1342.0, t, earlier))).observations)
        assertEquals(
            listOf(obs(1342.0, t, otherScope), obs(1342.0, later, otherScope)),
            map(batch(krx(1342.0, t, later)), s = otherScope).observations
        )
    }

    /**
     * KN02 (K24): the series is admitted when either the tether or the usd tab's 1d allSeries lists it, exactly once; 1w, jpy
     * and defaultVisible play no part.
     * Mutations: MA-05a tether only (3), MA-05b usd only (2), MA-05c 1w counted (4), MA-05d emitted per tab (1 twice), MA-05e any
     * tab (7), MA-05f defaultVisible (8).
     */
    @Test fun KN02_eitherOneDayCatalogAdmitsOnce() {
        val t = kst("20:01:00")
        val input = batch(krx(1342.0, t))
        val one = listOf(obs(1342.0, t))
        val admitted = listOf(
            "1 current" to current,
            "2 tether 1d only" to catalogOf(tetherOneDay, usdOneDay - K),
            "3 usd 1d only" to catalogOf(tetherOneDay - K, usdOneDay),
            "8 not default visible" to catalogOf(tetherOneDay, usdOneDay, visible = listOf("dxy"))
        )
        for ((label, catalog) in admitted) assertEquals(label, one, map(input, catalog).observations)
        val refused = listOf(
            "4 tether 1w only" to catalogOf(tetherOneDay - K, usdOneDay - K, tether1w = listOf(K)),
            "5 no tabs" to current.copy(tabs = emptyMap()),
            "6 no tether 1d, absent from usd" to catalogOf(null, usdOneDay - K),
            "7 jpy 1d only" to catalogOf(tetherOneDay - K, usdOneDay - K, jpy1d = listOf("investing.jpy", "hana.jpy", K))
        )
        for ((label, catalog) in refused) assertTrue(label, map(input, catalog).observations.isEmpty())
        assertEquals(setOf(K), krxGraphSeriesIds(TOPIC, current))
        assertTrue(krxGraphSeriesIds(TOPIC, current.copy(tabs = emptyMap())).isEmpty())
    }

    /**
     * KN03 (K23 a/b): the topic, source and asset decide, exactly and case-sensitively; the dollar index is skipped.
     * Mutations: MA-01 topic check removed, MA-02 source check removed, MA-03 asset check removed, MA-04 case ignored, MA-17
     * DollarIndex not excluded.
     */
    @Test fun KN03_theTopicAndAssetDecide() {
        val t = kst("20:01:00")
        val otherTopics = listOf("usdt:krw", "fx:usd-krw", "dxy:spot", "krx:usd-krw-futures-x", "KRX:USD-KRW-FUTURES")
        val cases = otherTopics.map { topic -> "krx on $topic" to map(batch(listOf(krx(1342.0, t, kst("20:01:02"))), topic = topic)) } +
            listOf(
                "upbit usdt" to map(batch(krx(1342.0, t, source = "upbit", asset = "usdt-krw"))),
                "krx usd" to map(batch(krx(1342.0, t, asset = "usd-krw"))),
                "hana futures" to map(batch(krx(1342.0, t, source = "hana"))),
                "source case" to map(batch(krx(1342.0, t, source = "KRX"))),
                "asset case" to map(batch(krx(1342.0, t, asset = "USD-KRW-FUTURES"))),
                "dollar index" to map(batch(TopicGraphCandidate.DollarIndex(104.5, t, "investing")))
            )
        for ((label, result) in cases) {
            assertTrue(label, result.observations.isEmpty())
            assertTrue(label, result.anomalies.isEmpty())
        }
        for (topic in otherTopics) assertTrue(topic, krxGraphSeriesIds(topic, current).isEmpty())
    }

    /**
     * KN04 (K25): implausible prices and non-quotes drop alone, without an anomaly; the rest keeps the batch order. An empty
     * batch and the two empty KRX frames (no key, explicit null) map nothing, and the explicit null frame decodes.
     * Mutations: MA-06 RateSanity removed, MA-12b anomaly reported for an implausible price, explicit null frame treated as a
     * failure.
     */
    @Test fun KN04_implausiblePricesAndEmptyInputs() {
        val t = kst("20:01:00")
        val mapped = map(
            batch(
                krx(1342.0, t),
                krx(Double.NaN, t, t + 9.seconds),
                krx(Double.POSITIVE_INFINITY, t),
                krx(0.0, t),
                krx(-1.0, t),
                krx(1e9, t),
                TopicGraphCandidate.DollarIndex(104.5, t, "investing"),
                krx(1343.0, t)
            )
        )
        assertEquals(listOf(obs(1342.0, t), obs(1343.0, t)), mapped.observations)
        assertTrue("an implausible quote reports no anomaly", mapped.anomalies.isEmpty())

        val empty = map(batch())
        assertTrue(empty.observations.isEmpty())
        assertTrue(empty.anomalies.isEmpty())
        for (text in listOf(
            """{"type":"snapshot","version":1,"topic":"krx:usd-krw-futures","data":{}}""",
            """{"type":"snapshot","version":1,"topic":"krx:usd-krw-futures","data":{"usd_krw_futures":null}}"""
        )) {
            val frame = decoder.decode(text) // TopicMessage.kt:226-231
            assertTrue(text, frame is DecodedTopicFrame.Krx)
            val data = (frame as DecodedTopicFrame.Krx).value.data
            assertNull(text, data.usdKrwFutures)
            val result = map(batch(data.allEntries.map { it.candidate() }))
            assertTrue(text, result.observations.isEmpty())
            assertTrue(text, result.anomalies.isEmpty())
        }
    }

    /**
     * KN05 (K-F1..K-F6; K07, K11, K13, K14, K18, K19): a change time equal to `timestamp` or absent gives one candidate; one
     * below `timestamp` + 5 s gives two; one at or past it is dropped and reported while `timestamp` stays. WS and REST map the
     * same.
     * Mutations: MA-07a `>=` to `>` (7), MA-07b ceiling 10 s (7), MA-07c ceiling removed (8), MA-08 equal times not merged (2
     * twice), MA-12 anomaly missing or wrong scope, MA-13 timestamp candidate dropped with the anomaly.
     */
    @Test fun KN05_changeTimeConsistency() {
        val t = kst("20:01:00")
        val fallback = kst("20:01:03.123456")
        val boundary = kst("06:00:00")
        for ((path, generation) in listOf(TopicGraphPath.WS to 1L, TopicGraphPath.REST_BOOTSTRAP to null)) {
            fun mapped(q: TopicGraphCandidate.Quote, s: GraphDataScope = scope) =
                map(batch(listOf(q), path = path, generation = generation), s = s)
            val inside = t + 5.seconds - 1.milliseconds
            val clean = listOf(
                "1 null" to (krx(1342.0, t) to listOf(obs(1342.0, t))),
                "2 equal (K-F3)" to (krx(1342.0, t, t) to listOf(obs(1342.0, t))),
                "3 DB fallback (K-F4)" to (krx(1342.0, fallback) to listOf(obs(1342.0, fallback))),
                "4 REST close boundary (K-F5)" to (krx(1342.0, boundary, boundary) to listOf(obs(1342.0, boundary))),
                "5 just inside" to (krx(1342.0, t, inside) to listOf(obs(1342.0, t), obs(1342.0, inside))),
                "6 hours earlier" to (krx(1342.0, t, t - 3.hours) to listOf(obs(1342.0, t - 3.hours), obs(1342.0, t)))
            )
            for ((case, pair) in clean) {
                val (q, expected) = pair
                val result = mapped(q)
                assertEquals("$path $case", expected, result.observations)
                assertTrue("$path $case", result.anomalies.isEmpty())
            }
            for ((case, bad) in listOf("7 ceiling" to t + 5.seconds, "8 hour later" to t + 1.hours)) {
                val result = mapped(krx(1342.0, t, bad))
                assertEquals("$path $case", listOf(obs(1342.0, t)), result.observations)
                assertEquals("$path $case", setOf(key(K)), result.anomalies)
            }
            assertEquals(
                "$path 9 other scope", setOf(GraphObservationSeriesKey(otherScope, K)),
                mapped(krx(1342.0, t, t + 6.seconds), otherScope).anomalies
            )
        }
    }

    /**
     * KN06: the KRX rule and the tether exchange rule agree on every pair (times out and whether an anomaly is reported).
     * Mutations: every MA-07*, MA-08 or MA-09 that makes the two copies diverge (stands in for DRY if O-5 extracts nothing).
     */
    @Test fun KN06_agreesWithTheTetherExchangeRule() {
        val t = kst("20:01:00")
        val changes = listOf(
            null, t, t + 1.milliseconds, t - 1.milliseconds, t + 5.seconds - 1.milliseconds, t + 5.seconds, t + 1.hours, t - 3.hours
        )
        for (changed in changes) {
            val mine = map(batch(krx(1342.0, t, changed)))
            val tether = tetherGraphMapping(
                scope,
                batch(listOf(krx(1342.0, t, changed, source = "upbit", asset = "usdt-krw")), topic = TopicCatalogue.TETHER),
                current
            )
            assertEquals("$changed", tether.observations.map { it.id.observedAt }, mine.observations.map { it.id.observedAt })
            assertEquals("$changed", tether.anomalies.isNotEmpty(), mine.anomalies.isNotEmpty())
        }
    }

    /**
     * KN07: the existing contract copy `topic-snapshot-krx` decodes to two candidates; the display merger reduces the same entry
     * to one time (TopicMessage.kt:176-177).
     * Mutations: MA-10, MA-11 timestamp ignored, decoder field mapping regressions.
     */
    @Test fun KN07_theContractCopyMapsBothTimes() {
        val text = ContractCorpusVerifier.verify(ContractResourceLoader.load()).text("topic-snapshot-krx")
        val frame = decoder.decode(text)
        assertTrue(frame is DecodedTopicFrame.Krx)
        val message = (frame as DecodedTopicFrame.Krx).value
        val e = message.data.usdKrwFutures!!
        val result = map(batch(listOf(e.candidate()), topic = message.topic))
        assertEquals(
            listOf(
                obs(1397.5, Instant.parse("2026-08-31T10:20:00+09:00")),
                obs(1397.5, Instant.parse("2026-08-31T10:20:00.001+09:00"))
            ),
            result.observations
        )
        assertTrue(result.anomalies.isEmpty())
        assertEquals(Instant.parse("2026-08-31T10:20:00.001+09:00"), e.toQuote()!!.at)
    }

    // --- T rows through D1-D3 (S6C7a), iOS original prices (PLAN:1397) ---------------------------------------------------

    /**
     * KT01 (K01): the display merger keeps the 19:59:58 change for both frames, so the 20:00:00 re-observation is lost there; the
     * feed keeps it in B, and the same frame again is duplicates that change nothing.
     * Mutations: MA-11 timestamp ignored (only `[DUPLICATE]`), MA-10.
     */
    @Test fun KT01_aReObservationLostByTheMergerIsKeptByTheFeed() {
        val first = TopicSourceEntry("krx", "usd-krw-futures", 1342.0, kst("19:59:55"), kst("19:59:58"))
        val second = TopicSourceEntry("krx", "usd-krw-futures", 1342.0, b, kst("19:59:58"))
        val shown = TopicRates().merge(listOf(first.toQuote()!!))
        assertSame(shown, shown.merge(listOf(second.toQuote()!!)))
        assertEquals(kst("19:59:58"), shown.quotes.getValue(TopicQuoteKey("krx", "usd-krw-futures")).at)

        fun frame(e: TopicSourceEntry, sequence: Long) = batch(listOf(e.candidate()), sequence = sequence)
        val s1 = feed(state(), kst("20:00:30"), frame(first, 1)).state
        val r2 = feed(s1, kst("20:00:30"), frame(second, 2))
        assertEquals(listOf(DUPLICATE, ADDED), r2.outcomes)
        assertEquals(id(1342.0, b), r2.state.data.app.buckets.getValue(b).close)
        val again = feed(r2.state, kst("20:00:40"), frame(second, 3))
        assertEquals(listOf(DUPLICATE, DUPLICATE), again.outcomes)
        assertEquals(r2.state.data.app.buckets, again.state.data.app.buckets)
    }

    /**
     * KT02 (K02): a 20:02 snapshot of a 19:59:58 change re-observed at 19:59:55 adds nothing to B and no time of its receipt.
     * Mutations: an observation made from the receive time (clock injection), MA-10.
     */
    @Test fun KT02_anOldSnapshotStaysOutOfB() {
        val s = feed(state(), now, batch(krx(1342.0, kst("19:59:55"), kst("19:59:58")))).state
        assertEquals(setOf(a), s.data.app.buckets.keys)
        assertEquals(setOf(kst("19:59:55"), kst("19:59:58")), times(s))
    }

    /**
     * KT03 and KT03-L (K03, K03-L): the 20:00:00 re-observation of 1342.0 is in B, so the 20:01 price widens B on its side.
     * Mutations: MA-11 (the re-observed 1342.0 missing from B).
     */
    @Test fun KT03_KT03L_theReObservedPriceIsPartOfB() {
        for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
            val s = feed(
                state(), kst("20:01:30"),
                batch(krx(1342.0, b, kst("19:59:58"))),
                batch(listOf(krx(next, kst("20:01:00"), kst("20:01:00"))), sequence = 2)
            ).state
            assertEquals("$next", expected, range(s, b))
            assertEquals("$next", id(next, kst("20:01:00")), close(s, b))
        }
    }

    /**
     * KT03v (K03v): the new price's change time carries the receipt's sub-second part; the range is KT03's and the close is the
     * change time.
     * Mutations: MA-10 (close at 20:01:00), MA-09.
     */
    @Test fun KT03v_aSubSecondChangeTimeClosesB() {
        for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
            val s = feed(
                state(), kst("20:01:30"),
                batch(krx(1342.0, b, kst("19:59:58"))),
                batch(listOf(krx(next, kst("20:01:00"), kst("20:01:00.400"))), sequence = 2)
            ).state
            assertEquals("$next", expected, range(s, b))
            assertEquals("$next", id(next, kst("20:01:00.400")), close(s, b))
        }
    }

    /**
     * KT04 (K04, K04-legacy): a change at 19:59:59.999 stays in A and the 20:00:00 price in B, with or without a change time;
     * neither is copied across (iOS GraphV2PreparedTabTests.swift:936-942).
     * Mutations: a copied boundary tolerance, a single-time form moved to another bucket.
     */
    @Test fun KT04_theBoundaryAssignsEachTimeToItsOwnBucket() {
        val firsts = listOf(
            "1 two times" to krx(1342.0, kst("19:59:55"), kst("19:59:59.999")),
            "2 single time" to krx(1342.0, kst("19:59:59.999"))
        )
        for ((label, first) in firsts) {
            val s = feed(state(), kst("20:00:30"), batch(first), batch(listOf(krx(1341.5, b, b)), sequence = 2)).state
            assertEquals(label, 1342.0 to 1342.0, range(s, a))
            assertEquals(label, 1341.5 to 1341.5, range(s, b))
        }
    }

    /**
     * KT04-K1 (O-1, K13): a REST close at the 06:00:00 boundary is one observation in the 06:00 bucket; it records the adopted
     * limit that a single-time form is `timestamp` alone.
     * Mutations: a single-time form dropped (if O-1 adopts).
     */
    @Test fun KT04_K1_aBoundaryCloseIsInTheSixOClockBucket() {
        val s = feed(
            state(), kst("06:02:00"),
            batch(listOf(krx(1388.0, kst("06:00:00"), kst("06:00:00"))), path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        ).state
        assertEquals(setOf(kst("06:00:00")), s.data.app.buckets.keys)
        assertEquals(1388.0 to 1388.0, range(s, kst("06:00:00")))
    }

    /**
     * KT04-K2 (K14-a): a close whose trade time is before the CM boundary goes to 05:50; no 06:00 bucket.
     * Mutations: bucket assigned by the receive time (06:01) or the close policy time (06:00).
     */
    @Test fun KT04_K2_aTradeTimeBeforeTheBoundaryStaysInTheEarlierBucket() {
        val input = batch(krx(1396.8, d21("05:59:59"), d21("05:59:59")))
        assertEquals(listOf(obs(1396.8, d21("05:59:59"))), map(input).observations)
        val r = feed(state(), d21("06:01:00.400"), input)
        assertEquals(listOf(ADDED), r.outcomes)
        assertEquals(setOf(d21("05:50:00")), r.state.data.app.buckets.keys)
        assertEquals(1396.8 to 1396.8, range(r.state, d21("05:50:00")))
    }

    /**
     * KT04-K3 (K14-b): a close at a tick's floored time is a same-time conflict kept as range; the close stays the latest
     * observation time, not the close source (GraphObservationReducer.kt:11-13, :43).
     * Mutations: the close source preferred as close, a same-time different price merged as DUPLICATE.
     */
    @Test fun KT04_K3_aCloseAtATicksTimeIsAConflictKeptAsRange() {
        val tick = batch(krx(1396.7, d21("05:59:55"), d21("05:59:55.100")))
        val closeBatch = batch(listOf(krx(1396.8, d21("05:59:55"), d21("05:59:55"))), sequence = 2)
        val s1 = feed(state(), d21("05:59:55.200"), tick)
        val s2 = feed(s1.state, d21("06:01:00.400"), closeBatch)
        assertEquals(listOf(ADDED, ADDED, SAME_TIME_CONFLICT), s1.outcomes + s2.outcomes)
        assertEquals(1396.7 to 1396.8, range(s2.state, d21("05:50:00")))
        assertEquals(id(1396.7, d21("05:59:55.100")), close(s2.state, d21("05:50:00")))
        assertTrue(map(tick).anomalies.isEmpty())
        assertTrue(map(closeBatch).anomalies.isEmpty())
    }

    /**
     * KT04-K4 (K22): across a contract roll each observation is adopted in its own bucket; no contract inference.
     * Mutations: a session or contract boundary inferred to drop the jump or carry the 06:00 close (K-R6).
     */
    @Test fun KT04_K4_aContractRollPassesThrough() {
        val closeBatch = batch(krx(1396.8, d21("06:00:00"), d21("06:00:00")))
        val open = batch(listOf(krx(1391.2, d21("08:30:05"), d21("08:30:03.200"))), sequence = 2)
        val s1 = feed(state(), d21("06:01:00.400"), closeBatch)
        assertEquals(listOf(ADDED), s1.outcomes)
        assertEquals(listOf(obs(1391.2, d21("08:30:03.200")), obs(1391.2, d21("08:30:05"))), map(open).observations)
        val s2 = feed(s1.state, d21("08:30:06"), open)
        assertEquals(listOf(ADDED, ADDED), s2.outcomes)
        assertEquals(setOf(d21("06:00:00"), d21("08:30:00")), s2.state.data.app.buckets.keys)
        assertEquals(1396.8 to 1396.8, range(s2.state, d21("06:00:00")))
        assertEquals(s1.state.data.app.buckets.getValue(d21("06:00:00")), s2.state.data.app.buckets.getValue(d21("06:00:00")))
        assertEquals(1391.2 to 1391.2, range(s2.state, d21("08:30:00")))
        assertEquals(id(1391.2, d21("08:30:05")), close(s2.state, d21("08:30:00")))
    }

    /**
     * KX01 (K05): two price changes floored to one `timestamp` keep their order by change time; the later price closes B.
     * Mutations: MA-09.
     */
    @Test fun KX01_changesInOneFiveSecondIntervalKeepTheirOrder() {
        val r = feed(
            state(), kst("20:00:30"),
            batch(krx(1342.0, b, kst("20:00:01.200"))),
            batch(listOf(krx(1342.3, b, kst("20:00:03.400"))), sequence = 2)
        )
        assertEquals(listOf(ADDED, ADDED, SAME_TIME_CONFLICT, ADDED), r.outcomes)
        assertEquals(1342.0 to 1342.3, range(r.state, b))
        assertEquals(id(1342.3, kst("20:00:03.400")), close(r.state, b))
    }

    /**
     * KX02 (K08, K09, K10): a change time outside the retained window is rejected while its `timestamp` is added; the same
     * record by WS, REST bootstrap and a later connection is one identity; reversed arrival keeps the same bucket.
     * Mutations: identity split by path, MA-09.
     */
    @Test fun KX02_retentionRedeliveryAndReversal() {
        val old = feed(state(), now, batch(krx(1342.0, kst("20:01:00"), kst("20:01:00") - 30.hours)))
        assertEquals(listOf(REJECTED, ADDED), old.outcomes)

        val q = krx(1342.0, kst("20:01:00"), kst("20:01:02"))
        val ws = batch(listOf(q), sequence = 1)
        val rest = batch(listOf(q), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        val later = batch(listOf(q), sequence = 3, generation = 2L)
        assertEquals(listOf(ADDED, ADDED, DUPLICATE, DUPLICATE, DUPLICATE, DUPLICATE), feed(state(), now, ws, rest, later).outcomes)

        val high = batch(listOf(krx(1345.7, kst("20:04:30"), kst("20:04:30"))), sequence = 1)
        val low = batch(listOf(krx(1341.7, kst("20:04:50"), kst("20:04:50"))), sequence = 2)
        for (order in listOf(listOf(high, low), listOf(low, high))) {
            val s = feed(state(), kst("20:05:00"), *order.toTypedArray()).state
            assertEquals(1341.7 to 1345.7, range(s, b))
            assertEquals(id(1341.7, kst("20:04:50")), s.data.app.tip)
        }
    }

    /**
     * KX03 (K27): past `upper = current + 600 s` the record is rejected and a TIME_ANOMALY demand lands on the current bucket;
     * the next bucket is admitted (GraphRecoveryReducer.kt:7, :17-20, :167-169; empty state: GraphRecovery.kt:27-28,
     * GraphRecoveryReducer.kt:46-51, :59-74).
     * Mutations: the adapter correcting the time to now.
     */
    @Test fun KX03_aFutureRecordIsAnAnomaly() {
        val far = feed(state(), now, batch(krx(1342.0, kst("20:20:00"))))
        assertEquals(listOf(REJECTED), far.outcomes)
        assertEquals(setOf(TIME_ANOMALY), far.state.pending[b]?.reasons)
        assertEquals(mapOf(b to GraphRecoveryDemand(1, setOf(TIME_ANOMALY))), far.state.pending)
        assertFalse(kst("20:20:00") in far.state.data.app.buckets)
        assertEquals(listOf(ADDED), feed(state(), now, batch(krx(1342.0, kst("20:10:00")))).outcomes)
    }

    /**
     * KX04 (K27, PLAN:1391): a device clock behind or ahead never rewrites observation times; 12 minutes behind puts the record
     * past `upper` (GraphRecoveryReducer.kt:20, :169).
     * Mutations: observation time corrected to the device now, bucket assigned by the receive time.
     */
    @Test fun KX04_deviceClockSkew() {
        val input = batch(krx(1342.0, kst("20:01:00"), kst("20:01:02")))
        for (at in listOf(kst("19:55:00"), kst("20:09:59"))) {
            val r = feed(state(), at, input)
            assertEquals("$at", listOf(ADDED, ADDED), r.outcomes)
            assertEquals("$at", setOf(kst("20:01:00"), kst("20:01:02")), times(r.state))
        }
        val behind = feed(state(), kst("19:49:00"), input)
        assertEquals(listOf(REJECTED, REJECTED), behind.outcomes)
        assertEquals(setOf(TIME_ANOMALY), behind.state.pending[kst("19:40:00")]?.reasons)
        assertFalse(b in behind.state.data.app.buckets)
    }

    /**
     * KX05 (K23 a): a KRX quote on another topic maps nothing in either adapter; KRX observations handed to a non-KRX series
     * are rejected and leave it unchanged (GraphRecoveryReducer.kt:165).
     * Mutations: MA-01, emission under a non-KRX key; the premise in 3 stops an empty mapping passing with empty outcomes.
     */
    @Test fun KX05_topicAssetMismatchAndNonKrxSeries() {
        val q = krx(1342.0, kst("20:01:00"), kst("20:01:02"))
        val onTether = batch(listOf(q), topic = TopicCatalogue.TETHER)
        assertTrue(map(onTether).observations.isEmpty())
        assertTrue(map(onTether).anomalies.isEmpty())
        assertTrue(tetherGraphMapping(scope, onTether, current).observations.isEmpty())
        val onFx = batch(listOf(q), topic = "fx:usd-krw")
        assertTrue(map(onFx).observations.isEmpty())
        assertTrue(map(onFx).anomalies.isEmpty())

        val upbit = key("upbit.usdt-krw")
        val s = observeRecoverable(
            GraphRecoverableState.empty(upbit, GraphObservationOrder(emptyList())),
            listOf(GraphObservation(upbit, GraphObservationId("upbit", "usdt-krw", kst("20:01:00"), 1420.0))),
            now
        ).state
        val m = map(batch(q)).observations
        assertEquals(listOf(obs(1342.0, kst("20:01:00")), obs(1342.0, kst("20:01:02"))), m)
        val r = observeRecoverable(s, m, now)
        assertEquals(listOf(REJECTED, REJECTED), r.outcomes)
        assertEquals(print(s), print(r.state))
    }

    /**
     * KX06 (K26, N-P8): the decoder propagates a KRX parse failure as an exception (TopicFrameDecoder.kt:41-43, :63); an
     * offset-less or malformed time throws kotlinx-datetime's DateTimeFormatException (InstantSerializer.kt:23-25), an array
     * shape throws SerializationException, both IllegalArgumentException subtypes. An explicit null change time decodes.
     * Mutations: the KRX branch swallowing a failure into an empty frame or `Unsupported` (D11 bypass), `KrxTopicData` accepting
     * an array, `InstantSerializer` reading an offset-less value in a default zone.
     */
    @Test fun KX06_decoderFailurePropagates() {
        val entry = """{"source":"krx","asset":"usd-krw-futures","rate":1342.0,""" +
            """"timestamp":"2026-10-20T20:01:00+09:00","rate_changed_at":"2026-10-20T20:01:02+09:00"}"""
        fun frame(data: String) = """{"type":"snapshot","version":1,"topic":"krx:usd-krw-futures","data":{"usd_krw_futures":$data}}"""
        val p0 = frame(entry)
        assertEquals(
            DecodedTopicFrame.Krx(
                KrxTopicMessage(
                    "snapshot", 1, "krx:usd-krw-futures",
                    KrxTopicData(
                        TopicSourceEntry(
                            "krx", "usd-krw-futures", 1342.0,
                            Instant.parse("2026-10-20T20:01:00+09:00"), Instant.parse("2026-10-20T20:01:02+09:00")
                        )
                    )
                )
            ),
            decoder.decode(p0)
        )
        val negatives = listOf(
            "1 no offset" to p0.replace(""""timestamp":"2026-10-20T20:01:00+09:00"""", """"timestamp":"2026-10-20T20:01:00""""),
            "2 bad change time" to p0.replace(""""rate_changed_at":"2026-10-20T20:01:02+09:00"""", """"rate_changed_at":"x""""),
            "3 array shape" to frame("[$entry]")
        )
        for ((label, text) in negatives) {
            assertFalse(label, text == p0)
            assertThrows(label, IllegalArgumentException::class.java) { decoder.decode(text) }
        }
        val nullChange = decoder.decode(
            p0.replace(""""rate_changed_at":"2026-10-20T20:01:02+09:00"""", """"rate_changed_at":null""")
        )
        assertTrue(nullChange is DecodedTopicFrame.Krx)
        assertNull((nullChange as DecodedTopicFrame.Krx).value.data.usdKrwFutures!!.rateChangedAt)
    }

    // --- close, REST and fallback rows (P3 r3 6.3) ------------------------------------------------------------------------

    /** K12's state: the tick before the CF close, then the WS close. */
    private fun cfClose(): Pair<GraphRecoverableReduction, GraphRecoverableReduction> {
        val s1 = feed(state(), kst("15:34:58"), batch(krx(1397.3, kst("15:34:55"), kst("15:34:57.100"))))
        val c = batch(listOf(krx(1397.5, kst("15:45:00"), kst("15:45:00"))), sequence = 2)
        return s1 to feed(s1.state, kst("15:46:00.500"), c)
    }

    /**
     * KX07 (K12): the CF close is one candidate in the 15:40 bucket; no observation at its receipt, the 15:30 bucket unchanged.
     * Mutations: an observation from the receive time (15:40 close at 15:46:00.500), the single close candidate dropped (O-1
     * not adopted), the close moved earlier or the 15:30 close changed (close/tick continuity inferred, K-R6).
     */
    @Test fun KX07_theCfCloseIsInItsOwnBucket() {
        val c = batch(listOf(krx(1397.5, kst("15:45:00"), kst("15:45:00"))), sequence = 2)
        assertEquals(listOf(obs(1397.5, kst("15:45:00"))), map(c).observations)
        assertTrue(map(c).anomalies.isEmpty())
        val (s1, s2) = cfClose()
        assertEquals(listOf(ADDED, ADDED), s1.outcomes)
        assertEquals(listOf(ADDED), s2.outcomes)
        assertEquals(1397.5 to 1397.5, range(s2.state, kst("15:40:00")))
        assertEquals(id(1397.5, kst("15:45:00")), close(s2.state, kst("15:40:00")))
        assertEquals(setOf(kst("15:34:55"), kst("15:34:57.100"), kst("15:45:00")), times(s2.state))
        assertEquals(s1.state.data.app.buckets[kst("15:30:00")], s2.state.data.app.buckets[kst("15:30:00")])
    }

    /**
     * KX08 (K15, K06): after the close, the first CM tick of the same earlier price keeps its old change time; nothing is
     * inferred about the 1397.5 close in between. From empty, the old candidate is adopted in 15:30.
     * Mutations: a change time earlier than the close dropped (P-5 inference), the old candidate dropped from empty, path or
     * generation in the identity (a: first candidate ADDED).
     */
    @Test fun KX08_aSamePriceAfterTheClose() {
        val m = batch(listOf(krx(1397.3, kst("18:00:05"), kst("15:34:57.100"))), sequence = 3)
        assertEquals(listOf(obs(1397.3, kst("15:34:57.100")), obs(1397.3, kst("18:00:05"))), map(m).observations)
        assertTrue(map(m).anomalies.isEmpty())

        val s2 = cfClose().second.state
        val after = feed(s2, kst("18:00:06"), m)
        assertEquals(listOf(DUPLICATE, ADDED), after.outcomes)
        assertEquals(s2.data.app.buckets[kst("15:30:00")], after.state.data.app.buckets[kst("15:30:00")])
        assertEquals(s2.data.app.buckets[kst("15:40:00")], after.state.data.app.buckets[kst("15:40:00")])
        assertEquals(id(1397.5, kst("15:45:00")), close(after.state, kst("15:40:00")))
        assertEquals(1397.3 to 1397.3, range(after.state, kst("18:00:00")))
        assertEquals(id(1397.3, kst("18:00:05")), close(after.state, kst("18:00:00")))
        assertEquals(times(s2) + kst("18:00:05"), times(after.state))

        val fresh = feed(state(), kst("18:00:06"), m)
        assertEquals(listOf(ADDED, ADDED), fresh.outcomes)
        assertEquals(setOf(kst("15:30:00"), kst("18:00:00")), fresh.state.data.app.buckets.keys)
    }

    /**
     * KX09 (K16, K17): a REST close alone is one candidate in 15:40 with no observation at 16:30; after a WS close at the same
     * time with another price it is a conflict kept as range, and the first adopted stays close and tip
     * (GraphObservationReducer.kt:11-13, :43, :46).
     * Mutations: same-time different price merged as DUPLICATE, the later one as close, another rule for REST (K-R5), an
     * observation at the REST receipt (1: 16:30).
     */
    @Test fun KX09_closeSources() {
        val restOnly = feed(
            state(), kst("16:30:00"),
            batch(listOf(krx(1397.6, kst("15:45:00"), kst("15:45:00"))), path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        )
        assertEquals(listOf(ADDED), restOnly.outcomes)
        assertEquals(setOf(kst("15:40:00")), restOnly.state.data.app.buckets.keys)
        assertEquals(1397.6 to 1397.6, range(restOnly.state, kst("15:40:00")))
        assertEquals(setOf(kst("15:45:00")), times(restOnly.state))

        val w = batch(krx(1397.5, kst("15:45:00"), kst("15:45:00")))
        val s1 = feed(state(), kst("15:46:00.500"), w)
        assertEquals(listOf(ADDED), s1.outcomes)
        val r = batch(
            listOf(krx(1397.6, kst("15:45:00"), kst("15:45:00"))),
            sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null
        )
        val s2 = feed(s1.state, kst("16:30:00"), r)
        assertEquals(listOf(SAME_TIME_CONFLICT), s2.outcomes)
        assertEquals(1397.5 to 1397.6, range(s2.state, kst("15:40:00")))
        assertEquals(id(1397.5, kst("15:45:00")), close(s2.state, kst("15:40:00")))
        assertEquals(id(1397.5, kst("15:45:00")), s2.state.data.app.tip)
        assertEquals(setOf(id(1397.5, kst("15:45:00")), id(1397.6, kst("15:45:00"))), s2.state.data.app.observations)
    }

    /**
     * KX10 (K20, K19): a WS tick then the DB fallback of the same price; the fallback's single time is adopted in 20:10 (CC2).
     * Mutations: a candidate without a change time dropped (no 20:10 bucket), the time ignored for the same price (DUPLICATE),
     * the fallback time replaced by the 20:10:05 receipt.
     */
    @Test fun KX10_aRedisToDbSwitch() {
        val tick = batch(krx(1397.4, kst("20:09:55"), kst("20:09:59.600")))
        val fallback = batch(listOf(krx(1397.4, kst("20:10:00.700"))), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        assertEquals(listOf(obs(1397.4, kst("20:10:00.700"))), map(fallback).observations)
        assertTrue(map(fallback).anomalies.isEmpty())
        val r = feed(state(), kst("20:10:05"), tick, fallback)
        assertEquals(listOf(ADDED, ADDED, ADDED), r.outcomes)
        assertEquals(setOf(kst("20:09:55"), kst("20:09:59.600")), times(r.state).filter { graphObservationBucketStart(it) == b }.toSet())
        assertEquals(id(1397.4, kst("20:09:59.600")), close(r.state, b))
        assertEquals(1397.4 to 1397.4, range(r.state, kst("20:10:00")))
        assertEquals(id(1397.4, kst("20:10:00.700")), close(r.state, kst("20:10:00")))
    }

    /**
     * KX11 (K21, limit fixture): a DB fallback after the CF close carries a later sub-second time and becomes close and tip; the
     * adapter does not correct the reversal, a closed server bucket replaces it (KS10).
     * Mutations: the exact 15:45:00 close fixed as close (source priority), a candidate without a change time dropped (CC2),
     * the time truncated to seconds (a conflict with 15:45:00 leaving 1397.5 as close).
     */
    @Test fun KX11_aFallbackReversal() {
        val s2 = cfClose().second.state
        val fallback = batch(listOf(krx(1397.3, kst("15:45:00.512"))), sequence = 3, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        assertEquals(listOf(obs(1397.3, kst("15:45:00.512"))), map(fallback).observations)
        assertTrue(map(fallback).anomalies.isEmpty())
        val r = feed(s2, kst("16:30:00"), fallback)
        assertEquals(listOf(ADDED), r.outcomes)
        assertEquals(1397.3 to 1397.5, range(r.state, kst("15:40:00")))
        assertEquals(id(1397.3, kst("15:45:00.512")), close(r.state, kst("15:40:00")))
        assertEquals(id(1397.3, kst("15:45:00.512")), r.state.data.app.tip)
    }

    /**
     * KX12 (design r5): a record later than the real clock keeps both original times; the adapter has no clock (design 3.1) and
     * rejecting the future is D3's ceiling (KX03). The future-clock mutation check assumes the system clock is earlier than 2100,
     * so the row is deterministic.
     * Mutations: the observation time cut or moved by the real clock (MA-CLAMP-now `minOf(at, Clock.System.now())`), a candidate
     * later than the real clock dropped.
     */
    @Test fun KX12_aRecordLaterThanTheRealClockKeepsItsTimes() {
        val far = Instant.parse("2100-01-01T00:00:00+09:00")
        val m = map(batch(krx(1342.0, far, far + 2.seconds)))
        assertEquals(listOf(obs(1342.0, far), obs(1342.0, far + 2.seconds)), m.observations)
        assertTrue(m.anomalies.isEmpty())
    }

    // --- seeds and closed buckets (S6C7b pure part, tether test :448-487 inputs on series K) -------------------------------

    /**
     * KS06 and KS06-L: an app extreme in B survives a later seed `[1341.7, 1341.9]` and stays the close.
     * Mutations: MA-14 (an empty filter fails), wrong time.
     */
    @Test fun KS06_KS06L_anAppExtremeSurvivesALaterSeed() {
        for ((app, expected) in listOf(1342.1 to (1341.7 to 1342.1), 1341.5 to (1341.5 to 1341.9))) {
            var s = observed(seeded(), batch(krx(app, kst("20:01:00"), kst("20:01:00"))))
            s = applyGraphServerTab(
                s, scope,
                seededTab("tether", K, GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:05:00")), fetchedAt = kst("20:05:00")),
                kst("20:05:00")
            )
            val bucket = unreplaced(s, b)
            assertEquals("$app", expected, bucket.range.low to bucket.range.high)
            assertEquals("$app", app, bucket.close, 0.0)
        }
    }

    /**
     * KS07 and KS07-L: a flat seed of 1346.4 or 1338.2 is kept as range once the app observes 1342.3.
     * Mutations: MA-14.
     */
    @Test fun KS07_KS07L_aFlatSeedIsKeptAsRange() {
        for ((flat, expected) in listOf(1346.4 to (1342.3 to 1346.4), 1338.2 to (1338.2 to 1342.3))) {
            var s = applyGraphServerTab(
                seeded(), scope,
                seededTab("tether", K, GraphV2InProgress(b, flat, flat, flat, kst("20:02:00")), fetchedAt = kst("20:02:00")),
                kst("20:02:00")
            )
            s = observed(s, batch(krx(1342.3, kst("20:04:00"), kst("20:04:00"))))
            val bucket = unreplaced(s, b)
            assertEquals("$flat", expected, bucket.range.low to bucket.range.high)
        }
    }

    /**
     * KS10 and KS10-L (K21): once the server provides B closed `[1341.7, 1341.9]`, B is that point whatever the app saw. The
     * tab is `"tether"`; the shared `"usd"` tab case is S6C7c (S6-5).
     * Mutations: MA-14 (caught only by the before-server premises, since a closed server bucket wins regardless of app
     * observations, GraphSeededObservationReducer.kt:80-82), tab-scoped id.
     */
    @Test fun KS10_KS10L_aClosedBucketReplacesTheAppRange() {
        val closed = FreeGraphPoint(b, 1341.8, 1341.9, 1341.7)
        for (app in listOf(1342.1, 1341.5)) {
            val s = observed(seeded(), batch(krx(app, kst("20:01:00"), kst("20:01:00"))))
            assertEquals("$app", setOf(obs(app, kst("20:01:00")).id), s.app.observations)
            val before = unreplaced(s, b)
            assertEquals("$app", app to app, before.range.low to before.range.high)
            assertEquals("$app", app, before.close, 0.0)
            val after = applyGraphServerTab(
                s, scope, seededTab("tether", K, points = listOf(closed), fetchedAt = kst("20:10:30")), kst("20:10:30")
            )
            assertEquals("$app", GraphComposedBucket.Closed(closed), composeGraphBucket(after, b))
        }
    }
}
