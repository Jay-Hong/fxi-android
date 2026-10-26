package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-09 발행 검사기(설계 r5 13절 T.10, 2절 ②(b) D-2 개정문). 행 09-0(부속 비공허 확인)·09-1·09-2·09-3.
 *
 * 켬 PAC는 S-4 [CHECKED_ON]으로만 만들고 본문은 [checkedOnTest] 안에서 돈다 — 끝에서 그 PAC의 모든 발행에 'capabilityAllowed ⟹
 * krxPermitSeq ≥ 1'을 단언한다. 훅(`onAccessPublishedForTest`)은 직접 대입하지 않고 [observePublications]만 쓴다(KW15).
 *
 * (시험 r2, E-02) S-4 자기 확인 파일 `KrxOnPublicationCheckSelfTest.kt`(T.1·16.2 목록 밖)는 접어서 지웠다. 그 두 확인은 이 클래스의
 * 행이 맡는다: 검사기 자신의 위반 검출(허용·permitSeq 0 발행을 끝 단언이 잡는다)은 09-0, `grantedKrxVisible()`만으로 OPEN·permit ≥ 1에
 * 이르는지·허용 발행이 모두 permit ≥ 1인지·관측기를 더해도 검사기가 남는지는 09-2. TA-07 KW15 양성 대조가 가리키는 파일을 이 클래스로
 * 옮기는 것은 KrxContestWiringContractTest 쪽 변경이다(이 클래스는 [checkedOnTest]를 쓴다).
 * (시험 r2, E-18) `premise:`는 HEAD에서도 성립하는 준비 조건에만 쓰고, α 동작에 기대는 준비 조건은 `contract(<행 또는 결정>):`로 행 계약
 * 앞에 둔다. 이 클래스가 보는 PAC·KrxContestPolicy·TopicAccessSnapshot은 스텁이 바꾸지 않은 de50885 코드라(노출 대입 PAC:3172, issue의 +1
 * KrxContestPolicy.kt:138) α에 기대는 준비 조건이 없다. 그래서 09-1의 permit 열은 `contract(…)`가 아니고, 계약 뒤에 두는 확인이라
 * `premise`도 아닌 '경로 확인(계약 뒤)'이다(전제가 변이를 죽이지 않게 계약 뒤로 옮긴 것은 r1 그대로). view 헬퍼의 실패 메시지가 행 id를 담는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxOnPublicationCheckTest {

    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)

    /** 켬 PAC의 contest view. [row]는 실패 메시지에 넣을 행 id다(T.0: 모든 단언 메시지가 행을 가리킨다). */
    private fun KrxHarness.view(row: String): KrxContestView = checkNotNull(coordinator.krxContestView) { "$row premise: 켬 PAC면 view가 있다" }

    /** 한 바퀴: KRX 거절(H1, 회전) → FE StableActive(true) 답 적용(ContestTest round와 같은 입력). */
    private suspend fun TestScope.round(h: KrxHarness) {
        h.rejectKrx(h.issueGrant())
        step(100)
    }

    /**
     * 09-0 (부속 비공허 확인, 시험 r2 E-02: 지운 KrxOnPublicationCheckSelfTest의 둘째 확인을 접은 행): 검사기 자신이 공허하지 않다 —
     * capabilityAllowed 참·permitSeq 0 발행 하나를 끝 단언이 AssertionError로 잡는다. permitSeq 0이어도 막힌 발행은 위반이 아니다. 계약 행이
     * 아니라 S-4의 공허함을 막는 확인이다.
     */
    @Test fun r09_0_theCheckItself_failsLoudlyOnAnAllowedPublicationWithoutAPermit() {
        val checker = KrxOnPublicationCheck()
        checker(krxSnap(capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED), krxPermitSeq = 0L))
        checker(krxSnap(krxPermitSeq = 1L))
        checker.assertHeld(minimumPublications = 2)
        assertEquals("09-0: 막힌 p = 0 발행과 허용 p = 1 발행은 위반이 아니다", 0, checker.violations.size)

        checker(krxSnap(krxPermitSeq = 0L, revision = 101L))
        assertEquals("09-0: 허용 p = 0 발행은 위반으로 기록된다", 1, checker.violations.size)
        val failure = assertThrows(AssertionError::class.java) { checker.assertHeld() }
        assertTrue("09-0: 실패 메시지가 그 발행을 가리킨다: ${failure.message}", failure.message.orEmpty().contains("rev=101"))
    }

    /**
     * 09-1 검사기: 켬 시나리오 granted → H1 → 신선한 true → 두 번째 모순 HOLD → 만료 → OPEN → C1 → 재허가. 이 PAC의 모든 발행에서
     * capabilityAllowed ⟹ krxPermitSeq ≥ 1이다.
     * 전제: 발행 ≥ 8건(이름 붙인 여덟 단계 — 각 단계가 capabilityAllowed 또는 permitSeq를 바꾼다), capabilityAllowed 참·거짓 발행이 모두 있다.
     * 잡을 변이: 노출 대입(PAC publishAccessSnapshotLocked의 `krxPermitSeq = …`)을 0L로, P-INC.
     * r1 검토 반영(P1): 두 변이가 바꾸는 값(스냅숏 krxPermitSeq·정책 permitSeq)을 전제 흐름에서 단언하면 계약이 아니라 전제가 변이를 죽인다.
     * 그래서 전제 흐름에는 phase만 두고, 각 OPEN 단계의 permit은 기록만 했다가 계약 단언 뒤에 경로 확인으로 단언한다. (시험 r2, E-18) 두
     * permit 열(정책 permitSeq·스냅숏 krxPermitSeq)은 HEAD에서도 성립하는 사실이라(PAC·정책 무변경) `contract(…)`로 적지 않고, 계약 뒤 확인이라
     * 준비 조건 표기(`premise(ON)`)도 쓰지 않는다 — 둘 다 '경로 확인(계약 뒤)'이다. 또 계약을 단계마다 그때까지의 발행에 단언한다(g3-fix
     * 실측 보강): P-INC에서는 정책 permitSeq가 0에 머물러 PAC 인계 자격 `rejectedPermit < permitSeq`(PremiumAccessCoordinator.kt krxHandoverEligibleLocked)가 거짓이 되므로 H1이 인계되지 않고 HOLD 전제가 먼저
     * 깨진다 — 계약을 끝에서만 보면 P-INC가 PREMISE로 죽는다. 단계별 계약은 granted 직후의 'OPEN·p = 0·허용' 발행에서 두 변이를 모두 먼저 잡는다.
     */
    @Test fun r09_1_everyOnPublicationOfAFullContestScenario_holdsTheInvariant() = checkedOnTest(minimumPublications = 8) { h ->
        val policyPermits = mutableListOf<Long>()
        val snapshotPermits = mutableListOf<Long>()
        fun recordPermits() {
            policyPermits += h.view("09-1").state.permitSeq
            snapshotPermits += h.coordinator.accessSnapshot.krxPermitSeq
        }
        /** 계약(단계별): 이 단계까지 받은 모든 발행에서 capabilityAllowed ⟹ krxPermitSeq ≥ 1. */
        fun contractSoFar(stepLabel: String) {
            val soFar = h.publicationCheck.published
            assertTrue("09-1 [$stepLabel] premise: 이 단계까지 검사한 발행이 있다 (${soFar.size})", soFar.isNotEmpty())
            soFar.forEach { s ->
                assertTrue(
                    "09-1 [$stepLabel]: rev=${s.revision} capabilityAllowed ⟹ krxPermitSeq ≥ 1 (permit=${s.krxPermitSeq})",
                    !s.facts.capabilityAllowed || s.krxPermitSeq >= 1L
                )
            }
        }

        h.grantedKrxVisible()
        assertEquals("09-1 premise: granted 뒤 OPEN", KrxContestPhase.OPEN, h.view("09-1").state.phase)
        contractSoFar("granted")
        recordPermits()

        round(h)
        assertEquals("09-1 premise: H1 → 신선한 true(T2)로 OPEN", KrxContestPhase.OPEN, h.view("09-1").state.phase)
        contractSoFar("H1 → T2")
        recordPermits()

        round(h)
        assertEquals("09-1 premise: 600초 안 두 번째 모순으로 HOLD", KrxContestPhase.HOLD, h.view("09-1").state.phase)
        assertFalse("09-1 premise: HOLD 중 capabilityAllowed 거짓", h.coordinator.accessSnapshot.facts.capabilityAllowed)
        contractSoFar("HOLD")
        val holdUntil = checkNotNull(h.view("09-1").state.holdUntilMillis) { "09-1 premise: HOLD 만료 시각" }
        step(holdUntil - testScheduler.currentTime)
        assertEquals("09-1 premise: 만료로 OPEN", KrxContestPhase.OPEN, h.view("09-1").state.phase)
        contractSoFar("만료 → OPEN")
        recordPermits()

        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("09-1 premise: C1로 NONE", KrxContestPhase.NONE, h.view("09-1").state.phase)
        contractSoFar("C1")

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("09-1 premise: 재허가로 OPEN", KrxContestPhase.OPEN, h.view("09-1").state.phase)
        contractSoFar("재허가")
        recordPermits()

        val published = h.publicationCheck.published
        assertTrue("09-1 premise: 발행 ≥ 8건 (${published.size})", published.size >= 8)
        assertTrue("09-1 premise: capabilityAllowed 참 발행이 있다", published.any { it.facts.capabilityAllowed })
        assertTrue("09-1 premise: capabilityAllowed 거짓 발행이 있다", published.any { !it.facts.capabilityAllowed })
        published.forEach { s ->
            assertTrue(
                "09-1: rev=${s.revision} capabilityAllowed ⟹ krxPermitSeq ≥ 1 (permit=${s.krxPermitSeq})",
                !s.facts.capabilityAllowed || s.krxPermitSeq >= 1L
            )
        }
        assertEquals("09-1: 검사기 위반 0", emptyList<TopicAccessSnapshot>(), h.publicationCheck.violations)
        assertEquals("09-1 경로 확인(계약 뒤, HEAD에서도 성립): OPEN 단계의 정책 허가 열", listOf(1L, 2L, 3L, 4L), policyPermits)
        assertEquals("09-1 경로 확인(계약 뒤, HEAD에서도 성립): OPEN 단계 스냅숏 krxPermitSeq 열", listOf(1L, 2L, 3L, 4L), snapshotPermits)
    }

    /**
     * 09-2 합성 보존(시험 r2 E-02: 지운 KrxOnPublicationCheckSelfTest의 첫째 확인을 접은 행): 검사기가 달린 PAC에 시험 관측기를
     * [observePublications]로 더해도 검사기는 그대로 달려 있고, 검사기와 관측기가 같은 발행을 모두 받는다. 함께 `grantedKrxVisible()`만으로 OPEN·permitSeq ≥ 1에 이르는지(18절 미확인 항목의 실측)와
     * 허용·막힘 발행이 모두 검사됐는지를 단언한다. 잡을 변이: 관측기가 검사기를 덮어씀.
     */
    @Test fun r09_2_anAddedObserver_keepsTheCheck_andBothSeeEveryPublication() = checkedOnTest { h ->
        val observed = h.coordinator.observePublications()
        h.grantedKrxVisible()
        assertEquals("09-2 premise(ON): grantedKrxVisible만으로 OPEN", KrxContestPhase.OPEN, h.view("09-2").state.phase)
        val snapshot = h.coordinator.accessSnapshot
        assertTrue("09-2 premise: OPEN 뒤 capabilityAllowed", snapshot.facts.capabilityAllowed)
        assertTrue("09-2 premise(ON): OPEN 뒤 krxPermitSeq ≥ 1 (${snapshot.krxPermitSeq})", snapshot.krxPermitSeq >= 1L)

        val checked = h.publicationCheck.published
        assertTrue("09-2: 관측기를 더한 뒤에도 검사기가 발행을 받았다", checked.isNotEmpty())
        assertTrue("09-2: capabilityAllowed 참 발행이 검사됐다", checked.any { it.facts.capabilityAllowed })
        assertTrue("09-2: capabilityAllowed 거짓 발행도 검사됐다", checked.any { !it.facts.capabilityAllowed })
        assertTrue("09-2: 허용 발행은 모두 p ≥ 1", checked.filter { it.facts.capabilityAllowed }.all { it.krxPermitSeq >= 1L })
        assertEquals("09-2: 관측기는 검사기와 같은 발행을 모두 받는다", checked, observed)
    }

    /**
     * 09-3 P-CON 대상: '켬·VISIBLE·premium runtime·¬OPEN·permitSeq 0' 발행을 이름으로 지정해 도달시키고, 그 발행에서 capabilityAllowed가
     * 거짓임을 단언한다(설계 r5 T.10: 도달성 미확인 — 이 r1에서 첫 허가 전 경로로 도달을 확인).
     * 경로: premium 확정·KRX 숨김(StableActive(false), B0와 같은 준비)으로 NONE·permit 0 → Pending(false)로 AWAITING(H2) → Pending(true).
     * Pending(true)는 AWAITING에서 허가하지 않으므로(KrxContestPolicy.kt:166-167) premium·`_krx` VISIBLE인 채 permit 0이 남는다.
     * 대상 발행은 `KRX_CONTESTED`가 아니라 'permitSeq 0 ∧ capability `NOT_GRANTED` 없음'으로 찾는다 — `NOT_GRANTED`가 없으면 premium runtime이고
     * `_krx` VISIBLE이며(PAC accessFactsLocked), 켬에서 permit 0이면 OPEN이 아니다. 그래서 P-CON 변이(PAC `KRX_CONTESTED` 조건 삭제)에서도 같은
     * 발행이 대상으로 잡히고 그때 capabilityAllowed가 참이 되어 이 행과 끝 검사기가 실패한다.
     */
    @Test fun r09_3_aVisiblePremiumPublicationBeforeTheFirstPermit_isNotAllowed() = checkedOnTest { h ->
        val published = h.coordinator.observePublications()
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settleKrx()
        assertEquals("09-3 premise: premium 확정", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("09-3 premise: NONE", KrxContestPhase.NONE, h.view("09-3").state.phase)
        assertEquals("09-3 premise: 첫 허가 전 permit 0", 0L, h.view("09-3").state.permitSeq)

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals("09-3 premise: Pending(false)로 AWAITING(H2)", KrxContestPhase.AWAITING, h.view("09-3").state.phase)
        assertEquals("09-3 premise: 여전히 permit 0", 0L, h.view("09-3").state.permitSeq)
        assertEquals("09-3 premise: KRX 숨김", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 600) }
        val floor = h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long?
        if (floor != null && floor > testScheduler.currentTime) advanceTimeBy(floor - testScheduler.currentTime)
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        step(0)
        assertEquals("09-3 premise: Pending(true)는 AWAITING에서 허가하지 않는다", KrxContestPhase.AWAITING, h.view("09-3").state.phase)
        assertEquals("09-3 premise: permit 0 그대로", 0L, h.view("09-3").state.permitSeq)

        val target = published.filter { it.krxPermitSeq == 0L && TopicAccessBlock.NOT_GRANTED !in it.facts.capabilityBlocks }
        assertTrue("09-3 premise: 대상 발행(permit 0 ∧ premium·VISIBLE)에 도달했다 (발행 ${published.size}건)", target.isNotEmpty())
        target.forEach { s ->
            assertTrue(
                "09-3 premise: rev=${s.revision} 대상 발행의 capability 막힘은 KRX_CONTESTED 말고 없다 — P-CON이면 비어 허용이 된다: ${s.facts.capabilityBlocks}",
                (s.facts.capabilityBlocks - TopicAccessBlock.KRX_CONTESTED).isEmpty()
            )
            assertFalse("09-3: rev=${s.revision} 켬·VISIBLE·premium·¬OPEN·permit 0 발행은 capabilityAllowed 거짓", s.facts.capabilityAllowed)
        }
    }
}
