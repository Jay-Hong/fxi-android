package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1r-2a: a re-check requirement outlives the query and the timer that were carrying it.
 *
 * Every query here is parked or answered explicitly, so what ran — and with `fresh_premium` or not — is counted, not inferred
 * from state. Time is bounded; nothing waits for idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessRecheckDemandTest {

    private val processJob = SupervisorJob()

    private class Store : AccessEpochStore {
        private var n = 0
        private val ids = EpochIdGenerator { "epoch-${n++}" }
        var record = AccessEpochRecord()
        var beforeLoad: suspend () -> Unit = {}

        override suspend fun load(): AccessEpochRecord {
            beforeLoad()
            return record
        }
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

    /** One fetch as it happened: whether it asked fresh, and the gate it is parked on, if any. */
    private class Fetch(val fresh: Boolean, val gate: CompletableDeferred<Unit>?)

    private class Source : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)

        /** The answer a fetch gets, chosen when it starts. */
        var next: () -> EntitlementsOutcome = { EntitlementsOutcome.StableActive(krxVisible = true) }

        /** The next fetch parks until its gate completes. */
        var parkNext = false

        /** The next fetch throws once it is released. */
        var throwNext = false

        val fetches = mutableListOf<Fetch>()

        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            val answeredAs = checkNotNull(identity)
            val outcome = next()
            val gate = if (parkNext) CompletableDeferred<Unit>().also { parkNext = false } else null
            val fails = throwNext.also { throwNext = false }
            fetches += Fetch(freshPremium, gate)
            gate?.await()
            if (fails) throw IOException("transport")
            return EntitlementsResult.Answered(answeredAs, outcome)
        }

        override suspend fun currentIdentity() = identity
    }

    private class Purger : UserScopePurger, CapabilityScopePurger {
        var throwing: Throwable? = null
        private fun run(): PurgeResult = throwing?.let { throw it } ?: PurgeResult.Completed
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = run()
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = run()
    }

    private class Harness(val store: Store, val source: Source, val purger: Purger, val coordinator: PremiumAccessCoordinator)

    private fun TestScope.build(
        clock: RecheckClock? = null,
        beforeSettled: suspend (Long, Long) -> Unit = { _, _ -> }
    ): Harness {
        val store = Store()
        val source = Source()
        val purger = Purger()
        val coordinator = PremiumAccessCoordinator(
            source = source,
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = clock ?: RecheckClock { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = { null },
            beforeRecheckSettled = beforeSettled
        )
        return Harness(store, source, purger, coordinator)
    }

    /** Bound to [OWNER] and confirmed premium, with nothing scheduled. */
    private suspend fun TestScope.confirmed(
        beforeSettled: suspend (Long, Long) -> Unit = { _, _ -> }
    ): Harness {
        val h = build(beforeSettled = beforeSettled)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        return h
    }

    private fun demandTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    private fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun Harness.freshFetches() = source.fetches.count { it.fresh }

    // --- an answer that does not answer the requirement leaves it and its timer ------------------------------------------

    @Test
    fun anAuthenticationAnswerLeavesAWaitingRecheck_whichRunsOnce_andThenStops() = demandTest {
        val h = confirmed()
        // X starts first and will answer "authentication".
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.source.parkNext = true
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // Y starts after it and leaves a premium re-check waiting behind a floor.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()

        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        checkNotNull(h.source.fetches.first { it.gate != null }.gate).complete(Unit)
        x.await()
        runCurrent()
        assertEquals("nothing asked at once", fresh, h.freshFetches())

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the armed premium re-check still ran after its floor", fresh + 1, h.freshFetches())

        val total = h.source.fetches.size
        settle()
        assertEquals("its authentication answer arms nothing new", total, h.source.fetches.size)
    }

    @Test
    fun anAnswerThatStartedBeforeTheDemand_doesNotEndIt() = demandTest {
        val h = confirmed()
        // X asks without fresh_premium and will settle with nothing more to ask.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()

        checkNotNull(h.source.fetches.first { it.gate != null }.gate).complete(Unit)
        x.await()
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the demand X could not answer was still run", fresh + 1, h.freshFetches())
    }

    @Test
    fun anAnswerThatStartedAfterTheDemandEndsIt_andNothingMoreIsAsked() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        advanceTimeBy(60_000)
        runCurrent()
        val total = h.source.fetches.size

        settle()
        assertEquals(total, h.source.fetches.size)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    // --- whatever carried the requirement ends, the requirement is looked at again --------------------------------------

    @Test
    fun aTimerFiringWhileAStrongerQueryRuns_asksNothing_andTheDemandRunsAfterThatQueryEnds() = demandTest {
        val h = confirmed()
        // P: a premium query that started first and is still running.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        val p = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // Q: an entitlements query after it leaves an entitlements re-check behind the default floor.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val total = h.source.fetches.size

        advanceTimeBy(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
        runCurrent()
        assertEquals("the timer left it to P", total, h.source.fetches.size)

        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        checkNotNull(h.source.fetches.first { it.gate != null }.gate).complete(Unit)
        p.await()
        settle()
        assertEquals("P started before the demand, so the demand still ran once", total + 1, h.source.fetches.size)
        assertEquals(false, h.source.fetches.last().fresh)
    }

    @Test
    fun aQueryThatThrows_stillHasTheDemandItWasRelyingOnRun() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        h.source.throwNext = true
        val p = async { runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) } }
        runCurrent()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        advanceTimeBy(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
        runCurrent()
        val total = h.source.fetches.size

        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        checkNotNull(h.source.fetches.first { it.gate != null }.gate).complete(Unit)
        assertTrue(p.await().isFailure)
        settle()
        assertEquals("the demand ran after the query it waited on threw", total + 1, h.source.fetches.size)
    }

    @Test
    fun anAuthenticationAnswerThatStartedAfterTheDemand_stillLeavesItAndItsTimer() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Started after the demand and before its timer runs, and asked as strongly.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()

        runCurrent()
        assertEquals("an undecided answer ends nothing: the armed timer ran", fresh + 1, h.freshFetches())
    }

    @Test
    fun aWeakerAnswerThatStartedAfterAPremiumDemand_doesNotEndIt() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Settles with nothing more to ask, but without fresh_premium.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val fresh = h.freshFetches()

        runCurrent()
        assertEquals("the premium demand still ran", fresh + 1, h.freshFetches())
    }

    @Test
    fun aStrongerDemandJoinsATimerNotYetFired_withoutMovingItsDeadline() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        advanceTimeBy(1_000)
        runCurrent()
        // Held back by the floor: raises a premium demand onto the entitlements timer.
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val total = h.source.fetches.size
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }

        advanceTimeBy(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS - 1_000)
        runCurrent()
        assertEquals("fired at the original deadline", total + 1, h.source.fetches.size)
        assertTrue("and asked fresh", h.source.fetches.last().fresh)
    }

    @Test
    fun aRecheckFromAnAnswerOlderThanTheAuthenticationStop_keepsItsFloorButArmsNothing() = demandTest {
        val h = confirmed()
        // X starts first and will ask for an entitlements retry.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.source.parkNext = true
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // Z starts later and stops automatic re-queries.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        checkNotNull(h.source.fetches.first { it.gate != null }.gate).complete(Unit)
        x.await()
        val total = h.source.fetches.size
        settle()
        assertEquals("X's retry did not arm while stopped", total, h.source.fetches.size)
    }

    @Test
    fun aCallerAskingAgainAfterAnAuthenticationStop_resumesTheDemand_evenWhenItsOwnQueryDoesNotRun() = demandTest {
        val h = confirmed()
        // X: a premium query that started first, still running; it will settle without answering anything later.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // W: an entitlements query that will ask for a retry.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.source.parkNext = true
        val w = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // Z: answers authentication and stops.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val (xGate, wGate) = h.source.fetches.mapNotNull { it.gate }
        wGate.complete(Unit)
        w.await()
        runCurrent()

        // The caller asks for premium again inside the floor W's retry left: held back, but it resumes.
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceTimeBy(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
        runCurrent()
        val total = h.source.fetches.size

        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        xGate.complete(Unit)
        x.await()
        settle()
        assertTrue("the resumed premium demand ran after X ended", h.source.fetches.drop(total).any { it.fresh })
    }

    @Test
    fun aTopicRejectionWhileADemandIsOwed_doesNotClearItsTimer() = demandTest {
        val h = confirmed()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()

        h.coordinator.onTopicRejected(grant, listOf(com.jay.fxi.domain.model.TopicRejectionReason.PREMIUM_REQUIRED))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the owed premium re-check still ran", fresh + 1, h.freshFetches())
    }

    @Test
    fun aRetryMergedIntoTheDemand_doesNotDisqualifyAQueryThatStartedAfterIt() = demandTest {
        val h = confirmed()
        // R: started before the demand; it will ask for an entitlements retry.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.source.parkNext = true
        val r = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // The demand.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Q: a premium query after it, still running — started before the demand's timer is dispatched, so the timer
        // leaves the demand to Q instead of running (and later being replaced) itself.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        val q = async(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val (rGate, qGate) = h.source.fetches.mapNotNull { it.gate }

        // R's retry joins the demand as a retry.
        rGate.complete(Unit)
        r.await()
        runCurrent()
        qGate.complete(Unit)
        q.await()
        runCurrent()
        val total = h.source.fetches.size

        settle()
        assertEquals("Q answered the demand; nothing more was asked", total, h.source.fetches.size)
    }

    @Test
    fun anAuthenticationAnswerOlderThanTheCallersResumption_doesNotStopReQueriesAgain() = demandTest {
        val h = confirmed()
        // V: premium, first; it will leave a premium re-check.
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
        h.source.parkNext = true
        val v = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // X: second; it will answer authentication, late.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.source.parkNext = true
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // Z: answers authentication now and stops.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val (vGate, xGate) = h.source.fetches.mapNotNull { it.gate }
        // V's re-check is owed, and only its floor kept while stopped.
        vGate.complete(Unit)
        v.await()
        runCurrent()
        val fresh = h.freshFetches()

        // The caller asks again: that resumes. Its own query will throw.
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.parkNext = true
        h.source.throwNext = true
        val f = async { runCatching { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) } }
        runCurrent()
        val fGate = checkNotNull(h.source.fetches.last().gate)
        // X's authentication started before the resumption.
        xGate.complete(Unit)
        x.await()
        fGate.complete(Unit)
        f.await()
        settle()
        assertTrue("the owed premium re-check ran", h.freshFetches() > fresh)
    }

    @Test
    fun aHoldThatClosesAdmission_leavesTheDemand_andItsResolutionArmsIt() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()
        h.store.record = h.store.record.copy(
            pendingPurges = listOf(PendingPurge(OWNER, "older-user-epoch", null, setOf(PurgeScope.USER)))
        )
        h.purger.throwing = IOException("purge")
        val held = h.coordinator.resumeStartupPurge().heldByPersistence()

        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("nothing asked while the hold stands", fresh, h.freshFetches())

        h.purger.throwing = null
        advanceTimeBy((checkNotNull(held.nextAttemptAt) - testScheduler.currentTime).coerceAtLeast(0))
        h.coordinator.resumePersistence(held.id).applied()
        settle()
        assertEquals("the owed re-check ran once the hold resolved on the same binding", fresh + 1, h.freshFetches())
    }

    @Test
    fun aFloorExpiringBetweenClockReads_doesNotLoseTheNewBindingsFirstPremiumRequest() = demandTest {
        var crossing = false
        var reads = 0
        val h = build(
            clock = RecheckClock {
                if (crossing) {
                    if (reads++ == 0) 59_999L else 60_000L
                } else {
                    testScheduler.currentTime
                }
            }
        )
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        val total = h.source.fetches.size
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }

        crossing = true
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        assertEquals(total + 1, h.source.fetches.size)
        assertTrue(h.source.fetches.last().fresh)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun everyAdmittedCallerRecordsResumption_evenBeforeAnAuthenticationStopHasArrived() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true) }
        h.source.parkNext = true
        val retry = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        val retryGate = checkNotNull(h.source.fetches.last().gate)

        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        h.source.parkNext = true
        val auth = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        val authGate = checkNotNull(h.source.fetches.last().gate)

        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        h.source.throwNext = true
        assertTrue(runCatching {
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        }.isFailure)

        authGate.complete(Unit)
        auth.await()
        retryGate.complete(Unit)
        retry.await()
        val total = h.source.fetches.size

        settle()
        assertEquals("the later caller prevents the old AUTH from stopping the retry", total + 1, h.source.fetches.size)
    }

    @Test
    fun aKrxRequiredAnswer_doesNotLetAWeakerAnswerCancelThePremiumDemand() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.source.next = { EntitlementsOutcome.KrxEntitlementRequired }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fresh = h.freshFetches()

        advanceTimeBy(RecheckSchedule.BASE_BACKOFF_MILLIS)
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        assertEquals("the weaker answer did not cancel the owed FP", fresh + 1, h.freshFetches())
    }

    @Test
    fun aDebouncedCallerAfterThePreservedFloor_createsNoQueryWithoutADemand() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 1) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        advanceTimeBy(1_000L)
        val total = h.source.fetches.size

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.IF_STALE)
        runCurrent()

        assertEquals("a debounce-only timer has no demand to execute", total, h.source.fetches.size)
    }

    @Test
    fun aBindingChangeDropsRegistrationsAndDemand_andLateWorkCannotStrengthenItsSuccessor() = demandTest {
        val h = confirmed()
        h.source.parkNext = true
        val old = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val oldGate = checkNotNull(h.source.fetches.last().gate)
        val oldBinding = h.coordinator.recheckDiagnostics().bindingEpoch

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.source.identity = EntitlementsIdentity("user-b", 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence("user-b", 2L)).applied()
        assertEquals(
            "old registrations are discarded before their queries finish",
            0,
            h.coordinator.recheckDiagnostics().registeredQueryCount
        )

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val total = h.source.fetches.size
        h.coordinator.refresh(
            RefreshIntent.FORCE_PREMIUM,
            QueryOrigin.SCHEDULED,
            requireProbeEpoch = oldBinding
        )
        oldGate.complete(Unit)
        old.await()
        runCurrent()

        assertEquals(total, h.source.fetches.size)
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(total + 1, h.source.fetches.size)
        assertEquals("the old FP demand did not enter user-b", false, h.source.fetches.last().fresh)

        settle()
        assertEquals(total + 1, h.source.fetches.size)
    }

    @Test
    fun anOldCompletionWaitingForTheCoordinator_doesNotReplaceANewerTimerThatHasFired() = demandTest {
        val completionEntered = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        var firstCompletion = true
        val h = confirmed(beforeSettled = { _, _ ->
            if (firstCompletion) {
                firstCompletion = false
                completionEntered.complete(Unit)
                releaseCompletion.await()
            }
        })
        try {
            h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            runCurrent()
            assertTrue(completionEntered.isCompleted)

            h.store.beforeLoad = {
                h.store.beforeLoad = {}
                releaseLock.await()
            }
            val blocker = async(start = CoroutineStart.UNDISPATCHED) {
                h.coordinator.topicGrant()
            }
            assertTrue(!blocker.isCompleted)

            releaseCompletion.complete(Unit)
            runCurrent()
            val total = h.source.fetches.size
            h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
            advanceTimeBy(RecheckSchedule.BASE_BACKOFF_MILLIS)
            runCurrent()
            assertEquals(total, h.source.fetches.size)

            releaseLock.complete(Unit)
            blocker.await()
            runCurrent()

            assertEquals("J2 runs at its original deadline without replacement", total + 1, h.source.fetches.size)
            settle()
            assertEquals(total + 1, h.source.fetches.size)
        } finally {
            releaseCompletion.complete(Unit)
            releaseLock.complete(Unit)
        }
    }

    @Test
    fun cancellationBeforeAQueryIsRegistered_isRecoveredByTheTimerCompletion() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val total = h.source.fetches.size

        h.store.beforeLoad = {
            h.store.beforeLoad = {}
            throw kotlinx.coroutines.CancellationException("cancel before query registration")
        }
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        runCurrent()
        assertEquals(total, h.source.fetches.size)

        advanceTimeBy(RecheckSchedule.BASE_BACKOFF_MILLIS)
        runCurrent()
        assertEquals("completion rearmed the demand without a StartedQuery", total + 1, h.source.fetches.size)
    }

    @Test
    fun cancellationInsideApply_releasesTheQueryThatWasCoveringTheDemand() = demandTest {
        val h = confirmed()
        h.source.parkNext = true
        val first = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val fetchGate = checkNotNull(h.source.fetches.last().gate)

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        advanceTimeBy(60_000L)
        runCurrent()
        val total = h.source.fetches.size

        val applyEntered = CompletableDeferred<Unit>()
        val applyGate = CompletableDeferred<Unit>()
        h.store.beforeLoad = {
            h.store.beforeLoad = {}
            applyEntered.complete(Unit)
            applyGate.await()
        }
        fetchGate.complete(Unit)
        runCurrent()
        assertTrue(applyEntered.isCompleted)

        first.cancel()
        first.join()
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        settle()

        assertEquals("the registered query was removed despite cancellation in apply", total + 1, h.source.fetches.size)
    }

    @Test
    fun aLiveNullHeldAnswer_preservesItsRetryAfterUntilTheActualRequery() = demandTest {
        val h = confirmed()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60) }
        h.source.parkNext = true
        val first = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val gate = checkNotNull(h.source.fetches.last().gate)

        h.source.identity = null
        gate.complete(Unit)
        first.await()
        val total = h.source.fetches.size

        h.source.identity = EntitlementsIdentity(OWNER, 1L)
        h.source.next = { EntitlementsOutcome.StableActive(krxVisible = true) }
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(total, h.source.fetches.size)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(total + 1, h.source.fetches.size)
        assertTrue(h.source.fetches.last().fresh)
    }

    @Test
    fun reopeningAdmissionWithoutADemand_asksNothing() = demandTest {
        val h = confirmed()
        val total = h.source.fetches.size
        h.store.record = h.store.record.copy(
            pendingPurges = listOf(PendingPurge(OWNER, "older-user-epoch", null, setOf(PurgeScope.USER)))
        )
        h.purger.throwing = IOException("purge")
        val held = h.coordinator.resumeStartupPurge().heldByPersistence()

        h.purger.throwing = null
        advanceTimeBy((checkNotNull(held.nextAttemptAt) - testScheduler.currentTime).coerceAtLeast(0L))
        h.coordinator.resumePersistence(held.id).applied()
        settle()

        assertEquals(total, h.source.fetches.size)
    }

    private companion object {
        const val OWNER = "user-a"
        const val SETTLE = 60 * 60 * 1_000L
    }
}
