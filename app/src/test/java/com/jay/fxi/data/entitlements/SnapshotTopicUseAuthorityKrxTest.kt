package com.jay.fxi.data.entitlements

import com.jay.fxi.data.entitlements.KrxHandSnapshots.FENCE7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.LIFETIME7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K1
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K2
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-01 SUA KRX 사용권(설계 r5 13절 T.2, 행 01-1·01-2·01-3·01-4·01-5·01-6·01-O1).
 *
 * 기대식: 설계 r5 4절 SUA 켬 행과 5A.3 `krxUse` — 발급과 판정 모두 `admitsUse(lifetime) ∧ E(s) ∧ 사용권 epoch =
 * recordFence.krxCapabilityEpoch ≠ null ∧ 사용권 capInv = capabilityInvalidations`, E(s) = `krxConsumerGuard(krxPermitSeq) ∧
 * capabilityAllowed`(2절 D-7, 5A D-9). 꺼짐(p = 0)에서는 HEAD처럼 KRX 사용권이 없다(de50885 SnapshotTopicUseAuthority.kt:11-12).
 * 일반 acquire·admits는 HEAD 그대로다 — 이 파일 끝의 동결 사본(de50885 SnapshotTopicUseAuthority.kt:14-22)과 비교한다(01-O1).
 *
 * 하네스는 S-1(KrxHandSnapshots.kt)뿐이다. SUAT의 private `snapshot(...)`(SUAT:22-36)은 쓰지 않는다. 켬 PAC가 없으므로 S-4는 쓰지 않는다.
 *
 * 전제 표기(T.0): `premise:` = HEAD에서도 성립하는 준비 조건. `premise(ON):` = 지정한 스냅숏에서 `krxPermitSeq ≥ 1`.
 * KRX 사용권을 허용 상태에서 확보하는 단계에는 `capabilityAllowed = true`를, KRX 사용권 거부를 시험하는 capability 막힘 단계에는
 * `capabilityAllowed = false`와 일반 use 허용을 따로 단언한다. `premise(OFF):` = 관측한 모든 스냅숏에서 `krxPermitSeq = 0`.
 * 거짓·null 단언 앞에는 그 판정이 의도한 창 안에 있음을 먼저 단언한다(사용권이 실제로 발급됐다, 일반 판정은 통과한다 등).
 * (r1 고침) 일반 acquire·admits에 관한 `premise:`는 동결 HEAD 사본(headAcquireUse·headAdmitsUse)으로만 계산한다 — HEAD에서 성립하는 준비
 * 조건이라는 T.0 정의에 맞추고, 운영 일반 판정의 변이(W-X)가 전제 실패로 가려지지 않게 한다. 운영 확장 함수·SUA 래퍼의 일반 판정은 계약으로만 본다.
 *
 * epoch가 null인 옛 사용권(01-5, 01-O1)은 acquire가 null epoch에서 사용권을 내지 않으므로 `KrxUseLifetime(lifetime, null, c)`로 직접 만든다.
 */
internal class SnapshotTopicUseAuthorityKrxTest {

    /**
     * 01-1 #5 짝(SUAT:66): SUAT:67과 같은 capability 막힘 {LOSS_CANDIDATE, NOT_GRANTED}, p = 1, 기록 K1. 일반 acquire·admits는 SUAT:68-70과
     * 같고, KRX 사용권 acquire는 null이며, 막힘 전 허용 스냅숏(K1, c0, p = 1)에서 얻은 사용권은 막힘 스냅숏에서 거짓이다.
     * 막힘 스냅숏의 capInv는 허용 스냅숏과 같은 c0으로 둔다(합성) — capabilityAllowed 하나만 다르게 두어야 A-X가 이 행에서 잡힌다.
     * (r1 고침) 설계는 일반 acquire·admits를 전제와 계약 둘 다로 적는다. 전제는 동결 HEAD 사본으로만 보고(HEAD에서 성립), 운영 확장 함수와
     * SUA 래퍼는 계약으로 본다 — W-X가 일반 판정에 들어가면 전제 실패가 아니라 계약 실패로 보고된다.
     * 잡을 변이: W-X(SUA 일반 판정에 KRX 막힘을 섞음), A-X.
     */
    @Test
    fun r01_1_capabilityOnlyHold_keepsSuat67GeneralUse_refusesKrxUse() {
        val allowed = krxSnap(recordEpoch = "K1", capabilityInvalidations = 0L, krxPermitSeq = 1L)
        val held = krxSnap(
            capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.NOT_GRANTED),
            recordEpoch = "K1",
            capabilityInvalidations = 0L,
            krxPermitSeq = 1L
        )
        val sevenThree = TopicUseLifetime(TopicGrantToken(7L), 3L)

