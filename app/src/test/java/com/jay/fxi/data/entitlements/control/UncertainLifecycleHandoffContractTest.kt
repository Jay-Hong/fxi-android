package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
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
 * Claude-owned 6-4bC3a contract (C3/split_codex.r1.md): a current-lifetime OnceConfirm·U Lifecycle REMOVE_EMPTY_GUARD is
 * terminated by handing over its L/N responsibilities through handoffAfterUncertainConfirm, and retried on its fixed
 * TerminationPendingDescriptor.LifecycleHandoff. The other six Lifecycle transitions stay UnsupportedInThisUnit (C3b–g). The paths
 * C2a locked once for every kind (the refusals before storage, cancellation, read failure, reopen) are not repeated.
 *  - The U fixture is a real REMOVE_EMPTY_GUARD execute whose Confirm landed and whose return failed (afterScope): the guard is gone
 *    and the own Lifecycle row is on disk.
 *  - The declaration names only the Required slots of RequirementInput.Lifecycle: SOURCE L (exact source) and RECEIPT L (receipt),
 *    completed and consumed; neither N slot is required. Measured with assessG05 before this contract (C3/C3a_probe_outputs.r1.txt):
 *    the exact and the wholly absent declarations are Accepted.
 *  - Success removes exactly the own row; every other key keeps its value and order. When the own row is absent at the first owner
 *    read, the handoff confirms that absence, and a retry on that fixed absence never promotes a returning row.
 *  - Another own kind, lifetime, transition or target is Conflict(CommandEvidenceMismatch) (the Lifecycle consumption matcher's rule);
 *    an uninterpretable own row is UninterpretableMetadata.
 * The implementation thread reads but does not edit this file.
 */
class UncertainLifecycleHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("C3a cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val F = FloorGuardFixtures
    private val now = BootReading("boot", 20_000)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val L = LandingBranch.L

    private fun store(f: TerminationFixture) = ControlRecordStore(f.storage.owner, bootReadingSource = BootReadingSource { now })
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(p[key] ?: "[]").jsonArray
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun ownIn(p: Preferences, c: CommandRef) = arr(p, evidenceKey).count { rowCommand(it) == c.id }
    /** Independent oracle: the before record minus exactly the own row; every other key equal. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    // ── the U Lifecycle and its declaration ────────────────────────────────────────────────────────────────────────────
    /** A real REMOVE_EMPTY_GUARD whose Confirm landed and whose return failed: OnceConfirm·U, guard removed, own row on disk. */
    private suspend fun lifecycleU(f: TerminationFixture, seed: (MutablePreferences) -> Unit = {}): CommandRef {
        f.edit { it.clear(); it += F.raw(F.empty); seed(it) }
        val c = f.store.prepareRemoveEmptyGuard(F.empty)
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("REMOVE_EMPTY_GUARD execute") { f.store.execute(c) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        check(f.history(c).expectedApplied is AppliedEvidence.Lifecycle) { "fixture: Lifecycle expected row" }
        check(ownIn(f.disk(), c) == 1) { "fixture: own row landed" }
        return c
    }
    private suspend fun absent(f: TerminationFixture, c: CommandRef) = f.edit { p ->
        p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
    }
    private suspend fun lockedRead(f: TerminationFixture) = controlTestTimeout("locked read") {
        f.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>,
        val all: List<RequiredSlot>)
    private suspend fun declare(f: TerminationFixture, c: CommandRef): Declared {
        val input = RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        val slots = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val e = HandoffEventBinding(c, owner)
        lockedRead(f)
        for (slot in slots) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(e, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(e, slot.key))
        }
        val issued = h.closeJoinAndIssueHandoff(input)
        assertTrue("fixture: declaration issued, got $issued", issued is HandoffIssueResult.Issued)
        issued as HandoffIssueResult.Issued
        return Declared(issued.closure, issued.handoff, slots, a.orderedSlots)
    }
    private suspend fun ready(f: TerminationFixture, seed: (MutablePreferences) -> Unit = {}): Pair<CommandRef, Declared> {
        val c = lifecycleU(f, seed); return c to declare(f, c)
    }
    private suspend fun handoff(f: TerminationFixture, c: CommandRef, d: Declared, h: CompletionHandoff = d.handoff) =
        controlTestTimeout("handoff") { store(f).handoffAfterUncertainConfirm(c, d.closure, h) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, d: Declared) =
        controlTestTimeout("retry") { store(f).retryTermination(c, d.closure) }
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef, d: Declared): TerminationPendingDescriptor.LifecycleHandoff {
        f.storage.storage.before = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.before = false }
        assertTrue("fixture: Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        val descriptor = f.history(c).terminationDescriptor
        assertTrue("fixture: LifecycleHandoff, got $descriptor", descriptor is TerminationPendingDescriptor.LifecycleHandoff)
        val w = f.tracker.recoverySnapshot(); assertTrue("fixture: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        return descriptor as TerminationPendingDescriptor.LifecycleHandoff
    }

    // ── expectations ───────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun completed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("C3a.$id: Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        r as ControlCompletionResult.Completed
        val after = f.disk()
        assertEquals("C3a.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("C3a.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("C3a.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("C3a.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("C3a.$id: commandRemoved", f.tracker.findPrepared(c))
        val w = f.tracker.recoverySnapshot()
        assertTrue("C3a.$id: out of U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("C3a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldFirst(id: String, f: TerminationFixture, c: CommandRef, d: Declared, check: (ControlCompletionResult) -> Unit,
        h: CompletionHandoff = d.handoff) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        check(handoff(f, c, d, h))
        assertEquals("C3a.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("C3a.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("C3a.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("C3a.$id: recordUntouched", before, f.storage.raw())
        assertTrue("C3a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldRetry(id: String, f: TerminationFixture, c: CommandRef, d: Declared, fixed: TerminationPendingDescriptor,
        check: (ControlCompletionResult) -> Unit) {
        f.armReadBack()
        val before = f.storage.raw()
        check(retry(f, c, d))
        assertEquals("C3a.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("C3a.$id: descriptorKept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot()
        assertTrue("C3a.$id: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("C3a.$id: noConfirm", before, f.storage.raw())
        assertTrue("C3a.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private fun recovery(reason: RecoveryReason): (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected RecoveryRequired($reason), got $r", reason, (r as? ControlCompletionResult.RecoveryRequired)?.reason) }
    private fun rejected(reason: CompletionRejectionReason): (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected Rejected($reason), got $r", reason, (r as? ControlCompletionResult.Rejected)?.reason) }
    private val conflict: (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected Conflict(CommandEvidenceMismatch), got $r", ConflictReason.CommandEvidenceMismatch,
            (r as? ControlCompletionResult.Conflict)?.reason) }
    private val inconsistent = recovery(RecoveryReason.InconsistentReclamation)
    private fun fixedAt(slot: RequiredSlot) = G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location)

    // ═══ T1 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T1_01_requiredSlots_sourceLAndReceiptL_issuanceTouchesNothing() = runReleaseTest {
        val f = fixture(); val c = lifecycleU(f)
        val before = f.disk(); val body = c.captureStateAndBody().body; val registered = f.tracker.findPrepared(c)
        val work = f.tracker.recoverySnapshot()
        val d = declare(f, c)
        val keys = d.handoff.slots.map { it.key }
        assertEquals("C3a.T1_01: each Required key once", d.required.map { it.key }, keys)
        assertEquals("C3a.T1_01: SOURCE L and RECEIPT L only",
            listOf(ObligationComponent.SOURCE to L, ObligationComponent.RECEIPT to L), keys.map { it.component to it.branch })
        assertEquals("C3a.T1_01: issuance wrote nothing", before, f.disk())
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertSame("C3a.T1_01: body the same object", body, c.captureStateAndBody().body)
        assertSame("C3a.T1_01: registered ref the same object", registered, f.tracker.findPrepared(c))
        assertEquals("C3a.T1_01: recovery sets unchanged", work, f.tracker.recoverySnapshot())
    }

    @Test fun T1_02_aMissingLAndAnExtraN_areEachRefusedByG05() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val missing = d.required.single { it.key.component == ObligationComponent.SOURCE }
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
        heldFirst("T1_02 missing SOURCE L", f, c, d,
            rejected(CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_L, missing.key, fixedAt(missing), null, null)))), forged)
        val g = fixture(); val (e, dg) = ready(g)
        val extra = dg.all.first { it.key.component == ObligationComponent.SOURCE && it.key.branch == LandingBranch.N }
        check(extra.requirement !is SlotRequirement.Required) { "fixture: SOURCE N is not required" }
        val slots = dg.handoff.slots + SlotHandoff(extra.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(extra.key.subject), emptyList()))
        heldFirst("T1_02 extra SOURCE N", g, e, dg, rejected(CompletionRejectionReason.G05(listOf(
            G05Failure(G05Id.EXTRA, extra.key, null, G05Location.Submitted(slots.size - 1), null)))),
            CompletionHandoff(dg.handoff.command, dg.handoff.responsibilityOwner, slots))
    }

    @Test fun T1_03_theOtherSixTransitions_stayUnsupported() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        for (t in LifecycleTransition.entries - LifecycleTransition.REMOVE_EMPTY_GUARD) {
            val other = f.tracker.registerPrepared(ControlLifecycleEvidenceFixtures.command(
                ControlLifecycleEvidenceFixtures.descriptor(id = "lc-$t", transition = t), life = f.tracker.lifetimeId))
            val access = f.boundary.accesses
            rejected(CompletionRejectionReason.UnsupportedInThisUnit)(controlTestTimeout("unsupported $t") {
                store(f).handoffAfterUncertainConfirm(other, closure(other), d.handoff) })
            assertEquals("C3a.T1_03 $t: no owner access", access, f.boundary.accesses)
            val gate = controlTestTimeout("inspect $t") { store(f).inspectCurrentUncertainHandoffGate(other, closure(other), d.handoff) }
            assertEquals("C3a.T1_03 $t: the observation gate refuses it too",
                HandoffGateDecision.Rejected(HandoffGateRefusal.Precondition(CompletionRejectionReason.UnsupportedInThisUnit)), gate)
        }
        check(c.lifecycleState == ControlCommandLifecycle.RETAINED)
    }

    // ═══ T5 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T5_01_exactOwnRow_onlyItRemoved_everythingElseInOrder() = runReleaseTest {
        val f = fixture()
        val otherA = ControlLifecycleEvidenceFixtures.command(ControlLifecycleEvidenceFixtures.descriptor(id = "other-a"))
        val otherB = ControlLifecycleEvidenceFixtures.command(ControlLifecycleEvidenceFixtures.descriptor(id = "other-b"))
        val (c, d) = ready(f) { p ->
            p[evidenceKey] = "[${ControlLifecycleEvidenceFixtures.wire(otherA)},${ControlLifecycleEvidenceFixtures.wire(otherB)}]"
            p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey) + Json.parseToJsonElement(ControlObligationFixtures.request)).toString()
        }
        // Execute appends the own row; move it between the two other rows (content unchanged).
        f.edit { p -> val rows = arr(p.toPreferences(), evidenceKey)
            val own = rows.single { rowCommand(it) == c.id }; val others = rows.filter { rowCommand(it) != c.id }
            p[evidenceKey] = JsonArray(listOf(others[0], own, others[1])).toString() }
        check(arr(f.disk(), evidenceKey).map { rowCommand(it) } == listOf("other-a", c.id, "other-b")) { "fixture: row order" }
        val before = f.disk()
        completed("T5_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("other rows kept in order", listOf("other-a", "other-b"), arr(f.disk(), evidenceKey).map { rowCommand(it) })
        assertEquals("demands kept", before[demandKey], f.disk()[demandKey])
    }

    @Test fun T5_02_wholeAbsenceAtTheFirstRead_confirmed_andAReturningRowIsNeverPromoted() = runReleaseTest {
        val f = fixture(); val c = lifecycleU(f); absent(f, c)
        val d = declare(f, c); val before = f.disk()
        completed("T5_02", f, c, handoff(f, c, d), withoutBarrier(before))

        val g = fixture(); val e = lifecycleU(g); val present = g.disk(); absent(g, e)
        val dg = declare(g, e); val beforeG = g.disk()
        val fixed = pendingByWriteFault(g, e, dg)
        assertNull("absence fixed at the first owner read", fixed.expectedOwn)
        assertEquals("nothing landed", beforeG, g.disk())
        g.edit { it.clear(); it += withoutBarrier(present) }
        check(ownIn(g.disk(), e) == 1) { "fixture: own row back" }
        heldRetry("T5_02 reappeared", g, e, dg, fixed, inconsistent)

        // Absence fixed, then a row of another kind appears under this command: evidence mismatch, not a reclamation.
        val k = fixture(); val n = lifecycleU(k); absent(k, n)
        val dk = declare(k, n); val fixedK = pendingByWriteFault(k, n, dk)
        k.edit { p -> p[evidenceKey] = """[{"version":2,"commandId":"${n.id}","ownerTrackingLifetimeId":"${n.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"g","joined":false,"written":true}]}]""" }
        heldRetry("T5_02 otherKind", k, n, dk, fixedK, conflict)
    }

    @Test fun T5_03_anOwnRowThatIsNotTheExpectedOne_conflict_andAnOpaqueOneIsUninterpretable() = runReleaseTest {
        val cases = listOf<Pair<String, (MutablePreferences, CommandRef) -> Unit>>(
            "kind" to { p, c -> p[evidenceKey] = """[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"g","joined":false,"written":true}]}]""" },
            "lifetime" to { p, c -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(c.ownerTrackingLifetimeId.value, OwnerTrackingLifetimeId.issue().value)
                .also { check(it != p[evidenceKey]) { "fixture edit" } } },
            "transition" to { p, _ -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"transition\":\"REMOVE_EMPTY_GUARD\"", "\"transition\":\"SETTLE_QUERY\"")
                .also { check(it != p[evidenceKey]) { "fixture edit" } } },
            "target" to { p, _ -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"id\":\"g\"", "\"id\":\"g2\"")
                .also { check(it != p[evidenceKey]) { "fixture edit" } } })
        for ((name, change) in cases) {
            val f = fixture(); val (c, d) = ready(f)
            f.edit { change(it, c) }
            heldFirst("T5_03 $name", f, c, d, { r -> assertEquals("C3a.T5_03 $name: Conflict(CommandEvidenceMismatch), got $r",
                ConflictReason.CommandEvidenceMismatch, (r as? ControlCompletionResult.Conflict)?.reason) })
        }
        val h = fixture(); val (m, dm) = ready(h)
        h.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(",\"transition\":\"REMOVE_EMPTY_GUARD\"", "")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldFirst("T5_03 opaque", h, m, dm, recovery(RecoveryReason.UninterpretableMetadata))
    }

    // ═══ T6 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T6_01_anotherRefOnTheOwnRow_dependencyPresent_unrelatedRefsPassAndStay() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val m = f.mutations()
        val dep = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(dep))
        heldFirst("T6_01 row", f, c, d, rejected(CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value))))

        val g = fixture(); val (e, dg) = ready(g)
        val u = g.mutations(); g.addUnresolved(u)
        val p = g.mutations()
        val pRow = ControlReleaseFixtures.row(p, id = p.id, lifetime = p.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(g.tracker, p, ReleasePendingDescriptor.ExactMutations(pRow))
        val w0 = g.tracker.recoverySnapshot(); val before = g.disk()
        completed("T6_01 unrelated", g, e, handoff(g, e, dg), expectedAfterDeletion(before, e))
        val w = g.tracker.recoverySnapshot()
        assertEquals("U without e unchanged", w0.unresolvedCommands - e, w.unresolvedCommands)
        assertEquals("P without e unchanged", w0.pendingReleases - e, w.pendingReleases)
    }

    @Test fun T6_02_writeFaultFixesTheExactDescriptor_retryDeletesWithoutTheFirstDestinations() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNotNull("exact own fixed", fixed.expectedOwn)
        assertSame("the tracked expected row", f.history(c).expectedApplied, fixed.expectedOwn)
        assertEquals(LifecycleTransition.REMOVE_EMPTY_GUARD, fixed.transition)
        assertEquals(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), fixed.deletionIdentity)
        assertSame(c, fixed.closureBinding.command)
        assertEquals(CompletionMode.ResponsibilityTransferred, fixed.mode)
        assertEquals(TerminationEntry.UncertainLifecycle, fixed.entry)
        assertEquals("nothing landed", before, f.disk())
        // The first owner decision is not asked for again: an unrelated demand appearing before the retry changes nothing.
        f.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey) + Json.parseToJsonElement(ControlObligationFixtures.request)).toString() }
        val beforeRetry = f.disk()
        completed("T6_02", f, c, retry(f, c, d), expectedAfterDeletion(beforeRetry, c))
    }

    @Test fun T6_03_returnSnapshotContaminated_pendingKept() = runReleaseTest {
        for (case in listOf("ownRowBack", "unrelatedKey")) {
            val f = fixture(); val (c, d) = ready(f); val ownRows = checkNotNull(f.disk()[evidenceKey]); val before = f.disk()
            f.boundary.afterReturn = { p -> p.toMutablePreferences().apply {
                if (case == "ownRowBack") this[evidenceKey] = ownRows else this[demandKey] = "[${ControlObligationFixtures.request}]"
            }.toPreferences() }
            val failure = runCatching { handoff(f, c, d) }.exceptionOrNull()
            assertEquals("C3a.T6_03 $case: invariant propagates", IllegalStateException::class.java, failure?.javaClass)
            assertEquals("C3a.T6_03 $case: the stored candidate landed", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            assertEquals("C3a.T6_03 $case: pending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertTrue("C3a.T6_03 $case: descriptor", f.history(c).terminationDescriptor is TerminationPendingDescriptor.LifecycleHandoff)
            val w = f.tracker.recoverySnapshot()
            assertTrue("C3a.T6_03 $case: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
            assertTrue("C3a.T6_03 $case: lease released", f.tracker.executing.isEmpty())
        }
    }

    @Test fun T6_04_landedThenReturnFailed_retryConfirmsAbsence() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.afterScope = false }
        assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        assertNotNull("exact descriptor", (f.history(c).terminationDescriptor as TerminationPendingDescriptor.LifecycleHandoff).expectedOwn)
        val landed = f.disk()
        assertEquals("deletion landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        completed("T6_04", f, c, retry(f, c, d), withoutBarrier(landed))
    }

    @Test fun T6_05_newDependentAfterTheDescriptor_heldOnRetry_thenCompletesWhenItLeaves() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        val m = f.mutations()
        val dep = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(dep))
        heldRetry("T6_05", f, c, d, fixed, rejected(CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value))))
        ControlReleaseFixtures.simulateReleased(f.tracker, m)
        completed("T6_05", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    /** The fixed descriptor projects the own row; a descriptor whose transition is not the body's is a gap keeping that row. */
    @Test fun T6_06_lifecycleHandoffProjection_ownRow_mismatchIsAGap() {
        val life = OwnerTrackingLifetimeId.issue()
        val ref = ControlLifecycleEvidenceFixtures.command(life = life)
        val row = ControlLifecycleEvidenceFixtures.row(ref)
        fun descriptor(own: AppliedEvidence.Lifecycle?, transition: LifecycleTransition = LifecycleTransition.REMOVE_EMPTY_GUARD) =
            TerminationPendingDescriptor.LifecycleHandoff(closure(ref).binding(), own, transition, DependencyAtom.AppliedRow(ref.id, life.value))
        fun project(d: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(ref.id, life.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, ref.body), emptyList(), null, d))
        for ((name, own) in listOf("exact" to row, "absent" to null)) {
            val p = project(descriptor(own))
            assertTrue("C3a.T6_06 $name: Known with the own row, got $p", p is DependencyProjection.Known &&
                DependencyAtom.AppliedRow(ref.id, life.value) in p.dependencies)
        }
        val pm = project(descriptor(row, LifecycleTransition.RECOVER_HOLD))
        assertTrue("C3a.T6_06 mismatch: a gap keeping the own row, got $pm", pm is DependencyProjection.Unknown &&
            DependencyGapCause.TerminationDescriptorMismatch in pm.gaps.map { it.cause } &&
            DependencyAtom.AppliedRow(ref.id, life.value) in pm.knownDependencies)
    }
}
