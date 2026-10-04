package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
import com.jay.fxi.domain.model.TopicSubscriptionState
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned R4-c C3c-2 route contract (R4c/C3c2/design_codex.r1.md + r2 agreed), run on a device with a real
 * PremiumTopicConsumer on the main thread, a real NewsViewModel over a fake repository and a settings fixture. The route starts the
 * consumer, forwards confirmed user choices through it, tells it about sign-in changes, draws nothing when inactive, and connects
 * news and settings without turning their actions into rate selections. The implementation reads but does not edit this file.
 */
class PremiumTopicRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val b2 = AuthIdentityFence("B", 2L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private fun rates() = TopicRates().merge(listOf(
        TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("hana", "usd-krw", 1391.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))

    private class FakeNews(var items: List<NewsItem> = emptyList(), var fail: Boolean = false,
                           var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null) : NewsRepository {
        var fetches = 0
        override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> {
            fetches += 1
            gate?.await()
            return if (fail) throw IOException("offline") else items
        }
        override suspend fun saveCache(items: List<NewsItem>) = Unit
        override suspend fun loadCache(): List<NewsItem>? = null
    }
    private class MemoryRows : RateRowPreferenceStore {
        val writes = mutableListOf<Triple<String, RateRowList, RateRowPreference>>()
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) { writes += Triple(uid, list, preference) }
    }

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    private var live: AuthIdentityFence? = a1
    private val rows = MemoryRows()
    private val selects = mutableListOf<Pair<AuthIdentityFence, FreeTab>>()
    private val topicRetries = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumer = PremiumTopicConsumer(display, focus, { live }, rows,
        { o, tab -> selects += o to tab; focus.value = OwnedTopicFocus(o, tab) }, { }, { o, tab -> topicRetries += o to tab }, scope)
    private var identity by mutableStateOf<AuthIdentityFence?>(a1)
    private var active by mutableStateOf(true)
    private var signOuts = 0
    @After fun tearDown() = scope.cancel()

    private var tester: androidx.compose.ui.test.junit4.StateRestorationTester? = null

    @androidx.compose.runtime.Composable
    private fun route(vm: NewsViewModel) {
        PremiumTopicRoute(
            consumer = consumer, identity = identity, isActive = active, newsViewModel = vm,
            userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = { signOuts += 1 },
            settingsContent = { u, signOut, dismiss ->
                Column {
                    Text("설정 fixture ${u?.uid}")
                    Button(onClick = signOut) { Text("fixture 로그아웃") }
                    Button(onClick = dismiss) { Text("fixture 닫기") }
                }
            })
    }

    private fun show(news: FakeNews = FakeNews(), restorable: Boolean = false) {
        val vm = NewsViewModel(news)
        if (restorable) {
            val t = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
            tester = t
            t.setContent { route(vm) }
        } else {
            rule.setContent { route(vm) }
        }
        rule.waitForIdle()
    }

    @Test fun c3c2_08a_theRouteStartsItsConsumer_andAClickReachesTheRuntimeOnce() {
        show()
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertExists()
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).performClick(); rule.waitForIdle()
        assertEquals("C3c2-08 one selection through the consumer", listOf(a1 to FreeTab.TETHER), selects)
    }

    @Test fun c3c2_08b_aSignInChangeClearsTheEarlierScreen_andInactiveDrawsAndDoesNothing() {
        show()
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertExists()
        live = b2
        identity = b2; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertDoesNotExist()
        live = a1
        identity = a1; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertExists()
        active = false; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.row("kb"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.EUR)).assertDoesNotExist()
        assertTrue("C3c2-08 nothing selected", selects.isEmpty())
    }

    @Test fun c3c2_03b_anAppliedSheetChangesTheShownRows() {
        show()
        rule.onNodeWithTag(PremiumTopicTags.customize(RateRowList.FX_BANKS)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("완료").assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("하나은행 숨기기")).performClick()
        rule.onNodeWithText("완료").performClick(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.row("hana"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.row("kb"), useUnmergedTree = true).assertExists()
    }

    @Test fun c3c2_05_newsShowsItsItems_orItsErrorWithRetry_andNeverSelectsARate() {
        focus.value = OwnedTopicFocus(a1, FreeTab.NEWS)
        show(FakeNews(items = listOf(NewsItem("n1", "환율 뉴스 제목", null, "인포맥스", "external_link", t))))
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("환율 뉴스 제목")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("C3c2-05 news selects no rate", selects.isEmpty())
    }

    @Test fun c3c2_05b_aNewsErrorOffersRetry_whichFetchesAgain_withoutARateSelection() {
        focus.value = OwnedTopicFocus(a1, FreeTab.NEWS)
        val news = FakeNews(fail = true)
        show(news)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("뉴스를 불러올 수 없습니다")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("C3c2-05 one fetch on attach", 1, news.fetches)
        rule.onNodeWithText("다시 시도").performClick(); rule.waitForIdle()
        rule.waitUntil(5_000) { news.fetches == 2 }
        assertTrue("C3c2-05 retry selects no rate", selects.isEmpty())
    }

    // A failing fetch never sets the cooldown (NewsViewModel records lastFetchAt on success only), so each call is observable.
    @Test fun c3c2_05c_aForegroundReturnFetchesOnlyWhileTheNewsPageIsShown() {
        focus.value = OwnedTopicFocus(a1, FreeTab.NEWS)
        val news = FakeNews(fail = true)
        show(news)
        rule.waitUntil(5_000) { news.fetches == 1 }
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { news.fetches == 2 }
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.EUR)).performClick(); rule.waitForIdle()
        val before = news.fetches
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals("C3c2-05 no news fetch while another page is shown", before, news.fetches)
    }

    @Test fun c3c2_05d_loadingAndEmptyNewsAreShown() {
        focus.value = OwnedTopicFocus(a1, FreeTab.NEWS)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        show(FakeNews(gate = gate))
        rule.onNodeWithText("뉴스 불러오는 중...").assertExists()
        gate.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("뉴스가 없습니다")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun c3c2_05e_anArticleOpensItsDetail_closedByButtonOrBack_andNotKeptAcrossAnOwnerChangeOrDeactivation() {
        focus.value = OwnedTopicFocus(a1, FreeTab.NEWS)
        show(FakeNews(items = listOf(NewsItem("n1", "상세 기사", "about:blank", "인포맥스", "external_link", t))), restorable = true)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("상세 기사")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("상세 기사").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        rule.onNodeWithText("상세 기사").performClick(); rule.waitForIdle()
        androidx.test.espresso.Espresso.pressBack(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        rule.onNodeWithText("상세 기사").performClick(); rule.waitForIdle()
        display.value = TopicDisplayState(TopicDisplayOwner(a1, 2L), rates(), false, TopicConnectionDisplay.OPEN); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        tester!!.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        display.value = TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        rule.onNodeWithText("상세 기사").performClick(); rule.waitForIdle()
        active = false; rule.waitForIdle()
        active = true; rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("닫기")).assertDoesNotExist()
        assertTrue("C3c2-05 news selects no rate", selects.isEmpty())
    }

    @Test fun c3c2_06b_settingsOpenWithTheUser_signOutAndCloseReachTheirCallbacks() {
        show()
        rule.onNodeWithTag(PremiumTopicTags.SETTINGS).performClick(); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertExists()
        rule.onNodeWithText("fixture 로그아웃").performClick(); rule.waitForIdle()
        assertEquals("C3c2-06 sign-out", 1, signOuts)
        rule.onNodeWithText("fixture 닫기").performClick(); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertDoesNotExist()
    }

    @Test fun c3c2_07d_aNewOwnerClosesOpenSettings_andTheyDoNotComeBackAfterARestoreOrForTheEarlierOwner() {
        show(restorable = true)
        rule.onNodeWithTag(PremiumTopicTags.SETTINGS).performClick(); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertExists()
        display.value = TopicDisplayState(TopicDisplayOwner(a1, 2L), rates(), false, TopicConnectionDisplay.OPEN); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertDoesNotExist()
        tester!!.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertDoesNotExist()
        display.value = TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN); rule.waitForIdle()
        rule.onNodeWithText("설정 fixture A").assertDoesNotExist()
    }

    // R4-c C2+b-3 (R4c/C2b/b3_design_codex.r2.md B3-09, Claude-owned): the topic line's retry reaches the runtime through the
    // consumer once, with the shown owner and tab, and an inactive route draws and forwards nothing.
    @Test fun b3_09b_theTopicRetryReachesTheConsumerOnce_andAnInactiveRouteForwardsNothing() {
        val problem = TopicSubscriptionSnapshot(topics = mapOf(
            "fx:usd-krw" to TopicSubscriptionState(desired = true, rejection = TopicRejectionReason.TOPICS_DISABLED),
            "dxy:spot" to TopicSubscriptionState(desired = true, confirmed = true)))
        display.value = TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN, topicState = problem)
        show()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER) and hasText("실시간 시세를 일시적으로 제공할 수 없습니다"), useUnmergedTree = true)
            .assertExists()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_ACTION) and hasText("다시 시도")).performClick(); rule.waitForIdle()
        assertEquals("B3-09b one topic retry with the shown owner and tab", listOf(o1 to FreeTab.USD), topicRetries)
        active = false; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.BANNER).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.BANNER_ACTION).assertDoesNotExist()
        assertEquals("B3-09b nothing more", 1, topicRetries.size)
    }

    // R4-c C2+b-3 (R4c/C2b/b3_survivor_codex.r1.md, mutant R01; Claude-owned): the route reads the screen again before handing the
    // click to the consumer, which reads it once more. A resolution published while the first read finishes is seen by the second, so
    // a line that already ended forwards nothing. The display change is fixed at the first read's last live-identity read, standing in
    // for a publication from the runtime's own dispatcher.
    @Test fun b3_09c_aResolutionPublishedDuringTheFirstReadIsSeenBeforeForwarding() {
        val problem = TopicSubscriptionSnapshot(topics = mapOf(
            "fx:usd-krw" to TopicSubscriptionState(desired = true, rejection = TopicRejectionReason.TOPICS_DISABLED)))
        val resolved = TopicSubscriptionSnapshot(topics = mapOf(
            "fx:usd-krw" to TopicSubscriptionState(desired = true, confirmed = true)))
        val input = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN, topicState = problem))
        val forwarded = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()
        // Non-immediate Main, as the production owner uses.
        val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var armed = false
        var reads = 0
        val c = PremiumTopicConsumer(
            display = input,
            focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD)),
            liveIdentity = {
                if (armed && ++reads == 2) {
                    armed = false
                    input.value = input.value.copy(topicState = resolved)
                }
                a1
            },
            rowPreferenceStore = rows,
            selectTab = { _, _ -> },
            retryConnection = {},
            retryTopics = { owner, tab -> forwarded += owner to tab },
            scope = routeScope
        )
        try {
            val vm = NewsViewModel(FakeNews())
            rule.setContent { PremiumTopicRoute(c, a1, true, vm, null, {}) }
            rule.waitForIdle()
            val click = requireNotNull(
                rule.onNodeWithTag(PremiumTopicTags.BANNER_ACTION).fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action
            )
            rule.runOnIdle {
                armed = true
                assertTrue("fixture: the click was handled", click())
            }
            rule.waitForIdle()
            assertTrue("fixture: the resolution was published during the first read", !armed)
            assertEquals("B3-09c a line that ended while it was read forwards nothing", 0, forwarded.size)
        } finally {
            routeScope.cancel()
        }
    }
}
