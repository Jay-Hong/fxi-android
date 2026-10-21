package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * S6-1 contract S6C6 (D17): the existing graph selection policy driven with the server's real tether and usd 1d ids
 * (pure; no storage, network or UI; no new seam — [GraphSeriesSelectionPolicy] is used as it is).
 *
 * Oracles: ANDROID_V2_PLAN.md :480 D17 (the graph resets and persists only the hana/KRX pair; other series selections are
 * unchanged), :1688-1689 (only tether's hana.usd/krx.* pair leaves visible+initialized, then the new default is persisted), :427
 * I4 (the KRX effective projection does not overwrite the raw preference, except D17's graph pair); iOS GraphV2ViewModel.swift
 * :571-575, :652-660; server graph_v2_intraday.py:155-162, tether :158 (tether 1d default = upbit, bithumb, hana, krx). Design: R4c/S6/s6_0b
 * s6_1_contracts.r4.md 4.3 (rows KD01-KD05). The existing GraphSeriesSelectionPolicyTest is not changed; the mutants it already
 * catches (MD-08 :90 same value, MD-09 :55 toggle gate, via B2_07/B2_08) are kept there and not proved again here.
 *
 * Ids: all11 is the server's tether 1d order (graph_v2_intraday.py:90-102); usd11 is the server's usd 1d order (:116-135, :146-147).
 * `reset` is [GraphSeriesSelectionPolicy.resetKrxPairOnFlip].
 */
class KrxGraphSelectionPolicyTest {

    private companion object {
        const val U = "upbit.usdt-krw"
        const val Bt = "bithumb.usdt-krw"
        const val Co = "coinone.usdt-krw"
        const val Kr = "korbit.usdt-krw"
        const val G = "gopax.usdt-krw"
        const val K = "krx.usd-krw-futures"
        const val I = "investing.usd"
        const val KB = "kb.usd"
        const val H = "hana.usd"
        const val DXY = "dxy"
        const val DXF = "dxy_futures"

        /** Server tether 1d order (graph_v2_intraday.py:90-102). */
        val all11: Set<String> = linkedSetOf(U, Bt, Co, Kr, G, K, I, KB, H, DXY, DXF)

        /** Server usd 1d order (graph_v2_intraday.py:116-135, :146-147). */
        val usd11: Set<String> = linkedSetOf(
            I, K, KB, H, "shinhan.usd", "woori.usd", "ibk.usd", "nh.usd", "sc.usd", "bs.usd", DXY
        )

        /** Tether 1d default (graph_v2_intraday.py:155-162, tether :158). */
        val U11 = GraphSelectionUniverse.Catalog(all11, setOf(U, Bt, H, K))
    }

    private val policy = GraphSeriesSelectionPolicy

    private fun sel(v: Set<String>, i: Set<String>) = GraphSeriesSelection(v, i)

    private fun GraphSelectionChange.selection(): GraphSeriesSelection =
        (this as? GraphSelectionChange.Replace)?.selection ?: throw AssertionError("expected Replace, was $this")

    private fun initialize(current: GraphSeriesSelection?, tab: String, universe: GraphSelectionUniverse, krxVisible: Boolean) =
        policy.initialize(current, tab, universe, krxVisible)

    private fun toggle(current: GraphSeriesSelection, tab: String, id: String, toggleable: Set<String>, krxVisible: Boolean) =
        policy.toggle(current, tab, id, toggleable, krxVisible)

    private fun reset(current: GraphSeriesSelection?, tab: String, previousVisible: Boolean?, nextVisible: Boolean) =
        policy.resetKrxPairOnFlip(current, tab, previousVisible, nextVisible)

    /**
     * KD01: a D17 round trip with the real ids — a user's U off and DXY on survive every flip; only hana.usd/krx.* are reset,
     * so a K the user turned off comes back with the pair's new default.
     *
     * Mutants: MD-06 dropping the :23 hana exclusion (H in s4), a reset that leaves initialized ids (s4's newIds empty), a
     * toggle that removes the toggled id from initialized (caught at s2/s3). Deleting toggle's `initializedSeries + seriesId`
     * (GraphSeriesSelectionPolicy.kt:62, :77) is not observable here: DXY, U and K are already initialized when toggled (design r4).
     */
    @Test fun KD01_aRealIdD17RoundTripResetsOnlyThePair() {
        val s0 = initialize(null, "tether", U11, false).selection()
        assertEquals("s0", sel(setOf(U, Bt, H), all11 - K), s0)
        val s1 = toggle(s0, "tether", DXY, all11, false).selection()
        assertEquals("s1", sel(setOf(U, Bt, H, DXY), all11 - K), s1)
        val s2 = toggle(s1, "tether", U, all11, false).selection()
        assertEquals("s2", sel(setOf(Bt, H, DXY), all11 - K), s2)
        val s3 = reset(s2, "tether", false, true).selection()
        assertEquals("s3", sel(setOf(Bt, DXY), all11 - setOf(K, H)), s3)
        val s4 = initialize(s3, "tether", U11, true).selection()
        assertEquals("s4", sel(setOf(Bt, DXY, K), all11), s4)
        val s5 = toggle(s4, "tether", K, all11, true).selection()
        assertEquals("s5", sel(setOf(Bt, DXY), all11), s5)
        val s6 = reset(s5, "tether", true, false).selection()
        assertEquals("s6", sel(setOf(Bt, DXY), all11 - setOf(K, H)), s6)
        val s7 = initialize(s6, "tether", U11, false).selection()
        assertEquals("s7", sel(setOf(Bt, DXY, H), all11 - K), s7)
        val s8 = initialize(reset(s7, "tether", false, true).selection(), "tether", U11, true).selection()
        assertEquals("s8: the user's K-off is reset with the pair; U off and DXY on stay", sel(setOf(Bt, DXY, K), all11), s8)

        assertEquals("rendered s7", setOf(Bt, DXY, H), policy.renderedIds(s7, all11, false))
        assertEquals("rendered s8", setOf(Bt, DXY, K), policy.renderedIds(s8, all11, true))
    }

    /**
     * KD02: a mismatched record (K only in visible, H only in initialized) loses both pair members from both sets.
     *
     * Mutants: MD-01 :93 built from initialized only (K stays in v), MD-02 built from visible only (H stays in i).
     */
    @Test fun KD02_aMismatchedRecordLosesThePairFromBothSets() {
        assertEquals(
            sel(setOf(I, Bt), setOf(I, Bt)),
            reset(sel(setOf(I, K, Bt), setOf(I, Bt, H)), "tether", false, true).selection()
        )
    }

    /**
     * KD03: a record without the pair is not rewritten — the very same Unchanged object.
     *
     * Mutant: MD-05 removing the :96 isEmpty guard (a Replace with equal values).
     */
    @Test fun KD03_noPairMeansUnchanged() {
        assertSame(GraphSelectionChange.Unchanged, reset(sel(setOf(U, Bt), setOf(U, Bt, DXY)), "tether", true, false))
    }

    /**
     * KD04: look-alike ids are not the pair; every `krx.`-prefixed id is.
     *
     * Mutants: MD-03 `== HANA_USD` → `startsWith("hana")`, MD-04 prefix `"krx."` → `"krx"`, a `== K`-only variant (night stays).
     */
    @Test fun KD04_lookAlikeIdsAreNotThePair() {
        val v = setOf(K, "krxx.usd", "hana.usdx", KB, "krx")
        val start = sel(v, v + "krx.usd-krw-night")
        val kept = setOf("krxx.usd", "hana.usdx", KB, "krx")
        assertEquals(sel(kept, kept), reset(start, "tether", true, false).selection())
    }

    /**
     * KD05: other tabs are untouched by the flip, and hana stays in the usd default when KRX is visible.
     *
     * Mutant: MD-07 removing `tab == TETHER_TAB &&` at :23 (H drops out in ③).
     */
    @Test fun KD05_otherTabsAreUnchanged() {
        val start = sel(setOf(I, K, H), usd11)
        val usdCatalog = GraphSelectionUniverse.Catalog(usd11, setOf(I, H))

        assertEquals("① reset in usd", GraphSelectionChange.Unchanged, reset(start, "usd", false, true))
        assertEquals("② initialize in usd", GraphSelectionChange.Unchanged, initialize(start, "usd", usdCatalog, false))
        assertEquals("② rendered", setOf(I, H), policy.renderedIds(start, usd11, false))
        assertEquals("③ first usd init, KRX visible", sel(setOf(I, H), usd11), initialize(null, "usd", usdCatalog, true).selection())
    }
}
