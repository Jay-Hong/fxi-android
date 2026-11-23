package com.jay.fxi.ui.free

import android.view.WindowInsets
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
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §4 H01–H04): the real [FreeSourceAlertPreviewHost] and tether preview sheet over a
 * test opener, with its seams driven: the paywall comes after the sheet is gone and once; a hide that does not finish delivers
 * nothing; an owner or tab that moves, a new host or an old opener delivers and opens nothing; a save-and-restore or an
 * activity recreate at any phase brings nothing back.
 */
@RunWith(AndroidJUnit4::class)
class S87PreviewHostDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class HideMode { NORMAL, CANCEL_SHOWN, FAIL, RETURN_SHOWN, HELD }

    private val asOf = Instant.parse("2026-11-23T01:30:00Z")
    private val quotes = mapOf("upbit" to 1432.5, "bithumb" to 1431.0)
    private val uid = mutableStateOf<String?>("u1")
    private val tab = mutableStateOf<FreeTab?>(FreeTab.TETHER)
    private val mounted = mutableStateOf(true)
    private val hostGeneration = mutableIntStateOf(0)
    private var hideMode = HideMode.NORMAL
    private val attemptModes = ArrayDeque<HideMode>()
    private var hideAttempts = 0
    private var hideGate = CompletableDeferred<Unit>()
    private var deliverGate: CompletableDeferred<Unit>? = null
    private var heldOrigin: PreviewSignalOrigin? = null
    private var paywalls = 0
    private val events = mutableListOf<String>()
    private val eventsAtPaywall = mutableListOf<List<String>>()
    private val contexts = mutableListOf<PreviewHostContext?>()
    private var opener: FreeSourceAlertPreviewOpener? = null

    private suspend fun hideBy(real: suspend () -> Unit) {
        hideAttempts += 1
        when (attemptModes.removeFirstOrNull() ?: hideMode) {
            HideMode.NORMAL -> real()
            HideMode.CANCEL_SHOWN -> throw CancellationException("s87: cancelled while shown")
            HideMode.FAIL -> throw IllegalStateException("s87: hide failed")
            HideMode.RETURN_SHOWN -> Unit
            HideMode.HELD -> { hideGate.await(); real() }
        }
    }

    private fun content(): @Composable () -> Unit = {
        Column {
            if (mounted.value) {
                androidx.compose.runtime.key(hostGeneration.intValue) {
                    FreeSourceAlertPreviewHost(
                        uid = uid.value,
                        activeTab = tab.value,
                        onSubscribe = { paywalls += 1; eventsAtPaywall += events.toList() },
                        hide = ::hideBy,
                        onEvent = { events += it },
                        beforeDeliver = { origin -> heldOrigin = origin; deliverGate?.await() },
                        onContext = { contexts += it }
                    ) { open ->
                        opener = open
                        Button(onClick = { tab.value?.let { open.open(it, quotes, asOf) } }, modifier = Modifier.testTag(OPEN)) { Text("open") }
                    }
                }
            }
        }
    }

    private fun show() { compose.setContent(content()); compose.waitForIdle() }
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun sheetOpen() = count(FreeSourcePreviewTags.SHEET) == 1
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun openPreview() {
        tap(OPEN)
        if (!sheetOpen()) throw AssertionError("premise: 열렸다")
    }
    private fun lastToken(): String = events.last { it.startsWith("shown ") }.removePrefix("shown ")
    private fun imeShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return shown
    }

    /** As the bank tests do after typing: close the keyboard, so nothing pressed next can land on it. */
    private fun closeKeyboard() {
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeShown() }
        if (imeShown()) throw AssertionError("premise: 키보드가 닫혔다")
    }
    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    /**
     * A double tap on the CTA, the delivery held after removal: no paywall while held, then shown → removed → delivered, once.
     * The clock is held for the two taps, as the bank's D05 holds it, so both land on the sheet before it starts to hide.
     */
    @Test fun H01_removeBeforePaywall() {
        deliverGate = CompletableDeferred()
        show()
        openPreview()
        val token = lastToken()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeByFrame()
        val noneWhileHiding = paywalls == 0
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { "removed $token" in events }
        compose.waitForIdle()
        val heldClean = !sheetOpen() && paywalls == 0
        update { deliverGate!!.complete(Unit) }
        compose.waitUntil(5_000) { paywalls >= 1 }
        compose.waitForIdle()
        check("제거 뒤 구독 한 번", noneWhileHiding && heldClean && paywalls == 1 && hideAttempts == 1 &&
            events == listOf("shown $token", "removed $token", "delivered $token") && eventsAtPaywall.single() == listOf("shown $token", "removed $token", "delivered $token"))
    }

    /** Each way a hide fails, on the attempt and its one retry: the preview stays closing, its input unchanged, nothing delivered. */
    @Test fun H02_failedHideNeverFinishes() {
        val outcomes = mutableListOf<Boolean>()
        for (first in listOf(HideMode.CANCEL_SHOWN, HideMode.FAIL, HideMode.RETURN_SHOWN)) {
            paywalls = 0; hideAttempts = 0; events.clear(); hostGeneration.intValue += 1
            attemptModes.clear(); attemptModes.addLast(first); attemptModes.addLast(first)
            hideMode = HideMode.RETURN_SHOWN
            if (first == HideMode.CANCEL_SHOWN) show() else compose.waitForIdle()
            openPreview()
            val token = lastToken()
            tap(SubscribeCTATags.BUTTON)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            if (sheetOpen() && count(FreeSourcePreviewTags.THRESHOLD) != 1) throw AssertionError("완료되지 않은 hide 전달 0")
            outcomes += hideAttempts == 2 && paywalls == 0 && "removed $token" !in events && sheetOpen() &&
                compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode()
                    .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "1433"
            check("완료되지 않은 hide 전달 0", outcomes.last())
            compose.onNodeWithTag(FreeSourcePreviewTags.SHEET, useUnmergedTree = true).performTouchInput { swipeDown() }
            compose.waitForIdle()
            check("완료되지 않은 hide 전달 0", !sheetOpen() && paywalls == 1 && hideAttempts == 2 && "removed $token" in events)
        }
        paywalls = 0; hideAttempts = 0
        update { hostGeneration.intValue += 1 }
        attemptModes.clear(); attemptModes.addLast(HideMode.FAIL); attemptModes.addLast(HideMode.NORMAL)
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        check("완료되지 않은 hide 전달 0", hideAttempts == 2 && !sheetOpen() && paywalls == 1)
        paywalls = 0; hideAttempts = 0; hideMode = HideMode.HELD; hideGate = CompletableDeferred()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        check("완료되지 않은 hide 전달 0", hideAttempts == 1 && sheetOpen())
        update { hostGeneration.intValue += 1 }
        update { hideGate.complete(Unit) }
        check("완료되지 않은 hide 전달 0", hideAttempts == 1 && !sheetOpen() && paywalls == 0)
        // A held first attempt can also finish normally.
        paywalls = 0; hideAttempts = 0; events.clear(); hostGeneration.intValue += 1; attemptModes.clear()
        hideMode = HideMode.HELD; hideGate = CompletableDeferred()
        compose.waitForIdle()
        openPreview()
        tap(SubscribeCTATags.BUTTON)
        val heldNothing = paywalls == 0 && sheetOpen()
        update { hideGate.complete(Unit) }
        compose.waitUntil(5_000) { paywalls == 1 }
        check("완료되지 않은 hide 전달 0", outcomes.all { it } && heldNothing && paywalls == 1)
    }

    /** A delivery held after removal is dropped when the owner or tab moves, the host leaves, or a newer session opens; an old opener opens nothing. */
    @Test fun H03_invalidationDropsPendingAndOldOpener() {
        val outcomes = mutableListOf<Boolean>()
        show()
        for (move in listOf("uid", "tab", "host", "reopen")) {
            if (move == "host") update { hostGeneration.intValue += 1 }
            paywalls = 0; events.clear(); deliverGate = CompletableDeferred(); heldOrigin = null
            if (count(OPEN) == 0) { update { mounted.value = true; uid.value = "u1"; tab.value = FreeTab.TETHER } }
            openPreview()
            val token = lastToken()
            tap(SubscribeCTATags.BUTTON)
            compose.waitUntil(5_000) { "removed $token" in events }
            val arrived = runCatching { compose.waitUntil(5_000) { heldOrigin != null } }.isSuccess
            val host = contexts.lastOrNull()
            check("이전 결속의 전달과 열기 0", arrived && host != null && heldOrigin?.token?.toString() == token &&
                heldOrigin?.session === host.session && heldOrigin?.binding == host.binding)
            when (move) {
                "uid" -> { update { uid.value = "u2" }; update { uid.value = "u1" } }
                "tab" -> { update { tab.value = FreeTab.USD }; update { tab.value = FreeTab.TETHER } }
                "host" -> { update { mounted.value = false }; update { mounted.value = true }; openPreview()
                    check("이전 결속의 전달과 열기 0", lastToken() == token) }
                "reopen" -> { openPreview(); check("이전 결속의 전달과 열기 0", lastToken() != token) }
            }
            update { deliverGate!!.complete(Unit) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            outcomes += paywalls == 0
            if (move == "reopen" || move == "host") update { hostGeneration.intValue += 1 }
            update { uid.value = "u1"; tab.value = FreeTab.TETHER }
        }
        // An opener kept from before a binding change opens nothing; the current one opens.
        deliverGate = null
        val kept = opener!!
        update { tab.value = FreeTab.USD }
        update { tab.value = FreeTab.TETHER }
        update { kept.open(FreeTab.TETHER, quotes, asOf) }
        val oldOpenedNothing = !sheetOpen()
        update { opener!!.open(FreeTab.TETHER, quotes, asOf) }
        val currentOpened = sheetOpen()
        val reported = contexts.filterNotNull().map { it.binding }.distinct().size >= 2
        check("이전 결속의 전달과 열기 0", outcomes.all { it } && oldOpenedNothing && currentOpened && reported)
    }

    /** A save-and-restore, or an activity recreate, while open, while closing, or with the delivery held: nothing comes back. */
    @Test fun H04_restoreDropsEveryPhase() {
        val outcomes = mutableListOf<Boolean>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent(content())
        compose.waitForIdle()
        for (phase in listOf("open", "closing", "held")) {
            paywalls = 0; events.clear(); hideAttempts = 0; heldOrigin = null
            deliverGate = if (phase == "held") CompletableDeferred() else null
            hideMode = if (phase == "closing") HideMode.HELD else HideMode.NORMAL
            hideGate = CompletableDeferred()
            openPreview()
            compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1777")
            compose.waitForIdle()
            closeKeyboard()
            check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.THRESHOLD) == 1 &&
                compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode()
                    .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "1777")
            if (phase != "open") tap(SubscribeCTATags.BUTTON)
            if (phase == "held") compose.waitUntil(5_000) { events.any { it.startsWith("removed ") } }
            if (phase == "closing") check("복원된 초안과 전달 0", sheetOpen() && hideAttempts == 1 && paywalls == 0)
            if (phase == "held") check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.SHEET) == 0 &&
                heldOrigin?.token?.toString() == lastToken() && paywalls == 0)
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            deliverGate?.complete(Unit)
            hideGate.complete(Unit)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.SHEET) == 0 && paywalls == 0)
            hideMode = HideMode.NORMAL; deliverGate = null
            openPreview()
            if (count(FreeSourcePreviewTags.THRESHOLD) != 1) throw AssertionError("복원된 초안과 전달 0")
            outcomes += compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "1433"
            tap(FreeSourcePreviewTags.CLOSE)
            compose.waitUntil(5_000) { !sheetOpen() }
        }
        check("복원된 초안과 전달 0", outcomes.all { it })
    }

    /** An activity recreate, through the saved-instance bundle, while open, while closing, or with the delivery held: nothing comes back. */
    @Test fun H05_recreateDropsEveryPhase() {
        val outcomes = mutableListOf<Boolean>()
        show()
        for (phase in listOf("open", "closing", "held")) {
            paywalls = 0; events.clear(); hideAttempts = 0; heldOrigin = null
            deliverGate = if (phase == "held") CompletableDeferred() else null
            hideMode = if (phase == "closing") HideMode.HELD else HideMode.NORMAL
            hideGate = CompletableDeferred()
            openPreview()
            compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1777")
            compose.waitForIdle()
            closeKeyboard()
            check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.THRESHOLD) == 1 &&
                compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode()
                    .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "1777")
            if (phase != "open") tap(SubscribeCTATags.BUTTON)
            if (phase == "held") compose.waitUntil(5_000) { events.any { it.startsWith("removed ") } }
            if (phase == "closing") check("복원된 초안과 전달 0", sheetOpen() && hideAttempts == 1 && paywalls == 0)
            if (phase == "held") check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.SHEET) == 0 &&
                heldOrigin?.token?.toString() == lastToken() && paywalls == 0)
            compose.activityRule.scenario.recreate()
            // The rule sets content once per test; the recreated activity gets the same tree directly, after its saved state.
            compose.activityRule.scenario.onActivity { it.setContent(null, content()) }
            compose.waitForIdle()
            deliverGate?.complete(Unit)
            hideGate.complete(Unit)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count(FreeSourcePreviewTags.SHEET) == 0 && paywalls == 0)
            hideMode = HideMode.NORMAL; deliverGate = null
            openPreview()
            if (count(FreeSourcePreviewTags.THRESHOLD) != 1) throw AssertionError("복원된 초안과 전달 0")
            outcomes += compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "1433"
            tap(FreeSourcePreviewTags.CLOSE)
            compose.waitUntil(5_000) { !sheetOpen() }
        }
        check("복원된 초안과 전달 0", outcomes.all { it })
    }

    private companion object {
        const val OPEN = "s87-test-open"
    }
}
