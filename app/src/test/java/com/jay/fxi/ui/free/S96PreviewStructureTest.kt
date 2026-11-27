package com.jay.fxi.ui.free

import com.jay.fxi.PostS6ApprovedDelta
import org.junit.Test

class S96PreviewStructureTest {
    private val scan = S87PreviewStructureTest
    private val code get() = scan.code
    private val screen get() = code.getValue(scan.SCREEN)
    private val source = "FreeSourceAlertPreviewSection"
    private val bank = "FreeBankAlertPreviewSection"
    private val comparison = "FreeComparisonAlertPreviewSection"
    private val fx = "tab in FREE_BANK_ALERT_PREVIEW_TABS"
    private val tether = scan.TETHER_GUARD

    private fun expect(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message\nexpected=$expected actual=$actual")
    }
    private fun items(raw: String, guard: String): List<String> {
        val text = scan.blankStrings(PostS6ApprovedDelta.codeAndStrings(raw))
        val body = scan.funBody(text, "FreeSnapshotTabContent") ?: return emptyList()
        val start = text.indexOf("if ($guard) {", body.first)
        if (start !in body || text.indexOf("if ($guard) {", start + 1) in body) return emptyList()
        val block = scan.balanced(text, text.indexOf('{', start), '{', '}') ?: return emptyList()
        return Regex("""\bitem\s*\{""").findAll(text, block.first).takeWhile { it.range.last < block.last }
            .filter { scan.enclosing(text, it.range.first) == block }.map { item ->
                val s = text.substring(scan.balanced(text, item.range.last, '{','}')!!).drop(1).trimStart()
                when {
                    s.startsWith("$bank(") -> "bank"
                    s.startsWith("$source(") -> "source"
                    s.startsWith("$comparison(tab = tab, kind = COMPARISON_PREVIEW_ABSOLUTE,") -> "absolute"
                    s.startsWith("$comparison(tab = tab, kind = COMPARISON_PREVIEW_SIGNED,") -> "signed"
                    else -> "other"
                }
            }.toList()
    }
    @Test fun R01() {
        val fxItems = items(screen,fx)
        val tetherItems = items(screen,tether)
        expect("premise: 은행",1,fxItems.count { it == "bank" })
        expect("premise: 거래소",1,tetherItems.count { it == "source" })
        val guard = "if ($fx) { item { $bank() } }"
        for (fake in listOf("// $guard\n", "val lure = \"$guard\"", "item { $bank() }", "$guard $guard"))
            expect("premise: 중복 또는 guard 밖",emptyList<String>(),items("fun Other() { $guard } fun FreeSnapshotTabContent() { $fake }",fx))
        expect("신규 직접 item 수",listOf(1,1,1),listOf(fxItems.count { it == "absolute" },tetherItems.count { it == "signed" },tetherItems.count { it == "absolute" }))
        expect("FX 진입점 순서",listOf("bank","absolute"),fxItems)
        expect("테더 진입점 순서",listOf("source","signed","absolute"),tetherItems)
        val visible = screen.indexOf("if (visible) {")
        val end = scan.balanced(scan.blankStrings(screen),screen.indexOf('{',visible),'{','}')!!.last
        for (g in listOf(fx,tether)) expect("visible 밖, 구독 앞",true,screen.indexOf("if ($g) {") in end until screen.indexOf("Button(onClick = onSubscribe,"))
    }
    private val openCall = "onOpenComparisonPreview = { kind -> if (tab == selected) onOpenComparisonPreview.open(tab, kind, state.comparisonPreviewQuotes, state.comparisonPreviewAsOf) },"
    private val host = "FreeComparisonAlertPreviewHost(uid = state.uid, activeTab = selected, onSubscribe = onSubscribe) { openComparisonPreview ->"
    private fun origins(text: String): List<String> = buildList {
        for ((tag, literal) in listOf("host" to host, "pager" to openCall, "handoff" to "onOpenComparisonPreview = openComparisonPreview,"))
            if (scan.squash(text).split(literal).size != 2) add(tag)
        if (Regex("""\bonOpenComparisonPreview\.open\(""").findAll(text).count() != 1) add("receiver")
        if (Regex("""\bonOpenComparisonPreview\b""").findAll(text).count() != 8 ||
            Regex("""\bopenComparisonPreview\b""").findAll(text).count() != 2) add("escape")
        val page = scan.funBody(text,"FreeSnapshotTabs")?.let { scan.squash(text.substring(it)) }.orEmpty()
        if ("FreeTab.NEWS -> FreeNewsTab(isVisible = pagerState.settledPage == page) else -> FreeSnapshotTabContent(" !in page ||
            "state = if (tab == selected) state else FreeSnapshotUiState(uid = state.uid), isSelectedPage = tab == selected," !in page) add("page")
        for ((flag, count) in listOf("comparisonExpanded" to 7, "kimchiExpanded" to 4)) {
            val decl = "var $flag by remember { mutableStateOf(false) }"
            val body = scan.funBody(text,"FreeSnapshotTabContent")
            if (Regex("""\b$flag\b""").findAll(text).count() != count || text.indexOf(decl) < 0 ||
                scan.enclosing(text,text.indexOf(decl)) != body) add(flag)
        }
    }
    private fun toggleProblems(raw: String): List<String> {
        val text = scan.blankStrings(raw)
        val body = scan.funBody(text, "FreeSnapshotTabContent") ?: return listOf("FreeSnapshotTabContent")
        val page = scan.squash(text.substring(body))
        return buildList {
            for ((kind, flag, expected) in listOf(
                Triple("ABSOLUTE", "comparisonExpanded", 2), Triple("SIGNED", "kimchiExpanded", 1)
            )) {
                val section = "$comparison(tab = tab, kind = COMPARISON_PREVIEW_$kind, expanded = $flag, " +
                    "onToggle = { $flag = !$flag }, onOpen = onOpenComparisonPreview)"
                if (page.split(section).size - 1 != expected) add(flag)
            }
        }
    }

