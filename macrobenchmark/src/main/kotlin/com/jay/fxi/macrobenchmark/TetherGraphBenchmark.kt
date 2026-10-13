package com.jay.fxi.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-4 D (compile-verified only): the three tether graph journeys the S5 gate measures (ANDROID_V2_PLAN.md :1527-1539; agreed
 * plan R4c/S5 s5_4d_plan.r2.md). Class and method names are the judge's identities and the selectors its expectations
 * (tools/performance/benchmark_gate.py EXPECTED_BENCHMARK_IDENTITIES, EXPECTED_BEHAVIOR).
 *
 * Not run yet. [enterPremiumUsd] needs the D24 ON benchmark artifact and the fixture runtime (S5-4 E, a separate user GO), and
 * measuring needs the fixed device (S5-4 F, a separate approval). The behavior record writer, run ids and the phase of each
 * execution also belong to E: here a journey that does not behave as agreed fails its assertion at once.
 *
 * The series observation reads the inline chart's description (GraphChart.kt GraphPlot.describe(): a prefix, the labels of the
 * lines that have points, then the extremes) and compares it with the frozen fixture's labels; it holds for that fixture only.
 */
@RunWith(AndroidJUnit4::class)
class TetherGraphBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** tab-entry: from premium USD, the tether tab; done when one day draws the 11 fixture series. */
    @Test
    fun tabEntry() = measure(setup = {
        enterPremiumUsd()
    }) {
        clickOnce(TETHER_TAB)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
    }

    /** period-switch: from tether one week (the catalog's 5 series), one day; done when it draws the 11 again. */
    @Test
    fun periodSwitch() = measure(setup = {
        enterPremiumUsd()
        clickOnce(TETHER_TAB)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
        clickPeriod(PERIOD_1W)
        awaitTether(PERIOD_1W, ONE_WEEK_LABELS)
    }) {
        clickPeriod(PERIOD_1D)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
    }

    /** The page anchor's top at the real page top, taken in each setup. */
    private var pageTop = 0

    /** scroll: tether one day at the page top; the page moves down through its container, then comes back to the top. */
    @Test
    fun scroll() = measure(setup = {
        enterPremiumUsd()
        clickOnce(TETHER_TAB)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
        scrollToTop()
        pageTop = anchorTop()
    }) {
        single(PAGE_SCROLL).scroll(Direction.DOWN, SCROLL_PERCENT)
        val moved = anchorTop()
        assertTrue("the page moved: anchor $pageTop -> $moved", moved < pageTop)
        scrollToTop()
        assertEquals("the page came back to the top", pageTop, anchorTop())
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
    }

    private fun measure(setup: MacrobenchmarkScope.() -> Unit, block: MacrobenchmarkScope.() -> Unit) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = setup,
            measureBlock = block
        )

    /**
     * Opens the premium USD screen. Unverified until S5-4 E: the D24 OFF benchmark build cannot show it, and the fixture's
     * entry is decided there.
     */
    private fun MacrobenchmarkScope.enterPremiumUsd() {
        startActivityAndWait()
        assertTrue("premium USD tab", device.wait(Until.hasObject(By.res(USD_TAB)), ENTRY_TIMEOUT_MS))
        assertTrue("premium USD selected", single(USD_TAB).isSelected)
    }

    /** The one object with [tag]; any other count fails (a missing bridge, a stale tag or a duplicate). */
    private fun MacrobenchmarkScope.single(tag: String): UiObject2 {
        val found = device.findObjects(By.res(tag))
        assertEquals("$tag lookups", 1, found.size)
        return found.single()
    }

    private fun MacrobenchmarkScope.clickOnce(tag: String) = single(tag).click()

    /** Clicks the inline period tab for [code]: once under the inline periods and once on the whole screen. */
    private fun MacrobenchmarkScope.clickPeriod(code: String) {
        val tag = "period_tab:$code"
        val inline = single(PERIODS).findObjects(By.res(tag))
        assertEquals("$tag under the inline periods", 1, inline.size)
        assertEquals("$tag on the screen", 1, device.findObjects(By.res(tag)).size)
        inline.single().click()
    }

    /**
     * Waits until the tether tab and the [period] tab are selected and the chart draws exactly [labels], in order. A missing or
     * repeated chart, an unknown, repeated or empty label and a description of another shape never match, so they time out.
     */
    private fun MacrobenchmarkScope.awaitTether(period: String, labels: List<String>) {
        val deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MS
        var observed: List<String>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            observed = chartLabels()
            if (observed == labels && isSelected(TETHER_TAB) && isSelected("period_tab:$period")) return
            SystemClock.sleep(POLL_MS)
        }
        fail("tether $period not drawn as agreed: labels $observed, expected $labels")
    }

    private fun MacrobenchmarkScope.isSelected(tag: String) =
        device.findObjects(By.res(tag)).singleOrNull()?.isSelected == true

    /** The labels in the one inline chart's description, or null when the chart or the description is not of that shape. */
    private fun MacrobenchmarkScope.chartLabels(): List<String>? {
        val text = device.findObjects(By.res(CHART)).singleOrNull()?.contentDescription ?: return null
        if (!text.startsWith(DESCRIPTION_PREFIX)) return null
        val rest = text.removePrefix(DESCRIPTION_PREFIX)
        val end = rest.indexOf('.')
        if (end < 0) return null
        val names = rest.substring(0, end)
        return if (names.isEmpty()) emptyList() else names.split(", ")
    }

    private fun MacrobenchmarkScope.anchorTop(): Int {
        val bounds = single(ANCHOR).visibleBounds
        assertTrue("the anchor has bounds", !bounds.isEmpty)
        return bounds.top
    }

    private fun MacrobenchmarkScope.scrollToTop() {
        var gestures = 0
        while (single(PAGE_SCROLL).scroll(Direction.UP, 1.0f)) {
            gestures++
            assertTrue("the page reaches its top", gestures < MAX_SCROLL_GESTURES)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.jay.fxi"
        const val USD_TAB = "premium_tab_USD"
        const val TETHER_TAB = "premium_tab_TETHER"
        const val PAGE_SCROLL = "premium_page_scroll"
        const val ANCHOR = "premium_heading"
        const val CHART = "graph_v2:inline:chart"
        const val PERIODS = "graph_v2:inline:periods"
        const val PERIOD_1D = "1d"
        const val PERIOD_1W = "1w"
        const val DESCRIPTION_PREFIX = "환율 추이 그래프. "
        /** The fixture's one-day series as the chart names them (GraphSeriesStyles.labelOf over app/src/benchmark/assets/s5_4). */
        val ONE_DAY_LABELS = listOf(
            "업비트", "빗썸", "코인원", "코빗", "고팍스", "KRX 미국달러선물", "인베스팅", "국민은행", "하나은행", "달러지수", "선물지수"
        )
        /** The catalog's one-week series for the same fixture. */
        val ONE_WEEK_LABELS = listOf("빗썸", "KRX 미국달러선물", "인베스팅", "하나은행", "달러지수")
        /** Small enough that the anchor stays on screen; S5-4 E confirms it on the device. */
        const val SCROLL_PERCENT = 0.25f
        const val MAX_SCROLL_GESTURES = 10
        const val ENTRY_TIMEOUT_MS = 10_000L
        const val READY_TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
