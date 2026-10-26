package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-08 정책 상태 탐색 성질(설계 r5 13절 T.9, 2절 ②(a) 켬 불변식). 행 08-1·08-2·08-3.
 *
 * [KrxContestPolicy.next]를 INITIAL(KrxContestPolicy.kt:18-27)에서 깊이 [DEPTH]까지 너비 우선으로 전부 돌리고, 도달한 모든 전이 s→s′에서
 * `s′.phase = OPEN ⟹ s′.permitSeq ≥ 1`과 `s′.permitSeq ≥ s.permitSeq`를 단언한다. 0a 코드와 기존 KrxContestPolicyTest는 바꾸지 않는다.
 *
 * **판정기는 규칙과 독립이다.** 정책 함수 `effectiveOpen`·`contested`(:87-90)를 부르지 않고 상태 필드(phase·permitSeq)만 직접 비교한다.
 * 결함 규칙 대조군 둘(08-2)이 같은 탐색기에서 위반을 찾는 것으로 탐색기 자체의 공허함을 막는다.
 *
 * **주장 범위(08-3).** '전수'는 아래 유한 영역 안에서만 주장한다 — 사건 = KeyChanged(openOnEntry T/F), HideEvidence(order),
 * Answer(startOrder, kind 4종, runtimeOpen T/F), HoldTimerFired(holdSeq ∈ {현재, 현재−1}, openOnExpiry T/F), KrxVisiblePublished.
 * order는 현재 barrier 기준 {b−1, b, b+1}(barrier가 null이면 {0, 1}), 사건마다 앞선 시각 증분 {0, 5_000, 60_000, 600_000}ms, 깊이 [DEPTH].
 * 시각은 절대값 그대로 두고 (상태, 시각) 쌍으로 중복을 지운다(추상화 없음). 깊이 [DEPTH]는 계약 잠금 때 Codex와 수치 합의할 값이다
 * (설계 r5 T.9 08-1 '깊이 상한 N'). 최단 도달 깊이(r1 검토 P2 정정): HOLD는 깊이 4(INITIAL → HideEvidence → STABLE_TRUE(OPEN p1) →
 * HideEvidence → STABLE_TRUE(HOLD)), 만료 HOLD의 재허가(HoldTimerFired(false) 뒤 KrxVisiblePublished 또는 STABLE_TRUE, :76·:177-178)는 깊이 6이다.
 * 이 r1의 [DEPTH] = 6은 그 마지막 경로까지 닿는 가장 얕은 값이다. 08-1 전제가 사건 종류 다섯 각각의 적용 전이 ≥ 1과 '만료 HOLD → OPEN' 전이
 * ≥ 1을 따로 고정하므로, 합의한 N이 그보다 작으면 탐색 범위가 조용히 줄지 않고 전제가 실패한다.
 * 정책의 `validState`(KrxContestPolicy.kt:92-100)는 'OPEN ⟹ permitSeq ≥ 1'을 검사하지 않는다. 그래서 이 성질은 '유효 상태 전부'가 아니라
 * 'INITIAL에서 도달한 상태'에 대한 주장이다(08-3이 그 사실을 시험으로 고정한다).
 *
 * P-CON(PAC 쪽 `KRX_CONTESTED` 삭제)은 정책 밖이라 이 클래스가 잡지 못한다 — TA-09 09-3이 맡는다.
 */
internal class KrxContestPolicyPermitInvariantTest {

    private companion object {
        /** 계약 잠금 때 Codex와 합의할 깊이 상한(설계 r5 T.9 08-1). */
        const val DEPTH = 6
        val INCREMENTS = listOf(0L, 5_000L, 60_000L, 600_000L)

        /** 실패 메시지에 담을 위반 수 상한. 전부 담으면 결함 규칙에서 위반이 수십만 건이라 메시지 작성이 OutOfMemoryError가 된다(g3-fix 탐침 P-RST 실측). */
        const val KEPT_VIOLATIONS = 20
    }

    private enum class Clause { OPEN_IMPLIES_PERMIT, PERMIT_MONOTONE }

    private data class Node(val state: KrxContestState, val now: Long)

    private data class Violation(
        val clause: Clause,
        val from: KrxContestState,
        val event: KrxContestEvent,
        val now: Long,
        val to: KrxContestState
    )

