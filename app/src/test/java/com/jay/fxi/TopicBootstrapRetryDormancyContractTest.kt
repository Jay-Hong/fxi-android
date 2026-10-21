package com.jay.fxi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2a dormancy (`s6_2a_contracts.r3.md` §5, Y01-Y05): the pure bootstrap retry policy
 * (`data/remote/TopicBootstrapRetryPolicy.kt`) has no production caller, holds no clock, randomness, sleep, mutable state,
 * exception swallowing or cancel wiring, and stays inside P1(a)'s prohibitions (no KRX reference, no subscribe/unsubscribe or
 * refresh call, no entitlements import, no capability / krx_visible / premium input).
 *
 * Format follows the S6-1 Z test (`KrxS6DormancyContractTest.kt:38-104`): the five roots `src/main/java`,
 * `src/productionBindings/java`, `src/s5Benchmark/java`, `src/debug/java`, `src/benchmark/java` (:41), read relative to the
 * working directory `app/`; a code map with `/*…*/` and `//…` comments stripped, keyed by the path relative to `app/`;
 * expectations as file sets. Every zero expectation carries a positive control that runs the very same pattern over the code map
 * outside the policy file and finds at least one file there. The S6-1 helpers are a `private companion` (:40), so this file keeps
 * its own copy of them (O-10).
 *
 * This test is amended by agreement when S6-2b deliberately adds a caller; deleting, skipping or assuming it away is not allowed
 * (`scope.r5.md:596`).
 */
class TopicBootstrapRetryDormancyContractTest {

    private companion object {
        val ROOTS = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
        const val M = "src/main/java/com/jay/fxi/"

        /** `N/` in the design. */
        const val N = "${M}data/remote/TopicBootstrapRetryPolicy.kt"
        const val TSC = "${M}data/remote/TopicSessionCoordinator.kt"
        const val TOPIC_RUNTIME = "${M}data/remote/TopicRuntime.kt"
        const val SVC = "${M}data/remote/TopicSnapshotBootstrapService.kt"
        const val SUBSCRIBE_COMMAND = "${M}data/remote/TopicSubscribeCommand.kt"
        const val AUTH_ACCESS_BINDER = "${M}data/entitlements/AuthAccessBinder.kt"
        const val COMPLETION_HANDOFF = "${M}data/entitlements/control/RequiredCompletionHandoff.kt"

        /** Every file read, as (path relative to `app/`, raw text), before any map collapses duplicates. */
        val read: List<Pair<String, String>> by lazy {
            ROOTS.flatMap { root ->
                val dir = File(root)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .map { "$root/${it.relativeTo(dir).invariantSeparatorsPath}" to it.readText() }.toList()
            }
        }

        /** Raw text, keyed by the path relative to `app/`. */
        val all: Map<String, String> by lazy { read.toMap() }

        /** Comments stripped as W05 does (`IssuerGraphWritePreparationContractTest.kt:118`, `KrxS6DormancyContractTest.kt:64-66`). */
        val code: Map<String, String> by lazy {
            all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        }

        /** The code map without the policy file: where every positive control looks. */
        val outsidePolicy: Map<String, String> by lazy { code - N }

        fun files(pattern: Regex, map: Map<String, String> = code): Set<String> =
            map.filterValues { pattern.containsMatchIn(it) }.keys

        // Y03 patterns. The named ones are reused verbatim by their named positive controls.
        const val DELAY = """\bdelay\s*\("""
        const val TIME_SOURCE = """\bTimeSource\b"""
        const val RANDOM = """\bRandom\b"""
        const val CATCH = """\bcatch\b"""
        val PURITY = listOf(
            """\bsuspend\b""",
            """\blaunch\b""",
            """\basync\b""",
            DELAY,
            """\bwithTimeout\w*""",
            TIME_SOURCE,
            """\bSystemClock\b""",
            """\bSystem\s*\.""",
            """\bClock\s*\.\s*System\b""",
            RANDOM,
            CATCH,
            """\brunCatching\b""",
            """\bvar\b""",
            """@Volatile""",
            """\bsynchronized\b""",
            """\bMutex\b""",
            """\bAtomic\w*""",
            """\bJob\b""",
            """\bcancel\s*\("""
        )

        // Y04 patterns.
        const val KRX_ANY = """(?i)krx"""
        const val KRX_FUTURES = """\bKRX_FUTURES\b"""
        const val SUBSCRIBE_REQUEST = """TopicSubscribeRequest"""
        const val SUBSCRIBE_CALL = """\b(un)?subscribe\s*\("""
        const val REFRESH_CALL = """\brefresh\s*\("""
        const val REFRESH_INTENTS = """\b(FORCE_ENTITLEMENTS|FORCE_PREMIUM|RefreshIntent)\b"""
        const val ENTITLEMENTS_PACKAGE = """\bcom\s*\.\s*jay\s*\.\s*fxi\s*\.\s*data\s*\.\s*entitlements\b"""
        const val ACCESS_INPUTS = """\b([Pp]remium\w*|TopicAccessSnapshot|TopicUseAuthority|capability|Capability|krxVisible|DESIRED)\b"""
        val P1A = listOf(KRX_ANY, KRX_FUTURES, SUBSCRIBE_REQUEST, SUBSCRIBE_CALL, REFRESH_CALL, REFRESH_INTENTS, ENTITLEMENTS_PACKAGE, ACCESS_INPUTS)

        /** The policy file's code; fails loudly (Y01) when the file is not where the scan looks. */
        val policy: String by lazy { code.getValue(N) }

        fun assertFoundIn(key: String, pattern: String) {
            assertTrue("premise: the code map has $key", key in code)
            assertTrue("positive control: $pattern is found in $key", Regex(pattern).containsMatchIn(code.getValue(key)))
        }

        /** Positive control first (the same pattern finds a file outside `N/`), then the zero in `N/`. */
        fun assertAbsentFromPolicy(pattern: String) {
            val regex = Regex(pattern)
            assertTrue("positive control: $pattern is found outside N/", files(regex, outsidePolicy).isNotEmpty())
            assertEquals("$pattern in N/", 0, regex.findAll(policy).count())
        }
    }

