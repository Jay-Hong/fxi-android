package com.jay.fxi.data.auth

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forces the order a real dispatcher only sometimes produces: a shared lookup has completed and its
 * caller has resumed, but the launch that removes the completed entry has not run yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthTokenProviderCompletionOrderTest {

    /** Runs process-scope work only when the test says so, one dispatched block at a time. */
    private class ManualDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runNext() = queue.removeFirst().run()
    }

    private class Source(var identity: AuthIdentity) : AuthTokenSource {
        var token = "old-token"
        var fetchCount = 0
        var forceRefreshCount = 0
        var cancelFirstForced = false
        override fun currentIdentity(): AuthIdentity = identity
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetchCount += 1
            if (forceRefresh) {
                forceRefreshCount += 1
                if (cancelFirstForced && forceRefreshCount == 1) throw CancellationException("upstream Firebase Task cancelled")
                token = "token-$forceRefreshCount"
            }
            return token
        }
    }

    /**
     * A non-forced lookup completes; its removal is held. A forced refresh then replaces the credential.
     * The next non-forced lookup should see the refreshed credential, not the completed lookup's old one.
     */
    private fun nonForcedLookupAfterRefresh(runRemovalFirst: Boolean) = runTest {
        val source = Source(AuthIdentity("user-a", 1))
        val manual = ManualDispatcher()
        val job = SupervisorJob()
        val provider = AuthTokenProvider(source, CoroutineScope(job + manual))

        val first = async { provider.currentSnapshot() }
        runCurrent()
        manual.runNext() // the lookup body
        runCurrent()
        assertEquals("old-token", first.await().token)
        assertEquals("only the completed lookup's removal is queued", 1, manual.queue.size)
        val heldRemoval = manual.queue.removeFirst()

        val refresh = async { provider.refreshAfterUnauthorized(first.await()) }
        runCurrent()
        while (manual.queue.isNotEmpty()) {
            manual.runNext()
            runCurrent()
        }
        assertEquals("token-1", refresh.await()?.token)

        if (runRemovalFirst) heldRemoval.run()
        val second = async { provider.currentSnapshot() }
        runCurrent()
        while (manual.queue.isNotEmpty()) {
            manual.runNext()
            runCurrent()
        }
        assertEquals("token-1", second.await().token)
        if (!runRemovalFirst) heldRemoval.run()
        job.cancel()
    }

    /**
     * A forced refresh completes; the removal of its forced lookup is held. A second credential is then
     * rejected. Its own refresh should force a new lookup, not reuse the completed one and call the result unusable.
     */
    private fun secondRefreshAfterFirst(runRemovalFirst: Boolean) = runTest {
        val source = Source(AuthIdentity("user-a", 1))
        val manual = ManualDispatcher()
        val job = SupervisorJob()
        val provider = AuthTokenProvider(source, CoroutineScope(job + manual))
        fun drain() {
            while (manual.queue.isNotEmpty()) {
                manual.runNext()
            }
        }

        val first = async { provider.currentSnapshot() }
        runCurrent(); drain(); runCurrent()
        val rejected = first.await()

        val refresh = async { provider.refreshAfterUnauthorized(rejected) }
        runCurrent()
        manual.runNext() // refresh body: starts the forced lookup and waits on it
        manual.runNext() // forced lookup body: completes, queueing its removal and then the refresh's resumption
        assertEquals("forced lookup removal, then refresh resumption", 2, manual.queue.size)
        val heldRemoval = manual.queue.removeFirst()
        drain(); runCurrent()
        val refreshed = refresh.await()
        assertEquals("token-1", refreshed?.token)

        if (runRemovalFirst) heldRemoval.run()
        val second = async { provider.refreshAfterUnauthorized(checkNotNull(refreshed)) }
        runCurrent(); drain(); runCurrent(); drain(); runCurrent()
        val result = second.await()
        if (!runRemovalFirst) heldRemoval.run()
        job.cancel()

        assertEquals("token-2", result?.token)
        assertEquals(2, source.forceRefreshCount)
    }

    /**
     * A forced refresh is cancelled upstream; both removals — the forced lookup's and the refresh's — are held. The
     * cancelled refresh is not latched, so it is not known rejected and a later caller retries instead of inheriting
     * the cancellation.
     */
    private fun laterRefreshAfterCancelled(runRemovalsFirst: Boolean) = runTest {
        val source = Source(AuthIdentity("user-a", 1)).apply { cancelFirstForced = true }
        val manual = ManualDispatcher()
        val job = SupervisorJob()
        val provider = AuthTokenProvider(source, CoroutineScope(job + manual))
        fun drain() {
            while (manual.queue.isNotEmpty()) {
                manual.runNext()
            }
        }

        val first = async { provider.currentSnapshot() }
        runCurrent(); drain(); runCurrent()
        val rejected = first.await()

        val refresh = async { runCatching { provider.refreshAfterUnauthorized(rejected) } }
        runCurrent()
        manual.runNext() // refresh body: starts the forced lookup and waits on it
        manual.runNext() // forced lookup body: throws, queueing its removal and then the refresh's resumption
        assertEquals("forced lookup removal, then refresh resumption", 2, manual.queue.size)
        val heldLookupRemoval = manual.queue.removeFirst()
        manual.runNext() // the refresh resumes and fails, queueing its own removal
        assertEquals("only the failed refresh's removal is queued", 1, manual.queue.size)
        val heldRefreshRemoval = manual.queue.removeFirst()
        runCurrent()
        assertTrue(refresh.await().exceptionOrNull() is CancellationException)

        if (runRemovalsFirst) {
            heldLookupRemoval.run()
            heldRefreshRemoval.run()
        }
        val known = provider.isKnownRejected(rejected)
        val later = async { runCatching { provider.refreshAfterUnauthorized(rejected) } }
        runCurrent(); drain(); runCurrent(); drain(); runCurrent()
        val result = later.await()
        if (!runRemovalsFirst) {
            heldLookupRemoval.run()
            heldRefreshRemoval.run()
        }
        job.cancel()

        assertFalse("a cancelled refresh was latched as a rejection", known)
        assertEquals("token-2", result.getOrNull()?.token)
        assertEquals(2, source.forceRefreshCount)
    }

    @Test
    fun control_laterRefreshAfterCancelled_removalsRunFirst() = laterRefreshAfterCancelled(runRemovalsFirst = true)

    @Test
    fun repro_laterRefreshAfterCancelled_removalsStillQueued() = laterRefreshAfterCancelled(runRemovalsFirst = false)

    @Test
    fun control_nonForcedLookupAfterRefresh_removalRunFirst() = nonForcedLookupAfterRefresh(runRemovalFirst = true)

    @Test
    fun repro_nonForcedLookupAfterRefresh_removalStillQueued() = nonForcedLookupAfterRefresh(runRemovalFirst = false)

    @Test
    fun control_secondRefreshAfterFirst_removalRunFirst() = secondRefreshAfterFirst(runRemovalFirst = true)

    @Test
    fun repro_secondRefreshAfterFirst_removalStillQueued() = secondRefreshAfterFirst(runRemovalFirst = false)
}
