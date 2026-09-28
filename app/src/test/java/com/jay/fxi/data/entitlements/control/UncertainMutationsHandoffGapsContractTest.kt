package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bC1e contract (6-4bC1 completion judgment r1, 6-4bC1_completion_and_C2_design_codex.r1.md: C1 contract gaps).
 * Rows the C1 consensus and the delivery-table amendment name for handoffAfterUncertainConfirm that the C1a–C1c contracts do not
 * hold on their own:
 *  - a pending ref whose release descriptor is missing may reach c's own Applied row: DependencyUnknown, nothing published;
 *  - an unrelated pending ref (its fixed descriptor names another command's Applied row) does not block and stays pending;
 *  - cancellation before the candidate leaves c RETAINED with no descriptor and the record untouched, and a later call completes;
 *  - cancellation after the management Confirm landed leaves c TERMINATION_PENDING with its fixed descriptor in U∩P, and the
 *    retry completes on that same descriptor object.
 * The fixture is the C1a one: a landed REQUEST edit (raisedAt 4 → 5) whose business Confirm return failed; the REQUEST is then
 * consumed and both REQUEST slots are declared completed and consumed through B1.
 * The implementation thread reads but does not edit this file.
 */
class UncertainMutationsHandoffGapsContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    @After fun close() = runReleaseTest { controlTestTimeout("C1e cleanup", 30_000) { fx.storage.close() } }
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }

    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff)
    /** The C1a landed edit: exact own present, c in U; the REQUEST consumed; both REQUEST slots completed and consumed in B1. */
    private suspend fun landedAndDeclared(): Pair<CommandRef, Declared> {
        fx.storage.seed(demand = "[$request]")
        val c = fx.store.prepare(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) })
        fx.storage.storage.afterScope = true
        assertTrue(controlTestTimeout("execute landed") { fx.store.execute(c) } is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        assertEquals("fixture: exact own", 1, ownIn(fx.disk(), c))
        fx.edit { it[ControlStoreTestStorage.DEMAND] = "[]" }
        val input = RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val b = HandoffEventBinding(c, owner)
        for (slot in a.orderedSlots.filter { it.requirement is SlotRequirement.Required }) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        val issued = h.closeJoinAndIssueHandoff(input) as HandoffIssueResult.Issued
        return c to Declared(issued.closure, issued.handoff)
    }
    private suspend fun handoff(c: CommandRef, d: Declared) = store().handoffAfterUncertainConfirm(c, d.closure, d.handoff)

    /** Success: only c's own Applied removed, terminal, body released, out of U, P and commands. */
    private suspend fun completed(r: ControlCompletionResult, c: CommandRef, before: Preferences) {
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only c's own Applied removed", JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }),
            evidence(after))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("c left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertNull("c left commands", fx.tracker.findPrepared(c))
    }
    /** RETAINED, still in U, no descriptor, out of P, lease released, record untouched. */
    private suspend fun retainedUntouched(c: CommandRef, before: Preferences) {
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("no descriptor", fx.tracker.findPrepared(c)?.terminationDescriptor)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("c in U, not in P", c in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("lease released", c !in fx.tracker.executing)
        assertEquals("record untouched", before, fx.disk())
    }

    /** G01: another ref RELEASE_PENDING without its fixed descriptor may reach c's Applied row: DependencyUnknown, nothing published. */
    @Test fun G01_pendingRefMissingItsReleaseDescriptor_dependencyUnknown() = runReleaseTest {
        val (c, d) = landedAndDeclared()
        val m = fx.mutations(); ControlReleaseFixtures.pending(m); fx.addPending(m)
        fx.armReadBack() // any management Confirm would write the barrier: whole-record equality = Confirm 0
        val before = fx.disk(); val writes = fx.storage.storage.writes
        val r = controlTestTimeout("handoff") { handoff(c, d) }
        assertEquals("DependencyUnknown on m, got $r", CompletionRejectionReason.DependencyUnknown(m.id, m.ownerTrackingLifetimeId.value,
            DependencyGapSource.ReleaseDescriptor(null)), (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("no write", writes, fx.storage.storage.writes)
        retainedUntouched(c, before)
        assertTrue("m still pending", m in fx.tracker.recoverySnapshot().pendingReleases)
    }

    /** G02: an unrelated pending ref whose fixed descriptor names only its own Applied row does not block, and stays pending. */
    @Test fun G02_unrelatedPendingRef_completes_andItStaysPending() = runReleaseTest {
        val (c, d) = landedAndDeclared()
        val m = fx.mutations()
        val row = ControlReleaseFixtures.row(m, id = m.id, lifetime = m.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        val before = fx.disk()
        completed(controlTestTimeout("handoff") { handoff(c, d) }, c, before)
        assertTrue("m still pending", m in fx.tracker.recoverySnapshot().pendingReleases)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, m.lifecycleState)
    }

    /** G03: cancelled while the owner transaction waits before its snapshot: RETAINED and untouched; a later call completes. */
    @Test fun G03_cancelledBeforeTheCandidate_retainedUntouched_thenCompletes() = runReleaseTest {
        val (c, d) = landedAndDeclared()
        val before = fx.disk()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fx.boundary.gate = reached to release
        coroutineScope {
            val caller = async { handoff(c, d) }
            try { withTimeout(10_000) { reached.await() }; caller.cancelAndJoin(); assertTrue("cancelled", caller.isCancelled) }
            finally { release.complete(Unit); caller.cancelAndJoin() }
        }
        retainedUntouched(c, before)
        assertEquals("no clock read", 0, reads.get())
        completed(controlTestTimeout("handoff again") { handoff(c, d) }, c, before)
    }

    /** G04: cancelled after the management Confirm landed: TERMINATION_PENDING with the fixed descriptor in U∩P; retry completes on it. */
    @Test fun G04_cancelledAfterTheConfirmLanded_pendingFixed_retryCompletes() = runReleaseTest {
        val (c, d) = landedAndDeclared()
        val before = fx.disk()
        val pause = ControlStoreTestStorage.Pause(); fx.storage.storage.pauseAfterScope = pause
        var descriptorAtPause: TerminationPendingDescriptor? = null
        coroutineScope {
            val caller = async { handoff(c, d) }
            try {
                withTimeout(10_000) { pause.reached.await() }
                descriptorAtPause = fx.tracker.findPrepared(c)?.terminationDescriptor
                assertEquals("landed before cancel", 0, ownIn(fx.disk(), c))
                caller.cancelAndJoin(); assertTrue("cancelled", caller.isCancelled)
            } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
        }
        fx.storage.storage.pauseAfterScope = null
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        val descriptor = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("fixed MutationsHandoff with the exact own", descriptor is TerminationPendingDescriptor.MutationsHandoff && descriptor.expectedOwn != null)
        assertSame("descriptor kept from before the cancel", descriptorAtPause, descriptor)
        val w = fx.tracker.recoverySnapshot(); assertTrue("c in U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertTrue("lease released", c !in fx.tracker.executing)
        var seen: TerminationPendingDescriptor? = null
        fx.boundary.afterReturn = { p -> seen = fx.tracker.findPrepared(c)?.terminationDescriptor; p }
        completed(controlTestTimeout("retry") { store().retryTermination(c, d.closure) }, c, before)
        assertSame("retry used the fixed descriptor object", descriptor, seen)
    }
}
