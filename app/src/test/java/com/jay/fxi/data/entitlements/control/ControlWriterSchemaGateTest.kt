package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlWriterSchemaGateTest {
    @get:Rule val folder = TemporaryFolder()

    private fun prepare(o: ControlStoreTestStorage, kind: String): CommandRef = when (kind) {
        "add" -> o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(recovery); set("id", ControlScalar.Text(id))
        })
        "edit" -> o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM"))
            set("raisedAt", ControlScalar.Integer(5))
        })
        "floor" -> o.control.prepare(o.control.recordFloor(node(emptyGuard), BootReading("boot", 50), 100, LifetimeId("life")))
        else -> NamespaceSettlementFixtures.command(o, NamespaceSettlementFixtures.input())
    }
    private suspend fun seed(o: ControlStoreTestStorage, schema: Int) {
        o.data.updateData {
            NamespaceSettlementFixtures.raw(requests = "[$request,$emptyGuard]", schema = schema).toMutablePreferences().apply {
                if (schema == 2) this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] =
                    """ [ {"version":2,"commandId":"old","ownerTrackingLifetimeId":"01234567-89ab-cdef-0123-456789abcdef","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"old-d","joined":false,"written":true}]} ] """
            }
        }
    }

    @Test fun addRequiresMigrationForNewEffect() = assertNewEffectRequiresMigration("add")
    @Test fun editRequiresMigrationForNewEffect() = assertNewEffectRequiresMigration("edit")
    @Test fun floorRequiresMigrationForNewEffect() = assertNewEffectRequiresMigration("floor")
    @Test fun facadeRotationRequiresMigrationForNewEffect() = assertNewEffectRequiresMigration("rotation")

    private fun assertNewEffectRequiresMigration(kind: String) = runBlocking {
        val o = ControlStoreTestStorage(File(folder.root, "$kind.preferences_pb"))
        try {
            seed(o, 1)
            val command = prepare(o, kind)
            val before = o.raw(); val writes = o.storage.writes
            NamespaceSettlementFixtures.negative(o.control.execute(command, NamespaceSettlementFixtures.context),
                RecoveryReason.ControlSchemaMigrationRequired)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            val tracked = ControlCommandTracking.forOwner(o.owner).findPrepared(command)!!
            assertNull(tracked.firstConfirmDiscontinuityCount)
            assertFalse(tracked.confirmationRequested.get())
            assertTrue(ControlCommandTracking.forOwner(o.owner).snapshot().isEmpty())
            assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
            val allowed = o.control.execute(command, NamespaceSettlementFixtures.context) as ControlStoreResult.Confirmed
            assertEquals(ConfirmedEffect.AppliedThisAttempt, allowed.effect)
            assertEquals(2, allowed.snapshot.record.schemaVersion)
            assertNotNull(ControlAppliedEvidence.own(allowed.snapshot.record, command))
        } finally { o.close() }
    }

    @Test fun executeRegistrationFailureKeepsReaderPrecedence() = assertHistoryFailurePrecedence(false)
    @Test fun previousConfirmationHistoryFailureKeepsReaderPrecedence() = assertHistoryFailurePrecedence(true)

    private fun assertHistoryFailurePrecedence(confirmOnly: Boolean) = runBlocking {
        val o = ControlStoreTestStorage(File(folder.root, "precedence.preferences_pb"))
        try {
            seed(o, 2)
            val foreign = CommandRef("foreign", emptyList(), NamespaceSettlementFixtures.trackerLife)
            val before = o.raw(); val writes = o.storage.writes
            val result = if (confirmOnly) o.control.confirmPrevious(foreign) else o.control.execute(foreign)
            assertTrue(result is ControlStoreResult.Unconfirmed)
            assertEquals(UnconfirmedReason.HistoryUnavailable, (result as ControlStoreResult.Unconfirmed).reason)
            assertEquals(setOf(foreign), result.localUnresolvedCommands)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            o.data.edit { it.remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }
            NamespaceSettlementFixtures.negative(
                if (confirmOnly) o.control.confirmPrevious(foreign) else o.control.execute(foreign),
                RecoveryReason.UnreadableRecord)
        } finally { o.close() }
    }

    @Test fun directRotationRequiresMigrationForV1() = assertDirectRotationSchema(1)
    @Test fun directRotationAllowsV2WithApplied() = assertDirectRotationSchema(2)

    private fun assertDirectRotationSchema(schema: Int) {
        val input = NamespaceSettlementFixtures.input()
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), NamespaceSettlementFixtures.trackerLife)
        val read = ControlRecordReader().read(NamespaceSettlementFixtures.raw(schema = schema)) as ControlRecordRead.Supported
        val decision = NamespaceSettlementFixtures.transition.decide(command, input, read, NamespaceSettlementFixtures.context, false, false)
        if (schema == 1) {
            assertTrue(decision is RecordTransactionDecision.Observe)
            NamespaceSettlementFixtures.negative((decision.value as ControlRecordStore.Outcome.Negative).result,
                RecoveryReason.ControlSchemaMigrationRequired)
        } else {
            assertTrue(decision is RecordTransactionDecision.Confirm)
            val candidate = (decision as RecordTransactionDecision.Confirm).candidate
            val row = ControlAppliedEvidence.own(ControlRecordReader().read(candidate) as ControlRecordRead.Supported, command)
            assertTrue("rotation must persist its Applied row", row is AppliedEvidence.Rotation)
            val evidence = row as AppliedEvidence.Rotation
            assertEquals(command.ownerTrackingLifetimeId.value, evidence.ownerTrackingLifetimeId)
            assertEquals(listOf("s"), evidence.sealIds); assertEquals(input.demandId, evidence.demandId)
        }
    }

    @Test fun directRotationRejectsMissingOriginalSchema() = assertDirectRotationRejectsOriginalSchema("missing")
    @Test fun directRotationRejectsStringOriginalSchema() = assertDirectRotationRejectsOriginalSchema("string")
    @Test fun directRotationRejectsLongOriginalSchema() = assertDirectRotationRejectsOriginalSchema("long")
    @Test fun directRotationRejectsRelabeledOriginalSchema() = assertDirectRotationRejectsOriginalSchema("relabeled")

    private fun assertDirectRotationRejectsOriginalSchema(variant: String) {
        val input = NamespaceSettlementFixtures.input()
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), NamespaceSettlementFixtures.trackerLife)
        val normal = ControlRecordReader().read(NamespaceSettlementFixtures.raw()) as ControlRecordRead.Supported
        val source = normal.original.toMutablePreferences().apply {
            when (variant) {
                "missing" -> remove(ControlStoreTestStorage.SCHEMA)
                "string" -> this[androidx.datastore.preferences.core.stringPreferencesKey("control_schema")] = "2"
                "long" -> this[androidx.datastore.preferences.core.longPreferencesKey("control_schema")] = 2L
                else -> this[ControlStoreTestStorage.SCHEMA] = 1
            }
        }
        val read = ControlRecordRead.Supported(source, normal.arrays, 2, normal.metadata)
        val decision = NamespaceSettlementFixtures.transition.decide(command, input, read, NamespaceSettlementFixtures.context, false, false)
        assertTrue(variant, decision is RecordTransactionDecision.Observe)
        NamespaceSettlementFixtures.negative((decision.value as ControlRecordStore.Outcome.Negative).result, RecoveryReason.UnreadableRecord)
    }

    @Test fun receiptFlagsWithInterpretableObligationsAndMetadata() = assertReceiptFlags(false to false)
    @Test fun receiptFlagsWithOpaqueMetadataOnly() = assertReceiptFlags(false to true)
    @Test fun receiptFlagsWithOpaqueObligationsOnly() = assertReceiptFlags(true to false)
    @Test fun receiptFlagsWithOpaqueObligationsAndMetadata() = assertReceiptFlags(true to true)

    private fun assertReceiptFlags(flags: Pair<Boolean, Boolean>) {
        val (obligation, metadata) = flags
        val input = NamespaceSettlementFixtures.input()
        val source = NamespaceSettlementFixtures.settled(input).toMutablePreferences().apply {
            if (obligation) this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[null]"
        }
        val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
        // Independent metadata flags on a confirmation-only settled witness.
        val synthetic = ControlRecordRead.Supported(source, read.arrays, 2, ControlMetadataRead.V2(
            ControlEvidenceReader.read(ControlPayloadCodec().decode(if (metadata) "[null]" else "[]") as PayloadRead.Parsed),
            ScopeFenceRead(PayloadRead.Parsed(emptyList()))))
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), NamespaceSettlementFixtures.trackerLife)
        val decision = NamespaceSettlementFixtures.transition.decide(command, input, synthetic, null, true, true)
        assertEquals(RecordTransactionDecision.Confirm::class.java, decision.javaClass)
        val receipt = (decision.value as ControlRecordStore.Outcome.Positive).settlement!!
        assertEquals(input.operationId, receipt.operationId)
        assertEquals(obligation, receipt.hasUninterpretable)
        assertEquals(metadata, receipt.hasUninterpretableMetadata)
        assertEquals(obligation || metadata, receipt.blocksProtectedAdmission)
    }

}
