package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.ui.free.heading
import com.jay.fxi.ui.rates.RateRowPresenter
import com.jay.fxi.ui.rates.RateRowsView
import com.jay.fxi.ui.rates.displayFor
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C3b contract (R4c/C3b/design_codex.r2.md, rounds r1→r2 agreed). The premium presenter draws the accepted tab
 * from the session's display state through the free tab's own projection and presenter, only while the display owner, the accepted
 * focus and the live identity are exactly the same account and session; the status line follows iOS (offline, or a stored price
 * still being refreshed); the editor offers hidden sources too; and the next row preference keeps what the sheet did not speak for.
 * The projection's own rules (sources, reference row, differences, scale) stay with TopicRateDisplayTest and RateRowPresenterTest.
 * The implementation reads but does not edit this file.
 */
class PremiumTopicPresenterTest {
    private val a1 = AuthIdentityFence("A", 1L)
    private val t1 = Instant.parse("2026-10-01T01:00:00Z")
    private val t2 = Instant.parse("2026-10-01T02:00:00Z")
    private val t3 = Instant.parse("2026-10-01T03:00:00Z")
    private val dxy = TopicDollarIndex(98.5, t3, "investing")

    private fun q(source: String, asset: String, rate: Double, at: Instant = t1) = TopicQuote(source, asset, rate, at)
    private fun rates(vararg quotes: TopicQuote, index: TopicDollarIndex? = null) = TopicRates(dollarIndex = index).merge(quotes.toList())
    private val full = rates(
        q("kb", "usd-krw", 1390.0), q("hana", "usd-krw", 1391.0, t2),
        q("kb", "jpy-krw", 950.12), q("kb", "eur-krw", 1600.0),
        q("upbit", "usdt-krw", 1400.0), q("bithumb", "usdt-krw", 1401.0), q("investing", "usd-krw", 1389.0),
        index = dxy)
    private fun display(r: TopicRates = full, seed: Boolean = false, connection: TopicConnectionDisplay = TopicConnectionDisplay.OPEN,
                        owner: TopicDisplayOwner? = TopicDisplayOwner(a1, 1L), resolved: Boolean = false) =
        TopicDisplayState(owner, r, seed, connection, cachedRefreshResolved = resolved)
    private fun present(tab: FreeTab, d: TopicDisplayState = display(), prefs: Map<RateRowList, RateRowPreference> = emptyMap()) =
        PremiumTopicPresenter.present(d, OwnedTopicFocus(a1, tab), a1, prefs)
    /** What the free projection and presenter draw for [tab], keeping an empty section only where an editor speaks for it. */
    private fun expectedSections(r: TopicRates, tab: FreeTab, prefs: Map<RateRowList, RateRowPreference>, editors: Set<RateRowList>): List<RateRowsView> =
        r.displayFor(tab, prefs).scales.flatMap { RateRowPresenter.present(it) }
            .filter { it.rows.isNotEmpty() || (it.list != null && it.list in editors) }

    @Test fun `C3b-01 each tab is drawn through the free projection, with its heading and the preferences given`() {
        val prefs = mapOf(RateRowList.FX_BANKS to RateRowPreference(order = listOf("hana", "kb")))
        for (tab in FreeTab.entries) {
            val ui = present(tab, prefs = prefs)
            assertEquals("C3b-01 $tab owner", TopicDisplayOwner(a1, 1L), ui.owner)
            assertEquals("C3b-01 $tab selected", tab, ui.selectedTab)
            assertEquals("C3b-01 $tab heading", tab.heading, ui.heading)
            assertEquals("C3b-01 $tab sections", expectedSections(full, tab, prefs, ui.rowEditors.map { it.list }.toSet()), ui.rateSections)
        }
        assertTrue("C3b-01 news has no rows", present(FreeTab.NEWS).rateSections.isEmpty())
        assertTrue("C3b-01 news has no editor", present(FreeTab.NEWS).rowEditors.isEmpty())
        assertEquals("C3b-01 the preference reached the rows", "hana", present(FreeTab.USD, prefs = prefs).rateSections.first().rows.first().id)
    }

