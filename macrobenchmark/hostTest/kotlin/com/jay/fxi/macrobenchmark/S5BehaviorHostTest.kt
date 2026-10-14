package com.jay.fxi.macrobenchmark

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-4 E slice 5: the behavior record's slot and failure rules, and how a poll feeds it, on the host
 * (macrobenchmark/hostTest/run_host_tests.py compiles S5Behavior.kt and S5Poll.kt themselves with these tests). Records a case
 * writes to s5.host.out are checked by the Python gate afterwards:
 * `<case>.success.json` with validate_behavior, `<case>.failure.json` with check_failure_record.
 */
class S5BehaviorHostTest {
    private val journeys = S5Behavior.Journey.values()
    private val out = System.getProperty("s5.host.out")?.let { File(it) }

    private fun state(e: Map<String, Any>) =
        S5Behavior.State(e["tab"] as String? ?: "USD", e["period"] as String? ?: "1d", e["ready"] as Boolean? ?: true,
            e["seriesShown"] as Int? ?: 4, e["scroll"] as String? ?: "na")

    /** A measure that observes exactly what the journey expects. */
    private fun observeAll(o: S5Behavior.Observation) {
        o.before = state(o.journey.before)
        o.journey.lookups.forEach { o.lookup(it, 1) }
        o.actionDone = true
        o.journey.during?.let { o.during = it }
        o.after = state(o.journey.after)
    }

    private fun pair(t: S5Behavior.Tracker, iteration: Int?) {
        t.beginSetup(iteration)
        observeAll(t.beginMeasure(iteration))
        t.endMeasure()
    }

    /** Three warmups, the WARM pass and measured 0-4, each observed. */
    private fun fullRun(t: S5Behavior.Tracker) {
        repeat(4) { pair(t, null) }
        (0..4).forEach { pair(t, it) }
    }

    private fun write(case: String, kind: String, record: Map<String, Any?>) {
        out ?: return
        out.mkdirs()
        File(out, "$case.$kind.json").writeText(json(record))
    }

    @Test
    fun `a full run fills eight slots in order and its record is the gate's`() {
        for (j in journeys) {
            val t = S5Behavior.Tracker(j)
            fullRun(t)
            assertTrue(j.id, t.complete)
            val record = S5Behavior.successRecord("run-${j.id}", t)
            @Suppress("UNCHECKED_CAST") val iterations = record["iterations"] as List<Map<String, Any?>>
            assertEquals(List(3) { "warmup" } + List(5) { "measured" }, iterations.map { it["phase"] })
            assertEquals(listOf(0, 1, 2, 0, 1, 2, 3, 4), iterations.map { it["index"] })
            assertEquals(j.during != null, iterations.all { "during" in it })
            write("full-${j.id}", "success", record)
        }
    }

