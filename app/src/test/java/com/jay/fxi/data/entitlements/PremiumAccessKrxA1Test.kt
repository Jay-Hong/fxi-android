package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1a A1 계약 시험 — 스위치 켬에서 grant 문맥이 KRX epoch를 빼고 비교되는지(설계 r2 3.2 A1-1~A1-5)와
 * OD-2 capabilityInvalidations(설계 r2 2절, scope r9 2.m).
 *
 * 'premise:'는 HEAD에서도 성립하는 준비 조건이다. 'premise(A1-n):'은 그 A1 지점에 기대는 준비 조건이다. 계약 단언은 지점 이름을 붙인다.
 * 스위치 꺼짐 대조(OFF)는 HEAD 동작(R10)을 그대로 단언한다. R10 전체 동일성은 DormantTrace 확장이 맡는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessKrxA1Test {
    private val processJob = SupervisorJob()
    private val premiumRefusal = listOf(TopicRejectionReason.PREMIUM_REQUIRED)
    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)

    private fun a1Test(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    private fun TestScope.on(): KrxHarness = krxHarness(processJob, ON)
    private fun TestScope.off(): KrxHarness = krxHarness(processJob, HEAD_FACTORY)

    /** 한 바퀴: KRX 거절(H1, 회전) → FE true. */
    private suspend fun TestScope.round(h: KrxHarness) {
        h.rejectKrx(h.issueGrant())
        step(100)
    }

    private companion object {
        /** 스위치 켬 PAC(0b PremiumAccessKrxContestTest.ON과 같은 인자). */
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

    // --- A1-1·A1-2: 발급 문맥과 tokenStanding ---------------------------------------------------------------------

    /**
     * A1-1·A1-2 (켬): H1이 KRX capability epoch를 회전해도 grant 토큰은 그대로이고 tokenStanding·userInvalidations도 그대로다.
     * 잡을 변이: 발급 문맥(PAC:1747·:1775)이나 tokenStanding 현재 문맥(PAC:2957) 중 하나만 투영.
     */
    @Test fun A1_on_krxRotationKeepsTheTokenStanding() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        val invalidations = h.coordinator.accessSnapshot.userInvalidations
        round(h)
        assertNotEquals("premise: H1이 KRX epoch를 회전했다", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("A1: KRX 단독 회전이 grant 토큰을 바꾸지 않는다", token, h.issueGrant())
        assertTrue("A1: 같은 토큰이 회전한 기록 위에서 standing이다", h.coordinator.accessSnapshot.facts.tokenStanding)
        assertEquals("A1: KRX 단독 회전이 userInvalidations를 올리지 않는다", invalidations, h.coordinator.accessSnapshot.userInvalidations)
    }

    /** OFF 대조(R10): 같은 입력에서 HEAD는 KRX 회전마다 새 토큰을 낸다(PACT:1220과 같은 동작). */
    @Test fun A1_off_krxRotationIssuesANewToken() = a1Test {
        val h = off()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        round(h)
        assertNotEquals("premise: H1이 KRX epoch를 회전했다", epoch, h.store.record.krxCapabilityEpoch)
        assertNotEquals("OFF: HEAD는 KRX 회전에 새 토큰을 낸다", token, h.issueGrant())
    }

    // --- A1-3·A1-5: 거절 검증과 거절 재승인 검증 -------------------------------------------------------------------

    /**
     * A1-3 (켬, PAC:1906): KRX 회전 뒤 같은 토큰으로 온 PREMIUM_REQUIRED 거절은 epoch 차이만으로 버려지지 않는다. 회전은 직접 조회의
     * StableActive(false)(C1, false edge)로 만들어 거절 경로를 거치지 않는다 — 그래야 PAC:1906만 투영하지 않은 구현이 준비 단계에 가려지지
     * 않는다. 이어 StableActive(true)로 KRX를 복구한 뒤 같은 옛 grant로 premium 거절을 넣는다. live 판독 실패라
     * 판정 못 한 거절이 되어 FORCE_PREMIUM 요구를 남긴다.
     * A1-5 (켬, PAC:1821): 그 요구의 신선한 StableActive(true)는 같은 문맥의 거절 재승인으로 새 토큰을 낸다. 질의 fence는 KRX epoch를
     * 들고(OD-3 기본안) 문맥은 투영되므로, PAC:1821이 양쪽을 투영하지 않으면 재승인이 신호 없이 막힌다.
     * 잡을 변이: PAC:1906 미투영(요구 없음), PAC:1821 미투영(새 토큰 없음).
     */
    @Test fun A1_on_afterKrxRotation_premiumRefusalIsVerified_andReapproves() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()   // 회전 전 토큰. HEAD라면 회전 뒤 이 토큰의 거절은 기록 fence 비교(PAC:1906)에서 버려진다(토큰은 재발급 전까지 그대로라 :1882는 통과)
        val before = h.store.record.krxCapabilityEpoch
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val epoch = h.store.record.krxCapabilityEpoch
        assertNotEquals("premise: C1(false edge)이 KRX epoch를 회전했다", before, epoch)
        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("premise: StableActive(true)로 KRX 복구", KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(grant, premiumRefusal)
        assertEquals("premise: 회전 뒤 epoch 그대로(이 거절은 KRX를 회전하지 않는다)", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("A1-3: 회전 뒤 같은 토큰의 premium 거절이 검증을 통과해 요구를 남긴다", RefreshIntent.FORCE_PREMIUM,
            h.coordinator.recheckDiagnostics().owedIntent)

        settleKrx()
        assertNull("A1-5: 재승인 FORCE_PREMIUM이 요구를 정산했다", h.coordinator.recheckDiagnostics().owedIntent)
        val result = h.coordinator.topicGrantResult()
        val reissued = checkNotNull(result.fence).grant
        assertNotEquals("A1-5: 거절 재승인이 새 토큰을 냈다", grant, reissued)
        val cause = result.cause as TopicGrantCause.RefusalReapproval
        assertEquals("A1-5: 재승인의 원래 토큰", grant, cause.from)
        assertTrue("A1-5: 재승인 토큰이 standing이다", h.coordinator.accessSnapshot.facts.tokenStanding)
    }

    // --- A1-4: 토픽 후보 Stale 검사 --------------------------------------------------------------------------------------

    /**
     * A1-4 (켬, 후보 검사의 fence 비교): premium 거절이 store.load 실패로 토픽 후보로 보류된 뒤, 직접 조회의 StableActive(false)(C1, false edge)가
     * KRX epoch를 회전한다. store가 복구돼 후보 라운드가 돌면 투영 비교라 KRX 회전만으로는 Stale이 아니다: 후보가 결정돼 premium 상실이 적용된다
     * (사용자 epoch 회전, PremiumConfirmed 아님). premium 후보에는 KRX 표지가 없어 epoch 가드와 섞이지 않는다.
     * 잡을 변이: 후보 검사 fence 미투영(KRX 회전으로 Stale 해제 — premium 거절 유실).
     */
    @Test fun A1_on_krxRotationDoesNotStaleAPremiumTopicCandidate() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val userEpoch = h.store.record.userAccessEpoch
        val r = h.coordinator.reserveRejection(grant, premiumRefusal)
        h.store.loadFailures = Int.MAX_VALUE
        h.coordinator.onTopicRejected(r)
        assertEquals("premise: premium 토픽 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        h.store.loadFailures = 0
        val krxEpoch = h.store.record.krxCapabilityEpoch
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertNotEquals("premise: C1이 KRX epoch를 회전했다", krxEpoch, h.store.record.krxCapabilityEpoch)
        assertEquals("premise: 후보가 아직 남아 있다", 1, h.coordinator.heldLossCandidateCount())
        assertEquals("premise: 사용자 epoch는 아직 그대로", userEpoch, h.store.record.userAccessEpoch)
        settleKrx()
        assertEquals("premise: 후보 라운드가 돌았다", 0, h.coordinator.heldLossCandidateCount())
        assertNotEquals("A1-4: 후보가 결정돼 사용자 epoch가 회전했다", userEpoch, h.store.record.userAccessEpoch)
        assertNotEquals("A1-4: premium 상실 적용", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    // --- 4.6 PAC 단위 행의 ON 짝 (설계 r2 4절, scope r9 4.6 #2·#3·#22·#24·#25) ------------------------------------------------
    // 기존 OFF 시험은 그대로 두고(설계 r2 0절), 같은 입력을 켬에서 돌려 4.6 재합의 기대값(선행 재합의 r2 2절)을 단언한다.

    /** #2 ON 짝(PACT:1220): 표식·journal 변화와 C1(false edge)의 KRX 회전 모두 grant 토큰을 바꾸지 않는다. 회전이 실제로 일어났다는 전제를 함께 단언한다. */
    @Test fun A1_on_pair_PACT1220_c1RotationKeepsTheToken() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        h.store.record = h.store.record.copy(
            pendingPurges = h.store.record.pendingPurges + PendingPurge("someone-else", "old-user", "old-krx", setOf(PurgeScope.USER))
        )
        assertEquals("표식·journal만 바뀌면 같은 토큰", token, h.issueGrant())
        val epoch = h.store.record.krxCapabilityEpoch
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settleKrx()
        assertEquals("premise: premium 확정 유지", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("premise: KRX 숨김", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNotEquals("premise: KRX epoch가 회전했다", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("#2: KRX 회전이 토큰을 바꾸지 않는다", token, h.issueGrant())
    }

    /** #3 ON 짝(PATST:519): C1 회전 뒤 토큰이 그대로 서 있고 userInvalidations·사용자 축·lastUserEnd가 바뀌지 않는다. */
    @Test fun A1_on_pair_PATST519_c1RotationKeepsTokenAndUserInvalidations() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        val before = h.coordinator.accessSnapshot
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settleKrx()
        val after = h.coordinator.accessSnapshot
        assertEquals("premise: premium 확정 유지", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertNotEquals("premise: 기록 fence가 움직였다(KRX epoch)", before.facts.recordFence, after.facts.recordFence)
        assertTrue("#3: 옛 토큰이 계속 서 있다", after.facts.tokenStanding)
        assertEquals("#3: 같은 토큰", token, after.facts.token)
        assertEquals("#3: userInvalidations 불변", before.userInvalidations, after.userInvalidations)
        assertTrue("#3: 사용자 축 허용", after.facts.userAllowed)
        assertEquals("#3: 사용자 종료 기록 불변", before.lastUserEnd, after.lastUserEnd)
        assertTrue("KRX만 닫힌다", !after.facts.capabilityAllowed)
    }

    /**
     * #22 ON 짝(PATST:762): KRX 쓰기가 시작 전에 멈춘 동안은 capability만 의심(꺼짐과 같음), 확정된 capability epoch 변화 뒤에도 같은 토큰이
     * 서 있고 KRX만 닫힌다(꺼짐에서는 tokenStanding false).
     */
    @Test fun A1_on_pair_PATST762_confirmedKrxEpochChangeKeepsTheToken() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.store.rotationGate = gate
        h.source.next = { krxFalse }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        // gate가 풀리기 전 단언이 실패해도 NonCancellable 회전 대기가 끝나도록 finally에서 푼다(실패를 멈춤으로 바꾸지 않는다, 배터리 r1 F01·F02).
        try {
            assertNull("premise: 회전 쓰기에 들어가 멈췄다", h.store.rotationGate)
            val doubt = h.coordinator.accessSnapshot.facts
            assertTrue("premise: capability만 의심", TopicAccessBlock.CONTEXT_UNCERTAIN in doubt.capabilityBlocks && doubt.userAllowed)
            assertTrue("premise: 의심 중 토큰 유지(꺼짐과 같음)", doubt.tokenStanding)
        } finally {
            gate.complete(Unit)
        }
        settleKrx()
        refreshing.join()
        val facts = h.coordinator.accessSnapshot.facts
        assertTrue("premise: 의심이 풀렸다", !facts.capabilityContextUncertain)
        assertTrue("#22: 확정된 KRX epoch 변화 뒤에도 같은 토큰이 서 있다", facts.tokenStanding)
        assertEquals("#22: 같은 토큰", token, facts.token)
        assertTrue("#22: 사용자 축 허용", facts.userAllowed)
        assertTrue("#22: KRX만 닫힌다", !facts.capabilityAllowed)
    }

    /**
     * #22 ON 짝(PATST:910 parkedAfterLanding, :1439-1442·:1457): KRX 회전이 착지한 뒤 반환 전에 멈췄다 풀린다. 의심이 선 첫 발행부터 끝까지
     * capability는 다시 허용으로 보이지 않고 사용자 축은 늘 허용이며, 착지 뒤에는 움직인 기록 위에 같은 토큰이 서 있다(꺼짐은 금지).
     */
    @Test fun A1_on_pair_PATST910_parkedAfterLanding_keepsTheTokenOnTheMovedRecord() = a1Test {
        krxLossLanding(parkAfterLanding = true)
    }

    /** #22 ON 짝(PATST:920 cancelledAfterLanding): KRX 회전이 착지한 뒤 store 자신의 취소로 끝나고 다시 읽어 확정된다. 결과는 :910 짝과 같다. */
    @Test fun A1_on_pair_PATST920_cancelledAfterLanding_keepsTheTokenOnTheMovedRecord() = a1Test {
        krxLossLanding(parkAfterLanding = false)
    }

    private suspend fun TestScope.krxLossLanding(parkAfterLanding: Boolean) {
        val h = on()
        h.grantedKrxVisible()
        val token = h.issueGrant()
        val beforeFence = h.coordinator.accessSnapshot.facts.recordFence
        val published = mutableListOf<TopicAccessFacts>()
        h.coordinator.onAccessPublishedForTest = { s -> published += s.facts }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        if (parkAfterLanding) h.store.landedGate = gate else h.store.rotationLandsThenCancels = 1
        h.source.next = { krxFalse }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        if (parkAfterLanding) {
            // 위 PATST762와 같은 이유로 gate는 finally에서 푼다.
            try {
                assertNull("premise: 착지 뒤 멈췄다", h.store.landedGate)
                assertNotEquals("premise: 기록이 움직였다", beforeFence, h.store.record.fence())
            } finally {
                gate.complete(Unit)
            }
        }
        settleKrx()
        refreshing.join()
        assertTrue("premise: 호출자는 취소되지 않았다", !refreshing.isCancelled)
        val facts = h.coordinator.accessSnapshot.facts
        assertEquals("premise: 착지한 기록이 관측됐다", h.store.record.fence(), facts.recordFence)
        assertNotEquals("premise: 기록 fence가 움직였다", beforeFence, facts.recordFence)
        assertTrue("premise: 의심이 풀렸다", !facts.capabilityContextUncertain)
        assertTrue("seal 없음", TopicAccessBlock.EXPLICIT_SEAL !in facts.capabilityBlocks)
        assertTrue("#22: 움직인 기록 위에 같은 토큰이 서 있다", facts.tokenStanding)
        assertEquals("#22: 같은 토큰", token, facts.token)
        val start = published.indexOfFirst { it.capabilityContextUncertain }
        assertTrue("premise: 의심이 발행됐다", start >= 0)
        val after = published.drop(start)
        assertTrue("capability가 다시 허용으로 보이지 않는다", after.none { it.capabilityAllowed })
        assertTrue("KRX 손실이 사용자 축을 가져가지 않는다", after.all { it.userAllowed })
    }

    /**
     * #24 ON 짝(PACT:1408-1411 'KRX epoch 회전' 사례, :1516): 같은 토큰의 premium 거절은 KRX 단독 회전(외부 기록 회전, C1 회전) 뒤에도 문맥이
     * 움직인 것으로 버려지지 않고 적용된다(A1-3 투영, 설계 r2 3.2 — 거절 검증의 KRX epoch 판단은 KRX 표지 epoch 가드만 맡고 premium 거절에는
     * 해당 없음). 꺼짐(HEAD)에서는 버려진다.
     */
    @Test fun A1_on_pair_PACT1408_premiumRefusalAfterAKrxOnlyRotation_isApplied() = a1Test {
        for (external in listOf(true, false)) {
            val label = if (external) "[외부 기록 회전]" else "[C1 회전]"
            val h = on()
            h.grantedKrxVisible()
            val token = h.issueGrant()
            val epoch = h.store.record.krxCapabilityEpoch
            if (external) {
                h.store.record = AccessEpochTransitions.rotate(
                    h.store.record, rotateUser = false, rotateKrx = true, ids = EpochIdGenerator { "external-0" }
                )   // PACT:1405와 같은 전이(epoch·mayContainKrxData·CAPABILITY journal)
            } else {
                h.source.next = { krxFalse }
                h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                settleKrx()
                assertEquals("$label premise(A1): 같은 토큰", token, h.issueGrant())
            }
            assertNotEquals("$label premise: KRX epoch가 회전했다", epoch, h.store.record.krxCapabilityEpoch)
            val stateBefore = h.coordinator.state.value
            val userEpoch = h.store.record.userAccessEpoch
            h.coordinator.onTopicRejected(token, premiumRefusal)
            step(120_000)
            assertNotEquals("$label #24: premium 거절이 적용됐다(상태)", stateBefore, h.coordinator.state.value)
            assertNotEquals("$label #24: 사용자 epoch 회전", userEpoch, h.store.record.userAccessEpoch)
        }
    }

    /**
     * #25 ON 짝(LossCandidateTest:445): KRX 거절이 판독 실패로 후보가 된 뒤 다른 답(C1)이 KRX만 회전해도 grant는 대체되지 않는다
     * (`replacement == grant`). 대체가 없으니 그 후보는 '대체된 grant'로 기록 없이 버려지지 않고, 판독이 계속 실패하는 동안 남는다(파생:
     * knownStaleWithoutRecordLocked의 grant 문맥 비교). :480은 질의 fence(OD-3 기본안 유지)라 바뀌지 않는다.
     */
    @Test fun A1_on_pair_LossCandidate445_krxOnlyRotationDoesNotReplaceTheGrant() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        h.store.loadFailures = 2
        h.rejectKrx(grant)
        runCurrent()
        assertEquals("premise: 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertEquals("#25: KRX 단독 회전은 grant를 대체하지 않는다", grant, h.issueGrant())
        h.store.loadFailures = Int.MAX_VALUE
        step(KrxHarnessDefaults.RETRY)
        assertEquals("#25(파생): 대체되지 않은 grant의 후보는 판독 실패 동안 남는다", 1, h.coordinator.heldLossCandidateCount())
        h.store.loadFailures = 0
    }

    // --- 4.6 #24 나머지 ON 짝 (Codex 시험 3회차 판단 7: PACT :2409·:2475·:2919·주석 :1281) -------------------------------------------------------

    private fun KrxHarness.view(): KrxContestView = checkNotNull(coordinator.krxContestView) { "스위치 켬이면 view가 있다" }

    /**
     * #24 ON 짝(PACT:2409, 원 입력): premium 거절 live 실패(FORCE_PREMIUM, 5초 floor) → KRX 거절의 store 실패로 후보(p) → 5초에 FP true가 같은 문맥
     * RefusalReapproval(새 토큰)과 함께 T2로 새 허가 p+1을 낸다. 그래서 켬에서는 다음 후보 라운드가 그 후보를 허가 재판정(CAP-R10 (i))으로 결정·회전 없이
     * 해제한다(꺼짐 원본은 후보가 결정돼 회전). 이는 이 원 입력의 기대값이다. 뒤의 H2가 재승인 조회를 S1으로 만들면 토큰만 재승인되고 permit은
     * 유지될 수 있으므로, 재승인을 건너 남는 KRX 후보의 lineage 보존은 별도 S1 변형으로 잠근다.
     */
    @Test fun A1_on_pair_PACT2409_reapprovedGrantCandidate_releasedByThePermitRedecision() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val p = h.view().state.permitSeq
        val grant = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        h.source.identity = null
        h.coordinator.onTopicRejected(grant, premiumRefusal)
        h.store.loadFailures = 1
        h.rejectKrx(grant)
        assertEquals("premise: KRX 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        step(3_000)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 1L)
        step(1_990)
        assertTrue("premise: 보류가 서 있다", TopicAccessBlock.LOSS_CANDIDATE in h.coordinator.accessSnapshot.facts.capabilityBlocks)
        step(4_000)
        val token = checkNotNull(h.coordinator.accessSnapshot.facts.token)
        assertNotEquals("premise: 옛 grant가 대체됐다", grant, token)
        assertEquals("ON: FP true가 T2로 새 허가", p + 1, h.view().state.permitSeq)
        assertEquals("ON: 후보는 허가 재판정으로 해제", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("ON: 결정·회전 없음", epoch, h.store.record.krxCapabilityEpoch)
        val cause = h.coordinator.topicGrantResult().cause
        assertTrue("ON: 토큰은 같은 문맥 재승인의 것(회전으로 새 문맥이 나지 않음): $cause",
            cause is TopicGrantCause.RefusalReapproval && cause.from == grant)
    }

    /**
     * #24 ON 짝(PACT:2409·:2475의 원 계약, Codex 시험 4회차 판단 1): 재승인을 건넌 KRX 후보는 Stale이 아니라 해소된다. 재승인 조건(조회 순번 >
     * 보고된 거절 순번, TopicRejectionLedger.kt:138)과 contest 신선함(barrier)은 별개다 — 재승인 조회 q를 먼저 시작해 두고 뒤의 CALLER FE Pending(false)
     * (H2)로 barrier를 q 위로 올리면, q의 true는 contest에 S1(새 허가 없음)이면서 같은 문맥 RefusalReapproval을 낸다(재승인 검증은 barrier를 보지 않음,
     * PAC:1810-1822). 후보의 표지 permit = permitSeq이고 phase AWAITING이라 허가 재판정은 통과하고, lineage(issuedFor)가 후보를 옛 grant의 것으로 남겨
     * 결정된다. 결정이 KRX를 회전하도록 마지막에 신선한 Pending(true)(T5)로 raw KRX만 VISIBLE로 둔다(Reducer:154-155 isFirstEdge).
     * 재승인 1회·2회 변형. 잡을 변이: 후보 검사에서 lineage 누락(Stale 해제 — 회전 없음).
     * 시간표: 후보 라운드는 보류 시점 기준 0·1·3·7·15·31초(RETRY 1초 배수), 조회 적용은 live 신원을 읽으므로(PAC:2061) 신원 미확인은 라운드 순간에만 넣는다.
     * 첫 H2 뒤 예약된 Pending 재조회는 gate에 걸어 진행 중으로 두어 그 뒤 예약 조회를 덮게 한다.
     */
    @Test fun A1_on_pair_PACT2409_krxCandidateAcrossS1Reapprovals_isResolved() = a1Test {
        for (reapprovals in listOf(1, 2)) lineageAcrossS1Reapprovals(reapprovals)
    }

    private suspend fun TestScope.lineageAcrossS1Reapprovals(reapprovals: Int) {
        val label = "[재승인 ${reapprovals}회]"
        val pendingFalse = EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0)
        val h = on()
        h.grantedKrxVisible()
        h.source.next = { pendingFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("$label premise: H2로 AWAITING", KrxContestPhase.AWAITING, h.view().state.phase)
        assertEquals("$label premise: raw KRX HIDDEN", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertTrue("$label premise: KRX 자료 표지 꺼짐", !h.store.record.mayContainKrxData)
        val hang = kotlinx.coroutines.CompletableDeferred<EntitlementsOutcome>()
        h.source.gates.addLast(hang)
        val f1 = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "$label premise: 재조회 floor" }
        step(f1 - testScheduler.currentTime)
        assertEquals("$label premise: 예약 재조회가 진행 중으로 걸렸다", 1, h.coordinator.recheckDiagnostics().inFlightOrders.size)
        val p = h.view().state.permitSeq
        val epoch = h.store.record.krxCapabilityEpoch
        val key = h.view().key
        var token = h.issueGrant()
        h.store.loadFailures = 1
        val rk = h.coordinator.reserveRejection(token, KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker(epoch, p))
        h.coordinator.onTopicRejected(rk)
        val t0 = testScheduler.currentTime
        assertEquals("$label premise: KRX 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        fun TestScope.feedRound(atOffset: Long) {
            val at = t0 + atOffset
            step(at - 1 - testScheduler.currentTime)
            h.source.identityNulls = 1
            step(1)
            assertEquals("$label premise: ${atOffset}ms 후보 라운드가 신원 미확인으로 돌았다", 0, h.source.identityNulls)
        }
        h.source.identityNulls = 2
        h.coordinator.onTopicRejected(token, premiumRefusal)
        assertEquals("$label premise: premium live 실패가 신원 미확인 하나를 썼다", 1, h.source.identityNulls)
        step(0)
        assertEquals("$label premise: 라운드 0이 나머지를 썼다", 0, h.source.identityNulls)
        feedRound(1_000)
        feedRound(3_000)
        repeat(reapprovals) { i ->
            val floor = checkNotNull(h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?) { "$label premise: FP floor" }
            advanceTimeBy(floor - testScheduler.currentTime)
            val qAnswer = kotlinx.coroutines.CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(qAnswer)
            val fp = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
            val q = h.coordinator.recheckDiagnostics().inFlightOrders.max()
            val sentBefore = h.source.calls.size
            h.source.next = { pendingFalse }
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            assertEquals("$label premise: CALLER FE가 즉시 답했다(#$i)", sentBefore + 1, h.source.calls.size)
            assertTrue("$label premise: H2가 barrier를 q 위로 올렸다(#$i)", checkNotNull(h.view().state.barrier) > q)
            assertEquals("$label premise: 이 H2는 회전하지 않는다(#$i)", epoch, h.store.record.krxCapabilityEpoch)
            qAnswer.complete(active)
            step(0)
            fp.join()
            val next = checkNotNull(h.coordinator.accessSnapshot.facts.token)
            assertNotEquals("$label premise: 같은 문맥 재승인 새 토큰(#$i)", token, next)
            val cause = h.coordinator.topicGrantResult().cause
            assertTrue("$label premise: 재승인 원인(#$i): $cause", cause is TopicGrantCause.RefusalReapproval && cause.from == token)
            assertEquals("$label premise: S1이라 허가 그대로(#$i)", p, h.view().state.permitSeq)
            assertEquals("$label premise: AWAITING(#$i)", KrxContestPhase.AWAITING, h.view().state.phase)
            assertEquals("$label premise: 같은 키(#$i)", key, h.view().key)
            assertEquals("$label premise: 후보가 남아 있다(#$i)", 1, h.coordinator.heldLossCandidateCount())
            token = next
            if (i == 0) feedRound(7_000)
            if (i == 0 && reapprovals == 2) {
                h.source.identityNulls = 1
                h.coordinator.onTopicRejected(token, premiumRefusal)
                assertEquals("$label premise: 둘째 premium live 실패", 0, h.source.identityNulls)
            }
        }
        if (reapprovals == 2) feedRound(15_000)
        val pFloor = h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?
        if (pFloor != null && pFloor > testScheduler.currentTime) advanceTimeBy(pFloor - testScheduler.currentTime)
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 600) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val rawFacts = h.coordinator.accessSnapshot.facts
        assertTrue("$label premise: Pending(true)로 raw KRX VISIBLE(NOT_GRANTED 없음 — 공개 krx 흐름은 후보 보류로 숨김 유지)",
            TopicAccessBlock.NOT_GRANTED !in rawFacts.capabilityBlocks)
        assertTrue("$label premise: 후보 보류 막힘", TopicAccessBlock.LOSS_CANDIDATE in rawFacts.capabilityBlocks)
        assertEquals("$label premise: T5라 허가 그대로", p, h.view().state.permitSeq)
        assertEquals("$label premise: 후보가 아직 남아 있다", 1, h.coordinator.heldLossCandidateCount())
        val nextRound = if (reapprovals == 2) 31_000L else 15_000L
        assertTrue("$label premise: 다음 라운드 전", testScheduler.currentTime < t0 + nextRound)
        step(t0 + nextRound - testScheduler.currentTime)
        assertEquals("재승인을 건넌 KRX 후보가 해소됐다", 0, h.coordinator.heldLossCandidateCount())
        assertNotEquals("해소의 회전(Stale 해제면 회전 없음)", epoch, h.store.record.krxCapabilityEpoch)
        hang.complete(EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 600))
    }

    /**
     * #24 ON 짝(PACT:2475, 원 입력): 첫 재승인 FP true가 T2로 새 허가를 내므로 켬에서는 후보가 두 번째 재승인까지 남지 않고 다음 라운드에서 허가 재판정으로
     * 해제된다(회전 없음). 재승인 사슬(두 번째 재승인의 원래 토큰 = 첫 재승인 토큰)은 그대로 이어진다. 같은 후보가 두 재승인을 건너 남는 계약은 별도 S1 변형으로 잠근다.
     */
    @Test fun A1_on_pair_PACT2475_firstReapprovalPermit_releasesTheCandidate_chainContinues() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val epoch = h.store.record.krxCapabilityEpoch
        h.source.identity = null
        h.coordinator.onTopicRejected(grant, premiumRefusal)
        h.store.loadFailures = 1
        h.rejectKrx(grant)
        step(4_990)
        assertEquals("premise: 후보 보류", 1, h.coordinator.heldLossCandidateCount())
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 1L)
        step(20)
        val first = checkNotNull(h.coordinator.accessSnapshot.facts.token)
        assertNotEquals("premise: 첫 재승인", grant, first)
        step(2_000)
        assertEquals("ON: 첫 T2 뒤 후보는 허가 재판정으로 해제", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("ON: 회전 없음", epoch, h.store.record.krxCapabilityEpoch)
        h.source.identity = null
        h.coordinator.onTopicRejected(first, premiumRefusal)
        step(4_990)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 1L)
        step(20)
        val second = checkNotNull(h.coordinator.accessSnapshot.facts.token)
        assertNotEquals("두 번째 재승인", first, second)
        assertEquals("재승인 사슬", first, (h.coordinator.topicGrantResult().cause as TopicGrantCause.RefusalReapproval).from)
        assertEquals("여전히 회전 없음", epoch, h.store.record.krxCapabilityEpoch)
    }

    /**
     * #24 ON 짝(PACT:2919): 켬에서 KRX 회전은 grant를 대체하지 않으므로, 요약 은퇴의 원래 계약은 켬에서도 grant를 대체하는 문맥 교체(같은 owner 재바인딩,
     * 세대 이동)로 확인한다. RefusalReapproval은 대기 중인 late 예약이 막으므로(TopicRejectionLedger.kt:135-140) 트리거로 쓰지 않는다. 대체 뒤 옛 grant의
     * late 처리·새 거절은 옛 요약을 되살리지 않고, 새 grant의 요약은 그대로다.
     */
    @Test fun A1_on_pair_PACT2919_summaryRetirement_withASameOwnerRebind() = a1Test {
        val h = on()
        h.grantedKrxVisible()
        val old = h.issueGrant()
        val late = h.coordinator.reserveRejection(old, premiumRefusal)
        h.source.identity = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 2L)
        h.coordinator.onIdentityChanged(com.jay.fxi.data.auth.AuthIdentityFence(KrxHarnessDefaults.OWNER, 2L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        val replacement = h.issueGrant()
        assertNotEquals("premise: 재바인딩이 grant를 대체했다", old, replacement)
        val fresh = h.coordinator.reserveRejection(replacement, KrxHarnessDefaults.KRX_REFUSAL)
        h.coordinator.onTopicRejected(late)
        h.coordinator.onTopicRejected(old, premiumRefusal)
        assertEquals("퇴역한 요약이 되살아나지 않는다", TopicRejectionView(emptyList(), null, null), h.coordinator.rejectionView(old))
        assertEquals("새 grant의 요약 그대로", TopicRejectionView(listOf(fresh.order), fresh.order, null), h.coordinator.rejectionView(replacement))
        h.coordinator.abandonRejection(late)
        assertEquals(listOf(fresh.order), h.coordinator.rejectionView(replacement).pendingOrders)
    }

    /**
     * #24 ON 짝(PACT:1281 주석·refusals_collapseToOneDecisionPerAcknowledgement): 켬에서는 KRX 먼저 회전해도 premium 거절이 낡지 않으므로(A1-3) 꺼짐
     * 주석의 근거는 달라지지만, 한 응답의 섞인 거절을 premium 하나로 모으는 동작(TopicRejection.of)은 같다. 두 순서 모두 premium 거절 하나와 상태·회전·purge·
     * 조회 수가 같다. 잡을 변이: 켬에서 섞인 거절을 KRX·premium 둘로 적용, 허가 판정을 섞인 거절에 적용.
     */
    @Test fun A1_on_pair_PACT1281_mixedRefusalCollapsesToOnePremiumDecision() = a1Test {
        val premium = TopicRejectionReason.PREMIUM_REQUIRED
        val krx = TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
        val alone = on()
        alone.grantedKrxVisible()
        val aloneRotations = alone.store.rotationAttempts
        val alonePurges = alone.purger.calls
        val aloneSends = alone.source.calls.size
        alone.coordinator.onTopicRejected(alone.issueGrant(), listOf(premium))
        settleKrx()
        val aloneState = alone.coordinator.state.value.state
        for (order in listOf(listOf(krx, premium), listOf(premium, krx))) {
            val h = on()
            h.grantedKrxVisible()
            val rotations = h.store.rotationAttempts
            val purges = h.purger.calls
            val sends = h.source.calls.size
            h.coordinator.onTopicRejected(h.issueGrant(), order)
            settleKrx()
            assertEquals("$order: 상태", aloneState, h.coordinator.state.value.state)
            assertEquals("$order: 회전 시도", alone.store.rotationAttempts - aloneRotations, h.store.rotationAttempts - rotations)
            assertEquals("$order: purge", alone.purger.calls - alonePurges, h.purger.calls - purges)
            assertEquals("$order: 조회", alone.source.calls.size - aloneSends, h.source.calls.size - sends)
        }
    }

    // --- 보존 계약(R5·OD-5)과 premium 상실 전체 재설정 (설계 r2 3.2) ----------------------------------------------------

    /**
     * 보존 계약: KRX 단독 회전(C1 false edge, H1 거절)은 A1 밖의 RS(attempts·floor·intent)와 userBlocks를 바꾸지 않는다 — 같은 입력의 꺼짐
     * 실행과 같다. 토큰·tokenStanding·userInvalidations 보존은 위 A1 시험이 맡는다. 잡을 변이: A1 구현이 KRX 회전에서 RS나 사용자 축을 건드림.
     */
    @Test fun A1_on_preservation_rsAndUserBlocksMatchTheOffRun() = a1Test {
        for (viaRefusal in listOf(false, true)) {
            val label = if (viaRefusal) "[H1]" else "[C1]"
            val on = on()
            val off = off()
            for (h in listOf(on, off)) h.grantedKrxVisible()
            for (h in listOf(on, off)) {
                if (viaRefusal) {
                    h.rejectKrx(h.issueGrant())
                } else {
                    h.source.next = { krxFalse }
                    h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                }
            }
            step(0)
            for (field in listOf("consecutiveAttempts", "earliestAllowedAtMillis", "pendingIntent")) {
                assertEquals("$label RS ${field}는 꺼짐과 같다", off.recheckSchedule().readPrivate(field), on.recheckSchedule().readPrivate(field))
            }
            assertEquals("$label userBlocks는 꺼짐과 같다", off.coordinator.accessSnapshot.facts.userBlocks, on.coordinator.accessSnapshot.facts.userBlocks)
            assertTrue("$label 사용자 축 허용", on.coordinator.accessSnapshot.facts.userAllowed)
        }
    }

    /**
     * premium 상실은 KRX 단독 철회가 아니므로 전체 재설정을 유지한다(scope 2.a): StableInactive 뒤 켬도 꺼짐과 같이 옛 토큰이 서지 않고
     * userInvalidations가 오르며 상태가 같다. 잡을 변이: A1 투영이 사용자 epoch까지 빼서 premium 상실 뒤에도 토큰 유지.
     */
    @Test fun A1_on_premiumLoss_keepsTheFullReset() = a1Test {
        val on = on()
        val off = off()
        for (h in listOf(on, off)) h.grantedKrxVisible()
        val before = on.coordinator.accessSnapshot
        val token = on.issueGrant()
        off.issueGrant()
        for (h in listOf(on, off)) {
            h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        }
        settleKrx()
        val a = on.coordinator.accessSnapshot
        val b = off.coordinator.accessSnapshot
        assertEquals("premise: 꺼짐과 같은 상태", off.coordinator.state.value.state, on.coordinator.state.value.state)
        assertTrue("premise: premium 상실", on.coordinator.state.value.state != PremiumAccessState.PremiumConfirmed)
        assertTrue("premium 상실 뒤 옛 토큰이 서지 않는다", !(a.facts.tokenStanding && a.facts.token == token))
        assertEquals("tokenStanding은 꺼짐과 같다", b.facts.tokenStanding, a.facts.tokenStanding)
        assertTrue("userInvalidations가 오른다", a.userInvalidations > before.userInvalidations)
        assertEquals("userInvalidations는 꺼짐과 같다", b.userInvalidations, a.userInvalidations)
    }

    // --- OD-2: capabilityInvalidations -------------------------------------------------------------------------

    /**
     * OD-2 (켬): capabilityAllowed 허용→차단 전이마다 capabilityInvalidations가 1 오른다. epoch 회전 없는 차단(H1′)도, 숨김 증거 없이 허용에서
     * 바로 오는 C1(false edge)도 센다. 이미 차단된 동안 다른 막힘이 더해져도(C1: false edge, NOT_GRANTED) 다시 세지 않는다. 차단→허용은 세지
     * 않는다. 잡을 변이: epoch 회전만 셈, 숨김 증거(거절)에서만 셈, 반복 차단을 다시 셈, 차단→허용을 셈.
     */
    @Test fun OD2_on_eachAllowedToBlockedTransitionCountsOnce() = a1Test { od2Sequence(on(), counting = true) }

    /** OD-2 OFF 대조: 같은 입력의 각 관측점에서 capabilityInvalidations는 0이다(중간에 올랐다 돌아오는 누수도 잡는다). */
    @Test fun OD2_off_neverCounts() = a1Test { od2Sequence(off(), counting = false) }

    private suspend fun TestScope.od2Sequence(h: KrxHarness, counting: Boolean) {
        fun expect(n: Long) = if (counting) n else 0L
        h.grantedKrxVisible()
        val start = h.coordinator.accessSnapshot
        assertTrue("premise: 처음엔 허용", start.facts.capabilityAllowed)
        val epoch = h.store.record.krxCapabilityEpoch

        h.undecidedRejection()   // H1′: live 판독 실패, 회전 없음
        val held = h.coordinator.accessSnapshot
        assertEquals("premise: H1′은 회전하지 않는다", epoch, h.store.record.krxCapabilityEpoch)
        if (counting) assertTrue("premise: 켬에서 H1′ 뒤 차단", !held.facts.capabilityAllowed)
        assertEquals("OD-2: 회전 없는 허용→차단도 1", expect(start.capabilityInvalidations + 1), held.capabilityInvalidations)

        h.source.next = { krxFalse }
        step(5_010)   // H1′의 FE(5초 floor)가 false로 답한다: C1·false edge 회전, _krx HIDDEN
        val falseEdge = h.coordinator.accessSnapshot
        assertTrue("premise: false edge 뒤 차단", !falseEdge.facts.capabilityAllowed)
        assertEquals("OD-2: 차단 중 다른 막힘은 다시 세지 않는다", expect(held.capabilityInvalidations), falseEdge.capabilityInvalidations)

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val reopened = h.coordinator.accessSnapshot
        assertTrue("premise: StableActive(true)로 다시 허용", reopened.facts.capabilityAllowed)
        assertEquals("OD-2: 차단→허용은 세지 않는다", expect(falseEdge.capabilityInvalidations), reopened.capabilityInvalidations)

        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)   // C1: 허용에서 바로, 숨김 증거 없음
        val c1 = h.coordinator.accessSnapshot
        assertTrue("premise: C1(허용에서 직접) 뒤 차단", !c1.facts.capabilityAllowed)
        assertEquals("OD-2: 숨김 증거 없는 허용→차단(C1)도 1", expect(reopened.capabilityInvalidations + 1), c1.capabilityInvalidations)

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        val reopened2 = h.coordinator.accessSnapshot
        assertTrue("premise: 다시 허용", reopened2.facts.capabilityAllowed)
        assertEquals("OD-2: 차단→허용은 세지 않는다(둘째)", expect(c1.capabilityInvalidations), reopened2.capabilityInvalidations)

        h.rejectKrx(h.issueGrant())   // H1: 회전 있는 차단
        val again = h.coordinator.accessSnapshot
        assertTrue("premise: H1 뒤 차단", !again.facts.capabilityAllowed)
        assertEquals("OD-2: 숨김 증거 있는 허용→차단(H1)", expect(reopened2.capabilityInvalidations + 1), again.capabilityInvalidations)
        if (counting) assertEquals("A1: KRX 거절만으로 사용자 축(userInvalidations)은 그대로", start.userInvalidations, again.userInvalidations)
    }
}
