package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13‴ 근거 기록·필요 판정·합류 규칙의 순수 표 시험(s6_2b0b_contracts.r2.md 4.4, 보충 A1 r9 1절·3절 G1~G5).
 *
 * 필요 = 억제된 true가 있고, 그 시작 순번(최댓값)이 가장 최근 억제 발행 뒤에 사실대로 발행된 안정 답의 시작 순번보다 크다.
 * 억제 발행은 그 전의 사실대로 발행 기록을 비운다(G2).
 */
class KrxReapprovalEvidenceTest {

    private val empty = KrxReapprovalEvidence.EMPTY

    @Test
    fun empty_isNotNeeded_andHasNoOrders() {
        assertEquals(null, empty.suppressedTrueOrder)
        assertEquals(null, empty.truthfulOrder)
        assertFalse(empty.needed())
    }

    @Test
    fun suppressedOnly_isNeeded() {
        val e = empty.onSuppressedTrue(5)
        assertEquals(5L, e.suppressedTrueOrder)
        assertEquals(null, e.truthfulOrder)
        assertTrue(e.needed())
    }

    @Test
    fun truthfulOnly_isNotNeeded() {
        val e = empty.onTruthfulStable(5)
        assertEquals(null, e.suppressedTrueOrder)
        assertEquals(5L, e.truthfulOrder)
        assertFalse(e.needed())
    }

    /**
     * 억제 뒤 늦게 시작한 사실 발행(CAP-K17): 필요 없음. 필요는 억제 순번이 사실 순번보다 '클' 때만이다(보충 A1 1절, sup > tru).
     * 같은 순번은 같은 조회라 실제로는 생기지 않지만, 규칙상 필요가 아니다. 먼저 시작한 사실 발행은 필요를 없애지 않는다.
     */
    @Test
    fun laterTruthfulAfterSuppression_clearsNeed_needRequiresStrictlyGreaterSuppressed() {
        val s = empty.onSuppressedTrue(5)
        assertFalse(s.onTruthfulStable(6).needed())
        assertFalse("sup > tru가 아니면 필요 없음", s.onTruthfulStable(5).needed())
        assertTrue(s.onTruthfulStable(4).needed())
    }

    /** 완료 순서 역전(CAP-K18): 억제 true(q2)보다 먼저 시작한 false(q1)가 나중에 발행돼도 필요가 남는다. */
    @Test
    fun earlierStartedTruthfulPublishedLater_keepsNeed() {
        val e = empty.onSuppressedTrue(12).onTruthfulStable(11)
        assertEquals(12L, e.suppressedTrueOrder)
        assertEquals(11L, e.truthfulOrder)
        assertTrue(e.needed())
    }

    /** Codex r5 반례(CAP-K26): 먼저 발행된 사실 기록이 나중의 억제를 상쇄하지 못한다. 억제 발행이 truthfulOrder를 비운다. */
    @Test
    fun suppressionAfterTruthful_clearsTruthful_andIsNeeded() {
        val e = empty.onTruthfulStable(13).onSuppressedTrue(12)
        assertEquals(12L, e.suppressedTrueOrder)
        assertEquals("억제 발행이 그 전의 사실 기록을 비운다", null, e.truthfulOrder)
        assertTrue(e.needed())
    }

    /** suppressedTrueOrder는 최댓값을 보존한다. 오래된 억제가 늦게 와도 줄지 않는다. */
    @Test
    fun suppressedOrder_keepsMaximum() {
        val e = empty.onSuppressedTrue(10).onSuppressedTrue(7)
        assertEquals(10L, e.suppressedTrueOrder)
        assertEquals(null, e.truthfulOrder)
        assertTrue(e.needed())
    }

    /** truthfulOrder도 최댓값을 보존한다(억제가 비우기 전까지). */
    @Test
    fun truthfulOrder_keepsMaximum() {
        val e = empty.onSuppressedTrue(5).onTruthfulStable(9).onTruthfulStable(6)
        assertEquals(9L, e.truthfulOrder)
        assertFalse(e.needed())
    }

    /** 억제 → 사실 → 억제: 다시 필요해진다. */
    @Test
    fun suppressTruthfulSuppress_isNeededAgain() {
        val e = empty.onSuppressedTrue(5).onTruthfulStable(9).onSuppressedTrue(11)
        assertEquals(11L, e.suppressedTrueOrder)
        assertEquals(null, e.truthfulOrder)
        assertTrue(e.needed())
    }

