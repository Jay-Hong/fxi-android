package com.jay.fxi.e5djourney

import com.jay.fxi.contract.ContractCorpusVerifier
import com.jay.fxi.contract.ContractResourceLoader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 U3a first unit (`s7u3a1_contract.codex.r1.md` Q6, `s7u3a1_results.codex.r3.md` Q1, `s7u3a1_commit_and_anr.codex.r1.md` C2):
 * the journey J13 hands the Firebase SDK
 * data strings that are copies of the bank goldens, because its APK cannot read this module's test resources. A copy made by
 * hand was wrong once — the two no-break spaces of the title's gaps had become ordinary spaces — so the copies are read here
 * from the journey's own source and compared with the corpus: the same keys, every golden value a JSON string, and every value
 * equal code point for code point, with no trimming and no whitespace folding.
 *
 * What is compared is the source text, read as Kotlin reads it. A copy counts only when the companion that the journey class
 * itself declares holds it once, directly, and the whole of its initializer is one `mapOf` call of plain string literal pairs:
 * a declaration that only a comment or a string holds is not one, and an operator or a call after the closing parenthesis — on
 * the same line or the next — fails, since it would change the map. What `deliver` does with a copy is fixed by comparing
 * the whole of the real `deliver`, its signature and its body, token for token with the reviewed form kept here ([DELIVER]):
 * literal text is opaque to this check and whitespace between call tokens is ignored, and any other difference — a condition
 * around a call, a key written another way, a call more or less — fails (`s7u3a1_commit_and_anr.codex.r2.md` D2). An auxiliary
 * check catches, in the present call forms, a copy name with an addition or a member call written right after it, or assigned
 * on directly; it is not a proof that a copy is only ever a plain argument. The journey's source keeps no non-ASCII whitespace
 * or format character: a no-break space is written as its escape.
 *
 * Not shown here: that the device run used this source (the run's own frozen manifest fixes that), what the device did with
 * the strings (the journey's own assertions), anything about the notification title the journey sends, which is its own marker
 * and not the golden's, a call made inside a string template's interpolation, and what a caller of `deliver` does to the data
 * before handing it over other than what the auxiliary check lists.
 */
class E5dJourneyJ13GoldenDataTest {
    private val corpus by lazy { ContractCorpusVerifier.verify(ContractResourceLoader.load()) }

    private fun root(id: String): JsonObject = Json.parseToJsonElement(corpus.text(id)).jsonObject

    private fun data(id: String): JsonObject = root(id).getValue("data").jsonObject

    private fun source(): String = File(JOURNEY).readText()

    /** [source] with its one [old] replaced: a negative whose edit did not land would show nothing. */
    private fun changed(source: String, old: String, new: String): String {
        assertEquals("premise: occurs once: $old", 1, occurrences(source, old))
        return source.replace(old, new)
    }

    @Test
    fun theJourneysThreeCopiesAreTheGoldensData() {
        val source = source()
        for ((name, id) in COPIES) {
            val copy = copy(source, name)
            assertNotNull("$name: declared once in the companion as one mapOf call of plain string pairs", copy)
            assertEquals("$name against $id", emptyList<String>(), problems(copy!!, data(id)))
        }
    }

    @Test
    fun aVisibleDeliveryCarriesTheCopysOwnBody_andTheListedMisusesOfACopyAreAbsent() {
        val source = source()
        for ((name, id) in COPIES - "SYNC") {
            val golden = root(id)
            val body = (golden.getValue("notification").jsonObject.getValue("body") as JsonPrimitive).content
            assertEquals("$id: the golden's notification body is its data body", body, (data(id).getValue("body") as JsonPrimitive).content)
            assertEquals("$name: body", body, copy(source, name)!!.single { it.first == "body" }.second)
        }
        assertEquals("deliver", emptyList<String>(), deliverProblems(source))
        assertEquals("the copies' uses", emptyList<String>(), useProblems(source))
    }

    /** What the reviewed form says, read from its own tokens: a reading aid with teeth against changing both sides together. */
    @Test
    fun theReviewedDeliverPutsTheDataAsItIs_andForAVisibleDeliveryTheCopysOwnBodyOnce() {
        val reviewed = tokens(DELIVER)!!
        fun once(snippet: String): Boolean {
            val part = tokens(snippet)!!
            return (0..reviewed.size - part.size).count { reviewed.subList(it, it + part.size) == part } == 1
        }
        val puts = reviewed.indices.filter { reviewed[it] == Token('w', "putExtra") }
        assertEquals("every putExtra of deliver, by its plain literal key", listOf("(data", "(mid", "(title", "(body"),
            puts.map { reviewed[it + 1].text + reviewed[it + 2].takeIf { key -> key.kind == 's' }?.text })
        assertTrue("the data strings as they are", once("putExtra(\"data\", Bundle().apply { data.forEach { (key, value) -> putString(key, value) } })"))
        assertTrue("the visible block is the marker title and the copy's own body, and nothing else",
            once("if (visible) { putExtra(\"title\", title) putExtra(\"body\", data.getValue(\"body\")) } }"))
        assertTrue("all of it in the one fcm call", once("val ack = run.client.ack(\"fcm\", nonce) { putExtra(\"data\""))
        assertEquals("data and visible are read nowhere else", listOf(3, 2), listOf("data", "visible").map { name -> reviewed.count { it == Token('w', name) } })
    }

    @Test
    fun theJourneysSourceKeepsNoInvisibleCharacter() {
        // A no-break space typed into a literal reads as a space; the copies write it as an escape so that it can be seen.
        assertEquals("non-ASCII whitespace or format characters in the journey's source", emptyList<String>(), invisible(source()))
        // Counted by code point: a format character outside the basic plane is two chars, neither of them a format character.
        val tag = String(Character.toChars(0xE0001))
        assertEquals(listOf("U+00A0", "U+200B", "U+E0001"), invisible("a" + 0xA0.toChar() + "b " + 0x200B.toChar() + tag + "\n"))
    }

    @Test
    fun negatives_aCopyThatDiffersIsReported() {
        val source = source()
        val golden = data("fcm-rate-alert-once")
        val title = (golden.getValue("title") as JsonPrimitive).content
        assertEquals("premise: the golden title's gaps are two no-break spaces each", 4, title.count { it == 0xA0.toChar() })
        val gaps = "하나은행\\u00A0\\u00A0달러\\u00A0\\u00A01391.2"

        // The defect this test exists for: ordinary spaces where the golden has no-break spaces.
        assertTrue(problems(copy(changed(source, gaps, "하나은행  달러  1391.2"), "ONCE")!!, golden).any { it.startsWith("title:") })
        // One no-break space fewer, and a trailing space: neither is folded away.
        assertTrue(problems(copy(changed(source, "하나은행\\u00A0\\u00A0달러", "하나은행\\u00A0달러"), "ONCE")!!, golden).any { it.startsWith("title:") })
        assertTrue(problems(copy(changed(source, "\"rate\" to \"1391.2\"", "\"rate\" to \"1391.2 \""), "ONCE")!!, golden).any { it.startsWith("rate:") })
        // A key missing, one too many, one twice.
        assertTrue(problems(copy(changed(source, "\"bank\" to \"hana\", ", ""), "ONCE")!!, golden).any { "missing" in it })
        assertTrue(problems(copy(changed(source, "\"bank\" to \"hana\"", "\"bank\" to \"hana\", \"extra\" to \"x\""), "ONCE")!!, golden).any { "unexpected" in it })
        assertTrue(problems(copy(changed(source, "\"bank\" to \"hana\"", "\"bank\" to \"hana\", \"bank\" to \"hana\""), "ONCE")!!, golden).any { "twice" in it })
        // A golden value that is not a JSON string is not read as its text.
        val numbered = JsonObject(golden + ("setting_id" to JsonPrimitive(101)))
        assertTrue(problems(copy(source, "ONCE")!!, numbered).any { it.startsWith("setting_id:") && "not a JSON string" in it })
        // The other maps are compared with their own goldens, not with this one.
        assertTrue(problems(copy(source, "REPEAT")!!, golden).isNotEmpty())
        assertTrue(problems(copy(source, "SYNC")!!, golden).isNotEmpty())
    }

    @Test
    fun negatives_aMapThatIsNotPlainStringPairsIsNotRead() {
        val source = source()
        val rate = "\"rate\" to \"1391.2\""
        assertNull("no such map", copy(source, "TWICE"))
        assertNull("a constant", copy(changed(source, SYNC_DECLARATION, "val SYNC = mapOf(\"type\" to SYNC_TYPE)"), "SYNC"))
        assertNull("a call", copy(changed(source, rate, "$rate.trim()"), "ONCE"))
        assertNull("a template", copy(changed(source, rate, "\"rate\" to \"\$RATE\""), "ONCE"))
        assertNull("an escape this reader does not know", copy(changed(source, rate, "\"rate\" to \"1391\\a2\""), "ONCE"))
        assertNull("a short unicode escape", copy(changed(source, rate, "\"rate\" to \"1391\\u00A\""), "ONCE"))
        assertNull("a comment between pairs", copy(changed(source, rate, "$rate /* 1391.2 */"), "ONCE"))
        assertNull("never closed", copy(inCompanion("val SYNC = mapOf(\"type\" to \"sync_alerts\""), "SYNC"))
        assertNull("empty", copy(inCompanion("val SYNC = mapOf()"), "SYNC"))
        assertNull("a key with no value", copy(inCompanion("val SYNC = mapOf(\"type\")"), "SYNC"))
        assertNull("no comma between pairs", copy(inCompanion("val X = mapOf(\"a\" to \"b\" \"c\" to \"d\")"), "X"))
        assertEquals("premise: the plain form is read", listOf("type" to "sync_alerts"), copy(inCompanion(SYNC_DECLARATION), "SYNC"))
        assertEquals("the escapes it does know", listOf("a" + 0xA0.toChar() + "b\\c\"d\$e\n" to "v"),
            copy(inCompanion("val X = mapOf(\"a\\u00A0b\\\\c\\\"d\\\$e\\n\" to \"v\")"), "X"))
    }

    @Test
    fun negatives_aDeclarationThatIsNotTheCompanionsOwnWholeInitializerIsNotRead() {
        val source = source()
        // `s7u3a1_results.codex.r3.md` Q1: a whole map with a key overridden after it would still read as the golden.
        assertNull("an operator after the call", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION + mapOf(\"type\" to \"wrong\")"), "SYNC"))
        assertNull("an operator on the next line", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION\n            + mapOf(\"type\" to \"wrong\")"), "SYNC"))
        assertNull("a call on the next line", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION\n            .mapValues { \"wrong\" }"), "SYNC"))
        assertNull("a call after a comment", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION // the golden's\n            .mapValues { \"wrong\" }"), "SYNC"))
        assertNull("a map closed early and added to", copy(changed(source, "\"bank\" to \"hana\", ", "\"bank\" to \"hana\") + mapOf("), "ONCE"))
        // Only what the companion itself declares, once, is a copy.
        assertNull("only in a comment", copy(changed(source, SYNC_DECLARATION, "// $SYNC_DECLARATION\n        val SYNCED = mapOf(\"type\" to \"wrong\")"), "SYNC"))
        assertNull("only in a block comment", copy(changed(source, SYNC_DECLARATION, "/* $SYNC_DECLARATION */ val SYNCED = mapOf(\"type\" to \"wrong\")"), "SYNC"))
        assertNull("only in a string", copy(changed(source, SYNC_DECLARATION, "val SYNCED = \"val SYNC = mapOf()\""), "SYNC"))
        assertNull("twice", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION\n        val SYNC = mapOf(\"type\" to \"wrong\")"), "SYNC"))
        assertNull("in a function of the companion", copy(changed(source, SYNC_DECLARATION, "fun sync(): Map<String, String> { $SYNC_DECLARATION\n            return SYNC }"), "SYNC"))
        assertNull("outside the companion", copy(changed(source, SYNC_DECLARATION, "val SYNCED = mapOf(\"type\" to \"wrong\")") + "\n$SYNC_DECLARATION\n", "SYNC"))
        assertNull("no companion", copy("class $JOURNEY_CLASS {\n    $SYNC_DECLARATION\n}\n", "SYNC"))
        assertNull("another class's companion", copy(
            "class Other {\n    companion object {\n        $SYNC_DECLARATION\n    }\n}\nclass $JOURNEY_CLASS {\n    companion object {\n        val TAG = \"t\"\n    }\n}\n", "SYNC"))
        assertNull("a nested class's companion", copy(
            "class $JOURNEY_CLASS {\n    class Inner {\n        companion object {\n            $SYNC_DECLARATION\n        }\n    }\n}\n", "SYNC"))
        assertNull("no journey class", copy("class Other {\n    companion object {\n        $SYNC_DECLARATION\n    }\n}\n", "SYNC"))
        assertNull("the journey class twice", copy(inCompanion(SYNC_DECLARATION) + inCompanion(SYNC_DECLARATION), "SYNC"))
        assertNull("a source that does not lex", copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION\n        /* never closed"), "SYNC"))
        // A comment between the call and the next declaration is where the journey's own are; a declaration may also end the companion.
        assertNotNull(copy(changed(source, SYNC_DECLARATION, "$SYNC_DECLARATION // sync\n        /** next */\n        val AFTER = 1"), "SYNC"))
    }

    @Test
    fun negatives_theVisibleBodyAndTheCopiesUses() {
        val source = source()
        val body = "putExtra(\"body\", data.getValue(\"body\"))"
        fun wrong(what: String, old: String, new: String) = assertTrue(what, deliverProblems(changed(source, old, new)).isNotEmpty())
        wrong("another body", body, "putExtra(\"body\", \"other\")")
        wrong("only in a comment", body, "putExtra(\"body\", title) // $body")
        wrong("set twice", body, "$body\n                putExtra(\"body\", \"other\")")
        wrong("outside the visible block", body, "}\n            run {\n                $body")
        wrong("deeper in the visible block", body, "if (case.isEmpty()) { $body }")
        wrong("no visible block", "if (visible) {", "if (visible && title.isNotEmpty()) {")
        // `s7u3a1_commit_and_anr.codex.r1.md` C2: the statement only inside a literal is no call, and a second call is one however
        // it is spaced — the last value put would be the wrong one.
        wrong("only in a raw string", body, "val example = \"\"\"$body\"\"\"")
        wrong("only in a string", body, "val example = \"putExtra(body)\"")
        wrong("overwritten, spaced otherwise", body, "$body\n                putExtra( \"body\", \"wrong\")")
        wrong("overwritten, across lines", body, "$body\n                putExtra(\n                    \"body\" /* key */, \"wrong\")")
        wrong("overwritten after the block", "        Log.i(TAG, \"\$case delivered mid=\$mid ack=\$ack\")", "        Intent().putExtra(\"body\", \"wrong\")")
        // `s7u3a1_commit_and_anr.codex.r2.md` D2: a condition without braces around the call, and a key written another way.
        wrong("under a condition without braces", body, "if (!visible) $body")
        wrong("overwritten under a parenthesized key", body, "$body\n                putExtra((\"body\"), \"wrong\")")
        wrong("overwritten under an escaped key", body, "$body\n                putExtra(\"b\\u006fdy\", \"wrong\")")
        // `s7u3a1_commit_and_anr.codex.r3.md` E2: a line comment ends at a carriage return too, and a raw string is not a quoted one.
        wrong("second body after a CR-terminated line comment", body, "$body // end\r                putExtra(\"body\", \"wrong\")")
        wrong("a raw-quoted title key", "putExtra(\"title\", title)", "putExtra(\"\"\"title\"\"\", title)")
        wrong("the data replaced before it is put", "        val mid = \"u3a-\${UUID.randomUUID()}\"", "        val mid = \"u3a-\${UUID.randomUUID()}\"\n        val data = data.mapValues { \"wrong\" }")
        wrong("a data string put changed", "data.forEach { (key, value) -> putString(key, value) }", "data.forEach { (key, value) -> putString(key, value.trim()) }")
        wrong("another signature", "data: Map<String, String>, visible: Boolean)", "data: Map<String, String>, visible: Boolean = false)")
        assertTrue("declared twice", deliverProblems(source.replace("    private fun foregroundVisible(", "    private fun deliver(nonce: String) = Unit\n\n    private fun foregroundVisible(")).isNotEmpty())
        assertTrue("not declared", deliverProblems(changed(source, "private fun deliver(", "private fun hand(")).isNotEmpty())
        // Spacing, line breaks and comments are not differences; a literal elsewhere that only reads like a call is not one.
        assertEquals("spaced otherwise", emptyList<String>(),
            deliverProblems(changed(source, body, "putExtra( \"body\" ,\n                    data . getValue( \"body\" ) ) // the copy's")))
        assertEquals("a literal outside deliver", emptyList<String>(),
            deliverProblems(changed(source, "    private fun pid(): Int", "    private val example = \"\"\"putExtra(\"body\", \"wrong\")\"\"\"\n\n    private fun pid(): Int")))

        val home = "deliver(nonce, \"B1-once\", ONCE, visible = true)"
        assertTrue("a copy added to at its use", useProblems(changed(source, home, "deliver(nonce, \"B1-once\", ONCE + (\"title\" to \"x\"), visible = true)")).isNotEmpty())
        assertTrue("a copy mapped at its use", useProblems(changed(source, "foregroundVisible(\"F1-repeat\", REPEAT)", "foregroundVisible(\"F1-repeat\", REPEAT.mapValues { \"x\" })")).isNotEmpty())
        assertTrue("a copy never used", useProblems(changed(source, "deliver(nonce, \"F2-sync\", SYNC, visible = false)",
            "deliver(nonce, \"F2-sync\", mapOf(\"type\" to \"sync_alerts\"), visible = false)")).any { "never used" in it })
        assertTrue("a copy assigned on", useProblems(changed(source, home, "val once = ONCE\n            $home")).isNotEmpty())
    }

    companion object {
        const val JOURNEY = "../e5dJourney/src/main/kotlin/com/jay/fxi/e5djourney/test/J13FcmDisplayTest.kt"
        const val SYNC_DECLARATION = "val SYNC = mapOf(\"type\" to \"sync_alerts\")"
        val COPIES = mapOf("ONCE" to "fcm-rate-alert-once", "REPEAT" to "fcm-rate-alert-repeat", "SYNC" to "fcm-sync-alerts")

        const val JOURNEY_CLASS = "J13FcmDisplayTest"

        /** The journey class with a companion that declares just [declaration]. */
        fun inCompanion(declaration: String): String = "class $JOURNEY_CLASS {\n    private companion object {\n        $declaration\n    }\n}\n"

        fun occurrences(text: String, part: String): Int {
            var count = 0
            var at = text.indexOf(part)
            while (at >= 0) {
                count++
                at = text.indexOf(part, at + 1)
            }
            return count
        }

        /**
         * [source] with every comment and the inside of every string and character literal blanked: the same length, line
         * breaks kept, so that an index into one is an index into the other. Null when a comment, a literal or a string template
         * does not end.
         */
        fun bare(source: String): String? = Lexer(source).run()

        private class Lexer(private val source: String) {
            private val out = StringBuilder(source)
            private var at = 0
            private var failed = false

            fun run(): String? {
                code(template = false)
                return if (failed) null else out.toString()
            }

            private fun blank(from: Int, to: Int) {
                for (index in from until to) if (out[index] != '\n') out.setCharAt(index, ' ')
            }

            /** Code to the end of the source, or — in a string template — to the brace that closes it. */
            private fun code(template: Boolean) {
                var depth = 0
                while (at < source.length && !failed) {
                    when {
                        source.startsWith("//", at) -> {
                            val from = at
                            while (at < source.length && source[at] != '\n' && source[at] != '\r') at++
                            blank(from, at)
                        }
                        source.startsWith("/*", at) -> comment()
                        source.startsWith("\"\"\"", at) -> literal("\"\"\"")
                        source[at] == '"' -> literal("\"")
                        source[at] == '\'' -> character()
                        source[at] == '{' -> { depth++; at++ }
                        source[at] == '}' -> {
                            at++
                            if (template && depth == 0) return
                            depth--
                        }
                        else -> at++
                    }
                }
                if (template) failed = true
            }

            /** A block comment; Kotlin's nest. */
            private fun comment() {
                val from = at
                var depth = 0
                while (at < source.length) {
                    if (source.startsWith("/*", at)) { depth++; at += 2 }
                    else if (source.startsWith("*/", at)) {
                        depth--; at += 2
                        if (depth == 0) return blank(from, at)
                    } else at++
                }
                failed = true
            }

            private fun literal(quote: String) {
                val raw = quote.length == 3
                val from = at + quote.length
                at = from
                while (at < source.length && !failed) {
                    when {
                        source.startsWith(quote, at) -> {
                            blank(from, at)
                            at += quote.length
                            return
                        }
                        !raw && source[at] == '\\' -> at += 2
                        !raw && source[at] == '\n' -> failed = true
                        source.startsWith("\${", at) -> { at += 2; code(template = true) }
                        else -> at++
                    }
                }
                failed = true
            }

            private fun character() {
                val from = at + 1
                at = from
                while (at < source.length && source[at] != '\'') {
                    if (source[at] == '\n') { failed = true; return }
                    at += if (source[at] == '\\') 2 else 1
                }
                if (at >= source.length) { failed = true; return }
                blank(from, at)
                at++
            }
        }

        /** The index of the brace that closes the one at [open] in [code]. */
        private fun close(code: String, open: Int): Int? {
            var depth = 0
            for (index in open until code.length) when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
            return null
        }

        /** How many more braces open than close in [code] from [from] up to [to]. */
        private fun level(code: String, from: Int, to: Int): Int = code.substring(from, to).let { part -> part.count { it == '{' } - part.count { it == '}' } }

        /**
         * The braces of the companion the journey class itself declares: the one `companion object` directly in the body of the
         * one `class J13FcmDisplayTest` of [code], its opening brace to its closing one. Another class's companion, or a nested
         * class's, is not it.
         */
        private fun companionBraces(code: String): IntRange? {
            val klass = Regex("""\bclass $JOURNEY_CLASS\s*\{""").findAll(code).toList().singleOrNull()?.range?.last ?: return null
            val klassEnd = close(code, klass) ?: return null
            val open = Regex("""\bcompanion object\s*\{""").findAll(code).map { it.range }
                .filter { it.first in klass..klassEnd && level(code, klass + 1, it.first) == 0 }.toList().singleOrNull()?.last ?: return null
            return close(code, open)?.let { open..it }
        }

        /**
         * The pairs of the copy [name] of [source], in order, each literal unescaped as Kotlin reads it. Null unless the
         * companion declares `val NAME = mapOf(...)` once and directly — not in a comment, a string or a nested block — the call
         * holds only `"key" to "value"` pairs with commas between, and nothing follows the call but the next `val` or the end of
         * the companion.
         */
        fun copy(source: String, name: String): List<Pair<String, String>>? {
            val code = bare(source) ?: return null
            val companion = companionBraces(code) ?: return null
            val start = Regex("""\bval ${Regex.escape(name)} = mapOf\(""").findAll(code).toList().singleOrNull()?.range ?: return null
            if (start.first !in companion || level(code, companion.first + 1, start.first) != 0) return null
            val literals = ArrayList<String>()
            val between = ArrayList<String>()
            val gap = StringBuilder()
            var at = start.last + 1
            while (true) {
                if (at >= source.length) return null
                val char = source[at++]
                when (char) {
                    ')' -> break
                    '"' -> {
                        val text = StringBuilder()
                        while (true) {
                            if (at >= source.length) return null
                            val next = source[at++]
                            when (next) {
                                '"' -> break
                                '$', '\n' -> return null
                                '\\' -> {
                                    if (at >= source.length) return null
                                    when (val escaped = source[at++]) {
                                        '\\', '"', '\'', '$' -> text.append(escaped)
                                        'n' -> text.append('\n')
                                        't' -> text.append('\t')
                                        'r' -> text.append('\r')
                                        'b' -> text.append('\b')
                                        'u' -> {
                                            val hex = source.substring(at, minOf(at + 4, source.length))
                                            if (!Regex("[0-9A-Fa-f]{4}").matches(hex)) return null
                                            text.append(hex.toInt(16).toChar())
                                            at += 4
                                        }
                                        else -> return null
                                    }
                                }
                                else -> text.append(next)
                            }
                        }
                        literals += text.toString()
                        between += gap.toString().trim()
                        gap.clear()
                    }
                    else -> gap.append(char)
                }
            }
            if (gap.toString().trim().isNotEmpty() || literals.isEmpty() || literals.size % 2 != 0) return null
            // Before a key: nothing (the first) or a comma; before a value: `to`.
            between.forEachIndexed { index, text ->
                val expected = if (index % 2 == 1) "to" else if (index == 0) "" else ","
                if (text != expected) return null
            }
            // After the call: the next declaration or the companion's end. Comments are blank in `code`; anything else there —
            // an operator, a call, on this line or a later one — would be part of the initializer and change the map.
            var next = at
            while (next < code.length && code[next].isWhitespace()) next++
            val ends = next == companion.last || (code.startsWith("val", next) && code.getOrNull(next + 3)?.isWhitespace() == true)
            if (!ends) return null
            return literals.chunked(2).map { (key, value) -> key to value }
        }

        /** What makes [copy] not the golden [data]: a key twice, missing or unexpected, a golden value that is no JSON string, a value that differs. */
        fun problems(copy: List<Pair<String, String>>, data: JsonObject): List<String> {
            val problems = ArrayList<String>()
            copy.groupBy { it.first }.filterValues { it.size > 1 }.keys.forEach { problems += "$it: twice in the copy" }
            val copied = copy.toMap()
            (data.keys - copied.keys).forEach { problems += "$it: missing in the copy" }
            (copied.keys - data.keys).forEach { problems += "$it: unexpected in the copy" }
            for ((key, value) in data) {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null) { problems += "$key: the golden value is not a JSON string"; continue }
                val mine = copied[key] ?: continue
                if (mine != text) problems += "$key: copy ${points(mine)} golden ${points(text)}"
            }
            return problems
        }

        /**
         * `deliver` as it was reviewed, every line of it: the data strings put as they are, the message id, and — only for a
         * visible delivery — the marker title and the copy's own body, each put once. The journey's `deliver` must be this, token
         * for token.
         */
        val DELIVER = listOf(
            "private fun deliver(nonce: String, case: String, data: Map<String, String>, visible: Boolean): Pair<JSONObject, String> {",
            "    val mid = \"u3a-\${UUID.randomUUID()}\"",
            "    val title = \"U3A \$case \${mid.takeLast(6)}\"",
            "    val ack = run.client.ack(\"fcm\", nonce) {",
            "        putExtra(\"data\", Bundle().apply { data.forEach { (key, value) -> putString(key, value) } })",
            "        putExtra(\"mid\", mid)",
            "        if (visible) {",
            "            putExtra(\"title\", title)",
            "            putExtra(\"body\", data.getValue(\"body\"))",
            "        }",
            "    }",
            "    Log.i(TAG, \"\$case delivered mid=\$mid ack=\$ack\")",
            "    return ack to title",
            "}"
        ).joinToString("\n")

        /** A token of Kotlin source: a word (`w`), a punctuation character (`p`), or a quoted string (`s`), raw string (`r`) or character (`c`) literal. */
        data class Token(val kind: Char, val text: String)

        private val OPEN = Token('p', "{")
        private val CLOSE = Token('p', "}")

        /**
         * The tokens of [source], whitespace and comments dropped. A literal is one token whose text is what stands between its
         * quotes, so nothing inside a literal is ever a word or a parenthesis. Null when the source does not lex.
         */
        fun tokens(source: String): List<Token>? {
            val code = bare(source) ?: return null
            val tokens = ArrayList<Token>()
            var at = 0
            while (at < code.length) {
                val char = code[at]
                when {
                    char.isWhitespace() -> at++
                    code.startsWith("\"\"\"", at) -> {
                        val end = code.indexOf("\"\"\"", at + 3).takeIf { it >= 0 } ?: return null
                        tokens += Token('r', source.substring(at + 3, end))
                        at = end + 3
                    }
                    char == '"' || char == '\'' -> {
                        val end = code.indexOf(char, at + 1).takeIf { it >= 0 } ?: return null
                        tokens += Token(if (char == '"') 's' else 'c', source.substring(at + 1, end))
                        at = end + 1
                    }
                    char.isLetterOrDigit() || char == '_' -> {
                        val from = at
                        while (at < code.length && (code[at].isLetterOrDigit() || code[at] == '_')) at++
                        tokens += Token('w', code.substring(from, at))
                    }
                    else -> {
                        tokens += Token('p', char.toString())
                        at++
                    }
                }
            }
            return tokens
        }

        /** The index of the token that closes the brace at [open], or null. */
        private fun closing(tokens: List<Token>, open: Int): Int? {
            var depth = 0
            for (index in open until tokens.size) {
                if (tokens[index] == OPEN) depth++
                if (tokens[index] == CLOSE && --depth == 0) return index
            }
            return null
        }

        /**
         * What makes the journey's `deliver` other than its reviewed form [DELIVER]: it must be declared once, privately, and
         * its tokens from `private` to the brace that closes its body must be the reviewed ones, one for one.
         */
        fun deliverProblems(source: String): List<String> {
            val tokens = tokens(source) ?: return listOf("the source does not lex")
            val reviewed = tokens(DELIVER) ?: return listOf("the reviewed form does not lex")
            val name = listOf(Token('w', "fun"), Token('w', "deliver"), Token('p', "("))
            val declared = (1..tokens.size - name.size).filter { tokens.subList(it, it + name.size) == name }
            val start = declared.singleOrNull()?.minus(1)?.takeIf { tokens[it] == Token('w', "private") } ?: return listOf("deliver: not declared once, privately")
            val open = (start until tokens.size).firstOrNull { tokens[it] == OPEN } ?: return listOf("deliver: no body")
            val end = closing(tokens, open) ?: return listOf("deliver: no end")
            val actual = tokens.subList(start, end + 1)
            if (actual == reviewed) return emptyList()
            val at = (0 until maxOf(actual.size, reviewed.size)).first { actual.getOrNull(it) != reviewed.getOrNull(it) }
            return listOf("deliver is not its reviewed form: token $at is ${actual.getOrNull(at)?.text} where ${reviewed.getOrNull(at)?.text} was expected")
        }

        /**
         * The auxiliary check of where the copies go. In the present call forms it catches a copy name with an addition or a
         * member call written right after it, one assigned on directly, and one never used: every use must stand between an
         * opening parenthesis or a comma and a comma or a closing parenthesis. It is not a proof that a copy is only ever a
         * plain argument — `(ONCE).mapValues { … }` passes it — and it refuses a named argument.
         */
        fun useProblems(source: String): List<String> {
            val code = bare(source) ?: return listOf("the source does not lex")
            val problems = ArrayList<String>()
            for (name in COPIES.keys) {
                val uses = Regex("""\b$name\b""").findAll(code).map { it.range }
                    .filterNot { code.substring(0, it.first).trimEnd().endsWith("val") }.toList()
                if (uses.isEmpty()) problems += "$name: never used"
                for (use in uses) {
                    val before = code.substring(0, use.first).trimEnd().lastOrNull()
                    val after = code.substring(use.last + 1).trimStart().firstOrNull()
                    if (before != ',' && before != '(' || after != ',' && after != ')') problems += "$name: not written as a bare argument"
                }
            }
            return problems
        }

        /** The non-ASCII whitespace and format characters of [text], each as its code point: what a reader cannot see. */
        fun invisible(text: String): List<String> = text.codePoints().toArray()
            .filter { it > 0x7F && (Character.isWhitespace(it) || Character.isSpaceChar(it) || Character.getType(it) == Character.FORMAT.toInt()) }
            .map { "U+%04X".format(it) }

        private fun points(text: String): String = text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
    }
}
