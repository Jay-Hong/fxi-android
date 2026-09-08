package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.ScalePolicyCalculator
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a rate row shows and how wide its bar is — the contract free and premium rows share.
 *
 * These are the three pieces the row itself cannot be trusted to get right on a device: the number
 * and the width have to agree, the width has to be linear over the scale policy's range, and the
 * ▲▼ cue has to notice a one-unit move.
 */
class RateBarPresentationTest {

    // The whole row is measured in one unit; these stand in for a phone's dp.
    private val available = 300f
    private val minBar = 60f
    private val diffMin = 50f

    private fun width(value: Double, low: Double, high: Double) =
        RateBarWidth.of(value, low, high, available, minBar, diffMin)

    /**
     * The same printed number is the same bar. This is why the width quantizes instead of using the
     * raw value: the row's text says the two are equal and the picture must not disagree.
     */
    @Test
    fun twoRowsPrintingTheSameNumberGetTheSameBar() {
        val a = 1400.0
        val b = 1400.004   // prints 1400.00 as well
        assertEquals(RateDisplay.format(a), RateDisplay.format(b))
        assertEquals(width(a, 1395.0, 1405.0), width(b, 1395.0, 1405.0), 0f)
    }

    /** …and a number that prints differently is drawn differently, or the spread is invisible. */
    @Test
    fun oneUnitApartIsOneVisibleStep() {
        val lower = width(1400.00, 1395.0, 1405.0)
        val upper = width(1400.01, 1395.0, 1405.0)
        assertNotEquals("a printed step produced no width step", lower, upper)
        assertTrue(upper > lower)
    }

    /**
     * Linear: equal steps in value are equal steps in width.
     *
     * The legacy table was banded, so a spread crossing 3원 or 4원 moved every bar by a step nobody's
     * quote made. Three evenly spaced values are the smallest fixture that tells the two apart.
     */
    @Test
    fun equalStepsInValueAreEqualStepsInWidth() {
        val low = 1390.0
        val high = 1410.0
        val first = width(1395.0, low, high) - width(1390.0, low, high)
        val second = width(1400.0, low, high) - width(1395.0, low, high)
        val third = width(1405.0, low, high) - width(1400.0, low, high)
        assertEquals(first, second, 1e-3f)
        assertEquals(second, third, 1e-3f)
    }

    /**
     * The shape does not depend on the spread. This is what "linear" buys over the legacy table.
     *
     * The old formula was linear *inside* each band and stepped between them — `35%~80%` at a span
     * of 4원 or more, `50%~80%` at 1원, `65%~77%` below 0.3원 — so evenly spaced values inside one
     * band still came out evenly spaced and a test with a single spread cannot tell the two apart.
     * A value halfway along its domain has to be drawn at the same length whatever that domain is.
     */
    @Test
    fun theWidthDependsOnlyOnRelativePositionNotOnTheSpan() {
        val wide = width(1400.0, 1390.0, 1410.0)      // span 20
        val narrow = width(1400.25, 1400.0, 1400.5)   // span 0.5, same halfway point
        assertEquals("the spread changed the shape", wide, narrow, 1e-3f)
        // …and the quarter point too, so it is not only the midpoint that lines up. Both fixtures
        // land on the second decimal: 1400.125 would quantize to 1400.12 and sit at 0.24, which is
        // the width contract working, not a failure of it.
        assertEquals(width(1395.0, 1390.0, 1410.0), width(1400.10, 1400.0, 1400.4), 1e-3f)
    }

    /**
     * A narrow row keeps room for the difference column.
     *
     * `0.80 × available` is the usual bound, but on a small phone the row is short enough that
     * `available − diffMin` is the tighter one — measured, that crossover is at about 275dp of row,
     * and a 320dp-wide phone leaves roughly 240dp after padding and the timestamp column. Without
     * this the bar would grow over the difference it is supposed to be compared against.
     */
    @Test
    fun aNarrowRowKeepsRoomForTheDifferenceColumn() {
        val narrow = 240f
        val diff = 55f
        assertTrue("the fixture is not narrow enough to bind", narrow - diff < RateBarWidth.MAX_FRACTION * narrow)
        val top = RateBarWidth.of(1410.0, 1390.0, 1410.0, available = narrow, minBar = 120f, diffMin = diff)
        assertEquals(narrow - diff, top, 1e-3f)
    }

