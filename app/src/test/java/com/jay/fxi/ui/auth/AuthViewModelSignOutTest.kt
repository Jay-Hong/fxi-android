package com.jay.fxi.ui.auth

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthTransitionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelSignOutTest {

    @Test
    fun signOut_ordersUnregisterThenGenerationCasThenFirebaseSignOut() = runTest {
        val owner = AuthIdentityFence("user-a", 7)
        val events = mutableListOf<String>()

        ownerBoundSignOut(
            owner = owner,
            unregister = { captured ->
                assertEquals(owner, captured)
                events += "unregister"
            },
            invalidate = { captured ->
                assertEquals(owner, captured)
                events += "invalidate"
                true
            },
            signOut = { events += "signOut" }
        )

        assertEquals(listOf("unregister", "invalidate", "signOut"), events)
    }

    @Test
    fun staleIntent_neverSignsOutTheReplacementSession() = runTest {
        val owner = AuthIdentityFence("user-a", 7)
        var firebaseSignOutCalled = false

        ownerBoundSignOut(
            owner = owner,
            unregister = {},
            invalidate = { false },
            signOut = { firebaseSignOutCalled = true }
        )

        assertFalse(firebaseSignOutCalled)
    }

    @Test
    fun appOwnedLoginCannotEnterBetweenGenerationCasAndFirebaseSignOut() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val owner = AuthIdentityFence("user-a", 7)
        val casCompleted = CompletableDeferred<Unit>()
        val allowSignOut = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val signOut = async {
            handedOffSignOut(
                coordinator = coordinator,
                owner = owner,
                unregister = {},
                invalidate = {
                    events += "invalidate"
                    casCompleted.complete(Unit)
                    true
                },
                signOut = {
                    allowSignOut.await()
                    events += "signOut"
                }
            )
        }
        casCompleted.await()
        val login = async {
            coordinator.withTransition {
                events += "login"
            }
        }
        runCurrent()

        assertEquals(listOf("invalidate"), events)
        assertFalse(login.isCompleted)

        allowSignOut.complete(Unit)
        signOut.await()
        login.await()
        assertEquals(listOf("invalidate", "signOut", "login"), events)
    }

    @Test
    fun cancelledScreen_doesNotReleaseTrackedSdkTransitionBeforeItTerminates() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val sdkStarted = CompletableDeferred<Unit>()
        val sdkTerminal = CompletableDeferred<Unit>()
        var competingTransitionEntered = false

        val screen = launch {
            coordinator.withTrackedTransition {
                sdkStarted.complete(Unit)
                sdkTerminal.await()
            }
        }
        sdkStarted.await()
        screen.cancelAndJoin()

        val competing = async {
            coordinator.withTransition {
                competingTransitionEntered = true
            }
        }
        runCurrent()
        assertFalse(competingTransitionEntered)

        sdkTerminal.complete(Unit)
        competing.await()
        assertTrue(competingTransitionEntered)
    }

    @Test
    fun trackedSignIn_advancesGenerationBeforeStartingTheSdkMutation() = runTest {
        val events = mutableListOf<String>()
        val coordinator = AuthTransitionCoordinator(
            processScope = backgroundScope,
            beforeTrackedTransition = { events += "advance-generation" }
        )

        coordinator.withTrackedTransition {
            events += "start-sdk"
        }

        assertEquals(listOf("advance-generation", "start-sdk"), events)
    }

    @Test
    fun injectedCoordinator_invalidatesExistingSameUidSnapshotBeforeTrackedSignIn() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val source = object : AuthTokenSource {
                var identity = AuthIdentity("user-a", 4)

                override fun currentIdentity(): AuthIdentity = identity

                override fun invalidateCurrentSession(expected: AuthIdentity): Boolean {
                    if (identity != expected) return false
                    identity = expected.copy(authGeneration = expected.authGeneration + 1L)
                    return true
                }

                override suspend fun fetchToken(
                    identity: AuthIdentity,
                    forceRefresh: Boolean
                ): String = "credential"
            }
            val provider = AuthTokenProvider(source, backgroundScope)
            val staleSnapshot = provider.currentSnapshot()
            val coordinator = AuthTransitionCoordinator(provider)

            coordinator.withTrackedTransition {
                assertFalse(provider.isCurrent(staleSnapshot))
                assertEquals(AuthIdentity("user-a", 5), source.identity)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledScreen_doesNotStartTrackedSdkTransitionThatWasStillQueued() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val releasePrecedingTransition = CompletableDeferred<Unit>()
        val preceding = launch {
            coordinator.withTransition {
                releasePrecedingTransition.await()
            }
        }
        runCurrent()

        var sdkStarted = false
        val queuedScreen = launch {
            coordinator.withTrackedTransition {
                sdkStarted = true
            }
        }
        runCurrent()
        assertFalse(sdkStarted)

        queuedScreen.cancelAndJoin()
        releasePrecedingTransition.complete(Unit)
        preceding.join()
        runCurrent()

        assertFalse(sdkStarted)
        var subsequentTransitionEntered = false
        coordinator.withTransition {
            subsequentTransitionEntered = true
        }
        assertTrue(subsequentTransitionEntered)
    }

    @Test
    fun handedOffTransition_doesNotRunTheSignInHook() = runTest {
        val events = mutableListOf<String>()
        val coordinator = AuthTransitionCoordinator(
            processScope = backgroundScope,
            beforeTrackedTransition = { events += "advance-generation" }
        )

        coordinator.withHandedOffTransition {
            events += "sign-out"
        }

        assertEquals(listOf("sign-out"), events)
    }

    /** Caller cancellation leaves the handed-off block holding the lease until this test releases it. */
    @Test
    fun cancelledScreen_doesNotAbandonAStartedHandedOffTransition() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        var finished = false
        var competingTransitionEntered = false

        val screen = launch {
            coordinator.withHandedOffTransition {
                started.complete(Unit)
                terminal.await()
                finished = true
            }
        }
        started.await()
        screen.cancelAndJoin()

        val competing = async {
            coordinator.withTransition {
                competingTransitionEntered = true
            }
        }
        runCurrent()
        assertFalse(competingTransitionEntered)

        terminal.complete(Unit)
        competing.await()
        assertTrue("취소된 화면이 시작된 로그아웃을 중간에 버렸다", finished)
        assertTrue(competingTransitionEntered)
    }

    @Test
    fun cancelledScreen_doesNotStartAHandedOffTransitionThatWasStillQueued() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val releasePrecedingTransition = CompletableDeferred<Unit>()
        val preceding = launch {
            coordinator.withTransition {
                releasePrecedingTransition.await()
            }
        }
        runCurrent()

        var started = false
        val queuedScreen = launch {
            coordinator.withHandedOffTransition {
                started = true
            }
        }
        runCurrent()
        queuedScreen.cancelAndJoin()
        releasePrecedingTransition.complete(Unit)
        preceding.join()
        runCurrent()

        assertFalse(started)
    }

    /** After caller cancellation, successful unregister and CAS still lead to the sign-out callback. */
    @Test
    fun signOut_isNotAbandonedWhenTheScreenGoesAwayDuringUnregister() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val owner = AuthIdentityFence("user-a", 7)
        val unregisterStarted = CompletableDeferred<Unit>()
        val unregisterDone = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val screen = launch {
            handedOffSignOut(
                coordinator = coordinator,
                owner = owner,
                unregister = {
                    unregisterStarted.complete(Unit)
                    unregisterDone.await()
                    events += "unregister"
                },
                invalidate = {
                    events += "invalidate"
                    true
                },
                signOut = { events += "signOut" }
            )
        }
        unregisterStarted.await()
        screen.cancelAndJoin()
        unregisterDone.complete(Unit)
        coordinator.withTransition {} // the lease comes back only once the sign-out has finished

        assertEquals(listOf("unregister", "invalidate", "signOut"), events)
    }

    @Test
    fun cancelledUnregister_doesNotInvalidateOrSignOut() {
        val owner = AuthIdentityFence("user-a", 7)
        var invalidated = false
        var firebaseSignOutCalled = false

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest {
                ownerBoundSignOut(
                    owner = owner,
                    unregister = { throw CancellationException("owner changed") },
                    invalidate = {
                        invalidated = true
                        true
                    },
                    signOut = { firebaseSignOutCalled = true }
                )
            }
        }

        assertFalse(invalidated)
        assertFalse(firebaseSignOutCalled)
    }
}
