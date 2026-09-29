package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
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
 * Claude-owned 6-4bC2d contract (6-4bC2 consensus r1 C2-D1; declaration 6-4bC2d_decl_codex.r1.md): a current-lifetime
 * OnceConfirm·U retired NULL settlement (SettleRetiredNull, "L") is terminated by handing over its L/N responsibilities through
 * handoffAfterUncertainConfirm, on the same SettlementHandoff descriptor and matcher as R and N (6-4bC2b/c). With L every settlement
 * kind is handed over. The paths C2a–C2c locked once (refusals before storage, cancellation, read failure, reopen, another own kind or
 * lifetime, opaque rows) are not repeated.
 *  - The U fixture is a real L execute whose Confirm landed and whose return failed (afterScope): the departed owner A's NULL seal s,
 *    or both()'s c and s (fixed order [s, c]), under the current fence and executor of B.
 *  - B1 declares only the Required slots of RequirementInput.Settlement: SEAL L/N completed and consumed and JOURNAL L/N retained on
 *    the departed owner's null-epoch journal (s 4 slots, both 8). L has no REQUEST slot on either branch and no NAMESPACE_RETIREMENT.
 *    Measured with assessG05 before this contract (C2d_scratch_probe_outputs.r1.txt): exact and absent, s and both, are Accepted.
 *  - The four patterns the C2a/C2b batteries found are rows from the start (T5_03–T5_05, T6_04).
 * The implementation thread reads but does not edit this file.
 */
class UncertainRetiredNullHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("C2d cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val L = RetiredNullFixtures
    private val N = NamespaceSettlementFixtures
    private val now = BootReading("boot", 20_000)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val N_ = LandingBranch.N

    private fun store(f: TerminationFixture) = ControlRecordStore(f.storage.owner, bootReadingSource = BootReadingSource { now })
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(p[key] ?: "[]").jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun ownIn(p: Preferences, c: CommandRef) = arr(p, evidenceKey).count { rowCommand(it) == c.id }
    private fun orderedIds(s: RetiredNullSettlement) = L.ordered(s).map { (it.text("id") as FieldRead.Present).value }
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    // ── the U settlement and its declaration ───────────────────────────────────────────────────────────────────────
    private suspend fun retiredNullU(f: TerminationFixture, s: RetiredNullSettlement = L.spec(), seed: (MutablePreferences) -> Unit = {}): CommandRef {
        f.edit { it.clear(); it += L.raw(s); seed(it) }
        val c = f.tracker.registerPrepared(L.command(s, f.tracker.lifetimeId))
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("L execute") { f.store.execute(c, L.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: L Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        val disk = f.disk()
        check(ownIn(disk, c) == 1) { "fixture: own row landed" }
        check(arr(disk, sealKey).count { op(it) == c.id } == orderedIds(s).size) { "fixture: seals settled by c" }
        return c
    }
    private suspend fun absent(f: TerminationFixture, c: CommandRef) = f.edit { p ->
        p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { op(it) != c.id }).toString()
    }
    private suspend fun lockedRead(f: TerminationFixture) = controlTestTimeout("locked read") {
        f.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>, val all: List<RequiredSlot>)
    private suspend fun declare(f: TerminationFixture, c: CommandRef): Declared {
        val body = c.body as ControlCommandBody.SettleRetiredNull
        val input = RequirementInput.Settlement(c, body)
        val all = (deriveRequiredObligations(input) as RequirementDerivation.Available).orderedSlots
        val slots = all.filter { it.requirement is SlotRequirement.Required }
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val e = HandoffEventBinding(c, owner)
        val locked = lockedRead(f)
        for (slot in slots) {
            val bound = (slot.requirement as SlotRequirement.Required).lowerBound
            if (bound is RequiredLowerBound.Journal) {
                val d = DestinationLocator.Journal(bound.key)
                val t = PriorStorageConfirmation.confirmRetainedSource(slot, d, locked)
                assertTrue("fixture: journal token for ${slot.key.branch}, got $t", t is RetainedSourceConfirmationResult.Issued)
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
        return Declared(issued.closure, issued.handoff, slots, all)
    }
    private suspend fun ready(f: TerminationFixture, s: RetiredNullSettlement = L.spec(), seed: (MutablePreferences) -> Unit = {}): Pair<CommandRef, Declared> {
        val c = retiredNullU(f, s, seed); return c to declare(f, c)
    }
    /** The declaration is made while the bundle is present; then the own row and every seal of the operation are removed. */
    private suspend fun readyAbsent(f: TerminationFixture, s: RetiredNullSettlement = L.spec()): Pair<CommandRef, Declared> {
        val (c, d) = ready(f, s); absent(f, c); return c to d
    }
    private suspend fun handoff(f: TerminationFixture, c: CommandRef, d: Declared, h: CompletionHandoff = d.handoff) =
        controlTestTimeout("handoff") { store(f).handoffAfterUncertainConfirm(c, d.closure, h) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, d: Declared) =
        controlTestTimeout("retry") { store(f).retryTermination(c, d.closure) }
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef, d: Declared): TerminationPendingDescriptor.SettlementHandoff {
        f.storage.storage.before = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.before = false }
        assertTrue("fixture: Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        val descriptor = f.history(c).terminationDescriptor
        assertTrue("fixture: SettlementHandoff, got $descriptor", descriptor is TerminationPendingDescriptor.SettlementHandoff)
        val w = f.tracker.recoverySnapshot(); assertTrue("fixture: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        return descriptor as TerminationPendingDescriptor.SettlementHandoff
    }

    // ── expectations ───────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun completed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("C2d.$id: Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        r as ControlCompletionResult.Completed
        val after = f.disk()
        assertEquals("C2d.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("C2d.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("C2d.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("C2d.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("C2d.$id: commandRemoved", f.tracker.findPrepared(c))
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2d.$id: out of U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("C2d.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldFirst(id: String, f: TerminationFixture, c: CommandRef, d: Declared, check: (ControlCompletionResult) -> Unit,
        h: CompletionHandoff = d.handoff) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        check(handoff(f, c, d, h))
        assertEquals("C2d.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("C2d.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("C2d.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("C2d.$id: recordUntouched", before, f.storage.raw())
        assertTrue("C2d.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldRetry(id: String, f: TerminationFixture, c: CommandRef, d: Declared, fixed: TerminationPendingDescriptor,
        check: (ControlCompletionResult) -> Unit) {
        f.armReadBack()
        val before = f.storage.raw()
        check(retry(f, c, d))
        assertEquals("C2d.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("C2d.$id: descriptorKept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2d.$id: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("C2d.$id: noConfirm", before, f.storage.raw())
        assertTrue("C2d.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private fun rejected(reason: CompletionRejectionReason): (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected Rejected($reason), got $r", reason, (r as? ControlCompletionResult.Rejected)?.reason) }
    private val inconsistent: (ControlCompletionResult) -> Unit = { r ->
        assertEquals("expected RecoveryRequired(InconsistentReclamation), got $r", RecoveryReason.InconsistentReclamation,
            (r as? ControlCompletionResult.RecoveryRequired)?.reason) }
    private fun fixedAt(slot: RequiredSlot) = G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location)
    private fun editSeal(p: MutablePreferences, sealId: String, change: (String) -> String) {
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).map {
            if (id(it) == sealId) Json.parseToJsonElement(change(it.toString())).also { n -> check(n != it) { "fixture edit of $sealId" } } else it
        }).toString()
    }

    // ═══ T1 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T1_01_requiredSlots_sFour_bothEight_noRequest_issuanceTouchesNothing_coverageAndExtra() = runReleaseTest {
        for (s in listOf(L.spec(), L.both())) {
            val f = fixture(); val c = retiredNullU(f, s)
            val before = f.disk(); val body = c.captureStateAndBody().body; val registered = f.tracker.findPrepared(c)
            val work = f.tracker.recoverySnapshot(); val writes = f.storage.storage.writes
            val d = declare(f, c)
            val ids = orderedIds(s)
            assertEquals("C2d.T1_01 $ids: issuance made no write", writes, f.storage.storage.writes)
            assertEquals("C2d.T1_01 $ids: issuance wrote nothing", before, f.disk())
            assertSame("C2d.T1_01 $ids: body", body, c.captureStateAndBody().body)
            assertSame("C2d.T1_01 $ids: registered ref", registered, f.tracker.findPrepared(c))
            assertEquals("C2d.T1_01 $ids: recovery sets", work, f.tracker.recoverySnapshot())
            val keys = d.handoff.slots.map { it.key }
            assertEquals("C2d.T1_01 $ids: each Required key once", d.required.map { it.key }, keys)
            assertEquals("C2d.T1_01 $ids: count", 4 * ids.size, keys.toSet().size)
            for (branch in LandingBranch.entries) {
                val at = keys.filter { it.branch == branch }
                assertEquals("C2d.T1_01 $ids $branch: components", mapOf(ObligationComponent.SEAL to ids.size, ObligationComponent.JOURNAL to ids.size),
                    at.groupingBy { it.component }.eachCount())
                val pairs = L.ordered(s).map { n -> (n.text("id") as FieldRead.Present).value to
                    JournalTargetV1("A", com.jay.fxi.data.entitlements.PurgeScope.valueOf((n.text("axis") as FieldRead.Present).value), null) }.toSet()
                assertEquals("C2d.T1_01 $ids $branch: JOURNAL (source id, departed owner's axis journal) pairs", pairs,
                    at.filter { it.component == ObligationComponent.JOURNAL }.map { (it.subject as ObligationSubject.Journal).let { j -> j.sourceSealId to j.key } }.toSet())
            }
            assertTrue("C2d.T1_01 $ids: REQUEST slots exist and are not required", d.all.filter { it.key.component == ObligationComponent.REQUEST }
                .let { r -> r.size == 2 && r.none { it.requirement is SlotRequirement.Required } })
        }
        val f = fixture(); val (c, d) = ready(f, L.both())
        val missing = d.required.last { it.key.component == ObligationComponent.JOURNAL && it.key.branch == N_ }
        heldFirst("T1_01 missing JOURNAL N", f, c, d, rejected(CompletionRejectionReason.G05(listOf(
            G05Failure(G05Id.COVERAGE_N, missing.key, fixedAt(missing), null, null)))),
            CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key }))
        val g = fixture(); val (e, dg) = ready(g)
        val extra = dg.all.first { it.key.component == ObligationComponent.REQUEST }
        val slots = dg.handoff.slots + SlotHandoff(extra.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(extra.key.subject), emptyList()))
        heldFirst("T1_01 extra REQUEST", g, e, dg, rejected(CompletionRejectionReason.G05(listOf(
            G05Failure(G05Id.EXTRA, extra.key, null, G05Location.Submitted(slots.size - 1), null)))),
            CompletionHandoff(dg.handoff.command, dg.handoff.responsibilityOwner, slots))
    }

    // ═══ T5 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T5_01_exactBundle_sAndBoth_othersInOrder_andWholeAbsence() = runReleaseTest {
        val other = N.input(targets = listOf(node(N.krx)), op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        val foreign = N.settled(other, N.raw("[${N.krx}]"), N.trackerLife)
        val krx2 = N.krx.replace("\"id\":\"c\"", "\"id\":\"c2\"").also { check(it != N.krx) }
        val other2 = N.input(targets = listOf(node(krx2)), op = "00000000-0000-0000-0000-000000000011", did = "00000000-0000-0000-0000-000000000012")
        val foreign2 = N.settled(other2, N.raw("[$krx2]"), N.trackerLife)
        val f = fixture(); val (c, d) = ready(f) { p ->
            p[sealKey] = JsonArray(arr(foreign, sealKey) + arr(p.toPreferences(), sealKey) + arr(foreign2, sealKey)).toString()
            p[evidenceKey] = JsonArray(arr(foreign, evidenceKey) + arr(foreign2, evidenceKey)).toString()
            p[demandKey] = "[${ControlObligationFixtures.request}]"
        }
        f.edit { p -> val rows = arr(p.toPreferences(), evidenceKey)
            val own = rows.single { rowCommand(it) == c.id }; val others = rows.filter { rowCommand(it) != c.id }
            p[evidenceKey] = JsonArray(listOf(others[0], own, others[1])).toString() }
        check(arr(f.disk(), sealKey).map { id(it) } == listOf("c", "s", "c2")) { "fixture: seal order" }
        check(arr(f.disk(), evidenceKey).map { rowCommand(it) } == listOf(other.operationId, c.id, other2.operationId)) { "fixture: row order" }
        val before = f.disk()
        completed("T5_01 s", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("other seals in order", arr(before, sealKey).filter { op(it) != c.id }, arr(f.disk(), sealKey))
        assertEquals("other rows in order", listOf(other.operationId, other2.operationId), arr(f.disk(), evidenceKey).map { rowCommand(it) })
        assertEquals("REQUEST kept", before[demandKey], f.disk()[demandKey])
        assertEquals("journal kept", before[DataStoreAccessEpochStore.PURGE_JOURNAL], f.disk()[DataStoreAccessEpochStore.PURGE_JOURNAL])
        val g = fixture(); val (e, dg) = ready(g, L.both()); val beforeG = g.disk()
        completed("T5_01 both", g, e, handoff(g, e, dg), expectedAfterDeletion(beforeG, e))
        val h = fixture(); val (m, dm) = readyAbsent(h, L.both()); val beforeH = h.disk()
        completed("T5_01 absent", h, m, handoff(h, m, dm), withoutBarrier(beforeH))
    }

    @Test fun T5_02_orphanPartialReversedOrReplacement_held() = runReleaseTest {
        val cases = listOf<Pair<String, (MutablePreferences, CommandRef) -> Unit>>(
            "orphan" to { p, c -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() },
            "sGone" to { p, _ -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "s" }).toString() },
            "cGone" to { p, _ -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "c" }).toString() },
            "reversed" to { p, _ -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"sealIds\":[\"s\",\"c\"]", "\"sealIds\":[\"c\",\"s\"]")
                .also { check(it != p[evidenceKey]) { "fixture edit" } } })
        for ((name, change) in cases) {
            val f = fixture(); val (c, d) = ready(f, L.both())
            f.edit { change(it, c) }
            heldFirst("T5_02 $name", f, c, d, inconsistent)
        }
        val g = fixture(); val (e, dg) = readyAbsent(g); val fixed = pendingByWriteFault(g, e, dg)
        check(fixed.expectedOwn == null)
        g.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + Json.parseToJsonElement(L.nullUser)).toString() }
        heldRetry("T5_02 replacement", g, e, dg, fixed, inconsistent)
    }

    /** T5-03 (pattern a): whole absence of the fixed seal, but a V2 seal of this operation under another id remains: held. */
    @Test fun T5_03_absentButAnotherSealOfThisOperationRemains_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val settled = arr(f.disk(), sealKey).single { id(it) == "s" }
        absent(f, c)
        val extra = Json.parseToJsonElement(settled.toString().replace("\"id\":\"s\"", "\"id\":\"s2\""))
        check(op(extra) == c.id && id(extra) == "s2")
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        heldFirst("T5_03", f, c, d, inconsistent)
    }

    /** T5-04 (pattern b): L's witness confirm path with a read-back obligation leaves an OnceConfirm·U with a null tracked row. */
    @Test fun T5_04_absenceWithANullTrackedExpectedRow_retryCompletes() = runReleaseTest {
        val f = fixture(); val s = L.spec()
        val witnessed = L.landed(L.command(s, f.tracker.lifetimeId), s).toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
        f.edit { it.clear(); it += witnessed }
        val c = f.tracker.registerPrepared(L.command(s, f.tracker.lifetimeId))
        f.armReadBack()
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("witness execute") { f.store.execute(c, L.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed && f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: U, got $r" }
        check(f.history(c).expectedApplied == null) { "fixture: tracked expected row null" }
        val d = declare(f, c); absent(f, c)
        val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNull("absence fixed", fixed.expectedOwn)
        completed("T5_04", f, c, retry(f, c, d), withoutBarrier(before))
    }

    /** T5-05 (pattern c): absence fixed, then the own row and the whole bundle come back exactly: not promoted, held. */
    @Test fun T5_05_absenceFixed_thenTheBundleReappears_notPromoted_held() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f, L.both())
        val present = f.disk()
        absent(f, c)
        val fixed = pendingByWriteFault(f, c, d)
        check(fixed.expectedOwn == null)
        f.edit { it.clear(); it += withoutBarrier(present) }
        check(ownIn(f.disk(), c) == 1 && arr(f.disk(), sealKey).count { op(it) == c.id } == 2) { "fixture: bundle back" }
        heldRetry("T5_05", f, c, d, fixed, inconsistent)
    }

    // ═══ T6 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T6_01_exactAndAbsentDescriptorsFixed_retriesComplete() = runReleaseTest {
        for (s in listOf(L.spec(), L.both())) {
            val f = fixture(); val (c, d) = ready(f, s); val before = f.disk()
            val fixed = pendingByWriteFault(f, c, d)
            assertNotNull("exact own fixed", fixed.expectedOwn)
            assertSame("the tracked expected row", f.history(c).expectedApplied, fixed.expectedOwn)
            assertEquals(HandoverSettlementTransition.RETIRED_NULL, fixed.transition)
            assertEquals(c.id, fixed.operationId)
            assertEquals(orderedIds(s), fixed.orderedSealIds)
            assertNull("L has no demand", fixed.demandId)
            assertEquals(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), fixed.deletionIdentity)
            assertEquals(TerminationEntry.UncertainSettlement, fixed.entry)
            assertEquals("nothing landed", before, f.disk())
            completed("T6_01 exact ${orderedIds(s)}", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
        }
        val g = fixture(); val (e, dg) = readyAbsent(g, L.both()); val beforeG = g.disk()
        val fixedG = pendingByWriteFault(g, e, dg)
        assertNull("absence fixed", fixedG.expectedOwn)
        assertEquals(HandoverSettlementTransition.RETIRED_NULL, fixedG.transition)
        assertEquals(e.id, fixedG.operationId)
        assertEquals(orderedIds(L.both()), fixedG.orderedSealIds)
        assertNull(fixedG.demandId)
        assertEquals(DependencyAtom.AppliedRow(e.id, e.ownerTrackingLifetimeId.value), fixedG.deletionIdentity)
        assertEquals("nothing landed", beforeG, g.disk())
        completed("T6_01 absent", g, e, retry(g, e, dg), withoutBarrier(beforeG))
    }

    @Test fun T6_02_bothProjection_mismatchGaps_newDependentHeldThenCompletes() = runReleaseTest {
        val f = fixture(); val s = L.both(); val (c, d) = ready(f, s); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        fun project(desc: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(c.id, c.ownerTrackingLifetimeId.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, c.body), emptyList(), null, desc))
        val atoms = setOf<DependencyAtom>(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)) +
            orderedIds(s).flatMap { listOf(DependencyAtom.ControlRow(ControlKind.SEAL, it), DependencyAtom.SealWitness(it, c.id)) }
        val p = project(fixed)
        assertTrue("projection of the fixed descriptor, got $p", p is DependencyProjection.Known && p.dependencies.containsAll(atoms))
        for ((name, desc, own) in listOf(
                Triple("transition", TerminationPendingDescriptor.SettlementHandoff(fixed.closureBinding, fixed.expectedOwn,
                    HandoverSettlementTransition.CURRENT_NULL, fixed.operationId, fixed.orderedSealIds, fixed.demandId, fixed.deletionIdentity),
                    setOf<DependencyAtom>(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value))),
                Triple("fixedId", TerminationPendingDescriptor.SettlementHandoff(fixed.closureBinding, fixed.expectedOwn,
                    fixed.transition, fixed.operationId, fixed.orderedSealIds.map { if (it == "c") "zz" else it }, fixed.demandId, fixed.deletionIdentity),
                    setOf(DependencyAtom.ControlRow(ControlKind.SEAL, "zz"), DependencyAtom.SealWitness("zz", c.id))))) {
            val pm = project(desc)
            assertTrue("C2d.T6_02 $name: TerminationDescriptorMismatch gap, got $pm", pm is DependencyProjection.Unknown &&
                DependencyGapCause.TerminationDescriptorMismatch in pm.gaps.map { it.cause })
            assertTrue("C2d.T6_02 $name: descriptor atoms kept, got $pm", (pm as DependencyProjection.Unknown).knownDependencies.containsAll(own))
        }
        val settled = node(arr(f.storage.raw(), sealKey).single { id(it) == "s" }.toString())
        val u = CommandRef("u-edit", listOf(ControlMutation.Edit.prepare(ControlKind.SEAL, settled) {}), OwnerTrackingLifetimeId.issue())
        f.addUnresolved(u)
        heldRetry("T6_02", f, c, d, fixed, rejected(CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, "s"))))
        val w = f.tracker.recoverySnapshot()
        ControlReleaseFixtures.replaceRecovery(f.tracker, LocalRecoveryWork(w.unresolvedCommands - u, w.pendingReleases))
        completed("T6_02", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    /** T6-03 (consensus): the exact retry re-checks both fixed ids, order, the operation's seal set and each V2 witness and text. */
    @Test fun T6_03_bothExactRetryRechecksV2WitnessAndBundle() = runReleaseTest {
        val cases = listOf<Pair<String, (MutablePreferences) -> Unit>>(
            "origin" to { p -> editSeal(p, "c") { it.replace("\"originLifetimeId\":\"l-origin\"", "\"originLifetimeId\":\"l-other\"") } },
            // V2 keeps before == after: the same epoch is changed in both, so the witness stays interpretable.
            "fenceEpoch" to { p -> editSeal(p, "s") { it.replace("\"userAccessEpoch\":\"u2\"", "\"userAccessEpoch\":\"u9\"") } },
            // The departed owner changed in the seal text and its journal together (the fence owner B is untouched).
            "text" to { p -> editSeal(p, "c") { it.replace("\"ownerUid\":\"A\"", "\"ownerUid\":\"Z\"") } },
            "cGone" to { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "c" }).toString() })
        for ((name, change) in cases) {
            val f = fixture(); val (c, d) = ready(f, L.both()); val fixed = pendingByWriteFault(f, c, d)
            f.edit { change(it) }
            check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable }) {
                "fixture $name: the record must stay interpretable" }
            heldRetry("T6_03 $name", f, c, d, fixed, inconsistent)
        }
        val g = fixture(); val (e, dg) = ready(g, L.both()); val before = g.disk()
        pendingByWriteFault(g, e, dg)
        g.edit { it.remove(DataStoreAccessEpochStore.PURGE_JOURNAL) }
        val beforeRetry = g.disk()
        check(beforeRetry != before) { "fixture: the first G05 destination is gone" }
        completed("T6_03 unchanged", g, e, retry(g, e, dg), expectedAfterDeletion(beforeRetry, e))
    }

    /** T6-04 (pattern d): the exact deletion landed but the return failed — the retry confirms absence; a contaminated return fails. */
    @Test fun T6_04_landedThenReturnFailed_retryConfirmsAbsence_andContaminatedReturnFails() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.afterScope = false }
        assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        assertNotNull("exact descriptor", (f.history(c).terminationDescriptor as TerminationPendingDescriptor.SettlementHandoff).expectedOwn)
        val landed = f.disk()
        assertEquals("deletion landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        completed("T6_04", f, c, retry(f, c, d), withoutBarrier(landed))
        val g = fixture(); val (e, dg) = ready(g); val ownRows = checkNotNull(g.disk()[evidenceKey]); val beforeG = g.disk()
        g.boundary.afterReturn = { p -> p.toMutablePreferences().apply { this[evidenceKey] = ownRows }.toPreferences() }
        val failure = runCatching { handoff(g, e, dg) }.exceptionOrNull()
        assertEquals("C2d.T6_04 contaminated: invariant propagates", IllegalStateException::class.java, failure?.javaClass)
        assertEquals("C2d.T6_04 contaminated: the stored candidate landed", expectedAfterDeletion(beforeG, e), withoutBarrier(g.disk()))
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, e.lifecycleState)
        assertTrue("descriptor kept", g.history(e).terminationDescriptor is TerminationPendingDescriptor.SettlementHandoff)
        val w = g.tracker.recoverySnapshot(); assertTrue("U∩P", e in w.unresolvedCommands && e in w.pendingReleases)
    }

    // ═══ T7 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    /** The old L of s; a successor L settles the departed owner's other axis c for real and its result is consumed; then the old L hands over. */
    @Test fun T7_01_realSuccessorSettlesTheOtherAxis_thenTheOldSettlementHandsOver() = runReleaseTest {
        val f = fixture()
        val c = retiredNullU(f) { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + Json.parseToJsonElement(L.nullKrx)).toString() }
        val successorSpec = L.spec(target = node(L.nullKrx), op = "l-operation-2")
        val s = f.tracker.registerPrepared(L.command(successorSpec, f.tracker.lifetimeId))
        val rs = controlTestTimeout("successor") { f.store.execute(s, L.context) }
        assertTrue("fixture: successor L Confirmed, got $rs", rs is ControlStoreResult.Confirmed)
        val consumed = controlTestTimeout("successor consumed") {
            f.store.completeSettlementAfterConsumption(s, closure(s), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)) }
        assertTrue("fixture: successor result consumed, got $consumed", consumed is ControlCompletionResult.Completed)
        val d = declare(f, c)
        val before = f.disk()
        assertEquals("the old settlement's seal is still stored", 1, arr(before, sealKey).count { op(it) == c.id })
        completed("T7_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertTrue("the old L removed only its own seal", arr(f.disk(), sealKey).none { op(it) == c.id })
    }
}
