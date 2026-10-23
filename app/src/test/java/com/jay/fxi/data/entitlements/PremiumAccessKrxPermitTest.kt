package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1a 허가 판정·epoch 가드·막힘 계약 시험(스위치 켬) — 설계 r2 3.3·4절, D4 r6 3.2·3.3, CAP-R01~R03·R06·R07·R09·R10(PAC 부분),
 * I3·I7·I8, W1~W3 끝 자리(분류는 시험 r1 요청서 4.1), epoch 가드 두 위치, 첫 허가 전 경계, C0·C1 정리. KRX subscribe 단언은 2b-2(O-7).
 * reopenOwed 묶음(CAP-R08·R03 (iii)·R06 승인 닫힘·R10 (iv)·S1 reopenOwed 변형)은 요청서 4.2로 이월했다.
 * 'premise:'는 HEAD 경로 도달, 'premise(A1):'는 A1(설계 r2 3.2)에 기대는 전제다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessKrxPermitTest {
    private val processJob = SupervisorJob()
    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)
    private val pendingFalse = EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0)

    private fun permitTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    private fun TestScope.on(): KrxHarness = krxHarness(processJob, ON)
    private fun KrxHarness.view(): KrxContestView = checkNotNull(coordinator.krxContestView) { "스위치 켬이면 view가 있다" }
    private fun KrxHarness.phase(): KrxContestPhase = view().state.phase
    private fun KrxHarness.permit(): Long = view().state.permitSeq
    private suspend fun KrxHarness.owed(): RefreshIntent? = coordinator.recheckDiagnostics().owedIntent
    private fun KrxHarness.marker(permit: Long = permit()): KrxSendMarker = KrxSendMarker(store.record.krxCapabilityEpoch, permit)
    private fun KrxHarness.reserve(grant: TopicGrantToken, marker: KrxSendMarker?): TopicRejectionReservation =
        coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, marker)

    /** 상태 변화 0 비교용: view 전체, demand(순번·intent·reapprovalOnly), RS attempts·floor·revision, 후보 수, 송신 수, epoch, 그 grant의 latestCurrent. */
    private data class Full(
        val view: KrxContestView, val demand: String?, val attempts: Any?, val floor: Any?, val revision: Any?,
        val held: Int, val sends: Int, val epoch: String?, val latestCurrent: Long?
    )

    private suspend fun KrxHarness.full(grant: TopicGrantToken): Full {
        val rs = recheckSchedule()
        return Full(
            view(), recheckDemandSnapshot()?.toString(), rs.readPrivate("consecutiveAttempts"), rs.readPrivate("earliestAllowedAtMillis"),
            rs.readPrivate("revision"), coordinator.heldLossCandidateCount(), source.calls.size, store.record.krxCapabilityEpoch,
            coordinator.rejectionView(grant).latestCurrent
        )
    }

    private fun KrxHarness.ended(grant: TopicGrantToken, reservation: TopicRejectionReservation) =
        assertTrue("예약이 끝났다", reservation.order !in coordinator.rejectionView(grant).pendingOrders)

    /**
     * CAP-R01·R06 (i) H1′ 변형: 허가 p의 첫 거절이 live 판독 실패로 H1′(회전 없음) 인계되면 그 잠금 구간에서 rejectedPermit = p다. 같은 허가·같은
     * epoch 표지의 두 번째 거절은 상태를 바꾸지 않는다(I7·I8). 회전이 없으니 epoch 가드로는 걸러지지 않고 허가 판정만이 막는다.
     * 잡을 변이: 허가 판정 누락(두 번째가 회전·barrier·demand·RS를 바꿀 수 있다. FE 송신은 병합될 수 있어 송신 수만으로는 판별하지 않는다),
     * 인계 기록 누락·지연.
     */
    @Test fun P1_samePermitDuplicateAfterHandover_changesNothing() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        val r1 = h.reserve(grant, h.marker())
        val r2 = h.reserve(grant, h.marker())
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(r1)
        assertEquals("premise: H1′으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("premise: H1′은 회전하지 않는다", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("H1′ 인계가 그 잠금 구간에서 rejectedPermit = p로 기록됐다", p, h.view().rejectedPermit)
        val before = h.full(grant)

        h.coordinator.onTopicRejected(r2)
        assertEquals("같은 허가 중복은 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r2)

        settleKrx()
        assertEquals("FE는 정확히 1", before.sends + 1, h.source.calls.size)
        assertEquals("그 FE의 true(T2)에서 허가", p + 1, h.permit())
    }

    /**
     * CAP-R01 H1 변형: 같은 epoch·같은 허가 표지의 거절 둘 중 첫째가 결정 가능한 H1(회전)로 인계되면 둘째는 상태를 바꾸지 않는다. 이 변형에서는
     * epoch 가드와 허가 판정이 서로를 가리므로(D4 CAP-R01 잡을 변이 칸) 둘 다 뺀 변이만 판별한다: FE 1, n은 그 true의 T2에서 한 번.
     */
    @Test fun P1c_h1FirstThenSameEpochDuplicate_changesNothing() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        val r1 = h.reserve(grant, h.marker())
        val r2 = h.reserve(grant, h.marker())
        h.coordinator.onTopicRejected(r1)
        assertEquals("premise: H1으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertNotEquals("premise: H1이 KRX epoch를 회전했다", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("H1 인계 기록", p, h.view().rejectedPermit)
        val before = h.full(grant)
        h.coordinator.onTopicRejected(r2)
        assertEquals("같은 epoch·같은 허가 중복은 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r2)
        settleKrx()
        assertEquals("FE는 정확히 1", before.sends + 1, h.source.calls.size)
        assertEquals("그 FE의 true(T2)에서 허가", p + 1, h.permit())
        assertEquals("n은 한 번만", 1, h.view().state.streak)
    }

    /**
     * CAP-R01 두 순서(D4 r6:604): 같은 허가·같은 epoch 표지의 예약 둘을 예약 순번의 역순으로 처리해도(r2 먼저) 먼저 처리된 하나만 인계하고 다른
     * 하나는 상태를 바꾸지 않는다. H1′(live 실패)·H1(결정) 첫째 변형 각각, FE 1·n은 T2에서 한 번. 잡을 변이: 순번 비교로 중복을 판정.
     */
    @Test fun P1d_samePermitPairInReverseOrder_handsOverOnce() = permitTest {
        for (liveFailure in listOf(true, false)) {
            val label = if (liveFailure) "[H1′]" else "[H1]"
            val h = on()
            h.grantedKrxVisible()
            val p = h.permit()
            val grant = h.issueGrant()
            val r1 = h.reserve(grant, h.marker())
            val r2 = h.reserve(grant, h.marker())
            if (liveFailure) h.source.identityNulls = 1
            h.coordinator.onTopicRejected(r2)
            assertEquals("$label premise: 인계로 AWAITING", KrxContestPhase.AWAITING, h.phase())
            assertEquals("$label 늦은 순번이 먼저 인계해도 rejectedPermit = p", p, h.view().rejectedPermit)
            val before = h.full(grant)
            h.coordinator.onTopicRejected(r1)
            assertEquals("$label 앞 순번 예약은 중복이라 상태를 바꾸지 않는다", before, h.full(grant))
            h.ended(grant, r1)
            settleKrx()
            assertEquals("$label FE는 정확히 1", before.sends + 1, h.source.calls.size)
            assertEquals("$label 그 true(T2)에서 허가", p + 1, h.permit())
            assertEquals("$label n은 한 번만", 1, h.view().state.streak)
        }
    }

    /**
     * CAP-R06 store 실패 변형: 같은 허가 중복의 처리 때 store.load가 실패하도록 해 둬도 후보가 생기지 않고 그 load도 쓰이지 않는다 —
     * 허가 판정은 store.load 앞에서 메모리 상태로 한다(D4 3.3 배치). 잡을 변이: 판정을 store.load 뒤로(후보 1 생성).
     */
    @Test fun P1b_samePermitDuplicate_isDecidedBeforeTheStoreRead() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val r1 = h.reserve(grant, h.marker())
        val r2 = h.reserve(grant, h.marker())
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(r1)
        assertEquals("premise: H1′으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val before = h.full(grant)
        h.store.loadFailures = 1
        h.coordinator.onTopicRejected(r2)
        assertEquals("중복 판정은 store.load를 쓰지 않는다", 1, h.store.loadFailures)
        assertEquals("중복은 후보도 상태 변화도 만들지 않는다", before, h.full(grant))
        h.ended(grant, r2)
    }

    /**
     * CAP-R07 (v) epoch 가드: OPEN(p)에서 보낸 표지 (e1, p)의 거절이, FE false(C1)로 NONE이 되고 KRX가 e2로 회전한 뒤 도착한다.
     * p는 인계되지 않았으므로 허가 판정은 통과하지만 표지 epoch가 현재 확정 epoch와 달라 인계 없이 끝난다(상태 변화 0).
     * 잡을 변이: epoch 가드 누락(H1로 AWAITING·회전).
     */
    @Test fun P2_oldEpochMarkerAfterC1_isDiscardedWithoutHandover() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val sentMarker = h.marker()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: C1로 NONE", KrxContestPhase.NONE, h.phase())
        assertNotEquals("premise: false edge가 KRX를 회전했다", sentMarker.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        assertEquals("premise(A1): KRX 회전 뒤에도 같은 토큰", grant, h.issueGrant())
        val before = h.full(grant)

        val late = h.reserve(grant, sentMarker)
        h.coordinator.onTopicRejected(late)
        assertEquals("옛 epoch 표지는 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, late)
    }

    /**
     * epoch 가드 첫째 위치(설계 r2 3.3, 검토 X3): P2와 같은 입력에서 늦은 예약의 처리 때 store.load가 실패하도록 해 둔다. 첫째 위치(store.load
     * 앞, 메모리 lastConfirmed와 비교)가 버리므로 load를 쓰지 않고 후보도 생기지 않는다. 잡을 변이: 첫째 위치 누락(둘째만 — load 실패로
     * H1′ 후보·AWAITING).
     */
    @Test fun P2b_oldEpochMarker_isDiscardedBeforeTheStoreRead() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val sentMarker = h.marker()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: C1로 NONE", KrxContestPhase.NONE, h.phase())
        assertNotEquals("premise: false edge가 KRX를 회전했다", sentMarker.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        assertEquals("premise(A1): KRX 회전 뒤에도 같은 토큰", grant, h.issueGrant())
        val before = h.full(grant)
        h.store.loadFailures = 1
        val late = h.reserve(grant, sentMarker)
        h.coordinator.onTopicRejected(late)
        assertEquals("첫째 위치: store.load를 쓰지 않는다", 1, h.store.loadFailures)
        assertEquals("첫째 위치: 후보 없음", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("옛 epoch 표지는 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, late)
        h.store.loadFailures = 0
    }

    /**
     * CAP-R07 (iv) 표지 없음: 표지 없는(null) 거절은 처리 시점 permitSeq로 판정한다(fail-closed). p > 1인 인계되지 않은 허가에서는 정상 H1
     * 인계이고, 그 허가가 인계된 뒤의 같은 null 표지 중복은 상태를 바꾸지 않는다. 잡을 변이: 표지 없는 거절을 버림, 늘 허가 1로 해석,
     * 인계된 허가의 null 표지를 다시 인계.
     */
    @Test fun P3_markerlessRefusal_isJudgedWithTheCurrentPermit() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        h.rejectKrx(h.issueGrant())
        step(100)
        val p = h.permit()
        assertTrue("premise: 첫 바퀴로 p > 1", p > 1)
        assertEquals("premise: n = 1·OPEN", KrxContestPhase.OPEN, h.phase())
        val grant = h.issueGrant()
        h.coordinator.onTopicRejected(h.reserve(grant, null))
        assertEquals("CAP-R07 (iv): 표지 없는 첫 거절이 H1로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("CAP-R07 (iv): 그 허가의 인계", p, h.view().rejectedPermit)
        val before = h.full(grant)
        val dup = h.reserve(grant, null)
        h.coordinator.onTopicRejected(dup)
        assertEquals("인계된 허가의 null 표지 중복은 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, dup)
    }

    /**
     * 명시 표지의 null epoch: `KrxSendMarker(null, p)`는 '표지 없음'과 다르다. 현재 확정 epoch가 non-null이면 epoch 불일치로 인계 없이
     * 끝난다(설계 r2 3.3 R4). 잡을 변이: 명시 표지의 null epoch를 표지 없음처럼 처리(H1).
     */
    @Test fun P3b_explicitMarkerWithNullEpoch_isAnEpochMismatch() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        assertTrue("premise: 현재 확정 epoch가 있다", h.store.record.krxCapabilityEpoch != null)
        val before = h.full(grant)
        val r = h.reserve(grant, KrxSendMarker(null, h.permit()))
        h.coordinator.onTopicRejected(r)
        assertEquals("null epoch 명시 표지는 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r)
    }

    /**
     * CAP-R03 (iv) W1 → abandon → W3: H2(Pending(false))로 AWAITING(p 인계 안 됨) → 예약된 FE q 진행 중 적격 예약 r(order > q)이 보고됨
     * (아직 처리 안 됨) → q의 신선한 true는 막혀 허가 없이 withheld(W1) → r이 PAC 처리 없이 abandon → 끝난 예약 평가에서 W3: 그 FE의
     * 응답 전에 rejectedPermit = p, withheld 비움, 독립 FE 요구·송신 1 → 그 FE의 true에서 허가.
     * 잡을 변이: W1 없음(q로 바로 허가), abandon을 W3로 평가 안 함(정체), W3 인계 기록 누락·FE 완료까지 지연.
     */
    @Test fun P4_blockedTrueIsWithheld_andAnAbandonedBlockerFiresW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()

        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val r = h.reserve(grant, h.marker(p))
        assertTrue("premise: r은 q 뒤에 보고됐다", r.order > q)

        gate.complete(active)
        step(0)
        assertEquals("W1: 막힌 true는 허가를 내지 않는다", p, h.permit())
        assertEquals("W1: AWAITING 유지", KrxContestPhase.AWAITING, h.phase())
        assertEquals("W1: withheld 기록", KrxWithheld(q, listOf(r.order)), h.view().withheld)

        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(r)
        step(0)
        assertEquals("W3: 그 FE 응답 전에 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: 그 FE 응답 전에 withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        assertEquals("W3: FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())

        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * CAP-R03 (i) W1 → W2: P4와 같은 W1 상태에서 막던 예약 r이 PAC에서 처리돼 H1로 인계되면 그 잠금 구간에서 withheld가 비고
     * rejectedPermit = p다. FE는 그 인계의 것 하나뿐이고 그 true(T2)에서 허가. 잡을 변이: W2 없음(withheld 잔존), W2에서 따로 FE를 더 냄.
     */
    @Test fun P5_blockerHandedOverByH1_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val r = h.reserve(grant, h.marker(p))
        gate.complete(active)
        step(0)
        assertEquals("W1: withheld 기록", KrxWithheld(q, listOf(r.order)), h.view().withheld)

        val handoverFe = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(handoverFe)
        val sent = h.source.calls.size
        h.coordinator.onTopicRejected(r)
        assertNull("W2: H1 인계가 withheld를 비웠다", h.view().withheld)
        assertEquals("W2: 그 인계가 rejectedPermit = p", p, h.view().rejectedPermit)
        step(0)
        handoverFe.complete(active)
        step(0)
        assertEquals("FE는 인계의 것 하나뿐", sent + 1, h.source.calls.size)
        assertEquals("그 FE의 true(T2)에서 허가", p + 1, h.permit())
    }

    /** H2로 AWAITING(p 인계 안 됨) → 예약된 FE q 진행 중 적격 예약들 보고(처리 안 됨) → q의 true가 막혀 W1. 반환: (grant, q, 예약들). */
    private suspend fun TestScope.withheldBy(h: KrxHarness, blockers: Int): Triple<TopicGrantToken, Long, List<TopicRejectionReservation>> {
        val p = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val rs = List(blockers) { h.reserve(grant, h.marker(p)) }
        gate.complete(active)
        step(0)
        assertEquals("W1: withheld 기록", KrxWithheld(q, rs.map { it.order }), h.view().withheld)
        return Triple(grant, q, rs)
    }

    /**
     * W3 끝 자리 — 잠금 대기 취소(설계 r2 3.3, 검토 U2): 막던 예약의 PAC 처리 작업이 mutex를 기다리다 취소되면 PAC 처리도 abandon도 없이
     * `finally`의 complete만 돈다. 스위치 켬이면 끝난 예약 평가를 PAC scope에 맡겨 잠금 안에서 W3를 판정한다: P4와 같이 그 FE 응답 전에
     * rejectedPermit = p, withheld 비움, 독립 FE 요구·송신 1, 그 true에서 허가. mutex는 조회가 아닌 grant 발급(topicGrantResult가 잠금 안에서
     * store.load를 부른다)으로 붙잡아 demand·조회 순서와 섞이지 않게 한다. 잡을 변이: 잠금 대기 취소를 평가하지 않음(withheld 잔존, 인계 기록
     * 없음, FE 없음 — AWAITING 고착).
     */
    @Test fun P6_blockerCancelledWhileWaitingForTheLock_firesW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()

        val loadGate = CompletableDeferred<Unit>()
        h.store.loadGate = loadGate
        val holder = async(start = CoroutineStart.UNDISPATCHED) { h.coordinator.topicGrantResult() }
        assertNull("premise: 발급이 잠금 안 store.load에 들어갔다(gate 소비)", h.store.loadGate)
        assertTrue("premise: 발급이 그 load에서 멈춰 mutex를 붙잡고 있다", loadGate.isActive && holder.isActive)
        val rejection = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.onTopicRejected(r) }
        assertTrue("premise: 거절 처리가 mutex를 기다린다", rejection.isActive)
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        rejection.cancel()
        step(0)
        loadGate.complete(Unit)
        step(0)
        assertEquals("premise(A1): 발급은 같은 토큰을 돌려준다", grant, checkNotNull(holder.await().fence).grant)
        h.ended(grant, r)
        assertEquals("W3: 잠금 대기 취소도 평가해 그 FE 응답 전에 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        assertEquals("W3: FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())

        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * W3 끝 자리 — live 신원 불일치(설계 r2 3.3 PAC:1933): 막던 예약 r의 처리에서 live 신원이 grant 문맥과 다르면 결정 없이 잠금 안에서
     * 반환한다(인계 아님). 그 종료도 W3로 평가한다: P4와 같은 결과. 잡을 변이: 잠금 안 이른 반환을 W3로 평가하지 않음(AWAITING 고착).
     */
    @Test fun P8_blockerEndingOnALiveIdentityMismatch_firesW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        val bound = h.source.identity
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.coordinator.onTopicRejected(r)
        h.source.identity = bound
        h.ended(grant, r)
        step(0)
        assertEquals("W3: live 불일치 종료도 평가해 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        assertEquals("W3: FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())
        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * W2 — H1′ live 판독 실패(설계 r2 3.3, D4 W2): 막던 예약 r이 live 판독 실패로 H1′ 인계되면 그 잠금 구간에서 withheld가 비고
     * rejectedPermit = p다. FE는 그 인계의 demand 하나뿐(5초 floor 뒤)이고 그 true에서 허가. 잡을 변이: H1′ 인계에 W2 없음(withheld 잔존),
     * H1′와 W3를 함께 발화(FE 2).
     */
    @Test fun P5b_blockerHandedOverByH1Prime_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val handoverFe = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(handoverFe)
        val sent = h.source.calls.size
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(r)
        assertNull("W2: H1′ 인계가 withheld를 비웠다", h.view().withheld)
        assertEquals("W2: 그 인계가 rejectedPermit = p", p, h.view().rejectedPermit)
        step(5_010)
        handoverFe.complete(active)
        settleKrx()
        assertEquals("FE는 인계의 것 하나뿐", sent + 1, h.source.calls.size)
        assertEquals("그 FE의 true에서 허가", p + 1, h.permit())
    }

    /**
     * W2 — H1′ store.load 실패 후보(설계 r2 3.3, D4 W2·I9): 막던 예약 r의 처리에서 store.load가 실패하면 후보로 보류되며 그 잠금 구간에서
     * withheld가 비고 rejectedPermit = p, 후보 1이다. (r4) store 실패를 유지해 후보 라운드가 결정하지 못하게 둔 채 W3의 독립 FE 요구가 없음을 보고,
     * 복구 뒤에는 후보 결정의 FE 하나뿐임을 본다(후보 라운드 0은 보류 즉시 돌아 load가 되면 결정 FE를 낸다 — 그것은 W3가 아니다).
     * 잡을 변이: 후보 보류에 W2 없음(withheld 잔존), 후보와 W3를 함께 발화.
     */
    @Test fun P5c_blockerHeldAsACandidate_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val sent = h.source.calls.size
        val loads = h.store.loads
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r)
        assertTrue("premise: 처리가 store.load에 닿았다", h.store.loads > loads)
        assertEquals("H1′ 후보 1", 1, h.coordinator.heldLossCandidateCount())
        assertNull("W2: 후보 보류가 withheld를 비웠다", h.view().withheld)
        assertEquals("W2: 그 인계가 rejectedPermit = p", p, h.view().rejectedPermit)
        h.ended(grant, r)
        step(0)
        assertEquals("premise: store 실패로 후보가 남아 있다", 1, h.coordinator.heldLossCandidateCount())
        assertNull("W3가 함께 발화하지 않는다(독립 FE 요구 없음)", h.owed())
        assertEquals("즉시 FE 없음", sent, h.source.calls.size)
        h.store.loadFailures = 0
        h.source.next = { active }   // withheldBy가 남긴 Pending(false)를 지워 후보 결정의 FE가 true로 답하게 한다
        settleKrx()
        assertEquals("후보 복구 결정의 FE 하나뿐", sent + 1, h.source.calls.size)
        assertEquals("그 true에서 허가", p + 1, h.permit())
    }

    /**
     * W3 끝 자리 — grant 불일치(PAC:1882, 시험 r1 내부 검토 C-1): 같은 문맥의 RefusalReapproval이 W1보다 먼저 토큰을 T0→T1로 바꾼 뒤, 은퇴한 T0로
     * 보고된 KRX 거절 r2(현재 epoch·허가 p 표지)가 H2 뒤 FE q 진행 중 대기해 q의 true를 막는다(W1 — 설계 r2 3.3 적격 정의는 토큰이 아니라 키로
     * 판정한다. ledger.reserve는 grant를 검사하지 않는다, TopicRejectionLedger.kt:85-91). r2는 :1882에서 결정 없이 끝나므로 그 종료를 W3로
     * 평가한다. 은퇴 토큰 예약을 막는 예약으로 셀지는 요청서 4.1에서 확인을 구한다. 잡을 변이: grant 불일치 반환을 W3로 평가하지 않음(고착).
     */
    @Test fun P10_retiredTokenBlockerEndingOnGrantMismatch_firesW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val t0 = h.issueGrant()
        val key = h.view().key
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(t0, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: premium H1′은 FORCE_PREMIUM 요구", RefreshIntent.FORCE_PREMIUM, h.owed())
        assertEquals("premise: premium 거절은 contest를 건드리지 않는다", KrxContestPhase.OPEN, h.phase())
        step(5_010)
        val t1 = h.issueGrant()
        assertNotEquals("premise: RefusalReapproval이 새 토큰을 냈다", t0, t1)
        assertEquals("premise: contest 키 불변", key, h.view().key)
        assertEquals("premise: 허가 불변", p, h.permit())
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val r2 = h.reserve(t0, h.marker(p))
        assertTrue("premise: r2는 q 뒤에 보고됐다", r2.order > q)
        gate.complete(active)
        step(0)
        assertEquals("W1: 은퇴 토큰이어도 같은 키·적격이면 막는다", KrxWithheld(q, listOf(r2.order)), h.view().withheld)
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        h.coordinator.onTopicRejected(r2)
        h.ended(t0, r2)
        step(0)
        assertEquals("W3: grant 불일치 종료도 평가해 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        assertEquals("W3: FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())
        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * W3 끝 자리 — store.load 실패지만 이미 알려진 낡은 문맥(시험 r1 내부 검토 C-2, PAC holdUnreadableLossLocked:3103): live fence가 문맥과 다르면
     * (키 성분이 아님) 후보도 인계도 없이 끝난다. 그 종료도 W3로 평가한다. 잡을 변이: knownStale 반환을 W3로 평가하지 않음.
     */
    @Test fun P8b_blockerEndingOnAKnownStaleUnreadableRecord_firesW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        h.source.liveFence = AuthIdentityFence(KrxHarnessDefaults.OWNER, 2L)
        h.store.loadFailures = 1
        h.coordinator.onTopicRejected(r)
        h.source.liveFence = null
        assertEquals("premise: load 실패가 소비됐다", 0, h.store.loadFailures)
        assertEquals("premise: 알려진 낡은 문맥이라 후보 없음", 0, h.coordinator.heldLossCandidateCount())
        h.ended(grant, r)
        step(0)
        assertEquals("W3: knownStale 종료도 평가해 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
    }

    /**
     * W3의 `recheckDemand == null` 조건(설계 r2 3.3, D4 r6 CAP-R03 잡을 변이 'demand가 있는데 중복 후속'): W1 뒤 premium 거절의 live 판독 실패로
     * FORCE_PREMIUM demand가 선 상태에서 막던 예약이 abandon되면 W3는 없다 — 인계 기록 없음, withheld 유지(W2·W3·허가·C1이 아무것도 일어나지
     * 않았다), 독립 FE 0. 그 demand의 조회(5초 floor) 하나가 T2로 허가하고 withheld를 지운다. 잡을 변이: demand 조건을 보지 않고 W3 발화.
     */
    @Test fun P12_noW3WhileADemandStands() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, q, rs) = withheldBy(h, blockers = 1)
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: FORCE_PREMIUM demand", RefreshIntent.FORCE_PREMIUM, h.owed())
        assertEquals("premium 거절은 withheld를 바꾸지 않는다", q, h.view().withheld?.fromOrder)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertEquals("demand가 있으면 W3 없음: 송신 그대로", sent, h.source.calls.size)
        assertTrue("W3 없음: 인계 기록 없음", h.view().rejectedPermit < p)
        assertEquals("W3 없음: withheld 유지", q, h.view().withheld?.fromOrder)
        h.source.next = { active }
        step(5_010)
        settleKrx()
        assertEquals("그 demand의 조회 하나뿐", sent + 1, h.source.calls.size)
        assertEquals("그 true(T2)에서 허가", p + 1, h.permit())
        assertNull("허가가 withheld를 지운다", h.view().withheld)
    }

    /**
     * 허가 발급·T2가 withheld를 지운다(D4 r6:181, CAP-R09 잡을 변이 '허가 발급이 withheld를 지우지 않음'): W1 뒤 막던 예약보다 늦게 시작한 CALLER FE의
     * 신선한 true는 막히지 않으므로 T2로 적용되고 withheld를 지운다. (가) n=0 → OPEN(p+1), 막던 예약은 앞 허가가 되어 상태 변화 0·FE 0(I8).
     * (나) 첫 바퀴 뒤(n=1) → T2로 HOLD(허가 그대로) 진입도 withheld를 지운다. 잡을 변이: T2·허가가 withheld를 남김.
     */
    @Test fun P11_unblockedFreshTrueClearsWithheld_open() = permitTest { unblockedTrue(preRound = false) }

    @Test fun P11b_unblockedFreshTrueClearsWithheld_holdEntry() = permitTest { unblockedTrue(preRound = true) }

    private suspend fun TestScope.unblockedTrue(preRound: Boolean) {
        val h = on()
        h.grantedKrxVisible()
        if (preRound) {
            h.rejectKrx(h.issueGrant())
            step(100)
            assertEquals("premise: 첫 바퀴 n=1·OPEN", KrxContestPhase.OPEN, h.phase())
        }
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        var spins = 0
        while (h.view().withheld != null) {   // CALLER가 floor에 미뤄지면 그 발화까지(HOLD 만료 전에 멈춘다)
            step(100)
            spins += 1
            assertTrue("CALLER FE의 true가 60초 안에 적용돼 withheld를 지운다", spins <= 600)
        }
        assertNull("T2가 withheld를 지운다", h.view().withheld)
        if (preRound) {
            assertEquals("(나) T2로 HOLD", KrxContestPhase.HOLD, h.phase())
            assertEquals("(나) HOLD는 허가를 내지 않는다", p, h.permit())
        } else {
            assertEquals("(가) T2로 OPEN(p+1)", p + 1, h.permit())
            val before = h.full(grant)
            h.coordinator.onTopicRejected(r)
            assertEquals("(가) 막던 예약은 앞 허가가 되어 상태 변화 0", before, h.full(grant))
            h.ended(grant, r)
        }
    }

    /**
     * W3 복수 blocker: 막던 예약 둘 중 첫째가 abandon돼도 둘째가 남아 있으면 W3는 없다(withheld 유지, FE 0). 둘째도 abandon되면 그때 W3.
     * 잡을 변이: 마지막 blocker가 아니어도 W3(FE 조기·중복).
     */
    @Test fun P7_onlyTheLastBlockerEndingFiresW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (_, q, rs) = withheldBy(h, blockers = 2)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(rs[0])
        step(0)
        assertEquals("첫째 blocker 종료: W3 없음", sent, h.source.calls.size)
        assertEquals("첫째 blocker 종료: 인계 없음", 0L, h.view().rejectedPermit)
        assertEquals("withheld는 남는다(목록은 W1 시점 기록 — D4 r6 3.2 withheldFrom)", q, h.view().withheld?.fromOrder)

        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        h.coordinator.abandonRejection(rs[1])
        step(0)
        assertEquals("마지막 blocker 종료: W3 FE 1", sent + 1, h.source.calls.size)
        assertEquals("W3 인계", p, h.view().rejectedPermit)
        followUp.complete(active)
        step(0)
        assertEquals(p + 1, h.permit())
    }

    /**
     * I7: H1 인계(회전)가 진행되는 동안 처리 작업이 취소돼도, 결정이 게시된 뒤의 취소는 인계를 되돌리지 않는다 — rejectedPermit = p는 그
     * 인계와 같은 잠금 구간에서 쓰였고 남는다. 그래서 뒤이은 같은 허가의 표지 없는 예약(처리 시점 permit p로 판정, epoch 가드 없음)은
     * 상태를 바꾸지 않는다. 잡을 변이: 취소 때 rejectedPermit을 되돌림(두 번째 인계), 인계 기록을 게시 뒤 비취소 구간 밖에 둠.
     * 게시 뒤 취소에서 H1의 FE 요구가 남는지는 P9b(요청서 4.x 결정 대기)가 따로 본다.
     */
    @Test fun P9_cancellationAfterPublishingDoesNotUndoTheHandover() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r1 = h.reserve(grant, h.marker())
        val rotationGate = CompletableDeferred<Unit>()
        h.store.rotationGate = rotationGate
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.onTopicRejected(r1) }
        assertNull("premise: H1 결정이 회전 쓰기에 들어갔다(gate 소비)", h.store.rotationGate)
        assertTrue("premise: 처리 작업이 회전 쓰기에서 멈췄다", job.isActive)
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)   // (r4) 취소 보호된 H1 후속 FE가 있으면 응답 대기로 붙잡아 AWAITING을 관측한다
        job.cancel()
        rotationGate.complete(Unit)
        step(0)
        assertTrue("premise: 게시 뒤 취소가 처리 작업을 끝냈다", job.isCancelled)
        assertEquals("premise: H1이 게시됐다(AWAITING)", KrxContestPhase.AWAITING, h.phase())
        h.ended(grant, r1)
        assertEquals("I7: 게시 뒤 취소가 인계 기록을 되돌리지 않는다", p, h.view().rejectedPermit)

        val before = h.full(grant)
        val dup = h.reserve(grant, null)
        h.coordinator.onTopicRejected(dup)
        assertEquals("I7: 같은 허가의 표지 없는 예약은 두 번째 인계가 아니다", before, h.full(grant))
        h.ended(grant, dup)
    }

    /**
     * I9(D4 r6:277) — 게시 뒤 취소에서도 H1이 남긴 후속 의무: HEAD는 게시 뒤 `ensureActive`(PAC:2379)가 던지면 onTopicRejected의 demand
     * 단계(PAC:1939-1946)를 건너뛴다. 꺼짐에서는 다음 같은 허가 거절이 다시 H1이 되어 메웠지만, 켬에서는 허가 판정이 그 길을 막으므로
     * AWAITING이 FE 없이 남는다(I9 위반). 이 시험은 '켬에서는 H1의 FE 요구도 게시 뒤 비취소 구간(onPublished)에서 세운다'는 의무를
     * 단언한다 — 설계 r2·D4 r6에 명시되지 않은 의무라 요청서 4.x에서 개정 여부를 묻는다(합의 전까지 계약 후보).
     * 잡을 변이: H1 demand를 게시 뒤 취소 가능 구간에 둠(FE 0, AWAITING 고착).
     */
    @Test fun P9b_cancellationAfterPublishingKeepsTheH1FollowUp() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r1 = h.reserve(grant, h.marker())
        val rotationGate = CompletableDeferred<Unit>()
        h.store.rotationGate = rotationGate
        val sent = h.source.calls.size
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.onTopicRejected(r1) }
        assertNull("premise: H1 결정이 회전 쓰기에 들어갔다(gate 소비)", h.store.rotationGate)
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)   // (r4) 후속 FE를 응답 대기로 붙잡아 게시 직후 상태와 송신을 따로 본다
        job.cancel()
        rotationGate.complete(Unit)
        step(0)
        assertTrue("premise: 게시 뒤 취소가 처리 작업을 끝냈다", job.isCancelled)
        assertEquals("premise: H1이 게시됐다(AWAITING)", KrxContestPhase.AWAITING, h.phase())
        settleKrx()
        assertEquals("I9: 게시 뒤 취소에도 H1의 FE가 정확히 1 나간다(응답 대기 중)", sent + 1, h.source.calls.size)
        followUp.complete(active)
        settleKrx()
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
        assertEquals("그 FE의 true에서 허가 — AWAITING 고착 없음", p + 1, h.permit())
    }

    private enum class FirstHandover { LIVE_FAILURE, STORE_FAILURE }
    private enum class DuplicateAt { BEFORE_FE, DURING_FE, AFTER_FE }
    private enum class Branch { DECIDABLE, LIVE_FAILURE, STORE_FAILURE }

    /**
     * CAP-R06 (PAC 부분): 허가 p의 첫 예약이 H1′로 인계된다(live 판독 실패, store.load 실패 후보). 같은 허가 표지의 지연 예약 N건(N=1·3)을
     * 사전 바퀴 없음/있음으로 FE true 뒤 T2 OPEN(p+1)/HOLD(p)를 각각 만든다. 각 예약의 처리 분기를 결정 가능·live 실패·store 실패로 바꿔 가며
     * (i) 첫 FE 발사 전 (ii) FE 진행 중 (iii) FE 응답 뒤에
     * 처리한다. 기대: entitlements 송신 정확히 1, 진행 중 FE 취소 0(그 FE의 true가 허가), LossCandidate ≤ 1, n은 T2에서만 +1, 중복 처리 전후
     * 상태(view·demand·RS·후보·송신·epoch) 같음, rejectedPermit = p, 중복 처리는 분기 주입(live·store 실패)을 소비하지 않는다(판정이
     * store.load·live 판독 앞). 승인 닫힘 분기는 reopenOwed 경로(CAP-R08)와 함께 따로 둔다. KRX subscribe 단언은 2b-2(O-7).
     * 잡을 변이: 중복이 raiseDemandLocked·armRecheckLocked를 부름, 중복이 enterAwaiting, 판정을 store.load 뒤에 둠(예약마다 후보),
     * 인계를 epoch로만 판정(H1′ 미회전 통과).
     */
    @Test fun R06_samePermitDuplicates_matrix() = permitTest {
        for (preRound in listOf(false, true))
            for (first in FirstHandover.values())
                for (n in listOf(1, 3))
                    for (at in DuplicateAt.values()) {
                        r06(first, n, at, preRound)
                    }
    }

    private suspend fun TestScope.r06(first: FirstHandover, n: Int, at: DuplicateAt, preRound: Boolean) {
        val label = "[$first n=$n $at preRound=$preRound]"
        val h = on()
        h.grantedKrxVisible()
        if (preRound) {
            h.rejectKrx(h.issueGrant())
            step(100)
            assertEquals("$label premise: 첫 바퀴 OPEN", KrxContestPhase.OPEN, h.phase())
            assertEquals("$label premise: 첫 바퀴 n=1", 1, h.view().state.streak)
        }
        val p = h.permit()
        val grant = h.issueGrant()
        val r1 = h.reserve(grant, h.marker())
        val dups = List(n) { h.reserve(grant, h.marker()) }
        when (first) {
            FirstHandover.LIVE_FAILURE -> h.source.identityNulls = 1
            FirstHandover.STORE_FAILURE -> h.store.loadFailures = 1
        }
        h.coordinator.onTopicRejected(r1)
        assertEquals("$label premise: H1′으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("$label H1′ 인계 기록", p, h.view().rejectedPermit)
        val sends0 = h.source.calls.size
        val fe = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(fe)

        suspend fun processDuplicates() {
            dups.forEachIndexed { i, dup ->
                val branch = Branch.values()[i % Branch.values().size]
                when (branch) {
                    Branch.DECIDABLE -> Unit
                    Branch.LIVE_FAILURE -> h.source.identityNulls = 1
                    Branch.STORE_FAILURE -> h.store.loadFailures = 1
                }
                val before = h.full(grant)
                h.coordinator.onTopicRejected(dup)
                assertEquals("$label 중복 $i($branch)은 상태를 바꾸지 않는다", before, h.full(grant))
                h.ended(grant, dup)
                when (branch) {
                    Branch.DECIDABLE -> Unit
                    Branch.LIVE_FAILURE -> {
                        assertEquals("$label 중복 $i: live 판독을 쓰지 않는다", 1, h.source.identityNulls)
                        h.source.identityNulls = 0
                    }
                    Branch.STORE_FAILURE -> {
                        assertEquals("$label 중복 $i: store.load를 쓰지 않는다", 1, h.store.loadFailures)
                        h.store.loadFailures = 0
                    }
                }
                assertTrue("$label LossCandidate ≤ 1", h.coordinator.heldLossCandidateCount() <= 1)
            }
        }

        suspend fun awaitFeInFlight() {
            var spins = 0
            while (h.source.calls.size == sends0) {
                step(100)
                spins += 1
                assertTrue("$label premise: 인계의 FE가 60초 안에 나간다", spins <= 600)
            }
            assertEquals("$label premise: FE 1이 진행 중", sends0 + 1, h.source.calls.size)
        }

        if (at == DuplicateAt.BEFORE_FE) processDuplicates()
        awaitFeInFlight()
        if (at == DuplicateAt.DURING_FE) processDuplicates()
        fe.complete(active)
        step(0)
        val afterFe = h.view().state
        assertEquals("$label T2의 phase",
            if (preRound) KrxContestPhase.HOLD else KrxContestPhase.OPEN, h.phase())
        assertEquals("$label T2의 허가", if (preRound) p else p + 1, h.permit())
        assertEquals("$label n은 T2에서 한 번만 증가", if (preRound) 2 else 1, afterFe.streak)
        assertEquals("$label 첫 인계 기록 유지", p, h.view().rejectedPermit)
        if (at == DuplicateAt.AFTER_FE) processDuplicates()
        assertEquals("$label 중복은 T2 결과를 바꾸지 않는다", afterFe, h.view().state)
        if (preRound) {
            val deadline = checkNotNull(afterFe.holdUntilMillis)
            val remaining = deadline - testScheduler.currentTime
            assertTrue("$label premise: HOLD 만료 전", remaining > 0)
            step(remaining - 1)
            assertEquals("$label HOLD·holdUntil·n·허가 유지", afterFe, h.view().state)
            assertEquals("$label 만료 전 추가 송신 없음", sends0 + 1, h.source.calls.size)
            step(1)
            assertEquals("$label 만료 뒤 OPEN", KrxContestPhase.OPEN, h.phase())
            assertEquals("$label 만료는 n을 올리지 않는다", 2, h.view().state.streak)
        } else {
            settleKrx()
        }
        assertEquals("$label entitlements 송신 정확히 1", sends0 + 1, h.source.calls.size)
        assertEquals("$label 최종 허가", p + 1, h.permit())
    }

    // --- C0·C1 정리, 첫 허가 전 경계, epoch 가드 둘째 위치 (설계 r2 3.3) -------------------------------------------------

    /**
     * C1: W1 상태에서 신선한 StableActive(false)(false edge)가 오면 NONE으로 가며 withheld를 비운다. 그 뒤 막던 예약이 abandon돼도 W3는
     * 없다(AWAITING이 아니다): FE 0, 인계 없음. 잡을 변이: C1이 withheld를 남김, NONE에서 W3 발화.
     */
    @Test fun C1_freshFalseClearsWithheld_andALaterEndFiresNoW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        step(5_010)
        assertEquals("premise: C1로 NONE", KrxContestPhase.NONE, h.phase())
        assertNull("C1: withheld 비움", h.view().withheld)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertEquals("NONE에서 끝난 blocker는 W3가 아니다: FE 0", sent, h.source.calls.size)
        assertNull("요구 없음", h.owed())
        assertTrue("인계 없음", h.view().rejectedPermit < p)
    }

    /**
     * C0: W1 상태에서 신원(키)이 바뀌면 withheld를 비운다. 옛 키의 막던 예약이 뒤에 끝나도 W3는 없다. 이 입력열에서는 새 키의 첫 답이
     * 허가(T1)를 내 허가 변경 정리가 withheld를 비우므로 C0 정리 누락을 따로 잡지 못한다(사전 변이 C17_withheld가 이 시험을 통과해 생존) —
     * C0 정리 자체는 C0b~C0f가 잡는다. 이 시험은 허가가 난 경로의 회귀 잠금이다.
     */
    @Test fun C0_keyChangeClearsWithheld_andAnOldKeyEndFiresNoW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        val keyBefore = h.view().key
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OTHER, 1L)).applied()
        h.source.next = { active }   // withheldBy가 남긴 Pending(false)를 지워 K03과 같은 경로로
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        assertTrue("premise: 키가 바뀌었다(신원 처리의 지연 C0)", h.view().key != keyBefore)
        assertNull("C0: withheld 비움", h.view().withheld)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertEquals("옛 키 blocker 종료는 W3가 아니다: FE 0", sent, h.source.calls.size)
        assertNull("C0 뒤에도 withheld 없음", h.view().withheld)
    }

    /**
     * C0 허가 없는 분기(D4 r7 C0 행 'withheld=null'·withheldFrom '허가 여부 무관', A2-3 'withheld는 AWAITING의 W1에서만 생긴다'): W1 상태에서
     * 신원(키)이 바뀌면 신원 처리 끝의 지연 C0 비교(ensureDemandArmedLocked→resumeKrxReopenLocked→syncContestKeyLocked)가 KRX 숨김·premium
     * 닫힘이라 허가 없이 NONE으로 두고, 새 키의 첫 답 Pending(false)이 허가 없이 NONE에서 AWAITING으로 옮긴다. 그 AWAITING에 옛 withheld가
     * 남아 있으면 안 된다. 옛 키의 막던 예약이 끝나도 W3는 없다(뒤쪽 단언은 회귀 잠금 — Pending demand가 W3를 막아 이 시험 안에서 고유
     * 판별력은 없다). 위 C0 시험은 새 키의 첫 답이 허가(T1)를 내 허가 변경 정리가 C0 정리를 가렸다(사전 변이 C17_withheld 생존). 잡을 변이:
     * C0가 withheld를 남김.
     */
    @Test fun C0b_keyChangeWithoutAPermit_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        val p = h.permit()
        val keyBefore = h.view().key
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OTHER, 1L)).applied()
        // 재조회 하한을 길게 두어 아래 step(0)의 송신 수가 W3만 재게 한다(재시도 0초면 새 키의 Pending 재조회가 같은 step에서 나간다).
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 600) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertTrue("premise: 키가 바뀌었다(신원 처리의 지연 C0)", h.view().key != keyBefore)
        assertEquals("premise: 허가 없이 AWAITING(새 키의 Pending(false))", KrxContestPhase.AWAITING, h.phase())
        assertEquals("premise: 허가 불변(C0 허가 없는 분기)", p, h.permit())
        assertNull("C0: 허가 없는 분기에서도 withheld 비움", h.view().withheld)
        val sent = h.source.calls.size
        val demandBefore = h.recheckDemandSnapshot()?.toString()
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertEquals("옛 키 blocker 종료는 W3가 아니다: 송신 0", sent, h.source.calls.size)
        assertEquals("W3의 독립 FE 요구 없음(600초 예약에 접혀도 순번이 바뀐다)", demandBefore, h.recheckDemandSnapshot()?.toString())
        assertTrue("인계 없음", h.view().rejectedPermit < p)
        assertNull("끝난 뒤에도 withheld 없음", h.view().withheld)
    }

    /**
     * C0 허가 없는 분기 — 신원이 아닌 키 성분(D4 r7 C0 행 `key ≠ 현재 키`, withheldFrom 'C0(허가 여부 무관)'): W1 상태에서 권위적 상실
     * (StableInactive)이 오면 decideLocked가 decisionGeneration을 올리고 사용자 축을 회전한다(binding·identity 그대로). 게시의 C0는 KRX 숨김·
     * premium 닫힘이라 허가 없이 NONE이고, 그 답은 0a Answer에 들어가지 않아 T2·C1·허가 변경 정리가 없다 — withheld를 비우는 것은 C0뿐이다.
     * 잡을 변이: C0의 withheld 정리를 신원·binding 변경에만 둠.
     */
    @Test fun C0c_authoritativeLossKeyChange_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        val p = h.permit()
        val keyBefore = checkNotNull(h.view().key)
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val key = checkNotNull(h.view().key)
        assertEquals("premise: binding 그대로(신원 사건 아님)", keyBefore.binding, key.binding)
        assertEquals("premise: identity 그대로(신원 사건 아님)", keyBefore.identity, key.identity)
        assertTrue("premise: 권위적 상실이 decisionGeneration을 바꿨다", keyBefore.decisionGeneration != key.decisionGeneration)
        assertEquals("premise: C0 허가 없는 분기로 NONE", KrxContestPhase.NONE, h.phase())
        assertEquals("premise: 허가 불변", p, h.permit())
        assertNull("C0(decisionGeneration): withheld 비움", h.view().withheld)
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertTrue("인계 없음", h.view().rejectedPermit < p)
        assertNull("끝난 뒤에도 withheld 없음", h.view().withheld)
    }

    /**
     * C0 허가 없는 분기 — userAccessEpoch 단독(K03 (iii)와 같은 합성: 기록의 사용자 축만 회전, binding·identity·generation 그대로). W1에서는
     * q의 true로 KRX가 다시 보이므로(그대로 키를 바꾸면 C0가 허가를 내 허가 변경 정리가 가린다) 먼저 Pending(false)로 KRX를 숨긴다 — PENDING_FALSE는
     * withheld를 지우는 사건(T2·C1·허가·C0·W2·W3)이 아니라 AWAITING·withheld가 그대로다. 그 뒤 사용자 축만 회전하면 다음 조회의 지연 C0가
     * 허가 없이 NONE으로 두고 그 답 Pending(false)이 AWAITING으로 옮긴다. 그 AWAITING에 옛 withheld가 남으면 안 된다. 잡을 변이: C0의 withheld
     * 정리를 신원·binding·generation 변경에만 둠(사용자 epoch 누락).
     */
    @Test fun C0d_userAccessEpochOnlyKeyChange_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val (_, q, rs) = withheldBy(h, blockers = 1)
        val p = h.permit()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 600) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertTrue("premise: raw _krx 숨김(Pending(false)의 false edge — NOT_GRANTED)", TopicAccessBlock.NOT_GRANTED in h.coordinator.accessSnapshot.facts.capabilityBlocks)
        assertEquals("premise: AWAITING 그대로", KrxContestPhase.AWAITING, h.phase())
        assertEquals("premise: 창 안 — withheld가 아직 있다", KrxWithheld(q, rs.map { it.order }), h.view().withheld)
        val keyBefore = checkNotNull(h.view().key)
        // P15와 같은 합성: 기록의 사용자 축만 회전하고 grant 발급의 load로 확정한다(확정 전 조회는 fence 불일치로 버려진다, PAC apply).
        h.store.record = AccessEpochTransitions.rotate(
            h.store.record, rotateUser = true, rotateKrx = false, ids = EpochIdGenerator { "external-user-0" }
        )
        h.issueGrant()
        assertEquals("premise: 저장된 contest 키는 아직 옛 키(지연 C0 대기)", keyBefore, h.view().key)
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 600) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val key = checkNotNull(h.view().key)
        assertEquals("premise: binding 그대로", keyBefore.binding, key.binding)
        assertEquals("premise: identity 그대로", keyBefore.identity, key.identity)
        assertEquals("premise: generation 그대로", keyBefore.decisionGeneration, key.decisionGeneration)
        assertTrue("premise: userAccessEpoch만 바뀐 키", key.userAccessEpoch != keyBefore.userAccessEpoch)
        // 이 입력열 끝의 phase(구현 r2에서는 NONE)는 단언하지 않는다 — 계약 대상은 허가 없는 C0의 withheld 정리다. W1 말고는 withheld를 만드는
        // 사건이 없고 W1은 AWAITING의 신선한 true가 필요하므로, 어느 phase든 남은 withheld는 C0 정리 누락이다.
        assertNotEquals("premise: C0 허가 없는 분기(OPEN 아님)", KrxContestPhase.OPEN, h.phase())
        assertEquals("premise: 허가 불변", p, h.permit())
        assertNull("C0(userAccessEpoch): withheld 비움", h.view().withheld)
    }

    /**
     * C0 허가 없는 분기 — decisionGeneration 단독(권위적 상실의 회전이 실패해 사용자 epoch는 그대로, seal 보류): 단언은 refresh 직후(양보
     * 전)에만 둔다 — 뒤의 복구 라운드가 회전을 착지시키면 userAccessEpoch가 다시 바뀐다. 잡을 변이: C0 withheld 정리에서 generation 성분 누락.
     */
    @Test fun C0e_generationOnlyKeyChange_clearsWithheld() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        withheldBy(h, blockers = 1)
        val p = h.permit()
        val keyBefore = checkNotNull(h.view().key)
        h.store.rotationFailures = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val key = checkNotNull(h.view().key)
        assertEquals("premise: binding 그대로", keyBefore.binding, key.binding)
        assertEquals("premise: identity 그대로", keyBefore.identity, key.identity)
        assertEquals("premise: userAccessEpoch 그대로(회전 미착지)", keyBefore.userAccessEpoch, key.userAccessEpoch)
        assertTrue("premise: decisionGeneration만 바뀐 키", keyBefore.decisionGeneration != key.decisionGeneration)
        assertEquals("premise: 허가 불변", p, h.permit())
        assertNull("C0(decisionGeneration 단독): withheld 비움", h.view().withheld)
    }

    /**
     * C0 정리의 행동 결과(회귀 잠금, 내부 적대 검토 F1): W1 뒤 권위적 상실의 회전이 계속 실패하면 USER seal이 서고 decisionGeneration만 바뀐
     * C0(허가 없이 NONE)가 된다. Pending(false)가 허가 없이 AWAITING으로 옮기고 FORCE_PREMIUM 재조회 요구를 세운다. 그 재조회의 신선한 true는
     * seal로 premium 게시가 억제돼 T2·W1 분기를 지나치고 0a는 RUNTIME_CLOSED로 무시하지만, 요구는 정산된다 — AWAITING·요구 없음·인계 없음이 남는다.
     * 이 창에서 옛 키의 막던 예약이 끝나도 W3(rejectedPermit 소비·독립 FE)는 없다. 잡을 변이: C0가 withheld를 남김(옛 withheld로 W3 발화).
     */
    @Test fun C0f_sealSuppressedTrueAfterKeyChange_oldBlockerEndFiresNoW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (_, _, rs) = withheldBy(h, blockers = 1)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settleKrx()
        assertEquals("premise: C0(generation)로 허가 없이 NONE", KrxContestPhase.NONE, h.phase())
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 600) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        assertEquals("premise: 허가 없이 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("premise: 허가 불변", p, h.permit())
        assertEquals("premise: Pending 재조회 요구", RefreshIntent.FORCE_PREMIUM, h.owed())
        h.source.next = { active }
        settleKrx()
        assertNotEquals("premise: USER seal로 premium 게시 억제", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("premise: RUNTIME_CLOSED로 AWAITING 유지", KrxContestPhase.AWAITING, h.phase())
        assertNull("premise: 억제된 true가 요구를 정산했다(창 안)", h.owed())
        assertTrue("premise: 아직 인계 없음(창 안)", h.view().rejectedPermit < p)
        val sent = h.source.calls.size
        h.coordinator.abandonRejection(rs.single())
        step(0)
        assertTrue("옛 키 blocker 종료는 W3가 아니다: 인계 없음", h.view().rejectedPermit < p)
        assertNull("W3의 독립 FE 요구 없음", h.owed())
        assertEquals("W3의 FE 송신 없음", sent, h.source.calls.size)
    }

    /** C0는 rejectedPermit을 유지한다(설계 r2 3.3 C0·C1). H1 인계(rejectedPermit = p) 뒤 신원 변경과 새 키의 첫 게시. 잡을 변이: C0가 rejectedPermit을 지움. */
    @Test fun C0_keepsRejectedPermit() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        h.rejectKrx(h.issueGrant())
        assertEquals("premise: H1으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("H1 인계 기록", p, h.view().rejectedPermit)
        val keyBefore = h.view().key
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OTHER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        assertTrue("premise: 키가 바뀌었다(신원 처리의 지연 C0)", h.view().key != keyBefore)
        assertEquals("C0: rejectedPermit 유지", p, h.view().rejectedPermit)
    }

    /**
     * 첫 허가 전 경계(설계 r2 3.3, 검토 U5·O-3): 켬, premium 확정·KRX 숨김(StableActive(false))으로 NONE·permitSeq 0인 상태에서 KRX 거절(표지
     * 없음 — 허가가 없으면 KRX 송신도 없다)은 `rejectedPermit(0) < permitSeq(0)`이 거짓이라 부적격이다: 상태 변화 0, HEAD의
     * KrxEntitlementRequired 결정·회전 없음. 잡을 변이: 경계 반전(permit 0을 인계 가능으로 봄 — AWAITING·결정).
     */
    @Test fun B0_beforeTheFirstPermit_everyKrxRefusalIsIneligible() = permitTest {
        val h = on()
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        assertEquals("premise: premium 확정", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("premise: NONE", KrxContestPhase.NONE, h.phase())
        assertEquals("premise: 허가 0", 0L, h.permit())
        val grant = h.issueGrant()
        val before = h.full(grant)
        val rotations = h.store.rotationAttempts
        val r = h.reserve(grant, null)
        h.coordinator.onTopicRejected(r)
        assertEquals("첫 허가 전 KRX 거절은 상태를 바꾸지 않는다", before, h.full(grant))
        assertEquals("결정·회전 없음", rotations, h.store.rotationAttempts)
        h.ended(grant, r)
    }

    /**
     * epoch 가드 둘째 위치(설계 r2 3.3 X3): 메모리의 확정 epoch(lastConfirmed)는 표지와 같지만 store.load로 본 기록의 epoch가 다르면 그 기록
     * epoch와 다시 비교해 인계 없이 끝낸다. 기록 epoch만 바꾼 합성 상태로 만든다(운영 도달 경로는 확인 안 함 — 회전 결과 불명 뒤 착지 등).
     * stub에서는 A1-3 미투영(PAC:1906)이 먼저 버려 통과한다. 참조 구현(A1-3 투영)에서 판별하는 변이: 둘째 위치 누락(인계·회전).
     */
    @Test fun G2_recordEpochMovedPastTheMarker_isDiscarded() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val marker = h.marker()
        h.store.record = h.store.record.copy(krxCapabilityEpoch = "epoch-external")
        val before = h.full(grant)
        val rotations = h.store.rotationAttempts
        val r = h.reserve(grant, marker)
        h.coordinator.onTopicRejected(r)
        assertEquals("기록 epoch가 표지와 다르면 인계 없이 끝난다", before, h.full(grant))
        assertEquals("결정·회전 없음", rotations, h.store.rotationAttempts)
        h.ended(grant, r)
    }

    // --- 후보 복구 허가 재판정 (설계 r2 3.3, CAP-R10) ---------------------------------------------------------------------

    /**
     * CAP-R10 (i)/(v): H1′ store 실패로 후보(p)가 보류된 뒤 다른 조회(CALLER FE)의 신선한 true가 T2로 OPEN(p+1)을 낸다. store가 복구돼 후보
     * 라운드가 돌면 후보의 표지 permit(p) != permitSeq(p+1)이라 판정·회전·FE 없이 해제한다. OPEN(p+1)·KRX VISIBLE 유지. (v)는 표지 없는
     * 예약 — 후보 보류 때 처리 시점 permit p로 고정한다. 잡을 변이: 후보 복구가 이전 허가 후보를 결정(OPEN 취소, 회전, FE+1), 표지 없는 후보의
     * permit을 복구 시점에 다시 읽음.
     */
    @Test fun R10i_earlierPermitCandidate_isReleasedWithoutDeciding() = permitTest { candidateAfterNewPermit(withMarker = true) }

    @Test fun R10v_markerlessCandidate_keepsItsHoldTimePermit() = permitTest { candidateAfterNewPermit(withMarker = false) }

    private suspend fun TestScope.candidateAfterNewPermit(withMarker: Boolean) {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r = h.reserve(grant, if (withMarker) h.marker() else null)
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r)
        assertEquals("premise: 토픽 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        assertEquals("premise: AWAITING", KrxContestPhase.AWAITING, h.phase())
        h.store.loadFailures = 0
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: CALLER FE의 true가 T2 허가", p + 1, h.permit())
        assertEquals("premise: 후보가 아직 남아 있다", 1, h.coordinator.heldLossCandidateCount())
        val epoch = h.store.record.krxCapabilityEpoch
        val sent = h.source.calls.size
        val rotations = h.store.rotationAttempts
        settleKrx()
        assertEquals("premise: 후보 라운드가 돌았다", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("CAP-R10: 회전 없음", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("CAP-R10: 회전 시도 없음", rotations, h.store.rotationAttempts)
        assertEquals("CAP-R10: FE 0", sent, h.source.calls.size)
        assertEquals("CAP-R10: OPEN(p+1) 유지", KrxContestPhase.OPEN, h.phase())
        assertEquals(p + 1, h.permit())
        assertEquals("CAP-R10: effective 유지", KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /**
     * CAP-R10 (ii): 현재 허가 후보(p)가 같은 키에서 결정 없이 Stale로 풀린다(후보 라운드의 live 신원이 문맥과 다름, PAC 후보 검사의 마지막
     * 분기). phase AWAITING·demand 없음이면 독립 FE 1, 그 true에서 허가. 0b(releaseCandidateLocked)의 동작이 2b-1a의 후보 표지 permit 교체
     * 뒤에도 남는지 잠근다(stub 통과 예상 — 회귀 잠금). 잡을 변이: 표지 permit 교체 뒤 Stale 해제의 후속 FE 누락(AWAITING 고착).
     */
    @Test fun R10ii_sameKeyStaleRelease_raisesOneFe() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r = h.reserve(grant, h.marker())
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r)
        assertEquals("premise: 토픽 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        assertEquals("premise: AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertNull("premise: demand 없음", h.owed())
        val sent = h.source.calls.size
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val bound = h.source.identity
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.store.loadFailures = 0
        var spins = 0
        while (h.coordinator.heldLossCandidateCount() > 0) {
            step(100)
            spins += 1
            assertTrue("premise: 후보 라운드가 60초 안에 돈다", spins <= 600)
        }
        h.source.identity = bound
        assertEquals("premise: Stale 해제는 contest 불변", KrxContestPhase.AWAITING, h.phase())
        assertEquals("CAP-R10 (ii): 후속 FE 1(응답 대기 중)", sent + 1, h.source.calls.size)
        followUp.complete(active)
        step(0)
        assertEquals("그 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * 후보 복구 허가 재판정의 HOLD 분기(설계 r2 3.3, D4 r6:187): n=1·OPEN(p)에서 표지 p 거절이 store 실패로 후보(p)가 된 뒤 CALLER의 신선한 true가
     * T2로 n=2·HOLD(허가 그대로 p)를 만든다. HOLD 만료 전에 후보 라운드가 돌면 표지 permit은 같아도 phase HOLD라 판정·회전·FE 없이 해제한다:
     * HOLD·holdUntil·n=2·permit p 유지. 잡을 변이: 재판정의 HOLD 조건 누락(결정·회전·FE+1, HOLD 취소).
     */
    @Test fun R10vi_samePermitCandidateDuringHold_isReleasedWithoutDeciding() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        h.rejectKrx(h.issueGrant())
        step(100)
        assertEquals("premise: 첫 바퀴 n=1·OPEN", KrxContestPhase.OPEN, h.phase())
        val p = h.permit()
        val grant = h.issueGrant()
        val r = h.reserve(grant, h.marker())
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r)
        assertEquals("premise: 토픽 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        h.store.loadFailures = 0
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: T2로 HOLD", KrxContestPhase.HOLD, h.phase())
        assertEquals("premise: n=2", 2, h.view().state.streak)
        assertEquals("premise: HOLD는 허가를 내지 않는다", p, h.permit())
        val holdUntil = checkNotNull(h.view().state.holdUntilMillis) { "premise: holdUntil이 있다" }
        val epoch = h.store.record.krxCapabilityEpoch
        val rotations = h.store.rotationAttempts
        val sent = h.source.calls.size
        while (h.coordinator.heldLossCandidateCount() > 0) {
            step(100)
            assertTrue("premise: 후보 라운드가 HOLD 만료 전에 돈다", testScheduler.currentTime < holdUntil)
        }
        assertEquals("HOLD 분기: 회전 없음", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("HOLD 분기: 회전 시도 없음", rotations, h.store.rotationAttempts)
        assertEquals("HOLD 분기: FE 0", sent, h.source.calls.size)
        assertEquals("HOLD 유지", KrxContestPhase.HOLD, h.phase())
        assertEquals("holdUntil 유지", holdUntil, h.view().state.holdUntilMillis)
        assertEquals("n=2 유지", 2, h.view().state.streak)
        assertEquals("허가 p 유지", p, h.permit())
    }

    /**
     * CAP-R10 (iii): store 실패가 계속되는 동안 같은 허가 예약 2건 — 후보 1(둘째는 허가 판정이 store.load 앞에서 버린다: load 수 불변). 복구
     * 뒤 FE 1·회전 ≤ 1, 그 true에서 허가. 잡을 변이: 예약마다 후보.
     */
    @Test fun R10iii_samePermitDuringStoreFailure_holdsOneCandidate() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r1 = h.reserve(grant, h.marker())
        val r2 = h.reserve(grant, h.marker())
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r1)
        assertEquals("premise: 후보 1", 1, h.coordinator.heldLossCandidateCount())
        val loads = h.store.loads
        h.coordinator.onTopicRejected(r2)
        assertEquals("CAP-R10 (iii): 후보는 1", 1, h.coordinator.heldLossCandidateCount())
        assertEquals("둘째는 store.load를 쓰지 않는다", loads, h.store.loads)
        h.ended(grant, r2)
        val sent = h.source.calls.size
        val rotations = h.store.rotationAttempts
        h.store.loadFailures = 0
        settleKrx()
        assertEquals("복구 뒤 FE 1", sent + 1, h.source.calls.size)
        assertTrue("회전 ≤ 1", h.store.rotationAttempts - rotations <= 1)
        assertEquals("그 true에서 허가", p + 1, h.permit())
    }

    // --- 시험 r2 보강 (Codex 시험 3회차 판단 1·2·3): 잠금 안 live 판독 중 취소, epoch 가드로 끝나는 blocker, 명시·확정 null epoch, 임의 옛 토큰 ------------

    /**
     * W3 끝 자리 — 잠금 안 live 판독 중 취소(PAC:1908-1911): 막던 예약의 처리가 잠금 안 live 판독에서 멈춘 동안 작업이 취소되면 CancellationException이
     * 그대로 나가고 current=false로 끝난다. 그 종료도 W3로 평가한다(잠금 밖 종료이므로 켬일 때만 PAC scope 평가). 잡을 변이: 이 종료를 평가하지 않음.
     */
    @Test fun P13_blockerCancelledDuringTheLiveRead_firesW3() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        val identityGate = CompletableDeferred<Unit>()
        h.source.identityGate = identityGate
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.onTopicRejected(r) }
        assertNull("premise: 처리가 잠금 안 live 판독에 들어갔다(gate 소비)", h.source.identityGate)
        assertTrue("premise: live 판독에서 멈췄다", job.isActive)
        job.cancel()
        step(0)
        assertTrue("premise: 취소로 끝났다", job.isCancelled)
        identityGate.complete(Unit)
        h.ended(grant, r)
        step(0)
        assertEquals("W3: live 판독 중 취소도 평가해 인계 기록", p, h.view().rejectedPermit)
        assertNull("W3: withheld 비움", h.view().withheld)
        assertEquals("W3: 독립 FE 송신 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        followUp.complete(active)
        step(0)
        assertEquals("W3 FE의 true에서 허가", p + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    /**
     * 막던 예약이 epoch 가드로 끝남(설계 r2 3.3, 합성 상태): W1 뒤 store 기록만 KRX 회전(AccessEpochTransitions.rotate)으로 옮긴다. (가) grant 발급의
     * load로 먼저 확정하면 첫째 위치(메모리 확정 epoch)가, (나) 확정하지 않으면 둘째 위치(store.load 기록 epoch)가 그 예약을 인계 없이 끝낸다. 그 예약은
     * 실제 withheld의 막던 예약이므로 종료를 W3로 평가한다. 운영 H2 경로(재조회 demand 포함)는 따로 분류한다. 잡을 변이: 가드 종료를 W3로 평가하지 않음.
     */
    @Test fun P14_blockerEndingOnTheEpochGuard_firesW3() = permitTest {
        for (confirmFirst in listOf(true, false)) {
            val label = if (confirmFirst) "[첫째 위치]" else "[둘째 위치]"
            val h = on()
            h.grantedKrxVisible()
            val p = h.permit()
            val (grant, q, rs) = withheldBy(h, blockers = 1)
            val r = rs.single()
            val key = h.view().key
            h.store.record = AccessEpochTransitions.rotate(
                h.store.record, rotateUser = false, rotateKrx = true, ids = EpochIdGenerator { "external-0" }
            )
            if (confirmFirst) assertEquals("$label premise(A1): 확정 발급도 같은 토큰", grant, h.issueGrant())
            assertNotEquals("$label premise: 표지 epoch가 기록 epoch와 다르다", r.marker?.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
            assertEquals("$label premise: 같은 키", key, h.view().key)
            assertEquals("$label premise: 허가 p", p, h.permit())
            assertEquals("$label premise: withheld 유지", q, h.view().withheld?.fromOrder)
            assertNull("$label premise: demand 없음", h.owed())
            val followUp = CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(followUp)
            val sent = h.source.calls.size
            h.coordinator.onTopicRejected(r)
            h.ended(grant, r)
            step(0)
            assertEquals("$label W3: 가드 종료도 평가해 인계 기록", p, h.view().rejectedPermit)
            assertNull("$label W3: withheld 비움", h.view().withheld)
            assertEquals("$label W3: 독립 FE 송신 정확히 1", sent + 1, h.source.calls.size)
            followUp.complete(active)
            step(0)
            assertEquals("$label W3 FE의 true에서 허가", p + 1, h.permit())
        }
    }

    /**
     * 명시 null epoch == 확정 null epoch는 적격(설계 r2 3.3 'nullable epoch 비교', 합성 상태): OPEN(p 미인계)에서 기록의 KRX epoch만 null로 바꾸고
     * topicGrantResult의 load로 확정한다. capability는 DERIVED_SEAL로 막히지만(PAC:2950) 그것은 effective 숨김이고 예약 판정과 별개다.
     * KrxSendMarker(null, p)는 첫 인계다. 잡을 변이: 명시 null epoch는 늘 폐기. 운영 도달 증명으로 세지 않는다.
     */
    @Test fun P3c_explicitNullEpochMatchingAConfirmedNullEpoch_isEligible() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val token = h.issueGrant()
        val key = h.view().key
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        assertEquals("premise(A1): 확정 발급도 같은 토큰", token, h.issueGrant())
        assertEquals("premise: 같은 키", key, h.view().key)
        assertEquals("premise: 허가 p", p, h.permit())
        val facts = h.coordinator.accessSnapshot.facts
        assertTrue("premise: capability DERIVED_SEAL", TopicAccessBlock.DERIVED_SEAL in facts.capabilityBlocks)
        assertTrue("premise: 사용자 축 허용", facts.userAllowed)
        val r = h.reserve(token, KrxSendMarker(null, p))
        h.coordinator.onTopicRejected(r)
        assertEquals("null == null 적격: 첫 인계 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertEquals("첫 인계 기록", p, h.view().rejectedPermit)
        assertEquals("인계로 처리됐다(latestCurrent)", r.order, h.coordinator.rejectionView(token).latestCurrent)
        h.ended(token, r)
    }

    /**
     * 막는 예약의 토큰 한정(Codex 시험 3회차 판단 2): 현재 토큰이나 같은 문맥 재승인 lineage가 아닌, 출처를 확인할 수 없는 토큰의 KRX 예약은 표지가
     * 맞아도 막지 않는다. q의 true는 T2로 허가한다. 잡을 변이: 임의 토큰 예약까지 막는 예약으로 셈(W1 고착).
     */
    @Test fun N6_unknownTokenReservation_doesNotBlock() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val stranger = h.reserve(TopicGrantToken(grant.value + 100), h.marker(p))
        assertTrue("premise: q 뒤에 보고됐다", stranger.order > q)
        gate.complete(active)
        step(0)
        assertEquals("출처 모를 토큰 예약은 막지 않는다: T2 허가", p + 1, h.permit())
        assertNull("withheld 없음", h.view().withheld)
    }

    // --- 시험 r5 (구현 r1 내부 검토 F1): 처리 전 지연 키 비교 ---------------------------------------------------------------------------------------

    private enum class StaleKeyVariant { EARLIER_PERMIT_MARKER, MARKERLESS, STORE_FAILURE, APPROVAL_CLOSED }

    /**
     * 처리 전 지연 키 비교(D4 r7 3.3 '모든 사건은 처리 전에 키를 지연 비교한다', 구현 r1 내부 검토 F1): store 기록만 사용자 epoch를 회전하고 grant 발급의
     * load가 그것을 확정하면, 새 문맥 grant가 나오지만 저장된 contest 키는 아직 옛 키다(합성 상태 — RO5와 같은 입력, 운영 도달 증명 아님). 그 grant의
     * KRX 거절은 판정 전에 키를 맞춰야 하므로 C0(raw KRX VISIBLE·premium runtime → OPEN·permit p+1)가 먼저 적용된다.
     * (가) 표지 permit p → 이전 허가라 인계 없음(OPEN(p+1), 인계 기록·FE 없음)
     * (나) 표지 없음 → 처리 시점 permit p+1로 인계(rejectedPermit = p+1), 같은 허가의 표지 없는 중복은 상태 변화 0, FE는 하나뿐
     * (다) 표지 없음 + store 실패 → 후보 permit p+1이라 복구가 결정(회전)·FE 1(옛 permit p를 찍으면 재판정이 결정 없이 풀어 AWAITING 고착)
     * (라) 표지 없음 + 승인 닫힘 → reopenOwed = (새 키, p+1), 재개 FE 1(옛 permit p면 재개가 permit 불일치로 FE 없이 지움)
     * 잡을 변이: 판정·permit 판독을 지연 키 비교 앞에 둠.
     */
    @Test fun P15_staleContestKey_isSyncedBeforeTheJudgment() = permitTest {
        for (variant in StaleKeyVariant.values()) staleKey(variant)
    }

    private suspend fun TestScope.staleKey(variant: StaleKeyVariant) {
        val label = "[$variant]"
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val oldToken = h.issueGrant()
        val oldKey = checkNotNull(h.view().key)
        val krxEpoch = h.store.record.krxCapabilityEpoch
        h.store.record = AccessEpochTransitions.rotate(
            h.store.record, rotateUser = true, rotateKrx = false, ids = EpochIdGenerator { "external-user-0" }
        )
        val grant = h.issueGrant()
        assertNotEquals("$label premise: 확정 load가 새 문맥 grant를 냈다", oldToken, grant)
        assertEquals("$label premise: 저장된 contest 키는 아직 옛 키(지연 C0 대기)", oldKey, h.view().key)
        assertEquals("$label premise: KRX epoch는 그대로", krxEpoch, h.store.record.krxCapabilityEpoch)
        val held = if (variant == StaleKeyVariant.APPROVAL_CLOSED) h.closeApprovalOnTheSameKey() else null
        if (variant == StaleKeyVariant.STORE_FAILURE) h.store.loadFailures = Int.MAX_VALUE
        val marker = if (variant == StaleKeyVariant.EARLIER_PERMIT_MARKER) KrxSendMarker(krxEpoch, p) else null
        val r = h.reserve(grant, marker)
        val sent = h.source.calls.size
        h.coordinator.onTopicRejected(r)
        val newKey = checkNotNull(h.view().key)
        assertTrue("$label 처리 전에 키를 맞췄다", newKey != oldKey)
        when (variant) {
            StaleKeyVariant.EARLIER_PERMIT_MARKER -> {
                assertEquals("$label C0로 OPEN", KrxContestPhase.OPEN, h.phase())
                assertEquals("$label C0의 새 허가", p + 1, h.permit())
                assertTrue("$label 이전 허가 표지는 인계하지 않는다", h.view().rejectedPermit < p + 1)
                assertNull("$label 인계로 처리되지 않았다", h.coordinator.rejectionView(grant).latestCurrent)
                h.ended(grant, r)
                settleKrx()
                assertEquals("$label FE 0", sent, h.source.calls.size)
            }
            StaleKeyVariant.MARKERLESS -> {
                assertEquals("$label 처리 시점 permit p+1로 인계", p + 1, h.view().rejectedPermit)
                assertEquals("$label AWAITING", KrxContestPhase.AWAITING, h.phase())
                val before = h.full(grant)
                val dup = h.reserve(grant, null)
                h.coordinator.onTopicRejected(dup)
                assertEquals("$label 같은 허가의 표지 없는 중복은 상태 변화 0", before, h.full(grant))
                h.ended(grant, dup)
                settleKrx()
                assertEquals("$label FE는 하나뿐", sent + 1, h.source.calls.size)
                assertEquals("$label 그 true에서 허가", p + 2, h.permit())
            }
            StaleKeyVariant.STORE_FAILURE -> {
                assertEquals("$label 후보 보류", 1, h.coordinator.heldLossCandidateCount())
                assertEquals("$label 인계 permit p+1", p + 1, h.view().rejectedPermit)
                h.store.loadFailures = 0
                val epoch = h.store.record.krxCapabilityEpoch
                settleKrx()
                assertEquals("$label 후보가 결정됐다", 0, h.coordinator.heldLossCandidateCount())
                assertNotEquals("$label 결정의 회전(옛 permit을 찍었으면 결정 없이 해제)", epoch, h.store.record.krxCapabilityEpoch)
                assertEquals("$label FE 1", sent + 1, h.source.calls.size)
                assertEquals("$label 그 true에서 허가", p + 2, h.permit())
            }
            StaleKeyVariant.APPROVAL_CLOSED -> {
                assertEquals("$label 재개 의무는 새 키·p+1", KrxReopenOwed(newKey, p + 1), h.view().reopenOwed)
                assertEquals("$label 인계 permit p+1", p + 1, h.view().rejectedPermit)
                h.reopenApproval(checkNotNull(held))
                settleKrx()
                assertEquals("$label 재개 FE 1", sent + 1, h.source.calls.size)
                assertEquals("$label 그 true에서 허가", p + 2, h.permit())
            }
        }
    }

    /**
     * 막힘은 만료된 HOLD에 적용하지 않는다(D4 r7 3.2 '막힘은 T2·T3·W1에서만', X2 행 'T3은 미만료 HOLD와 OPEN에만', 구현 r1 내부 검토 F2): H2 두 바퀴로
     * 인계 없이 n=2·HOLD(허가 p 미인계)에 들어가되 둘째 H2의 KRX 회전을 실패시켜 capability seal을 둔다. 보류 중 CALLER FE q를 진행 중에 두고 그 뒤
     * 적격 예약 r(현재 epoch·허가 p)을 보고한다. seal 때문에 HOLD가 허가 없이 만료(X2, holdExpired)된 뒤 q의 신선한 true가 오면 막힘과 무관하게
     * 0a의 X2 재개로 permit p+1·OPEN이 된다. r은 뒤에 이전 허가가 되어 상태 변화 0(I8). 잡을 변이: 만료 HOLD에도 막힘 적용(재개 허가 버림).
     */
    @Test fun P16_expiredHoldResumesDespiteAPendingEligibleReservation() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p0 = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: 첫 H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        h.source.next = { active }
        val floor1 = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: 첫 재조회 floor" }
        step(floor1 - testScheduler.currentTime)   // QUIET 초기화(긴 정지)를 피하려고 floor까지만 옮긴다
        step(0)
        assertEquals("premise: 첫 T2로 n=1·OPEN", KrxContestPhase.OPEN, h.phase())
        val p = h.permit()
        assertEquals("premise: n=1 허가", p0 + 1, p)
        assertEquals(1, h.view().state.streak)
        h.store.rotationFailures = 1_000
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: 둘째 H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertTrue("premise: 회전 실패로 capability seal", TopicAccessBlock.EXPLICIT_SEAL in h.coordinator.accessSnapshot.facts.capabilityBlocks)
        h.source.next = { active }
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: 재조회 floor" }
        step(floor - testScheduler.currentTime)
        step(0)
        assertEquals("premise: seal 중 T2로 n=2·HOLD", KrxContestPhase.HOLD, h.phase())
        assertEquals(2, h.view().state.streak)
        assertEquals("premise: HOLD는 허가를 내지 않는다(p 미인계)", p, h.permit())
        assertTrue("premise: p는 인계되지 않았다", h.view().rejectedPermit < p)
        val holdUntil = checkNotNull(h.view().state.holdUntilMillis)
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val sentBefore = h.source.calls.size
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        assertEquals("premise: HOLD 중 CALLER FE q가 바로 나갔다(응답 대기)", sentBefore + 1, h.source.calls.size)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.max()
        val grant = h.issueGrant()
        val r = h.reserve(grant, h.marker(p))
        assertTrue("premise: r은 q 뒤에 보고됐다", r.order > q)
        step(holdUntil - testScheduler.currentTime)
        assertEquals("premise: seal 때문에 허가 없이 만료(X2)", KrxContestPhase.HOLD, h.phase())
        assertTrue("premise: holdExpired", h.view().state.holdExpired)
        assertEquals("premise: 만료에도 허가 그대로", p, h.permit())
        gate.complete(active)
        step(0)
        caller.join()
        assertEquals("X2 재개: 만료 HOLD의 신선한 true는 막힘과 무관하게 허가", p + 1, h.permit())
        assertEquals("X2 재개로 OPEN", KrxContestPhase.OPEN, h.phase())
        assertNull("withheld 없음", h.view().withheld)
        val before = h.full(grant)
        h.coordinator.onTopicRejected(r)
        assertEquals("r은 이전 허가라 상태 변화 0(I8)", before, h.full(grant))
        h.ended(grant, r)
    }

    // --- reopenOwed 묶음 (합성 상태: 같은 키·기존 grant를 둔 승인 닫힘, harness closeApprovalOnTheSameKey) ----------------------------------------

    /**
     * CAP-R03 (iii): W1 → 같은 키 승인 닫힘 → 막던 예약이 닫힌 동안 처리돼 승인 닫힘 H1′로 인계: W2(withheld 비움), rejectedPermit = p,
     * reopenOwed = (키, p), 닫힌 동안 FE 0 → 같은 키 재개 때 FE 정확히 1 → true에서 허가. 잡을 변이: 승인 닫힘을 W3로 평가(닫힘에서 W3와 H1′ 동시),
     * 재개 FE 누락(고착), 재개 FE 중복.
     */
    @Test fun RO1_blockerHandedOverByApprovalClosure_reopenFiresOnce() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, q, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val key = checkNotNull(h.view().key)
        val held = h.closeApprovalOnTheSameKey()
        assertEquals("premise: 같은 키", key, h.view().key)
        assertEquals("premise: withheld 유지", q, h.view().withheld?.fromOrder)
        val sent = h.source.calls.size
        h.coordinator.onTopicRejected(r)
        h.ended(grant, r)
        assertNull("W2: 승인 닫힘 인계가 withheld를 비웠다", h.view().withheld)
        assertEquals("인계 기록", p, h.view().rejectedPermit)
        assertEquals("재개 의무", KrxReopenOwed(key, p), h.view().reopenOwed)
        step(10_000)
        assertEquals("닫힌 동안 FE 0", sent, h.source.calls.size)
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        h.reopenApproval(held)
        step(0)
        assertEquals("같은 키 재개 FE 정확히 1(응답 대기 중)", sent + 1, h.source.calls.size)
        assertNull("재개 의무를 지웠다", h.view().reopenOwed)
        followUp.complete(active)
        step(0)
        assertEquals("그 true에서 허가", p + 1, h.permit())
        settleKrx()
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    private enum class ClosedDupAt { WHILE_CLOSED, DURING_FE, AFTER_FE }

    /**
     * CAP-R06 승인 닫힘 분기: 허가 p의 첫 예약이 같은 키 승인 닫힘 H1′로 인계된다. 같은 허가 표지의 지연 예약 N건(N=1·3)을 닫힌 동안·재개 FE 진행 중·
     * 그 true 뒤에 처리해도 상태 변화 0, 재개 FE 정확히 1, n은 T2에서 한 번. 잡을 변이: 판정을 승인 닫힘 뒤에 둠(중복이 다시 숨김·재개 FE 2).
     */
    @Test fun RO2_approvalClosureFirstHandover_duplicatesChangeNothing() = permitTest {
        for (at in ClosedDupAt.values()) for (n in listOf(1, 3)) {
            val label = "[$at n=$n]"
            val h = on()
            h.grantedKrxVisible()
            val p = h.permit()
            val grant = h.issueGrant()
            val r1 = h.reserve(grant, h.marker())
            val dups = List(n) { h.reserve(grant, h.marker()) }
            val key = checkNotNull(h.view().key)
            val held = h.closeApprovalOnTheSameKey()
            val sends0 = h.source.calls.size
            h.coordinator.onTopicRejected(r1)
            assertEquals("$label premise: 승인 닫힘 H1′로 AWAITING", KrxContestPhase.AWAITING, h.phase())
            assertEquals("$label 인계 기록", p, h.view().rejectedPermit)
            assertEquals("$label 재개 의무", KrxReopenOwed(key, p), h.view().reopenOwed)
            suspend fun processDups() = dups.forEachIndexed { i, dup ->
                val before = h.full(grant)
                h.coordinator.onTopicRejected(dup)
                assertEquals("$label 중복 $i 은 상태를 바꾸지 않는다", before, h.full(grant))
                h.ended(grant, dup)
            }
            if (at == ClosedDupAt.WHILE_CLOSED) processDups()
            val fe = CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(fe)
            h.reopenApproval(held)
            step(0)
            assertEquals("$label 재개 FE 1이 진행 중", sends0 + 1, h.source.calls.size)
            if (at == ClosedDupAt.DURING_FE) processDups()
            fe.complete(active)
            step(0)
            assertEquals("$label T2로 허가", p + 1, h.permit())
            assertEquals("$label n은 T2에서 한 번", 1, h.view().state.streak)
            if (at == ClosedDupAt.AFTER_FE) processDups()
            settleKrx()
            assertEquals("$label entitlements 송신 정확히 1", sends0 + 1, h.source.calls.size)
        }
    }

    /**
     * CAP-R08 W3·승인 닫힘 겹침: H2로 AWAITING, W1(막던 예약 r(p)) → 승인 닫힘 → r이 닫힌 동안 승인 닫힘 H1′로 끝남(W2, reopenOwed) → 같은 키 재개.
     * (가) 재개 때 demand 없음 (나) 닫기 전 premium 거절 live 실패로 FORCE_PREMIUM demand가 서 있음(재개가 그 demand를 무장하고 재개 의무도 처리) —
     * 두 경우 모두 entitlements 쪽 송신 정확히 1, rejectedPermit = p, withheld 해제, 그 true에서 n+1 한 번.
     * 잡을 변이: 승인 닫힘에서 W3도 발화(ledger current=false, FE 2), 재개 FE를 기존 demand와 별개로 올림(송신 2), W3가 인계를 기록하지 않음.
     */
    @Test fun RO3_w3AndApprovalClosureOverlap_sendOnce() = permitTest {
        for (withDemand in listOf(false, true)) {
            val label = if (withDemand) "[demand 있음]" else "[demand 없음]"
            val h = on()
            h.grantedKrxVisible()
            val p = h.permit()
            val (grant, _, rs) = withheldBy(h, blockers = 1)
            val r = rs.single()
            if (withDemand) {
                h.source.identityNulls = 1
                h.coordinator.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
                assertEquals("$label premise: FORCE_PREMIUM demand", RefreshIntent.FORCE_PREMIUM, h.owed())
            }
            val held = h.closeApprovalOnTheSameKey()
            val sent = h.source.calls.size
            h.coordinator.onTopicRejected(r)
            h.ended(grant, r)
            assertNull("$label W2: withheld 해제", h.view().withheld)
            assertEquals("$label 인계 기록", p, h.view().rejectedPermit)
            h.source.next = { active }
            h.reopenApproval(held)
            settleKrx()
            assertEquals("$label 재개 뒤 송신 정확히 1", sent + 1, h.source.calls.size)
            assertEquals("$label 그 true에서 허가", p + 1, h.permit())
            assertEquals("$label n은 한 번", 1, h.view().state.streak)
        }
    }

    /**
     * CAP-R08 변형: 막던 예약 둘 — 은퇴 토큰 T0의 rOld(같은 문맥 재승인 lineage, P10과 같은 준비)와 현재 토큰의 r2. 승인 닫힘 뒤 (가) rOld가 먼저
     * grant 불일치로 끝나고(마지막 blocker가 아니라 W3 없음) r2가 승인 닫힘으로 인계, (나) r2가 먼저 승인 닫힘으로 인계(W2)되고 rOld가 뒤에 grant 불일치로
     * 끝남(withheld 없음, W3 없음). 두 순서 모두 재개 뒤 송신 정확히 1, rejectedPermit = p, 그 true에서 허가.
     */
    @Test fun RO3b_staleAndApprovalClosedBlockers_bothOrdersSendOnce() = permitTest {
        for (staleFirst in listOf(true, false)) {
            val label = if (staleFirst) "[stale 먼저]" else "[승인 닫힘 먼저]"
            val h = on()
            h.grantedKrxVisible()
            val p = h.permit()
            val t0 = h.issueGrant()
            h.source.identityNulls = 1
            h.coordinator.onTopicRejected(t0, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
            step(5_010)
            val t1 = h.issueGrant()
            assertNotEquals("$label premise: 같은 문맥 재승인 새 토큰", t0, t1)
            h.source.next = { pendingFalse }
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            assertEquals("$label premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
            val gate = CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(gate)
            val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
            advanceTimeBy(floor - testScheduler.currentTime)
            step(0)
            val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
            val rOld = h.reserve(t0, h.marker(p))
            val r2 = h.reserve(t1, h.marker(p))
            gate.complete(active)
            step(0)
            assertEquals("$label W1: 두 blocker", KrxWithheld(q, listOf(rOld.order, r2.order)), h.view().withheld)
            val held = h.closeApprovalOnTheSameKey()
            val sent = h.source.calls.size
            val order = if (staleFirst) listOf(rOld, r2) else listOf(r2, rOld)
            for (r in order) h.coordinator.onTopicRejected(r)
            h.ended(t0, rOld)
            h.ended(t1, r2)
            assertNull("$label withheld 해제", h.view().withheld)
            assertEquals("$label 인계 기록", p, h.view().rejectedPermit)
            h.source.next = { active }
            h.reopenApproval(held)
            settleKrx()
            assertEquals("$label 재개 뒤 송신 정확히 1", sent + 1, h.source.calls.size)
            assertEquals("$label 그 true에서 허가", p + 1, h.permit())
        }
    }

    /**
     * CAP-R08 진행 중 조회 병합(Codex 시험 4회차 판단 3): W1의 막던 예약 r이 보고된 뒤 CALLER FP q(> r.order)를 gate에 걸어 둔 채 승인을 닫고, r이
     * 승인 닫힘으로 인계된 다음 같은 키로 재개한다. 재개 의무는 이미 진행 중인 q가 덮는다: 재개 의무 제거·withheld 해제·rejectedPermit = p, q의
     * true로 T2 한 번, q를 포함한 송신 합 1(추가 FE 0). (r4) premium 거절로 demand를 세우지 않는다 — 합성 승인 닫힘이 userInvalidations를 올려 그
     * premium 거절의 재승인을 막고(attemptTopicReapprovalLocked) 재시도 조회를 따로 만들므로 재개 의무와 무관한 송신이 섞인다.
     * 잡을 변이: q 시작 뒤 재개 의무로 independent demand를 새로 세워 q가 정산하지 못하는 두 번째 조회를 만듦.
     */
    @Test fun RO3c_inFlightQueryCoversTheReopenObligation() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val (grant, _, rs) = withheldBy(h, blockers = 1)
        val r = rs.single()
        val floor = h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?
        if (floor != null && floor > testScheduler.currentTime) advanceTimeBy(floor - testScheduler.currentTime)
        val before = h.source.calls.size
        val qAnswer = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(qAnswer)
        val fp = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        assertEquals("premise: CALLER FP q가 바로 시작했다(응답 대기)", before + 1, h.source.calls.size)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.max()
        assertTrue("premise: q는 r 보고 뒤에 시작했다", q > r.order)
        val sent = h.source.calls.size
        val held = h.closeApprovalOnTheSameKey()
        h.coordinator.onTopicRejected(r)
        h.ended(grant, r)
        h.reopenApproval(held)
        step(0)
        assertNull("재개 의무 제거", h.view().reopenOwed)
        assertNull("withheld 해제", h.view().withheld)
        assertEquals("인계 기록", p, h.view().rejectedPermit)
        h.source.next = { active }
        qAnswer.complete(active)
        step(0)
        fp.join()
        settleKrx()
        assertEquals("q를 포함한 송신 합 1(추가 FE 0)", sent, h.source.calls.size)
        assertEquals("q의 true에서 T2 한 번", p + 1, h.permit())
        assertEquals("n은 한 번", 1, h.view().state.streak)
    }

    /**
     * C0의 reopenOwed 정리(Codex 시험 4회차 판단 2, 합성 상태): OPEN(p)에서 승인을 닫고 현재 허가의 적격 KRX 예약을 처리해 reopenOwed = (옛 키, p)를 둔다.
     * store 기록만 사용자 epoch 회전(AccessEpochTransitions.rotate(rotateUser=true))으로 옮기면, 실제 재개의 cleanup load가 새 사용자 epoch를 확정하고
     * contest 키(사용자 epoch 포함, PAC:2654)가 바뀐다. 재개는 옛 의무를 소비하기 전에 키 변경을 판정해야 한다: 새 키, reopenOwed = null,
     * rejectedPermit = p 유지, 옛 키의 재개 FE 0. raw KRX VISIBLE·premium runtime이 열려 있으므로 C0는 OPEN·permit p+1. 운영 도달 증명이 아니다.
     * 잡을 변이: 재개가 키 변경 전에 옛 키 의무로 FE를 냄, C0가 reopenOwed를 남김.
     */
    @Test fun RO5_keyChangeAtReopen_clearsTheReopenObligation() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val r = h.reserve(grant, h.marker())
        val oldKey = checkNotNull(h.view().key)
        val held = h.closeApprovalOnTheSameKey()
        h.coordinator.onTopicRejected(r)
        assertEquals("재개 의무(옛 키)", KrxReopenOwed(oldKey, p), h.view().reopenOwed)
        h.store.record = AccessEpochTransitions.rotate(
            h.store.record, rotateUser = true, rotateKrx = false, ids = EpochIdGenerator { "external-user-0" }
        )
        val sent = h.source.calls.size
        h.reopenApproval(held)
        step(0)
        assertTrue("C0: 새 키", h.view().key != oldKey)
        assertNull("C0: reopenOwed 정리", h.view().reopenOwed)
        assertEquals("C0: rejectedPermit 유지", p, h.view().rejectedPermit)
        assertEquals("옛 키의 재개 FE 0", sent, h.source.calls.size)
        assertEquals("C0: raw KRX VISIBLE·premium runtime이라 OPEN", KrxContestPhase.OPEN, h.phase())
        assertEquals("C0: permit p+1", p + 1, h.permit())
    }

    /**
     * CAP-R10 (iv): 이전 허가 후보(p)가 남은 채 새 허가 p+1이 나고, p+1이 같은 키 승인 닫힘으로 인계된다(reopenOwed (키, p+1)). 재개 뒤 재개 FE만 1이고,
     * 이전 허가 후보는 판정·회전·FE 없이 해제된다. 잡을 변이: 후보 해제가 후속 FE를 더함(송신 2), 이전 허가 후보 결정.
     */
    @Test fun RO4_earlierPermitCandidateWithApprovalClosureHandover() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val rc = h.reserve(grant, h.marker())
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(rc)
        assertEquals("premise: 후보(p)", 1, h.coordinator.heldLossCandidateCount())
        h.store.loadFailures = 0
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: T2로 p+1", p + 1, h.permit())
        assertEquals("premise: 후보가 아직 남아 있다", 1, h.coordinator.heldLossCandidateCount())
        val key = checkNotNull(h.view().key)
        val held = h.closeApprovalOnTheSameKey()
        val rn = h.reserve(grant, h.marker(p + 1))
        h.coordinator.onTopicRejected(rn)
        assertEquals("premise: p+1 승인 닫힘 인계", KrxReopenOwed(key, p + 1), h.view().reopenOwed)
        val epoch = h.store.record.krxCapabilityEpoch
        val rotations = h.store.rotationAttempts
        val sent = h.source.calls.size
        h.reopenApproval(held)
        settleKrx()
        assertEquals("premise: 후보 라운드가 돌았다", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("CAP-R10 (iv): 재개 FE만 1", sent + 1, h.source.calls.size)
        assertEquals("이전 허가 후보 결정 없음(회전 시도 없음)", rotations, h.store.rotationAttempts)
        assertEquals("이전 허가 후보 결정 없음(epoch 그대로)", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("재개 FE의 true에서 허가", p + 2, h.permit())
    }

    // --- 음성 대조: 부적격 예약은 상태를 바꾸지 않고 막지도 않는다 (I8, CAP-R02·R07·R09) ----------------------------------

    /**
     * CAP-R07 (ii): H2(Pending(false))로 AWAITING(p 인계 안 됨) → 예약된 FE의 true(T2)로 OPEN(p+1) → 현재 epoch·표지 p 예약은 앞 허가라
     * 상태 변화 0, OPEN(p+1) 유지. 잡을 변이: '같은 허가만 중복'으로 구현해 이전 허가를 첫 인계로 처리(OPEN(p+1) 취소, n+1, FE+1).
     */
    @Test fun N1b_earlierPermitMarkerAfterH2ThenT2_changesNothing() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()
        h.source.next = { active }
        settleKrx()
        assertEquals("premise: T2로 OPEN(p+1)", p + 1, h.permit())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        val before = h.full(grant)
        val r = h.reserve(grant, h.marker(p))
        h.coordinator.onTopicRejected(r)
        assertEquals("앞 허가 표지는 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r)
    }

    /**
     * CAP-R07 (i): 허가 p가 H1′(회전 없음)로 인계되고 FE true(T2)로 OPEN(p+1)이 된 뒤 도착한 같은 epoch·표지 p의 거절은 앞 허가라 부적격이다.
     * 상태 변화 0, OPEN(p+1) 유지. 잡을 변이: 표지 permit 대신 처리 시점 permit으로 판정(H1로 AWAITING).
     */
    @Test fun N1_earlierPermitMarkerInOpenNext_changesNothing() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val grant = h.issueGrant()
        val markerP = h.marker()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(h.reserve(grant, markerP))
        settleKrx()
        assertEquals("premise: FE true로 OPEN(p+1)", p + 1, h.permit())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals("premise: H1′은 회전하지 않아 표지 epoch가 현재 epoch다", markerP.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        val before = h.full(grant)
        val late = h.reserve(grant, markerP)
        h.coordinator.onTopicRejected(late)
        assertEquals("앞 허가 표지는 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, late)
    }

    /**
     * CAP-R07 (iii): HOLD 중(n = 2, 그 허가는 두 번째 바퀴에서 인계됨) 같은 허가·현재 epoch 표지의 거절은 부적격이다. HOLD·holdUntil 유지.
     * 잡을 변이: HOLD 중 인계된 허가의 거절을 다시 인계(HOLD를 AWAITING으로).
     */
    @Test fun N2_handedOverPermitMarkerDuringHold_changesNothing() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        h.rejectKrx(h.issueGrant())
        step(100)
        h.rejectKrx(h.issueGrant())
        step(100)
        assertEquals("premise: 두 번째 바퀴 뒤 HOLD", KrxContestPhase.HOLD, h.phase())
        val grant = h.issueGrant()
        val before = h.full(grant)
        val r = h.reserve(grant, h.marker())
        h.coordinator.onTopicRejected(r)
        assertEquals("HOLD 중 인계된 허가의 거절은 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r)
    }

    /**
     * CAP-R02·R09 (iii)(iv): H2로 AWAITING(p 인계 안 됨)에서 FE q 진행 중 처리되지 않은 예약 셋 — 옛 epoch 표지, 앞 허가 표지, premium 섞인
     * 거절 — 이 q 뒤에 보고돼도 막지 않는다. q의 true는 T2로 허가를 낸다. 잡을 변이: 대기 예약이면 무엇이든 막음, latestReported로 막음.
     */
    @Test fun N3_ineligiblePendingReservations_doNotBlock() = permitTest {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val oldEpoch = h.store.record.krxCapabilityEpoch
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        assertNotEquals("premise: Pending(false)의 false edge가 회전했다", oldEpoch, h.store.record.krxCapabilityEpoch)
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val pending = listOf(
            h.reserve(grant, KrxSendMarker(oldEpoch, p)),
            h.reserve(grant, h.marker(p - 1)),
            h.coordinator.reserveRejection(
                grant, listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, TopicRejectionReason.PREMIUM_REQUIRED), h.marker(p)
            )
        )
        assertTrue("premise: 셋 다 q 뒤에 보고됐다", pending.all { it.order > q })
        gate.complete(active)
        step(0)
        assertEquals("부적격 예약은 막지 않는다: T2 허가", p + 1, h.permit())
        assertNull("withheld 없음", h.view().withheld)
    }

    /**
     * CAP-R09 (iv) 뒷절반(D4 r6:612): PREMIUM_REQUIRED가 섞인 예약은 막지 않을 뿐 아니라(N3), 뒤이은 처리도 HEAD premium 경로 그대로다 — 허가
     * 판정·epoch 가드는 KRX 단독 거절에만 적용된다(설계 r2 3.3 '적격: KRX 단독 거절'). 켬과 꺼짐에서 같은 입력열(H2 → 예약된 FE q 진행 중 섞인
     * 예약 → q true → 그 예약 처리)에서 그 처리의 premium 결과가 같다: 처리 직후 상태, 처리 전후 userInvalidations 증가량, 처리 직후 요구, 처리
     * 뒤 settle까지의 송신 증가량. (H2의 KRX 회전은 꺼짐에서만 토큰을 바꿔 userInvalidations 절대값이 달라지므로 증가량으로 본다. settle 동안의
     * premium 재확인은 기본 답 active로 상태를 되돌리므로 상태는 처리 직후에 본다.) 잡을 변이: 허가 판정을 섞인 예약에도 적용(T2 뒤 표지 p는
     * 앞 허가라 premium 거절이 버려짐).
     */
    @Test fun N3b_premiumMixedReservation_followsTheHeadPremiumPath() = permitTest {
        val results = listOf(on(), krxHarness(processJob, HEAD_FACTORY)).map { h ->
            h.grantedKrxVisible()
            h.source.next = { pendingFalse }
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            val grant = h.issueGrant()
            val gate = CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(gate)
            val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
            advanceTimeBy(floor - testScheduler.currentTime)
            step(0)
            val mixed = h.coordinator.reserveRejection(
                grant, listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, TopicRejectionReason.PREMIUM_REQUIRED),
                KrxSendMarker(h.store.record.krxCapabilityEpoch, 1L)
            )
            h.source.next = { active }
            gate.complete(active)
            step(0)
            val invalidationsBefore = h.coordinator.accessSnapshot.userInvalidations
            val sendsBefore = h.source.calls.size
            h.coordinator.onTopicRejected(mixed)
            step(0)
            val stateAfter = h.coordinator.state.value.state
            val owedAfter = h.coordinator.recheckDiagnostics().owedIntent
            val invalidationsDelta = h.coordinator.accessSnapshot.userInvalidations - invalidationsBefore
            settleKrx()
            listOf(stateAfter, invalidationsDelta, owedAfter, h.source.calls.size - sendsBefore)
        }
        assertTrue("premise: premium 거절이 적용됐다(꺼짐, 처리 직후)", results[1][0] != PremiumAccessState.PremiumConfirmed)
        assertEquals("CAP-R09 (iv): 섞인 예약의 처리는 HEAD premium 경로와 같다(상태·userInvalidations·요구·송신)", results[1], results[0])
    }

    /**
     * A2-3·CAP-R09 (vi)(PAC 부분): C1로 NONE(barrier b, permit p)에서 적격 예약 r(p)가 대기 중이어도 신선한 StableActive(true)는 T1로 바로
     * 허가한다(NONE은 막힘을 보지 않는다). 그 뒤 r은 앞 허가가 되어 상태 변화 없이 끝난다(I8). 잡을 변이: NONE에도 막힘 적용(허가 없이 NONE).
     */
    @Test fun N4_noneIssuesThePermitDespiteAPendingEligibleReservation() = permitTest { noneIgnoresBlockers(active, "T1") }

    /** CAP-R09 (vi) 변형 Pending(true)(T4): NONE의 Pending(true)도 막힘을 보지 않고 허가한다(PC-05: Pending(true)는 NONE에서만 허가). */
    @Test fun N4b_nonePendingTrueIssuesThePermitDespiteAPendingEligibleReservation() = permitTest {
        noneIgnoresBlockers(EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 0), "T4")
    }

    private suspend fun TestScope.noneIgnoresBlockers(answer: EntitlementsOutcome, rule: String) {
        val h = on()
        h.grantedKrxVisible()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: C1로 NONE", KrxContestPhase.NONE, h.phase())
        val p = h.permit()
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        h.source.next = { answer }
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val r = h.reserve(grant, h.marker(p))
        assertTrue("premise: r은 q 뒤에 보고됐다", r.order > q)
        gate.complete(answer)
        caller.join()
        assertEquals("A2-3: NONE의 $rule 은 막힘을 보지 않는다", p + 1, h.permit())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertNull("NONE에서는 withheld가 생기지 않는다", h.view().withheld)
        val before = h.full(grant)
        h.coordinator.onTopicRejected(r)
        assertEquals("r은 앞 허가가 되어 상태를 바꾸지 않는다", before, h.full(grant))
        h.ended(grant, r)
    }

    /**
     * CAP-R09 (i): 허가 p가 H1′(회전 없음)로 인계돼 AWAITING인 동안 FE q가 진행 중이고, 같은 허가·현재 epoch 표지 r2가 q 뒤에 보고돼
     * 대기 중이다. r2는 인계된 허가의 중복이라 부적격이므로 q의 true를 막지 않는다: T2로 n+1(허가), withheld null. 그 뒤 r2가 처리돼도
     * 앞 허가라 상태 변화 0, FE 0. 잡을 변이: 막힘을 '현재 epoch 캡처'만으로 판정(중복이 막아 허가 없음, 뒤이은 W3 FE).
     */
    @Test fun N5_handedOverPermitDuplicatePending_doesNotBlock() = permitTest { handedOverDuplicate(abandon = false) }

    /** CAP-R09 (ii): N5와 같은데 r2가 뒤에 abandon된다 — 막힘이 없었으므로 FE 0, 상태 변화 0(withheld 고착도 없음). */
    @Test fun N5b_handedOverPermitDuplicateAbandoned_changesNothing() = permitTest { handedOverDuplicate(abandon = true) }

    private suspend fun TestScope.handedOverDuplicate(abandon: Boolean) {
        val h = on()
        h.grantedKrxVisible()
        val p = h.permit()
        val epoch = h.store.record.krxCapabilityEpoch
        h.undecidedRejection()
        assertEquals("premise: H1′으로 AWAITING", KrxContestPhase.AWAITING, h.phase())
        val grant = h.issueGrant()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        step(0)
        val q = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val r2 = h.reserve(grant, h.marker(p))
        assertTrue("premise: r2는 q 뒤에 보고됐다", r2.order > q)
        assertEquals("premise: r2는 현재 epoch 표지다(H1′ 무회전)", epoch, r2.marker?.krxCapabilityEpoch)
        gate.complete(active)
        step(0)
        assertEquals("CAP-R09 (i): 인계된 허가의 중복은 막지 않는다 — T2 허가", p + 1, h.permit())
        assertNull("withheld 없음", h.view().withheld)

        val before = h.full(grant)
        if (abandon) h.coordinator.abandonRejection(r2) else h.coordinator.onTopicRejected(r2)
        settleKrx()
        assertEquals("그 예약이 뒤에 끝나도 상태 변화 0·FE 0", before, h.full(grant))
        h.ended(grant, r2)
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
