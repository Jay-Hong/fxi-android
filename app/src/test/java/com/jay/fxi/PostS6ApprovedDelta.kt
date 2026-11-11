package com.jay.fxi

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * Exactly approved production changes from outside S6, on top of the frozen S6 source boundary (user decision
 * `R4c/S7/user_decision_s6freeze_A.r1.md`, design `s6freeze.codex.r1.md` §4).
 *
 * The S6 freeze tests keep their frozen inventory, BASELINE and S64b delta. Their meaning becomes "the original history plus the
 * exact bytes approved here": only an actual file whose hash equals its approved final hash is projected back to the history,
 * anything else stays visible to the same checks. KRX reference, caller and OFF scans keep reading the actual sources.
 *
 * The manifest's bytes are pinned by [APPROVED_SHA256]; changing the manifest, this pin, [NO_KRX_REVIEWED] or the forbidden
 * scope is a critical change (Claude·Codex exact-state agreement; S6 meaning or scope widening goes to the user).
 */
internal object PostS6ApprovedDelta {
    const val RESOURCE = "krx/post_s6/approved_delta.json"
    const val APPROVED_SHA256 = "ba93ab03068ba52855d2e9b2b61943a6642e05b787ff00a0893d59a1b0735e24"
    private const val BASE = S6Krx3bOffWiringContractTest.BASE
    private const val INVENTORY_SHA256 = "ee6b718c9ebc41f1e70a800bfb15d6073a66ef07041a2ba19b71e8a7a19d972f"
    private const val S64B_DELTA_SHA256 = "6a15c386a7c8220691c417e6e1f183d52614adb43b0dcd26ceb1bf712c61d536"
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val HEX40 = Regex("^[0-9a-f]{40}$")

    /**
     * Contents whose comment-free text was reviewed to hold no KRX reference, by SHA-256. Every `from` of a change must be one: the
     * text before a change is not available to this test, so the review that approved the change records it here.
     * - f2223839…: HEAD AuthenticatedApiService.kt, KRX only in the comment at :348 (graph catalog kdoc).
     * - e4662299…: HEAD AlertRepository.kt, KRX only in the comment at :49 (source alerts kdoc).
     * - a3ac34d8…: 05382fa9 PremiumTopicRoute.kt, no KRX at all (comments included).
     * - 159fd2a9…: 05382fa9 SettingsViewModel.kt, no KRX at all (comments included).
     * - a3021bca…: 05382fa9 PremiumTopicConsumer.kt, no KRX at all (comments included).
     * - 14c2521d…: 05382fa9 PremiumTopicScreen.kt, no KRX at all (comments included).
     * - cdf05372…: 05405265 FXiApplication.kt, no KRX at all (comments included).
     * - 4ac58a30…: 05405265 AlertRepository.kt, KRX only in the comment at :49 (source alerts kdoc).
     * - 26aab0a5…: 05405265 AuthenticatedApiService.kt, KRX only in the comment at :348 (graph catalog kdoc).
     */
    val NO_KRX_REVIEWED = setOf(
        "f22238393c489023bea5259c455d4bcaa4347ca40da5c9b306eb0787adedf7f9",
        "e46622991266bab843b30502d41789dbcff6807f6c449873037689e661c3a78f",
        "a3ac34d86bde39f742760e433bff8782dde92c83969beb64944b417dc5462bd5",
        "159fd2a92bba4a489e45f5ab4e43c8274a47df91e56c9005fc2884c2a4f2aa1d",
        "a3021bcad83f86aff42c9285b6adb47f98bc78be95e8a590c470f934857bc43a",
        "14c2521d76160a5f8be640966383ad47f843cf3ef035224e5a28262473058468",
        "cdf05372e6f59532fb9c3894e70e7d20a7756dfa9e8dab5281062fc4ebbffc7a",
        "4ac58a30a16701bdcd86f3232e24c1c652596d511346144ee5bfeeeef51d9026",
        "26aab0a503e6514df01d2006d2f27a2a5c32a4db70a7f80f72b5eb208ce2c242"
    )