    @Test fun `C3b-02 the yen tab keeps the figure and names its unit in the heading`() {
        val ui = present(FreeTab.JPY)
        assertEquals("C3b-02 value", 950.12, ui.rateSections.flatMap { it.rows }.single { it.id == "kb" }.value, 0.0)
        assertTrue("C3b-02 unit", ui.heading!!.contains("100엔당"))
    }

    @Test fun `C3b-03 the dollar index is offered on the dollar and tether tabs only, and never makes rows`() {
        for (tab in FreeTab.entries) {
            val expected = if (tab == FreeTab.USD || tab == FreeTab.TETHER) dxy else null
            assertEquals("C3b-03 $tab", expected, present(tab).dollarIndex)
        }
        val onlyIndex = present(FreeTab.USD, display(rates(index = dxy)))
        assertEquals("C3b-03 index alone", dxy, onlyIndex.dollarIndex)
        // Battery r1 (PM8): an empty heading with no editor to speak for it is not drawn at all.
        assertTrue("C3b-03 no section from the index", onlyIndex.rateSections.isEmpty())
        assertTrue("C3b-03 no editor from the index", onlyIndex.rowEditors.isEmpty())
    }

    @Test fun `C3b-04 anything but the same account and session in all three shows nothing`() {
        val d = display(seed = true, connection = TopicConnectionDisplay.OFFLINE)
        val cases = mapOf(
            "no owner" to PremiumTopicPresenter.present(display(seed = true, connection = TopicConnectionDisplay.OFFLINE, owner = null), OwnedTopicFocus(a1, FreeTab.USD), a1),
            "no focus" to PremiumTopicPresenter.present(d, null, a1),
            "no live" to PremiumTopicPresenter.present(d, OwnedTopicFocus(a1, FreeTab.USD), null),
            "focus other uid" to PremiumTopicPresenter.present(d, OwnedTopicFocus(AuthIdentityFence("B", 1L), FreeTab.USD), a1),
            "focus earlier session" to PremiumTopicPresenter.present(d, OwnedTopicFocus(AuthIdentityFence("A", 2L), FreeTab.USD), a1),
            "live other session" to PremiumTopicPresenter.present(d, OwnedTopicFocus(a1, FreeTab.USD), AuthIdentityFence("A", 2L)),
            "owner other session" to PremiumTopicPresenter.present(display(seed = true, connection = TopicConnectionDisplay.OFFLINE,
                owner = TopicDisplayOwner(AuthIdentityFence("A", 2L), 1L)), OwnedTopicFocus(a1, FreeTab.USD), a1)
        )
        for ((name, ui) in cases) assertEquals("C3b-04 $name", PremiumTopicUiState.NONE, ui)
    }

    @Test fun `C3b-05 a held model is kept only for the same owner, grant turn and tab`() {
        val d = display()
        val ui = present(FreeTab.USD, d)
        assertEquals("C3b-05 same", ui, ui.forCurrent(d, OwnedTopicFocus(a1, FreeTab.USD), a1))
        assertEquals("C3b-05 new grant turn", PremiumTopicUiState.NONE, ui.forCurrent(display(owner = TopicDisplayOwner(a1, 2L)), OwnedTopicFocus(a1, FreeTab.USD), a1))
        assertEquals("C3b-05 owner gone", PremiumTopicUiState.NONE, ui.forCurrent(display(owner = null), OwnedTopicFocus(a1, FreeTab.USD), a1))
        assertEquals("C3b-05 tab moved", PremiumTopicUiState.NONE, ui.forCurrent(d, OwnedTopicFocus(a1, FreeTab.JPY), a1))
        assertEquals("C3b-05 live moved", PremiumTopicUiState.NONE, ui.forCurrent(d, OwnedTopicFocus(a1, FreeTab.USD), AuthIdentityFence("A", 2L)))
    }

