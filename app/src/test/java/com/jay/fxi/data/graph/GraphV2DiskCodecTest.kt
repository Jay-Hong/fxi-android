package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 B1a-1 contract r2 (r1 battery survivors R16·R24·R25·R26·R27 closed by stored-value fixtures): a validated server Graph V2 tab split into its general and KRX disk components,
 * encoded, decoded against the exact key, and joined back. r3 (C1-2a review): a stored series without its axis group is
 * refused - the server never sends one, and the premium screen needs it for every toggle.
 *
 * Oracles: ANDROID_V2_PLAN.md S4 :1288-1294 (general key (uid, userAccessEpoch, tab, period); KRX series only under
 * (uid, userAccessEpoch, krxCapabilityEpoch, tab, period); only validated, filtered, namespace-separated server data on
 * disk; no app observation, display range, recovery state, freshness or grant); iOS a36682f GraphV2ViewModel.swift
 * :316-327 (KRX series by the `krx.` prefix, seeds with them) and CacheService.swift :119-140 (the tab stored as served).
 * Design: R4c/S4 b1_design_codex.r1 as trimmed by b1_review_claude.r1 (T1: no source/contract_code reclassification;
 * T2) and b1_verdict_codex.r1 §2 (API, JSON keys, split/codec/join rules). No file I/O, gate or A2 wiring here.
 */
class GraphV2DiskCodecTest {

    private val t0 = Instant.parse("2026-10-05T03:00:00Z")
    private val gkey = GraphV2GeneralKey("u1", "e1", "usd", "3m")
    private fun krxKey(epoch: String = "k1") = GraphV2KrxKey("u1", "e1", epoch, "usd", "3m")
    private val codec: GraphV2EnvelopeCodec = JsonGraphV2EnvelopeCodec()

    private fun series(
        id: String,
        rate: Double = 1390.0,
        source: String = "hana",
        contract: String? = null,
        empty: Boolean = false,
        insufficient: Boolean = false,
        carry: Boolean = true
    ) = FreeGraphSeries(
        seriesId = id,
        points = if (empty) emptyList() else listOf(
            FreeGraphPoint(t0 - 1.days, rate, rate + 1, rate - 1, source, "hana_observed_eod", "observed_rollup", contract),
            FreeGraphPoint(t0, rate + 2, null, null, source)
        ),
        label = "L-$id", axisGroup = "krw", unit = "KRW", decimals = 2,
        insufficientHistory = insufficient, perPointMetadata = listOf("close_basis", "source_method"),
        carryIn = if (carry) FreeGraphCarryIn(rate - 5, t0 - 3.days) else null
    )

    private fun seed(close: Double = 1391.0, high: Double = close + 1, low: Double = close - 1) =
        GraphV2InProgress(t0, high, low, close, t0 + 30.seconds)

    private fun tab(
        series: List<FreeGraphSeries>,
        inProgress: Map<String, GraphV2InProgress> = emptyMap(),
        domainStart: Instant? = t0 - 90.days,
        domainEnd: Instant? = t0,
        mode: String? = "fixed_start",
        period: GraphPeriod = GraphPeriod.THREE_MONTHS,
        tabId: String = "usd"
    ) = GraphV2Tab(
        tabId, period, "1d", t0 - 1.hours,
        FreeGraph("1d", series, "2026-07-05", "2026-10-05", domainStart, domainEnd, mode),
        inProgress
    )

    private fun <T> valid(v: GraphV2Validation<T>): T =
        (v as? GraphV2Validation.Valid)?.value ?: throw AssertionError("expected Valid, got $v")

    private fun invalid(label: String, v: GraphV2Validation<*>) =
        assertTrue("$label: expected Invalid, got $v", v is GraphV2Validation.Invalid)

    private fun split(t: GraphV2Tab, epoch: String? = "k1", responseId: String = "r1", catalog: GraphCatalog? = null) =
        valid(splitGraphV2ServerTab(t, gkey, epoch, responseId, catalog))

    private fun roundTrip(t: GraphV2Tab, epoch: String? = "k1"): GraphV2ComponentJoin {
        val c = split(t, epoch)
        val g = valid(codec.decodeGeneral(valid(codec.encodeGeneral(c.general)), gkey, null))
        val k = c.krx?.let { valid(codec.decodeKrx(valid(codec.encodeKrx(it)), it.key, null)) }
        return joinGraphV2Components(g, k)
    }

