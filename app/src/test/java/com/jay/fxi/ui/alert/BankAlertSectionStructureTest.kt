package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): API r2 §5 row 3, the structure of the read-only bank alert section,
 * read from the comment-free code and strings of the five production roots ([S6Krx3bOffWiringContractTest.ROOTS]; Kotlin
 * through [PostS6ApprovedDelta.codeAndStrings], Java through `javaCodeAndStrings` below), so a name cannot hide in a comment,
 * a string or an escape.
 *
 * - H: the holder `ui/alert/BankAlertSection.kt` names no AlertViewModel, AlertState, AlertRepository, AlertSettingsAccess,
 *   AlertRateLimitFloor, AlertFamilyRefresherRegistry, permission, push manager or registration, DataStore or
 *   SharedPreferences, and no lifecycle observer (LifecycleEventObserver, ProcessLifecycleOwner, LocalLifecycleOwner or another).
 * - A: its production adapter resolves the existing owner once, through the holder's own entry point inside the production
 *   factory, exposes the owner's `state` and `manualRefresh` as they are and delegates `ensure`, `authoritative` and `manual`
 *   one to one; the holder touches no other owner member and builds no owner, flow or coroutine; no other screen file names
 *   the owner type or the entry point.
 * - T: the source and factory types are declared and implemented in the holder only, the source is named there only, and the
 *   route names the factory only for its parameter.
 * - S: AlertSection keeps the agreed read-only signature and names no Switch, delete, add, edit or permission entry, no
 *   AlertRow and no view model; click modifiers stay in the header, button callbacks in the header or the error retry, and
 *   none of either is inside the read-only row render (the Rows branch, every function taking an AlertSetting, and what they
 *   call in the file). S7 E5b2b2 adds the prompt's one button and the banner's one click (N below) to that list.
 * - N (S7 E5b2b2, s7e5b2_api_agreed.r1.md §3.2, start agreement D10): AlertSection takes `notificationsBlocked` and
 *   `onEnableNotifications`; Empty under `if (notificationsBlocked)` shows the prompt with one Button calling
 *   onEnableNotifications, else the unchanged empty text; Rows under `if (notificationsBlocked)` shows the banner, above the
 *   list, with one `.clickable(onClick = onEnableNotifications)`. The holder calls `rememberNotificationPermissionAction()` once,
 *   right after its tab check, and passes `needsAction` and `{ run() }` only; that exact call is the one `permission` name the
 *   holder may hold.
 * - P: the premium route calls BankAlertSection exactly once, inside the belowRates lambda after the unchanged
 *   BankHistoryEntry, passing its own new last parameter as the source factory.
 *
 * Every lock is a function from the scanned sources to its violations: each test first shows the real tree clean, then feeds
 * the same function mutated trees it must reject (alias imports, qualified, backticked and escaped names, moved calls) and
 * comment-only or lookalike controls it must accept. Mutations anchor only on the agreed API and [BankAlertSectionTags], never
 * on an implementation's private names. Not this file's: the projection's values (BankAlertSectionStateTest), the owner's
 * transitions (BankAlertManualRefreshStateContractTest), the device rendering and wiring (BankAlertSectionTest,
 * BankAlertSectionRouteTest) and the N1 file-set locks (AlertN1StructureTest). The implementation thread reads but does not
 * edit this file.
 */
class BankAlertSectionStructureTest {

    @Test fun `S7E5A1_R3_00 the scan reads every production root comment-free and holds the four E5a1 files`() {
        val roots = S6Krx3bOffWiringContractTest.ROOTS
        assertTrue("premise(R3): the five production roots exist", roots.size == 5 && roots.all { File(it).isDirectory })
        assertTrue("premise(R3): the scan sees the production sources (${code.size})", code.size > 300)
        for (path in listOf(HOLDER, SECTION, ROUTE, OWNER, VM, REGISTRY)) {
            assertTrue("premise(R3): $path is scanned and non-empty", !code[path].isNullOrBlank())
        }
        val kotlin = strip("X.kt", "// AlertViewModel\n/* a /* AlertViewModel */ b */ val s = \"Alert\\u0056iewModel\"\nval t = `clickable`")
        assertEquals("R3 scan, Kotlin: comments go, nested ones included; the escaped literal stays, decoded", 1,
            count(kotlin, """\bAlertViewModel\b"""))
        assertEquals("R3 scan, Kotlin: a backticked name stays a name", 1, count(kotlin, """\bclickable\b"""))
        val java = strip("X.java", "class X { // BankAlertSection(\n /* BankAlertSection( */ void go() { Bank\\u0041lertSection(); } }")
        assertEquals("R3 scan, Java: comments go and a Unicode escape is decoded before lexing", 1, count(java, """\bBankAlertSection\s*\("""))
    }

    // ---- H: the holder's names ----

    @Test fun `S7E5A1_R3_H01 the holder names no view model state repository access floor registry permission push storage or lifecycle observer`() {
        assertEquals("premise(R3 H): the holder declares the section once", 1, count(code.getValue(HOLDER), """\bfun\s+BankAlertSection\s*\("""))
        for ((path, tag) in listOf(VM to "AlertViewModel", OWNER to "AlertSettingsAccess", OWNER to "AlertRateLimitFloor",
                REGISTRY to "ProcessLifecycleOwner")) {
            assertTrue("premise(R3 H): the $tag pattern finds the name where it lives ($path)",
                Regex(forbidden(tag)).containsMatchIn(code.getValue(path)))
        }
        assertEquals("R3 H: the holder's names", emptyList<String>(), holderNames(code))
    }

