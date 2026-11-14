package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateSourceSets
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S7 E5b3b contract (s7e5b3_api_agreed.r1.md D4): the bank alert editor's prices. The presenter carries every FX bank quote of the
 * accepted tab — hidden rows included, the premium FX sources only (no Citi), finite and positive only — by source code, and an
 * empty map for the tether and news tabs and for every state that is not the current owner's.
 */
class S7E5B3BAlertQuotesTest {
    private val a1 = AuthIdentityFence("A", 1L)
    private val b1 = AuthIdentityFence("B", 1L)
    private val t1 = Instant.parse("2026-10-01T01:00:00Z")

    private fun q(source: String, asset: String, rate: Double) = TopicQuote(source, asset, rate, t1)
    private val rates = TopicRates().merge(listOf(
        q("investing", "usd-krw", 1389.0), q("kb", "usd-krw", 1390.0), q("hana", "usd-krw", 1391.0), q("citi", "usd-krw", 1392.0),
        q("kb", "jpy-krw", 950.12), q("woori", "eur-krw", 1600.5),
        q("upbit", "usdt-krw", 1400.0)))
    private fun display(r: TopicRates = rates, owner: TopicDisplayOwner? = TopicDisplayOwner(a1, 1L)) =
        TopicDisplayState(owner, r, false, TopicConnectionDisplay.OPEN, cachedRefreshResolved = true)
    private fun present(tab: FreeTab, d: TopicDisplayState = display(), live: AuthIdentityFence = a1,
                        prefs: Map<RateRowList, RateRowPreference> = emptyMap()) =
        PremiumTopicPresenter.present(d, OwnedTopicFocus(a1, tab), live, prefs)

    @Test fun `Q01 each FX tab carries its own bank prices by source code, without Citi`() {
        assertEquals(mapOf("investing" to 1389.0, "kb" to 1390.0, "hana" to 1391.0), present(FreeTab.USD).alertQuotes)
        assertEquals(mapOf("kb" to 950.12), present(FreeTab.JPY).alertQuotes)
        assertEquals(mapOf("woori" to 1600.5), present(FreeTab.EUR).alertQuotes)
    }

    @Test fun `Q02 a hidden row keeps its price`() {
        val hideHana = mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("hana", "kb")))
        assertEquals(mapOf("investing" to 1389.0, "kb" to 1390.0, "hana" to 1391.0), present(FreeTab.USD, prefs = hideHana).alertQuotes)
    }

    @Test fun `Q03 only finite positive prices are carried`() {
        val odd = rates.merge(listOf(q("shinhan", "usd-krw", Double.NaN), q("nh", "usd-krw", 0.0), q("sc", "usd-krw", -1.0),
            q("bs", "usd-krw", Double.POSITIVE_INFINITY), q("ibk", "usd-krw", 1393.0)))
        assertEquals(mapOf("investing" to 1389.0, "kb" to 1390.0, "hana" to 1391.0, "ibk" to 1393.0), present(FreeTab.USD, display(odd)).alertQuotes)
    }

    @Test fun `Q04 the tether and news tabs and every non-current state carry none`() {
        assertEquals(emptyMap<String, Double>(), present(FreeTab.TETHER).alertQuotes)
        assertEquals(emptyMap<String, Double>(), present(FreeTab.NEWS).alertQuotes)
        assertEquals("another live identity", emptyMap<String, Double>(), present(FreeTab.USD, live = b1).alertQuotes)
        assertEquals("no owner", emptyMap<String, Double>(), present(FreeTab.USD, display(owner = null)).alertQuotes)
        assertEquals("NONE", emptyMap<String, Double>(), PremiumTopicUiState.NONE.alertQuotes)
        val ui = present(FreeTab.USD)
        assertEquals("a model for another tab is not the current one", emptyMap<String, Double>(),
            ui.forCurrent(display(), OwnedTopicFocus(a1, FreeTab.JPY), a1).alertQuotes)
        assertEquals("the current one keeps its prices", ui.alertQuotes, ui.forCurrent(display(), OwnedTopicFocus(a1, FreeTab.USD), a1).alertQuotes)
    }

    @Test fun `Q05 every premium FX source is carried with every row hidden, and no other source`() {
        val sources = RateSourceSets.PREMIUM_FX_SOURCES.toList()
        for ((t, pair) in listOf(FreeTab.USD to "usd-krw", FreeTab.JPY to "jpy-krw", FreeTab.EUR to "eur-krw").withIndex()) {
            val (tab, asset) = pair
            val expected = sources.mapIndexed { i, s -> s to 1000.0 + 100.0 * t + i + 0.25 }.toMap()
            val r = TopicRates().merge(expected.map { (s, v) -> q(s, asset, v) } +
                listOf(q("citi", asset, 1.5), q("upbit", asset, 2.5), q("nobank", asset, 3.5), q("kb", "usdt-krw", 4.5)))
            val hideAll = mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = sources.toSet()))
            assertEquals("$tab: every premium source, nothing else", expected, present(tab, display(r), prefs = hideAll).alertQuotes)
        }
    }
}
