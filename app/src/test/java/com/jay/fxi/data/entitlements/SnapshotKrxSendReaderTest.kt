package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-04 TSC KRX 결정 함수(판독 seam)와 effective 식(설계 r5 13절 T.5, 3절, 5A.3·5A.4). 행 04-1·04-2·04-3·
 * 04-4·04-5·04-O1.
 *
 * 판독 seam = [SnapshotTopicUseAuthority]의 [KrxSendReadAuthority.admitsWithKrx](WS 송신, admits형)와 [KrxSendReadAuthority.acquireWithKrx]
 * (REST 발행, acquire형). 한 번의 스냅숏 판독에서 (일반 사용, KRX 사용권, 표지 = (recordFence.krxCapabilityEpoch, krxPermitSeq))를 낸다(D-4).
 * 두 형태를 같은 행에서 시험한다. 실제 PAC 스냅숏은 운영 람다와 같은 꼴(`SnapshotTopicUseAuthority { coordinator.accessSnapshot }`,
 * GraphRuntimeModule.kt:37)로 읽고, 판독한 스냅숏을 기록해 전제를 그 스냅숏 위에서 단언한다.
 *
 * 켬 행은 S-4 [CHECKED_ON] PAC를 [checkedOnTest] 안에서 쓴다(끝에서 모든 발행의 켬 불변식 단언). 꺼짐 대조(04-O1)는 KrxContestHarness의
 * [HEAD_FACTORY] PAC(꼴 (나))이고, 일반 판정의 기준은 아래 동결한 HEAD 식 사본이다.
 *
 * 설계 정정(r1): 04-1의 '잡을 변이' 칸에서 K-SN을 뺀다 — 04-1은 한 판독 안에서 스냅숏이 바뀌지 않아 K-SN(epoch와 permitSeq를 다른 스냅숏에서
 * 읽음)을 잡을 수 없다. K-SN은 04-3(번갈아 내는 공급자, 두 형태)과 01-6이 잡는다.
 *
 * r1 검토 반영(g3-fix): 04-1·04-2의 '사용권 거짓' 단언 앞에 그 사용권이 발급 시점에서 참이라는 창 안 대조를 두었고, 04-2는 HOLD 전 사용권을
 * 두 형태로 본다. 04-5는 창의 capability 막힘 집합(EF-B가 참이 되는 창)과 풀린 뒤 일반 허용을 전제로 고정했다. 04-O1은 r0 기록 epoch
 * non-null을 전제로 둔다. 04-3·04-4에 5A.3 krxUse 발급 연언 둘('use 성공', 'epoch ≠ null')을 보강했다(설계 표에 올릴 항목).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SnapshotKrxSendReaderTest {

    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)

    // --- 동결한 HEAD 식 사본(de50885 SnapshotTopicUseAuthority.kt:14-22) ----------------------------------------------------------
    // 본문은 HEAD와 바이트 그대로다(들여쓰기만 클래스 안 4칸이 더해졌다). 다른 것은 이름 접두 `head`와 `private`뿐이다 — 같은 receiver
    // 확장 함수라 본문의 `facts`·`userInvalidations`가 HEAD와 같은 이름으로 풀린다.

    /** de50885 SnapshotTopicUseAuthority.kt:14-18 `acquireUse` 사본(본문 :15-17 바이트 그대로). */
    private fun TopicAccessSnapshot.headAcquireUse(fence: TopicSessionFence): TopicUseLifetime? {
        val token = facts.token ?: return null
        if (token != fence.grant || !facts.tokenStanding || !facts.userAllowed) return null
        return TopicUseLifetime(token, userInvalidations)
    }

    /** de50885 SnapshotTopicUseAuthority.kt:21-22 `admitsUse` 사본(본문 :22 바이트 그대로). */
    private fun TopicAccessSnapshot.headAdmitsUse(lifetime: TopicUseLifetime): Boolean =
        facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations

    // --- 판독 기록 -----------------------------------------------------------------------------------------------------------------

    /** 운영 람다와 같은 꼴로 읽고, 판독마다 그 스냅숏을 남기는 seam. */
    private class RecordedReads(private val supply: () -> TopicAccessSnapshot) {
        val reads = mutableListOf<TopicAccessSnapshot>()
        val authority = SnapshotTopicUseAuthority { supply().also { reads += it } }
    }

    /** 한 시점의 두 형태 판독과 각자 읽은 스냅숏, 그 시점의 contest phase와 PAC `krx` 흐름. */
    private data class Point(
        val label: String,
        val admission: KrxSendAdmission,
        val admissionRead: TopicAccessSnapshot,
        val acquisition: KrxSendAcquisition,
        val acquisitionRead: TopicAccessSnapshot,
        val phase: KrxContestPhase?,
        val krxFlow: KrxCapabilityState
    )

    private fun RecordedReads.point(label: String, h: KrxHarness, lifetime: TopicUseLifetime, fence: TopicSessionFence): Point {
        val admission = authority.admitsWithKrx(lifetime)
        val admissionRead = reads.last()
        val acquisition = authority.acquireWithKrx(fence)
        val acquisitionRead = reads.last()
        return Point(
            label, admission, admissionRead, acquisition, acquisitionRead,
            h.coordinator.krxContestView?.state?.phase, h.coordinator.krx.value
        )
    }

    private fun epochOf(snapshot: TopicAccessSnapshot): String? = snapshot.facts.recordFence?.krxCapabilityEpoch

    /** 같은 판독 스냅숏의 값으로 만든 KRX 사용권 기대값(5A.3 krxUse 발급: lifetime, 기록 epoch, capabilityInvalidations). */
    private fun krxUseOf(lifetime: TopicUseLifetime, snapshot: TopicAccessSnapshot) =
        KrxUseLifetime(lifetime, epochOf(snapshot), snapshot.capabilityInvalidations)

    private fun generalAllowed(snapshot: TopicAccessSnapshot, lifetime: TopicUseLifetime) =
        snapshot.facts.userAllowed && snapshot.facts.tokenStanding && snapshot.facts.token == lifetime.grant &&
            snapshot.userInvalidations == lifetime.invalidations

    private suspend fun TestScope.round(h: KrxHarness) {
        h.rejectKrx(h.issueGrant())
        step(100)
    }

    // --- 04-1 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 04-1 #20 결정 함수 대조(켬), S5·S4(AWAITING) 흡수: grantedKrxVisible → 토큰 발급 → 판독 r0 → rejectKrx(H1 회전 K1→K2) → 판독 →
     * FE StableActive(true)(T2 → OPEN(p = 2)) → 판독. 각 판독은 admits형·acquire형 둘이다.
     * 기대: r0 = (참, (K1, c0) 사용권, 표지 (K1, 1)). 회전 뒤(AWAITING): 일반 참, r0 사용권 판정 거짓, 새 판독의 KRX 사용권 null, 표지 (K2, 1).
     * OPEN(p = 2) 뒤: r0 사용권 거짓, 새 판독 (K2, c0+1) 사용권 참, 표지 (K2, 2). 비-KRX lifetime은 내내 허용.
     * r1 추가 단계: OPEN 뒤 외부 기록의 KRX 단독 회전(A1Test #24 꼴)을 관측시켜 epoch만 바뀐 스냅숏(K3, 같은 capInv·허용·토큰)에서 OPEN 사용권
     * 거짓, 새 판독 (K3, c0+1) 사용권·표지 (K3, 2)를 단언한다 — H1 회전은 epoch와 capInv를 함께 바꿔 E-X 단독을 가리기 때문이다.
     * 잡을 변이(r1 탐침 실측): W-X, E-X(외부 회전 단계). 설계 칸의 C-X 단독과 'NOT_GRANTED만 보는 식'은 이 행이 잡지 못한다(H1이 capInv도 올리고,
     * AWAITING은 NOT_GRANTED를 함께 낸다) — 각각 04-2, 04-2·04-5가 잡는다. K-SN은 r1 정정으로 04-3·01-6에 넘겼다.
     */
    @Test fun r04_1_decisionFunction_acrossH1RotationAndT2_bothForms() = checkedOnTest { h ->
        val r = RecordedReads { h.coordinator.accessSnapshot }
        h.grantedKrxVisible()
        val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { "04-1 premise: 발급된 grant" }
        val lifetime = checkNotNull(h.coordinator.accessSnapshot.headAcquireUse(fence)) { "04-1 premise: 일반 사용권" }
        val k1 = h.store.record.krxCapabilityEpoch
        val r0 = r.point("r0", h, lifetime, fence)
        val c0 = r0.admissionRead.capabilityInvalidations
        val r0Use = krxUseOf(lifetime, r0.admissionRead)
        // 창 안 대조(검토 P2): 뒤의 'r0 사용권 거짓' 둘이 공허하지 않도록, r0 사용권이 발급된 바로 그 상태에서 참임을 같이 본다.
        val r0UseAtR0 = r.authority.admitsKrx(r0Use)

        h.rejectKrx(fence.grant)
        val k2 = h.store.record.krxCapabilityEpoch
        val rotated = r.point("회전 뒤", h, lifetime, fence)
        val r0UseAfterRotation = r.authority.admitsKrx(r0Use)
        val generalAfterRotation = r.authority.admits(lifetime)

        h.source.next = { active }
        step(100)
        val opened = r.point("OPEN(p = 2) 뒤", h, lifetime, fence)
        val r0UseAfterOpen = r.authority.admitsKrx(r0Use)
        val newUse = krxUseOf(lifetime, opened.admissionRead)
        val newUseAfterOpen = r.authority.admitsKrx(newUse)
        val generalAfterOpen = r.authority.admits(lifetime)

        // r1 추가(E-X 단독): 외부 기록의 KRX 단독 회전(A1Test #24·PACT:1405와 같은 전이)을 발급의 store.load가 관측하게 한다. epoch만 바뀌고
        // capabilityInvalidations·허용·토큰은 그대로라, OPEN 사용권의 거부가 epoch 조건 하나에 달린다(H1 회전은 capInv도 함께 올려 E-X 단독을 가린다).
        h.store.record = AccessEpochTransitions.rotate(
            h.store.record, rotateUser = false, rotateKrx = true, ids = EpochIdGenerator { "external-0" }
        )
        val observedToken = h.issueGrant()
        val k3 = h.store.record.krxCapabilityEpoch
        val external = r.point("외부 회전 뒤", h, lifetime, fence)
        val newUseAfterExternal = r.authority.admitsKrx(newUse)

        // 전제
        r0.admissionRead.let { s ->
            assertEquals("04-1 premise: r0은 OPEN", KrxContestPhase.OPEN, r0.phase)
            assertTrue("04-1 premise(ON): r0에서 krxPermitSeq ≥ 1 (${s.krxPermitSeq})", s.krxPermitSeq >= 1L)
            assertEquals("04-1 premise(ON): r0의 permit은 1", 1L, s.krxPermitSeq)
            assertTrue("04-1 premise: r0에서 capabilityAllowed(허용 상태에서 KRX 사용권 확보)", s.facts.capabilityAllowed)
            assertEquals("04-1 premise: r0의 기록 epoch = K1", k1, epochOf(s))
        }
        rotated.admissionRead.let { s ->
            assertNotEquals("04-1 premise: H1이 KRX epoch를 회전했다", k1, k2)
            assertEquals("04-1 premise: 회전 뒤 판독은 AWAITING 스냅숏", KrxContestPhase.AWAITING, rotated.phase)
            assertEquals("04-1 premise: 회전 뒤 판독의 기록 epoch = K2", k2, epochOf(s))
            assertEquals("04-1 premise(ON): 회전 뒤 permit 1 그대로", 1L, s.krxPermitSeq)
            assertEquals("04-1 premise(A1): 회전 뒤 같은 토큰", fence.grant, s.facts.token)
            assertTrue("04-1 premise(A1): 회전 뒤 tokenStanding", s.facts.tokenStanding)
            assertFalse("04-1 premise: 회전 뒤 capabilityAllowed 거짓(KRX 사용권 거부를 시험하는 단계)", s.facts.capabilityAllowed)
            assertTrue("04-1 premise: 회전 뒤 일반 사용 허용", generalAllowed(s, lifetime))
            assertEquals("04-1 premise: 회전 뒤 capabilityInvalidations = c0 + 1", c0 + 1, s.capabilityInvalidations)
        }
        opened.admissionRead.let { s ->
            assertEquals("04-1 premise: FE true로 OPEN", KrxContestPhase.OPEN, opened.phase)
            assertEquals("04-1 premise(ON): OPEN 뒤 permit 2", 2L, s.krxPermitSeq)
            assertTrue("04-1 premise: OPEN 뒤 capabilityAllowed(새 사용권 확보)", s.facts.capabilityAllowed)
            assertEquals("04-1 premise: OPEN 뒤 기록 epoch = K2", k2, epochOf(s))
            assertEquals("04-1 premise: OPEN 뒤 capabilityInvalidations = c0 + 1", c0 + 1, s.capabilityInvalidations)
            assertEquals("04-1 premise(A1): OPEN 뒤 같은 토큰", fence.grant, s.facts.token)
        }
        external.admissionRead.let { s ->
            assertEquals("04-1 premise: 외부 회전 뒤 발급이 같은 토큰(A1)", fence.grant, observedToken)
            assertNotEquals("04-1 premise: 외부 회전이 epoch를 K2 → K3로 바꿨다", k2, k3)
            assertEquals("04-1 premise: 외부 회전 뒤 판독의 기록 epoch = K3(관측됨)", k3, epochOf(s))
            assertEquals("04-1 premise: 외부 회전 뒤 phase OPEN", KrxContestPhase.OPEN, external.phase)
            assertEquals("04-1 premise(ON): 외부 회전 뒤 permit 2 그대로", 2L, s.krxPermitSeq)
            assertTrue("04-1 premise: 외부 회전 뒤 capabilityAllowed 그대로(새 사용권 확보)", s.facts.capabilityAllowed)
            assertEquals("04-1 premise: 외부 회전 뒤 capabilityInvalidations = c0 + 1 그대로(차이는 epoch 하나)", c0 + 1, s.capabilityInvalidations)
            assertTrue("04-1 premise: 외부 회전 뒤 일반 사용 허용", generalAllowed(s, lifetime))
        }
        listOf(r0, rotated, opened, external).forEach { p ->
            assertEquals("04-1 premise: ${p.label} 두 형태가 같은 스냅숏을 읽었다", p.admissionRead, p.acquisitionRead)
        }

        // 계약 — HEAD와 같은 일반 판정
        listOf(r0, rotated, opened, external).forEach { p ->
            assertTrue("04-1: ${p.label} 일반(admits형) 허용", p.admission.admitted)
            assertEquals("04-1: ${p.label} 일반(acquire형) = 같은 lifetime", lifetime, p.acquisition.lifetime)
        }
        assertTrue("04-1: 회전 뒤 비-KRX lifetime 허용", generalAfterRotation)
        assertTrue("04-1: OPEN 뒤 비-KRX lifetime 허용", generalAfterOpen)

        // 계약 — KRX 사용권·표지
        assertEquals(
            "04-1 r0 (S5): admits형 = (참, (K1, c0) 사용권, 표지 (K1, 1))",
            KrxSendAdmission(true, KrxUseLifetime(lifetime, k1, c0), KrxSendMarker(k1, 1L)), r0.admission
        )
        assertEquals(
            "04-1 r0 (S5): acquire형 = (lifetime, (K1, c0) 사용권, 표지 (K1, 1))",
            KrxSendAcquisition(lifetime, KrxUseLifetime(lifetime, k1, c0), KrxSendMarker(k1, 1L)), r0.acquisition
        )
        assertTrue("04-1 r0: r0 사용권은 r0에서 참(창 안 — 뒤의 두 '거짓'의 대조)", r0UseAtR0)
        assertFalse("04-1 회전 뒤 (S4): r0 사용권 판정 거짓", r0UseAfterRotation)
        assertEquals(
            "04-1 회전 뒤 (S4): admits형 = (참, null, 표지 (K2, 1))",
            KrxSendAdmission(true, null, KrxSendMarker(k2, 1L)), rotated.admission
        )
        assertEquals(
            "04-1 회전 뒤 (S4): acquire형 = (lifetime, null, 표지 (K2, 1))",
            KrxSendAcquisition(lifetime, null, KrxSendMarker(k2, 1L)), rotated.acquisition
        )
        assertFalse("04-1 OPEN(p = 2) 뒤: r0 사용권 거짓", r0UseAfterOpen)
        assertEquals("04-1 OPEN(p = 2) 뒤: 새 사용권은 (K2, c0 + 1)", KrxUseLifetime(lifetime, k2, c0 + 1), newUse)
        assertEquals(
            "04-1 OPEN(p = 2) 뒤: admits형 = (참, (K2, c0+1) 사용권, 표지 (K2, 2))",
            KrxSendAdmission(true, newUse, KrxSendMarker(k2, 2L)), opened.admission
        )
        assertEquals(
            "04-1 OPEN(p = 2) 뒤: acquire형 = (lifetime, (K2, c0+1) 사용권, 표지 (K2, 2))",
            KrxSendAcquisition(lifetime, newUse, KrxSendMarker(k2, 2L)), opened.acquisition
        )
        assertTrue("04-1 OPEN(p = 2) 뒤: 새 사용권 판정 참", newUseAfterOpen)
        assertFalse("04-1 외부 회전 뒤(E-X 단독): epoch만 바뀌어도 OPEN 사용권 거짓", newUseAfterExternal)
        assertEquals(
            "04-1 외부 회전 뒤: admits형 = (참, (K3, c0+1) 사용권, 표지 (K3, 2))",
            KrxSendAdmission(true, KrxUseLifetime(lifetime, k3, c0 + 1), KrxSendMarker(k3, 2L)), external.admission
        )
        assertEquals(
            "04-1 외부 회전 뒤: acquire형 = (lifetime, (K3, c0+1) 사용권, 표지 (K3, 2))",
            KrxSendAcquisition(lifetime, KrxUseLifetime(lifetime, k3, c0 + 1), KrxSendMarker(k3, 2L)), external.acquisition
        )
    }

    // --- 04-2 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 04-2 #28 HOLD(S4 HOLD 흡수): 첫 바퀴(H1 → T2, OPEN p = 2) 뒤 HOLD 전 사용권 u를 확보하고, 600초 안 두 번째 모순으로 HOLD(streak 2 → 5초,
     * KrxContestPolicy.kt:51-53·:182-203) → 만료 → OPEN. 두 번째 모순은 H1′(live 판독 실패, 회전 없음 — KrxContestHarness.undecidedRejection)로
     * 넣어 u와 현재 스냅숏의 차이를 capabilityInvalidations 하나로 남긴다(그래야 C-X가 단독으로 잡힌다).
     * 기대: HOLD 중 일반 참, KRX 사용권 null(E 거짓), u 거짓. OPEN 뒤 u 거짓(capInv), 새 사용권 참. 잡을 변이: C-X, A-X.
     */
    @Test fun r04_2_holdRefusesKrx_keepsGeneral_andAPreHoldUseStaysWithdrawn() = checkedOnTest { h ->
        val r = RecordedReads { h.coordinator.accessSnapshot }
        h.grantedKrxVisible()
        round(h)
        val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { "04-2 premise: 발급된 grant" }
        val lifetime = checkNotNull(h.coordinator.accessSnapshot.headAcquireUse(fence)) { "04-2 premise: 일반 사용권" }
        val epoch = h.store.record.krxCapabilityEpoch
        val beforeHold = r.point("HOLD 전", h, lifetime, fence)
        val u = krxUseOf(lifetime, beforeHold.acquisitionRead)
        val c = beforeHold.acquisitionRead.capabilityInvalidations
        // 창 안 대조(검토 P2): 뒤의 'HOLD 전 사용권 거짓' 둘이 공허하지 않도록, 그 사용권이 확보 시점에서 참임을 같이 본다.
        val uAtIssue = r.authority.admitsKrx(u)

        h.undecidedRejection()
        val awaitingPhase = h.coordinator.krxContestView?.state?.phase
        step(5_100)
        val held = r.point("HOLD 중", h, lifetime, fence)
        val heldAt = testScheduler.currentTime
        val uDuringHold = r.authority.admitsKrx(u)
        val holdUntil = h.coordinator.krxContestView?.state?.holdUntilMillis

        if (holdUntil != null && holdUntil > testScheduler.currentTime) step(holdUntil - testScheduler.currentTime)
        val reopened = r.point("만료 뒤 OPEN", h, lifetime, fence)
        val uAfterOpen = r.authority.admitsKrx(u)
        val newUse = krxUseOf(lifetime, reopened.acquisitionRead)
        val newUseAfterOpen = r.authority.admitsKrx(newUse)

        // 전제
        beforeHold.acquisitionRead.let { s ->
            assertEquals("04-2 premise: 첫 바퀴 뒤 OPEN", KrxContestPhase.OPEN, beforeHold.phase)
            assertEquals("04-2 premise(ON): HOLD 전 사용권 확보 시점 permit 2", 2L, s.krxPermitSeq)
            assertTrue("04-2 premise: HOLD 전 사용권 확보 시점 capabilityAllowed", s.facts.capabilityAllowed)
        }
        assertEquals("04-2 premise: H1′으로 AWAITING", KrxContestPhase.AWAITING, awaitingPhase)
        held.admissionRead.let { s ->
            assertEquals("04-2 premise: 두 번째 모순의 FE true로 HOLD", KrxContestPhase.HOLD, held.phase)
            assertTrue("04-2 premise: HOLD 판독 때 만료 시각이 아직 안 왔다 ($heldAt < $holdUntil)", holdUntil != null && holdUntil > heldAt)
            assertEquals("04-2 premise(ON): HOLD 중 permit 2 그대로", 2L, s.krxPermitSeq)
            assertFalse("04-2 premise: HOLD 중 capabilityAllowed 거짓", s.facts.capabilityAllowed)
            assertTrue("04-2 premise: HOLD 중 KRX_CONTESTED", TopicAccessBlock.KRX_CONTESTED in s.facts.capabilityBlocks)
            assertTrue("04-2 premise: HOLD 중 일반 사용 허용", generalAllowed(s, lifetime))
            assertEquals("04-2 premise: H1′은 회전하지 않았다(epoch 그대로)", epoch, epochOf(s))
        }
        reopened.acquisitionRead.let { s ->
            assertEquals("04-2 premise: 만료로 OPEN", KrxContestPhase.OPEN, reopened.phase)
            assertEquals("04-2 premise(ON): 만료 허가 permit 3", 3L, s.krxPermitSeq)
            assertTrue("04-2 premise: OPEN 뒤 capabilityAllowed(새 사용권 확보)", s.facts.capabilityAllowed)
            assertEquals("04-2 premise: OPEN 뒤 epoch 그대로", epoch, epochOf(s))
            assertEquals("04-2 premise: OPEN 뒤 capabilityInvalidations = c + 1(차이는 capInv 하나)", c + 1, s.capabilityInvalidations)
        }

        // 계약 — HEAD와 같은 일반 판정
        assertTrue("04-2: HOLD 중 일반(admits형) 참", held.admission.admitted)
        assertEquals("04-2: HOLD 중 일반(acquire형) = lifetime", lifetime, held.acquisition.lifetime)

        // 계약 — KRX
        assertEquals("04-2: HOLD 전 사용권(acquire형) = (epoch, c)", u, beforeHold.acquisition.krxUse)
        assertEquals("04-2: HOLD 전 사용권(admits형) = (epoch, c)", u, beforeHold.admission.krxUse)
        assertNull("04-2: HOLD 중 KRX 사용권 null(admits형)", held.admission.krxUse)
        assertNull("04-2: HOLD 중 KRX 사용권 null(acquire형)", held.acquisition.krxUse)
        assertTrue("04-2: HOLD 전 사용권은 확보 시점에서 참(창 안 — 뒤의 두 '거짓'의 대조)", uAtIssue)
        assertFalse("04-2: HOLD 중 HOLD 전 사용권 거짓", uDuringHold)
        assertFalse("04-2: OPEN 뒤 HOLD 전 사용권 거짓(capInv)", uAfterOpen)
        assertEquals("04-2: OPEN 뒤 새 사용권(acquire형) = (epoch, c + 1)", KrxUseLifetime(lifetime, epoch, c + 1), reopened.acquisition.krxUse)
        assertEquals("04-2: OPEN 뒤 새 사용권(admits형) = (epoch, c + 1)", KrxUseLifetime(lifetime, epoch, c + 1), reopened.admission.krxUse)
        assertTrue("04-2: OPEN 뒤 새 사용권 판정 참", newUseAfterOpen)
    }

    // --- 04-3 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 04-3 CAP-R11 (i), 초안 EF-05 흡수: 공급자가 호출마다 s_a(K1, p = 1, 허용)와 s_b(K2, p = 2, 막힘)를 번갈아 낸다. 판독 1회. admits형·
     * acquire형 각각(형태마다 공급자를 새로 만들어 첫 호출이 s_a). 기대: 시도당 공급자 호출 1회, 세 값(표지·use·krxUse)이 모두 첫 스냅숏
     * s_a 값. 잡을 변이: K-SN, 두 번 판독, E와 표지를 다른 판독에서 읽음.
     * (r1 보강, 검토 P2 — 설계 표에 올릴 항목) 같은 한 판독에서 E 참·기록 epoch non-null인데 일반이 거짓이면 KRX 사용권도 null이다(5A.3
     * krxUse 발급의 'use 성공' 연언). admits형은 userInvalidations만 어긋난 s_c(acquire형에는 영향 없는 조건), acquire형은 tokenStanding만
     * 거짓인 s_d로 본다. 다른 TA-04 행은 KRX 사용권이 나와야 하는 모든 판독에서 일반이 참이라 이 연언을 판별하지 못한다. 추가 잡을 변이:
     * krxUse 발급에서 use 성공 연언 누락(admits형은 `admitsUse(lifetime)` 대신 일부 조건만 봄 포함).
     */
    @Test fun r04_3_oneSupplierReadPerAttempt_andEveryValueFromThatRead_bothForms() {
        val sA = krxSnap(recordEpoch = "K1", krxPermitSeq = 1L, capabilityInvalidations = 0L)
        val sB = krxSnap(
            recordEpoch = "K2", krxPermitSeq = 2L, capabilityInvalidations = 1L, userInvalidations = 4L,
            userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED)
        )
        val sC = krxSnap(recordEpoch = "K1", krxPermitSeq = 1L, userInvalidations = 4L)
        val sD = krxSnap(recordEpoch = "K1", krxPermitSeq = 1L, standing = false)
        val lifetime = KrxHandSnapshots.LIFETIME7
        val fence = KrxHandSnapshots.FENCE7
        assertEquals("04-3 premise: s_a에서 일반 acquire = LIFETIME7", lifetime, sA.headAcquireUse(fence))
        assertTrue("04-3 premise: s_a에서 일반 admits 참", sA.headAdmitsUse(lifetime))
        assertTrue("04-3 premise(ON): s_a permit ≥ 1", sA.krxPermitSeq >= 1L)
        assertTrue("04-3 premise: s_a capabilityAllowed", sA.facts.capabilityAllowed)
        assertNull("04-3 premise: s_b에서 일반 acquire null(막힘)", sB.headAcquireUse(fence))
        assertFalse("04-3 premise: s_b에서 일반 admits 거짓", sB.headAdmitsUse(lifetime))
        assertFalse("04-3 premise: s_b capabilityAllowed 거짓", sB.facts.capabilityAllowed)
        for ((label, s) in listOf("s_c" to sC, "s_d" to sD)) {
            assertTrue("04-3 보강 premise(ON): $label permit ≥ 1", s.krxPermitSeq >= 1L)
            assertTrue("04-3 보강 premise: $label capabilityAllowed(E 참 — 일반 연언만 남는 창)", s.facts.capabilityAllowed)
            assertEquals("04-3 보강 premise: $label 기록 epoch = K1(non-null)", "K1", epochOf(s))
        }
        assertFalse("04-3 보강 premise: s_c에서 일반 admits 거짓(userInvalidations 4 ≠ LIFETIME7의 3)", sC.headAdmitsUse(lifetime))
        assertNull("04-3 보강 premise: s_d에서 일반 acquire null(tokenStanding 거짓)", sD.headAcquireUse(fence))

        fun alternating(): Pair<SnapshotTopicUseAuthority, () -> Int> {
            var calls = 0
            val authority = SnapshotTopicUseAuthority { calls += 1; if (calls % 2 == 1) sA else sB }
            return authority to { calls }
        }

        val (admitsAuthority, admitsCalls) = alternating()
        val admission = admitsAuthority.admitsWithKrx(lifetime)
        val (acquireAuthority, acquireCalls) = alternating()
        val acquisition = acquireAuthority.acquireWithKrx(fence)

        assertEquals("04-3: admits형 시도당 공급자 호출 1회", 1, admitsCalls())
        assertEquals("04-3: acquire형 시도당 공급자 호출 1회", 1, acquireCalls())
        assertEquals(
            "04-3: admits형의 세 값이 모두 s_a 값(일반 참, (K1, 0) 사용권, 표지 (K1, 1))",
            KrxSendAdmission(true, KrxUseLifetime(lifetime, "K1", 0L), KrxSendMarker("K1", 1L)), admission
        )
        assertEquals(
            "04-3: acquire형의 세 값이 모두 s_a 값(LIFETIME7, (K1, 0) 사용권, 표지 (K1, 1))",
            KrxSendAcquisition(lifetime, KrxUseLifetime(lifetime, "K1", 0L), KrxSendMarker("K1", 1L)), acquisition
        )
        assertEquals(
            "04-3 보강(s_c): admits형 = (거짓, null, 표지 (K1, 1)) — 같은 판독의 일반이 거짓이면 KRX 사용권 null",
            KrxSendAdmission(false, null, KrxSendMarker("K1", 1L)), SnapshotTopicUseAuthority { sC }.admitsWithKrx(lifetime)
        )
        assertEquals(
            "04-3 보강(s_d): acquire형 = (null, null, 표지 (K1, 1)) — 같은 판독의 일반이 null이면 KRX 사용권 null",
            KrxSendAcquisition(null, null, KrxSendMarker("K1", 1L)), SnapshotTopicUseAuthority { sD }.acquireWithKrx(fence)
        )
    }

    // --- 04-4 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 04-4 null epoch 표지: 합성 스냅숏(확정 기록의 KRX epoch null, p = 1)에서 표지는 non-null = (null, 1)이다(r3 6절·D-15: 송신 경로는 표지를
     * 늘 non-null로 캡처한다). 두 형태 모두. 잡을 변이: K-NUL.
     * (r1 보강, 검토 P2 — 설계 표에 올릴 항목) 같은 스냅숏에서 KRX 사용권은 두 형태 모두 null이다: E 참·일반 참인데 기록 epoch만 null인 창이라
     * 5A.3 krxUse 발급의 명시 연언 `epoch ≠ null`(P3, 손 픽스처가 DERIVED_SEAL 없이 null epoch를 만들 수 있어 둔 연언)만 거부를 정한다.
     * 추가 잡을 변이: krxUse 발급에서 `epoch ≠ null` 연언 누락.
     */
    @Test fun r04_4_aNullRecordEpoch_stillGivesANonNullMarker_bothForms() {
        val snapshot = krxSnap(recordEpoch = null, krxPermitSeq = 1L)
        val lifetime = KrxHandSnapshots.LIFETIME7
        val fence = KrxHandSnapshots.FENCE7
        assertTrue("04-4 premise: 확정 기록이 있다", snapshot.facts.recordFence != null)
        assertNull("04-4 premise: 그 기록의 KRX epoch는 null", epochOf(snapshot))
        assertEquals("04-4 premise(ON): permit 1", 1L, snapshot.krxPermitSeq)
        assertTrue("04-4 premise: capabilityAllowed(E 참 — epoch 연언만 남는 창)", snapshot.facts.capabilityAllowed)
        assertTrue("04-4 premise: 일반 admits 참", snapshot.headAdmitsUse(lifetime))
        assertEquals("04-4 premise: 일반 acquire = LIFETIME7", lifetime, snapshot.headAcquireUse(fence))
        val authority = SnapshotTopicUseAuthority { snapshot }
        val admission = authority.admitsWithKrx(lifetime)
        val acquisition = authority.acquireWithKrx(fence)
        assertEquals("04-4: admits형 표지 = (null, 1)", KrxSendMarker(null, 1L), admission.marker)
        assertEquals("04-4: acquire형 표지 = (null, 1)", KrxSendMarker(null, 1L), acquisition.marker)
        assertTrue("04-4: 일반(admits형) 참", admission.admitted)
        assertEquals("04-4: 일반(acquire형) = LIFETIME7", lifetime, acquisition.lifetime)
        assertNull("04-4 보강: epoch null이면 KRX 사용권 null(admits형)", admission.krxUse)
        assertNull("04-4 보강: epoch null이면 KRX 사용권 null(acquire형)", acquisition.krxUse)
    }

    // --- 04-5 ----------------------------------------------------------------------------------------------------------------------

    /** 04-5 한 변형의 관측. */
    private class WindowRun(
        val label: String,
        val parkAfterLanding: Boolean,
        val lifetime: TopicUseLifetime,
        val e1: String?,
        val open: Point,
        val parked: Boolean,
        val storeEpochInWindow: String?,
        val window: Point,
        val released: Point,
        val reopenedPhase: KrxContestPhase?
    )

    /**
     * 04-5 회전 창 S6(초안 EF-02): 켬 OPEN(p)·KRX 수락 상태에서 KRX false 결정(C1)을 rotationGate로 회전 쓰기 앞에서 멈춘다(A1Test PATST762 꼴).
     * 착지 뒤 정지 변형(landedGate, A1Test parkAfterLanding)도 같은 PAC에서 재허가 뒤 둔다. gate는 finally에서 푼다. 두 변형을 모두 관측한
     * 뒤 전제를 전부, 그다음 계약을 단언한다.
     * 전제: CONTEXT_UNCERTAIN ∈ capabilityBlocks ∧ userAllowed ∧ tokenStanding ∧ `coordinator.krx.value == VISIBLE`(E와 PAC effective의 차이).
     * 기대: seam 판독의 일반 참, KRX 사용권 null(E 거짓), 표지 = 판독한 발행 스냅숏의 (기록 epoch, p). 풀린 뒤에도 KRX 사용권 null.
     * (r1 추가: 창 앞 OPEN 판독의 KRX 사용권 non-null — 창의 null이 공허하지 않음을 보이는 대조.)
     * 설계 문장 '착지 뒤 정지면 (e2, p)'는 발행 스냅숏이 아직 착지를 관측하기 전이라 그대로 관측되지 않는다 — 전제에서 판독 스냅숏의 기록
     * epoch를 단언하고 표지는 그 값으로 기대한다(보고서 편차 항목).
     * 잡을 변이: EF-B·EF-D(이 창에서 E가 참이 됨).
     */
    @Test fun r04_5_rotationWindow_contextUncertain_refusesKrx_withTheReadsMarker() = checkedOnTest { h ->
        val r = RecordedReads { h.coordinator.accessSnapshot }
        h.grantedKrxVisible()
        val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { "04-5 premise: 발급된 grant" }
        val runs = mutableListOf<WindowRun>()
        for (parkAfterLanding in listOf(false, true)) {
            val label = if (parkAfterLanding) "[착지 뒤 정지]" else "[착지 전 정지]"
            val lifetime = checkNotNull(h.coordinator.accessSnapshot.headAcquireUse(fence)) { "04-5 $label premise: 일반 사용권" }
            val e1 = h.store.record.krxCapabilityEpoch
            val open = r.point("$label 창 앞 OPEN", h, lifetime, fence)

            val gate = CompletableDeferred<Unit>()
            if (parkAfterLanding) h.store.landedGate = gate else h.store.rotationGate = gate
            h.source.next = { krxFalse }
            val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
            runCurrent()
            // gate가 풀리기 전 판독이 실패해도 NonCancellable 회전 대기가 끝나도록 finally에서 푼다(A1Test PATST762와 같은 이유).
            val (parked, storeEpochInWindow, window) = try {
                Triple(
                    if (parkAfterLanding) h.store.landedGate == null else h.store.rotationGate == null,
                    h.store.record.krxCapabilityEpoch,
                    r.point("$label 창", h, lifetime, fence)
                )
            } finally {
                gate.complete(Unit)
            }
            settleKrx()
            refreshing.join()
            val released = r.point("$label 풀린 뒤", h, lifetime, fence)

            // 다음 변형 준비: 재허가로 OPEN.
            h.source.next = { active }
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            runs += WindowRun(
                label, parkAfterLanding, lifetime, e1, open, parked, storeEpochInWindow, window, released,
                h.coordinator.krxContestView?.state?.phase
            )
        }

        // 전제 — 두 변형 모두
        for (run in runs) {
            val label = run.label
            run.open.admissionRead.let { s ->
                assertEquals("04-5 $label premise: 창 앞 OPEN", KrxContestPhase.OPEN, run.open.phase)
                assertTrue("04-5 $label premise(ON): 창 앞 permit ≥ 1", s.krxPermitSeq >= 1L)
                assertTrue("04-5 $label premise: 창 앞 capabilityAllowed(KRX 사용권 확보)", s.facts.capabilityAllowed)
            }
            assertTrue("04-5 $label premise: C1이 회전 쓰기에서 멈췄다", run.parked)
            if (run.parkAfterLanding) {
                assertNotEquals("04-5 $label premise: store 기록은 착지했다(e2)", run.e1, run.storeEpochInWindow)
            } else {
                assertEquals("04-5 $label premise: store 기록은 아직 e1", run.e1, run.storeEpochInWindow)
            }
            run.window.admissionRead.let { s ->
                assertEquals("04-5 $label premise: 창의 두 판독이 같은 스냅숏", s, run.window.acquisitionRead)
                assertTrue("04-5 $label premise: CONTEXT_UNCERTAIN ∈ capabilityBlocks", TopicAccessBlock.CONTEXT_UNCERTAIN in s.facts.capabilityBlocks)
                assertTrue(
                    "04-5 $label premise: 창 capability 막힘 ⊆ {EXPLICIT_SEAL, DERIVED_SEAL, CONTEXT_UNCERTAIN}(EF-B가 참이 되는 창): " +
                        "${s.facts.capabilityBlocks}",
                    s.facts.capabilityBlocks.all { it in setOf(TopicAccessBlock.EXPLICIT_SEAL, TopicAccessBlock.DERIVED_SEAL, TopicAccessBlock.CONTEXT_UNCERTAIN) }
                )
                assertTrue("04-5 $label premise: userAllowed", s.facts.userAllowed)
                assertTrue("04-5 $label premise: tokenStanding", s.facts.tokenStanding)
                assertEquals("04-5 $label premise: PAC krx 흐름은 아직 VISIBLE(E와의 차이)", KrxCapabilityState.VISIBLE, run.window.krxFlow)
                assertEquals("04-5 $label premise: 창에서 phase는 아직 OPEN", KrxContestPhase.OPEN, run.window.phase)
                assertTrue("04-5 $label premise(ON): 창 permit ≥ 1", s.krxPermitSeq >= 1L)
                assertFalse("04-5 $label premise: 창 capabilityAllowed 거짓", s.facts.capabilityAllowed)
                assertTrue("04-5 $label premise: 창 일반 사용 허용", generalAllowed(s, run.lifetime))
                assertEquals("04-5 $label premise: 창 발행 스냅숏의 기록 epoch는 아직 e1(착지 관측 전)", run.e1, epochOf(s))
            }
            run.released.admissionRead.let { s ->
                assertEquals("04-5 $label premise: 풀린 뒤 C1로 NONE", KrxContestPhase.NONE, run.released.phase)
                assertFalse("04-5 $label premise: 풀린 뒤 capabilityAllowed 거짓", s.facts.capabilityAllowed)
                assertTrue("04-5 $label premise: 풀린 뒤 일반 사용 허용(뒤의 KRX null이 일반 실패로 공허해지지 않음)", generalAllowed(s, run.lifetime))
                assertEquals("04-5 $label premise: 풀린 뒤 두 판독이 같은 스냅숏", s, run.released.acquisitionRead)
            }
            assertEquals("04-5 $label premise: 다음 변형 전 재허가로 OPEN", KrxContestPhase.OPEN, run.reopenedPhase)
        }

        // 계약 — HEAD와 같은 일반 판정(두 변형)
        for (run in runs) {
            assertTrue("04-5 ${run.label}: 창 일반(admits형) 참", run.window.admission.admitted)
            assertEquals("04-5 ${run.label}: 창 일반(acquire형) = lifetime", run.lifetime, run.window.acquisition.lifetime)
        }
        // 계약 — KRX 사용권·표지(두 변형)
        for (run in runs) {
            val label = run.label
            assertEquals(
                "04-5 $label: 창 앞 OPEN의 KRX 사용권은 non-null(대조)",
                krxUseOf(run.lifetime, run.open.acquisitionRead), run.open.acquisition.krxUse
            )
            assertNull("04-5 $label: 창 KRX 사용권 null(admits형)", run.window.admission.krxUse)
            assertNull("04-5 $label: 창 KRX 사용권 null(acquire형)", run.window.acquisition.krxUse)
            val p = run.window.admissionRead.krxPermitSeq
            assertEquals("04-5 $label: 창 표지(admits형) = (판독 스냅숏 epoch, p)", KrxSendMarker(epochOf(run.window.admissionRead), p), run.window.admission.marker)
            assertEquals("04-5 $label: 창 표지(acquire형) = (판독 스냅숏 epoch, p)", KrxSendMarker(epochOf(run.window.acquisitionRead), p), run.window.acquisition.marker)
            assertNull("04-5 $label: 풀린 뒤 KRX 사용권 null(admits형)", run.released.admission.krxUse)
            assertNull("04-5 $label: 풀린 뒤 KRX 사용권 null(acquire형)", run.released.acquisition.krxUse)
        }
    }

    // --- 04-O1 ---------------------------------------------------------------------------------------------------------------------

    /**
     * 04-O1 OFF(나), 초안 EF-03 흡수: 04-1·04-2와 같은 입력열을 [HEAD_FACTORY] PAC로. 별도로 premium·KRX VISIBLE 상태 판독(r0).
     * 전제(OFF): 관측한 모든 판독에서 krxPermitSeq = 0(capabilityInvalidations도 0). r0에서 capabilityAllowed ∧ p = 0 ∧ krx.value == VISIBLE(S1).
     * 기대: KRX 사용권 늘 null(E 거짓), 표지 (현재 epoch, 0) non-null. 일반 = 같은 스냅숏의 동결 HEAD 식(회전 뒤 거짓 — tokenStanding 거짓).
     * HEAD를 지키는 단언(일반·KRX null)을 먼저, α 값인 표지를 마지막에 둔다. 잡을 변이: G-OFF, G-GE.
     */
    @Test fun r04_O1_offPac_neverGivesAKrxUse_andMarksWithPermitZero() = runTest {
        val processJob = SupervisorJob()
        try {
            val h = krxHarness(processJob, HEAD_FACTORY)
            val r = RecordedReads { h.coordinator.accessSnapshot }
            val points = mutableListOf<Triple<Point, TopicUseLifetime, TopicSessionFence>>()

            // 04-1 입력열
            h.grantedKrxVisible()
            val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { "04-O1 premise: 발급된 grant" }
            val lifetime = checkNotNull(h.coordinator.accessSnapshot.headAcquireUse(fence)) { "04-O1 premise: 일반 사용권" }
            val r0 = r.point("r0(premium·KRX VISIBLE)", h, lifetime, fence)
            points += Triple(r0, lifetime, fence)
            h.rejectKrx(fence.grant)
            val rotated = r.point("회전 뒤", h, lifetime, fence)
            points += Triple(rotated, lifetime, fence)
            h.source.next = { active }
            step(100)
            points += Triple(r.point("FE 뒤", h, lifetime, fence), lifetime, fence)

            // 04-2 입력열
            round(h)
            val fence2 = checkNotNull(h.coordinator.topicGrantResult().fence) { "04-O1 premise: 다시 발급된 grant" }
            val lifetime2 = checkNotNull(h.coordinator.accessSnapshot.headAcquireUse(fence2)) { "04-O1 premise: 일반 사용권(2)" }
            points += Triple(r.point("둘째 바퀴 뒤", h, lifetime2, fence2), lifetime2, fence2)
            h.undecidedRejection()
            step(5_100)
            points += Triple(r.point("H1′ 뒤 FE", h, lifetime2, fence2), lifetime2, fence2)
            step(5_000)
            points += Triple(r.point("5초 더 뒤", h, lifetime2, fence2), lifetime2, fence2)

            // 전제
            assertNull("04-O1 premise(OFF): 꺼짐 PAC에는 contest view가 없다", h.coordinator.krxContestView)
            points.forEach { (p, _, _) ->
                assertEquals("04-O1 premise(OFF): ${p.label} admits형 판독 permit 0", 0L, p.admissionRead.krxPermitSeq)
                assertEquals("04-O1 premise(OFF): ${p.label} acquire형 판독 permit 0", 0L, p.acquisitionRead.krxPermitSeq)
                assertEquals("04-O1 premise(OFF): ${p.label} capabilityInvalidations 0", 0L, p.admissionRead.capabilityInvalidations)
            }
            r0.admissionRead.let { s ->
                assertTrue("04-O1 premise: r0 capabilityAllowed", s.facts.capabilityAllowed)
                assertEquals("04-O1 premise: r0 PAC krx 흐름 VISIBLE(S1 차이)", KrxCapabilityState.VISIBLE, r0.krxFlow)
                assertTrue("04-O1 premise: r0 일반 허용", generalAllowed(s, lifetime))
                assertNotNull("04-O1 premise: r0 기록 epoch non-null(G-OFF면 KRX 사용권이 나올 창)", epochOf(s))
                assertEquals("04-O1 premise: r0 두 판독이 같은 스냅숏", s, r0.acquisitionRead)
            }
            assertFalse("04-O1 premise: 꺼짐 회전 뒤 tokenStanding 거짓(A1 없음)", rotated.admissionRead.facts.tokenStanding)

            // 계약 — 일반 = 동결 HEAD 식(HEAD 보존)
            points.forEach { (p, use, useFence) ->
                assertEquals("04-O1: ${p.label} admits형 일반 = HEAD admitsUse", p.admissionRead.headAdmitsUse(use), p.admission.admitted)
                assertEquals("04-O1: ${p.label} acquire형 일반 = HEAD acquireUse", p.acquisitionRead.headAcquireUse(useFence), p.acquisition.lifetime)
            }
            assertTrue("04-O1: r0 일반 참", r0.admission.admitted)
            assertFalse("04-O1: 회전 뒤 일반 거짓(HEAD — tokenStanding 거짓)", rotated.admission.admitted)

            // 계약 — KRX 사용권 늘 null
            points.forEach { (p, _, _) ->
                assertNull("04-O1: ${p.label} KRX 사용권 null(admits형)", p.admission.krxUse)
                assertNull("04-O1: ${p.label} KRX 사용권 null(acquire형)", p.acquisition.krxUse)
            }

            // 계약 — 표지 (현재 epoch, 0), non-null
            points.forEach { (p, _, _) ->
                assertEquals("04-O1: ${p.label} 표지(admits형) = (판독 epoch, 0)", KrxSendMarker(epochOf(p.admissionRead), 0L), p.admission.marker)
                assertEquals("04-O1: ${p.label} 표지(acquire형) = (판독 epoch, 0)", KrxSendMarker(epochOf(p.acquisitionRead), 0L), p.acquisition.marker)
            }
        } finally {
            processJob.cancel()
        }
    }
}
