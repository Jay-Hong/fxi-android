package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Literal wire oracles: no production witness, candidate builder, or evidence encoder. */
internal object HandoverFormatFixtures {
    const val fence = """{"ownerUid":"B","userAccessEpoch":"u2","krxCapabilityEpoch":"k2"}"""
    const val lWitness = """{"version":2,"kind":"RETIRED_NULL","operationId":"lop","originLifetimeId":"origin","before":$fence,"after":$fence,"journal":{"ownerUid":"A","axis":"USER","epoch":null}}"""
    const val lSeal = """{"id":"ls","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER","settlement":$lWitness}"""
    const val rSeal = """{"id":"rs","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"old","settlement":{"operationId":"rop","originLifetimeId":"origin","operation":"JOURNAL_RETIRED","before":$fence,"after":$fence,"journal":{"ownerUid":"A","axis":"USER","epoch":"old"}}}"""
    const val nSeal = """{"id":"ns","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER","settlement":{"operationId":"nop","originLifetimeId":"origin","operation":"BEGIN_ROTATION","before":{"ownerUid":"A","userAccessEpoch":"u1","krxCapabilityEpoch":"k"},"after":{"ownerUid":"A","userAccessEpoch":"next","krxCapabilityEpoch":"k"},"journal":{"ownerUid":"A","axis":"USER","epoch":null}}}"""

    val nCompanion = nSeal.replace("\"id\":\"ns\"", "\"id\":\"nc\"")
        .replace("\"kind\":\"NULL_NAMESPACE\"", "\"kind\":\"NAMESPACE\"")
        .replace("\"axis\":\"USER\",\"settlement\"", "\"axis\":\"USER\",\"epoch\":\"u1\",\"settlement\"")

    fun applied(transition: String = "RETIRED_NULL", seals: String = "[\"ls\"]", demand: String = "null",
        command: String = "lop", lifetime: String = ReclamationFixtures.oldLife): String =
        """{"version":2,"commandId":"$command","ownerTrackingLifetimeId":"$lifetime","kind":"SETTLEMENT","transition":"$transition","sealIds":$seals,"demandId":$demand}"""

    fun change(raw: String, path: String, value: JsonElement?): String {
        val parts = path.split('.')
        return ControlObligationFixtures.change(Json.parseToJsonElement(raw) as JsonObject, parts.dropLast(1)) {
            JsonObject(if (value == null) it - parts.last() else it + (parts.last() to value))
        }.toString()
    }
    fun record(seals: String = "[$lSeal]", evidence: String = "[${applied()}]", schema: Int = 2): Preferences =
        ReclamationFixtures.raw(seals, evidence, schema)

    fun seal(raw: String = lSeal): SealV1 {
        val read = ControlObligations.read(ControlKind.SEAL, ControlObligationFixtures.node(raw))
        assertTrue(read is ControlEntryRead.Interpreted)
        assertEquals(ControlObligationFixtures.node(raw).toPayloadEntry(), (read as ControlEntryRead.Interpreted).original.toPayloadEntry())
        return read.value as SealV1
    }
    fun rejectSeal(raw: String) {
        assertTrue(seal().settlement is RetiredNullSettlementEvidenceV2)
        val result = ControlObligations.read(ControlKind.SEAL, ControlObligationFixtures.node(raw))
        assertTrue("whole seal must remain opaque", result is ControlEntryRead.Uninterpretable)
        assertEquals(ControlObligationFixtures.node(raw).toPayloadEntry(), (result as ControlEntryRead.Uninterpretable).original)
    }
    fun evidence(raw: String): ControlEvidenceRead = ControlEvidenceReader.read(ControlPayloadCodec().decode("[$raw]") as PayloadRead.Parsed)
    fun row(raw: String = applied()): AppliedEvidence.Settlement {
        val result = evidence(raw)
        assertFalse(result.hasUninterpretable)
        return (result.entries.single() as ControlEvidenceEntryRead.Interpreted).value as AppliedEvidence.Settlement
    }
    fun rejectEvidence(raw: String) {
        assertEquals(HandoverSettlementTransition.RETIRED_NULL, row().transition)
        val result = evidence(raw)
        assertTrue("whole Applied must remain opaque", result.hasUninterpretable)
        assertEquals(ControlObligationFixtures.node(raw).toPayloadEntry(),
            (result.entries.single() as ControlEvidenceEntryRead.Uninterpretable).original)
    }
}
