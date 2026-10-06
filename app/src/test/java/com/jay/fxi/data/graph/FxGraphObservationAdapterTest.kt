package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphObservationOutcome.ADDED
import com.jay.fxi.data.graph.GraphObservationOutcome.DUPLICATE
import com.jay.fxi.data.graph.GraphObservationOutcome.REJECTED
import com.jay.fxi.data.graph.GraphObservationOutcome.SAME_TIME_CONFLICT
import com.jay.fxi.data.graph.GraphRecoveryReason.TIME_ANOMALY
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphEventReason
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned S4 E1 contract r2 (JVM): FX bank/investing quotes mapped to their graph series, and the adapter-neutral holding
 * of inputs that arrive before the graph catalog.
 *
 * r2 (e1_contract.r1/codex_impl.r1.md, four old/new): a microsecond time in M01 so millisecond truncation fails; the read-only
 * probe also on the append and single-oversize paths; INITIAL, ACCESS_RESUMED and AUTH_RECOVERED do not take a loss back
 * either; M13b holds a continuity fact equal in kind and topics to the one before it.
 *
 * Oracles: ANDROID_V2_PLAN.md :1295-1304 (the bridge takes S3 hand-over input, checks the original topic, source, asset and
 * catalog mapping before adoption, drops implausible samples before the reducer, never re-feeds the TopicRates snapshot; a bucket
 * is assigned by the observation's server time; banks and investing use `timestamp` alone whatever topic carried them, and a
 * snapshot re-send or another source's update is not a new observation), :1323 (bounded holding of not-yet-mapped input and
 * its recovery hand-over), :1366-1370 (topic-asset mismatch, WS/REST re-delivery, input before catalog or screen, size bounds
 * and future times per adapter), :1377 (the tether reference rows belong to S5); server graph_v2_intraday.py:122-135 and
 * graph_v2.py:527-532 (1d FX ids `<source>.<ccy>` over investing and 8 banks, citi excluded), fx_topic_payload.py:102-115 (FX
 * carries timestamp only); iOS 89e866d GraphV2Section.swift:120-128, 1457-1460 (reverse mapping on source and asset together).
 * Design: R4c/S4 e1_design_codex.r2, cut down by e1_review_claude.r1 after a five-lens verification
 * (e1_design_verify_workflow.result.json), agreed in e1_review_codex.r1:
 *  - fxGraphSeriesIds(topic, catalog): only fx:usd-krw, fx:jpy-krw, fx:eur-krw; the exact ids `<registered source>.<ccy>`
 *    (Bank.fromCode, INVESTING included) that the currency tab's 1d allSeries lists. dxy, krx and unregistered ids never.
 *    The same set decides price mapping and, in F2, which series a continuity fact or a loss reaches.
 *  - fxGraphObservations(scope, batch, catalog): per candidate in order, a Quote whose asset is the topic's, whose source is
 *    registered, whose rate passes RateSanity.isPlausible and whose id is in that set becomes GraphObservation((scope, id),
 *    (source, asset, timestamp, rate)). rateChangedAt, mergeAt and toQuote() are never used. Anything else is dropped alone.
 *  - GraphPendingInputs: one per recorder scope, adapter-neutral, keeps the original TopicGraphInput objects in order. A unit
 *    is a candidate (any kind) or a continuity fact; an empty batch is not kept. Past 512 units the oldest inputs leave whole and
 *    their topics join lostTopics; a single input over 512 is not kept, its topics join lostTopics and the rest stays. No
 *    expiry: a quote held past the data window is rejected by D3 at replay (`slot < L`) without a demand, where a separate TTL
 *    would have turned it into a loss and a whole-window demand (e1_review_codex.r1 item 4). Exposed collections are read-only.
 *  - F2, not E1: replaying the held inputs through the live path in one serial turn, turning lostTopics into HANDOVER_LOSS over
 *    the whole demand window, replacing the pending value with EMPTY, access re-checks, continuity meaning and demand windows.
 * Completeness: each sentence above was matched against violating mutations by a four-agent workflow
 * (e1_contract.r1/completeness_workflow.result.json, no wrong expectation found); M13b, M13c and the added assertions in
 * M01-M04, M08, M11-M13 and M15 close its survivors (exact ids and topics, case, order and precision kept, no in-batch
 * removal, exactly 512 kept, read-only and copied collections, unchanged earlier values, no filtering or expiry in holding).
 * Not applicable here: low > high (a Quote carries one rate), diagnostic log on/off (E1 logs nothing). Parse failure is outside
 * E1's API and S3 has no FX invalid-timestamp decoder test (TopicFrameDecoderTest covers tether only), so it stays with F2's
 * integration run. The tether topic's FX rows and T19's exchange-carried case are S5's.
 *
 * The implementation thread reads but does not edit this file.
 */
class FxGraphObservationAdapterTest {

    private val scope = GraphDataScope("u1", "e1")
    private val otherScope = GraphDataScope("u2", "e2")
    private val sources = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private val currencies = listOf("usd", "jpy", "eur")

    private fun at(day: Int, hhmmss: String): Instant = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")
    private fun kst(hhmmss: String): Instant = at(6, hhmmss)
    private fun plusMillis(t: Instant, ms: Long): Instant = Instant.fromEpochMilliseconds(t.toEpochMilliseconds() + ms)

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val c = kst("20:10:00")

