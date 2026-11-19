package com.jay.fxi.e5djourney.test

import android.content.Intent
import android.graphics.Rect
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R4·R5·R7): what every journey shares — the app launched through its launcher entry,
 * the screen read and touched through UiAutomator on the build type's resource ids, the control channel, and the fixture
 * record read up to a fixed upper. [run] gives a journey its process nonce and, whatever the outcome, collects and checks that
 * process's whole record at the end (`s7e5dj_j2a.codex.r1.md` Q4-D): a record that cannot be collected fails the run, or is
 * kept as suppressed under the journey's own failure; one with an unexpected request is written marked UNVERIFIED and fails.
 * Before discovery there is no record to collect. A spoiled client (a timed-out call that may still be running,
 * `s7e5dj_j2b_channel.codex.r1.md` R1) collects nothing and fails the run; and a case that follows one starts from a new
 * process of the app, so it reads no evidence from the process that call went to.
 */
internal class E5dJourneyRun {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val client = E5dJourneyControlClient(instrumentation.context)

    fun close() = client.close()

    /**
     * Brings the app to the front through its launcher entry. When a call got no answer since the last launch — known process
     * or not ([E5dJourneyControlClient.isolationOwed]) — the package is force-stopped first, and the isolation is confirmed by
     * its process being gone before anything else happens; not confirmed, it is a premise failure and nothing is cleared.
     */
    fun launch() {
        val gone = synchronized(E5dJourneyControlClient.spoiled) { E5dJourneyControlClient.spoiled.toList() }
        if (E5dJourneyControlClient.isolationOwed || gone.isNotEmpty()) {
            Log.i(RECORD_TAG, "isolating after a call with no answer (known processes $gone): force-stop before launch")
            device.executeShellCommand("am force-stop ${E5dJourneyControlClient.PACKAGE}")
            val deadline = System.currentTimeMillis() + ISOLATION_MILLIS
            while (device.executeShellCommand("pidof ${E5dJourneyControlClient.PACKAGE}").isNotBlank()) {
                check(System.currentTimeMillis() < deadline) { "premise: force-stop 뒤에도 앱 process 가 남았다; 격리를 확인하지 못했다" }
                Thread.sleep(200)
            }
            E5dJourneyControlClient.isolated += gone
            E5dJourneyControlClient.spoiled.removeAll(gone.toSet())
            E5dJourneyControlClient.isolationOwed = false
        }
        val intent = checkNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(E5dJourneyControlClient.PACKAGE)) {
            "premise: the app has a launcher entry"
        }
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Waits until the fixture server has written nothing for [quietMillis] (J2b2: moved here from J10 for J10 and J11). */
    fun settle(nonce: String, quietMillis: Long = QUIET_MILLIS, timeoutMillis: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last = E5dJourneyTraffic.server(client.dump(nonce).lines, 0).size
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - quietSince < quietMillis) {
            check(System.currentTimeMillis() < deadline) { "서버 수신이 잦아들지 않았다" }
            Thread.sleep(250)
            val now = E5dJourneyTraffic.server(client.dump(nonce).lines, 0).size
            if (now != last) { last = now; quietSince = System.currentTimeMillis() }
        }
    }

    fun waitText(text: String, timeoutMillis: Long = 30_000) =
        assertTrue("화면에 '$text'", device.wait(Until.hasObject(By.textContains(text)), timeoutMillis))

    fun waitGoneText(text: String, timeoutMillis: Long = 10_000) =
        assertTrue("화면에서 '$text' 이(가) 사라진다", device.wait(Until.gone(By.textContains(text)), timeoutMillis))

    private fun screen(): Rect = Rect(0, 0, device.displayWidth, device.displayHeight)

    /** The nodes [selector] finds whose bounds are on the screen: offscreen pager pages and clipped items are not targets. */
    fun onScreen(selector: BySelector): List<UiObject2> = visible(device.findObjects(selector))

    /** [nodes] whose bounds are on the screen. */
    fun visible(nodes: List<UiObject2>): List<UiObject2> =
        nodes.filter { node -> runCatching { node.visibleBounds }.getOrNull()?.let { !it.isEmpty && Rect.intersects(it, screen()) } == true }

    /** The one node with [tag] on the screen, waited for; none or more than one is a failure. */
    fun node(tag: String, timeoutMillis: Long = 10_000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val found = onScreen(By.res(tag))
            if (found.size == 1) return found.single()
            check(found.size <= 1) { "'$tag' 이(가) 화면에 ${found.size}개" }
            check(System.currentTimeMillis() < deadline) { "'$tag' 이(가) 화면에 오지 않았다" }
            Thread.sleep(200)
        }
    }

    fun absent(tag: String): Boolean = onScreen(By.res(tag)).isEmpty()

    /** Every visible text with its bounds, for a failure message: what the screen showed when something was not found. */
    fun describeScreen(): String = runCatching {
        onScreen(By.textStartsWith("")).filter { it.text != null }.joinToString("; ") { "'${it.text}'${it.visibleBounds.toShortString()}" }
    }.getOrDefault("?")

    /** Where the visible texts are now: two equal fingerprints mean nothing on the screen moved. */
    private fun fingerprint(): String =
        runCatching { device.findObjects(By.textStartsWith("")).joinToString("|") { "${it.text}@${it.visibleBounds.top}" } }.getOrDefault("")

    private fun depth(node: UiObject2): Int {
        var d = 0
        var at: UiObject2? = node.parent
        while (at != null) { d++; at = at.parent }
        return d
    }

    /**
     * Scrolls the page's list once in [direction]; false when nothing on the screen moved, i.e. the list is at its end that way.
     * The list is found by what moves: the app's own tall scrollable nodes are tried innermost first, and the first whose scroll
     * changes what is on the screen is it (a graph or a pager can be as tall, and scrolling them that way moves nothing). Nothing
     * outside the app is scrolled: with the app not in front there is no list, and the answer is false.
     *
     * The swipe keeps [EDGE_FRACTION] of the screen's height away from its top and bottom edges as well as a tenth of the list
     * inside it: the list runs under the navigation bar, and in landscape a tenth of it is short enough for a swipe up to start
     * in the system's bottom gesture area and open recents instead of scrolling (`e5dj_j2b_dev11`). A list with too little
     * height left between those margins is not swiped at all.
     *
     * With [scope], only the scrollable nodes inside the node it gives are tried (a sheet's own window), and a shorter one
     * counts as tall: a sheet in landscape leaves its fields a short viewport.
     */
    fun scrollPage(direction: Direction, scope: (() -> UiObject2)? = null): Boolean {
        val candidates = runCatching {
            val nodes = if (scope == null) onScreen(By.scrollable(true).pkg(APP_PACKAGE)) else visible(scope().findObjects(By.scrollable(true)))
            val tall = if (scope == null) device.displayHeight / 4 else device.displayHeight / 8
            nodes.filter { it.visibleBounds.height() >= tall }.sortedByDescending { depth(it) }
        }.getOrDefault(emptyList())
        for (candidate in candidates) {
            val before = fingerprint()
            try {
                val bounds = candidate.visibleBounds
                val edge = (device.displayHeight * EDGE_FRACTION).toInt()
                val inner = bounds.height() / 10
                val top = maxOf(inner, edge - bounds.top)
                val bottom = maxOf(inner, bounds.bottom - (device.displayHeight - edge))
                if (bounds.height() - top - bottom < bounds.height() * 3 / 10) continue
                candidate.setGestureMargins(bounds.width() / 10, top, bounds.width() / 10, bottom)
                candidate.scroll(direction, 0.5f)
            } catch (stale: StaleObjectException) {
                continue
            }
            device.waitForIdle()
            if (fingerprint() != before) return true
        }
        return false
    }

    /**
     * The one node [selector] finds, scrolled into view: a lazy list composes only what is on screen, so a node further along
     * is not there until the list is scrolled to it. It scrolls [first] until the node shows or the list stops moving, then the
     * other way; [maxScrolls] is only a guard against a list that never stops, set well past any page's length, since turning
     * back before the end undoes the way already scrolled (`e5dj_j2b_dev11`: six steps up and six back down, again and again,
     * in landscape). With [bothWays] false it goes [first] only. With [scope], the node is looked for, and scrolled to, only
     * inside the node [scope] gives.
     */
    fun reveal(
        selector: BySelector,
        what: String,
        first: Direction = Direction.DOWN,
        maxScrolls: Int = MAX_SCROLLS,
        bothWays: Boolean = true,
        scope: (() -> UiObject2)? = null
    ): UiObject2 {
        fun find() = if (scope == null) onScreen(selector) else visible(scope().findObjects(selector))
        val opposite = when (first) { Direction.DOWN -> Direction.UP; Direction.UP -> Direction.DOWN; Direction.LEFT -> Direction.RIGHT; Direction.RIGHT -> Direction.LEFT }
        for (direction in if (bothWays) listOf(first, opposite) else listOf(first)) {
            for (step in 0 until maxScrolls) {
                // A node that recomposes between being found and being used is stale: it is simply looked for again.
                val found = try { find() } catch (stale: StaleObjectException) { emptyList() }
                check(found.size <= 1) { "'$what' 이(가) 화면에 ${found.size}개" }
                found.singleOrNull()?.let { return it }
                if (!scrollPage(direction, scope)) break
            }
        }
        return find().singleOrNull() ?: error("'$what' 이(가) 스크롤해도 화면에 없다. 화면: ${describeScreen()}")
    }

    fun reveal(tag: String): UiObject2 = reveal(By.res(tag), tag)

    fun awaitAbsent(tag: String, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!absent(tag)) {
            check(System.currentTimeMillis() < deadline) { "'$tag' 이(가) 화면에서 사라지지 않았다" }
            Thread.sleep(200)
        }
    }

    fun tap(tag: String) = node(tag).click()

    /** Taps the one on-screen node whose whole text is [text]. */
    fun tapText(text: String, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val found = onScreen(By.text(text))
            if (found.size == 1) return found.single().click()
            check(found.size <= 1) { "'$text' 이(가) 화면에 ${found.size}개" }
            check(System.currentTimeMillis() < deadline) { "'$text' 이(가) 화면에 오지 않았다" }
            Thread.sleep(200)
        }
    }

    /** The node's own text, or its descendants' texts joined, for a tag on a container. */
    fun textOf(tag: String): String {
        val node = node(tag)
        node.text?.let { return it }
        fun texts(n: UiObject2): List<String> = listOfNotNull(n.text) + n.children.flatMap { texts(it) }
        return texts(node).joinToString(" ")
    }

    fun awaitRecord(nonce: String, what: String, timeoutMillis: Long = 10_000, test: (List<String>) -> Boolean): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val lines = client.dump(nonce).lines
            if (test(lines)) return lines
            check(System.currentTimeMillis() < deadline) { "기록에 $what 이(가) 오지 않았다" }
            Thread.sleep(250)
        }
    }

    /**
     * The running process's discovery answer (nonce and pid). A process a timed-out call went to — still noted, or already
     * force-stopped — is refused before any of its evidence is read: the isolation the next case relies on is checked, not
     * assumed (`s7e5dj_j2b_channel.codex.r1.md` R1).
     */
    fun discover(): org.json.JSONObject {
        // Before the isolation a timeout left owing, nothing is sent: the given-up call may still hold the provider's lock there.
        check(!E5dJourneyControlClient.isolationOwed) { "premise: 응답 없는 call 뒤 격리 전이다; 앱에 보내지 않는다" }
        val body = client.ack(E5dJourneyControlClient.DISCOVER, null)
        val nonce = body.getString("nonce")
        check(nonce !in E5dJourneyControlClient.spoiled && nonce !in E5dJourneyControlClient.isolated) {
            "premise: process $nonce 에는 응답 없는 call 이 갔다; 그 증거는 읽지 않는다"
        }
        return body
    }

    /** Discovers the running process and runs [journey] on its nonce, collecting its record at the end in every case. */
    fun run(journey: (nonce: String) -> Unit) {
        val nonce = discover().getString("nonce")
        var failure: Throwable? = null
        try {
            journey(nonce)
        } catch (journeyFailure: Throwable) {
            failure = journeyFailure
            throw journeyFailure
        } finally {
            collect(nonce, failure)
        }
    }

    /**
     * The run's evidence: [nonce]'s whole record up to its fixed upper, checked for continuity, overflow and unexpected
     * requests, then written to logcat. A record that cannot be collected fails; one that is collected but fails a check is
     * still written, marked unverified, and then fails. [failure], if any, keeps a collection failure as suppressed.
     */
    fun collect(nonce: String, failure: Throwable?) {
        try {
            check(!client.isSpoiled) { "record not collected: the control client is spoiled; nonce=$nonce UNVERIFIED" }
            val record = client.dump(nonce)
            // Both server streams: a request outside the table, and a WebSocket frame or event outside it (J2b2 D3).
            val unexpected = (stream(record.lines, "http") + stream(record.lines, "ws")).filter { "UNEXPECTED" in it }
            val verified = unexpected.isEmpty()
            record.lines.forEach { Log.i(RECORD_TAG, it) }
            Log.i(RECORD_TAG, "record ${if (verified) "complete" else "UNVERIFIED"} nonce=$nonce upper=${record.upper}")
            check(verified) { "표 밖의 요청 $unexpected" }
        } catch (collection: Throwable) {
            if (failure != null) failure.addSuppressed(collection) else throw collection
        }
    }

    companion object {
        const val RECORD_TAG = "E5dJourneyRecord"
        const val APP_PACKAGE = E5dJourneyControlClient.PACKAGE
        const val EDGE_FRACTION = 0.15
        const val MAX_SCROLLS = 40
        const val ISOLATION_MILLIS = 10_000L
        const val QUIET_MILLIS = 3_000L

        fun stream(lines: List<String>, name: String) = lines.filter { it.split('\t').getOrNull(2) == name }

        fun texts(lines: List<String>, name: String) = stream(lines, name).map { it.split('\t', limit = 5).getOrElse(4) { "" } }

        /** The lines after sequence [mark] (exclusive), for a phase mark returned by the channel. */
        fun after(lines: List<String>, mark: Int) = lines.drop(mark)
    }
}
