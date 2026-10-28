package com.jay.fxi

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.KrxSessionSourceContract
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
 *
 * Z05 amended by **S6-2b-1b-α contract tests r1** (design `s6_2b1b_contracts.r5.md` §6.3, §8.2, §13 T.8).
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
            assertEquals("contract(Z02): exact mapper consumers for $name", setOf(ADAPTER, RECORDER_REDUCER), files(Regex("""\b$name\b""")))
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
     *
     * Amended by agreement for S6-2b-1b-α (**S6-2b-1b-α contract tests r1**, design `s6_2b1b_contracts.r5.md` §6.3 D-13, §8.2,
     * §13 T.8): the session must tell a KRX attempt (WS send read, REST marker capture, REST hand-over), so TSC names KRX_FUTURES
     * twice — the declaration and the body of the single internal predicate in TopicCatalogue (`carriesKrx`, the α stub's name). The
     * file set and the literal counts are unchanged. Two assertions are added: outside the declaration, KRX_FUTURES appears only in that
     * predicate body; the predicate is called only from TSC. A third (review) locks the predicate's place: one `fun carriesKrx(` in TSC,
     * declared `internal` inside the braces of `object TopicCatalogue` (§6.3 "TopicCatalogue 안 internal 술어"). Catches, besides MZ-04:
     * a second KRX test written inline in TSC, the predicate called from another file (e.g. TopicRuntime, the rejected OD-B08 (가)), and
     * the predicate moved into the session class or made private there.
     */
    @Test fun Z05_krxTopicNameStaysDeclaredOnceAndUnusedOutsideTheAdapter() {
        val krxFutures = Regex("""\bKRX_FUTURES\b""")
        assertEquals("Z05 KRX_FUTURES files", setOf(TSC, ADAPTER), files(krxFutures))
        val tsc = code.getValue(TSC)
        assertTrue("Z05 positive control: TopicCatalogue declares the predicate", Regex("""\bfun\s+carriesKrx\s*\(""").containsMatchIn(tsc))
        // Added (c, review): the predicate is internal and sits inside object TopicCatalogue.
        val internalPredicate = Regex("""\binternal\s+fun\s+carriesKrx\s*\(""")
        assertEquals("Z05 (added) one carriesKrx declaration in TSC", 1, Regex("""\bfun\s+carriesKrx\s*\(""").findAll(tsc).count())
        assertTrue("Z05 (added) the predicate is internal in object TopicCatalogue", internalPredicate.containsMatchIn(catalogueBody(tsc)))
        val moved = tsc.replaceFirst(internalPredicate, "private fun carriesKrxGone(") +
            "\nclass Elsewhere { private fun carriesKrx(topic: String): Boolean = topic == TopicCatalogue.KRX_FUTURES }\n"
        assertTrue("Z05 self-check: a private predicate outside TopicCatalogue is caught", !internalPredicate.containsMatchIn(catalogueBody(moved)))
        // TopicSessionCoordinator.kt:68 (declaration) + the TopicCatalogue predicate body (S6-2b-1b-α).
        assertEquals("Z05 KRX_FUTURES twice in TSC (declaration + predicate body)", 2, krxFutures.findAll(tsc).count())
        // TopicFrameDecoder.kt:63, TopicSessionCoordinator.kt:68.
        assertEquals("Z05 topic literal occurrences", mapOf(DECODER to 1, TSC to 1), occurrences(Regex(""""krx:usd-krw-futures"""")))
        // Added (a): outside the one declaration, the one use is the predicate body.
        assertEquals("Z05 (added) one KRX_FUTURES declaration in TSC", 1, Regex("""\bconst\s+val\s+KRX_FUTURES\s*=""").findAll(tsc).count())
        val body = predicateBody(tsc)
        assertEquals("Z05 (added) the predicate body names KRX_FUTURES once", 1, krxFutures.findAll(body).count())
        assertEquals("Z05 (added) outside the declaration, KRX_FUTURES is used only in the predicate body",
            krxFutures.findAll(body).count(), krxFutures.findAll(tsc).count() - 1)
        // Added (b): the predicate's callers.
        assertEquals("Z05 (added) carriesKrx is called only in TSC", setOf(TSC), carriesKrxCallers(code))
        // Positive control.
        assertTrue("Z05 TopicCatalogue.TETHER is found in at least two files", files(Regex("""\bTopicCatalogue\.TETHER\b""")).size >= 2)
        val elsewhere = code + (RECORDER_REDUCER to code.getValue(RECORDER_REDUCER) + "\nprivate val k = TopicCatalogue.carriesKrx(t)\n")
        assertEquals("Z05 self-check: a caller in another file is caught", setOf(TSC, RECORDER_REDUCER), carriesKrxCallers(elsewhere) + TSC)
    }

    /** Z05: the text of `fun carriesKrx(…)`'s body — an expression body up to the next declaration line, or a braced block. */
    private fun predicateBody(text: String): String {
        val head = checkNotNull(Regex("""\bfun\s+carriesKrx\s*\([^)]*\)\s*(?::\s*[\w.?]+\s*)?""").find(text)) { "Z05 no carriesKrx declaration" }
        var i = head.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return when (text.getOrNull(i)) {
            '{' -> {
                var depth = 0
                var j = i
                while (j < text.length) {
                    if (text[j] == '{') depth++
                    if (text[j] == '}') { depth--; if (depth == 0) break }
                    j++
                }
                text.substring(i, minOf(j + 1, text.length))
            }
            '=' -> {
                val rest = text.substring(i + 1)
                val end = Regex("""\n\s*(?:\}|internal\b|private\b|public\b|val\b|var\b|fun\b|const\b|override\b|@)""").find(rest)?.range?.first ?: rest.length
                rest.substring(0, end)
            }
            else -> error("Z05 carriesKrx has no body")
        }
    }

    /** Z05: the text between the braces of `object TopicCatalogue` (braces matched; a literal brace inside a string would count). */
    private fun catalogueBody(text: String): String {
        val head = checkNotNull(Regex("""\bobject\s+TopicCatalogue\b[^{]*\{""").find(text)) { "Z05 no object TopicCatalogue" }
        var depth = 0
        for (i in head.range.last until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') { depth--; if (depth == 0) return text.substring(head.range.last + 1, i) }
        }
        error("Z05 object TopicCatalogue does not close")
    }

    /** Z05: files calling `carriesKrx(` (a `fun carriesKrx(` declaration is not a call). */
    private fun carriesKrxCallers(map: Map<String, String>): Set<String> =
        map.filterValues { text ->
            Regex("""\bcarriesKrx\s*\(""").findAll(text).any { match ->
                !Regex("""\bfun\s+$""").containsMatchIn(text.substring(maxOf(0, match.range.first - 8), match.range.first))
            }
        }.keys

    /** S6-3a r2 §6: one private real-retirement unsubscribe factory, with bypass positive controls. */
    @Test fun Z06_noSubscribeOrUnsubscribeCommandIsAdded() {
        val subscribe = Regex("""TopicSubscribeRequest\s*\.\s*subscribe\s*\(""")
        val unsubscribe = Regex("""TopicSubscribeRequest\s*\.\s*unsubscribe\s*\(""")
        assertEquals("contract(Z06): original subscribe inventory", mapOf(SUBSCRIBE_COMMAND to 1), occurrences(subscribe))
        assertEquals("contract(Z06): exactly one real retirement factory", mapOf(TSC to 1), occurrences(unsubscribe))
        val tsc = code.getValue(TSC)
        assertTrue("contract(Z06): private real cleanup exists", Regex("""private\s+fun\s+completeKrxRetirementOnLoop\s*\(""").containsMatchIn(tsc))
        val body = KrxSessionSourceContract.function(tsc, "completeKrxRetirementOnLoop")
        assertEquals("contract(Z06): sole factory belongs to private synchronous cleanup", 1, unsubscribe.findAll(body).count())
        assertTrue("contract(Z06): real cleanup remains private", Regex("""private\s+fun\s+completeKrxRetirementOnLoop\s*\(""").containsMatchIn(tsc))
        assertEquals("contract(Z06): original factory declaration stays single", 1,
            Regex("""fun\s+unsubscribe\s*\(""").findAll(code.getValue(TOPIC_MESSAGE)).count())
        val extra = "\nfun bypass() { TopicSubscribeRequest\n .unsubscribe(id, topics) }"
        for (path in listOf(TSC, RECORDER_REDUCER)) {
            val mutated = code + (path to code.getValue(path) + extra)
            assertTrue("contract(Z06): same scan catches bypass in $path", occurrences(unsubscribe, mutated) != mapOf(TSC to 1))
        }
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
     * Z09 amended by α-1a/seam r2 §5.5: ON routes only to WS/REST KRX guards; OFF drops are O1/O2. Only tether is TETHER_DELIVERY (S6C5 rule 1, D14). Catches MZ-08 (bypassing KRX guards)
     * and MZ-09 (adding KRX to evidenceFor).
     */
    @Test fun Z09_sessionStillDropsKrxAndClassifiesOnlyTetherAsTetherDelivery() {
        val tsc = code.getValue(TSC)
        // α-1a explicit amendment: only ON KRX WS/REST acceptance routes change. O1 and O2 keep exact OFF drops.
        val ws = KrxSessionSourceContract.function(tsc, "onFrame")
        val rest = KrxSessionSourceContract.function(tsc, "applyBootstrap")
        assertTrue("premise: Z09 same source still handles GENERAL tether", ws.contains("DecodedTopicFrame.Tether"))
        val probe = "fun onFrame() { receiveKrx(live, frame) }"
        val route = Regex("""\breceiveKrx\s*[(]""")
        assertEquals("premise: Z09 positive route probe", 1, route.findAll(probe).count())
        assertEquals("premise: Z09 bypass probe", 0, route.findAll(route.replace(probe, "receive(")).count())
        assertEquals("contract(Z09): WS KRX reaches only receiveKrx", 1, Regex("""\breceiveKrx\s*[(]""").findAll(ws).count())
        assertEquals("contract(Z09): REST KRX reaches only applyKrxBootstrap", 1, Regex("""\bapplyKrxBootstrap\s*[(]""").findAll(rest).count())
        // S6-3b-1: dedicated KRX hand-over; the GENERAL contamination filter stays exact.
        val offer = Regex("""\bofferKrxGraphObservations\s*[(]""")
        assertEquals("contract(Z09): dedicated offer declaration and two callers only", 3, offer.findAll(tsc).count())
        for (caller in listOf("receiveKrx", "applyKrxBootstrap")) {
            assertEquals("contract(Z09): $caller has exactly one dedicated offer", 1,
                offer.findAll(KrxSessionSourceContract.function(tsc, caller)).count())
        }
        assertEquals("contract(Z09): GENERAL KRX contamination filter remains", 1,
            Regex("""entry\.source == "krx" \|\| entry\.asset == "usd-krw-futures"""").findAll(tsc).count())
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

    /** S6-3b-1 replaces the zero-KRX inventory with exact original-ownership surfaces. */
    @Test fun Z10_recorderHasNoKrx() {
        val allowed = mapOf(
            "GraphRecorder.kt" to setOf("KrxGraphPurgePlan", "KrxGraphPurgeOutcome", "KrxGraphPurgeSeal", "KrxGraphInputProjection", "purgeKrxMemory", "captureKrxMemoryPurgePlan", "projectKrxGraphInput", "KrxGraphStamp", "krxGraphStamp", "krxSeriesOwners", "krxUntransferred", "krxLost", "bindKrxGraph", "loseKrxTopics", "admitsKrx", "isKrxGraphTopic", "krxAdmission", "krx"),
            "GraphRecorderReducer.kt" to setOf("KrxGraphPurgePlan", "KrxGraphPurgeOutcome", "KrxGraphPurgeDecision", "purgeKrxMemory", "projectKrxGraphInput", "KrxGraphStamp", "KrxGraphMapping", "krxGraphStamp", "krxSeriesOwners", "krxUntransferred", "krxLost", "krxGraphMapping", "krxGraphSeriesIds", "admitsKrxUse", "copyKrxGraphStamps", "isKrx", "krx", "admitsKrx", "krxAdmission", "krxGraphNamespace", "sameKrxUse", "isKrxGraphTopic", "krxMapping", "krxLoss", "krxIds", "loseKrxTopics", "markGraphPendingKrxLoss", "krxGraphLoss", "krxWaits"),
            "GraphRecorderTopicSink.kt" to setOf("KrxGraphPurgePlan", "KrxGraphPurgeOutcome", "KrxGraphPurgeDecision", "KrxGraphInputProjection", "purgeKrxMemory", "projectKrxGraphInput", "KrxGraphStamp", "krxGraphStamp", "loseKrxTopics", "krxLost", "krxLedger", "keptKrxLedger", "copyKrxGraphStamps", "krxLoss", "krxGraphLoss"),
            "GraphPendingInputs.kt" to setOf("KrxGraphPurgePlan", "KrxGraphPurgeOutcome", "KrxGraphPurgeDecision", "KrxGraphInputProjection", "purgeKrxMemory", "projectKrxGraphInput", "KrxGraphStamp", "krxGraphStamp", "krxLost", "KRX_MEMORY_KEY", "copyKrxGraphStamps", "markKrxLoss", "krxLoss", "krxGraphLoss", "markGraphPendingKrxLoss", "isKrxGraphTopic")
        )
        val identifiers = Regex("""\b[A-Za-z_][A-Za-z_0-9]*[Kk][Rr][Xx][A-Za-z_0-9]*\b|\b[Kk][Rr][Xx][A-Za-z_0-9]*\b""")
        fun violations(map: Map<String, String>) = allowed.mapValues { (file, names) ->
            identifiers.findAll(map.getValue("${M}data/graph/$file")).map { it.value }.toSet() - names
        }.filterValues { it.isNotEmpty() }
        assertEquals("contract(Z10): exact original-sidecar/C-1/cutoff/physical purge surface only", emptyMap<String, Set<String>>(), violations(code))
        val recorder = "${M}data/graph/GraphRecorder.kt"
        val bypass = code + (recorder to code.getValue(recorder) + "\nfun krxBypass() = Unit")
        assertEquals("contract(Z10): prefix-like bypass inside an allowed file is rejected", mapOf("GraphRecorder.kt" to setOf("krxBypass")), violations(bypass))
        assertTrue("contract(Z10): REC consumes original through shared gate", code.getValue(recorder).contains("bindKrxGraph"))
        assertTrue("contract(Z10): RED uses the dedicated pure mapper", code.getValue(RECORDER_REDUCER).contains("krxGraphMapping"))
        val ingressExpected = setOf("data/graph/KrxGraphFence.kt", "data/remote/TopicSessionCoordinator.kt", "data/remote/TopicRuntime.kt",
            "data/remote/TopicRuntimeOwner.kt", "data/remote/DetachableGraphSinks.kt", "data/graph/GraphRuntimeAssembly.kt").map { M + it }.toSet()
        val fenceExpected = setOf("data/graph/KrxGraphFence.kt", "data/graph/GraphV2AccessGate.kt", "data/graph/GraphRuntimeAssembly.kt").map { M + it }.toSet()
        assertEquals("contract(Z10): exact ingress references across every root", ingressExpected, files(Regex("""\bKrxGraphIngress\b""")))
        assertEquals("contract(Z10): exact fence references, distinct from stamp/namespace types", fenceExpected, files(Regex("""\bKrxGraphFence\b""")))
        val injected = code + (recorder to code.getValue(recorder) + "\nval bad: KrxGraphIngress? = null")
        assertTrue("contract(Z10): same scanner sees an unauthorized ingress reference", files(Regex("""\bKrxGraphIngress\b"""), injected) != ingressExpected)
    }
}
