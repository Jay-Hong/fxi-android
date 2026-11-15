package com.jay.fxi.data.local

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `fxi_user_intent` has one DataStore, and each concern in it keeps to its own keys (S7 E5b4a).
 *
 * Read from the comment-free code and string literals of the five production roots
 * ([S6Krx3bOffWiringContractTest.ROOTS]). DataStore refuses two instances over one file at run time,
 * but only when both are touched in the same process; a second delegate that no test happens to
 * touch would ship. [BackupRulesLedgerTest] collects store names into a set, so it does not see a
 * second declaration of a name it already has.
 *
 * Two properties of the bank store are locked here because no device test can see them: the owner
 * check and the clearing share one edit (two sequential calls behave the same either way; only a
 * racing write tells them apart), and process-start readiness actually reads the file (the process
 * keeps one DataStore and answers from its cache, so a check that reads nothing passes on a device).
 *
 * Not covered: a name built at run time (`"fxi_" + "user_intent"`, a computed value) is not a
 * literal and is not seen by the literal count. For this land's three files the construction count
 * below covers it instead; a Unicode escape inside a quoted literal is decoded by
 * [PostS6ApprovedDelta.codeAndStrings] and so is seen.
 *
 * The counts, the member bodies and the exact declaration are the canonical shape this land agrees
 * to, not the API itself (`s7e5b4a_gaphunt.codex.r1.md` §3). Each detector below is also run on
 * fixture text, so a detector that stopped seeing its counterexample fails here rather than passing
 * silently.
 */
class UserIntentDataStoreStructureTest {

    @Test
    fun premise_everyProductionRootIsScanned() {
        assertTrue(
            "premise: 다섯 운영 root 와 충분한 소스",
            S6Krx3bOffWiringContractTest.ROOTS.all { File(it).isDirectory } && code.size > 300
        )
    }

    /**
     * Exactly one literal names the file as a store, and it is the one delegate, written as agreed.
     *
     * Any literal holding the name counts — a bare name, a path, a file name — so a second store or a
     * direct file writer cannot reach the file under a literal that merely contains the name. The
     * purge manifest's target id is the one other literal, and it is pinned as written.
     */
    @Test
    fun theFileHasOneDeclaration() {
        assertEquals(
            "fxi_user_intent 를 담은 문자열이 선언과 purge id 외에 있다",
            mapOf(DECLARING to 1, PURGE_MANIFEST to 1),
            sites(NAME_IN_A_LITERAL)
        )
        assertEquals("purge id 가 합의 형태가 아니다", mapOf(PURGE_MANIFEST to 1), sites(literal("datastore:fxi_user_intent")))
        assertTrue(
            "선언이 합의 형태가 아니다",
            squash(code.getValue(DECLARING)).contains(DECLARATION)
        )
    }

    /** Only the two concerns reach the file, through the shared accessor. */
    @Test
    fun onlyTheTwoConcernsUseTheAccessor() {
        assertEquals(
            mapOf(DECLARING to 1, RATE_ROW to 2, LAST_BANK to 3),
            sites("""\buserIntentDataStore\b""")
        )
    }

    /**
     * Each concern's keys are named in its own file and nowhere else.
     *
     * A key written from a second file would be a second writer the owner checks know nothing about.
     */
    @Test
    fun eachConcernOwnsItsKeys() {
        assertEquals(mapOf(LAST_BANK to 1), sites(literal("last_selected_bank_owner_uid")))
        assertEquals(mapOf(LAST_BANK to 1), sites(literal("last_selected_bank")))
        assertEquals(mapOf(RATE_ROW to 1), sites(literal("rate_row_owner_uid")))
    }

    /** The agreed surface (s7e5b4_api_agreed.r1.md §2.1): three members, and a concrete singleton over the app context. */
    @Test
    fun theBankStoreHasTheAgreedSurface() {
        val text = squash(code.getValue(LAST_BANK))
        assertTrue("interface 가 합의 멤버 셋이 아니다", text.contains(INTERFACE))
        assertTrue("구현 선언이 합의 형태가 아니다", text.contains(IMPLEMENTATION))
    }

