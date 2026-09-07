package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
 * The zoom window's arithmetic, against the iOS implementation it is ported from.
 *
 * The cases that matter are not the interpolations — they are the boundaries, where iOS makes a
 * specific choice that is easy to port wrongly: which of the two windows bounds the right edge,
 * what "zoomed all the way out" means, and what an impossible input does.
 */
class GraphZoomMathTest {

    private val midnight = Instant.parse("2026-09-07T00:00:00Z")
    private val dataStart = midnight
    private val dataEnd = midnight + 24.hours

    /** 1일 shape: the padded frame the x scale maps onto, ±10 minutes. */
    private val fullDomain = (dataStart - 10.minutes)..(dataEnd + 10.minutes)
    private val minLength = 1.hours

    private fun clamp(
        proposed: ClosedRange<Instant>,
        full: ClosedRange<Instant> = fullDomain,
        latest: Instant? = dataEnd,
        min: Duration = minLength
    ) = GraphZoomMath.clampVisibleDomain(proposed, full, latest, min)

    private val ClosedRange<Instant>.length: Duration get() = endInclusive - start

    // --- the period-derived constants ------------------------------------------------------------

    /** Six buckets, not a hardcoded hour — they only coincide because 1일 buckets at 10 minutes. */
    @Test
    fun theZoomFloorIsSixBucketsWhichIsAnHourOnTheDayChart() {
        assertEquals(1.hours, GraphZoomMath.minVisibleLength(GraphPeriod.ONE_DAY))
        assertEquals(6.hours, GraphZoomMath.minVisibleLength(GraphPeriod.ONE_WEEK))
        assertEquals(6.days, GraphZoomMath.minVisibleLength(GraphPeriod.THREE_MONTHS))
        GraphPeriod.entries.forEach {
            assertEquals("$it", GraphPreparedBuilder.bucketFor(it) * 6, GraphZoomMath.minVisibleLength(it))
        }
    }

    @Test
    fun aDoubleTapOpensSixHoursOnTheDayChartAndThirtyBucketsElsewhere() {
        assertEquals(6.hours, GraphZoomMath.doubleTapWindow(GraphPeriod.ONE_DAY))
        assertEquals(30.hours, GraphZoomMath.doubleTapWindow(GraphPeriod.ONE_WEEK))
        assertEquals(30.days, GraphZoomMath.doubleTapWindow(GraphPeriod.ONE_YEAR))
    }

    @Test
    fun followResumesWithinOneBucket() {
        GraphPeriod.entries.forEach {
            assertEquals("$it", GraphPreparedBuilder.bucketFor(it), GraphZoomMath.followThreshold(it))
        }
    }

    // --- locating a finger -----------------------------------------------------------------------

    @Test
    fun aFingerHalfwayAcrossThePlotIsHalfwayThroughTheWindow() {
        val a = GraphZoomMath.locationToInstant(
            locationX = 60f, plotLeft = 20f, plotWidth = 80f, baseline = dataStart..dataEnd
        )
        assertEquals(0.5, a.fraction, 1e-9)
        assertEquals(dataStart + 12.hours, a.instant)
    }

    @Test
    fun aFingerOutsideThePlotIsPulledToItsEdge() {
        val left = GraphZoomMath.locationToInstant(-40f, 20f, 80f, dataStart..dataEnd)
        val right = GraphZoomMath.locationToInstant(400f, 20f, 80f, dataStart..dataEnd)
        assertEquals(dataStart, left.instant)
        assertEquals(dataEnd, right.instant)
    }

    /** Before layout there is no plot to divide by; the middle is the answer that cannot throw. */
    @Test
    fun anUnmeasuredPlotAnswersWithItsMiddle() {
        val a = GraphZoomMath.locationToInstant(37f, 0f, 0f, dataStart..dataEnd)
        assertEquals(0.5, a.fraction, 0.0)
        assertEquals(dataStart + 12.hours, a.instant)
    }

    // --- following the live edge -------------------------------------------------------------------

    @Test
    fun followingPinsTheWindowsRightEdgeToTheNewestPointAndKeepsItsLength() {
        val raw = (dataStart + 1.hours)..(dataStart + 3.hours)
        val later = dataEnd + 30.minutes
        val resolved = GraphZoomMath.resolvedDomain(raw, later, isFollowing = true)
        assertEquals(later, resolved.endInclusive)
        assertEquals(2.hours, resolved.length)
    }

