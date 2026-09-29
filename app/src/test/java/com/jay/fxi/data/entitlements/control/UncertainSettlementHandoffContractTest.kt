package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
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
 * Claude-owned 6-4bC2b contract (6-4bC2 consensus r1 C2-D1; declaration 6-4bC2b_decl_codex.r1.md): a current-lifetime
 * OnceConfirm·U retired-namespace settlement (SettleRetiredNamespace, "R") is terminated by handing over its L/N responsibilities
 * through handoffAfterUncertainConfirm, and retried on its fixed TerminationPendingDescriptor.SettlementHandoff. N and L bodies
 * stay UnsupportedInThisUnit here (C2c/d). The paths C2a locked once for every kind (the refusals before storage, cancellation,
 * read failure, reopen) are not repeated.
 *  - The U fixture is a real R execute whose Confirm landed and whose return failed (afterScope).
 *  - B1 declares only the Required slots of RequirementInput.Settlement: SEAL L/N completed and consumed, JOURNAL L/N retained on
 *    the journal locator, and — when the target seal's owner is the fence owner — REQUEST L/N completed and consumed after R's
 *    REQUEST is consumed (6 slots). A departed owner has no REQUEST slot (4 slots). R has no NAMESPACE_RETIREMENT slot. The base
 *    fixture removes R's REQUEST from the record to stand for that consumption; T7 consumes it with a real SETTLE_QUERY. Measured
 *    with assessG05 before this contract (C2b_scratch_probe_outputs.r1.txt): the exact, the wholly absent (journal kept) and the
 *    departed declarations are Accepted.
 *  - Success removes exactly the own Applied row and this operation's seal; every other key keeps its value and order. When the own
 *    row and every seal of the operation are absent at the first owner read, the handoff confirms that absence.
 *  - Orphans, partial bundles, a changed fixed seal id, witness changes, extra seals of the operation and replacements at a fixed id
 *    are held without deletion (InconsistentReclamation); another own kind or lifetime is Conflict(CommandEvidenceMismatch); opaque
 *    rows are the uninterpretable reasons.
 * The implementation thread reads but does not edit this file.
 */
class UncertainSettlementHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("C2b cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val R = RetiredNamespaceFixtures
    private val N = NamespaceSettlementFixtures
    private val now = BootReading("boot", 20_000)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val L = LandingBranch.L
    private val N_ = LandingBranch.N
    private val departedContext = AttemptContext("B", 9, RetiredNamespaceFixtures.life, false, false)

    private fun store(f: TerminationFixture) = ControlRecordStore(f.storage.owner, bootReadingSource = BootReadingSource { now })
    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(p[key] ?: "[]").jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun ownIn(p: Preferences, c: CommandRef) = arr(p, evidenceKey).count { rowCommand(it) == c.id }
    /** Independent oracle: the before record minus exactly the own row and this operation's seals; every other key equal. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    // ── the U settlement and its declaration ───────────────────────────────────────────────────────────────────────
    /** A real R whose Confirm landed and whose return failed: OnceConfirm·U, own row and the settled seal on disk. */
    private suspend fun settlementU(f: TerminationFixture, s: RetiredNamespaceSettlement = R.spec(), ctx: AttemptContext = R.context,
        seed: (MutablePreferences) -> Unit = {}): CommandRef {
        f.edit { it.clear(); it += R.raw(s); seed(it) }
        val c = f.tracker.registerPrepared(R.command(s, f.tracker.lifetimeId))
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("R execute") { f.store.execute(c, ctx) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: R Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        val disk = f.disk()
        check(ownIn(disk, c) == 1) { "fixture: own row landed" }
        check(arr(disk, sealKey).count { op(it) == c.id } == 1) { "fixture: seal settled by c" }
        return c
    }
    /** Stands for R's REQUEST consumed by a later command (T7 does it with a real SETTLE_QUERY). */
    private suspend fun consumeRequest(f: TerminationFixture, s: RetiredNamespaceSettlement = R.spec()) =
        f.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey).filter { id(it) != s.demandId }).toString() }
    /** The own row and this operation's seal are gone (the journal stays: R already retired the namespace). */
    private suspend fun absent(f: TerminationFixture, c: CommandRef) = f.edit { p ->
        p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { op(it) != c.id }).toString()
    }
    private suspend fun lockedRead(f: TerminationFixture) = controlTestTimeout("locked read") {
        f.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>,
        val all: List<RequiredSlot>)
    private fun derivation(c: CommandRef) = deriveRequiredObligations(RequirementInput.Settlement(c,
        c.body as ControlCommandBody.SettleRetiredNamespace)) as RequirementDerivation.Available
    private suspend fun declare(f: TerminationFixture, c: CommandRef): Declared {
        val body = c.body as ControlCommandBody.SettleRetiredNamespace
        val input = RequirementInput.Settlement(c, body)
        val a = derivation(c)
        val slots = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
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
        return Declared(issued.closure, issued.handoff, slots, a.orderedSlots)
    }
    private suspend fun ready(f: TerminationFixture, s: RetiredNamespaceSettlement = R.spec(), ctx: AttemptContext = R.context,
        seed: (MutablePreferences) -> Unit = {}): Pair<CommandRef, Declared> {
        val c = settlementU(f, s, ctx, seed); if (s.demandId != null) consumeRequest(f, s); return c to declare(f, c)
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
        assertTrue("C2b.$id: Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        r as ControlCompletionResult.Completed
        val after = f.disk()
        assertEquals("C2b.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("C2b.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("C2b.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("C2b.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("C2b.$id: commandRemoved", f.tracker.findPrepared(c))
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2b.$id: out of U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("C2b.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldFirst(id: String, f: TerminationFixture, c: CommandRef, d: Declared, check: (ControlCompletionResult) -> Unit,
        h: CompletionHandoff = d.handoff) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        check(handoff(f, c, d, h))
        assertEquals("C2b.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("C2b.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("C2b.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("C2b.$id: recordUntouched", before, f.storage.raw())
        assertTrue("C2b.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldRetry(id: String, f: TerminationFixture, c: CommandRef, d: Declared, fixed: TerminationPendingDescriptor,
        check: (ControlCompletionResult) -> Unit) {
        f.armReadBack()
        val before = f.storage.raw()
        check(retry(f, c, d))
        assertEquals("C2b.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("C2b.$id: descriptorKept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2b.$id: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("C2b.$id: noConfirm", before, f.storage.raw())
        assertTrue("C2b.$id: leaseReleased", f.tracker.executing.isEmpty())
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
    @Test fun T1_01_requiredSlots_sameOwnerSix_departedFour_issuanceTouchesNothing() = runReleaseTest {
        for ((name, s, ctx, count) in listOf(Quad("same", R.spec(), R.context, 6), Quad("departed", R.departed(), departedContext, 4))) {
            val f = fixture(); val c = settlementU(f, s, ctx); if (s.demandId != null) consumeRequest(f, s)
            val before = f.disk(); val body = c.captureStateAndBody().body; val registered = f.tracker.findPrepared(c)
            val work = f.tracker.recoverySnapshot()
            val d = declare(f, c)
            val keys = d.handoff.slots.map { it.key }
            assertEquals("C2b.T1_01 $name: each Required key once", d.required.map { it.key }, keys)
            assertEquals("C2b.T1_01 $name: count", count, keys.size)
            assertEquals("C2b.T1_01 $name: distinct", count, keys.toSet().size)
            val components = if (count == 6) listOf(ObligationComponent.SEAL, ObligationComponent.JOURNAL, ObligationComponent.REQUEST)
                else listOf(ObligationComponent.SEAL, ObligationComponent.JOURNAL)
            assertEquals("C2b.T1_01 $name: components", components.flatMap { listOf(it to L, it to N_) }.toSet(),
                keys.map { it.component to it.branch }.toSet())
            assertEquals("C2b.T1_01 $name: issuance wrote nothing", before, f.disk())
            assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
            assertSame("C2b.T1_01 $name: body the same object", body, c.captureStateAndBody().body)
            assertSame("C2b.T1_01 $name: registered ref the same object", registered, f.tracker.findPrepared(c))
            assertEquals("C2b.T1_01 $name: recovery sets unchanged", work, f.tracker.recoverySnapshot())
        }
    }
    private data class Quad(val name: String, val s: RetiredNamespaceSettlement, val ctx: AttemptContext, val count: Int)

    @Test fun T1_02_coverageAndExtra_g05() = runReleaseTest {
        for (component in listOf(ObligationComponent.JOURNAL, ObligationComponent.REQUEST)) {
            val f = fixture(); val (c, d) = ready(f)
            val missing = d.required.single { it.key.component == component && it.key.branch == N_ }
            val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
            heldFirst("T1_02 missing $component N", f, c, d,
                rejected(CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_N, missing.key, fixedAt(missing), null, null)))), forged)
        }
        val g = fixture(); val (c, d) = ready(g, R.departed(), departedContext)
        val extra = d.all.first { it.key.component == ObligationComponent.REQUEST }
        check(extra.requirement !is SlotRequirement.Required) { "fixture: departed REQUEST is not required" }
        val slots = d.handoff.slots + SlotHandoff(extra.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(extra.key.subject), emptyList()))
        val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, slots)
        heldFirst("T1_02 extra REQUEST", g, c, d, rejected(CompletionRejectionReason.G05(listOf(
            G05Failure(G05Id.EXTRA, extra.key, null, G05Location.Submitted(slots.size - 1), null)))), forged)
    }

    // ═══ T5 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T5_01_exactBundle_onlyTheOwnRowAndItsSealRemoved_othersInOrder() = runReleaseTest {
        val f = fixture()
        val other = N.input(targets = listOf(node(N.krx)), op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        val foreign = N.settled(other, N.raw("[${N.krx}]"), N.trackerLife)
        val krx2 = N.krx.replace("\"id\":\"c\"", "\"id\":\"c2\"").also { check(it != N.krx) }
        val other2 = N.input(targets = listOf(node(krx2)), op = "00000000-0000-0000-0000-000000000011", did = "00000000-0000-0000-0000-000000000012")
        val foreign2 = N.settled(other2, N.raw("[$krx2]"), N.trackerLife)
        val (c, d) = ready(f) { p ->
            p[sealKey] = JsonArray(arr(foreign, sealKey) + arr(p.toPreferences(), sealKey) + arr(foreign2, sealKey)).toString()
            p[evidenceKey] = JsonArray(arr(foreign, evidenceKey) + arr(foreign2, evidenceKey)).toString()
            p[demandKey] = "[${ControlObligationFixtures.request}]"
        }
        // Execute appends the own row; move it between the two other operations' rows (content unchanged).
        f.edit { p -> val rows = arr(p.toPreferences(), evidenceKey)
            val own = rows.single { rowCommand(it) == c.id }; val others = rows.filter { rowCommand(it) != c.id }
            p[evidenceKey] = JsonArray(listOf(others[0], own, others[1])).toString() }
        check(arr(f.disk(), sealKey).map { id(it) } == listOf("c", "s", "c2")) { "fixture: seal order" }
        check(arr(f.disk(), evidenceKey).map { rowCommand(it) } == listOf(other.operationId, c.id, other2.operationId)) { "fixture: row order" }
        val before = f.disk()
        completed("T5_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("other seals kept in order", arr(before, sealKey).filter { op(it) != c.id }, arr(f.disk(), sealKey))
        assertEquals("other rows kept in order", listOf(other.operationId, other2.operationId), arr(f.disk(), evidenceKey).map { rowCommand(it) })
        assertEquals("unrelated REQUEST kept", before[demandKey], f.disk()[demandKey])
        assertEquals("journal kept", before[DataStoreAccessEpochStore.PURGE_JOURNAL], f.disk()[DataStoreAccessEpochStore.PURGE_JOURNAL])
    }

    @Test fun T5_02_wholeAbsenceAtTheFirstRead_absenceConfirm() = runReleaseTest {
        val f = fixture(); val c = settlementU(f); consumeRequest(f); absent(f, c)
        val d = declare(f, c)
        val before = f.disk()
        completed("T5_02", f, c, handoff(f, c, d), withoutBarrier(before))
    }

    @Test fun T5_03_orphanPartialOrChangedSealId_inconsistent() = runReleaseTest {
        val cases = listOf<Pair<String, (MutablePreferences, CommandRef) -> Unit>>(
            "orphan" to { p, c -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() },
            "sealGone" to { p, c -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { op(it) != c.id }).toString() },
            "rowSealId" to { p, _ -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"sealIds\":[\"s\"]", "\"sealIds\":[\"x\"]")
                .also { check(it != p[evidenceKey]) { "fixture edit" } } })
        for ((name, change) in cases) {
            val f = fixture(); val (c, d) = ready(f)
            f.edit { change(it, c) }
            heldFirst("T5_03 $name", f, c, d, inconsistent)
        }
    }

    @Test fun T5_04_witnessChangedOnRetry_orAnExtraSealOfThisOperation_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val fixed = pendingByWriteFault(f, c, d)
        f.edit { p -> p[sealKey] = checkNotNull(p[sealKey]).replace("\"originLifetimeId\":\"r-origin\"", "\"originLifetimeId\":\"r-other\"")
            .also { check(it != p[sealKey]) { "fixture edit" } } }
        heldRetry("T5_04 witness", f, c, d, fixed, inconsistent)
        val g = fixture(); val (e, dg) = ready(g)
        val settled = arr(g.disk(), sealKey).single { op(it) == e.id }
        val extra = Json.parseToJsonElement(settled.toString().replace("\"id\":\"s\"", "\"id\":\"s2\""))
        check(op(extra) == e.id && id(extra) == "s2")
        g.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        heldFirst("T5_04 extraSeal", g, e, dg, inconsistent)
    }

    @Test fun T5_05_otherOwnKindOrLifetime_conflict_opaqueRows_uninterpretable() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        f.edit { p -> p[evidenceKey] = """[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}]""" }
        heldFirst("T5_05 kind", f, c, d, conflict)
        val g = fixture(); val (e, dg) = ready(g)
        g.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(e.ownerTrackingLifetimeId.value, OwnerTrackingLifetimeId.issue().value)
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldFirst("T5_05 lifetime", g, e, dg, conflict)
        val h = fixture(); val (m, dm) = ready(h)
        h.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(",\"transition\":\"RETIRED_NAMESPACE\"", "")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldFirst("T5_05 metadata", h, m, dm, recovery(RecoveryReason.UninterpretableMetadata))
        val k = fixture(); val (n, dn) = ready(k)
        k.edit { p -> p[sealKey] = checkNotNull(p[sealKey]).replace("\"settlement\":{", "\"settlement\":{\"version\":3,").also { check(it != p[sealKey]) } }
        heldFirst("T5_05 seal", k, n, dn, recovery(RecoveryReason.UninterpretableObligations))
    }

    @Test fun T5_06_departedOwner_fourSlots_completesWithoutTouchingRequests() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f, R.departed(), departedContext)
        val before = f.disk()
        completed("T5_06", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("no REQUEST created or removed", before[demandKey], f.disk()[demandKey])
    }

    /** T5-07 (battery r1): whole absence of the fixed seal, but a seal of this operation under another id remains: held. */
    @Test fun T5_07_absentButAnotherSealOfThisOperationRemains_inconsistent() = runReleaseTest {
        val f = fixture(); val c = settlementU(f); consumeRequest(f)
        val settled = arr(f.disk(), sealKey).single { op(it) == c.id }
        absent(f, c)
        val d = declare(f, c)
        val extra = Json.parseToJsonElement(settled.toString().replace("\"id\":\"s\"", "\"id\":\"s2\""))
        check(op(extra) == c.id && id(extra) == "s2")
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        heldFirst("T5_07", f, c, d, inconsistent)
    }

    /**
     * T5-08 (battery r1): R's witness confirm path leaves the tracked expected row null; with a read-back obligation that Confirm
     * writes and fails after its scope (OnceConfirm·U). The absence fixed at the first owner read still completes on retry.
     */
    @Test fun T5_08_absenceWithANullTrackedExpectedRow_retryCompletes() = runReleaseTest {
        val f = fixture(); val s = R.spec()
        val c0 = R.command(s, f.tracker.lifetimeId)
        val witnessed = R.landed(c0, s).toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
        f.edit { it.clear(); it += witnessed }
        val c = f.tracker.registerPrepared(R.command(s, f.tracker.lifetimeId))
        f.armReadBack()
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("witness execute") { f.store.execute(c, R.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed && f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: U, got $r" }
        check(f.history(c).expectedApplied == null) { "fixture: tracked expected row null" }
        consumeRequest(f); absent(f, c)
        val d = declare(f, c)
        val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNull("absence fixed", fixed.expectedOwn)
        completed("T5_08", f, c, retry(f, c, d), withoutBarrier(before))
    }

    /** T5-09 (battery r1): absence fixed, then the own row and the settled seal come back exactly: not promoted, held. */
    @Test fun T5_09_absenceFixed_thenTheBundleReappears_notPromoted_held() = runReleaseTest {
        val f = fixture(); val c = settlementU(f); consumeRequest(f)
        val present = f.disk()
        absent(f, c)
        val d = declare(f, c); val fixed = pendingByWriteFault(f, c, d)
        check(fixed.expectedOwn == null)
        f.edit { it.clear(); it += withoutBarrier(present) }
        check(ownIn(f.disk(), c) == 1 && arr(f.disk(), sealKey).count { op(it) == c.id } == 1) { "fixture: bundle back" }
        heldRetry("T5_09", f, c, d, fixed, inconsistent)
    }

    // ═══ T6 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T6_01_absenceFixed_retryCompletes_replacementHeld() = runReleaseTest {
        val f = fixture(); val c = settlementU(f); consumeRequest(f); absent(f, c)
        val d = declare(f, c); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNull("absence fixed", fixed.expectedOwn)
        assertEquals("file unchanged", before, f.disk())
        completed("T6_01", f, c, retry(f, c, d), withoutBarrier(before))
        val g = fixture(); val e = settlementU(g); consumeRequest(g); absent(g, e)
        val dg = declare(g, e); val fixedG = pendingByWriteFault(g, e, dg)
        g.edit { p -> p[sealKey] = "[${N.user}]" }
        heldRetry("T6_01 replacement", g, e, dg, fixedG, inconsistent)
    }

    @Test fun T6_02_newDependentAfterTheDescriptor_projectionHeldThenCompletes() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        val p = projectDependency(DependencyProjectionInput(c.id, c.ownerTrackingLifetimeId.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, c.body), emptyList(), null, fixed))
        assertTrue("projection of the fixed descriptor, got $p", p is DependencyProjection.Known && p.dependencies.containsAll(setOf(
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), DependencyAtom.ControlRow(ControlKind.SEAL, "s"),
            DependencyAtom.SealWitness("s", c.id))))
        val mismatch = TerminationPendingDescriptor.SettlementHandoff(fixed.closureBinding, fixed.expectedOwn, fixed.transition,
            fixed.operationId, listOf("x"), fixed.demandId, fixed.deletionIdentity)
        val pm = projectDependency(DependencyProjectionInput(c.id, c.ownerTrackingLifetimeId.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, c.body), emptyList(), null, mismatch))
        assertTrue("fixed seal id mismatch is a gap keeping the descriptor's atoms, got $pm", pm is DependencyProjection.Unknown &&
            DependencyGapCause.TerminationDescriptorMismatch in pm.gaps.map { it.cause } &&
            pm.knownDependencies.containsAll(setOf(DependencyAtom.ControlRow(ControlKind.SEAL, "x"), DependencyAtom.SealWitness("x", c.id))))
        val settled = node(arr(f.storage.raw(), sealKey).single { id(it) == "s" }.toString())
        val u = CommandRef("u-edit", listOf(ControlMutation.Edit.prepare(ControlKind.SEAL, settled) {}), OwnerTrackingLifetimeId.issue())
        f.addUnresolved(u)
        heldRetry("T6_02", f, c, d, fixed, rejected(CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, "s"))))
        val w = f.tracker.recoverySnapshot()
        ControlReleaseFixtures.replaceRecovery(f.tracker, LocalRecoveryWork(w.unresolvedCommands - u, w.pendingReleases))
        completed("T6_02", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    @Test fun T6_03_writeFaultFixesTheExactDescriptor_retryDeletesWithoutTheFirstDestinations() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNotNull("exact own fixed", fixed.expectedOwn)
        assertSame("the tracked expected row", f.history(c).expectedApplied, fixed.expectedOwn)
        assertEquals(HandoverSettlementTransition.RETIRED_NAMESPACE, fixed.transition)
        assertEquals(c.id, fixed.operationId)
        assertEquals(listOf("s"), fixed.orderedSealIds)
        assertEquals("r-demand", fixed.demandId)
        assertEquals(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), fixed.deletionIdentity)
        assertSame(c, fixed.closureBinding.command)
        assertEquals(CompletionMode.ResponsibilityTransferred, fixed.mode)
        assertEquals(TerminationEntry.UncertainSettlement, fixed.entry)
        assertEquals("nothing landed", before, f.disk())
        f.edit { it.remove(DataStoreAccessEpochStore.PURGE_JOURNAL) }
        val beforeRetry = f.disk()
        completed("T6_03", f, c, retry(f, c, d), expectedAfterDeletion(beforeRetry, c))
    }

    @Test fun T6_04_returnSnapshotContaminated_settlementValidator_pendingKept() = runReleaseTest {
        for (case in listOf("ownRowBack", "unrelatedKey")) {
            val f = fixture(); val (c, d) = ready(f); val ownRows = checkNotNull(f.disk()[evidenceKey]); val before = f.disk()
            f.boundary.afterReturn = { p -> p.toMutablePreferences().apply {
                if (case == "ownRowBack") this[evidenceKey] = ownRows else this[demandKey] = "[${ControlObligationFixtures.request}]"
            }.toPreferences() }
            val failure = runCatching { handoff(f, c, d) }.exceptionOrNull()
            assertEquals("C2b.T6_04 $case: invariant propagates", IllegalStateException::class.java, failure?.javaClass)
            assertEquals("C2b.T6_04 $case: the stored candidate landed", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            assertEquals("C2b.T6_04 $case: pending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertTrue("C2b.T6_04 $case: descriptor", f.history(c).terminationDescriptor is TerminationPendingDescriptor.SettlementHandoff)
            val w = f.tracker.recoverySnapshot()
            assertTrue("C2b.T6_04 $case: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
            assertTrue("C2b.T6_04 $case: lease released", f.tracker.executing.isEmpty())
        }
    }

    /** T6-05 (battery r1): the exact deletion landed but the return failed; the retry on the exact descriptor confirms the absence. */
    @Test fun T6_05_landedThenReturnFailed_retryConfirmsAbsence() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.afterScope = false }
        assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        assertNotNull("exact descriptor", (f.history(c).terminationDescriptor as TerminationPendingDescriptor.SettlementHandoff).expectedOwn)
        val landed = f.disk()
        assertEquals("deletion landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        completed("T6_05", f, c, retry(f, c, d), withoutBarrier(landed))
    }

    // ═══ T7 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    /** R's REQUEST (owner A, binding 9, origin r-origin, order 23) consumed by a real SETTLE_QUERY with a later query order 24. */
    @Test fun T7_01_realSettleQueryConsumesTheRequest_thenTheOldSettlementHandsOver() = runReleaseTest {
        val f = fixture(); val c = settlementU(f)
        val q = DemandAuthFixtures
        val origin = R.life
        val binding = LifecycleBinding(SettlementExecutor("A", 9, origin), q.identity, 1, "binding-start")
        val fence = FenceV1("A", checkNotNull(f.disk()[DataStoreAccessEpochStore.USER_EPOCH]), checkNotNull(f.disk()[DataStoreAccessEpochStore.KRX_EPOCH]))
        val started = StartedQueryV1(fence, 5, q.identity, EventOrderV1(origin, 24), 9, RefreshIntent.FORCE_PREMIUM, 0)
        val request = node(arr(f.disk(), demandKey).single { id(it) == "r-demand" }.toString())
        val s = f.store.prepareSettleQuery(listOf(request), null, null, binding,
            q.decision(q = started, before = fence, after = fence, origin = origin), LifecycleOrderSource(origin, 24))
        val rs = controlTestTimeout("settle") { f.store.execute(s, q.context(q.runtime(binding = binding,
            registrations = listOf(LifecycleQueryRegistration("query-21", started))))) }
        assertTrue("fixture: SETTLE_QUERY Confirmed, got $rs", rs is ControlStoreResult.Confirmed)
        assertTrue("fixture: REQUEST consumed", arr(f.disk(), demandKey).none { id(it) == "r-demand" })
        val consumed = controlTestTimeout("settle consumed") {
            f.store.completeLifecycleAfterConsumption(s, closure(s), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)) }
        assertTrue("fixture: successor result consumed, got $consumed", consumed is ControlCompletionResult.Completed)
        val d = declare(f, c)
        val before = f.disk()
        assertEquals("the old settlement's seal is still stored", 1, arr(before, sealKey).count { op(it) == c.id })
        completed("T7_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
    }
}
