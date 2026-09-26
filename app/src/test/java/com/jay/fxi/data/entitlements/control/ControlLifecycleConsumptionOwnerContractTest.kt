package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4aB contract: the owner path of current-lifetime Lifecycle consumption termination (6-4a skeleton
 * consensus r2 §1–§2, T1, T4–T6; 6-4aB API consensus). API fixed by this contract:
 *   suspend fun ControlRecordStore.completeLifecycleAfterConsumption(command, closure, consumption: RotationConsumption)
 *   TerminationEntry.ConsumedLifecycle; TerminationPendingDescriptor.ExactLifecycleEvidence(mode, entry, closureBinding,
 *   expectedLifecycle: AppliedEvidence.Lifecycle); TerminationPendingDescriptor.mode on the interface.
 * First entry (same order as the Settlement entry): precheck → lease → exact registration → one body view (non-Lifecycle
 * body → UnsupportedInThisUnit) → confirmed → not unresolved → both declarations → closure; all before storage. The owner
 * then classifies schema/opaque first, records an own row as observedApplied, runs ControlLifecycleConsumption (6-4aA),
 * then G11 through consumptionDependencyViolation(self = command, {AppliedRow(own)}): a dependent that only relies on the
 * same DEMAND/HOLD business rows does NOT block (those rows are not deleted); a descriptor naming this Applied row
 * (known) or a broad gap blocks. Then descriptor → P → pending → Confirm → return validation → TERMINATED/body release/exact
 * cleanup; Completed carries the descriptor's mode. retryTermination admits ExactLifecycleEvidence only for a Lifecycle
 * body; the pending retry allows the own row exactly present (same deletion) or absent (absence Confirm). Records come from
 * the seven real writers (LifecycleWriterFixtures). confirmed=true with expected=null is not reached by these writers
 * (re-executing after the own row is removed returns RecoveryRequired and keeps expected — 6-4aB_writer_probe.r1.txt), so
 * that branch is exercised as a defensive state (LB_05). The implementation thread reads but does not edit this file.
 */
class ControlLifecycleConsumptionOwnerContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val W = LifecycleWriters
    private val removeGuard get() = W.all.single { it.transition == LifecycleTransition.REMOVE_EMPTY_GUARD }

    private suspend fun confirmed(f: TerminationFixture, w: LifecycleWriters.Writer = removeGuard): CommandRef {
        val c = w.confirm(f.storage)
        check(f.history(c).confirmed.get() && f.history(c).expectedApplied is AppliedEvidence.Lifecycle && !f.tracker.isUnresolved(c)) {
            "fixture ${w.transition} state" }
        return c
    }
    private suspend fun consume(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c), d: RotationConsumption = declared) =
        controlTestTimeout("lifecycle consume") { f.store.completeLifecycleAfterConsumption(c, k, d) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) =
        controlTestTimeout("lifecycle retry") { f.store.retryTermination(c, k) }

    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    /** Independent oracle: before minus exactly the own row; every other key and row unchanged. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()
    private suspend fun editOwn(f: TerminationFixture, c: CommandRef, change: MutableMap<String, JsonElement>.() -> Unit) =
        f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).map { if (rowCommand(it) == c.id) obj(it, change) else it }).toString() }

    private suspend fun assertConsumed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("D2B6/6-4aB.$id: completed $r", r is ControlCompletionResult.Completed)
        r as ControlCompletionResult.Completed
        assertEquals("D2B6/6-4aB.$id: consumed", CompletionMode.Consumed, r.mode)
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("D2B6/6-4aB.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-4aB.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("D2B6/6-4aB.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-4aB.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/6-4aB.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        val work = f.tracker.recoverySnapshot()
        assertFalse("D2B6/6-4aB.$id: notInU", c in work.unresolvedCommands)
        assertFalse("D2B6/6-4aB.$id: notInP", c in work.pendingReleases)
        assertEquals("D2B6/6-4aB.$id: U", work.unresolvedCommands, r.localUnresolvedCommands)
        assertEquals("D2B6/6-4aB.$id: P", work.pendingReleases, r.localPendingReleases)
        assertTrue("D2B6/6-4aB.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun refusedBeforeStorage(id: String, f: TerminationFixture, c: CommandRef, reason: CompletionRejectionReason,
        k: TerminationClosure = closure(c), d: RotationConsumption = declared) {
        val before = f.storage.raw(); val access = f.boundary.accesses; val work = f.tracker.recoverySnapshot()
        val r = consume(f, c, k, d)
        assertEquals("D2B6/6-4aB.$id: $reason", reason, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-4aB.$id: noStorageAccess", 0, f.boundary.accesses - access)
        assertEquals("D2B6/6-4aB.$id: recordUntouched", before, f.storage.raw())
        assertEquals("D2B6/6-4aB.$id: setsKept", work, f.tracker.recoverySnapshot())
        if (c.lifecycleState == ControlCommandLifecycle.RETAINED) assertNull("D2B6/6-4aB.$id: noDescriptor", f.tracker.findPrepared(c)?.terminationDescriptor)
    }
    /** Owner refusal on first entry: RETAINED, no descriptor/P, record unchanged even with the read-back armed (Confirm 0). */
    private suspend fun ownerRefused(id: String, f: TerminationFixture, c: CommandRef, expected: Any) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-4aB.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            is CompletionRejectionReason -> assertEquals("D2B6/6-4aB.$id: rejected $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
            ConflictReason::class -> assertTrue("D2B6/6-4aB.$id: conflict $r", r is ControlCompletionResult.Conflict &&
                r.reason == ConflictReason.CommandEvidenceMismatch)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-4aB.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-4aB.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-4aB.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-4aB.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-4aB.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef): TerminationPendingDescriptor {
        f.storage.storage.before = true
        val r = consume(f, c)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
        f.storage.storage.before = false
        return checkNotNull(f.history(c).terminationDescriptor)
    }
    private suspend fun retryHeld(id: String, f: TerminationFixture, c: CommandRef, d: TerminationPendingDescriptor, expected: Any,
        k: TerminationClosure = closure(c)) {
        f.armReadBack()
        val before = f.storage.raw()
        val r = retry(f, c, k)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-4aB.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            is CompletionRejectionReason -> assertEquals("D2B6/6-4aB.$id: rejected $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
            ConflictReason::class -> assertTrue("D2B6/6-4aB.$id: conflict $r", r is ControlCompletionResult.Conflict)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-4aB.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("D2B6/6-4aB.$id: descriptorKept", d, f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-4aB.$id: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-4aB.$id: noConfirmRequested", before, f.storage.raw())
        assertTrue("D2B6/6-4aB.$id: leaseReleased", f.tracker.executing.isEmpty())
    }

    // ── T1: every writer's own row is consumed; the fixed descriptor shape ──────────────────────────────────────
    @Test fun LB_01_eachWriterIsConsumedWithExactDeletionAndAFixedDescriptor() = runBlocking {
        for (w in W.all) {
            val f = fixture(); val c = confirmed(f, w); val before = f.disk()
            check(arr(before, evidenceKey).count { rowCommand(it) == c.id } == 1) { "fixture ${w.transition}: own row" }
            assertConsumed("01 ${w.transition}", f, c, consume(f, c), expectedAfterDeletion(before, c))
        }
        val f = fixture(); val c = confirmed(f); val expected = f.history(c).expectedApplied
        val d = pendingByWriteFault(f, c)
        assertTrue("D2B6/6-4aB.01: descriptor $d", d is TerminationPendingDescriptor.ExactLifecycleEvidence)
        d as TerminationPendingDescriptor.ExactLifecycleEvidence
        assertEquals(CompletionMode.Consumed, d.mode)
        assertEquals(TerminationEntry.ConsumedLifecycle, d.entry)
        assertSame("D2B6/6-4aB.01: expectedIsTheHistory", expected, d.expectedLifecycle)
        assertTrue("D2B6/6-4aB.01: pInSet", c in f.tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun LB_02_notConfirmedUnresolvedAndDeclarations() = runBlocking {
        run {
            val f = fixture()
            controlTestTimeout("seed") { f.storage.data.updateData { FloorGuardFixtures.raw(FloorGuardFixtures.empty) } }
            val c = f.store.prepareRemoveEmptyGuard(FloorGuardFixtures.empty)
            refusedBeforeStorage("02 notConfirmed", f, c, CompletionRejectionReason.NotConfirmed)
        }
        val g = fixture(); val d = confirmed(g); g.addUnresolved(d)
        refusedBeforeStorage("02 unresolved", g, d, CompletionRejectionReason.Unresolved)
        for ((name, decl) in listOf("result" to RotationConsumption(false, true), "followUp" to RotationConsumption(true, false))) {
            val h = fixture(); val e = confirmed(h)
            refusedBeforeStorage("02 $name", h, e, CompletionRejectionReason.ConsumptionNotDeclared, d = decl)
        }
    }
    @Test fun LB_03_eachClosureFieldAloneIsRefusedAtTheEntry() = runBlocking {
        val cases: List<Pair<ClosureViolation, (TerminationFixture, CommandRef) -> TerminationClosure>> = listOf(
            ClosureViolation.CommandMismatch to { f, c -> closure(c, command = CommandRef(c.id, checkNotNull(c.captureStateAndBody().body), f.tracker.lifetimeId)) },
            ClosureViolation.LifetimeMismatch to { _, c -> closure(c, lifetime = fixture().tracker.lifetimeId) },
            ClosureViolation.RelatedScopeMismatch to { _, c -> closure(c, scope = c.id + "-other") },
            ClosureViolation.GenerationMismatch to { _, c -> closure(c, capture = 6L) },
            ClosureViolation.EntriesOpen to { _, c -> closure(c, entriesClosed = false) },
            ClosureViolation.CapturedJoinedMismatch to { _, c -> closure(c, joined = setOf("job-2")) },
            ClosureViolation.RegisteredMismatch to { _, c -> closure(c, registered = setOf("job-2")) },
            ClosureViolation.ReceiptsOpen to { _, c -> closure(c, receiptsClosed = false) },
            ClosureViolation.OwnerMissing to { _, c -> closure(c, owner = " ") })
        for ((violation, make) in cases) {
            val f = fixture(); val c = confirmed(f)
            refusedBeforeStorage("03 $violation", f, c, CompletionRejectionReason.ClosureNotSatisfied(violation), k = make(f, c))
        }
        for (w in W.all) {
            val f = fixture(); val c = confirmed(f, w)
            refusedBeforeStorage("03 ${w.transition} wired", f, c, CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.EntriesOpen),
                k = closure(c, entriesClosed = false))
        }
    }
    @Test fun LB_04_bodyKindWrongLifetimeCloneInFlightTerminalAndOtherPath() = runBlocking {
        run { // Rotation and Mutations bodies are not this entry's.
            val f = fixture(); f.edit { it.clear(); it += NamespaceSettlementFixtures.raw() }
            val rot = f.tracker.registerPrepared(CommandRef(NamespaceSettlementFixtures.operation,
                ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input()), f.tracker.lifetimeId))
            check(controlTestTimeout("rotation") { f.store.execute(rot, NamespaceSettlementFixtures.context) } is ControlStoreResult.Confirmed)
            refusedBeforeStorage("04 rotationBody", f, rot, CompletionRejectionReason.UnsupportedInThisUnit)
            val m = f.mutations(); f.history(m).confirmed.set(true)
            refusedBeforeStorage("04 mutationsBody", f, m, CompletionRejectionReason.UnsupportedInThisUnit)
        }
        run {
            val f = fixture(); val other = fixture(); val c = confirmed(other)
            refusedBeforeStorage("04 wrongLifetime", f, c, CompletionRejectionReason.WrongTrackerLifetime)
            val g = fixture(); val d = confirmed(g)
            val clone = CommandRef(d.id, checkNotNull(d.captureStateAndBody().body), d.ownerTrackingLifetimeId)
            refusedBeforeStorage("04 unregisteredClone", g, clone, CompletionRejectionReason.NotRegisteredIdentity)
        }
        run {
            val g = fixture(); val c = confirmed(g); check(g.tracker.executing.add(c))
            try { refusedBeforeStorage("04 inFlight", g, c, CompletionRejectionReason.InFlight) } finally { g.tracker.executing.remove(c) }
        }
        run {
            val h = fixture(); val e = confirmed(h); val before = h.disk()
            assertConsumed("04 first", h, e, consume(h, e), expectedAfterDeletion(before, e))
            val access = h.boundary.accesses
            assertTrue("D2B6/6-4aB.04 alreadyTerminated", consume(h, e) is ControlCompletionResult.AlreadyTerminated)
            assertEquals("D2B6/6-4aB.04 terminalNoStorage", 0, h.boundary.accesses - access)
        }
        run {
            val i = fixture(); val p = confirmed(i); pendingByWriteFault(i, p)
            refusedBeforeStorage("04 otherPath", i, p, CompletionRejectionReason.OtherManagementPath)
        }
    }

    // ── T2 owner rows ──────────────────────────────────────────────────────────────────────────────────────────
    @Test fun LB_05_expectedNullIsHeldAsADefensiveState() = runBlocking {
        // Not reached by the seven writers (see header); the decider's first check still holds on the owner path.
        val f = fixture(); val c = confirmed(f)
        f.history(c).expectedApplied = null
        ownerRefused("05", f, c, RecoveryReason.ExpectedLifecycleEvidenceUnavailable)
    }
    @Test fun LB_06_ownRowLostAfterConfirmationIsLost() = runBlocking {
        for (w in W.all) {
            val f = fixture(); val c = confirmed(f, w)
            f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
            ownerRefused("06 ${w.transition}", f, c, RecoveryReason.CommandEvidenceLost)
        }
    }
    @Test fun LB_07_ownRowIsObservedBeforeAnUnrelatedOpaqueEntryRefuses() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        f.history(c).observedApplied.set(false)
        f.edit { p -> p[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[17]" }
        ownerRefused("07", f, c, RecoveryReason.UninterpretableObligations)
        assertTrue("D2B6/6-4aB.07: observedFirst", f.history(c).observedApplied.get())
    }
    @Test fun LB_08_ownRowChangesAreConflictOrTheOwnersSchemaClassification() = runBlocking {
        run { // A valid-shape change of the target id.
            val f = fixture(); val c = confirmed(f)
            editOwn(f, c) { this["targets"] = Json.parseToJsonElement("""[{"kind":"DEMAND","id":"g2","effect":"REMOVE"}]""") }
            ownerRefused("08 targetId", f, c, ConflictReason::class)
        }
        run { // A valid-shape change of the effect (SETTLE_QUERY suffix allows CREATE and REPLACE).
            val w = W.all.single { it.transition == LifecycleTransition.SETTLE_QUERY }
            val f = fixture(); val c = confirmed(f, w)
            editOwn(f, c) { this["targets"] = Json.parseToJsonElement("""[{"kind":"DEMAND","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"g","effect":"CREATE"}]""") }
            ownerRefused("08 effect", f, c, ConflictReason::class)
        }
        run { // A transition the schema rejects for this target shape → metadata classification first.
            val f = fixture(); val c = confirmed(f)
            editOwn(f, c) { this["transition"] = JsonPrimitive("REBIND_REQUESTS") }
            ownerRefused("08 schema", f, c, RecoveryReason.UninterpretableMetadata)
        }
    }

    // ── T6: current business rows are not required and are never touched ──────────────────────────────────────────
    @Test fun LB_09_currentBusinessRowsChangedAfterConfirmationAreKeptAsTheyAre() = runBlocking {
        for (w in W.all) {
            val f = fixture(); val c = confirmed(f, w)
            // A successor rewrote the business payloads after this ref's Confirm (e.g. Q2 consumed Q1's REQUEST).
            f.edit { p ->
                p[demandKey] = "[${ControlObligationFixtures.request.replace("\"id\":\"d\"", "\"id\":\"successor\"")}]"
                p[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
                p[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[]"
            }
            val before = f.disk()
            assertConsumed("09 ${w.transition}", f, c, consume(f, c), expectedAfterDeletion(before, c))
        }
    }

    // ── T4: pending, faults and retry ───────────────────────────────────────────────────────────────────────────
    @Test fun LB_10_readFaultAndCancellationBeforeTheCandidateKeepRetained() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk(); val work = f.tracker.recoverySnapshot()
        f.boundary.failNextBeforeSnapshot = true
        assertTrue("D2B6/6-4aB.10: unconfirmed", consume(f, c) is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-4aB.10: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-4aB.10: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-4aB.10: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-4aB.10: diskUnchanged", before, f.disk())
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.boundary.gate = reached to release
        val caller = async { f.store.completeLifecycleAfterConsumption(c, closure(c), declared) }
        try { withTimeout(10_000) { reached.await() }; caller.cancelAndJoin(); assertTrue(caller.isCancelled) }
        finally { release.complete(Unit); caller.cancelAndJoin() }
        assertEquals("D2B6/6-4aB.10: retainedAfterCancel", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertEquals("D2B6/6-4aB.10: diskUnchangedAfterCancel", before, f.disk())
        assertConsumed("10r", f, c, consume(f, c), expectedAfterDeletion(before, c))
    }
    @Test fun LB_11_writeFaultFixesTheDescriptorThenRetryDeletes() = runBlocking {
        for (w in W.all) {
            val f = fixture(); val c = confirmed(f, w); val before = f.disk()
            pendingByWriteFault(f, c)
            assertEquals("D2B6/6-4aB.11 ${w.transition}: nothingLanded", withoutBarrier(before), withoutBarrier(f.disk()))
            assertConsumed("11 ${w.transition}", f, c, retry(f, c), expectedAfterDeletion(before, c))
        }
    }
    @Test fun LB_12_landedThenReturnFailedRetryConfirmsAbsence() = runBlocking {
        val f = fixture(); val c = confirmed(f, W.all.single { it.transition == LifecycleTransition.RECOVER_HOLD }); val before = f.disk()
        f.storage.storage.afterScope = true
        assertTrue("D2B6/6-4aB.12: unconfirmed", consume(f, c) is ControlCompletionResult.Unconfirmed)
        f.storage.storage.afterScope = false
        val landed = f.disk()
        assertEquals("D2B6/6-4aB.12: landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        assertConsumed("12r", f, c, retry(f, c), withoutBarrier(landed))
    }
    @Test fun LB_13_cancellationAfterLandingRetryInFlightAndRetryNotPending() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk()
        val pause = ControlStoreTestStorage.Pause(); f.storage.storage.pauseAfterScope = pause
        val caller = async { f.store.completeLifecycleAfterConsumption(c, closure(c), declared) }
        try {
            withTimeout(10_000) { pause.reached.await() }
            assertEquals("D2B6/6-4aB.13: landedBeforeCancel", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            caller.cancelAndJoin(); assertTrue(caller.isCancelled)
        } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
        f.storage.storage.pauseAfterScope = null
        assertEquals("D2B6/6-4aB.13: pendingAfterCancel", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        check(f.tracker.executing.add(c))
        try {
            val access = f.boundary.accesses
            assertEquals("D2B6/6-4aB.13: retryInFlight", CompletionRejectionReason.InFlight, (retry(f, c) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-4aB.13: inFlightNoStorage", 0, f.boundary.accesses - access)
        } finally { f.tracker.executing.remove(c) }
        assertConsumed("13r", f, c, retry(f, c), expectedAfterDeletion(before, c))
        val g = fixture(); val d = confirmed(g); val access = g.boundary.accesses
        assertEquals("D2B6/6-4aB.13: retryNotPending", CompletionRejectionReason.NotTerminationPending, (retry(g, d) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-4aB.13: notPendingNoStorage", 0, g.boundary.accesses - access)
    }
    @Test fun LB_14_aReturnThatDoesNotMatchTheCandidateIsAnInvariantFailure() = runBlocking {
        for (name in listOf("otherKey", "ownBack")) {
            val f = fixture(); val c = confirmed(f); val sourceEvidence = checkNotNull(f.disk()[evidenceKey])
            check(f.disk()[ControlStoreTestStorage.EXTRA] != "changed-by-return") { "fixture: the tamper changes the return" }
            f.boundary.afterReturn = { p -> p.toMutablePreferences().apply {
                if (name == "otherKey") this[ControlStoreTestStorage.EXTRA] = "changed-by-return" else this[evidenceKey] = sourceEvidence }.toPreferences() }
            val failure = runCatching { consume(f, c) }.exceptionOrNull()
            assertEquals("D2B6/6-4aB.14 $name: invariantPropagates", IllegalStateException::class.java, failure?.javaClass)
            assertEquals("D2B6/6-4aB.14 $name: pendingKept", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertNotNull("D2B6/6-4aB.14 $name: descriptorKept", f.history(c).terminationDescriptor)
            assertTrue("D2B6/6-4aB.14 $name: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
            assertTrue("D2B6/6-4aB.14 $name: leaseReleased", f.tracker.executing.isEmpty())
        }
    }
    @Test fun LB_15_retryReplacementAndClosureBindingFieldsAreHeld() = runBlocking {
        run { // The own row replaced while pending.
            val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
            editOwn(f, c) { this["targets"] = Json.parseToJsonElement("""[{"kind":"DEMAND","id":"g2","effect":"REMOVE"}]""") }
            retryHeld("15 replacement", f, c, d, ConflictReason::class)
        }
        val cases: List<Pair<ClosureViolation, (CommandRef) -> TerminationClosure>> = listOf(
            ClosureViolation.GenerationMismatch to { c -> closure(c, capture = 8L, current = 8L) },
            ClosureViolation.OwnerMismatch to { c -> closure(c, owner = "owner-2") },
            ClosureViolation.WorkSetChanged to { c -> closure(c, captured = setOf("job-2"), joined = setOf("job-2"), registered = setOf("job-2")) },
            ClosureViolation.ReceiptsOpen to { c -> closure(c, receiptsClosed = false) })
        for ((violation, make) in cases) {
            val f = fixture(); val c = confirmed(f); val before = f.disk(); val d = pendingByWriteFault(f, c)
            retryHeld("15 $violation", f, c, d, CompletionRejectionReason.ClosureNotSatisfied(violation), k = make(c))
            assertConsumed("15r $violation", f, c, retry(f, c), expectedAfterDeletion(before, c))
        }
    }
    @Test fun LB_16_retryAdmitsTheLifecycleDescriptorOnlyForALifecycleBody() = runBlocking {
        val field = TrackedControlCommand::class.java.getDeclaredField("terminationDescriptor").apply { isAccessible = true }
        run { // A Lifecycle ref pending with a Settlement descriptor.
            val f = fixture(); val c = confirmed(f); pendingByWriteFault(f, c)
            val donor = fixture(); val k = RetiredNamespaceFixtures.spec()
            donor.edit { it.clear(); it += RetiredNamespaceFixtures.raw(k) }
            val s = donor.tracker.registerPrepared(CommandRef(k.operationId, ControlCommandBody.SettleRetiredNamespace(k), donor.tracker.lifetimeId))
            check(controlTestTimeout("settlement") { donor.store.execute(s, RetiredNamespaceFixtures.context) } is ControlStoreResult.Confirmed)
            donor.storage.storage.before = true
            check(controlTestTimeout("settlement pending") { donor.store.completeSettlementAfterConsumption(s, closure(s), declared) } is ControlCompletionResult.Unconfirmed)
            field.set(f.history(c), checkNotNull(field.get(donor.history(s))))
            val access = f.boundary.accesses
            assertEquals("D2B6/6-4aB.16 lifecycleRef", CompletionRejectionReason.UnsupportedInThisUnit, (retry(f, c) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-4aB.16 lifecycleRef noStorage", 0, f.boundary.accesses - access)
        }
        run { // A Settlement ref pending with a Lifecycle descriptor.
            val donor = fixture(); val c = confirmed(donor); pendingByWriteFault(donor, c)
            val f = fixture(); val k = RetiredNamespaceFixtures.spec()
            f.edit { it.clear(); it += RetiredNamespaceFixtures.raw(k) }
            val s = f.tracker.registerPrepared(CommandRef(k.operationId, ControlCommandBody.SettleRetiredNamespace(k), f.tracker.lifetimeId))
            check(controlTestTimeout("settlement") { f.store.execute(s, RetiredNamespaceFixtures.context) } is ControlStoreResult.Confirmed)
            f.storage.storage.before = true
            check(controlTestTimeout("settlement pending") { f.store.completeSettlementAfterConsumption(s, closure(s), declared) } is ControlCompletionResult.Unconfirmed)
            f.storage.storage.before = false
            field.set(f.history(s), checkNotNull(field.get(donor.history(c))))
            val access = f.boundary.accesses
            assertEquals("D2B6/6-4aB.16 settlementRef", CompletionRejectionReason.UnsupportedInThisUnit, (retry(f, s) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-4aB.16 settlementRef noStorage", 0, f.boundary.accesses - access)
        }
    }

    // ── T5 G11: only the own Applied row is protected ──────────────────────────────────────────────────────────
    private fun unrelatedAdd(id: Long) = ControlMutation.Add.prepare(ControlKind.RECOVERY_INTENT, java.util.UUID(0, id)) { issued ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(issued)) }
    private suspend fun refusedByDependency(id: String, f: TerminationFixture, c: CommandRef, expected: (CompletionRejectionReason?) -> Boolean) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        assertTrue("D2B6/6-4aB.$id: reason $r", expected((r as? ControlCompletionResult.Rejected)?.reason))
        assertEquals("D2B6/6-4aB.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-4aB.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-4aB.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-4aB.$id: recordUntouched", before, f.storage.raw())
    }
    @Test fun LB_17_businessRowDependentsPassAndOnlyTheAppliedRowOrAGapBlocks() = runBlocking {
        run { // Dependents over the same DEMAND/HOLD business rows, in commands, U, P and executing: the row is not deleted.
            val w = W.all.single { it.transition == LifecycleTransition.RECOVER_HOLD }
            val f = fixture(); val c = confirmed(f, w); val before = f.disk()
            val guard = node(arr(before, demandKey).first { it.jsonObject.getValue("id").jsonPrimitive.content == "g" }.toString())
            val m = f.store.prepare(ControlMutation.Edit.prepare(ControlKind.DEMAND, guard) {})
            val u = CommandRef("u-guard", listOf(ControlMutation.Edit.prepare(ControlKind.DEMAND, guard) {}), OwnerTrackingLifetimeId.issue()); f.addUnresolved(u)
            val p = CommandRef("p-guard", listOf(ControlMutation.Edit.prepare(ControlKind.DEMAND, guard) {}), OwnerTrackingLifetimeId.issue()); f.addPending(p)
            val x = CommandRef("e-guard", listOf(ControlMutation.Edit.prepare(ControlKind.DEMAND, guard) {}), OwnerTrackingLifetimeId.issue())
            check(f.tracker.executing.add(x))
            try {
                val r = consume(f, c)
                assertTrue("D2B6/6-4aB.17 businessDependents: $r", r is ControlCompletionResult.Completed)
                assertEquals("D2B6/6-4aB.17 businessDependents exactDeletion", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            } finally { f.tracker.executing.remove(x) }
            check(m.id.isNotEmpty())
        }
        run { // Another ref's release descriptor naming this Applied row: known intersection.
            val f = fixture(); val c = confirmed(f); val m = f.mutations()
            val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
                targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
            ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
            refusedByDependency("17 appliedRow", f, c) { it == CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
                DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)) }
        }
        run { // A pending ref missing its descriptor: a broad gap that may reach the Applied row.
            val f = fixture(); val c = confirmed(f); val m = f.mutations()
            ControlReleaseFixtures.pending(m); f.addPending(m)
            refusedByDependency("17 gap", f, c) { it is CompletionRejectionReason.DependencyUnknown && it.dependentCommandId == m.id }
        }
        run { // Unrelated U and P pass.
            val g = fixture(); val d = confirmed(g); val before = g.disk()
            g.addUnresolved(CommandRef("u-free", listOf(unrelatedAdd(93)), OwnerTrackingLifetimeId.issue()))
            g.addPending(CommandRef("p-free", listOf(unrelatedAdd(94)), OwnerTrackingLifetimeId.issue()))
            assertConsumed("17 unrelated", g, d, consume(g, d), expectedAfterDeletion(before, d))
        }
    }
    @Test fun LB_18_retryAfterADependentAppearedIsHeld() = runBlocking {
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        val m = f.mutations()
        val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        retryHeld("18", f, c, d, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)))
    }

    // ── T6: after termination the ref is closed without storage access ────────────────────────────────────────
    @Test fun LB_19_aTerminatedRefIsRefusedByItsOwnGates() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk()
        assertConsumed("19", f, c, consume(f, c), expectedAfterDeletion(before, c))
        val access = f.boundary.accesses
        val a = controlTestTimeout("execute") { f.store.execute(c) }
        val b = controlTestTimeout("execute ctx") { f.store.execute(c, AttemptContext("A", 3, LifetimeId("life"), false, false)) }
        val p = controlTestTimeout("confirmPrevious") { f.store.confirmPrevious(c) }
        assertTrue("D2B6/6-4aB.19 execute: $a", a is ControlStoreResult.Terminated)
        assertTrue("D2B6/6-4aB.19 executeContext: $b", b is ControlStoreResult.Terminated)
        assertTrue("D2B6/6-4aB.19 confirmPrevious: $p", p is ControlStoreResult.Terminated)
        assertEquals("D2B6/6-4aB.19 noStorage", 0, f.boundary.accesses - access)
    }

    // ── descriptor projection ──────────────────────────────────────────────────────────────────────────────────
    private fun project(ref: CommandRef, d: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(ref.id,
        ref.ownerTrackingLifetimeId.value, RefView(ControlCommandLifecycle.TERMINATION_PENDING, ref.body), emptyList(), null, d))
    @Test fun LB_20_theLifecycleDescriptorProjectsItsAppliedRowAndEachInconsistencyIsAGap() {
        val life = OwnerTrackingLifetimeId.issue()
        val input = ControlLifecycleEvidenceFixtures.descriptor(id = "lc-proj")
        val ref = CommandRef(input.operationId, ControlCommandBody.Lifecycle(input), life)
        val row = AppliedEvidence.Lifecycle(ref.id, life.value, input.transition, input.targets.map { it.target })
        fun exact(mode: CompletionMode = CompletionMode.Consumed, entry: TerminationEntry = TerminationEntry.ConsumedLifecycle,
            expected: AppliedEvidence.Lifecycle = row, binding: TerminationClosureBinding = closure(ref).binding()) =
            TerminationPendingDescriptor.ExactLifecycleEvidence(mode, entry, binding, expected)
        val consistent = project(ref, exact())
        assertTrue("D2B6/6-4aB.20 consistent: $consistent", consistent is DependencyProjection.Known &&
            DependencyAtom.AppliedRow(ref.id, life.value) in consistent.dependencies)
        val mismatch = setOf(DependencyGapCause.TerminationDescriptorMismatch)
        val otherRef = CommandRef("lc-other", ControlCommandBody.Lifecycle(ControlLifecycleEvidenceFixtures.descriptor(id = "lc-other")), life)
        val cases = listOf(
            "mode" to exact(mode = CompletionMode.NeverSubmitted),
            "entry" to exact(entry = TerminationEntry.ConsumedSettlement),
            "expectedCommand" to exact(expected = AppliedEvidence.Lifecycle("other", life.value, row.transition, row.targets)),
            "expectedLifetime" to exact(expected = AppliedEvidence.Lifecycle(ref.id, OwnerTrackingLifetimeId.issue().value, row.transition, row.targets)),
            "expectedTransition" to exact(expected = AppliedEvidence.Lifecycle(ref.id, life.value, LifecycleTransition.UPDATE_AUTH,
                listOf(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE)))),
            "expectedTargetId" to exact(expected = AppliedEvidence.Lifecycle(ref.id, life.value, row.transition,
                row.targets.map { LifecycleTarget(it.kind, it.id + "-x", it.effect) })),
            "bindingCommand" to exact(binding = closure(otherRef).binding()))
        for ((name, d) in cases) {
            val p = project(ref, d)
            assertTrue("D2B6/6-4aB.20 $name: $p", p is DependencyProjection.Unknown &&
                p.gaps.map { it.cause }.toSet() == mismatch)
        }
    }

    // ── r2 (measurement: shadowed and missing projection checks) — each inconsistency alone ─────────────────────
    @Test fun LB_21_eachBodyAndBindingInconsistencyAloneIsAGap() {
        val life = OwnerTrackingLifetimeId.issue()
        val input = ControlLifecycleEvidenceFixtures.descriptor(id = "lc-proj")
        val ref = CommandRef(input.operationId, ControlCommandBody.Lifecycle(input), life)
        val row = AppliedEvidence.Lifecycle(ref.id, life.value, input.transition, input.targets.map { it.target })
        fun exact(binding: TerminationClosureBinding = closure(ref).binding()) =
            TerminationPendingDescriptor.ExactLifecycleEvidence(CompletionMode.Consumed, TerminationEntry.ConsumedLifecycle, binding, row)
        val mismatch = setOf(DependencyGapCause.TerminationDescriptorMismatch)
        fun gapOnly(name: String, p: DependencyProjection) = assertTrue("D2B6/6-4aB.21 $name: $p",
            p is DependencyProjection.Unknown && p.gaps.map { it.cause }.toSet() == mismatch)
        // The body is not a Lifecycle body (same id and lifetime).
        val mutationsRef = CommandRef(ref.id, listOf(ControlMutation.Add.prepare(ControlKind.RECOVERY_INTENT, java.util.UUID(0, 71)) { issued ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(issued)) }), life)
        // (A Mutations body without adoption facts also carries its own AdoptionUnavailable gap; the descriptor gap must be there.)
        val bodyKind = projectDependency(DependencyProjectionInput(ref.id, life.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, mutationsRef.body), emptyList(), null, exact()))
        assertTrue("D2B6/6-4aB.21 bodyKind: $bodyKind", bodyKind is DependencyProjection.Unknown &&
            DependencyGapCause.TerminationDescriptorMismatch in bodyKind.gaps.map { it.cause })
        // The body's operation id differs from the ref id (descriptor, expected and binding agree with the ref).
        val otherOp = ControlLifecycleEvidenceFixtures.descriptor(id = "lc-other-op")
        gapOnly("bodyOperation", projectDependency(DependencyProjectionInput(ref.id, life.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, ControlCommandBody.Lifecycle(otherOp)), emptyList(), null, exact())))
        // The binding alone: its command, its lifetime, its scope — each with the other two left equal to the ref.
        val otherRef = CommandRef("lc-other", ControlCommandBody.Lifecycle(ControlLifecycleEvidenceFixtures.descriptor(id = "lc-other")), life)
        gapOnly("bindingCommandOnly", project(ref, exact(closure(ref, command = otherRef).binding())))
        gapOnly("bindingLifetimeOnly", project(ref, exact(closure(ref, lifetime = OwnerTrackingLifetimeId.issue()).binding())))
        gapOnly("bindingScopeOnly", project(ref, exact(closure(ref, scope = ref.id + "-other").binding())))
    }
}
