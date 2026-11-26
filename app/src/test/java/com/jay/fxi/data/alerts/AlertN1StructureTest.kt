package com.jay.fxi.data.alerts

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the N1 structure locks — API r1 §9's AlertN1StructureTest row as
 * r2/r3 §7 re-assign it (N1-Q, N1-S with r3's S3 text and Codex API r1's S1 text) — and the structure half of N1-A and N1-P.
 * S95_N03 also reads the two JVM entry mirrors directly and compares them to the service and Activity.
 * The production inventory is read from the comment-free code and strings of the six production roots
 * ([S6Krx3bOffWiringContractTest.ROOTS]): Kotlin through [PostS6ApprovedDelta.codeAndStrings], Java through
 * `javaCodeAndStrings` below (no root holds Java today; one that appears is scanned the same way, with Java's own rules:
 * Unicode escapes decoded before lexing, block comments not nesting). A reference cannot hide in a comment, a string or an
 * escape.
 *
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): API r2 §5's last row and Codex API r1 R5/R6 narrow S1 and widen
 * A-reach by exactly the new section holder `ui/alert/BankAlertSection.kt`; that holder needs an entry point, so it is not one
 * of the N1 NEW_FILES (its own surfaces are BankAlertSectionStructureTest's). S11 is unchanged. The implementation thread
 * reads but does not edit this file.
 *
 * - S1: the frozen AlertViewModel is named only by its own file (E5a1: AlertSection no longer imports it and names no
 *   MAX_COUNT); AlertSection is declared once and called directly once, from the section holder, with no other reference;
 *   AlertAddSheet likewise (S7 E5b3b) is declared once and called directly once, from the holder, with no other reference;
 *   the eight NEW_FILES reach no ViewModel, reflection, class loader or screen entry API.
 * - S2: ProcessLifecycleOwner is named by the topic owner and the registry only; the registry adds one observer, in start,
 *   on a lifecycle it reads lazily; no other new file observes a lifecycle.
 * - S3 / N1-P: S8-6 returns from the source branch before the bank path's one emit. The bank path calls `deliverPush`
 *   immediately after that emit, with the app-owned gate and raw type, setting_id and is_repeat (S7 E5c). It resolves the
 *   registry only with the gate open, for an exact `sync_alerts` (onSyncAlerts) or `rate_alert` (onBankPush), inside
 *   an Exception boundary that only reports. After the S8-6 source check, MainActivity's OFF parser calls `deliverTap` once, gated and lazy,
 *   with the raw is_repeat, and it resolves only for a bank SettingTriggered or a RefreshNeeded; `onBankPush` is the adapter's
 *   default, the registry's main hand-over and the bank owner's override. Nothing else calls, references or aliases these or
 *   `onSyncAlerts`; the default loggers write a fixed message, never the exception or a payload.
 * - S4 / N1-A: the registry starts only from FXiApplication's `startAlertFamilies`, which onCreate passes past its admission
 *   gate and the start helper runs once between the rate migration and the purge. The scan fixes which files may name each
 *   owner, the access and floor singletons, the row policy and the registry; inside the registry (S8-6) the access is named
 *   only in the source use binding and the row policy only in the exchange admission guard. Each owner provider is resolved
 *   once, in a supplier.
 * - S5 / S6 / N1-Q: the eight files in NEW_FILES name no KRX, read no snapshot authority, no alert bus, no permission prompt, no push
 *   token owner and declare no binding.
 * - S11 (gap battery r1): the five production `@Inject` delegations pass exactly the agreed suppliers, the owners' production
 *   recheck periods are their 1 s defaults, and S8-6 keeps the bank banner guard alongside the source callback. S8-5a: the source owner's three writes go
 *   through the three agreed production delegation functions, each passing its request's own use check.
 *
 * The suite checks real sources and exercises selected locks on mutated trees and comment or lookalike controls.
 * Outside this suite are
 * deliverPush's type × gate runs and the registry's runtime counts (AlertFamilyRefresherRegistryContractTest), the seven
 * starts' order and the closed-gate lambdas (FXiApplicationStartTest), the device paths, the ON service → registry → owner
 * integration that no allowed harness observes (left unverified, API r3 §7 N1-P), and N1-Q's existing KRX OFF/ON
 * pending-tap regression bundle (an exact-state full-suite run, not inherited). The implementation thread reads but does not
 * edit this file.
 */
class AlertN1StructureTest {

    @Test fun `S7N1_S00 the scan reads every production root comment-free in Kotlin and Java`() {
        val roots = S6Krx3bOffWiringContractTest.ROOTS
        assertTrue("premise(N1-S): the six production roots exist", roots.size == 6 && roots.all { File(it).isDirectory })
        assertTrue("premise(N1-S): the scan sees the production sources (${code.size})", code.size > 300)
        for (path in NEW_FILES + listOf(VM, SECTION, SHEET, HOLDER, ROUTE, TOPIC_OWNER, APP, FCM, KRX_SAMPLE)) {
            assertTrue("premise(N1-S): $path is scanned", path in code)
        }
        val kotlin = strip("X.kt", "// AlertViewModel\n/* a /* AlertViewModel */ still a comment */ val s = \"Alert\\u0056iewModel\"")
        assertEquals("N1-S Kotlin: comments go, nested ones included; the escaped literal stays, decoded", 1, count(kotlin, NAME_VM))
        assertTrue("N1-S Kotlin: the literal is kept as a literal", kotlin.contains("\"AlertViewModel\""))
        val java = strip("X.java", "class X { // AlertViewModel\n /* AlertViewModel */ String s = \"AlertViewModel\"; }")
        assertEquals("N1-S Java: line and block comments go, the string stays", 1, count(java, NAME_VM))
        assertEquals("N1-S Java: a Unicode escape is decoded in code, before lexing", "AlertViewModel vm;", strip("X.java", "Alert\\u0056iewModel vm;"))
        assertEquals("N1-S Java: so is one with several u", "AlertViewModel vm;", strip("X.java", "Alert\\uuu0056iewModel vm;"))
        assertEquals("N1-S Java: an escaped backslash starts no escape", "String s = \"\\\\u0041\";", strip("X.java", "String s = \"\\\\u0041\";"))
        assertEquals("N1-S Java: block comments do not nest", 1, count(strip("X.java", "/* a /* b */ AlertViewModel vm; // */"), NAME_VM))
        assertEquals("N1-S Kotlin: while the same text is all comment in Kotlin", 0, count(strip("X.kt", "/* a /* b */ AlertViewModel vm; // */"), NAME_VM))
        assertEquals("N1-S Java: a quote in a char literal opens no string", 0,
            count(strip("X.java", "char q = '\"'; // AlertViewModel\nString t = \"x\";"), NAME_VM))
        assertEquals("N1-S Java: a text block is a literal", 1, count(strip("X.java", "String t = \"\"\"\n  AlertViewModel\n  \"\"\";"), NAME_VM))
    }

    @Test fun `S7E5A1_N1S01 AlertViewModel is named only by its own file and AlertSection names no MAX_COUNT`() {
        assertEquals("premise(E5a1 S1): the frozen VM declares its class", 1, count(code.getValue(VM), """\bclass\s+AlertViewModel\b"""))
        assertTrue("premise(E5a1 S1): the MAX_COUNT pattern finds the VM's own constant", count(code.getValue(VM), """\bMAX_COUNT\b""") >= 1)
        assertEquals("premise(E5a1 S1): AlertSection is scanned and declares its Composable", 1,
            count(code.getValue(SECTION), """\bfun\s+AlertSection\s*\("""))
        assertEquals("E5a1 S1: no production root but the VM's own file names, constructs or reflects AlertViewModel, and AlertSection names no MAX_COUNT",
            emptyList<String>(), s1ViewModel(code))
    }

    // S7 E5b3b (API r2 D8): replaces "…and AlertAddSheet by nobody"; the holder now opens the one sheet.
    @Test fun `S7E5A1_N1S02 AlertSection and AlertAddSheet are each declared once and called directly once by the section holder`() {
        assertEquals("premise(E5a1 S1): the positive control finds AlertSection's declaration", mapOf(SECTION to 1),
            occurrences(code, """\bfun\s+AlertSection\s*\("""))
        assertEquals("premise(E5a1 S1): and AlertAddSheet's", mapOf(SHEET to 1), occurrences(code, """\bfun\s+AlertAddSheet\s*\("""))
        assertTrue("premise(E5a1 S1): the call pattern finds the holder's direct call", count(code.getValue(HOLDER), """\bAlertSection\s*[({]""") >= 1)
        assertEquals("E5a1/E5b3b S1: AlertSection and AlertAddSheet are each called by the holder alone, once", emptyList<String>(), s1Composables(code))
    }

    @Test fun `S7E5A1_N1S03 the S1 locks catch an alias import a qualified construction hiltViewModel a typed val reflection strings and an extra or missing AlertSection caller`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), s1ViewModel(code) + s1Composables(code))
        val alias = "package com.jay.fxi.ui.alert\nimport com.jay.fxi.ui.viewmodel.AlertViewModel as Vm\nfun bypass(vm: Vm) = vm"
        val newKotlin = listOf(
            "alias import" to alias,
            "fully qualified construction" to "val bypass = com.jay.fxi.ui.viewmodel.AlertViewModel(repository, auth)",
            "hiltViewModel type argument" to "@Composable fun Bypass() { val vm = hiltViewModel<AlertViewModel>() }",
            "typed val with hiltViewModel" to "@Composable fun Bypass() { val vm: AlertViewModel = hiltViewModel() }",
            "Class.forName string" to "val bypass = Class.forName(\"com.jay.fxi.ui.viewmodel.AlertViewModel\")",
            "escaped Class.forName string" to "val bypass = Class.forName(\"com.jay.fxi.ui.viewmodel.Alert\\u0056iewModel\")",
            "backticked name" to "val bypass: `AlertViewModel`? = null")
        for ((label, source) in newKotlin) assertCaught("N1-S S1 new Kotlin file, $label", "S1-files", s1ViewModel(withFile(BYPASS_KT, source)))
        assertCaught("N1-S S1 new Kotlin file, alias import", "S1-alias", s1ViewModel(withFile(BYPASS_KT, alias)))
        for ((label, source) in listOf(
            "Class.forName string" to "class Bypass { Object vm = Class.forName(\"com.jay.fxi.ui.viewmodel.AlertViewModel\"); }",
            "escaped identifier" to "class Bypass { Alert\\u0056iewModel vm; }")) {
            assertCaught("N1-S S1 new Java file, $label", "S1-files", s1ViewModel(withFile(BYPASS_JAVA, source)))
        }
        // E5a1: AlertSection names the VM no more, so any return of the VM to AlertSection widens the file set.
        val aliasedImport = "import com.jay.fxi.ui.viewmodel.AlertViewModel as Vm"
        val vmMaxCount = "private val max = AlertViewModel.MAX_COUNT"
        for ((label, source) in listOf(
            "the plain import restored" to "import com.jay.fxi.ui.viewmodel.AlertViewModel",
            "an aliased import" to aliasedImport,
            "fully qualified construction" to "private val bypass = com.jay.fxi.ui.viewmodel.AlertViewModel(a, b)",
            "hiltViewModel type argument" to "@Composable private fun Bypass() { val vm = hiltViewModel<AlertViewModel>() }",
            "typed val with hiltViewModel" to "@Composable private fun Bypass() { val vm: AlertViewModel = hiltViewModel() }",
            "escaped Class.forName string" to "private val bypass = Class.forName(\"com.jay.fxi.ui.viewmodel.Alert\\u0056iewModel\")",
            "the VM's MAX_COUNT" to vmMaxCount)) {
            assertCaught("E5a1 S1 inside AlertSection, $label", "S1-files", s1ViewModel(appended(SECTION, source)))
        }
        assertCaught("E5a1 S1 inside AlertSection, an aliased import", "S1-alias", s1ViewModel(appended(SECTION, aliasedImport)))
        assertCaught("E5a1 S1 inside AlertSection, the VM's MAX_COUNT", "S1-section-max-count", s1ViewModel(appended(SECTION, vmMaxCount)))
        val bareMaxCount = appended(SECTION, "private val max = MAX_COUNT")
        assertEquals("mutation premise: a bare MAX_COUNT leaves the VM's file set alone", setOf(VM), files(bareMaxCount, NAME_VM))
        assertCaught("E5a1 S1 inside AlertSection, a MAX_COUNT that names no VM", "S1-section-max-count", s1ViewModel(bareMaxCount))
        assertCaught("N1-S S1 a call in a new file", "S1-call AlertSection",
            s1Composables(withFile(BYPASS_KT, "@Composable fun Bypass() { AlertSection(currency = c) }")))
        assertCaught("N1-S S1 a function reference", "S1-function-reference AlertAddSheet",
            s1Composables(withFile(BYPASS_KT, "val bypass = ::AlertAddSheet")))
        assertCaught("N1-S S1 a call from AlertSection", "S1-call AlertAddSheet",
            s1Composables(appended(SECTION, "@Composable private fun Preview() { AlertAddSheet(onDismiss = {}) }")))
        // S7 E5b3b: the holder's one sheet call is the sheet's only call.
        assertCaught("N1-S S1 a second sheet call in the holder", "S1-call AlertAddSheet",
            s1Composables(appended(HOLDER, "@Composable private fun Preview() { AlertAddSheet(onDismiss = {}) }")))
        assertCaught("N1-S S1 the holder's sheet call removed", "S1-call AlertAddSheet",
            s1Composables(edited(HOLDER, """\bAlertAddSheet(?=\s*\()""", "OtherSheet")))
        assertCaught("N1-S S1 a call from AlertAddSheet", "S1-call AlertSection",
            s1Composables(appended(SHEET, "@Composable private fun Preview() { AlertSection() }")))
        assertCaught("N1-S S1 a Java call", "S1-call AlertSection",
            s1Composables(withFile(BYPASS_JAVA, "class Bypass { void go() { AlertSectionKt.AlertSection(null); } }")))
        // E5a1: the holder's one direct call is AlertSection's only call and its only name outside its own file.
        for ((label, tree, tag) in listOf(
            Triple("the holder's call removed", edited(HOLDER, """\bAlertSection(?=\s*[({])""", "ReadOnlySection"), "S1-call AlertSection"),
            Triple("a second call from the holder",
                appended(HOLDER, "@Composable private fun Twice() { AlertSection(c, s, {}, {}) }"), "S1-call AlertSection"),
            Triple("a call from the route", appended(ROUTE, "@Composable private fun Bypass() { AlertSection(c, s, {}, {}) }"), "S1-call AlertSection"),
            Triple("a function reference from the holder", appended(HOLDER, "private val bypass = ::AlertSection"), "S1-function-reference AlertSection"),
            Triple("an import that calls nothing", withFile(BYPASS_KT, "import com.jay.fxi.ui.alert.AlertSection\nval s7e5a1Bypass = 1"), "S1-named AlertSection"),
            Triple("a second declaration in the holder", appended(HOLDER, "@Composable internal fun AlertSection(x: Int) = Unit"),
                "S1-declaration AlertSection"))) {
            assertCaught("E5a1 S1 $label", tag, s1Composables(tree))
        }
        for ((label, path, source) in listOf(
            Triple("Kotlin comments only", BYPASS_KT, "// AlertViewModel AlertSection(\n/** hiltViewModel<AlertViewModel>() AlertAddSheet() */\nval s7n1Control = 1"),
            Triple("Java comments only", BYPASS_JAVA, "/* AlertViewModel */ class Control { // AlertSection(\n}"),
            Triple("lookalike names", BYPASS_KT, "fun AlertSectionLike() = Unit\nval alertViewModelCount = 0\nval s = \"AlertViewModels\""),
            Triple("the route's own BankAlertSection call and the section's tags", BYPASS_KT,
                "@Composable fun Bypass() { BankAlertSection(tab = t) }\nval tag = BankAlertSectionTags.ROOT\nval state: BankAlertSectionState? = null"))) {
            val tree = withFile(path, source)
            assertEquals("control(N1-S S1, $label): not a violation", emptyList<String>(), s1ViewModel(tree) + s1Composables(tree))
        }
    }

    @Test fun `S7N1_S04 the new N1 files reach no ViewModel reflection class loader or screen entry API`() {
        assertEquals("premise(N1-S S1): the new files are scanned and non-empty", emptyList<String>(),
            NEW_FILES.filter { code[it].isNullOrBlank() })
        assertEquals("N1-S S1: the new files' explicit inventory", emptyList<String>(), s1Entry(code))
        for ((tag, path, source) in listOf(
            Triple("ViewModel", OWNER, "private val vm = hiltViewModel<Nothing>()"),
            Triple("ViewModel", REGISTRY, "private val p = ViewModelProvider(store, factory)"),
            Triple("Class.forName", ACCESS, "private val c = Class.forName(\"x\")"),
            Triple("ClassLoader", FLOOR, "private val c = loader.loadClass(\"x\")"),
            Triple("reflection", POLICY, "private val k = AlertRetryPolicy::class"),
            Triple("Composable", PHASE, "@Composable fun Screen() = Unit"),
            Triple("screen", OWNER, "fun open(context: Context) = context.startActivity(Intent())"))) {
            assertCaught("N1-S S1 $tag in $path", "S1-entry-$tag $path", s1Entry(appended(path, source)))
        }
    }

    @Test fun `S7N1_S05 ProcessLifecycleOwner is named only by the topic owner and the registry which observes once in start`() {
        assertTrue("premise(N1-S S2): the topic owner still reads the process lifecycle",
            count(code.getValue(TOPIC_OWNER), """\bProcessLifecycleOwner\s*\.\s*get\s*\(""") >= 1)
        assertNotNull("premise(N1-S S2): the registry has a start", bodyRange(code.getValue(REGISTRY), "start"))
        assertEquals("N1-S S2 / N1-M structure", emptyList<String>(), s2Lifecycle(code))
    }

    @Test fun `S7N1_S06 the S2 lock catches a second observer an observer outside start and another lifecycle reach`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), s2Lifecycle(code))
        val outsideStart = appended(REGISTRY, "fun later(l: Lifecycle, o: LifecycleObserver) = l.addObserver(o)",
            editedBody(REGISTRY, "start") { OBSERVE.replaceFirst(it, ".hashCode(") })
        for ((label, tree, tag) in listOf(
            Triple("a second observer in the registry",
                appended(REGISTRY, "fun again(l: Lifecycle) = l.addObserver(LifecycleEventObserver { _, _ -> })"), "S2-observers"),
            Triple("the observer outside start", outsideStart, "S2-observer-outside-start"),
            Triple("the process lifecycle read at construction",
                edited(REGISTRY, """\{\s*ProcessLifecycleOwner\s*\.\s*get\s*\(\s*\)\s*\.\s*lifecycle\s*\}""", "ProcessLifecycleOwner.get().lifecycle.let { l -> { l } }"),
                "S2-lifecycle-supplier"),
            Triple("the owner reading the process lifecycle",
                appended(OWNER, "private val process = androidx.lifecycle.ProcessLifecycleOwner.get()"), "S2-files"),
            Triple("an escaped name in a new file", withFile(BYPASS_KT, "val name = \"androidx.lifecycle.Process\\u004CifecycleOwner\""), "S2-files"),
            Triple("a Java reach", withFile(BYPASS_JAVA, "class Bypass { Object o = androidx.lifecycle.ProcessLifecycleOwner.get(); }"), "S2-files"),
            Triple("the owner observing a lifecycle",
                appended(OWNER, "fun watch(owner: LifecycleOwner) = owner.lifecycle.addObserver(observer)"), "S2-other-observer"))) {
            assertCaught("N1-S S2 $label", tag, s2Lifecycle(tree))
        }
    }

    private fun String.swapOnce(old: String, new: String): String {
        val i = indexOf(old)
        require(i >= 0 && i == lastIndexOf(old))
        return replaceRange(i, i + old.length, new)
    }

    @Test fun S95_N01_comparisonPushEntry() {
        assertEquals(emptyList<String>(), pFcm(code))
        val logs = mapOf("deliverComparisonPush" to "comparison alert delivery failed",
            "consumeComparisonTap" to "comparison alert tap delivery failed")
        assertEquals(emptyList<String>(), s3Loggers(code))
        for ((method, message) in logs) {
            val old = "Log.w(TAG, \"$message\")"
            for (bad in listOf("Log.w(TAG, id)", "Log.w(TAG, price)", "Log.w(TAG, data.toString())",
                    "Log.w(TAG, \"$message\", failure)")) {
                val edited = editedBody(REGISTRY, method) { it.swapOnce(old, bad) }
                assertCaught("comparison logger $method", "S3-source-catch", s3Loggers(edited))
            }
        }
        val f1 = COMPARISON_PUSH
        val resolve = "{ alertFamiliesProvider.get() }"
        val post = "showAlertNotification(message, AlertFamilyRefresherRegistry.COMPARISON_ALERT, admitted)"
        val callback = "{ admitted -> $post }"
        val defaultPost = "showAlertNotification(message, AlertFamilyRefresherRegistry.COMPARISON_ALERT)"
        val variants = listOf(
            f1.swapOnce("if ($APP_GATE)", "val eager = alertFamiliesProvider.get()\nif ($APP_GATE)")
                .swapOnce(resolve, "{ eager }"),
            f1.swapOnce(" return }", " }"),
            f1.swapOnce(callback, "{ _ -> }\n$defaultPost"),
            f1.swapOnce(post, defaultPost),
            f1.swapOnce(post, """showAlertNotification(message, message.data.getValue("type"), admitted)"""),
            f1.swapOnce("if ($APP_GATE)", "if (true)"),
            f1.swapOnce(post, "showAlertNotification(message, AlertFamilyRefresherRegistry.COMPARISON_ALERT, { true })")
        )
        for (variant in variants) {
            val tree = editedBody(FCM, "onMessageReceived") { squash(it).swapOnce(f1, variant) }
            assertCaught("F1", "P-comparison-branch", pFcm(tree))
        }
    }

    @Test fun S95_N02_comparisonTapEntry() {
        assertEquals(emptyList<String>(), tActivity(code))
        val off = "parseAlertEventFromIntent"
        val removed = editedBody(ACTIVITY, off) { squash(it).swapOnce(COMPARISON_TAP, "") }
        val cold = editedBody(ACTIVITY, "parsePendingAlertEvent", removed) {
            val anchor = "if (intent == null) return val result = AlertTapEntry.intakeNew("
            squash(it).swapOnce(anchor, "if (intent == null) return " +
                COMPARISON_TAP.swapOnce("return null", "return") + " val result = AlertTapEntry.intakeNew(")
        }
        assertCaught("M1 in cold ON", "T-elsewhere parsePendingAlertEvent", tActivity(cold))
        val later = editedBody(ACTIVITY, off) {
            squash(it).swapOnce("$COMPARISON_TAP $SOURCE_TAP", "$SOURCE_TAP $COMPARISON_TAP")
        }
        assertCaught("M1 after source", "T-comparison-parser", tActivity(later))
    }

    @Test fun S95_N03_mirrorMatchesProduction() {
        val testDir = "src/test/java/com/jay/fxi/data/alerts/"
        fun body(path: String, method: String): String {
            val source = PostS6ApprovedDelta.codeAndStrings(File(path).readText(Charsets.UTF_8))
            return squash(source.substring(requireNotNull(bodyRange(source, method))))
        }
        fun splitAt(text: String, anchor: String): Pair<String, String> {
            val i = text.indexOf(anchor)
            require(i >= 0 && i == text.lastIndexOf(anchor)) { "ambiguous or missing: $anchor" }
            return text.substring(0, i).trim() to text.substring(i)
        }
        val rawType = """val type = message.data["type"] ?: return"""
        val sourceTap = "if (AlertFamilyRefresherRegistry.consumeSourceTap("
        val receive = body(FCM, "onMessageReceived")
        val log = squash("""Log.d(TAG, "Message received: type=${'$'}{message.data["type"]}")""")
        val afterLog = splitAt(receive, log).second.removePrefix(log).trimStart()
        val prodPush = splitAt(afterLog, rawType).first
        val parser = body(ACTIVITY, "parseAlertEventFromIntent")
        val guard = "if (intent == null) return null "
        assertTrue(parser.startsWith(guard))
        val prodTap = splitAt(parser.removePrefix(guard), sourceTap).first
        assertEquals(COMPARISON_PUSH, prodPush)
        assertEquals(COMPARISON_TAP, prodTap)

        val mirror = testDir + "S95Entry.kt"
        val history = testDir + "S86Entry.kt"
        val (push, pushTail) = splitAt(body(mirror, "push"), """val type = data["type"] ?: return null""")
        val (tap, tapTail) = splitAt(body(mirror, "tap"), sourceTap)
        val resolver = "{ alertFamiliesProvider.get() }"
        val publish = "showAlertNotification(message, AlertFamilyRefresherRegistry.COMPARISON_ALERT, admitted)"
        val mappedPush = push.replace("""data["type"]""", """message.data["type"]""")
            .replace("""data["notification_type"]""", """message.data["notification_type"]""")
            .replace("deliverComparisonPush(data, resolve)", "deliverComparisonPush(message.data, $resolver)")
            .replace("if (gateOpen)", "if ($APP_GATE)")
            .replace("publish(admitted)", publish)
            .replace("return null", "return")
        val mappedTap = tap.replace("consumeComparisonTap(extras, gateOpen, resolve)",
            "consumeComparisonTap(AndroidAlertTapExtras(intent), $APP_GATE) $resolver")
        assertEquals(prodPush, squash(mappedPush))
        assertEquals(prodTap, squash(mappedTap))
        val expectedMirrorPush = squash("""
            if (data["type"] == AlertFamilyRefresherRegistry.COMPARISON_ALERT ||
                data["notification_type"] == AlertFamilyRefresherRegistry.COMPARISON_ALERT) {
                if (gateOpen) {
                    AlertFamilyRefresherRegistry.deliverComparisonPush(data, resolve) { admitted -> publish(admitted) }
                }
                return null
            }
        """)
        val expectedMirrorTap = "if (AlertFamilyRefresherRegistry.consumeComparisonTap(extras, gateOpen, resolve)) return null"
        assertEquals(expectedMirrorPush, push)
        assertEquals(expectedMirrorTap, tap)
        assertEquals(body(history, "push"), pushTail)
        assertEquals(body(history, "tap"), tapTail)
    }

    @Test fun `S86_N01 source extras and both publication guards stay at their effect sites`() {
        val fcm = code.getValue(FCM)
        val body = squash(fcm.substring(checkNotNull(bodyRange(fcm, "showAlertNotification"))))
        assertTrue("N01 entry admission", body.startsWith("if (!ReleaseAdmission.isOpen || !admitted()) return val (title, body)"))
        val copy = squash("""
            if (krxContestSwitch.enabled) {
                AlertTapEntry.copyForegroundClassification(message.data, AndroidAlertTapExtras(intent))
            } else if (type == AlertFamilyRefresherRegistry.SOURCE_RATE_ALERT) {
                intent.putExtra("source", message.data["source"])
                intent.putExtra("asset", message.data["asset"])
            } else if (type == AlertFamilyRefresherRegistry.COMPARISON_ALERT) {
                for (key in AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS) {
                    message.data[key]?.let { intent.putExtra(key, it) }
                }
            }
        """)
        assertTrue("N01 OFF tuple copy", body.contains(copy))
        assertTrue("N01 admission immediately before PendingIntent", body.contains(
            "val notificationId = System.currentTimeMillis().toInt() if (!admitted()) return val pendingIntent = PendingIntent.getActivity("))
        assertTrue("N01 admission immediately before notify", body.endsWith(
            "val notificationManager = getSystemService(NotificationManager::class.java) if (admitted()) notificationManager.notify(notificationId, notification)"))
        assertEquals("N01 one PendingIntent acquisition", 1, Regex("""PendingIntent\.getActivity\(""").findAll(body).count())
        assertEquals("N01 one notification post", 1, Regex("""\.notify\(""").findAll(body).count())
    }

    /** S8-6. The source callback runs under the app gate before the bank sequence. */
    @Test fun `S7N1_P01 S8-6 source returns before the bank emit hook and banner`() {
        val fcm = code.getValue(FCM)
        assertNotNull("premise(N1-P): onMessageReceived has a block body", bodyRange(fcm, "onMessageReceived"))
        assertEquals("premise(N1-P): the existing route is there", 1, count(fcm, """\bAlertPushRouting\s*\.\s*route\s*\("""))
        assertEquals("S8-6: source exits before the bank emit, gated hook and guarded banner", emptyList<String>(), pFcm(code))
    }

    @Test fun `S7N1_S07 deliverPush and onSyncAlerts each have one declaration and one call in the agreed place`() {
        val registry = code.getValue(REGISTRY)
        assertEquals("premise(N1-S S3): the registry declares deliverPush", 1, count(registry, """\bfun\s+deliverPush\s*\("""))
        assertEquals("premise(N1-S S3): and onSyncAlerts", 1, count(registry, """\bfun\s+onSyncAlerts\s*\("""))
        assertEquals("N1-S S3: the call and declaration inventory", emptyList<String>(), s3Hook(code))
    }

    @Test fun `S7N1_P02 deliverPush resolves only with the gate open for an exact sync or an exact bank alert inside an Exception boundary`() {
        val registry = code.getValue(REGISTRY)
        val body = bodyRange(registry, "deliverPush")
        assertNotNull("premise(N1-P): deliverPush has a block body", body)
        val flat = squash(registry.substring(body!!))
        assertTrue("N1-P / N1-A / S7 E5c: the real body is an agreed form: $flat", GUARDS.any { it.matches(flat) })
        assertEquals("N1-P: the sync constant is the raw FCM type", 1, count(registry, "\\bconst\\s+val\\s+SYNC_ALERTS\\s*=\\s*\"sync_alerts\""))
        assertEquals("S7 E5c: so is the bank alert constant", 1, count(registry, "\\bconst\\s+val\\s+RATE_ALERT\\s*=\\s*\"rate_alert\""))
        val sync = "SYNC_ALERTS -> resolve().onSyncAlerts()"
        val bank = "RATE_ALERT -> resolve().onBankPush(settingId?.toIntOrNull(), isRepeat)"
        val boundary = "catch (e: Exception) {\n    reportFailure()\n}"
        fun form(branches: String, gate: String = "if (!gateOpen) return", catch: String = boundary) =
            "$gate\ntry {\n    when (type) {\n        $branches\n    }\n} $catch"
        for ((label, agreed) in listOf("the agreed order" to form("$sync\n$bank"), "the branches swapped" to form("$bank\n$sync"))) {
            assertEquals("control(N1-P / S7 E5c, $label): an agreed form passes", emptyList<String>(), s3Hook(withDeliverBody(agreed)))
        }
        for ((label, mutant) in listOf(
            "the gate ignored" to form("$sync\n$bank", gate = ""),
            "the gate inverted" to form("$sync\n$bank", gate = "if (gateOpen) return"),
            "any other type resolves" to form("$sync\n$bank\nelse -> resolve().onSyncAlerts()"),
            "a bank alert also syncs" to form("$sync\nRATE_ALERT -> { resolve().onSyncAlerts(); resolve().onBankPush(settingId?.toIntOrNull(), isRepeat) }"),
            "not an exact compare" to "if (!gateOpen) return\ntry {\n    when (type.lowercase()) {\n        $sync\n        $bank\n    }\n} $boundary",
            "the raw repeat parsed" to form("$sync\nRATE_ALERT -> resolve().onBankPush(settingId?.toIntOrNull(), isRepeat?.lowercase())"),
            "the id read loosely" to form("$sync\nRATE_ALERT -> resolve().onBankPush(settingId?.trim()?.toIntOrNull(), isRepeat)"),
            "resolved before the gate" to "val registry = resolve()\nif (!gateOpen) return\ntry {\n    when (type) {\n        SYNC_ALERTS -> registry.onSyncAlerts()\n" +
                "        RATE_ALERT -> registry.onBankPush(settingId?.toIntOrNull(), isRepeat)\n    }\n} $boundary",
            "no boundary" to "if (!gateOpen) return\nwhen (type) {\n    $sync\n    $bank\n}",
            "a Throwable boundary" to form("$sync\n$bank", catch = "catch (e: Throwable) {\n    reportFailure()\n}"),
            "a silent boundary" to form("$sync\n$bank", catch = "catch (e: Exception) { }"),
            "only the resolve guarded" to "if (!gateOpen) return\nval registry = try { resolve() } catch (e: Exception) { reportFailure(); return }\n" +
                "when (type) {\n    SYNC_ALERTS -> registry.onSyncAlerts()\n    RATE_ALERT -> registry.onBankPush(settingId?.toIntOrNull(), isRepeat)\n}")) {
            assertCaught("N1-P / S7 E5c $label", "S3-guard", s3Hook(withDeliverBody(mutant)))
        }
        assertCaught("N1-P the sync constant changed", "S3-sync-const", s3Hook(editedLiteral(REGISTRY, "\"sync_alerts\"", "\"sync_alert\"")))
        assertCaught("S7 E5c the bank alert constant changed", "S3-bank-const", s3Hook(editedLiteral(REGISTRY, "\"rate_alert\"", "\"rate_alerts\"")))
    }

    /** S8-6. Mutate the source return and both sides of the app gate independently of the bank hook. */
    @Test fun `S7N1_S08 S8-6 rejects source gate and bank hook mutations`() {
        fun hook(tree: Map<String, String>) = s3Hook(tree) + pFcm(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), hook(code))
        val fcm = code.getValue(FCM)
        val range = hookRange(fcm)
        val statement = fcm.substring(range)
        val emitLine = fcm.lastIndexOf('\n', fcm.indexOf(".emit(")) + 1
        assertTrue("mutation premise: the emit line precedes the hook", emitLine in 1..range.first)
        val beforeEmit = fcm.removeRange(range).let { it.substring(0, emitLine) + statement + "\n" + it.substring(emitLine) }
        val gate = "shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)"
        val raw = "message.data[\"setting_id\"], message.data[\"is_repeat\"]"
        val sourceExit = "            return\n        }\n        alertEventBusProvider.get().emit(route.event)"
        val post = "showAlertNotification(message, type, admitted)"
        val withoutPost = editedLiteral(FCM, post, "Unit")
        val sourceAt = fcm.indexOf("if (type == AlertFamilyRefresherRegistry.SOURCE_RATE_ALERT)")
        assertTrue(sourceAt >= 0)
        val brace = fcm.indexOf('{', sourceAt)
        val end = requireNotNull(closing(fcm, brace))
        val source = fcm.substring(sourceAt, end + 1)
        val lazyCall = "deliverSourcePush(message.data, { alertFamiliesProvider.get() })"
        val eager = source.swapOnce("if ($gate)", "val eager = alertFamiliesProvider.get()\nif ($gate)")
            .swapOnce(lazyCall, "deliverSourcePush(message.data, { eager })")
        for ((label, tree, tag) in listOf(
            Triple("source falls through to the bank emit",
                editedLiteral(FCM, sourceExit, sourceExit.replace("return", "")), "P-source-branch"),
            Triple("publish after the source gate",
                editedLiteral(FCM, sourceExit, sourceExit.replace("return", "showAlertNotification(message, type)\n            return"), withoutPost),
                "P-source-branch"),
            Triple("source provider resolved before the gate",
                code + (FCM to fcm.replaceRange(sourceAt, end + 1, eager)), "P-source-branch"),
            Triple("bank banner before the delivery",
                editedLiteral(FCM, "$statement\n        $BANK_BANNER", "$BANK_BANNER\n        $statement"), "P-order"),
            Triple("the hook before the emit", code + (FCM to beforeEmit), "P-after-emit"),
            Triple("a gate that is always open", hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, $raw, true) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("the release admission alone",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, $raw, ReleaseAdmission.isOpen) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("the no-data flag ignored",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, $raw, shouldStartAppOwnedServices(ReleaseAdmission.isOpen, false)) { alertFamiliesProvider.get() }" },
                "P-gate-expression"),
            Triple("the routed event for the raw type",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(route.event.toString(), $raw, $gate) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("an eager resolve",
                hookReplaced { "val eager = alertFamiliesProvider.get()\nAlertFamilyRefresherRegistry.deliverPush(type, $raw, $gate) { eager }" }, "P-gate-expression"),
            Triple("another setting key (S7 E5c)",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, message.data[\"settingId\"], message.data[\"is_repeat\"], $gate) { alertFamiliesProvider.get() }" },
                "P-gate-expression"),
            Triple("a parsed repeat (S7 E5c)",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, message.data[\"setting_id\"], message.data[\"is_repeat\"]?.lowercase(), $gate) { alertFamiliesProvider.get() }" },
                "P-gate-expression"),
            Triple("the two raw values swapped (S7 E5c)",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, message.data[\"is_repeat\"], message.data[\"setting_id\"], $gate) { alertFamiliesProvider.get() }" },
                "P-gate-expression"),
            Triple("no repeat (S7 E5c)",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, message.data[\"setting_id\"], null, $gate) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("the notification's repeat dropped (S7 E5c)",
                editedLiteral(FCM, "message.data[EXTRA_IS_REPEAT]?.let { putExtra(EXTRA_IS_REPEAT, it) }", ""), "P-repeat-extra"),
            Triple("the notification's repeat parsed (S7 E5c)",
                editedLiteral(FCM, "putExtra(EXTRA_IS_REPEAT, it)", "putExtra(EXTRA_IS_REPEAT, it.toBoolean())"), "P-repeat-extra"),
            Triple("a second hook", hookReplaced { "$it\n$it" }, "S3-deliver-fcm"),
            Triple("a declaration in place of the call", hookReplaced { "fun deliverPush(type: String) = Unit" }, "S3-deliver-fcm"),
            Triple("a direct sync from FCM", hookReplaced { "$it\nalertFamiliesProvider.get().onSyncAlerts()" }, "S3-sync-files"),
            Triple("the early return dropped",
                editedBody(FCM, "onMessageReceived") { Regex("""if\s*\(\s*!ReleaseAdmission\.isOpen\s*\)\s*return""").replaceFirst(it, "") }, "P-gate-first"),
            Triple("the emit inside a catch", editedBody(FCM, "onMessageReceived") { EMIT.replaceFirst(it, "try { \$1 } catch (e: Exception) { }") }, "P-catch"),
            Triple("a second emit", editedBody(FCM, "onMessageReceived") { EMIT.replaceFirst(it, "\$1\n\$1") }, "P-emit"),
            Triple("an alias import",
                edited(FCM, """\bimport\s+com\.jay\.fxi\.data\.alerts\.AlertFamilyRefresherRegistry\b""", "import com.jay.fxi.data.alerts.AlertFamilyRefresherRegistry as Families"),
                "S3-alias"),
            Triple("a function reference elsewhere", withFile(BYPASS_KT, "val bypass = AlertFamilyRefresherRegistry::deliverPush"), "S3-function-reference"),
            Triple("a deliverPush call elsewhere",
                withFile(BYPASS_KT, "fun bypass() = AlertFamilyRefresherRegistry.deliverPush(\"sync_alerts\", true) { error(\"x\") }"), "S3-deliver-files"),
            Triple("an onSyncAlerts call elsewhere", withFile(BYPASS_KT, "fun bypass(r: AlertFamilyRefresherRegistry) = r.onSyncAlerts()"), "S3-sync-files"),
            Triple("an onSyncAlerts call in Java",
                withFile(BYPASS_JAVA, "class Bypass { void go(AlertFamilyRefresherRegistry r) { r.onSyncAlerts(); } }"), "S3-sync-files"),
            Triple("an onSyncAlerts reference in the registry", appended(REGISTRY, "private val bypass = ::onSyncAlerts"), "S3-function-reference"),
            Triple("the registry calling its own sync", appended(REGISTRY, "fun bypass() { onSyncAlerts() }"), "S3-sync-tokens"))) {
            assertCaught("N1-S S3 / N1-P $label", tag, hook(tree))
        }
    }

    /** S8-6. Only the acceptSource catch may log the fixed source failure. */
    @Test fun `S7N1_S09 S8-6 accepts one fixed source catch beside the three default loggers`() {
        val registry = code.getValue(REGISTRY)
        assertEquals("premise(N1-S S3 / S7 E5c): three reportFailure defaults, the registry's, deliverPush's and deliverTap's", 3,
            REPORT_DEFAULT.findAll(registry).count())
        assertTrue("premise(N1-S S3 / S7 E5c): the registry catches at least the supplier, the adapter and the two deliveries",
            CATCH.findAll(registry).count() >= 4)
        assertEquals("N1-S S3 default loggers and catch boundaries", emptyList<String>(), s3Loggers(code))
        for ((label, tree, tag) in listOf(
            Triple("a payload in the delivery message",
                editedLiteral(REGISTRY, "\"$DELIVERY_MESSAGE\"", "\"$DELIVERY_MESSAGE \" + type"), "S3-logger-form"),
            Triple("an exception argument",
                editedLiteral(REGISTRY, "\"$REGISTRY_MESSAGE\"", "\"$REGISTRY_MESSAGE\", Throwable()"), "S3-logger-form"),
            Triple("a template message", editedLiteral(REGISTRY, "\"$REGISTRY_MESSAGE\"", "\"failed \${TAG}\""), "S3-logger-form"),
            Triple("another message", editedLiteral(REGISTRY, "\"$DELIVERY_MESSAGE\"", "\"sync failed\""), "S3-logger-message"),
            Triple("a catch that logs the exception",
                edited(REGISTRY, """(catch\s*\(\s*(\w+)\s*:\s*Exception\s*\)\s*\{)\s*reportFailure\(\)""", "\$1 Log.w(TAG, \"failed\", \$2)"), "S3-catch"),
            Triple("a Throwable catch", edited(REGISTRY, """catch\s*\(\s*(\w+)\s*:\s*Exception\s*\)""", "catch (\$1: Throwable)"), "S3-catch"),
            Triple("a seventh logger in the registry", appended(REGISTRY, "private fun note() { Log.w(TAG, \"note\") }"), "S3-logger-count"),
            Triple("source catch includes the payload",
                editedLiteral(REGISTRY, SOURCE_LOG, """Log.w(TAG, "source alert delivery failed " + source)"""), "S3-source-catch"),
            Triple("source catch passes the exception",
                editedLiteral(REGISTRY, SOURCE_LOG, """Log.w(TAG, "source alert delivery failed", failure)"""), "S3-source-catch"),
            Triple("a payload in the tap message (S7 E5c)",
                editedLiteral(REGISTRY, "\"$TAP_MESSAGE\"", "\"$TAP_MESSAGE \" + event"), "S3-logger-form"),
            Triple("another tap message (S7 E5c)", editedLiteral(REGISTRY, "\"$TAP_MESSAGE\"", "\"tap failed\""), "S3-logger-message"),
            Triple("a payload logged by another new file",
                appended(OWNER, "private fun note(e: Exception) { Log.w(TAG, \"failed\", e) }"), "S3-logger-payload"))) {
            assertCaught("N1-S S3 $label", tag, s3Loggers(tree))
        }
    }

    /** S8-6. A consumed source returns null before the cold slot or warm emit and legacy clear. */
    @Test fun `S7E5C_S01 S8-6 consumes source at OFF entry and preserves the lazy bank tap`() {
        fun both(tree: Map<String, String>) = tActivity(tree) + s3Hook(tree)
        assertEquals("S7 E5c: the activity's tap hook and the inventory", emptyList<String>(), both(code))
        val activity = code.getValue(ACTIVITY)
        val gate = "shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)"
        val repeat = "intent.getStringExtra(FXiMessagingService.EXTRA_IS_REPEAT)"
        val hook = Regex("""AlertFamilyRefresherRegistry\s*\.\s*deliverTap\s*\([^{}]*\)\s*\{\s*alertFamiliesProvider\s*\.\s*get\s*\(\s*\)\s*\}""")
        assertEquals("mutation premise: the tap hook is found once", 1, hook.findAll(activity).count())
        fun tapReplaced(with: (String) -> String): Map<String, String> {
            val match = checkNotNull(hook.find(activity))
            return code + (ACTIVITY to activity.replaceRange(match.range, with(match.value)))
        }
        val entry = activity.substringAfter("if (intent == null) return null").substringBefore("val type = intent.getStringExtra")
        val sourceAt = entry.indexOf("if (AlertFamilyRefresherRegistry.consumeSourceTap(")
        assertTrue(sourceAt >= 0)
        assertEquals(COMPARISON_TAP, squash(entry.take(sourceAt)))
        val sourceTap = entry.drop(sourceAt).trim()
        assertEquals("S8-6 mutation premise: move the complete OFF consumer", SOURCE_TAP, squash(sourceTap))
        val withoutSourceTap = editedLiteral(ACTIVITY, sourceTap, "")
        val routeLine = "val route = AlertPushRouting.route(type, settingId) ?: return null"
        val routeAfter = editedBody(ACTIVITY, "parseAlertEventFromIntent") { body ->
            val at = body.indexOf(routeLine)
            val without = body.removeRange(at, at + routeLine.length)
            val end = without.indexOf("return route.event")
            without.substring(0, end) + routeLine + "\n" + without.substring(end)
        }
        for ((label, tree, tag) in listOf(
            Triple("a gate that is always open", tapReplaced { "AlertFamilyRefresherRegistry.deliverTap(route.event, $repeat, true) { alertFamiliesProvider.get() }" }, "T-hook"),
            Triple("the release admission alone",
                tapReplaced { "AlertFamilyRefresherRegistry.deliverTap(route.event, $repeat, ReleaseAdmission.isOpen) { alertFamiliesProvider.get() }" }, "T-hook"),
            Triple("a raw key literal", tapReplaced { "AlertFamilyRefresherRegistry.deliverTap(route.event, intent.getStringExtra(\"is_repeat\"), $gate) { alertFamiliesProvider.get() }" },
                "T-key"),
            Triple("the repeat parsed", tapReplaced { "AlertFamilyRefresherRegistry.deliverTap(route.event, $repeat?.lowercase(), $gate) { alertFamiliesProvider.get() }" }, "T-hook"),
            Triple("another event", tapReplaced { "AlertFamilyRefresherRegistry.deliverTap(AlertEvent.RefreshNeeded, $repeat, $gate) { alertFamiliesProvider.get() }" }, "T-hook"),
            Triple("an eager resolve", tapReplaced { "val eager = alertFamiliesProvider.get()\nAlertFamilyRefresherRegistry.deliverTap(route.event, $repeat, $gate) { eager }" },
                "T-hook"),
            Triple("a second tap", tapReplaced { "${it}\n${it}" }, "S3-tap-activity"),
            Triple("the route read after the tap", routeAfter, "T-order"),
            Triple("the OFF consumer moved to the cold ON branch",
                editedLiteral(ACTIVITY, "val result = AlertTapEntry.intakeNew(",
                    sourceTap.removeSuffix(" null") + "\n            val result = AlertTapEntry.intakeNew(", withoutSourceTap),
                "T-elsewhere parsePendingAlertEvent"),
            Triple("a tap in the cold path",
                editedBody(ACTIVITY, "parsePendingAlertEvent") { "\nAlertFamilyRefresherRegistry.deliverTap(AlertEvent.RefreshNeeded, null, true) { alertFamiliesProvider.get() }\n$it" },
                "T-elsewhere parsePendingAlertEvent"),
            Triple("a tap in the warm path",
                editedBody(ACTIVITY, "handleNotificationIntent") { "\nAlertFamilyRefresherRegistry.deliverTap(AlertEvent.RefreshNeeded, null, true) { alertFamiliesProvider.get() }\n$it" },
                "T-elsewhere handleNotificationIntent"),
            Triple("the field resolved at injection",
                edited(ACTIVITY, """lateinit\s+var\s+alertFamiliesProvider\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""",
                    "lateinit var alertFamiliesProvider: AlertFamilyRefresherRegistry"), "T-provider"),
            Triple("the removal dropped", editedLiteral(ACTIVITY, "intent?.removeExtra(FXiMessagingService.EXTRA_IS_REPEAT)", ""), "T-clear"),
            Triple("a direct bank push from the activity", appended(ACTIVITY, "private fun bypass() = alertFamiliesProvider.get().onBankPush(1, \"false\")"), "S3-bank-files"),
            Triple("an alias import",
                edited(ACTIVITY, """\bimport\s+com\.jay\.fxi\.data\.alerts\.AlertFamilyRefresherRegistry\b""", "import com.jay.fxi.data.alerts.AlertFamilyRefresherRegistry as Families"),
                "S3-alias"),
            Triple("a function reference elsewhere", withFile(BYPASS_KT, "val bypass = AlertFamilyRefresherRegistry::deliverTap"), "S3-function-reference"),
            Triple("a deliverTap call elsewhere",
                withFile(BYPASS_KT, "fun bypass() = AlertFamilyRefresherRegistry.deliverTap(AlertEvent.RefreshNeeded, null, true) { error(\"x\") }"), "S3-tap-files"),
            Triple("a second deliverTap declaration", appended(REGISTRY, "fun deliverTap() = Unit"), "S3-tap-declaration"))) {
            assertCaught("S7 E5c $label", tag, both(tree))
        }
    }

    @Test fun `S7E5C_S02 deliverTap resolves only with the gate open for a bank setting or an all-family refresh inside an Exception boundary`() {
        val registry = code.getValue(REGISTRY)
        val body = bodyRange(registry, "deliverTap")
        assertNotNull("premise(S7 E5c): deliverTap has a block body", body)
        val flat = squash(registry.substring(body!!))
        assertTrue("S7 E5c: the real body is an agreed form: $flat", TAP_GUARDS.any { it.matches(flat) })
        val setting = "event is AlertEvent.SettingTriggered && event.family == AlertFamily.BANK -> resolve().onBankPush(event.settingId, isRepeat)"
        val refresh = "event == AlertEvent.RefreshNeeded -> resolve().onBankPush(null, null)"
        val boundary = "catch (e: Exception) {\n    reportFailure()\n}"
        fun form(branches: String, gate: String = "if (!gateOpen) return", catch: String = boundary) =
            "$gate\ntry {\n    when {\n        $branches\n    }\n} $catch"
        for ((label, agreed) in listOf("the agreed order" to form("$setting\n$refresh"), "the branches swapped" to form("$refresh\n$setting"))) {
            assertEquals("control(S7 E5c, $label): an agreed form passes", emptyList<String>(), s3Hook(withTapBody(agreed)))
        }
        for ((label, mutant) in listOf(
            "the gate ignored" to form("$setting\n$refresh", gate = ""),
            "the gate inverted" to form("$setting\n$refresh", gate = "if (gateOpen) return"),
            "any family's setting" to form("event is AlertEvent.SettingTriggered -> resolve().onBankPush(event.settingId, isRepeat)\n$refresh"),
            "a refresh passing the repeat" to form("$setting\nevent == AlertEvent.RefreshNeeded -> resolve().onBankPush(null, isRepeat)"),
            "a family refresh too" to form("$setting\n$refresh\nevent is AlertEvent.FamilyRefreshNeeded -> resolve().onBankPush(null, null)"),
            "anything else resolves" to form("$setting\n$refresh\nelse -> resolve().onBankPush(null, null)"),
            "the repeat parsed" to form(
                "event is AlertEvent.SettingTriggered && event.family == AlertFamily.BANK -> resolve().onBankPush(event.settingId, isRepeat?.lowercase())\n$refresh"),
            "resolved before the gate" to "val registry = resolve()\nif (!gateOpen) return\ntry {\n    when {\n" +
                "        event is AlertEvent.SettingTriggered && event.family == AlertFamily.BANK -> registry.onBankPush(event.settingId, isRepeat)\n" +
                "        event == AlertEvent.RefreshNeeded -> registry.onBankPush(null, null)\n    }\n} $boundary",
            "no boundary" to "if (!gateOpen) return\nwhen {\n    $setting\n    $refresh\n}",
            "a Throwable boundary" to form("$setting\n$refresh", catch = "catch (e: Throwable) {\n    reportFailure()\n}"),
            "a silent boundary" to form("$setting\n$refresh", catch = "catch (e: Exception) { }"))) {
            assertCaught("S7 E5c $label", "S3-tap-guard", s3Hook(withTapBody(mutant)))
        }
    }

    @Test fun `S7E5C_S03 onBankPush is the adapter default, the registry's main hand-over and the bank owner's override only`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), s3Hook(code))
        for ((label, tree, tag) in listOf(
            Triple("the registry calling the owner directly",
                appended(REGISTRY, "private fun bypass(o: BankAlertSettingsOwner) { o.onBankPush(1, \"false\") }"), "S3-bank-tokens"),
            Triple("the hand-over off main",
                edited(REGISTRY, """postToMain\s*\{\s*dispatch\s*\{\s*\(\s*it\s+as\?\s+AlertFamilyAdapter\s*\)\s*\?\.\s*onBankPush\s*\(\s*id\s*,\s*isRepeat\s*\)\s*\}\s*\}""", "dispatch { (it as? AlertFamilyAdapter)?.onBankPush(id, isRepeat) }"),
                "S3-bank-dispatch"),
            Triple("the hand-over parsing the repeat",
                edited(REGISTRY, """\(\s*it\s+as\?\s+AlertFamilyAdapter\s*\)\s*\?\.\s*onBankPush\s*\(\s*id\s*,\s*isRepeat\s*\)""", "(it as? AlertFamilyAdapter)?.onBankPush(id, isRepeat?.lowercase())"), "S3-bank-dispatch"),
            Triple("the owner calling itself", appended(OWNER, "private fun bypass() = onBankPush(1, \"false\")"), "S3-bank-owner"),
            Triple("the section holder reaching it",
                appended(HOLDER, "private fun bypass(o: BankAlertSettingsOwner) = o.onBankPush(1, \"false\")"), "S3-bank-files"),
            Triple("a Java caller",
                withFile(BYPASS_JAVA, "class Bypass { void go(AlertFamilyRefresherRegistry r) { r.onBankPush(1, \"false\"); } }"), "S3-bank-files"),
            Triple("a reference", appended(REGISTRY, "private val bypass = ::onBankPush"), "S3-function-reference"))) {
            assertCaught("S7 E5c $label", tag, s3Hook(tree))
        }
    }

    @Test fun `S7N1_A01 FXiApplication passes startAlertFamilies only past its admission gate and the helper runs it once before the purge`() {
        val app = code.getValue(APP)
        assertNotNull("premise(N1-A): onCreate has a block body", bodyRange(app, "onCreate"))
        assertNotNull("premise(N1-A): so has the start helper", bodyRange(app, "startAppOwnedServices"))
        assertEquals("N1-A structure / S4: gate-before-resolve in production onCreate", emptyList<String>(), aApplication(code))
    }

    /** S8-6. Check the Hilt use capture and the pre-resolution exchange guard in their owning functions. */
    @Test fun `S7E5A1_N1A02 S8-6 limits the new registry reaches to use binding and exchange admission`() {
        assertEquals("premise(N1-A / S7 E5c): the registry is named by the Application, FCM and the OFF tap parser", setOf(REGISTRY, APP, FCM, ACTIVITY),
            files(code, """\bAlertFamilyRefresherRegistry\b"""))
        assertTrue("premise(E5a1 A-reach): the section holder is scanned and declares its entry point",
            Regex("""@EntryPoint\b""").containsMatchIn(code.getValue(HOLDER)))
        assertEquals("N1-A / S4 with E5a1's holder: reach, scope and lazy resolution", emptyList<String>(), aReach(code))
        assertCaught("source use skips the grant check", "A-source-access",
            aReach(editedLiteral(REGISTRY, ".takeIf(access::admits)", "")))
        assertCaught("source guard accepts a denied exchange", "A-source-policy",
            aReach(editedLiteral(REGISTRY, "!SourceAlertRowPolicy.EXCHANGES.admits", "SourceAlertRowPolicy.EXCHANGES.admits")))
        assertCaught("access named elsewhere in the registry", "A-source-access",
            aReach(appended(REGISTRY, "private val bypass: AlertSettingsAccess? = null")))
        assertCaught("policy named elsewhere in the registry", "A-source-policy",
            aReach(appended(REGISTRY, "private val bypass = SourceAlertRowPolicy.EXCHANGES")))
    }

    @Test fun `S7N1_A03 the S4 and gate locks catch a start before the gate outside its lambda or dropped from the helper`() {
        val ownerProvider = Regex("""\b(\w+)\s*:\s*Provider\s*<\s*BankAlertSettingsOwner\s*>""").find(code.getValue(REGISTRY))?.groupValues?.get(1)
        assertNotNull("mutation premise: the registry takes the bank owner as a Provider", ownerProvider)
        val startBeforeGate = editedBody(APP, "onCreate") { body ->
            val call = Regex("""\bstartAppOwnedServices\s*\(""").find(body)!!
            val close = closing(body, call.range.last)!!
            body.substring(call.range.first, close + 1) + "\n" + body.removeRange(call.range.first, close + 1)
        }
        for ((label, tree, tag) in listOf(
            Triple("the start helper before the gate", startBeforeGate, "A-gate-order"),
            Triple("the registry resolved before the gate", editedBody(APP, "onCreate") { "\nalertFamilies.get().start()\n$it" }, "A-tokens"),
            Triple("the start outside its lambda", editedBody(APP, "onCreate") { LAMBDA.replaceFirst(it, "{ }") + "\nalertFamilies.get().start()\n" }, "A-lambda"),
            Triple("the field resolved at injection",
                edited(APP, """lateinit\s+var\s+alertFamilies\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""", "lateinit var alertFamilies: AlertFamilyRefresherRegistry"),
                "A-provider"),
            Triple("the gate without its return", edited(APP, """(if\s*\(\s*!\s*\w+\s*\)\s*\{[^{}]*?)\breturn\b""", "\$1"), "A-gate-return"),
            Triple("the start dropped from the helper", editedBody(APP, "startAppOwnedServices") { STEP.replaceFirst(it, "") }, "A-helper"),
            Triple("the start after the purge", editedBody(APP, "startAppOwnedServices") { STEP.replaceFirst(it, "") + "\nstartAlertFamilies()\n" }, "A-helper"),
            Triple("another starter",
                withFile(BYPASS_KT, "class Starter @Inject constructor(private val r: Provider<AlertFamilyRefresherRegistry>) { fun go() = r.get().start() }"),
                "A-reach AlertFamilyRefresherRegistry"),
            Triple("the registry starting itself", appended(REGISTRY, "private val selfStart = run { start() }"), "A-self-start"),
            Triple("the owner injected elsewhere", withFile(BYPASS_KT, "class Screen @Inject constructor(val owner: BankAlertSettingsOwner)"), "A-reach BankAlertSettingsOwner"),
            Triple("the owner named by the route (E5a1)", appended(ROUTE, "private val bypass: BankAlertSettingsOwner? = null"), "A-reach BankAlertSettingsOwner"),
            Triple("the owner named by AlertSection (E5a1)", appended(SECTION, "private val bypass: BankAlertSettingsOwner? = null"), "A-reach BankAlertSettingsOwner"),
            Triple("the holder no longer naming the owner (E5a1)", holderWithoutOwnerName(), "A-reach BankAlertSettingsOwner"),
            Triple("the floor read elsewhere", withFile(BYPASS_KT, "class Crud @Inject constructor(val floor: AlertRateLimitFloor)"), "A-reach AlertRateLimitFloor"),
            Triple("the access reached from Java", withFile(BYPASS_JAVA, "class Bypass { AlertSettingsAccess access; }"), "A-reach AlertSettingsAccess"),
            Triple("the owner resolved at construction", appended(REGISTRY, "private val eager = $ownerProvider.get()"), "A-lazy-owner"),
            Triple("the source owner resolved at construction (S8-4a)", appended(REGISTRY, "private val eagerSource = source.get()"), "A-lazy-source-owner"),
            Triple("the source owner injected elsewhere (S8-4a)", withFile(BYPASS_KT, "class Screen @Inject constructor(val owner: SourceAlertSettingsOwner)"),
                "A-reach SourceAlertSettingsOwner"),
            Triple("a source write outside the owner (S8-5a)",
                withFile(BYPASS_KT, "class Bypass(val r: AlertRepository) { suspend fun go(o: AuthIdentityFence) = r.deleteSourceSetting(o, 1) }"),
                "A-source-write deleteSourceSetting"),
            Triple("a second source create call in the owner through another receiver (S8-5a)",
                appended(SOURCE_OWNER, "private suspend fun again(r: AlertRepository, o: AuthIdentityFence) = r.createSourceSetting(o, " +
                    "SourceAlertSettingRequest(\"upbit\", \"usdt-krw\", AlertCondition.ABOVE, 1.0, true))"),
                "A-source-write createSourceSetting"),
            Triple("a second source edit inside the repository (S8-5a)",
                // Inside the class, as a member call would compile (the scan is string-level; the battery compiles real mutants).
                editedLiteral(REPOSITORY, "    suspend fun deleteSourceSetting(",
                    "    private suspend fun again(o: AuthIdentityFence) = updateSourceSetting(o, 1, SourceAlertSettingUpdateRequest())\n\n    suspend fun deleteSourceSetting("),
                "A-source-write updateSourceSetting"),
            Triple("a source create by reference (S8-5a)", withFile(BYPASS_KT, "val alias = AlertRepository::createSourceSetting"),
                "A-source-write createSourceSetting"),
            Triple("the delegation function used elsewhere (S8-5a)",
                withFile(BYPASS_KT, "class Bypass(val r: AlertRepository) { val d = productionSourceDelete(r) }"),
                "A-source-delegation productionSourceDelete"),
            Triple("the delegation calling the write twice (S8-5a)",
                editedLiteral(SOURCE_OWNER, "{ owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted) }",
                    "{ owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted); repository.deleteSourceSetting(owner, id, admitted) }"),
                "A-source-write-once deleteSourceSetting"),
            Triple("a floor per consumer", edited(FLOOR, """@Singleton\s+""", ""), "A-singleton AlertRateLimitFloor"))) {
            assertCaught("N1-A / S4 $label", tag, aApplication(tree) + aReach(tree))
        }
        assertCaught("N1-A / S4 FCM starting the registry", "P-provider", pFcm(hookReplaced { "$it\nalertFamiliesProvider.get().start()" }))
    }

    @Test fun `S8_5B_A01 the source owner's writes are called once each by the section's production seam, in their exact form`() {
        assertEquals(emptyList<String>(), aSourceEntries(code))
    }

    @Test fun `S8_5B_A02 the write lock catches another caller, a lost argument, a chained or extra handle, a reference and the registry`() {
        for ((label, tree, tag) in listOf(
            Triple("an add from another file", withFile(BYPASS_KT, "class Bypass(val c: Context) { fun go(d: SourceAlertDraft) = " +
                "EntryPointAccessors.fromApplication(c, SourceAlertSectionEntryPoint::class.java).sourceAlertSettingsOwner().addAlert(d) }"), "A-source-entry-point"),
            Triple("a second owner lookup in the section", appended(SOURCE_SECTION, "private fun s(c: Context) = " +
                "EntryPointAccessors.fromApplication(c, SourceAlertSectionEntryPoint::class.java).sourceAlertSettingsOwner()"), "A-source-entry-getter"),
            Triple("a new handle in place of the owner's", editedLiteral(SOURCE_SECTION, "override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)",
                "override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft).let { SourceAlertSaveRequest() }"), "A-source-entry form"),
            Triple("the edit's patch lost", editedLiteral(SOURCE_SECTION, "= owner.editAlert(id, edit)", "= owner.editAlert(id, SourceAlertEdit())"), "A-source-entry form"),
            Triple("the toggle inverted", editedLiteral(SOURCE_SECTION, "= owner.setEnabled(id, enabled)", "= owner.setEnabled(id, !enabled)"), "A-source-entry form"),
            Triple("another id deleted", editedLiteral(SOURCE_SECTION, "= owner.delete(id)", "= owner.delete(0)"), "A-source-entry form"),
            Triple("a write by reference in the section", appended(SOURCE_SECTION, "private val ref = SourceAlertSettingsOwner::addAlert"), "A-source-entry outside"),
            Triple("the registry writing", appended(REGISTRY, "private fun s(o: SourceAlertSettingsOwner, d: SourceAlertDraft) = o.addAlert(d)"), "A-source-entry registry"),
            Triple("an alias of the owner in the factory", editedLiteral(SOURCE_SECTION, "        val memory = entryPoint.lastSelectedSourceMemory()\n",
                "        val memory = entryPoint.lastSelectedSourceMemory()\n        val again = owner\n"), "A-source-entry uses"),
            Triple("the owner taken by another function of the section", appended(SOURCE_SECTION,
                "private fun s(o: SourceAlertSettingsOwner, d: SourceAlertDraft) = o.addAlert(d)"), "A-source-entry type"),
            Triple("a write left to a default", editedLiteral(SOURCE_SECTION, "    fun delete(id: Int)\n}", "    fun delete(id: Int) = Unit\n}"),
                "A-source-entry seam"))) {
            assertCaught("S8-5b $label", tag, aSourceEntries(tree))
        }
    }

    @Test fun `S7N1_S10 the NEW_FILES inventory names no KRX in code or strings`() {
        assertTrue("premise(N1-S S5): the same pattern finds KRX where it is", KRX.containsMatchIn(code.getValue(KRX_SAMPLE)))
        assertEquals("N1-S S5", emptyList<String>(), s5Krx(code))
        assertCaught("N1-S S5 an identifier", "S5-krx $OWNER", s5Krx(appended(OWNER, "private val krxGate = 0")))
        assertCaught("N1-S S5 a string", "S5-krx $FLOOR", s5Krx(appended(FLOOR, "private val tag = \"KRX\"")))
        assertCaught("N1-S S5 an escaped string", "S5-krx $REGISTRY", s5Krx(appended(REGISTRY, "private val name = \"\\u004Brx\"")))
        assertEquals("control(N1-S S5): a comment is not a reference", emptyList<String>(), s5Krx(appended(POLICY, "// KRX contest\n/* krx */")))
    }

    @Test fun `S7N1_Q01 the new files read no snapshot authority alert bus permission prompt push token owner or binding`() {
        assertEquals("premise(N1-Q): the new files are scanned", emptyList<String>(), NEW_FILES.filter { it !in code })
        assertEquals("N1-Q / N1-S S6", emptyList<String>(), s6Surfaces(code))
        for ((tag, path, source) in listOf(
            Triple("accessSnapshot", OWNER, "private val s = topic.accessSnapshot"),
            Triple("TopicAccessSnapshot", ACCESS, "private val t = TopicAccessSnapshot(a, b)"),
            Triple("SnapshotTopicUseAuthority", ACCESS, "private val u = SnapshotTopicUseAuthority(store)"),
            Triple("AlertEventBus", REGISTRY, "private val bus: AlertEventBus? = null"),
            Triple("AlertEventBus", OWNER, "private val name = \"Alert\\u0045ventBus\""),
            Triple("events", REGISTRY, "fun watch() = scope.launch { bus.events.collect { } }"),
            Triple("permission", OWNER, "fun ask() = launcher.launch(Manifest.permission.POST_NOTIFICATIONS)"),
            Triple("permission", REGISTRY, "fun ask() = ActivityCompat.requestPermissions(activity, names, 1)"),
            Triple("registerIfNeeded", OWNER, "fun push() = notifications.registerIfNeeded()"),
            Triple("FirebaseMessaging", REGISTRY, "private val m = FirebaseMessaging.getInstance()"),
            Triple("getToken", OWNER, "fun token() = messaging.getToken()"),
            Triple("onNewToken", REGISTRY, "fun onNewToken(token: String) = Unit"),
            Triple("PushNotificationManager", OWNER, "private val p: PushNotificationManager? = null"),
            Triple("registerDevice", OWNER, "fun register() = api.registerDevice(request)"),
            Triple("binding", PHASE, "@Module object Bypass"),
            Triple("binding", POLICY, "@Provides fun provide() = 1"),
            Triple("binding", FLOOR, "@Binds abstract fun bind(x: X): Y"))) {
            assertCaught("N1-Q $tag in $path", "S6-$tag $path", s6Surfaces(appended(path, source)))
        }
        assertEquals("control(N1-Q): names in comments only are not references", emptyList<String>(),
            s6Surfaces(appended(OWNER, "// AlertEventBus .events permission FirebaseMessaging getToken @Module\n/* registerIfNeeded .accessSnapshot */")))
    }

    /**
     * S11 (gap battery r1, wiring the behaviour tests cannot reach: they build through the primary constructors): the five
     * production `@Inject` delegations pass exactly the agreed suppliers — the monotonic clock, the [-0.2, 0.2) jitter, the
     * Main scope, the coordinator's live state and access revisions, the graph host, the published fence, the shared deletion
     * admission, the Main Handler post and the process lifecycle — both owners use their 1 s default recheck periods,
     * and the service has a gated source publication callback and a route-guarded bank-path banner call.
     */
    /** S8-6. The source callback carries admission and the bank call retains its route guard. */
    @Test fun `S7N1_S11 S8-6 preserves production wiring and the two distinct banner sites`() {
        for (path in INJECT_FORMS.keys) {
            assertNotNull("premise(S11): $path has exactly one @Inject constructor delegation", injectDelegation(code.getValue(path)))
        }
        assertEquals("S11: the agreed production wiring", emptyList<String>(), s11Wiring(code))
    }

    /** S8-6. Both banner mutants must still fail on the bank path. */
    @Test fun `S7N1_S12 S8-6 rejects wiring and banner mutations and accepts reformatted delegation`() {
        for ((label, tree, tag) in listOf(
            Triple("jitter [0, 1)", editedLiteral(OWNER, "Random.nextDouble(-0.2, 0.2)", "Random.nextDouble()"), "S11-inject $OWNER"),
            Triple("comparison supplier omitted", editedLiteral(REGISTRY, ", { comparison.get() }", ""), "S11-inject $REGISTRY"),
            Triple("comparison wired to an unguarded reader", editedLiteral(COMPARISON_OWNER, "comparisonSettingsGet(repository), access,",
                "{ use, _ -> repository.getComparisonAlerts(use.identity) }, access,"), "S11-inject $COMPARISON_OWNER"),
            Triple("comparison identity ignores deletion", editedLiteral(COMPARISON_OWNER, " && protectedAdmission()", ""),
                "S11-inject $COMPARISON_OWNER"),
            Triple("comparison identity ignores release", editedLiteral(COMPARISON_OWNER, "ReleaseAdmission.isOpen && ", ""),
                "S11-inject $COMPARISON_OWNER"),
            Triple("comparison identity changes during admission", editedLiteral(COMPARISON_OWNER, " && tokens.currentIdentityFence() == it", ""),
                "S11-inject $COMPARISON_OWNER"),
            Triple("comparison DELETE writer omitted", editedLiteral(COMPARISON_OWNER, ", comparisonDelete(repository)", ""),
                "S11-inject $COMPARISON_OWNER"),
            Triple("comparison POST constant guard", editedLiteral(COMPARISON_OWNER,
                "createComparisonAlert(use.identity, request, useAdmitted = guard)", "createComparisonAlert(use.identity, request, useAdmitted = { true })"),
                "S11-comparison-write comparisonCreate"),
            Triple("comparison PUT invokes another verb", editedLiteral(COMPARISON_OWNER,
                "repository.updateComparisonAlert", "repository.createComparisonAlert"), "S11-comparison-write comparisonUpdate"),
            Triple("comparison GET for one tab", editedLiteral(COMPARISON_OWNER, "tab = null", "tab = \"tether\""), "S11-comparison-get"),
            Triple("comparison GET with a constant guard", editedLiteral(COMPARISON_OWNER, "tab = null, useAdmitted = guard", "tab = null, useAdmitted = { true }"),
                "S11-comparison-get"),
            Triple("the source GET for every asset", editedLiteral(SOURCE_OWNER, "SourceAlertRowPolicy.SETTINGS_ASSET, admitted)", "null, admitted)"),
                "S11-inject $SOURCE_OWNER"),
            Triple("the source GET without its use check", editedLiteral(SOURCE_OWNER, "SourceAlertRowPolicy.SETTINGS_ASSET, admitted)",
                "SourceAlertRowPolicy.SETTINGS_ASSET)"), "S11-inject $SOURCE_OWNER"),
            Triple("the source GET with a constant use check", editedLiteral(SOURCE_OWNER, "SourceAlertRowPolicy.SETTINGS_ASSET, admitted)",
                "SourceAlertRowPolicy.SETTINGS_ASSET, { true })"), "S11-inject $SOURCE_OWNER"),
            // S8-5a: each source write's production delegation carries its request's use check.
            Triple("a source create without its use check", editedLiteral(SOURCE_OWNER, "repository.createSourceSetting(owner, request, admitted)",
                "repository.createSourceSetting(owner, request)"), "S11-source-write productionSourceCreate"),
            Triple("a source create with a constant use check", editedLiteral(SOURCE_OWNER, "repository.createSourceSetting(owner, request, admitted)",
                "repository.createSourceSetting(owner, request) { true }"), "S11-source-write productionSourceCreate"),
            Triple("a source edit without its use check", editedLiteral(SOURCE_OWNER, "repository.updateSourceSetting(owner, id, request, admitted)",
                "repository.updateSourceSetting(owner, id, request)"), "S11-source-write productionSourceEdit"),
            Triple("a source edit with a constant use check", editedLiteral(SOURCE_OWNER, "repository.updateSourceSetting(owner, id, request, admitted)",
                "repository.updateSourceSetting(owner, id, request) { true }"), "S11-source-write productionSourceEdit"),
            Triple("a source delete without its use check", editedLiteral(SOURCE_OWNER, "repository.deleteSourceSetting(owner, id, admitted)",
                "repository.deleteSourceSetting(owner, id)"), "S11-source-write productionSourceDelete"),
            Triple("a source delete with a constant use check", editedLiteral(SOURCE_OWNER, "repository.deleteSourceSetting(owner, id, admitted)",
                "repository.deleteSourceSetting(owner, id) { true }"), "S11-source-write productionSourceDelete"),
            Triple("a source write passed as its own lambda, around the delegation", editedLiteral(SOURCE_OWNER,
                "productionSourceDelete(repository)", "{ owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted) }"),
                "S11-inject $SOURCE_OWNER"),
            Triple("a source recheck period of 30 s", editedLiteral(SOURCE_OWNER, "private val accessRecheckMillis: Long = 1_000",
                "private val accessRecheckMillis: Long = 30_000"), "S11-source-recheck"),
            Triple("jitter [0, 1) spelled out", editedLiteral(OWNER, "Random.nextDouble(-0.2, 0.2)", "Random.nextDouble(0.0, 1.0)"), "S11-inject $OWNER"),
            Triple("a recheck period passed by production",
                editedLiteral(OWNER, "repository.deleteSetting(owner, id, admitted) }", "repository.deleteSetting(owner, id, admitted) }, 30_000"),
                "S11-inject $OWNER"),
            Triple("a toggle sending the inverse", editedLiteral(OWNER, "AlertSettingUpdateRequest(isEnabled = enabled)",
                "AlertSettingUpdateRequest(isEnabled = !enabled)"), "S11-inject $OWNER"),
            Triple("a toggle without the use check", editedLiteral(OWNER, "AlertSettingUpdateRequest(isEnabled = enabled), admitted)",
                "AlertSettingUpdateRequest(isEnabled = enabled))"), "S11-inject $OWNER"),
            Triple("a toggle that also sends a repeat change", editedLiteral(OWNER, "AlertSettingUpdateRequest(isEnabled = enabled)",
                "AlertSettingUpdateRequest(isEnabled = enabled, repeatIntervalSec = kotlinx.serialization.json.JsonNull)"), "S11-inject $OWNER"),
            Triple("a delete without the use check", editedLiteral(OWNER, "repository.deleteSetting(owner, id, admitted)", "repository.deleteSetting(owner, id)"),
                "S11-inject $OWNER"),
            Triple("another recheck default", edited(OWNER, """accessRecheckMillis\s*:\s*Long\s*=\s*1_000""", "accessRecheckMillis: Long = 30_000"),
                "S11-recheck"),
            Triple("a wall-clock floor", editedLiteral(FLOOR, "this(SystemClock::elapsedRealtime)", "this(System::currentTimeMillis)"), "S11-inject $FLOOR"),
            Triple("a deletion admission for nobody",
                editedLiteral(ACCESS, "GraphProtectedAdmission(tokens::currentIdentityFence, deletions)", "GraphProtectedAdmission({ null }, deletions)"),
                "S11-inject $ACCESS"),
            Triple("no published fence", editedLiteral(ACCESS, "topic.currentAccessFence", "{ null }"), "S11-inject $ACCESS"),
            Triple("the premium snapshot frozen at construction",
                editedLiteral(REGISTRY, "coordinator.state,", "kotlinx.coroutines.flow.MutableStateFlow(coordinator.state.value),"), "S11-inject $REGISTRY"),
            Triple("the sync run on the caller's thread", editedLiteral(REGISTRY, "Handler(Looper.getMainLooper()).post { action() }", "action()"),
                "S11-inject $REGISTRY"),
            Triple("the banner guard inverted",
                editedLiteral(FCM, "if (route.showBanner) showAlertNotification", "if (!route.showBanner) showAlertNotification"), "S11-banner"),
            Triple("a second banner call", editedLiteral(FCM, "if (route.showBanner) showAlertNotification(message, type)",
                "if (route.showBanner) showAlertNotification(message, type)\n        showAlertNotification(message, type)"), "S11-banner")
        )) {
            assertCaught("S11 $label", tag, s11Wiring(tree))
        }
        assertCaught("extra comparison writer reference", "A-comparison-write deleteComparisonAlert",
            aReach(code + (COMPARISON_OWNER to (code.getValue(COMPARISON_OWNER) + "\nval extra = ::comparisonDelete"))))

        val reformatted = edited(OWNER, """\{\s*Random\.nextDouble\(-0\.2,\s*0\.2\)\s*\}""", "{\n            Random.nextDouble(\n                -0.2, 0.2\n            )\n        }")
        assertEquals("control(S11): the same delegation reformatted passes", emptyList<String>(), s11Wiring(reformatted))
        val sourceReformatted = editedLiteral(SOURCE_OWNER, "{ owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted) }",
            "{ owner, id, admitted ->\n        repository.deleteSourceSetting(\n            owner, id, admitted\n        )\n    }")
        assertEquals("control(S11): a source delegation reformatted passes", emptyList<String>(), s11Wiring(sourceReformatted) + aReach(sourceReformatted))
    }

    // ---- mutation helpers: each asserts its own anchor, so no mutation can silently be the real tree ----

    private fun assertCaught(label: String, tag: String, violations: List<String>) =
        assertTrue("mutation($label): the lock reports $tag; it reported $violations", violations.any { it == tag || it.startsWith("$tag ") })

    private fun withFile(path: String, source: String, base: Map<String, String> = code): Map<String, String> {
        assertTrue("mutation premise: $path is a new file", path !in base)
        return base + (path to strip(path, source))
    }

    private fun appended(path: String, source: String, base: Map<String, String> = code): Map<String, String> {
        assertTrue("mutation premise: $path is scanned", path in base)
        return base + (path to base.getValue(path) + "\n" + strip(path, source) + "\n")
    }

    private fun edited(path: String, pattern: String, replacement: String, base: Map<String, String> = code): Map<String, String> {
        val text = base.getValue(path)
        val regex = Regex(pattern)
        assertTrue("mutation premise: /$pattern/ is in $path", regex.containsMatchIn(text))
        return base + (path to regex.replaceFirst(text, replacement))
    }

    private fun editedLiteral(path: String, old: String, new: String, base: Map<String, String> = code): Map<String, String> {
        val text = base.getValue(path)
        assertTrue("mutation premise: $old is in $path", text.contains(old))
        return base + (path to text.replaceFirst(old, new))
    }

    private fun editedBody(path: String, function: String, base: Map<String, String> = code, transform: (String) -> String): Map<String, String> {
        val text = base.getValue(path)
        val range = bodyRange(text, function)
        assertNotNull("mutation premise: $function has a block body in $path", range)
        val body = text.substring(range!!)
        val changed = transform(body)
        assertNotEquals("mutation premise: the edit changes $function", body, changed)
        return base + (path to text.replaceRange(range, changed))
    }

    private fun withDeliverBody(body: String): Map<String, String> {
        val text = code.getValue(REGISTRY)
        val range = bodyRange(text, "deliverPush")
        assertNotNull("mutation premise: deliverPush has a block body", range)
        return code + (REGISTRY to text.replaceRange(range!!, "\n$body\n"))
    }

    private fun withTapBody(body: String): Map<String, String> {
        val text = code.getValue(REGISTRY)
        val range = bodyRange(text, "deliverTap")
        assertNotNull("mutation premise: deliverTap has a block body", range)
        return code + (REGISTRY to text.replaceRange(range!!, "\n$body\n"))
    }

    /** The FCM hook statement: the deliverPush call with its trailing lambda. */
    private fun hookRange(text: String): IntRange {
        val start = HOOK_START.find(text)
        assertNotNull("mutation premise: the FCM hook statement is found", start)
        val argsClose = closing(text, start!!.range.last)
        assertNotNull("mutation premise: its arguments close", argsClose)
        var i = argsClose!! + 1
        while (i < text.length && text[i].isWhitespace()) i++
        val end = if (i < text.length && text[i] == '{') closing(text, i) ?: argsClose else argsClose
        return start.range.first..end
    }

    /** E5a1 A-reach shrink: every name of the bank owner in the section holder replaced, as if the holder reached it unnamed. */
    private fun holderWithoutOwnerName(): Map<String, String> {
        val text = code.getValue(HOLDER)
        val name = Regex("""\bBankAlertSettingsOwner\b""")
        assertTrue("mutation premise: the holder names the bank owner", name.containsMatchIn(text))
        return code + (HOLDER to name.replace(text, "Any"))
    }

    private fun hookReplaced(with: (String) -> String): Map<String, String> {
        val fcm = code.getValue(FCM)
        val range = hookRange(fcm)
        return code + (FCM to fcm.replaceRange(range, with(fcm.substring(range))))
    }

    internal val productionSources: Map<String, String> get() = code

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val VM = BASE + "ui/viewmodel/AlertViewModel.kt"
        val SECTION = BASE + "ui/alert/AlertSection.kt"
        val SHEET = BASE + "ui/alert/AlertAddSheet.kt"
        val TOPIC_OWNER = BASE + "data/remote/TopicRuntimeOwner.kt"
        val APP = BASE + "FXiApplication.kt"
        val FCM = BASE + "service/FXiMessagingService.kt"
        /** S7 E5c: the OFF tap parser's file, the registry's one new namer. */
        val ACTIVITY = BASE + "MainActivity.kt"
        val KRX_SAMPLE = BASE + "data/graph/KrxGraphFence.kt"
        val PHASE = BASE + "data/alerts/AlertLoadPhase.kt"
        val POLICY = BASE + "data/alerts/AlertRetryPolicy.kt"
        val FLOOR = BASE + "data/alerts/AlertRateLimitFloor.kt"
        val ACCESS = BASE + "data/alerts/AlertSettingsAccess.kt"
        val REGISTRY = BASE + "data/alerts/AlertFamilyRefresherRegistry.kt"
        val OWNER = BASE + "data/alerts/BankAlertSettingsOwner.kt"
        /** S8-3 (`R4c/S8/s8_3_contract` §1.1–§1.2): the source settings owner and its row policy. */
        val SOURCE_OWNER = BASE + "data/alerts/SourceAlertSettingsOwner.kt"
        val COMPARISON_OWNER = BASE + "data/alerts/ComparisonAlertSettingsOwner.kt"
        val ROW_POLICY = BASE + "data/alerts/SourceAlertRowPolicy.kt"
        val REPOSITORY = BASE + "data/repository/AlertRepository.kt"
        /** S8-4a: the tether tab's read section, the source owner's one screen namer. */
        val SOURCE_SECTION = BASE + "ui/alert/SourceAlertSection.kt"
        /** S8-4b: the tether history entry (process access and floor) and its holder (the session asset). */
        val SOURCE_HISTORY_ENTRY = BASE + "ui/alert/SourceHistoryEntry.kt"
        val SOURCE_HISTORY_HOLDER = BASE + "ui/alert/SourceHistoryStateHolder.kt"
        /** The six new N1 production files (API r1 §2–§6) and S8-3's two. E5a1's section holder is deliberately not one of them. */
        val NEW_FILES = listOf(PHASE, POLICY, FLOOR, ACCESS, REGISTRY, OWNER, SOURCE_OWNER, ROW_POLICY, COMPARISON_OWNER)
        /** S7 E5a1's section holder (API r2 §2): AlertSection's one caller and, besides the owner and the registry, the owner's one namer. */
        val HOLDER = BASE + "ui/alert/BankAlertSection.kt"
        val ROUTE = BASE + "ui/premium/view/PremiumTopicRoute.kt"
        val BYPASS_KT = BASE + "ui/alert/S7n1Bypass.kt"
        val BYPASS_JAVA = BASE + "ui/alert/S7n1Bypass.java"

        /** The default log messages: the registry's (N1 API r3 §5) and S7 E5c's push and tap ones (agreed in its implementation request). */
        const val REGISTRY_MESSAGE = "alert family adapter dispatch failed"
        const val DELIVERY_MESSAGE = "alert family push delivery failed"
        const val TAP_MESSAGE = "alert family tap delivery failed"

        const val NAME_VM = """\bAlertViewModel\b"""
        val KRX = Regex("(?i)krx")
        val OBSERVE = Regex("""\.\s*addObserver\s*\(""")
        val EMIT = Regex("""([\w.]+\(\)\.emit\([^()]*\))""")
        val LAMBDA = Regex("""\{\s*alertFamilies\s*\.\s*get\s*\(\s*\)\s*\.\s*start\s*\(\s*\)\s*\}""")
        val STEP = Regex("""\bstartAlertFamilies\s*\(\s*\)""")
        val HOOK_START = Regex("""\bAlertFamilyRefresherRegistry\s*\.\s*deliverPush\s*\(""")
        /** The FCM statement, squashed: the raw type, setting_id and is_repeat and exactly the app-owned gate, with a lazy resolve. */
        val HOOK = Regex("""AlertFamilyRefresherRegistry\.deliverPush\((?:type = )?type, (?:settingId = )?message\.data\["setting_id"], (?:isRepeat = )?message\.data\["is_repeat"], (?:gateOpen = )?shouldStartAppOwnedServices\((?:releaseAdmissionOpen = )?ReleaseAdmission\.isOpen, (?:benchmarkNoData = )?BuildConfig\.BENCHMARK_NO_DATA_MODE\)\) \{ alertFamiliesProvider\.get\(\) \}""")
        /** S7 E5c: the parser's statement, squashed: the routed event, the raw is_repeat by the FCM constant, the same gate, lazy. */
        val TAP_HOOK = Regex("""AlertFamilyRefresherRegistry\.deliverTap\((?:event = )?route\.event, (?:isRepeat = )?intent\.getStringExtra\(FXiMessagingService\.EXTRA_IS_REPEAT\), (?:gateOpen = )?shouldStartAppOwnedServices\((?:releaseAdmissionOpen = )?ReleaseAdmission\.isOpen, (?:benchmarkNoData = )?BuildConfig\.BENCHMARK_NO_DATA_MODE\)\) \{ alertFamiliesProvider\.get\(\) \}""")
        // S8-6 approved service and OFF parser boundaries (scope r2, existing structure tests).
        const val APP_GATE = "shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)"
        const val BANK_EMIT = "alertEventBusProvider.get().emit(route.event)"
        const val BANK_BANNER = "if (route.showBanner) showAlertNotification(message, type)"
        val COMPARISON_PUSH = squash("""
        if (message.data["type"] == AlertFamilyRefresherRegistry.COMPARISON_ALERT ||
            message.data["notification_type"] == AlertFamilyRefresherRegistry.COMPARISON_ALERT) {
            if (shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)) {
                AlertFamilyRefresherRegistry.deliverComparisonPush(message.data, { alertFamiliesProvider.get() }) { admitted ->
                    showAlertNotification(message, AlertFamilyRefresherRegistry.COMPARISON_ALERT, admitted)
                }
            }
            return
        }
        """)
        val COMPARISON_TAP = squash("""
        if (AlertFamilyRefresherRegistry.consumeComparisonTap(
                AndroidAlertTapExtras(intent),
                shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)
            ) { alertFamiliesProvider.get() }) return null
        """)
        val SOURCE_PUSH = squash("""
            if (type == AlertFamilyRefresherRegistry.SOURCE_RATE_ALERT) {
                if ($APP_GATE) {
                    AlertFamilyRefresherRegistry.deliverSourcePush(message.data, { alertFamiliesProvider.get() }) { admitted ->
                        showAlertNotification(message, type, admitted)
                    }
                }
                return
            }
        """)
        val SOURCE_TAP = squash("""
            if (AlertFamilyRefresherRegistry.consumeSourceTap(AndroidAlertTapExtras(intent), $APP_GATE) { alertFamiliesProvider.get() }) return null
        """)
        const val SOURCE_LOG = """Log.w(TAG, "source alert delivery failed")"""
        val REPORT_DEFAULT = Regex("""\breportFailure\s*:\s*\(\s*\)\s*->\s*Unit\s*=\s*\{""")
        val LOG_DEFAULT = Regex("""\{ Log\.w\(TAG, "([^"$\\]*)"\)(?: Unit)? \}""")
        val LOG_CALL = Regex("""\bLog\s*\.\s*\w+\s*\(""")
        val FIXED_LOG_ARGS = Regex("TAG, \"[^\"\$\\\\]*\"")
        val CONST_TAG = Regex("\\bconst\\s+val\\s+TAG\\s*=\\s*\"[^\"\$\\\\]*\"")
        val CATCH = Regex("""\bcatch\s*\(\s*\w+\s*:\s*([\w.]+)\s*\)\s*\{""")
        val CALL = Regex("""\b(\w+)\s*\(""")
        val KEYWORDS = setOf("if", "when", "while", "for", "catch", "return", "try")

        /** deliverPush's body, squashed (S7 E5c): the open gate first, then in one boundary an exact sync or an exact bank alert. */
        const val SYNC_BRANCH = """SYNC_ALERTS -> resolve\(\)\.onSyncAlerts\(\)"""
        const val BANK_BRANCH = """RATE_ALERT -> resolve\(\)\.onBankPush\(settingId\?\.toIntOrNull\(\), isRepeat\)"""
        const val TRY = """try \{ when \(type\) \{ (?:$SYNC_BRANCH $BANK_BRANCH|$BANK_BRANCH $SYNC_BRANCH) \} \} catch \(\w+: Exception\) \{ reportFailure\(\) \}"""
        val GUARDS = listOf("""if \(!gateOpen\) return $TRY""").map(::Regex)

        /** deliverTap's body, squashed (S7 E5c): the open gate first, then in one boundary a bank setting or an all-family refresh. */
        const val TAP_SETTING = """event is AlertEvent\.SettingTriggered && event\.family == AlertFamily\.BANK -> resolve\(\)\.onBankPush\(event\.settingId, isRepeat\)"""
        const val TAP_REFRESH = """event == AlertEvent\.RefreshNeeded -> resolve\(\)\.onBankPush\(null, null\)"""
        val TAP_GUARDS = listOf(
            """if \(!gateOpen\) return try \{ when \{ (?:$TAP_SETTING $TAP_REFRESH|$TAP_REFRESH $TAP_SETTING) \} \} catch \(\w+: Exception\) \{ reportFailure\(\) \}"""
        ).map(::Regex)

        val ENTRY_APIS = listOf(
            "ViewModel" to """(?i)viewmodel""",
            "Class.forName" to """\bClass\s*\.\s*forName\b""",
            "ClassLoader" to """\bClassLoader\b|\bclassLoader\b|\bloadClass\b""",
            "reflection" to """\b(?:kotlin|java\.lang)\.reflect\b|::\s*class\b|\.javaClass\b""",
            "Composable" to """@Composable\b""",
            "screen" to """\bsetContent\b|\bstartActivit(?:y|ies)\w*|\bIntent\b|\bEntryPointAccessors\b|@EntryPoint\b|\bNavController\b|\bnavigate\s*\(""")

        val FORBIDDEN = listOf(
            "accessSnapshot" to """\.\s*accessSnapshot\b""",
            "TopicAccessSnapshot" to """\bTopicAccessSnapshot\s*\(""",
            "SnapshotTopicUseAuthority" to """\bSnapshotTopicUseAuthority\s*\(""",
            "AlertEventBus" to """\bAlertEventBus\b""",
            "events" to """\.\s*events\b""",
            "permission" to """(?i)permission""",
            "registerIfNeeded" to """\bregisterIfNeeded\b""",
            "FirebaseMessaging" to """\bFirebaseMessaging\w*""",
            "getToken" to """\bgetToken\b""",
            "onNewToken" to """\bonNewToken\b""",
            "PushNotificationManager" to """\bPushNotificationManager\b""",
            "registerDevice" to """\bregisterDevice\b""",
            "binding" to """@(?:Module|Provides|Binds\w*|InstallIn|IntoSet|IntoMap|ElementsIntoSet|Multibinds)\b""")

        /** Every production source of the six roots, Kotlin and Java, comment-free with every literal kept. */
        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { it.invariantSeparatorsPath to strip(it.invariantSeparatorsPath, it.readText()) }
        }

        fun strip(path: String, source: String): String = when {
            path.endsWith(".kt") -> PostS6ApprovedDelta.codeAndStrings(source)
            path.endsWith(".java") -> javaCodeAndStrings(source)
            else -> error("not a scanned source: $path")
        }

        /** Java translates a Unicode escape before it lexes (JLS 3.3), unless its backslash is itself escaped. */
        private val JAVA_UNICODE_ESCAPE = Regex("""(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})""")

        /**
         * Java source with comments removed and every string, text block or char literal kept: the Java counterpart of
         * [PostS6ApprovedDelta.codeAndStrings]. Unicode escapes are decoded first, in code, literals and comments alike, as
         * Java does; a block comment ends at its first close, since Java's do not nest.
         */
        fun javaCodeAndStrings(source: String): String {
            val text = JAVA_UNICODE_ESCAPE.replace(source) { m -> m.groupValues[1] + m.groupValues[2].toInt(16).toChar() }
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> {
                        out.append(' ')
                        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                    }
                    text.startsWith("/*", i) -> {
                        out.append(' ')
                        val end = text.indexOf("*/", i + 2)
                        i = if (end < 0) text.length else end + 2
                    }
                    text.startsWith("\"\"\"", i) -> i = copyJavaLiteral(text, i, "\"\"\"", out)
                    text[i] == '"' || text[i] == '\'' -> i = copyJavaLiteral(text, i, text[i].toString(), out)
                    else -> {
                        out.append(text[i])
                        i++
                    }
                }
            }
            return out.toString()
        }

        private fun copyJavaLiteral(text: String, start: Int, delimiter: String, out: StringBuilder): Int {
            var i = start + delimiter.length
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text.startsWith(delimiter, i)) {
                    val end = i + delimiter.length
                    out.append(text, start, end)
                    return end
                }
                i++
            }
            out.append(text, start, text.length)
            return text.length
        }

        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()

        fun files(code: Map<String, String>, pattern: String): Set<String> {
            val regex = Regex(pattern)
            return code.filterValues { regex.containsMatchIn(it) }.keys
        }

        fun occurrences(code: Map<String, String>, pattern: String): Map<String, Int> {
            val regex = Regex(pattern)
            return code.mapValues { regex.findAll(it.value).count() }.filterValues { it > 0 }
        }

        /** Whitespace collapsed, braces spaced, no space just inside parentheses, no trailing comma before a close. */
        fun squash(text: String): String = text
            .replace(Regex("""\s*([{}])\s*"""), " \$1 ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex(""",\s*\)"""), ")")
            .trim()

        /** Index just past the literal at [start] (`"`, `"""` or `'`), template expressions included. */
        fun literalEnd(text: String, start: Int): Int {
            val raw = text.startsWith("\"\"\"", start)
            val delimiter = if (raw) "\"\"\"" else text[start].toString()
            val templates = raw || delimiter == "\""
            var i = start + delimiter.length
            while (i < text.length) {
                if (text.startsWith(delimiter, i)) {
                    var end = i + delimiter.length
                    if (raw) {
                        while (end < text.length && text[end] == '"') end++
                    }
                    return end
                }
                if (!raw && text[i] == '\\') {
                    i += 2
                    continue
                }
                if (templates && text.startsWith("\${", i)) {
                    i = (closing(text, i + 1) ?: return text.length) + 1
                    continue
                }
                i++
            }
            return text.length
        }

        /** Index of the bracket closing the `(` or `{` at [open], literals skipped; null when it does not close. */
        fun closing(text: String, open: Int): Int? {
            val openChar: Char = text.getOrNull(open) ?: return null
            val closeChar = when (openChar) {
                '(' -> ')'
                '{' -> '}'
                else -> return null
            }
            var depth = 0
            var i = open
            while (i < text.length) {
                val c = text[i]
                if (c == '"' || c == '\'') {
                    i = literalEnd(text, i)
                    continue
                }
                when (c) {
                    openChar -> depth++
                    closeChar -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return null
        }

        /** The one `fun [name](` in [text]: its parameter list's open and close index; null unless exactly one. */
        fun declaration(text: String, name: String): Pair<Int, Int>? {
            val found = Regex("""\bfun\s+${Regex.escape(name)}\s*\(""").findAll(text).toList()
            if (found.size != 1) return null
            val open = found.single().range.last
            val close = closing(text, open) ?: return null
            return open to close
        }

        fun paramsRange(text: String, name: String): IntRange? = declaration(text, name)?.let { (open, close) -> (open + 1) until close }

        /** The block body of the one `fun [name]`, braces excluded; null for an expression body or none. */
        fun bodyRange(text: String, name: String): IntRange? {
            val (_, close) = declaration(text, name) ?: return null
            var i = close + 1
            while (i < text.length && text[i] != '{' && text[i] != '=') i++
            if (i >= text.length || text[i] != '{') return null
            val end = closing(text, i) ?: return null
            return (i + 1) until end
        }

        // ---- the locks: each returns its violations, empty for an accepted tree ----

        /** S1 (E5a1, API r2 §5): AlertViewModel is named by its own file only; AlertSection names no MAX_COUNT at all. */
        fun s1ViewModel(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val named = files(code, NAME_VM)
            if (named != setOf(VM)) v += "S1-files ${named.sorted()}"
            val aliases = files(code, """\bimport\s+[\w.`]*\bAlertViewModel\b`?\s+as\b""")
            if (aliases.isNotEmpty()) v += "S1-alias ${aliases.sorted()}"
            val maxCount = count(code[SECTION].orEmpty(), """\bMAX_COUNT\b""")
            if (maxCount != 0) v += "S1-section-max-count $maxCount"
            return v
        }

        /**
         * S1 (E5a1, API r2 §5): each bank Composable is declared once in its own file. AlertSection is called directly exactly
         * once, from the section holder, and named nowhere else. S7 E5b3b (s7e5b3_api_agreed.r1.md D8): so is AlertAddSheet.
         */
        fun s1Composables(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((name, home, callers) in listOf(
                Triple("AlertSection", SECTION, mapOf(HOLDER to 1)),
                Triple("AlertAddSheet", SHEET, mapOf(HOLDER to 1)))) {
                val declared = occurrences(code, """\bfun\s+$name\s*\(""")
                if (declared != mapOf(home to 1)) v += "S1-declaration $name $declared"
                val calls = code.mapValues { (_, text) -> count(text, """\b$name\s*[({]""") - count(text, """\bfun\s+$name\s*\(""") }
                    .filterValues { it != 0 }
                if (calls != callers) v += "S1-call $name $calls"
                val references = files(code, """::\s*$name\b""")
                if (references.isNotEmpty()) v += "S1-function-reference $name ${references.sorted()}"
                val named = occurrences(code, """\b$name\b""")
                if (named != mapOf(home to 1) + callers) v += "S1-named $name $named"
            }
            return v
        }

        /** S1: the new files' explicit inventory: no ViewModel, reflection, class loader or screen entry. */
        fun s1Entry(code: Map<String, String>): List<String> = NEW_FILES.flatMap { path ->
            val text = code[path] ?: return@flatMap listOf("S1-entry-missing $path")
            ENTRY_APIS.filter { (_, pattern) -> Regex(pattern).containsMatchIn(text) }.map { (tag, _) -> "S1-entry-$tag $path" }
        }

        /** S2: ProcessLifecycleOwner's file set; one observer, added in the registry's start, on a lazily read lifecycle. */
        fun s2Lifecycle(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val named = files(code, """\bProcessLifecycleOwner\b""")
            if (named != setOf(TOPIC_OWNER, REGISTRY)) v += "S2-files ${named.sorted()}"
            val registry = code[REGISTRY].orEmpty()
            val observers = OBSERVE.findAll(registry).count()
            if (observers != 1) v += "S2-observers $observers"
            val start = bodyRange(registry, "start")
            if (start == null || OBSERVE.findAll(registry.substring(start)).count() != 1) v += "S2-observer-outside-start"
            if (count(registry, """\bProcessLifecycleOwner\s*\.\s*get\s*\(\s*\)""") != 1 ||
                count(registry, """\{\s*ProcessLifecycleOwner\s*\.\s*get\s*\(\s*\)\s*\.\s*lifecycle\s*\}""") != 1) v += "S2-lifecycle-supplier"
            val observing = Regex("""\baddObserver\b|\bremoveObserver\b|\bLifecycle\w*Observer\b|\bLocalLifecycleOwner\b|\brepeatOnLifecycle\b""")
            for (path in NEW_FILES - REGISTRY) if (observing.containsMatchIn(code[path].orEmpty())) v += "S2-other-observer $path"
            return v
        }

        /** S3 (API r3 §7 text; S7 E5c): deliverPush, deliverTap, onSyncAlerts and onBankPush: declarations, calls, references, aliases. */
        fun s3Hook(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val registry = code[REGISTRY].orEmpty()
            val fcm = code[FCM].orEmpty()
            val activity = code[ACTIVITY].orEmpty()
            val owner = code[OWNER].orEmpty()
            val call = """\bAlertFamilyRefresherRegistry\s*\.\s*deliverPush\s*\("""
            val deliverFiles = files(code, """\bdeliverPush\b""")
            if (deliverFiles != setOf(FCM, REGISTRY)) v += "S3-deliver-files ${deliverFiles.sorted()}"
            if (count(registry, """\bdeliverPush\b""") != 1 || count(registry, """\bfun\s+deliverPush\s*\(""") != 1) v += "S3-deliver-declaration"
            val companion = Regex("""\bcompanion\s+object\b[^{]*\{""").find(registry)
            val companionClose = companion?.let { closing(registry, it.range.last) }
            fun inCompanion(name: String): Boolean {
                val declared = Regex("""\bfun\s+$name\s*\(""").find(registry)
                return companion != null && companionClose != null && declared != null && declared.range.first in companion.range.last..companionClose
            }
            if (!inCompanion("deliverPush")) v += "S3-deliver-companion"
            if (count(fcm, """\bdeliverPush\b""") != 1 || count(fcm, call) != 1) v += "S3-deliver-fcm"
            val receive = bodyRange(fcm, "onMessageReceived")
            if (receive == null || count(fcm.substring(receive), call) != 1) v += "S3-deliver-outside-receive"
            val references = files(code, """::\s*(?:deliverPush|deliverTap|onSyncAlerts|onBankPush)\b""")
            if (references.isNotEmpty()) v += "S3-function-reference ${references.sorted()}"
            val syncFiles = files(code, """\bonSyncAlerts\b""")
            if (syncFiles != setOf(REGISTRY)) v += "S3-sync-files ${syncFiles.sorted()}"
            val syncCall = """\.\s*onSyncAlerts\s*\(\s*\)"""
            if (count(registry, """\bonSyncAlerts\b""") != 2 || count(registry, """\bfun\s+onSyncAlerts\s*\(""") != 1 ||
                count(registry, syncCall) != 1) v += "S3-sync-tokens"
            val deliver = bodyRange(registry, "deliverPush")
            if (deliver == null || count(registry.substring(deliver), syncCall) != 1) v += "S3-sync-outside-deliver"
            if (deliver == null || GUARDS.none { it.matches(squash(registry.substring(deliver))) }) v += "S3-guard"
            if (count(registry, "\\bconst\\s+val\\s+SYNC_ALERTS\\s*=\\s*\"sync_alerts\"") != 1) v += "S3-sync-const"
            if (count(registry, "\\bconst\\s+val\\s+RATE_ALERT\\s*=\\s*\"rate_alert\"") != 1) v += "S3-bank-const"
            // S7 E5c: the tap twin, called once from the OFF parser and nowhere else.
            val tapCall = """\bAlertFamilyRefresherRegistry\s*\.\s*deliverTap\s*\("""
            val tapFiles = files(code, """\bdeliverTap\b""")
            if (tapFiles != setOf(ACTIVITY, REGISTRY)) v += "S3-tap-files ${tapFiles.sorted()}"
            if (count(registry, """\bdeliverTap\b""") != 1 || count(registry, """\bfun\s+deliverTap\s*\(""") != 1) v += "S3-tap-declaration"
            if (!inCompanion("deliverTap")) v += "S3-tap-companion"
            if (count(activity, """\bdeliverTap\b""") != 1 || count(activity, tapCall) != 1) v += "S3-tap-activity"
            val parser = bodyRange(activity, "parseAlertEventFromIntent")
            if (parser == null || count(activity.substring(parser), tapCall) != 1) v += "S3-tap-outside-parser"
            val tap = bodyRange(registry, "deliverTap")
            if (tap == null || TAP_GUARDS.none { it.matches(squash(registry.substring(tap))) }) v += "S3-tap-guard"
            // S7 E5c: onBankPush is the adapter's no-op default, the registry's main hand-over and the bank owner's override only.
            val bankFiles = files(code, """\bonBankPush\b""")
            if (bankFiles != setOf(REGISTRY, OWNER)) v += "S3-bank-files ${bankFiles.sorted()}"
            val bankCall = """\.\s*onBankPush\s*\("""
            if (count(registry, """\bonBankPush\b""") != 6 || count(registry, """\bfun\s+onBankPush\s*\(""") != 2 ||
                count(registry, bankCall) != 4) v += "S3-bank-tokens"
            if (deliver == null || count(registry.substring(deliver), bankCall) != 1) v += "S3-bank-outside-deliver"
            if (tap == null || count(registry.substring(tap), bankCall) != 2) v += "S3-bank-outside-tap"
            if (count(registry, """\bpostToMain\s*\{\s*dispatch\s*\{\s*\(\s*it\s+as\?\s+AlertFamilyAdapter\s*\)\s*\?\.\s*onBankPush\s*\(\s*id\s*,\s*isRepeat\s*\)\s*\}\s*\}""") != 1) v += "S3-bank-dispatch"
            if (count(owner, """\bonBankPush\b""") != 1 || count(owner, """\boverride\s+fun\s+onBankPush\s*\(""") != 1) v += "S3-bank-owner"
            val aliases = files(code, """\bimport\s+[\w.`]*\b(?:AlertFamilyRefresherRegistry|deliverPush|deliverTap|onSyncAlerts|onBankPush)\b`?\s+as\b""")
            if (aliases.isNotEmpty()) v += "S3-alias ${aliases.sorted()}"
            val registryFiles = files(code, """\bAlertFamilyRefresherRegistry\b""")
            if (registryFiles != setOf(REGISTRY, APP, FCM, ACTIVITY)) v += "S3-registry-files ${registryFiles.sorted()}"
            return v
        }

        /** S8-6. The OFF consumer exits at parser entry, ahead of the S7 E5c bank route, lazy tap and event. */
        fun tActivity(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val activity = code[ACTIVITY].orEmpty()
            if (count(activity, """\balertFamiliesProvider\b""") != 4 ||
                count(activity, """@Inject\s+internal\s+lateinit\s+var\s+alertFamiliesProvider\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""") != 1) {
                v += "T-provider"
            }
            val range = bodyRange(activity, "parseAlertEventFromIntent") ?: return v + "T-parser-missing"
            val flat = squash(activity.substring(range))
            val head = "if (intent == null) return null "
            if (!flat.startsWith("$head$COMPARISON_TAP $SOURCE_TAP val type = ") ||
                occurrences(code, """\bconsumeComparisonTap\b""") != mapOf(ACTIVITY to 1, REGISTRY to 1)) v += "T-comparison-parser"
            if (!flat.contains("$SOURCE_TAP val type = ") ||
                occurrences(code, """\bconsumeSourceTap\b""") != mapOf(ACTIVITY to 1, REGISTRY to 1)) v += "T-source-parser"
            if (TAP_HOOK.findAll(flat).count() != 1) v += "T-hook"
            val order = listOf("val route = AlertPushRouting.route(type, settingId) ?: return null", "AlertFamilyRefresherRegistry.deliverTap(",
                "return route.event").map { flat.indexOf(it) }
            val bankEnd = "val route = AlertPushRouting.route(type, settingId) ?: return null <bank-tap> return route.event"
            if (order.any { it < 0 } || order != order.sorted() || !TAP_HOOK.replace(flat, "<bank-tap>").endsWith(bankEnd)) v += "T-order $order"
            for (other in listOf("onCreate", "onNewIntent", "parsePendingAlertEvent", "handleNotificationIntent", "clearNotificationExtras")) {
                val body = bodyRange(activity, other)?.let { activity.substring(it) }.orEmpty()
                if (Regex("""\b(?:deliverTap|consumeComparisonTap|consumeSourceTap|alertFamiliesProvider)\b""").containsMatchIn(body)) v += "T-elsewhere $other"
            }
            for ((name, action) in listOf("parsePendingAlertEvent" to "_pendingAlertEvent.value = event",
                    "handleNotificationIntent" to "alertEventBusProvider.get().emit(event)")) {
                val shell = bodyRange(activity, name)?.let { squash(activity.substring(it)) }.orEmpty()
                if (!shell.endsWith("} else { val event = parseAlertEventFromIntent(intent) ?: return $action clearNotificationExtras(intent) }")) {
                    v += "T-clear-order $name"
                }
            }
            if (count(activity, """\bparseAlertEventFromIntent\b""") != 3) v += "T-parser-calls"
            if (count(activity, """\bremoveExtra\s*\(\s*FXiMessagingService\s*\.\s*EXTRA_IS_REPEAT\s*\)""") != 1) v += "T-clear"
            if (count(activity, """\bgetStringExtra\s*\(\s*FXiMessagingService\s*\.\s*EXTRA_IS_REPEAT\s*\)""") != 1 ||
                Regex("\"is_repeat\"").containsMatchIn(activity)) v += "T-key"
            return v
        }

        /** S3: the default loggers and every catch boundary in the registry; every log call in the new files is fixed. */
        fun s3Loggers(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val registry = code[REGISTRY].orEmpty()
            val deliverParams = paramsRange(registry, "deliverPush")
            val tapParams = paramsRange(registry, "deliverTap")
            val defaults = REPORT_DEFAULT.findAll(registry).toList()
            if (defaults.size != 3) v += "S3-logger-defaults ${defaults.size}"
            val messages = mutableMapOf<String, String>()
            for (parameter in defaults) {
                val where = when {
                    deliverParams != null && parameter.range.first in deliverParams -> "deliverPush"
                    tapParams != null && parameter.range.first in tapParams -> "deliverTap"
                    else -> "registry"
                }
                val close = closing(registry, parameter.range.last)
                val lambda = if (close == null) "" else squash(registry.substring(parameter.range.last, close + 1))
                val match = LOG_DEFAULT.matchEntire(lambda)
                if (match == null) v += "S3-logger-form $where $lambda" else messages[where] = match.groupValues[1]
            }
            if (messages != mapOf("registry" to REGISTRY_MESSAGE, "deliverPush" to DELIVERY_MESSAGE, "deliverTap" to TAP_MESSAGE)) {
                v += "S3-logger-message $messages"
            }
            if (CONST_TAG.findAll(registry).count() != 1) v += "S3-logger-tag"
            val logCalls = LOG_CALL.findAll(registry).count()
            // Six registry log calls: three defaults plus fixed catches in acceptSource, deliverComparisonPush and consumeComparisonTap.
            if (logCalls != 6) v += "S3-logger-count $logCalls"
            for (path in NEW_FILES) {
                val text = code[path].orEmpty()
                for (log in LOG_CALL.findAll(text)) {
                    val close = closing(text, log.range.last)
                    val args = if (close == null) "?" else squash(text.substring(log.range.last + 1, close))
                    if (!FIXED_LOG_ARGS.matches(args)) v += "S3-logger-payload $path $args"
                }
            }
            val handlers = CATCH.findAll(registry).toList()
            val fixed = mapOf("acceptSource" to SOURCE_LOG,
                "deliverComparisonPush" to """Log.w(TAG, "comparison alert delivery failed")""",
                "consumeComparisonTap" to """Log.w(TAG, "comparison alert tap delivery failed")""")
            val fixedHandlers = fixed.map { (name, log) ->
                val span = bodyRange(registry, name)
                val handler = handlers.singleOrNull { span != null && it.range.first in span }
                if (handler == null) v += "S3-source-catch"
                handler to log
            }.toMap()
            if (handlers.size - fixed.size < 4) v += "S3-catch-count"
            for (handler in handlers) {
                val close = closing(registry, handler.range.last)
                val inner = if (close == null) "?" else registry.substring(handler.range.last + 1, close)
                val expected = fixedHandlers[handler]
                if (expected != null) {
                    if (handler.groupValues[1] != "Exception" || inner.trim() != expected) v += "S3-source-catch"
                    continue
                }
                val calls = CALL.findAll(inner).map { it.groupValues[1] }.filter { it !in KEYWORDS }.toList()
                if (handler.groupValues[1] != "Exception" || calls != listOf("reportFailure")) v += "S3-catch ${handler.groupValues[1]} $calls"
            }
            return v
        }

        /** S8-6. The exact source branch returns before the bank emit, gated hook and guarded post. */
        fun pFcm(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val fcm = code[FCM].orEmpty()
            val range = bodyRange(fcm, "onMessageReceived") ?: return listOf("P-receive-missing")
            val body = fcm.substring(range)
            val flat = squash(body)
            if (!flat.startsWith("if (!ReleaseAdmission.isOpen) return ")) v += "P-gate-first"
            val emit = """\.\s*emit\s*\("""
            if (count(body, emit) != 1 || count(fcm, emit) != 1) v += "P-emit"
            if (!Regex("""\bval type = message\.data\["type"] \?: return\b""").containsMatchIn(flat)) v += "P-raw-type"
            if (HOOK.findAll(flat).count() != 1) v += "P-gate-expression"
            if (!Regex("""\.emit\([^()]*\) AlertFamilyRefresherRegistry\.deliverPush\(""").containsMatchIn(flat)) v += "P-after-emit"
            if (count(fcm, """\balertFamiliesProvider\b""") != 4 ||
                count(fcm, """@Inject\s+internal\s+lateinit\s+var\s+alertFamiliesProvider\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""") != 1) {
                v += "P-provider"
            }
            if (Regex("""\btry\b|\bcatch\b""").containsMatchIn(body)) v += "P-catch"
            // S7 E5c: the app-made notification carries the raw is_repeat, only when present, under the one shared key constant.
            if (count(fcm, """\bmessage\s*\.\s*data\s*\[\s*EXTRA_IS_REPEAT\s*]\s*\?\.\s*let\s*\{\s*putExtra\s*\(\s*EXTRA_IS_REPEAT\s*,\s*it\s*\)\s*\}""") != 1 ||
                count(fcm, "\\bconst\\s+val\\s+EXTRA_IS_REPEAT\\s*=\\s*\"is_repeat\"") != 1 || count(fcm, "\"is_repeat\"") != 2) v += "P-repeat-extra"
            val typeRoute = """val type = message.data["type"] ?: return val route = AlertPushRouting.route(type, message.data["setting_id"]) ?: return"""
            if (!flat.contains("$COMPARISON_PUSH $typeRoute") ||
                occurrences(code, """\bdeliverComparisonPush\b""") != mapOf(FCM to 1, REGISTRY to 1)) v += "P-comparison-branch"
            if (!flat.contains("$typeRoute $SOURCE_PUSH $BANK_EMIT") ||
                occurrences(code, """\bdeliverSourcePush\b""") != mapOf(FCM to 1, REGISTRY to 1)) v += "P-source-branch"
            if (!HOOK.replace(flat, "<bank-push>").endsWith("$BANK_EMIT <bank-push> $BANK_BANNER")) v += "P-order"
            return v
        }

        /**
         * S11: the agreed production `@Inject` delegations, squashed (API r1 §3–§6; r2/r3 §4–§6; S7 E5a2 API r3 §4.1 mutations; S7 E5b1 API §6
         * create·edit; S7 E5b2a API §2.4 foreground hook; S7 E5b4b API §2.5 the memory's Add hand-over and its fixed-message report).
         */
        val INJECT_FORMS: Map<String, String> by lazy {
            mapOf(
                OWNER to """@Inject constructor(repository: AlertRepository, access: AlertSettingsAccess, floor: AlertRateLimitFloor,
                    coordinator: PremiumAccessCoordinator, topic: TopicRuntimeOwner, memory: LastSelectedBankMemory) : this({ owner, admitted -> repository.getSettings(owner, admitted) },
                    access, floor, AlertRetryPolicy(), SystemClock::elapsedRealtime, { Random.nextDouble(-0.2, 0.2) },
                    CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, coordinator.state, coordinator.accessRevisions,
                    topic.graphHost.map { it != null },
                    { owner, id, enabled, admitted -> repository.updateSettingPartial(owner, id, AlertSettingUpdateRequest(isEnabled = enabled), admitted) },
                    { owner, id, admitted -> repository.deleteSetting(owner, id, admitted) },
                    { owner, request, admitted -> repository.createSetting(owner, request, admitted) },
                    { owner, id, request, admitted -> repository.updateSettingPartial(owner, id, request, admitted) },
                    rememberAddedBank = memory::acceptAddedBank,
                    reportRememberFailure = { Log.w(TAG, "Last selected bank not accepted") })""",
                COMPARISON_OWNER to """@Inject constructor(repository: AlertRepository, access: AlertSettingsAccess,
                    tokens: AuthTokenProvider, deletions: DeletionAdmissionStore, coordinator: PremiumAccessCoordinator) : this(
                    comparisonSettingsGet(repository), access,
                    GraphProtectedAdmission(tokens::currentIdentityFence, deletions).let { protectedAdmission ->
                        { tokens.currentIdentityFence()?.takeIf { ReleaseAdmission.isOpen && protectedAdmission() && tokens.currentIdentityFence() == it } }
                    },
                    coordinator.state, coordinator.accessRevisions, CoroutineScope(SupervisorJob() + Dispatchers.Main),
                    { delay(it) }, { Random.nextDouble(-0.2, 0.2) }, comparisonCreate(repository), comparisonUpdate(repository), comparisonDelete(repository))""",
                SOURCE_OWNER to """@Inject constructor(repository: AlertRepository, access: AlertSettingsAccess, floor: AlertRateLimitFloor,
                    coordinator: PremiumAccessCoordinator) : this(
                    { owner, admitted -> repository.getSourceSettings(owner, SourceAlertRowPolicy.SETTINGS_ASSET, admitted) },
                    access, floor, AlertRetryPolicy(), SystemClock::elapsedRealtime, { Random.nextDouble(-0.2, 0.2) },
                    CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, coordinator.state, coordinator.accessRevisions,
                    productionSourceCreate(repository), productionSourceEdit(repository), productionSourceDelete(repository))""",
                ACCESS to """@Inject constructor(tokens: AuthTokenProvider, topic: TopicRuntimeOwner, uses: TopicUseAuthority,
                    coordinator: PremiumAccessCoordinator, deletions: DeletionAdmissionStore) : this(tokens::currentIdentityFence,
                    topic.currentAccessFence, uses, { coordinator.state.value }, { ReleaseAdmission.isOpen },
                    GraphProtectedAdmission(tokens::currentIdentityFence, deletions))""",
                FLOOR to """@Inject constructor() : this(SystemClock::elapsedRealtime)""",
                REGISTRY to """@Inject constructor(bank: Provider<BankAlertSettingsOwner>, source: Provider<SourceAlertSettingsOwner>,
                    comparison: Provider<ComparisonAlertSettingsOwner>,
                    coordinator: PremiumAccessCoordinator, tokens: AuthTokenProvider, capability: Provider<NotificationCapabilityMonitor>) :
                    this(listOf({ bank.get() }, { source.get() }, { comparison.get() }),
                    { ProcessLifecycleOwner.get().lifecycle },
                    { action -> Handler(Looper.getMainLooper()).post { action() }; Unit }, coordinator.state, tokens::currentIdentityFence,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main), foregroundHooks = listOf({ capability.get().onForeground() }))"""
            ).mapValues { squash(it.value) }
        }

        /** The one `@Inject constructor(...) : this(...)` of [text], from `@Inject` to the delegation's close; null unless exactly one. */
        fun injectDelegation(text: String): String? {
            val found = Regex("""@Inject\s+constructor\s*\(""").findAll(text).toList()
            if (found.size != 1) return null
            val open = found.single().range.last
            val close = closing(text, open) ?: return null
            val delegate = Regex("""^\s*:\s*this\s*\(""").find(text.substring(close + 1)) ?: return null
            val end = closing(text, close + 1 + delegate.range.last) ?: return null
            return text.substring(found.single().range.first, end + 1)
        }

        /**
         * S11 (S8-5a contract §2.2): the source owner's three production write delegations, squashed. The `@Inject` constructor
         * and the wire rig both use them, so a change here is a change on the wire the rig observes.
         */
        val SOURCE_WRITE_FORMS: Map<String, String> by lazy {
            mapOf(
                "productionSourceCreate" to """internal fun productionSourceCreate(
                    repository: AlertRepository
                ): suspend (AuthIdentityFence, SourceAlertSettingRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting> =
                    { owner, request, admitted -> repository.createSourceSetting(owner, request, admitted) }""",
                "productionSourceEdit" to """internal fun productionSourceEdit(
                    repository: AlertRepository
                ): suspend (AuthIdentityFence, Int, SourceAlertSettingUpdateRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting> =
                    { owner, id, request, admitted -> repository.updateSourceSetting(owner, id, request, admitted) }""",
                "productionSourceDelete" to """internal fun productionSourceDelete(
                    repository: AlertRepository
                ): suspend (AuthIdentityFence, Int, () -> Boolean) -> AuthBoundResult<Unit> =
                    { owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted) }"""
            ).mapValues { squash(it.value) }
        }

        /** The three source writes, each with the one delegation function that may call it. */
        val COMPARISON_WRITE_FORMS = mapOf(
            "comparisonCreate" to squash("""internal fun comparisonCreate(repository: AlertRepository):
    suspend (AlertSettingsUse, ComparisonAlertRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting> =
    { use, request, guard -> repository.createComparisonAlert(use.identity, request, useAdmitted = guard) }"""),
            "comparisonUpdate" to squash("""internal fun comparisonUpdate(repository: AlertRepository):
    suspend (AlertSettingsUse, Int, ComparisonAlertUpdateRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting> =
    { use, id, request, guard -> repository.updateComparisonAlert(use.identity, id, request, useAdmitted = guard) }"""),
            "comparisonDelete" to squash("""internal fun comparisonDelete(repository: AlertRepository):
    suspend (AlertSettingsUse, Int, () -> Boolean) -> AuthBoundResult<Unit> =
    { use, id, guard -> repository.deleteComparisonAlert(use.identity, id, useAdmitted = guard) }""")
        )

        val SOURCE_WRITES = listOf(
            "createSourceSetting" to "productionSourceCreate",
            "updateSourceSetting" to "productionSourceEdit",
            "deleteSourceSetting" to "productionSourceDelete")

        /** The one `internal fun [name](...)` of [text] with its lambda body, from `internal` to the lambda's close; null unless exactly one. */
        fun sourceWriteDelegation(text: String, name: String): String? {
            val found = Regex("""\binternal\s+fun\s+$name\s*\(""").findAll(text).toList()
            if (found.size != 1) return null
            val close = closing(text, found.single().range.last) ?: return null
            val body = Regex("""^[^{}=]*=\s*\{""").find(text.substring(close + 1)) ?: return null
            val end = closing(text, close + 1 + body.range.last) ?: return null
            return text.substring(found.single().range.first, end + 1)
        }

        /** S11: the five agreed delegations, the owners' 1 s default recheck periods, the source callback and the route-guarded banner call. */
        fun s11Wiring(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((path, form) in INJECT_FORMS) {
                if (code[path]?.let(::injectDelegation)?.let(::squash) != form) v += "S11-inject $path"
            }
            val comparison = code[COMPARISON_OWNER].orEmpty()
            for ((name, form) in COMPARISON_WRITE_FORMS) {
                if (sourceWriteDelegation(comparison, name)?.let(::squash) != form) v += "S11-comparison-write $name"
            }
            val get = "{ use, guard -> repository.getComparisonAlerts(use.identity, tab = null, useAdmitted = guard) }"
            if (count(squash(comparison), Regex.escape(squash(get))) != 1) v += "S11-comparison-get"
            val owner = code[OWNER].orEmpty()
            // S7 E5b4b: the two hand-over parameters follow the recheck period, each with its inert default for tests.
            if (count(owner, """\bprivate\s+val\s+accessRecheckMillis\s*:\s*Long\s*=\s*(?:1_000|1000)\s*,\s*""" +
                    """private\s+val\s+rememberAddedBank\s*:\s*\(\s*AuthIdentityFence\s*,\s*String\s*\)\s*->\s*Unit\s*=\s*\{\s*_\s*,\s*_\s*->\s*\}\s*,\s*""" +
                    """private\s+val\s+reportRememberFailure\s*:\s*\(\s*Throwable\s*\)\s*->\s*Unit\s*=\s*\{\s*\}\s*,?\s*\)""") != 1 ||
                count(owner, """\baccessRecheckMillis\b""") < 2) {
                v += "S11-recheck"
            }
            // S7 E5b4b API §2.5: the production constructor marks the memory's consumer bound once, as its whole body.
            val delegation = injectDelegation(owner)
            val afterDelegation = delegation?.let { owner.substring(owner.indexOf(it) + it.length) }.orEmpty()
            if (!Regex("""^\s*\{\s*memory\s*\.\s*markConsumerBound\s*\(\s*\)\s*\}""").containsMatchIn(afterDelegation) ||
                count(owner, """\bmarkConsumerBound\b""") != 1) {
                v += "S11-bound"
            }
            // S8-3: the source owner's production recheck period is its 1 s default and the GET's asset is the exchange one.
            val sourceOwner = code[SOURCE_OWNER].orEmpty()
            if (count(sourceOwner, """\bprivate\s+val\s+accessRecheckMillis\s*:\s*Long\s*=\s*(?:1_000|1000)\s*\)""") != 1) v += "S11-source-recheck"
            // S8-5a: each source write's delegation function as agreed, its request's own use check passed on unchanged.
            for ((name, form) in SOURCE_WRITE_FORMS) {
                if (sourceWriteDelegation(sourceOwner, name)?.let(::squash) != form) v += "S11-source-write $name"
            }
            if (count(code[ROW_POLICY].orEmpty(), "\\bconst\\s+val\\s+SETTINGS_ASSET\\s*=\\s*\"usdt-krw\"") != 1) v += "S11-source-asset"
            val fcm = code[FCM].orEmpty()
            val receive = bodyRange(fcm, "onMessageReceived")?.let { squash(fcm.substring(it)) }.orEmpty()
            // Four occurrences: the declaration and the comparison, source and bank banner calls.
            if (!receive.contains(COMPARISON_PUSH) || !receive.contains(SOURCE_PUSH) || !receive.endsWith(BANK_BANNER) ||
                count(fcm, """\bshowAlertNotification\b""") != 4) {
                v += "S11-banner"
            }
            return v
        }

        /** N1-A / S4: onCreate's gate precedes the one start, which passes the lazy registry start; the helper runs it. */
        fun aApplication(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val app = code[APP].orEmpty()
            val create = bodyRange(app, "onCreate") ?: return listOf("A-onCreate-missing")
            val body = app.substring(create)
            var gateEnd = -1
            val gateValue = Regex("""\bval\s+(\w+)\s*=\s*shouldStartAppOwnedServices\s*\(""").find(body)
            if (gateValue == null) v += "A-gate-value none" else {
                val close = closing(body, gateValue.range.last)
                val args = if (close == null) "" else squash(body.substring(gateValue.range.last + 1, close))
                val admissionValue = Regex("""\bval\s+(\w+)\s*=\s*ReleaseAdmission\s*\.\s*isOpen\b""").find(body)?.groupValues?.get(1)
                val admissionNames = listOfNotNull("ReleaseAdmission\\.isOpen", admissionValue?.let { Regex.escape(it) }).joinToString("|")
                val admitted = Regex("(?:releaseAdmissionOpen = )?(?:$admissionNames), (?:benchmarkNoData = )?BuildConfig\\.BENCHMARK_NO_DATA_MODE,?")
                if (!admitted.matches(args)) v += "A-gate-value $args"
                val gate = Regex("""\bif\s*\(\s*!\s*${Regex.escape(gateValue.groupValues[1])}\s*\)\s*\{""").find(body, gateValue.range.last)
                val gateClose = gate?.let { closing(body, it.range.last) }
                if (gate == null || gateClose == null || !Regex("""\breturn\b""").containsMatchIn(body.substring(gate.range.last, gateClose))) {
                    v += "A-gate-return"
                } else gateEnd = gateClose
            }
            val starts = code.mapValues { (_, text) -> count(text, """\bstartAppOwnedServices\s*\(""") - count(text, """\bfun\s+startAppOwnedServices\s*\(""") }
                .filterValues { it != 0 }
            if (starts != mapOf(APP to 1)) v += "A-start-calls $starts"
            val call = Regex("""\bstartAppOwnedServices\s*\(""").find(body)
            if (call == null || gateEnd < 0 || call.range.first < gateEnd) v += "A-gate-order"
            val callClose = call?.let { closing(body, it.range.last) }
            val args = if (call == null || callClose == null) "" else body.substring(call.range.last + 1, callClose)
            if (count(args, """\bstartAlertFamilies\s*=\s*\{\s*alertFamilies\s*\.\s*get\s*\(\s*\)\s*\.\s*start\s*\(\s*\)\s*\}""") != 1) v += "A-lambda"
            val tokens = count(app, """\balertFamilies\b""")
            if (tokens != 2) v += "A-tokens $tokens"
            if (count(app, """@Inject\s+internal\s+lateinit\s+var\s+alertFamilies\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""") != 1) v += "A-provider"
            val helper = bodyRange(app, "startAppOwnedServices")?.let { app.substring(it) }.orEmpty()
            val steps = listOf("launchRateMigration", "startAlertFamilies", "purgeRetiredStores").map { step ->
                Regex("""\b$step\s*\(\s*\)""").findAll(helper).map { it.range.first }.toList()
            }
            if (steps.any { it.size != 1 } || steps.map { it.single() }.let { it != it.sorted() }) v += "A-helper $steps"
            val params = paramsRange(app, "startAppOwnedServices")?.let { app.substring(it) }.orEmpty()
            if (count(params, """\bstartAlertFamilies\s*:\s*\(\s*\)\s*->\s*Unit\b""") != 1) v += "A-helper-parameter"
            return v
        }

        /** N1-A / S4: who names each new singleton (E5a1: the bank owner also by the section holder), scope, and the lazy bank owner. */
        fun aReach(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((name, expected) in listOf(
                "BankAlertSettingsOwner" to setOf(OWNER, REGISTRY, HOLDER),
                "AlertSettingsAccess" to setOf(ACCESS, OWNER, SOURCE_OWNER, COMPARISON_OWNER, SOURCE_HISTORY_ENTRY, REGISTRY,
                    BASE + "ui/alert/ComparisonAlertHistorySession.kt"),
                "AlertRateLimitFloor" to setOf(FLOOR, OWNER, SOURCE_OWNER, SOURCE_HISTORY_ENTRY),
                // S8-4a: the registry registers the source owner and the tether section reads it; the section projects with the policy.
                "ComparisonAlertSettingsOwner" to setOf(COMPARISON_OWNER, REGISTRY, BASE + "ui/alert/ComparisonAlertSection.kt"),
                "SourceAlertSettingsOwner" to setOf(SOURCE_OWNER, REGISTRY, SOURCE_SECTION),
                // S8-7: the free tether preview projects its quotes and lists its exchanges with the policy, and nothing more.
                "SourceAlertRowPolicy" to setOf(ROW_POLICY, SOURCE_OWNER, SOURCE_SECTION, SOURCE_HISTORY_HOLDER, REGISTRY,
                    BASE + "ui/free/FreeSourceAlertPreviewQuotes.kt", BASE + "ui/free/FreeSourceAlertPreviewSession.kt",
                    BASE + "ui/free/FreeSourceAlertPreviewSheet.kt"),
                "AlertFamilyRefresherRegistry" to setOf(REGISTRY, APP, FCM, ACTIVITY))) {
                val named = files(code, """\b$name\b""")
                if (named != expected) v += "A-reach $name ${named.sorted()}"
            }
            for ((name, path) in listOf("BankAlertSettingsOwner" to OWNER, "AlertSettingsAccess" to ACCESS,
                    "AlertRateLimitFloor" to FLOOR, "AlertFamilyRefresherRegistry" to REGISTRY, "SourceAlertSettingsOwner" to SOURCE_OWNER)) {
                val text = code[path].orEmpty()
                if (count(text, """@Singleton\s+(?:internal\s+)?class\s+$name\b""") != 1) v += "A-singleton $name"
                if (count(text, """@Inject\s+constructor\b""") != 1) v += "A-inject $name"
            }
            // S8-3: the source settings GET has one production caller, the source owner.
            val sourceGets = files(code, """\bgetSourceSettings\b""")
            if (sourceGets != setOf(REPOSITORY, SOURCE_OWNER)) v += "A-source-get ${sourceGets.sorted()}"
            // S8-5a: apart from its one declaration in the repository, each source write is named exactly once in production
            // code, inside its delegation function; any other receiver, alias, reference or internal call is a second use.
            for (delegate in COMPARISON_WRITE_FORMS.keys) {
                val write = delegate.removePrefix("comparison").lowercase() + "ComparisonAlert"
                if (occurrences(code, """\b$write\b""") != mapOf(COMPARISON_OWNER to 1, REPOSITORY to 2,
                        BASE + "data/remote/AuthenticatedApiService.kt" to 3) ||
                    occurrences(code, """\b$delegate\b""") != mapOf(COMPARISON_OWNER to 2)) v += "A-comparison-write $write"
            }
            for ((write, delegation) in SOURCE_WRITES) {
                val declared = count(code[REPOSITORY].orEmpty(), """\bsuspend\s+fun\s+$write\s*\(""")
                if (declared != 1) v += "A-source-write-declared $write"
                val uses = occurrences(code, """\b$write\b""").toMutableMap()
                uses[REPOSITORY] = (uses[REPOSITORY] ?: 0) - declared
                val used = uses.filterValues { it != 0 }
                if (used != mapOf(SOURCE_OWNER to 1)) v += "A-source-write $write $used"
                val body = sourceWriteDelegation(code[SOURCE_OWNER].orEmpty(), delegation)
                if (body == null || count(body, """\b$write\b""") != 1) v += "A-source-write-once $write"
                // The delegation function itself: declared and passed once, both in the source owner.
                val passed = occurrences(code, """\b$delegation\b""")
                if (passed != mapOf(SOURCE_OWNER to 2)) v += "A-source-delegation $delegation $passed"
            }
            val registry = code[REGISTRY].orEmpty()
            // S8-6. Pin each additional type name to its single approved use in this file.
            val bind = bodyRange(registry, "bindSourceUse")
            val binding = "@Inject internal fun bindSourceUse(topic: TopicRuntimeOwner, uses: TopicUseAuthority, access: AlertSettingsAccess)"
            val capture = squash("""
                captureSourceUse = capture@{
                    val fence = topic.currentAccessFence() ?: return@capture null
                    val lifetime = uses.acquire(fence) ?: return@capture null
                    AlertSettingsUse(fence.identity, fence, lifetime).takeIf(access::admits)
                }
            """)
            if (count(registry, """\bAlertSettingsAccess\b""") != 1 || bind == null ||
                !squash(registry.substring(0, bind.first)).endsWith("$binding {") ||
                squash(registry.substring(bind)) != capture) v += "A-source-access"
            val accept = bodyRange(registry, "acceptSource")
            val guard = squash("""
                if (source == null || asset == null || !SourceAlertRowPolicy.EXCHANGES.admits(source, asset)) return
                try { resolve().deliverSource(SourceAlertDelivery(source, asset, id, repeat), publish) } catch (
            """)
            if (count(registry, """\bSourceAlertRowPolicy\b""") != 1 || accept == null ||
                !squash(registry.substring(accept)).startsWith(guard)) v += "A-source-policy"
            if (count(registry, """\bstart\s*\(\s*\)""") != 1) v += "A-self-start"
            val provider = Regex("""\b(\w+)\s*:\s*Provider\s*<\s*BankAlertSettingsOwner\s*>""").findAll(registry).toList()
            val name = provider.singleOrNull()?.groupValues?.get(1)
            if (name == null || count(registry, """\b$name\s*\.\s*get\s*\(\s*\)""") != 1 ||
                count(registry, """\{\s*$name\s*\.\s*get\s*\(\s*\)\s*\}""") != 1) v += "A-lazy-owner"
            // S8-4a: the source owner, too, is resolved lazily and once.
            val sourceProvider = Regex("""\b(\w+)\s*:\s*Provider\s*<\s*SourceAlertSettingsOwner\s*>""").findAll(registry).toList()
            val sourceName = sourceProvider.singleOrNull()?.groupValues?.get(1)
            if (sourceName == null || count(registry, """\b$sourceName\s*\.\s*get\s*\(\s*\)""") != 1 ||
                count(registry, """\{\s*$sourceName\s*\.\s*get\s*\(\s*\)\s*\}""") != 1) v += "A-lazy-source-owner"
            val comparisonProvider = Regex("""\b(\w+)\s*:\s*Provider\s*<\s*ComparisonAlertSettingsOwner\s*>""").findAll(registry).toList()
            val comparisonName = comparisonProvider.singleOrNull()?.groupValues?.get(1)
            if (comparisonName == null || count(registry, """\b$comparisonName\s*\.\s*get\s*\(\s*\)""") != 1 ||
                count(registry, """\{\s*$comparisonName\s*\.\s*get\s*\(\s*\)\s*\}""") != 1) v += "A-lazy-comparison-owner"
            return v
        }

        /**
         * S8-5b (`R4c/S8/s8_5b_contract.agreed.r1.md` §4 "호출자 잠금"): the source owner's four outside writes are called once
         * each, by the tether section's production seam, in their exact delegating form; nothing else reaches the owner's writes.
         * The owner's own declarations and the bank owner's same-named API are not counted: only calls on the seam's owner are.
         */
        val SOURCE_ENTRY_FORMS = listOf(
            "override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)",
            "override fun editAlert(id: Int, edit: SourceAlertEdit) = owner.editAlert(id, edit)",
            "override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)",
            "override fun delete(id: Int) = owner.delete(id)")
        const val SOURCE_ENTRIES = """\b(?:addAlert|editAlert|setEnabled|delete)\b"""
        const val SOURCE_FACTORY_HEAD = "internal object ProductionSourceAlertSectionSourceFactory : SourceAlertSectionSourceFactory {"
        /** The seam, exactly: every member abstract, so no fake or implementation can leave a write to a default. */
        val SOURCE_SEAM = """internal interface SourceAlertSectionSource {
            val settings: StateFlow<SourceAlertSettingsState>
            val manualRefresh: StateFlow<ManualRefreshState>
            val selection: StateFlow<SourceSelectionSnapshot>
            suspend fun prepareSelection(identity: AuthIdentityFence)
            fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String)
            fun ensure()
            fun authoritative()
            fun manual()
            fun addAlert(draft: SourceAlertDraft): SourceAlertSaveRequest
            fun editAlert(id: Int, edit: SourceAlertEdit): SourceAlertSaveRequest
            fun setEnabled(id: Int, enabled: Boolean)
            fun delete(id: Int)
        }"""
        /** What the factory's one owner is used for, each once, after its one declaration. */
        val SOURCE_OWNER_USES = listOf("state", "manualRefresh", "ensure", "authoritative", "manual", "addAlert", "editAlert", "setEnabled", "delete")

        fun aSourceEntries(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val section = code[SOURCE_SECTION].orEmpty()
            // Who can hold the owner: its entry point is named, declared and called in the section only.
            val entryPoints = files(code, """\bSourceAlertSectionEntryPoint\b""")
            if (entryPoints != setOf(SOURCE_SECTION)) v += "A-source-entry-point ${entryPoints.sorted()}"
            val getters = occurrences(code, """\bsourceAlertSettingsOwner\b""")
            if (getters != mapOf(SOURCE_SECTION to 2)) v += "A-source-entry-getter $getters"
            // The registry holds the owner but calls none of its writes.
            if (count(code[REGISTRY].orEmpty(), """(?:\.|::)\s*$SOURCE_ENTRIES""") != 0) v += "A-source-entry registry"
            val head = section.indexOf(SOURCE_FACTORY_HEAD)
            val close = if (head < 0) null else closing(section, section.indexOf('{', head))
            if (close == null) return v + "A-source-entry factory"
            val factory = section.substring(head, close + 1)
            val flat = squash(factory)
            for (form in SOURCE_ENTRY_FORMS) {
                // Each form ends where the next member or the object's close begins: nothing chained or appended.
                if (count(flat, Regex.escape(squash(form)) + """(?= override\b| \})""") != 1) v += "A-source-entry form $form"
            }
            if (count(factory, """\bowner\s*\.\s*$SOURCE_ENTRIES""") != 4 || count(factory, """::\s*$SOURCE_ENTRIES""") != 0) v += "A-source-entry calls"
            if (count(flat, Regex.escape(squash("val owner = entryPoint.sourceAlertSettingsOwner()"))) != 1) v += "A-source-entry owner"
            // No alias: every use of the owner is one of its agreed members, each once.
            val uses = Regex("""\bowner\b(\s*\.\s*(\w+))?""").findAll(factory).map { it.groupValues[2] }.toList()
            if (uses.sorted() != (SOURCE_OWNER_USES + "").sorted()) v += "A-source-entry uses $uses"
            if (count(squash(section), Regex.escape(squash(SOURCE_SEAM))) != 1) v += "A-source-entry seam"
            // The section names the owner's type only in its import and its entry point.
            if (count(section, """\bSourceAlertSettingsOwner\b""") != 2) v += "A-source-entry type"
            // Outside the factory the section gets no owner and takes no write by reference: its holder calls the seam only.
            val rest = section.removeRange(head, close + 1)
            if (count(rest, """\.\s*sourceAlertSettingsOwner\s*\(""") != 0 || count(rest, """::\s*$SOURCE_ENTRIES""") != 0) v += "A-source-entry outside"
            return v
        }

        /** S5: no KRX in the code and strings of the new files. */
        fun s5Krx(code: Map<String, String>): List<String> = NEW_FILES.mapNotNull { path ->
            val text = code[path]
            when {
                text == null -> "S5-missing $path"
                KRX.containsMatchIn(text) -> "S5-krx $path"
                else -> null
            }
        }

        /** S6 / N1-Q: the surfaces the eight files in NEW_FILES never touch. */
        fun s6Surfaces(code: Map<String, String>): List<String> = NEW_FILES.flatMap { path ->
            val text = code[path] ?: return@flatMap listOf("S6-missing $path")
            FORBIDDEN.filter { (_, pattern) -> Regex(pattern).containsMatchIn(text) }.map { (tag, _) -> "S6-$tag $path" }
        }
    }
}
