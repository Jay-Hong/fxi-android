package com.jay.fxi

import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.entitlements.settleKrx
import com.jay.fxi.data.entitlements.step
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b0b 배선 계약(`R4c/S6/s6_2b0b/s6_2b0b_contracts.r2.md` 4.5): CAP-K11 CALLER `refresh(FORCE_ENTITLEMENTS)` 0, 운영 생성
 * 지점의 스위치 꺼짐, 정책·근거 타입의 운영 참조 범위, 시험 observer의 운영 미사용, `KRX_CONTESTED`의 운영 소비자 없음.
 *
 * 형식은 0a `KrxContestDormancyContractTest`를 따른다: 다섯 root를 `app/` 기준으로 읽고, 주석을 지운 코드 맵을 쓰며, 0을
 * 단언할 때마다 같은 패턴이 다른 곳에서 찾아지는 양성 대조를 둔다.
 */
class KrxContestWiringContractTest {

    private companion object {
        val ROOTS = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
        const val M = "src/main/java/com/jay/fxi/"
        const val PAC = "${M}data/entitlements/PremiumAccessCoordinator.kt"
        const val POLICY = "${M}data/entitlements/KrxContestPolicy.kt"
        const val EVIDENCE = "${M}data/entitlements/KrxReapprovalEvidence.kt"
        const val TAS = "${M}data/entitlements/TopicAccessSnapshot.kt"
        const val MODULE = "${M}di/EntitlementsModule.kt"
        const val MARKER = "${M}data/entitlements/KrxSendMarker.kt"
        const val LEDGER = "${M}data/entitlements/TopicRejectionLedger.kt"

        val read: List<Pair<String, String>> by lazy {
            ROOTS.flatMap { root ->
                val dir = File(root)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .map { "$root/${it.relativeTo(dir).invariantSeparatorsPath}" to it.readText() }.toList()
            }
        }

        val code: Map<String, String> by lazy {
            read.toMap().mapValues { (_, text) ->
                text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            }
        }

        fun files(pattern: Regex): Set<String> = code.filterValues { pattern.containsMatchIn(it) }.keys

        val POLICY_NAMES = listOf(
            "KrxContestPolicy", "KrxContestState", "KrxContestEvent", "KrxContestStep", "KrxContestPhase", "KrxAnswerKind", "KrxContestIgnore"
        )
        val EVIDENCE_NAMES = listOf("KrxReapprovalEvidence", "KrxDemandMerge", "reapprovalOnlyAfter")

        /** PremiumAccessCoordinator 생성 호출 하나를 괄호 짝으로 잘라 낸다. */
        fun constructorCall(text: String): String {
            // 단어 경계: `providePremiumAccessCoordinator(` 선언을 생성 호출로 잡지 않는다.
            val start = Regex("""\bPremiumAccessCoordinator\s*\(""").find(text) ?: error("no PremiumAccessCoordinator( call")
            var depth = 0
            for (i in start.range.last until text.length) {
                when (text[i]) {
                    '(' -> depth += 1
                    ')' -> { depth -= 1; if (depth == 0) return text.substring(start.range.first, i + 1) }
                }
            }
            error("unbalanced PremiumAccessCoordinator( call")
        }
    }

    /** KW-00: scan이 비어 있지 않다 — root가 있고, 대상 파일을 읽었고, 키가 읽은 파일과 같다. */
    @Test fun KW00_premise_rootsExistAndTargetsAreScanned() {
        for (root in ROOTS) assertTrue("premise: root $root exists", File(root).isDirectory)
        for (key in listOf(PAC, POLICY, EVIDENCE, TAS, MODULE)) assertTrue("premise: the code map has $key", key in code)
        assertEquals("premise: no two files read share a key", read.size, code.size)
    }

    /** KW-01 (CAP-K11): 운영 소스에 CALLER `refresh(FORCE_ENTITLEMENTS)`가 없다. 양성 대조: 같은 꼴의 FORCE_PREMIUM 호출은 있다. */
    @Test fun KW01_noCallerForceEntitlementsRefresh() {
        fun call(intent: String) = Regex("""\.refresh\(\s*(intent\s*=\s*)?RefreshIntent\.$intent\b""")
        assertTrue("positive control: a CALLER refresh(FORCE_PREMIUM) exists", files(call("FORCE_PREMIUM")).isNotEmpty())
        assertEquals("CALLER refresh(FORCE_ENTITLEMENTS)", emptySet<String>(), files(call("FORCE_ENTITLEMENTS")))
    }

    /** KW-02: 운영 생성 지점은 스위치를 넘기지 않고, 운영 소스 어디에도 스위치를 켜는 값이 없다. */
    @Test fun KW02_productionNeverEnablesTheContest() {
        assertTrue("positive control: the switch type is declared", files(Regex("""class\s+KrxContestSwitch\s*\(""")).isNotEmpty())
        val enabling = Regex("""KrxContestSwitch\s*\(\s*(enabled\s*=\s*)?true\s*\)""")
        assertEquals("KrxContestSwitch(true) in production", emptySet<String>(), files(enabling))
        val call = constructorCall(code.getValue(MODULE))
        assertTrue("positive control: the module call passes named arguments", Regex("""\bsource\s*=""").containsMatchIn(call))
        assertEquals("krxContest argument in the production construction", 0, Regex("""\bkrxContest\b""").findAll(call).count())
    }

