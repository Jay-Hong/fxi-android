package com.jay.fxi.domain.model

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScalePolicyCalculator 핵심 엣지 케이스 회귀 테스트.
 * iOS 구현과 수학적으로 동등해야 함.
 */
class ScalePolicyCalculatorTest {

    private val ts: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun rate(bank: String, value: Double): ExchangeRate = ExchangeRate(
        currency = "usd-krw",
        bank = bank,
        rate = value,
        timestamp = ts
    )

    // 1) Small-N: 가시 은행 수가 임계 미만 → raw 유지
    @Test
    fun `small-N returns raw fallback without computing IQR`() {
        val rates = listOf(
            rate("investing", 1395.0),
            rate("kb", 1408.0),
            rate("hana", 1420.0)
        )
        val policy = ScalePolicyCalculator.compute(rates)

        assertFalse(policy.isRobustActive)
        assertEquals(1395.0 to 1420.0, policy.rawRange)
        assertEquals(policy.rawRange, policy.displayRange)
    }

    // 2) Single high outlier: dispMax만 clip, dispMin은 rawMin 유지
    @Test
    fun `single high outlier clips only displayMax`() {
        val rates = listOf(
            rate("a", 1468.0),
            rate("b", 1469.0),
            rate("c", 1470.0),
            rate("d", 1470.0),
            rate("e", 1471.0),
            rate("f", 1472.0),
            rate("g", 1472.0),
            rate("h", 1473.0),
            rate("i", 1474.0),
            rate("j", 1500.0) // upper outlier
        )
        val policy = ScalePolicyCalculator.compute(rates)

        assertTrue("robust active", policy.isRobustActive)
        assertNotNull(policy.displayRange)
        val (dispMin, dispMax) = policy.displayRange!!
        assertEquals("dispMin stays at rawMin", 1468.0, dispMin, 1e-6)
        assertTrue("dispMax clipped below rawMax", dispMax < 1500.0)
    }

    // 3) Single low outlier: dispMin만 clip, dispMax는 rawMax 유지
    @Test
    fun `single low outlier clips only displayMin`() {
        val rates = listOf(
            rate("a", 1440.0), // lower outlier
            rate("b", 1468.0),
            rate("c", 1469.0),
            rate("d", 1470.0),
            rate("e", 1470.0),
            rate("f", 1471.0),
            rate("g", 1472.0),
            rate("h", 1472.0),
            rate("i", 1473.0),
            rate("j", 1474.0)
        )
        val policy = ScalePolicyCalculator.compute(rates)

        assertTrue("robust active", policy.isRobustActive)
        assertNotNull(policy.displayRange)
        val (dispMin, dispMax) = policy.displayRange!!
        assertTrue("dispMin clipped above rawMin", dispMin > 1440.0)
        assertEquals("dispMax stays at rawMax", 1474.0, dispMax, 1e-6)
    }

    // 4) IQR=0 엣지: 중앙 50%가 동일 호가 + outlier 존재 → median tolerance 경로
    @Test
    fun `IQR zero with outlier uses median tolerance path`() {
        val rates = listOf(
            rate("a", 1395.0), // outlier
            rate("b", 1408.0),
            rate("c", 1408.0),
            rate("d", 1408.0),
            rate("e", 1408.0)
        )
        val policy = ScalePolicyCalculator.compute(rates)

        assertTrue("robust active via zero-IQR fallback", policy.isRobustActive)
        assertNotNull(policy.displayRange)
        val (dispMin, dispMax) = policy.displayRange!!
        assertTrue("dispMin clipped above rawMin", dispMin > 1395.0)
        assertEquals("dispMax equals rawMax", 1408.0, dispMax, 1e-6)
    }

    // 5) ratio 임계 통과했지만 fence가 raw 바깥 → hasClippedRange=false → raw fallback
    @Test
    fun `ratio above threshold but fence outside raw returns raw fallback`() {
        // Q1=1001.0, Q3=1002.0, IQR=1.0, fullSpan=3.2, ratio=3.2 > 3.0
        // fenceLow=999.8 < rawMin=999.9, fenceHigh=1003.2 > rawMax=1003.1 → clip 없음
        val rates = listOf(
            rate("a", 999.9),
            rate("b", 1001.0),
            rate("c", 1001.5),
            rate("d", 1002.0),
            rate("e", 1003.1)
        )
        val policy = ScalePolicyCalculator.compute(rates)

        assertFalse("hasClippedRange=false → raw fallback", policy.isRobustActive)
        assertEquals(policy.rawRange, policy.displayRange)
    }
}
