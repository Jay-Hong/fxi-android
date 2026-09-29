package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.domain.model.FreeTab
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude-owned L-4f contract for the focus provider (L4f/decl_codex.r1.md). `A#1` is UID A at auth generation 1; the focus
 * expectation is the exact list of `(owner, tab)` handed to `setFocus`.
 *  - No focus before the restore for the live identity settles; then the stored tab, or 달러 when none, unknown or unreadable.
 *    뉴스 is a valid restored tab, not "unknown".
 *  - A restore for an identity that is no longer live, or that was cancelled, publishes nothing (and no 달러 fallback).
 *  - A confirmed choice for the live identity is focused and remembered; it wins over a restore still in flight; a choice for a
 *    stale identity does nothing; a failed save keeps the focus it already gave.
 * The implementation thread reads but does not edit this file.
 */
class TopicFocusProviderTest {

    private fun fence(uid: String, generation: Long) = AuthIdentityFence(uid, generation)
    private val a1 = fence("A", 1)
    private val a2 = fence("A", 2)
    private val b2 = fence("B", 2)

    /**
     * Reads wait on a deferred each; every read is recorded in call order. Writes are recorded, or refused; with [gateWrites]
     * each write waits on its own deferred and lands in [held] only when completed, so completion order can be reversed.
     */
    private class ScriptedTabs : FreeTabStore {
        val reads = mutableListOf<Pair<String, CompletableDeferred<FreeTab>>>()
        val remembered = mutableListOf<Pair<String, FreeTab>>()
        val held = mutableMapOf<String, FreeTab>()
        var refuseWrites: Throwable? = null
        var gateWrites = false
        val pendingWrites = mutableListOf<CompletableDeferred<Unit>>()
        override suspend fun lastTab(uid: String): FreeTab =
            CompletableDeferred<FreeTab>().also { reads += uid to it }.await()
        override suspend fun remember(uid: String, tab: FreeTab) {
            refuseWrites?.let { throw it }
            remembered += uid to tab
            if (gateWrites) CompletableDeferred<Unit>().also { pendingWrites += it }.await()
            held[uid] = tab
        }
    }

    private class Harness(test: TestScope) {
        val tabs = ScriptedTabs()
        val focused = mutableListOf<Pair<AuthIdentityFence, FreeTab>>()
        var live: AuthIdentityFence? = null
        private var listener: ((AuthIdentityFence?) -> Unit)? = null
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val provider = TopicFocusProvider(scope, AuthFenceStream { listener = it }, { live }, tabs) { owner, tab -> focused += owner to tab }
        fun signIn(f: AuthIdentityFence?) { live = f; checkNotNull(listener) { "start() did not observe fences" }(f) }
        /** Delivers a fence callback without changing what [live] answers — a callback that is late or repeated. */
        fun deliver(f: AuthIdentityFence?) { checkNotNull(listener) { "start() did not observe fences" }(f) }
        fun read(index: Int) = tabs.reads[index].second
    }

    private fun TestScope.started(first: AuthIdentityFence = a1): Harness =
        Harness(this).also { it.provider.start(); it.signIn(first); runCurrent() }

