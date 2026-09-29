package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
import com.jay.fxi.domain.model.SourceRate
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S1.5-b4a contract (next1/decision_codex.r1.md): the tether tab is **one list, one scale, the first drawn row the
 * reference**, for free and premium alike — iOS a36682f `FreeSnapshotViewModel.buildTetherSourceState` /
 * `ExchangeRateViewModel.usdtDisplayState` (rows in config order, `reference = rows.first`, one `ScalePolicy`), with the default
 * `SourcePreferenceManager.effectiveDefault(krxVisible=false)`: registry order investing → kb → hana → the five exchanges, all
 * exchanges and hana shown, investing and kb off. Each row keeps its own asset; the group declares the two it mixes. The
 * implementation thread reads but does not edit this file.
 */
class TetherSingleListTest {

    private val at = Instant.parse("2026-09-29T00:00:00Z")
    private fun bank(code: String, value: Double) = ExchangeRate("usd-krw", code, value, at)
    private fun exchange(code: String, value: Double) = SourceRate(code, "usdt-krw", value, at)

    /** Payload order deliberately not the registry's. */
    private val tether = FreeRate.Grouped(
        primaryAsset = "usdt-krw",
        usdtKrw = listOf(
            exchange("gopax", 1404.0), exchange("korbit", 1400.5), exchange("coinone", 1403.0),
            exchange("bithumb", 1401.5), exchange("upbit", 1402.0)
        ),
        usdKrwBanks = listOf(bank("hana", 1398.4), bank("kb", 1399.0)),
        usdKrwReference = bank("investing", 1398.8)
    )

    private fun ids(rate: FreeRate, prefs: Map<RateRowList, RateRowPreference>? = null) =
        rate.asRateScales(prefs).single().groups.single().quotes.map { it.id }

    @Test
    fun T1_theTetherTab_isOneListInRegistryOrder_withTheDefaultsShown() {
        val scales = tether.asRateScales()
        assertEquals(1, scales.size)
        val group = scales.single().groups.single()
        assertEquals(RateRowList.TETHER_EXCHANGES, group.list)
        assertEquals(setOf("usdt-krw", "usd-krw"), group.accepts)
        assertEquals(listOf("hana", "upbit", "bithumb", "coinone", "korbit", "gopax"), group.quotes.map { it.id })
    }

