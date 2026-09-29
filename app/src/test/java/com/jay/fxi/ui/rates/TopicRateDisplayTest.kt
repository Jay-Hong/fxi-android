package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S3-R1 contract (S3R2/decl_codex.r1.md, R1): the merged topic quotes go to the lists each tab shows, drawn by the
 * free tab's projection. Tether takes the free tab's sources (iOS a36682f `FreeSnapshotModels.swift:134-149`: exchanges ×5, banks
 * kb·hana, reference investing); the FX tabs take investing and the eight banks, never Citi (`ExchangeRateViewModel.swift:617`). The implementation thread reads but does not edit this file.
 */
class TopicRateDisplayTest {

    private val t0 = Instant.parse("2026-09-29T00:00:00Z")
    private fun q(source: String, asset: String, rate: Double) = TopicQuote(source, asset, rate, t0)
    private val dxy = TopicDollarIndex(98.5, t0, "investing")

    /** Everything the topics could hold at once, including what no tab may show. */
    private val everything = TopicRates(dollarIndex = dxy).merge(
        listOf(
            q("kb", "usd-krw", 1400.0), q("hana", "usd-krw", 1401.0), q("investing", "usd-krw", 1399.0),
            q("shinhan", "usd-krw", 1402.0), q("zz_unknown", "usd-krw", 1403.0),
            q("kb", "jpy-krw", 950.0), q("hana", "jpy-krw", 951.0),
            q("upbit", "usdt-krw", 1420.0), q("bithumb", "usdt-krw", 1421.0), q("binance", "usdt-krw", 1419.0),
            q("krx", "usd-krw-futures", 1405.0)
        )
    )

    private fun RateScale.ids() = groups.map { g -> g.asset to g.quotes.map { it.id }.toSet() }
    private fun TopicRateDisplay.allIds() = scales.flatMap { s -> s.groups.flatMap { g -> g.quotes.map { it.id } } }

    @Test
    fun D01_aDollarTab_showsTheDollarBanks_fromThePremiumSources_only() {
        val shown = everything.displayFor(FreeTab.USD)
        assertEquals(1, shown.scales.size)
        assertEquals(listOf("usd-krw" to setOf("kb", "hana", "investing", "shinhan")), shown.scales.single().ids())
    }

    @Test
    fun D02_eachFxTab_showsItsOwnAsset() {
        assertEquals(listOf("jpy-krw" to setOf("kb", "hana")), everything.displayFor(FreeTab.JPY).scales.single().ids())
        assertEquals(listOf("eur-krw" to emptySet<String>()), everything.displayFor(FreeTab.EUR).scales.single().ids())
    }

    @Test
    fun D03_theTetherTab_isOneList_withTheDefaultsShown_hanaFirst() {
        // S1.5-b4a: one list, one scale, first drawn row the reference; investing and kb off by default (iOS a36682f).
        val shown = everything.displayFor(FreeTab.TETHER)
        assertEquals(1, shown.scales.size)
        assertEquals(listOf("hana", "upbit", "bithumb"), shown.scales.single().groups.single().quotes.map { it.id })
    }

    @Test
    fun D04_withoutHana_theFirstExchangeLeads() {
        val shown = TopicRates().merge(listOf(q("kb", "usd-krw", 1400.0), q("upbit", "usdt-krw", 1420.0))).displayFor(FreeTab.TETHER)
        assertEquals(listOf("upbit"), shown.scales.single().groups.single().quotes.map { it.id })
        assertEquals(RateReference.FirstRow, shown.scales.single().groups.single().reference)
    }

    @Test
    fun D05_futuresAndUnknownSources_reachNoTab() {
        FreeTab.entries.forEach { tab ->
            val ids = everything.displayFor(tab).allIds()
            assertTrue("$tab showed krx", "krx" !in ids)
            assertTrue("$tab showed an unknown source", "zz_unknown" !in ids && "binance" !in ids)
        }
    }

    @Test
    fun D06_newsShowsNoRates_andEveryTabCarriesTheDollarIndexAsItIs() {
        assertEquals(emptyList<RateScale>(), everything.displayFor(FreeTab.NEWS).scales)
        FreeTab.entries.forEach { assertEquals("$it", dxy, everything.displayFor(it).dollarIndex) }
        assertEquals(null, TopicRates().displayFor(FreeTab.USD).dollarIndex)
    }

    @Test
    fun D07_theUsersArrangement_isHonouredThroughTheSameRoster() {
        val prefs = mapOf(
            RateRowList.FX_BANKS to RateRowPreference(order = listOf("hana", "kb", "investing", "shinhan"), hidden = setOf("shinhan")),
            RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("upbit"))
        )
        assertEquals(listOf("hana", "kb", "investing"), everything.displayFor(FreeTab.USD, prefs).scales.single().groups.single().quotes.map { it.id })
        assertEquals(listOf("investing", "kb", "hana", "bithumb"), everything.displayFor(FreeTab.TETHER, prefs).scales.single().groups.single().quotes.map { it.id })
    }

    @Test
    fun D08_citi_isNeverOnThePremiumFxList_evenWhenAskedFor() {
        // iOS a36682f `ExchangeRateViewModel.swift:617` drops Citi from the premium FX list; the free tab keeps it hidden by default.
        val withCiti = everything.merge(listOf(q("citi", "usd-krw", 1398.0)))
        val askedForEverything = mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = emptySet()))
        assertTrue("citi was shown", "citi" !in withCiti.displayFor(FreeTab.USD).allIds())
        assertTrue("citi was shown when every row was asked for", "citi" !in withCiti.displayFor(FreeTab.USD, askedForEverything).allIds())
    }

    @Test
    fun D09_withNoSavedArrangement_rowsFollowTheDefaultOrder_andTheFirstIsTheReference() {
        // iOS a36682f defaults: FX investing → kb → hana → shinhan → woori → ibk → nh → sc → bs (`Constants.swift:153-175`);
        // tether in registry order with investing and kb off (`RateSource.swift:41`, `SourcePreferenceManager.swift:141-159`).
        // Arrival order must not decide it.
        val fx = TopicRates().merge(
            listOf("bs", "sc", "nh", "ibk", "woori", "shinhan", "hana", "kb", "investing").mapIndexed { i, s -> q(s, "usd-krw", 1400.0 + i) }
        )
        val usd = fx.displayFor(FreeTab.USD).scales.single().groups.single()
        assertEquals(listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs"), usd.quotes.map { it.id })
        assertEquals(RateReference.FirstRow, usd.reference)
        val tether = TopicRates().merge(
            listOf("gopax", "korbit", "coinone", "bithumb", "upbit").map { q(it, "usdt-krw", 1420.0) } +
                listOf(q("hana", "usd-krw", 1401.0), q("kb", "usd-krw", 1400.0))
        ).displayFor(FreeTab.TETHER)
        assertEquals(listOf("hana", "upbit", "bithumb", "coinone", "korbit", "gopax"), tether.scales.single().groups.single().quotes.map { it.id })
    }
}
