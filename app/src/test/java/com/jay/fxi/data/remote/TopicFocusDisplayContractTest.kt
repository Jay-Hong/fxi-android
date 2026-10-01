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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claude-owned R4-c C3a contract (R4c/C3/design_codex.r3.md, rounds r1→r3 agreed). The focus provider exposes the tab it
 * accepted for the live identity as a read-only state: null until that identity's restore settles, then the restored tab (달러
 * when unreadable), a later confirmed choice over a restore still in flight, and null again when a new live identity is handled.
 * It adds no read, save or `setFocus` of its own, and the existing L-4f behaviour (TopicFocusProviderTest) is unchanged. It is
 * the provider's acceptance, not the session having handled it, and a state rather than a log of every choice.
 * The implementation reads but does not edit this file.
 */
class TopicFocusDisplayContractTest {
    private fun fence(uid: String, generation: Long) = AuthIdentityFence(uid, generation)
    private val a1 = fence("A", 1)
    private val a2 = fence("A", 2)
    private val b2 = fence("B", 2)

    private class ScriptedTabs : FreeTabStore {
        val reads = mutableListOf<Pair<String, CompletableDeferred<FreeTab>>>()
        val remembered = mutableListOf<Pair<String, FreeTab>>()
        var refuseWrites: Throwable? = null
        override suspend fun lastTab(uid: String): FreeTab = CompletableDeferred<FreeTab>().also { reads += uid to it }.await()
        override suspend fun remember(uid: String, tab: FreeTab) {
            refuseWrites?.let { throw it }
            remembered += uid to tab
        }
    }

    private class Harness(test: TestScope) {
        val tabs = ScriptedTabs()
        val focused = mutableListOf<Pair<AuthIdentityFence, FreeTab>>()
        var live: AuthIdentityFence? = null
        private var listener: ((AuthIdentityFence?) -> Unit)? = null
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val provider = TopicFocusProvider(scope, AuthFenceStream { listener = it }, { live }, tabs) { owner, tab -> focused += owner to tab }
        fun signIn(f: AuthIdentityFence?) { live = f; checkNotNull(listener)(f) }
        fun deliver(f: AuthIdentityFence?) { checkNotNull(listener)(f) }
        fun read(index: Int) = tabs.reads[index].second
        val shown get() = provider.focus.value
    }

    private fun TestScope.started(first: AuthIdentityFence = a1): Harness =
        Harness(this).also { it.provider.start(); it.signIn(first); runCurrent() }

