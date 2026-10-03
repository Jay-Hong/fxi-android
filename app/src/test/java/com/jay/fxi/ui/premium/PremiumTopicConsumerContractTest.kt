package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicRecoveryDisplay
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C3c-1 contract (R4c/C3c1/design_codex.r1.md). The consumer joins the runtime's display and accepted focus with
 * the live identity and the user's row preferences: it publishes and serves only the current owner's screen, forwards a confirmed
 * user tab once per change of target, restores preferences only for the exact sign-in that asked, keeps a user's change made during
 * the restore and saves one at a time, words the single status line as iOS does, and does nothing on its own. The presenter's own
 * rules stay with PremiumTopicPresenterTest. The implementation reads but does not edit this file.
 */
class PremiumTopicConsumerContractTest {
    private val a1 = AuthIdentityFence("A", 1L)
    private val a2 = AuthIdentityFence("A", 2L)
    private val b2 = AuthIdentityFence("B", 2L)
    private val t0 = Instant.parse("2026-09-30T15:05:00Z") // KST 2026-10-01 00:05
    private val t1 = Instant.parse("2026-10-01T14:59:00Z") // KST 23:59

    private fun rates(at: Instant = t0) = TopicRates().merge(listOf(
        TopicQuote("kb", "usd-krw", 1390.0, at), TopicQuote("hana", "usd-krw", 1391.0, at),
        TopicQuote("upbit", "usdt-krw", 1400.0, at), TopicQuote("bithumb", "usdt-krw", 1401.0, at)))

