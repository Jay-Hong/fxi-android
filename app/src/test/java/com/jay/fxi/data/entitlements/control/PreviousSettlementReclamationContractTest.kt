package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * Claude-owned 6-3C1 contract: the pure previous-lifetime Settlement reclamation decider (6-3 skeleton r3 §4, T9.1–T9.2;
 * revision 06 §8.1–§8.2; 6-3C API consensus). API fixed by this contract:
 *   internal class PreviousEvidenceSelection(items: List<PreviousEvidenceSelection.Item>) {
 *     sealed interface Item { val commandId: String
 *       class Settlement(commandId: String, rawApplied: ControlNode, orderedRawSeals: List<ControlNode>) : Item
 *         // exposes val commandId, val rawApplied, val orderedRawSeals
 *       class Lifecycle(commandId: String, rawApplied: ControlNode) : Item }   // exposes val commandId, val rawApplied
 *     val items: List<Item> }            // defensive copies (Settlement.orderedRawSeals too)
 *   internal object PreviousSettlementEvidenceReclamation {
 *     sealed interface Decision { Ready(candidate: Preferences); Recovery(reason: RecoveryReason); object Conflict;
 *       Rejected(reason: RejectionReason) }
 *     fun decide(read: ControlRecordRead.Supported, selection: PreviousEvidenceSelection,
 *       currentLifetime: OwnerTrackingLifetimeId, retry: Boolean, codec: ControlPayloadCodec): Decision
 *     fun validateReturn(returned: ControlRecordRead, selection: PreviousEvidenceSelection, candidate: Preferences): Boolean
 *   }
 * Precondition: a wholly interpretable schema-2 read. The decider itself also refuses an invalid selection with
 * Rejected(InvalidRequest) — empty, any Lifecycle item, duplicate command ids, an unreadable or duplicate selected seal id —
 * and the 6-3C2 entry applies the same rule before storage. No prepared preimage exists for a previous lifetime, so nothing
 * is compared with one. Every item:
 * the latest row with its commandId exists (first call; absent → Conflict), is SETTLEMENT and of a lifetime other than the
 * current one (else Conflict), and equals the selected raw row; the latest seals at the row's ordered ids equal the selected
 * raw seals in order (else Conflict). Integrity (else RecoveryRequired(InconsistentReclamation)): every selected seal is
 * settled with operationId == row.commandId and the operation's whole settled-seal set equals the row's ids; per transition —
 * RETIRED_NAMESPACE: one NAMESPACE seal, witness JOURNAL_RETIRED with before == after, journal = the seal key (owner, axis,
 * epoch), demandId present exactly when the seal owner equals before.ownerUid; CURRENT_NULL: every seal shares one witness
 * origin/operation(BEGIN_ROTATION)/before/after, each axis's journal is (before.ownerUid, axis, null) shared by its NULL and
 * companion, demandId present; RETIRED_NULL: departed-owner NULL seals on distinct axes, V2 witness, demandId null. The whole
 * selection is validated before one candidate removes every selected row and full bundle (all or nothing); every other
 * row/seal (text and order) and key survives; re-encoding past the limit → Rejected(TooLarge). Retry with the same
 * selection: all present exactly → same deletion; ALL absent (rows and seals at the ids, and no settled seal of those
 * operations) → Ready(read.original); anything partial → RecoveryRequired. Records are the real transitions' candidates
 * applied under an old tracking lifetime. Variants were first classified with the reader alone: only those that stay wholly
 * interpretable and change the record are decider rows. The implementation thread reads but does not edit this file.
 */
class PreviousSettlementReclamationContractTest {
    private val codec = ControlPayloadCodec()
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val R = RetiredNamespaceFixtures; private val NN = CurrentNullFixtures; private val L = RetiredNullFixtures
    private val current = OwnerTrackingLifetimeId.issue()

    private class Kind(val name: String, val input: HandoverSettlementInput, val raw: Preferences, val body: ControlCommandBody.Handover,
        val decide: (CommandRef, ControlRecordRead.Supported) -> RecordTransactionDecision<ControlRecordStore.Outcome>)
    private fun rn(s: RetiredNamespaceSettlement = R.spec(), ctx: AttemptContext = R.context, name: String = "RETIRED_NAMESPACE") =
        Kind(name, s, R.raw(s), ControlCommandBody.SettleRetiredNamespace(s)) { c, read -> R.transition.decide(c, s, read, ctx, false, false) }
    private fun rnDeparted() = rn(R.departed(), AttemptContext("B", 9, R.life, false, false), "RETIRED_NAMESPACE-departed")
    private fun cn(s: CurrentNullSettlement = NN.spec(), name: String = "CURRENT_NULL") =
        Kind(name, s, NN.raw(s), ControlCommandBody.RotateAndSettleCurrentNull(s)) { c, read -> NN.transition.decide(c, s, read, NN.context, false, false) }
    private fun rl(s: RetiredNullSettlement = L.spec(), name: String = "RETIRED_NULL") =
        Kind(name, s, L.raw(s), ControlCommandBody.SettleRetiredNull(s)) { c, read -> L.transition.decide(c, s, read, L.context, false, false) }
    private fun kinds() = listOf(rn(), rnDeparted(), cn(), cn(NN.both(), "CURRENT_NULL-both"), rl(), rl(L.both(), "RETIRED_NULL-both"))

