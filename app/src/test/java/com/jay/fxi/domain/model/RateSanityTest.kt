package com.jay.fxi.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one test a price has to pass, and the edges of it.
 *
 * Two surfaces ask this — the free snapshot sanitizer and the topic boundary — so the edges are
 * worth naming once: zero is not a price, the ceiling is exclusive, and non-finite is out even
 * though a `Double` will happily hold it.
 */
class RateSanityTest {

    @Test
    fun aPriceIsFinitePositiveAndUnderTheCeiling() {
        listOf(0.01, 1.0, 1400.5, RateSanity.UPPER_BOUND - 1)
            .forEach { assertTrue("$it 가 거부됐다", RateSanity.isPlausible(it)) }
    }

    /** Zero and the ceiling are both outside — the bounds are exclusive on both ends. */
    @Test
    fun theBoundsAreExclusive() {
        assertFalse(RateSanity.isPlausible(0.0))
        assertFalse(RateSanity.isPlausible(RateSanity.UPPER_BOUND))
        assertTrue(RateSanity.isPlausible(Double.MIN_VALUE))
    }

    @Test
    fun nothingNegativeOrNonFinitePasses() {
        listOf(-0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { assertFalse("$it 가 통과했다", RateSanity.isPlausible(it)) }
    }
}
