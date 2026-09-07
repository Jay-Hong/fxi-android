package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.GraphSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphSeriesStyleTest {

    /**
     * A source that has both a graph colour and a bar colour is drawn in the graph one.
     *
     * 하나 and 인베스팅 each have two, and they differ. Reaching for [Bank] first would change
     * those two lines on the free surface alone, so the same source would be a different colour
     * depending on which tab it was drawn on.
     */
    @Test
    fun theGraphColourWinsWhereASourceHasTwo() {
        assertNotEquals(GraphSource.HANA.colorHex, Bank.HANA.colorHex)
        assertEquals(GraphSource.HANA.colorHex, GraphSeriesStyles.colorOf("hana.usd"))
        assertNotEquals(GraphSource.INVESTING.colorHex, Bank.INVESTING.colorHex)
        assertEquals(GraphSource.INVESTING.colorHex, GraphSeriesStyles.colorOf("investing.usd"))
        assertEquals(GraphSource.KB.colorHex, GraphSeriesStyles.colorOf("kb.usd"))
        assertEquals(GraphSource.DXY.colorHex, GraphSeriesStyles.colorOf("dxy"))
    }

    /** A 1dp line on a dark ground disappears at the brand colour, so four exchanges are lifted. */
    @Test
    fun theDarkExchangesAreLightenedForLinesOnly() {
        assertEquals(0xFF4691E8, GraphSeriesStyles.colorOf("upbit.usdt-krw"))
        assertEquals(0xFF7FD0FF, GraphSeriesStyles.colorOf("coinone.usdt-krw"))
        assertEquals(0xFFD6D6D6, GraphSeriesStyles.colorOf("korbit.usdt-krw"))
        assertEquals(0xFFFFC94D, GraphSeriesStyles.colorOf("gopax.usdt-krw"))
        // 빗썸's own orange is already legible, so it is the one exchange with no override.
        assertEquals(0xFFFF6D00, GraphSeriesStyles.colorOf("bithumb.usdt-krw"))
    }

    /** The two dollar indices must not look alike — they are drawn on the same axis. */
    @Test
    fun theFuturesIndexIsDistinctFromTheSpotIndex() {
        assertNotEquals(GraphSeriesStyles.colorOf("dxy"), GraphSeriesStyles.colorOf("dxy_futures"))
        assertEquals(0xFFFF6B6B, GraphSeriesStyles.colorOf("dxy_futures"))
        assertEquals("선물지수", GraphSeriesStyles.labelOf("dxy_futures", "DXY Futures"))
        assertEquals("달러지수", GraphSeriesStyles.labelOf("dxy", "Dollar Index"))
    }

    /** 우리·기업·SC were re-coloured as lines to break up a pile of blues. Their bars are not. */
    @Test
    fun threeBanksAreRecolouredForLinesOnly() {
        listOf("woori", "ibk", "sc").forEach { source ->
            assertNotEquals(
                "$source's line still uses its bar colour",
                Bank.fromCode(source)!!.colorHex,
                GraphSeriesStyles.colorOf("$source.usd")
            )
        }
        // Applied by source, so every currency of that bank matches.
        assertEquals(GraphSeriesStyles.colorOf("woori.usd"), GraphSeriesStyles.colorOf("woori.jpy"))
        // The banks that were legible keep theirs.
        assertEquals(Bank.SHINHAN.colorHex, GraphSeriesStyles.colorOf("shinhan.usd"))
        assertEquals(Bank.NH.colorHex, GraphSeriesStyles.colorOf("nh.eur"))
        assertEquals(Bank.BS.colorHex, GraphSeriesStyles.colorOf("bs.usd"))
    }

    /** The legend reads like the rate list, not like the wire. */
    @Test
    fun labelsUseTheAppsOwnNames() {
        assertEquals("국민은행", GraphSeriesStyles.labelOf("kb.usd", "KB국민은행"))
        assertEquals("기업은행", GraphSeriesStyles.labelOf("ibk.jpy", "IBK기업은행"))
        assertEquals("업비트", GraphSeriesStyles.labelOf("upbit.usdt-krw", "Upbit"))
        assertEquals("빗썸", GraphSeriesStyles.labelOf("bithumb.usdt-krw", "Bithumb"))
        // Nothing recognised: the server's own label, rather than a blank or an id.
        assertEquals("무언가", GraphSeriesStyles.labelOf("unknown.thing", "무언가"))
    }

    /** 인베스팅 is the reference rate, drawn slightly heavier — as on the paid graph. */
    @Test
    fun theReferenceRateIsDrawnHeavier() {
        assertEquals(1.3f, GraphSeriesStyles.of("investing.usd", "인베스팅").lineWidthDp, 0f)
        assertEquals(1.0f, GraphSeriesStyles.of("hana.usd", "하나").lineWidthDp, 0f)
        assertEquals(1.0f, GraphSeriesStyles.of("dxy", "달러지수").lineWidthDp, 0f)
    }

    /** An id the app has never heard of is still drawable — visibly, and with the server's name. */
    @Test
    fun anUnknownSeriesIsStillDrawable() {
        val style = GraphSeriesStyles.of("mystery.thing", "미지")
        assertEquals("미지", style.label)
        assertEquals(1.0f, style.lineWidthDp, 0f)
        assertTrue("an unknown series must not be invisible", style.colorHex != 0L)
    }

    /** Every series the free tabs can draw resolves to something deliberate. */
    @Test
    fun everyFreeSeriesHasAColourOfItsOwn() {
        val ids = listOf(
            "investing.usd", "kb.usd", "hana.usd", "shinhan.usd", "woori.usd",
            "ibk.usd", "nh.usd", "sc.usd", "bs.usd", "dxy",
            "upbit.usdt-krw", "bithumb.usdt-krw", "coinone.usdt-krw",
            "korbit.usdt-krw", "gopax.usdt-krw", "dxy_futures"
        )
        val colours = ids.map { GraphSeriesStyles.colorOf(it) }
        assertEquals("two series share a colour", ids.size, colours.distinct().size)
    }
}
