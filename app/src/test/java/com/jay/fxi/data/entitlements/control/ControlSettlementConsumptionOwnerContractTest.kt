package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-3B2 contract: the owner path of current-lifetime R/N/L consumption termination (6-3 skeleton r3 §2–§3,
 * T1, T2 owner rows, T4.4–6, T5, T6; 6-3B API consensus r2 incl. a–c'). API fixed by this contract:
 *   suspend fun ControlRecordStore.completeSettlementAfterConsumption(command, closure, consumption: RotationConsumption)
 *   TerminationEntry.ConsumedSettlement, TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals(mode, entry,
 *   closureBinding, expectedSettlement: AppliedEvidence.Settlement, transition, operationId, orderedSealIds).
 * First entry: lifetime → terminal/other path → lease → exact registration → one body view (a non-Handover body is
 * UnsupportedInThisUnit) → confirmed, not unresolved, both declarations, closure — all before storage (closure only at the
 * entry: 6-2C2 agreement / 6-2D.1). The owner then classifies schema/opaque first (RecoveryRequired), records an
 * interpretable own row as observedApplied, runs the pure ControlSettlementConsumption (6-3B1), then G11 over every other
 * ref (commands ∪ U ∪ P ∪ executing), and only then fixes the descriptor, publishes P, begins and Confirms. The return is
 * validated before TERMINATED/body release/exact U-P-commands cleanup. retryTermination accepts a Handover body only with an
 * EvidenceAbsent (NeverConfirm) or ExactSettlementEvidenceAndSeals descriptor and re-runs the owner path; the pending retry
 * allows only the whole bundle exactly present (same deletion) or wholly absent (absence Confirm). Records come from the
 * real transitions (execute). The implementation thread reads but does not edit this file.
 */
class ControlSettlementConsumptionOwnerContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    private val openedBoundary = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() }; openedBoundary.forEach { it.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val R = RetiredNamespaceFixtures; private val NN = CurrentNullFixtures; private val L = RetiredNullFixtures

    private class Kind(val name: String, val raw: Preferences, val operationId: String, val body: ControlCommandBody.Handover,
        val context: AttemptContext)
    private fun r(s: RetiredNamespaceSettlement = R.spec()) = Kind("R", R.raw(s), s.operationId, ControlCommandBody.SettleRetiredNamespace(s), R.context)
    private fun n(s: CurrentNullSettlement = NN.spec(), name: String = "N") = Kind(name, NN.raw(s), s.operationId, ControlCommandBody.RotateAndSettleCurrentNull(s), NN.context)
    private fun l(s: RetiredNullSettlement = L.spec(), name: String = "L") = Kind(name, L.raw(s), s.operationId, ControlCommandBody.SettleRetiredNull(s), L.context)
    private fun kinds() = listOf(r(), n(), n(NN.both(), "N-both"), l(), l(L.both(), "L-both"))

