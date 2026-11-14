package com.jay.fxi.ui.alert

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * S7 E5b2b2 (s7e5b2_api_agreed.r1.md §3.2, §3.4): the real [AlertSection] with its two new arguments. Loading and Error come
 * first as before; Empty with notifications blocked shows the prompt instead of the empty text; Rows with notifications blocked
 * shows the banner above the list. The prompt's button and the banner each call onEnableNotifications once; the row switch and
 * delete never do. Gap hunt r1 (s7e5b2b2_gaphunt.codex.r1.md): G08 folding with a real identity, G09 the copy is drawn, G10 a
 * blocked section keeps confirmed delete, identity binding and both switch directions. Every query walks the unmerged tree.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B2SectionTest {

    @get:Rule val rule = createComposeRule()

    private val shown = mutableStateOf(state(Content.Empty))
    private val blocked = mutableStateOf(true)
    private val identity = mutableStateOf<AuthIdentityFence?>(A)
    private var enables = 0
    /** S7 E5b3b: the prompt now asks for the add entry; the banner still asks for the permission only. */
    private var adds = 0
    private val opens = mutableListOf<Int>()
    private val addAvailability = mutableStateOf<BankAlertAddAvailability>(BankAlertAddAvailability.Hidden)
    private var retries = 0
    private val toggles = mutableListOf<Pair<Int, Boolean>>()
    private val deletes = mutableListOf<Int>()

    private fun show() {
        rule.setContent {
            AlertSection(
                currency = SupportedCurrency.USD_KRW,
                state = shown.value,
                onRetry = { retries += 1 },
                onRefresh = { },
                actionIdentity = identity.value,
                onToggle = { id, enabled -> toggles += id to enabled },
                onDelete = { id -> deletes += id },
                notificationsBlocked = blocked.value,
                onEnableNotifications = { enables += 1 },
                addAvailability = addAvailability.value,
                onAdd = { adds += 1 },
                onEdit = { id -> opens += id }
            )
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    private fun update(content: Content? = null, isBlocked: Boolean? = null) {
        rule.runOnIdle {
            if (content != null) shown.value = state(content)
            if (isBlocked != null) blocked.value = isBlocked
        }
        rule.waitForIdle()
    }

    @Test fun S7E5B2B2_G01_aBlockedEmptySectionShowsThePromptWithTheAgreedCopyInsteadOfTheEmptyText() {
        show()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON))
        assertEquals("the empty text gives way to the prompt", 0, count(BankAlertSectionTags.EMPTY))
        assertEquals(0, texts("설정된 알림이 없습니다"))
        for (copy in listOf(PROMPT_TITLE, PROMPT_BODY, PROMPT_BUTTON)) assertEquals(copy, 1, texts(copy))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_BANNER))
        assertEquals("nothing runs before a click", listOf(0, 0), listOf(enables, adds))
    }

    @Test fun S7E5B2B2_G02_thePromptButtonCallsOnceAndNothingElse() {
        show()
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        // S7 E5b3b (start agreement §4-2, API r2 D5): the prompt now asks for the add entry; the holder runs the helper first.
        assertEquals(listOf(1, 0), listOf(adds, enables))
        assertEquals(0, retries)
        assertEquals(emptyList<Pair<Int, Boolean>>(), toggles)
        assertEquals(emptyList<Int>(), deletes)
    }

    @Test fun S7E5B2B2_G03_aBlockedRowsSectionShowsTheBannerAboveTheRows() {
        shown.value = state(Content.Rows(listOf(setting(71, order = 0), setting(72, order = 1))))
        show()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT))
        for (copy in listOf(BANNER_TITLE, BANNER_BODY)) assertEquals(copy, 1, texts(copy))
        assertEquals(1, count(BankAlertSectionTags.ROWS))
        val banner = rule.onNodeWithTag(BankAlertSectionTags.NOTIFY_BANNER, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag(BankAlertSectionTags.row(71), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the banner sits above the list: $banner / $first", banner.bottom <= first.top)
    }

    @Test fun S7E5B2B2_G04_theBannerCallsOnceAndTheRowControlsNever() {
        shown.value = state(Content.Rows(listOf(setting(71))))
        show()
        tap(BankAlertSectionTags.NOTIFY_BANNER)
        assertEquals("the banner asks for the permission only", listOf(1, 0), listOf(enables, adds))
        tap(BankAlertSectionTags.toggle(71))
        assertEquals(listOf(71 to false), toggles)
        tap(BankAlertSectionTags.delete(71))
        tap(BankAlertSectionTags.DELETE_CANCEL)
        assertEquals(emptyList<Int>(), deletes)
        assertEquals("the inline switch and delete never ask", 1, enables)
    }

    @Test fun S7E5B2B2_G05_loadingAndErrorComeFirstAndShowNeitherEvenWhenBlocked() {
        shown.value = state(Content.Loading)
        show()
        assertEquals(1, count(BankAlertSectionTags.LOADING))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT) + count(BankAlertSectionTags.NOTIFY_BANNER))
        update(content = Content.Error("알림 설정을 불러오지 못했습니다."))
        assertEquals(1, count(BankAlertSectionTags.ERROR))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT) + count(BankAlertSectionTags.NOTIFY_BANNER))
        tap(BankAlertSectionTags.RETRY)
        assertEquals(1, retries)
        assertEquals(0, enables)
    }

    @Test fun S7E5B2B2_G06_unblockedShowsNeitherAndKeepsTheEmptyText() {
        blocked.value = false
        show()
        assertEquals(1, count(BankAlertSectionTags.EMPTY))
        assertEquals(1, texts("설정된 알림이 없습니다"))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT) + count(BankAlertSectionTags.NOTIFY_BANNER))
        update(content = Content.Rows(listOf(setting(71))))
        assertEquals(1, count(BankAlertSectionTags.ROWS))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT) + count(BankAlertSectionTags.NOTIFY_BANNER))
        for (copy in listOf(PROMPT_TITLE, PROMPT_BODY, PROMPT_BUTTON, BANNER_TITLE, BANNER_BODY)) assertEquals(copy, 0, texts(copy))
    }

    @Test fun S7E5B2B2_G07_theSameCompositionFollowsTheBlockedFlagAndTheContent() {
        show()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        update(isBlocked = false)
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT))
        assertEquals(1, count(BankAlertSectionTags.EMPTY))
        update(content = Content.Rows(listOf(setting(71))), isBlocked = true)
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_PROMPT))
        update(isBlocked = false)
        assertEquals(0, count(BankAlertSectionTags.NOTIFY_BANNER))
        update(content = Content.Empty, isBlocked = true)
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        assertEquals(0, enables)
    }

    @Test fun S7E5B2B2_G08_foldingHidesEveryBodyAndBothNotifySurfacesWithARealIdentityAcrossChanges() {
        show()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        fun bodies() = listOf(BankAlertSectionTags.LOADING, BankAlertSectionTags.ERROR, BankAlertSectionTags.EMPTY, BankAlertSectionTags.ROWS,
            BankAlertSectionTags.NOTIFY_PROMPT, BankAlertSectionTags.NOTIFY_PROMPT_BUTTON, BankAlertSectionTags.NOTIFY_BANNER).sumOf { count(it) }
        assertEquals("folded: no body and no notify surface", 0, bodies())
        for ((content, isBlocked) in listOf(Content.Rows(listOf(setting(71))) to true, Content.Loading to true,
                Content.Error("알림 설정을 불러오지 못했습니다.") to true, Content.Rows(listOf(setting(71))) to false, Content.Empty to false, Content.Empty to true)) {
            update(content = content, isBlocked = isBlocked)
            assertEquals("folded through $content blocked=$isBlocked", 0, bodies())
            assertEquals(1, count(BankAlertSectionTags.ROOT))
        }
        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        assertEquals("unfolded: the latest state's prompt", 1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        update(content = Content.Rows(listOf(setting(71))))
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        assertEquals(1, count(BankAlertSectionTags.ROWS))
        assertEquals(listOf(0, 0), listOf(enables, retries))
        assertEquals(emptyList<Pair<Int, Boolean>>(), toggles)
    }

    @Test fun S7E5B2B2_G09_thePromptAndTheBannerAreDrawnNotJustPresent() {
        show()
        for (tag in listOf(BankAlertSectionTags.NOTIFY_PROMPT, BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)) {
            rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val ink = ink(tag)
            assertTrue("$tag is drawn: ink fraction $ink", ink >= MIN_INK)
        }
        update(content = Content.Rows(listOf(setting(71))))
        rule.onNodeWithTag(BankAlertSectionTags.NOTIFY_BANNER, useUnmergedTree = true).assertIsDisplayed()
        val ink = ink(BankAlertSectionTags.NOTIFY_BANNER)
        assertTrue("the banner is drawn: ink fraction $ink", ink >= MIN_INK)
    }

    @Test fun S7E5B2B2_G10_aBlockedSectionKeepsConfirmedDeleteItsIdentityBindingAndBothSwitchDirections() {
        shown.value = state(Content.Rows(listOf(setting(71), setting(72, order = 1, enabled = false))))
        show()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        tap(BankAlertSectionTags.delete(71))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals("a confirmed delete reaches its row once", listOf(71), deletes)
        tap(BankAlertSectionTags.delete(72))
        tap(BankAlertSectionTags.DELETE_CANCEL)
        assertEquals(listOf(71), deletes)
        tap(BankAlertSectionTags.delete(72))
        assertEquals(1, count(BankAlertSectionTags.DELETE_DIALOG))
        rule.runOnIdle { identity.value = A2 }
        rule.waitForIdle()
        assertEquals("another identity drops the open confirmation", 0, count(BankAlertSectionTags.DELETE_DIALOG))
        assertEquals(listOf(71), deletes)
        tap(BankAlertSectionTags.toggle(72))
        tap(BankAlertSectionTags.toggle(71))
        assertEquals("OFF to ON and ON to OFF, once each", listOf(72 to true, 71 to false), toggles)
        update(isBlocked = false)
        tap(BankAlertSectionTags.delete(72))
        tap(BankAlertSectionTags.DELETE_CONFIRM)
        assertEquals("after unblocking, confirm still deletes", listOf(71, 72), deletes)
        assertEquals("no row action ever asks", 0, enables)
    }

    /** The fraction of the node's pixels far from its most common colour: text and icons, not just a filled box. */
    private fun ink(tag: String): Double {
        val map = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val counts = HashMap<Int, Int>()
        for (y in 0 until map.height) for (x in 0 until map.width) counts.merge(map[x, y].toArgb(), 1, Int::plus)
        val background = counts.maxByOrNull { it.value }!!.key
        fun far(a: Int) = maxOf(kotlin.math.abs((a shr 16 and 0xFF) - (background shr 16 and 0xFF)),
            kotlin.math.abs((a shr 8 and 0xFF) - (background shr 8 and 0xFF)), kotlin.math.abs((a and 0xFF) - (background and 0xFF))) > 48
        var inked = 0
        for (y in 0 until map.height) for (x in 0 until map.width) if (far(map[x, y].toArgb())) inked++
        return inked.toDouble() / (map.width * map.height)
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
        val A2 = AuthIdentityFence("user-a", 2L)
        const val MIN_INK = 0.02
        const val PROMPT_TITLE = "환율 알림을 받으려면\n알림 권한이 필요합니다"
        const val PROMPT_BODY = "원하는 환율에 도달하면 푸시 알림으로 알려드립니다"
        const val PROMPT_BUTTON = "알림 권한 설정하기"
        const val BANNER_TITLE = "알림을 받을 수 없습니다"
        const val BANNER_BODY = "알림 권한을 설정해주세요"

        fun state(content: Content) = BankAlertSectionState(
            content = content,
            activeCount = (content as? Content.Rows)?.rows?.count { it.isEnabled } ?: 0,
            refreshSpinning = false,
            refreshEnabled = true
        )

        fun setting(id: Int, order: Int = 0, enabled: Boolean = true): AlertSetting {
            val at = Instant.fromEpochSeconds(1_793_000_000L - order)
            return AlertSetting(id, "user-a", "hana", SupportedCurrency.USD_KRW.code, AlertCondition.ABOVE, 1400.0 + id, enabled, false,
                at, at, null, null)
        }
    }
}
