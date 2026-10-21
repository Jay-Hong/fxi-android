package com.jay.fxi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b0a dormancy (`R4c/S6/s6_2b0a/s6_2b0a_contracts.r2.md` §5, PD-01..PD-05, CAP-P08): the pure KRX contest policy
 * (`data/entitlements/KrxContestPolicy.kt`) has no production caller, holds no clock, coroutine, lock, randomness or mutable state,
 * names no KRX topic, session, refresh intent, PAC, recheck schedule or reducer, and imports nothing outside the Kotlin standard
 * library.
 *
 * Format follows the S6-2a test (`TopicBootstrapRetryDormancyContractTest.kt`): the five roots read relative to `app/`, a code map
 * with comments stripped, and a positive control for every zero (the same pattern finds a file outside the policy).
 *
 * This test is amended by agreement when S6-2b0b deliberately adds a caller; deleting, skipping or assuming it away is not allowed.
 */
class KrxContestDormancyContractTest {

    private companion object {
        val ROOTS = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
        const val M = "src/main/java/com/jay/fxi/"
        const val POLICY = "${M}data/entitlements/KrxContestPolicy.kt"
        const val PAC = "${M}data/entitlements/PremiumAccessCoordinator.kt"

        val read: List<Pair<String, String>> by lazy {
            ROOTS.flatMap { root ->
                val dir = File(root)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .map { "$root/${it.relativeTo(dir).invariantSeparatorsPath}" to it.readText() }.toList()
            }
        }

        val all: Map<String, String> by lazy { read.toMap() }

        val code: Map<String, String> by lazy {
            all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        }

        val outsidePolicy: Map<String, String> by lazy { code - POLICY }

        val policy: String by lazy { code.getValue(POLICY) }

        fun files(pattern: Regex, map: Map<String, String> = code): Set<String> =
            map.filterValues { pattern.containsMatchIn(it) }.keys

        /** Positive control first (the same pattern finds a file outside the policy), then the zero in the policy. */
        fun assertAbsentFromPolicy(pattern: String) {
            val regex = Regex(pattern)
            assertTrue("positive control: $pattern is found outside the policy", files(regex, outsidePolicy).isNotEmpty())
            assertEquals("$pattern in the policy", 0, regex.findAll(policy).count())
        }

        val NAMES = listOf(
            "KrxContestPolicy", "KrxContestState", "KrxContestEvent", "KrxContestStep", "KrxContestPhase", "KrxAnswerKind", "KrxContestIgnore"
        )

        /** PD-02, word-boundary patterns as listed in §5. */
        val PURITY = listOf(
            """\bsuspend\b""", """\blaunch\b""", """\basync\b""", """\bdelay\b""", """\bwithTimeout\b""", """\bTimeSource\b""",
            """\bSystemClock\b""", """\bSystem\b""", """\bClock\b""", """\bRandom\b""", """\bMutex\b""", """\bsynchronized\b""",
            """\bVolatile\b""", """\bAtomic\w*""", """\bJob\b""", """\bFlow\b""", """\bvar\b""", """\blateinit\b"""
        )

        /** PD-03. */
        val TOPIC_AND_SESSION = listOf(
            """krx:""", """\bKRX_FUTURES\b""", """\bTopicCatalogue\b""", """subscribe""", """\brefresh\s*\(""",
            """\bFORCE_ENTITLEMENTS\b""", """\bFORCE_PREMIUM\b""", """\bRefreshIntent\b"""
        )

        /** PD-04. `TopicGrant\w*` covers the grant token and issuer types (no bare `TopicGrant` exists). */
        val DECISION_OWNERS = listOf(
            """\bPremiumAccessCoordinator\b""", """\bRecheckSchedule\b""", """\bPremiumAccessReducer\b""", """\bTopicRejection\b""",
            """\bTopicSessionCoordinator\b""", """\bTopicGrant\w*""", """\bLossCandidate\b""", """\bEntitlementsOutcome\b"""
        )
    }

    /** PD-00: the scan is not vacuous — every root exists, the policy and PAC are read, and keys are the files read. */
    @Test fun PD00_premise_everyRootExistsAndThePolicyIsScanned() {
        for (root in ROOTS) assertTrue("premise: root $root exists", File(root).isDirectory)
        for (key in listOf(POLICY, PAC)) assertTrue("premise: the code map has $key", key in code)
        assertEquals("premise: the code map's keys are the files read", read.map { it.first }.toSet(), code.keys)
        assertEquals("premise: no two files read share a key", read.size, code.size)
    }

    /** PD-01: no caller. Each of the seven names is found in the declaring file only. Catches early wiring. */
    @Test fun PD01_policyHasNoCaller() {
        for (name in NAMES) assertEquals("\\b$name\\b", setOf(POLICY), files(Regex("""\b$name\b""")))
    }

    /** PD-02: purity. Catches a clock, coroutine, lock, randomness or mutable state inside the policy. */
    @Test fun PD02_policyIsPure() {
        assertEquals("18 purity patterns", 18, PURITY.size)
        for (pattern in PURITY) assertAbsentFromPolicy(pattern)
    }

    /** PD-03: no KRX topic, subscribe, refresh call or refresh intent. Catches the P1(a)-prohibited reach. */
    @Test fun PD03_policyNamesNoTopicOrSession() {
        assertEquals("8 topic/session patterns", 8, TOPIC_AND_SESSION.size)
        for (pattern in TOPIC_AND_SESSION) assertAbsentFromPolicy(pattern)
    }

    /** PD-04: no PAC, recheck schedule, reducer, rejection, session, grant, candidate or outcome name (design r4 I4). */
    @Test fun PD04_policyNamesNoDecisionOwner() {
        assertEquals("8 decision-owner patterns", 8, DECISION_OWNERS.size)
        for (pattern in DECISION_OWNERS) assertAbsentFromPolicy(pattern)
    }

    /** PD-05: imports are the Kotlin standard library only (none is fine). Catches android, coroutines or another app package. */
    @Test fun PD05_policyImportsStandardLibraryOnly() {
        val imports = all.getValue(POLICY).lines().map { it.trim() }.filter { it.startsWith("import ") }
        val foreign = imports.filterNot { it.startsWith("import kotlin.") }
        assertEquals("non-standard imports", emptyList<String>(), foreign)
        // Positive control: the same filter flags PAC's imports.
        val pacForeign = all.getValue(PAC).lines().map { it.trim() }.filter { it.startsWith("import ") && !it.startsWith("import kotlin.") }
        assertTrue("positive control: PAC has non-standard imports", pacForeign.isNotEmpty())
    }
}
