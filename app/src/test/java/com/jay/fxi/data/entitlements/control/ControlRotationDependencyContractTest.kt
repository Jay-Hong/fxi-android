package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-2C2 contract: owner G11 for Rotation consumption (6-2 skeleton r3 §4; 6-2C API consensus §3). Inside the
 * owner's synchronous decide — after the consumption decision is Ready and before any descriptor binding, P publication,
 * begin or Confirm — the owner enumerates every OTHER ref it holds (commands ∪ U ∪ P ∪ executing, by identity), projects
 * each from one captured view plus its adopted targets and descriptors (null when the tracker has no entry), and classifies
 * against the protected set {AppliedRow(c.id, c.lifetime), ControlRow(SEAL, id) and SealWitness(id, c.id) per fixed seal}.
 * Known ∩ P → Rejected(DependencyPresent(dependentCommandId, dependentLifetimeId, atom)); a gap that may reach P →
 * Rejected(DependencyUnknown(dependentCommandId, dependentLifetimeId, source)). Both keep the record (no Confirm) and the
 * ref's lifecycle (RETAINED on first entry; TERMINATION_PENDING with its fixed descriptor and P on retry). Refs the tracker
 * never registered are still found through U/P/executing; the consuming ref itself is excluded; unrelated Known refs do not
 * block. The closure declaration is a pure value check at the entry (6-2B); it is not repeated inside the decide. The
 * implementation thread reads but does not edit this file.
 */
class ControlRotationDependencyContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val N = NamespaceSettlementFixtures
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)

    private suspend fun confirmed(f: TerminationFixture): CommandRef {
        f.edit { it.clear(); it += N.raw() }
        val input = N.input()
        val c = f.tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
        check(controlTestTimeout("rotation execute") { f.store.execute(c, N.context) } is ControlStoreResult.Confirmed)
        check(f.history(c).expectedApplied is AppliedEvidence.Rotation)
        return c
    }
    private suspend fun consume(f: TerminationFixture, c: CommandRef) = controlTestTimeout("consume") { f.store.completeAfterConsumption(c, closure(c), declared) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef) = controlTestTimeout("retry") { f.store.retryTermination(c, closure(c)) }
    /** The settled seal "s" as it now sits in the record, for a dependent Edit's `before`. */
    private suspend fun settledS(f: TerminationFixture) = node(Json.parseToJsonElement(checkNotNull(f.storage.raw()[sealKey])).jsonArray
        .single { it.jsonObject.getValue("id").jsonPrimitive.content == "s" }.toString())
    private fun editOf(before: ControlNode) = ControlMutation.Edit.prepare(ControlKind.SEAL, before) {}
    private fun foreign(id: String, vararg actions: ControlMutation) = CommandRef(id, actions.toList(), OwnerTrackingLifetimeId.issue())
    private val sAtom = DependencyAtom.ControlRow(ControlKind.SEAL, "s")

    private suspend fun refusedByDependency(id: String, f: TerminationFixture, c: CommandRef, expected: CompletionRejectionReason) {
        f.armReadBack() // any management Confirm would write the barrier: whole-record equality = Confirm 0
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        assertEquals("D2B6/6-2C2.$id: reason $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-2C2.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-2C2.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-2C2.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-2C2.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-2C2.$id: leaseReleased", c !in f.tracker.executing)
    }
    private suspend fun completes(id: String, f: TerminationFixture, c: CommandRef) {
        val r = consume(f, c)
        assertTrue("D2B6/6-2C2.$id: completed $r", r is ControlCompletionResult.Completed && r.mode == CompletionMode.Consumed)
        assertEquals("D2B6/6-2C2.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
    }

    // ── each identity source alone ───────────────────────────────────────────────────────────────────────────────
    @Test fun T6_60_registeredEditOfTheSealBlocks() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        val m = f.store.prepare(editOf(settledS(f)))
        refusedByDependency("60", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, sAtom))
    }
    @Test fun T6_61_unresolvedOnlyRefBlocks() = runBlocking {
        // Not registered in this tracker (another lifetime), present only in U.
        val f = fixture(); val c = confirmed(f); val u = foreign("u-only", editOf(settledS(f))); f.addUnresolved(u)
        refusedByDependency("61", f, c, CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value, sAtom))
    }
    @Test fun T6_62_pendingOnlyRefBlocks() = runBlocking {
        val f = fixture(); val c = confirmed(f); val p = foreign("p-only", editOf(settledS(f))); f.addPending(p)
        refusedByDependency("62", f, c, CompletionRejectionReason.DependencyPresent(p.id, p.ownerTrackingLifetimeId.value, sAtom))
    }
    @Test fun T6_63_executingOnlyRefBlocks() = runBlocking {
        val f = fixture(); val c = confirmed(f); val e = foreign("e-only", editOf(settledS(f)))
        check(f.tracker.executing.add(e))
        try { refusedByDependency("63", f, c, CompletionRejectionReason.DependencyPresent(e.id, e.ownerTrackingLifetimeId.value, sAtom)) }
        finally { f.tracker.executing.remove(e) }
    }
    @Test fun T6_64_adoptedTargetOnlyBlocks() = runBlocking {
        // A registered SEAL Add whose proposed id is unrelated but whose recorded adoption joined the existing seal "s".
        val f = fixture(); val c = confirmed(f)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 91)) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) }
        val m = f.store.prepare(add)
        f.history(m).targets.set(listOf(ControlCommandTarget("s", settledS(f), true)))
        refusedByDependency("64", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value, sAtom))
    }

    // ── unknown vs unrelated ─────────────────────────────────────────────────────────────────────────────────────
    @Test fun T6_65_unknownAdoptionOfASealAddBlocksAsUnknown() = runBlocking {
        // U-only SEAL Add: no tracker entry → adoption unknown; the existing-seal join could be "s".
        val f = fixture(); val c = confirmed(f)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 92)) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) }
        val u = foreign("u-seal-add", add); f.addUnresolved(u)
        refusedByDependency("65", f, c, CompletionRejectionReason.DependencyUnknown(u.id, u.ownerTrackingLifetimeId.value, DependencyGapSource.Adoption(null)))
    }
    @Test fun T6_66_unrelatedRefsDoNotBlock() = runBlocking {
        // A registered DEMAND-only ref, a U-only RECOVERY_INTENT Add with unknown adoption, and the consuming ref itself.
        val f = fixture(); val c = confirmed(f)
        f.store.prepare(f.store.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        val u = foreign("u-recovery", ControlMutation.Add.prepare(ControlKind.RECOVERY_INTENT, java.util.UUID(0, 93)) { id ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        f.addUnresolved(u)
        completes("66", f, c)
    }

    // ── retry re-runs G11 ────────────────────────────────────────────────────────────────────────────────────────
    @Test fun T6_67_retryAfterADependentAppearedIsHeld() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        f.storage.storage.before = true
        check(consume(f, c) is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING)
        val d = checkNotNull(f.history(c).terminationDescriptor)
        val u = foreign("u-late", editOf(settledS(f))); f.addUnresolved(u)
        f.armReadBack()
        val before = f.storage.raw()
        val r = retry(f, c)
        assertEquals("D2B6/6-2C2.67: reason $r", CompletionRejectionReason.DependencyPresent(u.id, u.ownerTrackingLifetimeId.value, sAtom),
            (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-2C2.67: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("D2B6/6-2C2.67: descriptorKept", d, f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-2C2.67: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-2C2.67: noConfirmRequested", before, f.storage.raw())
    }

    // ── r2 (measurement 6-2C2 r1): descriptors held by other refs ──────────────────────────────────────────────
    @Test fun T6_68_anotherRefsReleaseDescriptorNamingThisAppliedRowBlocks() = runBlocking {
        // A registered ref pending release whose fixed descriptor names this rotation's own Applied row: the Applied atom
        // is its only protected dependency (its targets are RECOVERY_INTENT).
        val f = fixture(); val c = confirmed(f); val m = f.mutations()
        val row = ControlReleaseFixtures.row(m, id = c.id, lifetime = c.ownerTrackingLifetimeId.value,
            targets = listOf(AppliedTarget(0, ControlKind.RECOVERY_INTENT, "r9", false, true)))
        ControlReleaseFixtures.simulatePending(f.tracker, m, ReleasePendingDescriptor.ExactMutations(row))
        refusedByDependency("68", f, c, CompletionRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
            DependencyAtom.AppliedRow(c.id, c.ownerTrackingLifetimeId.value)))
    }
    @Test fun T6_69_anUnrelatedRefPendingNeverConfirmDoesNotBlock() = runBlocking {
        // Its fixed EvidenceAbsent descriptor keeps its projection Known (RECOVERY_INTENT only), so it is Clear.
        val f = fixture(); val c = confirmed(f); val m = f.mutations()
        f.armReadBack(); f.storage.storage.before = true
        check(controlTestTimeout("pending donor") { f.store.abandonBeforeFirstConfirm(m, closure(m)) } is ControlCompletionResult.Unconfirmed)
        check(m.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING && f.history(m).terminationDescriptor is TerminationPendingDescriptor.EvidenceAbsent)
        completes("69", f, c)
    }
}