    private val grant = TopicGrantToken(1)
    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1), "e1", grant)
    private val lifetime = TopicUseLifetime(grant, 0)
    private val attribution = TopicUseAttribution(Any(), fence, 1, lifetime)
    private val authority = TopicGraphAuthority(Any(), fence, 1, lifetime)

    private fun quote(source: String, asset: String, rate: Double, t: Instant, changed: Instant? = null) =
        TopicGraphCandidate.Quote(source, asset, rate, t, changed)
    private fun batch(
        topic: String,
        candidates: List<TopicGraphCandidate>,
        sequence: Long = 1,
        path: TopicGraphPath = TopicGraphPath.WS,
        generation: Long? = 1
    ) = TopicGraphInput.Observations(sequence, topic, path, attribution, generation, candidates)
    private fun batch(topic: String, vararg candidates: TopicGraphCandidate) = batch(topic, candidates.toList())
    private fun fact(kind: TopicGraphEventKind, topics: Set<String>, sequence: Long, occurredAt: Long = 1_000L) =
        TopicGraphInput.Continuity(sequence, kind, null, topics, setOf(TopicGraphPath.WS), authority, 1, occurredAt)
    private fun quotes(n: Int, topic: String = "fx:usd-krw") =
        batch(topic, (0 until n).map { quote("kb", "usd-krw", 1340.0 + it * 0.01, plusMillis(b, it.toLong())) })

    private fun ids(ccy: String) = sources.map { "$it.$ccy" }
    private fun tab(id: String, oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        GraphCatalogTab(id, id, emptyMap(), buildMap {
            if (oneDay != null) put(GraphPeriod.ONE_DAY, GraphCatalogPeriod(oneDay, visible ?: oneDay.take(3)))
            put(GraphPeriod.ONE_WEEK, GraphCatalogPeriod(oneWeek, oneWeek))
        })
    private val current = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to tab("usd", ids("usd") + "dxy" + "krx.usd-krw-futures"),
            "jpy" to tab("jpy", ids("jpy")),
            "eur" to tab("eur", ids("eur")),
            "tether" to tab("tether", listOf("bithumb.usdt-krw", "investing.usd", "kb.usd", "hana.usd"))
        )
    )
    private fun withUsd(oneDay: List<String>?, oneWeek: List<String> = emptyList(), visible: List<String>? = null) =
        current.copy(tabs = current.tabs + ("usd" to tab("usd", oneDay, oneWeek, visible)))

    private fun map(input: TopicGraphInput.Observations, catalog: GraphCatalog = current, s: GraphDataScope = scope) =
        fxGraphObservations(s, input, catalog)
    private fun key(id: String, s: GraphDataScope = scope) = GraphObservationSeriesKey(s, id)
    private fun id(source: String, ccy: String, rate: Double, t: Instant) = GraphObservationId(source, "$ccy-krw", t, rate)
    private fun observation(source: String, ccy: String, rate: Double, t: Instant, s: GraphDataScope = scope) =
        GraphObservation(key("$source.$ccy", s), id(source, ccy, rate, t))
    private fun state(seriesId: String) = GraphRecoverableState.empty(key(seriesId), GraphObservationOrder(emptyList()))

    /** Maps each batch with E1 and applies it to one series' D3 state; other series' observations are D1-rejected there. */
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

    private fun offer(p: GraphPendingInputs, input: TopicGraphInput) = offerGraphPendingInput(p, input)
    private fun assertSameInputs(label: String, expected: List<TopicGraphInput>, actual: List<TopicGraphInput>) {
        assertEquals("$label size", expected.size, actual.size)
        expected.forEachIndexed { i, input -> assertSame("$label [$i]", input, actual[i]) }
    }

    /** Probes with add: clear() on an empty JDK read-only collection is a silent no-op. */
    private fun assertReadOnly(label: String, p: GraphPendingInputs) {
        try {
            @Suppress("UNCHECKED_CAST")
            (p.inputs as MutableList<TopicGraphInput>).add(fact(TopicGraphEventKind.INITIAL, emptySet(), 99))
            fail("$label inputs must be read-only")
        } catch (expected: UnsupportedOperationException) {
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (p.lostTopics as MutableSet<String>).add("probe")
            fail("$label lostTopics must be read-only")
        } catch (expected: UnsupportedOperationException) {
        }
    }

    // --- mapping ------------------------------------------------------------------------------------------------------

    /** M01: every registered source in each FX topic maps to its exact server id, keyed by the scope it is mapped for. */
    @Test fun M01_everyFxSourceAndCurrencyMapsToItsServerSeriesId() {
        val t = kst("20:01:00")
        for (ccy in currencies) for (source in sources) {
            val input = batch("fx:$ccy-krw", quote(source, "$ccy-krw", 1342.0, t))
            assertEquals("$source.$ccy", listOf(observation(source, ccy, 1342.0, t)), map(input))
            assertEquals("$source.$ccy other scope", listOf(observation(source, ccy, 1342.0, t, otherScope)), map(input, s = otherScope))
        }
        // sub-second times and every decimal pass through untouched
        val tm = kst("20:01:00.250250")
        assertEquals(listOf(observation("investing", "jpy", 912.3456, tm)), map(batch("fx:jpy-krw", quote("investing", "jpy-krw", 912.3456, tm))))
    }

    /**
     * M02: only the currency tab's 1d allSeries admits a series - a missing tab, a missing 1d period, an id absent from 1d or
     * present only in 1w maps nothing, while defaultVisible plays no part. citi is registered but absent; `toss.usd` is in the
     * catalog but its source is not registered.
     */
    @Test fun M02_onlyTheTabsOneDayCatalogAdmitsASeries() {
        val t = kst("20:01:00")
        val input = batch("fx:usd-krw", quote("hana", "usd-krw", 1342.0, t), quote("kb", "usd-krw", 1341.0, t))
        val both = listOf(observation("hana", "usd", 1342.0, t), observation("kb", "usd", 1341.0, t))
        val kbOnly = listOf(observation("kb", "usd", 1341.0, t))
        assertEquals("current", both, map(input))
        assertEquals("no usd tab", emptyList<GraphObservation>(), map(input, current.copy(tabs = current.tabs - "usd")))
        assertEquals("no 1d period", emptyList<GraphObservation>(), map(input, withUsd(oneDay = null, oneWeek = ids("usd"))))
        assertEquals("hana not in 1d", kbOnly, map(input, withUsd(ids("usd") - "hana.usd")))
        assertEquals("hana only in 1w", kbOnly, map(input, withUsd(ids("usd") - "hana.usd", oneWeek = listOf("hana.usd"))))
        assertEquals("not default visible", both, map(input, withUsd(ids("usd"), visible = listOf("investing.usd"))))
        assertEquals("citi", emptyList<GraphObservation>(), map(batch("fx:usd-krw", quote("citi", "usd-krw", 1342.0, t))))
        assertEquals(
            "citi once listed", listOf(observation("citi", "usd", 1342.0, t)),
            map(batch("fx:usd-krw", quote("citi", "usd-krw", 1342.0, t)), withUsd(ids("usd") + "citi.usd"))
        )
        assertEquals(
            "toss", emptyList<GraphObservation>(),
            map(batch("fx:usd-krw", quote("toss", "usd-krw", 1342.0, t)), withUsd(ids("usd") + "toss.usd"))
        )
    }

    /** M03: the topic decides the asset, and only the three FX topics map - tether, dollar index and other topics give nothing. */
    @Test fun M03_theTopicDecidesTheAssetAndOnlyFxTopicsMap() {
        val t = kst("20:01:00")
        val withGbp = current.copy(tabs = current.tabs + ("gbp" to tab("gbp", ids("gbp"))))
        val cases = listOf(
            "jpy on usd" to map(batch("fx:usd-krw", quote("hana", "jpy-krw", 912.0, t))),
            "usd on jpy" to map(batch("fx:jpy-krw", quote("hana", "usd-krw", 1342.0, t))),
            "krx on usd" to map(batch("fx:usd-krw", quote("krx", "usd-krw-futures", 1342.0, t))),
            "tether" to map(batch("usdt:krw", quote("hana", "usd-krw", 1342.0, t))),
            "dollar index topic" to map(batch("dxy:spot", quote("investing", "usd-krw", 1342.0, t))),
            "other fx topic" to map(batch("fx:gbp-krw", quote("hana", "gbp-krw", 1700.0, t)), withGbp),
            "krx topic" to map(batch("krx:usd-krw-futures", quote("kb", "usd-krw", 1342.0, t))),
            "fx topic look-alike" to map(batch("fx:usd-krw-futures", quote("kb", "usd-krw", 1342.0, t))),
            "asset prefix" to map(batch("fx:usd-krw", quote("kb", "usd-krw-futures", 1342.0, t))),
            "asset case" to map(batch("fx:usd-krw", quote("kb", "USD-KRW", 1342.0, t))),
            "source case" to map(batch("fx:usd-krw", quote("KB", "usd-krw", 1342.0, t))),
            "foreign id in the tab" to map(batch("fx:usd-krw", quote("hana", "jpy-krw", 912.0, t)), withUsd(ids("usd") + "hana.jpy"))
        )
        for ((label, result) in cases) assertEquals(label, emptyList<GraphObservation>(), result)
        assertTrue(fxGraphSeriesIds("usdt:krw", current).isEmpty())
        assertTrue(fxGraphSeriesIds("dxy:spot", current).isEmpty())
        assertTrue(fxGraphSeriesIds("fx:gbp-krw", withGbp).isEmpty())
        assertTrue(fxGraphSeriesIds("krx:usd-krw-futures", current).isEmpty())
        assertTrue(fxGraphSeriesIds("fx:usd-krw-futures", current).isEmpty())
    }

    /** M04: implausible prices and non-quotes drop one by one, keeping the plausible neighbours in order; an empty batch is empty. */
    @Test fun M04_implausiblePricesAndNonQuotesDropAlone() {
        val t = kst("20:01:00")
        val input = batch(
            "fx:usd-krw",
            quote("kb", "usd-krw", 1342.0, t),
            quote("hana", "usd-krw", Double.NaN, t),
            quote("woori", "usd-krw", Double.POSITIVE_INFINITY, t),
            quote("ibk", "usd-krw", Double.NEGATIVE_INFINITY, t),
            quote("nh", "usd-krw", 0.0, t),
            quote("sc", "usd-krw", -1.0, t),
            quote("bs", "usd-krw", 1e9, t),
            TopicGraphCandidate.DollarIndex(104.5, t, "investing"),
            quote("shinhan", "usd-krw", 1343.0, t)
        )
        assertEquals(listOf(observation("kb", "usd", 1342.0, t), observation("shinhan", "usd", 1343.0, t)), map(input))
        assertTrue(map(batch("fx:usd-krw")).isEmpty())

        // the output keeps the batch order: no sorting by source or time, no "newer than" filter per source
        val t2 = kst("20:01:05")
        val unsorted = batch(
            "fx:usd-krw",
            quote("shinhan", "usd-krw", 1343.0, t2),
            quote("hana", "usd-krw", 1342.0, t),
            quote("shinhan", "usd-krw", 1344.0, t),
            quote("investing", "usd-krw", 1341.0, t)
        )
        assertEquals(
            listOf(
                observation("shinhan", "usd", 1343.0, t2),
                observation("hana", "usd", 1342.0, t),
                observation("shinhan", "usd", 1344.0, t),
                observation("investing", "usd", 1341.0, t)
            ),
            map(unsorted)
        )
    }

    // --- time meaning and identity through D1-D3 ----------------------------------------------------------------------

    /**
     * M05 (T17, high and low): an old 19:59 record re-sent in a 20:02 snapshot is a duplicate and opens no B bucket; once B has
     * a record of its own, B holds that record alone. For every source and currency.
     */
    @Test fun M05_T17_aResentOldRecordDoesNotOpenTheNewBucket() {
        for (ccy in currencies) for (source in sources) for (old in listOf(1345.7, 1337.7)) {
            val label = "$source.$ccy $old"
            val topic = "fx:$ccy-krw"
            val asset = "$ccy-krw"
            val oldQuote = quote(source, asset, old, kst("19:59:00"))
            var s = feed(state("$source.$ccy"), kst("19:59:30"), batch(topic, oldQuote)).state
            val resent = feed(s, kst("20:02:00"), batch(topic, listOf(oldQuote), sequence = 2))
            assertEquals(label, listOf(DUPLICATE), resent.outcomes)
            assertNull(label, resent.state.data.app.buckets[b])
            s = feed(resent.state, kst("20:02:10"), batch(topic, listOf(quote(source, asset, 1341.7, kst("20:02:05"))), sequence = 3)).state
            val bucket = s.data.app.buckets.getValue(b)
            assertEquals(label, 1341.7 to 1341.7, bucket.high.rate to bucket.low.rate)
        }
    }

    /**
     * M06 (T18, low and high): with 1342.0 already seen at 19:59, a record of the same price at 20:00:00 is a new observation
     * in B, and the next price widens B on the matching side. For every source and currency.
     */
    @Test fun M06_T18_aNewTimestampOfTheSamePriceIsANewObservation() {
        for (ccy in currencies) for (source in sources) for ((next, expected) in listOf(1341.5 to (1341.5 to 1342.0), 1342.5 to (1342.0 to 1342.5))) {
            val label = "$source.$ccy $next"
            val topic = "fx:$ccy-krw"
            val asset = "$ccy-krw"
            var s = feed(state("$source.$ccy"), kst("19:59:30"), batch(topic, quote(source, asset, 1342.0, kst("19:59:00")))).state
            val same = feed(s, kst("20:00:30"), batch(topic, listOf(quote(source, asset, 1342.0, b)), sequence = 2))
            assertEquals(label, listOf(ADDED), same.outcomes)
            s = feed(same.state, kst("20:01:30"), batch(topic, listOf(quote(source, asset, next, kst("20:01:00"))), sequence = 3)).state
            val bucket = s.data.app.buckets.getValue(b)
            assertEquals(label, expected, bucket.low.rate to bucket.high.rate)
        }
    }

    /**
     * M07 (T19, E1 part): when the FX topic re-sends kb's unchanged 19:59 record alongside hana's update, kb gets no B bucket
     * while hana does. hana's USD, JPY and EUR series stay apart.
     */
    @Test fun M07_T19_anotherSourcesUpdateMakesNoNewObservation() {
        val now = kst("20:02:30")
        val first = batch("fx:usd-krw", quote("kb", "usd-krw", 1342.0, kst("19:59:00")), quote("hana", "usd-krw", 1343.0, kst("19:59:00")))
        val second = batch(
            "fx:usd-krw",
            listOf(quote("kb", "usd-krw", 1342.0, kst("19:59:00")), quote("hana", "usd-krw", 1344.0, kst("20:02:00"))),
            sequence = 2
        )
        val kb = feed(state("kb.usd"), now, first, second).state
        assertEquals(setOf(a), kb.data.app.buckets.keys)
        assertEquals(id("kb", "usd", 1342.0, kst("19:59:00")), kb.data.app.tip)
        val hana = feed(state("hana.usd"), now, first, second).state
        assertEquals(setOf(a, b), hana.data.app.buckets.keys)

        val t = kst("20:01:00")
        val perTopic = mapOf(
            "usd" to batch("fx:usd-krw", quote("hana", "usd-krw", 1342.0, t)),
            "jpy" to batch("fx:jpy-krw", quote("hana", "jpy-krw", 912.0, t)),
            "eur" to batch("fx:eur-krw", quote("hana", "eur-krw", 1561.0, t))
        )
        val rates = mapOf("usd" to 1342.0, "jpy" to 912.0, "eur" to 1561.0)
        for (ccy in currencies) {
            val s = feed(state("hana.$ccy"), now, *currencies.map { perTopic.getValue(it) }.toTypedArray()).state
            assertEquals(ccy, id("hana", ccy, rates.getValue(ccy), t), s.data.app.tip)
            assertEquals(ccy, setOf(b), s.data.app.buckets.keys)
        }
    }

    /**
     * M08: an FX quote is placed by `timestamp` alone - a rateChangedAt of 20:01 does not move a 19:59 record into B - and two
     * records of the same price three seconds apart in one batch are two observations.
     */
    @Test fun M08_timestampAloneDecidesTheObservation() {
        val input = batch("fx:usd-krw", quote("kb", "usd-krw", 1342.0, kst("19:59:00"), changed = kst("20:01:00")))
        assertEquals(listOf(observation("kb", "usd", 1342.0, kst("19:59:00"))), map(input))
        val s = feed(state("kb.usd"), kst("20:01:30"), input).state
        assertEquals(setOf(a), s.data.app.buckets.keys)
        val two = feed(
            s, kst("20:01:30"),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, kst("20:00:01")), quote("kb", "usd-krw", 1342.0, kst("20:00:04"))), 2)
        )
        assertEquals(listOf(ADDED, ADDED), two.outcomes)
        assertEquals(id("kb", "usd", 1342.0, kst("20:00:04")), two.state.data.app.buckets.getValue(b).close)

        // neither an earlier nor a later rateChangedAt is used, for any source, currency or path
        val t = kst("20:01:00")
        for (ccy in currencies) for (source in sources) for (changed in listOf(kst("19:59:00"), kst("20:03:00"))) {
            for ((path, generation) in listOf(TopicGraphPath.WS to 1L, TopicGraphPath.REST_BOOTSTRAP to null)) {
                val one = batch("fx:$ccy-krw", listOf(quote(source, "$ccy-krw", 1342.0, t, changed)), path = path, generation = generation)
                assertEquals("$source.$ccy $changed $path", listOf(observation(source, ccy, 1342.0, t)), map(one))
            }
        }
    }

    /**
     * M09: the same record by WS, by REST bootstrap and by a later WS connection maps to one identity - the path, sequence and
     * connection generation are not part of it - so the repeats are duplicates and change nothing.
     */
    @Test fun M09_redeliveryByAnyPathIsTheSameObservation() {
        val q = quote("kb", "usd-krw", 1342.0, kst("20:01:00"))
        val ws = batch("fx:usd-krw", listOf(q), sequence = 1, path = TopicGraphPath.WS, generation = 1)
        val rest = batch("fx:usd-krw", listOf(q), sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        val later = batch("fx:usd-krw", listOf(q), sequence = 3, path = TopicGraphPath.WS, generation = 2)
        assertEquals(map(ws), map(rest))
        assertEquals(map(ws), map(later))
        val all = feed(state("kb.usd"), kst("20:02:00"), ws, rest, later)
        assertEquals(listOf(ADDED, DUPLICATE, DUPLICATE), all.outcomes)
        val once = feed(state("kb.usd"), kst("20:02:00"), ws).state
        assertEquals(once.data.app.buckets, all.state.data.app.buckets)
        assertEquals(once.data.app.tip, all.state.data.app.tip)
    }

    /** M10: WS 20:04:50/1341.7 and REST 20:04:30/1345.7 in either order give B [1341.7, 1345.7] with close and tip 1341.7. */
    @Test fun M10_reversedWsAndRestKeepTheSameBucket() {
        val ws = batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1341.7, kst("20:04:50"))), sequence = 1, path = TopicGraphPath.WS)
        val rest = batch(
            "fx:usd-krw", listOf(quote("kb", "usd-krw", 1345.7, kst("20:04:30"))),
            sequence = 2, path = TopicGraphPath.REST_BOOTSTRAP, generation = null
        )
        for ((label, order) in listOf("ws first" to listOf(ws, rest), "rest first" to listOf(rest, ws))) {
            val s = feed(state("kb.usd"), kst("20:05:00"), *order.toTypedArray()).state
            val bucket = s.data.app.buckets.getValue(b)
            assertEquals(label, 1345.7 to 1341.7, bucket.high.rate to bucket.low.rate)
            assertEquals(label, id("kb", "usd", 1341.7, kst("20:04:50")), bucket.close)
            assertEquals(label, id("kb", "usd", 1341.7, kst("20:04:50")), s.data.app.tip)
        }
    }

    /** M11: a different price at the same source, asset and time is a conflict kept as range; close and tip stay with the first. */
    @Test fun M11_aSameTimeConflictKeepsTheFirstClose() {
        val t = kst("20:01:00")
        val r = feed(
            state("kb.usd"), kst("20:02:00"),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, t)), 1),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1343.0, t)), 2),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, t)), 3)
        )
        assertEquals(listOf(ADDED, SAME_TIME_CONFLICT, DUPLICATE), r.outcomes)
        val bucket = r.state.data.app.buckets.getValue(b)
        assertEquals(1343.0 to 1342.0, bucket.high.rate to bucket.low.rate)
        assertEquals(id("kb", "usd", 1342.0, t), bucket.close)
        assertEquals(id("kb", "usd", 1342.0, t), r.state.data.app.tip)

        // inside one batch E1 removes nothing: the repeat and the conflict reach D1, which tells them apart
        val q = quote("kb", "usd-krw", 1342.0, t)
        val inOne = batch("fx:usd-krw", q, q, quote("kb", "usd-krw", 1343.0, t))
        assertEquals(
            listOf(observation("kb", "usd", 1342.0, t), observation("kb", "usd", 1342.0, t), observation("kb", "usd", 1343.0, t)),
            map(inOne)
        )
        assertEquals(listOf(ADDED, DUPLICATE, SAME_TIME_CONFLICT), feed(state("kb.usd"), kst("20:02:00"), inOne).outcomes)
    }

    /** M12: the FX series set is exact ids of registered sources in the tab's 1d list - not dxy, krx or `toss.usd`, not visibility. */
    @Test fun M12_theFxSeriesSetIsExactAndIndependentOfVisibility() {
        val extras = withUsd(
            ids("usd") + "dxy" + "krx.usd-krw-futures" + "toss.usd" + "hana.jpy" + "kb.usdt" + "investing.usd-krw",
            visible = emptyList()
        )
        assertEquals(ids("usd").toSet(), fxGraphSeriesIds("fx:usd-krw", extras))
        assertEquals(ids("jpy").toSet(), fxGraphSeriesIds("fx:jpy-krw", current))
        assertEquals(ids("eur").toSet(), fxGraphSeriesIds("fx:eur-krw", current))
        assertEquals((ids("usd") - "hana.usd").toSet(), fxGraphSeriesIds("fx:usd-krw", withUsd(ids("usd") - "hana.usd")))
        assertEquals(
            "only in 1w", (ids("usd") - "hana.usd").toSet(),
            fxGraphSeriesIds("fx:usd-krw", withUsd(ids("usd") - "hana.usd", oneWeek = listOf("hana.usd")))
        )
        assertTrue("no 1d", fxGraphSeriesIds("fx:usd-krw", withUsd(oneDay = null, oneWeek = ids("usd"))).isEmpty())
        val noUsd = current.copy(tabs = current.tabs - "usd")
        assertTrue("no usd tab, though the tether tab lists kb.usd", fxGraphSeriesIds("fx:usd-krw", noUsd).isEmpty())
        assertEquals(ids("jpy").toSet(), fxGraphSeriesIds("fx:jpy-krw", noUsd))
        assertEquals((ids("usd") + "citi.usd").toSet(), fxGraphSeriesIds("fx:usd-krw", withUsd(ids("usd") + "citi.usd")))
        assertTrue(fxGraphSeriesIds("fx:usd-krw", current.copy(tabs = emptyMap())).isEmpty())
    }

    // --- holding before the catalog -----------------------------------------------------------------------------------

    /**
     * M13: units are candidates of any kind plus continuity facts. 300 FX quotes + 1 fact + 211 dollar-index candidates = 512
     * stay; one more fact evicts the oldest input whole and records its topic. A single 513-unit input is not kept and only its
     * topic is recorded; an empty batch is not kept; the inputs are the offered objects in order; the argument value does not
     * change; recorded losses survive later offers; an evicted fact records every topic it named; the collections are read-only.
     */
    @Test fun M13_holdingIsBoundedByUnitsAndRecordsWhatLeaves() {
        val fx300 = quotes(300)
        val fact1 = fact(TopicGraphEventKind.DELIVERY_INTERRUPTED, setOf("fx:jpy-krw"), 2)
        val dxy211 = batch("dxy:spot", (0 until 211).map { TopicGraphCandidate.DollarIndex(104.0 + it * 0.001, plusMillis(b, it.toLong()), "investing") })
        val p1 = offer(GraphPendingInputs.EMPTY, fx300)
        val p2 = offer(p1, fact1)
        val full = offer(p2, dxy211)
        assertSameInputs("512", listOf(fx300, fact1, dxy211), full.inputs)
        assertTrue(full.lostTopics.isEmpty())

        val fact2 = fact(TopicGraphEventKind.DELIVERY_RESUMED, setOf("usdt:krw", "krx:usd-krw-futures"), 3)
        val over = offer(full, fact2)
        assertSameInputs("513", listOf(fact1, dxy211, fact2), over.inputs)
        assertEquals(setOf("fx:usd-krw"), over.lostTopics)
        assertSameInputs("argument unchanged", listOf(fx300, fact1, dxy211), full.inputs)
        assertTrue(full.lostTopics.isEmpty())

        val big = quotes(513, "fx:eur-krw")
        val afterBig = offer(over, big)
        assertSameInputs("single big input", listOf(fact1, dxy211, fact2), afterBig.inputs)
        assertEquals(setOf("fx:usd-krw", "fx:eur-krw"), afterBig.lostTopics)
        val bigAlone = offer(GraphPendingInputs.EMPTY, quotes(513))
        assertTrue(bigAlone.inputs.isEmpty())
        assertEquals(setOf("fx:usd-krw"), bigAlone.lostTopics)

        val afterEmpty = offer(afterBig, batch("fx:jpy-krw"))
        assertSameInputs("empty batch", listOf(fact1, dxy211, fact2), afterEmpty.inputs)
        assertEquals(afterBig.lostTopics, afterEmpty.lostTopics)

        val one = quotes(1, "fx:jpy-krw")
        val afterOne = offer(afterBig, one)
        assertSameInputs("normal offer", listOf(fact1, dxy211, fact2, one), afterOne.inputs)
        assertEquals(setOf("fx:usd-krw", "fx:eur-krw"), afterOne.lostTopics)

        // 214 + 299 = 513 units: only fact1 (1 unit) leaves, and its topic is recorded
        val evictsFact1 = offer(afterOne, quotes(299, "fx:jpy-krw"))
        assertEquals(setOf("fx:usd-krw", "fx:eur-krw", "fx:jpy-krw"), evictsFact1.lostTopics)
        // 512 + 212 = 724 units: dxy211 leaves (513), then fact2 (512), each recording every topic it named
        val evictsTwo = offer(evictsFact1, quotes(212, "fx:eur-krw"))
        assertEquals(
            setOf("fx:usd-krw", "fx:eur-krw", "fx:jpy-krw", "dxy:spot", "usdt:krw", "krx:usd-krw-futures"),
            evictsTwo.lostTopics
        )
        assertEquals(3, evictsTwo.inputs.size)

        // an input of exactly 512 units is kept: it pushes everything else out
        val exact = quotes(512, "fx:jpy-krw")
        val afterExact = offer(over, exact)
        assertSameInputs("exactly 512", listOf(exact), afterExact.inputs)
        assertEquals(setOf("fx:usd-krw", "fx:jpy-krw", "dxy:spot", "usdt:krw", "krx:usd-krw-futures"), afterExact.lostTopics)
        assertSameInputs("exactly 512 alone", listOf(exact), offer(GraphPendingInputs.EMPTY, exact).inputs)

        // a later fact or snapshot for a lost topic does not take the loss back
        for (kind in listOf(
            TopicGraphEventKind.DELIVERY_RESUMED,
            TopicGraphEventKind.INITIAL,
            TopicGraphEventKind.ACCESS_RESUMED,
            TopicGraphEventKind.AUTH_RECOVERED
        )) {
            val resumedUsd = offer(over, fact(kind, setOf("fx:usd-krw"), 10))
            assertEquals(kind.name, setOf("fx:usd-krw"), resumedUsd.lostTopics)
        }
        val restUsd = offer(
            over,
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, b)), sequence = 12, path = TopicGraphPath.REST_BOOTSTRAP, generation = null)
        )
        assertEquals(setOf("fx:usd-krw"), restUsd.lostTopics)

        // every earlier value stays what it was
        assertSameInputs("p1 unchanged", listOf(fx300), p1.inputs)
        assertSameInputs("p2 unchanged", listOf(fx300, fact1), p2.inputs)
        assertSameInputs("over unchanged", listOf(fact1, dxy211, fact2), over.inputs)
        assertEquals(setOf("fx:usd-krw"), over.lostTopics)
        assertSameInputs("afterBig unchanged", listOf(fact1, dxy211, fact2), afterBig.inputs)
        assertEquals(setOf("fx:usd-krw", "fx:eur-krw"), afterBig.lostTopics)

        for ((label, p) in listOf(
            "empty" to GraphPendingInputs.EMPTY,
            "over" to over,
            "afterBig" to afterBig,
            "bigAlone" to bigAlone,
            "afterOne" to afterOne,
            "evictsTwo" to evictsTwo,
            "afterExact" to afterExact
        )) {
            assertReadOnly(label, p)
        }
    }

    /**
     * M13c: units are counted, not judged - duplicates count twice, the same object offered twice is held twice, and an
     * implausible quote or a dollar-index candidate takes a unit like any other. A recorded loss does not follow a later change
     * to the set a fact was built with.
     */
    @Test fun M13c_unitsAreCountedNotJudged() {
        val q = quote("kb", "usd-krw", 1342.0, b)
        val dup = batch("fx:usd-krw", listOf(q, q), sequence = 2)
        val p = offer(offer(GraphPendingInputs.EMPTY, quotes(511)), dup)
        assertSameInputs("duplicates count twice", listOf(dup), p.inputs)
        assertEquals(setOf("fx:usd-krw"), p.lostTopics)
        val one = quotes(1)
        assertSameInputs("same object twice", listOf(one, one), offer(offer(GraphPendingInputs.EMPTY, one), one).inputs)

        val padded = batch(
            "fx:usd-krw",
            buildList<TopicGraphCandidate> {
                repeat(511) { add(quote("kb", "usd-krw", 1340.0 + it * 0.01, plusMillis(b, it.toLong()))) }
                add(quote("hana", "usd-krw", Double.NaN, b))
                add(TopicGraphCandidate.DollarIndex(104.0, b, "investing"))
            }
        )
        val oversize = offer(GraphPendingInputs.EMPTY, padded)
        assertTrue(oversize.inputs.isEmpty())
        assertEquals(setOf("fx:usd-krw"), oversize.lostTopics)

        val named = mutableSetOf("fx:jpy-krw", "dxy:spot")
        val namedFact = TopicGraphInput.Continuity(9, TopicGraphEventKind.DELIVERY_INTERRUPTED, null, named, setOf(TopicGraphPath.WS), authority, 1, 1_000L)
        val pushed = offer(offer(GraphPendingInputs.EMPTY, namedFact), quotes(512))
        assertEquals(setOf("fx:jpy-krw", "dxy:spot"), pushed.lostTopics)
        named.add("usdt:krw")
        assertEquals(setOf("fx:jpy-krw", "dxy:spot"), pushed.lostTopics)
    }

    /**
     * M13b: the holding keeps every input as it came - every continuity kind, a fact naming no topic, batches of any topic
     * (tether, KRX, an unknown FX topic), the same record by three paths, and records a day or two apart in either order
     * (no expiry of any kind).
     */
    @Test fun M13b_holdingKeepsEveryInputAsItCame() {
        val facts = TopicGraphEventKind.values().mapIndexed { i, kind -> fact(kind, setOf("fx:usd-krw"), i + 1L) }
        val noTopics = TopicGraphInput.Continuity(
            100, TopicGraphEventKind.AUTHORITY_ENDED, TopicGraphEventReason.STOPPED, emptySet(), emptySet(), authority, null, 5_000L
        )
        val q = quote("kb", "usd-krw", 1342.0, kst("20:01:00"))
        val all = facts + listOf<TopicGraphInput>(
            fact(facts.last().kind, facts.last().topics, 99),
            noTopics,
            batch("usdt:krw", listOf(quote("bithumb", "usdt-krw", 1390.0, b)), sequence = 101),
            batch("krx:usd-krw-futures", listOf(quote("krx", "usd-krw-futures", 1342.0, b)), sequence = 102),
            batch("fx:gbp-krw", listOf(quote("hana", "gbp-krw", 1700.0, b)), sequence = 103),
            batch("fx:usd-krw", listOf(q), sequence = 201),
            batch("fx:usd-krw", listOf(q), sequence = 202, path = TopicGraphPath.REST_BOOTSTRAP, generation = null),
            batch("fx:usd-krw", listOf(q), sequence = 203, generation = 2),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1343.0, at(8, "20:01:00"))), sequence = 300),
            batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, at(6, "20:01:00"))), sequence = 301),
            fact(TopicGraphEventKind.DELIVERY_RESUMED, setOf("fx:jpy-krw"), 302, occurredAt = 1_000L + 48 * 3_600_000L),
            fact(TopicGraphEventKind.DELIVERY_INTERRUPTED, setOf("fx:jpy-krw"), 303, occurredAt = 1_000L)
        )
        val held = all.fold(GraphPendingInputs.EMPTY) { p, input -> offer(p, input) }
        assertSameInputs("everything", all, held.inputs)
        assertTrue(held.lostTopics.isEmpty())
    }

    /**
     * M14: inputs held before the catalog - INITIAL, INTERRUPTED, RESUMED and two quote batches - stay as the same objects in
     * the same order with nothing lost, and replaying the batches gives the D3 state that applying them on arrival gives.
     */
    @Test fun M14_heldInputsReplayAsTheyArrived() {
        val topics = setOf("fx:usd-krw")
        val initial = fact(TopicGraphEventKind.INITIAL, topics, 1, occurredAt = 1_000L)
        val interrupted = fact(TopicGraphEventKind.DELIVERY_INTERRUPTED, topics, 2, occurredAt = 2_000L)
        val resumed = fact(TopicGraphEventKind.DELIVERY_RESUMED, topics, 3, occurredAt = 3_000L)
        val first = batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1342.0, kst("20:01:00")), quote("hana", "usd-krw", 1343.0, kst("20:01:00"))), 4)
        val second = batch("fx:usd-krw", listOf(quote("kb", "usd-krw", 1341.5, kst("20:02:00"))), 5)
        val arrived = listOf(initial, interrupted, resumed, first, second)
        val held = arrived.fold(GraphPendingInputs.EMPTY) { p, input -> offer(p, input) }
        assertSameInputs("held", arrived, held.inputs)
        assertTrue(held.lostTopics.isEmpty())

        val now = kst("20:03:00")
        val replayed = feed(state("kb.usd"), now, *held.inputs.filterIsInstance<TopicGraphInput.Observations>().toTypedArray()).state
        val live = feed(state("kb.usd"), now, first, second).state
        assertEquals(live.data.app.buckets, replayed.data.app.buckets)
        assertEquals(live.data.app.tip, replayed.data.app.tip)
    }

    /**
     * M15 (E1 -> D3): a quote held past the data window is rejected at replay with no bucket, tip, demand or recorded loss. At
     * device 20:07, 20:10:00 enters C while 20:20:00 is rejected with TIME_ANOMALY on B, its time uncorrected.
     */
    @Test fun M15_replayedTimesAreJudgedByD3() {
        val old = batch("fx:usd-krw", quote("kb", "usd-krw", 1342.0, kst("20:01:00")))
        val held = offer(GraphPendingInputs.EMPTY, old)
        val replay = observeRecoverable(state("kb.usd"), map(held.inputs.single() as TopicGraphInput.Observations), at(7, "21:12:00"))
        assertEquals(listOf(REJECTED), replay.outcomes)
        assertTrue(replay.state.data.app.buckets.isEmpty())
        assertNull(replay.state.data.app.tip)
        assertTrue(replay.state.pending.isEmpty())
        assertEquals(0L, replay.state.generation)
        assertTrue(held.lostTopics.isEmpty())

        // E1 judges no time: a far past and a far future quote are mapped as they are and left to D3
        val past = Instant.parse("2000-01-01T00:00:00Z")
        val far = Instant.parse("2999-01-01T00:00:00Z")
        val t = kst("20:01:00")
        assertEquals(
            listOf(observation("kb", "usd", 1342.0, t), observation("hana", "usd", 1343.0, far), observation("woori", "usd", 1344.0, past)),
            map(batch("fx:usd-krw", quote("kb", "usd-krw", 1342.0, t), quote("hana", "usd-krw", 1343.0, far), quote("woori", "usd-krw", 1344.0, past)))
        )

        val future = batch("fx:usd-krw", quote("kb", "usd-krw", 1342.0, c), quote("kb", "usd-krw", 1344.0, kst("20:20:00")))
        assertEquals(kst("20:20:00"), map(future)[1].id.observedAt)
        val r = feed(state("kb.usd"), kst("20:07:00"), future)
        assertEquals(listOf(ADDED, REJECTED), r.outcomes)
        assertEquals(setOf(c), r.state.data.app.buckets.keys)
        assertEquals(mapOf(b to setOf(TIME_ANOMALY)), r.state.pending.mapValues { it.value.reasons })
    }
}
