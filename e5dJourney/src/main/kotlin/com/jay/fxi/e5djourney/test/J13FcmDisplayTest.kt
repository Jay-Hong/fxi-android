package com.jay.fxi.e5djourney.test

import android.os.Bundle
import android.util.Log
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S7 U3a, first unit (`u3a_design.claude.r2.md` §4, `s7u3a_design.codex.r2.md` Q3; user decision G-U3 "직접 주입 먼저"): who
 * shows a bank alert, and how often, when one delivery is handed to the Firebase SDK's own broadcast processor inside the app.
 * No FCM server, no Google Play services delivery and no real transport are in it: the fixture's `fcm` command builds the
 * RECEIVE intent from the journey's data strings and calls the processor the SDK's receiver calls. From there on the path is
 * the SDK's and the app's own — the real FXiMessagingService, the registry and the bank settings owner against the loopback
 * server.
 *
 * - J13_0 / J13_1: in the foreground a visible bank once (and a repeat) delivery is one post drawn by the app, none by the SDK,
 *   one settings GET, and one "Message received" line of the app's callback.
 * - J13_2: in the foreground a data-only sync is no post, one settings GET and one callback line.
 * - J13_3: after Home, ProcessLifecycleOwner has emitted ON_STOP and the app still has the same PID, a visible bank once
 *   delivery is one post drawn by the SDK, none by the app, no settings GET, and no callback line observed.
 * - J13_4: the `fcm` command refuses an input outside what it takes.
 * - J13_5: the second delivery exercises the same-process path that exposed the binder-thread regression in P6.
 *
 * A post count is the listener's over one observation window ([E5dJourneyNotifications]); "drawn by the app" is no tag and a
 * nonzero id, "drawn by the SDK" is the SDK's own tag and id 0. The data strings are the bank goldens'
 * (`app/src/test/resources/contracts/v2/fcm/rate-alert-once.json`, `rate-alert-repeat.json`, `sync-alerts.json`), copied here
 * because this APK cannot read the app's test resources; `E5dJourneyJ13GoldenDataTest` compares these copies with the corpus on
 * the JVM. The notification title carries this journey's own marker and is not the golden's. Not shown here: a delivery to a
 * killed process, a tap, the return to the app, another API level or a real device, the app online, and anything before the
 * processor — the server's send, the delivery itself, and the extras a real delivery carries. The runner puts the device in
 * airplane mode; each case reads the setting as 1 before its launch and again at the end of its clean-up, and a device that is
 * not in airplane mode fails all six cases on that premise instead of skipping them.
 */