    @Test
    fun F01_noFocusBeforeTheRestoreSettles() = runTest {
        val h = started()
        assertEquals("one restore read for A", listOf("A"), h.tabs.reads.map { it.first })
        assertEquals(emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
    }

    @Test
    fun F02_theRestoredTab_isFocused() = runTest {
        val h = started(); h.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals(listOf(a1 to FreeTab.TETHER), h.focused)
    }

    @Test
    fun F03_nothingStored_focusesDollar() = runTest {
        // The store answers 달러 for a UID with nothing stored (DataStoreFreeTabStoreTest S01).
        val h = started(); h.read(0).complete(FreeTab.USD); runCurrent()
        assertEquals(listOf(a1 to FreeTab.USD), h.focused)
    }

    @Test
    fun F04_aFailedRead_focusesDollar() = runTest {
        val h = started(); h.read(0).completeExceptionally(IOException("disk")); runCurrent()
        assertEquals(listOf(a1 to FreeTab.USD), h.focused)
    }

    @Test
    fun F05_newsIsARestoredTab_notUnknown() = runTest {
        val h = started(); h.read(0).complete(FreeTab.NEWS); runCurrent()
        assertEquals(listOf(a1 to FreeTab.NEWS), h.focused)
    }

    @Test
    fun F06_anotherUidWhileRestoring_onlyTheNewIdentityIsFocused() = runTest {
        val h = started()
        h.signIn(b2); runCurrent()
        h.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals("A's late restore publishes nothing", emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
        assertEquals(listOf("A", "B"), h.tabs.reads.map { it.first })
        h.read(1).complete(FreeTab.EUR); runCurrent()
        assertEquals(listOf(b2 to FreeTab.EUR), h.focused)
    }

    @Test
    fun F07_aNewGenerationOfTheSameUid_dropsTheOlderRestore() = runTest {
        val h = started()
        h.signIn(a2); runCurrent()
        h.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals(emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
        h.read(1).complete(FreeTab.JPY); runCurrent()
        assertEquals(listOf(a2 to FreeTab.JPY), h.focused)
    }

    @Test
    fun F08_aChoiceWhileRestoring_winsOverTheLateRestore() = runTest {
        val h = started()
        h.provider.onTabSelected(a1, FreeTab.JPY); runCurrent()
        h.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals(listOf(a1 to FreeTab.JPY), h.focused)
        assertEquals(listOf("A" to FreeTab.JPY), h.tabs.remembered)
    }

    @Test
    fun F09_aChoiceForAStaleIdentity_doesNothing() = runTest {
        val h = started(b2); h.read(0).complete(FreeTab.EUR); runCurrent()
        h.provider.onTabSelected(a1, FreeTab.TETHER); runCurrent()
        assertEquals(listOf(b2 to FreeTab.EUR), h.focused)
        assertEquals(emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
    }

    @Test
    fun F10_aChoiceAfterTheRestore_isFocusedAndRemembered() = runTest {
        val h = started(); h.read(0).complete(FreeTab.TETHER); runCurrent()
        h.provider.onTabSelected(a1, FreeTab.EUR); runCurrent()
        assertEquals(listOf(a1 to FreeTab.TETHER, a1 to FreeTab.EUR), h.focused)
        assertEquals(listOf("A" to FreeTab.EUR), h.tabs.remembered)
    }

    @Test
    fun F11_rapidChoices_areFocusedInOrder_andTheLastIsWhatIsHeld_evenWhenWritesCompleteReversed() = runTest {
        val h = started(); h.read(0).complete(FreeTab.USD); runCurrent()
        h.tabs.gateWrites = true
        h.provider.onTabSelected(a1, FreeTab.TETHER)
        h.provider.onTabSelected(a1, FreeTab.JPY)
        h.provider.onTabSelected(a1, FreeTab.NEWS)
        runCurrent()
        assertEquals(listOf(a1 to FreeTab.USD, a1 to FreeTab.TETHER, a1 to FreeTab.JPY, a1 to FreeTab.NEWS), h.focused)
        // Complete whatever writes are pending, newest first, until none remain.
        var rounds = 0
        while (h.tabs.pendingWrites.any { !it.isCompleted }) {
            h.tabs.pendingWrites.filter { !it.isCompleted }.reversed().forEach { it.complete(Unit) }
            runCurrent(); check(++rounds < 10) { "writes never settled" }
        }
        assertEquals("the last choice is what is held", FreeTab.NEWS, h.tabs.held["A"])
    }

    @Test
    fun F12_aCancelledRestore_publishesNothing_noDollarFallback() = runTest {
        val h = started()
        h.read(0).completeExceptionally(CancellationException("restore replaced")); runCurrent()
        assertEquals("a cancelled read is not a failed read", emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
        val g = started()
        g.scope.cancel(); runCurrent()
        g.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals("the provider's own scope cancelled", emptyList<Pair<AuthIdentityFence, FreeTab>>(), g.focused)
    }

    @Test
    fun F13_aFailedSave_keepsTheFocusItGave_andThePreviousHeldValue() = runTest {
        val h = started(); h.read(0).complete(FreeTab.USD); runCurrent()
        h.provider.onTabSelected(a1, FreeTab.TETHER); runCurrent()
        assertEquals(FreeTab.TETHER, h.tabs.held["A"])
        h.tabs.refuseWrites = IOException("disk full")
        h.provider.onTabSelected(a1, FreeTab.EUR); runCurrent()
        assertEquals("the EUR focus is given exactly once", listOf(a1 to FreeTab.USD, a1 to FreeTab.TETHER, a1 to FreeTab.EUR), h.focused)
        assertEquals("the previous good value is what is held", FreeTab.TETHER, h.tabs.held["A"])
    }

    @Test
    fun F14_theLiveIdentityChangedBeforeItsCallback_theLateRestorePublishesNothing() = runTest {
        val h = started()
        h.live = b2
        h.read(0).complete(FreeTab.TETHER); runCurrent()
        assertEquals(emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
    }

    @Test
    fun F16_aChoiceForTheLiveIdentity_isTakenBeforeItsCallbackArrives_andNotUndoneByIt() = runTest {
        // The plan (L-4f, 동결 후 11번) takes a valid current user's choice; a late callback does not make it stale.
        val h = started(); h.read(0).complete(FreeTab.USD); runCurrent()
        h.live = b2
        h.provider.onTabSelected(b2, FreeTab.EUR); runCurrent()
        assertEquals(listOf(a1 to FreeTab.USD, b2 to FreeTab.EUR), h.focused)
        assertEquals(listOf("B" to FreeTab.EUR), h.tabs.remembered)
        h.deliver(b2); runCurrent()
        h.tabs.reads.drop(1).forEach { it.second.complete(FreeTab.JPY) }; runCurrent()
        assertEquals("B's callback and any restore it starts do not override the choice", listOf(a1 to FreeTab.USD, b2 to FreeTab.EUR), h.focused)
    }

    @Test
    fun F17_aLateCallbackForAnIdentityNoLongerLive_startsNoRestore() = runTest {
        val h = started()
        h.live = b2
        h.deliver(a2); runCurrent()
        assertEquals(listOf("A"), h.tabs.reads.map { it.first })
        assertEquals(emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
    }

    @Test
    fun F18_aRepeatedCallbackForTheSameIdentity_doesNotRestoreAgain_orUndoTheChoice() = runTest {
        val h = started(); h.read(0).complete(FreeTab.TETHER); runCurrent()
        h.provider.onTabSelected(a1, FreeTab.EUR); runCurrent()
        h.deliver(a1); runCurrent()
        assertEquals(listOf("A"), h.tabs.reads.map { it.first })
        assertEquals(listOf(a1 to FreeTab.TETHER, a1 to FreeTab.EUR), h.focused)
    }

    @Test
    fun F19_aChoiceUnderOneIdentity_doesNotBlockTheNextIdentitysRestore() = runTest {
        val h = started(); h.read(0).complete(FreeTab.USD); runCurrent()
        h.provider.onTabSelected(a1, FreeTab.EUR); runCurrent()
        h.signIn(b2); runCurrent()
        h.read(1).complete(FreeTab.JPY); runCurrent()
        assertEquals(listOf(a1 to FreeTab.USD, a1 to FreeTab.EUR, b2 to FreeTab.JPY), h.focused)
    }
}
