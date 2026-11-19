package com.jay.fxi.ui.free

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5d structure (`s7e5d_api_agreed.r1.md`, 제안 r2 §2.3·§2.7·§3): what a running preview cannot show from outside.
 *
 * - P: the preview's files, the shared fields and the quote projection reach none of the capabilities D26 keeps at zero —
 *   permission helper, push, alert owner and repository, the remembered bank, history, topic, WS and Graph V2, saving of any
 *   kind, network, reflection, KRX — not by name, alias, qualified name, string, or a same-package neighbour; and they import
 *   nothing of the app beyond an allowlist.
 * - S: the preview sheet has no save at all and starts nothing; the reducer starts nothing; the fields start nothing.
 * - W: the canonical wiring of this land — one host above the pager, one section per bank page, one opener call carrying that
 *   tab's own quotes and as_of, one sheet, one CTA — and the remembered bank seeds nothing.
 *
 * Read from the comment-free code and literals of the production roots ([S6Krx3bOffWiringContractTest.ROOTS]).
 */
class S7E5DStructureTest {

    @Test
    fun premise_everyPreviewFileIsScanned() {
        for (path in PREVIEW_FILES + UI_STATE + SCREEN) assertTrue(path, path in code)
    }

    @Test
    fun P_thePreviewReachesNoForbiddenCapability() {
        assertEquals(emptyList<String>(), reach(code))
    }

    @Test
    fun P_theLockCatchesEachWayIn_andLeavesCommentsAlone() {
        for ((label, tree, tag) in listOf(
            Triple("an aliased owner", withImport(SECTION, "import com.jay.fxi.data.alerts.BankAlertSettingsOwner as Keeper"), "P-forbidden owner"),
            Triple("the remembered bank by qualified name", appended(SHEET, "private val m: com.jay.fxi.data.local.LastSelectedBankMemory? = null"), "P-forbidden memory"),
            Triple("the owner by string", appended(CTA, "private val n = \"BankAlertSettingsOwner\""), "P-forbidden owner"),
            Triple("a saveable draft", appended(SECTION, "@androidx.compose.runtime.Composable private fun s7e5d() { androidx.compose.runtime.saveable.rememberSaveable { 1 } }"),
                "P-forbidden persistence"),
            Triple("the permission helper's environment", appended(SESSION, "private val e: NotificationPermissionEnvironment? = null"), "P-forbidden permission"),
            Triple("reflection", appended(SESSION, "private val c = Class.forName(\"x\")"), "P-forbidden reflection"),
            Triple("a same-package neighbour", appended(SHEET, "private val v: FreeSnapshotViewModel? = null"), "P-neighbour"),
            Triple("a neighbour of the fields", appended(FIELDS, "private val h: BankHistoryController? = null"), "P-forbidden history"),
            Triple("an app import outside the allowlist", withImport(SESSION, "import com.jay.fxi.data.free.FreeSnapshotScheduler"), "P-import"),
            Triple("KRX in a string", appended(CTA, "private val k = \"krx\""), "P-forbidden krx"),
            Triple("a projection that reads the network", appended(UI_STATE, "private val s: com.jay.fxi.data.remote.AuthenticatedApiClient? = null"), "P-forbidden network")
        )) {
            assertCaught(label, tag, reach(tree))
        }
        assertEquals("주석은 위반이 아니다", emptyList<String>(), reach(appended(SECTION,
            "// BankAlertSettingsOwner rememberSaveable krx NotificationPermissionAction\n/* LastSelectedBankMemory Class.forName */\nprivate val s7e5dControl = 1")))
    }

    @Test
    fun S_thePreviewSheetHasNoSaveAndStartsNothing() {
        val sheet = flat(SHEET)
        assertEquals(1, count(sheet, Regex.escape(SHEET_SIGNATURE)))
        for ((name, pattern) in listOf(
            "submit" to """\bonSubmit\b|\bBankAlertEditor\s*\.\s*submit\b|\bAlertAddSheetTags\s*\.\s*SAVE\b|\bAlertAddSheet\s*\(""",
            "saving" to """\bsaving\b|\bSAVING\b""",
            "existing" to """\bexisting\b""",
            "seed" to """\baddBank\b"""
        )) {
            assertEquals("시트에 $name", 0, count(sheet, pattern))
        }
        assertEquals(1, count(sheet, Regex.escape("BankAlertEditor.check(BankAlertEditorMode.Add, input, currency, quotes, emptyList())")))
        assertEquals("남은 개수 0", 1, count(sheet, Regex.escape("remaining = null,")))
        assertEquals("CTA 하나", 1, count(sheet, """\bSubscribeCTA\s*\("""))
        assertEquals("시트 하나", 1, count(sheet, """\bModalBottomSheet\s*\("""))
        for (path in listOf(SHEET, SECTION, CTA, FIELDS, HOOKS_DEFAULT)) assertEquals("$path 가 무엇을 시작한다", 0, count(flat(path), EFFECTS))
        val reducer = flat(SESSION).substringAfter("internal class FreeAlertPreviewSession {").substringBefore("internal fun interface FreeAlertPreviewOpener")
        assertTrue("premise: reducer 본문을 찾았다", reducer.contains("fun dispose()"))
        assertEquals("reducer 가 무엇을 시작한다", 0, count(reducer, EFFECTS))
    }

