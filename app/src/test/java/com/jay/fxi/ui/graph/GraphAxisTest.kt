package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphAxisTest {

    /** 2026-09-01 00:00 KST. Every expectation below is in Seoul time. */
    private val midnightKst = Instant.parse("2026-08-31T15:00:00Z")

    // --- axis range ---------------------------------------------------------------------------------

    /**
     * Every axis gets a margin, not only the flat ones.
     *
     * A first version widened the range only when it fell under the floor. That leaves the highest
     * and lowest points of every ordinary chart drawn exactly on the border, where the stroke is
     * half-clipped. The margin is 5% of the span or the floor, whichever is larger, on each side.
     */
    @Test
    fun everyAxisGetsAMarginOnBothSides() {
        val range = GraphAxis.composeAxisRange(listOf(1390.0..1410.0), GraphAxis.KRW_MARGIN_FLOOR)!!
        // 5% of 20 is 1.0, which beats the 0.5 floor.
        assertEquals(1389.0, range.start, 1e-9)
        assertEquals(1411.0, range.endInclusive, 1e-9)
    }

    /** Below the floor, the floor wins — a 0.2-won day must not be magnified into a cliff. */
    @Test
    fun theFloorTakesOverWhenFivePercentIsTooSmall() {
        val range = GraphAxis.composeAxisRange(listOf(1400.0..1400.2), GraphAxis.KRW_MARGIN_FLOOR)!!
        assertEquals(1399.5, range.start, 1e-9)
        assertEquals(1400.7, range.endInclusive, 1e-9)
        // Symmetric, so the data keeps the middle of the axis.
        assertEquals(1400.1, (range.start + range.endInclusive) / 2.0, 1e-9)
    }

    /** The index scale needs its own floor: its numbers are a fourteenth of a KRW rate. */
    @Test
    fun theIndexAxisHasItsOwnFloor() {
        val range = GraphAxis.composeAxisRange(listOf(99.0..99.01), GraphAxis.INDEX_MARGIN_FLOOR)!!
        assertEquals(98.95, range.start, 1e-9)
        assertEquals(99.06, range.endInclusive, 1e-9)
        // Under the KRW floor the same series would be given a range ten times too wide to read.
        val wrong = GraphAxis.composeAxisRange(listOf(99.0..99.01), GraphAxis.KRW_MARGIN_FLOOR)!!
        assertTrue(wrong.endInclusive - wrong.start > range.endInclusive - range.start)
    }

    /** The axis is the union of everything visible on it, and then the margin. */
    @Test
    fun theAxisUnionsItsVisibleSeries() {
        val range = GraphAxis.composeAxisRange(
            listOf(1400.0..1401.0, 1390.0..1395.0, 1398.0..1420.0), GraphAxis.KRW_MARGIN_FLOOR
        )!!
        assertEquals(1390.0 - 1.5, range.start, 1e-9)
        assertEquals(1420.0 + 1.5, range.endInclusive, 1e-9)
        assertNull(GraphAxis.composeAxisRange(emptyList(), GraphAxis.KRW_MARGIN_FLOOR))
    }

    // --- value gridlines ------------------------------------------------------------------------------

    /**
     * The lines snap to a 1/2/5/10 ladder; the range does not. Rounding the range outward would
     * quietly enlarge it, which on a tight zoom undoes the zoom.
     */
    @Test
    fun linesSnapToTheLadderButTheRangeIsLeftAlone() {
        val ticks = GraphAxis.valueTicks(1387.3, 1412.9)
        assertTrue(ticks.isNotEmpty())
        val step = ticks[1].value - ticks[0].value
        assertTrue("step $step is not on the 1/2/5/10 ladder", step in listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0))
        ticks.forEach { assertEquals(0.0, it.value % step, 1e-6) }
    }

    /**
     * At most five lines inside the range, or the chart becomes a grid with a line through it.
     *
     * The cap only means something if some span would otherwise exceed it, so the span is searched
     * for rather than hardcoded — a literal one sits on a floating-point boundary and stops
     * exercising the loop the moment the arithmetic shifts underneath it. If no such span exists
     * the cap is dead code, and this fails saying so.
     */
    @Test
    fun theLadderIsClimbedUntilAtMostFiveLinesAreInside() {
        val low = 1000.0
        var span = 0.05
        var engaging: Double? = null
        while (span < 3000.0 && engaging == null) {
            if (GraphAxis.interiorCount(low, low + span, GraphAxis.niceStep(span / 4)) > 5) engaging = span
            span *= 1.01
        }
        assertNotNull("no span reaches the cap, so it is unreachable", engaging)

        val natural = GraphAxis.niceStep(engaging!! / 4)
        val chosen = GraphAxis.valueTicks(low, low + engaging!!).let { it[1].value - it[0].value }
        assertTrue("the cap did not coarsen the step ($chosen vs $natural)", chosen > natural)

        (listOf(engaging!!) + listOf(0.4, 1.0, 7.5, 23.0, 260.0, 1999.0)).forEach { s ->
            val ticks = GraphAxis.valueTicks(low, low + s)
            val inside = ticks.count { it.value > low + 1e-9 && it.value < low + s - 1e-9 }
            assertTrue("span $s produced $inside interior lines", inside <= 5)
        }
    }

    @Test
    fun theLadderClimbsOneTwoFiveTen() {
        assertEquals(1.0, GraphAxis.nextLadderStep(0.5), 1e-9)
        assertEquals(2.0, GraphAxis.nextLadderStep(1.0), 1e-9)
        assertEquals(5.0, GraphAxis.nextLadderStep(2.0), 1e-9)
        assertEquals(10.0, GraphAxis.nextLadderStep(5.0), 1e-9)
        assertEquals(20.0, GraphAxis.nextLadderStep(10.0), 1e-9)
        // d3's thresholds are geometric means (√2, √10, √50), not midpoints (1.5, 3.5, 7.5).
        // Only inputs *between* the two tell them apart — 1.2 and 1.5 round the same way under both.
        assertEquals(2.0, GraphAxis.niceStep(1.45), 1e-9)
        assertEquals(5.0, GraphAxis.niceStep(3.3), 1e-9)
        assertEquals(10.0, GraphAxis.niceStep(7.2), 1e-9)
        assertEquals(1.0, GraphAxis.niceStep(1.2), 1e-9)
        assertEquals(1.0, GraphAxis.niceStep(0.0), 1e-9)
    }

    /** A spanless range still gets one line, rather than none or a division by zero. */
    @Test
    fun aSpanlessRangeStillGetsALine() {
        assertEquals(1, GraphAxis.valueTicks(1400.0, 1400.0).size)
        assertEquals(1, GraphAxis.valueTicks(1400.0, 1399.0).size)
        assertEquals(1, GraphAxis.ticksAt(1400.0, 1410.0, step = 0.0).size)
    }

    // --- the index overlay --------------------------------------------------------------------------

    @Test
    fun anIndexValueIsPlacedProportionallyOnTheRateScale() {
        // Midway through the index range lands midway up the rate range.
        assertEquals(
            1400.0,
            GraphAxis.normalizeIndexValue(99.0, rateLow = 1390.0, rateHigh = 1410.0, indexLow = 98.0, indexHigh = 100.0),
            1e-9
        )
        // A spanless input sits on the floor rather than dividing by zero.
        assertEquals(1390.0, GraphAxis.normalizeIndexValue(99.0, 1390.0, 1410.0, 99.0, 99.0), 0.0)
        assertEquals(1390.0, GraphAxis.normalizeIndexValue(99.0, 1390.0, 1390.0, 98.0, 100.0), 0.0)
    }

    /** Two decimals below a step of 0.1, so a 0.05 ladder never prints the same label twice. */
    @Test
    fun theIndexLadderChangesPrecisionWithItsStep() {
        val fine = GraphAxis.indexLabels(99.00, 99.20)
        assertTrue(fine.isNotEmpty())
        assertTrue("a fine ladder must print hundredths", fine.all { it.label.substringAfter('.').length == 2 })
        assertEquals(fine.map { it.label }, fine.map { it.label }.distinct())

        val coarse = GraphAxis.indexLabels(95.0, 105.0)
        assertTrue("a coarse ladder prints tenths", coarse.all { it.label.substringAfter('.').length == 1 })
    }

    /**
     * A zoom tight enough to fall between two steps yields no aligned label at all. An axis with
     * no numbers on it is unreadable, so the midpoint is labelled alone.
     */
    @Test
    fun aZoomBetweenTwoStepsStillGetsOneLabel() {
        val labels = GraphAxis.indexLabels(99.011, 99.019)
        assertEquals(1, labels.size)
        assertEquals(99.015, labels.single().value, 1e-9)
        assertTrue(labels.single().value in 99.011..99.019)
    }

    @Test
    fun aSpanlessIndexRangeHasNoLabels() {
        assertEquals(emptyList<AxisTick>(), GraphAxis.indexLabels(99.0, 99.0))
        assertEquals(emptyList<AxisTick>(), GraphAxis.indexLabels(100.0, 99.0))
    }

    // --- time gridlines -------------------------------------------------------------------------------

    /** Three-hourly across a full day, hourly once zoomed inside half of one. */
    @Test
    fun theHourIntervalOpensUpAsTheWindowGrows() {
        assertEquals(3, GraphAxis.hourIntervalFor(24.hours))
        assertEquals(3, GraphAxis.hourIntervalFor(12.hours + 1.minutes))
        assertEquals(1, GraphAxis.hourIntervalFor(12.hours))
        assertEquals(1, GraphAxis.hourIntervalFor(2.hours))
    }

    /** 1일 lines land on aligned hours in Seoul, and midnight prints a date instead of an hour. */
    @Test
    fun oneDayLinesAreAlignedHoursInSeoul_andMidnightShowsTheDate() {
        val window = TimeFrame(midnightKst - 3.hours, midnightKst + 6.hours)
        val ticks = GraphAxis.xTicks(GraphPeriod.ONE_DAY, window, window, rightEdge = midnightKst + 6.hours)
        ticks.forEach { assertEquals(0L, it.ts.epochSeconds % 3600) }
        val midnight = ticks.single { it.ts == midnightKst }
        assertTrue(midnight.isMidnight)
        assertEquals("9/1", midnight.label)
        assertEquals("03", ticks.single { it.ts == midnightKst + 3.hours }.label)
    }

    /**
     * The label rule measures against the **frame's** right edge, not the last data point — which
     * is exactly how today's date comes to be shown at all.
     */
    @Test
    fun labelsAreHiddenNearTheFrameEdge_andBeyondIt() {
        val edge = midnightKst
        assertFalse(GraphAxis.labelVisible(edge + 1.hours, edge, null, GraphPeriod.ONE_DAY, isMidnight = false))
        assertFalse(GraphAxis.labelVisible(edge - 5.minutes, edge, null, GraphPeriod.ONE_DAY, isMidnight = false))
        assertTrue(GraphAxis.labelVisible(edge - 10.minutes, edge, null, GraphPeriod.ONE_DAY, isMidnight = false))
        // Midnight's label is a date and takes twice the width, so it needs twice the berth.
        assertFalse(GraphAxis.labelVisible(edge - 15.minutes, edge, null, GraphPeriod.ONE_DAY, isMidnight = true))
        assertTrue(GraphAxis.labelVisible(edge - 20.minutes, edge, null, GraphPeriod.ONE_DAY, isMidnight = true))
        // No frame edge at all: nothing to collide with.
        assertTrue(GraphAxis.labelVisible(edge + 100.days, null, null, GraphPeriod.ONE_DAY, isMidnight = false))
    }

    /** The first line is dropped only on the long periods — 1일 needs its leading date. */
    @Test
    fun theFirstLineKeepsItsLabelOnOneDayOnly() {
        val first = midnightKst
        val edge = midnightKst + 30.days
        assertTrue(GraphAxis.labelVisible(first, edge, first, GraphPeriod.ONE_DAY, isMidnight = true))
        assertFalse(GraphAxis.labelVisible(first, edge, first, GraphPeriod.ONE_WEEK, isMidnight = true))
        assertFalse(GraphAxis.labelVisible(first, edge, first, GraphPeriod.THREE_MONTHS, isMidnight = true))
        assertFalse(GraphAxis.labelVisible(first, edge, first, GraphPeriod.ONE_YEAR, isMidnight = true))
    }

    @Test
    fun eachPeriodKeepsItsOwnDistanceFromTheEdge() {
        val edge = midnightKst + 100.days
        assertFalse(GraphAxis.labelVisible(edge - 5.hours, edge, null, GraphPeriod.ONE_WEEK, isMidnight = true))
        assertTrue(GraphAxis.labelVisible(edge - 6.hours, edge, null, GraphPeriod.ONE_WEEK, isMidnight = true))
        assertFalse(GraphAxis.labelVisible(edge - 2.days, edge, null, GraphPeriod.THREE_MONTHS, isMidnight = true))
        assertTrue(GraphAxis.labelVisible(edge - 3.days, edge, null, GraphPeriod.THREE_MONTHS, isMidnight = true))
        assertFalse(GraphAxis.labelVisible(edge - 9.days, edge, null, GraphPeriod.ONE_YEAR, isMidnight = true))
        assertTrue(GraphAxis.labelVisible(edge - 10.days, edge, null, GraphPeriod.ONE_YEAR, isMidnight = true))
    }

    /** Long-period lines land on Seoul midnight; 1년 steps two months at a time from an even one. */
    @Test
    fun longPeriodLinesLandOnSeoulMidnight() {
        val weekFrame = TimeFrame(midnightKst, midnightKst + 7.days)
        val week = GraphAxis.xTicks(GraphPeriod.ONE_WEEK, weekFrame, weekFrame, midnightKst + 7.days)
        assertEquals(8, week.size)
        assertTrue(week.all { it.isMidnight })
        assertEquals("9/1", week.first().label)

        val yearFrame = TimeFrame(midnightKst, midnightKst + 200.days)
        val year = GraphAxis.xTicks(GraphPeriod.ONE_YEAR, yearFrame, yearFrame, midnightKst + 200.days)
        assertTrue(year.all { it.label.endsWith("월") })
        // Even months, two at a time — otherwise a year becomes twelve labels in a phone's width.
        // The sequence is aligned from 8월, which then falls outside the display window and is
        // clipped; what survives is still on the even cadence.
        assertEquals(listOf("10월", "12월", "2월"), year.map { it.label })
        assertTrue(
            "the sequence left the even cadence",
            year.all { it.label.removeSuffix("월").toInt() % 2 == 0 }
        )
    }

    /** 3달 steps a fortnight, so a quarter does not become a wall of dates. */
    @Test
    fun threeMonthsStepsAFortnight() {
        val quarter = TimeFrame(midnightKst, midnightKst + 60.days)
        val ticks = GraphAxis.xTicks(GraphPeriod.THREE_MONTHS, quarter, quarter, midnightKst + 60.days)
        assertEquals(14.days, ticks[1].ts - ticks[0].ts)
    }

    /**
     * Lines are generated from the **data** window and clipped to the **display** window.
     *
     * Generating from the padded window shifts the whole alignment — six hours of 3달 padding
     * moves a fortnightly base from Seoul midnight on the 2nd to the 1st, and every date label is
     * then a day out.
     */
    @Test
    fun linesAreAlignedToTheDataWindow_notThePaddedOne() {
        val data = TimeFrame(midnightKst + 1.days, midnightKst + 60.days)
        val padded = GraphFrame.rendered(data, GraphPeriod.THREE_MONTHS)
        val ticks = GraphAxis.xTicks(GraphPeriod.THREE_MONTHS, data, padded, data.end)
        // The 2nd, not the 1st: the six-hour lead does not move the base.
        assertEquals("9/2", ticks.first().label)
        ticks.forEach { assertTrue("a line fell outside the display window", it.ts >= padded.start && it.ts <= padded.end) }
    }

    /**
     * A line outside the display window is dropped before the first-line rule is applied.
     *
     * Otherwise an off-screen line absorbs the suppression meant for the leftmost visible one, and
     * the real first label prints where it gets clipped.
     */
    @Test
    fun anOffScreenLineDoesNotAbsorbTheFirstLineSuppression() {
        val data = TimeFrame(midnightKst, midnightKst + 30.days)
        val display = TimeFrame(midnightKst + 10.days, midnightKst + 30.days)
        val ticks = GraphAxis.xTicks(GraphPeriod.ONE_WEEK, data, display, midnightKst + 30.days)
        assertTrue(ticks.all { it.ts >= display.start })
        // The leftmost *visible* line is the suppressed one.
        assertFalse(ticks.first().showLabel)
        assertTrue(ticks.drop(1).first().showLabel)
    }

    /** January aligns back to the previous December — clamping to January leaves an odd month. */
    @Test
    fun januaryAlignsBackToDecember_notToItself() {
        val january = Instant.parse("2026-12-31T15:00:00Z") // 2027-01-01 00:00 KST
        val frame = TimeFrame(january, january + 200.days)
        val ticks = GraphAxis.xTicks(GraphPeriod.ONE_YEAR, frame, frame, frame.end)
        assertEquals(listOf("2월", "4월", "6월"), ticks.map { it.label })
    }

    /**
     * A 1일 window that does not open exactly on the hour still shows its date.
     *
     * Skipping the aligned stamp that precedes the data window drops the one tick whose label is a
     * date — the padding puts it back inside the *display* window, so the clip keeps it and the
     * chart says which day it is.
     */
    @Test
    fun theMidnightLineSurvivesAWindowThatOpensAfterIt() {
        val data = TimeFrame(midnightKst + 5.minutes, midnightKst + 1.days + 5.minutes)
        val display = GraphFrame.rendered(data, GraphPeriod.ONE_DAY)
        val ticks = GraphAxis.xTicks(GraphPeriod.ONE_DAY, data, display, data.end)
        assertEquals("the leading date line was skipped", midnightKst, ticks.first().ts)
        assertEquals("9/1", ticks.first().label)
        assertTrue(ticks.first().isMidnight)
    }
}
