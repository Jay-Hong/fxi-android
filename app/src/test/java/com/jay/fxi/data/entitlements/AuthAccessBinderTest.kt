package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The binder is the only caller of the coordinator's identity transitions, so what it filters and
 * what order it preserves *is* the contract.
 *
 * Every test drives [AuthAccessBinder.onUidObserved] directly. Going through Firebase would test
 * Firebase; the seam is where this class's own decisions live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthAccessBinderTest {

    private val processJob = SupervisorJob()

    /** The coordinator the last [build] made, for reading its recovery status. */
    private lateinit var built: PremiumAccessCoordinator

    /** A consumer that stops waiting and spins can keep a test from finishing; fail the test instead. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    /** What the coordinator was told, in order. */
    private sealed interface Call {
        data class OwnerChanged(val uid: String) : Call
        data object SignedOut : Call
        /** Recorded only when the store is asked to retire; a start with nothing owed never asks. */
        data object RetiredUnverifiedStart : Call
    }

    /**
     * Records against the real coordinator's collaborators rather than mocking the coordinator:
     * `PremiumAccessCoordinator` is a class, and a recording subclass would have to reproduce its
     * locking. Driving the real one and reading the store is closer to production.
     */
    private class RecordingStore(private val calls: MutableList<Call>) : AccessEpochStore {
        var record = AccessEpochRecord()
        private val ids = object : EpochIdGenerator {
            private var n = 0
            override fun next() = "epoch-${n++}"
        }

        /** Parks the next bind, to hold the consumer while more work queues behind it. */
        var blockNextBindOn: CompletableDeferred<Unit>? = null

        /** Parks one read, including the first read of an executing preparation. */
        var blockNextLoadOn: CompletableDeferred<Unit>? = null

        /** Fails the next bind before it writes anything. */
        var failNextBind = false

        /** Fails every bind before it writes anything, while set. */
        var failBinds = false

        /** Fails the next bind *after* it reached the record, so a read-back can see it landed. */
        var failNextBindAfterWrite = false

        /** Fails the next sign-out before it writes anything. */
        var failNextSignOut = false

        /** Fails the next read. */
        var failNextLoad = false

        /** Runs after the sign-out intent reached the record. */
        var onBeginSignOut: () -> Unit = {}

        /** Runs after a bind reached the record. */
        var onBind: () -> Unit = {}

        /** Every bind call, failed ones included. */
        var bindAttempts = 0

        /** Every read, failed ones included — what shows a start's settlement asked the store at all. */
        var loads = 0

        /** Fails the next retirement before it writes anything. */
        var failNextRetire = false

        override suspend fun retireUnverifiedStart(): AccessEpochRecord {
            if (failNextRetire) {
                failNextRetire = false
                throw java.io.IOException("simulated write failure")
            }
            calls += Call.RetiredUnverifiedStart
            return AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        }

        override suspend fun load(): AccessEpochRecord {
            loads += 1
            blockNextLoadOn?.let { gate -> blockNextLoadOn = null; gate.await() }
            if (failNextLoad) {
                failNextLoad = false
                throw java.io.IOException("simulated read failure")
            }
            return record
        }
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            blockNextBindOn?.let { gate -> blockNextBindOn = null; gate.await() }
            bindAttempts += 1
            if (failNextBind || failBinds) {
                failNextBind = false
                throw java.io.IOException("simulated write failure")
            }
            calls += Call.OwnerChanged(uid)
            val bound = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it; onBind() }
            if (failNextBindAfterWrite) {
                failNextBindAfterWrite = false
                throw java.io.IOException("simulated failure after the write")
            }
            return bound
        }

        override suspend fun signOut(): AccessEpochRecord {
            if (failNextSignOut) {
                failNextSignOut = false
                throw java.io.IOException("simulated write failure")
            }
            calls += Call.SignedOut
            return AccessEpochTransitions.signOut(record, ids).also { record = it }
        }

        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }

        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }

        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }

        override suspend fun beginSignOut(uid: String) =
            AccessEpochTransitions.beginSignOut(record, uid).also { record = it; onBeginSignOut() }
    }

    /** Deferred, like production: the journal survives so a resume stays observable. */
    private class RecordingPurger : UserScopePurger, CapabilityScopePurger {
        val attempts = mutableListOf<String?>()
        /** Runs inside the purge, which the binder calls on its consumer. */
        var onPurge: suspend () -> Unit = {}
        override suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult {
            attempts += namespace.pending.ownerUid
            onPurge()
            return PurgeResult.Deferred("not this slice")
        }

        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult {
            attempts += namespace.pending.ownerUid
            return PurgeResult.Deferred("not this slice")
        }
    }

    private fun TestScope.build(
        calls: MutableList<Call>,
        purger: RecordingPurger = RecordingPurger(),
        seedJournal: Boolean = false,
        /** `fresh_premium` flag of every query the binding issued, in order. */
        fetches: MutableList<Boolean> = mutableListOf(),
        /** What the coordinator reads as the live fence. Signed out unless a test says otherwise. */
        live: () -> AuthIdentityFence? = { null },
        store: RecordingStore = RecordingStore(calls),
        /** Collects failures that end a coroutine of the binder's scope, instead of failing the test run. */
        uncaught: MutableList<Throwable>? = null,
        /** Hands over the coordinator, for a test that reads back an edit from outside the FIFO. */
        onCoordinator: (PremiumAccessCoordinator) -> Unit = {},
        /** Off unless a test is about the automatic run: the others drive recoverSignOut themselves. */
        recoverAutomatically: Boolean = false,
        /**
         * Replays [initial] once on registration, as the production stream does. Off by default so a
         * test that drives [AuthAccessBinder.onFenceObserved] itself controls every observation.
         */
        replaying: Boolean = false,
        initial: AuthIdentityFence? = null
    ): AuthAccessBinder {
        if (seedJournal) {
            // A journal a previous process left behind — the only thing that makes a resume
            // observable, since an empty journal returns before touching a purger.
            store.record = AccessEpochRecord(
                ownerUid = "previous-owner",
                userAccessEpoch = "old-user-epoch",
                krxCapabilityEpoch = "old-krx-epoch",
                pendingPurges = listOf(
                    PendingPurge(
                        ownerUid = "previous-owner",
                        userAccessEpoch = "older-user-epoch",
                        krxCapabilityEpoch = null,
                        scopes = setOf(PurgeScope.USER)
                    )
                )
            )
        }
        val handler = uncaught?.let { sink -> CoroutineExceptionHandler { _, failure -> sink += failure } }
        val scope = CoroutineScope(
            processJob + StandardTestDispatcher(testScheduler) + (handler ?: EmptyCoroutineContext)
        )
        val coordinator = PremiumAccessCoordinator(
            source = object : EntitlementsSource {
                // Terminal on purpose. Binding an owner now issues one `fresh_premium` query, and
                // every `Indeterminate` reason schedules a recheck — so a transient answer would put
                // the coordinator on a ladder that never ends and `advanceUntilIdle` would *hang*
                // rather than fail. These tests are about the funnel, so the query answers once and
                // stops. The identity is read from the store because that is what the binding just
                // wrote; an answer for anyone else is discarded by the coordinator's own fence.
                private fun boundIdentity() =
                    EntitlementsIdentity(store.record.ownerUid.orEmpty(), 1L)

                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
                    fetches += freshPremium
                    return EntitlementsResult.Answered(
                        identity = boundIdentity(),
                        outcome = EntitlementsOutcome.StableInactive(krxVisible = false)
                    )
                }

                override suspend fun currentIdentity(): EntitlementsIdentity = boundIdentity()
            },
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = scope,
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = live
        )
        built = coordinator
        onCoordinator(coordinator)
        // No stream unless asked: most tests drive onFenceObserved themselves, so registration order
        // cannot make them pass for the wrong reason. The replaying stream is for the start-up order.
        val stream = if (replaying) AuthFenceStream { onFence -> onFence(initial) } else AuthFenceStream { }
        return AuthAccessBinder(coordinator, scope, stream, recoverAutomatically)
    }

    @Test
    fun repeatedSameUid_bindsTheOwnerExactlyOnce() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        repeat(3) { binder.onFenceObserved(fenceOf("user-a")) }
        advanceUntilIdle()

        // The fence stream replays the current fence to every new subscriber. Without the dedup
        // each replay re-enters onIdentityChanged, which resets a live grant to NoGrant.
        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    @Test
    fun signOutThenSignIn_isDispatchedInEmittedOrder() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onFenceObserved(fenceOf("user-a"))
        binder.onFenceObserved(null)
        binder.onFenceObserved(fenceOf("user-b"))
        advanceUntilIdle()

        // Inverting the middle pair would leave user-b signed in with signOut() applied last.
        assertEquals(
            listOf(
                Call.OwnerChanged("user-a"),
                Call.SignedOut,
                Call.OwnerChanged("user-b")
            ),
            calls
        )
        processJob.cancel()
    }

    @Test
    fun coldStartWithNoUser_doesNotSignOut_butStillResumesPurges() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onFenceObserved(null)
        advanceUntilIdle()

        // No owner, marker or intent on the record, so a first null has nothing to settle: nothing is
        // bound, signed out or retired. The start that does owe a retirement is its own test below.
        assertEquals(emptyList<Call>(), calls)
        processJob.cancel()
    }

    @Test
    fun signedOutCold_thenRealSignOutLater_stillDispatchesIt() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onFenceObserved(null)
        binder.onFenceObserved(fenceOf("user-a"))
        binder.onFenceObserved(null)
        advanceUntilIdle()

        // The cold-start no-op must not latch: a genuine A -> null transition is a teardown.
        assertEquals(
            listOf(Call.OwnerChanged("user-a"), Call.SignedOut),
            calls
        )
        processJob.cancel()
    }

    @Test
    fun repeatedNulls_signOutOnlyOnce() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onFenceObserved(fenceOf("user-a"))
        repeat(3) { binder.onFenceObserved(null) }
        advanceUntilIdle()

        assertEquals(
            listOf(Call.OwnerChanged("user-a"), Call.SignedOut),
            calls
        )
        processJob.cancel()
    }

    /**
     * The explicit resume at [AuthAccessBinder.start] retries a previous process's journal before
     * any observation arrives. With no observation at all — this test sends none — it is the only
     * path that does; a first observation may then land an edit whose cleanup resumes it again.
     */
    @Test
    fun signedOutColdStart_stillRetriesAJournalledPurge() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        val binder = build(calls, purger, seedJournal = true)

        binder.start()
        advanceUntilIdle()

        assertEquals(listOf("previous-owner"), purger.attempts)
        assertEquals("no observation yet, so nothing may bind, tear down or retire", emptyList<Call>(), calls)
        processJob.cancel()
    }

    /**
     * An existing subscriber who merely restores a login presses no purchase button, and
     * `onIdentityChanged` asks the server nothing — it binds the owner and resets to NoGrant. D23 also
     * says a cold-start grant can only come from a `fresh_premium` answer. Without a query here,
     * that user sits on the free surface for the life of the process.
     */
    @Test
    fun bindingAnOwner_asksTheServerOnceWithFreshPremium() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val binder = build(calls, fetches = fetches)
        binder.start()

        binder.onFenceObserved(fenceOf("user-a"))
        advanceUntilIdle()

        assertEquals("the restored login never reached the server", listOf(true), fetches)
        processJob.cancel()
    }

    /** Firebase replays the current user, and a replay is not a new binding. */
    @Test
    fun aReplayedUid_doesNotReAskTheServer() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val binder = build(calls, fetches = fetches)
        binder.start()

        repeat(3) { binder.onFenceObserved(fenceOf("user-a")) }
        advanceUntilIdle()

        assertEquals(listOf(true), fetches)
        processJob.cancel()
    }

    /**
     * `launch` orders nothing against a sign-out that arrives while the query is still queued. An
     * unpinned query would then start under the *next* generation, apply cleanly, and re-arm a
     * recheck for a session that has ended.
     */
    @Test
    fun aQueryQueuedBehindASignOut_doesNotRunUnderTheNextGeneration() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val binder = build(calls, fetches = fetches)
        binder.start()

        // Both events are consumed before either launched query gets to run.
        binder.onFenceObserved(fenceOf("user-a"))
        binder.onFenceObserved(null)
        advanceUntilIdle()

        assertEquals(
            "a query pinned to a binding ran after that binding was torn down",
            emptyList<Boolean>(),
            fetches
        )
        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        processJob.cancel()
    }

    // ---- fence-keyed binding (L-4b) --------------------------------------------------------------

    /**
     * B1 — the same account on a new session rebinds.
     *
     * The uid does not move here: `authGeneration` also advances on the explicit invalidation the
     * tracked sign-in path runs. Keyed on the bare uid this transition is invisible, and the
     * session that L-4a retired would never be handed a fence to recover on.
     */
    @Test
    fun sameUidWithANewGeneration_rebinds() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onFenceObserved(fenceOf("user-a", 1L))
        binder.onFenceObserved(fenceOf("user-a", 2L))
        advanceUntilIdle()

        assertEquals(
            listOf(Call.OwnerChanged("user-a"), Call.OwnerChanged("user-a")),
            calls
        )
        processJob.cancel()
    }

    /** B2 — the same fence delivered again is not a transition. */
    @Test
    fun theSameFenceDeliveredAgain_doesNotRebind() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        repeat(3) { binder.onFenceObserved(fenceOf("user-a", 4L)) }
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    /**
     * A sign-out request queued behind the binding of its own fence is judged after that binding.
     * Run out of order, the fence it names would not be bound yet and it could not arm.
     */
    @Test
    fun aSignOutRequestRunsAfterEveryEventQueuedBeforeIt() = runTest {
        val fence = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { fence })
        binder.start()

        binder.onFenceObserved(fence)
        val start = async { binder.beginSignOut(fence) }
        advanceUntilIdle()

        assertTrue(start.await() is SignOutStart.Armed)
        processJob.cancel()
    }

    @Test
    fun aStoppedConsumerFailsTheRequestsStillWaitingOnIt() = runTest {
        val fence = fenceOf("user-a", 7L)
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate
        val binder = build(calls, live = { fence }, store = store)
        binder.start()
        binder.onFenceObserved(fence)
        runCurrent() // the consumer is now parked inside the bind

        val waiting = async { runCatching { binder.beginSignOut(fence) } }
        runCurrent()
        processJob.cancel()
        runCurrent()

        val queued = waiting.await().exceptionOrNull()
        assertTrue("대기 중이던 요청이 실패로 끝나지 않았다: $queued", queued.isStoppedConsumer())
        val later = runCatching { binder.beginSignOut(fence) }.exceptionOrNull()
        assertTrue("멈춘 consumer 에 새 요청이 들어갔다: $later", later.isStoppedConsumer())
    }

    @Test
    fun cancellationBeforeTheConsumerStartsFailsQueuedAndLaterRequests() = runTest {
        val fence = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { fence })
        binder.start()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { binder.beginSignOut(fence) }
        }
        try {
            processJob.cancel() // the scheduled consumer body has not run
            runCurrent()

            assertTrue("request stranded before consumer entry", waiting.isCompleted)
            assertTrue(waiting.await().exceptionOrNull().isStoppedConsumer())
            assertTrue(runCatching { binder.beginSignOut(fence) }.exceptionOrNull().isStoppedConsumer())
        } finally {
            waiting.cancel()
            processJob.cancel()
        }
    }

    @Test
    fun cancellationAfterReceiveBeforeDispatchFailsTheRemovedRequest() = runTest {
        val fence = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { fence })
        binder.start()
        runCurrent() // the consumer is suspended waiting for an inbox item
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { binder.beginSignOut(fence) }
        }
        try {
            // The send resumed the receiver, but its dispatcher has not run it yet.
            processJob.cancel()
            runCurrent()

            assertTrue("request lost between receive and dispatch", waiting.isCompleted)
            assertTrue(waiting.await().exceptionOrNull().isStoppedConsumer())
        } finally {
            waiting.cancel()
            processJob.cancel()
        }
    }

    @Test
    fun cancellationDuringPreparationIsReportedAsConsumerFailure() = runTest {
        val fence = fenceOf("user-a", 7L)
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val binder = build(calls, live = { fence }, store = store)
        binder.start()
        binder.onFenceObserved(fence)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.blockNextLoadOn = gate
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { binder.beginSignOut(fence) }
        }
        try {
            runCurrent()
            assertEquals(null, store.blockNextLoadOn)
            assertTrue("preparation did not suspend at the read", !waiting.isCompleted)
            processJob.cancel()
            runCurrent()

            assertTrue("executing request was not completed", waiting.isCompleted)
            assertTrue(waiting.await().exceptionOrNull().isStoppedConsumer())
        } finally {
            gate.complete(Unit)
            waiting.cancel()
            processJob.cancel()
        }
    }

    @Test
    fun callerCancellationDoesNotWithdrawAnEnqueuedPreparation() = runTest {
        val fence = fenceOf("user-a", 7L)
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate
        val binder = build(calls, live = { fence }, store = store)
        binder.start()
        binder.onFenceObserved(fence)
        runCurrent()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { binder.beginSignOut(fence) }
        try {
            waiting.cancel()
            gate.complete(Unit)
            runCurrent()

            assertEquals(fence.uid, store.record.teardownOwedFor)
        } finally {
            gate.complete(Unit)
            waiting.cancel()
            processJob.cancel()
        }
    }

    /** From inside the consumer the request would wait on itself; it has to fail instead. */
    @Test
    fun aRequestFromTheConsumerFailsInsteadOfWaitingOnItself() = runTest {
        val fence = fenceOf("user-a", 7L)
        val purger = RecordingPurger()
        lateinit var binder: AuthAccessBinder
        var failure: Throwable? = null
        purger.onPurge = { failure = runCatching { binder.beginSignOut(fence) }.exceptionOrNull() }
        binder = build(mutableListOf(), purger = purger, seedJournal = true, live = { fence })

        binder.start() // resumes the seeded journal on the consumer
        advanceUntilIdle()

        assertTrue("consumer 안의 요청이 실패하지 않았다: $failure", failure is IllegalStateException)
        processJob.cancel()
    }

    @Test
    fun anUnexpectedFailureReachesTheCallerAndStopsTheConsumer() = runTest {
        val fence = fenceOf("user-a", 7L)
        val uncaught = mutableListOf<Throwable>()
        val binder = build(
            mutableListOf(),
            live = { throw IllegalArgumentException("live read") },
            uncaught = uncaught
        )
        binder.start()

        val failure = runCatching { binder.beginSignOut(fence) }.exceptionOrNull()
        advanceUntilIdle()

        assertTrue("준비 실패가 호출자에게 가지 않았다: $failure", failure is IllegalArgumentException)
        assertTrue("consumer 가 그 실패로 끝나지 않았다: $uncaught", uncaught.singleOrNull() is IllegalArgumentException)
        val later = runCatching { binder.beginSignOut(fence) }.exceptionOrNull()
        assertTrue("실패 뒤 consumer 가 계속 받았다: $later", later.isStoppedConsumer())
    }

    @Test
    fun aHeldEventIsRetriedFirstAndLaterOnesFollowInOrder() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val b = fenceOf("user-b", 3L)
        var live: AuthIdentityFence? = a
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(calls, live = { live }, store = store, onCoordinator = { coordinator = it })
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        val ticket = (binder.beginSignOut(a) as SignOutStart.Armed).ticket

        live = b
        store.failNextBind = true
        binder.onFenceObserved(b)
        binder.onFenceObserved(null)
        advanceUntilIdle()
        assertEquals("읽어 보기 전에 보류 사건 뒤의 사건이 적용됐다", listOf(Call.OwnerChanged("user-a")), calls)

        assertEquals(EditResolution.RESOLVED, coordinator.resolvePendingEdit(ticket))
        advanceUntilIdle()

        assertEquals(
            listOf(Call.OwnerChanged("user-a"), Call.OwnerChanged("user-b"), Call.SignedOut),
            calls
        )
        processJob.cancel()
    }

    // Recovery runs

    @Test
    fun aRecoveryRunBindsItsCandidateReleasesAndQueriesOnce() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val a = fenceOf("user-a", 7L)
        val binder = build(calls, fetches = fetches, live = { a })
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket

        assertEquals(RecoveryOutcome.FINISHED, binder.recoverSignOut(ticket))
        advanceUntilIdle()
        binder.onFenceObserved(a)
        advanceUntilIdle()

        assertEquals("barrier 가 맞춘 fence 를 다시 바인딩했다", listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals(listOf(true), fetches)
        processJob.cancel()
    }

    @Test
    fun aBarrierBindingMovesTheBoundFenceSoTheNextEndRotatesIt() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        var live: AuthIdentityFence? = a
        val binder = build(calls, live = { live }, store = store)
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        store.onBind = { live = null }

        assertEquals(RecoveryOutcome.HOLD, binder.recoverSignOut(ticket))
        binder.onFenceObserved(null)
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        processJob.cancel()
    }

    @Test
    fun aSignedOutRecoveryReleasesWithoutAQuery() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val a = fenceOf("user-a", 7L)
        var live: AuthIdentityFence? = a
        val binder = build(calls, fetches = fetches, live = { live })
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        val ticket = (binder.beginSignOut(a) as SignOutStart.Armed).ticket
        live = fenceOf("user-b", 3L)
        binder.onFenceObserved(null)
        advanceUntilIdle()
        live = null
        val queried = fetches.size

        assertEquals(RecoveryOutcome.FINISHED, binder.recoverSignOut(ticket))
        advanceUntilIdle()

        assertEquals(queried, fetches.size)
        assertEquals(1, calls.count { it == Call.SignedOut })
        processJob.cancel()
    }

    @Test
    fun anUnknownBarrierBindIsReadBackByTheRunWithoutDeadlock() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val binder = build(calls, live = { a }, store = store)
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        store.failNextBind = true

        assertEquals(RecoveryOutcome.FINISHED, binder.recoverSignOut(ticket))
        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals("실패한 bind 뒤 재개하지 않았다", 2, store.bindAttempts)
        processJob.cancel()
    }

    @Test
    fun anIdentityEventFailingAheadOfTheBarrierIsReadBackByTheRun() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val b = fenceOf("user-b", 3L)
        var current: AuthIdentityFence? = a
        var onNextRead: (() -> Unit)? = null
        lateinit var binder: AuthAccessBinder
        binder = build(calls, live = { onNextRead?.let { hook -> onNextRead = null; hook() }; current }, store = store)
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        // The candidate capture is the run's first live read. Like the tracker's drain, it queues the
        // event it saw ahead of the barrier — here one whose bind fails.
        onNextRead = {
            current = b
            store.failNextBind = true
            binder.onFenceObserved(b)
        }

        assertEquals(RecoveryOutcome.FINISHED, binder.recoverSignOut(ticket))
        // Failed, retried after the run read it back, then the barrier's own bind.
        assertEquals(3, store.bindAttempts)
        assertEquals(listOf(Call.OwnerChanged("user-b"), Call.OwnerChanged("user-b")), calls)
        processJob.cancel()
    }

    @Test
    fun aRunReadsBackOnceAndStopsWhenTheSameEditKeepsFailing() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(calls, live = { a }, store = store, onCoordinator = { coordinator = it })
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        store.failBinds = true

        assertEquals(RecoveryOutcome.UNRESOLVED, binder.recoverSignOut(ticket))
        assertEquals("한 번의 실행이 실패한 bind 를 두 번보다 많이 만났다", 2, store.bindAttempts)
        // A second run spends its one read-back on the step that finds the edit unresolved.
        assertEquals(RecoveryOutcome.UNRESOLVED, binder.recoverSignOut(ticket))
        assertEquals(3, store.bindAttempts)

        // The barrier stays at the FIFO head; once the edit reads back, the consumer finishes it.
        store.failBinds = false
        assertEquals(EditResolution.RESOLVED, coordinator.resolvePendingEdit(ticket))
        advanceUntilIdle()
        assertEquals(RecoveryOutcome.CLOSED, binder.recoverSignOut(ticket))
        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    @Test
    fun aHeldSignalSeenAfterTheReadBackIsSpentIsJudgedAgainUnderTheLock() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(calls, live = { a }, store = store, onCoordinator = { coordinator = it })
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        // An unresolved read, for the run to spend its one read-back on.
        store.failNextLoad = true
        assertEquals(RecoveryAdvance.UNRESOLVED, coordinator.advanceRecovery(ticket))
        store.failNextBind = true
        // Someone else reads back the barrier's failed bind the moment it holds events. Watching from
        // before the run starts, it is resumed ahead of the run's own watcher.
        val current = coordinator.awaitAttemptHeldOrGone(ticket, afterRevision = -1L)
        val other = launch {
            coordinator.awaitAttemptHeldOrGone(ticket, afterRevision = current.revision)
            coordinator.resolvePendingEdit(ticket)
        }
        runCurrent()

        val outcome = binder.recoverSignOut(ticket)

        assertEquals("이미 풀린 보류를 지난 신호만 보고 미해소로 보고했다", RecoveryOutcome.FINISHED, outcome)
        other.join()
        processJob.cancel()
    }

    @Test
    fun aRunGivesUpAfterItsBarrierReentriesWhileTheIdentityKeepsMoving() = runTest {
        val calls = mutableListOf<Call>()
        val a = fenceOf("user-a", 7L)
        var moving = false
        var generation = 100L
        val binder = build(calls, live = { if (moving) fenceOf("user-a", ++generation) else a })
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        moving = true

        assertEquals(RecoveryOutcome.RETRY_LATER, binder.recoverSignOut(ticket))
        assertEquals("움직이는 신원을 채택했다", emptyList<Call>(), calls)
        processJob.cancel()
    }

    @Test
    fun aHeldEndIsFinishedByItsRetryBeforeTheBarrierRuns() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        var live: AuthIdentityFence? = a
        val binder = build(calls, live = { live }, store = store)
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        val ticket = (binder.beginSignOut(a) as SignOutStart.Armed).ticket
        live = null
        store.failNextSignOut = true
        binder.onFenceObserved(null)
        advanceUntilIdle()

        assertEquals(RecoveryOutcome.CLOSED, binder.recoverSignOut(ticket))
        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        processJob.cancel()
    }

    @Test
    fun aRunWhoseAttemptEndsBehindABlockedItemStopsWithoutWaiting() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val b = fenceOf("user-b", 3L)
        var current: AuthIdentityFence? = a
        var onNextRead: (() -> Unit)? = null
        lateinit var binder: AuthAccessBinder
        binder = build(calls, live = { onNextRead?.let { hook -> onNextRead = null; hook() }; current }, store = store)
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        val ticket = (binder.beginSignOut(a) as SignOutStart.Armed).ticket
        current = null
        store.failNextSignOut = true
        binder.onFenceObserved(null)
        advanceUntilIdle()
        // The run spends its read-back on the held end. Its candidate capture then queues a sign-in
        // whose bind parks, between the end's retry — which finishes the attempt — and the barrier.
        val gate = CompletableDeferred<Unit>()
        onNextRead = {
            store.blockNextBindOn = gate
            binder.onFenceObserved(b)
        }

        val run = async { binder.recoverSignOut(ticket) }
        runCurrent()

        assertTrue("시도가 사라졌는데 막힌 항목 뒤의 회신을 기다렸다", run.isCompleted)
        assertEquals("사라진 시도를 미해소로 보고했다", RecoveryOutcome.CLOSED, run.await())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut, Call.OwnerChanged("user-b")), calls)
        processJob.cancel()
    }

    @Test
    fun aStoppedConsumerFailsAWaitingRecoveryRun() = runTest {
        val store = RecordingStore(mutableListOf())
        val a = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { a }, store = store)
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate

        val run = async { runCatching { binder.recoverSignOut(ticket) } }
        runCurrent()
        processJob.cancel()
        advanceUntilIdle()

        val failure = run.await().exceptionOrNull()
        assertTrue("멈춘 consumer 가 실행기에 알리지 않았다: $failure", failure.isStoppedConsumer())
    }

    @Test
    fun aCancelledRunDoesNotWithdrawItsBarrier() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val binder = build(calls, live = { a }, store = store)
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate

        val run = async { binder.recoverSignOut(ticket) }
        runCurrent()
        run.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals("취소한 실행의 barrier 가 끝나지 않았다", RecoveryOutcome.CLOSED, binder.recoverSignOut(ticket))
        processJob.cancel()
    }

    @Test
    fun aRecoveryRunFromTheConsumerIsRejected() = runTest {
        val purger = RecordingPurger()
        var failure: Throwable? = null
        val binder = build(mutableListOf(), purger = purger, seedJournal = true)
        purger.onPurge = { failure = runCatching { binder.recoverSignOut(SignOutTicket(1)) }.exceptionOrNull() }
        binder.start()
        advanceUntilIdle()

        assertTrue("consumer 안의 복구 실행이 거부되지 않았다: $failure", failure.isStoppedConsumer())
        processJob.cancel()
    }

    @Test
    fun anUnexpectedFailureInABarrierReachesTheRunAndStopsTheConsumer() = runTest {
        val a = fenceOf("user-a", 7L)
        val uncaught = mutableListOf<Throwable>()
        var armed = false
        var reads = 0
        val binder = build(
            mutableListOf(),
            live = { if (armed && ++reads == 2) throw IllegalArgumentException("live read") else a },
            uncaught = uncaught
        )
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        armed = true

        val failure = runCatching { binder.recoverSignOut(ticket) }.exceptionOrNull()
        advanceUntilIdle()

        assertTrue("barrier 실패가 실행기에 가지 않았다: $failure", failure is IllegalArgumentException)
        assertTrue("consumer 가 그 실패로 끝나지 않았다: $uncaught", uncaught.singleOrNull() is IllegalArgumentException)
    }

    // Automatic recovery

    @Test
    fun anAttemptThatCannotArmIsRecoveredAutomatically() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val a = fenceOf("user-a", 7L)
        val binder = build(calls, fetches = fetches, live = { a }, recoverAutomatically = true)
        binder.start()

        assertTrue(binder.beginSignOut(a) is SignOutStart.RecoveryRequired)
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals(listOf(true), fetches)
        assertEquals("끝난 시도의 상태가 남았다", null, built.recoveryStatus.value)
        processJob.cancel()
    }

    @Test
    fun aPreparationThatArmsDoesNotUseUpTheAutomaticRun() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(calls, live = { a }, store = store, onCoordinator = { coordinator = it }, recoverAutomatically = true)
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        // Park the preparation's first read, so its brief unresolved state is what the supervisor sees.
        val gate = CompletableDeferred<Unit>()
        store.blockNextLoadOn = gate
        val start = async { binder.beginSignOut(a) }
        runCurrent()
        gate.complete(Unit)
        val ticket = (start.await() as SignOutStart.Armed).ticket
        advanceUntilIdle()

        assertTrue(coordinator.stopDriver(ticket))
        advanceUntilIdle()

        // Settled, then bound again to the session still live: the stopped driver's attempt was recovered.
        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut, Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    @Test
    fun anEndQueuedBehindThePreparationIsAppliedBeforeAnythingIsSettled() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        var live: AuthIdentityFence? = a
        lateinit var binder: AuthAccessBinder
        binder = build(calls, live = { live }, store = store, recoverAutomatically = true)
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        // The session ends while the intent is written: the preparation sees nobody live and hands the
        // attempt to recovery, with that end still queued behind it.
        store.onBeginSignOut = {
            store.onBeginSignOut = {}
            live = null
            binder.onFenceObserved(null)
        }

        assertTrue(binder.beginSignOut(a) is SignOutStart.RecoveryRequired)
        advanceUntilIdle()

        assertEquals("대기하던 종료를 두 번 회전했다", listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        assertEquals(null, built.recoveryStatus.value)
        processJob.cancel()
    }

    @Test
    fun aDriverThatStopsAheadOfAQueuedEndStillRotatesOnce() = runTest {
        val calls = mutableListOf<Call>()
        val a = fenceOf("user-a", 7L)
        var live: AuthIdentityFence? = a
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(calls, live = { live }, onCoordinator = { coordinator = it }, recoverAutomatically = true)
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        val ticket = (binder.beginSignOut(a) as SignOutStart.Armed).ticket
        live = null
        // The end is queued, not applied, when the driver stops.
        binder.onFenceObserved(null)
        assertTrue(coordinator.stopDriver(ticket))
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        processJob.cancel()
    }

    @Test
    fun anUnfinishedAutomaticRunIsNotRepeatedByLaterSignals() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val binder = build(calls, live = { a }, store = store, recoverAutomatically = true)
        binder.start()
        store.failBinds = true
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()

        assertEquals(2, store.bindAttempts)
        assertEquals(
            SignOutRecoveryStatus(ticket, running = false, outcome = RecoveryOutcome.UNRESOLVED),
            built.recoveryStatus.value
        )
        processJob.cancel()
    }

    @Test
    fun aLateResultForAnEndedAttemptIsNotWritten() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val a = fenceOf("user-a", 7L)
        val b = fenceOf("user-b", 3L)
        var current: AuthIdentityFence? = a
        var onNextRead: (() -> Unit)? = null
        lateinit var binder: AuthAccessBinder
        binder = build(
            calls,
            live = { onNextRead?.let { hook -> onNextRead = null; hook() }; current },
            store = store,
            recoverAutomatically = true
        )
        binder.start()
        binder.onFenceObserved(a)
        advanceUntilIdle()
        binder.beginSignOut(a)
        current = null
        // The run's capture queues a sign-in that parks, so the run returns after its attempt ended.
        val gate = CompletableDeferred<Unit>()
        onNextRead = {
            store.blockNextBindOn = gate
            binder.onFenceObserved(b)
        }
        store.failNextSignOut = true
        binder.onFenceObserved(null)
        advanceUntilIdle()

        assertEquals("끝난 시도의 늦은 결과가 기록됐다", null, built.recoveryStatus.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, built.recoveryStatus.value)
        processJob.cancel()
    }

    @Test
    fun aRunInProgressIsShownAsRunning() = runTest {
        val store = RecordingStore(mutableListOf())
        val a = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { a }, store = store, recoverAutomatically = true)
        binder.start()
        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()

        assertEquals(SignOutRecoveryStatus(ticket, running = true), built.recoveryStatus.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, built.recoveryStatus.value)
        processJob.cancel()
    }

    @Test
    fun aCancellationThrownIntoAnActiveRunIsRecordedAsFailure() = runTest {
        val a = fenceOf("user-a", 7L)
        var reads = 0
        // The third live read is the run's candidate capture; the session changes under it.
        val binder = build(
            mutableListOf(),
            live = { if (++reads == 3) throw AuthIdentityChangedException() else a },
            recoverAutomatically = true
        )
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()

        assertEquals(SignOutRecoveryStatus(ticket, running = false, failed = true), built.recoveryStatus.value)
        processJob.cancel()
    }

    @Test
    fun aCancelledRunStopsShowingAsRunning() = runTest {
        val store = RecordingStore(mutableListOf())
        val a = fenceOf("user-a", 7L)
        val binder = build(mutableListOf(), live = { a }, store = store, recoverAutomatically = true)
        binder.start()
        store.blockNextBindOn = CompletableDeferred()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()
        assertEquals(SignOutRecoveryStatus(ticket, running = true), built.recoveryStatus.value)

        processJob.cancel()
        advanceUntilIdle()

        assertEquals(SignOutRecoveryStatus(ticket, running = false), built.recoveryStatus.value)
    }

    @Test
    fun aCancelledRunRecordsItStoppedEvenWhileTheLockIsBusy() = runTest {
        val store = RecordingStore(mutableListOf())
        val a = fenceOf("user-a", 7L)
        lateinit var coordinator: PremiumAccessCoordinator
        val binder = build(
            mutableListOf(), live = { a }, store = store, onCoordinator = { coordinator = it }, recoverAutomatically = true
        )
        binder.start()
        store.blockNextBindOn = CompletableDeferred()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()
        // Someone outside the cancelled scope takes the lock and holds it on a parked read, so the
        // cancelled run's last record has to wait for it rather than find it free.
        val readGate = CompletableDeferred<Unit>()
        store.blockNextLoadOn = readGate
        val busy = launch { coordinator.advanceRecovery(ticket) }
        runCurrent()
        processJob.cancel()
        advanceUntilIdle()
        readGate.complete(Unit)
        busy.join()
        advanceUntilIdle()

        assertEquals(SignOutRecoveryStatus(ticket, running = false), built.recoveryStatus.value)
    }

    @Test
    fun aRunThatThrowsIsRecordedAsFailed() = runTest {
        val a = fenceOf("user-a", 7L)
        var reads = 0
        // The preparation reads the live fence twice; the third read is the run's candidate capture.
        val binder = build(
            mutableListOf(),
            live = { if (++reads == 3) throw IllegalArgumentException("live read") else a },
            recoverAutomatically = true
        )
        binder.start()
        val ticket = (binder.beginSignOut(a) as SignOutStart.RecoveryRequired).ticket
        advanceUntilIdle()

        assertEquals(SignOutRecoveryStatus(ticket, running = false, failed = true), built.recoveryStatus.value)
        processJob.cancel()
    }

    /**
     * The consumer used to die here: with no sign-out attempt to own it, a failed bind threw out of
     * `onIdentityChanged` and the identity funnel went with it. The hold makes it a wait instead,
     * and the funnel keeps serving what comes next.
     */
    @Test
    fun aFailedBindWithNoAttempt_recoversWithoutStallingTheConsumer() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val binder = build(calls, store = store)
        binder.start()
        store.failNextBind = true

        binder.onFenceObserved(fenceOf("user-a"))
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals("user-a", store.record.ownerUid)

        // The funnel is still serving: a later observation is dispatched, not stuck behind the hold.
        binder.onFenceObserved(null)
        advanceUntilIdle()
        assertEquals(listOf(Call.OwnerChanged("user-a"), Call.SignedOut), calls)
        processJob.cancel()
    }

    /**
     * A bind that reached the record before failing is not run again: recovery reads the record
     * back, sees its own postcondition, and finishes the task. What this asserts is that recovery
     * uses the landing result — repeating the bind here would take the same-owner
     * `ensureNamespace` path, so it is not a rotation this prevents.
     */
    @Test
    fun aBindThatLandedBeforeFailing_isNotBoundAgain() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val binder = build(calls, store = store)
        binder.start()
        store.failNextBindAfterWrite = true

        binder.onFenceObserved(fenceOf("user-a"))
        advanceUntilIdle()

        assertEquals("착지한 묶기를 다시 실행했다", 1, store.bindAttempts)
        assertEquals("user-a", store.record.ownerUid)
        processJob.cancel()
    }

    /**
     * The startup purge used to run inside the same body whose `finally` stops the consumer, so a
     * journal a previous process left behind could end the identity funnel before it read anything.
     * It is a head task now: the failure holds, the hold's own round retries it, and the funnel
     * serves what comes next.
     */
    @Test
    fun aStartupPurgeThatThrows_doesNotEndTheConsumer() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        var failNext = true
        purger.onPurge = { if (failNext) { failNext = false; throw java.io.IOException("purge") } }
        val binder = build(calls, purger, seedJournal = true)

        binder.start()
        advanceUntilIdle()

        assertTrue("재개가 다시 시도되지 않았다", purger.attempts.size >= 2)
        binder.onFenceObserved(fenceOf("user-a"))
        advanceUntilIdle()
        assertEquals("소비자가 죽어 뒤 사건이 버려졌다", listOf(Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    /**
     * And nothing is accepted until it finishes. A purge that keeps failing runs out of automatic
     * rounds and the hold stands — the funnel stays at that head task rather than binding an owner
     * over a startup resume that never completed.
     */
    @Test
    fun aStartupPurgeStillHeld_acceptsNothingBehindIt() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        purger.onPurge = { throw java.io.IOException("purge") }
        val binder = build(calls, purger, seedJournal = true)

        binder.start()
        binder.onFenceObserved(fenceOf("user-a"))
        advanceUntilIdle()

        assertTrue("자동 회차가 돌지 않았다", purger.attempts.size >= 2)
        assertEquals("보류가 선 채로 뒤 사건이 적용됐다", emptyList<Call>(), calls)
        processJob.cancel()
    }

    // Unverified start (plan amendment 6), through the stream's start-up replay

    private val previousA = AccessEpochRecord(ownerUid = "user-a", userAccessEpoch = "u0", krxCapabilityEpoch = "k0")

    /** T2. A new process then binding the same uid over the kept record does not get the old namespace back. */
    @Test
    fun aFirstNullOverAPreviousOwnerRetiresIt_andALaterStartDoesNotHandItBack() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls).apply { record = previousA }
        build(calls, store = store, replaying = true, initial = null).start()
        advanceUntilIdle()

        assertEquals(listOf(Call.RetiredUnverifiedStart), calls)
        val retired = store.record
        assertEquals(null, retired.ownerUid)

        val a = fenceOf("user-a")
        val later = mutableListOf<Call>()
        val nextProcessStore = RecordingStore(later).apply { record = retired }
        build(later, store = nextProcessStore, live = { a }, replaying = true, initial = a).start()
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), later)
        assertEquals("회전 뒤 namespace 가 아니다", retired.userAccessEpoch, nextProcessStore.record.userAccessEpoch)
        assertTrue("이전 namespace 를 이어받았다", nextProcessStore.record.userAccessEpoch != previousA.userAccessEpoch)
        processJob.cancel()
    }

    /** T3. A restored same-uid start binds and keeps the namespace; the answer carries no marker to rotate on. */
    @Test
    fun aFirstObservationOfTheSameUidKeepsTheNamespace() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls).apply { record = previousA }
        val a = fenceOf("user-a")
        build(calls, store = store, live = { a }, replaying = true, initial = a).start()
        advanceUntilIdle()

        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals(previousA.userAccessEpoch, store.record.userAccessEpoch)
        assertEquals(previousA.pendingPurges, store.record.pendingPurges)
        processJob.cancel()
    }

    /** T4. After a sign-out landed, a first null finds no owner and no marker: no rotation, the journal resumes. */
    @Test
    fun aFirstNullAfterALandedSignOutRetiresNothingButTheJournalResumes() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        var n = 0
        val landed = AccessEpochTransitions.signOut(previousA, EpochIdGenerator { "landed-${n++}" })
        val store = RecordingStore(calls).apply { record = landed }
        build(calls, purger, store = store, replaying = true, initial = null).start()
        advanceUntilIdle()

        assertEquals(emptyList<Call>(), calls)
        assertEquals(landed, store.record)
        assertTrue("시작 purge 가 journal 을 재개하지 않았다", "user-a" in purger.attempts)
        processJob.cancel()
    }

    /** T6. Signed out and landed, the same uid bound again, then lost off disk before anything recorded it. */
    @Test
    fun theRebindThenExternalSignOutCounterexampleIsRetiredAtTheNextStart() = runTest {
        val calls = mutableListOf<Call>()
        var n = 0
        val gen = EpochIdGenerator { "hist-${n++}" }
        val history = AccessEpochTransitions.bindOwner(AccessEpochTransitions.signOut(previousA, gen), "user-a", gen)
        val store = RecordingStore(calls).apply { record = history }
        build(calls, store = store, replaying = true, initial = null).start()
        advanceUntilIdle()

        assertEquals(listOf(Call.RetiredUnverifiedStart), calls)
        assertEquals(history.pendingPurges.size + 1, store.record.pendingPurges.size)
        assertEquals(history.userAccessEpoch, store.record.pendingPurges.last().userAccessEpoch)
        processJob.cancel()
    }

    /** T9. While the settlement is held, the sign-in queued behind it binds nothing and asks nothing; then it does. */
    @Test
    fun aHeldSettlementKeepsTheSignInBehindItWaiting_thenTheSignInBinds() = runTest {
        val calls = mutableListOf<Call>()
        val fetches = mutableListOf<Boolean>()
        val store = RecordingStore(calls).apply { record = previousA; failNextRetire = true }
        val a = fenceOf("user-a")
        val binder = build(calls, fetches = fetches, store = store, live = { a }, replaying = true, initial = null)
        binder.start()
        binder.onFenceObserved(a)
        runCurrent()

        assertEquals("보류 중에 바인딩했다", emptyList<Call>(), calls)
        assertEquals("보류 중에 질의했다", emptyList<Boolean>(), fetches)

        advanceUntilIdle()
        assertEquals(listOf(Call.RetiredUnverifiedStart, Call.OwnerChanged("user-a")), calls)
        assertEquals(listOf(true), fetches)
        processJob.cancel()
    }

    /** T11. A restored same-uid start whose sign-out intent is still owed settles it before binding. */
    @Test
    fun aFirstObservationOfTheSameUidSettlesAnOwedIntentBeforeBinding() = runTest {
        val calls = mutableListOf<Call>()
        val owed = AccessEpochTransitions.beginSignOut(previousA, "user-a")
        val store = RecordingStore(calls).apply { record = owed }
        val a = fenceOf("user-a")
        build(calls, store = store, live = { a }, replaying = true, initial = a).start()
        advanceUntilIdle()

        assertEquals("시작 정산이 아니라 바인딩이 정산해야 한다", listOf(Call.OwnerChanged("user-a")), calls)
        assertEquals("user-a", store.record.ownerUid)
        assertTrue("owed 가 정산되지 않았다", store.record.userAccessEpoch != previousA.userAccessEpoch)
        assertEquals(previousA.userAccessEpoch, store.record.pendingPurges.last().userAccessEpoch)
        processJob.cancel()
    }

    /** Only the first observation says anything about a previous process; later nulls with nothing bound read nothing. */
    @Test
    fun onlyTheFirstObservationSettlesAStart() = runTest {
        val calls = mutableListOf<Call>()
        val store = RecordingStore(calls)
        val binder = build(calls, store = store)
        binder.start()
        advanceUntilIdle()
        val beforeObservations = store.loads

        repeat(3) { binder.onFenceObserved(null) }
        advanceUntilIdle()

        assertEquals("첫 null 만 기록을 읽어야 한다", beforeObservations + 1, store.loads)
        processJob.cancel()
    }

    /** Everything queued behind a held startup purge keeps its order: purge, settlement, sign-in, request. */
    @Test
    fun aHeldStartupPurgeKeepsTheSettlementTheSignInAndTheRequestInOrder() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        var failNext = true
        purger.onPurge = { if (failNext) { failNext = false; throw java.io.IOException("purge") } }
        val a = fenceOf("user-a")
        val binder = build(calls, purger, seedJournal = true, live = { a }, replaying = true, initial = null)
        binder.start()
        binder.onFenceObserved(a)
        val request = async { binder.beginSignOut(a) }
        runCurrent()
        assertEquals("시작 purge 보류 중에 뒤 사건이 적용됐다", emptyList<Call>(), calls)
        assertFalse("시작 purge 보류 중에 요청이 끝났다", request.isCompleted)

        advanceUntilIdle()
        assertEquals(listOf(Call.RetiredUnverifiedStart, Call.OwnerChanged("user-a")), calls)
        assertTrue("요청이 끝나지 않았다", request.isCompleted)
        assertTrue("로그인 바인딩 뒤 요청이 무장되지 않았다", request.await() is SignOutStart.Armed)
        processJob.cancel()
    }
}
/**
 * The consumer's own failure, not a cancellation. `CancellationException` extends
 * `IllegalStateException`, so a bare type check would accept the very thing it must reject.
 */
private fun Throwable?.isStoppedConsumer() =
    this is IllegalStateException && this !is kotlinx.coroutines.CancellationException

/** One observed identity. Generation defaults to 1 so existing cases read as they did. */
private fun fenceOf(uid: String, generation: Long = 1L) = AuthIdentityFence(uid, generation)