    // --- JSON surgery ------------------------------------------------------------------------------

    private fun obj(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    private fun bytes(e: JsonElement): ByteArray = e.toString().encodeToByteArray()
    private fun JsonObject.with(k: String, v: JsonElement?): JsonObject = JsonObject(if (v == null) this - k else this + (k to v))
    private fun JsonObject.edit(k: String, f: (JsonObject) -> JsonElement): JsonObject = with(k, f(getValue(k).jsonObject))
    private fun JsonObject.editSeries(i: Int, f: (JsonObject) -> JsonElement): JsonObject = edit("component") { c ->
        val list = c.getValue("series").jsonArray.toMutableList()
        list[i] = f(list[i].jsonObject)
        c.with("series", JsonArray(list))
    }

    // --- B01-a round trip and stored shape ----------------------------------------------------------

    /** Split, encode, decode against the exact key and join: the validated server tab comes back unchanged. */
    @Test fun B01a_aServerTabSurvivesTheRoundTrip() {
        val original = tab(
            listOf(series("hana.usd-krw"), series("krx.usd-krw-futures", 1391.2, "krx", "A75609"), series("investing.usd-krw", 1389.0)),
            mapOf("hana.usd-krw" to seed(), "krx.usd-krw-futures" to seed(1392.0))
        )
        val joined = roundTrip(original)
        assertTrue(joined.krxJoined)
        assertNull(joined.ignoredKrxReason)
        assertEquals(original, joined.tab)
    }

    /** The stored JSON has exactly the agreed keys, and nothing of the app's own state. */
    @Test fun B01a_theStoredJsonHasExactlyTheAgreedKeys() {
        val c = split(tab(listOf(series("hana.usd-krw", contract = "C1")), mapOf("hana.usd-krw" to seed())))
        val root = obj(valid(codec.encodeGeneral(c.general)))
        assertEquals(setOf("schema_version", "key", "response_id", "component"), root.keys)
        assertEquals(JsonPrimitive(1), root.getValue("schema_version"))
        assertEquals(setOf("uid", "user_access_epoch", "tab", "period"), root.getValue("key").jsonObject.keys)
        val component = root.getValue("component").jsonObject
        assertEquals(setOf("metadata", "series", "in_progress"), component.keys)
        assertEquals(
            setOf("bucket_size", "fetched_at", "range_start", "range_end", "domain_start_at", "domain_end_at", "live_domain_mode"),
            component.getValue("metadata").jsonObject.keys
        )
        val indexed = component.getValue("series").jsonArray.single().jsonObject
        assertEquals(setOf("ordinal", "series"), indexed.keys)
        val s = indexed.getValue("series").jsonObject
        assertEquals(
            setOf("id", "label", "axis_group", "unit", "decimals", "points", "insufficient_history", "per_point_metadata", "carry_in"),
            s.keys
        )
        assertEquals(
            setOf("timestamp", "rate", "high", "low", "source", "close_basis", "source_method", "contract_code"),
            s.getValue("points").jsonArray.first().jsonObject.keys
        )
        assertEquals(setOf("rate", "observed_at"), s.getValue("carry_in").jsonObject.keys)
        assertEquals(
            setOf("bucket_start", "high", "low", "close", "sampled_at"),
            component.getValue("in_progress").jsonObject.getValue("hana.usd-krw").jsonObject.keys
        )
        val k = obj(valid(codec.encodeKrx(checkNotNull(c.krx))))
        assertEquals(setOf("uid", "user_access_epoch", "krx_capability_epoch", "tab", "period"), k.getValue("key").jsonObject.keys)
    }

    // --- B01-b ordinary edges ------------------------------------------------------------------------

    /** Empty and partial answers, a flat seed and a reversed server domain are ordinary data and come back as they were. */
    @Test fun B01b_ordinaryEdgesSurviveUnchanged() {
        val cases = listOf(
            "empty tab" to tab(emptyList()),
            "empty points and insufficient history" to tab(listOf(series("hana.usd-krw", empty = true, insufficient = true, carry = false))),
            "partial series and an empty KRX series" to tab(listOf(series("hana.usd-krw"), series("krx.usd-krw-futures", empty = true))),
            "flat seed" to tab(listOf(series("hana.usd-krw")), mapOf("hana.usd-krw" to seed(1391.0, 1391.0, 1391.0))),
            "reversed server domain" to tab(listOf(series("hana.usd-krw")), domainStart = t0, domainEnd = t0 - 90.days),
            "no domain" to tab(listOf(series("hana.usd-krw")), domainStart = null, domainEnd = null, mode = null),
            "1d with seeds" to tab(listOf(series("hana.usd-krw")), mapOf("hana.usd-krw" to seed()), mode = "rolling", period = GraphPeriod.ONE_DAY)
        )
        for ((label, original) in cases) {
            val key = gkey.copy(period = original.period.code)
            val c = valid(splitGraphV2ServerTab(original, key, "k1", "r1", null))
            val g = valid(codec.decodeGeneral(valid(codec.encodeGeneral(c.general)), key, null))
            val k = c.krx?.let { valid(codec.decodeKrx(valid(codec.encodeKrx(it)), it.key, null)) }
            assertEquals(label, original, joinGraphV2Components(g, k).tab)
        }
    }

    // --- B01-c refusals ------------------------------------------------------------------------------

    /** Damage, a missing or unknown schema, any key element differing, and broken structure are refused, never defaulted. */
    @Test fun B01c_damagedOrMismatchedEnvelopesAreRefused() {
        val c = split(tab(listOf(series("hana.usd-krw"), series("investing.usd-krw"), series("krx.usd-krw-futures")),
            mapOf("hana.usd-krw" to seed(), "krx.usd-krw-futures" to seed())))
        val good = valid(codec.encodeGeneral(c.general))
        val goodKrx = valid(codec.encodeKrx(checkNotNull(c.krx)))
        val root = obj(good)
        valid(codec.decodeGeneral(good, gkey, null))

        invalid("not json", codec.decodeGeneral("{".encodeToByteArray(), gkey, null))
        invalid("schema missing", codec.decodeGeneral(bytes(root.with("schema_version", null)), gkey, null))
        invalid("schema 2", codec.decodeGeneral(bytes(root.with("schema_version", JsonPrimitive(2))), gkey, null))
        invalid("response id missing", codec.decodeGeneral(bytes(root.with("response_id", null)), gkey, null))
        for (other in listOf(gkey.copy(uid = "u2"), gkey.copy(userAccessEpoch = "e2"), gkey.copy(tab = "jpy"), gkey.copy(period = "1y"))) {
            invalid("key $other", codec.decodeGeneral(good, other, null))
        }
        for (other in listOf(krxKey("k2"), krxKey().copy(uid = "u2"), krxKey().copy(userAccessEpoch = "e2"), krxKey().copy(tab = "jpy"), krxKey().copy(period = "1y"))) {
            invalid("krx key $other", codec.decodeKrx(goodKrx, other, null))
        }
        invalid("general file read as KRX", codec.decodeKrx(good, krxKey(), null))
        invalid("negative ordinal", codec.decodeGeneral(bytes(root.editSeries(0) { it.with("ordinal", JsonPrimitive(-1)) }), gkey, null))
        val firstOrdinal = root.getValue("component").jsonObject.getValue("series").jsonArray[0].jsonObject.getValue("ordinal")
        invalid("duplicate ordinal", codec.decodeGeneral(bytes(root.editSeries(1) { it.with("ordinal", firstOrdinal) }), gkey, null))
        invalid("duplicate id", codec.decodeGeneral(bytes(root.editSeries(1) { s ->
            s.edit("series") { it.with("id", JsonPrimitive("hana.usd-krw")) } }), gkey, null))
        invalid("KRX id in the general component", codec.decodeGeneral(bytes(root.editSeries(1) { s ->
            s.edit("series") { it.with("id", JsonPrimitive("krx.other")) } }), gkey, null))
        invalid("general id in the KRX component", codec.decodeKrx(bytes(obj(goodKrx).editSeries(0) { s ->
            s.edit("series") { it.with("id", JsonPrimitive("hana.other")) } }), krxKey(), null))
        invalid("orphan seed", codec.decodeGeneral(bytes(root.edit("component") { comp ->
            comp.edit("in_progress") { ip -> ip.with("ghost.series", ip.getValue("hana.usd-krw")) } }), gkey, null))
        // Each stored-value rule on its own: the second point has no high/low, so only the price rule can refuse it.
        fun editPoint(i: Int, f: (JsonObject) -> JsonElement) = root.editSeries(0) { s ->
            s.edit("series") { ser ->
                val points = ser.getValue("points").jsonArray.toMutableList()
                points[i] = f(points[i].jsonObject)
                ser.with("points", JsonArray(points))
            } }
        invalid("implausible stored price", codec.decodeGeneral(bytes(editPoint(1) { it.with("rate", JsonPrimitive(-1.0)) }), gkey, null))
        invalid("high under the rate", codec.decodeGeneral(bytes(editPoint(0) { it.with("high", JsonPrimitive(1380.0)) }), gkey, null))
        invalid("decimals out of range", codec.decodeGeneral(bytes(root.editSeries(0) { s ->
            s.edit("series") { it.with("decimals", JsonPrimitive(9)) } }), gkey, null))
        invalid("axis group null", codec.decodeGeneral(bytes(root.editSeries(0) { s ->
            s.edit("series") { it.with("axis_group", JsonNull) } }), gkey, null))
        invalid("axis group null in the KRX component", codec.decodeKrx(bytes(obj(goodKrx).editSeries(0) { s ->
            s.edit("series") { it.with("axis_group", JsonNull) } }), krxKey(), null))
        invalid("carry-in not before the points", codec.decodeGeneral(bytes(root.editSeries(0) { s ->
            s.edit("series") { ser -> ser.edit("carry_in") { it.with("observed_at", JsonPrimitive(t0.toString())) } } }), gkey, null))
        invalid("seed close above its high", codec.decodeGeneral(bytes(root.edit("component") { comp ->
            comp.edit("in_progress") { ip -> ip.edit("hana.usd-krw") { it.with("close", JsonPrimitive(1500.0)) } } }), gkey, null))
    }

    /** A key with an empty element cannot be split or encoded, and a tab that is not the key's tab and period is refused. */
    @Test fun B01c_keysMustBeCompleteAndMatchTheTab() {
        val t = tab(listOf(series("hana.usd-krw")))
        for (bad in listOf(gkey.copy(uid = ""), gkey.copy(userAccessEpoch = ""), gkey.copy(tab = ""), gkey.copy(period = ""))) {
            invalid("blank $bad", splitGraphV2ServerTab(t, bad, "k1", "r1", null))
        }
        invalid("blank response id", splitGraphV2ServerTab(t, gkey, "k1", "", null))
        invalid("blank capability epoch", splitGraphV2ServerTab(t, gkey, "", "r1", null))
        invalid("other tab", splitGraphV2ServerTab(t, gkey.copy(tab = "jpy"), "k1", "r1", null))
        invalid("other period", splitGraphV2ServerTab(t, gkey.copy(period = "1y"), "k1", "r1", null))
    }

    // --- B09 the split ---------------------------------------------------------------------------------

    /**
     * `krx.` series leave whole — points, carry-in, provenance and seed — and keep their original ordinals. A general
     * series is never reclassified by its source or contract code (T1).
     */
    @Test fun B09_krxSeriesLeaveWholeAndKeepTheirOrdinals() {
        val g0 = series("hana.usd-krw", source = "krx", contract = "A75609")
        val k1 = series("krx.usd-krw-futures", 1391.2, "krx", "A75609")
        val g2 = series("investing.usd-krw", 1389.0)
        val k3 = series("krx.usd-krw-night", 1391.5, "krx", "A75610")
        val seeds = mapOf("hana.usd-krw" to seed(), "krx.usd-krw-futures" to seed(1392.0), "investing.usd-krw" to seed(1390.0), "krx.usd-krw-night" to seed(1393.0))
        val c = split(tab(listOf(g0, k1, g2, k3), seeds))

        assertEquals(listOf(0, 2), c.general.component.series.map { it.ordinal })
        assertEquals(listOf(g0, g2), c.general.component.series.map { it.series })
        assertEquals(setOf("hana.usd-krw", "investing.usd-krw"), c.general.component.inProgress.keys)
        val krx = checkNotNull(c.krx)
        assertEquals(krxKey(), krx.key)
        assertEquals(listOf(1, 3), krx.component.series.map { it.ordinal })
        assertEquals(listOf(k1, k3), krx.component.series.map { it.series })
        assertEquals(setOf("krx.usd-krw-futures", "krx.usd-krw-night"), krx.component.inProgress.keys)
        assertEquals("r1", c.general.responseId)
        assertEquals("r1", krx.responseId)
        assertEquals(GRAPH_V2_DISK_SCHEMA_VERSION, c.general.schemaVersion)
        assertEquals(c.general.component.metadata, krx.component.metadata)
    }

    /** Without a capability epoch there is no KRX component, and KRX series do not fall into the general one. */
    @Test fun B09_withoutACapabilityEpochNoKrxIsKept() {
        val c = split(tab(listOf(series("hana.usd-krw"), series("krx.usd-krw-futures")),
            mapOf("hana.usd-krw" to seed(), "krx.usd-krw-futures" to seed())), epoch = null)
        assertNull(c.krx)
        assertEquals(listOf("hana.usd-krw"), c.general.component.series.map { it.series.seriesId })
        assertEquals(setOf("hana.usd-krw"), c.general.component.inProgress.keys)
    }

    /** With a catalog, only the series it lists for this tab and period are stored, with their seeds. */
    @Test fun B09_aCatalogLimitsWhatIsStored() {
        val catalog = GraphCatalog(3600.seconds, mapOf("usd" to GraphCatalogTab("usd", "usd", emptyMap(),
            mapOf(GraphPeriod.THREE_MONTHS to GraphCatalogPeriod(listOf("hana.usd-krw", "krx.usd-krw-futures"), emptyList())))))
        val c = split(tab(listOf(series("hana.usd-krw"), series("krx.usd-krw-futures"), series("investing.usd-krw"), series("krx.usd-krw-night")),
            mapOf("investing.usd-krw" to seed(), "krx.usd-krw-night" to seed(), "hana.usd-krw" to seed())), catalog = catalog)
        assertEquals(listOf("hana.usd-krw"), c.general.component.series.map { it.series.seriesId })
        assertEquals(setOf("hana.usd-krw"), c.general.component.inProgress.keys)
        assertEquals(listOf("krx.usd-krw-futures"), checkNotNull(c.krx).component.series.map { it.series.seriesId })
        assertEquals(emptySet<String>(), c.krx!!.component.inProgress.keys)
    }

    // --- the response pair -----------------------------------------------------------------------------

    /** A KRX component joins only its own response: another response, other metadata, a clashing ordinal or another account gives the general part alone. */
    @Test fun ResponsePair_onlyTheSameResponseJoins() {
        val t = tab(listOf(series("hana.usd-krw"), series("krx.usd-krw-futures"), series("investing.usd-krw")),
            mapOf("hana.usd-krw" to seed(), "krx.usd-krw-futures" to seed(1392.0)))
        val c = split(t)
        val krx = checkNotNull(c.krx)
        val generalOnly = GraphV2Tab(t.tab, t.period, t.bucketSize, t.fetchedAt,
            t.graph.copy(series = t.graph.series.filter { !it.seriesId.startsWith("krx.") }),
            t.inProgress.filterKeys { !it.startsWith("krx.") })

        val good = joinGraphV2Components(c.general, krx)
        assertTrue(good.krxJoined)
        assertEquals(t, good.tab)

        for ((label, other) in listOf(
            "another response" to krx.copy(responseId = "r2"),
            "other metadata" to krx.copy(component = krx.component.copy(metadata = krx.component.metadata.copy(fetchedAt = t0))),
            "clashing ordinal" to krx.copy(component = krx.component.copy(series = krx.component.series.map { it.copy(ordinal = 0) })),
            "another account" to krx.copy(key = krx.key.copy(uid = "u2")),
            "another user epoch" to krx.copy(key = krx.key.copy(userAccessEpoch = "e2")),
            "another tab" to krx.copy(key = krx.key.copy(tab = "jpy"))
        )) {
            val joined = joinGraphV2Components(c.general, other)
            assertFalse(label, joined.krxJoined)
            assertNotNull(label, joined.ignoredKrxReason)
            assertEquals(label, generalOnly, joined.tab)
        }

        val alone = joinGraphV2Components(c.general, null)
        assertFalse(alone.krxJoined)
        assertEquals(generalOnly, alone.tab)
    }
}
