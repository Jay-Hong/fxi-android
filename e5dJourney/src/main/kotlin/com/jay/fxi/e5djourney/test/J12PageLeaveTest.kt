package com.jay.fxi.e5djourney.test

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.NEWS
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.OPEN
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.TETHER
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.TOGGLE
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.disposes
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.liveMount
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Companion.mounts
import com.jay.fxi.e5djourney.test.E5dJourneyPreview.Mount
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.after
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2b2 P6 (`e5dj_j2_plan.claude.r3.md` §3 P6 and §C3; `s7e5dj_j2b2_design.codex.r1.md` D7 with r2): the free bank
 * preview lives with its page.
 * - Direct entry (J12_0 테더, J12_1 뉴스): the tab is chosen through the real tab row and kept by the app's own store, and a new
 *   process starts on it. The first screen is that tab's page — by the page itself and by the new process's record (테더:
 *   free snapshot requests for tether only; 뉴스: the news request and no free snapshot at all) — and the whole page, top to end,
 *   has no bank preview section.
 * - Round trip (J12_2 테더, J12_3 뉴스): on 달러 the section is expanded; the journey goes to the other tab and back through the
 *   tab row. Only after the record shows the old USD page's mount disposed (the same mount, by its id) and a new USD mount does
 *   the journey judge the section collapsed again. No dispose is a premise failure (PENDING), never inferred from the screen.
 *   The pager's settings are not assumed; for 뉴스 the pages it passes on the way are not taken as chosen.
 * - Open modal, limited check (J12_4 테더, J12_5 뉴스): with the preview sheet open, the IME and the sheet's surface are recorded;
 *   the target tab's place is measured just before the sheet opens, since with the modal open the window below is not in the
 *   accessibility tree, and the modal does not move it. A target under the sheet's surface or the IME is not tapped and only that row is skipped
 *   (assumption, with the geometry). A target outside both is tapped, and the result reported is that tap's: the sheet closed,
 *   the tab unchanged, no paywall. The original obligation — a non-bank tab reached with the modal open — stays PENDING either
 *   way; this row neither passes nor closes it.
 */
@RunWith(AndroidJUnit4::class)
class J12PageLeaveTest {
    private val run = E5dJourneyRun()
    private val preview = E5dJourneyPreview(run)

    @Before
    fun ready() = preview.ready()

    @After
    fun close() {
        runCatching { preview.clear() }
        run.close()
    }

    // Direct entry ---------------------------------------------------------------------------------------------------------

    @Test
    fun J12_0_tetherKeptAcrossAStartShowsNoBankPreview() = directEntry(TETHER)

    @Test
    fun J12_1_newsKeptAcrossAStartShowsNoBankPreview() = directEntry(NEWS)

    private fun directEntry(tab: String) {
        preview.restartOn(other = "유로", tab = tab)
        run.run { nonce ->
            val lines = run.awaitRecord(nonce, "첫 화면의 요청") { lines ->
                val http = texts(lines, "http")
                if (tab == TETHER) http.any { "-> free-snapshot tether " in it } else http.any { "-> news empty" in it }
            }
            val snapshots = texts(lines, "http").filter { "-> free-snapshot " in it }
            if (tab == TETHER) {
                assertTrue("새 process 의 free snapshot 은 tether 뿐 $snapshots", snapshots.isNotEmpty() && snapshots.all { "-> free-snapshot tether " in it })
            } else {
                assertTrue("뉴스로 시작한 process 에는 free snapshot 이 없다 $snapshots", snapshots.isEmpty())
            }
            absentOnWholePage(TOGGLE)
        }
    }

    /** The page scrolled to its top and then to its end, [tag] absent at every step. */
    private fun absentOnWholePage(tag: String) {
        var steps = 0
        while (steps++ < E5dJourneyRun.MAX_SCROLLS && run.scrollPage(Direction.UP)) Unit
        steps = 0
        while (true) {
            assertTrue("'$tag' 이(가) 이 page 에 있다. 화면: ${run.describeScreen()}", run.absent(tag))
            if (steps++ >= E5dJourneyRun.MAX_SCROLLS || !run.scrollPage(Direction.DOWN)) return
        }
    }

    // Round trip -----------------------------------------------------------------------------------------------------------

    @Test
    fun J12_2_aRoundTripThroughTetherDisposesTheBankPage() = roundTrip(TETHER)

    @Test
    fun J12_3_aRoundTripThroughNewsDisposesTheBankPage() = roundTrip(NEWS)

