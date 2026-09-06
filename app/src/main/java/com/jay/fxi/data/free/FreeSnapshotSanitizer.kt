package com.jay.fxi.data.free

import com.jay.fxi.data.remote.dto.FreeSnapshotRateSerializer
import com.jay.fxi.data.remote.dto.FreeSnapshotResponse
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.domain.model.GraphPeriod
import javax.inject.Inject
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The absolute window every admitted timestamp must fall inside.
 *
 * Deliberately crude and absolute rather than relative to a clock: this class takes no clock, and
 * the job here is only to exclude values that no correct server can produce, not to judge freshness.
 */
private val SANE_INSTANTS =
    Instant.parse("2020-01-01T00:00:00Z")..Instant.parse("2100-01-01T00:00:00Z")

/** Free-only admission policy. Never consult the premium/KRX capability registry. */
class FreeSnapshotSanitizer @Inject constructor() {
    fun sanitize(response: FreeSnapshotResponse, tab: String, period: GraphPeriod): FreeSnapshot {
        val asset = TAB_ASSETS[tab] ?: invalid("Unsupported free snapshot tab")
        if (response.tab != tab || response.period != period.code) invalid("Free snapshot echo mismatch")
        // KST and UTC share the minute grid. Age/refresh scheduling remains Lane B's responsibility.
        if (Math.floorMod(response.asOf.epochSeconds, 3600L) != 1800L || response.asOf.nanosecondsOfSecond != 0) {
            invalid("Free snapshot basis is off the HH:30 grid")
        }
        // Every scalar here is bounded — `decimals in 0..8`, rates against a plausible range — and
        // the three instants were the exception. Grid alignment holds for *any* `:30:00`, including
        // year 999999999, and the scheduler converts `as_of` to a local date to find its basis slot:
        // an out-of-range instant throws there, inside the single-consumer loop. Bound it here, where
        // the rest of the admission policy lives, so that throw is unreachable rather than merely
        // contained. `generated_at` is when the cron *built* this view, so it is at or after the
        // basis it was built for — that holds for a stale canonical too, which is served long after
        // both. `refresh_not_before` is serve-time and may legitimately sit hours past a stale
        // `as_of`, so it is bounded only against the same absolute window.
        if (response.asOf !in SANE_INSTANTS || response.generatedAt !in SANE_INSTANTS) {
            invalid("Free snapshot timestamps are outside the supported window")
        }
        if (response.generatedAt < response.asOf) invalid("Free snapshot was generated before its basis")
        response.refreshNotBefore?.let {
            if (it !in SANE_INSTANTS) invalid("Free snapshot refresh hint is outside the supported window")
            if (it <= response.asOf) invalid("Free snapshot refresh hint is not after its basis")
        }

        // Scan the unprojected object BEFORE dispatch: every container (including opposite-shape
        // extras) and both legacy/topic identity fields participate, regardless of kind.
        val rawRate = response.rate
        if (rawRate.hasKrxContent()) invalid("KRX content in free snapshot rate")
        try {
            FreeSnapshotRateSerializer.validateShape(rawRate)
        } catch (_: IllegalArgumentException) {
            invalid("Invalid free snapshot rate shape")
        }
        val rate = if ("kind" !in rawRate) {
            if (tab == "tether" || rawRate.string("asset") != asset) invalid("Free snapshot flat shape/asset mismatch")
            FreeRate.Flat(asset, takeRates(rawRate.array("entries"), FX_RATE_SOURCES, asset, response.asOf, flat = true)
                .map { it.asExchangeRate() })
        } else {
            if (tab != "tether" || rawRate.string("primary_asset") != asset) {
                invalid("Free snapshot grouped shape/asset mismatch")
            }
            FreeRate.Grouped(
                asset,
                takeRates(rawRate.array("usdt_krw"), EXCHANGES, "usdt-krw", response.asOf),
                takeRates(rawRate.array("usd_krw_banks"), TETHER_BANKS, "usd-krw", response.asOf)
                    .map { it.asExchangeRate() },
                takeRates(listOf(rawRate.getValue("usd_krw_reference")), setOf("investing"), "usd-krw", response.asOf)
                    .singleOrNull()?.asExchangeRate()
            )
        }
        val allowed = (if (period == GraphPeriod.ONE_DAY) INTRADAY_GRAPH_IDS else HISTORICAL_GRAPH_IDS).getValue(tab)
        val seen = mutableSetOf<String>()
        val series = response.graph.series.mapNotNull { raw ->
            validOrNull {
                val item = raw.objectValue()
                val id = item.string("id")
                require(id in allowed && seen.add(id))
                // Check the whole series, including carry-in and every provenance field, before projection.
                require(!item.hasKrxContent())
                readSeries(item, response.asOf)
            }
        }
        // An unusable answer must not replace a last-good cache entry with an empty success.
        val hasRates = when (rate) {
            is FreeRate.Flat -> rate.entries.isNotEmpty()
            is FreeRate.Grouped -> rate.usdtKrw.isNotEmpty() || rate.usdKrwBanks.isNotEmpty() || rate.usdKrwReference != null
        }
        if (!hasRates || series.none { it.points.isNotEmpty() }) invalid("Empty sanitized free snapshot")
        val graph = response.graph
        // The envelope's own strings never passed through either scan above — the rate check takes
        // the rate block and the series check takes one series at a time — yet all four are copied
        // into the UID-scoped cache verbatim. The plan admits only sanitized values there, so a
        // `bucket_size` of "KRX" has to be refused here even though nothing renders it today.
        if (listOfNotNull(graph.bucketSize, graph.range.start, graph.range.end, graph.liveDomainMode)
                .any { it.mentionsKrx() }) {
            invalid("KRX content in free snapshot graph envelope")
        }
        return FreeSnapshot(
            tab, period, response.asOf, response.generatedAt, response.refreshNotBefore, rate,
            FreeGraph(graph.bucketSize, series, graph.range.start, graph.range.end,
                graph.domainStartAt, graph.domainEndAt, graph.liveDomainMode)
        )
    }