    @Test
    fun notFollowingLeavesTheWindowWhereItWasPut() {
        val raw = (dataStart + 1.hours)..(dataStart + 3.hours)
        assertEquals(raw, GraphZoomMath.resolvedDomain(raw, dataEnd, isFollowing = false))
        assertEquals(raw, GraphZoomMath.resolvedDomain(raw, null, isFollowing = true))
    }

    @Test
    fun followResumesNearTheLiveEdgeAndNotBehindIt() {
        val bucket = 10.minutes
        assertTrue(GraphZoomMath.shouldFollow(dataEnd - 9.minutes, dataEnd, bucket))
        assertTrue("a window ending past the edge is still at it",
            GraphZoomMath.shouldFollow(dataEnd + 9.minutes, dataEnd, bucket))
        assertTrue(!GraphZoomMath.shouldFollow(dataEnd - 11.minutes, dataEnd, bucket))
        assertTrue("exactly one bucket away is already away",
            !GraphZoomMath.shouldFollow(dataEnd - bucket, dataEnd, bucket))
        assertTrue(!GraphZoomMath.shouldFollow(dataEnd, null, bucket))
    }

    // --- panning -----------------------------------------------------------------------------------

    @Test
    fun draggingRightWalksIntoThePastAndKeepsTheWindowsLength() {
        val baseline = (dataStart + 4.hours)..(dataStart + 8.hours)
        val moved = GraphZoomMath.panTranslated(baseline, translationX = 100f, plotWidth = 400f)
        // A quarter of the plot, so a quarter of the window's four hours.
        assertEquals(baseline.start - 1.hours, moved.start)
        assertEquals(baseline.endInclusive - 1.hours, moved.endInclusive)
        assertEquals(baseline.length, moved.length)
    }

    @Test
    fun draggingLeftWalksForward() {
        val baseline = (dataStart + 4.hours)..(dataStart + 8.hours)
        val moved = GraphZoomMath.panTranslated(baseline, -100f, 400f)
        assertEquals(baseline.start + 1.hours, moved.start)
    }

    @Test
    fun anUnmeasuredPlotCannotPan() {
        val baseline = (dataStart + 4.hours)..(dataStart + 8.hours)
        assertEquals(baseline, GraphZoomMath.panTranslated(baseline, 100f, 0f))
    }

    // --- pinching ------------------------------------------------------------------------------------

    @Test
    fun theInstantUnderTheFingerStaysUnderTheFinger() {
        val baseline = dataStart..(dataStart + 8.hours)
        val anchor = GraphZoomMath.Anchor(fraction = 0.25, instant = dataStart + 2.hours)
        val zoomed = GraphZoomMath.pinched(baseline, anchor, scale = 2f)
        assertEquals(4.hours, zoomed.length)
        // Still a quarter of the way in: an hour after the start of a four-hour window.
        assertEquals(anchor.instant, zoomed.start + 1.hours)
    }

    @Test
    fun pinchingOutLengthensTheWindow() {
        val baseline = (dataStart + 2.hours)..(dataStart + 4.hours)
        val anchor = GraphZoomMath.Anchor(0.5, dataStart + 3.hours)
        assertEquals(4.hours, GraphZoomMath.pinched(baseline, anchor, 0.5f).length)
    }

    /**
     * A stray touch can report an absurd ratio, and the window's length is divided by it.
     *
     * Asserted against the bound's own arithmetic rather than by comparing two clamped calls to each
     * other — that would hold just as well if the bound were any other number.
     */
    @Test
    fun anAbsurdScaleIsBoundedAtAHundredEitherWay() {
        val baseline = dataStart..(dataStart + 8.hours)
        val anchor = GraphZoomMath.Anchor(0.5, dataStart + 4.hours)
        assertEquals(8.hours / 100, GraphZoomMath.pinched(baseline, anchor, 10_000f).length)
        assertEquals(8.hours * 100, GraphZoomMath.pinched(baseline, anchor, 0.0001f).length)
    }

    // --- clamping: the single gate ------------------------------------------------------------------

    @Test
    fun awindowGrownBackToTheWholeFrameIsNoWindowAtAll() {
        assertNull(clamp(fullDomain))
        // 99%, so rounding in the arithmetic that produced it cannot leave the chart stuck zoomed.
        assertNull(clamp((fullDomain.start + 6.minutes)..fullDomain.endInclusive))
    }