    @Test
    fun W_theWiringIsOneHostOneSectionPerBankPageAndOneOpenerCall() {
        val screen = flat(SCREEN)
        for (line in SCREEN_WIRING) assertEquals(line, 1, count(screen, Regex.escape(line)))
        val session = flat(SESSION)
        for (line in HOST_WIRING) assertEquals(line, 1, count(session, Regex.escape(line)))
        assertEquals(
            "previewQuotes·previewAsOf 를 읽는 곳은 UiState 와 그 opener 호출뿐",
            setOf(UI_STATE, SCREEN),
            code.filterValues { Regex("""\bpreviewQuotes\b|\bpreviewAsOf\b""").containsMatchIn(it) }.keys
        )
        for ((name, want) in listOf(
            "FreeAlertPreviewHost" to (SCREEN to 1),
            "FreeBankAlertPreviewSection" to (SCREEN to 1),
            "FreeBankAlertPreviewSheet" to (SESSION to 1),
            "SubscribeCTA" to (SHEET to 1)
        )) {
            assertEquals("$name 호출", mapOf(want.first to want.second), calls(code, name))
        }
        assertEquals(
            "공유 본문은 premium 시트와 preview 시트가 한 번씩 부른다",
            mapOf(PREMIUM_SHEET to 1, SHEET to 1),
            calls(code, "BankAlertEditorFields")
        )
        assertEquals("기억 은행이 preview 를 seed 하지 않는다", 1,
            count(session, Regex.escape("BankAlertEditor.initial(BankAlertEditorMode.Add, frozen, null)")))
    }

    /** G14: a name from any package outside the allowlist fails, imported or written out in full, under a neutral name or not. */
    @Test
    fun P_theAllowlistCoversEveryPackage() {
        for ((label, tree, tag) in listOf(
            Triple("java.nio imported", withImport(SESSION, "import java.nio.file.Files"), "P-import java.nio.file.Files"),
            Triple("java.net in full", appended(SESSION, "private fun s7e5dA() = java.net.URL(\"x\").openStream()"), "P-qualified java.net.URL"),
            Triple("an android API in full", appended(SECTION, "private val s7e5dB = android.os.Build.VERSION.SDK_INT"), "P-qualified android.os.Build.VERSION.SDK_INT"),
            Triple("a neutral wrapper over a full name", appended(SHEET, "private fun s7e5dHelp(x: String) = java.lang.Runtime.getRuntime()"), "P-qualified java.lang.Runtime.getRuntime"),
            Triple("a database", withImport(FIELDS, "import android.database.sqlite.SQLiteDatabase"), "P-import android.database.sqlite.SQLiteDatabase"),
            Triple("kotlin io", withImport(CTA, "import kotlin.io.path.Path"), "P-import kotlin.io.path.Path"),
            Triple("kotlin reflection in full", appended(SESSION, "private val s7e5dC = kotlin.reflect.typeOf<String>()"), "P-qualified kotlin.reflect.typeOf"),
            Triple("a platform hook", appended(SHEET, "private val s7e5dD = androidx.compose.ui.platform.LocalUriHandler"), "P-qualified androidx.compose.ui.platform.LocalUriHandler"),
            Triple("text-field state", withImport(FIELDS, "import androidx.compose.foundation.text.input.rememberTextFieldState as keepText"), "P-import androidx.compose.foundation.text.input.rememberTextFieldState"),
            Triple("coroutines beyond launch", withImport(SESSION, "import kotlinx.coroutines.Dispatchers"), "P-import kotlinx.coroutines.Dispatchers"),
            Triple("an app name in full", appended(SECTION, "private val s7e5dE = com.jay.fxi.data.free.FreeSnapshotKey(\"usd\", com.jay.fxi.domain.model.GraphPeriod.ONE_DAY)"), "P-qualified com.jay.fxi.data.free.FreeSnapshotKey")
        )) {
            assertCaught(label, tag, reach(tree))
        }
        assertEquals("허용된 이름의 완전수식과 주석은 위반이 아니다", emptyList<String>(), reach(appended(SESSION,
            "// java.net.URL android.os.Build\nprivate val s7e5dF = kotlinx.datetime.Instant.DISTANT_PAST\nprivate val s7e5dG = androidx.compose.ui.unit.Dp(1f)")))
    }

