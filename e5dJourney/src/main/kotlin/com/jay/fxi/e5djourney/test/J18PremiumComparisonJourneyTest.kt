package com.jay.fxi.e5djourney.test

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Line
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class J18PremiumComparisonJourneyTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)
    private val notifications = E5dJourneyNotifications(run)
    private lateinit var nonce: String
    private var pid = -1
    private var phase = "boot"
    private var mark = 0
    private var upper = 0
    private var sample = JSONObject()
    private var http = emptyList<Line>()
    private var details = emptyList<Line>()

    @After fun close() {
        try {
            notifications.revoke()
        } finally {
            try { preview.endProcess() } finally { run.close() }
        }
    }

    @Test fun J18_0_signedCreateRefreshHistory() {
        premise("API 32 OFF / listener / Root") {
            val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }
            val airplane = shell("settings get global airplane_mode_on")
            check(Build.VERSION.SDK_INT == 32 && avd.startsWith("Pixel_3a") && airplane == "0")
            val context = run.instrumentation.context
            val appUid = context.packageManager.getApplicationInfo(E5dJourneyRun.APP_PACKAGE, 0).uid
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} avd=$avd airplane=$airplane appUid=$appUid callerUid=${context.applicationInfo.uid}")
            notifications.allow()
            preview.ready()
            val process = preview.restartOn("유로", "달러")
            nonce = process.getString("nonce")
            pid = process.getInt("pid")
        }
        var finalRequests = emptyList<Int>()
        run.run { measured ->
            premise("premium cold") {
                check(measured == nonce)
                val start = run.client.ack("start", nonce)
                check(start.getInt("pid") == pid && start.getBoolean("providerAck") && start.getBoolean("processAck") &&
                    start.getInt("activityAcks") >= 1 && start.getBoolean("admissionOpen") &&
                    start.getBoolean("endpointsAreServer") && start.getString("baseUrl").startsWith("http://127.0.0.1:") &&
                    start.getString("identityUid") == "u1")
                checkNotifications()
                val free = owner("FreeConfirmed")
                readRecord()
                check(free.getString("uid") == "u1" && http.isEmpty())
                run.client.ack("answer", nonce) { putExtra("arg", "active") }
                run.client.ack("local-premium", nonce)
                val premium = owner("PremiumConfirmed")
                check(premium.getString("uid") == "u1" && premium.getLong("authGeneration") == free.getLong("authGeneration"))
                Log.i(TAG, "start=$start free=$free premium=$premium listener=${E5dJourneyNotificationListener.state()}")
                run.node("premium_settings", 30_000)
                run.awaitRecord(nonce, "comparison cold", 30_000) { COLD in E5dJourneyRun.texts(it, "http") }
                readRecord()
                check(http.map { it.text } == listOf(COLD))
            }
            val cold = http.single().sequence
            // The parent server has no comparison-http stream. Resolve a missing wire record at save.
            val coldWireMissing = details.isEmpty()
            if (!coldWireMissing) premise("cold GET wire") {
                check(wire(http.single(), "GET", SETTINGS)) { details.toString() }
            }
            boundary("tether")
            premise("기본 펼침·빈 signed 섹션") {
                run.tap("premium_tab_TETHER")
                run.device.waitForIdle()
                run.reveal(By.desc("김프 알림 접기"), "김프 알림 접기")
                run.reveal(By.text("거래소 시세와 환율의 차이(김프/역프) 알림"), "signed empty")
            }
            tapControl("comparison-signed-add", SHEET)
            result(SHEET, "취소·김프 알림·저장", poll { editor() != null })
            configure()
            boundary("save")
            val save = mark
            val button = field(By.text("저장")) { node ->
                val target = clickable(node)
                val enabled = target?.isEnabled == true
                sample.put("save", target?.let(::describe)).put("enabled", enabled)
                result(INPUT, "저장 enabled", enabled)
                checkNotNull(target)
            }
            clickInput("저장", button)
            observe { it.text.startsWith("POST ") }
            val posts = http.filter { it.sequence > save && it.text.startsWith("POST ") }
            val post = posts.singleOrNull()
            if (coldWireMissing) premise("cold GET wire") {
                check(details.isEmpty() && post?.text == "POST $SETTINGS q= -> UNEXPECTED") {
                    "missing comparison-http for cold GET #$cold"
                }
            }
            val asked = JSONObject(ASKED)
            val returned = JSONObject(ASKED).put("id", 3001).put("user_id", "u1").put("triggered", false)
                .put("last_notified_spread", JSONObject.NULL).put("created_at", SENT).put("updated_at", JSONObject.NULL)
            val stored = JSONObject(returned.toString()).put("threshold", -7)
            val evidence = JSONObject().put("asked", asked).put("returned", returned).put("stored", stored)
            val prefix = "POST $SETTINGS q= -> comparison create id=3001 n=1 evidence="
            result(POST, evidence, post != null && matchesBody(post, prefix, evidence) && wire(post, "POST", SETTINGS, asked))

            val id = storedId(post!!)
            verifyRow(id, "-5", "J18 POST 응답 행 표시")
            boundary("refresh")
            val refresh = mark
            readRecord()
            result(EARLY, listOf(post.sequence), http.filter { it.sequence in (cold + 1)..refresh }.map { it.sequence } == listOf(post.sequence))
            tapControl("comparison-signed-refresh", REFRESH)
            observe { it.text.startsWith("GET $SETTINGS ") }
            val read = http.filter { it.sequence > refresh }.singleOrNull()
            val list = envelope("alerts", stored)
            result(REFRESH, list, read != null && matchesBody(read, "GET $SETTINGS q= -> comparison alerts list n=1 body=", list) &&
                wire(read, "GET", SETTINGS))
            verifyRow(id, "-7", "J18 같은 ID 정본 -7 표시")
            boundary("history")
            readRecord()
            result(REFRESH, listOf(read!!.sequence), http.filter { it.sequence > refresh }.map { it.sequence } == listOf(read.sequence))
            tapControl("comparison-signed-history", HISTORY_GET)
            observe { it.text.startsWith("GET $HISTORY ") }
            val history = http.filter { it.sequence > mark }.singleOrNull()
            val logs = envelope("logs", JSONObject(HISTORY_ROW).put("setting_id", id))
            val historyPrefix = "GET $HISTORY q=diff_type=signed&limit=100&tab=tether -> comparison history body="
            result(HISTORY_GET, logs, history != null && matchesBody(history, historyPrefix, logs) && wire(history, "GET", HISTORY))
            val shown = poll { delivered() }
            result(HISTORY_UI, HISTORY_CELLS, shown)
            readRecord()
            finalRequests = listOf(cold, post.sequence, read.sequence, history!!.sequence)
            result(HISTORY_GET, finalRequests, http.map { it.sequence } == finalRequests)
        }
        // run() has collected its complete record. Include arrivals through that upper in the device verdict.
        readRecord()
        result(HISTORY_GET, finalRequests, http.map { it.sequence } == finalRequests)
    }

    private fun tapControl(tag: String, label: String) {
        val node = premise("$tag 조회") {
            val target = run.reveal(tag)
            sample = JSONObject().put("target", tag).put("node", describe(target)).put("screen", run.describeScreen())
            check(target.isEnabled) { sample.toString() }
            target
        }
        // Timestamp the enabled target before the click can dispatch HTTP.
        result(label, "$tag enabled", true)
        premise("$tag 클릭") { node.click() }
    }

    private fun storedId(post: Line) =
        JSONObject(post.text.substringAfter(" evidence=")).getJSONObject("returned").getInt("id")

    private fun boundary(name: String) = premise("phase $name") {
        val process = run.discover()
        check(process.getString("nonce") == nonce && process.getInt("pid") == pid &&
            run.device.currentPackageName == E5dJourneyRun.APP_PACKAGE)
        mark = preview.phase(nonce, name)
        phase = name
    }

    private fun <T> premise(label: String, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        throw AssertionError("premise: $label phase=$phase seq=$mark ${e.message}", e)
    } catch (e: AssertionError) {
        throw AssertionError("premise: $label phase=$phase seq=$mark ${e.message}", e)
    }

    private fun readRecord() = premise("record") {
        val dump = run.client.dump(nonce)
        upper = dump.upper
        val lines = E5dJourneyTraffic.parse(dump.lines)
        http = lines.filter { it.stream == "http" && it.text.substringAfter(' ').startsWith("/api/comparison") }
        details = lines.filter { it.stream == "comparison-http" }
    }

    // Allow five seconds for arrival, then up to five more for one quiet second.
    private fun observe(arrived: (Line) -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5_000
        var tailDeadline: Long? = null
        var last = -1
        var quiet = SystemClock.elapsedRealtime()
        do {
            readRecord()
            val now = SystemClock.elapsedRealtime()
            if (http.size != last) {
                last = http.size
                quiet = now
            }
            sample = JSONObject().put("screen", run.describeScreen())
            if (http.any { it.sequence > mark && arrived(it) }) {
                val deadline = tailDeadline ?: (now + 5_000).also { tailDeadline = it }
                if (now - quiet >= 1_000 || now >= deadline) return
            } else if (now >= until) return
            SystemClock.sleep(100)
        } while (true)
    }

    private fun result(label: String, expected: Any, ok: Boolean) {
        logResult(label, expected, ok)
        assertTrue(label, ok)
    }

    private fun logResult(label: String, expected: Any, ok: Boolean) {
        if (!ok) {
            val windows = run.instrumentation.uiAutomation.windows
            try {
                sample.put("accessibilityWindows", JSONArray(windows.map { it.toString() }))
            } finally {
                windows.forEach { it.recycle() }
            }
            sample.put("screen", run.describeScreen())
        }
        val evidence = JSONObject().put("case", "J18_0_signedCreateRefreshHistory").put("run", 1)
            .put("phase", phase).put("assertion", label).put("pass", ok).put("seq", mark).put("upper", upper)
            .put("elapsed", SystemClock.elapsedRealtime()).put("pid", pid).put("nonce", nonce)
            .put("expected", JSONObject.wrap(expected)).put("ui", sample)
            .put("http", JSONArray(http.map { "${it.sequence} ${it.phase} ${it.text}" }))
            .put("wire", JSONArray(details.map { it.text }))
        // Keep each logcat entry below its byte limit, including Hangul.
        val chunks = evidence.toString().chunked(850)
        if (chunks.size == 1) {
            Log.i(TAG, chunks.single())
        } else {
            chunks.forEachIndexed { i, part -> Log.i(TAG, "detail ${i + 1}/${chunks.size} $part") }
            Log.i(TAG, JSONObject().put("case", "J18_0_signedCreateRefreshHistory").put("phase", phase)
                .put("seq", mark).put("upper", upper).put("pid", pid).put("nonce", nonce)
                .put("assertion", label).put("pass", ok).put("detailParts", chunks.size).toString())
        }
    }

    private fun wire(line: Line, verb: String, path: String, asked: JSONObject? = null): Boolean {
        val detail = details.mapNotNull { json(it.text) }.singleOrNull { it.opt("httpSeq") == line.sequence } ?: return false
        val body = detail.opt("body") as? String ?: return false
        val query = if (path == HISTORY) JSONObject("""{"diff_type":["signed"],"tab":["tether"],"limit":["100"]}""") else JSONObject()
        return detail.opt("method") == verb && detail.opt("path") == path && detail.opt("status") == 200 &&
            detail.opt("bodySize") == body.toByteArray().size && same(query, detail.opt("query")) &&
            (if (path == HISTORY) detail.opt("encodedQuery") is String else detail.opt("encodedQuery") === JSONObject.NULL) &&
            (if (asked == null) body.isEmpty() else same(asked, json(body)))
    }

    private fun json(text: String) = runCatching { JSONObject(text) }.getOrNull()
    private fun matchesBody(line: Line, prefix: String, expected: JSONObject) =
        line.text.startsWith(prefix) && same(expected, json(line.text.removePrefix(prefix)))

    private fun same(expected: Any?, actual: Any?): Boolean = when (expected) {
        is JSONObject -> actual is JSONObject && expected.keys().asSequence().toSet() == actual.keys().asSequence().toSet() &&
            expected.keys().asSequence().all { same(expected.opt(it), actual.opt(it)) }
        is JSONArray -> actual is JSONArray && expected.length() == actual.length() &&
            (0 until expected.length()).all { same(expected.opt(it), actual.opt(it)) }
        is Number -> actual is Number && expected.toString().toBigDecimal().compareTo(actual.toString().toBigDecimal()) == 0
        JSONObject.NULL -> actual === JSONObject.NULL
        else -> expected == actual
    }

    private fun envelope(key: String, row: JSONObject) =
        JSONObject().put(key, JSONArray().put(row)).put("total_count", 1)

    private fun poll(timeout: Long = 5_000, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            try {
                if (condition()) return true
            } catch (_: StaleObjectException) {
                // Re-query the same window after recomposition.
            }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < until)
        return false
    }

    private fun owner(state: String): JSONObject {
        var owned = JSONObject()
        check(poll(60_000) {
            val access = run.client.ack("access", nonce)
            owned = access.getJSONObject("ownedAfter")
            access.getBoolean("stable") && owned.optString("state") == state
        }) { "$state: $owned" }
        return owned
    }

    private fun root(n: UiObject2) = generateSequence(n) { it.parent }.last()

    private fun visible(nodes: List<UiObject2>): List<UiObject2> {
        if (nodes.isEmpty()) return emptyList()
        val screen = Rect(0, 0, run.device.displayWidth, run.device.displayHeight)
        // Let poll retry stale bounds instead of counting that node as absent.
        return nodes.filter { node ->
            val bounds = node.visibleBounds
            !bounds.isEmpty && Rect.intersects(screen, bounds)
        }
    }

    private fun ancestors(node: UiObject2) = generateSequence(node.parent) { it.parent }

    private fun within(scope: UiObject2, selector: BySelector, accept: (UiObject2) -> Boolean = { true }): List<UiObject2> {
        val candidates = run.device.findObjects(selector).filter(accept)
        return visible(candidates).filter { node -> ancestors(node).any { it == scope } }
    }

    private fun describe(n: UiObject2) = JSONObject().put("res", n.resourceName).put("text", n.text)
        .put("desc", n.contentDescription).put("bounds", n.visibleBounds.toShortString())

    private fun editor(): UiObject2? {
        sample = JSONObject()
        val windows = run.onScreen(By.text("저장").pkg(E5dJourneyRun.APP_PACKAGE)).map(::root).distinct()
            .filter { within(it, By.text("취소")).isNotEmpty() && within(it, By.text("김프 알림")).isNotEmpty() }
        sample.put("windows", JSONArray(windows.map(::describe)))
        return windows.singleOrNull()
    }

    private fun unreadable(label: String): Nothing {
        val message = "premise: $label phase=$phase seq=$mark"
        logResult(message, label, false)
        throw AssertionError(message)
    }

    private fun <T : Any> readEditor(label: String, read: (UiObject2) -> T): T {
        var value: T? = null
        val ready = poll {
            val sheet = editor() ?: return@poll false
            value = read(sheet)
            true
        }
        if (!ready) unreadable("시트 판독 $label")
        return checkNotNull(value)
    }

    private fun <T : Any> field(
        selector: BySelector,
        label: String = selector.toString(),
        accept: (UiObject2) -> Boolean = { true },
        read: (UiObject2) -> T
    ): T {
        fun matches(sheet: UiObject2): List<T> {
            val nodes = within(sheet, selector, accept)
            sample.put("selector", label).put("matches", nodes.size)
            if (nodes.size > 1) unreadable("시트 항목 식별 $label")
            return nodes.map(read)
        }
        var nodes = readEditor(label, ::matches)
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            for (step in 0 until E5dJourneyRun.MAX_SCROLLS) {
                if (nodes.isNotEmpty()) break
                val moved = readEditor(label) { sheet -> run.scrollPage(direction) { sheet } }
                nodes = readEditor(label, ::matches)
                if (!moved) break
            }
        }
        // The summary can update after the numeric display during recomposition.
        if (nodes.isEmpty()) {
            var readable = false
            val found = poll {
                readable = false
                val sheet = editor() ?: return@poll false
                nodes = matches(sheet)
                readable = true
                nodes.isNotEmpty()
            }
            if (!readable) unreadable("시트 판독 $label")
            if (!found) {
                sample.put("node", JSONObject.NULL)
                result(INPUT, label, false)
            }
        }
        return nodes.single()
    }

    private fun clickInput(label: String, node: UiObject2) {
        try {
            node.click()
        } catch (e: RuntimeException) {
            unreadable("$label 클릭 ${e.message}")
        }
    }

    private fun clickable(n: UiObject2) = generateSequence(n) { it.parent }.firstOrNull { it.isClickable }

    private fun configure() {
        // Selections are scoped to the modal. The repeat menu is a separate popup.
        select("업비트") { it.startsWith("업비트") }
        select("하나") { it.startsWith("하나") }
        select("△ 이상")
        select("한번만")
        chooseRepeatInterval()
        select("5분", click = false)
        select("▽ 이하", click = false)
        field(By.desc("−")) { it }

        var value = reading(null)
        val start = value
        val target = BigDecimal("-5")
        var taps = 0
        while (value.compareTo(target) != 0 && taps < 100) {
            val delta = if (value > target) BigDecimal("-0.1") else BigDecimal("0.1")
            val arrow = if (delta.signum() < 0) "−" else "＋"
            val prior = value
            val button = field(By.desc(arrow)) { node ->
                if (!node.isEnabled) {
                    sample.put("node", describe(node))
                    result(INPUT, "$arrow enabled", false)
                }
                node
            }
            clickInput(arrow, button)
            taps++
            value = reading(prior)
            sample.put("start", start).put("tap", taps).put("before", prior).put("after", value)
            result(INPUT, prior + delta, value.compareTo(prior + delta) == 0)
        }
        result(INPUT, "-5", value.compareTo(target) == 0 && sample.optString("value") == "-5")
        val headline = "업비트 김프가 하나 대비 -5원 이하로 떨어지면 알려드려요"
        select(headline, click = false) { it.contains(headline) }
        select("5분", click = false)
    }

    private fun chooseRepeatInterval() {
        // API 32 reports the dropdown as a separate focused "Pop-Up Window".
        // Its ID distinguishes it from the repeated "5분" text in the sheet.
        val automation = run.instrumentation.uiAutomation
        var item: UiObject2? = null
        var popupId: Int? = null
        val opened = poll {
            sample = JSONObject()
            item = run.onScreen(By.text("5분").pkg(E5dJourneyRun.APP_PACKAGE)).singleOrNull()
            val windows = automation.windows
            try {
                popupId = windows.singleOrNull { it.isFocused && it.title?.toString() == "Pop-Up Window" }?.id
            } finally {
                windows.forEach { it.recycle() }
            }
            sample.put("popupId", popupId ?: JSONObject.NULL)
                .put("node", item?.let(::describe) ?: JSONObject.NULL)
            popupId != null && item != null
        }
        if (!opened) {
            if (item != null) unreadable("5분 메뉴 창 식별")
            readEditor("5분 메뉴") { true }
        }
        result(INPUT, "5분 메뉴", opened)
        sample = JSONObject().put("popupId", popupId)
        clickInput("5분 메뉴", checkNotNull(item))
        val restored = poll {
            val windows = automation.windows
            val closed = try {
                windows.isNotEmpty() && windows.none { it.id == popupId }
            } finally {
                windows.forEach { it.recycle() }
            }
            val sheet = editor()
            sample.put("popupId", popupId).put("popupClosed", closed)
            closed && sheet != null
        }
        if (!restored) unreadable("5분 메뉴 닫힘·시트 판독")
    }

    private fun select(text: String, click: Boolean = true, matches: (String) -> Boolean = { it == text }) {
        // Enumerate the same visible text surface as describeScreen, then verify sheet membership.
        val node = field(By.textStartsWith("").pkg(E5dJourneyRun.APP_PACKAGE), text, { it.text?.let(matches) == true }) { found ->
            sample.put("node", describe(found))
            result(INPUT, text, found.isEnabled)
            found
        }
        if (click) clickInput(text, node)
    }

    private fun reading(previous: BigDecimal?): BigDecimal {
        var lastText: String? = null
        poll {
            lastText = null
            val now = valueText() ?: return@poll false
            lastText = now
            previous == null || now.toBigDecimalOrNull()?.compareTo(previous) != 0
        }
        val text = lastText ?: unreadable("시트 표시 판독")
        val number = text.toBigDecimalOrNull()
        val magnitude = number?.stripTrailingZeros()?.toPlainString()
        val canonical = if (number != null && number.signum() >= 0) "+$magnitude" else magnitude
        result(INPUT, "부호 있는 십진 표시", number != null && NUMBER.matches(text) && canonical == text)
        return number!!
    }

    private fun valueText(): String? {
        val sheet = editor() ?: return null
        fun find(selector: BySelector) = within(sheet, selector)
        val tags = find(By.res("comparison-value"))
        val minus = find(By.desc("−"))
        val plus = find(By.desc("＋"))
        sample.put("minus", JSONArray(minus.map(::describe))).put("plus", JSONArray(plus.map(::describe)))
        val candidates = if (tags.isNotEmpty()) tags else {
            val left = minus.singleOrNull()?.visibleBounds ?: return null
            val right = plus.singleOrNull()?.visibleBounds ?: return null
            within(sheet, By.clazz("android.widget.TextView")) {
                val box = it.visibleBounds
                box.left >= left.right && box.right <= right.left &&
                    maxOf(box.top, left.top, right.top) < minOf(box.bottom, left.bottom, right.bottom)
            }
        }
        sample.put("candidates", JSONArray(candidates.map(::describe)))
        return candidates.singleOrNull()?.text?.takeIf { it.isNotBlank() }?.also { sample.put("value", it) }
    }

    private fun tokens(n: UiObject2): List<String> =
        listOfNotNull(n.text).flatMap { it.split('\n', ',') }.map { it.trim() }.filter { it.isNotEmpty() } +
            visible(n.children).flatMap(::tokens)

    // Read the API 32 accessibility state separately from the UiObject2 checked flag.
    private fun switchStateDescription(tag: String): String? {
        val root = run.instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val matches = mutableListOf<String?>()
        fun visit(node: AccessibilityNodeInfo) {
            try {
                if (node.viewIdResourceName == tag && node.isVisibleToUser && node.packageName == E5dJourneyRun.APP_PACKAGE) {
                    matches.add(node.stateDescription?.toString())
                } else {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
                }
            } finally {
                node.recycle()
            }
        }
        visit(root)
        return matches.singleOrNull()
    }

    private fun verifyRow(id: Int, value: String, label: String) {
        val expected = listOf("업비트-하나", value, "▽ 이하", "5분마다")
        val description = "업비트 vs 하나 김프 ${value}원 이하"
        val edit = "comparison-edit-$id"
        val toggle = "comparison-toggle-$id"
        if (poll { editor() == null }) {
            // The newly inserted row may be just outside the current viewport.
            try { run.reveal(edit) } catch (_: IllegalStateException) { }
        }
        val shown = poll {
            val closed = editor() == null
            val card = run.onScreen(By.res("comparison-signed")).singleOrNull()
            val edits = card?.let { within(it, By.res(Regex("comparison-edit-.*").toPattern())) }.orEmpty()
            val switches = card?.let { within(it, By.res(Regex("comparison-toggle-.*").toPattern())) }.orEmpty()
            val row = edits.singleOrNull()
            val switch = switches.singleOrNull()
            val fields = row?.let(::tokens)
            // The edit Column owns the semantics. Accessibility may expose its description on a child.
            val descriptions = row?.let { editNode ->
                val own = listOfNotNull(editNode.takeUnless { it.contentDescription.isNullOrEmpty() })
                own + within(editNode, By.desc(Regex(".+").toPattern()).pkg(E5dJourneyRun.APP_PACKAGE))
            }.orEmpty().map { node ->
                JSONObject().put("desc", node.contentDescription ?: JSONObject.NULL)
                    .put("res", node.resourceName ?: JSONObject.NULL).put("bounds", node.visibleBounds.toShortString())
            }
            val state = if (switch?.resourceName == toggle) switchStateDescription(toggle) else null
            sample.put("edits", JSONArray(edits.map(::describe))).put("switches", JSONArray(switches.map(::describe)))
                .put("descriptions", JSONArray(descriptions)).put("fields", fields?.let { JSONArray(it) }).put("checked", switch?.isChecked)
                .put("checkable", switch?.isCheckable).put("stateDescription", state ?: JSONObject.NULL).put("closed", closed)
            closed && row != null && switch != null && row.resourceName == edit && switch.resourceName == toggle && fields == expected &&
                descriptions.singleOrNull()?.opt("desc") == description && switch.isCheckable && switch.isChecked && state == "켜짐"
        }
        result(label, expected + listOf(description, toggle, "checked", "켜짐"), shown)
    }

    private fun delivered(): Boolean {
        val windows = run.onScreen(By.text("닫기").pkg(E5dJourneyRun.APP_PACKAGE)).map(::root)
            .filter { within(it, By.text("김프 알림 히스토리")).isNotEmpty() }.distinct()
        sample = JSONObject().put("windows", JSONArray(windows.map(::describe)))
        val sheet = windows.singleOrNull() ?: return false
        fun find(selector: BySelector) = within(sheet, selector)
        val left = find(By.descStartsWith("왼쪽 ")).singleOrNull() ?: return false
        val right = find(By.descStartsWith("오른쪽 ")).singleOrNull() ?: return false
        val tagged = find(By.res("comparison-history-row"))
        val leftParents = ancestors(left).toList()
        val rightParents = ancestors(right).toList()
        val row = if (tagged.isEmpty()) leftParents.firstOrNull { it in rightParents } else tagged.singleOrNull()
        if (row == null || row !in leftParents || row !in rightParents) return false
        val actual = listOf(left.contentDescription, right.contentDescription) + tokens(row)
        sample.put("cells", JSONArray(actual)).put("row", describe(row))
        return actual == HISTORY_CELLS
    }

    private fun shell(command: String) = run.device.executeShellCommand(command).trim()

    /** Read the package's own ranking preferences. An unconfigured app importance is the API 32 default. */
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
        Log.i(TAG, "POST_NOTIFICATION=$op rate_alerts importance=$importance")
    }

    private companion object {
        const val TAG = "E5dJourneyJ18"
        const val SETTINGS = "/api/comparison-alerts"
        const val HISTORY = "/api/comparison-notification-logs"
        const val COLD = "GET $SETTINGS q= -> comparison alerts list n=0"
        const val SHEET = "J18 김프 추가 시트"
        const val INPUT = "J18 테더 signed 저장 입력"
        const val POST = "J18 signed POST 1"
        const val EARLY = "J18 수동 전 목록 GET 0"
        const val REFRESH = "J18 수동 목록 GET 1"
        const val HISTORY_GET = "J18 signed history GET 1"
        const val HISTORY_UI = "J18 발송 당시 두 가격과 김프 표시"
        const val SENT = "2026-08-31T10:00:00+09:00"
        val NUMBER = Regex("""[+-]?(?:0|[1-9]\d*)(?:\.\d+)?""")
        val HISTORY_CELLS = listOf("왼쪽 업비트 1380", "오른쪽 하나 1400.00", "-20.0", "-1.43%", "-4", "▽ 이하", "08-31", "10:00")
        const val ASKED = """{"tab":"tether","diff_type":"signed","left_source":"upbit","left_asset":"usdt-krw","right_source":"hana","right_asset":"usd-krw","operator":"lte","threshold":-5.0,"repeat_interval_sec":300,"is_enabled":true}"""
        const val HISTORY_ROW = """{"id":503,"tab":"tether","diff_type":"signed","left_source":"upbit","left_asset":"usdt-krw","right_source":"hana","right_asset":"usd-krw","operator":"lte","threshold":-4,"left_rate":1380,"right_rate":1400,"spread":-20,"is_repeat":true,"sent_at":"2026-08-31T10:00:00+09:00","left_observed_at":"2026-08-31T09:59:00+09:00","right_observed_at":"2026-08-31T09:59:00+09:00"}"""
    }
}
