package com.jay.fxi

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.ui.free.RateRowEditEntry
import com.jay.fxi.ui.free.RateRowEditor
import com.jay.fxi.ui.rates.view.RateRowCustomizeSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the sheet hands back, and what it refuses to hand back.
 *
 * Everything happens in a draft, so the interesting assertions are about 완료 and 취소 — the
 * arithmetic behind them is already checked on the JVM, and what a device adds is whether the two
 * buttons really mean write and forget.
 */
class RateRowCustomizeSheetTest {

    @get:Rule val rule = createComposeRule()

    private val editor = RateRowEditor(
        list = RateRowList.FX_BANKS,
        title = "은행 순서 설정",
        entries = listOf(
            RateRowEditEntry("investing", "인베스팅", visible = true),
            RateRowEditEntry("kb", "국민은행", visible = true),
            RateRowEditEntry("citi", "씨티은행", visible = false)
        )
    )

    private class Applied(
        val seeded: List<String>,
        val order: List<String>,
        val hidden: Set<String>
    )

    private var applied: Applied? = null
    private var dismissed = false

    private fun show(model: RateRowEditor = editor) {
        rule.setContent {
            var open by mutableStateOf(true)
            if (open) {
                RateRowCustomizeSheet(
                    editor = model,
                    onDismiss = { dismissed = true; open = false },
                    onApply = { seeded, order, hidden ->
                        applied = Applied(seeded, order, hidden)
                        open = false
                    }
                )
            } else {
                Text("닫힘")
            }
        }
    }

    /**
     * A hidden row is in the sheet, legible, and says which way its switch goes.
     *
     * It is the one row somebody opens this for. Dropping it — or dimming it to half opacity, which
     * the paid sheet does — would make the rows this screen exists for the hardest to find.
     */
    @Test
    fun aHiddenRowIsPresentAndSaysHowToTurnItOn() {
        show()
        rule.onNodeWithText("씨티은행").assertIsDisplayed()
        rule.onNodeWithContentDescription("씨티은행 표시하기").assertExists()
        rule.onNodeWithContentDescription("국민은행 숨기기").assertExists()
    }

    /** 완료 hands back the seed as well, because "did they reorder" can only be asked against it. */
    @Test
    fun doneHandsBackTheSeedAndTheDraft() {
        show()
        rule.onNodeWithContentDescription("국민은행 숨기기").performClick()
        rule.onNodeWithText("완료").performClick()

        val result = requireNotNull(applied)
        assertEquals(listOf("investing", "kb", "citi"), result.seeded)
        assertEquals(listOf("investing", "kb", "citi"), result.order)
        assertEquals(setOf("kb", "citi"), result.hidden)
    }

    /** Turning a hidden row back on takes it out of the answer's hidden set. */
    @Test
    fun turningARowOnRemovesItFromTheHiddenSet() {
        show()
        rule.onNodeWithContentDescription("씨티은행 표시하기").performClick()
        rule.onNodeWithText("완료").performClick()
        assertEquals(emptySet<String>(), requireNotNull(applied).hidden)
    }

    /** 취소 writes nothing, however much was changed first. */
    @Test
    fun cancelWritesNothing() {
        show()
        rule.onNodeWithContentDescription("국민은행 숨기기").performClick()
        rule.onNodeWithContentDescription("씨티은행 표시하기").performClick()
        rule.onNodeWithText("취소").performClick()

        assertNull("취소했는데 기록됐다", applied)
        assertTrue(dismissed)
    }

    /** 되돌리기 puts the draft back to what the sheet opened with, without writing anything. */
    @Test
    fun revertRestoresTheSeedWithoutWriting() {
        show()
        rule.onNodeWithContentDescription("국민은행 숨기기").performClick()
        rule.onNodeWithContentDescription("국민은행 표시하기").assertExists()

        rule.onNodeWithText("되돌리기").performClick()
        rule.onNodeWithContentDescription("국민은행 숨기기").assertExists()
        assertNull(applied)

        rule.onNodeWithText("완료").performClick()
        assertEquals(setOf("citi"), requireNotNull(applied).hidden)
    }

    /**
     * A row the screen is only borrowing says so.
     *
     * D18 asks that the settings surface show the same effective result as the screen. Without this
     * the sheet would show every row switched off while one of them was plainly on screen, and
     * nothing would explain which.
     */
    @Test
    fun aBorrowedRowIsLabelledInTheSheet() {
        show(
            editor.copy(
                entries = listOf(
                    RateRowEditEntry("investing", "인베스팅", visible = false, projected = true),
                    RateRowEditEntry("kb", "국민은행", visible = false)
                )
            )
        )
        rule.onNodeWithText("임시 표시 중").assertIsDisplayed()
    }
}