    /** G12: the Screen's preview wiring does one thing each, and the Screen's other saving and fetching stay its own. */
    @Test
    fun P_theScreenWiringCatchesEachWayIn_andLeavesTheRestOfTheScreenAlone() {
        val opener = "onOpenPreview.open(tab, state.previewQuotes, state.previewAsOf)"
        val section = "FreeBankAlertPreviewSection(expanded = previewExpanded, onToggle = { previewExpanded = !previewExpanded }, onOpen = onOpenPreview)"
        val flag = "var previewExpanded by remember { mutableStateOf(false) }"
        val toggle = "onToggle = { previewExpanded = !previewExpanded }"
        val hostArg = "onSubscribe = onSubscribe,\n            hide = hooks::hide"
        val hostLambda = ") { openPreview ->"
        for ((label, tree, tag) in listOf(
            Triple("a store call before the opener", screenWith(opener, "onSelectTab(tab); $opener"), "P-screen-call onSelectTab opener"),
            Triple("a store call after the opener", screenWith(opener, "$opener; onApplyRowPreference(RateRowList.FX_BANKS, emptyList(), emptyList(), emptySet())"),
                "P-screen-call onApplyRowPreference opener"),
            Triple("a fetch again before opening", screenWith(opener, "onSelectPeriod(state.period); $opener"), "P-screen-call onSelectPeriod opener"),
            Triple("quotes made up on the way", screenWith(opener, "onOpenPreview.open(tab, state.previewQuotes.plus(\"kb\" to 1.0), state.previewAsOf)"),
                "P-screen-call plus opener"),
            Triple("a saved pending CTA in the section", screenWith(section, "rememberSaveable { mutableStateOf(false) }; $section"), "P-screen-forbidden persistence section"),
            Triple("a wrapper in full", screenWith(section, "FreeBankAlertPreviewSection(expanded = previewExpanded, onToggle = { previewExpanded = !previewExpanded }, onOpen = com.jay.fxi.ui.free.s7e5dWrap(onOpenPreview))"),
                "P-screen-call s7e5dWrap section"),
            Triple("the teaser's flag saved", screenWith(flag, "var previewExpanded by rememberSaveable { mutableStateOf(false) }"), "P-screen-forbidden persistence preview-state"),
            Triple("the teaser's flag seeded from outside", screenWith(flag, "var previewExpanded by remember { mutableStateOf(s7e5dSeed()) }"), "P-screen-call s7e5dSeed preview-state"),
            Triple("a store call in the toggle", screenWith(toggle, "onToggle = { onApplyRowPreference(RateRowList.FX_BANKS, emptyList(), emptyList(), emptySet()); previewExpanded = !previewExpanded }"),
                "P-screen-call onApplyRowPreference section"),
            Triple("the flag read elsewhere", screenWith(flag, "$flag; val s7e5dPeek = { previewExpanded }"), "P-screen-state-escape previewExpanded"),
            Triple("the flag under a condition", screenWith(flag, "if (visible) { $flag }"), "P-screen-state-place previewExpanded"),
            Triple("the flag handed something else", screenWith(section, "FreeBankAlertPreviewSection(expanded = true, onToggle = { previewExpanded = !previewExpanded }, onOpen = onOpenPreview)"),
                "P-screen-state-args"),
            Triple("a guard that calls", screenWith("if (tab in FREE_BANK_ALERT_PREVIEW_TABS) {", "if (tab in FREE_BANK_ALERT_PREVIEW_TABS && s7e5dAllow()) {"),
                "P-screen-call s7e5dAllow section-guard"),
            Triple("a host argument that calls", screenWith(hostArg, "onSubscribe = { s7e5dLog(); onSubscribe() },\n            hide = hooks::hide"),
                "P-screen-call s7e5dLog host-args"),
            Triple("the opener given to a helper", screenWith(hostLambda, "$hostLambda s7e5dKeep(openPreview)"), "P-screen-opener-escape openPreview"),
            Triple("the opener stored", screenWith(hostLambda, "$hostLambda val s7e5dHeld = openPreview;"), "P-screen-opener-escape openPreview"),
            Triple("the remembered bank by string in the opener", screenWith(opener, "onOpenPreview.open(tab, state.previewQuotes, state.previewAsOf).also { \"LastSelectedBankMemory\" }"),
                "P-screen-forbidden memory opener")
        )) {
            assertCaught(label, tag, reach(tree))
        }
        assertEquals("화면의 다른 저장·주석은 위반이 아니다", emptyList<String>(), reach(appended(SCREEN,
            "// onOpenPreview.open(tab) rememberSaveable LastSelectedBankMemory\n@androidx.compose.runtime.Composable private fun s7e5dOther() { var a by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(1) } }")))
    }

    /** J1b: each way the page and host seams could break is caught by the same range checks the clean tree passes. */
    @Test
    fun P_theSeamWiringCatchesEachBreak_andLeavesTheRestAlone() {
        val mount = "val pageMount = remember { PreviewPageMount(tab) }"
        val effect = "DisposableEffect(pageMount) {\n        onPage(pageMount, true)\n        onDispose { onPage(pageMount, false) }\n    }"
        for ((label, tree, tag) in listOf(
            Triple("the hand-down to the page cut", screenWith("onPage = onPage", "onPage = { _, _ -> }"), "P-seam-content-call"),
            Triple("the hand-down from the host cut", screenWith("onPage = hooks::onPage", "onPage = { _, _ -> }"), "P-seam-tabs-call"),
            Triple("the mount under a condition", screenWith(mount, "if (visible) { $mount }"), "P-seam-page-mount"),
            Triple("the effect under a braceless condition", screenWith(effect, "if (visible) $effect"), "P-seam-page-effect"),
            Triple("the invalidate under a braceless condition",
                sessionWithAll(INVALIDATE to "if (uid != null) $INVALIDATE"), "P-seam-host-context"),
            Triple("the report under a braceless condition",
                sessionWithAll(CONTEXT_REPORT to "if (uid != null) $CONTEXT_REPORT"), "P-seam-host-context"),
            Triple("the withdrawal under a braceless condition",
                sessionWithAll(CONTEXT_WITHDRAW to "if (uid != null) $CONTEXT_WITHDRAW"), "P-seam-host-context"),
            Triple("the effect inside a lazy item", screenWithAll(effect to "", "item { FreeBankAlertPreviewSection(" to "item { $effect; FreeBankAlertPreviewSection("),
                "P-seam-page-effect"),
            Triple("the mount saved", screenWith(mount, "val pageMount = rememberSaveable { PreviewPageMount(tab) }"), "P-seam-page-mount"),
            Triple("the mount shared", screenWith(mount, "val pageMount = s7e5dSharedMount"), "P-seam-page-mount"),
            Triple("a different mount on dispose", screenWith("onDispose { onPage(pageMount, false) }", "onDispose { onPage(PreviewPageMount(tab), false) }"),
                "P-seam-page-effect"),
            Triple("a no-op hook that does something", screenWith("override fun onEvent(event: String) = Unit", "override fun onEvent(event: String) = s7e5dLog(event)"),
                "P-seam-noop"),
            Triple("the no-op hide not hiding", screenWith("override suspend fun hide(realHide: suspend () -> Unit) = realHide()",
                "override suspend fun hide(realHide: suspend () -> Unit) = Unit"), "P-seam-noop"),
            Triple("the context reported before the invalidate", sessionWithAll(
                "    SideEffect { session.invalidate(uid, activeTab) }\n" to "",
                "    DisposableEffect(hostKey) { onDispose { onContext(null) } }\n" to
                    "    DisposableEffect(hostKey) { onDispose { onContext(null) } }\n    SideEffect { session.invalidate(uid, activeTab) }\n"),
                "P-seam-host-context"),
            Triple("the context not withdrawn", sessionWithAll("    DisposableEffect(hostKey) { onDispose { onContext(null) } }\n" to ""), "P-seam-host-context"),
            Triple("the signal's origin replaced by the current context", sessionWithAll(
                "beforeDeliver(PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token))" to
                    "beforeDeliver(PreviewSignalOrigin(session.identity, session.currentBinding, wanted.token))"), "P-seam-origin")
        )) {
            assertCaught(label, tag, reach(tree))
        }
        assertEquals("화면의 다른 저장·동작·주석은 위반이 아니다", emptyList<String>(), reach(appended(SCREEN,
            "// PreviewPageMount onPage(pageMount, true)\n@androidx.compose.runtime.Composable private fun s7e5dElse() { var b by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(2) } }")))
    }

