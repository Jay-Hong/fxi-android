package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 B2a contract r1: the graph series selection policy (pure; no storage, network or UI).
 *
 * Oracles: ANDROID_V2_PLAN.md :1292 (visibleSeriesIds and initializedSeries together tell a new default from a user's all-off),
 * :450 D17/D18 (the graph resets only the hana/KRX pair; no effective default projection - all-off is allowed), :1416 (the
 * pair reset is S6's flip); iOS a36682f GraphV2ViewModel.swift :731-775 (only uninitialised series take the catalog default; a
 * hidden KRX gate keeps krx.* out of initialisation), :777-789 (a toggle locks the series; a mutually exclusive counterpart is
 * turned off and locked), :344-356 (KRX visible: tether drops hana.usd from the default; hidden: krx.* dropped), :270-285 (the
 * tether flip removes hana.usd and krx.* from both sets), GraphV2Section.swift :491-499 (dxy and dxy_futures are exclusive in
 * tether only). Design: R4c/S4 b2_design_codex.r1 (API and rows B2-01..08; Android differs from iOS on purpose where noted:
 * a full-display fallback only when the catalog is missing, record presence rather than an empty set for "prior state", and
 * new ids under a missing catalog wait for it instead of being locked off).
 *
 * Ids are the server's real tether ids (exchange-rate app/graph_v2.py) plus FX short ids; X is an id the catalog never offers.
 */
class GraphSeriesSelectionPolicyTest {

    private companion object {
        const val A = "investing.usd"
        const val B = "kb.usd"
        const val C = "shinhan.usd"
        const val Z = "woori.usd"
        const val X = "ghost.usd"
        const val D = "bithumb.usdt-krw"
        const val H = "hana.usd"
        const val K = "krx.usd-krw-futures"
        const val K2 = "krx.usd-krw-night"
        const val DXY = "dxy"
        const val DXF = "dxy_futures"
    }

    private val policy = GraphSeriesSelectionPolicy

    private fun sel(v: Set<String>, i: Set<String>) = GraphSeriesSelection(v, i)

    private fun GraphSelectionChange.selection(): GraphSeriesSelection =
        (this as? GraphSelectionChange.Replace)?.selection ?: throw AssertionError("expected Replace, was $this")

    private fun catalog(all: Set<String>, default: Set<String>) = GraphSelectionUniverse.Catalog(all, default)

    private fun unavailable(admitted: Set<String>) = GraphSelectionUniverse.CatalogUnavailable(admitted)

    /** B2-01: a first initialisation turns on the catalog default inside the catalog only; the same input again writes nothing. */
    @Test fun B2_01_aFirstInitializationTakesTheDefaultInsideTheCatalog() {
        val universe = catalog(setOf(A, B), setOf(B, X))
        val first = policy.initialize(null, "usd", universe, krxVisible = true).selection()
        assertEquals(sel(setOf(B), setOf(A, B)), first)
        assertEquals("again: nothing to write", GraphSelectionChange.Unchanged, policy.initialize(first, "usd", universe, true))
    }

    /** B2-02: a catalog's empty default is respected; only a missing catalog shows everything the answer admitted. */
    @Test fun B2_02_aCatalogsEmptyDefaultIsNotAMissingCatalog() {
        assertEquals("catalog, empty default", sel(emptySet(), setOf(A, B)),
            policy.initialize(null, "usd", catalog(setOf(A, B), emptySet()), true).selection())
        assertEquals("no catalog: what the answer admitted", sel(setOf(A, B), setOf(A, B)),
            policy.initialize(null, "usd", unavailable(setOf(A, B)), true).selection())
        assertEquals("no catalog, empty answer", GraphSelectionChange.Unchanged,
            policy.initialize(null, "usd", unavailable(emptySet()), true))
        assertEquals("unsupported", GraphSelectionChange.Unchanged,
            policy.initialize(null, "usd", GraphSelectionUniverse.Unsupported, true))
    }

    /** B2-03: a restored all-off stays off, with or without a catalog; an empty record is a record, not an absence. */
    @Test fun B2_03_aRestoredAllOffStaysOff() {
        val allOff = sel(emptySet(), setOf(A, B))
        assertEquals("catalog default A", GraphSelectionChange.Unchanged,
            policy.initialize(allOff, "usd", catalog(setOf(A, B), setOf(A)), true))
        assertEquals("no catalog", GraphSelectionChange.Unchanged, policy.initialize(allOff, "usd", unavailable(setOf(A, B)), true))
        assertEquals("an empty record, no catalog", GraphSelectionChange.Unchanged,
            policy.initialize(sel(emptySet(), emptySet()), "usd", unavailable(setOf(A, B)), true))
    }

    /** B2-04: the selection is shared across periods; a new id takes its default; the raw sets keep ids of other periods. */
    @Test fun B2_04_aSelectionIsSharedAcrossPeriodsAndNewIdsTakeTheirDefault() {
        val next = policy.initialize(sel(setOf(A, Z), setOf(A, B, Z)), "usd", catalog(setOf(B, C), setOf(B, C)), true).selection()
        assertEquals(sel(setOf(A, C, Z), setOf(A, B, C, Z)), next)
        assertEquals("shown in this period", setOf(C), policy.renderedIds(next, setOf(B, C), true))
        assertEquals("back in the first period", setOf(A, Z), policy.renderedIds(next, setOf(A, B, Z), true))
    }

    /** B2-05: a toggle locks the series (its default never returns); the last series may go off; an id not offered is refused. */
    @Test fun B2_05_aToggleLocksTheSeries() {
        val off = policy.toggle(sel(setOf(A), setOf(A, B)), "usd", A, setOf(A, B), true).selection()
        assertEquals(sel(emptySet(), setOf(A, B)), off)
        assertEquals("the default does not return", GraphSelectionChange.Unchanged,
            policy.initialize(off, "usd", catalog(setOf(A, B), setOf(A)), true))
        assertEquals(sel(setOf(B), setOf(A, B)), policy.toggle(off, "usd", B, setOf(A, B), true).selection())
        assertTrue("not offered", policy.toggle(off, "usd", X, setOf(A, B), true) is GraphSelectionChange.Rejected)
    }

    /** B2-06: in tether, turning dxy_futures on turns dxy off and locks it; turning it off leaves dxy off; other tabs have no pair. */
    @Test fun B2_06_dxyAndItsFuturesAreExclusiveInTether() {
        val ids = setOf(DXY, DXF)
        val on = policy.toggle(sel(setOf(DXY), setOf(DXY)), "tether", DXF, ids, true).selection()
        assertEquals(sel(setOf(DXF), setOf(DXY, DXF)), on)
        assertEquals("the default does not bring dxy back", GraphSelectionChange.Unchanged,
            policy.initialize(on, "tether", catalog(ids, setOf(DXY)), true))
        assertEquals("off leaves dxy off", sel(emptySet(), setOf(DXY, DXF)), policy.toggle(on, "tether", DXF, ids, true).selection())
        assertEquals("another tab", sel(setOf(DXY, DXF), setOf(DXY, DXF)),
            policy.toggle(sel(setOf(DXY), setOf(DXY)), "usd", DXF, ids, true).selection())
    }

    /**
     * B2-07: a hidden KRX gate keeps krx.* out of initialisation and toggles; a shown gate makes KRX the tether default instead
     * of hana; a stored KRX choice is only projected away while hidden, never cut from the stored sets.
     */
    @Test fun B2_07_theKrxGateHoldsInitializationTogglesAndDisplay() {
        val universe = catalog(setOf(A, H, K), setOf(A, H, K))
        assertEquals("hidden", sel(setOf(A, H), setOf(A, H)), policy.initialize(null, "tether", universe, false).selection())
        assertEquals("shown", sel(setOf(A, K), setOf(A, H, K)), policy.initialize(null, "tether", universe, true).selection())
        assertTrue("no KRX toggle while hidden",
            policy.toggle(sel(setOf(A, H), setOf(A, H)), "tether", K, setOf(A, H, K), false) is GraphSelectionChange.Rejected)
        val stored = sel(setOf(A, K), setOf(A, K))
        assertEquals("projected away", setOf(A), policy.renderedIds(stored, setOf(A, K), false))
        assertEquals("kept in the stored sets", sel(setOf(A, H, K), setOf(A, H, K)),
            policy.initialize(stored, "tether", universe, false).selection())
    }

    /**
     * B2-08: a real KRX flip in tether removes hana.usd and every stored krx.* from both sets, then the new gate initialises
     * them again; nothing else changes. No reset for the same value, for the first baseline (no previous value) or another tab.
     */
    @Test fun B2_08_aRealKrxFlipResetsOnlyTheHanaKrxPairInTether() {
        val start = sel(setOf(A, D, H, Z), setOf(A, D, H, K, K2, Z))
        val universe = catalog(setOf(A, D, H, K), setOf(A, H, K))
        val reset = policy.resetKrxPairOnFlip(start, "tether", previousVisible = false, nextVisible = true).selection()
        assertEquals("reset", sel(setOf(A, D, Z), setOf(A, D, Z)), reset)
        val shown = policy.initialize(reset, "tether", universe, true).selection()
        assertEquals("shown", sel(setOf(A, D, K, Z), setOf(A, D, H, K, Z)), shown)
        val back = policy.initialize(
            policy.resetKrxPairOnFlip(shown, "tether", previousVisible = true, nextVisible = false).selection(),
            "tether", universe, false
        ).selection()
        assertEquals("hidden again", sel(setOf(A, D, H, Z), setOf(A, D, H, Z)), back)
        assertEquals("same value", GraphSelectionChange.Unchanged, policy.resetKrxPairOnFlip(start, "tether", true, true))
        assertEquals("first baseline", GraphSelectionChange.Unchanged, policy.resetKrxPairOnFlip(start, "tether", null, true))
        assertEquals("another tab", GraphSelectionChange.Unchanged, policy.resetKrxPairOnFlip(start, "usd", false, true))
    }
}
