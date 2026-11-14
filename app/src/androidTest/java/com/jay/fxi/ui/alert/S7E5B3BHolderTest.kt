package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.alerts.BankAlertSaveOutcome
import com.jay.fxi.data.alerts.BankAlertSaveRequest
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.NotSentReason
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b3b (s7e5b3_api_agreed.r1.md D5–D7): the real [BankAlertSection] over a fake source, under the helper's environment seam,
 * with the one sheet it opens. Adding runs the helper and opens only for the same identity while the snapshot still allows one;
 * editing opens without the helper; saving runs the helper again, checks the captured input once more against the latest list
 * and prices, then sends exactly one request and reads only that request's outcome. The click's OS read is real and GRANTED, so
 * the helper's continuation runs at once; this rig never opens a permission dialog or a settings screen.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B3BHolderTest {

    @get:Rule val compose = createComposeRule()

    private class Rig {
        var identity: AuthIdentityFence? = A
        val premium = MutableStateFlow(grantFor(A))
        val displayed = MutableStateFlow<NotificationCapability?>(NotificationCapability.GRANTED)
        val already = mutableListOf<AuthIdentityFence>()
        var duringHelper: () -> Unit = {}
        val env = NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { identity },
            premium = premium,
            displayed = displayed,
            onRequestResult = { false },
            onAlreadyGranted = { already += it; duringHelper(); true }
        )
    }

    private class FakeSource(state: BankAlertSettingsState) : BankAlertSectionSource {
        override val settings = MutableStateFlow(state)
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        val adds = mutableListOf<BankAlertDraft>()
        val edits = mutableListOf<Pair<Int, BankAlertEdit>>()
        val handles = mutableListOf<BankAlertSaveRequest>()
        var ensures = 0
        var authoritatives = 0
        var manuals = 0
        /** When set, the next requests come back already finished, as the owner's refusals do. */
        var immediate: BankAlertSaveOutcome? = null
        override fun ensure() { ensures += 1 }
        override fun authoritative() { authoritatives += 1 }
        override fun manual() { manuals += 1 }
        override fun setEnabled(id: Int, enabled: Boolean) = Unit
        override fun delete(id: Int) = Unit
        private fun request() = BankAlertSaveRequest().also { r -> immediate?.let { r.finish(it) }; handles += r }
        override fun add(draft: BankAlertDraft) = request().also { adds += draft }
        override fun edit(id: Int, edit: BankAlertEdit) = request().also { edits += id to edit }
    }

    private val tab = mutableStateOf(FreeTab.USD)
    private val quotes = mutableStateOf(mapOf("kb" to 1400.0, "hana" to 1500.0))

    private fun factory(source: FakeSource) = object : BankAlertSectionSourceFactory {
        override fun create(context: Context): BankAlertSectionSource = source
    }

    private fun show(rig: Rig, source: FakeSource) = compose.setContent {
        CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
            BankAlertSection(tab = tab.value, alertQuotes = quotes.value, sourceFactory = factory(source))
        }
    }.also { compose.waitForIdle() }

    private fun ready(vararg rows: AlertSetting, identity: AuthIdentityFence? = A) =
        BankAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), identity)

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
        (n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun finish(source: FakeSource, outcome: BankAlertSaveOutcome) = update { source.handles.last().finish(outcome) }
    private fun sheetOpen() = count(AlertAddSheetTags.SAVE) == 1
    /**
     * The save button's own click action, not a touch at its centre: a save right after typing a threshold sometimes missed the
     * button (2 of about 30 full-class runs, never when the test ran alone), so the tap lands by semantics. The sheet itself still
     * guards the click with canSave, so a disabled save sends nothing either way.
     */
    private fun save() {
        compose.onNodeWithTag(AlertAddSheetTags.SAVE, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }
    /** A node's click action, to call outside the frame that drew it. */
    private fun action(tag: String): () -> Unit {
        val click = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        return { click() }
    }

    @Before fun grantedDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("precondition: this device shows notifications, so the helper takes the granted path and opens nothing",
            NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    @Test fun S7E5B3B_H01_addingRunsTheHelperOpensTheAddSheetWithThePricesAndSendsOneDraft() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("the helper ran once for the owner", listOf(A), rig.already)
        assertEquals("알림 추가", textOf(AlertAddSheetTags.TITLE))
        assertEquals("the holder's prices reached the sheet", "1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        save()
        assertEquals(listOf(A, A), rig.already)
        assertEquals(listOf(BankAlertDraft("usd-krw", "kb", AlertCondition.BELOW, 1400.0, true, null)), source.adds)
        assertEquals("saving while the request is pending", 1, count(AlertAddSheetTags.SAVING))
        finish(source, BankAlertSaveOutcome.Saved(row(90)))
        assertTrue("saved closes the sheet", !sheetOpen())
    }

    @Test fun S7E5B3B_H02_thePromptRunsTheHelperAndOpensTheAddSheet() {
        val rig = Rig().apply { displayed.value = NotificationCapability.APP_DISABLED }
        val source = FakeSource(ready())
        show(rig, source)
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        assertEquals(listOf(A), rig.already)
        assertEquals("알림 추가", textOf(AlertAddSheetTags.TITLE))
        assertEquals(emptyList<BankAlertDraft>(), source.adds)
    }

    @Test fun S7E5B3B_H03_editingOpensWithoutTheHelperAndSendsOnlyTheChange() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        assertEquals("editing never asks", emptyList<AuthIdentityFence>(), rig.already)
        assertEquals("알림 수정", textOf(AlertAddSheetTags.TITLE))
        tap(AlertAddSheetTags.CONDITION)
        save()
        assertEquals(listOf(81 to BankAlertEdit(condition = AlertCondition.ABOVE)), source.edits)
        finish(source, BankAlertSaveOutcome.Removed)
        assertTrue("removed closes the sheet", !sheetOpen())
    }

    @Test fun S7E5B3B_H04_eachOutcomeShowsItsFixedCopyOrClosesAndNothingIsSentAgain() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        for ((outcome, copy) in listOf(
            BankAlertSaveOutcome.Failed(IllegalStateException("server words")) to BankAlertEditorCopy.FAILED,
            BankAlertSaveOutcome.NotSent(NotSentReason.CAP) to BankAlertEditorCopy.CAP,
            BankAlertSaveOutcome.NotSent(NotSentReason.NOT_READY) to BankAlertEditorCopy.NOT_SENT,
            BankAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED) to BankAlertEditorCopy.NOT_SENT,
            BankAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND) to BankAlertEditorCopy.NOT_SENT)) {
            val sent = source.adds.size
            save()
            assertEquals("$outcome: one more request", sent + 1, source.adds.size)
            assertEquals("$outcome: the new request is saving and shows no earlier copy", listOf(0, 1),
                listOf(count(AlertAddSheetTags.ERROR_DIALOG), count(AlertAddSheetTags.SAVING)))
            finish(source, outcome)
            assertEquals("$outcome", 1, texts(copy))
            assertEquals("$outcome: no server text", 0, texts("server words"))
            tap(AlertAddSheetTags.ERROR_CONFIRM)
            assertTrue("$outcome keeps the sheet", sheetOpen())
            assertEquals("$outcome: nothing sent by itself", sent + 1, source.adds.size)
        }
        val beforeSuperseded = source.adds.size
        save()
        assertEquals("superseded: one more request", beforeSuperseded + 1, source.adds.size)
        assertEquals("superseded: saving, no earlier copy", listOf(0, 1), listOf(count(AlertAddSheetTags.ERROR_DIALOG), count(AlertAddSheetTags.SAVING)))
        finish(source, BankAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED))
        val shownCopies = listOf(BankAlertEditorCopy.FAILED, BankAlertEditorCopy.CAP, BankAlertEditorCopy.NOT_SENT).filter { texts(it) > 0 }
        assertEquals("superseded shows nothing and stops saving (shown: $shownCopies)", listOf(0, 0, true), listOf(count(AlertAddSheetTags.ERROR_DIALOG),
            count(AlertAddSheetTags.SAVING), sheetOpen()))
        save()
        finish(source, BankAlertSaveOutcome.NotSent(NotSentReason.RESET))
        assertTrue("reset closes the sheet", !sheetOpen())
    }

    @Test fun S7E5B3B_H05_whilePendingNoSecondRequestAndDismissingLeavesTheRequestAlone() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        save()
        assertEquals("one request while pending", 1, source.adds.size)
        tap(AlertAddSheetTags.CANCEL)
        assertTrue(!sheetOpen())
        finish(source, BankAlertSaveOutcome.Failed(IllegalStateException()))
        assertEquals("a late outcome of a closed editor shows nothing", listOf(false, 0), listOf(sheetOpen(), count(AlertAddSheetTags.ERROR_DIALOG)))
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("a new editor starts clean", listOf(0, 0), listOf(count(AlertAddSheetTags.ERROR_DIALOG), count(AlertAddSheetTags.SAVING)))
    }

    @Test fun S7E5B3B_H06_anotherIdentityClosesTheEditorAndNoIdentityOpensNone() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertTrue(sheetOpen())
        update { source.settings.value = ready(row(81), identity = B) }
        assertTrue("another identity closes it", !sheetOpen())
        update { source.settings.value = ready(row(81), identity = null) }
        assertEquals("no identity: no add area", 0, count(BankAlertSectionTags.ADD_BUTTON))
    }

    @Test fun S7E5B3B_H07_theEditedRowGoingAwayClosesItsEditor() {
        val rig = Rig()
        val source = FakeSource(ready(row(81), row(82)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        update { source.settings.value = ready(row(82)) }
        assertTrue(!sheetOpen())
        assertEquals(emptyList<Pair<Int, BankAlertEdit>>(), source.edits)
    }

    @Test fun S7E5B3B_H08_theCapCountsEveryCurrencysIdsAndTheSheetShowsWhatRemains() {
        val rig = Rig()
        // One dollar row on screen; the rest are yen, so the button stays in view and the cap still counts every currency.
        val source = FakeSource(ready(*(1..30).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray()))
        show(rig, source)
        assertEquals("thirty across currencies: the cap line", listOf(0, 1), listOf(count(BankAlertSectionTags.ADD_BUTTON), count(BankAlertSectionTags.ADD_AT_CAP)))
        update { source.settings.value = ready(*(1..28).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray()) }
        assertEquals(1, texts("(2개 남음)"))
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("알림 2개 더 추가 가능", textOf(AlertAddSheetTags.REMAINING))
    }

    @Test fun S7E5B3B_H09_theSaveIsCheckedAgainstTheListAsItIsWhenTheHelperLetsItThrough() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        rig.duringHelper = { source.settings.value = ready(row(81), row(82, threshold = 1400.0)) }
        save()
        assertEquals("a duplicate that arrived meanwhile sends nothing", emptyList<BankAlertDraft>(), source.adds)
        assertEquals(1, texts(BankAlertEditorCopy.NOT_SENT))
        assertTrue(sheetOpen())
    }

    @Test fun S7E5B3B_H10_anotherTabEndsTheEditor() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertTrue(sheetOpen())
        update { tab.value = FreeTab.JPY }
        assertTrue("a new currency starts without an editor", !sheetOpen())
    }

    @Test fun S7E5B3B_H11_aSaveWhoseIdentityChangedWhileTheHelperRanSendsNothing() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        // The helper's own identity read stays A; only the holder's check of the source snapshot can stop this save.
        rig.duringHelper = { source.settings.value = ready(row(81), identity = B) }
        save()
        assertEquals("nothing sent for the identity the editor was opened under", emptyList<BankAlertDraft>(), source.adds)
        assertTrue("and that editor is gone", !sheetOpen())
    }

    @Test fun S7E5B3B_H12_aSaveForARowThatWentAwayWhileTheHelperRanSendsNothingAndShowsNoCopy() {
        val rig = Rig()
        val source = FakeSource(ready(row(81), row(82)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        tap(AlertAddSheetTags.CONDITION)
        rig.duringHelper = { source.settings.value = ready(row(82)) }
        save()
        assertEquals("no edit for a row that is gone", emptyList<Pair<Int, BankAlertEdit>>(), source.edits)
        assertEquals("not a refusal: no copy", 0, count(AlertAddSheetTags.ERROR_DIALOG))
        assertTrue("the editor closes", !sheetOpen())
    }

    @Test fun S7E5B3B_H13_twoSaveTapsBeforeTheScreenRedrawsSendOneRequest() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        // No frame between the taps (injected input advances the clock, so two clicks there would redraw in between): the save
        // button's click action twice in one main-thread turn, so only the holder's pending check can stop the second.
        val save = compose.onNodeWithTag(AlertAddSheetTags.SAVE, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { save(); save() }
        compose.waitForIdle()
        assertEquals("the second tap met a pending request", 1, source.adds.size)
        assertEquals("both taps reached the helper", listOf(A, A, A), rig.already)
    }

    @Test fun S7E5B3B_H14_withAnEditorOpenNeitherEntryReplacesIt() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        assertEquals("알림 수정", textOf(AlertAddSheetTags.TITLE))
        // Beneath the modal sheet a user cannot reach these; a test can, and the holder must still refuse a second editor.
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("the add entry kept the edit editor", "알림 수정", textOf(AlertAddSheetTags.TITLE))
        tap(AlertAddSheetTags.CANCEL)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("알림 추가", textOf(AlertAddSheetTags.TITLE))
        tap(BankAlertSectionTags.rowOpen(81))
        assertEquals("the row entry kept the add editor", "알림 추가", textOf(AlertAddSheetTags.TITLE))
        assertEquals(1, count(AlertAddSheetTags.SAVE))
    }

    @Test fun S7E5B3B_H15_theEditorTakesThePricesAsTheyAreWhenItOpens() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        update { quotes.value = mapOf("kb" to 1600.0, "hana" to 1500.0) }
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("the price that arrived after the section was drawn", "1600.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5B3B_H16_reachingTheCapWhileAddingShowsNoneRemainAndLeavesTheDecisionToTheOwner() {
        val rig = Rig()
        fun rows(n: Int) = ready(*(1..n).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray())
        val source = FakeSource(rows(29))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("알림 1개 더 추가 가능", textOf(AlertAddSheetTags.REMAINING))
        update { source.settings.value = rows(30) }
        assertTrue("the add editor stays at the cap", sheetOpen())
        assertEquals("알림을 더 추가할 수 없습니다", textOf(AlertAddSheetTags.REMAINING))
        save()
        assertEquals("the line does not block saving; the owner decides", 1, source.adds.size)
    }

    @Test fun S7E5B3B_H17_anAddWhoseSnapshotChangedWhileTheHelperRanOpensNothing() {
        val rig = Rig()
        fun rows(n: Int, identity: AuthIdentityFence? = A) =
            ready(*(1..n).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray(), identity = identity)
        val source = FakeSource(rows(29))
        show(rig, source)
        for ((label, next) in listOf("filled up" to rows(30), "no snapshot" to BankAlertSettingsState(AlertLoadPhase.Ready, null, A),
            "another identity" to rows(29, identity = B))) {
            rig.duringHelper = { source.settings.value = next }
            tap(BankAlertSectionTags.ADD_BUTTON)
            assertTrue("$label: no editor", !sheetOpen())
            rig.duringHelper = {}
            update { source.settings.value = rows(29) }
        }
    }

    @Test fun S7E5B3B_H18_theSheetSeesTheCurrentListForItsDuplicateWarning() {
        val rig = Rig()
        // The add sheet starts on kb, below, at kb's price 1400.00: the same as row 81 here.
        val source = FakeSource(ready(row(81, threshold = 1400.0)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("the sheet warns before any save", 1, count(AlertAddSheetTags.DUPLICATE))
        save()
        assertEquals(emptyList<BankAlertDraft>(), source.adds)
    }

    @Test fun S7E5B3B_H19_anEditEditorNeverShowsTheRemainingLine() {
        val rig = Rig()
        val source = FakeSource(ready(*(1..28).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray()))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(1))
        assertEquals("알림 수정", textOf(AlertAddSheetTags.TITLE))
        assertEquals("two remain, but editing adds nothing", 0, count(AlertAddSheetTags.REMAINING))
    }

    @Test fun S7E5B3B_H20_theSheetGetsEveryBanksPriceAndPricesThatArriveWhileItIsOpen() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        tap(AlertAddSheetTags.bank("hana"))
        assertEquals("hana's own price", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertTrue(textOf(AlertAddSheetTags.CURRENT_RATE), textOf(AlertAddSheetTags.CURRENT_RATE).contains(ExchangeRate.formatRate(1500.0, "usd-krw")))
        update { quotes.value = mapOf("kb" to 1400.0, "hana" to 1700.0) }
        assertTrue("a price that arrives while the sheet is open is shown",
            textOf(AlertAddSheetTags.CURRENT_RATE).contains(ExchangeRate.formatRate(1700.0, "usd-krw")))
        assertEquals("and leaves the input alone", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.CANCEL)
        update { tab.value = FreeTab.JPY; quotes.value = mapOf("kb" to 950.0) }
        update { source.settings.value = ready(row(81, currency = "jpy-krw")) }
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("the yen tab's map", "950.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5B3B_H21_editLooksUpTheRowInTheSnapshotAsItIsAtTheClick() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        val open = action(BankAlertSectionTags.rowOpen(81))
        // Same identity, same id, a new threshold, and the click before the screen redraws.
        compose.runOnUiThread { source.settings.value = ready(row(81, threshold = 1700.0)); open() }
        compose.waitForIdle()
        assertEquals("the latest row, not the drawn one", "1700.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5B3B_H22_editKeepsTheRowAsItWasWhenItOpened() {
        val off = row(81).apply { isEnabled = false }
        val rig = Rig()
        val source = FakeSource(ready(off))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        // The row object the editor came from changes afterwards; the editor's copy must not.
        compose.runOnIdle { off.isEnabled = true; off.triggered = true }
        tap(AlertAddSheetTags.CONDITION)
        save()
        assertEquals("editing an alert that was off turns it on", listOf(81 to BankAlertEdit(condition = AlertCondition.ABOVE, isEnabled = true)),
            source.edits)
    }

    @Test fun S7E5B3B_H23_editingIsOpenAtTheCap() {
        val rig = Rig()
        fun rows(n: Int) = ready(*(1..n).map { row(it, currency = if (it == 1) "usd-krw" else "jpy-krw") }.toTypedArray())
        val source = FakeSource(rows(30))
        show(rig, source)
        for (n in listOf(30, 31)) {
            update { source.settings.value = rows(n) }
            assertEquals("$n: the cap line", 1, count(BankAlertSectionTags.ADD_AT_CAP))
            tap(BankAlertSectionTags.rowOpen(1))
            assertEquals("$n: the editor opens without the helper", "알림 수정", textOf(AlertAddSheetTags.TITLE))
            assertEquals(0, count(AlertAddSheetTags.REMAINING))
            tap(AlertAddSheetTags.CONDITION)
            save()
            finish(source, BankAlertSaveOutcome.Saved(row(1)))
        }
        assertEquals("only the saves reached the helper", listOf(A, A), rig.already)
        assertEquals(2, source.edits.size)
        assertEquals(emptyList<BankAlertDraft>(), source.adds)
    }

    @Test fun S7E5B3B_H24_theClickedAddInputIsSentWholeAndSurvivesEveryRefusal() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        tap(AlertAddSheetTags.bank("hana"))
        tap(AlertAddSheetTags.CONDITION)
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1450.25")
        compose.waitForIdle()
        tap(AlertAddSheetTags.REPEAT)
        tap(AlertAddSheetTags.repeatOption(2))
        val shownBefore = listOf(AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT_LABEL).map(::textOf)
        val draft = BankAlertDraft("usd-krw", "hana", AlertCondition.ABOVE, 1450.25, true, RepeatInterval.FIVE_MINUTES)
        for (outcome in listOf(BankAlertSaveOutcome.Failed(IllegalStateException()), BankAlertSaveOutcome.NotSent(NotSentReason.CAP),
                BankAlertSaveOutcome.NotSent(NotSentReason.NOT_READY))) {
            save()
            finish(source, outcome)
            tap(AlertAddSheetTags.ERROR_CONFIRM)
            assertEquals("$outcome keeps the draft", shownBefore,
                listOf(AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT_LABEL).map(::textOf))
        }
        rig.duringHelper = { source.settings.value = ready(row(81), row(82, bank = "hana", condition = AlertCondition.ABOVE, threshold = 1450.25)) }
        save()
        rig.duringHelper = {}
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        assertEquals("a refused re-check keeps the draft too", shownBefore,
            listOf(AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT_LABEL).map(::textOf))
        assertEquals("every send carried the whole clicked input", listOf(draft, draft, draft), source.adds)
    }

    @Test fun S7E5B3B_H25_theSaveIsCheckedAgainstThePricesAndRowsAsTheyAreNow() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        // (a) kb 1400 when the editor opened (range 700–2100), 3000 now (range 1500–4500): 2500 is valid only now.
        tap(BankAlertSectionTags.ADD_BUTTON)
        update { quotes.value = mapOf("kb" to 3000.0, "hana" to 1500.0) }
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("2500")
        compose.waitForIdle()
        save()
        assertEquals("checked with the latest prices", listOf(2500.0), source.adds.map { it.threshold })
        finish(source, BankAlertSaveOutcome.Saved(row(90)))
        // (b) an edit whose duplicate went away after it opened, then one whose only twin is in another currency.
        update { quotes.value = mapOf("kb" to 1400.0, "hana" to 1500.0) }
        update { source.settings.value = ready(row(81), row(82, threshold = 1400.0)) }
        tap(BankAlertSectionTags.rowOpen(81))
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1400")
        compose.waitForIdle()
        assertEquals("premise: a duplicate while row 82 is there", 1, count(AlertAddSheetTags.DUPLICATE))
        update { source.settings.value = ready(row(81)) }
        save()
        assertEquals("checked with the latest rows", listOf(81 to BankAlertEdit(threshold = 1400.0)), source.edits)
        finish(source, BankAlertSaveOutcome.Saved(row(81)))
        update { source.settings.value = ready(row(81), row(83, currency = "jpy-krw", threshold = 1400.0)) }
        tap(BankAlertSectionTags.rowOpen(81))
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1400")
        compose.waitForIdle()
        save()
        assertEquals("a twin in another currency is no duplicate", 2, source.edits.size)
    }

    @Test fun S7E5B3B_H26_anEditorNeverComesBack() {
        val rig = Rig()
        val first = FakeSource(ready(row(81)))
        val second = FakeSource(ready(row(81)))
        val current = mutableStateOf(first)
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
                if (visible.value) BankAlertSection(tab = tab.value, alertQuotes = quotes.value, sourceFactory = factory(current.value))
            }
        }
        compose.waitForIdle()
        fun clean(label: String) {
            assertEquals("$label: nothing came back", listOf(false, 0, 0), listOf(sheetOpen(), count(AlertAddSheetTags.ERROR_DIALOG), count(AlertAddSheetTags.SAVING)))
            tap(BankAlertSectionTags.ADD_BUTTON)
            assertEquals("$label: a new click opens a clean editor", "1400.00", textOf(AlertAddSheetTags.THRESHOLD))
            tap(AlertAddSheetTags.CANCEL)
        }
        fun openWithDraft() {
            tap(BankAlertSectionTags.ADD_BUTTON)
            compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1450")
            compose.waitForIdle()
        }
        openWithDraft(); update { tab.value = FreeTab.JPY }; update { tab.value = FreeTab.USD }; clean("another currency and back")
        openWithDraft(); update { first.settings.value = ready(row(81), identity = B) }; update { first.settings.value = ready(row(81)) }
        clean("another identity and back")
        openWithDraft(); update { current.value = second }; update { current.value = first }; clean("another source and back")
        openWithDraft(); update { visible.value = false }; update { visible.value = true }; clean("the section gone and back")
        openWithDraft(); save()
        update { tab.value = FreeTab.JPY }; update { tab.value = FreeTab.USD }
        finish(first, BankAlertSaveOutcome.Failed(IllegalStateException()))
        clean("a pending request finishing after the editor ended")
        assertEquals("the request itself was never cancelled", BankAlertSaveOutcome.Failed::class, first.handles.single().outcome.value::class)
    }

    @Test fun S7E5B3B_H27_aRequestThatIsAlreadyFinishedTakesTheSamePath() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        for ((outcome, expect) in listOf(
            BankAlertSaveOutcome.Saved(row(90)) to "closed", BankAlertSaveOutcome.Removed to "closed",
            BankAlertSaveOutcome.Failed(IllegalStateException("server")) to BankAlertEditorCopy.FAILED,
            BankAlertSaveOutcome.NotSent(NotSentReason.CAP) to BankAlertEditorCopy.CAP,
            BankAlertSaveOutcome.NotSent(NotSentReason.NOT_READY) to BankAlertEditorCopy.NOT_SENT,
            BankAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED) to BankAlertEditorCopy.NOT_SENT,
            BankAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND) to BankAlertEditorCopy.NOT_SENT,
            BankAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED) to "open",
            BankAlertSaveOutcome.NotSent(NotSentReason.RESET) to "closed")) {
            for (edit in listOf(false, true)) {
                val label = "${if (edit) "edit" else "add"} $outcome"
                if (edit) { tap(BankAlertSectionTags.rowOpen(81)); tap(AlertAddSheetTags.CONDITION) } else tap(BankAlertSectionTags.ADD_BUTTON)
                val sent = source.adds.size + source.edits.size
                source.immediate = outcome
                save()
                source.immediate = null
                assertEquals("$label: one call", sent + 1, source.adds.size + source.edits.size)
                when (expect) {
                    "closed" -> assertTrue("$label closes", !sheetOpen())
                    "open" -> {
                        assertEquals("$label: open, not saving, no copy", listOf(true, 0, 0),
                            listOf(sheetOpen(), count(AlertAddSheetTags.SAVING), count(AlertAddSheetTags.ERROR_DIALOG)))
                        tap(AlertAddSheetTags.CANCEL)
                    }
                    else -> {
                        assertEquals("$label shows its copy", 1, texts(expect))
                        tap(AlertAddSheetTags.ERROR_CONFIRM)
                        assertTrue("$label keeps the sheet", sheetOpen())
                        tap(AlertAddSheetTags.CANCEL)
                    }
                }
                assertEquals("$label: nothing sent again", sent + 1, source.adds.size + source.edits.size)
            }
        }
    }

    @Test fun S7E5B3B_H28_onlyTheCurrentRequestIsReadAndOnlyOnce() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        for (old in listOf(BankAlertSaveOutcome.Failed(IllegalStateException()), BankAlertSaveOutcome.Saved(row(90)),
                BankAlertSaveOutcome.NotSent(NotSentReason.RESET))) {
            tap(BankAlertSectionTags.ADD_BUTTON)
            save()
            val e1 = source.handles.last()
            tap(AlertAddSheetTags.CANCEL)
            tap(BankAlertSectionTags.ADD_BUTTON)
            compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1420")
            compose.waitForIdle()
            save()
            val e2 = source.handles.last()
            update { e1.finish(old) }
            assertEquals("$old of the closed editor leaves the open one alone", listOf(true, 1, 0, "1420"),
                listOf(sheetOpen(), count(AlertAddSheetTags.SAVING), count(AlertAddSheetTags.ERROR_DIALOG), textOf(AlertAddSheetTags.THRESHOLD)))
            update { e2.finish(BankAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED)) }
            tap(AlertAddSheetTags.CANCEL)
        }
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        finish(source, BankAlertSaveOutcome.Failed(IllegalStateException()))
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        update { quotes.value = mapOf("kb" to 1401.0, "hana" to 1501.0) }
        update { source.settings.value = ready(row(81), row(85, currency = "jpy-krw")) }
        assertEquals("a read outcome is never read again", 0, count(AlertAddSheetTags.ERROR_DIALOG))
        assertEquals(7, source.adds.size)
    }

    @Test fun S7E5B3B_H29_theEditorAsksTheSourceForNothingButItsOneSave() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        val base = listOf(source.ensures, source.authoritatives, source.manuals)
        tap(BankAlertSectionTags.ADD_BUTTON); tap(AlertAddSheetTags.CANCEL)
        tap(BankAlertSectionTags.ADD_BUTTON); save(); finish(source, BankAlertSaveOutcome.Saved(row(90)))
        tap(BankAlertSectionTags.rowOpen(81)); tap(AlertAddSheetTags.CONDITION); save(); finish(source, BankAlertSaveOutcome.Removed)
        tap(BankAlertSectionTags.ADD_BUTTON); save(); finish(source, BankAlertSaveOutcome.Failed(IllegalStateException()))
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        update { quotes.value = mapOf("kb" to 1402.0, "hana" to 1502.0) }
        save(); finish(source, BankAlertSaveOutcome.NotSent(NotSentReason.RESET))
        assertEquals("no fetch, ensure or refresh from the editor", base, listOf(source.ensures, source.authoritatives, source.manuals))
        assertEquals(listOf(3, 1), listOf(source.adds.size, source.edits.size))
    }

    @Test fun S7E5B3B_H30_theHelperRunsInTheClickItself() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        val add = action(BankAlertSectionTags.ADD_BUTTON)
        var during = listOf<Int>()
        compose.runOnUiThread { add(); during = listOf(rig.already.size) }
        compose.waitForIdle()
        val save = action(AlertAddSheetTags.SAVE)
        compose.runOnUiThread { save(); during = during + rig.already.size + source.adds.size }
        assertEquals("the add click asked the helper before it returned; the save click asked and sent before it returned", listOf(1, 2, 1), during)
    }

    @Test fun S7E5B3B_H31_anEditSaveMeetsADuplicateThatArrivedWhileTheHelperRan() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1400")
        compose.waitForIdle()
        rig.duringHelper = { source.settings.value = ready(row(81), row(82, threshold = 1400.0)) }
        save()
        assertEquals("a same-currency twin that arrived meanwhile sends nothing", emptyList<Pair<Int, BankAlertEdit>>(), source.edits)
        assertEquals(1, texts(BankAlertEditorCopy.NOT_SENT))
        tap(AlertAddSheetTags.ERROR_CONFIRM)
        assertEquals("the draft stays", "1400", textOf(AlertAddSheetTags.THRESHOLD))
        update { source.settings.value = ready(row(81)) }
        assertEquals("premise: with the twin gone the sheet lets the save through again", 0, count(AlertAddSheetTags.DUPLICATE))
        rig.duringHelper = { source.settings.value = ready(row(81), row(83, currency = "jpy-krw", threshold = 1400.0)) }
        save()
        assertEquals("a twin in another currency is no duplicate", listOf(81 to BankAlertEdit(threshold = 1400.0)), source.edits)
    }

    @Test fun S7E5B3B_H32_anEditedRowThatMovesToAnotherCurrencyClosesItsEditor() {
        val rig = Rig()
        val source = FakeSource(ready(row(81), row(82)))
        show(rig, source)
        tap(BankAlertSectionTags.rowOpen(81))
        update { source.settings.value = ready(row(81, currency = "jpy-krw"), row(82)) }
        assertTrue("the id is only in another currency now", !sheetOpen())
        assertEquals(emptyList<Pair<Int, BankAlertEdit>>(), source.edits)
    }

    @Test fun S7E5B3B_H33_aRefusedEntryNeverInstallsAnEditorNotEvenForOneFrame() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        show(rig, source)
        val add = action(BankAlertSectionTags.ADD_BUTTON)
        val edit = action(BankAlertSectionTags.rowOpen(81))
        // One frame after the click and no more: an editor installed and then closed by the reactive rule is still drawn there, so
        // "not drawn" here means "never installed", not "closed afterwards".
        fun titlesAfterOneFrame(sameTurn: () -> Unit): Int {
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread(sameTurn)
                compose.mainClock.advanceTimeByFrame()
                return count(AlertAddSheetTags.TITLE)
            } finally {
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                rig.duringHelper = {}
                if (sheetOpen()) tap(AlertAddSheetTags.CANCEL)
                update { source.settings.value = ready(row(81)) }
            }
        }
        assertEquals("control: an accepted add is drawn within one frame", 1, titlesAfterOneFrame { add() })
        assertEquals("control: an accepted edit is drawn within one frame", 1, titlesAfterOneFrame { edit() })
        rig.duringHelper = { source.settings.value = ready(row(81), identity = B) }
        assertEquals("add: another identity by the continuation", 0, titlesAfterOneFrame { add() })
        for ((label, next) in listOf(
            "edit: the id only in another currency" to ready(row(81, currency = "jpy-krw")),
            "edit: the id gone" to ready(row(82)),
            "edit: another identity" to ready(row(81), identity = B),
            "edit: no identity" to ready(row(81), identity = null))) {
            assertEquals(label, 0, titlesAfterOneFrame { source.settings.value = next; edit() })
        }
    }

    private companion object {
        val A = AuthIdentityFence("A", 1L)
        val B = AuthIdentityFence("B", 1L)
        fun grantFor(identity: AuthIdentityFence) = OwnedPremiumAccess(identity.uid, identity.authGeneration, PremiumAccessState.PremiumConfirmed)
        fun row(id: Int, currency: String = "usd-krw", threshold: Double = 1300.0 + id, bank: String = "kb",
                condition: AlertCondition = AlertCondition.BELOW) = AlertSetting(
            id = id, userId = "A", bank = bank, currency = currency, condition = condition, threshold = threshold,
            isEnabled = true, triggered = false, createdAt = Instant.fromEpochSeconds(1_000L + id), updatedAt = Instant.fromEpochSeconds(0),
            triggeredAt = null, repeatIntervalSec = null
        )
    }
}
