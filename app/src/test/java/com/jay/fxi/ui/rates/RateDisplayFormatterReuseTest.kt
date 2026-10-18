package com.jay.fxi.ui.rates

import java.io.File
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S5 A2 U-A contract r2: reusing the rate formatter must not change a printed figure, nor the number it stands
 * for (`quantized`, which the bar width and the difference column are computed from).
 *
 * Design: R4c/S5/s5_4e/s10_next/A2_impl/design.r2.md (U-A). Oracle: the formatter as `RateDisplay` built it on every call
 * before this change — `Locale.KOREA`, no grouping, exactly two fraction digits — rebuilt fresh where state carried between
 * calls could matter. The reuse is per thread because `NumberFormat` is not thread-safe; A2/A3 observe that through the
 * module-internal `formatter()`, A4 proves `format` and `quantized` really go through it, and A5 keeps every other file off it.
 *
 * The implementation thread reads but does not edit this file.
 */
class RateDisplayFormatterReuseTest {

    private fun fresh(): NumberFormat = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    private val oracle: NumberFormat = fresh()

    private fun oracleQuantized(value: Double, with: NumberFormat = oracle): Double {
        if (!value.isFinite()) return value
        return with.parse(with.format(value))?.toDouble() ?: value
    }

    /** A dense sweep of quote-sized values, the half-cent boundaries on both sides, signs and very large magnitudes. */
    private fun values(): Sequence<Double> = sequence {
        var v = 0.0
        while (v <= 2000.0) {
            yield(v)
            v += 0.0013
        }
        for (base in listOf(0.005, 1.005, 99.995, 999.995, 1000.005, 1399.995, 1400.005, 1400.015)) {
            yield(base)
            yield(base + 1e-9)
            yield(base - 1e-9)
        }
        yieldAll(listOf(-0.0, 0.0, -0.004, -0.005, -1.235, -1399.995, 12345.678, 1234567.891, 1e9 + 0.125, Double.MAX_VALUE))
    }