    /** KW-03: 정책 이름은 정책 파일과 PAC에만, 근거 타입 이름은 근거 파일과 PAC에만 있다. PAC는 둘 다 실제로 쓴다. */
    @Test fun KW03_policyAndEvidenceAreReferencedOnlyByPac() {
        for (name in POLICY_NAMES) {
            val found = files(Regex("""\b$name\b"""))
            assertTrue("$name only in policy/PAC, found $found", found.all { it == POLICY || it == PAC })
        }
        for (name in EVIDENCE_NAMES) {
            val found = files(Regex("""\b$name\b"""))
            assertTrue("$name only in evidence/PAC, found $found", found.all { it == EVIDENCE || it == PAC })
        }
        assertTrue("PAC uses the policy", Regex("""\bKrxContestPolicy\b""").containsMatchIn(code.getValue(PAC)))
        assertTrue("PAC uses the evidence", Regex("""\bKrxReapprovalEvidence\b""").containsMatchIn(code.getValue(PAC)))
    }

    /** KW-04: 시험 observer는 PAC 선언 밖의 운영 소스에서 언급되지 않는다(운영은 설정하지 않는다). */
    @Test fun KW04_testObserversAreNotUsedInProduction() {
        for (name in listOf("onQueryStartedForTest", "onAccessPublishedForTest")) {
            val found = files(Regex("""\b$name\b"""))
            assertEquals("$name outside PAC", setOf(PAC), found)
        }
    }

    /** KW-05: `KRX_CONTESTED`는 선언(TAS)과 생산자(PAC)에만 있다. 소비자는 기존 `capabilityAllowed` 경로를 읽는다. */
    @Test fun KW05_contestedBlockHasNoProductionConsumer() {
        val found = files(Regex("""\bKRX_CONTESTED\b"""))
        assertEquals("KRX_CONTESTED in production", setOf(TAS, PAC), found)
        assertTrue("positive control: capabilityAllowed is read outside TAS",
            files(Regex("""\bcapabilityAllowed\b""")).any { it != TAS })
    }

    /**
     * KW-06 (S6-2b-1a, 설계 r2 4절): 새 상태는 0a 밖(PAC 비공개)에 있다 — 정책 파일은 표지·인계·막힘·재개 의무를 모르고, 새 타입은 선언 파일과
     * PAC(표지는 예약을 든 ledger 포함)에서만 쓰인다. `capabilityInvalidations`는 선언(TAS)과 생산자(PAC)에만 있다(소비자는 2b-1b).
     * 2b-1b가 소비자를 더하면 이 집합을 그 판에서 개정한다.
     */
    @Test fun KW06_newStateStaysOutOf0aAndInsidePac() {
        val policy = code.getValue(POLICY)
        for (name in listOf("rejectedPermit", "withheld", "reopenOwed", "KrxSendMarker", "KrxWithheld", "KrxReopenOwed", "capabilityInvalidations")) {
            assertTrue("0a 정책이 $name 을 안다", !Regex("""\b$name\b""").containsMatchIn(policy))
        }
        assertEquals("KrxSendMarker 참조", setOf(MARKER, PAC, LEDGER), files(Regex("""\bKrxSendMarker\b""")))
        for (name in listOf("KrxWithheld", "KrxReopenOwed")) {
            assertEquals("$name 참조", setOf(MARKER, PAC), files(Regex("""\b$name\b""")))
        }
        assertEquals("capabilityInvalidations 운영 참조", setOf(TAS, PAC), files(Regex("""\bcapabilityInvalidations\b""")))
        assertTrue("positive control: PAC가 표지를 쓴다", Regex("""\bKrxSendMarker\b""").containsMatchIn(code.getValue(PAC)))
    }

    /**
     * KW-07 (S6-2b-1a, 설계 r2 3.3·4절): 스위치 꺼짐에서는 '끝난 예약 평가' 작업을 PAC scope에 띄우지 않는다 — abandon과 잠금 대기 중 취소
     * 직후, PAC scope(StandardTestDispatcher)가 아무것도 디스패치하기 전에 processJob의 자식 수가 그대로다. 잠금 대기 중인 처리 작업은
     * UnconfinedTestDispatcher로 띄워 취소의 finally가 cancel() 안에서 바로 돌게 한다(디스패치 뒤에 세면 '띄운 뒤 본문에서 스위치를 보고 바로
     * 끝나는' 누수가 가려진다 — 시험 r1 내부 검토 C-3). 켬의 평가 발화는 PermitTest P4·P6이 잡는다(양성 쪽).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun KW07_offLaunchesNoEndedReservationEvaluation() = runTest {
        val processJob = SupervisorJob()
        try {
            val h = krxHarness(processJob)
            h.grantedKrxVisible()
            settleKrx()
            val grant = h.issueGrant()
            val marker = KrxSendMarker(h.store.record.krxCapabilityEpoch, 1L)
            val baseline = processJob.children.count()
            h.coordinator.abandonRejection(h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, marker))
            assertEquals("꺼짐 abandon은 PAC scope 작업을 띄우지 않는다", baseline, processJob.children.count())

            val loadGate = CompletableDeferred<Unit>()
            h.store.loadGate = loadGate
            val holder = async(start = CoroutineStart.UNDISPATCHED) { h.coordinator.topicGrantResult() }
            assertTrue("premise: 발급이 잠금 안 load에서 멈췄다", h.store.loadGate == null && holder.isActive)
            val r = h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, marker)
            val rejection = launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.onTopicRejected(r) }
            assertTrue("premise: 거절 처리가 mutex를 기다린다", rejection.isActive)
            rejection.cancel()
            assertTrue("premise: 취소의 finally가 cancel() 안에서 끝났다(디스패치 전)", rejection.isCompleted)
            assertEquals("꺼짐 잠금 대기 취소는 PAC scope 작업을 띄우지 않는다(디스패치 전)", baseline, processJob.children.count())
            step(0)
            assertEquals("디스패치 뒤에도 그대로", baseline, processJob.children.count())
            loadGate.complete(Unit)
            holder.await()
        } finally {
            processJob.cancel()
        }
    }
}
