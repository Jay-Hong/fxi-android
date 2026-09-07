package com.jay.fxi.ui.graph

import com.jay.fxi.data.free.FreeSnapshotSanitizer
import com.jay.fxi.data.remote.dto.FreeSnapshotResponse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the window is, and what may use which version of it.
 *
 * Two windows exist and conflating them is the defect this file is built around: the unpadded one
 * is where the data is — it positions the carry-in seed and the trailing hold, gates the dead-feed
 * check, and anchors zoom — while the padded one exists only so the outermost points are not drawn
 * on the border. Using the padded window to place data overshoots by the padding, which on 1년 is
 * three days.
 */
class GraphFrameTest {

    private val day0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun prepared(
        start: Instant? = day0,
        end: Instant? = day0 + 10.days,
        mode: String? = "fixed_start",
        points: List<FreeGraphPoint> = listOf(
            FreeGraphPoint(day0 + 1.days, 1400.0, null, null),
            FreeGraphPoint(day0 + 6.days, 1410.0, null, null)
        ),
        insufficient: Boolean = false,
        period: GraphPeriod = GraphPeriod.THREE_MONTHS
    ) = GraphPreparedBuilder.build(
        FreeGraph(
            bucketSize = null,
            series = listOf(FreeGraphSeries(
                seriesId = "investing.usd", points = points, label = "인베스팅",
                axisGroup = "krw", insufficientHistory = insufficient
            )),
            domainStartAt = start, domainEndAt = end, liveDomainMode = mode
        ),
        period
    )

    // --- resolving ------------------------------------------------------------------------------

    /** A snapshot's window is where the answer says it is; no clock is consulted. */
    @Test
    fun withNoClockTheServersWindowIsTakenAsGiven() {
        val frame = GraphFrame.resolve(prepared())!!
        assertEquals(day0, frame.start)
        assertEquals(day0 + 10.days, frame.end)
    }

    /**
     * The seam S4 will use. `rolling` slides the whole window and keeps its length; `fixed_start`
     * pins the left edge and lets the right one grow — and never shrinks it, so a clock behind the
     * server's cannot pull the window back over data already delivered.
     */
    @Test
    fun aLiveRightEdgeMovesTheWindowAccordingToItsMode() {
        val rolling = prepared(mode = "rolling", period = GraphPeriod.ONE_DAY)
        val later = day0 + 12.days
        val slid = GraphFrame.resolve(rolling, rightEdgeNow = later)!!
        assertEquals(later, slid.end)
        assertEquals(10.days, slid.length)

        val fixed = GraphFrame.resolve(prepared(), rightEdgeNow = later)!!
        assertEquals(day0, fixed.start)
        assertEquals(later, fixed.end)

        // A right edge behind the server's is ignored rather than obeyed.
        val stale = GraphFrame.resolve(prepared(), rightEdgeNow = day0 + 3.days)!!
        assertEquals(day0 + 10.days, stale.end)
    }

    /** No usable domain: fall back to where the data actually is. */
    @Test
    fun withoutADomainTheDataDecidesTheWindow() {
        val frame = GraphFrame.resolve(prepared(mode = "nonsense"))!!
        assertEquals(day0 + 1.days, frame.start)
        assertEquals(day0 + 6.days, frame.end)
    }

    /**
     * The live seam applies to the fallback too.
     *
     * Older servers send no domain, and a live graph on a fallback window would otherwise stop at
     * the last observation and never reach the present.
     */
    @Test
    fun theFallbackWindowAlsoFollowsALiveRightEdge() {
        val model = prepared(mode = "nonsense")
        val later = day0 + 9.days
        assertEquals(later, GraphFrame.resolve(model, rightEdgeNow = later)!!.end)
        // …and a clock behind the data does not pull it back.
        assertEquals(day0 + 6.days, GraphFrame.resolve(model, rightEdgeNow = day0 + 2.days)!!.end)
    }

    /** Neither a domain nor a span: there is nothing to draw and nothing to invent. */
    @Test
    fun withNeitherADomainNorASpanThereIsNoWindow() {
        assertNull(GraphFrame.resolve(prepared(
            mode = "nonsense",
            points = listOf(FreeGraphPoint(day0 + 1.days, 1400.0, null, null))
        )))
        assertNull(GraphFrame.resolve(prepared(mode = "nonsense", points = emptyList())))
    }

