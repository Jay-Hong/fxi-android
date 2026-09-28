package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bC1c contract (X12; 6-4b skeleton r2 T8, 6-4bC1 consensus r1; design 6-4bC1c_contract_design_codex.r1.md).
 * Each plate starts where the existing rows stop (ControlRotationDependencyContractTest T6_74 for the rotation R, and
 * ControlSettlementConsumptionOwnerContractTest B2_22 for the settlement R, N and L): A stores seal x by a SEAL Add and is left
 * OnceConfirm·U by a return fault; the counterpart then settles x with a real Confirmed execute, and its consumption termination
 * is refused by G11 on A's SEAL x. This contract completes the path:
 *  - A's SEAL L and N are recorded in B1 as completed and consumed only after the counterpart is Confirmed and x is settled;
 *    handoffAfterUncertainConfirm then removes only A's Mutations Applied row and terminates A;
 *  - the counterpart's existing consumption termination (completeAfterConsumption / completeSettlementAfterConsumption) then
 *    completes, removing its own Applied row and seal x;
 *  - A's execute (both overloads) and confirmPrevious then answer Terminated without storage access, and x is not recreated.
 * Every plate is separate; one does not stand in for another. Negative rows: completion declared on the still-active x, and
 * retained tokens issued on the active x then made stale by the settlement. Fault rows: A's management Confirm returns after
 * landing (A retries on its fixed descriptor), and the counterpart's management Confirm fails before writing (it retries).
 * The implementation thread reads but does not edit this file.
 */