    private fun read(p: Preferences) = ControlRecordReader().read(p) as ControlRecordRead.Supported
    private fun interpretable(p: Preferences): ControlRecordRead.Supported {
        val r = ControlRecordReader(codec).read(p)
        check(r is ControlRecordRead.Supported && r.schemaVersion == 2 && !r.hasUninterpretable && !r.hasUninterpretableMetadata) {
            "fixture: decider precondition (wholly interpretable schema 2) violated" }
        return r
    }
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun cmd(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))
    private fun node(e: JsonElement) = ControlObligationFixtures.node(e.toString())

    /** One settlement applied by the real transition under an OLD tracking lifetime. */
    private inner class Applied(val k: Kind) {
        val old = OwnerTrackingLifetimeId.issue()
        val c = CommandRef(k.input.operationId, k.body, old)
        val record: Preferences = k.decide(c, read(k.raw)).let {
            check(it is RecordTransactionDecision.Confirm) { "fixture ${k.name}: transition must apply: $it" }
            it.candidate
        }
        val row: JsonElement = arr(record, evidenceKey).single { cmd(it) == c.id }
        val seals: List<JsonElement> = row.jsonObject.getValue("sealIds").jsonArray.map { sid ->
            arr(record, sealKey).single { id(it) == sid.jsonPrimitive.content } }
        fun item(rawRow: JsonElement = row, rawSeals: List<JsonElement> = seals) =
            PreviousEvidenceSelection.Item.Settlement(c.id, node(rawRow), rawSeals.map { node(it) })
    }
    private fun selection(vararg items: PreviousEvidenceSelection.Item) = PreviousEvidenceSelection(items.toList())
    private fun decide(record: Preferences, sel: PreviousEvidenceSelection, retry: Boolean = false) =
        PreviousSettlementEvidenceReclamation.decide(interpretable(record), sel, current, retry, codec)
    /** Independent oracle: [record] minus exactly each selected row and every seal settled by those operations. */
    private fun oracle(record: Preferences, vararg ops: String): Preferences = record.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(record, evidenceKey).filter { cmd(it) !in ops }).toString()
        this[sealKey] = JsonArray(arr(record, sealKey).filter { op(it) !in ops }).toString()
    }.toPreferences()
    private fun withSeals(p: Preferences, s: List<JsonElement>) = p.toMutablePreferences().apply { this[sealKey] = JsonArray(s).toString() }.toPreferences()
    private fun withRows(p: Preferences, r: List<JsonElement>) = p.toMutablePreferences().apply { this[evidenceKey] = JsonArray(r).toString() }.toPreferences()
    private fun changed(base: Preferences, record: Preferences, what: String): Preferences {
        check(record != base) { "fixture $what: must change the record" }; return record }
    /** [record] and a selection that both carry the same edit of seal [index] (so raw equality holds and integrity decides). */
    private fun editSealBoth(a: Applied, index: Int, change: MutableMap<String, JsonElement>.() -> Unit): Pair<Preferences, PreviousEvidenceSelection> {
        val target = a.seals[index]; val edited = obj(target, change)
        val record = withSeals(a.record, arr(a.record, sealKey).map { if (id(it) == id(target)) edited else it })
        return changed(a.record, record, "seal $index") to selection(a.item(rawSeals = a.seals.mapIndexed { i, s -> if (i == index) edited else s }))
    }
    private fun editWitnessBoth(a: Applied, index: Int, change: MutableMap<String, JsonElement>.() -> Unit) =
        editSealBoth(a, index) { this["settlement"] = obj(getValue("settlement"), change) }
    private fun editRowBoth(a: Applied, change: MutableMap<String, JsonElement>.() -> Unit): Pair<Preferences, PreviousEvidenceSelection> {
        val edited = obj(a.row, change)
        val record = withRows(a.record, arr(a.record, evidenceKey).map { if (cmd(it) == a.c.id) edited else it })
        return changed(a.record, record, "row") to selection(a.item(rawRow = edited))
    }
    private val inconsistent = PreviousSettlementEvidenceReclamation.Decision.Recovery(RecoveryReason.InconsistentReclamation)
    private val conflict = PreviousSettlementEvidenceReclamation.Decision.Conflict

    // ── exact selection → one atomic candidate; the return validator accepts exactly that ────────────────────────
    @Test fun C1_01_eachTransitionIsReclaimedExactly() {
        for (k in kinds()) {
            val a = Applied(k); val sel = selection(a.item())
            val d = decide(a.record, sel)
            assertTrue("D2B6/6-3C1.01 ${k.name}: ready $d", d is PreviousSettlementEvidenceReclamation.Decision.Ready)
            val candidate = (d as PreviousSettlementEvidenceReclamation.Decision.Ready).candidate
            assertEquals("D2B6/6-3C1.01 ${k.name}: exactDeletion", oracle(a.record, a.c.id), candidate)
            assertTrue("D2B6/6-3C1.01 ${k.name}: returnValid",
                PreviousSettlementEvidenceReclamation.validateReturn(ControlRecordReader(codec).read(candidate), sel, candidate))
        }
    }
    @Test fun C1_02_aMultiSelectionIsAllOrNothing() {
        // RETIRED_NAMESPACE over seal "s" and RETIRED_NULL over the CAPABILITY NULL seal "c": disjoint bundles in one record.
        val x = Applied(rn()); val y = Applied(rl(L.spec(target = ControlObligationFixtures.node(L.nullKrx)), "RETIRED_NULL-c"))
        val both = withRows(withSeals(x.record, arr(x.record, sealKey) + y.seals), arr(x.record, evidenceKey) + y.row)
        val ok = decide(both, selection(x.item(), y.item()))
        assertEquals("D2B6/6-3C1.02 both", PreviousSettlementEvidenceReclamation.Decision.Ready(oracle(both, x.c.id, y.c.id)), ok)
        // One wrong item refuses the whole call — nothing is removed for the other.
        val wrong = obj(y.row) { this["demandId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000dd") }
        assertEquals("D2B6/6-3C1.02 oneWrong", conflict, decide(both, selection(x.item(), y.item(rawRow = wrong))))
    }

    // ── latest vs the fixed selection ────────────────────────────────────────────────────────────────────────────
    @Test fun C1_03_selectionMustEqualTheLatestRowsAndSeals() {
        for (k in kinds()) {
            val a = Applied(k)
            val otherRow = obj(a.row) { this["ownerTrackingLifetimeId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000ab") }
            assertEquals("D2B6/6-3C1.03 ${k.name} rowRaw", conflict, decide(a.record, selection(a.item(rawRow = otherRow))))
            val otherSeal = obj(a.seals.first()) { this["settlement"] = obj(getValue("settlement")) {
                this["originLifetimeId"] = JsonPrimitive("selected-origin") } }
            assertEquals("D2B6/6-3C1.03 ${k.name} sealRaw", conflict,
                decide(a.record, selection(a.item(rawSeals = listOf(otherSeal) + a.seals.drop(1)))))
            if (a.seals.size >= 2) assertEquals("D2B6/6-3C1.03 ${k.name} sealOrder", conflict,
                decide(a.record, selection(a.item(rawSeals = a.seals.reversed()))))
            val noRow = withRows(a.record, arr(a.record, evidenceKey).filter { cmd(it) != a.c.id })
            assertEquals("D2B6/6-3C1.03 ${k.name} rowAbsentFirstCall", conflict, decide(noRow, selection(a.item())))
        }
    }
    @Test fun C1_04_onlyAPreviousLifetimeSettlementRowIsEligible() {
        val a = Applied(rn())
        // The same row under the CURRENT tracker lifetime.
        val (currentRecord, currentSel) = editRowBoth(a) { this["ownerTrackingLifetimeId"] = JsonPrimitive(current.value) }
        assertEquals("D2B6/6-3C1.04 currentLifetime", conflict, decide(currentRecord, currentSel))
        // A MUTATIONS row selected as a Settlement.
        val mutations = Json.parseToJsonElement("""{"version":2,"commandId":"${a.c.id}","ownerTrackingLifetimeId":"${a.old.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}""")
        val mRecord = withRows(a.record, listOf(mutations))
        assertEquals("D2B6/6-3C1.04 notSettlement", conflict, decide(mRecord, selection(a.item(rawRow = mutations))))
    }

    // ── bundle integrity, each alone ─────────────────────────────────────────────────────────────────────────────
    @Test fun C1_05_bundleIntegrity() {
        for (k in kinds()) {
            val a = Applied(k)
            for (i in a.seals.indices) {
                // A selected seal no longer settled by this operation (selection and latest carry the same change).
                val (other, sel) = editWitnessBoth(a, i) { this["operationId"] = JsonPrimitive("other-operation") }
                assertEquals("D2B6/6-3C1.05 ${k.name} otherOperation[$i]", inconsistent, decide(other, sel))
            }
            // An extra seal settled by this operation outside the row's ids.
            val extra = obj(a.seals.first()) { this["id"] = JsonPrimitive("extra-own") }
            assertEquals("D2B6/6-3C1.05 ${k.name} extraOwnSeal", inconsistent,
                decide(changed(a.record, withSeals(a.record, arr(a.record, sealKey) + extra), "extra"), selection(a.item())))
        }
    }
    @Test fun C1_06_retiredNamespaceWitnessAndDemandRules() {
        for (k in listOf(rn(), rnDeparted())) {
            val a = Applied(k)
            val cases = listOf(
                "operation" to editWitnessBoth(a, 0) { this["operation"] = JsonPrimitive("BEGIN_ROTATION") },
                "beforeEqualsAfter" to editWitnessBoth(a, 0) { this["after"] = obj(getValue("after")) { this["krxCapabilityEpoch"] = JsonPrimitive("k-other") } },
                "journalEpoch" to editWitnessBoth(a, 0) { this["journal"] = obj(getValue("journal")) { this["epoch"] = JsonNull } },
                "demandRule" to editRowBoth(a) {
                    this["demandId"] = if (getValue("demandId") is JsonNull) JsonPrimitive("00000000-0000-0000-0000-0000000000dd") else JsonNull })
            for ((name, pair) in cases) assertEquals("D2B6/6-3C1.06 ${k.name} $name", inconsistent, decide(pair.first, pair.second))
        }
    }
    @Test fun C1_07_currentNullSharedWitnessAndJournal() {
        val a = Applied(cn(NN.both(), "CURRENT_NULL-both"))
        check(a.seals.size == 4) { "fixture: two NULL + two companions" }
        for (i in a.seals.indices) {
            val (o, so) = editWitnessBoth(a, i) { this["originLifetimeId"] = JsonPrimitive("other-origin") }
            assertEquals("D2B6/6-3C1.07 originDiffers[$i]", inconsistent, decide(o, so))
        }
        // A companion's witness `before` differing from the bundle's (the schema pins a NULL seal's before owner to the
        // seal owner, so only companions reach the decider with a different before — reader-classified).
        val companions = a.seals.indices.filter { a.seals[it].jsonObject.getValue("kind").jsonPrimitive.content == "NAMESPACE" }
        check(companions.size == 2) { "fixture: two companions" }
        for (i in companions) {
            val (b, sb) = editWitnessBoth(a, i) { this["before"] = obj(getValue("before")) { this["ownerUid"] = JsonPrimitive("A2") } }
            assertEquals("D2B6/6-3C1.07 companionBeforeDiffers[$i]", inconsistent, decide(b, sb))
        }
        // A companion's journal carrying the old epoch instead of the shared null-epoch key.
        val companion = a.seals.indexOfFirst { it.jsonObject.getValue("kind").jsonPrimitive.content == "NAMESPACE" }
        val (j, sj) = editWitnessBoth(a, companion) { this["journal"] = obj(getValue("journal")) {
            this["epoch"] = a.seals[companion].jsonObject.getValue("epoch") } }
        assertEquals("D2B6/6-3C1.07 companionJournalNotShared", inconsistent, decide(j, sj))
    }
    @Test fun C1_08_retiredNullSharedWitness() {
        val a = Applied(rl(L.both(), "RETIRED_NULL-both"))
        for (i in a.seals.indices) {
            val (o, so) = editWitnessBoth(a, i) { this["originLifetimeId"] = JsonPrimitive("other-origin") }
            assertEquals("D2B6/6-3C1.08 originDiffers[$i]", inconsistent, decide(o, so))
        }
    }

    // ── retry with the same selection ────────────────────────────────────────────────────────────────────────────
    @Test fun C1_09_retryAllPresentAllAbsentOrPartial() {
        for (k in kinds()) {
            val a = Applied(k); val sel = selection(a.item())
            assertEquals("D2B6/6-3C1.09 ${k.name} present", PreviousSettlementEvidenceReclamation.Decision.Ready(oracle(a.record, a.c.id)),
                decide(a.record, sel, retry = true))
            val absent = oracle(a.record, a.c.id)
            assertEquals("D2B6/6-3C1.09 ${k.name} absent", PreviousSettlementEvidenceReclamation.Decision.Ready(interpretable(absent).original),
                decide(absent, sel, retry = true))
            val rowOnly = withRows(absent, arr(absent, evidenceKey) + a.row)
            assertEquals("D2B6/6-3C1.09 ${k.name} rowOnly", inconsistent, decide(rowOnly, sel, retry = true))
            val sealOnly = withSeals(absent, arr(absent, sealKey) + a.seals.first())
            assertEquals("D2B6/6-3C1.09 ${k.name} sealOnly", inconsistent, decide(sealOnly, sel, retry = true))
            val outside = withSeals(absent, arr(absent, sealKey) + obj(a.seals.first()) { this["id"] = JsonPrimitive("outside-own") })
            assertEquals("D2B6/6-3C1.09 ${k.name} ownSealOutsideIds", inconsistent, decide(outside, sel, retry = true))
            val prepared = withSeals(absent, arr(absent, sealKey) + arr(k.raw, sealKey).first())
            assertEquals("D2B6/6-3C1.09 ${k.name} replacementAtAnId", inconsistent, decide(prepared, sel, retry = true))
        }
    }

    // ── survivors, the return validator, the codec limit ─────────────────────────────────────────────────────────
    @Test fun C1_10_survivorsReturnValidatorAndTooLarge() {
        val x = Applied(rn()); val y = Applied(rl(L.spec(target = ControlObligationFixtures.node(L.nullKrx)), "RETIRED_NULL-c"))
        val both = withRows(withSeals(x.record, arr(x.record, sealKey) + y.seals), arr(x.record, evidenceKey) + y.row)
        val sel = selection(x.item())
        val candidate = (decide(both, sel) as PreviousSettlementEvidenceReclamation.Decision.Ready).candidate
        assertEquals("D2B6/6-3C1.10 survivorKept", oracle(both, x.c.id), candidate)
        assertTrue("D2B6/6-3C1.10 returnValid", PreviousSettlementEvidenceReclamation.validateReturn(ControlRecordReader(codec).read(candidate), sel, candidate))
        val tampers = listOf(
            "rowBack" to withRows(candidate, arr(candidate, evidenceKey) + x.row),
            "sealBack" to withSeals(candidate, arr(candidate, sealKey) + x.seals.first()),
            "survivorSealLost" to withSeals(candidate, arr(candidate, sealKey).filter { op(it) != y.c.id }),
            "survivorRowLost" to withRows(candidate, arr(candidate, evidenceKey).filter { cmd(it) != y.c.id }))
        for ((name, returned) in tampers) assertFalse("D2B6/6-3C1.10 $name",
            PreviousSettlementEvidenceReclamation.validateReturn(ControlRecordReader(codec).read(returned), sel, candidate))
        // A surviving seal with raw unpaired surrogates re-encodes past 65,536 bytes.
        val survivor = """{"id":"zz","kind":"NAMESPACE","ownerUid":"${"\uD800".repeat(12_000)}","axis":"CAPABILITY","epoch":"k9"}"""
        val big = x.record.toMutablePreferences().apply { this[sealKey] = checkNotNull(this[sealKey]).removeSuffix("]") + "," + survivor + "]" }.toPreferences()
        val d = decide(big, selection(x.item()))
        val reason = (d as? PreviousSettlementEvidenceReclamation.Decision.Rejected)?.reason
        assertTrue("D2B6/6-3C1.10 tooLarge $d", reason is RejectionReason.TooLarge && reason.payloadKey == ControlPayloadKey.SEAL)
    }

    // ── r2 (Codex REVISE r1): decider-owned rules the parsers do not check ──────────────────────────────────────
    /** Record and selection with the same row edit and the same per-seal edits (raw equality holds; integrity decides). */
    private fun editBoth(a: Applied, rowChange: (MutableMap<String, JsonElement>.() -> Unit)?, sealChanges: Map<Int, MutableMap<String, JsonElement>.() -> Unit>,
        what: String): Pair<Preferences, PreviousEvidenceSelection> {
        val row = rowChange?.let { obj(a.row, it) } ?: a.row
        val seals = a.seals.mapIndexed { i, s -> sealChanges[i]?.let { obj(s, it) } ?: s }
        val byOld = a.seals.map { id(it) }.zip(seals).toMap()
        var record = withSeals(a.record, arr(a.record, sealKey).map { byOld[id(it)] ?: it })
        record = withRows(record, arr(record, evidenceKey).map { if (cmd(it) == a.c.id) row else it })
        // Seal ids may have changed: keep the row's ids and the seals consistent with each other.
        return changed(a.record, record, what) to selection(a.item(rawRow = row, rawSeals = seals))
    }
    @Test fun C1_11_retiredNullDistinctAxesSameOwnerAndV2Link() {
        val a = Applied(rl(L.both(), "RETIRED_NULL-both"))
        check(a.seals.size == 2) { "fixture: two NULL seals" }
        val cases = listOf(
            // Both seals on the USER axis (the second keeps its own id).
            "sameAxis" to editBoth(a, null, mapOf(0 to { this["axis"] = JsonPrimitive("USER")
                this["settlement"] = obj(getValue("settlement")) { this["journal"] = obj(getValue("journal")) { this["axis"] = JsonPrimitive("USER") } } },
                1 to { this["axis"] = JsonPrimitive("USER")
                this["settlement"] = obj(getValue("settlement")) { this["journal"] = obj(getValue("journal")) { this["axis"] = JsonPrimitive("USER") } } }), "sameAxis"),
            // The two departed owners differ (journal owner follows each seal owner).
            "ownersDiffer" to editBoth(a, null, mapOf(0 to { this["ownerUid"] = JsonPrimitive("Z")
                this["settlement"] = obj(getValue("settlement")) { this["journal"] = obj(getValue("journal")) { this["ownerUid"] = JsonPrimitive("Z") } } }), "ownersDiffer"),
            // RETIRED_NULL row, but a seal carries a valid V1 SIGN_OUT witness instead of V2.
            "v1Witness" to editBoth(a, null, mapOf(0 to {
                val w = getValue("settlement").jsonObject
                this["settlement"] = Json.parseToJsonElement("""{"operationId":${w.getValue("operationId")},"originLifetimeId":${w.getValue("originLifetimeId")},"operation":"SIGN_OUT","before":{"ownerUid":${getValue("ownerUid")},"userAccessEpoch":null,"krxCapabilityEpoch":null},"after":{"ownerUid":"B","userAccessEpoch":null,"krxCapabilityEpoch":null},"journal":{"ownerUid":${getValue("ownerUid")},"axis":${getValue("axis")},"epoch":null}}""") }), "v1Witness"))
        for ((name, pair) in cases) {
            interpretable(pair.first)
            assertEquals("D2B6/6-3C1.11 $name", inconsistent, decide(pair.first, pair.second))
        }
    }
    @Test fun C1_12_currentNullOrderAxesAndCompanionLinks() {
        val a = Applied(cn(NN.both(), "CURRENT_NULL-both"))
        val kinds = a.seals.map { it.jsonObject.getValue("kind").jsonPrimitive.content }
        val companions = kinds.indices.filter { kinds[it] == "NAMESPACE" }
        // Non-canonical order: the row and the selection both list the seals reversed.
        val reversedIds = JsonArray(a.row.jsonObject.getValue("sealIds").jsonArray.reversed())
        val reversedRow = obj(a.row) { this["sealIds"] = reversedIds }
        val reversedRecord = changed(a.record, withRows(a.record, arr(a.record, evidenceKey).map { if (cmd(it) == a.c.id) reversedRow else it }), "order")
        interpretable(reversedRecord)
        assertEquals("D2B6/6-3C1.12 nonCanonicalOrder", inconsistent, decide(reversedRecord, selection(a.item(rawRow = reversedRow, rawSeals = a.seals.reversed()))))
        // A companion whose axis has no NULL seal in the bundle: drop the CAPABILITY NULL, keep its companion.
        val capNull = kinds.indices.single { kinds[it] == "NULL_NAMESPACE" && a.seals[it].jsonObject.getValue("axis").jsonPrimitive.content == "CAPABILITY" }
        val keptIds = a.seals.filterIndexed { i, _ -> i != capNull }
        val dropRow = obj(a.row) { this["sealIds"] = JsonArray(keptIds.map { JsonPrimitive(id(it)) }) }
        val dropRecord = withRows(withSeals(a.record, arr(a.record, sealKey).filter { id(it) != id(a.seals[capNull]) }),
            arr(a.record, evidenceKey).map { if (cmd(it) == a.c.id) dropRow else it })
        interpretable(changed(a.record, dropRecord, "companionWithoutNull"))
        assertEquals("D2B6/6-3C1.12 companionWithoutItsNull", inconsistent, decide(dropRecord, selection(a.item(rawRow = dropRow, rawSeals = keptIds))))
        for (i in companions) {
            val (op, sop) = editWitnessBoth(a, i) { this["operation"] = JsonPrimitive("SIGN_OUT") }
            interpretable(op)
            assertEquals("D2B6/6-3C1.12 companionOperation[$i]", inconsistent, decide(op, sop))
            val (ep, sep) = editSealBoth(a, i) { this["epoch"] = JsonPrimitive("e-unlinked") }
            interpretable(ep)
            assertEquals("D2B6/6-3C1.12 companionEpochUnlinked[$i]", inconsistent, decide(ep, sep))
        }
    }
    @Test fun C1_13_selectionIsADefensiveCopy() {
        val a = Applied(cn(NN.both(), "CURRENT_NULL-both"))
        val seals = a.seals.map { node(it) }.toMutableList()
        val item = PreviousEvidenceSelection.Item.Settlement(a.c.id, node(a.row), seals)
        val items = mutableListOf<PreviousEvidenceSelection.Item>(item)
        val sel = PreviousEvidenceSelection(items)
        seals.clear(); items.clear()
        assertEquals("D2B6/6-3C1.13 itemsKept", 1, sel.items.size)
        assertEquals("D2B6/6-3C1.13 sealsKept", a.seals.size, (sel.items.single() as PreviousEvidenceSelection.Item.Settlement).orderedRawSeals.size)
        assertEquals("D2B6/6-3C1.13 stillReady", PreviousSettlementEvidenceReclamation.Decision.Ready(oracle(a.record, a.c.id)), decide(a.record, sel))
    }

    // ── r3 (measurement 6-3C1 r1): rows the r2 inputs walked past ──────────────────────────────────────────────
    private fun invalid(d: PreviousSettlementEvidenceReclamation.Decision) =
        (d as? PreviousSettlementEvidenceReclamation.Decision.Rejected)?.reason is RejectionReason.InvalidRequest
    @Test fun C1_14_theDeciderRefusesAnInvalidSelection() {
        val a = Applied(rn()); val b = Applied(rl(L.spec(target = ControlObligationFixtures.node(L.nullKrx)), "RETIRED_NULL-c"))
        val record = withRows(withSeals(a.record, arr(a.record, sealKey) + b.seals), arr(a.record, evidenceKey) + b.row)
        assertTrue("D2B6/6-3C1.14 empty", invalid(decide(record, PreviousEvidenceSelection(emptyList()))))
        assertTrue("D2B6/6-3C1.14 lifecycle", invalid(decide(record, selection(a.item(), PreviousEvidenceSelection.Item.Lifecycle(b.c.id, node(b.row))))))
        // Same command twice with disjoint seals, so only the duplicate-command rule applies.
        assertTrue("D2B6/6-3C1.14 duplicateCommand", invalid(decide(record, selection(a.item(),
            PreviousEvidenceSelection.Item.Settlement(a.c.id, node(a.row), b.seals.map { node(it) })))))
        assertTrue("D2B6/6-3C1.14 unreadableSeal", invalid(decide(record, selection(a.item(rawSeals = listOf(Json.parseToJsonElement("""{"id":"s"}""")))))))
        assertTrue("D2B6/6-3C1.14 duplicateSeal", invalid(decide(record, selection(a.item(),
            PreviousEvidenceSelection.Item.Settlement(b.c.id, node(b.row), a.seals.map { node(it) })))))
    }
    @Test fun C1_15_currentNullAxisCompositionAndSharedAfter() {
        val a = Applied(cn(NN.both(), "CURRENT_NULL-both"))
        val kinds = a.seals.map { it.jsonObject.getValue("kind").jsonPrimitive.content }
        val axis = { i: Int -> a.seals[i].jsonObject.getValue("axis").jsonPrimitive.content }
        val nulls = kinds.indices.filter { kinds[it] == "NULL_NAMESPACE" }; val comps = kinds.indices.filter { kinds[it] == "NAMESPACE" }
        val krxBefore = a.seals[0].jsonObject.getValue("settlement").jsonObject.getValue("before").jsonObject.getValue("krxCapabilityEpoch")
        /** Rebuild the bundle from [seals] (row ids in the given canonical order); every witness keeps CAPABILITY unrotated. */
        fun rebuilt(seals: List<JsonElement>, what: String): Pair<Preferences, PreviousEvidenceSelection> {
            val fixed = seals.map { e -> obj(e) { this["settlement"] = obj(getValue("settlement")) {
                this["after"] = obj(getValue("after")) { this["krxCapabilityEpoch"] = krxBefore } } } }
            val row = obj(a.row) { this["sealIds"] = JsonArray(fixed.map { JsonPrimitive(id(it)) }) }
            val others = arr(a.record, sealKey).filter { e -> a.seals.none { id(it) == id(e) } }
            val record = withRows(withSeals(a.record, others + fixed), arr(a.record, evidenceKey).map { if (cmd(it) == a.c.id) row else it })
            interpretable(changed(a.record, record, what))
            return record to selection(a.item(rawRow = row, rawSeals = fixed))
        }
        val toUser: MutableMap<String, JsonElement>.() -> Unit = { this["axis"] = JsonPrimitive("USER")
            this["settlement"] = obj(getValue("settlement")) { this["journal"] = obj(getValue("journal")) { this["axis"] = JsonPrimitive("USER") } } }
        val capNull = nulls.single { axis(it) == "CAPABILITY" }; val userNull = nulls.single { axis(it) == "USER" }
        val capComp = comps.single { axis(it) == "CAPABILITY" }; val userComp = comps.single { axis(it) == "USER" }
        // Two NULL seals on USER (the CAPABILITY companion dropped, CAPABILITY unrotated), canonical order NULL, NULL, companion.
        val (sameNull, sSameNull) = rebuilt(listOf(a.seals[userNull], obj(a.seals[capNull], toUser), a.seals[userComp]), "sameAxisNulls")
        assertEquals("D2B6/6-3C1.15 twoNullsOneAxis", inconsistent, decide(sameNull, sSameNull))
        // Two companions on USER (the CAPABILITY NULL dropped, CAPABILITY unrotated; the moved companion takes another USER epoch-free id key).
        val userEpoch = a.seals[userComp].jsonObject.getValue("epoch")
        val movedComp = obj(a.seals[capComp], toUser).let { obj(it) { this["epoch"] = userEpoch; this["ownerUid"] = getValue("ownerUid") } }
        val (sameComp, sSameComp) = rebuilt(listOf(a.seals[userNull], a.seals[userComp], movedComp), "sameAxisCompanions")
        assertEquals("D2B6/6-3C1.15 twoCompanionsOneAxis", inconsistent, decide(sameComp, sSameComp))
        // One companion's witness `after` differs from the bundle's.
        val (after, sAfter) = editWitnessBoth(a, comps.first()) { this["after"] = obj(getValue("after")) { this["userAccessEpoch"] = JsonPrimitive("u-other") } }
        interpretable(after)
        assertEquals("D2B6/6-3C1.15 companionAfterDiffers", inconsistent, decide(after, sAfter))
        // Single USER NULL bundle: every witness moves the CAPABILITY epoch although no NULL is on that axis.
        val one = Applied(cn())
        val moved = one.seals.indices.associateWith { { m: MutableMap<String, JsonElement> -> m["settlement"] = obj(m.getValue("settlement")) {
            this["after"] = obj(getValue("after")) { this["krxCapabilityEpoch"] = JsonPrimitive("k-moved") } } } }
        val (axisMoved, sAxisMoved) = editBoth(one, null, moved.mapValues { (_, f) -> { f(this) } }, "unrotatedAxisMoved")
        interpretable(axisMoved)
        assertEquals("D2B6/6-3C1.15 unrotatedAxisEpochMoved", inconsistent, decide(axisMoved, sAxisMoved))
    }
    @Test fun C1_16_retiredNullOrderAndSharedBefore() {
        val a = Applied(rl(L.both(), "RETIRED_NULL-both"))
        val reversedRow = obj(a.row) { this["sealIds"] = JsonArray(a.row.jsonObject.getValue("sealIds").jsonArray.reversed()) }
        val reversed = changed(a.record, withRows(a.record, arr(a.record, evidenceKey).map { if (cmd(it) == a.c.id) reversedRow else it }), "order")
        interpretable(reversed)
        assertEquals("D2B6/6-3C1.16 nonCanonicalOrder", inconsistent, decide(reversed, selection(a.item(rawRow = reversedRow, rawSeals = a.seals.reversed()))))
        // One seal's V2 witness before == after, but a different value from the other seal's.
        val (b, sb) = editWitnessBoth(a, 1) {
            val moved = obj(getValue("before")) { this["krxCapabilityEpoch"] = JsonPrimitive("k-other") }
            this["before"] = moved; this["after"] = moved }
        interpretable(b)
        assertEquals("D2B6/6-3C1.16 beforeDiffers", inconsistent, decide(b, sb))
    }
    @Test fun C1_17_retryWithOneOfTwoSelectedRowsStillPresentIsHeld() {
        val x = Applied(rn()); val y = Applied(rl(L.spec(target = ControlObligationFixtures.node(L.nullKrx)), "RETIRED_NULL-c"))
        val both = withRows(withSeals(x.record, arr(x.record, sealKey) + y.seals), arr(x.record, evidenceKey) + y.row)
        // x fully gone; y's row alone remains (no seal at any selected id, no seal of either operation), so only the
        // "a selected row is still present" rule applies.
        val onlyYRow = withRows(oracle(both, x.c.id, y.c.id), arr(oracle(both, x.c.id, y.c.id), evidenceKey) + y.row)
        assertEquals("D2B6/6-3C1.17", inconsistent, decide(onlyYRow, selection(x.item(), y.item()), retry = true))
    }

    // ── r4 (Codex REVISE of r3): a null-owner bundle where only the seal-owner rule sees a foreign companion ────────
    @Test fun C1_18_currentNullCompanionOwnerMustEqualTheBundleOwnerEvenWhenItIsNull() {
        // before/after owner null, NULL seals owned by null, every journal owner null (the schema accepts a null journal
        // owner for a NAMESPACE companion), but one companion still owned by "A": only the seal-owner rule refuses it.
        val a = Applied(cn(NN.both(), "CURRENT_NULL-both"))
        val kinds = a.seals.map { it.jsonObject.getValue("kind").jsonPrimitive.content }
        val firstCompanion = kinds.indexOfFirst { it == "NAMESPACE" }
        val edits = a.seals.indices.associateWith { i -> { m: MutableMap<String, JsonElement> ->
            if (kinds[i] == "NULL_NAMESPACE" || i != firstCompanion) m["ownerUid"] = JsonNull
            m["settlement"] = obj(m.getValue("settlement")) {
                this["before"] = obj(getValue("before")) { this["ownerUid"] = JsonNull }
                this["after"] = obj(getValue("after")) { this["ownerUid"] = JsonNull }
                this["journal"] = obj(getValue("journal")) { this["ownerUid"] = JsonNull } } } }
        val (record, sel) = editBoth(a, null, edits.mapValues { (_, f) -> { f(this) } }, "nullOwnerBundle")
        interpretable(record)
        check(a.seals[firstCompanion].jsonObject.getValue("ownerUid") == JsonPrimitive("A")) { "fixture: companion owned by A" }
        assertEquals("D2B6/6-3C1.18 foreignCompanionOwner", inconsistent, decide(record, sel))
        // Control: the same null-owner bundle with that companion also null-owned is reclaimed.
        val consistentEdits = a.seals.indices.associateWith { i -> { m: MutableMap<String, JsonElement> ->
            m["ownerUid"] = JsonNull
            m["settlement"] = obj(m.getValue("settlement")) {
                this["before"] = obj(getValue("before")) { this["ownerUid"] = JsonNull }
                this["after"] = obj(getValue("after")) { this["ownerUid"] = JsonNull }
                this["journal"] = obj(getValue("journal")) { this["ownerUid"] = JsonNull } } } }
        val (okRecord, okSel) = editBoth(a, null, consistentEdits.mapValues { (_, f) -> { f(this) } }, "nullOwnerConsistent")
        interpretable(okRecord)
        assertTrue("D2B6/6-3C1.18 nullOwnerConsistentIsReady", decide(okRecord, okSel) is PreviousSettlementEvidenceReclamation.Decision.Ready)
    }
}