    private fun screenWithAll(vararg edits: Pair<String, String>): Map<String, String> = withAll(SCREEN, edits.toList())
    private fun sessionWithAll(vararg edits: Pair<String, String>): Map<String, String> = withAll(SESSION, edits.toList())
    private fun withAll(path: String, edits: List<Pair<String, String>>): Map<String, String> {
        var text = code.getValue(path)
        for ((old, new) in edits) {
            assertEquals("mutation premise: '$old' 한 곳", 1, Regex.escape(old).toRegex().findAll(text).count())
            text = text.replace(old, PostS6ApprovedDelta.codeAndStrings(new))
        }
        return code + (path to text)
    }

    private fun screenWith(old: String, new: String): Map<String, String> {
        val text = code.getValue(SCREEN)
        assertEquals("mutation premise: '$old' 한 곳", 1, Regex.escape(old).toRegex().findAll(text).count())
        return code + (SCREEN to text.replace(old, PostS6ApprovedDelta.codeAndStrings(new)))
    }

    /** G19: a third caller, a caller that calls twice, or the premium sheet dropping the shared body all change this map. */
    @Test
    fun W_theFieldsCallerMapCatchesEachChange() {
        val call = "@androidx.compose.runtime.Composable private fun s7e5dExtra() { BankAlertEditorFields() }"
        for ((label, tree) in listOf(
            "a third caller" to appended(CTA, call),
            "the preview calling twice" to appended(SHEET, call),
            "the premium sheet dropping it" to (code + (PREMIUM_SHEET to code.getValue(PREMIUM_SHEET).replace(Regex("""\bBankAlertEditorFields\s*\("""), "s7e5dOther(")))
        )) {
            val got = calls(tree, "BankAlertEditorFields")
            assertTrue("mutation($label): $got", got != mapOf(PREMIUM_SHEET to 1, SHEET to 1))
        }
    }

    private fun calls(tree: Map<String, String>, name: String): Map<String, Int> =
        tree.mapValues { (_, t) -> count(t, """(?<!fun )(?<!\.)\b$name\s*\(""") }.filterValues { it > 0 }

    private fun assertCaught(label: String, tag: String, violations: List<String>) =
        assertTrue("mutation($label): expected $tag, got $violations", violations.any { it == tag || it.startsWith("$tag ") })

    private fun appended(path: String, source: String): Map<String, String> {
        assertTrue("mutation premise: $path is scanned", path in code)
        return code + (path to code.getValue(path) + "\n" + PostS6ApprovedDelta.codeAndStrings(source) + "\n")
    }

    private fun withImport(path: String, line: String): Map<String, String> {
        val text = code.getValue(path)
        val pkg = Regex("""(?m)^package\b[^\n]*\n""").find(text)!!
        return code + (path to text.substring(0, pkg.range.last + 1) + line + "\n" + text.substring(pkg.range.last + 1))
    }

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val SESSION = BASE + "ui/free/FreeAlertPreviewSession.kt"
        val SHEET = BASE + "ui/free/FreeBankAlertPreviewSheet.kt"
        val SECTION = BASE + "ui/free/FreeBankAlertPreviewSection.kt"
        val CTA = BASE + "ui/components/SubscribeCTA.kt"
        val FIELDS = BASE + "ui/alert/BankAlertEditorFields.kt"
        val PREMIUM_SHEET = BASE + "ui/alert/AlertAddSheet.kt"
        val UI_STATE = BASE + "ui/free/FreeSnapshotUiState.kt"
        val SCREEN = BASE + "ui/free/FreeSnapshotScreen.kt"
        /** S7 E5d-J: the no-op hook accessor every shipped build type compiles (`e5dj_design.claude.r3.md` §3.1). */
        val HOOKS_DEFAULT = "src/e5dHooksDefault/java/com/jay/fxi/ui/free/PreviewHostHooksDefault.kt"
        val PREVIEW_FILES = listOf(SESSION, SHEET, SECTION, CTA, FIELDS, HOOKS_DEFAULT)

        /** What the preview may import from the app. Everything else of the app is out of its reach. */
        val ALLOWED_IMPORT_PREFIXES = listOf("com.jay.fxi.domain.model.", "com.jay.fxi.ui.theme.")
        val ALLOWED_IMPORTS = setOf(
            "com.jay.fxi.data.remote.dto.RepeatIntervalChange",
            "com.jay.fxi.ui.alert.BankAlertEditor",
            "com.jay.fxi.ui.alert.BankAlertEditorFields",
            "com.jay.fxi.ui.alert.BankAlertEditorInput",
            "com.jay.fxi.ui.alert.BankAlertEditorMode",
            "com.jay.fxi.ui.alert.BankAlertSheetDragHandle",
            "com.jay.fxi.ui.components.SubscribeCTA",
            "com.jay.fxi.ui.components.BankIcon"
        )
        /**
         * G14: everything else the preview may name, from any package — imported or written out in full. The Compose UI
         * toolkit, but not its saving, its text-field state or its platform hooks (only the test tag); a handful of named types.
         * Anything not here fails, wherever it comes from.
         */
        val ALLOWED_PACKAGES = listOf(
            "androidx.compose.animation.",
            "androidx.compose.foundation.",
            "androidx.compose.material.icons.",
            "androidx.compose.material3.",
            "androidx.compose.runtime.",
            "androidx.compose.ui.",
            "kotlin.math."
        )
        val DENIED_PACKAGES = listOf(
            "androidx.compose.runtime.saveable.",
            "androidx.compose.foundation.text.input.",
            "androidx.compose.ui.platform."
        )
        val ALLOWED_NAMES = ALLOWED_IMPORTS + setOf(
            "androidx.compose.ui.platform.testTag",
            "kotlinx.coroutines.launch",
            "kotlinx.coroutines.CancellationException",
            "kotlinx.coroutines.ensureActive",
            "kotlinx.coroutines.CoroutineScope",
            "kotlinx.datetime.Instant",
            "java.text.NumberFormat",
            "java.util.Locale"
        )
        /** A dotted name rooted at a package — what an import or a fully qualified use names. */
        val QUALIFIED = Regex("""(?<![\w.])(?:java|javax|android|androidx|kotlin|kotlinx|com|org|dagger|okhttp3|retrofit2|okio|io|net|sun)(?:\.[A-Za-z_]\w*)+""")

