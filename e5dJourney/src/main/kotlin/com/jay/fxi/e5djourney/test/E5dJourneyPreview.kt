package com.jay.fxi.e5djourney.test

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * S7 E5d-J J2b (`e5dj_j2_plan.claude.r3.md` §3): the free screen and the bank alert preview as every journey drives them — the
 * real tab row, the page heading, the preview section, its modal sheet, the CTA held at the host's real breakpoint, and the
 * paywall. Moved here unchanged from J09 (J2b1) so that J10–J12 drive the screen the same way.
 *
 * The sheet is a modal in its own window, where the build type's test-tag resource ids do not reach: it is found by its title
 * and read and touched only inside that window, by the texts and descriptions the user sees. The page's open button carries the
 * same words as the title and is never taken for the sheet.
 *
 * [restartOn] is J2b2's start (`s7e5dj_j2b2_design.codex.r2.md` §2-3): a tab chosen through the real tab row and kept by the
 * app's own store, then a new process of the app through its launcher entry, whose first selected screen must be that tab.
 */
internal class E5dJourneyPreview(private val run: E5dJourneyRun) {

    class Held(val set: Long, val operation: Long)

    /** A preview page's mount as the record names it: `set <set> mount|dispose host=<host> mount=<mount> tab=<tab>`. */
    data class Mount(val set: String, val host: String, val mount: String, val tab: String)

