package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.remote.dto.FreeSnapshotFixtures as F
import com.jay.fxi.data.remote.dto.with
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.SourceRate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §3.1·§4): what the tether free preview opens with — each allowed exchange's
 * usdt-krw rate from the selected entry's own snapshot, with that snapshot's as_of, and nothing from another period's entry.
 */
class S87PreviewQuotesTest {

    private val t1 = Instant.parse("2026-11-23T01:30:00Z")
    private val t2 = Instant.parse("2026-11-22T01:30:00Z")
    private val period = FreeSnapshotUiState.DEFAULT_PERIOD
    private val otherPeriod = GraphPeriod.entries.first { it != period }

    private fun snapshot(tab: String, period: GraphPeriod, asOf: Instant, rate: FreeRate) = FreeSnapshot(
        tab = tab,
        period = period,
        asOf = asOf,
        generatedAt = asOf + 7.minutes,
        refreshNotBefore = asOf + 48.hours,
        rate = rate,
        graph = FreeGraph(null, listOf(FreeGraphSeries(
            seriesId = "investing.$tab",
            points = listOf(FreeGraphPoint(asOf - 2.hours, 1390.0, null, null), FreeGraphPoint(asOf, 1400.0, null, null))
        )))
    )

    private fun tether(asOf: Instant, vararg exchanges: Pair<String, Double>) = FreeRate.Grouped(
        "usdt-krw",
        exchanges.map { (source, rate) -> SourceRate(source, "usdt-krw", rate, asOf - 1.hours) },
        listOf(ExchangeRate("usd-krw", "hana", 1400.0, asOf - 1.hours)),
        null
    )

    private fun label(asOf: Instant?): String = when (asOf) {
        t1 -> "T1"
        t2 -> "T2"
        null -> "null"
        else -> asOf.toString()
    }

    /**
     * Base symbols only: the selected tether entry and a decoy for another period go through `FreeSnapshotUiState.from`, and
     * the two source preview getters are read by name. On the base neither exists, which reads as the empty pair; any other
     * reflection failure is not hidden.
     */
    @Test
    fun R02_groupedProjectionOnBase() {
        val usd = snapshot("usd", period, t1, FreeRate.Flat("usd-krw", listOf(ExchangeRate("usd-krw", "kb", 1400.0, t1 - 1.hours))))
        val bank = FreeSnapshotUiState.from("u1", FreeTab.USD, period, FreeSnapshotReadState("u1",
            mapOf(FreeSnapshotKey("usd", period) to FreeSnapshotEntry(usd, FreeSnapshotFreshness.FRESH))), emptySet(), emptyMap())
        assertEquals("premise: 은행 Flat 은 같은 경로로 투영된다", mapOf("kb" to 1400.0) to t1, bank.previewQuotes to bank.previewAsOf)

        val selected = snapshot("tether", period, t1, tether(t1, "upbit" to 1432.5))
        val decoy = snapshot("tether", otherPeriod, t2, tether(t2, "upbit" to 1500.0, "bithumb" to 1501.0))
        val read = FreeSnapshotReadState("u1", mapOf(
            FreeSnapshotKey("tether", otherPeriod) to FreeSnapshotEntry(decoy, FreeSnapshotFreshness.FRESH),
            FreeSnapshotKey("tether", period) to FreeSnapshotEntry(selected, FreeSnapshotFreshness.FRESH)
        ))
        val state = FreeSnapshotUiState.from("u1", FreeTab.TETHER, period, read, emptySet(), emptyMap())
        assertTrue("premise: 선택 기간의 테더 snapshot 이 화면에 실렸다", state.rateSections.isNotEmpty())
        assertTrue("premise: 미끼는 다른 기간이다", otherPeriod != period && t2 != t1)

        val type = FreeSnapshotUiState::class.java
        val quotesGetter = type.methods.firstOrNull { it.name == "getSourcePreviewQuotes" && it.parameterCount == 0 }
        val asOfGetter = type.methods.firstOrNull { it.name == "getSourcePreviewAsOf" && it.parameterCount == 0 }
        val observed: Pair<Map<*, *>, Instant?> = when {
            quotesGetter == null && asOfGetter == null -> emptyMap<String, Double>() to null
            quotesGetter == null || asOfGetter == null ->
                throw AssertionError("premise: source preview getter 는 둘 다 있거나 둘 다 없어야 한다 ($quotesGetter, $asOfGetter)")
            else -> (quotesGetter.invoke(state) as Map<*, *>) to (asOfGetter.invoke(state) as Instant?)
        }
        val expected: Pair<Map<*, *>, Instant?> = mapOf("upbit" to 1432.5) to t1
        if (observed != expected) {
            throw AssertionError("expected (${expected.first}, ${label(expected.second)}), actual (${observed.first}, ${label(observed.second)})")
        }
    }

