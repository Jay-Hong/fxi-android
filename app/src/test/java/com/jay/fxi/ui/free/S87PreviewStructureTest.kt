package com.jay.fxi.ui.free

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §4): where the tether free preview is mounted and how the screen hands it its
 * callbacks. Read from the comment-free code of the production sources, with string literals blanked, so a call written in a
 * comment or a string is never counted.
 */
class S87PreviewStructureTest {

    /**
     * The one source preview section sits in a lazy item directly under the selected-tether guard of `FreeSnapshotTabContent`.
     * The extractor first proves itself on the bank section the base already has, and on calls it must not count.
     */
    @Test
    fun R01_oneSectionInSelectedTetherItem() {
        val screen = blankStrings(code.getValue(SCREEN))
        val content = funBody(screen, "FreeSnapshotTabContent")
        assertEquals("premise: FreeSnapshotTabContent 본문", true, content != null)
        assertEquals("premise: 은행 section 은 같은 추출기로 한 곳", 1,
            guardedItemCalls(screen, content!!, "FreeBankAlertPreviewSection", "tab in FREE_BANK_ALERT_PREVIEW_TABS"))
        for ((label, fixture) in listOf(
            "문자열 안의 호출" to "fun FreeSnapshotTabContent() { if (tab == FreeTab.TETHER && isSelectedPage) { item { val s = \"FreeSourceAlertPreviewSection()\" } } }",
            "주석 안의 호출" to "fun FreeSnapshotTabContent() { if (tab == FreeTab.TETHER && isSelectedPage) { item { // FreeSourceAlertPreviewSection()\n } } }",
            "다른 함수의 호출" to "fun Other() { if (tab == FreeTab.TETHER && isSelectedPage) { item { FreeSourceAlertPreviewSection() } } } fun FreeSnapshotTabContent() { }",
            "guard 밖 호출" to "fun FreeSnapshotTabContent() { item { FreeSourceAlertPreviewSection() } }",
            "다른 guard 의 호출" to "fun FreeSnapshotTabContent() { if (tab == FreeTab.TETHER) { item { FreeSourceAlertPreviewSection() } } }"
        )) {
            val text = blankStrings(PostS6ApprovedDelta.codeAndStrings(fixture))
            val body = funBody(text, "FreeSnapshotTabContent")
            assertEquals("premise: 추출기는 $label 을 세지 않는다", 0,
                body?.let { guardedItemCalls(text, it, "FreeSourceAlertPreviewSection", TETHER_GUARD) } ?: 0)
        }
        val positive = blankStrings(PostS6ApprovedDelta.codeAndStrings(
            "fun FreeSnapshotTabContent() { if (tab == FreeTab.TETHER && isSelectedPage) { item { FreeSourceAlertPreviewSection(expanded = x) } } }"))
        assertEquals("premise: 추출기는 선택 테더 guard 안 item 의 호출을 센다", 1,
            guardedItemCalls(positive, funBody(positive, "FreeSnapshotTabContent")!!, "FreeSourceAlertPreviewSection", TETHER_GUARD))
        val actual = guardedItemCalls(screen, content, "FreeSourceAlertPreviewSection", TETHER_GUARD)
        if (actual != 1) throw AssertionError("테더 페이지의 source preview section 수: expected 1, actual $actual")
    }

