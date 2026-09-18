package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Isolated history guards and the complete-candidate boundary; real DataStore for facade tests. */
class ControlRecordStoreMutationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "control.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }

    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }

    private fun ControlRecordStore.sealAddition(): ControlMutation = addition(ControlKind.SEAL) { id ->
        literal(seal)
        set("id", ControlScalar.Text(id))
    }

    private fun ControlRecordStore.strongerRequest(): ControlMutation = edit(ControlKind.DEMAND, node(request)) {
        set("intent", ControlScalar.Text("FORCE_PREMIUM"))
        set("raisedAt", ControlScalar.Integer(5))
    }

    private fun confirmed(result: ControlStoreResult): ControlStoreResult.Confirmed {
        assertTrue("$result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }

    private fun historyUnavailable(result: ControlStoreResult) {
        assertTrue("$result", result is ControlStoreResult.Unconfirmed)
        assertEquals(UnconfirmedReason.HistoryUnavailable, (result as ControlStoreResult.Unconfirmed).reason)
    }

    private fun conflict(result: ControlStoreResult, reason: ConflictReason) {
        assertTrue("$result", result is ControlStoreResult.Conflict)
        assertEquals(reason, (result as ControlStoreResult.Conflict).reason)
    }

    /** A size rejection publishes a complete target but never requests confirmation. */
    private suspend fun rejectedAddition(o: ControlStoreTestStorage): Pair<CommandRef, ControlMutation> {
        val small = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = 100))
        val addition = small.sealAddition()
        val command = small.prepare(addition)
        val result = small.execute(command)
        assertTrue(result is ControlStoreResult.Rejected)
        assertTrue((result as ControlStoreResult.Rejected).reason is RejectionReason.TooLarge)
        val checkpoint = small.checkpoint(command)!!
        assertEquals(command.actions.size, checkpoint.targets.size)
        assertTrue(checkpoint.targets.all { it != null })
        assertFalse(checkpoint.confirmationRequested)
        return command to addition
    }

    @Test fun completeUnrequestedCheckpointRemainsHistoryUnavailable() = runBlocking {
        val o = open()
        o.seed()
        val (command, addition) = rejectedAddition(o)
        val checkpoint = o.control.checkpoint(command)!!
        // Reuse the immutable prepared operation in an independent command, with the normal limit.
        confirmed(o.control.execute(o.control.prepare(addition)))
        val reopened = open()
        val before = reopened.raw()
        historyUnavailable(reopened.control.confirmPrevious(command, checkpoint)) // C02 alone.
        assertEquals(before, reopened.raw())
    }

    @Test fun requestedCheckpointWithMissingTargetCannotAdoptCurrentSeal() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.sealAddition())
        // Explicit incomplete restore input: correct ref, true request flag, correct length, one null.
        val checkpoint = ControlCommandCheckpoint(command, listOf(null), confirmationRequested = true)
        val reopened = open()
        val before = reopened.raw()
        historyUnavailable(reopened.control.confirmPrevious(command, checkpoint)) // C03 alone.
        assertEquals(before, reopened.raw())
    }

    @Test fun checkpointWithExtraTargetsIsHistoryUnavailable() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.sealAddition())
        confirmed(o.control.execute(command))
        val good = o.control.checkpoint(command)!!
        val extra = ControlCommandCheckpoint(command, good.targets + good.targets, confirmationRequested = true)
        assertTrue(extra.targets.all { it != null })
        val reopened = open()
        historyUnavailable(reopened.control.confirmPrevious(command, extra)) // C04 alone, too long.
    }

    @Test fun checkpointWithTooFewTargetsIsHistoryUnavailable() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.sealAddition())
        confirmed(o.control.execute(command))
        val short = ControlCommandCheckpoint(command, emptyList(), confirmationRequested = true)
        assertFalse(short.targets.any { it == null })
        val reopened = open()
        historyUnavailable(reopened.control.confirmPrevious(command, short)) // C04 alone, too short.
    }

    @Test fun duplicateIdenticalEditsAreRejectedAtomically() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]")
        val edit = o.control.strongerRequest()
        val command = o.control.prepare(edit, edit)
        val before = o.raw()
        val writes = o.storage.writes
        val result = o.control.execute(command)
        assertTrue("$result", result is ControlStoreResult.Rejected)
        assertEquals(RejectionReason.InvalidRequest("operations must have distinct effective targets"),
            (result as ControlStoreResult.Rejected).reason)
        assertEquals(before, o.raw())
        assertEquals(writes, o.storage.writes)
    }

    @Test fun adoptedIdInDifferentArrayIsAnIdCollision() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.sealAddition())
        assertEquals(listOf("s"), confirmed(o.control.execute(command)).effectiveIds)
        // A supported new snapshot has that id in a different, fully interpretable array.
        o.seed(recovery = "[${recovery.replace("\"r\"", "\"s\"")}]")
        val before = o.raw()
        conflict(o.control.execute(command), ConflictReason.IdCollision)
        assertEquals(before, o.raw())
    }

    @Test fun retryCannotIgnoreAnOpaqueSealAddedAfterFirstAttempt() = runBlocking {
        val o = open()
        o.seed()
        val command = o.control.prepare(o.control.sealAddition())
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        assertNotNull(o.control.checkpoint(command)!!.targets.single())
        o.data.edit { it[SEAL] = "[{\"id\":\"future\",\"ownerUid\":\"other\"}]" }
        val before = o.raw()
        val result = o.control.execute(command)
        conflict(result, ConflictReason.UninterpretableTarget)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertEquals(before, o.raw())
    }

    @Test fun rejectedAdditionCannotClaimAnotherCommandsIdenticalPostcondition() = runBlocking {
        val o = open()
        o.seed()
        val (command, addition) = rejectedAddition(o)
        val other = confirmed(o.control.execute(o.control.prepare(addition)))
        assertEquals(o.control.checkpoint(command)!!.targets.single()!!.id, other.effectiveIds.single())
        val before = o.raw()
        conflict(o.control.execute(command), ConflictReason.IdCollision) // Not the initial-id gate: target is fixed.
        assertEquals(before, o.raw())
    }

    @Test fun canonicalNoopEditIsPostconditionConfirmedWithoutWrite() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]")
        val command = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) {})
        val writes = o.storage.writes
        val result = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.proof.storage)
        assertEquals(writes, o.storage.writes)
    }

    @Test fun noopEditPreservesPayloadWhitespace() = runBlocking {
        val o = open()
        val raw = "[  $request  ]"
        o.seed(demand = raw)
        val command = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) {})
        val result = confirmed(o.control.execute(command))
        assertEquals(raw, result.snapshot.record.original[DEMAND])
        assertEquals(raw, open().raw()[DEMAND])
    }

    @Test fun firstEditCannotClaimAnotherCommandsIdenticalPostcondition() = runBlocking {
        val o = open()
        o.seed(demand = "[$request]")
        val edit = o.control.strongerRequest()
        val first = o.control.prepare(edit)
        val second = o.control.prepare(edit)
        confirmed(o.control.execute(second))
        assertFalse(o.control.checkpoint(first)!!.confirmationRequested)
        val before = o.raw()
        conflict(o.control.execute(first), ConflictReason.TargetChanged)
        assertEquals(before, o.raw())
    }

    @Test fun mixedBatchAppliedEffectCoversAdditionJoinAndUnchangedEdit() = runBlocking {
        val o = open()
        o.seed(seal = "[$seal]", demand = "[$request]")
        val addition = o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(recovery)
            set("id", ControlScalar.Text(id))
        }
        val command = o.control.prepare(o.control.sealAddition(), addition,
            o.control.edit(ControlKind.DEMAND, node(request)) {})
        val result = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(listOf("s", (addition as ControlMutation.Add).proposedId, "d"), result.effectiveIds)
        assertEquals("[$seal]", result.snapshot.record.original[SEAL])
        assertEquals("[$request]", result.snapshot.record.original[DEMAND])
        assertEquals(ConfirmedEffect.PostconditionConfirmed, confirmed(o.control.execute(command)).effect)
    }

    @Test fun completeCandidateKindCheckRejectsCrossedActionTargets() = runBlocking {
        val o = open()
        o.seed()
        val sealAction = o.control.sealAddition() as ControlMutation.Add
        val recoveryAction = o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(recovery)
            set("id", ControlScalar.Text(id))
        } as ControlMutation.Add
        val command = o.control.prepare(sealAction, recoveryAction)
        val sealTarget = ControlCommandTarget(sealAction.proposedId,
            (sealAction.built as ControlWriteResult.Written).node, false)
        val recoveryTarget = ControlCommandTarget(recoveryAction.proposedId,
            (recoveryAction.built as ControlWriteResult.Written).node, false)
        val candidate = o.raw().toMutablePreferences().apply {
            this[SEAL] = ControlObligationFixtures.text(sealTarget.postcondition)
            this[ControlStoreTestStorage.RECOVERY] = ControlObligationFixtures.text(recoveryTarget.postcondition)
        }
        val complete = ControlRecordReader().read(candidate) as ControlRecordRead.Supported
        // Test the pure completed-candidate boundary directly; do not poison live command history.
        // Both raw postconditions match. Only their association with the action kinds is wrong.
        val before = o.raw()
        val writes = o.storage.writes
        assertNull(o.control.candidateRejection(command, complete,
            listOf(sealTarget.id, recoveryTarget.id), listOf(sealTarget, recoveryTarget)))
        assertEquals(RejectionReason.InvalidRequest("candidate does not preserve the required postcondition"),
            o.control.candidateRejection(command, complete,
                listOf(recoveryTarget.id, sealTarget.id), listOf(recoveryTarget, sealTarget)))
        assertEquals(before, o.raw())
        assertEquals(writes, o.storage.writes)
    }
}
