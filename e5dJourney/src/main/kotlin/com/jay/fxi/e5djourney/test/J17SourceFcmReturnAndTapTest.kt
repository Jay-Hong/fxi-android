package com.jay.fxi.e5djourney.test

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Line
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Direct SDK injection into a live API 32 process. Each method owns one listener window and one final server record. */
class J17SourceFcmReturnAndTapTest {
    @get:Rule val method = TestName()
    private val case get() = method.methodName.substringBeforeLast('_')
    private val run = E5dJourneyRun()
    private val notifications = E5dJourneyNotifications(run)
    private val preview = E5dJourneyPreview(run)
    private var primaryFailure: Throwable? = null
    private var cleanUp = false
    private lateinit var nonce: String
    private var pid = 0
    private var generation = -1L
    private lateinit var window: E5dJourneyNotifications.Window
    private val accounted = mutableSetOf<Int>()
    private val types = linkedMapOf<String, List<String>>()
    private var savedPost: E5dJourneyNotificationListener.Posted? = null
    private var evidenceIndex = 0
    private var startUpper: Int? = null

    private fun <T> premise(item: String, read: () -> T): T = try {
        read()
    } catch (e: Exception) {
        throw AssertionError("PREMISE $case $item ${e.message}", e)
    } catch (e: AssertionError) {
        throw AssertionError("PREMISE $case $item ${e.message}", e)
    }

    private fun shell(command: String) = run.device.executeShellCommand(command).trim()
    private fun airplane() = shell("settings get global airplane_mode_on")

