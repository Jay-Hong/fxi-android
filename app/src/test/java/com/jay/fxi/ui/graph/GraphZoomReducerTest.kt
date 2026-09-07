package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom as a state machine.
 *
 * These are the cases that never survive being written inside a composable: a gesture that begins
 * and is abandoned, a change that arrives without its beginning, and the question of whether the
 * window resumes following after the finger lifts. iOS names the first one as a defect it had to
 * fix, which is reason enough to keep the transitions somewhere they can be run.
 */
class GraphZoomReducerTest {

    private val midnight = Instant.parse("2026-09-07T00:00:00Z")
    private val dataEnd = midnight + 24.hours
    private val full = (midnight - 10.minutes)..(dataEnd + 10.minutes)

    private val context = GraphZoomContext(
        fullDomain = full,
        latestAnchor = dataEnd,
        minLength = GraphZoomMath.minVisibleLength(GraphPeriod.ONE_DAY),
        doubleTapWindow = GraphZoomMath.doubleTapWindow(GraphPeriod.ONE_DAY),
        followThreshold = GraphZoomMath.followThreshold(GraphPeriod.ONE_DAY),
        plotLeft = 0f,
        plotWidth = 1000f
    )

    private fun GraphZoomState.then(vararg gestures: GraphGesture): GraphZoomState =
        gestures.fold(this) { state, g -> GraphZoomReducer.reduce(state, g, context) }

    private fun unzoomed() = GraphZoomState()
    private val ClosedRange<Instant>.length get() = endInclusive - start

    // --- pinch ---------------------------------------------------------------------------------

    @Test
    fun aPinchStartsFromWhatIsOnScreenAndFreezesTheLiveEdge() {
        val state = unzoomed().then(GraphGesture.PinchBegan(locationX = 500f))
        assertEquals(full, state.pinchBaseline)
        assertEquals(full, state.visible)
        assertTrue(!state.isFollowing)
        assertNotNull(state.pinchAnchor)
    }

    @Test
    fun pinchingInNarrowsTheWindowAroundTheFinger() {
        val state = unzoomed().then(
            GraphGesture.PinchBegan(locationX = 500f),
            GraphGesture.PinchChanged(scale = 4f)
        )
        val window = state.visible!!
        assertEquals(full.length / 4, window.length)
        // The middle of the plot was grabbed, so the middle of the frame stays in the middle.
        val frameMiddle = full.start + full.length / 2
        assertEquals(frameMiddle, window.start + window.length / 2)
    }

    /**
     * The defect iOS calls out by name: a second finger that touches down and lifts without moving.
     *
     * `PinchBegan` has already written the whole frame into `visible`, which is non-null and so
     * counts as zoomed — the chart would stop following the live edge and the next double tap would
     * read as a reset instead of a zoom in. Ending re-clamps, and the whole frame clamps to null.
     */
    @Test
    fun aPinchThatBeganAndNeverMovedLeavesNoTraceOfItself() {
        val state = unzoomed().then(GraphGesture.PinchBegan(500f), GraphGesture.PinchEnded)
        assertEquals(GraphZoomState(), state)
        assertTrue(!state.isZoomed)
        assertTrue(state.isFollowing)
    }

    @Test
    fun aRealPinchSurvivesTheEndOfTheGesture() {
        val state = unzoomed().then(
            GraphGesture.PinchBegan(500f),
            GraphGesture.PinchChanged(4f),
            GraphGesture.PinchEnded
        )
        assertNotNull(state.visible)
        assertEquals(full.length / 4, state.visible!!.length)
        assertNull(state.pinchBaseline)
        assertNull(state.pinchAnchor)
    }

    @Test
    fun aChangeWithoutABeginningIsIgnored() {
        assertEquals(unzoomed(), unzoomed().then(GraphGesture.PinchChanged(4f)))
        assertEquals(unzoomed(), unzoomed().then(GraphGesture.PanChanged(120f)))
    }

