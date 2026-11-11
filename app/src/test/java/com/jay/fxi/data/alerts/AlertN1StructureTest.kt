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
 * Everything is read from the comment-free code and strings of the five production roots
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
 *   AlertAddSheet is declared and never called or referenced; the six new N1 files reach no ViewModel, reflection, class
 *   loader or screen entry API.
 * - S2: ProcessLifecycleOwner is named by the topic owner and the registry only; the registry adds one observer, in start,
 *   on a lifecycle it reads lazily; no other new file observes a lifecycle.
 * - S3 / N1-P: FCM's one new statement is `deliverPush`, right after its one emit, behind the app-owned gate expression and
 *   after the early ReleaseAdmission return; `deliverPush` resolves the registry only for an exact `sync_alerts` with that
 *   gate open, inside an Exception boundary that only reports; nothing else calls, references or aliases it or
 *   `onSyncAlerts`; the default loggers write a fixed message, never the exception or a payload.
 * - S4 / N1-A: the registry starts only from FXiApplication's `startAlertFamilies`, which onCreate passes past its admission
 *   gate and the start helper runs once between the rate migration and the purge; the new singletons are reached only
 *   through the registry, except that the bank owner is also named by the section holder (E5a1), and the registry resolves
 *   the bank owner only in a supplier.
 * - S5 / S6 / N1-Q: the six new files name no KRX, read no snapshot authority, no alert bus, no permission prompt, no push
 *   token owner and declare no binding.
 * - S11 (gap battery r1): the four production `@Inject` delegations pass exactly the agreed suppliers, the owner's production
 *   recheck period is its 1 s default, and FCM keeps its one guarded banner call.
 *
 * Every lock is a function from the scanned sources to its violations: each test first shows the real tree clean, then feeds
 * the same function mutated trees it must reject, and comment-only or lookalike controls it must accept. Not this file's:
 * deliverPush's type × gate runs and the registry's runtime counts (AlertFamilyRefresherRegistryContractTest), the seven
 * starts' order and the closed-gate lambdas (FXiApplicationStartTest), the device paths, the ON service → registry → owner
 * integration that no allowed harness observes (left unverified, API r3 §7 N1-P), and N1-Q's existing KRX OFF/ON
 * pending-tap regression bundle (an exact-state full-suite run, not inherited). The implementation thread reads but does not
 * edit this file.
 */
class AlertN1StructureTest {