class ControlX12HandoffConsumptionContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("X12 cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val now = BootReading("boot", 20_000)
    private val N = NamespaceSettlementFixtures
    private val R = RetiredNamespaceFixtures
    private val NN = CurrentNullFixtures
    private val L = RetiredNullFixtures

    // ── plates ───────────────────────────────────────────────────────────────────────────────────────────────────────
    private enum class Plate { ROTATION_R, SETTLEMENT_R, SETTLEMENT_N, SETTLEMENT_L }

    /** A stored x and is OnceConfirm·U; the counterpart has not run yet. */
    private inner class Board(val plate: Plate, val f: TerminationFixture, val a: CommandRef, val x: String, private val stored: ControlNode,
        private val restoreEpochs: (suspend () -> Unit)?) {
        lateinit var c: CommandRef
        lateinit var context: AttemptContext
        val handoffStore get() = ControlRecordStore(f.storage.owner, bootReadingSource = BootReadingSource { now })
        /** The counterpart settles x with a real Confirmed execute. */
        suspend fun runCounterpart() {
            restoreEpochs?.invoke()
            val (ref, ctx) = when (plate) {
                Plate.ROTATION_R -> {
                    val input = N.input(targets = listOf(stored))
                    CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId) to N.context
                }
                Plate.SETTLEMENT_R -> R.spec(target = stored).let {
                    CommandRef(it.operationId, ControlCommandBody.SettleRetiredNamespace(it), f.tracker.lifetimeId) to R.context }
                Plate.SETTLEMENT_N -> NN.spec(target = stored).let {
                    CommandRef(it.operationId, ControlCommandBody.RotateAndSettleCurrentNull(it), f.tracker.lifetimeId) to NN.context }
                Plate.SETTLEMENT_L -> L.spec(target = stored).let {
                    CommandRef(it.operationId, ControlCommandBody.SettleRetiredNull(it), f.tracker.lifetimeId) to L.context }
            }
            c = f.tracker.registerPrepared(ref); context = ctx
            val r = controlTestTimeout("counterpart execute") { f.store.execute(c, context) }
            assertTrue("fixture $plate: counterpart Confirmed, got $r", r is ControlStoreResult.Confirmed)
            val witness = settlementOf(x) as? JsonObject
            assertEquals("fixture $plate: x settled by counterpart", c.id,
                witness?.get("operationId")?.jsonPrimitive?.content)
        }
        suspend fun consume(): ControlCompletionResult = controlTestTimeout("consume") {
            if (plate == Plate.ROTATION_R) f.store.completeAfterConsumption(c, closure(c), declared)
            else f.store.completeSettlementAfterConsumption(c, closure(c), declared)
        }
        suspend fun retryCounterpart() = controlTestTimeout("counterpart retry") { f.store.retryTermination(c, closure(c)) }
        suspend fun seals() = Json.parseToJsonElement(checkNotNull(f.disk()[sealKey])) as JsonArray
        suspend fun settlementOf(id: String) = seals().single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject["settlement"]
        suspend fun sealIndex(id: String) = seals().indexOfFirst { it.jsonObject.getValue("id").jsonPrimitive.content == id }.also { check(it >= 0) }
        suspend fun xPresent() = seals().any { it.jsonObject.getValue("id").jsonPrimitive.content == x }
    }

    private suspend fun board(plate: Plate): Board {
        val f = fixture()
        val (raw, literalSeal, uuid) = when (plate) {
            Plate.ROTATION_R -> Triple(N.raw("[]"), N.user, UUID(0, 99))
            Plate.SETTLEMENT_R -> Triple(R.raw(), N.user, UUID(0, 197))
            Plate.SETTLEMENT_N -> Triple(NN.raw(), NN.nullUser, UUID(0, 198))
            Plate.SETTLEMENT_L -> Triple(L.raw(L.spec()), L.nullUser, UUID(0, 199))
        }
        f.edit { it.clear(); it += raw.toMutablePreferences().apply { this[sealKey] = "[]" }.toPreferences() }
        // As in B2_22: the retired-namespace settlement's NAMESPACE append must be current at Add time; the epochs move on
        // (another operation's rotation) before the settlement runs.
        var restore: (suspend () -> Unit)? = null
        if (plate == Plate.SETTLEMENT_R) {
            val settled = raw[DataStoreAccessEpochStore.USER_EPOCH] to raw[DataStoreAccessEpochStore.KRX_EPOCH]
            f.edit { p -> p[DataStoreAccessEpochStore.USER_EPOCH] = "u"; p[DataStoreAccessEpochStore.KRX_EPOCH] = "k" }
            restore = { f.edit { p -> p[DataStoreAccessEpochStore.USER_EPOCH] = checkNotNull(settled.first)
                p[DataStoreAccessEpochStore.KRX_EPOCH] = checkNotNull(settled.second) } }
        }
        val a = f.store.prepare(ControlMutation.Add.prepare(ControlKind.SEAL, uuid) { id -> literal(literalSeal); set("id", ControlScalar.Text(id)) })
        f.storage.storage.afterScope = true
        val ra = controlTestTimeout("A execute") { if (plate == Plate.ROTATION_R) f.store.execute(a, N.context) else f.store.execute(a) }
        assertTrue("fixture $plate: A Unconfirmed, got $ra", ra is ControlStoreResult.Unconfirmed)
        assertTrue("fixture $plate: A in U", f.tracker.isUnresolved(a))
        val arr = Json.parseToJsonElement(checkNotNull(f.disk()[sealKey])) as JsonArray
        val stored = arr.single()
        val x = uuid.toString()
        assertEquals("fixture $plate: A stored x", x, stored.jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("fixture $plate: A's own Applied", 1, ownIn(f.disk(), a))
        return Board(plate, f, a, x, node(stored.toString()), restore)
    }
    /** From the board up to the counterpart's first consumption, refused by G11 on A's SEAL x. */
    private suspend fun refusedStart(plate: Plate): Board = board(plate).also { b -> b.runCounterpart(); refusedByA(b) }

    // ── A's B1 declaration ───────────────────────────────────────────────────────────────────────────────────────────
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>)

    /** A's SEAL L and N: completed and consumed (done) or retained tokens on the active x. */
    private suspend fun declareA(b: Board, done: Boolean): Declared {
        val body = b.a.body as ControlCommandBody.Mutations
        val input = RequirementInput.Mutations(b.a, body, MutationAdoption.Current(checkNotNull(b.f.tracker.findPrepared(b.a)).targets.get()))
        val av = deriveRequiredObligations(input) as RequirementDerivation.Available
        val required = av.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertEquals("fixture: SEAL L and N only", listOf(LandingBranch.L, LandingBranch.N),
            required.map { assertEquals(ObligationComponent.SEAL, it.key.component); it.key.branch })
        val owner = ResponsibilityOwner(b.a.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(b.a, owner, HandoffEntryCloser { true }, 300); val e = HandoffEventBinding(b.a, owner)
        val locked = if (done) null else controlTestTimeout("locked read") {
            b.f.storage.owner.transactRecord { raw -> com.jay.fxi.data.entitlements.RecordTransactionDecision.Observe(
                ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        val dest = DestinationLocator.Payload(ControlKind.SEAL, b.x)
        for (slot in required) if (done) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(e, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(e, slot.key))
        } else {
            val t = PriorStorageConfirmation.confirmRetainedSource(slot, dest, checkNotNull(locked))
            assertTrue("fixture: token on active x, got $t", t is RetainedSourceConfirmationResult.Issued)
            assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(e, slot.key,
                HandoffDisposition.DurablyOwned(dest, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
        }
        val issued = h.closeJoinAndIssueHandoff(input)
        assertTrue("fixture: B1 issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Declared(issued.closure, issued.handoff, required)
    }
    private suspend fun handoffA(b: Board, d: Declared) =
        controlTestTimeout("A handoff") { b.handoffStore.handoffAfterUncertainConfirm(b.a, d.closure, d.handoff) }

    // ── expectations ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()

    /** The counterpart's consumption is refused by G11 on A's SEAL x: RETAINED, no descriptor, record and sets untouched (Confirm 0). */
    private suspend fun refusedByA(b: Board) {
        b.f.armReadBack()
        val before = b.f.storage.raw(); val work = b.f.tracker.recoverySnapshot()
        val writes = b.f.storage.storage.writes
        val aView = b.a.captureStateAndBody(); val cView = b.c.captureStateAndBody()
        val aDescriptor = b.f.history(b.a).terminationDescriptor
        val r = b.consume()
        assertEquals("${b.plate}: G11 on A's SEAL x, got $r", CompletionRejectionReason.DependencyPresent(b.a.id, b.a.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, b.x)), (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals(ControlCommandLifecycle.RETAINED, b.c.lifecycleState)
        assertNull("no descriptor", b.f.history(b.c).terminationDescriptor)
        assertEquals("sets kept", work, b.f.tracker.recoverySnapshot())
        assertEquals("record untouched", before, b.f.storage.raw())
        assertEquals("no Confirm write", writes, b.f.storage.storage.writes)
        assertEquals("A state unchanged", aView.state, b.a.lifecycleState)
        assertSame("A body unchanged", aView.body, b.a.captureStateAndBody().body)
        assertSame("A descriptor unchanged", aDescriptor, b.f.history(b.a).terminationDescriptor)
        assertEquals("counterpart state unchanged", cView.state, b.c.lifecycleState)
        assertSame("counterpart body unchanged", cView.body, b.c.captureStateAndBody().body)
    }
    /** A terminated with only its own Mutations Applied row removed (every other value, x included, unchanged). */
    private suspend fun aTransferred(b: Board, r: ControlCompletionResult, before: Preferences, ownBefore: Int) {
        assertEquals("A own before", ownBefore, ownIn(before, b.a))
        assertTrue("${b.plate}: A Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = b.f.disk()
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only A's Applied removed", JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(b.a.id) }),
            evidence(after))
        assertTrue("x kept for the counterpart", b.xPresent())
        assertEquals(ControlCommandLifecycle.TERMINATED, b.a.lifecycleState)
        assertNull("A body released", b.a.captureStateAndBody().body)
        val w = b.f.tracker.recoverySnapshot()
        assertTrue("A left U and P", b.a !in w.unresolvedCommands && b.a !in w.pendingReleases)
        assertNull("A left commands", b.f.tracker.findPrepared(b.a))
    }
    /** The counterpart consumed: exactly its own Applied row and seal x removed, every other value unchanged. */
    private suspend fun counterpartConsumed(b: Board, r: ControlCompletionResult, before: Preferences) {
        assertTrue("${b.plate}: counterpart Completed(Consumed), got $r", r is ControlCompletionResult.Completed && r.mode == CompletionMode.Consumed)
        val expected = before.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(b.c.id) }).toString()
            this[sealKey] = JsonArray((Json.parseToJsonElement(checkNotNull(before[sealKey])) as JsonArray).filterNot {
                it.jsonObject.getValue("id").jsonPrimitive.content == b.x }).toString()
            remove(ControlStoreTestStorage.BARRIER)
        }.toPreferences()
        assertEquals("exact deletion", expected, withoutBarrier(b.f.disk()))
        assertEquals(ControlCommandLifecycle.TERMINATED, b.c.lifecycleState)
        assertNull("counterpart body released", b.c.captureStateAndBody().body)
    }
    /** A's three re-calls answer Terminated with no storage access, no write and no seal recreated. */
    private suspend fun aRecallsInert(b: Board) {
        val access = b.f.boundary.accesses; val writes = b.f.storage.storage.writes; val disk = b.f.disk()
        val results = listOf(
            controlTestTimeout("A execute") { b.f.store.execute(b.a) },
            controlTestTimeout("A execute ctx") { b.f.store.execute(b.a, b.context) },
            controlTestTimeout("A confirmPrevious") { b.f.store.confirmPrevious(b.a) })
        for (r in results) assertTrue("${b.plate}: Terminated, got $r", r is ControlStoreResult.Terminated)
        assertEquals("no storage access", access, b.f.boundary.accesses)
        assertEquals("no write", writes, b.f.storage.storage.writes)
        assertEquals("record unchanged", disk, b.f.disk())
        assertTrue("x not recreated", !b.xPresent())
    }
    private fun g05Pair(d: Declared, id: G05Id, actual: G05Location?) = d.required.map { s ->
        G05Failure(id, s.key, G05Location.Fixed(FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.WHOLE)),
            G05Location.Submitted(d.handoff.slots.indexOfFirst { it.key == s.key }), actual) }
    /** A's first entry refused after one owner access: nothing published, A still U with its own Applied. */
    private suspend fun aRefused(b: Board, d: Declared, expected: CompletionRejectionReason) {
        val access = b.f.boundary.accesses; val writes = b.f.storage.storage.writes; val disk = b.f.disk()
        val work = b.f.tracker.recoverySnapshot()
        val refs = b.f.tracker.dependencyCandidatesExcluding(null)
        val views = refs.associateWith { it.captureStateAndBody() }
        val prepared = refs.associateWith { b.f.tracker.findPrepared(it) }
        val descriptors = refs.associateWith { b.f.tracker.findPrepared(it)?.terminationDescriptor }
        val r = handoffA(b, d)
        assertTrue("${b.plate}: expected Rejected($expected), got $r", r is ControlCompletionResult.Rejected && r.reason == expected)
        assertEquals("one owner access", access + 1, b.f.boundary.accesses)
        assertEquals("no write", writes, b.f.storage.storage.writes)
        assertEquals("record unchanged", disk, b.f.disk())
        assertTrue("A still U", b.f.tracker.isUnresolved(b.a))
        assertEquals(ControlCommandLifecycle.RETAINED, b.a.lifecycleState)
        assertNull("no descriptor", b.f.history(b.a).terminationDescriptor)
        assertEquals("sets unchanged", work, b.f.tracker.recoverySnapshot())
        assertEquals("refs unchanged", refs.toSet(), b.f.tracker.dependencyCandidatesExcluding(null).toSet())
        for (ref in refs) {
            assertEquals("state unchanged", views.getValue(ref).state, ref.lifecycleState)
            assertSame("body unchanged", views.getValue(ref).body, ref.captureStateAndBody().body)
            assertSame("registration unchanged", prepared[ref], b.f.tracker.findPrepared(ref))
            assertSame("descriptor unchanged", descriptors[ref], b.f.tracker.findPrepared(ref)?.terminationDescriptor)
        }
    }

    // ═══ P: the full path ══════════════════════════════════════════════════════════════════════════════════════════════
    private suspend fun fullPath(plate: Plate) {
        val b = refusedStart(plate)
        val d = declareA(b, done = true)
        val beforeA = b.f.disk()
        aTransferred(b, handoffA(b, d), beforeA, 1)
        val beforeC = b.f.disk()
        counterpartConsumed(b, b.consume(), beforeC)
        aRecallsInert(b)
    }
    @Test fun R_P_rotationR_fullPath() = runReleaseTest { fullPath(Plate.ROTATION_R) }
    @Test fun SR_P_settlementR_fullPath() = runReleaseTest { fullPath(Plate.SETTLEMENT_R) }
    @Test fun SN_P_settlementN_fullPath() = runReleaseTest { fullPath(Plate.SETTLEMENT_N) }
    @Test fun SL_P_settlementL_fullPath() = runReleaseTest { fullPath(Plate.SETTLEMENT_L) }

    // ═══ A: completion declared while x is still active ════════════════════════════════════════════════════════════════
    private suspend fun completedOnActive(plate: Plate) {
        val b = board(plate)
        val d = declareA(b, done = true)
        aRefused(b, d, CompletionRejectionReason.G05(g05Pair(d, G05Id.COMPLETED_CONFLICT, G05Location.ActualPayload(ControlKind.SEAL, b.sealIndex(b.x)))))
        assertTrue("active x kept", b.xPresent() && b.settlementOf(b.x) == null)
    }
    @Test fun R_A_rotationR_completedOnActiveX_g05CompletedConflict() = runReleaseTest { completedOnActive(Plate.ROTATION_R) }
    @Test fun SR_A_settlementR_completedOnActiveX_g05CompletedConflict() = runReleaseTest { completedOnActive(Plate.SETTLEMENT_R) }
    @Test fun SN_A_settlementN_completedOnActiveX_g05CompletedConflict() = runReleaseTest { completedOnActive(Plate.SETTLEMENT_N) }
    @Test fun SL_A_settlementL_completedOnActiveX_g05CompletedConflict() = runReleaseTest { completedOnActive(Plate.SETTLEMENT_L) }

    // ═══ S: retained tokens on the active x, made stale by the settlement ══════════════════════════════════════════════
    private suspend fun staleTokens(plate: Plate) {
        val b = board(plate)
        val d = declareA(b, done = false)
        b.runCounterpart()
        val at = G05Location.ActualPayload(ControlKind.SEAL, b.sealIndex(b.x))
        aRefused(b, d, CompletionRejectionReason.G05(g05Pair(d, G05Id.CONFIRMATION, at) + g05Pair(d, G05Id.LOWER_BOUND, at)))
        assertTrue("settled x kept", b.settlementOf(b.x) != null)
    }
    @Test fun R_S_rotationR_staleTokensAfterSettlement_g05ConfirmationAndLowerBound() = runReleaseTest { staleTokens(Plate.ROTATION_R) }
    @Test fun SR_S_settlementR_staleTokensAfterSettlement_g05ConfirmationAndLowerBound() = runReleaseTest { staleTokens(Plate.SETTLEMENT_R) }
    @Test fun SN_S_settlementN_staleTokensAfterSettlement_g05ConfirmationAndLowerBound() = runReleaseTest { staleTokens(Plate.SETTLEMENT_N) }
    @Test fun SL_S_settlementL_staleTokensAfterSettlement_g05ConfirmationAndLowerBound() = runReleaseTest { staleTokens(Plate.SETTLEMENT_L) }

    // ═══ FA: A's management Confirm lands but its return fails; A retries on its fixed descriptor ═════════════════════
    private suspend fun aReturnFault(plate: Plate) {
        val b = refusedStart(plate)
        val d = declareA(b, done = true)
        val beforeA = b.f.disk()
        b.f.storage.storage.afterScope = true
        val first = handoffA(b, d)
        assertTrue("${b.plate}: Unconfirmed(TERMINATION_PENDING), got $first",
            first is ControlCompletionResult.Unconfirmed && first.state == ControlCommandLifecycle.TERMINATION_PENDING)
        val descriptor = b.f.tracker.findPrepared(b.a)?.terminationDescriptor
        assertTrue("fixed MutationsHandoff", descriptor is TerminationPendingDescriptor.MutationsHandoff)
        val w = b.f.tracker.recoverySnapshot(); assertTrue("A in U∩P", b.a in w.unresolvedCommands && b.a in w.pendingReleases)
        assertEquals("A's Applied already gone", 0, ownIn(b.f.disk(), b.a))
        refusedByA(b) // A still pending: its SEAL Add still protects x
        var seen: TerminationPendingDescriptor? = null
        b.f.boundary.afterReturn = { p -> seen = b.f.tracker.findPrepared(b.a)?.terminationDescriptor; p }
        aTransferred(b, controlTestTimeout("A retry") { b.handoffStore.retryTermination(b.a, d.closure) }, beforeA, 1)
        assertSame("retry used the fixed descriptor object", descriptor, seen)
        val beforeC = b.f.disk()
        counterpartConsumed(b, b.consume(), beforeC)
        aRecallsInert(b)
    }
    @Test fun R_FA_rotationR_aReturnFault_retryThenConsume() = runReleaseTest { aReturnFault(Plate.ROTATION_R) }
    @Test fun SR_FA_settlementR_aReturnFault_retryThenConsume() = runReleaseTest { aReturnFault(Plate.SETTLEMENT_R) }
    @Test fun SN_FA_settlementN_aReturnFault_retryThenConsume() = runReleaseTest { aReturnFault(Plate.SETTLEMENT_N) }
    @Test fun SL_FA_settlementL_aReturnFault_retryThenConsume() = runReleaseTest { aReturnFault(Plate.SETTLEMENT_L) }

    // ═══ FC: after A terminated, the counterpart's management Confirm fails before writing; it retries ═══════════════
    private suspend fun counterpartWriteFault(plate: Plate) {
        val b = refusedStart(plate)
        val d = declareA(b, done = true)
        val beforeA = b.f.disk()
        aTransferred(b, handoffA(b, d), beforeA, 1)
        val beforeC = b.f.disk()
        b.f.storage.storage.before = true
        val first = b.consume()
        assertTrue("${b.plate}: counterpart Unconfirmed(TERMINATION_PENDING), got $first",
            first is ControlCompletionResult.Unconfirmed && first.state == ControlCommandLifecycle.TERMINATION_PENDING)
        assertEquals("nothing deleted yet", withoutBarrier(beforeC), withoutBarrier(b.f.disk()))
        counterpartConsumed(b, b.retryCounterpart(), beforeC)
        aRecallsInert(b)
    }
    @Test fun R_FC_rotationR_counterpartWriteFault_retryConsumes() = runReleaseTest { counterpartWriteFault(Plate.ROTATION_R) }
    @Test fun SR_FC_settlementR_counterpartWriteFault_retryConsumes() = runReleaseTest { counterpartWriteFault(Plate.SETTLEMENT_R) }
    @Test fun SN_FC_settlementN_counterpartWriteFault_retryConsumes() = runReleaseTest { counterpartWriteFault(Plate.SETTLEMENT_N) }
    @Test fun SL_FC_settlementL_counterpartWriteFault_retryConsumes() = runReleaseTest { counterpartWriteFault(Plate.SETTLEMENT_L) }
}
