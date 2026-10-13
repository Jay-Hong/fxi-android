package com.jay.fxi.s5fixture

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.AccessEpochRecord
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.entitlements.EntitlementsSource
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.LossObligation
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.entitlements.boundGeneration
import com.jay.fxi.ui.screen.RootViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S5-4 slice 4b: Root over the fixture identity, as RootViewModelTest drives it over a test source. The fixture user's grant
 * opens premium only for that user at that generation: signed out, another user and the same user at a later generation all
 * read no grant.
 */
class S5FixtureRootAccessTest {

    @Test
    fun `S5-4 4b the fixture identity opens premium only for its own user and generation`() = runTest {
        val identity = S5FixtureIdentity { it.run() }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val orders = AccessOrderSequence()
        val tokens = AuthTokenProvider(identity, scope, orders)
        val source = object : EntitlementsSource {
            override suspend fun currentIdentity(): EntitlementsIdentity? =
                identity.currentIdentity()?.let { EntitlementsIdentity(it.uid, it.authGeneration) }

            override suspend fun fetch(freshPremium: Boolean): EntitlementsResult =
                EntitlementsResult.Answered(checkNotNull(currentIdentity()), EntitlementsOutcome.StableActive(krxVisible = true))
        }
        val coordinator = PremiumAccessCoordinator(
            source = source,
            store = MemoryStore(),
            userPurger = UserScopePurger { PurgeResult.Completed },
            capabilityPurger = CapabilityScopePurger { PurgeResult.Completed },
            scope = scope,
            clock = { testScheduler.currentTime },
            liveFence = tokens::currentIdentityFence,
            orders = orders
        )
        val root = RootViewModel(coordinator, tokens)
        try {
            assertNull("signed out before the seed", tokens.currentIdentityFence())
            identity.signIn()
            val fence = checkNotNull(tokens.currentIdentityFence())
            assertEquals(1L, fence.authGeneration)
            val binding = coordinator.onIdentityChanged(fence).boundGeneration()
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = binding)
            val collected = root.access.value
            assertEquals(PremiumAccessState.PremiumConfirmed, collected.state)
            assertEquals(PremiumAccessState.PremiumConfirmed, root.accessForSession("s5-fixture", collected))
            assertEquals("another user", PremiumAccessState.NoGrant, root.accessForSession("someone", collected))
            assertEquals("signed out", PremiumAccessState.NoGrant, root.accessForSession(null, collected))
            identity.invalidateCurrentIdentity()
            assertEquals("the same user at a later generation", PremiumAccessState.NoGrant, root.accessForSession("s5-fixture", collected))
        } finally {
            job.cancel()
        }
    }

    /** RootViewModelTest's memory store, without its failure injection. */
    private class MemoryStore : AccessEpochStore {
        private var record = AccessEpochRecord()
        private var nextId = 0
        private val ids = EpochIdGenerator { "epoch-${nextId++}" }
        override suspend fun load(): AccessEpochRecord = record
        override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun retireUnverifiedStart() = AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
        override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
    }
}