    @Test fun `S7N1_S00 the scan reads every production root comment-free in Kotlin and Java`() {
        val roots = S6Krx3bOffWiringContractTest.ROOTS
        assertTrue("premise(N1-S): the five production roots exist", roots.size == 5 && roots.all { File(it).isDirectory })
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

    @Test fun `S7E5A1_N1S02 AlertSection is declared once and called directly once by the section holder and AlertAddSheet by nobody`() {
        assertEquals("premise(E5a1 S1): the positive control finds AlertSection's declaration", mapOf(SECTION to 1),
            occurrences(code, """\bfun\s+AlertSection\s*\("""))
        assertEquals("premise(E5a1 S1): and AlertAddSheet's", mapOf(SHEET to 1), occurrences(code, """\bfun\s+AlertAddSheet\s*\("""))
        assertTrue("premise(E5a1 S1): the call pattern finds the holder's direct call", count(code.getValue(HOLDER), """\bAlertSection\s*[({]""") >= 1)
        assertEquals("E5a1 S1: AlertSection is mounted by the holder alone, once, and AlertAddSheet by nobody", emptyList<String>(), s1Composables(code))
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
        assertEquals("premise(N1-S S1): the six new files are scanned and non-empty", emptyList<String>(),
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

    @Test fun `S7N1_P01 FCM keeps its gate and its one emit and adds only the gated hook right after the emit`() {
        val fcm = code.getValue(FCM)
        assertNotNull("premise(N1-P): onMessageReceived has a block body", bodyRange(fcm, "onMessageReceived"))
        assertEquals("premise(N1-P): the existing route is there", 1, count(fcm, """\bAlertPushRouting\s*\.\s*route\s*\("""))
        assertEquals("N1-P / N1-A structure: gate first, raw type, one emit, the gated hook right after it, no catch", emptyList<String>(), pFcm(code))
    }

    @Test fun `S7N1_S07 deliverPush and onSyncAlerts each have one declaration and one call in the agreed place`() {
        val registry = code.getValue(REGISTRY)
        assertEquals("premise(N1-S S3): the registry declares deliverPush", 1, count(registry, """\bfun\s+deliverPush\s*\("""))
        assertEquals("premise(N1-S S3): and onSyncAlerts", 1, count(registry, """\bfun\s+onSyncAlerts\s*\("""))
        assertEquals("N1-S S3: the call and declaration inventory", emptyList<String>(), s3Hook(code))
    }

    @Test fun `S7N1_P02 deliverPush resolves only for an exact sync with the gate open inside an Exception boundary`() {
        val registry = code.getValue(REGISTRY)
        val body = bodyRange(registry, "deliverPush")
        assertNotNull("premise(N1-P): deliverPush has a block body", body)
        val flat = squash(registry.substring(body!!))
        assertTrue("N1-P / N1-A: the real body is an agreed form: $flat", GUARDS.any { it.matches(flat) })
        assertEquals("N1-P: the sync constant is the raw FCM type", 1, count(registry, "\\bconst\\s+val\\s+SYNC_ALERTS\\s*=\\s*\"sync_alerts\""))
        val boundary = "try {\n    resolve().onSyncAlerts()\n} catch (e: Exception) {\n    reportFailure()\n}"
        for ((label, form) in listOf(
            "an early return on either" to "if (type != SYNC_ALERTS || !gateOpen) return\n$boundary",
            "both inside one branch" to "if (type == SYNC_ALERTS && gateOpen) {\n    try { resolve().onSyncAlerts() } catch (_: Exception) { reportFailure() }\n}",
            "two early returns" to "if (type != SYNC_ALERTS) return\nif (!gateOpen) return\n$boundary")) {
            assertEquals("control(N1-P, $label): an agreed form passes", emptyList<String>(), s3Hook(withDeliverBody(form)))
        }
        for ((label, mutant) in listOf(
            "either condition is enough" to "if (type != SYNC_ALERTS && !gateOpen) return\n$boundary",
            "the gate ignored" to "if (type != SYNC_ALERTS) return\n$boundary",
            "any type" to "if (!gateOpen) return\n$boundary",
            "not an exact compare" to "if (!type.equals(SYNC_ALERTS, ignoreCase = true) || !gateOpen) return\n$boundary",
            "resolved before the guard" to "val registry = resolve()\nif (type != SYNC_ALERTS || !gateOpen) return\ntry { registry.onSyncAlerts() } catch (e: Exception) { reportFailure() }",
            "no boundary" to "if (type != SYNC_ALERTS || !gateOpen) return\nresolve().onSyncAlerts()",
            "a Throwable boundary" to "if (type != SYNC_ALERTS || !gateOpen) return\ntry { resolve().onSyncAlerts() } catch (e: Throwable) { reportFailure() }",
            "a silent boundary" to "if (type != SYNC_ALERTS || !gateOpen) return\ntry { resolve().onSyncAlerts() } catch (e: Exception) { }",
            "only the resolve guarded" to "if (type != SYNC_ALERTS || !gateOpen) return\nval registry = try { resolve() } catch (e: Exception) { reportFailure(); return }\nregistry.onSyncAlerts()")) {
            assertCaught("N1-P $label", "S3-guard", s3Hook(withDeliverBody(mutant)))
        }
        assertCaught("N1-P the sync constant changed", "S3-sync-const", s3Hook(editedLiteral(REGISTRY, "\"sync_alerts\"", "\"sync_alert\"")))
    }

    @Test fun `S7N1_S08 the hook locks catch a moved ungated eager extra aliased or declared but not called hook`() {
        fun hook(tree: Map<String, String>) = s3Hook(tree) + pFcm(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), hook(code))
        val fcm = code.getValue(FCM)
        val range = hookRange(fcm)
        val statement = fcm.substring(range)
        val emitLine = fcm.lastIndexOf('\n', fcm.indexOf(".emit(")) + 1
        assertTrue("mutation premise: the emit line precedes the hook", emitLine in 1..range.first)
        val beforeEmit = fcm.removeRange(range).let { it.substring(0, emitLine) + statement + "\n" + it.substring(emitLine) }
        val gate = "shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)"
        for ((label, tree, tag) in listOf(
            Triple("the hook before the emit", code + (FCM to beforeEmit), "P-after-emit"),
            Triple("a gate that is always open", hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, true) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("the release admission alone",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, ReleaseAdmission.isOpen) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("the no-data flag ignored",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(type, shouldStartAppOwnedServices(ReleaseAdmission.isOpen, false)) { alertFamiliesProvider.get() }" },
                "P-gate-expression"),
            Triple("the routed event for the raw type",
                hookReplaced { "AlertFamilyRefresherRegistry.deliverPush(route.event.toString(), $gate) { alertFamiliesProvider.get() }" }, "P-gate-expression"),
            Triple("an eager resolve",
                hookReplaced { "val eager = alertFamiliesProvider.get()\nAlertFamilyRefresherRegistry.deliverPush(type, $gate) { eager }" }, "P-gate-expression"),
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

    @Test fun `S7N1_S09 the default loggers write a fixed message without the exception or a payload`() {
        val registry = code.getValue(REGISTRY)
        assertEquals("premise(N1-S S3): two reportFailure defaults, the registry's and deliverPush's", 2, REPORT_DEFAULT.findAll(registry).count())
        assertTrue("premise(N1-S S3): the registry catches at least the supplier, the adapter and the delivery", CATCH.findAll(registry).count() >= 3)
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
            Triple("a third logger in the registry", appended(REGISTRY, "private fun note() { Log.w(TAG, \"note\") }"), "S3-logger-count"),
            Triple("a payload logged by another new file",
                appended(OWNER, "private fun note(e: Exception) { Log.w(TAG, \"failed\", e) }"), "S3-logger-payload"))) {
            assertCaught("N1-S S3 $label", tag, s3Loggers(tree))
        }
    }

    @Test fun `S7N1_A01 FXiApplication passes startAlertFamilies only past its admission gate and the helper runs it once before the purge`() {
        val app = code.getValue(APP)
        assertNotNull("premise(N1-A): onCreate has a block body", bodyRange(app, "onCreate"))
        assertNotNull("premise(N1-A): so has the start helper", bodyRange(app, "startAppOwnedServices"))
        assertEquals("N1-A structure / S4: gate-before-resolve in production onCreate", emptyList<String>(), aApplication(code))
    }

    @Test fun `S7E5A1_N1A02 the registry starts only from startAlertFamilies and the new singletons are reached only through it or for the bank owner the section holder`() {
        assertEquals("premise(N1-A): the registry is named by the Application and FCM", setOf(REGISTRY, APP, FCM),
            files(code, """\bAlertFamilyRefresherRegistry\b"""))
        assertTrue("premise(E5a1 A-reach): the section holder is scanned and declares its entry point",
            Regex("""@EntryPoint\b""").containsMatchIn(code.getValue(HOLDER)))
        assertEquals("N1-A / S4 with E5a1's holder: reach, scope and lazy resolution", emptyList<String>(), aReach(code))
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
            Triple("a floor per consumer", edited(FLOOR, """@Singleton\s+""", ""), "A-singleton AlertRateLimitFloor"))) {
            assertCaught("N1-A / S4 $label", tag, aApplication(tree) + aReach(tree))
        }
        assertCaught("N1-A / S4 FCM starting the registry", "P-provider", pFcm(hookReplaced { "$it\nalertFamiliesProvider.get().start()" }))
    }

    @Test fun `S7N1_S10 the six new files name no KRX in code or strings`() {
        assertTrue("premise(N1-S S5): the same pattern finds KRX where it is", KRX.containsMatchIn(code.getValue(KRX_SAMPLE)))
        assertEquals("N1-S S5", emptyList<String>(), s5Krx(code))
        assertCaught("N1-S S5 an identifier", "S5-krx $OWNER", s5Krx(appended(OWNER, "private val krxGate = 0")))
        assertCaught("N1-S S5 a string", "S5-krx $FLOOR", s5Krx(appended(FLOOR, "private val tag = \"KRX\"")))
        assertCaught("N1-S S5 an escaped string", "S5-krx $REGISTRY", s5Krx(appended(REGISTRY, "private val name = \"\\u004Brx\"")))
        assertEquals("control(N1-S S5): a comment is not a reference", emptyList<String>(), s5Krx(appended(POLICY, "// KRX contest\n/* krx */")))
    }

    @Test fun `S7N1_Q01 the new files read no snapshot authority alert bus permission prompt push token owner or binding`() {
        assertEquals("premise(N1-Q): the six new files are scanned", emptyList<String>(), NEW_FILES.filter { it !in code })
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
     * S11 (gap battery r1, wiring the behaviour tests cannot reach: they build through the primary constructors): the four
     * production `@Inject` delegations pass exactly the agreed suppliers — the monotonic clock, the [-0.2, 0.2) jitter, the
     * Main scope, the coordinator's live state and access revisions, the graph host, the published fence, the shared deletion
     * admission, the Main Handler post and the process lifecycle — the owner's production recheck period is its 1 s default,
     * and FCM keeps its banner guard as it was (N1-P: banner unchanged).
     */
    @Test fun `S7N1_S11 the production constructors wire the agreed suppliers and FCM keeps its banner guard`() {
        for (path in INJECT_FORMS.keys) {
            assertNotNull("premise(S11): $path has exactly one @Inject constructor delegation", injectDelegation(code.getValue(path)))
        }
        assertEquals("S11: the agreed production wiring", emptyList<String>(), s11Wiring(code))
    }

    /** S11's lock rejects each wiring mutant the gap hunt proposed and accepts the same delegation reformatted. */
    @Test fun `S7N1_S12 the S11 lock catches each wiring mutant and accepts a reformatted delegation`() {
        for ((label, tree, tag) in listOf(
            Triple("jitter [0, 1)", editedLiteral(OWNER, "Random.nextDouble(-0.2, 0.2)", "Random.nextDouble()"), "S11-inject $OWNER"),
            Triple("jitter [0, 1) spelled out", editedLiteral(OWNER, "Random.nextDouble(-0.2, 0.2)", "Random.nextDouble(0.0, 1.0)"), "S11-inject $OWNER"),
            Triple("a recheck period passed by production",
                editedLiteral(OWNER, "topic.graphHost.map { it != null }", "topic.graphHost.map { it != null }, 30_000"), "S11-inject $OWNER"),
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
        val reformatted = edited(OWNER, """\{\s*Random\.nextDouble\(-0\.2,\s*0\.2\)\s*\}""", "{\n            Random.nextDouble(\n                -0.2, 0.2\n            )\n        }")
        assertEquals("control(S11): the same delegation reformatted passes", emptyList<String>(), s11Wiring(reformatted))
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

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val VM = BASE + "ui/viewmodel/AlertViewModel.kt"
        val SECTION = BASE + "ui/alert/AlertSection.kt"
        val SHEET = BASE + "ui/alert/AlertAddSheet.kt"
        val TOPIC_OWNER = BASE + "data/remote/TopicRuntimeOwner.kt"
        val APP = BASE + "FXiApplication.kt"
        val FCM = BASE + "service/FXiMessagingService.kt"
        val KRX_SAMPLE = BASE + "data/graph/KrxGraphFence.kt"
        val PHASE = BASE + "data/alerts/AlertLoadPhase.kt"
        val POLICY = BASE + "data/alerts/AlertRetryPolicy.kt"
        val FLOOR = BASE + "data/alerts/AlertRateLimitFloor.kt"
        val ACCESS = BASE + "data/alerts/AlertSettingsAccess.kt"
        val REGISTRY = BASE + "data/alerts/AlertFamilyRefresherRegistry.kt"
        val OWNER = BASE + "data/alerts/BankAlertSettingsOwner.kt"
        /** The six new N1 production files (API r1 §2–§6). E5a1's section holder is deliberately not one of them. */
        val NEW_FILES = listOf(PHASE, POLICY, FLOOR, ACCESS, REGISTRY, OWNER)
        /** S7 E5a1's section holder (API r2 §2): AlertSection's one caller and, besides the owner and the registry, the owner's one namer. */
        val HOLDER = BASE + "ui/alert/BankAlertSection.kt"
        val ROUTE = BASE + "ui/premium/view/PremiumTopicRoute.kt"
        val BYPASS_KT = BASE + "ui/alert/S7n1Bypass.kt"
        val BYPASS_JAVA = BASE + "ui/alert/S7n1Bypass.java"

        /** The agreed default log messages (API r3 §5). */
        const val REGISTRY_MESSAGE = "alert family adapter dispatch failed"
        const val DELIVERY_MESSAGE = "alert family sync delivery failed"

        const val NAME_VM = """\bAlertViewModel\b"""
        val KRX = Regex("(?i)krx")
        val OBSERVE = Regex("""\.\s*addObserver\s*\(""")
        val EMIT = Regex("""([\w.]+\(\)\.emit\([^()]*\))""")
        val LAMBDA = Regex("""\{\s*alertFamilies\s*\.\s*get\s*\(\s*\)\s*\.\s*start\s*\(\s*\)\s*\}""")
        val STEP = Regex("""\bstartAlertFamilies\s*\(\s*\)""")
        val HOOK_START = Regex("""\bAlertFamilyRefresherRegistry\s*\.\s*deliverPush\s*\(""")
        /** The FCM statement, squashed: the raw type and exactly the app-owned gate, with a lazy resolve (API r3 §6). */
        val HOOK = Regex("""AlertFamilyRefresherRegistry\.deliverPush\((?:type = )?type, (?:gateOpen = )?shouldStartAppOwnedServices\((?:releaseAdmissionOpen = )?ReleaseAdmission\.isOpen, (?:benchmarkNoData = )?BuildConfig\.BENCHMARK_NO_DATA_MODE\)\) \{ alertFamiliesProvider\.get\(\) \}""")
        val REPORT_DEFAULT = Regex("""\breportFailure\s*:\s*\(\s*\)\s*->\s*Unit\s*=\s*\{""")
        val LOG_DEFAULT = Regex("""\{ Log\.w\(TAG, "([^"$\\]*)"\)(?: Unit)? \}""")
        val LOG_CALL = Regex("""\bLog\s*\.\s*\w+\s*\(""")
        val FIXED_LOG_ARGS = Regex("TAG, \"[^\"\$\\\\]*\"")
        val CONST_TAG = Regex("\\bconst\\s+val\\s+TAG\\s*=\\s*\"[^\"\$\\\\]*\"")
        val CATCH = Regex("""\bcatch\s*\(\s*\w+\s*:\s*([\w.]+)\s*\)\s*\{""")
        val CALL = Regex("""\b(\w+)\s*\(""")
        val KEYWORDS = setOf("if", "when", "while", "for", "catch", "return", "try")

        /** deliverPush's body, squashed: an exact raw sync and the open gate, then resolve and sync inside the boundary. */
        const val TRY = """try \{ resolve\(\)\.onSyncAlerts\(\) \} catch \(\w+: Exception\) \{ reportFailure\(\) \}"""
        const val NOT_SYNC = """(?:type != SYNC_ALERTS|SYNC_ALERTS != type)"""
        const val IS_SYNC = """(?:type == SYNC_ALERTS|SYNC_ALERTS == type)"""
        val GUARDS = listOf(
            """if \($NOT_SYNC \|\| !gateOpen\) return $TRY""",
            """if \(!gateOpen \|\| $NOT_SYNC\) return $TRY""",
            """if \($NOT_SYNC\) return if \(!gateOpen\) return $TRY""",
            """if \(!gateOpen\) return if \($NOT_SYNC\) return $TRY""",
            """if \($IS_SYNC && gateOpen\) \{ $TRY \}""",
            """if \(gateOpen && $IS_SYNC\) \{ $TRY \}"""
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

        /** Every production source of the five roots, Kotlin and Java, comment-free with every literal kept. */
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
         * once, from the section holder, and named nowhere else; AlertAddSheet is called, referenced or named nowhere else.
         */
        fun s1Composables(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((name, home, callers) in listOf(
                Triple("AlertSection", SECTION, mapOf(HOLDER to 1)),
                Triple("AlertAddSheet", SHEET, emptyMap<String, Int>()))) {
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

        /** S3 (API r3 §7 text): deliverPush and onSyncAlerts, their declarations, calls, references and aliases. */
        fun s3Hook(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val registry = code[REGISTRY].orEmpty()
            val fcm = code[FCM].orEmpty()
            val call = """\bAlertFamilyRefresherRegistry\s*\.\s*deliverPush\s*\("""
            val deliverFiles = files(code, """\bdeliverPush\b""")
            if (deliverFiles != setOf(FCM, REGISTRY)) v += "S3-deliver-files ${deliverFiles.sorted()}"
            if (count(registry, """\bdeliverPush\b""") != 1 || count(registry, """\bfun\s+deliverPush\s*\(""") != 1) v += "S3-deliver-declaration"
            val companion = Regex("""\bcompanion\s+object\b[^{]*\{""").find(registry)
            val companionClose = companion?.let { closing(registry, it.range.last) }
            val declared = Regex("""\bfun\s+deliverPush\s*\(""").find(registry)
            if (companion == null || companionClose == null || declared == null || declared.range.first !in companion.range.last..companionClose) {
                v += "S3-deliver-companion"
            }
            if (count(fcm, """\bdeliverPush\b""") != 1 || count(fcm, call) != 1) v += "S3-deliver-fcm"
            val receive = bodyRange(fcm, "onMessageReceived")
            if (receive == null || count(fcm.substring(receive), call) != 1) v += "S3-deliver-outside-receive"
            val references = files(code, """::\s*deliverPush\b|::\s*onSyncAlerts\b""")
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
            val aliases = files(code, """\bimport\s+[\w.`]*\b(?:AlertFamilyRefresherRegistry|deliverPush|onSyncAlerts)\b`?\s+as\b""")
            if (aliases.isNotEmpty()) v += "S3-alias ${aliases.sorted()}"
            val registryFiles = files(code, """\bAlertFamilyRefresherRegistry\b""")
            if (registryFiles != setOf(REGISTRY, APP, FCM)) v += "S3-registry-files ${registryFiles.sorted()}"
            return v
        }

        /** S3: the default loggers and every catch boundary in the registry; every log call in the new files is fixed. */
        fun s3Loggers(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val registry = code[REGISTRY].orEmpty()
            val deliverParams = paramsRange(registry, "deliverPush")
            val defaults = REPORT_DEFAULT.findAll(registry).toList()
            if (defaults.size != 2) v += "S3-logger-defaults ${defaults.size}"
            val messages = mutableMapOf<String, String>()
            for (parameter in defaults) {
                val where = if (deliverParams != null && parameter.range.first in deliverParams) "deliverPush" else "registry"
                val close = closing(registry, parameter.range.last)
                val lambda = if (close == null) "" else squash(registry.substring(parameter.range.last, close + 1))
                val match = LOG_DEFAULT.matchEntire(lambda)
                if (match == null) v += "S3-logger-form $where $lambda" else messages[where] = match.groupValues[1]
            }
            if (messages != mapOf("registry" to REGISTRY_MESSAGE, "deliverPush" to DELIVERY_MESSAGE)) v += "S3-logger-message $messages"
            if (CONST_TAG.findAll(registry).count() != 1) v += "S3-logger-tag"
            val logCalls = LOG_CALL.findAll(registry).count()
            if (logCalls != 2) v += "S3-logger-count $logCalls"
            for (path in NEW_FILES) {
                val text = code[path].orEmpty()
                for (log in LOG_CALL.findAll(text)) {
                    val close = closing(text, log.range.last)
                    val args = if (close == null) "?" else squash(text.substring(log.range.last + 1, close))
                    if (!FIXED_LOG_ARGS.matches(args)) v += "S3-logger-payload $path $args"
                }
            }
            val handlers = CATCH.findAll(registry).toList()
            if (handlers.size < 3) v += "S3-catch-count ${handlers.size}"
            for (handler in handlers) {
                val close = closing(registry, handler.range.last)
                val inner = if (close == null) "?" else registry.substring(handler.range.last + 1, close)
                val calls = CALL.findAll(inner).map { it.groupValues[1] }.filter { it !in KEYWORDS }.toList()
                if (handler.groupValues[1] != "Exception" || calls != listOf("reportFailure")) v += "S3-catch ${handler.groupValues[1]} $calls"
            }
            return v
        }

        /** N1-P / S3: FCM's onMessageReceived: gate first, raw type, one emit, the gated hook right after it, no catch. */
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
            if (count(fcm, """\balertFamiliesProvider\b""") != 2 ||
                count(fcm, """@Inject\s+internal\s+lateinit\s+var\s+alertFamiliesProvider\s*:\s*Provider\s*<\s*AlertFamilyRefresherRegistry\s*>""") != 1) {
                v += "P-provider"
            }
            if (Regex("""\btry\b|\bcatch\b""").containsMatchIn(body)) v += "P-catch"
            val order = listOf("if (!ReleaseAdmission.isOpen) return", "val type = message.data[", "AlertPushRouting.route(", ".emit(",
                "AlertFamilyRefresherRegistry.deliverPush(", "showAlertNotification(").map { flat.indexOf(it) }
            if (order.any { it < 0 } || order != order.sorted()) v += "P-order $order"
            return v
        }

        /** S11: the agreed production `@Inject` delegations, squashed (API r1 §3–§6; r2/r3 §4–§6). */
        val INJECT_FORMS: Map<String, String> by lazy {
            mapOf(
                OWNER to """@Inject constructor(repository: AlertRepository, access: AlertSettingsAccess, floor: AlertRateLimitFloor,
                    coordinator: PremiumAccessCoordinator, topic: TopicRuntimeOwner) : this({ owner, admitted -> repository.getSettings(owner, admitted) },
                    access, floor, AlertRetryPolicy(), SystemClock::elapsedRealtime, { Random.nextDouble(-0.2, 0.2) },
                    CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, coordinator.state, coordinator.accessRevisions,
                    topic.graphHost.map { it != null })""",
                ACCESS to """@Inject constructor(tokens: AuthTokenProvider, topic: TopicRuntimeOwner, uses: TopicUseAuthority,
                    coordinator: PremiumAccessCoordinator, deletions: DeletionAdmissionStore) : this(tokens::currentIdentityFence,
                    topic.currentAccessFence, uses, { coordinator.state.value }, { ReleaseAdmission.isOpen },
                    GraphProtectedAdmission(tokens::currentIdentityFence, deletions))""",
                FLOOR to """@Inject constructor() : this(SystemClock::elapsedRealtime)""",
                REGISTRY to """@Inject constructor(bank: Provider<BankAlertSettingsOwner>, coordinator: PremiumAccessCoordinator,
                    tokens: AuthTokenProvider) : this(listOf({ bank.get() }), { ProcessLifecycleOwner.get().lifecycle },
                    { action -> Handler(Looper.getMainLooper()).post { action() }; Unit }, coordinator.state, tokens::currentIdentityFence,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main))"""
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

        /** S11: the four delegations as agreed, the owner's production recheck period its 1 s default, FCM's one guarded banner. */
        fun s11Wiring(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((path, form) in INJECT_FORMS) {
                if (code[path]?.let(::injectDelegation)?.let(::squash) != form) v += "S11-inject $path"
            }
            val owner = code[OWNER].orEmpty()
            if (count(owner, """\bprivate\s+val\s+accessRecheckMillis\s*:\s*Long\s*=\s*(?:1_000|1000)\s*,?\s*\)""") != 1 ||
                count(owner, """\baccessRecheckMillis\b""") < 2) {
                v += "S11-recheck"
            }
            val fcm = code[FCM].orEmpty()
            val receive = bodyRange(fcm, "onMessageReceived")?.let { squash(fcm.substring(it)) }.orEmpty()
            if (Regex("""(?<![!\w])if \(route\.showBanner\) showAlertNotification\(message, type\)""").findAll(receive).count() != 1 ||
                count(fcm, """\bshowAlertNotification\s*\(""") != 2) {
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
                "AlertSettingsAccess" to setOf(ACCESS, OWNER),
                "AlertRateLimitFloor" to setOf(FLOOR, OWNER),
                "AlertFamilyRefresherRegistry" to setOf(REGISTRY, APP, FCM))) {
                val named = files(code, """\b$name\b""")
                if (named != expected) v += "A-reach $name ${named.sorted()}"
            }
            for ((name, path) in listOf("BankAlertSettingsOwner" to OWNER, "AlertSettingsAccess" to ACCESS,
                    "AlertRateLimitFloor" to FLOOR, "AlertFamilyRefresherRegistry" to REGISTRY)) {
                val text = code[path].orEmpty()
                if (count(text, """@Singleton\s+(?:internal\s+)?class\s+$name\b""") != 1) v += "A-singleton $name"
                if (count(text, """@Inject\s+constructor\b""") != 1) v += "A-inject $name"
            }
            val registry = code[REGISTRY].orEmpty()
            if (count(registry, """\bstart\s*\(\s*\)""") != 1) v += "A-self-start"
            val provider = Regex("""\b(\w+)\s*:\s*Provider\s*<\s*BankAlertSettingsOwner\s*>""").findAll(registry).toList()
            val name = provider.singleOrNull()?.groupValues?.get(1)
            if (name == null || count(registry, """\b$name\s*\.\s*get\s*\(\s*\)""") != 1 ||
                count(registry, """\{\s*$name\s*\.\s*get\s*\(\s*\)\s*\}""") != 1) v += "A-lazy-owner"
            return v
        }

        /** S5: no KRX in the code and strings of the six new files. */
        fun s5Krx(code: Map<String, String>): List<String> = NEW_FILES.mapNotNull { path ->
            val text = code[path]
            when {
                text == null -> "S5-missing $path"
                KRX.containsMatchIn(text) -> "S5-krx $path"
                else -> null
            }
        }

        /** S6 / N1-Q: the surfaces the six new files never touch. */
        fun s6Surfaces(code: Map<String, String>): List<String> = NEW_FILES.flatMap { path ->
            val text = code[path] ?: return@flatMap listOf("S6-missing $path")
            FORBIDDEN.filter { (_, pattern) -> Regex(pattern).containsMatchIn(text) }.map { (tag, _) -> "S6-$tag $path" }
        }
    }
}
