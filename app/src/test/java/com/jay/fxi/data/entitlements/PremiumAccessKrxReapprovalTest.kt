package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S6-2b0b R13‴ 계약 시험(스위치 켬) — CAP-K15~K23, K25~K28 (`R4c/S6/s6_2b0b/s6_2b0b_contracts.r8.md` 4.2, 보충 A1 r9 3절·7절).
 *
 * seal: H1 결정의 회전을 실패시킨다(PAC:2266-2299). 복구는 결정 끝에서 바로 한 번 돌고(PAC:2246), 실패하면 1·2·4초 뒤 다시
 * 돈다(PAC:2399-2401, RETRY = 1000). 그래서 rotationFailures = 2면 해제는 거절 + 1초, 3이면 + 3초, 4면 + 7초다.
 * 전용 조회: 조회 시작과 같은 잠금 구간의 observer가 origin = SCHEDULED ∧ reapprovalOnly로 표시한 조회다(설계 4.2).
 * PAC scope의 비동기 실패는 수집한다. 기본 기대는 0건이고, true 답의 적용 load 실패를 일부러 만드는 시험만 IOException("load")
 * 정확한 개수를 기대한다(그 실패는 후보가 아니라 예외다, PAC:2703).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessKrxReapprovalTest {

    private val processJob = SupervisorJob()
    private val backgroundFailures = mutableListOf<Throwable>()

    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)

    private data class Start(val order: Long, val atMillis: Long, val origin: QueryOrigin, val intent: RefreshIntent, val reapprovalOnly: Boolean) {
        val dedicated: Boolean get() = origin == QueryOrigin.SCHEDULED && reapprovalOnly
    }

    private fun reapprovalTest(expectedLoadFailures: Int = 0, body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
        assertEquals(
            "PAC scope 비동기 실패는 기대한 IOException(\"load\") ${expectedLoadFailures}건뿐이어야 한다",
            List(expectedLoadFailures) { "IOException:load" },
            backgroundFailures.map { "${it::class.simpleName}:${it.message}" }
        )
    }

    private fun TestScope.on(): KrxHarness = krxHarness(processJob, ON) { backgroundFailures += it }

    private fun KrxHarness.recordStarts(): MutableList<Start> {
        val out = mutableListOf<Start>()
        coordinator.onQueryStartedForTest = { order, at, origin, intent, ro -> out += Start(order, at, origin, intent, ro) }
        return out
    }

    private fun KrxHarness.view(): KrxContestView = checkNotNull(coordinator.krxContestView)
    private fun KrxHarness.phase(): KrxContestPhase = view().state.phase
    private fun KrxHarness.permit(): Long = view().state.permitSeq
    private fun KrxHarness.needed(): Boolean = view().reapproval.needed()

    private fun TestScope.stepTo(target: Long) {
        val now = testScheduler.currentTime
        require(target >= now) { "target $target < now $now" }
        step(target - now)
    }

    private val rsFields = listOf("revision", "consecutiveAttempts", "earliestAllowedAtMillis")
    private fun KrxHarness.rsSnapshot(): List<Any?> = recheckSchedule().let { rs -> rsFields.map { rs.readPrivate(it) } }

    /**
     * 공통 앞부분: 바인딩·KRX VISIBLE → 회전을 rotationFailures번 실패시키며 KRX 거절 → 인계 FE(true)가 seal 중에 적용(억제).
     * 반환: 거절·FE 시각 t0.
     */
    private suspend fun TestScope.suppressedTrue(h: KrxHarness, rotationFailures: Int = 2, grant: Boolean = true): Long {
        if (grant) h.grantedKrxVisible()
        h.store.rotationFailures = rotationFailures
        h.source.next = { active }
        val t0 = testScheduler.currentTime
        h.rejectKrx(h.issueGrant())
        step(0)
        assertEquals("FE true가 seal 중에 적용돼 숨겨진다", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertTrue("억제 근거가 기록된다", h.view().reapproval.suppressedTrueOrder != null)
        return t0
    }

    /** 첫 전용 조회(t0 + 5000)를 deferred로 잡아 시작시키고, 그 조회 시작 기록을 돌려준다. */
    private suspend fun TestScope.firstDedicatedHeld(h: KrxHarness, starts: List<Start>, t0: Long): Pair<Start, CompletableDeferred<EntitlementsOutcome>> {
        stepTo(t0 + 5_000 - 1)
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        step(1)
        val first = starts.single { it.dedicated }
        assertEquals(t0 + 5_000, first.atMillis)
        return first to gate
    }

    /** 진행 중인 조회의 true 답을 지금 적용시키되 적용 load만 실패시킨다(예외 1건, PAC:2703). */
    private fun TestScope.failApplyOf(h: KrxHarness, gate: CompletableDeferred<EntitlementsOutcome>) {
        h.store.loadSkipsBeforeFailure = 0
        h.store.loadFailures = 1
        gate.complete(active)
        step(0)
        assertEquals("premise: 적용 load 실패는 예외로 끝난다", 0, h.store.loadFailures)
    }

    // --- CAP-K15 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K15: H1 회전 실패 → 인계 FE 시작 전에 해제(첫 복구 라운드가 FE보다 먼저 돌고 성공). 인계 FE 답이 사실대로 발행되고,
     * 소비 때 필요 없음 → 전용 0, 재승인 보고 0. 잡을 변이: 근거 없이 재승인을 냄.
     */
    @Test fun K15_releaseBeforeHandoffFe_noDedicatedQuery() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.store.rotationFailures = 1
        h.source.next = { active }
        h.rejectKrx(h.issueGrant())
        settleKrx()
        assertEquals("전용 조회 0", 0, starts.count { it.dedicated })
        assertEquals("재승인 보고 0(소멸)", emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    // --- CAP-K16 --------------------------------------------------------------------------------------------------------

    /** CAP-K16 OPEN: 억제 → 해제(+1초) → 직전 entitlements 시작(t0) + 5초에 전용 1 → true → 노출. 잡을 변이: 소멸로 정지. */
    @Test fun K16_open_dedicatedAfterSpacing_revealsKrx() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        stepTo(t0 + 4_999)
        assertEquals("간격 전에는 전용 0", 0, starts.count { it.dedicated })
        assertEquals("무장 전에는 보고 0", 0, h.reapprovals.size)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        step(1)
        val dedicated = starts.filter { it.dedicated }
        assertEquals(1, dedicated.size)
        assertEquals(t0 + 5_000, dedicated.single().atMillis)
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, dedicated.single().intent)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertEquals(1, h.reapprovals.size)
        settleKrx()
        assertEquals("전용은 한 번뿐", 1, starts.count { it.dedicated })
    }

    /** CAP-K16 미만료 HOLD: n = 3(10초 보류) 바퀴에서 억제 → 해제·전용(+5초) → true는 보류 중이라 허가 없음 → 만료에 허가. */
    @Test fun K16_unexpiredHold_revealsAtExpiry() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        // n = 1, 2 바퀴는 회전 성공으로 쌓는다.
        repeat(2) { i ->
            h.rejectKrx(h.issueGrant())
            step(100)
            val at = h.source.calls.last().atMillis
            val c = KrxContestPolicy.cooldownMillis(i + 1)
            if (c > 0) stepTo(at + c)
        }
        val starts = h.recordStarts()
        h.store.rotationFailures = 2
        val t0 = testScheduler.currentTime
        h.rejectKrx(h.issueGrant())
        step(0)
        assertEquals(KrxContestPhase.HOLD, h.phase())
        assertEquals(t0 + 10_000, h.view().state.holdUntilMillis)
        val permit = h.permit()
        stepTo(t0 + 5_000)
        assertEquals(1, starts.count { it.dedicated })
        assertEquals("보류 중이라 허가 없음", permit, h.permit())
        stepTo(t0 + 10_000)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /** CAP-K16 만료 HOLD: n = 2(5초 보류)가 seal 중 만료(X2) → 해제(+7초)·전용 → true → `KrxVisiblePublished`로 허가. */
    @Test fun K16_expiredHold_permitsOnVisiblePublish() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        h.rejectKrx(h.issueGrant())
        step(100)
        val starts = h.recordStarts()
        suppressedTrue(h, rotationFailures = 4, grant = false)
        assertEquals(KrxContestPhase.HOLD, h.phase())
        val holdUntil = checkNotNull(h.view().state.holdUntilMillis)
        val permit = h.permit()
        stepTo(holdUntil)
        assertEquals("만료됐지만 _krx HIDDEN이라 허가 없음(X2)", KrxContestPhase.HOLD, h.phase())
        assertTrue(h.view().state.holdExpired)
        assertEquals(permit, h.permit())
        settleKrx()
        assertEquals(1, starts.count { it.dedicated })
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /**
     * CAP-K16 (iv) 전용 답의 정산 수행(r7, 구현 검토 F2): SCHEDULED 전용 조회의 true가 G3로 칸을 비워도 그 답의 정산(settleDemandLocked)은
     * 같은 잠금 구간에서 돈다. 전용 답은 비우기 전 칸의 HEAD 정산 조건 5개를 모두 만족하므로 정산이 floor를 회수하고 무장을 남기지 않는다
     * (설계 r7 3.3 G3). 표지: 전용 무장의 floor(t0 + 5000)가 그 답 뒤 사라진다. 잡을 변이: G3가 publishLocked에서 RS를 취소해 자기 작업이
     * 끊기고 정산을 건너뜀(floor가 남는다), 정산 생략.
     * (계획과 다른 점: Codex r2 판단의 표지 'authStopped 해제'는 쓸 수 없었다. 전용 조회보다 먼저 시작한 CALLER는 해제 회전의 fence 변경으로
     * 답이 버려지고(PAC:1957), 해제 뒤 시작은 IF_STALE이면 debounce, FE·FP면 덮음(inFlightCovers)으로 전용 조회 앞에 둘 수 없다.)
     */
    @Test fun K16_dedicatedTrueIsSettled_retiresItsFloor() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        val (first, gate) = firstDedicatedHeld(h, starts, t0)
        assertEquals("premise: 전용 무장의 floor", first.atMillis, h.recheckSchedule().readPrivate("earliestAllowedAtMillis"))
        gate.complete(active)
        step(0)
        assertFalse(h.needed())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertNull("정산이 그 칸을 HEAD 조건으로 끝내 floor를 회수한다", h.recheckSchedule().readPrivate("earliestAllowedAtMillis"))
        assertFalse("무장이 남지 않는다", h.recheckSchedule().isArmed)
        assertNull(h.coordinator.recheckDiagnostics().owedIntent)
    }

    // --- CAP-K17 --------------------------------------------------------------------------------------------------------

    /** CAP-K17 해제 전: 억제 뒤 늦게 시작한 CALLER의 false(사실대로 발행, q > 억제 순번) → 소비 때 소멸. 전용 0, NONE·숨김. */
    @Test fun K17_laterFalseBeforeRelease_extinguishes() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        suppressedTrue(h)
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        settleKrx()
        assertEquals(0, starts.count { it.dedicated })
        assertEquals(emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        assertEquals(KrxContestPhase.NONE, h.phase())
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
    }

    /**
     * CAP-K17 해제 뒤: 칸이 reapprovalOnly로 간격을 기다리는 동안 CALLER false가 사실대로 발행 → 필요 없음, 보고 0, 해제 뒤 SCHEDULED 0.
     * 잡을 변이: 사실 false 미기록(G2), 해제 즉시 무장(간격 없는 HEAD 재승인). G3는 이 창에서 HEAD 정산(PAC:2040-2050)이 대신해 관측할 수
     * 없고 K20 (a)의 IF_STALE 입력이 맡는다.
     */
    @Test fun K17_laterFalseAfterRelease_clearsTheSlot() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 2_000)
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertFalse("CALLER false가 사실 근거가 되어 필요가 없다", h.needed())
        step(100)
        settleKrx()
        assertEquals(0, starts.count { it.dedicated })
        assertEquals("간격 대기 중 무장 0이라 보고 0", emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        assertTrue("해제 뒤 SCHEDULED 조회 0", starts.none { it.origin == QueryOrigin.SCHEDULED && it.atMillis > t0 })
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
    }

    /**
     * CAP-K17 (iii) 오래된 StableInactive는 사실 근거(r7, 구현 검토 F3, A1 r9:38·:71): seal 중 억제 true(sup) 뒤 시작한 CALLER FE의
     * StableInactive가 토픽 후보(H1′)의 barrier 아래라 오래된(S1) 답이어도, decideLocked는 그 답을 통째로 적용한다(KRX 축 무력화는
     * StableActive·Pending뿐). 그래서 G2가 사실 근거로 기록해 필요가 없어진다. 토픽 후보의 첫 복구 라운드는 신원 미확인으로 후보를 남겨
     * 그 결정의 회전이 q2를 fence로 버리지 않게 한다. 잡을 변이: 오래된 직접 답을 종류와 무관하게 근거에서 뺌.
     */
    @Test fun K17_staleStableInactive_isTruthfulEvidence() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        suppressedTrue(h)
        val sup = checkNotNull(h.view().reapproval.suppressedTrueOrder)
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val q2 = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        val q2Order = starts.last().order
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        assertTrue(q2Order > sup)
        val grant = h.issueGrant()
        h.store.loadFailures = 1
        h.source.identityNulls = 1
        h.rejectKrx(grant)
        assertEquals("premise: 토픽 후보", 1, h.coordinator.heldLossCandidateCount())
        assertTrue("premise: q2는 barrier 아래(오래된 답)", q2Order <= checkNotNull(h.view().state.barrier))

        gate.complete(EntitlementsOutcome.StableInactive(krxVisible = false))
        step(0)
        assertEquals("오래된 StableInactive도 사실 근거", q2Order, h.view().reapproval.truthfulOrder)
        assertFalse("필요 없음", h.needed())
        q2.join()
    }

    /**
     * CAP-K17 (iv) G3의 floor 회수(r7, 구현 검토 Q1, 설계 r7 3.3 G3): 전용 조회의 Pending(true, 60초)이 RS floor를 세운 뒤(RETRY라 칸은
     * 재승인 전용 유지), 그 칸의 raisedAt 뒤에 시작한 CALLER FORCE_PREMIUM의 true가 사실대로 발행돼 필요를 없앤다. 그 답은 비우기 전
     * 칸의 HEAD 정산 조건 5개를 모두 만족하므로 G3가 칸을 비우면서 floor를 회수한다. 잡을 변이: G3가 늘 floor를 보존.
     */
    @Test fun K17_g3OnASettlingAnswer_retiresTheServerFloor() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 5_000 - 1)
        val qd = CompletableDeferred<EntitlementsOutcome>()
        val qf = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(qd)
        h.source.gates.addLast(qf)
        step(1)
        assertTrue("premise: 전용 조회 시작", starts.last().dedicated)
        val fp = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        assertEquals("premise: CALLER FP가 칸 뒤에 시작", QueryOrigin.CALLER, starts.last().origin)

        qd.complete(EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 60))
        step(0)
        val floor = h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?
        assertTrue("premise: Pending이 floor를 세웠다: $floor", floor != null && floor >= t0 + 5_000 + 60_000)
        assertTrue("premise: 필요가 남는다", h.needed())

        qf.complete(active)
        step(0)
        assertFalse(h.needed())
        assertNull("G3: 정산 가능한 답이면 floor를 회수한다", h.recheckSchedule().readPrivate("earliestAllowedAtMillis"))
        assertNull(h.coordinator.recheckDiagnostics().owedIntent)
        fp.join()
    }

    // --- CAP-K18 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K18 완료 순서 역전. Dormant 대조와 같은 입력: H1(seal) → CALLER FE q1을 UNDISPATCHED로 즉시 시작(gate, 답 false) → scheduler
     * (예약 FE는 진행 중 q1이 덮어 시작하지 않는다, PAC:1551-1556) → CALLER FP q2의 true가 seal 중 억제 → q1 false 적용(C1, q1 < q2라
     * 필요는 남음) → 해제 → 전용 1 → true → T1. 잡을 변이: 완료 순서로 판정해 소멸.
     */
    @Test fun K18_completionInversion_keepsTheNeed_andRevives() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.store.rotationFailures = 2
        h.source.next = { active }
        h.rejectKrx(h.issueGrant())
        val q1Gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(q1Gate)
        val q1 = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        val q1Start = starts.last()
        assertEquals("q1은 CALLER로 실제 시작한다", QueryOrigin.CALLER, q1Start.origin)
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, q1Start.intent)
        val startsBeforeScheduler = starts.size
        step(0)
        assertEquals("premise: 예약 FE는 q1이 덮어 시작하지 않는다", startsBeforeScheduler, starts.size)
        val q2 = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        step(100)
        val q2Start = starts.last()
        assertEquals(QueryOrigin.CALLER, q2Start.origin)
        assertTrue("q2는 q1보다 늦게 시작", q2Start.order > q1Start.order)
        assertEquals("q2 true는 seal 중 억제", q2Start.order, h.view().reapproval.suppressedTrueOrder)
        q1Gate.complete(krxFalse)
        step(0)
        assertEquals("q1 false는 신선해 C1", KrxContestPhase.NONE, h.phase())
        assertTrue("억제 true가 더 새로운 근거라 필요가 남는다", h.needed())
        val permit = h.permit()
        settleKrx()
        assertEquals(1, starts.count { it.dedicated })
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals("재승인 true가 NONE에서 T1", permit + 1, h.permit())
        q1.join()
        q2.join()
    }

    // --- CAP-K19 --------------------------------------------------------------------------------------------------------

    /** K19 앞부분: 해제 뒤(t0 + 2000) CALLER q3의 false가 답 적용 load 실패로 질의 후보, 복구 라운드도 계속 실패. */
    private suspend fun TestScope.heldNewerCandidate(h: KrxHarness, t0: Long) {
        stepTo(t0 + 2_000)
        h.source.next = { krxFalse }
        h.store.loadSkipsBeforeFailure = 1   // 조회 시작의 load는 통과, 답 적용부터 실패
        h.store.loadFailures = Int.MAX_VALUE // 후보 복구도 실패해 후보가 남는다
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        assertEquals("premise: 질의 후보 보류", 1, h.coordinator.heldLossCandidateCount())
    }

    /**
     * CAP-K19 (결정 변형): 칸 reapprovalOnly, 해제 뒤 시작한 CALLER q3의 false가 질의 후보 보류 → 후보 결정까지 무장 0(RS revision·
     * attempts·floor 불변, 전용 0) → 결정이 C1(사실대로 발행, q3 > 억제 순번)이면 G3로 칸 비움, 필요 없음, 전용 0. 잡을 변이: (b) 누락.
     */
    @Test fun K19_heldNewerCandidate_blocksArming_thenDecisionClears() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        heldNewerCandidate(h, t0)
        val before = h.rsSnapshot()
        assertFalse(h.recheckSchedule().isArmed)
        stepTo(t0 + 5_000)
        stepTo(t0 + 9_000)
        assertEquals("후보가 남은 동안 RS 불변", before, h.rsSnapshot())
        assertFalse(h.recheckSchedule().isArmed)
        assertEquals("후보가 남은 동안 전용 0", 0, starts.count { it.dedicated })
        h.store.loadFailures = 0
        settleKrx()
        assertEquals("premise: 후보 결정", 0, h.coordinator.heldLossCandidateCount())
        assertFalse("결정이 필요를 없앤다", h.needed())
        assertEquals(0, starts.count { it.dedicated })
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
    }

    /**
     * CAP-K19 (Stale 변형): 같은 앞부분 뒤 capability epoch만 옮겨(키 유지) 후보를 Stale로 해제(PAC:2807 → :2792 → :2872). 해제는 사실
     * 근거가 아니라 필요가 남고, G6을 다시 거쳐 전용 조회 1 → true → 노출. 잡을 변이: 해제에서 무장 없음, 해제를 사실 근거로 셈.
     */
    @Test fun K19_heldNewerCandidate_staleRelease_keepsTheNeed_andRevives() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        heldNewerCandidate(h, t0)
        stepTo(t0 + 9_000)
        assertEquals(0, starts.count { it.dedicated })
        h.store.loadFailures = 0
        h.store.beginRotation(rotateUser = false, rotateKrx = true)
        h.source.next = { active }
        settleKrx()
        assertEquals("premise: 후보 해제", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("Stale 해제 뒤 전용 1", 1, starts.count { it.dedicated })
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    // --- CAP-K20 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K20 (a): 첫 전용 답의 적용 load 실패(true라 예외) → finally에서 G6 간격 대기 → 첫 시작 + 5초에 RS attempt 2 무장(백오프 5초)
     * → 발화 시각(첫 시작 + 10초)에 CALLER가 예약 콜백보다 먼저 잠금을 얻어 false를 사실대로 발행 → 필요 해소·칸 비움 → 발화가 묻지
     * 않고 돌아간다. CALLER는 IF_STALE이라 HEAD 정산(PAC:2044 intent < FE)이 칸을 끝내지 못하고, R13‴(G3 또는 G6 (a))만이 칸을 비우고
     * 무장을 취소한다. 잡을 변이: G3 절차 누락(칸 비움·무장 취소). 발화 시점 (a)만의 누락은 G3와 동등하다(필요는 G2에서만 바뀐다).
     */
    @Test fun K20_truthfulFalseBetweenArmingAndFiring_isNotQueried() = reapprovalTest(expectedLoadFailures = 1) {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        val (first, gate) = firstDedicatedHeld(h, starts, t0)
        failApplyOf(h, gate)
        advanceTimeBy(first.atMillis + 10_000 - testScheduler.currentTime)
        h.source.next = { krxFalse }
        val beforeCaller = starts.size
        // IF_STALE: HEAD 정산(PAC:2044 intent < FE)이 칸을 끝내지 못하므로 R13‴만이 칸을 비운다.
        // 직전 조회 시작(첫 전용 = first.atMillis) + 10000ms라 debounce(RS:188-189)를, 지금 == floor라 floor(RS:183-185)를 통과한다.
        h.coordinator.refresh(RefreshIntent.IF_STALE)
        assertEquals(beforeCaller + 1, starts.size)
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        assertEquals(RefreshIntent.IF_STALE, starts.last().intent)
        assertFalse(h.needed())
        assertEquals(null, h.coordinator.recheckDiagnostics().owedIntent)
        assertFalse("G3는 무장도 취소한다", h.recheckSchedule().isArmed)
        step(0)
        settleKrx()
        assertEquals("두 번째 전용 조회 없음", 1, starts.count { it.dedicated })
    }

    /**
     * CAP-K20 (b): 같은 발화 시각에 CALLER FP를 먼저 시작하되 그 true 답의 적용 load가 실패(직접 포착) → 필요·전용 표시 유지 →
     * 예약 콜백 실행. 직전 CALLER 시작 + 5000ms 전에는 재조회가 없고, 뒤에 전용 조회가 나가 노출된다. 잡을 변이: 발화 때 간격 미검사.
     */
    @Test fun K20b_callerAtFiringFails_keepsNeed_andRespacesFromItsStart() = reapprovalTest(expectedLoadFailures = 1) {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        val (first, gate) = firstDedicatedHeld(h, starts, t0)
        failApplyOf(h, gate)
        advanceTimeBy(first.atMillis + 10_000 - testScheduler.currentTime)
        val callerAt = testScheduler.currentTime
        h.source.next = { active }
        h.store.loadSkipsBeforeFailure = 1
        h.store.loadFailures = 1
        val failure = runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }.exceptionOrNull()
        assertTrue("CALLER true 답 적용의 load 실패를 직접 포착: $failure", failure is IOException)
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        assertEquals(callerAt, starts.last().atMillis)
        assertTrue("필요 유지", h.needed())
        val startsAfterCaller = starts.size
        runCurrent()
        stepTo(callerAt + 5_000 - 1)
        assertEquals("직전 CALLER 시작 + 5000ms 전 재조회 없음", startsAfterCaller, starts.size)
        settleKrx()
        val later = starts.drop(startsAfterCaller)
        assertTrue("뒤에 전용 조회가 나간다", later.any { it.dedicated })
        assertTrue("그 시각은 CALLER 시작 + 5000ms 이후", later.all { it.atMillis >= callerAt + 5_000 })
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /**
     * CAP-K20 (a)′ G3의 floor 보존(r7, 구현 검토 Q1 대조): K20 (a)와 같은 입력. 발화 시각의 CALLER IF_STALE은 시작 intent가 FE보다
     * 약해 HEAD 정산 조건을 못 채우므로(PAC:2044) G3가 칸을 비울 때 floor(첫 시작 + 10000)를 보존한다. 잡을 변이: G3가 늘 floor를 회수.
     */
    @Test fun K20_g3OnANonSettlingAnswer_preservesTheServerFloor() = reapprovalTest(expectedLoadFailures = 1) {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        val (first, gate) = firstDedicatedHeld(h, starts, t0)
        failApplyOf(h, gate)
        advanceTimeBy(first.atMillis + 10_000 - testScheduler.currentTime)
        assertEquals("premise: RS attempt 2의 floor", first.atMillis + 10_000, h.recheckSchedule().readPrivate("earliestAllowedAtMillis"))
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.IF_STALE)
        assertEquals(RefreshIntent.IF_STALE, starts.last().intent)
        assertFalse(h.needed())
        assertNull(h.coordinator.recheckDiagnostics().owedIntent)
        assertEquals("G3: 정산 못 하는 답이면 floor를 보존한다", first.atMillis + 10_000, h.recheckSchedule().readPrivate("earliestAllowedAtMillis"))
    }

    // --- CAP-K21 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K21 FP 합류: 해제 뒤 전용 FE 칸이 간격을 기다리는 동안 CALLER FE false를 gate로 시작 → CALLER FP Pending(true)로 더 센 FP
     * demand가 칸에 합류(PAC:2087-2093) → 먼저 시작한 FE false 완료로 필요 해소. 약한 FE는 FP demand를 정산하지 못하므로
     * (PAC:2043-2045) 일반 FP 조회 1이 남는다. 잡을 변이: 합류 뒤에도 G3로 칸을 비움, 합류를 재승인 전용으로 둠.
     */
    @Test fun K21_strongerFpDemandMerges_falseClearsNeed_butFpQueryRemains() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 2_000)
        assertTrue("premise: 해제 뒤 필요", h.needed())
        val feGate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(feGate)
        val fe = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 30) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("FP demand가 합류", RefreshIntent.FORCE_PREMIUM, h.coordinator.recheckDiagnostics().owedIntent)
        val before = starts.size
        feGate.complete(krxFalse)
        step(0)
        assertFalse("먼저 시작한 FE false가 필요를 없앤다", h.needed())
        assertEquals("FE는 FP demand를 정산하지 못한다", RefreshIntent.FORCE_PREMIUM, h.coordinator.recheckDiagnostics().owedIntent)
        h.source.next = { active }
        settleKrx()
        val after = starts.drop(before)
        assertEquals("일반 FP 조회 1", 1, after.count { it.origin == QueryOrigin.SCHEDULED && it.intent == RefreshIntent.FORCE_PREMIUM })
        assertTrue("합류한 칸은 재승인 전용이 아니다", after.none { it.reapprovalOnly })
        assertEquals(0, starts.count { it.dedicated })
        fe.join()
    }

    // --- CAP-K22 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K22 간격: 직전 entitlements 조회 시작 뒤 4.999초에는 무장·schedule 호출 0(RS revision·attempts·floor 불변), 5.000초에는 무장.
     * 잡을 변이: RS floor로 구현.
     */
    @Test fun K22_spacingBoundary_noScheduleBefore_armAt() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 999)
        val before = h.rsSnapshot()
        stepTo(t0 + 1_000)
        assertEquals("premise: 해제(복구 라운드 2)가 재승인 칸을 올렸다", RefreshIntent.FORCE_ENTITLEMENTS, h.coordinator.recheckDiagnostics().owedIntent)
        stepTo(t0 + 4_999)
        assertEquals("해제 뒤 간격 전에는 schedule 호출 0, attempts·floor 불변", before, h.rsSnapshot())
        assertEquals(0, starts.count { it.dedicated })
        step(1)
        assertEquals(1, starts.count { it.dedicated })
        assertEquals(t0 + 5_000, starts.first { it.dedicated }.atMillis)
    }

    /**
     * CAP-K22 모든 조회 시작 기준: 억제(t0) 뒤 t0 + 500에 CALLER FP 시작(시작 load 통과, true 답 적용 load 실패를 직접 포착 → 근거·
     * 필요 유지) → 해제(t0 + 1000) → t0 + 5499까지 RS revision·attempts·floor 불변 → t0 + 5500에 전용 조회. 잡을 변이: 인계 FE만 기준.
     */
    @Test fun K22_spacingCountsEveryEntitlementsStart() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 500)
        h.store.loadSkipsBeforeFailure = 1
        h.store.loadFailures = 1
        val failure = runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }.exceptionOrNull()
        assertTrue("CALLER true 답 적용의 load 실패를 직접 포착: $failure", failure is IOException)
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        assertEquals(t0 + 500, starts.last().atMillis)
        assertTrue(h.needed())
        stepTo(t0 + 999)
        val before = h.rsSnapshot()
        stepTo(t0 + 1_000)
        assertEquals("premise: 해제가 재승인 칸을 올렸다", RefreshIntent.FORCE_ENTITLEMENTS, h.coordinator.recheckDiagnostics().owedIntent)
        stepTo(t0 + 5_499)
        assertEquals("CALLER 시작 + 5초 전에는 RS 불변", before, h.rsSnapshot())
        assertEquals(0, starts.count { it.dedicated })
        step(1)
        assertEquals(1, starts.count { it.dedicated })
        assertEquals(t0 + 5_500, starts.first { it.dedicated }.atMillis)
    }

    // --- CAP-K23 --------------------------------------------------------------------------------------------------------

    /** CAP-K23 binding 변경: 근거를 버린다(옛 binding의 억제 근거로 재승인하지 않는다). */
    @Test fun K23_bindingChange_dropsTheEvidence() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        suppressedTrue(h)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OTHER, 1L)).applied()
        settleKrx()
        assertEquals(KrxReapprovalEvidence.EMPTY.suppressedTrueOrder, h.view().reapproval.suppressedTrueOrder)
        assertEquals("옛 binding의 근거로 전용 조회를 내지 않는다", 0, starts.count { it.dedicated })
    }

    /** K23 공통: 재승인 보고가 [intent] 1건이고, 그 재승인 조회는 일반 조회(reapprovalOnly = false)이며 전용 조회는 0이다. */
    private fun assertHeadReapproval(h: KrxHarness, starts: List<Start>, intent: RefreshIntent, releasedFrom: Int) {
        assertEquals("HEAD 재승인 보고", listOf(intent), h.reapprovals.map { it.first })
        val later = starts.drop(releasedFrom)
        assertTrue("재승인 조회가 나간다", later.any { it.origin == QueryOrigin.SCHEDULED && it.intent == intent })
        assertTrue("R13 전용 처리를 쓰지 않는다", later.none { it.reapprovalOnly })
        assertEquals(0, starts.count { it.dedicated })
    }

    /**
     * CAP-K23 :825 출처: CAPABILITY seal을 남긴 채 같은 uid의 새 auth generation으로 binding → 착지가 새 binding에 재승인을 등록
     * (PAC:825). 새 binding의 첫 true는 seal에 숨겨진다. 해제 때 HEAD 재승인(FE)을 그대로 낸다. 잡을 변이: 출처 무관 R13 적용.
     */
    @Test fun K23_sameUidRebindWhileSealed_keepsHeadReapproval() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.store.rotationFailures = 3
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 2L)).applied()
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        assertEquals("premise: 새 binding의 true는 seal에 숨겨진다", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        val from = starts.size
        settleKrx()
        assertHeadReapproval(h, starts, RefreshIntent.FORCE_ENTITLEMENTS, from)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /**
     * CAP-K23 :1249 출처: 서명 해제가 착지한 뒤 예외로 끝나 복구 장벽으로 같은 uid를 다시 binding(PAC:1249). CAPABILITY seal이 서명
     * 해제를 넘어 서도록 KRX epoch가 없는 기록과 Deferred purge를 쓴다(PremiumAccessLossSealTest의 같은 입력). 해제 때 HEAD 재승인.
     */
    @Test fun K23_signOutRecoveryRebind_keepsHeadReapproval() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.purger.result = PurgeResult.Deferred("retain the unknown journal")
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        val original = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
        h.source.liveFence = original
        val ticket = (h.coordinator.prepareSignOut(original) as SignOutStart.Armed).ticket
        assertTrue(h.coordinator.stopDriver(ticket))
        h.store.signOutLandsThenThrows = true
        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val rebound = AuthIdentityFence(KrxHarnessDefaults.OWNER, 2L)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.source.liveFence = rebound
        assertEquals("premise: 복구 장벽 binding", BarrierStep.RELEASED, h.coordinator.completeRecovery(ticket, rebound).step)

        val from = starts.size
        h.store.rotationFailures = 0
        h.source.next = { active }
        settleKrx()
        assertHeadReapproval(h, starts, RefreshIntent.FORCE_ENTITLEMENTS, from)
    }

    /**
     * CAP-K23 USER 축(:2320 출처): 권위적 상실의 USER·CAPABILITY 회전 실패로 seal → 그 중 FP true의 grant가 억제되며 재승인 등록
     * (PAC:2320). 해제 때 HEAD 재승인(FP)을 그대로 낸다. 잡을 변이: USER 축 빚에 R13 적용.
     */
    @Test fun K23_userAxisSuppressedGrant_keepsHeadReapproval() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.store.rotationFailures = 3
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        assertEquals("premise: USER seal이 grant를 억제", PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        val from = starts.size
        settleKrx()
        assertHeadReapproval(h, starts, RefreshIntent.FORCE_PREMIUM, from)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    // --- CAP-K25 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K25 B2: 인계 FE 진행 중 해제, 답이 fence로 폐기 → 근거 없음 → 전용 0. 인계 FE demand 재시도는 HEAD와 같다(FE 끝 + RS
     * 2회째 백오프 5초). 잡을 변이: B2를 필요로 판정.
     */
    @Test fun K25_feDroppedByFence_noDedicated_headRetry() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        h.store.rotationFailures = 2   // H1과 첫 복구 라운드 실패 → 해제는 +1초. FE는 그 전에 시작해 해제를 넘는다
        val feGate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(feGate)
        val t0 = testScheduler.currentTime
        h.rejectKrx(h.issueGrant())
        step(0)
        assertEquals("FE가 진행 중", 1, starts.count { it.origin == QueryOrigin.SCHEDULED })
        stepTo(t0 + 1_000)
        assertTrue("해제가 epoch를 바꿨다", h.store.rotationAttempts >= 3)
        feGate.complete(active)
        step(0)
        settleKrx()
        assertEquals("전용 0", 0, starts.count { it.dedicated })
        assertEquals("근거 없음 → 소멸, 재승인 보고 0(G4)", emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        val scheduled = starts.filter { it.origin == QueryOrigin.SCHEDULED }
        assertEquals("FE 재시도 1", 2, scheduled.size)
        assertEquals("재시도는 FE 끝(+1초) + RS 2회째 백오프 5초", t0 + 6_000, scheduled[1].atMillis)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    // --- CAP-K26 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K26 Codex r5 반례: q1(false, store.load 실패로 질의 후보) < q2(true, 적용 지연) < q3(true, 먼저 사실대로 발행) → q1 결정
     * C1·CAPABILITY 회전 실패(seal·빚) → q2 적용(억제) → 해제·소비 → 필요 참 → 전용 1 → 노출. 잡을 변이: 억제 뒤 이전 truthfulOrder 유지.
     */
    @Test fun K26_publishOrderCounterexample_revives() = reapprovalTest {
        val h = on()
        h.grantedKrxVisible()
        val starts = h.recordStarts()
        // q1: CALLER FE, false, 적용 때 store.load 실패 → 질의 후보.
        val q1Gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(q1Gate)
        val q1 = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        step(0)
        // q2: CALLER FP, true, 늦게 답.
        val q2Gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(q2Gate)
        val q2 = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        step(0)
        // q3: CALLER FE, true, 먼저 사실대로 발행.
        h.source.next = { active }
        val q3 = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        step(0)
        // q1 적용의 load 실패 → 질의 후보. 후보 결정의 C1 false edge 회전과 첫 복구 라운드 실패 → seal·빚(PAC:2299).
        h.store.loadFailures = 1
        h.store.rotationFailures = 2
        q1Gate.complete(krxFalse)
        step(0)
        assertEquals("q1 후보 결정은 C1", KrxContestPhase.NONE, h.phase())
        q2Gate.complete(active)
        step(0)
        assertTrue("억제 발행이 truthfulOrder를 비워 필요가 참", h.needed())
        settleKrx()
        assertEquals(1, starts.count { it.dedicated })
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        q1.join(); q2.join(); q3.join()
    }

    // --- CAP-K27 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K27: 첫 전용 조회 t0 + 5000(RS attempt 1)을 잡아 두었다가 시작 + 50ms에 true를 완료시키되 적용 load 실패(예외) → finally에서
     * G6 간격 대기 → 첫 시작 + 5000ms에 RS attempt 2 무장(백오프 5000ms) → 두 번째 전용 조회 첫 시작 + 10000ms. 둘 다 전용으로 센다.
     * 잡을 변이: 재시도를 계수에서 뺌, G6 우회, 간격을 실패 시각 기준으로 셈.
     */
    @Test fun K27_applyFailureRetry_exactTimeline() = reapprovalTest(expectedLoadFailures = 1) {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        val (first, gate) = firstDedicatedHeld(h, starts, t0)
        stepTo(first.atMillis + 50)
        failApplyOf(h, gate)
        stepTo(first.atMillis + 10_000 - 1)
        assertEquals("두 번째는 아직", 1, starts.count { it.dedicated })
        step(1)
        val dedicated = starts.filter { it.dedicated }
        assertEquals(2, dedicated.size)
        assertEquals(first.atMillis + 10_000, dedicated[1].atMillis)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    // --- CAP-K28 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K28 (i): 전용 칸이 간격을 기다리는 동안 CALLER FE false를 gate로 시작 → 독립 FE 요구(H1′, 회전이 없어 그 false가 fence로
     * 버려지지 않는다)가 칸에 합류 → 먼저 시작한 false는 S1이라(G2가 직접 S1 답을 사실 근거에서 뺀다) 질의 후보로 보류한 뒤 QH의
     * S1 후보 결정으로 적용(G2: 사실대로 발행된 false) → 필요 해소(시작 순번이 새 demand보다 앞서 HEAD 정산 불가) → 일반 FE demand가
     * 남아 조회 정확히 1, reapprovalOnly = false. 잡을 변이: 같은 intent 합류를 재시도로 분류해 표시 유지(G3로 칸 비움).
     */
    @Test fun K28_independentSameIntentMerge_clearsTheFlag() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 2_000)
        assertTrue("premise: 필요", h.needed())
        val qGate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(qGate)
        val q = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        assertEquals(QueryOrigin.CALLER, starts.last().origin)
        h.source.next = { active }
        h.undecidedRejection()
        val before = starts.size
        val queryOrder = starts.last().order
        assertTrue(queryOrder <= checkNotNull(h.view().state.barrier))

        h.store.loadFailures = Int.MAX_VALUE
        qGate.complete(krxFalse)
        step(0)
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertTrue("후보 보류는 사실 근거가 아니다", h.needed())

        h.store.loadFailures = 0
        step(KrxHarnessDefaults.RETRY)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertEquals(queryOrder, h.view().reapproval.truthfulOrder)
        assertFalse("QH의 S1 후보 결정이 필요를 없앤다", h.needed())
        assertEquals("일반 FE demand는 남는다", RefreshIntent.FORCE_ENTITLEMENTS, h.coordinator.recheckDiagnostics().owedIntent)
        settleKrx()
        val after = starts.drop(before)
        assertEquals("조회 정확히 1", 1, after.size)
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, after.single().intent)
        assertFalse("합류한 칸은 재승인 전용이 아니다", after.single().reapprovalOnly)
        q.join()
    }

    /**
     * CAP-K28 (i)′ 인계 FE 합류의 즉시 일반 조회(r8, 사전 변이 B18 생존 보완): 전용 칸이 간격(t0 + 5000)을 기다리는 동안 새 KRX 거절(H1,
     * 회전 성공)의 인계 FE 요구가 칸에 독립 합류하면 칸은 재승인 전용이 아니므로 G6 간격 없이 그 시각에 일반 SCHEDULED FE가 시작한다.
     * 잡을 변이: 같은 intent 독립 합류를 재시도로 분류해 표시 유지(인계 FE가 발화 때 G6에 막혀 t0 + 5000 전용 조회로 밀림). 강화한 K28 (i)는
     * QH 결정 뒤 contest 후속 FE(AWAITING·demand 없음)가 합류 분류 오류를 가려 이 변이를 놓친다.
     */
    @Test fun K28_handoffFeMergingIntoTheSlot_startsAtOnceAsOrdinary() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 2_000)
        assertTrue("premise: 필요", h.needed())
        assertEquals("premise: 칸이 간격을 기다린다", RefreshIntent.FORCE_ENTITLEMENTS, h.coordinator.recheckDiagnostics().owedIntent)
        val before = starts.size
        h.source.next = { active }
        h.rejectKrx(h.issueGrant())
        step(0)
        val after = starts.drop(before)
        assertEquals("인계 FE가 그 시각에 시작한다", 1, after.size)
        assertEquals(QueryOrigin.SCHEDULED, after.single().origin)
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, after.single().intent)
        assertEquals(t0 + 2_000, after.single().atMillis)
        assertFalse("합류한 칸은 재승인 전용이 아니다", after.single().reapprovalOnly)
        settleKrx()
        assertEquals(0, starts.count { it.dedicated })
    }

    /**
     * CAP-K28 (ii) 반대 순서: seal 중 일반 FE demand(H1′, 5초 floor)가 서 있는 동안 해제·빚 소비로 재승인이 그 칸에 합류
     * (PAC:2435-2441) → 실제 PAC 조회의 reapprovalOnly = false, 조회 정확히 1, 전용 0. 잡을 변이: 재승인 합류를 재승인 전용으로 둠.
     */
    @Test fun K28_reapprovalMergingIntoOrdinaryDemand_isNotReapprovalOnly() = reapprovalTest {
        val h = on()
        val starts = h.recordStarts()
        val t0 = suppressedTrue(h)
        stepTo(t0 + 500)
        h.source.next = { active }
        h.undecidedRejection()
        assertTrue("premise: 일반 FE demand 무장", h.recheckSchedule().isArmed)
        val before = starts.size
        stepTo(t0 + 1_000)
        assertEquals("premise: 해제가 재승인을 보고", 1, h.reapprovals.size)
        settleKrx()
        val after = starts.drop(before)
        assertEquals("조회 정확히 1", 1, after.size)
        assertEquals(QueryOrigin.SCHEDULED, after.single().origin)
        assertFalse("재승인이 일반 칸에 합류 → 재승인 전용 아님", after.single().reapprovalOnly)
        assertEquals(0, starts.count { it.dedicated })
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    private companion object {
        val ON = KrxHarnessFactory { p ->
            PremiumAccessCoordinator(
                source = p.source,
                store = p.store,
                userPurger = p.purger,
                capabilityPurger = p.purger,
                scope = p.scope,
                clock = p.clock,
                jitter = ProbeJitter.None,
                persistenceRetryDelayMillis = KrxHarnessDefaults.RETRY,
                liveFence = { p.source.liveFence },
                onLossReapprovalScheduled = { intent, binding -> p.reapprovals += intent to binding },
                orders = p.orders,
                krxContest = KrxContestSwitch(true)
            )
        }
    }
}
