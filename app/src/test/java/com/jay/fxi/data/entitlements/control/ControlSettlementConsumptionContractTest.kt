package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Claude-owned 6-3B1 contract: the pure R/N/L consumption decider (6-3 skeleton r3 §2–§3, T2–T4; 6-3B API consensus c').
 * API fixed by this contract:
 *   internal object ControlSettlementConsumption {
 *     sealed interface Decision { Ready(candidate: Preferences); Recovery(reason: RecoveryReason); object Conflict;
 *       Rejected(reason: RejectionReason) }
 *     fun decide(read: ControlRecordRead.Supported, command: CommandRef, input: HandoverSettlementInput,
 *       expected: AppliedEvidence.Settlement?, retry: Boolean, codec: ControlPayloadCodec): Decision
 *     fun validateReturn(returned: ControlRecordRead, command: CommandRef, candidate: Preferences): Boolean
 *   }
 *   RecoveryReason.ExpectedSettlementEvidenceUnavailable.
 * The decider's precondition is a wholly interpretable schema-2 read (schema/opaque are the owner's earlier checks, 6-3B2).
 * Order: expected null → ExpectedSettlementEvidenceUnavailable; own row found by commandId only — absent on first entry →
 * CommandEvidenceLost, on retry wholly absent (no own-operation seal, no seal at a fixed id) → Ready(read.original), else
 * InconsistentReclamation; the row's ordered sealIds are compared with the fixed order FIRST (order-only change →
 * InconsistentReclamation); then any difference of the row/expected commandId, lifetime, transition or demandId → Conflict;
 * then each fixed seal exists, is settled by this operation, and the operation's settled-seal set equals the fixed set;
 * each seal's immutable text and witness equal the fixed input (R V1 JOURNAL_RETIRED, N V1 BEGIN_ROTATION, L V2
 * RETIRED_NULL) — failures → InconsistentReclamation. The candidate removes exactly the own row and the full own seal bundle;
 * every other seal/row (original text and order) and every other key survive; current epochs/journal/REQUEST are NOT
 * required to still hold their applied values. Re-encoding past the codec limit → Rejected(TooLarge). Records come from the
 * real transitions' Confirm candidates. r2 (Codex REVISE r1): single-field variants were first classified by the reader
 * alone; only those that stay wholly interpretable AND change the record are decider rows here. Parser-precluded variants
 * (e.g. R/L seal ownerUid or kind flip, journal owner, L witness after, transitions whose cardinality/demand rules the
 * schema rejects) are the owner's earlier schema classification — 6-3B2 T2.6, not this decider. The implementation
 * thread reads but does not edit this file.
 */
class ControlSettlementConsumptionContractTest {
    private val codec = ControlPayloadCodec()
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val R = RetiredNamespaceFixtures; private val NN = CurrentNullFixtures; private val L = RetiredNullFixtures

    private class Kind(
        val name: String, val input: HandoverSettlementInput, val raw: Preferences, val body: ControlCommandBody.Handover,
        val decide: (CommandRef, ControlRecordRead.Supported) -> RecordTransactionDecision<ControlRecordStore.Outcome>
    )
    private fun r(s: RetiredNamespaceSettlement = R.spec(), ctx: AttemptContext = R.context) =
        Kind("R", s, R.raw(s), ControlCommandBody.SettleRetiredNamespace(s)) { c, read -> R.transition.decide(c, s, read, ctx, false, false) }
    private fun rDeparted() = R.departed().let { s -> r(s, AttemptContext("B", 9, R.life, false, false)).let {
        Kind("R-departed", it.input, it.raw, it.body, it.decide) } }
    private fun n(s: CurrentNullSettlement = NN.spec(), name: String = "N") =
        Kind(name, s, NN.raw(s), ControlCommandBody.RotateAndSettleCurrentNull(s)) { c, read -> NN.transition.decide(c, s, read, NN.context, false, false) }
    private fun l(s: RetiredNullSettlement = L.spec(), name: String = "L") =
        Kind(name, s, L.raw(s), ControlCommandBody.SettleRetiredNull(s)) { c, read -> L.transition.decide(c, s, read, L.context, false, false) }
    private fun kinds() = listOf(r(), n(), n(NN.both(), "N-both"), l(), l(L.both(), "L-both"))
    private fun multi() = listOf(n(NN.both(), "N-both"), l(L.both(), "L-both"))

