package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import com.jay.fxi.ui.theme.StatusError
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertSectionState.Content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S7 E5a2 device contract (R4c/S7/s7e5 API r3 §5–§6): the bank row's switch and delete on the real [AlertSection],
 * and the holder's delegation through the real [BankAlertSection] over a fake source.
 * - The switch shows isEnabled and passes the target value, once per tap; the row itself is state-driven and not blocked.
 * - Delete opens one confirmation with iOS's copy; cancel fires nothing; confirm fires that row's delete once; a change of the
 *   rendered identity drops an open confirmation without firing.
 * - One row's controls never act for another row.
 * - The holder hands a switch tap and a confirmed delete to its source one to one, for the identity the frame rendered.
 * Every query walks the unmerged tree. The implementation thread reads but does not edit this file.
 */
class S7E5A2BankRowActionsTest {
    @get:Rule val rule = createComposeRule()

    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val identity = mutableStateOf<AuthIdentityFence?>(alice)
    private val shown = mutableStateOf(BankAlertSectionState(Content.Loading, 0, refreshSpinning = false, refreshEnabled = true))
    private val toggles = mutableListOf<Pair<Int, Boolean>>()
    private val deletes = mutableListOf<Int>()

    private fun setting(id: Int, order: Int = 0, enabled: Boolean = true, bank: String = "hana"): AlertSetting {
        val at = Instant.fromEpochSeconds(1_793_000_000L - order)
        return AlertSetting(id, "user-a", bank, SupportedCurrency.USD_KRW.code, AlertCondition.ABOVE, 1400.0 + id, enabled, false,
            at, at, null, null)
    }

    private fun rows(vararg settings: AlertSetting) =
        BankAlertSectionState(Content.Rows(settings.toList()), settings.count { it.isEnabled }, false, true)

    private fun show(initial: BankAlertSectionState) {
        shown.value = initial
        rule.setContent {
            AlertSection(
                currency = SupportedCurrency.USD_KRW,
                state = shown.value,
                onRetry = { },
                onRefresh = { },
                actionIdentity = identity.value,
                onToggle = { id, enabled -> toggles += id to enabled },
                onDelete = { id -> deletes += id },
                notificationsBlocked = false,
                onEnableNotifications = { }
            )
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    private fun roots(): Int = rule.onAllNodes(isRoot()).fetchSemanticsNodes().size
    private fun dialogTexts(): List<String> = rule.onAllNodes(
        hasTestTag(BankAlertSectionTags.DELETE_DIALOG) or hasAnyAncestor(hasTestTag(BankAlertSectionTags.DELETE_DIALOG)), useUnmergedTree = true
    ).fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun toggleState(id: Int): ToggleableState? =
        rule.onAllNodes(hasTestTag(BankAlertSectionTags.toggle(id)), useUnmergedTree = true).fetchSemanticsNodes()
            .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ToggleableState) }

    @Test fun s7e5a2_R01_theSwitchShowsIsEnabledAndPassesTheTargetOncePerTap() {
        show(rows(setting(61, order = 0, enabled = true), setting(62, order = 1, enabled = false)))
        assertEquals(listOf(ToggleableState.On, ToggleableState.Off), listOf(toggleState(61), toggleState(62)))
        rule.onNodeWithTag(BankAlertSectionTags.toggle(61), useUnmergedTree = true).assertIsEnabled()
        tap(BankAlertSectionTags.toggle(61))
        tap(BankAlertSectionTags.toggle(62))
        assertEquals("the target values, never an inversion of a stale state", listOf(61 to false, 62 to true), toggles)
        assertEquals("the row is state-driven: it waits for the owner", listOf(ToggleableState.On, ToggleableState.Off),
            listOf(toggleState(61), toggleState(62)))
        tap(BankAlertSectionTags.toggle(61))
        assertEquals("not blocked while a request may run", listOf(61 to false, 62 to true, 61 to false), toggles)
        assertEquals("no delete", emptyList<Int>(), deletes)
    }

