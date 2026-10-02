package com.jay.fxi.ui.screen

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
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
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.remote.C4OwnerHarness
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.TETHER
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.USD
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.ack
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.decoder
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.tetherFrame
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C4 contract ROOT-REFUSAL (R4c/C4/design_codex.r3.md): a server refusal on the process owner's runtime
 * reaches the real access coordinator, Root's real decision and the owner's consumer, end to end. The coordinator, its grant
 * issuer and use authority are production; only its store, purgers, entitlement answer and clock are in memory. The refusal
 * arrives as an ack on the socket, as the server sends it. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class C4RootRefusalContractTest {

    private companion object {
        val U1 = AuthIdentityFence("u1", 1L)
        const val EUR = "fx:eur-krw"
        val USER = AuthState.SignedIn(UserInfo("u1", null, null, null, AuthProvider.GOOGLE))
    }

    /** The production coordinator over an in-memory epoch store, answering premium for `u1`/1. */
    private class Access(test: TestScope, scope: CoroutineScope) {
        private var n = 0
        private val ids = EpochIdGenerator { "access-${n++}" }
        private var record = AccessEpochRecord()
        private val store = object : AccessEpochStore {
            override suspend fun load(): AccessEpochRecord = record
            override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
            override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
            override suspend fun retireUnverifiedStart() =
                AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
            override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
            override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
                AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
            override suspend fun completePurges(completed: Collection<PendingPurge>) =
                AccessEpochTransitions.completePurges(record, completed).also { record = it }
            override suspend fun journalRetired(obligation: LossObligation) =
                AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
            override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
                AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
        }
        private val purger = object : UserScopePurger, CapabilityScopePurger {
            override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
            override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
        }
        val premium = PremiumAccessCoordinator(
            source = object : EntitlementsSource {
                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult =
                    EntitlementsResult.Answered(EntitlementsIdentity("u1", 1L), EntitlementsOutcome.StableActive(krxVisible = false))
                override suspend fun currentIdentity() = EntitlementsIdentity("u1", 1L)
            },
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = scope,
            clock = { test.testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = { U1 },
            orders = AccessOrderSequence()
        )
    }

    private class Firebase : AuthTokenSource {
        override fun currentIdentity(): AuthIdentity = AuthIdentity("u1", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "token"
    }

    @Test
    fun `C4-J-ROOT-REFUSAL a premium refusal on the owner's runtime sends Root free and empties the consumer, and nothing old is taken after`() = runTest {
        val access = Access(this, CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScheduler)))
        access.premium.onIdentityChanged(U1)
        access.premium.refresh(RefreshIntent.FORCE_PREMIUM)
        check(access.premium.state.value.state == PremiumAccessState.PremiumConfirmed) { "fixture: a fresh premium grant" }
        val root = RootViewModel(access.premium, AuthTokenProvider(Firebase(), orders = AccessOrderSequence()))
        fun destination() = rootDestinationFor(USER, root.accessForSession("u1", root.access.value))

        val h = C4OwnerHarness(
            this,
            online = true,
            issuer = PremiumAccessTopicGrantIssuer(access.premium),
            authority = SnapshotTopicUseAuthority { access.premium.accessSnapshot }
        )
        h.tabs.stored["u1"] = FreeTab.TETHER
        val lateRest = CompletableDeferred<Unit>().also { h.bootstrapGates[EUR] = it }
        h.owner.start()
        h.foreground(true) // the process's first foreground (C4 r4)
        h.settle(100)
        h.wire.open()
        h.settle(3_000)
        h.wire.deliver(tetherFrame(1390.0))
        h.settle(100)
        val old = checkNotNull(h.screen.ui.owner) { "fixture: the premium screen is shown" }
        check(h.screen.ui.rateSections.any { it.rows.isNotEmpty() }) { "fixture: a live price is shown" }
        assertEquals("fixture: Root opens premium", RootDestination.Premium, destination())

        h.wire.deliver(ack(h.subscribes.last().requestId, active = listOf(TETHER), rejections = mapOf(USD to "premium_required")))
        h.settle(100)
        assertEquals("the coordinator did not take the refusal", PremiumAccessState.Rejected, access.premium.state.value.state)
        assertEquals("Root did not go free", RootDestination.FreeSnapshot, destination())
        assertEquals("the consumer still draws", PremiumTopicScreenState.NONE, h.screen)

        // Everything that arrives after the refusal, under the old grant.
        h.wire.deliver(tetherFrame(1400.0))
        h.bootstrapOutcome = { TopicSnapshotOutcome.Delivered(decoder.decode(tetherFrame(1410.0))) }
        lateRest.complete(Unit)
        h.owner.consumer.onUserTabSelected(old, FreeTab.JPY)
        h.settle(3_000)
        assertEquals("a late input was drawn", PremiumTopicScreenState.NONE, h.screen)
        assertEquals("a late input reopened access", PremiumAccessState.Rejected, access.premium.state.value.state)
        assertEquals("a late input reopened Root", RootDestination.FreeSnapshot, destination())
        assertFalse("an old command selected a tab", h.tabs.remembered.any { it.second == FreeTab.JPY })

        // Root's other mappings stay as they were.
        assertEquals(RootDestination.FreeSnapshot, rootDestinationFor(USER, PremiumAccessState.FreeConfirmed))
        assertEquals(RootDestination.Login, rootDestinationFor(AuthState.SignedOut, PremiumAccessState.PremiumConfirmed))
        assertTrue("fixture: the late bootstrap was asked", EUR in h.asked())
    }
}
