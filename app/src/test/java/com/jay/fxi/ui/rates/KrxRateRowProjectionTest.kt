package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
import com.jay.fxi.domain.model.RateSourceSets
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S6-1 contract (R4c/S6/s6_0b/s6_1_contracts.r4.md 4.2, KP01-KP13): the premium rate-row projection with the KRX
 * row — S6C8 projection and the display half of S6C5. The projection only receives an already decided [KrxRowGate]; it decides no
 * access. The implementation thread reads but does not edit this file.
 *
 * Oracles: ANDROID_V2_PLAN.md :425 (I2 deny-by-default), :427 (I4, the raw original is preserved), :480 (D17 source list = the
 * original preserved + an effective projection), :1686-1694; memory `project_krx_topic_split.md` (three rules); iOS
 * `ExchangeRateViewModel.swift:639-643` (the KRX row is injected into the dollar tab), :673-689 (not tied to FX or tether
 * freshness, no time expiry), :751-752 (tether order filter); `BankPreferenceManager.swift:155-158` (krx first);
 * `SourcePreferenceManager.swift:41-50`, :141-160 (an unedited default follows krxVisible, krx first, hana↔krx — :154-159
 * `defaultVisibility`, hana is `!krxVisible`); `RateSource.swift:69-76` (label "달러선물").
 *
 * Invariant I-P (design 3.2): with `krx.visible` false the result is structurally the existing [displayFor] /
 * [editableRostersFor]. With `krx.visible` true and the row not drawn, no KRX row or placeholder appears; the only difference is
 * the gate-based hana default hide on TETHER when the user holds no hidden opinion (O-3 (I)). O-2 (krx first) and O-3 (I) are
 * the proposals these expectations are computed under.
 */
class KrxRateRowProjectionTest {

    private val t0 = Instant.parse("2026-09-29T00:00:00Z")
    private fun q(src: String, asset: String, rate: Double, at: Instant = t0) = TopicQuote(src, asset, rate, at)

    /** A dollar index so the dollarIndex comparisons are not null == null (design r4; TopicRateDisplayTest.kt:23-26). */
    private val dxy = TopicDollarIndex(98.5, t0, "investing")

    private val base = TopicRates(dollarIndex = dxy).merge(
        listOf(
            q("investing", "usd-krw", 1399.0), q("kb", "usd-krw", 1400.0), q("hana", "usd-krw", 1401.0),
            q("shinhan", "usd-krw", 1402.0), q("upbit", "usdt-krw", 1420.0), q("bithumb", "usdt-krw", 1421.0),
            q("kb", "jpy-krw", 950.0)
        )
    )
    private val withKrx = base.merge(listOf(q("krx", "usd-krw-futures", 1405.0)))
    private val hanaOnly = TopicRates().merge(listOf(q("hana", "usd-krw", 1401.0)))

    private val krxQuote = RateQuote("krx", "달러선물", 1405.0, t0, "usd-krw-futures")

    private val OFF0 = KrxRowGate(visible = false, tetherReceived = false)
    private val OFF1 = KrxRowGate(visible = false, tetherReceived = true)
    private val ON0 = KrxRowGate(visible = true, tetherReceived = false)
    private val ON1 = KrxRowGate(visible = true, tetherReceived = true)

