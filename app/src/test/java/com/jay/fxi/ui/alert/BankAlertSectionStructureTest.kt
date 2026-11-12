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
 *   call in the file).
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
            Triple("an add button", appended(SECTION, "@Composable private fun S7e5a1(onAdd: () -> Unit) = TextButton(onClick = onAdd) { Text(\"알림 추가\") }"), "S-entry add"),
            Triple("an add icon", appended(SECTION, "private val s7e5a1 = Icons.Default.Add"), "S-entry add"),
            Triple("the count limit", appended(SECTION, "private val s7e5a1 = com.jay.fxi.ui.viewmodel.AlertViewModel.MAX_COUNT"), "S-entry add"),
            Triple("an edit callback in the signature", edited(SECTION, """\bonRefresh\s*:\s*\(\s*\)\s*->\s*Unit\s*,""", "onRefresh: () -> Unit,\n    onEdit: (AlertSetting) -> Unit,"),
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
            Triple("another tab passed", routeWith { t, c -> t.replaceRange(c, "BankAlertSection(tab = FreeTab.USD, sourceFactory = $f)") }, "P-args"),
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
            "named arguments swapped and reformatted" to routeWith { t, c -> t.replaceRange(c, "BankAlertSection(\n    sourceFactory = $f,\n    tab = $p,\n)") },
            "a positional tab and a modifier" to routeWith { t, c -> t.replaceRange(c, "BankAlertSection($p, modifier = Modifier, sourceFactory = $f)") },
            "one qualified call without an import" to routeWith(code + (ROUTE to unimported.replace(route, ""))) { t, c ->
                t.replaceRange(c, "com.jay.fxi.ui.alert.BankAlertSection(tab = $p, sourceFactory = $f)")
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
        val OWNER_ALLOWED = setOf("state", "manualRefresh", "ensure", "authoritative", "manual", "setEnabled", "delete")
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
            "lifecycle-observer" to """\baddObserver\b|\bremoveObserver\b|\b\w*LifecycleObserver\b|\bLifecycle(?:Resume|Start|Event)Effect\b|\brepeatOnLifecycle\b""")

        /** API r2 §3 / §5 row 3: what AlertSection never draws or names. */
        val SECTION_FORBIDDEN = listOf(
            // S7 E5a2: the row switch, the delete button and its dialog are governed by sectionRowActions instead.
            "toggle-other" to """\bCheckbox\b|\bTriStateCheckbox\b|\btoggleable\b|\btriStateToggleable\b|\bSlider\b""",
            "swipe" to """\bSwipeToDismiss\w*""",
            "add" to """\bonAdd\w*|\bAlertAddSheet\b|\bAddAlert\w*|\bIcons\s*\.\s*\w+\s*\.\s*Add\b|\bFloatingActionButton\b|추가|\bMAX_COUNT\b""",
            "edit" to """(?i)edit|편집|수정|\bonTap\b|\bonLongClick\b""",
            "permission" to """(?i)permission|POST_NOTIFICATIONS|opensettings|\bACTION_\w*SETTINGS\b|\bActivityResultContracts\b|\brememberLauncherForActivityResult\b|\bstartActivit(?:y|ies)\w*|\bareNotificationsEnabled\b""",
            "AlertRow" to """\bAlertRow\b""",
            "view-model" to """\bAlertViewModel\b|\bAlertState\b|\bhiltViewModel\b""")

        /** API r2 §3: the read-only signature, squashed. */
        val SIGNATURE = listOf("currency: SupportedCurrency", "state: BankAlertSectionState", "onRetry: () -> Unit", "onRefresh: () -> Unit",
            "actionIdentity: AuthIdentityFence?", "onToggle: (Int, Boolean) -> Unit", "onDelete: (Int) -> Unit",
            "modifier: Modifier = Modifier", "metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current")

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
            "fun ensure()", "fun authoritative()", "fun manual()", "fun setEnabled(id: Int, enabled: Boolean)", "fun delete(id: Int)")
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

        class Regions(val content: IntRange, val header: List<IntRange>, val rows: List<IntRange>, val error: List<IntRange>)

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
                closure(text, listOf(branch.getValue("Error")), funs))
        }

        // ---- the locks: each returns its violations, empty for an accepted tree ----

        /** H: the holder's forbidden names, directly or through a type alias declared anywhere in the roots. */
        fun holderNames(code: Map<String, String>): List<String> {
            val text = code[HOLDER] ?: return listOf("H-missing")
            val v = HOLDER_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(text) }.map { (tag, _) -> "H-name $tag" }.toMutableList()
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
            return SECTION_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(text) }.map { (tag, _) -> "S-entry $tag" }
        }

        /** S: where AlertSection's click surfaces are; the regions' own shape is reported as a violation too. */
        fun sectionClicks(code: Map<String, String>): List<String> {
            val text = code[SECTION] ?: return listOf("S-missing")
            val v = mutableListOf<String>()
            val r = regions(text, v) ?: return v
            val imports = IMPORT_LINE.findAll(text).map { it.range }.toList()
            fun within(p: Int, ranges: List<IntRange>) = ranges.any { p in it }
            val actions = rowActionSpans(text, imports)
            for ((kind, regex) in listOf("modifier" to CLICK_MODIFIER, "action" to CLICK_ACTION)) {
                for (m in regex.findAll(text)) {
                    val p = m.range.first
                    when {
                        within(p, imports) -> Unit
                        // S7 E5a2: counted, placed and shaped by sectionRowActions.
                        within(p, actions) -> Unit
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
            return named.keys - setOf("modifier") == setOf("tab", "sourceFactory") && named["tab"] == tab && named["sourceFactory"] == factory
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