    /** The ends of the domain are the ends of the budget, and the budget leaves the diff column room. */
    @Test
    fun theDomainEndsMapToTheWidthEnds() {
        assertEquals(minBar, width(1390.0, 1390.0, 1410.0), 1e-3f)
        val top = width(1410.0, 1390.0, 1410.0)
        assertEquals(minOf(RateBarWidth.MAX_FRACTION * available, available - diffMin), top, 1e-3f)
        assertTrue("the bar left no room for the difference column", top <= available - diffMin)
    }

    /** Outside the domain is an outlier, and an outlier looks like an end — it does not overflow. */
    @Test
    fun aValueOutsideTheDomainClampsToAnEnd() {
        val top = width(1410.0, 1390.0, 1410.0)
        assertEquals(top, width(1600.0, 1390.0, 1410.0), 1e-3f)
        assertEquals(minBar, width(1000.0, 1390.0, 1410.0), 1e-3f)
    }

    /**
     * One quote, or several printing the same: there is no spread to place anything within.
     *
     * Pinning them to an end would read as "highest" or "lowest" when nothing is being compared.
     */
    @Test
    fun aDegenerateDomainSitsInTheMiddle() {
        val upper = minOf(RateBarWidth.MAX_FRACTION * available, available - diffMin)
        val middle = minBar + (upper - minBar) * 0.5f
        assertEquals(middle, width(1400.0, 1400.0, 1400.0), 1e-3f)
        // Two values that print the same are the same degenerate case.
        assertEquals(middle, width(1400.0, 1400.001, 1400.004), 1e-3f)
    }

    /** A row with no room to draw in falls back to the minimum rather than producing a negative bar. */
    @Test
    fun aRowNarrowerThanItsMinimumStillHasAWidth() {
        val squeezed = RateBarWidth.of(1400.0, 1390.0, 1410.0, available = 40f, minBar = 60f, diffMin = 50f)
        assertEquals(60f, squeezed, 1e-3f)
    }

    /** Nothing finite to draw: the shortest bar, not a NaN handed to the layout. */
    @Test
    fun nonFiniteRateOrDomainProducesTheMinimum() {
        assertEquals(minBar, width(Double.NaN, 1390.0, 1410.0), 1e-3f)
        assertEquals(minBar, width(1400.0, Double.NEGATIVE_INFINITY, 1410.0), 1e-3f)
    }

