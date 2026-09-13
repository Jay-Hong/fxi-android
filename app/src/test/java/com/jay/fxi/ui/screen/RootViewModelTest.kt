package com.jay.fxi.ui.screen

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AccessEpochRecord
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.LossObligation
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.boundGeneration
import com.jay.fxi.data.entitlements.heldByPersistence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.entitlements.EntitlementsSource
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    @Test
    fun sameUidWithANewLiveGeneration_rejectsTheCollectedGrantOnTheNextRead() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.confirmPremium()
            val collected = fixture.root.access.value
            assertEquals(PremiumAccessState.PremiumConfirmed, collected.state)
            assertEquals(
                RootDestination.Premium,
                rootDestinationFor(signedIn(), fixture.root.accessForSession(OWNER, collected))
            )

            // No auth/access Flow update: a recomposition must read the provider again even when
            // both collected values are unchanged. The grant belongs to 7; live auth is now 8.
            fixture.identity = AuthIdentity(OWNER, 8L)
            val access = fixture.root.accessForSession(OWNER, collected)
            assertEquals(PremiumAccessState.NoGrant, access)
            assertEquals(RootDestination.FreeSnapshot, rootDestinationFor(signedIn(), access))
            assertEquals("the test must retain the stale published grant", collected, fixture.root.access.value)
        } finally {
            fixture.processJob.cancel()
        }
    }

    @Test
    fun sameUidSignOutAndSignIn_cannotReuseThePreviousSessionsGrant() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.confirmPremium()
            val collected = fixture.root.access.value
            // Auth has moved, while the binder/coordinator have not consumed either transition.
            fixture.identity = null
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession(OWNER, collected))
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession(null, collected))
            fixture.identity = AuthIdentity(OWNER, 8L)
            assertEquals(
                RootDestination.FreeSnapshot,
                rootDestinationFor(signedIn(), fixture.root.accessForSession(OWNER, collected))
            )
        } finally {
            fixture.processJob.cancel()
        }
    }

    @Test
    fun matchingUidAndGeneration_preservesEveryAccessState() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.confirmPremium()
            val collected = fixture.root.access.value
            val states = listOf(
                PremiumAccessState.NoGrant,
                PremiumAccessState.PremiumConfirmed,
                PremiumAccessState.FreeConfirmed,
                PremiumAccessState.Pending(continuityEligible = false),
                PremiumAccessState.Pending(continuityEligible = true),
                PremiumAccessState.Rejected
            )
            states.forEach { state ->
                assertEquals(state, fixture.root.accessForSession(OWNER, collected.copy(state = state)))
            }
        } finally {
            fixture.processJob.cancel()
        }
    }

    @Test
    fun missingOwnershipOrAnyUidDisagreement_isNotOwned() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.confirmPremium()
            val collected = fixture.root.access.value
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession(null, collected))
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession("user-b", collected))
            assertEquals(
                PremiumAccessState.NoGrant,
                fixture.root.accessForSession(OWNER, collected.copy(uid = null))
            )
            assertEquals(
                PremiumAccessState.NoGrant,
                fixture.root.accessForSession(OWNER, collected.copy(authGeneration = null))
            )
            fixture.identity = AuthIdentity("user-b", 7L)
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession(OWNER, collected))
            assertEquals(PremiumAccessState.NoGrant, fixture.root.accessForSession("user-b", collected))
        } finally {
            fixture.processJob.cancel()
        }
    }

    /**
     * A grant Root collected before a loss candidate withdrew it (S1r-2c) is not honoured on the next read, even before the
     * new value reaches the collector.
     */
    @Test
    fun aCollectedGrant_isRefusedOnceALossCandidateHoldsTheUserAxis() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.confirmPremium()
            val collected = fixture.root.access.value
            assertEquals(PremiumAccessState.PremiumConfirmed, fixture.root.accessForSession(OWNER, collected))

            fixture.outcome = EntitlementsOutcome.StableInactive(krxVisible = false)
            fixture.afterFetch = { fixture.store.loadFailures = Int.MAX_VALUE }
            fixture.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

            val access = fixture.root.accessForSession(OWNER, collected)
            assertEquals(PremiumAccessState.NoGrant, access)
            assertEquals(RootDestination.FreeSnapshot, rootDestinationFor(signedIn(), access))
        } finally {
            fixture.processJob.cancel()
        }
    }

    /**
     * Root reads the coordinator's own flow rather than a copy, so there is no second value that can
     * fall behind the first.
     */
    @Test
    fun identityRecovery_isTheCoordinatorsFlow() = runTest {
        val fixture = Fixture(this)
        try {
            assertSame(fixture.coordinator.identityRecovery, fixture.root.identityRecovery)
        } finally {
            fixture.processJob.cancel()
        }
    }

    /**
     * A re-check is recorded against the hold that is standing, once, and nothing else is accepted.
     * The `true`/`false` here is the wake being recorded or refused — not the re-check's outcome.
     */
    @Test
    fun requestRecheck_recordsOneWakeForTheStandingHoldAndRefusesOthers() = runTest {
        val fixture = Fixture(this)
        try {
            assertFalse("보류가 없는데 깨우기가 기록됐다", fixture.root.requestRecheck(0L))

            fixture.store.failNextBind = true
            val held = fixture.coordinator
                .onIdentityChanged(AuthIdentityFence(OWNER, 7L))
                .heldByPersistence("bind 실패로 열린 보류")

            assertFalse("다른 id 가 서 있는 보류를 깨웠다", fixture.root.requestRecheck(held.id + 1))
            assertTrue("서 있는 보류의 id 로 깨우기가 기록되지 않았다", fixture.root.requestRecheck(held.id))
            assertFalse("이미 선 깨우기가 두 번 기록됐다", fixture.root.requestRecheck(held.id))
        } finally {
            fixture.processJob.cancel()
        }
    }

    private class Fixture(testScope: TestScope) {
        val processJob = SupervisorJob()
        private val scope = CoroutineScope(processJob + StandardTestDispatcher(testScope.testScheduler))
        var identity: AuthIdentity? = AuthIdentity(OWNER, 7L)
        var outcome: EntitlementsOutcome = EntitlementsOutcome.StableActive(krxVisible = false)

        /** Runs once after an answer is formed and before it returns. */
        var afterFetch: () -> Unit = {}
        private val tokens = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = identity
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String =
                error("Root must only read the non-suspend fence, never fetch a token")
        }, scope)
        private val source = object : EntitlementsSource {
            override suspend fun currentIdentity(): EntitlementsIdentity? =
                identity?.let { EntitlementsIdentity(it.uid, it.authGeneration) }

            override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
                val answer = EntitlementsResult.Answered(checkNotNull(currentIdentity()), outcome)
                afterFetch().also { afterFetch = {} }
                return answer
            }
        }
        val store = MemoryStore()
        val coordinator = PremiumAccessCoordinator(
            source = source,
            store = store,
            userPurger = UserScopePurger { PurgeResult.Completed },
            capabilityPurger = CapabilityScopePurger { PurgeResult.Completed },
            scope = scope,
            clock = { testScope.testScheduler.currentTime },
            liveFence = tokens::currentIdentityFence
        )
        val root = RootViewModel(coordinator, tokens)

        suspend fun confirmPremium() {
            // Derived from the fixture's own identity rather than restated: the binding publishes
            // the generation it was handed, so a test that sets 7 and binds 1 would be asserting
            // against a session that never existed.
            val observed = identity!!
            val binding =
                coordinator.onIdentityChanged(AuthIdentityFence(observed.uid, observed.authGeneration)).boundGeneration()
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = binding)
        }
    }

    private class MemoryStore : AccessEpochStore {
        /** The next `bindOwner` fails before touching the record, so the edit's outcome is known: nothing. */
        var failNextBind = false

        /** The next this many loads throw. */
        var loadFailures = 0
        private var record = AccessEpochRecord()
        private var nextId = 0
        private val ids = EpochIdGenerator { "epoch-${nextId++}" }
        override suspend fun load(): AccessEpochRecord {
            if (loadFailures > 0) {
                loadFailures -= 1
                throw IOException("injected load failure")
            }
            return record
        }
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            if (failNextBind) {
                failNextBind = false
                throw IOException("injected bind failure")
            }
            return AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        }
        override suspend fun signOut() =
            AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun retireUnverifiedStart() =
            AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }

        override suspend fun beginSignOut(uid: String) =
            AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
    }

    private fun signedIn() = AuthState.SignedIn(
        UserInfo(OWNER, email = null, displayName = null, photoUrl = null, provider = AuthProvider.GOOGLE)
    )

    private companion object {
        const val OWNER = "user-a"
    }
}
