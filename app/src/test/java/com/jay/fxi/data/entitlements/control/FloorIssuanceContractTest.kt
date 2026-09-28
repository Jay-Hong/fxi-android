package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bA3d1 contract: issuance for cross-command floor transfer (6-4bA3 consensus r6 N12 (2), N13 A3d1).
 *  - A floor slot's retained original row is the fixed row that produced its floor: a Mutations HOLD Add's AFTER, a HOLD
 *    Edit's BEFORE, a Mutations guard Add/Edit's AFTER, a lifecycle guard's AFTER, RECOVER_HOLD's HOLD SOURCE. Other slot
 *    kinds keep the A3a lookup. (END's T05/T05b in the A3a contract are amended accordingly.)
 *  - confirmLifecycleOutput issues RECOVER_HOLD's guard CREATE (no before) as a GuardFloor output, besides REPLACE.
 *  - A HoldFloor link can be issued on that CREATE token (no old guard); a GuardFloor source cannot start from a created
 *    guard (it has no preimage).
 * G05 is unchanged here: accepting another command's floor link is A3d2. Expected values are independent literals or the
 * store's own fixed rows; the implementation thread reads but does not edit this file.
 */
class FloorIssuanceContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3d1 cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N

    // ── Mutations runs ─────────────────────────────────────────────────────────────────────────────────────────────────
    private class MRun(val store: ControlStoreTestStorage, val c: CommandRef, val action: ControlMutation, val confirmed: ControlStoreResult.Confirmed) {
        private val after = when (action) {
            is ControlMutation.Add -> (action.built as ControlWriteResult.Written).node
            is ControlMutation.Edit -> (action.changed as ControlWriteResult.Written).node
        }
        val id = (ControlObligations.read(action.kind, after) as ControlEntryRead.Interpreted).value.id
        val a1 = deriveRequiredObligations(RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Previous(checkNotNull(store.control.checkpoint(c)) { "fixture: a retained checkpoint" })))
            .let { assertTrue("fixture: A1 Available, got $it", it is RequirementDerivation.Available); it as RequirementDerivation.Available }
        fun floor(branch: LandingBranch) = a1.orderedSlots.single { it.key.component == ObligationComponent.FLOOR && it.key.branch == branch }
        val afterRow get() = after
    }
    private suspend fun mutate(s: ControlStoreTestStorage, action: ControlMutation): MRun {
        val c = s.control.prepare(action)
        val r = controlTestTimeout("A3d1 execute") { s.control.execute(c) }
        assertTrue("fixture: the store must return Confirmed, got $r", r is ControlStoreResult.Confirmed)
        return MRun(s, c, action, r as ControlStoreResult.Confirmed)
    }
    private suspend fun holdAdd(): MRun {
        val s = open(); s.seed()
        return mutate(s, s.control.addition(ControlKind.HOLD) { id -> literal(ControlObligationFixtures.hold); set("id", ControlScalar.Text(id)) })
    }
    private val oldGuard get() = ControlObligationFixtures.node(ControlObligationFixtures.guard)
    private suspend fun guardRecapture(): MRun {
        val s = open(); s.seed(demand = "[${oldGuard.toPayloadEntry().fields}]")
        return mutate(s, s.control.recordFloor(oldGuard, BootReading("boot", 20_000), 50_000, LifetimeId("life2")))
    }
    private suspend fun lockedRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> {
        val r = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        assertEquals(RecordTransactionEvidence.LockedFileRead, r.evidence)
        return r
    }
    private fun issued(r: RetainedSourceConfirmationResult): ConfirmationBinding.RetainedSource {
        assertTrue("expected Issued, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value.binding as ConfirmationBinding.RetainedSource
    }
    private fun rejected(r: RetainedSourceConfirmationResult, reason: RetainedConfirmationFailure) =
        assertEquals(RetainedSourceConfirmationResult.Rejected(reason), r)
    private fun observedRow(b: ConfirmationBinding.RetainedSource) = when (val o = b.observed) {
        is RetainedDestinationTuple.Payload -> o.row
        is RetainedDestinationTuple.Guard -> o.row
        is RetainedDestinationTuple.Journal, is RetainedDestinationTuple.RetirementJournal -> error("no row")
    }.toPayloadEntry()

    // ═══ retained floor rows ═══════════════════════════════════════════════════════════════════════════════════════════
    @Test fun R01_mutationsHoldAdd_landed_floorLN_retainedFromTheAfterRow_bothOverloads() = runReleaseTest {
        val run = holdAdd(); val d = DestinationLocator.Payload(ControlKind.HOLD, run.id)
        for (branch in listOf(L, N)) {
            assertEquals(run.afterRow.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(run.floor(branch), d, run.confirmed))))
            assertEquals(run.afterRow.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(run.floor(branch), d, lockedRead(run.store)))))
        }
    }

    @Test fun R02_mutationsHoldAdd_storedFloorChanged_isNoExactRetainedRow() = runReleaseTest {
        val run = holdAdd()
        val changedRow = FloorGuardFixtures.field(run.afterRow, "floor", FloorGuardFixtures.floor(30_000, elapsed = 9_000))
        assertTrue(ControlObligations.read(ControlKind.HOLD, changedRow) is ControlEntryRead.Interpreted)
        val changed = open(); changed.seed(hold = "[${changedRow.toPayloadEntry().fields}]")
        rejected(PriorStorageConfirmation.confirmRetainedSource(run.floor(N), DestinationLocator.Payload(ControlKind.HOLD, run.id), lockedRead(changed)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    @Test fun R03_mutationsHoldAdd_otherIdLocator_isSubjectOrBoundMismatch() = runReleaseTest {
        val run = holdAdd()
        rejected(PriorStorageConfirmation.confirmRetainedSource(run.floor(L), DestinationLocator.Payload(ControlKind.HOLD, "other"), run.confirmed),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    /** A recaptured guard floor is bound to the AFTER row: landed, both the guard FLOOR and the whole-row locator issue. */
    @Test fun R04_mutationsGuardFloorRecapture_landed_retainedFromTheAfterRow() = runReleaseTest {
        val run = guardRecapture()
        assertEquals("fixture: max(30000 − 10000, 50000) anchored at 20000", FloorV1("boot", 20_000, 50_000, LifetimeId("life2")),
            guard(run.afterRow)?.floor)
        // The slot's subject keeps the before floor's origin while its bound is the AFTER floor (RequiredObligations.kt:347).
        assertEquals(LifetimeId("life"), (run.floor(N).key.subject as ObligationSubject.Floor).origin)
        assertEquals(LifetimeId("life2"), ((run.floor(N).requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Floor)
            .captured.originLifetimeId)
        for (d in listOf(DestinationLocator.Guard("g", GuardPart.FLOOR), DestinationLocator.Payload(ControlKind.DEMAND, "g")))
            for (branch in listOf(L, N))
                assertEquals(run.afterRow.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(run.floor(branch), d, run.confirmed))))
    }

    /** Before landing only the BEFORE row is stored; its floor is not the slot's bound (the recaptured floor). */
    @Test fun R05_mutationsGuardFloorRecapture_onlyTheBeforeRowStored_isNoExactRetainedRow() = runReleaseTest {
        val run = guardRecapture()
        val before = open(); before.seed(demand = "[${oldGuard.toPayloadEntry().fields}]")
        for (d in listOf(DestinationLocator.Guard("g", GuardPart.FLOOR), DestinationLocator.Payload(ControlKind.DEMAND, "g")))
            rejected(PriorStorageConfirmation.confirmRetainedSource(run.floor(N), d, lockedRead(before)), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** A HOLD Edit may only be a no-op, so its BEFORE row (the floor source) is also the stored row. */
    @Test fun R06_mutationsHoldEdit_landed_floorRetainedFromTheBeforeRow() = runReleaseTest {
        val hold = ControlObligationFixtures.node(ControlObligationFixtures.hold)
        val s = open(); s.seed(hold = "[${hold.toPayloadEntry().fields}]")
        val run = mutate(s, s.control.edit(ControlKind.HOLD, hold) {})
        for (branch in listOf(L, N))
            assertEquals(hold.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(run.floor(branch),
                DestinationLocator.Payload(ControlKind.HOLD, "h"), run.confirmed))))
    }

    @Test fun R07_mutationsGuardAdd_landed_floorRetainedFromTheAfterRow() = runReleaseTest {
        val s = open(); s.seed()
        val floorOnly = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val run = mutate(s, s.control.addition(ControlKind.DEMAND) { id -> literal(floorOnly); set("id", ControlScalar.Text(id)) })
        for (d in listOf(DestinationLocator.Guard(run.id, GuardPart.FLOOR), DestinationLocator.Payload(ControlKind.DEMAND, run.id)))
            for (branch in listOf(L, N))
                assertEquals(run.afterRow.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(run.floor(branch), d, run.confirmed))))
    }

    /** A slot whose floor bound is no longer the fixed row's floor is a changed slot, not a changed stored row. */
    @Test fun R08_mutationsHoldAdd_slotBoundChanged_isSubjectOrBoundMismatch() = runReleaseTest {
        val run = holdAdd(); val slot = run.floor(N)
        val req = slot.requirement as SlotRequirement.Required
        val changed = slot.copy(requirement = req.copy(lowerBound = (req.lowerBound as RequiredLowerBound.Floor)
            .copy(captured = FloorV1("boot", 9_000, 30_000, LifetimeId("life")))))
        rejected(PriorStorageConfirmation.confirmRetainedSource(changed, DestinationLocator.Payload(ControlKind.HOLD, run.id), run.confirmed),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val subject = slot.key.subject as ObligationSubject.Floor
        val otherSubject = slot.copy(key = slot.key.copy(subject = subject.copy(origin = LifetimeId("other-life"))))
        rejected(PriorStorageConfirmation.confirmRetainedSource(otherSubject, DestinationLocator.Payload(ControlKind.HOLD, run.id), run.confirmed),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    /**
     * SETTLE_QUERY that keeps a floored guard lists it among the required-unchanged rows (not the targets): its floor slot's
     * original row is that unchanged row's AFTER, which is the stored guard itself.
     */
    @Test fun R09_lifecycleUnchangedGuard_floorRetainedFromItsAfterRow() = runReleaseTest {
        val F = DemandAuthFixtures
        val kept = F.guard(auth = null, wait = 600_000)
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), kept, null, F.binding, F.decision(),
            LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        val c = F.command(p)
        val fixed = (c.body as ControlCommandBody.Lifecycle).input
        assertTrue("fixture: the kept guard is a required-unchanged row", fixed.requiredUnchanged.any { it.target.id == "g" })
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val slot = a1.orderedSlots.single { it.key.component == ObligationComponent.FLOOR && it.key.branch == N }
        val s = open(); controlTestTimeout("seed kept guard") { s.data.updateData { F.raw(kept) } }
        for (d in listOf(DestinationLocator.Guard("g", GuardPart.FLOOR), DestinationLocator.Payload(ControlKind.DEMAND, "g")))
            assertEquals(kept.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(slot, d, lockedRead(s)))))
    }

    /**
     * A slot re-pointed at another guard (sourceId and subject both g2) whose stored row carries the same floor: the fixed row
     * that produced the floor is the action's g1, so the slot no longer names its own fixed row.
     */
    @Test fun R11_mutationsGuardAdd_slotRepointedToAnotherIdWithTheSameFloor_isSubjectOrBoundMismatch() = runReleaseTest {
        val s = open(); s.seed()
        val floorOnly = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val run = mutate(s, s.control.addition(ControlKind.DEMAND) { id -> literal(floorOnly); set("id", ControlScalar.Text(id)) })
        val slot = run.floor(N)
        val req = slot.requirement as SlotRequirement.Required
        val bound = req.lowerBound as RequiredLowerBound.Floor
        val subject = slot.key.subject as ObligationSubject.Floor
        val repointed = slot.copy(key = slot.key.copy(subject = subject.copy(sourceId = "g2")),
            requirement = req.copy(lowerBound = bound.copy(sourceId = "g2")))
        val twin = FloorGuardFixtures.field(run.afterRow, "id", kotlinx.serialization.json.JsonPrimitive("g2"))
        val other = open(); other.seed(demand = "[${twin.toPayloadEntry().fields}]")
        assertEquals("fixture: the twin carries the same floor", guard(run.afterRow)?.floor, guard(twin)?.floor)
        rejected(PriorStorageConfirmation.confirmRetainedSource(repointed, DestinationLocator.Guard("g2", GuardPart.FLOOR), lockedRead(other)),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    // ═══ RECOVER_HOLD guard CREATE ═════════════════════════════════════════════════════════════════════════════════════
    private class RRun(val store: ControlStoreTestStorage, val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val guardTarget get() = fixed.targets.single { it.role == LifecycleRole.GUARD }.target
        val guardAfter get() = checkNotNull(fixed.targets.single { it.role == LifecycleRole.GUARD }.after)
    }
    private suspend fun recoverWithoutGuard(): RRun {
        val s = open()
        val base = H.input(g = null)
        val input = RecoverHoldInput(base.source, base.guard, base.before, base.binding, base.closure, H.now)
        controlTestTimeout("seed hold without guard") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        val r = controlTestTimeout("A3d1 execute recover") { s.control.execute(c, H.context(input)) }
        assertTrue("fixture: the store must return Confirmed, got $r", r is ControlStoreResult.Confirmed)
        return RRun(s, c, r as ControlStoreResult.Confirmed)
    }
    private val createdFloor = FloorV1("boot", 11_000, 29_000, LifetimeId("new-life"))

    /**
     * RECOVER_HOLD's guard FLOOR slot lists both the HOLD and the guard target rows among its fixed sources; its original row
     * is the guard's AFTER (the re-anchored floor), which the landed store holds.
     */
    @Test fun R10_recoverHoldGuardFloor_landed_retainedFromTheGuardsAfterRow() = runReleaseTest {
        val s = open()
        val base = H.input()
        val input = RecoverHoldInput(base.source, base.guard, base.before, base.binding, base.closure, H.now)
        controlTestTimeout("seed hold with guard") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        val r = controlTestTimeout("A3d1 execute recover") { s.control.execute(c, H.context(input)) }
        assertTrue("fixture: the store must return Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val slot = a1.orderedSlots.single { it.key.component == ObligationComponent.FLOOR && it.key.branch == N &&
            (it.key.subject as ObligationSubject.Floor).sourceKind == ControlKind.DEMAND }
        val wholeRows = (slot.requirement as SlotRequirement.Required).fixedSources.count { it.location.facet == FixedInputFacet.WHOLE &&
            it.fact is FixedSourceFact.LifecycleTarget }
        assertEquals("fixture: HOLD and guard target rows", 2, wholeRows)
        val after = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.targets.single { it.role == LifecycleRole.GUARD }.after)
        assertEquals("fixture: the re-anchored floor", FloorV1("boot", 11_000, 29_000, LifetimeId("new-life")), guard(after)?.floor)
        assertEquals(after.toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(slot,
            DestinationLocator.Guard("g", GuardPart.FLOOR), r as ControlStoreResult.Confirmed))))
    }
    private fun createToken(run: RRun): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmLifecycleOutput(run.c, run.fixed, run.confirmed, run.guardTarget)
        assertTrue("expected Issued, got $r", r is LifecycleOutputConfirmationResult.Issued)
        return (r as LifecycleOutputConfirmationResult.Issued).value
    }

    @Test fun C01_recoverHoldWithoutGuard_guardCreate_issuesGuardFloorOutputOfTheFixedAfterRow() = runReleaseTest {
        val run = recoverWithoutGuard()
        assertEquals("fixture: a created guard", LifecycleEffect.CREATE, run.guardTarget.effect)
        assertEquals("fixture: no guard before", null, run.fixed.targets.single { it.role == LifecycleRole.GUARD }.before)
        val b = createToken(run).binding as ConfirmationBinding.LifecycleOutput
        assertEquals(run.guardTarget, b.outputTarget)
        val out = b.output
        assertTrue("expected GuardFloor, got $out", out is TypedDestinationTuple.GuardFloor)
        out as TypedDestinationTuple.GuardFloor
        assertEquals(DestinationLocator.Guard(run.guardTarget.id, GuardPart.FLOOR), out.locator)
        assertEquals(run.guardAfter.toPayloadEntry(), out.row.toPayloadEntry())
        assertEquals(createdFloor, out.parsed.floor)
    }

    private fun parsedHold() = (ControlObligations.read(ControlKind.HOLD, H.hold()) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun holdSource(run: RRun) = TypedSourceTuple.HoldFloor(ResponsibilityOwner(run.c.ownerTrackingLifetimeId, "owner-1"),
        FloorSource("h", H.hold(), parsedHold(), FloorV1("boot", 10_000, 30_000, LifetimeId("life")), null), null, H.now, H.life)

    @Test fun C02_recoverHoldWithoutGuard_holdFloorLinkOnTheCreateToken_issued() = runReleaseTest {
        val run = recoverWithoutGuard(); val t = createToken(run)
        val out = (t.binding as ConfirmationBinding.LifecycleOutput).output
        val r = NamedTransferLink.linkNamedTransfer(holdSource(run), out, t)
        assertTrue("expected Issued, got $r", r is NamedTransferResult.Issued)
        val link = (r as NamedTransferResult.Issued).value
        assertEquals(LifecycleTransition.RECOVER_HOLD, link.transition)
        assertEquals(createdFloor, (link.destination as TypedDestinationTuple.GuardFloor).parsed.floor)
    }

    /** A created guard has no preimage, so no GuardFloor source can start from it on its own CREATE token. */
    @Test fun C03_recoverHoldWithoutGuard_guardFloorSourceOnTheCreateToken_isSourceMismatch() = runReleaseTest {
        val run = recoverWithoutGuard(); val t = createToken(run)
        val out = (t.binding as ConfirmationBinding.LifecycleOutput).output as TypedDestinationTuple.GuardFloor
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH)),
            NamedTransferLink.linkNamedTransfer(TypedSourceTuple.GuardFloor(ResponsibilityOwner(run.c.ownerTrackingLifetimeId, "owner-1"),
                out.row, out.parsed), out, t))
    }

    private fun withSnapshot(confirmed: ControlStoreResult.Confirmed, edit: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) =
        confirmed.copy(snapshot = ConfirmedControlSnapshot(ControlRecordReader().read(
            confirmed.snapshot.record.original.toMutablePreferences().apply(edit).toPreferences()) as ControlRecordRead.Supported))
    private fun confirmCreate(run: RRun, confirmed: ControlStoreResult.Confirmed) =
        PriorStorageConfirmation.confirmLifecycleOutput(run.c, run.fixed, confirmed, run.guardTarget)
    private fun rejectedCreate(r: LifecycleOutputConfirmationResult, reason: LifecycleConfirmationFailure) =
        assertEquals(LifecycleOutputConfirmationResult.Rejected(reason), r)

    /** The same real CREATE return, one fact broken at a time. */
    @Test fun C04_recoverHoldWithoutGuard_guardCreate_receiptAppliedAndOutputRowNegatives() = runReleaseTest {
        val run = recoverWithoutGuard()
        rejectedCreate(confirmCreate(run, run.confirmed.copy(receipt = null)), LifecycleConfirmationFailure.RECEIPT_MISSING)
        val receipt = checkNotNull(run.confirmed.lifecycleReceipt)
        assertTrue("fixture: the created guard is observed", receipt.targets.any { it.target == run.guardTarget })
        val changed = receipt.targets.map { if (it.target == run.guardTarget) it.copy(observation = LifecycleTargetObservation.Changed) else it }
        rejectedCreate(confirmCreate(run, run.confirmed.copy(receipt = ControlLifecycleReceipt(receipt.transition, receipt.commandId, changed,
            receipt.before, receipt.after, receipt.journal, receipt.hasUninterpretable, receipt.hasUninterpretableMetadata,
            receipt.requiredUnchanged))), LifecycleConfirmationFailure.RECEIPT_MISMATCH)
        rejectedCreate(confirmCreate(run, withSnapshot(run.confirmed) { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }),
            LifecycleConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
        val id = run.guardTarget.id
        val longer = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = H.payload(H.read(run.confirmed.snapshot.record.original)
                .arrays.getValue(ControlKind.DEMAND).entries.map {
                    val e = it as ControlEntryRead.Interpreted
                    if (e.value.id == id) H.field(e.original, "floor", FloorGuardFixtures.floor(30_000, "boot", 11_000, "new-life")) else e.original
                })
        }
        assertTrue("fixture: the altered guard is interpretable", !longer.snapshot.record.hasUninterpretable)
        rejectedCreate(confirmCreate(run, longer), LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
        val floorless = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = H.payload(H.read(run.confirmed.snapshot.record.original)
                .arrays.getValue(ControlKind.DEMAND).entries.map {
                    val e = it as ControlEntryRead.Interpreted
                    if (e.value.id == id) H.field(e.original, "floor", null) else e.original
                })
        }
        assertTrue("fixture: the floorless guard is interpretable", !floorless.snapshot.record.hasUninterpretable)
        rejectedCreate(confirmCreate(run, floorless), LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
    }
}