    private fun register(f: TerminationFixture, k: Kind) = f.tracker.registerPrepared(CommandRef(k.operationId, k.body, f.tracker.lifetimeId))
    /** A real confirmed, not-unresolved settlement with a non-null Settlement expectedApplied. */
    private suspend fun confirmed(f: TerminationFixture, k: Kind): CommandRef {
        f.edit { it.clear(); it += k.raw }
        val c = register(f, k)
        val r = controlTestTimeout("settlement execute") { f.store.execute(c, k.context) }
        check(r is ControlStoreResult.Confirmed) { "fixture ${k.name}: $r" }
        val t = f.history(c)
        check(t.confirmed.get() && t.expectedApplied is AppliedEvidence.Settlement && !f.tracker.isUnresolved(c)) { "fixture ${k.name} state" }
        return c
    }
    private suspend fun consume(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c), d: RotationConsumption = declared) =
        controlTestTimeout("settlement consume") { f.store.completeSettlementAfterConsumption(c, k, d) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) =
        controlTestTimeout("settlement retry") { f.store.retryTermination(c, k) }

    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    /** Independent oracle: before minus exactly the own row and this operation's seals; every other key equal. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    private suspend fun assertConsumed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("D2B6/6-3B2.$id: completed $r", r is ControlCompletionResult.Completed)
        r as ControlCompletionResult.Completed
        assertEquals("D2B6/6-3B2.$id: consumed", CompletionMode.Consumed, r.mode)
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("D2B6/6-3B2.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-3B2.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("D2B6/6-3B2.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-3B2.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/6-3B2.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        val work = f.tracker.recoverySnapshot()
        assertFalse("D2B6/6-3B2.$id: notInU", c in work.unresolvedCommands)
        assertFalse("D2B6/6-3B2.$id: notInP", c in work.pendingReleases)
        assertTrue("D2B6/6-3B2.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun refusedBeforeStorage(id: String, f: TerminationFixture, c: CommandRef, reason: CompletionRejectionReason,
        k: TerminationClosure = closure(c), d: RotationConsumption = declared) {
        val before = f.storage.raw(); val access = f.boundary.accesses; val work = f.tracker.recoverySnapshot()
        val r = consume(f, c, k, d)
        assertEquals("D2B6/6-3B2.$id: $reason", reason, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-3B2.$id: noStorageAccess", 0, f.boundary.accesses - access)
        assertEquals("D2B6/6-3B2.$id: recordUntouched", before, f.storage.raw())
        assertEquals("D2B6/6-3B2.$id: setsKept", work, f.tracker.recoverySnapshot())
        if (c.lifecycleState == ControlCommandLifecycle.RETAINED) assertNull("D2B6/6-3B2.$id: noDescriptor", f.tracker.findPrepared(c)?.terminationDescriptor)
    }
    /** Owner refusal on first entry: RETAINED, no descriptor/P, record unchanged even with the read-back armed (Confirm 0). */
    private suspend fun ownerRefused(id: String, f: TerminationFixture, c: CommandRef, expected: Any) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-3B2.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            is CompletionRejectionReason -> assertEquals("D2B6/6-3B2.$id: rejected $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
            ConflictReason::class -> assertTrue("D2B6/6-3B2.$id: conflict $r", r is ControlCompletionResult.Conflict)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-3B2.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-3B2.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-3B2.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-3B2.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-3B2.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef): TerminationPendingDescriptor {
        f.storage.storage.before = true
        val r = consume(f, c)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
        f.storage.storage.before = false
        return checkNotNull(f.history(c).terminationDescriptor)
    }
    private suspend fun retryHeld(id: String, f: TerminationFixture, c: CommandRef, d: TerminationPendingDescriptor, expected: Any,
        k: TerminationClosure = closure(c)) {
        f.armReadBack()
        val before = f.storage.raw()
        val r = retry(f, c, k)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-3B2.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            is CompletionRejectionReason -> assertEquals("D2B6/6-3B2.$id: rejected $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-3B2.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("D2B6/6-3B2.$id: descriptorKept", d, f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-3B2.$id: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-3B2.$id: noConfirmRequested", before, f.storage.raw())
        assertTrue("D2B6/6-3B2.$id: leaseReleased", f.tracker.executing.isEmpty())
    }

    // ── T1: eligibility, all before storage ──────────────────────────────────────────────────────────────────────
    @Test fun B2_01_eachKindIsConsumedWithExactDeletionAndAFixedDescriptorShape() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k); val before = f.disk()
            assertConsumed("01 ${k.name}", f, c, consume(f, c), expectedAfterDeletion(before, c))
        }
        // The pending descriptor fixed by a failed management write carries exactly the fixed authority.
        val f = fixture(); val k = n(NN.both(), "N-both"); val c = confirmed(f, k); val expected = f.history(c).expectedApplied
        val d = pendingByWriteFault(f, c)
        assertTrue("D2B6/6-3B2.01: descriptor $d", d is TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals)
        d as TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals
        assertEquals(CompletionMode.Consumed, d.mode)
        assertEquals(TerminationEntry.ConsumedSettlement, d.entry)
        assertSame("D2B6/6-3B2.01: expectedIsTheHistory", expected, d.expectedSettlement)
        assertEquals(HandoverSettlementTransition.CURRENT_NULL, d.transition)
        assertEquals(c.id, d.operationId)
        assertEquals((expected as AppliedEvidence.Settlement).sealIds, d.orderedSealIds)
    }
    @Test fun B2_02_notConfirmedUnresolvedAndDeclarations() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); f.edit { it.clear(); it += k.raw }; val c = register(f, k)
            refusedBeforeStorage("02 ${k.name} notConfirmed", f, c, CompletionRejectionReason.NotConfirmed)
            val g = fixture(); val d = confirmed(g, k); g.addUnresolved(d)
            refusedBeforeStorage("02 ${k.name} unresolved", g, d, CompletionRejectionReason.Unresolved)
            for ((name, decl) in listOf("result" to RotationConsumption(false, true), "followUp" to RotationConsumption(true, false))) {
                val h = fixture(); val e = confirmed(h, k)
                refusedBeforeStorage("02 ${k.name} $name", h, e, CompletionRejectionReason.ConsumptionNotDeclared, d = decl)
            }
        }
    }
    @Test fun B2_03_eachClosureFieldAloneIsRefusedAtTheEntry() = runBlocking {
        val cases: List<Pair<ClosureViolation, (TerminationFixture, CommandRef) -> TerminationClosure>> = listOf(
            ClosureViolation.CommandMismatch to { f, c -> closure(c, command = CommandRef(c.id, checkNotNull(c.captureStateAndBody().body), f.tracker.lifetimeId)) },
            ClosureViolation.LifetimeMismatch to { _, c -> closure(c, lifetime = fixture().tracker.lifetimeId) },
            ClosureViolation.RelatedScopeMismatch to { _, c -> closure(c, scope = c.id + "-other") },
            ClosureViolation.GenerationMismatch to { _, c -> closure(c, capture = 6L) },
            ClosureViolation.EntriesOpen to { _, c -> closure(c, entriesClosed = false) },
            ClosureViolation.CapturedJoinedMismatch to { _, c -> closure(c, joined = setOf("job-2")) },
            ClosureViolation.RegisteredMismatch to { _, c -> closure(c, registered = setOf("job-2")) },
            ClosureViolation.ReceiptsOpen to { _, c -> closure(c, receiptsClosed = false) },
            ClosureViolation.OwnerMissing to { _, c -> closure(c, owner = " ") })
        for ((violation, make) in cases) {
            val f = fixture(); val c = confirmed(f, r())
            refusedBeforeStorage("03 $violation", f, c, CompletionRejectionReason.ClosureNotSatisfied(violation), k = make(f, c))
        }
        // The same entry check is wired for every kind.
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k)
            refusedBeforeStorage("03 ${k.name} wired", f, c, CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.EntriesOpen),
                k = closure(c, entriesClosed = false))
        }
    }
    @Test fun B2_04_bodyKindTerminalInFlightAndOtherPath() = runBlocking {
        // A non-Handover body is not this entry's.
        val f = fixture(); f.edit { it.clear(); it += NamespaceSettlementFixtures.raw() }
        val rot = f.tracker.registerPrepared(CommandRef(NamespaceSettlementFixtures.operation,
            ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input()), f.tracker.lifetimeId))
        check(controlTestTimeout("rotation") { f.store.execute(rot, NamespaceSettlementFixtures.context) } is ControlStoreResult.Confirmed)
        refusedBeforeStorage("04 rotationBody", f, rot, CompletionRejectionReason.UnsupportedInThisUnit)
        val m = f.mutations(); f.history(m).confirmed.set(true)
        refusedBeforeStorage("04 mutationsBody", f, m, CompletionRejectionReason.UnsupportedInThisUnit)
        // Lease contention.
        val g = fixture(); val c = confirmed(g, r()); check(g.tracker.executing.add(c))
        try { refusedBeforeStorage("04 inFlight", g, c, CompletionRejectionReason.InFlight) } finally { g.tracker.executing.remove(c) }
        // Terminal, and another management path already pending.
        val h = fixture(); val e = confirmed(h, n()); val before = h.disk()
        assertConsumed("04 first", h, e, consume(h, e), expectedAfterDeletion(before, e))
        val access = h.boundary.accesses
        assertTrue("D2B6/6-3B2.04 alreadyTerminated", consume(h, e) is ControlCompletionResult.AlreadyTerminated)
        assertEquals("D2B6/6-3B2.04 terminalNoStorage", 0, h.boundary.accesses - access)
        val i = fixture(); val p = confirmed(i, l()); pendingByWriteFault(i, p)
        refusedBeforeStorage("04 otherPath", i, p, CompletionRejectionReason.OtherManagementPath)
    }

    // ── T2 owner rows ────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun B2_05_realWitnessReconfirmationLeavesExpectedNullAndIsHeld() = runBlocking {
        for (k in kinds()) {
            // The real transition applied under a foreign ref, own row stripped: execute reconfirms read.original.
            val f = fixture(); f.edit { it.clear(); it += k.raw }
            val foreign = CommandRef(k.operationId, k.body, OwnerTrackingLifetimeId.issue())
            val holder = fixture(); holder.edit { it.clear(); it += k.raw }
            val probe = holder.tracker.registerPrepared(CommandRef(k.operationId, k.body, holder.tracker.lifetimeId))
            check(controlTestTimeout("witness source") { holder.store.execute(probe, k.context) } is ControlStoreResult.Confirmed)
            val witnessed = holder.disk().toMutablePreferences().apply { this[evidenceKey] = "[]"; remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
            check(foreign.id == probe.id)
            f.edit { it.clear(); it += witnessed }
            val c = register(f, k)
            check(controlTestTimeout("witness execute") { f.store.execute(c, k.context) } is ControlStoreResult.Confirmed) { "fixture ${k.name}" }
            check(f.history(c).confirmed.get() && f.history(c).expectedApplied == null) { "fixture ${k.name}: expected must be null" }
            ownerRefused("05 ${k.name}", f, c, RecoveryReason.ExpectedSettlementEvidenceUnavailable)
        }
    }
    @Test fun B2_06_ownRowLostAfterConfirmation() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k)
            f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
            ownerRefused("06 ${k.name}", f, c, RecoveryReason.CommandEvidenceLost)
        }
    }
    @Test fun B2_07_ownRowIsObservedBeforeAnUnrelatedOpaqueEntryRefuses() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k)
            f.history(c).observedApplied.set(false) // isolate this transaction's observation
            f.edit { p -> p[demandKey] = "[17]" }
            ownerRefused("07 ${k.name}", f, c, RecoveryReason.UninterpretableObligations)
            assertTrue("D2B6/6-3B2.07 ${k.name}: observedFirst", f.history(c).observedApplied.get())
        }
    }
    @Test fun B2_08_schemaFailuresAreTheOwnersEarlierClassification() = runBlocking {
        // Parser-precluded single-field variants (6-3B1 header): seal ownerUid → obligations; row transition the schema
        // rejects for this cardinality/demand → metadata.
        val f = fixture(); val c = confirmed(f, r())
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey).map { if (op(it) == c.id) obj(it) { this["ownerUid"] = JsonPrimitive("Z") } else it }).toString() }
        ownerRefused("08 sealOwner", f, c, RecoveryReason.UninterpretableObligations)
        val g = fixture(); val d = confirmed(g, r())
        g.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).map { if (rowCommand(it) == d.id) obj(it) { this["transition"] = JsonPrimitive("RETIRED_NULL") } else it }).toString() }
        ownerRefused("08 rowTransition", g, d, RecoveryReason.UninterpretableMetadata)
    }
    @Test fun B2_09_aDeciderRefusalKeepsEverything() = runBlocking {
        // One representative decider refusal per result class reaches the caller unchanged, with Confirm 0.
        val f = fixture(); val c = confirmed(f, n(NN.both(), "N-both"))
        f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).map { e ->
            if (rowCommand(e) == c.id) obj(e) { this["sealIds"] = JsonArray(e.jsonObject.getValue("sealIds").jsonArray.reversed()) } else e }).toString() }
        ownerRefused("09 order", f, c, RecoveryReason.InconsistentReclamation)
        val g = fixture(); val d = confirmed(g, r())
        g.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).map { e ->
            if (rowCommand(e) == d.id) obj(e) { this["ownerTrackingLifetimeId"] = JsonPrimitive("00000000-0000-0000-0000-0000000000ab") } else e }).toString() }
        ownerRefused("09 lifetime", g, d, ConflictReason::class)
    }

    // ── T4 owner rows ────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun B2_10_otherOperationsBundleMarkersAndForeignKeysSurvive() = runBlocking {
        val f = fixture(); val c = confirmed(f, r())
        val other = R.spec(target = node(NamespaceSettlementFixtures.krx), op = "r-other", did = "r-other-demand")
        // r2: really apply the other operation, so its Applied row and settled seal are a genuine surviving bundle.
        val holder = fixture(); holder.edit { it.clear(); it += R.raw(other) }
        val oc = holder.tracker.registerPrepared(CommandRef(other.operationId, ControlCommandBody.SettleRetiredNamespace(other), holder.tracker.lifetimeId))
        check(controlTestTimeout("other execute") { holder.store.execute(oc, R.context) } is ControlStoreResult.Confirmed) { "fixture: other applied" }
        val foreign = holder.disk()
        check(arr(foreign, sealKey).single().let { op(it) == "r-other" } && arr(foreign, evidenceKey).single().let { rowCommand(it) == "r-other" }) { "fixture: settled bundle" }
        f.edit { p ->
            p[sealKey] = JsonArray(arr(foreign, sealKey) + arr(p.toPreferences(), sealKey)).toString()
            p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey) + arr(foreign, evidenceKey)).toString()
            p[DataStoreAccessEpochStore.TEARDOWN_OWED_FOR] = "old-owner"
            p[ControlStoreTestStorage.EXTRA] = "future-value"
        }
        check(oc.id == "r-other")
        val before = f.disk()
        assertConsumed("10", f, c, consume(f, c), expectedAfterDeletion(before, c))
        assertEquals("D2B6/6-3B2.10: foreignSealKeptFirst", id(arr(foreign, sealKey).first()), id(arr(f.disk(), sealKey).first()))
        assertEquals("D2B6/6-3B2.10: foreignRowKept", listOf("r-other"), arr(f.disk(), evidenceKey).map { rowCommand(it) })
    }
    private suspend fun badReturn(id: String, change: (Preferences, CommandRef) -> Preferences) {
        lateinit var boundary: ReleaseBoundaryData
        val s = ControlStoreTestStorage(File(folder.root, "settlement-return-$id.preferences_pb")) { ReleaseBoundaryData(it).also { b -> boundary = b } }
            .also { openedBoundary += it }
        val k = r()
        s.data.updateData { k.raw }
        val tracker = ControlCommandTracking.forOwner(s.owner)
        val c = tracker.registerPrepared(CommandRef(k.operationId, k.body, tracker.lifetimeId))
        check(controlTestTimeout("settlement execute") { s.control.execute(c, k.context) } is ControlStoreResult.Confirmed)
        boundary.after = { change(it, c) }
        val failure = runCatching { controlTestTimeout("consume") { s.control.completeSettlementAfterConsumption(c, closure(c), declared) } }.exceptionOrNull()
        assertEquals("D2B6/6-3B2.$id: invariantPropagates", IllegalStateException::class.java, failure?.javaClass)
        assertEquals("D2B6/6-3B2.$id: pendingKept", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertNotNull("D2B6/6-3B2.$id: descriptorKept", tracker.findPrepared(c)?.terminationDescriptor)
        assertTrue("D2B6/6-3B2.$id: pKept", c in tracker.recoverySnapshot().pendingReleases)
        assertTrue("D2B6/6-3B2.$id: leaseReleased", tracker.executing.isEmpty())
    }
    @Test fun B2_11_aReturnThatDoesNotMatchTheCandidateIsAnInvariantFailure() = runBlocking {
        badReturn("11a") { returned, _ -> returned.toMutablePreferences().apply { this[demandKey] = "[]" }.toPreferences() }
        badReturn("11b") { returned, c -> returned.toMutablePreferences().apply {
            this[evidenceKey] = """[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"SETTLEMENT","transition":"RETIRED_NAMESPACE","sealIds":["s"],"demandId":"r-demand"}]"""
        }.toPreferences() }
    }
    @Test fun B2_12_theRealFileStoragePathCarriesNoUnpairedSurrogate() = runBlocking {
        // TooLarge reachability boundary on the owner path (the pure branch is 6-3B1 B1_09).
        val f = fixture(); val c = confirmed(f, r())
        val survivor = """{"id":"zz","kind":"NAMESPACE","ownerUid":"${"\uD800".repeat(12_000)}","axis":"CAPABILITY","epoch":"k9"}"""
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + Json.parseToJsonElement(survivor)).toString() }
        assertTrue("D2B6/6-3B2.12: completed", consume(f, c) is ControlCompletionResult.Completed)
        assertTrue("D2B6/6-3B2.12: noUnpairedSurrogateOnDisk", checkNotNull(f.disk()[sealKey]).none(Char::isSurrogate))
    }

    // ── T5 pending boundary and retry ────────────────────────────────────────────────────────────────────────────
    @Test fun B2_13_readFaultAndCancellationBeforeTheCandidateKeepRetained() = runBlocking {
        val f = fixture(); val c = confirmed(f, r()); val before = f.disk(); val work = f.tracker.recoverySnapshot()
        f.boundary.failNextBeforeSnapshot = true
        assertTrue("D2B6/6-3B2.13: unconfirmed", consume(f, c) is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-3B2.13: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-3B2.13: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-3B2.13: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-3B2.13: diskUnchanged", before, f.disk())
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.boundary.gate = reached to release
        val caller = async { f.store.completeSettlementAfterConsumption(c, closure(c), declared) }
        try { withTimeout(10_000) { reached.await() }; caller.cancelAndJoin(); assertTrue(caller.isCancelled) }
        finally { release.complete(Unit); caller.cancelAndJoin() }
        assertEquals("D2B6/6-3B2.13: retainedAfterCancel", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertEquals("D2B6/6-3B2.13: diskUnchangedAfterCancel", before, f.disk())
        assertConsumed("13r", f, c, consume(f, c), expectedAfterDeletion(before, c))
    }
    @Test fun B2_14_writeFaultFixesTheDescriptorThenRetryDeletes() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k); val before = f.disk()
            pendingByWriteFault(f, c)
            assertTrue("D2B6/6-3B2.14 ${k.name}: pInSet", c in f.tracker.recoverySnapshot().pendingReleases)
            assertEquals("D2B6/6-3B2.14 ${k.name}: nothingLanded", withoutBarrier(before), withoutBarrier(f.disk()))
            assertConsumed("14 ${k.name}", f, c, retry(f, c), expectedAfterDeletion(before, c))
        }
    }
    @Test fun B2_15_landedThenReturnFailedRetryConfirmsAbsence() = runBlocking {
        val f = fixture(); val c = confirmed(f, l(L.both(), "L-both")); val before = f.disk()
        f.storage.storage.afterScope = true
        assertTrue("D2B6/6-3B2.15: unconfirmed", consume(f, c) is ControlCompletionResult.Unconfirmed)
        f.storage.storage.afterScope = false
        val landed = f.disk()
        assertEquals("D2B6/6-3B2.15: landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        assertConsumed("15r", f, c, retry(f, c), withoutBarrier(landed))
    }
    @Test fun B2_16_retryPartialReplacementAndExtraOwnSealAreHeld() = runBlocking {
        run { // own row gone, the bundle still present
            val f = fixture(); val c = confirmed(f, r()); val d = pendingByWriteFault(f, c)
            f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
            retryHeld("16a", f, c, d, RecoveryReason.InconsistentReclamation)
        }
        run { // own row and bundle gone, a prepared original back at the fixed id
            val k = r(); val f = fixture(); val c = confirmed(f, k); val d = pendingByWriteFault(f, c)
            f.edit { p ->
                p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
                p[sealKey] = JsonArray(arr(k.raw, sealKey)).toString() }
            retryHeld("16b", f, c, d, RecoveryReason.InconsistentReclamation)
        }
        run { // own row and bundle gone, an own-operation seal outside the fixed ids remains
            val f = fixture(); val c = confirmed(f, r()); val d = pendingByWriteFault(f, c)
            f.edit { p ->
                val own = arr(p.toPreferences(), sealKey).first { op(it) == c.id }
                p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
                p[sealKey] = JsonArray(listOf(obj(own) { this["id"] = JsonPrimitive("outside-own") })).toString() }
            retryHeld("16c", f, c, d, RecoveryReason.InconsistentReclamation)
        }
    }
    @Test fun B2_17_retryClosureBindingFieldsAlone() = runBlocking {
        val cases: List<Pair<ClosureViolation, (CommandRef) -> TerminationClosure>> = listOf(
            ClosureViolation.GenerationMismatch to { c -> closure(c, capture = 8L, current = 8L) },
            ClosureViolation.OwnerMismatch to { c -> closure(c, owner = "owner-2") },
            ClosureViolation.WorkSetChanged to { c -> closure(c, captured = setOf("job-2"), joined = setOf("job-2"), registered = setOf("job-2")) },
            ClosureViolation.ReceiptsOpen to { c -> closure(c, receiptsClosed = false) })
        for ((violation, make) in cases) {
            val f = fixture(); val c = confirmed(f, n()); val before = f.disk(); val d = pendingByWriteFault(f, c)
            retryHeld("17 $violation", f, c, d, CompletionRejectionReason.ClosureNotSatisfied(violation), k = make(c))
            assertConsumed("17r $violation", f, c, retry(f, c), expectedAfterDeletion(before, c))
        }
    }
    @Test fun B2_18_retryAcceptsOnlyTheHandoverDescriptorKinds() = runBlocking {
        // A Handover ref pending with a Rotation descriptor is not this ref's authority (unreachable except by a corrupted
        // local state): the retry entry refuses it before storage.
        val f = fixture(); val c = confirmed(f, r()); pendingByWriteFault(f, c)
        val donor = fixture(); donor.edit { it.clear(); it += NamespaceSettlementFixtures.raw() }
        val rot = donor.tracker.registerPrepared(CommandRef(NamespaceSettlementFixtures.operation,
            ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input()), donor.tracker.lifetimeId))
        check(controlTestTimeout("rotation") { donor.store.execute(rot, NamespaceSettlementFixtures.context) } is ControlStoreResult.Confirmed)
        donor.storage.storage.before = true
        check(controlTestTimeout("rotation pending") { donor.store.completeAfterConsumption(rot, closure(rot), declared) } is ControlCompletionResult.Unconfirmed)
        val field = TrackedControlCommand::class.java.getDeclaredField("terminationDescriptor").apply { isAccessible = true }
        field.set(f.history(c), checkNotNull(field.get(donor.history(rot))))
        val access = f.boundary.accesses
        val r = retry(f, c)
        assertEquals("D2B6/6-3B2.18", CompletionRejectionReason.UnsupportedInThisUnit, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-3B2.18: noStorageAccess", 0, f.boundary.accesses - access)
    }

    // ── T6 G11 ───────────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun settledSeal(f: TerminationFixture, c: CommandRef) = node(arr(f.storage.raw(), sealKey).first { op(it) == c.id }.toString())
    private fun editOf(before: ControlNode) = ControlMutation.Edit.prepare(ControlKind.SEAL, before) {}
    private fun foreign(id: String, vararg actions: ControlMutation) = CommandRef(id, actions.toList(), OwnerTrackingLifetimeId.issue())
    private suspend fun refusedByDependency(id: String, f: TerminationFixture, c: CommandRef, expected: CompletionRejectionReason) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        assertEquals("D2B6/6-3B2.$id: reason $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-3B2.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-3B2.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-3B2.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-3B2.$id: recordUntouched", before, f.storage.raw())
    }
    @Test fun B2_19_eachIdentitySourceAloneBlocks() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = confirmed(f, k); val s = settledSeal(f, c)
            val sealId = (s.text("id") as FieldRead.Present).value
            val atom = DependencyAtom.ControlRow(ControlKind.SEAL, sealId)
            val m = f.store.prepare(editOf(s))
            refusedByDependency("19 ${k.name} registered", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, atom))
            val g = fixture(); val d = confirmed(g, k); val u = foreign("u-only", editOf(settledSeal(g, d))); g.addUnresolved(u)
            refusedByDependency("19 ${k.name} unresolved", g, d, CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value, atom))
            val h = fixture(); val e = confirmed(h, k); val p = foreign("p-only", editOf(settledSeal(h, e))); h.addPending(p)
            refusedByDependency("19 ${k.name} pending", h, e, CompletionRejectionReason.DependencyPresent(p.id, p.ownerTrackingLifetimeId.value, atom))
            val i = fixture(); val j = confirmed(i, k); val x = foreign("e-only", editOf(settledSeal(i, j))); check(i.tracker.executing.add(x))
            try { refusedByDependency("19 ${k.name} executing", i, j, CompletionRejectionReason.DependencyPresent(x.id, x.ownerTrackingLifetimeId.value, atom)) }
            finally { i.tracker.executing.remove(x) }
        }
    }
    @Test fun B2_20_anotherRefsDescriptorNamingThisAppliedRowBlocksAndUnrelatedRefsDoNot() = runBlocking {
        val f = fixture(); val c = confirmed(f, r()); val m = f.mutations()
        val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        refusedByDependency("20 appliedRow", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)))
        val g = fixture(); val d = confirmed(g, n()); val before = g.disk()
        g.store.prepare(g.store.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        g.addUnresolved(foreign("u-recovery", ControlMutation.Add.prepare(ControlKind.RECOVERY_INTENT, java.util.UUID(0, 93)) { id ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) }))
        assertConsumed("20 unrelated", g, d, consume(g, d), expectedAfterDeletion(before, d))
    }
    @Test fun B2_21_retryAfterADependentAppearedIsHeld() = runBlocking {
        val f = fixture(); val c = confirmed(f, l()); val d = pendingByWriteFault(f, c)
        val s = settledSeal(f, c); val u = foreign("u-late", editOf(s)); f.addUnresolved(u)
        retryHeld("21", f, c, d, CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, (s.text("id") as FieldRead.Present).value)))
    }
    /** X12's S(R/N/L) form: A stores seal x and is left unresolved by a return fault; the settlement then settles x and
     *  its consumption termination meets A's fixed SEAL x and is refused. A's handoff/closure belongs to 6-4b. */
    private suspend fun x12(id: String, raw: Preferences, literalSeal: String, uuid: java.util.UUID,
        epochsAtAdd: Pair<String, String>? = null, settle: (ControlNode) -> Kind) {
        val x = uuid.toString(); val f = fixture()
        f.edit { it.clear(); it += raw.toMutablePreferences().apply { this[sealKey] = "[]" }.toPreferences() }
        // A NAMESPACE append must be current at Add time; R settles a RETIRED namespace, so the epochs move on afterwards
        // (another operation's rotation) before R runs.
        val settledEpochs = raw[DataStoreAccessEpochStore.USER_EPOCH] to raw[DataStoreAccessEpochStore.KRX_EPOCH]
        if (epochsAtAdd != null) f.edit { p -> p[DataStoreAccessEpochStore.USER_EPOCH] = epochsAtAdd.first; p[DataStoreAccessEpochStore.KRX_EPOCH] = epochsAtAdd.second }
        val a = f.store.prepare(ControlMutation.Add.prepare(ControlKind.SEAL, uuid) { sid -> literal(literalSeal); set("id", ControlScalar.Text(sid)) })
        val aBody = a.captureStateAndBody().body
        f.storage.storage.afterScope = true
        check(controlTestTimeout("A execute") { f.store.execute(a) } !is ControlStoreResult.Confirmed) { "fixture $id: A must not confirm" }
        f.storage.storage.afterScope = false
        check(f.tracker.isUnresolved(a)) { "fixture $id: A unresolved" }
        val stored = arr(f.storage.raw(), sealKey).single()
        check(id(stored) == x) { "fixture $id: A stored x" }
        if (epochsAtAdd != null) f.edit { p -> p[DataStoreAccessEpochStore.USER_EPOCH] = checkNotNull(settledEpochs.first)
            p[DataStoreAccessEpochStore.KRX_EPOCH] = checkNotNull(settledEpochs.second) }
        val k = settle(node(stored.toString()))
        val c = register(f, k)
        check(controlTestTimeout("settle x") { f.store.execute(c, k.context) } is ControlStoreResult.Confirmed) { "fixture $id: settles x" }
        refusedByDependency("22 $id", f, c, CompletionRejectionReason.DependencyPresent(a.id, a.ownerTrackingLifetimeId.value,
            DependencyAtom.ControlRow(ControlKind.SEAL, x)))
        assertTrue("D2B6/6-3B2.22 $id: aStillUnresolved", f.tracker.isUnresolved(a))
        assertSame("D2B6/6-3B2.22 $id: aBodyKept", aBody, a.captureStateAndBody().body)
    }
    @Test fun B2_22_x12SettlementOfASealAnUnresolvedAddStoredIsRefused() = runBlocking {
        x12("R", R.raw(), NamespaceSettlementFixtures.user, java.util.UUID(0, 197), epochsAtAdd = "u" to "k") { t -> r(R.spec(target = t)) }
        x12("N", NN.raw(), NN.nullUser, java.util.UUID(0, 198)) { t -> n(NN.spec(target = t)) }
        x12("L", L.raw(L.spec()), L.nullUser, java.util.UUID(0, 199)) { t -> l(L.spec(target = t), "L-x") }
    }

    // ── r2 (Codex REVISE r1): remaining rows ─────────────────────────────────────────────────────────────────────
    @Test fun B2_23_wrongTrackerLifetimeAndAnUnregisteredSameIdRef() = runBlocking {
        val f = fixture(); val other = fixture(); val c = confirmed(other, r())
        refusedBeforeStorage("23 wrongLifetime", f, c, CompletionRejectionReason.WrongTrackerLifetime)
        val g = fixture(); val d = confirmed(g, n())
        val clone = CommandRef(d.id, checkNotNull(d.captureStateAndBody().body), d.ownerTrackingLifetimeId)
        refusedBeforeStorage("23 unregisteredClone", g, clone, CompletionRejectionReason.NotRegisteredIdentity)
        assertSame("D2B6/6-3B2.23: registeredRefUntouched", d, g.history(d).command)
    }
    @Test fun B2_24_expectedNullStaysUnavailableEvenAfterAnOwnRowWasObserved() = runBlocking {
        // expected == null means no deletion authority; an own row observed later and lost again does not turn it into
        // CommandEvidenceLost (symmetric with the landed Rotation path). Both refusals keep everything.
        val k = r(); val f = fixture()
        val holder = fixture(); holder.edit { it.clear(); it += k.raw }
        val probe = holder.tracker.registerPrepared(CommandRef(k.operationId, k.body, holder.tracker.lifetimeId))
        check(controlTestTimeout("witness source") { holder.store.execute(probe, k.context) } is ControlStoreResult.Confirmed)
        val full = holder.disk().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
        f.edit { it.clear(); it += full.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences() }
        val c = register(f, k)
        check(controlTestTimeout("witness execute") { f.store.execute(c, k.context) } is ControlStoreResult.Confirmed)
        check(f.history(c).expectedApplied == null) { "fixture: expected null" }
        // An own-looking row appears (this ref's id and lifetime) and is observed by the owner.
        f.edit { p -> p[evidenceKey] = checkNotNull(full[evidenceKey]).replace(probe.ownerTrackingLifetimeId.value, c.ownerTrackingLifetimeId.value) }
        ownerRefused("24 observed", f, c, RecoveryReason.ExpectedSettlementEvidenceUnavailable)
        assertTrue("D2B6/6-3B2.24: observed", f.history(c).observedApplied.get())
        f.edit { p -> p[evidenceKey] = "[]" }
        ownerRefused("24 lostAfterObservation", f, c, RecoveryReason.ExpectedSettlementEvidenceUnavailable)
    }
    /** A single DataStore owner kept in memory, so a raw unpaired surrogate survives into the owner's snapshot. */
    private class MemoryDataStore : androidx.datastore.core.DataStore<Preferences> {
        private val lock = kotlinx.coroutines.sync.Mutex()
        private val state = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(androidx.datastore.preferences.core.PreferencesSerializer.defaultValue)
        var writes = 0; private set
        override val data: kotlinx.coroutines.flow.Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            lock.lock()
            try { val next = transform(state.value).toMutablePreferences().toPreferences(); writes++; state.value = next; return next }
            finally { lock.unlock() }
        }
    }
    @Test fun B2_25_tooLargeOnTheOwnerPathIsRejectedWithManagementConfirmZero() = runBlocking {
        val memory = MemoryDataStore()
        val owner = DataStoreAccessEpochStore(memory, com.jay.fxi.data.entitlements.EpochIdGenerator { java.util.UUID.randomUUID().toString() })
        val store = ControlRecordStore(owner); val tracker = ControlCommandTracking.forOwner(owner)
        val k = r()
        memory.updateData { k.raw }
        val c = tracker.registerPrepared(CommandRef(k.operationId, k.body, tracker.lifetimeId))
        check(controlTestTimeout("execute") { store.execute(c, k.context) } is ControlStoreResult.Confirmed)
        val survivor = """{"id":"zz","kind":"NAMESPACE","ownerUid":"${"\uD800".repeat(12_000)}","axis":"CAPABILITY","epoch":"k9"}"""
        memory.updateData { p -> p.toMutablePreferences().apply { this[sealKey] = checkNotNull(this[sealKey]).removeSuffix("]") + "," + survivor + "]" }.toPreferences() }
        val before = memory.data.first()
        val r = controlTestTimeout("consume") { store.completeSettlementAfterConsumption(c, closure(c), declared) }
        val reason = ((r as? ControlCompletionResult.Rejected)?.reason as? CompletionRejectionReason.Encoding)?.reason
        assertTrue("D2B6/6-3B2.25: tooLarge $r", reason is RejectionReason.TooLarge && reason.payloadKey == ControlPayloadKey.SEAL)
        assertEquals("D2B6/6-3B2.25: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-3B2.25: noDescriptor", tracker.findPrepared(c)?.terminationDescriptor)
        // No management Confirm: the record is unchanged (a Confirm of the candidate would remove the bundle).
        assertEquals("D2B6/6-3B2.25: recordUntouched", before, memory.data.first())
    }
    @Test fun B2_26_cancellationAfterLandingRetryInFlightAndRetryNotPending() = runBlocking {
        val f = fixture(); val c = confirmed(f, n()); val before = f.disk()
        val pause = ControlStoreTestStorage.Pause(); f.storage.storage.pauseAfterScope = pause
        val caller = async { f.store.completeSettlementAfterConsumption(c, closure(c), declared) }
        try {
            withTimeout(10_000) { pause.reached.await() }
            assertEquals("D2B6/6-3B2.26: landedBeforeCancel", expectedAfterDeletion(before, c), withoutBarrier(f.disk()))
            caller.cancelAndJoin(); assertTrue(caller.isCancelled)
        } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
        f.storage.storage.pauseAfterScope = null
        assertEquals("D2B6/6-3B2.26: pendingAfterCancel", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        check(f.tracker.executing.add(c))
        try {
            val access = f.boundary.accesses
            assertEquals("D2B6/6-3B2.26: retryInFlight", CompletionRejectionReason.InFlight, (retry(f, c) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-3B2.26: inFlightNoStorage", 0, f.boundary.accesses - access)
        } finally { f.tracker.executing.remove(c) }
        assertConsumed("26r", f, c, retry(f, c), expectedAfterDeletion(before, c))
        val g = fixture(); val d = confirmed(g, l()); val access = g.boundary.accesses
        assertEquals("D2B6/6-3B2.26: retryNotPending", CompletionRejectionReason.NotTerminationPending, (retry(g, d) as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-3B2.26: notPendingNoStorage", 0, g.boundary.accesses - access)
    }
    @Test fun B2_27_adoptedOnlyAnOldSameOperationRefAndTypedGaps() = runBlocking {
        run { // A registered SEAL Add whose proposed id is unrelated but whose recorded adoption joined the settled seal.
            val f = fixture(); val c = confirmed(f, r()); val s = settledSeal(f, c); val sid = (s.text("id") as FieldRead.Present).value
            val m = f.store.prepare(ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 91)) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) })
            f.history(m).targets.set(listOf(ControlCommandTarget(sid, s, true)))
            refusedByDependency("27 adoptedOnly", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, DependencyAtom.ControlRow(ControlKind.SEAL, sid)))
        }
        run { // An old-lifetime ref of the same operation in U (its body projects this operation's seal and witness).
            val k = n(); val f = fixture(); val c = confirmed(f, k)
            val old = CommandRef(k.operationId, k.body, OwnerTrackingLifetimeId.issue()); f.addUnresolved(old)
            val r = consume(f, c)
            val reason = (r as? ControlCompletionResult.Rejected)?.reason
            assertTrue("D2B6/6-3B2.27 oldSameOperation: $r", reason is CompletionRejectionReason.DependencyPresent && reason.dependentCommandId == old.id &&
                reason.dependentLifetimeId == old.ownerTrackingLifetimeId.value)
        }
        run { // First gap unrelated (DEMAND, empty adopted id), later gap may reach the protected seal (SEAL adoption unknown).
            val f = fixture(); val c = confirmed(f, r())
            val m = f.store.prepare(ControlMutation.Add.prepare(ControlKind.DEMAND, java.util.UUID(0, 97)) { id ->
                literal(ControlObligationFixtures.request); set("id", ControlScalar.Text(id)) },
                ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 98)) { id -> literal(NamespaceSettlementFixtures.user); set("id", ControlScalar.Text(id)) })
            f.history(m).targets.set(listOf(ControlCommandTarget("", node(ControlObligationFixtures.request), false), null))
            refusedByDependency("27 laterGap", f, c, CompletionRejectionReason.DependencyUnknown(m.id, m.ownerTrackingLifetimeId.value, DependencyGapSource.Adoption(1)))
        }
        run { // Every gap confined to DEMAND rows: unrelated, consumption proceeds.
            val f = fixture(); val c = confirmed(f, l()); val before = f.disk()
            f.addUnresolved(foreign("u-demand-add", ControlMutation.Add.prepare(ControlKind.DEMAND, java.util.UUID(0, 96)) { id ->
                literal(ControlObligationFixtures.request); set("id", ControlScalar.Text(id)) }))
            assertConsumed("27 unrelatedGap", f, c, consume(f, c), expectedAfterDeletion(before, c))
        }
    }
}
