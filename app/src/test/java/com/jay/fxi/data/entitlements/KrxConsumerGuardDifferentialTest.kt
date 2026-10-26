package com.jay.fxi.data.entitlements

import com.jay.fxi.data.entitlements.KrxHandSnapshots.FENCE7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.IDENTITY
import com.jay.fxi.data.entitlements.KrxHandSnapshots.LIFETIME7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K1
import com.jay.fxi.data.graph.GraphV2AccessCapture
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2DiskComponent
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-10 순수 판정식 차분(설계 r5 13절 T.11, 행 10-1·10-2·10-3·10-4·10-5; 2절 ③(a), 4절, 5A).
 *
 * 패키지: 설계 T.1은 TA-10의 패키지를 적지 않는다. 하네스 S-1(KrxHandSnapshots.kt)과 가드·E·SUA KRX 사용권이 모두
 * `com.jay.fxi.data.entitlements`에 있으므로 여기 둔다(gate는 같은 모듈의 internal 클래스라 이 패키지에서 부를 수 있다).
 *
 * HEAD 식 사본(이 파일 끝, 동결): gate `admitsKrx` = `captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed`
 * (de50885 GraphV2AccessGate.kt:79-80), SUA HEAD = KRX 사용권 없음(de50885 SnapshotTopicUseAuthority.kt:11-12)·일반 acquire/admits
 * (de50885 SnapshotTopicUseAuthority.kt:14-22).
 *
 * 격자: capture epoch {null, K1, K2} × 현재 기록 epoch {null, K1, K2} × capture capInv {0, 1} × 현재 capInv {0, 1} × capabilityAllowed
 * {T, F} × krxPermitSeq {0, 1}. 2절 ③(a)의 이름 붙인 칸 N·E·C·EC·Zr·Z(e1 = K1, e2 = K2)를 포함한다. 실제 소비자 API로 평가한다:
 * gate는 standing World(A1형 [a1Snap], 토큰 7은 K1 기록에 발급)에서 bind → 스냅숏 교체로 capture를 만들고(capture 생성자는 부르지
 * 않는다), SUA는 [SnapshotTopicUseAuthority]로 acquire → 스냅숏 교체 → acquire·admits. capture 시점 스냅숏은 늘 허용이고(capture
 * epoch null 칸은 기록 epoch null로 bind — 합성), 판정 시점의 capabilityAllowed 거짓은 {LOSS_CANDIDATE}로 만든다. 한 칸의 두 스냅숏은
 * 같은 krxPermitSeq를 쓴다(p는 줄지 않는다).
 *
 * SUA 옛 사용권은 acquire가 낸 값과 같은 `KrxUseLifetime(LIFETIME7, capture epoch, capture capInv)`를 직접 만들어 판정한다 — epoch null
 * 칸은 acquire가 null epoch에서 사용권을 내지 않으므로 직접 만들 수밖에 없고, 나머지 칸도 acquire 결과와 판정을 따로 단언하려고 같은
 * 꼴로 만든다(acquire가 그 값을 내는지는 같은 칸의 별도 단언).
 *
 * 격자 행은 칸마다 전제를 먼저 보고, 전제가 깨진 칸은 그 계약을 판정하지 않는다. 실패 메시지 첫 줄은 전제 실패가 있으면 첫 전제
 * 실패, 없으면 첫 계약 실패다.
 *
 * (r1 고침) 켬 칸의 'bind가 capture.capabilityInvalidations에 bind 스냅숏 값을 담는다'는 α의 새 동작(3절 D-27)이므로 r1 초판의
 * `premise(ON):` 대신 `contract(D-27):`로 단언한다(T.0: premise = HEAD에서도 성립, premise(ON) = krxPermitSeq ≥ 1). 이 계약이 깨진 칸은
 * capture가 그 칸을 나타내지 못하므로 그 칸의 gate 식 판정(admits KRX·ioAdmission KRX)은 하지 않는다 — 조용히 통과하지 않고 D-27 계약
 * 실패로 보고된다. 꺼짐 칸(p = 0)에는 D-27을 단언하지 않는다: 10-1의 기대값(HEAD 식)은 capture capInv와 무관하다.
 * 일반 use에 관한 칸 전제는 동결 HEAD 사본(headAcquireUse·headAdmitsUse)으로 계산한다(운영 일반 판정은 10-4가 계약으로 본다).
 *
 * (r1 고침) gate KRX는 `admits(capture, KRX)`와 `ioAdmission(capture).admits(KRX)` 둘 다 본다(T.13: 10-1은 gate admitsKrx·ioAdmission의
 * (가) 대조). `joinForExposure`는 검증된 디스크 성분(GraphV2DiskComponents)이 있어야 해 이 순수 격자에서는 부르지 않는다 — 그 (가) 대조는
 * 02-O1(TA-02)이 맡는다.
 *
 * (r1 고침, 격자 해석 — 편차) 설계 10-2 전제 '(premise(ON): capture 시점 허용 칸에 한해)'는 capture 시점에 허용되지 않는 칸이 격자에
 * 있음을 시사하지만, 이 클래스의 격자는 capture 스냅숏을 늘 허용으로 두고 capabilityAllowed {T, F} 축을 판정 시점에 둔다. capture epoch
 * null 칸은 기록 epoch null로 bind해 만든다(합성). capture 시점 막힘은 10-2의 추가 칸 ZN(NOT_GRANTED 중 기록 K1로 bind → capture
 * epoch null, 02-6 꼴)으로 따로 단언한다.
 *
 * (r1 고침, 범위 — 시험 r2 E-01로 결정됨) 2절 ③(a)는 HEAD 사본으로 TSC 결정 함수('거부')와 REST('전달 0', 표지 permitSeq 0/1)도 적지만
 * T.11 행 목록은 gate·SUA만 적는다. 이 클래스는 T.11을 따르며 `krxUseFor`·`krxConsumerGuard`를 직접 부르지 않는다. TSC 결정 함수는
 * TA-04(04-1·04-3·04-O1)와 11-4가, REST 전달 가드는 TA-05와 11-5가 맡는다. 그래서 이 클래스에 r10_6 행은 두지 않는다.
 */
