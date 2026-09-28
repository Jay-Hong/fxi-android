package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bB2 contract (6-4bB consensus r1 B-D1/B-D3, recipe 6-4bB2_recipe_codex.r1.md): the current-lifetime OnceConfirm·U
 * handoff gate, ControlRecordStore.inspectCurrentUncertainHandoffGate. It is an observation: it never confirms, writes, publishes a
 * descriptor or terminates, and its Eligible carries nothing a caller could reuse.
 *  - Entrance (no owner access at all): terminal → AlreadyTerminated; another management path → OtherManagementPath; another
 *    tracking lifetime; an unregistered ref object; the same ref in flight; not in U → NotUnresolved; in U without a business
 *    Confirm → NotOnceConfirm; a declaration for another ref → DeclarationRefMismatch; G04 → ClosureNotSatisfied(first violation
 *    in TerminationClosure.violation order).
 *  - One owner decision on the latest record: G07 first (unsupported/schema/opaque metadata/opaque obligations → RecoveryRequired,
 *    no clock read), then exactly one BootReadingSource read (missing, throwing, invalid → Clock refusal), then the full G05
 *    failure list with positions.
 * Real U state: a CURRENT_NULL settlement whose Confirm landed but whose return failed (afterScope fault); the six journal-owned
 * slots' retained confirmations are issued from a locked read; the REQUEST row is then consumed; HandoffCoordinator (B1) issues
 * the closure and the handoff. Every call is observed for owner accesses, store writes, deserialized preferences, the ref's state
 * and body, and the tracking sets.
 * The implementation thread reads but does not edit this file.
 */
class HandoffGateContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    @After fun close() = runReleaseTest { controlTestTimeout("B2 cleanup", 30_000) { fx.storage.close() } }
    private val N0 = CurrentNullFixtures
    private val journal = DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.USER, null))
    private val owned = setOf(ObligationComponent.JOURNAL, ObligationComponent.NAMESPACE_RETIREMENT)
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun gate(source: BootReadingSource? = BootReadingSource { reads.incrementAndGet(); now }) =
        ControlRecordStore(fx.storage.owner, bootReadingSource = source)

    private class Uncertain(val c: CommandRef, val a1: RequirementDerivation.Available, val closure: TerminationClosure,
        val handoff: CompletionHandoff, val owner: ResponsibilityOwner) {
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun k(i: Int) = required[i].key
    }

    private suspend fun uncertain(consumeRequest: Boolean = true): Uncertain {
        val s = N0.spec(companions = listOf(node(N0.companionUser)))
        controlTestTimeout("seed") { fx.storage.data.updateData { N0.raw(s) } }
        val c = fx.tracker.registerPrepared(N0.command(s, fx.tracker.lifetimeId))
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("execute") { fx.store.execute(c, N0.context) }
        assertTrue("fixture: Unconfirmed after the landed Confirm, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        val input = RequirementInput.Settlement(c, c.body as ControlCommandBody.Handover)
        val a1 = deriveRequiredObligations(input) as RequirementDerivation.Available
        val locked = controlTestTimeout("locked read") {
            fx.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, owner)
        for (slot in a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }) {
            if (slot.key.component in owned) {
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, journal, locked)
                assertTrue("fixture: token for ${slot.key}, got $t", t is RetainedSourceConfirmationResult.Issued)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(b, slot.key,
                    HandoffDisposition.DurablyOwned(journal, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
            } else {
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
            }
        }
        if (consumeRequest) controlTestTimeout("consume request") {
            fx.storage.data.updateData { raw ->
                val rows = N0.read(raw).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
                val kept = rows.filterNot { (it.text("id") as? FieldRead.Present)?.value == s.demandId }
                assertEquals("fixture: exactly one REQUEST consumed", rows.size - 1, kept.size)
                raw.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*kept.toTypedArray()) }.toPreferences()
            }
        }
        val issued = h.closeJoinAndIssueHandoff(input)
        assertTrue("fixture: B1 issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Uncertain(c, a1, issued.closure, issued.handoff, owner)
    }

    private class Observed(val accesses: Int, val writes: Int, val prefs: Preferences, val state: ControlCommandLifecycle, val body: Any?,
        val unresolved: Set<CommandRef>, val pending: Set<CommandRef>)
    private suspend fun observe(c: CommandRef): Observed {
        val v = c.captureStateAndBody(); val w = fx.tracker.recoverySnapshot()
        return Observed(fx.boundary.accesses, fx.storage.storage.writes, fx.disk(), v.state, v.body, w.unresolvedCommands, w.pendingReleases)
    }
    /** One gate call; asserts no write and nothing changed, and the given number of owner accesses. */
    private suspend fun inspect(store: ControlRecordStore, c: CommandRef, closure: TerminationClosure, handoff: CompletionHandoff,
        ownerAccesses: Int): HandoffGateDecision {
        val before = observe(c)
        val d = controlTestTimeout("gate") { store.inspectCurrentUncertainHandoffGate(c, closure, handoff) }
        val after = observe(c)
        assertEquals("owner accesses", before.accesses + ownerAccesses, after.accesses)
        assertEquals("no store write", before.writes, after.writes)
        assertEquals("deserialized preferences", before.prefs, after.prefs)
        assertEquals("ref state", before.state, after.state)
        assertSame("ref body", before.body, after.body)
        assertEquals("unresolved", before.unresolved, after.unresolved)
        assertEquals("pending releases", before.pending, after.pending)
        return d
    }
    private fun precondition(r: CompletionRejectionReason) = HandoffGateDecision.Rejected(HandoffGateRefusal.Precondition(r))
    private val fixed = G05Location.Fixed(FixedInputLocation(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE))
    private fun f(u: Uncertain, id: G05Id, i: Int, actual: G05Location?) = G05Failure(id, u.k(i), fixed, G05Location.Submitted(i), actual)
    private fun g05(failures: List<G05Failure>) = HandoffGateDecision.Rejected(HandoffGateRefusal.G05(failures))

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P1_issuedDeclarationForOnceConfirmU_eligible_oneOwnerAccess_oneClockRead() = runReleaseTest {
        val u = uncertain()
        assertEquals(HandoffGateDecision.Eligible, inspect(gate(), u.c, u.closure, u.handoff, ownerAccesses = 1))
        assertEquals("clock read once", 1, reads.get())
    }

    // ═══ entrance: no owner access ═════════════════════════════════════════════════════════════════════════════════════
    @Test fun E01_terminatedRef_alreadyTerminated() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations()
        assertTrue("fixture: abandoned", controlTestTimeout("abandon") { fx.store.abandonBeforeFirstConfirm(m, TerminationClosures.of(m)) }
            is ControlCompletionResult.Completed)
        assertEquals(HandoffGateDecision.AlreadyTerminated, inspect(gate(), m, TerminationClosures.of(m), u.handoff, 0))
    }

    @Test fun E02_releasedRef_otherManagementPath() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations()
        assertTrue("fixture: confirmed", controlTestTimeout("execute m") { fx.store.execute(m) } is ControlStoreResult.Confirmed)
        controlTestTimeout("release m") { fx.store.releaseAfterConsumption(m) }
        assertEquals(precondition(CompletionRejectionReason.OtherManagementPath), inspect(gate(), m, TerminationClosures.of(m), u.handoff, 0))
    }

    @Test fun E03_anotherTrackingLifetime_wrongTrackerLifetime() = runReleaseTest {
        val u = uncertain()
        val foreign = CommandRef(u.c.id, u.c.body, OwnerTrackingLifetimeId.issue())
        assertEquals(precondition(CompletionRejectionReason.WrongTrackerLifetime), inspect(gate(), foreign, u.closure, u.handoff, 0))
    }

    @Test fun E04_sameIdButNotTheRegisteredObject_notRegisteredIdentity() = runReleaseTest {
        val u = uncertain()
        val twin = CommandRef(u.c.id, u.c.body, u.c.ownerTrackingLifetimeId)
        assertEquals(precondition(CompletionRejectionReason.NotRegisteredIdentity), inspect(gate(), twin, u.closure, u.handoff, 0))
    }

    /** The same ref held by another owner transaction (paused inside it): InFlight, without an owner access of its own. */
    @Test fun E05_sameRefInFlight_inFlight() = runReleaseTest {
        val u = uncertain()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fx.boundary.gate = reached to release
        val holder = async { fx.store.confirmPrevious(u.c) }
        try {
            controlTestTimeout("holder inside the owner") { reached.await() }
            assertEquals(precondition(CompletionRejectionReason.InFlight), inspect(gate(), u.c, u.closure, u.handoff, 0))
        } finally {
            release.complete(Unit); holder.await()
        }
    }

    @Test fun E06_confirmedRefNotInU_notUnresolved() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations()
        assertTrue("fixture: confirmed", controlTestTimeout("execute m") { fx.store.execute(m) } is ControlStoreResult.Confirmed)
        assertTrue("fixture: not in U", m !in fx.tracker.snapshot())
        assertEquals(HandoffGateDecision.Rejected(HandoffGateRefusal.NotUnresolved), inspect(gate(), m, TerminationClosures.of(m), u.handoff, 0))
    }

    /** A ref marked unresolved without any business Confirm is not OnceConfirm·U. */
    @Test fun E07_unresolvedWithoutBusinessConfirm_notOnceConfirm() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations(); fx.addUnresolved(m)
        assertEquals(HandoffGateDecision.Rejected(HandoffGateRefusal.NotOnceConfirm), inspect(gate(), m, TerminationClosures.of(m), u.handoff, 0))
    }

    @Test fun E08_declarationForAnotherRef_declarationRefMismatch() = runReleaseTest {
        val u = uncertain()
        val other = fx.mutations()
        val forged = CompletionHandoff(u.handoff.command.copy(ref = other), u.handoff.responsibilityOwner, u.handoff.slots)
        assertEquals(HandoffGateDecision.Rejected(HandoffGateRefusal.DeclarationRefMismatch), inspect(gate(), u.c, u.closure, forged, 0))
    }

    /** G04, one violation at a time, in TerminationClosure.violation order. */
    @Test fun E09_closureViolations_closureNotSatisfied() = runReleaseTest {
        val u = uncertain()
        val k = u.closure
        fun variant(command: CommandRef = k.command, lifetime: OwnerTrackingLifetimeId = k.ownerTrackingLifetimeId, scope: String = k.relatedScope,
            capture: Long = k.generationAtCapture, current: Long = k.currentGeneration, entries: Boolean = k.entriesClosed,
            captured: Set<String> = k.captured, joined: Set<String> = k.joined, registered: Set<String> = k.registered,
            receipts: Boolean = k.receiptsClosed, owner: String = k.owner) =
            TerminationClosure(command, lifetime, scope, capture, current, entries, captured, joined, registered, receipts, owner)
        val cases = listOf(
            variant(command = fx.mutations()) to ClosureViolation.CommandMismatch,
            variant(lifetime = OwnerTrackingLifetimeId.issue()) to ClosureViolation.LifetimeMismatch,
            variant(scope = "other-scope") to ClosureViolation.RelatedScopeMismatch,
            variant(current = k.generationAtCapture + 1) to ClosureViolation.GenerationMismatch,
            variant(entries = false) to ClosureViolation.EntriesOpen,
            variant(joined = k.captured + "extra") to ClosureViolation.CapturedJoinedMismatch,
            variant(registered = k.captured + "extra") to ClosureViolation.RegisteredMismatch,
            variant(receipts = false) to ClosureViolation.ReceiptsOpen,
            variant(owner = "") to ClosureViolation.OwnerMissing)
        for ((closure, violation) in cases)
            assertEquals("$violation", precondition(CompletionRejectionReason.ClosureNotSatisfied(violation)), inspect(gate(), u.c, closure, u.handoff, 0))
    }

    // ═══ owner decision ════════════════════════════════════════════════════════════════════════════════════════════════
    /** Same ref, another fixed command kind in the declaration's binding: G05.ref only. */
    @Test fun O01_declarationBindingKindDiffers_g05Ref() = runReleaseTest {
        val u = uncertain()
        val forged = CompletionHandoff(u.handoff.command.copy(kind = FixedCommandKind.Rotation), u.handoff.responsibilityOwner, u.handoff.slots)
        assertEquals(g05(listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null))), inspect(gate(), u.c, u.closure, forged, 1))
    }

    /** The shared journal gone: all six journal-owned slots lose destination and confirmation. */
    @Test fun O02_journalGone_destinationAndConfirmationOnSixSlots() = runReleaseTest {
        val u = uncertain()
        fx.edit { it.remove(PURGE_JOURNAL) }
        val slots = listOf(2, 3, 6, 7, 8, 9)
        assertEquals(g05(slots.map { f(u, G05Id.DESTINATION, it, null) } + slots.map { f(u, G05Id.CONFIRMATION, it, null) }),
            inspect(gate(), u.c, u.closure, u.handoff, 1))
    }

    /** The after fence rewritten: the retirements' confirmation and lower bound at the metadata. */
    @Test fun O03_afterFenceChanged_retirementsConfirmationAndLowerBound() = runReleaseTest {
        val u = uncertain()
        fx.edit { it[USER_EPOCH] = "u3" }
        val r = listOf(8, 9)
        assertEquals(g05(r.map { f(u, G05Id.CONFIRMATION, it, G05Location.ActualMetadata) } + r.map { f(u, G05Id.LOWER_BOUND, it, G05Location.ActualMetadata) }),
            inspect(gate(), u.c, u.closure, u.handoff, 1))
    }

    /** The REQUEST row still present while both REQUEST slots are declared completed. */
    @Test fun O04_requestNotConsumed_completedConflictOnBothRequestSlots() = runReleaseTest {
        val u = uncertain(consumeRequest = false)
        val latest = N0.read(fx.disk())
        val (kind, entry) = latest.locations("n-demand").single()
        val at = G05Location.ActualPayload(kind, latest.arrays.getValue(kind).entries.indexOfFirst { it === entry })
        assertEquals(g05(listOf(f(u, G05Id.COMPLETED_CONFLICT, 10, at), f(u, G05Id.COMPLETED_CONFLICT, 11, at))),
            inspect(gate(), u.c, u.closure, u.handoff, 1))
    }

    /** A declaration missing the companion's journal L slot: the full G05 list is forwarded (coverage). */
    @Test fun O05_declarationMissingOneSlot_coverageL() = runReleaseTest {
        val u = uncertain()
        val forged = CompletionHandoff(u.handoff.command, u.handoff.responsibilityOwner, u.handoff.slots.filter { it.key != u.k(6) })
        assertEquals(g05(listOf(G05Failure(G05Id.COVERAGE_L, u.k(6), fixed, null, null))), inspect(gate(), u.c, u.closure, forged, 1))
    }

    /** G07 before the clock: an opaque SEAL row is a recovery, and the clock is not read. */
    @Test fun O06_opaqueObligation_recoveryUninterpretableObligations_noClockRead() = runReleaseTest {
        val u = uncertain()
        fx.edit { p ->
            val rows = N0.read(p.toPreferences()).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
            p[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*(rows + node("""{"kind":"NAMESPACE"}""")).toTypedArray())
        }
        assertEquals(HandoffGateDecision.RecoveryRequired(RecoveryReason.UninterpretableObligations), inspect(gate(), u.c, u.closure, u.handoff, 1))
        assertEquals("no clock read", 0, reads.get())
    }

    @Test fun O07_controlSchemaNotTwo_recoveryControlSchemaMigrationRequired() = runReleaseTest {
        val u = uncertain()
        fx.edit {
            it[ControlStoreTestStorage.SCHEMA] = 1
            for (key in listOf(ControlPayloadKey.COMMAND_EVIDENCE, ControlPayloadKey.SCOPE_FENCE)) it.remove(ControlRecordKeys.payload(key))
        }
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: a supported schema-1 record, got $read", read is ControlRecordRead.Supported && read.schemaVersion == 1)
        assertEquals(HandoffGateDecision.RecoveryRequired(RecoveryReason.ControlSchemaMigrationRequired), inspect(gate(), u.c, u.closure, u.handoff, 1))
        assertEquals("no clock read", 0, reads.get())
    }

    /** B-D3: no source, a throwing source, an invalid reading (empty boot id, negative elapsed). A null boot id is valid. */
    @Test fun O08_clockMissingThrowingOrInvalid_clockRefusal() = runReleaseTest {
        val u = uncertain()
        assertEquals(HandoffGateDecision.Rejected(HandoffGateRefusal.Clock(BootReadingRefusal.SOURCE_MISSING)), inspect(gate(null), u.c, u.closure, u.handoff, 1))
        assertEquals(HandoffGateDecision.Rejected(HandoffGateRefusal.Clock(BootReadingRefusal.SOURCE_EXCEPTION)),
            inspect(gate(BootReadingSource { throw IllegalStateException("clock") }), u.c, u.closure, u.handoff, 1))
        for (bad in listOf(BootReading("", 20_000), BootReading("boot", -1)))
            assertEquals("$bad", HandoffGateDecision.Rejected(HandoffGateRefusal.Clock(BootReadingRefusal.INVALID_READING)),
                inspect(gate(BootReadingSource { bad }), u.c, u.closure, u.handoff, 1))
        assertEquals("a null boot id is valid unknown continuity", HandoffGateDecision.Eligible,
            inspect(gate(BootReadingSource { BootReading(null, 20_000) }), u.c, u.closure, u.handoff, 1))
    }

    // ═══ contract r3: battery r1 additions ═══════════════════════════════════════════════════════════════════════════════
    /** R01: the ref terminates between the entrance precheck and the lease; the recheck after the lease sees it. */
    @Test fun R01_terminatedBetweenPrecheckAndLease_alreadyTerminated() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations()
        val tracker = fx.tracker
        val reached = java.util.concurrent.CountDownLatch(1); val resume = java.util.concurrent.CountDownLatch(1)
        val delegate = tracker.executing; val first = java.util.concurrent.atomic.AtomicBoolean(true)
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean {
                if (element === m && first.compareAndSet(true, false)) {
                    reached.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "lease pause timed out" }
                }
                return delegate.add(element)
            }
        })
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val store = gate()
            val future = worker.submit<HandoffGateDecision> {
                kotlinx.coroutines.runBlocking { store.inspectCurrentUncertainHandoffGate(m, TerminationClosures.of(m), u.handoff) }
            }
            assertTrue("gate paused at the lease", reached.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("fixture: abandoned meanwhile", controlTestTimeout("abandon") { fx.store.abandonBeforeFirstConfirm(m, TerminationClosures.of(m)) }
                is ControlCompletionResult.Completed)
            resume.countDown()
            assertEquals(HandoffGateDecision.AlreadyTerminated, future.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("lease released", delegate.isEmpty())
        } finally { resume.countDown(); worker.shutdownNow(); ControlReleaseFixtures.replaceLease(tracker, delegate) }
    }

    /** R02: a Mutations OnceConfirm·U ref and an empty declaration: coverage for every slot derived from the tracked adoption. */
    @Test fun R02_mutationsUncertainWithEmptyDeclaration_coverageForEveryDerivedSlot() = runReleaseTest {
        val u = uncertain()
        val m = fx.mutations()
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("execute m") { fx.store.execute(m) }
        assertTrue("fixture: Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: m in U", m in fx.tracker.snapshot())
        val targets = checkNotNull(fx.tracker.findPrepared(m)).targets.get()
        val a = deriveRequiredObligations(RequirementInput.Mutations(m, m.body as ControlCommandBody.Mutations, MutationAdoption.Current(targets)))
            as RequirementDerivation.Available
        val required = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertTrue("fixture: slots derived", required.isNotEmpty())
        fun fixedOf(s: RequiredSlot) = G05Location.Fixed((s.requirement as SlotRequirement.Required).fixedSources.first().location)
        val expected = required.filter { it.key.branch == LandingBranch.L }.map { G05Failure(G05Id.COVERAGE_L, it.key, fixedOf(it), null, null) } +
            required.filter { it.key.branch == LandingBranch.N }.map { G05Failure(G05Id.COVERAGE_N, it.key, fixedOf(it), null, null) }
        assertEquals(g05(expected), inspect(gate(), m, TerminationClosures.of(m), CompletionHandoff(a.commandBinding, ResponsibilityOwner(m.ownerTrackingLifetimeId, "owner-1"), emptyList()), 1))
        assertEquals("clock read once", 1, reads.get())
    }

    /** R03: the owner read fails before any snapshot: ReadFailed, no clock read. */
    @Test fun R03_ownerReadFails_readFailed() = runReleaseTest {
        val u = uncertain()
        fx.boundary.failNextBeforeSnapshot = true
        val d = inspect(gate(), u.c, u.closure, u.handoff, 1)
        assertTrue("ReadFailed, got $d", d is HandoffGateDecision.ReadFailed)
        assertEquals("no clock read", 0, reads.get())
    }

    /** R04: schema 1 while the v2 payload keys remain: unreadable. */
    @Test fun R04_schemaOneWithV2Payloads_recoveryUnreadableRecord() = runReleaseTest {
        val u = uncertain()
        fx.edit { it[ControlStoreTestStorage.SCHEMA] = 1 }
        assertEquals(HandoffGateDecision.RecoveryRequired(RecoveryReason.UnreadableRecord), inspect(gate(), u.c, u.closure, u.handoff, 1))
        assertEquals("no clock read", 0, reads.get())
    }

    /** R05: no control schema and no control payload at all: migration or recovery. */
    @Test fun R05_noControlKeys_recoveryMigrationOrRecovery() = runReleaseTest {
        val u = uncertain()
        fx.edit {
            it.remove(ControlStoreTestStorage.SCHEMA)
            for (key in ControlPayloadKey.entries) it.remove(ControlRecordKeys.payload(key))
        }
        assertTrue("fixture: migration or recovery read", ControlRecordReader().read(fx.disk()) is ControlRecordRead.MigrationOrRecoveryRequired)
        assertEquals(HandoffGateDecision.RecoveryRequired(RecoveryReason.MigrationOrRecovery), inspect(gate(), u.c, u.closure, u.handoff, 1))
        assertEquals("no clock read", 0, reads.get())
    }

    /** R06: an uninterpretable metadata entry. */
    @Test fun R06_opaqueMetadata_recoveryUninterpretableMetadata() = runReleaseTest {
        val u = uncertain()
        fx.edit { it[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: opaque metadata", read is ControlRecordRead.Supported && read.hasUninterpretableMetadata)
        assertEquals(HandoffGateDecision.RecoveryRequired(RecoveryReason.UninterpretableMetadata), inspect(gate(), u.c, u.closure, u.handoff, 1))
        assertEquals("no clock read", 0, reads.get())
    }

    /**
     * R07: a clock that is cancelled propagates the cancellation (not a Clock refusal) and releases the lease. Identity is not
     * asserted: under -ea kotlinx.coroutines debug mode copies exceptions across suspensions for stack-trace recovery.
     */
    @Test fun R07_clockCancelled_cancellationPropagates_leaseReleased() = runReleaseTest {
        val u = uncertain()
        val cancelled = kotlinx.coroutines.CancellationException("clock")
        val thrown = runCatching { gate(BootReadingSource { throw cancelled }).inspectCurrentUncertainHandoffGate(u.c, u.closure, u.handoff) }.exceptionOrNull()
        assertTrue("CancellationException(clock), got $thrown", thrown is kotlinx.coroutines.CancellationException && thrown.message == "clock")
        assertTrue("lease released", fx.tracker.executing.isEmpty())
    }
}
