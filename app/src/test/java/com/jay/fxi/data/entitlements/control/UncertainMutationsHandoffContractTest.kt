package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bC1a contract (6-4bC1 consensus r1, delivery-table amendment r2 C1 row): handoffAfterUncertainConfirm for a
 * current-lifetime OnceConfirm·U Mutations command, and its pending retry through retryTermination.
 *  - First entry: entrance prechecks without owner access; then one owner decision: G07, the injected clock once, G05 over the
 *    B1-issued declaration, the strict own/absent matcher, G11 on the matcher's Applied deletion identity; only then descriptor →
 *    P → pending → management Confirm; the returned snapshot is validated before terminal cleanup.
 *  - Success is Completed(ResponsibilityTransferred): only the command's own Mutations Applied row is removed; every other value
 *    (business rows included) is unchanged; the ref is TERMINATED with its body released and leaves U, P and commands.
 *  - Retry takes no new handoff: exact-present or all-absent of the fixed own evidence, latest integrity and new dependencies on
 *    the fixed identity; it never re-requires the first G05 destination. A held retry keeps the same descriptor object and makes
 *    no management Confirm.
 *  - Consensus D2: a real reopen (new owner and tracker) makes the old ref's retry WrongTrackerLifetime without storage access;
 *    recreating only the facade over the same owner still retries.
 * Fixtures: a request row "d" is edited (raisedAt 4 → 5) and the business Confirm lands but its return fails (exact own present;
 * the REQUEST is then consumed and both REQUEST slots are completed), or a no-op edit of "d" whose Confirm lands only the read
 * barrier (own absent; both REQUEST slots are transferred with retained confirmations on the untouched row). HandoffCoordinator
 * (B1) issues every declaration used for a positive row.
 * The implementation thread reads but does not edit this file.
 */
class UncertainMutationsHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    private val extra = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runReleaseTest { controlTestTimeout("C1a cleanup", 30_000) { (extra + fx.storage).forEach { it.close() } } }
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val requestD = DestinationLocator.Payload(ControlKind.DEMAND, "d")

    // ── OnceConfirm·U builders ───────────────────────────────────────────────────────────────────────────────────────
    private suspend fun seed() = fx.storage.seed(demand = "[$request]")
    private fun evidence(p: Preferences): JsonArray =
        Json.parseToJsonElement(p[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }

    /** A landed edit whose return failed: exact own present, c in U with its business Confirm requested. */
    private suspend fun landedEdit(): CommandRef {
        seed()
        val c = fx.store.prepare(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) })
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("execute landed") { fx.store.execute(c) }
        assertTrue("fixture: Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        assertEquals("fixture: exact own present", 1, ownIn(fx.disk(), c))
        return c
    }
    /** A no-op edit whose Confirm landed only the read barrier: own absent, c in U with its business Confirm requested. */
    private suspend fun noopEdit(): CommandRef {
        seed()
        val c = fx.store.prepare(fx.store.edit(ControlKind.DEMAND, node(request)) {})
        fx.armReadBack(); fx.storage.storage.afterScope = true
        val r = controlTestTimeout("execute noop") { fx.store.execute(c) }
        assertTrue("fixture: Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        assertEquals("fixture: own absent", 0, ownIn(fx.disk(), c))
        return c
    }
    private suspend fun consumeRequest() = fx.edit { it[ControlStoreTestStorage.DEMAND] = "[]" }

    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>)
    /** B1 issuance: REQUEST slots completed and consumed (landed, after consumption) or transferred with retained tokens (noop). */
    private suspend fun declare(c: CommandRef, completed: Boolean): Declared {
        val body = c.body as ControlCommandBody.Mutations
        val input = RequirementInput.Mutations(c, body, MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val required = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertTrue("fixture: only REQUEST slots", required.isNotEmpty() && required.all { it.key.component == ObligationComponent.REQUEST })
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, owner)
        val locked = if (completed) null else controlTestTimeout("locked read") {
            fx.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        for (slot in required) {
            if (completed) {
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
            } else {
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, requestD, checkNotNull(locked))
                assertTrue("fixture: token for ${slot.key}, got $t", t is RetainedSourceConfirmationResult.Issued)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(b, slot.key,
                    HandoffDisposition.DurablyOwned(requestD, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
            }
        }
        val issued = h.closeJoinAndIssueHandoff(input)
        assertTrue("fixture: B1 issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Declared(issued.closure, issued.handoff, required)
    }

    // ── observation ──────────────────────────────────────────────────────────────────────────────────────────────────
    private class Observed(val accesses: Int, val writes: Int, val prefs: Preferences, val state: ControlCommandLifecycle, val body: Any?,
        val unresolved: Set<CommandRef>, val pending: Set<CommandRef>, val descriptor: TerminationPendingDescriptor?)
    private suspend fun observe(c: CommandRef): Observed {
        val v = c.captureStateAndBody(); val w = fx.tracker.recoverySnapshot()
        return Observed(fx.boundary.accesses, fx.storage.storage.writes, fx.disk(), v.state, v.body, w.unresolvedCommands, w.pendingReleases,
            fx.tracker.findPrepared(c)?.terminationDescriptor)
    }
    /** Nothing changed (the entrance and every owner refusal). */
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
    private fun rejected(r: ControlCompletionResult, reason: CompletionRejectionReason) {
        assertTrue("expected Rejected($reason), got $r", r is ControlCompletionResult.Rejected && r.reason == reason)
    }
    /** Success: only c's own Applied removed (read barrier aside), terminal, body released, c out of U, P and commands. */
    private suspend fun completedWithOnlyOwnRemoved(r: ControlCompletionResult, c: CommandRef, before: Preferences) {
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only c's own Applied removed", JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }),
            evidence(after))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("c left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertNull("c left commands", fx.tracker.findPrepared(c))
    }

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P1_exactOwn_requestConsumedAndCompleted_responsibilityTransferred() = runReleaseTest {
        val c = landedEdit(); consumeRequest()
        val d = declare(c, completed = true)
        val before = fx.disk(); val writes = fx.storage.storage.writes
        completedWithOnlyOwnRemoved(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }, c, before)
        assertEquals("one management Confirm write", writes + 1, fx.storage.storage.writes)
        assertEquals("clock read once", 1, reads.get())
    }

    @Test fun P2_ownAbsent_requestTransferred_responsibilityTransferred() = runReleaseTest {
        val c = noopEdit()
        val d = declare(c, completed = false)
        val before = fx.disk()
        completedWithOnlyOwnRemoved(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }, c, before)
    }

    /** P3a: the management Confirm fails before writing; the fixed exact own is still present and a retry completes. */
    @Test fun P3a_retryWithFixedOwnStillPresent_completes() = runReleaseTest {
        val c = landedEdit(); consumeRequest()
        val d = declare(c, completed = true)
        val before = fx.disk()
        fx.storage.storage.before = true
        val first = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("pending, got $first", first is ControlCompletionResult.Unconfirmed && first.state == ControlCommandLifecycle.TERMINATION_PENDING)
        val descriptor = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("fixed MutationsHandoff with the exact own", descriptor is TerminationPendingDescriptor.MutationsHandoff && descriptor.expectedOwn != null)
        val w = fx.tracker.recoverySnapshot(); assertTrue("c in U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("own still present", 1, ownIn(fx.disk(), c))
        completedWithOnlyOwnRemoved(controlTestTimeout("retry") { store().retryTermination(c, d.closure) }, c, before)
    }

    /** P3b: the management delete lands but its return fails; the retry sees the fixed own all absent and completes. */
    @Test fun P3b_retryWithFixedOwnAllAbsent_completes() = runReleaseTest {
        val c = landedEdit(); consumeRequest()
        val d = declare(c, completed = true)
        val before = fx.disk()
        fx.storage.storage.afterScope = true
        val first = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("pending, got $first", first is ControlCompletionResult.Unconfirmed && first.state == ControlCommandLifecycle.TERMINATION_PENDING)
        assertEquals("own already removed", 0, ownIn(fx.disk(), c))
        completedWithOnlyOwnRemoved(controlTestTimeout("retry") { store().retryTermination(c, d.closure) }, c, before)
    }

    /** P4: an unrelated ref in U does not block and stays there. */
    @Test fun P4_unrelatedUnresolvedRef_completes_andItStays() = runReleaseTest {
        val c = noopEdit()
        val other = fx.mutations(); fx.addUnresolved(other)
        val d = declare(c, completed = false)
        val before = fx.disk()
        completedWithOnlyOwnRemoved(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }, c, before)
        assertTrue("other still in U", other in fx.tracker.snapshot())
    }

    /** P5a (consensus D2): a real reopen gives a new owner and tracker; the old ref's retry is WrongTrackerLifetime, no access. */
    @Test fun P5a_realReopen_oldRefRetry_wrongTrackerLifetime() = runReleaseTest {
        val c = landedEdit(); consumeRequest()
        val d = declare(c, completed = true)
        fx.storage.storage.before = true
        val first = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("pending, got $first", first is ControlCompletionResult.Unconfirmed)
        fx.storage.close()
        val reopened = ControlStoreTestStorage(fx.file).also { extra += it }
        val newTracker = ControlCommandTracking.forOwner(reopened.owner)
        val writes = reopened.storage.writes; val prefs = reopened.raw()
        val r = controlTestTimeout("retry after reopen") { ControlRecordStore(reopened.owner).retryTermination(c, d.closure) }
        rejected(r, CompletionRejectionReason.WrongTrackerLifetime)
        assertEquals(writes, reopened.storage.writes); assertEquals(prefs, reopened.raw())
        assertTrue("new tracker U/P empty", newTracker.recoverySnapshot().unresolvedCommands.isEmpty() && newTracker.recoverySnapshot().pendingReleases.isEmpty())
    }

    /** P5b: only the facade is recreated over the same owner; the shared tracker's pending ref retries to completion. */
    @Test fun P5b_facadeRecreatedOverSameOwner_retryCompletes() = runReleaseTest {
        val c = landedEdit(); consumeRequest()
        val d = declare(c, completed = true)
        val before = fx.disk()
        fx.storage.storage.before = true
        assertTrue(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) } is ControlCompletionResult.Unconfirmed)
        val facade = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { now })
        completedWithOnlyOwnRemoved(controlTestTimeout("retry via new facade") { facade.retryTermination(c, d.closure) }, c, before)
    }

    // ═══ negative: entrance (no owner access) ═══════════════════════════════════════════════════════════════════════════
    @Test fun N1_entrance_rejectedWithoutOwnerAccess() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        val confirmedRef = fx.mutations()
        assertTrue(controlTestTimeout("confirm other") { fx.store.execute(confirmedRef) } is ControlStoreResult.Confirmed)
        val neverConfirmed = fx.mutations(); fx.addUnresolved(neverConfirmed)
        val k = d.closure
        val cases = listOf<Triple<CommandRef, TerminationClosure, CompletionRejectionReason>>(
            Triple(CommandRef(c.id, c.body, OwnerTrackingLifetimeId.issue()), k, CompletionRejectionReason.WrongTrackerLifetime),
            Triple(CommandRef(c.id, c.body, c.ownerTrackingLifetimeId), k, CompletionRejectionReason.NotRegisteredIdentity),
            Triple(confirmedRef, TerminationClosures.of(confirmedRef), CompletionRejectionReason.NotUnresolved),
            Triple(neverConfirmed, TerminationClosures.of(neverConfirmed), CompletionRejectionReason.NotOnceConfirm),
            Triple(c, TerminationClosure(k.command, k.ownerTrackingLifetimeId, k.relatedScope, k.generationAtCapture, k.currentGeneration + 1,
                k.entriesClosed, k.captured, k.joined, k.registered, k.receiptsClosed, k.owner), CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.GenerationMismatch)))
        for ((ref, closure, reason) in cases) {
            val before = observe(c)
            rejected(controlTestTimeout("entrance $reason") { store().handoffAfterUncertainConfirm(ref, closure, d.handoff) }, reason)
            unchanged(before, observe(c), 0)
        }
        val forged = CompletionHandoff(d.handoff.command.copy(ref = confirmedRef), d.handoff.responsibilityOwner, d.handoff.slots)
        val before = observe(c)
        rejected(controlTestTimeout("declaration ref") { store().handoffAfterUncertainConfirm(c, k, forged) }, CompletionRejectionReason.DeclarationRefMismatch)
        unchanged(before, observe(c), 0)
    }

    // ═══ negative: owner decision ══════════════════════════════════════════════════════════════════════════════════════
    /** A declaration missing one slot: G05 coverage; nothing published. */
    @Test fun N2a_declarationMissingASlot_g05Coverage() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        val missing = d.required.first { it.key.branch == LandingBranch.L }
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, forged) }
        rejected(r, CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_L, missing.key,
            G05Location.Fixed((missing.requirement as SlotRequirement.Required).fixedSources.first().location), null, null))))
        unchanged(before, observe(c), 1)
    }

    /** An opaque obligation row: recovery, nothing published, no clock read. */
    @Test fun N2b_opaqueObligation_recovery() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        fx.edit { it[ControlStoreTestStorage.SEAL] = "[{\"kind\":\"NAMESPACE\"}]" }
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("RecoveryRequired(UninterpretableObligations), got $r",
            r is ControlCompletionResult.RecoveryRequired && r.reason == RecoveryReason.UninterpretableObligations)
        unchanged(before, observe(c), 1)
        assertEquals("no clock read", 0, reads.get())
    }

    /** Another ref's fixed release descriptor names c's own Applied row: G11 DependencyPresent on the matcher's identity. */
    @Test fun N4_anotherRefDependsOnTheOwnApplied_dependencyPresent() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        val m = fx.mutations()
        val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        val before = observe(c)
        rejected(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) },
            CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)))
        unchanged(before, observe(c), 1)
    }

    /** A new dependent after the pending: the retry is held with the same descriptor and no Confirm; once it is gone, completes. */
    @Test fun N5_newDependentAfterPending_retryHeld_thenCompletes() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        val before = fx.disk()
        fx.storage.storage.before = true
        assertTrue(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) } is ControlCompletionResult.Unconfirmed)
        val m = fx.mutations()
        val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(fx.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        val held = observe(c)
        rejected(controlTestTimeout("held retry") { store().retryTermination(c, d.closure) },
            CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)))
        unchanged(held, observe(c), 1)
        ControlReleaseFixtures.simulateReleased(fx.tracker, m) // the dependent finishes: released, out of P and commands
        completedWithOnlyOwnRemoved(controlTestTimeout("retry") { store().retryTermination(c, d.closure) }, c, before)
    }

    /** The first owner read fails: nothing published, c stays RETAINED in U and out of P. */
    @Test fun N6_firstOwnerReadFails_unconfirmedRetained() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        fx.boundary.failNextBeforeSnapshot = true
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("Unconfirmed(RETAINED, ReadingSnapshot), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.RETAINED && r.phase == ControlAttemptPhase.ReadingSnapshot)
        unchanged(before, observe(c), 1)
        assertNotNull(fx.tracker.findPrepared(c))
    }

    // ═══ contract r2: matcher negatives (Codex recipe, 6-4bC1a_contract_r1_codex.md) and further entrance rows ═════════════
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    /** Rewrite c's own Applied row (or add one) in the stored COMMAND_EVIDENCE array. */
    private suspend fun rewriteOwn(c: CommandRef, change: (JsonObject?) -> JsonObject) = fx.edit { p ->
        val rows = (Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray).map { it as JsonObject }
        val own = rows.firstOrNull { it["commandId"] == JsonPrimitive(c.id) }
        val kept = rows.filter { it !== own }
        p[evidenceKey] = JsonArray(kept + change(own)).toString()
    }
    private fun JsonObject.withTarget(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject {
        val target = (this["targets"] as JsonArray).single() as JsonObject
        return JsonObject(this + ("targets" to JsonArray(listOf(JsonObject(target + (key to value))))))
    }

    /** N3a: the own row names another target id (still interpretable): the strict matcher conflicts; nothing deleted. */
    @Test fun N3a_ownRowNamesAnotherTarget_conflictCommandEvidenceMismatch() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { checkNotNull(it).withTarget("id", JsonPrimitive("x")) }
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: still interpretable", read is ControlRecordRead.Supported && !read.hasUninterpretableMetadata)
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("Conflict(CommandEvidenceMismatch), got $r", r is ControlCompletionResult.Conflict && r.reason == ConflictReason.CommandEvidenceMismatch)
        unchanged(before, observe(c), 1)
    }

    /** N3b: the own row's target index is not its action's: the metadata is opaque, a recovery before the matcher. */
    @Test fun N3b_ownRowWithAnotherIndex_recoveryUninterpretableMetadata() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { checkNotNull(it).withTarget("index", JsonPrimitive(1)) }
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("RecoveryRequired(UninterpretableMetadata), got $r",
            r is ControlCompletionResult.RecoveryRequired && r.reason == RecoveryReason.UninterpretableMetadata)
        unchanged(before, observe(c), 1)
    }

    /** N3c: a valid own row appears for a command whose Confirm fixed no expected evidence: recovery, never a wildcard. */
    @Test fun N3c_ownRowWithoutExpectedEvidence_recoveryExpectedMutationsEvidenceUnavailable() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        rewriteOwn(c) { JsonObject(mapOf("version" to JsonPrimitive(2), "commandId" to JsonPrimitive(c.id),
            "ownerTrackingLifetimeId" to JsonPrimitive(c.ownerTrackingLifetimeId.value), "kind" to JsonPrimitive("MUTATIONS"),
            "targets" to JsonArray(listOf(JsonObject(mapOf("index" to JsonPrimitive(0), "kind" to JsonPrimitive("DEMAND"),
                "id" to JsonPrimitive("d"), "joined" to JsonPrimitive(false), "written" to JsonPrimitive(true))))))) }
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: the inserted own row is interpretable", read is ControlRecordRead.Supported && !read.hasUninterpretableMetadata &&
            ControlAppliedEvidence.own(read, c) != null)
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("RecoveryRequired(ExpectedMutationsEvidenceUnavailable), got $r",
            r is ControlCompletionResult.RecoveryRequired && r.reason == RecoveryReason.ExpectedMutationsEvidenceUnavailable)
        unchanged(before, observe(c), 1)
    }

    /** N1b: the same ref already holds its lease: InFlight, no owner access. */
    @Test fun N1b_sameRefInFlight_inFlight() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        check(fx.tracker.executing.add(c))
        try {
            val before = observe(c)
            rejected(controlTestTimeout("in flight") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }, CompletionRejectionReason.InFlight)
            unchanged(before, observe(c), 0)
        } finally { fx.tracker.executing.remove(c) }
    }

    /** N1c: a terminated ref answers AlreadyTerminated without owner access. */
    @Test fun N1c_terminatedRef_alreadyTerminated() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        val m = fx.mutations()
        assertTrue(controlTestTimeout("abandon") { fx.store.abandonBeforeFirstConfirm(m, TerminationClosures.of(m)) } is ControlCompletionResult.Completed)
        val accesses = fx.boundary.accesses
        val r = controlTestTimeout("terminated") { store().handoffAfterUncertainConfirm(m, TerminationClosures.of(m), d.handoff) }
        assertTrue("AlreadyTerminated, got $r", r is ControlCompletionResult.AlreadyTerminated)
        assertEquals("no owner access", accesses, fx.boundary.accesses)
    }

    /** N1d: a registered ref whose fixed body is not Mutations is handed over by its own branch (C2/C3); one that is not unresolved is refused before storage. */
    @Test fun N1d_nonMutationsBody_notUnresolved_refusedBeforeStorage() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        // Since 6-4bC3g every body kind is handed over, so no live body is UnsupportedInThisUnit: a Lifecycle command of the last
        // transition opened (RECOVER_HOLD) that is not unresolved is refused before storage.
        val settlement = fx.tracker.registerPrepared(ControlLifecycleEvidenceFixtures.command(ControlLifecycleEvidenceFixtures.descriptor(transition = LifecycleTransition.RECOVER_HOLD), life = fx.tracker.lifetimeId))
        val accesses = fx.boundary.accesses
        rejected(controlTestTimeout("non-mutations") { store().handoffAfterUncertainConfirm(settlement, TerminationClosures.of(settlement), d.handoff) },
            CompletionRejectionReason.NotUnresolved)
        assertEquals("no owner access", accesses, fx.boundary.accesses)
    }

    // ═══ contract r3: battery r1 additions (6-4bC1a_battery_r1_codex.md) ═══════════════════════════════════════════════
    private fun jsonOf(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) ->
        k to when (v) { is kotlinx.serialization.json.JsonElement -> v; is Int -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } })
    private suspend fun assertInterpretableMetadata() {
        val read = ControlRecordReader().read(fx.disk())
        assertTrue("fixture: interpretable metadata, got $read", read is ControlRecordRead.Supported && !read.hasUninterpretableMetadata)
    }
    private suspend fun conflictAtFirstEntry(c: CommandRef, d: Declared) {
        assertInterpretableMetadata()
        val before = observe(c)
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }
        assertTrue("Conflict(CommandEvidenceMismatch), got $r", r is ControlCompletionResult.Conflict && r.reason == ConflictReason.CommandEvidenceMismatch)
        unchanged(before, observe(c), 1)
    }

    /** R01: the own row is a valid ROTATION row of the same command. */
    @Test fun R01_ownRowOfAnotherEvidenceKind_conflict() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { jsonOf("version" to 2, "commandId" to c.id, "ownerTrackingLifetimeId" to c.ownerTrackingLifetimeId.value,
            "kind" to "ROTATION", "sealIds" to JsonArray(listOf(JsonPrimitive("s9"))), "demandId" to "q9") }
        conflictAtFirstEntry(c, d)
    }

    /** R02: the own row names another tracking lifetime. */
    @Test fun R02_ownRowOfAnotherLifetime_conflict() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { JsonObject(checkNotNull(it) + ("ownerTrackingLifetimeId" to JsonPrimitive("11111111-1111-4111-8111-111111111111"))) }
        conflictAtFirstEntry(c, d)
    }

    /** R03: the own row carries one target more than the command has actions. */
    @Test fun R03_ownRowWithAnExtraTarget_conflict() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { own -> val o = checkNotNull(own)
            JsonObject(o + ("targets" to JsonArray((o["targets"] as JsonArray) + jsonOf("index" to 1, "kind" to "DEMAND", "id" to "e",
                "joined" to false, "written" to true)))) }
        conflictAtFirstEntry(c, d)
    }

    /** R04: the own target names another kind. */
    @Test fun R04_ownTargetOfAnotherKind_conflict() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        rewriteOwn(c) { checkNotNull(it).withTarget("kind", JsonPrimitive("SEAL")) }
        conflictAtFirstEntry(c, d)
    }

    /** R05: two actions (d edited, e unchanged); the unwritten target's joined flag flipped: conflicts with the adoption. */
    @Test fun R05_unwrittenTargetMarkedJoined_conflict() = runReleaseTest { twoActionsWithSecondTarget("joined") }

    /** R10: the same two actions; the unwritten target now claims written: differs from the fixed expected evidence. */
    @Test fun R10_unwrittenTargetMarkedWritten_conflict() = runReleaseTest { twoActionsWithSecondTarget("written") }

    private suspend fun twoActionsWithSecondTarget(flag: String) {
        val requestE = request.replace("\"id\":\"d\"", "\"id\":\"e\"")
        fx.storage.seed(demand = "[$request,$requestE]")
        val c = fx.store.prepare(fx.store.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) },
            fx.store.edit(ControlKind.DEMAND, node(requestE)) {})
        fx.storage.storage.afterScope = true
        assertTrue(controlTestTimeout("execute two") { fx.store.execute(c) } is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: c in U", c in fx.tracker.snapshot())
        // d is consumed and completed; e is untouched and transferred with retained tokens.
        fx.edit { it[ControlStoreTestStorage.DEMAND] = "[$requestE]" }
        val body = c.body as ControlCommandBody.Mutations
        val input = RequirementInput.Mutations(c, body, MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val b = HandoffEventBinding(c, owner)
        val locked = controlTestTimeout("locked read") {
            fx.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        val requestE0 = DestinationLocator.Payload(ControlKind.DEMAND, "e")
        for (slot in a.orderedSlots.filter { it.requirement is SlotRequirement.Required }) {
            if ((slot.key.role as ObligationRole.MutationAction).index == 0) {
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
            } else {
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, requestE0, locked)
                assertTrue("fixture: token, got $t", t is RetainedSourceConfirmationResult.Issued)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(b, slot.key,
                    HandoffDisposition.DurablyOwned(requestE0, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
            }
        }
        val issued = h.closeJoinAndIssueHandoff(input) as HandoffIssueResult.Issued
        rewriteOwn(c) { own -> val o = checkNotNull(own); val targets = (o["targets"] as JsonArray).map { it as JsonObject }
            assertEquals("fixture: second target unwritten", JsonPrimitive(false), targets[1]["written"])
            JsonObject(o + ("targets" to JsonArray(listOf(targets[0], JsonObject(targets[1] + (flag to JsonPrimitive(true))))))) }
        conflictAtFirstEntry(c, Declared(issued.closure, issued.handoff, emptyList()))
    }

    /** R06: the own row was gone at the first entry (absence fixed) and reappears before the retry: the fixed absence wins. */
    @Test fun R06_fixedAbsenceThenOwnReappears_retryConflicts() = runReleaseTest {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        var saved: JsonObject? = null
        fx.edit { p -> val rows = evidence(p.toPreferences()).map { it as JsonObject }
            saved = rows.single { it["commandId"] == JsonPrimitive(c.id) }
            p[evidenceKey] = JsonArray(rows.filter { it !== saved }).toString() }
        fx.storage.storage.before = true
        assertTrue(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) } is ControlCompletionResult.Unconfirmed)
        val descriptor = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("fixed absence", descriptor is TerminationPendingDescriptor.MutationsHandoff && descriptor.expectedOwn == null)
        rewriteOwn(c) { checkNotNull(saved) }
        val before = observe(c)
        val r = controlTestTimeout("retry") { store().retryTermination(c, d.closure) }
        assertTrue("Conflict(CommandEvidenceMismatch), got $r", r is ControlCompletionResult.Conflict && r.reason == ConflictReason.CommandEvidenceMismatch)
        unchanged(before, observe(c), 1)
    }

    /** R07: no expected evidence and absence fixed; a valid own row appears before the retry: conflict (not the first-entry recovery). */
    @Test fun R07_noExpectedFixedAbsenceThenOwnAppears_retryConflicts() = runReleaseTest {
        val c = noopEdit(); val d = declare(c, completed = false)
        fx.storage.storage.before = true
        assertTrue(controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) } is ControlCompletionResult.Unconfirmed)
        rewriteOwn(c) { jsonOf("version" to 2, "commandId" to c.id, "ownerTrackingLifetimeId" to c.ownerTrackingLifetimeId.value,
            "kind" to "MUTATIONS", "targets" to JsonArray(listOf(jsonOf("index" to 0, "kind" to "DEMAND", "id" to "d", "joined" to false, "written" to true)))) }
        assertInterpretableMetadata()
        val before = observe(c)
        val r = controlTestTimeout("retry") { store().retryTermination(c, d.closure) }
        assertTrue("Conflict(CommandEvidenceMismatch), got $r", r is ControlCompletionResult.Conflict && r.reason == ConflictReason.CommandEvidenceMismatch)
        unchanged(before, observe(c), 1)
    }

    /** Returned-snapshot contamination: the check fails before any terminal step; the true candidate is on disk, so a retry completes. */
    private suspend fun contaminatedReturn(change: (JsonObject) -> (Preferences) -> Preferences) {
        val c = landedEdit(); consumeRequest(); val d = declare(c, completed = true)
        val before = fx.disk()
        val ownRow = evidence(before).map { it as JsonObject }.single { it["commandId"] == JsonPrimitive(c.id) }
        val returnedChange = change(ownRow)
        var descriptorAtReturn: TerminationPendingDescriptor? = null
        fx.boundary.afterReturn = { p ->
            descriptorAtReturn = fx.tracker.findPrepared(c)?.terminationDescriptor
            returnedChange(p)
        }
        val thrown = runCatching { store().handoffAfterUncertainConfirm(c, d.closure, d.handoff) }.exceptionOrNull()
        assertTrue("IllegalStateException, got $thrown", thrown is IllegalStateException)
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        val w = fx.tracker.recoverySnapshot(); assertTrue("c in U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        val descriptorAfterFailure = fx.tracker.findPrepared(c)?.terminationDescriptor
        assertTrue("descriptor fixed", descriptorAfterFailure is TerminationPendingDescriptor.MutationsHandoff)
        assertSame("descriptor object retained", descriptorAtReturn, descriptorAfterFailure)
        assertTrue("lease released", c !in fx.tracker.executing)
        fx.boundary.afterReturn = null
        completedWithOnlyOwnRemoved(controlTestTimeout("retry") { store().retryTermination(c, d.closure) }, c, before)
    }
    /** R08: a surviving row changed in the returned snapshot only. */
    @Test fun R08_returnedSurvivorChanged_checkFailsAndRetryCompletes() = runReleaseTest {
        contaminatedReturn { _ -> { p -> p.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[$request]" }.toPreferences() } }
    }
    /** R09: the deleted own row reappears in the returned snapshot only. */
    @Test fun R09_returnedOwnReappears_checkFailsAndRetryCompletes() = runReleaseTest {
        contaminatedReturn { own -> { p ->
            p.toMutablePreferences().apply { this[evidenceKey] = JsonArray(evidence(p) + own).toString() }.toPreferences() } }
    }
}