internal class KrxConsumerGuardDifferentialTest {

    /**
     * 10-1 p = 0 동치: p = 0 전 격자에서 후보 = HEAD 사본(모든 칸). gate KRX(admits·ioAdmission) = HEAD 식, SUA KRX acquire = null·admits = 거짓.
     * 잡을 변이: G-OFF, G-GE, G-CI(capInv 1 칸), (r1 고침) ioAdmission에만 넣은 G-OFF.
     */
    @Test
    fun r10_1_permitZero_everyCellEqualsTheHeadCopy() {
        val report = Report("10-1")
        val observed = grid(permit = 0L).map { observe("10-1", it) }

        report.premise(observed.any { o -> o.gateRealized && o.headKrx == true && o.c1WouldDiffer() }) {
            "10-1 premise: C-1 연언이 HEAD와 갈릴 gate 칸이 있다(G-OFF가 보일 칸)"
        }
        report.premise(observed.any { o -> o.gateRealized && o.headKrx == true && o.cell.currentCapInv > 0 && o.c1WouldDiffer() }) {
            "10-1 premise: 현재 capInv > 0이고 C-1이 HEAD와 갈릴 gate 칸이 있다(G-CI가 보일 칸)"
        }
        report.premise(observed.any { o -> o.cell.copy(permit = 1L).let(::expectedAdmits) }) {
            "10-1 premise: 가드만 빼면 SUA가 옛 사용권을 허용할 칸이 있다"
        }
        report.premise(observed.any { o -> o.cell.copy(permit = 1L).let(::expectedReissue) != null }) {
            "10-1 premise: 가드만 빼면 SUA가 사용권을 발급할 칸이 있다"
        }

        for (o in observed) {
            o.cellPremises.forEach(report::premiseFailed)
            if (o.cellPremises.isNotEmpty()) continue
            o.gatePremises.forEach(report::premiseFailed)
            o.gateContracts.forEach(report::contractFailed)
            if (o.gatePremises.isEmpty() && o.gateContracts.isEmpty()) {
                report.contract(o.gateKrx == o.headKrx) {
                    "10-1 contract: ${o.cell.label} gate KRX ${o.gateKrx} ≠ HEAD 사본 ${o.headKrx} (capture capInv ${o.captureCapInv})"
                }
                report.contract(o.ioKrx == o.headKrx) {
                    "10-1 contract: ${o.cell.label} ioAdmission KRX ${o.ioKrx} ≠ HEAD 사본 ${o.headKrx} (capture capInv ${o.captureCapInv})"
                }
            }
            report.contract(o.issued == null) { "10-1 contract: ${o.cell.label} capture 시점 KRX acquire ${o.issued} ≠ null" }
            report.contract(o.reissued == null) { "10-1 contract: ${o.cell.label} 판정 시점 KRX acquire ${o.reissued} ≠ null" }
            report.contract(!o.admitted) { "10-1 contract: ${o.cell.label} 옛 사용권 admits = true ≠ false" }
        }
        report.assertClean()
    }

