package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.transition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Separate execution: a direct comparator assertion cannot hide the full-path result. */
@RunWith(Parameterized::class)
class NamespaceSettlementWitnessPathTest(private val field: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun fields() = listOf(
            "operationId", "origin", "operation", "before.owner", "before.user", "before.krx",
            "after.owner", "after.user", "after.krx", "journal.owner", "journal.axis", "journal.epoch")
    }
    @Test fun storedWitnessHasFixedFullPathOutcome() {
        val expected = NamespaceSettlementOracle.witness()
        val changed = when (field) {
            "operationId" -> expected.copy(operationId = "other")
            "origin" -> expected.copy(originLifetimeId = LifetimeId("other"))
            "operation" -> expected.copy(operation = StoreOp.SIGN_OUT)
            "before.owner" -> expected.copy(before = expected.before.copy(ownerUid = "B"))
            "before.user" -> expected.copy(before = expected.before.copy(userAccessEpoch = "other"))
            "before.krx" -> expected.copy(before = expected.before.copy(krxCapabilityEpoch = "other"))
            "after.owner" -> expected.copy(after = expected.after.copy(ownerUid = "B"))
            "after.user" -> expected.copy(after = expected.after.copy(userAccessEpoch = "other"))
            "after.krx" -> expected.copy(after = expected.after.copy(krxCapabilityEpoch = "other"))
            "journal.owner" -> expected.copy(journal = expected.journal.copy(ownerUid = null))
            "journal.axis" -> expected.copy(journal = expected.journal.copy(axis = PurgeScope.CAPABILITY))
            "journal.epoch" -> expected.copy(journal = expected.journal.copy(epoch = null))
            else -> error(field)
        }
        val read = ControlRecordReader().read(raw(seals = jsonArray(withWitness(user, changed)))) as ControlRecordRead.Supported
        // D1 requires journal.axis == seal.key.axis; all other variants are valid D1 objects.
        assertEquals(field != "journal.axis", read.arrays.getValue(ControlKind.SEAL).entries.single() is ControlEntryRead.Interpreted)
        val spec = input(); val command = CommandRef(spec.operationId, ControlCommandBody.RotateAndSettle(spec), NamespaceSettlementFixtures.trackerLife)
        val decision = transition.decide(command, spec, read, context, false, false)
        assertEquals(RecordTransactionDecision.Observe::class.java, decision.javaClass)
        val reason = when (field) {
            "journal.axis" -> ConflictReason.UninterpretableTarget
            // Different operation ids select the foreign-witness branch before witnessMatches.
            "operationId" -> ConflictReason.TargetChanged
            else -> RecoveryReason.InconsistentSettlement
        }
        negative((decision.value as ControlRecordStore.Outcome.Negative).result, reason)
    }
}
