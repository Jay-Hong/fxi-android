package com.jay.fxi.e5djourney.test

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.LATE_MILLIS
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.PAYWALL_TEXT
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.SCREEN_TITLE
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Rule
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2b2 G10 (`e5dj_j2_plan.claude.r3.md` §3 G10 and its logout sub-case, as amended by
 * `e5dj_j2_plan_amend_g10.claude.r1.md`; `s7e5dj_j2b2_design.codex.r1.md`–`r3.md`): the production journey on the real Root,
 * Route, view models, scheduler, services and REST, with the server's receipts judged phase by phase against tables written
 * before each phase.
 *
 * J10_0: a new process that starts on 달러/1d (its choice kept by the app's own store). Its startup and warm-up are collected
 * and settled first. The positive control for a free snapshot request is the first real tab-row choice of 엔화, not yet active
 * in this process: its selected period once and its other three periods once each, nothing else. A separate cache row: back on
 * 달러, a real period switch to an already warmed period, and back, cause no request — the cache's result, not a period
 * change's positive control. Then the preview: expand, open, edit, close, reopen, CTA, paywall, each with no request. On the
 * paywall, with the same owner's FreeConfirmed as baseline: the entitlements answer turns active, then the fixture's local
 * signal — the production entry a purchase success calls — and the existing fresh requery reaches PremiumConfirmed; the
 * premium Root's WebSocket upgrade, subscribe and acknowledgement, the five topic snapshots, the five REST bootstraps, the bank
 * alert list and the USD 1d graph are checked against the premium table. The purchase success handling itself is not run.
 *
 * Each phase is a part of the record from its mark to the next part's mark, with its own table. A phase is looked at early, once
 * the server is quiet; the verdict is the final account: one fixed upper for the whole record, every server line from the start
 * to it in exactly one part (the last part runs to that upper), each part judged against its table again. Anything outside a
 * table fails, whatever phase it landed in. The free snapshot's hourly refresh (about HH:31:10–31:30 KST) has no place in any
 * table: the case starts clear of HH:31:00–31:45 for its whole measured window — no clock, cache or timer is changed — and a
 * window that reaches it anyway is a premise failure. The premium part's window is a premise too: under 40 s from its first
 * graph answer, WebSocket open or REST tether snapshot to the fixed upper, so the normal tether silence re-subscribe (45 s) and
 * a long stay's periodic traffic stay outside it; this is not a regression test of that traffic. The USD 1d graph may come one
 * to six times, every adjacent pair at least 3 s apart: graph re-request candidates whose origin is not observed.
 *
 * J10_1 and J10_2 (logout sub-case): with the preview open, or its CTA held at the host's real breakpoint, the fixture identity
 * signs out — through the real auth consumers to the Login root; the sheet, the draft and the held delivery are gone (the hold
 * abandoned and cancelled), and no paywall comes late. Then it signs in again: the identity bind's fresh requery, a free answer,
 * FreeConfirmed under a new auth generation, and the reopened preview starts from its initial values. Each command is followed
 * by the fence, owner, generation, Root and hook it should cause before the next. Firebase sign-out UI is not run.
 */
@RunWith(AndroidJUnit4::class)
class J10ProductionJourneyTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)

    @Before
    fun ready() = preview.ready()

    /** A case that reached premium, or signed out and in, leaves no session behind: the next case starts from a new process. */
    @After
    fun close() {
        runCatching { preview.clear() }
        runCatching { preview.endProcess() }
        run.close()
    }

    /** A part of the measured record: the server lines after [from], up to the next part's [from] or the final upper. */
    private class Part(val name: String, val from: Int, val rules: List<Rule>)

    /** The parts in record order; the first is the startup, from the record's start. */
    private val parts = ArrayList<Part>()

    /** A phase: its mark, its table, and its part of the record (registered as it is marked). */
    private inner class Phase(val nonce: String, val name: String, val rules: List<Rule>) {
        val mark = preview.phase(nonce, name)

        init {
            parts += Part(name, mark, rules)
        }

        /**
         * An early look, once the server is quiet: this phase's lines up to the upper that dump fixed, and the problems its table
         * finds there, logged only (J2b2 r2 R2-b). The final account decides, after its premises.
         */
        fun check() {
            run.settle(nonce)
            val record = run.client.dump(nonce)
            val lines = E5dJourneyTraffic.server(record.lines, mark, record.upper)
            Log.i(TAG, "phase $name early check ($mark, ${record.upper}] server lines=${lines.size}")
            Log.i(TAG, "phase $name early problems=${E5dJourneyTraffic.problems(lines, rules)}")
        }
    }

    // J10_0 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun J10_0_freeToPremiumThroughTheRealPathsWithEveryRequestAccountedFor() {
        startClearOfCadence()
        val wallStart = System.currentTimeMillis()
        preview.restartOn(other = "유로", tab = "달러")
        run.run { nonce ->
            // Startup and warm-up of 달러/1d, collected and settled before anything else.
            parts += Part("startup", 0, STARTUP)
            val boot = run.awaitRecord(nonce, "startup·warm-up", timeoutMillis = 60_000) { lines ->
                val http = texts(lines, "http")
                PERIODS.all { p -> http.any { it.endsWith("-> free-snapshot usd $p") } } && http.any { "-> entitlements free" in it }
            }
            run.settle(nonce)
            val startup = run.client.dump(nonce)
            Log.i(TAG, "phase startup early check (0, ${startup.upper}]")
            Log.i(TAG, "phase startup early problems=${E5dJourneyTraffic.problems(E5dJourneyTraffic.server(startup.lines, 0, startup.upper), STARTUP)}")
            assertTrue("premise: 이 process 에 엔화 요청이 없다", texts(boot, "http").none { "free-snapshot jpy" in it })
            val owner = access(nonce, "startup").getJSONObject("ownedAfter")
            assertEquals("FreeConfirmed", owner.getString("state"))

            // Positive control: the first real choice of 엔화 in this process.
            val jpy = Phase(nonce, "jpy-first", PERIODS.map { snapshot("jpy", it) })
            preview.tapTab("엔화")
            preview.heading("엔화")
            run.waitText("정상")
            run.awaitRecord(nonce, "엔화 warm-up", timeoutMillis = 30_000) { lines ->
                val http = texts(after(lines, jpy.mark), "http")
                PERIODS.all { p -> http.any { it.endsWith("-> free-snapshot jpy $p") } }
            }
            jpy.check()

            // Cache row: back on 달러, a warmed period and back — no request.
            val cache = Phase(nonce, "usd-cache", emptyList())
            preview.tapTab("달러")
            preview.heading("달러")
            for (period in listOf("1w", "1d")) {
                run.reveal("period_tab:$period").click()
                awaitSelected("period_tab:$period")
            }
            cache.check()

            // The preview, phase by phase, on 달러/1d: no request in any of them.
            Phase(nonce, "expand", emptyList()).let { p -> run.reveal(E5dJourneyPreview.TOGGLE).click(); run.device.waitForIdle(); p.check() }
            val open = Phase(nonce, "open", emptyList())
            preview.open()
            open.check()
            Phase(nonce, "edit", emptyList()).let { p -> preview.edit(); p.check() }
            Phase(nonce, "close", emptyList()).let { p -> preview.inSheet(By.text("닫기"), "닫기").click(); preview.sheetGone(); p.check() }
            Phase(nonce, "reopen", emptyList()).let { p -> preview.open(); preview.initialThreshold(); p.check() }
            val cta = Phase(nonce, "cta", emptyList())
            preview.inSheet(By.text(E5dJourneyPreview.CTA_TEXT), E5dJourneyPreview.CTA_TEXT).click()
            preview.sheetGone()
            run.waitText(PAYWALL_TEXT)
            cta.check()

            // Premium: the same owner's FreeConfirmed, the answer turned active, then the local signal.
            val baseline = access(nonce, "premium baseline")
            assertEquals("baseline FreeConfirmed", "FreeConfirmed", baseline.getJSONObject("ownedAfter").getString("state"))
            val premium = Phase(nonce, "premium", PREMIUM_ENTRY)
            run.client.ack("answer", nonce) { putExtra("arg", "active") }
            run.client.ack("local-premium", nonce)
            waitPremiumRoot()
            assertFalse("paywall 이 닫혔다", preview.paywallShown())
            val owned = access(nonce, "premium confirmed").getJSONObject("ownedAfter")
            assertEquals("PremiumConfirmed", owned.getString("state"))
            assertEquals("같은 owner", owner.getString("uid"), owned.getString("uid"))
            assertEquals("같은 세대", owner.getLong("authGeneration"), owned.getLong("authGeneration"))
            run.awaitRecord(nonce, "premium 진입", timeoutMillis = 60_000) { lines ->
                val server = E5dJourneyTraffic.server(lines, premium.mark)
                TOPICS.all { t -> E5dJourneyTraffic.count(server, "http GET /api/v2/topics/snapshot q=topic=\\Q$t\\E -> topic-snapshot \\Q$t\\E") >= 1 &&
                    E5dJourneyTraffic.count(server, "ws conn=\\d+ send snapshot \\Q$t\\E -> true") >= 1 } &&
                    E5dJourneyTraffic.count(server, "http GET /api/v2/graph/tab q=period=1d&tab=usd -> graph-tab usd 1d") >= 1 &&
                    E5dJourneyTraffic.count(server, "http GET /api/notification-settings q= -> alerts list .*") >= 1
            }
            premium.check()

            // The final account; the graph's intervals are read from the same fixed lines. From the local signal to the fixed upper
            // the journey only reads the screen, the access coordinator and the record: no screen, selection or refresh action.
            val account = account(nonce, wallStart, premium.mark)
            val premiumLines = account.parts.last().second
            // A second USD 1d request at least three seconds after the first is consistent with capability confirmation. That
            // interval alone does not establish its cause. Under the graph-only exception to the plan's unclassified-cause failure
            // (`s7e5dj_j2b2.codex.r2.md` S1), the first request and up to five more are admitted (rule 1..6): each further one the
            // same key answered graph-tab usd 1d, at least three seconds after the one before it — every adjacent pair — with no
            // screen, selection or refresh action and no further signal from the local signal to the fixed upper. Six is this
            // test's conservative limit, taken from one 1d recovery cycle's limit in GraphV2RequestCoordinator (six rounds); it
            // does not claim the lines were that cycle, a capability confirmation or an unconfirmed success. They are graph
            // re-request candidates whose actual origin is unobserved.
            val graphs = premiumLines.filter { it.stream == "http" && it.text.startsWith("GET /api/v2/graph/tab ") }
            Log.i(TAG, "graph requests ${graphs.map { "${it.sequence}@${it.nanos}" }}")
            assertTrue("graph 재조회 후보: 인접한 모든 쌍이 3초 이상 ${graphs.map { it.nanos }}",
                graphs.zipWithNext().all { (a, b) -> b.nanos - a.nanos >= GRAPH_GAP_NANOS })
            assertEquals("premium 구간의 control 줄은 신호 하나", listOf("local-premium signal"),
                E5dJourneyTraffic.parse(account.record.lines).filter { it.sequence > premium.mark && it.stream == "control" }.map { it.text })
        }
    }

    /** The final account's result: the record up to its fixed upper, and each part's server lines in order. */
    private class Account(val record: E5dJourneyControlClient.Dump, val parts: List<Pair<String, List<E5dJourneyTraffic.Line>>>)

    /**
     * The final account (R2-a): one dump fixes the upper; every server line from the record's start to it falls in exactly one
     * part — each part from its own mark to the next part's mark, the last to the upper — and every part is judged against its
     * table again. The parts' bounds and the upper are logged. Its premises come first, so a run that broke one is reported as
     * that premise and not as a table failure: the measured wall-clock window clear of the hourly refresh (R2-b), and the
     * premium part's window (W1, `s7e5dj_j2b2.codex.r2.md`) — from the earliest of its first graph-tab answer, WS conn=1 open
     * and first REST usdt:krw snapshot, WS open required, to this process's elapsed-realtime clock read just after the upper
     * is fixed (the record's clock, `E5dJourneyRecord`) — at least 0 and under 40 s, before a tether delivery's 45 s silence
     * window can bring a lone usdt:krw re-subscribe. The last line's time and the acknowledgement are not used for it.
     */
    private fun account(nonce: String, wallStart: Long, premiumFrom: Int): Account {
        run.settle(nonce)
        val record = run.client.dump(nonce)
        val dumpNanos = SystemClock.elapsedRealtimeNanos()
        val wallEnd = System.currentTimeMillis()
        check(cadenceOverlap(wallStart, wallEnd) == null) { "premise: the measured window $wallStart..$wallEnd reached HH:31:00–31:45 KST" }
        check(parts.firstOrNull()?.from == 0 && parts.zipWithNext().all { (a, b) -> a.from < b.from } && parts.last().from <= record.upper) {
            "parts out of order: ${parts.map { it.name to it.from }} upper ${record.upper}"
        }
        val server = E5dJourneyTraffic.server(record.lines, 0, record.upper)
        val premiumServer = server.filter { it.sequence > premiumFrom }
        val open = premiumServer.firstOrNull { it.stream == "ws" && it.text == "conn=1 open" }
        check(open != null) { "premise: the premium part has no WS conn=1 open" }
        val since = listOfNotNull(
            premiumServer.firstOrNull { it.stream == "http" && it.text.startsWith("GET /api/v2/graph/tab ") },
            open,
            premiumServer.firstOrNull { it.stream == "http" && it.text.startsWith("GET /api/v2/topics/snapshot q=topic=usdt:krw ") }
        ).minOf { it.nanos }
        val span = dumpNanos - since
        Log.i(TAG, "premium window since=$since dump=$dumpNanos span=$span ns upper=${record.upper}")
        check(span >= 0 && span < PREMIUM_WINDOW_NANOS) { "premise: the premium part's window $span ns is not within 0..40 s" }
        val ends = parts.drop(1).map { it.from } + record.upper
        val out = ArrayList<Pair<String, List<E5dJourneyTraffic.Line>>>()
        val problems = ArrayList<String>()
        for ((part, end) in parts.zip(ends)) {
            val mine = server.filter { it.sequence > part.from && it.sequence <= end }
            Log.i(TAG, "account part ${part.name} (${part.from}, $end] server lines=${mine.size}")
            out += part.name to mine
            problems += E5dJourneyTraffic.problems(mine, part.rules).map { "${part.name}: $it" }
        }
        Log.i(TAG, "account upper=${record.upper} server lines=${server.size} assigned=${out.sumOf { it.second.size }}")
        assertEquals("every server line in exactly one part", server.size, out.sumOf { it.second.size })
        assertEquals("final account", emptyList<String>(), problems)
        return Account(record, out)
    }

    /**
     * R2-b: a measured window that could reach HH:31:00–31:45 KST starts after that window instead. Nothing in the app's clock,
     * cache or timer is changed; the account checks the window that was actually measured.
     */
    private fun startClearOfCadence() {
        val now = System.currentTimeMillis()
        val until = cadenceOverlap(now, now + MEASURE_BUDGET_MILLIS)?.plus(CADENCE_MARGIN_MILLIS) ?: return
        Log.i(TAG, "starting clear of the hourly free snapshot refresh: waiting ${until - now} ms")
        Thread.sleep(until - now)
    }

    // Logout sub-case ------------------------------------------------------------------------------------------------------

    @Test
    fun J10_1_aSignOutWithThePreviewOpenLeavesNothing_andTheNextSessionStartsFresh() = logout(pending = false)

    @Test
    fun J10_2_aSignOutWithTheCtaHeldDeliversNothing_andTheNextSessionStartsFresh() = logout(pending = true)

    private fun logout(pending: Boolean) = run.run { nonce ->
        preview.choose("유로", "달러")
        val before = access(nonce).getJSONObject("ownedAfter")
        assertEquals("premise: FreeConfirmed", "FreeConfirmed", before.getString("state"))
        val token = preview.open()
        val initial = preview.initialThreshold()
        preview.edit()
        val held = if (pending) preview.holdCta(nonce, token) else null

        val out = preview.phase(nonce, "sign-out")
        run.client.ack("sign-out", nonce)
        run.waitText(LOGIN_TEXT, timeoutMillis = 30_000)
        val signedOut = run.awaitRecord(nonce, "sign-out 처리", timeoutMillis = 30_000) { lines ->
            val later = after(lines, out)
            texts(later, "auth").contains("signed-out") && texts(later, "auth").contains("fence none") &&
                (held == null || texts(later, "hook").any { it.endsWith("hold ended op=${held.operation} reason=cancelled") })
        }
        val hooks = texts(after(signedOut, out), "hook")
        if (held != null) {
            assertTrue("host 가 떠나며 hold 를 놓았다 $hooks", hooks.any { it.endsWith("hold op=${held.operation} abandoned reason=host-left") })
        }
        assertTrue("시트가 없다", preview.sheetOrNull() == null)
        assertFalse("paywall 이 없다", preview.paywallShown())
        Thread.sleep(LATE_MILLIS)
        assertFalse("늦게도 paywall 이 없다", preview.paywallShown())
        assertTrue("전달이 없다", texts(run.client.dump(nonce).lines, "hook").none { it.endsWith("event delivered $token") })
        if (held != null) {
            run.client.reject("release", nonce, "set") { putExtra("set", held.set); putExtra("operation", held.operation); putExtra("token", token) }
        }

        val back = preview.phase(nonce, "sign-in")
        run.client.ack("sign-in", nonce)
        run.waitText(SCREEN_TITLE, timeoutMillis = 30_000)
        run.awaitRecord(nonce, "sign-in 처리", timeoutMillis = 30_000) { lines ->
            val later = after(lines, back)
            texts(later, "auth").contains("signed-in") && texts(later, "auth").any { it.startsWith("fence u1 gen=") } &&
                texts(later, "http").any { "-> entitlements free" in it }
        }
        // While signed out the owner and generation may be absent (null): read without throwing until the new session settles.
        val renewed = awaitOwned(nonce) { it.optString("state") == "FreeConfirmed" && it.optLong("authGeneration", -1) > before.getLong("authGeneration") }
        assertEquals("같은 owner", before.getString("uid"), renewed.getString("uid"))
        preview.heading("달러")
        preview.open()
        assertEquals("재open: 당시 snapshot 의 임계값", initial, preview.initialThreshold())
        preview.inSheet(By.text("닫기"), "닫기").click()
        preview.sheetGone()
        assertFalse("닫기로 paywall 이 없다", preview.paywallShown())
    }

    // Steps ----------------------------------------------------------------------------------------------------------------

    /** A reading of the production access coordinator (J2b2 D5), stable or retried until it is; the reading used is logged. */
    private fun access(nonce: String, label: String = "reading"): JSONObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val reading = run.client.ack("access", nonce)
            if (reading.getBoolean("stable")) return reading.also { Log.i(TAG, "access $label $it") }
            check(System.currentTimeMillis() < deadline) { "access 관측이 안정되지 않았다: $reading" }
            Thread.sleep(200)
        }
    }

    private fun awaitOwned(nonce: String, test: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val owned = access(nonce).getJSONObject("ownedAfter")
            if (test(owned)) return owned
            check(System.currentTimeMillis() < deadline) { "access 가 오지 않았다: $owned" }
            Thread.sleep(250)
        }
    }

    private fun waitPremiumRoot() {
        val deadline = System.currentTimeMillis() + 60_000
        while (run.onScreen(By.res(PREMIUM_MARKER)).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "premium Root 가 오지 않았다. 화면: ${run.describeScreen()}" }
            Thread.sleep(250)
        }
    }

    private fun awaitSelected(tag: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (run.onScreen(By.res(tag).selected(true)).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "'$tag' 이(가) 선택되지 않았다" }
            Thread.sleep(200)
        }
    }

    /**
     * The end of the first HH:31:00–HH:31:45 KST window — around the free snapshot's hourly refresh — that the wall-clock window
     * [start]..[end] touches, or null when it touches none.
     */
    private fun cadenceOverlap(start: Long, end: Long): Long? {
        val kst = TimeZone.getTimeZone("Asia/Seoul")
        var hour = Calendar.getInstance(kst).apply { timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        while (hour <= end) {
            val from = hour + 31 * 60_000L
            val to = from + 45_000L
            if (start <= to && end >= from) return to
            hour += 3_600_000L
        }
        return null
    }

    private companion object {
        const val TAG = "E5dJourneyJ10"
        /** The least gap between two adjacent graph re-request candidates (S1). */
        const val GRAPH_GAP_NANOS = 3_000_000_000L
        /** The premium part's window, under the 45 s tether silence window (W1). */
        const val PREMIUM_WINDOW_NANOS = 40_000_000_000L
        /** The longest J10_0 is expected to measure (e5dj_j2b2_dev7: 152 s for the whole case), with room. */
        const val MEASURE_BUDGET_MILLIS = 6 * 60_000L
        const val CADENCE_MARGIN_MILLIS = 15_000L
        const val LOGIN_TEXT = "Google로 계속하기"
        const val PREMIUM_MARKER = "premium_settings"
        val PERIODS = listOf("1d", "1w", "3m", "1y")
        val TOPICS = listOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot")
        val ENTITLEMENTS_FREE = Rule("entitlements free", "http GET /api/entitlements q=(fresh_premium=true)? -> entitlements free rev=0", 1, 1)

        fun snapshot(tab: String, period: String) =
            Rule("free-snapshot $tab $period", "http GET /api/v2/free/snapshot q=period=$period&tab=$tab -> free-snapshot $tab $period", 1, 1)

        /** The startup's table: the first entitlements answer and the warm-up of 달러's four periods. */
        val STARTUP: List<Rule> = listOf(ENTITLEMENTS_FREE) + PERIODS.map { snapshot("usd", it) }

        /** The premium phase's table (G10, D2–D4): the answer switch, the probe's one active answer, and the premium entry. */
        val PREMIUM_ENTRY: List<Rule> = listOf(
            Rule("entitlements active", "http GET /api/entitlements q=fresh_premium=true -> entitlements active rev=1", 1, 1),
            Rule("ws upgrade", "http GET /ws q= -> ws-upgrade conn=1", 1, 1),
            Rule("ws open", "ws conn=1 open", 1, 1),
            Rule("ws subscribe", "ws conn=1 recv subscribe request=\\S+ token=present topics=\\[.+]", 1, 1),
            Rule("ws ack", "ws conn=1 send ack request=\\S+ accepted=\\[.+] active=\\[.+] -> true", 1, 1),
            Rule("ws snapshot", "ws conn=1 send snapshot (fx:usd-krw|fx:jpy-krw|fx:eur-krw|usdt:krw|dxy:spot) -> true", 5, 5),
            Rule("ws ping", "ws conn=1 recv ping", 0, 4),
            Rule("ws pong", "ws conn=1 send pong -> true", 0, 4),
            Rule("topic bootstrap", "http GET /api/v2/topics/snapshot q=topic=(fx:usd-krw|fx:jpy-krw|fx:eur-krw|usdt:krw|dxy:spot) -> topic-snapshot \\S+", 5, 5),
            Rule("alert list", "http GET /api/notification-settings q= -> alerts list n=\\d+ ids=\\[.*]", 1, 2),
            Rule("source alert list", "http GET /api/source-notification-settings q=asset=usdt-krw -> source settings list n=0", 1, 1),
            Rule("graph catalog", "http GET /api/v2/graph/catalog q= -> graph-catalog", 1, 1),
            Rule("graph usd 1d", "http GET /api/v2/graph/tab q=period=1d&tab=usd -> graph-tab usd 1d", 1, 6),
            Rule("register device", "http POST /api/register-device q= -> register-device token=present platform=android", 0, 1)
        )
    }
}