    /**
     * 10-2 p = 1 차이: gate 후보 = gate HEAD ∧ epoch 일치 ∧ capInv 일치이며, epoch만·capInv만·둘 다 다른 칸 각각에서 HEAD와의 차이 칸 수 > 0.
     * SUA KRX admits = admitsUse(u.lifetime) ∧ capabilityAllowed ∧ u.epoch = 현재 recordEpoch ≠ null ∧ u.capInv = 현재 capInv.
     * SUA acquire는 일반 acquire 성공 ∧ capabilityAllowed ∧ 현재 recordEpoch ≠ null일 때 현재 epoch·capInv의 사용권을 발급한다.
     * SUA는 N에서 acquire·admits 허용, E·C·EC·Zr·Z에서 옛 사용권 거부를 별도로 단언한다.
     * (5A.3 판정 줄에는 '≠ null'이 없지만 4절 SUA 행과 이 행의 문장을 따른다.)
     * (r1 고침) bind의 capture capInv(D-27)는 계약으로 단언하고, gate 식은 admits KRX와 ioAdmission KRX 둘 다에 단언한다. 격자 밖 추가 칸
     * ZN(capture 시점 막힘, [checkBlockedCaptureCell])을 둔다.
     * 잡을 변이: E-X, C-X, N-X, G-GT1, (r1 고침) A-X(ZN 발급).
     */
    @Test
    fun r10_2_permitOne_gateAndSuaFollowTheC1Formula() {
        val report = Report("10-2")
        val observed = grid(permit = 1L).map { observe("10-2", it) }

        for (o in observed) {
            o.cellPremises.forEach(report::premiseFailed)
            if (o.cellPremises.isNotEmpty()) continue
            o.gatePremises.forEach(report::premiseFailed)
            o.gateContracts.forEach(report::contractFailed)
            if (o.gatePremises.isEmpty() && o.gateContracts.isEmpty()) {
                val expected = expectedGate(o.cell, checkNotNull(o.headKrx))
                report.contract(o.gateKrx == expected) {
                    "10-2 contract: ${o.cell.label} gate KRX ${o.gateKrx} ≠ HEAD ∧ epoch 일치 ∧ capInv 일치 = $expected (HEAD ${o.headKrx})"
                }
                report.contract(o.ioKrx == expected) {
                    "10-2 contract: ${o.cell.label} ioAdmission KRX ${o.ioKrx} ≠ HEAD ∧ epoch 일치 ∧ capInv 일치 = $expected (HEAD ${o.headKrx})"
                }
            }
            checkSua(report, "10-2", o)
        }

        val categories = listOf<Pair<String, (Cell) -> Boolean>>(
            "epoch만 다른 칸" to { c -> c.epochDiffers && !c.capInvDiffers },
            "capInv만 다른 칸" to { c -> !c.epochDiffers && c.capInvDiffers },
            "둘 다 다른 칸" to { c -> c.epochDiffers && c.capInvDiffers }
        )
        for ((name, member) in categories) {
            val inCategory = observed.filter { member(it.cell) }
            report.premise(inCategory.any { o -> expectedGate(o.cell, o.cell.captureEpoch != null && o.cell.allowed) != (o.cell.captureEpoch != null && o.cell.allowed) }) {
                "10-2 premise: [$name] 식이 HEAD와 갈리는 칸이 격자에 있다"
            }
            val evaluable = inCategory.filter { it.cellPremises.isEmpty() && it.gatePremises.isEmpty() && it.gateContracts.isEmpty() }
            if (evaluable.isNotEmpty()) {
                val differing = evaluable.count { it.gateKrx != it.headKrx }
                report.contract(differing > 0) {
                    "10-2 contract: [$name] 전제를 지킨 ${evaluable.size}칸에서 gate KRX가 HEAD와 다른 칸 수 = $differing (> 0이어야 함)"
                }
            }
        }

        val named = mapOf(
            "N" to Cell("K1", "K1", 0L, 0L, allowed = true, permit = 1L),
            "E" to Cell("K1", "K2", 0L, 0L, allowed = true, permit = 1L),
            "C" to Cell("K1", "K1", 0L, 1L, allowed = true, permit = 1L),
            "EC" to Cell("K1", "K2", 0L, 1L, allowed = true, permit = 1L),
            "Zr" to Cell("K1", null, 0L, 0L, allowed = true, permit = 1L),
            "Z" to Cell(null, null, 0L, 0L, allowed = true, permit = 1L)
        )
        for ((name, cell) in named) {
            val o = observed.single { it.cell == cell }
            report.premise(o.judgeGeneralAdmitted && o.judgeCapabilityAllowed) {
                "10-2 premise(ON): [$name] 판정 스냅숏에서 일반 use 허용 ∧ capabilityAllowed = true"
            }
            val expectedOld = cell.captureEpoch?.let { KrxUseLifetime(LIFETIME7, it, cell.captureCapInv) }
            if (name == "N") {
                report.contract(o.issued == expectedOld && o.issued != null) { "10-2 contract: [N] acquire 허용 — ${o.issued} ≠ $expectedOld" }
                report.contract(o.admitted) { "10-2 contract: [N] 옛 사용권 admits 허용 — false" }
                report.contract(o.reissued == KrxUseLifetime(LIFETIME7, "K1", 0L)) { "10-2 contract: [N] 새 acquire (K1, c0) — ${o.reissued}" }
            } else {
                if (expectedOld != null) {
                    report.contract(o.issued == expectedOld) { "10-2 contract(창): [$name] 옛 사용권이 실제로 발급됐다 — ${o.issued} ≠ $expectedOld" }
                } else {
                    report.contract(o.issued == null) { "10-2 contract: [$name] null epoch에서 acquire = null — ${o.issued} (옛 사용권은 직접 만듦)" }
                }
                report.contract(!o.admitted) { "10-2 contract: [$name] 옛 사용권 거부 — admits = true" }
            }
        }

        checkBlockedCaptureCell(report)
        report.assertClean()
    }

