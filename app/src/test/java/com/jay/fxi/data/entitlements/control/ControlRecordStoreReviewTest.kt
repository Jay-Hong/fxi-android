package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.auth
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.nullSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settledSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.text
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Review regressions use real DataStore and public facade operations, except explicit restore inputs. */
class ControlRecordStoreReviewTest {
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

    private fun ControlRecordStore.stronger(before: ControlNode = node(request)): ControlMutation =
        edit(ControlKind.DEMAND, before) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM"))
            set("raisedAt", ControlScalar.Integer(5))
        }

    private fun confirmed(result: ControlStoreResult): ControlStoreResult.Confirmed {
        assertTrue("$result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }

    private fun conflict(result: ControlStoreResult, reason: ConflictReason) {
        assertTrue("$result", result is ControlStoreResult.Conflict)
        assertEquals(reason, (result as ControlStoreResult.Conflict).reason)
    }

    private fun historyUnavailable(result: ControlStoreResult) {
        assertTrue("$result", result is ControlStoreResult.Unconfirmed)
        assertEquals(UnconfirmedReason.HistoryUnavailable, (result as ControlStoreResult.Unconfirmed).reason)
    }

    private fun ControlMutation.desired(): ControlNode = (when (this) {
        is ControlMutation.Add -> built
        is ControlMutation.Edit -> changed
    } as ControlWriteResult.Written).node

    private fun ControlStoreResult.Confirmed.demand(): ControlNode =
        (snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted).original

    @Test fun rejectedLiveCheckpointCannotManufactureAnEditRequest() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val edit = o.control.stronger()
        val command = o.control.prepare(edit)
        val supplied = ControlCommandCheckpoint(command,
            listOf(ControlCommandTarget("d", edit.desired(), false)), true)
        historyUnavailable(o.control.confirmPrevious(command, supplied))
        val retained = o.control.checkpoint(command)!!
        assertFalse(retained.confirmationRequested)
        assertEquals(listOf(null), retained.targets)
        confirmed(o.control.execute(o.control.prepare(edit)))
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
    }

    @Test fun liveCheckpointCannotMoveAnAdoptedSealToAReplacement() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        confirmed(o.control.execute(command))
        val replacement = seal.replace("\"s\"", "\"replacement\"")
        o.seed(seal = "[$replacement]")
        val supplied = ControlCommandCheckpoint(command,
            listOf(ControlCommandTarget("replacement", node(replacement), true)), true)
        historyUnavailable(o.control.confirmPrevious(command, supplied))
        assertEquals("s", o.control.checkpoint(command)!!.targets.single()!!.id)
        conflict(o.control.execute(command), ConflictReason.TargetMissing)
    }

    @Test fun liveCheckpointCannotReplaceTheFixedPostconditionWithOpaqueData() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        confirmed(o.control.execute(command))
        val changed = seal.dropLast(1) + ",\"future\":1}"
        o.seed(seal = "[$changed]")
        // A future opaque node must not replace the previously fixed, interpretable postcondition.
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("s", node(changed), true)), true)
        historyUnavailable(o.control.confirmPrevious(command, supplied))
        assertEquals(node(seal).toPayloadEntry(), o.control.checkpoint(command)!!.targets.single()!!.postcondition.toPayloadEntry())
        conflict(o.control.execute(command), ConflictReason.UninterpretableTarget)
    }

    @Test fun liveCheckpointRequiresActualLocalConfirmationRequest() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val small = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = 100))
        val action = small.add()
        val command = small.prepare(action)
        assertTrue(small.execute(command) is ControlStoreResult.Rejected)
        val local = small.checkpoint(command)!!
        assertTrue(local.targets.all { it != null })
        val supplied = ControlCommandCheckpoint(command, local.targets, true)
        confirmed(o.control.execute(o.control.prepare(action)))
        historyUnavailable(o.control.confirmPrevious(command, supplied))
        assertFalse(o.control.checkpoint(command)!!.confirmationRequested)
        conflict(o.control.execute(command), ConflictReason.IdCollision)
    }

    @Test fun previousSealCheckpointMustMatchPreparedSealKey() = runBlocking {
        val o = open(); val changed = seal.replace("old", "different-epoch")
        o.seed(seal = "[$changed]")
        val command = o.control.prepare(o.control.add())
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("s", node(changed), true)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousJoinedSealCheckpointCannotClaimASettledSeal() = runBlocking {
        val o = open(); o.seed(seal = "[$settledSeal]")
        val command = o.control.prepare(o.control.add(raw = nullSeal))
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("s", node(settledSeal), true)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousOwnAdditionCheckpointMustMatchPreparedPostcondition() = runBlocking {
        val o = open()
        val action = o.control.add()
        val command = o.control.prepare(action)
        val changed = node(action.desired().toPayloadEntry().fields.toString().replace("old", "different-epoch"))
        val id = (action as ControlMutation.Add).proposedId
        o.seed(seal = text(changed))
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget(id, changed, false)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousOwnAdditionCheckpointMustMatchPreparedId() = runBlocking {
        val o = open(); o.seed()
        val action = o.control.add()
        val command = o.control.prepare(action)
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("other-id", action.desired(), false)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousEditCheckpointCannotSubstituteItsPreimageForItsPostcondition() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val command = o.control.prepare(o.control.stronger())
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("d", node(request), false)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousCheckpointCannotJoinAnEditAsThoughItWereAnAddition() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.edit(ControlKind.SEAL, node(seal)) {})
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("s", node(seal), true)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun previousJoinedCheckpointIdMustMatchItsNode() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("other-id", node(seal), true)), true)
        historyUnavailable(open().control.confirmPrevious(command, supplied))
    }

    @Test fun matchingLiveCheckpointUsesStructuralValues() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        confirmed(o.control.execute(command))
        val supplied = ControlCommandCheckpoint(command, listOf(ControlCommandTarget("s", node(seal), true)), true)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, confirmed(o.control.confirmPrevious(command, supplied)).effect)
    }

    @Test fun previousOwnAdditionAndEditCheckpointConfirmsWithoutApplying() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val command = o.control.prepare(o.control.add(ControlKind.RECOVERY_INTENT, recovery), o.control.stronger())
        confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open(); val before = reopened.raw(); val writes = reopened.storage.writes
        assertEquals(ConfirmedEffect.PostconditionConfirmed, confirmed(reopened.control.confirmPrevious(command, checkpoint)).effect)
        assertEquals(before, reopened.raw()); assertEquals(writes, reopened.storage.writes)
    }

    @Test fun confirmedAuthEditCannotRestoreAnEndedBinding() = runBlocking {
        val o = open(); o.seed(demand = "[$emptyGuard]")
        o.data.edit { it[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.OWNER_UID] = "A" }
        val command = o.control.prepareUpdateAuth(node(emptyGuard), null, DemandAuthFixtures.binding,
            LifecycleAuthEvent.Initialize, LifecycleOrderSource(DemandAuthFixtures.life))
        confirmed(o.control.execute(command, DemandAuthFixtures.context()))
        o.data.edit { it[DEMAND] = "[$emptyGuard]" } // Simulate the ended AUTH postcondition; the old ref must not reinstall it.
        val writes = o.storage.writes
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
        assertEquals("[$emptyGuard]", o.raw()[DEMAND]); assertEquals(writes, o.storage.writes)
    }

    @Test fun confirmedFloorEditCannotReplayAfterFacadeOnlyAba() = runBlocking {
        val o = open(); o.seed(demand = "[$emptyGuard]")
        val clock = BootReading("boot", 50)
        val first = confirmed(o.control.execute(o.control.prepare(o.control.recordFloor(node(emptyGuard), clock, 100, LifetimeId("A"))))).demand()
        val command = o.control.prepare(o.control.recordFloor(first, clock, 0, LifetimeId("B")))
        val second = confirmed(o.control.execute(command)).demand()
        val back = confirmed(o.control.execute(o.control.prepare(o.control.recordFloor(second, clock, 0, LifetimeId("A"))))).demand()
        assertEquals(first.toPayloadEntry(), back.toPayloadEntry())
        val before = o.raw(); val writes = o.storage.writes
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun unconfirmedEditCanRetryButConfirmedEditOnlyConfirms() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val command = o.control.prepare(o.control.stronger())
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, confirmed(o.control.execute(command)).effect)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, confirmed(o.control.execute(command)).effect)
    }

    @Test fun changedConfirmedOwnAdditionHasTheSameReasonInBothPaths() = runBlocking {
        val o = open(); o.seed()
        val action = o.control.add(ControlKind.DEMAND, request)
        val command = o.control.prepare(action)
        confirmed(o.control.execute(command))
        confirmed(o.control.execute(o.control.prepare(o.control.stronger(action.desired()))))
        val before = o.raw(); val writes = o.storage.writes
        conflict(o.control.execute(command), ConflictReason.TargetChanged)
        conflict(o.control.confirmPrevious(command, o.control.checkpoint(command)), ConflictReason.TargetChanged)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun changedRequestedOwnAdditionIsTargetChangedBeforeAnyConfirmation() = runBlocking {
        val o = open(); o.seed()
        val action = o.control.add(ControlKind.DEMAND, request)
        val command = o.control.prepare(action)
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        confirmed(o.control.execute(o.control.prepare(action)))
        confirmed(o.control.execute(o.control.prepare(o.control.stronger(action.desired()))))
        val result = o.control.execute(command)
        conflict(result, ConflictReason.TargetChanged)
        assertEquals(setOf(command), result.localUnresolvedCommands)
    }

    @Test fun previousCheckpointMustBelongToTheExactCommand() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val other = o.control.prepare(o.control.add())
        confirmed(o.control.execute(other))
        val checkpoint = o.control.checkpoint(other)!!
        val command = o.control.prepare(o.control.add())
        historyUnavailable(open().control.confirmPrevious(command, checkpoint))
    }

    @Test fun matchingCommandUuidDoesNotReplaceReferenceIdentity() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]")
        val command = o.control.prepare(o.control.add())
        confirmed(o.control.execute(command))
        val checkpoint = o.control.checkpoint(command)!!
        val differentRef = CommandRef(command.id, command.actions, command.ownerTrackingLifetimeId)
        historyUnavailable(open().control.confirmPrevious(differentRef, checkpoint))
    }

    @Test fun overlappingRefusalsRetainTheLeaseAndTheFailedAttemptsUncertainty() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add())
        val pause = ControlStoreTestStorage.Pause(); o.storage.pause = pause
        val first = async { o.control.execute(command) }
        withTimeout(10_000) { pause.reached.await() }
        try {
            repeat(2) { // The third call must also be refused while the first still owns the lease.
                val failure = runCatching { withTimeout(1_000) { o.control.execute(command) } }.exceptionOrNull()
                assertEquals(IllegalStateException::class.java, failure?.javaClass)
                assertEquals("the same command is already executing", failure?.message)
                assertFalse(first.isCompleted)
            }
            o.storage.after = true
        } finally { pause.release.complete(Unit) }
        val failed = first.await()
        assertTrue(failed is ControlStoreResult.Unconfirmed)
        assertEquals(setOf(command), failed.localUnresolvedCommands)
        assertTrue(confirmed(o.control.execute(command)).localUnresolvedCommands.isEmpty())
    }

    @Test fun firstGuardAdditionIsAllowedAndPersisted() = runBlocking {
        val o = open(); o.seed()
        val command = o.control.prepare(o.control.add(ControlKind.DEMAND, emptyGuard))
        val result = confirmed(o.control.execute(command))
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        val guard = result.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted
        assertTrue(guard.value is ScheduleGuardV1)
        assertEquals(result.effectiveIds.single(), guard.value.id)
        assertEquals(text(guard.original), open().raw()[DEMAND])
    }

    @Test fun firstConflictDoesNotLeaveANewUnresolvedCommand() = runBlocking {
        val o = open(); o.seed(seal = "[$seal,${seal.replace("\"s\"", "\"second\"")}]")
        val result = o.control.execute(o.control.prepare(o.control.add()))
        conflict(result, ConflictReason.AmbiguousSealKey)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        val other = o.control.prepare(o.control.add(ControlKind.RECOVERY_INTENT, recovery))
        assertTrue(confirmed(o.control.execute(other)).localUnresolvedCommands.isEmpty())
    }

    @Test fun firstRecoveryRequiredDoesNotLeaveANewUnresolvedCommand() = runBlocking {
        val o = open(); o.owner.bindOwner("A")
        val result = o.control.execute(o.control.prepare(o.control.add()))
        assertTrue(result is ControlStoreResult.RecoveryRequired)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        o.seed(); o.currentNamespace()
        assertTrue(confirmed(o.control.execute(o.control.prepare(o.control.add()))).localUnresolvedCommands.isEmpty())
    }

    @Test fun excessiveCandidateDepthIsRejectedWithoutPoisoningOwnerConfirmation() = runBlocking {
        val o = open(); o.seed(demand = "[$emptyGuard]")
        val shallow = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxDepth = 3))
        val command = shallow.prepare(shallow.add(ControlKind.RECOVERY_INTENT, recovery),
            shallow.recordFloor(node(emptyGuard), BootReading("boot", 0), 100, LifetimeId("life")))
        val before = o.raw(); val writes = o.storage.writes
        val result = shallow.execute(command)
        assertTrue("$result", result is ControlStoreResult.Rejected)
        assertEquals(RejectionReason.InvalidRequest("candidate violates codec envelope constraints"),
            (result as ControlStoreResult.Rejected).reason)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        assertFalse(shallow.checkpoint(command)!!.confirmationRequested)
        o.owner.load()
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun requestedCheckpointAtStorageBoundaryContainsEveryFixedTarget() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add(), o.control.add(ControlKind.RECOVERY_INTENT, recovery))
        val pause = ControlStoreTestStorage.Pause(); o.storage.pause = pause
        val first = async { o.control.execute(command) }
        withTimeout(10_000) { pause.reached.await() }
        try {
            val checkpoint = o.control.checkpoint(command)!!
            assertTrue(checkpoint.confirmationRequested)
            assertEquals(2, checkpoint.targets.size)
            assertTrue(checkpoint.targets.all { it != null })
        } finally { pause.release.complete(Unit) }
        confirmed(first.await())
        Unit
    }

    @Test fun previousRequestedFlagDoesNotInventLocalUnresolvedHistory() = runBlocking {
        val o = open(); o.seed(); o.currentNamespace()
        val command = o.control.prepare(o.control.add())
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open()
        val freshConflict = reopened.control.confirmPrevious(command, checkpoint)
        conflict(freshConflict, ConflictReason.TargetMissing)
        assertTrue(freshConflict.localUnresolvedCommands.isEmpty())
        historyUnavailable(reopened.control.confirmPrevious(command))
        val laterConflict = reopened.control.confirmPrevious(command, checkpoint)
        conflict(laterConflict, ConflictReason.TargetMissing)
        assertEquals(setOf(command), laterConflict.localUnresolvedCommands)
    }

    @Test fun previousUnlandedEditConfirmsNeitherPreimageNorMissingEffect() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val command = o.control.prepare(o.control.stronger())
        o.storage.before = true
        assertTrue(o.control.execute(command) is ControlStoreResult.Unconfirmed)
        val checkpoint = o.control.checkpoint(command)!!
        val reopened = open(); val writes = reopened.storage.writes
        conflict(reopened.control.confirmPrevious(command, checkpoint), ConflictReason.TargetChanged)
        assertEquals("[$request]", reopened.raw()[DEMAND]); assertEquals(writes, reopened.storage.writes)
    }
}
