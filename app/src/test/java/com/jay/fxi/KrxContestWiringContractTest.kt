package com.jay.fxi

import com.jay.fxi.data.remote.KrxSessionSourceContract
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
 *
 * **S6-2b-1b-α contract tests r1** (TA-07, design `s6_2b1b_contracts.r5.md` §13 T.8, §8.1, §2 ① and ③(c), §3, §6.3–§6.7):
 * KW05 extended (consumers read neither `KRX_CONTESTED` nor `krxContestView`), KW06 revised to the §8.1 exact sets (required ⊆
 * exact ⊆ upper bound), and new rows KW08–KW21 on the same code map and `files()`; KW22 (added by review: the session's internal
 * constructor keeps every parameter, type, default and pass-through of the public one) and the KW15 extension (CHECKED_ON files
 * keep the check; the S-4 file itself is exempt from the direct-assignment rule; only the existing ON factories switch the contest
 * on). Every new scan carries a positive control from the same scan. Every revised or new row (KW05, KW06, KW08–KW22) carries a self-check
 * that applies the mutation it catches to a copy of the code map, or — where the stub has nothing to find yet (KW12's ③(c) scan,
 * KW14's wiring shapes, KW16's per-body scan) — to a synthetic text written in the test. In rows that fail on the stub the self-checks
 * run before the contract assertions, so they are exercised there too. Rows that lock implementation wiring (KW06's D-6 attribution,
 * KW12, KW14, KW16) fail on the α stub by design; the others hold there.
 *
 * Revised after the adversarial review of r1 (g5-fix): KW12 gains the §2 ③(c) scan (a C-1 condition only behind the guard), KW06 names
 * the D-6 obligation before its exact set, KW09 locks the switch-off value `0L`, KW14 accepts a function reference and `@Binds`, KW15
 * requires one `assertHeld(` per `CHECKED_ON` even inside `checkedOnTest`, KW16 checks each KRX-use body, KW19 matches `[lL]ogger`.
 *
 * Revised after Codex tests r1 (tests r2, g5): KW14's positive control accepts the function reference as well as the dot call (Codex
 * fix 1); KW14 reads the exact passes of the send read — `krxSendReader = krxSendReader` into the session, `authority = authority`
 * and `krxSendReader = authority` in the `@Inject` delegation — instead of an `= authority` count (E-04); KW14 accepts either
 * singleton shape in GraphRuntimeModule and refuses a second instance (E-29); KW15's positive control points at TA-09's 09-0 and
 * 09-2 (E-02); KW12's KDoc states the limits of its comparison-form scan (E-03).
 *
 * Revised after Codex tests r2 (tests r3, g5; E-35 AGREE): KW22 expects the session's `krxSendReader` as `KrxSendReadAuthority?`
 * (nullable) with no default — a default stays forbidden — and gains three self-checks on copies of TSC's text (nullable without a
 * default accepted, non-null refused, nullable with `= null` refused); KW14's KDoc replaces its open item on the factory default with
 * the E-35 decision (the factory's `krxSendReader: KrxSendReadAuthority? = null`; the `@Inject` delegation passes the one singleton
 * explicitly), and its contract assertions are unchanged. After the tests r3 verification (g5-r3b): KW14 gains two E-35 self-checks
 * (`krxSendReader = null` in the `@Inject` delegation and into the session, each refused by the exact pairs after a parse window).
 *
 * Revised after Codex tests r3 (tests r4, kw14; Codex round 3 item 4): KW14 locks the factory's send-read parameter as exactly
 * `Parameter(krxSendReader, "KrxSendReadAuthority?", "null")` (`"null"` is the default text, not "no default"), read with the same
 * parser after the existing name check, and gains four self-checks on synthetic factory text, each after a parse window: `= null`
 * accepted; no default, a non-null type and a separate reader object as the default refused. The factory constructor's extraction
 * moved into a local function shared by the contract and the self-checks (same regex, span and parser; same messages).
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

        // S6-2b-1b-α (TA-07): the consumer and wiring files the new rows name.
        const val SUA = "${M}data/entitlements/SnapshotTopicUseAuthority.kt"
        const val TGD = "${M}data/entitlements/TopicGrantDeliverer.kt"
        const val GATE = "${M}data/graph/GraphV2AccessGate.kt"
        const val GV2RC = "${M}data/graph/GraphV2RequestCoordinator.kt"
        const val SEED = "${M}data/graph/GraphV2Cache.kt"
        const val HOLDER = "${M}ui/premium/graph/GraphV2ScreenStateHolder.kt"
        const val TSC = "${M}data/remote/TopicSessionCoordinator.kt"
        const val CMD = "${M}data/remote/TopicSubscribeCommand.kt"
        const val TOPIC_USE = "${M}data/remote/TopicUse.kt"
        const val RUNTIME = "${M}data/remote/TopicRuntime.kt"
        const val TRANSPORT = "${M}data/remote/AuthenticatedTransport.kt"
        const val BOOTSTRAP_SERVICE = "${M}data/remote/TopicSnapshotBootstrapService.kt"
        const val OUTCOME = "${M}data/remote/TopicSnapshotOutcome.kt"
        const val GRAPH_MODULE = "${M}di/GraphRuntimeModule.kt"
        const val PROCESS_GRAPH = "${M}data/graph/ProcessGraphBuilder.kt"

        // KW15: the unit test source set.
        const val TEST_ROOT = "src/test/java"
        const val T = "$TEST_ROOT/com/jay/fxi/"
        const val SELF = "${T}KrxContestWiringContractTest.kt"
        const val CHECK = "${T}data/entitlements/KrxOnPublicationCheck.kt"

        /** KW15 positive control (E-02): the TA-09 class whose rows 09-0 and 09-2 took over the stub's S-4 self test. */
        const val TA09 = "${T}data/entitlements/KrxOnPublicationCheckTest.kt"

        /**
         * KW06 (design r5 §8.1). Required elements and the upper bound are the design's; the exact set is this test version's reading
         * of the α implementation. KrxSendMarker: SUA joins as the send read's implementation (`krxSendMarker()`); TopicRuntime.kt
         * and TopicUse.kt stay inside the upper bound only (the wiring lambda need not name the type; the read interface is declared in
         * SUA here). capabilityInvalidations: GraphV2RequestCoordinator.kt joins because the OD-B07 ProtectedSlot keeps the original
         * request or seed capture's count, read off `GraphV2AccessCapture.capabilityInvalidations` by name (§3 D-6, T.14).
         */
        // α-1a seam r2 §1.1: CMD imports/carries KrxSendMarker only in KrxCommandAttempt.
        // No package-wide allowance; Z02 owns real call wiring, OFF traces own legacy behaviour.
        val MARKER_REQUIRED = setOf(MARKER, PAC, LEDGER, TGD, TSC)
        val MARKER_UPPER = setOf(MARKER, PAC, LEDGER, TGD, TSC, SUA, TOPIC_USE, RUNTIME, CMD)
        val MARKER_EXACT = setOf(MARKER, PAC, LEDGER, TGD, TSC, SUA, CMD)
        val CAP_INV_REQUIRED = setOf(TAS, PAC, SUA, GATE)
        val CAP_INV_UPPER = setOf(TAS, PAC, SUA, GATE, GV2RC, TSC)
        val CAP_INV_EXACT = setOf(TAS, PAC, SUA, GATE, GV2RC, TSC)

        /** KW12 (design r5 §2 (r4 통합 조정), §8.1 new scan): production files naming `krxPermitSeq`. */
        val PERMIT_FILES = setOf(TAS, PAC, SUA, GATE)

        /** KW15 extension: the ON factories that predate S-4 (0b / 2b-1a, O-8 — not changed) and S-4 itself. */
        const val BETA_FACTORY = "${T}data/entitlements/KrxBetaPacRig.kt"
        val ON_SWITCH_FILES = setOf(
            "${T}data/entitlements/PremiumAccessKrxContestTest.kt",
            "${T}data/entitlements/PremiumAccessKrxReapprovalTest.kt",
            "${T}data/entitlements/PremiumAccessKrxA1Test.kt",
            "${T}data/entitlements/PremiumAccessKrxPermitTest.kt",
            CHECK,
            BETA_FACTORY
        )

        /**
         * KW22: the two parameters the internal constructor adds (design r5 §3 OD-B06 (a), D-8; the read seam's name is the stub's). KW14
         * (E-04): TopicRuntimeFactory's internal-constructor parameter for the send read carries the same name.
         */
        const val READER_PARAMETER = "krxSendReader"
        const val MARKER_CHANNEL = "onRejectedWithMarker"

        fun strip(text: String): String =
            text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

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

        /** KW15: the unit test sources, keyed and stripped like [code]. */
        val testCode: Map<String, String> by lazy {
            val dir = File(TEST_ROOT)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .associate { "$TEST_ROOT/${it.relativeTo(dir).invariantSeparatorsPath}" to strip(it.readText()) }
        }

        fun files(pattern: Regex): Set<String> = code.filterValues { pattern.containsMatchIn(it) }.keys

        fun files(pattern: Regex, map: Map<String, String>): Set<String> = map.filterValues { pattern.containsMatchIn(it) }.keys

        fun occurrences(pattern: Regex, map: Map<String, String> = code): Map<String, Int> =
            map.mapValues { (_, text) -> pattern.findAll(text).count() }.filterValues { it > 0 }

        /**
         * The name at [start] is being declared rather than called: `fun`, `class`, `interface` or `object` earlier on its line with no
         * `=`, `(` or `{` in between (`class X(`, `fun X(`, `fun Receiver.x(`; not `val y = x(` or a call in an expression body).
         */
        fun declaredAt(text: String, start: Int): Boolean {
            val line = text.substring(text.lastIndexOf('\n', start - 1) + 1, start)
            return Regex("""\b(fun|class|interface|object)\b[^=({]*$""").containsMatchIn(line)
        }

        /** The text between the '(' at [open] and its matching ')', or null when it never closes (fail-closed). */
        fun argumentSpan(text: String, open: Int): String? {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) return text.substring(open + 1, i)
                    }
                }
            }
            return null
        }

        /** Every call of [name] in [text], declarations excluded, as its argument span (null: unbalanced). */
        fun callSpans(text: String, name: String): List<String?> =
            Regex("""\b$name\s*\(""").findAll(text).filter { !declaredAt(text, it.range.first) }
                .map { argumentSpan(text, it.range.last) }.toList()

        fun callCounts(name: String, map: Map<String, String> = code): Map<String, Int> =
            map.mapValues { (_, text) -> callSpans(text, name).size }.filterValues { it > 0 }

        /** Per file, the calls of [name] whose argument span matches [token], plus every unbalanced one (fail-closed). */
        fun spanViolations(map: Map<String, String>, name: String, token: Regex): Map<String, Int> =
            map.mapValues { (_, text) -> callSpans(text, name).count { it == null || token.containsMatchIn(it) } }.filterValues { it > 0 }

        fun normalized(text: String): String =
            text.replace(Regex("""\s+"""), " ").replace("( ", "(").replace(" )", ")").trim()

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

        // --- KW22: Kotlin parameter lists ------------------------------------------------------------------------------

        data class Parameter(val name: String, val type: String, val default: String?)

        /** Splits at top-level commas; (), [], {} and <> nest, and the `>` of `->` does not close. */
        fun splitTopLevel(list: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = 0
            while (i < list.length) {
                val c = list[i]
                if (c == '-' && i + 1 < list.length && list[i + 1] == '>') {
                    current.append("->")
                    i += 2
                    continue
                }
                when {
                    c in "([{<" -> depth += 1
                    c in ")]}>" -> depth -= 1
                    c == ',' && depth == 0 -> {
                        out += current.toString()
                        current.clear()
                        i += 1
                        continue
                    }
                }
                current.append(c)
                i += 1
            }
            if (current.isNotBlank()) out += current.toString()
            return out.map { normalized(it) }.filter { it.isNotEmpty() }
        }

        /** The first top-level `=` that is an assignment (not `==`, `!=`, `<=`, `>=`), or -1. */
        fun topLevelAssignment(text: String): Int {
            var depth = 0
            for (i in text.indices) {
                val c = text[i]
                if (c == '>' && i > 0 && text[i - 1] == '-') continue
                when {
                    c in "([{<" -> depth += 1
                    c in ")]}>" -> depth -= 1
                    c == '=' && depth == 0 &&
                        text.getOrNull(i + 1) != '=' && text.getOrNull(i - 1) !in setOf('=', '!', '<', '>') -> return i
                }
            }
            return -1
        }

        fun parameter(text: String): Parameter {
            val t = normalized(text).replace(Regex("""^(?:(?:private|internal|public|protected)\s+)?(?:(?:val|var)\s+)?"""), "")
            val colon = t.indexOf(':')
            check(colon > 0) { "KW22 unparsable parameter: $t" }
            val name = t.substring(0, colon).trim()
            val rest = t.substring(colon + 1)
            val eq = topLevelAssignment(rest)
            return if (eq < 0) Parameter(name, normalized(rest), null)
            else Parameter(name, normalized(rest.substring(0, eq)), normalized(rest.substring(eq + 1)))
        }

        // --- KW14 (E-04, E-29): named arguments and the module's bindings ------------------------------------------------------

        /** The `name = value` arguments of a call or delegation span, normalized; a positional argument fails the row (fail-closed). */
        fun namedArguments(span: String, row: String): List<Pair<String, String>> = splitTopLevel(span).map { argument ->
            val eq = topLevelAssignment(argument)
            check(eq > 0) { "$row positional argument: $argument" }
            normalized(argument.substring(0, eq)) to normalized(argument.substring(eq + 1))
        }

        /** KW11 and KW14 (E-29) count the same thing: a SnapshotTopicUseAuthority construction (`SnapshotTopicUseAuthority(` or `{`). */
        val SUA_CONSTRUCTION = Regex("""\bSnapshotTopicUseAuthority\s*[({]""")

        /** One function of a module file: its annotations, name, parameters, simple return-type name (null: none) and the text after it. */
        data class Binding(val annotations: String, val name: String, val parameters: List<Parameter>, val returns: String?, val body: String)

        val MODULE_FUN = Regex("""((?:@[\w.]+(?:\([^)]*\))?\s+)*)(?:(?:internal|private|public)\s+)?(?:abstract\s+)?fun\s+(\w+)\s*\(""")

        /**
         * The `@Provides`/`@Binds` functions of [module]; each body runs to the next function head or to the brace that closes its
         * enclosing declaration, whichever comes first (fail-closed on an unclosed list).
         */
        fun bindings(module: String): List<Binding> {
            val heads = MODULE_FUN.findAll(module).toList()
            return heads.mapIndexedNotNull { i, head ->
                if (!Regex("""@(Provides|Binds)\b""").containsMatchIn(head.groupValues[1])) return@mapIndexedNotNull null
                val end = heads.getOrNull(i + 1)?.range?.first ?: module.length
                val list = checkNotNull(argumentSpan(module, head.range.last)) { "KW14 module function ${head.groupValues[2]} does not close" }
                val raw = module.substring(head.range.last + list.length + 2, end)
                var depth = 0
                val close = raw.indices.firstOrNull { i -> when (raw[i]) { '{' -> { depth += 1; false }; '}' -> { depth -= 1; depth < 0 }; else -> false } } ?: raw.length
                val after = raw.substring(0, close)
                val type = Regex("""^\s*:\s*([\w.]+)""").find(after)
                Binding(head.groupValues[1], head.groupValues[2], splitTopLevel(list).map(::parameter),
                    type?.groupValues?.get(1)?.substringAfterLast('.'), normalized(type?.let { after.substring(it.range.last + 1) } ?: after))
            }
        }

        /** An identity binding's source type: `@Provides fun x(a: S): T = a` or `@Binds [abstract] fun x(a: S): T` gives S; else null. */
        fun identitySource(binding: Binding): String? {
            val parameter = binding.parameters.singleOrNull() ?: return null
            val binds = Regex("""@Binds\b""").containsMatchIn(binding.annotations) && Regex("""^[\s}]*$""").matches(binding.body)
            val provides = Regex("""@Provides\b""").containsMatchIn(binding.annotations) &&
                Regex("""^=\s*${Regex.escape(parameter.name)}[\s}]*$""").matches(binding.body)
            return if (binds || provides) parameter.type.substringAfterLast('.') else null
        }

        const val USE_TYPE = "TopicUseAuthority"
        const val SEND_READ_TYPE = "KrxSendReadAuthority"
        const val CONCRETE_TYPE = "SnapshotTopicUseAuthority"

        /**
         * KW14 (E-29): null when TopicUseAuthority and KrxSendReadAuthority are the one SnapshotTopicUseAuthority singleton in
         * [module], else the reason. Accepted: the one construction sits in a `@Singleton` binding that returns the concrete type or
         * KrxSendReadAuthority, and each of the two interface types is that binding's type or reaches it through identity bindings
         * ([identitySource]). Refused: a construction count other than one (KW11's count), the constructing binding not `@Singleton` or
         * returning TopicUseAuthority, two bindings of one type, and a type whose binding builds or wraps a value.
         */
        fun singletonViolation(module: String): String? {
            val built = SUA_CONSTRUCTION.findAll(module).count()
            if (built != 1) return "$CONCRETE_TYPE constructions in the module: $built (the one singleton is constructed once)"
            val all = bindings(module)
            val root = all.filter { SUA_CONSTRUCTION.containsMatchIn(it.body) }.singleOrNull()
                ?: return "the construction is not the body of one @Provides binding"
            if (root.returns !in setOf(CONCRETE_TYPE, SEND_READ_TYPE)) return "the singleton is provided as ${root.returns}, not as $CONCRETE_TYPE or $SEND_READ_TYPE"
            if (!Regex("""@Singleton\b""").containsMatchIn(root.annotations)) return "the constructing binding ${root.name} is not @Singleton"
            val typed = all.filter { it.returns in setOf(USE_TYPE, SEND_READ_TYPE, CONCRETE_TYPE) }.groupBy { it.returns }
            typed.forEach { (type, list) -> if (list.size > 1) return "$type has ${list.size} bindings" }
            fun reaches(type: String, seen: Set<String>): Boolean {
                if (type == root.returns) return true
                if (type in seen) return false
                val source = typed[type]?.single()?.let(::identitySource) ?: return false
                return reaches(source, seen + type)
            }
            for (type in listOf(USE_TYPE, SEND_READ_TYPE)) {
                if (!reaches(type, emptySet())) return "$type is not the same instance as the one singleton (${root.returns} from ${root.name})"
            }
            return null
        }

        // --- KW12 ③(c): a C-1 additional condition and the guard in front of it --------------------------------------------

        /**
         * A C-1 additional condition (design r5 §2 ③(a)·(c)): an equality test (`==`/`!=`) against the current record epoch
         * (`recordFence?.krxCapabilityEpoch`) or a capability-invalidation count (`capabilityInvalidations`, either side). Limit: a
         * comparison made through a local copy (`val e = facts.recordFence?.krxCapabilityEpoch; … == e`) is not seen; the per-file
         * premise (at least one condition found) keeps the scan from passing on a file where none is visible.
         */
        val C1_CONDITION = Regex(
            """\bcapabilityInvalidations\s*[!=]=|[!=]=\s*[\w.?]*\bcapabilityInvalidations\b|""" +
                """\brecordFence\?\.krxCapabilityEpoch\s*[!=]=|[!=]=\s*[\w.?]*\brecordFence\?\.krxCapabilityEpoch\b"""
        )

        /** The guard, called directly or through E (`krxEffective`, whose agreed body calls it — design r5 §5A.3). */
        val GUARD_CALL = Regex("""\bkrxConsumerGuard\s*\(|\bkrxEffective\s*\(""")

        val FUN_HEAD = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

        /** The function whose head comes last before [at]: its name and where its head ends. Null at top level (fail-closed). */
        fun enclosingFunction(text: String, at: Int): Pair<String, Int>? =
            FUN_HEAD.findAll(text).lastOrNull { it.range.first < at }?.let { it.groupValues[1] to it.range.last + 1 }

        /** A guard call stands between the enclosing function's head and [at] ("krxConsumerGuard를 앞에 둔"). */
        fun guardedAt(text: String, at: Int): Boolean {
            val (_, bodyStart) = enclosingFunction(text, at) ?: return false
            return bodyStart <= at && GUARD_CALL.containsMatchIn(text.substring(bodyStart, at))
        }

        /**
         * The functions holding a C-1 condition that is not behind the guard: the condition is unguarded when no guard call precedes it
         * inside its own function and not every call of that function (declarations excluded) is itself preceded by a guard call in
         * its own enclosing function. A function with no call at all counts as unguarded.
         */
        fun c1Unguarded(text: String): List<String> = C1_CONDITION.findAll(text).mapNotNull { hit ->
            val at = hit.range.first
            val enclosing = enclosingFunction(text, at) ?: return@mapNotNull "<top level>"
            if (guardedAt(text, at)) return@mapNotNull null
            val calls = Regex("""\b${enclosing.first}\s*\(""").findAll(text).filter { !declaredAt(text, it.range.first) }.toList()
            if (calls.isNotEmpty() && calls.all { guardedAt(text, it.range.first) }) null else enclosing.first
        }.distinct().toList()

        // --- KW16: a top-level function body -------------------------------------------------------------------------------

        /** From the head [head] to the next top-level declaration (column 0), or null when [head] is absent. */
        fun topLevelBody(text: String, head: Regex): String? {
            val start = head.find(text) ?: return null
            val rest = text.substring(start.range.last + 1)
            val end = Regex("""\n(?=@|(?:(?:internal|private|public)\s+)?(?:fun|data\s+class|class|interface|object|val|var)\b)""")
                .find(rest)?.range?.first ?: rest.length
            return rest.substring(0, end)
        }

        val POLICY_NAMES = listOf(
            "KrxContestPolicy", "KrxContestState", "KrxContestEvent", "KrxContestStep", "KrxContestPhase", "KrxAnswerKind", "KrxContestIgnore"
        )
        val EVIDENCE_NAMES = listOf("KrxReapprovalEvidence", "KrxDemandMerge", "reapprovalOnlyAfter")
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

    /**
     * KW-05: `KRX_CONTESTED`는 선언(TAS)과 생산자(PAC)에만 있다. 소비자는 기존 `capabilityAllowed` 경로를 읽는다.
     *
     * S6-2b-1b-α extension (design r5 §13 T.8 KW05, §8, §5A.1): `krxContestView`의 운영 참조도 PAC뿐이다 — α의 소비자(SUA·gate·seed·
     * TSC)는 contest 상태를 읽지 않고 스냅숏의 `capabilityAllowed`·`krxPermitSeq`만 읽는다. 잡을 변이: 소비자가 contest view나 HOLD 이름을
     * 직접 읽음(EF-B′와 같은 부류).
     */
    @Test fun KW05_contestedBlockHasNoProductionConsumer() {
        val found = files(Regex("""\bKRX_CONTESTED\b"""))
        assertEquals("KW05 KRX_CONTESTED in production", setOf(TAS, PAC), found)
        assertTrue("KW05 positive control: capabilityAllowed is read outside TAS",
            files(Regex("""\bcapabilityAllowed\b""")).any { it != TAS })
        val view = Regex("""\bkrxContestView\b""")
        assertTrue("KW05 positive control: PAC declares krxContestView", view.containsMatchIn(code.getValue(PAC)))
        assertEquals("KW05 krxContestView production references (no consumer reads the contest view)", setOf(PAC), files(view))
        val reading = code + (GATE to code.getValue(GATE) + "\nprivate fun leak(c: PremiumAccessCoordinator) = c.krxContestView\n")
        assertEquals("KW05 self-check: a consumer reading the contest view is caught", setOf(PAC, GATE), files(view, reading))
    }

    /**
     * KW-06 (S6-2b-1a, 설계 r2 4절): 새 상태는 0a 밖(PAC 비공개)에 있다 — 정책 파일은 표지·인계·막힘·재개 의무를 모르고, 새 타입은 선언 파일과
     * PAC(표지는 예약을 든 ledger 포함)에서만 쓰인다.
     *
     * S6-2b-1b-α revision (design r5 §13 T.8 KW06, §8.1, B6, D-22): the KrxSendMarker and capabilityInvalidations reference sets are
     * locked as exact sets inside the design's required elements and upper bound ([MARKER_REQUIRED] ⊆ [MARKER_EXACT] ⊆
     * [MARKER_UPPER]; [CAP_INV_REQUIRED] ⊆ [CAP_INV_EXACT] ⊆ [CAP_INV_UPPER]). A file outside the upper bound needs the design agreed
     * again (§8.1). Catches: a consumer file outside the bound naming the marker or the count (e.g. a transport file carrying the marker,
     * KW17's (b)/(b′)), a required carrier dropping it, and — on the α stub — the OD-B07 slot attribution not yet naming the count.
     *
     * Before the capabilityInvalidations exact set, the D-6 obligation is asserted by itself (GraphV2RequestCoordinator.kt names the
     * count: the ProtectedSlot keeps the original capture's), so the stub's failure names the obligation, not a set difference. §8.1
     * makes that file conditional ("그 이름으로 다룰 때만") and leaves the exact set to the reference implementation: both are
     * re-confirmed against the reference diff and written into the manifest; if the reference implementation does not name the field
     * there, the D-6 assertion and GV2RC in [CAP_INV_EXACT] are dropped together.
     */
    @Test fun KW06_newStateStaysOutOf0aAndInsidePac() {
        val policy = code.getValue(POLICY)
        for (name in listOf("rejectedPermit", "withheld", "reopenOwed", "KrxSendMarker", "KrxWithheld", "KrxReopenOwed", "capabilityInvalidations")) {
            assertTrue("KW06 0a 정책이 $name 을 안다", !Regex("""\b$name\b""").containsMatchIn(policy))
        }
        assertTrue("premise: KW06 KrxSendMarker required ⊆ exact ⊆ upper bound (§8.1)",
            MARKER_EXACT.containsAll(MARKER_REQUIRED) && MARKER_UPPER.containsAll(MARKER_EXACT))
        assertTrue("premise: KW06 capabilityInvalidations required ⊆ exact ⊆ upper bound (§8.1)",
            CAP_INV_EXACT.containsAll(CAP_INV_REQUIRED) && CAP_INV_UPPER.containsAll(CAP_INV_EXACT))
        assertTrue("KW06 positive control: PAC가 표지를 쓴다", Regex("""\bKrxSendMarker\b""").containsMatchIn(code.getValue(PAC)))

        // Self-check (runs on the stub): a transport file naming either type falls outside the upper bound.
        val leaked = code + (TRANSPORT to code.getValue(TRANSPORT) + "\nprivate val leak: KrxSendMarker? = null\nprivate val n = c.capabilityInvalidations\n")
        assertEquals("KW06 self-check: a marker in the transport is outside the bound", setOf(TRANSPORT),
            files(Regex("""\bKrxSendMarker\b"""), leaked) - MARKER_UPPER)
        assertEquals("KW06 self-check: a count in the transport is outside the bound", setOf(TRANSPORT),
            files(Regex("""\bcapabilityInvalidations\b"""), leaked) - CAP_INV_UPPER)

        val marker = files(Regex("""\bKrxSendMarker\b"""))
        assertTrue("KW06 KrxSendMarker required carriers missing: ${MARKER_REQUIRED - marker}", marker.containsAll(MARKER_REQUIRED))
        assertTrue("KW06 KrxSendMarker outside the §8.1 upper bound (re-agree the design): ${marker - MARKER_UPPER}", MARKER_UPPER.containsAll(marker))
        assertEquals("KW06 KrxSendMarker exact set", MARKER_EXACT, marker)
        for (name in listOf("KrxWithheld", "KrxReopenOwed")) {
            assertEquals("KW06 $name 참조", setOf(MARKER, PAC), files(Regex("""\b$name\b""")))
        }

        val capInv = files(Regex("""\bcapabilityInvalidations\b"""))
        assertTrue("KW06 capabilityInvalidations required readers missing: ${CAP_INV_REQUIRED - capInv}", capInv.containsAll(CAP_INV_REQUIRED))
        assertTrue("KW06 capabilityInvalidations outside the §8.1 upper bound (re-agree the design): ${capInv - CAP_INV_UPPER}", CAP_INV_UPPER.containsAll(capInv))
        assertTrue("KW06 (D-6) the ProtectedSlot attribution in GraphV2RequestCoordinator reads the original capture's capabilityInvalidations",
            GV2RC in capInv)
        assertEquals("contract(KW06): capabilityInvalidations exact set adds only TSC stamp reconciliation", CAP_INV_EXACT, capInv)
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

    // --- S6-2b-1b-α (TA-07, design r5 §13 T.8): OFF identity structure and consumer wiring ----------------------------

    /**
     * KW08 (design r5 §2 ①, B1): `TopicAccessSnapshot(` is built only by TAS INITIAL (TopicAccessSnapshot.kt:153) and the PAC
     * publication (PAC:3165); the class declaration (:141) is not a construction. Catches: a consumer file producing its own snapshot —
     * its `krxPermitSeq` would take the default 0L and put an ON guard back on HEAD's answer.
     */
    @Test fun KW08_accessSnapshotIsBuiltOnlyByTheInitialValueAndThePublication() {
        for (key in listOf(TAS, PAC, GATE)) assertTrue("premise: KW08 the code map has $key", key in code)
        val built = callCounts("TopicAccessSnapshot")
        assertEquals("KW08 TopicAccessSnapshot( constructions in production (declaration excluded)", mapOf(TAS to 1, PAC to 1), built)
        val mutated = code + (GATE to code.getValue(GATE) + "\nprivate val leaked = TopicAccessSnapshot(0L, facts, 0L, null, null)\n")
        assertEquals("KW08 self-check: a snapshot built in a consumer file is caught",
            mapOf(TAS to 1, PAC to 1, GATE to 1), callCounts("TopicAccessSnapshot", mutated))
    }

    /**
     * KW09 (design r5 §2 ①): `krxPermitSeq` is assigned once in production, in PAC, and that assignment is `if (krxContest.enabled)
     * … else 0L` on one line (PAC:3172). The TAS default (`val krxPermitSeq: Long = 0L`, :148) is a declaration, not an assignment.
     * Catches: an unconditional assignment (OFF publishes a permit), a switch-off value other than `0L` (`else 1L`), a second
     * assignment anywhere.
     */
    @Test fun KW09_thePermitIsAssignedOnceUnderTheSwitch() {
        val assignment = Regex("""\bkrxPermitSeq\s*=(?!=)""")
        assertTrue("KW09 positive control: TAS declares the field with its default 0L",
            Regex("""\bval\s+krxPermitSeq\s*:\s*Long\s*=\s*0L\b""").containsMatchIn(code.getValue(TAS)))
        assertEquals("KW09 krxPermitSeq assignments in production", mapOf(PAC to 1), occurrences(assignment))
        val switched = Regex("""^krxPermitSeq\s*=\s*if\s*\(\s*krxContest\.enabled\s*\)[^\n]*\belse\s+0L\b""")
        fun lineOf(text: String): String =
            text.substring(checkNotNull(assignment.find(text)) { "KW09 no krxPermitSeq assignment" }.range.first).substringBefore('\n')
        val pac = code.getValue(PAC)
        assertTrue("KW09 the one assignment is conditioned on the switch and publishes 0L when off: `${lineOf(pac)}`", switched.containsMatchIn(lineOf(pac)))
        val unconditional = pac.replaceFirst(Regex("""krxPermitSeq\s*=\s*if\s*\(\s*krxContest\.enabled\s*\)"""), "krxPermitSeq = if (true)")
        assertTrue("KW09 self-check: the mutation applied", unconditional != pac)
        assertTrue("KW09 self-check: an unconditional assignment is caught", !switched.containsMatchIn(lineOf(unconditional)))
        val offPermit = lineOf(pac).replace(Regex("""\belse\s+0L\b"""), "else 1L")
        assertTrue("KW09 self-check: the off-value mutation applied", offPermit != lineOf(pac))
        assertTrue("KW09 self-check: a switch-off permit (`else 1L`) is caught", !switched.containsMatchIn(offPermit))
    }

    /**
     * KW10 (design r5 §2 ①): no production `copy(` call names `krxPermitSeq` in its arguments. Catches: a copy that overwrites the
     * permit the publication computed.
     */
    @Test fun KW10_noProductionCopyOverwritesThePermit() {
        val permit = Regex("""\bkrxPermitSeq\b""")
        assertTrue("KW10 positive control: the same span scan finds `revision` in PAC's publication copy",
            (spanViolations(code, "copy", Regex("""\brevision\b"""))[PAC] ?: 0) >= 1)
        assertEquals("KW10 copy( argument spans naming krxPermitSeq", emptyMap<String, Int>(), spanViolations(code, "copy", permit))
        val mutated = code + (GATE to code.getValue(GATE) + "\nprivate fun leak(s: TopicAccessSnapshot) = s.copy(krxPermitSeq = 1L)\n")
        assertEquals("KW10 self-check: a copy overwriting the permit is caught", mapOf(GATE to 1), spanViolations(mutated, "copy", permit))
    }

    /**
     * KW11 (design r5 §2 ①, D-3): the snapshot is supplied only as `{ coordinator.accessSnapshot }` (GraphRuntimeModule.kt:37,
     * ProcessGraphBuilder.kt:97). S6-3a adds exactly one TopicRuntime memory-cleanup supplier of the same published value;
     * SnapshotTopicUseAuthority is still constructed once (GraphRuntimeModule). Catches: any additional snapshot read/supplier,
     * including a second one inside RT, and the topic factory building its own authority (D-3 (ii)).
     */
    @Test fun KW11_theSnapshotIsSuppliedOnlyByThePublishedValue() {
        for (key in listOf(GRAPH_MODULE, PROCESS_GRAPH, RUNTIME)) assertTrue("premise: KW11 the code map has $key", key in code)
        val read = Regex("""\.accessSnapshot\b""")
        val supplier = Regex("""\{\s*coordinator\.accessSnapshot\s*\}""")
        val expectedReads = mapOf(GRAPH_MODULE to 1, PROCESS_GRAPH to 1, RUNTIME to 1)
        assertEquals("contract(KW11): exact published snapshot reads include only the one RT supplier", expectedReads, occurrences(read))
        assertEquals("contract(KW11): all three reads are the supply lambda `{ coordinator.accessSnapshot }`",
            expectedReads, occurrences(supplier))
        for (path in listOf(RUNTIME, GATE)) {
            val extra = code + (path to code.getValue(path) + "\nprivate val extraSnapshot = { coordinator.accessSnapshot }\n")
            val extraReads = expectedReads + (path to ((expectedReads[path] ?: 0) + 1))
            assertEquals("contract(KW11): the same read scan catches an extra supplier in $path", extraReads, occurrences(read, extra))
            assertEquals("contract(KW11): the same lambda scan catches an extra supplier in $path", extraReads, occurrences(supplier, extra))
        }
        val construction = SUA_CONSTRUCTION
        assertEquals("KW11 SnapshotTopicUseAuthority constructions (one singleton)", mapOf(GRAPH_MODULE to 1),
            occurrences(construction).filterKeys { it != SUA })
        val mutated = code + (RUNTIME to code.getValue(RUNTIME) + "\nprivate val own = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n")
        assertEquals("KW11 self-check: the factory building its own authority is caught",
            mapOf(GRAPH_MODULE to 1, RUNTIME to 1), occurrences(construction, mutated).filterKeys { it != SUA })
    }

    /**
     * KW12 (design r5 §2 D-7, ③(c), §8.1 new scan): the guard `krxConsumerGuard` is defined once (SUA) and called from exactly
     * {SUA, gate, TSC}; production names `krxPermitSeq` in exactly {TAS, PAC, SUA, gate}; no consumer copies the predicate (in the
     * gate every `krxPermitSeq` is a guard argument, in SUA a guard or marker argument, in TSC every `permitSeq` a guard argument, and
     * no consumer compares a permit itself); and (§2 ③(c)) in SUA and the gate every C-1 additional condition ([C1_CONDITION]: an
     * equality test against the current record epoch or a capability-invalidation count) stands behind the guard — a guard call
     * (`krxConsumerGuard(` or E, `krxEffective(`) precedes it in its own function, or every call of that function is so preceded in
     * the caller's function ([c1Unguarded]). Catches: G-CP (a `> 0` copy), a consumer bypassing the guard (a C-1 condition reached
     * without the guard in front, as in admitsKrx calling the C-1 function bare while joinForExposure keeps the guard). The guard read
     * from a second source (G-SN) is not caught here; it belongs to 01-6/02-7/04-3. The ③(c) form is this test version's reading of
     * "krxConsumerGuard를 앞에 둔 판정 식" (design r5 §18.2 leaves it to the test version): it needs the condition in the visible
     * comparison forms of [C1_CONDITION], and treats "the function whose head comes last before the hit" as its enclosing function.
     *
     * Limits (E-03): the comparison-form scan cannot see a comparison made through a local copy
     * (`val e = facts.recordFence?.krxCapabilityEpoch; … == e`, or a copied count), and it does not prove that the guard's result
     * controls the comparison — it only finds a guard call textually ahead of it in the same function or in every caller, so a guard
     * whose result is discarded or sits in an unrelated branch also passes. The final form is re-agreed against the reference
     * implementation before lock (design r5 :95, :1129).
     *
     * Self-checks run first (they read only synthetic text and a copy of the code map), so they also run on the stub, which has no
     * caller of the guard and no C-1 condition yet.
     */
    @Test fun KW12_theGuardIsDefinedOnceAndCalledByTheThreeConsumers() {
        for (key in listOf(SUA, GATE, TSC)) assertTrue("premise: KW12 the code map has $key", key in code)

        // Self-checks of the G-CP scan and the ③(c) scan (synthetic shapes of the agreed gate and SUA).
        val comparison = Regex("""\b(krxP|p)ermitSeq\s*(>=?|<=?|==|!=)|(>=?|<=?|==|!=)\s*[\w.]*\b(krxP|p)ermitSeq\b""")
        val consumers = listOf(GATE, TSC, GV2RC, SEED, HOLDER).associateWith { code.getValue(it) } +
            (SUA to code.getValue(SUA).replace(Regex("""fun\s+krxConsumerGuard\s*\([^)]*\)\s*:\s*Boolean\s*=[^\n]*"""), ""))
        val copied = (consumers - TSC) + (GATE to consumers.getValue(GATE) + "\nprivate fun copy(s: TopicAccessSnapshot) = s.krxPermitSeq > 0\n")
        assertEquals("KW12 self-check: a guard copy in the gate is caught", mapOf(GATE to 1), occurrences(comparison, copied))
        val c1Function = """
            private fun c1(captured: GraphV2AccessCapture, access: TopicAccessSnapshot, n: Long): Boolean =
                captured.krxCapabilityEpoch == access.facts.recordFence?.krxCapabilityEpoch && n == access.capabilityInvalidations
        """
        val guardedGate = """
            fun admits(captured: GraphV2AccessCapture, component: GraphV2DiskComponent): Boolean = when (component) {
                GraphV2DiskComponent.GENERAL -> true
                GraphV2DiskComponent.KRX -> admitsKrx(captured, access, captured.capabilityInvalidations)
            }
            fun joinForExposure(captured: GraphV2AccessCapture, n: Long = captured.capabilityInvalidations) = admitsKrx(captured, access, n)
            private fun admitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot, n: Long): Boolean =
                captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed &&
                    (!krxConsumerGuard(access.krxPermitSeq) || c1(captured, access, n))
        """ + c1Function
        val bypassGate = """
            fun admits(captured: GraphV2AccessCapture, component: GraphV2DiskComponent): Boolean = when (component) {
                GraphV2DiskComponent.GENERAL -> true
                GraphV2DiskComponent.KRX -> admitsKrx(captured, access)
            }
            fun joinForExposure(captured: GraphV2AccessCapture, n: Long = captured.capabilityInvalidations): Boolean =
                captured.krxCapabilityEpoch != null && krxConsumerGuard(access.krxPermitSeq) && c1(captured, access, n)
            private fun admitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
                captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed && c1(captured, access, captured.capabilityInvalidations)
        """ + c1Function
        val guardedSua = """
            internal fun TopicAccessSnapshot.admitsKrxUse(use: KrxUseLifetime): Boolean {
                if (!admitsUse(use.lifetime) || !krxEffective()) return false
                if (use.capabilityInvalidations != capabilityInvalidations) return false
                return use.krxCapabilityEpoch == facts.recordFence?.krxCapabilityEpoch
            }
        """
        val inlineUnguardedSua = """
            internal fun TopicAccessSnapshot.admitsKrxUse(use: KrxUseLifetime): Boolean =
                admitsUse(use.lifetime) && facts.capabilityAllowed && use.capabilityInvalidations == capabilityInvalidations
            internal fun TopicAccessSnapshot.krxEffective(): Boolean = krxConsumerGuard(krxPermitSeq) && facts.capabilityAllowed
        """
        assertEquals("KW12 self-check (③(c) positive control): the guarded gate shape has no unguarded C-1", emptyList<String>(), c1Unguarded(guardedGate))
        assertEquals("KW12 self-check (③(c)): admitsKrx calling the C-1 function without the guard is caught", listOf("c1"), c1Unguarded(bypassGate))
        assertEquals("KW12 self-check (③(c) positive control): the guarded SUA shape (`!=` and `==`) has no unguarded C-1", emptyList<String>(), c1Unguarded(guardedSua))
        assertEquals("KW12 self-check (③(c)): an inline C-1 with no guard in front is caught", listOf("admitsKrxUse"), c1Unguarded(inlineUnguardedSua))

        assertEquals("KW12 krxConsumerGuard definitions", mapOf(SUA to 1), occurrences(Regex("""\bfun\s+krxConsumerGuard\s*\(""")))
        assertEquals("KW12 krxConsumerGuard call files", setOf(SUA, GATE, TSC), callCounts("krxConsumerGuard").keys)
        assertEquals("KW12 krxPermitSeq production references", PERMIT_FILES, files(Regex("""\bkrxPermitSeq\b""")))

        fun inside(text: String, token: Regex, vararg calls: String): Boolean =
            token.findAll(text).count() == calls.sumOf { name -> callSpans(text, name).sumOf { span -> span?.let { token.findAll(it).count() } ?: 0 } }
        val permit = Regex("""\bkrxPermitSeq\b""")
        assertTrue("KW12 every krxPermitSeq in the gate is a krxConsumerGuard argument", inside(code.getValue(GATE), permit, "krxConsumerGuard"))
        assertTrue("KW12 every krxPermitSeq in SUA is a krxConsumerGuard or KrxSendMarker argument",
            inside(code.getValue(SUA), permit, "krxConsumerGuard", "KrxSendMarker", "KrxSessionStamp"))
        // α-1a exact amendment: TSC may compare/carry the same read's stamp in the named ledger/issue owners only.
        // The predicate itself remains SUA's krxConsumerGuard; arbitrary p > 0 copies stay forbidden.
        val tsc = code.getValue(TSC)
        val ledgerNames = listOf("prepareKrxAttempt", "reconcileKrxSession", "reevaluateKrxPermit", "onKrxAttemptSent", "issueKrxBootstrap")
        val ledgerRanges = ledgerNames.map { name ->
            val body = KrxSessionSourceContract.function(tsc, name)
            val start = tsc.indexOf(body)
            start until start + body.length
        }
        val guardRanges = Regex("""\bkrxConsumerGuard\s*[(]""").findAll(tsc).map { hit ->
            val span = checkNotNull(argumentSpan(tsc, hit.range.last))
            hit.range.first..hit.range.last + span.length + 1
        }.toList()
        for (hit in Regex("""\bpermitSeq\b""").findAll(tsc)) {
            val permitted = ledgerRanges.any { hit.range.first in it } ||
                guardRanges.any { hit.range.first in it }
            assertTrue("contract(KW12): TSC permit read belongs to a named ledger owner or the existing guard", permitted)
        }
        assertEquals("KW12 copied permit predicates outside TSC ledger", emptyMap<String, Int>(), occurrences(comparison, consumers - TSC))
        for (hit in comparison.findAll(tsc)) {
            val line = tsc.substring(tsc.lastIndexOf('\n', hit.range.first - 1) + 1, tsc.indexOf('\n', hit.range.last).let { if (it < 0) tsc.length else it })
            assertTrue("contract(KW12): session comparison must be inside a ledger owner and compare consumedPermit",
                ledgerRanges.any { hit.range.first in it } && line.contains("consumedPermit"))
        }

        for (key in listOf(SUA, GATE)) {
            val text = code.getValue(key)
            assertTrue("KW12 ③(c) premise: C-1 conditions are present in $key", C1_CONDITION.containsMatchIn(text))
            assertEquals("KW12 ③(c) C-1 conditions not preceded by the guard in $key", emptyList<String>(), c1Unguarded(text))
        }
    }

    /**
     * KW13 (design r5 §3 OD-B06 (c)): neither TSC nor TopicRuntime obtains the send read or SnapshotTopicUseAuthority by a down-cast
     * (`as?`, `as`, `is`). Catches DC-X: a down-cast that is null under a wrapper, so a KRX row passes without the KRX path.
     */
    @Test fun KW13_noDownCastToTheSendRead() {
        val cast = Regex("""\b(as\??|is)\s+([\w.]+\.)?(KrxSendReadAuthority|SnapshotTopicUseAuthority)\b""")
        assertTrue("KW13 positive control: `as?` is found in TSC (`as? TopicSnapshotOutcome.Delivered`)",
            Regex("""\bas\?\s+TopicSnapshotOutcome\.Delivered\b""").containsMatchIn(code.getValue(TSC)))
        for (key in listOf(TSC, RUNTIME)) {
            assertTrue("premise: KW13 the code map has $key", key in code)
            assertEquals("KW13 down-casts to the send read in $key", 0, cast.findAll(code.getValue(key)).count())
        }
        val mutated = code.getValue(TSC) + "\nprivate val reader = authority as? KrxSendReadAuthority\n"
        assertEquals("KW13 self-check: a down-cast in TSC is caught", 1, cast.findAll(mutated).count())
    }

    /**
     * KW14 (design r5 §13 T.8 KW14, §3 D-3·D-8, §6.2; tests r2: E-04, E-29): TopicRuntime.create passes `onRejectedWithMarker` to
     * the deliverer's three-argument `forwardRejection` and the factory's send read to the session's internal-constructor parameter
     * exactly as `krxSendReader = krxSendReader` (TopicRuntime.kt:129-152); that channel is the only refusal wiring; the factory's
     * internal constructor names its send-read parameter `krxSendReader` (E-04: the session's name; its type and default: E-35, see
     * below); the `@Inject` constructor's `authority` is the internal send-read type (D-3 (i′)) and its `: this(` delegation passes it
     * exactly as `authority = authority` and `krxSendReader = authority` (E-04 replaces the earlier "`= authority` at least twice"
     * count); and in GraphRuntimeModule TopicUseAuthority and KrxSendReadAuthority are the one SnapshotTopicUseAuthority singleton
     * (E-29, [singletonViolation]). Catches: production wiring missing while every rig test passes; the send read or the injected
     * authority passed under another name or as another value; the factory's send-read parameter without its `= null` default, non-null,
     * or defaulting to a separate reader object; a second SnapshotTopicUseAuthority or a separate instance behind one of the two types.
     *
     * Decided (E-35, Codex tests r2 AGREE; it replaces the open item of tests r2): the factory's parameter is
     * `krxSendReader: KrxSendReadAuthority? = null` and the session's is `krxSendReader: KrxSendReadAuthority?` with no default
     * (KW22). `= authority` was not possible: the factory's internal constructor declares `authority: TopicUseAuthority`
     * (TopicRuntime.kt:67), so that default would need a down-cast, which KW13 forbids in TopicRuntime.kt. A null reader is the
     * reader-less session (E-07/E-35), distinct from an absent marker channel. The factory parameter's type and default are locked:
     * `KrxSendReadAuthority? = null` (E-35, Codex round 3). KW14 parses the factory's internal constructor and compares its one
     * `krxSendReader` parameter with `Parameter(krxSendReader, "KrxSendReadAuthority?", "null")`, where `"null"` is the default text; a
     * parameter without a default parses with no default (Kotlin `null`) and is refused, as are a non-null type and a separate reader
     * object as the default (self-checks). The type text is compared exactly (a qualified name is refused, fail-closed). KW14 also checks
     * the explicit `krxSendReader = authority` in the `@Inject` delegation (the one singleton passed to both parameters) and the exact
     * `krxSendReader = krxSendReader` pass in create. `krxSendReader = null` in either place compiles under E-35; the exact pairs refuse
     * it (self-checks).
     *
     * Accepted shapes. The marker channel: the lambda `{ a, b, c -> deliverer.forwardRejection(a, b, c) }` or the reference
     * `deliverer::forwardRejection` (the three-argument overload is the one the parameter type selects); the positive control that
     * precedes it accepts both as well (Codex fix 1). The module (E-29; the provider text is not pinned): either a concrete
     * SnapshotTopicUseAuthority `@Singleton` with TopicUseAuthority and KrxSendReadAuthority both bound to it, or the d3_probe shape — a
     * `@Singleton` KrxSendReadAuthority built from SnapshotTopicUseAuthority with TopicUseAuthority bound to it. A binding is
     * `@Provides fun x(a: S): T = a` or `@Binds [abstract] fun x(a: S): T`; a chain of such bindings to the one singleton is the same
     * object. Refused: any second construction (counted as KW11 counts it, [SUA_CONSTRUCTION]), the constructing binding not
     * `@Singleton` or providing TopicUseAuthority, and a type whose binding builds or wraps a value. Limits: a block-bodied binding
     * (`{ return a }`) is not read as an identity and is refused (fail-closed); bindings outside GraphRuntimeModule.kt are not read.
     * Self-checks on synthetic text run first.
     */
    @Test fun KW14_runtimeWiresTheMarkerChannelAndTheSendRead() {
        val forwarding = Regex("""\bdeliverer(?:\.forwardRejection\s*\(|\s*::\s*forwardRejection\b)""")
        val markerChannel = Regex(
            """\{\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*->\s*deliverer\.forwardRejection\s*\(\s*\1\s*,\s*\2\s*,\s*\3\s*\)\s*\}|deliverer\s*::\s*forwardRejection"""
        )
        fun passedAs(passed: List<Pair<String, String>>, name: String): List<Pair<String, String>> = passed.filter { it.first == name }
        fun markerWired(passed: List<Pair<String, String>>): Boolean =
            passedAs(passed, MARKER_CHANNEL).singleOrNull()?.let { markerChannel.matches(it.second) } == true
        fun twoArgumentChannel(passed: List<Pair<String, String>>): Boolean = passedAs(passed, "onRejected").isNotEmpty()
        val readerPass = listOf(READER_PARAMETER to READER_PARAMETER)
        fun readerPassed(passed: List<Pair<String, String>>): Boolean = passedAs(passed, READER_PARAMETER) == readerPass
        val injectedAuthority = listOf("authority" to "authority")
        val injectedReader = listOf(READER_PARAMETER to "authority")
        fun authorityPassedTwice(passed: List<Pair<String, String>>): Boolean =
            passedAs(passed, "authority") == injectedAuthority && passedAs(passed, READER_PARAMETER) == injectedReader
        // The factory's internal constructor (E-35, Codex tests r3): the contract and its self-checks read it with this one function.
        val factoryHead = Regex("""\bclass\s+TopicRuntimeFactory\s+internal\s+constructor\s*\(""")
        fun factoryParametersOf(text: String, row: String): List<Parameter> =
            splitTopLevel(checkNotNull(argumentSpan(text, checkNotNull(factoryHead.find(text)) { "$row no factory constructor" }.range.last)) {
                "$row the factory's internal constructor does not close"
            }).map(::parameter)
        // `"null"` here is the default text `= null`; a parameter without a default parses with default = null (Kotlin) and differs.
        val factoryReaderExpected = Parameter(READER_PARAMETER, "KrxSendReadAuthority?", "null")
        fun factoryReader(parameters: List<Parameter>): List<Parameter> = parameters.filter { it.name == READER_PARAMETER }

        // Self-checks (run on the stub). The session call: both accepted channel shapes pass the positive control and the contract.
        fun sessionSpan(vararg arguments: String): String = checkNotNull(
            callSpans("val session = TopicSessionCoordinator(\n" + arguments.joinToString(",\n") + "\n)\n", "TopicSessionCoordinator").single()
        ) { "KW14 self-check: the synthetic session call closes" }
        val lambdaChannel = "onRejectedWithMarker = { owner, rejected, marker -> deliverer.forwardRejection(owner, rejected, marker) }"
        val referenceChannel = "onRejectedWithMarker = deliverer::forwardRejection"
        for ((shape, channel) in listOf("lambda" to lambdaChannel, "function-reference" to referenceChannel)) {
            val synthetic = sessionSpan("scope = scope", "authority = authority", "krxSendReader = krxSendReader", channel, "graphSink = graphInputs ?: DormantTopicGraphSink")
            val passed = namedArguments(synthetic, "KW14 self-check")
            assertTrue("KW14 self-check: the $shape shape passes the positive control", forwarding.containsMatchIn(synthetic))
            assertTrue("KW14 self-check: the $shape shape is accepted as the marker channel", markerWired(passed))
            assertTrue("KW14 self-check: the $shape shape has no two-argument channel", !twoArgumentChannel(passed))
            assertTrue("KW14 self-check: `krxSendReader = krxSendReader` is the exact reader pass", readerPassed(passed))
        }
        val dropping = namedArguments(sessionSpan("authority = authority", "krxSendReader = krxSendReader",
            "onRejectedWithMarker = { owner, rejected, _ -> deliverer.forwardRejection(owner, rejected) }"), "KW14 self-check")
        assertTrue("KW14 self-check: a lambda dropping the marker is refused", !markerWired(dropping))
        val head = sessionSpan("scope = scope", "authority = authority", "onRejected = { owner, rejected -> deliverer.forwardRejection(owner, rejected) }")
        assertTrue("KW14 self-check: HEAD's two-argument wiring (TopicRuntime.kt:150 at de50885) passes the positive control", forwarding.containsMatchIn(head))
        assertTrue("KW14 self-check: HEAD's two-argument wiring is seen by the no-onRejected scan", twoArgumentChannel(namedArguments(head, "KW14 self-check")))
        assertTrue("KW14 self-check: a session call without the reader is refused", !readerPassed(namedArguments(head, "KW14 self-check")))
        assertTrue("KW14 self-check: the reader passed as another value (`krxSendReader = authority`) is refused",
            !readerPassed(namedArguments(sessionSpan("authority = authority", "krxSendReader = authority", referenceChannel), "KW14 self-check")))
        // The @Inject delegation: the exact pass replaces the count, which an unrelated second `= authority` satisfied.
        assertTrue("KW14 self-check: `authority = authority, krxSendReader = authority` is accepted",
            authorityPassedTwice(namedArguments("orders = orders, authority = authority, krxSendReader = authority, clock = clock", "KW14 self-check")))
        assertTrue("KW14 self-check: two `= authority` without `krxSendReader = authority` are refused",
            !authorityPassedTwice(namedArguments("authority = authority, lastKnownAuthority = authority", "KW14 self-check")))
        // E-35 made both reader parameters nullable, so `krxSendReader = null` now compiles in the delegation and in create; the exact
        // pairs refuse it. Each copy is first shown to parse with the reader as `null` (the window), then refused.
        val nullInDelegation = namedArguments("authority = authority, krxSendReader = null", "KW14 self-check")
        assertEquals("KW14 self-check (E-35): the delegation copy passes `authority = authority` and the reader as `null`",
            listOf("authority" to "authority", READER_PARAMETER to "null"), nullInDelegation)
        assertTrue("KW14 self-check (E-35): `krxSendReader = null` in the @Inject delegation is refused", !authorityPassedTwice(nullInDelegation))
        val nullIntoSession = namedArguments(sessionSpan("authority = authority", "krxSendReader = null", referenceChannel), "KW14 self-check")
        assertEquals("KW14 self-check (E-35): the session copy passes the reader as `null`",
            listOf(READER_PARAMETER to "null"), passedAs(nullIntoSession, READER_PARAMETER))
        assertTrue("KW14 self-check (E-35): `krxSendReader = null` into the session is refused", !readerPassed(nullIntoSession))
        assertTrue("KW14 self-check: the reader built as another value in the delegation is refused",
            !authorityPassedTwice(namedArguments("authority = authority, krxSendReader = SnapshotTopicUseAuthority { snapshot }", "KW14 self-check")))
        // The factory's send-read parameter (E-35, Codex tests r3 item 4): exactly `krxSendReader: KrxSendReadAuthority? = null`. Each
        // synthetic factory copy is first shown to parse — five parameters, the reader read as the expected name, type and default
        // (the window) — and then judged by the contract's comparison with factoryReaderExpected.
        fun factoryCopy(declaration: String): String = "internal class TopicRuntimeFactory internal constructor(\n" +
            "    private val orders: AccessOrderSequence,\n    private val authority: TopicUseAuthority,\n" +
            "    private val $declaration,\n    private val clock: TopicCommandClock,\n" +
            "    private val lastKnown: TopicLastKnownStore? = null\n) {\n}\n"
        data class FactoryShape(val shape: String, val declaration: String, val parsed: Parameter, val accepted: Boolean)
        for (s in listOf(
            FactoryShape("nullable with the `= null` default (E-35)", "$READER_PARAMETER: KrxSendReadAuthority? = null",
                Parameter(READER_PARAMETER, "KrxSendReadAuthority?", "null"), true),
            FactoryShape("nullable without a default", "$READER_PARAMETER: KrxSendReadAuthority?",
                Parameter(READER_PARAMETER, "KrxSendReadAuthority?", null), false),
            FactoryShape("a non-null type (default text kept as `null`, so only the type differs)", "$READER_PARAMETER: KrxSendReadAuthority = null",
                Parameter(READER_PARAMETER, "KrxSendReadAuthority", "null"), false),
            FactoryShape("a separate reader object as the default", "$READER_PARAMETER: KrxSendReadAuthority? = SomeReader",
                Parameter(READER_PARAMETER, "KrxSendReadAuthority?", "SomeReader"), false)
        )) {
            val parameters = factoryParametersOf(factoryCopy(s.declaration), "KW14 self-check")
            val parsedText = "type `${s.parsed.type}`, " + (s.parsed.default?.let { "default text `$it`" } ?: "no default")
            assertEquals("KW14 self-check (E-35): the factory copy with ${s.shape} parses as five parameters, the reader third",
                listOf("orders", "authority", READER_PARAMETER, "clock", "lastKnown"), parameters.map { it.name })
            assertEquals("KW14 self-check (E-35): the factory copy with ${s.shape} parses its one reader as $parsedText",
                listOf(s.parsed), factoryReader(parameters))
            assertEquals("KW14 self-check (E-35): the factory reader with ${s.shape} is ${if (s.accepted) "accepted" else "refused"}",
                s.accepted, factoryReader(parameters) == listOf(factoryReaderExpected))
        }
        // The module (E-29): accepted and refused shapes.
        val singleton = "@Provides\n@Singleton\n"
        val concrete = singleton +
            "fun provideSnapshotTopicUseAuthority(coordinator: PremiumAccessCoordinator): SnapshotTopicUseAuthority =\n    SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n" +
            "@Provides\nfun provideTopicUseAuthority(authority: SnapshotTopicUseAuthority): TopicUseAuthority = authority\n" +
            "@Provides\nfun provideKrxSendReadAuthority(authority: SnapshotTopicUseAuthority): KrxSendReadAuthority = authority\n}\n"
        val concreteBinds = singleton +
            "fun provideSnapshotTopicUseAuthority(coordinator: PremiumAccessCoordinator): SnapshotTopicUseAuthority = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n}\n" +
            "@Module\n@InstallIn(SingletonComponent::class)\ninternal abstract class AuthorityBindings {\n" +
            "    @Binds abstract fun bindTopicUseAuthority(authority: SnapshotTopicUseAuthority): TopicUseAuthority\n" +
            "    @Binds abstract fun bindKrxSendReadAuthority(authority: SnapshotTopicUseAuthority): KrxSendReadAuthority\n}\n"
        val probeRoot = singleton +
            "fun provideKrxSendReadAuthority(coordinator: PremiumAccessCoordinator): com.jay.fxi.data.entitlements.KrxSendReadAuthority =\n    SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n"
        val probe = probeRoot +
            "@Provides\nfun provideTopicUseAuthority(authority: com.jay.fxi.data.entitlements.KrxSendReadAuthority): TopicUseAuthority = authority\n"
        val probeBinds = probeRoot + "@Binds abstract fun bindTopicUseAuthority(reader: KrxSendReadAuthority): TopicUseAuthority\n"
        val chained = singleton +
            "fun provideSnapshotTopicUseAuthority(coordinator: PremiumAccessCoordinator): SnapshotTopicUseAuthority = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n" +
            "@Provides\nfun provideKrxSendReadAuthority(authority: SnapshotTopicUseAuthority): KrxSendReadAuthority = authority\n" +
            "@Provides\nfun provideTopicUseAuthority(reader: KrxSendReadAuthority): TopicUseAuthority = reader\n"
        // A declaration header after a binding (tests r2 verify:g5 R4, R5): the body ends at the brace closing its enclosing declaration.
        val moduleObject = "@Module\n@InstallIn(SingletonComponent::class)\ninternal object GraphRuntimeModule {\n"
        val bindsBeforeObject = "@Module\n@InstallIn(SingletonComponent::class)\ninternal abstract class AuthorityBindings {\n" +
            "    @Binds abstract fun bindTopicUseAuthority(authority: SnapshotTopicUseAuthority): TopicUseAuthority\n" +
            "    @Binds abstract fun bindKrxSendReadAuthority(authority: SnapshotTopicUseAuthority): KrxSendReadAuthority\n}\n" +
            moduleObject + singleton +
            "fun provideSnapshotTopicUseAuthority(coordinator: PremiumAccessCoordinator): SnapshotTopicUseAuthority = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n}\n"
        val probeThenModule = moduleObject + probe + "}\n" +
            "@Module\n@InstallIn(SingletonComponent::class)\ninternal object OtherModule {\n    @Provides fun provideOther(): Other = Other()\n}\n"
        for ((shape, text) in listOf("concrete singleton (@Provides)" to concrete, "concrete singleton (@Binds)" to concreteBinds,
                "d3_probe (@Provides)" to probe, "d3_probe (@Binds)" to probeBinds, "concrete singleton chained through the send read" to chained,
                "concrete singleton with the @Binds class placed before the module object" to bindsBeforeObject,
                "d3_probe as the object's last members with another module object after it" to probeThenModule)) {
            assertEquals("KW14 self-check (E-29): the $shape shape is accepted", null, singletonViolation(text))
        }
        val secondConstruction = probeRoot +
            "@Provides\nfun provideTopicUseAuthority(c: PremiumAccessCoordinator): TopicUseAuthority = SnapshotTopicUseAuthority { c.accessSnapshot }\n"
        val unscoped = "@Provides\nfun provideKrxSendReadAuthority(coordinator: PremiumAccessCoordinator): KrxSendReadAuthority = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n" +
            "@Provides\nfun provideTopicUseAuthority(authority: KrxSendReadAuthority): TopicUseAuthority = authority\n"
        val wrapped = probeRoot + "@Provides\nfun provideTopicUseAuthority(authority: KrxSendReadAuthority): TopicUseAuthority = object : TopicUseAuthority by authority {}\n"
        val oneSideOnly = singleton +
            "fun provideSnapshotTopicUseAuthority(coordinator: PremiumAccessCoordinator): SnapshotTopicUseAuthority = SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n" +
            "@Provides\nfun provideTopicUseAuthority(authority: SnapshotTopicUseAuthority): TopicUseAuthority = authority\n"
        val headModule = singleton +
            "fun provideTopicUseAuthority(coordinator: PremiumAccessCoordinator): TopicUseAuthority =\n    SnapshotTopicUseAuthority { coordinator.accessSnapshot }\n"
        for ((shape, text, reason) in listOf(
            Triple("a second SnapshotTopicUseAuthority construction for TopicUseAuthority", secondConstruction, "constructions in the module: 2"),
            Triple("an unscoped constructing binding (a separate instance per injection)", unscoped, "is not @Singleton"),
            Triple("TopicUseAuthority wrapping the send read (a separate instance)", wrapped, "TopicUseAuthority is not the same instance"),
            Triple("the concrete singleton with KrxSendReadAuthority unbound", oneSideOnly, "KrxSendReadAuthority is not the same instance"),
            Triple("HEAD's provider (GraphRuntimeModule.kt:34-37 at de50885)", headModule, "provided as TopicUseAuthority")
        )) {
            val violation = singletonViolation(text)
            assertTrue("KW14 self-check (E-29): $shape is refused for its reason (`$reason`), got `$violation`", violation?.contains(reason) == true)
        }

        for (key in listOf(RUNTIME, GRAPH_MODULE)) assertTrue("premise: KW14 the code map has $key", key in code)
        val runtime = code.getValue(RUNTIME)
        val sessions = callSpans(runtime, "TopicSessionCoordinator")
        assertEquals("premise: KW14 TopicRuntime.kt builds one session", 1, sessions.size)
        val span = checkNotNull(sessions.single()) { "KW14 the session call does not close" }
        assertTrue("KW14 positive control: the span scan sees the deliverer's refusal forwarding (`deliverer.forwardRejection(` or `deliverer::forwardRejection`)",
            forwarding.containsMatchIn(span))
        val toSession = namedArguments(span, "KW14")
        assertTrue("premise: KW14 the session call's named arguments are read (`authority = authority`, TopicRuntime.kt:139)", ("authority" to "authority") in toSession)

        assertTrue("KW14 onRejectedWithMarker is wired to the deliverer's three-argument forwardRejection", markerWired(toSession))
        assertTrue("KW14 the marker channel is the session's only refusal wiring (no `onRejected =`)", !twoArgumentChannel(toSession))
        assertEquals("KW14 (E-04) the session's internal-constructor parameter `$READER_PARAMETER` receives the factory's `$READER_PARAMETER` exactly",
            readerPass, passedAs(toSession, READER_PARAMETER))

        val factory = factoryHead.find(runtime)
        assertTrue("premise: KW14 TopicRuntimeFactory declares an internal primary constructor", factory != null)
        val factoryParameters = factoryParametersOf(runtime, "KW14")
        assertTrue("premise: KW14 the factory's internal-constructor parameters are read (`authority`, TopicRuntime.kt:67)", factoryParameters.any { it.name == "authority" })
        assertTrue("KW14 (E-04) the factory's internal constructor names its send-read parameter `$READER_PARAMETER`",
            factoryParameters.any { it.name == READER_PARAMETER })
        assertEquals("KW14 (E-35, Codex round 3) the factory's send-read parameter is exactly `$READER_PARAMETER: KrxSendReadAuthority? = null`",
            listOf(factoryReaderExpected), factoryReader(factoryParameters))

        val inject = Regex("""@Inject\s+constructor\s*\(""").find(runtime)
        assertTrue("premise: KW14 TopicRuntimeFactory has an @Inject constructor", inject != null)
        val injectHead = checkNotNull(inject) { "KW14 TopicRuntimeFactory has no @Inject constructor" }
        val injected = checkNotNull(argumentSpan(runtime, injectHead.range.last)) { "KW14 @Inject constructor does not close" }
        assertTrue("KW14 the @Inject constructor's authority is the internal send-read type (D-3 (i′))",
            Regex("""\bauthority\s*:\s*([\w.]+\.)?KrxSendReadAuthority\b""").containsMatchIn(injected))
        val afterInject = runtime.substring(injectHead.range.last + injected.length + 2)
        val delegation = Regex("""^\s*:\s*this\s*\(""").find(afterInject)
        assertTrue("premise: KW14 the @Inject constructor delegates with `: this(`", delegation != null)
        val passed = checkNotNull(argumentSpan(afterInject, checkNotNull(delegation) { "KW14 no `: this(` delegation" }.range.last)) {
            "KW14 the @Inject delegation does not close"
        }
        val byInject = namedArguments(passed, "KW14")
        assertTrue("premise: KW14 the @Inject delegation's named arguments are read (`orders = orders`, TopicRuntime.kt:102)", ("orders" to "orders") in byInject)
        assertEquals("KW14 (E-04) the @Inject delegation passes the injected authority exactly as `authority = authority`",
            injectedAuthority, passedAs(byInject, "authority"))
        assertEquals("KW14 (E-04) the @Inject delegation passes the injected authority exactly as `$READER_PARAMETER = authority`",
            injectedReader, passedAs(byInject, READER_PARAMETER))

        val module = code.getValue(GRAPH_MODULE)
        assertTrue("premise: KW14 GraphRuntimeModule's bindings are read (`provideDeletionAdmissionStore`, GraphRuntimeModule.kt:42)",
            bindings(module).any { it.name == "provideDeletionAdmissionStore" })
        assertEquals("KW14 (E-29) GraphRuntimeModule binds TopicUseAuthority and KrxSendReadAuthority to the one SnapshotTopicUseAuthority singleton",
            null, singletonViolation(module))
    }

    /**
     * KW15 (design r5 §13 T.8 KW15, optional; extended by review): new α ON test files keep the publication check (TA-09 composition).
     * (a) A test file calls `assertHeld(` at least once per `CHECKED_ON` it names (import lines not counted), whether or not it also
     * uses `checkedOnTest`: checkedOnTest asserts only the PAC it builds itself, which its caller never names, and a PAC built inside its
     * body with `CHECKED_ON` needs its own `assertHeld` (KrxOnPublicationCheck.kt:116-120); (b) no test file that uses `CHECKED_ON` or `checkedOnTest` assigns `onAccessPublishedForTest` itself — KrxOnPublicationCheck.kt, where S-4
     * installs the check and composes observers, is exempt; (c, addition) only the ON factories that predate S-4 (O-8) and S-4 itself
     * switch the contest on in test sources. This file is excluded from the scan (its own patterns name these tokens). Catches: the check
     * silently dropped (a hook assignment replaces it, a CHECKED_ON harness with no end assertion, a new ON factory without the check).
     * Positive controls: S-4 defines CHECKED_ON; the direct-assignment pattern finds S-4 and an older ON test; (E-02) the check-use
     * pattern finds TA-09's 09-2 and rule (a)'s `assertHeld(` pattern finds TA-09's 09-0 (KrxOnPublicationCheckTest) — those rows took
     * over the stub's S-4 self test, which is deleted, and no file set here names it.
     */
    @Test fun KW15_newOnTestsKeepThePublicationCheck() {
        assertTrue("premise: KW15 the test map is read (${testCode.size})", testCode.size > 100 && CHECK in testCode && SELF in testCode)
        val tests = testCode - SELF
        val namesChecked = Regex("""\bCHECKED_ON\b""")
        val usesCheck = Regex("""\bCHECKED_ON\b|\bcheckedOnTest\b""")
        val direct = Regex("""\bonAccessPublishedForTest\s*=(?!=)""")
        val switchesOn = Regex("""KrxContestSwitch\s*\(\s*(enabled\s*=\s*)?true\s*\)""")
        assertTrue("KW15 positive control: S-4 defines CHECKED_ON", Regex("""\bval\s+CHECKED_ON\b""").containsMatchIn(tests.getValue(CHECK)))
        assertTrue("KW15 positive control: the direct-assignment pattern finds S-4 and an older ON test",
            files(direct, tests).containsAll(setOf(CHECK, "${T}data/entitlements/PremiumAccessKrxA1Test.kt")))
        val ta09 = tests[TA09].orEmpty()
        val r09_0 = Regex("""\bfun\s+r09_0_\w*\s*\(""").find(ta09)?.let { head ->
            ta09.substring(head.range.first).let { rest -> rest.substring(0, Regex("""@Test\b""").find(rest)?.range?.first ?: rest.length) }
        }.orEmpty()
        assertTrue("KW15 positive control (E-02): the check-use pattern finds TA-09's 09-2 (KrxOnPublicationCheckTest, `= checkedOnTest`)",
            TA09 in files(usesCheck, tests) && Regex("""\bfun\s+r09_2_\w*\s*\(\s*\)\s*=\s*checkedOnTest\b""").containsMatchIn(ta09))
        assertTrue("KW15 positive control (E-02): rule (a)'s assertHeld( pattern finds TA-09's 09-0 (KrxOnPublicationCheckTest, the check's own end assertion)",
            Regex("""\bassertHeld\s*\(""").containsMatchIn(r09_0))

        fun withoutImports(text: String): String = text.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
        fun unchecked(map: Map<String, String>): Set<String> = map.filter { (key, text) ->
            val body = withoutImports(text)
            key != CHECK && Regex("""\bassertHeld\s*\(""").findAll(body).count() < namesChecked.findAll(body).count()
        }.keys
        fun assigning(map: Map<String, String>): Set<String> = (files(usesCheck, map) intersect files(direct, map)) - CHECK
        assertEquals("KW15 CHECKED_ON files without one assertHeld( per CHECKED_ON", emptySet<String>(), unchecked(tests))
        assertEquals("KW15 check-using files assigning onAccessPublishedForTest directly", emptySet<String>(), assigning(tests))
        assertEquals("KW15 test files switching the contest on (addition: only the pre-S-4 ON factories and S-4)", ON_SWITCH_FILES, files(switchesOn, tests))

        // β r2 §8.1: inspect installation/return and every actual caller (including future budget rigs),
        // rather than allowing a helper name in place of checking its checker lifecycle.
        fun betaViolations(map: Map<String, String>): Set<String> {
            val broken = mutableSetOf<String>()
            val factory = map[BETA_FACTORY].orEmpty()
            val constructsCheck = Regex("""\bval\s+check\s*=\s*KrxOnPublicationCheck\s*\(\s*\)""")
            val installsCheck = Regex("""\bcoordinator\.onAccessPublishedForTest\s*=\s*check\b""")
            val returnsBoth = Regex("""\breturn\s+KrxBetaPac\s*\(\s*coordinator\s*,\s*check\s*\)""")
            if (!constructsCheck.containsMatchIn(factory) || !installsCheck.containsMatchIn(factory) ||
                !returnsBoth.containsMatchIn(factory) || callSpans(factory, "PremiumAccessCoordinator").size != 1 ||
                !Regex("""\bkrxContest\s*=\s*KrxContestSwitch\s*\(\s*true\s*\)""").containsMatchIn(factory)) {
                broken += BETA_FACTORY
            }
            for ((key, text) in map) {
                if (key == BETA_FACTORY) continue
                val calls = callSpans(withoutImports(text), "krxBetaPac")
                if (calls.isEmpty()) continue
                // Each factory-owning rig audits its returned checker at teardown, even on a failing body.
                val finalBodies = Regex("""\bfinally\s*\{""").findAll(text).map { head ->
                    var depth = 1
                    var end = head.range.last + 1
                    while (end < text.length && depth > 0) {
                        if (text[end] == '{') depth++
                        if (text[end] == '}') depth--
                        end++
                    }
                    if (depth == 0) text.substring(head.range.last + 1, end - 1) else ""
                }.toList()
                val endChecks = finalBodies.sumOf { body -> Regex("""\.check\.assertHeld\s*\(""").findAll(body).count() }
                if (calls.any { it == null } || endChecks < calls.size || direct.containsMatchIn(text)) broken += key
            }
            val wrappers = files(Regex("""\bKrxBetaPac\s*\("""), map)
            broken += wrappers - BETA_FACTORY
            return broken
        }
        assertTrue("KW15 β positive control: real factory installs and returns its check", BETA_FACTORY in tests)
        val betaCallers = tests.filter { (key, text) -> key != BETA_FACTORY && callSpans(text, "krxBetaPac").isNotEmpty() }.keys
        assertTrue("KW15 β positive control: P and probe rigs are scanned", betaCallers.containsAll(setOf(
            "${T}data/entitlements/PremiumAccessKrxOnConsumerContractTest.kt",
            "${T}data/entitlements/KrxBetaSameEpochHoldProbeTest.kt")))
        assertEquals("KW15 β factory installation/return and all caller teardowns/hooks", emptySet<String>(), betaViolations(tests))
        val factoryText = tests.getValue(BETA_FACTORY)
        val probeKey = "${T}data/entitlements/KrxBetaSameEpochHoldProbeTest.kt"
        val probeText = tests.getValue(probeKey)
        assertEquals("KW15 β negative: checker installation removed", setOf(BETA_FACTORY),
            betaViolations(tests + (BETA_FACTORY to factoryText.replace("coordinator.onAccessPublishedForTest = check", ""))))
        assertEquals("KW15 β negative: returned checker replaced", setOf(BETA_FACTORY),
            betaViolations(tests + (BETA_FACTORY to factoryText.replace("return KrxBetaPac(coordinator, check)", "return KrxBetaPac(coordinator, KrxOnPublicationCheck())"))))
        assertEquals("KW15 β negative: teardown assertion removed", setOf(probeKey),
            betaViolations(tests + (probeKey to probeText.replace("pac.check.assertHeld()", "Unit"))))
        val fakeBeta = "${T}FakeBetaCheckOutsideFinally.kt" to
            "fun fake() { val pac = krxBetaPac(); try { Unit } finally { Unit }; pac.check.assertHeld() }"
        assertEquals("KW15 β negative: an end assertion outside finally is not teardown", setOf(fakeBeta.first),
            betaViolations(tests + fakeBeta))
        assertEquals("KW15 β negative: observation replaces the checker", setOf(probeKey),
            betaViolations(tests + (probeKey to (probeText + "\nfun broken() { c.onAccessPublishedForTest = {} }"))))

        val fake1 = "${T}FakeUncheckedOnTest.kt" to "fun a() = runTest { val h = krxHarness(job, CHECKED_ON) }"
        val fake2 = "${T}FakeAssigningOnTest.kt" to "fun b() = checkedOnTest { h -> h.coordinator.onAccessPublishedForTest = {} }"
        val fake3 = "${T}FakeOwnSwitchOnTest.kt" to "val ON = KrxContestSwitch(true)"
        val fakeNested = "${T}FakeNestedOnTest.kt" to "fun c() = checkedOnTest { h -> val h2 = krxHarness(job, CHECKED_ON) }"
        val fakeImported = "${T}FakeImportedOnTest.kt" to
            "import com.jay.fxi.data.entitlements.CHECKED_ON\nfun d() = runTest { val h = krxHarness(job, CHECKED_ON); h.publicationCheck.assertHeld() }"
        assertEquals("KW15 self-check (a)", setOf(fake1.first), unchecked(tests + fake1))
        assertEquals("KW15 self-check (a): a CHECKED_ON PAC built inside checkedOnTest without its own assertHeld is caught; an import line is not counted",
            setOf(fakeNested.first), unchecked(tests + fakeNested + fakeImported))
        assertEquals("KW15 self-check (b)", setOf(fake2.first), assigning(tests + fake2))
        assertEquals("KW15 self-check (c)", ON_SWITCH_FILES + fake3.first, files(switchesOn, tests + fake3))
    }

    /**
     * KW16 (design r5 §13 T.8 KW16, §5A.3 D-4·D-9, draft EF-06): the effective capability E is used only by the KRX use in
     * SnapshotTopicUseAuthority.kt — never by the gate (off, its HEAD admits KRX on capabilityAllowed) or the session — and SUA, the
     * gate and TSC read no PAC `krx` flow (no `KrxCapabilityState`, no PremiumAccessCoordinator). E is named as in the α stub
     * (`krxEffective`; the design calls it E). Each KRX-use body decides with E: the judgement `TopicAccessSnapshot.admitsKrxUse` and
     * the issue `TopicAccessSnapshot.krxUseFor` call `krxEffective(`, and `TopicAccessSnapshot.acquireKrxUse` calls `krxEffective(` or
     * `krxUseFor(` (bodies cut at the next top-level declaration, [topLevelBody]; the function names are the α stub's). Catches: the
     * gate using E (off, graph KRX lost), EF-D (the send read reading the flow), A-X on one half (the issue uses E while the judgement
     * drops it, or the reverse), and — on the stub — the KRX use not yet deciding with E. Self-checks run first.
     */
    @Test fun KW16_theEffectiveCapabilityIsUsedOnlyByTheKrxUse() {
        val effective = Regex("""\bkrxEffective\b""")
        val eCall = Regex("""\bkrxEffective\s*\(""")
        val judgeHead = Regex("""\bfun\s+TopicAccessSnapshot\.admitsKrxUse\s*\(""")
        val issueHead = Regex("""\bfun\s+TopicAccessSnapshot\.krxUseFor\s*\(""")
        val acquireHead = Regex("""\bfun\s+TopicAccessSnapshot\.acquireKrxUse\s*\(""")

        // Self-checks (run on the stub): E in the gate is caught; a body is cut before the next top-level declaration.
        val gateWithE = code + (GATE to code.getValue(GATE) + "\nprivate fun leak(s: TopicAccessSnapshot) = s.krxEffective()\n")
        assertEquals("KW16 self-check: E used in the gate is caught", setOf(SUA, GATE), files(effective, gateWithE))
        val synthetic = "internal fun TopicAccessSnapshot.admitsKrxUse(use: KrxUseLifetime): Boolean =\n    admitsUse(use.lifetime) && facts.capabilityAllowed\n" +
            "\ninternal fun TopicAccessSnapshot.krxEffective(): Boolean = krxConsumerGuard(krxPermitSeq) && facts.capabilityAllowed\n"
        val cut = checkNotNull(topLevelBody(synthetic, judgeHead)) { "KW16 self-check: the synthetic judgement is found" }
        assertTrue("KW16 self-check: a judgement without E is caught (the next declaration is not read into its body)", !eCall.containsMatchIn(cut))
        assertTrue("KW16 self-check: a judgement with E is accepted",
            eCall.containsMatchIn(checkNotNull(topLevelBody(synthetic.replaceFirst("&& facts.capabilityAllowed\n", "&& krxEffective()\n"), judgeHead)) {
                "KW16 self-check: the mutated judgement is found"
            }))

        assertTrue("KW16 positive control: SUA declares krxEffective", Regex("""\bfun\s+([\w.]+\.)?krxEffective\s*\(""").containsMatchIn(code.getValue(SUA)))
        assertTrue("KW16 positive control: KrxCapabilityState is found in PAC", Regex("""\bKrxCapabilityState\b""").containsMatchIn(code.getValue(PAC)))
        assertEquals("KW16 krxEffective files (not the gate, not the session)", setOf(SUA), files(effective))
        for (key in listOf(SUA, GATE, TSC)) {
            assertEquals("KW16 $key names no KrxCapabilityState (PAC krx flow)", 0, Regex("""\bKrxCapabilityState\b""").findAll(code.getValue(key)).count())
            assertEquals("KW16 $key names no PremiumAccessCoordinator", 0, Regex("""\bPremiumAccessCoordinator\b""").findAll(code.getValue(key)).count())
        }
        val sua = code.getValue(SUA)
        val judge = checkNotNull(topLevelBody(sua, judgeHead)) { "KW16 premise: SUA declares TopicAccessSnapshot.admitsKrxUse" }
        val issue = checkNotNull(topLevelBody(sua, issueHead)) { "KW16 premise: SUA declares TopicAccessSnapshot.krxUseFor" }
        val acquire = checkNotNull(topLevelBody(sua, acquireHead)) { "KW16 premise: SUA declares TopicAccessSnapshot.acquireKrxUse" }
        assertTrue("KW16 admitsKrxUse decides with krxEffective (body: `${normalized(judge)}`)", eCall.containsMatchIn(judge))
        assertTrue("KW16 the KRX issue (krxUseFor) decides with krxEffective (body: `${normalized(issue)}`)", eCall.containsMatchIn(issue))
        assertTrue("KW16 the KRX issue (acquireKrxUse) decides with krxEffective or krxUseFor (body: `${normalized(acquire)}`)",
            eCall.containsMatchIn(acquire) || Regex("""\bkrxUseFor\s*\(""").containsMatchIn(acquire))
    }

    /**
     * KW17 (design r5 §13 T.8 KW17, §5B.4 D-1, draft RB-08): the transport, the bootstrap service and the outcome name no
     * KrxSendMarker; TSC's `bootstrap` parameter type (TSC:389 at de50885) and the service's `bootstrap` signature are HEAD's; the
     * bootstrap's useAdmitted lambda is HEAD's `{ authority.admits(lifetime) }` (TSC:2754 at de50885). Catches: (b)/(b′)/(c) capture
     * wiring (a marker into the transport or the public seam), U-X (KRX judged inside useAdmitted).
     */
    @Test fun KW17_transportCarriesNoMarkerAndTheBootstrapSeamIsHeads() {
        assertTrue("KW17 positive control: KrxSendMarker is declared in its file", Regex("""\bKrxSendMarker\b""").containsMatchIn(code.getValue(MARKER)))
        for (key in listOf(TRANSPORT, BOOTSTRAP_SERVICE, OUTCOME)) {
            assertTrue("premise: KW17 the code map has $key", key in code)
            assertEquals("KW17 KrxSendMarker in $key", 0, Regex("""\bKrxSendMarker\b""").findAll(code.getValue(key)).count())
        }
        val tsc = normalized(code.getValue(TSC))
        val headType = "bootstrap: suspend (owner: AuthIdentityFence, topic: String, useAdmitted: () -> Boolean) -> TopicSnapshotOutcome,"
        val declared = Regex("""\bbootstrap\s*:""").findAll(tsc).count()
        assertTrue("premise: KW17 TSC declares the bootstrap parameter", declared >= 1)
        assertEquals("KW17 every TSC bootstrap parameter has HEAD's type (TSC:389)", declared, Regex(Regex.escape(headType)).findAll(tsc).count())
        val service = normalized(code.getValue(BOOTSTRAP_SERVICE))
        val headService = "suspend fun bootstrap(owner: AuthIdentityFence, topic: String, useAdmitted: (() -> Boolean)? = null): TopicSnapshotOutcome"
        assertEquals("KW17 the service's bootstrap signature is HEAD's", 1, Regex(Regex.escape(headService)).findAll(service).count())
        val tscRaw = code.getValue(TSC)
        val krxBody = KrxSessionSourceContract.function(tscRaw, "startKrxBootstrap")
        val generalRaw = tscRaw.replace(krxBody, "")
        assertEquals("premise: KW17 the session calls bootstrap(owner, topic) once", 1, Regex("""\bbootstrap\s*\(\s*owner\s*,\s*topic\s*\)""").findAll(generalRaw).count())
        val useAdmitted = Regex("""\bbootstrap\s*\(\s*owner\s*,\s*topic\s*\)\s*\{\s*authority\.admits\s*\(\s*lifetime\s*\)\s*\}""")
        assertEquals("KW17 the useAdmitted lambda is HEAD's `{ authority.admits(lifetime) }`", 1, useAdmitted.findAll(generalRaw).count())

        assertEquals("contract(KW17): one additional protected bootstrap caller, only inside the KRX ladder", 1,
            Regex("""\bbootstrap\s*[(]""").findAll(krxBody).count())
        assertTrue("contract(KW17): KRX ladder callback validates the original captured KRX use", krxBody.contains("admitsKrx"))

        // Self-checks: a marker in the transport, a widened public bootstrap type, KRX judged inside useAdmitted (U-X).
        val transportLeak = code.getValue(TRANSPORT) + "\nprivate val leak: KrxSendMarker? = null\n"
        assertEquals("KW17 self-check: a marker in the transport is caught", 1, Regex("""\bKrxSendMarker\b""").findAll(transportLeak).count())
        val widened = normalized(code.getValue(TSC).replaceFirst("useAdmitted: () -> Boolean)", "useAdmitted: () -> Boolean, marker: KrxSendMarker?)"))
        assertTrue("KW17 self-check: the type mutation applied", widened != tsc)
        assertTrue("KW17 self-check: a widened bootstrap type is caught",
            Regex(Regex.escape(headType)).findAll(widened).count() < Regex("""\bbootstrap\s*:""").findAll(widened).count())
        val judgedInside = tscRaw.replace(Regex("""(\bbootstrap\s*\(\s*owner\s*,\s*topic\s*\)\s*\{\s*authority\.admits\s*\(\s*lifetime\s*\))"""), "\$1 && krxAdmitted()")
        assertTrue("KW17 self-check: the U-X mutation applied", judgedInside != tscRaw)
        assertEquals("KW17 self-check: KRX judged inside useAdmitted (U-X) is caught", 0, useAdmitted.findAll(judgedInside).count())
    }

    /**
     * KW18 (design r5 §6.4 (ii), OD-B09 (a)): `supportedTopics` appears in production only in TopicSnapshotOutcome.kt (its
     * declaration is the positive control). Catches: the supported-topic list read as a condition (OD-B09 (b)) or anywhere it could leak.
     */
    @Test fun KW18_theSupportedTopicListIsReadNowhere() {
        val list = Regex("""\bsupportedTopics\b""")
        assertTrue("KW18 positive control: the declaration", Regex("""\bval\s+supportedTopics\b""").containsMatchIn(code.getValue(OUTCOME)))
        assertEquals("KW18 supportedTopics files in production", setOf(OUTCOME), files(list))
        val mutated = code + (TSC to code.getValue(TSC) + "\nprivate fun leak(o: TopicSnapshotOutcome.Unsupported) = o.supportedTopics\n")
        assertEquals("KW18 self-check: a read in TSC is caught", setOf(OUTCOME, TSC), files(list, mutated))
    }

    /**
     * KW19 (design r5 §6.4 (iii), H-6): TSC makes no log call and interpolates no outcome (`$outcome`, `${outcome}`,
     * `${input.outcome}`). The log pattern is the design's `\bLog\.|Timber|logger|println` as substrings, widened to `[lL]ogger`, so a
     * `sessionLogger.info(` or a `Logger` type is caught too. Catches: the supported-topic list (entitlement information) reaching a log.
     */
    @Test fun KW19_theSessionLogsNoOutcome() {
        val log = Regex("""\bLog\.|Timber|[lL]ogger|println""")
        val interpolation = Regex("""\${'$'}outcome\b|\${'$'}\{\s*(input\.)?outcome\s*\}""")
        assertTrue("KW19 positive control: the log pattern finds ProcessGraphBuilder's Log.w", log.containsMatchIn(code.getValue(PROCESS_GRAPH)))
        assertEquals("KW19 log calls in TSC", 0, log.findAll(code.getValue(TSC)).count())
        assertEquals("KW19 outcome interpolations in TSC", 0, interpolation.findAll(code.getValue(TSC)).count())
        val mutated = code.getValue(TSC) + "\nprivate fun leak(input: Any, outcome: Any) = \"\$outcome \${input.outcome}\"\n"
        assertEquals("KW19 self-check: both interpolations are caught", 2, interpolation.findAll(mutated).count())
        val logged = code.getValue(TSC) + "\nprivate val sessionLogger = java.util.logging.Logger.getLogger(\"t\")\nprivate fun w() = sessionLogger.info(\"x\")\n"
        assertTrue("KW19 self-check: a sessionLogger call is caught", log.findAll(logged).count() >= 2)
    }

    /**
     * KW20 (design r5 §3 D-6, D-27, deferred-items 14.9): `GraphV2AccessCapture(` is constructed once in production (the gate's bind,
     * GraphV2AccessGate.kt:40 at de50885); outside data/entitlements the only `copy(` naming a capture field is the capability
     * retirement (GV2RC:720 at de50885); no production `copy(` names `capabilityInvalidations`. Catches D-6 (a): an exposure copying a
     * slot's count onto a freshly bound capture.
     */
    @Test fun KW20_theCaptureIsBuiltOnceAndNeverCopiedWithAnotherCount() {
        for (key in listOf(GATE, GV2RC)) assertTrue("premise: KW20 the code map has $key", key in code)
        assertEquals("KW20 GraphV2AccessCapture( constructions in production (declaration excluded)", mapOf(GATE to 1), callCounts("GraphV2AccessCapture"))
        val captureField = Regex("""\b(krxCapabilityEpoch|capabilityInvalidations)\s*=(?!=)""")
        val all = spanViolations(code, "copy", captureField)
        assertTrue("KW20 positive control: the same scan finds the record-fence copies in data/entitlements",
            all.keys.any { it.startsWith("${M}data/entitlements/") })
        assertEquals("KW20 capture-field copies outside data/entitlements", mapOf(GV2RC to 1), all.filterKeys { !it.startsWith("${M}data/entitlements/") })
        val count = Regex("""\bcapabilityInvalidations\s*=(?!=)""")
        assertEquals("KW20 production copy( spans naming capabilityInvalidations", emptyMap<String, Int>(), spanViolations(code, "copy", count))
        val mutated = code + (GV2RC to code.getValue(GV2RC) + "\nprivate fun leak(c: GraphV2AccessCapture, n: Long) = c.copy(capabilityInvalidations = n)\n")
        assertEquals("KW20 self-check: an exposure copy of the count is caught", mapOf(GV2RC to 1), spanViolations(mutated, "copy", count))
    }

    /**
     * KW21 (design r5 §6.7 D-5, H-4): no production `TopicSessionCoordinator(` call passes `desired =`; Z07 (KrxS6DormancyContractTest)
     * stays. Only the parenthesised argument spans of the calls are scanned (declarations excluded): the internal constructor's own
     * `desired = desired` pass-through inside TSC is not a call and does not count (it is locked by KW22). Catches: a runtime desired
     * injection seam.
     */
    @Test fun KW21_productionPassesNoDesiredToTheSession() {
        val desired = Regex("""\bdesired\s*=(?!=)""")
        val calls = callCounts("TopicSessionCoordinator")
        assertEquals("KW21 positive control: the span scan finds the one production session call (TopicRuntime.kt)", mapOf(RUNTIME to 1), calls)
        assertTrue("KW21 positive control: a whole-file scan would hit TSC's pass-through `desired = desired`, which the span scan skips",
            desired.containsMatchIn(code.getValue(TSC)))
        assertEquals("KW21 session calls passing desired", emptyMap<String, Int>(), spanViolations(code, "TopicSessionCoordinator", desired))
        val injected = code.getValue(RUNTIME).replaceFirst(Regex("""\bTopicSessionCoordinator\s*\("""), "TopicSessionCoordinator(desired = setOf(TopicCatalogue.KRX_FUTURES), ")
        assertEquals("KW21 self-check: a desired argument is caught", mapOf(RUNTIME to 1),
            spanViolations(code + (RUNTIME to injected), "TopicSessionCoordinator", desired))
    }

    /**
     * KW22 (added by review, design r5 §3 OD-B06 (a), D-5, D-8): TSC's internal constructor has the public primary constructor's
     * parameters — same names, order, types and default text — plus the original two, `krxSendReader: KrxSendReadAuthority?` (nullable
     * and required: no default — E-35, Codex tests r2) and `onRejectedWithMarker` with the D-8 signature and `= null`; and its `: this(`
     * delegation passes every public parameter through by its own name. Catches: a default or type drifting between the runtime's and
     * the tests' constructor, a constant (e.g. a KRX desired) substituted in the delegation, which KW21's call scan does not see, and a
     * default given to the read seam (design r6 §3: because the reader is required, a call that names only public parameters always
     * resolves to the public constructor; a null reader is passed by name (design r6 §3; r7 §3, E-35)). Self-checks apply each mutation
     * to a copy of TSC's text first: the drifted default, the constant desired, and the reader parameter in three shapes (nullable
     * without a default accepted; non-null — the pre-E-35 stub r2 shape — refused; nullable with `= null` refused).
     * α-1c explicitly adds only krxRetirer: KrxSubscriptionRetirer = KrxSubscriptionRetirer.NONE to the internal constructor.
     * S6-3a adds krxMemorySnapshot: (() -> TopicAccessSnapshot)? = null immediately after krxRetirer; no other addition is allowed.
     * Method name stays stable for the existing inventory. The public constructor and its delegation remain unchanged.
     */
    @Test fun KW22_theInternalSessionConstructorIsThePublicOnePlusTwo() {
        data class Constructors(val primary: List<Parameter>, val internal: List<Parameter>, val passed: List<Pair<String, String>>)
        val internalHeadPattern = Regex("""\binternal\s+constructor\s*\(""")
        fun parse(tsc: String): Constructors {
            val primaryHead = checkNotNull(Regex("""\bclass\s+TopicSessionCoordinator\s*\(""").find(tsc)) { "premise: KW22 TSC declares its primary constructor" }
            val primary = splitTopLevel(checkNotNull(argumentSpan(tsc, primaryHead.range.last)) { "KW22 the primary constructor does not close" })
                .map(::parameter)
            val internalHead = internalHeadPattern.findAll(tsc).toList()
            assertEquals("premise: KW22 TSC has one internal constructor", 1, internalHead.size)
            val internalSpan = checkNotNull(argumentSpan(tsc, internalHead.single().range.last)) { "KW22 the internal constructor does not close" }
            val internal = splitTopLevel(internalSpan).map(::parameter)
            val afterInternal = tsc.substring(internalHead.single().range.last + internalSpan.length + 2)
            val delegation = checkNotNull(Regex("""^\s*:\s*this\s*\(""").find(afterInternal)) {
                "premise: KW22 the internal constructor delegates with `: this(`"
            }
            val passed = splitTopLevel(checkNotNull(argumentSpan(afterInternal, delegation.range.last)) { "KW22 the delegation does not close" })
                .map { argument ->
                    val eq = topLevelAssignment(argument)
                    check(eq > 0) { "KW22 positional delegation argument: $argument" }
                    normalized(argument.substring(0, eq)) to normalized(argument.substring(eq + 1))
                }
            return Constructors(primary, internal, passed)
        }
        val retirerName = "krxRetirer"
        val snapshotName = "krxMemorySnapshot"
        val addedNames = setOf(READER_PARAMETER, MARKER_CHANNEL, retirerName, snapshotName)
        fun publicPart(internal: List<Parameter>) = internal.filter { it.name !in addedNames }
        fun afterInternalHead(tsc: String, old: String, new: String): String {
            val at = checkNotNull(internalHeadPattern.find(tsc)) { "KW22 self-check: no internal constructor" }.range.first
            return tsc.substring(0, at) + tsc.substring(at).replaceFirst(old, new)
        }

        val tsc = code.getValue(TSC)
        // Self-checks: a default drifting in the internal constructor, a constant desired substituted in its delegation.
        val drifted = afterInternalHead(tsc, "TopicBootstrapOrder.ISSUE_GAP", "Duration.ZERO")
        assertTrue("KW22 self-check: the default mutation applied", drifted != tsc)
        parse(drifted).let { assertTrue("KW22 self-check: a drifted default is caught", it.primary != publicPart(it.internal)) }
        val substituted = afterInternalHead(tsc, "desired = desired", "desired = setOf(TopicCatalogue.KRX_FUTURES)")
        assertTrue("KW22 self-check: the delegation mutation applied", substituted != tsc)
        parse(substituted).let { assertTrue("KW22 self-check: a constant desired in the delegation is caught", it.primary.map { p -> p.name to p.name } != it.passed) }
        // Self-checks (E-35): the reader parameter rewritten in a copy of TSC's internal constructor, judged by the contract's value.
        val readerExpected = Parameter(READER_PARAMETER, "KrxSendReadAuthority?", null)
        val readerDeclaration = Regex("""\b$READER_PARAMETER\s*:\s*[\w.]*KrxSendReadAuthority\b\??(?:\s*=\s*null\b)?""")
        fun withReader(tsc: String, declaration: String): String {
            val at = checkNotNull(internalHeadPattern.find(tsc)) { "KW22 self-check: no internal constructor" }.range.first
            val rest = tsc.substring(at)
            checkNotNull(readerDeclaration.find(rest)) { "KW22 self-check: no `$READER_PARAMETER: KrxSendReadAuthority` parameter after the internal constructor head" }
            return tsc.substring(0, at) + readerDeclaration.replaceFirst(rest, declaration)
        }
        for ((shape, declaration, accepted) in listOf(
            Triple("nullable without a default (E-35)", "$READER_PARAMETER: KrxSendReadAuthority?", true),
            Triple("non-null (stub r2, before E-35)", "$READER_PARAMETER: KrxSendReadAuthority", false),
            Triple("nullable with `= null` (a default on the read seam)", "$READER_PARAMETER: KrxSendReadAuthority? = null", false)
        )) {
            val reader = parse(withReader(tsc, declaration)).internal.filter { it.name == READER_PARAMETER }
            assertEquals("KW22 self-check: the $shape copy parses as exactly that one reader parameter", listOf(parameter(declaration)), reader)
            assertEquals("KW22 self-check: the $shape reader parameter is ${if (accepted) "accepted" else "refused"}", accepted, reader.single() == readerExpected)
        }

        val retirerDeclaration = "krxRetirer: KrxSubscriptionRetirer = KrxSubscriptionRetirer.NONE"
        val retirerExpected = Parameter(retirerName, "KrxSubscriptionRetirer", "KrxSubscriptionRetirer.NONE")
        for ((declaration, accepted) in listOf(
            retirerDeclaration to true,
            "krxRetirer: KrxSubscriptionRetirer" to false,
            "krxRetirer: KrxSubscriptionRetirer? = null" to false,
            "krxRetirer: KrxSubscriptionRetirer = other" to false
        )) {
            val mutant = afterInternalHead(tsc, retirerDeclaration, declaration)
            val actual = parse(mutant).internal.single { it.name == retirerName }
            assertEquals("premise: KW22 exact retirer parameter mutation parsed", parameter(declaration), actual)
            assertEquals("contract(KW22): only the non-null NONE-default retirer dependency is allowed", accepted, actual == retirerExpected)
        }

        val snapshotDeclaration = "krxMemorySnapshot: (() -> TopicAccessSnapshot)? = null"
        val snapshotExpected = Parameter(snapshotName, normalized("(() -> TopicAccessSnapshot)?"), "null")
        val expectedAdded = listOf(
            readerExpected,
            Parameter(MARKER_CHANNEL, normalized("((TopicSessionFence, Map<String, TopicRejectionReason>, KrxSendMarker?) -> Unit)?"), "null"),
            retirerExpected,
            snapshotExpected
        )
        fun exactInternal(constructors: Constructors): Boolean = constructors.internal == constructors.primary + expectedAdded
        for ((declaration, accepted) in listOf(
            snapshotDeclaration to true,
            "krxMemorySnapshot: (() -> TopicAccessSnapshot)?" to false,
            "krxMemorySnapshot: () -> TopicAccessSnapshot" to false,
            "krxMemorySnapshot: (() -> TopicAccessSnapshot?)? = null" to false,
            "krxMemorySnapshot: (() -> TopicAccessSnapshot)? = { TopicAccessSnapshot.INITIAL }" to false
        )) {
            val mutant = afterInternalHead(tsc, snapshotDeclaration, declaration)
            val parsed = parse(mutant)
            assertEquals("contract(KW22): exact snapshot parameter mutation parsed", parameter(declaration),
                parsed.internal.single { it.name == snapshotName })
            assertEquals("contract(KW22): only the nullable read-only snapshot supplier with null default is allowed",
                accepted, exactInternal(parsed))
        }
        val swapped = afterInternalHead(afterInternalHead(afterInternalHead(tsc,
            retirerDeclaration, "__retirer_parameter__"), snapshotDeclaration, retirerDeclaration),
            "__retirer_parameter__", snapshotDeclaration)
        assertTrue("contract(KW22): the snapshot/retirer order mutation applied", swapped != tsc)
        assertTrue("contract(KW22): the snapshot supplier must immediately follow retirer", !exactInternal(parse(swapped)))
        val extra = afterInternalHead(tsc, snapshotDeclaration, "$snapshotDeclaration, extraSnapshot: (() -> TopicAccessSnapshot)? = null")
        assertTrue("contract(KW22): the extra-parameter mutation applied", extra != tsc)
        assertTrue("contract(KW22): an additional constructor parameter remains forbidden", !exactInternal(parse(extra)))

        val (primary, internal, passed) = parse(tsc)
        assertTrue("premise: KW22 the primary constructor's parameters are parsed (${primary.size})", primary.size >= 20 && primary.any { it.name == "desired" })
        val added = internal.filter { it.name in addedNames }
        assertEquals("contract(KW22): internal retains all public parameter names, order, types and defaults", primary, publicPart(internal))
        assertEquals("contract(KW22): original reader/channel/retirer plus the exact memory snapshot supplier", expectedAdded, added)
        assertEquals("contract(KW22): exact full constructor inventory places snapshot immediately after retirer", primary + expectedAdded, internal)
        assertEquals("KW22 the delegation passes every public parameter through by its own name",
            primary.map { it.name to it.name }, passed)
    }
}