    @Test fun s7e5a2_R02_deleteOpensOneConfirmationWithTheIosCopyAndCancelFiresNothing() {
        show(rows(setting(63)))
        assertEquals("premise: no dialog", 1, roots())
        tap(BankAlertSectionTags.delete(63))
        assertEquals("one dialog", 2, roots())
        val texts = dialogTexts()
        for (copy in listOf("알림 삭제", "'하나은행' 알림을 삭제하시겠습니까?", "삭제", "취소")) assertTrue("'$copy' in $texts", copy in texts)
        tap(BankAlertSectionTags.DELETE_CANCEL)
        assertEquals("closed", 1, roots())
        assertEquals("nothing fired", listOf(emptyList<Int>(), emptyList<Pair<Int, Boolean>>()), listOf(deletes, toggles))
    }

    @Test fun s7e5a2_R03_confirmFiresThatRowsDeleteOnceAndCloses() {
        show(rows(setting(64, order = 0), setting(65, order = 1)))
        tap(BankAlertSectionTags.delete(65))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals("closed", 1, roots())
        assertEquals("row 65 once", listOf(65), deletes)
        tap(BankAlertSectionTags.delete(64))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals(listOf(65, 64), deletes)
        assertEquals("no toggle", emptyList<Pair<Int, Boolean>>(), toggles)
    }

    @Test fun s7e5a2_R04_aChangedRenderedIdentityDropsAnOpenConfirmationWithoutFiring() {
        show(rows(setting(66)))
        tap(BankAlertSectionTags.delete(66))
        assertEquals("premise: open", 2, roots())
        rule.runOnIdle { identity.value = alice2 }
        rule.waitForIdle()
        assertEquals("dropped", 1, roots())
        assertEquals("nothing fired", emptyList<Int>(), deletes)
    }

    @Test fun s7e5a2_R05_oneRowsControlsNeverActForAnother() {
        show(rows(setting(67, order = 0), setting(68, order = 1), setting(69, order = 2, enabled = false)))
        tap(BankAlertSectionTags.toggle(68))
        tap(BankAlertSectionTags.delete(69))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals(listOf(listOf(68 to false), listOf(69)), listOf(toggles, deletes))
    }

    @Test fun s7e5a2_R08_backDismissesTheConfirmationWithoutFiring() {
        show(rows(setting(74)))
        tap(BankAlertSectionTags.delete(74))
        assertEquals("premise: open", 2, roots())
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        assertEquals("dismissed", 1, roots())
        assertEquals("nothing fired", emptyList<Int>(), deletes)
    }

    @Test fun s7e5a2_R09_aDisabledRowsDeleteButtonIsDrawnAsOpaqueAsAnEnabledRows() {
        show(rows(setting(75, order = 0, enabled = true), setting(76, order = 1, enabled = false)))
        fun pixels(id: Int) = rule.onNodeWithTag(BankAlertSectionTags.delete(id), useUnmergedTree = true).captureToImage().toPixelMap()
        val on = pixels(75)
        val off = pixels(76)
        assertEquals("premise: same size", listOf(on.width, on.height), listOf(off.width, off.height))
        fun distance(a: Int, b: Int): Int = maxOf(
            kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        )
        /** The most common colour (the background) and the largest distance of any pixel from it: how strongly the icon is drawn. */
        fun reach(map: androidx.compose.ui.graphics.PixelMap): Pair<Int, Int> {
            val counts = HashMap<Int, Int>()
            for (y in 0 until map.height) for (x in 0 until map.width) {
                val argb = map[x, y].toArgb()
                counts[argb] = (counts[argb] ?: 0) + 1
            }
            val background = counts.entries.maxBy { it.value }.key
            var most = 0
            for (y in 0 until map.height) for (x in 0 until map.width) most = maxOf(most, distance(map[x, y].toArgb(), background))
            return background to most
        }
        val (onBackground, onReach) = reach(on)
        val (offBackground, offReach) = reach(off)
        assertTrue("premise: same background", distance(onBackground, offBackground) <= 2)
        assertTrue("premise: the icon is drawn (reach $onReach)", onReach >= 64)
        val ratio = offReach.toDouble() / onReach
        assertTrue("the delete button is not dimmed with the row's content (iOS: always clear): $offReach / $onReach = $ratio",
            ratio >= 0.9)
    }