    @Test fun W01() {
        expect("선택 탭 opener의 기원",emptyList<String>(),origins(screen))
        for ((before,after,tag) in listOf(Triple(host,host.replace("onSubscribe = onSubscribe","onSubscribe = { onSubscribe() }"),"host"),
            Triple(openCall,openCall.replace("if (tab == selected)",""),"pager"),
            Triple(openCall,openCall.replace("state.comparisonPreviewQuotes","emptyMap()"),"pager"),
            Triple(openCall,openCall.replace("kind ->","kind -> store();"),"pager")))
            expect("결선 양성 $tag",listOf(tag),origins(screen.replace(before,after)))
        expect("펼침 콜백 본문", emptyList<String>(), toggleProblems(screen))
        for (flag in listOf("comparisonExpanded", "kimchiExpanded")) {
            val toggle = "$flag = !$flag"
            val extraCall = screen.replaceFirst(toggle, "$toggle; probe()")
            expect("펼침 콜백 추가 호출 양성", listOf(flag), toggleProblems(extraCall))
        }
        val session = code.getValue(scan.BASE + "ui/free/FreeComparisonAlertPreviewSession.kt")
        expect("host 재검사",true,"tab == activeTab" in session && "binding != currentBinding || !current(uid, tab)" in session)
    }
    private fun footerCount(raw: String): Int {
        val text = scan.blankStrings(raw)
        val body = scan.funBody(text, "FreeComparisonAlertPreviewSheet") ?: return 0
        val sheet = text.substring(body)
        val columns = mutableMapOf<IntRange, String>()
        for (call in Regex("""\bColumn\s*\(""").findAll(sheet)) {
            val args = scan.balanced(sheet, call.range.last, '(', ')') ?: continue
            val brace = sheet.indexOfFirstNonSpace(args.last + 1)
            val column = scan.balanced(sheet, brace, '{', '}') ?: continue
            columns[column] = sheet.substring(args)
        }

        val fields = Regex("""\bComparisonAlertEditorFields\s*\(""").findAll(sheet).singleOrNull() ?: return 0
        val scroll = scan.enclosing(sheet, fields.range.first) ?: return 0
        if (!Regex("""\bverticalScroll\s*\(""").containsMatchIn(columns[scroll].orEmpty())) return 0
        val outer = scan.enclosing(sheet, scroll.first) ?: return 0
        if (outer !in columns) return 0

        // Walk sibling calls after the scrolling Column. Skip each call's arguments and trailing lambda.
        val sibling = Regex("""(\w+)\s*\(""")
        var next = sheet.indexOfFirstNonSpace(scroll.last + 1)
        var count = 0
        while (next < outer.last) {
            val call = sibling.matchAt(sheet, next) ?: return 0
            val args = scan.balanced(sheet, call.range.last, '(', ')') ?: return 0
            if (call.groupValues[1] == "SubscribeCTA" &&
                Regex("""^\(\s*onClick\s*=\s*onSubscribe\s*[,)]""").containsMatchIn(sheet.substring(args))) count++
            next = sheet.indexOfFirstNonSpace(args.last + 1)
            if (sheet.getOrNull(next) == '{') {
                val block = scan.balanced(sheet, next, '{', '}') ?: return 0
                next = sheet.indexOfFirstNonSpace(block.last + 1)
            }
        }
        return count
    }