    // --- pan -----------------------------------------------------------------------------------

    @Test
    fun panningIntoThePastStopsTheWindowFollowing() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        val panned = zoomed.then(
            GraphGesture.PanBegan, GraphGesture.PanChanged(600f), GraphGesture.PanEnded
        )
        assertTrue("dragged into history but still riding the live edge", !panned.isFollowing)
        assertEquals(zoomed.visible!!.length, panned.visible!!.length)
        assertTrue(panned.visible!!.start < zoomed.visible!!.start)
        assertNull(panned.panBaseline)
    }

    @Test
    fun panningBackToTheLiveEdgeResumesFollowing() {
        val parked = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded,
            GraphGesture.PanBegan, GraphGesture.PanChanged(600f), GraphGesture.PanEnded
        )
        val returned = parked.then(
            GraphGesture.PanBegan, GraphGesture.PanChanged(-100_000f), GraphGesture.PanEnded
        )
        assertEquals(dataEnd, returned.visible!!.endInclusive)
        assertTrue(returned.isFollowing)
    }

    /**
     * The unzoomed chart does not pan at all — the pager owns that drag.
     *
     * iOS enables its pan recogniser only while zoomed (`GraphV2Section.swift:1182`) so a horizontal
     * drag on the whole-day chart still swipes between tabs. Swallowing it here would strand the
     * user on one tab; and accepting it would also write the whole frame into `visible`, which is
     * the frozen-full state that the pinch path has to undo on end.
     */
    @Test
    fun theUnzoomedChartLeavesHorizontalDragsToThePager() {
        assertEquals(unzoomed(), unzoomed().then(GraphGesture.PanBegan))
        assertEquals(unzoomed(), unzoomed().then(GraphGesture.PanBegan, GraphGesture.PanChanged(400f)))
        assertEquals(unzoomed(), unzoomed().then(
            GraphGesture.PanBegan, GraphGesture.PanChanged(400f), GraphGesture.PanEnded
        ))
    }

    /**
     * Starting a pan while the window is riding the live edge must stop it riding.
     *
     * Asserted from a *following* window on purpose: every other pan test here starts from a
     * centred zoom, which is already not following, so deleting the freeze outright would leave
     * them all passing while the drag went dead under the finger.
     */
    @Test
    fun aPanBegunWhileFollowingStopsFollowing() {
        val following = unzoomed().then(
            GraphGesture.PinchBegan(1000f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        assertTrue("this test needs a following window to be meaningful", following.isFollowing)

        val midPan = following.then(GraphGesture.PanBegan, GraphGesture.PanChanged(300f))
        assertTrue(!midPan.isFollowing)
        assertEquals(midPan.visible, midPan.resolved(dataEnd + 15.minutes))
    }

    /** An unmeasured plot cannot say how far a drag went, and must not undo the drag so far. */
    @Test
    fun aDragReportedBeforeLayoutLeavesTheWindowAlone() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        val moved = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(300f))
        val unmeasured = GraphZoomReducer.reduce(
            moved, GraphGesture.PanChanged(320f), context.copy(plotWidth = 0f)
        )
        assertEquals("the window snapped back to where the drag started", moved.visible, unmeasured.visible)
    }

    /**
     * A gesture reports cumulatively, so every change must be measured from where it began.
     *
     * Against the running window instead, the scales would compound: 2 then 4 would land on an
     * eighth of the frame rather than a quarter, and a slow pinch would run away.
     */
    @Test
    fun everyChangeIsMeasuredFromWhereTheGestureBegan() {
        val pinched = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(2f), GraphGesture.PinchChanged(4f)
        )
        assertEquals(full.length / 4, pinched.visible!!.length)

        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        val once = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(100f))
        val twice = zoomed.then(
            GraphGesture.PanBegan, GraphGesture.PanChanged(50f), GraphGesture.PanChanged(100f)
        )
        assertEquals(once.visible, twice.visible)
    }

    /**
     * Letting go of a pan must not move the window, even if the chart moved under it.
     *
     * Only the *pinch* re-clamps when it ends, and only to undo a pinch that began and never moved
     * (`GraphV2Section.swift:1229` versus `:1261`). Pan has no such case to undo, because it is
     * never accepted on the unzoomed chart — and re-clamping it anyway would drag the window along
     * with a rolling 1일 domain at the instant the finger lifted, which is not what the finger did.
     */
    @Test
    fun lettingGoOfAPanLeavesTheWindowExactlyWhereTheFingerLeftIt() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f), GraphGesture.PinchEnded
        )
        val parked = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(100_000f))
        assertEquals("this test needs a window against the left edge", full.start, parked.visible!!.start)

        // Ten seconds later the rolling window has moved on, start included.
        val rolled = context.copy(
            fullDomain = (full.start + 10.seconds)..(full.endInclusive + 10.seconds),
            latestAnchor = dataEnd + 10.seconds
        )
        val released = GraphZoomReducer.reduce(parked, GraphGesture.PanEnded, rolled)
        assertEquals(parked.visible, released.visible)
        assertNull(released.panBaseline)
    }

    /**
     * A drag that widens back to the whole chart is over, and cannot be resumed by a later report.
     *
     * iOS disables the pan recogniser the instant the chart stops being zoomed, and disabling one
     * that is already tracking cancels it. Keeping the baseline here instead would let the very next
     * change — after the frame grew again — resurrect a window from a gesture that no longer exists.
     */
    @Test
    fun aDragThatLosesTheWindowIsOver() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f), GraphGesture.PinchEnded
        )
        val dragging = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(50f))
        assertNotNull(dragging.panBaseline)

        // The frame shrinks to what the window already covers, so the clamp folds it to "everything".
        val shrunk = context.copy(fullDomain = dragging.visible!!)
        val folded = GraphZoomReducer.reduce(dragging, GraphGesture.PanChanged(50f), shrunk)
        assertEquals(GraphZoomState(), folded)

        // And the frame coming back must not bring the drag back with it.
        assertEquals(folded, GraphZoomReducer.reduce(folded, GraphGesture.PanChanged(50f), context))
    }

    /**
     * The pinch has the opposite rule, and it is not an oversight.
     *
     * Its recogniser is never disabled on iOS, so widening out through the whole frame and back in
     * is one continuous gesture — the baseline has to survive the trip through "everything".
     */
    @Test
    fun aPinchMayPassOutThroughTheWholeChartAndBackIn() {
        val out = unzoomed().then(GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(0.5f))
        assertNull("widening past the frame is the unzoomed chart", out.visible)
        assertNotNull("but the gesture is still in hand", out.pinchBaseline)

        val backIn = out.then(GraphGesture.PinchChanged(4f))
        assertNotNull("the pinch could not narrow again after passing through the whole chart",
            backIn.visible)
        assertEquals(full.length / 4, backIn.visible!!.length)
    }

    /** The same drift rule as pinch: a pan must start from what is on screen, not what was stored. */
    @Test
    fun aPanOnADriftingChartAlsoStartsFromWhereTheChartActuallyIs() {
        val following = unzoomed().then(
            GraphGesture.PinchBegan(1000f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        val twentyMinutesOn = dataEnd + 20.minutes
        val later = context.copy(
            fullDomain = full.start..(twentyMinutesOn + 10.minutes),
            latestAnchor = twentyMinutesOn
        )
        val touched = GraphZoomReducer.reduce(following, GraphGesture.PanBegan, later)
        assertEquals(twentyMinutesOn, touched.panBaseline!!.endInclusive)
    }

    // --- double tap ------------------------------------------------------------------------------

    @Test
    fun aDoubleTapOnTheUnzoomedChartOpensSixHoursAroundIt() {
        val state = unzoomed().then(GraphGesture.DoubleTap(locationX = 250f))
        val window = state.visible!!
        assertEquals(6.hours, window.length)
        // A quarter across the padded frame, so the window centres there.
        val tapped = full.start + full.length / 4
        assertEquals(tapped, window.start + 3.hours)
    }

    @Test
    fun aDoubleTapOnAZoomedChartPutsEverythingBack() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(6f), GraphGesture.PinchEnded
        )
        assertTrue(zoomed.isZoomed)
        assertEquals(GraphZoomState(), zoomed.then(GraphGesture.DoubleTap(500f)))
    }

    @Test
    fun aDoubleTapAtTheLiveEdgeStartsOutFollowing() {
        val state = unzoomed().then(GraphGesture.DoubleTap(locationX = 1000f))
        assertEquals(dataEnd, state.visible!!.endInclusive)
        assertTrue(state.isFollowing)
    }

    @Test
    fun aDoubleTapInTheMorningDoesNotFollow() {
        val state = unzoomed().then(GraphGesture.DoubleTap(locationX = 100f))
        assertTrue(!state.isFollowing)
    }

    // --- reset and the resolved window ---------------------------------------------------------

    @Test
    fun resetClearsEverythingIncludingAGestureInFlight() {
        val midGesture = unzoomed().then(GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f))
        assertEquals(GraphZoomState(), midGesture.then(GraphGesture.Reset))
    }

    @Test
    fun aFollowingWindowDriftsWithTheDataAndAParkedOneDoesNot() {
        val following = unzoomed().then(
            GraphGesture.PinchBegan(1000f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        assertTrue(following.isFollowing)
        val later = dataEnd + 20.minutes
        val drifted = following.resolved(later)!!
        assertEquals(later, drifted.endInclusive)
        assertEquals(following.visible!!.length, drifted.length)

        val parked = following.copy(isFollowing = false)
        assertEquals(following.visible, parked.resolved(later))
    }

    /**
     * The freeze is not bookkeeping — it decides what is drawn while the finger is still down.
     *
     * Left following, `resolved` would re-pin the window to the live edge on every frame of the
     * drag, so the chart would sit still under a moving finger and the pan would look broken.
     */
    @Test
    fun theWindowUnderAMovingFingerIsTheOneTheFingerPutThere() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        val midPan = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(400f))
        assertEquals(midPan.visible, midPan.resolved(dataEnd))

        val midPinch = zoomed.then(GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(2f))
        assertEquals(midPinch.visible, midPinch.resolved(dataEnd))
    }

    /**
     * Touching a chart that has been drifting must not throw away the drift.
     *
     * While following, what is on screen is the stored window moved to the live edge. A gesture that
     * started from the stored one instead would jump the chart backwards the instant it was touched
     * — by exactly as much time as had passed since the zoom.
     */
    @Test
    fun aGestureOnADriftingChartStartsFromWhereTheChartActuallyIs() {
        val following = unzoomed().then(
            GraphGesture.PinchBegan(1000f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        assertTrue(following.isFollowing)

        val twentyMinutesOn = dataEnd + 20.minutes
        val later = context.copy(
            fullDomain = full.start..(twentyMinutesOn + 10.minutes),
            latestAnchor = twentyMinutesOn
        )
        val touched = GraphZoomReducer.reduce(following, GraphGesture.PinchBegan(500f), later)

        assertEquals(twentyMinutesOn, touched.pinchBaseline!!.endInclusive)
        assertEquals(following.visible!!.length, touched.pinchBaseline!!.length)
    }

    /**
     * A second finger supersedes the drag, and leaves nothing of it behind.
     *
     * `PinchEnded` clears only the pinch's own fields, so a pan whose baseline was still set when
     * the pinch began would outlive the gesture that made it. Nothing reads it afterwards today —
     * the next `PanBegan` overwrites it — but a finished gesture should leave no state at all, and
     * "nothing reads it *today*" is the kind of thing that stops being true.
     */
    @Test
    fun aPinchThatInterruptsAPanLeavesNoTraceOfEither() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f), GraphGesture.PinchEnded
        )
        val panning = zoomed.then(GraphGesture.PanBegan, GraphGesture.PanChanged(80f))
        assertNotNull("this test needs a pan in flight", panning.panBaseline)

        val interrupted = panning.then(GraphGesture.PinchBegan(400f))
        assertNull("the pan's baseline survived the second finger", interrupted.panBaseline)

        val finished = interrupted.then(GraphGesture.PinchChanged(2f), GraphGesture.PinchEnded)
        assertNull(finished.pinchBaseline)
        assertNull(finished.panBaseline)
    }

    // --- the window that gets drawn ----------------------------------------------------------

    private val dayFrame = TimeFrame(midnight, dataEnd)

    /**
     * A window saved yesterday is pulled back onto today's chart before anything is drawn.
     *
     * Tested here rather than on the device on purpose: a gesture launders the window through the
     * reducer's own clamp, so an on-device test of this passes with or without it — that mutant was
     * run and survived. What breaks without this is the *untouched* chart, which draws gridlines
     * and no lines at all, and does not even show the empty message because the lines come from the
     * data frame rather than the window.
     */
    @Test
    fun aWindowFromAnotherDayIsPulledOntoThisOne() {
        val yesterday = GraphZoomState(
            visible = (midnight - 30.hours)..(midnight - 24.hours),
            isFollowing = false
        )
        val drawn = yesterday.windowFor(dayFrame, GraphPeriod.ONE_DAY)
        assertNotNull("a six-hour window should survive, moved", drawn)
        assertEquals("it should sit on the frame's leading edge", 6.hours, drawn!!.length)
        assertTrue("still outside the frame", drawn.endInclusive > dayFrame.start)
        assertTrue("pushed past the data", drawn.endInclusive <= dayFrame.end)
    }

    @Test
    fun aWindowInsideTheFrameIsLeftAlone() {
        val inside = GraphZoomState(
            visible = (midnight + 3.hours)..(midnight + 9.hours),
            isFollowing = false
        )
        assertEquals(inside.visible, inside.windowFor(dayFrame, GraphPeriod.ONE_DAY))
    }

    /**
     * "Zoomed all the way out" is measured against the **padded** frame, not the data.
     *
     * A window covering exactly the day's data is 24h inside a 24h20m frame — 98.6%, so it is still
     * a window. Measured against the unpadded data instead it would be 100% and collapse to "show
     * everything", and the user's zoom would vanish the moment it reached the edges of the data.
     */
    @Test
    fun theFullViewTestUsesThePaddedFrame() {
        val wholeDay = GraphZoomState(visible = dayFrame.start..dayFrame.end, isFollowing = false)
        val drawn = wholeDay.windowFor(dayFrame, GraphPeriod.ONE_DAY)
        assertNotNull("a window covering the data collapsed to the whole chart", drawn)
        assertEquals(24.hours, drawn!!.length)
    }

    /**
     * …and the right-hand stop is the **data**, not the padding.
     *
     * Stopping at the padded edge would leave every window that reached the end sitting ten minutes
     * past the newest point — never within a bucket of it — so following could never resume, which
     * is the one way anyone gets back to a live chart.
     */
    @Test
    fun theRightHandStopIsTheDataNotThePadding() {
        val pastTheEnd = GraphZoomState(
            visible = (dataEnd - 2.hours)..(dataEnd + 2.hours),
            isFollowing = false
        )
        val drawn = pastTheEnd.windowFor(dayFrame, GraphPeriod.ONE_DAY)!!
        assertEquals("the window ran past the last observation", dayFrame.end, drawn.endInclusive)
        assertTrue(
            "and so it could never start following again",
            GraphZoomMath.shouldFollow(drawn.endInclusive, dayFrame.end, 10.minutes)
        )
    }

    /** Zoom belongs to 1일; every other period draws the whole frame however stale the state is. */
    @Test
    fun noOtherPeriodDrawsAZoomWindow() {
        val zoomed = GraphZoomState(visible = (midnight + 3.hours)..(midnight + 9.hours))
        GraphPeriod.entries.filter { it != GraphPeriod.ONE_DAY }.forEach {
            assertNull("$it drew a zoom window", zoomed.windowFor(dayFrame, it))
        }
    }

    @Test
    fun anUnzoomedStateAndAnAbsentFrameBothDrawEverything() {
        assertNull(GraphZoomState().windowFor(dayFrame, GraphPeriod.ONE_DAY))
        val zoomed = GraphZoomState(visible = (midnight + 3.hours)..(midnight + 9.hours))
        assertNull(zoomed.windowFor(null, GraphPeriod.ONE_DAY))
    }

    // --- what survives being put away ------------------------------------------------------

    /**
     * The saver exists because going fullscreen removes the pager, and with it the chart.
     *
     * It deliberately keeps only the window and the follow flag. A gesture's baselines are not
     * saved — state is only ever put away between touches, and restoring a half-finished pinch
     * would leave a baseline that no finger corresponds to.
     */
    @Test
    fun aZoomedWindowComesBackFromBeingSaved() {
        val zoomed = unzoomed().then(
            GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f), GraphGesture.PinchEnded
        )
        val restored = saveAndRestore(zoomed)
        assertEquals(zoomed.visible, restored.visible)
        assertEquals(zoomed.isFollowing, restored.isFollowing)
        assertNull(restored.pinchBaseline)
        assertNull(restored.panBaseline)
    }

    /**
     * An unzoomed chart saves as *nothing at all*, which is how it comes back unzoomed.
     *
     * `listSaver` turns an empty list into null (`ListSaver.kt:44`), and a null is the framework's
     * way of saying there is nothing to restore — `rememberSaveable` then keeps its initial value.
     * Worth pinning: a saver that returned a three-element "empty" marker instead would work too,
     * and someone tidying this up could switch to one without noticing the contract changed.
     */
    @Test
    fun anUnzoomedChartSavesNothingAtAll() {
        assertNull(save(GraphZoomState()))
    }

    /** A state put away mid-gesture keeps its window and drops the half-finished gesture. */
    @Test
    fun aStatePutAwayMidGestureComesBackWithoutTheGesture() {
        val midGesture = unzoomed().then(GraphGesture.PinchBegan(500f), GraphGesture.PinchChanged(4f))
        assertNotNull(midGesture.pinchBaseline)
        val restored = saveAndRestore(midGesture)
        assertEquals(midGesture.visible, restored.visible)
        assertNull(restored.pinchBaseline)
        assertNull(restored.pinchAnchor)
    }

    @Test
    fun theFollowFlagSurvivesInBothPositions() {
        val following = unzoomed().then(
            GraphGesture.PinchBegan(1000f), GraphGesture.PinchChanged(8f), GraphGesture.PinchEnded
        )
        assertTrue(following.isFollowing)
        assertTrue(saveAndRestore(following).isFollowing)

        val parked = following.copy(isFollowing = false)
        assertTrue(!saveAndRestore(parked).isFollowing)
    }

    private fun save(state: GraphZoomState): Any? {
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        return with(GraphZoomStateSaver) { scope.save(state) }
    }

    private fun saveAndRestore(state: GraphZoomState): GraphZoomState =
        GraphZoomStateSaver.restore(save(state)!!)!!

    @Test
    fun anUnzoomedChartResolvesToNothingSoTheWholeFrameIsDrawn() {
        assertNull(unzoomed().resolved(dataEnd))
        assertNull(unzoomed().resolved(null))
    }
}
