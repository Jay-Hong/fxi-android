package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.auth
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.nullSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settledSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settlement
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.EXTRA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.HOLD
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.RECOVERY
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlRecordStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "control.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }

    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }

    private fun ControlRecordStore.add(kind: ControlKind, json: String): ControlMutation = addition(kind) { id ->
        literal(json)
        set("id", ControlScalar.Text(id))
    }
    private fun ControlRecordStore.addSeal(json: String = seal): CommandRef = prepare(add(ControlKind.SEAL, json))
    private fun confirmed(result: ControlStoreResult): ControlStoreResult.Confirmed {
        assertTrue("$result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    private fun conflict(result: ControlStoreResult, reason: ConflictReason) {
        assertTrue("$result", result is ControlStoreResult.Conflict)
        assertEquals(reason, (result as ControlStoreResult.Conflict).reason)
    }
    private fun strengthen(store: ControlRecordStore): CommandRef = store.prepare(store.edit(ControlKind.DEMAND, node(request)) {
        set("intent", ControlScalar.Text("FORCE_PREMIUM"))
        set("raisedAt", ControlScalar.Integer(5))
    })

    @Test fun allFourAdditionsPersistTogetherAndPreserveOtherKeys() = runBlocking {
        val o = open()
        o.seed()
        val epochs = o.owner.bindOwner("owner")
        o.data.edit { it[EXTRA] = "opaque" }
        val command = o.control.prepare(o.control.add(ControlKind.SEAL, seal.replace("null", "\"owner\"").replace("old", epochs.userAccessEpoch!!)), o.control.add(ControlKind.DEMAND, request),
            o.control.add(ControlKind.HOLD, hold), o.control.add(ControlKind.RECOVERY_INTENT, recovery))
        val writes = o.storage.writes
        val result = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(writes + 1, o.storage.writes)
        assertEquals(4, result.effectiveIds.toSet().size)
        result.effectiveIds.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertEquals("opaque", result.snapshot.record.original[EXTRA])
        assertEquals(epochs, o.owner.load())
        assertEquals(result.snapshot.record.original, open().raw())
    }

    @Test fun preparedInputsAndSeparateLifetimeAndSessionIdsAreStable() = runBlocking {
        val o = open()
        o.seed()
        var next = 0L
        val c = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++next) })
        val life = c.newLifetimeId()
        val session1 = c.newSessionId()
        val session2 = c.newSessionId()
        assertEquals(3, setOf(life.value, session1, session2).size)
        var builds = 0
        val addition = c.addition(ControlKind.RECOVERY_INTENT) { id ->
            builds++
            literal(recovery)
            set("id", ControlScalar.Text(id))
            set("sessionId", ControlScalar.Text(session1))
        }
        val command = c.prepare(addition)
        val first = confirmed(c.execute(command))
        val second = confirmed(c.execute(command))
        assertEquals(first.effectiveIds, second.effectiveIds)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, second.effect)
        assertEquals(RecordTransactionEvidence.LockedFileRead, second.proof.storage)
        assertEquals(1, builds)
        assertEquals(5L, next)
        assertEquals(session1, (second.snapshot.record.arrays.getValue(ControlKind.RECOVERY_INTENT)
            .entries.single() as ControlEntryRead.Interpreted).let { (it.value as RecoveryIntentV1).sessionId })
    }

    @Test fun cleanJoinPreservesLegacyIdAndExactStringsWithoutBarrier() = runBlocking {
        val o = open()
        val raw = "[  $seal  ]"
        o.seed(seal = raw, demand = "[ ]")
        val writes = o.storage.writes
        val result = confirmed(o.control.execute(o.control.addSeal()))
        assertEquals(ConfirmedEffect.JoinedExisting, result.effect)
        assertEquals(listOf("s"), result.effectiveIds)
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.proof.storage)
        assertEquals(raw, result.snapshot.record.original[SEAL])
        assertEquals("[ ]", result.snapshot.record.original[DEMAND])
        assertNull(result.snapshot.record.original[BARRIER])
        assertEquals(writes, o.storage.writes)
    }

    @Test fun concurrentSameKeyCommandsJoinOnePersistedIdentity() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val commands = List(12) { o.control.addSeal() }
        val results = commands.map { async { confirmed(o.control.execute(it)) } }.map { it.await() }
        assertEquals(1, results.count { it.effect == ConfirmedEffect.AppliedThisAttempt })
        assertEquals(11, results.count { it.effect == ConfirmedEffect.JoinedExisting })
        assertEquals(1, results.flatMap { it.effectiveIds }.toSet().size)
        assertEquals(1, (ControlPayloadCodec().decode(open().raw()[SEAL]!!) as PayloadRead.Parsed).entries.size)
    }

    @Test fun twoUnsettledSameKeySealsAreAnAmbiguousJoin() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal,${seal.replace("\"s\"", "\"second\"")}]")
        val before = o.raw()
        conflict(o.control.execute(o.control.addSeal()), ConflictReason.AmbiguousSealKey)
        assertEquals(before, o.raw())
    }

    @Test fun independentLossAfterSettlementGetsANewIdentity() = runBlocking {
        val o = open()
        o.seed(seal = "[$settledSeal]")
        val result = confirmed(o.control.execute(o.control.addSeal(nullSeal)))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertNotEquals("s", result.effectiveIds.single())
        assertEquals(2, result.snapshot.record.arrays.getValue(ControlKind.SEAL).entries.size)
    }

    @Test fun opaqueSealCannotBeNarrowedByPartlyReadableKey() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal,{\"id\":\"future\",\"ownerUid\":\"unrelated\"}]")
        conflict(o.control.execute(o.control.addSeal()), ConflictReason.UninterpretableTarget)
    }

    @Test fun newIdCollisionInAnotherArrayIsNotRetriedWithANewId() = runBlocking {
        val o = open()
        o.seed()
        val command = o.control.addSeal()
        val id = (command.actions.single() as ControlMutation.Add).proposedId
        o.data.edit { it[HOLD] = "[{\"id\":\"$id\",\"future\":true}]" }
        repeat(2) { conflict(o.control.execute(command), ConflictReason.IdCollision) }
        assertEquals("[]", o.raw()[SEAL])
        assertEquals(id, (command.actions.single() as ControlMutation.Add).proposedId)
    }

    @Test fun sameIdAndJsonWithoutCommandEvidenceIsACollision() = runBlocking {
        val o = open()
        o.seed()
        val command = o.control.addSeal()
        val addition = command.actions.single() as ControlMutation.Add
        val json = ControlObligationFixtures.text((addition.built as ControlWriteResult.Written).node)
        o.data.edit { it[SEAL] = json }
        conflict(o.control.execute(command), ConflictReason.IdCollision)
    }

    @Test fun duplicateIdsAcrossArraysMakeAnExistingTargetUnusable() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]", hold = "[{\"id\":\"d\"}]")
        conflict(o.control.execute(strengthen(o.control)), ConflictReason.IdCollision)
    }

    @Test fun healthyEditPreservesOpaqueSiblingsAndUntouchedPayloadBytes() = runBlocking {
        val o = open()
        val sibling = "{\"id\":\"future\",\"number\":1e400,\"negativeZero\":-0}"
        o.seed(demand = "[$sibling,$request,17]", hold = "[  null  ]", recovery = "[ ]")
        val result = confirmed(o.control.execute(strengthen(o.control)))
        assertTrue(result.snapshot.record.hasUninterpretable)
        assertTrue(result.snapshot.record.original[DEMAND]!!.contains(sibling))
        assertEquals("[  null  ]", result.snapshot.record.original[HOLD])
        assertEquals("[ ]", result.snapshot.record.original[RECOVERY])
        assertEquals(3, result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.size)
        val entries = result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries
        assertEquals("d", (entries[1] as ControlEntryRead.Interpreted).value.id)
        assertEquals("17", (entries[2] as ControlEntryRead.Uninterpretable).original.let {
            (it as PayloadEntry.Uninterpretable).raw.toString()
        })
    }

    @Test fun editResolvesLatestIdAfterArrayReordering() = runBlocking {
        val o = open()
        o.seed(demand = "[$request,$emptyGuard]")
        val command = strengthen(o.control)
        o.data.edit { it[DEMAND] = "[$emptyGuard,$request]" }
        val result = confirmed(o.control.execute(command))
        val entries = result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries
        assertEquals("g", (entries[0] as ControlEntryRead.Interpreted).value.id)
        assertEquals("d", (entries[1] as ControlEntryRead.Interpreted).value.id)
    }

    @Test fun changedTargetPreimageConflictsInsteadOfOverwriting() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]")
        val command = strengthen(o.control)
        o.data.edit { it[DEMAND] = "[${request.replace("\"A\"", "\"B\"")}]" }
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
    }

    @Test fun missingEditTargetConflicts() = runBlocking {
        val o = open()
        o.seed()
        conflict(o.control.execute(strengthen(o.control)), ConflictReason.TargetMissing)
    }

    @Test fun futureFieldOnExistingTargetConflicts() = runBlocking {
        val o = open()
        o.seed(demand = "[${request.dropLast(1)},\"future\":1}]")
        conflict(o.control.execute(strengthen(o.control)), ConflictReason.UninterpretableTarget)
    }

    @Test fun weakeningRequestIsRejectedWithoutNewUncertaintyOrWrite() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]")
        val before = o.raw()
        val writes = o.storage.writes
        val command = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(3)) })
        val result = o.control.execute(command)
        assertTrue(result is ControlStoreResult.Rejected)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        assertEquals(before, o.raw())
        assertEquals(writes, o.storage.writes)
    }

    @Test fun sealSettlementIsRejectedAtBothAdditionAndEditBoundaries() = runBlocking {
        val o = open()
        o.seed(seal = "[$nullSeal]")
        val adding = o.control.prepare(o.control.add(ControlKind.SEAL, settledSeal))
        val editing = o.control.prepare(o.control.edit(ControlKind.SEAL, node(nullSeal)) {
            createChild("settlement") { literal(settlement) }
        })
        for (command in listOf(adding, editing)) assertTrue(o.control.execute(command) is ControlStoreResult.Rejected)
        assertEquals("[$nullSeal]", o.raw()[SEAL])
    }

    @Test fun secondGuardIsRejectedByCompleteCandidateValidation() = runBlocking {
        val o = open()
        o.seed(demand = "[$emptyGuard]")
        val command = o.control.prepare(o.control.add(ControlKind.DEMAND, emptyGuard))
        val result = o.control.execute(command)
        assertTrue(result is ControlStoreResult.Rejected)
        assertEquals(RejectionReason.InvalidRequest("candidate introduces an id or guard-cardinality collision"),
            (result as ControlStoreResult.Rejected).reason)
        assertEquals("[$emptyGuard]", o.raw()[DEMAND])
    }

    @Test fun authAndFixedFloorEditsPreserveEachOthersEvidence() = runBlocking {
        val o = open()
        o.seed(demand = "[$emptyGuard]")
        val authCommand = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(emptyGuard)) {
            createChild("auth") { literal(auth) }
        })
        val withAuth = confirmed(o.control.execute(authCommand)).snapshot.record.arrays.getValue(ControlKind.DEMAND)
            .entries.single() as ControlEntryRead.Interpreted
        val floorCommand = o.control.prepare(o.control.recordFloor(withAuth.original, BootReading("boot", 50), 100,
            LifetimeId("floor-life")))
        val first = confirmed(o.control.execute(floorCommand))
        val second = confirmed(o.control.execute(floorCommand))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, second.effect)
        assertEquals(first.snapshot.record.original, second.snapshot.record.original)
        val saved = (second.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted)
            .value as ScheduleGuardV1
        assertEquals("life", saved.auth!!.originLifetimeId.value)
        assertEquals(FloorV1("boot", 50, 100, LifetimeId("floor-life")), saved.floor)
    }

    @Test fun multiPayloadOversizeRejectsEveryChangeWithUtf8Count() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val c = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = 300))
        val command = c.prepare(c.add(ControlKind.SEAL, seal), c.add(ControlKind.RECOVERY_INTENT,
            recovery.replace("\"session\"", "\"${"한".repeat(100)}\"")))
        val before = o.raw()
        val result = c.execute(command) as ControlStoreResult.Rejected
        val reason = result.reason as RejectionReason.TooLarge
        assertEquals(ControlPayloadKey.RECOVERY_INTENT, reason.payloadKey)
        assertTrue(reason.bytes > 300)
        assertEquals(300, reason.limit)
        assertEquals(before, open().raw())
    }

    @Test fun missingControlsAreRecoveryEvenWithExistingEpochs() = runBlocking {
        val o = open()
        o.owner.bindOwner("A")
        val before = o.raw()
        val result = o.control.execute(o.control.addSeal()) as ControlStoreResult.RecoveryRequired
        assertEquals(RecoveryReason.MigrationOrRecovery, result.reason)
        assertEquals(before, o.raw())
        assertNull(o.raw()[SCHEMA])
    }

    @Test fun incompleteFutureAndMistypedRecordsAreNeverNormalized() = runBlocking {
        val o = open()
        val command = o.control.addSeal()
        for (variant in 0..5) {
            o.seed()
            o.data.edit { when (variant) {
                0 -> it.remove(HOLD)
                1 -> it[SCHEMA] = 3
                2 -> it[stringPreferencesKey("control_schema")] = "1"
                3 -> it[DEMAND] = "malformed"
                4 -> it.remove(SCHEMA)
                else -> it.remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE))
            } }
            val before = o.raw()
            val result = o.control.execute(command) as ControlStoreResult.RecoveryRequired
            assertEquals(RecoveryReason.UnreadableRecord, result.reason)
            assertEquals(before, o.raw())
        }
    }

    @Test fun corruptionReplacementIsRecoveryAndNeverEmptyControlPromotion() = runBlocking {
        val o = open()
        o.seed()
        file.writeBytes(byteArrayOf(0x0f))
        val result = o.control.execute(o.control.addSeal()) as ControlStoreResult.RecoveryRequired
        assertEquals(RecoveryReason.MigrationOrRecovery, result.reason)
        assertNull(result.observation.original[SCHEMA])
        assertEquals(1, o.owner.load().pendingPurges.size)
    }

    @Test fun failureBeforeWriteRemainsUnconfirmedAndSameCommandCanRetry() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.before = true
        val result = o.control.execute(command) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, result.reason)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, result.phase)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertEquals("[]", o.raw()[SEAL])
        val retry = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, retry.effect)
        assertTrue(retry.localUnresolvedCommands.isEmpty())
        assertEquals(1L, retry.snapshot.record.original[BARRIER])
    }

    @Test fun failureAfterCacheUpdateDoesNotConfirmAndLegacyLoadRepairsIt() = runBlocking {
        val o = open()
        o.seed()
        val before = o.owner.bindOwner("A")
        val command = o.control.addSeal(seal.replace("null", "\"A\"").replace("old", before.userAccessEpoch!!))
        o.storage.after = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        assertNotEquals("[]", o.raw()[SEAL])
        assertEquals(before, o.owner.load())
        assertEquals("[]", o.raw()[SEAL])
        val other = o.control.prepare()
        assertEquals(setOf(command), o.control.execute(other).localUnresolvedCommands)
        assertEquals("[]", open().raw()[SEAL])
    }

    @Test fun joinAfterLegacyFailureUsesOneSharedBarrierAndThenLoadsWithoutWrites() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val before = o.owner.bindOwner("A")
        o.storage.after = true
        assertTrue(runCatching { o.owner.beginRotation(true, true) }.isFailure)
        val writes = o.storage.writes
        val result = confirmed(o.control.execute(o.control.addSeal()))
        assertEquals(ConfirmedEffect.JoinedExisting, result.effect)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(writes + 1, o.storage.writes)
        assertEquals(1L, result.snapshot.record.original[BARRIER])
        repeat(2) { assertEquals(before, o.owner.load()) }
        assertEquals(writes + 1, o.storage.writes)
    }

    @Test fun rejectionConflictAndRecoveryDoNotClearTheOwnersFailedReadBack() = runBlocking {
        val o = open()
        for (negative in 0..2) {
            o.seed()
            val before = o.owner.bindOwner("A")
            if (negative == 2) o.data.edit { it.remove(SCHEMA) }
            o.storage.after = true
            assertTrue(runCatching { o.owner.beginRotation(true, true) }.isFailure)
            val writes = o.storage.writes
            val command = if (negative == 0) o.control.prepare() else strengthen(o.control)
            val result = o.control.execute(command)
            assertTrue(when (negative) {
                0 -> result is ControlStoreResult.Rejected
                1 -> result is ControlStoreResult.Conflict
                else -> result is ControlStoreResult.RecoveryRequired
            })
            assertEquals(writes, o.storage.writes)
            assertEquals(before, o.owner.load())
            assertEquals(writes + 1, o.storage.writes)
        }
    }

    @Test fun failedJoinBarrierKeepsAdoptedIdAndUncertainty() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        o.owner.bindOwner("A")
        o.storage.after = true
        assertTrue(runCatching { o.owner.beginRotation(true, true) }.isFailure)
        val command = o.control.addSeal()
        o.storage.after = true
        val result = o.control.execute(command) as ControlStoreResult.Unconfirmed
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertEquals("s", o.control.checkpoint(command)!!.targets.single()!!.id)
        val retry = confirmed(o.control.execute(command))
        assertEquals(listOf("s"), retry.effectiveIds)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, retry.effect)
    }

    @Test fun laterRejectionPreservesEarlierUncertaintyForTheSameCommand() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val c = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = 300))
        val command = c.addSeal()
        o.storage.before = true
        assertTrue(c.execute(command) is ControlStoreResult.Unconfirmed)
        o.data.edit { it[SEAL] = "[${seal.replace("old", "other-" + "x".repeat(160))}]" }
        val rejected = c.execute(command) as ControlStoreResult.Rejected
        assertTrue(rejected.reason is RejectionReason.TooLarge)
        assertEquals(setOf(command), rejected.localUnresolvedCommands)
        assertNull(o.raw()[BARRIER])
    }

    @Test fun confirmingOneCommandDoesNotSettleAnotherCommandsUncertainty() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val first = o.control.addSeal()
        o.storage.before = true
        assertTrue(o.control.execute(first) is ControlStoreResult.Unconfirmed)
        o.data.edit { it[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.KRX_EPOCH] = "different" }
        val second = o.control.addSeal(seal.replace("USER", "CAPABILITY").replace("old", "different"))
        val result = confirmed(o.control.execute(second))
        assertEquals(setOf(first), result.localUnresolvedCommands)
    }

    @Test fun facadeRecreationRetainsOwnerLocalHistoryAndAdoption() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        val facade = ControlRecordStore(o.owner)
        assertNotNull(facade.checkpoint(command))
        val rejected = facade.execute(facade.prepare())
        assertEquals(setOf(command), rejected.localUnresolvedCommands)
        assertTrue(facade.execute(command) is ControlStoreResult.Confirmed)
    }

    @Test fun previousReferenceWithoutEvidenceIsHistoryUnavailable() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.addSeal()
        confirmed(o.control.execute(command))
        val reopened = open()
        val result = reopened.control.confirmPrevious(command) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.HistoryUnavailable, result.reason)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertNull(reopened.raw()[BARRIER])
    }

    @Test fun previousCompleteCheckpointConfirmsSafePostconditionWithoutCausalClaim() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.addSeal()
        confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open()
        val result = confirmed(reopened.control.confirmPrevious(command, checkpoint))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
        assertEquals(listOf("s"), result.effectiveIds)
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.proof.storage)
    }

    @Test fun incompleteCheckpointCannotAdoptCurrentSameKeySeal() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.addSeal()
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open()
        val result = reopened.control.confirmPrevious(command, checkpoint) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.HistoryUnavailable, result.reason)
        assertEquals("[$seal]", reopened.raw()[SEAL])
    }

    @Test fun previousConfirmationCannotRecreateMissingAddition() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open()
        conflict(reopened.control.confirmPrevious(command, checkpoint), ConflictReason.TargetMissing)
        assertEquals("[]", reopened.raw()[SEAL])
    }

    @Test fun previousReferenceCannotBeExecutedAsANewCommand() = runBlocking {
        val o = open()
        o.seed()
        val command = o.control.addSeal()
        val reopened = open()
        val result = reopened.control.execute(command) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.HistoryUnavailable, result.reason)
        assertEquals("[]", reopened.raw()[SEAL])
    }

    @Test fun recoveryClassificationPrecedesMissingHistory() = runBlocking {
        val o = open()
        val command = o.control.addSeal()
        val result = open().control.confirmPrevious(command)
        assertTrue(result is ControlStoreResult.RecoveryRequired)
    }

    @Test fun cancelledAdditionThatLandsRequiresSameCommandConfirmation() = runBlocking { cancelledAddition(false) }
    @Test fun cancelledAdditionThatFailsRetriesWithTheSameId() = runBlocking { cancelledAddition(true) }

    private suspend fun cancelledAddition(fail: Boolean) = kotlinx.coroutines.coroutineScope {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        val pause = ControlStoreTestStorage.Pause()
        o.storage.pause = pause
        o.storage.after = fail
        var returned = false
        val writer = launch { o.control.execute(command); returned = true }
        withTimeout(10_000) { pause.reached.await() }
        writer.cancelAndJoin()
        assertFalse(returned)
        assertTrue(writer.isCancelled)
        val adopted = o.control.checkpoint(command)!!.targets.single()!!.id
        val retry = async(start = CoroutineStart.UNDISPATCHED) { o.control.execute(command) }
        pause.release.complete(Unit)
        val result = confirmed(withTimeout(10_000) { retry.await() })
        assertEquals(listOf(adopted), result.effectiveIds)
        assertEquals(if (fail) ConfirmedEffect.AppliedThisAttempt else ConfirmedEffect.PostconditionConfirmed, result.effect)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(result.snapshot.record.original, open().raw())
    }

    @Test fun cancelledJoinCannotMoveToReplacementSameKeySeal() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        o.owner.bindOwner("A")
        o.storage.after = true
        assertTrue(runCatching { o.owner.beginRotation(true, true) }.isFailure)
        val command = o.control.addSeal()
        val pause = ControlStoreTestStorage.Pause()
        o.storage.pause = pause
        val writer = launch { o.control.execute(command) }
        withTimeout(10_000) { pause.reached.await() }
        writer.cancelAndJoin()
        assertTrue(writer.isCancelled)
        assertEquals("s", o.control.checkpoint(command)!!.targets.single()!!.id)
        pause.release.complete(Unit)
        o.data.edit { it[SEAL] = "[${seal.replace("\"s\"", "\"replacement\"")}]" }
        val result = o.control.execute(command)
        conflict(result, ConflictReason.TargetMissing)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertEquals("s", (result as ControlStoreResult.Conflict).expected.effectiveIds.single())
    }

    @Test fun checkpointDoesNotTreatSettledAdoptedSealAsJoined() = runBlocking {
        val o = open()
        o.seed(seal = "[$nullSeal]")
        val command = o.control.addSeal(nullSeal)
        confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        o.data.edit { it[SEAL] = "[$settledSeal]" }
        conflict(open().control.confirmPrevious(command, checkpoint), ConflictReason.TargetChanged)
    }

    @Test fun completedCommandDoesNotResurrectRemovedObligation() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        confirmed(o.control.execute(command))
        o.data.edit { it[SEAL] = "[]" }
        conflict(o.control.execute(command), ConflictReason.TargetMissing)
        assertEquals("[]", o.raw()[SEAL])
    }

    @Test fun corruptionReplacementFailureBeforeDecisionHasNoObservation() = runBlocking {
        val o = open()
        o.seed()
        file.writeBytes(byteArrayOf(0x0f))
        o.storage.after = true
        val command = o.control.addSeal()
        val result = o.control.execute(command) as ControlStoreResult.Unconfirmed
        assertEquals(ControlAttemptPhase.ReadingSnapshot, result.phase)
        assertNull(result.lastObservation)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertTrue(o.control.execute(command) is ControlStoreResult.RecoveryRequired)
        assertEquals(1, o.owner.load().pendingPurges.size)
    }

    @Test fun unexpectedExceptionPropagatesInsteadOfBecomingRejected() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.unexpected = true
        val failure = runCatching { o.control.execute(command) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals("unexpected implementation failure", failure?.message)
        assertEquals(setOf(command), o.control.execute(o.control.prepare()).localUnresolvedCommands)
        val retried = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, retried.effect)
        assertTrue(retried.localUnresolvedCommands.isEmpty())
    }

    @Test fun aFailedNewSealCannotDuplicateOrAdoptALaterSameKeySeal() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val first = o.control.addSeal()
        o.storage.before = true
        assertTrue(o.control.execute(first) is ControlStoreResult.Unconfirmed)
        val fixedId = o.control.checkpoint(first)!!.targets.single()!!.id
        val second = confirmed(o.control.execute(o.control.addSeal()))
        assertNotEquals(fixedId, second.effectiveIds.single())
        val result = o.control.execute(first)
        conflict(result, ConflictReason.TargetChanged)
        assertEquals(setOf(first), result.localUnresolvedCommands)
        assertEquals(fixedId, o.control.checkpoint(first)!!.targets.single()!!.id)
        assertEquals(second.snapshot.record.original, o.raw())
    }

    @Test fun twoNewSameKeySealsInOneBatchAreRejectedAtomically() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add(ControlKind.SEAL, seal), o.control.add(ControlKind.SEAL, seal))
        assertTrue(o.control.execute(command) is ControlStoreResult.Rejected)
        assertEquals("[]", o.raw()[SEAL])
        assertNull(o.raw()[BARRIER])
    }

    @Test fun aPendingWritePublishesNoResultAndSerializesLegacyLoad() = runBlocking {
        val o = open()
        o.seed()
        val before = o.owner.bindOwner("A")
        val pause = ControlStoreTestStorage.Pause()
        o.storage.pause = pause
        val write = async { o.control.execute(o.control.addSeal(seal.replace("null", "\"A\"").replace("old", before.userAccessEpoch!!))) }
        withTimeout(10_000) { pause.reached.await() }
        val load = async(start = CoroutineStart.UNDISPATCHED) { o.owner.load() }
        try {
            assertFalse(write.isCompleted)
            assertFalse(load.isCompleted)
        } finally {
            pause.release.complete(Unit)
        }
        val result = confirmed(withTimeout(10_000) { write.await() })
        assertEquals(before, withTimeout(10_000) { load.await() })
        assertEquals(result.snapshot.record.original, open().raw())
    }

    @Test fun changedCandidateRepairsLegacyFailureInTheSameSingleWrite() = runBlocking {
        val o = open()
        o.seed()
        val before = o.owner.bindOwner("A")
        o.storage.after = true
        assertTrue(runCatching { o.owner.beginRotation(true, true) }.isFailure)
        val writes = o.storage.writes
        val result = confirmed(o.control.execute(o.control.addSeal(seal.replace("null", "\"A\"").replace("old", before.userAccessEpoch!!))))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(1L, result.snapshot.record.original[BARRIER])
        assertEquals(writes + 1, o.storage.writes)
        assertEquals(before, o.owner.load())
        assertEquals(writes + 1, o.storage.writes)
    }

    @Test fun recoveryDoesNotSettleAnEarlierUnconfirmedCommand() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        o.data.edit { it.remove(SCHEMA) }
        val result = o.control.execute(command)
        assertTrue(result is ControlStoreResult.RecoveryRequired)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertNull(o.raw()[BARRIER])
    }

    @Test fun resultsRetainDetachedUnmodifiableTrackingSnapshots() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.addSeal()
        o.storage.before = true
        val failed = o.control.execute(command)
        confirmed(o.control.execute(command))
        assertEquals(setOf(command), failed.localUnresolvedCommands)
        assertThrows(UnsupportedOperationException::class.java) {
            (failed.localUnresolvedCommands as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (command.actions as MutableList).clear()
        }
        Unit
    }

    @Test fun idIssuanceCannotBeOverriddenByAdditionInput() = runBlocking {
        val o = open()
        o.seed()
        val command = o.control.prepare(o.control.addition(ControlKind.SEAL) { literal(seal) })
        assertTrue(o.control.execute(command) is ControlStoreResult.Rejected)
        assertEquals("[]", o.raw()[SEAL])
    }

    @Test fun aGuardFloorRecapturePreservesTheLargerRemainingWait() = runBlocking {
        val o = open()
        o.seed(demand = "[$guard]")
        val command = o.control.prepare(o.control.recordFloor(node(guard), BootReading("boot", 20_000), 100,
            LifetimeId("new-life")))
        val result = confirmed(o.control.execute(command))
        val saved = (result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted)
            .value as ScheduleGuardV1
        assertEquals(FloorV1("boot", 20_000, 20_000, LifetimeId("new-life")), saved.floor)
        assertEquals(node(guard).toPayloadEntry().fields["auth"], (result.snapshot.record.arrays.getValue(ControlKind.DEMAND)
            .entries.single() as ControlEntryRead.Interpreted).original.toPayloadEntry().fields["auth"])
    }

    @Test fun exactEncodedByteLimitIsAcceptedAndOneByteLessIsRejected() = runBlocking {
        val o = open()
        o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
            literal(seal.replace("old", "o".repeat(300))); set("id", ControlScalar.Text(id))
        })
        o.data.edit { it[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.USER_EPOCH] = "o".repeat(300) }
        val addition = command.actions.single() as ControlMutation.Add
        val text = ControlObligationFixtures.text((addition.built as ControlWriteResult.Written).node)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val small = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = bytes - 1))
        val result = small.execute(command) as ControlStoreResult.Rejected
        assertEquals(RejectionReason.TooLarge(ControlPayloadKey.forKind(ControlKind.SEAL), bytes, bytes - 1), result.reason)
        val exact = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = bytes))
        assertEquals(text, confirmed(exact.execute(command)).snapshot.record.original[SEAL])
    }

    @Test fun rejectedBatchStillKeepsItsPreviouslyAdoptedJoinTarget() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val invalid = o.control.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(-1)) }
        val command = o.control.prepare(o.control.add(ControlKind.SEAL, seal), invalid)
        assertTrue(o.control.execute(command) is ControlStoreResult.Rejected)
        assertEquals("s", o.control.checkpoint(command)!!.targets.first()!!.id)
        o.data.edit { it[SEAL] = "[${seal.replace("\"s\"", "\"new\"")}]" }
        conflict(o.control.execute(command), ConflictReason.TargetMissing)
        assertEquals("s", o.control.checkpoint(command)!!.targets.first()!!.id)
    }

    @Test fun anAdoptedIdWithADifferentTargetIsAConflict() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.addSeal()
        confirmed(o.control.execute(command))
        o.data.edit { it[SEAL] = "[${seal.replace("old", "new-epoch")}]" }
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
    }

    @Test fun evenAnUnusedProposedIdCollisionCannotBeHiddenByJoiningAnotherId() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.addSeal()
        val proposedId = (command.actions.single() as ControlMutation.Add).proposedId
        o.data.edit { it[RECOVERY] = "[{\"id\":\"$proposedId\",\"future\":1}]" }
        val before = o.raw()
        conflict(o.control.execute(command), ConflictReason.IdCollision)
        assertNull(o.control.checkpoint(command)!!.targets.single())
        assertEquals(before, o.raw())
    }

    @Test fun overlappingPreviousCommandConfirmationIsRefusedWithoutLosingUncertainty() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        o.owner.bindOwner("A")
        val command = o.control.addSeal()
        confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open()
        reopened.storage.after = true
        assertTrue(runCatching { reopened.owner.beginRotation(true, true) }.isFailure)
        val pause = ControlStoreTestStorage.Pause()
        reopened.storage.pause = pause
        val first = async { reopened.control.confirmPrevious(command, checkpoint) }
        withTimeout(10_000) { pause.reached.await() }
        try {
            val second = runCatching { withTimeout(1_000) { reopened.control.confirmPrevious(command, checkpoint) } }
            // TimeoutCancellationException also extends IllegalStateException; it is not the lease refusal.
            assertEquals(IllegalStateException::class.java, second.exceptionOrNull()?.javaClass)
            assertEquals("the same command is already executing", second.exceptionOrNull()?.message)
            assertFalse(first.isCompleted)
        } finally {
            pause.release.complete(Unit)
        }
        val result = confirmed(withTimeout(10_000) { first.await() })
        assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
        assertTrue(result.localUnresolvedCommands.isEmpty())
    }
}