    private fun stateOf(
        snapshot: FreeSnapshot?,
        freshness: FreeSnapshotFreshness = FreeSnapshotFreshness.FRESH,
        tab: FreeTab = FreeTab.TETHER,
        uid: String = "u1",
        readUid: String = uid,
        extra: Map<FreeSnapshotKey, FreeSnapshotEntry> = emptyMap(),
        preferences: Map<com.jay.fxi.domain.model.RateRowList, com.jay.fxi.domain.model.RateRowPreference> = emptyMap()
    ): FreeSnapshotUiState {
        val entries = extra + snapshot?.let { mapOf(FreeSnapshotKey(it.tab, it.period) to FreeSnapshotEntry(it, freshness)) }.orEmpty()
        return FreeSnapshotUiState.from(uid, tab, period, FreeSnapshotReadState(readUid, entries), emptySet(), preferences)
    }

    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    /** A hidden exchange stays out of the rows and is still a price the preview opens on; graph, banks and reference are not read. */
    @Test
    fun Q01_hiddenExchangeUsesRawGrouped() {
        val hide = mapOf(com.jay.fxi.domain.model.RateRowList.TETHER_EXCHANGES to com.jay.fxi.domain.model.RateRowPreference(hidden = setOf("bithumb")))
        val raw = tether(t1, "upbit" to 1432.5, "bithumb" to 1431.0)
            .copy(usdKrwReference = ExchangeRate("usd-krw", "investing", 1399.0, t1))
        val fixture = snapshot("tether", period, t1, raw)
        check("premise: 숨김 전 표시", stateOf(fixture).rateSections.flatMap { it.rows }.any { it.id == "bithumb" })
        val state = stateOf(fixture, preferences = hide)
        val shown = state.rateSections.flatMap { it.rows }.map { it.id }
        check("premise: 목록에서는 bithumb 이 숨겨졌다: $shown", "bithumb" !in shown && shown.isNotEmpty())
        check("숨긴 거래소의 원시 시세", state.sourcePreviewQuotes == mapOf("upbit" to 1432.5, "bithumb" to 1431.0) && state.sourcePreviewAsOf == t1)
    }

    /** Only the policy's exact pairs, in the order received: no other asset, no unknown source, no KRX, bank or case variant. */
    @Test
    fun Q02_acceptsOnlyExactPairs() {
        val rate = FreeRate.Grouped("usdt-krw", listOf(
            SourceRate("gopax", "usdt-krw", 1405.0, t1), SourceRate("upbit", "usd-krw", 1400.0, t1),
            SourceRate("krx", "usdt-krw", 1400.0, t1), SourceRate("kb", "usdt-krw", 1400.0, t1),
            SourceRate("investing", "usdt-krw", 1400.0, t1), SourceRate("future", "usdt-krw", 1400.0, t1),
            SourceRate("Upbit", "usdt-krw", 1400.0, t1), SourceRate(" upbit", "usdt-krw", 1400.0, t1),
            SourceRate("upbit", "USDT-KRW", 1400.0, t1), SourceRate("korbit", "usdt-krw", 1404.0, t1),
            SourceRate("coinone", "usdt-krw", 1403.0, t1), SourceRate("bithumb", "usdt-krw", 1402.0, t1),
            SourceRate("upbit", "usdt-krw", 1401.0, t1)
        ), listOf(ExchangeRate("usd-krw", "hana", 1400.0, t1)), ExchangeRate("usd-krw", "investing", 1399.0, t1))
        val got = sourcePreviewQuotes(rate)
        check("허용된 거래소 자산 쌍", got == mapOf("gopax" to 1405.0, "korbit" to 1404.0, "coinone" to 1403.0, "bithumb" to 1402.0, "upbit" to 1401.0) &&
            got.keys.toList() == listOf("gopax", "korbit", "coinone", "bithumb", "upbit"))
    }

    /** Finite, above zero and strictly below 1e9: the largest double under the bound is kept, the bound itself is not. */
    @Test
    fun Q03_checksStrictBounds() {
        val below = Math.nextDown(1e9)
        val rate = FreeRate.Grouped("usdt-krw", listOf(
            SourceRate("upbit", "usdt-krw", Double.NaN, t1), SourceRate("bithumb", "usdt-krw", Double.POSITIVE_INFINITY, t1),
            SourceRate("coinone", "usdt-krw", Double.NEGATIVE_INFINITY, t1), SourceRate("korbit", "usdt-krw", -1.0, t1),
            SourceRate("gopax", "usdt-krw", 0.0, t1), SourceRate("upbit", "usdt-krw", 1e9, t1),
            SourceRate("bithumb", "usdt-krw", below, t1)
        ), emptyList(), null)
        check("시세의 엄격한 상한", sourcePreviewQuotes(rate) == mapOf("bithumb" to below))
    }

