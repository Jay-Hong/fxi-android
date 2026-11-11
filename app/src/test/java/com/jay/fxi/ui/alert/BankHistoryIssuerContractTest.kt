package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
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
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.IssuerBankHistoryAccess
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.FreeTab
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (c) contract S7C_A01 (`R4c/S7/s7c_api_proposal.claude.r4.md` §4, Codex r2 C10): the real premium access
 * coordinator and its real use authority and revisions drive the bank history controller. A same-owner USER hold — a loss
 * held as a candidate — withdraws the shown list at its revision and ends the old use for good; its release reads nothing on its
 * own; only the user's retry reads again, under a new use. The coordinator's fakes follow PremiumAccessTopicSnapshotTest's
 * (store transitions, purger, entitlements source). The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankHistoryIssuerContractTest {
    private val processJob = SupervisorJob()

    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()
        var loadFailures = 0
        override suspend fun load(): AccessEpochRecord {
            if (loadFailures > 0) { loadFailures -= 1; throw IOException("load") }
            return record
        }
        override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun retireUnverifiedStart() = AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
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

    private class Purger : UserScopePurger, CapabilityScopePurger {
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
    }

    private class Source(var next: () -> EntitlementsOutcome) : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)
        var afterFetch: () -> Unit = {}
        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            val outcome = next()
            val owner = checkNotNull(identity)
            afterFetch().also { afterFetch = {} }
            return EntitlementsResult.Answered(owner, outcome)
        }
        override suspend fun currentIdentity() = identity
    }

    private class Call(val guard: () -> Boolean) {
        val answer = CompletableDeferred<AuthBoundResult<List<AlertHistoryItem>>>()
    }

    private val identity = AuthIdentityFence(OWNER, 1L)
    private val owner = TopicDisplayOwner(identity, 7L)
    private val generic = BankHistoryBody.Failed(BANK_HISTORY_GENERIC_FAILURE, temporary = false)
    private fun item() = AlertHistoryItem(1, 1, "kb", "usd-krw", AlertCondition.ABOVE, 1400.5, 1401.2, Instant.parse("2026-10-01T01:00:00Z"))

    private fun TestScope.settle() { advanceTimeBy(SETTLE); runCurrent() }

    @Test fun S7C_A01_aRealUserHoldWithdrawsTheList_andItsReleaseReadsNothing() = runTest {
        try {
            var n = 0
            val store = Store { "epoch-${n++}" }
            val source = Source { EntitlementsOutcome.StableActive(krxVisible = false) }
            val coordinator = PremiumAccessCoordinator(
                source = source, store = store, userPurger = Purger(), capabilityPurger = Purger(),
                scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime },
                jitter = ProbeJitter.None, persistenceRetryDelayMillis = RETRY, liveFence = { identity },
                onLossReapprovalScheduled = { _, _ -> }, orders = AccessOrderSequence())
            coordinator.onIdentityChanged(identity)
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            settle()
            assertEquals("premise: premium", PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
            store.record = store.record.copy(mayContainPremiumData = true)
            val fence = checkNotNull(coordinator.topicGrantResult().fence) { "premise: a grant" }

            val reads = mutableListOf<Call>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val access = IssuerBankHistoryAccess({ identity }, { fence }, { owner }, SnapshotTopicUseAuthority { coordinator.accessSnapshot },
                { true }, { true })
            val wiring = BankHistoryWiring(
                access,
                BankHistoryRead { _, _, guard -> Call(guard).also { reads += it }.answer.await() },
                coordinator.accessRevisions, MutableStateFlow(0L))
            val c = BankHistoryController(owner, FreeTab.USD, wiring, { owner to FreeTab.USD }, { _ -> {} }, scope)
            c.open()
            runCurrent()
            val first = reads.single()
            assertTrue("premise: loading under a standing guard", first.guard())
            first.answer.complete(AuthBoundResult({}, Result.success(listOf(item()))))
            runCurrent()
            assertTrue("premise: shown", c.holder.state.value?.body is BankHistoryBody.Loaded)
            c.holder.refresh()
            runCurrent()
            val refresh = reads.last()
            assertTrue("premise: a refresh in flight under a standing guard", reads.size == 2 && refresh.guard())
            // A use taken under the same access before the hold, to watch the issuer's own verdict on it.
            val old = checkNotNull(access.acquire(owner)) { "premise: a use before the hold" }
            assertTrue("premise: admitted", access.admits(old))

            // A same-owner USER hold: a loss held as a candidate, the token unchanged (PremiumAccessTopicSnapshotTest's short hold).
            source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
            source.afterFetch = { store.loadFailures = 2 }
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            runCurrent()
            assertTrue("premise: held", TopicAccessBlock.LOSS_CANDIDATE in coordinator.accessSnapshot.facts.userBlocks)
            assertEquals("the hold's revision withdrew the list", generic, c.holder.state.value?.body)
            assertFalse("the refresh in flight is refused", refresh.guard())
            assertFalse("the issuer refuses the use from before the hold", access.admits(old))

            // Released: the held answer goes stale with the next session of the same owner.
            source.identity = EntitlementsIdentity(OWNER, 2L)
            advanceTimeBy(RETRY)
            runCurrent()
            assertTrue("premise: released", coordinator.accessSnapshot.facts.userAllowed)
            assertEquals("the release read nothing", 2, reads.size)
            assertEquals("and brought nothing back", generic, c.holder.currentState()?.body)
            assertFalse("the use from before the hold stays refused", access.admits(old))
            assertTrue("a new use is admitted", access.acquire(owner)?.let(access::admits) == true)

            c.holder.retry()
            runCurrent()
            assertEquals("only the user's retry reads again", 3, reads.size)
            assertTrue("under a new standing use", reads.last().guard())
            c.close()
            scope.cancel()
        } finally {
            processJob.cancel()
        }
    }

    private companion object {
        const val OWNER = "user-a"
        const val RETRY = 1_000L
        const val SETTLE = 60 * 60 * 1_000L
    }
}
