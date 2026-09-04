package com.jay.fxi.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthTokenProviderTest {

    @Test
    fun concurrentCurrentLookups_shareOneFetch() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 1))
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        source.fetch = { _, forceRefresh ->
            assertFalse(forceRefresh)
            fetchStarted.complete(Unit)
            releaseFetch.await()
            "token-a"
        }
        val provider = AuthTokenProvider(source, backgroundScope)

        val first = async { provider.currentSnapshot() }
        fetchStarted.await()
        val second = async { provider.currentSnapshot() }
        runCurrent()

        assertEquals(1, source.fetchCount)
        releaseFetch.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun identityChangeDuringFetch_discardsLateToken() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 4))
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        source.fetch = { _, _ ->
            fetchStarted.complete(Unit)
            releaseFetch.await()
            "late-a-token"
        }
        val processJob = SupervisorJob()
        val provider = AuthTokenProvider(
            source,
            CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
        )
        val lookup = async {
            runCatching { provider.currentSnapshot() }
        }

        fetchStarted.await()
        source.identity = AuthIdentity("user-b", 5)
        releaseFetch.complete(Unit)

        val failure = lookup.await().exceptionOrNull()
        assertTrue(failure is AuthIdentityChangedException)
        processJob.cancel()
    }

    @Test
    fun concurrentUnauthorizedSnapshots_shareOneForcedRefresh() = runTest {
        val identity = AuthIdentity("user-a", 2)
        val source = FakeAuthTokenSource(identity)
        source.fetch = { _, forceRefresh -> if (forceRefresh) "fresh-token" else "old-token" }
        val provider = AuthTokenProvider(source, backgroundScope)
        val rejected = provider.currentSnapshot()

        val first = async { provider.refreshAfterUnauthorized(rejected) }
        val second = async { provider.refreshAfterUnauthorized(rejected) }

        assertEquals("fresh-token", first.await()?.token)
        assertEquals(first.await(), second.await())
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun completedRefresh_isReusedByFingerprint_withoutAnotherForcedRefresh() = runTest {
        val identity = AuthIdentity("user-a", 2)
        val source = FakeAuthTokenSource(identity)
        var currentToken = "old-token"
        source.fetch = { _, forceRefresh ->
            if (forceRefresh) currentToken = "fresh-token"
            currentToken
        }
        val provider = AuthTokenProvider(source, backgroundScope)
        val rejected = provider.currentSnapshot()

        assertEquals("fresh-token", provider.refreshAfterUnauthorized(rejected)?.token)
        runCurrent()
        assertEquals("fresh-token", provider.refreshAfterUnauthorized(rejected)?.token)
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun forcedRefreshReturningSameToken_isNeverReplayedAgain() = runTest {
        val identity = AuthIdentity("user-a", 8)
        val source = FakeAuthTokenSource(identity)
        source.fetch = { _, _ -> "same-token" }
        val provider = AuthTokenProvider(source, backgroundScope)
        val rejected = provider.currentSnapshot()

        assertNull(provider.refreshAfterUnauthorized(rejected))
        assertNull(provider.refreshAfterUnauthorized(rejected))
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun failedForcedRefresh_isStillOwnedOnlyOnceByTheRejectedSnapshot() = runTest {
        val identity = AuthIdentity("user-a", 8)
        val source = FakeAuthTokenSource(identity)
        source.fetch = { _, forceRefresh ->
            if (forceRefresh) throw AuthUnavailableException("refresh unavailable")
            "rejected-token"
        }
        val processJob = SupervisorJob()
        val provider = AuthTokenProvider(
            source,
            CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
        )
        val rejected = provider.currentSnapshot()

        assertNull(provider.refreshAfterUnauthorized(rejected))
        assertNull(provider.refreshAfterUnauthorized(rejected))
        assertEquals(1, source.forceRefreshCount)
        processJob.cancel()
    }

    @Test
    fun cancelledForcedRefresh_isSharedButNotLatched_andLaterCohortMayRetry() = runTest {
        val identity = AuthIdentity("user-a", 8)
        val source = FakeAuthTokenSource(identity)
        val firstForcedStarted = CompletableDeferred<Unit>()
        val cancelFirstForced = CompletableDeferred<Unit>()
        var forcedAttempt = 0
        source.fetch = { _, forceRefresh ->
            if (!forceRefresh) {
                "rejected-token"
            } else {
                forcedAttempt += 1
                if (forcedAttempt == 1) {
                    firstForcedStarted.complete(Unit)
                    cancelFirstForced.await()
                    throw CancellationException("upstream Firebase Task cancelled")
                }
                "fresh-token"
            }
        }
        val provider = AuthTokenProvider(source, backgroundScope)
        val rejected = provider.currentSnapshot()

        val first = async { runCatching { provider.refreshAfterUnauthorized(rejected) } }
        firstForcedStarted.await()
        val peer = async { runCatching { provider.refreshAfterUnauthorized(rejected) } }
        runCurrent()

        assertEquals(1, source.forceRefreshCount)
        assertFalse(peer.isCompleted)
        cancelFirstForced.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(peer.await().exceptionOrNull() is CancellationException)

        runCurrent()
        val later = provider.refreshAfterUnauthorized(rejected)

        assertEquals("fresh-token", later?.token)
        assertEquals(2, source.forceRefreshCount)
    }

    @Test
    fun replayCredentialRejectedOnce_isNotForceRefreshedLater() = runTest {
        val identity = AuthIdentity("user-a", 3)
        val source = FakeAuthTokenSource(identity)
        source.fetch = { _, forceRefresh -> if (forceRefresh) "third-token" else "second-token" }
        val provider = AuthTokenProvider(source, backgroundScope)
        val replayed = provider.currentSnapshot()
        provider.recordRejected(replayed)

        assertNull(provider.refreshAfterUnauthorized(replayed))
        assertEquals(0, source.forceRefreshCount)
    }

    @Test
    fun rejectedCredentials_areNotForgottenAfterMoreThanSixteenTokens() = runTest {
        val identity = AuthIdentity("user-a", 3)
        val source = FakeAuthTokenSource(identity)
        val provider = AuthTokenProvider(source, backgroundScope)
        val oldest = AuthSnapshot(identity.uid, identity.authGeneration, "rejected-0")

        repeat(17) { index ->
            provider.recordRejected(
                AuthSnapshot(identity.uid, identity.authGeneration, "rejected-$index")
            )
        }

        assertNull(provider.refreshAfterUnauthorized(oldest))
        assertEquals(0, source.forceRefreshCount)
    }

    @Test
    fun rejectedCredential_isScopedToItsExactAuthGeneration() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 1))
        val provider = AuthTokenProvider(source, backgroundScope)
        provider.recordRejected(AuthSnapshot("user-a", 1, "same-token"))

        source.identity = AuthIdentity("user-a", 2)
        val nextGeneration = AuthSnapshot("user-a", 2, "same-token")

        assertFalse(provider.isKnownRejected(nextGeneration))
    }

    @Test
    fun explicitSessionInvalidation_makesOldSnapshotStaleBeforeFirebaseSignOut() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 4))
        val provider = AuthTokenProvider(source, backgroundScope)
        val oldSnapshot = provider.currentSnapshot()

        assertTrue(provider.invalidateCurrentSession(oldSnapshot.fence))
        assertFalse(provider.isCurrent(oldSnapshot))
        assertEquals(5L, provider.currentSnapshot().authGeneration)
    }

    @Test
    fun appOwnedSignIn_advancesAnExistingSameUidGenerationBeforeSdkMutation() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 4))
        val provider = AuthTokenProvider(source, backgroundScope)
        val oldSnapshot = provider.currentSnapshot()

        assertTrue(provider.advanceGenerationBeforeSignIn())

        assertFalse(provider.isCurrent(oldSnapshot))
        assertEquals(AuthIdentity("user-a", 5), source.identity)
    }

    @Test
    fun identityChangedImmediatelyAfterSharedFetchCompletion_isRejectedByAwaitingCaller() = runTest {
        val original = AuthIdentity("user-a", 1)
        val replacement = AuthIdentity("user-b", 2)
        var identity = original
        var identityReads = 0
        val source = object : AuthTokenSource {
            override fun currentIdentity(): AuthIdentity {
                identityReads += 1
                val observed = identity
                if (identityReads == 4) identity = replacement
                return observed
            }

            override suspend fun fetchToken(
                identity: AuthIdentity,
                forceRefresh: Boolean
            ): String = "credential"
        }
        val provider = AuthTokenProvider(source, backgroundScope)

        val failure = runCatching { provider.currentSnapshot() }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertEquals(replacement, identity)
    }

    @Test
    fun inFlightRejectedCredential_isScopedToItsExactAuthGeneration() = runTest {
        val source = FakeAuthTokenSource(AuthIdentity("user-a", 1))
        val oldRefreshStarted = CompletableDeferred<Unit>()
        val releaseOldRefresh = CompletableDeferred<Unit>()
        source.fetch = { identity, forceRefresh ->
            when {
                identity.authGeneration == 1L && forceRefresh -> {
                    oldRefreshStarted.complete(Unit)
                    releaseOldRefresh.await()
                    "old-generation-refresh"
                }
                identity.authGeneration == 1L -> "shared-token"
                forceRefresh -> "shared-token"
                else -> "new-rejected"
            }
        }
        val provider = AuthTokenProvider(source, backgroundScope)
        val generationOne = provider.currentSnapshot()
        val oldRefresh = async {
            runCatching { provider.refreshAfterUnauthorized(generationOne) }
        }
        oldRefreshStarted.await()

        source.identity = AuthIdentity("user-a", 2)
        val generationTwo = provider.currentSnapshot()

        assertEquals("shared-token", provider.refreshAfterUnauthorized(generationTwo)?.token)
        releaseOldRefresh.complete(Unit)
        assertTrue(oldRefresh.await().exceptionOrNull() is AuthIdentityChangedException)
    }

    @Test
    fun snapshotString_neverContainsUidOrBearer() {
        val snapshot = AuthSnapshot("secret-uid", 9, "secret-bearer")
        val rendered = snapshot.toString()

        assertFalse(rendered.contains("secret-uid"))
        assertFalse(rendered.contains("secret-bearer"))
        assertTrue(rendered.contains("authGeneration=9"))
    }

    private class FakeAuthTokenSource(
        var identity: AuthIdentity?
    ) : AuthTokenSource {
        var fetchCount = 0
        var forceRefreshCount = 0
        var fetch: suspend (AuthIdentity, Boolean) -> String = { _, _ -> "token" }

        override fun currentIdentity(): AuthIdentity? = identity

        override fun invalidateCurrentSession(expected: AuthIdentity): Boolean {
            if (identity != expected) return false
            identity = expected.copy(authGeneration = expected.authGeneration + 1L)
            return true
        }

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetchCount += 1
            if (forceRefresh) forceRefreshCount += 1
            return fetch(identity, forceRefresh)
        }
    }
}