    private class Exploration(
        val nodes: Int,
        val appliedTransitions: Long,
        val phases: Set<KrxContestPhase>,
        val maxPermit: Long,
        /** 위반 전체 수. */
        val violationCount: Long,
        /** 처음 [KEPT_VIOLATIONS]건(메시지용). */
        val violations: List<Violation>,
        /** [explore]의 stopWhen이 참이 된 위반(대조군용), 없으면 null. */
        val stoppedOn: Violation?,
        /** AWAITING·HOLD에서 OPEN으로 간 적용 전이 수 — permitSeq와 무관한 재허가 도달 표지(P-INC에서도 같은 수가 남는다). */
        val reissues: Long,
        /** 사건 종류별 적용 전이 수(KeyChanged·HideEvidence·Answer·HoldTimerFired·KrxVisiblePublished). */
        val appliedByEvent: Map<String, Long>,
        /** 만료된 HOLD(holdExpired)에서 OPEN으로 간 적용 전이 수(깊이 6 경로). */
        val expiredHoldReissues: Long
    ) {
        val stats: String
            get() = "노드 $nodes, 적용 전이 $appliedTransitions, 방문 phase $phases, 최대 permit $maxPermit, 재허가 $reissues, " +
                "사건별 적용 $appliedByEvent, 만료 HOLD 재허가 $expiredHoldReissues, 위반 $violationCount"
    }

    private fun eventClass(event: KrxContestEvent): String = when (event) {
        is KrxContestEvent.KeyChanged -> "KeyChanged"
        is KrxContestEvent.HideEvidence -> "HideEvidence"
        is KrxContestEvent.Answer -> "Answer"
        is KrxContestEvent.HoldTimerFired -> "HoldTimerFired"
        KrxContestEvent.KrxVisiblePublished -> "KrxVisiblePublished"
    }

    private val eventClasses = listOf("KeyChanged", "HideEvidence", "Answer", "HoldTimerFired", "KrxVisiblePublished")

    private fun events(state: KrxContestState): List<KrxContestEvent> {
        val orders = state.barrier?.let { listOf(it - 1, it, it + 1) } ?: listOf(0L, 1L)
        return buildList {
            add(KrxContestEvent.KeyChanged(openOnEntry = true))
            add(KrxContestEvent.KeyChanged(openOnEntry = false))
            orders.forEach { add(KrxContestEvent.HideEvidence(it)) }
            for (order in orders) for (kind in KrxAnswerKind.entries) for (open in listOf(true, false)) {
                add(KrxContestEvent.Answer(order, kind, open))
            }
            for (seq in listOf(state.holdSeq, state.holdSeq - 1)) for (open in listOf(true, false)) {
                add(KrxContestEvent.HoldTimerFired(seq, open))
            }
            add(KrxContestEvent.KrxVisiblePublished)
        }
    }

    /**
     * INITIAL에서 [depth]까지 BFS. 판정은 필드 비교뿐이다(규칙 술어를 부르지 않는다). 새 위반에 [stopWhen]이 참이 되면 그 자리에서 멈춘다(대조군용).
     */
    private fun explore(
        depth: Int,
        rule: (KrxContestState, KrxContestEvent, Long) -> KrxContestStep,
        stopWhen: (Violation) -> Boolean = { false }
    ): Exploration {
        val start = Node(KrxContestState.INITIAL, 0L)
        val visited = HashSet<Node>().apply { add(start) }
        var frontier = listOf(start)
        val phases = mutableSetOf(start.state.phase)
        var maxPermit = start.state.permitSeq
        var applied = 0L
        val violations = mutableListOf<Violation>()
        var violationCount = 0L
        var stoppedOn: Violation? = null
        var reissues = 0L
        var expiredHoldReissues = 0L
        val byEvent = mutableMapOf<String, Long>()
        fun result() = Exploration(
            visited.size, applied, phases, maxPermit, violationCount, violations.toList(), stoppedOn, reissues, byEvent.toMap(), expiredHoldReissues
        )
        fun found(violation: Violation): Boolean {
            violationCount += 1
            if (violations.size < KEPT_VIOLATIONS) violations += violation
            if (stopWhen(violation)) stoppedOn = violation
            return stoppedOn != null
        }
        for (level in 1..depth) {
            val next = ArrayList<Node>()
            for (node in frontier) {
                for (increment in INCREMENTS) {
                    val now = node.now + increment
                    for (event in events(node.state)) {
                        val step = rule(node.state, event, now)
                        val to = if (step is KrxContestStep.Applied) step.state else node.state
                        if (step is KrxContestStep.Applied) {
                            applied += 1
                            byEvent.merge(eventClass(event), 1L, Long::plus)
                            if (to.phase == KrxContestPhase.OPEN &&
                                node.state.phase in setOf(KrxContestPhase.AWAITING, KrxContestPhase.HOLD)
                            ) {
                                reissues += 1
                            }
                            if (node.state.phase == KrxContestPhase.HOLD && node.state.holdExpired && to.phase == KrxContestPhase.OPEN) {
                                expiredHoldReissues += 1
                            }
                            if (to.phase == KrxContestPhase.OPEN && to.permitSeq < 1L &&
                                found(Violation(Clause.OPEN_IMPLIES_PERMIT, node.state, event, now, to))
                            ) {
                                return result()
                            }
                            if (to.permitSeq < node.state.permitSeq &&
                                found(Violation(Clause.PERMIT_MONOTONE, node.state, event, now, to))
                            ) {
                                return result()
                            }
                        }
                        phases += to.phase
                        if (to.permitSeq > maxPermit) maxPermit = to.permitSeq
                        val reached = Node(to, now)
                        if (visited.add(reached)) next += reached
                    }
                }
            }
            frontier = next
        }
        return result()
    }