    @Test
    fun T2_eachRowKeepsItsOwnAsset() {
        val quotes = tether.asRateScales(mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = emptySet())))
            .single().groups.single().quotes
        assertEquals(
            mapOf("investing" to "usd-krw", "kb" to "usd-krw", "hana" to "usd-krw", "upbit" to "usdt-krw",
                "bithumb" to "usdt-krw", "coinone" to "usdt-krw", "korbit" to "usdt-krw", "gopax" to "usdt-krw"),
            quotes.associate { it.id to it.asset }
        )
    }

    @Test
    fun T3_theFirstDrawnRowIsTheReference_andEveryOtherRowIsMeasuredAgainstIt_onOneRuler() {
        val view = RateRowPresenter.present(tether.asRateScales().single()).single()
        assertEquals("hana", view.rows.first { it.isReference }.id)
        assertEquals(1, view.rows.count { it.isReference })
        assertEquals(RateDisplay.quantized(1402.0) - RateDisplay.quantized(1398.4), view.rows.first { it.id == "upbit" }.difference!!, 1e-9)
        assertEquals("테더 시세", view.title)
    }

    @Test
    fun T4_withoutHana_theFirstExchangeLeads() {
        val noHana = tether.copy(usdKrwBanks = listOf(bank("kb", 1399.0)))
        assertEquals(listOf("upbit", "bithumb", "coinone", "korbit", "gopax"), ids(noHana))
        assertEquals("upbit", RateRowPresenter.present(noHana.asRateScales().single()).single().rows.first { it.isReference }.id)

        // Nothing at all: an empty list, no rows, nothing to edit, and no investing reference made up.
        val nothing = FreeRate.Grouped("usdt-krw", emptyList(), emptyList(), null)
        val group = nothing.asRateScales().single().groups.single()
        assertEquals(emptyList<RateQuote>(), group.quotes)
        assertEquals(RateReference.FirstRow, group.reference)
        assertEquals(emptyList<RateRow>(), RateRowPresenter.present(nothing.asRateScales().single()).single().rows)
        assertEquals(emptyList<RateQuote>(), nothing.editableRosters()[RateRowList.TETHER_EXCHANGES].orEmpty())
    }

    @Test
    fun T5_theUsersArrangementSpansBanksReferenceAndExchanges() {
        val prefs = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(order = listOf("investing", "upbit"), hidden = setOf("gopax")))
        val drawn = ids(tether, prefs)
        assertEquals(listOf("investing", "upbit", "kb", "hana", "bithumb", "coinone", "korbit"), drawn)
        assertEquals("investing", RateRowPresenter.present(tether.asRateScales(prefs).single()).single().rows.first { it.isReference }.id)
    }

    @Test
    fun T6_theSheetEditsTheWholeList_hiddenRowsIncluded() {
        val editable = tether.editableRosters()
        assertEquals(setOf(RateRowList.TETHER_EXCHANGES), editable.keys)
        assertEquals(
            listOf("investing", "kb", "hana", "upbit", "bithumb", "coinone", "korbit", "gopax"),
            editable.getValue(RateRowList.TETHER_EXCHANGES).map { it.id }
        )
        assertEquals(setOf("investing", "kb"), RateRowRoster.hiddenByDefault(RateRowList.TETHER_EXCHANGES))
    }

    @Test
    fun T7_aGroupStillRefusesAQuoteForAnAssetItDoesNotDeclare() {
        val stray = RateScale(
            listOf(
                RateQuoteGroup(
                    "테더 시세", "usdt-krw",
                    listOf(RateQuote("upbit", "업비트", 1402.0, at, "usdt-krw"), RateQuote("hana", "하나은행", 950.0, at, "jpy-krw")),
                    accepts = setOf("usdt-krw", "usd-krw")
                )
            )
        )
        assertTrue(runCatching { RateRowPresenter.present(stray) }.exceptionOrNull() is IllegalArgumentException)

        // Two groups on one ruler must declare the same assets.
        val split = RateScale(
            listOf(
                RateQuoteGroup("a", "usdt-krw", listOf(RateQuote("upbit", "업비트", 1402.0, at, "usdt-krw")), accepts = setOf("usdt-krw", "usd-krw")),
                RateQuoteGroup("b", "usd-krw", listOf(RateQuote("kb", "국민은행", 1399.0, at, "usd-krw")))
            )
        )
        assertTrue(runCatching { RateRowPresenter.present(split) }.exceptionOrNull() is IllegalArgumentException)

        // A group cannot declare an asset outside the set it accepts.
        val misdeclared = RateScale(listOf(RateQuoteGroup("c", "jpy-krw", emptyList(), accepts = setOf("usd-krw"))))
        assertTrue(runCatching { RateRowPresenter.present(misdeclared) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun T8_fxListsStayOneAsset_andTheirRowsSaySo() {
        val fx = FreeRate.Flat("usd-krw", listOf(bank("investing", 1398.8), bank("kb", 1399.0)))
        val group = fx.asRateScales().single().groups.single()
        assertEquals(setOf("usd-krw"), group.accepts)
        assertEquals(listOf("usd-krw", "usd-krw"), group.quotes.map { it.asset })
    }

    @Test
    fun T9_oneCodeTwiceInTheTetherList_isRefused_forDrawingAndForEditing() {
        // The roster speaks about codes, so a bank and an exchange sharing one would both resolve to one row.
        val twice = tether.copy(usdtKrw = tether.usdtKrw + SourceRate("kb", "usdt-krw", 1401.0, at))
        assertTrue(runCatching { twice.asRateScales() }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { twice.editableRosters() }.exceptionOrNull() is IllegalArgumentException)
    }
}
