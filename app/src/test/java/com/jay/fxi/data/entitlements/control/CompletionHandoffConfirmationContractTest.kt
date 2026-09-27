package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bA3b2a contract: G05 accepting DurablyOwned through an empty chain with a retained-source confirmation,
 * and the allowed-disposition violation of a completion-only slot (T3 × T4). Fixed inputs: 6-4bA3 declaration r2 §2/§4,
 * 6-4bA3 consensus r3 (N1–N7; N3 C-only), 6-4bA3b consensus, 6-4bA3b2 recipes r1 (6-4bA3b2_recipes_codex.r1.md §1–§3).
 * The A2 fill table applies: F(k) = Fixed(first fixedSources location of the A1 slot), S(i) = Submitted(i), A = the actual row.
 *
 * Decisions this contract fixes (flagged for review):
 *  - An empty chain is accepted only with a RetainedSource token whose slot equals the submitted key's A1 slot (whole value),
 *    whose observed locator equals the submitted destination, and whose observation is still true in latest (for Payload/Guard, the observed raw row equals latest's unique
 *    interpreted row; for Journal, the observed canonical key is present in latest); then
 *    the original-source lower bound applies unchanged. Any other token/chain combination records CONFIRMATION.
 *  - A token that is fine but latest was rewritten afterwards: CONFIRMATION with actualAt = the latest row (A); a token or
 *    chain that is itself unfit: CONFIRMATION with actualAt = null.
 *  - N3: a DurablyOwned on a COMPLETED_AND_CONSUMED_ONLY slot is COMPLETED_CONFLICT (expectedAt F(k), submittedAt S(i),
 *    actualAt null) whether or not its confirmation is valid — the canonical completedConflict read as the common id of an
 *    allowed-disposition violation.
 *  - Fixtures use no-op Edits (the obligation is exactly the stored original row) seeded into a real FileStorage and observed
 *    through the owner's normal locked read, so no other obligation interferes.
 * Named chains are A3b2b. The implementation thread reads but does not edit this file.
 */
class CompletionHandoffConfirmationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3b2a cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)

    // ── case: exact command + A1 derivation ────────────────────────────────────────────────────────────────────────────
    private class Case(val ref: CommandRef, input: RequirementInput) {
        val a1 = deriveRequiredObligations(input).let {
            assertTrue("fixture: A1 must be Available, got $it", it is RequirementDerivation.Available)
            it as RequirementDerivation.Available
        }
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun slot(k: RequiredObligationKey) = a1.orderedSlots.single { it.key == k }
        fun at(k: RequiredObligationKey) = required.indexOfFirst { it.key == k }.also { check(it >= 0) }
    }
    private fun noOpEdit(kind: ControlKind, row: String): Case {
        val e = ControlMutation.Edit.prepare(kind, ControlObligationFixtures.node(row)) {} as ControlMutation.Edit
        val after = (e.changed as ControlWriteResult.Written).node
        val id = (ControlObligations.read(kind, after) as ControlEntryRead.Interpreted).value.id
        val ref = CommandRef("cmd-${kind.name}", listOf(e), OwnerTrackingLifetimeId.issue())
        return Case(ref, RequirementInput.Mutations(ref, ref.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(listOf(ControlCommandTarget(id, after, false)))))
    }

    // ── store and reads ────────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun seeded(raw: Preferences) = open().also { s -> controlTestTimeout("seed") { s.data.updateData { raw } } }
    private suspend fun lockedRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> {
        val r = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        assertEquals(RecordTransactionEvidence.LockedFileRead, r.evidence)
        return r
    }
    private fun record(kind: ControlKind, vararg rows: ControlNode): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[ControlRecordKeys.payload(kind)] = rows.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    }.toPreferences()
    private fun retained(c: Case, k: RequiredObligationKey, d: DestinationLocator, read: RecordTransactionResult<ControlRecordRead.Supported>): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmRetainedSource(c.slot(k), d, read)
        assertTrue("fixture: retained issuer must issue for $k, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value
    }

    // ── handoff / assess ───────────────────────────────────────────────────────────────────────────────────────────────
    private fun owned(k: RequiredObligationKey, d: DestinationLocator, token: PriorStorageConfirmation?) =
        SlotHandoff(k, HandoffDisposition.DurablyOwned(d, emptyList(), token))
    private fun completed(k: RequiredObligationKey) = SlotHandoff(k, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(k.subject), emptyList()))
    private fun handoff(c: Case, slots: List<SlotHandoff>) =
        CompletionHandoff(c.a1.commandBinding, ResponsibilityOwner(c.ref.ownerTrackingLifetimeId, "owner-1"), slots)
    private fun assess(c: Case, slots: List<SlotHandoff>, latest: ControlRecordRead) =
        assessG05(c.ref, c.a1, handoff(c, slots), TerminationClosures.of(c.ref), latest, now)
    private fun accepted(r: G05Result) = assertEquals(G05Result.Accepted, r)
    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(expected, (r as G05Result.Rejected).failures)
    }
    private fun fixed(c: Case, k: RequiredObligationKey) =
        G05Location.Fixed((c.slot(k).requirement as SlotRequirement.Required).fixedSources.first().location)
    private fun f(id: G05Id, c: Case, k: RequiredObligationKey, actual: G05Location? = null) =
        G05Failure(id, k, fixed(c, k), G05Location.Submitted(c.at(k)), actual)

    // ═══ empty chain: positives ════════════════════════════════════════════════════════════════════════════════════════
    /** A no-op Edit whose every required slot is the stored original row: each slot gets its own retained token. */
    private suspend fun allRetained(kind: ControlKind, row: String, id: String) {
        val c = noOpEdit(kind, row)
        val read = lockedRead(seeded(record(kind, ControlObligationFixtures.node(row))))
        val d = DestinationLocator.Payload(kind, id)
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }
        accepted(assess(c, slots, read.value))
    }

    @Test fun P01_hold_sourceAndFloor_allFourSlotsRetained() = runReleaseTest {
        val c = noOpEdit(ControlKind.HOLD, ControlObligationFixtures.hold)
        assertEquals("fixture: SOURCE L/N + FLOOR L/N", listOf(ObligationComponent.SOURCE, ObligationComponent.SOURCE,
            ObligationComponent.FLOOR, ObligationComponent.FLOOR), c.required.map { it.key.component })
        allRetained(ControlKind.HOLD, ControlObligationFixtures.hold, "h")
    }

    @Test fun P02_intent_retained() = runReleaseTest { allRetained(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery, "r") }

    @Test fun P03_activeSeal_retained() = runReleaseTest { allRetained(ControlKind.SEAL, ControlObligationFixtures.seal, "s") }

    @Test fun P04_request_retained() = runReleaseTest { allRetained(ControlKind.DEMAND, ControlObligationFixtures.request, "d") }

    // ═══ empty chain: negatives ════════════════════════════════════════════════════════════════════════════════════════
    @Test fun N01_tokenOfAnotherSlot_isConfirmationWithNullActual() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request))))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val (qL, qN) = c.required.map { it.key }
        val slots = listOf(owned(qL, d, retained(c, qL, d, read)), owned(qN, d, retained(c, qL, d, read)))
        rejected(assess(c, slots, read.value), listOf(f(G05Id.CONFIRMATION, c, qN)))
    }

    @Test fun N02_latestRewrittenAfterTheToken_confirmationAtRow_andLowerBound() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val original = ControlObligationFixtures.node(ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, original)))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }
        val older = FloorGuardFixtures.field(original, "raisedAt", JsonPrimitive(1))
        val latest = lockedRead(seeded(record(ControlKind.DEMAND, older))).value
        assertTrue("fixture: interpretable", !latest.hasUninterpretable)
        val at = G05Location.ActualPayload(ControlKind.DEMAND, 0)
        val (qL, qN) = c.required.map { it.key }
        rejected(assess(c, slots, latest), listOf(f(G05Id.CONFIRMATION, c, qL, at), f(G05Id.CONFIRMATION, c, qN, at),
            f(G05Id.LOWER_BOUND, c, qL, at), f(G05Id.LOWER_BOUND, c, qN, at)))
    }

    @Test fun N03_emptyChainWithALifecycleOutputToken_isConfirmation() = runReleaseTest {
        // A real LifecycleOutput token from a real REBIND_REQUESTS Confirmed (A3a recipe).
        val F = DemandAuthFixtures
        val s = open()
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(r, F.request(id = "dormant", owner = "B")) } }
        val rc = s.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, 21))
        val confirmed = controlTestTimeout("rebind") { s.control.execute(rc, F.context(F.runtime())) } as ControlStoreResult.Confirmed
        val lifecycleToken = (PriorStorageConfirmation.confirmLifecycleOutput(rc, (rc.body as ControlCommandBody.Lifecycle).input, confirmed,
            LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)) as LifecycleOutputConfirmationResult.Issued).value
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request))))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val (qL, qN) = c.required.map { it.key }
        val slots = listOf(owned(qL, d, retained(c, qL, d, read)), owned(qN, d, lifecycleToken))
        rejected(assess(c, slots, read.value), listOf(f(G05Id.CONFIRMATION, c, qN)))
    }

    @Test fun N04_holdFloor_latestFloorShorter_confirmationAtRow_andLowerBound() = runReleaseTest {
        val c = noOpEdit(ControlKind.HOLD, ControlObligationFixtures.hold)
        val original = ControlObligationFixtures.node(ControlObligationFixtures.hold)
        val read = lockedRead(seeded(record(ControlKind.HOLD, original)))
        val d = DestinationLocator.Payload(ControlKind.HOLD, "h")
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }
        // Same wait (tied to the outcome), earlier anchor: less remaining at [now].
        val earlier = FloorGuardFixtures.field(original, "floor", FloorGuardFixtures.floor(elapsed = 0))
        val latest = lockedRead(seeded(record(ControlKind.HOLD, earlier))).value
        assertTrue("fixture: interpretable", !latest.hasUninterpretable)
        val at = G05Location.ActualPayload(ControlKind.HOLD, 0)
        val keys = c.required.map { it.key }
        rejected(assess(c, slots, latest), keys.map { f(G05Id.CONFIRMATION, c, it, at) } + keys.map { f(G05Id.LOWER_BOUND, c, it, at) })
    }

    // ═══ N3: completion-only slot submitted as DurablyOwned ═══════════════════════════════════════════════════════════
    @Test fun C01_completionOnlySlot_durablyOwned_threeCombinations() = runReleaseTest {
        val ref = FloorGuardFixtures.command()
        val c = Case(ref, RequirementInput.Lifecycle(ref, ref.body as ControlCommandBody.Lifecycle))
        val xL = c.required.single { it.key.component == ObligationComponent.SOURCE }.key
        assertEquals("fixture: completion-only", AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY,
            (c.slot(xL).requirement as SlotRequirement.Required).allowed)
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "g")
        val others = c.required.filter { it.key != xL }.map { completed(it.key) }
        fun slots(x: SlotHandoff) = c.required.map { if (it.key == xL) x else others.single { o -> o.key == it.key } }
        val emptyGuardRead = lockedRead(seeded(record(ControlKind.DEMAND, FloorGuardFixtures.empty)))
        // (a) row present, no confirmation
        rejected(assess(c, slots(owned(xL, d, null)), emptyGuardRead.value),
            listOf(f(G05Id.CONFIRMATION, c, xL), f(G05Id.COMPLETED_CONFLICT, c, xL)))
        // (b) locator absent
        val noGuard = lockedRead(seeded(record(ControlKind.DEMAND))).value
        rejected(assess(c, slots(owned(xL, d, null)), noGuard),
            listOf(f(G05Id.DESTINATION, c, xL), f(G05Id.CONFIRMATION, c, xL), f(G05Id.COMPLETED_CONFLICT, c, xL)))
        // (c) a valid retained confirmation does not lift the allowed-disposition violation
        rejected(assess(c, slots(owned(xL, d, retained(c, xL, d, emptyGuardRead))), emptyGuardRead.value),
            listOf(f(G05Id.COMPLETED_CONFLICT, c, xL)))
    }

    @Test fun C02_theOtherSlotsOfTheCompletionOnlyFixtureAreFine() = runReleaseTest {
        val ref = FloorGuardFixtures.command()
        val c = Case(ref, RequirementInput.Lifecycle(ref, ref.body as ControlCommandBody.Lifecycle))
        val read = lockedRead(seeded(record(ControlKind.DEMAND, FloorGuardFixtures.empty)))
        accepted(assess(c, c.required.map { completed(it.key) }, read.value))
    }

    // ═══ review r1 additions (Codex 6-4bA3b2a_contract_review_codex.r1.md) ═════════════════════════════════════════
    @Test fun N05_guardFloor_emptyChain_validThenLocatorAbsent() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.guard)
        val read = lockedRead(seeded(record(
            ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.guard))))
        val floorKeys = c.required.filter { it.key.component == ObligationComponent.FLOOR }.map { it.key }
        val authKeys = c.required.filter { it.key.component == ObligationComponent.AUTH }.map { it.key }
        assertEquals(2, floorKeys.size)
        assertEquals(2, authKeys.size)
        assertEquals(4, c.required.size)

        val floor = DestinationLocator.Guard("g", GuardPart.FLOOR)
        val auth = DestinationLocator.Guard("g", GuardPart.AUTH)
        val valid = c.required.map {
            val d = if (it.key.component == ObligationComponent.FLOOR) floor else auth
            owned(it.key, d, retained(c, it.key, d, read))
        }
        accepted(assess(c, valid, read.value))

        val absent = lockedRead(seeded(record(ControlKind.DEMAND))).value
        val slots = valid.map {
            if (it.key.component == ObligationComponent.AUTH) completed(it.key) else it
        }
        rejected(assess(c, slots, absent),
            floorKeys.map { f(G05Id.DESTINATION, c, it) } +
            floorKeys.map { f(G05Id.CONFIRMATION, c, it) })
    }

    @Test fun N06_guardFloor_tokenLocatorDiffersFromSubmittedDestination() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.guard)
        val read = lockedRead(seeded(record(
            ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.guard))))
        val floorKeys = c.required.filter { it.key.component == ObligationComponent.FLOOR }.map { it.key }
        assertEquals(2, floorKeys.size)

        val submitted = DestinationLocator.Guard("g", GuardPart.FLOOR)
        val observed = DestinationLocator.Payload(ControlKind.DEMAND, "g")
        val auth = DestinationLocator.Guard("g", GuardPart.AUTH)
        val slots = c.required.map {
            if (it.key.component == ObligationComponent.FLOOR)
                owned(it.key, submitted, retained(c, it.key, observed, read))
            else
                owned(it.key, auth, retained(c, it.key, auth, read))
        }
        rejected(assess(c, slots, read.value),
            floorKeys.map { f(G05Id.CONFIRMATION, c, it) })
    }

    @Test fun N07_journal_emptyChain_validThenLocatorAbsent() = runReleaseTest {
        val spec = RetiredNamespaceFixtures.spec()
        val ref = RetiredNamespaceFixtures.command(spec)
        val c = Case(ref, RequirementInput.Settlement(
            ref, ref.body as ControlCommandBody.Handover))
        val journalKeys = c.required.filter {
            it.key.component == ObligationComponent.JOURNAL
        }.map { it.key }
        assertEquals(2, journalKeys.size)

        val key = ((c.slot(journalKeys.first()).requirement as SlotRequirement.Required)
            .lowerBound as RequiredLowerBound.Journal).key
        val d = DestinationLocator.Journal(key)
        val settled = NamespaceSettlementFixtures.withWitness(
            NamespaceSettlementFixtures.user, RetiredNamespaceFixtures.witness(spec))
        val raw = RetiredNamespaceFixtures.raw(spec).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] =
                NamespaceSettlementFixtures.jsonArray(settled)
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = "[]"
            this[PURGE_JOURNAL] = "A|u||USER"
        }.toPreferences()
        val read = lockedRead(seeded(raw))
        val slots = c.required.map {
            if (it.key.component == ObligationComponent.JOURNAL)
                owned(it.key, d, retained(c, it.key, d, read))
            else completed(it.key)
        }
        accepted(assess(c, slots, read.value))

        val absent = lockedRead(seeded(raw.toMutablePreferences().apply {
            remove(PURGE_JOURNAL)
        }.toPreferences())).value
        rejected(assess(c, slots, absent),
            journalKeys.map { f(G05Id.DESTINATION, c, it) } +
            journalKeys.map { f(G05Id.CONFIRMATION, c, it) })
    }

    @Test fun N08_latestRowDiffersButRequestLowerBoundStillHolds() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val original = ControlObligationFixtures.node(ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, original)))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }

        val newer = FloorGuardFixtures.field(original, "raisedAt", JsonPrimitive(5))
        val latest = lockedRead(seeded(record(ControlKind.DEMAND, newer))).value
        assertTrue("fixture: interpretable", !latest.hasUninterpretable)
        assertEquals(2, c.required.size)
        val at = G05Location.ActualPayload(ControlKind.DEMAND, 0)
        rejected(assess(c, slots, latest),
            c.required.map { f(G05Id.CONFIRMATION, c, it.key, at) })
    }

    // ═══ measurement r1 additions (measure-6-4bA3b2a/sensitivity.r1.json survivors that are contract gaps) ═════════════
    /** A real REBIND_REQUESTS link (A3b1 recipe): the token of the real Confirmed and the link it issues. */
    private suspend fun rebindLink(): NamedTransferLink {
        val F = DemandAuthFixtures
        val s = open()
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(r, F.request(id = "dormant", owner = "B")) } }
        val rc = s.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, 21))
        val confirmed = controlTestTimeout("rebind") { s.control.execute(rc, F.context(F.runtime())) } as ControlStoreResult.Confirmed
        val token = (PriorStorageConfirmation.confirmLifecycleOutput(rc, (rc.body as ControlCommandBody.Lifecycle).input, confirmed,
            LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)) as LifecycleOutputConfirmationResult.Issued).value
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(rc, rc.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val n = (a1.orderedSlots.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N }
            .requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
        val source = TypedSourceTuple.Request(ResponsibilityOwner(rc.ownerTrackingLifetimeId, "owner-1"), r,
            DemandV1("r", "A", 2, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("old"), Long.MAX_VALUE)),
            n.minimumIntent, n.minimumOrder)
        val dest = TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, "r"),
            F.request(id = "r", owner = "A", binding = 3, origin = F.life, order = 22),
            DemandV1("r", "A", 3, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, EventOrderV1(F.life, 22)))
        val l = NamedTransferLink.linkNamedTransfer(source, dest, token)
        assertTrue("fixture: the A3b1 issuer must issue, got $l", l is NamedTransferResult.Issued)
        return (l as NamedTransferResult.Issued).value
    }

    @Test fun N09_nonEmptyChainWithARetainedToken_isConfirmation() = runReleaseTest {
        val link = rebindLink()
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request))))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val (qL, qN) = c.required.map { it.key }
        val chained = SlotHandoff(qN, HandoffDisposition.DurablyOwned(d, listOf(link), retained(c, qN, d, read)))
        rejected(assess(c, listOf(owned(qL, d, retained(c, qL, d, read)), chained), read.value), listOf(f(G05Id.CONFIRMATION, c, qN)))
    }

    @Test fun N10_validTokensButLatestUnreadable_destinationAndConfirmation() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request))))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }
        val unreadable = ControlRecordReader().read(ControlLifecycleEvidenceFixtures.raw(demand = "{not-an-array"))
        assertTrue("fixture", unreadable is ControlRecordRead.Unreadable)
        val keys = c.required.map { it.key }
        rejected(assess(c, slots, unreadable), keys.map { f(G05Id.DESTINATION, c, it) } + keys.map { f(G05Id.CONFIRMATION, c, it) })
    }

    /** Guard floor unchanged but AUTH state moved: the floor observation is no longer the row; AUTH also misses its bound. */
    @Test fun N11_guard_authStateChangedInLatest_confirmationAtRow_andAuthLowerBound() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.guard)
        val original = ControlObligationFixtures.node(ControlObligationFixtures.guard)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, original)))
        val floor = DestinationLocator.Guard("g", GuardPart.FLOOR)
        val auth = DestinationLocator.Guard("g", GuardPart.AUTH)
        val slots = c.required.map {
            val d = if (it.key.component == ObligationComponent.FLOOR) floor else auth
            owned(it.key, d, retained(c, it.key, d, read))
        }
        val guardAuth = checkNotNull(guard(original)?.auth)
        val moved = FloorGuardFixtures.field(original, "auth",
            DemandAuthFixtures.guard(auth = guardAuth.copy(authStateOrder = guardAuth.authStateOrder + 1)).toPayloadEntry().fields["auth"])
        val latest = lockedRead(seeded(record(ControlKind.DEMAND, moved))).value
        assertTrue("fixture: interpretable", !latest.hasUninterpretable)
        val at = G05Location.ActualPayload(ControlKind.DEMAND, 0)
        val authKeys = c.required.filter { it.key.component == ObligationComponent.AUTH }.map { it.key }
        rejected(assess(c, slots, latest), c.required.map { f(G05Id.CONFIRMATION, c, it.key, at) } +
            authKeys.map { f(G05Id.LOWER_BOUND, c, it, at) })
    }

    @Test fun N12_journalToken_latestJournalUnparsable_destinationAndConfirmation() = runReleaseTest {
        val spec = RetiredNamespaceFixtures.spec()
        val ref = RetiredNamespaceFixtures.command(spec)
        val c = Case(ref, RequirementInput.Settlement(ref, ref.body as ControlCommandBody.Handover))
        val journalKeys = c.required.filter { it.key.component == ObligationComponent.JOURNAL }.map { it.key }
        val key = ((c.slot(journalKeys.first()).requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Journal).key
        val d = DestinationLocator.Journal(key)
        val settled = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user, RetiredNamespaceFixtures.witness(spec))
        val raw = RetiredNamespaceFixtures.raw(spec).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = NamespaceSettlementFixtures.jsonArray(settled)
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = "[]"
            this[PURGE_JOURNAL] = "A|u||USER"
        }.toPreferences()
        val read = lockedRead(seeded(raw))
        val slots = c.required.map { if (it.key.component == ObligationComponent.JOURNAL) owned(it.key, d, retained(c, it.key, d, read)) else completed(it.key) }
        val broken = lockedRead(seeded(raw.toMutablePreferences().apply { this[PURGE_JOURNAL] = "broken" }.toPreferences())).value
        rejected(assess(c, slots, broken), journalKeys.map { f(G05Id.DESTINATION, c, it) } + journalKeys.map { f(G05Id.CONFIRMATION, c, it) })
    }

    // ═══ review r3 addition: duplicate ids make both rows uninterpretable, but locations still returns two positions ═══
    @Test fun N13_latestHasTwoRowsWithTheTokensId_destinationAndConfirmationWithoutPosition() = runReleaseTest {
        val c = noOpEdit(ControlKind.DEMAND, ControlObligationFixtures.request)
        val original = ControlObligationFixtures.node(ControlObligationFixtures.request)
        val read = lockedRead(seeded(record(ControlKind.DEMAND, original)))
        val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val slots = c.required.map { owned(it.key, d, retained(c, it.key, d, read)) }
        val twice = lockedRead(seeded(record(ControlKind.DEMAND, original, original))).value
        assertTrue("fixture: the colliding rows are uninterpretable", twice.hasUninterpretable)
        assertEquals("fixture: locations still returns both", 2, twice.locations("d").size)
        val keys = c.required.map { it.key }
        rejected(assess(c, slots, twice), keys.map { f(G05Id.DESTINATION, c, it) } + keys.map { f(G05Id.CONFIRMATION, c, it) })
    }
}