        assertEquals("01-1 premise: 막힘 스냅숏의 일반 acquire(HEAD 사본) = SUAT:68 값", sevenThree, held.headAcquireUse(FENCE7))
        assertTrue("01-1 premise: 막힘 스냅숏의 일반 admits(HEAD 사본) = SUAT:70 값", held.headAdmitsUse(sevenThree))
        assertTrue("01-1 premise(ON): 막힘 스냅숏 krxPermitSeq ≥ 1", held.krxPermitSeq >= 1)
        assertFalse("01-1 premise(ON): 막힘 스냅숏 capabilityAllowed = false", held.facts.capabilityAllowed)
        assertEquals("01-1 premise: 막힘 스냅숏 기록 epoch K1", "K1", held.facts.recordFence?.krxCapabilityEpoch)
        assertTrue("01-1 premise(ON): 허용 스냅숏 krxPermitSeq ≥ 1", allowed.krxPermitSeq >= 1)
        assertTrue("01-1 premise(ON): 허용 스냅숏 capabilityAllowed = true", allowed.facts.capabilityAllowed)
        assertEquals(
            "01-1 premise: 두 스냅숏은 capabilityBlocks만 다르다(capInv·기록·토큰·사용자 축 같음)",
            allowed.copy(facts = allowed.facts.copy(capabilityBlocks = held.facts.capabilityBlocks)),
            held
        )

        val heldAuthority = SnapshotTopicUseAuthority { held }
        assertEquals("01-1 contract: 일반 acquire(확장 함수)는 SUAT:68과 같다", sevenThree, held.acquireUse(FENCE7))
        assertTrue("01-1 contract: 일반 admits(확장 함수)는 SUAT:70과 같다", held.admitsUse(sevenThree))
        assertEquals("01-1 contract: 일반 acquire는 SUAT:68-69와 같다", sevenThree, heldAuthority.acquire(FENCE7))
        assertTrue("01-1 contract: 일반 admits는 SUAT:70과 같다", heldAuthority.admits(sevenThree))

        val u0 = allowed.acquireKrxUse(FENCE7)
        assertEquals(
            "01-1 contract(창): 막힘 전 허용 스냅숏에서 KRX 사용권 (K1, c0) 발급",
            KrxUseLifetime(LIFETIME7, "K1", 0L),
            u0
        )
        assertTrue("01-1 contract(창): 허용 스냅숏은 그 사용권을 허용", allowed.admitsKrxUse(checkNotNull(u0)))

