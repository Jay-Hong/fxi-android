package com.jay.fxi.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import com.jay.fxi.ui.alert.LocalNotificationPermissionEnvironment
import com.jay.fxi.ui.alert.NotificationPermissionEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b2b1 (s7e5b2b_start_agreed.r1.md §4-3): the real SettingsMainContent under the helper's environment seam. The row
 * shows only on needsAction, sits between the profile card and the general section, and one click runs the helper once.
 * The OS read in the click is real; this rig never opens a permission dialog or a settings screen (precondition below).
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B1SettingsRowTest {

    @get:Rule val compose = createComposeRule()

    private class Rig {
        var admitted = true
        var identity: AuthIdentityFence? = A
        val premium = MutableStateFlow(grantFor(A))
        val displayed = MutableStateFlow<NotificationCapability?>(NotificationCapability.APP_DISABLED)
        val already = mutableListOf<AuthIdentityFence>()
        val results = mutableListOf<AuthIdentityFence?>()
        val env = NotificationPermissionEnvironment(
            admitted = { admitted },
            liveIdentity = { identity },
            premium = premium,
            displayed = displayed,
            onRequestResult = { results += it; false },
            onAlreadyGranted = { already += it; true }
        )
    }

    private fun show(rig: Rig, user: UserInfo? = null) = compose.setContent {
        CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
            SettingsMainContent(
                userInfo = user,
                showRatingOption = false,
                onDismiss = {},
                onNavigateProfile = {},
                onNavigateAppInfo = {},
                onNavigateNotices = {},
                onNavigateAppRating = {},
                onNavigatePrivacyPolicy = {}
            )
        }
    }

    private fun rowCount() = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(SettingsTags.NOTIFICATION_ROW)).fetchSemanticsNodes().size

    @Test fun S7E5B2B1_R01_theRowShowsForAConfirmedSessionWhoseNotificationsAreBlocked() {
        val rig = Rig()
        show(rig)
        compose.onNodeWithTag(SettingsTags.NOTIFICATION_ROW).assertIsDisplayed()
        compose.onNodeWithText("알림 설정").assertIsDisplayed()
        compose.onNodeWithText("환율 알림을 받으려면 설정이 필요합니다").assertIsDisplayed()
    }

    @Test fun S7E5B2B1_R02_theRowFollowsEveryPublicationAndHidesWithoutAnAction() {
        val rig = Rig()
        show(rig)
        assertEquals(1, rowCount())
        rig.displayed.value = null
        compose.waitForIdle()
        assertEquals("unknown is not an action", 0, rowCount())
        rig.displayed.value = NotificationCapability.CHANNEL_DISABLED
        compose.waitForIdle()
        assertEquals(1, rowCount())
        rig.displayed.value = NotificationCapability.GRANTED
        compose.waitForIdle()
        assertEquals(0, rowCount())
        rig.displayed.value = NotificationCapability.RUNTIME_MISSING
        compose.waitForIdle()
        assertEquals(1, rowCount())
        rig.premium.value = grantFor(A, PremiumAccessState.FreeConfirmed)
        compose.waitForIdle()
        assertEquals("premium lost", 0, rowCount())
        rig.premium.value = grantFor(A)
        compose.waitForIdle()
        assertEquals(1, rowCount())
        rig.identity = B
        rig.premium.value = grantFor(A, PremiumAccessState.Pending(true))
        compose.waitForIdle()
        assertEquals("another owner", 0, rowCount())
        rig.premium.value = grantFor(A)
        compose.waitForIdle()
        assertEquals("the live identity is B: a grant for A is no action", 0, rowCount())
    }

    @Test fun S7E5B2B1_R03_aClosedGateShowsNoRow() {
        val rig = Rig().apply { admitted = false }
        show(rig)
        assertEquals(0, rowCount())
    }

    @Test fun S7E5B2B1_R04_theRowSitsAfterTheProfileCardAndBeforeTheGeneralSection() {
        val rig = Rig()
        show(rig, UserInfo(uid = "user-a", email = "tester@example.invalid", displayName = "Tester", photoUrl = null, provider = AuthProvider.APPLE))
        val row = compose.onNodeWithTag(SettingsTags.NOTIFICATION_ROW).getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithText("Tester", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val general = compose.onNodeWithText("앱 정보").getUnclippedBoundsInRoot()
        assertTrue("after the profile card: $profile / $row", profile.bottom <= row.top)
        assertTrue("before the general section: $row / $general", row.bottom <= general.top)
    }

    @Test fun S7E5B2B1_R05_oneClickRunsTheHelperOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("precondition: this device shows notifications, so the click takes the granted path and opens nothing",
            NotificationCapability.GRANTED, readNotificationCapability(context))
        val rig = Rig()
        show(rig)
        compose.onNodeWithTag(SettingsTags.NOTIFICATION_ROW).performClick()
        compose.waitForIdle()
        assertEquals("the helper asked the monitor once, for the live owner", listOf(A), rig.already)
        assertEquals(emptyList<AuthIdentityFence?>(), rig.results)
    }

    @Test fun S7E5B2B1_R06_withoutTheSeamTheClosedDebugGateIsInert() {
        compose.setContent {
            SettingsMainContent(
                userInfo = null,
                showRatingOption = false,
                onDismiss = {},
                onNavigateProfile = {},
                onNavigateAppInfo = {},
                onNavigateNotices = {},
                onNavigateAppRating = {},
                onNavigatePrivacyPolicy = {}
            )
        }
        compose.onNodeWithText("앱 정보").assertIsDisplayed()
        assertEquals("debug's gate is closed: production is inert", 0, rowCount())
    }

    @Test fun S7E5B2B1_R07_theRowEndsInItsChevron() {
        show(Rig())
        val chevrons = compose.onAllNodes(
            androidx.compose.ui.test.hasTestTag(SettingsTags.NOTIFICATION_ROW_CHEVRON) and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(SettingsTags.NOTIFICATION_ROW)),
            useUnmergedTree = true
        ).fetchSemanticsNodes()
        assertEquals("one chevron inside the row", 1, chevrons.size)
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
        val B = AuthIdentityFence("user-b", 2L)

        fun grantFor(owner: AuthIdentityFence, state: PremiumAccessState = PremiumAccessState.PremiumConfirmed) =
            OwnedPremiumAccess(owner.uid, owner.authGeneration, state)
    }
}