        fun allowedName(name: String): Boolean =
            ALLOWED_NAMES.any { name == it || name.startsWith("$it.") } ||
                ((ALLOWED_PACKAGES + ALLOWED_IMPORT_PREFIXES).any { name.startsWith(it) } && DENIED_PACKAGES.none { name.startsWith(it) })

        /** Same-package files the preview may lean on without an import: the pure editor model and the editor's own tags. */
        val ALLOWED_NEIGHBOURS = setOf(
            BASE + "ui/alert/BankAlertEditor.kt",
            BASE + "ui/alert/AlertAddSheet.kt",
            BASE + "ui/alert/AlertConditionExtensions.kt",
            BASE + "ui/components/BankIcon.kt"
        )

        val FORBIDDEN = listOf(
            "permission" to """(?i)permission""",
            "push" to """\bPushNotificationManager\b|\bPushRegistration\w*|\bFirebaseMessaging\w*|\bregisterIfNeeded\b|\breconcileRegistration\b""",
            "owner" to """\bBankAlertSettingsOwner\b|\bAlertRepository\b|\bAlertSettingsAccess\b|\bAlertFamily\w*|\bBankAlertSection\b|\bBankAlertSectionSource\w*|\bAlertRateLimitFloor\b""",
            "memory" to """\bLastSelectedBank\w*|\bLastBankSelection\w*|\bacceptAddedBank\b|\bprepareBankSelection\b|\bbankSelection\w*""",
            "history" to """\bBankHistory\w*|\bgetHistory\b|\bgetNotificationLogs\b""",
            "topic" to """\bTopic\w*|\bWebSocket\w*|\bGraphV2\w*|\bPremiumTopic\w*""",
            "persistence" to """\brememberSaveable\b|\bSaver\b|\bmapSaver\b|\blistSaver\b|\bSavedStateHandle\b|\bSaveableStateRegistry\b|\bLocalSaveableStateRegistry\b|\bSavedStateRegistry\w*|\bSaveableStateHolder\b|\bViewModel\b|\bhiltViewModel\b|(?i)datastore|\bSharedPreferences\b|\bgetSharedPreferences\b|\bjava\s*\.\s*io\b|\bFile\b|\bRoom\w*""",
            "network" to """\bRetrofit\b|\bOkHttp\w*|\bHttpURLConnection\b|\bAuthenticatedApi\w*|\bFreeSnapshotService\w*|\bAuthenticatedFreeSnapshotService\b|\bFreeSnapshotScheduler\b""",
            "entitlement" to """\bPremiumAccess\w*|\bSubscriptionManager\b|\bPurchases\b""",
            "system" to """\bContext\b|\bLocalContext\b|\bIntent\b|\bstartActivit\w*""",
            "reflection" to """\bClass\s*\.\s*forName\b|\bjavaClass\b|::\s*class\s*\.\s*java\b|\bgetDeclared\w*|\bgetMethod\b|\bKClass\b""",
            "krx" to """(?i)krx"""
        )
        val EFFECTS = """\b(?:LaunchedEffect|DisposableEffect|SideEffect|rememberCoroutineScope|CoroutineScope|GlobalScope|launch|async|snapshotFlow|produceState|collectAsState\w*|MutableStateFlow|MutableSharedFlow)\b"""
        val SHEET_SIGNATURE = squash("""internal fun FreeBankAlertPreviewSheet(
            sheetState: SheetState,
            currency: SupportedCurrency,
            quotes: Map<String, Double>,
            input: BankAlertEditorInput,
            enabled: Boolean,
            onInput: (BankAlertEditorInput) -> Unit,
            onClose: () -> Unit,
            onSubscribe: () -> Unit
        )""")
        val SCREEN_WIRING = listOf(
            "val hooks = currentPreviewHostHooks()",
            "FreeAlertPreviewHost(uid = state.uid, activeTab = selected, onSubscribe = onSubscribe, hide = hooks::hide, onEvent = hooks::onEvent, beforeDeliver = hooks::beforeDeliver, onContext = hooks::onContext) { openPreview ->",
            // S7 E5d-J J1b: the page's own composition, told to the seams, and the seams handed down to it.
            "onOpenPreview = openPreview, onPage = hooks::onPage)",
            "val pageMount = remember { PreviewPageMount(tab) }",
            "DisposableEffect(pageMount) { onPage(pageMount, true) onDispose { onPage(pageMount, false) } }",
            "onOpenPreview = { onOpenPreview.open(tab, state.previewQuotes, state.previewAsOf) }",
            "if (tab in FREE_BANK_ALERT_PREVIEW_TABS) { item { FreeBankAlertPreviewSection(expanded = previewExpanded, onToggle = { previewExpanded = !previewExpanded }, onOpen = onOpenPreview) } }",
            "var previewExpanded by remember { mutableStateOf(false) }"
        )
        val HOST_WIRING = listOf(
            "val session = remember(uid) { FreeAlertPreviewSession() }",
            "DisposableEffect(session) { onDispose { session.dispose() } }",
            "SideEffect { session.invalidate(uid, activeTab) }",
            // S7 E5d-J J1b: the host's own context, after the invalidate, and withdrawn when it leaves; the signal's origin.
            "val hostKey = remember { Any() }",
            "SideEffect { onContext(PreviewHostContext(hostKey, session.identity, session.currentBinding)) }",
            "DisposableEffect(hostKey) { onDispose { onContext(null) } }",
            "beforeDeliver(PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token))",
            "if (!sheetState.isVisible) session.dismissed(token)?.let(onSignal)",
            "if (session.deliverable(wanted)) {"
        )

        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        private val DECLARATION = Regex(
            """(?m)^(?:@[\w.]+(?:\([^)\n]*\))?\s+)*(?!private\b)(?:(?:internal|public|protected)\s+)?(?:(?:data|sealed|enum|abstract|open|inline|value|annotation|const|fun|suspend)\s+)*(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)"""
        )

