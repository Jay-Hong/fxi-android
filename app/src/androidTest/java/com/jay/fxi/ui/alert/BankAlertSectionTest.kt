package com.jay.fxi.ui.alert

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertSectionState.Content
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.StatusConnecting
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): §5 row 4 on a device — the real read-only [AlertSection] driven only
 * through its agreed signature (currency, [BankAlertSectionState], onRetry, onRefresh) and located only through
 * [BankAlertSectionTags]. It locks the four bodies (Loading, Error with retry, Empty, Rows) and that exactly one shows; the
 * active-count badge (>0 only, follows the state, stays in the folded header); the static row labels (triggered → "알림 발송됨"
 * whatever the repeat, null repeat → no label, known → RepeatInterval.everyLabel, unknown → "반복 알림"), bank name,
 * condition and two-decimal threshold; an enabled and a disabled row that differ for accessibility as well, and never change;
 * inside the rows subtree no add, edit, selection, custom or click action other than each row's own switch and delete button
 * (S7 E5a2; their behaviour is S7E5A2BankRowActionsTest's), no dialog or popup after a tap, long-press or swipe on a row's
 * content and no mutation of the given settings; the header folds and unfolds, stays folded across a
 * state update, and has no action other than refresh and folding; the refresh button's enabled and spinner states, and that
 * refresh and retry fire their callback once per tap when enabled and the disabled refresh fires none. Every query walks the
 * unmerged tree so that a merge choice of the implementation does not decide the outcome, except D16's, which reads what the
 * merged row node announces. The implementation thread reads but does not edit this file.
 */
class BankAlertSectionTest {
    @get:Rule val rule = createComposeRule()

    private val shown = mutableStateOf(BankAlertSectionState(Content.Loading, 0, refreshSpinning = false, refreshEnabled = true))
    private val retries = AtomicInteger()
    private val refreshes = AtomicInteger()

    // ---- fixture ----

    /** [order] 0 is the newest: a list in ascending order is already in the holder's createdAt-descending order. */
    private fun setting(
        id: Int,
        order: Int = 0,
        bank: String = "hana",
        condition: AlertCondition = AlertCondition.ABOVE,
        threshold: Double = 1400.0 + id,
        enabled: Boolean = true,
        triggered: Boolean = false,
        repeat: Int? = null
    ): AlertSetting {
        val at = Instant.fromEpochSeconds(1_793_000_000L - order)
        return AlertSetting(
            id = id, userId = "uid-e5a1", bank = bank, currency = SupportedCurrency.USD_KRW.code, condition = condition,
            threshold = threshold, isEnabled = enabled, triggered = triggered, createdAt = at, updatedAt = at,
            triggeredAt = if (triggered) at else null, repeatIntervalSec = repeat
        )
    }

    /** A consistent rows state: the badge is the number of enabled rows. */
    private fun rows(vararg settings: AlertSetting, spinning: Boolean = false, refreshEnabled: Boolean = true) =
        BankAlertSectionState(Content.Rows(settings.toList()), settings.count { it.isEnabled }, spinning, refreshEnabled)

    private fun body(content: Content, spinning: Boolean = false, refreshEnabled: Boolean = true) =
        BankAlertSectionState(content, 0, spinning, refreshEnabled)

    // ---- host ----

    private fun show(initial: BankAlertSectionState) {
        shown.value = initial
        rule.setContent {
            AlertSection(
                currency = SupportedCurrency.USD_KRW,
                state = shown.value,
                onRetry = { retries.incrementAndGet() },
                onRefresh = { refreshes.incrementAndGet() },
                actionIdentity = null,
                onToggle = { _, _ -> },
                onDelete = { },
                notificationsBlocked = false,
                onEnableNotifications = { }
            )
        }
        rule.waitForIdle()
    }

    /** The same composition receives a new state, as the holder's collected flows would deliver it. */
    private fun update(next: BankAlertSectionState) {
        rule.runOnIdle { shown.value = next }
        rule.waitForIdle()
    }

    private fun tap(node: SemanticsNodeInteraction) {
        node.performClick()
        rule.waitForIdle()
    }

    // ---- semantics helpers (unmerged tree throughout) ----

    private fun inSubtree(tag: String): SemanticsMatcher = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    private fun countOf(matcher: SemanticsMatcher): Int = nodes(matcher).size
    private fun subtree(tag: String): List<SemanticsNode> = nodes(inSubtree(tag))
    private fun texts(tag: String): List<String> =
        subtree(tag).flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    private fun rowTexts(id: Int): List<String> = texts(BankAlertSectionTags.row(id))
    private fun rowShows(id: Int, fragment: String): Boolean = rowTexts(id).any { fragment in it }

    /** The one node carrying a click action at or under [tag] (the action itself, wherever the tag sits on it). */
    private fun clickableIn(tag: String): SemanticsNodeInteraction =
        rule.onNode(hasClickAction() and inSubtree(tag), useUnmergedTree = true)

    private val spinner: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private fun refreshSpinners(): Int = countOf(spinner and inSubtree(BankAlertSectionTags.REFRESH))

    /** Everything a reader of a node hears or sees. */
    private fun words(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.StateDescription)) +
            listOfNotNull(n.config.getOrNull(SemanticsActions.OnClick)?.label)

    /** The accessibility-visible content of a row's subtree, independent of how the implementation merges it. */
    private fun accessibility(id: Int): List<String> = subtree(BankAlertSectionTags.row(id)).flatMap { n ->
        buildList {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { add("text=${it.text}") }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add("desc=$it") }
            n.config.getOrNull(SemanticsProperties.StateDescription)?.let { add("state=$it") }
            if (SemanticsProperties.Disabled in n.config) add("disabled")
            n.config.getOrNull(SemanticsProperties.Selected)?.let { add("selected=$it") }
            n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { add("toggle=$it") }
        }
    }.sorted()

    private val bodies = listOf(
        BankAlertSectionTags.LOADING, BankAlertSectionTags.ERROR, BankAlertSectionTags.EMPTY, BankAlertSectionTags.ROWS
    )

    /** The header is there in every state, folded or not: the section root, its title, refresh and the fold control. */
    private fun assertHeader() {
        rule.onNodeWithTag(BankAlertSectionTags.ROOT, useUnmergedTree = true).assertIsDisplayed()
        assertTrue("the header title", countOf(hasText(TITLE) and inSubtree(BankAlertSectionTags.ROOT)) >= 1)
        clickableIn(BankAlertSectionTags.REFRESH).assertExists()
        clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED).assertExists()
    }

    /** Exactly [expected] of the four bodies is shown (null: folded, none). Call after [assertHeader] as the premise. */
    private fun assertBody(expected: String?) {
        bodies.forEach { tag ->
            val count = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            if (tag == expected) assertEquals("$tag is the body", 1, count) else assertEquals("$tag is not shown", 0, count)
        }
        if (expected != null) rule.onNodeWithTag(expected, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertCallbacks(retry: Int, refresh: Int) {
        assertEquals("onRetry calls", retry, retries.get())
        assertEquals("onRefresh calls", refresh, refreshes.get())
    }

    // ---- bodies ----

    @Test
    fun s7e5a1_D01_loadingShowsTheLoadingViewAndNoOtherBody() {
        show(body(Content.Loading))

        assertHeader()
        assertBody(BankAlertSectionTags.LOADING)
        assertTrue("loading copy", texts(BankAlertSectionTags.LOADING).contains(LOADING_TEXT))
        assertEquals("no retry while loading", 0, countOf(hasTestTag(BankAlertSectionTags.RETRY)))
        assertCallbacks(retry = 0, refresh = 0)
    }

    @Test
    fun s7e5a1_D02_errorShowsItsGivenCopyAndRetryFiresOncePerTap() {
        show(body(Content.Error(NETWORK_COPY)))

        assertHeader()
        assertBody(BankAlertSectionTags.ERROR)
        assertTrue("the given fixed copy", texts(BankAlertSectionTags.ERROR).contains(NETWORK_COPY))
        assertEquals(
            "retry belongs to the error card", 1,
            countOf(hasTestTag(BankAlertSectionTags.RETRY) and hasAnyAncestor(hasTestTag(BankAlertSectionTags.ERROR)))
        )
        clickableIn(BankAlertSectionTags.RETRY).assertIsEnabled()

        tap(clickableIn(BankAlertSectionTags.RETRY))
        assertCallbacks(retry = 1, refresh = 0)
        tap(clickableIn(BankAlertSectionTags.RETRY))
        assertCallbacks(retry = 2, refresh = 0)

        // The card shows whatever copy the state now carries, not a remembered one.
        update(body(Content.Error(LOGIN_COPY)))
        assertBody(BankAlertSectionTags.ERROR)
        assertTrue(texts(BankAlertSectionTags.ERROR).contains(LOGIN_COPY))
        assertFalse(texts(BankAlertSectionTags.ERROR).contains(NETWORK_COPY))

        // Retry is not tied to the refresh button: with refresh disabled and spinning, a retry tap still fires once.
        update(body(Content.Error(LOGIN_COPY), spinning = true, refreshEnabled = false))
        clickableIn(BankAlertSectionTags.REFRESH).assertIsNotEnabled()
        clickableIn(BankAlertSectionTags.RETRY).assertIsEnabled()
        tap(clickableIn(BankAlertSectionTags.RETRY))
        assertCallbacks(retry = 3, refresh = 0)
    }

    @Test
    fun s7e5a1_D03_emptyShowsTheEmptyStateAndNoRow() {
        show(body(Content.Empty))

        assertHeader()
        assertBody(BankAlertSectionTags.EMPTY)
        assertTrue("empty copy", texts(BankAlertSectionTags.EMPTY).contains(EMPTY_TEXT))
        assertEquals("no retry when empty", 0, countOf(hasTestTag(BankAlertSectionTags.RETRY)))
        assertCallbacks(retry = 0, refresh = 0)
    }

    @Test
    fun s7e5a1_D04_rowsShowEveryGivenRowOnceInsideTheRowsBodyInTheGivenOrder() {
        // Already in the holder's createdAt-descending order; ids deliberately not monotonic.
        val given = listOf(setting(7, order = 0), setting(2, order = 1), setting(5, order = 2))
        show(rows(*given.toTypedArray()))

        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("no retry with rows", 0, countOf(hasTestTag(BankAlertSectionTags.RETRY)))
        val tops = given.map { s ->
            val found = nodes(hasTestTag(BankAlertSectionTags.row(s.id)))
            assertEquals("row ${s.id} once", 1, found.size)
            assertEquals(
                "row ${s.id} inside the rows body", 1,
                countOf(hasTestTag(BankAlertSectionTags.row(s.id)) and hasAnyAncestor(hasTestTag(BankAlertSectionTags.ROWS)))
            )
            found.single().boundsInRoot.top
        }
        assertEquals("the view keeps the order it is given", tops.sorted(), tops)
        assertTrue("distinct positions", tops.toSet().size == tops.size)
    }

    @Test
    fun s7e5a1_D05_aStateChangeReplacesTheBodyInTheSameComposition() {
        show(body(Content.Loading))
        assertHeader()
        assertBody(BankAlertSectionTags.LOADING)

        update(rows(setting(1)))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals(1, countOf(hasTestTag(BankAlertSectionTags.row(1))))

        update(body(Content.Empty))
        assertHeader()
        assertBody(BankAlertSectionTags.EMPTY)
        assertEquals("the old row is gone", 0, countOf(hasTestTag(BankAlertSectionTags.row(1))))

        update(body(Content.Error(GENERIC_COPY)))
        assertHeader()
        assertBody(BankAlertSectionTags.ERROR)

        update(body(Content.Loading))
        assertHeader()
        assertBody(BankAlertSectionTags.LOADING)
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- badge ----

    @Test
    fun s7e5a1_D06_theBadgeShowsAPositiveActiveCountOnlyAndFollowsTheState() {
        val on1 = setting(11, order = 0)
        val on2 = setting(12, order = 1)
        val on3 = setting(13, order = 2)
        val off = setting(14, order = 3, enabled = false)
        show(rows(on1, on2, on3, off))
        fun badge(n: Int) = countOf(
            hasText("$n") and inSubtree(BankAlertSectionTags.ROOT) and !inSubtree(BankAlertSectionTags.ROWS)
        )

        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("the badge carries the active count", 1, badge(3))

        // A folded header keeps the badge.
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(null)
        assertEquals(1, badge(3))
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertBody(BankAlertSectionTags.ROWS)

        // One fewer enabled row: the badge follows.
        update(rows(on1, on2, off))
        assertHeader()
        assertEquals(1, badge(2))
        assertEquals("the old count is gone", 0, badge(3))

        // Zero active: rows are still shown, but no badge.
        update(rows(on1.copy(isEnabled = false), on2.copy(isEnabled = false), off))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals(1, countOf(hasTestTag(BankAlertSectionTags.row(11))))
        assertEquals("no zero badge", 0, countOf(hasText("0") and inSubtree(BankAlertSectionTags.ROOT)))
        assertEquals("no stale badge", 0, badge(2))
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- static row labels ----

    @Test
    fun s7e5a1_D07_rowStatusLabelsFollowTriggeredThenTheRawRepeatValue() {
        val unknownSeconds = 123
        assertNull("premise: 123 is not a known interval", RepeatInterval.from(unknownSeconds))
        val known = RepeatInterval.FIVE_MINUTES
        show(
            rows(
                setting(1, order = 0, repeat = null),
                setting(2, order = 1, repeat = known.seconds),
                setting(3, order = 2, repeat = unknownSeconds),
                setting(4, order = 3, triggered = true, repeat = null),
                setting(5, order = 4, triggered = true, repeat = known.seconds),
                setting(6, order = 5, triggered = true, repeat = unknownSeconds)
            )
        )
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        (1..6).forEach { id -> assertTrue("premise: row $id is drawn with its bank", rowShows(id, "하나은행")) }

        // null repeat, not triggered: no label at all (no "1회" either).
        assertFalse(rowShows(1, SENT_LABEL))
        assertFalse(rowShows(1, UNKNOWN_REPEAT_LABEL))
        assertFalse(rowShows(1, "마다"))
        assertFalse(rowShows(1, "1회"))

        // Known repeat: its every-label.
        assertTrue(rowTexts(2).contains(known.everyLabel))
        assertFalse(rowShows(2, UNKNOWN_REPEAT_LABEL))
        assertFalse(rowShows(2, SENT_LABEL))

        // Unknown non-null repeat: the generic repeat label, never a once/null rendering or a guessed interval.
        assertTrue(rowTexts(3).contains(UNKNOWN_REPEAT_LABEL))
        assertFalse(rowShows(3, "마다"))
        assertFalse(rowShows(3, SENT_LABEL))
        assertFalse(rowShows(3, "1회"))

        // Triggered wins over every repeat value.
        listOf(4, 5, 6).forEach { id ->
            assertTrue("row $id sent", rowTexts(id).contains(SENT_LABEL))
            assertFalse("row $id has no repeat label", rowShows(id, "마다"))
            assertFalse("row $id has no generic repeat label", rowShows(id, UNKNOWN_REPEAT_LABEL))
        }
    }

    @Test
    fun s7e5a1_D08_everyKnownIntervalShowsItsEveryLabel() {
        show(rows(setting(30, repeat = RepeatInterval.entries.first().seconds)))
        assertHeader()
        RepeatInterval.entries.forEach { interval ->
            update(rows(setting(30, repeat = interval.seconds)))
            assertBody(BankAlertSectionTags.ROWS)
            assertTrue("premise: the row is drawn", rowShows(30, "하나은행"))
            assertTrue("${interval.name} label", rowTexts(30).contains(interval.everyLabel))
            assertFalse(rowShows(30, UNKNOWN_REPEAT_LABEL))
            assertFalse(rowShows(30, SENT_LABEL))
            RepeatInterval.entries.filter { it != interval }.forEach { other ->
                assertFalse("${interval.name} is not ${other.name}", rowTexts(30).contains(other.everyLabel))
            }
        }
    }

    @Test
    fun s7e5a1_D09_rowsShowBankNameConditionAndTwoDecimalThreshold() {
        show(
            rows(
                setting(91, order = 0, bank = "hana", condition = AlertCondition.ABOVE, threshold = 1400.5),
                setting(92, order = 1, bank = "zzbank", condition = AlertCondition.BELOW, threshold = 1388.0)
            )
        )
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)

        assertTrue("known bank: its display name", rowShows(91, "하나은행"))
        assertFalse("not the raw code", rowTexts(91).contains("hana"))
        assertTrue(rowShows(91, "1400.50"))
        assertTrue(rowShows(91, "△"))
        assertTrue(rowShows(91, "이상"))
        assertFalse(rowShows(91, "▽"))
        assertFalse(rowShows(91, "이하"))

        assertTrue("unknown bank: the raw code", rowShows(92, "zzbank"))
        assertTrue(rowShows(92, "1388.00"))
        assertTrue(rowShows(92, "▽"))
        assertTrue(rowShows(92, "이하"))
        assertFalse(rowShows(92, "△"))
        assertFalse(rowShows(92, "이상"))
    }

    @Test
    fun s7e5a1_D10_enabledAndDisabledRowsDifferForAccessibilityAndNeverChange() {
        // Identical except id and isEnabled.
        val on = setting(81, order = 0, threshold = 1400.0, enabled = true)
        val off = setting(82, order = 1, threshold = 1400.0, enabled = false)
        val given = listOf(on, off)
        val before = given.map { it.copy() }
        show(BankAlertSectionState(Content.Rows(given), activeCount = 1, refreshSpinning = false, refreshEnabled = true))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)

        val onA11y = accessibility(81)
        val offA11y = accessibility(82)
        assertTrue("premise: comparable rows", onA11y.contains("text=하나은행") && offA11y.contains("text=하나은행"))
        assertNotEquals("enabled and disabled are told apart for accessibility, not only by opacity", onA11y, offA11y)
        assertEquals("premise: no dialog or popup", 1, countOf(isRoot()))

        listOf(81, 82).forEach { id ->
            val node = rule.onNodeWithTag(BankAlertSectionTags.row(id), useUnmergedTree = true)
            node.assertIsDisplayed()
            node.performClick()
            rule.waitForIdle()
            node.performTouchInput { longClick() }
            rule.waitForIdle()
            // Inset from the edges so a system back gesture is not what is exercised.
            node.performTouchInput { swipeLeft(startX = centerX + width / 4f, endX = centerX - width / 4f) }
            rule.waitForIdle()
        }

        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("nothing opened from a row", 1, countOf(isRoot()))
        assertEquals("the enabled row is unchanged", onA11y, accessibility(81))
        assertEquals("the disabled row is unchanged", offA11y, accessibility(82))
        assertEquals("the given settings are not mutated", before, given)
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- read-only boundary ----

    @Test
    fun s7e5a1_D11_theRowsSubtreeHasNoAddEditOrClickActionBesidesEachRowsSwitchAndDeleteButton() {
        show(
            rows(
                setting(41, order = 0, enabled = true, repeat = RepeatInterval.FIVE_MINUTES.seconds),
                setting(42, order = 1, enabled = false),
                setting(43, order = 2, enabled = true, triggered = true, repeat = 123, condition = AlertCondition.BELOW)
            )
        )
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        listOf(41, 42, 43).forEach { id ->
            assertEquals(
                "premise: row $id is inside the rows body", 1,
                countOf(hasTestTag(BankAlertSectionTags.row(id)) and hasAnyAncestor(hasTestTag(BankAlertSectionTags.ROWS)))
            )
        }
        val inside = subtree(BankAlertSectionTags.ROWS)
        assertTrue("premise: the rows subtree has its rows and their texts", inside.size > 3)
        // S7 E5a2: each row's one switch and one delete button are the only controls; everything else stays inert.
        listOf(41, 42, 43).forEach { id ->
            val toggles = subtree(BankAlertSectionTags.toggle(id)).filter { SemanticsProperties.ToggleableState in it.config }
            assertEquals("row $id has one switch", 1, toggles.size)
            val deletes = subtree(BankAlertSectionTags.delete(id)).filter { SemanticsActions.OnClick in it.config }
            assertEquals("row $id has one delete button", 1, deletes.size)
            assertTrue("its delete button says so", subtree(BankAlertSectionTags.delete(id)).any { n -> words(n).any { "삭제" in it } })
        }
        val controls = listOf(41, 42, 43).flatMap { subtree(BankAlertSectionTags.toggle(it)) + subtree(BankAlertSectionTags.delete(it)) }
            .map { it.id }.toSet()

        inside.filterNot { it.id in controls }.forEach { n ->
            val c = n.config
            assertFalse("no click action in a row", SemanticsActions.OnClick in c)
            assertFalse("no long-click action in a row", SemanticsActions.OnLongClick in c)
            assertFalse("no custom (swipe/delete) action in a row", SemanticsActions.CustomActions in c)
            assertFalse("no switch/checkbox state in a row", SemanticsProperties.ToggleableState in c)
            assertFalse("no selectable row", SemanticsProperties.Selected in c)
            assertFalse("no edit field in a row", SemanticsProperties.EditableText in c)
            c.getOrNull(SemanticsProperties.Role)?.let { assertEquals("only an image role may appear in a row", Role.Image, it) }
            words(n).forEach { w ->
                ROW_FORBIDDEN.forEach { f -> assertFalse("'$f' in a row: $w", f in w) }
            }
        }
    }

    @Test
    fun s7e5a1_D12_theSectionsOnlyActionsAreRefreshFoldingAndTheRowControls() {
        show(rows(setting(51, order = 0), setting(52, order = 1, enabled = false)))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("premise: no dialog or popup", 1, countOf(isRoot()))

        val section = subtree(BankAlertSectionTags.ROOT)
        assertTrue("premise: the section has nodes", section.isNotEmpty())
        // S7 E5a2: the row controls are S7E5A2BankRowActionsTest's; here they are only kept out of the inert scan.
        val controls = listOf(51, 52).flatMap { subtree(BankAlertSectionTags.toggle(it)) + subtree(BankAlertSectionTags.delete(it)) }
            .map { it.id }.toSet()
        assertTrue("premise: the row controls are found", controls.isNotEmpty())
        section.filterNot { it.id in controls }.forEach { n ->
            assertFalse("no switch in the section", SemanticsProperties.ToggleableState in n.config)
            assertFalse("no edit field in the section", SemanticsProperties.EditableText in n.config)
            words(n).forEach { w -> SECTION_FORBIDDEN.forEach { f -> assertFalse("'$f' in the section: $w", f in w) } }
        }

        // Any clickable besides refresh and the fold control may only fold and unfold (the header); nothing else fires.
        val others = hasClickAction() and inSubtree(BankAlertSectionTags.ROOT) and
            !inSubtree(BankAlertSectionTags.REFRESH) and !inSubtree(BankAlertSectionTags.TOGGLE_EXPANDED) and
            !inSubtree(BankAlertSectionTags.toggle(51)) and !inSubtree(BankAlertSectionTags.toggle(52)) and
            !inSubtree(BankAlertSectionTags.delete(51)) and !inSubtree(BankAlertSectionTags.delete(52))
        val count = countOf(others)
        for (i in 0 until count) {
            tap(rule.onAllNodes(others, useUnmergedTree = true)[i])
            assertHeader()
            assertBody(null)
            assertEquals(1, countOf(isRoot()))
            assertCallbacks(retry = 0, refresh = 0)
            tap(rule.onAllNodes(others, useUnmergedTree = true)[i])
            assertHeader()
            assertBody(BankAlertSectionTags.ROWS)
        }
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- folding ----

    @Test
    fun s7e5a1_D13_theHeaderFoldsAndUnfoldsAndStaysFoldedAcrossAStateUpdate() {
        show(rows(setting(61, order = 0), setting(62, order = 1)))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)

        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(null)
        assertEquals(0, countOf(hasTestTag(BankAlertSectionTags.row(61))))
        assertCallbacks(retry = 0, refresh = 0)

        // A new state recomposes the section; the fold is remembered.
        update(rows(setting(63)))
        assertHeader()
        assertBody(null)
        assertEquals(0, countOf(hasTestTag(BankAlertSectionTags.row(63))))

        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("the new rows after unfolding", 1, countOf(hasTestTag(BankAlertSectionTags.row(63))))
        assertEquals("not the old ones", 0, countOf(hasTestTag(BankAlertSectionTags.row(61))))

        // Folding hides the error card and its retry too.
        update(body(Content.Error(GENERIC_COPY)))
        assertBody(BankAlertSectionTags.ERROR)
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(null)
        assertEquals(0, countOf(hasTestTag(BankAlertSectionTags.RETRY)))
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertBody(BankAlertSectionTags.ERROR)
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- refresh ----

    @Test
    fun s7e5a1_D14_anEnabledRefreshFiresOncePerTapInEveryBodyAndWhileFolded() {
        show(rows(setting(71)))
        assertHeader()
        clickableIn(BankAlertSectionTags.REFRESH).assertIsEnabled()
        assertEquals("not spinning", 0, refreshSpinners())

        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 1)
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 2)

        var expected = 2
        listOf(body(Content.Loading), body(Content.Error(GENERIC_COPY)), body(Content.Empty)).forEach { s ->
            update(s)
            assertHeader()
            clickableIn(BankAlertSectionTags.REFRESH).assertIsEnabled()
            tap(clickableIn(BankAlertSectionTags.REFRESH))
            expected += 1
            assertCallbacks(retry = 0, refresh = expected)
        }

        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(null)
        assertCallbacks(retry = 0, refresh = expected)
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = expected + 1)
    }

    @Test
    fun s7e5a1_D15_aDisabledRefreshFiresNothingAndTheSpinnerFollowsTheState() {
        show(rows(setting(72)))
        assertHeader()
        clickableIn(BankAlertSectionTags.REFRESH).assertIsEnabled()
        assertEquals(0, refreshSpinners())
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 1)

        // Cooling down: disabled, no spinner, a tap fires nothing.
        update(rows(setting(72), spinning = false, refreshEnabled = false))
        assertHeader()
        clickableIn(BankAlertSectionTags.REFRESH).assertIsNotEnabled()
        assertEquals("cooling down is not spinning", 0, refreshSpinners())
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 1)

        // Sending: disabled and spinning inside the refresh button, a tap fires nothing.
        update(rows(setting(72), spinning = true, refreshEnabled = false))
        assertHeader()
        clickableIn(BankAlertSectionTags.REFRESH).assertIsNotEnabled()
        assertTrue("the refresh spinner", refreshSpinners() >= 1)
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 1)

        // Enabled again: no spinner, one call per tap.
        update(rows(setting(72), spinning = false, refreshEnabled = true))
        assertHeader()
        clickableIn(BankAlertSectionTags.REFRESH).assertIsEnabled()
        assertEquals("the spinner stops", 0, refreshSpinners())
        tap(clickableIn(BankAlertSectionTags.REFRESH))
        assertCallbacks(retry = 0, refresh = 2)
    }

    // ---- gap-hunt kill tests (s7e5/gaphunt_candidates.r1) ----

    /**
     * Which word goes with which state: an enabled row is announced "켜짐", a disabled one "꺼짐", and the word follows
     * the state, not the row's id or position. It reads the merged row node, the one a screen reader announces (the only
     * merged-tree query in this file). Disclosed test-side tightening: the agreed text (API r2 §3, Codex r2 §3) says only
     * that enabled and disabled are told apart statically; these exact words are this file's choice.
     */
    @Test
    fun s7e5a1_D16_anEnabledRowIsAnnouncedOnAndADisabledRowOff() {
        val onWord = "켜짐"
        val offWord = "꺼짐"
        fun announced(id: Int): String? = rule.onNodeWithTag(BankAlertSectionTags.row(id)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)

        // Identical except id and isEnabled.
        val on = setting(83, order = 0, threshold = 1400.0, enabled = true)
        val off = setting(84, order = 1, threshold = 1400.0, enabled = false)
        show(rows(on, off))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("an enabled row is announced as on", onWord, announced(83))
        assertEquals("a disabled row is announced as off", offWord, announced(84))

        // The same ids with isEnabled swapped: each announcement follows its row's state.
        update(rows(on.copy(isEnabled = false), off.copy(isEnabled = true)))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("a row that became disabled is announced as off", offWord, announced(83))
        assertEquals("a row that became enabled is announced as on", onWord, announced(84))
        assertCallbacks(retry = 0, refresh = 0)
    }

    /**
     * A disabled row's content is drawn at about half the opacity of an otherwise identical enabled row (API r2 §3 "비활성
     * 행은 opacity 0.5", Codex r2 §3). Opacity never reaches semantics, so this reads the screen. Each row is captured.
     * Its background is its most common pixel, and its reach is the largest per-channel distance of any pixel from that
     * background. An alpha layer a moves every content pixel to background + a × (content − background), so the disabled
     * reach over the enabled reach is a. The 0.3..0.7 band allows for anti-aliasing and rounding but not for 1.0 (no
     * dimming). If the captures differ in size, only their common top-left area is compared. A third row follows, so
     * neither compared row touches the section's rounded bottom corners.
     */
    @Test
    fun s7e5a1_D17_aDisabledRowIsDrawnAtAboutHalfTheOpacityOfAnIdenticalEnabledRow() {
        fun capture(id: Int): PixelMap =
            rule.onNodeWithTag(BankAlertSectionTags.row(id), useUnmergedTree = true).captureToImage().toPixelMap()
        fun distance(a: Int, b: Int): Int = maxOf(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF))
        )
        /** The most common colour of the top-left [w]×[h] of [map], and the largest distance of any pixel there from it. */
        fun backgroundAndReach(map: PixelMap, w: Int, h: Int): Pair<Int, Int> {
            val counts = HashMap<Int, Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val argb = map[x, y].toArgb()
                counts[argb] = (counts[argb] ?: 0) + 1
            }
            val background = counts.entries.maxBy { it.value }.key
            var reach = 0
            for (y in 0 until h) for (x in 0 until w) reach = maxOf(reach, distance(map[x, y].toArgb(), background))
            return background to reach
        }

        // Same bank, threshold and condition, no status label; only isEnabled differs.
        val on = setting(85, order = 0, threshold = 1400.0, enabled = true)
        val off = setting(86, order = 1, threshold = 1400.0, enabled = false)
        val tail = setting(87, order = 2, threshold = 1400.0, enabled = true)
        show(rows(on, off, tail))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        listOf(85, 86).forEach { id ->
            rule.onNodeWithTag(BankAlertSectionTags.row(id), useUnmergedTree = true).assertIsDisplayed()
            assertTrue("premise: row $id is drawn with its bank", rowShows(id, "하나은행"))
            listOf(SENT_LABEL, UNKNOWN_REPEAT_LABEL, "마다").forEach { label ->
                assertFalse("premise: row $id has no status label ($label)", rowShows(id, label))
            }
        }
        assertEquals("premise: the compared rows show the same texts", rowTexts(85), rowTexts(86))

        val onPixels = capture(85)
        val offPixels = capture(86)
        // S7 E5a2: the switch and the delete button stay fully opaque (as on iOS); compare only the content left of them.
        fun contentWidth(id: Int): Int {
            val row = rule.onNodeWithTag(BankAlertSectionTags.row(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val toggle = rule.onNodeWithTag(BankAlertSectionTags.toggle(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return (toggle.left - row.left).toInt()
        }
        val w = minOf(onPixels.width, offPixels.width, contentWidth(85), contentWidth(86))
        val h = minOf(onPixels.height, offPixels.height)
        assertTrue("premise: the captures share an area (${w}x$h)", w > 0 && h > 0)
        val (onBackground, onReach) = backgroundAndReach(onPixels, w, h)
        val (offBackground, offReach) = backgroundAndReach(offPixels, w, h)
        assertTrue(
            "premise: both rows lie on the same background (${"%08x".format(onBackground)} vs " +
                "${"%08x".format(offBackground)})",
            distance(onBackground, offBackground) <= 2
        )
        assertTrue("premise: the enabled row draws visible content (reach $onReach)", onReach >= 64)
        val ratio = offReach.toDouble() / onReach
        assertTrue(
            "a disabled row is drawn at about half the opacity of an identical enabled row: " +
                "reach $offReach / $onReach = $ratio, expected 0.3..0.7",
            ratio in 0.3..0.7
        )
    }

    @Test
    fun s7e5a1_D18_exactlyOneActiveAlertShowsTheBadgeOneAndZeroShowsNoBadge() {
        val on = setting(15, order = 0)
        val off = setting(16, order = 1, enabled = false)
        show(rows(on, off))
        fun badge(n: Int) = countOf(
            hasText("$n") and inSubtree(BankAlertSectionTags.ROOT) and !inSubtree(BankAlertSectionTags.ROWS)
        )

        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("premise: exactly one active alert", 1, shown.value.activeCount)
        assertEquals("one active alert: the badge shows 1", 1, badge(1))

        // A folded header keeps the badge of one.
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertHeader()
        assertBody(null)
        assertEquals("one active alert: the folded header keeps the badge 1", 1, badge(1))
        tap(clickableIn(BankAlertSectionTags.TOGGLE_EXPANDED))
        assertBody(BankAlertSectionTags.ROWS)

        // Zero active: rows are still shown, with no badge.
        update(rows(on.copy(isEnabled = false), off))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertEquals("premise: no active alert", 0, shown.value.activeCount)
        assertEquals("zero active: no badge 0", 0, countOf(hasText("0") and inSubtree(BankAlertSectionTags.ROOT)))
        assertEquals("zero active: the badge 1 is gone", 0, badge(1))

        // One active again: the badge 1 returns.
        update(rows(on, off))
        assertHeader()
        assertEquals("one active alert again: the badge shows 1", 1, badge(1))
        assertCallbacks(retry = 0, refresh = 0)
    }

    @Test
    fun s7e5a1_D19_disabledRowsKeepTheStatusLabelsOfTheTriggeredThenRawRepeatTable() {
        val unknownSeconds = 123
        assertNull("premise: 123 is not a known interval", RepeatInterval.from(unknownSeconds))
        val known = RepeatInterval.FIVE_MINUTES
        val given = listOf(
            setting(101, order = 0, enabled = false, triggered = true, repeat = null),
            setting(102, order = 1, enabled = false, triggered = true, repeat = known.seconds),
            setting(103, order = 2, enabled = false, triggered = true, repeat = unknownSeconds),
            setting(104, order = 3, enabled = false, repeat = known.seconds),
            setting(105, order = 4, enabled = false, repeat = unknownSeconds),
            setting(106, order = 5, enabled = false, repeat = null)
        )
        given.forEach { assertFalse("premise: row ${it.id} is disabled", it.isEnabled) }
        show(rows(*given.toTypedArray()))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        given.forEach { s -> assertTrue("premise: row ${s.id} is drawn with its bank", rowShows(s.id, "하나은행")) }

        // Triggered and disabled (a one-shot alert after it fired): the sent label wins over every repeat value.
        listOf(101, 102, 103).forEach { id ->
            assertTrue("disabled row $id: triggered shows the sent label", rowTexts(id).contains(SENT_LABEL))
            assertFalse("disabled row $id: triggered has no repeat label", rowShows(id, "마다"))
            assertFalse("disabled row $id: triggered has no generic repeat label", rowShows(id, UNKNOWN_REPEAT_LABEL))
        }

        // Not triggered and disabled (a paused alert): the raw repeat value decides, as for an enabled row.
        assertTrue(
            "disabled row 104: a known interval shows its every-label", rowTexts(104).contains(known.everyLabel)
        )
        assertFalse("disabled row 104: no generic repeat label", rowShows(104, UNKNOWN_REPEAT_LABEL))
        assertFalse("disabled row 104: not sent", rowShows(104, SENT_LABEL))

        assertTrue(
            "disabled row 105: an unknown non-null value shows the generic repeat label",
            rowTexts(105).contains(UNKNOWN_REPEAT_LABEL)
        )
        assertFalse("disabled row 105: no guessed interval", rowShows(105, "마다"))
        assertFalse("disabled row 105: not sent", rowShows(105, SENT_LABEL))
        assertFalse("disabled row 105: not a once rendering", rowShows(105, "1회"))

        listOf(SENT_LABEL, UNKNOWN_REPEAT_LABEL, "마다", "1회").forEach { label ->
            assertFalse("disabled row 106: a null repeat has no label ($label)", rowShows(106, label))
        }
        assertCallbacks(retry = 0, refresh = 0)
    }

    // ---- parity with iOS and the earlier Android section (implementation review r1; test-side tightenings, disclosed) ----

    /**
     * The title group folds as well as the chevron: tapping the "환율 알림" title or the active-count badge folds and unfolds
     * the body (iOS AlertSection.swift sectionHeader and the pre-E5a1 Android section share one toggle). A tap is a touch at the
     * node, so the click action may sit on the node or on the header group around it.
     */
    @Test
    fun s7e5a1_D20_tappingTheTitleOrTheBadgeFoldsAndUnfolds() {
        show(rows(setting(201, order = 0), setting(202, order = 1)))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        val title = rule.onNode(hasText(TITLE) and inSubtree(BankAlertSectionTags.ROOT), useUnmergedTree = true)
        val badge = rule.onNode(hasText("2") and inSubtree(BankAlertSectionTags.ROOT) and !inSubtree(BankAlertSectionTags.ROWS),
            useUnmergedTree = true)

        for ((label, target) in listOf("the title" to title, "the badge" to badge)) {
            tap(target)
            assertHeader()
            assertBody(null)
            assertEquals("$label folded the body: no row", 0, countOf(hasTestTag(BankAlertSectionTags.row(201))))
            tap(target)
            assertHeader()
            assertBody(BankAlertSectionTags.ROWS)
            assertEquals("$label unfolded the body", 1, countOf(hasTestTag(BankAlertSectionTags.row(201))))
        }
        assertCallbacks(retry = 0, refresh = 0)
    }

    /** The colour of the one [label] text in row [id], as laid out (the colour a Text draws with). */
    private fun labelColor(id: Int, label: String): Color {
        val node = nodes(hasText(label) and inSubtree(BankAlertSectionTags.row(id))).single()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue("premise: the label lays out", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        return layouts.single().layoutInput.style.color
    }

    /**
     * "알림 발송됨" is drawn in the status colour and a repeat label in the accent colour, enabled or not (iOS AlertSection.swift
     * bankInfo: triggered `.orange`, repeat `Color.accentColor`), so a repeating alert never looks already sent.
     */
    @Test
    fun s7e5a1_D21_theSentLabelUsesTheStatusColourAndARepeatLabelTheAccent() {
        val known = RepeatInterval.FIVE_MINUTES
        show(
            rows(
                setting(211, order = 0, triggered = true),
                setting(212, order = 1, repeat = known.seconds),
                setting(213, order = 2, repeat = 123),
                setting(214, order = 3, enabled = false, triggered = true, repeat = known.seconds),
                setting(215, order = 4, enabled = false, repeat = known.seconds)
            )
        )
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)
        assertNotEquals("premise: the two colours differ", StatusConnecting, Primary)

        assertEquals("a sent label: the status colour", StatusConnecting, labelColor(211, SENT_LABEL))
        assertEquals("a known repeat: the accent colour", Primary, labelColor(212, known.everyLabel))
        assertEquals("an unknown repeat: the accent colour", Primary, labelColor(213, UNKNOWN_REPEAT_LABEL))
        assertEquals("a disabled sent label: still the status colour", StatusConnecting, labelColor(214, SENT_LABEL))
        assertEquals("a disabled repeat: still the accent colour", Primary, labelColor(215, known.everyLabel))
    }

    /**
     * The threshold is the app's one formatted threshold ([AlertSetting.formattedThreshold], the shared Korean two-decimal
     * formatter with half-even rounding), not a second formatting rule: an exact binary tie at the third decimal shows as that
     * formatter rounds it.
     */
    @Test
    fun s7e5a1_D22_theThresholdIsTheSharedFormattedThresholdEvenOnATie() {
        val tie = setting(221, order = 0, threshold = 1475.125)
        val up = setting(222, order = 1, threshold = 1475.375)
        assertEquals("premise: the shared formatter rounds the 1/8 tie to even", "1475.12", tie.formattedThreshold)
        assertEquals("premise: and the 3/8 tie to even", "1475.38", up.formattedThreshold)
        show(rows(tie, up))
        assertHeader()
        assertBody(BankAlertSectionTags.ROWS)

        assertTrue("row 221 shows the shared format", rowShows(221, tie.formattedThreshold))
        assertFalse("row 221 shows no half-up rounding", rowShows(221, "1475.13"))
        assertTrue("row 222 shows the shared format", rowShows(222, up.formattedThreshold))
    }

    private companion object {
        const val TITLE = "환율 알림"
        const val LOADING_TEXT = "알림 설정 로드 중..."
        const val EMPTY_TEXT = "설정된 알림이 없습니다"
        const val SENT_LABEL = "알림 발송됨"
        const val UNKNOWN_REPEAT_LABEL = "반복 알림"
        const val NETWORK_COPY = "네트워크 연결을 확인해 주세요."
        const val LOGIN_COPY = "로그인이 필요합니다."
        const val GENERIC_COPY = "알림 설정을 불러오지 못했습니다."
        /** S7 E5a2: "삭제" is the delete button's own label now, kept out of these inert scans with the button. */
        val ROW_FORBIDDEN = listOf("삭제", "편집", "수정", "추가")
        val SECTION_FORBIDDEN = listOf("삭제", "편집", "수정", "추가", "권한")
    }
}