    /**
     * Every source preview callback on the screen comes from where it is declared: the host gets the screen's own owner,
     * selection and paywall and no seam; its opener is handed on bare once; the pager calls it once, on the selected page, with
     * that page's own pair; the page hands it to the section alone; the teaser's flag is plainly remembered and only flipped.
     */
    @Test
    fun W01_screenCallbacksHaveOnlyTheirDeclaredOrigins() {
        if (screenProblems(code.getValue(SCREEN)).isNotEmpty()) throw AssertionError("화면 콜백의 기원")
        for ((label, old, new) in listOf(
            Triple("a wrapped paywall", "onSubscribe = onSubscribe) { openSourcePreview ->", "onSubscribe = { s87Log(); onSubscribe() }) { openSourcePreview ->"),
            Triple("a seam on the source host", "onSubscribe = onSubscribe) { openSourcePreview ->", "onSubscribe = onSubscribe, onEvent = hooks::onEvent) { openSourcePreview ->"),
            Triple("the opener stored", "{ openSourcePreview ->", "{ openSourcePreview -> val s87Held = openSourcePreview;"),
            Triple("an inactive page opening", "{ if (tab == selected) onOpenSourcePreview.open(", "{ if (true) onOpenSourcePreview.open("),
            Triple("quotes made up on the way", "state.sourcePreviewQuotes, state.sourcePreviewAsOf)", "state.sourcePreviewQuotes.plus(\"upbit\" to 1.0), state.sourcePreviewAsOf)"),
            Triple("a saved flag", "var sourcePreviewExpanded by remember { mutableStateOf(false) }", "var sourcePreviewExpanded by rememberSaveable { mutableStateOf(false) }"),
            Triple("a section handed something else", "onOpen = onOpenSourcePreview) }", "onOpen = { s87Log(); onOpenSourcePreview() }) }")
        )) {
            val text = code.getValue(SCREEN)
            if (Regex.escape(old).toRegex().findAll(text).count() != 1) throw AssertionError("mutation premise: '$old' 한 곳")
            val got = screenProblems(text.replace(old, PostS6ApprovedDelta.codeAndStrings(new)))
            if (got.isEmpty()) throw AssertionError("mutation($label) 미검출")
        }
        if (screenProblems(code.getValue(SCREEN)).isNotEmpty()) throw AssertionError("화면 콜백의 기원")
    }

    /**
     * The shared draft rules have two callers, the premium editor and the free preview, and the free preview's files reach none
     * of what D26 keeps at zero — not by name, alias, qualified name or string. Each of the three preview sheets calls the CTA once.
     */
    @Test
    fun W02_rulesHaveTwoPureCallers() {
        if (ruleProblems(code).isNotEmpty()) throw AssertionError("공유 규칙과 무료 진입 경계")
        for ((label, path, line) in listOf(
            Triple("an aliased editor", SESSION, "import com.jay.fxi.ui.alert.SourceAlertEditor as S87Rules"),
            Triple("the remembered source by qualified name", SHEET, "private val s87M: com.jay.fxi.data.local.LastSelectedSourceMemory? = null"),
            Triple("the owner by string", QUOTES, "private val s87N = \"SourceAlertSettingsOwner\""),
            Triple("a saveable draft", SECTION, "@androidx.compose.runtime.Composable private fun s87S() { androidx.compose.runtime.saveable.rememberSaveable { 1 } }"),
            Triple("the permission helper", SHEET, "private val s87P: NotificationPermissionEnvironment? = null"),
            Triple("KRX in a string", QUOTES, "private val s87K = \"krx\""),
            Triple("a third rules caller", SECTION, "private val s87R = com.jay.fxi.ui.alert.SourceAlertDraftRules.formatThreshold(1.0)"),
            Triple("a premium seed that skips the rules", EDITOR, "private val s87E = 1")
        )) {
            val tree = if (path == EDITOR) code + (EDITOR to code.getValue(EDITOR).replace("SourceAlertDraftRules.seed(sources, quotes, remembered)", "s87Seed()"))
                else code + (path to code.getValue(path) + "\n" + PostS6ApprovedDelta.codeAndStrings(line) + "\n")
            if (ruleProblems(tree).isEmpty()) throw AssertionError("mutation($label) 미검출")
        }
        if (ruleProblems(code).isNotEmpty()) throw AssertionError("공유 규칙과 무료 진입 경계")
    }

