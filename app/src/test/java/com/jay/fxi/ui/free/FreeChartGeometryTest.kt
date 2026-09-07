package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeChartGeometryTest {

    private val t0 = Instant.parse("2026-09-06T00:00:00Z")

    private fun point(hour: Int, rate: Double, high: Double? = null, low: Double? = null) =
        FreeGraphPoint(t0 + hour.hours, rate, high, low)

    private fun series(id: String, axis: String?, points: List<FreeGraphPoint>) =
        FreeGraphSeries(seriesId = id, points = points, label = id, axisGroup = axis)

    /**
     * Two series on one axis share one frame, so a line that barely moves is drawn as barely
     * moving. Scaling each series against its own extremes — the first version of this model —
     * made a 1-won drift and a 20-won swing both fill the chart top to bottom.
     */
    @Test
    fun seriesOnOneAxisShareOneFrame() {
        val flat = series("hana.usd", "krw", listOf(point(0, 1400.0), point(2, 1401.0)))
        val swingy = series("investing.usd", "krw", listOf(point(0, 1390.0), point(2, 1410.0)))
        val frame = FreeChartGeometry.frames(listOf(flat, swingy)).getValue("krw")

        assertEquals(1390.0, frame.low, 0.0)
        assertEquals(1410.0, frame.high, 0.0)

        val plotted = FreeChartGeometry.normalize(flat.points, frame)
        assertTrue("the near-flat series was stretched to fill the frame", plotted.all { it.y in 0.4f..0.6f })
        assertEquals(1f, FreeChartGeometry.normalize(swingy.points, frame).first().y, 1e-6f)
        assertEquals(0f, FreeChartGeometry.normalize(swingy.points, frame).last().y, 1e-6f)
    }

    /**
     * Value axes split; the time axis does not.
     *
     * Computing a whole domain per axis group also split x, so the same instant sat mid-chart on
     * one axis and hard left on the other and the two lines could not be read against each other —
     * which is the only reason to draw them together.
     */
    @Test
    fun theTimeAxisIsSharedEvenWhenTheValueAxesAreNot() {
        val krw = series("investing.usd", "krw", listOf(point(0, 1390.0), point(4, 1410.0)))
        val index = series("dxy", "index", listOf(point(2, 98.0), point(3, 100.0)))
        val frames = FreeChartGeometry.frames(listOf(krw, index))

        assertEquals(setOf("krw", "index"), frames.keys)
        // One clock for both.
        assertEquals(t0, frames.getValue("krw").start)
        assertEquals(t0, frames.getValue("index").start)
        assertEquals(t0 + 4.hours, frames.getValue("krw").end)
        assertEquals(t0 + 4.hours, frames.getValue("index").end)

        // The same instant therefore lands at the same x on either axis.
        val probe = listOf(point(2, 1400.0))
        assertEquals(
            FreeChartGeometry.normalize(probe, frames.getValue("krw")).single().x,
            FreeChartGeometry.normalize(listOf(point(2, 99.0)), frames.getValue("index")).single().x,
            1e-6f
        )

        // …while the value ranges stay apart: 1,400 KRW and an index of 99 on one scale is two
        // straight lines.
        assertEquals(1390.0, frames.getValue("krw").low, 0.0)
        assertEquals(98.0, frames.getValue("index").low, 0.0)
    }

    /**
     * A caption about one series must report that series' numbers. Reading them off the shared
     * frame told the user — and the screen reader — values this series never reached.
     */
    @Test
    fun aSeriesOwnExtremesAreNotTheFramesExtremes() {
        val flat = series("hana.usd", "krw", listOf(point(0, 1400.0), point(2, 1401.0)))
        val swingy = series("investing.usd", "krw", listOf(point(0, 1390.0), point(2, 1410.0)))
        val frame = FreeChartGeometry.frames(listOf(flat, swingy)).getValue("krw")

        val own = FreeChartGeometry.extremesOf(flat.points)!!
        assertEquals(1400.0, own.start, 0.0)
        assertEquals(1401.0, own.endInclusive, 0.0)
        assertNotEquals(frame.low, own.start, 0.0)
        assertNotEquals(frame.high, own.endInclusive, 0.0)
        assertNull(FreeChartGeometry.extremesOf(emptyList()))
    }

    /** Clipping to the closes would cut the wicks off the buckets the server actually sent. */
    @Test
    fun theFrameCoversTheHighsAndLows_notJustTheCloses() {
        val withWicks = series("investing.usd", "krw", listOf(point(0, 1400.0, high = 1420.0, low = 1385.0)))
        val frame = FreeChartGeometry.frames(listOf(withWicks)).getValue("krw")
        assertEquals(1385.0, frame.low, 0.0)
        assertEquals(1420.0, frame.high, 0.0)
        val own = FreeChartGeometry.extremesOf(withWicks.points)!!
        assertEquals(1385.0, own.start, 0.0)
        assertEquals(1420.0, own.endInclusive, 0.0)
    }

    /** One point, or a run of identical values, is drawn in the middle rather than dividing by zero. */
    @Test
    fun degenerateFramesLandAtTheMidpoint() {
        val single = series("investing.usd", "krw", listOf(point(1, 1400.0)))
        val frame = FreeChartGeometry.frames(listOf(single)).getValue("krw")
        assertEquals(listOf(FreeSnapshotChartPoint(0.5f, 0.5f)), FreeChartGeometry.normalize(single.points, frame))

        val flat = series("investing.usd", "krw", listOf(point(0, 1400.0), point(4, 1400.0)))
        val flatFrame = FreeChartGeometry.frames(listOf(flat)).getValue("krw")
        val plotted = FreeChartGeometry.normalize(flat.points, flatFrame)
        assertEquals(listOf(0f, 1f), plotted.map { it.x })
        assertTrue("a flat line was not level", plotted.all { it.y == 0.5f })
    }

    /** Screen y grows downward, so the largest value has to sit at the top. */
    @Test
    fun theHighestValueIsDrawnAtTheTop() {
        val s = series("investing.usd", "krw", listOf(point(0, 1380.0), point(1, 1420.0)))
        val plotted = FreeChartGeometry.normalize(s.points, FreeChartGeometry.frames(listOf(s)).getValue("krw"))
        assertEquals(1f, plotted.first().y, 1e-6f)
        assertEquals(0f, plotted.last().y, 1e-6f)
    }

    /** Out-of-order wire points must not draw a line that doubles back on itself. */
    @Test
    fun pointsAreDrawnInTimeOrderWhateverOrderTheyArrivedIn() {
        val shuffled = series("investing.usd", "krw", listOf(point(4, 1410.0), point(0, 1390.0), point(2, 1400.0)))
        val plotted = FreeChartGeometry.normalize(
            shuffled.points, FreeChartGeometry.frames(listOf(shuffled)).getValue("krw")
        )
        assertEquals(listOf(0f, 0.5f, 1f), plotted.map { it.x })
    }

    /** A series with no points takes no part in the frame and gets no entry of its own. */
    @Test
    fun nothingToDrawHasNoFrame() {
        assertTrue(FreeChartGeometry.frames(emptyList()).isEmpty())
        assertTrue(FreeChartGeometry.frames(listOf(series("investing.usd", "krw", emptyList()))).isEmpty())

        val real = series("investing.usd", "krw", listOf(point(0, 1400.0), point(2, 1402.0)))
        val empty = series("dxy", "index", emptyList())
        val frames = FreeChartGeometry.frames(listOf(real, empty))
        assertEquals(setOf("krw"), frames.keys)
        assertEquals(t0 + 2.hours, frames.getValue("krw").end)
    }

    @Test
    fun aBackwardsFrameIsRefusedRatherThanDrawn() {
        assertThrows(IllegalArgumentException::class.java) {
            FreeChartDomain(start = t0 + 1.hours, end = t0, low = 1.0, high = 2.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FreeChartDomain(start = t0, end = t0 + 1.hours, low = 2.0, high = 1.0)
        }
    }
}
