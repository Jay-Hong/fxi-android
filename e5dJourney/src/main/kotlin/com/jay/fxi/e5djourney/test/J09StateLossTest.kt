package com.jay.fxi.e5djourney.test

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2b G09 (`e5d_gaphunt.claude.r4.md` G09 and R13, `e5dj_j2_plan.claude.r3.md` §3 G09 with judgement r1 R9): the
 * preview's open sheet, its draft and a CTA held before delivery are all gone after the system recreates the Activity for a real
 * rotation, and after a real process death with the same task brought back; the reopened preview starts from KB, Below, Once
 * and the snapshot of the moment; the free tab the user chose is still the tab shown; and no paywall appears, late or not.
 *
 * - The hold is the host's real breakpoint: armed for the exact host, session and binding, entered after the sheet's removal is
 *   applied, and released only by its operation and token (`E5dJourneyHooks`). The positive control releases it and sees the
 *   real delivery open the paywall, so "no paywall" below is a result and not a blind spot.
 * - Rotation: configuration change, the same process (nonce and PID) and a new Activity instance, each from the record.
 * - Process death: HOME, the Activity's own stop and saved state and the process's stop, the whole record collected, `am kill`,
 *   the PID gone, the task still in recents with its component, then `ActivityManager.moveTaskToFront` of that task — nothing
 *   is sent to the app from the kill to the return. Afterwards: a new nonce and PID, the same task, the Activity created with a
 *   saved state, and every command for the old process refused.
 * - The tab is chosen through the real tab row, away from and then onto the target, so the preference kept is this run's.
 * - The sheet is a modal in its own window, where the build type's test-tag resource ids do not reach: it is found by its title
 *   and read and touched only inside that window, by the texts and descriptions the user sees.
 */
private typealias Held = E5dJourneyPreview.Held

