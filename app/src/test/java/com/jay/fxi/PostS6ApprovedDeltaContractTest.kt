package com.jay.fxi

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned contract of the post-S6 approved delta (user decision `R4c/S7/user_decision_s6freeze_A.r1.md`, design
 * `s6freeze.codex.r1.md` §4.4). Every negative first sees the unaltered input validate, then changes one thing and requires the
 * validator's own error for it — never a GREEN that was red already.
 */
class PostS6ApprovedDeltaContractTest {
    private val base = S6Krx3bOffWiringContractTest.BASE
    private val api = base + "data/remote/AuthenticatedApiService.kt"
    private val repo = base + "data/repository/AlertRepository.kt"
    private val access = base + "data/repository/BankHistoryAccess.kt"
    private val s7b = listOf("BankHistoryRow.kt", "BankHistorySheet.kt", "BankHistoryStateHolder.kt").map { base + "ui/alert/$it" }.toSet()
    private val owner = base + "data/remote/TopicRuntimeOwner.kt"
    private val s7cChanged = setOf(owner, base + "ui/premium/view/PremiumTopicRoute.kt", base + "ui/settings/SettingsViewModel.kt",
        base + "ui/premium/PremiumTopicConsumer.kt", base + "ui/premium/view/PremiumTopicScreen.kt")
    private val s7cAdded = setOf(base + "data/repository/DeletionAdmissionRevisions.kt", base + "ui/alert/BankHistoryEntry.kt")
    private val inventory = S64bFixtureDelta.inventoryBytes()
    private val s64b = S64bResourceBytes.delta()
    private val raw = PostS6ApprovedDelta.raw()
    private val json = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject

    private fun errors(input: JsonObject, pin: String? = null) =
        PostS6ApprovedDelta.parse(input.toString().toByteArray(), inventory, s64b, pin).first
    private fun errorsRaw(input: String, pin: String? = null) = PostS6ApprovedDelta.parse(input.toByteArray(), inventory, s64b, pin).first

