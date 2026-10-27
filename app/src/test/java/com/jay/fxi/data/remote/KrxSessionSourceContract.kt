package com.jay.fxi.data.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** D-A: the actual installed boundary is the sole entry to these four TSC private functions. */
internal object KrxSessionSourceContract {
    val typedEntries = setOf("prepareKrxAttempt", "checkKrxAttemptBeforeEncode", "onKrxAttemptSent", "discardKrxAttempt")
    fun strip(text: String): String = text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
    fun block(source: String, start: Int): IntRange {
        val open = source.indexOf('{', start)
        check(open >= 0) { "contract(O2-07): braced boundary not found" }
        var depth = 0
        for (i in open until source.length) {
            if (source[i] == '{') depth++
            if (source[i] == '}') { depth--; if (depth == 0) return open..i }
        }
        error("contract(O2-07): unclosed boundary")
    }
    fun function(source: String, name: String): String {
        val head = checkNotNull(Regex("""\bfun\s+$name\s*\(""").find(source)) { "contract(Z02): missing function $name" }
        var depth = 1
        var cursor = head.range.last + 1
        while (cursor < source.length && depth > 0) {
            if (source[cursor] == '(') depth++
            if (source[cursor] == ')') depth--
            cursor++
        }
        check(depth == 0) { "contract(Z02): unclosed parameters $name" }
        while (cursor < source.length && source[cursor] != '{' && source[cursor] != '=') cursor++
        check(cursor < source.length) { "contract(Z02): no function body $name" }
        return if (source[cursor] == '{') {
            val range = block(source, cursor)
            source.substring(head.range.first, range.last + 1)
        } else source.substring(head.range.first, source.indexOf('\n', cursor).let { if (it < 0) source.length else it })
    }
    fun outsideCalls(raw: String): Map<String, Int> {
        val source = strip(raw)
        val head = Regex("""\bval\s+boundary\s*=\s*object\s*:\s*KrxCommandAttemptBoundary\s*\{""").find(source)
            ?: return mapOf("missing-installed-boundary" to 1)
        val range = block(source, head.range.first)
        return typedEntries.associateWith { name ->
            Regex("""\b$name\s*\(""").findAll(source).count { match ->
                val prefix = source.substring(source.lastIndexOf('\n', match.range.first - 1) + 1, match.range.first)
                val declaration = Regex("""\bfun\s+$""").containsMatchIn(prefix)
                !declaration && match.range.first !in range
            }
        }.filterValues { it > 0 }
    }
    fun assertClosed() {
        val raw = File("src/main/java/com/jay/fxi/data/remote/TopicSessionCoordinator.kt").readText()
        val source = strip(raw)
        assertTrue("premise: O2-07 scans real TSC boundary installation", source.contains("command.installKrxAttemptBoundary"))
        assertEquals("contract(O2-07): exact typed private entry calls stay inside installed boundary", emptyMap<String, Int>(), outsideCalls(raw))
        for (name in typedEntries) {
            val pattern = Regex("""\b$name\s*\(""")
            assertEquals("contract(O2-07): $name declaration plus its one boundary call", 2, pattern.findAll(source).count())
            val mutated = raw + "\nprivate fun bypass() { $name(live, attempt) }\n"
            assertEquals("premise: O2-07 positive bypass mutation $name is caught", mapOf(name to 1), outsideCalls(mutated))
        }
    }
}
