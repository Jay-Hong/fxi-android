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
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-4 D: the three tether graph journeys the S5 gate measures (ANDROID_V2_PLAN.md :1527-1539; agreed
 * plan R4c/S5 s5_4d_plan.r2.md). Class and method names are the judge's identities and the selectors its expectations
 * (tools/performance/benchmark_gate.py EXPECTED_BENCHMARK_IDENTITIES, EXPECTED_BEHAVIOR).
 *
 * Run so far on the emulator with the s5Benchmark fixture build (S5-4 E 4d dry runs, slice 5 real runs); measuring needs the
 * fixed device (S5-4 F, a separate approval). A journey that does not behave as agreed fails its assertion at once. With the
 * `s5.runId` argument each run also writes the gate's behavior record (S5Behavior, S5BehaviorWriter): a success record of the
 * eight slots, or the failure record of the slot and stage that failed.
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
    fun tabEntry() = measure(S5Behavior.Journey.TAB_ENTRY, setup = {
        enterPremiumUsd()
    }) { slot ->
        slot.before { NA }
        val tab = slot.lookup(TETHER_TAB)
        slot.action { tab.click() }
        slot.after(NA) { keep -> awaitTether(PERIOD_1D, ONE_DAY_LABELS, keep) }
    }

    /** period-switch: from tether one week (the catalog's 5 series), one day; done when it draws the 11 again. */
    @Test
    fun periodSwitch() = measure(S5Behavior.Journey.PERIOD_SWITCH, setup = {
        enterPremiumUsd()
        clickOnce(TETHER_TAB)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
        clickPeriod(PERIOD_1W)
        awaitTether(PERIOD_1W, ONE_WEEK_LABELS)
    }) { slot ->
        slot.before { NA }
        val period = slot.lookupPeriod(PERIOD_1D)
        slot.action { period.click() }
        slot.after(NA) { keep -> awaitTether(PERIOD_1D, ONE_DAY_LABELS, keep) }
    }

    /** The page anchor's top at the real page top, taken in each setup. */
    private var pageTop = 0

    /** scroll: tether one day at the page top; the page moves down through its container, then comes back to the top. */
    @Test
    fun scroll() = measure(S5Behavior.Journey.SCROLL, setup = {
        enterPremiumUsd()
        clickOnce(TETHER_TAB)
        awaitTether(PERIOD_1D, ONE_DAY_LABELS)
        scrollToTop()
        pageTop = anchorTop()
    }) { slot ->
        slot.before { pagePosition() }
        val page = slot.lookup(PAGE_SCROLL)
        slot.action { page.scroll(Direction.DOWN, SCROLL_PERCENT) }
        slot.during {
            val moved = anchorTop()
            slot.duringObserved(if (moved < pageTop) "moved" else "top")
            assertTrue("the page moved: anchor $pageTop -> $moved", moved < pageTop)
        }
        slot.after(null) { keep ->
            scrollToTop()
            val top = S5Poll.afterScroll({ anchorTop() }, pageTop, slot::afterScroll)
            assertEquals("the page came back to the top", pageTop, top)
            awaitTether(PERIOD_1D, ONE_DAY_LABELS, keep)
        }
    }

    /** The scroll journey's page position against the anchor top its setup took. */
    private fun MacrobenchmarkScope.pagePosition(): String = if (anchorTop() == pageTop) "top" else "moved"

    /**
     * Runs one journey. With the `s5.runId` argument (S5BehaviorWriter) it also keeps the gate's behavior record: slots by
     * `scope.iteration`, each stage observed before its own assertion, the record written once after measureRepeated returns or
     * throws, and the original error thrown on (a record write error rides along as suppressed).
     */
    private fun measure(
        journey: S5Behavior.Journey,
        setup: MacrobenchmarkScope.() -> Unit,
        block: MacrobenchmarkScope.(Slot) -> Unit
    ) {
        val runId = S5BehaviorWriter.runId()
        val tracker = runId?.let { S5Behavior.Tracker(journey) }
        try {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
                startupMode = StartupMode.WARM,
                iterations = 5,
                setupBlock = {
                    tracker?.beginSetup(iteration)
                    try {
                        setup()
                    } catch (error: Throwable) {
                        tracker?.fail(error)
                        throw error
                    }
                },
                measureBlock = {
                    val observation = tracker?.beginMeasure(iteration) ?: S5Behavior.Observation(journey)
                    try {
                        block(Slot(this, observation, tracker))
                        tracker?.endMeasure()
                    } catch (error: Throwable) {
                        tracker?.fail(error)
                        throw error
                    }
                }
            )
        } catch (error: Throwable) {
            if (runId != null && tracker != null) {
                try {
                    if (tracker.fail(error) != null) S5BehaviorWriter.write(runId, S5Behavior.failureRecord(runId, tracker))
                } catch (writeError: Throwable) {
                    error.addSuppressed(writeError)
                }
            }
            throw error
        }
        if (runId != null && tracker != null) S5BehaviorWriter.write(runId, S5Behavior.successRecord(runId, tracker))
    }

    /** One measure's stages: each observation is kept before the assertion that reads it, so a failure record shows it. */
    private inner class Slot(
        private val scope: MacrobenchmarkScope,
        private val observation: S5Behavior.Observation,
        private val tracker: S5Behavior.Tracker?
    ) {
        fun before(scroll: MacrobenchmarkScope.() -> String) {
            tracker?.stage(S5Behavior.Stage.BEFORE)
            S5Poll.before(observation, scope.reads()) { scope.scroll() }
        }

        /** The one object with [tag]; the count found is kept first. */
        fun lookup(tag: String): UiObject2 {
            tracker?.stage(S5Behavior.Stage.LOOKUP)
            val found = scope.device.findObjects(By.res(tag))
            observation.lookup(tag, found.size)
            assertEquals("$tag lookups", 1, found.size)
            return found.single()
        }

        /** The inline period tab for [code]: once under the inline periods (the count kept) and once on the whole screen. */
        fun lookupPeriod(code: String): UiObject2 {
            tracker?.stage(S5Behavior.Stage.LOOKUP)
            val tag = "period_tab:$code"
            val inline = scope.single(PERIODS).findObjects(By.res(tag))
            observation.lookup(tag, inline.size)
            assertEquals("$tag under the inline periods", 1, inline.size)
            assertEquals("$tag on the screen", 1, scope.device.findObjects(By.res(tag)).size)
            return inline.single()
        }

        fun action(run: () -> Unit) {
            tracker?.stage(S5Behavior.Stage.ACTION)
            run()
            observation.actionDone = true
        }

        fun during(run: () -> Unit) {
            tracker?.stage(S5Behavior.Stage.DURING)
            run()
        }

        fun duringObserved(scroll: String) {
            observation.during = scroll
        }

        /**
         * The after stage. [settle] passes every snapshot its own comparison reads to `keep`, so the record holds the very reads
         * that decided: the matching one on success, the last poll on a timeout. Nothing is read again afterwards.
         */
        fun after(scroll: String?, settle: MacrobenchmarkScope.(keep: (S5Poll.Snapshot) -> Unit) -> Unit) {
            tracker?.stage(S5Behavior.Stage.AFTER)
            afterScroll = scroll
            scope.settle { shot -> S5Poll.keepAfter(observation, shot, afterScroll) }
        }

        private var afterScroll: String? = null

        /** The scroll after-position, kept before the assertion that compares the same anchor read. */
        fun afterScroll(position: String) {
            afterScroll = position
            observation.after = S5Behavior.State(null, null, null, null, position)
        }
    }

    /**
     * Opens the premium USD screen. With WARM the rule runs one unmeasured setup and measure before the measured ones (S5-4 E
     * 4d dry run), so a later setup reopens on the tab the earlier pass left, tether: it chooses USD again then.
     */
    private fun MacrobenchmarkScope.enterPremiumUsd() {
        startActivityAndWait()
        assertTrue("premium USD tab", device.wait(Until.hasObject(By.res(USD_TAB)), ENTRY_TIMEOUT_MS))
        if (!isChecked(USD_TAB)) clickOnce(USD_TAB)
        assertTrue("premium USD selected", awaitChecked(USD_TAB))
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
    private fun MacrobenchmarkScope.awaitTether(period: String, labels: List<String>, keep: (S5Poll.Snapshot) -> Unit = {}) {
        val deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MS
        val (matched, last) = S5Poll.await(
            reads(), keep,
            matches = { it.labels == labels && it.checked(TETHER_TAB) && it.selected("period_tab:$period") },
            expired = { SystemClock.uptimeMillis() >= deadline },
            pause = { SystemClock.sleep(POLL_MS) }
        )
        if (!matched) fail("tether $period not drawn as agreed: labels ${last.labels}, expected $labels")
    }

    private fun MacrobenchmarkScope.isSelected(tag: String) =
        fresh { device.findObjects(By.res(tag)).singleOrNull()?.isSelected } == true

    /** A premium tab's choice: its `selected` semantics has no tab role, so accessibility reports it as checked. */
    private fun MacrobenchmarkScope.isChecked(tag: String) =
        fresh { device.findObjects(By.res(tag)).singleOrNull()?.isChecked } == true

    /** Polls [isChecked] until [ENTRY_TIMEOUT_MS]: each read is fresh from the node found, which a selector wait was not. */
    private fun MacrobenchmarkScope.awaitChecked(tag: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + ENTRY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (isChecked(tag)) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /** A node replaced between the lookup and the read is not there yet; the caller polls again. */
    private inline fun <T> fresh(read: () -> T?): T? = try { read() } catch (_: StaleObjectException) { null }

    /** This screen's reads for S5Poll; a node replaced mid-read is "not observed" (null), any other error goes on. */
    private fun MacrobenchmarkScope.reads() = S5Poll.Reads<UiObject2>(
        tabs = { fresh { device.findObjects(By.res(Pattern.compile("premium_tab_.*"))).mapNotNull { o -> o.resourceName?.let { it to o.isChecked } } } },
        periods = { fresh { device.findObjects(By.res(Pattern.compile("period_tab:.*"))).mapNotNull { o -> o.resourceName?.let { it to o.isSelected } } } },
        charts = { fresh { device.findObjects(By.res(CHART)) } },
        description = { chart -> fresh { chart.contentDescription } },
        labelsOf = ::labelsOf
    )

    /** The labels in a chart description, or null when the description is not of that shape. */
    private fun labelsOf(text: String): List<String>? {
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
        const val PAGE_SCROLL = S5Behavior.PAGE_SCROLL
        const val ANCHOR = "premium_heading"
        const val CHART = S5Behavior.CHART
        const val NA = "na"
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