    private fun takeRates(
        entries: List<JsonElement>,
        allowedSources: Set<String>,
        asset: String,
        asOf: Instant,
        flat: Boolean = false
    ): List<SourceRate> {
        val seen = mutableSetOf<Pair<String, String>>()
        return entries.mapNotNull { raw ->
            validOrNull {
                val item = raw.objectValue()
                val source = item.string(if (flat) "bank" else "source")
                val entryAsset = item.string(if (flat) "currency" else "asset")
                require(source in allowedSources && entryAsset == asset)
                // Claim the first allowed identity even if its value is malformed: later duplicates never repair it.
                require(seen.add(source to entryAsset))
                val timestamp = Instant.parse(item.string("timestamp"))
                require(timestamp <= asOf)
                SourceRate(source, entryAsset, item.price("rate"), timestamp)
            }
        }
    }

    private fun readSeries(item: JsonObject, asOf: Instant): FreeGraphSeries {
        val points = item.array("data").map { raw ->
            val point = raw.objectValue()
            val ts = Instant.parse(point.string("ts"))
            require(ts <= asOf)
            val rate = point.price("rate")
            val high = point.optionalPrice("high")
            val low = point.optionalPrice("low")
            require((high == null || high >= rate) && (low == null || low <= rate))
            FreeGraphPoint(ts, rate, high, low, point.string("source"),
                point.optionalString("close_basis"), point.optionalString("source_method"))
        }
        val provenance = item.getValue("provenance").objectValue()
        val insufficient = (provenance["insufficient_history"] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.booleanOrNull ?: malformed()
        val metadata = provenance.array("per_point_metadata").map { it.stringValue() }
        val carryIn = item["carry_in"]?.takeUnless { it == JsonNull }?.let { raw ->
            val carry = raw.objectValue()
            val observedAt = Instant.parse(carry.string("observed_at"))
            require(observedAt <= asOf && (points.isEmpty() || observedAt < points.first().timestamp))
            FreeGraphCarryIn(carry.price("rate"), observedAt)
        }
        val decimals = (item["decimals"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: malformed()
        require(decimals in 0..8)
        return FreeGraphSeries(item.string("id"), points, item.string("label"), item.string("axis_group"),
            item.string("unit"), decimals, insufficient, metadata, carryIn)
    }

    private fun SourceRate.asExchangeRate() = ExchangeRate(asset, source, rate, timestamp)

    private companion object {
        val TAB_ASSETS = mapOf("usd" to "usd-krw", "jpy" to "jpy-krw", "eur" to "eur-krw", "tether" to "usdt-krw")
        val FX_GRAPH_SOURCES = setOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
        // Citi is valid in flat rate payloads, but is not in the free graph catalog.
        val FX_RATE_SOURCES = FX_GRAPH_SOURCES + "citi"
        val EXCHANGES = setOf("upbit", "bithumb", "coinone", "korbit", "gopax")
        val TETHER_BANKS = setOf("kb", "hana")
        val INTRADAY_GRAPH_IDS = mapOf(
            "usd" to (FX_GRAPH_SOURCES.map { "$it.usd" }.toSet() + "dxy"),
            "jpy" to FX_GRAPH_SOURCES.map { "$it.jpy" }.toSet(),
            "eur" to FX_GRAPH_SOURCES.map { "$it.eur" }.toSet(),
            "tether" to (EXCHANGES.map { "$it.usdt-krw" }.toSet() +
                setOf("investing.usd", "kb.usd", "hana.usd", "dxy", "dxy_futures"))
        )

        // The historical builder has fewer sources than the intraday catalog (including no DXY futures).
        val HISTORICAL_GRAPH_IDS = mapOf(
            "usd" to setOf("investing.usd", "hana.usd", "dxy"),
            "jpy" to setOf("investing.jpy", "hana.jpy"),
            "eur" to setOf("investing.eur", "hana.eur"),
            "tether" to setOf("bithumb.usdt-krw", "investing.usd", "hana.usd", "dxy")
        )

        fun invalid(message: String): Nothing = throw FreeSnapshotValidationException(message)
        fun malformed(): Nothing = throw IllegalArgumentException("Malformed free snapshot content")
        fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: malformed()
        fun JsonElement.stringValue(): String = (this as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: malformed()
        fun JsonObject.string(key: String): String = (this[key] ?: malformed()).stringValue()
        fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.stringValue()
        fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: malformed()
        fun JsonObject.price(key: String): Double {
            val number = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: malformed()
            require(number.isFinite() && number > 0 && number < 1e9)
            return number
        }
        fun JsonObject.optionalPrice(key: String): Double? = this[key]?.takeUnless { it == JsonNull }?.let { price(key) }

        /**
         * Names no legitimate field or value carries.
         *
         * `contains`, not `startsWith`: the plan's requirement is zero KRX **strings** on the free
         * surface, and several wire fields arrive as unbounded free text. A series `label` and
         * `unit` render verbatim; its `axis_group` and every `per_point_metadata` entry are not
         * drawn today but are kept in the model and in the cache the plan admits only sanitized
         * values to. A prefix test admits "USD KRX 선물" as a label and `usd_krx_rate` as a key.
         * Nothing legitimate contains either: the sources are bank and exchange names, the assets
         * are `*-krw`, and the KRX provenance values (`krx_openapi_daily`, `krx_cf_close_1545`) are
         * defined only for rows the free builder excludes.
         */
        fun String.namesKrx(): Boolean =
            contains("krx", ignoreCase = true) || contains("usd-krw-futures", ignoreCase = true)

        /** The rule for anything that renders: [namesKrx], plus the contract field named in prose. */
        fun String.mentionsKrx(): Boolean =
            trim().let { it.namesKrx() || it.contains("contract_code", ignoreCase = true) }

        fun JsonElement.hasKrxContent(): Boolean = when (this) {
            is JsonObject -> any { (key, value) ->
                // A key naming the contract field is evidence of KRX only when it carries one.
                // Today's builder omits the key entirely for a non-KRX row (`graph_v2.py:346`), so
                // this null branch is a *tolerance* rather than a case anyone has observed — but it
                // is the same reading the server's own free check takes (`free_snapshot.py:388`,
                // `contract_code is not None`), and matching it is what keeps Android from blanking
                // a snapshot the server was right to serve. The name still matches by substring —
                // leaving it an exact match let `usd_contract_code` through.
                (key.contains("contract_code", ignoreCase = true) && value != JsonNull) ||
                    key.namesKrx() || value.hasKrxContent()
            }
            is JsonArray -> any { it.hasKrxContent() }
            is JsonPrimitive -> isString && content.mentionsKrx()
        }

        inline fun <T> validOrNull(block: () -> T): T? = try {
            block()
        } catch (_: IllegalArgumentException) {
            // Content errors are isolated to the entire entry/series; coroutine cancellation is never caught.
            null
        } catch (_: NoSuchElementException) {
            null
        }
    }
}
