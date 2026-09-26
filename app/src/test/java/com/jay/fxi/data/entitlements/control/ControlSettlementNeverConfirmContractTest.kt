package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-3A contract: NeverConfirm for the three Handover settlements R (SettleRetiredNamespace), N
 * (RotateAndSettleCurrentNull) and L (SettleRetiredNull) — 6-3 skeleton r3 §1 and T7; revision 06 §7.1; 6-1 carry row
 * (`6-1_carry_to_6-2_6-3.md`). abandonBeforeFirstConfirm and the EvidenceAbsent branch of retryTermination now accept a
 * Handover body; every other Handover management path (consumption termination) stays UnsupportedInThisUnit until 6-3B.
 * Besides the shared history and whole-record gates, the owner's latest read refuses as Conflict when an own Applied row
 * exists (observedApplied recorded first) or when ANY seal carries a settlement whose operationId is this command's id —
 * without an own Applied row, on only part of the fixed targets, or on a seal outside them. Another operation's settled
 * seal is not own evidence and is preserved; so is the prepared target. A pending retry re-runs the same scan. The owner
 * keeps a single witness-scan condition (not a second Handover branch). Own records are the real transition's Confirm
 * candidate for the command, not hand-written. The implementation thread reads but does not edit this file.
 */
class ControlSettlementNeverConfirmContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)

    /** One settlement kind: its fixed input, the initial record, the body and the real transition's Confirm candidate. */
    private class Kind(
        val name: String,
        val raw: Preferences,
        val operationId: String,
        val body: ControlCommandBody.Handover,
        private val decide: (CommandRef, ControlRecordRead.Supported) -> RecordTransactionDecision<ControlRecordStore.Outcome>
    ) {
        /** The real transition applied for [c] on [raw]: own Applied row under c's tracking lifetime + settled seals. */
        fun candidate(c: CommandRef, on: Preferences = raw): Preferences {
            val d = decide(c, ControlRecordReader().read(on) as ControlRecordRead.Supported)
            check(d is RecordTransactionDecision.Confirm) { "fixture $name: transition must apply: $d" }
            return d.candidate
        }
    }
    private val R = RetiredNamespaceFixtures; private val NN = CurrentNullFixtures; private val L = RetiredNullFixtures
    private fun r(s: RetiredNamespaceSettlement = R.spec()) = Kind("R", R.raw(s), s.operationId, ControlCommandBody.SettleRetiredNamespace(s)) { c, read ->
        R.transition.decide(c, s, read, R.context, false, false) }
    private fun n(s: CurrentNullSettlement = NN.spec()) = Kind("N", NN.raw(s), s.operationId, ControlCommandBody.RotateAndSettleCurrentNull(s)) { c, read ->
        NN.transition.decide(c, s, read, NN.context, false, false) }
    private fun l(s: RetiredNullSettlement = L.spec()) = Kind("L", L.raw(s), s.operationId, ControlCommandBody.SettleRetiredNull(s)) { c, read ->
        L.transition.decide(c, s, read, L.context, false, false) }
    private fun kinds() = listOf(r(), n(), l())

    private fun register(f: TerminationFixture, k: Kind): CommandRef =
        f.tracker.registerPrepared(CommandRef(k.operationId, k.body, f.tracker.lifetimeId))
    private suspend fun write(f: TerminationFixture, record: Preferences) = f.edit { it.clear(); it += record }
    private fun withoutEvidence(p: Preferences) = p.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun seals(p: Preferences) = Json.parseToJsonElement(checkNotNull(p[sealKey])).jsonArray
    private fun settlementOp(p: Preferences, id: String) = seals(p).single { it.jsonObject.getValue("id").jsonPrimitive.content == id }
        .jsonObject["settlement"]?.jsonObject?.getValue("operationId")?.jsonPrimitive?.content
    /** [p] with seal [id] replaced by its unsettled original from [raw] (partial own witness). */
    private fun unsettle(p: Preferences, raw: Preferences, id: String) = p.toMutablePreferences().apply {
        val original = seals(raw).single { it.jsonObject.getValue("id").jsonPrimitive.content == id }
        this[sealKey] = JsonArray(seals(p).map { if (it.jsonObject.getValue("id").jsonPrimitive.content == id) original else it }).toString()
    }.toPreferences()

    private suspend fun abandon(f: TerminationFixture, c: CommandRef) = controlTestTimeout("settlement abandon") { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef) = controlTestTimeout("settlement retry") { f.store.retryTermination(c, closure(c)) }

    private suspend fun assertCompleted(id: String, f: TerminationFixture, c: CommandRef) {
        val before = f.disk()
        val r = abandon(f, c)
        assertTrue("D2B6/6-3A.$id: completed $r", r is ControlCompletionResult.Completed)
        r as ControlCompletionResult.Completed
        assertEquals("D2B6/6-3A.$id: neverSubmitted", CompletionMode.NeverSubmitted, r.mode)
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("D2B6/6-3A.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-3A.$id: onlyBarrierMayChange", withoutBarrier(before), withoutBarrier(after))
        assertEquals("D2B6/6-3A.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-3A.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/6-3A.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        assertTrue("D2B6/6-3A.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun assertConflictFirstEntry(id: String, f: TerminationFixture, c: CommandRef, observed: Boolean) {
        f.armReadBack() // any management Confirm(original) would now write the barrier: whole-record equality = Confirm 0
        val before = f.storage.raw()
        val r = abandon(f, c)
        assertTrue("D2B6/6-3A.$id: conflict $r", r is ControlCompletionResult.Conflict)
        assertEquals("D2B6/6-3A.$id: observedApplied", observed, f.history(c).observedApplied.get())
        assertEquals("D2B6/6-3A.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-3A.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertFalse("D2B6/6-3A.$id: notPending", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-3A.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-3A.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    /** Leaves [c] TERMINATION_PENDING with the read-back armed: the management Confirm write fails before its block. */
    private suspend fun pending(f: TerminationFixture, c: CommandRef): TerminationPendingDescriptor {
        f.armReadBack(); f.storage.storage.before = true
        val r = abandon(f, c)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
        f.storage.storage.before = false
        val d = checkNotNull(f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-3A: absenceDescriptor $d", d is TerminationPendingDescriptor.EvidenceAbsent &&
            d.mode == CompletionMode.NeverSubmitted && d.entry == TerminationEntry.AbandonBeforeFirstConfirm)
        return d
    }

    // ── T7.3 / T7.4: no own evidence completes; prepared targets and another operation's seal survive ───────────
    @Test fun T7s_01_preparedOnlySettlementCompletesAndKeepsItsTargets() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw); val c = register(f, k)
            val targetSeals = seals(k.raw).toString()
            assertCompleted("01 ${k.name}", f, c)
            assertEquals("D2B6/6-3A.01 ${k.name}: targetsKept", targetSeals, seals(f.disk()).toString())
        }
    }
    @Test fun T7s_02_anotherOperationsSettledSealIsNotOwnEvidence() = runBlocking {
        val others = listOf(r(R.spec(op = "r-other", did = "r-other-demand")), n(NN.spec(op = "n-other", did = "n-other-demand")),
            l(L.spec(op = "l-other")))
        for ((k, other) in kinds().zip(others)) {
            val f = fixture()
            val foreign = CommandRef(other.operationId, other.body, OwnerTrackingLifetimeId.issue())
            write(f, other.candidate(foreign)) // settled by `other` under a foreign tracking lifetime
            val c = register(f, k)
            check(settlementOp(f.disk(), "s") == other.operationId) { "fixture ${k.name}" }
            assertCompleted("02 ${k.name}", f, c)
            assertEquals("D2B6/6-3A.02 ${k.name}: otherOperationSealKept", other.operationId, settlementOp(f.disk(), "s"))
        }
    }

    // ── T7.1 / T7.2: own evidence refuses (carry row) ───────────────────────────────────────────────────────────
    @Test fun T7s_03_ownOperationWitnessWithoutAppliedIsConflict() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = register(f, k)
            write(f, withoutEvidence(k.candidate(c)))
            check(settlementOp(f.disk(), "s") == c.id && f.disk()[evidenceKey] == "[]") { "fixture ${k.name}" }
            assertConflictFirstEntry("03 ${k.name}", f, c, observed = false)
        }
    }
    @Test fun T7s_04_ownWitnessOnOneOfTwoTargetsIsConflict() = runBlocking {
        // N and L carry up to two NULL targets: a witness on only one is still own evidence.
        for (k in listOf(n(NN.both()), l(L.both()))) {
            val f = fixture(); val c = register(f, k)
            write(f, unsettle(withoutEvidence(k.candidate(c)), k.raw, "c"))
            check(settlementOp(f.disk(), "s") == c.id && settlementOp(f.disk(), "c") == null) { "fixture ${k.name}" }
            assertConflictFirstEntry("04 ${k.name}", f, c, observed = false)
        }
    }
    @Test fun T7s_05_ownAppliedIsConflictAndObservedFirst() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); val c = register(f, k)
            write(f, k.candidate(c))
            check(f.disk()[evidenceKey] != "[]") { "fixture ${k.name}" }
            assertConflictFirstEntry("05 ${k.name}", f, c, observed = true)
        }
    }

    // ── shared gates still apply ─────────────────────────────────────────────────────────────────────────────────
    @Test fun T7s_06_uninterpretableRecordIsRecoveryRequired() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw.toMutablePreferences().apply { this[demandKey] = "[17]" }.toPreferences()); val c = register(f, k)
            f.armReadBack()
            val before = f.storage.raw()
            val r = abandon(f, c)
            assertEquals("D2B6/6-3A.06 ${k.name}: recoveryRequired $r", RecoveryReason.UninterpretableObligations,
                (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            assertEquals("D2B6/6-3A.06 ${k.name}: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
            assertEquals("D2B6/6-3A.06 ${k.name}: recordUntouched", before, f.storage.raw())
        }
    }
    @Test fun T7s_07_historyGateRefusesBeforeStorage() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw); val c = register(f, k)
            f.history(c).confirmationRequested.set(true)
            val access = f.boundary.accesses
            val r = abandon(f, c)
            assertEquals("D2B6/6-3A.07 ${k.name}", CompletionRejectionReason.NotNeverConfirm(NeverConfirmViolation.ConfirmationRequested),
                (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-3A.07 ${k.name}: noStorageAccess", 0, f.boundary.accesses - access)
            assertEquals("D2B6/6-3A.07 ${k.name}: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        }
    }

    // ── T7.5: pending retry re-runs the witness scan ─────────────────────────────────────────────────────────────
    @Test fun T7s_08_retrySeesAnOwnWitnessThatAppeared() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw); val c = register(f, k); val d = pending(f, c)
            val witnessed = withoutEvidence(k.candidate(c))
            f.edit { p -> p[sealKey] = checkNotNull(witnessed[sealKey]) }
            check(settlementOp(f.storage.raw(), "s") == c.id) { "fixture ${k.name}" }
            val before = f.storage.raw()
            val r = retry(f, c)
            assertTrue("D2B6/6-3A.08 ${k.name}: conflict $r", r is ControlCompletionResult.Conflict)
            assertEquals("D2B6/6-3A.08 ${k.name}: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertSame("D2B6/6-3A.08 ${k.name}: descriptorKept", d, f.history(c).terminationDescriptor)
            assertTrue("D2B6/6-3A.08 ${k.name}: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
            assertEquals("D2B6/6-3A.08 ${k.name}: noConfirmRequested", before, f.storage.raw())
        }
    }
    @Test fun T7s_09_retryWithoutOwnEvidenceCompletes() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw); val c = register(f, k); pending(f, c)
            val targetSeals = seals(k.raw).toString()
            val r = retry(f, c)
            assertTrue("D2B6/6-3A.09 ${k.name}: completed $r", r is ControlCompletionResult.Completed && r.mode == CompletionMode.NeverSubmitted)
            assertEquals("D2B6/6-3A.09 ${k.name}: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
            assertNull("D2B6/6-3A.09 ${k.name}: exactCommandRemoved", f.tracker.findPrepared(c))
            assertFalse("D2B6/6-3A.09 ${k.name}: pCleared", c in f.tracker.recoverySnapshot().pendingReleases)
            assertEquals("D2B6/6-3A.09 ${k.name}: targetsKept", targetSeals, seals(f.disk()).toString())
        }
    }

    // ── only the NeverConfirm paths open in 6-3A ─────────────────────────────────────────────────────────────────
    @Test fun T7s_10_consumptionTerminationStaysUnsupportedForHandover() = runBlocking {
        for (k in kinds()) {
            val f = fixture(); write(f, k.raw); val c = register(f, k); f.history(c).confirmed.set(true)
            val access = f.boundary.accesses
            val r = controlTestTimeout("consume") { f.store.completeAfterConsumption(c, closure(c), RotationConsumption(true, true)) }
            assertEquals("D2B6/6-3A.10 ${k.name}", CompletionRejectionReason.UnsupportedInThisUnit, (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-3A.10 ${k.name}: noStorageAccess", 0, f.boundary.accesses - access)
            assertEquals("D2B6/6-3A.10 ${k.name}: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        }
    }

    // ── T7.6: one witness-scan condition in the owner ────────────────────────────────────────────────────────────
    @Test fun T7s_11_ownerKeepsASingleWitnessScanCondition() {
        val store = SealSourceTripwire.read(SealSourceTripwire.sourceRoot(File(checkNotNull(System.getProperty("user.dir"))),
            System.getProperty("fxi.seal.sourceRoot"))).getValue("main/java/com/jay/fxi/data/entitlements/control/ControlRecordStore.kt")
        assertEquals("D2B6/6-3A.11: single scan condition", 1,
            Regex("body !is ControlCommandBody\\.Mutations && body !is ControlCommandBody\\.Lifecycle").findAll(store).count())
        assertFalse("D2B6/6-3A.11: no separate Handover witness branch",
            Regex("is ControlCommandBody\\.Handover\\s*&&[^\\n]*settlement").containsMatchIn(store))
    }
}
