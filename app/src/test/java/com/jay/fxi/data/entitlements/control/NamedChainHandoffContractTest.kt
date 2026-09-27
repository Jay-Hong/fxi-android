package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RefreshIntent
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bA3b2b contract: G05 accepting DurablyOwned through a named link chain (REBIND_REQUESTS, END_AUTH_BINDING,
 * RECOVER_HOLD), the named REQUEST lower bound, the HOLD FLOOR → guard FLOOR destination transfer, and chain continuity (N7).
 * Fixed inputs: 6-4bA3 declaration r2 §2/§4, 6-4bA3 consensus r4 (N1–N9), 6-4bA3b consensus, 6-4bA3b2 recipes r1
 * (6-4bA3b2_recipes_codex.r1.md §1–§3), the committed A3b1 issuer (d8c8b23) and A3b2a empty-chain check (1d035a7).
 * The A2 fill table applies: F(k) = Fixed(first fixedSources location of the A1 slot), S(i) = Submitted(i), A = the actual row.
 * Every fixture starts from the real store's own Confirmed (seed → prepare → execute); links and tokens come from the
 * committed issuers, never hand-built.
 *
 * Decisions this contract fixes (flagged for review):
 *  - N9: a named chain is structurally valid for a slot when (a) its first link's source is bound to the slot's A1 fixed
 *    SOURCE row (REQUEST: the fixed before; HOLD FLOOR: the fixed HOLD row; guard FLOOR: the fixed old guard), (b) every
 *    link's token was issued for the exact command (binding.command === exactCommand), (c) adjacent links continue: REQUEST
 *    output → next REQUEST source, or GuardFloor output → next GuardFloor source (id, raw row, parsed; the source locator is
 *    Guard(parsed.id, FLOOR)) — any other type pair is invalid (N7), and (d) the last link's destination locator equals the
 *    submitted destination. It is then accepted when also (e) every link's owner equals the handoff owner, (f) the last link's
 *    confirmation is the very priorWrite object (===), and (g) the last output's raw row equals latest's unique row.
 *  - Structural validity (a)–(d) alone decides which lower bound applies: with it, REQUEST N uses compareNamedRequest(
 *    RequestNeed(N.source, N.minimumIntent, N.minimumOrder), last link, latest) and HOLD FLOOR's destination becomes the last
 *    link's guard FLOOR, compared against that confirmed output floor's remainingAt(now) — never the old HOLD floor again.
 *    Without it the original comparator / HOLD locator applies (the no-link fallback). (e)–(g) only add CONFIRMATION.
 *  - In a structurally valid chain ((a)–(d) hold), a link owner that differs from the handoff owner records OWNER (key k,
 *    expectedAt F(k), submittedAt S(i), actualAt null) together with CONFIRMATION(null). A structurally invalid chain (e.g. a
 *    link of another command, N07/N18, and A3b2a N09) records no OWNER; its other failures are judged independently.
 *  - For a nonempty named chain, CONFIRMATION actualAt = A requires (a)–(f) and a unique latest row whose raw value differs
 *    from the last output; other chain defects yield null. For an eligible empty chain, a unique latest row differing from
 *    the retained or N8 output token can likewise yield A.
 *  - N8: END_AUTH_BINDING's AUTH L (subject the old AUTH, bound the new AUTH on the AFTER guard) is accepted through an empty
 *    chain with the LifecycleOutput token of the command's own GUARD REPLACE target (confirmLifecycleOutput now issues a
 *    GuardAuth(Guard(id, AUTH), row, parsed) output for it; linkNamedTransfer rejects GuardAuth as UNSUPPORTED). Any other empty
 *    chain + LifecycleOutput (REQUEST L, AUTH N, another target, another command) stays CONFIRMATION(null).
 *  - A chain carrying a RetainedSource priorWrite records CONFIRMATION(null).
 *  - RECOVER_HOLD's JOURNAL / NAMESPACE_RETIREMENT completion is modelled by the owner's completePurges on the exact pending
 *    entry before reading latest; G05 does not prove the external purge (P03, N13).
 *  - A valid multi-link chain is not constructible inside one exact command's fixed sources for these three transitions, so
 *    the contract pins continuity only negatively (N11a/N11b).
 * The implementation thread reads but does not edit this file.
 */
class NamedChainHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3b2b cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val F = DemandAuthFixtures
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)

    // ── real store returns ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Run(val store: ControlStoreTestStorage, val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle))
            as RequirementDerivation.Available
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun slot(k: RequiredObligationKey) = a1.orderedSlots.single { it.key == k }
        fun at(k: RequiredObligationKey) = required.indexOfFirst { it.key == k }.also { check(it >= 0) }
        fun key(component: ObligationComponent, branch: LandingBranch, subject: (ObligationSubject) -> Boolean = { true }) =
            required.single { it.key.component == component && it.key.branch == branch && subject(it.key.subject) }.key
    }
    private suspend fun execute(store: ControlStoreTestStorage, c: CommandRef, context: AttemptContext): ControlStoreResult.Confirmed {
        val result = controlTestTimeout("A3b2b execute") { store.control.execute(c, context) }
        assertTrue("fixture: the store must return Confirmed, got $result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    private val oldRebind get() = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
    private suspend fun rebind(orderStart: Long = 21, old: ControlNode = oldRebind): Run {
        val s = open()
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(old, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, orderStart))
        return Run(s, c, execute(s, c, F.context(F.runtime())))
    }
    private val endGuard get() = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
    private val oldEnd get() = F.request(binding = 2, origin = LifetimeId("old"))
    private val endClosure get() = LifecycleBindingClosure(guard(endGuard)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
    private suspend fun end(replacement: LifecycleBinding? = F.binding): Run {
        val s = open()
        controlTestTimeout("seed end") { s.data.updateData { F.raw(endGuard, oldEnd, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareEndAuthBinding(endGuard, listOf(oldEnd), F.binding, endClosure, replacement, LifecycleOrderSource(F.life, 21))
        return Run(s, c, execute(s, c, F.context(F.runtime(closure = endClosure))))
    }
    private suspend fun recoverHold(): Run {
        val s = open()
        val base = H.input()
        val input = RecoverHoldInput(base.source, base.guard, base.before, base.binding, base.closure, H.now)
        controlTestTimeout("seed hold") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        return Run(s, c, execute(s, c, H.context(input)))
    }
    /** The owner's completePurges on the exact pending entry, then a fresh read: the caller's purge-completion model. */
    private suspend fun afterPurge(run: Run): ControlRecordRead.Supported {
        val pending = controlTestTimeout("load") { run.store.owner.load() }.pendingPurges
        assertEquals("fixture: exactly one pending purge after RECOVER_HOLD", 1, pending.size)
        controlTestTimeout("complete purge") { run.store.owner.completePurges(pending) }
        val latest = H.read(run.store.raw())
        val landed = run.confirmed.snapshot.record
        for (id in listOf("g", newRequestId(run)))
            assertEquals("fixture: purge completion keeps row $id", H.row(landed.original, ControlKind.DEMAND, id).toPayloadEntry(),
                H.row(latest.original, ControlKind.DEMAND, id).toPayloadEntry())
        return latest
    }
    private fun newRequestId(run: Run) = (run.key(ObligationComponent.REQUEST, L).subject as ObligationSubject.Request).id

    // ── issuers ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private val requestTarget = LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)
    private val guardTarget = LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE)
    private fun token(run: Run, target: LifecycleTarget): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmLifecycleOutput(run.c, run.fixed, run.confirmed, target)
        assertTrue("fixture: the A3a issuer must issue, got $r", r is LifecycleOutputConfirmationResult.Issued)
        return (r as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun retained(run: Run, k: RequiredObligationKey, d: DestinationLocator): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmRetainedSource(run.slot(k), d, run.confirmed)
        assertTrue("fixture: retained issuer must issue for $k, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value
    }
    private fun issued(r: NamedTransferResult): NamedTransferLink {
        assertTrue("fixture: the A3b1 issuer must issue, got $r", r is NamedTransferResult.Issued)
        return (r as NamedTransferResult.Issued).value
    }
    private fun owner(run: Run, key: String = "owner-1") = ResponsibilityOwner(run.c.ownerTrackingLifetimeId, key)
    private fun requestN(run: Run) = (run.slot(run.key(ObligationComponent.REQUEST, N)).requirement as SlotRequirement.Required)
        .lowerBound as RequiredLowerBound.Request
    private fun rebound(order: Long = 22) =
        F.request(id = "r", owner = "A", binding = 3, origin = F.life, intent = RefreshIntent.FORCE_PREMIUM, order = order)
    private fun reboundValue(order: Long = 22) = DemandV1("r", "A", 3, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("life"), order))
    private fun requestLink(run: Run, preimage: ControlNode, parsed: DemandV1, order: Long = 22, key: String = "owner-1",
        t: PriorStorageConfirmation = token(run, requestTarget)) = issued(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.Request(owner(run, key), preimage, parsed, requestN(run).minimumIntent, requestN(run).minimumOrder),
        TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, "r"), rebound(order), reboundValue(order)), t))
    private fun oldValue(order: Long) = DemandV1("r", "A", 2, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("old"), order))
    private fun rebindLink(run: Run) = requestLink(run, oldRebind, oldValue(Long.MAX_VALUE))
    private fun endLink(run: Run) = requestLink(run, oldEnd, oldValue(4))
    private val recoveredGuard get() = H.field(H.guard(), "floor", FloorGuardFixtures.floor(29000, "boot", 11000, "new-life"))
    private val recoveredGuardValue get() = ScheduleGuardV1("g", FloorV1("boot", 11000, 29000, LifetimeId("new-life")),
        AuthSnapshotV1("A", 2, 3, LifetimeId("life"), false, 10, 20))
    private val oldGuardValue get() = ScheduleGuardV1("g", FloorV1("boot", 10000, 10000, LifetimeId("life")),
        AuthSnapshotV1("A", 2, 3, LifetimeId("life"), false, 10, 20))
    private val guardFloorDestination get() =
        TypedDestinationTuple.GuardFloor(DestinationLocator.Guard("g", GuardPart.FLOOR), recoveredGuard, recoveredGuardValue)
    private fun parsedHold() = (ControlObligations.read(ControlKind.HOLD, H.hold()) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun holdLink(run: Run, t: PriorStorageConfirmation = token(run, guardTarget)) = issued(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.HoldFloor(owner(run), FloorSource("h", H.hold(), parsedHold(), FloorV1("boot", 10000, 30000, LifetimeId("life")),
            H.guard()), H.guard(), H.now, H.life), guardFloorDestination, t))
    private fun guardLink(run: Run, t: PriorStorageConfirmation = token(run, guardTarget)) = issued(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.GuardFloor(owner(run), H.guard(), oldGuardValue), guardFloorDestination, t))

    // ── handoff / assess ───────────────────────────────────────────────────────────────────────────────────────────────
    private val gFloor = DestinationLocator.Guard("g", GuardPart.FLOOR)
    private val gAuth = DestinationLocator.Guard("g", GuardPart.AUTH)
    private val rPayload = DestinationLocator.Payload(ControlKind.DEMAND, "r")
    private fun chain(d: DestinationLocator, links: List<NamedTransferLink>, token: PriorStorageConfirmation?) =
        HandoffDisposition.DurablyOwned(d, links, token)
    private fun named(link: NamedTransferLink, d: DestinationLocator = link.destination.locator) = chain(d, listOf(link), link.confirmation)
    private fun kept(token: PriorStorageConfirmation, d: DestinationLocator) = chain(d, emptyList(), token)
    /** Every required slot in A1 order: the given disposition, else CompletedAndConsumed of its own subject. */
    private fun slots(run: Run, given: Map<RequiredObligationKey, HandoffDisposition>) = run.required.map {
        SlotHandoff(it.key, given[it.key] ?: HandoffDisposition.CompletedAndConsumed(ComponentCompletion(it.key.subject), emptyList()))
    }
    private fun assess(run: Run, given: Map<RequiredObligationKey, HandoffDisposition>,
        latest: ControlRecordRead = run.confirmed.snapshot.record) = assessG05(run.c, run.a1,
        CompletionHandoff(run.a1.commandBinding, owner(run), slots(run, given)), TerminationClosures.of(run.c), latest, now)
    private fun accepted(r: G05Result) = assertEquals(G05Result.Accepted, r)
    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(expected, (r as G05Result.Rejected).failures)
    }
    private fun fixed(run: Run, k: RequiredObligationKey) =
        G05Location.Fixed((run.slot(k).requirement as SlotRequirement.Required).fixedSources.first().location)
    private fun f(id: G05Id, run: Run, k: RequiredObligationKey, actual: G05Location? = null) =
        G05Failure(id, k, fixed(run, k), G05Location.Submitted(run.at(k)), actual)
    private fun rowAt(latest: ControlRecordRead.Supported, id: String): G05Location.ActualPayload {
        val (kind, entry) = latest.locations(id).single()
        return G05Location.ActualPayload(kind, latest.arrays.getValue(kind).entries.indexOfFirst { it === entry })
    }
    private fun rewritten(latest: ControlRecordRead.Supported, id: String, change: (ControlNode) -> ControlNode) =
        H.read(H.changeRow(latest.original, ControlKind.DEMAND, id, change))

    // ── per-transition keys ────────────────────────────────────────────────────────────────────────────────────────────
    private fun reqL(run: Run) = run.key(ObligationComponent.REQUEST, L)
    private fun reqN(run: Run) = run.key(ObligationComponent.REQUEST, N)
    private fun isGuardFloor(s: ObligationSubject) = (s as? ObligationSubject.Floor)?.sourceKind == ControlKind.DEMAND
    private fun isHoldFloor(s: ObligationSubject) = (s as? ObligationSubject.Floor)?.sourceKind == ControlKind.HOLD
    private fun gfL(run: Run) = run.key(ObligationComponent.FLOOR, L, ::isGuardFloor)
    private fun gfN(run: Run) = run.key(ObligationComponent.FLOOR, N, ::isGuardFloor)
    private fun hfL(run: Run) = run.key(ObligationComponent.FLOOR, L, ::isHoldFloor)
    private fun hfN(run: Run) = run.key(ObligationComponent.FLOOR, N, ::isHoldFloor)
    private fun authL(run: Run) = run.key(ObligationComponent.AUTH, L)
    private fun authN(run: Run) = run.key(ObligationComponent.AUTH, N)

    private fun rebindGiven(run: Run, link: NamedTransferLink = rebindLink(run)) =
        mapOf(reqL(run) to named(link), reqN(run) to named(link))
    private fun endGiven(run: Run, link: NamedTransferLink = endLink(run)) = mapOf(
        reqL(run) to named(link), reqN(run) to named(link),
        gfL(run) to kept(retained(run, gfL(run), gFloor), gFloor), gfN(run) to kept(retained(run, gfN(run), gFloor), gFloor),
        authL(run) to kept(token(run, guardTarget), gAuth))
    private fun recoverGiven(run: Run): Map<RequiredObligationKey, HandoffDisposition> {
        val hold = holdLink(run); val g = guardLink(run)
        val request = DestinationLocator.Payload(ControlKind.DEMAND, newRequestId(run))
        return mapOf(hfL(run) to named(hold), hfN(run) to named(hold), gfL(run) to named(g), gfN(run) to named(g),
            authL(run) to kept(retained(run, authL(run), gAuth), gAuth), authN(run) to kept(retained(run, authN(run), gAuth), gAuth),
            reqL(run) to kept(retained(run, reqL(run), request), request), reqN(run) to kept(retained(run, reqN(run), request), request))
    }

    // ═══ positives ═════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P01_rebind_requestLN_throughTheIssuedLink() = runReleaseTest {
        val run = rebind()
        assertEquals("fixture: BINDING L/N, REQUEST L/N, RECEIPT L", listOf(ObligationComponent.BINDING, ObligationComponent.BINDING,
            ObligationComponent.REQUEST, ObligationComponent.REQUEST, ObligationComponent.RECEIPT), run.required.map { it.key.component })
        accepted(assess(run, rebindGiven(run)))
    }

    @Test fun P02_end_requestLinks_retainedGuardFloor_authLThroughTheGuardOutput() = runReleaseTest {
        val run = end()
        accepted(assess(run, endGiven(run)))
    }

    @Test fun P03_recoverHold_afterPurgeCompletion_namedFloors_retainedAuthAndRequest() = runReleaseTest {
        val run = recoverHold()
        accepted(assess(run, recoverGiven(run), afterPurge(run)))
    }

    // ═══ REQUEST chains ════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun N01_rebind_nPriorWriteNull_linkKept_confirmationOnly() = runReleaseTest {
        val run = rebind(); val link = rebindLink(run)
        rejected(assess(run, rebindGiven(run, link) + (reqN(run) to chain(rPayload, listOf(link), null))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run))))
    }

    @Test fun N02_rebind_noLinkAtAll_L_confirmation_N_alsoLowerBoundAtRow() = runReleaseTest {
        val run = rebind(); val latest = run.confirmed.snapshot.record
        rejected(assess(run, mapOf(reqL(run) to chain(rPayload, emptyList(), null), reqN(run) to chain(rPayload, emptyList(), null))),
            listOf(f(G05Id.CONFIRMATION, run, reqL(run)), f(G05Id.CONFIRMATION, run, reqN(run)),
                f(G05Id.LOWER_BOUND, run, reqN(run), rowAt(latest, "r"))))
    }

    /** Order 23 still meets L (≥ 22) but not the named N bound (the output order exactly); both lose the confirmation. */
    @Test fun N03_rebind_latestOrderRaisedAfterTheLink_confirmationAtRow_NAlsoLowerBound() = runReleaseTest {
        val run = rebind()
        val latest = rewritten(run.confirmed.snapshot.record, "r") { FloorGuardFixtures.field(it, "raisedAt", JsonPrimitive(23)) }
        val a = rowAt(latest, "r")
        rejected(assess(run, rebindGiven(run), latest), listOf(f(G05Id.CONFIRMATION, run, reqL(run), a),
            f(G05Id.CONFIRMATION, run, reqN(run), a), f(G05Id.LOWER_BOUND, run, reqN(run), a)))
    }

    @Test fun N04_rebind_chainWithARetainedToken_isConfirmation() = runReleaseTest {
        val run = rebind(); val link = rebindLink(run)
        val other = noOpRequestToken()
        rejected(assess(run, rebindGiven(run, link) + (reqN(run) to chain(rPayload, listOf(link), other))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run))))
    }
    private suspend fun noOpRequestToken(): PriorStorageConfirmation {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request)) {}
            as ControlMutation.Edit
        val after = (e.changed as ControlWriteResult.Written).node
        val ref = CommandRef("cmd-noop", listOf(e), OwnerTrackingLifetimeId.issue())
        val a1 = deriveRequiredObligations(RequirementInput.Mutations(ref, ref.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(listOf(ControlCommandTarget("d", after, false))))) as RequirementDerivation.Available
        val s = open()
        controlTestTimeout("seed noop") { s.data.updateData { F.raw(ControlObligationFixtures.node(ControlObligationFixtures.request)) } }
        val read = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> com.jay.fxi.data.entitlements.RecordTransactionDecision.Observe(
                ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        val slot = a1.orderedSlots.first { it.requirement is SlotRequirement.Required && it.key.branch == N }
        val r = PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.DEMAND, "d"), read)
        assertTrue("fixture: retained issuer must issue, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value
    }

    /** A second token for the same output is an equal-looking but different object: identity, not equality, is the binding. */
    @Test fun N05_rebind_priorWriteIsAnotherIssuedToken_notTheLinksOwn() = runReleaseTest {
        val run = rebind(); val link = rebindLink(run)
        rejected(assess(run, rebindGiven(run, link) + (reqN(run) to chain(rPayload, listOf(link), token(run, requestTarget)))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run))))
    }

    @Test fun N06_rebind_linkOwnerKeyDiffers_ownerAndConfirmation() = runReleaseTest {
        val run = rebind()
        val stranger = requestLink(run, oldRebind, oldValue(Long.MAX_VALUE), key = "owner-2")
        rejected(assess(run, rebindGiven(run) + (reqN(run) to named(stranger))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run)), f(G05Id.OWNER, run, reqN(run))))
    }

    /** A link from another REBIND whose first source is a different before row: no named bound, general comparator rejects. */
    @Test fun N07_rebind_linkOfAnotherCommand_firstSourceNotTheFixedBefore() = runReleaseTest {
        val run = rebind()
        val otherOld = F.request(binding = 2, origin = LifetimeId("old"), order = 7)
        val other = rebind(old = otherOld)
        val foreign = requestLink(other, otherOld, oldValue(7))
        rejected(assess(run, rebindGiven(run) + (reqN(run) to named(foreign))), listOf(f(G05Id.CONFIRMATION, run, reqN(run)),
            f(G05Id.LOWER_BOUND, run, reqN(run), rowAt(run.confirmed.snapshot.record, "r"))))
    }

    @Test fun N08_end_requestNPriorWriteNull_confirmationOnly() = runReleaseTest {
        val run = end(); val link = endLink(run)
        rejected(assess(run, endGiven(run, link) + (reqN(run) to chain(rPayload, listOf(link), null))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run))))
    }

    // ═══ floor chains ══════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun N09_recover_holdFloorLPriorWriteNull_linkKept_confirmationOnly() = runReleaseTest {
        val run = recoverHold(); val given = recoverGiven(run)
        val link = (given.getValue(hfL(run)) as HandoffDisposition.DurablyOwned).linkChain.single()
        rejected(assess(run, given + (hfL(run) to chain(gFloor, listOf(link), null)), afterPurge(run)),
            listOf(f(G05Id.CONFIRMATION, run, hfL(run))))
    }

    /** Without a link the HOLD FLOOR slot may not name the guard: the guard row found is not the HOLD destination. */
    @Test fun N10_recover_holdFloorLNoLink_guardDestination_destinationAtRowAndConfirmation() = runReleaseTest {
        val run = recoverHold(); val latest = afterPurge(run)
        rejected(assess(run, recoverGiven(run) + (hfL(run) to chain(gFloor, emptyList(), null)), latest),
            listOf(f(G05Id.DESTINATION, run, hfL(run), rowAt(latest, "g")), f(G05Id.CONFIRMATION, run, hfL(run))))
    }

    /** N7: the second link's GuardFloor source (the old guard) is not the first link's output (the new guard). */
    @Test fun N11a_recover_guardFloorChainDiscontinuous_confirmation() = runReleaseTest {
        val run = recoverHold(); val g = guardLink(run)
        rejected(assess(run, recoverGiven(run) + (gfN(run) to chain(gFloor, listOf(g, g), g.confirmation)), afterPurge(run)),
            listOf(f(G05Id.CONFIRMATION, run, gfN(run))))
    }

    /** A HoldFloor source after the first link breaks continuity; the HOLD FLOOR slot falls back to the HOLD locator. */
    @Test fun N11b_recover_holdFloorChainDiscontinuous_destinationAndConfirmation() = runReleaseTest {
        val run = recoverHold(); val hold = holdLink(run); val latest = afterPurge(run)
        rejected(assess(run, recoverGiven(run) + (hfN(run) to chain(gFloor, listOf(hold, hold), hold.confirmation)), latest),
            listOf(f(G05Id.DESTINATION, run, hfN(run), rowAt(latest, "g")), f(G05Id.CONFIRMATION, run, hfN(run))))
    }

    /**
     * The guard's floor wait shortened after landing: every token on the guard row loses its confirmation at the row; the
     * transferred HOLD FLOOR (against the confirmed output floor) and the guard FLOOR (captured after) lose their bound.
     */
    @Test fun N12_recover_latestGuardFloorShortened_confirmationAndLowerBoundAtRow() = runReleaseTest {
        val run = recoverHold()
        val latest = rewritten(afterPurge(run), "g") { H.field(it, "floor", FloorGuardFixtures.floor(28000, "boot", 11000, "new-life")) }
        val a = rowAt(latest, "g")
        rejected(assess(run, recoverGiven(run), latest), listOf(
            f(G05Id.CONFIRMATION, run, hfL(run), a), f(G05Id.CONFIRMATION, run, hfN(run), a),
            f(G05Id.CONFIRMATION, run, gfL(run), a), f(G05Id.CONFIRMATION, run, gfN(run), a),
            f(G05Id.CONFIRMATION, run, authL(run), a), f(G05Id.CONFIRMATION, run, authN(run), a),
            f(G05Id.LOWER_BOUND, run, hfL(run), a), f(G05Id.LOWER_BOUND, run, hfN(run), a),
            f(G05Id.LOWER_BOUND, run, gfL(run), a), f(G05Id.LOWER_BOUND, run, gfN(run), a)))
    }

    /** The landing snapshot still carries the journal: declaring JOURNAL / NAMESPACE_RETIREMENT complete conflicts with it. */
    @Test fun N13_recover_landingSnapshotAsLatest_journalCompletionsConflict() = runReleaseTest {
        val run = recoverHold(); val latest = run.confirmed.snapshot.record
        val journal = G05Location.ActualJournal(0)
        val keys = listOf(run.key(ObligationComponent.JOURNAL, L), run.key(ObligationComponent.JOURNAL, N),
            run.key(ObligationComponent.NAMESPACE_RETIREMENT, L), run.key(ObligationComponent.NAMESPACE_RETIREMENT, N))
        rejected(assess(run, recoverGiven(run), latest), keys.map { f(G05Id.COMPLETED_CONFLICT, run, it, journal) })
    }

    // ═══ N8: END guard AUTH output ═════════════════════════════════════════════════════════════════════════════════════
    private fun fixedAfter(run: Run, target: LifecycleTarget) = checkNotNull(run.fixed.targets.single { it.target == target }.after)

    @Test fun E01_endGuardReplace_issuesGuardAuthOutput_ofTheFixedAfterRow() = runReleaseTest {
        val run = end()
        val binding = token(run, guardTarget).binding as ConfirmationBinding.LifecycleOutput
        assertEquals(guardTarget, binding.outputTarget)
        val out = binding.output
        assertTrue("expected GuardAuth, got $out", out is TypedDestinationTuple.GuardAuth)
        out as TypedDestinationTuple.GuardAuth
        assertEquals(gAuth, out.locator)
        assertEquals(fixedAfter(run, guardTarget).toPayloadEntry(), out.row.toPayloadEntry())
        assertEquals(guard(fixedAfter(run, guardTarget)), out.parsed)
        assertEquals((run.slot(authL(run)).requirement as SlotRequirement.Required).lowerBound,
            RequiredLowerBound.Auth(checkNotNull(out.parsed.auth)))
    }

    @Test fun E02_guardAuthDestination_isNeverANamedLink() = runReleaseTest {
        val run = end(); val t = token(run, guardTarget)
        val out = (t.binding as ConfirmationBinding.LifecycleOutput).output
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)),
            NamedTransferLink.linkNamedTransfer(TypedSourceTuple.Request(owner(run), oldEnd, oldValue(4), requestN(run).minimumIntent,
                requestN(run).minimumOrder), out, t))
    }

    @Test fun E03_endWithoutReplacement_guardWithoutAuth_isSnapshotOutputMismatch() = runReleaseTest {
        val run = end(replacement = null)
        assertTrue("fixture: END without replacement still replaces guard g", run.fixed.targets.any { it.target == guardTarget })
        assertEquals("fixture: the AFTER guard carries no AUTH", null, guard(fixedAfter(run, guardTarget))?.auth)
        assertEquals(LifecycleOutputConfirmationResult.Rejected(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH),
            PriorStorageConfirmation.confirmLifecycleOutput(run.c, run.fixed, run.confirmed, guardTarget))
    }

    /** Only the guard's floor wait grows after landing: every token on the guard row loses its confirmation; no bound fails. */
    @Test fun N20_end_latestGuardFloorLengthened_confirmationAtRowOnly() = runReleaseTest {
        val run = end()
        val latest = rewritten(run.confirmed.snapshot.record, "g") { H.field(it, "floor", FloorGuardFixtures.floor(91000)) }
        assertEquals("fixture: only the wait changed", 91000L, guard(H.row(latest.original, ControlKind.DEMAND, "g"))?.floor?.waitMillis)
        val a = rowAt(latest, "g")
        rejected(assess(run, endGiven(run), latest), listOf(f(G05Id.CONFIRMATION, run, gfL(run), a),
            f(G05Id.CONFIRMATION, run, gfN(run), a), f(G05Id.CONFIRMATION, run, authL(run), a)))
    }

    @Test fun N14_end_authLWithTheRequestOutputToken_confirmation() = runReleaseTest {
        val run = end()
        rejected(assess(run, endGiven(run) + (authL(run) to kept(token(run, requestTarget), gAuth))),
            listOf(f(G05Id.CONFIRMATION, run, authL(run))))
    }

    /** AUTH N's bound is the old AUTH: not eligible, and the guard now carries another scope — destination at the row. */
    @Test fun N15_end_authNWithTheGuardOutputToken_destinationAtRowAndConfirmation() = runReleaseTest {
        val run = end()
        rejected(assess(run, endGiven(run) + (authN(run) to kept(token(run, guardTarget), gAuth))),
            listOf(f(G05Id.DESTINATION, run, authN(run), rowAt(run.confirmed.snapshot.record, "g")), f(G05Id.CONFIRMATION, run, authN(run))))
    }

    @Test fun N16_end_authLWithTheGuardOutputTokenOfAnotherEnd_confirmation() = runReleaseTest {
        val run = end(); val other = end()
        rejected(assess(run, endGiven(run) + (authL(run) to kept(token(other, guardTarget), gAuth))),
            listOf(f(G05Id.CONFIRMATION, run, authL(run))))
    }

    @Test fun N17_rebind_requestLEmptyChainWithItsOutputToken_confirmation() = runReleaseTest {
        val run = rebind()
        rejected(assess(run, rebindGiven(run) + (reqL(run) to kept(token(run, requestTarget), rPayload))),
            listOf(f(G05Id.CONFIRMATION, run, reqL(run))))
    }

    // ═══ N9: exact command and REQUEST continuity ═════════════════════════════════════════════════════════════════════
    /** Same fixed before, same shape: only the link token's command differs, so the chain is not structurally valid. */
    @Test fun N18_rebind_sameSourceLinkOfAnotherCommand_confirmationAndGeneralLowerBound() = runReleaseTest {
        val run = rebind(); val other = rebind(orderStart = 30)
        val foreign = requestLink(other, oldRebind, oldValue(Long.MAX_VALUE), order = 31)
        rejected(assess(run, rebindGiven(run) + (reqN(run) to named(foreign))), listOf(f(G05Id.CONFIRMATION, run, reqN(run)),
            f(G05Id.LOWER_BOUND, run, reqN(run), rowAt(run.confirmed.snapshot.record, "r"))))
    }

    /** The second link's preimage (the old row) is not the first link's output (the rebound row). */
    @Test fun N19_rebind_requestChainDiscontinuous_confirmationAndGeneralLowerBound() = runReleaseTest {
        val run = rebind(); val link = rebindLink(run)
        rejected(assess(run, rebindGiven(run, link) + (reqN(run) to chain(rPayload, listOf(link, link), link.confirmation))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run)), f(G05Id.LOWER_BOUND, run, reqN(run), rowAt(run.confirmed.snapshot.record, "r"))))
    }

    // ═══ measurement r1 additions (measure-6-4bA3b2b/sensitivity.r1.json survivors that are contract gaps) ═══════════════
    @Test fun E04_guardAuthDestination_withARetainedToken_isStillUnsupported() = runReleaseTest {
        val run = end()
        val out = (token(run, guardTarget).binding as ConfirmationBinding.LifecycleOutput).output
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)),
            NamedTransferLink.linkNamedTransfer(TypedSourceTuple.Request(owner(run), oldEnd, oldValue(4), requestN(run).minimumIntent,
                requestN(run).minimumOrder), out, retained(run, gfL(run), gFloor)))
    }

    /** The guard FLOOR slot also lists the HOLD row among its fixed sources: a HoldFloor link still does not bind to it. */
    @Test fun N21_recover_holdLinkOnTheGuardFloorSlot_confirmation() = runReleaseTest {
        val run = recoverHold()
        rejected(assess(run, recoverGiven(run) + (gfN(run) to named(holdLink(run))), afterPurge(run)),
            listOf(f(G05Id.CONFIRMATION, run, gfN(run))))
    }

    @Test fun N21b_recover_guardLinkOnTheHoldFloorSlot_destinationAtRowAndConfirmation() = runReleaseTest {
        val run = recoverHold(); val latest = afterPurge(run)
        rejected(assess(run, recoverGiven(run) + (hfN(run) to named(guardLink(run))), latest),
            listOf(f(G05Id.DESTINATION, run, hfN(run), rowAt(latest, "g")), f(G05Id.CONFIRMATION, run, hfN(run))))
    }

    /** Two rebound REQUESTs: the link of r2 does not bind to r's slot, although it is the exact command's own link. */
    @Test fun N22_rebindTwoRequests_linkOfTheOtherRequest_onThisSlot_destinationAndConfirmation() = runReleaseTest {
        val s = open()
        val old2 = F.request(id = "r2", binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind two") { s.data.updateData { F.raw(oldRebind, old2, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareRebindRequests(listOf(oldRebind, old2), F.binding, LifecycleOrderSource(F.life, 21))
        val run = Run(s, c, execute(s, c, F.context(F.runtime())))
        fun isId(id: String) = { subject: ObligationSubject -> (subject as ObligationSubject.Request).id == id }
        fun linkOf(id: String, old: ControlNode): NamedTransferLink {
            val target = LifecycleTarget(ControlKind.DEMAND, id, LifecycleEffect.REPLACE)
            val bound = (run.slot(run.key(ObligationComponent.REQUEST, N, isId(id))).requirement as SlotRequirement.Required)
                .lowerBound as RequiredLowerBound.Request
            val after = fixedAfter(run, target)
            return issued(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.Request(owner(run), old, checkNotNull(demand(old)),
                bound.minimumIntent, bound.minimumOrder), TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, id),
                after, checkNotNull(demand(after))), token(run, target)))
        }
        val r = linkOf("r", oldRebind); val r2 = linkOf("r2", old2)
        val rN = run.key(ObligationComponent.REQUEST, N, isId("r"))
        val given = mapOf(run.key(ObligationComponent.REQUEST, L, isId("r")) to named(r), rN to named(r2),
            run.key(ObligationComponent.REQUEST, L, isId("r2")) to named(r2), run.key(ObligationComponent.REQUEST, N, isId("r2")) to named(r2))
        rejected(assess(run, given), listOf(f(G05Id.DESTINATION, run, rN, rowAt(run.confirmed.snapshot.record, "r2")),
            f(G05Id.CONFIRMATION, run, rN)))
    }

    @Test fun N23_rebind_submittedLocatorIsNotTheLinksDestination_destinationAndConfirmation() = runReleaseTest {
        val run = rebind(); val link = rebindLink(run)
        rejected(assess(run, rebindGiven(run, link) + (reqN(run) to named(link, DestinationLocator.Payload(ControlKind.DEMAND, "dormant")))),
            listOf(f(G05Id.DESTINATION, run, reqN(run), rowAt(run.confirmed.snapshot.record, "dormant")), f(G05Id.CONFIRMATION, run, reqN(run))))
    }

    @Test fun N24_rebind_latestHasTwoRowsWithTheOutputsId_destinationAndConfirmationWithoutPosition() = runReleaseTest {
        val run = rebind(); val landed = run.confirmed.snapshot.record
        val rows = landed.arrays.getValue(ControlKind.DEMAND).entries.flatMap {
            val e = it as ControlEntryRead.Interpreted
            if (e.value.id == "r") listOf(e.original, e.original) else listOf(e.original)
        }
        val latest = H.read(landed.original.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)] = H.payload(rows) }.toPreferences())
        rejected(assess(run, rebindGiven(run), latest), listOf(f(G05Id.DESTINATION, run, reqL(run)), f(G05Id.DESTINATION, run, reqN(run)),
            f(G05Id.CONFIRMATION, run, reqL(run)), f(G05Id.CONFIRMATION, run, reqN(run))))
    }

    @Test fun N25_end_authLGuardOutputToken_submittedAtTheFloorLocator_destinationAndConfirmation() = runReleaseTest {
        val run = end()
        rejected(assess(run, endGiven(run) + (authL(run) to kept(token(run, guardTarget), gFloor))),
            listOf(f(G05Id.DESTINATION, run, authL(run), rowAt(run.confirmed.snapshot.record, "g")), f(G05Id.CONFIRMATION, run, authL(run))))
    }

    @Test fun N26_rebind_requestLinkOnTheReceiptSlot_destinationConfirmationAndCompletedConflict() = runReleaseTest {
        val run = rebind(); val receipt = run.key(ObligationComponent.RECEIPT, L)
        rejected(assess(run, rebindGiven(run) + (receipt to named(rebindLink(run)))), listOf(
            f(G05Id.DESTINATION, run, receipt, rowAt(run.confirmed.snapshot.record, "r")), f(G05Id.CONFIRMATION, run, receipt),
            f(G05Id.COMPLETED_CONFLICT, run, receipt)))
    }

    /** The guard FLOOR L slot shares END's GUARD role and L branch with AUTH L, but the N8 path is AUTH only. */
    @Test fun N27_end_guardFloorLWithTheGuardOutputTokenAtTheAuthLocator_destinationAndConfirmation() = runReleaseTest {
        val run = end()
        rejected(assess(run, endGiven(run) + (gfL(run) to kept(token(run, guardTarget), gAuth))),
            listOf(f(G05Id.DESTINATION, run, gfL(run), rowAt(run.confirmed.snapshot.record, "g")), f(G05Id.CONFIRMATION, run, gfL(run))))
    }

    /**
     * An old guard floor that outlasts the HOLD floor: the exact max output (anchor 11000, wait 49000) exceeds the HOLD's
     * remaining time, so a guard shortened to wait 40000 still covers the HOLD floor but not the transferred output floor.
     */
    @Test fun N28_recover_outputFloorAboveTheHoldFloor_shortenedGuard_holdFloorLowerBoundAgainstTheOutput() = runReleaseTest {
        val longGuard = H.guard(wait = 50000)
        val s = open()
        val base = H.input(g = longGuard)
        val input = RecoverHoldInput(base.source, base.guard, base.before, base.binding, base.closure, H.now)
        controlTestTimeout("seed long guard") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        val run = Run(s, c, execute(s, c, H.context(input)))
        val after = fixedAfter(run, guardTarget)
        assertEquals("fixture: exact max output", FloorV1("boot", 11000, 49000, LifetimeId("new-life")), guard(after)?.floor)
        val out = TypedDestinationTuple.GuardFloor(gFloor, after, checkNotNull(guard(after)))
        val hold = issued(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.HoldFloor(owner(run), FloorSource("h", H.hold(), parsedHold(),
            FloorV1("boot", 10000, 30000, LifetimeId("life")), longGuard), longGuard, H.now, H.life), out, token(run, guardTarget)))
        val g = issued(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.GuardFloor(owner(run), longGuard, checkNotNull(guard(longGuard))),
            out, token(run, guardTarget)))
        val request = DestinationLocator.Payload(ControlKind.DEMAND, newRequestId(run))
        val given = mapOf(hfL(run) to named(hold), hfN(run) to named(hold), gfL(run) to named(g), gfN(run) to named(g),
            authL(run) to kept(retained(run, authL(run), gAuth), gAuth), authN(run) to kept(retained(run, authN(run), gAuth), gAuth),
            reqL(run) to kept(retained(run, reqL(run), request), request), reqN(run) to kept(retained(run, reqN(run), request), request))
        val latest = rewritten(afterPurge(run), "g") { H.field(it, "floor", FloorGuardFixtures.floor(40000, "boot", 11000, "new-life")) }
        val a = rowAt(latest, "g")
        rejected(assess(run, given, latest), listOf(
            f(G05Id.CONFIRMATION, run, hfL(run), a), f(G05Id.CONFIRMATION, run, hfN(run), a),
            f(G05Id.CONFIRMATION, run, gfL(run), a), f(G05Id.CONFIRMATION, run, gfN(run), a),
            f(G05Id.CONFIRMATION, run, authL(run), a), f(G05Id.CONFIRMATION, run, authN(run), a),
            f(G05Id.LOWER_BOUND, run, hfL(run), a), f(G05Id.LOWER_BOUND, run, hfN(run), a),
            f(G05Id.LOWER_BOUND, run, gfL(run), a), f(G05Id.LOWER_BOUND, run, gfN(run), a)))
    }

    /**
     * A second REBIND whose before is the first REBIND's output continues the chain row-for-row, but its token belongs to
     * another command: N9 (b) checks every link, so the chain is not structurally valid — no OWNER, general lower bound.
     */
    @Test fun N29_rebind_continuedByAnotherCommandsLink_confirmationAndGeneralLowerBound_noOwner() = runReleaseTest {
        val run = rebind(); val first = rebindLink(run)
        val s = open()
        controlTestTimeout("seed second rebind") { s.data.updateData { F.raw(rebound(), F.request(id = "dormant", owner = "B")) } }
        val binding2 = LifecycleBinding(SettlementExecutor("A", 4, LifetimeId("life2")), F.identity, 1, "binding-start")
        val c = s.control.prepareRebindRequests(listOf(rebound()), binding2, LifecycleOrderSource(LifetimeId("life2"), 21))
        val second = Run(s, c, execute(s, c, F.context(F.runtime(binding = binding2))))
        val after = fixedAfter(second, requestTarget)
        val bound2 = requestN(second)
        val link2 = issued(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.Request(owner(second), rebound(), reboundValue(),
            bound2.minimumIntent, bound2.minimumOrder), TypedDestinationTuple.Request(rPayload, after, checkNotNull(demand(after))),
            token(second, requestTarget)))
        assertEquals("fixture: the chain continues row-for-row", first.destination.row.toPayloadEntry(),
            (link2.source as TypedSourceTuple.Request).preimage.toPayloadEntry())
        rejected(assess(run, rebindGiven(run, first) + (reqN(run) to chain(rPayload, listOf(first, link2), link2.confirmation))),
            listOf(f(G05Id.CONFIRMATION, run, reqN(run)), f(G05Id.LOWER_BOUND, run, reqN(run), rowAt(run.confirmed.snapshot.record, "r"))))
    }
}