    /**
     * A forbidden core change this ledger may carry, in its one unit only — that unit's id, source parent and approval record —
     * and only from and to these exact bytes. In place of the KRX-free rule its comment-free KRX lines are pinned by
     * [krxLinesSha256] ([krxLinesSha256] of the text). The forbidden scope itself is unchanged.
     * - S7-c F1 (`R4c/S7/user_decision_s7c_F1.r1.md` cc93e4f7…): TopicRuntimeOwner.kt.
     * - S7-N1 (`R4c/S7/s7n1/s7n1_scope.confirmed.r1.md` 1ecc6234…, user decision `R4c/S7/user_decision_s7n1.r1.md` E1):
     *   FXiMessagingService.kt, the `sync_alerts` hand-over after the emit.
     */
    class CoreException(val unitId: String, val sourceParent: String, val approvalRecordSha256: String, val path: String,
        val from: String, val to: String, val krxLinesSha256: String)

    val CORE_EXCEPTIONS: Set<CoreException> = setOf(
        CoreException(
            unitId = "S7-c",
            sourceParent = "05382fa9980a43009c237af464a44510f64770fc",
            approvalRecordSha256 = "cc93e4f7c7143dadea05ada082e2bdf494cba353cab7b8b4a4962b7630b2ab88",
            path = BASE + "data/remote/TopicRuntimeOwner.kt",
            from = "a618496be598fc92aa78184e732286f05f61e8bbc510de5a7b4b4dd1c92a7f56",
            to = "aef67386a820bc7db734ff786cabb3817a9394108bba468965b0c4c8069777f3",
            krxLinesSha256 = "234ddcf3be22830a470b42544091ab75bed096e27e420f9dee5158bc1f307260"
        ),
        CoreException(
            unitId = "S7-N1",
            sourceParent = "0540526510b14447e171b4cc3522cdda00246b7f",
            approvalRecordSha256 = "1ecc6234c4d4aebb9d6a51b90984404f7758a2e28549590f3e3d6911717ed838",
            path = BASE + "service/FXiMessagingService.kt",
            from = "5c157653a1e692df586958c0f81fe002e6b2ddb3671e85944321be1e87c50923",
            to = "9f3ecfc91728b9bc30ad8652b2a0c82d5d53251b565a7babba543a3fcb06b6fe",
            krxLinesSha256 = "08e142a551e0e47752a09853de243aa65b727b5863c75171d1cb7157ece13d02"
        )
    )

    /** The exception a unit's changed entry is, by every one of its fields, or null. */
    private fun coreException(id: String, unit: JsonObject, path: String, entry: JsonObject?): CoreException? =
        CORE_EXCEPTIONS.firstOrNull {
            it.unitId == id && it.sourceParent == unit.string("source_parent") &&
                it.approvalRecordSha256 == unit.string("approval_record_sha256") && it.path == path &&
                entry != null && entry.keys == setOf("from", "to") && entry.string("from") == it.from && entry.string("to") == it.to
        }

    /** UTF-8 SHA-256 of the comment-free lines that name KRX, each trimmed, in order, joined by LF with no final LF. */
    fun krxLinesSha256(source: String): String =
        sha(codeAndStrings(source).split("\n").filter { KRX.containsMatchIn(it) }.joinToString("\n") { it.trim() }.toByteArray(Charsets.UTF_8))

    /** S6's own approved and core surfaces: never changed through this ledger. */
    val FORBIDDEN_PATHS: Set<String> = S6Krx3bOffWiringContractTest.ALLOWED + S6Krx3bOffWiringContractTest.S64A_DELTA +
        S64bFixtureDelta.CHANGED + S64bFixtureDelta.ADDED + S6Krx3cOffContractTest.NORMAL
    val FORBIDDEN_PREFIXES: List<String> = listOf(
        "data/entitlements/", "data/graph/", "data/auth/", "di/", "admission/",
        "data/remote/Topic", "data/remote/Krx", "data/remote/AuthenticatedTransport.kt",
        "data/local/TopicLastKnown", "data/local/GraphSelection",
        "data/remote/DetachableGraphSinks.kt"
    ).map { BASE + it }

