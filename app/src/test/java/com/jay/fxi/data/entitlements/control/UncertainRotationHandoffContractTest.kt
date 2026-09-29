package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Claude-owned 6-4bC2a contract (6-4bC2 consensus r1 C2-D1; declaration 6-4bC2a_decl_codex.r1.md): a current-lifetime
 * OnceConfirm·U rotation (RotateAndSettle) is terminated by handing over its L/N responsibilities through
 * handoffAfterUncertainConfirm, and retried on its fixed TerminationPendingDescriptor.RotationHandoff.
 *  - The U fixture is a real rotation execute whose Confirm landed and whose return failed (afterScope).
 *  - B1 declares only the Required slots of RequirementInput.Rotation: SEAL L/N completed and consumed, JOURNAL L/N and
 *    NAMESPACE_RETIREMENT L/N retained on the rotation's journal locator, REQUEST L/N completed and consumed after the rotation's
 *    REQUEST is consumed. The base fixture removes the REQUEST from the record to stand for that consumption; T7 consumes it with a
 *    real SETTLE_QUERY. (Measured with assessG05 before this contract: the other declarations are COMPLETED_CONFLICT.)
 *  - Success removes exactly the own Applied row and this operation's seals; every other key, REQUEST, journal and epoch survive
 *    in order. When the own row and every seal of the operation are already absent at the first owner read (and the namespace is
 *    retired, so the journal is gone), the handoff confirms that absence.
 *  - Orphans, partial bundles, extra seals of the operation and replacements at a fixed id are held without deletion
 *    (RecoveryRequired(InconsistentReclamation)); another own kind is Conflict(CommandEvidenceMismatch); opaque rows are the
 *    uninterpretable reasons. The matcher rows whose first-entry G05 would already refuse (a removed seal breaks its retained
 *    retirement token) are measured on the retry, where G05 is not asked again.
 *  - G11 protects AppliedRow(c) and each fixed seal (ControlRow, SealWitness). A retry uses the fixed descriptor and never asks for
 *    the first G05 destinations again.
 * The implementation thread reads but does not edit this file.
 */
class UncertainRotationHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("C2a cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val N = NamespaceSettlementFixtures
    private val both = N.input(targets = listOf(node(N.user), node(N.krx)))
    private val now = BootReading("boot", 20_000)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val L = LandingBranch.L
    private val N_ = LandingBranch.N

    private fun store(f: TerminationFixture) = ControlRecordStore(f.storage.owner, bootReadingSource = BootReadingSource { now })
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun ownIn(p: Preferences, c: CommandRef) = arr(p, evidenceKey).count { rowCommand(it) == c.id }
    private fun replaceSeal(seals: JsonArray, target: String, change: (String) -> String): String =
        JsonArray(seals.map { if (id(it) == target) Json.parseToJsonElement(change(it.toString())).also { n -> check(n != it) { "fixture edit" } } else it }).toString()
    /** Independent oracle: the before record minus exactly the own row and this operation's seals; every other key equal. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    // ── the U rotation and its declaration ─────────────────────────────────────────────────────────────────────────
    /** A real rotation whose Confirm landed and whose return failed: OnceConfirm·U, own row and settled seals on disk. */
    private suspend fun rotationU(f: TerminationFixture, input: RotateAndSettleNamespaces = N.input(), seals: String = "[${N.user}]",
        seed: (MutablePreferences) -> Unit = {}): CommandRef {
        f.edit { it.clear(); it += N.raw(seals); seed(it) }
        val c = f.tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("rotation execute") { f.store.execute(c, N.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: rotation Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        val disk = f.disk()
        check(ownIn(disk, c) == 1) { "fixture: own row landed" }
        check(arr(disk, sealKey).count { op(it) == c.id } == input.targets.size) { "fixture: seals settled by c" }
        return c
    }
    /** Stands for the rotation's REQUEST consumed by a later command (T7 does it with a real SETTLE_QUERY). */
    private suspend fun consumeRequest(f: TerminationFixture) =
        f.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey).filter { id(it) != N.demandId }).toString() }
    /** The namespace retired after the rotation: own row, this operation's seals and the journal are gone. */
    private suspend fun retired(f: TerminationFixture, c: CommandRef) = f.edit { p ->
        p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { op(it) != c.id }).toString()
        p.remove(DataStoreAccessEpochStore.PURGE_JOURNAL)
    }
    private suspend fun lockedRead(f: TerminationFixture) = controlTestTimeout("locked read") {
        f.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>)
    private fun required(c: CommandRef) = (deriveRequiredObligations(RequirementInput.Rotation(c, c.body as ControlCommandBody.RotateAndSettle))
        as RequirementDerivation.Available).orderedSlots.filter { it.requirement is SlotRequirement.Required }
    /** B1 over the Required slots; [journalRetained] false declares every slot completed (the retired namespace). */
    private suspend fun declare(f: TerminationFixture, c: CommandRef, journalRetained: Boolean = true): Declared {
        val input = RequirementInput.Rotation(c, c.body as ControlCommandBody.RotateAndSettle)
        val slots = required(c)
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val e = HandoffEventBinding(c, owner)
        val locked = lockedRead(f)
        for (slot in slots) {
            val bound = (slot.requirement as SlotRequirement.Required).lowerBound
            val journal = when (bound) {
                is RequiredLowerBound.Journal -> bound.key
                is RequiredLowerBound.NamespaceRetirement -> bound.scope.journalKey
                else -> null
            }
            if (journalRetained && journal != null) {
                val d = DestinationLocator.Journal(journal)
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, d, locked)
                assertTrue("fixture: journal token for ${slot.key.component} ${slot.key.branch}, got $t", t is RetainedSourceConfirmationResult.Issued)
                assertEquals(RecordResult.Recorded, h.recordConfirmedTransfer(e, slot.key,
                    HandoffDisposition.DurablyOwned(d, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
            } else {
                assertEquals(RecordResult.Recorded, h.recordComponentCompleted(e, slot.key, ComponentCompletion(slot.key.subject)))
                assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(e, slot.key))
            }
        }
        val issued = h.closeJoinAndIssueHandoff(input)
        assertTrue("fixture: B1 issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Declared(issued.closure, issued.handoff, slots)
    }
    /** The common base: U rotation, its REQUEST consumed, B1 issued. */
    private suspend fun ready(f: TerminationFixture, input: RotateAndSettleNamespaces = N.input(), seals: String = "[${N.user}]",
        seed: (MutablePreferences) -> Unit = {}): Pair<CommandRef, Declared> {
        val c = rotationU(f, input, seals, seed); consumeRequest(f); return c to declare(f, c)
    }
    private suspend fun handoff(f: TerminationFixture, c: CommandRef, d: Declared, h: CompletionHandoff = d.handoff,
        k: TerminationClosure = d.closure) = controlTestTimeout("handoff") { store(f).handoffAfterUncertainConfirm(c, k, h) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, d: Declared) =
        controlTestTimeout("retry") { store(f).retryTermination(c, d.closure) }
    /** The management Confirm fails before its block: TERMINATION_PENDING with the fixed RotationHandoff, nothing landed. */
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef, d: Declared): TerminationPendingDescriptor.RotationHandoff {
        f.storage.storage.before = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.before = false }
        assertTrue("fixture: Unconfirmed, got $r", r is ControlCompletionResult.Unconfirmed)
        r as ControlCompletionResult.Unconfirmed
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, r.state)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, r.phase)
        val descriptor = f.history(c).terminationDescriptor
        assertTrue("fixture: RotationHandoff, got $descriptor", descriptor is TerminationPendingDescriptor.RotationHandoff)
        return descriptor as TerminationPendingDescriptor.RotationHandoff
    }

    // ── expectations ───────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun completed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("C2a.$id: Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        r as ControlCompletionResult.Completed
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("C2a.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("C2a.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("C2a.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("C2a.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("C2a.$id: commandRemoved", f.tracker.findPrepared(c))
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2a.$id: out of U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("C2a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    /** First-entry owner refusal: RETAINED, still in U, no descriptor, record unchanged with the read-back armed (Confirm 0). */
    private suspend fun heldFirst(id: String, f: TerminationFixture, c: CommandRef, d: Declared, check: (ControlCompletionResult) -> Unit,
        h: CompletionHandoff = d.handoff) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = handoff(f, c, d, h)
        check(r)
        assertEquals("C2a.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("C2a.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("C2a.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("C2a.$id: recordUntouched", before, f.storage.raw())
        assertTrue("C2a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    /** Retry refusal: TERMINATION_PENDING, the same descriptor object, U∩P, record unchanged with the read-back armed. */
    private suspend fun heldRetry(id: String, f: TerminationFixture, c: CommandRef, d: Declared, fixed: TerminationPendingDescriptor,
        check: (ControlCompletionResult) -> Unit) {
        f.armReadBack()
        val before = f.storage.raw()
        val r = retry(f, c, d)
        check(r)
        assertEquals("C2a.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("C2a.$id: descriptorKept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2a.$id: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("C2a.$id: noConfirm", before, f.storage.raw())
        assertTrue("C2a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private fun recovery(reason: RecoveryReason): (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected RecoveryRequired($reason), got $r", reason, (r as? ControlCompletionResult.RecoveryRequired)?.reason) }
    private fun rejected(reason: CompletionRejectionReason): (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected Rejected($reason), got $r", reason, (r as? ControlCompletionResult.Rejected)?.reason) }
    private val conflict: (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected Conflict(CommandEvidenceMismatch), got $r", ConflictReason.CommandEvidenceMismatch,
            (r as? ControlCompletionResult.Conflict)?.reason) }
    private val inconsistent = recovery(RecoveryReason.InconsistentReclamation)

    // ═══ T1: issuance and the refusals before storage ══════════════════════════════════════════════════════════════
    @Test fun T1_01_requiredSlots_oneSealEight_twoSealsFourteen_issuanceTouchesNothing() = runReleaseTest {
        val f = fixture(); val c = rotationU(f); consumeRequest(f)
        val before = f.disk()
        val d = declare(f, c)
        val keys = d.handoff.slots.map { it.key }
        assertEquals("each Required key once", d.required.map { it.key }, keys)
        assertEquals("one seal: SEAL, JOURNAL, NAMESPACE_RETIREMENT, REQUEST × L/N",
            listOf(ObligationComponent.SEAL, ObligationComponent.JOURNAL, ObligationComponent.NAMESPACE_RETIREMENT, ObligationComponent.REQUEST)
                .flatMap { comp -> listOf(comp to L, comp to N_) }.toSet(), keys.map { it.component to it.branch }.toSet())
        assertEquals(8, keys.size)
        assertEquals("issuance wrote nothing", before, f.disk())
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        val g = fixture(); val two = rotationU(g, both, "[${N.user},${N.krx}]"); consumeRequest(g)
        val beforeTwo = g.disk()
        val dTwo = declare(g, two)
        val twoKeys = dTwo.handoff.slots.map { it.key }
        assertEquals("two seals: each Required key once", dTwo.required.map { it.key }, twoKeys)
        assertEquals("two seals: 3 × 2 per seal + REQUEST L/N", 14, twoKeys.size)
        assertEquals("two seals: keys distinct", 14, twoKeys.toSet().size)
        assertEquals("two seals: issuance wrote nothing", beforeTwo, g.disk())
        assertEquals(ControlCommandLifecycle.RETAINED, two.lifecycleState)
    }

    @Test fun T1_02_refusalsBeforeStorage() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        suspend fun refused(id: String, ref: CommandRef, reason: CompletionRejectionReason, h: CompletionHandoff = d.handoff,
            k: TerminationClosure = d.closure) {
            val raw = f.storage.raw(); val access = f.boundary.accesses
            val r = handoff(f, ref, d, h, k)
            assertEquals("C2a.T1_02 $id: $reason, got $r", reason, (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("C2a.T1_02 $id: no owner access", 0, f.boundary.accesses - access)
            assertEquals("C2a.T1_02 $id: record untouched", raw, f.storage.raw())
        }
        val g = fixture(); val (foreign, dg) = ready(g)
        refused("lifetime", foreign, CompletionRejectionReason.WrongTrackerLifetime, dg.handoff, dg.closure)
        refused("clone", CommandRef(c.id, c.body, c.ownerTrackingLifetimeId), CompletionRejectionReason.NotRegisteredIdentity)
        check(f.tracker.executing.add(c))
        try { refused("lease", c, CompletionRejectionReason.InFlight) } finally { f.tracker.executing.remove(c) }
        val confirmedRotation = N.input(op = "00000000-0000-0000-0000-000000000031", did = "00000000-0000-0000-0000-000000000032",
            u = "00000000-0000-0000-0000-000000000033")
        val notU = f.tracker.registerPrepared(CommandRef(confirmedRotation.operationId, ControlCommandBody.RotateAndSettle(confirmedRotation), f.tracker.lifetimeId))
        refused("notUnresolved", notU, CompletionRejectionReason.NotUnresolved)
        f.addUnresolved(notU)
        refused("notOnceConfirm", notU, CompletionRejectionReason.NotOnceConfirm)
        refused("otherHandoffRef", c, CompletionRejectionReason.DeclarationRefMismatch, dg.handoff)
        refused("closureOpen", c, CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.EntriesOpen), k = closure(c, entriesClosed = false))
        // Since 6-4bC3g every body kind is handed over, so no live body is UnsupportedInThisUnit: a Lifecycle command of the last
        // transition opened (RECOVER_HOLD) that is not unresolved is refused before storage like any other kind.
        val s = f.tracker.registerPrepared(ControlLifecycleEvidenceFixtures.command(ControlLifecycleEvidenceFixtures.descriptor(transition = LifecycleTransition.RECOVER_HOLD), life = f.tracker.lifetimeId))
        refused("lifecycleNotUnresolved", s, CompletionRejectionReason.NotUnresolved)
        // Pending and terminal refs, on a fresh fixture (the refs above reference the seal "s" and would block by G11).
        val h = fixture(); val (p, dp) = ready(h)
        pendingByWriteFault(h, p, dp)
        val hRaw = h.storage.raw(); val hAccess = h.boundary.accesses
        assertEquals("C2a.T1_02 pending", CompletionRejectionReason.OtherManagementPath, (handoff(h, p, dp) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("C2a.T1_02 pending: no owner access", 0, h.boundary.accesses - hAccess)
        assertEquals("C2a.T1_02 pending: record untouched", hRaw, h.storage.raw())
        completed("T1_02", h, p, retry(h, p, dp), expectedAfterDeletion(h.disk(), p))
        val access = h.boundary.accesses
        assertTrue("C2a.T1_02 terminated", handoff(h, p, dp) is ControlCompletionResult.AlreadyTerminated)
        assertEquals("C2a.T1_02 terminated: no owner access", 0, h.boundary.accesses - access)
    }

    @Test fun T1_03_declarationMissingRequestN_g05CoverageN() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val missing = d.required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == N_ }
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
        val expected = rejected(CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_N, missing.key,
            G05Location.Fixed((missing.requirement as SlotRequirement.Required).fixedSources.first().location), null, null))))
        heldFirst("T1_03", f, c, d, { r ->
            expected(r)
            // Only the owner transaction itself: one storage access around the call (the raw reads of heldFirst are outside it).
        }, forged)
        val access = f.boundary.accesses
        expected(handoff(f, c, d, forged))
        assertEquals("C2a.T1_03: one owner access", 1, f.boundary.accesses - access)
    }

    // ═══ T5: the matcher ═══════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T5_01_exactBundle_onlyTheOwnRowAndItsSealRemoved_everythingElseInOrder() = runReleaseTest {
        val f = fixture()
        val other = N.input(targets = listOf(node(N.krx)), op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        val foreignRecord = N.settled(other, N.raw("[${N.krx}]"), N.trackerLife)
        val krx2 = N.krx.replace("\"id\":\"c\"", "\"id\":\"c2\"").also { check(it != N.krx) }
        val other2 = N.input(targets = listOf(node(krx2)), op = "00000000-0000-0000-0000-000000000011", did = "00000000-0000-0000-0000-000000000012")
        val foreignRecord2 = N.settled(other2, N.raw("[$krx2]"), N.trackerLife)
        val otherRequest = ControlObligationFixtures.request
        val (c, d) = ready(f) { p ->
            // The other operations' seals before and after this operation's seal; their rows before the own row (execute appends it).
            p[sealKey] = JsonArray(arr(foreignRecord, sealKey) + Json.parseToJsonElement(N.user) + arr(foreignRecord2, sealKey)).toString()
            p[evidenceKey] = JsonArray(arr(foreignRecord, evidenceKey) + arr(foreignRecord2, evidenceKey)).toString()
            p[demandKey] = "[$otherRequest]"
        }
        check(arr(f.disk(), sealKey).map { id(it) } == listOf("c", "s", "c2") && arr(f.disk(), evidenceKey).size == 3) { "fixture: order" }
        val before = f.disk()
        completed("T5_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("other seals kept in order, text unchanged", arr(before, sealKey).filter { op(it) != c.id }, arr(f.disk(), sealKey))
        assertEquals("other rows kept in order", listOf(other.operationId, other2.operationId), arr(f.disk(), evidenceKey).map { rowCommand(it) })
        assertEquals("other REQUEST kept", before[demandKey], f.disk()[demandKey])
        assertEquals("journal kept", before[DataStoreAccessEpochStore.PURGE_JOURNAL], f.disk()[DataStoreAccessEpochStore.PURGE_JOURNAL])
    }

    @Test fun T5_02_wholeAbsenceAtTheFirstRead_absenceConfirm_andAFaultFixesANullExpectedOwn() = runReleaseTest {
        val f = fixture(); val c = rotationU(f); consumeRequest(f); retired(f, c)
        val d = declare(f, c, journalRetained = false)
        val before = f.disk()
        completed("T5_02", f, c, handoff(f, c, d), withoutBarrier(before))
        val g = fixture(); val e = rotationU(g); consumeRequest(g); retired(g, e)
        val dg = declare(g, e, journalRetained = false)
        val beforeG = g.disk()
        val fixed = pendingByWriteFault(g, e, dg)
        assertNull("absence fixed at the first owner read", fixed.expectedOwn)
        assertEquals(e.id, fixed.operationId); assertEquals(listOf("s"), fixed.orderedSealIds)
        completed("T5_02 retry", g, e, retry(g, e, dg), withoutBarrier(beforeG))
    }

    @Test fun T5_03_exactDescriptorFixed_thenSealWitnessOrOwnKindChanged_heldOnRetry() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val fixed = pendingByWriteFault(f, c, d)
        f.edit { p -> p[sealKey] = replaceSeal(arr(p.toPreferences(), sealKey), "s") { it.replace("\"originLifetimeId\":\"life\"", "\"originLifetimeId\":\"life2\"") } }
        heldRetry("T5_03 witness", f, c, d, fixed, inconsistent)
        val g = fixture(); val (e, dg) = ready(g); val fixedG = pendingByWriteFault(g, e, dg)
        g.edit { p -> p[evidenceKey] = """[{"version":2,"commandId":"${e.id}","ownerTrackingLifetimeId":"${e.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}]""" }
        check(ControlAppliedEvidence.own(ControlRecordReader().read(g.storage.raw()) as ControlRecordRead.Supported, e) != null) { "fixture: own row parses" }
        heldRetry("T5_03 ownKind", g, e, dg, fixedG, conflict)
    }

    @Test fun T5_04_rowSealOrderReversed_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f, both, "[${N.user},${N.krx}]")
        f.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"sealIds\":[\"s\",\"c\"]", "\"sealIds\":[\"c\",\"s\"]")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldFirst("T5_04", f, c, d, inconsistent)
    }

    @Test fun T5_05_orphanSealWithoutTheOwnRow_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
        heldFirst("T5_05", f, c, d, inconsistent)
    }

    @Test fun T5_06_partialTwoSealBundle_heldOnRetry() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f, both, "[${N.user},${N.krx}]"); val fixed = pendingByWriteFault(f, c, d)
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "c" }).toString() }
        heldRetry("T5_06 ownRowKept", f, c, d, fixed, inconsistent)
        val g = fixture(); val (e, dg) = ready(g, both, "[${N.user},${N.krx}]"); val fixedG = pendingByWriteFault(g, e, dg)
        g.edit { p ->
            p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != e.id }).toString()
            p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "c" }).toString()
        }
        heldRetry("T5_06 onlySLeft", g, e, dg, fixedG, inconsistent)
    }

    @Test fun T5_07_extraSealOfThisOperation_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val extra = arr(N.settled(N.input(targets = listOf(node(N.krx))), N.raw("[${N.krx}]"), f.tracker.lifetimeId), sealKey).single()
        check(op(extra) == c.id)
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        heldFirst("T5_07", f, c, d, inconsistent)
    }

    @Test fun T5_08_opaqueOwnRowOrOpaqueSeal_uninterpretable() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        f.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(",\"demandId\":\"${N.demandId}\"", "").also { check(it != p[evidenceKey]) } }
        heldFirst("T5_08 metadata", f, c, d, recovery(RecoveryReason.UninterpretableMetadata))
        val g = fixture(); val (e, dg) = ready(g)
        g.edit { p -> p[sealKey] = checkNotNull(p[sealKey]).replace("\"settlement\":{", "\"settlement\":{\"version\":3,").also { check(it != p[sealKey]) } }
        heldFirst("T5_08 seal", g, e, dg, recovery(RecoveryReason.UninterpretableObligations))
    }

    @Test fun T5_09_absenceFixed_thenAReplacementAtTheFixedId_heldOnRetry() = runReleaseTest {
        val f = fixture(); val c = rotationU(f); consumeRequest(f); retired(f, c)
        val d = declare(f, c, journalRetained = false); val fixed = pendingByWriteFault(f, c, d)
        check(fixed.expectedOwn == null)
        f.edit { p -> p[sealKey] = "[${N.user}]" }
        heldRetry("T5_09", f, c, d, fixed, inconsistent)
    }

    /** T5-10: retired, but a seal of this operation under another id remains: not whole absence, held without deletion. */
    @Test fun T5_10_retiredButAnotherSealOfThisOperationRemains_inconsistent() = runReleaseTest {
        val f = fixture(); val c = rotationU(f); consumeRequest(f); retired(f, c)
        val d = declare(f, c, journalRetained = false)
        val extra = arr(N.settled(N.input(targets = listOf(node(N.krx))), N.raw("[${N.krx}]"), f.tracker.lifetimeId), sealKey).single()
        check(op(extra) == c.id && id(extra) == "c")
        f.edit { p -> p[sealKey] = JsonArray(listOf(extra)).toString() }
        heldFirst("T5_10", f, c, d, inconsistent)
    }

    /**
     * T5-11: the witness confirm path leaves the tracked expected row null (ControlRotationConsumptionContractTest T2_10); with a
     * read-back obligation that Confirm writes and fails after its scope, so the rotation is OnceConfirm·U. After the namespace
     * retires, the absence fixed at the first owner read still completes on retry.
     */
    @Test fun T5_11_absenceWithANullTrackedExpectedRow_retryCompletes() = runReleaseTest {
        val f = fixture()
        val witnessed = N.settled(N.input(), N.raw(), f.tracker.lifetimeId).toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
        f.edit { it.clear(); it += witnessed }
        val input = N.input()
        val c = f.tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
        f.armReadBack() // the witness path confirms read.original; the read-back obligation makes that Confirm write (and fail after scope)
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("witness execute") { f.store.execute(c, N.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed && f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: U, got $r" }
        check(f.history(c).expectedApplied == null) { "fixture: tracked expected row null" }
        consumeRequest(f); retired(f, c)
        val d = declare(f, c, journalRetained = false)
        val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNull("absence fixed", fixed.expectedOwn)
        completed("T5_11", f, c, retry(f, c, d), withoutBarrier(before))
    }

    /** T5-12: absence fixed, then the own row and the settled seal come back exactly: no promotion to exact, held without deletion. */
    @Test fun T5_12_absenceFixed_thenTheBundleReappears_notPromoted_held() = runReleaseTest {
        val f = fixture(); val c = rotationU(f); consumeRequest(f)
        val present = f.disk()
        retired(f, c)
        val d = declare(f, c, journalRetained = false); val fixed = pendingByWriteFault(f, c, d)
        check(fixed.expectedOwn == null)
        f.edit { it.clear(); it += withoutBarrier(present) }
        check(ownIn(f.disk(), c) == 1 && arr(f.disk(), sealKey).count { op(it) == c.id } == 1) { "fixture: bundle back" }
        heldRetry("T5_12", f, c, d, fixed, inconsistent)
    }

    // ═══ T6: dependencies, faults, cancellation, returns ═══════════════════════════════════════════════════════════
    @Test fun T6_01_anotherRefOnTheOwnRowOrTheSeal_dependencyPresent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val m = f.mutations()
        val dep = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(dep))
        heldFirst("T6_01 row", f, c, d, rejected(CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value))))
        val g = fixture(); val (e, dg) = ready(g)
        val settled = node(arr(g.storage.raw(), sealKey).single { id(it) == "s" }.toString())
        val edit = g.store.prepare(ControlMutation.Edit.prepare(ControlKind.SEAL, settled) {})
        heldFirst("T6_01 seal", g, e, dg, rejected(CompletionRejectionReason.DependencyPresent(edit.id, edit.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, "s"))))
    }

    @Test fun T6_02_unknownAdoptionOfASealAdd_dependencyUnknown() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 92)) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) }
        val u = CommandRef("u-seal-add", listOf(add), OwnerTrackingLifetimeId.issue()); f.addUnresolved(u)
        heldFirst("T6_02", f, c, d, rejected(CompletionRejectionReason.DependencyUnknown(u.id, u.ownerTrackingLifetimeId.value,
            DependencyGapSource.Adoption(null))))
    }

    @Test fun T6_03_writeFaultFixesTheExactDescriptor_retryDeletesWithoutTheFirstDestinations() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNotNull("exact own fixed", fixed.expectedOwn)
        assertEquals(c.id, fixed.operationId)
        assertEquals(listOf("s"), fixed.orderedSealIds)
        assertEquals(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), fixed.deletionIdentity)
        assertSame(c, fixed.closureBinding.command)
        assertEquals(CompletionMode.ResponsibilityTransferred, fixed.mode)
        assertEquals(TerminationEntry.UncertainRotation, fixed.entry)
        val w = f.tracker.recoverySnapshot(); assertTrue("U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("nothing landed", before, f.disk())
        // The journal the first G05 destinations named is gone: the retry does not ask for them again.
        f.edit { it.remove(DataStoreAccessEpochStore.PURGE_JOURNAL) }
        val beforeRetry = f.disk()
        completed("T6_03", f, c, retry(f, c, d), expectedAfterDeletion(beforeRetry, c))
    }

    @Test fun T6_04_landedThenReturnFailed_retryConfirmsAbsence() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.afterScope = false }
        assertTrue("Unconfirmed, got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        val landed = f.disk()
        assertEquals("deletion landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        completed("T6_04", f, c, retry(f, c, d), withoutBarrier(landed))
    }

    @Test fun T6_05_retryCancelledBeforeItsSnapshot_pendingKept_thenCompletes() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.boundary.gate = reached to release
        val writes = f.storage.storage.writes
        coroutineScope {
            val caller = async { retry(f, c, d) }
            try { withTimeout(10_000) { reached.await() }; caller.cancelAndJoin(); assertTrue("cancelled", caller.isCancelled) }
            finally { release.complete(Unit); caller.cancelAndJoin() }
        }
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("descriptor kept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot(); assertTrue("U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("no Confirm", writes, f.storage.storage.writes)
        assertEquals("file unchanged", before, f.disk())
        assertTrue("lease released", f.tracker.executing.isEmpty())
        completed("T6_05", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    @Test fun T6_06_unrelatedUAndPendingRefs_completes_andTheyStay() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val u = f.mutations(); f.addUnresolved(u)
        val p = f.mutations()
        val pRow = ControlReleaseFixtures.row(p, id = p.id, lifetime = p.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, p, ReleasePendingDescriptor.ExactMutations(pRow))
        val w0 = f.tracker.recoverySnapshot(); val uState = u.lifecycleState
        val before = f.disk()
        completed("T6_06", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        val w = f.tracker.recoverySnapshot()
        assertEquals("U without c unchanged", w0.unresolvedCommands - c, w.unresolvedCommands)
        assertEquals("P without c unchanged", w0.pendingReleases - c, w.pendingReleases)
        assertTrue("u stays in U", u in w.unresolvedCommands)
        assertTrue("p stays in P", p in w.pendingReleases)
        assertEquals(uState, u.lifecycleState)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, p.lifecycleState)
    }

    @Test fun T6_07_newDependentAfterTheDescriptor_heldOnRetry_thenCompletesWhenItLeaves() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        val settled = node(arr(f.storage.raw(), sealKey).single { id(it) == "s" }.toString())
        val u = CommandRef("u-edit", listOf(ControlMutation.Edit.prepare(ControlKind.SEAL, settled) {}), OwnerTrackingLifetimeId.issue())
        f.addUnresolved(u)
        heldRetry("T6_07", f, c, d, fixed, rejected(CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, "s"))))
        val w = f.tracker.recoverySnapshot()
        ControlReleaseFixtures.replaceRecovery(f.tracker, LocalRecoveryWork(w.unresolvedCommands - u, w.pendingReleases))
        completed("T6_07", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    /** T6-07 (projection): each RotationHandoff projects AppliedRow(c), ControlRow(SEAL, id) and SealWitness(id, op); a binding mismatch is a gap. */
    @Test fun T6_07b_rotationHandoffProjection_rowSealAndWitness_mismatchIsAGap() {
        val life = OwnerTrackingLifetimeId.issue()
        val input = N.input()
        val ref = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), life)
        val row = AppliedEvidence.Rotation(ref.id, life.value, listOf("s"), N.demandId)
        fun descriptor(own: AppliedEvidence.Rotation?, operation: String = ref.id, seals: List<String> = listOf("s"),
            binding: TerminationClosureBinding = closure(ref).binding()) =
            TerminationPendingDescriptor.RotationHandoff(binding, own, operation, seals, DependencyAtom.AppliedRow(ref.id, life.value))
        fun project(d: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(ref.id, life.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, ref.body), emptyList(), null, d))
        val atoms = setOf(DependencyAtom.AppliedRow(ref.id, life.value), DependencyAtom.ControlRow(ControlKind.SEAL, "s"),
            DependencyAtom.SealWitness("s", ref.id))
        for ((name, own) in listOf("exact" to row, "absent" to null)) {
            val p = project(descriptor(own))
            assertTrue("C2a.T6_07b $name: Known with row, seal and witness, got $p", p is DependencyProjection.Known && p.dependencies.containsAll(atoms))
        }
        val otherInput = N.input(op = "00000000-0000-0000-0000-000000000042")
        val otherRef = CommandRef(otherInput.operationId, ControlCommandBody.RotateAndSettle(otherInput), life)
        // Each mismatch keeps the descriptor's own atoms (values the body projection cannot produce).
        val otherOp = "00000000-0000-0000-0000-000000000041"
        for ((name, d, own) in listOf(
                Triple("operation", descriptor(row, operation = otherOp), setOf<DependencyAtom>(DependencyAtom.SealWitness("s", otherOp))),
                Triple("seals", descriptor(row, seals = listOf("c")),
                    setOf(DependencyAtom.ControlRow(ControlKind.SEAL, "c"), DependencyAtom.SealWitness("c", ref.id))),
                Triple("bindingCommand", descriptor(row, binding = closure(otherRef).binding()), setOf(DependencyAtom.AppliedRow(ref.id, life.value))))) {
            val p = project(d)
            assertTrue("C2a.T6_07b $name: TerminationDescriptorMismatch gap, got $p", p is DependencyProjection.Unknown &&
                DependencyGapCause.TerminationDescriptorMismatch in p.gaps.map { it.cause })
            assertTrue("C2a.T6_07b $name: descriptor atoms kept, got $p", (p as DependencyProjection.Unknown).knownDependencies.containsAll(own))
        }
    }

    @Test fun T6_08_firstReadFailureOrCancellationBeforeTheCandidate_retainedUntouched() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk(); val work = f.tracker.recoverySnapshot()
        f.boundary.failNextBeforeSnapshot = true
        val r = handoff(f, c, d)
        assertTrue("Unconfirmed(RETAINED, ReadingSnapshot), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.RETAINED && r.phase == ControlAttemptPhase.ReadingSnapshot)
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.boundary.gate = reached to release
        coroutineScope {
            val caller = async { handoff(f, c, d) }
            try { withTimeout(10_000) { reached.await() }; caller.cancelAndJoin(); assertTrue("cancelled", caller.isCancelled) }
            finally { release.complete(Unit); caller.cancelAndJoin() }
        }
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("no descriptor", f.history(c).terminationDescriptor)
        assertEquals("sets kept", work, f.tracker.recoverySnapshot())
        assertEquals("file unchanged", before, f.disk())
        assertTrue("lease released", f.tracker.executing.isEmpty())
        completed("T6_08", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
    }

    @Test fun T6_09_returnSnapshotContaminated_invariantFailure_pendingKept() = runReleaseTest {
        for (case in listOf("ownRowBack", "unrelatedKey")) {
            val f = fixture(); val (c, d) = ready(f); val ownRows = checkNotNull(f.disk()[evidenceKey]); val before = f.disk()
            f.boundary.afterReturn = { p -> p.toMutablePreferences().apply {
                if (case == "ownRowBack") this[evidenceKey] = ownRows else this[demandKey] = "[${ControlObligationFixtures.request}]"
            }.toPreferences() }
            val failure = runCatching { handoff(f, c, d) }.exceptionOrNull()
            assertEquals("C2a.T6_09 $case: invariant propagates", IllegalStateException::class.java, failure?.javaClass)
            assertEquals("C2a.T6_09 $case: the stored candidate landed", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            assertEquals("C2a.T6_09 $case: pending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertTrue("C2a.T6_09 $case: descriptor", f.history(c).terminationDescriptor is TerminationPendingDescriptor.RotationHandoff)
            val w = f.tracker.recoverySnapshot()
            assertTrue("C2a.T6_09 $case: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
            assertTrue("C2a.T6_09 $case: lease released", f.tracker.executing.isEmpty())
        }
    }

    @Test fun T6_10_exactRetryWithTheSealGoneOrReplaced_held() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val fixed = pendingByWriteFault(f, c, d)
        f.edit { p -> p[sealKey] = "[]" }
        heldRetry("T6_10 gone", f, c, d, fixed, inconsistent)
        val g = fixture(); val (e, dg) = ready(g); val fixedG = pendingByWriteFault(g, e, dg)
        g.edit { p -> p[sealKey] = "[${N.user}]" }
        heldRetry("T6_10 replaced", g, e, dg, fixedG, inconsistent)
    }

    // ═══ T7: a real successor consumes the REQUEST ═════════════════════════════════════════════════════════════════
    @Test fun T7_01_realSettleQueryConsumesTheRequest_thenTheOldRotationHandsOver() = runReleaseTest {
        val f = fixture(); val c = rotationU(f)
        val q = DemandAuthFixtures
        val fence = FenceV1("A", checkNotNull(f.disk()[DataStoreAccessEpochStore.USER_EPOCH]), checkNotNull(f.disk()[DataStoreAccessEpochStore.KRX_EPOCH]))
        val started = q.query.copy(fence = fence)
        val request = node(arr(f.disk(), demandKey).single { id(it) == N.demandId }.toString())
        val s = f.store.prepareSettleQuery(listOf(request), null, null, q.binding, q.decision(q = started, before = fence, after = fence),
            LifecycleOrderSource(q.life, 21))
        val rs = controlTestTimeout("settle") {
            f.store.execute(s, q.context(q.runtime(registrations = listOf(LifecycleQueryRegistration("query-21", started))))) }
        assertTrue("fixture: SETTLE_QUERY Confirmed, got $rs", rs is ControlStoreResult.Confirmed)
        assertTrue("fixture: REQUEST consumed", arr(f.disk(), demandKey).none { id(it) == N.demandId })
        val consumed = controlTestTimeout("settle consumed") {
            f.store.completeLifecycleAfterConsumption(s, closure(s), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)) }
        assertTrue("fixture: successor result consumed, got $consumed", consumed is ControlCompletionResult.Completed)
        val d = declare(f, c)
        val before = f.disk()
        assertEquals("the old rotation's settled seal is still stored", 1, arr(before, sealKey).count { op(it) == c.id })
        completed("T7_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
    }

    // ═══ T11: a real reopen ═════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T11_01_reopenedFile_oldRefRetry_wrongTrackerLifetime_newTrackerEmpty() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); pendingByWriteFault(f, c, d)
        val file = f.disk()
        controlTestTimeout("close", 30_000) { f.storage.close() }; opened.remove(f)
        val reopened = TerminationFixture(folder.root, 99, f.file).also { opened += it }
        val access = reopened.boundary.accesses
        val r = controlTestTimeout("old ref retry") {
            ControlRecordStore(reopened.storage.owner, bootReadingSource = BootReadingSource { now }).retryTermination(c, d.closure) }
        assertEquals("expected WrongTrackerLifetime, got $r", CompletionRejectionReason.WrongTrackerLifetime,
            (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("no owner access", 0, reopened.boundary.accesses - access)
        assertEquals("file unchanged", file, reopened.disk())
        val w = reopened.tracker.recoverySnapshot()
        assertTrue("new tracker U/P empty", w.unresolvedCommands.isEmpty() && w.pendingReleases.isEmpty())
    }
}
