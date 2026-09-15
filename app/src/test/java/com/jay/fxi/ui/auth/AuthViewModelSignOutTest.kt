package com.jay.fxi.ui.auth

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthTransitionCoordinator
import com.jay.fxi.data.entitlements.SignOutStart
import com.jay.fxi.data.entitlements.SignOutTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
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

    private val ticket = SignOutTicket(1)
    private val armedStart: suspend (AuthIdentityFence) -> SignOutStart = { SignOutStart.Armed(ticket) }

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
                begin = armedStart,
                stopDriver = {},
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
            val provider = AuthTokenProvider(source, backgroundScope, AccessOrderSequence())
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
                begin = armedStart,
                stopDriver = {},
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

    // The sign-out attempt

    @Test
    fun anArmedDriverRunsTheSideEffectsAndLeavesTheAttemptToItsEnd() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val owner = AuthIdentityFence("user-a", 7)
        val events = mutableListOf<String>()

        handedOffSignOut(
            coordinator = coordinator,
            owner = owner,
            begin = { captured -> events += "begin"; assertEquals(owner, captured); SignOutStart.Armed(ticket) },
            stopDriver = { events += "stop" },
            unregister = { events += "unregister" },
            invalidate = { events += "invalidate"; true },
            signOut = { events += "signOut" }
        )

        assertEquals(listOf("begin", "unregister", "invalidate", "signOut"), events)
    }

    @Test
    fun onlyAnArmedStartRunsTheSideEffects() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val owner = AuthIdentityFence("user-a", 7)
        listOf(
            SignOutStart.Stale,
            SignOutStart.Joined(ticket),
            SignOutStart.Busy(ticket),
            SignOutStart.RecoveryRequired(ticket)
        ).forEach { start ->
            val events = mutableListOf<String>()
            handedOffSignOut(
                coordinator = coordinator,
                owner = owner,
                begin = { start },
                stopDriver = { events += "stop" },
                unregister = { events += "unregister" },
                invalidate = { events += "invalidate"; true },
                signOut = { events += "signOut" }
            )
            assertEquals("$start 가 부수효과를 실행했다", emptyList<String>(), events)
        }
    }

    @Test
    fun aSessionAlreadyMovedAtTheCasStopsTheDriver() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val stopped = mutableListOf<SignOutTicket>()
        var firebaseSignOutCalled = false

        handedOffSignOut(
            coordinator = coordinator,
            owner = AuthIdentityFence("user-a", 7),
            begin = armedStart,
            stopDriver = { stopped += it },
            unregister = {},
            invalidate = { false },
            signOut = { firebaseSignOutCalled = true }
        )

        assertEquals(listOf(ticket), stopped)
        assertFalse(firebaseSignOutCalled)
    }

    @Test
    fun anIdentityChangeUnderTheUnregisterStopsTheDriverWithoutSigningOut() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val stopped = mutableListOf<SignOutTicket>()
        val events = mutableListOf<String>()

        handedOffSignOut(
            coordinator = coordinator,
            owner = AuthIdentityFence("user-a", 7),
            begin = armedStart,
            stopDriver = { stopped += it },
            unregister = { throw AuthIdentityChangedException() },
            invalidate = { events += "invalidate"; true },
            signOut = { events += "signOut" }
        )

        assertEquals(listOf(ticket), stopped)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun anUnregisterThatRunsOutOfTimeStillSignsOut() = runTest {
        val coordinator = AuthTransitionCoordinator(backgroundScope)
        val stopped = mutableListOf<SignOutTicket>()
        val events = mutableListOf<String>()

        handedOffSignOut(
            coordinator = coordinator,
            owner = AuthIdentityFence("user-a", 7),
            begin = armedStart,
            stopDriver = { stopped += it },
            unregister = { awaitCancellation() },
            invalidate = { events += "invalidate"; true },
            signOut = { events += "signOut" }
        )

        assertEquals(listOf("invalidate", "signOut"), events)
        assertEquals(UNREGISTER_DEADLINE_MILLIS, testScheduler.currentTime)
        assertEquals(emptyList<SignOutTicket>(), stopped)
    }

    @Test
    fun aFirebaseSignOutThatThrowsStopsTheDriver() = runTest {
        // A process scope of its own: in backgroundScope the failed hand-off would fail the test run itself.
        val coordinator = AuthTransitionCoordinator(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val stopped = mutableListOf<SignOutTicket>()

        val failure = runCatching {
            handedOffSignOut(
                coordinator = coordinator,
                owner = AuthIdentityFence("user-a", 7),
                begin = armedStart,
                stopDriver = { stopped += it },
                unregister = {},
                invalidate = { true },
                signOut = { throw IllegalStateException("sdk") }
            )
        }.exceptionOrNull()

        assertTrue("Firebase 실패가 사라졌다: $failure", failure is IllegalStateException && failure !is CancellationException)
        assertEquals(listOf(ticket), stopped)
    }

    @Test
    fun aCancellationWhileThePreparationIsAnsweredStillStopsTheArmedDriver() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AuthTransitionCoordinator(process)
        val answer = CompletableDeferred<Unit>()
        val begun = CompletableDeferred<Unit>()
        val stopped = mutableListOf<SignOutTicket>()
        val events = mutableListOf<String>()

        val screen = launch {
            runCatching {
                handedOffSignOut(
                    coordinator = coordinator,
                    owner = AuthIdentityFence("user-a", 7),
                    // The binder answers a queued preparation even after its caller is gone.
                    begin = { begun.complete(Unit); answer.await(); SignOutStart.Armed(ticket) },
                    stopDriver = { yield(); stopped += it },
                    unregister = { events += "unregister" },
                    invalidate = { events += "invalidate"; true },
                    signOut = { events += "signOut" }
                )
            }
        }
        begun.await()
        process.cancel()
        runCurrent()
        answer.complete(Unit)
        advanceUntilIdle()
        screen.join()

        assertEquals("응답을 기다리다 취소된 실행자가 무장 시도를 남겼다", listOf(ticket), stopped)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aCancellationRightAfterTheAnswerStillStopsTheArmedDriver() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AuthTransitionCoordinator(process)
        val answer = CompletableDeferred<Unit>()
        val begun = CompletableDeferred<Unit>()
        val stopped = mutableListOf<SignOutTicket>()
        val events = mutableListOf<String>()

        val screen = launch {
            runCatching {
                handedOffSignOut(
                    coordinator = coordinator,
                    owner = AuthIdentityFence("user-a", 7),
                    begin = { begun.complete(Unit); answer.await(); SignOutStart.Armed(ticket) },
                    stopDriver = { yield(); stopped += it },
                    unregister = { events += "unregister" },
                    invalidate = { events += "invalidate"; true },
                    signOut = { events += "signOut" }
                )
            }
        }
        begun.await()
        // The answer is in, but the driver has not resumed yet when its work is cancelled.
        answer.complete(Unit)
        process.cancel()
        advanceUntilIdle()
        screen.join()

        assertEquals(listOf(ticket), stopped)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aHandOffCancelledBeforeItStartsOpensNoAttempt() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AuthTransitionCoordinator(process)
        var begins = 0
        val stopped = mutableListOf<SignOutTicket>()
        process.cancel()

        val screen = launch {
            runCatching {
                handedOffSignOut(
                    coordinator = coordinator,
                    owner = AuthIdentityFence("user-a", 7),
                    begin = { begins++; SignOutStart.Armed(ticket) },
                    stopDriver = { stopped += it },
                    unregister = {},
                    invalidate = { true },
                    signOut = {}
                )
            }
        }
        advanceUntilIdle()
        screen.join()

        assertEquals("취소된 인계가 시도를 열었다", 0, begins)
        assertEquals(emptyList<SignOutTicket>(), stopped)
    }

    @Test
    fun aCancellationWhileStoppingAfterTheCasStillStops() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AuthTransitionCoordinator(process)
        val stopping = CompletableDeferred<Unit>()
        val lockFree = CompletableDeferred<Unit>()
        val stopped = mutableListOf<SignOutTicket>()

        val screen = launch {
            runCatching {
                handedOffSignOut(
                    coordinator = coordinator,
                    owner = AuthIdentityFence("user-a", 7),
                    begin = armedStart,
                    // Waits like the real one for the coordinator's lock.
                    stopDriver = { stopping.complete(Unit); lockFree.await(); stopped += it },
                    unregister = {},
                    invalidate = { false },
                    signOut = {}
                )
            }
        }
        stopping.await()
        process.cancel()
        runCurrent()
        lockFree.complete(Unit)
        advanceUntilIdle()
        screen.join()

        assertEquals("CAS 뒤 멈춤이 취소에 끊겼다", listOf(ticket), stopped)
    }

    @Test
    fun aCancelledSignOutStillStopsItsDriver() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = AuthTransitionCoordinator(process)
        val unregisterStarted = CompletableDeferred<Unit>()
        val stopped = mutableListOf<SignOutTicket>()

        val screen = launch {
            runCatching {
                handedOffSignOut(
                    coordinator = coordinator,
                    owner = AuthIdentityFence("user-a", 7),
                    begin = armedStart,
                    // Suspends like the real one, which takes the coordinator's lock.
                    stopDriver = { yield(); stopped += it },
                    unregister = { unregisterStarted.complete(Unit); awaitCancellation() },
                    invalidate = { true },
                    signOut = {}
                )
            }
        }
        unregisterStarted.await()
        process.cancel()
        advanceUntilIdle()
        screen.join()

        assertEquals("취소된 실행자가 멈췄다고 알리지 않았다", listOf(ticket), stopped)
    }
}