    @Test fun `S7E5A1_R3_H02 the holder lock catches alias imports qualified backticked escaped and aliased names and accepts comments and lookalikes`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), holderNames(code))
        val elsewhereAlias = withFile(BYPASS_KT, "typealias S7e5a1Vm = com.jay.fxi.ui.viewmodel.AlertViewModel")
        for ((label, tree, tag) in listOf(
            Triple("the view model by alias import", withImport(HOLDER, "import com.jay.fxi.ui.viewmodel.AlertViewModel as Vm"), "H-name AlertViewModel"),
            Triple("the view model state qualified", appended(HOLDER, "private val s7e5a1: com.jay.fxi.ui.viewmodel.AlertState? = null"), "H-name AlertState"),
            Triple("the repository qualified", appended(HOLDER, "private val s7e5a1: com.jay.fxi.data.repository.AlertRepository? = null"), "H-name AlertRepository"),
            Triple("the access by alias import", withImport(HOLDER, "import com.jay.fxi.data.alerts.AlertSettingsAccess as Access"), "H-name AlertSettingsAccess"),
            Triple("the floor in an escaped string", appended(HOLDER, "private val s7e5a1 = \"com.jay.fxi.data.alerts.Alert\\u0052ateLimitFloor\""), "H-name AlertRateLimitFloor"),
            Triple("the registry backticked", appended(HOLDER, "private val s7e5a1: `AlertFamilyRefresherRegistry`? = null"), "H-name AlertFamilyRefresherRegistry"),
            Triple("a permission request", appended(HOLDER, "private val s7e5a1 = android.Manifest.permission.POST_NOTIFICATIONS"), "H-name permission"),
            Triple("an escaped permission string", appended(HOLDER, "private val s7e5a1 = \"perm\\u0069ssion\""), "H-name permission"),
            Triple("the push manager qualified", appended(HOLDER, "private val s7e5a1: com.jay.fxi.notification.PushNotificationManager? = null"), "H-name PushNotificationManager"),
            Triple("a push registration", appended(HOLDER, "private fun s7e5a1(m: Any) = registerIfNeeded()"), "H-name registerIfNeeded"),
            Triple("a messaging token", appended(HOLDER, "private fun s7e5a1() = com.google.firebase.messaging.FirebaseMessaging.getInstance().token"), "H-name push"),
            Triple("a data store", appended(HOLDER, "private val Context.s7e5a1 by androidx.datastore.preferences.preferencesDataStore(name = \"x\")"), "H-name DataStore"),
            Triple("shared preferences", appended(HOLDER, "private fun s7e5a1(c: Context) = c.getSharedPreferences(\"x\", 0)"), "H-name SharedPreferences"),
            Triple("an event observer", appended(HOLDER, "private val s7e5a1 = androidx.lifecycle.LifecycleEventObserver { _, _ -> }"), "H-name LifecycleEventObserver"),
            Triple("the process lifecycle escaped", appended(HOLDER, "private val s7e5a1 = \"androidx.lifecycle.Process\\u004CifecycleOwner\""), "H-name ProcessLifecycleOwner"),
            Triple("the composition lifecycle", appended(HOLDER, "@Composable private fun S7e5a1() { val l = LocalLifecycleOwner.current }"), "H-name LocalLifecycleOwner"),
            Triple("a lifecycle effect", appended(HOLDER, "@Composable private fun S7e5a1() { LifecycleResumeEffect(Unit) { onPauseOrDispose { } } }"), "H-name lifecycle-observer"),
            Triple("an observer added", appended(HOLDER, "private fun s7e5a1(l: Lifecycle, o: Any) = l.addObserver(o)"), "H-name lifecycle-observer"),
            Triple("a forbidden type through a type alias elsewhere", appended(HOLDER, "private val s7e5a1: S7e5a1Vm? = null", elsewhereAlias), "H-typealias S7e5a1Vm"))) {
            assertCaught("R3 H $label", tag, holderNames(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(HOLDER, "// AlertViewModel AlertState AlertRepository permission PushNotificationManager\n" +
                "/* ProcessLifecycleOwner LocalLifecycleOwner SharedPreferences DataStore registerIfNeeded AlertFamilyRefresherRegistry */\nprivate val s7e5a1Control = 1"),
            "lookalike names" to appended(HOLDER, "private val alertStateCount = 0\nprivate val s7e5a1Label = \"AlertViewModels\"\n" +
                "private val s7e5a1Kind = AlertRepositoryFailureKind.NETWORK"),
            "a harmless type alias elsewhere" to appended(HOLDER, "private val s7e5a1: S7e5a1Rows? = null",
                withFile(BYPASS_KT, "typealias S7e5a1Rows = List<Int>")))) {
            assertClean("R3 H, $label", holderNames(tree))
        }
    }

    // ---- A: the production adapter ----

    @Test fun `S7E5A1_R3_A01 the production adapter resolves the existing owner once and uses only its state manualRefresh ensure authoritative and manual`() {
        val synthetic = mapOf(OWNER to strip(OWNER, "class BankAlertSettingsOwner internal constructor(private val a: Int, b: () -> Unit = { }) : X {\n" +
            "    private val hidden = 1\n    val shown: Int = listOf(1).map { val inner = it; inner }.size\n    @Volatile private var flag = false\n" +
            "    override fun onSync() { val nested = 3 }\n    private class Chain(val generation: Long) { var manual = false }\n" +
            "    companion object { const val C = 1 }\n    /** fun commented() */\n    suspend fun later() = Unit\n}"))
        assertEquals("premise(R3 A): the member reader keeps the non-private top-level members only", setOf("shown", "onSync", "later"),
            ownerMembers(synthetic))
        val members = ownerMembers(code)
        assertTrue("premise(R3 A): the owner exposes the five members the adapter uses and the five it must not ($members)",
            members.containsAll(OWNER_ALLOWED + OWNER_OTHERS))
        assertEquals("R3 A: the production adapter", emptyList<String>(), adapter(code))
        assertClean("R3 A, the same holder squashed onto one line", adapter(code + (HOLDER to squash(code.getValue(HOLDER)))))
    }

    @Test fun `S7E5A1_R3_A02 the adapter lock catches another owner member a derived flow a swapped delegation a second resolution and the owner type elsewhere`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), adapter(code))
        val flat = squash(code.getValue(HOLDER))
        val settings = SETTINGS.findAll(flat).toList()
        assertEquals("mutation premise: the squashed holder has one settings override", 1, settings.size)
        val r = settings.single().groupValues[1]
        val er = Regex.escape(r)
        fun flatEdit(regex: Regex, replacement: (MatchResult) -> String): Map<String, String> {
            val found = regex.findAll(flat).toList()
            assertEquals("mutation premise: /${regex.pattern}/ is in the squashed holder once", 1, found.size)
            return code + (HOLDER to flat.replaceRange(found.single().range, replacement(found.single())))
        }
        for ((label, tree, tag) in listOf(
            Triple("the owner reset", appended(HOLDER, "private fun s7e5a1(o: BankAlertSettingsOwner) = o.reset()"), "A-owner-member reset"),
            Triple("a sync through a scope function", appended(HOLDER, "private fun s7e5a1(o: BankAlertSettingsOwner) = with(o) { onSync() }"), "A-owner-member onSync"),
            Triple("a function reference", appended(HOLDER, "private val s7e5a1 = BankAlertSettingsOwner::onForeground"), "A-owner-member onForeground"),
            Triple("the outcome stream", appended(HOLDER, "private fun s7e5a1(o: BankAlertSettingsOwner) = o.manualOutcomes"), "A-owner-member manualOutcomes"),
            Triple("an escaped reflective member", appended(HOLDER, "private fun s7e5a1(o: BankAlertSettingsOwner) = o.javaClass.getMethod(\"re\\u0073et\")"),
                "A-owner-member reset"),
            Triple("reflection", appended(HOLDER, "private fun s7e5a1(o: Any) = o.javaClass.getDeclaredMethods()"), "A-reflection"),
            Triple("the manual delegation swapped for a reset", flatEdit(methodOverride("manual", er)) { "override fun manual() = $r.reset()" }, "A-manual"),
            Triple("a derived settings flow", flatEdit(SETTINGS) { it.value + ".map { it }" }, "A-settings"),
            Triple("a local manual flow", flatEdit(flowOverride("manualRefresh", "ManualRefreshState", er, "manualRefresh")) {
                "override val manualRefresh = MutableStateFlow(ManualRefreshState())" }, "A-manualRefresh"),
            Triple("a local manual flow is a construction", flatEdit(flowOverride("manualRefresh", "ManualRefreshState", er, "manualRefresh")) {
                "override val manualRefresh = MutableStateFlow(ManualRefreshState())" }, "A-flow-construction"),
            Triple("ensure delegated to authoritative", flatEdit(methodOverride("ensure", er)) { "override fun ensure() = $r.authoritative()" }, "A-ensure"),
            Triple("authoritative delegated to manual", flatEdit(methodOverride("authoritative", er)) { "override fun authoritative() = $r.manual()" }, "A-authoritative"),
            Triple("manual doing more", flatEdit(methodOverride("manual", er)) { "override fun manual() { $r.manual(); $r.ensure() }" }, "A-manual"),
            Triple("setEnabled delegated to delete", flatEdit(argOverride("setEnabled", "id: Int, enabled: Boolean", "id, enabled", er)) {
                "override fun setEnabled(id: Int, enabled: Boolean) = $r.delete(id)" }, "A-setEnabled"),
            Triple("setEnabled inverted", flatEdit(argOverride("setEnabled", "id: Int, enabled: Boolean", "id, enabled", er)) {
                "override fun setEnabled(id: Int, enabled: Boolean) = $r.setEnabled(id, !enabled)" }, "A-setEnabled"),
            Triple("delete doing more", flatEdit(argOverride("delete", "id: Int", "id", er)) { "override fun delete(id: Int) { $r.delete(id); $r.ensure() }" }, "A-delete"),
            Triple("a toggle callback without the identity check", flatEdit(Regex(Regex.escape(BINDINGS[2]))) {
                "onToggle = { id, enabled -> source.setEnabled(id, enabled) }," }, "A-binding"),
            Triple("a delete callback re-bound to the latest snapshot", flatEdit(Regex(Regex.escape(BINDINGS[3]))) {
                "onDelete = { id -> if (source.settings.value.identity != null) source.delete(id) }," }, "A-binding"),
            Triple("a second resolution", appended(HOLDER,
                "@Composable private fun S7e5a1(c: Context) { val o = EntryPointAccessors.fromApplication(c, BankAlertSectionEntryPoint::class.java).bankAlertSettingsOwner() }"),
                "A-resolution"),
            Triple("an activity resolution", appended(HOLDER, "private fun s7e5a1(a: android.app.Activity) = EntryPointAccessors.fromActivity(a, BankAlertSectionEntryPoint::class.java)"),
                "A-resolution"),
            Triple("an owner built here", appended(HOLDER, "private fun s7e5a1() = BankAlertSettingsOwner(a, b, c)"), "A-construction"),
            Triple("a coroutine of its own", appended(HOLDER, "private val s7e5a1 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)"), "A-flow-construction"),
            Triple("a list of its own", appended(HOLDER, "private val s7e5a1 = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AlertSetting>())"), "A-flow-construction"),
            Triple("a fetch of its own", appended(HOLDER, "private suspend fun s7e5a1(r: Any) = r.getSettings()"), "A-get"),
            Triple("a second adapter", appended(HOLDER, "private object S7e5a1 : BankAlertSectionSource { override val settings = x.state }"), "A-settings"),
            Triple("the entry point reshaped", edited(HOLDER, """\bfun\s+bankAlertSettingsOwner\s*\(\s*\)\s*:\s*BankAlertSettingsOwner\b""",
                "fun bankAlertSettingsOwner(): BankAlertSettingsOwner\n    fun s7e5a1(): Int"), "A-entry-point"),
            Triple("the owner type in another screen file by alias import",
                withFile(BYPASS_KT, "import com.jay.fxi.data.alerts.BankAlertSettingsOwner as Owner\nval s7e5a1Bypass: Owner? = null"), "A-owner-type"),
            Triple("an owner alias", withFile(BYPASS_KT, "import com.jay.fxi.data.alerts.BankAlertSettingsOwner as Owner\nval s7e5a1Bypass: Owner? = null"), "A-alias"),
            Triple("the owner type escaped elsewhere", withFile(BYPASS_KT, "val s7e5a1Name = \"com.jay.fxi.data.alerts.BankAlert\\u0053ettingsOwner\""), "A-owner-type"),
            Triple("the owner type from the route", appended(ROUTE, "private val s7e5a1: com.jay.fxi.data.alerts.BankAlertSettingsOwner? = null"), "A-owner-type"),
            Triple("the entry point elsewhere", withFile(BYPASS_KT, "val s7e5a1Bypass = BankAlertSectionEntryPoint::class.java"), "A-entry-point-files"),
            Triple("the entry point from Java", withFile(BYPASS_JAVA, "class Bypass { Object o = BankAlertSectionEntryPoint.class; }"), "A-entry-point-files"))) {
            assertCaught("R3 A $label", tag, adapter(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(HOLDER, "// owner.reset() owner.onSync() owner.manualOutcomes MutableStateFlow EntryPointAccessors.fromApplication(\n" +
                "/* BankAlertSettingsOwner(a) javaClass launch getSettings */\nprivate val s7e5a1Control = 1"),
            "lookalike names" to appended(HOLDER, "private val s7e5a1ResetLabel = \"resets\"\nprivate val onSyncCount = 0\nprivate val launched = false"),
            "an ensure block body" to flatEdit(methodOverride("ensure", er)) { "override fun ensure() { $r.ensure() }" },
            "a settings getter" to flatEdit(SETTINGS) { "override val settings get() = $r.state" })) {
            assertClean("R3 A, $label", adapter(tree))
        }
    }

    // ---- T: where the source seam lives ----

    @Test fun `S7E5A1_R3_T01 the source and factory types are declared implemented and named in the holder file only`() {
        assertEquals("premise(R3 T): the holder implements the source once", mapOf(HOLDER to 1), occurrences(code, IMPL_SOURCE))
        assertTrue("premise(R3 T): the route names the factory", Regex("""\bBankAlertSectionSourceFactory\b""").containsMatchIn(code.getValue(ROUTE)))
        assertEquals("premise(R3 T): the holder's own supertype clause and declaration are not factory lambdas", emptyMap<String, Int>(), factoryLambdas(code))
        assertEquals("R3 T: the seam's home", emptyList<String>(), types(code))
    }

    @Test fun `S7E5A1_R3_T02 the type lock catches a source or factory declared implemented aliased or named elsewhere and a widened source`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), types(code))
        for ((label, tree, tag) in listOf(
            Triple("a second source interface in the route", appended(ROUTE, "internal interface BankAlertSectionSource { val x: Int }"), "T-declaration source"),
            Triple("a second factory interface elsewhere", withFile(BYPASS_KT, "internal fun interface BankAlertSectionSourceFactory { fun create(): Int }"), "T-declaration factory"),
            Triple("a second production object elsewhere", withFile(BYPASS_KT, "internal object ProductionBankAlertSectionSourceFactory"), "T-declaration production"),
            Triple("a source implemented elsewhere", withFile(BYPASS_KT, "internal class S7e5a1Bypass(o: Any) : BankAlertSectionSource {\n}"), "T-source-implementation"),
            Triple("a source object in the route", appended(ROUTE, "private val s7e5a1 = object : BankAlertSectionSource { }"), "T-source-implementation"),
            Triple("a Java source", withFile(BYPASS_JAVA, "class Bypass implements BankAlertSectionSource { }"), "T-source-implementation"),
            Triple("a factory lambda in the route", appended(ROUTE, "private val s7e5a1 = BankAlertSectionSourceFactory { context -> error(\"x\") }"), "T-factory-implementation"),
            Triple("a factory object elsewhere", withFile(BYPASS_KT, "internal object S7e5a1Bypass : BankAlertSectionSourceFactory {\n}"), "T-factory-implementation"),
            Triple("the source by alias import", withFile(BYPASS_KT, "import com.jay.fxi.ui.alert.BankAlertSectionSource as Seam\nval s7e5a1Bypass = 1"), "T-alias"),
            Triple("the source named by the route", appended(ROUTE, "private val s7e5a1: com.jay.fxi.ui.alert.BankAlertSectionSource? = null"), "T-named BankAlertSectionSource"),
            Triple("the source escaped elsewhere", withFile(BYPASS_KT, "val s7e5a1Name = \"com.jay.fxi.ui.alert.BankAlert\\u0053ectionSource\""), "T-named BankAlertSectionSource"),
            Triple("the factory named by another file", withFile(BYPASS_KT, "val s7e5a1Bypass: com.jay.fxi.ui.alert.BankAlertSectionSourceFactory? = null"), "T-named BankAlertSectionSourceFactory"),
            Triple("a member added to the source", edited(HOLDER, """\binterface\s+BankAlertSectionSource\s*\{""", "interface BankAlertSectionSource {\n    fun refreshAll()\n"), "T-source-shape"),
            Triple("a member added to the factory", edited(HOLDER, """\binterface\s+BankAlertSectionSourceFactory\s*\{""",
                "interface BankAlertSectionSourceFactory {\n    fun owner(): Any\n"), "T-factory-shape"))) {
            assertCaught("R3 T $label", tag, types(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(ROUTE, "// BankAlertSectionSource object : BankAlertSectionSource {\n/* BankAlertSectionSourceFactory { } interface BankAlertSectionSource */\nprivate val s7e5a1Control = 1"),
            "lookalike names" to withFile(BYPASS_KT, "class BankAlertSectionSourceLike\nval bankAlertSectionSources = 0"))) {
            assertClean("R3 T, $label", types(tree))
        }
    }

    // ---- S: AlertSection's read-only surface ----

    @Test fun `S7E5A1_R3_S01 AlertSection keeps the agreed signature and names no add edit permission entry AlertRow or view model`() {
        assertNotNull("premise(R3 S): AlertSection is declared once", declaration(code.getValue(SECTION), "AlertSection"))
        assertTrue("premise(R3 S): the patterns see the legacy names where they live",
            SWITCH_CALL.containsMatchIn(code.getValue(ROW)) && Regex(sectionForbidden("AlertRow")).containsMatchIn(code.getValue(ROW)))
        assertEquals("R3 S: signature and entries", emptyList<String>(), sectionSignature(code) + sectionEntries(code))
    }

    @Test fun `S7E5A1_R3_S02 AlertSection keeps click surfaces in the header the error retry and the agreed row actions only`() {
        val text = code.getValue(SECTION)
        val problems = mutableListOf<String>()
        val r = regions(text, problems)
        assertNotNull("premise(R3 S): the content dispatch, the row render and the header are found: $problems", r)
        val rowTags = tagPattern("row").findAll(text).map { it.range.first }.toList()
        assertTrue("premise(R3 S): the per-row tag is drawn inside the row render (the window this lock watches)",
            rowTags.isNotEmpty() && rowTags.all { p -> r!!.rows.any { p in it } })
        assertTrue("premise(R3 S): the header holds the refresh wiring",
            r!!.header.any { Regex("""\bonRefresh\b""").containsMatchIn(text.substring(it.first, it.last + 1)) })
        assertTrue("premise(R3 S): the header has a click surface", CLICK_ACTION.findAll(text).any { m -> r.header.any { m.range.first in it } })
        assertTrue("premise(R3 S): the error retry is a button callback in the error body",
            CLICK_ACTION.findAll(text).any { m -> r.error.any { m.range.first in it } })
        assertEquals("R3 S: click surfaces", emptyList<String>(), sectionClicks(code))
    }

    @Test fun `S7E5A1_R3_S03 the entry lock catches each entry by alias import qualified name function reference or escaped string and accepts comments and lookalikes`() {
        fun entries(tree: Map<String, String>) = sectionSignature(tree) + sectionEntries(tree) + sectionRowActions(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), entries(code))
        for ((label, tree, tag) in listOf(
            Triple("a qualified Switch in a row", atSectionTag("row", "androidx.compose.material3.Switch(checked = true, onCheckedChange = null) "), "R-switch"),
            Triple("a Switch by alias import", withImport(SECTION, "import androidx.compose.material3.Switch as Toggle"), "R-alias"),
            Triple("a checkbox in a row", atSectionTag("row", "Checkbox(checked = true, onCheckedChange = null) "), "S-entry toggle-other"),
            Triple("a delete button", appended(SECTION, "@Composable private fun S7e5a1(onDelete: () -> Unit) = IconButton(onClick = onDelete) { }"), "R-delete"),
            Triple("an escaped delete label", appended(SECTION, "private val s7e5a1 = \"\\uc0ad\\uc81c\""), "R-delete-label"),
            Triple("a delete dialog", appended(SECTION, "@Composable private fun S7e5a1() = AlertDialog(onDismissRequest = { }, confirmButton = { })"), "R-dialog"),
            // S7 E5b3b: the agreed onAdd and "알림 추가" are counted by sectionAddEdit; any other add name stays an entry.
            Triple("an add button", appended(SECTION, "@Composable private fun S7e5a1(onAddRow: () -> Unit) = TextButton(onClick = onAddRow) { Text(\"새 알림 추가하기\") }"), "S-entry add"),
            Triple("an add icon", appended(SECTION, "private val s7e5a1 = Icons.Default.Add"), "S-entry add"),
            Triple("the count limit", appended(SECTION, "private val s7e5a1 = com.jay.fxi.ui.viewmodel.AlertViewModel.MAX_COUNT"), "S-entry add"),
            Triple("an edit callback in the signature", edited(SECTION, """\bonRefresh\s*:\s*\(\s*\)\s*->\s*Unit\s*,""", "onRefresh: () -> Unit,\n    onEditRow: (AlertSetting) -> Unit,"),
                "S-entry edit"),
            Triple("a parameter added to the signature", edited(SECTION, """\bonRefresh\s*:\s*\(\s*\)\s*->\s*Unit\s*,""", "onRefresh: () -> Unit,\n    onRowTap: (AlertSetting) -> Unit,"),
                "S-signature"),
            Triple("a parameter dropped from the signature", edited(SECTION, """\bonRetry\s*:\s*\(\s*\)\s*->\s*Unit\s*,""", ""), "S-signature"),
            Triple("a public section", edited(SECTION, """\binternal\s+fun\s+AlertSection\s*\(""", "fun AlertSection("), "S-signature"),
            Triple("a row tap", atSectionTag("row", "Modifier.clickable(onClickLabel = \"편집\") { } "), "S-entry edit"),
            Triple("a permission entry", appended(SECTION, "private val s7e5a1 = android.Manifest.permission.POST_NOTIFICATIONS"), "S-entry permission"),
            Triple("an escaped permission", appended(SECTION, "private val s7e5a1 = \"perm\\u0069ssion\""), "S-entry permission"),
            Triple("a settings entry", appended(SECTION, "private fun s7e5a1(c: Context) = c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))"), "S-entry permission"),
            Triple("the legacy row", appended(SECTION, "@Composable private fun S7e5a1(s: AlertSetting) { AlertRow(setting = s, onToggle = { }, onDelete = { }, onTap = { }) }"),
                "S-entry AlertRow"),
            Triple("the legacy row qualified", appended(SECTION, "@Composable private fun S7e5a1(s: AlertSetting) { com.jay.fxi.ui.alert.AlertRow(s, { }, { }, { }) }"),
                "S-entry AlertRow"),
            Triple("the legacy row by alias import", withImport(SECTION, "import com.jay.fxi.ui.alert.AlertRow as LegacyRow"), "S-entry AlertRow"),
            Triple("a function reference to the legacy row", appended(SECTION, "private val s7e5a1 = ::AlertRow"), "S-entry AlertRow"),
            Triple("an escaped legacy row name", appended(SECTION, "private val s7e5a1 = \"Alert\\u0052ow\""), "S-entry AlertRow"),
            Triple("the view model back", withImport(SECTION, "import com.jay.fxi.ui.viewmodel.AlertViewModel"), "S-entry view-model"),
            Triple("the view model state qualified", appended(SECTION, "private val s7e5a1: com.jay.fxi.ui.viewmodel.AlertState? = null"), "S-entry view-model"))) {
            assertCaught("R3 S $label", tag, entries(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(SECTION, "// Switch( onDelete AlertRow( permission 알림 추가 onEdit 삭제\n" +
                "/* IconButton(onClick = { }) Modifier.clickable { } AlertViewModel.MAX_COUNT */\nprivate val s7e5a1Control = 1"),
            "lookalike names" to appended(SECTION, "private val switched = false\nprivate fun AlertRowLike() = Unit\nprivate val s7e5a1Label = \"AlertRows\"\n" +
                "private val unclickable = 0"))) {
            assertClean("R3 S, $label", entries(tree) + sectionClicks(tree))
        }
    }

    @Test fun `S7E5A1_R3_S04 the click lock catches a click in a row the rows branch the section the loading empty or error body and accepts more header clicks`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), sectionClicks(code))
        val aliased = withImport(SECTION, "import androidx.compose.foundation.clickable as tap")
        for ((label, tree, tag) in listOf(
            Triple("a clickable row", atSectionTag("row", "Box(Modifier.clickable { }) { } "), "S-row-click"),
            Triple("a long press on a row", atSectionTag("row", "Modifier.combinedClickable(onClick = { }, onLongClick = { }) "), "S-row-click"),
            Triple("a semantics click action on a row", atSectionTag("row", "Modifier.semantics { onClick(label = null) { true } } "), "S-row-click"),
            Triple("a row toggle", atSectionTag("row", "Modifier.toggleable(value = true, onValueChange = { }) "), "S-row-click"),
            Triple("a row tap gesture", atSectionTag("row", "Modifier.pointerInput(Unit) { detectTapGestures { } } "), "S-row-click"),
            Triple("a row icon button", atSectionTag("row", "IconButton(onClick = { }) { } "), "S-row-click"),
            Triple("a row with a button role", atSectionTag("row", "Modifier.semantics { role = Role.Button } "), "S-row-click"),
            Triple("a backticked clickable row", atSectionTag("row", "Modifier.`clickable` { } "), "S-row-click"),
            Triple("a clickable through an alias import", atSectionTag("row", "Modifier.tap { } ", aliased), "S-click-alias"),
            Triple("an inline clickable in the rows branch", atRowsBranch("Box(Modifier.clickable { }) { }"), "S-row-click"),
            Triple("a row helper with a click", atSectionTag("row", "S7e5a1Tap() ",
                appended(SECTION, "@Composable private fun S7e5a1Tap() { Box(Modifier.clickable { }) { } }")), "S-row-click"),
            Triple("the whole section clickable", atSectionBody("Box(Modifier.clickable { }) { } "), "S-modifier-outside-header"),
            Triple("a clickable loading view", atSectionTag("LOADING", "IconButton(onClick = { }) { } "), "S-action-outside-header"),
            Triple("a clickable empty state", atSectionTag("EMPTY", "Box(Modifier.clickable { }) { } "), "S-modifier-outside-header"),
            Triple("a clickable error card", atSectionTag("RETRY", "Box(Modifier.clickable { }) { } "), "S-modifier-outside-header"),
            Triple("a click helper nobody in the header calls", appended(SECTION, "@Composable private fun S7e5a1() { IconButton(onClick = { }) { } }"),
                "S-action-outside-header"))) {
            assertCaught("R3 S $label", tag, sectionClicks(tree))
        }
        for ((label, tree) in listOf(
            "another click in the header" to atSectionTag("REFRESH", "Box(Modifier.clickable { }) { } "),
            "a second retry button" to atSectionTag("RETRY", "TextButton(onClick = { }) { } "),
            "a header helper" to atSectionTag("REFRESH", "S7e5a1HeaderAction(onClick = { }) ",
                appended(SECTION, "@Composable private fun S7e5a1HeaderAction(onClick: () -> Unit) { IconButton(onClick = onClick) { } }")),
            "comments only" to appended(SECTION, "// Modifier.clickable { } IconButton(onClick = { })\n/* Switch() Role.Button */\nprivate val s7e5a1Control = 1"))) {
            assertClean("R3 S, $label", sectionClicks(tree))
        }
    }

    @Test fun `S7E5A2_S05 AlertSection draws one target-valued switch and one delete button per row and one identity-bound confirmation`() {
        assertEquals("S7E5A2 S: row actions", emptyList<String>(), sectionRowActions(code))
    }

    @Test fun `S7E5A2_S06 the row action lock catches an inverted untagged or extra switch an unconfirmed or re-bound delete and copy drift`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), sectionRowActions(code))
        for ((label, tree, tag) in listOf(
            Triple("an inverted switch", edited(SECTION, """\bonCheckedChange\s*=\s*onToggle\b""", "onCheckedChange = { onToggle(!setting.isEnabled) }"), "R-switch-target"),
            Triple("an untagged switch", edited(SECTION, """\.testTag\(BankAlertSectionTags\.toggle\(setting\.id\)\)""", ""), "R-switch-tag"),
            Triple("a second switch in the header", atSectionTag("REFRESH", "Switch(checked = true, onCheckedChange = { }) "), "R-switch"),
            Triple("a delete without confirmation", edited(SECTION, """pendingDelete = PendingBankDelete\(""", "onDelete(setting.id); PendingBankDelete("), "R-delete-confirm"),
            Triple("a confirmation calling the latest callback", edited(SECTION, """\bpending\s*\.\s*confirm\s*\(\s*\)""", "onDelete(0)"), "R-dialog-confirm"),
            Triple("a pending delete kept across identities", edited(SECTION, """\bremember\s*\(\s*actionIdentity\s*\)""", "remember"), "R-pending-identity"),
            Triple("a changed dialog title", edited(SECTION, """"알림 삭제"""", "\"삭제 확인\""), "R-dialog-copy"),
            Triple("an untagged confirm button", edited(SECTION, """\.testTag\(BankAlertSectionTags\.DELETE_CONFIRM\)""", ""), "R-dialog-tag"),
            Triple("a second toggle call", appended(SECTION, "private fun s7e5a2(onToggle: (Int, Boolean) -> Unit) = onToggle(1, true)"), "R-toggle-call"))) {
            assertCaught("S7E5A2 R $label", tag, sectionRowActions(tree))
        }
        assertClean("S7E5A2 R, comments only", sectionRowActions(appended(SECTION,
            "// Switch( AlertDialog( onDelete( onToggle( 삭제 remember(actionIdentity)\n/* IconButton(onClick = onDelete) */\nprivate val s7e5a2Control = 1")))
    }

    // ---- N: S7 E5b2b2's prompt and banner, and the holder's helper call ----

    @Test fun `S7E5B2B2_N01 AlertSection shows the prompt in Empty and the banner above Rows only when blocked and both call onEnableNotifications`() {
        assertEquals("premise: the prompt's button and the banner's click are the only click spans the notify lock lets through", 2,
            notifySpans(code.getValue(SECTION)).size)
        assertEquals("S7E5B2B2 N: section", emptyList<String>(), sectionNotify(code) + sectionClicks(code) + sectionSignature(code))
    }

    @Test fun `S7E5B2B2_N02 the section lock catches a moved unguarded inverted doubled or rewired prompt or banner and accepts comments and reformatting`() {
        fun all(tree: Map<String, String>) = sectionNotify(tree) + sectionClicks(tree) + sectionSignature(tree) + sectionEntries(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), all(code))
        val promptGuard = """if \(notificationsBlocked\) \{(?=\s*Column\()"""
        val bannerGuard = """if \(notificationsBlocked\) \{(?=\s*Row\()"""
        val bannerClick = """\.clickable\(onClick = onEnableNotifications\)"""
        for ((label, tree, tag) in listOf(
            Triple("an inverted prompt guard", edited(SECTION, promptGuard, "if (!notificationsBlocked) {"), "N-prompt-place"),
            Triple("an unguarded prompt", edited(SECTION, promptGuard, "if (true) {"), "N-prompt-place"),
            Triple("a prompt button in the loading view", atSectionTag("LOADING",
                "Button(onClick = onEnableNotifications, modifier = Modifier.testTag(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)) { } "), "N-prompt-button-place"),
            Triple("a second button in the prompt", edited(SECTION, """(?=\}\s*\}\s*else\s*\{)""", "TextButton(onClick = onEnableNotifications) { }\n"),
                "S-action-outside-header"),
            Triple("the prompt calling retry", edited(SECTION,
                """onClick = onAdd,(?=\s*modifier = Modifier\.testTag\(BankAlertSectionTags\.NOTIFY_PROMPT_BUTTON\))""", "onClick = onRetry,"),
                "N-prompt-callback"),
            // S7 E5b3b (R8-2): the prompt asks for the add entry and the banner for the permission only.
            Triple("the prompt back on the permission only", edited(SECTION,
                """onClick = onAdd,(?=\s*modifier = Modifier\.testTag\(BankAlertSectionTags\.NOTIFY_PROMPT_BUTTON\))""", "onClick = onEnableNotifications,"),
                "N-prompt-callback"),
            Triple("the banner opening the add entry", edited(SECTION, bannerClick, ".clickable(onClick = onAdd)"), "N-banner-click"),
            Triple("a click inside the prompt button", edited(SECTION, """Text\("알림 권한 설정하기"""", "IconButton(onClick = { }) { }\nText(\"알림 권한 설정하기\""),
                "N-prompt-clicks"),
            Triple("a clickable prompt", atSectionTag("NOTIFY_PROMPT", "Modifier.clickable(onClick = onEnableNotifications) "), "S-modifier-outside-header"),
            Triple("a banner in the empty state", atSectionTag("EMPTY", "Modifier.testTag(BankAlertSectionTags.NOTIFY_BANNER) "), "N-banner-place"),
            Triple("an unguarded banner", edited(SECTION, bannerGuard, "if (true) {"), "N-banner-place"),
            Triple("the banner below the list", edited(SECTION,
                """(if \(notificationsBlocked\) \{\s*Row\([\s\S]*?\n {24}\}\n)(\s*Column\(\s*Modifier\s*\.testTag\(BankAlertSectionTags\.ROWS\)[\s\S]*?\n {24}\}\n)""",
                "\$2\$1"), "N-banner-place"),
            Triple("the banner calling refresh", edited(SECTION, bannerClick, ".clickable(onClick = onRefresh)"), "N-banner-click"),
            Triple("a lambda banner click", edited(SECTION, bannerClick, ".clickable { onEnableNotifications() }"), "N-banner-click"),
            Triple("a second banner click", edited(SECTION, bannerClick, ".clickable(onClick = onEnableNotifications).clickable(onClick = onEnableNotifications)"),
                "N-banner-click"),
            Triple("a click inside the banner", edited(SECTION, """Text\("알림 권한을 설정해주세요"""", "IconButton(onClick = { }) { }\nText(\"알림 권한을 설정해주세요\""),
                "S-row-click"),
            Triple("the empty text under the guard", edited(SECTION, """\}\s*else\s*\{(?=\s*Column\(\s*modifier = Modifier\s*\.testTag\(BankAlertSectionTags\.EMPTY\))""", ""),
                "N-empty-shape"),
            Triple("the callback called while drawing", edited(SECTION, promptGuard, "if (notificationsBlocked) { onEnableNotifications()"), "N-callback-uses"),
            Triple("the flag read in the header", atSectionTag("REFRESH", "if (notificationsBlocked) Unit "), "N-flag-uses"),
            Triple("the callback parameter dropped", edited(SECTION, """\s*onEnableNotifications: \(\) -> Unit,""", ""), "S-signature"),
            Triple("a permission-named callback", edited(SECTION, """\bonEnableNotifications\b""", "onRequestPermission"), "S-entry permission"))) {
            assertCaught("S7E5B2B2 N $label", tag, all(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(SECTION, "// Button(onClick = onEnableNotifications) if (notificationsBlocked) NOTIFY_BANNER\n" +
                "/* .clickable(onClick = onEnableNotifications) */\nprivate val s7e5b2b2Control = 1"),
            "a reformatted banner click" to edited(SECTION, bannerClick, ".clickable(\n onClick = onEnableNotifications\n )"))) {
            assertClean("S7E5B2B2 N, $label", all(tree))
        }
    }

    @Test fun `S7E5B2B2_N03 the holder calls the helper once after its tab check and passes needsAction and run only`() {
        assertEquals("premise: the holder's one call is what H lets through", 1, HELPER_CALL.findAll(code.getValue(HOLDER)).count())
        assertEquals("S7E5B2B2 N: holder", emptyList<String>(), holderNotify(code) + holderNames(code))
    }

    @Test fun `S7E5B2B2_N04 the holder lock catches a second moved inverted rewired or aliased helper use and accepts comments and reformatting`() {
        fun all(tree: Map<String, String>) = holderNotify(tree) + holderNames(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), all(code))
        val wiring = """onEnableNotifications = \{ notificationAction\.run\(\) \}"""
        for ((label, tree, tag) in listOf(
            Triple("a second helper call", appended(HOLDER, "@Composable private fun S7e5b2b2() = rememberNotificationPermissionAction()"), "N-holder-call"),
            Triple("the call before the tab check", edited(HOLDER,
                """\n    val currency = bankCurrencyOf\(tab\) \?: return\n    val notificationAction = rememberNotificationPermissionAction\(\)\n""",
                "\n    val notificationAction = rememberNotificationPermissionAction()\n    val currency = bankCurrencyOf(tab) ?: return\n"), "N-holder-wiring"),
            Triple("an inverted action", edited(HOLDER, """notificationsBlocked = notificationAction\.needsAction""", "notificationsBlocked = !notificationAction.needsAction"),
                "N-holder-wiring"),
            Triple("a continuation", edited(HOLDER, wiring, "onEnableNotifications = { notificationAction.run { source.manual() } }"), "N-holder-wiring"),
            Triple("the callback rewired", edited(HOLDER, wiring, "onEnableNotifications = source::manual"), "N-holder-wiring"),
            Triple("the action used once more", edited(HOLDER, """LaunchedEffect\(source, tab\) \{ source\.ensure\(\) \}""",
                "LaunchedEffect(source, tab) { source.ensure(); notificationAction.run() }"), "N-holder-uses"),
            Triple("a function reference to the helper", appended(HOLDER, "private val s7e5b2b2 = ::rememberNotificationPermissionAction"), "H-name permission"),
            Triple("the helper by alias import", withImport(HOLDER, "import com.jay.fxi.ui.alert.rememberNotificationPermissionAction as ask"), "H-name permission"),
            Triple("the environment seam named", appended(HOLDER, "private val s7e5b2b2 = LocalNotificationPermissionEnvironment"), "H-name permission"),
            Triple("the action type named", appended(HOLDER, "private fun s7e5b2b2(a: NotificationPermissionAction) = a.needsAction"), "H-name permission"),
            Triple("an escaped helper call", appended(HOLDER, "private val s7e5b2b2 = \"rememberNotificationPerm\\u0069ssionAction()\""), "N-holder-call"))) {
            assertCaught("S7E5B2B2 N $label", tag, all(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(HOLDER, "// rememberNotificationPermissionAction() permission notificationAction.run()\nprivate val s7e5b2b2Control = 1"),
            "a reformatted wiring" to edited(HOLDER, wiring, "onEnableNotifications = {\n                notificationAction.run()\n            }"))) {
            assertClean("S7E5B2B2 N, $label", all(tree))
        }
    }

    @Test fun `S7E5B2B2_N05 the section reads no notification state directly qualified aliased or through a type alias and accepts comments`() {
        assertTrue("premise: the pattern sees the OS read where it lives",
            Regex(sectionForbidden("notification-os")).containsMatchIn(code.getValue(BASE + "service/NotificationCapability.kt")))
        assertEquals("premise: the real tree is clean", emptyList<String>(), sectionEntries(code))
        val elsewhereAlias = withFile(BYPASS_KT, "typealias S7e5b2b2Nm = androidx.core.app.NotificationManagerCompat")
        for ((label, tree, tag) in listOf(
            Triple("a channel read in the section", atSectionBody(
                "androidx.core.app.NotificationManagerCompat.from(androidx.compose.ui.platform.LocalContext.current).getNotificationChannel(\"rate_alerts\")?.importance\n"),
                "S-entry notification-os"),
            Triple("the capability read", appended(SECTION, "private fun s7e5b2b2(c: android.content.Context) = com.jay.fxi.service.readNotificationCapability(c)"),
                "S-entry notification-os"),
            Triple("the capability type", appended(SECTION, "private val s7e5b2b2: com.jay.fxi.service.NotificationCapability? = null"), "S-entry notification-os"),
            Triple("the monitor", appended(SECTION, "private val s7e5b2b2: com.jay.fxi.service.NotificationCapabilityMonitor? = null"), "S-entry notification-os"),
            Triple("the manager by alias import", withImport(SECTION, "import androidx.core.app.NotificationManagerCompat as Nm"), "S-entry notification-os"),
            Triple("a context in the section", appended(SECTION, "@Composable private fun S7e5b2b2() { val c = androidx.compose.ui.platform.LocalContext.current }"),
                "S-entry notification-os"),
            Triple("an SDK branch", appended(SECTION, "private val s7e5b2b2 = android.os.Build.VERSION.SDK_INT >= 33"), "S-entry notification-os"),
            Triple("the manager behind a type alias elsewhere", appended(SECTION, "private val s7e5b2b2: S7e5b2b2Nm? = null", elsewhereAlias), "S-typealias S7e5b2b2Nm"))) {
            assertCaught("S7E5B2B2 N05 $label", tag, sectionEntries(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(SECTION, "// NotificationManagerCompat getNotificationChannel LocalContext readNotificationCapability\nprivate val s7e5b2b2Control = 1"),
            "a harmless type alias elsewhere" to appended(SECTION, "private val s7e5b2b2: S7e5b2b2Ints? = null",
                withFile(BYPASS_KT, "typealias S7e5b2b2Ints = List<Int>")))) {
            assertClean("S7E5B2B2 N05, $label", sectionEntries(tree))
        }
    }

    @Test fun `S7E5B2B2_N06 the holder makes no notification decision of its own and leaves the helper the only path`() {
        assertTrue("premise: the pattern sees the OS read where it lives",
            Regex(forbidden("notification-os")).containsMatchIn(code.getValue(BASE + "service/NotificationCapability.kt")))
        assertEquals("premise: the real tree is clean", emptyList<String>(), holderNames(code) + holderNotify(code))
        for ((label, tree, tag) in listOf(
            Triple("an SDK and OS gate before the section", edited(HOLDER, """\n(\s*)AlertSection\(""",
                "\n        if (android.os.Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(LocalContext.current).areNotificationsEnabled()) return@key\n\$1AlertSection("),
                "H-name notification-os"),
            Triple("a bare enabled read", appended(HOLDER, "private fun s7e5b2b2(c: Context) = androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()"),
                "H-name notification-os"),
            Triple("the capability read", appended(HOLDER, "private fun s7e5b2b2(c: Context) = com.jay.fxi.service.readNotificationCapability(c)"), "H-name notification-os"),
            Triple("the monitor", appended(HOLDER, "private val s7e5b2b2: com.jay.fxi.service.NotificationCapabilityMonitor? = null"), "H-name notification-os"),
            Triple("the registration coordinator", appended(HOLDER, "private val s7e5b2b2: com.jay.fxi.service.PushRegistrationCoordinator? = null"),
                "H-name notification-os"),
            Triple("a settings screen", appended(HOLDER, "private fun s7e5b2b2(c: Context) = c.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS))"),
                "H-name notification-os"),
            Triple("the manager by alias import", withImport(HOLDER, "import androidx.core.app.NotificationManagerCompat as Nm"), "H-name notification-os"))) {
            assertCaught("S7E5B2B2 N06 $label", tag, holderNames(tree))
        }
        assertClean("S7E5B2B2 N06, comments only", holderNames(appended(HOLDER,
            "// NotificationManagerCompat areNotificationsEnabled Build.VERSION startActivity\nprivate val s7e5b2b2Control = 1")))
    }

    // ---- Q: S7 E5b3b's add area, row editor entry and the holder's one sheet (s7e5b3_api_agreed.r1.md D5, D8) ----
    // Replaces S7E5B2B2_N07 ("neither the holder nor the section opens an add or edit sheet"), which E5b3b opens by agreement.

    @Test fun `S7E5B3B_Q01 the section places its add area after the empty text and the rows and its row editor entry in the row`() {
        assertEquals("S7E5B3B Q: section", emptyList<String>(), sectionAddEdit(code) + sectionClicks(code) + sectionEntries(code))
        assertEquals("premise: one add click and one row open click", listOf(1, 1), listOf(addClicks(code.getValue(SECTION)).size, openClicks(code.getValue(SECTION)).size))
    }

    @Test fun `S7E5B3B_Q02 the add and edit lock catches a moved doubled rewired or drawing-time entry and accepts comments and reformatting`() {
        fun all(tree: Map<String, String>) = sectionAddEdit(tree) + sectionClicks(tree) + sectionEntries(tree) + sectionNotify(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), all(code))
        val addClick = """\.clickable\(onClick = onAdd\)"""
        val openClick = """\.clickable\(onClick = onOpen\)"""
        for ((label, tree, tag) in listOf(
            Triple("the add area under the blocked prompt", edited(SECTION, """Text\("알림 권한 설정하기"\)\s*\}""", "\$0\nBankAlertAddArea(addAvailability, metrics, onAdd)"), "Q-add-place"),
            Triple("the add area in the loading view", atSectionTag("LOADING", "BankAlertAddArea(addAvailability, metrics, onAdd) "), "Q-add-place"),
            Triple("no add area after the empty text", edited(SECTION,
                """(\n\s*)BankAlertAddArea\(addAvailability, metrics, onAdd\)(?=\s*\}\s*\}\s*is Content\.Rows)""", ""), "Q-add-place"),
            Triple("the add button asking for the permission", edited(SECTION, addClick, ".clickable(onClick = onEnableNotifications)"), "Q-add-click"),
            Triple("a lambda add click", edited(SECTION, addClick, ".clickable { onAdd() }"), "Q-add-click"),
            Triple("a second add click", edited(SECTION, addClick, ".clickable(onClick = onAdd).clickable(onClick = onAdd)"), "Q-add-click"),
            Triple("adding while drawing", edited(SECTION, """BankAlertAddAvailability\.Hidden -> Unit""", "BankAlertAddAvailability.Hidden -> onAdd()"), "Q-add-uses"),
            Triple("a clickable cap line", atSectionTag("ADD_AT_CAP", "Modifier.clickable(onClick = onAdd) "), "Q-add-uses"),
            Triple("the row opening a fixed id", edited(SECTION, """onOpen = \{ onEdit\(setting\.id\) \}""", "onOpen = { onEdit(0) }"), "Q-edit-uses"),
            Triple("a long press on the row", edited(SECTION, openClick, ".combinedClickable(onClick = onOpen, onLongClick = onOpen)"), "Q-open-click"),
            Triple("a second row click", edited(SECTION, openClick, ".clickable(onClick = onOpen).clickable(onClick = onOpen)"), "Q-open-click"),
            Triple("the row opening from the header", atSectionTag("REFRESH", "onEdit(1) "), "Q-edit-uses"),
            Triple("a second add copy", appended(SECTION, "private val s7e5b3b = \"알림 추가\""), "Q-add-copy"),
            Triple("a sheet in the section", appended(SECTION, "@Composable private fun S7e5b3b() { AlertAddSheet(onDismiss = { }) }"), "S-entry add"))) {
            assertCaught("S7E5B3B Q $label", tag, all(tree))
        }
        for ((label, tree) in listOf(
            "comments only" to appended(SECTION, "// onAdd() onEdit(1) \"알림 추가\" BankAlertAddArea(\n/* .clickable(onClick = onAdd) */\nprivate val s7e5b3bControl = 1"),
            "a reformatted add click" to edited(SECTION, addClick, ".clickable(\n onClick = onAdd\n )"))) {
            assertClean("S7E5B3B Q, $label", all(tree))
        }
    }

    @Test fun `S7E5B3B_Q03 the holder opens the one sheet in one call and gives the section its add and edit callbacks once`() {
        fun all(tree: Map<String, String>) = holderEditor(tree) + holderNames(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), all(code))
        for ((label, tree, tag) in listOf(
            Triple("a second sheet call in the holder", appended(HOLDER, "@Composable private fun S7e5b3b() { AlertAddSheet(onDismiss = { }) }"), "H-name sheet"),
            Triple("a bottom sheet in the holder", appended(HOLDER, "@Composable private fun S7e5b3b() { androidx.compose.material3.ModalBottomSheet(onDismissRequest = { }) { } }"),
                "H-name sheet"),
            Triple("an edit callback elsewhere in the holder", appended(HOLDER, "private fun s7e5b3b(onEdit: (Int) -> Unit) = onEdit(1)"), "H-name sheet"),
            Triple("a function reference to the sheet", appended(HOLDER, "private val s7e5b3b = ::AlertAddSheet"), "H-name sheet"),
            Triple("the sheet call gone", edited(HOLDER, """\bAlertAddSheet\s*\(""", "AlertAddSheetElsewhere("), "Q-holder-entry"),
            Triple("the add callback outside the section call", edited(HOLDER, """\bonAdd\s*=(?!=)""", "onAddLater ="), "Q-holder-entry"))) {
            assertCaught("S7E5B3B Q03 $label", tag, all(tree))
        }
        assertClean("S7E5B3B Q03, comments only", all(appended(HOLDER, "// AlertAddSheet( onAdd = onEdit = 추가 편집\nprivate val s7e5b3bControl = 1")))
    }

    @Test fun `S7E5B3B_Q04 the owner's save members are reached only through the adapter's one-to-one delegation`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), adapter(code))
        val flat = squash(code.getValue(HOLDER))
        val r = SETTINGS.findAll(flat).single().groupValues[1]
        val add = renamedOverride("add", "draft: BankAlertDraft", "addAlert", "draft", Regex.escape(r))
        fun flatEdit(regex: Regex, replacement: (MatchResult) -> String): Map<String, String> {
            val found = regex.findAll(flat).toList()
            assertEquals("mutation premise: /${regex.pattern}/ is in the squashed holder once", 1, found.size)
            return code + (HOLDER to flat.replaceRange(found.single().range, replacement(found.single())))
        }
        val draft = "BankAlertDraft(\"usd-krw\", \"kb\", com.jay.fxi.domain.model.AlertCondition.BELOW, 1400.0, true, null)"
        for ((label, tree) in listOf(
            "a second add from the holder" to appended(HOLDER, "private fun s7e5b3b(o: BankAlertSettingsOwner) = o.addAlert($draft)"),
            "an edit through a scope function" to appended(HOLDER, "private fun s7e5b3b(o: BankAlertSettingsOwner) = with(o) { editAlert(1, BankAlertEdit()) }"),
            "a function reference" to appended(HOLDER, "private val s7e5b3b = BankAlertSettingsOwner::addAlert"),
            "a save as the factory resolves the owner" to flatEdit(Regex("""\.bankAlertSettingsOwner\(\)""")) { "${it.value}.also { it.addAlert($draft) }" },
            "the delegation doing more" to flatEdit(add) { "override fun add(draft: BankAlertDraft) = $r.addAlert(draft).also { $r.editAlert(1, BankAlertEdit()) }" })) {
            assertCaught("S7E5B3B Q04 $label", "A-owner-save", adapter(tree))
        }
        for ((label, tree) in listOf(
            "a block delegation" to flatEdit(add) { "override fun add(draft: BankAlertDraft): BankAlertSaveRequest { return $r.addAlert(draft) }" },
            "comments only" to appended(HOLDER, "// owner.addAlert(draft) owner.editAlert(id, edit) ::addAlert\nprivate val s7e5b3bControl = 1"),
            "lookalike names" to appended(HOLDER, "private val addAlertLabel = \"알림 추가\"\nprivate val editAlerts = 0"))) {
            assertClean("S7E5B3B Q04, $label", adapter(tree))
        }
    }

    @Test fun `S7E5B3B_Q05 the sheet's remaining is the helper's result and nothing else`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), holderEditor(code))
        for ((label, tree, tag) in listOf(
            Triple("no remaining", edited(HOLDER, """remaining = bankAlertSheetRemaining\(mode, availability\)""", "remaining = null"), "Q-holder-remaining"),
            Triple("always the adding count", edited(HOLDER, """remaining = bankAlertSheetRemaining\(mode, availability\)""",
                "remaining = bankAlertSheetRemaining(BankAlertEditorMode.Add, availability)"), "Q-holder-remaining"),
            Triple("one more than the helper", edited(HOLDER, """remaining = bankAlertSheetRemaining\(mode, availability\)""",
                "remaining = bankAlertSheetRemaining(mode, availability)?.plus(1)"), "Q-holder-remaining"),
            Triple("the helper used elsewhere", appended(HOLDER, "private val s7e5b3b = bankAlertSheetRemaining(BankAlertEditorMode.Add, BankAlertAddAvailability.Hidden)"),
                "Q-holder-remaining-uses"))) {
            assertCaught("S7E5B3B Q05 $label", tag, holderEditor(tree))
        }
        assertClean("S7E5B3B Q05, comments only", holderEditor(appended(HOLDER, "// remaining = bankAlertSheetRemaining(mode, availability)\nprivate val s7e5b3bControl = 1")))
    }

    @Test fun `S7E5B3B_Q06 the outcome is read only for the current editor and the current request`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), holderEditor(code))
        val guard = """if \(handle == null \|\| session\.editor !== open \|\| open\.handle !== handle\) return@LaunchedEffect"""
        for ((label, tree, tag) in listOf(
            Triple("no editor check", edited(HOLDER, guard, "if (handle == null || open.handle !== handle) return@LaunchedEffect"), "Q-holder-outcome-guard"),
            Triple("no request check", edited(HOLDER, guard, "if (handle == null || session.editor !== open) return@LaunchedEffect"), "Q-holder-outcome-guard"),
            Triple("the checks after the first change", edited(HOLDER, """NotSentReason\.RESET -> session\.editor = null""",
                "NotSentReason.RESET -> { session.editor = null; if (session.editor !== open || open.handle !== handle) Unit }",
                edited(HOLDER, guard, "if (handle == null) return@LaunchedEffect")), "Q-holder-outcome-guard"),
            Triple("a second outcome effect", appended(HOLDER, "@Composable private fun S7e5b3b(outcome: Any?) { LaunchedEffect(outcome) { } }"),
                "Q-holder-outcome-effect"),
            // Commit review T1: the whole guard kept, but never run, never returning, or never true.
            Triple("the guard under a false branch", edited(HOLDER, guard, "if (false) {\n                        if (handle == null || session.editor !== open || open.handle !== handle) return@LaunchedEffect\n                    }"),
                "Q-holder-outcome-guard"),
            Triple("the guard without its return", edited(HOLDER, guard, "if (handle == null || session.editor !== open || open.handle !== handle) Unit"),
                "Q-holder-outcome-guard"),
            Triple("the guard made never true", edited(HOLDER, guard, "if ((handle == null || session.editor !== open || open.handle !== handle) && false) return@LaunchedEffect"),
                "Q-holder-outcome-guard"),
            Triple("another statement before the guard", edited(HOLDER, guard,
                "if (outcome == BankAlertSaveOutcome.Removed) session.editor = null\n                    if (handle == null || session.editor !== open || open.handle !== handle) return@LaunchedEffect"),
                "Q-holder-outcome-guard"))) {
            assertCaught("S7E5B3B Q06 $label", tag, holderEditor(tree))
        }
        assertClean("S7E5B3B Q06, the holder squashed onto one line", holderEditor(code + (HOLDER to squash(code.getValue(HOLDER)))))
    }

    @Test fun `S7E5B3B_Q08 the add, edit and save paths read the live snapshot`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), holderEditor(code))
        for ((label, tree, tag) in listOf(
            Triple("edit from the drawn snapshot", edited(HOLDER, """val live = source\.settings\.value(\s+val row = live\.settings)""", "val live = settings$1"),
                "Q-holder-live-onEdit"),
            Triple("the add's identity from the drawn snapshot", edited(HOLDER, """val captured = source\.settings\.value\.identity""", "val captured = settings.identity"),
                "Q-holder-live-onAdd"),
            Triple("the save from the drawn snapshot", edited(HOLDER, """val live = source\.settings\.value(\s+val rows)""", "val live = settings$1"),
                "Q-holder-live-save"),
            Triple("the add continuation from the drawn snapshot", edited(HOLDER, """val live = source\.settings\.value(\s+if \(session\.alive)""",
                "val live = settings$1"), "Q-holder-live-onAdd"))) {
            assertCaught("S7E5B3B Q08 $label", tag, holderEditor(tree))
        }
    }

    @Test fun `S7E5B3B_Q07 each helper run stays in its click and no return path replays a save`() {
        fun all(tree: Map<String, String>) = holderNotify(tree) + holderNames(tree)
        assertEquals("premise: the real tree is clean", emptyList<String>(), all(code))
        val addRun = """notificationAction\.run \{(\s+val live = source\.settings\.value\s+if \(session\.alive)"""
        val saveRun = """notificationAction\.run \{(\s+val live = source\.settings\.value\s+val rows)"""
        fun inBody(line: String, base: Map<String, String> = code) = edited(HOLDER, """val availability = bankAlertAddAvailability\(settings\)""",
            "val availability = bankAlertAddAvailability(settings)\n        " + line, base)
        for ((label, tree, tag) in listOf(
            // Both keep five uses and two continuations: only where the runs are tells them apart.
            Triple("the add run moved to an effect", inBody("LaunchedEffect(availability) { notificationAction.run { } }", edited(HOLDER, addRun, "run {$1")),
                "N-holder-run-place"),
            Triple("the save run moved to an effect", inBody("LaunchedEffect(availability) { notificationAction.run { } }", edited(HOLDER, saveRun, "run {$1")),
                "N-holder-run-place"),
            Triple("the save run moved to a remember", inBody("val s7e5b3b = remember { notificationAction.run { } }", edited(HOLDER, saveRun, "run {$1")),
                "N-holder-run-place"),
            // Return paths a replay would need (gap hunt G09 SETTINGS): the helper's state, the lifecycle, the OS.
            Triple("a replay when the helper stops needing action", inBody("LaunchedEffect(notificationAction.needsAction) { }"), "N-holder-uses"),
            Triple("a replay on resume", inBody("LifecycleResumeEffect(Unit) { onPauseOrDispose { } }"), "H-name lifecycle-observer"),
            Triple("a replay on an OS read", inBody("LaunchedEffect(Unit) { if (readNotificationCapability(applicationContext) != null) Unit }"),
                "H-name notification-os"))) {
            assertCaught("S7E5B3B Q07 $label", tag, all(tree))
        }
        assertClean("S7E5B3B Q07, the holder squashed onto one line", holderNotify(code + (HOLDER to squash(code.getValue(HOLDER)))))
    }

    // Commit review r2 (A5·A5b): while the system's permission dialog is in front the rig reads no Compose root, so "no add editor
    // during the wait" is locked here, by where the editor is built and installed, instead of by a semantics read during the wait.
    @Test fun `S7E5B3B_Q09 the add editor is built and installed only in its helper continuation and the edit editor only in onEdit`() {
        // Small strings exercise only the sheet reader, independently of either production holder.
        fun sheetSnippet(label: String, text: String, vararg conditions: String) {
            val gates = installSheetGates(text)
            assertEquals("Q09 sheet snippet / $label: gate count", conditions.size, gates.size)
            assertEquals("Q09 sheet snippet / $label: caller condition spans",
                conditions.toList(), gates.map { text.substring(it) })
            val spans = conditions.map { condition ->
                val branch = text.indexOf("if ($condition)")
                val from = if (branch >= 0) branch + 4 else text.indexOf("$condition?.let")
                assertTrue("Q09 sheet snippet / $label: expected condition anchor", from >= 0)
                from until (from + condition.length)
            }
            assertEquals("Q09 sheet snippet / $label: exact caller offsets", spans, gates)
        }
        val bridge = """
            @Composable private fun Q09SheetBridge(editor: Q09Editor, done: () -> Unit) {
                AlertAddSheet(editor)
            }
        """.trimIndent()
        fun snippet(body: String, functions: String = bridge) = """
            @Composable private fun BankAlertSection() {
                val open = session.editor
                $body
            }
            $functions
        """.trimIndent()
        val bridgeCall = "Q09SheetBridge(open) { Unit }"
        for ((gate, condition) in listOf("if (open != null)" to "open != null",
                "if (null != open)" to "null != open", "open?.let" to "open")) {
            val argument = if (gate == "open?.let") "it" else "open"
            sheetSnippet("one bridge / $gate", snippet("$gate { Q09SheetBridge($argument) { Unit } }"), condition)
            sheetSnippet("direct sheet / $gate", snippet("$gate { AlertAddSheet($argument) }", ""), condition)
            val direct = gate.replace("open", "session.editor")
            sheetSnippet("direct read / $direct", snippet("$direct { Q09SheetBridge($argument) { Unit } }"),
                condition.replace("open", "session.editor"))
        }
        val guardedBridge = "if (open != null) { $bridgeCall }"
        sheetSnippet("bridge outside gate", snippet(bridgeCall))
        sheetSnippet("bridge inside and outside gate", snippet("$guardedBridge\n$bridgeCall"))
        sheetSnippet("bridge reference", snippet("$guardedBridge\nval saved = ::Q09SheetBridge"))
        sheetSnippet("null then branch", snippet("if (open == null) { $bridgeCall }"))
        sheetSnippet("disjunction", snippet("if (open != null || flag) { $bridgeCall }"))
        sheetSnippet("renamed bridge", snippet(guardedBridge).replace("Q09SheetBridge", "OtherSheetBridge"), "open != null")
        sheetSnippet("two bridge levels", snippet("if (open != null) { Q09Outer(open) { Unit } }",
            bridge + "\n@Composable private fun Q09Outer(editor: Q09Editor, done: () -> Unit) { Q09SheetBridge(editor) { Unit } }"))
        sheetSnippet("overloaded bridge", snippet(guardedBridge,
            bridge + "\n@Composable private fun Q09SheetBridge(editor: Q09Editor) { Unit }"))
        sheetSnippet("declaration inside gate is not a call", snippet("if (open != null) { $bridge }", ""))

        // Closed statement paths: keep the original 18 controls above unchanged.
        sheetSnippet("P11 direct key", snippet(
            "if (open != null) { key(open) { AlertAddSheet(open) } }", ""), "open != null")
        sheetSnippet("P12 direct nested key", snippet(
            "if (open != null) { key(a) { key(b) { AlertAddSheet(open) } } }", ""), "open != null")
        sheetSnippet("P13 bridge top-level sheet", snippet(guardedBridge), "open != null")
        sheetSnippet("P14 bridge body key", snippet(guardedBridge,
            bridge.replace("AlertAddSheet(editor)", "key(editor) { AlertAddSheet(editor) }")), "open != null")
        sheetSnippet("P15 bridge caller and body key", snippet(
            "if (open != null) { key(a) { key(b) { $bridgeCall } } }",
            bridge.replace("AlertAddSheet(editor)", "key(editor) { AlertAddSheet(editor) }")), "open != null")
        val deferredDirect = "slot.content = { AlertAddSheet(open) }"
        sheetSnippet("N-S09 direct stored lambda", snippet(
            "if (open != null) { $deferredDirect }; slot.content?.invoke()", ""))
        sheetSnippet("N-S10 direct stored lambda under key", snippet(
            "if (open != null) { key(open) { $deferredDirect } }; slot.content?.invoke()", ""))
        sheetSnippet("N-S11 stored bridge call", snippet(
            "if (open != null) { slot.content = { $bridgeCall } }; slot.content?.invoke()"))
        sheetSnippet("N-S12 stored sheet in bridge body", snippet(guardedBridge,
            bridge.replace("AlertAddSheet(editor)", "slot.content = { AlertAddSheet(editor) }")))
        val emptyBridge = bridge.replace("AlertAddSheet(editor)", "Unit")
        sheetSnippet("N-S13 sheet in bridge trailing lambda", snippet(
            "if (open != null) { Q09SheetBridge(open) { AlertAddSheet(open) } }", emptyBridge))
        sheetSnippet("N-S14 direct run", snippet(
            "if (open != null) { run { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S15 direct Box", snippet(
            "if (open != null) { Box { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S16 anonymous function", snippet(
            "if (open != null) { val saved = fun() { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S17 sheet in bridge argument lambda", snippet(
            "if (open != null) { Q09SheetBridge(open, { AlertAddSheet(open) }) }", emptyBridge))
        for (wrapper in listOf("remember", "LaunchedEffect(open)", "with(open)")) {
            sheetSnippet("N-S18-20 direct $wrapper", snippet(
                "if (open != null) { $wrapper { AlertAddSheet(open) } }", ""))
        }
        sheetSnippet("N-S21 object expression", snippet(
            "if (open != null) { val saved = object { @Composable fun Render() = AlertAddSheet(open) } }", ""))

        // Whole-statement closure: all thirteen new negatives have zero gates and no condition spans.
        sheetSnippet("N-S22 E1 expression-body local function", snippet(
            "if (open != null) { @Composable fun Later() = AlertAddSheet(open); slot.content = { Later() } }; slot.content?.invoke()", ""))
        sheetSnippet("N-S23 E2 default argument", snippet(
            "if (open != null) { @Composable fun Later(ignored: Unit = AlertAddSheet(open)) { Unit }; slot.content = { Later() } }; slot.content?.invoke()", ""))
        sheetSnippet("N-S24 assigned key", snippet(
            "if (open != null) { val x = key(a) { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S25 sheet in call argument", snippet(
            "if (open != null) { Foo(AlertAddSheet(open)) }", ""))
        sheetSnippet("N-S26 annotated sheet", snippet(
            "if (open != null) { @Composable AlertAddSheet(open) }", ""))
        sheetSnippet("N-S27 labelled key", snippet(
            "if (open != null) { later@ key(a) { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S28 assigned bridge call", snippet(
            "if (open != null) { val r = $bridgeCall }"))
        sheetSnippet("N-S29 E1 newline after equals", snippet(
            "if (open != null) { @Composable fun Later() =\nAlertAddSheet(open); slot.content = { Later() } }; slot.content?.invoke()", ""))
        sheetSnippet("N-S30 assigned key newline", snippet(
            "if (open != null) { val x =\nkey(a) { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S31 annotation newline", snippet(
            "if (open != null) { @Composable\nAlertAddSheet(open) }", ""))
        sheetSnippet("N-S32 label newline", snippet(
            "if (open != null) { later@\nkey(a) { AlertAddSheet(open) } }", ""))
        sheetSnippet("N-S33 E1 in bridge body", snippet(guardedBridge,
            bridge.replace("AlertAddSheet(editor)",
                "@Composable fun Later() = AlertAddSheet(editor); slot.content = { Later() }")))
        sheetSnippet("N-S34 E2 in bridge body", snippet(guardedBridge,
            bridge.replace("AlertAddSheet(editor)",
                "@Composable fun Later(ignored: Unit = AlertAddSheet(editor)) { Unit }; slot.content = { Later() }")))
        // Reader-only control: symbol binding is intentionally a holderInstall responsibility.
        sheetSnippet("P16 reader keeps syntactic key recognition", snippet(
            "if (open != null) { key(open) { AlertAddSheet(open) } }",
            "fun key(vararg k: Any?, content: @Composable () -> Unit) { slot.content = content }"), "open != null")

        // L2: the gate itself starts its statement, for direct and one-bridge paths alike.
        for ((gate, argument) in listOf("if (open != null)" to "open", "open?.let" to "it")) {
            for ((label, prefix) in listOf(
                    "expression body" to "@Composable fun Later() = ",
                    "assigned" to "val y = ",
                    "return" to "return ",
                    "annotation" to "@Composable ",
                    "label" to "later@ ",
                    "expression body newline" to "@Composable fun Later() =\n",
                    "assigned newline" to "val y =\n",
                    "return newline" to "return\n",
                    "annotation newline" to "@Composable\n",
                    "label newline" to "later@\n")) {
                sheetSnippet("L2 direct / $gate / $label", snippet(
                    "$prefix$gate { AlertAddSheet($argument) }", ""))
                sheetSnippet("L2 caller / $gate / $label", snippet(
                    "$prefix$gate { Q09SheetBridge($argument) { Unit } }"))
            }
        }

        // Each format traverses the complete procedure: clean tree, spans, mutations, exact violations.
        val raw = File(HOLDER).readText()
        val rawTree = code + (HOLDER to strip(HOLDER, raw))
        assertEquals("premise: raw strip equals code map", code.getValue(HOLDER), rawTree.getValue(HOLDER))
        fun formatted(source: String, gap: String? = null, arguments: Boolean = false): String {
            val calls = installScan(source).writes.filter { it.call != null }
            return calls.sortedByDescending { it.span.first }.fold(source) { text, write ->
                val call = write.call!!
                if (gap != null) text.replaceRange(write.equalsEnd, call.first, gap)
                else if (arguments) {
                    val open = text.indexOf('(', call.first)
                    text.replaceRange(open + 1, open + 1, "\n        ")
                } else text
            }
        }
        val variants = listOf(
            "actual" to rawTree,
            "squash" to (code + (HOLDER to squash(code.getValue(HOLDER)))),
            "newline after equals" to (code + (HOLDER to strip(HOLDER, formatted(raw, gap = "\n    ")))),
            "newline in arguments" to (code + (HOLDER to strip(HOLDER, formatted(raw, arguments = true)))),
            // Insert into SOURCE, then use this test's actual strip path.
            "inline comment" to (code + (HOLDER to strip(HOLDER, formatted(raw, gap = " /* Q09 inline */ ")))),
            "long indentation" to (code + (HOLDER to strip(HOLDER, formatted(raw, gap = "\n" + " ".repeat(64))))))
        for ((format, base) in variants) {
            assertClean("Q09 $format", holderInstall(base))
            val holder = base.getValue(HOLDER)
            val scan = installScan(holder)
            val add = scan.writes.singleOrNull { it.mode == "add" && it.direct }
            val edit = scan.writes.singleOrNull { it.mode == "edit" && it.direct }
            assertNotNull("Q09 $format: add spans", add)
            assertNotNull("Q09 $format: edit spans", edit)
            val a = add!!
            val e = edit!!
            val braces = pairs(holder, '{', '}')
            val onAdd = Regex("""\bonAdd\s*=\s*\{""").find(holder)?.let { m -> braces.firstOrNull { it.first == m.range.last } }
            assertNotNull("Q09 $format: onAdd", onAdd)
            val run = Regex("""\bnotificationAction\s*\.\s*run\s*\{""").findAll(holder).singleOrNull { it.range.first in onAdd!! }
            assertNotNull("Q09 $format: add run", run)
            val runEnd = closing(holder, run!!.range.last)
            assertNotNull("Q09 $format: run end", runEnd)
            assertTrue("Q09 $format: add in run", a.span.first in run.range.last..runEnd!!)
            val addText = holder.substring(a.span)
            val editText = holder.substring(e.span)
            val rhs = holder.substring(a.call!!)
            val type = scan.type!!
            val tickRhs = rhs.replaceRange(0, type.length, "`$type`")
            val beforeRun = run.range.first
            val dropAdd = Triple(a.span.first, a.span.last + 1, "Unit")
            fun rewrite(vararg edits: Triple<Int, Int, String>): Map<String, String> =
                base + (HOLDER to edits.sortedByDescending { it.first }.fold(holder) { text, (s, end, replacement) ->
                    text.replaceRange(s, end, replacement)
                })
            fun replaceAdd(replacement: String) = rewrite(Triple(a.span.first, a.span.last + 1, replacement))
            fun before(line: String) = rewrite(Triple(beforeRun, beforeRun, "$line;\n"))
            fun restoredEarly(expression: String, property: String = "editor") = rewrite(
                Triple(beforeRun, beforeRun, "session.$property = $expression;\n"),
                Triple(run.range.last + 1, run.range.last + 1, "\nsession.$property = null;\n"))
            fun expected(label: String, tree: Map<String, String>, vararg tags: String) {
                assertEquals("Q09 $format / $label", tags.toList().sorted(), holderInstall(tree).sorted())
            }
            // Original six controls; assert the COMPLETE list rather than one required tag.
            expected("N01 add before run", rewrite(Triple(beforeRun, beforeRun, "$addText;\n"), dropAdd),
                "Q-holder-install-add")
            expected("N02 add after run", rewrite(dropAdd, Triple(runEnd + 1, runEnd + 1, "\n$addText;")),
                "Q-holder-install-add")
            expected("N03 duplicate add", before(addText), "Q-holder-install-assign", "Q-holder-install-mode")
            expected("N04 built early, installed by name", rewrite(
                Triple(beforeRun, beforeRun, "val s7e5b3bEditor = $rhs;\n"),
                Triple(a.span.first, a.span.last + 1, "session.editor = s7e5b3bEditor")),
                "Q-holder-install-alias", "Q-holder-install-assign", "Q-holder-install-rhs", "Q-holder-install-add")
            expected("N05 scope write", rewrite(Triple(beforeRun, beforeRun, "session.apply { editor = $rhs };\n"), dropAdd),
                "Q-holder-install-write", "Q-holder-install-alias", "Q-holder-install-assign", "Q-holder-install-add")
            expected("N06 edit inside run", rewrite(Triple(e.span.first, e.span.last + 1, "notificationAction.run { $editText }")),
                "Q-holder-install-edit")
            expected("N07 B1", restoredEarly("null ?: $tickRhs"),
                "Q-holder-install-backtick", "Q-holder-install-rhs", "Q-holder-install-alias", "Q-holder-install-assign", "Q-holder-install-mode")
            expected("N08 B1a property", restoredEarly(rhs, "`editor`"),
                "Q-holder-install-backtick", "Q-holder-install-assign", "Q-holder-install-mode")
            expected("N09 B1a property and type", restoredEarly(tickRhs, "`editor`"),
                "Q-holder-install-backtick", "Q-holder-install-assign", "Q-holder-install-mode")
            val aliasRhs = rhs.replaceRange(0, type.length, "S7e5b3bQ09Alias")
            val aliasTree = restoredEarly("null ?: $aliasRhs")
            expected("N10 B1b typealias", aliasTree + (HOLDER to
                (aliasTree.getValue(HOLDER) + "\nprivate typealias S7e5b3bQ09Alias = $type\n")),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            val importAt = Regex("""\bimport\s+""").find(holder)
            assertNotNull("Q09 $format: import insertion", importAt)
            val packageName = Regex("""\bpackage\s+([\w.]+)""").find(holder)!!.groupValues[1]
            expected("N11 B1b import alias", rewrite(
                Triple(importAt!!.range.first, importAt.range.first, "import $packageName.$type as S7e5b3bQ09Alias;\n"),
                Triple(beforeRun, beforeRun, "session.editor = null ?: $aliasRhs;\n"),
                Triple(run.range.last + 1, run.range.last + 1, "\nsession.editor = null;\n")),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            // This LOCAL NAME is generated here, not assumed to exist in either implementation.
            fun kept(expression: String) = rewrite(
                Triple(beforeRun, beforeRun, "val s7e5b3bKept = session.editor;\nsession.editor = $expression;\n"),
                Triple(run.range.last + 1, run.range.last + 1, "\nsession.editor = null;\n"))
            expected("N12 next-line Elvis", kept("null\n?: s7e5b3bKept"),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            expected("N13 null let", kept("null.let { s7e5b3bKept }"),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            expected("N14 null Elvis kept", kept("null ?: s7e5b3bKept"),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            expected("N15 backtick constructor", replaceAdd("session.editor = $tickRhs"),
                "Q-holder-install-backtick")
            expected("N16 next-line dot", kept("null\n.let { s7e5b3bKept }"),
                "Q-holder-install-rhs", "Q-holder-install-assign")
            expected("N17 constructor suffix", replaceAdd("$addText.let { it }"), "Q-holder-install-rhs")
            expected("N18 parentheses", replaceAdd("session.editor = ($rhs)"),
                "Q-holder-install-rhs", "Q-holder-install-alias", "Q-holder-install-assign")
            expected("N19 safe call", replaceAdd("$addText?.let { it }"), "Q-holder-install-rhs")
            val typeToken = scan.tokens.single { token ->
                token.name == type && scan.tokens.indexOf(token).let { index ->
                    scan.tokens.getOrNull(index - 1)?.name == "<" &&
                        scan.tokens.getOrNull(index - 2)?.name == "mutableStateOf"
                }
            }
            expected("N22 backtick generic type", rewrite(Triple(typeToken.start, typeToken.end,
                96.toChar() + type + 96.toChar())), "Q-holder-install-backtick")
            val propertyToken = scan.tokens.single { token ->
                token.name == "editor" && scan.tokens.indexOf(token).let { index ->
                    scan.tokens.getOrNull(index - 1)?.name == "var" && scan.tokens.getOrNull(index + 1)?.name == "by"
                }
            }
            expected("N23 backtick property declaration", rewrite(Triple(propertyToken.start, propertyToken.end,
                96.toChar() + "editor" + 96.toChar())), "Q-holder-install-backtick")
            val gates = installSheetGates(holder)
            assertEquals("Q09 $format: sheet gate anchor", 1, gates.size)
            expected("N20 sheet gate bypass", rewrite(*gates.map { gate ->
                Triple(gate.first, gate.last + 1, "true")
            }.toTypedArray()), "Q-holder-install-sheet")
            expected("N21 sheet gate disjunction", rewrite(*gates.map { gate ->
                Triple(gate.first, gate.last + 1, "(" + holder.substring(gate) + ") || true")
            }.toTypedArray()), "Q-holder-install-sheet")
            // One anchor rule for both the direct/key and the one-bridge implementation.
            val path = installSheetPaths(holder).single()
            val statement = installSheetStatement(holder, path)
            assertNotNull("Q09 $format: N24 top-level sheet/bridge statement", statement)
            val held = holder.substring(statement!!)
            val slotName = "s7e5b3bQ09Slot"
            val slotType = "S7e5b3bQ09SheetSlot"
            assertTrue("Q09 $format: N24 fresh generated names",
                scan.tokens.none { it.name == slotName || it.name == slotType })
            val delayed = rewrite(
                Triple(path.guard, path.guard, "val $slotName = remember { $slotType() };\n"),
                Triple(statement.first, statement.last + 1, "$slotName.content = { $held }"),
                Triple(path.block.last + 1, path.block.last + 1, "\n$slotName.content?.invoke();\n"))
            expected("N24 stored sheet path invoked after gate", delayed + (HOLDER to
                (delayed.getValue(HOLDER) + "\nprivate class $slotType { var content: (@Composable () -> Unit)? = null }\n")),
                "Q-holder-install-sheet")
            expected("N25 competing key declaration", base + (HOLDER to (holder +
                "\n@Composable private fun key(vararg k: Any?, content: @Composable () -> Unit) { Unit }\n")),
                "Q-holder-install-key")
            val composeKey = listOf("import", "androidx", ".", "compose", ".", "runtime", ".", "key")
            val keyImport = scan.tokens.windowed(composeKey.size).single { part -> part.map { it.name } == composeKey }
            val afterKeyImport = scan.tokens.indexOf(keyImport.last()) + 1
            val keyImportEnd = scan.tokens.getOrNull(afterKeyImport)?.takeIf { it.name == ";" }?.end ?: keyImport.last().end
            expected("N26 missing Compose key import", rewrite(
                Triple(keyImport.first().start, keyImportEnd, "")), "Q-holder-install-key")
            // Binding-only positives use holderInstall, not just the syntactic sheet reader.
            // These are lexical fixtures; x/source/handle do not need runtime definitions.
            for ((label, body) in listOf(
                    "P17 key after delegated declaration" to
                        "val quotes by rememberUpdatedState(x)\nkey(source) { Unit }",
                    "P18 key after lambda arrow" to
                        "val outcome = handle?.let { h -> key(h) { Unit } }",
                    "P19 key on assignment RHS" to
                        "val installed = key(source) { Unit }")) {
                val control = "\n@Composable private fun s7e5b3bKeyBindingControl() {\n$body\n}\n"
                val suffix = if (format == "squash") "\n" + squash(control) else control
                expected(label, base + (HOLDER to (holder + suffix)))
            }
            expected("N27 L1 extension let declaration", base + (HOLDER to (holder +
                "\n@Composable private fun $type.let(content: @Composable ($type) -> Unit) { Unit }\n")),
                "Q-holder-install-let")
            expected("N28 L1 let import alias", rewrite(
                Triple(importAt.range.first, importAt.range.first, "import q09.bindings.capture as let;\n")),
                "Q-holder-install-let")
            val comments = raw + "\n// session.editor = $type(BankAlertEditorMode.Add)\n" +
                "/* session.editor = null ?: " + "`$type`" + "(BankAlertEditorMode.Add) */\n"
            assertClean("Q09 $format: comments", holderInstall(base + (HOLDER to strip(HOLDER, comments))))
        }
    }

    // ---- P: the premium route's mount ----

    @Test fun `S7E5A1_R3_P01 the route calls BankAlertSection once inside belowRates after the unchanged BankHistoryEntry passing its factory parameter`() {
        val route = code.getValue(ROUTE)
        val slot = belowRates(route)
        assertNotNull("premise(R3 P): the route passes belowRates as the agreed if-lambda-else-null", slot)
        val body = route.substring(slot!!.body)
        assertTrue("premise(R3 P): the lambda keeps the history entry", HISTORY_CALL.containsMatchIn(body))
        assertTrue("premise(R3 P): the section call is observed inside the lambda (the window this lock watches)", CALL.containsMatchIn(body))
        assertEquals("R3 P: the mount", emptyList<String>(), routeMount(code))
    }

    @Test fun `S7E5A1_R3_P02 the route lock catches a second moved aliased qualified referenced or unfactoried call and accepts comments and reformatting`() {
        assertEquals("premise: the real tree is clean", emptyList<String>(), routeMount(code))
        val route = code.getValue(ROUTE)
        val slot = belowRates(route)
        assertNotNull("mutation premise: the belowRates slot is found", slot)
        val p = slot!!.param
        val f = "bankAlertSectionSourceFactory"
        assertEquals("mutation premise: the route has one history entry call", 1, HISTORY_CALL.findAll(route).count())
        fun callText(text: String, call: IntRange) = text.substring(call.first, call.last + 1)
        for ((label, tree, tag) in listOf(
            Triple("a second call in the lambda", routeWith { t, c -> t.replaceRange(c, callText(t, c) + "\n" + callText(t, c)) }, "P-calls"),
            Triple("the call before the history entry", routeWith { t, c ->
                val removed = t.removeRange(c)
                val entry = HISTORY_CALL.find(removed)!!
                removed.substring(0, entry.range.first) + callText(t, c) + "\n" + removed.substring(entry.range.first)
            }, "P-order"),
            Triple("the call outside belowRates", routeWith { t, c ->
                t.replaceRange(c, "Unit") + "\n@Composable private fun S7e5a1Elsewhere($p: FreeTab) {\n    ${callText(t, c)}\n}\n"
            }, "P-lambda"),
            Triple("the call in another file", routeWith { t, c -> t.replaceRange(c, "Unit") }.let {
                withFile(BYPASS_KT, "@Composable fun S7e5a1Bypass($p: FreeTab) { ${callText(route, sectionCall(route)!!)} }", it)
            }, "P-calls"),
            Triple("no factory passed", routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = $p)") }, "P-args"),
            Triple("the production factory passed directly",
                routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = $p, sourceFactory = ProductionBankAlertSectionSourceFactory)") }, "P-args"),
            Triple("another tab passed", routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = FreeTab.USD, alertQuotes = screen.ui.alertQuotes, sourceFactory = $f)") }, "P-args"),
            // S7 E5b3b: the quotes are the screen's own, passed once.
            Triple("other quotes passed", routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = $p, alertQuotes = emptyMap(), sourceFactory = $f)") }, "P-args"),
            Triple("no quotes passed", routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = $p, sourceFactory = $f)") }, "P-args"),
            Triple("an alias import", routeWith(withImport(ROUTE, "import com.jay.fxi.ui.alert.BankAlertSection as Section")) { t, c ->
                t.replaceRange(c, callText(t, c).replaceFirst("BankAlertSection", "Section"))
            }, "P-alias"),
            Triple("a second qualified call", routeWith { t, c -> t.replaceRange(c, callText(t, c) + "\ncom.jay.fxi.ui.alert." + callText(t, c)) }, "P-calls"),
            Triple("a function reference", appended(ROUTE, "private val s7e5a1 = ::BankAlertSection"), "P-function-reference"),
            Triple("an escaped reference elsewhere", withFile(BYPASS_KT, "val s7e5a1Name = \"com.jay.fxi.ui.alert.BankAlert\\u0053ection\""), "P-named"),
            Triple("a Java call", withFile(BYPASS_JAVA, "class Bypass { void go() { BankAlertSectionKt.BankAlertSection(null, null, null); } }"), "P-calls"),
            Triple("the route building a source", appended(ROUTE, "private fun s7e5a1(context: Context) = $f.create(context)"), "P-factory-tokens"),
            Triple("the route naming the owner", appended(ROUTE, "private val s7e5a1: com.jay.fxi.data.alerts.BankAlertSettingsOwner? = null"), "P-route-owner"),
            Triple("the route naming the owner's state", appended(ROUTE, "private val s7e5a1: com.jay.fxi.data.alerts.ManualRefreshState? = null"), "P-route-owner"),
            Triple("the factory parameter not last",
                edited(ROUTE, """\bbankAlertSectionSourceFactory\s*:\s*BankAlertSectionSourceFactory\s*=\s*ProductionBankAlertSectionSourceFactory\b""",
                    "\$0,\n    s7e5a1Extra: Int = 0"), "P-parameter"),
            Triple("a wider slot", routeWith { t, _ ->
                t.replaceRange(belowRates(t)!!.condition, "screen.ui.selectedTab != FreeTab.NEWS && !settingsOpen && detailUrl == null")
            }, "P-condition"),
            Triple("the history entry's arguments changed", routeWith { t, _ ->
                val open = t.indexOf('(', HISTORY_CALL.find(t)!!.range.first)
                t.replaceRange(open + 1, closing(t, open)!!, "tab = $p, owner = owner")
            }, "P-history-entry"))) {
            assertCaught("R3 P $label", tag, routeMount(tree))
        }
        val unimported = Regex("""(?m)^[ \t]*import[ \t]+com\.jay\.fxi\.ui\.alert\.BankAlertSection[ \t]*\r?\n""")
        for ((label, tree) in listOf(
            "comments only" to appended(ROUTE, "// BankAlertSection(tab = tab)\n/* ::BankAlertSection $f.create(context) BankAlertSettingsOwner */\nprivate val s7e5a1Control = 1"),
            "named arguments swapped and reformatted" to routeWith { t, c ->
                t.replaceRange(c, "BankAlertSection(\n    sourceFactory = $f,\n    alertQuotes = screen.ui.alertQuotes,\n    tab = $p,\n)")
            },
            "a positional tab and a modifier" to routeWith { t, c ->
                t.replaceRange(c, "BankAlertSection($p, alertQuotes = screen.ui.alertQuotes, modifier = Modifier, sourceFactory = $f)")
            },
            "one qualified call without an import" to routeWith(code + (ROUTE to unimported.replace(route, ""))) { t, c ->
                t.replaceRange(c, "com.jay.fxi.ui.alert.BankAlertSection(tab = $p, alertQuotes = screen.ui.alertQuotes, sourceFactory = $f)")
            })) {
            assertClean("R3 P, $label", routeMount(tree))
        }
    }

    // ---- mutation helpers: each asserts its own anchor, so no mutation can silently be the real tree ----

    private fun assertCaught(label: String, tag: String, violations: List<String>) =
        assertTrue("mutation($label): the lock reports $tag; it reported $violations", violations.any { it == tag || it.startsWith("$tag ") })

    private fun assertClean(label: String, violations: List<String>) =
        assertEquals("control($label): not a violation", emptyList<String>(), violations)

    private fun withFile(path: String, source: String, base: Map<String, String> = code): Map<String, String> {
        assertTrue("mutation premise: $path is a new file", path !in base)
        return base + (path to strip(path, source))
    }

    private fun appended(path: String, source: String, base: Map<String, String> = code): Map<String, String> {
        assertTrue("mutation premise: $path is scanned", path in base)
        return base + (path to base.getValue(path) + "\n" + strip(path, source) + "\n")
    }

    private fun withImport(path: String, line: String, base: Map<String, String> = code): Map<String, String> {
        val text = base.getValue(path)
        val pkg = Regex("""(?m)^package\b[^\n]*\n""").find(text)
        assertNotNull("mutation premise: $path has a package line", pkg)
        return base + (path to text.substring(0, pkg!!.range.last + 1) + line + "\n" + text.substring(pkg.range.last + 1))
    }

    private fun edited(path: String, pattern: String, replacement: String, base: Map<String, String> = code): Map<String, String> {
        val text = base.getValue(path)
        val regex = Regex(pattern)
        assertTrue("mutation premise: /$pattern/ is in $path", regex.containsMatchIn(text))
        return base + (path to regex.replaceFirst(text, replacement))
    }

    /** [insert] placed just before AlertSection's one use of `BankAlertSectionTags.[tag]`: inside the window that tag marks. */
    private fun atSectionTag(tag: String, insert: String, base: Map<String, String> = code): Map<String, String> {
        val text = base.getValue(SECTION)
        val found = tagPattern(tag).findAll(text).toList()
        assertEquals("mutation premise: AlertSection uses BankAlertSectionTags.$tag once", 1, found.size)
        val at = found.single().range.first
        return base + (SECTION to text.substring(0, at) + insert + text.substring(at))
    }

    /** [insert] placed right after the content dispatch's `Rows ->`: inside the Rows branch itself. */
    private fun atRowsBranch(insert: String): Map<String, String> {
        val text = code.getValue(SECTION)
        val rows = LABEL.findAll(text).filter { it.groupValues[1] == "Rows" }.toList()
        assertEquals("mutation premise: AlertSection has one Rows branch", 1, rows.size)
        val at = rows.single().range.last + 1
        return code + (SECTION to text.substring(0, at) + " " + insert + text.substring(at))
    }

    /** [insert] placed first in AlertSection's own body: outside the header, the content and the row render. */
    private fun atSectionBody(insert: String): Map<String, String> {
        val text = code.getValue(SECTION)
        val body = bodyRange(text, "AlertSection")
        assertNotNull("mutation premise: AlertSection has a block body", body)
        return code + (SECTION to text.substring(0, body!!.first) + insert + text.substring(body.first))
    }

    private fun routeWith(base: Map<String, String> = code, transform: (text: String, call: IntRange) -> String): Map<String, String> {
        val text = base.getValue(ROUTE)
        val call = sectionCall(text)
        assertNotNull("mutation premise: the route's one BankAlertSection call is found", call)
        val changed = transform(text, call!!)
        assertNotEquals("mutation premise: the edit changes the route", text, changed)
        return base + (ROUTE to changed)
    }

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val HOLDER = BASE + "ui/alert/BankAlertSection.kt"
        val SECTION = BASE + "ui/alert/AlertSection.kt"
        val ROW = BASE + "ui/alert/AlertRow.kt"
        val ROUTE = BASE + "ui/premium/view/PremiumTopicRoute.kt"
        val OWNER = BASE + "data/alerts/BankAlertSettingsOwner.kt"
        val REGISTRY = BASE + "data/alerts/AlertFamilyRefresherRegistry.kt"
        val VM = BASE + "ui/viewmodel/AlertViewModel.kt"
        val BYPASS_KT = BASE + "ui/alert/S7e5a1Bypass.kt"
        val BYPASS_JAVA = BASE + "ui/alert/S7e5a1Bypass.java"
        const val DATA_ALERTS = "/com/jay/fxi/data/alerts/"

        /** API r2 §2: the owner members the adapter uses. */
        val OWNER_ALLOWED = setOf("state", "manualRefresh", "ensure", "authoritative", "manual", "setEnabled", "delete", "addAlert", "editAlert")
        /** The N1 owner members the holder must not use (the reader computes the full set from the owner's source). */
        val OWNER_OTHERS = setOf("manualOutcomes", "reset", "onForeground", "onBackground", "onSync")

        /** API r2 §2 / §5 row 3: what the holder never names. */
        val HOLDER_FORBIDDEN = listOf(
            "AlertViewModel" to """\bAlertViewModel\b""",
            "AlertState" to """\bAlertState\b""",
            "AlertRepository" to """\bAlertRepository\b""",
            "AlertSettingsAccess" to """\bAlertSettingsAccess\b""",
            "AlertRateLimitFloor" to """\bAlertRateLimitFloor\b""",
            "AlertFamilyRefresherRegistry" to """\bAlertFamilyRefresherRegistry\b""",
            "permission" to """(?i)permission""",
            "PushNotificationManager" to """\bPushNotificationManager\b""",
            "registerIfNeeded" to """\bregisterIfNeeded\b""",
            "push" to """\bFirebaseMessaging\w*|\bgetToken\b|\bregisterDevice\b|\bonNewToken\b""",
            "DataStore" to """(?i)datastore""",
            "SharedPreferences" to """SharedPreferences""",
            "LifecycleEventObserver" to """\bLifecycleEventObserver\b""",
            "ProcessLifecycleOwner" to """\bProcessLifecycleOwner\b""",
            "LocalLifecycleOwner" to """\bLocalLifecycleOwner\b""",
            "lifecycle-observer" to """\baddObserver\b|\bremoveObserver\b|\b\w*LifecycleObserver\b|\bLifecycle(?:Resume|Start|Event)Effect\b|\brepeatOnLifecycle\b""",
            // S7 E5b2b2 (gap hunt G02): every notification decision stays in the helper.
            "notification-os" to """\bNotificationManager\w*|\bgetNotificationChannels?\b|\bareNotificationsEnabled\b|\breadNotificationCapability\b|\bNotificationCapability\w*|\bPushRegistration\w*|\bPushIntentReconciler\b|\bgetSystemService\b|\bstartActivit(?:y|ies)\w*|\bACTION_\w*SETTINGS\b|\bBuild\s*\.\s*VERSION\b|\bActivityResultContracts\b|\brememberLauncherForActivityResult\b""",
            // S7 E5b2b2 (gap hunt G05, start agreement §4-2): the holder opens no add or edit sheet.
            "sheet" to """\bAlertAddSheet\b|\bAlertEdit\w*|\bAddAlert\w*|\bonAdd\w*|\bonEdit\w*|\b\w*BottomSheet\w*|\bFloatingActionButton\b|추가|편집|수정""")

        /** API r2 §3 / §5 row 3: what AlertSection never draws or names. */
        val SECTION_FORBIDDEN = listOf(
            // S7 E5a2: the row switch, the delete button and its dialog are governed by sectionRowActions instead.
            "toggle-other" to """\bCheckbox\b|\bTriStateCheckbox\b|\btoggleable\b|\btriStateToggleable\b|\bSlider\b""",
            "swipe" to """\bSwipeToDismiss\w*""",
            "add" to """\bonAdd\w*|\bAlertAddSheet\b|\bAddAlert\w*|\bIcons\s*\.\s*\w+\s*\.\s*Add\b|\bFloatingActionButton\b|추가|\bMAX_COUNT\b""",
            "edit" to """(?i)edit|편집|수정|\bonTap\b|\bonLongClick\b""",
            "permission" to """(?i)permission|POST_NOTIFICATIONS|opensettings|\bACTION_\w*SETTINGS\b|\bActivityResultContracts\b|\brememberLauncherForActivityResult\b|\bstartActivit(?:y|ies)\w*|\bareNotificationsEnabled\b""",
            "AlertRow" to """\bAlertRow\b""",
            "view-model" to """\bAlertViewModel\b|\bAlertState\b|\bhiltViewModel\b""",
            // S7 E5b2b2 (gap hunt G01): the section reads no notification state and holds no Context to reach one.
            "notification-os" to """\bNotificationManager\w*|\bgetNotificationChannels?\b|\breadNotificationCapability\b|\bNotificationCapability\w*|\bgetSystemService\b|\bLocalContext\b|\bBuild\s*\.\s*VERSION\b""")

        /** API r2 §3: the read-only signature, squashed. */
        val SIGNATURE = listOf("currency: SupportedCurrency", "state: BankAlertSectionState", "onRetry: () -> Unit", "onRefresh: () -> Unit",
            "actionIdentity: AuthIdentityFence?", "onToggle: (Int, Boolean) -> Unit", "onDelete: (Int) -> Unit",
            "notificationsBlocked: Boolean", "onEnableNotifications: () -> Unit",
            // S7 E5b3b (API r2 D5)
            "addAvailability: BankAlertAddAvailability", "onAdd: () -> Unit", "onEdit: (Int) -> Unit",
            "modifier: Modifier = Modifier", "metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current")

        /** S7 E5b2b2 (start agreement D10): the holder's one helper call, the only `permission` name the holder may hold. */
        val HELPER_CALL = Regex("""\brememberNotificationPermissionAction\s*\(\s*\)""")
        /** S7 E5b2b2: the holder's wiring, squashed: the call right after the tab check, then needsAction and run only. */
        val NOTIFY_WIRING = listOf(
            "val currency = bankCurrencyOf(tab) ?: return\n    val notificationAction = rememberNotificationPermissionAction()",
            "notificationsBlocked = notificationAction.needsAction,",
            "onEnableNotifications = { notificationAction.run() },").map { squash(it) }
        val BUTTON_CALL = Regex("""(?<![\w.`])Button\s*\(""")
        val ROW_CALL = Regex("""(?<![\w.`])Row\s*\(""")
        val CLICKABLE_CALL = Regex("""\.\s*clickable\s*\(""")
        /** What directly precedes a block's `{` when the block draws only for blocked notifications. */
        val GUARD = Regex("""\bif\s*\(\s*notificationsBlocked\s*\)\s*$""")
        /** S7 E5b3b (API r2 D8): the holder's one sheet call and its two section callbacks, each let through once. */
        val HOLDER_EDITOR_ENTRIES = listOf(Regex("""\bAlertAddSheet\s*\("""), Regex("""\bonAdd\s*=(?!=)"""), Regex("""\bonEdit\s*=(?!=)"""))
        /** S7 E5b3b: the section's agreed add and edit names, let through by [sectionEntries] and counted by [sectionAddEdit]. */
        val SECTION_EDITOR_ENTRIES = listOf(Regex("""\bonAdd\b"""), Regex("""\bonEdit\b"""), Regex(""""알림 추가""""))
        val ADD_AREA_CALL = Regex("""(?<![\w.`])BankAlertAddArea\s*\(""")

        /** Click modifiers and semantics actions: allowed in the header only. */
        val CLICK_MODIFIER = Regex("""\b(?:clickable|combinedClickable|selectable|selectableGroup|toggleable|triStateToggleable|pointerInput|pointerInteropFilter|draggable|anchoredDraggable|swipeable|detectTap\w*|detectDrag\w*|customActions|CustomAccessibilityAction)\b|\b(?:onClick|onLongClick|onDoubleClick)\b(?=\s*[({])""")
        /** Button callbacks, buttons and interactive roles: allowed in the header and the error retry only. */
        val CLICK_ACTION = Regex("""\b(?:onClick|onLongClick|onDoubleClick|onCheckedChange|onValueChange|onSelectedChange)\b(?=\s*=(?!=))|\b\w*Button\b|\b(?:Switch|Checkbox|TriStateCheckbox|Slider)\b|\b\w*Chip\b|\bRole\s*\.\s*(?:Button|Switch|Checkbox|RadioButton|Tab|DropdownList)\b""")
        val CLICK_ALIAS = Regex("""\bimport\s+[\w.`]*\b(?:clickable|combinedClickable|selectable|toggleable|triStateToggleable|pointerInput|detectTapGestures|Button|IconButton|TextButton|OutlinedButton|Switch|Checkbox)\b`?\s+as\b""")
        val IMPORT_LINE = Regex("""(?m)^[ \t]*import[ \t][^\n]*""")
        /** A content dispatch branch label: an optional `is`, an optional qualifier, then Loading, Error, Empty or Rows, then `->`. */
        val LABEL = Regex("""(?:\bis\s+)?(?:[\w.]+\s*\.\s*)?\b(Loading|Error|Empty|Rows)\b\s*->""")
        val ALERT_SETTING = Regex("""\bAlertSetting\b""")
        val TOP = Regex("""(?m)^(?:@[\w.]+(?:\([^)\n]*\))?[ \t]+)*(?:(?:private|internal|public|protected|inline|suspend|operator|infix|tailrec|data|sealed|enum|abstract|open|const|lateinit|annotation|value)[ \t]+)*(fun|val|var|class|object|interface|typealias)\b""")
        val FUN_HEAD = Regex("""fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        val MEMBER = Regex("""(?m)^[ \t]*((?:@[\w.]+(?:\([^)\n]*\))?[ \t]+)*(?:(?:public|internal|protected|private|override|open|final|suspend|inline|operator|lateinit|const|abstract|tailrec|infix)[ \t]+)*)(?:fun|val|var)[ \t]+(\w+)""")
        val TYPEALIAS = Regex("""\btypealias\s+(\w+)\s*(?:<[^=]*>)?\s*=\s*([^\n]+)""")

        val REFLECTION = Regex("""\bjavaClass\b|\bgetMethod\b|\bgetDeclared\w*|\bgetField\b|\bClass\s*\.\s*forName\b|\b(?:kotlin|java\s*\.\s*lang)\s*\.\s*reflect\b|\bmemberFunctions\b|\bcallBy\b""")
        val FLOW_CONSTRUCTION = Regex("""\bMutableStateFlow\b|\bMutableSharedFlow\b|\bstateIn\b|\bshareIn\b|\bcombine\b|\bflowOf\b|\bcallbackFlow\b|\bchannelFlow\b|\bflow\s*\{|\bsnapshotFlow\b|\bproduceState\b|\blaunch\b|\basync\b|\bCoroutineScope\b|\bGlobalScope\b|\brunBlocking\b""")
        /** API r2 §2's entry point, squashed. */
        val ENTRY_POINT = Regex("""(?:@EntryPoint @InstallIn\(SingletonComponent::class\)|@InstallIn\(SingletonComponent::class\) @EntryPoint) (?:internal )?interface BankAlertSectionEntryPoint \{ fun bankAlertSettingsOwner\(\): BankAlertSettingsOwner \}""")
        /** The adapter's settings override, squashed: the owner's `state` as it is; group 1 is the owner reference. */
        val SETTINGS = flowOverride("settings", "BankAlertSettingsState", """([\w.]+?(?:\(\))?)""", "state")

        /** An implementation: an anonymous object, a named object or class with it among its supertypes, or a Java implements. */
        val IMPL_SOURCE = Regex("""\bobject\s*:\s*(?:[\w.<>]+(?:\([^()]*\))?\s*,\s*)*BankAlertSectionSource\b|\b(?:object|class)\s+\w+[^{}=]*?:\s*(?:[\w.<>]+(?:\([^()]*\))?\s*,\s*)*BankAlertSectionSource\b|\bimplements\s+(?:[\w.]+\s*,\s*)*BankAlertSectionSource\b""")
        val IMPL_FACTORY = Regex("""\bobject\s*:\s*(?:[\w.<>]+(?:\([^()]*\))?\s*,\s*)*BankAlertSectionSourceFactory\b|\b(?:object|class)\s+\w+[^{}=]*?:\s*(?:[\w.<>]+(?:\([^()]*\))?\s*,\s*)*BankAlertSectionSourceFactory\b|\bimplements\s+(?:[\w.]+\s*,\s*)*BankAlertSectionSourceFactory\b""")
        val FACTORY_BRACE = Regex("""\bBankAlertSectionSourceFactory\s*\{""")
        val SOURCE_SHAPE = setOf("val settings: StateFlow<BankAlertSettingsState>", "val manualRefresh: StateFlow<ManualRefreshState>",
            "fun ensure()", "fun authoritative()", "fun manual()", "fun setEnabled(id: Int, enabled: Boolean)", "fun delete(id: Int)",
            // S7 E5b3b (API r2 D5): the owner's add and edit, one to one.
            "fun add(draft: BankAlertDraft): BankAlertSaveRequest", "fun edit(id: Int, edit: BankAlertEdit): BankAlertSaveRequest")
        val FACTORY_SHAPE = setOf("fun create(context: Context): BankAlertSectionSource")

        val CALL = Regex("""\bBankAlertSection\s*\(""")
        val HISTORY_CALL = Regex("""\bBankHistoryEntry\s*\(""")
        val CONDITION = squash("screen.ui.selectedTab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR) && !settingsOpen && detailUrl == null")
        const val FACTORY_PARAMETER = "bankAlertSectionSourceFactory: BankAlertSectionSourceFactory = ProductionBankAlertSectionSourceFactory"
        val ROUTE_FORBIDDEN = listOf(
            "owner" to """\bBankAlertSettingsOwner\b""",
            "entry-point" to """\bBankAlertSectionEntryPoint\b|\bbankAlertSettingsOwner\b""",
            "source" to """\bBankAlertSectionSource\b""",
            "owner-state" to """\bBankAlertSettingsState\b|\bManualRefreshState\b""")

        /** The unchanged BankHistoryEntry arguments (API r2 §4: the entry's arguments, condition and mount effect stay). */
        fun historyArgs(tab: String) = listOf("tab = $tab", "owner = owner", "shown = { consumer.state.value.ui.let { it.owner to it.selectedTab } }",
            "listen = consumer::addScreenListener", "wiring = { bankHistoryWiring.create(context) { consumer.state.value.ui.owner } }").map(::squash)

        fun forbidden(tag: String) = HOLDER_FORBIDDEN.first { it.first == tag }.second
        fun sectionForbidden(tag: String) = SECTION_FORBIDDEN.first { it.first == tag }.second

        fun tagPattern(tag: String) = Regex("""\bBankAlertSectionTags\s*\.\s*""" + if (tag == "row") """row\s*\(""" else """$tag\b""")

        fun flowOverride(name: String, type: String, receiver: String, member: String) =
            Regex("""\boverride val $name(?: ?: ?StateFlow<$type>)? (?:= |get\(\) = )$receiver\.$member(?= override\b| \}|$)""")

        /** S7 E5a2: a one-to-one delegation with arguments, squashed: `override fun name(params) = r.name(args)` or its block form. */
        fun argOverride(name: String, params: String, args: String, receiver: String) =
            Regex("""\boverride fun $name\($params\)(?: ?: ?Unit)? (?:= $receiver\.$name\($args\)(?= override\b| \}|$)|\{ $receiver\.$name\($args\) \})""")

        /** S7 E5b3b: a one-to-one delegation to an owner member of another name, squashed. */
        fun renamedOverride(name: String, params: String, target: String, args: String, receiver: String) =
            Regex("""\boverride fun $name\($params\)(?: ?: ?BankAlertSaveRequest)? (?:= $receiver\.$target\($args\)(?= override\b| \}|$)|\{ return $receiver\.$target\($args\) \})""")

        val SWITCH_CALL = Regex("""\bSwitch\s*\(""")
        val ICON_BUTTON_CALL = Regex("""\bIconButton\s*\(""")
        val DIALOG_CALL = Regex("""\bAlertDialog\s*\(""")
        /** The holder's three binding lines, squashed: every row action is bound to the identity the frame rendered. */
        val BINDINGS = listOf(
            "val actionIdentity: AuthIdentityFence? = settings.identity",
            "actionIdentity = actionIdentity,",
            "onToggle = { id, enabled -> if (actionIdentity != null && source.settings.value.identity == actionIdentity) source.setEnabled(id, enabled) },",
            "onDelete = { id -> if (actionIdentity != null && source.settings.value.identity == actionIdentity) source.delete(id) },").map { squash(it) }

        fun methodOverride(name: String, receiver: String) =
            Regex("""\boverride fun $name\(\)(?: ?: ?Unit)? (?:= $receiver\.$name\(\)(?= override\b| \}|$)|\{ $receiver\.$name\(\) \})""")

        fun importOf(name: String) = """(?m)^[ \t]*import[ \t]+com\.jay\.fxi\.ui\.alert\.$name[ \t]*;?[ \t\r]*$"""

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

        /** Java source with comments removed and every literal kept, Unicode escapes decoded first; block comments do not nest. */
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

        fun occurrences(code: Map<String, String>, regex: Regex): Map<String, Int> =
            code.mapValues { regex.findAll(it.value).count() }.filterValues { it > 0 }

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

        /** Every `[open]...[close]` pair of [text], literals skipped. */
        fun pairs(text: String, open: Char, close: Char): List<IntRange> {
            val out = mutableListOf<IntRange>()
            val stack = ArrayDeque<Int>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '"' || c == '\'') {
                    i = literalEnd(text, i)
                    continue
                }
                if (c == open) stack.addLast(i) else if (c == close && stack.isNotEmpty()) out += stack.removeLast()..i
                i++
            }
            return out
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

        /** The arguments (or parameters) of a list's inside, split at its top-level commas, each squashed; empty ones dropped. */
        fun topLevelArgs(text: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '"' || c == '\'') {
                    i = literalEnd(text, i)
                    continue
                }
                when (c) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        out += text.substring(start, i)
                        start = i + 1
                    }
                }
                i++
            }
            out += text.substring(start)
            return out.map(::squash).filter { it.isNotEmpty() }
        }

        /** [text] with every literal and every bracket's inside blanked, newlines kept: the top-level shape of a body. */
        fun topLevelShape(text: String): String {
            val out = StringBuilder(text.length)
            var depth = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '"' || c == '\'' || c == '`') {
                    val end = if (c == '`') (text.indexOf('`', i + 1).takeIf { it >= 0 } ?: (text.length - 1)) + 1 else literalEnd(text, i)
                    for (k in i until end) out.append(if (text[k] == '\n') '\n' else ' ')
                    i = end
                    continue
                }
                when (c) {
                    '(', '{', '[' -> {
                        out.append(if (depth == 0) c else ' ')
                        depth++
                    }
                    ')', '}', ']' -> {
                        depth--
                        out.append(if (depth == 0) c else ' ')
                    }
                    else -> out.append(if (depth == 0 || c == '\n') c else ' ')
                }
                i++
            }
            return out.toString()
        }

        // ---- region reading for AlertSection ----

        class Fn(val name: String, val params: IntRange, val extent: IntRange)

        class Regions(val content: IntRange, val header: List<IntRange>, val rows: List<IntRange>, val error: List<IntRange>,
            val branch: Map<String, IntRange> = emptyMap())

        /** The file's top-level functions, each extending to the next top-level declaration. */
        fun topLevelFunctions(text: String): List<Fn> {
            val starts = TOP.findAll(text).toList()
            return starts.mapIndexedNotNull { k, m ->
                val keyword = m.groups[1] ?: return@mapIndexedNotNull null
                if (keyword.value != "fun" ||
                    Regex("""^fun\s+interface\b""").containsMatchIn(text.substring(keyword.range.first, minOf(text.length, keyword.range.first + 40)))) {
                    return@mapIndexedNotNull null
                }
                val end = starts.getOrNull(k + 1)?.range?.first ?: text.length
                val head = FUN_HEAD.find(text, keyword.range.first)?.takeIf { it.range.first == keyword.range.first } ?: return@mapIndexedNotNull null
                val open = head.range.last
                val close = closing(text, open)?.takeIf { it < end } ?: return@mapIndexedNotNull null
                Fn(head.groupValues[1], (open + 1) until close, m.range.first until end)
            }
        }

        /** [seeds] plus the extent of every top-level function they call or reference, transitively. */
        fun closure(text: String, seeds: List<IntRange>, funs: List<Fn>): List<IntRange> {
            val out = seeds.toMutableList()
            val taken = mutableSetOf<String>()
            var frontier = seeds
            while (frontier.isNotEmpty()) {
                val next = funs.filter { fn ->
                    val call = Regex("""(?<![\w.])${Regex.escape(fn.name)}\s*[({]|::\s*${Regex.escape(fn.name)}\b""")
                    fn.name !in taken && frontier.any { r -> call.containsMatchIn(text.substring(r.first, r.last + 1)) }
                }
                taken += next.map { it.name }
                frontier = next.map { it.extent }
                out += frontier
            }
            return out
        }

        /** Start of the call owning the block at [open]: `Name(args) {` or `Name {`; [open] itself when there is none. */
        fun callStart(text: String, open: Int, parens: List<IntRange>): Int {
            var j = open - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            if (j >= 0 && text[j] == ')') {
                val args = parens.firstOrNull { it.last == j } ?: return open
                j = args.first - 1
                while (j >= 0 && text[j].isWhitespace()) j--
            }
            var s = j
            while (s >= 0 && (text[s].isLetterOrDigit() || text[s] == '_' || text[s] == '.')) s--
            return if (s < j) s + 1 else open
        }

        /**
         * AlertSection's regions, read without its private names: the content dispatch is the block holding the four Content
         * branch labels; the row render is its Rows branch plus every top-level function taking an AlertSetting, with what they
         * call; the error body is the Error branch with what it calls; the header is the call owning the innermost block that
         * holds both header tags (REFRESH and TOGGLE_EXPANDED), with what it calls, and it must not hold or sit in the dispatch.
         */
        fun regions(text: String, problems: MutableList<String>): Regions? {
            val braces = pairs(text, '{', '}')
            val parens = pairs(text, '(', ')')
            fun innermost(vararg at: Int): IntRange? = braces.filter { b -> at.all { it > b.first && it < b.last } }.maxByOrNull { it.first }
            val dispatch = LABEL.findAll(text).toList().groupBy { innermost(it.range.first) }
                .filter { (block, labels) -> block != null && labels.map { it.groupValues[1] }.sorted() == listOf("Empty", "Error", "Loading", "Rows") }
            if (dispatch.size != 1) {
                problems += "S-dispatch ${dispatch.size}"
                return null
            }
            val (block, labels) = dispatch.entries.single()
            val content = block!!
            val sorted = labels.sortedBy { it.range.first }
            val branch = sorted.mapIndexed { k, m ->
                m.groupValues[1] to ((m.range.last + 1) until (sorted.getOrNull(k + 1)?.range?.first ?: content.last))
            }.toMap()
            val funs = topLevelFunctions(text)
            val rowSeeds = listOf(branch.getValue("Rows")) +
                funs.filter { ALERT_SETTING.containsMatchIn(text.substring(it.params.first, it.params.last + 1)) }.map { it.extent }
            val refresh = tagPattern("REFRESH").findAll(text).toList()
            val toggle = tagPattern("TOGGLE_EXPANDED").findAll(text).toList()
            if (refresh.size != 1 || toggle.size != 1) {
                problems += "S-header-tags ${refresh.size} ${toggle.size}"
                return null
            }
            val headBlock = innermost(refresh.single().range.first, toggle.single().range.first)
            if (headBlock == null) {
                problems += "S-header-block"
                return null
            }
            val headerCall = callStart(text, headBlock.first, parens)..headBlock.last
            if (content.first in headerCall || headerCall.first in content) {
                problems += "S-header-shape"
                return null
            }
            return Regions(content, closure(text, listOf(headerCall), funs), closure(text, rowSeeds, funs),
                closure(text, listOf(branch.getValue("Error")), funs), branch)
        }

        // ---- the locks: each returns its violations, empty for an accepted tree ----

        /** H: the holder's forbidden names, directly or through a type alias declared anywhere in the roots. */
        fun holderNames(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("H-missing")
            // S7 E5b2b2: the exact empty-argument call is let through; an alias, a reference or any other permission name is not.
            // S7 E5b3b: so are the one sheet call and the two section callbacks (counted and placed by holderEditor).
            val scanned = HOLDER_EDITOR_ENTRIES.fold(HELPER_CALL.replace(text, " ")) { t, r -> r.find(t)?.let { t.replaceRange(it.range, " ") } ?: t }
            val v = HOLDER_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(scanned) }.map { (tag, _) -> "H-name $tag" }.toMutableList()
            for (source in code.values) for (alias in TYPEALIAS.findAll(source)) {
                val target = alias.groupValues[2]
                if (HOLDER_FORBIDDEN.any { (_, p) -> Regex(p).containsMatchIn(target) } &&
                    Regex("""\b${Regex.escape(alias.groupValues[1])}\b""").containsMatchIn(text)) {
                    v += "H-typealias ${alias.groupValues[1]}"
                }
            }
            return v.distinct()
        }

        /** The owner's non-private members: the declarations at its class body's top level, nested blocks masked. */
        fun ownerMembers(code: Map<String, String>): Set<String> {
            val text = code[OWNER] ?: return emptySet()
            val head = Regex("""\bclass\s+BankAlertSettingsOwner\b""").find(text) ?: return emptySet()
            var i = head.range.last + 1
            while (i < text.length && text[i] != '{') {
                if (text[i] == '(') i = closing(text, i) ?: return emptySet()
                i++
            }
            val close = closing(text, i) ?: return emptySet()
            val shape = topLevelShape(text.substring(i + 1, close))
            return MEMBER.findAll(shape).filter { "private" !in it.groupValues[1].split(Regex("""\s+""")) }.map { it.groupValues[2] }.toSet()
        }

        fun objectBody(text: String, name: String): IntRange? {
            val m = Regex("""\bobject\s+${Regex.escape(name)}\b""").findAll(text).toList().singleOrNull() ?: return null
            val open = text.indexOf('{', m.range.last)
            if (open < 0) return null
            val close = closing(text, open) ?: return null
            return open..close
        }

        /** A: the production adapter and the owner's reach from the screen side. */
        fun adapter(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("A-missing")
            val v = mutableListOf<String>()
            for (name in (ownerMembers(code) - OWNER_ALLOWED).sorted()) {
                if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)) v += "A-owner-member $name"
            }
            if (REFLECTION.containsMatchIn(text)) v += "A-reflection"
            if (FLOW_CONSTRUCTION.containsMatchIn(text)) v += "A-flow-construction"
            if (Regex("""\bBankAlertSettingsOwner\s*\(""").containsMatchIn(text)) v += "A-construction"
            if (Regex("""\bgetSettings\b""").containsMatchIn(text)) v += "A-get"
            val flat = squash(text)
            if (ENTRY_POINT.findAll(flat).count() != 1) v += "A-entry-point"
            val resolutions = Regex("""\bEntryPointAccessors\s*\.\s*fromApplication\s*\(""").findAll(text).map { it.range.first }.toList()
            if (resolutions.size != 1 || count(text, """\bEntryPointAccessors\s*\.\s*\w+""") != 1 ||
                count(text, """\.\s*bankAlertSettingsOwner\s*\(\s*\)""") != 1) {
                v += "A-resolution"
            }
            val factory = objectBody(text, "ProductionBankAlertSectionSourceFactory")
            if (factory == null || resolutions.any { it !in factory }) v += "A-resolution-place"
            val outside = files(code, """\bBankAlertSettingsOwner\b""").filterNot { DATA_ALERTS in it }.toSet()
            if (outside != setOf(HOLDER)) v += "A-owner-type ${outside.sorted()}"
            val entry = files(code, """\bBankAlertSectionEntryPoint\b|\bbankAlertSettingsOwner\b""")
            if (entry != setOf(HOLDER)) v += "A-entry-point-files ${entry.sorted()}"
            if (files(code, """\bimport\s+[\w.`]*\b(?:BankAlertSettingsOwner|BankAlertSectionEntryPoint)\b`?\s+as\b""").isNotEmpty()) v += "A-alias"
            val settings = SETTINGS.findAll(flat).toList()
            if (settings.size != 1) return v + "A-settings ${settings.size}"
            val r = Regex.escape(settings.single().groupValues[1])
            if (flowOverride("manualRefresh", "ManualRefreshState", r, "manualRefresh").findAll(flat).count() != 1) v += "A-manualRefresh"
            for (name in listOf("ensure", "authoritative", "manual")) {
                if (methodOverride(name, r).findAll(flat).count() != 1) v += "A-$name"
            }
            if (argOverride("setEnabled", "id: Int, enabled: Boolean", "id, enabled", r).findAll(flat).count() != 1) v += "A-setEnabled"
            if (argOverride("delete", "id: Int", "id", r).findAll(flat).count() != 1) v += "A-delete"
            // S7 E5b3b: add and edit delegate one to one to the owner's addAlert and editAlert.
            if (renamedOverride("add", "draft: BankAlertDraft", "addAlert", "draft", r).findAll(flat).count() != 1) v += "A-add"
            if (renamedOverride("edit", "id: Int, edit: BankAlertEdit", "editAlert", "id, edit", r).findAll(flat).count() != 1) v += "A-edit"
            if (count(text, """\bsource\s*\.\s*add\s*\(""") != 1 || count(text, """\bsource\s*\.\s*edit\s*\(""") != 1) v += "A-binding save calls"
            // S7 E5b3b (gap hunt G18): the owner's two save members are named only by their one-to-one delegation.
            if (count(text, """\baddAlert\b""") != 1 || count(text, """\beditAlert\b""") != 1) {
                v += "A-owner-save ${count(text, """\baddAlert\b""")} ${count(text, """\beditAlert\b""")}"
            }
            for (line in BINDINGS) if (count(flat, Regex.escape(line)) != 1) v += "A-binding $line"
            if (count(text, """\bsource\s*\.\s*setEnabled\b""") != 1 || count(text, """\bsource\s*\.\s*delete\b""") != 1) v += "A-binding calls"
            return v
        }

        /** The member declarations of the one `interface [name]` in [text], squashed; null unless exactly one. */
        fun interfaceShape(text: String, name: String): Set<String>? {
            val m = Regex("""\binterface\s+${Regex.escape(name)}\b""").findAll(text).toList().singleOrNull() ?: return null
            val open = text.indexOf('{', m.range.last)
            if (open < 0) return null
            val close = closing(text, open) ?: return null
            return squash(text.substring(open + 1, close)).split(Regex("""(?=\b(?:val|var|fun)\s)""")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }

        /** SAM constructions of the factory: `BankAlertSectionSourceFactory {` that is neither its declaration nor a supertype clause. */
        fun factoryLambdas(code: Map<String, String>): Map<String, Int> = code.mapValues { (_, t) ->
            FACTORY_BRACE.findAll(t).count { m ->
                val before = t.substring(0, m.range.first).trimEnd()
                !before.endsWith(":") && !Regex("""\binterface$""").containsMatchIn(before)
            }
        }.filterValues { it > 0 }

        /** T: the source seam's declarations, implementations, names and shape. */
        fun types(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for ((label, pattern) in listOf(
                "source" to """\binterface\s+BankAlertSectionSource\b""",
                "factory" to """\binterface\s+BankAlertSectionSourceFactory\b""",
                "production" to """\bobject\s+ProductionBankAlertSectionSourceFactory\b""")) {
                val found = occurrences(code, Regex(pattern))
                if (found != mapOf(HOLDER to 1)) v += "T-declaration $label $found"
            }
            val sources = occurrences(code, IMPL_SOURCE)
            if (sources != mapOf(HOLDER to 1)) v += "T-source-implementation $sources"
            val factories = occurrences(code, IMPL_FACTORY)
            if (factories != mapOf(HOLDER to 1)) v += "T-factory-implementation $factories"
            val lambdas = factoryLambdas(code)
            if (lambdas.isNotEmpty()) v += "T-factory-implementation lambda $lambdas"
            for ((name, expected) in listOf("BankAlertSectionSource" to setOf(HOLDER), "BankAlertSectionSourceFactory" to setOf(HOLDER, ROUTE),
                    "ProductionBankAlertSectionSourceFactory" to setOf(HOLDER, ROUTE))) {
                val named = files(code, """\b$name\b""")
                if (named != expected) v += "T-named $name ${named.sorted()}"
            }
            if (files(code, """\bimport\s+[\w.`]*\b(?:BankAlertSectionSource|BankAlertSectionSourceFactory|ProductionBankAlertSectionSourceFactory)\b`?\s+as\b""")
                    .isNotEmpty()) {
                v += "T-alias"
            }
            val holder = code[HOLDER].orEmpty()
            if (interfaceShape(holder, "BankAlertSectionSource") != SOURCE_SHAPE) v += "T-source-shape ${interfaceShape(holder, "BankAlertSectionSource")}"
            if (interfaceShape(holder, "BankAlertSectionSourceFactory") != FACTORY_SHAPE) {
                v += "T-factory-shape ${interfaceShape(holder, "BankAlertSectionSourceFactory")}"
            }
            return v
        }

        /** S: AlertSection's agreed read-only signature. */
        fun sectionSignature(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("S-missing")
            val v = mutableListOf<String>()
            if (count(text, """@Composable\s+internal\s+fun\s+AlertSection\s*\(""") != 1) v += "S-signature visibility"
            val params = paramsRange(text, "AlertSection")?.let { topLevelArgs(text.substring(it)) }
            if (params != SIGNATURE) v += "S-signature $params"
            return v
        }

        /** S: the entries and names AlertSection never has. */
        fun sectionEntries(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("S-missing")
            // S7 E5b3b: the agreed add and edit names are let through here and counted and placed by sectionAddEdit.
            val scanned = SECTION_EDITOR_ENTRIES.fold(text) { t, r -> r.replace(t, " ") }
            val v = SECTION_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(scanned) }.map { (tag, _) -> "S-entry $tag" }.toMutableList()
            // S7 E5b2b2 (gap hunt G01): a forbidden name behind a type alias declared in any scanned source.
            for (source in code.values) for (alias in TYPEALIAS.findAll(source)) {
                val target = alias.groupValues[2]
                if (SECTION_FORBIDDEN.any { (_, p) -> Regex(p).containsMatchIn(target) } &&
                    Regex("""\b${Regex.escape(alias.groupValues[1])}\b""").containsMatchIn(text)) {
                    v += "S-typealias ${alias.groupValues[1]}"
                }
            }
            return v.distinct()
        }

        /** S: where AlertSection's click surfaces are; the regions' own shape is reported as a violation too. */
        fun sectionClicks(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("S-missing")
            val v = mutableListOf<String>()
            val r = regions(text, v) ?: return v
            val imports = IMPORT_LINE.findAll(text).map { it.range }.toList()
            fun within(p: Int, ranges: List<IntRange>) = ranges.any { p in it }
            val actions = rowActionSpans(text, imports)
            val notify = notifySpans(text)
            val editor = addClicks(text) + openClicks(text)
            for ((kind, regex) in listOf("modifier" to CLICK_MODIFIER, "action" to CLICK_ACTION)) {
                for (m in regex.findAll(text)) {
                    val p = m.range.first
                    when {
                        within(p, imports) -> Unit
                        // S7 E5a2: counted, placed and shaped by sectionRowActions.
                        within(p, actions) -> Unit
                        // S7 E5b2b2: counted, placed and shaped by sectionNotify.
                        within(p, notify) -> Unit
                        // S7 E5b3b: counted, placed and shaped by sectionAddEdit.
                        within(p, editor) -> Unit
                        within(p, r.rows) -> { v += "S-row-click ${m.value}" }
                        within(p, r.header) -> Unit
                        kind == "action" && within(p, r.error) -> Unit
                        else -> { v += "S-$kind-outside-header ${m.value}" }
                    }
                }
            }
            val aliases = CLICK_ALIAS.findAll(text).count()
            if (aliases != 0) v += "S-click-alias $aliases"
            fun holds(ranges: List<IntRange>, pattern: String) = ranges.any { Regex(pattern).containsMatchIn(text.substring(it.first, it.last + 1)) }
            val rowTags = tagPattern("row").findAll(text).map { it.range.first }.toList()
            if (rowTags.isEmpty() || !rowTags.all { within(it, r.rows) }) v += "S-shape row-tag"
            val retries = tagPattern("RETRY").findAll(text).map { it.range.first }.toList()
            if (retries.isEmpty() || !retries.all { within(it, r.error) } || !holds(r.error, """\bonRetry\b""")) v += "S-shape retry"
            if (!holds(r.header, """\bonRefresh\b""")) v += "S-shape refresh"
            return v
        }

        /** S7 E5b2b2: the prompt's Button (its whole call) and the banner's click modifier calls. */
        fun notifySpans(text: String): List<IntRange> {
            val buttonTags = tagPattern("NOTIFY_PROMPT_BUTTON").findAll(text).map { it.range.first }.toList()
            return BUTTON_CALL.findAll(text).mapNotNull { callSpan(text, it) }.filter { s -> buttonTags.any { it in s } }.toList() +
                bannerClicks(text)
        }

        /** S7 E5b2b2: the `.clickable(` calls in the argument list of the innermost Row call whose arguments hold the banner tag. */
        fun bannerClicks(text: String): List<IntRange> {
            val tag = tagPattern("NOTIFY_BANNER").findAll(text).toList().singleOrNull() ?: return emptyList()
            val args = ROW_CALL.findAll(text).mapNotNull { m ->
                val open = text.indexOf('(', m.range.first)
                closing(text, open)?.let { open..it }
            }.filter { tag.range.first in it }.maxByOrNull { it.first } ?: return emptyList()
            return CLICKABLE_CALL.findAll(text).filter { it.range.first in args }.mapNotNull { m ->
                closing(text, text.indexOf('(', m.range.first))?.let { m.range.first..it }
            }.toList()
        }

        /** S7 E5b3b: the `.clickable(` calls in the argument list of the innermost Row call whose arguments hold [tag]. */
        fun tagClicks(text: String, tag: String): List<IntRange> {
            val at = tagPattern(tag).findAll(text).toList().singleOrNull() ?: return emptyList()
            val args = ROW_CALL.findAll(text).mapNotNull { m ->
                val open = text.indexOf('(', m.range.first)
                closing(text, open)?.let { open..it }
            }.filter { at.range.first in it }.maxByOrNull { it.first } ?: return emptyList()
            return CLICKABLE_CALL.findAll(text).filter { it.range.first in args }.mapNotNull { m ->
                closing(text, text.indexOf('(', m.range.first))?.let { m.range.first..it }
            }.toList()
        }

        fun addClicks(text: String) = tagClicks(text, "ADD_BUTTON")
        fun openClicks(text: String) = tagClicks(text, "rowOpen")

        /**
         * Q (S7 E5b3b, API r2 D5 and D8): the add area is the private BankAlertAddArea, called after the empty text when not blocked
         * and after the rows; its button is one Row with `.clickable(onClick = onAdd)` and its cap line has no click. The row's bank
         * and condition area is one Row with `.clickable(onClick = onOpen)` that calls `onEdit(setting.id)`. The names are counted.
         */
        fun sectionAddEdit(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("Q-missing")
            val v = mutableListOf<String>()
            val r = regions(text, v) ?: return v
            val braces = pairs(text, '{', '}')
            fun guarded(p: Int, branch: IntRange) = braces.any { b ->
                p > b.first && p < b.last && b.first in branch && GUARD.containsMatchIn(text.substring(maxOf(branch.first, b.first - 80), b.first))
            }
            fun at(tag: String) = tagPattern(tag).findAll(text).map { it.range.first }.toList()
            val declarations = Regex("""\bfun\s+BankAlertAddArea\s*\(""").findAll(text).count()
            val calls = ADD_AREA_CALL.findAll(text).map { it.range.first }.filter { p -> !text.substring(maxOf(0, p - 4), p).endsWith("fun ") }.toList()
            val empty = r.branch.getValue("Empty")
            val rowsBranch = r.branch.getValue("Rows")
            val emptyTag = at("EMPTY").singleOrNull()
            val rowsTag = at("ROWS").singleOrNull()
            val inEmpty = calls.filter { it in empty }
            val inRows = calls.filter { it in rowsBranch }
            if (declarations != 1 || calls.size != 2 || inEmpty.size != 1 || inRows.size != 1 ||
                guarded(inEmpty.single(), empty) || emptyTag == null || inEmpty.single() < emptyTag || rowsTag == null || inRows.single() < rowsTag) {
                v += "Q-add-place $calls"
            }
            if (at("ADD_BUTTON").size != 1 || at("ADD_AT_CAP").size != 1) v += "Q-add-tags"
            val adds = addClicks(text).map { squash(text.substring(it.first, it.last + 1)) }
            if (adds != listOf(".clickable(onClick = onAdd)")) v += "Q-add-click $adds"
            val opens = openClicks(text).map { squash(text.substring(it.first, it.last + 1)) }
            if (opens != listOf(".clickable(onClick = onOpen)")) v += "Q-open-click $opens"
            if (at("rowOpen").size != 1 || at("rowOpen").none { p -> r.rows.any { p in it } }) v += "Q-open-place"
            if (count(text, """\bonAdd\b""") != 6 || count(text, """onClick = onAdd\b""") != 2) v += "Q-add-uses ${count(text, """\bonAdd\b""")}"
            if (count(text, """\bonEdit\b""") != 2 || count(squash(text), Regex.escape("onOpen = { onEdit(setting.id) }")) != 1) v += "Q-edit-uses ${count(text, """\bonEdit\b""")}"
            if (count(text, """\bonOpen\b""") != 3) v += "Q-open-uses ${count(text, """\bonOpen\b""")}"
            if (count(text, """"알림 추가"""") != 1) v += "Q-add-copy"
            return v
        }

        /** Q (S7 E5b3b): the holder opens the one sheet in one call and gives the section its two callbacks once each. */
        fun holderEditor(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("Q-holder-missing")
            val v = mutableListOf<String>()
            for (entry in HOLDER_EDITOR_ENTRIES) if (entry.findAll(text).count() != 1) v += "Q-holder-entry ${entry.pattern}"
            val section = Regex("""\bAlertSection\s*\(""").find(text)
            val sectionArgs = section?.let { val open = text.indexOf('(', it.range.first); closing(text, open)?.let { c -> open..c } }
            for (entry in HOLDER_EDITOR_ENTRIES.drop(1)) {
                val m = entry.find(text)
                if (sectionArgs == null || m == null || m.range.first !in sectionArgs) v += "Q-holder-callback ${entry.pattern}"
            }
            if (count(text, """\bsource\s*\.\s*settings\s*\.\s*value\b""") < 1) v += "Q-holder-live"
            // S7 E5b3b (battery r4 G04b): the add, edit and save paths read the live snapshot, never the drawn one. The test rig
            // updates collectAsState in the same turn as the source, so only the reading itself can be checked here.
            val lambdas = pairs(text, '{', '}')
            fun lambdaOf(m: MatchResult?): IntRange? = m?.let { found -> lambdas.firstOrNull { it.first == found.range.last } }
            val sites = listOf(
                "onAdd" to lambdaOf(Regex("""\bonAdd\s*=\s*\{""").find(text)),
                "onEdit" to lambdaOf(Regex("""\bonEdit\s*=\s*\{""").find(text)),
                "save" to Regex("""\{\s*\w+\s*->""").findAll(text).mapNotNull { m -> lambdas.firstOrNull { it.first == m.range.first } }
                    .firstOrNull { span -> Regex("""\bnotificationAction\s*\.\s*run\s*\{""").containsMatchIn(text.substring(span.first, span.last + 1)) })
            for ((name, span) in sites) {
                val inside = span?.let { text.substring(it.first, it.last + 1) }
                if (inside == null || !Regex("""\bsource\s*\.\s*settings\s*\.\s*value\b""").containsMatchIn(inside) ||
                    Regex("""(?<![.\w])settings\b""").containsMatchIn(inside)) {
                    v += "Q-holder-live-$name"
                }
            }
            // S7 E5b3b (gap hunt G13): the sheet's remaining is exactly the helper's result, and the helper is used nowhere else.
            val sheet = Regex("""\bAlertAddSheet\s*\(""").find(text)
            val sheetArgs = sheet?.let { val open = text.indexOf('(', it.range.first); closing(text, open)?.let { c -> topLevelArgs(text.substring(open + 1, c)) } }
            if (sheetArgs == null || sheetArgs.count { it == "remaining = bankAlertSheetRemaining(mode, availability)" } != 1 ||
                sheetArgs.count { it.startsWith("remaining") } != 1) {
                v += "Q-holder-remaining"
            }
            if (count(text, """\bbankAlertSheetRemaining\b""") != 2) v += "Q-holder-remaining-uses ${count(text, """\bbankAlertSheetRemaining\b""")}"
            // S7 E5b3b (gap hunt G12): the one effect that reads the outcome checks the current editor and the current request by
            // reference before it changes anything.
            val effects = Regex("""\bLaunchedEffect\s*\(([^()]*)\)""").findAll(text).filter { Regex("""\boutcome\b""").containsMatchIn(it.groupValues[1]) }.toList()
            if (effects.size != 1) {
                v += "Q-holder-outcome-effect ${effects.size}"
            } else {
                val brace = text.indexOf('{', effects.single().range.last)
                val end = closing(text, brace)
                val body = if (brace < 0 || end == null) "" else text.substring(brace + 1, end)
                // The effect's first statement is the guard itself, an `||` of checks that returns: it then decides every change
                // after it (commit review T1: a guard merely present before the first change can be wrapped and never run).
                val cond = Regex("""^\s*if\s*\(([^{}()]*)\)\s*return@LaunchedEffect\b""").find(body)?.groupValues?.get(1)
                if (cond == null || !Regex("""\bsession\s*\.\s*editor\s*!==\s*open\b""").containsMatchIn(cond) ||
                    !Regex("""\bopen\s*\.\s*handle\s*!==\s*handle\b""").containsMatchIn(cond) ||
                    Regex("""&&|\bfalse\b|\btrue\b|!\s*\(""").containsMatchIn(cond)) {
                    v += "Q-holder-outcome-guard"
                }
            }
            return v
        }

        /**
         * S7 E5b3b (commit review r2): where each editor is built and installed. The one add editor is built and installed
         * directly inside the add's helper continuation, so none exists while the system asks; the one edit editor directly in
         * onEdit, outside any run. Canonical surface: the editor type is the one `session.editor` holds, built only as the direct
         * right side of `session.editor =` with `BankAlertEditorMode.Add` or `BankAlertEditorMode.Edit(` written there; no name,
         * scope function, reference or helper carries it, and every editor write is `session.editor =`.
         */
        fun holderInstall(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("Q-holder-missing")
            val scan = installScan(text)
            val v = mutableListOf<String>()
            if (scan.backtick) v += "Q-holder-install-backtick"
            if (scan.type == null) return v + "Q-holder-install-type"
            if (scan.writes.any { !it.receiver || it.operator != "=" } ||
                scan.tokens.windowed(2).any { it[0].name == "::" && it[1].name == "editor" } ||
                scan.tokens.windowed(2).any { it[0].name == "editor" && it[1].name in setOf("++", "--") } ||
                scan.tokens.indices.any { i -> scan.tokens[i].name in setOf("++", "--") &&
                    (scan.tokens.getOrNull(i + 1)?.name == "editor" ||
                        (scan.tokens.getOrNull(i + 1)?.name == "session" && scan.tokens.getOrNull(i + 2)?.name == "." &&
                            scan.tokens.getOrNull(i + 3)?.name == "editor")) }) v += "Q-holder-install-write"
            if (scan.writes.any { !it.complete }) v += "Q-holder-install-rhs"
            // Link by the RHS START OFFSET, never a preceding-character window.
            if (scan.builds.any { b -> scan.writes.none { it.direct && it.call?.first == b.span.first } } ||
                scan.tokens.windowed(2).any { it[0].name == "::" && it[1].name == scan.type }) v += "Q-holder-install-alias"
            val installs = scan.writes.filter { !it.nullOnly }
            if (installs.size != 2 || installs.any { !it.direct }) v += "Q-holder-install-assign"
            // Require the unaliased Compose import and fail closed on a competing/ambiguous key name.
            // Import separators may be absent in the existing synthetic squash control.
            val kt = scan.tokens
            fun keyName(i: Int) = kt.getOrNull(i)?.name
            val keyImports = kt.indices.filter { keyName(it) == "import" }.map { start ->
                var after = start + 2
                while (keyName(after) == "." && after + 1 < kt.size) after += 2
                if (keyName(after) == "as") after += 2
                start until after
            }
            val composeKey = listOf("import", "androidx", ".", "compose", ".", "runtime", ".", "key")
            val exactKeyImports = keyImports.filter { span ->
                span.map { kt[it].name } == composeKey && span.none { kt[it].backtick }
            }
            val competingImport = keyImports.any { span ->
                keyName(span.last) == "key" && span !in exactKeyImports
            }
            fun declarationHeader(i: Int): Boolean {
                val root = (0 until i).lastOrNull {
                    keyName(it) == "{" && (installClose(kt, it) ?: -1) > i
                } ?: -1
                // The previous statement's val/by/fun is never part of this call's header.
                // File-level declarations use the same splitter with the virtual root -1.
                val statement = installSheetStatementTokens(text, kt, root, i) ?: return true
                var before = i - 1
                while (before >= statement.first && keyName(before) !in setOf("{", "}", ";", "=", "->")) {
                    if (keyName(before) in setOf("fun", "val", "var", "class", "interface", "object", "typealias")) {
                        return true
                    }
                    if (keyName(before) in setOf(")", "]")) {
                        val left = if (keyName(before) == ")") "(" else "["
                        before = (statement.first until before).lastOrNull {
                            keyName(it) == left && installClose(kt, it) == before
                        } ?: return true
                    }
                    before--
                }
                return false
            }
            val competingKey = kt.indices.any { i ->
                keyName(i) == "key" && keyImports.none { i in it } &&
                    (keyName(i + 1) != "(" || declarationHeader(i))
            }
            if (exactKeyImports.size != 1 || competingImport || competingKey) v += "Q-holder-install-key"
            // L1: retain standard-let binding as a premise, but reject holder-local competitors.
            // Calls with parentheses or a trailing lambda are allowed; other let uses fail closed.
            val letAlias = keyImports.any { span ->
                keyName(span.last - 1) == "as" && keyName(span.last) == "let"
            }
            val competingLet = kt.indices.any { i ->
                keyName(i) == "let" && keyImports.none { i in it } &&
                    (keyName(i + 1) !in setOf("(", "{") || declarationHeader(i))
            }
            if (letAlias || competingLet) v += "Q-holder-install-let"
            if (installSheetGates(text).isEmpty()) v += "Q-holder-install-sheet"
            val adds = scan.builds.filter { it.mode == "add" }
            val edits = scan.builds.filter { it.mode == "edit" }
            if (scan.builds.size != 2 || adds.size != 1 || edits.size != 1) return v + "Q-holder-install-mode"
            val braces = pairs(text, '{', '}')
            fun lambdaAt(m: MatchResult?): IntRange? = m?.let { found -> braces.firstOrNull { it.first == found.range.last } }
            val onAdd = lambdaAt(Regex("""\bonAdd\s*=\s*\{""").find(text))
            val onEdit = lambdaAt(Regex("""\bonEdit\s*=\s*\{""").find(text))
            val runs = Regex("""\bnotificationAction\s*\.\s*run\s*\{""").findAll(text).mapNotNull { lambdaAt(it) }.toList()
            val addRuns = runs.filter { r -> onAdd != null && r.first in onAdd }
            if (addRuns.size != 1 || adds.single().span.first !in addRuns.single()) v += "Q-holder-install-add"
            val e = edits.single().span.first
            if (onEdit == null || e !in onEdit || runs.any { e in it }) v += "Q-holder-install-edit"
            return v
        }

        // Q09 token offsets refer to the INPUT, including raw-source comments in formatting controls.
        private data class InstallToken(val name: String, val start: Int, val end: Int, val backtick: Boolean = false)
        private data class InstallBuild(val span: IntRange, val mode: String?)
        private data class InstallWrite(
            val span: IntRange, val equalsEnd: Int, val call: IntRange?, val mode: String?,
            val receiver: Boolean, val operator: String, val direct: Boolean, val nullOnly: Boolean, val complete: Boolean)
        private data class InstallScan(
            val tokens: List<InstallToken>, val type: String?, val builds: List<InstallBuild>,
            val writes: List<InstallWrite>, val backtick: Boolean)

        private fun installTokens(text: String): List<InstallToken> {
            val out = mutableListOf<InstallToken>()
            val operators = listOf("===", "!==", "..<", "==", "!=", "<=", ">=", "?:", "?.", "::", "->",
                "+=", "-=", "*=", "/=", "%=", "++", "--", "&&", "||", "!!", "..")
            fun scan(from: Int, until: Int) {
                var i = from
                while (i < until) {
                    val start = i
                    when {
                        text[i].isWhitespace() -> i++
                        text.startsWith("//", i) -> { while (i < until && text[i] != '\n' && text[i] != '\r') i++ }
                        text.startsWith("/*", i) -> {
                            var depth = 1
                            i += 2
                            while (i < until && depth > 0) {
                                when {
                                    text.startsWith("/*", i) -> { depth++; i += 2 }
                                    text.startsWith("*/", i) -> { depth--; i += 2 }
                                    else -> i++
                                }
                            }
                        }
                        text[i] == '"' || text[i] == '\'' -> {
                            val end = literalEnd(text, i)
                            out += InstallToken("<literal>", start, end)
                            // Keep executable template expressions visible; ordinary literal contents are opaque.
                            if (text[i] == '"') {
                                val raw = text.startsWith("\"\"\"", i)
                                var j = i + if (raw) 3 else 1
                                while (j < end) {
                                    if (!raw && text[j] == '\\') { j += 2; continue }
                                    if (text[j] == '$' && text.getOrNull(j + 1) == '{') {
                                        val close = closing(text, j + 1) ?: break
                                        out += InstallToken("{", j + 1, j + 2)
                                        scan(j + 2, close)
                                        out += InstallToken("}", close, close + 1)
                                        j = close + 1
                                    } else j++
                                }
                            }
                            i = end
                        }
                        text[i] == 96.toChar() -> {
                            i = (text.indexOf(96.toChar(), i + 1).takeIf { it >= 0 } ?: (text.length - 1)) + 1
                            out += InstallToken(text.substring(start + 1, i - 1), start, i, true)
                        }
                        text[i].isLetterOrDigit() || text[i] == '_' -> {
                            while (i < until && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                            out += InstallToken(text.substring(start, i), start, i)
                        }
                        else -> {
                            val op = operators.firstOrNull { text.startsWith(it, i) } ?: text[i].toString()
                            i += op.length
                            out += InstallToken(op, start, i)
                        }
                    }
                }
            }
            scan(0, text.length)
            return out
        }

        private fun installClose(tokens: List<InstallToken>, open: Int): Int? {
            val right = when (tokens.getOrNull(open)?.name) { "(" -> ")"; "{" -> "}"; "[" -> "]"; else -> return null }
            val stack = ArrayDeque<String>()
            for (i in open until tokens.size) {
                val name = tokens[i].name
                when (name) {
                    "(" -> stack.addLast(")")
                    "{" -> stack.addLast("}")
                    "[" -> stack.addLast("]")
                    ")", "}", "]" -> {
                        if (stack.isEmpty() || stack.removeLast() != name) return null
                        if (stack.isEmpty()) return i.takeIf { name == right }
                    }
                }
            }
            return null
        }

        private fun installEnds(text: String, tokens: List<InstallToken>, end: Int): Boolean {
            val next = tokens.getOrNull(end + 1) ?: return true
            if (next.name in setOf(";", "}")) return true
            // squash erases the newline before the next when branch. A branch label is not an RHS type check.
            if (next.name == "is") {
                var labelEnd = end + 2
                if (tokens.getOrNull(labelEnd)?.name?.firstOrNull()?.let { it.isLetter() || it == '_' } == true) {
                    labelEnd++
                    while (tokens.getOrNull(labelEnd)?.name == "." &&
                        tokens.getOrNull(labelEnd + 1)?.name?.firstOrNull()?.let { it.isLetter() || it == '_' } == true) labelEnd += 2
                    if (tokens.getOrNull(labelEnd)?.name == "->") {
                        val block = (0..end).lastOrNull { tokens[it].name == "{" && (installClose(tokens, it) ?: -1) > end }
                        if (block != null) {
                            val before = block - 1
                            if (tokens.getOrNull(before)?.name == "when") return true
                            if (tokens.getOrNull(before)?.name == ")") {
                                val paren = (0 until before).lastOrNull { tokens[it].name == "(" && installClose(tokens, it) == before }
                                if (paren != null && tokens.getOrNull(paren - 1)?.name == "when") return true
                            }
                        }
                    }
                }
            }
            val gap = text.substring(tokens[end].end, next.start)
            if ('\n' !in gap && '\r' !in gap) return false
            // Tokens Kotlin can join across a line break; conservatively includes calls and trailing lambdas.
            return next.name !in setOf(".", "?.", "?:", "::", "!!", "(", "[", "{", "?", ":", ",",
                "+", "-", "*", "/", "%", "<", ">", "<=", ">=", "==", "!=", "===", "!==", "&&", "||",
                "..", "..<", "in", "is", "as", "!", "++", "--", "=", "+=", "-=", "*=", "/=", "%=")
        }

        private fun installScan(text: String): InstallScan {
            val t = installTokens(text)
            fun name(i: Int) = t.getOrNull(i)?.name
            val types = t.indices.filter { name(it) == "var" && name(it + 1) == "editor" && name(it + 2) == "by" &&
                name(it + 3) == "mutableStateOf" && name(it + 4) == "<" && name(it + 6) == "?" && name(it + 7) == ">" }
            val type = types.singleOrNull()?.let { name(it + 5) }
            val builds = if (type == null) emptyList() else t.indices.mapNotNull { i ->
                if (name(i) != type || name(i + 1) != "(" || name(i - 1) in setOf("class", "fun")) return@mapNotNull null
                val close = installClose(t, i + 1) ?: return@mapNotNull null
                val first = i + 2
                val mode = if (name(first) == "BankAlertEditorMode" && name(first + 1) == ".") when (name(first + 2)) {
                    "Add" -> if (name(first + 3) in setOf(",", ")")) "add" else null
                    "Edit" -> if (name(first + 3) == "(" &&
                        installClose(t, first + 3)?.let { name(it + 1) in setOf(",", ")") } == true) "edit" else null
                    else -> null
                } else null
                InstallBuild(t[i].start..(t[close].end - 1), mode)
            }
            val writes = t.indices.mapNotNull { i ->
                if (name(i) != "editor" || name(i + 1) !in setOf("=", "+=", "-=", "*=", "/=", "%=")) return@mapNotNull null
                val qualified = name(i - 1) in setOf(".", "?.")
                val start = if (qualified) maxOf(0, i - 2) else i
                val receiver = qualified && name(i - 1) == "." && name(i - 2) == "session" &&
                    name(i - 3) !in setOf(".", "?.", "::")
                val rhs = i + 2
                val build = builds.singleOrNull { it.span.first == t.getOrNull(rhs)?.start }
                val end = build?.let { b -> t.indexOfFirst { it.end == b.span.last + 1 } } ?: rhs
                val literalNull = name(rhs) == "null"
                val complete = (literalNull || build != null) && end in t.indices && installEnds(text, t, end)
                val last = t.getOrNull(end)?.end ?: t[i + 1].end
                InstallWrite(t[start].start until last, t[i + 1].end, build?.span, build?.mode,
                    receiver, name(i + 1)!!, receiver && name(i + 1) == "=" && build != null,
                    literalNull && complete, complete)
            }
            val backtick = t.any { it.backtick && it.name == type } ||
                types.any { t[it + 1].backtick } ||
                t.indices.any { t[it].backtick && name(it) == "editor" && name(it + 1) in setOf("=", "+=", "-=", "*=", "/=", "%=") }
            return InstallScan(t, type, builds, writes, backtick)
        }

        /**
         * Q09 surface: one sheet in an exact editor != null THEN block or an editor nullable-let body.
         * Read session.editor directly or through one immutable local; no local name is prescribed.
         * The binding is in the same enclosing block before the guard and has no RHS suffix.
         * Follow at most one plain named block function: one declaration, one bare call, no other name use.
         * At EVERY block, the whole statement containing the target must be key(...) { body }, with
         * the target in that body, or the exact terminal call. Sheet terminals have no trailing lambda;
         * bridge call terminals may have one. Declarations, prefixes, suffixes and argument nesting fail closed.
         * Apply this both gate -> sheet/call and bridge body -> sheet. Sibling statements are unrestricted.
         * The gate token itself must start its enclosing statement (direct and caller paths alike).
         * The reader recognizes key/let by syntax; holderInstall separately checks their holder binding surface.
         * Return the CALLER's condition spans, so Q09 mutates the real gate, not the callee body.
         */
        private data class InstallSheetPath(
            val condition: IntRange, val block: IntRange, val site: IntRange, val guard: Int)

        private fun installSheetGates(text: String): List<IntRange> = installSheetPaths(text).map { it.condition }

        // Share N24's statement selection with path, gate-prefix and binding checks (token indices, inclusive).
        // root == -1 selects file-level statements for declaration headers; ordinary roots are braces.
        // Balanced containers stay indivisible. Prefixes must not become standalone "statements" at a newline.
        private fun installSheetStatementTokens(
            text: String, t: List<InstallToken>, root: Int, site: Int
        ): IntRange? {
            fun name(i: Int) = t.getOrNull(i)?.name
            val end = if (root == -1) t.size else installClose(t, root) ?: return null
            if ((root != -1 && name(root) != "{") || site !in (root + 1) until end) return null
            val firstImport = t.indexOfFirst { it.name == "import" }
            val syntheticSquash = firstImport > 0 && t.firstOrNull()?.name == "package" &&
                text.substring(0, t[firstImport].start).none { it == '\n' || it == '\r' || it == ';' }
            val incomplete = setOf("val", "var", "fun", "class", "interface", "object", "typealias",
                "return", "throw", "by", "=", "+=", "-=", "*=", "/=", "%=", ".", "?.", "::", "@", ":",
                ",", "->", "+", "-", "*", "/", "%", "<", ">", "<=", ">=", "==", "!=", "===", "!==",
                "&&", "||", "?:", "!", "in", "is", "as")
            var first = root + 1
            var i = first
            while (i < end) {
                if (name(i) == ";") { first = i + 1; i++; continue }
                // An annotation (including its optional balanced arguments) belongs to what follows.
                // A label's @ also stays attached; the resulting prefixed statement will be rejected.
                if (name(i) == "@") {
                    var after = i + 1
                    if (name(after + 1) == ":") after += 2
                    if (name(after) == "[") {
                        after = (installClose(t, after) ?: return null) + 1
                    } else {
                        if (t.getOrNull(after)?.name?.firstOrNull()?.let { it.isLetter() || it == '_' } != true) return null
                        after++
                        while (name(after) == "." && after + 1 < end) after += 2
                        if (name(after) == "(") after = (installClose(t, after) ?: return null) + 1
                    }
                    if (after >= end) return null
                    i = after
                    continue
                }
                val last = if (name(i) in setOf("(", "{", "[")) installClose(t, i) ?: return null else i
                if (last >= end) return null
                val next = name(last + 1)
                // Only the existing package/import squash control loses all newline separators.
                // Restore an unambiguous next declaration/control/call after a complete operand/container.
                val squashEnd = syntheticSquash && name(last) !in incomplete &&
                    (next in setOf("val", "var", "fun", "class", "object", "if", "when", "return", "throw") ||
                        (name(last + 2) == "(" && next?.firstOrNull()?.let { it.isLetter() || it == '_' } == true))
                val boundary = last + 1 == end || (name(last) !in incomplete && next != "@" &&
                    (installEnds(text, t, last) || squashEnd))
                if (boundary) {
                    if (site in first..last) return first..last
                    first = last + 1
                }
                i = last + 1
            }
            return null
        }

        private fun installSheetStatement(text: String, path: InstallSheetPath): IntRange? {
            val t = installTokens(text)
            val root = t.indexOfFirst { it.start == path.block.first && it.name == "{" }
            val site = t.indexOfFirst { it.start == path.site.first }
            if (root < 0 || site < 0) return null
            val statement = installSheetStatementTokens(text, t, root, site) ?: return null
            return t[statement.first].start until t[statement.last].end
        }

        private fun installSheetPaths(text: String): List<InstallSheetPath> {
            val t = installTokens(text)
            fun name(i: Int) = t.getOrNull(i)?.name
            val sheet = t.indices.filter { name(it) == "AlertAddSheet" && name(it + 1) == "(" }.singleOrNull() ?: return emptyList()
            data class SheetFunction(val declaration: Int, val body: IntRange)
            // Plain fun Name(...), with an optional simple/qualified nullable or generic return type.
            // Parameter defaults/lambdas are balanced by installClose; expression/extension/generic funs are outside this surface.
            val functions = t.indices.mapNotNull { i ->
                if (name(i) != "fun" || name(i + 2) != "(") return@mapNotNull null
                val close = installClose(t, i + 2) ?: return@mapNotNull null
                var body = close + 1
                if (name(body) == ":") {
                    body++
                    while (body < t.size && (name(body) in setOf(".", "?", "<", ">", ",", "*") ||
                            name(body)?.firstOrNull()?.let { it.isLetter() || it == '_' } == true)) body++
                }
                if (name(body) != "{") return@mapNotNull null
                val end = installClose(t, body) ?: return@mapNotNull null
                SheetFunction(i + 1, (body + 1) until end)
            }
            fun functionAt(i: Int) = functions.filter { i in it.body }.maxByOrNull { it.body.first }
            fun blockAt(i: Int) = (0 until i).lastOrNull { name(it) == "{" && (installClose(t, it) ?: -1) > i }
            fun allowedStatements(root: Int, site: Int, bridgeCall: Boolean): Boolean {
                var block = root
                while (true) {
                    val statement = installSheetStatementTokens(text, t, block, site) ?: return false
                    val first = statement.first
                    if (name(first + 1) != "(") return false
                    val args = installClose(t, first + 1) ?: return false
                    if (first == site) {
                        if (args == statement.last) return true
                        return bridgeCall && name(args + 1) == "{" &&
                            installClose(t, args + 1) == statement.last
                    }
                    // A whole key call, and descent ONLY through its trailing body, never its arguments.
                    if (name(first) != "key" || name(args + 1) != "{") return false
                    val body = args + 1
                    val close = installClose(t, body) ?: return false
                    if (close != statement.last || site !in (body + 1) until close) return false
                    block = body
                }
            }
            fun path(begin: Int, until: Int, root: Int, end: Int, site: Int, guard: Int) =
                InstallSheetPath(t[begin].start until t[until].start,
                    t[root].start until t[end].end, t[site].start until t[site].end, t[guard].start)
            // Pure squash is a synthetic code-map control: package/import statements lost their separators.
            // Never relax a real source's alias RHS boundary merely because its next token looks like a call.
            val firstImport = t.indexOfFirst { it.name == "import" }
            val syntheticSquash = firstImport > 0 && t.firstOrNull()?.name == "package" &&
                text.substring(0, t[firstImport].start).none { it == '\n' || it == '\r' || it == ';' }
            fun editorRead(from: Int, until: Int, guard: Int): Boolean {
                if ((from until until).map { name(it) } == listOf("session", ".", "editor")) return true
                if (until != from + 1) return false
                val local = name(from)
                val declarations = t.indices.filter { name(it) in setOf("val", "var") && name(it + 1) == local &&
                    it < guard && blockAt(it) == blockAt(guard) }
                val d = declarations.singleOrNull() ?: return false
                return name(d) == "val" && d < guard && blockAt(d) == blockAt(guard) &&
                    (d + 2..d + 5).map { name(it) } == listOf("=", "session", ".", "editor") &&
                    (installEnds(text, t, d + 5) || (syntheticSquash &&
                        (name(d + 6) in setOf("val", "var", "if", "when", "return", "throw") ||
                            (name(d + 7) == "(" && t.getOrNull(d + 6)?.name?.firstOrNull()?.isLetter() == true) ||
                            (name(d + 6) == local && name(d + 7) == "?." && name(d + 8) == "let"))))
            }
            fun gateStartsStatement(guard: Int): Boolean {
                val root = blockAt(guard) ?: return false
                return installSheetStatementTokens(text, t, root, guard)?.first == guard
            }
            fun gatesAt(site: Int, bridgeCall: Boolean): List<InstallSheetPath> = t.indices.mapNotNull { i ->
                // A named function declaration inside a guard does not execute its body at that guard.
                if (functionAt(i) != functionAt(site)) return@mapNotNull null
                // Nullable let is the other accepted gate. No callback variable name is assumed.
                if (name(i) == "?." && name(i + 1) == "let" && name(i + 2) == "{") {
                    val end = installClose(t, i + 2) ?: return@mapNotNull null
                    val begin = if (name(i - 2) == "." && name(i - 3) == "session") i - 3 else i - 1
                    if (gateStartsStatement(begin) &&
                        allowedStatements(i + 2, site, bridgeCall) && editorRead(begin, i, i)) {
                        return@mapNotNull path(begin, i, i + 2, end, site, begin)
                    }
                    return@mapNotNull null
                }
                if (name(i) != "if" || name(i + 1) != "(" || !gateStartsStatement(i)) return@mapNotNull null
                val close = installClose(t, i + 1) ?: return@mapNotNull null
                if (name(close + 1) != "{") return@mapNotNull null
                val end = installClose(t, close + 1) ?: return@mapNotNull null
                if (!allowedStatements(close + 1, site, bridgeCall)) return@mapNotNull null
                val begin = i + 2
                val nonNull = (name(close - 2) == "!=" && name(close - 1) == "null" &&
                    editorRead(begin, close - 2, i)) ||
                    (name(begin) == "null" && name(begin + 1) == "!=" && editorRead(begin + 2, close, i))
                if (nonNull) path(begin, close, close + 1, end, site, i) else null
            }
            val direct = gatesAt(sheet, false)
            if (direct.isNotEmpty()) return direct
            val owner = functionAt(sheet) ?: return emptyList()
            if (!allowedStatements(owner.body.first - 1, sheet, false)) return emptyList()
            val uses = t.indices.filter { name(it) == name(owner.declaration) }
            // Count every normalized name use: duplicates, overloads, references and unsupported call spellings fail closed.
            if (uses.size != 2) return emptyList()
            val call = uses.singleOrNull { it != owner.declaration } ?: return emptyList()
            if (name(call + 1) != "(" || name(call - 1) in setOf(".", "?.", "::") ||
                call in owner.body || installClose(t, call + 1) == null) return emptyList()
            return gatesAt(call, true)
        }


        /** N: AlertSection's prompt and banner (API §3.2): placed, guarded, one click each, both calling onEnableNotifications. */
        fun sectionNotify(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("N-missing")
            val v = mutableListOf<String>()
            val r = regions(text, v) ?: return v
            val braces = pairs(text, '{', '}')
            fun guarded(p: Int, branch: IntRange) = braces.any { b ->
                p > b.first && p < b.last && b.first in branch && GUARD.containsMatchIn(text.substring(maxOf(branch.first, b.first - 80), b.first))
            }
            fun at(tag: String) = tagPattern(tag).findAll(text).map { it.range.first }.toList()
            val empty = r.branch.getValue("Empty")
            val rowsBranch = r.branch.getValue("Rows")
            val prompt = at("NOTIFY_PROMPT")
            if (prompt.size != 1 || prompt.single() !in empty || !guarded(prompt.single(), empty)) v += "N-prompt-place $prompt"
            val button = at("NOTIFY_PROMPT_BUTTON")
            if (button.size != 1 || button.single() !in empty || !guarded(button.single(), empty)) v += "N-prompt-button-place $button"
            val buttons = BUTTON_CALL.findAll(text).mapNotNull { callSpan(text, it) }.filter { s -> button.any { it in s } }.toList()
            if (buttons.size != 1) {
                v += "N-prompt-button ${buttons.size}"
            } else {
                val span = buttons.single()
                val open = text.indexOf('(', span.first)
                val args = topLevelArgs(text.substring(open + 1, closing(text, open)!!))
                // S7 E5b3b (start agreement §4-2, API r2 D5): the prompt now asks for the add entry.
                if (args.count { it == "onClick = onAdd" } != 1 || args.count { it.startsWith("onClick") } != 1) v += "N-prompt-callback $args"
                val inside = text.substring(span.first, span.last + 1)
                if (CLICK_MODIFIER.findAll(inside).count() != 0 || CLICK_ACTION.findAll(inside).map { it.value }.toList() != listOf("Button", "onClick")) {
                    v += "N-prompt-clicks"
                }
            }
            val banner = at("NOTIFY_BANNER")
            val rowsTag = at("ROWS")
            if (banner.size != 1 || banner.single() !in rowsBranch || !guarded(banner.single(), rowsBranch) ||
                rowsTag.size != 1 || banner.single() > rowsTag.single()) {
                v += "N-banner-place $banner"
            }
            val clicks = bannerClicks(text).map { squash(text.substring(it.first, it.last + 1)) }
            if (clicks != listOf(".clickable(onClick = onEnableNotifications)")) v += "N-banner-click $clicks"
            if (count(text, """\bonEnableNotifications\b""") != 2) v += "N-callback-uses ${count(text, """\bonEnableNotifications\b""")}"
            if (count(text, """\bnotificationsBlocked\b""") != 3) v += "N-flag-uses ${count(text, """\bnotificationsBlocked\b""")}"
            val emptyTag = at("EMPTY")
            if (emptyTag.size != 1 || emptyTag.single() !in empty || guarded(emptyTag.single(), empty)) v += "N-empty-shape $emptyTag"
            return v
        }

        /** N: the holder asks the helper once, right after the tab check, inside BankAlertSection, and passes needsAction and run only. */
        fun holderNotify(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("N-holder-missing")
            val v = mutableListOf<String>()
            if (count(text, """\brememberNotificationPermissionAction\b""") != 1 || HELPER_CALL.findAll(text).count() != 1) v += "N-holder-call"
            val flat = squash(text)
            for (line in NOTIFY_WIRING) if (count(flat, Regex.escape(line)) != 1) v += "N-holder-wiring $line"
            val uses = count(text, """\bnotificationAction\b""")
            // S7 E5b3b (API r2 D8, R8-3): declaration, needsAction, the banner's run(), the add and the save continuations.
            if (uses != 5) v += "N-holder-uses $uses"
            val continuations = Regex("""\bnotificationAction\s*\.\s*run\s*\{""").findAll(text).map { it.range.first }.toList()
            val body = bodyRange(text, "BankAlertSection")
            val call = HELPER_CALL.find(text)
            if (body == null || call == null || call.range.first !in body) v += "N-holder-place"
            if (continuations.size != 2 || body == null || continuations.any { it !in body }) v += "N-holder-continuations $continuations"
            // S7 E5b3b (gap hunt G17): one run in the section's onAdd lambda, the other in a `{ input ->` callback, and neither inside
            // an effect, a remember or a state producer, so each runs in the click that asked for it.
            val braces = pairs(text, '{', '}')
            val onAdd = Regex("""\bonAdd\s*=\s*\{""").find(text)?.let { m -> braces.firstOrNull { it.first == m.range.last } }
            val callbacks = Regex("""\{\s*\w+\s*->""").findAll(text).mapNotNull { m -> braces.firstOrNull { it.first == m.range.first } }.toList()
            // The lambda that directly follows such a call, after its optional argument list.
            fun lambdaAfter(end: Int): IntRange? {
                var i = end + 1
                while (i < text.length && text[i].isWhitespace()) i++
                if (text.getOrNull(i) == '(') {
                    i = (closing(text, i) ?: return null) + 1
                    while (i < text.length && text[i].isWhitespace()) i++
                }
                return if (text.getOrNull(i) == '{') braces.firstOrNull { it.first == i } else null
            }
            val deferred = Regex("""\b(?:LaunchedEffect|DisposableEffect|SideEffect|remember\w*|produceState|snapshotFlow|derivedStateOf)\b""").findAll(text)
                .mapNotNull { lambdaAfter(it.range.last) }.toList()
            val inAdd = continuations.filter { p -> onAdd != null && p in onAdd }
            val inCallback = continuations.filter { p -> callbacks.any { p in it } && (onAdd == null || p !in onAdd) }
            if (inAdd.size != 1 || inCallback.size != 1 || continuations.any { p -> deferred.any { p in it } }) v += "N-holder-run-place $continuations"
            return v
        }

        /** A call from its name through its closing parenthesis and a directly trailing lambda. */
        fun callSpan(text: String, name: MatchResult): IntRange? {
            val open = text.indexOf('(', name.range.first)
            val close = closing(text, open) ?: return null
            var i = close + 1
            while (i < text.length && text[i].isWhitespace()) i++
            if (text.getOrNull(i) != '{') return name.range.first..close
            val lambda = closing(text, i) ?: return null
            return name.range.first..lambda
        }

        /** S7 E5a2: the spans of every Switch call, every IconButton holding the per-row delete tag, and every AlertDialog call. */
        fun rowActionSpans(text: String, imports: List<IntRange>): List<IntRange> {
            fun code(m: MatchResult) = imports.none { m.range.first in it }
            val deleteTags = tagPattern("delete").findAll(text).map { it.range.first }.toList()
            return SWITCH_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }.toList() +
                ICON_BUTTON_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }.filter { s -> deleteTags.any { it in s } }.toList() +
                DIALOG_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }.toList()
        }

        /**
         * S7 E5a2 R: the row's one target-valued switch and one delete button, and the one confirmation dialog that keeps the
         * callback of the identity it was opened under. Toggle carries no permission; edit, add and row taps stay forbidden.
         */
        fun sectionRowActions(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("R-missing")
            val v = mutableListOf<String>()
            val problems = mutableListOf<String>()
            val r = regions(text, problems) ?: return listOf("R-regions $problems")
            val imports = IMPORT_LINE.findAll(text).map { it.range }.toList()
            fun code(m: MatchResult) = imports.none { m.range.first in it }
            fun inRows(p: Int) = r.rows.any { p in it }
            fun body(s: IntRange) = text.substring(s.first, s.last + 1)
            if (Regex("""\bimport\s+[\w.`]*\b(?:Switch|AlertDialog|IconButton)\b`?\s+as\b""").containsMatchIn(text)) v += "R-alias"
            val switches = SWITCH_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }.toList()
            if (switches.size != 1) v += "R-switch ${switches.size}"
            else {
                val s = switches.single()
                if (!inRows(s.first)) v += "R-switch-place"
                if (count(body(s), tagPattern("toggle").pattern) != 1) v += "R-switch-tag"
                if (count(body(s), """\bonCheckedChange\s*=\s*onToggle\s*[,)]""") != 1) v += "R-switch-target"
                if (count(body(s), """\bchecked\s*=\s*setting\s*\.\s*isEnabled\s*[,)]""") != 1) v += "R-switch-checked"
            }
            if (count(text, """\bonCheckedChange\b""") != 1) v += "R-switch-target count"
            val deleteTags = tagPattern("delete").findAll(text).toList()
            val buttons = ICON_BUTTON_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }
                .filter { s -> deleteTags.any { it.range.first in s } }.toList()
            if (deleteTags.size != 1 || buttons.size != 1) v += "R-delete ${deleteTags.size} ${buttons.size}"
            else if (!inRows(buttons.single().first)) v += "R-delete-place"
            if (count(text, """\bonClick\s*=\s*onDelete\s*[,)]""") != 1) v += "R-delete"
            val dialogs = DIALOG_CALL.findAll(text).filter(::code).mapNotNull { callSpan(text, it) }.toList()
            if (dialogs.size != 1) v += "R-dialog ${dialogs.size}"
            else {
                val d = dialogs.single()
                val b = body(d)
                if (inRows(d.first) || d.first in r.content) v += "R-dialog-place"
                for (tag in listOf("DELETE_DIALOG", "DELETE_CONFIRM", "DELETE_CANCEL")) if (count(b, tagPattern(tag).pattern) != 1) v += "R-dialog-tag $tag"
                if (count(b, """\bpending\s*\.\s*confirm\s*\(\s*\)""") != 1 || count(text, """\.\s*confirm\s*\(\s*\)""") != 1) v += "R-dialog-confirm"
                for (copy in listOf(""""알림 삭제"""", """"'\$\{pending\.bankName\}' 알림을 삭제하시겠습니까\?"""", """"삭제"""", """"취소"""")) {
                    if (count(b, copy) != 1) v += "R-dialog-copy $copy"
                }
            }
            if (count(text, """\bremember\s*\(\s*actionIdentity\s*\)""") != 1) v += "R-pending-identity"
            if (count(text, """(?<!\bclass\s)\bPendingBankDelete\s*\(""") != 1 || count(text, """\bonDelete\s*\(\s*id\s*\)""") != 1 ||
                count(text, """\bonDelete\s*\(""") != 1) {
                v += "R-delete-confirm"
            }
            if (count(text, """\bonToggle\s*\(\s*setting\s*\.\s*id\s*,\s*enabled\s*\)""") != 1 || count(text, """\bonToggle\s*\(""") != 1) v += "R-toggle-call"
            if (count(text, "삭제") != 4) v += "R-delete-label ${count(text, "삭제")}"
            return v
        }

        /** The route's one BankAlertSection call, name to closing parenthesis; null unless exactly one. */
        fun sectionCall(text: String): IntRange? {
            val m = CALL.findAll(text).toList().singleOrNull() ?: return null
            val open = text.indexOf('(', m.range.first)
            val close = closing(text, open) ?: return null
            return m.range.first..close
        }

        class Slot(val condition: IntRange, val param: String, val body: IntRange)

        private fun skip(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        /** The route's `belowRates = if (condition) { { param -> body } } else null`; null for any other form. */
        fun belowRates(text: String): Slot? {
            val m = Regex("""\bbelowRates\s*=\s*""").findAll(text).toList().singleOrNull() ?: return null
            var i = m.range.last + 1
            if (!text.startsWith("if", i)) return null
            i = skip(text, i + 2)
            if (text.getOrNull(i) != '(') return null
            val conditionClose = closing(text, i) ?: return null
            val condition = (i + 1) until conditionClose
            i = skip(text, conditionClose + 1)
            if (text.getOrNull(i) != '{') return null
            val outerClose = closing(text, i) ?: return null
            val j = skip(text, i + 1)
            if (text.getOrNull(j) != '{') return null
            val lambdaClose = closing(text, j) ?: return null
            if (text.substring(lambdaClose + 1, outerClose).isNotBlank()) return null
            if (!Regex("""^\s*else\s+null\b""").containsMatchIn(text.substring(outerClose + 1))) return null
            val head = Regex("""^\s*(\w+)\s*->""").find(text.substring(j + 1, lambdaClose)) ?: return null
            return Slot(condition, head.groupValues[1], (j + 1 + head.range.last + 1) until lambdaClose)
        }

        /** The section call's arguments: the slot's tab (named or first) and `sourceFactory = [factory]`, an optional modifier. */
        fun agreedArgs(args: List<String>, tab: String, factory: String): Boolean {
            val named = mutableMapOf<String, String>()
            for ((k, a) in args.withIndex()) {
                val m = Regex("""^(\w+) ?= ?(?!=)(.+)$""").matchEntire(a)
                if (m != null) {
                    if (named.put(m.groupValues[1], m.groupValues[2]) != null) return false
                } else if (k == 0) {
                    named["tab"] = a
                } else {
                    return false
                }
            }
            // S7 E5b3b (API r2 D4): the screen's own alert quotes, one argument.
            return named.keys - setOf("modifier") == setOf("tab", "alertQuotes", "sourceFactory") && named["tab"] == tab &&
                named["alertQuotes"] == "screen.ui.alertQuotes" && named["sourceFactory"] == factory
        }

        /** P: the route's mount of the section, and the section's call inventory over every root. */
        fun routeMount(code: Map<String, String>): List<String> {
            val route = code[ROUTE] ?: return listOf("P-missing")
            val v = mutableListOf<String>()
            val calls = code.mapValues { (_, t) -> CALL.findAll(t).count() - count(t, """\bfun\s+BankAlertSection\s*\(""") }.filterValues { it != 0 }
            if (calls != mapOf(ROUTE to 1)) v += "P-calls $calls"
            val references = files(code, """::\s*BankAlertSection\b""")
            if (references.isNotEmpty()) v += "P-function-reference ${references.sorted()}"
            val aliases = files(code, """\bimport\s+[\w.`]*\bBankAlertSection\b`?\s+as\b""")
            if (aliases.isNotEmpty()) v += "P-alias ${aliases.sorted()}"
            val named = files(code, """\bBankAlertSection\b""")
            if (named != setOf(HOLDER, ROUTE)) v += "P-named ${named.sorted()}"
            val sectionImports = count(route, importOf("BankAlertSection"))
            if (sectionImports > 1 || count(route, """\bBankAlertSection\b""") != 1 + sectionImports) v += "P-route-tokens"
            val last = paramsRange(route, "PremiumTopicRoute")?.let { topLevelArgs(route.substring(it)) }?.lastOrNull()
            if (last != FACTORY_PARAMETER) v += "P-parameter $last"
            if (count(route, """\bbankAlertSectionSourceFactory\b""") != 2) v += "P-factory-tokens parameter"
            for (name in listOf("BankAlertSectionSourceFactory", "ProductionBankAlertSectionSourceFactory")) {
                val imports = count(route, importOf(name))
                if (imports > 1 || count(route, """\b$name\b""") != 1 + imports) v += "P-factory-tokens $name"
            }
            for ((tag, pattern) in ROUTE_FORBIDDEN) if (Regex(pattern).containsMatchIn(route)) v += "P-route-owner $tag"
            val slot = belowRates(route) ?: return v + "P-below-rates"
            val condition = squash(route.substring(slot.condition))
            if (condition != CONDITION) v += "P-condition $condition"
            val body = route.substring(slot.body)
            val entries = HISTORY_CALL.findAll(body).toList()
            if (entries.size != 1) return v + "P-history-entry ${entries.size}"
            val entryOpen = body.indexOf('(', entries.single().range.first)
            val entryClose = closing(body, entryOpen) ?: return v + "P-history-entry unclosed"
            val entryArgs = topLevelArgs(body.substring(entryOpen + 1, entryClose))
            if (entryArgs != historyArgs(slot.param)) v += "P-history-entry $entryArgs"
            val inside = CALL.findAll(body).toList()
            if (inside.size != 1) return v + "P-lambda ${inside.size}"
            if (inside.single().range.first < entryClose) v += "P-order"
            val open = body.indexOf('(', inside.single().range.first)
            val close = closing(body, open) ?: return v + "P-args unclosed"
            val args = topLevelArgs(body.substring(open + 1, close))
            if (!agreedArgs(args, slot.param, "bankAlertSectionSourceFactory")) v += "P-args $args"
            return v
        }
    }
}