    private fun measured(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        }
    }

    @Before
    fun open() = measured {
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
            generation = premium.getLong("authGeneration")
            run.node("premium_settings", 30_000)
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
        premise("테더 빈 목록") {
            run.tap("premium_tab_TETHER")
            run.reveal("source_alert_section")
            run.reveal(By.text("설정된 거래소 알림이 없습니다"), "빈 설정")
            run.settle(nonce)
            val rows = record()
            val sources = gets(rows, SOURCE)
            val banks = gets(rows, BANK)
            check(sources.isNotEmpty() && sources.all { it.text == SOURCE_OK } &&
                banks.isNotEmpty() && banks.all { BANK_OK.matches(it.text) }) { entries(sources + banks) }
            startUpper = rows.last().sequence
            window = notifications.open(nonce)
            logWindow("open", window)
            val lock = checkNotNull(run.instrumentation.context.getSystemService(android.app.KeyguardManager::class.java))
            check(!lock.isKeyguardLocked)
        }
    }

    @After
    fun close() {
        val failures = mutableListOf<Throwable>()
        fun attempt(item: String, action: () -> Unit) {
            runCatching { premise(item, action) }.exceptionOrNull()?.let(failures::add)
        }
        if (::nonce.isInitialized) attempt("전체 record") { collect() }
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
        primaryFailure?.let { primary ->
            failures.forEach(primary::addSuppressed)
            return
        }
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
    private fun deliveryLogs(window: E5dJourneyNotifications.Window, ack: JSONObject, label: String): Logs {
        check(Build.VERSION.SDK_INT >= 31)
        val (stdout, stdin, stderr) = run.instrumentation.uiAutomation.executeShellCommandRwe(
            "logcat -b main -d -v epoch -s $TAG:I FXiMessaging:D"
        )
        stdin.close()
        val lines = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { it.readLines() }
        val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).bufferedReader().use { it.readText() }
        check(err.isBlank()) { "logcat stderr=$err" }
        val start = lines.indexOfFirst { it.endsWith("begin $nonce $label") }
        val end = lines.indexOfLast { it.endsWith("end $nonce $label") }
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

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun upper() = premise("record upper") { run.client.dump(nonce).upper }
    private fun record() = premise("record 판독") { E5dJourneyTraffic.parse(run.client.dump(nonce).lines) }
    private fun gets(rows: List<Line>, path: String) = rows.filter { it.stream == "http" && it.text.startsWith("GET $path ") }
    private fun entries(lines: List<Line>) = lines.map { listOf(it.sequence, it.nanos, it.phase, it.text) }
    private fun claim(lines: List<Line>) { lines.forEach { accounted.add(it.sequence) } }

    /** Use the same fixed dump for the footer and for the check of gaps, including traffic after E. */
    private fun collect() {
        val dump = run.client.dump(nonce)
        val rows = E5dJourneyTraffic.parse(dump.lines)
        val unexpected = dump.lines.filter { raw ->
            val fields = raw.split('\t', limit = 5)
            fields.size == 5 && fields[2] in listOf("http", "ws") && "UNEXPECTED" in fields[4]
        }
        val unclaimed = rows.filter { line ->
            val monitored = line.stream in listOf("intent", "intent-source") ||
                line.stream == "control" && line.text.startsWith("fcm ") ||
                line.stream == "server" && line.text.startsWith("source row ") ||
                line.stream == "http" && line.text.substringAfter(' ').substringBefore(' ') in listOf(SOURCE, BANK)
            monitored && startUpper?.let { line.sequence > it } == true && line.sequence !in accounted
        }
        val complete = unexpected.isEmpty() && rows.size == dump.upper && rows.all { it.nanos > 0 } && unclaimed.isEmpty()
        dump.lines.forEach { Log.i(E5dJourneyRun.RECORD_TAG, it) }
        Log.i(E5dJourneyRun.RECORD_TAG, "record ${if (complete) "complete" else "UNVERIFIED"} nonce=$nonce upper=${dump.upper}")
        check(unexpected.isEmpty()) { "표 밖의 요청 $unexpected" }
        check(complete) { "upper=${dump.upper} unclaimed=${entries(unclaimed)}" }
    }

    /** Each part fits one Android log entry, even for non-ASCII screen text. */
    private fun evidence(label: String, value: String) {
        val index = ++evidenceIndex
        val parts = value.codePoints().toArray().asList().chunked(700).map {
            val points = it.toIntArray()
            String(points, 0, points.size)
        }
        parts.forEachIndexed { i, part -> Log.i(TAG, "$case evidence=$index $label ${i + 1}/${parts.size} $part") }
    }

    private fun result(prefix: String, detail: String, ok: Boolean) {
        if (!ok) evidence("failure", "$case $prefix $detail")
        assertTrue("$case $prefix $detail", ok)
    }

    private fun logWindow(label: String, w: E5dJourneyNotifications.Window) {
        Log.i(TAG, "$case $label from=${w.from} epoch=${w.startedAt} listener=${w.state} bank=${w.gets}")
    }

    private fun observe(w: E5dJourneyNotifications.Window = window) = premise("listener 판독") {
        notifications.read(nonce, w, pid).also {
            evidence("listener", "from=${w.from} start=${w.startedAt} end=${System.currentTimeMillis()} " +
                "listener=${E5dJourneyNotificationListener.state()} posted=${it.posted} active=${it.active} types=${it.typeLogs}")
        }
    }

    private data class Delivery(val mid: String, val title: String, val types: List<String>, val before: Int, val start: Long)

    private fun inject(label: String, data: Map<String, String>, visible: Boolean, front: Boolean): Delivery = premise("주입 $label") {
        preview.phase(nonce, "$case-$label-${types.size}")
        val mark = notifications.mark(nonce, window)
        logWindow(label, mark)
        val before = upper()
        val key = "$label-${types.size}"
        Log.i(TAG, "begin $nonce $key")
        val start = now()
        val (ack, title) = deliver(nonce, case, data, visible)
        Log.i(TAG, "end $nonce $key")
        val logs = deliveryLogs(mark, ack, key)
        val after = upper()
        val controls = record().filter { it.sequence in before + 1..after && it.stream == "control" && it.text.startsWith("fcm ") }
        check(controls.map { it.text } == listOf("fcm mid=${logs.mid} visible=$visible result=-1 failure=null")) { entries(controls) }
        claim(controls)
        check(ack.getInt("result") == -1 && !ack.has("failure") && ack.getInt("pid") == pid &&
            !ack.getBoolean("keyguard") && (ack.getInt("importanceBefore") == 100) == front) { ack }
        types[key] = logs.types
        evidence("inject", "$key before=$before after=$after mid=${logs.mid} visible=$visible types=${logs.types} ack=$ack")
        Delivery(logs.mid, title, logs.types, before, start)
    }

    private data class Command(
        val answer: E5dJourneyControlClient.Answer, val before: Int, val after: Int,
        val lines: List<Line>, val id: Any?, val arg: Any?
    ) {
        private fun typed(value: Any?) = value?.let { "${it.javaClass.simpleName}:$it" } ?: "absent"
        val body get() = answer.body
        val detail get() = "command=${body.optString("command")} id=${typed(id)} arg=${typed(arg)} code=${answer.code} " +
            "reason=${body.optString("reason", "absent")} lines=${lines.size} request=${body.optString("request")} " +
            "nonce=${body.optString("nonce")} pid=${body.optInt("pid")} before=$before after=$after"
    }

    private fun command(id: Any?, arg: Any?): Command = premise("source-row 응답") {
        fun Intent.field(key: String, value: Any?) {
            when (value) {
                null -> Unit
                is String -> putExtra(key, value)
                is Int -> putExtra(key, value)
                else -> error("unsupported test input")
            }
        }
        val before = upper()
        val reply = run.client.send("source-row", nonce) { field("id", id); field("arg", arg) }
        val rows = record()
        val lines = rows.filter { it.sequence > before && it.stream == "server" && it.text.startsWith("source row ") }
        claim(lines)
        Command(reply, before, rows.last().sequence, lines, id, arg).also {
            evidence("command", "${it.detail} body=${it.body} server=${entries(lines)}")
        }
    }

    private fun accepted(cmd: Command, state: String): Boolean {
        val ackUpper = cmd.body.opt("recordUpper") as? Int ?: return false
        return cmd.answer.acked && cmd.body.opt("nonce") == nonce && cmd.body.opt("pid") == pid &&
            cmd.body.opt("id") == 102 && cmd.body.opt("state") == state &&
            ackUpper in cmd.before + 1..cmd.after && cmd.lines.singleOrNull()?.let {
                it.sequence <= ackUpper && it.text == "source row id=102 -> $state n=1"
            } == true
    }

    private fun server(state: String) = premise("서버 $state") {
        val cmd = command("102", state)
        check(accepted(cmd, state)) { cmd.detail }
    }

    private fun listed(line: Line, state: String): Boolean {
        val prefix = "GET $SOURCE q=asset=usdt-krw -> source settings list n=1 body="
        if (!line.text.startsWith(prefix)) return false
        val fired = state == FIRED
        val expected = JSONObject("""{"id":102,"user_id":"u1","source":"upbit","asset":"usdt-krw","condition":"below","threshold":1385.0,
            "created_at":"2026-08-31T10:00:00+09:00","repeat_interval_sec":null,"updated_at":null}""")
            .put("is_enabled", !fired).put("triggered", fired)
            .put("triggered_at", if (fired) "2026-09-01T09:00:00+09:00" else JSONObject.NULL)
        return runCatching {
            equalJson(JSONObject().put("settings", JSONArray().put(expected)).put("total_count", 1),
                JSONObject(line.text.removePrefix(prefix)))
        }.isSuccess
    }

    private data class Reading(
        val opens: Int, val toggles: Int, val checkable: Boolean?, val checked: Boolean?,
        val siblings: Boolean, val text: List<String>, val switches: List<List<Boolean>>
    ) {
        val state: String get() {
            val words = text.flatMap { it.split(Regex("""[\s,]+""")) }.filter { it.isNotEmpty() }
            val sent = words.zipWithNext().any { (a, b) -> a == "알림" && b == "발송됨" }
            return when {
                opens > 1 || toggles > 1 -> "ambiguous"
                opens == 0 || toggles == 0 -> "missing"
                !siblings || checkable != true || "업비트" !in words -> "inconsistent"
                checked == true && "켜짐" in words && "꺼짐" !in words && !sent -> ARMED
                checked == false && "꺼짐" in words && "켜짐" !in words && sent -> FIRED
                else -> "inconsistent"
            }
        }
        fun raw() = mapOf("id" to 102, "open" to opens, "toggle" to toggles, "checkable" to checkable,
            "checked" to checked, "siblings" to siblings, "switches" to switches, "text" to text.takeIf { opens > 0 })
    }

    private fun row(): Reading = premise("102행 트리 판독") {
        fun fields(node: UiObject2): List<String> = listOfNotNull(node.text) + run.visible(node.children).flatMap(::fields)
        fun sample(): Reading {
            val front = run.device.currentPackageName
            check(front != null && run.device.hasObject(By.pkg(front))) { "unreadable UI tree package=$front" }
            val opens = run.onScreen(By.res(ROW))
            val toggles = run.onScreen(By.res(TOGGLE))
            val open = opens.singleOrNull()
            val toggle = toggles.singleOrNull()
            return Reading(opens.size, toggles.size, toggle?.isCheckable, toggle?.isChecked,
                open != null && toggle != null && open.parent != null && open.parent == toggle.parent, opens.flatMap(::fields),
                toggles.map { listOf(it.isCheckable, it.isChecked) })
        }
        try { sample() } catch (_: StaleObjectException) { sample() }
    }

    /** Only absence/ambiguity reported by reveal is a tree result. A disconnected reader still fails collection. */
    private fun revealRow() {
        try { run.reveal(ROW) } catch (e: IllegalStateException) {
            if (e.message?.startsWith("'$ROW' 이(가)") != true) throw e
        }
    }

    private data class Prep(val g: Long, val u: Long)

    private fun syncRead(state: String, label: String, fixture: Command? = null): Prep {
        val delivery = inject(label, SYNC, visible = false, front = true)
        var shown = row()
        if (shown.opens == 0) {
            revealRow()
            shown = row()
        }
        while (shown.state != state && now() < delivery.start + INSIDE) {
            SystemClock.sleep(150)
            shown = row()
        }
        val u = now()
        premise("준비 quiet") { run.settle(nonce, quietMillis = 1_000) }
        // An idempotent command can leave the expected state visible before the asynchronous GET.
        // Command cases judge the settled response and screen, independently of G/U.
        if (fixture != null) shown = row()
        val rows = record().filter { it.sequence > delivery.before }
        val source = gets(rows, SOURCE)
        val bank = gets(rows, BANK)
        claim(source + bank)
        val obs = observe()
        val commandEvidence = fixture?.let { "${it.detail} body=${it.body} server=${entries(it.lines)}" }.orEmpty()
        val detail = "stage=$label $commandEvidence G=${source.singleOrNull()?.nanos} U=$u " +
            "row=${shown.raw()} source=${entries(source)} bank=${entries(bank)} types=${delivery.types} posted=${obs.posted} active=${obs.active}"
        evidence("sync", "$detail nonce=$nonce pid=$pid mid=${delivery.mid} before=${delivery.before}")
        val postsOk = retained(obs, savedPost)
        val ok = postsOk && source.size == 1 && (fixture != null || source.single().nanos <= u) && listed(source.single(), state) &&
            bank.size == 1 && BANK_OK.matches(bank.single().text) && shown.state == state &&
            delivery.types == listOf("sync_alerts") && (fixture != null || u <= delivery.start + INSIDE)
        if (fixture == null) premise("준비($state)") { check(ok) { detail } }
        else result(if (label == "unchanged") "거절 뒤 행 불변" else "source-row $label 본문·화면", detail, ok)
        return Prep(source.single().nanos, u)
    }

    private fun adopt(state: String): Prep = premise("준비($state)") {
        val old = row()
        check(old.state == if (state == ARMED) FIRED else ARMED) { old }
        server(state)
        syncRead(state, "adopt-$state")
    }

    private fun seed() {
        server("seed")
        syncRead(ARMED, "seed")
    }

    private fun equalJson(expected: Any, actual: Any) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                val keys = expected.keys().asSequence().toSet()
                check(keys == actual.keys().asSequence().toSet()) { "expected=$expected actual=$actual" }
                for (key in keys) equalJson(expected.get(key), actual.get(key))
            }
            expected is JSONArray && actual is JSONArray -> {
                check(expected.length() == actual.length()) { "expected=$expected actual=$actual" }
                for (i in 0 until expected.length()) equalJson(expected.get(i), actual.get(i))
            }
            expected is Number && actual is Number ->
                check(expected.toString().toBigDecimal().compareTo(actual.toString().toBigDecimal()) == 0) { "expected=$expected actual=$actual" }
            else -> check(expected == actual) { "expected=$expected (${expected.javaClass}) actual=$actual (${actual.javaClass})" }
        }
    }

    private fun five(post: E5dJourneyNotificationListener.Posted) = listOf(post.tag, post.id, post.channel, post.title, post.postTime)

    private fun five(active: E5dJourneyNotifications.Active) = listOf(active.tag, active.id, active.channel, active.title, active.postTime)

    private data class Home(val at: Long, val launcher: String, val original: String, val mark: E5dJourneyNotifications.Window)

    private fun home(): Home = premise("Home·ON_STOP") {
        val homes = shell("cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().mapNotNull { android.content.ComponentName.unflattenFromString(it.trim())?.packageName }.toSet()
        preview.phase(nonce, "$case-home")
        val mark = notifications.mark(nonce, window)
        logWindow("home", mark)
        val original = checkNotNull(lastResumed(record()))
        val at = now()
        check(run.device.pressKeyCode(KeyEvent.KEYCODE_HOME))
        run.awaitRecord(nonce, "ON_STOP", 20_000) { lines -> E5dJourneyTraffic.parse(lines).any {
            it.nanos >= at && it.stream == "process" && it.text == "ON_STOP"
        } }
        val launcher = run.device.currentPackageName
        check(launcher != null && launcher in homes && launcher != E5dJourneyRun.APP_PACKAGE) { "front=$launcher homes=$homes" }
        sameProcess()
        Log.i(TAG, "$case Home=$at launcher=$launcher pid=$pid nonce=$nonce")
        Home(at, launcher, original, mark)
    }

    private fun ownerOf(post: E5dJourneyNotificationListener.Posted) = when {
        post.tag == null && post.id != 0 -> "app"
        post.tag?.startsWith(E5dJourneyNotifications.SDK_TAG_PREFIX) == true && post.id == 0 -> "sdk"
        else -> "unknown"
    }

    private fun retained(seen: E5dJourneyNotifications.Observation, post: E5dJourneyNotificationListener.Posted?) =
        if (post == null) seen.posted.isEmpty() && seen.active.isEmpty()
        else seen.posted.map { five(it) } == listOf(five(post)) && seen.active.map { five(it) } == listOf(five(post))

    private fun backgroundGets(prep: Prep, home: Home?, end: Long, atAction: Boolean = false) {
        val rows = record().filter { it.nanos > prep.u && it.nanos <= end }
        val source = gets(rows, SOURCE)
        val bank = gets(rows, BANK)
        claim(source + bank)
        val boundary = if (atAction) "A" else "end"
        val detail = "stage=before-action sourceN=${source.size} source=${seqs(source)} bankN=${bank.size} bank=${seqs(bank)} " +
            "U=${prep.u} Home=${home?.at} $boundary=$end pid=$pid nonce=$nonce raw=${json(mapOf("source" to entries(source), "bank" to entries(bank)))}"
        premise("전경 GET") { check((source + bank).none { home == null || it.nanos < home.at }) { detail } }
        if (home != null) result("배경 GET 0", detail, source.isEmpty() && bank.isEmpty())
    }

    private fun background(prep: Prep, home: Home, delivery: Delivery?, visible: Boolean): E5dJourneyNotificationListener.Posted? {
        val seen = observe(home.mark)
        val post = seen.posted.singleOrNull()
        val detail = "stage=background Home=${home.at} end=${now()} pid=$pid nonce=$nonce mid=${delivery?.mid ?: "none"} " +
            "posted=${seen.posted} active=${seen.active} types=${seen.typeLogs}"
        val ok = if (visible) post != null && ownerOf(post) == "sdk" && post.title == delivery?.title &&
            post.channel == E5dJourneyNotifications.CHANNEL && retained(seen, post) else retained(seen, null)
        result("배경 게시", detail, ok)
        val wanted = if (delivery != null && !visible) listOf("sync_alerts") else emptyList()
        result("배경 callback", detail, seen.typeLogs == wanted && delivery?.types.orEmpty() == wanted)
        backgroundGets(prep, home, now())
        return post
    }

    private fun lastResumed(rows: List<Line>) = rows.filter { it.stream == "activity" }
        .mapNotNull { RESUMED.matchEntire(it.text)?.groupValues?.get(1) }.lastOrNull()

    /** Keep the request boundary immediately adjacent to the first navigation action. */
    private data class Navigation(val upper: Int?, val path: String)
    private fun navigate(): Navigation {
        if (!preview.premiumShown()) return Navigation(null, "premium-missing")
        if (run.onScreen(By.res(ROW)).isNotEmpty()) return Navigation(null, "auto")
        val tab = run.node("premium_tab_TETHER")
        val select = !tab.isSelected
        val n = upper()
        val movedAt = now()
        if (select) tab.click()
        revealRow()
        val path = if (select) "select-tether/reveal" else "reveal"
        Log.i(TAG, "$case navigation N=$n at=$movedAt path=$path")
        return Navigation(n, path)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quote(value)
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> value.entries.sortedBy { it.key as String }.joinToString(",", "{", "}") {
            quote(it.key as String) + ":" + json(it.value)
        }
        else -> error("unsupported evidence value ${value.javaClass}")
    }

    private fun seqs(rows: List<Line>) = json(rows.map { it.sequence })

    private data class Watch(
        val prep: Prep, val a: Long, val c: Long, val e: Long, val nav: Navigation,
        val last: Reading, val observed: E5dJourneyNotifications.Observation,
        val source: List<Line>, val bank: List<Line>, val events: List<Line>, val original: String,
        val frontAtC: Boolean, val samePremium: Boolean, val detail: String
    )

    private fun watch(prep: Prep, home: Home?, op: String, mid: String?, needOne: Boolean,
                      outside: Boolean = false, action: () -> Unit): Watch {
        val original = home?.original ?: premise("기존 Activity") { checkNotNull(lastResumed(record())) }
        val actionMark = premise("동작 listener 경계") {
            preview.phase(nonce, "$case-$op")
            notifications.mark(nonce, window)
        }
        logWindow(op, actionMark)
        evidence("action", "op=$op mid=${mid ?: "none"} G=${prep.g} U=${prep.u} Home=${home?.at} original=$original pid=$pid nonce=$nonce")
        val a = now()
        premise("$op 조작") { action() }
        val frontAtC = premise("$op 화면 판독") {
            sameProcess()
            val until = now() + INSIDE
            fun foreground(): Boolean = preview.premiumShown() && (op == "shade" || record().any {
                it.nanos > a && it.stream == "activity" && RESUMED.matches(it.text)
            })
            var front = foreground()
            while (!front && now() < until) { SystemClock.sleep(150); front = foreground() }
            front
        }
        val nav = if (op in listOf("tap", "recreate")) premise("테더 탐색") { navigate() } else Navigation(null, "retained")
        val first = row()
        val c = now()
        evidence("row-C", json(mapOf("op" to op, "C" to c, "A" to a, "row" to first.raw(), "front" to frontAtC)))
        SystemClock.sleep(8_000)
        if (needOne && !outside) {
            val rows = record().filter { it.nanos > a }
            if (gets(rows, SOURCE).isEmpty() || case == "J17_2" && gets(rows, BANK).isEmpty()) {
                while (now() <= prep.g + INSIDE) SystemClock.sleep(100)
            }
        }
        val actionEnd = observe(actionMark)
        val seen = observe()
        val identity = premise("종료 PID·owner 판독") {
            sameProcess()
            run.client.ack("access", nonce)
        }
        val owned = identity.optJSONObject("ownedAfter")
        val premium = preview.premiumShown() && identity.optBoolean("stable") && owned != null && owned.optString("uid") == "u1" &&
            owned.optLong("authGeneration") == generation && owned.optString("state") == "PremiumConfirmed"
        val last = row()
        val e = now()
        evidence("row-E", json(mapOf("op" to op, "E" to e, "row" to last.raw())))
        evidence("post-E", json(mapOf("op" to op, "posted" to seen.posted.map { five(it) },
            "active" to seen.active.map { five(it) }, "types" to actionEnd.typeLogs)))
        val rows = record().filter { it.nanos <= e }
        val later = rows.filter { it.nanos > a }
        val source = gets(later, SOURCE)
        val bank = gets(later, BANK)
        val launches = later.filter { it.stream == "intent" || it.stream == "intent-source" }
        claim(source + bank + launches)
        val pairs = launches.groupBy { it.text.substringBefore(' ') }.map { (id, lines) ->
            listOf(id, lines.filter { it.stream == "intent" }.map { it.text }, lines.filter { it.stream == "intent-source" }.map { it.text })
        }
        val sourceIntent = launches.singleOrNull { it.stream == "intent-source" }?.text
        val asset = sourceIntent?.substringAfter(",asset:", "unknown")?.removeSuffix("]")
            ?: if (launches.isEmpty()) "na" else "unknown"
        val owner = seen.posted.singleOrNull()?.let(::ownerOf) ?: if (seen.posted.isEmpty()) "none" else "multiple"
        val raw = json(mapOf("types" to (types + (op to actionEnd.typeLogs)), "posted" to seen.posted.map { five(it) },
            "active" to seen.active.map { five(it) }, "source" to entries(source), "bank" to entries(bank),
            "launches" to pairs, "rowC" to first.raw(), "rowE" to last.raw()))
        val detail = "op=$op sourceN=${source.size} source=${seqs(source)} bankN=${bank.size} bank=${seqs(bank)} " +
            "postedN=${seen.posted.size} owner=$owner activeN=${seen.active.size} asset=$asset " +
            "rowC=${first.state} rowE=${last.state} nonce=$nonce pid=$pid mid=${mid ?: "none"} " +
            "G=${prep.g} U=${prep.u} A=$a N=${nav.upper ?: "na"} C=$c E=$e raw=$raw"
        evidence("observation", detail)
        evidence("route", "op=$op nav=${nav.path} N=${nav.upper} original=$original access=$identity " +
            "activity=${entries(later.filter { it.stream == "activity" })}")
        backgroundGets(prep, home, a, atAction = true)
        premise("주입 귀속") {
            check(actionEnd.typeLogs.isEmpty() && seen.typeLogs == types.values.flatten() && later.none { it.stream == "control" && it.text.startsWith("fcm ") }) {
                "actual=${seen.typeLogs} deliveries=$types"
            }
        }
        return Watch(prep, a, c, e, nav, last, seen, source, bank, later, original, frontAtC, premium, detail)
    }

    private fun counts(w: Watch, source: Int, bank: Int?, state: String, outside: Boolean = false, shade: Boolean = false) {
        val limit = w.prep.g + INSIDE
        fun timely(lines: List<Line>, expected: Int): Boolean = when {
            expected == 0 -> w.c <= limit && w.e <= limit + 8_000_000_000L
            lines.isEmpty() -> w.c <= limit && w.e >= limit
            lines.size == 1 -> lines.single().nanos <= limit
            else -> true
        }
        premise("시간 창") {
            check(w.frontAtC && if (outside) w.a >= w.prep.u + OUTSIDE else timely(w.source, source)) { w.detail }
        }
        val phase = if (shade) "shade " else ""
        result("${phase}source GET $source", w.detail, w.source.size == source && w.source.all { listed(it, state) })
        if (w.nav.upper != null && source == 1) {
            result("tap GET 탐색 전 귀속 미확인", w.detail, w.source.single().sequence <= w.nav.upper)
        }
        if (bank != null) premise("bank 시간 창") { check(timely(w.bank, bank)) { w.detail } }
        result("${phase}bank GET ${bank ?: "정상 목록"}", w.detail,
            (bank == null || bank == w.bank.size) && w.bank.all { BANK_OK.matches(it.text) })
    }

    private fun sameActivity(w: Watch, shade: Boolean = false): Boolean {
        val id = w.original
        return w.events.none { it.stream == "intent" || it.stream == "intent-source" } &&
            (shade || w.events.any { it.stream == "activity" && it.nanos <= w.c && it.text.startsWith("MainActivity#$id resumed ") }) &&
            (lastResumed(w.events) ?: id) == id
    }

    private fun newActivity(w: Watch, extras: String, sourceExtras: String): Boolean {
        val primary = w.events.singleOrNull { it.stream == "intent" } ?: return false
        val secondary = w.events.singleOrNull { it.stream == "intent-source" } ?: return false
        val (id, fields) = LAUNCH.matchEntire(primary.text)?.destructured ?: return false
        fun event(name: String) = w.events.singleOrNull { it.stream == "activity" && it.text.startsWith("MainActivity#$id $name ") }
        val pre = event("pre-created saved=false") ?: return false
        val created = event("created") ?: return false
        return id != w.original && fields == extras && secondary.text == "MainActivity#$id extras[$sourceExtras]" &&
            pre.sequence < primary.sequence && primary.sequence < secondary.sequence && secondary.sequence < created.sequence &&
            w.events.any { it.sequence > created.sequence && it.nanos <= w.c && it.stream == "activity" && it.text.startsWith("MainActivity#$id resumed ") } &&
            lastResumed(w.events) == id
    }

    private fun launcher(visible: Boolean, sync: Boolean, outside: Boolean = false, cancelShade: Boolean = false) {
        seed()
        adopt(FIRED)
        val prep = adopt(ARMED)
        val home = home()
        server(FIRED)
        val delivery = when {
            visible -> inject("sdk-once", ONCE, visible = true, front = false)
            sync -> inject("background-sync", SYNC, visible = false, front = false)
            else -> null
        }
        SystemClock.sleep(1_500)
        val post = background(prep, home, delivery, visible)
        if (cancelShade) {
            val title = checkNotNull(delivery).title
            val before = upper()
            premise("SDK shade 열기·취소") {
                notifications.shadeItem(title)
                notifications.closeShade(title)
                val until = now() + 5_000_000_000L
                while (run.device.currentPackageName == E5dJourneyNotifications.SYSTEM_UI && now() < until) SystemClock.sleep(100)
            }
            val rows = record().filter { it.sequence > before }
            result("shade launcher 유지", "Home=${home.at} launcher=${home.launcher} front=${run.device.currentPackageName} " +
                "events=${entries(rows)}", run.device.currentPackageName == home.launcher && rows.none { it.stream == "intent" })
        }
        if (outside) while (now() < prep.u + OUTSIDE) SystemClock.sleep(250)
        val beforeReturn = background(prep, home, delivery, visible)
        result("배경 게시", "stage=return first=$post last=$beforeReturn", post == beforeReturn)
        val one = outside || sync
        val w = watch(prep, home, "launcher", delivery?.mid, one, outside) { run.launch() }
        counts(w, if (one) 1 else 0, if (outside) null else if (sync) 1 else 0, FIRED, outside)
        val wanted = if (one) FIRED else ARMED
        result("102행 $wanted", w.detail, w.last.state == wanted)
        result("게시 유지", w.detail, retained(w.observed, post))
        result("같은 Activity", w.detail, w.samePremium && sameActivity(w))
    }

    @Test fun J17_0_launcherInside() = measured { launcher(visible = true, sync = false, cancelShade = true) }

    @Test fun J17_1_launcherOutside() = measured { launcher(visible = true, sync = false, outside = true) }

    @Test fun J17_2_backgroundSync() = measured { launcher(visible = false, sync = true) }

    @Test fun J17_3_noInjection() = measured { launcher(visible = false, sync = false) }

    private fun tap(prep: Prep, home: Home?, delivery: Delivery, post: E5dJourneyNotificationListener.Posted, extras: String) {
        val item = premise("클릭할 SystemUI 항목") { notifications.shadeItem(delivery.title) }
        val before = observe()
        premise("클릭 직전 같은 게시") { check(retained(before, post)) { "posted=${before.posted} active=${before.active} key=${five(post)}" } }
        val w = watch(prep, home, "tap", delivery.mid, true) { item.click() }
        result("게시 소비 1", w.detail, w.observed.posted.map { five(it) } == listOf(five(post)) && w.observed.active.isEmpty())
        result("같은 PID premium", w.detail, w.samePremium)
        result("새 Activity extras", w.detail, newActivity(w, extras, "source:String=upbit,asset:String=usdt-krw"))
        counts(w, 1, 0, ARMED)
        result("102행 armed 채택", w.detail, w.last.state == ARMED)
    }

    @Test
    fun J17_4_appTap() = measured {
        seed()
        server(FIRED)
        val delivery = inject("app-once", ONCE, visible = true, front = true)
        val post = premise("전경 once 게시·채택") {
            val until = now() + INSIDE
            var shown = row()
            while (shown.state != FIRED && now() < until) { SystemClock.sleep(150); shown = row() }
            run.settle(nonce, quietMillis = 1_000)
            val seen = observe()
            val rows = record().filter { it.sequence > delivery.before }
            val source = gets(rows, SOURCE)
            val bank = gets(rows, BANK)
            claim(source + bank)
            val p = seen.posted.singleOrNull()
            val detail = "row=${shown.raw()} posted=${seen.posted} active=${seen.active} types=${delivery.types} " +
                "source=${entries(source)} bank=${entries(bank)}"
            evidence("app-once", detail)
            check(p != null && ownerOf(p) == "app" && p.channel == E5dJourneyNotifications.CHANNEL &&
                p.title == delivery.title && retained(seen, p) && delivery.types == listOf("source_rate_alert") &&
                source.size == 1 && listed(source.single(), FIRED) && bank.isEmpty() && shown.state == FIRED) { detail }
            p
        }
        savedPost = post
        fun prepare(): Prep {
            adopt(ARMED)
            val prep = adopt(FIRED)
            server(ARMED)
            return prep
        }
        val cancelled = watch(prepare(), null, "shade", delivery.mid, false) {
            notifications.shadeItem(delivery.title)
            notifications.closeShade(delivery.title)
        }
        counts(cancelled, 0, 0, ARMED, shade = true)
        result("shade 같은 Activity", cancelled.detail, sameActivity(cancelled, shade = true) && cancelled.samePremium &&
            cancelled.events.none { it.stream == "process" && it.text == "ON_STOP" })
        result("shade 102행 fired", cancelled.detail, cancelled.last.state == FIRED)
        result("shade 게시 유지", cancelled.detail, retained(cancelled.observed, post))
        tap(prepare(), null, delivery, post, APP_EXTRAS)
    }

    @Test
    fun J17_5_sdkTap() = measured {
        seed()
        val prep = adopt(FIRED)
        val home = home()
        server(ARMED)
        val delivery = inject("sdk-once", ONCE, visible = true, front = false)
        SystemClock.sleep(1_500)
        val post = checkNotNull(background(prep, home, delivery, visible = true))
        tap(prep, home, delivery, post, SDK_EXTRAS)
    }

    @Test
    fun J17_6_newWithoutExtras() = measured {
        seed()
        val prep = adopt(FIRED)
        val home = home()
        server(ARMED)
        background(prep, home, null, visible = false)
        val w = watch(prep, home, "recreate", null, false) {
            val reply = shell(RECREATE)
            check("Error" !in reply) { reply }
        }
        result("새 Activity 무extras", w.detail, newActivity(w, NO_EXTRAS, "source:absent,asset:absent") && w.samePremium)
        counts(w, 0, 0, ARMED)
        result("102행 fired", w.detail, w.last.state == FIRED)
        result("게시 0", w.detail, retained(w.observed, null))
    }

    @Test
    fun J17_7_sourceRowCommand() = measured {
        val beforeSeed = command("102", FIRED)
        val seed = command("102", "seed")
        result("source-row seed ACK", seed.detail, seed.answer.code == E5dJourneyControlClient.ACK)
        fun acceptedResult(cmd: Command, state: String) = result("source-row $state 성공 줄",
            "${cmd.detail} body=${cmd.body} server=${entries(cmd.lines)}", accepted(cmd, state))
        fun rejected(cmd: Command) = result("source-row 거절",
            "${cmd.detail} body=${cmd.body} server=${entries(cmd.lines)}", cmd.answer.code == E5dJourneyControlClient.REJECT &&
                cmd.body.opt("reason") == "source-row" && cmd.body.opt("nonce") == nonce && cmd.body.opt("pid") == pid && cmd.lines.isEmpty())
        acceptedResult(seed, "seed")
        syncRead(ARMED, "seed", seed)
        rejected(beforeSeed)
        val refusals = listOf("102" to "seed", null to FIRED, "x" to FIRED, "101" to FIRED, 102 to FIRED,
            "102" to null, "102" to 1, "102" to "other")
        val rejectedCommands = refusals.map { (id, arg) -> command(id, arg).also(::rejected) }
        syncRead(ARMED, "unchanged", rejectedCommands.last())
        for (state in listOf(FIRED, FIRED, ARMED)) {
            val cmd = command("102", state)
            acceptedResult(cmd, state)
            syncRead(state, state, cmd)
        }
        premise("끝 PID·listener") {
            sameProcess()
            val seen = observe()
            check(retained(seen, null) && seen.typeLogs == types.values.flatten()) { "posts=${seen.posted} types=${seen.typeLogs}" }
        }
    }

    private companion object {
        const val TAG = "E5dJourneyJ17"
        const val SOURCE = "/api/source-notification-settings"
        const val BANK = "/api/notification-settings"
        const val SOURCE_OK = "GET $SOURCE q=asset=usdt-krw -> source settings list n=0"
        val BANK_OK = Regex("""GET $BANK q= -> alerts list n=\d+ ids=\[(?:\d+(?:, \d+)*)?]""")
        val STARTUP_FREE = Regex("""GET /api/entitlements q=(fresh_premium=true)? -> entitlements free rev=0""")
        val LOG_LINE = Regex("""\s*(\d+)\.(\d{3})\s+(\d+)\s+\d+\s+([VDIWEF])\s+(\S+?)\s*:\s?(.*)""")
        val MID = Regex("""u3a-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}""")
        val TYPE = Regex("""Message received: type=(\S+)""")

        const val ROW = "source_alert_row_open_102"
        const val TOGGLE = "source_alert_toggle_102"
        const val ARMED = "armed"
        const val FIRED = "fired"
        const val INSIDE = 20_000_000_000L
        const val OUTSIDE = 40_000_000_000L
        const val RECREATE = "am start -n com.jay.fxi/com.jay.fxi.MainActivity -f 0x14000000"
        const val APP_EXTRAS = "notification_type:String=source_rate_alert,type:absent,setting_id:Integer=102,is_repeat:String=false"
        const val SDK_EXTRAS = "notification_type:absent,type:String=source_rate_alert,setting_id:String=102,is_repeat:String=false"
        const val NO_EXTRAS = "notification_type:absent,type:absent,setting_id:absent,is_repeat:absent"
        val RESUMED = Regex("""MainActivity#(\d+) resumed task=-?\d+""")
        val LAUNCH = Regex("""MainActivity#(\d+) launch flags=0x[0-9a-f]+ action=\S+ extras\[(.*)]""")

        val ONCE = mapOf(
            "asset" to "usdt-krw", "body" to "[ 1390 ↑이상 도달 ]",
            "condition" to "above", "is_repeat" to "false",
            "rate" to "1392.2", "setting_id" to "102",
            "source" to "upbit", "threshold" to "1390.0",
            "title" to "📈 업비트\u00A0\u00A0테더\u00A0\u00A01392.2", "type" to "source_rate_alert"
        )

        val SYNC = mapOf("type" to "sync_alerts")
    }
}