    /**
     * read compares the owner and clears in one edit; remember writes in one edit; verifyReadable reads and does nothing else.
     *
     * Each member's body is the text from its declaration to the next member (or the companion). A read that compared on a
     * snapshot and cleared in a second edit could clear a value written between the two; a readiness check that reads nothing
     * would report a file it never opened as readable.
     */
    @Test
    fun eachBankStoreMemberTouchesTheFileOnce() {
        // With the edit as read's only access, the owner can only be compared inside it.
        assertEquals("read: 파일 접근은 edit 하나", listOf("userIntentDataStore.edit {"), accesses(member("override suspend fun read(")))
        assertEquals("remember: 파일 접근은 edit 하나", listOf("userIntentDataStore.edit {"), accesses(member("override suspend fun remember(")))
        assertEquals(
            "verifyReadable: data.first() 하나뿐이어야 한다",
            "override suspend fun verifyReadable() { context.userIntentDataStore.data.first() }",
            member("override suspend fun verifyReadable(")
        )
    }

    /**
     * The move constructs no other DataStore (G08).
     *
     * In the three files this land changes, the canonical delegate is the only DataStore
     * construction — whatever the name argument is built from — and no import is aliased, so none
     * can hide under another name. A new store elsewhere is outside this land's change set: the
     * PostS6 ledger and the final diff review stop it, and [BackupRulesLedgerTest] owns the store list.
     */
    @Test
    fun theDelegateMoveIntroducesNoOtherStoreConstruction() {
        assertEquals(1, constructions(DECLARATION))
        assertEquals(1, constructions("""private val Context.x by preferencesDataStore("fxi_" + "user_intent")"""))
        assertEquals(1, constructions("private const val N = \"fxi_user_intent\"\nprivate val Context.y by preferencesDataStore(name = N)"))
        assertEquals(1, constructions("val s = PreferenceDataStoreFactory.create { f }"))
        assertEquals(0, constructions("import androidx.datastore.preferences.preferencesDataStore\n"))
        assertEquals(1, aliases("import androidx.datastore.preferences.preferencesDataStore as pds\n"))

        assertEquals(
            "이 land 의 세 파일에서 DataStore 생성은 공유 선언 하나뿐이어야 한다",
            mapOf(DECLARING to 1),
            LAND.associateWith { constructions(code.getValue(it)) }.filterValues { it > 0 }
        )
        assertEquals("import alias 가 있다", emptyMap<String, Int>(),
            LAND.associateWith { aliases(code.getValue(it)) }.filterValues { it > 0 })
    }

    /**
     * The two stores call the real DataStore and coroutine APIs (G09).
     *
     * The member-body lock reads `data.first()` as text; a `first`, `edit` or `data` declared in the
     * same file, as a member, an extension or a top-level function, would be what that text calls.
     */
    @Test
    fun bankStoreCallsTheUnshadowedDataStoreApis() {
        assertEquals(1, shadows("private suspend fun kotlinx.coroutines.flow.Flow<Preferences>.first(): Preferences = emptyPreferences()"))
        assertEquals(1, shadows("fun Flow<Preferences>.first(): Preferences = TODO()"))
        assertEquals(1, shadows("private val data = MutableStateFlow(emptyPreferences())"))
        assertEquals(1, shadows("suspend fun <T> DataStore<T>.edit(block: (T) -> Unit) {}"))
        assertEquals(0, shadows("val preferences = context.userIntentDataStore.data.first()"))

        for (path in listOf(LAST_BANK, RATE_ROW)) {
            val text = code.getValue(path)
            for (required in REQUIRED_IMPORTS) assertTrue("$path: $required 가 없다", Regex("""(?m)^\s*${Regex.escape(required)}\s*$""").containsMatchIn(text))
            assertEquals("$path: import alias 가 있다", 0, aliases(text))
            assertEquals("$path: first·edit·data 를 가리는 선언이 있다", 0, shadows(text))
        }
    }

