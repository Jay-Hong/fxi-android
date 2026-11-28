package com.jay.fxi.e5djourney.test

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Line
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

class J19ComparisonDeliveryJourneyTest {
    @get:Rule val method = TestName()
    private val case get() = method.methodName.substringBeforeLast('_')
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)
    private val notifications = E5dJourneyNotifications(run)
    private val processes = mutableSetOf<String>()
    private val pids = mutableSetOf<Int>()
    private lateinit var nonce: String
    private var pid = 0
    private var ordinal = 0
    private var id = 0
    private var role = ""
    private var phase = ""
    private var mark = 0
    private var generation = -1L
    private var stageAt = 0L
    private val returnReads = mutableMapOf<String, Line>()
    private var fresh = 0L
    private val record = mutableListOf<Line>()
    private var mid = ""
    private var ticket: String? = null
    private var canonicalHash = ""
    private lateinit var homePackages: Set<String>
    private val windowIds = mutableMapOf<E5dJourneyNotifications.Window, String>()
    private var seeded = false
    private var ready = false
    private var endReading: (() -> Unit)? = null
    private var endRecord: ((List<Line>) -> Unit)? = null
    private var ui = JSONObject()
    // Both controls come from this J19_3 method, before the SDK manual refresh.
    private var armedSwitch: Bitmap? = null
    private var firedSwitch: Bitmap? = null
    private class FiredReadingFailure(message: String) : AssertionError(message)
    private lateinit var window: E5dJourneyNotifications.Window
    private val pairKeys = listOf("tab", "diff_type", "left_source", "left_asset", "right_source", "right_asset")
    private val extraKeys = pairKeys + listOf("notification_type", "type", "setting_id", "is_repeat", "source", "asset",
        "title", "body", "left_rate", "right_rate", "left_observed_at", "right_observed_at", "threshold", "operator", "spread")
    private val paths = listOf("/api/notification-settings", "/api/source-notification-settings", "/api/comparison-alerts")
    private val none = listOf(0, 0, 0)
    private val comparison = listOf(0, 0, 1)
    private val kind get() = if (id == 103) "absolute" else "signed"
    private val prefix get() = "GET ${paths.last()} q= -> comparison alerts list n="
    private val tag = "E5dJourneyJ19"

    private fun now() = SystemClock.elapsedRealtime()
    private fun shell(cmd: String) = run.device.executeShellCommand(cmd).trim()
    // Each dump fixes its own upper. Reuse only the validated prefix of this sub-run.
    private fun rows(): List<Line> = premise("record") {
        var upper: Int? = null
        val max = E5dJourneyControlClient.PAGE_MAX
        do {
            val cursor = record.size
            val body = run.client.ack("dump", nonce) {
                putExtra("cursor", cursor)
                putExtra("max", max)
                upper?.let { putExtra("upper", it) }
            }
            val page = E5dJourneyControlClient.checkPage(nonce, cursor, upper, max, body)
            val lines = E5dJourneyTraffic.parse(page.lines)
            check(lines.size == page.lines.size && lines.all { it.nanos >= 0 }) { "unreadable record after $cursor" }
            record.addAll(lines)
            upper = page.upper
        } while (page.more)
        record.toList()
    }
    private fun since(seq: Int) = rows().filter { it.sequence > seq }
    private fun texts(lines: List<Line>, stream: String) = lines.filter { it.stream == stream }.map { it.text }
    private fun counts(lines: List<Line>) = paths.map { path -> texts(lines, "http").count { it.startsWith("GET $path ") } }
    private fun boundary(label: String): Int {
        phase = label
        mark = premise("phase $label") { preview.phase(nonce, "$case-$ordinal-$label") }
        timing("phase $label")
        return mark
    }

    private fun log(label: String, actual: Any, expected: Any, ok: Boolean) {
        val process = if (::nonce.isInitialized) nonce else "undiscovered"
        val detail = JSONObject().put("case", case).put("ordinal", ordinal).put("role", role).put("phase", phase)
            .put("seq", mark).put("elapsed", now()).put("pid", pid).put("nonce", process).put("mid", mid)
            .put("label", label).put("actual", actual).put("expected", expected).put("ok", ok)
        // Logcat truncates a long record. Split by characters with a conservative UTF-8 bound.
        val parts = detail.toString().chunked(700)
        parts.forEachIndexed { i, part -> Log.i(tag, "detail $process $ordinal ${i + 1}/${parts.size} $part") }
    }

    private fun timing(stage: String, at: Long = now()) {
        val value = JSONObject().put("stage", stage).put("atMs", at).put("stepMs", at - stageAt)
        if (fresh > 0L) value.put("freshMs", fresh).put("ageMs", at - fresh)
        stageAt = at
        log("timing", value, "elapsedRealtime ms", true)
    }

    private fun result(label: String, actual: Any, expected: Any, ok: Boolean) {
        val message = "$case $label"
        log(message, actual, expected, ok)
        assertTrue(message, ok)
    }

    private fun <T> premise(label: String, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        premiseFailure(label, e)
    } catch (e: FiredReadingFailure) {
        throw e
    } catch (e: AssertionError) {
        premiseFailure(label, e)
    }

    private fun premiseFailure(label: String, e: Throwable): Nothing {
        log("premise: $label", JSONObject().put("cause", e.toString()).put("ui", ui), label, false)
        throw AssertionError("premise: $case/$ordinal $label ${e.message}", e)
    }

    private fun poll(ms: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = now() + ms
        do {
            try {
                if (condition()) return true
            } catch (_: StaleObjectException) {
                // Requery the visible window on the next sample.
            }
            SystemClock.sleep(80)
        } while (now() < end)
        return false
    }

    @Before fun open() = premise("API 32 airplane and listener") {
        check(Build.VERSION.SDK_INT == 32 && shell("settings get global airplane_mode_on") == "1")
        check(shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }.startsWith("Pixel_3a"))
        if (case in listOf("J19_2", "J19_3", "J19_4")) {
            homePackages = shell("cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().mapNotNull { android.content.ComponentName.unflattenFromString(it.trim())?.packageName }.toSet()
            check(homePackages.isNotEmpty())
        }
        notifications.allow()
    }

    @After fun close() {
        try { notifications.revoke() } finally {
            try { preview.endProcess() } finally { run.close() }
        }
    }

    private fun sub(row: Int, name: String, body: () -> Unit) {
        ordinal++
        role = name
        id = row
        ticket = null
        seeded = false
        canonicalHash = ""
        windowIds.clear()
        record.clear()
        returnReads.clear()
        fresh = 0L
        stageAt = now()
        endReading = null
        endRecord = null
        ready = false
        mid = ""
        phase = "boot"
        mark = 0
        ui = JSONObject()
        val prelaunch = case == "J19_0" && ordinal == 1
        Log.i(tag, "sub begin $case $ordinal $role")
        val boot = premise("fresh process") {
            preview.endProcess()
            check(!E5dJourneyControlClient.isolationOwed)
            if (!prelaunch) run.launch()
            run.discover()
        }
        nonce = boot.getString("nonce")
        pid = boot.getInt("pid")
        var failure: Throwable? = null
        fun cleanup(label: String, action: () -> Unit) {
            try { premise(label, action) } catch (e: Throwable) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        try {
            val negatives = mutableListOf<Refusal>()
            premise("cold") {
                check(processes.add(nonce) && pids.add(pid))
                if (prelaunch) {
                    checkProviderOnly()
                    negatives += probe("comparison-row", "not-ready") { putExtra("id", "103"); putExtra("arg", "seed") }
                    negatives += probe("comparison-read", "not-ready") { putExtra("arg", "hold") }
                    checkProviderOnly()
                    run.launch()
                }
                run.waitText(E5dJourneyPreview.SCREEN_TITLE, E5dJourneyPreview.COLD_START_MILLIS)
                sameProcess()
                val start = run.client.ack("start", nonce)
                ready = true
                val context = run.instrumentation.context
                val appUid = context.packageManager.getApplicationInfo(E5dJourneyRun.APP_PACKAGE, 0).uid
                log("start", JSONObject().put("body", start).put("appUid", appUid).put("callerUid", context.applicationInfo.uid),
                    "same PID, loopback, signed caller", true)
                check(start.getInt("pid") == pid && start.getString("baseUrl").startsWith("http://127.0.0.1:") && start.getBoolean("providerAck") && start.getBoolean("processAck") && start.getInt("activityAcks") >= 1 &&
                    start.getBoolean("admissionOpen") && start.getBoolean("endpointsAreServer") && start.getString("identityUid") == "u1")
                checkNotifications()
                val free = owner("FreeConfirmed")
                check(free.getString("uid") == "u1" && counts(rows()).last() == 0)
                run.client.ack("answer", nonce) { putExtra("arg", "active") }
                run.client.ack("local-premium", nonce)
                generation = free.getLong("authGeneration")
                sameOwner()
                run.node("premium_settings", 30_000)
                run.awaitRecord(nonce, "cold", 30_000) { "${prefix}0" in E5dJourneyRun.texts(it, "http") }
                run.settle(nonce)
                val cold = rows()
                val bootLines = texts(cold, "lifecycle")
                check(bootLines.first() == "ack provider nonce=$nonce pid=$pid" && bootLines[1] == "ack process" &&
                    bootLines.drop(2).any { it.startsWith("ack activity MainActivity#") })
                check(counts(cold).last() == 1)
                val emptyGet = cold.single { it.stream == "http" && it.text == "${prefix}0" }
                val wire = texts(cold, "comparison-http").map(::JSONObject).single { it.getInt("httpSeq") == emptyGet.sequence }
                check(wire.getString("method") == "GET" && wire.getString("path") == paths.last() && wire.isNull("encodedQuery") &&
                    wire.getInt("status") == 200 && wire.getLong("bodySize") == 0L)
                log("cold", JSONObject().put("start", start).put("free", free), "one empty premium GET", true)
                if (prelaunch) {
                    negatives += probe("comparison-row", "nonce", UUID.randomUUID().toString()) { putExtra("id", "103"); putExtra("arg", "seed") }
                    negatives += probe("comparison-row") { putExtra("id", 103); putExtra("arg", "seed") }
                    negatives += probe("comparison-row") { putExtra("id", "103"); putExtra("arg", "fired") }
                    negatives += probe("comparison-row") { putExtra("id", "104"); putExtra("arg", "seed") }
                    negatives += probe("comparison-row") { putExtra("id", "103"); putExtra("arg", "other") }
                    negatives += probe("comparison-read") { putExtra("arg", "hold") }
                }
            }
            boundary("seed")
            val seed = premise("seed envelope") {
                rowCommand("seed").also { check(it.body.getInt("pid") == pid && it.body.getString("nonce") == nonce) { it.body } }
            }
            seeded = seed.acked
            val seedLines = texts(since(mark), "server").filter { it.startsWith("comparison row ") }
            result("comparison-row seed ACK", JSONObject().put("code", seed.code).put("body", seed.body)
                .put("server", JSONArray(seedLines)), "ACK", seed.acked)
            premise("seed and observer") {
                check(seedLines == listOf("comparison row id=$id -> seed n=1"))
                validateRow(seed.body, "seed")
                val obs = texts(rows(), "comparison-observer")
                check(observerReady(obs, activity()))
                check(texts(rows(), "comparison-slot").single().endsWith("value=null"))
                if (prelaunch) {
                    negatives.forEach { verify(it) }
                    verify(probe("comparison-row") { putExtra("id", "103"); putExtra("arg", "seed") })
                    verify(probe("comparison-row") { putExtra("id", "103"); putExtra("arg", "armed"); putExtra("extra", true) })
                    hold()
                    verify(probe("comparison-read") { putExtra("arg", "hold") })
                    verify(probe("comparison-read") { putExtra("arg", "release"); putExtra("ticket", "wrong") })
                    verify(probe("comparison-read") { putExtra("arg", "release"); putExtra("ticket", 1) })
                    val used = ticket
                    release()
                    verify(probe("comparison-read") { putExtra("arg", "release"); putExtra("ticket", used) })
                }
            }
            if (case in listOf("J19_3", "J19_4")) premise("bank create and warm controls") { bankControls() }
            premise("seed read and listener baseline") {
                window = notifications.open(nonce)
                refresh(false)
                run.settle(nonce, 1_000)
                timing("seed settled")
            }
            body()
        } catch (e: Throwable) {
            failure = if (e is Exception) AssertionError("premise: $case/$ordinal $phase ${e.message}", e) else e
        } finally {
            if (ticket != null) cleanup("release") { release() }
            cleanup("observer cleanup") {
                val obs = texts(rows(), "comparison-observer")
                val bus = obs.filter { it.startsWith("event=bus-ready ") }
                if (bus.isNotEmpty()) {
                    preview.phase(nonce, "j19-observer-end")
                    check(texts(rows(), "comparison-observer").filter { it.startsWith("event=bus-end ") } ==
                        bus.map { it.replace("bus-ready", "bus-end") } && bus.size == 1)
                }
                check(if (seeded) bus.size == 1 else obs.isEmpty())
                check(texts(rows(), "comparison-gate").none { it.startsWith("expired ") })
                if (prelaunch && seeded && bus.size == 1) {
                    val from = rows().last().sequence
                    run.client.reject("phase", nonce, "phase") { putExtra("arg", "j19-observer-end") }
                    check(since(from).none { it.stream in listOf("phase", "comparison-observer") })
                }
            }
            if (failure == null) {
                try { endReading?.invoke() } catch (e: Throwable) { failure = e }
            }
            if (ready) cleanup("notifications-clear") { run.client.ack("notifications-clear", nonce) }
            cleanup("complete collect") { run.collect(nonce, null) }
            if (failure == null) {
                try { endRecord?.invoke(rows()) } catch (e: Throwable) { failure = e }
            }
            Log.i(tag, "sub end $case $ordinal $role pid=$pid nonce=$nonce suppressed=${failure?.suppressed?.size ?: 0}")
        }
        failure?.let { throw it }
    }

    private fun checkProviderOnly() {
        val lines = rows()
        check(lines.first().sequence == 1 && texts(lines, "lifecycle") == listOf("ack provider nonce=$nonce pid=$pid") &&
            lines.first().stream == "lifecycle" && counts(lines).last() == 0)
    }

    private fun sameProcess() {
        val boot = run.discover()
        check(boot.getString("nonce") == nonce && boot.getInt("pid") == pid) { boot }
    }

    private fun sameOwner() {
        val premium = owner("PremiumConfirmed")
        check(premium.getString("uid") == "u1" && premium.getLong("authGeneration") == generation)
    }

    private data class Refusal(val answer: E5dJourneyControlClient.Answer, val reason: String, val changes: List<String>)
    private fun probe(command: String, reason: String = command, target: String = nonce, extras: Intent.() -> Unit): Refusal {
        val from = rows().last().sequence
        val answer = run.client.send(command, target, extras)
        val changes = since(from).filter { it.stream == "comparison-gate" || it.stream == "server" && it.text.startsWith("comparison row") }.map { it.text }
        log("probe $command", JSONObject().put("body", answer.body).put("code", answer.code).put("changes", JSONArray(changes)), reason, !answer.acked)
        return Refusal(answer, reason, changes)
    }

    private fun states(body: JSONObject): Pair<JSONObject, JSONObject> {
        val detail = body.getJSONObject("detail")
        check(detail.keys().asSequence().toSet() == setOf("before", "after"))
        val pair = detail.getJSONObject("before") to detail.getJSONObject("after")
        for (part in listOf(pair.first, pair.second)) {
            check(part.keys().asSequence().toSet() == setOf("rowsHash", "nextComparisonId", "tickets"))
            check(part.getString("rowsHash").matches(Regex("[0-9a-f]{64}")) && part.get("nextComparisonId") == 3001)
            check(part.getJSONArray("tickets").length() <= 1)
        }
        return pair
    }

    private fun verify(probe: Refusal) {
        val body = probe.answer.body
        check(!probe.answer.acked && body.getString("nonce") == nonce && body.getInt("pid") == pid &&
            body.getString("reason") == probe.reason && probe.changes.isEmpty()) { body }
        val withDetail = probe.reason.startsWith("comparison-")
        check(body.keys().asSequence().toSet() == setOf("request", "command", "nonce", "pid", "reason") +
            if (withDetail) setOf("detail") else emptySet())
        if (withDetail) {
            val (before, after) = states(body)
            check(before.toString() == after.toString()) { body }
        } else check(!body.has("detail"))
    }

    private fun rowCommand(state: String) = run.client.send("comparison-row", nonce) {
        putExtra("id", id.toString())
        putExtra("arg", state)
    }

    private fun validateRow(body: JSONObject, state: String) {
        check(body.keys().asSequence().toSet() == setOf("request", "command", "nonce", "pid", "id", "state", "recordUpper", "detail"))
        val (before, after) = states(body)
        check(body.get("id") == id && body.getString("state") == state && body.getInt("pid") == pid &&
            rows()[body.getInt("recordUpper") - 1].text == "comparison row id=$id -> $state n=1")
        check(before.getJSONArray("tickets").length() == 0 && after.getJSONArray("tickets").length() == 0)
        val empty = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
        check(before.getString("rowsHash") == (if (state == "seed") empty else canonicalHash))
        canonicalHash = rowHash(state == "fired")
        check(after.getString("rowsHash") == canonicalHash) { body }
    }

    // Template timestamps and JSON primitive spellings participate in the fixture digest.
    private fun rowHash(fired: Boolean): String {
        val once = id == 103
        val row = sortedMapOf<String, Any?>("id" to id, "user_id" to "u1",
            "created_at" to "2026-08-31T10:00:00+09:00", "updated_at" to null, "last_notified_spread" to null,
            "tab" to (if (once) "usd" else "tether"), "diff_type" to kind,
            "left_source" to (if (once) "kb" else "upbit"), "left_asset" to (if (once) "usd-krw" else "usdt-krw"),
            "right_source" to "hana", "right_asset" to "usd-krw", "operator" to (if (once) "gte" else "lte"),
            "threshold" to (if (once) 8 else -5), "repeat_interval_sec" to (if (once) null else 300),
            "triggered" to fired, "is_enabled" to !fired)
        val array = row.entries.joinToString(",", "[{", "}]") { (key, value) ->
            JSONObject.quote(key) + ":" + if (value is String) JSONObject.quote(value) else value.toString()
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(array.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun server(state: String) = premise("server $state") {
        val answer = rowCommand(state)
        check(answer.acked) { answer.body }
        validateRow(answer.body, state)
        log("server", answer.body, state, true)
        timing("server $state")
    }

    private fun gateAck(body: JSONObject, op: String, token: String) {
        check(body.getInt("pid") == pid && body.keys().asSequence().toSet() ==
            setOf("request", "command", "nonce", "pid", "ticket", "recordUpper", "detail"))
        val (before, after) = states(body)
        check(before.getString("rowsHash") == canonicalHash && after.getString("rowsHash") == canonicalHash && body.getString("ticket") == token)
        check(rows()[body.getInt("recordUpper") - 1].text == "$op ticket=$token")
        val active = (if (op == "hold") after else before).getJSONArray("tickets")
        val empty = (if (op == "hold") before else after).getJSONArray("tickets")
        val gate = active.getJSONObject(0)
        check(empty.length() == 0 && active.length() == 1 && gate.keys().asSequence().toSet() == setOf("ticket", "state") &&
            gate.getString("ticket") == token &&
            gate.getString("state") in (if (op == "hold") listOf("held") else listOf("held", "arrived")))
        log("gate $op", body, token, true)
    }

    private fun hold() {
        val body = run.client.ack("comparison-read", nonce) { putExtra("arg", "hold") }
        val token = body.getString("ticket")
        ticket = token
        check(token == body.getString("request"))
        gateAck(body, "hold", token)
    }

    private fun release() {
        val token = checkNotNull(ticket)
        ticket = null
        val body = run.client.ack("comparison-read", nonce) { putExtra("arg", "release"); putExtra("ticket", token) }
        gateAck(body, "release", token)
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

    private fun activity(lines: List<Line> = rows()): Int = lines.last { it.stream == "activity" && " resumed task=" in it.text }
        .text.substringAfter("MainActivity#").substringBefore(' ').toInt()

    private fun observerReady(events: List<String>, activity: Int): Boolean {
        val bus = events.singleOrNull { it.startsWith("event=bus-ready ") } ?: return false
        val suffix = bus.substringAfter(" subscription=")
        return events.none { it.startsWith("event=bus-end ") } &&
            suffix.endsWith(" pendingTapEnabled=false") && events.all { it.endsWith(" subscription=$suffix") } &&
            listOf("slot-ready", "listener-ready").all { events.count { e -> e == "event=$it activity=$activity subscription=$suffix" } == 1 }
    }

    private fun entries(lines: List<Line>) = texts(lines, "intent-comparison").map(::JSONObject)

    private fun matched(pair: List<JSONObject>): Boolean {
        if (pair.size != 2) return false
        val (pre, post) = pair
        return pre.getString("edge") == "pre" && post.getString("edge") == "post" &&
            pre.getInt("pid") == pid && pre.getString("nonce") == nonce &&
            listOf("entry", "activity", "nonce", "pid", "serial", "ref", "flags", "saved").all { pre.get(it) == post.get(it) }
    }

    private fun extrasMatch(entry: JSONObject, values: Map<String, Any>): Boolean {
        val extras = entry.getJSONObject("extras")
        return extraKeys.all { key ->
            val field = extras.getJSONObject(key)
            val value = values[key]
            if (value == null) !field.getBoolean("present") && field.isNull("class") && field.isNull("value")
            else field.getBoolean("present") && field.getString("class") == value.javaClass.name && field.get("value") == value
        }
    }

    // Lifecycle records identify a replacement even when its comparison observer wrote nothing.
    private fun checkObserver(all: List<Line>, from: Int, old: Int) {
        val lines = all.filter { it.sequence > from }
        val observer = all.filter { it.stream == "comparison-observer" }
        fun installed(activity: Int, before: Int) {
            val acks = observer.filter { it.sequence < before }.map { it.text }
            check(observerReady(acks, activity)) { "activity=$activity before=$before observer=$acks" }
        }
        installed(old, from)
        val events = observer.map { it.text }
        check(observerReady(events, activity(all))) { "live observer: $events" }
        val entries = lines.filter { it.stream == "intent-comparison" }
        val payloads = entries.map { JSONObject(it.text) }
        val createdIds = texts(lines, "activity").filter { it.startsWith("MainActivity#") && " pre-created " in it }
            .map { it.substringAfter('#').substringBefore(' ').toInt() }
        val recordedCreates = payloads.filter { it.getString("entry") == "create" }.map { it.getInt("activity") }
        val subId = events.single { it.startsWith("event=bus-ready ") }.substringAfter(" subscription=").substringBefore(' ')
        for (current in (createdIds + recordedCreates).toSet()) {
            val first = entries.firstOrNull { JSONObject(it.text).getInt("activity") == current }
            checkNotNull(first) { "missing create record for $current" }
            installed(current, first.sequence)
            val slot = "activity=$current subscription=$subId value="
            check(lines.any { it.stream == "comparison-slot" && it.text.startsWith(slot) && it.sequence < first.sequence }) {
                "missing initial slot for $current"
            }
            check(lines.count { it.stream == "comparison-slot-sample" && it.text.startsWith(slot) && it.sequence > first.sequence } == 1) {
                "missing or ambiguous postCreated slot sample for $current"
            }
        }
        for (entry in entries) installed(JSONObject(entry.text).getInt("activity"), entry.sequence)
        // Multiple complete deliveries are observable. A partial record cannot establish input or consumption.
        for (pair in payloads.groupBy { it.getInt("serial") }.values) {
            check(matched(pair)) { "incomplete or unbound entry $pair" }
            for (edge in pair) {
                val fields = edge.getJSONObject("extras")
                check(fields.keys().asSequence().toSet() == extraKeys.toSet()) { "unreadable extras $fields" }
                for (key in extraKeys) {
                    val field = fields.getJSONObject(key)
                    check(field.get("present") is Boolean && field.has("class") && field.has("value")) { field }
                }
            }
        }
    }

    private fun transition(pre: JSONObject?, kind: String, old: Int, live: Int): Boolean {
        if (pre == null || pre.getString("entry") != kind || pre.getInt("activity") != live) return false
        return when (kind) {
            "create" -> old != live && pre.opt("saved") == false
            "warm" -> old == live && pre.isNull("saved")
            else -> false
        }
    }

    private fun explicit(create: Boolean, extras: String = "") {
        val flags = if (create) "0x14000000" else "0x30000000"
        val reply = shell("am start -n com.jay.fxi/com.jay.fxi.MainActivity -f $flags $extras")
        check("Error" !in reply && "Exception" !in reply) { reply }
    }

    private fun bankControls() {
        for (create in listOf(true, false)) {
            val kind = if (create) "create" else "warm"
            val sentinels = pairKeys.associateWith { "bank-$kind-$it" }
            val args = sentinels.entries.joinToString(" ") { "--es ${it.key} ${it.value}" }
            val input = if (create) "--es notification_type rate_alert --ei setting_id 101" else "--es type rate_alert --es setting_id 101"
            val expected: Map<String, Any> = if (create) sentinels + mapOf("notification_type" to "rate_alert", "setting_id" to 101)
                else sentinels + mapOf("type" to "rate_alert", "setting_id" to "101")
            check(run.device.currentPackageName == E5dJourneyRun.APP_PACKAGE)
            val old = activity()
            val from = boundary("bank-$kind")
            explicit(create, "$input $args")
            val event = "SettingTriggered(settingId=101, family=BANK)"
            check(poll { texts(since(from), "comparison-bus").any { it.endsWith("value=$event") } &&
                entries(since(from)).size >= 2 && preview.premiumShown() })
            run.settle(nonce, 1_000)
            val all = rows()
            val lines = all.filter { it.sequence > from }
            checkObserver(all, from, old)
            val pair = entries(lines)
            val slots = texts(lines, "comparison-slot").map { it.substringAfter("value=") }
            val bus = texts(lines, "comparison-bus").map { it.substringAfter("value=") }
            val requiredFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
                (if (create) Intent.FLAG_ACTIVITY_CLEAR_TOP else Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val slotSample = texts(lines, "comparison-slot-sample")
            val destroyed = texts(lines, "activity").any { it.startsWith("MainActivity#$old destroyed ") }
            val ok = matched(pair) && transition(pair[0], kind, old, activity(all)) && (!create || destroyed) &&
                (pair[0].getInt("flags") and requiredFlags) == requiredFlags &&
                (if (create) slotSample.size == 1 else slotSample.isEmpty()) && extrasMatch(pair[0], expected) && extrasMatch(pair[1], sentinels) &&
                slots == (if (create) listOf("null", event, "null") else emptyList()) && bus == listOf(event)
            log("bank-$kind", JSONArray(lines.map { "${it.sequence} ${it.stream} ${it.text}" }), expected.toString(), ok)
            check(ok)
            sameProcess()
            sameOwner()
        }
    }

    // One accessibility snapshot. Read visible descendants belonging to this edit, including
    // merged text or descriptions exposed on a child. Never look up a node by its expected value.
    private fun readRow(fired: Boolean, renew: Boolean = false): Boolean? {
        ui = JSONObject().put("id", id).put("root", JSONObject.NULL)
        val automation = run.instrumentation.uiAutomation
        // On API 32 reapplying the current service info clears this client's accessibility cache.
        if (renew) automation.serviceInfo = checkNotNull(automation.serviceInfo)
        val root = automation.rootInActiveWindow ?: return null
        var toggleBounds: Rect? = null
        val fields = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val switches = JSONArray()
        var incomplete = false
        var edits = 0
        var cards = 0
        var editBounds: String? = null
        var addVisible = false
        fun walk(node: AccessibilityNodeInfo, inCard: Boolean, inEdit: Boolean) {
            try {
                if (renew && (node === root || inEdit) && !node.refresh()) {
                    incomplete = true
                    return
                }
                val res = node.viewIdResourceName
                val visible = node.isVisibleToUser && node.packageName?.toString() == E5dJourneyRun.APP_PACKAGE
                val card = inCard || visible && res == "comparison-$kind"
                if (visible && res == "comparison-$kind") cards++
                val edit = inEdit || card && visible && res == "comparison-edit-$id"
                if (visible && card && res == "comparison-edit-$id") {
                    edits++
                    editBounds = Rect().also { node.getBoundsInScreen(it) }.toShortString()
                }
                if (visible && card && res == "comparison-$kind-add") addVisible = true
                if (visible && edit) {
                    node.text?.toString()?.split('\n', ',')?.map(String::trim)?.filter(String::isNotEmpty)?.let(fields::addAll)
                    node.contentDescription?.toString()?.takeIf(String::isNotEmpty)?.let(descriptions::add)
                }
                if (visible && card && res == "comparison-toggle-$id") {
                    toggleBounds = Rect().also { node.getBoundsInScreen(it) }
                    switches.put(JSONObject().put("checked", node.isChecked).put("checkable", node.isCheckable)
                        .put("stateDescription", node.stateDescription?.toString() ?: JSONObject.NULL))
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) walk(child, card, edit) else if (visible) incomplete = true
                }
            } finally {
                node.recycle()
            }
        }
        walk(root, false, false)
        ui = JSONObject().put("id", id).put("incomplete", incomplete).put("cards", cards).put("edits", edits).put("switches", switches)
            .put("fields", JSONArray(fields)).put("descriptions", JSONArray(descriptions))
            .put("editBounds", editBounds ?: JSONObject.NULL).put("addVisible", addVisible)
        if (case == "J19_3" && switches.length() == 1) ui.put("toggleBounds", toggleBounds?.flattenToString())
        val labels = (if (id == 103) listOf("국민↔하나", "8.0", "△ 이상") else listOf("업비트-하나", "-5", "▽ 이하")) +
            if (fired) listOf("알림 발송됨") else if (id == 106) listOf("5분마다") else emptyList()
        val desc = if (id == 103) "국민−하나 차이 8.0원 이상 벌어지면" else "업비트 vs 하나 김프 -5원 이하"
        if (incomplete || cards != 1 || edits != 1 || switches.length() != 1 || fields.size < 3 || descriptions.isEmpty()) return null
        if (id == 106 && (!addVisible || fields.size < 4)) return null
        val toggle = switches.getJSONObject(0)
        if (toggle.isNull("stateDescription")) return null
        return fields == labels && descriptions == listOf(desc) && toggle.getBoolean("checkable") &&
            toggle.getBoolean("checked") == !fired && toggle.getString("stateDescription") == if (fired) "꺼짐" else "켜짐"
    }

    private fun sampleRow(fired: Boolean, until: Long = now() + 5_000, latest: Long = until): Boolean {
        var readable: JSONObject? = null
        while (now() < until) {
            val match = try { readRow(fired) } catch (_: StaleObjectException) { null }
            val sampled = now()
            if (match != null && sampled <= latest) {
                ui.put("sampledMs", sampled)
                if (match) return true
                readable = ui
            }
            val remaining = until - sampled
            if (remaining > 0) SystemClock.sleep(minOf(80L, remaining))
        }
        ui = checkNotNull(readable) { "no unique readable row id=$id by $latest: $ui" }
        return false
    }

    private fun switchPixels(screen: Bitmap?): Bitmap? {
        val rect = Rect.unflattenFromString(ui.optString("toggleBounds")) ?: return null
        if (screen == null || rect.isEmpty || !Rect(0, 0, screen.width, screen.height).contains(rect)) return null
        val crop = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
        val copy = crop.copy(Bitmap.Config.ARGB_8888, false)
        if (crop !== screen) crop.recycle()
        return copy
    }

    private fun captureSwitch(): Bitmap? {
        return try {
            val screen = run.instrumentation.uiAutomation.takeScreenshot() ?: return null
            try { switchPixels(screen) } finally { screen.recycle() }
        } catch (e: RuntimeException) {
            Log.w("J19SwitchCapture", "switch screenshot unavailable", e)
            null
        }
    }

    // An exact match is meaningful only with two distinguishable, equally sized controls.
    private fun pixels(crop: Bitmap?): String {
        val armed = armedSwitch ?: return "unknown"
        val fired = firedSwitch ?: return "unknown"
        if (crop == null || armed.width != fired.width || armed.height != fired.height || armed.sameAs(fired)) return "unknown"
        if (crop.width != armed.width || crop.height != armed.height) return "unknown"
        val colors = IntArray(crop.width * crop.height)
        crop.getPixels(colors, 0, crop.width, 0, 0, crop.width, crop.height)
        if (colors.all { it == colors[0] }) return "unknown"
        return when {
            crop.sameAs(armed) -> "armed"
            crop.sameAs(fired) -> "fired"
            else -> "unknown"
        }
    }

    private fun readSdkFired(get: Line) {
        val automation = run.instrumentation.uiAutomation
        val until = now() + 10_000
        var reads = 0
        var seen: Boolean
        do {
            reads++
            seen = try { readRow(true, renew = true) == true } catch (_: StaleObjectException) { false }
            ui.put("sampledMs", now())
            if (seen) {
                val crop = captureSwitch()
                val state = pixels(crop)
                crop?.recycle()
                ui.put("switchPixels", state)
                if (state == "fired") break
            }
            seen = false
            SystemClock.sleep(160)
        } while (now() < until)

        fun ownerReading(): Line {
            val access = run.client.ack("access", nonce) { putExtra("arg", "sample-row-103") }
            return since(access.getInt("recordUpperBefore")).single { it.stream == "comparison-owner" }.also {
                check(it.phase == "$case-$ordinal-$phase" && it.sequence > get.sequence &&
                    it.sequence <= access.getInt("recordUpperAfter"))
            }
        }
        val line = ownerReading()
        val identity = "id=103 uid=u1 generation=$generation"
        val ownerFired = line.text == "$identity isEnabled=false triggered=true"
        val ownerArmed = line.text == "$identity isEnabled=true triggered=false"
        ui.put("ownerSeq", line.sequence).put("reads", reads)
        if (seen && ownerFired) return

        val evidence = JSONObject().put("nonce", nonce).put("pid", pid).put("phase", phase)
            .put("getSeq", get.sequence).put("ownerSeq", line.sequence).put("owner", line.text).put("lastRead", ui)
        val dir = File(checkNotNull(run.instrumentation.context.getExternalFilesDir(null)), "j193-$nonce")
        check(dir.mkdir()) { dir }
        val errors = JSONObject()
        fun capture(name: String, action: () -> Unit) {
            try { action() } catch (e: Exception) { errors.put(name, e.toString()) }
        }
        fun save(name: String, bitmap: Bitmap?) {
            capture(name) {
                checkNotNull(bitmap) { "image unavailable" }
                File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }
        save("armed-control", armedSwitch)
        save("fired-control", firedSwitch)
        fun snapshot(name: String): String {
            var matched: Boolean? = null
            capture("$name-row") { matched = readRow(true, renew = true) }
            var screen: Bitmap? = null
            capture("$name-image") { screen = automation.takeScreenshot() }
            var crop: Bitmap? = null
            capture("$name-crop") { crop = switchPixels(screen) }
            val state = pixels(crop)
            evidence.put(name, JSONObject().put("atMs", now()).put("row", ui)
                .put("matchesFired", matched ?: JSONObject.NULL).put("switchPixels", state))
            save(name, screen)
            save("$name-switch", crop)
            screen?.recycle()
            crop?.recycle()
            return state
        }
        val first = snapshot("screen-1")
        SystemClock.sleep(250)
        val last = snapshot("screen-2")
        val after = ownerReading()
        evidence.put("ownerAfterSeq", after.sequence).put("ownerAfter", after.text)
        capture("windows") { run.device.dumpWindowHierarchy(File(dir, "windows.xml")) }
        evidence.put("visible", run.describeScreen()).put("captureErrors", errors)
        val verdict = when {
            line.text != after.text -> "OWNER_UNRESOLVED"
            ownerArmed -> "OWNER_ARMED"
            !ownerFired -> "OWNER_UNRESOLVED"
            first == "armed" && last == "armed" -> "DISPLAY_ARMED"
            first == "fired" && last == "fired" -> "ACCESSIBILITY_REVIEW"
            else -> "DISPLAY_REVIEW"
        }
        evidence.put("verdict", verdict)
        File(dir, "reading.json").writeText(evidence.toString(2))
        log("fired reading", evidence, "owner and visible 103 fired", false)
        throw FiredReadingFailure("J19_3/2 fired $verdict j193-$nonce")
    }

    private fun navigate(revealRow: Boolean) {
        val tab = run.node("premium_tab_" + if (id == 103) "USD" else "TETHER")
        if (!tab.isSelected) tab.click()
        if (revealRow) {
            try { run.reveal("comparison-edit-$id") } catch (_: IllegalStateException) {
                // sampleRow will retry identification against newly acquired trees.
            }
        }
    }

    private fun listed(lines: List<Line>, fired: Boolean): Line? {
        val rowLine = lines.singleOrNull { it.stream == "http" && it.text.startsWith(prefix + "1 body=") } ?: return null
        val wire = texts(lines, "comparison-http").map(::JSONObject).singleOrNull { it.getInt("httpSeq") == rowLine.sequence } ?: return null
        if (wire.getString("method") != "GET" || wire.getString("path") != paths.last() || !wire.isNull("encodedQuery") ||
            wire.getInt("status") != 200 || wire.getLong("bodySize") != 0L || wire.getString("body") != "") return null
        val envelope = JSONObject(rowLine.text.substringAfter(" body="))
        val list = envelope.getJSONArray("alerts")
        if (envelope.getInt("total_count") != 1 || list.length() != 1) return null
        val row = list.getJSONObject(0)
        val once = id == 103
        return rowLine.takeIf { row.getInt("id") == id && row.getString("user_id") == "u1" &&
            row.getString("diff_type") == kind && row.getString("tab") == (if (once) "usd" else "tether") &&
            row.getString("left_asset") == (if (once) "usd-krw" else "usdt-krw") &&
            row.getString("left_source") == (if (once) "kb" else "upbit") && row.getString("right_source") == "hana" &&
            row.getString("right_asset") == "usd-krw" && row.getString("operator") == (if (once) "gte" else "lte") &&
            row.getDouble("threshold") == (if (once) 8.0 else -5.0) &&
            (if (once) row.isNull("repeat_interval_sec") else row.getInt("repeat_interval_sec") == 300) &&
            row.getBoolean("is_enabled") == !fired && row.getBoolean("triggered") == fired }
    }

    // Exercise the real header buttons before the comparison clock starts. Process ON_START
    // consults each owner's last successful read independently, including on a warm return.
    private fun refreshReturnFamilies() {
        returnReads.clear()
        for ((tab, tag, response) in listOf(
            Triple("TETHER", "source_alert_refresh", "GET ${paths[1]} q=asset=usdt-krw -> source settings list n=0"),
            Triple("USD", "bank-alert-refresh", "GET ${paths[0]} q= -> alerts list n=1 ids=[101]")
        )) {
            val page = run.node("premium_tab_$tab")
            if (!page.isSelected) page.click()
            val button = run.reveal(tag)
            check(button.isEnabled) { "$tag disabled" }
            val before = rows().last().sequence
            button.click()
            check(poll { since(before).any { it.stream == "http" && it.text == response } }) { "$tag: $response" }
            // A tab mount can also ensure a stale family. Use the earlier successful read.
            returnReads[tag] = since(before).first { it.stream == "http" && it.text == response }
        }
    }

    private fun refresh(fired: Boolean) {
        val manual = "manual-$kind-$fired"
        val tap = case == "J19_3" || case == "J19_4"
        if (tap) {
            boundary(manual)
            refreshReturnFamilies()
        }
        navigate(false)
        val button = run.reveal("comparison-$kind-refresh")
        val from = if (tap) rows().last().sequence else boundary(manual)
        button.click()
        check(poll { listed(since(from), fired) != null })
        val sdkFired = case == "J19_3" && ordinal == 2 && fired
        if (sdkFired) {
            // A transiently absent edit still needs an owner reading and the failure snapshot.
            try {
                run.reveal("comparison-edit-$id")
            } catch (e: RuntimeException) {
                Log.w("J19SwitchCapture", "reacquiring row after reveal", e)
            }
        } else run.reveal("comparison-edit-$id")
        // reveal(edit) stops at the first visible pixel. The add button follows the seeded
        // signed row, below its value, direction and repeat/fired note.
        if (id == 106) run.reveal("comparison-signed-add")
        if (sdkFired) readSdkFired(checkNotNull(listed(since(from), true)))
        else check(sampleRow(fired)) { ui }
        val lines = since(from)
        fresh = checkNotNull(listed(lines, fired)).nanos / 1_000_000
        check(counts(since(mark)).last() == 1)
        log("manual", ui, "id=$id fired=$fired fresh=$fresh", true)
        timing("manual adopted")
    }

    private fun allowedAbsolute(base: Map<String, String>) = base + mapOf("left_source" to "kb", "left_asset" to "usd-krw",
        "title" to base.getValue("title").replace("달러선물", "국민"))

    private fun allowedSigned(base: Map<String, String>) = base + mapOf("right_source" to "hana", "right_asset" to "usd-krw",
        "title" to base.getValue("title").replace("달러선물", "하나"))

    private fun deliver(data: Map<String, String>, front: Boolean) {
        mid = UUID.randomUUID().toString()
        val ack = run.client.ack("fcm", nonce) {
            putExtra("data", Bundle().apply { data.forEach { (key, value) -> putString(key, value) } })
            putExtra("mid", mid)
            putExtra("title", data.getValue("title"))
            putExtra("body", data.getValue("body"))
        }
        log("fcm", ack, mid, true)
        check(ack.getInt("result") == -1 && ack.getInt("pid") == pid && !ack.getBoolean("keyguard") &&
            (ack.getInt("importanceBefore") == 100) == front)
        check(texts(since(mark), "control").count { it == "fcm mid=$mid visible=true result=-1 failure=null" } == 1)
    }

    private fun active(): JSONArray = run.client.ack("notifications", nonce) { putExtra("comparisonDetail", true) }.getJSONArray("active")

    private class View(val posts: List<E5dJourneyNotificationListener.Posted>, val active: JSONArray, val types: List<String>,
        val record: List<Line>, val lines: List<Line>, val gets: List<Int>) {
        fun raw() = JSONObject().put("posts", JSONArray(posts.map { it.toString() })).put("active", active).put("types", JSONArray(types))
            .put("gets", JSONArray(gets)).put("record", JSONArray(lines.filter { it.stream in listOf("http", "comparison-http",
                "intent-comparison", "comparison-slot", "comparison-bus", "comparison-gate", "control", "activity") }
                .map { "${it.sequence} ${it.nanos} ${it.stream} ${it.text}" }))
    }

    private fun observe(from: Int, w: E5dJourneyNotifications.Window): View = premise("listener, HTTP and callback log") {
        val (state, posts) = E5dJourneyNotificationListener.snapshot(w.from)
        fun connected(s: E5dJourneyNotificationListener.State) = s.connected && s.connections == w.state.connections &&
            s.disconnections == w.state.disconnections
        check(connected(state)) { "listener ${w.state} -> $state" }
        val key = windowIds.getValue(w)
        val end = UUID.randomUUID().toString()
        Log.i(tag, "window end $nonce $key $end")
        val sinceEpoch = "${w.startedAt / 1000}.${(w.startedAt % 1000).toString().padStart(3, '0')}"
        val logcat = shell("logcat -b main -d -T $sinceEpoch -v epoch -s $tag:I FXiMessaging:D").lines()
        val begin = logcat.indexOfFirst { it.endsWith("window begin $nonce $key") }
        val last = logcat.indexOfLast { it.endsWith("window end $nonce $key $end") }
        check(begin >= 0 && last > begin) { "logcat boundaries $begin/$last" }
        val caller = android.os.Process.myPid()
        val bookend = Regex("""\s*\d+\.\d+\s+$caller\s+\d+\s+I\s+$tag\s*:\s*window .*""")
        check(bookend.matches(logcat[begin]) && bookend.matches(logcat[last]))
        val pattern = Regex("""\s*\d+\.\d+\s+$pid\s+\d+\s+D\s+FXiMessaging\s*:\s*Message received: type=(\S+)""")
        val span = logcat.subList(begin + 1, last)
        val types = span.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1) }
        val active = active()
        val all = rows()
        val lines = all.filter { it.sequence > from }
        check(connected(E5dJourneyNotificationListener.state()))
        timing("observe")
        View(posts, active, types, all, lines, counts(lines))
    }

    private fun waitTail(w: E5dJourneyNotifications.Window, checkFresh: Boolean = false) {
        val end = now() + 5_000
        timing("tail begin")
        premise("trailing observation") {
            do {
                val state = E5dJourneyNotificationListener.state()
                check(state.connected && state.connections == w.state.connections && state.disconnections == w.state.disconnections)
                if (now() < end) SystemClock.sleep(minOf(100L, end - now()).coerceAtLeast(1L))
            } while (now() < end)
        }
        val ended = now()
        timing("tail end", ended)
        if (checkFresh) inside(ended)
    }

    private fun tail(from: Int, w: E5dJourneyNotifications.Window, checkFresh: Boolean = false): View {
        waitTail(w, checkFresh)
        return observe(from, w)
    }

    // Repeat the winning observation through teardown. UI navigation has its own recorded
    // boundary and is never credited with the GET required before it.
    private fun seal(seen: View, from: Int, w: E5dJourneyNotifications.Window, postsLabel: String, getsLabel: String,
        inspectEntries: Boolean, navigation: Int? = null, control: Boolean = false) {
        fun confirm(label: String, raw: JSONObject, expected: Any, ok: Boolean) {
            if (ok) return
            if (control) premise(label) { log(label, raw, expected, false); check(ok) }
            else result(label, raw, expected, false)
        }
        val verified = seen.lines.filter { navigation == null || it.sequence < navigation }
        val expectedGets = counts(verified)
        val endOfNavigation = rows().last().sequence
        endReading = {
            val end = observe(from, w)
            val label = when (case) {
                "J19_0" -> "callback 1"
                "J19_1" -> "원본 callback 1"
                else -> postsLabel
            }
            confirm(label, end.raw(), seen.types.toString(), seen.types == end.types)
            confirm(postsLabel, end.raw(), seen.active.toString(), seen.posts == end.posts && seen.active.toString() == end.active.toString())
        }
        endRecord = { final ->
            val all = final.filter { it.sequence > from }
            val counted = all.filter { navigation == null || it.sequence < navigation || it.sequence > endOfNavigation }
            val raw = JSONObject().put("from", from).put("navigation", navigation).put("navigationEnd", endOfNavigation)
                .put("http", JSONArray(texts(all, "http"))).put("entries", JSONArray(entries(all)))
                .put("slot", JSONArray(texts(all, "comparison-slot"))).put("bus", JSONArray(texts(all, "comparison-bus")))
            confirm(getsLabel, raw, expectedGets.toString(), counts(counted) == expectedGets)
            if (inspectEntries) confirm(if (case == "J19_4") "금지 extras 단회 소비" else "단회 소비·generic 0",
                raw, "one pre/post pair and zero generic", entries(all).size == 2 && noGeneric(all))
        }
    }

    private fun post(seen: View, data: Map<String, String>, sdk: Boolean): JSONObject? {
        val p = seen.posts.singleOrNull() ?: return null
        if (seen.active.length() != 1) return null
        val a = seen.active.getJSONObject(0)
        return a.takeIf { (if (sdk) p.tag?.startsWith(E5dJourneyNotifications.SDK_TAG_PREFIX) == true && p.id == 0
            else p.tag == null && p.id != 0) && p.channel == E5dJourneyNotifications.CHANNEL && p.title == data["title"] &&
            p.tag == (if (a.isNull("tag")) null else a.getString("tag")) && a.getInt("id") == p.id && a.getString("title") == p.title &&
            a.getLong("postTime") == p.postTime && a.getString("channel") == p.channel && a.getString("text") == data["body"] &&
            a.getString("packageName") == E5dJourneyRun.APP_PACKAGE && a.getString("key").isNotEmpty() }
    }

    private fun foreground(data: Map<String, String>, allowed: Boolean) {
        if (allowed) {
            server(if (id == 103) "armed" else "fired")
            premise("hold") { hold() }
        }
        val w = listenerMark()
        val from = boundary("foreground")
        var before = JSONObject()
        var optimistic = false
        var arrived = false
        premise("FCM") { deliver(data, true) }
        if (allowed) {
            val token = ticket
            val arrival = "arrive ticket=$token"
            arrived = poll { arrival in texts(since(from), "comparison-gate") }
            if (arrived) premise("held UI snapshot") {
                val at = since(from).single { it.stream == "comparison-gate" && it.text == arrival }.nanos / 1_000_000
                optimistic = sampleRow(id == 103, until = at + 3_500, latest = at + 4_000)
                before = ui
            }
        }
        // A failing injection or sample unwinds to sub's independent release/observer/collect cleanup.
        if (ticket != null) premise("release held GET") { release() }
        val seen = tail(from, w)
        val raw = seen.raw().put("heldUI", before).put("arrived", arrived)
        val callback = seen.types == listOf("comparison_alert")
        if (allowed) {
            result("callback 1", raw, "comparison_alert", callback)
            result("앱 게시 1", raw, "one app, no SDK, golden title/body", post(seen, data, false) != null)
            result("비교 GET 1", raw, comparison.toString(), seen.gets == comparison && arrived)
            result("응답 전 once·repeat 상태", raw, "fired=${id == 103}", optimistic)
            val adopted = premise("row sample") { sampleRow(id == 106) }
            result("정본 행 채택", raw.put("ui", ui), "id=$id fired=${id == 106}", adopted && listed(seen.lines, id == 106) != null)
        } else {
            result("원본 callback 1", raw, "comparison_alert", callback)
            result("금지 게시 0", raw, 0, seen.posts.isEmpty() && seen.active.length() == 0)
            val armed = premise("row sample") { sampleRow(false) }
            result("금지 GET 0·행 유지", raw.put("ui", ui), none.toString(), seen.gets == none && armed)
        }
        seal(seen, from, w, if (allowed) "앱 게시 1" else "금지 게시 0",
            if (allowed) "비교 GET 1" else "금지 GET 0·행 유지", inspectEntries = false)
    }

    @Test fun J19_0_foregroundAllowed() {
        sub(103, "absolute") { foreground(allowedAbsolute(ABS_ONCE), true) }
        sub(106, "signed") { foreground(allowedSigned(SIGNED_REPEAT), true) }
    }

    @Test fun J19_1_foregroundForbidden() {
        sub(103, "absolute") { foreground(ABS_ONCE, false) }
        sub(106, "signed") { foreground(SIGNED_REPEAT, false) }
    }

    private fun home(): Int = premise("Home/ON_STOP") {
        val from = boundary("home")
        check(run.device.pressHome())
        check(poll { "ON_STOP" in texts(since(from), "process") })
        check(run.device.currentPackageName in homePackages)
        sameProcess()
        timing("home stopped")
        from
    }

    private fun inside(ended: Long) = premise("ordinary return freshness") {
        val age = ended - fresh
        log("freshness", age, "0..20000 ms", age in 0..20_000)
        check(age in 0..20_000) { age }
        if (phase.startsWith("ordinary-") || phase == "shade-click") {
            check(returnReads.size == 2)
            for ((tag, read) in returnReads) {
                val elapsed = ended - read.nanos / 1_000_000
                val valid = read.sequence < mark && elapsed in 0L until 30_000L
                log("return family freshness", JSONObject().put("httpSeq", read.sequence).put("ageMs", elapsed), tag, valid)
                check(valid) { "$tag seq=${read.sequence} ageMs=$elapsed" }
            }
        }
    }

    private fun listenerMark() = premise("listener baseline") {
        notifications.mark(nonce, window).also { w ->
            val key = UUID.randomUUID().toString()
            windowIds[w] = key
            Log.i(tag, "window begin $nonce $key")
            timing("listener marked")
            log("listener baseline", JSONObject().put("key", key).put("from", w.from).put("startedAt", w.startedAt)
                .put("connections", w.state.connections).put("disconnections", w.state.disconnections), key, true)
        }
    }

    private fun noGeneric(lines: List<Line>): Boolean = texts(lines, "comparison-bus").isEmpty() &&
        texts(lines, "comparison-slot").all { it.endsWith("value=null") } &&
        texts(lines, "comparison-slot-sample").all { it.endsWith("value=null") }

    private fun launcher(data: Map<String, String>, inject: Boolean, ordinary: List<Int>): List<Int> {
        server("fired")
        val w = listenerMark()
        val background = home()
        boundary(if (inject) "sdk" else "no-injection")
        if (inject) premise("background FCM") { deliver(data, false) }
        if (inject) timing("FCM acknowledged")
        val bg = tail(background, w)
        val saved = post(bg, data, true)
        if (inject) {
            result("SDK 게시 1", bg.raw(), "one SDK", saved != null)
            result("배경 callback·앱 게시·배달 GET 0", bg.raw(), none.toString(), bg.types.isEmpty() && bg.gets == none)
        } else premise("background control") {
            check(bg.types.isEmpty() && bg.gets == none && bg.posts.isEmpty() && bg.active.length() == 0)
        }
        val back = boundary("launcher")
        premise("launcher start") { run.launch(); timing("launcher started") }
        waitTail(w, checkFresh = true)
        val boot = premise("launcher discovery") { run.discover() }
        val owned = premise("launcher owner reading") { run.client.ack("access", nonce) }
        val after = owned.getJSONObject("ownedAfter")
        val identity = boot.getString("nonce") == nonce && boot.getInt("pid") == pid && owned.getBoolean("stable") &&
            after.optString("state") == "PremiumConfirmed" && after.optString("uid") == "u1" && after.optLong("authGeneration") == generation
        val nav = boundary("launcher-ui")
        val measured = observe(back, w)
        val returnLines = measured.lines.filter { it.sequence < nav }
        val actual = counts(returnLines)
        val armed = premise("read launcher row") {
            if (preview.premiumShown()) navigate(true)
            sampleRow(false)
        }
        val raw = measured.raw().put("access", owned).put("boot", boot).put("ui", ui).put("nav", nav)
        if (inject) {
            val retained = post(measured, data, true)
            result("무탭 동일 PID·행·게시 유지", raw, "armed, same post and owner",
                identity && armed && retained != null && retained.toString() == saved.toString() &&
                    measured.types.isEmpty())
            result("무탭 비교 GET 0", raw, ordinary.toString(), actual == ordinary && actual.last() == 0)
        } else premise("launcher ordinary") {
            log("ordinary", raw, "armed, no comparison GET", actual.last() == 0)
            check(identity && armed && actual.last() == 0 && measured.posts.isEmpty() && measured.types.isEmpty() &&
                measured.active.length() == 0)
        }
        seal(measured, back, w, if (inject) "무탭 동일 PID·행·게시 유지" else "launcher control",
            "무탭 비교 GET 0", inspectEntries = false, navigation = nav, control = !inject)
        return actual
    }

    @Test fun J19_2_backgroundLauncher() {
        var ordinary = emptyList<Int>()
        sub(103, "control") { ordinary = launcher(allowedAbsolute(ABS_ONCE), false, emptyList()) }
        sub(103, "sdk") { launcher(allowedAbsolute(ABS_ONCE), true, ordinary) }
    }

    // Each rehearsal starts in the live background and finishes on the same premium owner.
    // Re-adopt the seed after a rehearsal so neither its traffic nor its age enters the next window.
    private fun ordinaryReturns(sdk: Boolean): Map<String, List<Int>> = premise("no-extras controls") {
        val result = mutableMapOf<String, List<Int>>()
        for (kind in if (sdk) listOf("create", "warm") else listOf("create")) {
            val old = activity()
            val w = listenerMark()
            val bg = home()
            val from = boundary("ordinary-$kind")
            explicit(kind == "create")
            waitTail(w, checkFresh = true)
            sameProcess()
            sameOwner()
            val seen = observe(from, w)
            val pair = entries(seen.lines)
            checkObserver(seen.record, from, old)
            val good = matched(pair) && transition(pair[0], kind, old, activity(seen.record)) &&
                pair.all { extrasMatch(it, emptyMap()) } &&
                noGeneric(seen.lines) && seen.gets == none && seen.types.isEmpty() &&
                seen.posts.isEmpty() && seen.active.length() == 0 && counts(seen.record.filter { it.sequence > bg && it.sequence < from }) == none
            log("ordinary-$kind", seen.raw(), "matching entry, no delivery or family GET", good)
            check(good)
            val nav = boundary("ordinary-$kind-ui")
            val throughReturn = since(from).filter { it.sequence < nav }
            check(counts(throughReturn) == seen.gets) { "late ordinary GET before navigation: ${texts(throughReturn, "http")}" }
            result[kind] = seen.gets
            refresh(false)
            if (case == "J19_3" && sdk && kind == "warm") armedSwitch = captureSwitch()
        }
        result
    }

    private fun shadeItem(title: String): UiObject2 {
        check(run.device.openNotification())
        val systemUi = E5dJourneyNotifications.SYSTEM_UI
        var item: UiObject2? = null
        var candidates = JSONArray()
        fun points(text: String) = text.codePoints().toArray()
        val expected = points(title)
        val found = poll(E5dJourneyNotifications.SHADE_MILLIS) {
            val exact = run.onScreen(By.pkg(systemUi).text(title)).filter { it.text == title }
            if (exact.isNotEmpty()) {
                item = exact.singleOrNull()
                candidates = JSONArray(exact.map { it.text })
            } else {
                val visible = run.onScreen(By.pkg(systemUi)).mapNotNull { node ->
                    node.text?.takeIf(String::isNotEmpty)?.let { node to it }
                }
                candidates = JSONArray(visible.map { (_, text) ->
                    JSONObject().put("text", text).put("codePoints", JSONArray(points(text).toList()))
                })
                item = visible.singleOrNull { (_, text) -> points(text).contentEquals(expected) }?.first
                if (item != null) log("shade fallback", candidates, title, true)
            }
            item != null
        }
        if (!found) {
            log("shade candidates", candidates, title, false)
            error("no unique visible SystemUI title $title")
        }
        return checkNotNull(item)
    }

    private fun tray(data: Map<String, String>, sdk: Boolean, forbidden: Boolean) {
        val controls = ordinaryReturns(sdk)
        server("fired")
        val w = listenerMark()
        val start = if (sdk) home() else boundary("front")
        boundary("prepare-post")
        premise("notification injection") { deliver(data, !sdk) }
        timing("FCM acknowledged")
        val seen = tail(start, w)
        val posted = post(seen, data, sdk)
        val prepared = posted != null && seen.types == (if (sdk) emptyList() else listOf("comparison_alert")) &&
            seen.gets == (if (sdk) none else comparison)
        if (forbidden) result("금지 SDK 게시 1", seen.raw(), "SDK only, no GET or callback", prepared)
        else premise("actual notification before tap") {
            log("prepare-post", seen.raw(), "golden notification", prepared)
            check(prepared)
        }
        if (!forbidden) premise("fired adoption before tap") {
            if (sdk) {
                val from = boundary("prepare-launcher")
                run.launch()
                timing("launcher started")
                val returned = tail(from, w, checkFresh = true)
                check(returned.gets.last() == 0 && post(returned, data, true)?.toString() == posted.toString())
                sameOwner()
                refresh(true)
            } else {
                fresh = checkNotNull(listed(seen.lines, true)).nanos / 1_000_000
                check(sampleRow(true)) { ui }
                firedSwitch = captureSwitch()
                timing("posted fired row adopted")
            }
            server("armed")
        }
        val old = activity()
        val tapWindow = listenerMark()
        val bg = if (forbidden) start else home()
        val item = premise("SystemUI golden notification") { shadeItem(data.getValue("title")) }
        val from = boundary("shade-click")
        val beforeClick = since(bg).filter { it.sequence < from }
        premise("pre-click notification and background") {
            val active = active()
            check(active.length() == 1 && active.getJSONObject(0).toString() == posted.toString() && counts(beforeClick) == none)
        }
        premise("SystemUI click") { item.click(); timing("shade clicked") }
        waitTail(tapWindow, checkFresh = true)
        val nav = boundary("tap-ui")
        val seenTap = observe(from, tapWindow)
        val throughTap = seenTap.record.filter { it.sequence < nav }
        premise("tap observer") { checkObserver(throughTap, from, old) }
        val lines = seenTap.lines.filter { it.sequence < nav }
        val pair = entries(lines)
        val pre = pair.firstOrNull()
        val kind = pre?.optString("entry")
        val ordinary = kind?.let(controls::get)
        val expected = if (sdk) data else data.filterKeys { it in pairKeys || it == "is_repeat" } +
            mapOf("setting_id" to id, "notification_type" to "comparison_alert")
        val validEntry = kind != null && ordinary != null && transition(pre, kind, old, activity(throughTap))
        val appFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val input = validEntry && pre != null && (sdk || (pre.getInt("flags") and appFlags) == appFlags) && extrasMatch(pre, expected)
        val consumedPost = seenTap.posts.isEmpty() && seenTap.active.length() == 0 && seenTap.types.isEmpty()
        val cleared = matched(pair) && extrasMatch(pair[1], emptyMap())
        val raw = seenTap.raw().put("savedPost", posted).put("nav", nav).put("ordinary", ordinary?.let { JSONArray(it) })
        if (forbidden) {
            result("실제 SDK 입력·게시 소비", raw, "original SDK extras, post consumed", input && consumedPost)
            result("금지 extras 단회 소비", raw, "one matching pre/post, extras absent", cleared)
        } else {
            result("게시 소비 1", raw, "removed, no repost", consumedPost)
            result("실제 입구와 extras 값·형", raw, JSONObject(expected), input)
            result("단회 소비·generic 0", raw, "all absent, slot and bus quiet", cleared && noGeneric(lines))
        }
        val expectedGets = ordinary?.mapIndexed { i, n -> n + if (!forbidden && i == 2) 1 else 0 }
        val getsOk = counts(lines) == expectedGets
        if (!forbidden) result("tap 비교 GET 1", raw, expectedGets.toString(), getsOk)
        val armed = premise("tap row sample") {
            sameProcess()
            sameOwner()
            navigate(true)
            sampleRow(false)
        }
        raw.put("ui", ui)
        if (forbidden) result("generic·tap GET 0·행 유지", raw, "ordinary, no generic, armed", getsOk && armed && noGeneric(lines))
        else result("armed 정본 채택", raw, "103 armed", armed && listed(lines, false) != null)
        seal(seenTap, from, tapWindow, if (forbidden) "실제 SDK 입력·게시 소비" else "게시 소비 1",
            if (forbidden) "generic·tap GET 0·행 유지" else "tap 비교 GET 1", inspectEntries = true, navigation = nav)
    }

    @Test fun J19_3_allowedTrayTaps() {
        sub(103, "app") { tray(allowedAbsolute(ABS_ONCE), false, false) }
        sub(103, "sdk") { tray(allowedAbsolute(ABS_ONCE), true, false) }
    }

    @Test fun J19_4_forbiddenSdkTap() {
        sub(103, "sdk-forbidden") { tray(ABS_ONCE, true, true) }
    }

    private companion object {
        val ABS_ONCE = mapOf(
            "body" to "[ 8 ↑이상 도달]",
            "diff_type" to "absolute",
            "is_repeat" to "false",
            "left_asset" to "usd-krw-futures",
            "left_observed_at" to "2026-08-31T01:20:00",
            "left_rate" to "1397.5",
            "left_source" to "krx",
            "operator" to "gte",
            "right_asset" to "usd-krw",
            "right_observed_at" to "2026-08-31T01:20:00",
            "right_rate" to "1388.4",
            "right_source" to "hana",
            "setting_id" to "103",
            "spread" to "9.1",
            "tab" to "usd",
            "threshold" to "8.0",
            "title" to "📊 달러선물 ↔ 하나\u00A0\u00A0차이\u00A0\u00A09.1",
            "type" to "comparison_alert"
        )
        val SIGNED_REPEAT = mapOf(
            "body" to "[ -5 ↓이하 도달]",
            "diff_type" to "signed",
            "is_repeat" to "true",
            "left_asset" to "usdt-krw",
            "left_observed_at" to "2026-08-31T01:20:00",
            "left_rate" to "1392.0",
            "left_source" to "upbit",
            "operator" to "lte",
            "right_asset" to "usd-krw-futures",
            "right_observed_at" to "2026-08-31T01:20:00",
            "right_rate" to "1397.5",
            "right_source" to "krx",
            "setting_id" to "106",
            "spread" to "-5.5",
            "tab" to "tether",
            "threshold" to "-5.0",
            "title" to "📊 업비트 - 달러선물\u00A0\u00A0김프\u00A0\u00A0-5.5 (-0.39%)",
            "type" to "comparison_alert"
        )
    }
}
