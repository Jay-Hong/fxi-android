package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.LEGACY_RATES_FRAME
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.TETHER
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.U1
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.USD
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.decoder
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.tetherFrame
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.ui.premium.PremiumTopicScreenBanner
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.premium.TopicBannerReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C4 contract for the process owner (R4c/C4/design_codex.r3.md, JVM contracts START-ADMISSION, INPUTS,
 * IDENTITY-UNMOUNTED, TABS-LIFETIME, OFFLINE-SEED and the dynamic half of LEGACY-ZERO; r4 adds BACKGROUND: the session connects
 * only after the process's first foreground, R4c/C4/review_codex.impl.r2.md). The first identity row runs without a foreground, so
 * it also shows identity reaching the consumer before activation. Every test joins the production
 * factory, runtime, owner and consumer through [C4OwnerHarness]; only auth, server, stores, clock and platform inputs are
 * replaced. The identity tests never call `consumer.currentState()` or a route effect after a change: either could stand in for
 * a missing owner notification. They judge by the store's cancellation and re-read and by what the consumer publishes. That a
 * restore's late result is not adopted, and that a save already inside the store is not withdrawn, is the consumer's C3c-1
 * contract; here the owner's part is that the change reaches it. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicRuntimeOwnerContractTest {

    private companion object {
        val A = AuthIdentityFence("ua", 1L)
        val B = AuthIdentityFence("ub", 1L)
        val B2 = AuthIdentityFence("ub", 2L)
    }

    private fun C4OwnerHarness.shownOwner() = checkNotNull(screen.ui.owner) { "fixture: a premium screen is shown" }

    private fun C4OwnerHarness.hasPrices() = screen.ui.rateSections.any { it.rows.isNotEmpty() }

    @Test
    fun `C4-J-START-ADMISSION the owner creates, starts and installs once per process and hands out one consumer`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        assertThrows("ready before start", IllegalStateException::class.java) { h.owner.requireReady() }
        assertThrows("a consumer before start", IllegalStateException::class.java) { h.owner.consumer }
        h.settle(1_000)
        assertEquals("a runtime before start", 0, h.scopes.size)

        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.owner.requireReady()
        val consumer = h.owner.consumer
        h.settle(100)
        val fenceSubscribers = h.fences.subscribers
        h.owner.start()
        h.settle(100)

        assertEquals("one runtime per process", 1, h.scopes.size)
        assertSame("one consumer per process", consumer, h.owner.consumer)
        assertEquals("identity forwarding installed again", fenceSubscribers, h.fences.subscribers)
        assertEquals("one foreground forwarding", 1, h.foregroundObservers.size)
        assertEquals("one socket", 1, h.wire.requests.size)
        h.owner.requireReady()
    }

    // C4 contract r4 (R4c/C4/review_codex.impl.r2.md item 2, device finding F3): a process the system starts in the background — a
    // push, a scheduled job — installs, is ready and follows identity, but its session connects and bootstraps nothing until the
    // process's first foreground. Later returns follow the existing policy.
    @Test
    fun `C4-J-BACKGROUND a process started without the foreground is ready but connects and bootstraps nothing`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.settle(5_000)
        h.online.value = false
        h.settle(100)
        h.online.value = true
        h.settle(5_000)
        h.owner.requireReady()
        assertEquals("a socket before the first foreground", 0, h.wire.requests.size)
        assertEquals("a bootstrap before the first foreground", emptyList<String>(), h.asked())
        assertEquals("one runtime", 1, h.scopes.size)
    }

    @Test
    fun `C4-J-BACKGROUND the first foreground starts the connection once, and a later return opens nothing over a live one`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.settle(1_000)
        h.foreground(true)
        h.settle(100)
        assertEquals("the first foreground did not connect", 1, h.wire.requests.size)
        h.wire.open()
        h.settle(3_000)
        assertTrue("the first foreground did not bootstrap", h.asked().isNotEmpty())
        h.foreground(false)
        h.settle(1_000)
        h.foreground(true)
        h.settle(100)
        assertEquals("a later return opened another socket over a live one", 1, h.wire.requests.size)
        assertEquals("another runtime", 1, h.scopes.size)
    }

    @Test
    fun `C4-J-BACKGROUND offline at the first foreground connects once the network returns`() = runTest {
        val h = C4OwnerHarness(this, online = false)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true)
        h.settle(5_000)
        assertEquals("a socket while offline", 0, h.wire.requests.size)
        h.online.value = true
        h.settle(100)
        assertEquals("the network's return did not connect", 1, h.wire.requests.size)
    }

    @Test
    fun `C4-J-INPUTS the current offline value and every later change reach the one runtime`() = runTest {
        val h = C4OwnerHarness(this, online = false)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(10_000)
        assertEquals("offline: a socket", 0, h.wire.requests.size)
        assertEquals("offline: a bootstrap", emptyList<String>(), h.asked())
        assertEquals("offline: shown as offline", PremiumTopicScreenBanner.Offline, h.screen.banner)

        h.online.value = true
        h.settle(100)
        assertEquals("going online did not reach the runtime", 1, h.wire.requests.size)
        h.settle(3_000)
        assertTrue("no bootstrap after going online", h.asked().isNotEmpty())

        h.online.value = false
        h.settle(100)
        h.online.value = true
        h.settle(100)
        h.online.value = true
        h.settle(100)
        assertEquals("a repeated input created a runtime", 1, h.scopes.size)
    }

    @Test
    fun `C4-J-INPUTS a current online value reaches the runtime at the first foreground`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        assertEquals("the current online value was not passed at the first foreground", 1, h.wire.requests.size)
    }

    // C4 contract r3 (R4c/C4/review_codex.impl.r1.md item 3), r4: judged before Main runs, so an activation that leaves the current
    // value to the Main collector is told apart from one that passes it itself.
    @Test
    fun `C4-J-INPUTS the current online value reaches the runtime before Main runs`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertFalse("fixture: Main still holds the owner's work", h.main.idle)
        assertEquals("the current online value waited for Main", 1, h.wire.requests.size)
    }

    // C4 contract r3 (R4c/C4/review_codex.impl.r1.md item 1, device finding F1): with the ladder spent, the network going away
    // shows offline, and coming back reconnects without a manual retry; a repeated online value opens nothing more.
    @Test
    fun `C4-J-INPUTS offline and back after the ladder is spent shows offline, then reconnects by itself`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.drop()
        h.settle(1)
        for (n in 1..5) {
            h.settle(1_600L * n + 10)
            h.wire.drop()
            h.settle(1)
        }
        assertEquals("fixture: the ladder is spent", PremiumTopicScreenBanner.Failed, h.screen.banner)
        val spent = h.wire.requests.size
        h.online.value = false
        h.settle(100)
        assertEquals("going offline is not shown", PremiumTopicScreenBanner.Offline, h.screen.banner)
        assertEquals("going offline opened a socket", spent, h.wire.requests.size)
        h.online.value = true
        h.settle(100)
        assertEquals("coming back did not reconnect by itself", spent + 1, h.wire.requests.size)
        h.online.value = true
        h.settle(100)
        assertEquals("a repeated online value opened another socket", spent + 1, h.wire.requests.size)
    }

    @Test
    fun `C4-J-INPUTS coming back to the foreground reaches the runtime's reconnection`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(1)
        h.wire.drop()
        h.settle(100)
        assertEquals("fixture: the reconnection waits on its rung", 1, h.wire.requests.size)
        h.foreground(false)
        h.settle(1)
        assertEquals("leaving the foreground opened a socket", 1, h.wire.requests.size)
        h.foreground(true)
        h.settle(1)
        assertEquals("coming back did not reconnect at once", 2, h.wire.requests.size)
        assertEquals("a foreground input created a runtime", 1, h.scopes.size)
    }

    @Test
    fun `C4-J-IDENTITY-UNMOUNTED a sign-in change reaches the unmounted consumer through Main, not inside the callback`() = runTest {
        val h = C4OwnerHarness(this, live = A, online = false)
        h.fakeIssuer.grant = null // no grant: the display stays empty
        h.tabs.gate = CompletableDeferred() // no restored tab: the focus stays null
        h.prefs.readGate = CompletableDeferred() // every preference read stays pending
        h.owner.start()
        h.settle(1_000)
        assertEquals("fixture: nothing is drawn", PremiumTopicScreenState.NONE, h.screen)
        assertEquals("fixture: start bound A", listOf("ua"), h.prefs.reads)
        val aRead = h.prefs.readJobs.single()

        h.consumerIdentityReadsOnMain.clear()
        val dispatched = h.main.dispatched
        h.signIn(B)
        assertFalse("the callback notified the consumer itself", aRead.isCancelled)
        assertEquals("the callback read B itself", listOf("ua"), h.prefs.reads)
        assertEquals("the callback read the consumer identity itself", emptyList<Boolean>(), h.consumerIdentityReadsOnMain)
        assertTrue("the callback enqueued nothing for Main", h.main.dispatched > dispatched)
        h.settle()
        assertTrue("the consumer was not notified", h.consumerIdentityReadsOnMain.isNotEmpty())
        assertTrue("the consumer identity was read outside Main", h.consumerIdentityReadsOnMain.all { it })
        assertTrue("A's pending restore was not cancelled", aRead.isCancelled)
        assertEquals("A's read", listOf("ua"), h.prefs.cancelled)
        assertEquals("B was not read", listOf("ua", "ub"), h.prefs.reads)

        h.signIn(B2)
        h.settle()
        assertEquals("a new generation of the same uid kept the old restore", listOf("ua", "ub"), h.prefs.cancelled)
        assertEquals("a new generation of the same uid was not read", listOf("ua", "ub", "ub"), h.prefs.reads)

        h.signIn(null)
        h.settle()
        assertEquals("signing out kept the old restore", listOf("ua", "ub", "ub"), h.prefs.cancelled)
        assertEquals("signing out read preferences", 3, h.prefs.reads.size)
        assertEquals("signing out drew something", PremiumTopicScreenState.NONE, h.screen)
        assertTrue("a generation change or logout notified outside Main", h.consumerIdentityReadsOnMain.all { it })
    }

    @Test
    fun `C4-J-IDENTITY-UNMOUNTED after a change, the old identity's queued save and old command reach nothing`() = runTest {
        val h = C4OwnerHarness(this, live = A, online = true)
        h.fakeIssuer.grant = TopicSessionFence(A, "epoch-a", TopicGrantToken(1L))
        h.tabs.stored["ua"] = FreeTab.USD
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(3_000)
        val old = h.shownOwner()
        assertEquals("fixture: A's USD screen", FreeTab.USD, h.screen.ui.selectedTab)

        val parked = CompletableDeferred<Unit>().also { h.prefs.rememberGate = it }
        h.owner.consumer.applyRowPreference(old, FreeTab.USD, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        h.settle()
        h.owner.consumer.applyRowPreference(old, FreeTab.USD, RateRowList.FX_BANKS, listOf("hana", "kb"), listOf("hana", "kb"), setOf("kb"))
        h.settle()
        assertEquals("fixture: the first save is inside the store, the second waits",
            listOf("ua" to RateRowList.FX_BANKS), h.prefs.remembered)

        h.signIn(B)
        h.settle()
        parked.complete(Unit)
        h.settle(1_000)
        assertEquals("A's queued save reached the store after the change", listOf("ua" to RateRowList.FX_BANKS), h.prefs.remembered)
        assertEquals("A's screen is still drawn", PremiumTopicScreenState.NONE, h.screen)

        h.owner.consumer.onUserTabSelected(old, FreeTab.JPY)
        h.settle(1_000)
        assertFalse("A's old command selected a tab", h.tabs.remembered.any { it.second == FreeTab.JPY })
        assertTrue("B was not bound", "ub" in h.prefs.reads)
    }

    @Test
    fun `C4-J-TABS-LIFETIME no tab is chosen before the restore, and the restore and every selection reach the runtime focus`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        val restore = CompletableDeferred<Unit>().also { h.tabs.gate = it }
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(3_000)
        assertEquals("a tab was chosen before the restore", emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
        assertEquals("a topic was asked before the restore", emptyList<String>(), h.asked())
        assertEquals("something was drawn before the restore", PremiumTopicScreenState.NONE, h.screen)

        restore.complete(Unit)
        h.settle(3_000)
        assertEquals("the restored tab is not shown", FreeTab.TETHER, h.screen.ui.selectedTab)
        assertEquals("the restored tab was not asked first", TETHER, h.asked().first())

        for (tab in listOf(FreeTab.NEWS, FreeTab.USD, FreeTab.JPY, FreeTab.EUR, FreeTab.TETHER)) {
            h.owner.consumer.onUserTabSelected(h.shownOwner(), tab)
            h.settle(1_000)
            assertEquals("$tab did not reach the runtime focus", tab, h.screen.ui.selectedTab)
            assertEquals("$tab was not remembered", "u1" to tab, h.tabs.remembered.last())
        }
        val remembered = h.tabs.remembered.size
        h.owner.consumer.onUserTabSelected(h.shownOwner(), FreeTab.TETHER)
        h.settle(1_000)
        assertEquals("a repeated selection selected again", remembered, h.tabs.remembered.size)
    }

    @Test
    fun `C4-J-TABS-LIFETIME watching, remounting and a news selection add no rate request to the runtime's own plan`() = runTest {
        // The runtime alone, given the same inputs at the same times.
        val base = C4OwnerHarness(this, online = true)
        base.tabs.stored["u1"] = FreeTab.TETHER
        val plain = base.factory.create()
        plain.start()
        plain.setOnline(true)
        base.settle(100)
        base.wire.open()
        base.settle(3_000)
        plain.selectTab(U1, FreeTab.NEWS)
        base.settle(3_000)
        val asked = base.asked()
        val subscribed = base.subscribes.map { it.topics }
        plain.stop()

        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(3_000)
        // The route coming back: the same consumer, started and told the identity again.
        val consumer = h.owner.consumer
        consumer.start()
        consumer.onIdentityChanged()
        assertSame("a remount got another consumer", consumer, h.owner.consumer)
        consumer.onUserTabSelected(h.shownOwner(), FreeTab.NEWS)
        h.settle(3_000)

        assertEquals("bootstraps differ from the runtime's own plan", asked, h.asked())
        assertEquals("subscriptions differ from the runtime's own plan", subscribed, h.subscribes.map { it.topics })
        assertEquals("another socket", 1, h.wire.requests.size)
        assertEquals("another runtime", 1, h.scopes.size)
    }

    @Test
    fun `C4-J-OFFLINE-SEED the same owner's seed is drawn offline, with no socket and no bootstrap`() = runTest {
        // An earlier process adopted a live price and saved its seed.
        val earlier = C4OwnerHarness(this, online = true, withLastKnown = true)
        earlier.tabs.stored["u1"] = FreeTab.TETHER
        earlier.bootstrapOutcome = { topic ->
            if (topic == TETHER) TopicSnapshotOutcome.Delivered(decoder.decode(tetherFrame(1390.0)))
            else TopicSnapshotOutcome.Degraded
        }
        earlier.owner.start()
        earlier.foreground(true) // the earlier process was opened by the user
        earlier.settle(10_000)
        check(earlier.memory.writes > 0) { "fixture: the earlier process saved a seed" }

        val h = C4OwnerHarness(this, online = false, withLastKnown = true, memory = earlier.memory)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.settle(5_000)
        assertEquals("not shown as offline", PremiumTopicScreenBanner.Offline, h.screen.banner)
        assertTrue("the seed is not drawn", h.hasPrices())
        assertEquals("a socket", 0, h.wire.requests.size)
        assertEquals("a bootstrap", emptyList<String>(), h.asked())
    }

    @Test
    fun `C4-J-LEGACY-ZERO a legacy rates frame on the owner's socket is neither drawn nor saved`() = runTest {
        val h = C4OwnerHarness(this, online = true, withLastKnown = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(3_000)
        h.shownOwner()
        val writes = h.memory.writes

        h.wire.deliver(LEGACY_RATES_FRAME)
        h.settle(1_000)
        assertFalse("a legacy price is drawn", h.hasPrices())
        assertNull("a legacy index is drawn", h.screen.ui.dollarIndex)
        assertEquals("a legacy price was saved", writes, h.memory.writes)

        h.wire.deliver(tetherFrame(1390.0))
        h.settle(1_000)
        assertTrue("positive control: a topic frame is drawn", h.hasPrices())
        assertTrue("positive control: a topic frame is saved", h.memory.writes > writes)
    }

    // R4-c C2+b-3 B3-10 (Claude-owned, R4c/C2b/b3_design_codex.r2.md): through the production factory, runtime, owner and consumer,
    // the topic line's retry for the shown tab is one subscribe for that tab's retryable topic on the open socket, not a reconnection.
    @Test
    fun `B3-10 the owner's consumer turns a topic retry into one subscribe for the shown tab, not a reconnection`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.tabs.stored["u1"] = FreeTab.USD
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(1)
        val first = h.subscribes.last()
        h.wire.deliver(C4OwnerHarness.ack(first.requestId, active = first.topics - USD, rejections = mapOf(USD to "topics_disabled")))
        h.settle(1)
        assertEquals("fixture: the line offers a retry", PremiumTopicScreenBanner.Topic(TopicBannerReason.TOPICS_DISABLED, canRetry = true),
            h.screen.banner)
        val owner = h.shownOwner()
        val subscribes = h.subscribes.size
        val sockets = h.wire.requests.size
        h.owner.consumer.retryTopics(owner, FreeTab.USD)
        h.settle(1)
        assertEquals("B3-10 one subscribe", subscribes + 1, h.subscribes.size)
        assertEquals("B3-10 for the dollar topic", listOf(USD), h.subscribes.last().topics)
        assertEquals("B3-10 no reconnection", sockets, h.wire.requests.size)
    }
}