    /**
     * The tolerance is 99% exactly, not "nearly all of it".
     *
     * A hundred-minute frame makes the boundary land on a whole minute, so this pins the number
     * rather than some value near it: 99 minutes folds to the whole chart, 98 stays a window.
     */
    @Test
    fun theFullViewThresholdSitsAtNinetyNinePercent() {
        val frame = midnight..(midnight + 100.minutes)
        val ninetyNine = (midnight + 1.minutes)..(midnight + 100.minutes)
        val ninetyEight = (midnight + 2.minutes)..(midnight + 100.minutes)
        assertNull(clamp(ninetyNine, full = frame, latest = null, min = 1.minutes))
        assertEquals(98.minutes, clamp(ninetyEight, full = frame, latest = null, min = 1.minutes)!!.length)
    }

    /**
     * Both corrections at once, in the order iOS applies them: left first, then right.
     *
     * A window wider than the space between the frame's start and the data's end cannot satisfy
     * both, and iOS resolves that by letting the start slip left of the frame rather than letting
     * the end run past the data. Swapping the order looks equally reasonable and is wrong: it would
     * end at 90 here, ten minutes beyond the newest point, and following could never resume.
     */
    @Test
    fun whenBothEdgesFightTheRightOneWins() {
        val frame = midnight..(midnight + 100.minutes)
        val latest = midnight + 80.minutes
        val proposed = (midnight - 30.minutes)..(midnight + 60.minutes)
        val clamped = clamp(proposed, full = frame, latest = latest, min = 10.minutes)!!
        assertEquals(latest, clamped.endInclusive)
        assertEquals(midnight - 10.minutes, clamped.start)
        assertTrue("the start was not allowed to slip outside the frame", clamped.start < frame.start)
    }

    @Test
    fun awindowCanNotBeNarrowerThanTheFloor() {
        val tiny = (dataStart + 12.hours)..(dataStart + 12.hours + 1.minutes)
        val clamped = clamp(tiny)
        assertNotNull(clamped)
        assertEquals(minLength, clamped!!.length)
        // Widened about its own centre rather than from one end.
        assertEquals(dataStart + 12.hours + 30.seconds, clamped.start + 30.minutes)
    }

    /**
     * The one that a port gets wrong: the right edge stops at the **data**, not at the padded frame.
     *
     * Letting it reach the padding would leave a window whose end is ten minutes past the newest
     * point — never within a bucket of it — so following could never resume.
     */
    @Test
    fun theRightEdgeStopsAtTheDataAndNotAtThePadding() {
        val pastTheEnd = (dataEnd - 1.hours)..(dataEnd + 1.hours)
        val clamped = clamp(pastTheEnd)!!
        assertEquals(dataEnd, clamped.endInclusive)
        assertTrue("clamped to the padded edge instead", clamped.endInclusive < fullDomain.endInclusive)
        assertTrue(GraphZoomMath.shouldFollow(clamped.endInclusive, dataEnd, 10.minutes))
    }

    @Test
    fun withNoDataAnchorTheFramesOwnEdgeIsTheBoundary() {
        val pastTheEnd = (dataEnd - 1.hours)..(dataEnd + 1.hours)
        assertEquals(fullDomain.endInclusive, clamp(pastTheEnd, latest = null)!!.endInclusive)
    }

    @Test
    fun awindowPushedOffTheLeftIsSlidBackIn() {
        val beforeTheStart = (fullDomain.start - 5.hours)..(fullDomain.start - 1.hours)
        val clamped = clamp(beforeTheStart)!!
        assertEquals(fullDomain.start, clamped.start)
        assertEquals(4.hours, clamped.length)
    }

    @Test
    fun aFrameWithNoWidthHasNothingToZoom() {
        val instant = dataStart..dataStart
        assertNull(clamp(dataStart..(dataStart + 1.hours), full = instant))
    }

    /** A floor wider than the whole frame means the frame is already as close as anyone can get. */
    @Test
    fun aFloorWiderThanTheFrameIsTheWholeFrame() {
        assertNull(clamp(dataStart..(dataStart + 1.hours), min = 100.days))
    }

    /** Reversed input cannot be produced by the gestures, but must not produce a reversed window. */
    @Test
    fun aBackwardsProposalComesBackForwards() {
        val backwards = (dataStart + 6.hours)..(dataStart + 2.hours)
        val clamped = clamp(backwards)!!
        assertEquals(minLength, clamped.length)
        assertTrue(clamped.start < clamped.endInclusive)
    }
}
