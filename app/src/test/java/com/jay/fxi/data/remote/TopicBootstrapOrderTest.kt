package com.jay.fxi.data.remote

import com.jay.fxi.domain.model.FreeTab
import org.junit.Assert.assertEquals
import org.junit.Test

/** The order a grant's bootstraps go out in, as a pure function of the shown tab and the desired set (L-4f). */
class TopicBootstrapOrderTest {

    private val usd = TopicCatalogue.FX[0]
    private val jpy = TopicCatalogue.FX[1]
    private val eur = TopicCatalogue.FX[2]
    private val tether = TopicCatalogue.TETHER
    private val dxy = TopicCatalogue.DXY

    /** What each tab shows, whole — `dxy:spot` on both 달러 and 테더. */
    @Test
    fun `each tab shows its price rows and graph live inputs`() {
        assertEquals(
            mapOf(
                FreeTab.NEWS to emptyList(),
                FreeTab.TETHER to listOf(tether, dxy),
                FreeTab.USD to listOf(usd, dxy),
                FreeTab.JPY to listOf(jpy),
                FreeTab.EUR to listOf(eur)
            ),
            FreeTab.entries.associateWith(TopicBootstrapOrder::shownBy)
        )
    }

    /** Every tab's plan is the five desired topics exactly once, its own first and then D1's order. */
    @Test
    fun `every tab plans each desired topic once in its own order`() {
        assertEquals(
            mapOf(
                FreeTab.NEWS to listOf(tether, dxy, usd, jpy, eur),
                FreeTab.TETHER to listOf(tether, dxy, usd, jpy, eur),
                FreeTab.USD to listOf(usd, dxy, tether, jpy, eur),
                FreeTab.JPY to listOf(jpy, tether, dxy, usd, eur),
                FreeTab.EUR to listOf(eur, tether, dxy, usd, jpy)
            ),
            FreeTab.entries.associateWith { TopicBootstrapOrder.plan(it, TopicCatalogue.DESIRED) }
        )
    }

    /** A desired set narrows the plan without reordering it, and a topic no tab claims is kept at the end. */
    @Test
    fun `a partial or unclaimed desired set is narrowed or kept, never dropped`() {
        assertEquals(listOf(usd, tether), TopicBootstrapOrder.plan(FreeTab.USD, setOf(tether, usd)))
        assertEquals(listOf(tether, usd), TopicBootstrapOrder.plan(FreeTab.JPY, setOf(tether, usd)))
        assertEquals(emptyList<String>(), TopicBootstrapOrder.plan(FreeTab.USD, emptySet()))
        assertEquals(
            listOf(usd, dxy, tether, jpy, eur, TopicCatalogue.KRX_FUTURES),
            TopicBootstrapOrder.plan(FreeTab.USD, TopicCatalogue.DESIRED + TopicCatalogue.KRX_FUTURES)
        )
    }
}