    internal companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val SCREEN = BASE + "ui/free/FreeSnapshotScreen.kt"
        val EDITOR = BASE + "ui/alert/SourceAlertEditor.kt"
        val RULES = BASE + "ui/alert/SourceAlertDraftRules.kt"
        val QUOTES = BASE + "ui/free/FreeSourceAlertPreviewQuotes.kt"
        val SESSION = BASE + "ui/free/FreeSourceAlertPreviewSession.kt"
        val SECTION = BASE + "ui/free/FreeSourceAlertPreviewSection.kt"
        val SHEET = BASE + "ui/free/FreeSourceAlertPreviewSheet.kt"
        val BANK_SHEET = BASE + "ui/free/FreeBankAlertPreviewSheet.kt"
        val FREE_FILES = listOf(RULES, QUOTES, SESSION, SECTION, SHEET)
        const val TETHER_GUARD = "tab == FreeTab.TETHER && isSelectedPage"

        /** What the free tether preview never names, from comment-free code with its strings kept. */
        val FORBIDDEN = listOf(
            "editor" to """\bSourceAlertEditor\b|\bSourceAlertEditorSheet\b|\bSourceAlertSection\w*|\bSourceAlertSubmit\b|\bSourceAlertDraft\b|\bSourceAlertEdit\b""",
            "owner" to """\bSourceAlertSettingsOwner\b|\bAlertRepository\b|\bAlertSettingsAccess\b|\bAlertFamily\w*|\bSourceAlertSaveOutcome\b""",
            "memory" to """\bLastSelectedSource\w*|\bacceptAddedSource\b|\bprepareSourceSelection\b|\bsourceSelection\w*""",
            "history" to """\bSourceHistory\w*|\bgetHistory\b|\bgetNotificationLogs\b""",
            "permission" to """(?i)permission""",
            "push" to """\bPushNotificationManager\b|\bPushRegistration\w*|\bFirebaseMessaging\w*|\bNotificationManager\w*|\bnotify\s*\(""",
            "persistence" to """\brememberSaveable\b|\bSaver\b|\bmapSaver\b|\blistSaver\b|\bSavedStateHandle\b|\bSaveableStateRegistry\b|\bSaveableStateHolder\b|\bViewModel\b|\bhiltViewModel\b|(?i)datastore|\bSharedPreferences\b|\bFile\b|\bRoom\w*""",
            "network" to """\bRetrofit\b|\bOkHttp\w*|\bHttpURLConnection\b|\bAuthenticatedApi\w*|\bFreeSnapshotService\w*|\bFreeSnapshotScheduler\b""",
            "entitlement" to """\bPremiumAccess\w*|\bSubscriptionManager\b|\bPurchases\b""",
            "system" to """\bContext\b|\bLocalContext\b|\bIntent\b|\bstartActivit\w*""",
            "reflection" to """\bClass\s*\.\s*forName\b|\bjavaClass\b|::\s*class\s*\.\s*java\b|\bKClass\b""",
            "krx" to """(?i)krx"""
        )