    private fun unit(index: Int = 0) = json.getValue("units").jsonArray[index].jsonObject
    private fun withUnits(vararg units: JsonObject) = JsonObject(json + ("units" to JsonArray(units.toList())))
    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))
    private fun changedEntry(from: String, to: String) = JsonObject(mapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(to)))
    private val someHash = "0".repeat(63) + "1"
    private val productBase: Map<String, String> =
        Json.parseToJsonElement(s64b.toString(Charsets.UTF_8)).jsonObject.getValue("product_base").jsonObject.mapValues { it.value.jsonPrimitive.content }

    @org.junit.Before fun theUnalteredInputMustBeGreen() {
        val (found, parsed) = PostS6ApprovedDelta.parse(raw, inventory, s64b, PostS6ApprovedDelta.APPROVED_SHA256)
        assertEquals("precondition: approved manifest", emptyList<String>(), found)
        assertEquals("precondition: approved actual sources", emptyList<String>(),
            PostS6ApprovedDelta.actualErrors(checkNotNull(parsed), PostS6ApprovedDelta.actualSources()))
    }

    @Test fun P01_theApprovedLedgerValidates_andMatchesTheActualSourcesExactly() {
        assertEquals("the pin is the manifest's bytes", PostS6ApprovedDelta.APPROVED_SHA256, PostS6ApprovedDelta.sha(raw))
        val ledger = PostS6ApprovedDelta.ledger()
        assertEquals("exactly the approved S7-a, S7-b and S7-c paths", setOf(api, repo, access) + s7b + s7cChanged + s7cAdded, ledger.final.keys)
        assertEquals(setOf(api, repo) + s7cChanged, ledger.changed)
        assertEquals(setOf(access) + s7b + s7cAdded, ledger.added)
        assertEquals("history hashes", mapOf(api to productBase.getValue(api), repo to productBase.getValue(repo), access to null) +
            s7b.associateWith { null } + s7cChanged.associateWith { productBase.getValue(it) } + s7cAdded.associateWith { null }, ledger.original)
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, PostS6ApprovedDelta.actualSources()))
        val actual = PostS6ApprovedDelta.actualSources().mapValues { PostS6ApprovedDelta.sha(it.value) }
        val projected = PostS6ApprovedDelta.project(actual, ledger)
        assertEquals("changed paths go back to their history", productBase.getValue(api), projected[api])
        assertEquals(productBase.getValue(repo), projected[repo])
        for (path in s7cChanged) assertEquals("S7-c $path goes back to its history", productBase.getValue(path), projected[path])
        assertFalse("the approved additions are dropped from the history view", (setOf(access) + s7b + s7cAdded).any { it in projected })
        assertEquals("nothing else moves", actual - (setOf(api, repo, access) + s7b + s7cChanged + s7cAdded),
            projected - (setOf(api, repo) + s7cChanged))
    }

    @Test fun N01_theApprovalPinBindsTheManifestBytes() {
        assertEquals(emptyList<String>(), errorsRaw(raw.toString(Charsets.UTF_8), PostS6ApprovedDelta.APPROVED_SHA256))
        val edited = raw.toString(Charsets.UTF_8).replace("\"note\": \"", "\"note\": \" ")
        check(edited != raw.toString(Charsets.UTF_8))
        assertEquals("a reformatted note alone", listOf("approval-pin"), errorsRaw(edited, PostS6ApprovedDelta.APPROVED_SHA256))
    }

    @Test fun N02_rawJsonDuplicatesMalformedAndTrailingInputAreRefused() {
        val text = raw.toString(Charsets.UTF_8)
        val duplicated = text.replace("\"id\": \"S7-a\",", "\"id\": \"S7-a\", \"id\": \"S7-a\",")
        check(duplicated != text)
        assertEquals(listOf("duplicate-key id"), errorsRaw(duplicated))
        val escapedDuplicate = text.replace("\"id\": \"S7-a\",", "\"id\": \"S7-a\", \"\\u0069d\": \"S7-a\",")
        check(escapedDuplicate != text)
        assertEquals(listOf("duplicate-key id"), errorsRaw(escapedDuplicate))
        assertTrue("malformed", errorsRaw(text.dropLast(3)).single().startsWith("raw-json"))
        assertTrue("trailing", errorsRaw(text + "{}").single().startsWith("raw-json"))
        assertEquals("not an object", listOf("raw-json not an object"), errorsRaw("[]"))
    }

    @Test fun N03_theShapeIsExact() {
        assertEquals(emptyList<String>(), errors(json))
        assertTrue(errors(JsonObject(json + ("extra" to JsonPrimitive(1)))).single().startsWith("keys"))
        assertEquals("a missing note fails the key set and the note", 2, errors(JsonObject(json - "note")).count { it.startsWith("keys") || it == "note" })
        assertEquals(listOf("schema"), errors(json.with("schema", JsonPrimitive("1"))))
        assertEquals(listOf("schema"), errors(json.with("schema", JsonPrimitive(2))))
        assertEquals(listOf("note"), errors(json.with("note", JsonPrimitive(3))))
        assertEquals(listOf("unit-0 keys"), errors(withUnits(JsonObject(unit() + ("extra" to JsonPrimitive(1))))))
        assertEquals(listOf("unit-parent S7-a"), errors(withUnits(unit().with("source_parent", JsonPrimitive("abc")))))
        assertEquals(listOf("unit-approval S7-a"), errors(withUnits(unit().with("approval_record_sha256", JsonPrimitive("abc")))))
        assertEquals(listOf("units-empty"), errors(withUnits()))
        assertEquals(listOf("unit-empty S7-a"), errors(withUnits(unit().with("changed", JsonObject(emptyMap())).with("added", JsonObject(emptyMap())))))
    }

    @Test fun N04_theBasePinsAreTheFrozenHistory() {
        val b = json.getValue("base").jsonObject
        assertEquals(listOf("base-inventory"), errors(json.with("base", b.with("source_inventory_sha256", JsonPrimitive(someHash)))))
        assertEquals(listOf("base-s64b"), errors(json.with("base", b.with("s64b_delta_sha256", JsonPrimitive(someHash)))))
        assertEquals(listOf("base-commit"), errors(json.with("base", b.with("product_commit", JsonPrimitive("cf8fb85")))))
        assertEquals("the actual inventory bytes are pinned too", listOf("base-inventory"),
            PostS6ApprovedDelta.parse(raw, inventory + byteArrayOf(10), s64b, null).first)
    }

    @Test fun N05_aChangeChainsFromTheHistoryAndFromTheUnitBeforeIt() {
        val changed = unit().getValue("changed").jsonObject
        val forged = changed + (api to changedEntry(someHash, changed.getValue(api).jsonObject.getValue("to").jsonPrimitive.content))
        assertTrue(errors(withUnits(unit().with("changed", JsonObject(forged)))).contains("from $api"))
        // A second unit continues from the first unit's `to`; its own `to` is reviewed as its next `from`.
        val firstTo = changed.getValue(api).jsonObject.getValue("to").jsonPrimitive.content
        val next = JsonObject(mapOf("id" to JsonPrimitive("S7-b"), "source_parent" to JsonPrimitive("1".repeat(40)),
            "approval_record_sha256" to JsonPrimitive(someHash),
            "changed" to JsonObject(mapOf(api to changedEntry(firstTo, someHash))), "added" to JsonObject(emptyMap())))
        assertEquals("a chained `from` is not reviewed until its review is recorded", listOf("krx-before-unreviewed $api"), errors(withUnits(unit(), next)))
        val skipping = next.with("changed", JsonObject(mapOf(api to changedEntry(productBase.getValue(api), someHash))))
        assertTrue("skipping the unit before is refused", errors(withUnits(unit(), skipping)).contains("from $api"))
        assertEquals("duplicate unit id", listOf("unit-id 1"), errors(withUnits(unit(), next.with("id", JsonPrimitive("S7-a")))).filter { it.startsWith("unit-id") })
    }

    @Test fun N06_anUnreviewedBeforeContentIsRefused() {
        val path = base + "domain/model/Bank.kt"
        val entry = JsonObject(mapOf(path to changedEntry(productBase.getValue(path), someHash)))
        assertEquals(listOf("krx-before-unreviewed $path"), errors(withUnits(unit().with("changed", entry).with("added", JsonObject(emptyMap())))))
    }

    @Test fun N07_s6SurfacesAreForbiddenWhateverTheirTokens() {
        val paths = listOf(base + "data/remote/DetachableGraphSinks.kt",
            base + "data/remote/TopicRuntime.kt", base + "data/entitlements/PremiumAccessCoordinator.kt",
            base + "di/EntitlementsModule.kt", base + "MainActivity.kt", base + "data/graph/GraphRecorder.kt", base + "data/auth/AuthTokenProvider.kt")
        for (path in paths) {
            val from = productBase[path] ?: error("history has $path")
            val entry = JsonObject(mapOf(path to changedEntry(from, someHash)))
            assertTrue("$path forbidden", errors(withUnits(unit().with("changed", entry).with("added", JsonObject(emptyMap())))).contains("forbidden $path"))
        }
        val added = base + "data/entitlements/Bypass.kt"
        assertTrue(errors(withUnits(unit().with("added", JsonObject(mapOf(added to JsonPrimitive(someHash)))))).contains("forbidden $added"))
    }

    @Test fun N08_pathFormsOutsideTheProductionKotlinRootAreRefused() {
        for (path in listOf("/abs/$access", base + "data/../data/repository/X.kt", base + "./X.kt", base + "data//X.kt",
                base.replace('/', '\\') + "X.kt", "src/test/java/com/jay/fxi/X.kt", base + "X.java", base + "X\u0000.kt")) {
            val result = errors(withUnits(unit().with("added", JsonObject(mapOf(path to JsonPrimitive(someHash))))))
            assertTrue("$path: $result", result.contains("path-form $path"))
        }
    }

    @Test fun N09_overlapExistingAndMissingAreRefused() {
        val added = unit().getValue("added").jsonObject
        assertTrue(errors(withUnits(unit().with("added", JsonObject(added + (api to JsonPrimitive(someHash)))))).any { it.startsWith("overlap") })
        val existing = base + "domain/model/Bank.kt"
        assertTrue(errors(withUnits(unit().with("added", JsonObject(added + (existing to JsonPrimitive(someHash)))))).contains("added-existing $existing"))
        val missing = base + "data/repository/Nowhere.kt"
        val changed = unit().getValue("changed").jsonObject + (missing to changedEntry(someHash, someHash))
        assertTrue(errors(withUnits(unit().with("changed", JsonObject(changed)))).contains("changed-missing $missing"))
    }

    @Test fun A01_theActualSourcesMustCarryExactlyTheApprovedBytes() {
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, actual))
        for (path in ledger.final.keys) {
            assertEquals(listOf("actual-hash $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))))
            assertEquals(listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
        }
        for (path in ledger.changed) {
            val revertedHash = checkNotNull(ledger.original[path])
            val hashesAtHistory = actual.mapValues { PostS6ApprovedDelta.sha(it.value) } + (path to revertedHash)
            assertEquals("projection alone is not exact validation", revertedHash,
                PostS6ApprovedDelta.project(hashesAtHistory, ledger)[path])
        }
        // The projection never hides a change that is not exactly approved.
        val hashes = actual.mapValues { PostS6ApprovedDelta.sha(it.value) }
        val altered = hashes + (repo to someHash) + (access to someHash) + (base + "service/Bypass.kt" to someHash)
        val projected = PostS6ApprovedDelta.project(altered, ledger)
        assertEquals("an altered approved file stays visible", someHash, projected[repo])
        assertEquals("an approved addition at other bytes stays visible", someHash, projected[access])
        assertEquals("an unlisted file stays visible", someHash, projected[base + "service/Bypass.kt"])
    }

    @Test fun A02_aKrxReferenceInCodeOrStringsIsRefused_commentsAreNot() {
        val actual = PostS6ApprovedDelta.actualSources()
        val cases = listOf(
            "an identifier" to "\nval k: KrxSendMarker? = null\n",
            "code after a raw string ending with four quotes" to
                "\nval raw = \"\"\"\"x\"\"\"\"; val next = \"//\"; val hidden: com.jay.fxi.data.entitlements.KrxSendMarker? = null\n",
            "a template expression with a nested string" to
                "\nval text = \"${'$'}{\"//\"}\"; val hidden: KrxSendMarker? = null\n",
            "a Unicode-escaped topic" to
                "\nval topic = \"\\u006b\\u0072\\u0078:usd-krw-futures\"\n",
            "code after a CR-terminated line comment" to
                "\n// note\rval hidden: KrxSendMarker? = null\n",
            "code after a string with an escaped quote and //" to
                "\nval s = \"a\\\"//\"; val hidden: KrxSendMarker? = null\n",
            "code after a char literal holding a double quote" to
                "\nval c = '\"'; val hidden: KrxSendMarker? = null\n",
            "code after a raw string whose template holds //" to
                "\nval r = \"\"\"${'$'}{\"//\"}\"\"\"; val hidden: KrxSendMarker? = null\n",
            "code after a line comment holding a quote" to
                "\n// say \"hi\nval hidden: KrxSendMarker? = null\n",
            "a constant" to "\nconst val KRX_TOPIC = 1\n",
            "a string" to "\nval t = \"krx:usd-krw-futures\"\n",
            "a string after //" to "\nval u = \"https://x.example/krx\"\n",
            "a raw string" to "\nval r = \"\"\"\n krx \n\"\"\"\n",
            "an import alias" to "\nimport com.jay.fxi.data.entitlements.KrxSendMarker as Marker\n"
        )
        for ((label, addition) in cases) {
            val bytes = actual.getValue(access) + addition.toByteArray()
            // The ledger agrees with the altered bytes, so only the KRX rule can refuse them.
            val ledger = PostS6ApprovedDelta.Ledger(mapOf(access to null), mapOf(access to PostS6ApprovedDelta.sha(bytes)))
            assertEquals(label, listOf("krx-reference $access"), PostS6ApprovedDelta.actualErrors(ledger, actual + (access to bytes)))
        }
        for ((label, addition) in listOf("a line comment" to "\n// KRX note\n", "a nested block comment" to "\n/* outer /* KRX */ still */\n",
                "a kdoc" to "\n/** krx */\n",
                "a block comment holding a quote" to "\n/* \" KRX */\n")) {
            val bytes = actual.getValue(access) + addition.toByteArray()
            val ledger = PostS6ApprovedDelta.Ledger(mapOf(access to null), mapOf(access to PostS6ApprovedDelta.sha(bytes)))
            assertEquals("$label is not a reference", emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, actual + (access to bytes)))
        }
        assertTrue("positive control: the shared files mention KRX in comments",
            String(actual.getValue(api)).contains("KRX") && String(actual.getValue(repo)).contains("KRX"))
    }

    @Test fun N10_aGreenTwoUnitChainRejectsReordering() {
        val apiBefore = productBase.getValue(api)
        val intermediate = productBase.getValue(repo)
        val first = unit()
            .with("changed", JsonObject(mapOf(api to changedEntry(apiBefore, intermediate))))
            .with("added", JsonObject(emptyMap()))
        val second = unit()
            .with("id", JsonPrimitive("S7-next"))
            .with("changed", JsonObject(mapOf(api to changedEntry(intermediate, someHash))))
            .with("added", JsonObject(emptyMap()))
        val valid = withUnits(first, second)
        val (found, folded) = PostS6ApprovedDelta.parse(valid.toString().toByteArray(), inventory, s64b, null)
        assertEquals("precondition: the synthetic chain is green", emptyList<String>(), found)
        val ledger = checkNotNull(folded)
        assertEquals(apiBefore, ledger.original[api])
        assertEquals(someHash, ledger.final[api])
        assertTrue(errors(withUnits(second, first)).contains("from $api"))
    }

    @Test fun N11_matchingActualAndManifestCannotReplaceTheApprovalPin() {
        val actual = PostS6ApprovedDelta.actualSources()
        val approved = PostS6ApprovedDelta.ledger()
        val alteredBytes = actual.getValue(access) + byteArrayOf(10)
        val changedRaw = raw.toString(Charsets.UTF_8)
            .replace(approved.final.getValue(access), PostS6ApprovedDelta.sha(alteredBytes)).toByteArray()
        check(!raw.contentEquals(changedRaw))
        val (withoutPin, alteredLedger) = PostS6ApprovedDelta.parse(changedRaw, inventory, s64b, null)
        assertEquals("precondition: only the approval pin is violated", emptyList<String>(), withoutPin)
        assertEquals(emptyList<String>(),
            PostS6ApprovedDelta.actualErrors(checkNotNull(alteredLedger), actual + (access to alteredBytes)))
        assertEquals(listOf("approval-pin"),
            PostS6ApprovedDelta.parse(changedRaw, inventory, s64b, PostS6ApprovedDelta.APPROVED_SHA256).first)
    }

    /** R1: each D20 history branch checks the approved overlay itself, so running it alone cannot accept a missing addition. */
    @Test fun A04_eachD20HistoryBranchAloneRefusesAMissingApprovedAddition() {
        val inventoryJson = Json.parseToJsonElement(inventory.toString(Charsets.UTF_8)).jsonObject
        val delta = S64bFixtureDelta.load()
        val actual = PostS6ApprovedDelta.actualSources()
        val d20 = S6KrxReagreementContractTest()
        for (sub in listOf("source", "protected337", "protected349", "product-base")) {
            d20.assertActual(sub, inventoryJson, delta, actual)
            val refused = runCatching { d20.assertActual(sub, inventoryJson, delta, actual - access) }.exceptionOrNull()
            assertTrue("$sub alone refuses a missing approved addition: $refused",
                refused is AssertionError && refused.message.orEmpty().contains("approved post-S6 changes are exactly present"))
        }
    }

    @Test fun A03_anAliasOrSpecialFileIsRefused() {
        // Canonical: on macOS the temp root itself sits behind /var -> /private/var, which is not the alias under test.
        val root = Files.createTempDirectory("post-s6").toFile().canonicalFile
        try {
            val actual = PostS6ApprovedDelta.actualSources()
            val ledger = PostS6ApprovedDelta.ledger()
            for (path in ledger.final.keys) File(root, path).apply { parentFile.mkdirs() }.writeBytes(actual.getValue(path))
            assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, actual, root))
            val target = File(root, "elsewhere.kt").apply { writeBytes(actual.getValue(access)) }
            File(root, access).delete()
            Files.createSymbolicLink(File(root, access).toPath(), target.toPath())
            assertEquals(listOf("alias $access"), PostS6ApprovedDelta.actualErrors(ledger, actual, root))
            Files.delete(File(root, access).toPath())
            Files.createDirectory(File(root, access).toPath())
            assertEquals("a directory cannot be a regular source", listOf("alias $access"),
                PostS6ApprovedDelta.actualErrors(ledger, actual, root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- S7-c F1 core exception (`R4c/S7/s7c_api_proposal.claude.r2.md` §3) ----------------------------------------------

    private val s7c get() = unit(2)
    private val ownerEntry get() = s7c.getValue("changed").jsonObject.getValue(owner).jsonObject
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun s7cWithOwner(entry: JsonObject) =
        s7c.with("changed", JsonObject(s7c.getValue("changed").jsonObject + (owner to entry)))
    private fun unitsWithS7c(changedS7c: JsonObject) = withUnits(unit(0), unit(1), changedS7c)

    @Test fun E01_theExactCoreExceptionValidates_andItsKrxLinesAreTheReviewedOnes() {
        val core = PostS6ApprovedDelta.CORE_EXCEPTIONS.single()
        assertEquals("premise: the third unit is S7-c", "S7-c", s7c.str("id"))
        assertEquals(listOf(core.unitId, core.sourceParent, core.approvalRecordSha256, core.path, core.from, core.to),
            listOf(s7c.str("id"), s7c.str("source_parent"), s7c.str("approval_record_sha256"), owner, ownerEntry.str("from"), ownerEntry.str("to")))
        assertEquals("the reviewed KRX lines of the history bytes", "234ddcf3be22830a470b42544091ab75bed096e27e420f9dee5158bc1f307260", core.krxLinesSha256)
        assertEquals("the final bytes keep them", core.krxLinesSha256, PostS6ApprovedDelta.krxLinesSha256(File(owner).readText()))
        assertEquals(emptyList<String>(), errors(json))
        for (path in s7cChanged - owner) assertTrue("$path's history is reviewed KRX-free", productBase.getValue(path) in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the owner's history is not on the KRX-free list", core.from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
    }

    @Test fun E02_E03_anotherToOrFromIsForbidden() {
        val to = errors(unitsWithS7c(s7cWithOwner(changedEntry(ownerEntry.str("from"), someHash))))
        assertTrue("another to: $to", to.containsAll(listOf("forbidden $owner", "krx-before-unreviewed $owner")))
        val from = errors(unitsWithS7c(s7cWithOwner(changedEntry(someHash, ownerEntry.str("to")))))
        assertTrue("another from: $from", from.containsAll(listOf("forbidden $owner", "from $owner", "krx-before-unreviewed $owner")))
    }

    @Test fun E04_E05_theSameBytesUnderAnotherPathOrAsAnAdditionAreForbidden() {
        val runtime = base + "data/remote/TopicRuntime.kt"
        val moved = s7c.with("changed", JsonObject(s7c.getValue("changed").jsonObject - owner + (runtime to ownerEntry)))
        assertTrue(errors(unitsWithS7c(moved)).contains("forbidden $runtime"))
        val other = base + "data/remote/TopicRuntimeOwnerCopy.kt"
        val asAdded = s7c.with("added", JsonObject(s7c.getValue("added").jsonObject + (other to JsonPrimitive(ownerEntry.str("to")))))
        assertTrue(errors(unitsWithS7c(asAdded)).contains("forbidden $other"))
        val ownerAdded = s7c.with("changed", JsonObject(s7c.getValue("changed").jsonObject - owner))
            .with("added", JsonObject(s7c.getValue("added").jsonObject + (owner to JsonPrimitive(ownerEntry.str("to")))))
        assertTrue("the owner itself as an addition", errors(unitsWithS7c(ownerAdded)).contains("forbidden $owner"))
    }

    @Test fun E06_theExceptionPathsKrxLinesArePinned() {
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        val text = actual.getValue(owner).toString(Charsets.UTF_8)
        assertTrue("premise", "graph.krxGraphIngress)" in text)
        val edited = text.replace("graph.krxGraphIngress)", "graph.krxGraphIngress )").toByteArray()
        assertEquals("a KRX line edited", listOf("actual-hash $owner", "core-krx-lines $owner"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (owner to edited)))
        val more = (text + "\nprivate val krxBypass = 1\n").toByteArray()
        assertEquals("a KRX line added", listOf("actual-hash $owner", "core-krx-lines $owner"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (owner to more)))
        val plain = (text + "\n// a comment with krx in it\n").toByteArray()
        assertEquals("any other byte", listOf("actual-hash $owner"), PostS6ApprovedDelta.actualErrors(ledger, actual + (owner to plain)))
    }

    @Test fun E08_theExceptionBelongsToItsOneUnit() {
        assertTrue("another id", errors(unitsWithS7c(s7c.with("id", JsonPrimitive("S7-x")))).contains("forbidden $owner"))
        assertTrue("another parent", errors(unitsWithS7c(s7c.with("source_parent", JsonPrimitive("1".repeat(40))))).contains("forbidden $owner"))
        assertTrue("another approval", errors(unitsWithS7c(s7c.with("approval_record_sha256", JsonPrimitive(someHash)))).contains("forbidden $owner"))
        // Moved whole into the unit before: S7-b's provenance, the same path and bytes.
        val s7b = unit(1).with("changed", JsonObject(mapOf(owner to ownerEntry)))
        val rest = s7c.with("changed", JsonObject(s7c.getValue("changed").jsonObject - owner))
        assertTrue("moved to S7-b", errors(withUnits(unit(0), s7b, rest)).contains("forbidden $owner"))
    }

    @Test fun E09_aLaterUnitCannotContinueTheExceptionAndAnotherPairGetsNoKrxPass() {
        val next = JsonObject(mapOf("id" to JsonPrimitive("S7-d"), "source_parent" to JsonPrimitive("1".repeat(40)),
            "approval_record_sha256" to JsonPrimitive(someHash),
            "changed" to JsonObject(mapOf(owner to changedEntry(ownerEntry.str("to"), someHash))), "added" to JsonObject(emptyMap())))
        assertTrue(errors(withUnits(unit(0), unit(1), s7c, next)).contains("forbidden $owner"))
        val actual = PostS6ApprovedDelta.actualSources()
        val other = PostS6ApprovedDelta.Ledger(mapOf(owner to ownerEntry.str("from")), mapOf(owner to PostS6ApprovedDelta.sha(actual.getValue(owner) + byteArrayOf(10))))
        assertEquals("another final for the owner is KRX-checked as any path", listOf("krx-reference $owner"),
            PostS6ApprovedDelta.actualErrors(other, actual + (owner to actual.getValue(owner) + byteArrayOf(10))))
        // The approved final over another history (only a hand-built ledger reaches it): no KRX pass either.
        val otherFrom = PostS6ApprovedDelta.Ledger(mapOf(owner to someHash), mapOf(owner to ownerEntry.str("to")))
        assertEquals("another history for the owner is KRX-checked as any path", listOf("krx-reference $owner"),
            PostS6ApprovedDelta.actualErrors(otherFrom, actual))
    }
}
