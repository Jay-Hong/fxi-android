package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
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
 * Claude-owned 6-4bC1d-3b contract (T7 end to end; 6-4bC1d consensus r1 D4 and D6; design 6-4bC1d3_design_codex.r1.md). Test only.
 * An old Mutations command left OnceConfirm·U hands over after its source has been processed by a real successor and the record
 * has moved on further:
 *  - HOLD: 5d (RECOVER_HOLD), then guard rewrites (recordFloor, UPDATE_AUTH), then the 5d journal settled (completePurges) and a
 *    further KRX epoch rotation (beginRotation). SOURCE L/N are completed and consumed; FLOOR L/N submit the chain to the latest
 *    guard. The journal settlement and the rotation are not floor links: they only coexist.
 *  - RECOVERY_INTENT: 5e (RECOVER_INTENT), then journal settlement and rotation; SOURCE L/N completed and consumed; no FLOOR slot.
 *  - Mixed old command: a HOLD Add with an unrelated REQUEST's no-op Edit, whose REQUEST L/N are retained on that row (a
 *    record holds one guard, so the same-guard case is C1d-3a's).
 *  - handoffAfterUncertainConfirm: G11, unrelated U/P, management-Confirm faults and fixed-descriptor retry, returned-snapshot
 *    contamination, a real reopen, and re-calls of the terminated old ref.
 * Successor completion is recorded only after the successor's Confirmed result and the source's absence are asserted.
 * The implementation thread reads but does not edit this file.
 */
class MutationsRecoverySuccessorEndToEndContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    private val extra = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runReleaseTest { controlTestTimeout("C1d-3b cleanup", 30_000) { (extra + fx.storage).forEach { it.close() } } }
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }
    private fun owner(c: CommandRef) = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")

    // ── old command ──────────────────────────────────────────────────────────────────────────────────────────────────
    private enum class Fault { AFTER_SCOPE, BEFORE }
    /** The business Confirm lands and returns failed (AFTER_SCOPE, own written) or fails before writing (BEFORE, nothing landed). */
    private suspend fun oldU(vararg actions: ControlMutation, fault: Fault, own: Int): CommandRef {
        val c = fx.store.prepare(*actions)
        if (own == 0) fx.armReadBack() // a no-op Confirm reaches its write only with a read-back obligation
        if (fault == Fault.AFTER_SCOPE) fx.storage.storage.afterScope = true else fx.storage.storage.before = true
        val r = controlTestTimeout("old execute") { fx.store.execute(c) }
        assertTrue("fixture: old Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: old in U", c in fx.tracker.snapshot())
        assertEquals("fixture: old own Applied", own, ownIn(fx.disk(), c))
        return c
    }
    private fun holdJson() = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30_000, "boot", 10_000, "life")).toPayloadEntry().fields.toString()
    private fun holdAdd() = fx.store.addition(ControlKind.HOLD) { id -> literal(holdJson()); set("id", ControlScalar.Text(id)) }
    private fun idOf(a: ControlMutation) = (a as ControlMutation.Add).proposedId
    private suspend fun seedHold(guard: ControlNode?, holds: String = "[]", demand: List<ControlNode> = emptyList()) = controlTestTimeout("seed") {
        fx.storage.data.updateData { H.before(H.input(g = guard), siblings = false).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = holds
            if (demand.isNotEmpty()) this[ControlRecordKeys.payload(ControlKind.DEMAND)] =
                (listOfNotNull(guard) + demand).joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
        }.toPreferences() }
    }
    private suspend fun row(kind: ControlKind, id: String) = H.row(fx.disk(), kind, id)
    private suspend fun absent(id: String) = (ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported).locations(id).isEmpty()

    // ── successors ───────────────────────────────────────────────────────────────────────────────────────────────────
    private class Recover(val c: CommandRef, val confirmed: ControlStoreResult.Confirmed, val row: ControlNode, val oldGuard: ControlNode?) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val guardFixed get() = fixed.targets.single { it.role == LifecycleRole.GUARD }
        val guardAfter get() = checkNotNull(guardFixed.after)
        val destination get() = DestinationLocator.Guard(checkNotNull(guard(guardAfter)).id, GuardPart.FLOOR)
    }
    private suspend fun recoverHold(holdId: String, guardId: String?): Recover {
        val source = row(ControlKind.HOLD, holdId); val oldGuard = guardId?.let { row(ControlKind.DEMAND, it) }
        val input = RecoverHoldInput(source, oldGuard, FenceV1("A", "u", "k"), H.binding, H.restart(source, H.executor), H.now)
        val c = fx.store.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        val r = controlTestTimeout("5d") { fx.store.execute(c, H.context(input)) }
        assertTrue("fixture: 5d Confirmed, got $r", r is ControlStoreResult.Confirmed)
        assertTrue("fixture: HOLD $holdId removed by 5d", absent(holdId))
        return Recover(c, r as ControlStoreResult.Confirmed, source, oldGuard)
    }
    private fun lifecycleToken(c: CommandRef, confirmed: ControlStoreResult.Confirmed, target: LifecycleTarget): PriorStorageConfirmation {
        val t = PriorStorageConfirmation.confirmLifecycleOutput(c, (c.body as ControlCommandBody.Lifecycle).input, confirmed, target)
        assertTrue("fixture: output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        return (t as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun linked(r: NamedTransferResult): NamedTransferLink {
        assertTrue("fixture: link issued, got $r", r is NamedTransferResult.Issued)
        return (r as NamedTransferResult.Issued).value
    }
    private fun parsedHold(row: ControlNode) = (ControlObligations.read(ControlKind.HOLD, row) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun output(r: Recover) = TypedDestinationTuple.GuardFloor(r.destination, r.guardAfter, checkNotNull(guard(r.guardAfter)))
    private class Step(val token: PriorStorageConfirmation, val link: NamedTransferLink, val after: ControlNode)
    private fun holdStep(r: Recover): Step {
        val t = lifecycleToken(r.c, r.confirmed, r.guardFixed.target)
        val link = linked(NamedTransferLink.linkNamedTransfer(TypedSourceTuple.HoldFloor(owner(r.c),
            FloorSource(parsedHold(r.row).id, r.row, parsedHold(r.row), checkNotNull(parsedHold(r.row).floor), r.oldGuard), r.oldGuard, H.now, H.life),
            output(r), t))
        return Step(t, link, r.guardAfter)
    }
    private fun guardSource(c: CommandRef, preimage: ControlNode) = TypedSourceTuple.GuardFloor(owner(c), preimage, checkNotNull(guard(preimage)))
    private suspend fun floorStep(previous: ControlNode, wait: Long = 31_000): Step {
        val m = fx.store.prepare(fx.store.recordFloor(previous, BootReading("boot", 12_000), wait, H.life))
        val r = controlTestTimeout("recordFloor") { fx.store.execute(m) }
        assertTrue("fixture: recordFloor Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val t = PriorStorageConfirmation.confirmMutationFloorOutput(m, 0, r as ControlStoreResult.Confirmed)
        assertTrue("fixture: floor token, got $t", t is MutationFloorOutputConfirmationResult.Issued)
        val token = (t as MutationFloorOutputConfirmationResult.Issued).value
        val out = (token.binding as ConfirmationBinding.MutationFloorOutput).output
        return Step(token, linked(NamedTransferLink.linkRecordedFloorTransfer(guardSource(m, previous), out, token)), out.row)
    }
    /** UPDATE_AUTH from a Caller at boot 60000 (the floor has expired: no retry REQUEST); auth changes, floor kept. */
    private suspend fun callerStep(previous: ControlNode): Step {
        val q = DemandAuthFixtures
        val caller = LifecycleCaller("later", LifecycleCallerOrigin.CALLER, q.binding, LifecycleOrderGrant(q.life, 20, 1, 0, 21),
            com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, BootReading("boot", 60_000))
        val u = fx.store.prepareUpdateAuth(previous, null, q.binding, LifecycleAuthEvent.Caller(caller), LifecycleOrderSource(q.life, 21))
        val r = controlTestTimeout("update auth") { fx.store.execute(u, q.context(q.runtime(caller = caller))) }
        assertTrue("fixture: UPDATE_AUTH Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val fixed = (u.body as ControlCommandBody.Lifecycle).input
        val token = lifecycleToken(u, r as ControlStoreResult.Confirmed, fixed.targets.single { it.role == LifecycleRole.GUARD }.target)
        val out = (token.binding as ConfirmationBinding.LifecycleOutput).output as TypedDestinationTuple.GuardFloor
        return Step(token, linked(NamedTransferLink.linkNamedTransfer(guardSource(u, previous), out, token)), out.row)
    }
    private val lifecycleIntent = """{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k0"}"""
    private suspend fun recoverIntent(id: String) {
        val life = LifetimeId("new-life")
        val source = row(ControlKind.RECOVERY_INTENT, id)
        val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")
        val closure = HoldRecoveryClosure.AfterRestart(source, binding.executor, "old-tracking", true, true)
        val c = fx.store.prepareRecoverIntent(RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure), LifecycleOrderSource(life, 21))
        val r = controlTestTimeout("5e") { fx.store.execute(c, AttemptContext("A", 3, life, false, false,
            intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure))) }
        assertTrue("fixture: 5e Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val fixed = (c.body as ControlCommandBody.Lifecycle).input
        val namespace = checkNotNull(fixed.namespace)
        val actual = fx.disk()
        val epochs = (fx.storage.owner as DataStoreAccessEpochStore).load()
        assertTrue("fixture: intent $id removed by 5e", absent(id))
        assertEquals("5e exact journal", namespace.journal, epochs.pendingPurges)
        assertEquals("5e KRX epoch", namespace.after.krxCapabilityEpoch, actual[DataStoreAccessEpochStore.KRX_EPOCH])
        // The marker follows 5e's namespace postcondition; when it declares none the seeded value (true) stays.
        assertEquals("5e marker", namespace.krxMayContain ?: true, actual[DataStoreAccessEpochStore.MAY_CONTAIN_KRX])
        val request = fixed.targets.single { it.role == LifecycleRole.REQUEST }
        assertEquals("5e REQUEST", checkNotNull(request.after).toPayloadEntry().fields,
            row(ControlKind.DEMAND, request.target.id).toPayloadEntry().fields)
    }
    /** The pending purge of the CAPABILITY axis settled, then a further KRX epoch rotation; both kept by the handoff. */
    private suspend fun settleAndRotate() {
        val epochs = fx.storage.owner as DataStoreAccessEpochStore
        val before = controlTestTimeout("load") { epochs.load() }
        val capability = before.pendingPurges.filter { PurgeScope.CAPABILITY in it.scopes }
        assertTrue("fixture: a pending CAPABILITY purge to settle, got ${before.pendingPurges}", capability.isNotEmpty())
        val settled = controlTestTimeout("complete purges") { epochs.completePurges(capability) }
        assertTrue("fixture: settled", settled.pendingPurges.none { it in capability })
        val rotated = controlTestTimeout("rotate") { epochs.beginRotation(rotateUser = false, rotateKrx = true) }
        assertTrue("fixture: KRX rotated", rotated.krxCapabilityEpoch != settled.krxCapabilityEpoch)
    }
    private fun intentRaw(): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM] = true; this[DataStoreAccessEpochStore.MAY_CONTAIN_KRX] = true
    }.toPreferences()
    private suspend fun seedRaw(p: Preferences) = controlTestTimeout("raw seed") { fx.storage.data.updateData { p } }

    // ── B1 ───────────────────────────────────────────────────────────────────────────────────────────────────────────
    private inner class B1(val c: CommandRef) {
        val o = owner(c)
        val h = HandoffCoordinator(c, o, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, o)
        val input = RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val required = (deriveRequiredObligations(input) as RequirementDerivation.Available).orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun of(index: Int, component: ObligationComponent, branch: LandingBranch) = required.single {
            it.key.role == ObligationRole.MutationAction(index) && it.key.component == component && it.key.branch == branch }
        fun complete(index: Int, component: ObligationComponent) = listOf(L, N).forEach { branch -> val s = of(index, component, branch)
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, s.key, ComponentCompletion(s.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, s.key)) }
        fun floorChain(index: Int, steps: List<Step>, branches: List<LandingBranch> = listOf(L, N)) = branches.forEach { branch ->
            assertEquals(RecordResult.Recorded, h.recordConfirmedMutationFloorTransfer(b, of(index, ObligationComponent.FLOOR, branch),
                HandoffDisposition.DurablyOwned(steps.last().link.destination.locator, steps.map { it.link }, steps.last().token))) }
        suspend fun retained(index: Int, component: ObligationComponent, d: DestinationLocator) = listOf(L, N).forEach { branch ->
            val s = of(index, component, branch)
            val t = PriorStorageConfirmation.confirmRetainedSource(s, d, controlTestTimeout("locked read") {
                fx.storage.owner.transactRecord { raw -> com.jay.fxi.data.entitlements.RecordTransactionDecision.Observe(
                    ControlRecordReader().read(raw) as ControlRecordRead.Supported) } })
            assertTrue("fixture: retained $component token, got $t", t is RetainedSourceConfirmationResult.Issued)
            assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(b, s.key,
                HandoffDisposition.DurablyOwned(d, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value))) }
        suspend fun issue(): HandoffIssueResult.Issued {
            val r = h.closeJoinAndIssueHandoff(input)
            assertTrue("fixture: B1 issued, got $r", r is HandoffIssueResult.Issued)
            return r as HandoffIssueResult.Issued
        }
    }

    // ── handoff observations ─────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun handoff(c: CommandRef, i: HandoffIssueResult.Issued) =
        controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, i.closure, i.handoff) }
    private suspend fun retry(c: CommandRef, i: HandoffIssueResult.Issued) = controlTestTimeout("retry") { store().retryTermination(c, i.closure) }
    private fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
    /** Only the old command's own Applied removed; every other value (journal, epochs, markers, REQUESTs, guard) unchanged. */
    private suspend fun completed(r: ControlCompletionResult, c: CommandRef, before: Preferences, ownBefore: Int) {
        assertEquals("own before", ownBefore, ownIn(before, c))
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only the old command's Applied removed",
            JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }), evidence(after))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertNull("left commands", fx.tracker.findPrepared(c))
    }
    private fun pendingConfirming(r: ControlCompletionResult) = assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r",
        r is ControlCompletionResult.Unconfirmed && r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
    private fun descriptorOf(c: CommandRef): TerminationPendingDescriptor.MutationsHandoff {
        val d = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("fixed MutationsHandoff, got $d", d is TerminationPendingDescriptor.MutationsHandoff)
        return d as TerminationPendingDescriptor.MutationsHandoff
    }
    private suspend fun refusedAtOwner(c: CommandRef, i: HandoffIssueResult.Issued, check: (ControlCompletionResult) -> Unit) {
        fx.armReadBack() // any management Confirm would write the barrier: whole-record equality = Confirm 0
        val before = fx.disk(); val writes = fx.storage.storage.writes; val own = ownIn(before, c)
        check(handoff(c, i))
        assertEquals("no write", writes, fx.storage.storage.writes)
        assertEquals("record unchanged", before, fx.disk())
        assertEquals("own kept", own, ownIn(fx.disk(), c))
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        val w = fx.tracker.recoverySnapshot(); assertTrue("RETAINED in U, not in P", c in w.unresolvedCommands && c !in w.pendingReleases)
    }

    // ── the HOLD end-to-end state (H1) ───────────────────────────────────────────────────────────────────────────────
    private inner class HoldState(val c: CommandRef, val i: HandoffIssueResult.Issued)
    /** Old HOLD Add·U → 5d → recordFloor → UPDATE_AUTH(Caller) → journal settled → KRX rotated; B1 issues SOURCE completed + FLOOR chain. */
    private suspend fun holdEndToEnd(): HoldState {
        seedHold(H.guard())
        val a = holdAdd(); val c = oldU(a, fault = Fault.AFTER_SCOPE, own = 1)
        val r = recoverHold(idOf(a), "g")
        val s0 = holdStep(r); val s1 = floorStep(s0.after); val s2 = callerStep(s1.after)
        settleAndRotate()
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE); b1.floorChain(0, listOf(s0, s1, s2))
        return HoldState(c, b1.issue())
    }

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun H1_holdRecoveredRewrittenSettledRotated_transferred() = runReleaseTest {
        val s = holdEndToEnd(); val before = fx.disk()
        completed(handoff(s.c, s.i), s.c, before, 1)
    }

    /** H2: the no-op HOLD Edit's business Confirm failed before writing (own 0); the pre-existing HOLD is recovered. */
    @Test fun H2_holdNoopEditNotLanded_recoveredAndRewritten_transferred() = runReleaseTest {
        val hold = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30_000, "boot", 10_000, "life"))
        seedHold(H.guard(), holds = "[${hold.toPayloadEntry().fields}]")
        val c = oldU(fx.store.edit(ControlKind.HOLD, hold) {}, fault = Fault.BEFORE, own = 0)
        val r = recoverHold("h", "g"); val s0 = holdStep(r); val s1 = floorStep(s0.after)
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE); b1.floorChain(0, listOf(s0, s1))
        val i = b1.issue(); val before = fx.disk()
        completed(handoff(c, i), c, before, 0)
    }

    @Test fun I1_intentRecoveredSettledRotated_transferred() = runReleaseTest {
        seedRaw(intentRaw())
        val a = fx.store.addition(ControlKind.RECOVERY_INTENT) { id -> literal(lifecycleIntent); set("id", ControlScalar.Text(id)) }
        val c = oldU(a, fault = Fault.AFTER_SCOPE, own = 1)
        recoverIntent(idOf(a)); settleAndRotate()
        val b1 = B1(c); assertTrue("fixture: no FLOOR slot", b1.required.none { it.key.component == ObligationComponent.FLOOR })
        b1.complete(0, ObligationComponent.SOURCE)
        val i = b1.issue(); val before = fx.disk()
        completed(handoff(c, i), c, before, 1)
    }

    @Test fun I2_intentNoopEditNotLanded_recovered_transferred() = runReleaseTest {
        val source = ControlObligationFixtures.node(lifecycleIntent)
        seedRaw(intentRaw().toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[${source.toPayloadEntry().fields}]" }.toPreferences())
        val c = oldU(fx.store.edit(ControlKind.RECOVERY_INTENT, source) {}, fault = Fault.BEFORE, own = 0)
        recoverIntent("r")
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE)
        val i = b1.issue(); val before = fx.disk()
        completed(handoff(c, i), c, before, 0)
    }

    /** M1: HOLD Add with an unrelated REQUEST's no-op Edit (a record holds one guard): the REQUEST L/N retained on its row. */
    @Test fun M1_holdWithAnUnrelatedRequestEdit_retainedRequest_transferred() = runReleaseTest {
        val other = H.request("q", "A", com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE)
        seedHold(H.guard(), demand = listOf(other))
        val a = holdAdd(); val c = oldU(a, fx.store.edit(ControlKind.DEMAND, other) {}, fault = Fault.AFTER_SCOPE, own = 1)
        val r = recoverHold(idOf(a), "g"); val s0 = holdStep(r)
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE); b1.floorChain(0, listOf(s0))
        b1.retained(1, ObligationComponent.REQUEST, DestinationLocator.Payload(ControlKind.DEMAND, "q"))
        val i = b1.issue(); val before = fx.disk()
        completed(handoff(c, i), c, before, 1)
    }

    // ═══ negative ══════════════════════════════════════════════════════════════════════════════════════════════════════
    /** I3: the intent is still stored; only a rotation and settlement happened. SOURCE declared completed: G05 COMPLETED_CONFLICT. */
    @Test fun I3_intentStillStored_completionDeclared_g05CompletedConflict() = runReleaseTest {
        seedRaw(intentRaw())
        val a = fx.store.addition(ControlKind.RECOVERY_INTENT) { id -> literal(lifecycleIntent); set("id", ControlScalar.Text(id)) }
        val c = oldU(a, fault = Fault.AFTER_SCOPE, own = 1)
        controlTestTimeout("rotate") { (fx.storage.owner as DataStoreAccessEpochStore).beginRotation(rotateUser = false, rotateKrx = true) }
        settleAndRotate()
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE)
        val i = b1.issue()
        val read = ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported
        val index = read.arrays.getValue(ControlKind.RECOVERY_INTENT).entries.indexOfFirst {
            ((it as ControlEntryRead.Interpreted).value as ControlObligationV1).id == idOf(a) }
        val expected = listOf(L, N).map { branch -> val s = b1.of(0, ObligationComponent.SOURCE, branch)
            G05Failure(G05Id.COMPLETED_CONFLICT, s.key, G05Location.Fixed((s.requirement as SlotRequirement.Required).fixedSources.first().location),
                G05Location.Submitted(i.handoff.slots.indexOfFirst { it.key == s.key }), G05Location.ActualPayload(ControlKind.RECOVERY_INTENT, index)) }
        refusedAtOwner(c, i) { assertTrue("expected Rejected(G05), got $it",
            it is ControlCompletionResult.Rejected && it.reason == CompletionRejectionReason.G05(expected)) }
    }

    @Test fun M1b_unrelatedRequestNMissing_b1IncompleteSlots() = runReleaseTest {
        val other = H.request("q", "A", com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE)
        seedHold(H.guard(), demand = listOf(other))
        val a = holdAdd(); val c = oldU(a, fx.store.edit(ControlKind.DEMAND, other) {}, fault = Fault.AFTER_SCOPE, own = 1)
        val r = recoverHold(idOf(a), "g"); val s0 = holdStep(r)
        val b1 = B1(c); b1.complete(0, ObligationComponent.SOURCE); b1.floorChain(0, listOf(s0))
        val s = b1.of(1, ObligationComponent.REQUEST, L); val d = DestinationLocator.Payload(ControlKind.DEMAND, "q")
        val t = PriorStorageConfirmation.confirmRetainedSource(s, d, controlTestTimeout("locked read") {
            fx.storage.owner.transactRecord { raw -> com.jay.fxi.data.entitlements.RecordTransactionDecision.Observe(
                ControlRecordReader().read(raw) as ControlRecordRead.Supported) } }) as RetainedSourceConfirmationResult.Issued
        assertEquals(RecordResult.Recorded, b1.h.recordConfirmedTransfer(b1.b, s.key, HandoffDisposition.DurablyOwned(d, emptyList(), t.value)))
        val r2 = b1.h.closeJoinAndIssueHandoff(b1.input)
        assertTrue("expected Refused(INCOMPLETE_OR_INVALID_SLOTS), got $r2",
            r2 is HandoffIssueResult.Refused && r2.reason == HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    // ═══ G11 and unrelated refs ════════════════════════════════════════════════════════════════════════════════════════
    @Test fun G1_anotherRefDependsOnTheOldOwnApplied_dependencyPresent() = runReleaseTest {
        val s = holdEndToEnd()
        val m = fx.mutations()
        val dep = ControlReleaseFixtures.row(m, id = s.c.id, lifetime = s.c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, m, ReleasePendingDescriptor.ExactMutations(dep))
        refusedAtOwner(s.c, s.i) { assertEquals(CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(s.c.id, s.c.ownerTrackingLifetimeId.value)), (it as? ControlCompletionResult.Rejected)?.reason) }
    }

    @Test fun G2_unrelatedUAndPendingRefs_transferred_andTheyStay() = runReleaseTest {
        val s = holdEndToEnd()
        val u = fx.mutations(); fx.addUnresolved(u)
        val p = fx.mutations()
        val row = ControlReleaseFixtures.row(p, id = p.id, lifetime = p.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, p, ReleasePendingDescriptor.ExactMutations(row))
        val pDescriptor = fx.tracker.findPrepared(p)?.releaseDescriptor
        val before = fx.disk()
        completed(handoff(s.c, s.i), s.c, before, 1)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("u stays in U", u in w.unresolvedCommands)
        assertTrue("p stays in P", p in w.pendingReleases)
        assertSame("p's descriptor unchanged", pDescriptor, fx.tracker.findPrepared(p)?.releaseDescriptor)
    }

    // ═══ faults, retry, reopen ═════════════════════════════════════════════════════════════════════════════════════════
    /** F1: the management Confirm fails before writing; the successor moves on (another recordFloor); the retry completes. */
    @Test fun F1_managementConfirmFailsBeforeWrite_successorMovesOn_retryCompletes() = runReleaseTest {
        val s = holdEndToEnd()
        fx.storage.storage.before = true
        pendingConfirming(handoff(s.c, s.i))
        val d = descriptorOf(s.c); assertTrue("own fixed", d.expectedOwn != null)
        val w = fx.tracker.recoverySnapshot(); assertTrue("U∩P", s.c in w.unresolvedCommands && s.c in w.pendingReleases)
        floorStep(row(ControlKind.DEMAND, "g"), wait = 45_000) // the latest guard moves on; the retry does not re-require the first destination
        var seen: TerminationPendingDescriptor? = null
        fx.boundary.afterReturn = { p -> seen = fx.tracker.findPrepared(s.c)?.terminationDescriptor; p }
        val before = fx.disk()
        completed(retry(s.c, s.i), s.c, before, 1)
        assertSame("the fixed descriptor object", d, seen)
    }

    @Test fun F2_managementConfirmLandsReturnFails_retryCompletesAllAbsent() = runReleaseTest {
        val s = holdEndToEnd(); val before = fx.disk()
        fx.storage.storage.afterScope = true
        pendingConfirming(handoff(s.c, s.i))
        assertEquals("own already gone", 0, ownIn(fx.disk(), s.c))
        completed(retry(s.c, s.i), s.c, before, 1)
    }

    @Test fun F3_newDependentAfterPending_retryHeld_thenCompletes() = runReleaseTest {
        val s = holdEndToEnd(); val before = fx.disk()
        fx.storage.storage.before = true
        pendingConfirming(handoff(s.c, s.i))
        val d = descriptorOf(s.c)
        val m = fx.mutations()
        val dep = ControlReleaseFixtures.row(m, id = s.c.id, lifetime = s.c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, m, ReleasePendingDescriptor.ExactMutations(dep))
        val writes = fx.storage.storage.writes
        assertEquals(CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(s.c.id, s.c.ownerTrackingLifetimeId.value)), (retry(s.c, s.i) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("no management Confirm", writes, fx.storage.storage.writes)
        assertSame("descriptor kept", d, fx.tracker.findPrepared(s.c)?.terminationDescriptor)
        ControlReleaseFixtures.simulateReleased(fx.tracker, m)
        completed(retry(s.c, s.i), s.c, before, 1)
    }

    /** F4: the returned snapshot is contaminated (a surviving value changed); the check fails before terminal; retry completes. */
    @Test fun F4_returnedSnapshotContaminated_checkFails_retryCompletes() = runReleaseTest {
        val s = holdEndToEnd(); val before = fx.disk()
        fx.boundary.afterReturn = { p -> p.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[${H.hold().toPayloadEntry().fields}]"
        }
            .also { it[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[${ControlObligationFixtures.recovery}]" }.toPreferences() }
        val thrown = runCatching { store().handoffAfterUncertainConfirm(s.c, s.i.closure, s.i.handoff) }.exceptionOrNull()
        assertTrue("IllegalStateException, got $thrown", thrown is IllegalStateException)
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, s.c.lifecycleState)
        descriptorOf(s.c)
        val w = fx.tracker.recoverySnapshot(); assertTrue("U∩P", s.c in w.unresolvedCommands && s.c in w.pendingReleases)
        fx.boundary.afterReturn = null
        completed(retry(s.c, s.i), s.c, before, 1)
    }

    private suspend fun reopenedRetryRefused(s: HoldState, ownOnDisk: Int) {
        fx.storage.close()
        val reopened = ControlStoreTestStorage(fx.file).also { extra += it }
        val tracker = ControlCommandTracking.forOwner(reopened.owner)
        val writes = reopened.storage.writes; val prefs = reopened.raw()
        assertEquals(ownOnDisk, ownIn(prefs, s.c))
        val r = controlTestTimeout("retry after reopen") { ControlRecordStore(reopened.owner).retryTermination(s.c, s.i.closure) }
        assertEquals(CompletionRejectionReason.WrongTrackerLifetime, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals(writes, reopened.storage.writes); assertEquals(prefs, reopened.raw())
        assertTrue("new tracker U/P empty", tracker.recoverySnapshot().unresolvedCommands.isEmpty() && tracker.recoverySnapshot().pendingReleases.isEmpty())
    }
    @Test fun R1a_reopenAfterPrewriteFault_oldRefWrongTrackerLifetime() = runReleaseTest {
        val s = holdEndToEnd(); fx.storage.storage.before = true
        pendingConfirming(handoff(s.c, s.i)); reopenedRetryRefused(s, ownOnDisk = 1)
    }
    @Test fun R1b_reopenAfterLandedReturnFault_oldRefWrongTrackerLifetime() = runReleaseTest {
        val s = holdEndToEnd(); fx.storage.storage.afterScope = true
        pendingConfirming(handoff(s.c, s.i)); reopenedRetryRefused(s, ownOnDisk = 0)
    }

    /** R2: after success the old ref's business calls answer Terminated and a second handoff AlreadyTerminated, without access. */
    @Test fun R2_oldRefRecalls_terminatedWithoutStorageAccess() = runReleaseTest {
        val s = holdEndToEnd(); val before = fx.disk()
        completed(handoff(s.c, s.i), s.c, before, 1)
        val access = fx.boundary.accesses; val writes = fx.storage.storage.writes; val disk = fx.disk()
        for (r in listOf(controlTestTimeout("e1") { fx.store.execute(s.c) },
                controlTestTimeout("e2") { fx.store.execute(s.c, H.context(H.input())) },
                controlTestTimeout("cp") { fx.store.confirmPrevious(s.c) }))
            assertTrue("Terminated, got $r", r is ControlStoreResult.Terminated)
        assertTrue("AlreadyTerminated", handoff(s.c, s.i) is ControlCompletionResult.AlreadyTerminated)
        assertEquals("no access", access, fx.boundary.accesses)
        assertEquals("no write", writes, fx.storage.storage.writes)
        assertEquals("record unchanged", disk, fx.disk())
    }
}
