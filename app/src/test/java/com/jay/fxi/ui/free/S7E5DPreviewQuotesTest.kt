package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.ui.alert.BankAlertEditor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5d (`s7e5d_api_agreed.r1.md`, 제안 r2 §2.1·§3): what the bank alert preview opens with. Each editor bank's rate from the
 * tab's own snapshot — the nine choices, Investing included, the hidden rows' too — together with that snapshot's as_of, and
 * nothing off the three bank tabs, for another owner, or without a live snapshot.
 */
class S7E5DPreviewQuotesTest {

    private val basis = Instant.parse("2026-09-06T02:30:00Z")
    private val period = FreeSnapshotUiState.DEFAULT_PERIOD

    private fun rates(currency: String, vararg banks: Pair<String, Double>) =
        FreeRate.Flat(currency, banks.map { (bank, rate) -> ExchangeRate(currency, bank, rate, basis - 1.hours) })

    private fun snapshot(tab: String, rate: FreeRate) = FreeSnapshot(
        tab = tab,
        period = period,
        asOf = basis,
        generatedAt = basis + 7.minutes,
        refreshNotBefore = basis + 48.hours,
        rate = rate,
        graph = FreeGraph(null, listOf(FreeGraphSeries(
            seriesId = "investing.$tab",
            points = listOf(FreeGraphPoint(basis - 2.hours, 1390.0, null, null), FreeGraphPoint(basis, 1400.0, null, null))
        )))
    )

    private fun stateOf(
        tab: FreeTab,
        snapshot: FreeSnapshot?,
        freshness: FreeSnapshotFreshness = FreeSnapshotFreshness.FRESH,
        uid: String = "u1",
        readUid: String = uid,
        preferences: Map<RateRowList, RateRowPreference> = emptyMap()
    ): FreeSnapshotUiState {
        val entries = snapshot?.let { mapOf(FreeSnapshotKey(it.tab, it.period) to FreeSnapshotEntry(it, freshness)) }.orEmpty()
        return FreeSnapshotUiState.from(uid, tab, period, FreeSnapshotReadState(readUid, entries), emptySet(), preferences)
    }

    @Test
    fun everyEditorBankIncludingInvestingIsProjectedWithTheSnapshotsAsOf() {
        val codes = BankAlertEditor.BANK_CHOICES.map { it.code }
        assertTrue("premise: Investing 은 선택지다", "investing" in codes)
        val banks = codes.mapIndexed { i, code -> code to 1300.0 + i }.toTypedArray()
        val state = stateOf(FreeTab.USD, snapshot("usd", rates("usd-krw", *banks)))
        assertEquals(banks.toMap(), state.previewQuotes)
        assertEquals("투영 순서는 응답 순서", codes, state.previewQuotes.keys.toList())
        assertEquals("as_of 는 그 snapshot 의 것(generatedAt 아님)", basis, state.previewAsOf)
    }

    /** A row the user hid stays hidden in the list and is still a price the preview can open on. */
    @Test
    fun hiddenRowsAreStillQuoted() {
        val hideTwo = mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("kb", "hana")))
        val state = stateOf(FreeTab.JPY, snapshot("jpy", rates("jpy-krw", "kb" to 950.0, "hana" to 951.0, "investing" to 949.0)), preferences = hideTwo)
        val shown = state.rateSections.flatMap { it.rows }.map { it.id }
        assertTrue("premise: 목록에서는 숨겨졌다: $shown", shown.none { it in setOf("kb", "hana") } && "investing" in shown)
        assertEquals(mapOf("kb" to 950.0, "hana" to 951.0, "investing" to 949.0), state.previewQuotes)
    }

    @Test
    fun citiKrxUnknownAndUnusableRatesAreDropped() {
        val state = stateOf(FreeTab.EUR, snapshot("eur", rates(
            "eur-krw",
            "citi" to 1500.0, "krx" to 1500.0, "future_bank" to 1500.0, "KB" to 1500.0,
            "kb" to Double.NaN, "hana" to Double.POSITIVE_INFINITY, "woori" to 0.0, "nh" to -1.0,
            "sc" to 1510.0
        )))
        assertEquals(mapOf("sc" to 1510.0), state.previewQuotes)
        assertEquals(basis, state.previewAsOf)
    }

    /**
     * A bank twice keeps its first entry, and an unusable first entry is not repaired by a later one — the sanitizer's rule.
     * The sanitizer already keeps one per bank, so this is the projection's own guarantee.
     */
    @Test
    fun theFirstOfADuplicateIsKept_andAnUnusableFirstIsNotRepaired() {
        assertEquals(
            mapOf("sc" to 1510.0),
            FreeSnapshotUiState.previewQuotes(rates("eur-krw", "sc" to 1510.0, "kb" to Double.NaN, "sc" to 1520.0, "kb" to 1400.0))
        )
    }

    /** A live snapshot with no editor bank in it still knows where it came from. */
    @Test
    fun anAnswerWithNoEditorBankKeepsItsAsOf() {
        val state = stateOf(FreeTab.USD, snapshot("usd", rates("usd-krw", "citi" to 1400.0)))
        assertEquals(emptyMap<String, Double>(), state.previewQuotes)
        assertEquals(basis, state.previewAsOf)
    }

    @Test
    fun nothingOffTheBankTabsForAnotherOwnerOrWithoutALiveSnapshot() {
        val usd = snapshot("usd", rates("usd-krw", "kb" to 1400.0))
        val tether = snapshot("tether", FreeRate.Grouped(
            "usdt-krw",
            listOf(SourceRate("bithumb", "usdt-krw", 1390.0, basis)),
            listOf(ExchangeRate("usd-krw", "hana", 1400.0, basis)),
            null
        ))
        val cases = mapOf(
            "뉴스" to stateOf(FreeTab.NEWS, null),
            "테더" to stateOf(FreeTab.TETHER, tether),
            "만료" to stateOf(FreeTab.USD, usd, freshness = FreeSnapshotFreshness.UNAVAILABLE),
            "도착 전" to stateOf(FreeTab.USD, null),
            "다른 owner 의 캐시" to stateOf(FreeTab.USD, usd, uid = "u1", readUid = "u2")
        )
        for ((label, state) in cases) {
            assertEquals(label, emptyMap<String, Double>(), state.previewQuotes)
            assertNull(label, state.previewAsOf)
        }
        val delayed = stateOf(FreeTab.USD, usd, freshness = FreeSnapshotFreshness.DELAYED)
        assertEquals("지연은 아직 살아 있는 snapshot 이다", mapOf("kb" to 1400.0), delayed.previewQuotes)
    }
}