    /**
     * 08-1 유한 영역 전수: 도달한 모든 전이에서 OPEN ⟹ permitSeq ≥ 1, permitSeq는 줄지 않는다.
     * 전제: OPEN·HOLD·AWAITING을 각각 1회 이상 방문, AWAITING·HOLD → OPEN 재허가 전이 ≥ 1, 사건 종류 다섯 각각 적용 전이 ≥ 1, 만료 HOLD → OPEN
     * 전이 ≥ 1(탐색이 issue·HOLD·만료 경로를 실제로 지났다).
     * r1 검토 반영(P1, 설계 전제 칸과의 차이 — Codex 합의 대상): 설계 전제 칸의 'permitSeq ≥ 2 상태 방문'은 P-INC(+1 삭제)에서 먼저 거짓이 되어
     * P-INC를 계약이 아닌 전제가 죽이게 만든다. 그래서 전제 자리에는 permitSeq와 무관한 재허가 전이 수를 두고, 'permitSeq ≥ 2 도달'은 계약 단언
     * 뒤의 경로 확인으로 옮겼다. 사건 종류별·만료 HOLD 전제(검토 P2)도 permitSeq를 읽지 않는다.
     * 잡을 변이: P-INC(issue의 +1 삭제, KrxContestPolicy.kt:138), P-RST(예: KeyChanged :62-68에 permitSeq 0).
     */
    @Test fun r08_1_finiteRegion_openImpliesAPermit_andThePermitNeverDecreases() {
        val result = explore(DEPTH, KrxContestPolicy::next)
        val stats = result.stats
        assertTrue("08-1 premise: OPEN 방문 — $stats", KrxContestPhase.OPEN in result.phases)
        assertTrue("08-1 premise: HOLD 방문 — $stats", KrxContestPhase.HOLD in result.phases)
        assertTrue("08-1 premise: AWAITING 방문 — $stats", KrxContestPhase.AWAITING in result.phases)
        assertTrue("08-1 premise: AWAITING/HOLD에서 OPEN으로 재허가한 전이 ≥ 1 — $stats", result.reissues > 0L)
        for (kind in eventClasses) {
            assertTrue("08-1 premise: 사건 $kind 의 적용 전이 ≥ 1 — $stats", (result.appliedByEvent[kind] ?: 0L) > 0L)
        }
        assertTrue("08-1 premise: 만료 HOLD(holdExpired)에서 OPEN으로 간 전이 ≥ 1 — $stats", result.expiredHoldReissues > 0L)
        assertTrue("08-1 premise: 적용 전이가 있었다 — $stats", result.appliedTransitions > 0L)
        assertTrue(
            "08-1: 깊이 $DEPTH 유한 영역의 모든 도달 전이에서 OPEN ⟹ permitSeq ≥ 1 ∧ permitSeq 비감소 — $stats; 처음 위반 ${result.violations.take(3)}",
            result.violationCount == 0L
        )
        assertTrue("08-1 (경로 확인, 계약 뒤): permitSeq ≥ 2 도달 — $stats", result.maxPermit >= 2L)
    }

