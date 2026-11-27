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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S96PreviewHostDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class HideMode { NORMAL, CANCEL_SHOWN, FAIL, RETURN_SHOWN, HELD }

    private val asOf = Instant.parse("2026-11-23T01:30:00Z")
    private val quotes = mapOf(("upbit" to "usdt-krw") to 1432.5, ("bithumb" to "usdt-krw") to 1431.0)
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
    private var opener: FreeComparisonAlertPreviewOpener? = null

    private suspend fun hideBy(real: suspend () -> Unit) {
        hideAttempts += 1
        when (attemptModes.removeFirstOrNull() ?: hideMode) {
            HideMode.NORMAL -> real()
            HideMode.CANCEL_SHOWN -> throw CancellationException("s96: cancelled while shown")
            HideMode.FAIL -> throw IllegalStateException("s96: hide failed")
            HideMode.RETURN_SHOWN -> Unit
            HideMode.HELD -> { hideGate.await(); real() }
        }
    }

    private fun content(): @Composable () -> Unit = {
        Column {
            if (mounted.value) {
                androidx.compose.runtime.key(hostGeneration.intValue) {
                    FreeComparisonAlertPreviewHost(
                        uid = uid.value,
                        activeTab = tab.value,
                        onSubscribe = { paywalls += 1; eventsAtPaywall += events.toList() },
                        hide = ::hideBy,
                        onEvent = { events += it },
                        beforeDeliver = { origin -> heldOrigin = origin; deliverGate?.await() },
                        onContext = { contexts += it }
                    ) { open ->
                        opener = open
                        Button(onClick = { tab.value?.let { open.open(it, "absolute", quotes, asOf) } }, modifier = Modifier.testTag(OPEN)) { Text("open") }
                    }
                }
            }
        }
    }

    private fun show() { compose.setContent(content()); compose.waitForIdle() }
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun sheetOpen() = count("free-comparison-preview-sheet") == 1
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun openPreview() {
        tap(OPEN)
        if (!sheetOpen()) throw AssertionError("premise: 열렸다")
    }
    private fun lastToken(): String = events.last { it.startsWith("shown ") }.removePrefix("shown ")
    private fun awaitObservation(test: () -> Boolean) {
        var frames = 0
        while (!test() && frames++ < 120) compose.mainClock.advanceTimeByFrame()
        if (!test()) throw AssertionError("마지막 관측값: events=$events hideAttempts=$hideAttempts paywalls=$paywalls")
    }
    private fun value() = compose.onAllNodes(hasTestTag("comparison-value"), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.singleOrNull()?.text
    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError("$message\nevents=$events attempts=$hideAttempts paywalls=$paywalls")
    }

    @Test fun H01() {
        deliverGate = CompletableDeferred()
        show()
        openPreview()
        val token = lastToken()
        hideMode = HideMode.HELD
        tap(SubscribeCTATags.BUTTON)
        awaitObservation { hideAttempts == 1 }
        tap(SubscribeCTATags.BUTTON)
        check("hide 완료 전 구독 호출", sheetOpen() && paywalls == 0)
        update { hideGate.complete(Unit) }
        awaitObservation { heldOrigin != null }
        val heldClean = !sheetOpen() && paywalls == 0
        update { deliverGate!!.complete(Unit) }
        awaitObservation { paywalls >= 1 }
        compose.waitForIdle()
        check("제거 뒤 구독 한 번", heldClean && paywalls == 1 && hideAttempts == 1 &&
            events == listOf("shown $token", "removed $token", "delivered $token") && eventsAtPaywall.singleOrNull() == listOf("shown $token", "removed $token", "delivered $token"))
    }

    @Test fun H02() {
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
            if (sheetOpen() && count("comparison-value") != 1) throw AssertionError("완료되지 않은 hide 전달 0")
            if (sheetOpen()) {
                val step = compose.onNodeWithTag("comparison-step-＋",true).fetchSemanticsNode()
                check("닫힘 중 입력 비활성",androidx.compose.ui.semantics.SemanticsProperties.Disabled in step.config &&
                    step.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null)
            }
            outcomes += hideAttempts == 2 && paywalls == 0 && "removed $token" !in events && sheetOpen() &&
                value() == "2"
            check("visible hide의 완료 처리", outcomes.last())
            compose.onNodeWithTag("free-comparison-preview-sheet", useUnmergedTree = true).performTouchInput { swipeDown() }
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
        awaitObservation { paywalls == 1 }
        check("완료되지 않은 hide 전달 0", outcomes.all { it } && heldNothing && paywalls == 1)
    }

    @Test fun H03() {
        val outcomes = mutableListOf<Boolean>()
        show()
        for (phase in listOf("open","closing")) for (move in listOf("uid","tab","host")) {
            hideAttempts = 0
            hideMode = HideMode.HELD
            hideGate = CompletableDeferred()
            openPreview()
            val old = opener ?: throw AssertionError("premise: opener")
            if (phase == "closing") {
                tap(SubscribeCTATags.BUTTON)
                awaitObservation { hideAttempts == 1 }
            }
            when (move) {
                "uid" -> { update { uid.value = "u2" }; update { uid.value = "u1" } }
                "tab" -> { update { tab.value = FreeTab.USD }; update { tab.value = FreeTab.TETHER } }
                else -> { update { mounted.value = false }; update { mounted.value = true } }
            }
            update { hideGate.complete(Unit); old.open(FreeTab.TETHER,"absolute",quotes,asOf) }
            check("이전 열림과 hide 폐기",!sheetOpen() && paywalls == 0)
            openPreview()
            hideMode = HideMode.NORMAL
            tap("free-comparison-preview-close")
            awaitObservation { !sheetOpen() }
        }
        for (move in listOf("uid", "tab", "host", "reopen")) {
            if (move == "host") update { hostGeneration.intValue += 1 }
            paywalls = 0; events.clear(); deliverGate = CompletableDeferred(); heldOrigin = null
            if (count(OPEN) == 0) { update { mounted.value = true; uid.value = "u1"; tab.value = FreeTab.TETHER } }
            openPreview()
            val token = lastToken()
            tap(SubscribeCTATags.BUTTON)
            awaitObservation { "removed $token" in events }
            val arrived = runCatching { awaitObservation { heldOrigin != null } }.isSuccess
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
        update { kept.open(FreeTab.TETHER, "absolute", quotes, asOf) }
        val oldOpenedNothing = !sheetOpen()
        update { opener!!.open(FreeTab.TETHER, "absolute", quotes, asOf) }
        val currentOpened = sheetOpen()
        val reported = contexts.filterNotNull().map { it.binding }.distinct().size >= 2
        check("이전 결속의 전달과 열기 0", outcomes.all { it } && oldOpenedNothing && currentOpened && reported)
    }

    @Test fun H04() {
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
            tap("comparison-step-＋")
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count("comparison-value") == 1 &&
                value() == "3")
            if (phase != "open") tap(SubscribeCTATags.BUTTON)
            if (phase == "held") awaitObservation { events.any { it.startsWith("removed ") } }
            if (phase == "closing") check("복원된 초안과 전달 0", sheetOpen() && hideAttempts == 1 && paywalls == 0)
            if (phase == "held") check("복원된 초안과 전달 0", count("free-comparison-preview-sheet") == 0 &&
                heldOrigin?.token?.toString() == lastToken() && paywalls == 0)
            restoration.emulateSavedInstanceStateRestore()
            if (phase == "open") {
                val count = count("free-comparison-preview-sheet")
                if (count > 0) check("premise: 복원된 편집값",value() == "3")
                check("Open 복원 뒤 시트 수",count == 0)
            }
            compose.waitForIdle()
            deliverGate?.complete(Unit)
            hideGate.complete(Unit)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count("free-comparison-preview-sheet") == 0 && paywalls == 0)
            hideMode = HideMode.NORMAL; deliverGate = null
            openPreview()
            if (count("comparison-value") != 1) throw AssertionError("복원된 초안과 전달 0")
            outcomes += value() == "2"
            tap("free-comparison-preview-close")
            awaitObservation { !sheetOpen() }
        }
        check("복원된 초안과 전달 0", outcomes.all { it })
    }

    @Test fun H05() {
        val outcomes = mutableListOf<Boolean>()
        show()
        for (phase in listOf("open", "closing", "held")) {
            paywalls = 0; events.clear(); hideAttempts = 0; heldOrigin = null
            deliverGate = if (phase == "held") CompletableDeferred() else null
            hideMode = if (phase == "closing") HideMode.HELD else HideMode.NORMAL
            hideGate = CompletableDeferred()
            openPreview()
            tap("comparison-step-＋")
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count("comparison-value") == 1 &&
                value() == "3")
            if (phase != "open") tap(SubscribeCTATags.BUTTON)
            if (phase == "held") awaitObservation { events.any { it.startsWith("removed ") } }
            if (phase == "closing") check("복원된 초안과 전달 0", sheetOpen() && hideAttempts == 1 && paywalls == 0)
            if (phase == "held") check("복원된 초안과 전달 0", count("free-comparison-preview-sheet") == 0 &&
                heldOrigin?.token?.toString() == lastToken() && paywalls == 0)
            compose.activityRule.scenario.recreate()
            // The rule sets content once per test; the recreated activity gets the same tree directly, after its saved state.
            compose.activityRule.scenario.onActivity { it.setContent(null, content()) }
            compose.waitForIdle()
            deliverGate?.complete(Unit)
            hideGate.complete(Unit)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            check("복원된 초안과 전달 0", count("free-comparison-preview-sheet") == 0 && paywalls == 0)
            hideMode = HideMode.NORMAL; deliverGate = null
            openPreview()
            if (count("comparison-value") != 1) throw AssertionError("복원된 초안과 전달 0")
            outcomes += value() == "2"
            tap("free-comparison-preview-close")
            awaitObservation { !sheetOpen() }
        }
        check("복원된 초안과 전달 0", outcomes.all { it })
    }

    @Test fun H06() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent(content())
        openPreview()
        tap("comparison-pick-upbit")
        tap("comparison-step-＋")
        tap("comparison-step-＋")
        check("보통 닫기 전 편집", value() != "2")
        tap("free-comparison-preview-close")
        awaitObservation { !sheetOpen() }
        openPreview()
        check("재open seed", value() == "2" && paywalls == 0)
        tap("comparison-step-＋")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        check("복원 뒤 시트 수", !sheetOpen() && paywalls == 0)
        openPreview()
        check("새 편집", value() == "2" && paywalls == 0)
    }

    private companion object {
        const val OPEN = "s96-test-open"
    }
}
