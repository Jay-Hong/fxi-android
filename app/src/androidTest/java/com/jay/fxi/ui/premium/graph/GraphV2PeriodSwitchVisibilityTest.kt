package com.jay.fxi.ui.premium.graph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphSelectionBinding
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S5 A3 C1 contract r1 (instrumented, surfaces with a recording action): a period change on a measured, shown
 * surface hands its visibility over to the new token without the unmeasured false in between, and a token that leaves before
 * its first measurement still ends with false.
 *
 * Oracles: A3/impl/design.r3.md D1 (design (b): inViewport is unknown until the first onGloballyPositioned, decided there
 * as today from a positive width and height; `shown = hostExposed && inViewport == true`; with a token, a closing observer
 * keyed by token and lifecycle owner reports (token, false) on ON_STOP and on dispose from the first composition on, and
 * reports nothing on start; the LifecycleStartEffect(token, shown) that reports visibility is composed only when
 * `!hostExposed || inViewport != null`; duplicate falses are allowed) and its contract sentence for
 * GraphV2SurfaceVisibilityTest's header: "before the first measurement an exposed surface omits only its initial start
 * report; stop or disposal still reports false under that token. A surface that is not host-exposed reports false on
 * lifecycle start even before measurement". Rows P01-P03, P05 and P06 of design.r3's "계약 시험" as Codex's design review r1
 * (A3/impl/codex_design.r1.txt item 1 and item 4's P05/P06) worded them: the closing observer exists because a token
 * composed and then stopped or removed before any measurement would otherwise leave no false behind it (H02, H03 and H12
 * only cover measured or settled surfaces). P04 is not a test: it is the requirement that P01 fail on HEAD 8f93fe7.
 * Selection: A3/step1/selection.r3.md sections 1-2 (the unmeasured new token's false is what makes the holder drop the
 * live publication on every period change).
 *
 * Withholding the first measurement: each surface is composed inside [Held], a SubcomposeLayout of fixed size that always
 * subcomposes its content but measures and places it only while `placed` is true. Composition and effects - the
 * LifecycleStartEffect and the closing observer - therefore run while the surface's root layout node is neither measured nor
 * placed, and Compose dispatches onGloballyPositioned only to placed nodes, so no first measurement happens until the test
 * sets `placed` back. A test changes the token and clears `placed` in one main-thread block, so the frame that composes the
 * new key is also the one that stops placing it. Each surface also carries a counting onGloballyPositioned through its own
 * `modifier` (the same layout node as the surface root's), and every held step asserts as a premise that the count did not
 * move. With `outside`, the content is placed just below the container's bottom edge: boundsInWindow is clipped by the
 * container, which clips to its bounds (clipToBounds; boundsInWindow clips only at clipping layers), so the first
 * measurement is a zero area. The lifecycle is a LifecycleRegistry the test drives on the main
 * thread (H03's TestOwner).
 *
 * Fixtures: GraphV2SurfaceVisibilityTest's token, chart and state builders and its recording actions, copied with every named
 * argument. The implementation thread reads but does not edit this file.
 */
class GraphV2PeriodSwitchVisibilityTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val a1 = AuthIdentityFence("A", 1L)
    private val owner = TopicDisplayOwner(a1, 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(7L))
    private val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)
    private fun binding(tab: String) = GraphSelectionBinding(a1, GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, tab), 1L)
    private fun token(
        tab: String = "usd",
        surface: GraphV2Surface = GraphV2Surface.INLINE,
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        screen: Long = 1L,
        gen: Long = 0L
    ) = GraphV2UiToken(owner, fence, lifetime, binding(tab), tab, period, screen, surface, gen)

    private val midnight: Instant = Instant.parse("2026-10-05T15:00:00Z")

    private fun chart(tab: String = "usd", rightEdgeNow: Instant? = null, base: Double = 1000.0): GraphV2ChartModel {
        val id = "x.$tab"
        val graph = FreeGraph(
            "10min",
            listOf(FreeGraphSeries(id, (0..144).map { FreeGraphPoint(midnight + (it * 10).minutes, base + it, null, null) },
                label = "X", axisGroup = "krw")),
            domainStartAt = midnight, domainEndAt = midnight + 24.hours, liveDomainMode = "rolling"
        )
        return GraphV2ChartModel(GraphPreparedBuilder.build(graph, GraphPeriod.ONE_DAY), setOf(id), rightEdgeNow)
    }

    private fun state(
        content: GraphV2Content = GraphV2Content.READY,
        tab: String = "usd",
        inline: GraphV2UiToken? = token(tab),
        fullscreen: GraphV2UiToken? = null,
        chart: GraphV2ChartModel? = if (content == GraphV2Content.READY) chart(tab) else null,
        notice: GraphV2Notice? = null,
        refreshing: Boolean = false,
        visible: Set<String> = setOf("x.$tab")
    ): GraphV2ScreenState {
        val ready = content == GraphV2Content.READY
        return GraphV2ScreenState(
            tab, GraphPeriod.ONE_DAY, listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), content,
            if (ready) GraphV2SelectionStatus.READY else GraphV2SelectionStatus.UNBOUND,
            if (ready) GraphSeriesSelection(visible, setOf("x.$tab")) else null,
            if (ready) listOf(GraphV2SeriesToggle("x.$tab", GraphSeriesStyles.of("x.$tab", "X"), "x.$tab" in visible, true, "krw"))
            else emptyList(),
            chart, refreshing, null, notice, fullscreen != null, inline, fullscreen
        )
    }

    private val reports: MutableList<Pair<GraphV2UiToken, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val actions = GraphV2UiActions(
        selectPeriod = { _, _ -> },
        toggleSeries = { _, _ -> },
        enterFullscreen = {},
        exitFullscreen = {},
        retrySelection = {},
        setSurfaceVisible = { tk, shown -> reports += tk to shown }
    )

    private fun last(token: GraphV2UiToken): Boolean? = synchronized(reports) { reports.lastOrNull { it.first == token }?.second }
    private fun shows(token: GraphV2UiToken): Int = synchronized(reports) { reports.count { it.first == token && it.second } }
    private fun of(token: GraphV2UiToken): List<Boolean> = synchronized(reports) { reports.filter { it.first == token }.map { it.second } }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    // --- the harness ----------------------------------------------------------------------------------------------------

    private val life = TestOwner()
    private var lifeOwner: LifecycleOwner by mutableStateOf(life)
    private var s by mutableStateOf(state())
    private var exposed by mutableStateOf(true)
    private var inlinePlaced by mutableStateOf(true)
    private var fullPlaced by mutableStateOf(true)
    private var inlineOutside by mutableStateOf(false)
    private var fullOutside by mutableStateOf(false)
    private var inlineMounted by mutableStateOf(true)
    private var fullMounted by mutableStateOf(true)
    private val inlinePositions = AtomicInteger()
    private val fullPositions = AtomicInteger()

    /**
     * Always subcomposes [content]; measures and places it only while [placed], at the top or, with [outside], just below
     * the bottom edge of this clipping container (a zero visible area once measured). Unplaced content gets no
     * onGloballyPositioned.
     */
    @Composable
    private fun Held(placed: Boolean, outside: Boolean, content: @Composable () -> Unit) {
        SubcomposeLayout(Modifier.fillMaxWidth().height(160.dp).clipToBounds()) { constraints ->
            val measurables = subcompose(Unit, content)
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = if (placed) measurables.map { it.measure(loose) } else emptyList()
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEach { it.place(0, if (outside) constraints.maxHeight else 0) }
            }
        }
    }

    private fun compose(initial: GraphV2ScreenState) {
        s = initial
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifeOwner) {
                Column {
                    Held(inlinePlaced, inlineOutside) {
                        if (inlineMounted) {
                            GraphV2Section(s, actions, Modifier.onGloballyPositioned { inlinePositions.incrementAndGet() },
                                hostExposed = exposed)
                        }
                    }
                    Held(fullPlaced, fullOutside) {
                        if (fullMounted) {
                            GraphV2Fullscreen(s, actions, Modifier.onGloballyPositioned { fullPositions.incrementAndGet() },
                                hostExposed = exposed)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Changes the state and stops placing both surfaces in one main-thread block, then waits for the frame. */
    private fun holdAndShow(next: GraphV2ScreenState) {
        rule.runOnUiThread {
            inlinePlaced = false
            fullPlaced = false
            s = next
        }
        rule.waitForIdle()
    }

    private fun release() {
        rule.runOnUiThread {
            inlinePlaced = true
            fullPlaced = true
        }
        rule.waitForIdle()
    }

    private fun lifecycle(next: Lifecycle.State) {
        rule.runOnUiThread { life.registry.currentState = next }
        rule.waitForIdle()
    }

    private val t1 = token()
    private val f1 = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L)
    private val t2 = token(period = GraphPeriod.THREE_MONTHS, screen = 2L)
    private val f2 = token(surface = GraphV2Surface.FULLSCREEN, period = GraphPeriod.THREE_MONTHS, screen = 2L, gen = 1L)
    private val t3 = token(period = GraphPeriod.ONE_YEAR, screen = 3L)
    private val f3 = token(surface = GraphV2Surface.FULLSCREEN, period = GraphPeriod.ONE_YEAR, screen = 3L, gen = 1L)

    /** The positions counted so far, inline and fullscreen. */
    private fun positions() = inlinePositions.get() to fullPositions.get()

    private fun composeShown() {
        compose(state(inline = t1, fullscreen = f1))
        assertEquals("premise: the inline surface is measured and shown", true, last(t1))
        assertEquals("premise: the fullscreen is measured and shown", true, last(f1))
    }

    // --- P01-P03 ----------------------------------------------------------------------------------------------------------

    /**
     * P01: on a measured, shown surface a period token change reports the new token true first - no (new, false) before it -
     * and ends the old token with false; inline and fullscreen alike. On HEAD the new key starts unmeasured and reports false.
     */
    @Test fun P01_aPeriodChangeOnAShownSurfaceFirstReportsTheNewTokenShown() {
        composeShown()
        s = state(inline = t2, fullscreen = f2)
        rule.waitForIdle()
        for ((old, next) in listOf(t1 to t2, f1 to f2)) {
            assertEquals("${next.surface}: the new token's first report is true, with no false before it", true, of(next).firstOrNull())
            assertEquals("${next.surface}: the new token is shown", true, last(next))
            assertEquals("${old.surface}: the old token ends with false", false, last(old))
        }
    }

    /**
     * P02: with hostExposed=false a token change reports (new, false) at once - before any measurement - and never true,
     * also once it is measured; inline and fullscreen alike.
     */
    @Test fun P02_aTokenChangeOnAHostHiddenSurfaceReportsFalseAtOnce() {
        exposed = false
        compose(state(inline = t1, fullscreen = f1))
        assertEquals("premise", 0, shows(t1))
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: false before measurement", false, last(next))
            assertEquals("${next.surface}: never true", 0, shows(next))
        }
        release()
        assertNotEquals("premise: measured now", before, positions())
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: still false after measurement", false, last(next))
            assertEquals("${next.surface}: never true", 0, shows(next))
        }
    }

    /**
     * P03: on a surface whose area in the viewport is zero, a token change reports no true before measurement and its first
     * measurement leaves it false: the new token's reports are false only and its last report is false.
     */
    @Test fun P03_aTokenChangeOutOfTheViewportEndsFalseAfterMeasurement() {
        inlineOutside = true
        fullOutside = true
        compose(state(inline = t1, fullscreen = f1))
        assertTrue("premise: measured", inlinePositions.get() > 0 && fullPositions.get() > 0)
        assertEquals("premise", 0, shows(t1))
        assertEquals("premise", 0, shows(f1))
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: no report before the first measurement", emptyList<Boolean>(), of(next))
        release()
        assertTrue("premise: measured after release", inlinePositions.get() > before.first && fullPositions.get() > before.second)
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: false after measurement", false, last(next))
            assertTrue("${next.surface}: only false", of(next).isNotEmpty() && of(next).none { it })
        }
    }

    // --- P05: a token that leaves before its first measurement --------------------------------------------------------------

    /** P05a: a new token on both surfaces, still unmeasured when the owner stops, ends with false and is never shown. */
    @Test fun P05a_aStopBeforeTheFirstMeasurementEndsTheNewTokenWithFalse() {
        composeShown()
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        assertEquals(false, last(t1))
        assertEquals(false, last(f1))
        lifecycle(Lifecycle.State.CREATED)
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: the stop ends it with false", false, last(next))
            assertEquals("${next.surface}: never shown", 0, shows(next))
        }
        lifecycle(Lifecycle.State.STARTED)
        assertEquals("premise: still unmeasured", before, positions())
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: no true from a start before measurement", 0, shows(next))
    }

    /** P05b: a new token on both surfaces, still unmeasured when the surfaces are removed, ends with false. */
    @Test fun P05b_aRemovalBeforeTheFirstMeasurementEndsTheNewTokenWithFalse() {
        composeShown()
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        rule.runOnUiThread {
            inlineMounted = false
            fullMounted = false
        }
        rule.waitForIdle()
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: the removal ends it with false", false, last(next))
            assertEquals("${next.surface}: never shown", 0, shows(next))
        }
    }

    /**
     * P05c: a new token replaced by the next one before its first measurement ends with false; the next token, once
     * measured, is shown and the replaced one never was.
     */
    @Test fun P05c_aReplacementBeforeTheFirstMeasurementEndsTheNewTokenWithFalse() {
        composeShown()
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        holdAndShow(state(inline = t3, fullscreen = f3))
        assertEquals("premise: not measured since the changes", before, positions())
        for (left in listOf(t2, f2)) {
            assertEquals("${left.surface}: the replacement ends it with false", false, last(left))
            assertEquals("${left.surface}: never shown", 0, shows(left))
        }
        release()
        for ((left, next) in listOf(t2 to t3, f2 to f3)) {
            assertEquals("${next.surface}: the next token is shown once measured", true, last(next))
            assertEquals("${left.surface}: still never shown", 0, shows(left))
        }
    }

    /**
     * P05d: an unmeasured new token follows a replaced lifecycle owner: the replacement or that owner's stop ends it with
     * false (design.r3 D1 keys the closing observer on token and lifecycleOwner).
     */
    @Test fun P05d_aReplacedLifecycleOwnerBeforeTheFirstMeasurementEndsTheNewTokenWithFalse() {
        composeShown()
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        val other = TestOwner()
        rule.runOnUiThread { other.registry.currentState = Lifecycle.State.RESUMED; lifeOwner = other }
        rule.waitForIdle()
        rule.runOnUiThread { other.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: ends with false under the new owner", false, last(next))
            assertEquals("${next.surface}: never shown", 0, shows(next))
        }
    }

    // --- P06: before the first measurement --------------------------------------------------------------------------------

    /**
     * P06a: before its first measurement an exposed surface reports nothing for its new token - only the initial start report
     * is omitted - yet a stop still ends it with false, a start before measurement reports no true, and the first measurement
     * reports it shown. On HEAD the new key reports false on start.
     */
    @Test fun P06a_anExposedSurfaceOmitsOnlyItsInitialStartReportBeforeMeasurement() {
        composeShown()
        val before = positions()
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: no report before the first measurement", emptyList<Boolean>(), of(next))
        lifecycle(Lifecycle.State.CREATED)
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: a stop still reports false", false, last(next))
        lifecycle(Lifecycle.State.RESUMED)
        assertEquals("premise: still unmeasured", before, positions())
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: no true before measurement", 0, shows(next))
        release()
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: shown once measured", true, last(next))
    }

    /**
     * P06b: host exposure changing before the first measurement. Hidden at the change, the new token reports false at once;
     * exposed while still unmeasured it reports no true, and the first measurement reports it shown. Exposed at the change and
     * then hidden while unmeasured, it reports false on that change even before measurement; exposed again, no true until the
     * first measurement. Duplicate falses are allowed.
     */
    @Test fun P06b_hostExposureChangesBeforeTheFirstMeasurement() {
        composeShown()
        val before = positions()
        rule.runOnUiThread { exposed = false }
        holdAndShow(state(inline = t2, fullscreen = f2))
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: host-hidden at the change reports false at once", false, last(next))
        rule.runOnUiThread { exposed = true }
        rule.waitForIdle()
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: exposed before measurement is not true", 0, shows(next))
            assertEquals("${next.surface}: still false", false, last(next))
        }
        release()
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: shown once measured", true, last(next))

        val again = positions()
        holdAndShow(state(inline = t3, fullscreen = f3))
        rule.runOnUiThread { exposed = false }
        rule.waitForIdle()
        for (next in listOf(t3, f3)) assertEquals("${next.surface}: hidden before measurement reports false", false, last(next))
        rule.runOnUiThread { exposed = true }
        rule.waitForIdle()
        assertEquals("premise: not measured since the change", again, positions())
        for (next in listOf(t3, f3)) assertEquals("${next.surface}: no true before measurement", 0, shows(next))
        release()
        for (next in listOf(t3, f3)) assertEquals("${next.surface}: shown once measured", true, last(next))
    }

    /**
     * P06c: an exposed surface whose first measurement after a token change is a zero area reports nothing before that
     * measurement and false after it, never true; a stop then still reports false.
     */
    @Test fun P06c_aZeroAreaFirstMeasurementReportsFalse() {
        composeShown()
        val before = positions()
        rule.runOnUiThread {
            inlineOutside = true
            fullOutside = true
        }
        holdAndShow(state(inline = t2, fullscreen = f2))
        assertEquals("premise: not measured since the change", before, positions())
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: no report before the first measurement", emptyList<Boolean>(), of(next))
        release()
        assertTrue("premise: measured", inlinePositions.get() > before.first && fullPositions.get() > before.second)
        for (next in listOf(t2, f2)) {
            assertEquals("${next.surface}: a zero area is reported false", false, last(next))
            assertEquals("${next.surface}: never true", 0, shows(next))
        }
        lifecycle(Lifecycle.State.CREATED)
        for (next in listOf(t2, f2)) assertEquals("${next.surface}: a stop reports false", false, last(next))
    }
}