    /**
     * The app in front, with whatever an earlier case left open (a sheet, a paywall) closed first, and the page title shown. A
     * premium Root an earlier case reached is ended by a new process (the fixture's answer starts free in every process).
     */
    fun ready() {
        run.launch()
        if (premiumShown()) {
            endProcess()
            run.launch()
        }
        val deadline = System.currentTimeMillis() + COLD_START_MILLIS
        while (sheetOrNull() == null && !paywallShown() && run.onScreen(By.text("달러")).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "화면에 '달러'도 시트도 paywall 도 오지 않았다" }
            Thread.sleep(500)
        }
        clear()
        run.waitText("달러", timeoutMillis = COLD_START_MILLIS)
        run.reveal(By.text(SCREEN_TITLE), SCREEN_TITLE, Direction.UP)
    }

    /** Closes a sheet or a paywall an earlier case left open: one Back at a time, each followed by the wait for the sheet to be gone. */
    fun clear() {
        repeat(2) {
            if (sheetOrNull() == null) return@repeat
            run.device.pressBack()
            runCatching { sheetGone(timeoutMillis = 5_000) }
        }
        if (paywallShown()) closePaywall()
    }

    /**
     * Chooses [tab] through the real tab row (from [other] first, so the choice is this run's), lets the app keep it, then
     * starts a new process: force-stop, the process gone, the launcher entry. The new process's first selected screen must be
     * [tab] — a premise, never corrected by the fixture — and its nonce and pid must be new. Returns the new discovery.
     */
    fun restartOn(other: String, tab: String): JSONObject {
        val old = run.discover()
        choose(other, tab)
        // A moment for the app's own store to write the choice (it does so in a coroutine). Whether it did is not assumed: the
        // new process's first screen below is the check, and a choice that was not kept is a premise failure.
        Thread.sleep(SAVE_SETTLE_MILLIS)
        endProcess()
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        val now = run.discover()
        assertTrue("새 process", now.getString("nonce") != old.getString("nonce") && now.getInt("pid") != old.getInt("pid"))
        firstSelected(tab)
        return now
    }

    /**
     * The first selected screen of a new process is [tab]'s page, before anything is touched. The screen alone does not say
     * which tab is selected (the tab row exposes no selection), so a journey also reads the new process's record.
     */
    fun firstSelected(tab: String) = pageShown(tab)

    /** Whether the premium Root is in front (its settings button, J2b2). */
    fun premiumShown(): Boolean = run.onScreen(By.res(PREMIUM_MARKER)).isNotEmpty()

    /** Ends the app's process: force-stop, then its process gone — a premise; nothing else is sent to the app. */
    fun endProcess() {
        run.device.executeShellCommand("am force-stop ${E5dJourneyControlClient.PACKAGE}")
        val deadline = System.currentTimeMillis() + RESTART_MILLIS
        while (run.device.executeShellCommand("pidof ${E5dJourneyControlClient.PACKAGE}").isNotBlank()) {
            check(System.currentTimeMillis() < deadline) { "premise: force-stop 뒤에도 앱 process 가 남았다" }
            Thread.sleep(200)
        }
    }

    /** Picks [other] and then [target] through the real tab row, and waits for [target]'s page (a data page: fresh). */
    fun choose(other: String, target: String) {
        tapTab(other)
        pageShown(other)
        tapTab(target)
        pageShown(target)
        if (target != NEWS && target != TETHER) run.waitText("정상")
    }

    /** Taps [name] in the tab row: the topmost node with that text, since a page below may show the same word (테더). */
    fun tapTab(name: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val node = runCatching { run.onScreen(By.text(name)).minByOrNull { it.visibleBounds.top } }.getOrNull()
            if (node != null) return node.click()
            check(System.currentTimeMillis() < deadline) { "탭 '$name' 이(가) 화면에 오지 않았다" }
            Thread.sleep(200)
        }
    }

    /**
     * [tab]'s page is shown: a data page's heading (`<tab> · …`); for 테더, whose heading is the bare word, that word twice —
     * the tab row and the page; for 뉴스, which has no heading, the fixture's empty news page.
     */
    fun pageShown(tab: String) = when (tab) {
        NEWS -> run.waitText(NEWS_EMPTY, timeoutMillis = COLD_START_MILLIS)
        TETHER -> {
            val deadline = System.currentTimeMillis() + COLD_START_MILLIS
            while (true) {
                // The heading is the list's first item: the page is scrolled to its top, then the word must show twice.
                var steps = 0
                while (steps++ < E5dJourneyRun.MAX_SCROLLS && run.scrollPage(Direction.UP)) Unit
                if (run.onScreen(By.text(TETHER)).size == 2) break
                check(System.currentTimeMillis() < deadline) { "'$TETHER' page 가 오지 않았다. 화면: ${run.describeScreen()}" }
                Thread.sleep(500)
            }
        }
        else -> heading(tab)
    }

    /**
     * The page heading for [tab], scrolled back into view: it is the list's first item, and a page scrolled down by an earlier
     * step — or restored scrolled down — has it off screen, so the list is scrolled up until it shows or the top is reached.
     * Tried again until [COLD_START_MILLIS] for a page that is still coming up.
     */
    fun heading(tab: String) {
        val deadline = System.currentTimeMillis() + COLD_START_MILLIS
        while (true) {
            if (runCatching { run.reveal(By.textStartsWith("$tab · "), "$tab · ", Direction.UP, bothWays = false) }.isSuccess) return
            check(System.currentTimeMillis() < deadline) { "'$tab · ' 제목이 오지 않았다. 화면: ${run.describeScreen()}" }
            Thread.sleep(500)
        }
    }

    /** Expands the bank preview if needed, opens it, and returns the token the record names for the sheet now shown. */
    fun open(): Long {
        // The section is a lazy item further down the page: scrolled to; expanded only if its open button is not there just
        // below the toggle (a page keeps the section expanded), then the open button.
        run.reveal(TOGGLE)
        val expanded = runCatching { run.reveal(By.res(OPEN), OPEN, maxScrolls = 2, bothWays = false) }.isSuccess
        if (!expanded) {
            run.reveal(TOGGLE).click()
            run.device.waitForIdle()
        }
        run.reveal(OPEN).click()
        sheet()
        val nonce = run.discover().getString("nonce")
        val lines = run.awaitRecord(nonce, "시트 표시") { lines -> texts(lines, "hook").any { Regex(""".* event shown \d+""").matches(it) } }
        return texts(lines, "hook").last { Regex(""".* event shown \d+""").matches(it) }.substringAfterLast(' ').toLong()
    }

    /**
     * The open sheet's window root, found by its title; null when no sheet is shown, or while it is recomposing (stale). The
     * page's open button carries the same words as the title, so a match that is that button or inside it is not the sheet
     * (`e5dj_j2b_dev11`: the button on screen was taken for a sheet, and the Back meant to close it left the app). More than
     * one title left is a failure.
     */
    fun sheetOrNull(): UiObject2? = try {
        val titles = run.onScreen(By.text(SHEET_TITLE)).filterNot { underOpenButton(it) }
        check(titles.size <= 1) { "시트 제목이 ${titles.size}개" }
        var node = titles.singleOrNull()
        while (node != null) node = node.parent ?: break
        node
    } catch (stale: StaleObjectException) {
        null
    }

    private fun underOpenButton(node: UiObject2): Boolean {
        var at: UiObject2? = node
        while (at != null) {
            if (at.resourceName == OPEN) return true
            at = at.parent
        }
        return false
    }

    fun sheet(timeoutMillis: Long = 30_000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            sheetOrNull()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "시트가 화면에 오지 않았다" }
            Thread.sleep(200)
        }
    }

    fun sheetGone(timeoutMillis: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (sheetOrNull() != null) {
            if (System.currentTimeMillis() >= deadline) {
                val titles = run.onScreen(By.text(SHEET_TITLE)).map { runCatching { it.visibleBounds.toShortString() }.getOrDefault("?") }
                val nonce = run.discover().getString("nonce")
                val tail = texts(run.client.dump(nonce).lines, "hook").takeLast(6)
                error("시트가 사라지지 않았다: 제목 $titles, hook 끝 $tail")
            }
            Thread.sleep(200)
        }
    }

    /**
     * The one node [selector] finds inside the sheet's window, scrolled to downwards inside the sheet: in landscape the sheet's
     * fields run below its short viewport (`e5dj_j2b_dev12`). Never upwards — dragging a sheet already at its top down closes it.
     * More than one, or none down to the end, is a failure.
     */
    fun inSheet(selector: BySelector, what: String): UiObject2 =
        run.reveal(selector, what, Direction.DOWN, bothWays = false, scope = { sheet() })

    /**
     * The first open's threshold, after checking the opening values — KB, the threshold of the snapshot, Below, Once — in the
     * order the sheet lays them out from the top, so that scrolling down to one never passes another.
     */
    fun initialThreshold(): String {
        inSheet(By.text("국민은행"), "국민은행")
        val threshold = inSheet(By.clazz("android.widget.EditText"), "임계값").text.orEmpty()
        assertTrue("premise: 당시 snapshot 의 임계값 '$threshold'", threshold.isNotBlank())
        inSheet(By.text("이하"), "이하")
        inSheet(By.text("한번만"), "한번만")
        return threshold
    }

    /** A draft: another bank and the other direction. */
    fun edit() {
        inSheet(By.desc("하나은행"), "하나은행").click()
        run.device.waitForIdle()
        inSheet(By.text("하나은행"), "하나은행")
        inSheet(By.text("이하"), "이하").click()
        run.device.waitForIdle()
        inSheet(By.text("이상"), "이상")
    }

    /**
     * Arms the real breakpoint for the host as it is now, taps the CTA, and waits until the delivery is held: the record has the
     * sheet's removal for this token before the hold's entry, and no paywall is shown.
     */
    fun holdCta(nonce: String, token: Long): Held {
        val sets = run.client.ack("hosts", nonce).getJSONArray("sets")
        val live = (0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.getBoolean("attached") && !it.isNull("host") }
        assertEquals("premise: host 하나 $live", 1, live.size)
        val host: JSONObject = live.single()
        val set = host.getLong("set")
        val operation = run.client.ack("arm", nonce) {
            putExtra("set", set); putExtra("host", host.getLong("host")); putExtra("session", host.getLong("session"))
            putExtra("binding", host.getLong("binding"))
        }.getLong("operation")
        // An arm for another binding is refused.
        run.client.reject("arm", nonce, "host") {
            putExtra("set", set); putExtra("host", host.getLong("host")); putExtra("session", host.getLong("session"))
            putExtra("binding", host.getLong("binding") + 1)
        }
        inSheet(By.text(CTA_TEXT), CTA_TEXT).click()
        sheetGone()
        val lines = run.awaitRecord(nonce, "hold 진입") { lines -> texts(lines, "hook").any { it.endsWith("hold entered op=$operation token=$token") } }
        val hooks = texts(lines, "hook")
        val removed = hooks.indexOfLast { it.endsWith("event removed $token") }
        val entered = hooks.indexOfFirst { it.endsWith("hold entered op=$operation token=$token") }
        assertTrue("removed 가 hold 진입보다 먼저 기록됐다 $hooks", removed in 0 until entered)
        assertFalse("hold 중에는 paywall 이 없다", paywallShown())
        return Held(set, operation)
    }

    fun phase(nonce: String, name: String): Int = run.client.ack("phase", nonce) { putExtra("arg", name) }.getInt("sequence")

    fun paywallShown() = run.onScreen(By.text(PAYWALL_TEXT)).isNotEmpty()

    fun closePaywall() {
        run.onScreen(By.desc("닫기")).firstOrNull()?.click() ?: error("paywall 닫기가 없다")
        run.waitGoneText(PAYWALL_TEXT)
    }

    companion object {
        const val SCREEN_TITLE = "무료 · 시간별 스냅샷"
        const val PAYWALL_TEXT = "환율 동향을 한눈에"
        const val COLD_START_MILLIS = 60_000L
        const val LATE_MILLIS = 3_000L
        const val RESTART_MILLIS = 10_000L
        const val SAVE_SETTLE_MILLIS = 2_000L
        const val TOGGLE = "free-alert-preview-toggle"
        const val OPEN = "free-alert-preview-open"
        const val SHEET_TITLE = "알림 추가 (미리보기)"
        const val CTA_TEXT = "구독 혜택 보기"
        const val NEWS = "뉴스"
        const val TETHER = "테더"
        const val NEWS_EMPTY = "뉴스가 없습니다"
        const val PREMIUM_MARKER = "premium_settings"
        private val MOUNT = Regex("""set (\d+) mount (?:bound )?host=(\d+) mount=(\d+) tab=(\w+)""")
        private val DISPOSE = Regex("""set (\d+) dispose host=(\d+) mount=(\d+) tab=(\w+)""")

        fun mounts(lines: List<String>): List<Mount> = texts(lines, "hook").mapNotNull { line ->
            MOUNT.matchEntire(line)?.groupValues?.let { Mount(it[1], it[2], it[3], it[4]) }
        }

        fun disposes(lines: List<String>): List<Mount> = texts(lines, "hook").mapNotNull { line ->
            DISPOSE.matchEntire(line)?.groupValues?.let { Mount(it[1], it[2], it[3], it[4]) }
        }

        /** The [tab] page's mount still alive in [lines]: the last such mount the lines have no dispose for. */
        fun liveMount(lines: List<String>, tab: String): Mount? {
            val gone = disposes(lines).toSet()
            return mounts(lines).lastOrNull { it.tab == tab && it !in gone }
        }
    }
}
