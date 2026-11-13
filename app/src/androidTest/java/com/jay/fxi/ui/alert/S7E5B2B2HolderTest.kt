package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b2b2 (s7e5b2_api_agreed.r1.md §3.2): the real [BankAlertSection] over a fake source, under the helper's environment
 * seam. The holder shows the prompt or the banner exactly when the helper has an action, and a click runs the helper once.
 * The click's OS read is real; this rig never opens a permission dialog or a settings screen (precondition below).
 * Gap hunt r1 G06: H08 a blocked holder still hands a confirmed delete to its source for the rendered owner.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B2HolderTest {

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

    private fun factory(source: FakeSource) = object : BankAlertSectionSourceFactory {
        override fun create(context: Context): BankAlertSectionSource = source
    }

    private fun show(rig: Rig?, source: FakeSource, tab: FreeTab = FreeTab.USD) = compose.setContent {
        if (rig == null) {
            BankAlertSection(tab = tab, sourceFactory = factory(source))
        } else {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
                BankAlertSection(tab = tab, sourceFactory = factory(source))
            }
        }
    }

    private fun empty() = FakeSource(BankAlertSettingsState(AlertLoadPhase.Ready, emptyList(), A))
    private fun rows() = FakeSource(BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(81)), A))

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun shownCount() = count(BankAlertSectionTags.NOTIFY_PROMPT) + count(BankAlertSectionTags.NOTIFY_BANNER)

    private fun assertGrantedDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("precondition: this device shows notifications, so the click takes the granted path and opens nothing",
            NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    @Test fun S7E5B2B2_H01_anEmptyHolderShowsThePromptWhenTheHelperHasAnAction() {
        show(Rig(), empty())
        compose.waitForIdle()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_PROMPT))
        assertEquals(0, count(BankAlertSectionTags.EMPTY))
    }

    @Test fun S7E5B2B2_H02_thePromptRunsTheHelperOnceForTheLiveOwner() {
        assertGrantedDevice()
        val rig = Rig()
        val source = empty()
        show(rig, source)
        compose.waitForIdle()
        compose.onNodeWithTag(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(A), rig.already)
        assertEquals(emptyList<AuthIdentityFence?>(), rig.results)
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test fun S7E5B2B2_H03_theBannerRunsTheHelperOnceAndTheSwitchStillGoesToTheSource() {
        assertGrantedDevice()
        val rig = Rig()
        val source = rows()
        show(rig, source)
        compose.waitForIdle()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        compose.onNodeWithTag(BankAlertSectionTags.NOTIFY_BANNER, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(A), rig.already)
        compose.onNodeWithTag(BankAlertSectionTags.toggle(81), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("setEnabled 81 false"), source.calls)
        assertEquals("the switch never asks", listOf(A), rig.already)
    }

    @Test fun S7E5B2B2_H04_theHolderFollowsTheHelpersPublications() {
        val rig = Rig()
        show(rig, empty())
        compose.waitForIdle()
        assertEquals(1, shownCount())
        rig.displayed.value = NotificationCapability.GRANTED
        compose.waitForIdle()
        assertEquals("granted is no action", 0, shownCount())
        assertEquals(1, count(BankAlertSectionTags.EMPTY))
        rig.displayed.value = null
        compose.waitForIdle()
        assertEquals("unknown is no action", 0, shownCount())
        rig.displayed.value = NotificationCapability.RUNTIME_MISSING
        compose.waitForIdle()
        assertEquals(1, shownCount())
        rig.premium.value = grantFor(A, PremiumAccessState.FreeConfirmed)
        compose.waitForIdle()
        assertEquals("premium lost", 0, shownCount())
        rig.identity = B
        rig.premium.value = grantFor(A)
        compose.waitForIdle()
        assertEquals("the live identity is B: a grant for A is no action", 0, shownCount())
    }

    @Test fun S7E5B2B2_H05_aClosedGateShowsNeither() {
        show(Rig().apply { admitted = false }, rows())
        compose.waitForIdle()
        assertEquals(1, count(BankAlertSectionTags.ROWS))
        assertEquals(0, shownCount())
    }

    @Test fun S7E5B2B2_H06_withoutTheSeamTheClosedDebugGateIsInert() {
        show(null, empty())
        compose.waitForIdle()
        assertEquals(1, count(BankAlertSectionTags.EMPTY))
        assertEquals("debug's gate is closed: production is inert", 0, shownCount())
    }

    @Test fun S7E5B2B2_H07_aNonBankTabDrawsNoSectionAndAsksNothing() {
        val rig = Rig()
        show(rig, empty(), tab = FreeTab.TETHER)
        compose.waitForIdle()
        assertEquals(0, count(BankAlertSectionTags.ROOT))
        assertEquals(0, shownCount())
        assertEquals(emptyList<AuthIdentityFence>(), rig.already)
    }

    @Test fun S7E5B2B2_H08_aBlockedHolderHandsAConfirmedDeleteToItsSourceAndAsksNothing() {
        val rig = Rig()
        val source = rows()
        show(rig, source)
        compose.waitForIdle()
        assertEquals(1, count(BankAlertSectionTags.NOTIFY_BANNER))
        compose.onNodeWithTag(BankAlertSectionTags.delete(81), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BankAlertSectionTags.DELETE_CONFIRM, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete 81"), source.calls)
        assertEquals("the delete never asks the helper", emptyList<AuthIdentityFence>(), rig.already)
        assertEquals(emptyList<AuthIdentityFence?>(), rig.results)
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
        val B = AuthIdentityFence("user-b", 2L)

        fun grantFor(owner: AuthIdentityFence, state: PremiumAccessState = PremiumAccessState.PremiumConfirmed) =
            OwnedPremiumAccess(owner.uid, owner.authGeneration, state)

        fun setting(id: Int): AlertSetting {
            val at = Instant.fromEpochSeconds(1_793_000_000L)
            return AlertSetting(id, "user-a", "hana", SupportedCurrency.USD_KRW.code, AlertCondition.ABOVE, 1400.0 + id, true, false,
                at, at, null, null)
        }
    }
}