        /** The non-private top-level names [text] declares. */
        fun topLevelNames(text: String): Set<String> = DECLARATION.findAll(text).map { it.groupValues[1] }.toSet()

        private fun packageDir(path: String) = path.substringBeforeLast('/') + "/"

        /** Every way the preview files, the fields and the projection reach what D26 keeps at zero. */
        fun reach(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for (path in PREVIEW_FILES + UI_STATE) {
                val text = code[path] ?: return listOf("P-missing $path")
                for ((name, pattern) in FORBIDDEN) if (Regex(pattern).containsMatchIn(text)) v += "P-forbidden $name $path"
            }
            for (path in PREVIEW_FILES) {
                val text = code.getValue(path)
                for (imported in Regex("""(?m)^import\s+([\w.]+)""").findAll(text).map { it.groupValues[1] }) {
                    if (!allowedName(imported)) v += "P-import $imported $path"
                }
                val body = Regex("""(?m)^(?:import|package)\b[^\n]*\n""").replace(text, "\n")
                for (qualified in QUALIFIED.findAll(body).map { it.value }.toSet()) {
                    if (!allowedName(qualified)) v += "P-qualified $qualified $path"
                }
                val words = Regex("""\b[A-Za-z_]\w*\b""").findAll(text).map { it.value }.toSet()
                val dir = packageDir(path)
                for ((other, otherText) in code) {
                    if (other == path || other in PREVIEW_FILES || other in ALLOWED_NEIGHBOURS || packageDir(other) != dir) continue
                    val shared = topLevelNames(otherText) intersect words
                    if (shared.isNotEmpty()) v += "P-neighbour ${other.substringAfterLast('/')} ${shared.sorted()} $path"
                }
            }
            v += screenWiring(code.getValue(SCREEN))
            v += seamWiring(code.getValue(SCREEN), code.getValue(SESSION))
            return v
        }

        /**
         * G12: the Screen's own preview wiring, found by the agreed names and checked like a preview file — the host call's
         * arguments, every opener lambda (the block around `.open(`), the section's item block and the condition that guards
         * it, and the teaser's open flag held by the page ([stateWiring]). In each, nothing forbidden, nothing outside the
         * allowlist, and no call but the one that range exists to make. The Screen's other code — its fullscreen, zoom, row
         * preferences, scheduler — is not this lock's business.
         */
        fun screenWiring(text: String): List<String> {
            val v = mutableListOf<String>()
            val ranges = wiringRanges(text)
            if (ranges.none { it.first == "host-args" } || ranges.none { it.first == "opener" } || ranges.none { it.first == "section" } ||
                ranges.none { it.first == "preview-state" }) {
                return listOf("W-screen-missing ${ranges.map { it.first }}")
            }
            for ((label, body) in ranges) {
                for ((name, pattern) in FORBIDDEN) if (Regex(pattern).containsMatchIn(body)) v += "P-screen-forbidden $name $label"
                for (qualified in QUALIFIED.findAll(body).map { it.value }.toSet()) if (!allowedName(qualified)) v += "P-screen-qualified $qualified $label"
                val allowed = when (label) {
                    "opener" -> setOf("open")
                    "section" -> setOf("FreeBankAlertPreviewSection")
                    "preview-state" -> setOf("remember", "mutableStateOf")
                    else -> emptySet()
                }
                for (call in CALL.findAll(body).map { it.groupValues[1] }.toSet()) {
                    if (call !in allowed && call !in KEYWORDS) v += "P-screen-call $call $label"
                }
            }
            v += openerEscapes(text)
            v += stateWiring(text)
            return v
        }

        private val FLAG_USE = Regex("""(?<![\w.])previewExpanded\b""")

        private const val PAGE_MOUNT = "val pageMount = remember { PreviewPageMount(tab) }"
        private const val PAGE_EFFECT = "DisposableEffect(pageMount) { onPage(pageMount, true) onDispose { onPage(pageMount, false) } }"
        private const val NO_HOOKS = "override suspend fun hide(realHide: suspend () -> Unit) = realHide() " +
            "override fun onEvent(event: String) = Unit " +
            "override suspend fun beforeDeliver(origin: PreviewSignalOrigin) = Unit " +
            "override fun onContext(context: PreviewHostContext?) = Unit " +
            "override fun onPage(mount: PreviewPageMount, composed: Boolean) = Unit"
        private const val INVALIDATE = "SideEffect { session.invalidate(uid, activeTab) }"
        private const val HOST_KEY = "val hostKey = remember { Any() }"
        private const val CONTEXT_REPORT = "SideEffect { onContext(PreviewHostContext(hostKey, session.identity, session.currentBinding)) }"
        private const val CONTEXT_WITHDRAW = "DisposableEffect(hostKey) { onDispose { onContext(null) } }"
        private const val ORIGIN = "beforeDeliver(PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token))"

