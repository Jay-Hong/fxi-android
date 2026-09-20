package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlLifecycleEvidenceDispatchTest {
    @get:Rule val folder = TemporaryFolder()
    private val storage by lazy { ControlStoreTestStorage(File(folder.root, "dispatch.preferences_pb")) }
    private val tracking get() = ControlCommandTracking.forOwner(storage.owner)
    @After fun close() = runReleaseTest { storage.close() }
    private fun ref(input: ControlLifecycleDescriptor) = tracking.registerPrepared(F.command(input, tracking.lifetimeId))
    private suspend fun seed(c: CommandRef, demand: String = "[]") = controlTestTimeout("dispatch seed") {
        storage.data.updateData { F.raw(demand = demand, evidence = " [ ${F.wire(c)} ] ") }
    }
    private suspend fun confirmed(transition: LifecycleTransition, targets: List<LifecycleFixedTarget>, demand: String = "[]") {
        val c = ref(F.descriptor(transition = transition, targets = targets, executor = F.executor))
        seed(c, demand)
        val other = ref(F.descriptor(id = "unrelated"))
        tracking.markUnresolved(other)
        val before = storage.raw()
        // Confirmation does not re-authorize archived runtime context.
        val result = controlTestTimeout("dispatch confirm") { storage.control.execute(c) }
        assertTrue(F.retry("dispatch.$transition"), result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
        val receipt = checkNotNull(result.lifecycleReceipt)
        assertEquals(transition, receipt.transition)
        assertEquals(c.id, receipt.commandId)
        assertEquals(targets.map { it.target }, receipt.targets.map { it.target })
        assertEquals(setOf(other), result.localUnresolvedCommands)
        assertTrue(result.localPendingReleases.isEmpty())
        assertEquals(LifecycleClassification.CURRENT_POSTCONDITION_CONFIRMED, result.lifecycleDiagnostic!!.classification)
        assertSame(result.lifecycleDiagnostic, c.lastLifecycleDiagnostic)
        assertFalse(result.lifecycleDiagnostic.previouslyConfirmed)
        assertTrue(result.lifecycleDiagnostic.confirmationRequested)
        val after = storage.raw().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
        assertEquals(before, after)
    }
    @Test fun rebindDispatch() = runReleaseTest { confirmed(LifecycleTransition.REBIND_REQUESTS, listOf(F.replaceRequest), "[${F.stronger.toPayloadEntry().fields}]") }
    @Test fun settleDispatch() = runReleaseTest { confirmed(LifecycleTransition.SETTLE_QUERY, listOf(F.createRequest), "[${F.stronger.toPayloadEntry().fields}]") }
    @Test fun updateAuthDispatch() = runReleaseTest {
        val after = F.node(ControlObligationFixtures.guard)
        val target = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE), LifecycleRole.GUARD, F.guard, after)
        confirmed(LifecycleTransition.UPDATE_AUTH, listOf(target), "[${ControlObligationFixtures.guard}]")
    }
    @Test fun endBindingDispatch() = runReleaseTest {
        val before = F.node("""{"id":"g","kind":"SCHEDULE_GUARD","auth":${ControlObligationFixtures.auth}}""")
        val target = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE), LifecycleRole.GUARD, before, F.guard)
        confirmed(LifecycleTransition.END_AUTH_BINDING, listOf(target), "[${ControlObligationFixtures.emptyGuard}]")
    }
    @Test fun emptyGuardDispatch() = runReleaseTest { confirmed(LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(F.removeGuard)) }
    @Test fun holdDispatch() = runReleaseTest {
        val target = LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, "h", LifecycleEffect.REMOVE), LifecycleRole.HOLD, F.node(ControlObligationFixtures.hold), null)
        confirmed(LifecycleTransition.RECOVER_HOLD, listOf(target))
    }
    @Test fun intentDispatch() = runReleaseTest {
        val target = LifecycleFixedTarget(LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE), LifecycleRole.RECOVERY_INTENT, F.node(ControlObligationFixtures.recovery), null)
        confirmed(LifecycleTransition.RECOVER_INTENT, listOf(target))
    }
    private suspend fun requireReadBack() {
        val failure = controlTestTimeout("require owner read-back") {
            runCatching { storage.owner.transactRecord<Unit> { throw IOException("injected read-back obligation") } }.exceptionOrNull()
        }
        assertTrue(failure is IOException)
    }
    @Test fun confirmationFailureRetainsObservedEvidenceAndFixedHistory() = runReleaseTest {
        val c = ref(F.descriptor())
        seed(c)
        requireReadBack()
        storage.storage.before = true
        val failed = controlTestTimeout("confirm I/O failure") { storage.control.execute(c) }
        assertTrue(failed is ControlStoreResult.Unconfirmed)
        val history = checkNotNull(tracking.findPrepared(c))
        assertTrue(history.observedApplied.get())
        assertTrue(history.confirmationRequested.get())
        assertFalse(history.confirmed.get())
        val fixed = history.expectedApplied
        val count = history.firstConfirmDiscontinuityCount
        val next = controlTestTimeout("confirm retry") { storage.control.execute(c) }
        assertTrue(next is ControlStoreResult.Confirmed)
        assertEquals(count, history.firstConfirmDiscontinuityCount)
        assertEquals(ControlAppliedEvidence.node(checkNotNull(fixed)), ControlAppliedEvidence.node(checkNotNull(history.expectedApplied)))
        assertTrue(next.localUnresolvedCommands.isEmpty())
    }
    @Test fun cancellationPreservesActualDiagnosticWithoutPublishingSuccess() = runReleaseTest {
        val c = ref(F.descriptor())
        seed(c)
        requireReadBack()
        val gate = ControlStoreTestStorage.Pause()
        storage.storage.pauseAfterScope = gate
        var returned = false
        val caller = launch { storage.control.execute(c); returned = true }
        try {
            controlTestTimeout("confirm cancellation gate") { gate.reached.await() }
            caller.cancelAndJoinForTest()
            val d = checkNotNull(c.lastLifecycleDiagnostic)
            assertEquals(LifecycleOwnEvidence.Matched, d.ownEvidence)
            assertTrue(d.confirmationRequested)
            assertFalse(returned)
            assertFalse(checkNotNull(tracking.findPrepared(c)).confirmed.get())
            assertEquals(setOf(c), tracking.snapshot())
        } finally {
            gate.release.complete(Unit)
            caller.cancelAndJoinForTest()
        }
    }
    private val namespace = LifecycleNamespacePostcondition(F.fence, F.fence,
        listOf(PendingPurge("A", "old", null, setOf(PurgeScope.USER))))
    private suspend fun namespaceChanged(change: (MutablePreferences) -> Unit) {
        val c = ref(F.descriptor(namespace = namespace))
        val raw = F.raw(evidence = "[${F.wire(c)}]").toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|old||USER" }
        change(raw)
        controlTestTimeout("namespace confirmation seed") { storage.data.updateData { raw } }
        val writes = storage.storage.writes
        val result = controlTestTimeout("namespace confirmation") { storage.control.execute(c) }
        assertTrue(F.retry("namespace"), result is ControlStoreResult.Conflict)
        assertEquals(LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, result.lifecycleDiagnostic!!.classification)
        assertEquals(writes, storage.storage.writes)
    }
    @Test fun laterEpochCannotReconfirmOldNamespace() = runReleaseTest { namespaceChanged { it[USER_EPOCH] = "later" } }
    @Test fun consumedJournalCannotReconfirmOldHandoff() = runReleaseTest { namespaceChanged { it.remove(PURGE_JOURNAL) } }
}