    /**
     * The dormant store has no startup or legacy side effects (G10).
     *
     * The shared file is the four imports and the declaration, nothing else. The bank store's file
     * keeps to W1, this land's canonical work rule (not the API; `e5b4a_gaphunt.claude.r3.md` §1):
     * imports from one closed set; every bare identifier an allowed keyword or name or one the file
     * declares; every identifier after `.` one of a closed set of members, which a declaration in
     * the file can never satisfy; no backtick identifier. Template code inside strings counts as
     * code. A legacy file, the journal, a path, a scope or a thread cannot be reached under those
     * rules, however it is spelled. W1 is a closed rule over one small file, not a proof that
     * arbitrary Kotlin has no effect: a misuse of an allowed call is the device tests' to catch.
     */
    @Test
    fun theDormantStoreHasNoStartupOrLegacySideEffects() {
        for (positive in W1_POSITIVE) assertEquals("양성 fixture 가 거부됐다: $positive", emptySet<String>(), w1Violations(withMember(positive)))
        for ((negative, reason) in W1_NEGATIVE) {
            val found = w1Violations(if (negative.startsWith("package ")) negative else withMember(negative))
            assertTrue("음성 fixture 가 기대 사유 [$reason] 로 거부되지 않았다: $found ← $negative", found.any { it.startsWith(reason) })
        }

        val sharedSource = source(DECLARING)
        val shared = code.getValue(DECLARING)
        assertEquals("공유 파일의 import 가 합의 넷이 아니다", DECLARING_IMPORTS, imports(sharedSource))
        assertEquals("공유 파일에 선언 외 코드가 있다", "package com.jay.fxi.data.local $DECLARATION", squash(withoutImports(shared)))

        val bank = source(LAST_BANK)
        assertEquals("은행 파일 import 가 허용 밖이다", emptySet<String>(), imports(bank) - BANK_IMPORTS)
        assertEquals("은행 파일이 W1 밖이다", emptySet<String>(), w1Violations(bank))
    }

    /** E5b4a is dormant: nothing in production reads, writes or binds the bank store yet. */
    @Test
    fun theBankStoreHasNoConsumerYet() {
        assertEquals(
            setOf(LAST_BANK),
            sites("""\b(?:DataStore)?LastSelectedBankStore\b""").keys
        )
    }

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val DECLARING = BASE + "data/local/UserIntentDataStore.kt"
        val RATE_ROW = BASE + "data/local/RateRowPreferenceStore.kt"
        val LAST_BANK = BASE + "data/local/LastSelectedBankStore.kt"
        val PURGE_MANIFEST = BASE + "data/entitlements/purge/PurgeManifest.kt"

        /** A string literal holding the file's name as a whole word, anywhere in it. */
        const val NAME_IN_A_LITERAL = """"[^"\n]*(?<![A-Za-z0-9_])fxi_user_intent(?![A-Za-z0-9_])[^"\n]*""""
        val DECLARATION = squash(
            """internal val Context.userIntentDataStore: DataStore<Preferences> by preferencesDataStore(
                name = "fxi_user_intent"
            )"""
        )
        val INTERFACE = squash(
            """interface LastSelectedBankStore {
                suspend fun read(uid: String): String?
                suspend fun remember(uid: String, bankCode: String)
                suspend fun verifyReadable()
            }"""
        )
        val IMPLEMENTATION = squash(
            """@Singleton
            class DataStoreLastSelectedBankStore @Inject constructor(
                @ApplicationContext private val context: Context
            ) : LastSelectedBankStore {"""
        )
        val MEMBER_END = listOf("override ", "private companion object", "companion object")
        val LAND = listOf(DECLARING, LAST_BANK, RATE_ROW)
        val DECLARING_IMPORTS = setOf(
            "android.content.Context", "androidx.datastore.core.DataStore",
            "androidx.datastore.preferences.core.Preferences", "androidx.datastore.preferences.preferencesDataStore"
        )
        val REQUIRED_IMPORTS = listOf("import kotlinx.coroutines.flow.first", "import androidx.datastore.preferences.core.edit")
        val CONSTRUCTION = Regex("""\b(?:preferencesDataStore|preferencesDataStoreFile|PreferenceDataStoreFactory|DataStoreFactory|MultiProcessDataStoreFactory)\b|\bdataStore\s*\(""")
        val ALIAS = Regex("""(?m)^\s*import\s+[\w.]+\s+as\s+\w+""")
        val SHADOW = Regex("""\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(?:first|edit|data)\b""")
        val BANK_IMPORTS = setOf(
            "android.content.Context", "androidx.datastore.preferences.core.MutablePreferences",
            "androidx.datastore.preferences.core.Preferences", "androidx.datastore.preferences.core.edit",
            "androidx.datastore.preferences.core.stringPreferencesKey", "dagger.hilt.android.qualifiers.ApplicationContext",
            "javax.inject.Inject", "javax.inject.Singleton", "kotlinx.coroutines.flow.first"
        )
        val W1_KEYWORDS = setOf(
            "package", "interface", "class", "object", "companion", "private", "internal", "public", "override", "suspend",
            "fun", "val", "var", "const", "constructor", "get", "if", "else", "when", "return", "null", "true", "false",
            "is", "in", "it", "this"
        )
        val W1_NAMES = setOf(
            "Context", "String", "Unit", "Boolean", "Preferences", "MutablePreferences", "Key", "LastSelectedBankStore",
            "DataStoreLastSelectedBankStore", "Singleton", "Inject", "ApplicationContext", "userIntentDataStore", "context",
            "data", "first", "edit", "remove", "stringPreferencesKey", "require", "isNotBlank", "let", "also", "takeIf"
        )