    private val P_krx = mapOf(
        RateRowList.FX_BANKS to RateRowPreference(order = listOf("krx", "hana", "kb", "investing"), hidden = emptySet()),
        RateRowList.TETHER_EXCHANGES to RateRowPreference(order = listOf("krx", "upbit", "hana"), hidden = emptySet())
    )
    private val P_onlyKrx = mapOf(
        RateRowList.FX_BANKS to RateRowPreference(
            hidden = setOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs", "citi")
        ),
        RateRowList.TETHER_EXCHANGES to RateRowPreference(
            hidden = setOf("investing", "kb", "hana", "upbit", "bithumb", "coinone", "korbit", "gopax")
        )
    )
    private val P_hiddenKb = mapOf(
        RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("kb")),
        RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("kb"))
    )

    /** Mutable instances on purpose: an implementation that edits the caller's order or hidden set shows up in KP12. */
    private val P_hiddenKrx: Map<RateRowList, RateRowPreference> = mutableMapOf(
        RateRowList.FX_BANKS to RateRowPreference(
            order = mutableListOf("hana", "krx", "kb", "investing"), hidden = mutableSetOf("krx")
        ),
        RateRowList.TETHER_EXCHANGES to RateRowPreference(
            order = mutableListOf("upbit", "krx", "hana"), hidden = mutableSetOf("krx")
        )
    )

    private fun ids(display: TopicRateDisplay): List<String> = display.scales.single().groups.single().quotes.map { it.id }
    private fun rosterIds(rosters: Map<RateRowList, List<RateQuote>>, list: RateRowList) = rosters.getValue(list).map { it.id }

    /** I-P with a drawn-less KRX on TETHER: the existing display, minus the gate-hidden hana, and nothing else changed. */
    private fun withoutHana(display: TopicRateDisplay): TopicRateDisplay = display.copy(
        scales = display.scales.map { scale ->
            scale.copy(groups = scale.groups.map { group -> group.copy(quotes = group.quotes.filterNot { it.id == "hana" }) })
        }
    )

    /** Catches MP-01 (row added while hidden), MP-02 (futures always in `accepts`), MP-03 (krx filtered after the roster, so the rescue is lost: `[]`), MP-15 (placeholder / empty group). */
    @Test
    fun KP01_withoutTheGate_theProjectionIsTheExistingOne_C8() {
        val prefsCases = listOf(null, P_krx, P_onlyKrx)
        FreeTab.entries.forEach { tab ->
            listOf(OFF0, OFF1).forEach { gate ->
                listOf(base, withKrx).forEach { rates ->
                    prefsCases.forEach { prefs ->
                        assertEquals(
                            "$tab $gate $prefs",
                            rates.displayFor(tab, prefs),
                            rates.premiumDisplayFor(tab, prefs, krx = gate)
                        )
                    }
                    assertEquals("$tab $gate", rates.editableRostersFor(tab), rates.premiumEditableRostersFor(tab, krx = gate))
                }
            }
        }
        listOf(OFF0, OFF1).forEach { gate ->
            // D18 rescue survives: RateRowRoster.kt:94
            assertEquals("$gate", listOf("investing"), ids(withKrx.premiumDisplayFor(FreeTab.USD, P_onlyKrx, krx = gate)))
            assertEquals("$gate", listOf("hana"), ids(withKrx.premiumDisplayFor(FreeTab.TETHER, P_onlyKrx, krx = gate)))
        }
    }

    /** Catches MP-05 (USD tied to tetherReceived), MP-08 (label/asset typo), MP-02b (`accepts` missing — the presenter refuses), MP-10b (order). */
    @Test
    fun KP02_theDollarTab_showsKrxFirst_withoutWaitingForTether() {
        val on0 = withKrx.premiumDisplayFor(FreeTab.USD, null, krx = ON0)
        val on1 = withKrx.premiumDisplayFor(FreeTab.USD, null, krx = ON1)
        assertEquals(on1, on0)
        listOf(on0, on1).forEach { shown ->
            assertEquals(listOf("krx", "investing", "kb", "hana", "shinhan"), ids(shown))
            val group = shown.scales.single().groups.single()
            assertEquals(krxQuote, group.quotes.first())
            assertEquals("usd-krw", group.asset)
            assertEquals(setOf("usd-krw", "usd-krw-futures"), group.accepts)
            assertEquals(RateRowList.FX_BANKS, group.list)
            assertEquals(RateReference.FirstRow, group.reference)
            // RateRowPresenter.kt:109-116 checks each quote asset against accepts
            val firstRow = RateRowPresenter.present(shown.scales.single()).first().rows.first()
            assertEquals("krx", firstRow.id)
            assertTrue(firstRow.isReference)
            assertEquals(withKrx.displayFor(FreeTab.USD, null).dollarIndex, shown.dollarIndex)
        }
    }

    /** Catches MP-04 (tetherReceived ignored on tether), MP-11 (hana hidden despite a hidden opinion), MP-12 (hana default hide missing, O-3 adopted). */
    @Test
    fun KP03_theTetherTab_showsKrx_onlyAfterTether_andHidesHanaByDefault() {
        // ① ON1
        val on1 = withKrx.premiumDisplayFor(FreeTab.TETHER, null, krx = ON1)
        assertEquals(listOf("krx", "upbit", "bithumb"), ids(on1))
        assertEquals(setOf("usdt-krw", "usd-krw", "usd-krw-futures"), on1.scales.single().groups.single().accepts)
        // ② O-3 (I), SourcePreferenceManager.swift:154-159
        assertEquals(listOf("upbit", "bithumb"), ids(withKrx.premiumDisplayFor(FreeTab.TETHER, null, krx = ON0)))
        // ③ RateRowRoster.kt:121-122
        assertEquals(
            listOf("krx", "investing", "hana", "upbit", "bithumb"),
            ids(withKrx.premiumDisplayFor(FreeTab.TETHER, P_hiddenKb, krx = ON1))
        )
    }

    /** Catches MP-06 (placeholder without data, or the hana default hide missing while the gate is on). */
    @Test
    fun KP04_noKrxData_meansNoEmptySlot() {
        FreeTab.entries.forEach { tab ->
            listOf(null, P_krx).forEach { prefs ->
                val shown = base.premiumDisplayFor(tab, prefs, krx = ON1)
                val existing = base.displayFor(tab, prefs)
                if (tab == FreeTab.TETHER && prefs == null) {
                    // O-3 (I), iOS SourcePreferenceManager.swift:154-159
                    assertEquals(listOf("upbit", "bithumb"), ids(shown))
                    assertEquals(withoutHana(existing), shown)
                } else {
                    assertEquals("$tab $prefs", existing, shown)
                }
            }
        }
    }

    /** Catches MP-09 (age filter). */
    @Test
    fun KP05_krxHasNoTimeExpiry_S6C5rule2() {
        val old = t0 - 72.hours
        val rates = base.merge(listOf(q("krx", "usd-krw-futures", 1405.0, old)))
        assertEquals(
            RateQuote("krx", "달러선물", 1405.0, old, "usd-krw-futures"),
            rates.premiumDisplayFor(FreeTab.USD, null, krx = ON1).scales.single().groups.single().quotes.first()
        )
    }

    /** Catches MP-10 (krx-first overrides the stored order; position lost on restore). */
    @Test
    fun KP06_theRawOrderIsKept_andRestored() {
        // ①
        val p = mapOf(RateRowList.FX_BANKS to RateRowPreference(order = listOf("hana", "krx", "kb", "investing"), hidden = setOf("kb")))
        val off1 = withKrx.premiumDisplayFor(FreeTab.USD, p, krx = OFF1)
        assertEquals(listOf("hana", "investing", "shinhan"), ids(off1))
        assertEquals(withKrx.displayFor(FreeTab.USD, p), off1)
        assertEquals(listOf("hana", "krx", "investing", "shinhan"), ids(withKrx.premiumDisplayFor(FreeTab.USD, p, krx = ON1)))
        // ② krx absent from the stored order goes first (iOS BankPreferenceManager.swift:192-200), not appended (RateRowRoster.kt:110-118)
        val p2 = mapOf(RateRowList.FX_BANKS to RateRowPreference(order = listOf("hana", "kb", "investing"), hidden = emptySet()))
        assertEquals(listOf("krx", "hana", "kb", "investing", "shinhan"), ids(withKrx.premiumDisplayFor(FreeTab.USD, p2, krx = ON1)))
    }

    /** Catches MP-14 (krx added to a shared registry: free-path KRX-0 regression, FX graph admission polluted). */
    @Test
    fun KP07_theFreeAndSharedRegistriesAreUnchanged_premiumOnly() {
        assertNull(Bank.fromCode("krx"))
        assertNull(Exchange.fromCode("krx"))
        val shared = RateSourceSets.FX_RATE_SOURCES + RateSourceSets.PREMIUM_FX_SOURCES + RateSourceSets.EXCHANGES +
            RateSourceSets.TETHER_BANKS + RateSourceSets.TETHER_SOURCES
        assertTrue("krx" !in shared)
        assertEquals(setOf("citi"), RateRowRoster.hiddenByDefault(RateRowList.FX_BANKS))
        assertEquals(setOf("investing", "kb"), RateRowRoster.hiddenByDefault(RateRowList.TETHER_EXCHANGES))
    }

    /** Catches MP-13 (krx missing from, or leaking into, the editor). */
    @Test
    fun KP08_theEditorRoster() {
        // ①
        val usd = withKrx.premiumEditableRostersFor(FreeTab.USD, krx = ON0)
        assertEquals(setOf(RateRowList.FX_BANKS), usd.keys)
        assertEquals(listOf("krx", "investing", "kb", "hana", "shinhan"), rosterIds(usd, RateRowList.FX_BANKS))
        assertEquals(krxQuote, usd.getValue(RateRowList.FX_BANKS).first())
        // ②
        val tether = withKrx.premiumEditableRostersFor(FreeTab.TETHER, krx = ON1)
        assertEquals(setOf(RateRowList.TETHER_EXCHANGES), tether.keys)
        assertEquals(
            listOf("krx", "investing", "kb", "hana", "upbit", "bithumb"),
            rosterIds(tether, RateRowList.TETHER_EXCHANGES)
        )
        // ③
        assertEquals(withKrx.editableRostersFor(FreeTab.TETHER), withKrx.premiumEditableRostersFor(FreeTab.TETHER, krx = ON0))
    }

    /** Catches MP-07 (KRX injected into JPY / EUR / NEWS). */
    @Test
    fun KP09_otherTabs_areTheExistingProjection() {
        listOf(FreeTab.JPY, FreeTab.EUR, FreeTab.NEWS).forEach { tab ->
            listOf(null, P_krx).forEach { prefs ->
                val shown = withKrx.premiumDisplayFor(tab, prefs, krx = ON1)
                assertEquals("$tab $prefs", withKrx.displayFor(tab, prefs), shown)
                assertEquals("$tab $prefs", withKrx.displayFor(tab, prefs).dollarIndex, shown.dollarIndex)
            }
            assertEquals("$tab", withKrx.editableRostersFor(tab), withKrx.premiumEditableRostersFor(tab, krx = ON1))
        }
        assertEquals(emptyList<RateScale>(), withKrx.premiumDisplayFor(FreeTab.NEWS, null, krx = ON1).scales)
    }

    /** Catches a variant that treats krx as "hidden by default" so a rescue happens. */
    @Test
    fun KP10_D18_andKrx() {
        assertEquals(listOf("krx"), ids(withKrx.premiumDisplayFor(FreeTab.USD, P_onlyKrx, krx = ON1)))
        assertEquals(listOf("krx"), ids(withKrx.premiumDisplayFor(FreeTab.TETHER, P_onlyKrx, krx = ON1)))
    }

    /** Catches a KRX insertion that reorders or hides the other rows. */
    @Test
    fun KP11_nonKrxRows_areKept() {
        listOf(FreeTab.USD, FreeTab.TETHER).forEach { tab ->
            listOf(P_krx, P_hiddenKb).forEach { prefs ->
                assertEquals(
                    "$tab $prefs",
                    ids(withKrx.displayFor(tab, prefs)),
                    ids(withKrx.premiumDisplayFor(tab, prefs, krx = ON1)).filter { it != "krx" }
                )
            }
        }
        assertEquals(listOf("hana", "kb", "investing", "shinhan"), ids(withKrx.displayFor(FreeTab.USD, P_krx)))
        assertEquals(listOf("upbit", "hana", "investing", "kb", "bithumb"), ids(withKrx.displayFor(FreeTab.TETHER, P_krx)))
    }

    /**
     * Catches: krx-first ignoring the user's hidden (krx in on1); the TETHER hana default hide despite a hidden opinion (hana lost,
     * MP-11); the OFF/ON path sanitizing and editing the stored order or hidden (krx removed, hidden shrunk); state left between
     * calls (`on2 ≠ on1`); the editor dropping the hidden krx.
     */
    @Test
    fun KP12_theUsersKrxHide_isKept() {
        val expected = mapOf(
            FreeTab.USD to listOf("hana", "kb", "investing", "shinhan"),
            FreeTab.TETHER to listOf("upbit", "hana", "investing", "kb", "bithumb")
        )
        listOf(FreeTab.USD, FreeTab.TETHER).forEach { tab ->
            val on1 = withKrx.premiumDisplayFor(tab, P_hiddenKrx, krx = ON1)
            val off = withKrx.premiumDisplayFor(tab, P_hiddenKrx, krx = OFF1)
            val on2 = withKrx.premiumDisplayFor(tab, P_hiddenKrx, krx = ON1)
            assertEquals("$tab", expected.getValue(tab), ids(on1))
            assertEquals("$tab", ids(withKrx.displayFor(tab, P_hiddenKrx)), ids(on1))
            assertEquals("$tab", withKrx.displayFor(tab, P_hiddenKrx), off)
            assertEquals("$tab", on1, on2)
            assertEquals(
                "$tab",
                RateRowPreference(listOf("hana", "krx", "kb", "investing"), setOf("krx")),
                P_hiddenKrx[RateRowList.FX_BANKS]
            )
            assertEquals(
                "$tab",
                RateRowPreference(listOf("upbit", "krx", "hana"), setOf("krx")),
                P_hiddenKrx[RateRowList.TETHER_EXCHANGES]
            )
        }
        // RateRowSources.kt:127-133: a hidden krx must stay re-enableable
        assertEquals(
            listOf("krx", "investing", "kb", "hana", "shinhan"),
            rosterIds(withKrx.premiumEditableRostersFor(FreeTab.USD, krx = ON1), RateRowList.FX_BANKS)
        )
        assertEquals(
            listOf("krx", "investing", "kb", "hana", "upbit", "bithumb"),
            rosterIds(withKrx.premiumEditableRostersFor(FreeTab.TETHER, krx = ON1), RateRowList.TETHER_EXCHANGES)
        )
    }

    /** Catches the existing `RateRowRoster.effective` rescue (static `hiddenByDefault`, RateRowRoster.kt:94) reviving hana (`[hana]`), and the hana default hide missing with the gate on (`[hana]`). */
    @Test
    fun KP13_theGateBasedHanaHide_isNotRescued() {
        val on1 = hanaOnly.premiumDisplayFor(FreeTab.TETHER, null, krx = ON1)
        assertEquals(1, on1.scales.size)
        assertEquals(1, on1.scales.single().groups.size)
        assertEquals(emptyList<String>(), ids(on1))
        assertEquals(withoutHana(hanaOnly.displayFor(FreeTab.TETHER, null)), on1)
        // OFF1: hana is outside the static {investing, kb}, so eligible (RateRowRoster.kt:89-92)
        val off1 = hanaOnly.premiumDisplayFor(FreeTab.TETHER, null, krx = OFF1)
        assertEquals(listOf("hana"), ids(off1))
        assertEquals(hanaOnly.displayFor(FreeTab.TETHER, null), off1)
    }
}
