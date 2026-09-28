package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bC1d-1(a) contract (6-4bC1d consensus r1, D3 and D5): an old Mutations HOLD command left OnceConfirm·U hands
 * over its FLOOR L/N to the guard written by a real RECOVER_HOLD successor, through HandoffCoordinator's new entry
 * recordConfirmedMutationFloorTransfer.
 *  - The new B1 entry accepts only a non-empty HoldFloor → GuardFloor… chain for an old Mutations HOLD FLOOR slot, judged by the
 *    named-chain structure and FLOOR owner checks that G05 also applies (the first link's originalHold equals the slot's fixed
 *    floor row, the chain is continuous, the FLOOR ownerKey rule) and requires priorWrite to be the last link's confirmation and
 *    the end locator to be the destination. It is
 *    re-checked at issuance against an independently derived slot. The existing recordConfirmedTransfer keeps refusing another
 *    command's LifecycleOutput and an empty chain: a successor's success is never attributed to the old slot by itself.
 *  - The same chain may serve both L and N (A3d2, canonical §4.2). G05 is unchanged.
 *  - When the output guard equals the existing guard, RECOVER_HOLD lists the guard in requiredUnchanged, not in its targets; the new
 *    issuer confirmRecoverHoldUnchangedGuardOutput confirms that guard so the link can be issued.
 *  - SOURCE L/N are completed and consumed only after the real RECOVER_HOLD is Confirmed and the HOLD is gone. A floor is never
 *    declared completed.
 * Floors: the old HOLD floor (anchor 10000, wait 30000) has 29000 left at mergeNow 11000; the existing guard's (wait 10000) 9000;
 * the output floor is (boot, 11000, 29000, new-life).
 * The implementation thread reads but does not edit this file.
 */
class MutationsHoldFloorSuccessorContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    @After fun close() = runReleaseTest { controlTestTimeout("C1d-1a cleanup", 30_000) { fx.storage.close() } }
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }
    private fun holdJson(anchor: Long = 10_000) = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30_000, "boot", anchor, "life"))
        .toPayloadEntry().fields.toString()
    /** The existing guard whose floor already equals the output floor: RECOVER_HOLD leaves it unchanged. */
    private val exactGuard get() = FloorGuardFixtures.guard(FloorGuardFixtures.floor(29_000, "boot", 11_000, "new-life"), auth = true)

    // ── old command and successor ───────────────────────────────────────────────────────────────────────────────────
    private class Recover(val c: CommandRef, val confirmed: ControlStoreResult.Confirmed, val row: ControlNode, val oldGuard: ControlNode?) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val guardFixed get() = (fixed.targets + fixed.requiredUnchanged).single { it.role == LifecycleRole.GUARD }
        val unchanged get() = guardFixed in fixed.requiredUnchanged
        val guardAfter get() = checkNotNull(guardFixed.after)
        val destination get() = DestinationLocator.Guard(checkNotNull(guard(guardAfter)).id, GuardPart.FLOOR)
    }
    private suspend fun seed(guard: ControlNode?, holds: String = "[]") = controlTestTimeout("seed") {
        fx.storage.data.updateData { H.before(H.input(g = guard), siblings = false).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = holds }.toPreferences() }
    }
    /** Executes the old command; its business Confirm lands and returns failed. own = 0 arms a read-back for a no-op. */
    private suspend fun oldU(vararg actions: ControlMutation, own: Int): CommandRef {
        val c = fx.store.prepare(*actions)
        if (own == 0) fx.armReadBack()
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("old execute") { fx.store.execute(c) }
        assertTrue("fixture: old Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: old in U", c in fx.tracker.snapshot())
        assertEquals("fixture: old own Applied", own, ownIn(fx.disk(), c))
        return c
    }
    private fun holdAdd(anchor: Long = 10_000) = fx.store.addition(ControlKind.HOLD) { id -> literal(holdJson(anchor)); set("id", ControlScalar.Text(id)) }
    private fun holdId(a: ControlMutation) = (a as ControlMutation.Add).proposedId
    private suspend fun storedRow(kind: ControlKind, id: String) = H.row(fx.disk(), kind, id)
    /** A real RECOVER_HOLD of the stored HOLD [holdId], merging into the stored guard [guardId] (or none). */
    private suspend fun recover(holdId: String, guardId: String?, krx: String = "k", order: Long = 21): Recover {
        val row = storedRow(ControlKind.HOLD, holdId)
        val oldGuard = guardId?.let { storedRow(ControlKind.DEMAND, it) }
        val input = RecoverHoldInput(row, oldGuard, FenceV1("A", "u", krx), H.binding, H.restart(row, H.executor), H.now)
        val c = fx.store.prepareRecoverHold(input, LifecycleOrderSource(H.life, order))
        val r = controlTestTimeout("recover") { fx.store.execute(c, H.context(input)) }
        assertTrue("fixture: RECOVER_HOLD Confirmed, got $r", r is ControlStoreResult.Confirmed)
        assertTrue("fixture: HOLD $holdId gone", ControlRecordReader().read(fx.disk()).let {
            (it as ControlRecordRead.Supported).locations(holdId).isEmpty() })
        return Recover(c, r as ControlStoreResult.Confirmed, row, oldGuard)
    }

    // ── tokens and links ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun token(r: Recover): PriorStorageConfirmation {
        val t = if (r.unchanged) PriorStorageConfirmation.confirmRecoverHoldUnchangedGuardOutput(r.c, r.fixed, r.confirmed, r.guardFixed.target)
        else PriorStorageConfirmation.confirmLifecycleOutput(r.c, r.fixed, r.confirmed, r.guardFixed.target)
        assertTrue("fixture: guard output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        return (t as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun owner(c: CommandRef) = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
    private fun parsedHold(row: ControlNode) = (ControlObligations.read(ControlKind.HOLD, row) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun output(r: Recover) = TypedDestinationTuple.GuardFloor(r.destination, r.guardAfter, checkNotNull(guard(r.guardAfter)))
    private fun holdSource(r: Recover, oldGuard: ControlNode? = r.oldGuard, mergeNow: BootReading = H.now, origin: LifetimeId = H.life) =
        TypedSourceTuple.HoldFloor(owner(r.c), FloorSource(parsedHold(r.row).id, r.row, parsedHold(r.row), checkNotNull(parsedHold(r.row).floor),
            oldGuard), oldGuard, mergeNow, origin)
    private fun linked(result: NamedTransferResult): NamedTransferLink {
        assertTrue("fixture: link issued, got $result", result is NamedTransferResult.Issued)
        return (result as NamedTransferResult.Issued).value
    }
    private fun holdLink(r: Recover, t: PriorStorageConfirmation = token(r)) =
        linked(NamedTransferLink.linkNamedTransfer(holdSource(r), output(r), t))
    private fun guardLink(r: Recover, preimage: ControlNode, t: PriorStorageConfirmation) = linked(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.GuardFloor(owner(r.c), preimage, checkNotNull(guard(preimage))), output(r), t))

    // ── old slots and B1 ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Slots(val input: RequirementInput.Mutations, val required: List<RequiredSlot>) {
        fun of(index: Int, component: ObligationComponent, branch: LandingBranch) = required.single {
            it.key.role == ObligationRole.MutationAction(index) && it.key.component == component && it.key.branch == branch }
    }
    private fun slots(c: CommandRef): Slots {
        val input = RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        return Slots(input, a.orderedSlots.filter { it.requirement is SlotRequirement.Required })
    }
    private inner class B1(val c: CommandRef) {
        val owner = owner(c)
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, owner)
        val s = slots(c)
        fun completeSources() = s.required.filter { it.key.component == ObligationComponent.SOURCE }.forEach { complete(it) }
        fun complete(slot: RequiredSlot) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        fun floor(slot: RequiredSlot, d: DestinationLocator, links: List<NamedTransferLink>, prior: PriorStorageConfirmation) =
            h.recordConfirmedMutationFloorTransfer(b, slot, HandoffDisposition.DurablyOwned(d, links, prior))
        suspend fun issue() = h.closeJoinAndIssueHandoff(s.input)
    }
    private fun recorded(r: RecordResult) = assertEquals(RecordResult.Recorded, r)
    private fun invalid(r: RecordResult) = assertEquals(RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION), r)
    private fun issued(r: HandoffIssueResult): HandoffIssueResult.Issued {
        assertTrue("expected B1 Issued, got $r", r is HandoffIssueResult.Issued)
        return r as HandoffIssueResult.Issued
    }
    private fun refusedIssue(r: HandoffIssueResult, reason: HandoffIssueRefusal) =
        assertTrue("expected Refused($reason), got $r", r is HandoffIssueResult.Refused && r.reason == reason)

    /** Success: only the old command's own Applied row removed, terminal, body released, out of U, P and commands. */
    private suspend fun transferred(c: CommandRef, i: HandoffIssueResult.Issued, ownBefore: Int) {
        val before = fx.disk()
        assertEquals("own before", ownBefore, ownIn(before, c))
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, i.closure, i.handoff) }
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only the old command's Applied removed",
            JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }), evidence(after))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
    }

    // ── single HOLD runs ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Single(val c: CommandRef, val r: Recover, val own: Int)
    private suspend fun singleAdd(existing: ControlNode?): Single {
        seed(existing)
        val a = holdAdd(); val c = oldU(a, own = 1)
        return Single(c, recover(holdId(a), existing?.let { checkNotNull(guard(it)).id }), 1)
    }
    private suspend fun singleNoopEdit(): Single {
        val hold = H.hold()
        seed(H.guard(), holds = "[${hold.toPayloadEntry().fields}]")
        val c = oldU(fx.store.edit(ControlKind.HOLD, hold) {}, own = 0)
        return Single(c, recover("h", "g"), 0)
    }
    /** SOURCE L/N completed; FLOOR L/N each recorded through the new entry with the same single link. */
    private fun declaredSingle(run: Single): B1 {
        val b1 = B1(run.c); b1.completeSources()
        val t = token(run.r); val link = holdLink(run.r, t)
        for (branch in listOf(L, N)) recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), run.r.destination, listOf(link), t))
        return b1
    }

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P1_holdAdd_existingGuardReplaced_floorLNLinked_transferred() = runReleaseTest {
        val run = singleAdd(H.guard())
        assertEquals("fixture: guard REPLACE", LifecycleEffect.REPLACE, run.r.guardFixed.target.effect)
        assertEquals("fixture: output floor", FloorV1("boot", 11_000, 29_000, LifetimeId("new-life")), guard(run.r.guardAfter)?.floor)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    @Test fun P2_holdAdd_noGuard_guardCreated_floorLNLinked_transferred() = runReleaseTest {
        val run = singleAdd(null)
        assertEquals("fixture: guard CREATE", LifecycleEffect.CREATE, run.r.guardFixed.target.effect)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    @Test fun P3_holdNoopEdit_floorLNLinked_transferred() = runReleaseTest {
        val run = singleNoopEdit()
        transferred(run.c, issued(declaredSingle(run).issue()), 0)
    }

    /** P4: the existing guard already carries the exact output floor: requiredUnchanged, confirmed by the new issuer. */
    @Test fun P4_existingGuardAlreadyExact_requiredUnchanged_newTokenLinked_transferred() = runReleaseTest {
        val run = singleAdd(exactGuard)
        assertTrue("fixture: guard in requiredUnchanged", run.r.unchanged)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    /** Two HOLD Adds in one old command; A recovered first (guard CREATE), B then joins A's guard (A3d2 chain). */
    private inner class Pair2(val c: CommandRef, val a: Recover, val b: Recover)
    private suspend fun two(): Pair2 {
        seed(null)
        val addA = holdAdd(10_000); val addB = holdAdd(10_300)
        val c = oldU(addA, addB, own = 1)
        val a = recover(holdId(addA), null)
        assertEquals("fixture: A's guard CREATE", LifecycleEffect.CREATE, a.guardFixed.target.effect)
        val b = recover(holdId(addB), a.destination.id, checkNotNull(fx.disk()[DataStoreAccessEpochStore.KRX_EPOCH]), 22)
        assertEquals("fixture: B joins A's guard", a.destination, b.destination)
        assertEquals("fixture: exact max", FloorV1("boot", 11_000, 29_300, LifetimeId("new-life")), guard(b.guardAfter)?.floor)
        return Pair2(c, a, b)
    }
    @Test fun P5_twoHolds_chainHoldFloorAThenGuardFloorB_andBsOwnLink_transferred() = runReleaseTest {
        val p = two(); val b1 = B1(p.c); b1.completeSources()
        val tB = token(p.b)
        val chainA = listOf(holdLink(p.a), guardLink(p.b, p.a.guardAfter, tB)); val linkB = holdLink(p.b, tB)
        for (branch in listOf(L, N)) {
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), p.b.destination, chainA, tB))
            recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), p.b.destination, listOf(linkB), tB))
        }
        transferred(p.c, issued(b1.issue()), 1)
    }

    // ═══ negative: the new B1 entry ════════════════════════════════════════════════════════════════════════════════════
    @Test fun N1_linkOfAnotherHoldActionOnThisSlot_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tB = token(p.b); val linkB = holdLink(p.b, tB)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), p.b.destination, listOf(linkB), tB))
    }

    @Test fun N2_brokenChain_holdLinkRepeated_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tA = token(p.a); val linkA = holdLink(p.a, tA)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), p.a.destination, listOf(linkA, linkA), tA))
    }

    @Test fun N3_priorWriteIsAnotherSuccessorsToken_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tA = token(p.a); val linkB = holdLink(p.b)
        invalid(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, L), p.b.destination, listOf(linkB), tA))
    }

    @Test fun N7_floorNNotRecorded_b1IncompleteSlots() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c); b1.completeSources()
        val t = token(run.r)
        recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), run.r.destination, listOf(holdLink(run.r, t)), t))
        refusedIssue(b1.issue(), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    @Test fun N8_floorDeclaredCompleted_b1IncompleteSlots() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c); b1.completeSources()
        token(run.r) // the successor's guard is confirmable; the slots are declared completed instead
        for (branch in listOf(L, N)) b1.complete(b1.s.of(0, ObligationComponent.FLOOR, branch))
        refusedIssue(b1.issue(), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    /** N9: the existing entry still refuses the successor's LifecycleOutput without a slot chain. */
    @Test fun N9_successorOutputWithoutAChain_existingEntryInvalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r)
        holdLink(run.r, t) // reach the new-entry era: a valid chain exists but is not submitted
        invalid(b1.h.recordConfirmedTransfer(b1.b, b1.s.of(0, ObligationComponent.FLOOR, L).key,
            HandoffDisposition.DurablyOwned(run.r.destination, emptyList(), t)))
    }

    /** N13 (contract r2): the new entry also refuses an empty chain with the successor's token. */
    @Test fun N13_newEntryEmptyChain_invalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), run.r.destination, emptyList(), t))
    }

    /**
     * N14 (contract r2): a slot forged to carry B's HOLD row and FLOOR lower bound as action 0's accepts B's link at recording; issuance
     * re-derives action 0's real slot, whose fixed row is A's, and refuses with TRANSFER_NOT_CONFIRMED.
     */
    @Test fun N14_recordedOnAForgedSlot_issuanceRederivesAndRefuses() = runReleaseTest {
        val p = two(); val b1 = B1(p.c); b1.completeSources()
        val tB = token(p.b); val linkB = holdLink(p.b, tB)
        val chainA = listOf(holdLink(p.a), guardLink(p.b, p.a.guardAfter, tB))
        val real = b1.s.of(0, ObligationComponent.FLOOR, L); val req = real.requirement as SlotRequirement.Required
        val afterA = FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.AFTER)
        val bReq = b1.s.of(1, ObligationComponent.FLOOR, L).requirement as SlotRequirement.Required
        val bAfter = bReq.fixedSources
            .single { it.location == FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 1, FixedInputFacet.AFTER) }.fact
        val forged = real.copy(requirement = req.copy(lowerBound = bReq.lowerBound, fixedSources = req.fixedSources.map {
            if (it.location == afterA) FixedSourceEvidence(afterA, bAfter) else it }))
        recorded(b1.floor(forged, p.b.destination, listOf(linkB), tB))
        recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, N), p.b.destination, chainA, tB))
        for (branch in listOf(L, N)) recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), p.b.destination, listOf(linkB), tB))
        refusedIssue(b1.issue(), HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
    }

    // ── contract r4: battery r1 rows (6-4bC1d1a_battery_r1_codex.md) ──────────────────────────────────────────────────
    /** N15 (H01): the same FLOOR slot recorded twice through the new entry: the second is a duplicate. */
    @Test fun N15_sameFloorSlotRecordedTwice_duplicate() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r); val link = holdLink(run.r, t); val slot = b1.s.of(0, ObligationComponent.FLOOR, L)
        recorded(b1.floor(slot, run.r.destination, listOf(link), t))
        assertEquals(RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT), b1.floor(slot, run.r.destination, listOf(link), t))
    }

    /** N16 (H02): a FLOOR slot already recorded completed conflicts with a transfer through the new entry. */
    @Test fun N16_floorSlotCompletedThenTransferred_conflict() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r); val slot = b1.s.of(0, ObligationComponent.FLOOR, L)
        assertEquals(RecordResult.Recorded, b1.h.recordComponentCompleted(b1.b, slot.key, ComponentCompletion(slot.key.subject)))
        assertEquals(RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT),
            b1.floor(slot, run.r.destination, listOf(holdLink(run.r, t)), t))
    }

    /**
     * N17 (H03): the new entry is for an old Mutations command. A coordinator of A's own RECOVER_HOLD receiving the old command's
     * action 1 FLOOR slot with B's link and token (B is not A, the fixed row is B's, the ownerKey matches) refuses it.
     */
    @Test fun N17_nonMutationsCoordinator_invalidConfirmation() = runReleaseTest {
        val p = two(); val s = slots(p.c)
        val owner = owner(p.a.c)
        val h = HandoffCoordinator(p.a.c, owner, HandoffEntryCloser { true }, 300)
        val tB = token(p.b)
        invalid(h.recordConfirmedMutationFloorTransfer(HandoffEventBinding(p.a.c, owner), s.of(1, ObligationComponent.FLOOR, L),
            HandoffDisposition.DurablyOwned(p.b.destination, listOf(holdLink(p.b, tB)), tB)))
    }

    /**
     * N18 (H07): a Lifecycle command's own LifecycleOutput still confirms its slot at issuance. A real REBIND_REQUESTS hands over
     * its REQUEST L through its own named link and every other required slot completed and consumed: B1 issues.
     */
    @Test fun N18_lifecycleCommandsOwnOutput_b1Issues() = runReleaseTest {
        val q = DemandAuthFixtures
        val oldRebind = q.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind") { fx.storage.data.updateData { q.raw(oldRebind, q.request(id = "dormant", owner = "B")) } }
        val c = fx.store.prepareRebindRequests(listOf(oldRebind), q.binding, LifecycleOrderSource(q.life, 21))
        val r = controlTestTimeout("rebind execute") { fx.store.execute(c, q.context(q.runtime())) }
        assertTrue("fixture: rebind Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val input = RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)
        val required = (deriveRequiredObligations(input) as RequirementDerivation.Available).orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val t = PriorStorageConfirmation.confirmLifecycleOutput(c, (c.body as ControlCommandBody.Lifecycle).input, r as ControlStoreResult.Confirmed,
            LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE))
        assertTrue("fixture: own output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        val token = (t as LifecycleOutputConfirmationResult.Issued).value
        val requestL = required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == L }
        val n = (required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == N }.requirement
            as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
        val owner = owner(c)
        val link = linked(NamedTransferLink.linkNamedTransfer(
            TypedSourceTuple.Request(owner, oldRebind, DemandV1("r", "A", 2, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM,
                EventOrderV1(LifetimeId("old"), Long.MAX_VALUE)), n.minimumIntent, n.minimumOrder),
            TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, "r"),
                q.request(id = "r", owner = "A", binding = 3, origin = q.life, intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, order = 22),
                DemandV1("r", "A", 3, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("life"), 22))), token))
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val b = HandoffEventBinding(c, owner)
        for (slot in required) if (slot === requestL) {
            recorded(h.recordConfirmedTransfer(b, slot.key, HandoffDisposition.DurablyOwned(
                (token.binding as ConfirmationBinding.LifecycleOutput).output.locator, listOf(link), token)))
        } else {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        issued(h.closeJoinAndIssueHandoff(input))
        Unit
    }

    // ═══ negative: the link issuer and re-anchor recomputation (fixture-level preconditions of the chain) ═══════════════
    @Test fun N4_existingGuardButOldGuardOmitted_linkSourceMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val t = token(run.r)
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH)),
            NamedTransferLink.linkNamedTransfer(holdSource(run.r, oldGuard = null), output(run.r), t))
    }

    @Test fun N5_outputOneMillisecondShort_reanchorExactMax() = runReleaseTest {
        val run = singleAdd(H.guard())
        val short = FloorGuardFixtures.field(run.r.guardAfter, "floor", FloorGuardFixtures.floor(28_999, "boot", 11_000, "new-life"))
        val src = holdSource(run.r)
        assertEquals(ReanchorValidation.Invalid(ReanchorField.EXACT_MAX),
            validHoldReanchor(src.source, run.r.oldGuard, H.now, H.life, run.r.destination.id, short))
    }

    @Test fun N6_mergeNowOrOriginTampered_linkSourceMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val t = token(run.r)
        val mismatch = NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH))
        assertEquals(mismatch, NamedTransferLink.linkNamedTransfer(holdSource(run.r, mergeNow = BootReading("boot", 11_001)), output(run.r), t))
        assertEquals(mismatch, NamedTransferLink.linkNamedTransfer(holdSource(run.r, origin = LifetimeId("other-life")), output(run.r), t))
    }

    // ═══ negative: latest record at the handoff (G05, unchanged) ═══════════════════════════════════════════════════════
    private fun floorFailures(i: HandoffIssueResult.Issued, s: Slots, id: G05Id, actual: G05Location?) = listOf(L, N).map { branch ->
        val slot = s.of(0, ObligationComponent.FLOOR, branch)
        G05Failure(id, slot.key, G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location),
            G05Location.Submitted(i.handoff.slots.indexOfFirst { it.key == slot.key }), actual)
    }
    private suspend fun g05Refused(c: CommandRef, i: HandoffIssueResult.Issued, expected: List<G05Failure>) {
        val before = fx.disk(); val writes = fx.storage.storage.writes
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, i.closure, i.handoff) }
        assertTrue("expected Rejected(G05($expected)), got $r",
            r is ControlCompletionResult.Rejected && r.reason == CompletionRejectionReason.G05(expected))
        assertEquals("no write", writes, fx.storage.storage.writes)
        assertEquals("record unchanged", before, fx.disk())
        assertEquals("old own kept", 1, ownIn(fx.disk(), c))
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    private suspend fun guardIndex(id: String): Int {
        val read = ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported
        val (kind, entry) = read.locations(id).single()
        check(kind == ControlKind.DEMAND)
        return read.arrays.getValue(kind).entries.indexOfFirst { it === entry }
    }

    @Test fun N10_latestGuardRemovedAfterIssuance_g05DestinationAndConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = declaredSingle(run); val i = issued(b1.issue())
        val gid = run.r.destination.id
        fx.edit { p -> val key = ControlRecordKeys.payload(ControlKind.DEMAND)
            p[key] = JsonArray((Json.parseToJsonElement(checkNotNull(p[key])) as JsonArray).filter {
                (it as JsonObject)["id"] != JsonPrimitive(gid) }).toString() }
        g05Refused(run.c, i, floorFailures(i, b1.s, G05Id.DESTINATION, null) + floorFailures(i, b1.s, G05Id.CONFIRMATION, null))
    }

    @Test fun N11_latestGuardFloorShortenedAfterIssuance_g05ConfirmationAndLowerBound() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = declaredSingle(run); val i = issued(b1.issue())
        val gid = run.r.destination.id
        fx.edit { p -> val key = ControlRecordKeys.payload(ControlKind.DEMAND)
            p[key] = checkNotNull(p[key]).replace("\"waitMillis\":29000", "\"waitMillis\":20000").also { check(it != p[key]) } }
        val at = G05Location.ActualPayload(ControlKind.DEMAND, guardIndex(gid))
        g05Refused(run.c, i, floorFailures(i, b1.s, G05Id.CONFIRMATION, at) + floorFailures(i, b1.s, G05Id.LOWER_BOUND, at))
    }

    /** N12: a HOLD field change is not a preparable Edit (regression); the valid no-op Edit then takes the P3 path. */
    @Test fun N12_holdChangeEditInvalid_thenNoopEditTransferred() = runReleaseTest {
        val changed = ControlMutation.Edit.prepare(ControlKind.HOLD, H.hold()) { set("binding", ControlScalar.Integer(18)) }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), changed.changed)
        val run = singleNoopEdit()
        transferred(run.c, issued(declaredSingle(run).issue()), 0)
    }
}