    private fun String.indexOfFirstNonSpace(start: Int): Int {
        var index = start
        while (index < length && this[index].isWhitespace()) index++
        return index
    }

    @Test fun W02() {
        val path = scan.BASE + "ui/free/FreeComparisonAlertPreviewSheet.kt"
        val sheet = code.getValue(path)
        fun calls(name: String) = Regex("""(?<!fun )\b$name\s*\(""")
        expect("무료 footer CTA 호출 수", 1, footerCount(sheet))
        val footer = "SubscribeCTA(onClick = onSubscribe, modifier = Modifier.padding(vertical = 12.dp))"
        val freeCall = "ComparisonAlertEditorFields(fields, quotes, enabled, change, message = null)"
        val withoutFooter = sheet.replace(footer, "")
        val inOtherFunction = withoutFooter + "\nfun Other(onSubscribe: () -> Unit) { $footer }"
        val inScroll = withoutFooter.replace(freeCall, "$freeCall\n$footer")
        for (moved in listOf(inOtherFunction, inScroll)) {
            expect("premise: 이동한 CTA 한 곳", 1, calls("SubscribeCTA").findAll(moved).count())
            expect("이동한 footer CTA 호출 수", 0, footerCount(moved))
        }
        fun save(t: String) = Regex(""""(?:저장|알림 만들기)"""").findAll(t).count()
        val files = code.filterKeys { it.substringAfterLast('/').startsWith("FreeComparisonAlertPreview") }
        expect("무료 시트의 저장 리터럴",0,files.values.sumOf(::save))
        expect("저장 양성",1,save(sheet + """TextButton(onClick = {}, enabled = false) { Text("저장") }"""))
        fun hasFieldsCall(text: String) = scan.squash(text).split(freeCall).size == 2
        expect("공유 Fields 입력",true,hasFieldsCall(sheet))
        expect("유료 message 전달 대조",false,hasFieldsCall(sheet.replace(freeCall,freeCall.replace("message = null","message = error"))))
        fun callers(name: String) = code.mapValues { calls(name).findAll(it.value).count() }.filterValues { it > 0 }
        expect("Fields 호출자",mapOf(path to 1,scan.BASE + "ui/alert/ComparisonAlertEditorSheet.kt" to 1),callers("ComparisonAlertEditorFields"))
        expect("CTA 호출자",mapOf(path to 1,scan.SHEET to 1,scan.BANK_SHEET to 1),callers("SubscribeCTA"))
        val forbidden = Regex("""(?i)permission|ComparisonAlertSettingsOwner|ComparisonAlertHandle|ComparisonAlertSection|ComparisonAlertEditorSheet|AlertRepository|canSave|\.draft\(|\.restore\(|\.duplicate\(|History|rememberSaveable|DataStore|FreeSnapshotScheduler|기준시각|KRX""")
        expect("무료 외곽의 금지 호출",emptyList<String>(),files.values.flatMap { t -> forbidden.findAll(t).map { it.value }.toList() })
        for (lure in listOf("import a.ComparisonAlertSettingsOwner as Alias","val name = \"AlertRepository\"",
            "fun neutral() = a.NotificationPermissionAction()","fun neutral() = store.draft()"))
            expect("금지 탐지 양성",true,forbidden.containsMatchIn(lure))
    }
}