    /** 이전 주기의 큰 사실 순번 뒤에 그보다 오래된 억제가 와도, 억제가 비우므로 필요가 참이다(발행 순서 기준). */
    @Test
    fun oldSuppressionAfterNewTruthful_isStillNeeded() {
        val e = empty.onSuppressedTrue(5).onTruthfulStable(20).onSuppressedTrue(8)
        assertEquals(8L, e.suppressedTrueOrder)
        assertEquals(null, e.truthfulOrder)
        assertTrue(e.needed())
    }

    // --- G6 (b): 억제 true보다 늦게 시작한 CAPABILITY 질의 후보만 기다린다 ---------------------------------------------

    @Test
    fun blocksOnCandidate_onlyStrictlyNewerThanSuppressed() {
        val e = empty.onSuppressedTrue(10)
        assertTrue(e.blocksOnCandidate(11))
        assertFalse("같은 순번은 더 새로운 근거가 아니다", e.blocksOnCandidate(10))
        assertFalse(e.blocksOnCandidate(9))
    }

    @Test
    fun blocksOnCandidate_withoutSuppression_neverBlocks() {
        assertFalse(empty.blocksOnCandidate(0))
        assertFalse(empty.blocksOnCandidate(Long.MAX_VALUE))
    }

    // --- G4·G5: reapprovalOnly 합류 규칙 -----------------------------------------------------------------------------

    /** 이전 표시: null = 칸 없음, false = 일반 칸, true = 재승인 전용 칸. 다섯 합류 × 세 이전 상태 전수. */
    @Test
    fun reapprovalOnlyAfter_fullTable() {
        val expected = mapOf(
            // 재승인이 빈 칸에 들어감 → true
            Triple(KrxDemandMerge.REAPPROVAL_INTO_EMPTY, null, 0) to true,
            // 재승인이 재승인 전용 칸에 합류 → true (간격·재판정을 잃지 않는다)
            Triple(KrxDemandMerge.REAPPROVAL_INTO_REAPPROVAL_ONLY, true, 0) to true,
            // 재승인이 일반 칸에 합류(반대 순서, CAP-K28 (ii)) → false
            Triple(KrxDemandMerge.REAPPROVAL_INTO_OTHER, false, 0) to false,
            // 다른 독립 사건 합류(같은 intent 포함, CAP-K28 (i)) → false
            Triple(KrxDemandMerge.OTHER_INDEPENDENT, true, 0) to false,
            Triple(KrxDemandMerge.OTHER_INDEPENDENT, false, 0) to false,
            Triple(KrxDemandMerge.OTHER_INDEPENDENT, null, 0) to false,
            // 같은 demand의 재시도 → 이전 표시 유지
            Triple(KrxDemandMerge.RETRY, true, 0) to true,
            Triple(KrxDemandMerge.RETRY, false, 0) to false
        )
        expected.forEach { (key, want) ->
            val (merge, previous, _) = key
            assertEquals("$merge after $previous", want, reapprovalOnlyAfter(previous, merge))
        }
    }

    /** 합류 종류와 이전 표시가 맞지 않는 조합은 계약 위반이다(예: 칸이 있는데 '빈 칸에 들어감'). */
    @Test
    fun reapprovalOnlyAfter_inconsistentCombinations_fail() {
        val inconsistent = listOf(
            KrxDemandMerge.REAPPROVAL_INTO_EMPTY to true,
            KrxDemandMerge.REAPPROVAL_INTO_EMPTY to false,
            KrxDemandMerge.REAPPROVAL_INTO_REAPPROVAL_ONLY to null,
            KrxDemandMerge.REAPPROVAL_INTO_REAPPROVAL_ONLY to false,
            KrxDemandMerge.REAPPROVAL_INTO_OTHER to null,
            KrxDemandMerge.REAPPROVAL_INTO_OTHER to true,
            KrxDemandMerge.RETRY to null
        )
        inconsistent.forEach { (merge, previous) ->
            val failed = runCatching { reapprovalOnlyAfter(previous, merge) }.exceptionOrNull()
            assertTrue("$merge after $previous 는 IllegalArgumentException이어야 한다", failed is IllegalArgumentException)
        }
    }

    /** 순수 타입은 값으로만 바뀐다: 같은 입력열이면 같은 결과, 원본은 그대로. */
    @Test
    fun valueSemantics_inputUnchanged() {
        val a = empty.onSuppressedTrue(3)
        val b = a.onTruthfulStable(4)
        assertEquals(3L, a.suppressedTrueOrder)
        assertEquals(null, a.truthfulOrder)
        assertEquals(KrxReapprovalEvidence.EMPTY.onSuppressedTrue(3).onTruthfulStable(4), b)
    }
}