    /** One applied case: the command, the real Confirm candidate (own row + settled bundle) and its expected row. */
    private inner class Case(val k: Kind) {
        val c = CommandRef(k.input.operationId, k.body, OwnerTrackingLifetimeId.issue())
        val applied: Preferences = k.decide(c, read(k.raw)).let {
            check(it is RecordTransactionDecision.Confirm) { "fixture ${k.name}: transition must apply: $it" }
            it.candidate
        }
        val expected = ControlAppliedEvidence.own(read(applied), c) as AppliedEvidence.Settlement
        fun decide(record: Preferences = applied, exp: AppliedEvidence.Settlement? = expected, retry: Boolean = false) =
            ControlSettlementConsumption.decide(interpretable(record), c, k.input, exp, retry, codec)
        /** Independent oracle: [record] minus exactly the own row and every seal settled by this operation. */
        fun oracle(record: Preferences = applied): Preferences = record.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(record, evidenceKey).filter { cmd(it) != c.id }).toString()
            this[sealKey] = JsonArray(arr(record, sealKey).filter { op(it) != c.id }).toString()
        }.toPreferences()
    }

    private fun read(p: Preferences) = ControlRecordReader().read(p) as ControlRecordRead.Supported
    private fun interpretable(p: Preferences): ControlRecordRead.Supported {
        val read = ControlRecordReader(codec).read(p)
        check(read is ControlRecordRead.Supported && read.schemaVersion == 2 && !read.hasUninterpretable &&
            !read.hasUninterpretableMetadata) { "fixture: decider precondition (wholly interpretable schema 2) violated" }
        return read
    }
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun cmd(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))
    private fun withSeals(p: Preferences, seals: List<JsonElement>) = p.toMutablePreferences().apply { this[sealKey] = JsonArray(seals).toString() }.toPreferences()
    private fun editSeal(p: Preferences, sealId: String, change: (JsonElement) -> JsonElement?) =
        withSeals(p, arr(p, sealKey).mapNotNull { if (id(it) == sealId) change(it) else it }.also {
            check(it != arr(p, sealKey).toList()) { "fixture edit of $sealId" } })
    private fun editSettlement(p: Preferences, sealId: String, change: MutableMap<String, JsonElement>.() -> Unit) =
        editSeal(p, sealId) { s -> obj(s) { this["settlement"] = obj(getValue("settlement"), change) } }
    private fun editRow(p: Preferences, c: CommandRef, change: MutableMap<String, JsonElement>.() -> Unit) = p.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(p, evidenceKey).map { if (cmd(it) == c.id) obj(it, change) else it }).toString()
    }.toPreferences()
    private fun recovery(reason: RecoveryReason) = ControlSettlementConsumption.Decision.Recovery(reason)
    private val inconsistent get() = recovery(RecoveryReason.InconsistentReclamation)

    // ── T4.1: exact bundle → the independent deletion; the return validator accepts exactly that ────────────────
    @Test fun B1_01_exactBundleIsReadyWithExactlyTheOwnRowAndBundleRemoved() {
        for (k in kinds() + rDeparted()) {
            val x = Case(k)
            check(arr(x.applied, sealKey).count { op(it) == x.c.id } >= 1) { "fixture ${k.name}: settled" }
            val d = x.decide()
            assertTrue("D2B6/6-3B1.01 ${k.name}: ready $d", d is ControlSettlementConsumption.Decision.Ready)
            val candidate = (d as ControlSettlementConsumption.Decision.Ready).candidate
            assertEquals("D2B6/6-3B1.01 ${k.name}: exactDeletion", x.oracle(), candidate)
            assertTrue("D2B6/6-3B1.01 ${k.name}: returnValid",
                ControlSettlementConsumption.validateReturn(ControlRecordReader(codec).read(candidate), x.c, candidate))
        }
    }

    // ── T2: expected / own classification ───────────────────────────────────────────────────────────────────────
    @Test fun B1_02_nullExpectedIsUnavailable() {
        for (k in kinds()) assertEquals("D2B6/6-3B1.02 ${k.name}", recovery(RecoveryReason.ExpectedSettlementEvidenceUnavailable),
            Case(k).decide(exp = null))
    }
    @Test fun B1_03_ownRowAbsenceOnFirstEntryAndRetry() {
        for (k in kinds()) {
            val x = Case(k)
            val noRow = x.applied.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
            assertEquals("D2B6/6-3B1.03a ${k.name}: firstEntryLost", recovery(RecoveryReason.CommandEvidenceLost), x.decide(noRow))
            assertEquals("D2B6/6-3B1.03b ${k.name}: retryPartialIsHeld", inconsistent, x.decide(noRow, retry = true))
            val absent = x.oracle()
            val allAbsent = x.decide(absent, retry = true)
            assertEquals("D2B6/6-3B1.03c ${k.name}: retryAllAbsentConfirmsOriginal",
                ControlSettlementConsumption.Decision.Ready(interpretable(absent).original), allAbsent)
            // A replacement at a fixed id (the prepared, unsettled original) is not an all-absent bundle.
            val fixedId = id(arr(k.raw, sealKey).first())
            val replacement = withSeals(absent, arr(absent, sealKey) + arr(k.raw, sealKey).first { id(it) == fixedId })
            assertEquals("D2B6/6-3B1.03d ${k.name}: retryReplacementIsHeld", inconsistent, x.decide(replacement, retry = true))
        }
    }
    @Test fun B1_04_eachRowOrExpectedFieldAlone() {
        for (k in kinds()) {
            val x = Case(k)
            // commandId: the own row is looked up by commandId only, so it is simply not found.
            assertEquals("D2B6/6-3B1.04 ${k.name} commandId", recovery(RecoveryReason.CommandEvidenceLost),
                x.decide(editRow(x.applied, x.c) { this["commandId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000cc") }))
            assertEquals("D2B6/6-3B1.04 ${k.name} rowLifetime", ControlSettlementConsumption.Decision.Conflict,
                x.decide(editRow(x.applied, x.c) { this["ownerTrackingLifetimeId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000ab") }))
            val e = x.expected
            val otherTransition = HandoverSettlementTransition.entries.first { it != e.transition }
            assertEquals("D2B6/6-3B1.04 ${k.name} expectedTransition", ControlSettlementConsumption.Decision.Conflict,
                x.decide(exp = AppliedEvidence.Settlement(e.commandId, e.ownerTrackingLifetimeId, otherTransition, e.sealIds, e.demandId)))
            assertEquals("D2B6/6-3B1.04 ${k.name} expectedLifetime", ControlSettlementConsumption.Decision.Conflict,
                x.decide(exp = AppliedEvidence.Settlement(e.commandId, "00000000-0000-0000-0000-0000000000ab", e.transition, e.sealIds, e.demandId)))
            assertEquals("D2B6/6-3B1.04 ${k.name} expectedDemand", ControlSettlementConsumption.Decision.Conflict,
                x.decide(exp = AppliedEvidence.Settlement(e.commandId, e.ownerTrackingLifetimeId, e.transition, e.sealIds,
                    if (e.demandId == null) "00000000-0000-0000-0000-0000000000dd" else null)))
            if (e.demandId != null) assertEquals("D2B6/6-3B1.04 ${k.name} rowDemand", ControlSettlementConsumption.Decision.Conflict,
                x.decide(editRow(x.applied, x.c) { this["demandId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000dd") }))
        }
    }
    @Test fun B1_05_orderOnlyChangeFailsTheFixedOrderFirst() {
        for (k in multi()) {
            val x = Case(k)
            val ids = x.expected.sealIds
            check(ids.size >= 2) { "fixture ${k.name}: two seals" }
            val reordered = editRow(x.applied, x.c) { this["sealIds"] = JsonArray(ids.reversed().map { JsonPrimitive(it) }) }
            assertEquals("D2B6/6-3B1.05 ${k.name}", inconsistent, x.decide(reordered))
        }
    }

    // ── T3: full bundle predicates, each alone ──────────────────────────────────────────────────────────────────
    @Test fun B1_06_eachBundlePredicateAlone() {
        for (k in kinds()) {
            val x = Case(k); val s = x.expected.sealIds.first()
            val original = arr(k.raw, sealKey).first { id(it) == s }
            val cases = listOf(
                "missing" to editSeal(x.applied, s) { null },
                "unsettled" to editSeal(x.applied, s) { original },
                "otherOperation" to editSettlement(x.applied, s) { this["operationId"] = JsonPrimitive("other-operation") },
                "witnessOrigin" to editSettlement(x.applied, s) { this["originLifetimeId"] = JsonPrimitive("other-origin") },
                // The L V2 witness schema requires before == after, so both move together there.
                "witnessBefore" to editSettlement(x.applied, s) {
                    val moved = obj(getValue("before")) { this["krxCapabilityEpoch"] = JsonPrimitive("k-other") }
                    this["before"] = moved; if ("version" in this) this["after"] = moved },
                "extraOwnSeal" to withSeals(x.applied, arr(x.applied, sealKey) + obj(arr(x.applied, sealKey).first { id(it) == s }) {
                    this["id"] = JsonPrimitive("extra-own") }))
            for ((name, record) in cases) assertEquals("D2B6/6-3B1.06 ${k.name} $name", inconsistent, x.decide(record))
        }
    }
    @Test fun B1_07_movedEpochsConsumedRequestAndOtherOperationsSealAreNotObstacles() {
        for (k in kinds()) {
            val x = Case(k)
            val foreign = obj(arr(x.applied, sealKey).first()) { this["id"] = JsonPrimitive("foreign-seal")
                this["settlement"] = obj(getValue("settlement")) { this["operationId"] = JsonPrimitive("foreign-operation") } }
            val moved = withSeals(x.applied, listOf(foreign) + arr(x.applied, sealKey)).toMutablePreferences().apply {
                this[DataStoreAccessEpochStore.USER_EPOCH] = "moved-user"
                this[DataStoreAccessEpochStore.KRX_EPOCH] = "moved-krx"
                this[demandKey] = "[]"
            }.toPreferences()
            val d = x.decide(moved)
            assertTrue("D2B6/6-3B1.07 ${k.name}: ready $d", d is ControlSettlementConsumption.Decision.Ready)
            assertEquals("D2B6/6-3B1.07 ${k.name}: foreignKeptFirst", x.oracle(moved), (d as ControlSettlementConsumption.Decision.Ready).candidate)
        }
    }

    // ── T4.3: the return validator ──────────────────────────────────────────────────────────────────────────────
    @Test fun B1_08_returnValidatorRejectsEachTamper() {
        for (k in kinds()) {
            val x = Case(k)
            val candidate = (x.decide() as ControlSettlementConsumption.Decision.Ready).candidate
            val ownSeals = arr(x.applied, sealKey).filter { op(it) == x.c.id }
            val tampers = listOf(
                "ownRowBack" to candidate.toMutablePreferences().apply { this[evidenceKey] = checkNotNull(x.applied[evidenceKey]) }.toPreferences(),
                "oneOwnSealBack" to withSeals(candidate, arr(candidate, sealKey) + ownSeals.first()),
                "otherKeyChanged" to candidate.toMutablePreferences().apply { this[DataStoreAccessEpochStore.USER_EPOCH] = "tampered" }.toPreferences())
            for ((name, returned) in tampers) assertFalse("D2B6/6-3B1.08 ${k.name} $name",
                ControlSettlementConsumption.validateReturn(ControlRecordReader(codec).read(returned), x.c, candidate))
        }
        // A surviving seal or row that disappears from the return (another operation's bundle is kept by the candidate).
        val x = Case(r())
        val other = Case(r(R.spec(target = ControlObligationFixtures.node(NamespaceSettlementFixtures.krx), op = "r-other", did = "r-other-demand")))
        val both = x.applied.toMutablePreferences().apply {
            this[sealKey] = JsonArray(arr(other.applied, sealKey) + arr(x.applied, sealKey)).toString()
            this[evidenceKey] = JsonArray(arr(x.applied, evidenceKey) + arr(other.applied, evidenceKey)).toString()
        }.toPreferences()
        val candidate = (x.decide(both) as ControlSettlementConsumption.Decision.Ready).candidate
        assertEquals("D2B6/6-3B1.08 coexistingCandidateIsTheOracle", x.oracle(both), candidate)
        assertTrue("D2B6/6-3B1.08 coexistingReturnValid",
            ControlSettlementConsumption.validateReturn(ControlRecordReader(codec).read(candidate), x.c, candidate))
        assertFalse("D2B6/6-3B1.08 survivorSealLost", ControlSettlementConsumption.validateReturn(ControlRecordReader(codec).read(
            withSeals(candidate, arr(candidate, sealKey).filter { op(it) != other.c.id })), x.c, candidate))
        assertFalse("D2B6/6-3B1.08 survivorRowLost", ControlSettlementConsumption.validateReturn(ControlRecordReader(codec).read(
            candidate.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()), x.c, candidate))
    }

    // ── T4.6 (pure part): re-encoding past the codec limit ──────────────────────────────────────────────────────
    @Test fun B1_09_survivorThatReencodesPastTheLimitIsTooLarge() {
        val x = Case(r())
        val lone = "\uD800".repeat(12_000)
        val survivor = """{"id":"zz","kind":"NAMESPACE","ownerUid":"$lone","axis":"CAPABILITY","epoch":"k9"}"""
        val record = x.applied.toMutablePreferences().apply {
            this[sealKey] = checkNotNull(this[sealKey]).removeSuffix("]") + "," + survivor + "]"
        }.toPreferences()
        check(checkNotNull(record[sealKey]).toByteArray(Charsets.UTF_8).size <= ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES) { "fixture: decodes" }
        val d = x.decide(record)
        val reason = (d as? ControlSettlementConsumption.Decision.Rejected)?.reason
        assertTrue("D2B6/6-3B1.09: tooLarge $d", reason is RejectionReason.TooLarge && reason.payloadKey == ControlPayloadKey.SEAL &&
            reason.bytes > reason.limit)
    }

    // ── r2: remaining single-field rows (each changes the record and stays wholly interpretable) ─────────────────
    private fun changed(x: Case, record: Preferences, what: String): Preferences {
        check(record != x.applied) { "fixture ${x.k.name} $what: must change the record" }; return record }
    private fun onSeal(p: Preferences, index: Int, change: MutableMap<String, JsonElement>.() -> Unit) =
        withSeals(p, arr(p, sealKey).mapIndexed { i, e -> if (i == index) obj(e, change) else e })
    private fun onWitness(p: Preferences, index: Int, change: MutableMap<String, JsonElement>.() -> Unit) =
        onSeal(p, index) { this["settlement"] = obj(getValue("settlement"), change) }
    private fun ownIndexes(x: Case) = arr(x.applied, sealKey).withIndex().filter { op(it.value) == x.c.id }.map { it.index }

    @Test fun B1_10_rowTransitionKindAndDemandAlone() {
        val rows: List<Pair<Kind, List<Pair<String, (Case) -> Preferences>>>> = listOf(
            r() to listOf(
                "transition=CURRENT_NULL" to { x -> editRow(x.applied, x.c) { this["transition"] = JsonPrimitive("CURRENT_NULL") } },
                "demandNull" to { x -> editRow(x.applied, x.c) { this["demandId"] = kotlinx.serialization.json.JsonNull } }),
            rDeparted() to listOf(
                "transition=RETIRED_NULL" to { x -> editRow(x.applied, x.c) { this["transition"] = JsonPrimitive("RETIRED_NULL") } },
                "demandSetOnNullRow" to { x -> editRow(x.applied, x.c) { this["demandId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000dd") } }))
        for ((k, variants) in rows) for ((what, make) in variants) {
            val x = Case(k)
            assertEquals("D2B6/6-3B1.10 ${k.name} $what", ControlSettlementConsumption.Decision.Conflict, x.decide(changed(x, make(x), what)))
        }
        // An own row of another kind (same commandId/lifetime, MUTATIONS) contradicts the expected Settlement.
        for (k in kinds() + rDeparted()) {
            val x = Case(k)
            val mutationsRow = x.applied.toMutablePreferences().apply {
                this[evidenceKey] = """[{"version":2,"commandId":"${x.c.id}","ownerTrackingLifetimeId":"${x.c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}]"""
            }.toPreferences()
            assertEquals("D2B6/6-3B1.10 ${k.name} kind=MUTATIONS", ControlSettlementConsumption.Decision.Conflict, x.decide(mutationsRow))
        }
    }
    @Test fun B1_11_expectedCommandIdAndSealIdsAlone() {
        for (k in kinds() + rDeparted()) {
            val x = Case(k); val e = x.expected
            assertEquals("D2B6/6-3B1.11 ${k.name} expectedCommandId", ControlSettlementConsumption.Decision.Conflict,
                x.decide(exp = AppliedEvidence.Settlement("00000000-0000-0000-0000-0000000000cc", e.ownerTrackingLifetimeId, e.transition, e.sealIds, e.demandId)))
            val otherIds = if (e.sealIds.size >= 2) e.sealIds.reversed() else e.sealIds + "extra-id"
            assertEquals("D2B6/6-3B1.11 ${k.name} expectedSealIds", ControlSettlementConsumption.Decision.Conflict,
                x.decide(exp = AppliedEvidence.Settlement(e.commandId, e.ownerTrackingLifetimeId, e.transition, otherIds, e.demandId)))
        }
    }
    @Test fun B1_12_eachSealOfTheBundleAndEachRemainingWitnessField() {
        for (k in kinds() + rDeparted()) {
            val x = Case(k)
            for (i in ownIndexes(x)) {
                val sealId = id(arr(x.applied, sealKey)[i])
                assertEquals("D2B6/6-3B1.12 ${k.name} missing[$sealId]", inconsistent,
                    x.decide(changed(x, withSeals(x.applied, arr(x.applied, sealKey).filterIndexed { j, _ -> j != i }), "missing $sealId")))
            }
        }
        // N: every NULL and companion seal — wrong kind, companion immutable epoch, witness after.
        run {
            val x = Case(n(NN.both(), "N-both"))
            for (i in ownIndexes(x)) {
                val seal = arr(x.applied, sealKey)[i]; val sealId = id(seal)
                val flipped = onSeal(x.applied, i) {
                    if (getValue("kind").jsonPrimitive.content == "NAMESPACE") { this["kind"] = JsonPrimitive("NULL_NAMESPACE"); remove("epoch") }
                    else { this["kind"] = JsonPrimitive("NAMESPACE"); this["epoch"] = JsonPrimitive("e9") } }
                assertEquals("D2B6/6-3B1.12 N-both wrongKind[$sealId]", inconsistent, x.decide(changed(x, flipped, "kind $sealId")))
                if ("epoch" in seal.jsonObject) assertEquals("D2B6/6-3B1.12 N-both companionEpoch[$sealId]", inconsistent,
                    x.decide(changed(x, onSeal(x.applied, i) { this["epoch"] = JsonPrimitive("e-other") }, "epoch $sealId")))
                assertEquals("D2B6/6-3B1.12 N-both witnessAfter[$sealId]", inconsistent, x.decide(changed(x,
                    onWitness(x.applied, i) { this["after"] = obj(getValue("after")) { this["userAccessEpoch"] = JsonPrimitive("u-other") } }, "after $sealId")))
            }
        }
        // R: witness after and journal epoch (the schema lets both vary).
        for (k in listOf(r(), rDeparted())) {
            val x = Case(k); val i = ownIndexes(x).single()
            assertEquals("D2B6/6-3B1.12 ${k.name} witnessAfter", inconsistent, x.decide(changed(x,
                onWitness(x.applied, i) { this["after"] = obj(getValue("after")) { this["userAccessEpoch"] = JsonPrimitive("u-other") } }, "after")))
            assertEquals("D2B6/6-3B1.12 ${k.name} witnessJournalEpoch", inconsistent, x.decide(changed(x,
                onWitness(x.applied, i) { this["journal"] = obj(getValue("journal")) {
                    this["epoch"] = if (getValue("epoch") is kotlinx.serialization.json.JsonNull) JsonPrimitive("e-j") else kotlinx.serialization.json.JsonNull } }, "journal epoch")))
        }
    }

    // ── r3 (measurement 6-3B1 r1): rows the r2 inputs walked past ──────────────────────────────────────────────
    @Test fun B1_13_retryWithAnOwnOperationSealOutsideTheFixedIdsIsHeld() {
        for (k in kinds() + rDeparted()) {
            val x = Case(k)
            val absent = x.oracle()
            val own = arr(x.applied, sealKey).first { op(it) == x.c.id }
            val outside = withSeals(absent, arr(absent, sealKey) + obj(own) { this["id"] = JsonPrimitive("outside-own") })
            check(arr(outside, sealKey).none { id(it) in x.expected.sealIds }) { "fixture ${k.name}: no seal at a fixed id" }
            assertEquals("D2B6/6-3B1.13 ${k.name}", inconsistent, x.decide(outside, retry = true))
        }
    }
    @Test fun B1_14_rowAndExpectedMovedTogetherStillDisagreeWithTheFixedInput() {
        // Each change is applied to BOTH the stored row and the expected row, so only the comparison with the fixed
        // input/command sees it.
        val otherLife = "00000000-0000-0000-0000-0000000000ab"
        for (k in kinds() + rDeparted()) {
            val x = Case(k); val e = x.expected
            assertEquals("D2B6/6-3B1.14 ${k.name} lifetime", ControlSettlementConsumption.Decision.Conflict, x.decide(
                changed(x, editRow(x.applied, x.c) { this["ownerTrackingLifetimeId"] = JsonPrimitive(otherLife) }, "lifetime"),
                AppliedEvidence.Settlement(e.commandId, otherLife, e.transition, e.sealIds, e.demandId)))
        }
        val transitions = listOf(r() to HandoverSettlementTransition.CURRENT_NULL, rDeparted() to HandoverSettlementTransition.RETIRED_NULL)
        for ((k, t) in transitions) {
            val x = Case(k); val e = x.expected
            assertEquals("D2B6/6-3B1.14 ${k.name} transition", ControlSettlementConsumption.Decision.Conflict, x.decide(
                changed(x, editRow(x.applied, x.c) { this["transition"] = JsonPrimitive(t.name) }, "transition"),
                AppliedEvidence.Settlement(e.commandId, e.ownerTrackingLifetimeId, t, e.sealIds, e.demandId)))
        }
        val otherDemand = "00000000-0000-0000-0000-0000000000dd"
        for (k in listOf(r(), rDeparted(), n(), n(NN.both(), "N-both"))) {
            val x = Case(k); val e = x.expected
            assertEquals("D2B6/6-3B1.14 ${k.name} demand", ControlSettlementConsumption.Decision.Conflict, x.decide(
                changed(x, editRow(x.applied, x.c) { this["demandId"] = JsonPrimitive(otherDemand) }, "demand"),
                AppliedEvidence.Settlement(e.commandId, e.ownerTrackingLifetimeId, e.transition, e.sealIds, otherDemand)))
        }
    }
}
