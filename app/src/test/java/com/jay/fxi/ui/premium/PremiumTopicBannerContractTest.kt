package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicRecoveryDisplay
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicControlState
import com.jay.fxi.domain.model.TopicDeliveryState
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
import com.jay.fxi.domain.model.TopicSubscriptionState
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C2+b-3 contract (R4c/C2b/b3_design_codex.r2.md, adjusted by b3_review_claude.r1.md). With the connection open
 * and no status line above it, the premium screen shows one topic line for the shown tab's topics, worded and ordered as iOS
 * `a36682f` words them, with "다시 시도" when one of those topics may be retried by hand. A click is checked against the current
 * owner, the accepted tab and the current line before it reaches the runtime, once, with that owner and tab. Nothing is hidden or
 * marked busy by the click itself. The implementation reads but does not edit this file.
 */
class PremiumTopicBannerContractTest {
    private val a1 = AuthIdentityFence("A", 1L)
    private val a2 = AuthIdentityFence("A", 2L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val t0 = Instant.parse("2026-09-30T15:05:00Z")
    private val tether = TopicCatalogue.TETHER
    private val dxy = TopicCatalogue.DXY
    private val usd = "fx:usd-krw"
    private val jpy = "fx:jpy-krw"
    private val eur = "fx:eur-krw"
    private val krx = TopicCatalogue.KRX_FUTURES

    private val loginText = "로그인 상태를 다시 확인해 주세요"
    private val disabledText = "실시간 시세를 일시적으로 제공할 수 없습니다"
    private val unavailableText = "일부 실시간 시세를 사용할 수 없습니다"
    private val delayedText = "실시간 시세 수신이 지연되고 있습니다"

    private fun topicLine(reason: TopicBannerReason, canRetry: Boolean) = PremiumTopicScreenBanner.Topic(reason, canRetry)

    private class Rows : RateRowPreferenceStore {
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }

    private inner class Harness(test: TestScope, tab: FreeTab = FreeTab.USD, d: TopicDisplayState = state()) {
        val display = MutableStateFlow(d)
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, tab))
        var live: AuthIdentityFence? = a1
        val connectionRetries = mutableListOf<TopicDisplayOwner>()
        val topicRetries = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val consumer = PremiumTopicConsumer(display, focus, { live }, Rows(), { _, _ -> }, { connectionRetries += it },
            { o, t -> topicRetries += o to t }, scope)
        fun banner() = consumer.state.value.banner
    }

    private fun rates() = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t0), TopicQuote("upbit", "usdt-krw", 1400.0, t0)))
    private fun ok() = TopicSubscriptionState(desired = true, confirmed = true, receiveGeneration = 1, deliveryState = TopicDeliveryState.HEALTHY)
    private fun refused(reason: TopicRejectionReason) = TopicSubscriptionState(desired = true, rejection = reason)
    private fun degraded() =
        TopicSubscriptionState(desired = true, confirmed = true, receiveGeneration = 1, deliveryState = TopicDeliveryState.DEGRADED)
    private fun topics(
        vararg changes: Pair<String, TopicSubscriptionState>,
        auth: TopicAuthResolution = TopicAuthResolution.RESOLVED,
        failure: TopicWholeRequestFailure? = null,
        control: TopicControlState = TopicControlState.ACKNOWLEDGED
    ) = TopicSubscriptionSnapshot(
        topics = listOf(tether, dxy, usd, jpy, eur).associateWith { ok() } + changes,
        controlState = control,
        wholeFailure = failure,
        authResolution = auth
    )
    private fun state(
        topicState: TopicSubscriptionSnapshot = topics(),
        owner: TopicDisplayOwner? = o1,
        seed: Boolean = false,
        connection: TopicConnectionDisplay = TopicConnectionDisplay.OPEN,
        resolved: Boolean = false,
        recovery: TopicRecoveryDisplay = TopicRecoveryDisplay.None
    ) = TopicDisplayState(owner = owner, rates = rates(), containsSeed = seed, connection = connection, recovery = recovery,
        cachedRefreshResolved = resolved, topicState = topicState)

    @Test fun `B3-00 the four lines are worded as iOS, and only a retryable one carries 다시 시도`() {
        assertEquals("B3-00 words", listOf(loginText, disabledText, unavailableText, delayedText),
            listOf(TopicBannerReason.AUTH_FAILED, TopicBannerReason.TOPICS_DISABLED, TopicBannerReason.TOPIC_UNAVAILABLE,
                TopicBannerReason.DELIVERY_DELAYED).map { topicLine(it, false).text })
        for (reason in TopicBannerReason.entries) {
            assertEquals("B3-00 $reason with a retry", "다시 시도", topicLine(reason, true).action)
            assertNull("B3-00 $reason without", topicLine(reason, false).action)
        }
        assertEquals("B3-00 four reasons", 4, TopicBannerReason.entries.size)
    }

    @Test fun `B3-01 a screen that is not allowed shows no topic line`() = runTest {
        val problem = topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED))
        val h = Harness(this, d = state(problem))
        h.consumer.start(); runCurrent()
        assertEquals("fixture: the line is shown", topicLine(TopicBannerReason.TOPICS_DISABLED, true), h.banner())
        h.display.value = state(problem, owner = null); runCurrent()
        assertEquals("B3-01 no owner", PremiumTopicScreenState.NONE, h.consumer.state.value)
        h.display.value = state(problem); runCurrent()
        h.live = a2
        assertEquals("B3-01 the live identity moved", PremiumTopicScreenState.NONE, h.consumer.currentState())
        h.live = a1
        h.focus.value = OwnedTopicFocus(a2, FreeTab.USD); runCurrent()
        assertEquals("B3-01 focus for another sign-in", PremiumTopicScreenState.NONE, h.consumer.state.value)
        h.focus.value = OwnedTopicFocus(a1, FreeTab.USD)
        h.display.value = state(topics()); runCurrent()
        assertNull("B3-01 nothing left over from before", h.banner())
        h.display.value = state(problem, owner = TopicDisplayOwner(a1, 2L)); runCurrent()
        assertEquals("B3-01 a new grant turn shows its own line", topicLine(TopicBannerReason.TOPICS_DISABLED, true), h.banner())
        h.scope.cancel()
        assertEquals("B3-01 ended", PremiumTopicScreenState.NONE, h.consumer.currentState())
    }

    @Test fun `B3-02 the topic line comes after every connection and stored-price line, and only while the connection is open`() = runTest {
        val problem = topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED))
        val line = topicLine(TopicBannerReason.TOPICS_DISABLED, true)
        val cases = listOf(
            state(problem, connection = TopicConnectionDisplay.OFFLINE, seed = true) to PremiumTopicScreenBanner.Offline,
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED, recovery = TopicRecoveryDisplay.Exhausted) to
                PremiumTopicScreenBanner.Failed,
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED, seed = true) to PremiumTopicScreenBanner.RefreshingCached,
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED, recovery = TopicRecoveryDisplay.Connecting) to
                PremiumTopicScreenBanner.Connecting,
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED, recovery = TopicRecoveryDisplay.Reconnecting(2)) to
                PremiumTopicScreenBanner.Reconnecting(2),
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED) to null,
            state(problem, seed = true) to PremiumTopicScreenBanner.RefreshingCached,
            state(problem, seed = true, resolved = true) to line,
            state(problem) to line,
            state(topics()) to null
        )
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        for ((d, expected) in cases) {
            h.display.value = d; runCurrent()
            assertEquals("B3-02 ${d.connection} ${d.recovery} seed=${d.containsSeed} resolved=${d.cachedRefreshResolved}",
                expected, h.banner())
            if (expected is PremiumTopicScreenBanner.Topic) assertNull("B3-02 no time on a topic line", h.consumer.state.value.updatedText)
        }
        h.scope.cancel()
    }

    @Test fun `B3-03 the first reason that applies to the shown tab's desired topics is shown`() = runTest {
        val cases = listOf(
            topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED), dxy to refused(TopicRejectionReason.TOPIC_UNAVAILABLE),
                auth = TopicAuthResolution.FAILED) to TopicBannerReason.AUTH_FAILED,
            topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED), dxy to refused(TopicRejectionReason.TOPIC_UNAVAILABLE)) to
                TopicBannerReason.TOPICS_DISABLED,
            topics(usd to degraded(), dxy to refused(TopicRejectionReason.TOPIC_UNAVAILABLE)) to TopicBannerReason.TOPIC_UNAVAILABLE,
            topics(usd to degraded()) to TopicBannerReason.DELIVERY_DELAYED,
            topics(usd to TopicSubscriptionState(desired = false, rejection = TopicRejectionReason.TOPICS_DISABLED)) to null,
            topics(usd to TopicSubscriptionState(desired = false), dxy to TopicSubscriptionState(desired = false),
                auth = TopicAuthResolution.FAILED) to null,
            topics(jpy to refused(TopicRejectionReason.TOPICS_DISABLED), eur to degraded(),
                tether to refused(TopicRejectionReason.TOPIC_UNAVAILABLE)) to null
        )
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        for ((snapshot, reason) in cases) {
            h.display.value = state(snapshot); runCurrent()
            assertEquals("B3-03 $snapshot", reason, (h.banner() as? PremiumTopicScreenBanner.Topic)?.reason)
            if (reason == null) assertNull("B3-03 no line at all", h.banner())
        }
        h.scope.cancel()
    }

    @Test fun `B3-04 a tab's topics are its price rows and its graph's live inputs, whatever rows are hidden`() = runTest {
        val scope = mapOf(
            FreeTab.TETHER to setOf(tether, dxy), FreeTab.USD to setOf(usd, dxy), FreeTab.JPY to setOf(jpy), FreeTab.EUR to setOf(eur),
            FreeTab.NEWS to emptySet()
        )
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        for (topic in listOf(tether, dxy, usd, jpy, eur, krx)) for (tab in FreeTab.entries) {
            h.focus.value = OwnedTopicFocus(a1, tab)
            h.display.value = state(topics(topic to degraded())); runCurrent()
            val expected = if (topic in scope.getValue(tab)) topicLine(TopicBannerReason.DELIVERY_DELAYED, true) else null
            assertEquals("B3-04 $topic on $tab", expected, h.banner())
        }
        h.focus.value = OwnedTopicFocus(a1, FreeTab.USD)
        h.display.value = state(topics(usd to degraded())); runCurrent()
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("kb"), listOf("kb"), setOf("kb")); runCurrent()
        assertEquals("B3-04 a hidden row narrows nothing", topicLine(TopicBannerReason.DELIVERY_DELAYED, true), h.banner())
        h.scope.cancel()
    }

    @Test fun `B3-05 the retry is offered when one of the tab's topics may be retried by hand, whichever reason is shown`() = runTest {
        val cases = listOf(
            topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED)) to topicLine(TopicBannerReason.TOPICS_DISABLED, true),
            topics(usd to refused(TopicRejectionReason.TOPIC_UNAVAILABLE)) to topicLine(TopicBannerReason.TOPIC_UNAVAILABLE, false),
            topics(usd to refused(TopicRejectionReason.TOPIC_UNAVAILABLE), dxy to degraded()) to
                topicLine(TopicBannerReason.TOPIC_UNAVAILABLE, true),
            topics(usd to degraded(), failure = TopicWholeRequestFailure.TemporarilyUnavailable(30L), control = TopicControlState.FAILED) to
                topicLine(TopicBannerReason.DELIVERY_DELAYED, true),
            topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED), failure = TopicWholeRequestFailure.TemporarilyUnavailable(30L),
                control = TopicControlState.FAILED) to topicLine(TopicBannerReason.TOPICS_DISABLED, false),
            topics(auth = TopicAuthResolution.FAILED) to topicLine(TopicBannerReason.AUTH_FAILED, true)
        )
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        for ((snapshot, expected) in cases) {
            h.display.value = state(snapshot); runCurrent()
            assertEquals("B3-05 $snapshot", expected, h.banner())
        }
        h.scope.cancel()
    }

    @Test fun `B3-06 a click reaches the runtime once with its owner and tab, and only while that line still offers it`() = runTest {
        val problem = topics(usd to refused(TopicRejectionReason.TOPICS_DISABLED))
        val h = Harness(this, d = state(problem))
        h.consumer.start(); runCurrent()
        h.consumer.retryTopics(o1, FreeTab.USD); runCurrent()
        assertEquals("B3-06 the current line", listOf(o1 to FreeTab.USD), h.topicRetries)

        h.consumer.retryTopics(TopicDisplayOwner(a1, 9L), FreeTab.USD)
        h.consumer.retryTopics(o1, FreeTab.EUR)
        h.live = a2
        h.consumer.retryTopics(o1, FreeTab.USD)
        h.live = a1
        runCurrent()
        assertEquals("B3-06 a stale owner, another tab, a moved sign-in", 1, h.topicRetries.size)

        // Read now, not as last published: the collector has not run since either change.
        h.display.value = state(topics())
        h.consumer.retryTopics(o1, FreeTab.USD)
        assertEquals("B3-06 the problem already ended", 1, h.topicRetries.size)
        runCurrent()
        h.display.value = state(problem)
        h.consumer.retryTopics(o1, FreeTab.USD)
        assertEquals("B3-06 a problem not yet published", 2, h.topicRetries.size)
        runCurrent()

        for (d in listOf(
            state(topics(usd to refused(TopicRejectionReason.TOPIC_UNAVAILABLE))),
            state(problem, connection = TopicConnectionDisplay.OFFLINE),
            state(problem, seed = true),
            state(problem, connection = TopicConnectionDisplay.DISCONNECTED, recovery = TopicRecoveryDisplay.Exhausted)
        )) {
            h.display.value = d; runCurrent()
            h.consumer.retryTopics(o1, FreeTab.USD); runCurrent()
            assertEquals("B3-06 no retry offered: ${h.banner()}", 2, h.topicRetries.size)
        }
        h.display.value = state(problem)
        h.focus.value = OwnedTopicFocus(a1, FreeTab.EUR); runCurrent()
        h.consumer.retryTopics(o1, FreeTab.USD); runCurrent()
        assertEquals("B3-06 the tab moved on", 2, h.topicRetries.size)
        assertTrue("B3-06 the connection retry is not used", h.connectionRetries.isEmpty())
        h.scope.cancel()
    }

    @Test fun `B3-07 after a click the line follows the published state, with no busy line of its own`() = runTest {
        val h = Harness(this, d = state(topics(usd to degraded())))
        h.consumer.start(); runCurrent()
        val line = topicLine(TopicBannerReason.DELIVERY_DELAYED, true)
        h.consumer.retryTopics(o1, FreeTab.USD); runCurrent()
        assertEquals("fixture: forwarded", 1, h.topicRetries.size)
        assertEquals("B3-07 not hidden by the click", line, h.banner())
        assertEquals("B3-07 nor when read now", line, h.consumer.currentState().banner)
        val revalidating = TopicSubscriptionState(desired = true, confirmed = true, receiveGeneration = 1,
            deliveryState = TopicDeliveryState.REVALIDATING, revalidationAttempt = 1)
        h.display.value = state(topics(usd to revalidating, control = TopicControlState.PENDING)); runCurrent()
        assertNull("B3-07 a revalidation in flight has no line", h.banner())
        h.display.value = state(topics(usd to degraded())); runCurrent()
        assertEquals("B3-07 its silence brings the line back", line, h.banner())
        h.display.value = state(topics(auth = TopicAuthResolution.REFRESHING, control = TopicControlState.PENDING)); runCurrent()
        assertNull("B3-07 a refresh alone has no line", h.banner())
        h.display.value = state(topics()); runCurrent()
        assertNull("B3-07 healthy", h.banner())
        h.scope.cancel()
    }
}
