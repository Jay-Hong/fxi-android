package com.jay.fxi.ui.alert

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertSectionState.Content
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b3b (s7e5b3_api_agreed.r1.md D5, D7): the real [AlertSection] with its add area and row editor entry. The add area follows
 * the empty text when notifications are not blocked and follows the rows always; Loading, Error and the blocked prompt have none.
 * Hidden draws nothing, Available a button ("알림 추가", "(n개 남음)" at three or fewer), AtCap the cap line instead. The prompt now
 * asks for the add entry and the banner for the permission only; a row's bank and condition area opens that row's editor.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B3BSectionTest {

    @get:Rule val rule = createComposeRule()

    private val shown = mutableStateOf(state(Content.Rows(listOf(setting(71)))))
    private val blocked = mutableStateOf(false)
    private val availability = mutableStateOf<BankAlertAddAvailability>(BankAlertAddAvailability.Available(10))
    private var adds = 0
    private var enables = 0
    private val opens = mutableListOf<Int>()
    private val toggles = mutableListOf<Pair<Int, Boolean>>()
    private val deletes = mutableListOf<Int>()

    private fun show() {
        rule.setContent {
            AlertSection(
                currency = SupportedCurrency.USD_KRW,
                state = shown.value,
                onRetry = { },
                onRefresh = { },
                actionIdentity = A,
                onToggle = { id, enabled -> toggles += id to enabled },
                onDelete = { id -> deletes += id },
                notificationsBlocked = blocked.value,
                onEnableNotifications = { enables += 1 },
                addAvailability = availability.value,
                onAdd = { adds += 1 },
                onEdit = { id -> opens += id }
            )
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { rule.onNodeWithTag(tag, useUnmergedTree = true).performClick(); rule.waitForIdle() }
    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun update(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }
    private fun inButton(text: String) =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(BankAlertSectionTags.ADD_BUTTON)), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun S7E5B3B_B01_rowsShowTheAddButtonBelowTheListAndAClickAsksForTheAddEntryOnce() {
        show()
        assertEquals(1, count(BankAlertSectionTags.ADD_BUTTON))
        assertEquals(1, inButton("알림 추가"))
        assertTrue("below the rows", bounds(BankAlertSectionTags.ADD_BUTTON).top >= bounds(BankAlertSectionTags.ROWS).bottom - 1f)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals(listOf(1, 0), listOf(adds, enables))
        assertEquals(emptyList<Int>(), opens)
    }

    @Test fun S7E5B3B_B02_theRemainingCountShowsAtThreeOrFewer() {
        show()
        for ((n, shownText) in listOf(4 to null, 3 to "(3개 남음)", 1 to "(1개 남음)", 30 to null)) {
            update { availability.value = BankAlertAddAvailability.Available(n) }
            assertEquals("$n", if (shownText == null) 0 else 1, if (shownText == null) inButton("(${n}개 남음)") else inButton(shownText))
        }
    }

    @Test fun S7E5B3B_B03_atTheCapTheLineReplacesTheButtonAndDoesNothing() {
        availability.value = BankAlertAddAvailability.AtCap
        show()
        assertEquals(listOf(0, 1), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.ADD_AT_CAP)))
        assertEquals(1, texts("최대 30개까지 설정할 수 있습니다"))
        tap(BankAlertSectionTags.ADD_AT_CAP)
        assertEquals(0, adds)
    }

    @Test fun S7E5B3B_B04_hiddenDrawsNeitherTheButtonNorTheCapLine() {
        availability.value = BankAlertAddAvailability.Hidden
        show()
        assertEquals(listOf(0, 0), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.ADD_AT_CAP)))
        assertEquals(0, texts("알림 추가"))
    }

    @Test fun S7E5B3B_B05_anEmptySectionShowsTheButtonBelowTheEmptyTextOnlyWhenNotBlocked() {
        shown.value = state(Content.Empty)
        show()
        assertEquals(listOf(1, 1), listOf(count(BankAlertSectionTags.EMPTY), count(BankAlertSectionTags.ADD_BUTTON)))
        assertTrue("below the empty text", bounds(BankAlertSectionTags.ADD_BUTTON).top >= bounds(BankAlertSectionTags.EMPTY).bottom - 1f)
        update { blocked.value = true }
        assertEquals("the prompt alone", listOf(1, 0, 0), listOf(count(BankAlertSectionTags.NOTIFY_PROMPT), count(BankAlertSectionTags.ADD_BUTTON),
            count(BankAlertSectionTags.ADD_AT_CAP)))
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        assertEquals("the prompt asks for the add entry", listOf(1, 0), listOf(adds, enables))
        update { availability.value = BankAlertAddAvailability.AtCap }
        assertEquals("no cap line under the prompt either", 0, count(BankAlertSectionTags.ADD_AT_CAP))
    }

    @Test fun S7E5B3B_B06_blockedRowsShowTheBannerTheRowsAndTheButtonAndTheBannerOnlyAsks() {
        blocked.value = true
        show()
        assertEquals(listOf(1, 1, 1), listOf(count(BankAlertSectionTags.NOTIFY_BANNER), count(BankAlertSectionTags.ROWS), count(BankAlertSectionTags.ADD_BUTTON)))
        tap(BankAlertSectionTags.NOTIFY_BANNER)
        assertEquals("the banner asks for the permission only", listOf(1, 0), listOf(enables, adds))
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals(listOf(1, 1), listOf(enables, adds))
    }

    @Test fun S7E5B3B_B07_loadingAndErrorHaveNoAddArea() {
        shown.value = state(Content.Loading)
        show()
        assertEquals("loading", listOf(0, 0), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.ADD_AT_CAP)))
        update { shown.value = state(Content.Error("알림 설정을 불러오지 못했습니다.")) }
        assertEquals("error", listOf(0, 0), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.ADD_AT_CAP)))
    }

    @Test fun S7E5B3B_B08_aRowsBankAndConditionAreaOpensThatRowAndTheSwitchAndDeleteNever() {
        shown.value = state(Content.Rows(listOf(setting(71), setting(72, order = 1))))
        show()
        tap(BankAlertSectionTags.rowOpen(72))
        assertEquals(listOf(72), opens)
        tap(BankAlertSectionTags.toggle(71))
        tap(BankAlertSectionTags.delete(71))
        tap(BankAlertSectionTags.DELETE_CANCEL)
        assertEquals("the switch and delete never open", listOf(72), opens)
        assertEquals(listOf(71 to false), toggles)
        assertEquals(0, adds)
    }

    @Test fun S7E5B3B_B09_foldingHidesTheAddAreaAndTheRows() {
        show()
        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        assertEquals(listOf(0, 0), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.rowOpen(71))))
        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        assertEquals(1, count(BankAlertSectionTags.ADD_BUTTON))
    }

    @Test fun S7E5B3B_B10_theBankNameAndTheConditionBothOpenTheirRow() {
        val rows = listOf(setting(71), setting(72, order = 1, bank = "hana"))
        shown.value = state(Content.Rows(rows))
        show()
        for ((s, bankName) in rows.zip(listOf("국민은행", "하나은행"))) {
            val inRow = hasAnyAncestor(hasTestTag(BankAlertSectionTags.rowOpen(s.id)))
            for (text in listOf(bankName, s.formattedThreshold, s.condition.displayText)) {
                assertEquals("${s.id}: '$text' is inside its row's editor entry", 1,
                    rule.onAllNodes(hasText(text) and inRow, useUnmergedTree = true).fetchSemanticsNodes().size)
            }
            // A touch at the text itself, not the tag's node: the entry covers the bank column and the condition line.
            for (text in listOf(bankName, s.formattedThreshold)) {
                rule.onNode(hasText(text) and inRow, useUnmergedTree = true).performTouchInput { click(center) }
                rule.waitForIdle()
            }
        }
        assertEquals(listOf(71, 71, 72, 72), opens)
        rule.onNodeWithTag(BankAlertSectionTags.toggle(71), useUnmergedTree = true).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("the switch never opens", listOf(71, 71, 72, 72), opens)
    }

    private companion object {
        val A = AuthIdentityFence("A", 1L)
        fun state(content: Content) = BankAlertSectionState(content, activeCount = 0, refreshSpinning = false, refreshEnabled = true)
        fun setting(id: Int, order: Int = 0, bank: String = "kb") = AlertSetting(
            id = id, userId = "A", bank = bank, currency = "usd-krw", condition = AlertCondition.BELOW, threshold = 1400.0 + id,
            isEnabled = true, triggered = false, createdAt = Instant.fromEpochSeconds(1_000L - order), updatedAt = Instant.fromEpochSeconds(0),
            triggeredAt = null, repeatIntervalSec = null
        )
    }
}