        assertNull("01-1 contract: 막힘 스냅숏의 KRX acquire = null", held.acquireKrxUse(FENCE7))
        assertNull("01-1 contract: 막힘 스냅숏의 KRX acquire = null(SUA 래퍼)", heldAuthority.acquireKrx(FENCE7))
        assertFalse("01-1 contract: 막힘 전 사용권의 admits = false", held.admitsKrxUse(u0))
        assertFalse("01-1 contract: 막힘 전 사용권의 admits = false(SUA 래퍼)", heldAuthority.admitsKrx(u0))
    }

    /**
     * 01-2 OD-2 왕복: s0 허용(K1, c0, p = 1) → s1 {KRX_CONTESTED}(K1, c0+1, p = 1) → s2 허용(K1, c0+1, p = 2), 토큰·userInvalidations 같음.
     * s0 사용권은 s0 참·s1 거짓·s2 거짓. s2 새 acquire = (K1, c0+1)이고 s2에서 참. 일반 admits는 세 스냅숏 모두 참.
     * 잡을 변이: C-X.
     */
    @Test
    fun r01_2_holdRoundTrip_withdrawsTheOldKrxUse_andIssuesANewOne() {
        val s0 = krxSnap(recordEpoch = "K1", capabilityInvalidations = 0L, krxPermitSeq = 1L)
        val s1 = krxSnap(
            capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED),
            recordEpoch = "K1",
            capabilityInvalidations = 1L,
            krxPermitSeq = 1L
        )
        val s2 = krxSnap(recordEpoch = "K1", capabilityInvalidations = 1L, krxPermitSeq = 2L)
        var published = s0
        val authority = SnapshotTopicUseAuthority { published }

        assertTrue("01-2 premise(ON): s0 krxPermitSeq ≥ 1", s0.krxPermitSeq >= 1)
        assertTrue("01-2 premise(ON): s0 capabilityAllowed = true", s0.facts.capabilityAllowed)
        for ((name, s) in listOf("s1" to s1, "s2" to s2)) {
            assertEquals("01-2 premise: $name 토큰이 s0과 같다", s0.facts.token, s.facts.token)
            assertEquals("01-2 premise: $name userInvalidations가 s0과 같다", s0.userInvalidations, s.userInvalidations)
            assertEquals("01-2 premise: $name 기록 epoch가 s0과 같다", "K1", s.facts.recordFence?.krxCapabilityEpoch)
        }

        val u0 = authority.acquireKrx(FENCE7)
        assertEquals("01-2 contract: s0에서 사용권 (K1, c0) 발급", KrxUseLifetime(LIFETIME7, "K1", 0L), u0)
        checkNotNull(u0)
        assertTrue("01-2 contract: s0 사용권은 s0에서 참", authority.admitsKrx(u0))
        assertTrue("01-2 contract: 일반 admits는 s0에서 참", authority.admits(LIFETIME7))

        published = s1
        assertTrue("01-2 premise(ON): s1 krxPermitSeq ≥ 1", s1.krxPermitSeq >= 1)
        assertFalse("01-2 premise(ON): s1 capabilityAllowed = false", s1.facts.capabilityAllowed)
        assertTrue("01-2 contract(창): 일반 admits는 s1에서 참(일반 use 허용)", authority.admits(LIFETIME7))
        assertFalse("01-2 contract: s0 사용권은 s1에서 거짓", authority.admitsKrx(u0))

        published = s2
        assertTrue("01-2 premise(ON): s2 krxPermitSeq ≥ 1", s2.krxPermitSeq >= 1)
        assertTrue("01-2 premise(ON): s2 capabilityAllowed = true", s2.facts.capabilityAllowed)
        assertTrue("01-2 contract(창): 일반 admits는 s2에서 참", authority.admits(LIFETIME7))
        val fresh = authority.acquireKrx(FENCE7)
        assertEquals("01-2 contract(창): s2 새 acquire = (K1, c0+1)", KrxUseLifetime(LIFETIME7, "K1", 1L), fresh)
        assertTrue("01-2 contract: s2 새 사용권은 s2에서 참", authority.admitsKrx(checkNotNull(fresh)))
        assertFalse("01-2 contract: s0 사용권은 s2에서 거짓(capInv)", authority.admitsKrx(u0))
    }

    /**
     * 01-3 #28 epoch: s0 사용권 → s3 기록 K2, 허용, 같은 토큰 standing(A1형 손 빌더), c0+1, p = 2. 합성 변형 s3e: K2인데 capInv c0 그대로.
     * s3에서 옛 사용권 거짓, 새 사용권 (K2, c0+1) 참. s3e에서 옛 사용권 거짓(epoch만 다르다).
     * (r1 고침) s3e의 부정 단언 앞에 s3e 자신이 켜진 스냅숏임을 단언한다: s3e는 자신의 사용권 (K2, c0)을 내고 허용한다(창).
     * 잡을 변이: E-X(s3e에서만 단독 검출. s3e의 켬 PAC 도달성은 설계 18절 미확인).
     */
    @Test
    fun r01_3_recordEpochMove_withdrawsTheOldKrxUse_underTheSameStandingToken() {
        val s0 = a1Snap(record = RECORD_K1, capabilityInvalidations = 0L, krxPermitSeq = 1L)
        val s3 = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = 1L, krxPermitSeq = 2L)
        val s3e = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = 0L, krxPermitSeq = 2L)

        assertTrue("01-3 premise(ON): s0 krxPermitSeq ≥ 1", s0.krxPermitSeq >= 1)
        assertTrue("01-3 premise(ON): s0 capabilityAllowed = true", s0.facts.capabilityAllowed)
        val u0 = s0.acquireKrxUse(FENCE7)
        assertEquals("01-3 contract(창): s0에서 사용권 (K1, c0) 발급", KrxUseLifetime(LIFETIME7, "K1", 0L), u0)
        checkNotNull(u0)
        assertTrue("01-3 contract(창): s0 사용권은 s0에서 참", s0.admitsKrxUse(u0))

        for ((name, s) in listOf("s3" to s3, "s3e" to s3e)) {
            assertTrue("01-3 premise: $name 같은 토큰 standing(A1형)", s.facts.tokenStanding)
            assertEquals("01-3 premise: $name 토큰 7", TopicGrantToken(7L), s.facts.token)
            assertTrue("01-3 premise: $name 일반 admits(HEAD 사본) 참", s.headAdmitsUse(LIFETIME7))
            assertEquals("01-3 premise: $name 기록 epoch K2", "K2", s.facts.recordFence?.krxCapabilityEpoch)
            assertTrue("01-3 premise(ON): $name krxPermitSeq ≥ 1", s.krxPermitSeq >= 1)
            assertTrue("01-3 premise(ON): $name capabilityAllowed = true", s.facts.capabilityAllowed)
        }
        assertEquals("01-3 premise: s3e capInv는 옛 사용권과 같다(epoch만 다름)", u0.capabilityInvalidations, s3e.capabilityInvalidations)

        val fresh = s3.acquireKrxUse(FENCE7)
        assertEquals("01-3 contract(창): s3 새 사용권 (K2, c0+1)", KrxUseLifetime(LIFETIME7, "K2", 1L), fresh)
        assertTrue("01-3 contract: s3 새 사용권은 s3에서 참", s3.admitsKrxUse(checkNotNull(fresh)))
        assertFalse("01-3 contract: s3에서 옛 사용권 거짓", s3.admitsKrxUse(u0))
        val ownE = s3e.acquireKrxUse(FENCE7)
        assertEquals("01-3 contract(창): s3e 자신의 사용권 (K2, c0)", KrxUseLifetime(LIFETIME7, "K2", 0L), ownE)
        assertTrue("01-3 contract(창): s3e는 자신의 사용권을 허용", s3e.admitsKrxUse(checkNotNull(ownE)))
        assertFalse("01-3 contract: s3e에서 옛 사용권 거짓(epoch만 다름)", s3e.admitsKrxUse(u0))
    }

    /**
     * 01-4 사용자 축: s0 사용권 → userInvalidations 3→4 또는 userBlocks {LOSS_CANDIDATE}. 일반·KRX 모두 거짓.
     * 바뀐 스냅숏은 capability 쪽(허용, 기록 K1, capInv c0, p ≥ 1)이 s0과 같아 사용자 축만이 거부 이유다.
     * (r1 고침, 설계 입력 밖 강화) 설계 r5 01-4 입력은 userInvalidations·userBlocks만 적는다. 그것만으로는 KRX 발급을 일반 use가 실패하는
     * 스냅숏에서 돌리지 않고, 판정의 토큰·standing 부분도 돌리지 않는다 — 발급이 `acquireUse(fence)` 없이 facts.token·userInvalidations로
     * 사용권을 만드는 변이(M1)와 판정이 `facts.token == u.lifetime.grant`나 `facts.tokenStanding`을 빼는 변이(M2)가 12행 모두를 통과한다.
     * 그래서 변형 둘(토큰 8, standing 거짓)을 더하고, 변형마다 KRX acquire를 단언한다: HEAD 일반 acquire가 null이면 KRX acquire도 null,
     * 서면(userInvalidations 4) 그 lifetime으로 (K1, c0) 사용권이 발급되고 그 스냅숏에서 허용된다(4절 SUA 행 '발급과 판정 모두 admitsUse',
     * 10-2 '일반 acquire 성공' 연언). 01-O1 꺼짐 칸에도 두 변형을 더했다.
     * 잡을 변이: KRX 판정에서 `admitsUse`(SUA.kt:21-22) 누락, M1(발급에서 `acquireUse(fence)` 누락), M2(판정에서 토큰·standing 누락).
     */
    @Test
    fun r01_4_userAxisOrTokenChange_withdrawsBothTheGeneralAndTheKrxUse() {
        val s0 = krxSnap(recordEpoch = "K1", capabilityInvalidations = 0L, krxPermitSeq = 1L)
        assertTrue("01-4 premise(ON): s0 krxPermitSeq ≥ 1", s0.krxPermitSeq >= 1)
        assertTrue("01-4 premise(ON): s0 capabilityAllowed = true", s0.facts.capabilityAllowed)
        assertTrue("01-4 premise: s0 일반 admits(HEAD 사본) 참", s0.headAdmitsUse(LIFETIME7))
        val u0 = s0.acquireKrxUse(FENCE7)
        assertEquals("01-4 contract(창): s0에서 사용권 (K1, c0) 발급", KrxUseLifetime(LIFETIME7, "K1", 0L), u0)
        checkNotNull(u0)
        assertTrue("01-4 contract(창): s0 사용권은 s0에서 참", s0.admitsKrxUse(u0))

        val lossCandidate = setOf(TopicAccessBlock.LOSS_CANDIDATE)
        // (이름, 변형 스냅숏, s0에서 그 축 하나만 바꾼 값)
        val variants = listOf(
            Triple(
                "userInvalidations 3→4",
                krxSnap(recordEpoch = "K1", userInvalidations = 4L, krxPermitSeq = 1L),
                s0.copy(userInvalidations = 4L)
            ),
            Triple(
                "userBlocks {LOSS_CANDIDATE}",
                krxSnap(recordEpoch = "K1", userBlocks = lossCandidate, krxPermitSeq = 1L),
                s0.copy(facts = s0.facts.copy(userBlocks = lossCandidate))
            ),
            Triple(
                "token 8",
                krxSnap(token = 8L, recordEpoch = "K1", krxPermitSeq = 1L),
                s0.copy(facts = s0.facts.copy(token = TopicGrantToken(8L)))
            ),
            Triple(
                "standing false",
                krxSnap(standing = false, recordEpoch = "K1", krxPermitSeq = 1L),
                s0.copy(facts = s0.facts.copy(tokenStanding = false))
            )
        )
        val generals = variants.map { (_, s, _) -> s.headAcquireUse(FENCE7) }
        assertTrue(
            "01-4 premise: HEAD 일반 acquire가 서는 변형(발급 대조)과 실패하는 변형(null 판정)이 둘 다 있다",
            generals.any { it == null } && generals.any { it != null }
        )

        for ((name, s, onlyThisAxis) in variants) {
            assertEquals("01-4 premise: [$name] s0과 이 축 하나만 다르다(capability 쪽·기록 K1·capInv c0·p 같음)", onlyThisAxis, s)
            assertTrue("01-4 premise(ON): [$name] krxPermitSeq ≥ 1", s.krxPermitSeq >= 1)
            assertTrue("01-4 premise(ON): [$name] capabilityAllowed = true(capability 쪽은 s0과 같다)", s.facts.capabilityAllowed)
            assertFalse("01-4 premise: [$name] 일반 admits(HEAD 사본) 거짓 — 거부 이유는 이 축", s.headAdmitsUse(LIFETIME7))

            val authority = SnapshotTopicUseAuthority { s }
            assertFalse("01-4 contract: [$name] 일반 admits 거짓", s.admitsUse(LIFETIME7))
            assertFalse("01-4 contract: [$name] KRX admits 거짓", s.admitsKrxUse(u0))
            assertFalse("01-4 contract: [$name] KRX admits 거짓(SUA 래퍼)", authority.admitsKrx(u0))

            val general = s.headAcquireUse(FENCE7)
            assertEquals("01-4 contract: [$name] 일반 acquire(확장 함수) = HEAD 사본", general, s.acquireUse(FENCE7))
            if (general == null) {
                assertNull("01-4 contract: [$name] 일반 acquire 실패 → KRX acquire = null", s.acquireKrxUse(FENCE7))
                assertNull("01-4 contract: [$name] 일반 acquire 실패 → KRX acquire = null(SUA 래퍼)", authority.acquireKrx(FENCE7))
            } else {
                val issued = s.acquireKrxUse(FENCE7)
                assertEquals(
                    "01-4 contract(대조): [$name] 일반 acquire가 서면 KRX 사용권은 그 lifetime으로 발급",
                    KrxUseLifetime(general, "K1", 0L),
                    issued
                )
                assertTrue("01-4 contract(대조): [$name] 그 사용권은 이 스냅숏에서 참", s.admitsKrxUse(checkNotNull(issued)))
            }
        }
    }

    /**
     * 01-5 null epoch: (i) 기록 epoch null + capabilityAllowed 참(합성, DERIVED_SEAL 없음) (ii) 기록 없음. 토큰 standing·사용자 축 허용.
     * KRX acquire = null이고, epoch null 사용권은 어떤 스냅숏에서도 거짓이다. epoch null 사용권은 acquire가 내지 않으므로 직접 만든다.
     * 거짓·null 단언이 공허하지 않도록 같은 스냅숏에서 epoch만 K1으로 바꾼 짝이 사용권을 내고 허용함을 먼저 단언한다(r1 추가 대조).
     * 잡을 변이: N-X.
     */
    @Test
    fun r01_5_nullRecordEpoch_issuesNoKrxUse_andANullEpochUseIsNeverAdmitted() {
        for (c in listOf(0L, 1L)) {
            val nullEpoch = krxSnap(recordEpoch = null, capabilityInvalidations = c, krxPermitSeq = 1L)
            val noRecord = krxSnap(recordConfirmed = false, capabilityInvalidations = c, krxPermitSeq = 1L)
            val siblingK1 = krxSnap(recordEpoch = "K1", capabilityInvalidations = c, krxPermitSeq = 1L)
            val siblingK2 = krxSnap(recordEpoch = "K2", capabilityInvalidations = c, krxPermitSeq = 1L)
            val label = "c=$c"

            for ((name, s) in listOf("(i) 기록 epoch null" to nullEpoch, "(ii) 기록 없음" to noRecord)) {
                assertEquals("01-5 premise: [$label $name] 토큰 standing·사용자 축 허용(일반 acquire, HEAD 사본)", LIFETIME7, s.headAcquireUse(FENCE7))
                assertNull("01-5 premise: [$label $name] 기록 epoch null", s.facts.recordFence?.krxCapabilityEpoch)
                assertTrue("01-5 premise(ON): [$label $name] krxPermitSeq ≥ 1", s.krxPermitSeq >= 1)
                assertTrue("01-5 premise(ON): [$label $name] capabilityAllowed = true(합성)", s.facts.capabilityAllowed)
            }
            assertNull("01-5 premise: [$label] (ii)는 기록 자체가 없다", noRecord.facts.recordFence)
            assertEquals(
                "01-5 premise: [$label] 대조 짝은 기록 epoch만 다르다",
                nullEpoch.copy(facts = nullEpoch.facts.copy(recordFence = KrxHandSnapshots.record("K1"))),
                siblingK1
            )

            val siblingUse = siblingK1.acquireKrxUse(FENCE7)
            assertEquals("01-5 contract(창): [$label] 기록 epoch K1 짝은 사용권 (K1, c)를 낸다", KrxUseLifetime(LIFETIME7, "K1", c), siblingUse)
            assertTrue("01-5 contract(창): [$label] 그 짝은 그 사용권을 허용", siblingK1.admitsKrxUse(checkNotNull(siblingUse)))

            assertNull("01-5 contract: [$label] (i) KRX acquire = null", nullEpoch.acquireKrxUse(FENCE7))
            assertNull("01-5 contract: [$label] (ii) KRX acquire = null", noRecord.acquireKrxUse(FENCE7))
            assertNull("01-5 contract: [$label] (i) KRX acquire = null(SUA 래퍼)", SnapshotTopicUseAuthority { nullEpoch }.acquireKrx(FENCE7))

            val nullUse = KrxUseLifetime(LIFETIME7, null, c)
            for ((name, s) in listOf("(i)" to nullEpoch, "(ii)" to noRecord, "K1 허용" to siblingK1, "K2 허용" to siblingK2)) {
                assertTrue("01-5 premise: [$label $name] 일반 admits(HEAD 사본) 참(창)", s.headAdmitsUse(LIFETIME7))
                assertEquals("01-5 premise: [$label $name] capInv가 사용권과 같다", c, s.capabilityInvalidations)
                assertFalse("01-5 contract: [$label $name] epoch null 사용권 거짓", s.admitsKrxUse(nullUse))
            }
        }
    }

    /**
     * 01-6 질문당 1회 판독: `SnapshotTopicUseAuthority { reads++; … }`(SUAT:73-89 꼴), 공급자가 호출마다 다른 객체 s_a(K1·p1·c0)와
     * s_b(K2·p2·c1)를 번갈아 돌려준다. KRX acquire·admits 각각 판독 1회이고, 결과는 그 질문이 읽은 한 객체만으로 설명된다.
     * (r1 고침, 설계 입력 밖 하위 단언) s_a·s_b는 둘 다 가드를 통과하므로 가드를 앞선 판독에서 캐시해 쓰는 단일 판독형 G-SN은 위 입력만으로
     * 갈리지 않는다. 가드만 다른 짝 s_a0(s_a에서 krxPermitSeq만 0)을 s_a와 번갈아 공급해 admits(u_a)·acquire를 각각 두 번 묻고, 읽은 객체가
     * s_a면 허용·발급, s_a0면 거부·null임을 단언한다(질문마다 판독 1회).
     * 잡을 변이: G-SN(두 번 판독형·캐시된 가드형), K-SN.
     */
    @Test
    fun r01_6_eachKrxQuestion_readsThePublishedSnapshotOnce() {
        val sA = krxSnap(recordEpoch = "K1", krxPermitSeq = 1L, capabilityInvalidations = 0L, revision = 100L)
        val sB = krxSnap(recordEpoch = "K2", krxPermitSeq = 2L, capabilityInvalidations = 1L, revision = 101L)
        assertNotSame("01-6 premise: 두 공급 객체가 다르다", sA, sB)
        for ((name, s) in listOf("s_a" to sA, "s_b" to sB)) {
            assertTrue("01-6 premise(ON): $name krxPermitSeq ≥ 1", s.krxPermitSeq >= 1)
            assertTrue("01-6 premise(ON): $name capabilityAllowed = true", s.facts.capabilityAllowed)
            assertEquals("01-6 premise: $name 일반 acquire(HEAD 사본) 성공", LIFETIME7, s.headAcquireUse(FENCE7))
        }
        val issued = mapOf(sA to KrxUseLifetime(LIFETIME7, "K1", 0L), sB to KrxUseLifetime(LIFETIME7, "K2", 1L))

        var reads = 0
        var lastRead: TopicAccessSnapshot? = null
        val authority = SnapshotTopicUseAuthority {
            reads += 1
            (if (reads % 2 == 1) sA else sB).also { lastRead = it }
        }

        val before = reads
        val acquired = authority.acquireKrx(FENCE7)
        assertEquals("01-6 contract: KRX acquire 판독 1회", 1, reads - before)
        assertNotNull("01-6 premise: acquire가 공급자를 읽었다", lastRead)
        val acquireRead = lastRead!!
        assertEquals(
            "01-6 contract: KRX acquire 결과는 읽은 한 객체(${if (acquireRead === sA) "s_a" else "s_b"})로만 설명된다",
            issued.getValue(acquireRead),
            acquired
        )

        val uA = KrxUseLifetime(LIFETIME7, "K1", 0L)
        val uB = KrxUseLifetime(LIFETIME7, "K2", 1L)
        for ((useName, use) in listOf("u_a(K1,c0)" to uA, "u_b(K2,c1)" to uB)) {
            repeat(2) { round ->
                val readsBefore = reads
                val admitted = authority.admitsKrx(use)
                assertEquals("01-6 contract: KRX admits 판독 1회 [$useName #$round]", 1, reads - readsBefore)
                val read = lastRead!!
                val readName = if (read === sA) "s_a" else "s_b"
                val expected = issued.getValue(read) == use
                assertEquals("01-6 contract: KRX admits [$useName #$round]는 읽은 한 객체($readName)로만 설명된다", expected, admitted)
            }
        }

        // (r1 고침, 설계 입력 밖) G-SN 단일 판독형: s_a·s_b는 둘 다 가드를 통과하므로 가드를 앞선(캐시된) 판독에서 가져와도 결과가 같다.
        // 가드만 다른 짝 s_a0(s_a에서 krxPermitSeq만 0)을 번갈아 공급해, 가드가 이 질문의 판독에서 왔음을 결과로 가른다.
        val sA0 = sA.copy(krxPermitSeq = 0L, revision = 102L)
        assertEquals("01-6 premise: s_a0은 s_a와 facts가 같다", sA.facts, sA0.facts)
        assertEquals("01-6 premise: s_a0은 s_a와 capInv·userInvalidations가 같다",
            sA.capabilityInvalidations to sA.userInvalidations, sA0.capabilityInvalidations to sA0.userInvalidations)
        assertEquals("01-6 premise: s_a0 krxPermitSeq = 0(가드만 다름)", 0L, sA0.krxPermitSeq)
        assertTrue("01-6 premise(ON): s_a krxPermitSeq ≥ 1", sA.krxPermitSeq >= 1)
        assertTrue("01-6 premise: s_a0 일반 admits(HEAD 사본) 참", sA0.headAdmitsUse(LIFETIME7))
        var guardReads = 0
        var guardLast: TopicAccessSnapshot? = null
        val guardAuthority = SnapshotTopicUseAuthority {
            guardReads += 1
            (if (guardReads % 2 == 1) sA else sA0).also { guardLast = it }
        }
        val readNames = mutableSetOf<String>()
        repeat(2) { round ->
            val readsBefore = guardReads
            val admitted = guardAuthority.admitsKrx(uA)
            assertEquals("01-6 contract: KRX admits 판독 1회 [s_a/s_a0 admits #$round]", 1, guardReads - readsBefore)
            val read = checkNotNull(guardLast)
            if (read === sA) {
                readNames += "s_a"
                assertTrue("01-6 contract: [s_a/s_a0 admits #$round] 읽은 s_a(p = 1)로 u_a 허용", admitted)
            } else {
                assertTrue("01-6 premise: [s_a/s_a0 admits #$round] 읽은 객체는 s_a0(창)", read === sA0)
                readNames += "s_a0"
                assertFalse("01-6 contract: [s_a/s_a0 admits #$round] 읽은 s_a0(p = 0)로 u_a 거부", admitted)
            }
        }
        repeat(2) { round ->
            val readsBefore = guardReads
            val acquiredNow = guardAuthority.acquireKrx(FENCE7)
            assertEquals("01-6 contract: KRX acquire 판독 1회 [s_a/s_a0 acquire #$round]", 1, guardReads - readsBefore)
            val read = checkNotNull(guardLast)
            if (read === sA) {
                readNames += "s_a"
                assertEquals("01-6 contract: [s_a/s_a0 acquire #$round] 읽은 s_a(p = 1)로 (K1, c0) 발급", uA, acquiredNow)
            } else {
                assertTrue("01-6 premise: [s_a/s_a0 acquire #$round] 읽은 객체는 s_a0(창)", read === sA0)
                readNames += "s_a0"
                assertNull("01-6 contract: [s_a/s_a0 acquire #$round] 읽은 s_a0(p = 0)로 발급 없음", acquiredNow)
            }
        }
        assertEquals("01-6 premise: s_a와 s_a0이 모두 읽혔다(창)", setOf("s_a", "s_a0"), readNames)
    }

    /**
     * 01-O1 OFF (가): 01-1~01-5의 모든 스냅숏을 p = 0으로. capInv는 입력 그대로 둔 합성 칸 포함(꺼짐 PAC는 0만 낸다).
     * KRX acquire는 늘 null, admits는 늘 거짓. 일반 acquire·admits는 동결한 HEAD 사본(de50885 SnapshotTopicUseAuthority.kt:14-22)과
     * 모든 입력에서 같다. 거짓 단언이 공허하지 않도록 '가드만 빼면 허용되는' 칸(p = 0·capInv > 0 칸 포함)이 있음을 먼저 단언한다.
     * 잡을 변이: G-OFF, G-CI(p = 0·capInv > 0 칸), G-GE.
     */
    @Test
    fun r01_O1_permitZero_issuesAndAdmitsNoKrxUse_andKeepsHeadGeneralUse() {
        val cells = offCells()
        val uses = listOf("K1", "K2", null).flatMap { e -> listOf(0L, 1L).map { c -> KrxUseLifetime(LIFETIME7, e, c) } }
        val lifetimes = listOf(LIFETIME7, TopicUseLifetime(TopicGrantToken(7L), 4L), TopicUseLifetime(TopicGrantToken(8L), 3L))

        for ((label, s) in cells) {
            assertEquals("01-O1 premise(OFF): [$label] krxPermitSeq = 0", 0L, s.krxPermitSeq)
        }
        val guardOnly = cells.flatMap { (label, s) ->
            uses.filter { u -> wouldAdmitButForGuard(s, u) }.map { u -> label to u }
        }
        assertTrue("01-O1 premise: 가드만 빼면 허용되는 (스냅숏, 사용권) 칸이 있다(${guardOnly.size})", guardOnly.isNotEmpty())
        assertTrue(
            "01-O1 premise: 그 가운데 capInv > 0 칸이 있다(G-CI)",
            guardOnly.any { (_, u) -> u.capabilityInvalidations > 0 }
        )
        assertTrue(
            "01-O1 premise: 가드만 빼면 KRX acquire가 발급될 칸이 있다",
            cells.any { (_, s) -> wouldIssueButForGuard(s) }
        )

        for ((label, s) in cells) {
            val authority = SnapshotTopicUseAuthority { s }
            assertEquals("01-O1 contract: [$label] 일반 acquire = HEAD 사본", s.headAcquireUse(FENCE7), s.acquireUse(FENCE7))
            assertEquals("01-O1 contract: [$label] 일반 acquire(SUA 래퍼) = HEAD 사본", s.headAcquireUse(FENCE7), authority.acquire(FENCE7))
            for (lifetime in lifetimes) {
                assertEquals("01-O1 contract: [$label] 일반 admits($lifetime) = HEAD 사본", s.headAdmitsUse(lifetime), s.admitsUse(lifetime))
                assertEquals(
                    "01-O1 contract: [$label] 일반 admits($lifetime, SUA 래퍼) = HEAD 사본",
                    s.headAdmitsUse(lifetime),
                    authority.admits(lifetime)
                )
            }
            assertNull("01-O1 contract: [$label] KRX acquire = null", s.acquireKrxUse(FENCE7))
            assertNull("01-O1 contract: [$label] KRX acquire(SUA 래퍼) = null", authority.acquireKrx(FENCE7))
            for (u in uses) {
                assertFalse("01-O1 contract: [$label] KRX admits($u) = false", s.admitsKrxUse(u))
                assertFalse("01-O1 contract: [$label] KRX admits($u, SUA 래퍼) = false", authority.admitsKrx(u))
            }
        }
    }

    /** 01-1~01-5가 쓰는 스냅숏을 p = 0으로(01-O1, r1 고침으로 01-4의 토큰 8·standing 거짓 변형 포함). capInv는 입력 그대로다. */
    private fun offCells(): List<Pair<String, TopicAccessSnapshot>> = buildList {
        val p = 0L
        add("01-1 허용" to krxSnap(recordEpoch = "K1", krxPermitSeq = p))
        add(
            "01-1 막힘" to krxSnap(
                capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.NOT_GRANTED),
                recordEpoch = "K1",
                krxPermitSeq = p
            )
        )
        add("01-2 s0" to krxSnap(recordEpoch = "K1", capabilityInvalidations = 0L, krxPermitSeq = p))
        add(
            "01-2 s1" to krxSnap(
                capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED),
                recordEpoch = "K1",
                capabilityInvalidations = 1L,
                krxPermitSeq = p
            )
        )
        add("01-2 s2(capInv 1)" to krxSnap(recordEpoch = "K1", capabilityInvalidations = 1L, krxPermitSeq = p))
        add("01-3 s0" to a1Snap(record = RECORD_K1, krxPermitSeq = p))
        add("01-3 s3" to a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = 1L, krxPermitSeq = p))
        add("01-3 s3e" to a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = 0L, krxPermitSeq = p))
        add("01-4 userInvalidations 4" to krxSnap(recordEpoch = "K1", userInvalidations = 4L, krxPermitSeq = p))
        add(
            "01-4 userBlocks" to krxSnap(
                recordEpoch = "K1",
                userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE),
                krxPermitSeq = p
            )
        )
        add("01-4 token 8" to krxSnap(token = 8L, recordEpoch = "K1", krxPermitSeq = p))
        add("01-4 standing false" to krxSnap(standing = false, recordEpoch = "K1", krxPermitSeq = p))
        for (c in listOf(0L, 1L)) {
            add("01-5 (i) c=$c" to krxSnap(recordEpoch = null, capabilityInvalidations = c, krxPermitSeq = p))
            add("01-5 (ii) c=$c" to krxSnap(recordConfirmed = false, capabilityInvalidations = c, krxPermitSeq = p))
            add("01-5 K1 c=$c" to krxSnap(recordEpoch = "K1", capabilityInvalidations = c, krxPermitSeq = p))
            add("01-5 K2 c=$c" to krxSnap(recordEpoch = "K2", capabilityInvalidations = c, krxPermitSeq = p))
        }
    }

    /** 가드 하나만 빼면 4절 SUA 켬 식이 [u]를 허용하는가(01-O1 공허 방지 전제용 — 대상 코드를 부르지 않는다). */
    private fun wouldAdmitButForGuard(s: TopicAccessSnapshot, u: KrxUseLifetime): Boolean {
        val epoch = s.facts.recordFence?.krxCapabilityEpoch
        return s.headAdmitsUse(u.lifetime) && s.facts.capabilityAllowed && epoch != null && u.krxCapabilityEpoch == epoch &&
            u.capabilityInvalidations == s.capabilityInvalidations
    }

    /** 가드 하나만 빼면 4절 SUA 켬 식이 이 스냅숏에서 KRX 사용권을 발급하는가(01-O1 공허 방지 전제용). */
    private fun wouldIssueButForGuard(s: TopicAccessSnapshot): Boolean =
        s.headAcquireUse(FENCE7) != null && s.facts.capabilityAllowed && s.facts.recordFence?.krxCapabilityEpoch != null

    private companion object {
        /** de50885 SnapshotTopicUseAuthority.kt:14-18 동결 사본(일반 acquire, 본문 그대로). */
        fun TopicAccessSnapshot.headAcquireUse(fence: TopicSessionFence): TopicUseLifetime? {
            val token = facts.token ?: return null
            if (token != fence.grant || !facts.tokenStanding || !facts.userAllowed) return null
            return TopicUseLifetime(token, userInvalidations)
        }

        /** de50885 SnapshotTopicUseAuthority.kt:21-22 동결 사본(일반 admits, 본문 그대로). */
        fun TopicAccessSnapshot.headAdmitsUse(lifetime: TopicUseLifetime): Boolean =
            facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations
    }
}