    /** Reads and writes wait on a deferred each, in call order; at most [running] writes may be in flight. */
    private class GatedStore : RateRowPreferenceStore {
        val reads = mutableListOf<Pair<String, CompletableDeferred<Map<RateRowList, RateRowPreference>>>>()
        val writes = mutableListOf<Triple<String, RateRowList, RateRowPreference>>()
        val writeGates = mutableListOf<CompletableDeferred<Unit>>()
        var running = 0
        var maxRunning = 0
        /** A store that finishes its read or write even when its caller was cancelled. */
        var nonCancellable = false
        override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> {
            val gate = CompletableDeferred<Map<RateRowList, RateRowPreference>>().also { reads += uid to it }
            return if (nonCancellable) withContext(NonCancellable) { gate.await() } else gate.await()
        }
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) {
            running += 1; maxRunning = maxOf(maxRunning, running)
            try {
                writes += Triple(uid, list, preference)
                val gate = CompletableDeferred<Unit>().also { writeGates += it }
                if (nonCancellable) withContext(NonCancellable) { gate.await() } else gate.await()
            } finally { running -= 1 }
        }
    }

    private inner class Harness(test: TestScope, tab: FreeTab = FreeTab.USD, d: TopicDisplayState = state()) {
        val display = MutableStateFlow(d)
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, tab))
        var live: AuthIdentityFence? = a1
        val store = GatedStore()
        val selects = mutableListOf<Pair<AuthIdentityFence, FreeTab>>()
        val retries = mutableListOf<TopicDisplayOwner>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val consumer = PremiumTopicConsumer(display, focus, { live }, store, { o, t -> selects += o to t }, { retries += it }, scope)
        fun shown() = consumer.state.value
        fun rowIds() = shown().ui.rateSections.flatMap { s -> s.rows.map { it.id } }
    }
    private fun state(owner: TopicDisplayOwner? = TopicDisplayOwner(a1, 1L), r: TopicRates = rates(), seed: Boolean = false,
                      connection: TopicConnectionDisplay = TopicConnectionDisplay.OPEN, resolved: Boolean = false,
                      recovery: TopicDisplayState.() -> TopicRecoveryDisplay = { TopicRecoveryDisplay.None }) =
        TopicDisplayState(owner, r, seed, connection, cachedRefreshResolved = resolved).let { it.copy(recovery = it.recovery()) }
    private val o1 = TopicDisplayOwner(a1, 1L)

    @Test fun `C3c1-01 only the current owner's screen is published or served, and nothing is selected for it`() = runTest {
        val h = Harness(this)
        h.focus.value = null
        h.consumer.start(); runCurrent()
        assertEquals("C3c1-01 no focus yet", PremiumTopicScreenState.NONE, h.shown())
        h.focus.value = OwnedTopicFocus(a1, FreeTab.USD); runCurrent()
        assertEquals("C3c1-01 shown while restoring", o1, h.shown().ui.owner)
        assertEquals("C3c1-01 current", h.shown(), h.consumer.currentState())
        h.live = a2
        assertEquals("C3c1-01 live moved before any callback", PremiumTopicScreenState.NONE, h.consumer.currentState())
        h.live = a1
        h.display.value = state(owner = TopicDisplayOwner(a1, 2L)); runCurrent()
        assertEquals("C3c1-01 a new grant turn is a new model", TopicDisplayOwner(a1, 2L), h.shown().ui.owner)
        h.scope.cancel()
        assertEquals("C3c1-01 ended", PremiumTopicScreenState.NONE, h.consumer.currentState())
        assertEquals("C3c1-01 nothing selected", emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.selects)
    }

    @Test fun `C3c1-02 a confirmed user tab is forwarded once per change of target, the last one kept`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        h.consumer.onUserTabSelected(o1, FreeTab.TETHER)
        h.consumer.onUserTabSelected(o1, FreeTab.TETHER)
        h.consumer.onUserTabSelected(o1, FreeTab.USD)
        runCurrent()
        assertEquals("C3c1-02 each change once, the return to USD kept although focus still says USD",
            listOf(a1 to FreeTab.TETHER, a1 to FreeTab.USD), h.selects)
        h.consumer.onUserTabSelected(TopicDisplayOwner(a1, 9L), FreeTab.EUR); runCurrent()
        assertEquals("C3c1-02 a stale owner selects nothing", 2, h.selects.size)
        h.display.value = state(owner = TopicDisplayOwner(a1, 2L)); runCurrent()
        h.consumer.onUserTabSelected(TopicDisplayOwner(a1, 2L), FreeTab.USD); runCurrent()
        assertEquals("C3c1-02 a new owner compares with its focus", 2, h.selects.size)
        // Battery r1 (CM6): an earlier owner's target is dropped, so the new owner's first different choice is forwarded.
        h.consumer.onUserTabSelected(TopicDisplayOwner(a1, 2L), FreeTab.JPY); runCurrent()
        h.display.value = state(owner = TopicDisplayOwner(a1, 3L)); runCurrent()
        h.consumer.onUserTabSelected(TopicDisplayOwner(a1, 3L), FreeTab.JPY); runCurrent()
        assertEquals("C3c1-02 the next owner's JPY is its own choice", listOf(a1 to FreeTab.TETHER, a1 to FreeTab.USD, a1 to FreeTab.JPY, a1 to FreeTab.JPY), h.selects)
        h.scope.cancel()
    }

    @Test fun `C3c1-03 preferences are restored only for the exact sign-in that asked, once per sign-in`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        assertEquals("C3c1-03 one read for A", listOf("A"), h.store.reads.map { it.first })
        h.display.value = state(owner = TopicDisplayOwner(a1, 2L)); runCurrent()
        assertEquals("C3c1-03 a new grant turn reads nothing more", 1, h.store.reads.size)
        h.live = b2
        h.display.value = state(owner = TopicDisplayOwner(b2, 1L))
        h.focus.value = OwnedTopicFocus(b2, FreeTab.USD)
        h.consumer.onIdentityChanged(); runCurrent()
        assertEquals("C3c1-03 a read for B", listOf("A", "B"), h.store.reads.map { it.first })
        h.store.reads[0].second.complete(mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("kb"))))
        runCurrent()
        assertTrue("C3c1-03 A's late restore is not B's", "kb" in h.rowIds())
        h.store.reads[1].second.complete(emptyMap()); runCurrent()
        assertTrue("C3c1-03 B's own restore", "kb" in h.rowIds())
        h.scope.cancel()
    }

    @Test fun `C3c1-04 a change during the restore shows at once and is replayed over the restored preferences, then saved`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, seeded = listOf("kb", "hana"), order = listOf("hana", "kb"), hidden = emptySet())
        runCurrent()
        assertEquals("C3c1-04 shown at once", listOf("hana", "kb"), h.rowIds())
        assertTrue("C3c1-04 not saved before the restore", h.store.writes.isEmpty())
        h.store.reads[0].second.complete(mapOf(
            RateRowList.FX_BANKS to RateRowPreference(order = listOf("kb", "sc", "hana"), hidden = setOf("sc")),
            RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("upbit"))))
        runCurrent()
        val replayed = RateRowPreference(order = listOf("hana", "sc", "kb"), hidden = setOf("sc"))
        assertEquals("C3c1-04 replayed and saved", listOf(Triple("A", RateRowList.FX_BANKS, replayed)), h.store.writes)
        assertEquals("C3c1-04 still shown", listOf("hana", "kb"), h.rowIds())
        h.focus.value = OwnedTopicFocus(a1, FreeTab.TETHER); runCurrent()
        val tether = h.shown().ui.rowEditors.single { it.list == RateRowList.TETHER_EXCHANGES }
        assertEquals("C3c1-04 the untouched list is restored", false, tether.entries.single { it.code == "upbit" }.visible)
        h.scope.cancel()
    }

    @Test fun `C3c1-05 saves run one at a time in order, a failure keeps the change, and stale or cancelled work does nothing`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        h.store.reads[0].second.completeExceptionally(IOException("unreadable"))
        runCurrent()
        assertEquals("C3c1-05 an unreadable restore draws defaults", listOf("kb", "hana"), h.rowIds())
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("hana", "kb"), listOf("hana", "kb"), setOf("kb"))
        runCurrent()
        assertEquals("C3c1-05 one save running", 1, h.store.writes.size)
        h.store.writeGates[0].completeExceptionally(IOException("full")); runCurrent()
        assertEquals("C3c1-05 the next save after the failure", 2, h.store.writes.size)
        assertEquals("C3c1-05 never two at once", 1, h.store.maxRunning)
        assertEquals("C3c1-05 the failed change is still shown", listOf("hana"), h.rowIds())
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("hana"), listOf("hana"), emptySet())
        runCurrent()
        h.live = b2
        h.consumer.onIdentityChanged(); runCurrent()
        h.store.writeGates[1].complete(Unit); runCurrent()
        assertEquals("C3c1-05 A's queued save is dropped after the sign-in moved", 2, h.store.writes.size)
        h.scope.cancel()
        h.store.reads.forEach { it.second.complete(emptyMap()) }
        runCurrent()
        assertEquals("C3c1-05 nothing after the end", 2, h.store.writes.size)
    }

    @Test fun `C3c1-06 the status line is the first that applies, worded as iOS, and only a current failure offers a retry`() = runTest {
        val cases = listOf(
            state(connection = TopicConnectionDisplay.OFFLINE, seed = true) to PremiumTopicScreenBanner.Offline,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true) { TopicRecoveryDisplay.Exhausted } to PremiumTopicScreenBanner.Failed,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true) to PremiumTopicScreenBanner.RefreshingCached,
            state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Connecting } to PremiumTopicScreenBanner.Connecting,
            state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Reconnecting(1) } to PremiumTopicScreenBanner.Reconnecting(1),
            state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Reconnecting(5) } to PremiumTopicScreenBanner.Reconnecting(5),
            state() to null,
            state(connection = TopicConnectionDisplay.DISCONNECTED) to null,
            // R4-c F2 revision (R4c/C2b/design_codex.r2.md §6, agreed): once the session accepted an answer in this round, a seed no
            // longer holds the line; the next status that applies shows, in the same order as before.
            state(connection = TopicConnectionDisplay.OFFLINE, seed = true, resolved = true) to PremiumTopicScreenBanner.Offline,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true, resolved = true) { TopicRecoveryDisplay.Exhausted } to
                PremiumTopicScreenBanner.Failed,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true) { TopicRecoveryDisplay.Connecting } to
                PremiumTopicScreenBanner.RefreshingCached,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true, resolved = true) { TopicRecoveryDisplay.Connecting } to
                PremiumTopicScreenBanner.Connecting,
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true, resolved = true) { TopicRecoveryDisplay.Reconnecting(2) } to
                PremiumTopicScreenBanner.Reconnecting(2),
            state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true, resolved = true) to null,
            state(seed = true, resolved = true) to null)
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        for (tab in FreeTab.entries) for ((d, banner) in cases) {
            h.focus.value = OwnedTopicFocus(a1, tab)
            h.display.value = d; runCurrent()
            assertEquals("C3c1-06 $tab ${d.connection} ${d.recovery} seed=${d.containsSeed} resolved=${d.cachedRefreshResolved}",
                banner, h.shown().banner)
            if (d.cachedRefreshResolved && banner != PremiumTopicScreenBanner.Offline) {
                assertNull("C3c1-06 $tab released: no cached time", h.shown().updatedText)
            }
        }
        // The retry rows below are about the owner, not the tab: back to the tab the harness started on.
        h.focus.value = OwnedTopicFocus(a1, FreeTab.USD); runCurrent()
        assertEquals("C3c1-06 texts", listOf("연결 중...", "연결 중...", "재연결 중 (2/5)", "연결할 수 없습니다"),
            listOf(PremiumTopicScreenBanner.Connecting.text, PremiumTopicScreenBanner.Reconnecting(1).text,
                PremiumTopicScreenBanner.Reconnecting(2).text, PremiumTopicScreenBanner.Failed.text))
        assertEquals("C3c1-06 retry label", "재연결", PremiumTopicScreenBanner.Failed.action)

        h.display.value = state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Reconnecting(2) }; runCurrent()
        h.consumer.retryConnection(o1); runCurrent()
        assertTrue("C3c1-06 no retry while not failed", h.retries.isEmpty())
        h.display.value = state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Exhausted }; runCurrent()
        h.consumer.retryConnection(TopicDisplayOwner(a1, 9L)); runCurrent()
        assertTrue("C3c1-06 a stale owner retries nothing", h.retries.isEmpty())
        h.consumer.retryConnection(o1); runCurrent()
        assertEquals("C3c1-06 the current failure", listOf(o1), h.retries)
        h.scope.cancel()
    }

    @Test fun `C3c1-07 the time is KST HH-mm with the banner's prefix, and only on banners that carry one`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        h.display.value = state(connection = TopicConnectionDisplay.OFFLINE); runCurrent()
        assertEquals("C3c1-07 offline", "마지막 업데이트: 00:05", h.shown().updatedText)
        h.display.value = state(r = rates(t1), connection = TopicConnectionDisplay.DISCONNECTED, seed = true); runCurrent()
        assertEquals("C3c1-07 cached", "23:59", h.shown().updatedText)
        h.display.value = state(r = TopicRates(), connection = TopicConnectionDisplay.OFFLINE); runCurrent()
        assertEquals("C3c1-07 nothing observed", PremiumTopicScreenBanner.Offline, h.shown().banner)
        assertNull("C3c1-07 no time without an observation", h.shown().updatedText)
        h.display.value = state(connection = TopicConnectionDisplay.DISCONNECTED, seed = true) { TopicRecoveryDisplay.Exhausted }; runCurrent()
        assertNull("C3c1-07 a failure hides the cached time", h.shown().updatedText)
        h.display.value = state(connection = TopicConnectionDisplay.DISCONNECTED) { TopicRecoveryDisplay.Connecting }; runCurrent()
        assertNull("C3c1-07 connecting has no time", h.shown().updatedText)
        // R4-c F2 revision: a released seed carries no cached time, whichever status replaces it.
        h.display.value = state(r = rates(t1), connection = TopicConnectionDisplay.DISCONNECTED, seed = true, resolved = true) {
            TopicRecoveryDisplay.Connecting
        }; runCurrent()
        assertNull("C3c1-07 released and connecting: no time", h.shown().updatedText)
        h.display.value = state(r = rates(t1), seed = true, resolved = true); runCurrent()
        assertNull("C3c1-07 released and open: no banner", h.shown().banner)
        assertNull("C3c1-07 released and open: no time", h.shown().updatedText)
        h.scope.cancel()
    }

    @Test fun `C3c1-08 the consumer does nothing on its own`() = runTest {
        val h = Harness(this, tab = FreeTab.NEWS)
        h.consumer.start()
        val seen = mutableListOf<PremiumTopicScreenState>()
        val watcher = h.scope.launch { h.consumer.state.collect { seen += it } }
        runCurrent()
        h.display.value = state(seed = true); runCurrent()
        watcher.cancel()
        h.scope.launch { h.consumer.state.collect { } }; runCurrent()
        assertTrue("C3c1-08 something was published", seen.isNotEmpty())
        assertEquals("C3c1-08 no select", emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.selects)
        assertEquals("C3c1-08 no retry", emptyList<TopicDisplayOwner>(), h.retries)
        assertTrue("C3c1-08 no save", h.store.writes.isEmpty())
        h.scope.cancel()
    }

    // ---- Battery r1–r3 rows (R4c/C3c1/battery_codex.r2.md agreed): guards each pinned by a state that reaches it. ----

    @Test fun `C3c1-09 a second start adds no read or writer, and an ended scope starts nothing`() = runTest {
        val h = Harness(this)
        h.consumer.start(); h.consumer.start(); runCurrent()
        assertEquals("C3c1-09 one read", 1, h.store.reads.size)
        h.store.reads[0].second.complete(emptyMap()); runCurrent()
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        runCurrent()
        assertEquals("C3c1-09 the first save is running", 1, h.store.writes.size)
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("hana", "kb"), listOf("hana", "kb"), setOf("kb"))
        runCurrent()
        assertEquals("C3c1-09 the second waits for the one writer", 1, h.store.writes.size)
        h.store.writeGates[0].complete(Unit); runCurrent()
        assertEquals("C3c1-09 then it runs", 2, h.store.writes.size)
        assertEquals("C3c1-09 never two at once", 1, h.store.maxRunning)
        h.scope.cancel()

        val ended = Harness(this)
        ended.scope.cancel()
        ended.consumer.start(); runCurrent()
        assertEquals("C3c1-09 no read on an ended scope", 0, ended.store.reads.size)
        assertEquals("C3c1-09 nothing shown", PremiumTopicScreenState.NONE, ended.consumer.currentState())
    }

    @Test fun `C3c1-10 before start nothing is served or read`() = runTest {
        val h = Harness(this)
        assertEquals("C3c1-10 current", PremiumTopicScreenState.NONE, h.consumer.currentState())
        h.consumer.onIdentityChanged(); runCurrent()
        assertEquals("C3c1-10 no read", 0, h.store.reads.size)
        assertEquals("C3c1-10 still nothing", PremiumTopicScreenState.NONE, h.shown())
        h.scope.cancel()
    }

    @Test fun `C3c1-11 a row change answered for another owner or another tab is not applied`() = runTest {
        val h = Harness(this)
        h.consumer.start(); runCurrent()
        h.store.reads[0].second.complete(emptyMap()); runCurrent()
        h.consumer.applyRowPreference(TopicDisplayOwner(a1, 9L), FreeTab.USD, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        runCurrent()
        assertEquals("C3c1-11 another owner: rows kept", listOf("kb", "hana"), h.rowIds())
        assertTrue("C3c1-11 another owner: no save", h.store.writes.isEmpty())
        h.consumer.applyRowPreference(o1, FreeTab.JPY, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        runCurrent()
        assertEquals("C3c1-11 another tab: rows kept", listOf("kb", "hana"), h.rowIds())
        assertTrue("C3c1-11 another tab: no save", h.store.writes.isEmpty())
        h.scope.cancel()
    }

    /** A display whose value moves to [next] after one read once [armed]: the session publishing between two reads of one refresh. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class MovingDisplay(private val base: MutableStateFlow<TopicDisplayState>, private val next: TopicDisplayState) : StateFlow<TopicDisplayState> {
        var armed = false
        private var readsSinceArmed = 0
        override val value: TopicDisplayState
            get() = if (armed && readsSinceArmed++ >= 1) next else base.value
        override val replayCache: List<TopicDisplayState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<TopicDisplayState>): Nothing = base.collect(collector)
    }

    @Test fun `C3c1-12 a display that moves during one refresh serves nothing for that refresh`() = runTest {
        val base = MutableStateFlow(state())
        val moving = MovingDisplay(base, state(owner = TopicDisplayOwner(a1, 2L)))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val consumer = PremiumTopicConsumer(moving, MutableStateFlow(OwnedTopicFocus(a1, FreeTab.USD)), { a1 }, GatedStore(), { _, _ -> }, { }, scope)
        consumer.start(); runCurrent()
        check(consumer.currentState().ui.owner == o1) { "fixture: shown before it moves" }
        moving.armed = true
        assertEquals("C3c1-12", PremiumTopicScreenState.NONE, consumer.currentState())
        scope.cancel()
    }

    @Test fun `C3c1-13 a restore that returns after its sign-in moved, from a store that ignores cancellation, is not applied`() = runTest {
        val h = Harness(this)
        h.store.nonCancellable = true
        h.consumer.start(); runCurrent()
        h.live = b2
        h.display.value = state(owner = TopicDisplayOwner(b2, 1L))
        h.focus.value = OwnedTopicFocus(b2, FreeTab.USD)
        h.consumer.onIdentityChanged(); runCurrent()
        h.store.reads[0].second.complete(mapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("kb"))))
        runCurrent()
        // Battery r4 (FM16): the late result is dropped outright; taking it would bind A and force B to read again.
        assertEquals("C3c1-13 A's late result triggers nothing", listOf("A", "B"), h.store.reads.map { it.first })
        h.store.reads[1].second.complete(emptyMap()); runCurrent()
        assertTrue("C3c1-13 A's late restore is not B's", "kb" in h.rowIds())
        h.scope.cancel()
    }

    @Test fun `C3c1-14 after the end no further save is made, even when the running one ignores cancellation`() = runTest {
        val h = Harness(this)
        h.store.nonCancellable = true
        h.consumer.start(); runCurrent()
        h.store.reads[0].second.complete(emptyMap()); runCurrent()
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("kb", "hana"), listOf("hana", "kb"), emptySet())
        runCurrent()
        h.consumer.applyRowPreference(o1, FreeTab.USD, RateRowList.FX_BANKS, listOf("hana", "kb"), listOf("hana", "kb"), setOf("kb"))
        runCurrent()
        check(h.store.writes.size == 1) { "fixture: the first save runs, the second waits" }
        h.scope.cancel()
        h.store.writeGates[0].complete(Unit); runCurrent()
        assertEquals("C3c1-14 the queued save is not made", 1, h.store.writes.size)
    }
}
