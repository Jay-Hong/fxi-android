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

    @Test fun genericAndFacadeRotationGatesPreserveNewAndExistingUnresolvedHistory() = runBlocking {
        for (kind in listOf("add", "edit", "floor", "rotation")) for (unresolved in listOf(false, true)) {
            val o = ControlStoreTestStorage(File(folder.root, "$kind-$unresolved.preferences_pb"))
            try {
                seed(o, 1)
                val command = prepare(o, kind)
                val tracking = ControlCommandTracking.forOwner(o.owner)
                val sibling = prepare(o, "add")
                if (unresolved) {
                    o.storage.before = true
                    val failed = o.control.execute(command, NamespaceSettlementFixtures.context)
                    assertEquals(ControlStoreResult.Unconfirmed::class.java, failed.javaClass)
                    failed as ControlStoreResult.Unconfirmed
                    assertEquals(UnconfirmedReason.StorageFailure, failed.reason)
                    assertEquals(ControlAttemptPhase.ConfirmingStorage, failed.phase)
                    assertEquals(java.io.IOException::class.java, failed.failure?.javaClass)
                    assertEquals("before write block", failed.failure?.message)
                    o.storage.before = false
                    tracking.markUnresolved(sibling)
                }
                seed(o, 2)
                val before = o.raw()
                val writes = o.storage.writes
                val members = tracking.snapshot()
                val tracked = tracking.findPrepared(command)!!
                val targets = tracked.targets.get()
                val requested = tracked.confirmationRequested.get()
                for (confirm in listOf(false, true)) {
                    val result = if (confirm) o.control.confirmPrevious(command, o.control.checkpoint(command))
                        else o.control.execute(command, NamespaceSettlementFixtures.context)
                    assertEquals(ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
                    result as ControlStoreResult.RecoveryRequired
                    assertSame(command, result.command)
                    assertEquals(RecoveryReason.ControlWriterUpgradeRequired, result.reason)
                    assertEquals(before, result.observation.original)
                    assertEquals(2, (result.observation as ControlRecordRead.Supported).schemaVersion)
                    assertEquals(members, result.localUnresolvedCommands)
                    assertEquals(members, tracking.snapshot())
                    assertEquals(before, o.raw())
                    assertEquals(writes, o.storage.writes)
                    assertSame(targets, tracked.targets.get()) // candidate dispatch would capture targets
                    assertEquals(requested, tracked.confirmationRequested.get())
                    assertFalse(tracked.confirmed.get())
                    assertTrue(tracking.executing.isEmpty())
                }
                seed(o, 1)
                val allowed = o.control.execute(command, NamespaceSettlementFixtures.context)
                assertEquals("$kind/$unresolved: $allowed", ControlStoreResult.Confirmed::class.java, allowed.javaClass)
                allowed as ControlStoreResult.Confirmed
                assertEquals(ConfirmedEffect.AppliedThisAttempt, allowed.effect)
                assertSame(command, allowed.command)
                assertEquals(1, allowed.snapshot.record.schemaVersion)
                assertFalse(command in allowed.localUnresolvedCommands)
            } finally { o.close() }
        }
    }

    @Test fun completedCommandsAndPreviousReferencesStillCannotConfirmV2() = runBlocking {
        for (kind in listOf("add", "rotation")) {
            val o = ControlStoreTestStorage(File(folder.root, "confirmed-$kind.preferences_pb"))
            try {
                seed(o, 1)
                val command = prepare(o, kind)
                val first = o.control.execute(command, NamespaceSettlementFixtures.context) as ControlStoreResult.Confirmed
                val checkpoint = o.control.checkpoint(command)
                o.data.edit {
                    it[ControlStoreTestStorage.SCHEMA] = 2
                    it[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[]"
                    it[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[]"
                }
                val before = o.raw(); val writes = o.storage.writes
                for (result in listOf(o.control.execute(command), o.control.confirmPrevious(command, checkpoint))) {
                    assertEquals(ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
                    result as ControlStoreResult.RecoveryRequired
                    assertEquals(RecoveryReason.ControlWriterUpgradeRequired, result.reason)
                    assertEquals(before, result.observation.original)
                    assertTrue(result.localUnresolvedCommands.isEmpty())
                }
                assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
                assertTrue(ControlCommandTracking.forOwner(o.owner).findPrepared(command)!!.confirmed.get())
                assertEquals(ConfirmedEffect.AppliedThisAttempt, first.effect)
            } finally { o.close() }
        }
    }

    @Test fun readerAndRegistrationFailuresKeepTheirPrecedence() = runBlocking {
        val o = ControlStoreTestStorage(File(folder.root, "precedence.preferences_pb"))
        try {
            seed(o, 2)
            val foreign = CommandRef("foreign", emptyList())
            val before = o.raw(); val writes = o.storage.writes
            val result = o.control.execute(foreign)
            assertEquals(ControlStoreResult.Unconfirmed::class.java, result.javaClass)
            result as ControlStoreResult.Unconfirmed
            assertEquals(UnconfirmedReason.HistoryUnavailable, result.reason)
            assertEquals(ControlAttemptPhase.PreparingCandidate, result.phase)
            assertEquals(before, result.lastObservation!!.original)
            assertEquals(setOf(foreign), result.localUnresolvedCommands)
            val previous = o.control.confirmPrevious(foreign)
            assertEquals(ControlStoreResult.RecoveryRequired::class.java, previous.javaClass)
            previous as ControlStoreResult.RecoveryRequired
            assertEquals(RecoveryReason.ControlWriterUpgradeRequired, previous.reason)
            assertEquals(setOf(foreign), previous.localUnresolvedCommands)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            o.data.edit { it.remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }
            val damaged = o.control.execute(foreign) as ControlStoreResult.RecoveryRequired
            assertEquals(RecoveryReason.UnreadableRecord, damaged.reason)
            assertEquals(listOf(ControlRecordProblem.MissingPayload(ControlPayloadKey.SCOPE_FENCE)),
                (damaged.observation as ControlRecordRead.Unreadable).problems)
        } finally { o.close() }
    }

    @Test fun directRotationGateReturnsObserveBeforeAnyCandidateOrConfirmation() {
        val input = NamespaceSettlementFixtures.input()
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input))
        for (confirm in listOf(false, true)) for (confirmed in listOf(false, true)) {
            val read = ControlRecordReader().read(NamespaceSettlementFixtures.raw(schema = 2)) as ControlRecordRead.Supported
            val decision = NamespaceSettlementFixtures.transition.decide(command, input, read, NamespaceSettlementFixtures.context, confirm, confirmed)
            assertEquals(RecordTransactionDecision.Observe::class.java, decision.javaClass)
            assertEquals(ControlRecordStore.Outcome.Negative::class.java, decision.value.javaClass)
            val result = (decision.value as ControlRecordStore.Outcome.Negative).result
            assertEquals(ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
            result as ControlStoreResult.RecoveryRequired
            assertSame(command, result.command)
            assertSame(read, result.observation)
            assertEquals(RecoveryReason.ControlWriterUpgradeRequired, result.reason)
            assertTrue(result.localUnresolvedCommands.isEmpty())
            assertEquals(NamespaceSettlementFixtures.raw(schema = 2), read.original)
        }
        val allowed = NamespaceSettlementFixtures.transition.decide(command, input,
            ControlRecordReader().read(NamespaceSettlementFixtures.raw()) as ControlRecordRead.Supported,
            NamespaceSettlementFixtures.context, false, false)
        assertEquals(RecordTransactionDecision.Confirm::class.java, allowed.javaClass)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, (allowed.value as ControlRecordStore.Outcome.Positive).effect)
    }

    @Test fun directRotationChecksTheOriginalSchemaEvenWhenRelabeled() {
        val input = NamespaceSettlementFixtures.input()
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input))
        val v1 = ControlRecordReader().read(NamespaceSettlementFixtures.raw()) as ControlRecordRead.Supported
        val allowed = NamespaceSettlementFixtures.transition.decide(command, input, v1, NamespaceSettlementFixtures.context, false, false)
        assertEquals(RecordTransactionDecision.Confirm::class.java, allowed.javaClass)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, (allowed.value as ControlRecordStore.Outcome.Positive).effect)
        val v2 = ControlRecordReader().read(NamespaceSettlementFixtures.raw(schema = 2)) as ControlRecordRead.Supported
        val relabeled = ControlRecordRead.Supported(v2.original, v2.arrays, 1, ControlMetadataRead.NotPresentV1)
        for ((label, read) in listOf("CONTROL normal v2" to v2, "NEGATIVE[SNAPSHOT_V2_RELABELLED]" to relabeled)) {
            for (confirm in listOf(false, true)) for (confirmed in listOf(false, true)) {
                val decision = NamespaceSettlementFixtures.transition.decide(command, input, read, NamespaceSettlementFixtures.context, confirm, confirmed)
                assertEquals(label, RecordTransactionDecision.Observe::class.java, decision.javaClass)
                assertEquals(ControlRecordStore.Outcome.Negative::class.java, decision.value.javaClass)
                val result = (decision.value as ControlRecordStore.Outcome.Negative).result
                assertEquals(ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
                result as ControlStoreResult.RecoveryRequired
                assertSame(command, result.command)
                assertSame(read, result.observation)
                assertEquals(RecoveryReason.ControlWriterUpgradeRequired, result.reason)
                assertTrue(result.localUnresolvedCommands.isEmpty())
                assertEquals(v2.original, read.original)
            }
        }
    }

    @Test fun directRotationRejectsMistypedOrMissingOriginalSchema() {
        val input = NamespaceSettlementFixtures.input()
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input))
        val normal = ControlRecordReader().read(NamespaceSettlementFixtures.raw()) as ControlRecordRead.Supported
        val allowed = NamespaceSettlementFixtures.transition.decide(command, input, normal, NamespaceSettlementFixtures.context, false, false)
        assertEquals(RecordTransactionDecision.Confirm::class.java, allowed.javaClass)
        for (variant in listOf("missing", "string", "long")) {
            val original = normal.original.toMutablePreferences().apply {
                when (variant) {
                    "missing" -> remove(ControlStoreTestStorage.SCHEMA)
                    "string" -> this[androidx.datastore.preferences.core.stringPreferencesKey("control_schema")] = "1"
                    else -> this[androidx.datastore.preferences.core.longPreferencesKey("control_schema")] = 1L
                }
            }.toPreferences()
            val read = ControlRecordRead.Supported(original, normal.arrays, 1, ControlMetadataRead.NotPresentV1)
            val decision = NamespaceSettlementFixtures.transition.decide(command, input, read, NamespaceSettlementFixtures.context, false, false)
            assertEquals("NEGATIVE[SNAPSHOT_SCHEMA_TYPE:$variant]", RecordTransactionDecision.Observe::class.java, decision.javaClass)
            assertEquals(ControlRecordStore.Outcome.Negative::class.java, decision.value.javaClass)
            val result = (decision.value as ControlRecordStore.Outcome.Negative).result
            assertEquals("NEGATIVE[SNAPSHOT_SCHEMA_TYPE:$variant] result", ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
            result as ControlStoreResult.RecoveryRequired
            assertEquals(RecoveryReason.ControlWriterUpgradeRequired, result.reason)
            assertSame(read, result.observation)
            assertSame(command, result.command)
            assertTrue(result.localUnresolvedCommands.isEmpty())
            assertEquals(original, read.original)
        }
    }

    @Test fun receiptFlagsAreIndependentAndForwardedFromTheSnapshot() {
        for (obligation in listOf(false, true)) for (metadata in listOf(false, true)) {
            val input = NamespaceSettlementFixtures.input()
            val source = NamespaceSettlementFixtures.settled(input).toMutablePreferences().apply {
                if (obligation) this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[null]"
            }
            val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
            // Deliberate internal synthetic read exercises receipt forwarding behind the temporary v2 gate.
            val synthetic = ControlRecordRead.Supported(source, read.arrays, 1, ControlMetadataRead.V2(
                ControlEvidenceReader.read(ControlPayloadCodec().decode(if (metadata) "[null]" else "[]") as PayloadRead.Parsed),
                ScopeFenceRead(PayloadRead.Parsed(emptyList()))))
            val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input))
            val decision = NamespaceSettlementFixtures.transition.decide(command, input, synthetic, null, true, true)
            assertEquals(RecordTransactionDecision.Confirm::class.java, decision.javaClass)
            val receipt = (decision.value as ControlRecordStore.Outcome.Positive).settlement!!
            assertEquals(input.operationId, receipt.operationId)
            assertEquals(obligation, receipt.hasUninterpretable)
            assertEquals(metadata, receipt.hasUninterpretableMetadata)
            assertEquals(obligation || metadata, receipt.blocksProtectedAdmission)
        }
    }
}