    @Test
    fun `the WARM pass is no slot - the fourth null pair observes into nothing kept`() {
        val t = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY)
        repeat(3) { pair(t, null) }
        t.beginSetup(null)
        val warm = t.beginMeasure(null)
        warm.before = S5Behavior.State("JPY", "1y", false, 0, "na")
        assertNull("measured 0 is not opened by the WARM pass", t.observation(3))
        t.endMeasure()
        (0..4).forEach { pair(t, it) }
        assertEquals("USD", t.observation(3)!!.before!!.tab)
    }

    @Test
    fun `any other order of calls is refused`() {
        val cases = mapOf<String, (S5Behavior.Tracker) -> Unit>(
            "dry run: one null pair then measured 0" to { t -> pair(t, null); t.beginSetup(0) },
            "two null pairs then measured 0" to { t -> repeat(2) { pair(t, null) }; t.beginSetup(0) },
            "a fifth null pair" to { t -> repeat(4) { pair(t, null) }; t.beginSetup(null) },
            "measured 0 then 2" to { t -> repeat(4) { pair(t, null) }; pair(t, 0); t.beginSetup(2) },
            "measured 5" to { t -> repeat(4) { pair(t, null) }; (0..4).forEach { pair(t, it) }; t.beginSetup(5) },
            "a measure with another iteration" to { t -> repeat(4) { pair(t, null) }; t.beginSetup(0); t.beginMeasure(null) },
            "a setup while a slot is open" to { t -> t.beginSetup(null); t.beginSetup(null) },
            "a measure without its setup" to { t -> t.beginMeasure(null) }
        )
        for ((name, run) in cases) {
            val t = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY)
            val error = assertThrows(name, IllegalStateException::class.java) { run(t) }
            assertTrue(name, error.message!!.startsWith("S5 behavior slots:"))
            // What the outer catch does next: no failure, no record, and it stays so.
            assertNull(name, t.fail(error))
            assertNull(name, t.fail(RuntimeException("again")))
            assertNull(name, t.failure)
            assertFalse(name, t.complete)
            assertThrows(name, IllegalStateException::class.java) { S5Behavior.successRecord("r", t) }
            assertThrows(name, IllegalStateException::class.java) { S5Behavior.failureRecord("r", t) }
        }
    }

    @Test
    fun `an incomplete observation cannot close its slot`() {
        val t = S5Behavior.Tracker(S5Behavior.Journey.SCROLL)
        t.beginSetup(null)
        val o = t.beginMeasure(null)
        observeAll(o)
        o.during = null
        assertThrows(IllegalStateException::class.java) { t.endMeasure() }
    }

    @Test
    fun `a compile warmup failure fails its own slot`() {
        for (slot in 0..2) for (inMeasure in listOf(false, true)) {
            val t = S5Behavior.Tracker(S5Behavior.Journey.PERIOD_SWITCH)
            repeat(slot) { pair(t, null) }
            t.beginSetup(null)
            if (inMeasure) {
                t.beginMeasure(null).before = state(S5Behavior.Journey.PERIOD_SWITCH.before)
                t.stage(S5Behavior.Stage.LOOKUP)
            }
            val f = t.fail(AssertionError("boom"))!!
            assertEquals(slot, f.slot)
            assertEquals(if (inMeasure) "lookup" else "setup", f.stage.wire)
            assertEquals(S5Behavior.OPERATION, f.kind)
            write("warmup$slot-${if (inMeasure) "lookup" else "setup"}", "failure", S5Behavior.failureRecord("w$slot", t))
        }
    }

    @Test
    fun `a WARM pass failure is measured 0's setup failure with nothing observed`() {
        for (stage in listOf(null, S5Behavior.Stage.ACTION)) {
            val t = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY)
            repeat(3) { pair(t, null) }
            t.beginSetup(null)
            if (stage != null) {
                val warm = t.beginMeasure(null)
                warm.before = state(S5Behavior.Journey.TAB_ENTRY.before)
                t.stage(stage)
            }
            val f = t.fail(AssertionError("premium USD tab"))!!
            assertEquals(3, f.slot)
            assertEquals("setup", f.stage.wire)
            assertEquals(S5Behavior.OPERATION, f.kind)
            assertTrue(f.reason, f.reason.startsWith("WARM pass ${stage?.wire ?: "setup"}: premium USD tab"))
            val record = S5Behavior.failureRecord("warm", t)
            @Suppress("UNCHECKED_CAST") val failed = (record["iterations"] as List<Map<String, Any?>>)[3]
            assertEquals(listOf(null, null, null, null), listOf(failed["lookups"], failed["before"], failed["after"], failed["action"]))
            write("warm-${stage?.wire ?: "setup"}", "failure", record)
        }
    }

    @Test
    fun `a measured setup or measure failure fails that measured slot`() {
        for (i in 0..4) {
            val t = S5Behavior.Tracker(S5Behavior.Journey.SCROLL)
            repeat(4) { pair(t, null) }
            (0 until i).forEach { pair(t, it) }
            t.beginSetup(i)
            assertEquals(3 + i, t.fail(RuntimeException("setup"))!!.slot)
            write("measured$i-setup", "failure", S5Behavior.failureRecord("m$i", t))
        }
    }

    @Test
    fun `outside any slot - before the first setup, after the compile, after the last slot`() {
        assertEquals(0, S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY).fail(RuntimeException("kill"))!!.slot)
        val compiled = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY).also { t -> repeat(3) { pair(t, null) } }
        assertEquals(3, compiled.fail(RuntimeException("compile"))!!.slot)
        val done = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY).also { fullRun(it) }
        assertNull("no slot is left to fail", done.fail(RuntimeException("metrics")))
    }

    @Test
    fun `the kind is a mismatch only when the stage's own record shows it`() {
        fun failAt(j: S5Behavior.Journey, stage: S5Behavior.Stage, fill: (S5Behavior.Observation) -> Unit): S5Behavior.Tracker {
            val t = S5Behavior.Tracker(j)
            repeat(4) { pair(t, null) }
            t.beginSetup(0)
            val o = t.beginMeasure(0)
            fill(o)
            t.stage(stage)
            t.fail(AssertionError("assertion"))
            return t
        }
        val e = S5Behavior.Journey.TAB_ENTRY
        val cases = listOf(
            Triple("lookup-0", failAt(e, S5Behavior.Stage.LOOKUP) { it.before = state(e.before); it.lookup("premium_tab_TETHER", 0) }, S5Behavior.MISMATCH),
            Triple("lookup-2", failAt(e, S5Behavior.Stage.LOOKUP) { it.before = state(e.before); it.lookup("premium_tab_TETHER", 2) }, S5Behavior.MISMATCH),
            Triple("lookup-error", failAt(e, S5Behavior.Stage.LOOKUP) { it.before = state(e.before) }, S5Behavior.OPERATION),
            Triple("action", failAt(e, S5Behavior.Stage.ACTION) { it.before = state(e.before); it.lookup("premium_tab_TETHER", 1) }, S5Behavior.OPERATION),
            Triple("after-count", failAt(e, S5Behavior.Stage.AFTER) {
                it.before = state(e.before); it.lookup("premium_tab_TETHER", 1); it.actionDone = true
                it.lookup(S5Behavior.CHART, 1); it.after = state(e.after).copy(seriesShown = 3)
            }, S5Behavior.MISMATCH),
            Triple("after-same-fields", failAt(e, S5Behavior.Stage.AFTER) {
                it.before = state(e.before); it.lookup("premium_tab_TETHER", 1); it.actionDone = true
                it.lookup(S5Behavior.CHART, 1); it.after = state(e.after)
            }, S5Behavior.OPERATION),
            Triple("during-top", failAt(S5Behavior.Journey.SCROLL, S5Behavior.Stage.DURING) {
                it.before = state(S5Behavior.Journey.SCROLL.before); it.lookup(S5Behavior.PAGE_SCROLL, 1); it.actionDone = true; it.during = "top"
            }, S5Behavior.MISMATCH)
        )
        for ((name, t, kind) in cases) {
            assertEquals(name, kind, t.failure!!.kind)
            val record = S5Behavior.failureRecord("k-$name", t)
            @Suppress("UNCHECKED_CAST") val failed = (record["iterations"] as List<Map<String, Any?>>)[3]
            assertEquals(name, if (name == "action") "failed" else if (t.observation(3)!!.actionDone) "completed" else null, failed["action"])
            write("kind-$name", "failure", record)
        }
    }

    @Test
    fun `the run id and the run it needs`() {
        fun args(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get
        assertNull(S5Behavior.runId(args()))
        assertEquals("attempt-1.a_b", S5Behavior.runId(args("s5.runId" to "attempt-1.a_b")))
        for (bad in listOf("", "Upper", "-lead", "a".repeat(65), "a b", "a\n")) {
            assertThrows(bad, IllegalStateException::class.java) { S5Behavior.runId(args("s5.runId" to bad)) }
        }
        assertEquals("r", S5Behavior.runId(args("s5.runId" to "r", "androidx.benchmark.profiling.mode" to " None ")))
        for (unfit in listOf("androidx.benchmark.dryRunMode.enable" to "true", "androidx.benchmark.compilation.enabled" to "false",
                "androidx.benchmark.profiling.mode" to "MethodTracing", "androidx.benchmark.profiling.mode" to "StackSampling")) {
            assertThrows(unfit.toString(), IllegalStateException::class.java) { S5Behavior.runId(args("s5.runId" to "r", unfit)) }
        }
    }

    @Test
    fun `the expectations are the gate's`() {
        val gate = System.getProperty("s5.gate.expected") ?: return assertFalse("s5.gate.expected not given", true)
        assertEquals(gate.trim(), json(journeys.associate { j ->
            j.id to sortedMapOf<String, Any?>("lookups" to j.lookups.sorted(), "before" to j.before.toSortedMap(),
                "after" to j.after.toSortedMap(), "during" to j.during?.let { mapOf("scroll" to it) })
        }.toSortedMap()))
    }

    /**
     * Scripted screen reads for S5Poll: poll n (counted from 0, started by its tabs read) answers from polls[n]; a Throwable
     * value is thrown at that read. Every read is logged as "<poll>:<field>", so a test sees what was read and how often.
     * [unscripted] is for a wait's expired or pause callback: it fails the test once every scripted poll has been taken, so a
     * wait that keeps polling (for instance one that swallowed a read's error) ends instead of looping.
     */
    private class Screen(private val polls: List<Map<String, Any?>>) {
        private var poll = -1
        val log = mutableListOf<String>()

        fun unscripted() = check(poll + 1 < polls.size) { "the wait polled past the ${polls.size} scripted polls" }

        private fun take(field: String): Any? {
            log += "$poll:$field"
            val value = polls[poll][field]
            if (value is Throwable) throw value
            return value
        }

        @Suppress("UNCHECKED_CAST")
        val reads = S5Poll.Reads<String>(
            tabs = { poll++; take("tabs") as List<Pair<String, Boolean>>? },
            periods = { take("periods") as List<Pair<String, Boolean>>? },
            charts = { take("charts") as List<String>? },
            description = { chart -> assertEquals("the one chart found", "chart", chart); take("description") as String? },
            labelsOf = { text -> if (text.startsWith("series ")) text.removePrefix("series ").split(",") else null }
        )
    }

    private val tether = listOf("premium_tab_USD" to false, "premium_tab_TETHER" to true)
    private val oneDay = listOf("period_tab:1d" to true, "period_tab:1w" to false)
    private val drawn = mapOf("tabs" to tether, "periods" to oneDay, "charts" to listOf("chart"), "description" to "series a,b,c")
    private val reads = listOf("tabs", "periods", "charts", "description")
    private fun log(poll: Int, fields: List<String>) = fields.map { "$poll:$it" }
    private fun matchesDrawn(shot: S5Poll.Snapshot) =
        shot.labels == listOf("a", "b", "c") && shot.checked("premium_tab_TETHER") && shot.selected("period_tab:1d")

    /** Measured 0 of tab entry, opened up to its after stage as the journey runs it. */
    private fun atTabEntryAfter(): Pair<S5Behavior.Tracker, S5Behavior.Observation> {
        val t = S5Behavior.Tracker(S5Behavior.Journey.TAB_ENTRY)
        repeat(4) { pair(t, null) }
        t.beginSetup(0)
        val o = t.beginMeasure(0)
        o.before = state(o.journey.before)
        o.lookup("premium_tab_TETHER", 1)
        o.actionDone = true
        t.stage(S5Behavior.Stage.AFTER)
        return t to o
    }

    @Test
    fun `a read that throws keeps what its poll read, reads nothing more, and the same error goes on`() {
        for ((at, field) in reads.withIndex()) {
            val boom = NullPointerException("getUiAutomation at $field")
            val screen = Screen(listOf(drawn + (field to boom)))
            val kept = mutableListOf<S5Poll.Snapshot>()
            val thrown = assertThrows(field, NullPointerException::class.java) { S5Poll.poll(screen.reads) { kept += it } }
            assertSame(field, boom, thrown)
            assertEquals(field, log(0, reads.take(at + 1)), screen.log)
            val shot = kept.single()
            assertEquals(field, if (at > 0) tether else null, shot.tabs)
            assertEquals(field, if (at > 1) oneDay else null, shot.periods)
            assertEquals(field, if (at > 2) 1 else null, shot.charts)
            assertEquals(field, S5Behavior.State(if (at > 0) "TETHER" else null, if (at > 1) "1d" else null, null, null, "na"), shot.state("na"))
        }
    }

    @Test
    fun `a poll that fails replaces the earlier poll in the after record, and the gate takes that record`() {
        for ((case, tabs) in listOf("poll-after-partial" to tether, "poll-after-partial-mismatch" to listOf("premium_tab_USD" to true))) {
            val (t, o) = atTabEntryAfter()
            val boom = NullPointerException("periods")
            val screen = Screen(listOf(drawn + ("description" to "series a"), mapOf("tabs" to tabs, "periods" to boom)))
            val thrown = assertThrows(case, NullPointerException::class.java) {
                S5Poll.await(screen.reads, { S5Poll.keepAfter(o, it, "na") }, ::matchesDrawn, expired = { false }, pause = screen::unscripted)
            }
            assertSame(case, boom, thrown)
            assertEquals(case, log(0, reads) + log(1, listOf("tabs", "periods")), screen.log)
            assertEquals(case, S5Behavior.State(tabs.single { it.second }.first.removePrefix("premium_tab_"), null, null, null, "na"), o.after)
            assertNull("$case: the chart count of poll 0 is not kept", o.lookups[S5Behavior.CHART])
            val failure = t.fail(thrown)!!
            assertEquals(case, listOf(3, S5Behavior.Stage.AFTER), listOf(failure.slot, failure.stage))
            assertEquals(case, if (case.endsWith("mismatch")) S5Behavior.MISMATCH else S5Behavior.OPERATION, failure.kind)
            write(case, "failure", S5Behavior.failureRecord(case, t))
        }
    }

    @Test
    fun `success and timeout keep the snapshot they compared and read nothing after it`() {
        val wrong = drawn + ("description" to "series a")
        val changed = mapOf("tabs" to listOf("premium_tab_USD" to true), "periods" to oneDay, "charts" to emptyList<String>())
        val success = Screen(listOf(wrong, drawn, changed))
        val (_, o) = atTabEntryAfter()
        val kept = mutableListOf<S5Poll.Snapshot>()
        val (matched, shot) = S5Poll.await(success.reads, { kept += it; S5Poll.keepAfter(o, it, "na") }, ::matchesDrawn,
            expired = { false }, pause = success::unscripted)
        assertTrue(matched)
        assertSame(kept.last(), shot)
        assertEquals("the screen that changed after the match is never read", log(0, reads) + log(1, reads), success.log)
        assertEquals(S5Behavior.State("TETHER", "1d", true, 3, "na"), o.after)
        assertEquals(1, o.lookups[S5Behavior.CHART])

        var checks = 0
        var pauses = 0
        val timeout = Screen(listOf(wrong, wrong, changed, drawn))
        val keptOnTimeout = mutableListOf<S5Poll.Snapshot>()
        val (late, last) = S5Poll.await(timeout.reads, { keptOnTimeout += it }, ::matchesDrawn, expired = { ++checks == 3 },
            pause = { timeout.unscripted(); pauses++ })
        assertFalse(late)
        assertSame(keptOnTimeout.last(), last)
        assertEquals(listOf(3, 2, 3), listOf(checks, pauses, keptOnTimeout.size))
        assertEquals(S5Behavior.State("USD", "1d", false, null, "na"), last.state("na"))
        assertEquals(log(0, reads) + log(1, reads) + log(2, listOf("tabs", "periods", "charts")), timeout.log)

        val once = Screen(listOf(wrong, drawn))
        val (first, only) = S5Poll.await(once.reads, {}, ::matchesDrawn, expired = { true }, pause = { error("no pause") })
        assertFalse(first)
        assertEquals("one poll even when the time is already up", listOf("a"), only.labels)
        assertEquals(log(0, reads), once.log)
    }

    @Test
    fun `the chart is ready only when its one description was read, and the description is asked only of one chart`() {
        fun shot(vararg change: Pair<String, Any?>) = Screen(listOf(drawn + change)).let { s -> S5Poll.poll(s.reads) {} to s.log }
        val cases = listOf(
            Triple(arrayOf<Pair<String, Any?>>("charts" to listOf("chart", "chart")), S5Behavior.State("TETHER", "1d", false, null, "na"), 2),
            Triple(arrayOf<Pair<String, Any?>>("charts" to emptyList<String>()), S5Behavior.State("TETHER", "1d", false, null, "na"), 0),
            Triple(arrayOf<Pair<String, Any?>>("charts" to null), S5Behavior.State("TETHER", "1d", null, null, "na"), null),
            Triple(arrayOf<Pair<String, Any?>>("description" to null), S5Behavior.State("TETHER", "1d", null, null, "na"), 1),
            Triple(arrayOf<Pair<String, Any?>>("description" to "other text"), S5Behavior.State("TETHER", "1d", false, null, "na"), 1),
            Triple(arrayOf<Pair<String, Any?>>("tabs" to null, "periods" to listOf("period_tab:1d" to true, "period_tab:1w" to true)),
                S5Behavior.State(null, null, true, 3, "na"), 1)
        )
        for ((change, expected, charts) in cases) {
            val (s, log) = shot(*change)
            val name = change.joinToString { it.first }
            assertEquals(name, expected, s.state("na"))
            assertEquals(name, charts, s.charts)
            assertEquals("$name: the description is read only of one chart", charts == 1, log.contains("0:description"))
        }
    }

    @Test
    fun `the before stage keeps a partial poll and reads the scroll position last`() {
        val order = mutableListOf<String>()
        val full = Screen(listOf(drawn))
        val o = S5Behavior.Observation(S5Behavior.Journey.SCROLL)
        S5Poll.before(o, full.reads) { order += full.log; "top" }
        assertEquals("the scroll position is read after the poll", log(0, reads), order)
        assertEquals(S5Behavior.State("TETHER", "1d", true, 3, "top"), o.before)

        val boom = NullPointerException("periods")
        val partial = S5Behavior.Observation(S5Behavior.Journey.SCROLL)
        var scrolled = 0
        assertSame(boom, assertThrows(NullPointerException::class.java) {
            S5Poll.before(partial, Screen(listOf(drawn + ("periods" to boom))).reads) { scrolled++; "top" }
        })
        assertEquals(0, scrolled)
        assertEquals(S5Behavior.State("TETHER", null, null, null, null), partial.before)

        val anchorGone = IllegalStateException("anchor")
        val noScroll = S5Behavior.Observation(S5Behavior.Journey.SCROLL)
        assertSame(anchorGone, assertThrows(IllegalStateException::class.java) {
            S5Poll.before(noScroll, Screen(listOf(drawn)).reads) { throw anchorGone }
        })
        assertEquals(S5Behavior.State("TETHER", "1d", true, 3, null), noScroll.before)
    }

    @Test
    fun `the scroll after-position reads the anchor once and asserts on that same read`() {
        for ((pageTop, position) in listOf(120 to "top", 100 to "moved")) {
            val anchor = mutableListOf(120, 300)
            val kept = mutableListOf<String>()
            assertEquals(120, S5Poll.afterScroll({ anchor.removeAt(0) }, pageTop) { kept += it })
            assertEquals(listOf(position), kept)
            assertEquals("one anchor read", listOf(300), anchor)
        }
        val gone = IllegalStateException("anchor")
        val kept = mutableListOf<String>()
        assertSame(gone, assertThrows(IllegalStateException::class.java) { S5Poll.afterScroll({ throw gone }, 120) { kept += it } })
        assertTrue(kept.isEmpty())
    }

    /** Enough JSON for these records: maps keep their order, strings are escaped. */
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k) + ":" + json(v) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        is String -> "\"" + value.flatMap { c ->
            when (c) {
                '"' -> "\\\"".toList(); '\\' -> "\\\\".toList(); '\n' -> "\\n".toList()
                else -> if (c < ' ') "\\u%04x".format(c.code).toList() else listOf(c)
            }
        }.joinToString("") + "\""
        is Int, is Boolean -> value.toString()
        else -> error("unexpected ${value.javaClass}")
    }
}
