package com.jay.fxi

import com.jay.fxi.data.remote.TopicCatalogue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-1 dormancy (`s6_1_contracts.r4.md` §4.4, Z01-Z10 and Z08s): what S6-1 adds has no production caller, P1(a)'s prohibitions
 * hold (no KRX subscribe/unsubscribe command, no KRX in `DESIRED`, no outside `FORCE_ENTITLEMENTS` query), and the S6C5
 * classification stays where it is (the session still drops KRX frames and candidates, and only tether is TETHER_DELIVERY).
 *
 * Format follows W05 (`IssuerGraphWritePreparationContractTest.kt:109-125`), widened to every production and measurement source
 * set: `src/main/java`, `src/productionBindings/java`, `src/s5Benchmark/java`, `src/debug/java`, `src/benchmark/java`, read
 * relative to the working directory `app/` (as W05 does). The code map strips `/*…*/` and `//…` comments the way W05 does, and is
 * keyed by the path relative to `app/` (e.g. `src/main/java/com/jay/fxi/data/remote/TopicFrameDecoder.kt`), so equal package paths
 * in different roots never overwrite each other. Expectations are file sets; where one more use inside an already listed file is
 * itself the violation (the Z05 literal, Z06, Z08 (b)) they are occurrence maps (key -> count, zero keys left out). Every zero
 * expectation carries a positive control from the same scan.
 *
 * Z08 (b) scans every `refresh(` call (except one directly preceded by `fun`, i.e. a declaration), cuts out its whole argument
 * span by matching parentheses across lines, and counts the call as one violation when `FORCE_ENTITLEMENTS` appears anywhere in
 * that span; an unmatched parenthesis also counts as a violation (fail-closed). Limits, recorded and not covered:
 * - a call that passes the intent through a variable (`refresh(intent, origin, …)`, today the schedule path at
 *   `PremiumAccessCoordinator.kt:412`) or a function reference (`::refresh`) is not seen; blocking a variable-borne addition inside
 *   an allowed file would need (a) to be an occurrence map too, and r3 keeps (a) a file set as Codex scoped it;
 * - parentheses inside string or character literals are counted like code parentheses (literals are not parsed). A literal with an
 *   unbalanced parenthesis inside a `refresh(` argument span can therefore end the span early or late; when it leaves the span
 *   unclosed the call counts as a violation (fail-closed), and when it closes the span early a `FORCE_ENTITLEMENTS` after that point
 *   is not seen;
 * - a declaration excluded by the `fun` rule must have whitespace between `fun` and `refresh`; an extension declaration
 *   (`fun X.refresh(`) is scanned like a call, which only errs towards a violation.
 *
 * This test is amended by agreement when S6-2a, S6-2b, S6-3, S6-4 or S6-5 deliberately adds a caller; deleting, skipping or
 * assuming it away is not allowed (`scope.r4.md:596`).
 */
class KrxS6DormancyContractTest {