        /** After `.` or `?.`. No declaration in the file can stand in for one: a member always wins over a same-named extension. */
        val W1_MEMBERS = setOf("userIntentDataStore", "context", "data", "first", "edit", "remove", "isNotBlank", "let", "also", "takeIf", "Key")

        /** A store written the canonical way; [withMember] adds one fixture line before its companion. */
        val CANONICAL_SAMPLE = """package com.jay.fxi.data.local
            import android.content.Context
            interface LastSelectedBankStore { suspend fun read(uid: String): String? suspend fun remember(uid: String, bankCode: String) suspend fun verifyReadable() }
            @Singleton class DataStoreLastSelectedBankStore @Inject constructor(@ApplicationContext private val context: Context) : LastSelectedBankStore {
            override suspend fun read(uid: String): String? { require(uid.isNotBlank()) var code: String? = null
            context.userIntentDataStore.edit { preferences -> if (preferences[OWNER_UID] == uid) { code = preferences[BANK] } else { preferences.remove(OWNER_UID) preferences.remove(BANK) } } return code }
            override suspend fun remember(uid: String, bankCode: String) { require(uid.isNotBlank()) context.userIntentDataStore.edit { preferences -> preferences[OWNER_UID] = uid preferences[BANK] = bankCode } }
            override suspend fun verifyReadable() { context.userIntentDataStore.data.first() }
            // FIXTURE
            private companion object { val OWNER_UID = stringPreferencesKey("a") val BANK = stringPreferencesKey("b") } }"""

        fun withMember(line: String) = CANONICAL_SAMPLE.replace("// FIXTURE", line)

        /** Pure, IO-free forms the API allows; W1 must accept each (codex r2 §4.1, r3 §4.2). */
        val W1_POSITIVE = listOf(
            "",
            "private fun isOwner(stored: String?, uid: String): Boolean = stored == uid",
            """private val OWNER: Preferences.Key<String> = stringPreferencesKey("a")""",
            "private suspend fun peek() = this.context.userIntentDataStore.data.first()",
            """private val ownerName get() = "x"""",
            """private fun describe(owner: String) = "o=${'$'}owner"""",
            """private val escaped = "\${'$'}unlistedTemplate"""",
            """private val note = "android.system.Os::unlink `x`" private val rawNote = ${"\"\"\""}File::delete `y`${"\"\"\""} private val tick = '`'""",
            """private fun d(owner: String) = "a ${'$'}{owner + "\")("} b ${'$'}{ /* /* n */ */ owner}"""",
            "private fun context(uid: String): String = uid private fun use(uid: String) = context(uid)",
            "private fun init() = Unit private fun go() = init()",
            "private fun init(action: () -> Unit) = action() private fun helper() { init { } }",
        )

