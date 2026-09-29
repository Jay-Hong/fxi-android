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
 * Claude-owned 6-4bC2c contract (6-4bC2 consensus r1 C2-D1; declaration 6-4bC2c_decl_codex.r1.md): a current-lifetime
 * OnceConfirm·U current NULL settlement (RotateAndSettleCurrentNull, "N") is terminated by handing over its L/N responsibilities
 * through handoffAfterUncertainConfirm, on the same SettlementHandoff descriptor and matcher as R (6-4bC2b). The retired NULL
 * settlement (L) stays UnsupportedInThisUnit. The paths C2a locked once (refusals before storage, cancellation, read failure,
 * reopen) are not repeated.
 *  - The U fixture is a real N execute whose Confirm landed and whose return failed (afterScope). "sUs" is the USER NULL seal s with
 *    its companion us; "both" is both()'s s, us, c, ks.
 *  - B1 declares only the Required slots of RequirementInput.Settlement: SEAL L/N completed and consumed, JOURNAL L/N and each axis's
 *    NAMESPACE_RETIREMENT L/N retained on the null-epoch journal locator, REQUEST L/N completed and consumed after N's REQUEST is
 *    consumed (sUs 12 slots, both 22). The retirement tokens are issued while the seals are present; the absence rows remove the
 *    bundle after the declaration. Measured with assessG05 before this contract (C2c_scratch_probe_outputs.r1.txt): exact and absent,
 *    sUs and both, are Accepted.
 *  - The four patterns the C2a/C2b batteries found are rows from the start: another seal of this operation after absence, a null
 *    tracked expected row with a fixed absence, no promotion of a reappearing bundle, and the absence retry after a landed deletion.
 * The implementation thread reads but does not edit this file.
 */
class UncertainCurrentNullHandoffContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("C2c cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val NN = CurrentNullFixtures
    private val N = NamespaceSettlementFixtures
    private val sUs = CurrentNullFixtures.spec(companions = listOf(node(CurrentNullFixtures.companionUser)))
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
    private fun orderedIds(s: CurrentNullSettlement) = NN.ordered(s).map { (it.text("id") as FieldRead.Present).value }
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    // ── the U settlement and its declaration ───────────────────────────────────────────────────────────────────────
    private suspend fun nullU(f: TerminationFixture, s: CurrentNullSettlement = sUs, seed: (MutablePreferences) -> Unit = {}): CommandRef {
        f.edit { it.clear(); it += NN.raw(s); seed(it) }
        val c = f.tracker.registerPrepared(NN.command(s, f.tracker.lifetimeId))
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("N execute") { f.store.execute(c, NN.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed) { "fixture: N Unconfirmed, got $r" }
        check(f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: OnceConfirm·U" }
        val disk = f.disk()
        check(ownIn(disk, c) == 1) { "fixture: own row landed" }
        check(arr(disk, sealKey).count { op(it) == c.id } == orderedIds(s).size) { "fixture: seals settled by c" }
        return c
    }
    /** Stands for N's REQUEST consumed by a later command (T7 does it with a real SETTLE_QUERY). */
    private suspend fun consumeRequest(f: TerminationFixture, s: CurrentNullSettlement = sUs) =
        f.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey).filter { id(it) != s.demandId }).toString() }
    private suspend fun absent(f: TerminationFixture, c: CommandRef) = f.edit { p ->
        p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { op(it) != c.id }).toString()
    }
    private suspend fun lockedRead(f: TerminationFixture) = controlTestTimeout("locked read") {
        f.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private class Declared(val closure: TerminationClosure, val handoff: CompletionHandoff, val required: List<RequiredSlot>)
    private suspend fun declare(f: TerminationFixture, c: CommandRef): Declared {
        val body = c.body as ControlCommandBody.RotateAndSettleCurrentNull
        val input = RequirementInput.Settlement(c, body)
        val slots = (deriveRequiredObligations(input) as RequirementDerivation.Available).orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val owner = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val e = HandoffEventBinding(c, owner)
        val locked = lockedRead(f)
        for (slot in slots) {
            val journal = when (val bound = (slot.requirement as SlotRequirement.Required).lowerBound) {
                is RequiredLowerBound.Journal -> bound.key
                is RequiredLowerBound.NamespaceRetirement -> bound.scope.journalKey
                else -> null
            }
            if (journal != null) {
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
    private suspend fun ready(f: TerminationFixture, s: CurrentNullSettlement = sUs, seed: (MutablePreferences) -> Unit = {}): Pair<CommandRef, Declared> {
        val c = nullU(f, s, seed); consumeRequest(f, s); return c to declare(f, c)
    }
    /** The declaration is made while the bundle is present; then the own row and every seal of the operation are removed. */
    private suspend fun readyAbsent(f: TerminationFixture, s: CurrentNullSettlement = sUs): Pair<CommandRef, Declared> {
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
        assertTrue("C2c.$id: Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        r as ControlCompletionResult.Completed
        val after = f.disk()
        assertEquals("C2c.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("C2c.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("C2c.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("C2c.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("C2c.$id: commandRemoved", f.tracker.findPrepared(c))
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2c.$id: out of U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
        assertTrue("C2c.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldFirst(id: String, f: TerminationFixture, c: CommandRef, d: Declared, check: (ControlCompletionResult) -> Unit,
        h: CompletionHandoff = d.handoff) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        check(handoff(f, c, d, h))
        assertEquals("C2c.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("C2c.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("C2c.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("C2c.$id: recordUntouched", before, f.storage.raw())
        assertTrue("C2c.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun heldRetry(id: String, f: TerminationFixture, c: CommandRef, d: Declared, fixed: TerminationPendingDescriptor,
        check: (ControlCompletionResult) -> Unit) {
        f.armReadBack()
        val before = f.storage.raw()
        check(retry(f, c, d))
        assertEquals("C2c.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("C2c.$id: descriptorKept", fixed, f.history(c).terminationDescriptor)
        val w = f.tracker.recoverySnapshot()
        assertTrue("C2c.$id: U∩P", c in w.unresolvedCommands && c in w.pendingReleases)
        assertEquals("C2c.$id: noConfirm", before, f.storage.raw())
        assertTrue("C2c.$id: leaseReleased", f.tracker.executing.isEmpty())
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
    private fun editSeal(p: MutablePreferences, sealId: String, change: (String) -> String) {
        p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).map {
            if (id(it) == sealId) Json.parseToJsonElement(change(it.toString())).also { n -> check(n != it) { "fixture edit of $sealId" } } else it
        }).toString()
    }

    // ═══ T1 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T1_01_requiredSlots_sUsTwelve_bothTwentyTwo_issuanceTouchesNothing_andCoverage() = runReleaseTest {
        for ((s, count) in listOf(sUs to 12, NN.both() to 22)) {
            val f = fixture(); val c = nullU(f, s); consumeRequest(f, s)
            val before = f.disk(); val body = c.captureStateAndBody().body; val registered = f.tracker.findPrepared(c)
            val work = f.tracker.recoverySnapshot(); val writes = f.storage.storage.writes
            val d = declare(f, c)
            assertEquals("C2c.T1_01 $count: issuance made no write", writes, f.storage.storage.writes)
            val ids = orderedIds(s).toSet()
            val axes = if (count == 12) setOf(com.jay.fxi.data.entitlements.PurgeScope.USER)
                else setOf(com.jay.fxi.data.entitlements.PurgeScope.USER, com.jay.fxi.data.entitlements.PurgeScope.CAPABILITY)
            for (branch in LandingBranch.entries) {
                val at = d.handoff.slots.map { it.key }.filter { it.branch == branch }
                val perComponent = at.groupingBy { it.component }.eachCount()
                assertEquals("C2c.T1_01 $count $branch: components", mapOf(ObligationComponent.SEAL to ids.size, ObligationComponent.JOURNAL to ids.size,
                    ObligationComponent.NAMESPACE_RETIREMENT to axes.size, ObligationComponent.REQUEST to 1), perComponent)
                assertEquals("C2c.T1_01 $count $branch: SEAL ids", ids,
                    at.filter { it.component == ObligationComponent.SEAL }.map { (it.subject as ObligationSubject.Seal).id }.toSet())
                val journals = at.filter { it.component == ObligationComponent.JOURNAL }.map { it.subject as ObligationSubject.Journal }
                // Each seal's JOURNAL names the null-epoch journal of its own axis (one shared physical key per axis).
                val pairs = NN.ordered(s).map { node ->
                    (node.text("id") as FieldRead.Present).value to
                        JournalTargetV1("A", com.jay.fxi.data.entitlements.PurgeScope.valueOf((node.text("axis") as FieldRead.Present).value), null) }.toSet()
                assertEquals("C2c.T1_01 $count $branch: JOURNAL (source id, axis journal) pairs", pairs, journals.map { it.sourceSealId to it.key }.toSet())
                assertEquals("C2c.T1_01 $count $branch: one shared null-epoch journal per axis",
                    axes.map { JournalTargetV1("A", it, null) }.toSet(), journals.map { it.key }.toSet())
            }
            val keys = d.handoff.slots.map { it.key }
            assertEquals("C2c.T1_01 $count: each Required key once", d.required.map { it.key }, keys)
            assertEquals("C2c.T1_01 $count: count", count, keys.size)
            assertEquals("C2c.T1_01 $count: distinct", count, keys.toSet().size)
            assertEquals("C2c.T1_01 $count: issuance wrote nothing", before, f.disk())
            assertSame("C2c.T1_01 $count: body", body, c.captureStateAndBody().body)
            assertSame("C2c.T1_01 $count: registered ref", registered, f.tracker.findPrepared(c))
            assertEquals("C2c.T1_01 $count: recovery sets", work, f.tracker.recoverySnapshot())
        }
        // A companion's JOURNAL N or the axis retirement N left out: G05 coverage.
        for (component in listOf(ObligationComponent.JOURNAL, ObligationComponent.NAMESPACE_RETIREMENT)) {
            val f = fixture(); val (c, d) = ready(f)
            val missing = d.required.last { it.key.component == component && it.key.branch == N_ }
            val forged = CompletionHandoff(d.handoff.command, d.handoff.responsibilityOwner, d.handoff.slots.filter { it.key != missing.key })
            heldFirst("T1_01 missing $component N", f, c, d,
                rejected(CompletionRejectionReason.G05(listOf(G05Failure(G05Id.COVERAGE_N, missing.key, fixedAt(missing), null, null)))), forged)
        }
    }

    // ═══ T5 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T5_01_exactBundle_sUsAndBoth_onlyTheOwnRowAndAllItsSealsRemoved() = runReleaseTest {
        val other = N.input(targets = listOf(node(N.krx)), op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        val foreign = N.settled(other, N.raw("[${N.krx}]"), N.trackerLife)
        val f = fixture(); val (c, d) = ready(f) { p ->
            p[sealKey] = JsonArray(arr(foreign, sealKey) + arr(p.toPreferences(), sealKey)).toString()
            p[evidenceKey] = JsonArray(arr(foreign, evidenceKey)).toString()
            p[demandKey] = "[${ControlObligationFixtures.request}]"
        }
        val before = f.disk()
        completed("T5_01 sUs", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
        assertEquals("other seal kept", listOf("c"), arr(f.disk(), sealKey).map { id(it) })
        assertEquals("other row kept", listOf(other.operationId), arr(f.disk(), evidenceKey).map { rowCommand(it) })
        assertEquals("unrelated REQUEST kept", before[demandKey], f.disk()[demandKey])
        assertEquals("journal kept", before[DataStoreAccessEpochStore.PURGE_JOURNAL], f.disk()[DataStoreAccessEpochStore.PURGE_JOURNAL])
        val g = fixture(); val (e, dg) = ready(g, NN.both()); val beforeG = g.disk()
        completed("T5_01 both", g, e, handoff(g, e, dg), expectedAfterDeletion(beforeG, e))
        assertTrue("all four seals removed", arr(g.disk(), sealKey).none { op(it) == e.id })
    }

    @Test fun T5_02_wholeAbsenceAfterTheDeclaration_absenceConfirm() = runReleaseTest {
        val f = fixture(); val (c, d) = readyAbsent(f)
        val before = f.disk()
        completed("T5_02", f, c, handoff(f, c, d), withoutBarrier(before))
    }

    @Test fun T5_03_orphanPartialOrReplacement_held() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f, NN.both())
        f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
        heldFirst("T5_03 orphan", f, c, d, inconsistent)
        for (gone in listOf("us", "ks")) {
            val g = fixture(); val (e, dg) = ready(g, NN.both())
            g.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != gone }).toString() }
            heldFirst("T5_03 partial $gone", g, e, dg, inconsistent)
        }
        val h = fixture(); val (m, dm) = readyAbsent(h); val fixed = pendingByWriteFault(h, m, dm)
        check(fixed.expectedOwn == null)
        h.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + Json.parseToJsonElement(NN.companionUser)).toString() }
        heldRetry("T5_03 replacement", h, m, dm, fixed, inconsistent)
    }

    @Test fun T5_04_exactDescriptorFixed_thenTransitionWitnessOrSealChanged_heldOnRetry() = runReleaseTest {
        // A single-seal N (no companion) keeps the row parsable as RETIRED_NAMESPACE: a changed transition is then a Conflict.
        val f = fixture(); val (c, d) = ready(f, NN.spec()); val fixed = pendingByWriteFault(f, c, d)
        check(ControlAppliedEvidence.own(ControlRecordReader().read(f.disk().toMutablePreferences().apply {
            this[evidenceKey] = checkNotNull(this[evidenceKey]).replace("\"transition\":\"CURRENT_NULL\"", "\"transition\":\"RETIRED_NAMESPACE\"") }
            .toPreferences()) as ControlRecordRead.Supported, c) != null) { "fixture: the changed row still parses" }
        f.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"transition\":\"CURRENT_NULL\"", "\"transition\":\"RETIRED_NAMESPACE\"")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldRetry("T5_04 transition", f, c, d, fixed, conflict)
        val cases = listOf<Pair<String, (MutablePreferences) -> Unit>>(
            "usWitness" to { p -> editSeal(p, "us") { it.replace("\"originLifetimeId\":\"n-origin\"", "\"originLifetimeId\":\"n-other\"") } },
            "ksWitness" to { p -> editSeal(p, "ks") { it.replace("\"originLifetimeId\":\"n-origin\"", "\"originLifetimeId\":\"n-other\"") } },
            "ksText" to { p -> editSeal(p, "ks") { it.replace("\"epoch\":\"k2\"", "\"epoch\":\"k9\"") } },
            "ksMissing" to { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).filter { id(it) != "ks" }).toString() })
        for ((name, change) in cases) {
            val g = fixture(); val (e, dg) = ready(g, NN.both()); val fixedG = pendingByWriteFault(g, e, dg)
            g.edit { change(it) }
            heldRetry("T5_04 $name", g, e, dg, fixedG, inconsistent)
        }
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
        h.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace(",\"transition\":\"CURRENT_NULL\"", "")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        heldFirst("T5_05 metadata", h, m, dm, recovery(RecoveryReason.UninterpretableMetadata))
        val k = fixture(); val (n, dn) = ready(k)
        k.edit { p -> editSeal(p, "us") { it.replace("\"settlement\":{", "\"settlement\":{\"version\":3,") } }
        heldFirst("T5_05 seal", k, n, dn, recovery(RecoveryReason.UninterpretableObligations))
    }

    /** T5-06 (pattern a): whole absence of the fixed seals, but a seal of this operation under another id remains: held. */
    @Test fun T5_06_absentButAnotherSealOfThisOperationRemains_inconsistent() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val settled = arr(f.disk(), sealKey).single { id(it) == "us" }
        absent(f, c)
        val extra = Json.parseToJsonElement(settled.toString().replace("\"id\":\"us\"", "\"id\":\"us2\""))
        check(op(extra) == c.id && id(extra) == "us2")
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        heldFirst("T5_06", f, c, d, inconsistent)
    }

    /**
     * T5-07 (pattern b): N's witness confirm path leaves the tracked expected row null; with a read-back obligation that Confirm
     * writes and fails after its scope (OnceConfirm·U). The declaration is made with the seals present; the absence fixed at the
     * first owner read then completes on retry.
     */
    @Test fun T5_07_absenceWithANullTrackedExpectedRow_retryCompletes() = runReleaseTest {
        val f = fixture(); val s = sUs
        val witnessed = NN.landed(NN.command(s, f.tracker.lifetimeId), s).toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
        f.edit { it.clear(); it += witnessed }
        val c = f.tracker.registerPrepared(NN.command(s, f.tracker.lifetimeId))
        f.armReadBack()
        f.storage.storage.afterScope = true
        val r = try { controlTestTimeout("witness execute") { f.store.execute(c, NN.context) } } finally { f.storage.storage.afterScope = false }
        check(r is ControlStoreResult.Unconfirmed && f.tracker.isUnresolved(c) && f.history(c).confirmationRequested.get()) { "fixture: U, got $r" }
        check(f.history(c).expectedApplied == null) { "fixture: tracked expected row null" }
        consumeRequest(f); val d = declare(f, c); absent(f, c)
        val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        assertNull("absence fixed", fixed.expectedOwn)
        completed("T5_07", f, c, retry(f, c, d), withoutBarrier(before))
    }

    /** T5-08 (pattern c): absence fixed, then the own row and the whole bundle come back exactly: not promoted, held. */
    @Test fun T5_08_absenceFixed_thenTheBundleReappears_notPromoted_held() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f)
        val present = f.disk()
        absent(f, c)
        val fixed = pendingByWriteFault(f, c, d)
        check(fixed.expectedOwn == null)
        f.edit { it.clear(); it += withoutBarrier(present) }
        check(ownIn(f.disk(), c) == 1 && arr(f.disk(), sealKey).count { op(it) == c.id } == 2) { "fixture: bundle back" }
        heldRetry("T5_08", f, c, d, fixed, inconsistent)
    }

    // ═══ T6 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun T6_01_exactAndAbsentDescriptorsFixed_retriesComplete() = runReleaseTest {
        for (s in listOf(sUs, NN.both())) {
            val f = fixture(); val (c, d) = ready(f, s); val before = f.disk()
            val fixed = pendingByWriteFault(f, c, d)
            assertNotNull("exact own fixed", fixed.expectedOwn)
            assertSame("the tracked expected row", f.history(c).expectedApplied, fixed.expectedOwn)
            assertEquals(HandoverSettlementTransition.CURRENT_NULL, fixed.transition)
            assertEquals(c.id, fixed.operationId)
            assertEquals(orderedIds(s), fixed.orderedSealIds)
            assertEquals("n-demand", fixed.demandId)
            assertEquals(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value), fixed.deletionIdentity)
            assertEquals(TerminationEntry.UncertainSettlement, fixed.entry)
            assertEquals("nothing landed", before, f.disk())
            completed("T6_01 exact ${orderedIds(s)}", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
        }
        val g = fixture(); val (e, dg) = readyAbsent(g); val beforeG = g.disk()
        val fixedG = pendingByWriteFault(g, e, dg)
        assertNull("absence fixed", fixedG.expectedOwn)
        assertEquals(orderedIds(sUs), fixedG.orderedSealIds)
        assertEquals(HandoverSettlementTransition.CURRENT_NULL, fixedG.transition)
        assertEquals(e.id, fixedG.operationId)
        assertEquals("n-demand", fixedG.demandId)
        assertEquals(DependencyAtom.AppliedRow(e.id, e.ownerTrackingLifetimeId.value), fixedG.deletionIdentity)
        assertEquals("nothing landed", beforeG, g.disk())
        completed("T6_01 absent", g, e, retry(g, e, dg), withoutBarrier(beforeG))
    }

    @Test fun T6_02_bothProjection_mismatchGaps_newDependentHeldThenCompletes() = runReleaseTest {
        val f = fixture(); val s = NN.both(); val (c, d) = ready(f, s); val before = f.disk()
        val fixed = pendingByWriteFault(f, c, d)
        fun project(desc: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(c.id, c.ownerTrackingLifetimeId.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, c.body), emptyList(), null, desc))
        val atoms = setOf<DependencyAtom>(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)) +
            orderedIds(s).flatMap { listOf(DependencyAtom.ControlRow(ControlKind.SEAL, it), DependencyAtom.SealWitness(it, c.id)) }
        val p = project(fixed)
        assertTrue("projection of the fixed descriptor, got $p", p is DependencyProjection.Known && p.dependencies.containsAll(atoms))
        for ((name, desc, own) in listOf(
                Triple("transition", TerminationPendingDescriptor.SettlementHandoff(fixed.closureBinding, fixed.expectedOwn,
                    HandoverSettlementTransition.RETIRED_NAMESPACE, fixed.operationId, fixed.orderedSealIds, fixed.demandId, fixed.deletionIdentity),
                    setOf<DependencyAtom>(DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value))),
                Triple("companionId", TerminationPendingDescriptor.SettlementHandoff(fixed.closureBinding, fixed.expectedOwn,
                    fixed.transition, fixed.operationId, fixed.orderedSealIds.map { if (it == "ks") "zz" else it }, fixed.demandId, fixed.deletionIdentity),
                    setOf(DependencyAtom.ControlRow(ControlKind.SEAL, "zz"), DependencyAtom.SealWitness("zz", c.id))))) {
            val pm = project(desc)
            assertTrue("C2c.T6_02 $name: TerminationDescriptorMismatch gap, got $pm", pm is DependencyProjection.Unknown &&
                DependencyGapCause.TerminationDescriptorMismatch in pm.gaps.map { it.cause })
            assertTrue("C2c.T6_02 $name: descriptor atoms kept, got $pm", (pm as DependencyProjection.Unknown).knownDependencies.containsAll(own))
        }
        val settled = node(arr(f.storage.raw(), sealKey).single { id(it) == "us" }.toString())
        val u = CommandRef("u-edit", listOf(ControlMutation.Edit.prepare(ControlKind.SEAL, settled) {}), OwnerTrackingLifetimeId.issue())
        f.addUnresolved(u)
        heldRetry("T6_02", f, c, d, fixed, rejected(CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, "us"))))
        val w = f.tracker.recoverySnapshot()
        ControlReleaseFixtures.replaceRecovery(f.tracker, LocalRecoveryWork(w.unresolvedCommands - u, w.pendingReleases))
        completed("T6_02", f, c, retry(f, c, d), expectedAfterDeletion(before, c))
    }

    /** T6-03 (pattern d): the exact deletion landed but the return failed — the retry confirms absence; a contaminated return fails. */
    @Test fun T6_03_landedThenReturnFailed_retryConfirmsAbsence_andContaminatedReturnFails() = runReleaseTest {
        val f = fixture(); val (c, d) = ready(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = try { handoff(f, c, d) } finally { f.storage.storage.afterScope = false }
        assertTrue("Unconfirmed(TERMINATION_PENDING, ConfirmingStorage), got $r", r is ControlCompletionResult.Unconfirmed &&
            r.state == ControlCommandLifecycle.TERMINATION_PENDING && r.phase == ControlAttemptPhase.ConfirmingStorage)
        assertNotNull("exact descriptor", (f.history(c).terminationDescriptor as TerminationPendingDescriptor.SettlementHandoff).expectedOwn)
        val landed = f.disk()
        assertEquals("deletion landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        completed("T6_03", f, c, retry(f, c, d), withoutBarrier(landed))
        val g = fixture(); val (e, dg) = ready(g); val ownRows = checkNotNull(g.disk()[evidenceKey]); val beforeG = g.disk()
        g.boundary.afterReturn = { p -> p.toMutablePreferences().apply { this[evidenceKey] = ownRows }.toPreferences() }
        val failure = runCatching { handoff(g, e, dg) }.exceptionOrNull()
        assertEquals("C2c.T6_03 contaminated: invariant propagates", IllegalStateException::class.java, failure?.javaClass)
        assertEquals("C2c.T6_03 contaminated: the stored candidate landed", expectedAfterDeletion(beforeG, e), withoutBarrier(g.disk()))
        assertEquals(ControlCommandLifecycle.TERMINATION_PENDING, e.lifecycleState)
        assertTrue("descriptor kept", g.history(e).terminationDescriptor is TerminationPendingDescriptor.SettlementHandoff)
        val w = g.tracker.recoverySnapshot(); assertTrue("U∩P", e in w.unresolvedCommands && e in w.pendingReleases)
    }

    // ═══ T7 ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    /** N's REQUEST (owner A, binding 9, origin n-origin, order 23) consumed by a real SETTLE_QUERY with a later query order 24. */
    @Test fun T7_01_realSettleQueryConsumesTheRequest_thenTheOldSettlementHandsOver() = runReleaseTest {
        val f = fixture(); val c = nullU(f)
        val q = DemandAuthFixtures
        val origin = NN.life
        val binding = LifecycleBinding(SettlementExecutor("A", 9, origin), q.identity, 1, "binding-start")
        val fence = FenceV1("A", checkNotNull(f.disk()[DataStoreAccessEpochStore.USER_EPOCH]), checkNotNull(f.disk()[DataStoreAccessEpochStore.KRX_EPOCH]))
        val started = StartedQueryV1(fence, 5, q.identity, EventOrderV1(origin, 24), 9, RefreshIntent.FORCE_PREMIUM, 0)
        val request = node(arr(f.disk(), demandKey).single { id(it) == "n-demand" }.toString())
        val s = f.store.prepareSettleQuery(listOf(request), null, null, binding,
            q.decision(q = started, before = fence, after = fence, origin = origin), LifecycleOrderSource(origin, 24))
        val rs = controlTestTimeout("settle") { f.store.execute(s, q.context(q.runtime(binding = binding,
            registrations = listOf(LifecycleQueryRegistration("query-21", started))))) }
        assertTrue("fixture: SETTLE_QUERY Confirmed, got $rs", rs is ControlStoreResult.Confirmed)
        assertTrue("fixture: REQUEST consumed", arr(f.disk(), demandKey).none { id(it) == "n-demand" })
        val consumed = controlTestTimeout("settle consumed") {
            f.store.completeLifecycleAfterConsumption(s, closure(s), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)) }
        assertTrue("fixture: successor result consumed, got $consumed", consumed is ControlCompletionResult.Completed)
        val d = declare(f, c)
        val before = f.disk()
        assertEquals("the old settlement's seals are still stored", 2, arr(before, sealKey).count { op(it) == c.id })
        completed("T7_01", f, c, handoff(f, c, d), expectedAfterDeletion(before, c))
    }
}
