package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.applied
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.change
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.evidence
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lWitness
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.nSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.record
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rejectEvidence
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rejectSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.row
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.seal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HandoverAppliedFormatTest {
    @Test fun A01_missing_version() {
        rejectEvidence(change(applied(), "version", null))
    }
    @Test fun A01_missing_commandId() {
        rejectEvidence(change(applied(), "commandId", null))
    }
    @Test fun A01_missing_ownerTrackingLifetimeId() {
        rejectEvidence(change(applied(), "ownerTrackingLifetimeId", null))
    }
    @Test fun A01_missing_kind() {
        rejectEvidence(change(applied(), "kind", null))
    }
    @Test fun A01_missing_transition() {
        rejectEvidence(change(applied(), "transition", null))
    }
    @Test fun A01_missing_sealIds() {
        rejectEvidence(change(applied(), "sealIds", null))
    }
    @Test fun A01_missing_demandId() {
        rejectEvidence(change(applied(), "demandId", null))
    }
    @Test fun A01_unknown_key() {
        rejectEvidence(change(applied(), "future", JsonPrimitive(true)))
    }
    @Test fun A01_version() {
        rejectEvidence(change(applied(), "version", JsonPrimitive(3)))
    }
    @Test fun A01_quoted_version() {
        rejectEvidence(change(applied(), "version", JsonPrimitive("2")))
    }
    @Test fun A01_empty_command() {
        rejectEvidence(change(applied(), "commandId", JsonPrimitive("")))
    }
    @Test fun A01_numeric_command() {
        rejectEvidence(change(applied(), "commandId", JsonPrimitive(1)))
    }
    @Test fun A01_empty_lifetime() {
        rejectEvidence(change(applied(), "ownerTrackingLifetimeId", JsonPrimitive("")))
    }
    @Test fun A01_noncanonical_lifetime() {
        rejectEvidence(change(applied(), "ownerTrackingLifetimeId", JsonPrimitive("00000000-0000-0000-0000-0000000000AA")))
    }
    @Test fun A01_numeric_lifetime() {
        rejectEvidence(change(applied(), "ownerTrackingLifetimeId", JsonPrimitive(1)))
    }
    @Test fun A01_unknown_kind() {
        rejectEvidence(change(applied(), "kind", JsonPrimitive("FUTURE")))
    }
    @Test fun A01_unknown_transition() {
        rejectEvidence(change(applied(), "transition", JsonPrimitive("OWNER_DEPARTURE")))
    }
    @Test fun A01_numeric_transition() {
        rejectEvidence(change(applied(), "transition", JsonPrimitive(1)))
    }
    @Test fun A01_null_seals() {
        rejectEvidence(change(applied(), "sealIds", JsonNull))
    }
    @Test fun A01_object_seal_id() {
        rejectEvidence(change(applied(), "sealIds", JsonArray(listOf(JsonObject(emptyMap())))))
    }
    @Test fun A01_numeric_seal_id() {
        rejectEvidence(change(applied(), "sealIds", JsonArray(listOf(JsonPrimitive(1)))))
    }
    @Test fun A01_null_seal_id() {
        rejectEvidence(change(applied(), "sealIds", JsonArray(listOf(JsonNull))))
    }
    @Test fun A01_empty_seal_id() {
        rejectEvidence(change(applied(), "sealIds", JsonArray(listOf(JsonPrimitive("")))))
    }
    @Test fun A01_duplicate_seal_id() {
        rejectEvidence(change(applied(), "sealIds", JsonArray(listOf(JsonPrimitive("ls"), JsonPrimitive("ls")))))
    }
    @Test fun A01_numeric_demand() {
        rejectEvidence(change(applied(), "demandId", JsonPrimitive(1)))
    }
    @Test fun A01_empty_demand() {
        rejectEvidence(change(applied(transition = "RETIRED_NAMESPACE"), "demandId", JsonPrimitive("")))
    }
    @Test fun A01_R_empty_seals() {
        rejectEvidence(applied(transition = "RETIRED_NAMESPACE", seals = "[]", demand = "null"))
    }
    @Test fun A01_R_too_many_seals() {
        rejectEvidence(applied(transition = "RETIRED_NAMESPACE", seals = """["s0","s1"]""", demand = "null"))
    }
    @Test fun A01_R_maximum_supported() {
        assertEquals(1, row(applied(transition = "RETIRED_NAMESPACE", seals = """["s0"]""", demand = "null")).sealIds.size)
    }
    @Test fun A01_N_empty_seals() {
        rejectEvidence(applied(transition = "CURRENT_NULL", seals = "[]", demand = "\"d\""))
    }
    @Test fun A01_N_too_many_seals() {
        rejectEvidence(applied(transition = "CURRENT_NULL", seals = """["s0","s1","s2","s3","s4"]""", demand = "\"d\""))
    }
    @Test fun A01_N_maximum_supported() {
        assertEquals(4, row(applied(transition = "CURRENT_NULL", seals = """["s0","s1","s2","s3"]""", demand = "\"d\"")).sealIds.size)
    }
    @Test fun A01_L_empty_seals() {
        rejectEvidence(applied(transition = "RETIRED_NULL", seals = "[]", demand = "null"))
    }
    @Test fun A01_L_too_many_seals() {
        rejectEvidence(applied(transition = "RETIRED_NULL", seals = """["s0","s1","s2"]""", demand = "null"))
    }
    @Test fun A01_L_maximum_supported() {
        assertEquals(2, row(applied(transition = "RETIRED_NULL", seals = """["s0","s1"]""", demand = "null")).sealIds.size)
    }
    @Test fun A01_N_null_demand() {
        rejectEvidence(applied(transition = "CURRENT_NULL"))
    }
    @Test fun A01_L_nonnull_demand() {
        rejectEvidence(applied(demand = "\"d\""))
    }
    @Test fun A01_nonnull_demand_collides() {
        rejectEvidence(applied(transition = "RETIRED_NAMESPACE", demand = "\"ls\""))
    }
    @Test fun A01_R_nullable_demand_supported() {
        assertNull(row(applied(transition = "RETIRED_NAMESPACE")).demandId)
    }
    @Test fun A01_R_nonnull_demand_supported() {
        assertEquals("d", row(applied(transition = "RETIRED_NAMESPACE", demand = "\"d\"")).demandId)
    }
    @Test fun A01_N_one_seal_supported() {
        assertEquals(listOf("ls"), row(applied(transition = "CURRENT_NULL", demand = "\"d\"")).sealIds)
    }
    @Test fun A01_encoder_exact_null_and_order() {
        val ids = mutableListOf("second", "first")
        val value = AppliedEvidence.Settlement("lop", ReclamationFixtures.oldLife,
            HandoverSettlementTransition.RETIRED_NULL, ids, null)
        ids.clear()
        val expected = Json.parseToJsonElement(applied(seals = """["second","first"]"""))
        assertEquals(expected, ControlAppliedEvidence.node(value))
        assertEquals(listOf("second", "first"), row(expected.toString()).sealIds)
        assertTrue(runCatching { (value.sealIds as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
    @Test fun A01_encoder_nonnull_demand() {
        val value = AppliedEvidence.Settlement("rop", ReclamationFixtures.oldLife,
            HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("rs"), "d")
        assertEquals(Json.parseToJsonElement(applied("RETIRED_NAMESPACE", """["rs"]""", "\"d\"", "rop")),
            ControlAppliedEvidence.node(value))
    }
    @Test fun A01_duplicate_command_rows_both_opaque() {
        val wire = applied()
        val read = ControlEvidenceReader.read(ControlPayloadCodec().decode("[$wire,$wire]") as PayloadRead.Parsed)
        assertEquals(2, read.entries.filterIsInstance<ControlEvidenceEntryRead.Uninterpretable>().size)
    }
    @Test fun A01_duplicate_with_future_kind_both_opaque() {
        val wire = applied()
        val future = change(wire, "kind", JsonPrimitive("FUTURE"))
        val read = ControlEvidenceReader.read(ControlPayloadCodec().decode("[$wire,$future]") as PayloadRead.Parsed)
        assertEquals(2, read.entries.filterIsInstance<ControlEvidenceEntryRead.Uninterpretable>().size)
    }
}