        /** The body range of the one `fun name(` in [text], whatever receiver it has. */
        private fun funBody(text: String, name: String): IntRange? {
            val head = Regex("""\bfun\s+(?:[\w.]+\.)?$name\s*\(""").find(text) ?: return null
            val params = balanced(text, head.range.last, '(', ')') ?: return null
            return balanced(text, text.indexOf('{', params.last), '{', '}')
        }

        /** The argument text, squashed, of each call to [name] that is not its declaration. */
        private fun callArgs(text: String, name: String): List<String> =
            Regex("""(?<![\w.])$name\s*\(""").findAll(text)
                .filterNot { Regex("""\bfun\s+(?:[\w.]+\.)?$""").containsMatchIn(text.substring(0, it.range.first)) }
                .mapNotNull { m -> balanced(text, m.range.last, '(', ')')?.let { squash(text.substring(it.first + 1, it.last)) } }.toList()

        /** How many times [name] is constructed in [text]: its calls, not its `class` declaration. */
        private fun constructions(text: String, name: String): Int =
            Regex("""(?<![\w.])$name\s*\(""").findAll(text).count { !Regex("""\bclass\s+$""").containsMatchIn(text.substring(0, it.range.first)) }

        /** Where [line] (squashed) sits, if it occurs exactly once directly in [body] before [before]; null otherwise. */
        private fun once(text: String, body: IntRange, line: String, before: Int? = null): Int? {
            val hits = Regex(line.split(" ").joinToString("""\s+""") { Regex.escape(it) }).findAll(text).map { it.range.first }.toList()
            val at = hits.singleOrNull() ?: return null
            if (enclosing(text, at) != body) return null
            if (before != null && at > before) return null
            return at
        }

        /**
         * S7 E5d-J J1b (`s7e5dj_j1b.codex.r1.md` §2.2): the page and host seams, by range and not by the file's word count. The
         * page's report is handed down host → FreeSnapshotTabs → FreeSnapshotTabContent unbroken; one plain-remembered mount per
         * composition sits directly in FreeSnapshotTabContent's body beside previewExpanded, before its LazyColumn, and the same
         * object is reported on mount and on dispose; the no-op hooks do exactly nothing but the real hide once. The host reports
         * its context after its invalidate, both directly in its body, withdraws it with its own lifetime, and hands each signal
         * its own origin, which is built nowhere else.
         */
        fun seamWiring(screen: String, session: String): List<String> {
            val v = mutableListOf<String>()
            if (callArgs(screen, "FreeSnapshotTabs").singleOrNull()?.contains("onPage = hooks::onPage") != true) v += "P-seam-tabs-call"
            val contentCalls = callArgs(screen, "FreeSnapshotTabContent")
            if (contentCalls.size != 1 || Regex("""\bonPage\s*=\s*([^,)]+)""").findAll(contentCalls.single()).map { it.groupValues[1].trim() }.toList() != listOf("onPage")) {
                v += "P-seam-content-call"
            }
            val content = funBody(screen, "FreeSnapshotTabContent")
            val list = content?.let { Regex("""\bLazyColumn\s*\(""").find(screen, it.first)?.range?.first }
            if (content == null || list == null) {
                v += "P-seam-page-missing"
            } else {
                if (once(screen, content, PAGE_MOUNT, before = list) == null) v += "P-seam-page-mount"
                if (once(screen, content, PAGE_EFFECT, before = list) == null ||
                    once(screen, content, "$PAGE_MOUNT $PAGE_EFFECT", before = list) == null) v += "P-seam-page-effect"
                if (Regex("""\bvar\s+previewExpanded\b""").findAll(screen).count() == 1 &&
                    Regex("""\bvar\s+previewExpanded\b""").find(screen)!!.range.first.let { enclosing(screen, it) != content }) v += "P-seam-page-mount"
            }
            if (constructions(screen, "PreviewPageMount") != 1) v += "P-seam-page-mount"
            val noHooks = Regex("""\bobject\s+NoPreviewHostHooks\b[^{]*\{""").find(screen)
                ?.let { balanced(screen, it.range.last, '{', '}') }?.let { squash(screen.substring(it.first + 1, it.last)) }
            if (noHooks != NO_HOOKS) v += "P-seam-noop"
            val host = funBody(session, "FreeAlertPreviewHost")
            if (host == null) {
                v += "P-seam-host-missing"
            } else {
                val setup = listOf(
                    "val session = remember(uid) { FreeAlertPreviewSession() }",
                    "DisposableEffect(session) { onDispose { session.dispose() } }",
                    INVALIDATE, HOST_KEY, CONTEXT_REPORT, CONTEXT_WITHDRAW
                ).joinToString(" ")
                if (once(session, host, setup) == null) v += "P-seam-host-context"
            }
            if (Regex("""(?<![\w.])onContext\s*\(""").findAll(session).count() != 2) v += "P-seam-host-context"
            val origins = Regex(ORIGIN.split(" ").joinToString("""\s+""") { Regex.escape(it) }).findAll(session).count()
            if (origins != 1 || constructions(session, "PreviewSignalOrigin") != 1) v += "P-seam-origin"
            return v.distinct()
        }