    private fun roundTrip(through: String) = run.run { nonce ->
        preview.choose("유로", "달러")
        expand()
        val before = usdMount(nonce) ?: error("premise: 달러 page 의 mount 가 기록에 없다")
        val mark = preview.phase(nonce, "leave-${through}")
        preview.tapTab(through)
        preview.pageShown(through)
        val disposed = runCatching {
            run.awaitRecord(nonce, "달러 page 의 dispose") { lines -> disposes(after(lines, mark)).contains(before) }
        }
        check(disposed.isSuccess) { "premise(PENDING): 달러 page mount $before 의 dispose 가 오지 않았다" }
        val back = preview.phase(nonce, "back-from-${through}")
        preview.tapTab("달러")
        preview.heading("달러")
        val lines = run.awaitRecord(nonce, "새 달러 mount") { lines -> mounts(after(lines, back)).any { it.tab == "USD" } }
        val renewed = mounts(after(lines, back)).last { it.tab == "USD" }
        assertNotEquals("새 mount", before, renewed)
        // Only now: the section of the new page starts collapsed.
        run.reveal(TOGGLE)
        assertTrue("다시 접혔다", runCatching { run.reveal(By.res(OPEN), OPEN, maxScrolls = 2, bothWays = false) }.isFailure)
        assertTrue("시트가 없다", preview.sheetOrNull() == null)
        assertFalse("paywall 이 없다", preview.paywallShown())
    }

    /** Expands the bank preview section on the page shown: its open button must then be just below the toggle. */
    private fun expand() {
        run.reveal(TOGGLE)
        if (runCatching { run.reveal(By.res(OPEN), OPEN, maxScrolls = 2, bothWays = false) }.isFailure) {
            run.reveal(TOGGLE).click()
            run.device.waitForIdle()
        }
        run.reveal(By.res(OPEN), OPEN, maxScrolls = 2, bothWays = false)
    }

    /** The USD page mount still alive: the last USD mount the record has no dispose for. */
    private fun usdMount(nonce: String): Mount? = liveMount(run.client.dump(nonce).lines, "USD")

    // Open modal, limited check --------------------------------------------------------------------------------------------

    @Test
    fun J12_4_aTapOnTetherWithTheSheetOpen_limitedCheck() = modalTap(TETHER)

    @Test
    fun J12_5_aTapOnNewsWithTheSheetOpen_limitedCheck() = modalTap(NEWS)

    private fun modalTap(target: String) = run.run { nonce ->
        preview.choose("유로", "달러")
        // The target's place is measured before the modal opens: with it open, the window below is not in the tree
        // (`e5dj_j2b2_dev2`). The modal does not move that window; the screen's size is checked to be the same.
        val node = run.onScreen(By.text(target)).minByOrNull { it.visibleBounds.top } ?: error("관측 실패: 탭 '$target' 이(가) 없다")
        val at = node.visibleBounds
        val screen = run.device.displayWidth to run.device.displayHeight
        preview.open()
        assertTrue("모달 동안 화면 크기가 같다", (run.device.displayWidth to run.device.displayHeight) == screen)
        val sheet = preview.sheet()
        val window = sheet.visibleBounds
        val title = run.onScreen(By.text(E5dJourneyPreview.SHEET_TITLE)).singleOrNull() ?: error("관측 실패: 시트 제목이 하나가 아니다")
        val surface = surfaceOf(title, window)
        check(surface != null && !surface.isEmpty) { "관측 실패: 시트 표면의 경계를 읽지 못했다 (창 $window)" }
        val ime = run.instrumentation.uiAutomation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { Rect().also(it::getBoundsInScreen) }
        val x = at.centerX()
        val y = at.centerY()
        val covered = surface.contains(x, y) || ime.any { it.contains(x, y) }
        val geometry = "target $target $at centre ($x,$y), sheet window $window, sheet surface $surface, ime $ime"
        Log.i(TAG, geometry)
        assumeTrue("가려진 목표: 누르지 않는다 — $geometry", !covered)
        val mark = preview.phase(nonce, "modal-tap-$target")
        run.device.click(x, y)
        preview.sheetGone(timeoutMillis = 10_000)
        preview.heading("달러")
        Thread.sleep(E5dJourneyPreview.LATE_MILLIS)
        assertFalse("paywall 이 없다", preview.paywallShown())
        val later = after(run.client.dump(nonce).lines, mark)
        assertTrue("탭이 바뀌지 않았다 ${mounts(later)}", mounts(later).none { it.tab != "USD" })
    }

    /**
     * The sheet's surface: the outermost ancestor of its title that neither covers (nearly) the whole window nor starts at the
     * window's top. Above the surface are only containers that fill the window — its root, and a box down to the navigation bar
     * (`e5dj_j2b2_dev4`: 0–2007 of 2220) — while the sheet itself starts below the top. A union of the window's nodes took one
     * of those in and came out as the whole screen (`e5dj_j2b2_dev3`). Measured too small, a covered target would be tapped,
     * the sheet would not close, and the row would fail — not pass. Null when the title cannot be read.
     */
    private fun surfaceOf(title: UiObject2, window: Rect): Rect? = try {
        var node = title
        var surface = node.visibleBounds
        while (true) {
            val parent = node.parent ?: break
            val bounds = parent.visibleBounds
            if (bounds.top <= window.top || (bounds.width() >= window.width() * NEAR_WHOLE && bounds.height() >= window.height() * NEAR_WHOLE)) break
            surface = bounds
            node = parent
        }
        surface
    } catch (stale: StaleObjectException) {
        null
    }

    private companion object {
        const val TAG = "E5dJourneyJ12"
        const val NEAR_WHOLE = 0.98
    }
}
