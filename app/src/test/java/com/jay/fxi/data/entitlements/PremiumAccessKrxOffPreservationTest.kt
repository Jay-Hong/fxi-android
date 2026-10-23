package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1a OFF 보존 회귀 잠금(스위치 꺼짐, HEAD bc9d614와 같은 동작). 사전 변이 배터리 r1의 OFF_guard_031(후보 해소 뒤 OFF 전용
 * ensureDemandArmedLocked 제거)이 계약 6클래스·전체 단위 시험에서 생존해 더한다 — 그 무장은 S6-2b0b(f102623)가 OFF 전용으로 바꾼 줄이다.
 * 클래스 계약 수준의 잠금이며 운영 도달 입력열은 아니다(호출자 IF_STALE·FORCE_ENTITLEMENTS는 현재 운영 배선에 없다, 미확인).
 * 근거: `S6/s6_2b1/offguard_probe_r1/`(분석 워크플로 wf_f90a5250-cea의 프로브).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessKrxOffPreservationTest {

    private val processJob = SupervisorJob()

    private class Store : AccessEpochStore {
        private var n = 0
        private val ids = EpochIdGenerator { "epoch-${n++}" }
        var record = AccessEpochRecord()
        var loadFailures = 0

        override suspend fun load(): AccessEpochRecord {
            if (loadFailures > 0) {
                loadFailures -= 1
                throw IOException("load")
            }
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

    /** One fetch, parked until answered. [after] runs once the answer is formed, before it returns. */
    private class Call(val fresh: Boolean) {
        val answer = CompletableDeferred<EntitlementsOutcome>()
        var after: () -> Unit = {}
    }

    private class Source : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)

        /** Answered at once, then cleared. */
        var auto: EntitlementsOutcome? = null
        val calls = mutableListOf<Call>()

        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            val owner = checkNotNull(identity)
            val call = Call(freshPremium)
            calls += call
            val outcome = auto?.also { auto = null } ?: call.answer.await()
            call.after()
            return EntitlementsResult.Answered(owner, outcome)
        }

        override suspend fun currentIdentity() = identity
    }

    private class Purger : UserScopePurger, CapabilityScopePurger {
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
    }

    private class Harness(val store: Store, val source: Source, val coordinator: PremiumAccessCoordinator)

    private suspend fun TestScope.confirmed(): Harness {
        val store = Store()
        val source = Source()
        val purger = Purger()
        val coordinator = PremiumAccessCoordinator(
            source = source,
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            persistenceRetryDelayMillis = RETRY,
            liveFence = { null },
            orders = AccessOrderSequence()
            // krxContest left at its default: KrxContestSwitch.OFF
        )
        val h = Harness(store, source, coordinator)
        coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        source.auto = EntitlementsOutcome.StableActive(krxVisible = true)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        return h
    }

    private fun offTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    /**
     * 후보의 정산이 인증 정지를 풀었는데 그 후보가 답하지 못한 나중 요구가 남으면, 후보 결정의 후속 무장(PAC 후보 해소의 OFF 전용
     * ensureDemandArmedLocked)이 그 요구를 실행한다. 잡을 변이: OFF_guard_031(그 무장 제거 — 요구가 영구히 미실행).
     *
     * B (IF_STALE), X (FORCE_ENTITLEMENTS) and A (FORCE_ENTITLEMENTS) start in that order, A last among callers.
     * X answers TRANSIENT: a FORCE_ENTITLEMENTS demand and a timer. A answers AUTHENTICATION: re-queries stop, the timer
     * stays. The timer fires Q (SCHEDULED, FORCE_ENTITLEMENTS); Q's StableInactive cannot be read, so it is held.
     * B, started before A, answers UNTYPED_FORBIDDEN: the demand is strengthened to FORCE_PREMIUM after Q started, and
     * only its floor is recorded (stopped). Recovery resolves Q: Q started after A, so its settlement resumes re-queries,
     * but it does not answer the stronger, later demand and asks for nothing itself.
     */
    @Test
    fun off_authStopResumedByCandidate_laterDemandIsRunByTheFollowUpArming() = offTest {
        val h = confirmed()
        advanceTimeBy(11_000) // past the IF_STALE debounce
        runCurrent()

        val b = async { h.coordinator.refresh(RefreshIntent.IF_STALE) }
        runCurrent()
        val x = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        val a = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        assertEquals("granted + B + X + A", 4, h.source.calls.size)
        val (callB, callX, callA) = h.source.calls.drop(1)

        callX.answer.complete(EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, retryAfterSeconds = 1))
        x.await()
        runCurrent()
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, h.coordinator.recheckDiagnostics().owedIntent)

        callA.answer.complete(EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))
        a.await()
        runCurrent()
        assertTrue("A stopped re-queries", h.coordinator.recheckDiagnostics().authStopped)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("the timer armed before the stop still fired Q", 5, h.source.calls.size)
        val callQ = h.source.calls[4]
        assertFalse(callQ.fresh)
        callQ.after = { h.store.loadFailures = 2 } // Q's decision read, and recovery's first read
        callQ.answer.complete(EntitlementsOutcome.StableInactive(krxVisible = false))
        runCurrent()
        assertEquals("Q is held", 1, h.coordinator.heldLossCandidateCount())

        callB.answer.complete(EntitlementsOutcome.Indeterminate(IndeterminateReason.UNTYPED_FORBIDDEN))
        b.await()
        runCurrent()
        val beforeResolve = h.coordinator.recheckDiagnostics()
        assertEquals(RefreshIntent.FORCE_PREMIUM, beforeResolve.owedIntent)
        assertTrue(beforeResolve.authStopped)
        assertEquals(0, beforeResolve.registeredQueryCount)
        val callsBefore = h.source.calls.size

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("recovery resolved Q", 0, h.coordinator.heldLossCandidateCount())
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        val afterResolve = h.coordinator.recheckDiagnostics()
        assertFalse("Q's settlement resumed re-queries", afterResolve.authStopped)
        assertEquals("the stronger later demand is still owed", RefreshIntent.FORCE_PREMIUM, afterResolve.owedIntent)

        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(
            "the owed demand is still FORCE_PREMIUM",
            RefreshIntent.FORCE_PREMIUM,
            h.coordinator.recheckDiagnostics().owedIntent
        )
        assertEquals("the owed FORCE_PREMIUM demand was run", callsBefore + 1, h.source.calls.size)
        assertTrue(h.source.calls.last().fresh)
    }

    private companion object {
        const val OWNER = "user-a"
        const val RETRY = 1_000L
        const val SETTLE = 60 * 60 * 1_000L
    }
}
