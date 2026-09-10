package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSeriesVisibilityTest {

    /**
     * The distinction the whole feature rests on. `null` is "nobody has chosen", which the tab's
     * default answers; the empty set is a choice, and answering it with the default would silently
     * overrule the user every time they turned the last series off.
     */
    @Test
    fun neverChosenTakesTheDefault_butAllOffIsKept() {
        FreeTab.entries.filter { it.isData }.forEach { tab ->
            assertEquals(tab.defaultVisibleSeriesIds, FreeSeriesVisibility.resolve(tab, null))
            assertTrue(
                "all-off was repopulated on ${tab.name}",
                FreeSeriesVisibility.resolve(tab, emptySet()).isEmpty()
            )
        }
        assertEquals(setOf("dxy"), FreeSeriesVisibility.resolve(FreeTab.USD, setOf("dxy")))
    }

    /** The defaults are iOS's, series for series. A drift here is a drift between the platforms. */
    @Test
    fun defaultsMatchTheReferenceClient() {
        assertEquals(setOf("investing.usd", "kb.usd", "hana.usd", "dxy"), FreeTab.USD.defaultVisibleSeriesIds)
        assertEquals(setOf("investing.jpy", "hana.jpy"), FreeTab.JPY.defaultVisibleSeriesIds)
        assertEquals(setOf("investing.eur", "hana.eur"), FreeTab.EUR.defaultVisibleSeriesIds)
        assertEquals(
            setOf("upbit.usdt-krw", "bithumb.usdt-krw", "hana.usd"),
            FreeTab.TETHER.defaultVisibleSeriesIds
        )
        // KRX is in none of them: the free tier does not disclose that it exists.
        assertTrue(FreeTab.entries.none { tab -> tab.defaultVisibleSeriesIds.any { "krx" in it } })
        assertEquals(emptySet<String>(), FreeTab.NEWS.defaultVisibleSeriesIds)
    }

    /**
     * `ANDROID_V2_PLAN.md §7 S2` — the tether tab's spot and futures dollar indices are mutually
     * exclusive. Turning one *on* displaces the other; turning one *off* must not summon it, or
     * all-off would be unreachable on that tab.
     */
    @Test
    fun theTetherTabsTwoDollarIndicesDisplaceEachOther() {
        val withSpot = FreeSeriesVisibility.toggle(FreeTab.TETHER, setOf("bithumb.usdt-krw"), "dxy")
        assertEquals(setOf("bithumb.usdt-krw", "dxy"), withSpot)

        val swapped = FreeSeriesVisibility.toggle(FreeTab.TETHER, withSpot, "dxy_futures")
        assertEquals(setOf("bithumb.usdt-krw", "dxy_futures"), swapped)

        val back = FreeSeriesVisibility.toggle(FreeTab.TETHER, swapped, "dxy")
        assertEquals(setOf("bithumb.usdt-krw", "dxy"), back)

        assertEquals(
            setOf("bithumb.usdt-krw"),
            FreeSeriesVisibility.toggle(FreeTab.TETHER, back, "dxy")
        )
        // All-off stays reachable.
        assertEquals(
            emptySet<String>(),
            FreeSeriesVisibility.toggle(FreeTab.TETHER, setOf("bithumb.usdt-krw"), "bithumb.usdt-krw")
        )
    }

    /**
     * Only the tether tab pairs them. The 달러 tab carries `dxy` and no futures series at all, so
     * applying the rule there would be a restriction with nothing on the other side of it.
     */
    @Test
    fun noOtherTabPairsAnything() {
        val both = FreeSeriesVisibility.toggle(FreeTab.USD, setOf("dxy_futures"), "dxy")
        assertEquals(setOf("dxy_futures", "dxy"), both)
        val stacked = FreeSeriesVisibility.toggle(FreeTab.JPY, setOf("investing.jpy"), "hana.jpy")
        assertEquals(setOf("investing.jpy", "hana.jpy"), stacked)
    }

    @Test
    fun togglingAnUnknownIdJustAddsAndRemovesIt() {
        val added = FreeSeriesVisibility.toggle(FreeTab.USD, emptySet(), "something.new")
        assertEquals(setOf("something.new"), added)
        assertEquals(emptySet<String>(), FreeSeriesVisibility.toggle(FreeTab.USD, added, "something.new"))
    }
}