        /** Everything wrong with the shared rules' callers and the free files' reach in [tree]; empty when it holds. */
        fun ruleProblems(tree: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            for (path in FREE_FILES) {
                val text = tree[path] ?: return listOf("missing $path")
                for ((name, pattern) in FORBIDDEN) if (Regex(pattern).containsMatchIn(text)) v += "forbidden $name $path"
            }
            val callers = tree.filterKeys { it != RULES }.mapValues { (_, t) -> Regex("""\bSourceAlertDraftRules\s*\.""").findAll(t).count() }.filterValues { it > 0 }.keys
            if (callers != setOf(EDITOR, SESSION, SHEET)) v += "rules callers $callers"
            val editor = squash(tree.getValue(EDITOR))
            for (line in listOf(
                "SourceAlertEditorMode.Add -> SourceAlertDraftRules.seed(sources, quotes, remembered)",
                "if (mode == SourceAlertEditorMode.Add) SourceAlertDraftRules.pick(input, source, quotes) else input.copy(source = source)",
                "fun range(quote: Double?): ClosedFloatingPointRange<Double>? = SourceAlertDraftRules.range(quote)",
                "fun formatThreshold(value: Double): String = SourceAlertDraftRules.formatThreshold(value)",
                "private fun usableQuote(quote: Double?): Double? = SourceAlertDraftRules.usableQuote(quote)",
                "SourceAlertDraftRules.addRepeatDescription(null)",
                "SourceAlertDraftRules.addRepeatDescription(interval)"
            )) if (editor.split(line).size != 2) v += "editor delegation '$line'"
            if (squash(tree.getValue(SESSION)).split("SourceAlertDraftRules.seed(SOURCES, frozen, null)").size != 2) v += "free seed"
            if (squash(tree.getValue(SHEET)).split("SourceAlertDraftRules.pick(input, code, quotes)").size != 2) v += "free pick"
            val sheet = squash(tree.getValue(SHEET))
            for (call in listOf("SourceAlertDraftRules.addRepeatDescription(chosen)", "SourceAlertDraftRules.range(quote)",
                "SourceAlertDraftRules.formatThreshold(it)", "SourceAlertDraftRules.formatThreshold(it.start)",
                "SourceAlertDraftRules.formatThreshold(it.endInclusive)", "SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)"))
                if (sheet.split(call).size != 2) v += "sheet delegation '$call'"
            if ("SourceAlertRowPolicy.EXCHANGES.admits(entry.source, entry.asset)" !in squash(tree.getValue(QUOTES))) v += "quotes policy"
            if ("SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)" !in squash(tree.getValue(SESSION))) v += "seed policy"
            val cta = tree.mapValues { (_, t) -> Regex("""(?<!fun )(?<!\.)\bSubscribeCTA\s*\(""").findAll(t).count() }.filterValues { it > 0 }
            if (cta != mapOf(BANK_SHEET to 1, SHEET to 1, BASE + "ui/free/FreeComparisonAlertPreviewSheet.kt" to 1)) v += "cta $cta"
            return v
        }