    /**
     * Y01: the scan is not vacuous — every root is there, the code map has the policy file, TSC, `TopicRuntime` and the bootstrap
     * service, and the code map's keys are exactly the files read. Catches a path error that lets the scan pass emptily.
     */
    @Test fun Y01_premise_everyRootExistsAndThePolicyFileIsScanned() {
        for (root in ROOTS) assertTrue("premise: root $root exists", File(root).isDirectory)
        for (key in listOf(N, TSC, TOPIC_RUNTIME, SVC)) {
            assertTrue("premise: the code map has $key", key in code)
        }
        assertEquals("premise: the code map's keys are the files read", read.map { it.first }.toSet(), code.keys)
        assertEquals("premise: no two files read share a key", read.size, code.size)
    }

    /**
     * Y02: no caller. Each of the seven names is found in the declaring file only (the positive control is the declaring file
     * itself). Catches MR-Y01 (TSC or `TopicRuntime` calling the policy, wiring that belongs to S6-2b).
     */
    @Test fun Y02_policyHasNoCaller() {
        for (name in listOf(
            "TopicBootstrapRetryPolicy",
            "TopicBootstrapLadder",
            "TopicBootstrapVerdict",
            "TopicBootstrapRetryKind",
            "TopicBootstrapStop",
            "TopicBootstrapGo",
            "TopicBootstrapNext"
        )) {
            assertEquals("\\b$name\\b", setOf(N), files(Regex("""\b$name\b""")))
        }
    }

    /**
     * Y03: purity. None of the 19 patterns occurs in the policy's code, and each is found by the same pattern outside it. Catches
     * MR-Y02 (a clock, randomness, sleep, mutable state, swallowed exception or cancel wiring inside the policy).
     */
    @Test fun Y03_policyIsPure() {
        assertEquals("19 purity patterns", 19, PURITY.size)
        // TopicRuntime.kt:291.
        assertFoundIn(TOPIC_RUNTIME, DELAY)
        // TopicRuntime.kt:109.
        assertFoundIn(TOPIC_RUNTIME, RANDOM)
        // TopicSnapshotBootstrapService.kt:71.
        assertFoundIn(SVC, TIME_SOURCE)
        // TopicSnapshotBootstrapService.kt:74.
        assertFoundIn(SVC, CATCH)
        for (pattern in PURITY) assertAbsentFromPolicy(pattern)
    }