    /**
     * 08-2 결함 규칙 대조군: 같은 탐색기를 시험 안 결함 래퍼 둘에 적용하면 둘 다 위반을 찾는다(탐색기 공허화 방지).
     * ① 실제 next 결과가 OPEN이면 permitSeq를 0으로 — 'OPEN ⟹ permitSeq ≥ 1' 조항이 잡는다.
     * ② KeyChanged의 결과에서 permitSeq를 0으로 — 'permitSeq 비감소' 조항이 OPEN 아닌 결과(openOnEntry = false)에서도 따로 잡는다.
     * 판정기는 두 대조군에서도 규칙 술어를 부르지 않는다.
     */
    @Test fun r08_2_defectiveRuleControls_areBothCaught() {
        val zeroOnOpen: (KrxContestState, KrxContestEvent, Long) -> KrxContestStep = { s, e, now ->
            when (val step = KrxContestPolicy.next(s, e, now)) {
                is KrxContestStep.Applied ->
                    if (step.state.phase == KrxContestPhase.OPEN) KrxContestStep.Applied(step.state.copy(permitSeq = 0L)) else step
                is KrxContestStep.Ignored -> step
            }
        }
        val zeroOnKeyChange: (KrxContestState, KrxContestEvent, Long) -> KrxContestStep = { s, e, now ->
            when (val step = KrxContestPolicy.next(s, e, now)) {
                is KrxContestStep.Applied ->
                    if (e is KrxContestEvent.KeyChanged) KrxContestStep.Applied(step.state.copy(permitSeq = 0L)) else step
                is KrxContestStep.Ignored -> step
            }
        }

        val first = explore(DEPTH, zeroOnOpen) { it.clause == Clause.OPEN_IMPLIES_PERMIT }
        assertTrue(
            "08-2 ①: OPEN 결과의 permitSeq를 0으로 만든 대조군에서 'OPEN ⟹ permitSeq ≥ 1' 위반을 찾는다 (멈춘 위반 ${first.stoppedOn}, 위반 ${first.violationCount})",
            first.stoppedOn?.clause == Clause.OPEN_IMPLIES_PERMIT
        )

        val second = explore(DEPTH, zeroOnKeyChange) { it.clause == Clause.PERMIT_MONOTONE && it.to.phase != KrxContestPhase.OPEN }
        assertTrue(
            "08-2 ②: KeyChanged에서 permitSeq를 0으로 만든 대조군에서 OPEN 아닌 결과의 'permitSeq 비감소' 위반을 찾는다 " +
                "(멈춘 위반 ${second.stoppedOn}, 처음 위반 ${second.violations.take(3).map { it.clause to it.to.phase }})",
            second.stoppedOn.let { it != null && it.clause == Clause.PERMIT_MONOTONE && it.to.phase != KrxContestPhase.OPEN }
        )
    }

    /**
     * 08-3 주장 범위: `validState`(KrxContestPolicy.kt:92-100)는 'OPEN ⟹ permitSeq ≥ 1'을 검사하지 않는다 — OPEN·permitSeq 0 상태를 넣어도
     * INVALID_INPUT으로 거부하지 않고 전이를 적용한다. 그래서 08-1의 '전수'는 도달 상태·유한 영역에 대한 주장일 뿐이다(KDoc 참조).
     * (설계 행은 KDoc 기재만 요구한다. 관측 가능한 형태로 그 사실을 고정한 재진술.)
     */
    @Test fun r08_3_claimScope_validStateDoesNotCheckOpenImpliesAPermit() {
        val openWithoutAPermit = KrxContestState.INITIAL.copy(phase = KrxContestPhase.OPEN, permitSeq = 0L)
        val step = KrxContestPolicy.next(openWithoutAPermit, KrxContestEvent.HideEvidence(0L), 0L)
        assertTrue(
            "08-3: OPEN·permitSeq 0 상태도 유효 입력으로 받아 전이를 적용한다(validState가 이 조합을 거부하지 않는다): $step",
            step is KrxContestStep.Applied
        )
        assertEquals(
            "08-3: 그 결과는 AWAITING — 정책이 'OPEN ⟹ permitSeq ≥ 1'을 전제로 검사하지 않음",
            KrxContestPhase.AWAITING, (step as KrxContestStep.Applied).state.phase
        )
    }
}
