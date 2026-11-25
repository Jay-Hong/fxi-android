package com.jay.fxi.e5djourney.test

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * S7 U3a, second unit (`s7u3a/u3a2_contract.agreed.r1.md`; user decision G-U3 "직접 주입 먼저"): what the bank settings do when
 * the app comes back after a delivery, and when a notification is tapped. As in J13, a delivery is handed to the Firebase
 * SDK's own broadcast processor inside the app; nothing before the processor is in it.
 *
 * - J14_0 / J14_1: after a visible once delivery behind (the SDK's post, no callback), a return through the launcher inside
 *   the owner's 30 second freshness window asks for nothing, and one outside it asks once.
 * - J14_2 / J14_3: a data-only sync behind is held and asked for once at the return, inside the window; with no delivery the
 *   same return asks for nothing.
 * - J14_4: a tap on the post the app drew in front starts a new MainActivity whose Intent carries the app's own extras, and
 *   the settings are asked for once. Opening the shade and closing it again does none of that.
 * - J14_5 / J14_6: the same for the post the SDK drew behind, whose Intent carries the delivery's data strings; with the
 *   shade closed again and a return through the launcher, nothing is asked for.
 * - J14_7 / J14_8: a new MainActivity under the same request flags but with no extras, in front and from behind, asks for
 *   nothing: a new instance alone is not what makes a tap ask.
 * - J14_9: the fixture's `alert-row` command refuses what it does not take and leaves no line.
 *
 * How a refresh is seen: the loopback server's row 101 is put in another shape than the screen shows (`alert-row`), so a list
 * that was fetched and adopted changes the row on the screen, and one that was not leaves it. Each timing case first has the app
 * adopt a list that differs from its screen ("preparation"): that the screen changed shows the owner took the answer — its
 * unresolved-refresh debt cleared and its last success set — and fixes two times on the device's monotonic clock, G (the
 * record line of that one list request) and U (right after the screen was read changed). The owner's success time lies between
 * them. "Inside" is then: the request an action caused is recorded no later than G + 20 s, or, where none is expected, the
 * screen was read no later than G + 20 s and the watch ended no later than G + 28 s. "Outside" is: the action was asked for no
 * earlier than U + 40 s. A run that cannot show its times fails on a premise (a message starting `premise:`), never as a result.
 *
 * The server's armed shape in J14_4, J14_5, J14_7 and J14_8 is a condition of the test — it makes an adopted answer visible —
 * and not the state a real server is in after a once alert; in J14_7 and J14_8 the expected screen is fired.
 *
 * What a tap case measures is the Intent a new MainActivity was started with (the fixture's `intent` line, written before the
 * Activity's own code reads and clears the extras), one list request after the tap and its answer on the screen. That the id
 * in those extras was routed as a valid setting is not shown here: an id that cannot be read also asks once. That is the
 * parser's and the routing's own source and their JVM tests. Not shown either: a delivery to a killed process, the app online,
 * another API level or a real device, a real FCM delivery and the extras it carries, and that a refused `alert-row` leaves
 * every other field of the server's list as it was (the screen shows row 101 only). The data strings are the bank goldens',
 * copied here and compared with the corpus by `E5dJourneyJ13GoldenDataTest`, as J13's are. The runner puts the device in
 * airplane mode; a device that is not fails every case on that premise.
 */
class J14FcmReturnAndTapTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)
    private val notifications = E5dJourneyNotifications(run)

    /** True once this case has read the device's airplane setting as on: only then is there anything of its own to clean up. */
    private var inAirplaneMode = false

    private fun airplane(): String = run.device.executeShellCommand("settings get global airplane_mode_on").trim()

    /** As J13: the device already in airplane mode (read, never written), a new process of the app in front, the listener connected. */
    @Before
    fun open() {
        val atStart = airplane()
        check(atStart == "1") { "premise: J14 는 airplane mode 가 켜진 기기에서만 돈다(실행자가 켠다). airplane_mode_on='$atStart'" }
        inAirplaneMode = true
        Log.i(TAG, "airplane at start=$atStart")
        notifications.allow()
        preview.endProcess()
        run.launch()
        run.waitText(E5dJourneyPreview.SCREEN_TITLE, timeoutMillis = E5dJourneyPreview.COLD_START_MILLIS)
    }

    /** A case may end with the shade open or the app behind: Home first, so that neither is left for the next case. */
    @After
    fun close() {
        try {
            if (inAirplaneMode) {
                runCatching { run.device.pressHome() }
                runCatching { notifications.revoke() }
                runCatching { preview.endProcess() }
                val atEnd = airplane()
                Log.i(TAG, "airplane at end=$atEnd")
                check(atEnd == "1") { "사례가 끝났을 때 airplane mode 가 켜져 있지 않다: airplane_mode_on='$atEnd'" }
            }
        } finally {
            run.close()
        }
    }

    /** The fixture's premium session with the bank settings loaded once: where every case starts from. */
    private fun premium(nonce: String) {
        run.awaitRecord(nonce, "startup", timeoutMillis = 90_000) { lines -> texts(lines, "http").any { "-> entitlements free" in it } }
        checkComparison("free", record(nonce), 0)
        run.client.ack("answer", nonce) { putExtra("arg", "active") }
        run.client.ack("local-premium", nonce)
        val deadline = System.currentTimeMillis() + PREMIUM_MILLIS
        while (run.client.ack("access", nonce).getJSONObject("ownedAfter").getString("state") != "PremiumConfirmed") {
            check(System.currentTimeMillis() < deadline) { "premise: PremiumConfirmed 가 오지 않았다" }
            Thread.sleep(500)
        }
        run.awaitRecord(nonce, "은행·비교 설정의 첫 GET", timeoutMillis = 90_000) { lines ->
            val http = texts(lines, "http")
            http.any { it.startsWith(SETTINGS_GET) } && COMPARISON_LIST in http
        }
        run.settle(nonce)
        checkComparison("cold premium", record(nonce), 1)
    }

    private var comparisonUpper = 0

    private fun checkComparison(label: String, through: List<E5dJourneyTraffic.Line>, expected: Int) {
        assertComparisonRows(label, through.filter { it.sequence > comparisonUpper }, expected)
        through.lastOrNull()?.let { comparisonUpper = it.sequence }
    }

    private fun pid(): Int = run.discover().getInt("pid")

    /** One delivery of [data]; a visible one carries this journey's marker as its title and the golden's body. */
    private fun deliver(nonce: String, case: String, data: Map<String, String>, visible: Boolean): Pair<JSONObject, String> {
        val mid = "u3a-${UUID.randomUUID()}"
        val title = "U3A $case ${mid.takeLast(6)}"
        val ack = run.client.ack("fcm", nonce) {
            putExtra("data", Bundle().apply { data.forEach { (key, value) -> putString(key, value) } })
            putExtra("mid", mid)
            if (visible) {
                putExtra("title", title)
                putExtra("body", data.getValue("body"))
            }
        }
        Log.i(TAG, "$case delivered mid=$mid ack=$ack")
        return ack to title
    }

    // ---- the record, the clock and the screen

    private fun record(nonce: String): List<E5dJourneyTraffic.Line> = E5dJourneyTraffic.parse(run.client.dump(nonce).lines)

    /** The sequence of the record's last line now. */
    private fun upper(nonce: String): Int = run.client.dump(nonce).upper

    /** Every bank settings request the loopback server recorded, in order, whatever it answered. */
    private fun gets(record: List<E5dJourneyTraffic.Line>) = record.filter { it.stream == "http" && it.text.startsWith(SETTINGS_GET) }

    /** Whether the server answered [get] with the owner's list. */
    private fun listed(get: E5dJourneyTraffic.Line) = LISTED in get.text

    /** The device's monotonic clock, the one the record's lines carry. */
    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    private fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000

    /** Puts the loopback server's row 101 in [state] and holds the ACK to the one line that change wrote. */
    private fun serverRow(nonce: String, state: String) {
        val before = upper(nonce)
        val ack = run.client.ack("alert-row", nonce) {
            putExtra("id", ROW_ID.toString())
            putExtra("arg", state)
        }
        val changes = record(nonce)
            .filter { it.sequence in (before + 1)..ack.getInt("recordUpper") && it.stream == "server" && it.text.startsWith(ROW_CHANGE) }.map { it.text }
        check(ack.getInt("id") == ROW_ID && ack.getString("state") == state && changes == listOf("${ROW_CHANGE}id=$ROW_ID -> $state n=1")) {
            "premise: 서버의 ${ROW_ID}행을 $state 로 바꾼 기록이 ACK 와 맞지 않는다: ack=$ack 기록=$changes"
        }
    }

    /** What the screen shows of row 101, read from the row's own toggle and the row's own text: [ARMED], [FIRED], or what was read. */
    private fun row(): String {
        repeat(3) {
            try {
                run.reveal(ROW)
                val sent = SENT in run.textOf(ROW)
                val checked = run.node(TOGGLE).isChecked
                return when {
                    checked && !sent -> ARMED
                    !checked && sent -> FIRED
                    else -> "mixed(checked=$checked sent=$sent)"
                }
            } catch (stale: StaleObjectException) {
                Thread.sleep(100)
            }
        }
        return "unread(stale)"
    }

    private fun awaitRow(wanted: String, start: Long = now()): String {
        val deadline = start + ROW_MILLIS * 1_000_000L
        while (true) {
            val seen = row()
            if (now() > deadline) return "timeout(last=$seen)"
            if (seen == wanted) return seen
            Thread.sleep(300)
        }
    }

    private fun awaitPremium(what: String) {
        val deadline = System.currentTimeMillis() + SCREEN_MILLIS
        while (!preview.premiumShown()) {
            check(System.currentTimeMillis() < deadline) { "$what: premium 화면이 오지 않았다. 화면: ${run.describeScreen()}" }
            Thread.sleep(200)
        }
    }

    // ---- preparation

    /** The request and the following screen reading on the device clock; Home is marked on that clock as well. */
    private class Prep(val g: Long, val u: Long) {
        var homeAt: Long? = null
        var homePackage: String? = null
    }

    private lateinit var caseWindow: E5dJourneyNotifications.Window
    private lateinit var homeWindow: E5dJourneyNotifications.Window
    private var casePid = -1

    /**
     * The preparation: the server's row put in [state], which the screen does not show, one data-only sync in front, and the
     * screen read changed to [state]. Exactly one settings request, answered with the list, lies between; nothing more comes
     * while the server is watched quiet. Anything else is a premise failure.
     */
    private fun adopt(nonce: String, state: String): Prep = try {
        adoptRow(nonce, state)
    } catch (failure: IllegalStateException) {
        if (failure.message?.startsWith("premise:") == true) throw failure
        throw IllegalStateException("premise: 준비($state) 관측 실패: ${failure.message}", failure)
    }

    private fun adoptRow(nonce: String, state: String): Prep {
        val other = if (state == FIRED) ARMED else FIRED
        val shown = row()
        check(shown == other) { "premise: 준비($state) 직전 화면의 ${ROW_ID}행이 $other 가 아니다: $shown" }
        serverRow(nonce, state)
        val before = record(nonce)
        checkComparison("준비($state) sync 전", before, 0)
        val from = before.last().sequence
        val start = now()
        val (ack, _) = deliver(nonce, "prep-$state", SYNC, visible = false)
        check(ack.getInt("result") == -1 && ack.getInt("importanceBefore") == 100) { "premise: 준비($state) 의 sync 가 앞에서 전달되지 않았다: $ack" }
        val seen = awaitRow(state, start)
        val u = now()
        val got = gets(record(nonce)).filter { it.sequence > from && it.nanos <= u }
        check(got.size == 1 && listed(got.single())) { "premise: 준비($state) 의 GET 불일치: ${got.map { it.text }}" }
        check(u - start <= ROW_MILLIS * 1_000_000L && seen == state) {
            "premise: 준비($state) 의 채택이 확인되지 않았다: 화면=$seen U-start=${u-start}ns"
        }
        run.settle(nonce, quietMillis = QUIET_MILLIS)
        val settled = record(nonce)
        checkComparison("준비($state) sync", settled, 1)
        val extra = gets(settled).filter { it.nanos > u }
        check(extra.isEmpty()) { "premise: 준비($state) 뒤 조용한 동안 추가 GET: ${extra.map { it.nanos to it.text }}" }
        val g = got.single().nanos
        Log.i(TAG, "adopt $state U-G=${ms(g, u)}ms G=$g U=$u")
        return Prep(g, u)
    }

    /** The MainActivity that was resumed last, by its number in the record. */
    private fun resumed(nonce: String): String =
        checkNotNull(record(nonce).lastOrNull { it.stream == "activity" && RESUMED.matches(it.text) }) { "premise: resumed 된 MainActivity 가 기록에 없다" }
            .let { RESUMED.matchEntire(it.text)!!.groupValues[1] }

    /** Home, the process stopped and still the same one; no settings request since [prep]. Returns the phase mark's sequence. */
    private fun home(nonce: String, pid: Int, prep: Prep): Int {
        val mark = preview.phase(nonce, "j14-home")
        homeWindow = notifications.mark(nonce, caseWindow)
        val homeAt = now()
        prep.homeAt = homeAt
        // Whether the key was injected (pressKeyCode). pressHome answers something else — whether an accessibility event
        // followed within a second — and said false for a Home that was pressed (O1, rehearsal 4).
        check(run.device.pressKeyCode(KeyEvent.KEYCODE_HOME)) { "premise: Home 키가 주입되지 않았다" }
        val stop = runCatching {
            run.awaitRecord(nonce, "process ON_STOP", timeoutMillis = 20_000) { lines ->
                texts(after(lines, mark), "process").any { it == "ON_STOP" }
            }
        }
        check(stop.isSuccess) { "premise: Home 뒤 ON_STOP 미확인: ${stop.exceptionOrNull()}" }
        prep.homePackage = run.device.currentPackageName
        val got = gets(record(nonce)).filter { it.nanos > prep.u }
        check(got.none { it.nanos < homeAt }) { "premise: U부터 Home 요청 전까지 추가 GET: ${got.map { it.text }}" }
        assertTrue("Home 뒤 배경 GET: ${got.map { it.text }}", got.none { it.nanos >= homeAt })
        val homePid = pid()
        check(homePid == pid) { "premise: Home 뒤 pid=$homePid 시작 pid=$pid" }
        return mark
    }

    // ---- notifications

    private fun five(post: E5dJourneyNotificationListener.Posted) = listOf(post.tag, post.id, post.channel, post.title, post.postTime)

    private fun five(active: E5dJourneyNotifications.Active) = listOf(active.tag, active.id, active.channel, active.title, active.postTime)

    private fun byTheApp(post: E5dJourneyNotificationListener.Posted, title: String) =
        post.tag == null && post.id != 0 && post.channel == E5dJourneyNotifications.CHANNEL && post.title == title

    private fun byTheSdk(post: E5dJourneyNotificationListener.Posted, title: String) =
        post.tag?.startsWith(E5dJourneyNotifications.SDK_TAG_PREFIX) == true && post.id == 0 && post.channel == E5dJourneyNotifications.CHANNEL && post.title == title

    /** Gives a post after [window]'s start the time to reach the listener; whether one came is for the caller to judge. */
    private fun awaitPost(window: E5dJourneyNotifications.Window) {
        val deadline = System.currentTimeMillis() + POST_MILLIS
        while (E5dJourneyNotificationListener.state().posted <= window.from && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    /** The delivery's marker title and the observation boundary taken at Home. */
    private class Shown(val title: String, val window: E5dJourneyNotifications.Window)

    /** One visible once delivery while the app is behind, and the first reading of what it left ([assertBehind]). */
    private fun visibleBehind(nonce: String, pid: Int, label: String): Shown {
        val window = homeWindow
        val (ack, title) = deliver(nonce, label, ONCE, visible = true)
        assertEquals("$label: processor 의 결과", -1, ack.getInt("result"))
        assertNotEquals("premise: $label: 주입 직전에 읽은 importance 가 100 이 아니다(Home 뒤)", 100, ack.getInt("importanceBefore"))
        assertEquals("premise: $label: keyguard 가 없다", false, ack.getBoolean("keyguard"))
        awaitPost(window)
        return Shown(title, window).also { assertBehind(nonce, pid, it, "$label 주입 직후") }
    }

    /**
     * While the app is behind: the one post is the SDK's and is the one active notification, with the same tag, id, channel,
     * title and post time; the app drew none, its callback left no type line, and no settings request came. Returns the post.
     */
    private fun assertBehind(nonce: String, pid: Int, shown: Shown, whenRead: String): E5dJourneyNotificationListener.Posted {
        val seen = notifications.read(nonce, shown.window, pid)
        Log.i(TAG, "behind($whenRead) posted=${seen.posted} active=${seen.active} settingsGets=+${seen.gets} typeLogs=${seen.typeLogs}")
        assertEquals("$whenRead: 뒤에 있는 동안의 게시는 1건이다 ${seen.posted}", 1, seen.posted.size)
        val post = seen.posted.single()
        assertTrue("$whenRead: SDK 가 그린 게시다(SDK tag·id 0·채널·표지 title) $post", byTheSdk(post, shown.title))
        assertEquals("$whenRead: active 는 그 1건이다(tag·id·channel·title·postTime)", listOf(five(post)), seen.active.map { five(it) })
        assertEquals("$whenRead: 뒤에 있는 동안 은행 설정 GET 은 없다", 0, seen.gets)
        assertEquals("$whenRead: 대상 PID 의 callback type 로그가 관측되지 않았다", emptyList<String>(), seen.typeLogs)
        return post
    }

    /** The one post in this observation is still the active notification, value for value. */
    private fun assertStill(seen: E5dJourneyNotifications.Observation, post: E5dJourneyNotificationListener.Posted, what: String) {
        assertEquals("$what: 그 사이 다른 게시는 없다 ${seen.posted}", listOf(five(post)), seen.posted.map { five(it) })
        assertEquals("$what: 그 알림은 active 그대로다(tag·id·channel·title·postTime) ${seen.active}", listOf(five(post)), seen.active.map { five(it) })
    }

    /** The tapped [post] is gone and nothing else was posted since [window] began: read at the end of the watch. */
    private fun assertConsumed(seen: E5dJourneyNotifications.Observation, post: E5dJourneyNotificationListener.Posted) {
        assertEquals("(a) 사례의 게시는 탭한 그 1건뿐이다 ${seen.posted}", listOf(five(post)), seen.posted.map { five(it) })
        assertTrue("(a) 탭한 알림은 더 이상 active 가 아니다 ${seen.active}", seen.active.none { it.tag == post.tag && it.id == post.id })
    }

    // ---- after an action

    /** What followed an action asked for at [a]: the row as it was at [c], and every settings request in (a, e]. */
    private class Watch(
        val a: Long, val c: Long, val e: Long, val rowAtC: String, val gets: List<E5dJourneyTraffic.Line>,
        val rowAtE: String, val notifications: E5dJourneyNotifications.Observation, val premium: Boolean,
        val pid: Int, val record: List<E5dJourneyTraffic.Line>
    )

    /**
     * The watch after an action, the app in front again: the row is read as it is — not waited for — which fixes C; then at
     * least the trailing time passes. Where a request is expected inside the freshness window, none has come by then and G + 20 s
     * is still ahead, the watch goes on until G + 20 s, so that a missing request is said only after its time is over. E is
     * when the watch really ended.
     */
    private fun watch(nonce: String, what: String, a: Long, prep: Prep, expectGet: Boolean, outside: Boolean = false, comparison: Int = 0): Watch {
        val inside = prep.takeUnless { outside }
        val shown = row()
        val c = now()
        check(shown == FIRED || shown == ARMED) { "premise: $what: C에서 101행 판독 실패: $shown C=$c" }
        Thread.sleep(E5dJourneyNotifications.TRAILING_MILLIS)
        fun got(until: Long) = gets(record(nonce)).filter { it.nanos > a && it.nanos <= until }
        if (expectGet && inside != null && got(now()).isEmpty()) {
            while (ms(inside.g, now()) <= INSIDE_MILLIS) Thread.sleep(250)
        }
        val endPosts = notifications.read(nonce, caseWindow, casePid)
        val premium = preview.premiumShown()
        val endPid = pid()
        val endRow = if (premium) try { row() } catch (e: IllegalStateException) { "unread(${e.message})" } else "premium 화면 없음"
        val e = now()
        val lines = record(nonce).filter { it.nanos <= e }
        checkComparison("$what 동작 전", lines.filter { it.nanos <= a }, 0)
        checkComparison("$what 동작 뒤", lines, comparison)
        val allGets = gets(lines)
        val before = allGets.filter { it.nanos > prep.u && it.nanos <= a }
        val homeAt = prep.homeAt
        check(before.none { homeAt == null || it.nanos < homeAt }) {
            "premise: $what: U–A 전경 GET U=${prep.u} A=$a GET=${before.map { it.nanos to it.text }}"
        }
        assertTrue("$what: Home 뒤 A까지 배경 GET: ${before.map { it.nanos to it.text }}", before.isEmpty())
        val gets = allGets.filter { it.nanos > a }
        Log.i(TAG, "$what watch rowAtC=$shown C-A=${ms(a, c)}ms E-A=${ms(a, e)}ms gets=${gets.map { "+${ms(a, it.nanos)}ms ${it.text}" }}" +
            (inside?.let { " A-G=${ms(it.g, a)}ms A-U=${ms(it.u, a)}ms C-G=${ms(it.g, c)}ms E-G=${ms(it.g, e)}ms" } ?: ""))
        return Watch(a, c, e, shown, gets, endRow, endPosts, premium, endPid, lines)
    }

    /**
     * Inside the window, one request expected. None in (A, E]: a missing request when the screen was read by G + 20 s, a premise
     * failure when it was read later than that. Exactly one: the window is judged by that request's own time — later than
     * G + 20 s is a premise failure, and otherwise it must be the list, whenever the screen was read. More than one fails.
     */
    private fun assertOneGetInside(what: String, prep: Prep, seen: Watch) {
        when (seen.gets.size) {
            0 -> {
                check(seen.c - prep.g <= INSIDE_MILLIS * 1_000_000L) { "premise: $what: 창 안임을 입증할 수 없다: C-G=${ms(prep.g, seen.c)}ms" }
                fail("$what: 기대한 은행 설정 GET 이 없다(E-G=${ms(prep.g, seen.e)}ms 까지 관측, C 의 행=${seen.rowAtC})")
            }
            1 -> {
                val get = seen.gets.single()
                check(get.nanos - prep.g <= INSIDE_MILLIS * 1_000_000L) { "premise: $what: GET 이 창 안의 결정임을 입증할 수 없다: GET-G=${ms(prep.g, get.nanos)}ms" }
                assertTrue("$what: 그 GET 은 성공한 목록 응답이다: ${get.text}", listed(get))
            }
            else -> fail("$what: 동작 뒤 은행 설정 GET 은 정확히 1건이어야 한다: ${seen.gets.map { it.text }}")
        }
    }

    /** Inside the window, none expected: the screen read by G + 20 s and the watch over by G + 28 s, and no request in (A, E]. */
    private fun assertNoGetInside(what: String, prep: Prep, seen: Watch) {
        check(seen.c - prep.g <= INSIDE_MILLIS * 1_000_000L && seen.e - prep.g <= WINDOW_MILLIS * 1_000_000L) {
            "premise: $what: 창 안의 관측임을 입증할 수 없다: C-G=${ms(prep.g, seen.c)}ms E-G=${ms(prep.g, seen.e)}ms"
        }
        assertEquals("$what: 창 안에서는 은행 설정 GET 이 없다 ${seen.gets.map { it.text }}", 0, seen.gets.size)
    }

    /** Outside the window: the action asked for no earlier than U + 40 s, and exactly one request in (A, E], answered with the list. */
    private fun assertOneGetOutside(what: String, prep: Prep, seen: Watch) {
        check(ms(prep.u, seen.a) >= OUTSIDE_MILLIS) { "premise: $what: 창 밖임을 입증할 수 없다: A-U=${ms(prep.u, seen.a)}ms" }
        assertEquals("$what: 창 밖의 복귀 뒤 은행 설정 GET 은 정확히 1건이다 ${seen.gets.map { it.text }} row=${seen.rowAtE}", 1, seen.gets.size)
        assertTrue("$what: 그 GET 은 성공한 목록 응답이다: ${seen.gets.single().text}", listed(seen.gets.single()))
    }

    /** Waits for a MainActivity to be resumed after sequence [mark], then for the premium screen. */
    private fun awaitFront(nonce: String, a: Long, what: String) {
        run.awaitRecord(nonce, "$what 뒤 MainActivity 의 resumed", timeoutMillis = 20_000) { lines ->
            E5dJourneyTraffic.parse(lines).any { it.nanos > a && it.stream == "activity" && RESUMED.matches(it.text) }
        }
        awaitPremium(what)
    }

    /** Back through the launcher entry: the record's upper and the clock just before the request, then the app in front. */
    private fun returnThroughLauncher(nonce: String, what: String): Pair<Int, Long> {
        val mark = upper(nonce)
        val a = now()
        run.launch()
        awaitFront(nonce, a, what)
        return mark to a
    }

    /** No Activity was made since sequence [from], and MainActivity#[id] was resumed again after sequence [returned]. */
    private fun assertSameInstance(seen: Watch, from: Int, returned: Int, id: String, what: String) {
        val record = seen.record
        assertEquals("$what: 새로 만들어진 Activity 가 없다", emptyList<String>(), record.filter { it.sequence > from && it.stream == "intent" }.map { it.text })
        assertTrue("$what: 기존 MainActivity#$id 가 다시 resumed 됐다",
            record.any { it.nanos > seen.a && it.nanos <= seen.c && it.sequence > returned && it.stream == "activity" && it.text.startsWith("MainActivity#$id resumed ") })
    }

    /**
     * Exactly one Activity was made since sequence [mark]: a MainActivity with no saved state, whose launch line stands between
     * its own pre-created and created lines and says the Intent carried [extras] under the four keys a tap is parsed from.
     * The flags and the action are logged, not asserted.
     */
    private fun assertNewInstance(seen: Watch, mark: Int, extras: String, what: String) {
        val later = seen.record.filter { it.sequence > mark }
        val launches = later.filter { it.stream == "intent" }
        Log.i(TAG, "$what launches=${launches.map { it.text }}")
        assertEquals("$what: 새로 만들어진 Activity 는 정확히 하나다 ${launches.map { it.text }}", 1, launches.size)
        val launch = launches.single()
        val match = LAUNCH.matchEntire(launch.text)
        assertTrue("$what: MainActivity 의 launch 줄이다: ${launch.text}", match != null)
        val (id, carried) = match!!.destructured
        fun activity(event: String, where: (Int) -> Boolean) =
            later.any { it.stream == "activity" && it.text.startsWith("MainActivity#$id $event") && where(it.sequence) }
        assertTrue("$what: 그 인스턴스는 saved state 없이 만들어졌다(launch 줄 앞의 pre-created saved=false)", activity("pre-created saved=false ") { it < launch.sequence })
        assertTrue("$what: 그 인스턴스의 created 가 launch 줄 뒤에 있다", activity("created ") { it > launch.sequence })
        assertTrue("$what: A 뒤 새 인스턴스의 launch·resumed", launch.nanos > seen.a && later.any {
            it.sequence > launch.sequence && it.nanos <= seen.c && it.stream == "activity" && it.text.startsWith("MainActivity#$id resumed ")
        })
        assertEquals("$what: 시작 Intent 가 실은 네 키의 형과 값 actual=[$carried]", extras, carried)
    }

    /** One case: the premium session, row 101 armed on the screen, and the case's one cleared observation window. */
    private fun case(journey: (nonce: String, pid: Int, window: E5dJourneyNotifications.Window) -> Unit) {
        run.run { nonce ->
            premium(nonce)
            val pid = pid()
            val start = row()
            check(start == ARMED) { "premise: 시작할 때 ${ROW_ID}행이 armed 가 아니다: $start" }
            caseWindow = notifications.open(nonce)
            casePid = pid
            journey(nonce, pid, caseWindow)
            assertEquals("끝까지 같은 process 다", pid, pid())
        }
    }

    // ---- B2: a visible delivery behind, then the return

    private fun visibleBehindThenReturn(label: String, outside: Boolean) = case { nonce, pid, _ ->
        adopt(nonce, FIRED)
        val prep = adopt(nonce, ARMED)
        val id = resumed(nonce)
        val homeMark = home(nonce, pid, prep)
        serverRow(nonce, FIRED)
        val shown = visibleBehind(nonce, pid, label)
        if (outside) while (ms(prep.u, now()) < OUTSIDE_MILLIS) Thread.sleep(500)
        val post = assertBehind(nonce, pid, shown, "$label 복귀 직전")
        val (returned, a) = returnThroughLauncher(nonce, label)
        Log.i(TAG, "$label return asked for at A-G=${ms(prep.g, a)}ms A-U=${ms(prep.u, a)}ms")
        val seen = watch(nonce, label, a, prep, expectGet = outside, outside = outside)
        if (outside) assertOneGetOutside(label, prep, seen) else assertNoGetInside(label, prep, seen)
        assertEquals("$label: 행", if (outside) FIRED else ARMED, seen.rowAtE)
        assertStill(seen.notifications, post, "$label 복귀 뒤")
        assertSameInstance(seen, homeMark, returned, id, label)
    }

    @Test
    fun J14_0_afterAVisibleDeliveryBehind_aReturnInsideTheWindowAsksForNothing() = visibleBehindThenReturn("B2-in", outside = false)

    @Test
    fun J14_1_afterAVisibleDeliveryBehind_aReturnOutsideTheWindowAsksOnce() = visibleBehindThenReturn("B2-out", outside = true)

    // ---- B3: a data-only sync behind, then the return

    private fun syncBehindThenReturn(label: String, inject: Boolean) = case { nonce, pid, _ ->
        adopt(nonce, FIRED)
        val prep = adopt(nonce, ARMED)
        val id = resumed(nonce)
        val homeMark = home(nonce, pid, prep)
        serverRow(nonce, FIRED)
        val behind = homeWindow
        if (inject) {
            val (ack, _) = deliver(nonce, label, SYNC, visible = false)
            assertEquals("$label: processor 의 결과", -1, ack.getInt("result"))
            assertNotEquals("premise: $label: 주입 직전에 읽은 importance 가 100 이 아니다(Home 뒤)", 100, ack.getInt("importanceBefore"))
        }
        Thread.sleep(BEHIND_MILLIS)
        val seen = notifications.read(nonce, behind, pid)
        Log.i(TAG, "$label behind posted=${seen.posted} active=${seen.active} settingsGets=+${seen.gets} typeLogs=${seen.typeLogs}")
        assertEquals("$label: 뒤에 있는 동안 게시는 없다 ${seen.posted}", 0, seen.posted.size)
        assertEquals("$label: active 도 없다 ${seen.active}", 0, seen.active.size)
        assertEquals("$label: Home 뒤 앱 callback 의 type 로그", if (inject) listOf("sync_alerts") else emptyList(), seen.typeLogs)
        val (returned, a) = returnThroughLauncher(nonce, label)
        val after = watch(nonce, label, a, prep, expectGet = inject, comparison = if (inject) 1 else 0)
        if (inject) assertOneGetInside(label, prep, after) else assertNoGetInside(label, prep, after)
        assertEquals("$label: 행", if (inject) FIRED else ARMED, after.rowAtE)
        assertEquals("$label: 사례 전체에 게시는 없다", 0, after.notifications.posted.size)
        assertSameInstance(after, homeMark, returned, id, label)
    }

    @Test
    fun J14_2_aSyncBehindIsAskedForOnceAtTheReturn_insideTheWindow() = syncBehindThenReturn("B3", inject = true)

    @Test
    fun J14_3_withNoDeliveryBehind_theSameReturnAsksForNothing() = syncBehindThenReturn("B3-control", inject = false)

    // ---- T1: the app's own post, tapped

    @Test
    fun J14_4_aTapOnTheAppsPostStartsANewActivityWithTheAppsExtras_andAsksOnce() = case { nonce, pid, window ->
        // (1) The post the app draws in front. A premise here: J13_0 is where that is the subject.
        serverRow(nonce, FIRED)
        val before = gets(record(nonce)).size
        val (ack, title) = deliver(nonce, "T1", ONCE, visible = true)
        awaitPost(window)
        val pushed = awaitRow(FIRED)
        run.settle(nonce, quietMillis = QUIET_MILLIS)
        val first = notifications.read(nonce, window, pid)
        val pushRecord = record(nonce)
        checkComparison("T1 개별 push", pushRecord, 0)
        val pushGets = gets(pushRecord).drop(before)
        check(ack.getInt("result") == -1 && ack.getInt("importanceBefore") == 100 && first.posted.size == 1 &&
            byTheApp(first.posted.single(), title) && pushGets.size == 1 && listed(pushGets.single())) {
            "premise: (1) visible once 전달·게시·GET 불일치: ack=$ack 게시=${first.posted} GET=${pushGets.map { it.text }}"
        }
        check(pushed == FIRED) { "premise: (1) visible once 화면이 fired 가 아니다: $pushed" }
        val post = first.posted.single()

        // (2) The push's own request cannot be told from its local mark on the screen, so two lists are adopted after it.
        fun prepareStepTwo(state: String): Prep = try {
            adopt(nonce, state)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("premise: (2) ${e.message}", e)
        }
        prepareStepTwo(ARMED)
        val prep1 = prepareStepTwo(FIRED)
        assertStill(notifications.read(nonce, window, pid), post, "(2) 준비 뒤")
        serverRow(nonce, ARMED)

        // (3) The shade opened and closed again: the control for the tap.
        val originalId = resumed(nonce)
        val cancelMark = upper(nonce)
        val a1 = now()
        notifications.shadeItem(title)
        notifications.closeShade(title)
        awaitPremium("(3) shade 를 닫은 뒤")
        val cancelled = watch(nonce, "(3) shade 취소", a1, prep1, expectGet = false)
        assertNoGetInside("(3) shade 취소", prep1, cancelled)
        val between = cancelled.record.filter { it.sequence > cancelMark && it.nanos >= a1 }
        Log.i(TAG, "(3) lifecycle lines=${between.filter { it.stream in LIFECYCLE }.map { "${it.stream} ${it.text}" }}")
        assertEquals("(3): 새로 만들어진 Activity 가 없다", emptyList<String>(), between.filter { it.stream == "intent" }.map { it.text })
        assertTrue("(3): process ON_STOP 이 없다", between.none { it.stream == "process" && it.text == "ON_STOP" })
        assertTrue("(3): 기존 MainActivity의 premium 화면", cancelled.premium &&
            cancelled.record.lastOrNull { it.stream == "activity" && RESUMED.matches(it.text) }?.text?.startsWith("MainActivity#$originalId resumed ") == true)
        assertEquals("(3): 행", FIRED, cancelled.rowAtE)
        assertStill(cancelled.notifications, post, "(3) shade 취소 뒤")

        // (4) The same preparation again, so that the tap's request is judged against a window of its own.
        val armedAgain = adopt(nonce, ARMED)
        val prep2 = adopt(nonce, FIRED)
        assertStill(notifications.read(nonce, window, pid), post, "(4) 준비 뒤")
        serverRow(nonce, ARMED)

        // (5) The tap.
        val tapMark = upper(nonce)
        val item = notifications.shadeItem(title)
        assertStill(notifications.read(nonce, window, pid), post, "(5) 클릭 직전")
        val a2 = now()
        item.click()
        awaitFront(nonce, a2, "(5) 탭")
        val tapped = watch(nonce, "(5) 탭", a2, prep2, expectGet = true)
        val reprep = gets(tapped.record).filter { it.nanos > cancelled.e && it.nanos <= a2 }
        check(reprep.map { it.nanos } == listOf(armedAgain.g, prep2.g)) {
            "premise: (3)–(5) 재준비 GET 불일치: ${reprep.map { it.nanos to it.text }}"
        }
        val end = tapped.notifications
        assertConsumed(end, post)
        assertTrue("(b) premium 화면이 보인다", tapped.premium)
        assertEquals("(b) 같은 process 다", pid, tapped.pid)
        assertNewInstance(tapped, tapMark, APP_EXTRAS, "(c)")
        assertOneGetInside("(d) 탭", prep2, tapped)
        assertEquals("(e) 행은 서버의 응답이다", ARMED, tapped.rowAtE)
        assertEquals("사례 전체의 앱 callback type 로그", listOf("rate_alert") + List(4) { "sync_alerts" }, end.typeLogs)
    }

    // ---- T2: the SDK's post, tapped or left

    /** T2's preparation: what it measured, and the post the SDK drew while the app was behind. */
    private class SdkPost(val prep: Prep, val homeMark: Int, val id: String, val shown: Shown)

    /** The app holds fired (adopted) and is behind, the server alone is armed again, and the SDK's post is up. */
    private fun sdkPostBehind(nonce: String, pid: Int, label: String): SdkPost {
        val prep = adopt(nonce, FIRED)
        val id = resumed(nonce)
        val homeMark = home(nonce, pid, prep)
        serverRow(nonce, ARMED)
        return SdkPost(prep, homeMark, id, visibleBehind(nonce, pid, label))
    }

    @Test
    fun J14_5_aTapOnTheSdksPostStartsANewActivityWithTheDeliverysData_andAsksOnce() = case { nonce, pid, _ ->
        val sdk = sdkPostBehind(nonce, pid, "T2")
        val tapMark = upper(nonce)
        val item = notifications.shadeItem(sdk.shown.title)
        val post = assertBehind(nonce, pid, sdk.shown, "T2 클릭 직전")
        val a = now()
        item.click()
        awaitFront(nonce, a, "T2 탭")
        val tapped = watch(nonce, "T2 탭", a, sdk.prep, expectGet = true)
        assertConsumed(tapped.notifications, post)
        assertTrue("(b) premium 화면이 보인다", tapped.premium)
        assertEquals("(b) 같은 process 다", pid, tapped.pid)
        assertNewInstance(tapped, tapMark, SDK_EXTRAS, "(c)")
        assertOneGetInside("(d) 탭", sdk.prep, tapped)
        assertEquals("(e) 행은 서버의 응답이다", ARMED, tapped.rowAtE)
    }

    @Test
    fun J14_6_withTheShadeClosedAgain_aReturnThroughTheLauncherAsksForNothing() = case { nonce, pid, _ ->
        val label = "T2-no-tap"
        val sdk = sdkPostBehind(nonce, pid, label)
        // What is in front after Home is read from the device, not asked of the package manager: on this image the HOME
        // intent resolves to another package than the launcher that is really shown.
        val inFront = sdk.prep.homePackage
        val homes = run.device.executeShellCommand(
            "cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME"
        ).lineSequence().mapNotNull { android.content.ComponentName.unflattenFromString(it.trim())?.packageName }.toSet()
        check(!inFront.isNullOrBlank() && inFront in homes && run.device.currentPackageName == inFront && inFront != E5dJourneyRun.APP_PACKAGE &&
            inFront != E5dJourneyNotifications.SYSTEM_UI) { "premise: $label: Home 화면 미확인: $inFront 후보=$homes" }
        notifications.shadeItem(sdk.shown.title)
        notifications.closeShade(sdk.shown.title)
        val deadline = System.currentTimeMillis() + SCREEN_MILLIS
        while (run.device.currentPackageName != inFront && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertEquals("$label: shade 를 닫은 뒤 Home 직후의 화면(launcher)이 다시 앞에 있다", inFront, run.device.currentPackageName)
        val post = assertBehind(nonce, pid, sdk.shown, "$label 복귀 직전")
        val (returned, a) = returnThroughLauncher(nonce, label)
        val seen = watch(nonce, label, a, sdk.prep, expectGet = false)
        assertNoGetInside(label, sdk.prep, seen)
        assertEquals("$label: 행", FIRED, seen.rowAtE)
        assertStill(seen.notifications, post, "$label 복귀 뒤")
        assertSameInstance(seen, sdk.homeMark, returned, sdk.id, label)
    }

    // ---- a new Activity with no extras

    private fun recreationWithoutExtras(label: String, behind: Boolean) = case { nonce, pid, _ ->
        val prep = adopt(nonce, FIRED)
        if (behind) home(nonce, pid, prep)
        serverRow(nonce, ARMED)
        val mark = upper(nonce)
        val a = now()
        val said = run.device.executeShellCommand(RECREATE).trim()
        Log.i(TAG, "$label am start: $said")
        check("Error" !in said) { "premise: $label: am start 가 실패했다: $said" }
        awaitFront(nonce, a, label)
        val seen = watch(nonce, label, a, prep, expectGet = false)
        assertNewInstance(seen, mark, NO_EXTRAS, label)
        assertTrue("$label: premium 화면이 보인다", seen.premium)
        assertNoGetInside(label, prep, seen)
        assertEquals("$label: 행", FIRED, seen.rowAtE)
        assertEquals("$label: 사례 전체에 게시는 없다", 0, seen.notifications.posted.size)
    }

    @Test
    fun J14_7_aNewActivityWithNoExtrasInFrontAsksForNothing() = recreationWithoutExtras("recreate-front", behind = false)

    @Test
    fun J14_8_aNewActivityWithNoExtrasFromBehindAsksForNothing() = recreationWithoutExtras("recreate-behind", behind = true)

    // ---- the fixture's command

    @Test
    fun J14_9_theAlertRowCommandRefusesWhatItDoesNotTake_andLeavesNoLine() {
        case { nonce, _, _ ->
            fun changes() = record(nonce).count { it.stream == "server" && it.text.startsWith(ROW_CHANGE) }
            val before = changes()
            fun refused(what: String, extras: Intent.() -> Unit) {
                run.client.reject("alert-row", nonce, "alert-row", extras)
                assertEquals("$what: 거절은 서버의 행 전환 줄을 남기지 않는다", before, changes())
            }
            refused("id 없음") { putExtra("arg", FIRED) }
            refused("읽히지 않는 id") { putExtra("id", "x"); putExtra("arg", FIRED) }
            refused("문자열이 아닌 id") { putExtra("id", ROW_ID); putExtra("arg", FIRED) }
            refused("없는 행") { putExtra("id", "102"); putExtra("arg", FIRED) }
            refused("arg 없음") { putExtra("id", ROW_ID.toString()) }
            refused("모르는 arg") { putExtra("id", ROW_ID.toString()); putExtra("arg", "other") }
            refused("문자열이 아닌 arg") { putExtra("id", ROW_ID.toString()); putExtra("arg", 1) }
            // What the screen can say after them: one more list, and row 101 as it was.
            val refusedRecord = record(nonce)
            checkComparison("거절", refusedRecord, 0)
            val from = refusedRecord.last().sequence
            val (ack, _) = deliver(nonce, "after-refusals", SYNC, visible = false)
            assertEquals("processor 의 결과", -1, ack.getInt("result"))
            run.awaitRecord(nonce, "거절 뒤 sync 의 설정 GET", timeoutMillis = 20_000) { lines -> gets(E5dJourneyTraffic.parse(lines)).any { it.sequence > from } }
            run.settle(nonce, quietMillis = QUIET_MILLIS)
            val settled = record(nonce)
            checkComparison("거절 뒤 sync", settled, 1)
            val got = gets(settled).filter { it.sequence > from }
            assertEquals("거절 뒤 sync 의 은행 설정 GET 은 1건이다 ${got.map { it.text }}", 1, got.size)
            assertTrue("그 GET 은 성공한 목록 응답이다: ${got.single().text}", listed(got.single()))
            assertEquals("거절 뒤에도 ${ROW_ID}행은 armed 로 보인다", ARMED, row())
        }
    }

    private companion object {
        const val TAG = "E5dJourneyJ14"
        const val PREMIUM_MILLIS = 90_000L
        const val SETTINGS_GET = "GET /api/notification-settings "
        const val LISTED = " -> alerts list n="
        const val ROW_CHANGE = "alerts row "

        const val ROW_ID = 101
        const val ROW = "bank-alert-row-101"
        const val TOGGLE = "bank-alert-toggle-101"
        const val SENT = "알림 발송됨"
        const val ARMED = "armed"
        const val FIRED = "fired"

        /** The contract's times (`u3a2_contract.agreed.r1.md` §2.2), in milliseconds from G or from U. */
        const val INSIDE_MILLIS = 20_000L
        const val WINDOW_MILLIS = 28_000L
        const val OUTSIDE_MILLIS = 40_000L

        const val ROW_MILLIS = 20_000L
        const val SCREEN_MILLIS = 30_000L
        const val POST_MILLIS = 10_000L
        const val QUIET_MILLIS = 1_000L
        const val BEHIND_MILLIS = 1_500L

        /** A new MainActivity that carries no extras: the flags a notification's Intent asks with, and nothing else. */
        const val RECREATE = "am start -n com.jay.fxi/com.jay.fxi.MainActivity -f 0x14000000"

        /** The four keys as the app's own notification, the SDK's, and no notification at all leave them in the launch Intent. */
        const val APP_EXTRAS = "notification_type:String=rate_alert,type:absent,setting_id:Integer=101,is_repeat:String=false"
        const val SDK_EXTRAS = "notification_type:absent,type:String=rate_alert,setting_id:String=101,is_repeat:String=false"
        const val NO_EXTRAS = "notification_type:absent,type:absent,setting_id:absent,is_repeat:absent"

        val LIFECYCLE = setOf("activity", "activity-lifecycle", "process")
        val RESUMED = Regex("""MainActivity#(\d+) resumed task=-?\d+""")
        val LAUNCH = Regex("""MainActivity#(\d+) launch flags=0x[0-9a-f]+ action=\S+ extras\[(.*)]""")

        /** `contracts/v2/fcm/rate-alert-once.json` data; the title's gaps are two no-break spaces each, as in the corpus. */
        val ONCE = mapOf(
            "bank" to "hana", "body" to "[ 1390 ↑이상 도달 ]", "condition" to "above", "currency" to "usd-krw", "is_repeat" to "false",
            "rate" to "1391.2", "setting_id" to "101", "threshold" to "1390.0", "title" to "📈 하나은행\u00A0\u00A0달러\u00A0\u00A01391.2", "type" to "rate_alert"
        )

        /** `contracts/v2/fcm/sync-alerts.json` data. */
        val SYNC = mapOf("type" to "sync_alerts")
    }
}
