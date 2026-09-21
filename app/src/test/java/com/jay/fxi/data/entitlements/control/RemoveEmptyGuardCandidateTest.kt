package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoveEmptyGuardCandidateTest {
    private val survivor = DemandAuthFixtures.request(id = "survivor")

    // Fixed wire facts, independent of decide(), payload() and the Applied encoder.
    private val prior = """{"version":2,"commandId":"prior","ownerTrackingLifetimeId":"11111111-1111-4111-8111-111111111111","kind":"MUTATIONS","targets":[{"index":-0,"kind":"DEMAND","id":"old-r","joined":false,"written":true}]}"""
    private fun ownWire(c: CommandRef) = ControlLifecycleEvidenceFixtures.wire(
        command = c.id, lifetime = c.ownerTrackingLifetimeId.value)
    private fun removal(c: CommandRef, before: Preferences, survivors: List<ControlNode>, previous: String? = null) =
        F.rows(before, ControlKind.DEMAND, survivors).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] =
                if (previous == null) "[${ownWire(c)}]" else "[$previous,${ownWire(c)}]"
        }
    private fun payloadFacts(c: CommandRef, before: Preferences, candidate: Preferences,
        survivors: List<ControlNode>, previous: String? = null) {
        assertNotEquals("prior", c.id)
        val beforeRead = F.read(before)
        assertEquals(2, beforeRead.schemaVersion)
        val removed = beforeRead.locations("g").single()
        assertEquals(ControlKind.DEMAND, removed.first)
        assertEquals(F.empty.toPayloadEntry(), (removed.second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val priorEvidence = Json.parseToJsonElement(before[ControlLifecycleEvidenceFixtures.evidenceKey]!!) as JsonArray
        assertTrue(priorEvidence.none { it.jsonObject["commandId"] == JsonPrimitive(c.id) })
        val after = F.read(candidate)
        assertEquals(2, after.schemaVersion)
        assertFalse(after.hasUninterpretable)
        assertFalse(after.hasUninterpretableMetadata)
        assertTrue(after.locations("g").isEmpty())
        assertEquals(survivors.map { it.toPayloadEntry() },
            after.arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original.toPayloadEntry() })
        val evidence = Json.parseToJsonElement(candidate[ControlLifecycleEvidenceFixtures.evidenceKey]!!) as JsonArray
        assertEquals(if (previous == null) 1 else 2, evidence.size)
        assertEquals(Json.parseToJsonElement(ownWire(c)), evidence.last())
        if (previous != null) assertEquals(Json.parseToJsonElement(previous), evidence.first())
        // All other payloads, schema, fence keys and external bytes must remain exact.
        assertEquals(before, candidate.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = before[ControlRecordKeys.payload(ControlKind.DEMAND)]!!
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = before[ControlLifecycleEvidenceFixtures.evidenceKey]!!
        })
    }

    @Test fun EG_C02payload() {
        val c = F.command()
        val strong = F.field(survivor, "intent", JsonPrimitive("FORCE_PREMIUM"))
        val weak = F.field(strong, "intent", JsonPrimitive("IF_STALE"))
        val before = F.raw(F.empty, strong, hold = F.hold())
        val candidate = removal(c, before, listOf(weak))
        assertFalse(F.read(before).hasUninterpretable)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals(JsonPrimitive("FORCE_PREMIUM"), strong.toPayloadEntry().fields["intent"])
        assertEquals(JsonPrimitive("IF_STALE"), weak.toPayloadEntry().fields["intent"])
        assertEquals(strong.toPayloadEntry().fields - "intent", weak.toPayloadEntry().fields - "intent")
        payloadFacts(c, before, candidate, listOf(weak))
        assertFalse(F.atomic("EG_C02payload"), F.transition.validCandidate(c, "g", F.read(before), candidate))
    }

    @Test fun EG_C02opaquePayload() {
        val c = F.command()
        val opaque = F.field(survivor, "future", JsonPrimitive(true))
        val before = F.raw(F.empty, opaque)
        val candidate = removal(c, before, listOf(survivor))
        val read = F.read(before)
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretableMetadata)
        assertEquals(listOf(ControlKind.DEMAND), read.arrays.filterValues { it.hasUninterpretable }.keys.toList())
        assertEquals(1, read.arrays.getValue(ControlKind.DEMAND).entries.count { it is ControlEntryRead.Uninterpretable })
        assertEquals(survivor.toPayloadEntry().fields, opaque.toPayloadEntry().fields - "future")
        payloadFacts(c, before, candidate, listOf(survivor))
        // Direct candidate boundary: Store admission rejects the opaque obligation first.
        assertFalse(F.atomic("EG_C02opaquePayload"), F.transition.validCandidate(c, "g", read, candidate))
    }

    @Test fun EG_C10payload() {
        val c = F.command()
        val normalized = prior.replace("\"index\":-0", "\"index\":0")
        val before = F.raw(F.empty).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$prior]"
        }
        val candidate = removal(c, before, emptyList(), normalized)
        val read = F.read(before)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        assertNotEquals(prior, normalized)
        assertEquals(prior, normalized.replace("\"index\":0", "\"index\":-0"))
        val oldEntry = (read.metadata as ControlMetadataRead.V2).evidence.entries.single() as ControlEvidenceEntryRead.Interpreted
        val newEntry = (F.read(candidate).metadata as ControlMetadataRead.V2).evidence.entries.first() as ControlEvidenceEntryRead.Interpreted
        // Parsed values agree; the required distinction is the preserved numeric literal.
        val oldValue = oldEntry.value as AppliedEvidence.Mutations
        val newValue = newEntry.value as AppliedEvidence.Mutations
        assertEquals("prior", oldValue.commandId)
        assertEquals(oldValue.commandId, newValue.commandId)
        assertEquals("11111111-1111-4111-8111-111111111111", oldValue.ownerTrackingLifetimeId)
        assertEquals(oldValue.ownerTrackingLifetimeId, newValue.ownerTrackingLifetimeId)
        val expectedTargets = listOf(AppliedTarget(0, ControlKind.DEMAND, "old-r", joined = false, written = true))
        assertEquals(expectedTargets, oldValue.targets)
        assertEquals(expectedTargets, newValue.targets)
        assertNotEquals(oldEntry.original.toPayloadEntry(), newEntry.original.toPayloadEntry())
        payloadFacts(c, before, candidate, emptyList(), normalized)
        assertFalse(F.atomic("EG_C10payload"), F.transition.validCandidate(c, "g", read, candidate))
    }

    @Test fun EG_C10opaquePayload() {
        val c = F.command()
        val ordinary = prior.replace("\"index\":-0", "\"index\":0")
        val opaque = JsonObject((Json.parseToJsonElement(ordinary) as JsonObject) + ("future" to JsonPrimitive(true)))
        val before = F.raw(F.empty).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$opaque]"
        }
        val candidate = removal(c, before, emptyList(), ordinary)
        val read = F.read(before)
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertTrue(read.hasUninterpretableMetadata)
        assertEquals(1, (read.metadata as ControlMetadataRead.V2).evidence.entries.count { it is ControlEvidenceEntryRead.Uninterpretable })
        assertEquals(Json.parseToJsonElement(ordinary), JsonObject(opaque - "future"))
        payloadFacts(c, before, candidate, emptyList(), ordinary)
        // Direct candidate boundary: Store admission rejects opaque metadata first.
        assertFalse(F.atomic("EG_C10opaquePayload"), F.transition.validCandidate(c, "g", read, candidate))
    }

    private fun reject(id: String, change: (Preferences, Preferences) -> Preferences) {
        val c = F.command(); val before = F.raw(F.empty, survivor, hold = F.hold())
        val valid = F.candidate(c, before); val bad = change(before, valid)
        // Independent dimension oracle. Only one of removal, survivor, external, evidence is wrong.
        val read = F.read(bad)
        assertFalse(read.hasUninterpretable); assertFalse(read.hasUninterpretableMetadata)
        val demand = read.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
        val external = bad.toMutablePreferences().apply {
            remove(ControlRecordKeys.payload(ControlKind.DEMAND)); remove(ControlLifecycleEvidenceFixtures.evidenceKey)
        }
        val oldExternal = before.toMutablePreferences().apply {
            remove(ControlRecordKeys.payload(ControlKind.DEMAND)); remove(ControlLifecycleEvidenceFixtures.evidenceKey)
        }
        val fixedEvidence = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        val facts = listOf(demand.none { it.value.id == "g" },
            demand.filter { it.value.id != "g" }.map { it.original.toPayloadEntry().fields.toString() } == listOf(survivor.toPayloadEntry().fields.toString()),
            external == oldExternal, Json.parseToJsonElement(bad[ControlLifecycleEvidenceFixtures.evidenceKey]!!) == Json.parseToJsonElement(fixedEvidence))
        assertEquals("one candidate contract only", 1, facts.count { !it })
        assertFalse(F.atomic(id), F.transition.validCandidate(c, "g", F.read(before), bad))
    }
    @Test fun C01() = reject("EG_C01") { before, valid -> F.rows(valid, ControlKind.DEMAND, listOf(F.empty, survivor)) }
    @Test fun C02() = reject("EG_C02") { _, valid -> F.rows(valid, ControlKind.DEMAND, emptyList()) }
    @Test fun C11_payload() = reject("EG_C11payload") { _, valid -> valid.toMutablePreferences().apply {
        this[ControlRecordKeys.payload(ControlKind.HOLD)] = " [ ${ControlObligationFixtures.hold} ] "
    } }
    @Test fun C11_bytes() = reject("EG_C11bytes") { _, valid -> valid.toMutablePreferences().apply {
        this[byteArrayPreferencesKey("lifecycle-external")] = byteArrayOf(1, 1, -1)
    } }
    private fun evidence(id: String, change: (JsonObject) -> JsonObject?) = reject(id) { _, valid ->
        valid.toMutablePreferences().apply {
            val key = ControlLifecycleEvidenceFixtures.evidenceKey
            val old = (Json.parseToJsonElement(this[key]!!) as JsonArray).single() as JsonObject
            this[key] = change(old)?.let { "[$it]" } ?: "[]"
        }
    }
    @Test fun C10_absent() = evidence("EG_C10absent") { null }
    @Test fun C10_transition() = evidence("EG_C10transition") { JsonObject(it + ("transition" to JsonPrimitive("SETTLE_QUERY"))) }
    @Test fun C10_effect() {
        val c = F.command()
        val expected = ControlAppliedEvidence.node(AppliedEvidence.Lifecycle(c.id, c.ownerTrackingLifetimeId.value,
            LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE))))
        val target = (expected["targets"] as JsonArray).single().jsonObject
        val altered = JsonObject(expected + ("targets" to JsonArray(listOf(JsonObject(target + ("effect" to JsonPrimitive("REPLACE")))))))
        assertEquals(JsonObject(expected - "targets"), JsonObject(altered - "targets"))
        assertEquals(JsonObject(target - "effect"), JsonObject((altered["targets"] as JsonArray).single().jsonObject - "effect"))
        // Direct exact-evidence boundary: the parser rejects this shape before a full candidate.
        assertFalse(F.atomic("EG_C10effect"), F.transition.validEvidence(c, "g", emptyList(), listOf(PayloadEntry.Obj(altered).toString())))
    }
    @Test fun C10_priorOrder() {
        val c = F.command()
        val own = PayloadEntry.Obj(ControlAppliedEvidence.node(ControlLifecycleEvidenceFixtures.row(c))).toString()
        assertFalse(F.atomic("EG_C10order"), F.transition.validEvidence(c, "g", listOf("first", "second"), listOf("second", "first", own)))
    }
    @Test fun C10_id() = evidence("EG_C10id") { JsonObject(it + ("commandId" to JsonPrimitive("other"))) }
    @Test fun C10_lifetime() = evidence("EG_C10lifetime") { JsonObject(it + ("ownerTrackingLifetimeId" to JsonPrimitive("11111111-1111-4111-8111-111111111111"))) }
    @Test fun C12_schema() {
        val c = F.command()
        val before = F.raw(F.empty).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[{\"future\":true}]" }
        val after = F.rows(before, ControlKind.DEMAND, emptyList()).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        }
        // Direct final-schema boundary: opaque HOLD is unchanged, all intended effects are exact.
        assertTrue(F.read(after).hasUninterpretable)
        assertFalse(F.read(after).hasUninterpretableMetadata)
        assertFalse(F.atomic("EG_C12schema"), F.transition.validCandidate(c, "g", F.read(before), after))
    }
    @Test fun successfulCandidateUsesOneAppliedAndPreservesRawSiblings() {
        val c = F.command(); val raw = F.raw(F.empty, survivor, hold = F.hold())
        val after = F.candidate(c, raw)
        assertEquals("[${ControlLifecycleEvidenceFixtures.wire(c)}]", after[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertEquals(listOf("survivor"), F.read(after).arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>().map { it.value.id })
        assertArrayEquals(raw[byteArrayPreferencesKey("lifecycle-external")], after[byteArrayPreferencesKey("lifecycle-external")])
    }

    @Test fun C12_unreadableCandidate() {
        val c = F.command(); val before = F.raw(F.empty)
        // Assemble from the fixed before record and required effects, independently of the writer.
        val valid = F.rows(before, ControlKind.DEMAND, emptyList()).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        }
        val key = ControlRecordKeys.payload(ControlKind.DEMAND)
        assertEquals("[]", valid[key])
        assertEquals("[${ControlLifecycleEvidenceFixtures.wire(c)}]", valid[ControlLifecycleEvidenceFixtures.evidenceKey])
        val bad = valid.toMutablePreferences().apply { this[key] = "[" }
        assertEquals(valid, bad.toMutablePreferences().apply { this[key] = "[]" })
        val read = ControlRecordReader(F.codec).read(bad)
        assertTrue(read is ControlRecordRead.Unreadable)
        val problems = (read as ControlRecordRead.Unreadable).problems
        assertEquals(1, problems.size)
        assertEquals(ControlPayloadKey.DEMAND, (problems.single() as ControlRecordProblem.UnreadablePayload).payloadKey)
        // Direct boundary input: a single missing closing bracket, without fabricating a Supported record.
        assertFalse(F.atomic("EG_C12read"), F.transition.validCandidate(c, "g", F.read(before), bad))
    }

    @Test fun C12_schemaCall() {
        val c = F.command()
        val before = F.raw(F.empty).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[{\"future\":true}]"
        }
        val after = F.rows(before, ControlKind.DEMAND, emptyList()).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        }
        val read = F.read(after)
        assertEquals(2, read.schemaVersion)
        assertTrue(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        // Unlike the existing callee mutation, this target must leave the helper's verdict intact.
        assertEquals(RecoveryReason.UninterpretableObligations, ControlLifecycleBoundary.recordProblem(read))
        assertTrue(read.arrays.getValue(ControlKind.DEMAND).entries.isEmpty())
        assertEquals(before, after.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = before[ControlRecordKeys.payload(ControlKind.DEMAND)]!!
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]"
        })
        assertEquals("[${ControlLifecycleEvidenceFixtures.wire(c)}]", after[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertFalse(F.atomic("EG_C12schemaCall"), F.transition.validCandidate(c, "g", F.read(before), after))
    }

    @Test fun C10_evidenceCall() {
        val c = F.command(); val before = F.raw(F.empty, survivor)
        val valid = F.rows(before, ControlKind.DEMAND, listOf(survivor)).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        }
        assertEquals("[]", before[ControlLifecycleEvidenceFixtures.evidenceKey])
        val bad = valid.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }
        val read = F.read(bad)
        assertNull(ControlLifecycleBoundary.recordProblem(read))
        assertTrue(read.locations("g").isEmpty())
        assertEquals(survivor.toPayloadEntry(), (read.locations("survivor").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        assertEquals(valid, bad.toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        })
        // The callee still rejects; the mutation ignores that verdict only at the invocation site.
        assertFalse(F.transition.validEvidence(c, "g", emptyList(), emptyList()))
        assertFalse(F.atomic("EG_C10call"), F.transition.validCandidate(c, "g", F.read(before), bad))
    }

    @Test fun EG_candidateCall() {
        val c = F.command()
        val input = (c.body as ControlCommandBody.Lifecycle).input
        val target = input.targets.single()
        assertEquals(LifecycleTransition.REMOVE_EMPTY_GUARD, input.transition)
        assertEquals(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE), target.target)
        val expectedGuard = checkNotNull(target.before)
        assertNotNull(guard(expectedGuard))
        val fields = expectedGuard.toPayloadEntry().fields
        assertFalse(fields.containsKey("floor"))
        assertFalse(fields.containsKey("auth"))
        val before = F.raw(F.empty).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[{\"future\":true}]"
        }
        val read = F.read(before)
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretableMetadata)
        assertEquals(listOf(ControlKind.HOLD), read.arrays.filterValues { it.hasUninterpretable }.keys.toList())
        assertEquals(1, read.arrays.getValue(ControlKind.DEMAND).entries.size)
        assertEquals(expectedGuard.toPayloadEntry(), (read.locations("g").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        assertTrue((read.metadata as ControlMetadataRead.V2).evidence.entries.isEmpty())
        // Construct the exact removal + Applied candidate from fixed input facts, without decide.
        val expectedEvidence = "[${ControlLifecycleEvidenceFixtures.wire(c)}]"
        val candidate = F.rows(before, ControlKind.DEMAND, emptyList()).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = expectedEvidence
        }
        val after = F.read(candidate)
        assertEquals(2, after.schemaVersion)
        assertFalse(after.hasUninterpretableMetadata)
        assertEquals(listOf(ControlKind.HOLD), after.arrays.filterValues { it.hasUninterpretable }.keys.toList())
        assertTrue(after.locations("g").isEmpty())
        assertTrue(after.arrays.getValue(ControlKind.DEMAND).entries.isEmpty())
        assertEquals(expectedEvidence, candidate[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertEquals(before, candidate.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = before[ControlRecordKeys.payload(ControlKind.DEMAND)]!!
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]"
        })
        assertEquals(RecoveryReason.UninterpretableObligations, ControlLifecycleBoundary.recordProblem(after))
        assertFalse(F.transition.validCandidate(c, "g", read, candidate))
        // Direct call boundary only: the Store path rejects this record in ControlLifecycle first.
        val decision = F.transition.decide(c, input, read)
        assertFalse(F.atomic("EG_candidateCall"), decision is RecordTransactionDecision.Confirm)
        assertTrue(decision is RecordTransactionDecision.Observe)
    }
}
