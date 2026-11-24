package com.jay.fxi.e5djourney.test

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Line
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Direct SDK injection into a live API 32 process. Each method owns one listener window and one final server record. */
class J16SourceFcmDisplayTest {
    @get:Rule val method = TestName()
    private val case get() = method.methodName.substringBeforeLast('_')
    private val run = E5dJourneyRun()
    private val notifications = E5dJourneyNotifications(run)
    private val preview = E5dJourneyPreview(run)
    private var cleanUp = false
    private lateinit var nonce: String
    private var pid = 0
    private var recorded = false

    private fun <T> premise(item: String, read: () -> T): T = try {
        read()
    } catch (e: Exception) {
        throw AssertionError("PREMISE $case $item ${e.message}", e)
    } catch (e: AssertionError) {
        throw AssertionError("PREMISE $case $item ${e.message}", e)
    }

    private fun shell(command: String) = run.device.executeShellCommand(command).trim()
    private fun airplane() = shell("settings get global airplane_mode_on")

    @Before
    fun open() {
        premise("기기") {
            val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }
            val air = airplane()
            check(Build.VERSION.SDK_INT == 32 && avd.startsWith("Pixel_3a") && air == "1") {
                "api=${Build.VERSION.SDK_INT} avd=$avd airplane=$air"
            }
            cleanUp = true
            Log.i(TAG, "$case api=32 avd=$avd airplane=$air")
        }
        premise("listener 접속") { notifications.allow() }
        premise("새 프로세스와 첫 화면") {
            preview.endProcess()
            run.launch()
            run.waitText(E5dJourneyPreview.SCREEN_TITLE, E5dJourneyPreview.COLD_START_MILLIS)
            val discovered = run.discover()
            nonce = discovered.getString("nonce")
            pid = discovered.getInt("pid")
        }
        premise("start ready") {
            val start = run.client.ack("start", nonce)
            check(start.getInt("pid") == pid && start.getBoolean("providerAck") && start.getBoolean("processAck") &&
                start.getInt("activityAcks") >= 1 && start.getBoolean("admissionOpen") &&
                start.getBoolean("endpointsAreServer") && start.getString("identityUid") == "u1") { start }
        }
        premise("앱 알림 허용·rate_alerts") { checkNotifications() }
        premise("같은 owner premium") {
            run.awaitRecord(nonce, "free entitlements", 90_000) { lines ->
                E5dJourneyRun.texts(lines, "http").any(STARTUP_FREE::matches)
            }
            val free = owner("FreeConfirmed")
            check(free.getString("uid") == "u1") { free }
            run.client.ack("answer", nonce) { putExtra("arg", "active") }
            run.client.ack("local-premium", nonce)
            val premium = owner("PremiumConfirmed")
            check(premium.getString("uid") == free.getString("uid") &&
                premium.getLong("authGeneration") == free.getLong("authGeneration")) { "$free -> $premium" }
        }
        premise("기동 GET·서버 quiet") {
            run.awaitRecord(nonce, "bank/source 목록", 90_000) { lines ->
                val http = E5dJourneyRun.texts(lines, "http")
                SOURCE_OK in http && http.any(BANK_OK::matches)
            }
            run.settle(nonce)
        }
    }

    @After
    fun close() {
        val failures = mutableListOf<Throwable>()
        fun attempt(item: String, action: () -> Unit) {
            runCatching { premise(item, action) }.exceptionOrNull()?.let(failures::add)
        }
        if (::nonce.isInitialized && !recorded) attempt("전체 record") { run.collect(nonce, null) }
        if (cleanUp) {
            attempt("listener 권한 회수") { notifications.revoke() }
            attempt("프로세스 종료") { preview.endProcess() }
            attempt("종료 비행기 모드") {
                val air = airplane()
                Log.i(TAG, "$case airplane at end=$air")
                check(air == "1") { "airplane=$air" }
            }
        }
        attempt("client close") { run.close() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun checkNotifications() {
        val pkg = E5dJourneyRun.APP_PACKAGE
        val op = shell("cmd appops get $pkg POST_NOTIFICATION")
        check("No operations." in op || Regex("""POST_NOTIFICATION:\s*(allow|default)\b""").containsMatchIn(op)) {
            "POST_NOTIFICATION=$op"
        }
        val prefs = shell("dumpsys notification").lines()
        val header = Regex("""(?:AppSettings|PackagePreferences): ${Regex.escape(pkg)} \(\d+\).*""")
        val start = prefs.indexOfFirst { header.matches(it.trim()) }
        check(start >= 0) { "$pkg 알림 설정을 읽을 수 없다" }
        val app = prefs[start]
        val channels = prefs.drop(start + 1).takeWhile {
            it.isBlank() || it.takeWhile(Char::isWhitespace).length > app.takeWhile(Char::isWhitespace).length
        }
        val channel = channels.singleOrNull { "NotificationChannel{" in it && "mId='rate_alerts'" in it }
        val importance = channel?.let { Regex("""\bmImportance=(-?\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
        check(!Regex("""\bimportance=(NONE|0)\b""", RegexOption.IGNORE_CASE).containsMatchIn(app) &&
            importance != null && importance > 0 && "mDeleted=true" !in channel.orEmpty()) { "$app rate_alerts importance=$importance" }
    }

    private fun owner(state: String): JSONObject {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (true) {
            val access = run.client.ack("access", nonce)
            val owned = access.getJSONObject("ownedAfter")
            if (access.getBoolean("stable") && owned.getString("state") == state) return owned
            check(SystemClock.elapsedRealtime() < until) { "expected=$state access=$access" }
            SystemClock.sleep(250)
        }
    }

    @Test
    fun J16_0_foregroundOnce() = foregroundVisible(ONCE)

    @Test
    fun J16_1_foregroundDerivedRepeat() {
        val data = derivedRepeat(ONCE)
        checkDerived(ONCE, data)
        foregroundVisible(data)
    }

    private fun derivedRepeat(base: Map<String, String>) = base + ("is_repeat" to "true")

    private fun checkDerived(base: Map<String, String>, data: Map<String, String>) {
        check(data.keys == base.keys && base["is_repeat"] == "false" && data["is_repeat"] == "true" &&
            data.filter { (key, value) -> value != base[key] }.keys == setOf("is_repeat")) {
            "PREMISE J16_1 upbit once 파생 입력 base=$base data=$data"
        }
        Log.i(TAG, "$case upbit once 파생 · is_repeat=true source=${data.getValue("source")} setting_id=${data.getValue("setting_id")}")
    }

    @Test
    fun J16_2_foregroundDataOnlySync() = measure(SYNC, Post.NONE, visible = false, source = 1, bank = 1)

    @Test
    fun J16_3_liveBackgroundOnce() {
        premise("Home·ON_STOP") {
            val home = preview.phase(nonce, "home")
            check(run.device.pressHome()) { "pressHome failed" }
            run.awaitRecord(nonce, "ON_STOP", 20_000) { lines ->
                "ON_STOP" in E5dJourneyRun.texts(E5dJourneyRun.after(lines, home), "process")
            }
            sameProcess()
        }
        measure(ONCE, Post.SDK, visible = true, source = 0, bank = 0)
    }

    @Test
    fun J16_4_foregroundOffKrxRepeat() = measure(KRX_REPEAT, Post.NONE, visible = true, source = 0, bank = 0)

    private fun foregroundVisible(data: Map<String, String>) = measure(data, Post.APP, visible = true, source = 1, bank = 0)

    private enum class Post { APP, SDK, NONE }

    private fun sameProcess() {
        val now = run.discover()
        check(now.getString("nonce") == nonce && now.getInt("pid") == pid) { "expected=$nonce/$pid actual=$now" }
    }

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

    private data class Logs(val mid: String, val types: List<String>)

    /** The same logcat read supplies deliver's full mid and the PID's callback lines. Bookends make an empty read detectable. */
    private fun deliveryLogs(window: E5dJourneyNotifications.Window, ack: JSONObject): Logs {
        check(Build.VERSION.SDK_INT >= 31)
        val (stdout, stdin, stderr) = run.instrumentation.uiAutomation.executeShellCommandRwe(
            "logcat -b main -d -v epoch -s $TAG:I FXiMessaging:D"
        )
        stdin.close()
        val lines = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { it.readLines() }
        val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).bufferedReader().use { it.readText() }
        check(err.isBlank()) { "logcat stderr=$err" }
        val start = lines.indexOfFirst { it.endsWith("begin $nonce") }
        val end = lines.indexOfLast { it.endsWith("end $nonce") }
        check(start >= 0 && end > start) { "logcat boundaries begin=$start end=$end" }
        val types = mutableListOf<String>()
        val mids = mutableListOf<String>()
        val ownPid = Process.myPid()
        for (i in start..end) {
            val row = checkNotNull(LOG_LINE.matchEntire(lines[i])) { "unreadable logcat row=${lines[i]}" }
            val (sec, ms, process, level, tag, message) = row.destructured
            when {
                i == start || i == end -> check(process.toInt() == ownPid && tag == TAG && level == "I")
                tag == TAG && process.toInt() == ownPid && message.startsWith("$case delivered ") -> {
                    val mid = message.substringAfter("mid=").substringBefore(' ')
                    check(MID.matches(mid) && message == "$case delivered mid=$mid ack=$ack") { message }
                    mids += mid
                }
                tag == "FXiMessaging" && process.toInt() == pid && message.startsWith("Message received:") &&
                    sec.toLong() * 1_000 + ms.toLong() >= window.startedAt -> {
                    val match = checkNotNull(TYPE.matchEntire(message)) { "callback row=$message" }
                    check(level == "D") { "callback level=$level" }
                    types += match.groupValues[1]
                }
            }
        }
        return Logs(checkNotNull(mids.singleOrNull()) { "delivery mids=$mids" }, types)
    }

    private data class Seen(
        val posted: List<E5dJourneyNotificationListener.Posted>,
        val active: List<E5dJourneyNotifications.Active>,
        val logs: Logs
    )

    /** Shares open/active and the listener's atomic snapshot with J13. Keep the raw log read checkable even for Home. */
    private fun closeWindow(window: E5dJourneyNotifications.Window, ack: JSONObject): Seen {
        SystemClock.sleep(E5dJourneyNotifications.TRAILING_MILLIS)
        val (active, endedAt) = notifications.active(nonce)
        Log.i(TAG, "end $nonce")
        val logs = deliveryLogs(window, ack)
        val (state, posted) = E5dJourneyNotificationListener.snapshot(window.from)
        check(state.connected && state.connections == window.state.connections && state.disconnections == window.state.disconnections) {
            "listener ${window.state} -> $state"
        }
        Log.i(TAG, "$case listener=[${window.from},${state.posted}) epoch=${window.startedAt}..$endedAt")
        return Seen(posted, active, logs)
    }

    private fun measure(data: Map<String, String>, post: Post, visible: Boolean, source: Int, bank: Int) {
        Log.i(TAG, "begin $nonce")
        val window = premise("listener 관측창") { notifications.open(nonce) }
        val start = premise("시작 record·기동 GET") {
            val before = run.client.dump(nonce)
            val initial = E5dJourneyTraffic.server(before.lines, 0, before.upper)
            val sources = gets(initial, SOURCE)
            val banks = gets(initial, BANK)
            check(sources.isNotEmpty() && sources.all { it.text == SOURCE_OK } &&
                banks.isNotEmpty() && banks.all { BANK_OK.matches(it.text) }) { "source=${summary(sources)} bank=${summary(banks)}" }
            before.upper
        }
        val (ack, title) = premise("processor ACK") { deliver(nonce, case, data, visible) }
        premise("주입 process·importance·keyguard") {
            val importance = ack.getInt("importanceBefore")
            check(ack.getInt("pid") == pid && ack.getInt("result") == -1 && !ack.getBoolean("keyguard") &&
                (importance == 100) == (post != Post.SDK)) { ack }
        }
        val seen = premise("listener·active·logcat 판독") { closeWindow(window, ack) }
        val record = premise("최종 record") { run.client.dump(nonce) }
        val server = E5dJourneyTraffic.server(record.lines, start, record.upper)
        val unexpected = (E5dJourneyRun.stream(record.lines, "http") + E5dJourneyRun.stream(record.lines, "ws"))
            .filter { "UNEXPECTED" in it }
        val malformed = record.lines.firstOrNull { !RECORD_LINE.matches(it) }
        record.lines.forEach { Log.i(E5dJourneyRun.RECORD_TAG, it) }
        val status = if (unexpected.isEmpty() && malformed == null) "complete" else "UNVERIFIED"
        Log.i(E5dJourneyRun.RECORD_TAG, "record $status nonce=$nonce upper=${record.upper}")
        recorded = true
        val invalid = unexpected.takeIf { it.isNotEmpty() }?.let { IllegalStateException("PREMISE $case 표 밖의 요청 $it") }
        try {
            premise("최종 PID·주입 record") {
                sameProcess()
                check(record.upper > start && malformed == null) { "invalid record S=$start U=${record.upper} row=$malformed" }
                val controls = E5dJourneyRun.texts(record.lines.drop(start), "control").filter { it.startsWith("fcm ") }
                check(controls == listOf("fcm mid=${seen.logs.mid} visible=$visible result=-1 failure=null")) { controls }
            }
            val sourceRows = gets(server, SOURCE)
            val bankRows = gets(server, BANK)
            val detail = "types=${seen.logs.types} postedN=${seen.posted.size} posted=${seen.posted} " +
                "sourceN=${sourceRows.size} source=${summary(sourceRows)} bankN=${bankRows.size} bank=${summary(bankRows)} " +
                "activeN=${seen.active.size} active=${seen.active} title=$title nonce=$nonce pid=$pid mid=${seen.logs.mid} S=$start U=${record.upper}"
            Log.i(TAG, "$case $detail")
            if (post != Post.SDK) {
                val type = if (visible) "source_rate_alert" else "sync_alerts"
                assertTrue("$case callback $type 1 $detail", seen.logs.types == listOf(type))
            }
            val one = seen.posted.singleOrNull()
            val marked = one != null && one.channel == E5dJourneyNotifications.CHANNEL && one.title == title
            when (post) {
                Post.APP -> assertTrue("$case 앱 게시 1 $detail", marked && one != null && one.tag == null && one.id != 0)
                Post.SDK -> {
                    assertTrue("$case SDK 게시 1 $detail", marked && one != null && one.id == 0 &&
                        one.tag?.startsWith(E5dJourneyNotifications.SDK_TAG_PREFIX) == true)
                    assertTrue("$case PID callback 로그 미관측 $detail", seen.logs.types.isEmpty())
                }
                Post.NONE -> assertTrue("$case 게시 0 $detail", seen.posted.isEmpty())
            }
            assertTrue("$case source GET $source $detail", sourceRows.size == source && sourceRows.all { it.text == SOURCE_OK })
            assertTrue("$case bank GET $bank $detail", bankRows.size == bank && bankRows.all { BANK_OK.matches(it.text) })
            val active = seen.active.singleOrNull()
            val count = if (post == Post.NONE) 0 else 1
            assertTrue("$case 끝 active $count $detail", if (count == 0) seen.active.isEmpty() else
                one != null && active != null && active.tag == one.tag && active.id == one.id &&
                    active.channel == one.channel && active.title == one.title)
        } catch (failure: Throwable) {
            invalid?.let(failure::addSuppressed)
            throw failure
        }
        if (invalid != null) throw invalid
    }

    private fun gets(server: List<Line>, path: String) = server.filter { it.stream == "http" && it.text.startsWith("GET $path ") }
    private fun summary(rows: List<Line>) = rows.joinToString(prefix = "[", postfix = "]") { "#${it.sequence} ${it.text}" }

    private companion object {
        const val TAG = "E5dJourneyJ16"
        const val SOURCE = "/api/source-notification-settings"
        const val BANK = "/api/notification-settings"
        const val SOURCE_OK = "GET $SOURCE q=asset=usdt-krw -> source settings list n=0"
        val BANK_OK = Regex("""GET $BANK q= -> alerts list n=\d+ ids=\[(?:\d+(?:, \d+)*)?]""")
        val STARTUP_FREE = Regex("""GET /api/entitlements q=(fresh_premium=true)? -> entitlements free rev=0""")
        val RECORD_LINE = Regex("""\d+\t\d+\t[^\t]+\t[^\t]+\t.+""")
        val LOG_LINE = Regex("""\s*(\d+)\.(\d{3})\s+(\d+)\s+\d+\s+([VDIWEF])\s+(\S+?)\s*:\s?(.*)""")
        val MID = Regex("""u3a-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}""")
        val TYPE = Regex("""Message received: type=(\S+)""")

        val ONCE = mapOf(
            "asset" to "usdt-krw", "body" to "[ 1390 ↑이상 도달 ]",
            "condition" to "above", "is_repeat" to "false",
            "rate" to "1392.2", "setting_id" to "102",
            "source" to "upbit", "threshold" to "1390.0",
            "title" to "📈 업비트\u00A0\u00A0테더\u00A0\u00A01392.2", "type" to "source_rate_alert"
        )

        val KRX_REPEAT = mapOf(
            "asset" to "usd-krw-futures", "body" to "[ 1400 ↓이하 도달 ]",
            "condition" to "below", "is_repeat" to "true",
            "rate" to "1397.5", "setting_id" to "105",
            "source" to "krx", "threshold" to "1400.0",
            "title" to "📉 달러선물\u00A0\u00A01397.5", "type" to "source_rate_alert"
        )

        val SYNC = mapOf("type" to "sync_alerts")
    }
}