    /**
     * Y04: P1(a) boundary and no KRX reference. None of the 8 patterns occurs in the policy's code, and each is found by the same
     * pattern outside it. Catches MR-Y03 (a KRX topic reference bypassing S6-1 Z05, a PAC import, a subscribe or refresh call, a
     * capability / krx_visible / premium input that would make it a new decision function; premium is MR-Y04, design r3).
     */
    @Test fun Y04_policyStaysInsideP1aAndNamesNoKrx() {
        assertEquals("8 P1(a) patterns", 8, P1A.size)
        // TopicSessionCoordinator.kt:68.
        assertFoundIn(TSC, KRX_ANY)
        assertFoundIn(TSC, KRX_FUTURES)
        // TopicSubscribeCommand.kt:650 (and :456 for the bare call).
        assertFoundIn(SUBSCRIBE_COMMAND, SUBSCRIBE_REQUEST)
        assertFoundIn(SUBSCRIBE_COMMAND, """TopicSubscribeRequest\s*\.\s*subscribe\s*\(""")
        assertFoundIn(SUBSCRIBE_COMMAND, SUBSCRIBE_CALL)
        // AuthAccessBinder.kt:399 (FORCE_PREMIUM).
        assertFoundIn(AUTH_ACCESS_BINDER, REFRESH_CALL)
        assertFoundIn(AUTH_ACCESS_BINDER, REFRESH_INTENTS)
        assertFoundIn(AUTH_ACCESS_BINDER, """\bFORCE_PREMIUM\b""")
        // AuthAccessBinder.kt:1, the package line.
        assertTrue(
            "positive control: $ENTITLEMENTS_PACKAGE matches the package line of $AUTH_ACCESS_BINDER",
            Regex(ENTITLEMENTS_PACKAGE).containsMatchIn(code.getValue(AUTH_ACCESS_BINDER).lineSequence().first())
        )
        // TopicRuntime.kt:10-11, the import form.
        assertTrue(
            "positive control: $ENTITLEMENTS_PACKAGE matches an import line of $TOPIC_RUNTIME",
            code.getValue(TOPIC_RUNTIME).lines().filter { it.startsWith("import ") }.any { Regex(ENTITLEMENTS_PACKAGE).containsMatchIn(it) }
        )
        // RequiredCompletionHandoff.kt:86, the qualified-name form (not the package or import lines).
        assertTrue("premise: the code map has $COMPLETION_HANDOFF", COMPLETION_HANDOFF in code)
        assertTrue(
            "positive control: $ENTITLEMENTS_PACKAGE matches a qualified name outside package/import lines of $COMPLETION_HANDOFF",
            code.getValue(COMPLETION_HANDOFF).lines().filter { !it.startsWith("package ") && !it.startsWith("import ") }
                .any { Regex(ENTITLEMENTS_PACKAGE).containsMatchIn(it) }
        )
        // TopicRuntime.kt:10-11.
        assertFoundIn(TOPIC_RUNTIME, ACCESS_INPUTS)
        for (pattern in P1A) assertAbsentFromPolicy(pattern)
    }

    /**
     * Y05 (design r3): the budget has one source. The policy names `TopicSnapshotBootstrapService.ATTEMPT_BUDGET` rather than
     * writing the number again (design §2), which RL01 cannot tell apart when the number is the same.
     * Catches MR-L09b (the budget re-written as the literal `10_000L`).
     */
    @Test fun Y05_theBudgetComesFromTheBootstrapService() {
        // TopicSnapshotBootstrapService.kt:126, the declaration.
        assertFoundIn(SVC, """\bATTEMPT_BUDGET\b""")
        assertTrue(
            "N/ names TopicSnapshotBootstrapService.ATTEMPT_BUDGET",
            Regex("""TopicSnapshotBootstrapService\s*\.\s*ATTEMPT_BUDGET""").containsMatchIn(policy)
        )
    }
}
