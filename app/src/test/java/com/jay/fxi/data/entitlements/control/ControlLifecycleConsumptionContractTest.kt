package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4aA contract: the pure current-lifetime Lifecycle consumption decider (6-4a skeleton consensus r2 §1,
 * T2–T3; 6-4aA API consensus). API fixed by this contract:
 *   internal object ControlLifecycleConsumption {
 *     sealed interface Decision { Ready(candidate: Preferences); Recovery(reason: RecoveryReason); object Conflict;
 *       Rejected(reason: RejectionReason) }
 *     fun decide(read: ControlRecordRead.Supported, command: CommandRef, input: ControlLifecycleDescriptor,
 *       expected: AppliedEvidence.Lifecycle?, retry: Boolean, codec: ControlPayloadCodec): Decision
 *     fun validateReturn(returned: ControlRecordRead, command: CommandRef, candidate: Preferences): Boolean
 *   }
 *   RecoveryReason.ExpectedLifecycleEvidenceUnavailable.
 * Precondition: a wholly interpretable schema-2 read (schema/opaque are the owner's earlier checks, 6-4aB).
 * Order: (1) expected null → ExpectedLifecycleEvidenceUnavailable (also on retry). (2) the fixed input and expected must
 * agree — input.operationId == command.id, expected.commandId == command.id, expected lifetime == the command's,
 * ControlLifecycleEvidence.matches(input, expected) (transition and ordered kind/id/effect) — else Conflict. (3) own row
 * found by commandId: absent on first entry → CommandEvidenceLost; on retry → Ready(read.original) (one row, no partial).
 * (4) own not a Lifecycle row, or ControlAppliedEvidence.node(own) != node(expected) → Conflict. (5) the candidate removes
 * exactly the own row; every other row (original text and order) and every other key survive; re-encoding past the codec
 * limit → Rejected(TooLarge). Current business postconditions (targets, requiredUnchanged, namespace) are never read: the
 * rows' DEMAND/HOLD/RECOVERY_INTENT targets need not exist. Records are literal schema-2 rows — this decider depends only
 * on the evidence rows; the owner contract (6-4aB) uses the real writers. The implementation thread reads but does not edit
 * this file.
 */
class ControlLifecycleConsumptionContractTest {
    private val codec = ControlPayloadCodec()
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val extraKey = stringPreferencesKey("lifecycle-external-text")
    private fun t(kind: ControlKind, id: String, effect: LifecycleEffect) = LifecycleTarget(kind, id, effect)
    private val D = ControlKind.DEMAND
    private val REMOVE = LifecycleEffect.REMOVE; private val REPLACE = LifecycleEffect.REPLACE; private val CREATE = LifecycleEffect.CREATE

    /** One valid wire shape per transition (ControlLifecycleEvidence.validShape), several targets where the shape allows. */
    private val shapes: Map<LifecycleTransition, List<LifecycleTarget>> = linkedMapOf(
        LifecycleTransition.REBIND_REQUESTS to listOf(t(D, "d1", REPLACE), t(D, "d2", REPLACE)),
        LifecycleTransition.SETTLE_QUERY to listOf(t(D, "q", REMOVE), t(D, "r", CREATE)),
        LifecycleTransition.UPDATE_AUTH to listOf(t(D, "g", REPLACE), t(D, "d", CREATE)),
        LifecycleTransition.END_AUTH_BINDING to listOf(t(D, "g", REPLACE), t(D, "d", REPLACE)),
        LifecycleTransition.REMOVE_EMPTY_GUARD to listOf(t(D, "g", REMOVE)),
        LifecycleTransition.RECOVER_HOLD to listOf(t(ControlKind.HOLD, "h", REMOVE), t(D, "d", CREATE), t(D, "g", REPLACE)),
        LifecycleTransition.RECOVER_INTENT to listOf(t(ControlKind.RECOVERY_INTENT, "i", REMOVE), t(D, "d", CREATE))
    )

    private fun wire(id: String, lifetime: String, transition: LifecycleTransition, targets: List<LifecycleTarget>) =
        ControlLifecycleEvidenceFixtures.wire(transition.name,
            targets.joinToString(",") { ControlLifecycleEvidenceFixtures.target(it.kind.name, it.id, it.effect.name) }, id, lifetime)
    private fun descriptor(op: String, transition: LifecycleTransition, targets: List<LifecycleTarget>) =
        ControlLifecycleDescriptor(op, transition, targets.map { LifecycleFixedTarget(it, LifecycleRole.REQUEST, null, null) })

    /** One applied case: the command (current lifetime), its fixed descriptor, the record and the expected row. */
    private inner class Case(val transition: LifecycleTransition, val targets: List<LifecycleTarget> = checkNotNull(shapes[transition])) {
        val lifetime = OwnerTrackingLifetimeId.issue()
        val input = descriptor("lc-${transition.name.lowercase()}", transition, targets)
        val c = CommandRef(input.operationId, ControlCommandBody.Lifecycle(input), lifetime)
        val ownRow = wire(c.id, lifetime.value, transition, targets)
        // Survivors: an earlier-lifetime Lifecycle row and a current-lifetime Mutations row, before and after the own row.
        val foreignLifecycle = wire("lc-foreign", OwnerTrackingLifetimeId.issue().value, LifecycleTransition.REMOVE_EMPTY_GUARD,
            listOf(t(D, "foreign-guard", REMOVE)))
        val foreignMutations = """{"version":2,"commandId":"m-live","ownerTrackingLifetimeId":"${lifetime.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"x","joined":false,"written":true}]}"""
        val record: Preferences = ControlLifecycleEvidenceFixtures.raw(demand = "[${ControlObligationFixtures.request}]",
            evidence = "[$foreignLifecycle,$ownRow,$foreignMutations]").toMutablePreferences().apply {
            this[extraKey] = "  keep me exactly  "
        }.toPreferences()
        val expected = ControlAppliedEvidence.own(read(record), c) as AppliedEvidence.Lifecycle
        fun decide(p: Preferences = record, exp: AppliedEvidence.Lifecycle? = expected, retry: Boolean = false,
            command: CommandRef = c, fixed: ControlLifecycleDescriptor = input) =
            ControlLifecycleConsumption.decide(interpretable(p), command, fixed, exp, retry, codec)
        /** Independent oracle: [p] minus exactly the own row. */
        fun oracle(p: Preferences = record): Preferences = p.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(p, evidenceKey).filter { cmd(it) != c.id }).toString()
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
    private fun cmd(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))
    private fun editOwn(x: Case, change: MutableMap<String, JsonElement>.() -> Unit): Preferences = x.record.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(x.record, evidenceKey).map { if (cmd(it) == x.c.id) obj(it, change) else it }).toString()
    }.toPreferences().also { check(it != x.record) { "fixture: own row edit" } }
    private fun targetsJson(targets: List<LifecycleTarget>) = JsonArray(targets.map {
        Json.parseToJsonElement(ControlLifecycleEvidenceFixtures.target(it.kind.name, it.id, it.effect.name)) })
    private fun recovery(reason: RecoveryReason) = ControlLifecycleConsumption.Decision.Recovery(reason)
    private val conflict = ControlLifecycleConsumption.Decision.Conflict

    // ── T3.1: each transition's own row is removed alone; the return validator accepts exactly that ─────────────
    @Test fun A_01_eachTransitionRemovesExactlyTheOwnRow() {
        for (transition in LifecycleTransition.entries) {
            val x = Case(transition)
            val d = x.decide()
            assertTrue("D2B6/6-4aA.01 $transition: ready $d", d is ControlLifecycleConsumption.Decision.Ready)
            val candidate = (d as ControlLifecycleConsumption.Decision.Ready).candidate
            assertEquals("D2B6/6-4aA.01 $transition: exactDeletion", x.oracle(), candidate)
            assertEquals("D2B6/6-4aA.01 $transition: survivorsInOrder", listOf("lc-foreign", "m-live"), arr(candidate, evidenceKey).map { cmd(it) })
            assertTrue("D2B6/6-4aA.01 $transition: returnValid",
                ControlLifecycleConsumption.validateReturn(ControlRecordReader(codec).read(candidate), x.c, candidate))
        }
    }

    // ── T2.1: expected null is held on first entry and on retry ─────────────────────────────────────────────────
    @Test fun A_02_expectedNullIsHeld() {
        for (transition in LifecycleTransition.entries) for (retry in listOf(false, true)) {
            assertEquals("D2B6/6-4aA.02 $transition retry=$retry", recovery(RecoveryReason.ExpectedLifecycleEvidenceUnavailable),
                Case(transition).decide(exp = null, retry = retry))
        }
    }

    // ── T2 (fixed input ↔ expected): each disagreement alone is a Conflict ──────────────────────────────────────
    @Test fun A_03_fixedInputAndExpectedMustAgree() {
        val x = Case(LifecycleTransition.RECOVER_HOLD)
        val e = x.expected
        fun exp(id: String = e.commandId, life: String = e.ownerTrackingLifetimeId, transition: LifecycleTransition = e.transition,
            targets: List<LifecycleTarget> = e.targets) = AppliedEvidence.Lifecycle(id, life, transition, targets)
        val ts = e.targets
        val cases = listOf(
            "inputOperation" to x.decide(fixed = descriptor("other-op", x.transition, x.targets)),
            "expectedCommand" to x.decide(exp = exp(id = "other-command")),
            "expectedLifetime" to x.decide(exp = exp(life = OwnerTrackingLifetimeId.issue().value)),
            "expectedTransition" to x.decide(exp = exp(transition = LifecycleTransition.RECOVER_INTENT)),
            "expectedTargetKind" to x.decide(exp = exp(targets = listOf(ts[0], t(ControlKind.HOLD, ts[1].id, ts[1].effect), ts[2]))),
            "expectedTargetId" to x.decide(exp = exp(targets = listOf(ts[0], t(D, "other", ts[1].effect), ts[2]))),
            "expectedTargetEffect" to x.decide(exp = exp(targets = listOf(ts[0], t(D, ts[1].id, REPLACE), ts[2]))),
            "expectedTargetOrder" to x.decide(exp = exp(targets = listOf(ts[0], ts[2], ts[1]))),
            "expectedTargetCount" to x.decide(exp = exp(targets = ts.dropLast(1))))
        for ((name, d) in cases) assertEquals("D2B6/6-4aA.03 $name", conflict, d)
    }

    // ── T2.2: own absence ─────────────────────────────────────────────────────────────────────────────────────
    @Test fun A_04_ownAbsenceIsLostOnFirstEntryAndAbsentOnRetry() {
        for (transition in LifecycleTransition.entries) {
            val x = Case(transition)
            val absent = x.oracle()
            assertEquals("D2B6/6-4aA.04 $transition first", recovery(RecoveryReason.CommandEvidenceLost), x.decide(absent))
            val read = interpretable(absent)
            val d = ControlLifecycleConsumption.decide(read, x.c, x.input, x.expected, true, codec)
            assertTrue("D2B6/6-4aA.04 $transition retry: $d", d is ControlLifecycleConsumption.Decision.Ready)
            assertSame("D2B6/6-4aA.04 $transition retryIsTheReadItself", read.original, (d as ControlLifecycleConsumption.Decision.Ready).candidate)
        }
    }

    // ── T2.3: the own row must be this Lifecycle row exactly (single valid-shape changes) ────────────────────────
    @Test fun A_05_ownRowMustEqualTheExpectedRow() {
        run { // Same commandId, but a Mutations row.
            val x = Case(LifecycleTransition.SETTLE_QUERY)
            val mutationsOwn = editOwn(x) {
                remove("transition"); this["kind"] = JsonPrimitive("MUTATIONS")
                this["targets"] = Json.parseToJsonElement("""[{"index":0,"kind":"DEMAND","id":"q","joined":false,"written":true}]""")
            }
            assertEquals("D2B6/6-4aA.05 notLifecycle", conflict, x.decide(mutationsOwn))
        }
        val x = Case(LifecycleTransition.SETTLE_QUERY)
        val cases = listOf(
            // Effect only: the SETTLE_QUERY suffix allows both CREATE and REPLACE.
            "effect" to editOwn(x) { this["targets"] = targetsJson(listOf(t(D, "q", REMOVE), t(D, "r", REPLACE))) },
            "id" to editOwn(x) { this["targets"] = targetsJson(listOf(t(D, "q", REMOVE), t(D, "r2", CREATE))) },
            "count" to editOwn(x) { this["targets"] = targetsJson(listOf(t(D, "r", CREATE))) },
            "transition" to editOwn(x) { this["transition"] = JsonPrimitive("REBIND_REQUESTS"); this["targets"] = targetsJson(listOf(t(D, "q", REPLACE))) },
            "lifetime" to editOwn(x) { this["ownerTrackingLifetimeId"] = JsonPrimitive(OwnerTrackingLifetimeId.issue().value) })
        for ((name, record) in cases) assertEquals("D2B6/6-4aA.05 $name", conflict, x.decide(record))
        val r = Case(LifecycleTransition.REBIND_REQUESTS)
        val order = editOwn(r) { this["targets"] = targetsJson(listOf(t(D, "d2", REPLACE), t(D, "d1", REPLACE))) }
        assertEquals("D2B6/6-4aA.05 order", conflict, r.decide(order))
    }

    // ── T2.6: current business postconditions are never required ────────────────────────────────────────────────
    @Test fun A_06_currentTargetsAndPayloadsDoNotMatter() {
        for (transition in LifecycleTransition.entries) {
            val x = Case(transition)
            val changed = x.record.toMutablePreferences().apply {
                this[demandKey] = "[]"
                this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
                this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[]"
            }.toPreferences()
            val d = x.decide(changed)
            assertTrue("D2B6/6-4aA.06 $transition: ready $d", d is ControlLifecycleConsumption.Decision.Ready)
            assertEquals("D2B6/6-4aA.06 $transition: exactDeletion", x.oracle(changed), (d as ControlLifecycleConsumption.Decision.Ready).candidate)
        }
    }

    // ── T3 (pure part): re-encoding past the codec limit ──────────────────────────────────────────────────────
    @Test fun A_07_survivorThatReencodesPastTheLimitIsTooLarge() {
        val x = Case(LifecycleTransition.REMOVE_EMPTY_GUARD)
        val lone = "\uD800".repeat(12_000)
        val survivor = wire("lc-large", OwnerTrackingLifetimeId.issue().value, LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(t(D, lone, REMOVE)))
        val record = x.record.toMutablePreferences().apply {
            this[evidenceKey] = checkNotNull(this[evidenceKey]).removeSuffix("]") + "," + survivor + "]"
        }.toPreferences()
        check(checkNotNull(record[evidenceKey]).toByteArray(Charsets.UTF_8).size <= ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES) { "fixture: decodes" }
        val d = x.decide(record)
        val reason = (d as? ControlLifecycleConsumption.Decision.Rejected)?.reason
        assertTrue("D2B6/6-4aA.07: tooLarge $d", reason is RejectionReason.TooLarge &&
            reason.payloadKey == ControlPayloadKey.COMMAND_EVIDENCE && reason.bytes > reason.limit)
    }

    // ── T3.3: the return validator rejects each divergence alone ──────────────────────────────────────────────
    @Test fun A_08_theReturnValidatorRejectsEachDivergence() {
        val x = Case(LifecycleTransition.RECOVER_INTENT)
        val candidate = (x.decide() as ControlLifecycleConsumption.Decision.Ready).candidate
        fun rejected(name: String, returned: Preferences) = assertFalse("D2B6/6-4aA.08 $name",
            ControlLifecycleConsumption.validateReturn(ControlRecordReader(codec).read(returned), x.c, candidate))
        rejected("ownBack", x.record)
        rejected("survivorDropped", candidate.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(candidate, evidenceKey).filter { cmd(it) != "m-live" }).toString() }.toPreferences())
        rejected("otherKey", candidate.toMutablePreferences().apply { this[extraKey] = "changed" }.toPreferences())
        rejected("otherPayload", candidate.toMutablePreferences().apply { this[demandKey] = "[]" }.toPreferences())
        rejected("unsupported", candidate.toMutablePreferences().apply { this[ControlStoreTestStorage.SCHEMA] = 1 }.toPreferences())
        // The read barrier alone is not a divergence.
        assertTrue("D2B6/6-4aA.08 barrierOnly", ControlLifecycleConsumption.validateReturn(ControlRecordReader(codec).read(
            candidate.toMutablePreferences().apply { this[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.READ_BARRIER] = 7L }.toPreferences()),
            x.c, candidate))
    }

    // ── r2 (measurement: guards shadowed by the node comparison) — the row agrees with expected, the fixed input does not ─
    @Test fun A_09_eachFixedInputGuardAloneWhenRowAndExpectedAgree() {
        val x = Case(LifecycleTransition.RECOVER_HOLD)
        // expected.commandId ≠ command, the row moved with it: unguarded, the row is not found by command id (Lost).
        run {
            val record = editOwn(x) { this["commandId"] = JsonPrimitive("other-command") }
            val exp = AppliedEvidence.Lifecycle("other-command", x.expected.ownerTrackingLifetimeId, x.expected.transition, x.expected.targets)
            assertEquals("D2B6/6-4aA.09 expectedCommand", conflict, x.decide(record, exp))
        }
        // expected lifetime ≠ the command's, the row moved with it: unguarded, the row equals expected and would be deleted.
        run {
            val other = OwnerTrackingLifetimeId.issue().value
            val record = editOwn(x) { this["ownerTrackingLifetimeId"] = JsonPrimitive(other) }
            val exp = AppliedEvidence.Lifecycle(x.expected.commandId, other, x.expected.transition, x.expected.targets)
            assertEquals("D2B6/6-4aA.09 expectedLifetime", conflict, x.decide(record, exp))
        }
        // The fixed descriptor differs from both (row == expected): transition, effect, id, order and count alone.
        val ts = x.targets
        val fixedCases = listOf(
            "descriptorTransition" to descriptor(x.input.operationId, LifecycleTransition.RECOVER_INTENT, ts),
            "descriptorEffect" to descriptor(x.input.operationId, x.transition, listOf(ts[0], t(D, ts[1].id, REPLACE), ts[2])),
            "descriptorId" to descriptor(x.input.operationId, x.transition, listOf(ts[0], t(D, "other", ts[1].effect), ts[2])),
            "descriptorOrder" to descriptor(x.input.operationId, x.transition, listOf(ts[0], ts[2], ts[1])),
            "descriptorCount" to descriptor(x.input.operationId, x.transition, ts.dropLast(1)))
        for ((name, fixed) in fixedCases) assertEquals("D2B6/6-4aA.09 $name", conflict, x.decide(fixed = fixed))
    }
}
