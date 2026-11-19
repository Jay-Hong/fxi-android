package com.jay.fxi.e5djourney.test

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2a J00 (`e5dj_j2_plan.claude.r3.md` R5, R7; `s7e5dj_j2a.codex.r1.md` Q3·Q4): the journey build type runs production
 * on the fixture, and the fixture's three observers are ready and continuous before any journey starts.
 *
 * - Each start acknowledgement on its own: build values, the installed package's flags and meta-data, the network security policy
 *   in force, admission, one identity behind the three auth bindings, the endpoints actually resolved equal to this process's
 *   loopback server, the qualified wall clock, the journey accessor selected, and the provider, process and Activity lifecycle
 *   acknowledgements.
 * - The real free Root on the fixture owner, the USD page with a fresh snapshot shown, and the server's real entitlements and free
 *   snapshot requests, with nothing outside its table; every fence a production subscriber was given names the current owner.
 * - HOME and back: for the baseline MainActivity instance and task, its Application callback, its own Lifecycle observer and the
 *   process observer each move, after the phase mark, in the same process; the record read later goes on from the earlier one.
 * - The record is read up to a fixed upper, in pages of 200, 7, 1 and exactly its upper, and the reader's page check and the
 *   receiver's refusals catch a cut tail, a moved cursor or upper, an empty page claiming more, another process and an overflow.
 *   Grown past 201 lines, it is read across the 200-line page boundary at an upper of 200 and of 201.
 * - The channel answers a current command and rejects one for another process or an unknown command.
 *
 * Once the process nonce is known, the whole record is collected and checked at the end whatever the outcome, and a failure to
 * collect it fails the test (or is kept as suppressed under the journey's own failure). Before discovery there is no record to
 * collect. PENDING here (J2b): holding and releasing a delivery at the host's breakpoint and the host, token and operation
 * refusals; the journeys G09, G10, G11 and P6.
 */
@RunWith(AndroidJUnit4::class)
class J00StartTest {
    private val run = E5dJourneyRun()
    private val device get() = run.device
    private val client get() = run.client

    @After
    fun close() {
        run.close()
    }

    private fun launch() = run.launch()

    private fun waitText(text: String, timeoutMillis: Long = 30_000) = run.waitText(text, timeoutMillis)

    private fun awaitRecord(nonce: String, what: String, timeoutMillis: Long = 10_000, test: (List<String>) -> Boolean) =
        run.awaitRecord(nonce, what, timeoutMillis, test)

    @Test
    fun J00_theFixtureIsSelected_productionRunsOnIt_andTheObserversAreReady() {
        launch()
        // A cold start on a loaded emulator has taken over 30 seconds to its first frame (`e5dj_j2a_dev5_INFRA_NOTE.md`).
        waitText("무료 · 시간별 스냅샷", timeoutMillis = COLD_START_MILLIS)
        // Once the nonce is known, the record is collected and checked at the end whatever the outcome (`E5dJourneyRun.run`).
        run.run { nonce -> journey(nonce) }
    }

    private fun journey(nonce: String) {
        val start = client.ack("start", nonce)
        for ((key, want) in listOf(
            "buildType" to "e5dJourney",
            "debug" to true,
            "topicV2ReleaseOn" to true,
            "benchmarkNoDataMode" to false,
            "revenueCatKeyEmpty" to true,
            "crashCollectionAllowed" to false,
            "semanticsResourceIds" to true,
            "debuggableFlag" to true,
            "unminified" to true,
            "manifestTopicV2ReleaseOn" to true,
            "manifestAnalytics" to false,
            "manifestCrashlytics" to false,
            "manifestFcmAutoInit" to false,
            "cleartextLoopback" to true,
            "cleartextLocalhost" to true,
            "cleartextNewsImage" to false,
            "cleartextOther" to false,
            "admissionOpen" to true,
            "authIsIdentity" to true,
            "currentUser" to "u1",
            "identityUid" to "u1",
            "tokenForIdentity" to true,
            "endpointsAreServer" to true,
            "clockIsSystem" to true,
            "providerAck" to true,
            "processAck" to true
        )) {
            assertEquals("start ack $key: $start", want, start.get(key))
        }
        val port = start.getString("baseUrl").removePrefix("http://127.0.0.1:").removeSuffix("/")
        assertTrue("loopback 서버 $start", port.toIntOrNull() != null && start.getString("baseUrl") == "http://127.0.0.1:$port/")
        assertEquals("loopback WebSocket $start", "ws://127.0.0.1:$port/ws", start.getString("webSocketUrl"))
        assertTrue("journey accessor 선택 $start", start.getLong("accessorSelections") >= 1)
        assertTrue("Activity observer ack $start", start.getInt("activityAcks") >= 1)
        assertEquals("응답은 free 로 시작한다 $start", "free rev=0", start.getString("answer"))

        waitText("달러 · USD/KRW")
        waitText("정상")
        val first = awaitRecord(nonce, "free snapshot 수신") { lines -> stream(lines, "http").any { "free-snapshot usd 1d" in it } }
        val http = stream(first, "http")
        assertTrue("entitlements 실제 수신 $http", http.any { "GET /api/entitlements" in it && "entitlements free rev=0" in it })
        assertEquals("표 밖의 요청", emptyList<String>(), http.filter { "UNEXPECTED" in it })
        val lifecycle = texts(first, "lifecycle")
        assertTrue("provider ack $lifecycle", lifecycle.any { it.startsWith("ack provider nonce=$nonce ") })
        assertTrue("process ack $lifecycle", lifecycle.contains("ack process"))
        val baseline = lifecycle.firstNotNullOfOrNull { Regex("""ack activity MainActivity#(\d+)""").matchEntire(it)?.groupValues?.get(1) }
        checkNotNull(baseline) { "Activity ack $lifecycle" }
        val task = texts(first, "activity").firstNotNullOfOrNull {
            Regex("""MainActivity#$baseline pre-created .* task=(\d+)""").matchEntire(it)?.groupValues?.get(1)
        }
        checkNotNull(task) { "baseline MainActivity#$baseline 의 task" }

        // A returning user's cold start: every subscriber is handed the current owner first, never a signed-out fence.
        val fences = texts(first, "auth").filter { it.startsWith("fence ") }
        val generation = start.getLong("identityGeneration")
        assertTrue("fence 가 전달됐다 $fences", fences.isNotEmpty())
        assertEquals("production 구독자가 받은 fence 는 모두 현재 identity 다", listOf("fence u1 gen=$generation"), fences.distinct())
        // The journey accessor at work in the real host: its set selected and attached, the host's context, and the USD page
        // bound to that host. Holding and releasing a delivery there is J2b's and stays PENDING.
        val hooks = texts(first, "hook")
        assertTrue("accessor 선택 $hooks", hooks.any { Regex("""set \d+ selected""").matches(it) })
        assertTrue("accessor 부착 $hooks", hooks.any { Regex("""set \d+ attached""").matches(it) })
        assertTrue("host context $hooks", hooks.any { Regex("""set \d+ context host=1 session=1 binding=\d+""").matches(it) })
        assertTrue("USD page 가 host 에 묶였다 $hooks", hooks.any { Regex("""set \d+ (mount|mount bound) host=1 mount=\d+ tab=USD""").matches(it) })
        assertEquals("모르는 page 해제", emptyList<String>(), hooks.filter { " dispose unknown " in it })

        roundTripCatchesEachBreak()

        // HOME and back in the same process: the baseline instance's callback, its Lifecycle and the process each move.
        val home = client.ack("phase", nonce) { putExtra("arg", "home") }.getInt("sequence")
        device.pressHome()
        val away = awaitRecord(nonce, "background 이력") { lines -> roundTrip(lines, home, "home", baseline, task, "stopped", "ON_STOP") }
        val back = client.ack("phase", nonce) { putExtra("arg", "back") }.getInt("sequence")
        assertTrue("phase watermark 는 앞의 기록 뒤다", back > away.size)
        launch()
        waitText("무료 · 시간별 스냅샷")
        val returned = awaitRecord(nonce, "foreground 이력") { lines -> roundTrip(lines, back, "back", baseline, task, "started", "ON_START") }
        assertTrue("기록은 이어진다", returned.size > away.size && returned.take(away.size) == away)
        assertEquals("같은 process", nonce, run.discover().getString("nonce"))
        assertEquals("표 밖의 요청(복귀 뒤)", emptyList<String>(), stream(returned, "http").filter { "UNEXPECTED" in it })

        // The record read in pages of 200, 7, 1 and exactly its upper agrees with itself; then the page check and the receiver's
        // refusals.
        val whole = client.dump(nonce)
        for (max in listOf(7, 1, whole.upper)) {
            val paged = client.dump(nonce, max)
            assertTrue("page $max: upper ${paged.upper} ≥ ${whole.upper}", paged.upper >= whole.upper)
            assertEquals("page $max 로 읽어도 같은 기록", whole.lines, paged.lines.take(whole.upper))
        }
        pageCheckCatchesEachBreak(nonce, whole)
        for ((what, extras) in listOf<Pair<String, Intent.() -> Unit>>(
            "cursor past upper" to { putExtra("cursor", whole.upper + 1); putExtra("max", 10); putExtra("upper", whole.upper) },
            "upper past the record" to { putExtra("cursor", 0); putExtra("max", 10); putExtra("upper", whole.upper + 100_000) },
            "negative cursor" to { putExtra("cursor", -1); putExtra("max", 10) },
            "no cursor" to { putExtra("max", 10) },
            "max 0" to { putExtra("cursor", 0); putExtra("max", 0) },
            "max over 200" to { putExtra("cursor", 0); putExtra("max", 201) }
        )) {
            runCatching { client.reject("dump", nonce, "page", extras) }.onFailure { throw AssertionError("dump $what 은(는) 거절돼야 한다", it) }
        }

        // The channel: a command for another process and an unknown command are rejected.
        client.reject("phase", "not-this-process", "nonce") { putExtra("arg", "x") }
        client.reject("no-such-command", nonce, "command")

        pageBoundaries(nonce)
    }

    /**
     * The 200-line page boundary on the real recorder (`s7e5dj_j2a.codex.r2.md` Q4-A): phase marks grow the record past 201
     * lines, then it is read up to a fixed upper of exactly 200 (one full page, nothing more) and of 201 (a full page and a
     * one-line remainder), and up to its own upper in pages of 200; each in order, at its length, ending at its upper.
     */
    private fun pageBoundaries(nonce: String) {
        var last = 0
        var marks = 0
        while (last < 205) {
            last = client.ack("phase", nonce) { putExtra("arg", "pad") }.getInt("sequence")
            marks++
            check(marks < 400) { "premise: the record did not grow past 205 (at $last)" }
        }
        val exact = client.dump(nonce, max = 200, upTo = 200)
        assertEquals("upper 200: 한 page", listOf(200), exact.pages)
        val over = client.dump(nonce, max = 200, upTo = 201)
        assertEquals("upper 201: 200 + 1", listOf(200, 1), over.pages)
        val whole = client.dump(nonce, max = 200)
        assertTrue("자기 upper ${whole.upper} ≥ $last", whole.upper >= last)
        assertEquals("200 단위 page", listOf(200, whole.upper - 200), whole.pages)
        for (dump in listOf(exact, over, whole)) {
            assertEquals("길이 = upper", dump.upper, dump.lines.size)
            assertEquals("순서", (1..dump.upper).map { it.toString() }, dump.lines.map { it.substringBefore('\t') })
        }
        assertEquals("같은 기록", exact.lines, over.lines.take(200))
        assertEquals("같은 기록", over.lines, whole.lines.take(201))
        assertTrue("phase mark 가 기록에 있다", whole.lines[last - 1].split('\t').let { it[2] == "phase" && it[4] == "pad" })
    }

    /**
     * After the [marker] phase mark at sequence [mark]: the [id] MainActivity instance's own Application callback [callback] in
     * [task], its Lifecycle observer's [event], and the process observer's [event], each on its own line.
     */
    private fun roundTrip(lines: List<String>, mark: Int, marker: String, id: String, task: String, callback: String, event: String): Boolean {
        val markLine = lines.getOrNull(mark - 1)?.split('\t', limit = 5)
        if (markLine == null || markLine.size < 5 || markLine[0] != mark.toString() || markLine[2] != "phase" || markLine[4] != marker) return false
        val after = lines.drop(mark)
        return texts(after, "activity").contains("MainActivity#$id $callback task=$task") &&
            texts(after, "activity-lifecycle").contains("MainActivity#$id $event") &&
            texts(after, "process").contains(event)
    }

    /** [roundTrip] on made-up records: the whole set passes, and each missing, misplaced or misattributed part fails. */
    private fun roundTripCatchesEachBreak() {
        fun line(seq: Int, stream: String, text: String) = "$seq\t0\t$stream\thome\t$text"
        val mark = line(1, "phase", "home")
        val callback = line(2, "activity", "MainActivity#1 stopped task=7")
        val lifecycle = line(3, "activity-lifecycle", "MainActivity#1 ON_STOP")
        val process = line(4, "process", "ON_STOP")
        fun holds(vararg lines: String) = roundTrip(lines.toList(), 1, "home", "1", "7", "stopped", "ON_STOP")
        assertTrue("양성 대조", holds(mark, callback, lifecycle, process))
        assertFalse("Lifecycle observer 기록 없음", holds(mark, callback, process))
        assertFalse("Application callback 없음", holds(mark, lifecycle, process))
        assertFalse("process 사건 없음", holds(mark, callback, lifecycle))
        assertFalse("다른 Activity 의 Lifecycle", holds(mark, callback, line(3, "activity-lifecycle", "MainActivity#2 ON_STOP"), process))
        assertFalse("다른 task 의 callback", holds(mark, line(2, "activity", "MainActivity#1 stopped task=8"), lifecycle, process))
        assertFalse("mark 앞의 사건", roundTrip(listOf(callback, lifecycle, process, line(4, "phase", "home")), 4, "home", "1", "7", "stopped", "ON_STOP"))
        assertFalse("다른 phase 의 mark", roundTrip(listOf(line(1, "phase", "back"), callback, lifecycle, process), 1, "home", "1", "7", "stopped", "ON_STOP"))
    }

    /** [E5dJourneyControlClient.checkPage] on pages made from a real record: a valid page passes and each break throws. */
    private fun pageCheckCatchesEachBreak(nonce: String, whole: E5dJourneyControlClient.Dump) {
        check(whole.upper >= 4) { "premise: a record of at least four lines, not ${whole.upper}" }
        fun page(cursor: Int, upper: Int, lines: List<String>, more: Boolean, overflowed: Boolean = false, by: String = nonce) =
            JSONObject().put("nonce", by).put("cursor", cursor).put("upper", upper).put("last", upper).put("more", more)
                .put("overflowed", overflowed).put("lines", JSONArray(lines))
        val upper = whole.upper
        val head = whole.lines.take(3)
        fun passes(body: JSONObject, cursor: Int = 0, fixed: Int? = upper, max: Int = 3) =
            runCatching { E5dJourneyControlClient.checkPage(nonce, cursor, fixed, max, body) }.isSuccess
        assertTrue("양성 대조(첫 page)", passes(page(0, upper, head, more = true), fixed = null))
        assertTrue("양성 대조(마지막 page)", passes(page(upper - 1, upper, listOf(whole.lines.last()), more = false), cursor = upper - 1))
        assertFalse("잘린 tail", passes(page(upper - 1, upper, emptyList(), more = false), cursor = upper - 1))
        assertFalse("page 가 모자람", passes(page(0, upper, head.take(2), more = true)))
        assertFalse("cursor 불일치", passes(page(1, upper, head, more = true)))
        assertFalse("upper 이동", passes(page(0, upper + 1, head, more = true)))
        assertFalse("more 인데 빈 page", passes(page(0, upper, emptyList(), more = true)))
        assertFalse("끝인데 more", passes(page(upper - 1, upper, listOf(whole.lines.last()), more = true), cursor = upper - 1))
        assertFalse("다른 process", passes(page(0, upper, head, more = true, by = "another")))
        assertFalse("overflow", passes(page(0, upper, head, more = true, overflowed = true)))
        assertFalse("sequence 순서", passes(page(0, upper, listOf(head[1], head[0], head[2]), more = true)))
    }

    private fun stream(lines: List<String>, name: String) = E5dJourneyRun.stream(lines, name)

    private fun texts(lines: List<String>, name: String) = E5dJourneyRun.texts(lines, name)

    private companion object {
        const val COLD_START_MILLIS = 60_000L
    }
}