    @Test
    fun aWindowThatDoesNotMoveForwardsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { TimeFrame(day0 + 1.days, day0) }
        assertThrows(IllegalArgumentException::class.java) { TimeFrame(day0, day0) }
    }

    // --- padding --------------------------------------------------------------------------------

    /** Padding is added on the way to the screen and nowhere else. */
    @Test
    fun theRenderedWindowIsTheDataWindowPlusBreathingRoom() {
        val frame = TimeFrame(day0, day0 + 10.days)
        val rendered = GraphFrame.rendered(frame, GraphPeriod.ONE_YEAR)
        assertEquals(day0 - 1.days, rendered.start)
        assertEquals(day0 + 13.days, rendered.end)
        // The data window is untouched by having been rendered.
        assertEquals(day0, frame.start)
        assertEquals(day0 + 10.days, frame.end)
    }

    @Test
    fun eachPeriodHasItsOwnBreathingRoom() {
        assertEquals(10.minutes to 10.minutes, GraphFrame.paddingFor(GraphPeriod.ONE_DAY))
        assertEquals(1.hours to 1.hours, GraphFrame.paddingFor(GraphPeriod.ONE_WEEK))
        assertEquals(6.hours to 1.days, GraphFrame.paddingFor(GraphPeriod.THREE_MONTHS))
        assertEquals(1.days to 3.days, GraphFrame.paddingFor(GraphPeriod.ONE_YEAR))
    }

    // --- the trailing hold ------------------------------------------------------------------------

    /** A value published an hour ago has not changed; the line may say so out to the right edge. */
    @Test
    fun aRecentLastValueIsHeldOutToTheRightEdge() {
        val model = prepared()
        val frame = GraphFrame.resolve(model)!!
        val hold = GraphFrame.trailingHold(model.bySeries.getValue("investing.usd"), GraphPeriod.THREE_MONTHS, frame)
        assertEquals(LinePoint(day0 + 10.days, 1410.0), hold)
        assertEquals(
            listOf(day0 + 1.days, day0 + 5.days, day0 + 6.days, day0 + 10.days),
            GraphFrame.lineWithTrailing(model.bySeries.getValue("investing.usd"), GraphPeriod.THREE_MONTHS, frame)
                .map { it.ts }
        )
    }

    /**
     * A feed that stopped days ago is not "unchanged" — it is missing, and a flat line running to
     * the present would say the wrong thing.
     */
    @Test
    fun aDeadFeedIsNotHeld() {
        val model = prepared(
            end = day0 + 30.days,
            points = listOf(FreeGraphPoint(day0 + 1.days, 1400.0, null, null))
        )
        val frame = GraphFrame.resolve(model)!!
        assertNull(GraphFrame.trailingHold(model.bySeries.getValue("investing.usd"), GraphPeriod.THREE_MONTHS, frame))
    }

    /** Exactly at the budget is still honest; one moment past it is not. */
    @Test
    fun theTrailingBudgetIsAnInclusiveEdge() {
        fun holdWithEnd(end: Instant): LinePoint? {
            val model = prepared(end = end, points = listOf(FreeGraphPoint(day0, 1400.0, null, null)))
            return GraphFrame.trailingHold(
                model.bySeries.getValue("investing.usd"), GraphPeriod.THREE_MONTHS, GraphFrame.resolve(model)!!
            )
        }
        assertNotNull(holdWithEnd(day0 + 5.days))
        assertNull(holdWithEnd(day0 + 5.days + 1.minutes))
    }

    /** 1일 never holds: over a single day, anything worth holding is a gap the user should see. */
    @Test
    fun oneDayNeverHolds() {
        val model = prepared(mode = "rolling", period = GraphPeriod.ONE_DAY)
        val frame = GraphFrame.resolve(model)!!
        assertNull(GraphFrame.trailingHold(model.bySeries.getValue("investing.usd"), GraphPeriod.ONE_DAY, frame))
    }

    /** A series the server already flagged as not reaching back is not extended forwards either. */
    @Test
    fun anInsufficientSeriesIsNotHeld() {
        val model = prepared(insufficient = true)
        val frame = GraphFrame.resolve(model)!!
        assertNull(GraphFrame.trailingHold(model.bySeries.getValue("investing.usd"), GraphPeriod.THREE_MONTHS, frame))
    }

    // --- against the real payloads -----------------------------------------------------------------

    /**
     * Every checked-in contract fixture, decoded and sanitised exactly as the app does, then built.
     *
     * Synthetic inputs prove the rules; these prove the rules were written for the payloads the
     * server actually sends. The mode/period agreement in particular is a claim about the server
     * that would otherwise only be an assumption.
     */
    @Test
    fun everyContractFixtureBuildsAndResolves() {
        val json = NetworkModule.provideWireJson()
        val sanitizer = FreeSnapshotSanitizer()
        val tabs = listOf("usd", "jpy", "eur", "tether")
        var checked = 0
        tabs.forEach { tab ->
            GraphPeriod.entries.forEach { period ->
                val name = "/contracts/v2/free/$tab-${period.code}.json"
                val text = javaClass.getResource(name)?.readText()
                    ?: error("missing contract fixture $name")
                val snapshot = sanitizer.sanitize(
                    json.decodeFromString<FreeSnapshotResponse>(text), tab, period
                )
                val model = GraphPreparedBuilder.build(snapshot.graph, period)

                val domain = model.domain
                assertNotNull("$tab-${period.code} declared no usable domain", domain)
                domain!!
                // rolling belongs to 1일 and to nothing else — a claim about the server, checked here.
                assertEquals(
                    "$tab-${period.code} mode",
                    if (period == GraphPeriod.ONE_DAY) LiveDomainMode.ROLLING else LiveDomainMode.FIXED_START,
                    domain.mode
                )
                val frame = GraphFrame.resolve(model)!!
                assertEquals("$tab-${period.code} window", domain.start, frame.start)
                assertEquals(snapshot.asOf, frame.end)
                assertTrue("$tab-${period.code} padded window must contain the data window",
                    GraphFrame.rendered(frame, period).let { it.start < frame.start && it.end > frame.end })
                // 1일 carries no carry-in, so nothing may be seeded there.
                if (period == GraphPeriod.ONE_DAY) {
                    assertTrue("$tab-1d seeded a carry-in", model.bySeries.values.none { it.carryInApplied })
                }
                checked++
            }
        }
        assertEquals(16, checked)
    }
}
