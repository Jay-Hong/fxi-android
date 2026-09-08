package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a list draws, before anything is drawn.
 *
 * The half of `SourcePreferenceManager` that has an answer today. The user's own order and their
 * hidden set arrive with the sheet that writes them; these are the defaults and the projection that
 * both halves go through.
 */
class RateRowRosterTest {

    private val fxPayload = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs", "citi")
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")

    /** `ANDROID_V2_PLAN.md:800` — and only Citi, so a future addition here is a deliberate one. */
    @Test
    fun citiIsTheOnlyBankAFreshInstallHides() {
        assertEquals(setOf("citi"), RateRowRoster.hiddenByDefault(RateRowList.FX_BANKS))
        assertEquals(emptySet<String>(), RateRowRoster.hiddenByDefault(RateRowList.TETHER_EXCHANGES))
    }

    /** Everything that arrived, in the order it arrived, minus what the default hides. */
    @Test
    fun theOrderIsTheServersAndOnlyTheHiddenAreRemoved() {
        assertEquals(
            fxPayload - "citi",
            RateRowRoster.effective(RateRowList.FX_BANKS, fxPayload).codes
        )
        assertEquals(exchanges, RateRowRoster.effective(RateRowList.TETHER_EXCHANGES, exchanges).codes)
    }

    /**
     * A code the app has never heard of is kept.
     *
     * The sanitizer already decided what may be shown. By the time a code is here it is a real
     * quote, and dropping it would take a number off the screen with nothing left to notice by.
     */
    @Test
    fun aCodeTheRosterHasNeverHeardOfIsKept() {
        val withStranger = listOf("kb", "nonghyup2", "citi")
        assertEquals(listOf("kb", "nonghyup2"), RateRowRoster.effective(RateRowList.FX_BANKS, withStranger).codes)
    }

    /** A hidden code that did not arrive changes nothing — the roster is not a required list. */
    @Test
    fun aHiddenCodeThatDidNotArriveIsNotMissed() {
        val withoutCiti = listOf("investing", "kb")
        assertEquals(withoutCiti, RateRowRoster.effective(RateRowList.FX_BANKS, withoutCiti).codes)
    }

    /** Nothing arrived: nothing is drawn, and no row is invented to fill the space. */
    @Test
    fun anEmptyPayloadDrawsNothing() {
        assertEquals(emptyList<String>(), RateRowRoster.effective(RateRowList.FX_BANKS, emptyList()).codes)
    }

    /**
     * The user's order is honoured, and a code they have never seen is appended where it arrived.
     *
     * Inserting it next to a neighbour would move rows the user did arrange, and a stored order
     * says nothing about where an unseen code belongs.
     */
    @Test
    fun theStoredOrderIsHonouredAndUnseenCodesFollowIt() {
        val preference = RateRowPreference(order = listOf("hana", "kb", "investing"))
        val arrived = listOf("investing", "kb", "hana", "shinhan")
        assertEquals(
            listOf("hana", "kb", "investing", "shinhan"),
            RateRowRoster.effective(RateRowList.FX_BANKS, arrived, preference).codes
        )
    }

    /** A stored code that did not arrive is simply not drawn — the order is not a required list. */
    @Test
    fun aStoredCodeThatDidNotArriveIsSkipped() {
        val preference = RateRowPreference(order = listOf("hana", "sc", "kb"))
        assertEquals(
            listOf("hana", "kb"),
            RateRowRoster.effective(RateRowList.FX_BANKS, listOf("kb", "hana"), preference).codes
        )
    }

    /**
     * An empty hidden set is an opinion, not an absence: it shows Citi.
     *
     * This is the whole reason the two axes are nullable rather than defaulted. If "hidden = {}"
     * were read as "no preference", a user who deliberately turned Citi on would find it off again
     * on the next launch.
     */
    @Test
    fun anEmptyHiddenSetShowsWhatTheDefaultHides() {
        val preference = RateRowPreference(hidden = emptySet())
        assertEquals(
            listOf("kb", "citi"),
            RateRowRoster.effective(RateRowList.FX_BANKS, listOf("kb", "citi"), preference).codes
        )
    }

    /**
     * Everything hidden draws one default-visible row, reported as projected — D18's rescue.
     *
     * The rescue must not look like a choice: the sheet shows the row as temporary and nothing
     * saves it back, which is what D18's "원본은 바꾸지 않고" asks for. And it never rescues a row
     * the *defaults* hide — Citi stays off even here, or the default would mean nothing.
     */
    @Test
    fun hidingEverythingDrawsOneRowAndSaysItIsProjected() {
        val allOff = RateRowPreference(hidden = setOf("kb", "hana", "citi"))
        val selection = RateRowRoster.effective(RateRowList.FX_BANKS, listOf("kb", "hana", "citi"), allOff)
        assertEquals(listOf("kb"), selection.codes)
        assertEquals("kb", selection.projected)

        // …and with only default-hidden codes present there is no candidate, so nothing is drawn.
        val onlyCiti = RateRowRoster.effective(RateRowList.FX_BANKS, listOf("citi"), RateRowPreference(hidden = setOf("citi")))
        assertEquals(emptyList<String>(), onlyCiti.codes)
        assertNull(onlyCiti.projected)
    }

    /** Nothing is projected when nothing needed rescuing. */
    @Test
    fun nothingIsProjectedWhenSomethingSurvives() {
        assertNull(RateRowRoster.effective(RateRowList.FX_BANKS, fxPayload).projected)
    }

    /**
     * A payload of nothing but hidden codes draws nothing. Reviewed into existence.
     *
     * The first version fell back to the first code that arrived, which put Citi back on screen —
     * the opposite of what the default says. And it is reachable: the sanitizer drops entries one
     * at a time (`FreeSnapshotSanitizer.kt:129-141`), so a payload can arrive holding Citi alone.
     *
     * D18's rescue projects one *default-visible* candidate, and Citi is not one. It needs a stored
     * hidden set to be distinct from the defaults before it has any candidate to offer, so it
     * belongs to the slice that adds the store — not here, wearing the wrong fallback.
     */
    @Test
    fun aPayloadOfOnlyHiddenCodesDrawsNothing() {
        assertEquals(emptyList<String>(), RateRowRoster.effective(RateRowList.FX_BANKS, listOf("citi")).codes)
    }

    /**
     * The tether exchanges sit exactly on the scale policy's small-N threshold.
     *
     * Five sources, and `ScalePolicyCalculator` needs five before it will clip an outlier. Hiding
     * one drops the whole section to the raw range, so one bad quote squashes the other four —
     * which is a real consequence of a checkbox, not a rounding detail. Nothing can hide one today;
     * this is the number that has to be looked at again when something can.
     */
    @Test
    fun hidingOneExchangeWouldCrossTheScalePolicyThreshold() {
        assertEquals(ScalePolicyCalculator.DEFAULT_MIN_VISIBLE_FOR_ROBUST, exchanges.size)
        val values = listOf(1402.0, 1401.5, 1403.0, 1400.5, 1460.0)
        assertTrue("다섯이면 이상치를 잘라낸다", ScalePolicyCalculator.computeValues(values).isRobustActive)
        assertTrue(
            "넷이면 raw 로 떨어진다",
            !ScalePolicyCalculator.computeValues(values.drop(1)).isRobustActive
        )
    }
}
