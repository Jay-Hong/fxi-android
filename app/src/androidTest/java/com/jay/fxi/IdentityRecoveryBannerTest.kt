package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.screen.IDENTITY_RECOVERY_BANNER_TAG
import com.jay.fxi.ui.screen.IDENTITY_RECOVERY_CONTENT_TAG
import com.jay.fxi.ui.screen.IdentityRecoveryHost
import com.jay.fxi.ui.screen.RecoveryNotice
import com.jay.fxi.ui.theme.FXiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The banner and its layout, composed with fake notices and a fake callback.
 *
 * Nothing here goes through `RootScreen`, so it does not depend on the D24 gate that stops debug
 * builds before the armed screen. What to say is locked on the JVM in `RecoveryNoticeTest`; this
 * checks only that the strings are drawn, a press forwards the notice's own id, and the content is
 * pushed below the banner.
 *
 * ⚠️ CI compiles this and does not run it. It has to be run on a device or emulator.
 */
class IdentityRecoveryBannerTest {

    /** An activity rule rather than a bare compose rule, so the inset tests can draw edge-to-edge. */
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fakeContentTag = "fake_destination"

    private fun notice(holdId: Long = 42L, action: String? = null) = RecoveryNotice(
        holdId = holdId,
        title = "제목-가짜",
        body = "본문-가짜",
        action = action
    )

    private fun host(notice: RecoveryNotice?, onRecheck: (Long) -> Unit = {}) {
        rule.setContent {
            FXiTheme {
                IdentityRecoveryHost(notice = notice, onRecheck = onRecheck) {
                    Box(Modifier.fillMaxSize().testTag(fakeContentTag))
                }
            }
        }
    }

    @Test
    fun withNoNoticeNoBannerIsDrawnAndTheContentIsShown() {
        host(notice = null)

        rule.onAllNodesWithTag(IDENTITY_RECOVERY_BANNER_TAG).assertCountEquals(0)
        rule.onNodeWithTag(fakeContentTag).assertIsDisplayed()
    }

    @Test
    fun aNoticeDrawsItsTitleAndBody() {
        host(notice = notice())

        rule.onNodeWithTag(IDENTITY_RECOVERY_BANNER_TAG).assertIsDisplayed()
        rule.onNodeWithText("제목-가짜").assertIsDisplayed()
        rule.onNodeWithText("본문-가짜").assertIsDisplayed()
    }

    /** No action, no button — the banner offers nothing where pressing would change nothing. */
    @Test
    fun aNoticeWithoutAnActionHasNoClickableNode() {
        host(notice = notice(action = null))

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    /** The press names the id of the notice on screen, not any other value. */
    @Test
    fun pressingTheActionForwardsTheNoticesOwnHoldId() {
        val pressed = mutableListOf<Long>()
        host(notice = notice(holdId = 4_294_967_296L, action = "누르기-가짜"), onRecheck = { pressed += it })

        rule.onNodeWithText("누르기-가짜").performClick()

        assertEquals(listOf(4_294_967_296L), pressed)
    }

    /** Pushed, not covered: the destination starts where the banner ends. */
    @Test
    fun theContentStartsBelowTheBanner() {
        host(notice = notice())

        val banner = rule.onNodeWithTag(IDENTITY_RECOVERY_BANNER_TAG).getUnclippedBoundsInRoot()
        val content = rule.onNodeWithTag(IDENTITY_RECOVERY_CONTENT_TAG).getUnclippedBoundsInRoot()

        assertTrue("콘텐츠가 배너 아래에서 시작하지 않는다: banner=$banner content=$content", content.top >= banner.bottom)
    }

    /**
     * The content slot spans the rest of the host, whatever the destination measures itself as.
     * A wrap-content destination is what shows the difference: without the weight its slot would
     * shrink to the content and leave the bottom of the screen empty.
     */
    @Test
    fun theContentSlotSpansTheRestOfTheHost() {
        rule.setContent {
            FXiTheme {
                Box(Modifier.fillMaxSize().testTag("host_root")) {
                    IdentityRecoveryHost(notice = notice(), onRecheck = {}) {
                        Box(Modifier.fillMaxWidth().height(10.dp))
                    }
                }
            }
        }

        val root = rule.onNodeWithTag("host_root").getUnclippedBoundsInRoot()
        val slot = rule.onNodeWithTag(IDENTITY_RECOVERY_CONTENT_TAG).getUnclippedBoundsInRoot()
        assertTrue("콘텐츠 자리가 화면 끝까지 닿지 않는다: root=$root slot=$slot", slot.bottom >= root.bottom - 0.5.dp)
    }

    /**
     * Status-bar insets are consumed only while the banner is showing.
     *
     * Destinations pad themselves for the status bar. With a banner above them, that padding would
     * be a second gap below the banner, so the slot consumes the insets; with no banner it must not,
     * or every screen would slide under the status bar in normal use.
     *
     * The activity is switched to edge-to-edge first, and the test refuses to judge if the inset still
     * reads zero — at zero, consuming and not consuming look identical and both cases would pass.
     */
    @Test
    fun statusBarInsetsAreConsumedOnlyWhileTheBannerShows() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        var statusBar: Dp = (-1).dp
        var shown by mutableStateOf<RecoveryNotice?>(null)

        rule.setContent {
            FXiTheme {
                val density = LocalDensity.current
                statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
                IdentityRecoveryHost(notice = shown, onRecheck = {}) {
                    Box(Modifier.fillMaxSize().testTag("outer")) {
                        Box(
                            Modifier.windowInsetsPadding(WindowInsets.statusBars)
                                .fillMaxWidth()
                                .height(1.dp)
                                .testTag("inner")
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue("상태바 inset 이 0 이라 이 시험은 판정할 수 없다(edge-to-edge 미적용?): $statusBar", statusBar > 0.dp)

        fun padding(): Dp {
            val outer = rule.onNodeWithTag("outer").getUnclippedBoundsInRoot()
            val inner = rule.onNodeWithTag("inner").getUnclippedBoundsInRoot()
            return inner.top - outer.top
        }

        val withoutBanner = padding()
        assertTrue("배너가 없는데 목적지의 상태바 패딩이 사라졌다: $withoutBanner vs $statusBar",
            (withoutBanner - statusBar).value in -0.5f..0.5f)

        shown = notice()
        rule.waitForIdle()
        val withBanner = padding()
        assertTrue("배너가 있는데 목적지가 상태바 패딩을 한 번 더 넣었다: $withBanner", withBanner.value in -0.5f..0.5f)
    }
}
