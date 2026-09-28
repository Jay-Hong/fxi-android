package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.RecordTransactionDecision
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
 * Claude-owned 6-4bA3d2 contract: G05 accepts an old command's floor slots through links issued by later RECOVER_HOLD commands
 * (6-4bA3 consensus r6 N12 (2)–(8), N13 A3d2). One file holds, in order: a Mutations command adding HOLDs A and B (same
 * command), A's RECOVER_HOLD (no guard → guard CREATE, output anchored at mergeNow 11000 with A's remaining 29000), and B's
 * RECOVER_HOLD into the same guard (exact max of the guard's 29000 and B's 29300 → 29300). The old Mutations command is judged.
 *  - A floor chain's first link is bound to the slot's floor-producing fixed row (a HOLD Add's AFTER): text, id, parsed, floor.
 *    Each link's token belongs to the command that issued it; adjacent GuardFloor links continue output → source; a HoldFloor
 *    link may only be first; the last locator is the submitted one; link owner keys equal the handoff owner key. REQUEST keeps
 *    N9 (b) (all tokens of the exact command) — the NamedChain N07/N18/N29 rows are unchanged.
 *  - After only A is recovered, A's FLOOR L/N are owned by the guard (output floor) and B's FLOOR L/N by B's own HOLD row
 *    (retained, A3d1): no FLOOR failure. B's SOURCE L/N cannot be declared complete while B is stored (COMPLETED_CONFLICT).
 *  - After both, A's FLOOR L/N use the two-command chain HoldFloor(A) → GuardFloor(B's), B's FLOOR L/N their HoldFloor(B): the
 *    whole old handoff is accepted, every FLOOR slot represented by the last output (29300).
 * Two old commands with byte-identical fixed HOLD rows are not distinguishable by these data (consensus r6 design §limit); HOLD
 * ids are issued per Add, so that case is not constructed. The implementation thread reads but does not edit this file.
 */
class CrossCommandFloorContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3d2 cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)

    // ── the sequence ───────────────────────────────────────────────────────────────────────────────────────────────────
    private class Old(val c: CommandRef, val a1: RequirementDerivation.Available, val idA: String, val idB: String) {
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun slot(k: RequiredObligationKey) = a1.orderedSlots.single { it.key == k }
        fun at(k: RequiredObligationKey) = required.indexOfFirst { it.key == k }.also { check(it >= 0) }
        fun key(index: Int, component: ObligationComponent, branch: LandingBranch) = required.single {
            it.key.role == ObligationRole.MutationAction(index) && it.key.component == component && it.key.branch == branch }.key
    }
    private class Recover(val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val guardRow get() = fixed.targets.single { it.role == LifecycleRole.GUARD }
        val guardTarget get() = guardRow.target
        val guardAfter get() = checkNotNull(guardRow.after)
    }
    private class Seq(val store: ControlStoreTestStorage, val old: Old, val rowA: ControlNode, val rowB: ControlNode,
        val a: Recover, val b: Recover?, val rowC: ControlNode? = null, val c: Recover? = null)

    private fun holdJson(anchor: Long) = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30_000, "boot", anchor, "life"))
        .toPayloadEntry().fields.toString()
    private suspend fun execute(s: ControlStoreTestStorage, c: CommandRef, context: AttemptContext?): ControlStoreResult.Confirmed {
        val r = controlTestTimeout("A3d2 execute") { if (context == null) s.control.execute(c) else s.control.execute(c, context) }
        assertTrue("fixture: the store must return Confirmed, got $r", r is ControlStoreResult.Confirmed)
        return r as ControlStoreResult.Confirmed
    }
    private suspend fun recover(s: ControlStoreTestStorage, source: ControlNode, guard: ControlNode?, krx: String, orderStart: Long): Recover {
        val input = RecoverHoldInput(source, guard, FenceV1("A", "u", krx), H.binding, H.restart(source, H.executor), H.now)
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, orderStart))
        return Recover(c, execute(s, c, H.context(input)))
    }
    private suspend fun sequence(recoverB: Boolean, withC: Boolean = false): Seq {
        val s = open()
        controlTestTimeout("seed namespace") {
            s.data.updateData { H.before(H.input(g = null), siblings = false).toMutablePreferences().apply {
                this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }.toPreferences() }
        }
        val addA = s.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_000)); set("id", ControlScalar.Text(id)) }
        val addB = s.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_300)); set("id", ControlScalar.Text(id)) }
        val c = s.control.prepare(addA, addB)
        execute(s, c, null)
        val a1 = deriveRequiredObligations(RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Previous(checkNotNull(s.control.checkpoint(c)) { "fixture: a retained checkpoint" })))
            .let { assertTrue("fixture: A1 Available, got $it", it is RequirementDerivation.Available); it as RequirementDerivation.Available }
        fun idOf(a: ControlMutation) = (ControlObligations.read(ControlKind.HOLD, ((a as ControlMutation.Add).built as ControlWriteResult.Written).node)
            as ControlEntryRead.Interpreted).value.id
        val old = Old(c, a1, idOf(addA), idOf(addB))
        // Another old command: its own Mutations adding HOLD C (remaining 29400 at mergeNow).
        val idC = if (!withC) null else {
            val addC = s.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_400)); set("id", ControlScalar.Text(id)) }
            execute(s, s.control.prepare(addC), null)
            idOf(addC)
        }
        val raw = s.raw()
        val rowA = H.row(raw, ControlKind.HOLD, old.idA); val rowB = H.row(raw, ControlKind.HOLD, old.idB)
        val a = recover(s, rowA, null, "k", 21)
        assertEquals("fixture: A's guard is created", LifecycleEffect.CREATE, a.guardTarget.effect)
        assertEquals("fixture: A's output floor", FloorV1("boot", 11_000, 29_000, LifetimeId("new-life")), guard(a.guardAfter)?.floor)
        val b = if (!recoverB) null else {
            val krx = checkNotNull(s.raw()[KRX_EPOCH])
            recover(s, rowB, H.row(s.raw(), ControlKind.DEMAND, a.guardTarget.id), krx, 22).also {
                assertEquals("fixture: B joins A's guard", a.guardTarget.id, it.guardTarget.id)
                assertEquals("fixture: exact max", FloorV1("boot", 11_000, 29_300, LifetimeId("new-life")), guard(it.guardAfter)?.floor)
            }
        }
        val rowC = idC?.let { H.row(raw, ControlKind.HOLD, it) }
        val cr = if (rowC == null) null else {
            val krx = checkNotNull(s.raw()[KRX_EPOCH])
            recover(s, rowC, H.row(s.raw(), ControlKind.DEMAND, a.guardTarget.id), krx, 23).also {
                assertEquals("fixture: C joins the same guard", a.guardTarget.id, it.guardTarget.id)
                assertEquals("fixture: exact max", FloorV1("boot", 11_000, 29_400, LifetimeId("new-life")), guard(it.guardAfter)?.floor)
            }
        }
        return Seq(s, old, rowA, rowB, a, b, rowC, cr)
    }

    // ── issuers ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun owner(c: CommandRef, key: String = "owner-1") = ResponsibilityOwner(c.ownerTrackingLifetimeId, key)
    private fun token(r: Recover): PriorStorageConfirmation {
        val t = PriorStorageConfirmation.confirmLifecycleOutput(r.c, r.fixed, r.confirmed, r.guardTarget)
        assertTrue("fixture: guard output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        return (t as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun issued(r: NamedTransferResult): NamedTransferLink {
        assertTrue("fixture: the A3b1 issuer must issue, got $r", r is NamedTransferResult.Issued)
        return (r as NamedTransferResult.Issued).value
    }
    private fun parsedHold(row: ControlNode) = (ControlObligations.read(ControlKind.HOLD, row) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun output(r: Recover) = TypedDestinationTuple.GuardFloor(DestinationLocator.Guard(r.guardTarget.id, GuardPart.FLOOR),
        r.guardAfter, checkNotNull(guard(r.guardAfter)))
    private fun holdLink(r: Recover, row: ControlNode, oldGuard: ControlNode?, t: PriorStorageConfirmation, key: String = "owner-1") =
        issued(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.HoldFloor(owner(r.c, key),
            FloorSource(parsedHold(row).id, row, parsedHold(row), checkNotNull(parsedHold(row).floor), oldGuard), oldGuard, H.now, H.life),
            output(r), t))
    private fun guardLink(r: Recover, preimage: ControlNode, t: PriorStorageConfirmation) = issued(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.GuardFloor(owner(r.c), preimage, checkNotNull(guard(preimage))), output(r), t))
    private suspend fun lockedRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> =
        controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }

    // ── handoff / assess ───────────────────────────────────────────────────────────────────────────────────────────────
    private fun chain(d: DestinationLocator, links: List<NamedTransferLink>, prior: PriorStorageConfirmation?) =
        HandoffDisposition.DurablyOwned(d, links, prior)
    private fun completed(k: RequiredObligationKey): HandoffDisposition = HandoffDisposition.CompletedAndConsumed(ComponentCompletion(k.subject), emptyList())
    private fun assess(o: Old, given: Map<RequiredObligationKey, HandoffDisposition>, latest: ControlRecordRead) = assessG05(o.c, o.a1,
        CompletionHandoff(o.a1.commandBinding, owner(o.c), o.required.map { SlotHandoff(it.key, given[it.key] ?: completed(it.key)) }),
        TerminationClosures.of(o.c), latest, now)
    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(expected, (r as G05Result.Rejected).failures)
    }
    private fun f(id: G05Id, o: Old, k: RequiredObligationKey, actual: G05Location? = null) = G05Failure(id, k,
        G05Location.Fixed((o.slot(k).requirement as SlotRequirement.Required).fixedSources.first().location), G05Location.Submitted(o.at(k)), actual)
    private fun rowAt(latest: ControlRecordRead.Supported, id: String): G05Location.ActualPayload {
        val (kind, entry) = latest.locations(id).single()
        return G05Location.ActualPayload(kind, latest.arrays.getValue(kind).entries.indexOfFirst { it === entry })
    }
    private fun keys(o: Old) = object {
        val aSourceL = o.key(0, ObligationComponent.SOURCE, L); val aSourceN = o.key(0, ObligationComponent.SOURCE, N)
        val aFloorL = o.key(0, ObligationComponent.FLOOR, L); val aFloorN = o.key(0, ObligationComponent.FLOOR, N)
        val bSourceL = o.key(1, ObligationComponent.SOURCE, L); val bSourceN = o.key(1, ObligationComponent.SOURCE, N)
        val bFloorL = o.key(1, ObligationComponent.FLOOR, L); val bFloorN = o.key(1, ObligationComponent.FLOOR, N)
    }
    /** Both recovered: A's two-command chain and B's own link, all on B's token, B's output guard as destination. */
    private fun bothGiven(q: Seq): Map<RequiredObligationKey, HandoffDisposition> {
        val b = checkNotNull(q.b); val t = token(b); val k = keys(q.old)
        val chainA = listOf(holdLink(q.a, q.rowA, null, token(q.a)), guardLink(b, q.a.guardAfter, t))
        val linkB = holdLink(b, q.rowB, q.a.guardAfter, t)
        val g = output(b).locator
        return mapOf(k.aFloorL to chain(g, chainA, t), k.aFloorN to chain(g, chainA, t),
            k.bFloorL to chain(g, listOf(linkB), t), k.bFloorN to chain(g, listOf(linkB), t))
    }

    // ═══ positives ═════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P01_onlyARecovered_floorSlotsOwnedByTheGuardAndByBsRow_onlyBsSourceConflicts() = runReleaseTest {
        val q = sequence(recoverB = false); val k = keys(q.old)
        assertEquals("fixture: SOURCE L/N + FLOOR L/N per HOLD Add", 8, q.old.required.size)
        val read = lockedRead(q.store); val latest = read.value
        val tA = token(q.a); val linkA = holdLink(q.a, q.rowA, null, tA); val g = output(q.a).locator
        val bRow = DestinationLocator.Payload(ControlKind.HOLD, q.old.idB)
        fun retainedB(key: RequiredObligationKey): PriorStorageConfirmation {
            val r = PriorStorageConfirmation.confirmRetainedSource(q.old.slot(key), bRow, read)
            assertTrue("fixture: B's retained floor token, got $r", r is RetainedSourceConfirmationResult.Issued)
            return (r as RetainedSourceConfirmationResult.Issued).value
        }
        val given = mapOf(k.aFloorL to chain(g, listOf(linkA), tA), k.aFloorN to chain(g, listOf(linkA), tA),
            k.bFloorL to chain(bRow, emptyList(), retainedB(k.bFloorL)), k.bFloorN to chain(bRow, emptyList(), retainedB(k.bFloorN)))
        val at = rowAt(latest, q.old.idB)
        rejected(assess(q.old, given, latest), listOf(f(G05Id.COMPLETED_CONFLICT, q.old, k.bSourceL, at),
            f(G05Id.COMPLETED_CONFLICT, q.old, k.bSourceN, at)))
    }

    @Test fun P02_bothRecovered_twoCommandChainForA_ownLinkForB_wholeOldHandoffAccepted() = runReleaseTest {
        val q = sequence(recoverB = true)
        assertEquals(G05Result.Accepted, assess(q.old, bothGiven(q), lockedRead(q.store).value))
    }

    // ═══ negatives (both recovered) ════════════════════════════════════════════════════════════════════════════════════
    /** B's HoldFloor link on A's FLOOR N: the first source is not A's fixed AFTER row; the HOLD slot may not name the guard. */
    @Test fun N01_firstSourceIsAnotherHoldsRow_destinationAndConfirmation_noOwner() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old); val latest = lockedRead(q.store).value
        val b = checkNotNull(q.b); val t = token(b)
        val given = bothGiven(q) + (k.aFloorN to chain(output(b).locator, listOf(holdLink(b, q.rowB, q.a.guardAfter, t)), t))
        rejected(assess(q.old, given, latest), listOf(f(G05Id.DESTINATION, q.old, k.aFloorN, rowAt(latest, b.guardTarget.id)),
            f(G05Id.CONFIRMATION, q.old, k.aFloorN)))
    }

    @Test fun N02_linkOwnerKeyDiffers_ownerAndConfirmation() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old)
        val b = checkNotNull(q.b); val t = token(b)
        val stranger = listOf(holdLink(q.a, q.rowA, null, token(q.a), key = "owner-2"), guardLink(b, q.a.guardAfter, t))
        rejected(assess(q.old, bothGiven(q) + (k.aFloorN to chain(output(b).locator, stranger, t)), lockedRead(q.store).value),
            listOf(f(G05Id.CONFIRMATION, q.old, k.aFloorN), f(G05Id.OWNER, q.old, k.aFloorN)))
    }

    /** A HoldFloor link after the first breaks the chain: the HOLD slot falls back to its own locator. */
    @Test fun N03_holdFloorAsSecondLink_destinationAndConfirmation() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old); val latest = lockedRead(q.store).value
        val b = checkNotNull(q.b); val t = token(b)
        val broken = listOf(holdLink(q.a, q.rowA, null, token(q.a)), holdLink(b, q.rowB, q.a.guardAfter, t))
        rejected(assess(q.old, bothGiven(q) + (k.aFloorN to chain(output(b).locator, broken, t)), latest),
            listOf(f(G05Id.DESTINATION, q.old, k.aFloorN, rowAt(latest, b.guardTarget.id)), f(G05Id.CONFIRMATION, q.old, k.aFloorN)))
    }

    /** Repeating B's GuardFloor link: its source (A's output) is not the previous link's output (B's output). */
    @Test fun N04_guardChainDiscontinuous_destinationAndConfirmation() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old); val latest = lockedRead(q.store).value
        val b = checkNotNull(q.b); val t = token(b); val gl = guardLink(b, q.a.guardAfter, t)
        val broken = listOf(holdLink(q.a, q.rowA, null, token(q.a)), gl, gl)
        rejected(assess(q.old, bothGiven(q) + (k.aFloorN to chain(output(b).locator, broken, t)), latest),
            listOf(f(G05Id.DESTINATION, q.old, k.aFloorN, rowAt(latest, b.guardTarget.id)), f(G05Id.CONFIRMATION, q.old, k.aFloorN)))
    }

    /** The guard's floor shortened below B's output (29300) at the same now: every FLOOR slot loses confirmation and bound. */
    @Test fun N05_latestGuardShortened_allFourFloorSlots_confirmationAndLowerBoundAtRow() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old); val b = checkNotNull(q.b)
        val landed = lockedRead(q.store).value
        val latest = H.read(H.changeRow(landed.original, ControlKind.DEMAND, b.guardTarget.id) {
            H.field(it, "floor", FloorGuardFixtures.floor(29_000, "boot", 11_000, "new-life")) })
        val a = rowAt(latest, b.guardTarget.id)
        val floors = listOf(k.aFloorL, k.aFloorN, k.bFloorL, k.bFloorN)
        rejected(assess(q.old, bothGiven(q), latest), floors.map { f(G05Id.CONFIRMATION, q.old, it, a) } +
            floors.map { f(G05Id.LOWER_BOUND, q.old, it, a) })
    }

    /** A longer floor keeps every bound, but the guard row is not the last output any more. */
    @Test fun N06_latestGuardLengthened_allFourFloorSlots_confirmationAtRowOnly() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old); val b = checkNotNull(q.b)
        val landed = lockedRead(q.store).value
        val latest = H.read(H.changeRow(landed.original, ControlKind.DEMAND, b.guardTarget.id) {
            H.field(it, "floor", FloorGuardFixtures.floor(40_000, "boot", 11_000, "new-life")) })
        val a = rowAt(latest, b.guardTarget.id)
        rejected(assess(q.old, bothGiven(q), latest), listOf(k.aFloorL, k.aFloorN, k.bFloorL, k.bFloorN).map { f(G05Id.CONFIRMATION, q.old, it, a) })
    }

    // ═══ contract review r1 additions ═════════════════════════════════════════════════════════════════════════════════
    /**
     * A third RECOVER_HOLD brings HOLD C of another old Mutations command into the same guard (29400). The old A/B handoff
     * extended by C's GuardFloor link is accepted; A's FLOOR N started from C's HoldFloor link — same last locator and output —
     * is bound to another old command's row, not A's.
     */
    @Test fun N07_linkStartingFromAnotherOldCommandsHold_destinationAndConfirmation() = runReleaseTest {
        val q = sequence(recoverB = true, withC = true); val k = keys(q.old); val latest = lockedRead(q.store).value
        val b = checkNotNull(q.b); val c = checkNotNull(q.c); val tB = token(b); val tC = token(c)
        val gC = guardLink(c, b.guardAfter, tC)
        val chainA = listOf(holdLink(q.a, q.rowA, null, token(q.a)), guardLink(b, q.a.guardAfter, tB), gC)
        val chainB = listOf(holdLink(b, q.rowB, q.a.guardAfter, tB), gC)
        val g = output(c).locator
        val extended = mapOf(k.aFloorL to chain(g, chainA, tC), k.aFloorN to chain(g, chainA, tC),
            k.bFloorL to chain(g, chainB, tC), k.bFloorN to chain(g, chainB, tC))
        assertEquals(G05Result.Accepted, assess(q.old, extended, latest))
        val foreign = holdLink(c, checkNotNull(q.rowC), b.guardAfter, tC)
        rejected(assess(q.old, extended + (k.aFloorN to chain(g, listOf(foreign), tC)), latest),
            listOf(f(G05Id.DESTINATION, q.old, k.aFloorN, rowAt(latest, c.guardTarget.id)), f(G05Id.CONFIRMATION, q.old, k.aFloorN)))
    }

    @Test fun N08_validChainButPriorWriteIsAnEarlierToken_confirmationOnly() = runReleaseTest {
        val q = sequence(recoverB = true); val k = keys(q.old)
        val given = bothGiven(q); val owned = given.getValue(k.aFloorN) as HandoffDisposition.DurablyOwned
        rejected(assess(q.old, given + (k.aFloorN to chain(owned.destination, owned.linkChain, token(q.a))), lockedRead(q.store).value),
            listOf(f(G05Id.CONFIRMATION, q.old, k.aFloorN)))
    }

    /** B's exact max is 29300: one short or one over is refused by the candidate and re-anchor checks, before any G05. */
    @Test fun N09_secondRecoveryExactMaxShortOrOver_refusedByCandidateAndReanchor() = runReleaseTest {
        val q = sequence(recoverB = true); val b = checkNotNull(q.b)
        val input = HoldFloorInput(q.rowB, q.a.guardAfter, H.now, H.life, "unused")
        val source = FloorSource(parsedHold(q.rowB).id, q.rowB, parsedHold(q.rowB), checkNotNull(parsedHold(q.rowB).floor), q.a.guardAfter)
        assertTrue("fixture: the real output is valid", HoldFloorPlan.validCandidate(input, b.guardAfter))
        for (wait in listOf(29_299L, 29_301L)) {
            val candidate = H.field(b.guardAfter, "floor", FloorGuardFixtures.floor(wait, "boot", 11_000, "new-life"))
            assertEquals(false, HoldFloorPlan.validCandidate(input, candidate))
            assertEquals(ReanchorValidation.Invalid(ReanchorField.EXACT_MAX),
                validHoldReanchor(source, q.a.guardAfter, H.now, H.life, b.guardTarget.id, candidate))
        }
    }

    // ═══ battery r1 additions (measure-6-4bA3d2/sensitivity.r1.json survivors that are contract gaps) ═══════════════════
    private val floorOnlyGuard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""

    /**
     * An old Mutations command adds HOLD h (remaining 29300) and a floored guard g (remaining 29000); a later RECOVER_HOLD merges
     * h into g (REPLACE, exact max 29300). The old guard FLOOR slots start their chain with a GuardFloor link whose source is
     * the Add's AFTER guard row; the HOLD FLOOR slots with the HoldFloor link — the whole old handoff is accepted.
     */
    @Test fun N10_oldGuardFloorSlot_guardFloorFirstLinkFromTheAddsAfterRow_accepted() = runReleaseTest {
        val s = open()
        controlTestTimeout("seed namespace") {
            s.data.updateData { H.before(H.input(g = null), siblings = false).toMutablePreferences().apply {
                this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }.toPreferences() }
        }
        val addH = s.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_300)); set("id", ControlScalar.Text(id)) }
        val addG = s.control.addition(ControlKind.DEMAND) { id -> literal(floorOnlyGuard); set("id", ControlScalar.Text(id)) }
        val c = s.control.prepare(addH, addG)
        execute(s, c, null)
        val a1 = deriveRequiredObligations(RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Previous(checkNotNull(s.control.checkpoint(c))))) as RequirementDerivation.Available
        fun id(a: ControlMutation, kind: ControlKind) = (ControlObligations.read(kind, ((a as ControlMutation.Add).built as ControlWriteResult.Written).node)
            as ControlEntryRead.Interpreted).value.id
        val o = Old(c, a1, id(addH, ControlKind.HOLD), id(addG, ControlKind.DEMAND))
        val raw = s.raw()
        val holdRow = H.row(raw, ControlKind.HOLD, o.idA); val guardRow = H.row(raw, ControlKind.DEMAND, o.idB)
        val r = recover(s, holdRow, guardRow, "k", 21)
        assertEquals("fixture: the guard is replaced", LifecycleEffect.REPLACE, r.guardTarget.effect)
        assertEquals("fixture: exact max", FloorV1("boot", 11_000, 29_300, LifetimeId("new-life")), guard(r.guardAfter)?.floor)
        val t = token(r); val g = output(r).locator
        val hold = holdLink(r, holdRow, guardRow, t); val gl = guardLink(r, guardRow, t)
        val given = mapOf(o.key(0, ObligationComponent.FLOOR, L) to chain(g, listOf(hold), t), o.key(0, ObligationComponent.FLOOR, N) to chain(g, listOf(hold), t),
            o.key(1, ObligationComponent.FLOOR, L) to chain(g, listOf(gl), t), o.key(1, ObligationComponent.FLOOR, N) to chain(g, listOf(gl), t))
        assertEquals("fixture: HOLD SOURCE/FLOOR + guard FLOOR", 6, o.required.size)
        assertEquals(G05Result.Accepted, assess(o, given, lockedRead(s).value))
    }

    /**
     * Restart: the old command ran in a store closed before the file is reopened; A and B are recovered under the new tracking
     * lifetime. Link owners carry that lifetime (bound by the issuer to their tokens), the same owner key as the old handoff.
     */
    @Test fun N11_recoveredAfterReopen_linkOwnersOfAnotherLifetimeWithTheSameKey_accepted() = runReleaseTest {
        val file = folder.newFile()
        val first = ControlStoreTestStorage(file)
        controlTestTimeout("seed namespace") {
            first.data.updateData { H.before(H.input(g = null), siblings = false).toMutablePreferences().apply {
                this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }.toPreferences() }
        }
        val addA = first.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_000)); set("id", ControlScalar.Text(id)) }
        val addB = first.control.addition(ControlKind.HOLD) { id -> literal(holdJson(10_300)); set("id", ControlScalar.Text(id)) }
        val c = first.control.prepare(addA, addB)
        execute(first, c, null)
        val a1 = deriveRequiredObligations(RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Previous(checkNotNull(first.control.checkpoint(c))))) as RequirementDerivation.Available
        fun idOf(a: ControlMutation) = (ControlObligations.read(ControlKind.HOLD, ((a as ControlMutation.Add).built as ControlWriteResult.Written).node)
            as ControlEntryRead.Interpreted).value.id
        val o = Old(c, a1, idOf(addA), idOf(addB))
        controlTestTimeout("close first", 30_000) { first.close() }
        val s = open(file)
        val raw = s.raw()
        val rowA = H.row(raw, ControlKind.HOLD, o.idA); val rowB = H.row(raw, ControlKind.HOLD, o.idB)
        val a = recover(s, rowA, null, "k", 21)
        val b = recover(s, rowB, H.row(s.raw(), ControlKind.DEMAND, a.guardTarget.id), checkNotNull(s.raw()[KRX_EPOCH]), 22)
        assertTrue("fixture: another tracking lifetime", a.c.ownerTrackingLifetimeId !== c.ownerTrackingLifetimeId)
        val q = Seq(s, o, rowA, rowB, a, b)
        assertEquals(G05Result.Accepted, assess(o, bothGiven(q), lockedRead(s).value))
    }

    /**
     * RECOVER_HOLD's own guard slot accepts a first GuardFloor(BEFORE → AFTER) link only from the exact command: the same
     * transfer issued by another command with identical inputs (another store) is not this command's own link.
     */
    @Test fun N12_recoverGuardSlot_ownBeforeLinkOfAnotherIdenticalRecover_confirmationOnly() = runReleaseTest {
        suspend fun plain(): Recover {
            val s = open()
            val input = RecoverHoldInput(H.hold(), H.guard(), FenceV1("A", "u", "k"), H.binding, H.restart(H.hold(), H.executor), H.now)
            controlTestTimeout("seed plain") { s.data.updateData { H.before(input) } }
            val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
            return Recover(c, execute(s, c, H.context(input)))
        }
        val run = plain(); val twin = plain()
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(run.c, run.c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val slot = a1.orderedSlots.single { it.key.component == ObligationComponent.FLOOR && it.key.branch == N &&
            (it.key.subject as ObligationSubject.Floor).sourceKind == ControlKind.DEMAND }
        val own = guardLink(run, H.guard(), token(run)); val foreign = guardLink(twin, H.guard(), token(twin))
        fun judge(link: NamedTransferLink): G05Result {
            val handoff = CompletionHandoff(a1.commandBinding, owner(run.c), a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
                .map { SlotHandoff(it.key, if (it.key == slot.key) chain(link.destination.locator, listOf(link), link.confirmation) else completed(it.key)) })
            return assessG05(run.c, a1, handoff, TerminationClosures.of(run.c), run.confirmed.snapshot.record, now)
        }
        // The other slots are declared complete against the landing snapshot and fail identically in both judgements; the
        // exact difference is the one CONFIRMATION of the guard FLOOR N slot.
        fun failuresOf(r: G05Result) = (r as? G05Result.Rejected)?.failures.orEmpty()
        val ownFailures = failuresOf(judge(own)); val foreignFailures = failuresOf(judge(foreign))
        assertTrue("fixture: the own link leaves the slot without failure", ownFailures.none { it.key == slot.key })
        val confirmation = G05Failure(G05Id.CONFIRMATION, slot.key,
            G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location),
            G05Location.Submitted(a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }.indexOfFirst { it.key == slot.key }), null)
        assertEquals(1, foreignFailures.count { it == confirmation })
        assertEquals(ownFailures, foreignFailures.filter { it != confirmation })
    }
}
