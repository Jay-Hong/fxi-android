package com.jay.fxi.ui.alert

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.SupportedCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b4b (G07): the real [AlertAddSheet] alone. The Add seed is an initial value only: once the sheet is open, a different
 * seed or a new quote does not rebuild what was entered — the bank it opened on, the threshold, the condition, the repeat —
 * and only the shown current quote follows the prices.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B4BSheetSeedTest {

    @get:Rule val rule = createComposeRule()

    private val seed = mutableStateOf<String?>("hana")
    private val quotes = mutableStateOf(mapOf("kb" to 1400.0, "hana" to 1500.0, "woori" to 1450.0))

    private fun show() {
        rule.setContent {
            AlertAddSheet(
                mode = BankAlertEditorMode.Add,
                currency = SupportedCurrency.USD_KRW,
                quotes = quotes.value,
                existing = emptyList(),
                saving = false,
                errorMessage = null,
                remaining = null,
                addBank = seed.value,
                onSubmit = {},
                onErrorDismiss = {},
                onDismiss = {}
            )
        }
        rule.waitForIdle()
    }

    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun tap(tag: String) { node(tag).performClick(); rule.waitForIdle() }
    /**
     * A tag's own text, else the text merged into its node: the condition toggle is a clickable Row whose text is its
     * children's (E5b4b device run 1, S01). The condition must read non-empty, so an empty read cannot pass a comparison.
     */
    private fun textOf(tag: String): String {
        fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
        val text = node(tag).fetchSemanticsNode().own()
            .ifEmpty { rule.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().own() }
        assertTrue("$tag: 조건 문구를 읽지 못했다", tag != AlertAddSheetTags.CONDITION || text.isNotEmpty())
        return text
    }
    private fun update(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }

    @Test fun S7E5B4B_S01_aLaterSeedOrQuoteKeepsTheOpenInput_andOnlyTheShownQuoteMoves() {
        show()
        assertEquals("premise: hana 로 열렸다", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        val bank = textOf(AlertAddSheetTags.HEADER_BANK)

        node(AlertAddSheetTags.THRESHOLD).performTextReplacement("1490")
        rule.waitForIdle()
        val conditionBefore = textOf(AlertAddSheetTags.CONDITION)
        assertTrue("premise: 조건 문구를 읽었다: '$conditionBefore'", conditionBefore.isNotEmpty())
        tap(AlertAddSheetTags.CONDITION)
        val condition = textOf(AlertAddSheetTags.CONDITION)
        assertNotEquals("premise: 조건이 바뀌었다", conditionBefore, condition)
        tap(AlertAddSheetTags.REPEAT)
        tap(AlertAddSheetTags.repeatOption(2))
        val repeat = textOf(AlertAddSheetTags.REPEAT_LABEL)
        assertEquals("premise: 반복이 바뀌었다", "5분", repeat)
        val shownBefore = textOf(AlertAddSheetTags.CURRENT_RATE)

        update {
            seed.value = "woori"
            quotes.value = quotes.value + ("hana" to 1510.0) + ("woori" to 1460.0)
        }

        assertEquals("연 은행이 바뀌었다", bank, textOf(AlertAddSheetTags.HEADER_BANK))
        assertEquals("입력한 기준값이 바뀌었다", "1490", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("조건이 바뀌었다", condition, textOf(AlertAddSheetTags.CONDITION))
        assertEquals("반복이 바뀌었다", repeat, textOf(AlertAddSheetTags.REPEAT_LABEL))
        val shown = textOf(AlertAddSheetTags.CURRENT_RATE)
        assertNotEquals("현재 시세 표시가 따라오지 않았다", shownBefore, shown)
        assertTrue("현재 시세 표시가 새 hana 값이 아니다: $shown", shown.contains(ExchangeRate.formatRate(1510.0, SupportedCurrency.USD_KRW.code)))
    }
}
