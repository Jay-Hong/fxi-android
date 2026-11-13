package com.jay.fxi.ui.alert

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b3a (s7e5b3_api_agreed.r1.md: proposal r2 D3 + errata r1 E2–E3): the real [AlertAddSheet] alone, with no caller in the
 * product. It shows the rules of [BankAlertEditor], hands the immutable input to onSubmit once per save, stays dismissable while
 * saving (cancel, Back, dragging it away), disables the form instead of swallowing touches, shows a fixed error and clears it on
 * 확인, and shows the add-only remaining line. Every query walks the unmerged tree.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B3ASheetTest {

    @get:Rule val rule = createComposeRule()

    private val mode = mutableStateOf<BankAlertEditorMode>(BankAlertEditorMode.Add)
    private val quotes = mutableStateOf(mapOf("kb" to 1400.0, "hana" to 1500.0))
    private val existing = mutableStateOf<List<AlertSetting>>(emptyList())
    private val saving = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val remaining = mutableStateOf<Int?>(null)
    private val submits = mutableListOf<BankAlertEditorInput>()
    private var errorDismisses = 0
    /** When set, the parent consumes the error as a holder would: the next composition has none. */
    private var clearErrorOnDismiss = false
    private var dismisses = 0

    private fun show() {
        rule.setContent {
            AlertAddSheet(
                mode = mode.value,
                currency = SupportedCurrency.USD_KRW,
                quotes = quotes.value,
                existing = existing.value,
                saving = saving.value,
                errorMessage = error.value,
                remaining = remaining.value,
                onSubmit = { submits += it },
                onErrorDismiss = { errorDismisses += 1; if (clearErrorOnDismiss) error.value = null },
                onDismiss = { dismisses += 1 }
            )
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun tap(tag: String) { node(tag).performClick(); rule.waitForIdle() }
    private fun textOf(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
            (n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
        }
    private fun update(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }

    private fun row(id: Int = 7, bank: String = "kb", threshold: Double = 1400.0, enabled: Boolean = true, repeat: Int? = null) = AlertSetting(
        id = id, userId = "u", bank = bank, currency = "usd-krw", condition = AlertCondition.BELOW, threshold = threshold,
        isEnabled = enabled, triggered = false, createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0),
        triggeredAt = null, repeatIntervalSec = repeat
    )

    @Test fun S7E5B3A_V01_addingShowsTheNineOfferedBanksStartingOnKbWithItsPriceAndOnce() {
        show()
        assertEquals("알림 추가", textOf(AlertAddSheetTags.TITLE))
        for (b in BankAlertEditor.BANK_CHOICES) assertEquals(b.code, 1, count(AlertAddSheetTags.bank(b.code)))
        assertEquals("Citi is not offered", 0, count(AlertAddSheetTags.bank(Bank.CITI.code)))
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("국민은행", textOf(AlertAddSheetTags.HEADER_BANK))
        assertEquals("한번만", textOf(AlertAddSheetTags.REPEAT_LABEL))
        assertEquals("조건 충족 시 1회만 발송", textOf(AlertAddSheetTags.REPEAT_DESCRIPTION))
        assertEquals("알림은 1회 발송 후 자동으로 꺼집니다", textOf(AlertAddSheetTags.BANNER_REPEAT))
        assertEquals("no enable switch (G4)", 0, texts("알림 활성화"))
        assertEquals(1, count(AlertAddSheetTags.CURRENT_RATE)); assertEquals(1, count(AlertAddSheetTags.RANGE))
        node(AlertAddSheetTags.SAVE).assertIsEnabled()
    }

    @Test fun S7E5B3A_V02_savingHandsTheExactInputOnceAndPickingABankStartsAtItsPrice() {
        show()
        tap(AlertAddSheetTags.bank("hana"))
        assertEquals("1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.CONDITION)
        tap(AlertAddSheetTags.REPEAT)
        tap(AlertAddSheetTags.repeatOption(2))   // null, 1분, 5분
        assertEquals("5분", textOf(AlertAddSheetTags.REPEAT_LABEL))
        assertEquals("조건이 충족되는 동안 5분마다 반복 발송됩니다", textOf(AlertAddSheetTags.BANNER_REPEAT))
        tap(AlertAddSheetTags.SAVE)
        assertEquals(listOf(BankAlertEditorInput(Bank.HANA, AlertCondition.ABOVE, "1500.00", RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES))), submits)
        assertEquals("saving does not dismiss by itself", 0, dismisses)
    }

    @Test fun S7E5B3A_V03_typingIsTrimmedAndOutOfRangeOrDuplicateBlocksSaving() {
        show()
        node(AlertAddSheetTags.THRESHOLD).performTextReplacement("1,234.567"); rule.waitForIdle()
        assertEquals("1234.56", textOf(AlertAddSheetTags.THRESHOLD))
        node(AlertAddSheetTags.THRESHOLD).performTextReplacement("2200"); rule.waitForIdle()
        assertEquals(1, count(AlertAddSheetTags.OUT_OF_RANGE)); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        node(AlertAddSheetTags.THRESHOLD).performTextReplacement("1400"); rule.waitForIdle()
        assertEquals(0, count(AlertAddSheetTags.OUT_OF_RANGE)); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { existing.value = listOf(row(id = 1)) }
        assertEquals(1, count(AlertAddSheetTags.DUPLICATE)); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        tap(AlertAddSheetTags.SAVE)
        assertEquals("a disabled save sends nothing", emptyList<BankAlertEditorInput>(), submits)
    }

    @Test fun S7E5B3A_V04_withoutAQuoteThereIsNoRangeAndSavingIsStillPossible() {
        quotes.value = emptyMap()
        show()
        assertEquals("", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals(0, count(AlertAddSheetTags.CURRENT_RATE)); assertEquals(0, count(AlertAddSheetTags.RANGE))
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        node(AlertAddSheetTags.THRESHOLD).performTextReplacement("5"); rule.waitForIdle()
        node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { quotes.value = mapOf("kb" to 1400.0) }
        assertEquals("a price that arrives later never overwrites what is typed", "5", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("but the range now applies", 1, count(AlertAddSheetTags.OUT_OF_RANGE))
    }

    @Test fun S7E5B3A_V05_editingKeepsTheRowAndNeedsAChangeExceptForAnOffRow() {
        mode.value = BankAlertEditorMode.Edit(row(threshold = 12345.0, repeat = 3600))
        quotes.value = emptyMap()   // gap hunt G08: no range, so only "nothing changed" can keep SAVE off
        show()
        assertEquals("알림 수정", textOf(AlertAddSheetTags.TITLE))
        assertEquals("five whole digits are shown as they are", "12345.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("1시간", textOf(AlertAddSheetTags.REPEAT_LABEL))
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        update { mode.value = BankAlertEditorMode.Edit(row(threshold = 1400.0, enabled = false)) }
        node(AlertAddSheetTags.SAVE).assertIsEnabled()
        tap(AlertAddSheetTags.SAVE)
        assertEquals(listOf(BankAlertEditorInput(Bank.KB, AlertCondition.BELOW, "1400.00", RepeatIntervalChange.Keep)), submits)
    }

    @Test fun S7E5B3A_V06_anEditedCitiOrUnknownRowKeepsItsNameAndAnUnknownIntervalIsCalled반복알림() {
        mode.value = BankAlertEditorMode.Edit(row(bank = "citi"))
        show()
        assertEquals("씨티은행", textOf(AlertAddSheetTags.HEADER_BANK))
        assertEquals("Citi is not offered while editing either", 0, count(AlertAddSheetTags.bank("citi")))
        update { mode.value = BankAlertEditorMode.Edit(row(bank = "krx", repeat = 120)) }
        assertEquals("krx", textOf(AlertAddSheetTags.HEADER_BANK))
        assertEquals("반복 알림", textOf(AlertAddSheetTags.REPEAT_LABEL))
        assertEquals("조건 충족 동안 반복 발송", textOf(AlertAddSheetTags.REPEAT_DESCRIPTION))
    }

    @Test fun S7E5B3A_V07_whileSavingTheFormIsDisabledButCancelStillDismisses() {
        show()
        update { saving.value = true }
        assertEquals(1, count(AlertAddSheetTags.SAVING)); assertEquals(1, texts("저장 중..."))
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        node(AlertAddSheetTags.bank("hana")).assertIsNotEnabled()
        node(AlertAddSheetTags.CONDITION).assertIsNotEnabled()
        node(AlertAddSheetTags.REPEAT).assertIsNotEnabled()
        node(AlertAddSheetTags.THRESHOLD).assertIsNotEnabled()
        tap(AlertAddSheetTags.CANCEL)
        assertEquals(1, dismisses)
        assertEquals(emptyList<BankAlertEditorInput>(), submits)
    }

    @Test fun S7E5B3A_V08_whileSavingBackDismisses() {
        show()
        update { saving.value = true }
        Espresso.pressBack(); rule.waitForIdle()
        assertEquals(1, dismisses)
    }

    @Test fun S7E5B3A_V09_whileSavingDraggingTheSheetAwayDismisses() {
        show()
        update { saving.value = true }
        node(AlertAddSheetTags.SAVING).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 200) }
        rule.waitForIdle()
        assertEquals(1, dismisses)
    }

    /** V09 turns saving on after the first composition; this one composes already saving, so a guard on saving inside the
     *  sheet's dismiss request cannot hide behind a request captured before saving began. */
    @Test fun S7E5B3A_V09b_composedWhileSavingDraggingTheSheetAwayStillDismisses() {
        saving.value = true
        show()
        assertEquals(1, count(AlertAddSheetTags.SAVING))
        node(AlertAddSheetTags.SAVING).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 200) }
        rule.waitForIdle()
        assertEquals(1, dismisses)
    }

    @Test fun S7E5B3A_V10_anErrorShowsFixedCopyAndConfirmClearsItOnce() {
        show()
        update { error.value = "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요." }
        assertEquals(1, count(AlertAddSheetTags.ERROR_DIALOG)); assertEquals(1, texts("오류"))
        assertEquals(1, texts("알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."))
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        assertEquals(1, errorDismisses)
        assertEquals("the error alone sends nothing and keeps the sheet", listOf(emptyList<BankAlertEditorInput>(), 0), listOf(submits, dismisses))
    }

    @Test fun S7E5B3A_V11_theRemainingLineIsAddOnlyAtThreeOrFewer() {
        show()
        assertEquals(0, count(AlertAddSheetTags.REMAINING))
        update { remaining.value = 4 }; assertEquals(0, count(AlertAddSheetTags.REMAINING))
        update { remaining.value = 3 }; assertEquals("알림 3개 더 추가 가능", textOf(AlertAddSheetTags.REMAINING))
        update { remaining.value = 1 }; assertEquals("알림 1개 더 추가 가능", textOf(AlertAddSheetTags.REMAINING))
        update { remaining.value = 0 }; assertEquals("알림을 더 추가할 수 없습니다", textOf(AlertAddSheetTags.REMAINING))
        node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { mode.value = BankAlertEditorMode.Edit(row(enabled = false)); remaining.value = 2 }
        assertEquals("never while editing", 0, count(AlertAddSheetTags.REMAINING))
    }

    @Test fun S7E5B3A_V13_pickingOnceFromTheMenuMeansOnceAgain() {
        mode.value = BankAlertEditorMode.Edit(row(repeat = 300, enabled = true))
        show()
        assertEquals("5분", textOf(AlertAddSheetTags.REPEAT_LABEL))
        tap(AlertAddSheetTags.REPEAT)
        tap(AlertAddSheetTags.repeatOption(0))
        assertEquals("한번만", textOf(AlertAddSheetTags.REPEAT_LABEL))
        assertEquals("조건 충족 시 1회만 발송", textOf(AlertAddSheetTags.REPEAT_DESCRIPTION))
        assertEquals("알림은 1회 발송 후 자동으로 꺼집니다", textOf(AlertAddSheetTags.BANNER_REPEAT))
        tap(AlertAddSheetTags.SAVE)
        assertEquals(listOf(BankAlertEditorInput(Bank.KB, AlertCondition.BELOW, "1400.00", RepeatIntervalChange.Once)), submits)
    }

    @Test fun S7E5B3A_V12_aDuplicateIsJudgedAgainstTheCurrentListAndEditingSkipsItsOwnRow() {
        val self = row(id = 7, threshold = 1400.0, enabled = false)
        mode.value = BankAlertEditorMode.Edit(self)
        existing.value = listOf(self)
        show()
        assertEquals(0, count(AlertAddSheetTags.DUPLICATE)); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { existing.value = listOf(self, row(id = 8, threshold = 1400.001)) }
        assertEquals(1, count(AlertAddSheetTags.DUPLICATE)); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        assertTrue(submits.isEmpty())
    }

    // --- gap hunt r1 (s7e5b3a_gaphunt.codex.r1.md) ---

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    private fun pick(index: Int) { tap(AlertAddSheetTags.REPEAT); tap(AlertAddSheetTags.repeatOption(index)) }
    private fun type(text: String) { node(AlertAddSheetTags.THRESHOLD).performTextReplacement(text); rule.waitForIdle() }
    private fun pixels(tag: String): List<Triple<Int, Int, Int>> {
        val map = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val c = map[x, y].toArgb()
            out += Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        return out
    }
    private fun reds(tag: String) = pixels(tag).count { (r, g, b) -> r > 170 && g < 120 && b < 110 }
    private fun yellows(tag: String) = pixels(tag).count { (r, g, b) -> r > 170 && g > 150 && b < 90 }

    @Test fun S7E5B3A_V14_untouchedAndRoundTripsStayUnchanged() {
        val self = row(id = 7, bank = "kb", threshold = 1400.0, enabled = true, repeat = 60)
        mode.value = BankAlertEditorMode.Edit(self)
        existing.value = listOf(self)
        show()
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        tap(AlertAddSheetTags.bank("hana")); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        tap(AlertAddSheetTags.bank("kb")); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        tap(AlertAddSheetTags.CONDITION); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        tap(AlertAddSheetTags.CONDITION); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        type("1410"); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        type("1400.00"); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        pick(2); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        pick(1); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        assertTrue(submits.isEmpty())
    }

    @Test fun S7E5B3A_V15_aLargeInitialThresholdSurvivesOtherChangesAndIsTrimmedOnTheFirstTyping() {
        mode.value = BankAlertEditorMode.Edit(row(threshold = 12345.0, enabled = false))
        quotes.value = emptyMap()
        show()
        assertEquals("12345.00", textOf(AlertAddSheetTags.THRESHOLD)); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        tap(AlertAddSheetTags.CONDITION); pick(2); update { remaining.value = 1 }
        assertEquals("12345.00", textOf(AlertAddSheetTags.THRESHOLD))
        type("12345.01")
        assertEquals("1234.01", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.SAVE)
        assertEquals("1234.01", submits.single().thresholdText)
    }

    @Test fun S7E5B3A_V16_pickingABankFollowsTheModeAndTheCurrentPrices() {
        mode.value = BankAlertEditorMode.Edit(row(threshold = 1300.0))
        show()
        tap(AlertAddSheetTags.bank("hana"))
        assertEquals("editing keeps the threshold", "1300.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.SAVE)
        assertEquals(Bank.HANA, submits.single().bank)
        update { mode.value = BankAlertEditorMode.Add }
        type("1300")
        tap(AlertAddSheetTags.bank("sc")); assertEquals("no price, the typed text stays", "1300", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.bank("kb")); assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.bank("hana")); assertEquals("1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        update { quotes.value = mapOf("kb" to 1400.0, "hana" to 1600.0) }
        assertEquals("a new price does not overwrite", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.bank("hana")); assertEquals("a re-pick uses the latest price", "1600.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5B3A_V17_everyRepeatMenuEntryIsOfferedInOrderAndSetsItsOwnValue() {
        show()
        BankAlertEditor.REPEAT_CHOICES.forEachIndexed { index, interval ->
            tap(AlertAddSheetTags.REPEAT)
            val label = interval?.label ?: "한번만"
            rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(AlertAddSheetTags.repeatOption(index))), useUnmergedTree = true).assertExists()
            tap(AlertAddSheetTags.repeatOption(index))
            val choice = if (interval == null) RepeatIntervalChange.Once else RepeatIntervalChange.Every(interval)
            val words = BankAlertEditor.repeatTexts(BankAlertEditorMode.Add, choice)
            assertEquals(listOf(words.label, words.description, words.banner),
                listOf(textOf(AlertAddSheetTags.REPEAT_LABEL), textOf(AlertAddSheetTags.REPEAT_DESCRIPTION), textOf(AlertAddSheetTags.BANNER_REPEAT)))
            tap(AlertAddSheetTags.SAVE)
            assertEquals("option $index", choice, submits.last().repeat)
        }
        assertEquals(11, submits.size)
    }

    @Test fun S7E5B3A_V18_theHintsFollowTheSelectedBanksLivePriceWithoutTouchingTheInput() {
        show()
        type("1400")
        update { quotes.value = mapOf("kb" to 800.0, "hana" to 1500.0) }
        assertEquals("1400", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("현재 국민은행 환율: ${ExchangeRate.formatRate(800.0, "usd-krw")}", textOf(AlertAddSheetTags.CURRENT_RATE))
        assertEquals("설정 가능: 400 ~ 1200원", textOf(AlertAddSheetTags.RANGE))
        assertEquals("현재 환율의 ±50% 범위를 벗어났습니다", textOf(AlertAddSheetTags.OUT_OF_RANGE))
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        tap(AlertAddSheetTags.bank("hana")); type("2200")
        assertEquals("현재 하나은행 환율: ${ExchangeRate.formatRate(1500.0, "usd-krw")}", textOf(AlertAddSheetTags.CURRENT_RATE))
        assertEquals("설정 가능: 750 ~ 2250원", textOf(AlertAddSheetTags.RANGE))
        assertEquals(0, count(AlertAddSheetTags.OUT_OF_RANGE)); node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { quotes.value = mapOf("kb" to 800.0, "hana" to Double.NaN) }
        assertEquals(listOf(0, 0, 0), listOf(count(AlertAddSheetTags.CURRENT_RATE), count(AlertAddSheetTags.RANGE), count(AlertAddSheetTags.OUT_OF_RANGE)))
        node(AlertAddSheetTags.SAVE).assertIsEnabled()
        update { existing.value = listOf(row(id = 1, bank = "hana", threshold = 2200.0)) }
        assertEquals("동일한 알림이 이미 존재합니다", textOf(AlertAddSheetTags.DUPLICATE)); node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        update { existing.value = emptyList() }
        assertEquals(0, count(AlertAddSheetTags.DUPLICATE)); node(AlertAddSheetTags.SAVE).assertIsEnabled()
    }

    @Test fun S7E5B3A_V19_saveIsOncePerClickAndTheFormComesBackAfterSaving() {
        show()
        tap(AlertAddSheetTags.SAVE)
        val first = submits.single()
        update { saving.value = true }
        node(AlertAddSheetTags.SAVE).assertIsNotEnabled()
        tap(AlertAddSheetTags.SAVE); tap(AlertAddSheetTags.bank("hana"))
        assertEquals("nothing while saving", listOf(first), submits)
        update { saving.value = false }
        assertEquals(0, count(AlertAddSheetTags.SAVING))
        for (tag in listOf(AlertAddSheetTags.SAVE, AlertAddSheetTags.bank("hana"), AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT, AlertAddSheetTags.THRESHOLD)) {
            node(tag).assertIsEnabled()
        }
        type("1401")
        tap(AlertAddSheetTags.SAVE); tap(AlertAddSheetTags.SAVE)
        assertEquals(listOf("1400.00", "1401", "1401"), submits.map { it.thresholdText })
        assertEquals("the first input is unchanged", "1400.00", first.thresholdText)
    }

    @Test fun S7E5B3A_V20_noRemainingIsShownOnlyAndDoesNotBlockSaving() {
        remaining.value = 0
        show()
        tap(AlertAddSheetTags.SAVE)
        assertEquals(listOf(1, 0), listOf(submits.size, dismisses))
    }

    @Test fun S7E5B3A_V21_consumingTheErrorKeepsTheDraftAndSendsNothing() {
        clearErrorOnDismiss = true
        show()
        tap(AlertAddSheetTags.bank("hana")); tap(AlertAddSheetTags.CONDITION); type("1550"); pick(1)
        fun draft() = listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.REPEAT_LABEL))
        val before = draft()
        update { error.value = "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요." }
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        assertEquals(listOf(1, 0), listOf(errorDismisses, count(AlertAddSheetTags.ERROR_DIALOG)))
        assertEquals(before, draft())
        update { error.value = "저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요." }
        Espresso.pressBack(); rule.waitForIdle()
        assertEquals("Back closes the dialog, not the sheet", listOf(2, 0, 0), listOf(errorDismisses, count(AlertAddSheetTags.ERROR_DIALOG), dismisses))
        assertEquals(before, draft())
        update { remaining.value = 2 }
        assertEquals("no callback without an error", 2, errorDismisses)
        assertTrue(submits.isEmpty())
    }

    @Test fun S7E5B3A_V22a_composedWhileSavingCancelDismisses() {
        saving.value = true
        show()
        tap(AlertAddSheetTags.CANCEL)
        assertEquals(listOf(1, 0), listOf(dismisses, submits.size))
    }

    @Test fun S7E5B3A_V22b_composedWhileSavingBackDismisses() {
        saving.value = true
        show()
        Espresso.pressBack(); rule.waitForIdle()
        assertEquals(listOf(1, 0), listOf(dismisses, submits.size))
    }

    @Test fun S7E5B3A_V22c_composedWhileSavingATapOnTheScrimDismisses() {
        saving.value = true
        show()
        // The SHEET node spans the sheet's window; the title bar shows where the sheet's surface begins.
        val window = bounds(AlertAddSheetTags.SHEET)
        val titleTop = bounds(AlertAddSheetTags.TITLE).top
        assertTrue("premise: there is scrim above the sheet (window top ${window.top}, title top $titleTop px)", titleTop - window.top > 150f)
        node(AlertAddSheetTags.SHEET).performTouchInput { click(Offset(width / 2f, 10f)) }
        rule.waitForIdle()
        assertEquals(listOf(1, 0), listOf(dismisses, submits.size))
    }

    @Test fun S7E5B3A_V23_aNarrowScreenShowsFiveThenFourBanksAndNoSwitch() {
        show()
        val cells = BankAlertEditor.BANK_CHOICES.map { b -> node(AlertAddSheetTags.bank(b.code)).assertIsDisplayed(); bounds(AlertAddSheetTags.bank(b.code)) }
        val first = cells.take(5); val second = cells.drop(5)
        assertTrue("premise: a narrow sheet", first.all { kotlin.math.abs(it.top - first[0].top) < 1f })
        assertTrue("four on the next row", second.all { kotlin.math.abs(it.top - second[0].top) < 1f } && second[0].top >= first[0].bottom - 1f)
        for (row in listOf(first, second)) row.zipWithNext().forEach { (a, b) -> assertTrue("in order, no overlap", b.left >= a.right - 1f) }
        assertEquals("no switch at all", 0, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, texts("달러")); assertEquals(1, texts("(USD/KRW)"))
    }

    @Test fun S7E5B3A_V24_theRemainingLineIsRedAtZeroAndYellowOtherwiseAfterTheThreeNotes() {
        remaining.value = 0
        show()
        assertTrue("the line is below the first note", bounds(AlertAddSheetTags.REMAINING).top > bounds(AlertAddSheetTags.BANNER_REPEAT).bottom)
        // impl review r1: it is the fourth line, after all three notes (errata E3, iOS :658–676)
        val lastNote = rule.onNode(hasText("평일 아침 8:30 경 첫 고시 환율은 환전이 되지 않습니다"), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("the line is below the third note", bounds(AlertAddSheetTags.REMAINING).top > lastNote.bottom)
        assertTrue("a red icon at zero", reds(AlertAddSheetTags.REMAINING_ICON) > 0 && yellows(AlertAddSheetTags.REMAINING_ICON) == 0)
        assertTrue("red text at zero", reds(AlertAddSheetTags.REMAINING) > 0)
        update { remaining.value = 2 }
        assertTrue("a yellow icon otherwise", yellows(AlertAddSheetTags.REMAINING_ICON) > 0 && reds(AlertAddSheetTags.REMAINING_ICON) == 0)
        assertEquals("plain text otherwise", 0, reds(AlertAddSheetTags.REMAINING))
        update { remaining.value = null }
        assertEquals(listOf(0, 0), listOf(count(AlertAddSheetTags.REMAINING), count(AlertAddSheetTags.REMAINING_ICON)))
    }

    /** impl review r1: the condition toggle draws the app's own condition symbols (AlertCondition.symbol, iOS △/▽). */
    @Test fun S7E5B3A_V25_theConditionToggleShowsTheTriangleAndItsName() {
        show()
        fun shown(text: String) = rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(AlertAddSheetTags.CONDITION)), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertEquals(listOf(1, 1, 0, 0), listOf(shown("▽"), shown("이하"), shown("△"), shown("이상")))
        tap(AlertAddSheetTags.CONDITION)
        assertEquals(listOf(0, 0, 1, 1), listOf(shown("▽"), shown("이하"), shown("△"), shown("이상")))
    }
}
