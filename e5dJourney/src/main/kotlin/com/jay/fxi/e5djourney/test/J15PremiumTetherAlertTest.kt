package com.jay.fxi.e5djourney.test

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyTraffic.Line
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** One launcher journey. Only the user's save, refresh and history taps drive the source endpoints. */
@RunWith(AndroidJUnit4::class)
class J15PremiumTetherAlertTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)
    private lateinit var nonce: String
    private var pid = -1
    private var phase = "boot"
    private var mark = 0

    @After
    fun close() {
        try {
            preview.endProcess()
        } finally {
            run.close()
        }
    }

    @Test
    fun J15_0_addFromRootThenReadCanonicalRowAndDeliveredPrice() {
        stage(DEVICE) { checkDevice() }
        val process = stage(ROOT) {
            preview.ready()
            preview.restartOn("유로", "달러")
        }
        nonce = process.getString("nonce")
        pid = process.getInt("pid")
        run.run { measured ->
            stage(ROOT) {
                check(measured == nonce)
                val start = run.client.ack("start", nonce)
                check(start.getBoolean("providerAck") && start.getBoolean("processAck") && start.getInt("activityAcks") >= 1)
                check(start.getBoolean("admissionOpen") && start.getBoolean("endpointsAreServer") && start.getString("identityUid") == "u1")
                run.awaitRecord(nonce, "free entitlements", 60_000) { lines ->
                    // J10's startup rule: the first answer may carry fresh_premium=true (J10ProductionJourneyTest ENTITLEMENTS_FREE).
                    E5dJourneyRun.texts(lines, "http").any { STARTUP_FREE.matches(it) }
                }
            }
            stage(DEVICE) { checkNotifications() }
            stage("PREMISE J15 같은 owner premium Root") {
                val free = owner("FreeConfirmed")
                check(free.getString("uid") == "u1")
                assertComparisonRows("free", run.client.dump(nonce).server(0), 0)
                run.client.ack("answer", nonce) { putExtra("arg", "active") }
                run.client.ack("local-premium", nonce)
                val premium = owner("PremiumConfirmed")
                check(premium.getString("uid") == free.getString("uid") &&
                    premium.getLong("authGeneration") == free.getLong("authGeneration")) { "$free -> $premium" }
                run.node("premium_settings", 30_000)
            }
            run.awaitRecord(nonce, "cold comparison 목록", 30_000) { COMPARISON_LIST in E5dJourneyRun.texts(it, "http") }
            val cold = observed().server(0)
            assertComparisonRows("cold premium", cold, 1)
            val initial = cold.requests(SETTINGS)
            stage("PREMISE J15 기본 source 빈 목록") {
                check(initial.isNotEmpty() && initial.all { it.text == EMPTY }) { "n=${initial.size} ${summary(initial)}" }
            }
            val tether = boundary("tether")
            stage("J15 테더 탭의 빈 알림 섹션") {
                tap(run.node("premium_tab_TETHER"))
                run.device.waitForIdle()
                run.reveal("source_alert_section")
                recordGraphSurface()
                run.reveal(By.text("설정된 거래소 알림이 없습니다"), "빈 설정")
                tap(run.reveal("source_alert_add_button"))
            }
            val editor = stage("J15 추가 시트 열림") { await { editorOrNull() } }
            stage("PREMISE J15 빗썸 시세와 활성 입력") {
                tap(field(editor, By.text("빗썸")))
                field(editor, By.text("현재 시세 1392원"))
                check(field(editor, EDIT).isEnabled)
            }
            val saveButton = stage("J15 저장 직전 빗썸·이상·1450·한번만") {
                tap(field(editor, By.text("▽ 이하")))
                field(editor, EDIT).text = "1450"
                if (Regex("""\bmInputShown=true\b""").containsMatchIn(shell("dumpsys input_method"))) {
                    run.device.pressBack()
                    run.device.waitForIdle()
                }
                tap(field(editor, By.text("한번만")))
                field(editor, By.text("조건 충족 시 1회만 발송"))
                field(editor, By.text("△ 이상"))
                check(field(editor, EDIT).text == "1450")
                button(field(editor, By.text("저장")))
            }
            val s = boundary("save")
            stage(TAP) { tap(saveButton) }
            val saved = observed().server(0)
            val posts = saved.requests(SETTINGS).filter { it.sequence > s && it.text.startsWith("POST ") }
            val post = stage("J15 저장 POST 정확히 1") { only(posts) }
            checkTable(saved) { line ->
                post.text == "POST $SETTINGS q= -> UNEXPECTED" && (line.sequence == post.sequence ||
                    (line.sequence > tether && line.text == GRAPH.substringBefore(" -> ") + " -> UNEXPECTED"))
            }
            val created = checkCreation(post)
            stage("J15 저장 시트 닫힘") {
                await { true.takeIf { editorOrNull() == null && run.device.currentPackageName == E5dJourneyRun.APP_PACKAGE } }
            }
            val posted = stage(ROW) { readRow(created.id, "1450.00") }
            stage("J15 발급 id의 POST 가격 1450.00") {
                check(posted == rowTokens("1450.00")) { "id=${created.id} row=$posted body=${created.returned}" }
            }
            stage(TAP) { overlayFree(run.reveal("source_alert_refresh")).also { check(it.isEnabled) } }
            stage(ROOT) { run.settle(nonce) }
            val r = boundary("refresh")
            stage(TAP) { tap(overlayFree(run.node("source_alert_refresh"))) }
            val refreshed = observed().server(0)
            val gets = refreshed.requests(SETTINGS).filter { it.text.startsWith("GET ") }
            checkTable(refreshed)
            // Before the refresh's own count: nothing asked for the list between the save and the refresh mark.
            stage("J15 저장 뒤 새로고침 전 source GET 0") {
                val early = gets.filter { it.sequence in (s + 1)..r }
                check(early.isEmpty()) { summary(early) }
            }
            val reads = gets.filter { it.sequence > r }
            if (reads.size != 1) {
                // The row is read as a premise first, so an unreadable row never becomes this count's result.
                val seen = stage(ROW) { readRow(created.id, "1450.00") }
                stage("J15 새로고침 뒤 source GET 정확히 1") { error("GET n=${reads.size} ${summary(reads)} id=${created.id} row=$seen") }
            }
            val read = reads.single()
            val body = stage("J15 정본 GET 본문이 같은 id 저장 행과 일치") {
                val prefix = "GET $SETTINGS q=asset=usdt-krw -> source settings list n=1 body="
                check(read.text.startsWith(prefix)) { read.text }
                val actual = JSONObject(read.text.removePrefix(prefix))
                equalJson(envelope("settings", created.stored), actual)
                actual
            }
            val adopted = stage(ROW) { readRow(created.id, "1460.00") }
            stage("J15 같은 id 화면이 정본 가격 1460.00을 채택") {
                check(adopted == rowTokens("1460.00")) { "id=${created.id} row=$adopted body=$body" }
            }
            stage(TAP) { overlayFree(run.reveal("source-history-entry")).also { check(it.isEnabled) } }
            val early = observed().server(0).requests(HISTORY)
            stage("J15 열기 전 history 0") { check(early.isEmpty()) { summary(early) } }
            val h = boundary("history")
            stage(TAP) { tap(overlayFree(run.node("source-history-entry"))) }
            val opened = observed().server(0)
            val logs = opened.requests(HISTORY).filter { it.sequence > h }
            val history = stage("J15 history GET 정확히 1") {
                only(logs).also { check(it.text.startsWith("GET ")) { it.text } }
            }
            checkTable(opened) { line ->
                line.sequence == history.sequence && line.text in setOf(
                    "GET $HISTORY q=limit=100 -> UNEXPECTED", "GET $HISTORY q=asset=usdt-krw -> UNEXPECTED"
                )
            }
            stage("J15 history query asset·limit 각 1") {
                check(history.text.substringBefore(" -> ") == "GET $HISTORY q=asset=usdt-krw&limit=100") { history.text }
            }
            stage("J15 history 응답 502·102") {
                val kind = history.text.substringAfter(" -> ")
                check(kind.startsWith("source history body=")) { kind }
                check(created.id != 102) { "history setting_id가 새 id와 같다" }
                equalJson(envelope("logs", JSONObject(HISTORY_ROW)), JSONObject(kind.removePrefix("source history body=")))
            }
            val cells = stage("J15 history 시트·업비트 조건 행 1") { await { historyRow() } }
            stage("J15 history 발송 가격 1384.80") {
                check(cells[2] == "1384.80") { "발송 가격=[${cells[2]}] 행=$cells" }
                Log.i(TAG, "history seq=${history.sequence} 행=$cells")
            }
            finalCounts(tether, s, r, h, post.sequence, read.sequence, history.sequence)
        }
    }

    private fun boundary(name: String): Int {
        mark = stage(ROOT) { preview.phase(nonce, name) }
        phase = name
        return mark
    }

    private fun <T> stage(label: String, action: () -> T): T {
        try {
            return action()
        } catch (e: Exception) {
            throw AssertionError("$label phase=$phase seq=$mark ${e.message}", e)
        } catch (e: AssertionError) {
            throw AssertionError("$label phase=$phase seq=$mark ${e.message}", e)
        }
    }

    private fun shell(command: String) = run.device.executeShellCommand(command).trim()

    private fun checkDevice() {
        val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }
        val airplane = shell("settings get global airplane_mode_on")
        check(Build.VERSION.SDK_INT == 32 && avd.startsWith("Pixel_3a") && airplane == "0") {
            "api=${Build.VERSION.SDK_INT} avd=$avd airplane=$airplane"
        }
    }

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
    }

    private fun owner(state: String): JSONObject = await(60_000) {
        val a = run.client.ack("access", nonce)
        a.getJSONObject("ownedAfter").takeIf { a.getBoolean("stable") && it.optString("state") == state }
    }

    private fun <T : Any> await(timeout: Long = 10_000, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            try {
                read()?.let { return it }
            } catch (_: StaleObjectException) {
                // Recomposition between finding and reading a node.
            }
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < until)
        error("관측 시간 만료 화면=${run.describeScreen()}")
    }

    private fun recordGraphSurface() {
        // The production surface can render no node. A blank graph and an inline error are both observations.
        val tag = "graph_v2:inline:root"
        try {
            run.reveal(tag)
        } catch (missing: IllegalStateException) {
            if (run.onScreen(By.res(tag)).isNotEmpty()) throw missing
        }
        run.settle(nonce)
        val graph = run.onScreen(By.res(tag)).singleOrNull()
        val visible = graph?.let { "bounds=${it.visibleBounds} chart=${it.hasObject(By.res("graph_v2:inline:chart"))} text=${fields(it)}" }
        Log.i(TAG, "tether graph ${visible ?: "표시 노드 없음, 화면=${run.describeScreen()}"}")
    }

    private fun rootOf(node: UiObject2): UiObject2 = generateSequence(node) { it.parent }.last()

    private fun editorOrNull(): UiObject2? {
        val tagged = run.onScreen(By.res("source-alert-editor-sheet"))
        if (tagged.isNotEmpty()) return tagged.single()
        val marker = run.onScreen(By.text("알림 받을 시세")).singleOrNull()
        if (marker != null) return rootOf(marker)
        // Scrolling the fields can clip their marker. The fixed header still identifies the modal window.
        return run.onScreen(By.text("저장")).map(::rootOf).filter {
            it.hasObject(By.text("알림 추가")) && it.hasObject(By.text("취소"))
        }.singleOrNull()
    }

    private fun field(editor: UiObject2, selector: BySelector): UiObject2 =
        run.reveal(selector, selector.toString(), scope = { editorOrNull() ?: editor })

    private fun button(node: UiObject2): UiObject2 =
        generateSequence(node) { it.parent }.first { it.isClickable }.also { check(it.isEnabled) }

    private fun tap(node: UiObject2) {
        check(node.isEnabled && !node.visibleBounds.isEmpty && run.device.currentPackageName == E5dJourneyRun.APP_PACKAGE) {
            "target=${node.resourceName} enabled=${node.isEnabled} bounds=${node.visibleBounds}"
        }
        node.click()
    }

    /** Keep each text field intact, including the condition's number and direction. */
    private fun fields(node: UiObject2): List<String> =
        node.text.orEmpty().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() } +
            run.visible(node.children).flatMap(::fields)

    /** The once row as SourceAlertSection draws it, in its order: exchange, state, price, direction, condition. */
    private fun rowTokens(price: String) = listOf("빗썸", "켜짐", price, "△", "이상")

    /**
     * Reads the issued id's row, found anew on every reading, until it is exactly [rowTokens] of [price] or 5 s pass, and returns
     * the last reading. A row that could not be read at all is a premise failure (thrown); a readable row with other tokens is
     * returned, so the caller's result assertion reports what was on the screen.
     */
    private fun readRow(id: Int, price: String): List<String> {
        val tag = "source_alert_row_open_$id"
        run.reveal(tag)
        var last: List<String>? = null
        val until = SystemClock.elapsedRealtime() + 5_000
        do {
            val read = try {
                run.onScreen(By.res(tag)).singleOrNull()?.let(::fields)?.flatMap { it.split(Regex("""\s+""")) }?.filter { it.isNotEmpty() }
            } catch (_: StaleObjectException) {
                null
            }
            if (!read.isNullOrEmpty()) last = read
            if (read == rowTokens(price)) {
                Log.i(TAG, "phase=$phase seq=$mark id=$id row=$read")
                return read
            }
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < until)
        return last ?: error("id=$id 행을 읽을 수 없다 화면=${run.describeScreen()}")
    }

    /** A tap target with no sheet over it: the editor is gone and no history sheet is open. */
    private fun overlayFree(node: UiObject2): UiObject2 {
        check(editorOrNull() == null && historySheetOrNull() == null) { "시트가 대상 위에 있다 화면=${run.describeScreen()}" }
        return node
    }

    /**
     * The history sheet, by its tag when exposed, else the window root holding both its close button and its title. No
     * candidate is null; more than one is an ambiguous observation and throws, so a tap check before it cannot pass.
     */
    private fun historySheetOrNull(): UiObject2? {
        val tagged = run.onScreen(By.res("source-history-sheet"))
        check(tagged.size <= 1) { "history 시트 tag 후보가 ${tagged.size}개" }
        if (tagged.isNotEmpty()) return tagged.single()
        val roots = run.onScreen(By.text("닫기")).map(::rootOf).filter { it.hasObject(By.text("알림 히스토리")) }.distinct()
        check(roots.size <= 1) { "history 시트 후보가 ${roots.size}개" }
        return roots.singleOrNull()
    }

    /**
     * The one history row as SourceHistorySheet draws it (`SourceHistoryReadRow`): five cells in order — exchange, condition,
     * delivered price, date, time. It is the tagged row if the tag is exposed, else the condition cell's own parent; the sheet or
     * list is never taken as the row. No price, right or wrong, is a locator: the price cell is read afterwards.
     */
    private fun historyRow(): List<String>? {
        val sheet = historySheetOrNull() ?: return null
        val taggedRows = run.visible(sheet.findObjects(By.res("source-history-row")))
        val rows = taggedRows.ifEmpty { run.visible(sheet.findObjects(By.text(CONDITION))).mapNotNull { it.parent } }
        val row = rows.singleOrNull() ?: return null
        // One reading: the cells checked here are the cells the price assertion compares.
        val cells = fields(row)
        val shaped = cells.size == 5 && cells[0] == "업비트" && cells[1] == CONDITION && PRICE.matches(cells[2]) &&
            DATE.matches(cells[3]) && TIME.matches(cells[4])
        return cells.takeIf { shaped }
    }

    private fun E5dJourneyControlClient.Dump.server(from: Int) = E5dJourneyTraffic.server(lines, from, upper)

    /** Count arrivals of all kinds, including 404. The observation ends even when no request was sent. */
    private fun observed(): E5dJourneyControlClient.Dump = stage(ROOT) {
        fun sameProcess() {
            val now = run.discover()
            check(now.getString("nonce") == nonce && now.getInt("pid") == pid &&
                run.device.currentPackageName == E5dJourneyRun.APP_PACKAGE) { "측정 process 또는 전경 화면 변경 pid=$pid" }
        }
        sameProcess()
        run.client.dump(nonce)
        SystemClock.sleep(5_000)
        run.settle(nonce)
        run.device.waitForIdle()
        sameProcess()
        run.client.dump(nonce)
    }

    private fun List<Line>.requests(path: String) = filter { it.stream == "http" && it.text.substringAfter(' ').substringBefore(' ') == path }
    private fun summary(lines: List<Line>) = lines.joinToString { "${it.phase}#${it.sequence} ${it.text}" }
    private fun checkTable(lines: List<Line>, allowed: (Line) -> Boolean = { false }) {
        stage("J15 최종 요청 수·순서") {
            val outside = lines.filter { "UNEXPECTED" in it.text && !allowed(it) }
            check(outside.isEmpty()) { summary(outside) }
        }
    }

    private fun only(lines: List<Line>): Line {
        check(lines.size == 1) { "n=${lines.size} ${summary(lines)}" }
        return lines.single()
    }

    private data class Created(val id: Int, val returned: JSONObject, val stored: JSONObject)

    private fun checkCreation(post: Line): Created {
        val match = stage("J15 source POST가 생성 행을 반환해야 한다") {
            CREATE.matchEntire(post.text) ?: error("seq=${post.sequence} ${post.text}")
        }
        val asked = JSONObject("""{"source":"bithumb","asset":"usdt-krw","condition":"above","threshold":1450,"is_enabled":true}""")
        val evidence = stage("J15 POST 본문이 입력과 일치") {
            JSONObject(match.groupValues[3]).also { equalJson(asked, it.getJSONObject("asked")) }
        }
        return stage("J15 발급 id·저장 행 일치") {
            val id = match.groupValues[1].toInt()
            check(id > 0 && match.groupValues[2] == "1") { post.text }
            val expected = JSONObject(asked.toString()).put("id", id).put("user_id", "u1")
                .put("created_at", "2026-08-31T10:00:00+09:00").put("updated_at", JSONObject.NULL)
                .put("repeat_interval_sec", JSONObject.NULL).put("triggered", false).put("triggered_at", JSONObject.NULL)
            val returned = evidence.getJSONObject("returned")
            val stored = evidence.getJSONObject("stored")
            equalJson(expected, returned)
            equalJson(JSONObject(expected.toString()).put("threshold", 1460.0), stored)
            Created(id, returned, stored)
        }
    }

    private fun envelope(key: String, row: JSONObject) = JSONObject().put(key, JSONArray().put(row)).put("total_count", 1)

    /** org.json's typed getters coerce strings and booleans. Compare the parsed values before coercion. */
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

    private fun finalCounts(tether: Int, s: Int, r: Int, h: Int, post: Int, read: Int, history: Int) {
        val record = observed()
        stage("J15 최종 요청 수·순서") {
            val u = record.upper
            val server = record.server(0)
            assertComparisonRows("테더 진입 전", server.filter { it.sequence <= tether }, 1)
            assertComparisonRows("테더 진입·편집", server.filter { it.sequence in (tether + 1)..s }, 0)
            assertComparisonRows("source 추가", server.filter { it.sequence in (s + 1)..r }, 0)
            assertComparisonRows("source refresh", server.filter { it.sequence in (r + 1)..h }, 0)
            assertComparisonRows("source history", server.filter { it.sequence > h }, 0)
            val writes = server.filter { it.stream == "http" && WRITE.containsMatchIn(it.text) }
            val gets = server.requests(SETTINGS).filter { it.text.startsWith("GET ") }
            val early = gets.filter { it.sequence <= s }
            val logs = server.requests(HISTORY)
            Log.i(TAG, "S=$s R=$r H=$h U=$u prefetch=${summary(early)} writes=${summary(writes)}")
            check(s < post && post < r && r < read && read < h && h < history && history <= u)
            check(writes.map { it.sequence } == listOf(post)) { summary(writes) }
            check(early.isNotEmpty() && early.all { it.text == EMPTY }) { summary(early) }
            check(gets.none { it.sequence in (s + 1)..r } && gets.filter { it.sequence > r }.map { it.sequence } == listOf(read)) { summary(gets) }
            check(logs.map { it.sequence } == listOf(history)) { summary(logs) }
            val graph = server.filter { it.sequence > tether && it.stream == "http" && it.text == GRAPH }
            check(graph.isNotEmpty()) { "tether/1d 응답 0" }
            Log.i(TAG, "tether/1d ${summary(graph)}")
            check(server.none { "UNEXPECTED" in it.text }) { summary(server.filter { "UNEXPECTED" in it.text }) }
        }
    }

    private companion object {
        const val TAG = "E5dJourneyJ15"
        const val DEVICE = "PREMISE J15 장치·알림 조건"
        const val ROOT = "PREMISE J15 새 달러 Root·fixture 준비"
        const val SETTINGS = "/api/source-notification-settings"
        const val HISTORY = "/api/source-notification-logs"
        const val EMPTY = "GET $SETTINGS q=asset=usdt-krw -> source settings list n=0"
        const val GRAPH = "GET /api/v2/graph/tab q=period=1d&tab=tether -> graph-tab tether 1d"
        const val CONDITION = "1385.00 ▽ 이하"
        const val HISTORY_ROW = """{"id":502,"setting_id":102,"source":"upbit","asset":"usdt-krw","condition":"below","threshold":1385.0,"triggered_rate":1384.8,"sent_at":"2026-08-31T10:00:00+09:00"}"""
        val STARTUP_FREE = Regex("""GET /api/entitlements q=(fresh_premium=true)? -> entitlements free rev=0""")
        val EDIT = By.clazz("android.widget.EditText")
        val CREATE = Regex("""POST $SETTINGS q= -> source create id=(\d+) n=(\d+) evidence=(.+)""")
        const val TAP = "PREMISE J15 조작 대상 접근"
        const val ROW = "PREMISE J15 발급 id 행 판독"
        val PRICE = Regex("""\d+\.\d{2}""")
        val DATE = Regex("""\d{2}-\d{2}""")
        val TIME = Regex("""\d{2}:\d{2}""")
        val WRITE = Regex("""^(POST|PUT|PATCH|DELETE) /api/(notification-settings|source-notification-settings|comparison-alerts)(/|\s)""")
    }
}
