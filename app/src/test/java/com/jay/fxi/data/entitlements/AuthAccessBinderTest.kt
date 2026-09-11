package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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

    /** What the coordinator was told, in order. */
    private sealed interface Call {
        data class OwnerChanged(val uid: String) : Call
        data object SignedOut : Call
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

        override suspend fun load() = record
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            calls += Call.OwnerChanged(uid)
            return AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        }

        override suspend fun signOut(): AccessEpochRecord {
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
            AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
    }

    /** Deferred, like production: the journal survives so a resume stays observable. */
    private class RecordingPurger : UserScopePurger, CapabilityScopePurger {
        val attempts = mutableListOf<String?>()
        override suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult {
            attempts += namespace.pending.ownerUid
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
        live: () -> AuthIdentityFence? = { null }
    ): AuthAccessBinder {
        val store = RecordingStore(calls)
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
        val scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
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
        // No stream: every test drives onFenceObserved itself, so registration order cannot make
        // a test pass for the wrong reason.
        return AuthAccessBinder(coordinator, scope, AuthFenceStream { })
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

        // A synthesised sign-out would rotate both epochs and journal a purge whenever a previous
        // process left an owner bound, destroying same-uid cold-start continuity.
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
     * The explicit resume at [AuthAccessBinder.start] is the only path that retries a previous
     * process's journal when nobody signs in — no owner change ever fires in that session.
     */
    @Test
    fun signedOutColdStart_stillRetriesAJournalledPurge() = runTest {
        val calls = mutableListOf<Call>()
        val purger = RecordingPurger()
        val binder = build(calls, purger, seedJournal = true)

        binder.start()
        advanceUntilIdle()

        assertEquals(listOf("previous-owner"), purger.attempts)
        assertEquals("nobody signed in, so nothing may bind or tear down", emptyList<Call>(), calls)
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
}
/** One observed identity. Generation defaults to 1 so existing cases read as they did. */
private fun fenceOf(uid: String, generation: Long = 1L) = AuthIdentityFence(uid, generation)
