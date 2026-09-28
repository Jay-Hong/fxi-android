package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.jay.fxi.data.entitlements.RefreshIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4bB1 contract (6-4bB consensus r1 B-D2, recipe T1 G05C): the restricted handoff consumer. A HandoffCoordinator
 * owns one command's related work and its own event ledger; closeJoinAndIssueHandoff closes every entry kind and captures the
 * registered work under one linearization, cancels and joins the captured jobs within a finite deadline, re-checks the generation,
 * the registration set and the ledger, and only then issues an immutable TerminationClosure + CompletionHandoff whose slots are
 * the independently derived required slots in derivation order, each with the disposition the ledger recorded.
 *  - A DurablyOwned slot needs a recorded confirmed transfer; a started-only transfer is waited for up to the evidence deadline.
 *  - A CompletedAndConsumed slot needs both the completion event and its result-consumed event; every expected receipt must be
 *    consumed and a consumed receipt is carried in its slot's consumedReceipts.
 *  - Events for another command (identity) or another responsibility owner are rejected at record time.
 *  - Caller cancellation propagates as CancellationException; a join deadline is a refusal.
 *  - Issuance is one-shot; entries stay closed after any issuance attempt.
 * The coordinator holds no store: across every issuance call made through issue() the store sees no write, and the
 * deserialized preferences, the ref's state and body, and the tracking sets are unchanged. Issued values are structural bindings, not proof of external completion (B-D2).
 * Real CURRENT_NULL settlement fixture (USER NULL seal s + companion us): JOURNAL and NAMESPACE_RETIREMENT slots are transferred
 * with each slot's own retained confirmation; SEAL and REQUEST slots are completed and consumed.
 * The implementation thread reads but does not edit this file.
 */