    /**
     * Non-finite *geometry* cannot come back as the fallback.
     *
     * The floor is built out of `minBar`, so returning it when `minBar` itself is the broken input
     * hands the layout a NaN or an infinite width — which is worse than drawing nothing, because it
     * spreads. Found by review after the first version checked all six inputs together and returned
     * a value it had already computed from three of them.
     */
    @Test
    fun nonFiniteGeometryProducesAFiniteWidth() {
        for (broken in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, RateBarWidth.of(1400.0, 1390.0, 1410.0, available, broken, diffMin), 0f)
            assertEquals(0f, RateBarWidth.of(1400.0, 1390.0, 1410.0, broken, minBar, diffMin), 0f)
            assertEquals(0f, RateBarWidth.of(1400.0, 1390.0, 1410.0, available, minBar, broken), 0f)
        }
    }

    @Test
    fun quantizedIsWhatTheRowPrints() {
        assertEquals("1400.00", RateDisplay.format(1400.004))
        assertEquals(1400.0, RateDisplay.quantized(1400.004), 0.0)
        assertEquals(1400.01, RateDisplay.quantized(1400.0051), 1e-9)
        // Non-finite is left alone: there is no printed form to parse back.
        assertTrue(RateDisplay.quantized(Double.NaN).isNaN())
    }

    /**
     * Every one-unit move is pointed at, not the 4% of them that survive floating point.
     *
     * Stepping one unit at a time from 1390.00 to 1410.00, a literal `abs(delta) >= 0.01` clears
     * only 80 of the 2000 moves — the subtraction usually lands on `0.009999999999990905`. The
     * fixture below is one that misses and one that does not, so a threshold written without slack
     * fails here rather than passing on the lucky half.
     */
    @Test
    fun everyOneUnitMoveIsCued() {
        assertTrue("1400.00→1400.01 should be one of the misses", Math.abs(1400.01 - 1400.0) < 0.01)
        assertTrue("1400.12→1400.13 should be one that clears", Math.abs(1400.13 - 1400.12) >= 0.01)
        val cue = RateCueTracker()
        assertNull("the first value has nothing to have moved from", cue.accept(1400.0))
        assertEquals(RateDirection.UP, cue.accept(1400.01))
        assertEquals(RateDirection.DOWN, cue.accept(1400.0))

        val walk = RateCueTracker()
        var value = 1390.0
        walk.accept(value)
        var missed = 0
        repeat(2000) {
            value = Math.round((value + 0.01) * 100.0) / 100.0
            if (walk.accept(value) != RateDirection.UP) missed++
        }
        assertEquals("one-unit moves went uncued", 0, missed)
    }

    /**
     * The slack is for representation error only — it does not lower D19's threshold.
     *
     * 0.002 is a real move by any decimal reckoning and stays below the line; so does a step that
     * happens to change the printed number, which is the case that separates this from a rule
     * written against the printed value instead of the value.
     */
    @Test
    fun aMoveBelowTheThresholdIsNotCued() {
        val cue = RateCueTracker()
        cue.accept(1400.0)
        assertNull(cue.accept(1400.002))

        val crossing = RateCueTracker()
        crossing.accept(1400.004)
        assertEquals("1400.00", RateDisplay.format(1400.004))
        assertEquals("1400.01", RateDisplay.format(1400.006))
        assertNull("0.002 is below the threshold however it prints", crossing.accept(1400.006))
    }

    /**
     * The baseline follows every value, so sub-threshold moves cannot accumulate into a cue.
     *
     * Four steps of 0.004 add up to more than a unit, and each one is measured against the step
     * before it rather than against where the row started.
     */
    @Test
    fun theBaselineFollowsEveryValueEvenWhenNothingIsShown() {
        val cue = RateCueTracker()
        cue.accept(1400.000)
        assertNull(cue.accept(1400.004))
        assertNull(cue.accept(1400.008))
        assertNull(cue.accept(1400.012))
        assertNull(cue.accept(1400.016))
    }

    /**
     * Reduce Motion cannot reach the cue's memory.
     *
     * iOS returns before updating its baseline while the setting is on, so the first cue after
     * turning it off is measured against a value of unknown age. Here the flag is only an argument
     * to [RateBarAnimation.animates] and no path through [RateCueTracker.accept] skips the update.
     * Whether the wiring calls `accept` at all is the wiring's contract, checked where it is wired.
     */
    @Test
    fun reduceMotionOnlyDecidesWhetherToAnimate() {
        assertTrue(RateBarAnimation.animates(reduceMotion = false, hasAppeared = true))
        assertTrue("Reduce Motion should snap", !RateBarAnimation.animates(reduceMotion = true, hasAppeared = true))
        assertTrue("the first composition should snap", !RateBarAnimation.animates(reduceMotion = false, hasAppeared = false))
        assertEquals(500, RateBarAnimation.DURATION_MILLIS)
    }

    /**
     * The values overload is the one the bank overload uses, so the tether tab cannot get a
     * different scale policy from the same numbers.
     */
    @Test
    fun bothScalePolicyEntryPointsAgree() {
        val values = listOf(1395.0, 1408.0, 1408.5, 1409.0, 1409.5, 1440.0)
        val rates = values.mapIndexed { index, value ->
            ExchangeRate(
                bank = "bank$index",
                currency = "usd-krw",
                rate = value,
                timestamp = Instant.parse("2026-09-08T00:00:00Z")
            )
        }
        assertEquals(ScalePolicyCalculator.computeValues(values), ScalePolicyCalculator.compute(rates))
        // …and it is the clipped range, not the raw one, that the bar is drawn against.
        val policy = ScalePolicyCalculator.computeValues(values)
        assertTrue("the outlier should have triggered robust mode", policy.isRobustActive)
        assertNotEquals(policy.rawRange, policy.displayRange)
    }
}
