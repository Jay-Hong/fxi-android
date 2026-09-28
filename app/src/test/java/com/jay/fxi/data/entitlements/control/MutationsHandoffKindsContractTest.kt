package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.auth
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.floor
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.nullSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.topicHold
import java.util.concurrent.atomic.AtomicInteger
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
 * Claude-owned 6-4bC1b contract (6-4bC1b consensus r1; design 6-4bC1b_contract_design_codex.r2.md): handoffAfterUncertainConfirm
 * for every Mutations kind once the retained issuer covers the stored Mutations rows (C1b-0). Successor typed links are out of
 * scope (separate consensus).
 *  - Every row executes a real command whose business Confirm fails on return, so the ref is OnceConfirm·U. Every required L/N
 *    slot is recorded in B1 (HandoffCoordinator) either with a retained token issued from the observed record or as completed and
 *    consumed, and B1 issues the closure and declaration that are submitted.
 *  - Success (T+) is Completed(ResponsibilityTransferred): the command's own Applied row goes 1 → 0 and every other value is
 *    unchanged. T0 is the same result with no own row (0 → 0). Every refusal leaves the own row and everything else untouched.
 *  - Completion follows real processing: a SettleQuery, current NULL settlement, RECOVER_HOLD or RECOVER_INTENT command is
 *    Confirmed on the same store first, then B1 records the completion and its consumption. A floor is never declared completed.
 *  - The expected layer is fixed per case: an uninterpretable own row is a G07 recovery, an interpretable own mismatch is a
 *    matcher Conflict, destination/confirmation/bound/completion failures are the listed G05 failures in G05 order.
 * The implementation thread reads but does not edit this file.
 */
class MutationsHandoffKindsContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    private val extra = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runReleaseTest { controlTestTimeout("C1b cleanup", 30_000) { (extra + fx.storage).forEach { it.close() } } }
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)

    // ── rows ─────────────────────────────────────────────────────────────────────────────────────────────────────────
    private val newSeal = """{"id":"x","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"e1"}"""
    private val joinedSeal = newSeal.replace("\"id\":\"x\"", "\"id\":\"s\"")
    private val floorGuard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":$floor}"""
    private val authGuard = """{"id":"g","kind":"SCHEDULE_GUARD","auth":$auth}"""
    private val floorlessHold = topicHold // the fixture PENDING hold carries retryAfterSeconds 30, which needs its floor
    private val strongRequest = request.replace("IF_STALE", "FORCE_PREMIUM")

    private fun add(kind: ControlKind, json: String) = fx.store.addition(kind) { id -> literal(json); set("id", ControlScalar.Text(id)) }
    private fun idOf(m: ControlMutation) = (m as ControlMutation.Add).proposedId
    private fun payload(kind: ControlKind, id: String) = DestinationLocator.Payload(kind, id)

    // ── OnceConfirm·U ────────────────────────────────────────────────────────────────────────────────────────────────
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }

    /** Executes the actions; the business Confirm lands and its return fails. own=0 arms a read-back so the Confirm reaches its write. */
    private suspend fun runU(vararg actions: ControlMutation, own: Int): CommandRef {
        val c = fx.store.prepare(*actions)
        if (own == 0) fx.armReadBack()
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("execute") { fx.store.execute(c) }
        assertTrue("fixture: Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        assertEquals("fixture: own Applied rows", own, ownIn(fx.disk(), c))
        return c
    }

    private sealed interface Disp {
        data class To(val d: DestinationLocator) : Disp
        data object Done : Disp
        data object Skip : Disp
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>)
    private fun index(slot: RequiredSlot) = (slot.key.role as ObligationRole.MutationAction).index

    private suspend fun lockedRead() = controlTestTimeout("locked read") {
        fx.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }

    /** B1: each required slot gets a retained token on its destination (To), a completion and its consumption (Done), or nothing. */
    private suspend fun issue(c: CommandRef, plan: (RequiredSlot) -> Disp): Pair<HandoffIssueResult, List<RequiredSlot>> {
        val body = c.body as ControlCommandBody.Mutations
        val input = RequirementInput.Mutations(c, body, MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val required = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertTrue("fixture: required slots", required.isNotEmpty())
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, owner)
        val locked = lockedRead()
        for (slot in required) when (val p = plan(slot)) {
            is Disp.To -> {
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, p.d, locked)
                assertTrue("fixture: token for ${slot.key} at ${p.d}, got $t", t is RetainedSourceConfirmationResult.Issued)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(b, slot.key,
                    HandoffDisposition.DurablyOwned(p.d, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
            }
            Disp.Done -> {
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
            }
            Disp.Skip -> Unit
        }
        return h.closeJoinAndIssueHandoff(input) to required
    }
    private suspend fun declare(c: CommandRef, plan: (RequiredSlot) -> Disp): Declared {
        val (issued, required) = issue(c, plan)
        assertTrue("fixture: B1 issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Declared(issued.closure, issued.handoff, required)
    }
    private fun refused(r: HandoffIssueResult, reason: HandoffIssueRefusal) =
        assertTrue("expected Refused($reason), got $r", r is HandoffIssueResult.Refused && r.reason == reason)

    // ── observation ──────────────────────────────────────────────────────────────────────────────────────────────────
    private class Observed(val accesses: Int, val writes: Int, val prefs: Preferences, val state: ControlCommandLifecycle, val body: Any?,
        val unresolved: Set<CommandRef>, val pending: Set<CommandRef>, val descriptor: TerminationPendingDescriptor?)
    private suspend fun observe(c: CommandRef): Observed {
        val v = c.captureStateAndBody(); val w = fx.tracker.recoverySnapshot()
        return Observed(fx.boundary.accesses, fx.storage.storage.writes, fx.disk(), v.state, v.body, w.unresolvedCommands, w.pendingReleases,
            fx.tracker.findPrepared(c)?.terminationDescriptor)
    }
    private fun unchanged(before: Observed, after: Observed, ownerAccesses: Int) {
        assertEquals("owner accesses", before.accesses + ownerAccesses, after.accesses)
        assertEquals("no store write", before.writes, after.writes)
        assertEquals("deserialized preferences", before.prefs, after.prefs)
        assertEquals("ref state", before.state, after.state)
        assertSame("ref body", before.body, after.body)
        assertEquals("unresolved", before.unresolved, after.unresolved)
        assertEquals("pending", before.pending, after.pending)
        assertSame("descriptor", before.descriptor, after.descriptor)
    }
    private suspend fun handoff(c: CommandRef, d: Declared, h: CompletionHandoff = d.handoff) =
        controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, h) }

    /** T+ (own 1 → 0) or T0 (own 0 → 0): only c's own Applied removed, terminal, body released, out of U, P and commands. */
    private suspend fun completedTransferred(r: ControlCompletionResult, c: CommandRef, before: Preferences, ownBefore: Int) {
        assertEquals("own before", ownBefore, ownIn(before, c))
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only c's own Applied removed", JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }),
            evidence(after))
        assertEquals("own after", 0, ownIn(after, c))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("c left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertNull("c left commands", fx.tracker.findPrepared(c))
    }
    private suspend fun transferred(c: CommandRef, d: Declared, own: Int) {
        val before = fx.disk()
        completedTransferred(handoff(c, d), c, before, own)
    }
    private fun rejected(r: ControlCompletionResult, reason: CompletionRejectionReason) =
        assertTrue("expected Rejected($reason), got $r", r is ControlCompletionResult.Rejected && r.reason == reason)
    /** A first-entry refusal after one owner access: the result is checked, nothing changed, the own count is kept. */
    private suspend fun refusedAtOwner(c: CommandRef, d: Declared, h: CompletionHandoff = d.handoff, check: (ControlCompletionResult) -> Unit) {
        val own = ownIn(fx.disk(), c)
        val before = observe(c)
        check(handoff(c, d, h))
        unchanged(before, observe(c), 1)
        assertEquals("own kept", own, ownIn(fx.disk(), c))
    }

    // ── G05 expectations ─────────────────────────────────────────────────────────────────────────────────────────────
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private fun slotOf(d: Declared, component: ObligationComponent, branch: LandingBranch, index: Int = 0) =
        d.required.single { it.key.component == component && it.key.branch == branch && index(it) == index }
    private fun fail(d: Declared, id: G05Id, slot: RequiredSlot, actual: G05Location?) = G05Failure(id, slot.key,
        G05Location.Fixed(FixedInputLocation(FixedInputRoot.MUTATION_ACTION, index(slot), FixedInputFacet.WHOLE)),
        G05Location.Submitted(d.handoff.slots.indexOfFirst { it.key == slot.key }.also { check(it >= 0) }), actual)
    /** The failure [id] for the L then the N slot of [component]. */
    private fun both(d: Declared, id: G05Id, component: ObligationComponent, actual: G05Location?) =
        listOf(L, N).map { fail(d, id, slotOf(d, component, it), actual) }
    private fun g05(vararg groups: List<G05Failure>) = CompletionRejectionReason.G05(groups.toList().flatten())

    // ── record edits ─────────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun setRow(key: Preferences.Key<String>, value: String) = fx.edit { it[key] = value }
    private suspend fun rewrite(key: Preferences.Key<String>, from: String, to: String) = fx.edit {
        val v = checkNotNull(it[key]); check(v.contains(from)) { "fixture: $from not in $v" }; it[key] = v.replace(from, to)
    }
    private suspend fun rewriteOwn(c: CommandRef, change: (JsonObject) -> JsonObject) = fx.edit { p ->
        val rows = (Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray).map { it as JsonObject }
        val own = rows.single { it["commandId"] == JsonPrimitive(c.id) }
        p[evidenceKey] = JsonArray(rows.filter { it !== own } + change(own)).toString()
    }
    private fun JsonObject.targets() = (this["targets"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.withTargets(t: List<JsonObject>) = JsonObject(this + ("targets" to JsonArray(t)))
    private suspend fun assertInterpretableMetadata() {
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: interpretable metadata, got $read", read is ControlRecordRead.Supported && !read.hasUninterpretableMetadata)
    }

    // ── kind fixtures (all return the U ref) ─────────────────────────────────────────────────────────────────────────
    private class Run(val c: CommandRef, val id: String)
    private suspend fun requestAdd(json: String = request): Run { fx.storage.seed(); val a = add(ControlKind.DEMAND, json); return Run(runU(a, own = 1), idOf(a)) }
    /** A NAMESPACE append must match the current epoch: no owner and USER epoch "old", as the fixture seal has. */
    private suspend fun sealAdd(json: String = seal): Run {
        fx.storage.seed(); if (json == seal) fx.storage.currentNamespace()
        val a = add(ControlKind.SEAL, json); return Run(runU(a, own = 1), idOf(a))
    }
    private suspend fun guardFloorAdd(): Run { fx.storage.seed(); val a = add(ControlKind.DEMAND, floorGuard); return Run(runU(a, own = 1), idOf(a)) }
    private suspend fun authGuardNoop(): CommandRef {
        fx.storage.seed(demand = "[$authGuard]"); return runU(fx.store.edit(ControlKind.DEMAND, node(authGuard)) {}, own = 0)
    }
    private suspend fun holdAdd(json: String): Run { fx.storage.seed(); val a = add(ControlKind.HOLD, json); return Run(runU(a, own = 1), idOf(a)) }
    private suspend fun intentAdd(): Run { fx.storage.seed(); val a = add(ControlKind.RECOVERY_INTENT, recovery); return Run(runU(a, own = 1), idOf(a)) }
    private fun guardParts(id: String): (RequiredSlot) -> Disp = { s ->
        Disp.To(DestinationLocator.Guard(id, if (s.key.component == ObligationComponent.AUTH) GuardPart.AUTH else GuardPart.FLOOR))
    }
    private fun direct(d: DestinationLocator): (RequiredSlot) -> Disp = { Disp.To(d) }
    private val done: (RequiredSlot) -> Disp = { Disp.Done }

    // ═══ positive: directly owned ══════════════════════════════════════════════════════════════════════════════════════
    @Test fun P01_activeRequestAdd_directLN_transferred() = runReleaseTest {
        val r = requestAdd(); transferred(r.c, declare(r.c, direct(payload(ControlKind.DEMAND, r.id))), 1)
    }

    @Test fun P02_changedRequestEdit_directLN_transferred() = runReleaseTest {
        fx.storage.seed(demand = "[$request]")
        val c = runU(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }, own = 1)
        transferred(c, declare(c, direct(payload(ControlKind.DEMAND, "d"))), 1)
    }

    @Test fun P03_activeSealAdd_directLN_transferred() = runReleaseTest {
        val r = sealAdd(); transferred(r.c, declare(r.c, direct(payload(ControlKind.SEAL, r.id))), 1)
    }

    @Test fun P04_noopSealEdit_ownAbsent_transferred() = runReleaseTest {
        fx.storage.seed(seal = "[$seal]")
        val c = runU(fx.store.edit(ControlKind.SEAL, node(seal)) {}, own = 0)
        transferred(c, declare(c, direct(payload(ControlKind.SEAL, "s"))), 0)
    }

    /** P05: the SEAL Add joins the existing seal "s" of the same key; handed over on the adopted id only. */
    @Test fun P05_joinedSealAdd_ownAbsent_transferredOnTheAdoptedId() = runReleaseTest {
        fx.storage.seed(seal = "[$joinedSeal]")
        val c = runU(add(ControlKind.SEAL, newSeal), own = 0)
        val target = checkNotNull(fx.tracker.findPrepared(c)).targets.get().single()
        assertTrue("fixture: joined on s", target != null && target.id == "s" && target.joined)
        transferred(c, declare(c, direct(payload(ControlKind.SEAL, "s"))), 0)
    }

    @Test fun P06_guardFloorAdd_floorLN_transferred() = runReleaseTest {
        val r = guardFloorAdd()
        val d = declare(r.c, guardParts(r.id))
        assertEquals("fixture: FLOOR L and N only", setOf(ObligationComponent.FLOOR), d.required.map { it.key.component }.toSet())
        transferred(r.c, d, 1)
    }

    /** P07: a guard with AUTH gets a recaptured floor; FLOOR L/N and the preserved AUTH L/N are each handed over. */
    @Test fun P07_guardFloorRecaptureWithAuth_floorAndAuthLN_transferred() = runReleaseTest {
        fx.storage.seed(demand = "[$guard]")
        val c = runU(fx.store.recordFloor(node(guard), now, 50_000, LifetimeId("life2")), own = 1)
        val d = declare(c, guardParts("g"))
        assertEquals("fixture: FLOOR and AUTH, L and N", 4, d.required.size)
        transferred(c, d, 1)
    }

    @Test fun P08_authOnlyGuardNoopEdit_authLN_transferred() = runReleaseTest {
        val c = authGuardNoop()
        val d = declare(c, guardParts("g"))
        assertEquals("fixture: AUTH L and N only", setOf(ObligationComponent.AUTH), d.required.map { it.key.component }.toSet())
        transferred(c, d, 0)
    }

    /** P09: SOURCE and FLOOR, each L and N, from the same stored hold. */
    @Test fun P09_holdAddWithFloor_sourceAndFloorLN_transferred() = runReleaseTest {
        val r = holdAdd(hold)
        val d = declare(r.c, direct(payload(ControlKind.HOLD, r.id)))
        assertEquals("fixture: SOURCE and FLOOR, L and N", 4, d.required.size)
        transferred(r.c, d, 1)
    }

    @Test fun P10_holdNoopEditWithFloor_sourceAndFloorLN_transferred() = runReleaseTest {
        fx.storage.seed(hold = "[$hold]")
        val c = runU(fx.store.edit(ControlKind.HOLD, node(hold)) {}, own = 0)
        val d = declare(c, direct(payload(ControlKind.HOLD, "h")))
        assertEquals("fixture: SOURCE and FLOOR, L and N", 4, d.required.size)
        transferred(c, d, 0)
    }

    @Test fun P11_intentAdd_sourceLN_transferred() = runReleaseTest {
        val r = intentAdd(); transferred(r.c, declare(r.c, direct(payload(ControlKind.RECOVERY_INTENT, r.id))), 1)
    }

    @Test fun P12_intentNoopEdit_sourceLN_transferred() = runReleaseTest {
        fx.storage.seed(recovery = "[$recovery]")
        val c = runU(fx.store.edit(ControlKind.RECOVERY_INTENT, node(recovery)) {}, own = 0)
        transferred(c, declare(c, direct(payload(ControlKind.RECOVERY_INTENT, "r"))), 0)
    }

    // ═══ positive: completed and consumed ══════════════════════════════════════════════════════════════════════════════
    // Each component is processed by a real command on the same store (Confirmed), and only then is the completion and its
    // consumption recorded in B1. The processing command's own evidence and rows stay in the record the handoff reads.
    private suspend fun seedRaw(p: Preferences) = controlTestTimeout("raw seed") { fx.storage.data.updateData { p } }
    private fun jsonOf(n: ControlNode) = n.toPayloadEntry().fields.toString()
    private suspend fun rows(kind: ControlKind): List<ControlNode> =
        (ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported).arrays.getValue(kind).entries.map {
            (it as ControlEntryRead.Interpreted).original }
    private fun idIn(kind: ControlKind, n: ControlNode) = ((ControlObligations.read(kind, n) as ControlEntryRead.Interpreted).value as ControlObligationV1).id
    private suspend fun stored(kind: ControlKind, id: String) = rows(kind).single { idIn(kind, it) == id }
    private suspend fun absent(kind: ControlKind, id: String) = rows(kind).none { idIn(kind, it) == id }
    private suspend fun processed(c: CommandRef, context: AttemptContext) {
        val r = controlTestTimeout("process") { fx.store.execute(c, context) }
        assertTrue("fixture: processing Confirmed, got $r", r is ControlStoreResult.Confirmed)
    }

    /** SettleQuery consumes the REQUEST [id] (DemandAuthFixtures: owner A, binding 3, registered query order 21). */
    private suspend fun settleRequest(id: String) {
        val q = DemandAuthFixtures
        processed(fx.store.prepareSettleQuery(listOf(stored(ControlKind.DEMAND, id)), null, null, q.binding, q.decision(),
            LifecycleOrderSource(q.life, 21)), q.context(q.runtime()))
        assertTrue("fixture: REQUEST $id consumed", absent(ControlKind.DEMAND, id))
    }
    /** The current NULL settlement settles the NULL seal [id] (CurrentNullFixtures: fence A/u2/k2). */
    private suspend fun settleNullSeal(id: String) {
        val n = CurrentNullFixtures
        processed(fx.store.prepareCurrentNullSettlement(listOf(stored(ControlKind.SEAL, id)),
            ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported, n.before, n.executor, n.request), n.context)
        val settled = (ControlObligations.read(ControlKind.SEAL, stored(ControlKind.SEAL, id)) as ControlEntryRead.Interpreted).value as SealV1
        assertTrue("fixture: seal $id settled", settled.settlement != null)
    }
    /** RECOVER_HOLD processes the floorless HOLD [id] (HoldRecoveryFixtures, after restart). */
    private suspend fun recoverHold(id: String) {
        val h = HoldRecoveryFixtures
        val input = h.input(h = stored(ControlKind.HOLD, id))
        processed(fx.store.prepareRecoverHold(input, LifecycleOrderSource(h.life, 21)), h.context(input))
        assertTrue("fixture: HOLD $id processed", absent(ControlKind.HOLD, id))
    }
    private val lifecycleIntent = """{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k0"}"""
    /** RECOVER_INTENT processes the intent [id] after a restart (LifecycleWriterFixtures.recoverIntent inputs). */
    private suspend fun recoverIntent(id: String) {
        val life = LifetimeId("new-life")
        val source = stored(ControlKind.RECOVERY_INTENT, id)
        val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")
        val closure = HoldRecoveryClosure.AfterRestart(source, binding.executor, "old-tracking", true, true)
        processed(fx.store.prepareRecoverIntent(RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure), LifecycleOrderSource(life, 21)),
            AttemptContext("A", 3, life, false, false, intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure)))
        assertTrue("fixture: intent $id processed", absent(ControlKind.RECOVERY_INTENT, id))
    }
    private fun lifecycleRaw(): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM] = true; this[DataStoreAccessEpochStore.MAY_CONTAIN_KRX] = true
    }.toPreferences()

    @Test fun P13_requestAdd_settled_completedLN_transferred() = runReleaseTest {
        seedRaw(DemandAuthFixtures.raw())
        val a = add(ControlKind.DEMAND, jsonOf(DemandAuthFixtures.request()))
        val c = runU(a, own = 1); settleRequest(idOf(a))
        transferred(c, declare(c, done), 1)
    }

    /** P14: the added NULL seal is settled by the current NULL settlement; the settled seal stays, which is no completion conflict. */
    @Test fun P14_sealAdd_settled_completedLN_transferred() = runReleaseTest {
        seedRaw(CurrentNullFixtures.raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[]" }.toPreferences())
        val a = add(ControlKind.SEAL, CurrentNullFixtures.nullUser)
        val c = runU(a, own = 1); settleNullSeal(idOf(a))
        transferred(c, declare(c, done), 1)
    }

    @Test fun P15_sealNoopEdit_settled_completedLN_transferred() = runReleaseTest {
        seedRaw(CurrentNullFixtures.raw())
        val c = runU(fx.store.edit(ControlKind.SEAL, node(CurrentNullFixtures.nullUser)) {}, own = 0)
        settleNullSeal("s")
        transferred(c, declare(c, done), 0)
    }

    @Test fun P16_floorlessHoldAdd_recovered_completedLN_transferred() = runReleaseTest {
        val template = HoldRecoveryFixtures.hold(floor = false)
        seedRaw(HoldRecoveryFixtures.before(HoldRecoveryFixtures.input(h = template)).toMutablePreferences().apply {
            this[ControlStoreTestStorage.HOLD] = "[]" }.toPreferences())
        val a = add(ControlKind.HOLD, jsonOf(template))
        val c = runU(a, own = 1); recoverHold(idOf(a))
        val d = declare(c, done)
        assertEquals("fixture: SOURCE L and N only", setOf(ObligationComponent.SOURCE), d.required.map { it.key.component }.toSet())
        transferred(c, d, 1)
    }

    @Test fun P17_intentAdd_recovered_completedLN_transferred() = runReleaseTest {
        seedRaw(lifecycleRaw())
        val a = add(ControlKind.RECOVERY_INTENT, lifecycleIntent)
        val c = runU(a, own = 1); recoverIntent(idOf(a))
        transferred(c, declare(c, done), 1)
    }

    // ═══ positive: mixed actions ═══════════════════════════════════════════════════════════════════════════════════════
    private val survivorRow = JsonObject(mapOf("version" to JsonPrimitive(2), "commandId" to JsonPrimitive("survivor"),
        "ownerTrackingLifetimeId" to JsonPrimitive("11111111-1111-4111-8111-111111111111"), "kind" to JsonPrimitive("MUTATIONS"),
        "targets" to JsonArray(listOf(JsonObject(mapOf("index" to JsonPrimitive(0), "kind" to JsonPrimitive("RECOVERY_INTENT"),
            "id" to JsonPrimitive("r9"), "joined" to JsonPrimitive(false), "written" to JsonPrimitive(true)))))))
    /** index 0 REQUEST changed Edit (settled by SettleQuery, completed), 1 GUARD floor Add, 2 SEAL no-op, 3 HOLD no-op, 4 INTENT no-op (direct). */
    private suspend fun mixed(): Pair<CommandRef, Declared> {
        seedRaw(DemandAuthFixtures.raw(node(request)).toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = "[$seal]"; this[ControlStoreTestStorage.HOLD] = "[$hold]"
            this[ControlStoreTestStorage.RECOVERY] = "[$recovery]" }.toPreferences())
        val g = add(ControlKind.DEMAND, floorGuard)
        val c = runU(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }, g,
            fx.store.edit(ControlKind.SEAL, node(seal)) {}, fx.store.edit(ControlKind.HOLD, node(hold)) {},
            fx.store.edit(ControlKind.RECOVERY_INTENT, node(recovery)) {}, own = 1)
        settleRequest("d")
        fx.edit { p -> p[evidenceKey] = JsonArray(evidence(p.toPreferences()) + survivorRow).toString() }
        assertInterpretableMetadata()
        val d = declare(c) { s -> when (index(s)) {
            0 -> Disp.Done
            1 -> Disp.To(DestinationLocator.Guard(idOf(g), GuardPart.FLOOR))
            2 -> Disp.To(payload(ControlKind.SEAL, "s"))
            3 -> Disp.To(payload(ControlKind.HOLD, "h"))
            else -> Disp.To(payload(ControlKind.RECOVERY_INTENT, "r"))
        } }
        assertEquals("fixture: 12 L/N slots", 12, d.required.size)
        return c to d
    }

    @Test fun P18_mixedActions_everyIndexHandedOver_onlyOwnRemoved() = runReleaseTest {
        val (c, d) = mixed()
        transferred(c, d, 1)
        assertEquals("survivor kept", 1, evidence(fx.disk()).count { (it as JsonObject)["commandId"] == JsonPrimitive("survivor") })
    }

    // ═══ negative ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun N01_guardFloorNSlotNotRecorded_b1Refuses() = runReleaseTest {
        val r = guardFloorAdd()
        refused(issue(r.c) { s -> if (s.key.branch == N) Disp.Skip else guardParts(r.id)(s) }.first, HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
        assertEquals("own kept", 1, ownIn(fx.disk(), r.c))
    }

    @Test fun N02_mixedDeclarationWithoutGuardFloorN_g05CoverageN() = runReleaseTest {
        val (c, d) = mixed()
        val missing = slotOf(d, ObligationComponent.FLOOR, N, index = 1)
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
        refusedAtOwner(c, d, forged) { rejected(it, CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_N, missing.key,
            G05Location.Fixed(FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 1, FixedInputFacet.WHOLE)), null, null)))) }
    }

    private fun conflict(r: ControlCompletionResult) = assertTrue("Conflict(CommandEvidenceMismatch), got $r",
        r is ControlCompletionResult.Conflict && r.reason == ConflictReason.CommandEvidenceMismatch)
    private fun recoveryMetadata(r: ControlCompletionResult) = assertTrue("RecoveryRequired(UninterpretableMetadata), got $r",
        r is ControlCompletionResult.RecoveryRequired && r.reason == RecoveryReason.UninterpretableMetadata)

    /** N03: two REQUEST Adds; the own row's target ids are swapped between the indexes (still interpretable). */
    @Test fun N03_ownTargetIdsSwappedBetweenIndexes_conflict() = runReleaseTest {
        fx.storage.seed()
        val a0 = add(ControlKind.DEMAND, request); val a1 = add(ControlKind.DEMAND, request)
        val c = runU(a0, a1, own = 1)
        val d = declare(c) { s -> Disp.To(payload(ControlKind.DEMAND, idOf(if (index(s) == 0) a0 else a1))) }
        rewriteOwn(c) { own -> val t = own.targets()
            own.withTargets(listOf(JsonObject(t[0] + ("id" to t[1]["id"]!!)), JsonObject(t[1] + ("id" to t[0]["id"]!!)))) }
        assertInterpretableMetadata()
        refusedAtOwner(c, d) { conflict(it) }
    }

    /** REQUEST changed Edit (index 0) and a joined SEAL Add (index 1): the own row's joined target has (joined, written) = (true, false). */
    private suspend fun requestEditAndJoinedSeal(): Pair<CommandRef, Declared> {
        fx.storage.seed(seal = "[$joinedSeal]", demand = "[$request]")
        val c = runU(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) },
            add(ControlKind.SEAL, newSeal), own = 1)
        val d = declare(c) { s -> Disp.To(if (index(s) == 0) payload(ControlKind.DEMAND, "d") else payload(ControlKind.SEAL, "s")) }
        rewriteOwn(c) { own -> val t = own.targets()
            assertEquals("fixture: joined target", listOf(JsonPrimitive(true), JsonPrimitive(false)), listOf(t[1]["joined"], t[1]["written"]))
            own }
        return c to d
    }
    private fun JsonObject.withJoinedWritten(joined: Boolean, written: Boolean): JsonObject { val t = targets()
        return withTargets(listOf(t[0], JsonObject(t[1] + ("joined" to JsonPrimitive(joined)) + ("written" to JsonPrimitive(written))))) }

    @Test fun N04_joinedTargetFlippedToWritten_conflict() = runReleaseTest {
        val (c, d) = requestEditAndJoinedSeal()
        rewriteOwn(c) { it.withJoinedWritten(joined = false, written = true) }
        assertInterpretableMetadata()
        refusedAtOwner(c, d) { conflict(it) }
    }

    @Test fun N05_joinedAndWritten_uninterpretable_recovery() = runReleaseTest {
        val (c, d) = requestEditAndJoinedSeal()
        rewriteOwn(c) { it.withJoinedWritten(joined = true, written = true) }
        refusedAtOwner(c, d) { recoveryMetadata(it) }
    }

    @Test fun N06_ownAppliedDuplicated_recovery() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        fx.edit { p -> val rows = evidence(p.toPreferences())
            p[evidenceKey] = JsonArray(rows + rows.single { (it as JsonObject)["commandId"] == JsonPrimitive(r.c.id) }).toString() }
        assertEquals("fixture: two own rows", 2, ownIn(fx.disk(), r.c))
        refusedAtOwner(r.c, d) { recoveryMetadata(it) }
    }

    @Test fun N07_activeRequestLeft_declaredCompleted_g05CompletedConflict() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, done)
        refusedAtOwner(r.c, d) { rejected(it, g05(both(d, G05Id.COMPLETED_CONFLICT, ObligationComponent.REQUEST,
            G05Location.ActualPayload(ControlKind.DEMAND, 0)))) }
    }

    @Test fun N08_activeSealLeft_declaredCompleted_g05CompletedConflict() = runReleaseTest {
        val r = sealAdd(); val d = declare(r.c, done)
        refusedAtOwner(r.c, d) { rejected(it, g05(both(d, G05Id.COMPLETED_CONFLICT, ObligationComponent.SEAL,
            G05Location.ActualPayload(ControlKind.SEAL, 0)))) }
    }

    @Test fun N09_floorRecordedCompleted_b1Refuses() = runReleaseTest {
        val r = guardFloorAdd()
        refused(issue(r.c, done).first, HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
        assertEquals("own kept", 1, ownIn(fx.disk(), r.c))
    }

    @Test fun N10_floorDispositionForgedCompleted_g05CompletedConflict() = runReleaseTest {
        val r = guardFloorAdd(); val d = declare(r.c, guardParts(r.id))
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.map {
            SlotHandoff(it.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(it.key.subject), emptyList())) })
        refusedAtOwner(r.c, d, forged) { rejected(it, g05(both(d, G05Id.COMPLETED_CONFLICT, ObligationComponent.FLOOR, null))) }
    }

    @Test fun N11_requestRowRemovedAfterToken_g05DestinationAndConfirmation() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        setRow(ControlStoreTestStorage.DEMAND, "[]")
        refusedAtOwner(r.c, d) { rejected(it, g05(both(d, G05Id.DESTINATION, ObligationComponent.REQUEST, null),
            both(d, G05Id.CONFIRMATION, ObligationComponent.REQUEST, null))) }
    }

    private fun confirmationAndBound(d: Declared, component: ObligationComponent, kind: ControlKind) = g05(
        both(d, G05Id.CONFIRMATION, component, G05Location.ActualPayload(kind, 0)),
        both(d, G05Id.LOWER_BOUND, component, G05Location.ActualPayload(kind, 0)))

    @Test fun N12_strongRequestWeakenedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val r = requestAdd(strongRequest); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        rewrite(ControlStoreTestStorage.DEMAND, "FORCE_PREMIUM", "IF_STALE")
        refusedAtOwner(r.c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.REQUEST, ControlKind.DEMAND)) }
    }

    @Test fun N13_sealKeyChangedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val r = sealAdd(); val d = declare(r.c, direct(payload(ControlKind.SEAL, r.id)))
        rewrite(ControlStoreTestStorage.SEAL, "\"epoch\":\"old\"", "\"epoch\":\"e2\"")
        refusedAtOwner(r.c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.SEAL, ControlKind.SEAL)) }
    }

    @Test fun N14_guardFloorWeakenedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val r = guardFloorAdd(); val d = declare(r.c, guardParts(r.id))
        rewrite(ControlStoreTestStorage.DEMAND, "\"waitMillis\":30000", "\"waitMillis\":10000")
        refusedAtOwner(r.c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.FLOOR, ControlKind.DEMAND)) }
    }

    @Test fun N15_authStateOrderChangedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val c = authGuardNoop(); val d = declare(c, guardParts("g"))
        rewrite(ControlStoreTestStorage.DEMAND, "\"authStateOrder\":8", "\"authStateOrder\":7")
        refusedAtOwner(c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.AUTH, ControlKind.DEMAND)) }
    }

    @Test fun N16_holdProvenanceChangedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val r = holdAdd(floorlessHold); val d = declare(r.c, direct(payload(ControlKind.HOLD, r.id)))
        rewrite(ControlStoreTestStorage.HOLD, "\"grant\":9", "\"grant\":10")
        refusedAtOwner(r.c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.SOURCE, ControlKind.HOLD)) }
    }

    @Test fun N17_intentTargetEpochChangedAfterToken_g05ConfirmationAndLowerBound() = runReleaseTest {
        val r = intentAdd(); val d = declare(r.c, direct(payload(ControlKind.RECOVERY_INTENT, r.id)))
        rewrite(ControlStoreTestStorage.RECOVERY, "\"targetEpoch\":null", "\"targetEpoch\":\"k\"")
        refusedAtOwner(r.c, d) { rejected(it, confirmationAndBound(d, ObligationComponent.SOURCE, ControlKind.RECOVERY_INTENT)) }
    }

    // ═══ fault, retry, reopen ══════════════════════════════════════════════════════════════════════════════════════════
    private fun pendingConfirming(r: ControlCompletionResult) = assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r",
        r is ControlCompletionResult.Unconfirmed && r.state == ControlCommandLifecycle.TERMINATION_PENDING &&
            r.phase == ControlAttemptPhase.ConfirmingStorage)
    private fun handoffDescriptor(c: CommandRef): TerminationPendingDescriptor.MutationsHandoff {
        val descriptor = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("fixed MutationsHandoff, got $descriptor", descriptor is TerminationPendingDescriptor.MutationsHandoff)
        return descriptor as TerminationPendingDescriptor.MutationsHandoff
    }
    private suspend fun retry(c: CommandRef, d: Declared) = controlTestTimeout("retry") { store().retryTermination(c, d.closure) }

    @Test fun F01_confirmFailsBeforeWrite_retryTransfers() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        val before = fx.disk()
        fx.storage.storage.before = true
        pendingConfirming(handoff(r.c, d))
        assertTrue("expected own fixed", handoffDescriptor(r.c).expectedOwn != null)
        assertEquals("own still present", 1, ownIn(fx.disk(), r.c))
        completedTransferred(retry(r.c, d), r.c, before, 1)
    }

    @Test fun F02_confirmLandsReturnFails_retryOnSameDescriptorTransfers() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        val before = fx.disk()
        fx.storage.storage.afterScope = true
        pendingConfirming(handoff(r.c, d))
        val descriptor = handoffDescriptor(r.c)
        assertEquals("own already removed", 0, ownIn(fx.disk(), r.c))
        var seen: TerminationPendingDescriptor? = null
        fx.boundary.afterReturn = { p -> seen = fx.tracker.findPrepared(r.c)?.terminationDescriptor; p }
        completedTransferred(retry(r.c, d), r.c, before, 1)
        assertSame("retry used the fixed descriptor object", descriptor, seen)
    }

    @Test fun F03_joinedSealOwnAbsent_confirmReturnFails_retryTransfers() = runReleaseTest {
        fx.storage.seed(seal = "[$joinedSeal]")
        val c = runU(add(ControlKind.SEAL, newSeal), own = 0)
        val d = declare(c, direct(payload(ControlKind.SEAL, "s")))
        val before = fx.disk()
        fx.armReadBack(); fx.storage.storage.afterScope = true
        pendingConfirming(handoff(c, d))
        assertNull("absence fixed", handoffDescriptor(c).expectedOwn)
        completedTransferred(retry(c, d), c, before, 0)
    }

    @Test fun F04_realReopen_oldRefRetry_wrongTrackerLifetime() = runReleaseTest {
        val r = requestAdd(); val d = declare(r.c, direct(payload(ControlKind.DEMAND, r.id)))
        fx.storage.storage.before = true
        pendingConfirming(handoff(r.c, d))
        fx.storage.close()
        val reopened = ControlStoreTestStorage(fx.file).also { extra += it }
        val newTracker = ControlCommandTracking.forOwner(reopened.owner)
        val writes = reopened.storage.writes; val prefs = reopened.raw()
        rejected(controlTestTimeout("retry after reopen") { ControlRecordStore(reopened.owner).retryTermination(r.c, d.closure) },
            CompletionRejectionReason.WrongTrackerLifetime)
        assertEquals(writes, reopened.storage.writes); assertEquals(prefs, reopened.raw())
        assertEquals("own kept", 1, ownIn(prefs, r.c))
        assertTrue("new tracker U/P empty", newTracker.recoverySnapshot().unresolvedCommands.isEmpty() &&
            newTracker.recoverySnapshot().pendingReleases.isEmpty())
    }
}
