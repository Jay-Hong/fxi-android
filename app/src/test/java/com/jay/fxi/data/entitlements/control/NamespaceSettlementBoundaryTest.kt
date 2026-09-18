package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.fence
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.operation
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.transition
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.settled
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.replace
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Direct tests distinguish exact witness/candidate guards from earlier D1 schema gates. */
class NamespaceSettlementBoundaryTest {
    @Test fun everyWitnessFieldIsRequiredEvenWhenD1WouldRejectItEarlier() {
        val spec = input(); val expected = NamespaceSettlementOracle.witness()
        val variants = linkedMapOf(
            "operationId" to expected.copy(operationId = "other"),
            "origin" to expected.copy(originLifetimeId = LifetimeId("other")),
            "operation" to expected.copy(operation = StoreOp.SIGN_OUT),
            "before.owner" to expected.copy(before = fence.copy(ownerUid = "B")),
            "before.user" to expected.copy(before = fence.copy(userAccessEpoch = "other")),
            "before.krx" to expected.copy(before = fence.copy(krxCapabilityEpoch = "other")),
            "after.owner" to expected.copy(after = expected.after.copy(ownerUid = "B")),
            "after.user" to expected.copy(after = expected.after.copy(userAccessEpoch = "other")),
            "after.krx" to expected.copy(after = expected.after.copy(krxCapabilityEpoch = "other")),
            "journal.owner" to expected.copy(journal = expected.journal.copy(ownerUid = null)),
            "journal.axis" to expected.copy(journal = expected.journal.copy(axis = PurgeScope.CAPABILITY)),
            "journal.epoch" to expected.copy(journal = expected.journal.copy(epoch = null))
        )
        for ((field, changed) in variants) {
            assertTrue(field, transition.witnessMatches(expected.copy(), expected))
            assertFalse(field, transition.witnessMatches(changed, expected))

        }
    }

    @Test fun unsettledTargetFieldsAreComparedIndependentlyBeforeAnyCandidate() {
        val expected = input().seals.single()
        val variants = listOf(
            expected.copy(kind = SealTargetKind.NULL_NAMESPACE),
            expected.copy(key = expected.key.copy(ownerUid = "B")),
            expected.copy(key = expected.key.copy(axis = PurgeScope.CAPABILITY)),
            expected.copy(key = expected.key.copy(epoch = "other"))
        )
        for (actual in variants) {
            assertTrue(transition.unsettledTargetMatches(expected.copy(), expected))
            assertFalse(transition.unsettledTargetMatches(actual, expected))
        }
        // A NULL_NAMESPACE with the same non-null epoch is deliberately a pure-boundary case.
        // D1 cannot produce that combination; the full-path kind case also hits the epoch guard.
    }

    @Test fun immutableFieldsAndKeyOrderAreComparedStructurally() {
        val spec = input(); val original = node(user); val settled = withWitness(user, spec.witness(spec.seals.single()))
        assertTrue(transition.immutableSealMatches(settled, original))
        assertTrue(transition.immutableSealMatches(node(settled.toPayloadEntry().fields.entries.reversed().joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }), original))
        assertFalse(transition.immutableSealMatches(withWitness(user.replace("\"A\"", "\"B\""), spec.witness(spec.seals.single())), original))
    }

    @Test fun eachChangedPayloadChecksUtf8SizeAndEnvelopeIndependently() {
        for (kind in listOf(ControlKind.SEAL, ControlKind.DEMAND)) {
            val entries = listOf(PayloadEntry.Uninterpretable(JsonPrimitive("한".repeat(21_845))))
            val bytes = 65_539 // [ + quote + 65535 UTF-8 bytes + quote + ]
            assertEquals(NamespaceSettlementTransition.ChangedPayload.Rejected(RejectionReason.TooLarge(kind, bytes, 65_536)),
                transition.encodeChanged(kind, entries))
            assertEquals(NamespaceSettlementTransition.ChangedPayload.Encoded("[]"), transition.encodeChanged(kind, emptyList()))
            val depth = NamespaceSettlementTransition(ControlPayloadCodec(maxDepth = 2))
            val tooDeep = listOf(PayloadEntry.Uninterpretable(JsonArray(listOf(JsonArray(emptyList())))))
            assertEquals(NamespaceSettlementTransition.ChangedPayload.Rejected(RejectionReason.InvalidRequest("InvalidEnvelope")),
                depth.encodeChanged(kind, tooDeep))
            assertEquals(NamespaceSettlementTransition.ChangedPayload.Encoded("[[]]"),
                depth.encodeChanged(kind, listOf(PayloadEntry.Uninterpretable(JsonArray(emptyList())))))
        }
    }

    @Test fun completeCandidateChecksSupportWholeRecordIdentityAndExactPostcondition() {
        val required = mapOf("s" to (ControlKind.SEAL to node(user)))
        assertTrue(transition.validCandidate(ControlRecordReader().read(raw()), required))
        assertFalse(transition.validCandidate(ControlRecordReader().read(androidx.datastore.preferences.core.emptyPreferences()), required))
        assertFalse(transition.validCandidate(ControlRecordReader().read(raw(seals = "[]")), required))
        val wrongKind = raw(seals = "[]").toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[$user]" }
        assertFalse(transition.validCandidate(ControlRecordReader().read(wrongKind), required))
        val request = node(ControlObligationFixtures.request.replace("\"d\"", "\"s\""))
        val onlyWrongKind = ControlRecordReader().read(raw(seals = "[]", requests = jsonArray(request)))
        assertFalse(transition.validCandidate(onlyWrongKind, mapOf("s" to (ControlKind.SEAL to request))))
        val collision = raw(requests = "[{\"id\":\"s\"}]")
        assertFalse(transition.validCandidate(ControlRecordReader().read(collision), required))
        assertFalse(transition.validCandidate(ControlRecordReader().read(raw(seals = "[${user.replace("\"u\"", "\"v\"")}]")), required))
    }

    @Test fun exactUtf8LimitRemainsInclusive() {
        val entries = listOf(PayloadEntry.Uninterpretable(JsonPrimitive("한".repeat(21_844) + "")))
        val bytes = 65_536
        assertEquals(NamespaceSettlementTransition.ChangedPayload.Encoded("[\"${"한".repeat(21_844)}\"]"), transition.encodeChanged(ControlKind.SEAL, entries))
        val smaller = NamespaceSettlementTransition(ControlPayloadCodec(maxPayloadBytes = bytes - 1))
        assertEquals(NamespaceSettlementTransition.ChangedPayload.Rejected(RejectionReason.TooLarge(ControlKind.SEAL, bytes, bytes - 1)),
            smaller.encodeChanged(ControlKind.SEAL, entries))
    }
}
