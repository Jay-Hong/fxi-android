package com.jay.fxi.data.entitlements

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
        seedJournal: Boolean = false
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
                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult =
                    EntitlementsResult.Unauthenticated(
                        EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
                    )

                override suspend fun currentIdentity(): EntitlementsIdentity? = null
            },
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = scope,
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None
        )
        // No stream: every test drives onUidObserved itself, so registration order cannot make a
        // test pass for the wrong reason.
        return AuthAccessBinder(coordinator, scope, AuthUidStream { })
    }

    @Test
    fun repeatedSameUid_bindsTheOwnerExactlyOnce() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        repeat(3) { binder.onUidObserved("user-a") }
        advanceUntilIdle()

        // Firebase re-delivers the current user on every listener registration. Without the dedup
        // each replay re-enters onOwnerChanged, which resets a live grant to NoGrant.
        assertEquals(listOf(Call.OwnerChanged("user-a")), calls)
        processJob.cancel()
    }

    @Test
    fun signOutThenSignIn_isDispatchedInEmittedOrder() = runTest {
        val calls = mutableListOf<Call>()
        val binder = build(calls)
        binder.start()

        binder.onUidObserved("user-a")
        binder.onUidObserved(null)
        binder.onUidObserved("user-b")
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

        binder.onUidObserved(null)
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

        binder.onUidObserved(null)
        binder.onUidObserved("user-a")
        binder.onUidObserved(null)
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

        binder.onUidObserved("user-a")
        repeat(3) { binder.onUidObserved(null) }
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
}
