package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
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
            RateRowRoster.effective(RateRowList.FX_BANKS, fxPayload)
        )
        assertEquals(exchanges, RateRowRoster.effective(RateRowList.TETHER_EXCHANGES, exchanges))
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
        assertEquals(listOf("kb", "nonghyup2"), RateRowRoster.effective(RateRowList.FX_BANKS, withStranger))
    }

    /** A hidden code that did not arrive changes nothing — the roster is not a required list. */
    @Test
    fun aHiddenCodeThatDidNotArriveIsNotMissed() {
        val withoutCiti = listOf("investing", "kb")
        assertEquals(withoutCiti, RateRowRoster.effective(RateRowList.FX_BANKS, withoutCiti))
    }

    /** Nothing arrived: nothing is drawn, and no row is invented to fill the space. */
    @Test
    fun anEmptyPayloadDrawsNothing() {
        assertEquals(emptyList<String>(), RateRowRoster.effective(RateRowList.FX_BANKS, emptyList()))
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
        assertEquals(emptyList<String>(), RateRowRoster.effective(RateRowList.FX_BANKS, listOf("citi")))
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