class J13FcmDisplayTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)
    private val notifications = E5dJourneyNotifications(run)

    /** True once this case has read the device's airplane setting as on: only then is there anything of its own to clean up. */
    private var inAirplaneMode = false

    private fun airplane(): String = run.device.executeShellCommand("settings get global airplane_mode_on").trim()

    /**
     * Every case needs the device already in airplane mode and says so instead of switching it: the runner sets the mode once
     * and lets the device settle before this class runs. The setting is read as exactly 1 before the app is launched, and
     * anything else fails the case there, before a launch. The test never writes the setting. What that shows is the setting
     * at this reading and at the one [close] takes, and the settings requests at the loopback server. External traffic and SDK
     * RPC attempts are unmeasured. Each case then starts from a new process of the app, in front and past its first screen,
     * with the listener connected. The control channel is not used before the first screen: right after a force-stop the
     * provider is not there to answer yet.
     */
    @Before
    fun open() {
        val atStart = airplane()
        check(atStart == "1") { "premise: J13 은 airplane mode 가 켜진 기기에서만 돈다(실행자가 켠다). airplane_mode_on='$atStart'" }
        inAirplaneMode = true
        Log.i(TAG, "airplane at start=$atStart")
        notifications.allow()
        preview.endProcess()
        run.launch()
        run.waitText(E5dJourneyPreview.SCREEN_TITLE, timeoutMillis = E5dJourneyPreview.COLD_START_MILLIS)
    }

    /** After the start premise succeeds, clean up the listener and app and verify the setting is still 1. Always close the control client. */
    @After
    fun close() {
        try {
            if (inAirplaneMode) {
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

    /** The fixture's premium session with the bank settings loaded once: where every delivery below starts from. */
    private fun premium(nonce: String) {
        run.awaitRecord(nonce, "startup", timeoutMillis = 90_000) { lines -> texts(lines, "http").any { "-> entitlements free" in it } }
        val freeRecord = run.client.dump(nonce)
        assertComparisonRows("free", E5dJourneyTraffic.server(freeRecord.lines, 0, freeRecord.upper), 0)
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
    }

    private fun injectionStart(nonce: String): Int {
        val dump = run.client.dump(nonce)
        assertComparisonRows("주입 전 cold 목록", E5dJourneyTraffic.server(dump.lines, 0, dump.upper), 1)
        return dump.upper
    }

    private fun checkDelivery(nonce: String, from: Int, expected: Int) {
        val dump = run.client.dump(nonce)
        assertComparisonRows("주입 ($from, ${dump.upper}]", E5dJourneyTraffic.server(dump.lines, from, dump.upper), expected)
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

    private fun log(case: String, seen: E5dJourneyNotifications.Observation) {
        Log.i(TAG, "$case posted=${seen.posted} active=${seen.active} settingsGets=+${seen.gets} typeLogs=${seen.typeLogs}")
    }

    /** The reading the fixture took just before the delivery: in front (importance 100) and no keyguard. */
    private fun assertDeliveredInTheForeground(label: String, ack: JSONObject) {
        assertEquals("$label premise: 주입 직전에 읽은 importance 가 100 이고 keyguard 가 없다", listOf<Any>(100, false),
            listOf(ack.getInt("importanceBefore"), ack.getBoolean("keyguard")))
    }

    /** A visible bank delivery in the foreground: the app's one post, its callback line and one settings GET. */
    private fun foregroundVisible(case: String, data: Map<String, String>) {
        run.run { nonce ->
            premium(nonce)
            val pid = pid()
            val window = notifications.open(nonce)
            val from = injectionStart(nonce)
            val (ack, title) = deliver(nonce, case, data, visible = true)
            val seen = notifications.close(nonce, window, pid)
            checkDelivery(nonce, from, 0)
            log(case, seen)
            assertEquals("processor 의 결과", -1, ack.getInt("result"))
            assertDeliveredInTheForeground(case, ack)
            assertEquals("관측창의 게시는 1건이다 ${seen.posted}", 1, seen.posted.size)
            val post = seen.posted.single()
            assertTrue("앱이 그린 게시다(tag 없음·0이 아닌 id·채널·표지 title) $post",
                post.tag == null && post.id != 0 && post.channel == E5dJourneyNotifications.CHANNEL && post.title == title)
            assertEquals("끝에 남은 active 는 그 1건이다", listOf(Triple(post.tag, post.id, post.title)), seen.active.map { Triple(it.tag, it.id, it.title) })
            assertEquals("은행 설정 GET 이 1건 늘었다", 1, seen.gets)
            assertEquals("앱 callback 의 type 로그", listOf("rate_alert"), seen.typeLogs)
        }
    }

    @Test
    fun J13_0_inTheForegroundAVisibleBankOnceDeliveryIsOnePostByTheApp_andOneSettingsGet() = foregroundVisible("F1-once", ONCE)

    @Test
    fun J13_1_inTheForegroundAVisibleBankRepeatDeliveryIsOnePostByTheApp_andOneSettingsGet() = foregroundVisible("F1-repeat", REPEAT)

    @Test
    fun J13_2_inTheForegroundADataOnlySyncIsNoPost_andOneSettingsGet() {
        run.run { nonce ->
            premium(nonce)
            val pid = pid()
            val window = notifications.open(nonce)
            val from = injectionStart(nonce)
            val (ack, _) = deliver(nonce, "F2-sync", SYNC, visible = false)
            val seen = notifications.close(nonce, window, pid)
            checkDelivery(nonce, from, 1)
            log("F2-sync", seen)
            assertEquals("processor 의 결과", -1, ack.getInt("result"))
            assertDeliveredInTheForeground("F2-sync", ack)
            assertEquals("관측창의 게시는 없다 ${seen.posted}", 0, seen.posted.size)
            assertEquals("끝에 남은 active 도 없다 ${seen.active}", 0, seen.active.size)
            assertEquals("은행 설정 GET 이 1건 늘었다", 1, seen.gets)
            assertEquals("앱 callback 의 type 로그", listOf("sync_alerts"), seen.typeLogs)
        }
    }

    @Test
    fun J13_3_afterHomeAVisibleBankOnceDeliveryIsOnePostByTheSdk_andNoSettingsGet() {
        run.run { nonce ->
            premium(nonce)
            val pid = pid()
            val home = preview.phase(nonce, "home")
            run.device.pressHome()
            run.awaitRecord(nonce, "process ON_STOP", timeoutMillis = 20_000) { lines -> texts(after(lines, home), "process").any { it == "ON_STOP" } }
            assertEquals("premise: Home 뒤에도 같은 process 다", pid, pid())
            val window = notifications.open(nonce)
            val from = injectionStart(nonce)
            val (ack, title) = deliver(nonce, "B1-once", ONCE, visible = true)
            val seen = notifications.close(nonce, window, pid)
            checkDelivery(nonce, from, 0)
            log("B1-once", seen)
            assertEquals("processor 의 결과", -1, ack.getInt("result"))
            assertNotEquals("premise: 주입 직전에 읽은 importance 가 100 이 아니다(Home 뒤 provider call 중의 값)", 100, ack.getInt("importanceBefore"))
            assertEquals("premise: keyguard 가 없다(분기가 Home 에서 온 것이다)", false, ack.getBoolean("keyguard"))
            assertEquals("관측창의 게시는 1건이다 ${seen.posted}", 1, seen.posted.size)
            val post = seen.posted.single()
            assertTrue("SDK 가 그린 게시다(SDK tag·id 0·채널·표지 title) $post",
                post.tag?.startsWith(E5dJourneyNotifications.SDK_TAG_PREFIX) == true && post.id == 0 &&
                    post.channel == E5dJourneyNotifications.CHANNEL && post.title == title)
            assertEquals("끝에 남은 active 는 그 1건이다", listOf(Triple(post.tag, post.id, post.title)), seen.active.map { Triple(it.tag, it.id, it.title) })
            assertEquals("background 동안 은행 설정 GET 은 늘지 않았다", 0, seen.gets)
            assertEquals("대상 PID 의 callback type 로그가 관측되지 않았다", emptyList<String>(), seen.typeLogs)
            assertEquals("끝까지 같은 process 다", pid, pid())
        }
    }

    @Test
    fun J13_4_theFcmCommandRefusesAnInputOutsideWhatItTakes() {
        run.run { nonce ->
            premium(nonce)
            val window = notifications.open(nonce)
            val from = injectionStart(nonce)
            fun data(vararg pairs: Pair<String, String>) = Bundle().apply { pairs.forEach { (key, value) -> putString(key, value) } }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "sync_alerts")) }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "sync_alerts")); putExtra("mid", "") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "sync_alerts")); putExtra("mid", 7) }
            run.client.reject("fcm", nonce, "fcm") { putExtra("mid", "u3a-no-data") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "rate_alert", "google.message_id" to "x")); putExtra("mid", "u3a-reserved") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "rate_alert", "gcm.n.e" to "1")); putExtra("mid", "u3a-reserved-2") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "rate_alert", "from" to "x")); putExtra("mid", "u3a-envelope") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", Bundle().apply { putInt("setting_id", 101) }); putExtra("mid", "u3a-not-string") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "rate_alert")); putExtra("mid", "u3a-title-only"); putExtra("title", "t") }
            run.client.reject("fcm", nonce, "fcm") { putExtra("data", data("type" to "rate_alert")); putExtra("mid", "u3a-body-only"); putExtra("body", "b") }
            // A key that is there with no String behind it is neither a visible nor a data-only delivery.
            run.client.reject("fcm", nonce, "fcm") {
                putExtra("data", data("type" to "rate_alert")); putExtra("mid", "u3a-null-title"); putExtra("title", null as String?); putExtra("body", "b")
            }
            run.client.reject("fcm", nonce, "fcm") {
                putExtra("data", data("type" to "rate_alert")); putExtra("mid", "u3a-null-pair"); putExtra("title", null as String?); putExtra("body", null as String?)
            }
            run.client.reject("fcm", nonce, "fcm") {
                putExtra("data", data("type" to "rate_alert")); putExtra("mid", "u3a-not-string-pair"); putExtra("title", 1); putExtra("body", 2)
            }
            val seen = notifications.close(nonce, window, pid())
            checkDelivery(nonce, from, 0)
            log("refused", seen)
            assertEquals("거절 사례의 관측창에서 대상 패키지 게시가 관측되지 않았다 ${seen.posted}", 0, seen.posted.size)
            assertEquals("은행 설정 GET 도 늘지 않았다", 0, seen.gets)
            assertEquals("대상 PID 의 callback type 로그가 관측되지 않았다", emptyList<String>(), seen.typeLogs)
            assertTrue("거절된 요청은 record 에 fcm 줄을 남기지 않았다", texts(run.client.dump(nonce).lines, "control").none { it.startsWith("fcm ") })
        }
    }

    @Test
    fun J13_5_aSecondDeliveryInTheSameProcessIsOnePostByTheAppAgain() {
        run.run { nonce ->
            premium(nonce)
            val pid = pid()
            for (round in listOf("first", "second")) {
                run.settle(nonce)
                val window = notifications.open(nonce)
                val from = injectionStart(nonce)
                val (ack, title) = deliver(nonce, "F1-$round", ONCE, visible = true)
                val seen = notifications.close(nonce, window, pid)
                checkDelivery(nonce, from, 0)
                log("F1-$round", seen)
                assertEquals("$round: processor 의 결과", -1, ack.getInt("result"))
                assertDeliveredInTheForeground(round, ack)
                assertEquals("$round: 관측창의 게시는 1건이다 ${seen.posted}", 1, seen.posted.size)
                val post = seen.posted.single()
                assertTrue("$round: 앱이 그린 게시다(tag 없음·0이 아닌 id·채널·표지 title) $post",
                    post.tag == null && post.id != 0 && post.channel == E5dJourneyNotifications.CHANNEL && post.title == title)
                assertEquals("$round: 끝에 남은 active 는 그 1건이다", listOf(Triple(post.tag, post.id, post.title)),
                    seen.active.map { Triple(it.tag, it.id, it.title) })
                assertEquals("$round: 은행 설정 GET 이 1건 늘었다", 1, seen.gets)
                assertEquals("$round: 앱 callback 의 type 로그", listOf("rate_alert"), seen.typeLogs)
            }
            assertEquals("끝까지 같은 process 다", pid, pid())
        }
    }

    private companion object {
        const val TAG = "E5dJourneyJ13"
        const val PREMIUM_MILLIS = 90_000L
        const val SETTINGS_GET = "GET /api/notification-settings "

        /** `contracts/v2/fcm/rate-alert-once.json` data; the title's gaps are two no-break spaces each, as in the corpus. */
        val ONCE = mapOf(
            "bank" to "hana", "body" to "[ 1390 ↑이상 도달 ]", "condition" to "above", "currency" to "usd-krw", "is_repeat" to "false",
            "rate" to "1391.2", "setting_id" to "101", "threshold" to "1390.0", "title" to "📈 하나은행\u00A0\u00A0달러\u00A0\u00A01391.2", "type" to "rate_alert"
        )

        /** `contracts/v2/fcm/rate-alert-repeat.json` data; the title's gaps are two no-break spaces each, as in the corpus. */
        val REPEAT = mapOf(
            "bank" to "kb", "body" to "[ 1380 ↓이하 도달 ]", "condition" to "below", "currency" to "usd-krw", "is_repeat" to "true",
            "rate" to "1379.8", "setting_id" to "104", "threshold" to "1380.0", "title" to "📉 국민은행\u00A0\u00A0달러\u00A0\u00A01379.8", "type" to "rate_alert"
        )

        /** `contracts/v2/fcm/sync-alerts.json` data. */
        val SYNC = mapOf("type" to "sync_alerts")
    }
}