        /** Everything wrong with the screen's source preview wiring in [text]; empty when it holds. */
        fun screenProblems(raw: String): List<String> {
            val text = blankStrings(raw)
            val v = mutableListOf<String>()
            val hosts = Regex("""(?<![\w.])FreeSourceAlertPreviewHost\s*\(""").findAll(text).toList()
            if (hosts.size != 1) return listOf("host calls ${hosts.size}")
            val args = balanced(text, hosts.single().range.last, '(', ')') ?: return listOf("host args")
            if (squash(raw.substring(args.first + 1, args.last)) != "uid = state.uid, activeTab = selected, onSubscribe = onSubscribe") v += "host args"
            val lambda = Regex("""^\s*\{\s*([A-Za-z_]\w*)\s*->""").find(text.substring(args.last + 1))?.groupValues?.get(1)
            if (lambda != "openSourcePreview") v += "host lambda $lambda"
            val uses = Regex("""(?<![\w.])openSourcePreview\b""").findAll(text).count()
            if (uses != 2 || squash(raw).split("onOpenSourcePreview = openSourcePreview,").size != 2) v += "host opener uses $uses"
            val tabs = funBody(text, "FreeSnapshotTabs")?.let { squash(text.substring(it)) }.orEmpty()
            if ("FreeTab.NEWS -> FreeNewsTab(isVisible = pagerState.settledPage == page) else -> FreeSnapshotTabContent(" !in tabs ||
                "state = if (tab == selected) state else FreeSnapshotUiState(uid = state.uid), isSelectedPage = tab == selected," !in tabs) v += "page branch"
            val opener = "onOpenSourcePreview = { if (tab == selected) onOpenSourcePreview.open(tab, state.sourcePreviewQuotes, state.sourcePreviewAsOf) },"
            if (squash(raw).split(opener).size != 2) v += "pager opener"
            if (Regex("""\bonOpenSourcePreview\s*\.\s*open\s*\(""").findAll(text).count() != 1) v += "opener calls"
            val screenBody = funBody(text, "FreeSnapshotScreen")?.let { squash(text.substring(it)) }.orEmpty()
            val pageBody = funBody(text, "FreeSnapshotTabContent")?.let { squash(text.substring(it)) }.orEmpty()
            val subscribe = Regex("""\bonSubscribe\b""")
            if (subscribe.findAll(screenBody).count() != 8 || subscribe.findAll(tabs).count() != 2 ||
                subscribe.findAll(pageBody).count() != 1 || "Button(onClick = onSubscribe," !in pageBody ||
                "FreeSourceAlertPreviewHost(" !in screenBody || opener !in tabs) v += "subscribe origin"
            if (Regex("""\bonSubscribe\b""").findAll(text).count() != 14) v += "extra subscribe use"
            if (Regex("""\.\s*open\s*\(""").findAll(text).count() != 3 ||
                Regex("""\bonOpenComparisonPreview\s*\.\s*open\s*\(""").findAll(text).count() != 1) v += "open calls"
            val section = "FreeSourceAlertPreviewSection(expanded = sourcePreviewExpanded, onToggle = { sourcePreviewExpanded = !sourcePreviewExpanded }, onOpen = onOpenSourcePreview)"
            if (squash(raw).split(section).size != 2 || Regex("""(?<![\w.])FreeSourceAlertPreviewSection\s*\(""").findAll(text).count() != 1) v += "section call"
            if (squash(raw).split("var sourcePreviewExpanded by remember { mutableStateOf(false) }").size != 2) v += "flag"
            if (Regex("""(?<![\w.])sourcePreviewExpanded\b""").findAll(text).count() != 4) v += "flag uses"
            if (Regex("""(?<![\w.])onOpenSourcePreview\b""").findAll(text).count() != 6) v += "page callback uses"
            return v
        }

        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        /** [text] with every string literal's content replaced by spaces of the same length; the quotes stay. */
        fun blankStrings(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text.startsWith("\"\"\"", i)) {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length - 3 else it }
                    out.append("\"\"\"").append(" ".repeat(end - i - 3)).append("\"\"\"")
                    i = end + 3
                } else if (text[i] == '"') {
                    var j = i + 1
                    while (j < text.length && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    val end = minOf(j, text.length - 1)
                    out.append('"').append(" ".repeat(end - i - 1)).append(if (end < text.length && text[end] == '"') "\"" else " ")
                    i = end + 1
                } else {
                    out.append(text[i])
                    i++
                }
            }
            return out.toString()
        }

        /** The body range of the one `fun name(` in [text], whatever receiver it has. */
        fun funBody(text: String, name: String): IntRange? {
            val head = Regex("""\bfun\s+(?:[\w.]+\.)?$name\s*\(""").find(text) ?: return null
            val params = balanced(text, head.range.last, '(', ')') ?: return null
            val open = text.indexOf('{', params.last)
            return if (open < 0) null else balanced(text, open, '{', '}')
        }

        /**
         * How many calls to [callee] sit in a lazy `item { … }` block whose enclosing block is directly the body of an
         * `if (guard)` with exactly [guard] as its condition, all inside [body].
         */
        fun guardedItemCalls(text: String, body: IntRange, callee: String, guard: String): Int =
            Regex("""(?<![\w.])$callee\s*\(""").findAll(text).count { call ->
                if (call.range.first !in body) return@count false
                val item = enclosing(text, call.range.first) ?: return@count false
                if (!Regex("""\bitem\s*$""").containsMatchIn(text.substring(0, item.first))) return@count false
                val block = enclosing(text, item.first) ?: return@count false
                val head = Regex("""\bif\s*\(""").findAll(text.substring(0, block.first)).lastOrNull() ?: return@count false
                val cond = balanced(text, head.range.last, '(', ')') ?: return@count false
                text.substring(cond.last + 1, block.first).isBlank() && squash(text.substring(cond.first + 1, cond.last)) == guard
            }

        fun balanced(text: String, at: Int, open: Char, close: Char): IntRange? {
            if (text.getOrNull(at) != open) return null
            var depth = 0
            for (i in at until text.length) {
                if (text[i] == open) depth++
                if (text[i] == close && --depth == 0) return at..i
            }
            return null
        }

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

        fun squash(text: String): String = Regex("""\s+""").replace(text, " ").trim()
    }
}
