package com.jay.fxi.data.auth

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1 recovery signal §2: when [AuthTokenProvider] announces that a failed identity has a credential again. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthTokenProviderRecoveryTest {

    private val userA1 = AuthIdentity("user-a", 1)

    private class Source(var identity: AuthIdentity?) : AuthTokenSource {
        var fetchCount = 0
        var forceRefreshCount = 0
        var fetch: suspend (AuthIdentity, Boolean) -> String = { _, _ -> "token" }

        override fun currentIdentity(): AuthIdentity? = identity

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetchCount += 1
            if (forceRefresh) forceRefreshCount += 1
            return fetch(identity, forceRefresh)
        }
    }

    /**
     * The provider's process scope. Not `backgroundScope`: a lookup that fails there would fail the test, where production's
     * supervisor scope only hands the failure to its waiters.
     */
    private val processJob = SupervisorJob()

    private fun TestScope.provider(source: Source, orders: AccessOrderSequence = AccessOrderSequence()) =
        AuthTokenProvider(source, CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), orders)

    private fun recoveryTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    private fun AuthTokenProvider.recorded(): List<AuthCredentialRecovery> =
        ArrayList<AuthCredentialRecovery>().also { events -> observe { events += it } }

    private fun unavailable() = AuthUnavailableException("Firebase ID token acquisition failed")

    private suspend fun AuthTokenProvider.failedLookup(): Throwable? = runCatching { currentSnapshot() }.exceptionOrNull()

    /** The same failure a waiter got before this change. By class and message: `await` may hand over a recovered copy. */
    private fun assertSameFailure(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    @Test
    fun failureThenSuccess_emitsOneRecoveryCarryingTheAcquisitionOrders() = recoveryTest {
        val source = Source(userA1)
        val orders = AccessOrderSequence()
        val provider = provider(source, orders)
        val events = provider.recorded()
        val failure = unavailable()
        source.fetch = { _, _ -> throw failure }

        // The acquisition result is unchanged: the waiter gets the same exception.
        assertSameFailure(failure, provider.failedLookup())
        runCurrent()
        source.fetch = { _, _ -> "token-2" }

        assertEquals("token-2", provider.currentSnapshot().token)
        runCurrent()
        assertEquals("token-2", provider.currentSnapshot().token)

        // Orders: lookup 1 started (1), failed (2); lookup 2 started (3) and recovered (4); lookup 3 started (5).
        assertEquals(listOf(AuthCredentialRecovery(AuthIdentityFence("user-a", 1), 1L, 3L, 4L)), events)
        assertEquals(6L, orders.next())
    }

    @Test
    fun severalFailuresAndSuccesses_emitOncePerRun() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()

        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()
        runCurrent()
        provider.currentSnapshot()
        runCurrent()
        assertEquals(listOf(1L), events.map { it.episode })

        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()

        assertEquals(listOf(1L, 2L), events.map { it.episode })
    }

    @Test
    fun oneFailureAwaitedByTwoWaiters_isRecordedOnce() = recoveryTest {
        val source = Source(userA1)
        val orders = AccessOrderSequence()
        val provider = provider(source, orders)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = unavailable()
        source.fetch = { _, _ ->
            started.complete(Unit)
            release.await()
            throw failure
        }

        val first = async { provider.failedLookup() }
        started.await()
        val second = async { provider.failedLookup() }
        runCurrent()
        release.complete(Unit)

        assertSameFailure(failure, first.await())
        assertSameFailure(failure, second.await())
        assertEquals(1, source.fetchCount)
        // One start (1) and one failure (2), whatever the number of waiters.
        assertEquals(3L, orders.next())
    }

    @Test
    fun acquisitionStartedBeforeTheLatestFailure_doesNotRecover_evenForAWaiterThatJoinedAfterIt() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.fetch = { _, _ ->
            started.complete(Unit)
            release.await()
            "token"
        }
        val early = async { provider.currentSnapshot() }
        started.await()
        // A newer failure lands while that lookup is still out.
        provider.recordRejected(AuthSnapshot("user-a", 1, "replayed"))
        val joined = async { provider.currentSnapshot() }
        runCurrent()
        release.complete(Unit)

        assertEquals("token", early.await().token)
        assertEquals("token", joined.await().token)
        assertEquals(2, source.fetchCount)
        assertTrue(events.isEmpty())

        runCurrent()
        source.fetch = { _, _ -> "token" }
        val recovered = provider.currentSnapshot()

        assertEquals("token", recovered.token)
        assertEquals(1, events.size)
    }

    @Test
    fun knownRejectedCredential_isNotARecovery() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        provider.recordRejected(AuthSnapshot("user-a", 1, "bad"))

        source.fetch = { _, _ -> "bad" }
        assertEquals("bad", provider.currentSnapshot().token)
        runCurrent()
        assertTrue(events.isEmpty())

        source.fetch = { _, _ -> "good" }
        provider.currentSnapshot()

        assertEquals(1, events.size)
    }

    @Test
    fun unusableRefreshVerdict_opensARun_thatANewCredentialRecovers() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> "same" }
        val rejected = provider.currentSnapshot()

        assertNull(provider.refreshAfterUnauthorized(rejected))
        runCurrent()
        // The forced lookup inside that refresh returned the rejected token: not a recovery.
        assertTrue(events.isEmpty())

        source.fetch = { _, _ -> "new" }
        provider.currentSnapshot()

        assertEquals(1, events.size)
        assertEquals(1, source.forceRefreshCount)
    }

    /** With a run open, the forced lookup of a refresh still in flight that hands the rejected token back is no recovery. */
    @Test
    fun rejectedTokenReturnedByARefreshInFlight_isNotARecovery() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> "rejected" }
        val rejected = provider.currentSnapshot()
        runCurrent()
        source.fetch = { _, forceRefresh -> if (forceRefresh) "rejected" else throw unavailable() }
        provider.failedLookup()
        runCurrent()

        assertNull(provider.refreshAfterUnauthorized(rejected))
        runCurrent()
        assertTrue("the refresh's own lookup of the rejected token recovered", events.isEmpty())

        source.fetch = { _, _ -> "new" }
        provider.currentSnapshot()

        assertEquals(listOf(1L), events.map { it.episode })
    }

    @Test
    fun rememberedVerdictAndRepeatedRejection_openNoNewRun_andRecoveryKeepsTheRejectionMemory() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> "same" }
        val rejected = provider.currentSnapshot()
        assertNull(provider.refreshAfterUnauthorized(rejected))
        runCurrent()
        source.fetch = { _, _ -> "new" }
        provider.currentSnapshot()
        runCurrent()
        assertEquals(1, events.size)

        // The remembered Unusable answers again without a new refresh, and is not a new failure.
        assertNull(provider.refreshAfterUnauthorized(rejected))
        runCurrent()
        provider.currentSnapshot()
        runCurrent()
        assertEquals(1, events.size)
        assertTrue(provider.isKnownRejected(rejected))
        assertEquals(1, source.forceRefreshCount)

        // A rejection recorded twice is one piece of evidence.
        val replayed = AuthSnapshot("user-a", 1, "replayed")
        provider.recordRejected(replayed)
        provider.currentSnapshot()
        runCurrent()
        assertEquals(2, events.size)
        provider.recordRejected(replayed)
        provider.currentSnapshot()

        assertEquals(2, events.size)
        assertTrue(provider.isKnownRejected(replayed))
    }

    @Test
    fun failedForcedRefresh_isRecordedOnceByTheLookupThatSawIt() = recoveryTest {
        val source = Source(userA1)
        val orders = AccessOrderSequence()
        val provider = provider(source, orders)
        val events = provider.recorded()
        source.fetch = { _, forceRefresh -> if (forceRefresh) throw unavailable() else "old" }
        val rejected = provider.currentSnapshot()

        assertNull(provider.refreshAfterUnauthorized(rejected))
        // Lookup started (1); forced lookup started (2) and failed (3). The refresh's Unusable is that same failure.
        assertEquals(4L, orders.next())

        runCurrent()
        source.fetch = { _, _ -> "fresh" }
        provider.currentSnapshot()

        assertEquals(1, events.size)
    }

    @Test
    fun identityChange_dropsTheOpenRunWithoutAnEvent() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()

        source.identity = AuthIdentity("user-a", 2)
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()
        runCurrent()
        assertTrue(events.isEmpty())

        source.identity = userA1
        provider.currentSnapshot()
        runCurrent()
        assertTrue(events.isEmpty())

        source.identity = AuthIdentity("user-a", 3)
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()

        assertEquals(listOf(AuthIdentityFence("user-a", 3) to 2L), events.map { it.fence to it.episode })
    }

    @Test
    fun noUserAndAFailureAfterTheUserLeft_openNoRun() = recoveryTest {
        val source = Source(null)
        val provider = provider(source)
        val events = provider.recorded()

        assertTrue(provider.failedLookup() is AuthUnavailableException)
        assertEquals(0, source.fetchCount)

        source.identity = userA1
        source.fetch = { _, _ ->
            source.identity = null
            throw AuthUnavailableException("No authenticated user")
        }
        // The failure is still thrown as it was; the user it was captured for is gone, so nothing is recorded.
        assertTrue(provider.failedLookup() is AuthUnavailableException)
        runCurrent()

        source.identity = userA1
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()

        assertTrue(events.isEmpty())
    }

    @Test
    fun upstreamCancellation_opensNoRun() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> throw CancellationException("upstream Firebase Task cancelled") }
        assertTrue(provider.failedLookup() is CancellationException)
        runCurrent()

        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()

        assertTrue(events.isEmpty())
    }

    @Test
    fun sharedRecoveringLookup_emitsOneEvent_evenWhenAWaiterIsCancelled() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.fetch = { _, _ ->
            started.complete(Unit)
            release.await()
            "token"
        }
        val cancelled = async { provider.currentSnapshot() }
        started.await()
        val kept = async { provider.currentSnapshot() }
        runCurrent()
        cancelled.cancel()
        release.complete(Unit)

        assertEquals("token", kept.await().token)
        assertEquals(2, source.fetchCount)
        assertEquals(1, events.size)
    }

    @Test
    fun subscribers_seeOnlyEventsDecidedAfterTheyRegister() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val early = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()
        runCurrent()

        val late = provider.recorded()
        assertTrue(late.isEmpty())

        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()

        assertEquals(listOf(1L, 2L), early.map { it.episode })
        assertEquals(listOf(2L), late.map { it.episode })
    }

    /**
     * The identity moves between the lookup's own identity check and the provider's lock: the lookup still fails as before, and
     * no recovery is announced for the identity that left.
     */
    @Test
    fun identityMovingRightAfterTheLookupChecked_announcesNoRecovery() = recoveryTest {
        var identity: AuthIdentity? = userA1
        var flipOnNextRead = false
        var failNext = true
        val source = object : AuthTokenSource {
            override fun currentIdentity(): AuthIdentity? {
                val observed = identity
                if (flipOnNextRead) {
                    flipOnNextRead = false
                    identity = AuthIdentity("user-a", 2)
                }
                return observed
            }

            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
                if (failNext) {
                    failNext = false
                    throw unavailable()
                }
                // The next read is the lookup's check after this returns; the one after it is the provider's.
                flipOnNextRead = true
                return "token"
            }
        }
        val provider = AuthTokenProvider(source, CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), AccessOrderSequence())
        val events = provider.recorded()
        provider.failedLookup()
        runCurrent()

        assertTrue(provider.failedLookup() is AuthIdentityChangedException)
        runCurrent()

        assertTrue("a recovery was announced for the identity that left", events.isEmpty())
    }

    // ---- rejection evidence (L-4e E6a) ---------------------------------------------------------------------------------------

    @Test
    fun rememberedNullThroughBothRefusalApis_doesNotMoveTheFailureOrder() = recoveryTest {
        for (evidenceFirst in listOf(true, false)) {
            val source = Source(userA1)
            val orders = AccessOrderSequence()
            val provider = provider(source, orders)
            val events = provider.recorded()
            val refused = AuthSnapshot("user-a", 1, "refused")

            // Creates completedRefreshes[fp] = null without either refusal API.
            source.fetch = { _, _ -> "refused" }
            assertNull(provider.refreshAfterUnauthorized(refused))
            runCurrent()

            val release = CompletableDeferred<Unit>()
            source.fetch = { _, _ ->
                release.await()
                "fresh"
            }
            val recovering = async { provider.currentSnapshot() }
            runCurrent()

            val before = orders.next()
            if (evidenceFirst) {
                provider.recordRejectionEvidence(refused)
                provider.recordRejected(refused)
            } else {
                provider.recordRejected(refused)
                provider.recordRejectionEvidence(refused)
            }
            assertEquals(before + 1L, orders.next())

            release.complete(Unit)
            assertEquals("fresh", recovering.await().token)
            assertEquals(listOf(1L), events.map { it.episode })
            assertEquals(1, source.forceRefreshCount)
        }
    }

    /** Evidence opens a run: the refused token read again is no recovery, a new one is. */
    @Test
    fun rejectionEvidence_opensARun_theSameTokenIsNoRecovery_andANewOneIs() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        provider.recordRejectionEvidence(AuthSnapshot("user-a", 1, "refused"))

        source.fetch = { _, _ -> "refused" }
        provider.currentSnapshot()
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(provider.isKnownRejected(AuthSnapshot("user-a", 1, "refused")))

        source.fetch = { _, _ -> "fresh" }
        provider.currentSnapshot()

        assertEquals(listOf(1L), events.map { it.episode })
    }

    /** One refusal reported through both calls, in either order and across a recovery, is one failure. */
    @Test
    fun theSameRefusalThroughBothCalls_acrossARecovery_isNeverANewFailure() = recoveryTest {
        for (evidenceFirst in listOf(true, false)) {
            val source = Source(userA1)
            val provider = provider(source)
            val events = provider.recorded()
            val refused = AuthSnapshot("user-a", 1, "refused")
            if (evidenceFirst) provider.recordRejectionEvidence(refused) else provider.recordRejected(refused)
            source.fetch = { _, _ -> "fresh" }
            provider.currentSnapshot()
            runCurrent()
            assertEquals(1, events.size)

            if (evidenceFirst) provider.recordRejected(refused) else provider.recordRejectionEvidence(refused)
            provider.currentSnapshot()
            runCurrent()

            assertEquals("evidenceFirst=$evidenceFirst: 같은 거부가 새 실패가 됐다", 1, events.size)
        }
    }

    /**
     * A refusal recorded while that credential's own forced refresh is running: the refresh then writes its result over the spent
     * marker. The fingerprint still says the refusal was reported, so reporting it again as evidence is not a new failure — and a
     * lookup that started after the first report still recovers.
     */
    @Test
    fun aRefusalRecordedDuringItsOwnRefresh_isStillReportedOnce() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val events = provider.recorded()
        val refused = AuthSnapshot("user-a", 1, "refused")
        val forcedStarted = CompletableDeferred<Unit>()
        val releaseForced = CompletableDeferred<Unit>()
        source.fetch = { _, forceRefresh ->
            if (forceRefresh) {
                forcedStarted.complete(Unit)
                releaseForced.await()
                "replacement"
            } else {
                "refused"
            }
        }
        val refresh = async { provider.refreshAfterUnauthorized(refused) }
        forcedStarted.await()
        provider.recordRejected(refused)
        releaseForced.complete(Unit)
        assertEquals("replacement", refresh.await()?.token)
        runCurrent()

        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        source.fetch = { _, _ ->
            lookupStarted.complete(Unit)
            releaseLookup.await()
            "fresh"
        }
        val lookup = async { provider.currentSnapshot() }
        lookupStarted.await()
        provider.recordRejectionEvidence(refused)
        releaseLookup.complete(Unit)
        lookup.await()

        assertEquals("같은 거부의 재보고가 새 실패가 되어 회복을 막았다", 1, events.size)
    }

    /** Evidence spends nothing: the refused credential's forced refresh still runs, and a refresh handing a refused token back is unusable. */
    @Test
    fun rejectionEvidence_leavesTheForcedRefreshUnspent_butARefusedTokenIsNeverReplayed() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        val first = AuthSnapshot("user-a", 1, "first")
        provider.recordRejectionEvidence(first)
        source.fetch = { _, forceRefresh -> if (forceRefresh) "second" else "first" }

        assertEquals("second", provider.refreshAfterUnauthorized(first)?.token)
        assertEquals(1, source.forceRefreshCount)

        val third = AuthSnapshot("user-a", 1, "third")
        provider.recordRejectionEvidence(third)
        runCurrent()
        source.fetch = { _, forceRefresh -> if (forceRefresh) "third" else "second" }
        assertNull("거부된 토큰을 replay 후보로 돌려줬다", provider.refreshAfterUnauthorized(AuthSnapshot("user-a", 1, "second")))
        assertEquals(2, source.forceRefreshCount)
    }

    /** Evidence belongs to its identity: another identity starts clean, and evidence for one that left is refused. */
    @Test
    fun rejectionEvidence_isScopedToItsIdentity() = recoveryTest {
        val source = Source(userA1)
        val provider = provider(source)
        provider.recordRejectionEvidence(AuthSnapshot("user-a", 1, "refused"))

        source.identity = AuthIdentity("user-a", 2)
        provider.recordRejectionEvidence(AuthSnapshot("user-a", 2, "other"))

        assertFalse(provider.isKnownRejected(AuthSnapshot("user-a", 2, "refused")))
        assertTrue(runCatching { provider.recordRejectionEvidence(AuthSnapshot("user-a", 1, "late")) }.exceptionOrNull() is AuthIdentityChangedException)
    }

    /**
     * Two recoveries decided on two threads while the first is still being delivered. The second delivery is held at the pump
     * (its thread is BLOCKED inside `deliverOwedRecoveries`) until the first callback returns: then every subscriber gets each
     * event it was registered for, once and in order, and no callback runs inside another. A subscriber registered from inside a
     * callback gets only the events decided after it. Real threads, so every wait is bounded and the gate opens on failure too.
     */
    @Test(timeout = 30_000)
    fun recoveriesDecidedOnTwoThreads_areDeliveredOnceInOrder_withoutNesting() {
        val workers = Collections.synchronizedList(ArrayList<Thread>())
        val workerFactory = Executors.defaultThreadFactory()
        val pool = Executors.newFixedThreadPool(3) { task ->
            workerFactory.newThread(task).also { workers += it }
        }
        val job = SupervisorJob()
        val release = CountDownLatch(1)
        try {
            val inFirst = CountDownLatch(1)
            var failNext = true
            val source = object : AuthTokenSource {
                override fun currentIdentity(): AuthIdentity = userA1

                override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
                    if (failNext) {
                        failNext = false
                        throw unavailable()
                    }
                    if (forceRefresh) {
                        return "token-2"
                    }
                    return "token-1"
                }
            }
            val provider = AuthTokenProvider(source, CoroutineScope(job + pool.asCoroutineDispatcher()), AccessOrderSequence())
            val first = Collections.synchronizedList(ArrayList<Long>())
            val nested = Collections.synchronizedList(ArrayList<Long>())
            val late = Collections.synchronizedList(ArrayList<Long>())
            val active = AtomicInteger()
            val mostActive = AtomicInteger()
            provider.observe { recovery ->
                mostActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                first += recovery.episode
                if (recovery.episode == 1L) {
                    provider.observe { nested += it.episode }
                    inFirst.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "the first callback was never released" }
                }
                active.decrementAndGet()
            }
            runBlocking {
                provider.failedLookup()
                val firstLookup = async(Dispatchers.Default) { provider.currentSnapshot() }
                check(inFirst.await(10, TimeUnit.SECONDS)) { "the first recovery was never delivered" }

                provider.observe { late += it.episode }
                provider.recordRejected(AuthSnapshot("user-a", 1, "replayed"))
                val refresh = async(Dispatchers.Default) { provider.refreshAfterUnauthorized(AuthSnapshot("user-a", 1, "token-1")) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (true) {
                    val snapshot = synchronized(workers) { workers.toList() }
                    val reachedPump = snapshot.any { worker ->
                        worker.state == Thread.State.BLOCKED && worker.stackTrace.any { frame ->
                            frame.className == AuthTokenProvider::class.java.name &&
                                frame.methodName == "deliverOwedRecoveries"
                        }
                    }
                    if (reachedPump) break
                    check(System.nanoTime() < deadline) { "the second delivery never reached the pump while the first held it" }
                    Thread.sleep(5)
                }
                release.countDown()

                assertEquals("token-1", firstLookup.await().token)
                assertEquals("token-2", refresh.await()?.token)
            }

            assertEquals(listOf(1L, 2L), first.toList())
            assertEquals("a subscriber registered inside a callback got the event being delivered", listOf(2L), nested.toList())
            assertEquals(listOf(2L), late.toList())
            assertEquals("a callback ran inside another", 1, mostActive.get())
        } finally {
            release.countDown()
            job.cancel()
            pool.shutdownNow()
        }
    }

    /** Production's reporter: called once, nothing uncaught, and the result, the other subscribers and later events unchanged. */
    @Test
    fun injectedSubscriberFailureReporter_isCalledOnce_andNothingElseChanges() = recoveryTest {
        val uncaught = ArrayList<Throwable>()
        val reported = ArrayList<Throwable>()
        val source = Source(userA1)
        val provider = AuthTokenProvider(
            source,
            CoroutineScope(
                processJob + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, failure -> uncaught += failure }
            ),
            AccessOrderSequence(),
            onRecoverySubscriberFailure = { reported += it }
        )
        val boom = IllegalStateException("subscriber failed")
        provider.observe { throw boom }
        val events = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()

        source.fetch = { _, _ -> "token" }
        assertEquals("token", provider.currentSnapshot().token)
        runCurrent()
        assertEquals(listOf<Throwable>(boom), reported)
        assertTrue(uncaught.isEmpty())
        assertEquals(1, events.size)

        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()
        runCurrent()

        assertEquals(2, events.size)
        assertEquals(2, reported.size)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun throwingSubscriber_costsNeitherTheResultNorTheOtherSubscribers_andItsExceptionIsRethrown() = recoveryTest {
        val uncaught = ArrayList<Throwable>()
        val source = Source(userA1)
        val provider = AuthTokenProvider(
            source,
            CoroutineScope(
                processJob + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, failure -> uncaught += failure }
            ),
            AccessOrderSequence()
        )
        val boom = IllegalStateException("subscriber failed")
        provider.observe { throw boom }
        val events = provider.recorded()
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()

        source.fetch = { _, _ -> "token" }
        assertEquals("token", provider.currentSnapshot().token)
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(listOf<Throwable>(boom), uncaught)

        // Delivery still works afterwards.
        source.fetch = { _, _ -> throw unavailable() }
        provider.failedLookup()
        runCurrent()
        source.fetch = { _, _ -> "token" }
        provider.currentSnapshot()
        runCurrent()

        assertEquals(2, events.size)
    }
}