    @Test fun s7e5a2_R10_theConfirmationNamesTheRowsOwnBankAndFallsBackToItsCode() {
        val kb = setting(77, order = 0, bank = "kb")
        show(rows(kb, setting(78, order = 1, bank = "zz-unknown")))
        tap(BankAlertSectionTags.delete(77))
        assertTrue(dialogTexts().toString(), "'${kb.bankType!!.displayName}' 알림을 삭제하시겠습니까?" in dialogTexts())
        tap(BankAlertSectionTags.DELETE_CANCEL)
        tap(BankAlertSectionTags.delete(78))
        assertTrue(dialogTexts().toString(), "'zz-unknown' 알림을 삭제하시겠습니까?" in dialogTexts())
        tap(BankAlertSectionTags.DELETE_CANCEL)
    }

    @Test fun s7e5a2_R11_theConfirmButtonIsDrawnInTheErrorColourAsIosDrawsADestructiveAction() {
        show(rows(setting(79)))
        tap(BankAlertSectionTags.delete(79))
        val node = rule.onAllNodes(hasText("삭제") and hasAnyAncestor(hasTestTag(BankAlertSectionTags.DELETE_CONFIRM)), useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue("premise: the label lays out", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertEquals(StatusError, layouts.single().layoutInput.style.color)
        tap(BankAlertSectionTags.DELETE_CANCEL)
    }

    @Test fun s7e5a2_R12_theDeleteButtonIsDrawnInTheErrorColourAsIosDrawsItsRedTrash() {
        show(rows(setting(80)))
        val map = rule.onNodeWithTag(BankAlertSectionTags.delete(80), useUnmergedTree = true).captureToImage().toPixelMap()
        val counts = HashMap<Int, Int>()
        for (y in 0 until map.height) for (x in 0 until map.width) counts.merge(map[x, y].toArgb(), 1, Int::plus)
        val background = counts.entries.maxBy { it.value }.key
        fun far(a: Int) = maxOf(kotlin.math.abs((a shr 16 and 0xFF) - (background shr 16 and 0xFF)),
            kotlin.math.abs((a shr 8 and 0xFF) - (background shr 8 and 0xFF)), kotlin.math.abs((a and 0xFF) - (background and 0xFF)))
        var ink = background
        for (y in 0 until map.height) for (x in 0 until map.width) { val a = map[x, y].toArgb(); if (far(a) > far(ink)) ink = a }
        val r = ink shr 16 and 0xFF; val g = ink shr 8 and 0xFF; val b = ink and 0xFF
        assertTrue("the icon's strongest ink is red (r=$r g=$g b=$b)", r - maxOf(g, b) >= 60)
    }

    // ---- the holder ----

    private class FakeSource(state: BankAlertSettingsState) : BankAlertSectionSource {
        override val settings = MutableStateFlow(state)
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        val calls = mutableListOf<String>()
        override fun ensure() = Unit
        override fun authoritative() = Unit
        override fun manual() = Unit
        override fun setEnabled(id: Int, enabled: Boolean) {
            calls += "setEnabled $id $enabled"
        }
        override fun delete(id: Int) {
            calls += "delete $id"
        }
    }

    @Test fun s7e5a2_R06_theHolderHandsASwitchTapAndAConfirmedDeleteToItsSourceOneToOne() {
        val source = FakeSource(BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(71, order = 0), setting(72, order = 1, enabled = false)), alice))
        rule.setContent { BankAlertSection(tab = FreeTab.USD, sourceFactory = object : BankAlertSectionSourceFactory {
            override fun create(context: Context): BankAlertSectionSource = source
        }) }
        rule.waitForIdle()
        tap(BankAlertSectionTags.toggle(71))
        tap(BankAlertSectionTags.toggle(72))
        tap(BankAlertSectionTags.delete(71))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals(listOf("setEnabled 71 false", "setEnabled 72 true", "delete 71"), source.calls)
    }

    @Test fun s7e5a2_R07_theHolderSendsNothingForASnapshotWithoutAnIdentity() {
        val source = FakeSource(BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(73)), null))
        rule.setContent { BankAlertSection(tab = FreeTab.USD, sourceFactory = object : BankAlertSectionSourceFactory {
            override fun create(context: Context): BankAlertSectionSource = source
        }) }
        rule.waitForIdle()
        tap(BankAlertSectionTags.toggle(73))
        tap(BankAlertSectionTags.delete(73))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals(emptyList<String>(), source.calls)
    }
}
