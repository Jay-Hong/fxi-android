package com.jay.fxi.ui.free

import com.jay.fxi.data.free.*
import com.jay.fxi.domain.model.*
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import org.junit.Test

class S96PreviewQuotesTest {
    private val t1 = Instant.parse("2026-11-27T00:00:00Z")
    private val t2 = t1 + 1.hours
    private val day = GraphPeriod.ONE_DAY
    private val other = GraphPeriod.entries.first { it != day }
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val banks = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private fun bank(id: String, price: Double, asset: String = "usd-krw") = ExchangeRate(asset, id, price, t1)
    private fun exchange(id: String, price: Double, asset: String = "usdt-krw") = SourceRate(id, asset, price, t1)
    private fun grouped() = FreeRate.Grouped("usdt-krw", exchanges.mapIndexed { i, id -> exchange(id, 1300.0 + i) },
        listOf(bank("kb", 1399.0), bank("hana", 1400.0)), bank("investing", 1401.0))
    private fun entry(rate: FreeRate, period: GraphPeriod = day, at: Instant = t1,
        freshness: FreeSnapshotFreshness = FreeSnapshotFreshness.FRESH, tab: String = "tether") =
        FreeSnapshotKey(tab, period) to FreeSnapshotEntry(FreeSnapshot(tab = tab, period = period, asOf = at,
            generatedAt = at + 1.hours, refreshNotBefore = at + 48.hours, rate = rate, graph = FreeGraph(null, emptyList())), freshness)
    private fun state(vararg entries: Pair<FreeSnapshotKey, FreeSnapshotEntry>, tab: FreeTab = FreeTab.TETHER,
        period: GraphPeriod = day, owner: String = "u", hidden: Set<String> = emptySet()) =
        try {
            FreeSnapshotUiState.from("u", tab, period, FreeSnapshotReadState(owner, entries.toMap()), emptySet(),
                mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = hidden)))
        } catch (e: Exception) { throw AssertionError("premise: from이 예외 없이 상태를 만든다", e) }

    private fun expect(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message\nexpected=$expected actual=$actual")
    }

    @Test fun R02() {
        val raw = grouped().copy(usdtKrw = grouped().usdtKrw.take(2))
        val entries = arrayOf(entry(FreeRate.Flat("usd-krw", listOf(bank("kb", 1822.0))), tab = "usd"), entry(raw), entry(raw.copy(usdtKrw = listOf(exchange("upbit", 1600.0))), other, t2))
        val first = state(*entries)
        val second = state(*entries, period = other)
        val fx = state(*entries,tab = FreeTab.USD)
        expect("premise: 다른 탭의 시세",mapOf("kb" to 1822.0),fx.previewQuotes)
        expect("premise: source T1", mapOf("upbit" to 1300.0, "bithumb" to 1301.0) to t1, first.sourcePreviewQuotes to first.sourcePreviewAsOf)
        expect("premise: source T2", mapOf("upbit" to 1600.0) to t2, second.sourcePreviewQuotes to second.sourcePreviewAsOf)
        fun observed(s: FreeSnapshotUiState): Pair<Map<*, *>, Instant?> {
            val methods = s.javaClass.methods.filter { it.parameterCount == 0 }
            val q = methods.singleOrNull { it.name == "getComparisonPreviewQuotes" }
            val time = methods.singleOrNull { it.name == "getComparisonPreviewAsOf" }
            if (q == null && time == null) return emptyMap<Any, Any>() to null
            if (q == null || time == null) throw AssertionError("premise: 두 getter의 동시 존재")
            return (q.invoke(s) as Map<*, *>) to (time.invoke(s) as Instant?)
        }
        val reference = mapOf(("kb" to "usd-krw") to 1399.0, ("hana" to "usd-krw") to 1400.0, ("investing" to "usd-krw") to 1401.0)
        expect("선택 entry의 비교 시세", reference + mapOf(("upbit" to "usdt-krw") to 1300.0, ("bithumb" to "usdt-krw") to 1301.0) to t1, observed(first))
        expect("다른 기간", reference + (("upbit" to "usdt-krw") to 1600.0) to t2, observed(second))
        expect("FX entry",mapOf(("kb" to "usd-krw") to 1822.0) to t1,observed(fx))
        expect("다른 UID", emptyMap<Any, Any>() to null, observed(state(*entries, owner = "v")))
    }

    @Test fun Q01() {
        val raw = grouped()
        val shown = state(entry(raw), hidden = setOf("bithumb"))
        expect("premise: 표시 행에서 숨김", false, shown.rateSections.flatMap { it.rows }.any { it.id == "bithumb" })
        expect("숨긴 leg의 원시 시세", 1301.0, shown.comparisonPreviewQuotes["bithumb" to "usdt-krw"])
        val lure = raw.copy(usdKrwBanks = raw.usdKrwBanks + bank("investing", 1401.0), usdKrwReference = null)
        val expected = exchanges.mapIndexed { i, id -> (id to "usdt-krw") to 1300.0 + i } +
            listOf(("kb" to "usd-krw") to 1399.0, ("hana" to "usd-krw") to 1400.0)
        val noRef = state(entry(lure))
        val invalidRef = comparisonPreviewBasis(FreeTab.TETHER, lure.copy(usdKrwReference = bank("investing", 1e9)), t1)
        for ((q, at) in listOf(noRef.comparisonPreviewQuotes to noRef.comparisonPreviewAsOf, invalidRef))
            expect("참조 없는 테더의 은행 배열", expected to t1, q.toList() to at)
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) {
            val asset = "${tab.serverTab}-krw"
            val good = banks.mapIndexed { i, id -> bank(id, 1200.0 + i, asset) }
            val bad = listOf(bank("kb", 2.0, "foreign"), bank("citi", 1.0, asset), bank("krx", 2.0, asset),
                bank(" KB", 3.0, asset), bank("upbit", 4.0, asset), bank("other", 5.0, asset))
            val got = comparisonPreviewBasis(tab, FreeRate.Flat(asset, bad + good + good), t1)
            expect("선택 탭 비교 시세 전체", good.map { (it.bank to asset) to it.rate } to t1, got.first.toList() to got.second)
            expect("부모 asset", emptyMap<Any, Any>() to null, comparisonPreviewBasis(tab, FreeRate.Flat("wrong", good), t1))
        }
        for (ref in listOf(bank("kb", 9.0), bank("investing", 9.0, "usdt-krw"))) {
            val onlyReference = raw.copy(usdtKrw = emptyList(), usdKrwBanks = emptyList(), usdKrwReference = ref)
            expect("참조 leg의 출처와 통화", emptyMap<Any, Any>() to t1,
                comparisonPreviewBasis(FreeTab.TETHER, onlyReference, t1))
        }
        expect("테더의 Flat 거부", emptyMap<Any, Any>() to null, comparisonPreviewBasis(FreeTab.TETHER,
            FreeRate.Flat("usdt-krw", listOf(bank("kb", 1.0, "usdt-krw"))), t1))
        expect("FX의 Grouped 거부", emptyMap<Any, Any>() to null,
            comparisonPreviewBasis(FreeTab.USD, raw.copy(primaryAsset = "usd-krw"), t1))
        val wrong = raw.copy(usdtKrw = listOf(exchange("upbit", 2.0, "usd-krw"), exchange("kb", 3.0),
            exchange("KRX", 4.0), exchange(" Upbit", 5.0)) + raw.usdtKrw,
            usdKrwBanks = listOf(bank("kb", 1.0, "usdt-krw"), bank("upbit", 7.0)) + raw.usdKrwBanks)
        val got = comparisonPreviewBasis(FreeTab.TETHER, wrong, t1)
        expect("선택 탭 비교 시세 전체", (expected + (("investing" to "usd-krw") to 1401.0)) to t1, got.first.toList() to got.second)
    }

    @Test fun Q02() {
        fun project(prices: List<Double>, ref: Double) = listOf(
            comparisonPreviewBasis(FreeTab.USD, FreeRate.Flat("usd-krw", prices.map { bank("kb", it) }), t1).first,
            comparisonPreviewBasis(FreeTab.TETHER, FreeRate.Grouped("usdt-krw", prices.map { exchange("upbit", it) },
                prices.map { bank("hana", it) }, bank("investing", ref)), t1).first)
        val below = Math.nextDown(1e9)
        for (v in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1e9, below)) {
            val got = project(listOf(v), v)
            val values = if (v == below) List(4) { below } else emptyList()
            expect("시세의 엄격한 상한", values, got.flatMap { it.values })
        }
        expect("정확한 leg의 첫 유효값", listOf(1.0, 1.0, 1.0, 3.0), project(listOf(-1.0, 1.0, 2.0), 3.0).flatMap { it.values })
    }

    @Test fun Q03() {
        val first = entry(grouped())
        val second = entry(grouped().copy(usdtKrw = listOf(exchange("bithumb", 1600.0))), other, t2)
        val live = state(first, second)
        expect("선택 entry의 기준시각", t1, live.comparisonPreviewAsOf)
        fun basis(s: FreeSnapshotUiState) = s.comparisonPreviewQuotes to s.comparisonPreviewAsOf
        val expected = mapOf(("bithumb" to "usdt-krw") to 1600.0, ("kb" to "usd-krw") to 1399.0,
            ("hana" to "usd-krw") to 1400.0, ("investing" to "usd-krw") to 1401.0) to t2
        expect("선택 기간의 시세와 시각", expected, basis(state(first, second, period = other)))
        expect("지연 entry의 시세와 시각", expected, basis(state(first, second.first to second.second.copy(freshness = FreeSnapshotFreshness.DELAYED), period = other)))
        val empty = emptyMap<Pair<String,String>, Double>()
        val expired = state(first.first to first.second.copy(freshness = FreeSnapshotFreshness.UNAVAILABLE))
        expect("premise: 만료 label", true, expired.asOfLabel != null)
        expect("만료 시세와 시각", empty to null, basis(expired))
        for (s in listOf(state(), state(first, owner = "v"), live.forOwner("other"), state(first, tab = FreeTab.NEWS),
            state(entry(FreeRate.Flat("usd-krw", emptyList()))))) expect("중립 상태", empty to null, basis(s))
        expect("정상 빈 entry", empty to t1, basis(state(entry(FreeRate.Grouped("usdt-krw", emptyList(), emptyList(), null)))))
        expect("다른 부모 asset", empty to null, comparisonPreviewBasis(FreeTab.TETHER, grouped().copy(primaryAsset = "usd-krw"), t1))
    }
}