    // R4-c F2 revision (R4c/C2b/design_codex.r2.md §6, agreed): a stored price is announced as being refreshed only until the session
    // accepts an answer in the current round (`cachedRefreshResolved`); offline is unchanged, and no banner means no time.
    @Test fun `C3b-06 the status line follows iOS, and its time is the latest shown observation`() {
        for (tab in FreeTab.entries)
            for (c in TopicConnectionDisplay.entries) for (seed in listOf(false, true)) for (resolved in listOf(false, true))
                for (empty in listOf(false, true)) {
                    val ui = present(tab, display(if (empty) TopicRates() else full, seed, c, resolved = resolved))
                    val banner = when {
                        c == TopicConnectionDisplay.OFFLINE -> PremiumTopicBanner.OFFLINE
                        seed && !resolved -> PremiumTopicBanner.REFRESHING_CACHED
                        else -> null
                    }
                    assertEquals("C3b-06 banner $tab $c seed=$seed resolved=$resolved empty=$empty", banner, ui.statusBanner)
                    if (banner == null || empty) {
                        assertNull("C3b-06 no time $tab $c seed=$seed resolved=$resolved empty=$empty", ui.lastUpdated)
                    }
                }
        assertEquals("C3b-06 texts", listOf("오프라인 모드", "저장된 환율 · 최신 데이터 확인 중"), PremiumTopicBanner.entries.map { it.text })
        val offline = display(connection = TopicConnectionDisplay.OFFLINE)
        assertEquals("C3b-06 dollar tab counts its index", t3, present(FreeTab.USD, offline).lastUpdated)
        assertEquals("C3b-06 yen tab ignores the index and other tabs", t1, present(FreeTab.JPY, offline).lastUpdated)
        val noIndex = display(rates(q("kb", "usd-krw", 1390.0), q("hana", "usd-krw", 1391.0, t2)), connection = TopicConnectionDisplay.OFFLINE)
        assertEquals("C3b-06 latest row", t2, present(FreeTab.USD, noIndex).lastUpdated)
        val hideHana = mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("hana")))
        assertEquals("C3b-06 a hidden row does not count", t1, present(FreeTab.USD, noIndex, hideHana).lastUpdated)
    }

    @Test fun `C3b-07 an empty roster keeps the tab, and a default-hidden arrival keeps its editor`() {
        val empty = present(FreeTab.USD, display(TopicRates(), connection = TopicConnectionDisplay.OFFLINE))
        assertEquals("C3b-07 tab kept", FreeTab.USD, empty.selectedTab)
        assertEquals("C3b-07 heading kept", FreeTab.USD.heading, empty.heading)
        assertTrue("C3b-07 no section", empty.rateSections.isEmpty())
        assertTrue("C3b-07 no editor", empty.rowEditors.isEmpty())
        assertEquals("C3b-07 banner kept", PremiumTopicBanner.OFFLINE, empty.statusBanner)

        val kbOnly = rates(q("kb", "usd-krw", 1390.0))
        val tether = present(FreeTab.TETHER, display(kbOnly))
        val editor = tether.rowEditors.single { it.list == RateRowList.TETHER_EXCHANGES }
        assertEquals("C3b-07 kb offered, hidden by default", false, editor.entries.single { it.code == "kb" }.visible)
        assertEquals("C3b-07 sections as projected", expectedSections(kbOnly, FreeTab.TETHER, emptyMap(), setOf(RateRowList.TETHER_EXCHANGES)), tether.rateSections)
    }

    @Test fun `C3b-08 each call reflects only its input, values and times kept`() {
        val seed = display(rates(q("kb", "usd-krw", 1390.0)), seed = true)
        val mixed = display(rates(q("kb", "usd-krw", 1390.0), q("hana", "usd-krw", 1395.0, t2)), seed = true)
        val live = display(rates(q("kb", "usd-krw", 1392.0, t3), q("hana", "usd-krw", 1395.0, t2)))
        for ((name, d) in listOf("seed" to seed, "mixed" to mixed, "live" to live)) {
            val rows = present(FreeTab.USD, d).rateSections.flatMap { it.rows }.associateBy { it.id }
            for ((key, quote) in d.rates.quotes) if (key.asset == "usd-krw") {
                assertEquals("C3b-08 $name ${key.source} value", quote.rate, rows.getValue(key.source).value, 0.0)
                assertEquals("C3b-08 $name ${key.source} time", quote.at, rows.getValue(key.source).observedAt)
            }
        }
    }

    @Test fun `C3b-09 the editor offers hidden sources, and an all-hidden list shows one as temporary`() {
        val r = rates(q("investing", "usd-krw", 1389.0), q("kb", "usd-krw", 1390.0), q("upbit", "usdt-krw", 1400.0), q("bithumb", "usdt-krw", 1401.0))
        val prefs = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("investing", "kb", "upbit")))
        val entries = present(FreeTab.TETHER, display(r), prefs).rowEditors.single { it.list == RateRowList.TETHER_EXCHANGES }.entries
        assertEquals("C3b-09 every arrival offered", setOf("investing", "kb", "upbit", "bithumb"), entries.map { it.code }.toSet())
        assertEquals("C3b-09 hidden ones", setOf("investing", "kb", "upbit"), entries.filterNot { it.visible }.map { it.code }.toSet())
        assertTrue("C3b-09 nothing temporary while one is shown", entries.none { it.projected })

        val allHidden = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("investing", "kb", "upbit", "bithumb")))
        val rescued = present(FreeTab.TETHER, display(r), allHidden).rowEditors.single { it.list == RateRowList.TETHER_EXCHANGES }.entries
        assertEquals("C3b-09 one temporary", 1, rescued.count { it.projected })
        assertEquals("C3b-09 the temporary one is still a hidden choice", false, rescued.single { it.projected }.visible)
    }

    @Test fun `C3b-10 an unarrived source keeps its seat and its hidden choice, and a new one keeps the place given`() {
        val next = PremiumTopicPresenter.nextRowPreference(
            RateRowList.FX_BANKS,
            stored = RateRowPreference(order = listOf("kb", "sc", "hana", "bs"), hidden = setOf("sc", "hana")),
            seeded = listOf("kb", "hana", "investing"),
            order = listOf("investing", "hana", "kb"),
            hidden = setOf("kb"))
        assertEquals("C3b-10", RateRowPreference(order = listOf("investing", "sc", "hana", "bs", "kb"), hidden = setOf("sc", "kb")), next)
    }

    @Test fun `C3b-11 an unchanged order is not recorded, and hidden choices outside the sheet are kept`() {
        val fresh = PremiumTopicPresenter.nextRowPreference(RateRowList.TETHER_EXCHANGES, stored = null,
            seeded = listOf("upbit", "bithumb"), order = listOf("upbit", "bithumb"), hidden = setOf("bithumb"))
        assertEquals("C3b-11 no stored order, default hidden kept", RateRowPreference(order = null, hidden = setOf("investing", "kb", "bithumb")), fresh)

        val kept = PremiumTopicPresenter.nextRowPreference(RateRowList.FX_BANKS,
            stored = RateRowPreference(order = listOf("hana", "kb", "sc"), hidden = setOf("sc")),
            seeded = listOf("hana", "kb"), order = listOf("hana", "kb"), hidden = emptySet())
        assertEquals("C3b-11 stored order kept, unarrived sc stays hidden", RateRowPreference(order = listOf("hana", "kb", "sc"), hidden = setOf("sc")), kept)

        val back = present(FreeTab.USD, display(rates(q("kb", "usd-krw", 1390.0), q("sc", "usd-krw", 1391.0))), mapOf(RateRowList.FX_BANKS to kept))
        assertEquals("C3b-11 sc returns hidden", false, back.rowEditors.single().entries.single { it.code == "sc" }.visible)
    }
}