    /** An unusable first row does not claim its source here; the first valid row does, and a later one never replaces it. */
    @Test
    fun Q04_firstValidWins() {
        val rate = FreeRate.Grouped("usdt-krw", listOf(
            SourceRate("upbit", "usdt-krw", Double.NaN, t1), SourceRate("bithumb", "usdt-krw", 1402.0, t1),
            SourceRate("upbit", "usdt-krw", 1401.0, t1), SourceRate("upbit", "usdt-krw", 1499.0, t1),
            SourceRate("bithumb", "usdt-krw", 1498.0, t1)
        ), emptyList(), null)
        val got = sourcePreviewQuotes(rate)
        check("첫 유효 시세", got == mapOf("bithumb" to 1402.0, "upbit" to 1401.0) && got.keys.toList() == listOf("bithumb", "upbit"))
    }

    /** The pair comes from the selected entry alone, with its own as_of, and is empty or null wherever that entry is not live. */
    @Test
    fun Q05_bindsPairToOneEntry() {
        val selected = snapshot("tether", period, t1, tether(t1, "upbit" to 1432.5))
        val decoy = mapOf(FreeSnapshotKey("tether", otherPeriod) to
            FreeSnapshotEntry(snapshot("tether", otherPeriod, t2, tether(t2, "upbit" to 1500.0)), FreeSnapshotFreshness.FRESH))
        val empty = snapshot("tether", period, t1, tether(t1, "krx" to 1400.0))
        val live = mapOf(
            "정상" to stateOf(selected, extra = decoy),
            "지연" to stateOf(selected, FreeSnapshotFreshness.DELAYED, extra = decoy)
        )
        val none = mapOf(
            "만료" to stateOf(selected, FreeSnapshotFreshness.UNAVAILABLE, extra = decoy),
            "도착 전" to stateOf(null, extra = decoy),
            "다른 owner 의 캐시" to stateOf(selected, readUid = "u2"),
            "다른 탭" to stateOf(snapshot("usd", period, t1, FreeRate.Flat("usd-krw", listOf(ExchangeRate("usd-krw", "kb", 1400.0, t1)))), tab = FreeTab.USD),
            "잘못된 shape" to stateOf(snapshot("tether", period, t1, FreeRate.Flat("usdt-krw", emptyList()))),
            "owner 교체" to stateOf(selected, extra = decoy).forOwner("u9")
        )
        val ok = live.values.all { it.sourcePreviewQuotes == mapOf("upbit" to 1432.5) && it.sourcePreviewAsOf == t1 } &&
            none.values.all { it.sourcePreviewQuotes.isEmpty() && it.sourcePreviewAsOf == null } &&
            stateOf(empty).let { it.sourcePreviewQuotes.isEmpty() && it.sourcePreviewAsOf == t1 }
        check("선택 entry의 시각과 시세", ok)
    }

    /**
     * The real wire boundary: a tether answer goes through the sanitizer, which claims each pair at its first row before the
     * price is checked — an invalid first row then hides a valid duplicate — and refuses the whole answer for one KRX row.
     */
    @Test
    fun Q06_wireSanitizerPreclaimsAndRejectsKrx() {
        val wire = F.snapshot("tether")
        val base = wire.getValue("rate") as JsonObject
        fun sanitize(rate: JsonObject) = com.jay.fxi.data.free.FreeSnapshotSanitizer().sanitize(F.decode(wire.with("rate", rate)), "tether", GraphPeriod.ONE_DAY)
        val clean = sanitize(base.with("usdt_krw", JsonArray(listOf(F.rate("upbit", "usdt-krw", 1401.0, false), F.rate("bithumb", "usdt-krw", 1402.0, false)))))
        check("premise: 정상 wire 는 그대로 투영된다", sourcePreviewQuotes(clean.rate as FreeRate.Grouped) == mapOf("upbit" to 1401.0, "bithumb" to 1402.0))
        val preclaimed = sanitize(base.with("usdt_krw", JsonArray(listOf(
            F.rate("upbit", "usdt-krw", -1.0, false), F.rate("upbit", "usdt-krw", 1401.0, false), F.rate("bithumb", "usdt-krw", 1402.0, false)))))
        val krx = runCatching { sanitize(base.with("usdt_krw", JsonArray(listOf(F.rate("upbit", "usdt-krw", 1401.0, false), F.rate("krx", "usdt-krw", 1400.0, false))))) }
        check("wire 경계의 선점과 거부", sourcePreviewQuotes(preclaimed.rate as FreeRate.Grouped) == mapOf("bithumb" to 1402.0) &&
            krx.exceptionOrNull() is com.jay.fxi.data.free.FreeSnapshotValidationException)
    }
}
