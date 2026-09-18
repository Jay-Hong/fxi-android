package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.text
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.written
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** r4: independent checkpoint guards, final postconditions, and retained uncertainty semantics. */
class ControlRecordStoreFinalReviewTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "control.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }

    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }

    private fun ControlRecordStore.add(kind: ControlKind = ControlKind.SEAL, raw: String = seal): ControlMutation =
        addition(kind) { id -> literal(raw); set("id", ControlScalar.Text(id)) }

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

    @Test fun previousCheckpointPreservesSameKindActionOrder() = runBlocking {
        val o = open(); o.seed()
        val command = o.control.prepare(
            o.control.add(ControlKind.RECOVERY_INTENT, recovery),
            o.control.add(ControlKind.RECOVERY_INTENT, recovery))
        val applied = confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        assertTrue(checkpoint.confirmationRequested)
        assertEquals(2, checkpoint.targets.filterNotNull().size)
        val reversed = ControlCommandCheckpoint(command, checkpoint.targets.reversed(), true)
        val reopened = open(); val before = reopened.raw(); val writes = reopened.storage.writes

        // Same kind, complete shape, distinct ids and exact stored postconditions; only order differs.
        historyUnavailable(reopened.control.confirmPrevious(command, reversed))
        val correct = confirmed(reopened.control.confirmPrevious(command, checkpoint))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, correct.effect)
        assertEquals(applied.effectiveIds, correct.effectiveIds)
        assertTrue(correct.localUnresolvedCommands.isEmpty())
        assertEquals(before, reopened.raw()); assertEquals(writes, reopened.storage.writes)
    }

    @Test fun previousCheckpointCannotUseTwoJoinsForOneOfTwoRequiredSealKeys() = runBlocking {
        val o = open()
        val secondK1Seal = seal.replace("\"s\"", "\"t\"")
        val k2Seal = seal.replace("old", "other-epoch")
        o.seed(seal = "[$seal,$secondK1Seal]")
        val k1Action = o.control.add() as ControlMutation.Add
        val k2Action = o.control.add(raw = k2Seal) as ControlMutation.Add
        val k1 = (ControlObligations.read(ControlKind.SEAL, written(k1Action.built)) as ControlEntryRead.Interpreted).value as SealV1
        val k2 = (ControlObligations.read(ControlKind.SEAL, written(k2Action.built)) as ControlEntryRead.Interpreted).value as SealV1
        assertNotEquals(k1.key, k2.key)
        val command = o.control.prepare(k1Action, k2Action)
        val supplied = ControlCommandCheckpoint(command, listOf(
            ControlCommandTarget("s", node(seal), true),
            ControlCommandTarget("t", node(secondK1Seal), true)), true)
        val reopened = open(); val before = reopened.raw(); val writes = reopened.storage.writes
        val record = ControlRecordReader().read(before) as ControlRecordRead.Supported
        val seals = record.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals(listOf("s", "t"), seals.map { it.id })
        assertTrue(seals.all { it.key == k1.key && it.settlement == null })
        assertTrue(seals.none { it.key == k2.key })

        // Both supplied joins fit action 0. Action 1's key is absent; kind/id/postcondition gates pass.
        val result = reopened.control.confirmPrevious(command, supplied)
        historyUnavailable(result)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertEquals(before, reopened.raw()); assertEquals(writes, reopened.storage.writes)
    }

    @Test fun liveCheckpointCannotChangeOnlyTheJoinedFlag() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add())
        assertEquals(ConfirmedEffect.AppliedThisAttempt, confirmed(o.control.execute(command)).effect)
        val checkpoint = o.control.checkpoint(command)!!
        val target = checkpoint.targets.single()!!
        assertFalse(target.joined)
        assertTrue(checkpoint.confirmationRequested)
        val supplied = ControlCommandCheckpoint(command,
            listOf(ControlCommandTarget(target.id, target.postcondition, true)), true)
        val before = o.raw(); val writes = o.storage.writes

        // The same id/postcondition is also a valid same-key unsettled join; only local.joined disagrees.
        val result = o.control.confirmPrevious(command, supplied)
        historyUnavailable(result)
        assertEquals(setOf(command), result.localUnresolvedCommands)
        assertSame(target, o.control.checkpoint(command)!!.targets.single())
        assertTrue(o.control.checkpoint(command)!!.confirmationRequested)
        val retry = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, retry.effect)
        assertTrue(retry.localUnresolvedCommands.isEmpty())
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun completeCandidateRejectsOnlyTheWrongPostconditionWithMatchingKindAndId() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val action = o.control.edit(ControlKind.DEMAND, node(request)) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM"))
            set("raisedAt", ControlScalar.Integer(5))
        } as ControlMutation.Edit
        val command = o.control.prepare(action)
        val target = ControlCommandTarget("d", written(action.changed), false)
        val before = o.raw(); val writes = o.storage.writes
        val wrong = ControlRecordReader().read(before) as ControlRecordRead.Supported
        val good = ControlRecordReader().read(before.toMutablePreferences().apply {
            this[DEMAND] = text(target.postcondition)
        }) as ControlRecordRead.Supported

        // Direct pure boundary: one interpreted DEMAND id d in each; only its raw postcondition differs.
        assertNull(o.control.candidateRejection(command, good, listOf("d"), listOf(target)))
        assertEquals(RejectionReason.InvalidRequest("candidate does not preserve the required postcondition"),
            o.control.candidateRejection(command, wrong, listOf("d"), listOf(target)))
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun mismatchingLiveCheckpointRestoresUncertaintyThatConflictPreserves() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        assertTrue(confirmed(o.control.execute(command)).localUnresolvedCommands.isEmpty())
        val target = o.control.checkpoint(command)!!.targets.single()!!
        val replacement = seal.replace("\"s\"", "\"replacement\"")
        o.seed(seal = "[$replacement]")
        val supplied = ControlCommandCheckpoint(command,
            listOf(ControlCommandTarget("replacement", node(replacement), true)), true)
        val before = o.raw(); val writes = o.storage.writes
        val uncertain = o.control.confirmPrevious(command, supplied)
        historyUnavailable(uncertain)
        assertEquals(setOf(command), uncertain.localUnresolvedCommands)
        assertSame(target, o.control.checkpoint(command)!!.targets.single())
        val missing = o.control.execute(command)
        conflict(missing, ConflictReason.TargetMissing)
        assertEquals(setOf(command), missing.localUnresolvedCommands)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        val unrelated = o.control.prepare(o.control.add(ControlKind.RECOVERY_INTENT, recovery))
        assertEquals(setOf(command), confirmed(o.control.execute(unrelated)).localUnresolvedCommands)
    }

    @Test fun missingOwnSealReportsTheBlockerForReapplicationAndAbsenceForConfirmation() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add())
        o.storage.before = true
        val uncertain = o.control.execute(command)
        assertTrue("$uncertain", uncertain is ControlStoreResult.Unconfirmed)
        assertEquals(UnconfirmedReason.StorageFailure, (uncertain as ControlStoreResult.Unconfirmed).reason)
        val checkpoint = o.control.checkpoint(command)!!
        confirmed(o.control.execute(o.control.prepare(o.control.add())))
        val before = o.raw(); val writes = o.storage.writes
        val retry = o.control.execute(command)
        conflict(retry, ConflictReason.TargetChanged)
        assertEquals(setOf(command), retry.localUnresolvedCommands)
        val liveConfirmation = o.control.confirmPrevious(command, checkpoint)
        conflict(liveConfirmation, ConflictReason.TargetMissing)
        assertEquals(setOf(command), liveConfirmation.localUnresolvedCommands)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        val reopened = open(); val reopenedWrites = reopened.storage.writes
        val previousConfirmation = reopened.control.confirmPrevious(command, checkpoint)
        conflict(previousConfirmation, ConflictReason.TargetMissing)
        assertTrue(previousConfirmation.localUnresolvedCommands.isEmpty())
        assertEquals(before, reopened.raw()); assertEquals(reopenedWrites, reopened.storage.writes)
    }
}