    @Test fun `C3a-01 nothing is shown before the restore settles, and watching adds no read or save`() = runTest {
        val h = Harness(this)
        assertNull("C3a-01 before start", h.shown)
        h.provider.start()
        h.signIn(a1)
        val seen = mutableListOf<OwnedTopicFocus?>()
        val watcher = h.scope.launch { h.provider.focus.collect { seen += it } }
        runCurrent()
        assertNull("C3a-01 restore pending", h.shown)
        assertEquals("C3a-01 no setFocus", emptyList<Pair<AuthIdentityFence, FreeTab>>(), h.focused)
        assertEquals("C3a-01 one read", listOf("A"), h.tabs.reads.map { it.first })
        assertEquals("C3a-01 no save", emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
        assertEquals("C3a-01 only null observed", listOf<OwnedTopicFocus?>(null), seen)
        watcher.cancel()
        h.scope.cancel()
    }

    @Test fun `C3a-02 each restored tab is shown for its identity, once, without a save`() = runTest {
        for (tab in FreeTab.entries) {
            val h = started()
            h.read(0).complete(tab)
            runCurrent()
            assertEquals("C3a-02 $tab shown", OwnedTopicFocus(a1, tab), h.shown)
            assertEquals("C3a-02 $tab setFocus once", listOf(a1 to tab), h.focused)
            assertEquals("C3a-02 $tab no save", emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
            h.scope.cancel()
        }
    }

    @Test fun `C3a-03 an unreadable restore shows the dollar tab, and a cancelled one shows nothing`() = runTest {
        val failed = started()
        failed.read(0).completeExceptionally(IOException("disk"))
        runCurrent()
        assertEquals("C3a-03 fallback", OwnedTopicFocus(a1, FreeTab.USD), failed.shown)
        failed.scope.cancel()

        val cancelled = started()
        cancelled.read(0).completeExceptionally(CancellationException("gone"))
        runCurrent()
        assertNull("C3a-03 cancelled shows nothing", cancelled.shown)
        assertEquals("C3a-03 cancelled focuses nothing", emptyList<Pair<AuthIdentityFence, FreeTab>>(), cancelled.focused)
        cancelled.scope.cancel()
    }

    @Test fun `C3a-04 a new live identity clears the shown tab, and only its own restore is shown`() = runTest {
        val h = started()
        h.signIn(b2)
        runCurrent()
        h.read(0).complete(FreeTab.TETHER)
        runCurrent()
        assertNull("C3a-04 A's late restore is not shown", h.shown)
        h.read(1).complete(FreeTab.JPY)
        runCurrent()
        assertEquals("C3a-04 B's restore", OwnedTopicFocus(b2, FreeTab.JPY), h.shown)

        h.signIn(a2)
        runCurrent()
        assertNull("C3a-04 A#2 handled clears B's tab", h.shown)
        h.read(2).complete(FreeTab.EUR)
        runCurrent()
        assertEquals("C3a-04 A#2 restore", OwnedTopicFocus(a2, FreeTab.EUR), h.shown)

        h.signIn(null)
        runCurrent()
        assertNull("C3a-04 signed out", h.shown)
        h.scope.cancel()
    }

    @Test fun `C3a-05 a confirmed choice wins over a late restore, survives a failed save, and the latest choice is shown`() = runTest {
        val h = started()
        h.tabs.refuseWrites = IOException("full")
        h.provider.onTabSelected(a1, FreeTab.NEWS)
        runCurrent()
        assertEquals("C3a-05 chosen", OwnedTopicFocus(a1, FreeTab.NEWS), h.shown)
        h.read(0).complete(FreeTab.TETHER)
        runCurrent()
        assertEquals("C3a-05 late restore does not replace the choice", OwnedTopicFocus(a1, FreeTab.NEWS), h.shown)
        h.tabs.refuseWrites = null
        h.provider.onTabSelected(a1, FreeTab.TETHER)
        h.provider.onTabSelected(a1, FreeTab.EUR)
        runCurrent()
        assertEquals("C3a-05 latest choice", OwnedTopicFocus(a1, FreeTab.EUR), h.shown)
        assertEquals("C3a-05 saves in order", listOf("A" to FreeTab.TETHER, "A" to FreeTab.EUR), h.tabs.remembered)

        // The live identity moved before its callback arrived: a choice for it is accepted, and the late callback keeps it.
        h.live = a2
        h.provider.onTabSelected(a2, FreeTab.JPY)
        runCurrent()
        assertEquals("C3a-05 choice ahead of the callback", OwnedTopicFocus(a2, FreeTab.JPY), h.shown)
        h.deliver(a2)
        runCurrent()
        assertEquals("C3a-05 the callback does not clear it", OwnedTopicFocus(a2, FreeTab.JPY), h.shown)
        h.scope.cancel()
    }

    @Test fun `C3a-06 a repeated callback and stale inputs change nothing`() = runTest {
        val h = started()
        h.read(0).complete(FreeTab.EUR)
        runCurrent()
        val shown = h.shown
        val focused = h.focused.toList()
        h.deliver(a1)
        h.deliver(b2)
        h.provider.onTabSelected(b2, FreeTab.JPY)
        runCurrent()
        assertEquals("C3a-06 shown kept", shown, h.shown)
        assertEquals("C3a-06 no new read", 1, h.tabs.reads.size)
        assertEquals("C3a-06 no setFocus", focused, h.focused)
        assertEquals("C3a-06 no save", emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
        h.scope.cancel()
    }

    @Test fun `C3a-07a a restore that settles after the provider's scope ended shows nothing`() = runTest {
        val h = started()
        h.scope.cancel()
        h.read(0).complete(FreeTab.TETHER)
        runCurrent()
        assertNull("C3a-07a", h.shown)
    }
}