@RunWith(AndroidJUnit4::class)
class J09StateLossTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)

    /** The app in front, with whatever an earlier case left open (a sheet, a paywall) closed first, and the page title shown. */
    @Before
    fun ready() = preview.ready()

    /** Leaves no sheet, keyboard or paywall for the next case, and the natural orientation. */
    @After
    fun close() {
        runCatching { clear() }
        runCatching { run.device.setOrientationNatural() }
        runCatching { run.device.unfreezeRotation() }
        run.close()
    }

    // The hold and the paywall --------------------------------------------------------------------------------------------

    @Test
    fun J09_0_releasingTheHeldCtaDeliversTheRealPaywall() = run.run { nonce ->
        choose(other = "유로", target = "엔화")
        val token = open()
        val held = holdCta(nonce, token)
        assertFalse("hold 중에는 paywall 이 없다", paywallShown())
        run.client.ack("release", nonce) { putExtra("set", held.set); putExtra("operation", held.operation); putExtra("token", token) }
        run.awaitRecord(nonce, "해제와 전달") { lines ->
            val hooks = texts(lines, "hook")
            hooks.any { it.endsWith("hold ended op=${held.operation} reason=released") } && hooks.any { it.endsWith("event delivered $token") }
        }
        run.waitText(PAYWALL_TEXT)
        run.client.reject("release", nonce, "operation") { putExtra("set", held.set); putExtra("operation", held.operation); putExtra("token", token) }
        closePaywall()
    }

    // Rotation -------------------------------------------------------------------------------------------------------------

    @Test
    fun J09_1_aRotationWithTheSheetOpenLeavesNoSheetAndNoDraft() = run.run { nonce ->
        choose(other = "유로", target = "엔화")
        val token = open()
        val initial = initialThreshold()
        edit()
        val mark = phase(nonce, "rotate-open")
        val before = activities(nonce)
        run.device.setOrientationLeft()
        val recreated = awaitRecreated(nonce, mark, before, landscape = true)
        assertTrue("연 시트가 제거됐다", texts(after(recreated, mark), "hook").any { it.endsWith("event removed $token") })
        afterLoss(nonce, target = "엔화", initial = initial, token = token)
        val back = phase(nonce, "rotate-back")
        val beforeBack = activities(nonce)
        run.device.setOrientationNatural()
        awaitRecreated(nonce, back, beforeBack, landscape = false)
    }

    @Test
    fun J09_2_aRotationWithTheCtaHeldDeliversNothing() = run.run { nonce ->
        choose(other = "엔화", target = "유로")
        val token = open()
        val initial = initialThreshold()
        edit()
        val held = holdCta(nonce, token)
        val mark = phase(nonce, "rotate-pending")
        val before = activities(nonce)
        run.device.setOrientationLeft()
        awaitRecreated(nonce, mark, before, landscape = true)
        // The host left first; its cancelled call resumes on the main thread a little later, then records how it ended.
        val ended = run.awaitRecord(nonce, "held 호출의 취소") { lines ->
            texts(after(lines, mark), "hook").any { it.endsWith("hold ended op=${held.operation} reason=cancelled") }
        }
        val hooks = texts(after(ended, mark), "hook")
        assertTrue("host 가 떠나며 hold 를 놓았다 $hooks", hooks.any { it.endsWith("hold op=${held.operation} abandoned reason=host-left") })
        afterLoss(nonce, target = "유로", initial = initial, token = token)
        // The old set left with its host: a release for it is refused.
        run.client.reject("release", nonce, "set") { putExtra("set", held.set); putExtra("operation", held.operation); putExtra("token", token) }
        run.device.setOrientationNatural()
    }

    // Process death --------------------------------------------------------------------------------------------------------

    @Test
    fun J09_3_aProcessDeathWithTheSheetOpenComesBackWithoutIt() = death(pending = false, other = "유로", target = "엔화")

    @Test
    fun J09_4_aProcessDeathWithTheCtaHeldComesBackWithoutAPaywall() = death(pending = true, other = "엔화", target = "유로")

    private fun death(pending: Boolean, other: String, target: String) {
        val old = run.discover()
        val nonce = old.getString("nonce")
        val pid = old.getInt("pid")
        var token = 0L
        var initial = ""
        var held: Held? = null
        var task = ""
        var failure: Throwable? = null
        try {
            choose(other, target)
            token = open()
            initial = initialThreshold()
            edit()
            if (pending) held = holdCta(nonce, token)
            val baseline = activities(nonce).last()
            val home = phase(nonce, "home")
            run.device.pressHome()
            run.awaitRecord(nonce, "background 저장", timeoutMillis = 15_000) { lines ->
                val later = after(lines, home)
                texts(later, "activity").any { it.startsWith("MainActivity#${baseline.id} saved-state ") } &&
                    texts(later, "activity").any { it.startsWith("MainActivity#${baseline.id} stopped ") } &&
                    texts(later, "process").contains("ON_STOP")
            }
            task = baseline.task
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            // Every line of the old process, before it is killed.
            run.collect(nonce, failure)
        }
        // From here until the task is back, nothing is sent to the app. The kill is repeated, never anything else: in
        // `e5dj_j2b_dev9` a single `am kill` right after the collection left the process running; why was not established.
        val deadline = System.currentTimeMillis() + 20_000
        var kills = 0
        while (run.device.executeShellCommand("pidof ${E5dJourneyControlClient.PACKAGE}").isNotBlank()) {
            check(System.currentTimeMillis() < deadline) { "premise: PID $pid 이(가) $kills 번의 kill 에도 사라지지 않았다" }
            run.device.executeShellCommand("am kill ${E5dJourneyControlClient.PACKAGE}")
            kills++
            Thread.sleep(1_000)
        }
        Log.i(TAG, "PID $pid gone after $kills kill(s)")
        val recents = run.device.executeShellCommand("dumpsys activity recents")
        Log.i(TAG, "recents before return: ${recents.lines().filter { "com.jay.fxi" in it && "e5djourney" !in it }.take(6)}")
        assertTrue("premise: task $task 이(가) recents 에 com.jay.fxi/.MainActivity 와 남았다",
            recents.lines().any { Regex("""(#|taskId=|id=)$task\b""").containsMatchIn(it) && "com.jay.fxi" in it } ||
                recents.contains(Regex("""#$task\b[^\n]*\n[^\n]*com\.jay\.fxi/\.MainActivity""")))
        // Nothing may have started the app again before the return: the restored Activity must be the first thing in the new process.
        assertTrue("premise: 복귀 전에 앱 process 가 다시 떴다",
            run.device.executeShellCommand("pidof ${E5dJourneyControlClient.PACKAGE}").isBlank())
        val activityManager = run.instrumentation.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.moveTaskToFront(task.toInt(), 0)
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        run.run { renewed ->
            assertNotEquals("새 process", nonce, renewed)
            val start = run.client.ack("start", renewed)
            assertNotEquals("새 PID", pid, start.getInt("pid"))
            val lines = run.awaitRecord(renewed, "복원된 Activity") { lines ->
                texts(lines, "activity").any { Regex("""MainActivity#\d+ pre-created saved=true .* task=$task""").matches(it) }
            }
            assertTrue("새 provider", texts(lines, "lifecycle").any { it.startsWith("ack provider nonce=$renewed ") })
            afterLoss(renewed, target = target, initial = initial, token = token)
            // Every command for the old process is refused, a held release included.
            val stale = held
            if (stale != null) {
                run.client.reject("release", nonce, "nonce") { putExtra("set", stale.set); putExtra("operation", stale.operation); putExtra("token", token) }
            }
            run.client.reject("phase", nonce, "nonce") { putExtra("arg", "stale") }
        }
    }

    // Steps ----------------------------------------------------------------------------------------------------------------

    private class ActivityLine(val id: String, val task: String)

    // The screen and the preview as every journey drives them (moved unchanged to E5dJourneyPreview for J10–J12).
    private fun clear() = preview.clear()
    private fun choose(other: String, target: String) = preview.choose(other, target)
    private fun heading(tab: String) = preview.heading(tab)
    private fun open(): Long = preview.open()
    private fun sheetOrNull(): UiObject2? = preview.sheetOrNull()
    private fun sheetGone() = preview.sheetGone()
    private fun inSheet(selector: BySelector, what: String): UiObject2 = preview.inSheet(selector, what)
    private fun initialThreshold(): String = preview.initialThreshold()
    private fun edit() = preview.edit()
    private fun holdCta(nonce: String, token: Long): Held = preview.holdCta(nonce, token)
    private fun paywallShown() = preview.paywallShown()
    private fun closePaywall() = preview.closePaywall()

    private fun phase(nonce: String, name: String): Int = preview.phase(nonce, name)

    private fun activities(nonce: String): List<ActivityLine> = texts(run.client.dump(nonce).lines, "activity").mapNotNull { line ->
        Regex("""MainActivity#(\d+) pre-created .* task=(\d+)""").matchEntire(line)?.let { ActivityLine(it.groupValues[1], it.groupValues[2]) }
    }

    /** After [mark]: a new Activity instance with a saved state and the configuration turned, in the same process. */
    private fun awaitRecreated(nonce: String, mark: Int, before: List<ActivityLine>, landscape: Boolean): List<String> {
        val known = before.map { it.id }.toSet()
        val orientation = if (landscape) 2 else 1
        val lines = run.awaitRecord(nonce, "재생성", timeoutMillis = 60_000) { lines ->
            texts(after(lines, mark), "activity").any { line ->
                Regex("""MainActivity#(\d+) pre-created saved=true config=$orientation/.*""").matchEntire(line)?.groupValues?.get(1)?.let { it !in known } == true
            }
        }
        val now = run.discover()
        assertEquals("같은 process", nonce, now.getString("nonce"))
        val providers = texts(lines, "lifecycle").filter { it.startsWith("ack provider ") }
        assertEquals("provider 는 한 번", 1, providers.size)
        assertEquals("같은 PID", providers.single().substringAfter(" pid=").takeWhile { it.isDigit() }.toInt(), now.getInt("pid"))
        return lines
    }

    /** What every loss leaves: the chosen tab, no sheet, no paywall now or a little later, no delivery, and an initial reopen. */
    private fun afterLoss(nonce: String, target: String, initial: String, token: Long) {
        heading(target)
        assertTrue("시트가 없다", sheetOrNull() == null)
        assertFalse("paywall 이 없다", paywallShown())
        Thread.sleep(LATE_MILLIS)
        assertFalse("늦게도 paywall 이 없다", paywallShown())
        assertTrue("전달이 없다", texts(run.client.dump(nonce).lines, "hook").none { it.endsWith("event delivered $token") })
        open()
        assertEquals("재open: 당시 snapshot 의 임계값", initial, initialThreshold())
        inSheet(By.text("닫기"), "닫기").click()
        sheetGone()
        assertFalse("닫기로 paywall 이 없다", paywallShown())
    }

    private companion object {
        const val TAG = "E5dJourneyJ09"
        const val SCREEN_TITLE = E5dJourneyPreview.SCREEN_TITLE
        const val PAYWALL_TEXT = E5dJourneyPreview.PAYWALL_TEXT
        const val COLD_START_MILLIS = E5dJourneyPreview.COLD_START_MILLIS
        const val LATE_MILLIS = E5dJourneyPreview.LATE_MILLIS
    }
}