    private companion object {
        val ROOTS = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
        const val M = "src/main/java/com/jay/fxi/"

        const val ADAPTER = "${M}data/graph/KrxGraphObservationAdapter.kt"
        const val PROJECTION = "${M}ui/rates/KrxRateRowProjection.kt"
        const val TSC = "${M}data/remote/TopicSessionCoordinator.kt"
        const val RECORDER_REDUCER = "${M}data/graph/GraphRecorderReducer.kt"
        const val SELECTION_POLICY = "${M}domain/model/GraphSeriesSelectionPolicy.kt"
        const val DECODER = "${M}data/remote/TopicFrameDecoder.kt"
        const val SUBSCRIBE_COMMAND = "${M}data/remote/TopicSubscribeCommand.kt"
        const val TOPIC_MESSAGE = "${M}data/remote/dto/TopicMessage.kt"
        const val COORDINATOR = "${M}data/entitlements/PremiumAccessCoordinator.kt"

        /** Raw text, keyed by the path relative to `app/`. */
        val all: Map<String, String> by lazy {
            ROOTS.flatMap { root ->
                val dir = File(root)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .map { "$root/${it.relativeTo(dir).invariantSeparatorsPath}" to it.readText() }.toList()
            }.toMap()
        }

        /** Comments stripped as W05 does (`IssuerGraphWritePreparationContractTest.kt:118`). */
        val code: Map<String, String> by lazy {
            all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        }

        fun files(pattern: Regex, map: Map<String, String> = code): Set<String> =
            map.filterValues { pattern.containsMatchIn(it) }.keys

        fun occurrences(pattern: Regex, map: Map<String, String> = code): Map<String, Int> =
            map.mapValues { (_, text) -> pattern.findAll(text).count() }.filterValues { it > 0 }

        private val refreshCall = Regex("""\brefresh\s*\(""")

        /**
         * Z08 (b): per file, the number of `refresh(` calls whose whole argument span names [token], plus every call whose
         * parentheses do not match (fail-closed). A `refresh(` directly preceded by `fun` + whitespace is a declaration and skipped.
         */
        fun refreshArgumentViolations(map: Map<String, String>, token: String): Map<String, Int> {
            val tokenPattern = Regex("""\b$token\b""")
            return map.mapValues { (_, text) ->
                refreshCall.findAll(text).count { match ->
                    val before = text.substring(0, match.range.first)
                    val trimmed = before.trimEnd()
                    val declaration = before.length > trimmed.length && trimmed.endsWith("fun") &&
                        (trimmed.length == 3 || !trimmed[trimmed.length - 4].isJavaIdentifierPart())
                    if (declaration) return@count false
                    val open = match.range.last
                    var depth = 1
                    var i = open + 1
                    while (i < text.length && depth > 0) {
                        when (text[i]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        i++
                    }
                    if (depth != 0) true else tokenPattern.containsMatchIn(text.substring(open + 1, i - 1))
                }
            }.filterValues { it > 0 }
        }
    }

    /** Z01: the scan is not vacuous — every root is there, the map is large, and the files the other rows name are in it. */
    @Test fun Z01_premise_everyRootExistsAndTheNamedFilesAreScanned() {
        for (root in ROOTS) assertTrue("premise: root $root exists", File(root).isDirectory)
        assertTrue("premise: the scan sees the whole source (${all.size})", all.size > 100)
        assertEquals("premise: the code map has the raw map's keys", all.keys, code.keys)
        for (key in listOf(ADAPTER, PROJECTION, TSC, RECORDER_REDUCER, SELECTION_POLICY)) {
            assertTrue("premise: the code map has $key", key in code)
        }
    }

    /** Z02: the adapter has no caller. Catches MZ-01 (wiring it into `GraphRecorderReducer.kt:178`). */
    @Test fun Z02_adapterHasNoCaller() {
        for (name in listOf("krxGraphMapping", "krxGraphSeriesIds", "KrxGraphMapping")) {
            // Positive control: the declaring file itself is found.
            assertEquals("\\b$name\\b", setOf(ADAPTER), files(Regex("""\b$name\b""")))
        }
    }

    /** Z03: the projection has no caller. Catches MZ-02 (replacing `PremiumTopicPresenter.kt:38`). */
    @Test fun Z03_projectionHasNoCaller() {
        for (name in listOf("premiumDisplayFor", "premiumEditableRostersFor", "KrxRowGate", "KRX_ROW_LABEL")) {
            // Positive control: the declaring file itself is found.
            assertEquals("\\b$name\\b", setOf(PROJECTION), files(Regex("""\b$name\b""")))
        }
    }

    /** Z04: D17 is not wired. Catches MZ-03 (a production flip calling it). */
    @Test fun Z04_d17IsNotWired() {
        // Positive control: the declaration is found.
        assertEquals(setOf(SELECTION_POLICY), files(Regex("""\bresetKrxPairOnFlip\b""")))
    }

    /**
     * Z05: the KRX topic name. Catches MZ-04 (the subscribe/desired side reaching for KRX_FUTURES, a new literal) and one more
     * literal inside the two files that already hold one (which a file set would let through).
     */
    @Test fun Z05_krxTopicNameStaysDeclaredOnceAndUnusedOutsideTheAdapter() {
        val krxFutures = Regex("""\bKRX_FUTURES\b""")
        assertEquals("KRX_FUTURES files", setOf(TSC, ADAPTER), files(krxFutures))
        // TopicSessionCoordinator.kt:68 - the declaration only.
        assertEquals("KRX_FUTURES once in TSC", 1, krxFutures.findAll(code.getValue(TSC)).count())
        // TopicFrameDecoder.kt:63, TopicSessionCoordinator.kt:68.
        assertEquals("topic literal occurrences", mapOf(DECODER to 1, TSC to 1), occurrences(Regex(""""krx:usd-krw-futures"""")))
        // Positive control.
        assertTrue("TopicCatalogue.TETHER is found in at least two files", files(Regex("""\bTopicCatalogue\.TETHER\b""")).size >= 2)
    }

    /**
     * Z06: no subscribe/unsubscribe command is added. Catches MZ-05 (an unwired KRX subscribe/unsubscribe command, P1(a)), a second
     * subscribe call inside `TopicSubscribeCommand.kt`, and a call split across lines (`TopicSubscribeRequest\n    .subscribe(`).
     */
    @Test fun Z06_noSubscribeOrUnsubscribeCommandIsAdded() {
        // Kotlin's \s includes the newline. TopicSubscribeCommand.kt:650.
        assertEquals("subscribe calls", mapOf(SUBSCRIBE_COMMAND to 1), occurrences(Regex("""TopicSubscribeRequest\s*\.\s*subscribe\s*\(""")))
        assertEquals("unsubscribe calls", emptyMap<String, Int>(), occurrences(Regex("""TopicSubscribeRequest\s*\.\s*unsubscribe\s*\(""")))
        // Positive control: the factory's declaration, TopicMessage.kt:47.
        assertEquals("fun unsubscribe( in TopicMessage.kt", 1, Regex("""fun\s+unsubscribe\s*\(""").findAll(code.getValue(TOPIC_MESSAGE)).count())
    }

    /** Z07: `DESIRED` has no KRX. Catches MZ-06 (adding KRX to DESIRED), locked here directly apart from S5FixtureRoutesTest :68. */
    @Test fun Z07_desiredHasNoKrx() {
        assertEquals(setOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot"), TopicCatalogue.DESIRED)
    }

    /**
     * Z08: no outside `FORCE_ENTITLEMENTS` query. Catches MZ-07 (`refresh(RefreshIntent.FORCE_ENTITLEMENTS)` from
     * `AuthAccessBinder` or a new file) and MZ-07b (a named or reordered CALLER call inside an allowed file, which r2's
     * `\brefresh\(\s*(?:RefreshIntent\.)?FORCE_ENTITLEMENTS\b` let through). Signature: `PremiumAccessCoordinator.kt:1516-1530`.
     */
    @Test fun Z08_noOutsideForceEntitlementsQuery() {
        // (a) Comment-only ControlSchema.kt and ControlObligations.kt drop out of the code map.
        assertEquals(
            "(a) FORCE_ENTITLEMENTS files",
            setOf(
                "${M}data/entitlements/PremiumAccess.kt",
                "${M}data/entitlements/PremiumAccessCoordinator.kt",
                "${M}data/entitlements/PremiumAccessReducer.kt",
                "${M}data/entitlements/control/ObligationComparators.kt",
                "${M}data/entitlements/control/DemandAuthFacts.kt",
                "${M}data/entitlements/control/HoldRecoveryFacts.kt"
            ),
            files(Regex("""\bFORCE_ENTITLEMENTS\b"""))
        )
        // (b)
        assertEquals("(b) refresh calls naming FORCE_ENTITLEMENTS", emptyMap<String, Int>(), refreshArgumentViolations(code, "FORCE_ENTITLEMENTS"))
        // Positive control: the same scan finds FORCE_PREMIUM (AuthAccessBinder.kt:399, :520, PremiumAccessCoordinator.kt:1394).
        val premium = refreshArgumentViolations(code, "FORCE_PREMIUM")
        assertTrue("the same scan counts FORCE_PREMIUM: $premium", premium.values.sum() >= 1)
    }

    /**
     * Z08s: the Z08 (b) scan itself. Catches a scan that sees only positional arguments (①②④ pass), one that assumes argument order
     * (①④), one that cannot cross a newline or a receiver (④), and one that only looks at the file set (all four pass).
     */
    @Test fun Z08s_theRefreshScanCatchesEveryCallShape() {
        val calls = listOf(
            "refresh(origin = QueryOrigin.CALLER, intent = RefreshIntent.FORCE_ENTITLEMENTS)",
            "refresh(intent = RefreshIntent.FORCE_ENTITLEMENTS, origin = QueryOrigin.CALLER)",
            "refresh(RefreshIntent.FORCE_ENTITLEMENTS)",
            "coordinator\n    .refresh(\n        requireDecisionGeneration = generation,\n        intent = FORCE_ENTITLEMENTS\n    )"
        )
        val forceEntitlements = Regex("""\bFORCE_ENTITLEMENTS\b""")
        val realFiles = files(forceEntitlements)
        assertEquals("the unappended map has no violation (Z08 (b))", emptyMap<String, Int>(), refreshArgumentViolations(code, "FORCE_ENTITLEMENTS"))
        for ((index, call) in calls.withIndex()) {
            val copy = code + (COORDINATOR to code.getValue(COORDINATOR) + "\n" + call)
            assertEquals("call ${index + 1}: one violation in the coordinator", mapOf(COORDINATOR to 1), refreshArgumentViolations(copy, "FORCE_ENTITLEMENTS"))
            assertEquals("call ${index + 1}: the (a) file set does not move", realFiles, files(forceEntitlements, copy))
        }
    }

    /**
     * Z09: the session still drops KRX and only tether is TETHER_DELIVERY (S6C5 rule 1, D14). Catches MZ-08 (wiring KRX reception)
     * and MZ-09 (adding KRX to evidenceFor).
     */
    @Test fun Z09_sessionStillDropsKrxAndClassifiesOnlyTetherAsTetherDelivery() {
        val tsc = code.getValue(TSC)
        // TopicSessionCoordinator.kt:1759, :2851.
        assertEquals("KRX frames dropped", 2, Regex("""is DecodedTopicFrame\.Krx -> Unit""").findAll(tsc).count())
        // TopicSessionCoordinator.kt:1881.
        assertEquals("KRX graph candidates dropped", 1, Regex("""entry\.source == "krx" \|\| entry\.asset == "usd-krw-futures"""").findAll(tsc).count())
        // TopicSessionCoordinator.kt:2893-2898.
        val evidenceFor = Regex(
            """fun\s+evidenceFor\s*\(\s*topic\s*:\s*String\s*\)\s*:\s*TopicSilenceEvidence\s*=\s*""" +
                """if\s*\(\s*topic\s*==\s*TopicCatalogue\.TETHER\s*\)\s*\{\s*TopicSilenceEvidence\.TETHER_DELIVERY\s*\}\s*""" +
                """else\s*\{\s*TopicSilenceEvidence\.OTHER\s*\}"""
        )
        assertEquals("evidenceFor classifies only tether as TETHER_DELIVERY", 1, evidenceFor.findAll(tsc).count())
        // Positive control.
        assertTrue("the same file handles tether frames", Regex("""is DecodedTopicFrame\.Tether""").findAll(tsc).count() >= 1)
    }

    /** Z10: the recorder has no KRX. Catches MZ-01 and a KRX axis slipped into the recorder (bypassing the critical CAPABILITY axis). */
    @Test fun Z10_recorderHasNoKrx() {
        val recorderFiles = listOf("GraphRecorderReducer.kt", "GraphRecorder.kt", "GraphRecorderTopicSink.kt", "GraphPendingInputs.kt")
            .map { "${M}data/graph/$it" }
        val krx = Regex("""(?i)krx""")
        for (key in recorderFiles) {
            assertTrue("premise: the code map has $key", key in code)
            assertEquals("$key names no KRX", 0, krx.findAll(code.getValue(key)).count())
        }
        // Positive control: GraphRecorderReducer.kt:178.
        assertTrue("tetherGraphMapping is found in the recorder reducer", Regex("""\btetherGraphMapping\b""").containsMatchIn(code.getValue(RECORDER_REDUCER)))
        // Positive control for the pattern itself: the same scan finds KRX where it is known to be.
        assertTrue("(?i)krx is found in TopicSessionCoordinator.kt", krx.containsMatchIn(code.getValue(TSC)))
    }
}
