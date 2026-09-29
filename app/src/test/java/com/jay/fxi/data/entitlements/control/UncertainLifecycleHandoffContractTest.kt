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
 * TerminationPendingDescriptor.LifecycleHandoff. 6-4bC3b opens REBIND_REQUESTS; the other five transitions stay UnsupportedInThisUnit
 * (C3c–g). The paths
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
    /** The Lifecycle transitions this contract hands over so far. */
    private val OPENED = setOf(LifecycleTransition.REMOVE_EMPTY_GUARD, LifecycleTransition.REBIND_REQUESTS)

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

    @Test fun T1_03_theTransitionsNotYetOpened_stayUnsupported() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        for (t in LifecycleTransition.entries - OPENED) {
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

    // ═══ C3b: REBIND_REQUESTS ═════════════════════════════════════════════════════════════════════════════════════════
    // Measured with assessG05 before these rows (C3/C3b_probe_outputs.r1.txt): Required = BINDING L/N, REQUEST L/N for each
    // rebound request, RECEIPT L. REQUEST L/N complete only after the rebound request is consumed by a later command; while it
    // stands, each REQUEST slot is a G05 completion conflict.
    private val D = DemandAuthFixtures

    /** A real REBIND_REQUESTS of [count] requests (binding 2 → 3) whose Confirm landed and whose return failed. */
    private suspend fun rebindU(f: TerminationFixture, count: Int = 1, seed: (MutablePreferences) -> Unit = {}): CommandRef {
        val requests = (1..count).map { D.request(id = "r$it", binding = 2) }
        f.edit { it.clear(); it += D.raw(*requests.toTypedArray()); seed(it) }
        val c = f.store.prepareRebindRequests(requests, D.binding, LifecycleOrderSource(D.life, 21))
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("REBIND execute") { f.store.execute(c, D.context(D.runtime())) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        check(ownIn(f.disk(), c) == 1) { "fixture: own row landed" }
        return c
    }
    private fun demandIds(p: Preferences) = arr(p, demandKey).map { it.jsonObject.getValue("id").jsonPrimitive.content }
    /** Stands for the rebound requests consumed by a later command (T7b does it with a real SETTLE_QUERY). */
    private suspend fun consume(f: TerminationFixture, vararg ids: String) =
        f.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey).filter { it.jsonObject.getValue("id").jsonPrimitive.content !in ids }).toString() }

    @Test fun T1_b01_rebindSlots_bindingRequestPerRebound_receipt_issuanceTouchesNothing() = runReleaseTest {
        for ((count, expected) in listOf(1 to 5, 2 to 7)) {
            val f = fixture(); val c = rebindU(f, count); consume(f, *(1..count).map { "r$it" }.toTypedArray())
            val before = f.disk(); val work = f.tracker.recoverySnapshot()
            val d = declare(f, c)
            val keys = d.handoff.slots.map { it.key }
            assertEquals("C3b.T1_b01 $count: each Required key once", d.required.map { it.key }, keys)
            assertEquals("C3b.T1_b01 $count: count", expected, keys.size)
            assertEquals("C3b.T1_b01 $count: components",
                listOf(ObligationComponent.BINDING to L, ObligationComponent.BINDING to LandingBranch.N) +
                    (1..count).flatMap { listOf(ObligationComponent.REQUEST to L, ObligationComponent.REQUEST to LandingBranch.N) } +
                    listOf(ObligationComponent.RECEIPT to L),
                keys.map { it.component to it.branch })
            assertEquals("C3b.T1_b01 $count: issuance wrote nothing", before, f.disk())
            assertEquals("C3b.T1_b01 $count: recovery sets unchanged", work, f.tracker.recoverySnapshot())
            // Each rebound request is its own pair of slots, in input order, bound at the order it was raised.
            val requests = d.required.filter { it.key.component == ObligationComponent.REQUEST }
            assertEquals("C3b.T1_b01 $count: request subjects and branches",
                (1..count).flatMap { listOf("r$it" to L, "r$it" to LandingBranch.N) },
                requests.map { (it.key.subject as ObligationSubject.Request).id to it.key.branch })
            assertEquals("C3b.T1_b01 $count: request lower bounds follow the raise order",
                (1..count).flatMap { listOf(21L + it, 21L + it) },
                requests.map { ((it.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request).minimumOrder.value })
            assertEquals("C3b.T1_b01 $count: the observation gate admits an opened transition", HandoffGateDecision.Eligible,
                controlTestTimeout("inspect") { store(f).inspectCurrentUncertainHandoffGate(c, d.closure, d.handoff) })
            completed("T1_b01 $count", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        }
    }

    @Test fun T1_b03_aMissingRequestSlotAndADuplicatedOne_areRefusedByG05() = runReleaseTest {
        val f = fixture(); val c = rebindU(f, 2); consume(f, "r1", "r2")
        val d = declare(f, c)
        val r2n = d.required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N &&
            (it.key.subject as ObligationSubject.Request).id == "r2" }
        heldFirst("T1_b03 missing r2/N", f, c, d,
            rejected(CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_N, r2n.key, fixedAt(r2n), null, null)))),
            CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != r2n.key }))

        val g = fixture(); val e = rebindU(g, 2); consume(g, "r1", "r2")
        val dg = declare(g, e)
        val r1l = dg.handoff.slots.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == L &&
            (it.key.subject as ObligationSubject.Request).id == "r1" }
        val slots = dg.handoff.slots + r1l
        heldFirst("T1_b03 duplicate r1/L", g, e, dg, { r ->
            val failures = ((r as? ControlCompletionResult.Rejected)?.reason as? CompletionRejectionReason.G05)?.failures.orEmpty()
            assertTrue("C3b.T1_b03: a DUPLICATE for r1/L at the second submission, got $r",
                failures.any { it.id == G05Id.DUPLICATE && it.key == r1l.key && it.submittedAt == G05Location.Submitted(slots.size - 1) })
            assertTrue("C3b.T1_b03: nothing but DUPLICATE refused: $failures", failures.isNotEmpty() && failures.all { it.id == G05Id.DUPLICATE })
        }, CompletionHandoff(dg.handoff.command, dg.handoff.responsibilityOwner, slots))
    }

    @Test fun T1_b02_aReboundRequestStillStanding_isAG05CompletionConflict_forItsOwnSlotsOnly() = runReleaseTest {
        val f = fixture(); val c = rebindU(f, 2); consume(f, "r1")
        val d = declare(f, c)
        val standing = d.required.filter { it.key.component == ObligationComponent.REQUEST }.drop(2)
        check(standing.size == 2) { "fixture: r2's two REQUEST slots" }
        heldFirst("T1_b02", f, c, d, { r ->
            val failures = ((r as? ControlCompletionResult.Rejected)?.reason as? CompletionRejectionReason.G05)?.failures
            assertEquals("C3b.T1_b02: completion conflicts on r2's REQUEST L and N only, got $r",
                standing.map { it.key }.toSet(), failures?.filter { it.id == G05Id.COMPLETED_CONFLICT }?.map { it.key }?.toSet())
            assertTrue("C3b.T1_b02: nothing else refused: $failures", failures.orEmpty().all { it.id == G05Id.COMPLETED_CONFLICT })
        })
    }

    @Test fun T5_b01_exactOwnRow_onlyItRemoved_otherDemandsKept() = runReleaseTest {
        val f = fixture()
        val other = D.request(id = "other", binding = 9)
        val c = rebindU(f) { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey) + Json.parseToJsonElement(other.toPayloadEntry().fields.toString())).toString() }
        consume(f, "r1")
        check(demandIds(f.disk()) == listOf("other")) { "fixture: only the unrelated request left" }
        val d = declare(f, c); val before = f.disk()
        completed("T5_b01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("the unrelated request kept", listOf("other"), demandIds(f.disk()))
    }

    @Test fun T5_b02_wholeAbsenceAtTheFirstRead_confirmed() = runReleaseTest {
        val f = fixture(); val c = rebindU(f); consume(f, "r1"); absent(f, c)
        val d = declare(f, c); val before = f.disk()
        completed("T5_b02", f, c, handoff(f, c, d), withoutBarrier(before))
    }

    @Test fun T6_b01_writeFaultFixesARebindDescriptor_retryDeletes() = runReleaseTest {
        val f = fixture(); val c = rebindU(f); consume(f, "r1")
        val d = declare(f, c); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertEquals(LifecycleTransition.REBIND_REQUESTS, fixed.transition)
        assertNotNull("exact own fixed", fixed.expectedOwn)
        assertEquals("nothing landed", before, f.disk())
        completed("T6_b01", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    /** The rebound request (owner A, binding 3, origin life, raised at 22) consumed by a real SETTLE_QUERY at order 24. */
    @Test fun T7_b01_aRealSettleQueryConsumesTheReboundRequest_thenTheRebindHandsOver() = runReleaseTest {
        val f = fixture(); val c = rebindU(f)
        val started = StartedQueryV1(D.fence, 5, D.identity, EventOrderV1(D.life, 24), 3, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, 0)
        val request = D.node(arr(f.disk(), demandKey).single { it.jsonObject.getValue("id").jsonPrimitive.content == "r1" }.toString())
        val s = f.store.prepareSettleQuery(listOf(request), null, null, D.binding, D.decision(q = started), LifecycleOrderSource(D.life, 24))
        val rs = controlTestTimeout("settle") { f.store.execute(s, D.context(D.runtime(registrations = listOf(LifecycleQueryRegistration("query-21", started))))) }
        assertTrue("fixture: SETTLE_QUERY Confirmed, got $rs", rs is ControlStoreResult.Confirmed)
        assertTrue("fixture: the rebound request consumed", "r1" !in demandIds(f.disk()))
        val consumed = controlTestTimeout("settle consumed") {
            f.store.completeLifecycleAfterConsumption(s, closure(s), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)) }
        assertTrue("fixture: the settle query's result consumed, got $consumed", consumed is ControlCompletionResult.Completed)
        val d = declare(f, c); val before = f.disk()
        completed("T7_b01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
    }
}