    /**
     * 10-2 추가 칸 ZN(r1 고침, 설계 격자 밖 — 편차): capture 시점 막힘. NOT_GRANTED 중 기록 K1로 bind하면 capture epoch는 null이고(gate:39,
     * 02-6 꼴) SUA 발급도 null이다(A-X). 같은 기록·capInv의 허용 판정 스냅숏에서는 gate KRX가 거부되고(HEAD와 같음), SUA는 (K1, c0)를 새로 낸다.
     */
    private fun checkBlockedCaptureCell(report: Report) {
        val atCapture = a1Snap(
            record = RECORD_K1,
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            capabilityInvalidations = 0L,
            krxPermitSeq = 1L,
            revision = 100L
        )
        val atJudge = a1Snap(record = RECORD_K1, capabilityInvalidations = 0L, krxPermitSeq = 1L, revision = 101L)
        report.premise(atCapture.krxPermitSeq >= 1) { "10-2 premise(ON): [ZN] capture 스냅숏 krxPermitSeq ≥ 1" }
        report.premise(!atCapture.facts.capabilityAllowed) {
            "10-2 premise(ON): [ZN] capture 스냅숏 capabilityAllowed = false(막힘 중 확보 시도)"
        }
        report.premise(atCapture.headAcquireUse(FENCE7) == LIFETIME7) {
            "10-2 premise(ON): [ZN] capture 스냅숏 일반 use 허용(HEAD 일반 acquire = LIFETIME7)"
        }
        report.premise(atJudge.krxPermitSeq >= 1 && atJudge.facts.capabilityAllowed) {
            "10-2 premise(ON): [ZN] 판정 스냅숏 krxPermitSeq ≥ 1 ∧ capabilityAllowed = true"
        }
        report.premise(atJudge.headAdmitsUse(LIFETIME7)) { "10-2 premise: [ZN] 판정 스냅숏 일반 use 허용(HEAD 사본)" }
        report.premise(atCapture.facts.copy(capabilityBlocks = emptySet()) == atJudge.facts) {
            "10-2 premise: [ZN] 두 스냅숏의 facts는 capabilityBlocks만 다르다"
        }

        val world = World(atCapture)
        val capture = world.gate.bind(FENCE7, LIFETIME7)
        report.premise(capture != null && capture.krxCapabilityEpoch == null) {
            "10-2 premise: [ZN] 막힘 중 bind는 성공하고 capture epoch = null(gate:39), 실제 ${capture?.krxCapabilityEpoch}"
        }
        if (capture != null) {
            report.contract(capture.capabilityInvalidations == 0L) {
                "10-2 contract(D-27): [ZN] bind capture.capabilityInvalidations == 0(bind 스냅숏 값), 실제 ${capture.capabilityInvalidations}"
            }
            world.snapshot = atJudge
            val readsBefore = world.reads
            val general = world.gate.admits(capture, GraphV2DiskComponent.GENERAL)
            val krx = world.gate.admits(capture, GraphV2DiskComponent.KRX)
            report.premise(general && world.reads - readsBefore == 2) {
                "10-2 premise: [ZN] 판정 스냅숏에서 gate GENERAL 허용·판정 스냅숏 판독 2회(창), 실제 GENERAL $general 판독 ${world.reads - readsBefore}"
            }
            report.premise(!headAdmitsKrx(capture, atJudge)) { "10-2 premise: [ZN] HEAD 식도 capture epoch null을 거부" }
            report.contract(!krx) { "10-2 contract: [ZN] capture epoch null → gate KRX 거부(HEAD와 같음) — true" }
        }

        var published = atCapture
        val sua = SnapshotTopicUseAuthority { published }
        val issued = sua.acquireKrx(FENCE7)
        published = atJudge
        val reissued = sua.acquireKrx(FENCE7)
        report.contract(reissued == KrxUseLifetime(LIFETIME7, "K1", 0L)) {
            "10-2 contract(창): [ZN] 판정 시점(허용) SUA acquire = (K1, c0) — $reissued"
        }
        report.contract(issued == null) { "10-2 contract: [ZN] 막힘 중 SUA acquire = null(A-X) — $issued" }
    }

    /**
     * 10-3 짝: 같은 입력에서 p만 0↔1로 바꾼 짝마다 p = 0 결과 = HEAD, p = 1 결과 = 10-2의 식(gate·SUA).
     * 잡을 변이: G-OFF.
     */
    @Test
    fun r10_3_permitPairs_zeroIsHead_oneIsTheFormula() {
        val report = Report("10-3")
        val inputs = grid(permit = 0L)
        report.premise(inputs.any { c -> expectedGate(c.copy(permit = 1L), c.captureEpoch != null && c.allowed) != (c.captureEpoch != null && c.allowed) }) {
            "10-3 premise: p만 바꿔 gate 결과가 갈리는 짝이 있다"
        }
        for (input in inputs) {
            val off = observe("10-3", input.copy(permit = 0L))
            val on = observe("10-3", input.copy(permit = 1L))
            report.premise(captureSnapshot(off.cell).copy(krxPermitSeq = 1L) == captureSnapshot(on.cell)) {
                "10-3 premise: ${input.label} 짝의 capture 스냅숏이 krxPermitSeq만 다르다"
            }
            report.premise(judgementSnapshot(off.cell).copy(krxPermitSeq = 1L) == judgementSnapshot(on.cell)) {
                "10-3 premise: ${input.label} 짝의 판정 스냅숏이 krxPermitSeq만 다르다"
            }
            for (o in listOf(off, on)) {
                o.cellPremises.forEach(report::premiseFailed)
                if (o.cellPremises.isNotEmpty()) continue
                o.gatePremises.forEach(report::premiseFailed)
                o.gateContracts.forEach(report::contractFailed)
                if (o.gatePremises.isEmpty() && o.gateContracts.isEmpty()) {
                    val expected = expectedGate(o.cell, checkNotNull(o.headKrx))
                    val basis = if (o.cell.permit == 0L) "HEAD" else "식"
                    report.contract(o.gateKrx == expected) {
                        "10-3 contract: ${o.cell.label} gate KRX ${o.gateKrx} ≠ $basis $expected"
                    }
                    report.contract(o.ioKrx == expected) {
                        "10-3 contract: ${o.cell.label} ioAdmission KRX ${o.ioKrx} ≠ $basis $expected"
                    }
                }
                checkSua(report, "10-3", o)
            }
        }
        report.assertClean()
    }