        /** Each must be refused, for the reason given (codex r1 §4, r2 §4.2–4.3, r3 §3–4; plan r4). A source starting with `package ` is a whole file. */
        val W1_NEGATIVE: List<Pair<String, String>> = listOf(
            """override suspend fun forget(uid: String) { context.userIntentDataStore.edit { android.system.Os.unlink(context.filesDir.path + "/datastore/fxi_" + "cache.preferences_pb") } }""" to "bare:android",
            """init { java.io.File(context.filesDir, "x").delete() }""" to "forbidden:init-block",
            "private val warm = context.filesDir.listFiles()" to "member:filesDir",
            """val leak = java.io.File("a")""" to "bare:java",
            "private val warmUp = Thread { }" to "bare:Thread",
            "private suspend fun journal() = LocalMigrationJournal.LAST_BANK_SELECTION" to "bare:LocalMigrationJournal",
            """private fun filesDir() = Unit private fun listFiles() = Unit private fun forEach() = Unit private fun name() = Unit private fun delete() = Unit
               private suspend fun sweep() { context.filesDir.listFiles()?.forEach { dir -> dir.listFiles()?.forEach { file -> if (file.name == "fxi_cache.preferences_pb") { file.delete() } } } }""" to "member:filesDir",
            """val probe = "${'$'}{android.system.Os.unlink(context.filesDir.path + "/datastore/fxi_" + "cache.preferences_pb")}"""" to "bare:android",
            """private fun deleteFile(n: String) = Unit private fun sweep() { context.run { deleteFile("fxi_cache.preferences_pb") } }""" to "forbidden:run",
            "private fun `delete`() = Unit" to "backtick",
            "init { }" to "forbidden:init-block",
            "context(Context) private fun x() = Unit" to "context-receiver",
            "private val marker = Unit\ncontext(Context) private fun x() = Unit" to "context-receiver",
            """private fun getFilesDir() = Unit private fun resolve() = Unit private fun delete() = Unit
               private fun <Key> legacyPath(f: (String) -> Key): Key = f("datastore/fxi_cache.preferences_pb")
               private suspend fun sweep() { val root = (context::getFilesDir)() val victim = legacyPath(root::resolve) val erase = victim::delete erase() }""" to "callable-ref",
            """private fun openFileOutput() = Unit private fun close() = Unit
               private fun probe() { val open = context::openFileOutput val stream = open("e5b4a_probe", 0) val finish = stream::close finish() }""" to "callable-ref",
            """private fun onContext(target: Context, action: Context.() -> Unit) { action(target) } private fun openFileOutput() = Unit
               private fun probe() { onContext(context) { openFileOutput("e5b4a_probe", 0) } }""" to "receiver-type",
            "private fun Context.let() = Unit" to "extension-decl",
            "private fun run() = Unit private fun apply() = Unit private fun with() = Unit private fun go() { run() apply() with() }" to "forbidden:run",
            "private val warm = context . /* ( \" */\n filesDir" to "member:filesDir",
            """private val t = "${'$'}unlistedTemplate"""" to "bare:unlistedTemplate",
            "private val r = ${"\"\"\""}${'$'}unlistedTemplate${"\"\"\""}" to "bare:unlistedTemplate",
            "private val r = ${"\"\"\""}\\${'$'}unlistedTemplate${"\"\"\""}" to "bare:unlistedTemplate",
            "private val rawProbe = ${"\"\"\""}${'$'}{android.system.Os.unlink(\"x\")}${"\"\"\""}" to "bare:android",
            """private val nested = "${'$'}{"${'$'}{android.system.Os.unlink("x")}"}"""" to "bare:android",
            """private val p = "${'$'}{ /* ") ( */ android.system.Os.unlink("x") }"""" to "bare:android",
            """private val u = "abc""" to "lexer",
            """private val u = "${'$'}{isOwner"""" to "lexer",
            "private val u = 1 /* open" to "lexer",
            // Unbalanced or mismatched parentheses and brackets in code, template code included (codex commit r1 T2, plan r4 §1).
            "private val sample = (Unit" to "lexer",
            "private val sample = Unit)" to "lexer",
            "private val sample = [Unit" to "lexer",
            "private val sample = (Unit]" to "lexer",
            "private fun f() { (Unit }" to "lexer",
            """private val u = "${'$'}{(isOwner}"""" to "lexer",
            "package com.jay.fxi.data.local\nimport android.content.Context; val leak = java.io.File(\"a\")\n" +
                CANONICAL_SAMPLE.substringAfter("import android.content.Context\n") to "line:import",
        )

        /** Every Kotlin production source of the scanned roots, comment-free with every literal kept; Java as written. */
        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        fun literal(content: String) = "\"" + Regex.escape(content) + "\""
        fun squash(text: String): String = Regex("""\s+""").replace(text, " ").replace("( ", "(").replace(" )", ")").trim()
        fun sites(pattern: String): Map<String, Int> = Regex(pattern).let { r ->
            code.mapValues { (_, text) -> r.findAll(text).count() }.filterValues { it > 0 }
        }

        /** One member of the bank store's implementation, squashed: from [start] to the next member or the companion. */
        fun member(start: String): String {
            val text = squash(code.getValue(LAST_BANK)).substringAfter(IMPLEMENTATION)
            val from = text.indexOf(start)
            check(from >= 0 && text.indexOf(start, from + 1) < 0) { "$start 는 정확히 한 번 있어야 한다" }
            val ends = MEMBER_END.map { text.indexOf(it, from + start.length) }.filter { it >= 0 }
            val body = text.substring(from, ends.minOrNull() ?: text.length).trim()
            return if (ends.isEmpty()) body.removeSuffix("}").trim() else body
        }

        fun withoutImports(text: String) = text.lines().filterNot { it.trim().startsWith("import ") }.joinToString("\n")
        fun constructions(text: String) = CONSTRUCTION.findAll(withoutImports(text)).count()
        fun aliases(text: String) = ALIAS.findAll(text).count()
        fun shadows(text: String) = SHADOW.findAll(text).count()

        fun imports(text: String): Set<String> =
            Regex("""(?m)^\s*import\s+([\w.]+)\s*$""").findAll(text).map { it.groupValues[1] }.toSet()

        fun source(path: String) = File(path).readText()

        /**
         * W1's findings for [text], each a reason prefix the fixtures name: `bare:`, `member:`,
         * `callable-ref`, `receiver-type`, `extension-decl`, `forbidden:` (run·apply·with and `init-block`), `context-receiver`,
         * `backtick`, `lexer`, `line:import`, `unsupported:`. The text is lexed first — comments become
         * spaces, literal data a placeholder, template code stays — and only then split into lines,
         * so a package or import line must be the whole declaration on its line.
         */
        fun w1Violations(text: String): Set<String> {
            val scanner = W1Scanner(text)
            val lexed = scanner.run()
            val out = scanner.errors.toMutableSet()
            val kept = lexed.lines().map { line ->
                val t = line.trim()
                when {
                    !t.startsWith("package ") && !t.startsWith("import ") -> line
                    Regex("""(?:package|import)\s+[\w.]+""").matches(t) -> ""
                    else -> { out += "line:import"; "" }
                }
            }
            val body = kept.joinToString("\n")
            if (W1Scanner.BACKTICK in body) out += "backtick"
            if ("::" in body) out += "callable-ref"
            if (hasContextDeclaration(body)) out += "context-receiver"
            if (hasInitBlock(body)) out += "forbidden:init-block"
            if (Regex("""\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?[A-Za-z_][\w<>?, ]*\.\s*[A-Za-z_]""").containsMatchIn(body)) out += "extension-decl"
            var i = body.indexOf('.')
            while (i >= 0) {
                val previous = if (i > 0) body[i - 1] else ' '
                when {
                    body.startsWith("..", i) -> { out += "unsupported:.."; i++ }
                    previous.isDigit() && i + 1 < body.length && body[i + 1].isDigit() -> Unit
                    else -> {
                        val next = Regex("""\s*([A-Za-z_]\w*|\S)""").find(body, i + 1)?.groupValues?.get(1) ?: ""
                        when {
                            next == "(" -> out += "receiver-type"
                            next !in W1_MEMBERS -> out += "member:$next"
                        }
                    }
                }
                i = body.indexOf('.', i + 1)
            }
            val declared = buildSet {
                Regex("""\b(?:fun|val|var|class|interface|object)\s+(?:<[^>]*>\s*)?(\w+)""").findAll(body).forEach { add(it.groupValues[1]) }
                Regex("""<\s*(\w+)\s*>""").findAll(body).forEach { add(it.groupValues[1]) }
                Regex("""\b(\w+)\s*:(?!:)""").findAll(body).forEach { add(it.groupValues[1]) }
                Regex("""\{\s*(\w+(?:\s*,\s*\w+)*)\s*->""").findAll(body).forEach { m -> m.groupValues[1].split(",").forEach { add(it.trim()) } }
            }
            for (m in Regex("""\b[A-Za-z_]\w*\b""").findAll(body)) {
                val name = m.value
                if (name == W1Scanner.LITERAL || name == W1Scanner.BACKTICK) continue
                if (name in W1_FORBIDDEN) { out += "forbidden:$name"; continue }
                val before = body.substring(0, m.range.first).trimEnd()
                if (before.endsWith(".") || before.endsWith("::")) continue
                if (name !in W1_KEYWORDS && name !in W1_NAMES && name !in declared) out += "bare:$name"
            }
            return out
        }

        /** Spelled out, whatever the file declares: each opens a receiver (an `init` block is refused separately). */
        val W1_FORBIDDEN = setOf("run", "apply", "with")

        /**
         * A context receiver or parameter declaration (codex r5 §4.1). Each `context(` in the code is
         * one of three: the name in `fun context(`; a call inside an expression, where the token before
         * it is an operator, `(`, `,`, `.` or `return`/`if`/`else`/`when`/`in`/`is`; or a candidate in
         * statement position — after anything else, a line break included. A candidate followed past its
         * closing parenthesis by a modifier, an annotation or a declaration keyword is a declaration; one
         * followed by anything else is a statement-level call. A candidate whose parenthesis does not close
         * cannot be told apart and counts as a declaration.
         */
        fun hasContextDeclaration(body: String): Boolean {
            for (m in Regex("""\bcontext\s*\(""").findAll(body)) {
                val before = body.substring(0, m.range.first).trimEnd()
                if (Regex("""\bfun$""").containsMatchIn(before)) continue
                if (before.isNotEmpty() && before.last() in "=(,+-*/%!<>&|?:.") continue
                if (Regex("""\b(?:return|if|else|when|in|is)$""").containsMatchIn(before)) continue
                var depth = 0
                var i = m.range.last
                do {
                    if (i >= body.length) return true
                    if (body[i] == '(') depth++ else if (body[i] == ')') depth--
                    i++
                } while (depth > 0)
                val after = body.substring(i).trimStart()
                if (after.startsWith("@") || DECLARATION_START.containsMatchIn(after)) return true
            }
            return false
        }

        val DECLARATION_START = Regex(
            """^(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|abstract|open|final|data|enum|sealed|inner|value|const|lateinit|fun|val|var|class|interface|object)\b"""
        )

        /**
         * An `init {` whose nearest enclosing brace opens a class, object or interface body — or the file
         * itself — is an initializer block (codex r5 §4.2). Inside a function body or lambda the same text
         * is a call of a helper named `init` with a trailing lambda, which W1 judges like any other call.
         * A brace opens a class body when the text since the previous `{`, `}` or `;` names `class`,
         * `object` or `interface` and no `fun`.
         */
        fun hasInitBlock(body: String): Boolean {
            val stack = ArrayDeque<Boolean>()
            var boundary = 0
            for ((i, c) in body.withIndex()) {
                if (c == '{') {
                    if (Regex("""\binit\s*$""").containsMatchIn(body.substring(boundary, i)) && stack.lastOrNull() != false) return true
                    val head = body.substring(boundary, i)
                    stack.addLast(Regex("""\b(?:class|object|interface)\b""").containsMatchIn(head) && !Regex("""\bfun\b""").containsMatchIn(head))
                    boundary = i + 1
                } else if (c == '}') {
                    stack.removeLastOrNull()
                    boundary = i + 1
                } else if (c == ';') {
                    boundary = i + 1
                }
            }
            return false
        }

        /**
         * Kotlin source lexed for W1: comments become a space, string and char literal data a
         * placeholder that keeps tokens apart, `${…}` and `$name` template code stays code with the
         * same treatment inside it. In an ordinary string a backslash escapes; in a raw string it
         * does not. Anything left open at the end is recorded in [errors] rather than dropped.
         */
        private class W1Scanner(private val source: String) {
            val errors = mutableSetOf<String>()
            private val out = StringBuilder(source.length)
            private var i = 0

            fun run(): String {
                code(closeOnBrace = false)
                return out.toString()
            }

            private fun at(text: String) = source.startsWith(text, i)

            /**
             * Code, or a template's code when [closeOnBrace]. Every `(`, `[` and `{` in code must close with its own kind in
             * order; a stray, early or wrong close, or one left open, is a lexer finding (plan r4 §1 "괄호 불균형").
             */
            private fun code(closeOnBrace: Boolean) {
                val open = ArrayDeque<Char>()
                while (i < source.length) {
                    val c = source[i]
                    when {
                        at("//") -> { while (i < source.length && source[i] != '\n') i++; out.append(' ') }
                        at("/*") -> blockComment()
                        at("\"\"\"") -> { i += 3; string(raw = true) }
                        c == '"' -> { i++; string(raw = false) }
                        c == '\'' -> char()
                        c == '`' -> { out.append(' ').append(BACKTICK).append(' '); i++ }
                        c == '(' || c == '[' || c == '{' -> { open.addLast(c); out.append(c); i++ }
                        c == '}' && closeOnBrace && open.isEmpty() -> { i++; return }
                        c == ')' || c == ']' || c == '}' -> {
                            if (open.removeLastOrNull() != OPENER.getValue(c)) errors += "lexer"
                            out.append(c); i++
                        }
                        else -> { out.append(c); i++ }
                    }
                }
                if (closeOnBrace || open.isNotEmpty()) errors += "lexer"
            }

            private fun blockComment() {
                var depth = 0
                do {
                    when {
                        at("/*") -> { depth++; i += 2 }
                        at("*/") -> { depth--; i += 2 }
                        else -> i++
                    }
                } while (depth > 0 && i < source.length)
                if (depth > 0) errors += "lexer"
                out.append(' ')
            }

            private fun char() {
                i++
                while (i < source.length && source[i] != '\'' && source[i] != '\n') { if (source[i] == '\\') i++; i++ }
                if (i >= source.length || source[i] != '\'') errors += "lexer" else i++
                out.append(' ').append(LITERAL).append(' ')
            }

            private fun string(raw: Boolean) {
                out.append(' ').append(LITERAL).append(' ')
                while (i < source.length) {
                    when {
                        raw && at("\"\"\"") -> { i += 3; while (i < source.length && source[i] == '"') i++; return }
                        !raw && source[i] == '"' -> { i++; return }
                        !raw && source[i] == '\n' -> { errors += "lexer"; return }
                        !raw && source[i] == '\\' -> i += 2
                        at("\${") -> { i += 2; out.append(' '); code(closeOnBrace = true); out.append(' ').append(LITERAL).append(' ') }
                        source[i] == '$' && i + 1 < source.length && (source[i + 1].isLetter() || source[i + 1] == '_') -> {
                            i++
                            out.append(' ')
                            while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) { out.append(source[i]); i++ }
                            out.append(' ')
                        }
                        else -> i++
                    }
                }
                errors += "lexer"
            }

            companion object {
                const val LITERAL = "W1LIT"
                const val BACKTICK = "W1BACKTICK"
                val OPENER = mapOf(')' to '(', ']' to '[', '}' to '{')
            }
        }

        /** Each reference to the shared accessor in [text], with what is done to it. */
        fun accesses(text: String): List<String> =
            Regex("""userIntentDataStore\s*\.\s*(edit\s*\{|data\b[^\s]*|\w+)""").findAll(text).map { it.value }.toList()
    }
}
