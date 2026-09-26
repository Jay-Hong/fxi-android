package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.TerminationClosures.base
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-1B T4 contract (skeleton v12 §3·§4·§4.1·§4.2·§8 T4; revision 06 §3.2·§3.3·§4.3). Storage boundary of the
 * NeverConfirm termination. A read-back obligation is armed first so the management Confirm(original) really enters
 * the write scope. Failure or cancellation before the management Confirm keeps RETAINED and U/P; failure or
 * cancellation after the pending publication keeps TERMINATION_PENDING, P and the fixed EvidenceAbsent descriptor
 * (whose closure binding is the first declaration's value copy), with the landed / not-landed disk state told apart
 * before retry; retryTermination completes with exact U/P/commands cleanup, including c ∈ U∩P. A returned snapshot
 * that fails validation keeps the pending state (release precedent). After a restart the old ref stays closed for both
 * business entries (Terminated) and is refused by the new owner's management entries (WrongTrackerLifetime). Closure declarations: first-entry violations reject before any descriptor/P/pending;
 * retry violations — including OwnerMismatch and WorkSetChanged — keep the pending state without storage access; the
 * declaration copies its sets. Retry precedence is decided before the closure. Pending retry re-runs the whole-record
 * gate (observe first). The implementation thread reads but does not edit this file.
 */
class ControlNeverConfirmBoundaryContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture(file: java.io.File? = null) =
        (if (file == null) TerminationFixture(folder.root, opened.size) else TerminationFixture(folder.root, opened.size, file)).also { opened += it }
    private suspend fun abandon(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) =
        controlTestTimeout("abandon") { f.store.abandonBeforeFirstConfirm(c, k) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) =
        controlTestTimeout("retry") { f.store.retryTermination(c, k) }
    /** Leaves [c] TERMINATION_PENDING: read-back armed, then the management Confirm write fails before its block. */
    private suspend fun pending(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) {
        f.armReadBack(); f.storage.storage.before = true
        val r = abandon(f, c, k)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
    }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    // Preferences equality compares ByteArray values by content; a raw asMap() comparison would compare array identity.
    private fun withoutBarrier(p: Preferences): Preferences = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun barrier(p: Preferences) = p[ControlStoreTestStorage.BARRIER] ?: 0L
    private fun own(c: CommandRef) = ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))

    private fun assertCompletedCleanup(id: String, f: TerminationFixture, c: CommandRef, result: ControlCompletionResult) {
        assertTrue("D2B6/T4.$id: completed $result", result is ControlCompletionResult.Completed)
        assertEquals("D2B6/T4.$id: neverSubmitted", CompletionMode.NeverSubmitted, (result as ControlCompletionResult.Completed).mode)
        assertEquals("D2B6/T4.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/T4.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/T4.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        val work = f.tracker.recoverySnapshot()
        assertFalse("D2B6/T4.$id: exactU", c in work.unresolvedCommands)
        assertFalse("D2B6/T4.$id: exactP", c in work.pendingReleases)
        assertEquals("D2B6/T4.$id: resultSets", work.unresolvedCommands, result.localUnresolvedCommands)
        assertEquals("D2B6/T4.$id: resultSets", work.pendingReleases, result.localPendingReleases)
        assertTrue("D2B6/T4.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private fun assertPendingKept(id: String, f: TerminationFixture, c: CommandRef, descriptor: Any?) {
        assertEquals("D2B6/T4.$id: pendingKept", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertTrue("D2B6/T4.$id: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertTrue("D2B6/T4.$id: descriptorPresent", descriptor != null)
        assertSame("D2B6/T4.$id: descriptorKept", descriptor, f.history(c).terminationDescriptor)
        assertTrue("D2B6/T4.$id: bodyKept", c.captureStateAndBody().body != null)
        assertFalse("D2B6/T4.$id: leaseReleased", c in f.tracker.executing)
    }
    /** The fixed descriptor right after the pending publication: EvidenceAbsent(NeverSubmitted, Abandon, first binding). */
    private fun assertDescriptor(id: String, f: TerminationFixture, c: CommandRef, k: TerminationClosure): Any {
        val d = f.history(c).terminationDescriptor
        assertTrue("D2B6/T4.$id: evidenceAbsent $d", d is TerminationPendingDescriptor.EvidenceAbsent)
        d as TerminationPendingDescriptor.EvidenceAbsent
        assertEquals("D2B6/T4.$id: descriptorMode", CompletionMode.NeverSubmitted, d.mode)
        assertEquals("D2B6/T4.$id: descriptorEntry", TerminationEntry.AbandonBeforeFirstConfirm, d.entry)
        val b = d.closureBinding
        assertSame("D2B6/T4.$id: bindCommand", c, b.command)
        assertSame("D2B6/T4.$id: bindLifetime", c.ownerTrackingLifetimeId, b.ownerTrackingLifetimeId)
        assertEquals("D2B6/T4.$id: bindScope", k.relatedScope, b.relatedScope)
        assertEquals("D2B6/T4.$id: bindGeneration", k.currentGeneration, b.generation)
        assertEquals("D2B6/T4.$id: bindEntries", true, b.entriesClosed)
        assertEquals("D2B6/T4.$id: bindSets", listOf(k.captured, k.joined, k.registered), listOf(b.captured, b.joined, b.registered))
        assertEquals("D2B6/T4.$id: bindReceipts", true, b.receiptsClosed)
        assertEquals("D2B6/T4.$id: bindOwner", k.owner, b.owner)
        return d
    }

    // ── storage boundary ──────────────────────────────────────────────────────────────────────────────────────────
    @Test fun T4_01_readFaultBeforeManagementConfirmKeepsRetained() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val work = f.tracker.recoverySnapshot()
        f.boundary.failNextBeforeSnapshot = true
        val r = abandon(f, c)
        assertTrue("D2B6/T4.01: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/T4.01: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/T4.01: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/T4.01: setsKept", work, f.tracker.recoverySnapshot())
        assertCompletedCleanup("01r", f, c, abandon(f, c)) // Still NeverConfirm: the first entry may run again.
    }

    @Test fun T4_02_cancellationBeforeManagementConfirmKeepsRetained() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val work = f.tracker.recoverySnapshot()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.boundary.gate = reached to release
        val call: Deferred<Unit> = async { f.store.abandonBeforeFirstConfirm(c, closure(c)); Unit }
        controlTestTimeout("pre-snapshot gate") { reached.await() }
        call.cancel(); release.complete(Unit)
        val failure: Throwable? = try { call.await(); null } catch (e: CancellationException) { e }
        assertTrue("D2B6/T4.02: cancellationPropagates", failure is CancellationException)
        assertEquals("D2B6/T4.02: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/T4.02: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/T4.02: setsKept", work, f.tracker.recoverySnapshot())
        assertTrue("D2B6/T4.02: leaseReleased", f.tracker.executing.isEmpty())
    }

    @Test fun T4_03_writeFaultBeforeBlockThenRetryWithCInUAndP() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations()
        val u = f.store.prepare(); f.addUnresolved(u); val p = f.store.prepare(); f.addPending(p)
        f.addUnresolved(c) // A first-Confirm-before read failure left c in U: after the pending publication c ∈ U∩P.
        val before = f.disk(); val k = closure(c)
        pending(f, c, k)
        val work = f.tracker.recoverySnapshot()
        assertTrue("D2B6/T4.03: cInUandP", c in work.unresolvedCommands && c in work.pendingReleases)
        val descriptor = assertDescriptor("03", f, c, k)
        assertPendingKept("03", f, c, descriptor)
        assertEquals("D2B6/T4.03: nothingLanded", before, f.disk())
        val r = retry(f, c)
        assertCompletedCleanup("03r", f, c, r)
        assertEquals("D2B6/T4.03: unrelatedKept", setOf(u), f.tracker.recoverySnapshot().unresolvedCommands)
        assertEquals("D2B6/T4.03: unrelatedKept", setOf(p), f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/T4.03: onlyBarrier", withoutBarrier(before), withoutBarrier(f.disk()))
    }

    private suspend fun afterBlockFault(id: String, landed: Boolean) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val k = closure(c)
        f.armReadBack(); val before = f.disk()
        if (landed) f.storage.storage.afterScope = true else f.storage.storage.after = true
        val r = abandon(f, c, k)
        assertTrue("D2B6/T4.$id: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        val descriptor = assertDescriptor(id, f, c, k)
        assertPendingKept(id, f, c, descriptor)
        val mid = f.disk() // The file, not the cache: tells landed from not landed.
        assertEquals("D2B6/T4.$id: contentUnchanged", withoutBarrier(before), withoutBarrier(mid))
        assertEquals("D2B6/T4.$id: landedOrNot", barrier(before) + if (landed) 1 else 0, barrier(mid))
        assertCompletedCleanup("${id}r", f, c, retry(f, c))
        assertEquals("D2B6/T4.$id: onlyBarrier", withoutBarrier(before), withoutBarrier(f.disk()))
    }
    /** afterScope: the write scope completed (landed), then the return failed. */
    @Test fun T4_04_landedButReturnFailedKeepsPendingThenRetryCompletes() = runBlocking { afterBlockFault("04", landed = true) }
    /** after: the block ran but the write scope failed (not landed). */
    @Test fun T4_05_blockRanButScopeFailedKeepsPendingThenRetryCompletes() = runBlocking { afterBlockFault("05", landed = false) }

    @Test fun T4_06_cancellationAfterLandingKeepsPendingThenRetryCompletes() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); f.armReadBack()
        val pause = ControlStoreTestStorage.Pause(); f.storage.storage.pauseAfterScope = pause
        val call: Deferred<Unit> = async { f.store.abandonBeforeFirstConfirm(c, closure(c)); Unit }
        controlTestTimeout("landed pause") { pause.reached.await() }
        call.cancel(); pause.release.complete(Unit)
        val failure: Throwable? = try { call.await(); null } catch (e: CancellationException) { e }
        assertTrue("D2B6/T4.06: cancellationPropagates", failure is CancellationException)
        assertPendingKept("06", f, c, f.history(c).terminationDescriptor)
        assertCompletedCleanup("06r", f, c, retry(f, c))
    }

    private suspend fun badReturn(id: String, change: (Preferences, CommandRef) -> Preferences) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val k = closure(c)
        f.boundary.afterReturn = { change(it, c) }
        val failure = runCatching { abandon(f, c, k) }.exceptionOrNull()
        assertTrue("D2B6/T4.$id: returnValidationFails $failure", failure is IllegalStateException)
        assertPendingKept(id, f, c, assertDescriptor(id, f, c, k))
    }
    @Test fun T4_07_returnedSnapshotWithOwnRowKeepsPending() = runBlocking {
        badReturn("07") { p, c -> p.toMutablePreferences().apply { this[evidenceKey] = "[${own(c)}]" }.toPreferences() }
    }
    @Test fun T4_08_returnedSnapshotWithOtherChangeKeepsPending() = runBlocking {
        badReturn("08") { p, _ -> p.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "changed" }.toPreferences() }
    }

    @Test fun T4_09_restartDoesNotRestoreOldRefAuthority() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val checkpoint = checkNotNull(f.store.checkpoint(c))
        assertCompletedCleanup("09", f, c, abandon(f, c))
        f.storage.close(); opened.remove(f)
        val g = fixture(f.file)
        val before = g.storage.raw(); val writes = g.storage.storage.writes
        for (r in listOf(g.store.execute(c), g.store.execute(c, NamespaceSettlementFixtures.context), g.store.confirmPrevious(c, checkpoint)))
            assertTrue("D2B6/T4.09: oldRefClosed $r", r is ControlStoreResult.Terminated)
        // The new owner has a new tracker lifetime object: the lifetime check precedes the terminal check.
        assertEquals("D2B6/T4.09: managementAbandonWrongLifetime", CompletionRejectionReason.WrongTrackerLifetime,
            (abandon(g, c) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/T4.09: managementRetryWrongLifetime", CompletionRejectionReason.WrongTrackerLifetime,
            (retry(g, c) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/T4.09: noStorageEffect", before, g.storage.raw()); assertEquals(writes, g.storage.storage.writes)
        assertTrue("D2B6/T4.09: newTrackerUntouched", g.tracker.recoverySnapshot().let { it.unresolvedCommands.isEmpty() && it.pendingReleases.isEmpty() })
    }

    // ── closure declaration: first entry (before descriptor/P/pending, no storage access) ────────────────────────
    private suspend fun firstEntryViolation(id: String, violation: ClosureViolation, bad: (CommandRef) -> TerminationClosure) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val t = f.history(c)
        val work = f.tracker.recoverySnapshot(); val before = f.storage.raw(); val accessBefore = f.boundary.accesses
        val r = abandon(f, c, bad(c))
        assertEquals("D2B6/T4.$id: noStorageAccess", 0, f.boundary.accesses - accessBefore)
        assertEquals("D2B6/T4.$id: closureViolation", CompletionRejectionReason.ClosureNotSatisfied(violation),
            (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/T4.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/T4.$id: noDescriptor", t.terminationDescriptor)
        assertEquals("D2B6/T4.$id: setsUnchanged", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/T4.$id: noWrite", before, f.storage.raw())
    }
    @Test fun T4_10_commandMismatchSameIdOtherRef() = runBlocking {
        firstEntryViolation("10", ClosureViolation.CommandMismatch) { c -> closure(c, command = CommandRef(c.id, c.body, c.ownerTrackingLifetimeId)) }
    }
    @Test fun T4_11_lifetimeMismatchSameText() = runBlocking {
        firstEntryViolation("11", ClosureViolation.LifetimeMismatch) { c -> closure(c, lifetime = ControlReleaseFixtures.sameLifetimeText(c.ownerTrackingLifetimeId)) }
    }
    @Test fun T4_12_relatedScopeMismatch() = runBlocking { firstEntryViolation("12", ClosureViolation.RelatedScopeMismatch) { closure(it, scope = "other") } }
    @Test fun T4_13_generationMismatch() = runBlocking { firstEntryViolation("13", ClosureViolation.GenerationMismatch) { closure(it, current = 8L) } }
    @Test fun T4_14_entriesOpen() = runBlocking { firstEntryViolation("14", ClosureViolation.EntriesOpen) { closure(it, entriesClosed = false) } }
    @Test fun T4_15_capturedJoinedMismatch() = runBlocking { firstEntryViolation("15", ClosureViolation.CapturedJoinedMismatch) { closure(it, joined = emptySet()) } }
    @Test fun T4_16_registeredMismatch() = runBlocking { firstEntryViolation("16", ClosureViolation.RegisteredMismatch) { closure(it, registered = base + "job-2") } }
    @Test fun T4_17_receiptsOpen() = runBlocking { firstEntryViolation("17", ClosureViolation.ReceiptsOpen) { closure(it, receiptsClosed = false) } }
    @Test fun T4_18_ownerMissingEmpty() = runBlocking { firstEntryViolation("18", ClosureViolation.OwnerMissing) { closure(it, owner = "") } }
    @Test fun T4_19_ownerMissingBlank() = runBlocking { firstEntryViolation("19", ClosureViolation.OwnerMissing) { closure(it, owner = "  ") } }
    @Test fun T4_20_emptyWorkSetsAreAllowed() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations()
        assertCompletedCleanup("20", f, c, abandon(f, c, closure(c, captured = emptySet(), joined = emptySet(), registered = emptySet())))
    }
    @Test fun T4_21_declarationCopiesItsSets() {
        val c = ControlReleaseFixtures.fixture().command
        val cap = mutableSetOf("job-1"); val join = mutableSetOf("job-1"); val reg = mutableSetOf("job-1")
        val k = closure(c, captured = cap, joined = join, registered = reg)
        for (s in listOf(cap, join, reg)) s += "job-2"
        assertEquals("D2B6/T4.21: capturedCopied", setOf("job-1"), k.captured)
        assertEquals("D2B6/T4.21: joinedCopied", setOf("job-1"), k.joined)
        assertEquals("D2B6/T4.21: registeredCopied", setOf("job-1"), k.registered)
    }

    // ── closure declaration: retry (pending, descriptor and P kept; no storage access, no Confirm) ───────────────
    private suspend fun retryViolation(id: String, violation: ClosureViolation, bad: (CommandRef) -> TerminationClosure) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); pending(f, c)
        val descriptor = f.history(c).terminationDescriptor
        val before = f.storage.raw(); val writes = f.storage.storage.writes; val accessBefore = f.boundary.accesses
        val r = retry(f, c, bad(c))
        assertEquals("D2B6/T4.$id: noStorageAccess", 0, f.boundary.accesses - accessBefore)
        assertEquals("D2B6/T4.$id: closureViolation", CompletionRejectionReason.ClosureNotSatisfied(violation),
            (r as? ControlCompletionResult.Rejected)?.reason)
        assertPendingKept(id, f, c, descriptor)
        assertEquals("D2B6/T4.$id: noWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
    }
    @Test fun T4_30_retryGenerationChanged() = runBlocking { retryViolation("30", ClosureViolation.GenerationMismatch) { closure(it, capture = 8L, current = 8L) } }
    @Test fun T4_31_retryWorkSetChanged() = runBlocking {
        retryViolation("31", ClosureViolation.WorkSetChanged) { closure(it, captured = setOf("job-2"), joined = setOf("job-2"), registered = setOf("job-2")) }
    }
    @Test fun T4_32_retryNewRegistration() = runBlocking { retryViolation("32", ClosureViolation.RegisteredMismatch) { closure(it, registered = base + "job-2") } }
    @Test fun T4_33_retryReceiptReopened() = runBlocking { retryViolation("33", ClosureViolation.ReceiptsOpen) { closure(it, receiptsClosed = false) } }
    @Test fun T4_34_retryEntriesReopened() = runBlocking { retryViolation("34", ClosureViolation.EntriesOpen) { closure(it, entriesClosed = false) } }
    @Test fun T4_35_retryOwnerChanged() = runBlocking { retryViolation("35", ClosureViolation.OwnerMismatch) { closure(it, owner = "owner-2") } }
    @Test fun T4_36_retryOwnerBlankIsMissingNotMismatch() = runBlocking { retryViolation("36", ClosureViolation.OwnerMissing) { closure(it, owner = " ") } }
    @Test fun T4_37_retryScopeChanged() = runBlocking { retryViolation("37", ClosureViolation.RelatedScopeMismatch) { closure(it, scope = "other") } }
    @Test fun T4_38_retryCommandMismatch() = runBlocking {
        retryViolation("38", ClosureViolation.CommandMismatch) { c -> closure(c, command = CommandRef(c.id, c.body, c.ownerTrackingLifetimeId)) }
    }
    @Test fun T4_39_retryLifetimeMismatch() = runBlocking {
        retryViolation("39", ClosureViolation.LifetimeMismatch) { c -> closure(c, lifetime = ControlReleaseFixtures.sameLifetimeText(c.ownerTrackingLifetimeId)) }
    }

    @Test fun T4_40_defensiveCopyPositiveAndNegative() = runBlocking {
        for ((id, mutated) in listOf("40a" to false, "40b" to true)) {
            val f = fixture(); f.storage.seed(); val c = f.mutations()
            val cap = mutableSetOf("job-1"); val join = mutableSetOf("job-1"); val reg = mutableSetOf("job-1")
            pending(f, c, closure(c, captured = cap, joined = join, registered = reg))
            val descriptor = f.history(c).terminationDescriptor
            val snapshot = setOf("job-1") // Pre-mutation values, held separately.
            for (s in listOf(cap, join, reg)) s += "job-2" // Mutate the originals after the declaration was made.
            if (!mutated) {
                assertCompletedCleanup(id, f, c, retry(f, c, closure(c, captured = snapshot, joined = snapshot, registered = snapshot)))
            } else {
                val r = retry(f, c, closure(c, captured = cap, joined = join, registered = reg))
                assertEquals("D2B6/T4.$id: workSetChanged", CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.WorkSetChanged),
                    (r as? ControlCompletionResult.Rejected)?.reason)
                assertPendingKept(id, f, c, descriptor)
            }
        }
    }

    // ── retry precedence: decided before the closure ───────────────────────────────────────────────────────────────
    @Test fun T4_50_retryPrecedenceOverBadClosure() = runBlocking {
        val f = fixture(); f.storage.seed()
        fun bad(c: CommandRef) = closure(c, scope = "bad")
        val done = f.mutations(); assertCompletedCleanup("50pre", f, done, abandon(f, done))
        assertTrue("D2B6/T4.50a: alreadyTerminated", retry(f, done, bad(done)) is ControlCompletionResult.AlreadyTerminated)
        val releasing = f.mutations(); ControlReleaseFixtures.simulatePending(f.tracker, releasing)
        assertEquals("D2B6/T4.50b: otherManagementPath", CompletionRejectionReason.OtherManagementPath,
            (retry(f, releasing, bad(releasing)) as? ControlCompletionResult.Rejected)?.reason)
        val retained = f.mutations()
        assertEquals("D2B6/T4.50c: notTerminationPending", CompletionRejectionReason.NotTerminationPending,
            (retry(f, retained, bad(retained)) as? ControlCompletionResult.Rejected)?.reason)
        val busy = f.mutations(); pending(f, busy); check(f.tracker.executing.add(busy))
        try {
            val accessBefore = f.boundary.accesses
            assertEquals("D2B6/T4.50d: inFlight", CompletionRejectionReason.InFlight,
                (retry(f, busy, bad(busy)) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T4.50d: noStorageAccess", 0, f.boundary.accesses - accessBefore)
        } finally { f.tracker.executing.remove(busy) }
        // Not pending is decided before the lease: a held business lease must not turn it into InFlight.
        val leased = f.mutations(); check(f.tracker.executing.add(leased))
        try {
            val accessBefore = f.boundary.accesses
            assertEquals("D2B6/T4.50f: notPendingBeforeLease", CompletionRejectionReason.NotTerminationPending,
                (retry(f, leased, bad(leased)) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T4.50f: noStorageAccess", 0, f.boundary.accesses - accessBefore)
            assertTrue("D2B6/T4.50f: leaseKeptByHolder", leased in f.tracker.executing)
        } finally { f.tracker.executing.remove(leased) }
        // A normally released ref (exact history already removed) is another management path, for both entries.
        val released = f.mutations()
        ControlReleaseFixtures.simulatePending(f.tracker, released); ControlReleaseFixtures.simulateReleased(f.tracker, released)
        val accessBeforeReleased = f.boundary.accesses
        assertEquals("D2B6/T4.50g: releasedAbandonOtherPath", CompletionRejectionReason.OtherManagementPath,
            (abandon(f, released, closure(released)) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/T4.50g: releasedRetryOtherPath", CompletionRejectionReason.OtherManagementPath,
            (retry(f, released, bad(released)) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/T4.50g: noStorageAccess", 0, f.boundary.accesses - accessBeforeReleased)
        val other = fixture(); val foreign = other.mutations()
        assertEquals("D2B6/T4.50e: wrongTrackerLifetime", CompletionRejectionReason.WrongTrackerLifetime,
            (retry(f, foreign, bad(foreign)) as? ControlCompletionResult.Rejected)?.reason)
    }

    // ── pending retry re-runs the whole-record gate ────────────────────────────────────────────────────────────────
    private suspend fun retryGate(id: String, expected: Any, observedFirst: Boolean, change: (MutablePreferences, CommandRef) -> Unit) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); pending(f, c)
        val descriptor = f.history(c).terminationDescriptor
        f.edit { change(it, c) }
        val before = f.storage.raw()
        val r = retry(f, c)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/T4.$id: recoveryRequired $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            ConflictReason::class -> assertTrue("D2B6/T4.$id: conflict $r", r is ControlCompletionResult.Conflict)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/T4.$id: observedApplied", observedFirst, f.history(c).observedApplied.get())
        assertPendingKept(id, f, c, descriptor)
        assertEquals("D2B6/T4.$id: noContentWrite", withoutBarrier(before), withoutBarrier(f.storage.raw()))
        // 6-1 completion (§8 T4 "Confirm 미요청"): pending() left the read-back armed, so any management Confirm would
        // write the read barrier. The whole record, barrier included, is therefore unchanged only if none was requested.
        assertEquals("D2B6/T4.$id: noConfirmRequested", before, f.storage.raw())
    }
    @Test fun T4_60_retryUnrelatedArrayUninterpretable() = runBlocking {
        retryGate("60", RecoveryReason.UninterpretableObligations, false) { p, _ -> p[ControlStoreTestStorage.DEMAND] = "[17]" }
    }
    @Test fun T4_61_retryMetadataUninterpretable() = runBlocking {
        retryGate("61", RecoveryReason.UninterpretableMetadata, false) { p, _ -> p[evidenceKey] = "[17]" }
    }
    @Test fun T4_62_retrySchemaNotTwo() = runBlocking {
        retryGate("62", RecoveryReason.ControlSchemaMigrationRequired, false) { p, _ ->
            p[ControlStoreTestStorage.SCHEMA] = 1; p.remove(evidenceKey); p.remove(ReclamationFixtures.fenceKey)
        }
    }
    @Test fun T4_63_retryUnreadableEnvelopeKeepsObservedApplied() = runBlocking {
        retryGate("63", RecoveryReason.UnreadableRecord, false) { p, c -> p[evidenceKey] = "[${own(c)}]"; p[ControlStoreTestStorage.HOLD] = "[" }
    }
    @Test fun T4_64_retryOwnAppliedObservedBeforeUninterpretable() = runBlocking {
        retryGate("64", RecoveryReason.UninterpretableObligations, true) { p, c -> p[evidenceKey] = "[${own(c)}]"; p[ControlStoreTestStorage.DEMAND] = "[17]" }
    }
    @Test fun T4_65_retryLateOwnRowIsConflict() = runBlocking {
        retryGate("65", ConflictReason::class, true) { p, c -> p[evidenceKey] = "[${own(c)}]" }
    }
}
