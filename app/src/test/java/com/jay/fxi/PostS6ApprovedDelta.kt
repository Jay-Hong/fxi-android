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
    const val APPROVED_SHA256 = "82e5bf9ed625a75119d26e5c24abd3aacb0b6aaea3d691f3ef4d7072575100c8"
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
     * - d9b0bd9d…: 530b4333 BankAlertSettingsOwner.kt, no KRX at all (comments included).
     * - 3b9ef40d…: 530b4333 AlertSection.kt, no KRX at all (comments included).
     * - dcef69a2…: 530b4333 PremiumTopicRoute.kt, no KRX at all (comments included).
     * - 7376946b…: 9f30832d AuthenticatedApiService.kt, KRX only in the comment at :349 (graph catalog kdoc).
     * - 07f599cf…: 9f30832d AlertRepository.kt, KRX only in the comment at :55 (source alerts kdoc).
     * - 576aa7dd…: 9f30832d BankAlertSettingsOwner.kt, no KRX at all (comments included).
     * - 6daaff32…: 9f30832d AlertSection.kt, no KRX at all (comments included).
     * - 4a93a045…: 9f30832d BankAlertSection.kt, no KRX at all (comments included).
     * - b437782a…: e06fbb02 AlertFamilyRefresherRegistry.kt, no KRX at all (comments included).
     * - 59fae3be…: e06fbb02 BankAlertSettingsOwner.kt, no KRX at all (comments included).
     * - bc27950c…: 222c520a BankAlertSettingsOwner.kt, no KRX at all (comments included).
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
        "26aab0a503e6514df01d2006d2f27a2a5c32a4db70a7f80f72b5eb208ce2c242",
        "d9b0bd9dd13013116fb22a55dd8bbc082b9d16e6bb520d0006c0d323c65f1e3c",
        "3b9ef40d5735de6f7adc70bb293ff5221ac4543396347feba8b6776b32e35b3d",
        "dcef69a23db7b6c637161303c6bd0557bc6ab5591d4daf1057e5b64270e4f628",
        "7376946bf30934ec3fcdab88abe6bbd7dd47a0756e0cb108c97a395dbeee6ba9",
        "07f599cf0e454d5e8ee12490db4b4b4a7486833d7f7a5cba52273afe00b9b0d4",
        "576aa7ddfd66560aada3ab85071c9920517c0ba6938e0c0418a675ff24fec80f",
        "6daaff3217367923e4df4d052cc096c75c09deaebacb4c77d00e0535613068f1",
        "4a93a04557d9470addb0bff083204bc0433f3b433c893e7ca60b5c911eebc4ec",
        "b437782aa4648c50756f2f6d7ffbda1e516d4ecb16dc4845ec172e28de23ed11",
        "59fae3be294672e42230faf34979fb1a344509702d766d298fe5c9897fec1831",
        "bc27950c5be8cc77f63b1943c2f4bd0843318f7523cd327bc06c960e11554f52"
    )

    /**
     * A forbidden core change this ledger may carry, in its one unit only — that unit's id, source parent and approval record —
     * and only from and to these exact bytes. In place of the KRX-free rule its comment-free KRX lines are pinned by
     * [krxLinesSha256] ([krxLinesSha256] of the text). The forbidden scope itself is unchanged.
     * A path may continue in a later unit only through that later unit's separately pinned CoreException. parse validates every
     * predecessor; actualErrors uses the last changed entry's from and the final to. Projection still uses the original history hash.
     * - S7-c F1 (`R4c/S7/user_decision_s7c_F1.r1.md` cc93e4f7…): TopicRuntimeOwner.kt.
     * - S7-N1 (`R4c/S7/s7n1/s7n1_scope.confirmed.r1.md` 1ecc6234…, user decision `R4c/S7/user_decision_s7n1.r1.md` E1):
     *   FXiMessagingService.kt, the `sync_alerts` hand-over after the emit.
     * - S7-E5a2 (`R4c/S7/s7e5/s7e5a2_scope.confirmed.r1.md` 16ae2162…, user decision `R4c/S7/user_decision_s7e5next.r1.md` G1):
     *   AuthenticatedTransport.kt, the optional use check of one mutation's send. The file names no KRX, so its pinned KRX lines are
     *   the empty text.
     * - S7-E5c (`R4c/S7/s7e5/s7e5c_exact_proposal.claude.r3.md` d92df63a…, user decision `R4c/S7/user_decision_s7e5c.r1.md` D1/D2
     *   with its errata): FXiMessagingService.kt again, its second link from S7-N1's to (the raw setting_id/is_repeat hand-over and
     *   the app-made notification's is_repeat), and MainActivity.kt, its first link (the OFF parser's deliverTap and is_repeat removal).
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
        ),
        CoreException(
            unitId = "S7-E5a2",
            sourceParent = "9f30832d1a14f08940619c35ce79f6fbdf136505",
            approvalRecordSha256 = "16ae21627bfd6cfdad0cfb9d2a2d6aedb0fe443c6566ead1caf8519440a91128",
            path = BASE + "data/remote/AuthenticatedTransport.kt",
            from = "49dc53a37dc341be829965fd4a6123aee5f93d0ac7f678197aa3df0f4542b041",
            to = "9699188899118ce692c3ee583ad69b521d08b240493dc2e41f744e09bb331023",
            krxLinesSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        ),
        CoreException(
            unitId = "S7-E5c",
            sourceParent = "e06fbb0202ce9af0da72d4f52640b233d711c8c5",
            approvalRecordSha256 = "d92df63aa74097d2342511c0ecdeda141c20fbd973f321b3ede5f5a94c3f017e",
            path = BASE + "service/FXiMessagingService.kt",
            from = "9f3ecfc91728b9bc30ad8652b2a0c82d5d53251b565a7babba543a3fcb06b6fe",
            to = "8f05d0470162f67573e29499f54ce0de6028f9e863dbea1aee36b4a5588d5ee6",
            krxLinesSha256 = "08e142a551e0e47752a09853de243aa65b727b5863c75171d1cb7157ece13d02"
        ),
        CoreException(
            unitId = "S7-E5c",
            sourceParent = "e06fbb0202ce9af0da72d4f52640b233d711c8c5",
            approvalRecordSha256 = "d92df63aa74097d2342511c0ecdeda141c20fbd973f321b3ede5f5a94c3f017e",
            path = BASE + "MainActivity.kt",
            from = "29d3e6c4d779b3e3bd8c0d1414547f9228adef74dff6d499410cbe79a1adca82",
            to = "363279e89b869c63f4c5a7e59dff81db858e5cb00da41ec9fd3d6f00aba3854b",
            krxLinesSha256 = "e61ab21d19206fde24d0b2a8d74e0e75def7397c1ac1d3be33f8514b07b725aa"
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
    class Ledger(
        val original: Map<String, String?>,
        val final: Map<String, String>,
        val lastFrom: Map<String, String?> = original
    ) {
        val changed: Set<String> get() = original.filterValues { it != null }.keys
        val added: Set<String> get() = original.filterValues { it == null }.keys
    }

    /**
     * S7-E5c's O04s exception (design `s7e5c_exact_proposal.claude.r3.md` §3): the approved bytes of exactly the FCM builder,
     * parser and clear spans, each over its untouched 0302 raw. The resource's bytes are pinned here; changing either is critical.
     */
    const val O04S_RESOURCE = "krx/post_s6/o04s_approved.json"
    const val O04S_APPROVED_SHA256 = "15bab3edfcad9f6ade31b30695fc199f1f632a7fad0aa5bf10f561507e81f806"
    val O04S_SPANS: Set<String> = setOf("FCM", "parser", "clear")

    class O04sApproval(val frozenSha256: String, val approvedSha256: String)

    fun o04sRaw(): ByteArray {
        val stream = PostS6ApprovedDelta::class.java.classLoader.getResourceAsStream(O04S_RESOURCE)
        assertNotNull("precondition(post-S6): $O04S_RESOURCE exists", stream)
        return stream!!.use { it.readBytes() }
    }

    /** The approved span list, validated byte for byte against its pin before any use; no list at all on any error. */
    fun o04sApproved(raw: ByteArray, expectedPin: String? = O04S_APPROVED_SHA256): Pair<List<String>, Map<String, O04sApproval>?> {
        val out = mutableListOf<String>()
        if (expectedPin != null && sha(raw) != expectedPin) out += "o04s-pin"
        val text = raw.toString(Charsets.UTF_8)
        val duplicates = try { S64bFixtureDelta.RawKeys(text).scan() } catch (invalid: IllegalArgumentException) {
            return (out + "o04s-raw-json ${invalid.message}") to null
        }
        if (duplicates.isNotEmpty()) return (out + duplicates.map { "o04s-duplicate-key $it" }) to null
        val json = try { Json.parseToJsonElement(text) as? JsonObject } catch (invalid: IllegalArgumentException) { null }
            ?: return (out + "o04s-raw-json not an object") to null
        if (json.keys != O04S_SPANS) out += "o04s-spans ${json.keys.sorted()}"
        val approvals = linkedMapOf<String, O04sApproval>()
        for ((id, element) in json) {
            val entry = element as? JsonObject
            if (entry == null || entry.keys != setOf("unit", "frozen_sha256", "approved_sha256")) { out += "o04s-entry $id"; continue }
            if (entry.string("unit") != "S7-E5c") out += "o04s-unit $id"
            val frozen = entry.string("frozen_sha256"); val approved = entry.string("approved_sha256")
            if (!frozen.matches(HEX64) || !approved.matches(HEX64)) { out += "o04s-hash-form $id"; continue }
            approvals[id] = O04sApproval(frozen, approved)
        }
        return out to approvals.takeIf { out.isEmpty() }
    }

    /**
     * O04s's byte rule for one span: its exact frozen raw, unless the approved list names it; then its UTF-8 bytes hash to the
     * approved value and the frozen raw is the very one the list and the 0302 index both name.
     */
    fun o04sSpanErrors(id: String, frozen: ByteArray, indexSha256: String?, actual: String, approvals: Map<String, O04sApproval>): List<String> {
        val approval = approvals[id]
            ?: return if (frozen.contentEquals(actual.toByteArray(Charsets.UTF_8))) emptyList() else listOf("o04s-bytes $id")
        val out = mutableListOf<String>()
        if (sha(frozen) != approval.frozenSha256 || indexSha256 != approval.frozenSha256) out += "o04s-frozen $id"
        if (sha(actual.toByteArray(Charsets.UTF_8)) != approval.approvedSha256) out += "o04s-approved $id"
        return out
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
        val lastFrom = linkedMapOf<String, String?>()
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
                lastFrom[path] = from
                current[path] = to
            }
            for ((path, hash) in added) {
                val h = (hash as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (!h.matches(HEX64)) { out += "hash-form $path"; continue }
                if (path in current) out += "added-existing $path"
                if (!original.containsKey(path)) original[path] = null
                lastFrom[path] = null
                current[path] = h
            }
        }
        if (out.isNotEmpty()) return out to null
        return out to Ledger(original, original.keys.associateWith { current.getValue(it) }, lastFrom)
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
            // parse validates every unit and predecessor; only the last exact pair gets its pinned KRX lines.
            val core = CORE_EXCEPTIONS.firstOrNull { it.path == path && ledger.lastFrom[path] == it.from && want == it.to }
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
