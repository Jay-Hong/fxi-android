package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTabTest {

    /** `ANDROID_V2_PLAN.md:830`, and iOS `TabSelection.ordered`. Two surfaces, one order. */
    @Test
    fun theRowIsNewsTetherDollarYenEuro_andOpensOnDollar() {
        assertEquals(
            listOf("뉴스", "테더", "달러", "엔화", "유로"),
            FreeTab.entries.map { it.title }
        )
        assertEquals(FreeTab.USD, FreeTab.INITIAL)
    }

    /**
     * The titles are read off [SupportedCurrency] rather than repeated, so this is not a copy of
     * the line above: it fails if the two ever stop agreeing, which is the drift iOS's single
     * ordered list exists to prevent.
     */
    @Test
    fun currencyTabsBorrowTheirTitleAndAssetFromTheCurrency() {
        FreeTab.entries.mapNotNull { tab -> tab.currency?.let { tab to it } }.forEach { (tab, currency) ->
            assertEquals(currency.tabTitle, tab.title)
            // "usd" ↔ "usd-krw": the free endpoint's tab key is the currency's code without the quote.
            assertEquals(currency.code, "${tab.serverTab}-krw")
        }
        assertEquals(
            listOf(SupportedCurrency.USD_KRW, SupportedCurrency.JPY_KRW, SupportedCurrency.EUR_KRW),
            FreeTab.entries.mapNotNull { it.currency }
        )
    }

    /** 뉴스 is the only tab with no snapshot behind it, and the scheduler must know that. */
    @Test
    fun newsAloneHasNoServerTab() {
        assertNull(FreeTab.NEWS.serverTab)
        assertTrue(FreeTab.entries.filterNot { it.isData }.singleOrNull() == FreeTab.NEWS)
        assertEquals(listOf("tether", "usd", "jpy", "eur"), FreeTab.entries.mapNotNull { it.serverTab })
        assertEquals(FreeTab.TETHER, FreeTab.forServerTab("tether"))
        assertNull("뉴스 must not be reachable from a server tab key", FreeTab.forServerTab(null))
        assertNull(FreeTab.forServerTab("krx"))
    }

    /**
     * The stored value survives reordering and retitling — an ordinal or a title would not — and
     * anything it cannot recognise opens on 달러 rather than failing.
     */
    @Test
    fun storageRoundTrips_andAnythingUnrecognisedOpensOnDollar() {
        FreeTab.entries.forEach { assertEquals(it, FreeTab.fromStorageValue(it.storageValue)) }
        assertEquals(FreeTab.INITIAL, FreeTab.fromStorageValue(null))
        assertEquals(FreeTab.INITIAL, FreeTab.fromStorageValue(""))
        assertEquals(FreeTab.INITIAL, FreeTab.fromStorageValue("KRX"))
        // Not the ordinal, and not the display title.
        assertEquals(FreeTab.INITIAL, FreeTab.fromStorageValue("0"))
        assertEquals(FreeTab.INITIAL, FreeTab.fromStorageValue("달러"))
    }
}
