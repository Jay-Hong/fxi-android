package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.ui.alert.BankHistoryController
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryWiring
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (c) contract (`R4c/S7/s7c_api_proposal.claude.r3.md` §2.1–2.2, §4 S7C_C01·L02): the consumer's screen
 * listener is told the published screen at once and after every publication, never on a path that publishes nothing, and an
 * unregistering listener is not told again; through it the bank history controller ends its session at the first published
 * loss of its owner or tab, also when the screen returns before anything else runs. The implementation thread reads but does
 * not edit this file.
 */
class PremiumTopicConsumerScreenListenerTest {
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private val t0 = Instant.parse("2026-09-30T15:05:00Z")

    private class Store : RateRowPreferenceStore {
        override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> = emptyMap()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }

    private fun display(owner: TopicDisplayOwner?) = TopicDisplayState(owner,
        TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t0), TopicQuote("kb", "jpy-krw", 930.0, t0))),
        false, TopicConnectionDisplay.OPEN, cachedRefreshResolved = false)

    private inner class Harness(val test: TestScope) {
        val display = MutableStateFlow(display(o1))
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val consumer = PremiumTopicConsumer(display, focus, { a1 }, Store(), { _, _ -> }, {}, { _, _ -> }, scope)
        val told = mutableListOf<PremiumTopicScreenState>()
        fun settle() = test.runCurrent()
    }

    @Test fun S7C_C01a_registrationTellsTheCurrentValue_thenEachPublication_sameValuesToo() = runTest {
        val h = Harness(this)
        h.consumer.addScreenListener { h.told += it }
        assertEquals("told at once, before start", listOf(PremiumTopicScreenState.NONE), h.told)
        h.consumer.start()
        h.settle()
        assertEquals("premise: the started screen is published", o1, h.consumer.state.value.ui.owner)
        assertEquals("the last told is the published screen", h.consumer.state.value, h.told.last())
        val before = h.told.size
        h.consumer.currentState()
        h.consumer.currentState()
        assertEquals("each publication, the same value included", before + 2, h.told.size)
        h.focus.value = OwnedTopicFocus(a1, FreeTab.JPY)
        h.settle()
        assertEquals("a new tab is told", FreeTab.JPY, h.told.last().ui.selectedTab)
        h.scope.cancel()
    }

    @Test fun S7C_C01b_aPathThatPublishesNothingTellsNothing() = runTest {
        val h = Harness(this)
        h.consumer.addScreenListener { h.told += it }
        h.told.clear()
        h.consumer.currentState()
        assertEquals("not started: nothing published, nothing told", emptyList<PremiumTopicScreenState>(), h.told)
        h.consumer.start()
        h.settle()
        h.scope.cancel()
        h.told.clear()
        h.consumer.currentState()
        assertEquals("scope ended: nothing published, nothing told", emptyList<PremiumTopicScreenState>(), h.told)
    }

    @Test fun S7C_C01c_aListenerUnregisteredDuringANotificationIsNotToldAgain() = runTest {
        val h = Harness(this)
        h.consumer.start()
        h.settle()
        val second = mutableListOf<PremiumTopicScreenState>()
        var unregisterSecond: () -> Unit = {}
        val unregisterFirst = h.consumer.addScreenListener { if (h.told.size < 10) { h.told += it; if (h.told.size > 1) unregisterSecond() } }
        unregisterSecond = h.consumer.addScreenListener { second += it }
        assertEquals("premise: both told at registration", 1 to 1, h.told.size to second.size)
        h.consumer.currentState()
        assertEquals("the first ran and unregistered the second before it was told", 2 to 1, h.told.size to second.size)
        h.consumer.currentState()
        assertEquals("and the second stays unregistered", 3 to 1, h.told.size to second.size)
        unregisterFirst()
        h.consumer.currentState()
        assertEquals("the first is gone too", 3, h.told.size)
        h.scope.cancel()
    }

    /**
     * S7C_C01d (Codex r3 D01/D05): the same callback registered twice is two independent registrations; unregistering one during
     * a notification skips that one only, and calling its unregister again touches no other.
     */
    @Test fun S7C_C01d_twoRegistrationsOfOneCallbackAreIndependent() = runTest {
        val h = Harness(this)
        h.consumer.start()
        h.settle()
        var unregisterFirst: () -> Unit = {}
        var removeDuring = false
        val callback: (PremiumTopicScreenState) -> Unit = { h.told += it; if (removeDuring) { removeDuring = false; unregisterFirst() } }
        unregisterFirst = h.consumer.addScreenListener(callback)
        val unregisterSecond = h.consumer.addScreenListener(callback)
        assertEquals("premise: each told at its registration", 2, h.told.size)
        h.consumer.currentState()
        assertEquals("both registrations told once", 4, h.told.size)
        removeDuring = true
        h.consumer.currentState()
        assertEquals("the first, told, unregisters itself; the second is still told once", 6, h.told.size)
        h.consumer.currentState()
        assertEquals("from then on the second only", 7, h.told.size)
        unregisterFirst()
        unregisterFirst()
        h.consumer.currentState()
        assertEquals("unregistering the first again leaves the second", 8, h.told.size)
        unregisterSecond()
        h.consumer.currentState()
        assertEquals("and the second can go too", 8, h.told.size)
        h.scope.cancel()
    }

    // ---- The bank history controller through the real consumer (S7C_L02) -------------------------------------------------

    private class Call(val guard: () -> Boolean) {
        val answer = CompletableDeferred<AuthBoundResult<List<AlertHistoryItem>>>()
    }

    private fun controller(h: Harness, reads: MutableList<Call>, owner: TopicDisplayOwner = o1) = BankHistoryController(
        owner, FreeTab.USD,
        BankHistoryWiring(object : BankHistoryAccess {
            private var n = 0L
            override fun acquire(owner: TopicDisplayOwner) = TopicGrantToken(++n).let {
                BankHistoryUse(owner, TopicSessionFence(owner.identity, "epoch", it), TopicUseLifetime(it, 0))
            }
            override fun admits(use: BankHistoryUse) = true
        }, BankHistoryRead { _, _, guard -> Call(guard).also { reads += it }.answer.await() }, MutableStateFlow(0L), MutableStateFlow(0L)),
        { h.consumer.state.value.ui.let { it.owner to it.selectedTab } }, h.consumer::addScreenListener, h.scope)

    @Test fun S7C_L02_theConsumersLossAndReturnPublicationsEndTheSession() = runTest {
        val cases: List<Pair<String, Harness.() -> Pair<() -> Unit, () -> Unit>>> = listOf(
            "USD-JPY-USD" to { { focus.value = OwnedTopicFocus(a1, FreeTab.JPY) } to { focus.value = OwnedTopicFocus(a1, FreeTab.USD) } },
            "USD-NEWS-USD" to { { focus.value = OwnedTopicFocus(a1, FreeTab.NEWS) } to { focus.value = OwnedTopicFocus(a1, FreeTab.USD) } },
            "owner 1-2-1" to { { display.value = display(o2) } to { display.value = display(o1) } }
        )
        for ((label, moves) in cases) {
            val h = Harness(this)
            h.consumer.start()
            h.settle()
            val reads = mutableListOf<Call>()
            val c = controller(h, reads)
            c.open()
            h.settle()
            val first = reads.single()
            assertTrue("premise($label): loading under a standing guard", first.guard())
            val (away, back) = moves(h)
            away()
            h.settle()
            assertNull("$label: the loss publication ended the session", c.holder.state.value)
            back()
            h.settle()
            assertEquals("premise($label): the screen is back", o1 to FreeTab.USD, h.consumer.state.value.ui.let { it.owner to it.selectedTab })
            assertFalse("$label: the old guard stays down", first.guard())
            first.answer.complete(AuthBoundResult({}, Result.success(emptyList())))
            h.settle()
            assertNull("$label: nothing comes back", c.holder.state.value)
            assertEquals("$label: the return read nothing", 1, reads.size)
            c.open()
            h.settle()
            assertEquals("$label: only the user's next open reads", 2, reads.size)
            c.close()
            h.scope.cancel()
        }
    }
}
