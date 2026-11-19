package com.jay.fxi.ui.free

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.AlertAddSheetTags
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d gap hunt r1 G04–G08 (`e5d_gaphunt.claude.r2.md`): the real host and sheet with its three internal seams.
 *
 * - G04: only a hide that completes normally, with the sheet then hidden, finishes the preview. A cancelled, failed, or
 *   returned-but-still-shown hide finishes nothing; the user's next dismiss does, and only then can a wished paywall open.
 * - G05: the modal's removal is applied before the paywall callback, which happens once, after [beforeDeliver] is released.
 * - G06: callbacks kept from an earlier host, owner or recreated host open, edit, close or subscribe nothing.
 * - G07: while closing, the fields take no input and the repeat menu does not open.
 * - G08: a save-and-restore or an activity recreate at each phase — open, closing, closing for the paywall, removed and waiting
 *   to deliver — brings back no modal, no draft and no paywall.
 */
@RunWith(AndroidJUnit4::class)
class S7E5DHostSeamDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class HideMode { NORMAL, CANCEL_SHOWN, CANCEL_HIDDEN, FAIL, RETURN_SHOWN, HELD }

    private val asOf = Instant.parse("2026-09-06T02:30:00Z")
    private val quotes = mapOf("kb" to 1400.0, "hana" to 1500.0)
    private val uid = mutableStateOf<String?>("u1")
    private val tab = mutableStateOf<FreeTab?>(FreeTab.USD)
    private val mounted = mutableStateOf(true)
    private val hostGeneration = mutableIntStateOf(0)
    /** Read by the content, so changing it forces a recomposition without changing anything the preview sees. */
    private val nudge = mutableIntStateOf(0)
    private var hideMode = HideMode.NORMAL
    /** Modes for the next hide attempts, first one first; once empty, [hideMode] applies. */
    private val attemptModes = ArrayDeque<HideMode>()
    private var hideAttempts = 0
    private var hideGate = CompletableDeferred<Unit>()
    private var deliverGate: CompletableDeferred<Unit>? = null
    private var paywalls = 0
    private val events = mutableListOf<String>()
    private val eventsAtPaywall = mutableListOf<List<String>>()
    private var opener: FreeAlertPreviewOpener? = null
    /** What the host told its context seam, in order (J1b). */
    private val contexts = mutableListOf<PreviewHostContext?>()
    /** The origin each held delivery was told (J1b). */
    private val origins = mutableListOf<PreviewSignalOrigin>()

    private suspend fun hideBy(real: suspend () -> Unit) {
        hideAttempts += 1
        when (attemptModes.removeFirstOrNull() ?: hideMode) {
            HideMode.NORMAL -> real()
            HideMode.CANCEL_SHOWN -> throw CancellationException("s7e5d: cancelled while shown")
            HideMode.CANCEL_HIDDEN -> { real(); throw CancellationException("s7e5d: cancelled after hiding") }
            HideMode.FAIL -> throw IllegalStateException("s7e5d: hide failed")
            HideMode.RETURN_SHOWN -> Unit
            HideMode.HELD -> { hideGate.await(); real() }
        }
    }

    private fun content(): @Composable () -> Unit = {
        Column {
            if (mounted.value) {
                // A new generation is a new host: the key forgets the old one, as leaving and re-entering the surface does.
                androidx.compose.runtime.key(hostGeneration.intValue) {
                    FreeAlertPreviewHost(
                        uid = uid.value,
                        activeTab = tab.value,
                        onSubscribe = { paywalls += 1; eventsAtPaywall += events.toList() },
                        hide = ::hideBy,
                        onEvent = { events += it },
                        beforeDeliver = { origins += it; deliverGate?.await() },
                        onContext = { contexts += it }
                    ) { open ->
                        opener = open
                        Button(onClick = { tab.value?.let { open.open(it, quotes, asOf) } }, modifier = Modifier.testTag(OPEN)) {
                            Text("open ${nudge.intValue}")
                        }
                    }
                }
            }
        }
    }

    private fun show() { compose.setContent(content()); compose.waitForIdle() }
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun sheetOpen() = count(FreeAlertPreviewTags.SHEET) == 1
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    /** The node's own words; when it has none (a button whose words are its child's), the same tag in the merged tree. */
    private fun textOf(tag: String): String {
        fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
        return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().own()
            .ifEmpty { compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().own() }
    }
    private fun disabled(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null
    private fun openPreview() { tap(OPEN); assertTrue("premise: 열렸다", sheetOpen()) }
    private fun lastToken(): String = events.last { it.startsWith("shown ") }.removePrefix("shown ")
    private fun dragDown() {
        compose.onNodeWithTag(FreeAlertPreviewTags.SHEET, useUnmergedTree = true).performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    // ---- G04 -------------------------------------------------------------------------------------------------------------

    @Test fun S7E5D_H01_aNormalHideThatEndsHiddenOpensThePaywallOnce() {
        show()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals(1, paywalls)
    }

    /**
     * The first attempt × its one retry. Only a normal return with the sheet then hidden finishes, on the first attempt or the
     * retry; a retry that returns normally finishes too when the first already left the sheet hidden. Anything else leaves the
     * preview closing with no paywall and no removal — the budget is spent, and nothing is reported as recovered.
     */
    @Test fun S7E5D_H02_theFirstAttemptAndItsOneRetry() {
        val firsts = listOf(HideMode.CANCEL_SHOWN, HideMode.CANCEL_HIDDEN, HideMode.FAIL, HideMode.RETURN_SHOWN)
        val retries = listOf(HideMode.NORMAL, HideMode.CANCEL_SHOWN, HideMode.CANCEL_HIDDEN, HideMode.FAIL, HideMode.RETURN_SHOWN)
        for (first in firsts) for (retry in retries) {
            val label = "$first→$retry"
            paywalls = 0; hideAttempts = 0; events.clear(); hostGeneration.intValue += 1
            attemptModes.clear(); attemptModes.addLast(first); attemptModes.addLast(retry)
            hideMode = HideMode.RETURN_SHOWN // a third attempt, if one were made, would be counted and change nothing
            if (first == firsts.first() && retry == retries.first()) show() else compose.waitForIdle()
            openPreview()
            val token = lastToken()
            tap(SubscribeCTATags.BUTTON)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            val hiddenAfterRetry = retry == HideMode.NORMAL || (retry == HideMode.RETURN_SHOWN && first == HideMode.CANCEL_HIDDEN)
            assertEquals("$label: 요청 한 번 + 재시도 한 번", 2, hideAttempts)
            if (hiddenAfterRetry) {
                compose.waitUntil(5_000) { paywalls == 1 }
                assertTrue("$label: 제거 뒤 전달", "removed $token" in events)
            } else {
                assertEquals("$label: 예산 소진 뒤 paywall", 0, paywalls)
                assertTrue("$label: 제거되지 않았다", "removed $token" !in events)
                // Reset for the next pair: a new host forgets this closing preview.
                update { hostGeneration.intValue += 1 }
                compose.waitUntil(5_000) { "removed $token" in events }
                assertEquals("$label: 이탈은 신호가 없다", 0, paywalls)
            }
        }
    }

    /** A normal first attempt needs no retry. */
    @Test fun S7E5D_H02b_aNormalFirstAttemptFinishesAlone() {
        show()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { paywalls == 1 }
        assertEquals(1, hideAttempts)
    }

    /** Cancelled after hiding, and the retry held: nothing until the retry returns normally; then one paywall. */
    @Test fun S7E5D_H03b_aCancelledButHiddenFirstHideWaitsForTheRetry() {
        attemptModes.addLast(HideMode.CANCEL_HIDDEN); attemptModes.addLast(HideMode.HELD)
        show()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { hideAttempts == 2 }
        compose.waitForIdle()
        assertEquals("취소 직후·재시도 대기 중 paywall", 0, paywalls)
        assertEquals("premise: 아직 완료로 세지 않았다", 0, events.count { it.startsWith("removed ") })
        compose.runOnIdle { hideGate.complete(Unit) }
        compose.waitUntil(5_000) { paywalls == 1 }
        assertTrue(events.any { it.startsWith("removed ") })
    }

    /**
     * The budget spent with the sheet already hidden, then a Back. Whether the platform reports a dismiss here is not assumed;
     * what holds either way: no paywall before the modal is removed, and never more than one.
     */
    @Test fun S7E5D_H03_afterTheBudgetASecondDismissIsHandledAtMostOnce() {
        attemptModes.addLast(HideMode.CANCEL_HIDDEN); attemptModes.addLast(HideMode.CANCEL_HIDDEN)
        hideMode = HideMode.RETURN_SHOWN
        show()
        openPreview()
        val token = lastToken()
        tap(SubscribeCTATags.BUTTON)
        compose.waitForIdle()
        assertEquals("예산 소진 뒤", 0, paywalls)
        Espresso.pressBack()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        if ("removed $token" in events) {
            compose.waitUntil(5_000) { paywalls == 1 }
            val seen = eventsAtPaywall.single()
            assertTrue("제거 뒤 전달: $seen", seen.indexOf("removed $token") in 0 until seen.indexOf("delivered $token"))
        } else {
            assertEquals("보고된 dismiss 가 없으면 신호도 없다", 0, paywalls)
        }
        assertTrue("많아야 한 번", paywalls <= 1)
    }

    /**
     * A close whose hide and retry did not hide, then the user's drag. Whether that drag ends it is the platform's business and
     * not assumed; a close never opens the paywall either way.
     */
    @Test fun S7E5D_H04_aPlainCloseNeverOpensThePaywall_evenAfterTheBudget() {
        hideMode = HideMode.CANCEL_SHOWN
        show()
        openPreview()
        tap(FreeAlertPreviewTags.CLOSE)
        assertTrue("예산 소진 뒤 아직 보인다", sheetOpen())
        assertEquals(2, hideAttempts)
        hideMode = HideMode.NORMAL
        dragDown()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(0, paywalls)
    }

    // ---- G05 -------------------------------------------------------------------------------------------------------------

    @Test fun S7E5D_H05_theRemovalIsAppliedBeforeThePaywall_andItHappensOnce() {
        show()
        openPreview()
        val token = lastToken()
        deliverGate = CompletableDeferred()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { "removed $token" in events }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals("전달 대기 중 모달", 0, count(FreeAlertPreviewTags.SHEET))
        assertEquals("전달 대기 중 paywall", 0, paywalls)
        compose.runOnIdle { deliverGate!!.complete(Unit) }
        compose.waitUntil(5_000) { paywalls == 1 }
        val seen = eventsAtPaywall.single()
        assertTrue("제거 적용이 전달보다 앞선다: $seen", seen.indexOf("removed $token") in 0 until seen.indexOf("delivered $token"))
        assertTrue("callback 순간 모달이 다시 보이지 않았다: $seen", seen.lastIndexOf("shown $token") < seen.indexOf("removed $token"))
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        update { nudge.intValue += 1 }
        assertEquals("강제 recomposition·추가 frame 뒤에도", 1, paywalls)
        deliverGate = null
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { paywalls == 2 }
        assertEquals("새 preview 는 새 1", 2, paywalls)
    }

    // ---- G06 -------------------------------------------------------------------------------------------------------------

    private data class Kept(val opener: FreeAlertPreviewOpener, val edit: (String) -> Boolean, val close: () -> Boolean, val subscribe: () -> Boolean)

    /** The live sheet's own handlers, taken through its semantics, so they can be called after their host is gone. */
    private fun keep(): Kept {
        val field = compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode().config
        val close = compose.onNodeWithTag(FreeAlertPreviewTags.CLOSE, useUnmergedTree = true).fetchSemanticsNode().config
        val cta = compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).fetchSemanticsNode().config
        val setText = field[SemanticsActions.SetText].action!!
        val closeClick = close[SemanticsActions.OnClick].action!!
        val ctaClick = cta[SemanticsActions.OnClick].action!!
        return Kept(opener!!, { setText(androidx.compose.ui.text.AnnotatedString(it)) }, closeClick, ctaClick)
    }

    private fun replay(kept: Kept) {
        compose.runOnIdle {
            kept.opener.open(FreeTab.USD, quotes, asOf)
            kept.edit("1111")
            kept.close()
            kept.subscribe()
        }
        compose.waitForIdle()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    /** Between cases only: one content for the whole test, and a fresh host for each case. Each case's own exit is its check. */
    private fun nextCase() {
        update { paywalls = 0; mounted.value = true; uid.value = "u1"; tab.value = FreeTab.USD; hostGeneration.intValue += 1 }
        events.clear()
    }

    @Test fun S7E5D_H06_handlersKeptFromAnEarlierHostOrOwnerDoNothing() {
        show()
        for (exit in listOf("unmount", "owner A→B→A", "same owner, new host")) {
            nextCase()
            openPreview()
            val kept = keep()
            when (exit) {
                "unmount" -> { update { mounted.value = false }; update { mounted.value = true } }
                "owner A→B→A" -> { update { uid.value = "u2" }; update { uid.value = "u1" } }
                "same owner, new host" -> update { hostGeneration.intValue += 1 }
            }
            compose.waitUntil(5_000) { !sheetOpen() }
            val shownBefore = events.count { it.startsWith("shown ") }
            replay(kept)
            assertEquals("$exit: 새 모달", shownBefore, events.count { it.startsWith("shown ") })
            assertTrue("$exit: 모달", !sheetOpen())
            assertEquals("$exit: paywall", 0, paywalls)
            openPreview()
            assertEquals("$exit: 새 host 의 draft 는 처음부터", "1400.00", textOf(AlertAddSheetTags.THRESHOLD))
            tap(FreeAlertPreviewTags.CLOSE)
            compose.waitUntil(5_000) { !sheetOpen() }
        }
    }

    /** Removed and waiting to deliver, then the host goes or the owner or tab moves and comes back: nothing is delivered. */
    @Test fun S7E5D_H07_aMoveBetweenRemovalAndDeliveryDeliversNothing() {
        show()
        for (move in listOf("unmount", "owner A→B→A", "tab USD→JPY→USD", "new host")) {
            nextCase()
            openPreview()
            val token = lastToken()
            deliverGate = CompletableDeferred()
            tap(SubscribeCTATags.BUTTON)
            compose.waitUntil(5_000) { "removed $token" in events }
            when (move) {
                "unmount" -> { update { mounted.value = false }; update { mounted.value = true } }
                "owner A→B→A" -> { update { uid.value = "u2" }; update { uid.value = "u1" } }
                "tab USD→JPY→USD" -> { update { tab.value = FreeTab.JPY }; update { tab.value = FreeTab.USD } }
                "new host" -> update { hostGeneration.intValue += 1 }
            }
            compose.runOnIdle { deliverGate!!.complete(Unit) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            assertEquals("$move: paywall", 0, paywalls)
            assertEquals("$move: 모달", 0, count(FreeAlertPreviewTags.SHEET))
            deliverGate = null
        }
    }

    // ---- G07 -------------------------------------------------------------------------------------------------------------

    @Test fun S7E5D_H08_whileClosingTheFieldsTakeNoInput_andTheMenuStaysShut() {
        hideMode = HideMode.HELD
        show()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        assertTrue("premise: 닫는 중에도 아직 보인다", sheetOpen())
        for (tag in listOf(AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT, AlertAddSheetTags.bank("hana"))) {
            assertTrue("$tag 가 닫는 중에 켜져 있다", disabled(tag))
        }
        val before = listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.REPEAT_LABEL))
        tap(AlertAddSheetTags.bank("hana"))
        tap(AlertAddSheetTags.CONDITION)
        tap(AlertAddSheetTags.REPEAT)
        assertEquals("반복 메뉴가 열렸다", 0, count(AlertAddSheetTags.repeatOption(0)))
        assertEquals(before, listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.REPEAT_LABEL)))
        compose.runOnIdle { hideGate.complete(Unit) }
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals(1, paywalls)
    }

    /** The positive control: while open, the same controls take input and the menu opens. */
    @Test fun S7E5D_H09_whileOpenTheSameControlsWork() {
        show()
        openPreview()
        tap(AlertAddSheetTags.bank("hana"))
        assertEquals("1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.REPEAT)
        assertTrue("반복 메뉴가 열린다", count(AlertAddSheetTags.repeatOption(0)) == 1)
    }

    // ---- G08 -------------------------------------------------------------------------------------------------------------

    private enum class Phase { OPEN, CLOSING, CLOSING_FOR_PAYWALL, REMOVED_WAITING }

    private fun reach(phase: Phase) {
        openPreview()
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1390")
        when (phase) {
            Phase.OPEN -> Unit
            Phase.CLOSING -> { hideMode = HideMode.HELD; tap(FreeAlertPreviewTags.CLOSE); assertTrue(sheetOpen()) }
            Phase.CLOSING_FOR_PAYWALL -> { hideMode = HideMode.HELD; tap(SubscribeCTATags.BUTTON); assertTrue(sheetOpen()) }
            Phase.REMOVED_WAITING -> {
                val token = lastToken()
                deliverGate = CompletableDeferred()
                tap(SubscribeCTATags.BUTTON)
                compose.waitUntil(5_000) { "removed $token" in events }
            }
        }
    }

    private fun releaseAndSettle() {
        compose.runOnIdle { hideGate.complete(Unit); deliverGate?.complete(Unit) }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun assertNothingCameBack(label: String) {
        assertTrue("$label: 모달", !sheetOpen())
        assertEquals("$label: paywall", 0, paywalls)
        hideMode = HideMode.NORMAL; deliverGate = null
        openPreview()
        assertEquals("$label: 재open 은 처음부터", listOf("국민은행", "1400.00", "한번만"),
            listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.REPEAT_LABEL)))
    }

    /** The rule sets content once per test, so each phase is its own test. */
    private fun restoreAt(phase: Phase) {
        val restoration = StateRestorationTester(compose)
        restoration.setContent(content())
        compose.waitForIdle()
        reach(phase)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        releaseAndSettle()
        assertNothingCameBack("restore/$phase")
    }

    @Test fun S7E5D_H10a_aSaveAndRestoreWhileOpenBringsNothingBack() = restoreAt(Phase.OPEN)
    @Test fun S7E5D_H10b_aSaveAndRestoreWhileClosingBringsNothingBack() = restoreAt(Phase.CLOSING)
    @Test fun S7E5D_H10c_aSaveAndRestoreWhileClosingForThePaywallBringsNothingBack() = restoreAt(Phase.CLOSING_FOR_PAYWALL)
    @Test fun S7E5D_H10d_aSaveAndRestoreBetweenRemovalAndDeliveryBringsNothingBack() = restoreAt(Phase.REMOVED_WAITING)

    @Test fun S7E5D_H11_anActivityRecreateAtEachPhaseBringsNothingBack() {
        show()
        for (phase in Phase.entries) {
            paywalls = 0; events.clear(); hideMode = HideMode.NORMAL; hideGate = CompletableDeferred(); deliverGate = null
            reach(phase)
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.setContent(null, content()) }
            compose.waitForIdle()
            releaseAndSettle()
            assertNothingCameBack("recreate/$phase")
            tap(FreeAlertPreviewTags.CLOSE)
            compose.waitUntil(5_000) { !sheetOpen() }
        }
    }

    // ---- J1b: the host's context and each signal's origin, read only ------------------------------------------------------

    /**
     * The context seam hears this host, its session and the binding that session applied: a tab change with nothing open moves the
     * binding under the same host and session, a new owner is a new session, and leaving withdraws it. A held delivery is told the
     * session, binding and token its signal was issued under; a binding that moved while it was held delivers nothing.
     */
    @Test fun S7E5D_H17_theContextSeamHearsTheRealBindingAndEachSignalItsOrigin() {
        show()
        fun last(): PreviewHostContext {
            val context = contexts.lastOrNull()
            assertNotNull("premise: 문맥이 보고됐다", context)
            return context!!
        }
        val first = last()
        update { tab.value = FreeTab.JPY }
        assertSame("같은 host", first.host, last().host)
        assertSame("같은 session", first.session, last().session)
        assertEquals("아무것도 열리지 않은 채 바뀐 binding", first.binding + 1, last().binding)
        update { tab.value = FreeTab.USD }
        val atOpen = last()
        assertEquals("돌아온 것도 새 binding", first.binding + 2, atOpen.binding)
        deliverGate = CompletableDeferred()
        openPreview()
        val token = lastToken().toLong()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { origins.size == 1 }
        val origin = origins.single()
        assertSame("signal 의 session", atOpen.session, origin.session)
        assertEquals("signal 의 binding", atOpen.binding, origin.binding)
        assertEquals("signal 의 token", token, origin.token)
        update { tab.value = FreeTab.JPY }
        assertEquals("보류 중에 binding 이 움직였다", origin.binding + 1, last().binding)
        update { deliverGate?.complete(Unit) }
        compose.waitForIdle()
        assertEquals("움직인 binding 의 signal 은 아무것도 열지 않는다", 0, paywalls)
        update { uid.value = "u2" }
        assertSame("owner 가 바뀌어도 같은 host", first.host, last().host)
        assertNotSame("owner 가 바뀌면 새 session", first.session, last().session)
        update { mounted.value = false }
        assertNull("host 가 떠나면 문맥을 거둔다", contexts.last())
    }

    private companion object {
        const val OPEN = "s7e5d-seam-open"
    }
}