    /** The ledger folded over the S6 history: each path's original history hash (null when added) and its final approved hash. */
    class Ledger(val original: Map<String, String?>, val final: Map<String, String>) {
        val changed: Set<String> get() = original.filterValues { it != null }.keys
        val added: Set<String> get() = original.filterValues { it == null }.keys
    }

    fun raw(): ByteArray {
        val stream = PostS6ApprovedDelta::class.java.classLoader.getResourceAsStream(RESOURCE)
        assertNotNull("precondition(post-S6): $RESOURCE exists", stream)
        return stream!!.use { it.readBytes() }
    }

    /** The approved ledger; fails unless the manifest is exactly the approved one and every rule holds. */
    fun ledger(): Ledger {
        val raw = raw()
        val (errors, ledger) = parse(raw, S64bFixtureDelta.inventoryBytes(), S64bResourceBytes.delta(), expectedPin = APPROVED_SHA256)
        assertEquals("contract(post-S6): the approved ledger validates", emptyList<String>(), errors)
        return checkNotNull(ledger)
    }

    /**
     * Error codes for [raw], empty with the folded ledger when valid. [expectedPin] null skips only the approval pin, so the other
     * rules can be checked on altered inputs by the contract test.
     */
    fun parse(raw: ByteArray, inventoryBytes: ByteArray, s64bBytes: ByteArray, expectedPin: String?): Pair<List<String>, Ledger?> {
        val out = mutableListOf<String>()
        if (expectedPin != null && sha(raw) != expectedPin) out += "approval-pin"
        val text = raw.toString(Charsets.UTF_8)
        val duplicates = try { S64bFixtureDelta.RawKeys(text).scan() } catch (invalid: IllegalArgumentException) {
            return listOf("raw-json ${invalid.message}") to null
        }
        if (duplicates.isNotEmpty()) return (out + duplicates.map { "duplicate-key $it" }) to null
        val json = try { Json.parseToJsonElement(text) as? JsonObject } catch (invalid: IllegalArgumentException) { null }
            ?: return (out + "raw-json not an object") to null
        if (json.keys != setOf("schema", "note", "base", "units")) out += "keys ${json.keys}"
        if ((json["schema"] as? JsonPrimitive)?.let { if (it.isString) null else it.intOrNull } != 1) out += "schema"
        if ((json["note"] as? JsonPrimitive)?.isString != true) out += "note"
        val base = json["base"] as? JsonObject
        if (base == null || base.keys != setOf("product_commit", "source_inventory_sha256", "s64b_delta_sha256")) out += "base-keys"
        else {
            if (!base.string("product_commit").matches(HEX40)) out += "base-commit"
            if (base.string("source_inventory_sha256") != INVENTORY_SHA256 || sha(inventoryBytes) != INVENTORY_SHA256) out += "base-inventory"
            if (base.string("s64b_delta_sha256") != S64B_DELTA_SHA256 || sha(s64bBytes) != S64B_DELTA_SHA256) out += "base-s64b"
        }
        val units = json["units"] as? kotlinx.serialization.json.JsonArray ?: return (out + "units") to null
        if (units.isEmpty()) out += "units-empty"
        // The history before any post-S6 unit: the S64b product base, its changed `to` and its added journal.
        val s64b = Json.parseToJsonElement(s64bBytes.toString(Charsets.UTF_8)).jsonObject
        val current = linkedMapOf<String, String>()
        s64b.getValue("product_base").jsonObject.forEach { (path, hash) -> current[path] = hash.jsonPrimitive.content }
        s64b.getValue("changed").jsonObject.forEach { (path, entry) -> current[path] = entry.jsonObject.getValue("to").jsonPrimitive.content }
        s64b.getValue("added").jsonObject.forEach { (path, hash) -> current[path] = hash.jsonPrimitive.content }
        val original = linkedMapOf<String, String?>()
        val ids = mutableSetOf<String>()
        for ((index, element) in units.withIndex()) {
            val unit = element as? JsonObject ?: run { out += "unit-$index not an object"; null } ?: continue
            if (unit.keys != setOf("id", "source_parent", "approval_record_sha256", "changed", "added")) { out += "unit-$index keys"; continue }
            val id = (unit["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (id.isBlank() || !ids.add(id)) out += "unit-id $index"
            if (!unit.string("source_parent").matches(HEX40)) out += "unit-parent $id"
            if (!unit.string("approval_record_sha256").matches(HEX64)) out += "unit-approval $id"
            val changed = unit["changed"] as? JsonObject ?: run { out += "unit-changed $id"; JsonObject(emptyMap()) }
            val added = unit["added"] as? JsonObject ?: run { out += "unit-added $id"; JsonObject(emptyMap()) }
            if (changed.isEmpty() && added.isEmpty()) out += "unit-empty $id"
            for (path in changed.keys + added.keys) {
                if (!pathForm(path)) out += "path-form $path"
                val excepted = path in changed && coreException(id, unit, path, changed[path] as? JsonObject) != null
                if ((path in FORBIDDEN_PATHS || FORBIDDEN_PREFIXES.any { path.startsWith(it) }) && !excepted) out += "forbidden $path"
            }
            if ((changed.keys intersect added.keys).isNotEmpty()) out += "overlap ${changed.keys intersect added.keys}"
            for ((path, entry) in changed) {
                val e = entry as? JsonObject
                if (e == null || e.keys != setOf("from", "to")) { out += "changed-entry $path"; continue }
                val from = e.string("from"); val to = e.string("to")
                if (!from.matches(HEX64) || !to.matches(HEX64)) { out += "hash-form $path"; continue }
                val before = current[path]
                if (before == null) out += "changed-missing $path"
                else if (from != before) out += "from $path"
                if (from !in NO_KRX_REVIEWED && coreException(id, unit, path, e) == null) out += "krx-before-unreviewed $path"
                if (!original.containsKey(path)) original[path] = before
                current[path] = to
            }
            for ((path, hash) in added) {
                val h = (hash as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (!h.matches(HEX64)) { out += "hash-form $path"; continue }
                if (path in current) out += "added-existing $path"
                if (!original.containsKey(path)) original[path] = null
                current[path] = h
            }
        }
        if (out.isNotEmpty()) return out to null
        return out to Ledger(original, original.keys.associateWith { current.getValue(it) })
    }

    /** Error codes for the actual sources against [ledger]: every approved path present, regular, at its final bytes, KRX-free. */
    fun actualErrors(ledger: Ledger, actual: Map<String, ByteArray>, appRoot: File = File(".")): List<String> {
        val out = mutableListOf<String>()
        for ((path, want) in ledger.final) {
            val bytes = actual[path]
            if (bytes == null) { out += "missing $path"; continue }
            if (sha(bytes) != want) out += "actual-hash $path"
            val file = File(appRoot, path)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.canonicalFile != file.absoluteFile.normalize())
                out += "alias $path"
            val text = bytes.toString(Charsets.UTF_8)
            // Only the exception's exact history-to-final pair is pinned by its KRX lines; every other path stays KRX-free.
            val core = CORE_EXCEPTIONS.firstOrNull { it.path == path && ledger.original[path] == it.from && want == it.to }
            if (core != null) {
                if (krxLinesSha256(text) != core.krxLinesSha256) out += "core-krx-lines $path"
            } else if (KRX.containsMatchIn(codeAndStrings(text))) out += "krx-reference $path"
        }
        return out
    }

    /** Asserts [actualErrors] is empty for the actual production sources. */
    fun requireExact(actual: Map<String, ByteArray>) =
        assertEquals("contract(post-S6): approved changes are exactly present", emptyList<String>(), actualErrors(ledger(), actual))

    /**
     * For the historical fingerprints only: an approved path whose actual hash equals its final approved hash goes back to its
     * history hash, an approved added path at its exact hash is dropped. Every other path and hash is returned as is.
     */
    fun project(hashes: Map<String, String>, ledger: Ledger = ledger()): Map<String, String> =
        hashes.filterNot { (path, hash) -> path in ledger.added && hash == ledger.final[path] }
            .mapValues { (path, hash) -> if (path in ledger.changed && hash == ledger.final[path]) ledger.original.getValue(path)!! else hash }

    /** The actual production source bytes, over the S6 freeze roots, relative to the app module. */
    fun actualSources(): Map<String, ByteArray> = S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.associate { it.invariantSeparatorsPath to it.readBytes() }

    private val KRX = Regex("(?i)krx")

    /** Kotlin source with comments removed and every string or char literal kept, so a reference cannot hide in either. */
    fun codeAndStrings(source: String): String {
        class Scanner {
            val out = StringBuilder(source.length)
            var i = 0
            fun at(text: String) = source.startsWith(text, i)
            fun copy(count: Int) {
                val end = minOf(i + count, source.length)
                out.append(source, i, end)
                i = end
            }
            fun lineComment() {
                out.append(' ')
                while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
            }
            fun blockComment() {
                out.append(' ')
                var depth = 0
                while (i < source.length) {
                    when {
                        at("/*") -> { depth++; i += 2 }
                        at("*/") -> { depth--; i += 2; if (depth == 0) return }
                        else -> i++
                    }
                }
            }
            fun quoted(quote: Char, raw: Boolean = false) {
                val delimiter = if (raw) "\"\"\"" else quote.toString()
                copy(delimiter.length)
                while (i < source.length) {
                    when {
                        at(delimiter) -> {
                            var count = delimiter.length
                            if (raw) while (source.getOrNull(i + count) == '"') count++
                            copy(count)
                            return
                        }
                        !raw && source[i] == '\\' -> {
                            val unicode = if (at("\\u") && i + 6 <= source.length)
                                source.substring(i + 2, i + 6).toIntOrNull(16) else null
                            if (unicode != null) {
                                out.append(unicode.toChar())
                                i += 6
                            } else copy(2)
                        }
                        quote == '"' && at("\${") -> {
                            copy(2)
                            code(template = true)
                        }
                        else -> copy(1)
                    }
                }
            }
            fun code(template: Boolean) {
                var braces = 0
                while (i < source.length) {
                    when {
                        at("//") -> lineComment()
                        at("/*") -> blockComment()
                        at("\"\"\"") -> quoted('"', raw = true)
                        source[i] == '"' || source[i] == '\'' || source[i] == '\u0060' -> quoted(source[i])
                        template && source[i] == '{' -> { braces++; copy(1) }
                        template && source[i] == '}' -> {
                            copy(1)
                            if (braces == 0) return
                            braces--
                        }
                        else -> copy(1)
                    }
                }
            }
        }
        val scanner = Scanner()
        scanner.code(template = false)
        return scanner.out.toString()
    }

    private fun pathForm(path: String) = path.startsWith(BASE) && path.endsWith(".kt") && !path.startsWith("/") && '\\' !in path &&
        '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    fun sha(bytes: ByteArray): String = S6Krx3bOffWiringContractTest.sha(bytes)
}

/** The S64b delta resource bytes, read the same way the S64b helper reads its sibling inventory. */
internal object S64bResourceBytes {
    fun delta(): ByteArray {
        val stream = S64bResourceBytes::class.java.classLoader.getResourceAsStream("krx/s64b/fixture_delta.json")
        assertNotNull("precondition(post-S6): krx/s64b/fixture_delta.json exists", stream)
        return stream!!.use { it.readBytes() }
    }
}
