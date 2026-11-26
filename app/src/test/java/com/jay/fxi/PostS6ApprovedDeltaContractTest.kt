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
import org.junit.Assert.assertNull
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
        assertEquals("the folded ledger contains exactly the expected production paths through S9-4b",
            setOf(api, repo, access) + s7b + s7cChanged + s7cAdded + n1Changed + n1Added + e5a1Changed + e5a1Added + transport + activity +
                pushManager + pushCoordinator + e5b2aAdded + settingsScreen + e5b2b1Added + alertSheet + e5b3aAdded + e5b3bNew +
                rateRowStore + e5b4aAdded + cacheService + e5b4bAdded + e5dNew + e5dAdded + s83Added + s84aAdded + s84bAdded + s85bAdded +
                s87AddedPaths + s91Policy + s92aOwner + s92bSection + (s93Added.keys + s94bAdded.keys),
            ledger.final.keys)
        assertEquals(setOf(api, repo) + s7cChanged + n1Changed + e5a1Changed + transport + activity + pushManager + pushCoordinator +
            settingsScreen + alertSheet + e5b3bNew + rateRowStore + cacheService + e5dNew, ledger.changed)
        assertEquals(setOf(access) + s7b + s7cAdded + n1Added + e5a1Added + e5b2aAdded + e5b2b1Added + e5b3aAdded + e5b4aAdded + e5b4bAdded + e5dAdded +
            s83Added + s84aAdded + s84bAdded + s85bAdded + s87AddedPaths + s91Policy + s92aOwner + s92bSection + (s93Added.keys + s94bAdded.keys), ledger.added)
        assertEquals("history hashes", mapOf(api to productBase.getValue(api), repo to productBase.getValue(repo), access to null) +
            s7b.associateWith { null } + s7cChanged.associateWith { productBase.getValue(it) } + s7cAdded.associateWith { null } +
            n1Changed.associateWith { productBase.getValue(it) } + n1Added.associateWith { null } +
            e5a1Changed.associateWith { productBase.getValue(it) } + e5a1Added.associateWith { null } +
            mapOf(transport to productBase.getValue(transport), activity to productBase.getValue(activity)) +
            mapOf(pushManager to productBase.getValue(pushManager), pushCoordinator to productBase.getValue(pushCoordinator)) +
            e5b2aAdded.associateWith { null } + mapOf(settingsScreen to productBase.getValue(settingsScreen)) +
            e5b2b1Added.associateWith { null } + mapOf(alertSheet to productBase.getValue(alertSheet)) +
            e5b3aAdded.associateWith { null } + e5b3bNew.associateWith { productBase.getValue(it) } +
            mapOf(rateRowStore to productBase.getValue(rateRowStore)) + e5b4aAdded.associateWith { null } +
            mapOf(cacheService to productBase.getValue(cacheService)) + e5b4bAdded.associateWith { null } +
            e5dNew.associateWith { productBase.getValue(it) } + e5dAdded.associateWith { null } + s83Added.associateWith { null } +
            s84aAdded.associateWith { null } + s84bAdded.associateWith { null } + s85bAdded.associateWith { null } +
            (s87AddedPaths + s91Policy + s92aOwner + s92bSection + (s93Added.keys + s94bAdded.keys)).associateWith { null }, ledger.original)
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, PostS6ApprovedDelta.actualSources()))
        val bytes = PostS6ApprovedDelta.actualSources()
        val actual = bytes.mapValues { PostS6ApprovedDelta.sha(it.value) }
        val changes = s85b.getValue("changed").jsonObject
        for (path in s85bChanged.keys - route - presenter) {
            assertEquals("$path has the current file's bytes", actual.getValue(path), ledger.final.getValue(path))
            assertEquals("$path ends with the S8-5b to", changes.getValue(path).jsonObject.str("to"), ledger.final.getValue(path))
            assertEquals("$path ends with the S8-5b from", changes.getValue(path).jsonObject.str("from"),
                ledger.lastFrom.getValue(path))
        }
        assertEquals("the bank owner has the current file's bytes", actual.getValue(s85cOwner), ledger.final.getValue(s85cOwner))
        assertEquals("the bank owner ends with the S8-5c from", s85c.getValue("changed").jsonObject.getValue(s85cOwner).jsonObject.str("from"),
            ledger.lastFrom.getValue(s85cOwner))
        for (path in s86Changed.keys - registry) {
            assertEquals("$path has the current file's bytes", actual.getValue(path), ledger.final.getValue(path))
            assertEquals("$path ends with the S8-6 from", s86.getValue("changed").jsonObject.getValue(path).jsonObject.str("from"),
                ledger.lastFrom.getValue(path))
        }
        for (path in s87Changed.keys) {
            assertEquals("$path has the current file's bytes", actual.getValue(path), ledger.final.getValue(path))
            assertEquals("$path ends with the S8-7 from", s87Entry(path).str("from"), ledger.lastFrom.getValue(path))
        }
        for (path in s87AddedPaths) {
            assertEquals("$path has the current file's bytes", actual.getValue(path), ledger.final.getValue(path))
            assertEquals("$path has no history before S8-7", null, ledger.lastFrom[path])
        }
        for ((path, link) in s91Changed) {
            assertEquals("$path ends with the S9-1 from", link.str("from"), ledger.lastFrom.getValue(path))
            assertEquals("$path ends with the S9-1 to", link.str("to"), ledger.final.getValue(path))
        }
        assertEquals(s91PolicySha, ledger.final.getValue(s91Policy))
        assertEquals(null, ledger.lastFrom.getValue(s91Policy))
        val latestChanges = s92bChanged + s93Changed + s94aChanged + s94bChanged
        for ((path, link) in latestChanges) {
            assertEquals("$path latest predecessor", link.str("from"), ledger.lastFrom.getValue(path))
            assertEquals("$path latest approved bytes", link.str("to"), ledger.final.getValue(path))
        }
        for ((path, hash) in s93Added + s94bAdded) {
            assertEquals(hash, ledger.final.getValue(path))
            assertEquals(null, ledger.lastFrom.getValue(path))
        }
        val expectedLastFrom = mutableMapOf<String, String?>()
        for (index in 0 until 27) {
            val prior = unit(index)
            for ((path, link) in prior.getValue("changed").jsonObject) expectedLastFrom[path] = link.jsonObject.str("from")
            for (path in prior.getValue("added").jsonObject.keys) expectedLastFrom[path] = null
        }
        for ((path, link) in latestChanges) expectedLastFrom[path] = link.str("from")
        for (path in (s93Added.keys + s94bAdded.keys)) expectedLastFrom[path] = null
        assertEquals("the complete lastFrom map through S9-4b", expectedLastFrom, ledger.lastFrom)
        for (path in latestChanges.keys + (s93Added.keys + s94bAdded.keys)) {
            assertEquals("$path at altered bytes stays visible", someHash,
                PostS6ApprovedDelta.project(actual + (path to someHash), ledger).getValue(path))
        }
        for (path in s94bChanged.keys + s94bAdded.keys) {
            assertEquals(listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, bytes - path))
            val altered = bytes.getValue(path) + byteArrayOf(32)
            assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(ledger, bytes + (path to altered)))
            assertEquals("actual bytes for $path", ledger.final.getValue(path), actual[path])
        }
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
        assertEquals("S7-E5b3a's sheet goes back to its history", productBase.getValue(alertSheet), projected[alertSheet])
        for (path in e5b3bNew) assertEquals("S7-E5b3b $path goes back to its history", productBase.getValue(path), projected[path])
        assertEquals("S7-E5b4a's rate row store goes back to its history", productBase.getValue(rateRowStore), projected[rateRowStore])
        assertEquals("S7-E5b4b's cache service goes back to its history", productBase.getValue(cacheService), projected[cacheService])
        for (path in e5dNew) assertEquals("S7-E5d $path goes back to its history", productBase.getValue(path), projected[path])
        assertEquals("the service goes back to its first history through both of its links", productBase.getValue(messaging), projected[messaging])
        assertFalse("the approved additions are dropped from the history view",
            (setOf(access) + s7b + s7cAdded + n1Added + e5a1Added + e5b2aAdded + e5b2b1Added + e5b3aAdded + e5b4aAdded + e5b4bAdded + e5dAdded +
                s83Added + s84aAdded + s84bAdded + s85bAdded + s87AddedPaths + s91Policy + s92aOwner + s92bSection + (s93Added.keys + s94bAdded.keys)).any { it in projected })
        val moved = setOf(api, repo, access) + s7b + s7cChanged + s7cAdded + n1Changed + n1Added + e5a1Changed + e5a1Added + transport + activity +
            pushManager + pushCoordinator + e5b2aAdded + settingsScreen + e5b2b1Added + alertSheet + e5b3aAdded + e5b3bNew +
            rateRowStore + e5b4aAdded + cacheService + e5b4bAdded + e5dNew + e5dAdded + s83Added + s84aAdded + s84bAdded + s85bAdded +
            s87AddedPaths + s91Policy + s92aOwner + s92bSection + (s93Added.keys + s94bAdded.keys)
        assertEquals("nothing else moves", actual - moved,
            projected - (setOf(api, repo) + s7cChanged + n1Changed + e5a1Changed + transport + activity + pushManager + pushCoordinator +
                settingsScreen + alertSheet + e5b3bNew + rateRowStore + cacheService + e5dNew))
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
                base.replace('/', '\\') + "X.kt", "src/test/java/com/jay/fxi/X.kt", base + "X.java", base + "X\u0000.kt",
                // S7 E5d-J: the accessor root is the one other root, and only that one.
                "src/debug/java/com/jay/fxi/X.kt", "src/e5dJourney/java/com/jay/fxi/X.kt", "src/e5dHooksDefault/kotlin/com/jay/fxi/X.kt")) {
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
        assertEquals("the exceptions in unit order: S7-E5c continues the service and first changes the activity, S8-6 continues both",
            listOf("S7-c" to owner, "S7-N1" to messaging, "S7-E5a2" to transport, "S7-E5c" to messaging, "S7-E5c" to activity,
                "S8-6" to messaging, "S8-6" to activity),
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
        // S8-6 continues both paths from S7-E5c's to through its own CoreException; the history origin is unchanged.
        val s86Core = { path: String -> PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S8-6" && it.path == path } }
        assertEquals("the service's last from is S8-6's real from (S7-E5c's to), its original the product base",
            listOf(e5cCores.first { it.path == messaging }.to, s86Core(messaging).from, productBase.getValue(messaging)),
            listOf(ledger.lastFrom[messaging], ledger.lastFrom[messaging], ledger.original[messaging]))
        assertEquals("the activity's last from is S8-6's real from (S7-E5c's to), its original the product base",
            listOf(e5cCores.first { it.path == activity }.to, s86Core(activity).from, productBase.getValue(activity)),
            listOf(ledger.lastFrom[activity], ledger.lastFrom[activity], ledger.original[activity]))
        val lastRegistryChange = json.getValue("units").jsonArray.map { it.jsonObject }.last { registry in it.getValue("changed").jsonObject }
        assertEquals("an added path's last from is its last change's from",
            lastRegistryChange.getValue("changed").jsonObject.getValue(registry).jsonObject.str("from"), ledger.lastFrom[registry])
        assertEquals("and it stays an addition in history", null, ledger.original[registry])
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, PostS6ApprovedDelta.actualSources()))
        // The cumulative original→final pair is no exception: a hand-built ledger that names it gets no KRX pass.
        // The checkout's final bytes (S8-6's to, after S7-E5c's link) under the cumulative pair: only the KRX rule can refuse it.
        val cumulative = PostS6ApprovedDelta.Ledger(mapOf(messaging to productBase.getValue(messaging)), mapOf(messaging to ledger.final.getValue(messaging)))
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
        assertEquals("premise: the eleventh unit is S7-E5b2b2", "S7-E5b2b2", e5b2b2.str("id"))
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

    // ---- S7-E5b3a: the bank alert editor's model and sheet (s7e5b3_api_agreed.r1.md D1–D3, D8), one KRX-free change and one addition ----

    private val e5b3a get() = unit(11)
    private val e5b3aApproval = "065d153a639ff036bd8fc09438c4f6d6c497048f8e1223f72871f3273b18c496"
    private val e5b3aParent = "668d073f8506f1eb2e0c558442870e8d973fb51c"
    private val alertSheet = base + "ui/alert/AlertAddSheet.kt"
    private val e5b3aAdded = setOf(base + "ui/alert/BankAlertEditor.kt")
    private fun unitsWithE5b3a(changedE5b3a: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), unit(9), unit(10), changedE5b3a)

    @Test fun E56_theE5b3aUnitIsExact_andStartsTheSheetsChain() {
        assertEquals("premise: the twelfth unit is S7-E5b3a", "S7-E5b3a", e5b3a.str("id"))
        assertEquals("its parent is the S7-E5b2b2 commit and its record the agreed E5b3 API",
            listOf(e5b3aParent, e5b3aApproval), listOf(e5b3a.str("source_parent"), e5b3a.str("approval_record_sha256")))
        val changed = e5b3a.getValue("changed").jsonObject
        assertEquals("only the sheet changes", setOf(alertSheet), changed.keys)
        assertEquals("and the editor model is added", e5b3aAdded, e5b3a.getValue("added").jsonObject.keys)
        assertEquals("the sheet starts from its product base", productBase.getValue(alertSheet), changed.getValue(alertSheet).jsonObject.str("from"))
        assertTrue("no earlier unit touches the sheet", (0..10).none { i -> alertSheet in unit(i).getValue("changed").jsonObject.keys })
        assertTrue("the bytes before S7-E5b3a are reviewed KRX-free", changed.getValue(alertSheet).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        for (path in changed.keys + e5b3aAdded) assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        assertTrue("no core exception belongs to S7-E5b3a", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b3a" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E57_theE5b3aUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b3a.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b3a(e5b3a).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b3a unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val sheetTo = changed.getValue(alertSheet).jsonObject.str("to")
        val broken = e5b3a.with("changed", JsonObject(changed + (alertSheet to changedEntry(someHash, sheetTo))))
        assertEquals("another from on the sheet: the broken link and the unreviewed history",
            listOf("from $alertSheet", "krx-before-unreviewed $alertSheet"),
            PostS6ApprovedDelta.parse(unitsWithE5b3a(broken).toString().toByteArray(), inventory, s64b, null).first)
        val forbiddenAdd = base + "data/entitlements/S7E5b3aProbe.kt"
        val withForbidden = e5b3a.with("added", JsonObject(e5b3a.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithE5b3a(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5b3b: the alert quotes and the section's editor entry (s7e5b3_api_agreed.r1.md D4, D5, D8), five KRX-free changes ----

    private val e5b3b get() = unit(12)
    private val e5b3bParent = "963835d6b9aac869a2face4c6b583558376af569"
    private val presenter = base + "ui/premium/PremiumTopicPresenter.kt"
    private val uiState = base + "ui/premium/PremiumTopicUiState.kt"
    private val e5b3bNew = setOf(presenter, uiState)
    private val e5b3bChanged = setOf(alertSection, bankSection, presenter, uiState, route)
    private fun unitsWithE5b3b(changedE5b3b: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), unit(9), unit(10), unit(11), changedE5b3b)

    @Test fun E58_theE5b3bUnitIsExact_andContinuesEveryChain() {
        assertEquals("premise: the thirteenth unit is S7-E5b3b", "S7-E5b3b", e5b3b.str("id"))
        assertEquals("its parent is the S7-E5b3a commit and its record the agreed E5b3 API",
            listOf(e5b3bParent, e5b3aApproval), listOf(e5b3b.str("source_parent"), e5b3b.str("approval_record_sha256")))
        val changed = e5b3b.getValue("changed").jsonObject
        assertEquals("the section, its holder, the presenter, the ui state and the route change", e5b3bChanged, changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), e5b3b.getValue("added").jsonObject.keys)
        val earlier = (0..11).map { unit(it) }
        for (path in e5b3bChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            val expected = when {
                previous == null -> productBase.getValue(path)
                path in previous.getValue("changed").jsonObject.keys -> previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to")
                else -> previous.getValue("added").jsonObject.getValue(path).jsonPrimitive.content
            }
            assertEquals("$path continues its chain", expected, changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S7-E5b3b are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in e5b3bNew) assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys })
        assertTrue("no core exception belongs to S7-E5b3b", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b3b" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E59_theE5b3bUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b3b.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b3b(e5b3b).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b3b unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in e5b3bChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = e5b3b.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithE5b3b(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S7E5b3bProbe.kt"
        val withForbidden = e5b3b.with("added", JsonObject(e5b3b.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithE5b3b(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5b4a: the shared user-intent DataStore and the dormant last-bank store (s7e5b4_api_agreed.r1.md §2.1), one KRX-free change and two additions ----

    private val e5b4a get() = unit(13)
    private val e5b4aParent = "82e28ffa1dad0468caa233b38b8d80f1b10991c3"
    private val e5b4aApproval = "7a96b3cab73c17d01f0356bde25c372b732a1849cb812aa0283bd3a5dca00cd2"
    private val rateRowStore = base + "data/local/RateRowPreferenceStore.kt"
    private val e5b4aAdded = setOf(base + "data/local/UserIntentDataStore.kt", base + "data/local/LastSelectedBankStore.kt")
    private fun unitsWithE5b4a(changedE5b4a: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), unit(9), unit(10), unit(11), unit(12), changedE5b4a)

    @Test fun E60_theE5b4aUnitIsExact_andStartsTheRateRowStoresChain() {
        assertEquals("premise: the fourteenth unit is S7-E5b4a", "S7-E5b4a", e5b4a.str("id"))
        assertEquals("its parent is the S7-E5b3b commit and its record the agreed E5b4 API",
            listOf(e5b4aParent, e5b4aApproval), listOf(e5b4a.str("source_parent"), e5b4a.str("approval_record_sha256")))
        val changed = e5b4a.getValue("changed").jsonObject
        assertEquals("only the rate row store changes", setOf(rateRowStore), changed.keys)
        assertEquals("and the shared delegate and the bank store are added", e5b4aAdded, e5b4a.getValue("added").jsonObject.keys)
        assertEquals("the rate row store starts from its product base", productBase.getValue(rateRowStore), changed.getValue(rateRowStore).jsonObject.str("from"))
        assertTrue("no earlier unit touches the rate row store", (0..12).none { i -> rateRowStore in unit(i).getValue("changed").jsonObject.keys })
        for (path in e5b4aAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", (0..12).none { i -> path in unit(i).getValue("changed").jsonObject.keys || path in unit(i).getValue("added").jsonObject.keys })
        }
        assertTrue("the bytes before S7-E5b4a are reviewed KRX-free", changed.getValue(rateRowStore).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        for (path in changed.keys + e5b4aAdded) assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        assertTrue("no core exception belongs to S7-E5b4a", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b4a" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E61_theE5b4aUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b4a.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b4a(e5b4a).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b4a unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val storeTo = changed.getValue(rateRowStore).jsonObject.str("to")
        val broken = e5b4a.with("changed", JsonObject(changed + (rateRowStore to changedEntry(someHash, storeTo))))
        assertEquals("another from on the rate row store: the broken link and the unreviewed history",
            listOf("from $rateRowStore", "krx-before-unreviewed $rateRowStore"),
            PostS6ApprovedDelta.parse(unitsWithE5b4a(broken).toString().toByteArray(), inventory, s64b, null).first)
        val existing = e5b4a.with("added", JsonObject(e5b4a.getValue("added").jsonObject + (rateRowStore to JsonPrimitive(someHash))))
        assertTrue("the changed store cannot also be added",
            PostS6ApprovedDelta.parse(unitsWithE5b4a(existing).toString().toByteArray(), inventory, s64b, null).first.any { it.startsWith("overlap") })
        val forbiddenAdd = base + "data/local/TopicLastKnownS7E5b4aProbe.kt"
        val withForbidden = e5b4a.with("added", JsonObject(e5b4a.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix of the same folder",
            PostS6ApprovedDelta.parse(unitsWithE5b4a(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5b4b: the memory, its wiring and the legacy migration (s7e5b4_api_agreed.r1.md §2.2–§2.6), six KRX-free changes and two additions ----

    private val e5b4b get() = unit(14)
    /** The S7-E5b4a commit. A placeholder in the reference tree; the land rebinds it to the real commit. */
    private val e5b4bParent = "426e4079876b7a0e7805baabea8da6c43fcc791b"
    private val cacheService = base + "data/local/CacheService.kt"
    private val e5b4bChanged = setOf(application, bankOwner, cacheService, alertSheet, base + "ui/alert/BankAlertEditor.kt", bankSection)
    private val e5b4bAdded = setOf(base + "data/local/LastSelectedBankMemory.kt", base + "data/local/LastBankSelectionMigration.kt")
    private fun unitsWithE5b4b(changedE5b4b: JsonObject) =
        withUnits(unit(0), unit(1), unit(2), unit(3), unit(4), unit(5), unit(6), unit(7), unit(8), unit(9), unit(10), unit(11), unit(12), unit(13), changedE5b4b)

    @Test fun E62_theE5b4bUnitIsExact_andContinuesEveryChain() {
        assertEquals("premise: the fifteenth unit is S7-E5b4b", "S7-E5b4b", e5b4b.str("id"))
        assertEquals("its parent is the S7-E5b4a commit and its record the agreed E5b4 API",
            listOf(e5b4bParent, e5b4aApproval), listOf(e5b4b.str("source_parent"), e5b4b.str("approval_record_sha256")))
        val changed = e5b4b.getValue("changed").jsonObject
        assertEquals("the application, the owner, the cache, the sheet, the editor model and the holder change", e5b4bChanged, changed.keys)
        assertEquals("and the memory and the migration are added", e5b4bAdded, e5b4b.getValue("added").jsonObject.keys)
        val earlier = (0..13).map { unit(it) }
        for (path in e5b4bChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            val expected = when {
                previous == null -> productBase.getValue(path)
                path in previous.getValue("changed").jsonObject.keys -> previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to")
                else -> previous.getValue("added").jsonObject.getValue(path).jsonPrimitive.content
            }
            assertEquals("$path continues its chain", expected, changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S7-E5b4b are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in e5b4bAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("cacheService enters the ledger here", earlier.none { cacheService in it.getValue("changed").jsonObject.keys })
        assertTrue("no core exception belongs to S7-E5b4b", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5b4b" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E63_theE5b4bUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5b4b.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5b4b(e5b4b).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5b4b unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in e5b4bChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = e5b4b.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithE5b4b(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/local/GraphSelectionS7E5b4bProbe.kt"
        val withForbidden = e5b4b.with("added", JsonObject(e5b4b.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix of the same folder",
            PostS6ApprovedDelta.parse(unitsWithE5b4b(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S7-E5d: the bank family's free alert preview (s7e5d_api_agreed.r1.md), three KRX-free changes and five additions ----

    private val e5d get() = unit(15)
    /** The S7-E5b4b commit. A placeholder in the reference tree; the land rebinds it to the real commit. */
    private val e5dParent = "069e29ff9bd3b27432105f28aeb5b8ad70f4dea6"
    private val e5dApproval = "d798497cda9f1d32850991a1dfa4b74bb14b2a1e8e7c36eb6d925b74f14c2439"
    /** The two free files enter the ledger here; the sheet continues its chain from S7-E5b4b. */
    private val e5dNew = setOf(base + "ui/free/FreeSnapshotScreen.kt", base + "ui/free/FreeSnapshotUiState.kt")
    private val e5dChanged = e5dNew + alertSheet
    private val e5dAdded = setOf(base + "ui/free/FreeAlertPreviewSession.kt", base + "ui/free/FreeBankAlertPreviewSheet.kt",
        base + "ui/free/FreeBankAlertPreviewSection.kt", base + "ui/components/SubscribeCTA.kt", base + "ui/alert/BankAlertEditorFields.kt",
        "src/e5dHooksDefault/java/com/jay/fxi/ui/free/PreviewHostHooksDefault.kt")
    private fun unitsWithE5d(changedE5d: JsonObject) = withUnits(*((0..14).map { unit(it) } + changedE5d).toTypedArray())

    @Test fun E64_theE5dUnitIsExact_andContinuesEveryChain() {
        assertEquals("premise: the sixteenth unit is S7-E5d", "S7-E5d", e5d.str("id"))
        assertEquals("its parent is the S7-E5b4b commit and its record the agreed E5d API",
            listOf(e5dParent, e5dApproval), listOf(e5d.str("source_parent"), e5d.str("approval_record_sha256")))
        val changed = e5d.getValue("changed").jsonObject
        assertEquals("the free screen, its state and the premium sheet change", e5dChanged, changed.keys)
        assertEquals("and the preview's five files and the hooks' no-op accessor are added", e5dAdded, e5d.getValue("added").jsonObject.keys)
        val earlier = (0..14).map { unit(it) }
        for (path in e5dChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            val expected = when {
                previous == null -> productBase.getValue(path)
                path in previous.getValue("changed").jsonObject.keys -> previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to")
                else -> previous.getValue("added").jsonObject.getValue(path).jsonPrimitive.content
            }
            assertEquals("$path continues its chain", expected, changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S7-E5d are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in e5dNew) assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys })
        for (path in e5dAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S7-E5d", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S7-E5d" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E65_theE5dUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = e5d.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithE5d(e5d).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered E5d unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in e5dChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = e5d.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithE5d(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/local/GraphSelectionS7E5dProbe.kt"
        val withForbidden = e5d.with("added", JsonObject(e5d.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix of the same folder",
            PostS6ApprovedDelta.parse(unitsWithE5d(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S8-2: the source family's five wire calls (s8_2_contract.agreed.r1.md), two KRX-free changes and no addition ----

    private val s82 get() = unit(16)
    /** The S7-U3a2 commit this unit branches from. */
    private val s82Parent = "dc0a7db1d21807e659bfa5a298d4025f9ed5bc69"
    /** The S7·S8 decision record whose decision 4 delegates the exchange × usdt-krw general paths to an exact-state review. */
    private val s82Approval = "fa7f5916ee94f501b905a4f7ff2bbb5a767c3888e202e37a429669f6e82739e2"
    private val s82Changed = setOf(api, repo)
    private fun unitsWithS82(changedS82: JsonObject) = withUnits(*((0..15).map { unit(it) } + changedS82).toTypedArray())

    @Test fun E66_theS82UnitIsExact_andContinuesBothChains() {
        assertEquals("premise: the seventeenth unit is S8-2", "S8-2", s82.str("id"))
        assertEquals("its parent is the S7-U3a2 commit and its record the agreed S7·S8 decisions",
            listOf(s82Parent, s82Approval), listOf(s82.str("source_parent"), s82.str("approval_record_sha256")))
        val changed = s82.getValue("changed").jsonObject
        assertEquals("the authenticated api service and the alert repository change", s82Changed, changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), s82.getValue("added").jsonObject.keys)
        val earlier = (0..15).map { unit(it) }
        for (path in s82Changed) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertTrue("premise: an earlier unit changed $path", previous != null && path in previous.getValue("changed").jsonObject.keys)
            assertEquals("$path continues its chain", checkNotNull(previous).getValue("changed").jsonObject.getValue(path).jsonObject.str("to"),
                changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S8-2 are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-2", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-2" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E67_theS82UnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = s82.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS82(s82).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-2 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s82Changed) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s82.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS82(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/remote/TopicS82Probe.kt"
        val withForbidden = s82.with("added", JsonObject(s82.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix of the same folder",
            PostS6ApprovedDelta.parse(unitsWithS82(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S8-3: the source settings owner and its row policy (s8_3_contract agreed), one KRX-free change and two additions ----

    private val s83 get() = unit(17)
    /** The S8-2 commit this unit branches from. */
    private val s83Parent = "a142ca2264fbf4c9987ceae9389ba62a7b94f2b6"
    private val s83Changed = setOf(registry)
    private val s83Added = setOf(base + "data/alerts/SourceAlertSettingsOwner.kt", base + "data/alerts/SourceAlertRowPolicy.kt")
    private fun unitsWithS83(changedS83: JsonObject) = withUnits(*((0..16).map { unit(it) } + changedS83).toTypedArray())

    @Test fun E68_theS83UnitIsExact_andContinuesTheRegistryChain() {
        assertEquals("premise: the eighteenth unit is S8-3", "S8-3", s83.str("id"))
        assertEquals("its parent is the S8-2 commit and its record the agreed S7·S8 decisions",
            listOf(s83Parent, s82Approval), listOf(s83.str("source_parent"), s83.str("approval_record_sha256")))
        val changed = s83.getValue("changed").jsonObject
        assertEquals("the registry changes", s83Changed, changed.keys)
        assertEquals("and the source owner and its row policy are added", s83Added, s83.getValue("added").jsonObject.keys)
        val earlier = (0..16).map { unit(it) }
        for (path in s83Changed) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertTrue("premise: an earlier unit changed $path", previous != null && path in previous.getValue("changed").jsonObject.keys)
            assertEquals("$path continues its chain", checkNotNull(previous).getValue("changed").jsonObject.getValue(path).jsonObject.str("to"),
                changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S8-3 are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in s83Added) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-3", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-3" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E69_theS83UnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = s83.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS83(s83).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-3 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s83Changed) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s83.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS83(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S83Probe.kt"
        val withForbidden = s83.with("added", JsonObject(s83.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS83(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S8-4a: the tether tab's read section (s8_4a_contract agreed), two KRX-free changes and one addition ------------------

    private val s84a get() = unit(18)
    /** The S8-3 commit this unit branches from. */
    private val s84aParent = "b8b191e548dc4429cfe0f6c57dfd3679bdc20f04"
    private val s84aChanged = setOf(registry, route)
    private val s84aAdded = setOf(base + "ui/alert/SourceAlertSection.kt")
    private fun unitsWithS84a(changedS84a: JsonObject) = withUnits(*((0..17).map { unit(it) } + changedS84a).toTypedArray())

    @Test fun E70_theS84aUnitIsExact_andContinuesTheRegistryAndRouteChains() {
        assertEquals("premise: the nineteenth unit is S8-4a", "S8-4a", s84a.str("id"))
        assertEquals("its parent is the S8-3 commit and its record the agreed S7·S8 decisions",
            listOf(s84aParent, s82Approval), listOf(s84a.str("source_parent"), s84a.str("approval_record_sha256")))
        val changed = s84a.getValue("changed").jsonObject
        assertEquals("the registry and the route change", s84aChanged, changed.keys)
        assertEquals("and the tether section is added", s84aAdded, s84a.getValue("added").jsonObject.keys)
        val earlier = (0..17).map { unit(it) }
        for (path in s84aChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertTrue("premise: an earlier unit changed $path", previous != null && path in previous.getValue("changed").jsonObject.keys)
            assertEquals("$path continues its chain", checkNotNull(previous).getValue("changed").jsonObject.getValue(path).jsonObject.str("to"),
                changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S8-4a are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in s84aAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-4a", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-4a" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E71_theS84aUnitRefusesAnotherFromAnUnreviewedOneAndAForbiddenAddition() {
        val changed = s84a.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS84a(s84a).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-4a unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s84aChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s84a.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS84a(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S84aProbe.kt"
        val withForbidden = s84a.with("added", JsonObject(s84a.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS84a(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
    }

    // ---- S8-4b: the tether tab's history session (s8_4b_contract agreed), one KRX-free change and three additions ----------

    private val s84b get() = unit(19)
    /** The S8-4a commit this unit branches from. */
    private val s84bParent = "d87bbd551047432d97f323a2a0b1a832b8789b0b"
    private val s84bChanged = setOf(route)
    private val s84bAdded = setOf("SourceHistoryEntry.kt", "SourceHistoryStateHolder.kt", "SourceHistorySheet.kt").map { base + "ui/alert/$it" }.toSet()
    private fun unitsWithS84b(changedS84b: JsonObject) = withUnits(*((0..18).map { unit(it) } + changedS84b).toTypedArray())

    @Test fun E72_theS84bUnitIsExact_andContinuesTheRouteChain() {
        assertEquals("premise: the twentieth unit is S8-4b", "S8-4b", s84b.str("id"))
        assertEquals("its parent is the S8-4a commit and its record the agreed S7·S8 decisions",
            listOf(s84bParent, s82Approval), listOf(s84b.str("source_parent"), s84b.str("approval_record_sha256")))
        val changed = s84b.getValue("changed").jsonObject
        assertEquals("the route changes", s84bChanged, changed.keys)
        assertEquals("and the history's three files are added", s84bAdded, s84b.getValue("added").jsonObject.keys)
        val earlier = (0..18).map { unit(it) }
        for (path in s84bChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertTrue("premise: an earlier unit changed $path", previous != null && path in previous.getValue("changed").jsonObject.keys)
            assertEquals("$path continues its chain from S8-4a", checkNotNull(previous).getValue("changed").jsonObject.getValue(path).jsonObject.str("to"),
                changed.getValue(path).jsonObject.str("from"))
            assertEquals("the previous change is S8-4a's", "S8-4a", previous.str("id"))
            assertTrue("$path: the bytes before S8-4b are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in s84bAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-4b", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-4b" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E73_theS84bUnitRefusesABrokenFromAForbiddenAdditionAndAnAlteredAddition() {
        val changed = s84b.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS84b(s84b).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-4b unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s84bChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s84b.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS84b(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S84bProbe.kt"
        val withForbidden = s84b.with("added", JsonObject(s84b.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS84b(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        for (path in s84bAdded) {
            assertEquals("$path at other bytes is refused", listOf("actual-hash $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))))
            assertEquals("$path missing is refused", listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
        }
    }

    // ---- S8-5a: the exchange family's four writes (s8_5a_contract agreed), S8-3's two additions changed again, no addition ----

    private val s85a get() = unit(20)
    /** The S8-4b commit this unit branches from. */
    private val s85aParent = "34763f87aea977bc6e9fc2fa3bdf59c4f7785049"
    private val s85aChanged = setOf("SourceAlertSettingsOwner.kt", "SourceAlertRowPolicy.kt").map { base + "data/alerts/$it" }.toSet()
    private fun unitsWithS85a(changedS85a: JsonObject) = withUnits(*((0..19).map { unit(it) } + changedS85a).toTypedArray())

    @Test fun E74_theS85aUnitIsExact_andContinuesTheS83AdditionsChains() {
        assertEquals("premise: the twenty-first unit is S8-5a", "S8-5a", s85a.str("id"))
        assertEquals("its parent is the S8-4b commit and its record the agreed S7·S8 decisions",
            listOf(s85aParent, s82Approval), listOf(s85a.str("source_parent"), s85a.str("approval_record_sha256")))
        val changed = s85a.getValue("changed").jsonObject
        assertEquals("the source owner and its row policy change", s85aChanged, changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), s85a.getValue("added").jsonObject.keys)
        val earlier = (0..19).map { unit(it) }
        for (path in s85aChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertTrue("premise: S8-3 added $path", previous != null && previous.str("id") == "S8-3" && path in previous.getValue("added").jsonObject.keys)
            assertEquals("$path continues from S8-3's addition", checkNotNull(previous).getValue("added").jsonObject.getValue(path).jsonPrimitive.content,
                changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S8-5a are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-5a", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-5a" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E75_theS85aUnitRefusesABrokenFromAForbiddenAdditionAndAlteredBytes() {
        val changed = s85a.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS85a(s85a).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-5a unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s85aChanged) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s85a.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS85a(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S85aProbe.kt"
        val withForbidden = s85a.with("added", JsonObject(s85a.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS85a(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        for (path in s85aChanged) {
            assertEquals("$path at other bytes is refused", listOf("actual-hash $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))))
            assertEquals("$path missing is refused", listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
        }
    }

    // ---- S8-5b: the tether alert editor and row controls (s8_5b_contract agreed), four earlier files changed again, three added ----

    private val s85b get() = unit(21)
    /** The S8-5a commit this unit branches from. */
    private val s85bParent = "889dde0babdc6ad2c749d78a2d5e9cb3226a87b3"
    /** Each changed path and the unit that last wrote it before S8-5b. */
    private val s85bChanged = mapOf(
        base + "ui/alert/SourceAlertSection.kt" to "S8-4a",
        base + "ui/premium/view/PremiumTopicRoute.kt" to "S8-4b",
        base + "ui/premium/view/PremiumTopicScreen.kt" to "S7-c",
        base + "ui/premium/PremiumTopicPresenter.kt" to "S7-E5b3b")
    private val s85bAdded = setOf(base + "ui/alert/SourceAlertEditor.kt", base + "ui/alert/SourceAlertEditorSheet.kt",
        base + "data/local/LastSelectedSourceMemory.kt")
    private fun unitsWithS85b(changedS85b: JsonObject) = withUnits(*((0..20).map { unit(it) } + changedS85b).toTypedArray())

    @Test fun E76_theS85bUnitIsExact_andContinuesEachChainFromItsLastWriter() {
        assertEquals("premise: the twenty-second unit is S8-5b", "S8-5b", s85b.str("id"))
        assertEquals("its parent is the S8-5a commit and its record the agreed S7·S8 decisions",
            listOf(s85bParent, s82Approval), listOf(s85b.str("source_parent"), s85b.str("approval_record_sha256")))
        val changed = s85b.getValue("changed").jsonObject
        assertEquals("the section, the route, the screen and the presenter change", s85bChanged.keys, changed.keys)
        assertEquals("and the editor, its sheet and the source memory are added", s85bAdded, s85b.getValue("added").jsonObject.keys)
        val earlier = (0..20).map { unit(it) }
        for ((path, writer) in s85bChanged) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertEquals("premise: $writer last wrote $path", writer, previous?.str("id"))
            val previousTo = if (path in checkNotNull(previous).getValue("changed").jsonObject.keys)
                previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to")
            else previous.getValue("added").jsonObject.getValue(path).jsonPrimitive.content
            assertEquals("$path continues from $writer", previousTo, changed.getValue(path).jsonObject.str("from"))
            assertTrue("$path: the bytes before S8-5b are reviewed KRX-free", changed.getValue(path).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        for (path in s85bAdded) {
            assertFalse("$path is new to the product", path in productBase)
            assertTrue("$path enters the ledger here", earlier.none { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys })
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertTrue("no core exception belongs to S8-5b", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-5b" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E77_theS85bUnitRefusesABrokenFromAForbiddenAdditionAndAlteredBytes() {
        val changed = s85b.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS85b(s85b).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-5b unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s85bChanged.keys) {
            val to = changed.getValue(path).jsonObject.str("to")
            val broken = s85b.with("changed", JsonObject(changed + (path to changedEntry(someHash, to))))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"),
                PostS6ApprovedDelta.parse(unitsWithS85b(broken).toString().toByteArray(), inventory, s64b, null).first)
        }
        val forbiddenAdd = base + "data/entitlements/S85bProbe.kt"
        val withForbidden = s85b.with("added", JsonObject(s85b.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS85b(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        val presenter = base + "ui/premium/PremiumTopicPresenter.kt"
        val withoutPresenter = s85b.with("changed", JsonObject(changed - presenter))
        // Every later unit stays, so the omitted presenter is the only path the checkout no longer matches.
        val later = (22 until json.getValue("units").jsonArray.size).map { unit(it) }
        val (omissionErrors, omissionLedger) = PostS6ApprovedDelta.parse(
            withUnits(*((0..20).map { unit(it) } + withoutPresenter + later).toTypedArray()).toString().toByteArray(), inventory, s64b, null)
        assertEquals(listOf("from $presenter"), omissionErrors)
        assertNull(omissionLedger)
        val prefix = withUnits(*((0..20).map { unit(it) } + withoutPresenter + (22..29).map { unit(it) }).toTypedArray())
        val (prefixErrors, foldedPrefix) = PostS6ApprovedDelta.parse(prefix.toString().toByteArray(), inventory, s64b, null)
        assertEquals(emptyList<String>(), prefixErrors)
        assertEquals("the omitted presenter no longer matches the checkout", listOf("actual-hash $presenter"),
            PostS6ApprovedDelta.actualErrors(checkNotNull(foldedPrefix), s94aSources(actual)))

        val priorUnit = (0..20).map { unit(it) }.single { it.str("id") == "S7-E5b3b" }
        val priorFrom = priorUnit.getValue("changed").jsonObject.getValue(presenter).jsonObject.str("from")
        val currentEntry = changed.getValue(presenter).jsonObject
        assertTrue("the earlier presenter from was reviewed", priorFrom in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the earlier from differs from the required predecessor", priorFrom == currentEntry.str("from"))
        val reused = s85b.with("changed", JsonObject(changed +
            (presenter to changedEntry(priorFrom, currentEntry.str("to")))))
        assertEquals("a reviewed but stale presenter from breaks only the chain", listOf("from $presenter"),
            errors(unitsWithS85b(reused)))

        for (path in s85bChanged.keys + s85bAdded) {
            assertEquals("$path at other bytes is refused", listOf("actual-hash $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))))
            assertEquals("$path missing is refused", listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
        }
    }

    // ---- S8-5c: a bank toggle's acceptance outdates an older GET (s8_5c_scope agreed), the bank owner changed again, no addition ----

    private val s85c get() = unit(22)
    /** The S8-5b commit this unit branches from. */
    private val s85cParent = "707c3c4e3264163fea8552404aa1cc26b98915c6"
    /** R4c/S8/s8_5c_scope.agreed.record.r1.md, the Claude·Codex scope agreement under the S6 freeze decision A §4. */
    private val s85cApproval = "8932b861e56a63c32bbbcdcfc018e6ce9859b2d30e802bef9934e4a1d06b7bbc"
    private val s85cOwner = base + "data/alerts/BankAlertSettingsOwner.kt"
    private fun unitsWithS85c(changedS85c: JsonObject) = withUnits(*((0..21).map { unit(it) } + changedS85c).toTypedArray())

    @Test fun E78_theS85cUnitIsExact_andContinuesTheBankOwnerFromItsLastWriter() {
        assertEquals("premise: the twenty-third unit is S8-5c", "S8-5c", s85c.str("id"))
        assertEquals("its parent is the S8-5b commit and its record the agreed S8-5c scope",
            listOf(s85cParent, s85cApproval), listOf(s85c.str("source_parent"), s85c.str("approval_record_sha256")))
        val changed = s85c.getValue("changed").jsonObject
        assertEquals("only the bank owner changes", setOf(s85cOwner), changed.keys)
        assertEquals("and nothing is added", emptySet<String>(), s85c.getValue("added").jsonObject.keys)
        val earlier = (0..21).map { unit(it) }
        val previous = earlier.lastOrNull { s85cOwner in it.getValue("changed").jsonObject.keys || s85cOwner in it.getValue("added").jsonObject.keys }
        assertEquals("premise: S7-E5b4b last wrote the bank owner", "S7-E5b4b", previous?.str("id"))
        assertEquals("the bank owner continues from S7-E5b4b", checkNotNull(previous).getValue("changed").jsonObject.getValue(s85cOwner).jsonObject.str("to"),
            changed.getValue(s85cOwner).jsonObject.str("from"))
        assertTrue("the bytes before S8-5c are reviewed KRX-free", changed.getValue(s85cOwner).jsonObject.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the bank owner is no forbidden S6 surface", s85cOwner in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { s85cOwner.startsWith(it) })
        assertTrue("no core exception belongs to S8-5c", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-5c" })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test fun E79_theS85cUnitRefusesABrokenOrStaleFromAForbiddenAdditionAnOmissionAndAlteredBytes() {
        val changed = s85c.getValue("changed").jsonObject
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS85c(s85c).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-5c unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val to = changed.getValue(s85cOwner).jsonObject.str("to")
        val broken = s85c.with("changed", JsonObject(changed + (s85cOwner to changedEntry(someHash, to))))
        assertEquals("another from on the bank owner: the broken link and the unreviewed history",
            listOf("from $s85cOwner", "krx-before-unreviewed $s85cOwner"),
            PostS6ApprovedDelta.parse(unitsWithS85c(broken).toString().toByteArray(), inventory, s64b, null).first)

        val priorFrom = (0..21).map { unit(it) }.single { it.str("id") == "S7-E5b4b" }
            .getValue("changed").jsonObject.getValue(s85cOwner).jsonObject.str("from")
        assertTrue("the earlier bank owner from was reviewed", priorFrom in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse("the earlier from differs from the required predecessor", priorFrom == changed.getValue(s85cOwner).jsonObject.str("from"))
        val reused = s85c.with("changed", JsonObject(changed + (s85cOwner to changedEntry(priorFrom, to))))
        assertEquals("a reviewed but stale bank owner from breaks only the chain", listOf("from $s85cOwner"), errors(unitsWithS85c(reused)))

        val forbiddenAdd = base + "data/entitlements/S85cProbe.kt"
        val withForbidden = s85c.with("added", JsonObject(s85c.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix",
            PostS6ApprovedDelta.parse(unitsWithS85c(withForbidden).toString().toByteArray(), inventory, s64b, null).first.contains("forbidden $forbiddenAdd"))

        val actual = PostS6ApprovedDelta.actualSources()
        assertEquals("a unit without its change is refused", listOf("unit-empty S8-5c"),
            errors(unitsWithS85c(s85c.with("changed", JsonObject(emptyMap())))))
        val (omissionErrors, omissionLedger) = PostS6ApprovedDelta.parse(
            withUnits(*((0..21).map { unit(it) } + (23 until json.getValue("units").jsonArray.size).map { unit(it) }).toTypedArray())
                .toString().toByteArray(), inventory, s64b, null)
        assertEquals("omitting the unit, later units kept, leaves a well-formed history", emptyList<String>(), omissionErrors)
        assertEquals("the omitted bank owner no longer matches the checkout", listOf("actual-hash $s85cOwner"),
            PostS6ApprovedDelta.actualErrors(checkNotNull(omissionLedger), actual))

        val ledger = PostS6ApprovedDelta.ledger()
        assertEquals("the bank owner at other bytes is refused", listOf("actual-hash $s85cOwner"),
            PostS6ApprovedDelta.actualErrors(ledger, actual + (s85cOwner to actual.getValue(s85cOwner) + byteArrayOf(10))))
        assertEquals("the bank owner missing is refused", listOf("missing $s85cOwner"), PostS6ApprovedDelta.actualErrors(ledger, actual - s85cOwner))
    }

    // ---- S8-6: the exchange source push and the KRX-switch-OFF tap (R4c/S8/user_decision_s8_6.r1.md), two protected links ----

    private val s86 get() = unit(23)
    /** The S8-5c commit this unit branches from. */
    private val s86Parent = "6d85e9ec25711eab2b839d39f2e429db019f8e81"
    /** R4c/S8/user_decision_s8_6.r1.md: the user's decision delegated to Claude·Codex, both A, binding decision 4's protected diff. */
    private val s86Approval = "2b73b32b65a5b9df5f685a7a014e61211c3af90c3f6e9ec2f0a112d483417875"
    /** Each changed path and the unit that last wrote it before S8-6. */
    private val s86Changed = mapOf(messaging to "S7-E5c", activity to "S7-E5c", registry to "S8-4a",
        base + "data/alerts/SourceAlertSettingsOwner.kt" to "S8-5a")
    private val s86Protected = setOf(messaging, activity)
    private val s86Cores get() = PostS6ApprovedDelta.CORE_EXCEPTIONS.filter { it.unitId == "S8-6" }
    private fun s86Entry(path: String) = s86.getValue("changed").jsonObject.getValue(path).jsonObject
    private fun s86With(path: String, entry: JsonObject) = s86.with("changed", JsonObject(s86.getValue("changed").jsonObject + (path to entry)))
    private fun unitsWithS86(changedS86: JsonObject) = withUnits(*((0..22).map { unit(it) } + changedS86).toTypedArray())

    @Test fun E80_theS86UnitIsExact_continuesEachChain_andItsTwoProtectedLinksAreItsOwnExceptions() {
        assertEquals("premise: the twenty-fourth unit is S8-6", "S8-6", s86.str("id"))
        assertEquals("its parent is the S8-5c commit and its record the delegated decision",
            listOf(s86Parent, s86Approval), listOf(s86.str("source_parent"), s86.str("approval_record_sha256")))
        assertEquals("the service, the activity, the registry and the source owner change", s86Changed.keys, s86.getValue("changed").jsonObject.keys)
        assertEquals("and nothing is added", emptySet<String>(), s86.getValue("added").jsonObject.keys)
        val earlier = (0..22).map { unit(it) }
        for ((path, writer) in s86Changed) {
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            assertEquals("premise: $writer last wrote $path", writer, previous?.str("id"))
            assertEquals("$path continues from $writer", checkNotNull(previous).getValue("changed").jsonObject.getValue(path).jsonObject.str("to"),
                s86Entry(path).str("from"))
        }
        for (path in s86Changed.keys - s86Protected) {
            assertTrue("$path: the bytes before S8-6 are reviewed KRX-free", s86Entry(path).str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            assertFalse("$path is no forbidden S6 surface", path in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
                PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
        }
        assertEquals("two exceptions, the service and the activity", listOf(messaging, activity), s86Cores.map { it.path })
        for (core in s86Cores) {
            val entry = s86Entry(core.path)
            assertEquals("${core.path}: the exception is this unit's exact link", listOf(s86Parent, s86Approval, entry.str("from"), entry.str("to")),
                listOf(core.sourceParent, core.approvalRecordSha256, core.from, core.to))
            assertTrue("premise: ${core.path} is a forbidden S6 surface", core.path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
            assertEquals("${core.path}: the KRX lines are S7-E5c's, unchanged",
                PostS6ApprovedDelta.CORE_EXCEPTIONS.single { it.unitId == "S7-E5c" && it.path == core.path }.krxLinesSha256, core.krxLinesSha256)
            assertEquals("${core.path}: the final bytes keep the pinned KRX lines", core.krxLinesSha256,
                PostS6ApprovedDelta.krxLinesSha256(File(core.path).readText()))
        }
        assertEquals(emptyList<String>(), errors(json))
    }

    // Edits use zero-based, half-open ranges of UTF-8 source lines.
    private fun ByteArray.restoreLines(edits: MutableList<String>.() -> Unit): ByteArray {
        val lines = toString(Charsets.UTF_8).split('\n').toMutableList()
        lines.edits()
        return lines.joinToString("\n").toByteArray()
    }
    private fun MutableList<String>.replaceLines(start: Int, end: Int, vararg original: String) {
        subList(start, end).clear()
        addAll(start, original.asList())
    }

    // Reconstruct the approved prefix from the checkout without requiring Git history in a shallow CI checkout.
    // Each edit restores a zero-based, half-open line range to its text in 6d85e9ec. The clean control
    // verifies every reconstructed byte against the parsed prefix before introducing the four successors.
    private fun s86ParentSources(actual: Map<String, ByteArray>): Map<String, ByteArray> {
        val before = actual.toMutableMap()
        fun restore(path: String, edits: MutableList<String>.() -> Unit) {
            before[path] = actual.getValue(path).restoreLines(edits)
        }
        restore(activity) {
            replaceLines(117, 121)
        }
        restore(registry) {
            replaceLines(197, 241)
            replaceLines(147, 163)
            replaceLines(82, 91)
            replaceLines(74, 75,
                "        listOf({ bank.get() }, { source.get() }),")
            replaceLines(69, 70)
            replaceLines(63, 65,
                "    private val foregroundHooks: List<() -> Unit> = emptyList()")
            replaceLines(44, 51)
            replaceLines(14, 19)
        }
        restore(base + "data/alerts/SourceAlertSettingsOwner.kt") {
            replaceLines(1227, 1228,
                "            val use = access.acquire()")
            replaceLines(1208, 1209)
            replaceLines(1200, 1201)
            replaceLines(1141, 1150)
            replaceLines(1124, 1125)
            replaceLines(1107, 1111)
            replaceLines(1070, 1072,
                "    private fun begin(automatic: Boolean, manual: Boolean) {",
                "        val chain = Chain(generation, automatic, manual)")
            replaceLines(401, 435)
            replaceLines(389, 390)
            replaceLines(384, 385)
            replaceLines(358, 359)
            replaceLines(340, 341,
                "        val chain = active")
            replaceLines(320, 321,
                "        val chain = active")
            replaceLines(314, 315,
                "        val success = lastSuccessAt",
                "        if (reconcileOwed == null && mutableState.value.settings != null && success != null && remaining(addDelay(success, 30_000)) > 0) return")
            replaceLines(306, 311,
                "        active?.let {")
            replaceLines(293, 304)
            replaceLines(258, 260,
                "    private class Chain(val generation: Long, var automatic: Boolean, var manual: Boolean) {")
            replaceLines(159, 161)
        }
        restore(api) {
            replaceLines(373, 374,
                "        transport.executeRead(owner) { service.getComparisonNotificationLogs(it, tab, diffType, limit) }")
            replaceLines(370, 372,
                "        limit: Int")
            replaceLines(356, 362,
                "    suspend fun deleteComparisonAlert(owner: AuthSnapshot, id: Int): AuthenticatedHttpResponse<Unit> =",
                "        transport.executeMutation(owner) { service.deleteComparisonAlert(it, id) }")
            replaceLines(352, 353,
                "        transport.executeMutation(owner) { service.updateComparisonAlert(it, id, request) }")
            replaceLines(349, 351,
                "        request: ComparisonAlertUpdateRequest")
            replaceLines(337, 343,
                "    suspend fun createComparisonAlert(owner: AuthSnapshot, request: ComparisonAlertRequest): AuthenticatedHttpResponse<ComparisonAlertSetting> =",
                "        transport.executeMutation(owner) { service.createComparisonAlert(it, request) }")
            replaceLines(324, 334,
                "    suspend fun getComparisonAlerts(owner: AuthSnapshot, tab: String?): AuthenticatedHttpResponse<ComparisonAlertsResponse> =",
                "        transport.executeRead(owner) { service.getComparisonAlerts(it, tab) }")
        }
        restore(repo) {
            replaceLines(311, 312,
                "            requireCurrent = { apiService.requireCurrent(owner) },")
            replaceLines(283, 284,
                "                requireCurrent = { apiService.requireCurrent(owner) },")
            replaceLines(272, 277)
            replaceLines(269, 270)
            replaceLines(253, 254,
                "        return safeApiCall(owner, { evidence }) { snapshot ->")
            replaceLines(248, 249)
            replaceLines(172, 182,
                "        limit: Int = 100",
                "    ): AuthBoundResult<List<ComparisonAlertHistoryItem>> = safeApiCall(owner) { snapshot ->",
                "        apiService.getComparisonNotificationLogs(snapshot, tab, diffType, limit)",
                "            .requireBody(\"GET /api/comparison-notification-logs\")",
                "            .logs")
            replaceLines(157, 166,
                "    /** Idempotent on the server: an already deleted alert also succeeds. */",
                "    suspend fun deleteComparisonAlert(owner: AuthIdentityFence, id: Int): AuthBoundResult<Unit> =",
                "        safeApiCall(owner) { snapshot ->",
                "            val response = apiService.deleteComparisonAlert(snapshot, id)",
                "            response.failure?.let { throw AuthenticatedApiException(it) }",
                "        }")
            replaceLines(146, 156,
                "    suspend fun updateComparisonAlert(owner: AuthIdentityFence, id: Int, request: ComparisonAlertUpdateRequest): AuthBoundResult<ComparisonAlertSetting> =",
                "        safeApiCall(owner) { snapshot ->",
                "            apiService.updateComparisonAlert(snapshot, id, request)",
                "                .requireBody(\"PUT /api/comparison-alerts/{id}\")",
                "        }")
            replaceLines(135, 144,
                "    suspend fun createComparisonAlert(owner: AuthIdentityFence, request: ComparisonAlertRequest): AuthBoundResult<ComparisonAlertSetting> =",
                "        safeApiCall(owner) { snapshot ->",
                "            apiService.createComparisonAlert(snapshot, request)",
                "                .requireBody(\"POST /api/comparison-alerts\")",
                "        }")
            replaceLines(133, 134)
            replaceLines(117, 130,
                "    /** Comparison / kimchi-premium alerts; [tab] null is every tab. */",
                "    suspend fun getComparisonAlerts(owner: AuthIdentityFence, tab: String? = null): AuthBoundResult<List<ComparisonAlertSetting>> =",
                "        safeApiCall(owner) { snapshot ->",
                "            apiService.getComparisonAlerts(snapshot, tab)")
        }
        restore(messaging) {
            replaceLines(124, 125,
                "        notificationManager.notify(notificationId, notification)")
            replaceLines(107, 108)
            replaceLines(101, 104)
            replaceLines(86, 88,
                "    private fun showAlertNotification(message: RemoteMessage, type: String) {",
                "        if (!ReleaseAdmission.isOpen) return")
            replaceLines(57, 65)
        }
        restore(base + "ui/alert/SourceAlertEditor.kt") {
            replaceLines(256, 257,
                "    private fun usableQuote(quote: Double?): Double? = quote?.takeIf { it.isFinite() && it > 0 }")
            replaceLines(254, 255,
                "    fun formatThreshold(value: Double): String = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString()")
            replaceLines(247, 248,
                "            \"조건 충족 동안 \${interval.everyLabel} 발송\",")
            replaceLines(242, 243,
                "            ?: return SourceAlertRepeatTexts(\"한번만\", \"조건 충족 시 1회만 발송\", \"알림은 1회 발송 후 자동으로 꺼집니다\", false)")
            replaceLines(111, 112,
                "    fun range(quote: Double?): ClosedFloatingPointRange<Double>? {",
                "        val q = usableQuote(quote) ?: return null",
                "        val lower = floor(q * 0.5 / 10.0 + 0.5) * 10.0",
                "        val upper = floor(q * 1.5 / 10.0 + 0.5) * 10.0",
                "        return if (lower.isFinite() && upper.isFinite()) lower..upper else null",
                "    }")
            replaceLines(107, 109,
                "    ): SourceAlertEditorInput = input.copy(",
                "        source = source,",
                "        thresholdText = if (mode == SourceAlertEditorMode.Add) {",
                "            usableQuote(quotes[source])?.let(::formatThreshold) ?: input.thresholdText",
                "        } else {",
                "            input.thresholdText",
                "        }",
                "    )")
            replaceLines(92, 93,
                "        SourceAlertEditorMode.Add -> {",
                "            val source = remembered?.takeIf { it in sources } ?: sources.first()",
                "            SourceAlertEditorInput(",
                "                source = source,",
                "                condition = AlertCondition.BELOW,",
                "                thresholdText = usableQuote(quotes[source])?.let(::formatThreshold).orEmpty(),",
                "                repeat = RepeatIntervalChange.Once",
                "            )",
                "        }")
            replaceLines(13, 13,
                "import kotlin.math.floor")
        }
        restore(base + "ui/free/FreeSnapshotScreen.kt") {
            replaceLines(424, 428)
            replaceLines(325, 327)
            replaceLines(283, 284)
            replaceLines(277, 278)
            replaceLines(263, 264)
            replaceLines(257, 258)
            replaceLines(192, 193)
            replaceLines(169, 176,
                "            FreeSnapshotTabs(selected, state, pageState, onSelectTab, onSelectPeriod, onToggleSeries,",
                "                onApplyRowPreference, onExpand = { fullscreen = true }, onSubscribe = onSubscribe, onOpenPreview = openPreview,",
                "                onPage = hooks::onPage)")
        }
        restore(base + "ui/free/FreeSnapshotUiState.kt") {
            replaceLines(166, 168)
            replaceLines(160, 161)
            replaceLines(98, 106,
                "    val previewAsOf: Instant? = null")
        }
        restore(route) {
            replaceLines(273, 281)
            replaceLines(236, 240)
            replaceLines(223, 224,
                "                        { tab, _ ->")
            replaceLines(82, 84,
                "    sourceHistoryWiring: SourceHistoryWiringFactory = ProductionSourceHistoryWiring")
            replaceLines(25, 28)
        }
        return before
    }

    @Test fun E81_theS86UnitRefusesAnotherLinkForAProtectedPath_aBrokenGeneralFrom_andAlteredBytes() {
        val (control, controlLedger) = PostS6ApprovedDelta.parse(unitsWithS86(s86).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-6 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        for (path in s86Protected) {
            val entry = s86Entry(path)
            for ((label, other) in listOf(
                "another id" to s86.with("id", JsonPrimitive("S8-7")),
                "another parent" to s86.with("source_parent", JsonPrimitive("1".repeat(40))),
                "another approval" to s86.with("approval_record_sha256", JsonPrimitive(someHash)),
                "another to" to s86With(path, changedEntry(entry.str("from"), someHash))
            )) {
                assertTrue("$path $label", errors(unitsWithS86(other)).contains("forbidden $path"))
            }
        }
        for (path in s86Changed.keys - s86Protected) {
            val broken = s86With(path, changedEntry(someHash, s86Entry(path).str("to")))
            assertEquals("another from on $path: the broken link and the unreviewed history",
                listOf("from $path", "krx-before-unreviewed $path"), errors(unitsWithS86(broken)))
        }
        val forbiddenAdd = base + "data/entitlements/S86Probe.kt"
        val withForbidden = s86.with("added", JsonObject(mapOf(forbiddenAdd to JsonPrimitive(someHash))))
        assertTrue("an addition under a forbidden prefix", errors(unitsWithS86(withForbidden)).contains("forbidden $forbiddenAdd"))
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        val later = (24 until json.getValue("units").jsonArray.size).map { unit(it) }
        val (omissionErrors, omissionLedger) = PostS6ApprovedDelta.parse(
            withUnits(*((0..22).map { unit(it) } + later).toTypedArray()).toString().toByteArray(), inventory, s64b, null)
        assertEquals("S9-2b cannot follow the S8-4a registry without S8-6", listOf("from $registry"), omissionErrors)
        assertEquals(null, omissionLedger)

        val prefix = withUnits(*(0..22).map { unit(it) }.toTypedArray())
        val (prefixErrors, prefixLedger) = PostS6ApprovedDelta.parse(prefix.toString().toByteArray(), inventory, s64b, null)
        assertEquals(emptyList<String>(), prefixErrors)
        val laterAdditions = later.flatMap { it.getValue("added").jsonObject.keys }
        val before = s86ParentSources(s92bSources(actual) - laterAdditions.toSet())
        val historical = checkNotNull(prefixLedger)
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(historical, before))
        val successors = s86Changed.keys.associateWith { path ->
            // S9-2b appended one provider to the S8-6 registry constructor and its refresher list.
            val bytes = if (path == registry) actual.getValue(path).toString(Charsets.UTF_8)
                .replace("        comparison: Provider<ComparisonAlertSettingsOwner>,\n", "")
                .replace(", { comparison.get() }", "").toByteArray() else actual.getValue(path)
            assertEquals("S8-6 successor bytes for $path", s86Entry(path).str("to"), PostS6ApprovedDelta.sha(bytes))
            bytes
        }
        assertEquals("only the four S8-6 successors differ from the clean prefix", s86Changed.keys.map { "actual-hash $it" }.sorted(),
            PostS6ApprovedDelta.actualErrors(historical, before + successors).sorted())
        for (path in s86Changed.keys) {
            assertEquals("$path at other bytes is refused", listOf("actual-hash $path"),
                PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))))
            assertEquals("$path missing is refused", listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
        }
    }

    // ---- S8-7: the tether free preview (R4c/S8/s8_7_contract.codex.r2.md §6), general paths only ----

    private val s87 get() = unit(24)
    /** The lint-heap commit this unit branches from; its product bytes are 7664be9d's. */
    private val s87Parent = "ab128e88c236318349b6013c6c012f6cf994a1ff"
    /** R4c/S8/s8_7_contract.agreed.record.r1.md: the agreed S8-7 contract record. */
    private val s87Approval = "2e1e22643378664d28a433e0f226c0bf6a69ee11770994e6a3cd2df7f2efa82c"
    /** Each changed path, the unit that last wrote it before S8-7, and its reviewed base bytes. */
    private val s87Changed = mapOf(
        base + "ui/free/FreeSnapshotScreen.kt" to ("S7-E5d" to "dc444dc627f2e53c27737e6ca85714ae29ffe7a6ab980a2068aa838e9c9f856c"),
        base + "ui/free/FreeSnapshotUiState.kt" to ("S7-E5d" to "2abfc0fb3cbd73a73d053d9bcf127e51e61e0919fb660a8abf2e60527295d28a"),
        base + "ui/alert/SourceAlertEditor.kt" to ("S8-5b" to "2987b0935ff8abcb34404634e67b168185eb66d2816287c8d40bcf8d18c08af5")
    )
    private val s87Added get() = s87.getValue("added").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val s87AddedPaths = setOf(base + "ui/alert/SourceAlertDraftRules.kt", base + "ui/free/FreeSourceAlertPreviewQuotes.kt",
        base + "ui/free/FreeSourceAlertPreviewSession.kt", base + "ui/free/FreeSourceAlertPreviewSection.kt", base + "ui/free/FreeSourceAlertPreviewSheet.kt")
    private fun s87Entry(path: String) = s87.getValue("changed").jsonObject.getValue(path).jsonObject
    private fun s87With(path: String, entry: JsonObject) = s87.with("changed", JsonObject(s87.getValue("changed").jsonObject + (path to entry)))
    private fun withUnitAt(index: Int, replacement: JsonObject) = json.with("units",
        JsonArray(json.getValue("units").jsonArray.mapIndexed { i, original -> if (i == index) replacement else original }))

    private fun s87Check(ok: Boolean) {
        if (!ok) throw AssertionError("S8-7 단위의 정확한 이력")
    }

    @Test fun E82_theS87UnitIsExactAndContinuesThreeChains() {
        s87Check(s87.str("id") == "S8-7")
        val earlier = (0..23).map { unit(it) }
        val actual = PostS6ApprovedDelta.actualSources().mapValues { PostS6ApprovedDelta.sha(it.value) }
        var ok = listOf(s87Parent, s87Approval) == listOf(s87.str("source_parent"), s87.str("approval_record_sha256")) &&
            s87.getValue("changed").jsonObject.keys == s87Changed.keys && s87Added.keys == s87AddedPaths
        for ((path, writerAndFrom) in s87Changed) {
            val (writer, from) = writerAndFrom
            val previous = earlier.lastOrNull { path in it.getValue("changed").jsonObject.keys || path in it.getValue("added").jsonObject.keys }
            val previousTo = previous?.let { u ->
                u.getValue("changed").jsonObject[path]?.jsonObject?.str("to") ?: u.getValue("added").jsonObject[path]?.jsonPrimitive?.content
            }
            ok = ok && previous?.str("id") == writer && previousTo == from && s87Entry(path).str("from") == from &&
                from in PostS6ApprovedDelta.NO_KRX_REVIEWED && s87Entry(path).str("to") == actual[path]
        }
        for ((path, hash) in s87Added) {
            ok = ok && hash == actual[path] && earlier.none { u -> path in u.getValue("changed").jsonObject.keys || path in u.getValue("added").jsonObject.keys } &&
                path !in productBase
        }
        for (path in s87Changed.keys + s87Added.keys) {
            ok = ok && path !in PostS6ApprovedDelta.FORBIDDEN_PATHS && PostS6ApprovedDelta.FORBIDDEN_PREFIXES.none { path.startsWith(it) } &&
                PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S8-7" || it.path == path }
        }
        s87Check(ok && errors(json).isEmpty())
    }

    @Test fun E83_theS87UnitRefusesBrokenLinksAndBytes() {
        val (control, controlLedger) = PostS6ApprovedDelta.parse(withUnitAt(24, s87).toString().toByteArray(), inventory, s64b, null)
        assertEquals("premise: the unaltered S8-7 unit validates", emptyList<String>(), control)
        assertTrue("premise: and folds a ledger", controlLedger != null)
        val results = mutableListOf<Boolean>()
        for (path in s87Changed.keys) {
            val unknown = PostS6ApprovedDelta.sha("S8-7 unreviewed $path".toByteArray())
            val unreviewed = s87With(path, changedEntry(unknown, s87Entry(path).str("to")))
            results += errors(withUnitAt(24, unreviewed)) == listOf("from $path", "krx-before-unreviewed $path")
            val stale = s87With(path, changedEntry(PostS6ApprovedDelta.NO_KRX_REVIEWED.first { it != s87Entry(path).str("from") }, s87Entry(path).str("to")))
            results += errors(withUnitAt(24, stale)) == listOf("from $path")
        }
        val forbiddenAdd = base + "data/entitlements/S87Probe.kt"
        results += errors(withUnitAt(24, s87.with("added", JsonObject(s87.getValue("added").jsonObject + (forbiddenAdd to JsonPrimitive(someHash)))))) ==
            listOf("forbidden $forbiddenAdd")
        val ledger = PostS6ApprovedDelta.ledger()
        val actual = PostS6ApprovedDelta.actualSources()
        for (path in s87Changed.keys + s87Added.keys) {
            results += PostS6ApprovedDelta.actualErrors(ledger, actual + (path to actual.getValue(path) + byteArrayOf(10))) == listOf("actual-hash $path")
            results += PostS6ApprovedDelta.actualErrors(ledger, actual - path) == listOf("missing $path")
        }
        for (path in s87Changed.keys) {
            val without = s87.with("changed", JsonObject(s87.getValue("changed").jsonObject - path))
            val (e, l) = PostS6ApprovedDelta.parse(withUnitAt(24, without).toString().toByteArray(), inventory, s64b, null)
            results += e.isEmpty() && l != null && PostS6ApprovedDelta.actualErrors(l, actual) == listOf("actual-hash $path")
        }
        for (path in s87Added.keys) {
            val without = s87.with("added", JsonObject(s87.getValue("added").jsonObject - path))
            val (e, l) = PostS6ApprovedDelta.parse(withUnitAt(24, without).toString().toByteArray(), inventory, s64b, null)
            results += e.isEmpty() && l != null && path !in l.final &&
                path in PostS6ApprovedDelta.project(actual.mapValues { PostS6ApprovedDelta.sha(it.value) }, l) &&
                without.getValue("added").jsonObject.keys != s87AddedPaths
        }
        for (path in s87Changed.keys + s87AddedPaths) {
            val injected = actual.getValue(path) + "\nprivate const val s87Probe = \"KRX\"\n".toByteArray()
            val hash = PostS6ApprovedDelta.sha(injected)
            val unit = if (path in s87Changed) s87With(path, changedEntry(s87Entry(path).str("from"), hash))
                else s87.with("added", JsonObject(s87.getValue("added").jsonObject + (path to JsonPrimitive(hash))))
            val (errors, changedLedger) = PostS6ApprovedDelta.parse(withUnitAt(24, unit).toString().toByteArray(), inventory, s64b, null)
            results += errors.isEmpty() && changedLedger != null &&
                PostS6ApprovedDelta.actualErrors(changedLedger, actual + (path to injected)) == listOf("krx-reference $path")
        }
        if (results.any { !it }) throw AssertionError("S8-7 변조 검출")
    }

    // ---- S9-1: the comparison alert row policy and its two S8-2 predecessors ----

    private val s91 get() = unit(25)
    private val s91Parent = "478c0e4b111d1f8adfca4dbe1f2e7293d129fcda"
    private val s91Approval = "c2c4030998b1c8ee0ddbedff00763df77e7a0528b873e17afdcb4b3e7f3533e6"
    private val s91Policy = base + "data/alerts/ComparisonAlertRowPolicy.kt"
    private val s91PolicySha = "36f0f92224b68abc6c60099c207476fe81239514efb844a6e1a378df602f8c17"
    private val s91Changed = mapOf(
        api to changedEntry("a633deadf8c122233a639cca17c453ffdacf1f1b4a9af368fce01bfaec865072",
            "dd9f854309a40311c689f7fb594f0c339f0eba8cf9eac64555524b6a3c434205"),
        repo to changedEntry("73c7ec4b1158b5934f42ef20b03cf61164f6f5ee538f6aed62ebe91d8edf801b",
            "dc30ca264c7b3dce7aec3fd92d0a66b998e90902ae41b4d66ec239664f8c501d")
    )

    @Test fun E84_theS91UnitIsExactAndContinuesBothS82Chains() {
        assertEquals("the twenty-sixth unit", "S9-1", json.getValue("units").jsonArray.getOrNull(25)?.jsonObject?.str("id"))
        assertEquals(s91Parent, s91.str("source_parent"))
        assertEquals(s91Approval, s91.str("approval_record_sha256"))
        assertEquals(JsonObject(s91Changed), s91.getValue("changed"))
        assertEquals(JsonObject(mapOf(s91Policy to JsonPrimitive(s91PolicySha))), s91.getValue("added"))

        val earlier = (0 until 25).map { unit(it) }
        for ((path, link) in s91Changed) {
            val previous = earlier.last { path in it.getValue("changed").jsonObject || path in it.getValue("added").jsonObject }
            assertEquals("$path last changed in S8-2", "S8-2", previous.str("id"))
            assertEquals("$path continues from S8-2", previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to"), link.str("from"))
            assertTrue("$path has a reviewed predecessor", link.str("from") in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        }
        assertTrue("S9-1 has no core exception", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S9-1" })
    }

    @Test fun E85_theS91UnitRefusesAlteredApprovalLinksPathsAndPin() {
        assertEquals("the mutation control contains S9-1", "S9-1", json.getValue("units").jsonArray.getOrNull(25)?.jsonObject?.str("id"))
        // errors(JsonObject) serializes compact JSON. Pin the identical serialization of the clean control.
        val controlPin = PostS6ApprovedDelta.sha(json.toString().toByteArray())
        assertEquals(emptyList<String>(), errors(withUnitAt(25, s91), controlPin))
        for ((field, value) in listOf("source_parent" to "1".repeat(40), "approval_record_sha256" to someHash)) {
            assertEquals("another $field", listOf("approval-pin"),
                errors(withUnitAt(25, s91.with(field, JsonPrimitive(value))), controlPin))
        }

        val changed = s91.getValue("changed").jsonObject
        for ((path, link) in changed) {
            val from = link.jsonObject.str("from")
            val flipped = (from.take(2).toInt(16) xor 1).toString(16).padStart(2, '0') + from.drop(2)
            val broken = changed.with(path, link.jsonObject.with("from", JsonPrimitive(flipped)))
            assertEquals("one byte changed in the predecessor of $path", listOf("from $path", "krx-before-unreviewed $path"),
                errors(withUnitAt(25, s91.with("changed", broken))))
        }

        val added = s91.getValue("added").jsonObject
        val omitted = s91.with("added", JsonObject(added - s91Policy))
        assertEquals("omitting the policy breaks the approval pin", listOf("approval-pin"), errors(withUnitAt(25, omitted), controlPin))
        val forbidden = base + "data/entitlements/S91Probe.kt"
        val extra = s91.with("added", added.with(forbidden, JsonPrimitive(someHash)))
        assertEquals("an extra forbidden addition", listOf("forbidden $forbidden"), errors(withUnitAt(25, extra)))
        assertEquals("approved bytes with a wrong pin", listOf("approval-pin"), errorsRaw(raw.toString(Charsets.UTF_8), someHash))
    }

    // ---- S9-2a: the comparison alert settings owner ----

    private val s92a get() = unit(26)
    private val s92aOwner = base + "data/alerts/ComparisonAlertSettingsOwner.kt"
    private val s92aOwnerSha = "5a031537637ff67b4bf3d0a38b0fc453ac1fb0479156278ce0148e5f3f72ce46"

    @Test fun E86_theS92aUnitIsExactAndAddsOnlyTheComparisonOwner() {
        assertEquals("the twenty-seventh unit", "S9-2a", json.getValue("units").jsonArray.getOrNull(26)?.jsonObject?.str("id"))
        assertEquals("666cbd456dc0ab922e2d2c97cb6c747333ebd80e", s92a.str("source_parent"))
        // Both units are bound to the agreed S9-0 scope record.
        assertEquals(s91Approval, s92a.str("approval_record_sha256"))
        assertEquals(JsonObject(emptyMap()), s92a.getValue("changed"))
        assertEquals(JsonObject(mapOf(s92aOwner to JsonPrimitive(s92aOwnerSha))), s92a.getValue("added"))
        assertFalse("the owner has no product-base predecessor", s92aOwner in productBase)
        assertTrue("the owner first appears in S9-2a", (0 until 26).map { unit(it) }.none {
            s92aOwner in it.getValue("changed").jsonObject || s92aOwner in it.getValue("added").jsonObject
        })
        assertTrue("S9-2a has no core exception", PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S9-2a" })
    }

    @Test fun E87_theS92aUnitRefusesAlteredApprovalPathsOwnerBytesAndPin() {
        assertEquals("the mutation control contains S9-2a", "S9-2a", json.getValue("units").jsonArray.getOrNull(26)?.jsonObject?.str("id"))
        val controlPin = PostS6ApprovedDelta.sha(json.toString().toByteArray())
        assertEquals(emptyList<String>(), errors(withUnitAt(26, s92a), controlPin))
        val goRecordSha = "cfef6db4ded5eadcbdc87f21f619e69b73a37a4f5162268e2653dff258d23617"
        for ((field, value) in listOf("source_parent" to s91Parent, "approval_record_sha256" to goRecordSha)) {
            assertEquals("another $field", listOf("approval-pin"),
                errors(withUnitAt(26, s92a.with(field, JsonPrimitive(value))), controlPin))
        }

        val added = s92a.getValue("added").jsonObject
        val omitted = s92a.with("added", JsonObject(added - s92aOwner))
        val prefix = (0 until 26).map { unit(it) }
        assertEquals(listOf("unit-empty S9-2a"), errors(withUnits(*(prefix + omitted).toTypedArray())))
        assertEquals(listOf("unit-empty S9-2a", "changed-missing $s92aOwner"), errors(withUnitAt(26, omitted)))
        assertEquals(listOf("changed-missing $s92aOwner"), errors(withUnits(*((0 until 30).filter { it != 26 }
            .map { unit(it) }).toTypedArray())))
        val forbidden = base + "data/entitlements/S92aProbe.kt"
        val extra = s92a.with("added", added.with(forbidden, JsonPrimitive(someHash)))
        assertEquals("an extra forbidden addition", listOf("forbidden $forbidden"), errors(withUnitAt(26, extra)))

        val actual = PostS6ApprovedDelta.actualSources()
        val altered = actual.getValue(s92aOwner).copyOf()
        altered[0] = (altered[0].toInt() xor 1).toByte()
        assertEquals("one changed byte in the owner", listOf("actual-hash $s92aOwner"),
            PostS6ApprovedDelta.actualErrors(PostS6ApprovedDelta.ledger(), actual + (s92aOwner to altered)))
        assertEquals("approved bytes with a wrong pin", listOf("approval-pin"), errorsRaw(raw.toString(Charsets.UTF_8), someHash))
    }

    private val s92bSection = base + "ui/alert/ComparisonAlertSection.kt"
    private val s92bSectionSha = "2779633563f2c1ff023f3b54dbc20d60196619ac5d41b691a3c08f1cac51098d"
    private val s92bChanged = mapOf(
        registry to changedEntry("3ad2ca3c709ccbbd05297a32c072bcd1e2fde4130fd5d978a69847c608e20697",
            "725efe217ce9592a34dff062aee6ee6861d06cb5cff4451b03727798ba73d41e"),
        route to changedEntry("d3974388047316c6fa64b5d118f819a91a7793b010a6380d87e888c517dea752",
            "8875b3961e2baa49c0400123aeea0ea476acdc7d34f72d1071a72d15586382d2")
    )
    private val expectedS92b = JsonObject(mapOf(
        "id" to JsonPrimitive("S9-2b"),
        "source_parent" to JsonPrimitive("70c903a0d47af92b980a038c150794bc6f4057b8"),
        "approval_record_sha256" to JsonPrimitive(s91Approval),
        "changed" to JsonObject(s92bChanged),
        "added" to JsonObject(mapOf(s92bSection to JsonPrimitive(s92bSectionSha)))
    ))

    // Undo only the S9-3 hunks for tests of historical bytes in a shallow checkout.
    private fun s93Sources(latest: Map<String, ByteArray>): Map<String, ByteArray> {
        val actual = s94aSources(latest)
        val before = actual.toMutableMap()
        before[s92aOwner] = actual.getValue(s92aOwner).restoreLines {
            replaceLines(567, 579)
            replaceLines(550, 551,
                "        if (state.value.adopted == null) mutableState.value = state.value.copy(phase = phase)")
            replaceLines(494, 495,
                "                    mutableState.value = ComparisonAlertSettingsState(AlertLoadPhase.Ready, use.identity, adopted)")
            replaceLines(488, 489,
                "                if (!current(chain) || !guard()) return")
            replaceLines(485, 486)
            replaceLines(424, 430)
            replaceLines(399, 414)
            replaceLines(397, 398,
                "    private fun abandon() {")
            replaceLines(386, 389,
                "        abandon()",
                "        mutableState.value = ComparisonAlertSettingsState()")
            replaceLines(363, 364)
            replaceLines(153, 332)
            replaceLines(140, 147)
            replaceLines(134, 136,
                "        CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, { Random.nextDouble(-0.2, 0.2) }")
            replaceLines(113, 117,
                "    private val jitter: () -> Double")
            replaceLines(49, 104)
            replaceLines(13, 17)
        }
        assertEquals(s92aOwnerSha, PostS6ApprovedDelta.sha(before.getValue(s92aOwner)))
        return before
    }

    private fun s92bSources(latest: Map<String, ByteArray>): Map<String, ByteArray> {
        val actual = s93Sources(latest)
        val before = (actual - s93Added.keys).toMutableMap()
        before[s92bSection] = actual.getValue(s92bSection).restoreLines {
            replaceLines(282, 286)
            replaceLines(264, 265,
                "private fun ComparisonCard(kind: String, tab: String, body: ComparisonSectionBody, refresh: () -> Unit) {")
            replaceLines(250, 259)
            replaceLines(248, 249,
                "        ComparisonCard(kind, serverTab, body) {")
            replaceLines(240, 241)
            replaceLines(217, 231,
                "        val mount = remember { ComparisonSectionMount({ current() }, { epoch++ }) }")
            replaceLines(207, 209,
                "    shown: () -> Boolean, listen: (() -> Unit) -> (() -> Unit)")
            replaceLines(197, 198,
                "    ComparisonSectionMountContent(tab, kind, owner, sourceFactory, current) { end ->")
            replaceLines(193, 194,
                "    sourceFactory: ComparisonAlertSectionSourceFactory, isShown: () -> Boolean")
            replaceLines(35, 36)
            replaceLines(16, 17)
        }
        before[route] = actual.getValue(route).restoreLines {
            replaceLines(281, 282,
                "                                            tab, \"absolute\", owner, consumer, comparisonAlertSectionSourceFactory,")
            replaceLines(277, 278,
                "                                            tab, \"signed\", owner, consumer, comparisonAlertSectionSourceFactory,")
            replaceLines(241, 242,
                "                                    tab, \"absolute\", owner, consumer, comparisonAlertSectionSourceFactory,")
            replaceLines(85, 87,
                "    comparisonAlertSectionSourceFactory: ComparisonAlertSectionSourceFactory = ProductionComparisonAlertSectionSourceFactory")
            replaceLines(25, 27)
        }
        return before
    }

    private fun throughS92b(replacement: JsonObject) = withUnits(*((0 until 27).map { unit(it) } + replacement).toTypedArray())

    @Test fun E88_theS92bUnitIsExactAndContinuesTheReviewedRegistryAndRoute() {
        val earlier = (0 until 27).map { unit(it) }
        assertEquals("S9-2a", earlier.last().str("id"))
        assertEquals("the twenty-eighth unit and all of its fields", expectedS92b, unit(27))
        for ((path, writer) in mapOf(registry to "S8-6", route to "S8-5b")) {
            val previous = earlier.last { path in it.getValue("changed").jsonObject || path in it.getValue("added").jsonObject }
            assertEquals("$path last writer", writer, previous.str("id"))
            val from = s92bChanged.getValue(path).str("from")
            assertEquals("$path last approved bytes", from, previous.getValue("changed").jsonObject.getValue(path).jsonObject.str("to"))
            assertTrue("$path predecessor reviewed", from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        }
        assertFalse(s92bSection in productBase)
        assertTrue(earlier.none { s92bSection in it.getValue("changed").jsonObject || s92bSection in it.getValue("added").jsonObject })
        assertTrue(PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S9-2b" })
        val actual = s92bSources(PostS6ApprovedDelta.actualSources())
        for ((path, hash) in s92bChanged.mapValues { it.value.str("to") } + (s92bSection to s92bSectionSha)) {
            assertFalse(path in PostS6ApprovedDelta.FORBIDDEN_PATHS || PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
            assertEquals("$path S9-2b prefix bytes", hash, PostS6ApprovedDelta.sha(actual.getValue(path)))
        }
    }

    @Test fun E89_theS92bUnitRefusesAlteredLinksPathsBytesApprovalAndPin() {
        val approved = unit(27)
        assertEquals("clean mutation control", expectedS92b, approved)
        val changed = approved.getValue("changed").jsonObject
        val added = approved.getValue("added").jsonObject
        fun parse(replacement: JsonObject) = PostS6ApprovedDelta.parse(
            throughS92b(replacement).toString().toByteArray(), inventory, s64b, null)
        fun validLedger(replacement: JsonObject): PostS6ApprovedDelta.Ledger {
            val (found, folded) = parse(replacement)
            assertEquals(emptyList<String>(), found)
            return checkNotNull(folded)
        }
        fun rejected(replacement: JsonObject, vararg expected: String) {
            val (found, folded) = parse(replacement)
            assertEquals(expected.toList(), found)
            assertEquals(null, folded)
        }

        val ledger = validLedger(approved)
        val actual = s92bSources(PostS6ApprovedDelta.actualSources())
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, actual))
        for ((path, prior) in mapOf(registry to s86, route to s85b)) {
            val stale = prior.getValue("changed").jsonObject.getValue(path).jsonObject.str("from")
            val link = changed.getValue(path).jsonObject
            fun withFrom(value: String) = approved.with("changed", changed.with(path, link.with("from", JsonPrimitive(value))))
            rejected(withFrom(stale), "from $path")
            rejected(withFrom(someHash), "from $path", "krx-before-unreviewed $path")
        }

        val registryTo = changed.getValue(registry).jsonObject.getValue("to")
        val overlap = approved.with("added", added.with(registry, registryTo))
        rejected(overlap, "overlap [$registry]", "added-existing $registry")
        rejected(overlap.with("changed", JsonObject(changed - registry)), "added-existing $registry")
        val asChange = changed.with(s92bSection, changedEntry(s92bChanged.getValue(route).str("from"), s92bSectionSha))
        rejected(approved.with("changed", asChange).with("added", JsonObject(emptyMap())), "changed-missing $s92bSection")
        val compact = throughS92b(approved).toString()
        val sectionKey = JsonPrimitive(s92bSection).toString()
        val duplicate = compact.replace(sectionKey, "$sectionKey:\"$s92bSectionSha\",$sectionKey")
        assertEquals(listOf("duplicate-key $s92bSection"), errorsRaw(duplicate))

        // These variants satisfy the generic schema. The independent exact unit in E88 binds their meaning.
        val extra = base + "ui/alert/S92bProbe.kt"
        for (other in listOf(
            approved.with("source_parent", JsonPrimitive(s91Parent)),
            approved.with("approval_record_sha256", JsonPrimitive(someHash)),
            approved.with("added", JsonObject(emptyMap())),
            approved.with("added", added.with(extra, JsonPrimitive(someHash)))
        )) {
            val folded = validLedger(other)
            assertFalse("E88 refuses this well-formed replacement", expectedS92b == other)
            val expectedErrors = if (extra in folded.final) listOf("missing $extra") else emptyList()
            assertEquals(expectedErrors, PostS6ApprovedDelta.actualErrors(folded, actual))
        }
        rejected(approved.with("added", added.with(activity, registryTo)), "forbidden $activity", "added-existing $activity")
        val forbidden = base + "data/entitlements/S92bProbe.kt"
        rejected(approved.with("added", added.with(forbidden, registryTo)), "forbidden $forbidden")

        for (path in s92bChanged.keys + s92bSection) {
            assertEquals(listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
            val bytes = actual.getValue(path)
            val altered = bytes.copyOf()
            altered[0] = (altered[0].toInt() xor 1).toByte()
            assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(ledger, actual + (path to altered)))
            val injected = bytes + "\nprivate const val probe = \"KRX\"\n".toByteArray()
            val hash = JsonPrimitive(PostS6ApprovedDelta.sha(injected))
            val matching = if (path == s92bSection) approved.with("added", added.with(path, hash))
                else approved.with("changed", changed.with(path, changed.getValue(path).jsonObject.with("to", hash)))
            val folded = validLedger(matching)
            assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(folded, actual))
            assertEquals(listOf("krx-reference $path"), PostS6ApprovedDelta.actualErrors(folded, actual + (path to injected)))
        }
        assertEquals(listOf("approval-pin"), errorsRaw(raw.toString(Charsets.UTF_8) + " ", PostS6ApprovedDelta.APPROVED_SHA256))
    }

    private val s93Session = base + "ui/alert/ComparisonAlertHistorySession.kt"
    private val s93Sheet = base + "ui/alert/ComparisonAlertHistorySheet.kt"
    private val s93Changed = mapOf(
        s92bSection to changedEntry(s92bSectionSha, "1fe4649659dfe6638901e32bbe4f6f6313ea97650214d884a5314fe34e506a99"),
        route to changedEntry(s92bChanged.getValue(route).str("to"), "167b01c0e2280475d7053b629b80f9080af85b575edf8eaa7510c4496e734d07")
    )
    private val s93Added = mapOf(
        s93Session to "847bd61ea117ebf825a4ab212140cf0548494a13c92e3566143e42f6e3cf911b",
        s93Sheet to "e62bffeafe43d58898109c0fbcbf47e618da83d3518e629ee59be863add1b9b9"
    )
    private val expectedS93 = JsonObject(mapOf(
        "id" to JsonPrimitive("S9-3"),
        "source_parent" to JsonPrimitive("f94795eff152bf39bc9bd94c287c78fabf468024"),
        "approval_record_sha256" to JsonPrimitive(s91Approval),
        "changed" to JsonObject(s93Changed),
        "added" to JsonObject(s93Added.mapValues { JsonPrimitive(it.value) })
    ))

    @Test fun E90_theS93UnitIsExactAndContinuesTheS92bSectionAndRoute() {
        assertEquals("the twenty-ninth unit and all its fields", expectedS93, unit(28))
        val earlier = (0 until 28).map { unit(it) }
        for ((path, link) in s93Changed) {
            val writer = earlier.last { path in it.getValue("added").jsonObject || path in it.getValue("changed").jsonObject }
            assertEquals("$path previous writer", "S9-2b", writer.str("id"))
            val predecessor = if (path == s92bSection) writer.getValue("added").jsonObject.str(path)
                else writer.getValue("changed").jsonObject.getValue(path).jsonObject.str("to")
            assertEquals("$path last approved bytes", link.str("from"), predecessor)
            assertTrue("$path reviewed predecessor", predecessor in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        }
        for (path in s93Added.keys) {
            assertFalse(path in productBase)
            assertTrue("$path is new in S9-3", earlier.none { path in it.getValue("added").jsonObject || path in it.getValue("changed").jsonObject })
        }
        val actual = s93Sources(PostS6ApprovedDelta.actualSources())
        for ((path, hash) in s93Changed.mapValues { it.value.str("to") } + s93Added) {
            assertFalse(path in PostS6ApprovedDelta.FORBIDDEN_PATHS || PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) })
            assertEquals(path == s92bSection, hash in PostS6ApprovedDelta.NO_KRX_REVIEWED)
            if (path == s92bSection) assertEquals(hash, unit(30).getValue("changed").jsonObject.getValue(path).jsonObject.str("from"))
            assertEquals("$path S9-3 final bytes", hash, PostS6ApprovedDelta.sha(actual.getValue(path)))
            assertFalse(PostS6ApprovedDelta.CORE_EXCEPTIONS.any { it.path == path || it.unitId == "S9-3" })
        }
    }

    @Test fun E91_theS93UnitRefusesAlteredHistoryPathsBytesApprovalAndPin() {
        val approved = unit(28)
        assertEquals("clean S9-3 mutation control", expectedS93, approved)
        val changed = approved.getValue("changed").jsonObject
        val added = approved.getValue("added").jsonObject
        val prefix = withUnits(*(0..28).map { unit(it) }.toTypedArray())
        fun at(index: Int, replacement: JsonObject) =
            withUnits(*(0..28).map { if (it == index) replacement else unit(it) }.toTypedArray())
        val noSection = approved.with("changed", JsonObject(changed - s92bSection))
        assertEquals(listOf("from $s92bSection"), errors(withUnitAt(28, noSection)))
        fun parse(input: JsonObject) = PostS6ApprovedDelta.parse(input.toString().toByteArray(), inventory, s64b, null)
        fun rejected(input: JsonObject, vararg expected: String) {
            val (found, folded) = parse(input)
            assertEquals(expected.sorted(), found.sorted())
            assertEquals(null, folded)
        }
        fun validLedger(input: JsonObject): PostS6ApprovedDelta.Ledger {
            val (found, folded) = parse(input)
            assertEquals(emptyList<String>(), found)
            return checkNotNull(folded)
        }
        val actual = s93Sources(PostS6ApprovedDelta.actualSources())
        val ledger = validLedger(prefix)
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(ledger, actual))

        val withoutB = prefix.with("units", JsonArray(prefix.getValue("units").jsonArray.filterIndexed { i, _ -> i != 27 }))
        rejected(withoutB, "from $route", "changed-missing $s92bSection")
        rejected(at(27, unit(27).with("added", JsonObject(emptyMap()))), "changed-missing $s92bSection")
        for ((path, link) in s93Changed) {
            fun withFrom(from: String) = at(28, approved.with("changed", changed.with(path, link.with("from", JsonPrimitive(from)))))
            rejected(withFrom(s92bChanged.getValue(route).str("from")), "from $path")
            rejected(withFrom(someHash), "from $path", "krx-before-unreviewed $path")
            val asAdded = approved.with("added", added.with(path, link.getValue("to")))
            rejected(at(28, asAdded), "overlap [$path]", "added-existing $path")
            rejected(at(28, asAdded.with("changed", JsonObject(changed - path))), "added-existing $path")
        }
        for ((path, hash) in s93Added) {
            val asChange = changed.with(path, changedEntry(s92bSectionSha, hash))
            rejected(at(28, approved.with("changed", asChange).with("added", JsonObject(added - path))), "changed-missing $path")
        }
        val key = JsonPrimitive(s93Session).toString()
        val duplicate = prefix.toString().replace(key, "$key:\"$someHash\",$key")
        assertEquals(listOf("duplicate-key $s93Session"), errorsRaw(duplicate))
        val forbidden = base + "data/entitlements/S93Probe.kt"
        fun withExtra(path: String) = approved.with("added", added.with(path, JsonPrimitive(someHash)))
        rejected(at(28, withExtra(activity)), "forbidden $activity", "added-existing $activity")
        rejected(at(28, withExtra(forbidden)), "forbidden $forbidden")

        val compactPin = PostS6ApprovedDelta.sha(prefix.toString().toByteArray())
        assertEquals(emptyList<String>(), errors(prefix, compactPin))
        for ((field, value) in mapOf("source_parent" to s91Parent, "approval_record_sha256" to someHash)) {
            val other = approved.with(field, JsonPrimitive(value))
            assertFalse("$field differs from the approved record", other == expectedS93)
            val input = at(28, other)
            validLedger(input)
            assertEquals(listOf("approval-pin"), errors(input, compactPin))
        }
        val extra = base + "ui/alert/S93Probe.kt"
        val extraUnit = withExtra(extra)
        assertFalse(extraUnit.getValue("added").jsonObject.keys == s93Added.keys)
        assertEquals(listOf("missing $extra"), PostS6ApprovedDelta.actualErrors(validLedger(at(28, extraUnit)), actual))

        for (path in s93Changed.keys + s93Added.keys) {
            val isChange = path in s93Changed
            val field = if (isChange) "changed" else "added"
            val entries = approved.getValue(field).jsonObject
            val omitted = approved.with(field, JsonObject(entries - path))
            val folded = validLedger(at(28, omitted))
            assertFalse(omitted.getValue(field).jsonObject.keys == (if (isChange) s93Changed.keys else s93Added.keys))
            if (isChange) assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(folded, actual))
            else assertFalse(path in folded.final)

            assertEquals(listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
            val bytes = actual.getValue(path)
            val altered = bytes.copyOf()
            altered[0] = (altered[0].toInt() xor 1).toByte()
            assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(ledger, actual + (path to altered)))
            val injected = bytes + "\nprivate const val probe = \"KRX\"\n".toByteArray()
            val hash = JsonPrimitive(PostS6ApprovedDelta.sha(injected))
            val entry = if (isChange) entries.getValue(path).jsonObject.with("to", hash) else hash
            val matching = approved.with(field, entries.with(path, entry))
            assertEquals(listOf("krx-reference $path"), PostS6ApprovedDelta.actualErrors(
                validLedger(at(28, matching)), actual + (path to injected)))
        }
        assertEquals(listOf("approval-pin"), errorsRaw(raw.toString(Charsets.UTF_8) + " ", PostS6ApprovedDelta.APPROVED_SHA256))
    }

    private val s94aTo = "ad409a548f678d3eca93bf250f49a6b3ae8831d25e22fa224bf619cc5c560d31"
    private val s94aChanged = mapOf(s92aOwner to changedEntry(s92aOwnerSha, s94aTo))
    private val expectedS94a = JsonObject(mapOf(
        "id" to JsonPrimitive("S9-4a"),
        "source_parent" to JsonPrimitive("3added6b5b64c3cf650432dfa1660935d1d87966"),
        "approval_record_sha256" to JsonPrimitive(s91Approval),
        "changed" to JsonObject(s94aChanged),
        "added" to JsonObject(emptyMap())
    ))

    @Test fun E92_exactComparisonOwnerSuccessor() {
        assertEquals(30, json.getValue("units").jsonArray.take(30).size)
        assertEquals(expectedS94a, unit(29))
        val previous = (0 until 29).map { unit(it) }.last {
            s92aOwner in it.getValue("added").jsonObject || s92aOwner in it.getValue("changed").jsonObject
        }
        assertEquals("S9-2a", previous.str("id"))
        assertEquals(s92aOwnerSha, previous.getValue("added").jsonObject.str(s92aOwner))
        assertTrue(s92aOwnerSha in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        assertFalse(s92aOwner in PostS6ApprovedDelta.FORBIDDEN_PATHS ||
            PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { s92aOwner.startsWith(it) })
        assertTrue(PostS6ApprovedDelta.CORE_EXCEPTIONS.none { it.unitId == "S9-4a" || it.path == s92aOwner })
        val actual = PostS6ApprovedDelta.actualSources()
        assertEquals(s94aTo, PostS6ApprovedDelta.sha(actual.getValue(s92aOwner)))
        assertEquals(PostS6ApprovedDelta.APPROVED_SHA256, PostS6ApprovedDelta.sha(raw))
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(PostS6ApprovedDelta.ledger(), actual))
    }

    @Test fun E93_refusesInvalidComparisonOwnerSuccessors() {
        val approved = unit(29)
        assertEquals(expectedS94a, approved)
        fun rejected(input: String, vararg expected: String, pin: String? = null) {
            val (found, folded) = PostS6ApprovedDelta.parse(input.toByteArray(), inventory, s64b, pin)
            assertEquals(expected.toList(), found)
            assertNull(folded)
        }
        fun ledger(input: JsonObject): PostS6ApprovedDelta.Ledger {
            val (found, folded) = PostS6ApprovedDelta.parse(input.toString().toByteArray(), inventory, s64b, null)
            assertEquals(emptyList<String>(), found)
            return checkNotNull(folded)
        }
        fun change(from: String = s92aOwnerSha, to: String = s94aTo) =
            approved.with("changed", JsonObject(mapOf(s92aOwner to changedEntry(from, to))))
        fun full(unit: JsonObject) = withUnitAt(29, unit).toString()
        rejected(full(change(s92bSectionSha)), "from $s92aOwner")
        rejected(full(change(someHash)), "from $s92aOwner", "krx-before-unreviewed $s92aOwner")
        val overlap = approved.with("added", JsonObject(mapOf(s92aOwner to JsonPrimitive(s94aTo))))
        rejected(full(overlap), "overlap [$s92aOwner]", "added-existing $s92aOwner")
        rejected(full(overlap.with("changed", JsonObject(emptyMap()))), "added-existing $s92aOwner")
        rejected(full(approved.with("changed", JsonObject(emptyMap()))), "unit-empty S9-4a")

        val actual = PostS6ApprovedDelta.actualSources()
        val prefix = withUnits(*(0 until 29).map { unit(it) }.toTypedArray())
        fun actualErrors(ledger: PostS6ApprovedDelta.Ledger, bytes: Map<String, ByteArray>) =
            PostS6ApprovedDelta.actualErrors(ledger, bytes)
        assertEquals(listOf("actual-hash $s92aOwner"), actualErrors(ledger(prefix), s94aSources(actual)))
        val key = JsonPrimitive(s92aOwner).toString()
        val rawText = raw.toString(Charsets.UTF_8)
        val lastKey = rawText.lastIndexOf(key)
        rejected(rawText.replaceRange(lastKey, lastKey, "$key:{},"),
            "duplicate-key $s92aOwner")
        val forbidden = base + "data/entitlements/S94aProbe.kt"
        fun extra(path: String) = approved.with("added", JsonObject(mapOf(path to JsonPrimitive(s94aTo))))
        rejected(full(extra(forbidden)), "forbidden $forbidden")
        rejected(full(extra(activity)), "forbidden $activity", "added-existing $activity")
        val clean = ledger(json)
        assertEquals(listOf("missing $s92aOwner"), actualErrors(clean, actual - s92aOwner))
        val bytes = actual.getValue(s92aOwner)
        val altered = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(listOf("actual-hash $s92aOwner"), actualErrors(clean, actual + (s92aOwner to altered)))
        val injected = bytes + "\nprivate const val probe = \"KRX\"\n".toByteArray()
        val matching = withUnitAt(29, change(to = PostS6ApprovedDelta.sha(injected)))
        assertEquals(listOf("krx-reference $s92aOwner"), actualErrors(ledger(matching), actual + (s92aOwner to injected)))

        val compactPin = PostS6ApprovedDelta.sha(json.toString().toByteArray())
        assertEquals(emptyList<String>(), errors(json, compactPin))
        for (other in listOf(extra(base + "ui/alert/S94aProbe.kt"), approved.with("source_parent", JsonPrimitive(s91Parent)),
            approved.with("approval_record_sha256", JsonPrimitive(someHash)))) {
            ledger(withUnitAt(29, other))
            assertFalse(expectedS94a == other)
            rejected(full(other), "approval-pin", pin = compactPin)
        }
        rejected(rawText + " ", "approval-pin", pin = PostS6ApprovedDelta.APPROVED_SHA256)
    }
    private fun s94aSources(actual: Map<String, ByteArray>): Map<String, ByteArray> {
        val before = (actual - s94bAdded.keys).toMutableMap()
        before[s92bSection] = actual.getValue(s92bSection).restoreLines {
            replaceLines(392, 395)
            replaceLines(388, 390,
                "        Switch(checked = row.isEnabled, onCheckedChange = null, modifier = Modifier.semantics {",
                "            role = Role.Switch",
                "            toggleableState = ToggleableState(row.isEnabled)")
            replaceLines(378, 381,
                "            .clearAndSetSemantics { contentDescription = text.description }) {")
            replaceLines(373, 374,
                "private fun ComparisonRow(row: ComparisonAlertSetting) {")
            replaceLines(362, 367)
            replaceLines(360, 361,
                "                    ComparisonRow(row)")
            replaceLines(337, 338,
                "            IconButton({ expanded = !expanded }, Modifier.testTag(\"comparison-\$kind-expand\")) {")
            replaceLines(318, 319,
                "        Row(verticalAlignment = Alignment.CenterVertically) {")
            replaceLines(311, 314,
                "private fun ComparisonCard(kind: String, tab: String, body: ComparisonSectionBody, open: () -> Unit, refresh: () -> Unit) {",
                "    var expanded by remember { mutableStateOf(true) }")
            replaceLines(287, 297)
            replaceLines(284, 286,
                "        ComparisonCard(kind, serverTab, body, open) {")
            replaceLines(276, 277)
            replaceLines(258, 259,
                "                if (session == null) {")
            replaceLines(243, 256,
                "        val mount = remember { ComparisonSectionMount({ current() }, { session?.close(); epoch++ }) }")
            replaceLines(229, 231,
                "    shown: () -> Boolean, historyWiring: ComparisonAlertHistoryWiringFactory = ProductionComparisonAlertHistoryWiring,")
            replaceLines(208, 220,
                "    ComparisonSectionMountContent(tab, kind, owner, sourceFactory, current, historyWiring) { end ->")
            replaceLines(111, 115)
            replaceLines(96, 100)
            replaceLines(55, 55,
                "import androidx.compose.ui.semantics.toggleableState",
                "import androidx.compose.ui.state.ToggleableState")
            replaceLines(53, 53,
                "import androidx.compose.ui.semantics.role")
            replaceLines(51, 51,
                "import androidx.compose.ui.semantics.Role")
            replaceLines(3, 10)
        }
        assertEquals(s94bChanged.getValue(s92bSection).str("from"), PostS6ApprovedDelta.sha(before.getValue(s92bSection)))
        before[presenter] = actual.getValue(presenter).restoreLines {
            replaceLines(83, 85,
                "            alertQuotes = alertQuotes")
            replaceLines(53, 61)
            replaceLines(6, 7)
        }
        assertEquals(s94bChanged.getValue(presenter).str("from"), PostS6ApprovedDelta.sha(before.getValue(presenter)))
        before[uiState] = actual.getValue(uiState).restoreLines {
            replaceLines(32, 34,
                "    val alertQuotes: Map<String, Double> = emptyMap()")
            replaceLines(6, 7)
        }
        assertEquals(s94bChanged.getValue(uiState).str("from"), PostS6ApprovedDelta.sha(before.getValue(uiState)))
        return before
    }

    private val s94bChanged = mapOf(
        s92bSection to changedEntry("1fe4649659dfe6638901e32bbe4f6f6313ea97650214d884a5314fe34e506a99", "b56f116bc5f9577531ad33fde3ea913854e681af221000e793eebe77bf0e3323"),
        presenter to changedEntry("bbb56f0fc17058c8ae954ca229615ff1dfb882cb3a25319e760a98ab843e11e3", "68680ded6837eaf057573f4ac8ec4c1795eeeba2ad55bfca549ebbb0dd3e1781"),
        uiState to changedEntry("961722112329f27cf5d7a344226b96747df2f77c3d253fdc4e0e548e5e60993f", "64c424571a3669e6c8a5e7c9a6241831c04cbedc17b947cce9cb9fb18b66d44e")
    )
    private val s94bAdded = mapOf(
        base + "ui/alert/ComparisonAlertDraftRules.kt" to "9a9e8f0f8807c7a0c989f4630ca55d67515c30fd08b45dd3587fec5eda91e895",
        base + "ui/alert/ComparisonAlertEditor.kt" to "ee3c7aeff7214a8d85eb8dbdfd4a4241d1fcd5f4a87dcfd77837209afbfc07e7",
        base + "ui/alert/ComparisonAlertEditorSheet.kt" to "2920fb11ccbbd70cbec0c3d14d17aef07e70572d24fadfc6d155694508cb09b0"
    )

    private val expectedS94b get() = JsonObject(mapOf(
        "id" to JsonPrimitive("S9-4b"),
        "source_parent" to JsonPrimitive("4a292277dd7428f9077b4e18221995db9f44d8bf"),
        "approval_record_sha256" to JsonPrimitive(s91Approval),
        "changed" to JsonObject(s94bChanged), "added" to JsonObject(s94bAdded.mapValues { JsonPrimitive(it.value) })
    ))

    @Test fun E94_exactComparisonEditorSuccessor() {
        assertEquals(31, json.getValue("units").jsonArray.size)
        assertEquals(expectedS94b, unit(30))
        val actual = PostS6ApprovedDelta.actualSources()
        val history = (0..29).map { unit(it) }
        for ((path, writer) in mapOf(s92bSection to "S9-3", presenter to "S8-5b", uiState to "S7-E5b3b")) {
            val last = history.last { path in it.getValue("changed").jsonObject || path in it.getValue("added").jsonObject }
            assertEquals(writer, last.str("id"))
            val from = s94bChanged.getValue(path).str("from")
            assertEquals(from, last.getValue("changed").jsonObject.getValue(path).jsonObject.str("to"))
            assertTrue(from in PostS6ApprovedDelta.NO_KRX_REVIEWED)
        }
        for (path in s94bAdded.keys) {
            assertFalse(path in productBase)
            assertTrue(history.none { path in it.getValue("added").jsonObject || path in it.getValue("changed").jsonObject })
        }
        for ((path, digest) in s94bChanged.mapValues { it.value.str("to") } + s94bAdded) {
            assertEquals(digest, PostS6ApprovedDelta.sha(actual.getValue(path)))
            assertFalse(PostS6ApprovedDelta.FORBIDDEN_PREFIXES.any { path.startsWith(it) } || path in PostS6ApprovedDelta.FORBIDDEN_PATHS)
            assertFalse(PostS6ApprovedDelta.CORE_EXCEPTIONS.any { it.path == path || it.unitId == "S9-4b" })
        }
        assertEquals(PostS6ApprovedDelta.APPROVED_SHA256, PostS6ApprovedDelta.sha(raw))
        assertEquals(emptyList<String>(), PostS6ApprovedDelta.actualErrors(PostS6ApprovedDelta.ledger(), actual))
    }

    @Test fun E95_refusesInvalidComparisonEditorSuccessors() {
        val approved = unit(30)
        val changed = approved.getValue("changed").jsonObject
        val added = approved.getValue("added").jsonObject
        val actual = PostS6ApprovedDelta.actualSources()
        fun parsed(input: JsonObject) = PostS6ApprovedDelta.parse(input.toString().toByteArray(), inventory, s64b, null)
        fun rejected(unit: JsonObject, vararg messages: String) {
            val (errors, ledger) = parsed(withUnitAt(30, unit))
            assertEquals(messages.toList(), errors)
            assertNull(ledger)
        }
        fun folded(input: JsonObject): PostS6ApprovedDelta.Ledger {
            val (errors, ledger) = parsed(input)
            assertEquals(emptyList<String>(), errors)
            return checkNotNull(ledger)
        }
        fun changedSection(from: String) = approved.with("changed", changed.with(s92bSection,
            changedEntry(from, changed.getValue(s92bSection).jsonObject.str("to"))))
        rejected(changedSection(s92bSectionSha), "from $s92bSection")
        rejected(changedSection(someHash), "from $s92bSection", "krx-before-unreviewed $s92bSection")
        val overlap = approved.with("added", added.with(s92bSection, changed.getValue(s92bSection).jsonObject.getValue("to")))
        rejected(overlap, "overlap [$s92bSection]", "added-existing $s92bSection")
        rejected(overlap.with("changed", JsonObject(changed - s92bSection)), "added-existing $s92bSection")
        val ledger = folded(json)
        val project = actual.mapValues { PostS6ApprovedDelta.sha(it.value) }
        for (path in changed.keys + added.keys) {
            val isChange = path in changed
            val key = if (isChange) "changed" else "added"
            val entries = approved.getValue(key).jsonObject
            val omitted = approved.with(key, JsonObject(entries - path))
            val shorter = folded(withUnitAt(30, omitted))
            if (isChange) {
                assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(shorter, actual))
            } else {
                fun moved(from: String) = omitted.with("changed", changed.with(path, changedEntry(from, s94bAdded.getValue(path))))
                rejected(moved(s92bSectionSha), "changed-missing $path")
                rejected(moved(someHash), "changed-missing $path", "krx-before-unreviewed $path")
                assertFalse(omitted.getValue(key).jsonObject.keys == s94bAdded.keys)
                assertEquals(project[path], PostS6ApprovedDelta.project(project, shorter)[path])
                assertFalse(path in PostS6ApprovedDelta.project(project, ledger))
            }
            val bytes = actual.getValue(path)
            assertEquals(listOf("missing $path"), PostS6ApprovedDelta.actualErrors(ledger, actual - path))
            assertEquals(listOf("actual-hash $path"), PostS6ApprovedDelta.actualErrors(ledger, actual + (path to (bytes + byteArrayOf(32)))))
            val injected = bytes + "\nprivate const val probe = \"KRX\"\n".toByteArray()
            val sha = JsonPrimitive(PostS6ApprovedDelta.sha(injected))
            val entry = if (isChange) entries.getValue(path).jsonObject.with("to", sha) else sha
            val matching = folded(withUnitAt(30, approved.with(key, entries.with(path, entry))))
            assertEquals(listOf("krx-reference $path"), PostS6ApprovedDelta.actualErrors(matching, actual + (path to injected)))
        }
        val prefix = withUnits(*(0..29).map { unit(it) }.toTypedArray())
        assertEquals(s94bChanged.keys.map { "actual-hash $it" }.sorted(), PostS6ApprovedDelta.actualErrors(folded(prefix), actual).sorted())
        val forbidden = base + "data/entitlements/S94bProbe.kt"
        fun extra(path: String) = approved.with("added", added.with(path, JsonPrimitive(someHash)))
        rejected(extra(forbidden), "forbidden $forbidden")
        rejected(extra(activity), "forbidden $activity", "added-existing $activity")
        val rawText = raw.toString(Charsets.UTF_8)
        val key = JsonPrimitive(s94bAdded.keys.first()).toString()
        val index = rawText.lastIndexOf(key)
        val duplicate = rawText.replaceRange(index, index, "$key:\"$someHash\",")
        val duplicateResult = PostS6ApprovedDelta.parse(duplicate.toByteArray(), inventory, s64b, null)
        assertEquals(listOf("duplicate-key ${s94bAdded.keys.first()}"), duplicateResult.first)
        assertNull(duplicateResult.second)
        val pin = PostS6ApprovedDelta.sha(json.toString().toByteArray())
        for (other in listOf(extra(base + "ui/alert/S94bProbe.kt"), approved.with("source_parent", JsonPrimitive(s91Parent)),
            approved.with("approval_record_sha256", JsonPrimitive(someHash)))) {
            val input = withUnitAt(30, other)
            folded(input)
            assertFalse(expectedS94b == other)
            assertEquals(listOf("approval-pin"), errors(input, pin))
        }
        val spaced = rawText + " "
        assertEquals(listOf("approval-pin"), errorsRaw(spaced, PostS6ApprovedDelta.APPROVED_SHA256))
    }
}
