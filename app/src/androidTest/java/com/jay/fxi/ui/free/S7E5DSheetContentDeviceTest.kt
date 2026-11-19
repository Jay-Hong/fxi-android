package com.jay.fxi.ui.free

import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.AlertAddSheet
import com.jay.fxi.ui.alert.AlertAddSheetTags
import com.jay.fxi.ui.alert.BankAlertEditor
import com.jay.fxi.ui.alert.BankAlertEditorMode
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d gap hunt r1 G18·G19 (`e5d_gaphunt.claude.r2.md`).
 *
 * - G18: the whole preview modal, read node by node from its unmerged semantics, offers exactly the close, the subscribe button
 *   and the editor's controls, and says nothing of saving (outside the CTA's own note), history, examples, duplicates, a
 *   remaining count, activation or KRX. The checker is shown to catch each of those on a made-up tree first.
 * - G19: for each of the three currencies, the premium editor and the preview show the same body for the same input, and that
 *   body matches the editor model: the threshold the model formats, the quote, the range and its 100엔 suffix.
 */
@RunWith(AndroidJUnit4::class)
class S7E5DSheetContentDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val asOf = Instant.parse("2026-09-06T02:30:00Z")
    private val quotesBy = mapOf(
        FreeTab.USD to mapOf("investing" to 1390.0, "kb" to 1400.0, "hana" to 1500.0),
        FreeTab.JPY to mapOf("kb" to 950.12, "hana" to 951.34),
        FreeTab.EUR to mapOf("kb" to 1600.0, "hana" to 1610.5)
    )
    private val shown = mutableStateOf<String?>(null)
    private val tab = mutableStateOf(FreeTab.USD)

    private fun content(): @Composable () -> Unit = {
        Column {
            FreeAlertPreviewHost(uid = "u1", activeTab = tab.value, onSubscribe = {}) { open ->
                Button(onClick = { open.open(tab.value, quotesBy.getValue(tab.value), asOf) }, modifier = Modifier.testTag(OPEN)) { Text("open") }
            }
            if (shown.value == "premium") {
                AlertAddSheet(
                    mode = BankAlertEditorMode.Add,
                    currency = tab.value.currency!!,
                    quotes = quotesBy.getValue(tab.value),
                    existing = emptyList(),
                    saving = false,
                    errorMessage = null,
                    remaining = null,
                    onSubmit = {},
                    onErrorDismiss = {},
                    onDismiss = { shown.value = null }
                )
            }
        }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }

    // ---- G18 -------------------------------------------------------------------------------------------------------------

    /** One node as the checker sees it. */
    private data class Seen(val tag: String?, val clickable: Boolean, val words: List<String>)

    private fun walk(node: SemanticsNode): List<Seen> {
        val c = node.config
        val words = (c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text))
        return listOf(Seen(c.getOrNull(SemanticsProperties.TestTag), c.getOrNull(SemanticsActions.OnClick) != null, words)) +
            node.children.flatMap(::walk)
    }

    private val bankTags = BankAlertEditor.BANK_CHOICES.map { AlertAddSheetTags.bank(it.code) }
    private val allowedClickable = setOf(
        FreeAlertPreviewTags.CLOSE, SubscribeCTATags.BUTTON, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT
    ) + bankTags
    private val forbiddenWords = listOf("이미 존재", "더 추가", "기록", "히스토리", "예시", "켜집니다", "켜져요", "활성화", "KRX", "미국달러F")

    /** Every way the modal could offer or promise more than a preview. Empty means it does not. */
    private fun violations(nodes: List<Seen>): List<String> {
        val v = mutableListOf<String>()
        for (n in nodes) {
            if (n.clickable && n.tag !in allowedClickable) v += "clickable ${n.tag ?: "(untagged)"} ${n.words}"
            for (w in n.words) {
                for (bad in forbiddenWords) if (bad in w) v += "word '$bad' in ${n.tag}: $w"
                if ("저장" in w && n.tag != SubscribeCTATags.NOTE) v += "save word in ${n.tag}: $w"
            }
        }
        return v
    }

    @Test fun S7E5D_C01_theCheckerCatchesEachWayToOfferMore() {
        val base = listOf(Seen(FreeAlertPreviewTags.CLOSE, true, listOf("닫기")), Seen(SubscribeCTATags.NOTE, false, listOf("알림 저장과 발송은 구독 후 사용할 수 있어요")))
        assertEquals("premise: 기준은 깨끗하다", emptyList<String>(), violations(base))
        for ((label, extra) in listOf(
            "an untagged save button" to Seen(null, true, listOf("저장")),
            "a save text" to Seen("x", false, listOf("저장하기")),
            "a tagged extra button" to Seen("free-alert-preview-history", true, listOf("열기")),
            "history" to Seen(null, false, listOf("알림 기록")),
            "an example" to Seen(null, false, listOf("예시: 1,400원")),
            "a duplicate notice" to Seen(null, false, listOf("동일한 알림이 이미 존재합니다")),
            "a remaining count" to Seen(null, false, listOf("알림 3개 더 추가 가능")),
            "an activation promise" to Seen(null, false, listOf("구독하면 이 알림이 켜집니다")),
            "KRX by description" to Seen(null, false, listOf("KRX 선물"))
        )) {
            assertTrue("checker missed $label", violations(base + extra).isNotEmpty())
        }
    }

    @Test fun S7E5D_C02_theWholeModalOffersOnlyThePreview() {
        compose.setContent(content())
        compose.waitForIdle()
        for (t in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) {
            update { tab.value = t }
            tap(OPEN)
            val sheet = compose.onNodeWithTag(FreeAlertPreviewTags.SHEET, useUnmergedTree = true).fetchSemanticsNode()
            val nodes = walk(sheet)
            assertEquals("$t", emptyList<String>(), violations(nodes))
            assertEquals("$t: 닫기 하나", 1, nodes.count { it.tag == FreeAlertPreviewTags.CLOSE })
            assertEquals("$t: CTA 하나", 1, nodes.count { it.tag == SubscribeCTATags.BUTTON })
            assertEquals("$t: 은행 9개, 각 하나", bankTags.map { 1 }, bankTags.map { tag -> nodes.count { it.tag == tag } })
            for (bank in BankAlertEditor.BANK_CHOICES) {
                val cell = walk(compose.onNodeWithTag(AlertAddSheetTags.bank(bank.code), useUnmergedTree = true).fetchSemanticsNode())
                assertTrue("$t: ${bank.code} 칸에 ${bank.displayName}", cell.any { n -> n.words.any { bank.displayName in it } })
            }
            tap(FreeAlertPreviewTags.CLOSE)
            compose.waitUntil(5_000) { count(FreeAlertPreviewTags.SHEET) == 0 }
        }
    }

    // ---- G19 -------------------------------------------------------------------------------------------------------------

    private val bodyTags = listOf(
        AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.CURRENT_RATE,
        AlertAddSheetTags.RANGE, AlertAddSheetTags.OUT_OF_RANGE, AlertAddSheetTags.REPEAT, AlertAddSheetTags.REPEAT_LABEL,
        AlertAddSheetTags.REPEAT_DESCRIPTION, AlertAddSheetTags.BANNER_REPEAT
    ) + bankTags

    /** The shared body as one map: per tag, how many, their words, and whether disabled or selected. */
    private fun body(): Map<String, List<Any?>> = bodyTags.associateWith { tag ->
        val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        listOf(nodes.size) + nodes.map { n ->
            listOf(walk(n).flatMap { it.words }, n.config.getOrNull(SemanticsProperties.Disabled) != null, n.config.getOrNull(SemanticsProperties.Selected))
        }
    }

    private fun imeShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return shown
    }

    /** A field in the sheet's scrolling body, brought into view; one hidden behind the keyboard would take the click elsewhere. */
    private fun tapField(tag: String, sheetTag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.performScrollTo()
        compose.waitForIdle()
        val shown = node.fetchSemanticsNode().boundsInWindow
        assertTrue("premise: $tag 가 보인다 $shown ime=${imeShown()}", shown.width > 0f && shown.height > 0f)
        assertEquals("premise: 시트가 열려 있다", 1, count(sheetTag))
        node.performClick()
        compose.waitForIdle()
    }

    /** The same input on either sheet: the keyboard closed after typing, every field brought into view before its click. */
    private fun act(outOfRange: String, sheetTag: String) {
        tapField(AlertAddSheetTags.bank("hana"), sheetTag)
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement(outOfRange)
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeShown() }
        tapField(AlertAddSheetTags.CONDITION, sheetTag)
        tapField(AlertAddSheetTags.REPEAT, sheetTag)
        compose.waitUntil(5_000) { count(AlertAddSheetTags.repeatOption(1)) == 1 }
        tap(AlertAddSheetTags.repeatOption(1))
    }

    @Test fun S7E5D_C03_thePremiumEditorAndThePreviewShowTheSameBody_inEachCurrency() {
        compose.setContent(content())
        compose.waitForIdle()
        for (t in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) {
            update { tab.value = t }
            val quotes = quotesBy.getValue(t)
            val currency: SupportedCurrency = t.currency!!
            val outOfRange = (quotes.getValue("hana") * 2).toInt().toString()

            update { shown.value = "premium" }
            compose.waitUntil(5_000) { count(AlertAddSheetTags.SHEET) == 1 }
            val premiumStart = body()
            act(outOfRange, AlertAddSheetTags.SHEET)
            val premiumAfter = body()
            update { shown.value = null }
            compose.waitUntil(5_000) { count(AlertAddSheetTags.SHEET) == 0 }

            tap(OPEN)
            compose.waitUntil(5_000) { count(FreeAlertPreviewTags.SHEET) == 1 }
            val previewStart = body()
            act(outOfRange, FreeAlertPreviewTags.SHEET)
            val previewAfter = body()
            tap(FreeAlertPreviewTags.CLOSE)
            compose.waitUntil(5_000) { count(FreeAlertPreviewTags.SHEET) == 0 }

            assertEquals("$t: 처음", premiumStart, previewStart)
            assertEquals("$t: 같은 입력 뒤", premiumAfter, previewAfter)
            val threshold = BankAlertEditor.formatThreshold(quotes.getValue("kb"))
            val thresholdWords = (previewStart.getValue(AlertAddSheetTags.THRESHOLD)[1] as List<*>)[0] as List<*>
            assertTrue("$t: 모델이 만든 초기 임계값 $threshold: $thresholdWords", threshold in thresholdWords)
            assertEquals("$t: 범위 밖 표시", 1, previewAfter.getValue(AlertAddSheetTags.OUT_OF_RANGE)[0])
            val range = previewStart.getValue(AlertAddSheetTags.RANGE)
            assertEquals("$t: 범위 한 줄", 1, range[0])
            val rangeWords = ((range[1] as List<*>)[0] as List<*>).joinToString(" ")
            assertEquals("$t: 100엔 단위 표기", currency.isJPY, "100엔" in rangeWords)
        }
    }

    private companion object {
        const val OPEN = "s7e5d-content-open"
    }
}
