package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.FreeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tab heading has to carry now that the rows print a bare number.
 *
 * The figures lost their `원` and `원 (100엔)` suffixes when the rows became bars — matching iOS and
 * the app's own paid rows, both of which print `%.2f` alone. For the won pairs nothing is lost, but
 * 950 for yen is meaningless without knowing whether it buys one yen or a hundred, so the unit has
 * to live somewhere and this is the only heading every figure below it belongs to.
 */
class FreeTabHeadingTest {

    @Test
    fun theYenTabSaysWhatItsFiguresArePer() {
        assertTrue("엔화 탭에 100엔 기준이 없다", FreeTab.JPY.heading.contains("100엔당"))
    }

    @Test
    fun theOtherCurrencyTabsNameTheirPairWithoutAUnit() {
        assertEquals("달러 · USD/KRW", FreeTab.USD.heading)
        assertEquals("유로 · EUR/KRW", FreeTab.EUR.heading)
    }

    /** The tabs that are not a currency pair keep their plain title. */
    @Test
    fun theNonCurrencyTabsKeepTheirTitle() {
        assertEquals(FreeTab.NEWS.title, FreeTab.NEWS.heading)
        assertEquals(FreeTab.TETHER.title, FreeTab.TETHER.heading)
    }
}
