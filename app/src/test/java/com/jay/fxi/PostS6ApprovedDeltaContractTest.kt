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
    private val messaging = base + "service/FXiMessagingService.kt"
    private val application = base + "FXiApplication.kt"
    /** S7-N1's changes to paths no unit before it changed; its api and repo changes continue S7-a's chain. */
    private val n1Changed = setOf(application, messaging)
    private val n1Added = listOf("AlertLoadPhase.kt", "AlertRetryPolicy.kt", "AlertRateLimitFloor.kt", "AlertSettingsAccess.kt",
        "AlertFamilyRefresherRegistry.kt", "BankAlertSettingsOwner.kt").map { base + "data/alerts/$it" }.toSet()
    private val bankOwner = base + "data/alerts/BankAlertSettingsOwner.kt"
    private val route = base + "ui/premium/view/PremiumTopicRoute.kt"
    private val alertSection = base + "ui/alert/AlertSection.kt"
    /** S7-E5a1's change to a path no unit before it changed; its owner change continues S7-N1's addition, its route change S7-c's chain. */
    private val e5a1Changed = setOf(alertSection)
    private val e5a1Added = setOf(base + "ui/alert/BankAlertSection.kt")
    private val transport = base + "data/remote/AuthenticatedTransport.kt"
    private val bankSection = base + "ui/alert/BankAlertSection.kt"
    private val e5a2Changed = setOf(transport, api, repo, bankOwner, alertSection, bankSection)
    private val activity = base + "MainActivity.kt"
    private val registry = base + "data/alerts/AlertFamilyRefresherRegistry.kt"
    private val e5cChanged = setOf(messaging, activity, registry, bankOwner)
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
        assertEquals("exactly the approved S7-a, S7-b, S7-c, S7-N1, S7-E5a1, S7-E5a2, S7-E5c, S7-E5b2a and S7-E5b2b1 paths (S7-E5b2b2 changes two of them again)",
            setOf(api, repo, access) + s7b + s7cChanged + s7cAdded + n1Changed + n1Added + e5a1Changed + e5a1Added + transport + activity +
                pushManager + pushCoordinator + e5b2aAdded + settingsScreen + e5b2b1Added,
            ledger.final.keys)
        assertEquals(setOf(api, repo) + s7cChanged + n1Changed + e5a1Changed + transport + activity + pushManager + pushCoordinator +
            settingsScreen, ledger.changed)
        assertEquals(setOf(access) + s7b + s7cAdded + n1Added + e5a1Added + e5b2aAdded + e5b2b1Added, ledger.added)
        assertEquals("history hashes", mapOf(api to productBase.getValue(api), repo to productBase.getValue(repo), access to null) +
            s7b.associateWith { null } + s7cChanged.associateWith { productBase.getValue(it) } + s7cAdded.associateWith { null } +
            n1Changed.associateWith { productBase.getValue(it) } + n1Added.associateWith { null } +
            e5a1Changed.associateWith { productBase.getValue(it) } + e5a1Added.associateWith { null } +
            mapOf(transport to productBase.getValue(transport), activity to productBase.getValue(activity)) +
            mapOf(pushManager to productBase.getValue(pushManager), pushCoordinator to productBase.getValue(pushCoordinator)) +
            e5b2aAdded.associateWith { null } + mapOf(settingsScreen to productBase.getValue(settingsScreen)) +
            e5b2b1Added.associateWith { null }, ledger.original)
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, PostS6ApprovedDelta.actualSources()))
        val actual = PostS6ApprovedDelta.actualSources().mapValues { PostS6ApprovedDelta.sha(it.value) }
        val projected = PostS6ApprovedDelta.project(actual, ledger)
        assertEquals("changed paths go back to their history", productBase.getValue(api), projected[api])
        assertEquals(productBase.getValue(repo), projected[repo])
        for (path in s7cChanged) assertEquals("S7-c $path goes back to its history", productBase.getValue(path), projected[path])
        for (path in n1Changed) assertEquals("S7-N1 $path goes back to its history", productBase.getValue(path), projected[path])
        for (path in e5a1Changed) assertEquals("S7-E5a1 $path goes back to its history", productBase.getValue(path), projected[path])
        assertEquals("S7-E5a2's transport goes back to its history", productBase.getValue(transport), projected[transport])
        assertEquals("S7-E5c's activity goes back to its history", productBase.getValue(activity), projected[activity])
        for (path in listOf(pushManager, pushCoordinator)) assertEquals("S7-E5b2a $path goes back to its history", productBase.getValue(path), projected[path])
        assertEquals("S7-E5b2b1's settings screen goes back to its history", productBase.getValue(settingsScreen), projected[settingsScreen])
        assertEquals("the service goes back to its first history through both of its links", productBase.getValue(messaging), projected[messaging])
        assertFalse("the approved additions are dropped from the history view",
            (setOf(access) + s7b + s7cAdded + n1Added + e5a1Added + e5b2aAdded + e5b2b1Added).any { it in projected })
        val moved = setOf(api, repo, access) + s7b + s7cChanged + s7cAdded + n1Changed + n1Added + e5a1Changed + e5a1Added + transport + activity +
            pushManager + pushCoordinator + e5b2aAdded + settingsScreen + e5b2b1Added
        assertEquals("nothing else moves", actual - moved,
            projected - (setOf(api, repo) + s7cChanged + n1Changed + e5a1Changed + transport + activity + pushManager + pushCoordinator +
                settingsScreen))
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
        // S7-N1 recorded the review of S7-a's api `to` (its own `from`), so that link passes; the next unreviewed link does not.
        assertTrue("premise: S7-a's api `to` is reviewed", firstTo in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertEquals("a chained `from` whose review is recorded passes", emptyList<String>(), errors(withUnits(unit(), next)))
        val after = next.with("id", JsonPrimitive("S7-x"))
            .with("changed", JsonObject(mapOf(api to changedEntry(someHash, "0".repeat(63) + "2"))))
        assertEquals("a chained `from` is not reviewed until its review is recorded", listOf("krx-before-unreviewed $api"),
            errors(withUnits(unit(), next, after)))
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
        val core = PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S7-c" && it.path == owner }
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

    // ---- S7-N1 core exception (`R4c/S7/s7n1/s7n1_scope.confirmed.r1.md`, user decision E1) ---------------------------------

    private val n1 get() = unit(3)
    private val n1Approval = "1ecc6234c4d4aebb9d6a51b90984404f7758a2e28549590f3e3d6911717ed838"
    private val messagingEntry get() = n1.getValue("changed").jsonObject.getValue(messaging).jsonObject
    private fun n1WithMessaging(entry: JsonObject) =
        n1.with("changed", JsonObject(n1.getValue("changed").jsonObject + (messaging to entry)))
    private fun unitsWithN1(changedN1: JsonObject) = withUnits(unit(0), unit(1), unit(2), changedN1)

    @Test fun E11_theN1CoreExceptionValidates_andItsKrxLinesAreTheReviewedOnes() {
        assertEquals("the exceptions in unit order: S7-E5c continues the service and first changes the activity",
            listOf("S7-c" to owner, "S7-N1" to messaging, "S7-E5a2" to transport, "S7-E5c" to messaging, "S7-E5c" to activity),
            PostS6ApprovedDelta.CORE_EXCEPTIONS.map { it.unitId to it.path })
        val core = PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S7-N1" && it.path == messaging }
        assertEquals("premise: the fourth unit is S7-N1", "S7-N1", n1.str("id"))
        assertEquals("its parent is the reviewed HEAD and its record the scope confirmation",
            listOf("0540526510b14447e171b4cc3522cdda00246b7f", n1Approval), listOf(n1.str("source_parent"), n1.str("approval_record_sha256")))
        assertEquals(listOf(core.unitId, core.sourceParent, core.approvalRecordSha256, core.path, core.from, core.to),
            listOf(n1.str("id"), n1.str("source_parent"), n1.str("approval_record_sha256"), messaging, messagingEntry.str("from"),
                messagingEntry.str("to")))
        assertEquals("the service's history is its product base", productBase.getValue(messaging), core.from)
        assertEquals("the reviewed KRX lines of the history bytes", "08e142a551e0e47752a09853de243aa65b727b5863c75171d1cb7157ece13d02",
            core.krxLinesSha256)
        assertEquals("the final bytes keep them", core.krxLinesSha256, PostS6ApprovedDelta.krxLinesSha256(File(messaging).readText()))
        assertTrue("premise: the service is a forbidden S6 surface", messaging in PostS6ApprovedDelta.FORBIDDEN_PATHS)
        assertEquals(emptyList<String>(), errors(json))
        val changed = n1.getValue("changed").jsonObject
        assertEquals("the unit's exact changes", setOf(api, repo) + n1Changed, changed.keys)
        assertEquals("and additions", n1Added, n1.getValue("added").jsonObject.keys)
        val s7a = unit(0).getValue("changed").jsonObject
        for (path in setOf(api, repo)) assertEquals("$path continues S7-a's chain", s7a.getValue(path).jsonObject.str("to"),
            changed.getValue(path).jsonObject.str("from"))
        assertEquals("the application's history is its product base", productBase.getValue(application),
            changed.getValue(application).jsonObject.str("from"))
        for (path in setOf(api, repo, application)) assertTrue("$path's bytes before S7-N1 are reviewed KRX-free",
            changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the service's history is not on the KRX-free list", core.from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
    }

    @Test fun E12_anotherToOrFromForTheServiceIsForbidden() {
        val to = errors(unitsWithN1(n1WithMessaging(changedEntry(messagingEntry.str("from"), someHash))))
        assertTrue("another to: $to", to.containsAll(listOf("forbidden $messaging", "krx-before-unreviewed $messaging")))
        val from = errors(unitsWithN1(n1WithMessaging(changedEntry(someHash, messagingEntry.str("to")))))
        assertTrue("another from: $from", from.containsAll(listOf("forbidden $messaging", "from $messaging", "krx-before-unreviewed $messaging")))
    }

    @Test fun E13_theServiceExceptionBelongsToItsOneUnit_andTheTwoExceptionsDoNotCross() {
        for ((label, other) in listOf(
            "another id" to n1.with("id", JsonPrimitive("S7-N2")),
            "another parent" to n1.with("source_parent", JsonPrimitive("1".repeat(40))),
            "another approval" to n1.with("approval_record_sha256", JsonPrimitive(someHash)),
            "S7-c's approval" to n1.with("approval_record_sha256", JsonPrimitive(s7c.str("approval_record_sha256")))
        )) {
            val found = errors(unitsWithN1(other))
            assertTrue("$label: $found", found.containsAll(listOf("forbidden $messaging", "krx-before-unreviewed $messaging")))
        }
        // Moved whole into S7-c: S7-c's provenance, the same path and bytes.
        val intoS7c = s7c.with("changed", JsonObject(s7c.getValue("changed").jsonObject + (messaging to messagingEntry)))
        val rest = n1.with("changed", JsonObject(n1.getValue("changed").jsonObject - messaging))
        assertTrue("moved to S7-c", errors(withUnits(unit(0), unit(1), intoS7c, rest)).contains("forbidden $messaging"))
        // S7-c's owner continued under S7-N1's provenance.
        val ownerTo = s7c.getValue("changed").jsonObject.getValue(owner).jsonObject.str("to")
        val ownerInN1 = n1.with("changed", JsonObject(n1.getValue("changed").jsonObject + (owner to changedEntry(ownerTo, someHash))))
        assertTrue("the owner under S7-N1", errors(unitsWithN1(ownerInN1)).contains("forbidden $owner"))
    }

    @Test fun E14_theServicesKrxLinesArePinned() {
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        val text = actual.getValue(messaging).toString(Charsets.UTF_8)
        assertTrue("premise", "if (krxContestSwitch.enabled) {" in text)
        val edited = text.replace("if (krxContestSwitch.enabled) {", "if (krxContestSwitch.enabled ) {").toByteArray()
        assertEquals("a KRX line edited", listOf("actual-hash $messaging", "core-krx-lines $messaging"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (messaging to edited)))
        val more = (text + "\nprivate val krxBypass = 1\n").toByteArray()
        assertEquals("a KRX line added", listOf("actual-hash $messaging", "core-krx-lines $messaging"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (messaging to more)))
        val plain = (text + "\n// a comment with krx in it\n").toByteArray()
        assertEquals("any other byte", listOf("actual-hash $messaging"), PostS6ApprovedDelta.actualErrors(ledger, actual + (messaging to plain)))
    }

    // ---- S7-E5a1 read-only bank section (`R4c/S7/s7e5/s7e5a1_scope.confirmed.r1.md`, user decision E5; no core exception) -------

    private val e5a1 get() = unit(4)

    @Test fun E21_theE5a1UnitIsExact_continuesTheChains_andNeedsNoCoreException() {
        assertEquals("premise: the fifth unit is S7-E5a1", "S7-E5a1", e5a1.str("id"))
        assertEquals("its parent is the S7-N1 commit", "530b4333211f81ddbd0fe8924ec9e4ad98e57d15", e5a1.str("source_parent"))
        assertEquals("its record is the scope confirmation", "16f5ddf3f1a9277a5a70fda336b8d4afc9aa7d09e308a65563ff72f10ddcbf48", e5a1.str("approval_record_sha256"))
        val changed = e5a1.getValue("changed").jsonObject
        assertEquals("the unit's exact changes", setOf(bankOwner, route) + e5a1Changed, changed.keys)
        assertEquals("and additions", e5a1Added, e5a1.getValue("added").jsonObject.keys)
        assertEquals("the owner continues S7-N1's addition", n1.getValue("added").jsonObject.getValue(bankOwner).jsonPrimitive.content,
            changed.getValue(bankOwner).jsonObject.str("from"))
        assertEquals("the route continues S7-c's chain", s7c.getValue("changed").jsonObject.getValue(route).jsonObject.str("to"),
            changed.getValue(route).jsonObject.str("from"))
        assertEquals("the section's history is its product base", productBase.getValue(alertSection),
            changed.getValue(alertSection).jsonObject.str("from"))
        for (path in changed.keys) {
            assertTrue("$path's bytes before S7-E5a1 are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
        }
        for (path in e5a1Added) assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
        assertTrue("no exception belongs to it", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5a1" })
        assertEquals(emptyList<String>(), errors(json))
        // The same unit with the section's history left unreviewed is refused.
        val unreviewed = e5a1.with("changed", JsonObject(changed + (alertSection to changedEntry(someHash, changed.getValue(alertSection).jsonObject.str("to")))))
        assertTrue(errors(withUnits(unit(0), unit(1), unit(2), unit(3), unreviewed)).contains("from $alertSection"))
    }

    @Test fun E15_aLaterUnitCannotContinueTheServiceException_andAnotherHistoryGetsNoKrxPass() {
        val next = JsonObject(mapOf("id" to JsonPrimitive("S7-N2"), "source_parent" to JsonPrimitive("1".repeat(40)),
            "approval_record_sha256" to JsonPrimitive(n1Approval),
            "changed" to JsonObject(mapOf(messaging to changedEntry(messagingEntry.str("to"), someHash))), "added" to JsonObject(emptyMap())))
        assertTrue(errors(withUnits(unit(0), unit(1), unit(2), n1, next)).contains("forbidden $messaging"))
        val actual = PostS6ApprovedDelta.actualSources()
        val otherFrom = PostS6ApprovedDelta.Ledger(mapOf(messaging to someHash), mapOf(messaging to PostS6ApprovedDelta.ledger().final.getValue(messaging)))
        assertEquals("another history for the service is KRX-checked as any path", listOf("krx-reference $messaging"),
            PostS6ApprovedDelta.actualErrors(otherFrom, actual))
    }

    // ---- S7-E5a2 bank row toggle and delete (`R4c/S7/s7e5/s7e5a2_scope.confirmed.r1.md`, user decision G1) ---------------------

    private val e5a2 get() = unit(5)
    private val e5a2Approval = "16ae21627bfd6cfdad0cfb9d2a2d6aedb0fe443c6566ead1caf8519440a91128"
    private val transportEntry get() = e5a2.getValue("changed").jsonObject.getValue(transport).jsonObject
    private fun e5a2WithTransport(entry: JsonObject) =
        e5a2.with("changed", JsonObject(e5a2.getValue("changed").jsonObject + (transport to entry)))
    private fun unitsWithE5a2(changedE5a2: JsonObject) = withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), changedE5a2)

    @Test fun E31_theE5a2UnitIsExact_andContinuesEveryChain() {
        assertEquals("premise: the sixth unit is S7-E5a2", "S7-E5a2", e5a2.str("id"))
        assertEquals("its parent is the S7-E5a1 commit and its record the scope confirmation",
            listOf("9f30832d1a14f08940619c35ce79f6fbdf136505", e5a2Approval), listOf(e5a2.str("source_parent"), e5a2.str("approval_record_sha256")))
        val changed = e5a2.getValue("changed").jsonObject
        assertEquals("the unit's exact changes", e5a2Changed, changed.keys)
        assertEquals("and no additions", emptySet<String>(), e5a2.getValue("added").jsonObject.keys)
        val n1c = n1.getValue("changed").jsonObject
        val e5a1c = e5a1.getValue("changed").jsonObject
        for (path in setOf(api, repo)) assertEquals("$path continues S7-N1's chain", n1c.getValue(path).jsonObject.str("to"),
            changed.getValue(path).jsonObject.str("from"))
        for (path in setOf(bankOwner, alertSection)) assertEquals("$path continues S7-E5a1's chain", e5a1c.getValue(path).jsonObject.str("to"),
            changed.getValue(path).jsonObject.str("from"))
        assertEquals("the holder continues S7-E5a1's addition", e5a1.getValue("added").jsonObject.getValue(bankSection).jsonPrimitive.content,
            changed.getValue(bankSection).jsonObject.str("from"))
        assertEquals("the transport's history is its product base", productBase.getValue(transport), transportEntry.str("from"))
        for (path in changed.keys - transport) {
            assertTrue("$path's bytes before S7-E5a2 are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertEquals(emptyList<String>(), errors(json))
        val unreviewed = e5a2.with("changed", JsonObject(changed + (bankOwner to changedEntry(someHash, changed.getValue(bankOwner).jsonObject.str("to")))))
        assertTrue(errors(unitsWithE5a2(unreviewed)).containsAll(listOf("from $bankOwner", "krx-before-unreviewed $bankOwner")))
    }

    @Test fun E32_theTransportCoreExceptionValidates_andPinsNoKrxLines() {
        val core = PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S7-E5a2" }
        assertEquals(listOf(core.unitId, core.sourceParent, core.approvalRecordSha256, core.path, core.from, core.to),
            listOf(e5a2.str("id"), e5a2.str("source_parent"), e5a2.str("approval_record_sha256"), transport, transportEntry.str("from"),
                transportEntry.str("to")))
        assertTrue("premise: the transport is a forbidden S6 prefix", PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { transport.startsWith(it) })
        assertEquals("the transport names no KRX: its pinned lines are the empty text", PostS6ApprovedDelta.sha(ByteArray(0)), core.krxLinesSha256)
        assertEquals("the final bytes keep that", core.krxLinesSha256, PostS6ApprovedDelta.krxLinesSha256(File(transport).readText()))
        assertFalse("the transport's history is not on the KRX-free list", core.from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        val more = (actual.getValue(transport).toString(Charsets.UTF_8) + "\nprivate val krxBypass = 1\n").toByteArray()
        assertEquals("a KRX line added", listOf("actual-hash $transport", "core-krx-lines $transport"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (transport to more)))
    }

    @Test fun E33_anotherToOrFromForTheTransportIsForbidden() {
        val to = errors(unitsWithE5a2(e5a2WithTransport(changedEntry(transportEntry.str("from"), someHash))))
        assertTrue("another to: $to", to.containsAll(listOf("forbidden $transport", "krx-before-unreviewed $transport")))
        val from = errors(unitsWithE5a2(e5a2WithTransport(changedEntry(someHash, transportEntry.str("to")))))
        assertTrue("another from: $from", from.containsAll(listOf("forbidden $transport", "from $transport", "krx-before-unreviewed $transport")))
    }

    @Test fun E34_theTransportExceptionBelongsToItsOneUnit_andNoExceptionCrosses() {
        for ((label, other) in listOf(
            "another id" to e5a2.with("id", JsonPrimitive("S7-E5a3")),
            "another parent" to e5a2.with("source_parent", JsonPrimitive("1".repeat(40))),
            "another approval" to e5a2.with("approval_record_sha256", JsonPrimitive(someHash)),
            "S7-N1's approval" to e5a2.with("approval_record_sha256", JsonPrimitive(n1Approval))
        )) {
            val found = errors(unitsWithE5a2(other))
            assertTrue("$label: $found", found.containsAll(listOf("forbidden $transport", "krx-before-unreviewed $transport")))
        }
        val intoE5a1 = e5a1.with("changed", JsonObject(e5a1.getValue("changed").jsonObject + (transport to transportEntry)))
        val rest = e5a2.with("changed", JsonObject(e5a2.getValue("changed").jsonObject - transport))
        assertTrue("moved to S7-E5a1", errors(withUnits(unit(0), unit(1), unit(2), unit(3), intoE5a1, rest)).contains("forbidden $transport"))
        val messagingTo = n1.getValue("changed").jsonObject.getValue(messaging).jsonObject.str("to")
        val serviceInE5a2 = e5a2.with("changed", JsonObject(e5a2.getValue("changed").jsonObject + (messaging to changedEntry(messagingTo, someHash))))
        assertTrue("the service under S7-E5a2", errors(unitsWithE5a2(serviceInE5a2)).contains("forbidden $messaging"))
    }

    @Test fun E35_aLaterUnitCannotContinueTheTransportException() {
        val next = JsonObject(mapOf("id" to JsonPrimitive("S7-E5a3"), "source_parent" to JsonPrimitive("1".repeat(40)),
            "approval_record_sha256" to JsonPrimitive(e5a2Approval),
            "changed" to JsonObject(mapOf(transport to changedEntry(transportEntry.str("to"), someHash))), "added" to JsonObject(emptyMap())))
        assertTrue(errors(withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), e5a2, next)).contains("forbidden $transport"))
        val actual = PostS6ApprovedDelta.actualSources()
        val otherFrom = PostS6ApprovedDelta.Ledger(mapOf(transport to someHash), mapOf(transport to transportEntry.str("to")))
        val more = actual.getValue(transport) + "\nprivate val krxBypass = 1\n".toByteArray()
        assertEquals("another history for the transport gets no exception: a KRX line is checked as on any path",
            listOf("actual-hash $transport", "krx-reference $transport"), PostS6ApprovedDelta.actualErrors(otherFrom, actual + (transport to more)))
    }

    // ---- S7-E5c bank push/tap (`R4c/S7/s7e5/s7e5c_exact_proposal.claude.r3.md`, user decision D1/D2 with its errata) ---------

    private val e5c get() = unit(6)
    private val e5cApproval = "d92df63aa74097d2342511c0ecdeda141c20fbd973f321b3ede5f5a94c3f017e"
    private val e5cParent = "e06fbb0202ce9af0da72d4f52640b233d711c8c5"
    private fun e5cEntry(path: String) = e5c.getValue("changed").jsonObject.getValue(path).jsonObject
    private fun e5cWith(path: String, entry: JsonObject) = e5c.with("changed", JsonObject(e5c.getValue("changed").jsonObject + (path to entry)))
    private fun unitsWithE5c(changedE5c: JsonObject) = withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), changedE5c)
    private val e5cCores get() = PostS6ApprovedDelta.CORE_EXCEPTIONS.filter { it.unitId == "S7-E5c" }

    @Test fun E41_theE5cUnitIsExact_andContinuesEveryChain() {
        assertEquals("premise: the seventh unit is S7-E5c", "S7-E5c", e5c.str("id"))
        assertEquals("its parent is the S7-E5a2 commit and its record the agreed exact design",
            listOf(e5cParent, e5cApproval), listOf(e5c.str("source_parent"), e5c.str("approval_record_sha256")))
        val changed = e5c.getValue("changed").jsonObject
        assertEquals("the unit's exact changes", e5cChanged, changed.keys)
        assertEquals("and no additions", emptySet<String>(), e5c.getValue("added").jsonObject.keys)
        assertEquals("the service continues S7-N1's link", messagingEntry.str("to"), e5cEntry(messaging).str("from"))
        assertEquals("the activity's history is its product base", productBase.getValue(activity), e5cEntry(activity).str("from"))
        assertEquals("the registry continues S7-N1's addition", n1.getValue("added").jsonObject.getValue(registry).jsonPrimitive.content,
            e5cEntry(registry).str("from"))
        assertEquals("the owner continues S7-E5a2's chain", e5a2.getValue("changed").jsonObject.getValue(bankOwner).jsonObject.str("to"),
            e5cEntry(bankOwner).str("from"))
        for (path in setOf(registry, bankOwner)) {
            assertTrue("$path's bytes before S7-E5c are reviewed KRX-free", e5cEntry(path).str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E42_theTwoE5cExceptionsValidate_andTheKrxCheckUsesEachPathsRealLastFrom() {
        assertEquals("two exceptions, the service and the activity", listOf(messaging, activity), e5cCores.map { it.path })
        for (core in e5cCores) {
            val entry = e5cEntry(core.path)
            assertEquals(listOf(core.sourceParent, core.approvalRecordSha256, core.from, core.to),
                listOf(e5cParent, e5cApproval, entry.str("from"), entry.str("to")))
            assertTrue("premise: ${core.path} is a forbidden S6 surface", core.path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
            assertFalse("${core.path}'s history before S7-E5c is not on the KRX-free list", core.from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertEquals("the final bytes keep the pinned KRX lines", core.krxLinesSha256, PostS6ApprovedDelta.krxLinesSha256(File(core.path).readText()))
        }
        val n1Core = PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S7-N1" }
        assertEquals("the service's two links pin the same KRX lines", n1Core.krxLinesSha256, e5cCores.first { it.path == messaging }.krxLinesSha256)
        assertEquals("the activity's reviewed KRX lines", "e61ab21d19206fde24d0b2a8d74e0e75def7397c1ac1d3be33f8514b07b725aa",
            e5cCores.first { it.path == activity }.krxLinesSha256)
        val ledger = PostS6ApprovedDelta.ledger()
        assertEquals("the service's last from is S7-E5c's real from, its original the product base",
            listOf(n1Core.to, productBase.getValue(messaging)), listOf(ledger.lastFrom[messaging], ledger.original[messaging]))
        assertEquals("the activity's last from and original are its product base",
            listOf(productBase.getValue(activity), productBase.getValue(activity)), listOf(ledger.lastFrom[activity], ledger.original[activity]))
        val lastRegistryChange = json.getValue("units").jsonArray.map { it.jsonObject }.last { registry in it.getValue("changed").jsonObject }
        assertEquals("an added path's last from is its last change's from",
            lastRegistryChange.getValue("changed").jsonObject.getValue(registry).jsonObject.str("from"), ledger.lastFrom[registry])
        assertEquals("and it stays an addition in history", null, ledger.original[registry])
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, PostS6ApprovedDelta.actualSources()))
        // The cumulative original→final pair is no exception: a hand-built ledger that names it gets no KRX pass.
        val cumulative = PostS6ApprovedDelta.Ledger(mapOf(messaging to productBase.getValue(messaging)), mapOf(messaging to e5cEntry(messaging).str("to")))
        assertEquals("the synthetic pair is KRX-checked as any path", listOf("krx-reference $messaging"),
            PostS6ApprovedDelta.actualErrors(cumulative, PostS6ApprovedDelta.actualSources()))
    }

    @Test fun E43_aMissingOrUnapprovedPredecessorLinkFailsTheWholeLedger() {
        val n1WithoutService = n1.with("changed", JsonObject(n1.getValue("changed").jsonObject - messaging))
        val dropped = PostS6ApprovedDelta.parse(withUnits(unit(0), unit(1), unit(2), n1WithoutService, unit(4), unit(5), e5c).toString().toByteArray(),
            inventory, s64b, null)
        assertTrue("the E5c link no longer continues anything: ${dropped.first}", dropped.first.contains("from $messaging"))
        assertEquals("and there is no ledger", null, dropped.second)
        val wrongApproval = n1.with("approval_record_sha256", JsonPrimitive(someHash))
        val broken = PostS6ApprovedDelta.parse(withUnits(unit(0), unit(1), unit(2), wrongApproval, unit(4), unit(5), e5c).toString().toByteArray(),
            inventory, s64b, null)
        assertTrue("the predecessor's own exception fails though the last pair matches: ${broken.first}",
            broken.first.contains("forbidden $messaging"))
        assertEquals("and there is no ledger", null, broken.second)
    }

    @Test fun E44_eachE5cExceptionBelongsToItsOneUnitParentPathAndTo() {
        val elsewhere = (PostS6ApprovedDelta.FORBIDDEN_PATHS - PostS6ApprovedDelta.ledger().final.keys).sorted().first()
        for (path in listOf(messaging, activity)) {
            val entry = e5cEntry(path)
            for ((label, other) in listOf(
                "another id" to e5c.with("id", JsonPrimitive("S7-E5d")),
                "another parent" to e5c.with("source_parent", JsonPrimitive("1".repeat(40))),
                "another approval" to e5c.with("approval_record_sha256", JsonPrimitive(someHash)),
                "S7-E5a2's approval" to e5c.with("approval_record_sha256", JsonPrimitive(e5a2Approval)),
                "another to" to e5cWith(path, changedEntry(entry.str("from"), someHash))
            )) {
                val found = errors(unitsWithE5c(other))
                assertTrue("$path $label: $found", found.contains("forbidden $path"))
            }
            val rest = e5c.getValue("changed").jsonObject - path
            val moved = e5c.with("changed", JsonObject(rest + (elsewhere to entry)))
            assertTrue("$path moved to $elsewhere", errors(unitsWithE5c(moved)).contains("forbidden $elsewhere"))
            val asAddition = e5c.with("changed", JsonObject(rest)).with("added", JsonObject(mapOf(path to JsonPrimitive(entry.str("to")))))
            assertTrue("$path as an addition", errors(unitsWithE5c(asAddition)).contains("forbidden $path"))
        }
    }

    @Test fun E45_theE5cPathsKrxLinesArePinned_andAnyOtherByteIsAnActualHash() {
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        for (path in listOf(messaging, activity)) {
            val text = actual.getValue(path).toString(Charsets.UTF_8)
            assertTrue("premise: $path has a KRX line", "if (krxContestSwitch.enabled) {" in text)
            val edited = text.replaceFirst("if (krxContestSwitch.enabled) {", "if (krxContestSwitch.enabled ) {").toByteArray()
            assertEquals("$path: a KRX line edited", listOf("actual-hash $path", "core-krx-lines $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to edited)))
            val more = (text + "\nprivate val krxBypass = 1\n").toByteArray()
            assertEquals("$path: a KRX line added", listOf("actual-hash $path", "core-krx-lines $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to more)))
            val plain = (text + "\n// a comment with krx in it\n").toByteArray()
            assertEquals("$path: any other byte", listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(ledger, actual + (path to plain)))
        }
    }

    @Test fun E46_aLaterUnitCannotContinueTheE5cExceptions() {
        for (path in listOf(messaging, activity)) {
            val next = JsonObject(mapOf("id" to JsonPrimitive("S7-E5d"), "source_parent" to JsonPrimitive("1".repeat(40)),
                "approval_record_sha256" to JsonPrimitive(e5cApproval),
                "changed" to JsonObject(mapOf(path to changedEntry(e5cEntry(path).str("to"), someHash))), "added" to JsonObject(emptyMap())))
            assertTrue("$path", errors(withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), e5c, next)).contains("forbidden $path"))
        }
    }

    /**
     * Gap G14 (`s7e5c_gaphunt.codex.r1.md` §3): each E5c exception binds its from. Only that one entry's from changes, the rest of
     * the unit and every other unit stay as approved; the forbidden rule must fire for it on its own, beside the chain errors.
     */
    @Test fun `S7E5C_G14_E47 each E5c exception from alone is forbidden`() {
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5c(e5c).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5c unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in listOf(messaging, activity)) {
            val entry = e5cEntry(path)
            assertTrue("premise: $path is a forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
            val (found, ledger) = PostS6ApprovedDelta.parse(
                unitsWithE5c(e5cWith(path, changedEntry(someHash, entry.str("to")))).toString().toByteArray(), inventory, s64b, null)
            assertEquals("$path another from alone: forbidden, the broken link and the unreviewed history, and nothing else",
                listOf("forbidden $path", "from $path", "krx-before-unreviewed $path"), found)
            assertEquals("$path another from alone: no ledger", null, ledger)
        }
    }

    // ---- S7-E5b1: the owner's create and edit (s7e5b1_api_agreed.r1.md), one KRX-free changed path, no exception ----

    private val e5b1 get() = unit(7)
    private val e5b1Approval = "987b55d2c712b8d9ca4d6db74321a85f08da1886a4a0c9c41b3335781d753354"
    private val e5b1Parent = "222c520ad1105bdad2317d1c28ecf3d03dca79da"
    private fun unitsWithE5b1(changedE5b1: JsonObject) = withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), changedE5b1)

    @Test fun E48_theE5b1UnitIsExact_andContinuesTheOwnersChain() {
        assertEquals("premise: the eighth unit is S7-E5b1", "S7-E5b1", e5b1.str("id"))
        assertEquals("its parent is the S7-E5c commit and its record the agreed API",
            listOf(e5b1Parent, e5b1Approval), listOf(e5b1.str("source_parent"), e5b1.str("approval_record_sha256")))
        val changed = e5b1.getValue("changed").jsonObject
        assertEquals("only the owner changes", setOf(bankOwner), changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), e5b1.getValue("added").jsonObject.keys)
        val entry = changed.getValue(bankOwner).jsonObject
        assertEquals("the owner continues S7-E5c's chain", e5cEntry(bankOwner).str("to"), entry.str("from"))
        assertTrue("the owner's bytes before S7-E5b1 are reviewed KRX-free", entry.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the owner is no forbidden S6 surface", bankOwner in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { bankOwner.startsWith(it) })
        assertTrue("no core exception belongs to S7-E5b1", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b1" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E49_theE5b1UnitRefusesAnotherFromAndAnyForbiddenPath() {
        val entry = e5b1.getValue("changed").jsonObject.getValue(bankOwner).jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b1(e5b1).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b1 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val unreviewed = e5b1.with("changed", JsonObject(mapOf(bankOwner to changedEntry(someHash, entry.str("to")))))
        assertEquals("another from: the broken link and the unreviewed history",
            listOf("from $bankOwner", "krx-before-unreviewed $bankOwner"),
            PostS6ApprovedDelta.parse(unitsWithE5b1(unreviewed).toString().toByteArray(), inventory, s64b, null).first)
        val messagingTo = e5cEntry(messaging).str("to")
        val forbidden = e5b1.with("changed", JsonObject(mapOf(bankOwner to entry, messaging to changedEntry(messagingTo, someHash))))
        assertTrue("a forbidden path without its own exception",
            PostS6ApprovedDelta.parse(unitsWithE5b1(forbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $messaging"))
    }

    // ---- S7-E5b2a: capability, D21 reconcile and the registry hook (s7e5b2_api_agreed.r1.md), three KRX-free changes and three additions ----

    private val e5b2a get() = unit(8)
    private val e5b2aApproval = "645ed19342712dc6587d9ac963d56fd89f5c70a04101840c20fb4bee4befbbd8"
    private val e5b2aParent = "a350518724e22a275e2bafd0f0158a47b16d8d81"
    private val pushManager = base + "service/PushNotificationManager.kt"
    private val pushCoordinator = base + "service/PushRegistrationCoordinator.kt"
    private val e5b2aChanged = setOf(registry, pushManager, pushCoordinator)
    private val e5b2aAdded = listOf("NotificationCapability.kt", "NotificationCapabilityMonitor.kt", "PushIntentReconciler.kt").map { base + "service/$it" }.toSet()
    private fun unitsWithE5b2a(changedE5b2a: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), changedE5b2a)

    @Test fun E50_theE5b2aUnitIsExact_andContinuesTheRegistrysChain() {
        assertEquals("premise: the ninth unit is S7-E5b2a", "S7-E5b2a", e5b2a.str("id"))
        assertEquals("its parent is the S7-E5b1 commit and its record the agreed API",
            listOf(e5b2aParent, e5b2aApproval), listOf(e5b2a.str("source_parent"), e5b2a.str("approval_record_sha256")))
        val changed = e5b2a.getValue("changed").jsonObject
        assertEquals("the registry and the two push files change", e5b2aChanged, changed.keys)
        assertEquals("and three files are added", e5b2aAdded, e5b2a.getValue("added").jsonObject.keys)
        assertEquals("the registry continues S7-E5c's chain", e5cEntry(registry).str("to"), changed.getValue(registry).jsonObject.str("from"))
        for ((path, entry) in changed) {
            assertTrue("$path: the bytes before S7-E5b2a are reviewed KRX-free", entry.jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in e5b2aAdded) assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        assertTrue("no core exception belongs to S7-E5b2a", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b2a" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E51_theE5b2aUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b2a.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b2a(e5b2a).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b2a unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val registryTo = changed.getValue(registry).jsonObject.str("to")
        val broken = e5b2a.with("changed", JsonObject(changed + (registry to changedEntry(someHash, registryTo))))
        assertEquals("another from on the registry: the broken link and the unreviewed history",
            listOf("from $registry", "krx-before-unreviewed $registry"),
            PostS6ApprovedDelta.parse(unitsWithE5b2a(broken).toString().toByteArray(), inventory, s64b, null).first)
        val forbiddenAdd = base + "data/entitlements/S7E5b2aProbe.kt"
        val withForbidden = e5b2a.with("added", JsonObject(e5b2a.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithE5b2a(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5b2b1: the permission helper and the settings row (s7e5b2b_start_agreed.r1.md, errata r2 E5), one KRX-free change and one addition ----

    private val e5b2b1 get() = unit(9)
    private val e5b2b1Approval = "daf92b86237e40f079c01b8525b3f2b185b71d74c603e114c83b6fa6f17742a0"
    private val e5b2b1Parent = "ca543affdbd859a01b0a08ea0e856226398de4af"
    private val settingsScreen = base + "ui/settings/SettingsScreen.kt"
    private val e5b2b1Added = setOf(base + "ui/alert/NotificationPermissionAction.kt")
    private fun unitsWithE5b2b1(changedE5b2b1: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), changedE5b2b1)

    @Test fun E52_theE5b2b1UnitIsExact_andStartsTheSettingsScreensChain() {
        assertEquals("premise: the tenth unit is S7-E5b2b1", "S7-E5b2b1", e5b2b1.str("id"))
        assertEquals("its parent is the S7-E5b2a commit and its record the agreed start decisions",
            listOf(e5b2b1Parent, e5b2b1Approval), listOf(e5b2b1.str("source_parent"), e5b2b1.str("approval_record_sha256")))
        val changed = e5b2b1.getValue("changed").jsonObject
        assertEquals("only the settings screen changes", setOf(settingsScreen), changed.keys)
        assertEquals("and the helper is added", e5b2b1Added, e5b2b1.getValue("added").jsonObject.keys)
        assertEquals("the settings screen starts from its product base", productBase.getValue(settingsScreen),
            changed.getValue(settingsScreen).jsonObject.str("from"))
        assertTrue("the bytes before S7-E5b2b1 are reviewed KRX-free", changed.getValue(settingsScreen).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        for (path in changed.keys + e5b2b1Added) assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        assertTrue("no core exception belongs to S7-E5b2b1", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b2b1" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E53_theE5b2b1UnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b2b1.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b2b1(e5b2b1).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b2b1 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val settingsTo = changed.getValue(settingsScreen).jsonObject.str("to")
        val broken = e5b2b1.with("changed", JsonObject(changed + (settingsScreen to changedEntry(someHash, settingsTo))))
        assertEquals("another from on the settings screen: the broken link and the unreviewed history",
            listOf("from $settingsScreen", "krx-before-unreviewed $settingsScreen"),
            PostS6ApprovedDelta.parse(unitsWithE5b2b1(broken).toString().toByteArray(), inventory, s64b, null).first)
        val forbiddenAdd = base + "data/entitlements/S7E5b2b1Probe.kt"
        val withForbidden = e5b2b1.with("added", JsonObject(e5b2b1.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithE5b2b1(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5b2b2: the section's prompt and banner and the holder's helper call (s7e5b2b_start_agreed.r1.md D1, errata r2 E5), two KRX-free changes ----

    private val e5b2b2 get() = unit(10)
    private val e5b2b2Parent = "876a3cf3b27fda1b62e770c8b8428f592726465b"
    private val e5b2b2Changed = setOf(alertSection, bankSection)
    private fun unitsWithE5b2b2(changedE5b2b2: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), unit(9), changedE5b2b2)

    @Test fun E54_theE5b2b2UnitIsExact_andContinuesTheSectionsChains() {
        assertEquals("premise: the eleventh and last unit is S7-E5b2b2", listOf("S7-E5b2b2", 11), listOf(e5b2b2.str("id"), json.getValue("units").jsonArray.size))
        assertEquals("its parent is the S7-E5b2b1 commit and its record the agreed start decisions",
            listOf(e5b2b2Parent, e5b2b1Approval), listOf(e5b2b2.str("source_parent"), e5b2b2.str("approval_record_sha256")))
        val changed = e5b2b2.getValue("changed").jsonObject
        assertEquals("the section and its holder change", e5b2b2Changed, changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), e5b2b2.getValue("added").jsonObject.keys)
        val e5a2c = e5a2.getValue("changed").jsonObject
        for (path in e5b2b2Changed) {
            assertEquals("$path continues S7-E5a2's chain", e5a2c.getValue(path).jsonObject.str("to"), changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S7-E5b2b2 are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no unit between S7-E5a2 and S7-E5b2b2 changes them", (6..9).none { i -> unit(i).getValue("changed").jsonObject.keys.any { it in e5b2b2Changed } })
        assertTrue("no core exception belongs to S7-E5b2b2", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b2b2" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E55_theE5b2b2UnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b2b2.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b2b2(e5b2b2).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b2b2 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in e5b2b2Changed) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = e5b2b2.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithE5b2b2(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S7E5b2b2Probe.kt"
        val withForbidden = e5b2b2.with("added", JsonObject(e5b2b2.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithE5b2b2(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }
}
