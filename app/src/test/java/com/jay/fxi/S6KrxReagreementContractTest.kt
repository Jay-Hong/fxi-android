package com.jay.fxi

import com.jay.fxi.data.remote.*
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.local.*
import com.jay.fxi.di.ServerEndpoints
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/** Source expectations are frozen HEAD inventory plus contracts.r2 §4.1/§5, never candidate-learned. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxReagreementContractTest {
    @Test fun d20_sourceAndPublicApiBoundariesAreExact() {
        val cases = listOf("source","protected337","protected349","TSC-installer","Factory-installer","bootstrap-callers","new-file-bypass","allowed-file-bypass","provider-OFF","typed-sync","shared-switch-identity","Factory-reject-replacement","compile-constructors/trailing-lambda",
            // S6-4b D20 follow-up (s6_4b_contracts.r4.md §7): the exact fixture delta and its validator's negatives.
            "delta-format","product-base","delta-fifth-file","delta-outside","delta-traversal","delta-overlap","delta-main","delta-from-forged","delta-added-existing","delta-base",
            "delta-stage1-modules","delta-stage1-journal","delta-stage1-preserved",
            "delta-main-byte","delta-unlisted-fixture-byte","delta-actual-not-to-routes","delta-actual-not-to-server",
            "delta-unlisted-fixture-path","delta-alias","delta-duplicate-key",
            "delta-actual-not-to-modules","delta-actual-not-added-journal","delta-missing-journal337","delta-missing-journal349")
        for (sub in s64Cases("D20",cases)) {
            val inventory = resource("source_inventory.json").jsonObject
            val expected = inventory.getValue("source_files").jsonObject
            val roots = inventory.getValue("roots").jsonArray.map { it.jsonPrimitive.content }
            val actual = roots.flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }.associate { it.invariantSeparatorsPath to it.readBytes() }
            assertEquals("precondition(D20/$sub): five populated archive roots",listOf(350,3,7,1,1),inventory.getValue("root_counts").jsonArray.map { it.jsonPrimitive.int })
            val delta = S64bFixtureDelta.load()
            val code = actual.mapValues { KrxSessionSourceContract.strip(it.value.toString(Charsets.UTF_8)) }
            val base = "src/main/java/com/jay/fxi/"; val rtPath = base + "data/remote/TopicRuntime.kt"
            val rt = code.getValue(rtPath); val tsc = code.getValue(base+"data/remote/TopicSessionCoordinator.kt"); val tbo = code.getValue(base+"data/remote/TopicBootstrapOrder.kt")
            fun count(pattern: String,text: String) = Regex(pattern).findAll(text).count()
            // Receiver-aware: declarations are excluded; unqualified/this calls are Factory calls only in RT.
            fun calls(sources: Map<String,String>): Map<String,Int> {
                val result = linkedMapOf<String,Int>()
                val pattern = Regex("""(?:(\b[A-Za-z_]\w*)\s*\.)?\binstallKrxDesiredProducer\s*\(""")
                for ((path,text) in sources) for (m in pattern.findAll(text)) {
                    if (Regex("""\bfun\s*$""").containsMatchIn(text.substring(0,m.range.first))) continue
                    val receiver = m.groupValues[1].ifEmpty { "<implicit>" }
                    val key = "$path::$receiver"; result[key] = (result[key] ?: 0) + 1
                }
                return result
            }
            if (sub in setOf("delta-main-byte", "delta-unlisted-fixture-byte", "delta-actual-not-to-routes",
                    "delta-actual-not-to-server", "delta-unlisted-fixture-path", "delta-actual-not-to-modules",
                    "delta-actual-not-added-journal", "delta-missing-journal337", "delta-missing-journal349")) {
                val fixture = "src/s5Benchmark/java/com/jay/fxi/s5fixture/"
                val path = when (sub) {
                    "delta-main-byte" -> "src/main/java/com/jay/fxi/di/EntitlementsModule.kt"
                    "delta-unlisted-fixture-byte" -> fixture + "S5FixtureIdentity.kt"
                    "delta-actual-not-to-modules" -> fixture + "S5FixtureModules.kt"
                    "delta-actual-not-added-journal", "delta-missing-journal337", "delta-missing-journal349" -> fixture + "S5PurityJournal.kt"
                    "delta-actual-not-to-routes" -> fixture + "S5FixtureRoutes.kt"
                    "delta-actual-not-to-server" -> fixture + "S5FixtureServer.kt"
                    else -> fixture + "S64bUnlistedFixture.kt"
                }
                val validator = when (sub) {
                    "delta-unlisted-fixture-path" -> "source"
                    "delta-actual-not-to-routes", "delta-actual-not-to-server", "delta-actual-not-to-modules",
                    "delta-actual-not-added-journal", "delta-missing-journal337" -> "protected337"
                    "delta-missing-journal349" -> "protected349"
                    else -> "product-base"
                }
                // An existing baseline RED must never make a negative pass. In particular, HEAD's
                // placeholder-to delta cannot establish either actual!=to negative's premise.
                val baselines = if (sub.startsWith("delta-actual-not-") || sub.startsWith("delta-missing-journal"))
                    listOf("source", "product-base", "protected337", "protected349") else listOf(validator)
                for (baseline in baselines) try {
                    assertActual(baseline, inventory, delta, actual)
                } catch (failure: AssertionError) {
                    throw AssertionError("precondition(D20/$sub): GREEN $baseline actual/delta required; ${failure.message}", failure)
                }
                if (sub == "delta-unlisted-fixture-path")
                    assertFalse("precondition(D20/$sub): injected path is absent", path in actual)
                val altered = if (sub.startsWith("delta-missing-journal")) actual - path else actual + (path to if (sub == "delta-unlisted-fixture-path")
                    "// unlisted fixture input\n".toByteArray() else actual.getValue(path) + byteArrayOf(10))
                val validators = if (sub.startsWith("delta-actual-not-")) listOf("protected337", "protected349") else listOf(validator)
                for (check in validators) {
                    var rejected: AssertionError? = null
                    try { assertActual(check, inventory, delta, altered) }
                    catch (failure: AssertionError) { rejected = failure }
                    assertNotNull("contract(D20): $sub/the validator rejects invalid actual input ($check)", rejected)
                    println("D20/$sub validator=$check injectedPath=$path rejection=${rejected?.message}")
                }
                continue
            }
            if (sub == "delta-alias" || sub == "delta-duplicate-key") {
                val baseline = S64bFixtureDelta.rawErrors(S64bFixtureDelta.inventoryBytes(), inventory, delta.raw)
                assertEquals("precondition(D20/$sub): GREEN raw delta format", emptyList<String>(), baseline)
                val path = S64bFixtureDelta.CHANGED.first()
                val changed = delta.json.getValue("changed").jsonObject
                val raw = if (sub == "delta-alias") {
                    val alias = path.substringBeforeLast('/') + "/./" + path.substringAfterLast('/')
                    JsonObject(delta.json + ("changed" to JsonObject(changed - path + (alias to changed.getValue(path))))).toString()
                } else {
                    val members = changed.entries.flatMap { (key, entry) ->
                        val member = "${JsonPrimitive(key)}:$entry"
                        if (key == path) listOf(member, member) else listOf(member)
                    }.joinToString(",")
                    JsonObject(delta.json - "changed").toString().dropLast(1) + ",\"changed\":{$members}}"
                }
                val found = S64bFixtureDelta.rawErrors(S64bFixtureDelta.inventoryBytes(), inventory, raw)
                assertTrue("contract(D20): $sub/the validator rejects the invalid delta: $found", found.isNotEmpty())
                println("D20/$sub expectedDiagnostic=${if (sub == "delta-alias") "path-form" else "duplicate-key"} actualDiagnostics=$found")
                continue
            }
            when (sub) {
                // S6-4b: the frozen path set plus exactly the delta's added path; nothing learned from the candidate.
                "source" -> assertActual(sub, inventory, delta, actual)
                "protected337", "protected349" -> assertActual(sub, inventory, delta, actual)
                "delta-format" -> assertEquals("contract(D20): delta-format/the agreed delta validates against the frozen inventory",
                    emptyList<String>(),S64bFixtureDelta.rawErrors(S64bFixtureDelta.inventoryBytes(), inventory, delta.raw))
                "product-base" -> assertActual(sub, inventory, delta, actual)
                "delta-fifth-file","delta-outside","delta-traversal","delta-overlap","delta-main","delta-from-forged","delta-added-existing","delta-base",
                "delta-stage1-modules","delta-stage1-journal" -> {
                    val stage1 = sub.startsWith("delta-stage1-")
                    val input = if (stage1) S64bFixtureDelta.stage1().json else delta.json
                    val (altered, expectedError) = S64bFixtureDelta.negative(sub, input, inventory)
                    val found = S64bFixtureDelta.errors(S64bFixtureDelta.inventoryBytes(),inventory,altered, stage1)
                    assertTrue("contract(D20): $sub/the validator rejects the invalid delta: $found", found.isNotEmpty())
                    println("D20/$sub expectedDiagnostic=$expectedError actualDiagnostics=$found")
                }
                "delta-stage1-preserved" -> {
                    val stage1 = S64bFixtureDelta.stage1()
                    assertEquals("contract(D20): delta-stage1-preserved/original stage1 validates", emptyList<String>(),
                        S64bFixtureDelta.errors(S64bFixtureDelta.inventoryBytes(), inventory, stage1.json, true))
                    val routes = S64bFixtureDelta.CHANGED.first { it.endsWith("S5FixtureRoutes.kt") }
                    assertEquals("contract(D20): delta-stage1-preserved/unchanged Routes to", stage1.changedTo(routes), delta.changedTo(routes))
                    assertEquals("contract(D20): delta-stage1-preserved/product base retains approved 4a bytes",
                        stage1.productBase() - S64bFixtureDelta.MODULES, delta.productBase())
                }
                "TSC-installer" -> {
                    assertEquals("contract(D20): TSC-installer/receiver sites",mapOf("$rtPath::session" to 1),calls(code).filterKeys { it.endsWith("::session") })
                    assertEquals("contract(D20): TSC-installer/create call once",1,count("""session\.installKrxDesiredProducer\s*\(""",KrxSessionSourceContract.function(rt,"create")))
                    assertTrue("contract(D20): TSC-installer/exact signature",Regex("""internal\s+fun\s+installKrxDesiredProducer\s*\(\s*switch\s*:\s*KrxContestSwitch\s*\)""").containsMatchIn(tsc))
                }
                "Factory-installer" -> {
                    assertEquals("contract(D20): Factory-installer/receiver sites",mapOf("$rtPath::session" to 1,"$rtPath::<implicit>" to 1),calls(code))
                    val di = Regex("""@Inject\s+constructor\s*\([^)]*sharedSwitch\s*:\s*KrxContestSwitch\s*,?\s*\)\s*:\s*this\s*\([^)]*\)\s*\{\s*installKrxDesiredProducer\(sharedSwitch\)\s*}""")
                    assertTrue("contract(D20): Factory-installer/new DI sole settings caller",di.containsMatchIn(rt))
                    assertTrue("contract(D20): Factory-installer/exact signature",Regex("""internal\s+fun\s+installKrxDesiredProducer\s*\(\s*switch\s*:\s*KrxContestSwitch\s*\)""").containsMatchIn(rt))
                }
                "shared-switch-identity" -> {
                    assertFalse("contract(D20): shared-switch-identity/new switch instances forbidden",Regex("""\bKrxContestSwitch\s*\(""").containsMatchIn(rt+tsc))
                    assertTrue("contract(D20): shared-switch-identity/DI passes exact object",Regex("""\binstallKrxDesiredProducer\s*\(\s*sharedSwitch\s*\)""").containsMatchIn(rt))
                }
                "Factory-reject-replacement" -> runTest {
                    var failure: Throwable? = null; var invoked = false
                    val w = S64HeadWorld(this,"D20/Factory-reject-replacement",configure = { _,factory ->
                        factory.installKrxDesiredProducer(KrxContestSwitch.OFF)
                        failure = runCatching { factory.installKrxDesiredProducer(KrxContestSwitch(false)) }.exceptionOrNull()
                        invoked = true
                    })
                    try {
                        assertTrue("precondition(D20/Factory-reject-replacement): real pre-create settings invoked",invoked)
                        assertTrue("contract(D20): Factory-reject-replacement/equal-value different object rejected",failure is IllegalStateException || failure is IllegalArgumentException)
                    } finally { w.close() }
                }
                "typed-sync" -> assertTrue("contract(D20): typed-sync/private same-read signature",Regex("""private\s+fun\s+syncRuntimeKrxDesired\s*\(\s*stamp\s*:\s*KrxSessionStamp\?\s*,\s*use\s*:\s*KrxUseLifetime\?\s*\)""").containsMatchIn(tsc))
                "bootstrap-callers" -> {
                    assertEquals("contract(D20): bootstrap-callers/exact total",2,count("""TopicBootstrapOrder\.bootstrapShownBy\s*\(""",tsc))
                    for (name in listOf("retryTopicsOnLoop","issueOwedBootstraps")) assertEquals("contract(D20): bootstrap-callers/$name",1,count("""TopicBootstrapOrder\.bootstrapShownBy\s*\(""",KrxSessionSourceContract.function(tsc,name)))
                    assertEquals("contract(D20): bootstrap-callers/TBO K once",1,count("""\bKRX_FUTURES\b""",tbo))
                }
                "new-file-bypass" -> {
                    val altered = code + (base+"data/remote/Bypass.kt" to "fun x() { factory.installKrxDesiredProducer(other) }")
                    assertNotEquals("contract(D20): new-file-bypass/receiver scan detects",calls(code),calls(altered))
                    assertNotEquals("contract(D20): new-file-bypass/path scan detects",expected.keys,actual.keys+"src/main/java/com/jay/fxi/data/remote/Bypass.kt")
                }
                "allowed-file-bypass" -> assertNotEquals("contract(D20): allowed-file-bypass/receiver scan detects",calls(code),calls(code + (rtPath to rt+"\nfun bad() { session.installKrxDesiredProducer(other) }")))
                "provider-OFF" -> assertTrue("contract(D20): provider-OFF/singleton",Regex("""fun\s+provideKrxContestSwitch\(\)\s*:\s*KrxContestSwitch\s*=\s*KrxContestSwitch\.OFF""").containsMatchIn(code.getValue(base+"di/EntitlementsModule.kt")))
                else -> {
                    for ((name,headParameters) in inventory.getValue("head_constructor_parameters").jsonObject) {
                        val text = code.getValue(base+"data/remote/$name")
                        val actualParameters = constructorParameters(text).filterNot { it.contains("sharedSwitch:KrxContestSwitch") }
                        assertEquals("contract(D20): compile-constructors/trailing-lambda/$name exact preserved parameters and defaults",headParameters.jsonArray.map { it.jsonPrimitive.content },actualParameters)
                    }
                }
            }
        }
    }
    /** The same actual-input validator serves the preserved positives and the independent negatives. */
    private fun assertActual(sub: String, inventory: JsonObject, delta: S64bFixtureDelta.Delta, actual: Map<String, ByteArray>) {
        val expected = inventory.getValue("source_files").jsonObject
        when (sub) {
            "source" -> {
                assertEquals("contract(D20): source/exact frozen paths plus the 4b added journal",expected.keys + S64bFixtureDelta.ADDED,actual.keys)
                for (path in actual.keys) {
                    val file = File(path)
                    assertTrue("contract(D20): source/no source aliases or special files/$path",
                        java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                            file.canonicalFile == file.absoluteFile.normalize())
                }
            }
            "protected337", "protected349" -> {
                assertEquals("contract(D20): $sub/frozen protected count", if (sub == "protected337") 337 else 349,
                    inventory.getValue(sub).jsonObject.getValue("protected").jsonObject.size)
                assertEquals("contract(D20): $sub/exact added protection", setOf("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt"), S64bFixtureDelta.ADDED)
                for ((path,hash) in inventory.getValue(sub).jsonObject.getValue("protected").jsonObject) {
                    val want = if (path in S64bFixtureDelta.CHANGED) delta.changedTo(path) else hash.jsonPrimitive.content
                    assertEquals("contract(D20): $sub/$path/raw HEAD bytes or the agreed 4b delta",want,actual[path]?.let(::sha))
                }
                for (path in S64bFixtureDelta.ADDED)
                    assertEquals("contract(D20): $sub/$path/the added journal is protected at its agreed bytes",delta.added(path),actual[path]?.let(::sha))
            }
            "product-base" -> {
                for ((path,hash) in delta.productBase()) assertEquals("contract(D20): product-base/$path/bytes equal the 4b base",hash,actual[path]?.let(::sha))
                assertEquals("contract(D20): product-base/covers every frozen path outside the delta",expected.keys - S64bFixtureDelta.CHANGED,delta.productBase().keys)
            }
            else -> error("unregistered actual validator $sub")
        }
    }

    private fun constructorParameters(text: String): List<String> = Regex("""\bconstructor\s*\(|\bclass\s+TopicSessionCoordinator\s*\(""").findAll(text).map { head ->
        var depth = 1; var end = head.range.last + 1; val start = end
        while (depth > 0 && end < text.length) { when (text[end++]) { '(' -> depth++; ')' -> depth-- } }
        check(depth == 0) { "precondition(D20/compile-constructors): constructor closes" }
        text.substring(start,end-1).replace(Regex("""\s+"""),"").trimEnd(',')
    }.toList()
    @Suppress("unused")
    private fun compileFactoryConstructors(tokenProvider: AuthTokenProvider, orders: AccessOrderSequence,
        fences: AuthFenceStream, recoveries: AuthCredentialRecoveryStream, coordinator: PremiumAccessCoordinator,
        bootstrapService: TopicSnapshotBootstrapService, decoder: TopicFrameDecoder, tabs: FreeTabStore,
        json: Json, lastKnown: TopicLastKnownStore, authority: KrxSendReadAuthority, endpoints: ServerEndpoints,
        sharedSwitch: KrxContestSwitch): Pair<TopicRuntimeFactory, TopicRuntimeFactory> =
        TopicRuntimeFactory(tokenProvider, orders, fences, recoveries, coordinator, bootstrapService, decoder, tabs, json,
            lastKnown, authority, endpoints) to
        TopicRuntimeFactory(tokenProvider, orders, fences, recoveries, coordinator, bootstrapService, decoder, tabs, json,
            lastKnown, authority, endpoints, sharedSwitch)

    @Suppress("unused")
    private fun compileTscTrailingLambda(scope: kotlinx.coroutines.CoroutineScope, clock: TopicCommandClock,
        connect: (Long) -> TopicTransport, credentials: TopicCommandCredentials, orders: AccessOrderSequence,
        authority: TopicUseAuthority, bootstrap: suspend (AuthIdentityFence, String, () -> Boolean) -> TopicSnapshotOutcome,
        store: com.jay.fxi.domain.model.TopicSubscriptionStateStore,
        encode: (com.jay.fxi.data.remote.dto.TopicSubscribeRequest) -> String, newId: () -> String,
        jitter: () -> Double, liveIdentity: () -> AuthIdentityFence?): TopicSessionCoordinator =
        TopicSessionCoordinator(scope, clock, connect, credentials, orders, authority, bootstrap, store, encode, newId, jitter, liveIdentity) { 1_000_000L }

    companion object {
        internal fun resource(name: String): JsonElement {
            val stream = S6KrxReagreementContractTest::class.java.classLoader.getResourceAsStream("krx/s64a/$name")
            assertNotNull("precondition(D20/resource): krx/s64a/$name exists", stream)
            return stream!!.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
        }
        internal fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** S6-4b-2 cumulative D20 stage 2; frozen inventory and the preserved stage1 revision remain immutable. */
internal object S64bFixtureDelta {
    private const val DIR = "src/s5Benchmark/java/com/jay/fxi/s5fixture/"
    const val MODULES = DIR + "S5FixtureModules.kt"
    val STAGE1_CHANGED = setOf(DIR + "S5FixtureRoutes.kt", DIR + "S5FixtureServer.kt")
    val CHANGED = STAGE1_CHANGED + MODULES
    val ADDED = setOf(DIR + "S5PurityJournal.kt")
    private val HEX = Regex("^[0-9a-f]{64}$")

    class Delta(val json: JsonObject, val raw: String = json.toString()) {
        fun changedTo(path: String) = json.getValue("changed").jsonObject.getValue(path).jsonObject.getValue("to").jsonPrimitive.content
        fun added(path: String) = json.getValue("added").jsonObject.getValue(path).jsonPrimitive.content
        fun productBase(): Map<String, String> = json.getValue("product_base").jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    fun load(): Delta {
        val stream = S64bFixtureDelta::class.java.classLoader.getResourceAsStream("krx/s64b/fixture_delta.json")
        assertNotNull("precondition(D20/delta): krx/s64b/fixture_delta.json exists", stream)
        val raw = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Delta(Json.parseToJsonElement(raw).jsonObject, raw)
    }

    fun stage1(): Delta {
        val stream = S64bFixtureDelta::class.java.classLoader.getResourceAsStream("krx/s64b/fixture_delta.stage1.json")
        assertNotNull("precondition(D20/stage1): approved original preserved", stream)
        val raw = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Delta(Json.parseToJsonElement(raw).jsonObject, raw)
    }

    fun inventoryBytes(): ByteArray {
        val stream = S64bFixtureDelta::class.java.classLoader.getResourceAsStream("krx/s64a/source_inventory.json")
        assertNotNull("precondition(D20/delta): frozen inventory exists", stream)
        return stream!!.use { it.readBytes() }
    }

    /** Only an exact approved 4b to hash is projected back to its frozen from hash for legacy fingerprints. */
    fun normalizeForLegacyFingerprint(actualHashes: Map<String, String>): Map<String, String> {
        val frozenBytes = inventoryBytes()
        val frozen = Json.parseToJsonElement(frozenBytes.toString(Charsets.UTF_8)).jsonObject
        val delta = load()
        assertEquals("precondition(S64b/legacy-fingerprint): delta base, exact paths and from match the frozen inventory",
            emptyList<String>(), errors(frozenBytes, frozen, delta.json))
        assertTrue("contract(S64b/legacy-fingerprint): added journal exists", ADDED.all { it in actualHashes })
        // Omit only the exact approved added bytes from the historical fingerprint.
        // A missing/altered journal stays visible to the independent actual/protection checks.
        return actualHashes.filterNot { (path, hash) -> path in ADDED && hash == delta.added(path) }
            .mapValues { (path, actualHash) ->
            if (path in CHANGED && actualHash == delta.changedTo(path))
                delta.json.getValue("changed").jsonObject.getValue(path).jsonObject.getValue("from").jsonPrimitive.content
            else actualHash
        }
    }

    private fun normalized(path: String) = path.isNotEmpty() && !path.startsWith("/") && '\\' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Error codes, empty when the delta is exactly the agreed shape. */
    fun errors(inventoryBytes: ByteArray, inventory: JsonObject, delta: JsonObject, stage1: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        if (delta.keys != setOf("schema", "note", "base", "changed", "added", "product_base")) out += "keys ${delta.keys}"
        if (delta["schema"]?.jsonPrimitive?.content != "1") out += "schema"
        if (delta["base"]?.jsonPrimitive?.content != S6KrxReagreementContractTest.sha(inventoryBytes)) out += "base"
        val frozen = inventory.getValue("source_files").jsonObject
        val changed = delta["changed"]?.jsonObject ?: JsonObject(emptyMap())
        val added = delta["added"]?.jsonObject ?: JsonObject(emptyMap())
        for (path in changed.keys + added.keys) if (!normalized(path)) out += "path-form $path"
        if (changed.keys != if (stage1) STAGE1_CHANGED else CHANGED) out += "changed-keys ${changed.keys}"
        if (added.keys != if (stage1) emptySet<String>() else ADDED) out += "added-keys ${added.keys}"
        if ((changed.keys intersect added.keys).isNotEmpty()) out += "overlap ${changed.keys intersect added.keys}"
        for (path in added.keys) if (path in frozen) out += "added-existing $path"
        for ((path, entry) in changed) {
            val e = entry.jsonObject
            if (e.keys != setOf("from", "to")) out += "changed-entry $path"
            if (e["from"]?.jsonPrimitive?.content != frozen[path]?.jsonPrimitive?.content) out += "from $path"
            if (e["to"]?.jsonPrimitive?.content?.matches(HEX) != true) out += "hash-form $path"
        }
        for ((path, hash) in added) if (!hash.jsonPrimitive.content.matches(HEX)) out += "hash-form $path"
        return out
    }

    /** Scan raw object member names before converting this validator input to JsonObject. */
    fun rawErrors(inventoryBytes: ByteArray, inventory: JsonObject, raw: String): List<String> = try {
        val duplicates = RawKeys(raw).scan()
        if (duplicates.isNotEmpty()) duplicates.map { "duplicate-key $it" }
        else errors(inventoryBytes, inventory, Json.parseToJsonElement(raw).jsonObject)
    } catch (invalid: IllegalArgumentException) {
        listOf("raw-json ${invalid.message}")
    }

    private class RawKeys(private val raw: String) {
        private var at = 0
        private val duplicates = mutableListOf<String>()
        private fun space() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
        private fun take(c: Char): Boolean {
            space()
            if (at >= raw.length || raw[at] != c) return false
            at++
            return true
        }
        private fun string(): String {
            space()
            val start = at
            require(at < raw.length && raw[at++] == '"') { "object key/string expected" }
            while (at < raw.length) {
                when (raw[at++]) {
                    '\\' -> { require(at < raw.length); at++ }
                    '"' -> return Json.parseToJsonElement(raw.substring(start, at)).jsonPrimitive.content
                }
            }
            throw IllegalArgumentException("unterminated string")
        }
        private fun value() {
            space()
            require(at < raw.length) { "value expected" }
            when (raw[at]) {
                '{' -> {
                    at++
                    val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do {
                        val key = string()
                        if (!keys.add(key)) duplicates += key
                        require(take(':')) { "colon expected" }
                        value()
                        if (take('}')) return
                    } while (take(','))
                    throw IllegalArgumentException("object delimiter expected")
                }
                '[' -> {
                    at++
                    if (take(']')) return
                    do { value(); if (take(']')) return } while (take(','))
                    throw IllegalArgumentException("array delimiter expected")
                }
                '"' -> { string() }
                else -> {
                    val start = at
                    while (at < raw.length && raw[at] !in ",]} \t\r\n") at++
                    require(at > start) { "scalar expected" }
                    val scalar = Json.parseToJsonElement(raw.substring(start, at))
                    require(scalar is JsonPrimitive) { "scalar expected" }
                }
            }
        }
        fun scan(): List<String> {
            value(); space()
            require(at == raw.length) { "trailing raw JSON" }
            return duplicates
        }
    }

    /** The altered delta for one negative and the error code the validator must report for it. */
    fun negative(sub: String, delta: JsonObject, inventory: JsonObject): Pair<JsonObject, String> {
        fun with(key: String, value: JsonObject) = JsonObject(delta + (key to value))
        val changed = delta.getValue("changed").jsonObject
        val added = delta.getValue("added").jsonObject
        val anyChanged = changed.values.first()
        val existing = inventory.getValue("source_files").jsonObject.keys.first { it.startsWith("src/main/") }
        val anyHash = JsonPrimitive("0".repeat(64))
        return when (sub) {
            "delta-fifth-file" -> with("changed", JsonObject(changed + (DIR + "S5FixtureIdentity.kt" to anyChanged))) to "changed-keys"
            "delta-outside" -> with("added", JsonObject(added + ("src/s5Benchmark/java/com/jay/fxi/Other.kt" to anyHash))) to "added-keys"
            "delta-traversal" -> with("added", JsonObject(mapOf("src/s5Benchmark/java/com/jay/fxi/s5fixture/../../../../main/X.kt" to anyHash))) to "path-form"
            "delta-overlap" -> with("added", JsonObject(added + (CHANGED.first() to anyHash))) to "overlap"
            "delta-main" -> with("changed", JsonObject(changed + (existing to anyChanged))) to "changed-keys"
            "delta-from-forged" -> with("changed", JsonObject(changed.mapValues { (path, e) ->
                if (path == CHANGED.first()) JsonObject(e.jsonObject + ("from" to JsonPrimitive("f".repeat(64)))) else e })) to "from"
            "delta-added-existing" -> with("added", JsonObject(added + (existing to anyHash))) to "added-existing"
            "delta-stage1-modules" -> with("changed", JsonObject(changed + (DIR + "S5FixtureModules.kt" to anyChanged))) to "changed-keys"
            "delta-stage1-journal" -> with("added", JsonObject(added + (DIR + "S5PurityJournal.kt" to anyHash))) to "added-keys"
            "delta-base" -> JsonObject(delta + ("base" to JsonPrimitive("e".repeat(64)))) to "base"
            else -> error("unregistered D20 delta negative $sub")
        }
    }
}