        /**
         * The teaser's open flag (`e5d_devfix_plan.claude.r3.md` X1·X6): declared once, directly in FreeSnapshotTabContent's own
         * body before its LazyColumn and under no condition; used only as the section's `expanded` and inside its `onToggle`,
         * which flips it and does nothing else — no getter, no wrapper, nothing handed on.
         */
        fun stateWiring(text: String): List<String> {
            val decls = Regex("""\bvar\s+previewExpanded\b""").findAll(text).toList()
            if (decls.size != 1) return listOf("P-screen-state-decl ${decls.size}")
            val decl = decls.single().range
            val head = Regex("""\bfun\s+FreeSnapshotTabContent\s*\(""").find(text) ?: return listOf("P-screen-state-missing content")
            val params = balanced(text, head.range.last, '(', ')') ?: return listOf("P-screen-state-missing params")
            val body = balanced(text, text.indexOf('{', params.last), '{', '}') ?: return listOf("P-screen-state-missing body")
            val list = Regex("""\bLazyColumn\s*\(""").find(text, body.first)?.range?.first
            val v = mutableListOf<String>()
            if (enclosing(text, decl.first) != body || list == null || list > body.last || decl.first > list) v += "P-screen-state-place previewExpanded"
            val calls = Regex("""\bFreeBankAlertPreviewSection\s*\(""").findAll(text).mapNotNull { balanced(text, it.range.last, '(', ')') }.toList()
            for (use in FLAG_USE.findAll(text)) {
                if (use.range.first in decl) continue
                if (calls.none { use.range.first in it }) v += "P-screen-state-escape previewExpanded"
            }
            for (args in calls) {
                val a = squash(text.substring(args.first + 1, args.last))
                if (FLAG_USE.findAll(a).count() != 3 || "expanded = previewExpanded," !in a || "onToggle = { previewExpanded = !previewExpanded }" !in a) {
                    v += "P-screen-state-args"
                }
            }
            return v.distinct()
        }

        private val CALL = Regex("""(?<![\w])([A-Za-z_]\w*)\s*(?:<[^<>()]*>\s*)?\(""")
        private val KEYWORDS = setOf("if", "when", "for", "while", "return")

        /** The wiring ranges of [text], each a label and the code inside it. */
        fun wiringRanges(text: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            for (m in Regex("""\bFreeAlertPreviewHost\s*\(""").findAll(text)) {
                val args = balanced(text, m.range.last, '(', ')') ?: continue
                out += "host-args" to text.substring(args.first + 1, args.last)
            }
            for (m in Regex("""\.\s*open\s*\(""").findAll(text)) {
                val block = enclosing(text, m.range.first) ?: continue
                out += "opener" to text.substring(block.first + 1, block.last)
            }
            for (m in Regex("""\bvar\s+previewExpanded\b""").findAll(text)) {
                val open = text.indexOf('{', m.range.last)
                val block = (if (open < 0) null else balanced(text, open, '{', '}')) ?: continue
                out += "preview-state" to text.substring(m.range.first, block.last + 1)
            }
            for (m in Regex("""\bFreeBankAlertPreviewSection\s*\(""").findAll(text)) {
                val item = enclosing(text, m.range.first) ?: continue
                out += "section" to text.substring(item.first + 1, item.last)
                val guard = enclosing(text, item.first) ?: continue
                val head = Regex("""\bif\s*\(""").findAll(text.substring(0, guard.first)).lastOrNull() ?: continue
                val cond = balanced(text, head.range.last, '(', ')') ?: continue
                if (text.substring(cond.last + 1, guard.first).isBlank()) out += "section-guard" to text.substring(cond.first + 1, cond.last)
            }
            return out
        }

        /**
         * Every name the Screen holds an opener under — the host lambda's parameter and each `FreeAlertPreviewOpener` parameter —
         * is only called through `.open(` or handed on bare as an argument: it is never stored, wrapped or given to a helper.
         */
        fun openerEscapes(text: String): List<String> {
            val names = mutableSetOf<String>()
            for (m in Regex("""\bFreeAlertPreviewHost\s*\(""").findAll(text)) {
                val args = balanced(text, m.range.last, '(', ')') ?: continue
                Regex("""^\s*\{\s*([A-Za-z_]\w*)\s*->""").find(text.substring(args.last + 1))?.let { names += it.groupValues[1] }
            }
            Regex("""\b([A-Za-z_]\w*)\s*:\s*FreeAlertPreviewOpener\b""").findAll(text).forEach { names += it.groupValues[1] }
            val v = mutableListOf<String>()
            for (name in names) {
                for (use in Regex("""(?<![\w.])$name\b""").findAll(text)) {
                    val after = text.substring(use.range.last + 1)
                    val before = text.substring(0, use.range.first)
                    val declared = Regex("""^\s*(?::|->)""").containsMatchIn(after)
                    val called = Regex("""^\s*\.\s*open\s*\(""").containsMatchIn(after)
                    val handedOn = Regex("""=\s*$""").containsMatchIn(before) && Regex("""^\s*[,)]""").containsMatchIn(after) &&
                        !Regex("""(?:val|var)\s+\w+\s*=\s*$""").containsMatchIn(before)
                    val labelled = Regex("""^\s*=(?!=)""").containsMatchIn(after) && Regex("""[(,]\s*$""").containsMatchIn(before)
                    if (!declared && !called && !handedOn && !labelled) v += "P-screen-opener-escape $name"
                }
            }
            return v
        }

        /** The index range of the bracket pair opening at [at]. */
        fun balanced(text: String, at: Int, open: Char, close: Char): IntRange? {
            if (text.getOrNull(at) != open) return null
            var depth = 0
            for (i in at until text.length) {
                if (text[i] == open) depth++
                if (text[i] == close && --depth == 0) return at..i
            }
            return null
        }

        /** The innermost `{…}` around [index]. */
        fun enclosing(text: String, index: Int): IntRange? {
            var depth = 0
            for (i in index - 1 downTo 0) {
                when (text[i]) {
                    '}' -> depth++
                    '{' -> if (depth == 0) return balanced(text, i, '{', '}') else depth--
                }
            }
            return null
        }

        fun squash(text: String): String = Regex("""\s+""").replace(text, " ").replace("( ", "(").replace(" )", ")").trim()
        fun flat(path: String): String = squash(code.getValue(path))
        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()
    }
}