class HandoffCoordinatorContractTest : CurrentNullOwnerBase() {
    private val N0 = CurrentNullFixtures
    private val journal = DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.USER, null))
    private val owned = setOf(ObligationComponent.JOURNAL, ObligationComponent.NAMESPACE_RETIREMENT)
    private val deadline = 300L

    private class Prepared(val c: CommandRef, val input: RequirementInput, val a1: RequirementDerivation.Available,
        val tokens: Map<RequiredObligationKey, PriorStorageConfirmation>, val owner: ResponsibilityOwner) {
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val binding = HandoffEventBinding(c, owner)
    }

    private suspend fun prepare(): Prepared {
        val s = N0.spec(companions = listOf(node(N0.companionUser)))
        seedN(s); val c = registerN(s)
        val confirmed = successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        val input = RequirementInput.Settlement(c, c.body as ControlCommandBody.Handover)
        val a1 = deriveRequiredObligations(input) as RequirementDerivation.Available
        val tokens = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required && it.key.component in owned }.associate { slot ->
            val r = PriorStorageConfirmation.confirmRetainedSource(slot, journal, confirmed)
            assertTrue("fixture: issued for ${slot.key}", r is RetainedSourceConfirmationResult.Issued)
            slot.key to (r as RetainedSourceConfirmationResult.Issued).value
        }
        return Prepared(c, input, a1, tokens, ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1"))
    }

    private class Closer(val refuse: HandoffEntryKind? = null) : HandoffEntryCloser {
        val closed = mutableListOf<HandoffEntryKind>()
        override fun close(kind: HandoffEntryKind): Boolean { closed += kind; return kind != refuse }
    }
    private fun coordinator(p: Prepared, closer: HandoffEntryCloser = Closer()) = HandoffCoordinator(p.c, p.owner, closer, deadline)

    private fun transfer(p: Prepared, key: RequiredObligationKey) = HandoffDisposition.DurablyOwned(journal, emptyList(), p.tokens.getValue(key))
    /** Feed the ledger with every required slot's events, except the listed ones. */
    private fun feed(h: HandoffCoordinator, p: Prepared, except: Set<RequiredObligationKey> = emptySet(),
        dispositions: MutableMap<RequiredObligationKey, Any> = mutableMapOf()): Map<RequiredObligationKey, Any> {
        for (slot in p.required) {
            if (slot.key in except) continue
            if (slot.key.component in owned) {
                val d = transfer(p, slot.key)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, slot.key, d))
                dispositions[slot.key] = d
            } else {
                val completion = ComponentCompletion(slot.key.subject)
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, slot.key, completion))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(p.binding, slot.key))
                dispositions[slot.key] = completion
            }
        }
        return dispositions
    }

    private class Observed(val writes: Int, val bytes: Preferences, val state: ControlCommandLifecycle, val body: Any?,
        val unresolved: Set<CommandRef>, val pending: Set<CommandRef>)
    private suspend fun observe(c: CommandRef): Observed {
        val v = c.captureStateAndBody()
        val w = tracking.recoverySnapshot()
        return Observed(o.storage.writes, disk(), v.state, v.body, w.unresolvedCommands, w.pendingReleases)
    }
    /** Run one issuance call and assert the store and the ref were not touched by it. */
    private suspend fun issue(p: Prepared, h: HandoffCoordinator, input: RequirementInput = p.input): HandoffIssueResult {
        val before = observe(p.c)
        val r = h.closeJoinAndIssueHandoff(input)
        val after = observe(p.c)
        assertEquals("no store write", before.writes, after.writes)
        assertEquals("deserialized preferences", before.bytes, after.bytes)
        assertEquals("ref state", before.state, after.state)
        assertSame("ref body", before.body, after.body)
        assertEquals("unresolved", before.unresolved, after.unresolved)
        assertEquals("pending releases", before.pending, after.pending)
        return r
    }
    private fun refused(r: HandoffIssueResult, reason: HandoffIssueRefusal) = assertEquals(HandoffIssueResult.Refused(reason), r)
    private fun issued(r: HandoffIssueResult): HandoffIssueResult.Issued {
        assertTrue("expected Issued, got $r", r is HandoffIssueResult.Issued)
        return r as HandoffIssueResult.Issued
    }
    /**
     * Related work lives outside runReleaseTest's scope and is cancelled when the test ends, so a coordinator that fails to cancel
     * or join (or a stub that throws) fails the test instead of hanging it.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun cancelWork() = workScope.cancel()
    private fun work(): Job = workScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
    /** A job that ignores cancellation until released: join cannot finish within the deadline. */
    private fun stuck(release: CompletableDeferred<Unit>, started: CompletableDeferred<Unit>? = null): Job =
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { started?.complete(Unit); release.await() } }
        }
    /** A job that runs [block] after being cancelled, i.e. after capture, while the coordinator joins it. */
    private fun onCancel(block: () -> Unit): Job = workScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { block() }
    }

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P1_twoJoinedJobs_allSlotsConfirmedOrConsumed_issuedWithExactClosureAndHandoff() = runReleaseTest {
        val p = prepare(); val closer = Closer(); val h = coordinator(p, closer)
        val j1 = work(); val j2 = work()
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", j1))
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.QUERY, "w2", j2))
        val fed = feed(h, p)
        val t = issued(issue(p, h))
        assertEquals("every entry kind closed", HandoffEntryKind.entries.toSet(), closer.closed.toSet())
        assertTrue("captured jobs cancelled and joined", j1.isCancelled && j1.isCompleted && j2.isCancelled && j2.isCompleted)
        assertEquals(null, t.closure.violation(p.c, p.c.ownerTrackingLifetimeId))
        assertSame(p.c, t.closure.command)
        assertEquals(p.c.id, t.closure.relatedScope)
        assertEquals(setOf("w1", "w2"), t.closure.captured)
        assertEquals(setOf("w1", "w2"), t.closure.joined)
        assertEquals(setOf("w1", "w2"), t.closure.registered)
        assertEquals("owner-1", t.closure.owner)
        assertEquals(p.a1.commandBinding, t.handoff.command)
        assertEquals(p.owner, t.handoff.responsibilityOwner)
        assertEquals("derivation order", p.required.map { it.key }, t.handoff.slots.map { it.key })
        for (slot in t.handoff.slots) when (val d = slot.disposition) {
            is HandoffDisposition.DurablyOwned -> assertSame("the recorded transfer", fed[slot.key], d)
            is HandoffDisposition.CompletedAndConsumed -> {
                assertEquals(fed[slot.key], d.completion); assertEquals(emptyList<ReceiptIdentity>(), d.consumedReceipts)
            }
        }
    }

    /** An expected receipt that was consumed is carried in its slot's consumedReceipts. */
    @Test fun P2_consumedExpectedReceipt_carriedInItsSlot() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val sealL = p.required.first { it.key.component == ObligationComponent.SEAL }.key
        val receipt = ReceiptIdentity.Settlement(p.c.id, HandoverSettlementTransition.CURRENT_NULL)
        assertEquals(RecordResult.Recorded, h.expectReceipt(p.binding, receipt, sealL))
        assertEquals(RecordResult.Recorded, h.recordReceiptConsumed(p.binding, receipt))
        feed(h, p)
        val t = issued(issue(p, h))
        val d = t.handoff.slots.single { it.key == sealL }.disposition as HandoffDisposition.CompletedAndConsumed
        assertEquals(listOf<ReceiptIdentity>(receipt), d.consumedReceipts)
        assertTrue("closure receipts closed", t.closure.receiptsClosed)
    }

    /** No related work at all: empty sets are not refused by themselves. */
    @Test fun P3_noRelatedWork_issuedWithEmptySets() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        feed(h, p)
        val t = issued(issue(p, h))
        assertEquals(emptySet<String>(), t.closure.captured)
        assertEquals(emptySet<String>(), t.closure.joined)
        assertEquals(emptySet<String>(), t.closure.registered)
        assertEquals(null, t.closure.violation(p.c, p.c.ownerTrackingLifetimeId))
    }

    // ═══ negative ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun N1_oneEntryKindFailsToClose_entriesOpen() = runReleaseTest {
        val p = prepare(); val h = coordinator(p, Closer(refuse = HandoffEntryKind.TOPIC))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.ENTRIES_OPEN)
    }

    /** A captured job that does not finish within the deadline: refused, and entries stay closed afterwards. */
    @Test fun N2_capturedJobNotJoinedWithinDeadline_workNotJoined_entriesStayClosed() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val release = CompletableDeferred<Unit>()
        val j = stuck(release)
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.RETRY, "w1", j))
        feed(h, p)
        try {
            refused(issue(p, h), HandoffIssueRefusal.WORK_NOT_JOINED)
            val late = work()
            try {
                assertEquals(RegistrationResult.Rejected(RegistrationRefusal.ENTRIES_CLOSED),
                    h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "late", late))
            } finally {
                late.cancel()
            }
        } finally {
            j.cancel()
            release.complete(Unit)
            j.join()
        }
    }

    /** Registration before closure is captured and joined (P1); registration after closure is refused. */
    @Test fun N3_registrationAfterClosure_refusedEntriesClosed() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        feed(h, p)
        issued(issue(p, h))
        val late = work()
        assertEquals(RegistrationResult.Rejected(RegistrationRefusal.ENTRIES_CLOSED), h.registerWork(p.binding, HandoffEntryKind.QUERY, "late", late))
        late.cancel()
    }

    @Test fun N4_generationAdvancedAfterCapture_generationChanged() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val j = onCancel { assertEquals(RecordResult.Recorded, h.advanceGeneration(p.binding)) }
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", j))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.GENERATION_CHANGED)
    }

    @Test fun N5_registrationSetChangedAfterCapture_registrationChanged() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val j = onCancel { assertEquals(RecordResult.Recorded, h.unregisterWork(p.binding, "w1")) }
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", j))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.REGISTRATION_CHANGED)
    }

    /** A transfer that started (it may have landed) but whose confirmation never came back: not issued on the landing alone. */
    @Test fun N6_transferStartedButNeverConfirmed_transferNotConfirmed() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val j = p.required.first { it.key.component == ObligationComponent.JOURNAL }.key
        assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, j))
        feed(h, p, except = setOf(j))
        refused(issue(p, h), HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
    }

    @Test fun N7_completedButResultNotConsumed_completionNotConsumed() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val q = p.required.first { it.key.component == ObligationComponent.REQUEST }.key
        assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, q, ComponentCompletion(q.subject)))
        feed(h, p, except = setOf(q))
        refused(issue(p, h), HandoffIssueRefusal.COMPLETION_NOT_CONSUMED)
    }

    @Test fun N7b_expectedReceiptNotConsumed_receiptsOpen() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val sealL = p.required.first { it.key.component == ObligationComponent.SEAL }.key
        assertEquals(RecordResult.Recorded, h.expectReceipt(p.binding, ReceiptIdentity.Settlement(p.c.id, HandoverSettlementTransition.CURRENT_NULL), sealL))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.RECEIPTS_OPEN)
    }

    /** Events for another command or another responsibility owner are rejected when recorded, and change nothing. */
    @Test fun N8_eventsForAnotherCommandOrOwner_rejectedAtRecord() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val other = N0.command(N0.spec(companions = listOf(node(N0.companionUser))), p.c.ownerTrackingLifetimeId)
        val q = p.required.first { it.key.component == ObligationComponent.REQUEST }.key
        for (b in listOf(HandoffEventBinding(other, p.owner), HandoffEventBinding(p.c, ResponsibilityOwner(p.c.ownerTrackingLifetimeId, "owner-2")))) {
            val rejectedJob = work()
            try {
                assertEquals(RegistrationResult.Rejected(RegistrationRefusal.OWNER_OR_COMMAND_MISMATCH),
                    h.registerWork(b, HandoffEntryKind.CALLBACK, "x", rejectedJob))
                assertEquals(RecordResult.Rejected(RecordRefusal.OWNER_OR_COMMAND_MISMATCH),
                    h.recordComponentCompleted(b, q, ComponentCompletion(q.subject)))
            } finally {
                rejectedJob.cancel()
            }
        }
        feed(h, p)
        issued(issue(p, h))
        Unit
    }

    /** The requirement input of another command: refused. */
    @Test fun N8b_requirementInputOfAnotherCommand_ownerOrCommandMismatch() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        feed(h, p)
        val other = N0.command(N0.spec(companions = listOf(node(N0.companionUser))), p.c.ownerTrackingLifetimeId)
        refused(issue(p, h, RequirementInput.Settlement(other, other.body as ControlCommandBody.Handover)), HandoffIssueRefusal.OWNER_OR_COMMAND_MISMATCH)
    }

    /** One required slot has no event at all: refused as incomplete. */
    @Test fun N9_requiredSlotWithoutEvents_incompleteOrInvalidSlots() = runReleaseTest {
        val p = prepare()
        for (missing in listOf(p.required.first { it.key.branch == LandingBranch.L }.key, p.required.first { it.key.branch == LandingBranch.N }.key)) {
            val h = coordinator(p)
            feed(h, p, except = setOf(missing))
            refused(issue(p, h), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
        }
    }

    /** The same slot recorded twice is refused at record time. */
    @Test fun N9b_duplicateEventForOneSlot_rejectedAtRecord() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val j = p.required.first { it.key.component == ObligationComponent.JOURNAL }.key
        assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, j, transfer(p, j)))
        assertEquals(RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT), h.recordConfirmedTransfer(p.binding, j, transfer(p, j)))
    }

    @Test fun N10_secondIssuance_alreadyIssued() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        feed(h, p)
        issued(issue(p, h))
        refused(issue(p, h), HandoffIssueRefusal.ALREADY_ISSUED)
    }

    // ═══ cancellation ══════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun C1_callerCancelledWhileJoining_cancellationPropagates() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val release = CompletableDeferred<Unit>(); val joining = CompletableDeferred<Unit>()
        val j = stuck(release, joining)
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", j))
        feed(h, p)
        val before = observe(p.c)
        val call = async { h.closeJoinAndIssueHandoff(p.input) }
        controlTestTimeout("join started") { joining.await() }
        call.cancel()
        val thrown = runCatching { call.await() }.exceptionOrNull()
        assertTrue("CancellationException, got $thrown", thrown is CancellationException)
        j.cancel(); release.complete(Unit); j.join()
        val after = observe(p.c)
        assertEquals(before.writes, after.writes); assertEquals(before.bytes, after.bytes); assertEquals(before.state, after.state)
    }

    /** Cancelled at the call's first suspension while a started transfer still awaits confirmation: cancellation propagates. */
    @Test fun C2_callerCancelledWhileConfirmationPending_cancellationPropagates() = runReleaseTest {
        val p = prepare(); val h = HandoffCoordinator(p.c, p.owner, Closer(), deadline, evidenceTimeoutMillis = 60_000)
        val j = p.required.first { it.key.component == ObligationComponent.JOURNAL }.key
        assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, j))
        feed(h, p, except = setOf(j))
        val before = observe(p.c)
        val call = async(start = CoroutineStart.UNDISPATCHED) { h.closeJoinAndIssueHandoff(p.input) }
        assertTrue("fixture: the call is suspended, not finished", call.isActive)
        call.cancel()
        val thrown = runCatching { call.await() }.exceptionOrNull()
        assertTrue("CancellationException, got $thrown", thrown is CancellationException)
        val after = observe(p.c)
        assertEquals(before.writes, after.writes); assertEquals(before.bytes, after.bytes); assertEquals(before.state, after.state)
    }

    // ═══ contract r4: battery r1 additions ═══════════════════════════════════════════════════════════════════════════════
    private fun rejectedRecord(r: RecordResult, reason: RecordRefusal) = assertEquals(RecordResult.Rejected(reason), r)
    private fun key(p: Prepared, component: ObligationComponent, branch: LandingBranch = LandingBranch.L) =
        p.required.first { it.key.component == component && it.key.branch == branch }.key

    /** R01: a coordinator built for an owner of another tracking lifetime or with a blank key accepts no event and issues nothing. */
    @Test fun R01_coordinatorOwnerNotTheCommands_eventsRejected_issuanceOwnerOrCommandMismatch() = runReleaseTest {
        val p = prepare()
        val q = key(p, ObligationComponent.REQUEST)
        for (bad in listOf(ResponsibilityOwner(OwnerTrackingLifetimeId.issue(), "owner-1"), ResponsibilityOwner(p.c.ownerTrackingLifetimeId, ""))) {
            val h = HandoffCoordinator(p.c, bad, Closer(), deadline)
            val b = HandoffEventBinding(p.c, bad)
            val job = work()
            try {
                assertEquals(RegistrationResult.Rejected(RegistrationRefusal.OWNER_OR_COMMAND_MISMATCH), h.registerWork(b, HandoffEntryKind.CALLBACK, "w", job))
            } finally { job.cancel() }
            rejectedRecord(h.recordComponentCompleted(b, q, ComponentCompletion(q.subject)), RecordRefusal.OWNER_OR_COMMAND_MISMATCH)
            refused(issue(p, h), HandoffIssueRefusal.OWNER_OR_COMMAND_MISMATCH)
        }
    }

    /** R02: after issuance every event is refused. */
    @Test fun R02_eventAfterIssuance_alreadyIssued() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        feed(h, p); issued(issue(p, h))
        rejectedRecord(h.advanceGeneration(p.binding), RecordRefusal.ALREADY_ISSUED)
    }

    /** R03: an event naming a work id that was never registered. */
    @Test fun R03_eventFromUnregisteredWork_unknownWork() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        rejectedRecord(h.recordTransferStarted(p.binding, key(p, ObligationComponent.JOURNAL), sourceWorkId = "missing"), RecordRefusal.UNKNOWN_WORK)
    }

    /** R04: the same work id twice, or a blank id. */
    @Test fun R04_duplicateOrBlankWorkId_duplicateWork() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val a = work(); val b = work()
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", a))
        assertEquals(RegistrationResult.Rejected(RegistrationRefusal.DUPLICATE_WORK), h.registerWork(p.binding, HandoffEntryKind.QUERY, "w1", b))
        assertEquals(RegistrationResult.Rejected(RegistrationRefusal.DUPLICATE_WORK), coordinator(p).registerWork(p.binding, HandoffEntryKind.QUERY, " ", b))
    }

    /** R05: a transfer start twice, after a confirmation, or after a completion of the same slot. */
    @Test fun R05_transferStartConflicts_duplicateOrConflicting() = runReleaseTest {
        val p = prepare()
        val j = key(p, ObligationComponent.JOURNAL); val q = key(p, ObligationComponent.REQUEST)
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, j))
            rejectedRecord(h.recordTransferStarted(p.binding, j), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, j, transfer(p, j)))
            rejectedRecord(h.recordTransferStarted(p.binding, j), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, q, ComponentCompletion(q.subject)))
            rejectedRecord(h.recordTransferStarted(p.binding, q), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
    }

    /** R06–R08: a confirmed transfer without a confirmation, with another slot's token, or with a destination not the token's. */
    @Test fun R06_confirmedTransferWithoutValidToken_invalidConfirmation() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val jL = key(p, ObligationComponent.JOURNAL, LandingBranch.L); val jN = key(p, ObligationComponent.JOURNAL, LandingBranch.N)
        rejectedRecord(h.recordConfirmedTransfer(p.binding, jL, HandoffDisposition.DurablyOwned(journal, emptyList(), null)), RecordRefusal.INVALID_CONFIRMATION)
        rejectedRecord(h.recordConfirmedTransfer(p.binding, jL, HandoffDisposition.DurablyOwned(journal, emptyList(), p.tokens.getValue(jN))),
            RecordRefusal.INVALID_CONFIRMATION)
        rejectedRecord(h.recordConfirmedTransfer(p.binding, jL, HandoffDisposition.DurablyOwned(
            DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.USER, "u2")), emptyList(), p.tokens.getValue(jL))), RecordRefusal.INVALID_CONFIRMATION)
    }

    /** R09: a completion twice, or on a slot whose transfer started or was confirmed. */
    @Test fun R09_completionConflicts_duplicateOrConflicting() = runReleaseTest {
        val p = prepare()
        val j = key(p, ObligationComponent.JOURNAL); val q = key(p, ObligationComponent.REQUEST)
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, q, ComponentCompletion(q.subject)))
            rejectedRecord(h.recordComponentCompleted(p.binding, q, ComponentCompletion(q.subject)), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, j))
            rejectedRecord(h.recordComponentCompleted(p.binding, j, ComponentCompletion(j.subject)), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
        coordinator(p).let { h ->
            assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, j, transfer(p, j)))
            rejectedRecord(h.recordComponentCompleted(p.binding, j, ComponentCompletion(j.subject)), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        }
    }

    /** R10: a completion naming another subject. */
    @Test fun R10_completionOfAnotherSubject_invalidConfirmation() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val q = key(p, ObligationComponent.REQUEST); val s = key(p, ObligationComponent.SEAL)
        rejectedRecord(h.recordComponentCompleted(p.binding, q, ComponentCompletion(s.subject)), RecordRefusal.INVALID_CONFIRMATION)
    }

    /** R11: result consumed before the completion, or twice. */
    @Test fun R11_resultConsumedWithoutCompletionOrTwice_duplicateOrConflicting() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val q = key(p, ObligationComponent.REQUEST)
        rejectedRecord(h.recordCompletionResultConsumed(p.binding, q), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, q, ComponentCompletion(q.subject)))
        assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(p.binding, q))
        rejectedRecord(h.recordCompletionResultConsumed(p.binding, q), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
    }

    /** R12/R13: receipts of another command, expected twice, consumed without expectation, consumed twice. */
    @Test fun R12_receiptEvents_unexpectedOrDuplicate() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val mine = ReceiptIdentity.Settlement(p.c.id, HandoverSettlementTransition.CURRENT_NULL)
        val other = ReceiptIdentity.Settlement("another-operation", HandoverSettlementTransition.CURRENT_NULL)
        rejectedRecord(h.expectReceipt(p.binding, other), RecordRefusal.UNEXPECTED_RECEIPT)
        rejectedRecord(h.recordReceiptConsumed(p.binding, mine), RecordRefusal.UNEXPECTED_RECEIPT)
        assertEquals(RecordResult.Recorded, h.expectReceipt(p.binding, mine))
        rejectedRecord(h.expectReceipt(p.binding, mine), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        assertEquals(RecordResult.Recorded, h.recordReceiptConsumed(p.binding, mine))
        rejectedRecord(h.recordReceiptConsumed(p.binding, mine), RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
    }

    /** I01: entries are closed exactly once per kind; a later call does not close them again. */
    @Test fun I01_entriesClosedOncePerKind_secondCallDoesNotRecloseAndIsAlreadyIssued() = runReleaseTest {
        val p = prepare()
        val calls = mutableMapOf<HandoffEntryKind, Int>()
        val h = coordinator(p, HandoffEntryCloser { kind -> val n = (calls[kind] ?: 0) + 1; calls[kind] = n; n == 1 })
        feed(h, p)
        issued(issue(p, h))
        refused(issue(p, h), HandoffIssueRefusal.ALREADY_ISSUED)
        assertEquals(HandoffEntryKind.entries.associateWith { 1 }, calls)
    }

    /** I02: a generation change seen right after the join refuses at once, without waiting for pending evidence. */
    @Test fun I02_generationChangedDuringJoin_refusedBeforeEvidenceWait() = runReleaseTest {
        val p = prepare(); val h = HandoffCoordinator(p.c, p.owner, Closer(), deadline, evidenceTimeoutMillis = 60_000)
        val j = onCancel { assertEquals(RecordResult.Recorded, h.advanceGeneration(p.binding)) }
        assertEquals(RegistrationResult.Registered, h.registerWork(p.binding, HandoffEntryKind.CALLBACK, "w1", j))
        val jn = key(p, ObligationComponent.JOURNAL)
        assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, jn))
        feed(h, p, except = setOf(jn))
        refused(withTimeout(10_000) { issue(p, h) }, HandoffIssueRefusal.GENERATION_CHANGED)
    }

    /** I03: a generation change while waiting for a started transfer's confirmation refuses even when the confirmation arrives. */
    @Test fun I03_generationChangedDuringEvidenceWait_generationChanged() = runReleaseTest {
        val p = prepare(); val h = HandoffCoordinator(p.c, p.owner, Closer(), deadline, evidenceTimeoutMillis = 60_000)
        val jn = key(p, ObligationComponent.JOURNAL)
        assertEquals(RecordResult.Recorded, h.recordTransferStarted(p.binding, jn))
        feed(h, p, except = setOf(jn))
        val call = async(start = CoroutineStart.UNDISPATCHED) { h.closeJoinAndIssueHandoff(p.input) }
        assertTrue("fixture: waiting for the confirmation", call.isActive)
        assertEquals(RecordResult.Recorded, h.advanceGeneration(p.binding))
        assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, jn, transfer(p, jn)))
        refused(withTimeout(10_000) { call.await() }, HandoffIssueRefusal.GENERATION_CHANGED)
    }

    /** I04: an event for a key that is not a required slot (same subject, another role). */
    @Test fun I04_eventForANonRequiredKey_incompleteOrInvalidSlots() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val q = key(p, ObligationComponent.REQUEST)
        val foreign = q.copy(role = ObligationRole.Rotation)
        assertTrue("fixture: not a required key", p.required.none { it.key == foreign })
        assertEquals(RecordResult.Recorded, h.recordComponentCompleted(p.binding, foreign, ComponentCompletion(foreign.subject)))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    /** I05: a receipt expected for a slot that is transferred, not completed. */
    @Test fun I05_receiptForATransferredSlot_incompleteOrInvalidSlots() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val receipt = ReceiptIdentity.Settlement(p.c.id, HandoverSettlementTransition.CURRENT_NULL)
        assertEquals(RecordResult.Recorded, h.expectReceipt(p.binding, receipt, key(p, ObligationComponent.JOURNAL)))
        assertEquals(RecordResult.Recorded, h.recordReceiptConsumed(p.binding, receipt))
        feed(h, p)
        refused(issue(p, h), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    /**
     * I06: a token of the same key from a second, identically specified CURRENT_NULL command settled in another store. Recording
     * sees only the key and the locator; issuance compares the whole slot (its fixed input is the other command's).
     */
    @Test fun I06_sameKeyTokenOfAnotherCommand_recordedButTransferNotConfirmed() = runReleaseTest {
        val p = prepare(); val h = coordinator(p)
        val o2 = open()
        val s2 = N0.spec(companions = listOf(node(N0.companionUser)))
        controlTestTimeout("seed second") { o2.data.updateData { N0.raw(s2) } }
        val tracking2 = ControlCommandTracking.forOwner(o2.owner)
        val c2 = tracking2.registerPrepared(N0.command(s2, tracking2.lifetimeId))
        val confirmed2 = controlTestTimeout("second execute") { o2.control.execute(c2, N0.context) } as ControlStoreResult.Confirmed
        val a2 = deriveRequiredObligations(RequirementInput.Settlement(c2, c2.body as ControlCommandBody.Handover)) as RequirementDerivation.Available
        val jn = key(p, ObligationComponent.JOURNAL)
        val slot2 = a2.orderedSlots.single { it.key == jn }
        assertTrue("fixture: same key, different slot", slot2 != p.required.single { it.key == jn })
        val r = PriorStorageConfirmation.confirmRetainedSource(slot2, journal, confirmed2)
        assertTrue("fixture: second token issued", r is RetainedSourceConfirmationResult.Issued)
        val foreign = HandoffDisposition.DurablyOwned(journal, emptyList(), (r as RetainedSourceConfirmationResult.Issued).value)
        assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(p.binding, jn, foreign))
        feed(h, p, except = setOf(jn))
        refused(issue(p, h), HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
    }

    /** I07: a floor-only guard Add (M08): both slots are DURABLY_OWNED_ONLY, so completions cannot be issued. Pure, no store. */
    @Test fun I07_completionsForDurablyOwnedOnlySlots_incompleteOrInvalidSlots() = runReleaseTest {
        val add = ControlMutation.Add.prepare(ControlKind.DEMAND, java.util.UUID(0L, 8L)) { id ->
            literal("""{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}""")
            set("id", ControlScalar.Text(id))
        }
        val ref = CommandRef("cmd-m08", listOf(add), OwnerTrackingLifetimeId.issue())
        val input = RequirementInput.Mutations(ref, ref.body as ControlCommandBody.Mutations, MutationAdoption.Current(listOf(
            ControlCommandTarget(add.proposedId, (add.built as ControlWriteResult.Written).node, false))))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val required = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertTrue("fixture: floor slots are DURABLY_OWNED_ONLY", required.isNotEmpty() &&
            required.all { (it.requirement as SlotRequirement.Required).allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY })
        val owner = ResponsibilityOwner(ref.ownerTrackingLifetimeId, "owner-1"); val b = HandoffEventBinding(ref, owner)
        val h = HandoffCoordinator(ref, owner, Closer(), deadline)
        for (slot in required) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        refused(h.closeJoinAndIssueHandoff(input), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    // ── REBIND_REQUESTS: a real lifecycle command, its LifecycleOutput tokens and a named link ─────────────────────────────
    private val F = DemandAuthFixtures
    private class LRun(val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val binding = HandoffEventBinding(c, owner)
        val input = RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)
    }
    private val oldRebind get() = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
    private suspend fun rebindRun(): LRun {
        val s = open()
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(oldRebind, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareRebindRequests(listOf(oldRebind), F.binding, LifecycleOrderSource(F.life, 21))
        val r = controlTestTimeout("rebind execute") { s.control.execute(c, F.context(F.runtime())) }
        assertTrue("fixture: rebind Confirmed, got $r", r is ControlStoreResult.Confirmed)
        return LRun(c, r as ControlStoreResult.Confirmed)
    }
    private val requestTarget = LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)
    private fun lifecycleToken(run: LRun): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmLifecycleOutput(run.c, (run.c.body as ControlCommandBody.Lifecycle).input, run.confirmed, requestTarget)
        assertTrue("fixture: lifecycle token issued, got $r", r is LifecycleOutputConfirmationResult.Issued)
        return (r as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun outputLocator(t: PriorStorageConfirmation) = (t.binding as ConfirmationBinding.LifecycleOutput).output.locator
    private fun rebindLink(run: LRun, t: PriorStorageConfirmation): NamedTransferLink {
        val n = (run.required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N }.requirement
            as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
        val r = NamedTransferLink.linkNamedTransfer(
            TypedSourceTuple.Request(run.owner, oldRebind, DemandV1("r", "A", 2, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("old"), Long.MAX_VALUE)),
                n.minimumIntent, n.minimumOrder),
            TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, "r"),
                F.request(id = "r", owner = "A", binding = 3, origin = F.life, intent = RefreshIntent.FORCE_PREMIUM, order = 22),
                DemandV1("r", "A", 3, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("life"), 22))), t)
        assertTrue("fixture: named link issued, got $r", r is NamedTransferResult.Issued)
        return (r as NamedTransferResult.Issued).value
    }

    /** I08: a LifecycleOutput-backed transfer on a COMPLETED_AND_CONSUMED_ONLY slot is not issuable. */
    @Test fun I08_transferOnCompletedOnlySlot_incompleteOrInvalidSlots() = runReleaseTest {
        val run = rebindRun()
        val completedOnly = run.required.filter { (it.requirement as SlotRequirement.Required).allowed == AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY }
        assertTrue("fixture: a completed-only slot exists", completedOnly.isNotEmpty())
        assertTrue("fixture: no durably-owned-only slot to confuse the verdict", run.required.none {
            (it.requirement as SlotRequirement.Required).allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY })
        val target = completedOnly.first().key
        val t = lifecycleToken(run)
        val h = HandoffCoordinator(run.c, run.owner, Closer(), deadline)
        assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(run.binding, target, HandoffDisposition.DurablyOwned(outputLocator(t), emptyList(), t)))
        for (slot in run.required) if (slot.key != target) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(run.binding, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(run.binding, slot.key))
        }
        refused(h.closeJoinAndIssueHandoff(run.input), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    /** I09: a LifecycleOutput token issued for another command. */
    @Test fun I09_lifecycleTokenOfAnotherCommand_invalidConfirmation() = runReleaseTest {
        val run = rebindRun(); val other = rebindRun()
        val t = lifecycleToken(other)
        val h = HandoffCoordinator(run.c, run.owner, Closer(), deadline)
        val q = run.required.first { it.key.component == ObligationComponent.REQUEST }.key
        rejectedRecord(h.recordConfirmedTransfer(run.binding, q, HandoffDisposition.DurablyOwned(outputLocator(t), emptyList(), t)), RecordRefusal.INVALID_CONFIRMATION)
    }

    /** I10: a named chain whose priorWrite is not its terminal link's token, or whose destination is not its terminal output. */
    @Test fun I10_namedChainNotEndingInItsOwnTokenOrDestination_invalidConfirmation() = runReleaseTest {
        val run = rebindRun()
        val link = rebindLink(run, lifecycleToken(run))
        val q = run.required.first { it.key.component == ObligationComponent.REQUEST }.key
        val h = HandoffCoordinator(run.c, run.owner, Closer(), deadline)
        rejectedRecord(h.recordConfirmedTransfer(run.binding, q,
            HandoffDisposition.DurablyOwned(link.destination.locator, listOf(link), lifecycleToken(run))), RecordRefusal.INVALID_CONFIRMATION)
        rejectedRecord(h.recordConfirmedTransfer(run.binding, q,
            HandoffDisposition.DurablyOwned(DestinationLocator.Payload(ControlKind.DEMAND, "x"), listOf(link), link.confirmation)), RecordRefusal.INVALID_CONFIRMATION)
    }
}