    /**
     * 10-4 SUA 일반 불변: 격자 + token·standing·userBlocks·userInvalidations 변형에서 일반 acquire·admits는 p·capability 칸과 무관하게
     * HEAD 사본(de50885 SnapshotTopicUseAuthority.kt:14-22)과 같다(켬 포함). 확장 함수와 SUA 래퍼 둘 다 본다.
     * 잡을 변이: W-X(SUA).
     */
    @Test
    fun r10_4_generalAcquireAndAdmits_stayHeadOverEveryPermitAndCapabilityCell() {
        val report = Report("10-4")
        val lifetimes = listOf(LIFETIME7, TopicUseLifetime(TopicGrantToken(7L), 4L), TopicUseLifetime(TopicGrantToken(8L), 3L))
        val capabilityBlockSets = listOf(
            emptySet(),
            setOf(TopicAccessBlock.KRX_CONTESTED),
            setOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.NOT_GRANTED)
        )
        val snapshots = buildList {
            for (token in listOf(7L, 8L, null)) for (standing in listOf(true, false))
                for (userBlocks in listOf(emptySet(), setOf(TopicAccessBlock.LOSS_CANDIDATE)))
                    for (userInvalidations in listOf(3L, 4L)) for (capabilityBlocks in capabilityBlockSets)
                        for (epoch in listOf(null, "K1", "K2")) for (capInv in listOf(0L, 1L)) for (permit in listOf(0L, 1L, 2L)) {
                            add(
                                krxSnap(
                                    token = token,
                                    standing = standing,
                                    userBlocks = userBlocks,
                                    capabilityBlocks = capabilityBlocks,
                                    userInvalidations = userInvalidations,
                                    recordEpoch = epoch,
                                    krxPermitSeq = permit,
                                    capabilityInvalidations = capInv
                                )
                            )
                        }
        }
        report.premise(snapshots.any { it.krxPermitSeq >= 1 && !it.facts.capabilityAllowed && it.headAcquireUse(FENCE7) != null }) {
            "10-4 premise(ON): p ≥ 1·capability 막힘인데 HEAD 일반 acquire가 성공하는 칸이 있다(W-X가 보일 칸)"
        }
        report.premise(snapshots.any { it.krxPermitSeq >= 1 && it.facts.capabilityAllowed && it.headAcquireUse(FENCE7) == null }) {
            "10-4 premise(ON): p ≥ 1·capability 허용인데 HEAD 일반 acquire가 거부되는 칸이 있다"
        }
        for (s in snapshots) {
            val label = "token=${s.facts.token?.value} standing=${s.facts.tokenStanding} user=${s.facts.userBlocks} " +
                "userInv=${s.userInvalidations} cap=${s.facts.capabilityBlocks} epoch=${s.facts.recordFence?.krxCapabilityEpoch} " +
                "capInv=${s.capabilityInvalidations} p=${s.krxPermitSeq}"
            val authority = SnapshotTopicUseAuthority { s }
            val headAcquire = s.headAcquireUse(FENCE7)
            report.contract(s.acquireUse(FENCE7) == headAcquire) { "10-4 contract: [$label] 일반 acquire ≠ HEAD 사본 $headAcquire" }
            report.contract(authority.acquire(FENCE7) == headAcquire) { "10-4 contract: [$label] 일반 acquire(SUA 래퍼) ≠ HEAD 사본 $headAcquire" }
            for (lifetime in lifetimes) {
                val head = s.headAdmitsUse(lifetime)
                report.contract(s.admitsUse(lifetime) == head) { "10-4 contract: [$label] 일반 admits($lifetime) ≠ HEAD 사본 $head" }
                report.contract(authority.admits(lifetime) == head) { "10-4 contract: [$label] 일반 admits($lifetime, SUA 래퍼) ≠ HEAD 사본 $head" }
            }
        }
        report.assertClean()
    }

    /**
     * 10-5 E 순수 격자: capabilityBlocks ∈ {∅, 생산 여섯 값 각각 단독, {CONTEXT_UNCERTAIN, EXPLICIT_SEAL}} × krxPermitSeq ∈ {0, 1, 2}.
     * 기대값은 손으로 쓴 표다(식 함수를 부르지 않는다). ∅이고 p ≥ 1일 때만 참.
     * 잡을 변이: 가드 삭제, `>= 0`, EF-B.
     */
    @Test
    fun r10_5_effectiveKrx_handWrittenTable() {
        val report = Report("10-5")
        for ((blocks, permit, expected) in E_TABLE) {
            val s = krxSnap(capabilityBlocks = blocks, krxPermitSeq = permit)
            val label = "blocks=$blocks p=$permit"
            val premisesHold = s.facts.capabilityBlocks == blocks && s.krxPermitSeq == permit &&
                s.facts.capabilityAllowed == blocks.isEmpty()
            report.premise(s.facts.capabilityBlocks == blocks) { "10-5 premise: [$label] 스냅숏 capabilityBlocks가 표의 값이다" }
            report.premise(s.krxPermitSeq == permit) { "10-5 premise: [$label] 스냅숏 krxPermitSeq가 표의 값이다" }
            report.premise(s.facts.capabilityAllowed == blocks.isEmpty()) { "10-5 premise: [$label] capabilityAllowed = (blocks = ∅)" }
            if (premisesHold) {
                val actual = s.krxEffective()
                report.contract(actual == expected) { "10-5 contract: [$label] E = $actual ≠ 표 $expected" }
            }
        }
        report.premise(E_TABLE.count { it.third } == 2) { "10-5 premise: 표의 참 칸은 ∅·p ∈ {1, 2} 둘뿐" }
        report.assertClean()
    }

    // ---- 격자와 관측 ----

    private data class Cell(
        val captureEpoch: String?,
        val currentEpoch: String?,
        val captureCapInv: Long,
        val currentCapInv: Long,
        val allowed: Boolean,
        val permit: Long
    ) {
        val label: String
            get() = "[capture=($captureEpoch,c$captureCapInv) current=($currentEpoch,c$currentCapInv) allowed=$allowed p=$permit]"
        val epochDiffers: Boolean get() = captureEpoch != currentEpoch
        val capInvDiffers: Boolean get() = captureCapInv != currentCapInv
    }

    /** 한 칸의 관측: 전제 실패(칸 전체 / gate 부분), gate의 D-27 계약 실패, gate·SUA 결과. */
    private data class Observed(
        val cell: Cell,
        val cellPremises: List<String>,
        val gatePremises: List<String>,
        val gateContracts: List<String>,
        val gateRealized: Boolean,
        val captureCapInv: Long?,
        val gateKrx: Boolean?,
        val ioKrx: Boolean?,
        val headKrx: Boolean?,
        val judgeGeneralAdmitted: Boolean,
        val judgeCapabilityAllowed: Boolean,
        val issued: KrxUseLifetime?,
        val reissued: KrxUseLifetime?,
        val admitted: Boolean
    ) {
        /** 실제 capture 값으로 C-1 연언이 HEAD와 갈리는가(10-1 공허 방지 전제용). */
        fun c1WouldDiffer(): Boolean = cell.captureEpoch != cell.currentEpoch || captureCapInv != cell.currentCapInv
    }

    /** gate가 읽는 live 입력. bind 뒤 [snapshot]을 바꿔 판정한다. [reads]는 스냅숏 판독 수. */
    private class World(initial: TopicAccessSnapshot) {
        var snapshot: TopicAccessSnapshot = initial
        var reads = 0
        val gate = GraphV2AccessGate(
            currentIdentity = { IDENTITY },
            currentAccessFence = { FENCE7 },
            snapshot = { reads++; snapshot },
            protectedAdmission = { true }
        )
    }

    private fun grid(permit: Long): List<Cell> = buildList {
        val epochs = listOf(null, "K1", "K2")
        for (captureEpoch in epochs) for (currentEpoch in epochs) for (captureCapInv in listOf(0L, 1L))
            for (currentCapInv in listOf(0L, 1L)) for (allowed in listOf(true, false)) {
                add(Cell(captureEpoch, currentEpoch, captureCapInv, currentCapInv, allowed, permit))
            }
    }

    /** capture를 잡는 스냅숏: 허용, 기록 epoch = capture epoch, capInv = capture capInv. 토큰 7은 K1 기록에 발급(A1형 standing). */
    private fun captureSnapshot(cell: Cell): TopicAccessSnapshot = a1Snap(
        record = KrxHandSnapshots.record(cell.captureEpoch),
        issuedRecord = RECORD_K1,
        capabilityInvalidations = cell.captureCapInv,
        krxPermitSeq = cell.permit,
        revision = 100L
    )

    /** 판정 스냅숏: 기록 epoch = 현재 epoch, capInv = 현재 capInv, capabilityAllowed 거짓은 {LOSS_CANDIDATE}. */
    private fun judgementSnapshot(cell: Cell): TopicAccessSnapshot = a1Snap(
        record = KrxHandSnapshots.record(cell.currentEpoch),
        issuedRecord = RECORD_K1,
        capabilityBlocks = if (cell.allowed) emptySet() else setOf(TopicAccessBlock.LOSS_CANDIDATE),
        capabilityInvalidations = cell.currentCapInv,
        krxPermitSeq = cell.permit,
        revision = 101L
    )

    private fun observe(row: String, cell: Cell): Observed {
        val cellPremises = mutableListOf<String>()
        val gatePremises = mutableListOf<String>()
        val gateContracts = mutableListOf<String>()
        val atCapture = captureSnapshot(cell)
        val atJudge = judgementSnapshot(cell)
        val on = cell.permit >= 1
        fun premise(ok: Boolean, text: String) {
            if (!ok) cellPremises += "$row $text ${cell.label}"
        }

        if (on) {
            premise(atCapture.krxPermitSeq >= 1, "premise(ON): capture 스냅숏 krxPermitSeq ≥ 1")
            premise(atCapture.facts.capabilityAllowed, "premise(ON): capture 스냅숏 capabilityAllowed = true(허용 상태에서 확보)")
            premise(atJudge.krxPermitSeq >= 1, "premise(ON): 판정 스냅숏 krxPermitSeq ≥ 1")
            if (cell.allowed) {
                premise(atJudge.facts.capabilityAllowed, "premise(ON): 판정 스냅숏 capabilityAllowed = true")
            } else {
                premise(!atJudge.facts.capabilityAllowed, "premise(ON): 거부 판정 스냅숏 capabilityAllowed = false")
                premise(atJudge.headAdmitsUse(LIFETIME7), "premise(ON): 거부 판정 스냅숏 일반 use 허용(HEAD 사본)")
            }
        } else {
            premise(atCapture.krxPermitSeq == 0L && atJudge.krxPermitSeq == 0L, "premise(OFF): 두 스냅숏 krxPermitSeq = 0")
        }
        premise(atCapture.headAcquireUse(FENCE7) == LIFETIME7, "premise: capture 스냅숏 일반 acquire(HEAD 사본) = LIFETIME7")
        premise(atJudge.headAdmitsUse(LIFETIME7), "premise: 판정 스냅숏 일반 use 허용(HEAD 사본, standing·사용자 축)")
        premise(atCapture.facts.recordFence?.krxCapabilityEpoch == cell.captureEpoch, "premise: capture 스냅숏 기록 epoch")
        premise(atJudge.facts.recordFence?.krxCapabilityEpoch == cell.currentEpoch, "premise: 판정 스냅숏 기록 epoch")
        premise(atJudge.capabilityInvalidations == cell.currentCapInv, "premise: 판정 스냅숏 capInv")
        premise(atJudge.facts.capabilityAllowed == cell.allowed, "premise: 판정 스냅숏 capabilityAllowed")

        // gate: standing World에서 bind → 스냅숏 교체.
        val world = World(atCapture)
        val capture: GraphV2AccessCapture? = world.gate.bind(FENCE7, LIFETIME7)
        if (capture == null) {
            gatePremises += "$row premise: bind 성공 ${cell.label}"
        } else {
            if (capture.krxCapabilityEpoch != cell.captureEpoch) {
                gatePremises += "$row premise: capture epoch = ${cell.captureEpoch}(실제 ${capture.krxCapabilityEpoch}) ${cell.label}"
            }
            if (on && capture.capabilityInvalidations != cell.captureCapInv) {
                gateContracts += "$row contract(D-27): bind capture.capabilityInvalidations == ${cell.captureCapInv}(bind 스냅숏 값), " +
                    "실제 ${capture.capabilityInvalidations} ${cell.label}"
            }
        }
        world.snapshot = atJudge
        val readsBefore = world.reads
        val general = capture?.let { world.gate.admits(it, GraphV2DiskComponent.GENERAL) }
        val krx = capture?.let { world.gate.admits(it, GraphV2DiskComponent.KRX) }
        if (capture != null) {
            if (general != true) gatePremises += "$row premise: 판정 스냅숏에서 gate GENERAL 허용(창) ${cell.label}"
            if (world.reads - readsBefore != 2) {
                gatePremises += "$row premise: gate 판정이 실제로 판정 스냅숏을 읽었다(판독 ${world.reads - readsBefore}) ${cell.label}"
            }
        }
        val ioReadsBefore = world.reads
        val ioKrx = capture?.let { world.gate.ioAdmission(it).admits(GraphV2DiskComponent.KRX) }
        if (capture != null && world.reads - ioReadsBefore != 1) {
            gatePremises += "$row premise: ioAdmission KRX 판정이 실제로 판정 스냅숏을 읽었다(판독 ${world.reads - ioReadsBefore}) ${cell.label}"
        }
        val head = capture?.let { headAdmitsKrx(it, atJudge) }

        // SUA: acquire → 스냅숏 교체 → acquire·admits.
        var published = atCapture
        val sua = SnapshotTopicUseAuthority { published }
        val issued = sua.acquireKrx(FENCE7)
        published = atJudge
        val reissued = sua.acquireKrx(FENCE7)
        val oldUse = KrxUseLifetime(LIFETIME7, cell.captureEpoch, cell.captureCapInv)
        val admitted = sua.admitsKrx(oldUse)

        return Observed(
            cell = cell,
            cellPremises = cellPremises,
            gatePremises = gatePremises,
            gateContracts = gateContracts,
            gateRealized = capture != null && capture.krxCapabilityEpoch == cell.captureEpoch,
            captureCapInv = capture?.capabilityInvalidations,
            gateKrx = krx,
            ioKrx = ioKrx,
            headKrx = head,
            judgeGeneralAdmitted = atJudge.headAdmitsUse(LIFETIME7),
            judgeCapabilityAllowed = atJudge.facts.capabilityAllowed,
            issued = issued,
            reissued = reissued,
            admitted = admitted
        )
    }

    /** SUA의 칸별 계약(10-2·10-3): p = 0이면 HEAD(사용권 없음), p = 1이면 10-2의 식. */
    private fun checkSua(report: Report, row: String, o: Observed) {
        val expectedIssued = expectedIssue(o.cell)
        val expectedReissued = expectedReissue(o.cell)
        val expectedAdmitted = expectedAdmits(o.cell)
        val basis = if (o.cell.permit == 0L) "HEAD(사용권 없음)" else "식"
        report.contract(o.issued == expectedIssued) { "$row contract: ${o.cell.label} capture 시점 SUA KRX acquire ${o.issued} ≠ $basis $expectedIssued" }
        report.contract(o.reissued == expectedReissued) { "$row contract: ${o.cell.label} 판정 시점 SUA KRX acquire ${o.reissued} ≠ $basis $expectedReissued" }
        report.contract(o.admitted == expectedAdmitted) { "$row contract: ${o.cell.label} SUA 옛 사용권 admits ${o.admitted} ≠ $basis $expectedAdmitted" }
    }

    // ---- 기대식(설계 10-2 문장, 대상 코드를 부르지 않는다) ----

    /** gate: p = 0이면 HEAD, p = 1이면 HEAD ∧ epoch 일치 ∧ capInv 일치. */
    private fun expectedGate(cell: Cell, head: Boolean): Boolean =
        if (cell.permit == 0L) head else head && cell.captureEpoch == cell.currentEpoch && cell.captureCapInv == cell.currentCapInv

    /** capture 시점 SUA acquire: 일반 acquire 성공(전제) ∧ capabilityAllowed(capture는 늘 허용) ∧ 기록 epoch ≠ null. */
    private fun expectedIssue(cell: Cell): KrxUseLifetime? =
        if (cell.permit == 0L || cell.captureEpoch == null) null else KrxUseLifetime(LIFETIME7, cell.captureEpoch, cell.captureCapInv)

    /** 판정 시점 SUA acquire: 일반 acquire 성공(전제) ∧ capabilityAllowed ∧ 현재 기록 epoch ≠ null → (현재 epoch, 현재 capInv). */
    private fun expectedReissue(cell: Cell): KrxUseLifetime? =
        if (cell.permit == 0L || !cell.allowed || cell.currentEpoch == null) null
        else KrxUseLifetime(LIFETIME7, cell.currentEpoch, cell.currentCapInv)

    /** SUA admits: admitsUse(u.lifetime)(전제) ∧ capabilityAllowed ∧ u.epoch = 현재 recordEpoch ≠ null ∧ u.capInv = 현재 capInv. */
    private fun expectedAdmits(cell: Cell): Boolean =
        cell.permit != 0L && cell.allowed && cell.currentEpoch != null && cell.captureEpoch == cell.currentEpoch &&
            cell.captureCapInv == cell.currentCapInv

    /** 격자 행의 실패 모음: 첫 줄은 첫 전제 실패(있으면) 또는 첫 계약 실패. */
    private class Report(private val row: String) {
        private val premises = mutableListOf<String>()
        private val contracts = mutableListOf<String>()

        fun premise(ok: Boolean, text: () -> String) {
            if (!ok) premises += text()
        }

        fun premiseFailed(text: String) {
            premises += text
        }

        fun contractFailed(text: String) {
            contracts += text
        }

        fun contract(ok: Boolean, text: () -> String) {
            if (!ok) contracts += text()
        }

        fun assertClean() {
            if (premises.isEmpty() && contracts.isEmpty()) return
            val first = premises.firstOrNull() ?: contracts.first()
            val body = buildString {
                append(first)
                append("\n$row 요약: 전제 실패 ${premises.size}건, 계약 실패 ${contracts.size}건")
                premises.take(LISTED).forEach { append("\n  [전제] ").append(it) }
                if (premises.size > LISTED) append("\n  [전제] … ${premises.size - LISTED}건 더")
                contracts.take(LISTED).forEach { append("\n  [계약] ").append(it) }
                if (contracts.size > LISTED) append("\n  [계약] … ${contracts.size - LISTED}건 더")
            }
            throw AssertionError(body)
        }
    }

    private companion object {
        const val LISTED = 12

        /**
         * 10-5 E 표: (capabilityBlocks, krxPermitSeq, 기대 E). 손으로 쓴 값이다 — 식 함수를 부르지 않는다(초안 EF-01).
         * 생산 여섯 값은 PAC:3116-3124의 NOT_GRANTED·LOSS_CANDIDATE·EXPLICIT_SEAL·DERIVED_SEAL·CONTEXT_UNCERTAIN·KRX_CONTESTED.
         */
        val E_TABLE: List<Triple<Set<TopicAccessBlock>, Long, Boolean>> = listOf(
            Triple(emptySet(), 0L, false),
            Triple(emptySet(), 1L, true),
            Triple(emptySet(), 2L, true),
            Triple(setOf(TopicAccessBlock.NOT_GRANTED), 0L, false),
            Triple(setOf(TopicAccessBlock.NOT_GRANTED), 1L, false),
            Triple(setOf(TopicAccessBlock.NOT_GRANTED), 2L, false),
            Triple(setOf(TopicAccessBlock.LOSS_CANDIDATE), 0L, false),
            Triple(setOf(TopicAccessBlock.LOSS_CANDIDATE), 1L, false),
            Triple(setOf(TopicAccessBlock.LOSS_CANDIDATE), 2L, false),
            Triple(setOf(TopicAccessBlock.EXPLICIT_SEAL), 0L, false),
            Triple(setOf(TopicAccessBlock.EXPLICIT_SEAL), 1L, false),
            Triple(setOf(TopicAccessBlock.EXPLICIT_SEAL), 2L, false),
            Triple(setOf(TopicAccessBlock.DERIVED_SEAL), 0L, false),
            Triple(setOf(TopicAccessBlock.DERIVED_SEAL), 1L, false),
            Triple(setOf(TopicAccessBlock.DERIVED_SEAL), 2L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), 0L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), 1L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), 2L, false),
            Triple(setOf(TopicAccessBlock.KRX_CONTESTED), 0L, false),
            Triple(setOf(TopicAccessBlock.KRX_CONTESTED), 1L, false),
            Triple(setOf(TopicAccessBlock.KRX_CONTESTED), 2L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN, TopicAccessBlock.EXPLICIT_SEAL), 0L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN, TopicAccessBlock.EXPLICIT_SEAL), 1L, false),
            Triple(setOf(TopicAccessBlock.CONTEXT_UNCERTAIN, TopicAccessBlock.EXPLICIT_SEAL), 2L, false)
        )

        /** de50885 GraphV2AccessGate.kt:79-80 동결 사본(gate `admitsKrx`, 본문 그대로). */
        fun headAdmitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
            captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed

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