    @Test fun A1_reuseKeepsEveryFigureAndItsValue() {
        var checked = 0
        for (v in values()) {
            assertEquals("format($v)", oracle.format(v), RateDisplay.format(v))
            assertEquals("quantized($v) bits", oracleQuantized(v).toRawBits(), RateDisplay.quantized(v).toRawBits())
            checked++
        }
        assertTrue("the sweep reaches past 1000, where grouping would show", checked > 1_000_000)
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("format($v)", oracle.format(v), RateDisplay.format(v))
            val q = RateDisplay.quantized(v)
            assertTrue("quantized($v) is returned unchanged", (v.isNaN() && q.isNaN()) || q == v)
        }
    }

    /** No state carries between calls on the reused instance, even after it has been asked to parse garbage. */
    @Test fun A1b_aReusedFormatterAnswersAsAFreshOneEvenAfterGarbage() {
        val junk = listOf("abc", "1,234.5", "1E5", "∞", "-", "", "NaN", "-0", "١٢٣", "  12.3  ", "12.345.6")
        var i = 0
        var v = 0.0
        while (v <= 2000.0) {
            if (i % 97 == 0) runCatching { RateDisplay.formatter().parse(junk[(i / 97) % junk.size]) }
            val f = fresh()
            assertEquals("format($v)", f.format(v), RateDisplay.format(v))
            assertEquals("quantized($v) bits", oracleQuantized(v, f).toRawBits(), RateDisplay.quantized(v).toRawBits())
            v += 0.0371
            i++
        }
        assertTrue("garbage was fed between checks", i > 50_000)
    }

    /** Exact half-cent ties: HALF_EVEN in the printed figure, and `quantized` is that printed figure. */
    @Test fun A1c_exactHalfCentTiesRoundHalfEvenInBothHalves() {
        val ties = mapOf(
            0.125 to "0.12", 0.375 to "0.38", 921.625 to "921.62", 1400.125 to "1400.12",
            1400.375 to "1400.38", 1400.625 to "1400.62", 1400.875 to "1400.88", -1400.375 to "-1400.38"
        )
        for ((v, printed) in ties) {
            assertEquals("oracle($v)", printed, oracle.format(v))
            assertEquals("format($v)", printed, RateDisplay.format(v))
            assertEquals("quantized($v) is the printed figure", printed.toDouble().toRawBits(), RateDisplay.quantized(v).toRawBits())
        }
    }

    @Test fun A1d_quantizedKeepsTheSignOfZero() {
        assertEquals("-0.00", RateDisplay.format(-0.004))
        for (v in listOf(-0.0, -0.004, 0.0, 0.004)) {
            assertEquals("quantized($v) bits", oracleQuantized(v).toRawBits(), RateDisplay.quantized(v).toRawBits())
        }
    }

    @Test fun A2_oneThreadKeepsOneFormatter() {
        val first = RateDisplay.formatter()
        RateDisplay.format(1400.0)
        RateDisplay.quantized(1400.004)
        assertSame(first, RateDisplay.formatter())
    }

    @Test fun A3_eachThreadHasItsOwnFormatter() {
        val here = RateDisplay.formatter()
        val there = AtomicReference<NumberFormat>()
        val thereAgain = AtomicReference<NumberFormat>()
        val worker = Thread {
            there.set(RateDisplay.formatter())
            RateDisplay.format(1.0)
            thereAgain.set(RateDisplay.formatter())
        }
        worker.start()
        worker.join(30_000)
        assertFalse("the worker finished", worker.isAlive)
        assertNotNull("the worker ran", there.get())
        assertSame("one thread keeps one formatter", there.get(), thereAgain.get())
        assertNotSame("threads never share a formatter", here, there.get())
    }

    /** `format` prints, and `quantized` both prints and parses, with this thread's formatter — not a private copy. */
    @Test fun A4_formatAndQuantizedUseTheThreadFormatter() {
        val f = RateDisplay.formatter()
        val savedMode = f.roundingMode
        val savedIntegerOnly = f.isParseIntegerOnly
        try {
            f.roundingMode = RoundingMode.DOWN
            assertEquals("format prints with the thread's formatter", "1400.00", RateDisplay.format(1400.009))
            assertEquals("quantized prints with it", 1400.0, RateDisplay.quantized(1400.009), 0.0)
            f.roundingMode = savedMode
            f.isParseIntegerOnly = true
            assertEquals("quantized parses with it", 1400.0, RateDisplay.quantized(1400.25), 0.0)
        } finally {
            f.roundingMode = savedMode
            f.isParseIntegerOnly = savedIntegerOnly
        }
        assertEquals("restored", "1400.01", RateDisplay.format(1400.009))
        assertEquals("restored", 1400.25, RateDisplay.quantized(1400.25), 0.0)
    }

    /** The formatter is built in one place — the per-thread initializer — and no other file touches it. */
    @Test fun A5_theFormatterIsBuiltOnceAndOnlyRateDisplayTouchesIt() {
        val own = File("src/main/java/com/jay/fxi/ui/rates/RateDisplay.kt")
        assertTrue("the scan found the source tree", own.isFile)
        val text = own.readText()
        assertEquals("one construction site", 1, Regex("""getNumberInstance\s*\(""").findAll(text).count())
        assertTrue("the construction sits in a per-thread holder", text.contains("ThreadLocal"))
        val pattern = Regex("""RateDisplay\s*(\.|::)\s*formatter\b""")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "RateDisplay.kt" }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /** Concurrent callers on different threads each get exactly their own figures. */
    @Test fun A6_concurrentCallersEachGetTheirOwnFigures() {
        val failures = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)
        val threads = (0 until 4).map { t ->
            Thread {
                val mine = fresh()
                start.await()
                var v = 1000.0 + t * 0.37
                repeat(50_000) {
                    val expected = mine.format(v)
                    val got = RateDisplay.format(v)
                    if (expected != got) failures += "t$t $v: $expected != $got"
                    v += 0.0041
                }
            }.apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    failures += "t$t threw ${error.javaClass.name}: ${error.message}"
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(60_000) }
        assertTrue("all workers finished", threads.none { it.isAlive })
        assertEquals(emptyList<String>(), failures.take(5))
    }
}
