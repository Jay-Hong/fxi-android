package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b0b PAC 배선(스위치 켬) 계약 시험 — CAP-K01~K07, K09, K10, K12~K14, CAP-G01, O-7
 * (`R4c/S6/s6_2b0b/s6_2b0b_contracts.r8.md` 4.1, D4 `loop_bound.r4.md` 3.3·8절, 보충 A2 r8).
 *
 * 관측: contest 상태는 `krxContestView`, 공개 effective는 `krx`, 숨김 근거는 `accessSnapshot.facts.capabilityBlocks`
 * (`NOT_GRANTED` = `_krx`가 VISIBLE이 아님, `KRX_CONTESTED` = `_krx` VISIBLE ∧ phase ≠ OPEN), 발행 이력은 동기 observer.
 * 'premise:'로 시작하는 단언은 HEAD 경로에 도달했는지를 확인한다(contest와 무관한 HEAD 관측값).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessKrxContestTest {

    private val processJob = SupervisorJob()

    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)
    private val pendingFalse = EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0)

    private fun contestTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    private fun TestScope.on(): KrxHarness = krxHarness(processJob, ON)
    private fun TestScope.off(): KrxHarness = krxHarness(processJob, HEAD_FACTORY)

    private fun KrxHarness.view(): KrxContestView = checkNotNull(coordinator.krxContestView) { "스위치 켬이면 view가 있다" }
    private fun KrxHarness.phase(): KrxContestPhase = view().state.phase
    private fun KrxHarness.permit(): Long = view().state.permitSeq
    private fun KrxHarness.streak(): Int = view().state.streak
    private fun KrxHarness.blocks(): Set<TopicAccessBlock> = coordinator.accessSnapshot.facts.capabilityBlocks
    private suspend fun KrxHarness.held(): Int = coordinator.heldLossCandidateCount()
    private suspend fun KrxHarness.owed(): RefreshIntent? = coordinator.recheckDiagnostics().owedIntent

    /** 지금부터 store.load를 모두 실패시키거나(true) 되돌린다(false). 시험이 시각을 골라 후보 복구 라운드를 실패·성공시킨다. */
    private fun KrxHarness.failLoads(on: Boolean) {
        store.loadSkipsBeforeFailure = 0
        store.loadFailures = if (on) Int.MAX_VALUE else 0
    }

    /** 한 바퀴의 앞부분: grant 발급 → KRX 거절(H1). FE는 아직 돌지 않는다. */
    private suspend fun KrxHarness.rejectNow() = rejectKrx(issueGrant())

    /** 한 바퀴: 거절 → FE 답 적용. FE 답 적용 시각(= 즉시 응답하는 fake의 조회 시각)을 돌려준다. */
    private suspend fun TestScope.round(h: KrxHarness): Long {
        h.rejectNow()
        step(100)
        return h.source.calls.last().atMillis
    }

    private fun cooldown(n: Int): Long = KrxContestPolicy.cooldownMillis(n)

    /** 현재 시각을 target까지 정확히 옮긴다(가상 시계). target 시각의 작업도 돈다. */
    private fun TestScope.stepTo(target: Long) {
        val now = testScheduler.currentTime
        require(target >= now) { "target $target < now $now" }
        step(target - now)
    }

    // --- CAP-K01 --------------------------------------------------------------------------------------------------------

    /** CAP-K01: 거절 → FE StableActive(true). H1 결정 공개 직후와 첫 답 뒤. 잡을 변이: 1회차부터 지연, 거절 미연결. */
    @Test fun K01_firstRejectionThenTrue_isPermittedImmediately() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        assertEquals("cold start의 첫 true는 허가(T1)", KrxContestPhase.OPEN, h.phase())
        val before = h.permit()

        h.rejectNow()
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        assertNotNull("barrier = reservation.order", h.view().state.barrier)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertTrue("H1 결정은 _krx를 HIDDEN으로 발행", TopicAccessBlock.NOT_GRANTED in h.blocks())
        assertFalse(TopicAccessBlock.KRX_CONTESTED in h.blocks())
        assertEquals(before, h.permit())

        step(100)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(before + 1, h.permit())
        assertEquals(1, h.streak())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertFalse(TopicAccessBlock.KRX_CONTESTED in h.blocks())
        assertEquals("허가 번호는 snapshot에 노출된다(소비자 없음)", h.permit(), h.coordinator.accessSnapshot.krxPermitSeq)
    }

    // --- CAP-K02 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K02: (거절 → FE true) × 7. 허가는 해당 FE 답 적용 시각에서 0·5·10·20·40·60·60초 뒤다. 보류 중 effective HIDDEN과
     * `KRX_CONTESTED`, 사용자 축(GENERAL)과 premium 상태는 그대로다. 같은 키이면 KRX epoch가 바뀌어도 n이 이어진다(S6-2b-1a 개정: 켬에서는
     * A1로 KRX 회전이 grant 토큰을 바꾸지 않는다 — 토큰이 바뀌는 같은 키 입력과 '토큰 키' 변이는 K03 (iv-b)가 맡는다).
     * 잡을 변이: epoch를 키에 넣음, hold를 RS로, 보류를 사용자 축에 걸음.
     */
    @Test fun K02_sevenRounds_permitOffsetsFollowTheCooldown() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        var permit = h.permit()
        val offsets = mutableListOf<Long>()
        repeat(7) { i ->
            val k = i + 1
            val epochBefore = h.store.record.krxCapabilityEpoch
            val answeredAt = round(h)
            assertTrue("회전으로 KRX epoch가 바뀐다(바퀴 $k)", h.store.record.krxCapabilityEpoch != epochBefore)
            assertEquals("같은 키이면 n이 이어진다(바퀴 $k)", k, h.streak())
            val c = cooldown(k)
            if (c == 0L) {
                assertEquals(KrxContestPhase.OPEN, h.phase())
                offsets += 0L
            } else {
                assertEquals(KrxContestPhase.HOLD, h.phase())
                assertEquals(answeredAt + c, h.view().state.holdUntilMillis)
                assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
                assertTrue(TopicAccessBlock.KRX_CONTESTED in h.blocks())
                assertFalse("보류 중 _krx는 VISIBLE", TopicAccessBlock.NOT_GRANTED in h.blocks())
                assertFalse("보류 중 그래프용 capabilityAllowed는 false", h.coordinator.accessSnapshot.facts.capabilityAllowed)
                assertTrue("보류는 사용자 축(GENERAL)을 막지 않는다", h.coordinator.accessSnapshot.facts.userAllowed)
                assertEquals("보류는 premium 상태를 바꾸지 않는다", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
                stepTo(answeredAt + c - 1)
                assertEquals(KrxContestPhase.HOLD, h.phase())
                assertEquals(permit, h.permit())
                step(1)
                assertEquals(KrxContestPhase.OPEN, h.phase())
                offsets += testScheduler.currentTime - answeredAt
            }
            assertEquals(permit + 1, h.permit())
            assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
            assertFalse(TopicAccessBlock.KRX_CONTESTED in h.blocks())
            permit = h.permit()
        }
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), offsets)
    }

    // --- CAP-K03 --------------------------------------------------------------------------------------------------------

    /** n = 2(HOLD)까지 쌓는다. */
    private suspend fun TestScope.holdAtStreakTwo(h: KrxHarness) {
        round(h)
        assertEquals(1, h.streak())
        round(h)
        assertEquals(2, h.streak())
        assertEquals(KrxContestPhase.HOLD, h.phase())
    }

    /**
     * CAP-K03 (i) 신원 변경(binding·identity·decisionGeneration이 함께 바뀐다, PAC:455-457): KeyChanged로 n·barrier·hold가 비워진다.
     * binding(probeEpoch) 변경은 늘 decisionGeneration 변경과 함께 일어나 이 시험은 셋을 함께 바꾼다. identity는 publishLocked가 uid를 기록
     * 소유자로 쓰므로 generation 변경 없이 바뀔 수도 있다(설계 r8, `survivor_review_r1/` A07).
     */
    @Test fun K03_identityChange_resetsTheContest() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        holdAtStreakTwo(h)
        val keyBefore = h.view().key
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OTHER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        val state = h.view().state
        assertTrue("키가 바뀐다", h.view().key != keyBefore)
        assertEquals("n 초기화", 0, state.streak)
        assertEquals("hold 해제", null, state.holdUntilMillis)
        assertTrue("phase는 OPEN 또는 NONE", state.phase == KrxContestPhase.OPEN || state.phase == KrxContestPhase.NONE)
    }

    /** CAP-K03 (ii) 권위적 상실로 decisionGeneration만 바뀐 뒤 재부여: n이 비워진다(D4 C1 행 'StableInactive는 C0'). */
    @Test fun K03_authoritativeLoss_changesGeneration_andResets() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        holdAtStreakTwo(h)
        val generationBefore = h.view().key?.decisionGeneration
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        assertTrue("decisionGeneration이 바뀐다", h.view().key?.decisionGeneration != generationBefore)
        assertEquals(0, h.streak())
    }

    /**
     * CAP-K03 (iii) userAccessEpoch만 바뀜: 기록의 사용자 축 회전(production AccessEpochTransitions.rotate, 실패한 USER 회전을 복구
     * 라운드가 나중에 착지시키는 경우와 같은 기록 변화)으로 binding·identity·generation은 그대로다. 다음 답에서 KeyChanged.
     * 잡을 변이: 키에서 userAccessEpoch 누락.
     */
    @Test fun K03_userAccessEpochOnly_resetsTheContest() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        holdAtStreakTwo(h)
        val keyBefore = checkNotNull(h.view().key)
        h.store.beginRotation(rotateUser = true, rotateKrx = false)
        assertTrue("premise: 사용자 축 epoch만 바뀐다", h.store.record.userAccessEpoch != keyBefore.userAccessEpoch)
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        val key = checkNotNull(h.view().key)
        assertEquals("binding 그대로", keyBefore.binding, key.binding)
        assertEquals("identity 그대로", keyBefore.identity, key.identity)
        assertEquals("generation 그대로", keyBefore.decisionGeneration, key.decisionGeneration)
        assertTrue("userAccessEpoch만 바뀐 키", key.userAccessEpoch != keyBefore.userAccessEpoch)
        assertEquals("n 초기화", 0, h.streak())
        assertEquals("hold 해제", null, h.view().state.holdUntilMillis)
    }

    /**
     * CAP-K03 (iv-a) (S6-2b-1a 개정, 설계 r2 4절 R1): H1의 KRX 회전은 epoch를 실제로 바꾸지만 A1이라 grant 토큰도 contest 키도 그대로이고
     * n이 이어진다.
     */
    @Test fun K03_krxRotation_keepsTokenKeyAndStreak() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val tokenBefore = h.issueGrant()
        val epochBefore = h.store.record.krxCapabilityEpoch
        round(h)
        assertTrue("premise: H1이 KRX epoch를 회전했다", h.store.record.krxCapabilityEpoch != epochBefore)
        assertEquals("A1: 토큰 그대로", tokenBefore, h.issueGrant())
        val keyAfterFirst = h.view().key
        round(h)
        assertEquals("키는 그대로", keyAfterFirst, h.view().key)
        assertEquals("n이 이어진다", 2, h.streak())
    }

    /**
     * CAP-K03 (iv-b) (S6-2b-1a 개정, 설계 r2 4절 R1): n = 1·OPEN에서 PREMIUM_REQUIRED 거절을 live 판독 실패로 인계하고, 5초 floor 뒤
     * FORCE_PREMIUM의 StableActive(true)로 같은 문맥의 거절 재승인을 만든다. 실제 새 토큰이 나오지만 contest 키와 n은 그대로이고, 다음
     * KRX 거절 → true에서 n = 2다. 잡을 변이: 토큰을 contest 키로 사용(키가 바뀌어 n이 초기화).
     */
    @Test fun K03_refusalReapprovalNewToken_keepsKeyAndStreak() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        round(h)
        assertEquals("premise: n = 1", 1, h.streak())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        val key = h.view().key
        val token = h.issueGrant()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(token, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: 판정 못 한 premium 거절이 FORCE_PREMIUM 요구를 남긴다", RefreshIntent.FORCE_PREMIUM, h.owed())
        step(5_010)   // 5초 floor만 넘긴다. QUIET(600초)를 넘기면 다음 T2에서 n이 0부터 다시 센다
        val result = h.coordinator.topicGrantResult()
        val reissued = checkNotNull(result.fence).grant
        assertTrue("premise: 거절 재승인이 실제 새 토큰을 냈다", reissued != token)
        assertEquals("premise: 재승인의 원래 토큰", token, (result.cause as TopicGrantCause.RefusalReapproval).from)
        assertEquals("새 토큰에도 contest 키 그대로", key, h.view().key)
        assertEquals("새 토큰에도 n 그대로", 1, h.streak())
        round(h)
        assertEquals("다음 KRX 거절 → true에서 n이 이어진다", 2, h.streak())
    }

    /**
     * CAP-K03 (v) identity 단독 변경(A07 반증, `survivor_review_r1/`): 이전 프로세스가 남긴 다른 owner(OTHER) 기록 위에서 OWNER 서명 해제가
     * 복구로 들어가 FinishSignedOut으로 끝나면 봉인이 발행한 _state(OWNER)가 남는다. 그 뒤 인증 없는 답(Unauthenticated)은 owner 검사를
     * 건너뛰어 발행하고, publishLocked가 기록의 uid(OTHER)로 identity만 바꾼다(generation 불변). 키는 그 identity를 따라가야 하고, 그
     * identity로 묶인 질의 후보의 Stale 해제는 같은 키 규칙대로 FE 1을 낸다(설계 3.2 마지막 행, CAP-K14와 같은 뒷부분).
     * 잡을 변이: 키 비교에서 binding·identity 제외(A07).
     */
    @Test fun K03_identityOnlyChangeAtPublish_followsTheKey() = contestTest {
        val h = on()
        h.store.bindOwner(KrxHarnessDefaults.OTHER)   // 앞 프로세스가 남긴 owner(이번 프로세스는 바인딩을 끝낸 적 없음)
        val ownerFence = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
        h.source.liveFence = ownerFence
        val start = h.coordinator.prepareSignOut(ownerFence)
        assertTrue("premise: 기록 owner가 달라 복구로 간다", start is SignOutStart.RecoveryRequired)
        h.source.liveFence = null
        val released = h.coordinator.completeRecovery((start as SignOutStart.RecoveryRequired).ticket, null)
        assertEquals("premise: FinishSignedOut으로 해제", BarrierStep.RELEASED, released.step)

        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT) }
        h.source.unauthenticatedFetches = 1
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val key = checkNotNull(h.view().key)
        assertEquals("premise: 발행이 기록 owner로 identity를 바꾼다", KrxHarnessDefaults.OTHER, h.coordinator.state.value.uid)
        assertEquals("premise: binding은 봉인의 1회 이동 그대로", 1L, key.binding)
        assertEquals("premise: generation은 봉인의 1회 증가 그대로", 1L, key.decisionGeneration)
        assertEquals("키가 발행된 identity를 따라간다", EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L), key.identity)

        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OTHER, 1L)
        h.source.next = { active }
        settleKrx()
        assertEquals("premise: 예약된 재조회가 확정", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals("premise: 확정 답도 키를 바꾸지 않는다(binding·generation·identity 그대로)", key, h.view().key)
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        val permit = h.permit()

        val grant = h.issueGrant()
        h.source.next = { krxFalse }
        h.store.loadSkipsBeforeFailure = 1
        h.store.loadFailures = 1
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: 새 identity로 묶인 질의 후보", 1, h.held())
        assertEquals("premise: 후보 보류 뒤에도 같은 키", key, h.view().key)
        assertNull(h.owed())
        h.store.loadFailures = 1
        h.rejectKrx(grant)
        assertEquals("premise: 토픽 후보까지 2", 2, h.held())
        assertNull("premise: demand 없음", h.owed())
        assertEquals(KrxContestPhase.AWAITING, h.phase())

        h.store.beginRotation(rotateUser = false, rotateKrx = true)
        h.store.loadSkipsBeforeFailure = 1
        h.store.loadFailures = 1
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        step(0)
        assertEquals("premise: 질의 후보만 해제", 1, h.held())
        assertEquals("같은 키 Stale 해제의 FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())
        assertEquals("FE 정확히 1, 그 자리에서", sent + 1, h.source.calls.size)
        assertEquals(KrxContestPhase.AWAITING, h.phase())

        followUp.complete(active)
        step(0)
        assertEquals("그 FE의 신선한 true에서 T2", KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals("FE는 그 하나뿐", sent + 1, h.source.calls.size)
    }

    // --- CAP-K04 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K04: 거절 전에 시작한 CALLER Q0의 true / false는 S1이다. 허가 0, n 불변. 거절은 회전 없는 H1′이라 Q0 답이 fence 검사
     * (PAC:1957)를 통과해 contest 판정에 도달한다. 잡을 변이: barrier 검사 누락(Q0로 허가·C1).
     */
    @Test fun K04_callerStartedBeforeRejection_isStale_true() = contestTest { staleCaller(active) }

    @Test fun K04_callerStartedBeforeRejection_isStale_false() = contestTest { staleCaller(krxFalse) }

    private suspend fun TestScope.staleCaller(answer: EntitlementsOutcome) {
        val h = on()
        h.grantedKrxVisible()
        val q0 = CompletableDeferred<EntitlementsOutcome>()
        val fe = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(q0)
        h.source.gates.addLast(fe)
        val caller = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        step(0)
        val reservation = h.undecidedRejection()
        assertEquals("barrier = reservation.order", reservation.order, h.view().state.barrier)
        val permit = h.permit()
        val streak = h.streak()
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        q0.complete(answer)
        step(0)
        assertEquals("Q0 답은 S1: 허가 0", permit, h.permit())
        assertEquals("n 불변", streak, h.streak())
        assertEquals("S1은 phase를 바꾸지 않는다", KrxContestPhase.AWAITING, h.phase())
        assertFalse("Q0 적용 직후에도 capabilityAllowed는 false", h.coordinator.accessSnapshot.facts.capabilityAllowed)
        fe.complete(active)
        step(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
        assertEquals("뒤이은 신선한 FE true는 허가", permit + 1, h.permit())
        caller.join()
    }

    // --- CAP-K05 --------------------------------------------------------------------------------------------------------

    /** CAP-K05: n = 4 HOLD 중 신선한 stable false → 보류 취소·허가 0·n = 0 → 신선한 true에서 즉시 허가. 잡을 변이: C1 미초기화. */
    @Test fun K05_freshFalseDuringHold_resets_thenTrueOpensImmediately() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        repeat(3) { i ->
            val at = round(h)
            val c = cooldown(i + 1)
            if (c > 0) stepTo(at + c)
        }
        round(h)
        assertEquals(4, h.streak())
        assertEquals(KrxContestPhase.HOLD, h.phase())
        val permit = h.permit()

        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals(KrxContestPhase.NONE, h.phase())
        assertEquals(0, h.streak())
        assertEquals(null, h.view().state.holdUntilMillis)
        assertEquals(permit, h.permit())

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals("T1은 n을 올리지 않는다", 0, h.streak())
    }

    // --- CAP-K06 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K06: HOLD 중 CALLER FORCE_PREMIUM은 즉시 송신되고, RS attempts·floor·intent가 스위치 꺼짐 대조와 같다. userBlocks·
     * tokenStanding·grant 토큰도 HOLD가 바꾸지 않는다. 잡을 변이: hold를 RS floor로 구현.
     */
    @Test fun K06_callerDuringHold_isSentAtOnce_andRsMatchesOffRun() = contestTest {
        val on = on()
        val off = off()
        for (h in listOf(on, off)) h.grantedKrxVisible()
        for (h in listOf(on, off)) round(h)
        for (h in listOf(on, off)) round(h)
        assertEquals(KrxContestPhase.HOLD, on.phase())
        val grantBefore = on.issueGrant()   // 꺼짐(HEAD)에서는 발급이 tokenStanding을 바꿀 수 있어(H1 회전 뒤 낡은 문맥, PAC:1695-1699) 먼저 한다. 켬은 A1로 KRX 회전이 문맥을 바꾸지 않는다(S6-2b-1a)
        val factsBefore = on.coordinator.accessSnapshot.facts

        val sentBefore = on.source.calls.size to off.source.calls.size
        on.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        off.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(0)
        assertEquals("HOLD 중에도 즉시 송신", sentBefore.first + 1, on.source.calls.size)
        assertEquals(sentBefore.second + 1, off.source.calls.size)
        assertEquals("같은 시각", off.source.calls.last().atMillis, on.source.calls.last().atMillis)
        assertFalse(on.recheckSchedule().floorBlocksNow(testScheduler.currentTime))
        for (field in listOf("consecutiveAttempts", "earliestAllowedAtMillis", "pendingIntent")) {
            assertEquals("RS ${field}는 꺼짐 대조와 같다", off.recheckSchedule().readPrivate(field), on.recheckSchedule().readPrivate(field))
        }
        assertEquals("userBlocks 불변", factsBefore.userBlocks, on.coordinator.accessSnapshot.facts.userBlocks)
        assertEquals("tokenStanding 불변", factsBefore.tokenStanding, on.coordinator.accessSnapshot.facts.tokenStanding)
        assertEquals("grant 토큰 불변", grantBefore, on.issueGrant())
    }

    // --- CAP-K07 --------------------------------------------------------------------------------------------------------

    /** CAP-K07: demand 없는 HOLD 전 구간과 만료에서 /api/ 0, 만료에 허가 1(X1). 잡을 변이: 타이머가 조회를 냄. */
    @Test fun K07_holdTimer_sendsNothing() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        round(h)
        val at = round(h)
        assertEquals(KrxContestPhase.HOLD, h.phase())
        val permit = h.permit()
        val sent = h.source.calls.size
        assertTrue("FE 답이 demand를 정산했다", !h.recheckSchedule().isArmed)
        stepTo(at + cooldown(2) - 1)
        assertEquals("보류 중 /api/ 0", sent, h.source.calls.size)
        step(1)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals("만료에도 /api/ 0", sent, h.source.calls.size)
        settleKrx()
        assertEquals(sent, h.source.calls.size)
    }

    // --- CAP-K09 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K09 (a) live 판독 실패: AWAITING(barrier = reservation.order), FE는 HEAD대로 5초 floor 뒤, n 불변. 뒤이은 신선한 true에서
     * 허가 1, n은 그 T2에서만 +1. 잡을 변이: 분기에서 n 증가, OPEN에 남음.
     */
    @Test fun K09_liveReadFailure_entersAwaitingWithoutStepping() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val reservation = h.undecidedRejection()
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        assertEquals(reservation.order, h.view().state.barrier)
        assertEquals("n 불변", 0, h.streak())
        assertEquals("미결정이라 _krx는 VISIBLE, phase ≠ OPEN이므로 KRX_CONTESTED", true, TopicAccessBlock.KRX_CONTESTED in h.blocks())
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        val sent = h.source.calls.size
        step(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS - 1)
        assertEquals("floor 전에는 FE 없음", sent, h.source.calls.size)
        step(1)
        assertEquals(sent + 1, h.source.calls.size)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals(1, h.streak())
    }

    /** CAP-K09 (b) store.load 실패 토픽 후보 보류: AWAITING, 후보 복구가 FE를 만든다. 뒤이은 true에서 허가 1, n은 T2에서만 +1. */
    @Test fun K09_storeLoadFailure_entersAwaitingWithoutStepping() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val grant = h.issueGrant()
        h.store.loadFailures = 1
        h.rejectKrx(grant)
        assertEquals("premise: 토픽 후보 보류(PAC:1833-1837)", 1, h.held())
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        assertEquals(0, h.streak())
        settleKrx()
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals("후보 결정이 다시 숨김 근거가 돼도 n은 T2에서 한 번만 오른다", 1, h.streak())
    }

    // CAP-K09 (c) '승인 닫힘 뒤 같은 binding 재개방'은 설계 r3 4.1에서 도달 불가로 정리했다(prepareSignOut의 seal이 generation·
    // probeEpoch를 바꾸고 복구 binding도 다시 바꾼다, PAC:583·:652-654·:1234-1236). 키가 바뀌므로 C0 경로이고, 같은 키의 reopenOwed는 2b-1 몫이다.

    /**
     * CAP-K09 (d) 옛 키 grant의 승인 닫힘 거절(r7, 구현 검토 F1, D4 H1′·I8): 같은 uid 새 auth generation 바인딩의 READ가 실패해 식별자
     * 저장 보류(접근 닫힘)인 동안 옛 키에서 발급된 grant의 KRX 거절이 오면 contest를 바꾸지 않는다(같은 키에서 끝나는 거절만 숨김 근거).
     * 보류가 풀린 뒤 새 바인딩의 첫 신선한 true는 T1이라 n이 오르지 않는다. 잡을 변이: 승인 닫힘 분기에서 키 확인 없이 HideEvidence.
     */
    @Test fun K09_oldKeyRefusalWhileAccessIsClosed_changesNothing() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val oldGrant = h.issueGrant()
        val before = h.view().state
        assertEquals(KrxContestPhase.OPEN, before.phase)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.store.loadFailures = 1   // 새 바인딩의 READ 실패 → 식별자 저장 보류
        val held = h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 2L)).heldByPersistence()
        assertTrue("premise: 저장 보류로 접근이 닫혔다", TopicAccessBlock.PERSISTENCE_HOLD in h.coordinator.accessSnapshot.facts.userBlocks)

        h.rejectKrx(oldGrant)
        assertNotEquals("옛 키 거절은 AWAITING을 만들지 않는다", KrxContestPhase.AWAITING, h.phase())
        assertEquals("barrier 불변", before.barrier, h.view().state.barrier)

        val due = checkNotNull(held.nextAttemptAt) { "premise: 자동 회차가 예약됐다" }
        advanceTimeBy(due - testScheduler.currentTime)
        h.coordinator.resumePersistence(held.id).applied("보류 재개")
        assertFalse("premise: 보류 해제", TopicAccessBlock.PERSISTENCE_HOLD in h.coordinator.accessSnapshot.facts.userBlocks)
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals("새 바인딩의 첫 true는 T1: n 불변", 0, h.streak())
        assertEquals(KrxContestPhase.OPEN, h.phase())
    }

    // --- CAP-K10 --------------------------------------------------------------------------------------------------------

    /** CAP-K10: L1c 꼴, CALLER refresh(FORCE_ENTITLEMENTS) 6회 순차. 조회 6건, grant 유지, 허가 변화 없음. */
    @Test fun K10_l1cShape_sixSequentialCallerQueries() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val permit = h.permit()
        val sent = h.source.calls.size
        repeat(6) {
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            step(0)
        }
        assertEquals(sent + 6, h.source.calls.size)
        assertEquals(grant, h.issueGrant())
        assertEquals(permit, h.permit())
        assertEquals(KrxContestPhase.OPEN, h.phase())
    }

    // --- CAP-K12 --------------------------------------------------------------------------------------------------------

    /** CAP-K12: 회전 쓰기 실패(sealed) 뒤 신선한 true는 시작 순번 기준으로 허가된다. effective는 seal 동안 숨김, 해제 뒤 노출. */
    @Test fun K12_sealedRotationFailure_permitsByOrder() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        h.store.rotationFailures = 2
        round(h)
        assertEquals("seal 중 true도 T2로 허가", permit + 1, h.permit())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals("seal이 _krx를 숨긴다", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertFalse("_krx HIDDEN이면 KRX_CONTESTED 아님", TopicAccessBlock.KRX_CONTESTED in h.blocks())
        settleKrx()
        assertEquals("해제 뒤 재승인 답으로 노출", KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertEquals(permit + 1, h.permit())
    }

    // --- CAP-K13 --------------------------------------------------------------------------------------------------------
    //
    // D4 CAP-K13의 진행 중 CALLER FP 뒤 SCHEDULED 재조회 시작은 HEAD의 inFlightCovers 가드(PAC:1551-1556)로 불가능하므로, CALLER FE로
    // barrier를 올리는 동등 순서로 대체한다(설계 r3 4.1). floor 만료 시각까지만 옮기고(그 시각의 예약 작업은 아직 돌지 않는다)
    // CALLER FP를 먼저 시작한 뒤 CALLER FE의 Pending(false)로 barrier를 FP 시작 위로 올린다. (i) H1 결정 (ii) H1′(held-back FE)
    // (iv) KRX 거절 없는 NONE→H2를 구별하고, (iii)은 토픽 후보 Stale 해제와 O-7 대조로 잠근다.

    /**
     * floor 만료 시각으로 옮겨 CALLER [intent](gate)를 먼저 시작하고, CALLER FE의 Pending(false)로 barrier를 그 시작 위로 올린다.
     * 반환: (CALLER job, 그 조회 순번).
     */
    private suspend fun TestScope.callerThenBarrierAtFloor(
        h: KrxHarness,
        gate: CompletableDeferred<EntitlementsOutcome>,
        intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM
    ): Pair<Job, Long> {
        val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "premise: floor가 있다" }
        advanceTimeBy(floor - testScheduler.currentTime)
        h.source.gates.addLast(gate)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(intent) }
        val order = h.coordinator.recheckDiagnostics().inFlightOrders.single()
        val beforeCallerFe = h.source.calls.size
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: CALLER FE가 즉시 시작해 답한다", beforeCallerFe + 1, h.source.calls.size)
        assertTrue("CALLER FE Pending(false)가 barrier를 CALLER 시작 위로 올린다", checkNotNull(h.view().state.barrier) > order)
        return job to order
    }

    /** S1 CALLER 답 도착 → 같은 잠금 구간의 후속 FE 정확히 1 → 그 신선한 true에서 T2 → 더 이상의 조회 없음. */
    private suspend fun TestScope.staleAnswerThenFollowUp(h: KrxHarness, gate: CompletableDeferred<EntitlementsOutcome>, permit: Long, streak: Int) {
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        h.source.next = { active }
        val sentBeforeStale = h.source.calls.size

        gate.complete(active)
        step(0)

        assertEquals("CALLER 답은 S1: 허가 0", permit, h.permit())
        assertEquals("n 불변", streak, h.streak())
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        assertEquals("후속 FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())
        assertEquals("후속 FE 정확히 1", sentBeforeStale + 1, h.source.calls.size)
        assertFalse(h.source.calls.last().freshPremium)

        followUp.complete(active)
        step(0)
        assertEquals("후속 FE의 신선한 true에서 T2", permit + 1, h.permit())
        assertEquals(streak + 1, h.streak())
        assertEquals(KrxContestPhase.OPEN, h.phase())

        val sent = h.source.calls.size
        settleKrx()
        assertEquals(sent, h.source.calls.size)
    }

    /**
     * CAP-K13 (i): H1 → FE Pending(false)(H2) → floor 만료에 CALLER FP 시작 → CALLER FE Pending(false)(barrier > FP) → FP true(S1,
     * demand 정산) → 후속 FE 1 → T2. 잡을 변이: S1을 HEAD 정산과 따로 둠(AWAITING 고착), 오래된 답으로 허가.
     */
    @Test fun K13_staleAnswerSettlingTheDemand_keepsAFollowUpFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val streak = h.streak()
        h.source.next = { pendingFalse }
        h.rejectNow()
        step(100)
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        val (fp, _) = callerThenBarrierAtFloor(h, gate)
        staleAnswerThenFollowUp(h, gate, permit, streak)
        fp.join()
    }

    /**
     * CAP-K13 (ii): premium 거절의 live 판독 실패(FORCE_PREMIUM 요구, 5초 floor) → 1초 뒤 KRX H1 결정(회전) → 그 인계 FE가 RS floor로 held
     * back → floor 만료에 CALLER FP 먼저 → CALLER FE Pending(false) → FP true(S1, demand 정산) → 후속 FE 1. KRX H1′만으로는 만들 수 없다:
     * 회전이 없어 KRX 자료 표지가 남고, CALLER FE의 Pending(false)가 HEAD false edge로 KRX epoch를 회전시켜 먼저 시작한 FP 답이
     * fence(PAC:1957)로 버려진다(일회용 HEAD 탐침으로 확인).
     * (S6-2b-1a 개정, 설계 r2 4절: floor를 만드는 앞 거절을 KRX H1′에서 premium H1′로 바꿨다. 같은 허가의 두 번째 KRX 거절은 허가 판정으로
     * 부적격이라 H1 결정이 나지 않는다. premium 거절은 contest와 KRX 허가를 건드리지 않으므로 0b·2b-1a 모두에서 같은 입력이다.)
     */
    @Test fun K13_heldBackFe_staleAnswer_keepsAFollowUpFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val streak = h.streak()
        val premiumGrant = h.issueGrant()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(premiumGrant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: premium H1′은 FORCE_PREMIUM 요구", RefreshIntent.FORCE_PREMIUM, h.owed())
        assertEquals("premise: premium 거절은 contest를 건드리지 않는다", KrxContestPhase.OPEN, h.phase())
        step(1_000)
        val sent = h.source.calls.size
        h.rejectNow()
        step(0)
        assertEquals("premise: 인계 FE가 RS floor로 held back", sent, h.source.calls.size)
        assertFalse("premise: H1 회전 뒤 KRX 자료 표지가 없어 뒤의 Pending(false)가 다시 회전하지 않는다", h.store.record.mayContainKrxData)
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        val (fp, _) = callerThenBarrierAtFloor(h, gate)
        staleAnswerThenFollowUp(h, gate, permit, streak)
        fp.join()
    }

    /**
     * CAP-K13 (iv): KRX 거절 없이 NONE(신선한 false의 C1)에서 CALLER FP의 Pending(false)로 H2 → floor 만료에 CALLER FP 먼저 →
     * CALLER FE Pending(false) → FP true(S1) → 후속 FE 1 → T2.
     */
    @Test fun K13_noRejection_pendingEntry_keepsAFollowUpFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals(KrxContestPhase.NONE, h.phase())
        val permit = h.permit()
        val streak = h.streak()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals("KRX 거절 없는 H2", KrxContestPhase.AWAITING, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        val (fp, _) = callerThenBarrierAtFloor(h, gate)
        staleAnswerThenFollowUp(h, gate, permit, streak)
        fp.join()
    }

    // --- CAP-K13 (iii) · O-7 ------------------------------------------------------------------------------------------

    /**
     * K13 (iv)처럼 KRX 거절 없는 H2로 AWAITING + 토픽 후보(현재 grant의 거절, store.load 실패, PAC:1833-1837). 첫 복구 라운드는 신원 미확인으로
     * 후보를 남긴다. (S6-2b-1a 개정, 설계 r2 4절: 앞부분을 H1에서 H2로 바꿨다. H1으로 들어가면 뒤의 거절이 같은 허가의 두 번째 거절이라
     * 허가 판정으로 부적격이 되어 후보가 생기지 않는다. H2는 허가를 인계하지 않으므로 이 거절이 첫 인계다 — 0b·2b-1a 모두 같은 입력.)
     */
    private suspend fun TestScope.topicCandidateDuringStaleCaller(h: KrxHarness, callerIntent: RefreshIntent): Pair<Job, CompletableDeferred<EntitlementsOutcome>> {
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        step(100)
        assertEquals("premise: KRX 거절 없는 H2", KrxContestPhase.AWAITING, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        val (caller, _) = callerThenBarrierAtFloor(h, gate, callerIntent)
        val grant = h.issueGrant()
        h.store.loadFailures = 1
        h.source.identityNulls = 1   // 첫 복구 라운드의 신원 판독(PAC:2813)이 null → IdentityUnknown으로 후보 유지
        h.rejectKrx(grant)
        assertEquals("premise: 토픽 후보 보류", 1, h.held())
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        return caller to gate
    }

    /**
     * CAP-K13 (iii) = O-7 Stale: 토픽 후보가 남은 동안 S1 CALLER FP true가 demand를 정산 → 후속 FE 0 → 후보가 같은 키 Stale로 해제
     * (복구 라운드의 live 신원이 문맥과 다름 → 후보 검사의 마지막 분기 → 해제) → 후속 FE 정확히 1 → T2. 잡을 변이: 후보 중에도 FE를 냄,
     * 해제 뒤 FE 없음. (S6-2b-1a 개정, 설계 r2 3.2 A1-4: 켬에서는 토픽 후보의 fence 비교가 KRX epoch를 빼므로 capability epoch만 옮기면
     * Stale이 되지 않는다. 같은 키 Stale을 live 신원 불일치로 만든다 — 0b·2b-1a 모두 같은 분기.)
     */
    @Test fun O7_topicCandidate_staleRelease_followUpExactlyOnce() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val streak = h.streak()
        val (fp, gate) = topicCandidateDuringStaleCaller(h, RefreshIntent.FORCE_PREMIUM)
        h.source.next = { active }
        val sent = h.source.calls.size

        gate.complete(active)
        step(0)
        assertEquals("S1: 허가 0", permit, h.permit())
        assertEquals("premise: 후보가 남아 있다", 1, h.held())
        assertEquals("O-7: 후보가 남은 동안 S1 정산 뒤 FE 0", sent, h.source.calls.size)
        assertNull("후속 FE 요구 없음", h.owed())

        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val bound = h.source.identity
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        step(KrxHarnessDefaults.RETRY)
        h.source.identity = bound
        assertEquals("premise: 후보 해제", 0, h.held())
        assertEquals("O-7: 해제 뒤 후속 FE 정확히 1", sent + 1, h.source.calls.size)
        assertFalse(h.source.calls.last().freshPremium)
        followUp.complete(active)
        step(0)
        assertEquals(permit + 1, h.permit())
        assertEquals(streak + 1, h.streak())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        settleKrx()
        assertEquals(sent + 1, h.source.calls.size)
        fp.join()
    }

    /**
     * O-7 결정: 토픽 후보가 남은 동안 S1 CALLER FE true가 FE demand를 정산(FE라 grant를 다시 발급하지 않는다) → FE 0 → 후보 복구
     * 결정(HEAD가 FE 재조회를 요구, PAC:2853-2858) → FE 정확히 1(후속 FE 규칙이 하나 더 얹지 않는다) → T2.
     */
    @Test fun O7_topicCandidate_decision_followUpExactlyOnce() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val (fe, gate) = topicCandidateDuringStaleCaller(h, RefreshIntent.FORCE_ENTITLEMENTS)
        h.source.next = { active }
        val sent = h.source.calls.size

        gate.complete(active)
        step(0)
        assertEquals(permit, h.permit())
        assertEquals("premise: 후보가 남아 있다", 1, h.held())
        assertEquals("O-7: 후보가 남은 동안 FE 0", sent, h.source.calls.size)

        step(KrxHarnessDefaults.RETRY)
        assertEquals("premise: 후보 결정", 0, h.held())
        assertEquals("O-7: 결정 뒤 FE 정확히 1", sent + 1, h.source.calls.size)
        assertEquals(permit + 1, h.permit())
        settleKrx()
        assertEquals(sent + 1, h.source.calls.size)
        fe.join()
    }

    // --- CAP-K14 --------------------------------------------------------------------------------------------------------

    /**
     * CAP-K14: OPEN(p) → CALLER의 StableActive(false)가 답 적용의 store.load 실패(PAC:1944)로 질의 후보 → 보류 중 contest 불변 →
     * 복구 결정에서 C1(NONE, n = 0) → 재부여 뒤 신선한 true에서 T1(허가 p+1). KRX subscribe 단언은 2b-2에서 완결한다.
     */
    @Test fun K14_queryCandidateDecision_appliesC1_thenTrueIssuesT1() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val streak = h.streak()
        h.source.next = { krxFalse }
        h.store.loadSkipsBeforeFailure = 1   // 조회 시작의 load(PAC:1572)는 통과
        h.store.loadFailures = 1             // 답 적용의 load만 실패
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("premise: 질의 후보", 1, h.held())
        assertEquals("보류 중 contest 불변", KrxContestPhase.OPEN, h.phase())
        assertEquals(permit, h.permit())
        assertEquals(streak, h.streak())
        step(0)   // 첫 복구 라운드가 곧바로 결정한다(PAC:2376 → :2798)
        assertEquals("premise: 후보 결정", 0, h.held())
        assertEquals("질의 후보 결정에서 C1", KrxContestPhase.NONE, h.phase())
        assertEquals(0, h.streak())
        assertEquals(permit, h.permit())
        settleKrx()
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        step(100)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
    }

    /** CAP-K14 변형: 질의 후보가 Pending(false)이면 보류 중 불변, 결정에서 H2(AWAITING, n 불변). */
    @Test fun K14_pendingFalseCandidate_appliesH2() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        h.store.loadSkipsBeforeFailure = 1
        h.store.loadFailures = 1
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("premise: 질의 후보", 1, h.held())
        assertEquals("보류 중 contest 불변", KrxContestPhase.OPEN, h.phase())
        step(0)
        assertEquals("premise: 후보 결정", 0, h.held())
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        assertEquals(0, h.streak())
        assertEquals(permit, h.permit())
    }

    /**
     * CAP-K14 Stale 변형(demand 있음): H1 → FE의 StableActive(false)가 질의 후보 → HEAD가 조회 끝에 정산되지 않은 demand를 다시 무장 →
     * capability epoch만 옮겨 후보를 같은 키 Stale로 해제(PAC:2807 → :2792) → AWAITING 유지·무장 유지 → 재조회 true에서 T2.
     * demand 없음 변형은 바로 아래 시험이다.
     */
    @Test fun K14_queryCandidateStale_withDemand_staysArmed() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        h.source.next = { krxFalse }
        h.rejectNow()
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        val t = testScheduler.currentTime
        h.store.loadSkipsBeforeFailure = 1   // FE 시작의 load는 통과
        h.store.loadFailures = Int.MAX_VALUE // 답 적용과 복구 라운드는 실패 → 후보 유지
        step(0)
        assertEquals("premise: FE 답이 질의 후보", 1, h.held())
        assertTrue("premise: HEAD가 조회 끝에 demand를 다시 무장", h.recheckSchedule().isArmed)
        assertEquals(KrxContestPhase.AWAITING, h.phase())

        stepTo(t + KrxHarnessDefaults.RETRY - 1)
        h.failLoads(false)
        h.store.beginRotation(rotateUser = false, rotateKrx = true)
        h.source.next = { active }
        step(1)
        assertEquals("premise: 후보가 Stale로 해제", 0, h.held())
        assertEquals("Stale 해제는 contest 불변", KrxContestPhase.AWAITING, h.phase())
        assertEquals(permit, h.permit())
        assertTrue("demand가 무장돼 있다", h.recheckSchedule().isArmed)
        settleKrx()
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
    }

    /**
     * CAP-K14 Stale 변형(demand 없음, Codex 시험 검토 r2 1): OPEN에서 CALLER FE false가 질의 후보(demand 없음, PAC:2696) → 저장한 grant의
     * 토픽 거절 store.load 실패로 토픽 후보(H1′, demand 없음, PAC:1833-1837) → AWAITING·demand 없음 → capability epoch만 회전 → 복구
     * 라운드가 오래된 질의 후보부터 Stale 해제(PAC:2765-2797)하고 토픽 후보 판독은 실패해 남김 → 토픽 후보가 남은 상태에서 후속 FE
     * 요구·조회 정확히 1 → FE true에서 T2 → 토픽 후보의 뒤이은 해제(OPEN)는 조회를 더하지 않는다. 두 후보를 한꺼번에 해제하면 O-7의
     * 토픽 후보 후속이 질의 후보 분기 누락을 가릴 수 있어 나눈다. 잡을 변이: 질의 후보 Stale 해제 뒤 AWAITING·demand 없음에서 FE 없음.
     */
    @Test fun K14_queryCandidateStale_withoutDemand_raisesOneFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val permit = h.permit()
        val streak = h.streak()
        val grant = h.issueGrant()
        h.source.next = { krxFalse }
        h.store.loadSkipsBeforeFailure = 1   // CALLER FE 시작의 load는 통과
        h.store.loadFailures = 1             // 답 적용의 load만 실패 → 질의 후보
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: 질의 후보", 1, h.held())
        assertNull("premise: demand 없음", h.owed())
        assertEquals(KrxContestPhase.OPEN, h.phase())

        h.store.loadFailures = 1             // 복구 작업이 돌기 전에, 토픽 거절의 load를 실패시킨다
        h.rejectKrx(grant)
        assertEquals("premise: 토픽 후보까지 2", 2, h.held())
        assertNull("premise: demand 없음", h.owed())
        assertEquals(KrxContestPhase.AWAITING, h.phase())

        h.store.beginRotation(rotateUser = false, rotateKrx = true)
        h.store.loadSkipsBeforeFailure = 1   // 오래된 질의 후보의 판독은 성공(Stale) …
        h.store.loadFailures = 1             // … 다음 토픽 후보의 판독은 실패
        val followUp = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(followUp)
        val sent = h.source.calls.size
        step(0)
        assertEquals("premise: 질의 후보만 해제, 토픽 후보는 남음", 1, h.held())
        assertEquals("후속 FE 요구", RefreshIntent.FORCE_ENTITLEMENTS, h.owed())
        assertEquals("후속 FE 정확히 1", sent + 1, h.source.calls.size)
        assertFalse(h.source.calls.last().freshPremium)
        assertEquals("Stale 해제는 contest 불변", KrxContestPhase.AWAITING, h.phase())
        assertEquals(permit, h.permit())

        followUp.complete(active)
        step(0)
        assertEquals("후속 FE의 신선한 true에서 T2", KrxContestPhase.OPEN, h.phase())
        assertEquals(permit + 1, h.permit())
        assertEquals(streak + 1, h.streak())
        settleKrx()
        assertEquals("premise: 토픽 후보도 해제", 0, h.held())
        assertEquals("토픽 후보 해제는 조회를 더하지 않는다", sent + 1, h.source.calls.size)
    }

    /**
     * 늦은 S1 질의 후보 앞부분: n = 1 OPEN에서 오래된 CALLER FE(false) 시작 → H1′ → 그 false의 적용 load 실패로 질의 후보(q ≤ barrier)
     * → FE(floor 5초)의 신선한 true가 T2로 HOLD(n = 2). 후보는 load 실패가 계속돼 남는다. 반환: (CALLER job, 후보 보류 시각).
     */
    private suspend fun TestScope.lateStaleCandidateThenHold(h: KrxHarness): Pair<Job, Long> {
        round(h)
        assertEquals(1, h.streak())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val q0 = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        h.undecidedRejection()
        assertEquals(KrxContestPhase.AWAITING, h.phase())
        val t = testScheduler.currentTime
        h.failLoads(true)
        gate.complete(krxFalse)
        step(0)
        assertEquals("premise: 오래된 false가 질의 후보", 1, h.held())
        assertEquals("보류 중 contest 불변", KrxContestPhase.AWAITING, h.phase())
        stepTo(t + PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS - 1)
        h.failLoads(false)
        h.source.next = { active }
        step(1)
        assertEquals("premise: 후보가 아직 남아 있다", 1, h.held())
        assertEquals("FE 신선한 true는 T2", KrxContestPhase.HOLD, h.phase())
        assertEquals(2, h.streak())
        return q0 to t
    }

    /**
     * CAP-K14 늦은 S1 후보 HOLD 변형: 후보가 HOLD 중 복구 결정(+7초 라운드) → S1이라 contest 전이 없음, HEAD false edge·회전 유지 →
     * 같은 잠금 구간에서 후속 FE 정확히 1 → 그 true로 `_krx` VISIBLE → 만료에 허가. 외부 조회 없이 HOLD·HIDDEN에 고착되지 않는다.
     * 잡을 변이: 질의 후보 결정에 contest 전이 없음(고착), 후속 FE 누락, S1 후보로 C1.
     */
    @Test fun K14_lateStaleCandidate_decidedDuringHold_recoversWithOneFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val (q0, t) = lateStaleCandidateThenHold(h)
        val permit = h.permit()
        val holdUntil = checkNotNull(h.view().state.holdUntilMillis)
        val epoch = h.store.record.krxCapabilityEpoch
        val sent = h.source.calls.size
        stepTo(t + 7_000)
        assertEquals("premise: 후보 결정", 0, h.held())
        assertTrue("HEAD false edge의 회전 유지", h.store.record.krxCapabilityEpoch != epoch)
        assertEquals("S1 후보: 전이 없음", KrxContestPhase.HOLD, h.phase())
        assertEquals("S1 후보: n 불변", 2, h.streak())
        assertEquals("후속 FE 정확히 1", sent + 1, h.source.calls.size)
        assertFalse(h.source.calls.last().freshPremium)
        assertEquals(permit, h.permit())
        stepTo(holdUntil)
        assertEquals(KrxContestPhase.OPEN, h.phase())
        assertEquals("만료에 허가", permit + 1, h.permit())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        settleKrx()
        assertEquals("외부 조회 없이 복귀", sent + 1, h.source.calls.size)
        q0.join()
    }

    /**
     * CAP-K14 늦은 S1 후보 OPEN 변형: 후보 복구를 hold 만료 뒤(+15초 라운드)로 미룸 → 만료에 허가(OPEN) → 결정이 S1이라 OPEN 유지,
     * HEAD false edge로 `_krx` HIDDEN → 후속 FE 정확히 1 → true로 VISIBLE. 허가는 늘지 않는다(T3). OPEN·HIDDEN에 고착되지 않는다.
     */
    @Test fun K14_lateStaleCandidate_decidedAfterExpiry_recoversWithOneFe() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val (q0, t) = lateStaleCandidateThenHold(h)
        val holdUntil = checkNotNull(h.view().state.holdUntilMillis)
        h.failLoads(true)
        stepTo(holdUntil)
        assertEquals("premise: 후보가 아직 남아 있다", 1, h.held())
        assertEquals(KrxContestPhase.OPEN, h.phase())
        val permit = h.permit()
        val sent = h.source.calls.size
        stepTo(t + 15_000 - 1)
        h.failLoads(false)
        step(1)
        assertEquals("premise: 후보 결정", 0, h.held())
        assertEquals("S1 후보: OPEN 유지", KrxContestPhase.OPEN, h.phase())
        assertEquals("후속 FE 정확히 1", sent + 1, h.source.calls.size)
        assertEquals("허가는 늘지 않는다", permit, h.permit())
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        settleKrx()
        assertEquals("외부 조회 없이 복귀", sent + 1, h.source.calls.size)
        q0.join()
    }

    // --- CAP-G01 --------------------------------------------------------------------------------------------------------

    private data class Published(val epoch: String?, val allowed: Boolean)

    private fun KrxHarness.recordPublished(): MutableList<Published> {
        val out = mutableListOf<Published>()
        coordinator.onAccessPublishedForTest = { s -> out += Published(s.facts.recordFence?.krxCapabilityEpoch, s.facts.capabilityAllowed) }
        return out
    }

    private fun KrxHarness.currentPublished(): Published =
        Published(coordinator.accessSnapshot.facts.recordFence?.krxCapabilityEpoch, coordinator.accessSnapshot.facts.capabilityAllowed)

    private fun transitions(seq: List<Published>): Pair<Int, Int> {
        var falls = 0
        var rises = 0
        seq.zipWithNext().forEach { (a, b) ->
            if (a.allowed && !b.allowed) falls += 1
            if (!a.allowed && b.allowed) rises += 1
        }
        return falls to rises
    }

    /**
     * CAP-G01: (거절 → FE true) × 4. 발행 이력에서 capabilityAllowed의 false 전이는 바퀴당 1회, true 전이는 바퀴당 1회. epoch
     * 변화는 allowed false 구간 안에서만. 잡을 변이: KRX_CONTESTED를 HOLD 한정 파생, 전이 뒤 snapshot 미발행, block 누락.
     */
    @Test fun G01_eachRoundFlipsAllowedOnceEachWay() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val published = h.recordPublished()
        val start = h.currentPublished()
        assertTrue(start.allowed)
        repeat(4) { i ->
            val at = round(h)
            val c = cooldown(i + 1)
            if (c > 0) stepTo(at + c)
        }
        val seq = listOf(start) + published
        assertEquals("false·true 전이 각 4회", 4 to 4, transitions(seq))
        seq.zipWithNext().forEach { (a, b) ->
            if (a.epoch != b.epoch) assertFalse("epoch 변화는 allowed false 구간 안에서만", a.allowed || b.allowed)
        }
    }

    /**
     * CAP-G01 HOLD 중 true 반복: 만료 전 CALLER true 두 번은 조회·적용되지만 allowed 전이는 0이다. 변화 없는 재계산은 발행하지 않으므로
     * (PAC:2608, TAS:126-127) 그 두 답의 발행 0건도 허용한다. 관측기가 allowed false 구간의 발행을 받는지는 HOLD 진입 발행으로 따로 확인한다.
     * (S6-2b-1a 개정, 설계 r2 4절: 켬에서는 A1로 KRX 회전이 grant 문맥을 바꾸지 않아 HOLD 중 발급이 토큰 교체 발행을 내지 않는다. 그래서
     * 관측기를 둘째 바퀴의 거절 뒤·FE 답 전에 달고, T2로 HOLD에 들어가는 발행(allowed false 유지)을 관측 확인에 쓴다.)
     */
    @Test fun G01_trueRepeatsDuringHold_noTransitions() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        round(h)
        h.rejectNow()
        val published = h.recordPublished()
        val start = h.currentPublished()
        assertFalse("premise: 둘째 거절 뒤 allowed false", start.allowed)
        step(100)
        assertEquals(KrxContestPhase.HOLD, h.phase())
        assertTrue("premise: allowed false 구간의 발행(HOLD 진입)이 관측된다", published.isNotEmpty())
        val sent = h.source.calls.size
        repeat(2) {
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            step(100)
        }
        assertEquals("premise: 만료 전", KrxContestPhase.HOLD, h.phase())
        assertEquals("premise: CALLER true 두 건이 조회·적용됐다", sent + 2, h.source.calls.size)
        assertTrue(h.source.calls.takeLast(2).all { it.freshPremium })
        assertEquals("HOLD 중 true 반복은 전이 0", 0 to 0, transitions(listOf(start) + published))
        assertFalse("HOLD 중 allowed는 false로 남는다", h.coordinator.accessSnapshot.facts.capabilityAllowed)
    }

    /** CAP-G01 H1′ 변형: live 판독 실패(미결정)는 _krx를 VISIBLE로 둔 채 AWAITING. `KRX_CONTESTED`로 allowed false. */
    @Test fun G01_liveReadFailureVariant_hidesThroughContested() = contestTest {
        val h = on()
        h.grantedKrxVisible()
        val published = h.recordPublished()
        h.undecidedRejection()
        assertTrue(TopicAccessBlock.KRX_CONTESTED in h.blocks())
        assertFalse(h.coordinator.accessSnapshot.facts.capabilityAllowed)
        settleKrx()
        val seq = listOf(Published(null, true)) + published
        assertEquals("false·true 전이 각 1회", 1 to 1, transitions(seq))
    }

    // --- 스위치 꺼짐의 candidate 표면 ----------------------------------------------------------------------------------

    /**
     * candidate·스위치 꺼짐(Codex 시험 검토 r1 ⑧): D1과 K18 꼴 입력에서 view는 null, 모든 발행의 krxPermitSeq = 0·`KRX_CONTESTED` 없음,
     * 모든 조회 시작의 reapprovalOnly = false. HEAD 기록 일치는 PremiumAccessKrxDormantTraceTest가 맡는다.
     */
    @Test fun G00_switchOff_exposesNoContestSurface() = contestTest {
        val h = off()
        val snapshots = mutableListOf<TopicAccessSnapshot>()
        val starts = mutableListOf<Boolean>()
        h.coordinator.onAccessPublishedForTest = { snapshots += it }
        h.coordinator.onQueryStartedForTest = { _, _, _, _, reapprovalOnly -> starts += reapprovalOnly }
        h.grantedKrxVisible()
        repeat(3) {
            h.source.next = { active }
            round(h)
            settleKrx()
            assertNull(h.coordinator.krxContestView)
        }
        h.store.rotationFailures = 2
        h.rejectNow()
        val gate = CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(gate)
        val q1 = launch(start = CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        step(0)
        val q2 = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        step(100)
        gate.complete(krxFalse)
        step(0)
        q1.join()
        q2.join()
        settleKrx()
        assertNull(h.coordinator.krxContestView)
        assertTrue("premise: 발행과 조회가 있었다", snapshots.isNotEmpty() && starts.isNotEmpty())
        assertTrue("krxPermitSeq는 늘 0", snapshots.all { it.krxPermitSeq == 0L })
        assertTrue("KRX_CONTESTED 없음", snapshots.none { TopicAccessBlock.KRX_CONTESTED in it.facts.capabilityBlocks })
        assertTrue("reapprovalOnly는 늘 false", starts.none { it })
    }

    private companion object {
        /** HEAD 생성자 인자 + 스위치 켬. */
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
