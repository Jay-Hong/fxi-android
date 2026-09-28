package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.RefreshIntent
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bA3a contract: the prior-storage confirmation issuers (T4, issuer half).
 * Fixed inputs: 6-4bA3 declaration r2 (6-4bA3_decl_codex.r2.md §1), 6-4bA3 consensus (N1–N4), 6-4bA3a recipes r1
 * (6-4bA3a_recipes_codex.r1.md). Every positive starts from the real store's own `Confirmed` return (seed → prepare →
 * execute) or from a real owner `transactRecord` return; negative cases include single-field variants and multi-defect
 * precedence cases; copies of real returns are synthetic variants. Expected outputs are literals from the writer tests' independent
 * landing facts (DemandAuthWriterTest.kt:65–72, HoldRecoveryFixtures.candidate), never the issuer's own result.
 *
 * Decisions this contract fixes (flagged for review):
 *  - Lifecycle reason order: REF_OR_DESCRIPTOR_MISMATCH → UNSUPPORTED_TRANSITION_OR_TARGET → EFFECT_NOT_ELIGIBLE →
 *    RECEIPT_MISSING → RECEIPT_MISMATCH → APPLIED_MISSING_OR_MISMATCH → SNAPSHOT_UNINTERPRETABLE → SNAPSHOT_OUTPUT_MISMATCH →
 *    NAMED_EFFECT_MISMATCH. The receipt's fixed fields and required observations are checked before, and separately from,
 *    the returned snapshot (the receipt carries observation marks only, no output text — ControlLifecycle.kt:62), so the
 *    snapshot reasons stay distinguishable.
 *  - AppliedThisAttempt and PostconditionConfirmed both require the exact own Lifecycle Applied row in the returned snapshot.
 *  - AppliedThisAttempt must come with CompletedWriteScope storage evidence; with LockedFileRead it is EFFECT_NOT_ELIGIBLE (N03b).
 *  - Named output targets supported in A3a: REQUEST REPLACE of REBIND_REQUESTS / END_AUTH_BINDING (same id), and the GUARD
 *    REPLACE of RECOVER_HOLD as the guard FLOOR component. 6-4bA3 consensus r4 N8 adds the GUARD AUTH REPLACE of
 *    END_AUTH_BINDING (a GuardAuth output; its rows live in the A3b2b contract). Any other target is
 *    UNSUPPORTED_TRANSITION_OR_TARGET.
 *  - Retained reason order: NOT_A_REQUIRED_SOURCE → OBSERVATION_RECORD_MISMATCH → SUBJECT_OR_BOUND_MISMATCH →
 *    NO_EXACT_RETAINED_ROW. OBSERVATION_NOT_NORMAL is removed from the declaration (6-4bA3 consensus r2 N5): both overloads
 *    only accept types that already denote a normal return (Confirmed; RecordTransactionResult whose evidence values are both
 *    normal endings — AccessEpochRecordTransaction.kt:28–43), so no input can reach it. R00 pins the remaining four names.
 *    A changed retained row (e.g. another AUTH state) is NO_EXACT_RETAINED_ROW; a changed slot — its subject, its locator, or a
 *    lower bound that no longer matches the slot's fixed original source (U03c) — is SUBJECT_OR_BOUND_MISMATCH.
 *  - A retained locator that is not the slot's own source location (same kind, other id) is SUBJECT_OR_BOUND_MISMATCH.
 *  - 6-4bA3 consensus r6 N12 (2) (A3d1): a floor slot's original row is the fixed row that produced its floor — a lifecycle
 *    guard's AFTER row — so END's T05 (before landing) / T05b (landed) payload expectations are the AFTER row's.
 *  - The issued binding keeps fixed values only: its non-static fields are exactly the declared ones and none holds a
 *    snapshot, record read, Preferences, proof, receipt or store result (canonical §4.2: "snapshot을 붙잡지 않는다"); the fixed
 *    descriptor and its namespace are separate copies with unmodifiable lists, plans are the same immutable plan objects.
 *  - Named effects checked in the snapshot beyond the output row: END keeps the guard output, RECOVER_HOLD removes the HOLD and
 *    writes the journal (N09, N11, N12).
 * assessG05 and linkNamedTransfer are untouched in A3a (consensus N2). The implementation thread reads but does not edit this file.
 */
class PriorStorageConfirmationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3a cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val F = DemandAuthFixtures
    private val H = HoldRecoveryFixtures

    // ── real store returns ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Run(val store: ControlStoreTestStorage, val c: CommandRef, val confirmed: ControlStoreResult.Confirmed,
        val context: AttemptContext) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
    }
    private suspend fun execute(store: ControlStoreTestStorage, c: CommandRef, context: AttemptContext): ControlStoreResult.Confirmed {
        val result = controlTestTimeout("A3a execute") { store.control.execute(c, context) }
        assertTrue("fixture: the store must return Confirmed, got $result", result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertSame(c, result.command)
        return result
    }
    private suspend fun rebind(): Run {
        val s = open()
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(r, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, 21))
        val ctx = F.context(F.runtime())
        return Run(s, c, execute(s, c, ctx), ctx)
    }
    private suspend fun end(): Run {
        val s = open()
        val g = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
        val r = F.request(binding = 2, origin = LifetimeId("old"))
        val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
        controlTestTimeout("seed end") { s.data.updateData { F.raw(g, r, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareEndAuthBinding(g, listOf(r), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        val ctx = F.context(F.runtime(closure = closed))
        return Run(s, c, execute(s, c, ctx), ctx)
    }
    private suspend fun recoverHold(before: Preferences = H.before(H.input())): Run {
        val s = open()
        val input = H.input()
        controlTestTimeout("seed hold") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        val ctx = H.context(input)
        return Run(s, c, execute(s, c, ctx), ctx)
    }
    private fun requestTarget() = LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)
    private fun guardTarget() = LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE)

    // ── independent expected outputs ───────────────────────────────────────────────────────────────────────────────────
    /** DemandAuthWriterTest.kt:65 / :71: same id "r", owner A, binding 3, origin life, order 22, intent FORCE_PREMIUM. */
    private val reboundRow get() = F.request(id = "r", owner = "A", binding = 3, origin = F.life, intent = RefreshIntent.FORCE_PREMIUM, order = 22)
    private val reboundValue = DemandV1("r", "A", 3, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("life"), 22))
    /** HoldRecoveryFixtures.candidate: guard g keeps its AUTH and gains floor boot/11000/29000/new-life. */
    private val recoveredGuardRow get() = H.field(H.guard(), "floor", FloorGuardFixtures.floor(29000, "boot", 11000, "new-life"))
    private val recoveredGuardValue = ScheduleGuardV1("g", FloorV1("boot", 11000, 29000, LifetimeId("new-life")),
        AuthSnapshotV1("A", 2, 3, LifetimeId("life"), false, 10, 20))

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun issued(r: LifecycleOutputConfirmationResult): ConfirmationBinding.LifecycleOutput {
        assertTrue("expected Issued, got $r", r is LifecycleOutputConfirmationResult.Issued)
        val b = (r as LifecycleOutputConfirmationResult.Issued).value.binding
        assertTrue("expected LifecycleOutput binding, got $b", b is ConfirmationBinding.LifecycleOutput)
        return b as ConfirmationBinding.LifecycleOutput
    }
    private fun rejected(r: LifecycleOutputConfirmationResult, reason: LifecycleConfirmationFailure) =
        assertEquals(LifecycleOutputConfirmationResult.Rejected(reason), r)
    private fun issuedRetained(r: RetainedSourceConfirmationResult): ConfirmationBinding.RetainedSource {
        assertTrue("expected Issued, got $r", r is RetainedSourceConfirmationResult.Issued)
        val b = (r as RetainedSourceConfirmationResult.Issued).value.binding
        assertTrue("expected RetainedSource binding, got $b", b is ConfirmationBinding.RetainedSource)
        return b as ConfirmationBinding.RetainedSource
    }
    private fun rejectedRetained(r: RetainedSourceConfirmationResult, reason: RetainedConfirmationFailure) =
        assertEquals(RetainedSourceConfirmationResult.Rejected(reason), r)
    private fun confirm(run: Run, target: LifecycleTarget, confirmed: ControlStoreResult.Confirmed = run.confirmed,
        exact: CommandRef = run.c, fixed: ControlLifecycleDescriptor = run.fixed) =
        PriorStorageConfirmation.confirmLifecycleOutput(exact, fixed, confirmed, target)
    private fun payloadOf(n: ControlNode) = n.toPayloadEntry()
    private fun <T> assertUnmodifiable(label: String, list: List<T>, probe: T) =
        assertTrue("$label must be unmodifiable", runCatching { (list as MutableList<T>).add(probe) }.isFailure)
    private fun assertSameDescriptorValue(expected: ControlLifecycleDescriptor, actual: ControlLifecycleDescriptor) {
        assertNotSame("fixed must be a separate copy", expected, actual)
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.transition, actual.transition)
        assertEquals(expected.targets, actual.targets)
        val probe = expected.targets.first()
        assertNotSame(expected.targets, actual.targets); assertUnmodifiable("targets", actual.targets, probe)
        assertEquals(expected.requiredUnchanged, actual.requiredUnchanged)
        if (expected.requiredUnchanged.isNotEmpty()) assertNotSame(expected.requiredUnchanged, actual.requiredUnchanged)
        assertUnmodifiable("requiredUnchanged", actual.requiredUnchanged, probe)
        assertEquals(expected.executor, actual.executor)
        val en = expected.namespace; val an = actual.namespace
        assertEquals(en == null, an == null)
        if (en != null && an != null) {
            assertNotSame("namespace must be a separate copy", en, an)
            assertEquals(en.before, an.before); assertEquals(en.after, an.after); assertEquals(en.journal, an.journal)
            assertEquals(en.userMayContain, an.userMayContain); assertEquals(en.krxMayContain, an.krxMayContain)
            assertNotSame("namespace.journal must be a separate copy", en.journal, an.journal)
            if (en.journal.isNotEmpty()) assertUnmodifiable("namespace.journal", an.journal, en.journal.first())
        }
        assertSame(expected.demandAuth, actual.demandAuth); assertSame(expected.removeEmptyGuard, actual.removeEmptyGuard)
        assertSame(expected.recoverHold, actual.recoverHold); assertSame(expected.recoverIntent, actual.recoverIntent)
    }
    /** Snapshot variant: same record with one edit, re-read (a synthetic variant of the real return). */
    private fun withSnapshot(confirmed: ControlStoreResult.Confirmed, edit: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) =
        confirmed.copy(snapshot = ConfirmedControlSnapshot(ControlRecordReader().read(
            confirmed.snapshot.record.original.toMutablePreferences().apply(edit).toPreferences()) as ControlRecordRead.Supported))
    private fun demandRows(confirmed: ControlStoreResult.Confirmed): List<ControlNode> =
        confirmed.snapshot.record.arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
    private fun rows(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val forbiddenHeld = listOf(ConfirmedControlSnapshot::class.java, ControlRecordRead::class.java, Preferences::class.java,
        ConfirmationProof::class.java, ControlSettlementReceipt::class.java, ControlStoreResult::class.java, RecordTransactionResult::class.java)
    private fun fieldNames(x: Any) = x.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
    private fun assertHoldsFixedValuesOnly(x: Any) {
        for (f in x.javaClass.declaredFields) assertTrue("${x.javaClass.simpleName}.${f.name}: ${f.type.simpleName} must not be held",
            forbiddenHeld.none { it.isAssignableFrom(f.type) })
    }

    // ═══ enum pins ═════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun R00_reasonSets() {
        assertEquals(listOf("REF_OR_DESCRIPTOR_MISMATCH", "UNSUPPORTED_TRANSITION_OR_TARGET", "EFFECT_NOT_ELIGIBLE", "RECEIPT_MISSING",
            "RECEIPT_MISMATCH", "APPLIED_MISSING_OR_MISMATCH", "SNAPSHOT_UNINTERPRETABLE", "SNAPSHOT_OUTPUT_MISMATCH", "NAMED_EFFECT_MISMATCH"),
            LifecycleConfirmationFailure.entries.map { it.name })
        assertEquals(setOf("NOT_A_REQUIRED_SOURCE", "NO_EXACT_RETAINED_ROW", "SUBJECT_OR_BOUND_MISMATCH", "OBSERVATION_RECORD_MISMATCH"),
            RetainedConfirmationFailure.entries.map { it.name }.toSet())
    }

    // ═══ lifecycle output: positives ═══════════════════════════════════════════════════════════════════════════════════
    @Test fun L01_rebind_sameIdRequest_appliedThisAttempt() = runReleaseTest {
        val run = rebind()
        assertEquals(ConfirmedEffect.AppliedThisAttempt, run.confirmed.effect)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, run.confirmed.proof.storage)
        val b = issued(confirm(run, requestTarget()))
        assertSame(run.c, b.command)
        assertSameDescriptorValue(run.fixed, b.fixed)
        assertEquals(requestTarget(), b.outputTarget)
        val out = b.output as TypedDestinationTuple.Request
        assertEquals(DestinationLocator.Payload(ControlKind.DEMAND, "r"), out.locator)
        assertEquals(payloadOf(reboundRow), payloadOf(out.row))
        assertEquals(reboundValue, out.parsed)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, b.effect)
        assertEquals(LifecycleTargetObservation.PresentExact, b.observation)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, b.storageEvidence)
    }

    @Test fun L02_rebind_reexecuted_postconditionConfirmed_lockedRead() = runReleaseTest {
        val run = rebind()
        val again = execute(run.store, run.c, run.context)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, again.effect)
        assertEquals(RecordTransactionEvidence.LockedFileRead, again.proof.storage)
        val b = issued(confirm(run, requestTarget(), again))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, b.effect)
        assertEquals(RecordTransactionEvidence.LockedFileRead, b.storageEvidence)
        assertEquals(payloadOf(reboundRow), payloadOf((b.output as TypedDestinationTuple.Request).row))
    }

    @Test fun L03_endAuthBinding_withRequest_sameIdRebind() = runReleaseTest {
        val run = end()
        val b = issued(confirm(run, requestTarget()))
        val out = b.output as TypedDestinationTuple.Request
        assertEquals(DestinationLocator.Payload(ControlKind.DEMAND, "r"), out.locator)
        assertEquals(payloadOf(reboundRow), payloadOf(out.row))
        assertEquals(reboundValue, out.parsed)
        assertEquals(LifecycleTransition.END_AUTH_BINDING, b.fixed.transition)
    }

    @Test fun L04_recoverHold_guardFloorOutput() = runReleaseTest {
        val run = recoverHold()
        val b = issued(confirm(run, guardTarget()))
        val out = b.output as TypedDestinationTuple.GuardFloor
        assertEquals(DestinationLocator.Guard("g", GuardPart.FLOOR), out.locator)
        assertEquals(payloadOf(recoveredGuardRow), payloadOf(out.row))
        assertEquals(recoveredGuardValue, out.parsed)
        assertEquals(LifecycleTargetObservation.PresentExact, b.observation)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, b.storageEvidence)
    }

    @Test fun L05_issuedBindingHoldsFixedValuesOnly_andCopiesTheDescriptor() = runReleaseTest {
        val run = recoverHold()
        val r = confirm(run, guardTarget())
        val b = issued(r)
        val token = (r as LifecycleOutputConfirmationResult.Issued).value
        assertEquals(setOf("binding"), fieldNames(token))
        assertEquals(setOf("command", "fixed", "outputTarget", "output", "effect", "observation", "storageEvidence"), fieldNames(b))
        assertHoldsFixedValuesOnly(token)
        assertHoldsFixedValuesOnly(b)
        assertTrue("fixture: RECOVER_HOLD fixes a namespace journal", checkNotNull(run.fixed.namespace).journal.isNotEmpty())
        assertSameDescriptorValue(run.fixed, b.fixed)
    }

    // ═══ lifecycle output: one reason each ═════════════════════════════════════════════════════════════════════════════
    @Test fun N01_ref_exactCommandIsATwin_or_fixedIsAnotherDescriptor() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, requestTarget(), exact = CommandRef(run.c.id, run.c.body, run.c.ownerTrackingLifetimeId)),
            LifecycleConfirmationFailure.REF_OR_DESCRIPTOR_MISMATCH)
        val other = rebind()
        rejected(confirm(run, requestTarget(), fixed = other.fixed), LifecycleConfirmationFailure.REF_OR_DESCRIPTOR_MISMATCH)
    }

    @Test fun N02_unsupportedTarget_notATarget_or_notTheNamedOutput() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, LifecycleTarget(ControlKind.DEMAND, "dormant", LifecycleEffect.REPLACE)),
            LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
        val hold = recoverHold()
        val created = hold.fixed.targets.single { it.target.effect == LifecycleEffect.CREATE }.target
        rejected(confirm(hold, created), LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
    }

    @Test fun N03_effectJoinedExisting_isNotEligible() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, requestTarget(), run.confirmed.copy(effect = ConfirmedEffect.JoinedExisting)),
            LifecycleConfirmationFailure.EFFECT_NOT_ELIGIBLE)
    }

    @Test fun N04_receiptMissing() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, requestTarget(), run.confirmed.copy(receipt = null)), LifecycleConfirmationFailure.RECEIPT_MISSING)
    }

    @Test fun N05_receiptMismatch_commandId_or_outputObservation() = runReleaseTest {
        val run = rebind()
        val receipt = checkNotNull(run.confirmed.lifecycleReceipt)
        fun copy(commandId: String = receipt.commandId, targets: List<LifecycleObservedTarget> = receipt.targets) =
            ControlLifecycleReceipt(receipt.transition, commandId, targets, receipt.before, receipt.after, receipt.journal,
                receipt.hasUninterpretable, receipt.hasUninterpretableMetadata, receipt.requiredUnchanged)
        rejected(confirm(run, requestTarget(), run.confirmed.copy(receipt = copy(commandId = "other"))),
            LifecycleConfirmationFailure.RECEIPT_MISMATCH)
        val changed = receipt.targets.map { if (it.target == requestTarget()) it.copy(observation = LifecycleTargetObservation.Changed) else it }
        assertTrue("fixture: the output target is observed", receipt.targets.any { it.target == requestTarget() })
        rejected(confirm(run, requestTarget(), run.confirmed.copy(receipt = copy(targets = changed))),
            LifecycleConfirmationFailure.RECEIPT_MISMATCH)
    }

    @Test fun N06_appliedMissing_inBothEffects() = runReleaseTest {
        val run = rebind()
        val noApplied: androidx.datastore.preferences.core.MutablePreferences.() -> Unit =
            { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }
        rejected(confirm(run, requestTarget(), withSnapshot(run.confirmed, noApplied)), LifecycleConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
        val again = execute(run.store, run.c, run.context)
        rejected(confirm(run, requestTarget(), withSnapshot(again, noApplied)), LifecycleConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
        // An interpretable Applied row of the same command id but another tracking lifetime is a mismatch, not a match.
        val life = run.c.ownerTrackingLifetimeId.value
        val otherLife: androidx.datastore.preferences.core.MutablePreferences.() -> Unit = {
            val wire = checkNotNull(this[ControlLifecycleEvidenceFixtures.evidenceKey])
            assertTrue("fixture: the Applied row names the lifetime", wire.contains(life))
            val other = if (life == "00000000-0000-0000-0000-000000000001") "00000000-0000-0000-0000-000000000002"
                else "00000000-0000-0000-0000-000000000001"
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = wire.replace(life, other)
        }
        for (confirmed in listOf(run.confirmed, again)) {
            val v = withSnapshot(confirmed, otherLife)
            assertFalse("fixture: the changed Applied row is interpretable", v.snapshot.record.hasUninterpretableMetadata)
            rejected(confirm(run, requestTarget(), v), LifecycleConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
        }
    }

    @Test fun N07_snapshotUninterpretable_unrelatedOpaqueRow() = runReleaseTest {
        val run = rebind()
        val v = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(demandRows(run.confirmed)).dropLast(1) + """,{"id":"zz"}]"""
        }
        assertTrue("fixture: supported envelope with an opaque row", v.snapshot.record.hasUninterpretable)
        rejected(confirm(run, requestTarget(), v), LifecycleConfirmationFailure.SNAPSHOT_UNINTERPRETABLE)
    }

    @Test fun N08_snapshotOutputMismatch_outputRowFieldChanged() = runReleaseTest {
        val run = rebind()
        val v = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(demandRows(run.confirmed).map {
                if ((it.text("id") as? FieldRead.Present)?.value == "r")
                    F.request(id = "r", owner = "A", binding = 3, origin = F.life, intent = RefreshIntent.IF_STALE, order = 22) else it
            })
        }
        assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
        rejected(confirm(run, requestTarget(), v), LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
    }

    @Test fun N09_namedEffectMismatch_recoverHold_holdStillPresent() = runReleaseTest {
        val run = recoverHold()
        val v = withSnapshot(run.confirmed) { this[ControlRecordKeys.payload(ControlKind.HOLD)] = H.payload(listOf(H.hold())) }
        assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
        rejected(confirm(run, guardTarget(), v), LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
    }

    @Test fun N10_multipleDefects_firstReasonWins() = runReleaseTest {
        val run = rebind()
        val twin = CommandRef(run.c.id, run.c.body, run.c.ownerTrackingLifetimeId)
        rejected(confirm(run, requestTarget(), run.confirmed.copy(effect = ConfirmedEffect.JoinedExisting), exact = twin),
            LifecycleConfirmationFailure.REF_OR_DESCRIPTOR_MISMATCH)
        rejected(confirm(run, requestTarget(), run.confirmed.copy(effect = ConfirmedEffect.JoinedExisting, receipt = null)),
            LifecycleConfirmationFailure.EFFECT_NOT_ELIGIBLE)
        val opaqueNoReceipt = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(demandRows(run.confirmed)).dropLast(1) + """,{"id":"zz"}]"""
        }.copy(receipt = null)
        rejected(confirm(run, requestTarget(), opaqueNoReceipt), LifecycleConfirmationFailure.RECEIPT_MISSING)
    }

    // ═══ retained source ═══════════════════════════════════════════════════════════════════════════════════════════════
    private fun holdDerivation(c: CommandRef) = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle))
        .let { it as RequirementDerivation.Available }
    private fun RequirementDerivation.Available.slot(p: (RequiredObligationKey) -> Boolean) = orderedSlots.single { p(it.key) }
    private fun RequirementDerivation.Available.holdSource() =
        slot { it.component == ObligationComponent.SOURCE && it.subject is ObligationSubject.Hold && it.branch == LandingBranch.N }
    private suspend fun lockedRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> {
        val locked = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        assertEquals(RecordTransactionEvidence.LockedFileRead, locked.evidence)
        assertEquals(locked.snapshot, locked.value.original)
        return locked
    }
    private suspend fun writtenRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> {
        val written = controlTestTimeout("owner written read") {
            s.owner.transactRecord { raw ->
                val candidate = raw.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "retained-observation-1" }.toPreferences()
                RecordTransactionDecision.Confirm(candidate, ControlRecordReader().read(candidate) as ControlRecordRead.Supported)
            }
        }
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, written.evidence)
        assertEquals(written.snapshot, written.value.original)
        return written
    }
    /** A store seeded with the RECOVER_HOLD source record, and the command prepared against it (never executed). */
    private suspend fun heldStore(before: Preferences = H.before(H.input())): Pair<ControlStoreTestStorage, CommandRef> {
        val s = open()
        controlTestTimeout("seed held") { s.data.updateData { before } }
        return s to s.control.prepareRecoverHold(H.input(), LifecycleOrderSource(H.life, 21))
    }

    @Test fun T01_retainedHold_lockedRead_and_writtenRead() = runReleaseTest {
        val (s, c) = heldStore()
        val slot = holdDerivation(c).holdSource()
        for (read in listOf(lockedRead(s), writtenRead(s))) {
            val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), read))
            assertEquals(slot, b.slot)
            val observed = b.observed as RetainedDestinationTuple.Payload
            assertEquals(DestinationLocator.Payload(ControlKind.HOLD, "h"), observed.locator)
            assertEquals(payloadOf(H.hold()), payloadOf(observed.row))
            assertEquals(read.evidence, b.storageEvidence)
            assertEquals(setOf("slot", "observed", "storageEvidence"), fieldNames(b))
            assertHoldsFixedValuesOnly(b)
        }
    }

    @Test fun T02_retainedHoldFloor_payloadLocator() = runReleaseTest {
        val (s, c) = heldStore()
        val slot = holdDerivation(c).slot {
            it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.HOLD && it.branch == LandingBranch.N
        }
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), lockedRead(s)))
        assertEquals(slot, b.slot)
        assertEquals(payloadOf(H.hold()), payloadOf((b.observed as RetainedDestinationTuple.Payload).row))
    }

    @Test fun T03_retainedJournal_keyAlreadyPresent() = runReleaseTest {
        val before = H.before(H.input()).toMutablePreferences().apply { this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        val (s, c) = heldStore(before)
        val slot = holdDerivation(c).slot { it.component == ObligationComponent.JOURNAL && it.branch == LandingBranch.N }
        val key = JournalTargetV1("A", PurgeScope.CAPABILITY, "k")
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Journal(key), lockedRead(s)))
        assertEquals(DestinationLocator.Journal(key), (b.observed as RetainedDestinationTuple.Journal).locator)
    }

    @Test fun T04_retainedGuardAuth_fromTheRecoverHoldConfirmed() = runReleaseTest {
        val run = recoverHold()
        val slot = holdDerivation(run.c).slot { it.component == ObligationComponent.AUTH && it.branch == LandingBranch.N }
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Guard("g", GuardPart.AUTH), run.confirmed))
        val observed = b.observed as RetainedDestinationTuple.Guard
        assertEquals(DestinationLocator.Guard("g", GuardPart.AUTH), observed.locator)
        assertEquals(payloadOf(recoveredGuardRow), payloadOf(observed.row))
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, b.storageEvidence)
    }

    @Test fun U01_notARequiredSource_receiptSlot() = runReleaseTest {
        val (s, c) = heldStore()
        val receiptSlot = holdDerivation(c).slot { it.component == ObligationComponent.RECEIPT && it.branch == LandingBranch.L }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(receiptSlot, DestinationLocator.Payload(ControlKind.HOLD, "h"), lockedRead(s)),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
    }

    @Test fun U02_noExactRetainedRow_absent_or_opaque() = runReleaseTest {
        val (_, c) = heldStore()
        val slot = holdDerivation(c).holdSource()
        for (hold in listOf("[]", """[{"id":"h"}]""")) {
            val other = open()
            controlTestTimeout("seed variant") {
                other.data.updateData { H.before(H.input()).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = hold }.toPreferences() }
            }
            rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), lockedRead(other)),
                RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        }
    }

    @Test fun U03_subjectOrBoundMismatch_slotSubject_or_otherLocatorId() = runReleaseTest {
        val withOther = H.before(H.input()).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = H.payload(listOf(H.hold(), H.hold(id = "other-hold")))
        }.toPreferences()
        val (s, c) = heldStore(withOther)
        val slot = holdDerivation(c).holdSource()
        val subject = slot.key.subject as ObligationSubject.Hold
        val moved = slot.copy(key = slot.key.copy(subject = subject.copy(binding = subject.binding + 1)))
        val read = lockedRead(s)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(moved, DestinationLocator.Payload(ControlKind.HOLD, "h"), read),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        assertFalse("fixture: the other HOLD row is interpretable", read.value.hasUninterpretable)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "other-hold"), read),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    @Test fun U04_observationRecordMismatch_valueAndSnapshotDiffer() = runReleaseTest {
        val (s, c) = heldStore()
        val slot = holdDerivation(c).holdSource()
        val read = lockedRead(s)
        val forged = RecordTransactionResult(read.value,
            read.snapshot.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "different" }.toPreferences(), read.evidence)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), forged),
            RetainedConfirmationFailure.OBSERVATION_RECORD_MISMATCH)
    }

    @Test fun U05_multipleDefects_firstReasonWins() = runReleaseTest {
        val (_, c) = heldStore()
        val d = holdDerivation(c)
        val slot = d.holdSource()
        val empty = open()
        controlTestTimeout("seed empty hold") {
            empty.data.updateData { H.before(H.input()).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }.toPreferences() }
        }
        val absent = lockedRead(empty)
        val receiptSlot = d.slot { it.component == ObligationComponent.RECEIPT && it.branch == LandingBranch.L }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(receiptSlot, DestinationLocator.Payload(ControlKind.HOLD, "h"), absent),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
        val forgedAbsent = RecordTransactionResult(absent.value,
            absent.snapshot.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "different" }.toPreferences(), absent.evidence)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), forgedAbsent),
            RetainedConfirmationFailure.OBSERVATION_RECORD_MISMATCH)
        val subject = slot.key.subject as ObligationSubject.Hold
        val moved = slot.copy(key = slot.key.copy(subject = subject.copy(binding = subject.binding + 1)))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(moved, DestinationLocator.Payload(ControlKind.HOLD, "h"), absent),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    // ═══ review r1 additions ═══════════════════════════════════════════════════════════════════════════════════════════
    private fun changedAuthGuard(row: ControlNode, order: Long): ControlNode {
        val auth = checkNotNull(guard(row)?.auth).copy(authStateOrder = order)
        return FloorGuardFixtures.field(row, "auth", F.guard(auth = auth).toPayloadEntry().fields["auth"])
    }

    @Test fun N11_namedEffectMismatch_end_guardOutputDamaged_requestIntact() = runReleaseTest {
        val run = end()
        val v = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(demandRows(run.confirmed).map {
                if ((it.text("id") as? FieldRead.Present)?.value == "g") changedAuthGuard(it, 99) else it
            })
        }
        assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
        rejected(confirm(run, requestTarget(), v), LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
    }

    @Test fun N12_namedEffectMismatch_recoverHold_journalMissing_guardIntact() = runReleaseTest {
        val run = recoverHold()
        val v = withSnapshot(run.confirmed) { remove(PURGE_JOURNAL) }
        assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
        rejected(confirm(run, guardTarget(), v), LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
    }

    @Test fun U06_noExactRetainedRow_journalKeyAbsent() = runReleaseTest {
        val (s, c) = heldStore()
        val slot = holdDerivation(c).slot { it.component == ObligationComponent.JOURNAL && it.branch == LandingBranch.N }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot,
            DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.CAPABILITY, "k")), lockedRead(s)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    @Test fun U07_noExactRetainedRow_guardAuthChanged_confirmedOverload() = runReleaseTest {
        val run = recoverHold()
        val slot = holdDerivation(run.c).slot { it.component == ObligationComponent.AUTH && it.branch == LandingBranch.N }
        val v = withSnapshot(run.confirmed) {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(demandRows(run.confirmed).map {
                if ((it.text("id") as? FieldRead.Present)?.value == "g") changedAuthGuard(it, 99) else it
            })
        }
        assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Guard("g", GuardPart.AUTH), v),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    // ═══ measurement r1 additions (measure-6-4bA3a/sensitivity.r1.json survivors that are contract gaps) ═══════════════
    private suspend fun settle(): Run {
        val s = open()
        val r = F.request(); val g = F.guard(F.auth.copy(authStopped = false), 90000)
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
        controlTestTimeout("seed settle") { s.data.updateData { F.raw(r, g, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, LifecycleOrderSource(F.life, 21))
        val ctx = F.context(F.runtime())
        return Run(s, c, execute(s, c, ctx), ctx)
    }

    @Test fun N02b_unsupported_sameIdOtherEffect_settleTransition() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.CREATE)),
            LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
        val st = settle()
        assertEquals(LifecycleTransition.SETTLE_QUERY, st.fixed.transition)
        rejected(confirm(st, st.fixed.targets.first().target), LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
    }

    @Test fun N03b_appliedThisAttempt_withLockedFileRead_isNotEligible() = runReleaseTest {
        val run = rebind()
        rejected(confirm(run, requestTarget(), run.confirmed.copy(proof = ConfirmationProof(RecordTransactionEvidence.LockedFileRead))),
            LifecycleConfirmationFailure.EFFECT_NOT_ELIGIBLE)
    }

    @Test fun N05b_receiptMismatch_everyFixedField() = runReleaseTest {
        val run = rebind()
        val r = checkNotNull(run.confirmed.lifecycleReceipt)
        assertEquals("fixture: REBIND fixes no namespace", null, run.fixed.namespace)
        val variants = listOf(
            ControlLifecycleReceipt(LifecycleTransition.END_AUTH_BINDING, r.commandId, r.targets, r.before, r.after, r.journal,
                r.hasUninterpretable, r.hasUninterpretableMetadata, r.requiredUnchanged),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, r.before, r.after, r.journal, r.hasUninterpretable,
                r.hasUninterpretableMetadata, r.requiredUnchanged + LifecycleObservedTarget(
                    LifecycleTarget(ControlKind.DEMAND, "dormant", LifecycleEffect.REPLACE), LifecycleTargetObservation.PresentExact)),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, FenceV1("A", "u", "k"), r.after, r.journal,
                r.hasUninterpretable, r.hasUninterpretableMetadata, r.requiredUnchanged),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, r.before, FenceV1("A", "u", "k"), r.journal,
                r.hasUninterpretable, r.hasUninterpretableMetadata, r.requiredUnchanged),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, r.before, r.after,
                mapOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)) to JournalObservation.Present),
                r.hasUninterpretable, r.hasUninterpretableMetadata, r.requiredUnchanged),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, r.before, r.after, r.journal, true,
                r.hasUninterpretableMetadata, r.requiredUnchanged),
            ControlLifecycleReceipt(r.transition, r.commandId, r.targets, r.before, r.after, r.journal, r.hasUninterpretable,
                true, r.requiredUnchanged))
        for (v in variants) rejected(confirm(run, requestTarget(), run.confirmed.copy(receipt = v)), LifecycleConfirmationFailure.RECEIPT_MISMATCH)
        // A REMOVE target is expected Absent: the RECOVER_HOLD receipt observing h as PresentExact is a mismatch.
        val hold = recoverHold()
        val hr = checkNotNull(hold.confirmed.lifecycleReceipt)
        val removeObserved = hr.targets.map { if (it.target.effect == LifecycleEffect.REMOVE) it.copy(observation = LifecycleTargetObservation.PresentExact) else it }
        assertTrue("fixture: RECOVER_HOLD removes h", hr.targets.any { it.target.effect == LifecycleEffect.REMOVE })
        rejected(confirm(hold, guardTarget(), hold.confirmed.copy(receipt = ControlLifecycleReceipt(hr.transition, hr.commandId, removeObserved,
            hr.before, hr.after, hr.journal, hr.hasUninterpretable, hr.hasUninterpretableMetadata, hr.requiredUnchanged))),
            LifecycleConfirmationFailure.RECEIPT_MISMATCH)
    }

    @Test fun N07b_snapshotUninterpretable_metadataOnly() = runReleaseTest {
        val run = rebind()
        val v = withSnapshot(run.confirmed) {
            val wire = checkNotNull(this[ControlLifecycleEvidenceFixtures.evidenceKey])
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = wire.dropLast(1) + ",{}]"
        }
        assertFalse("fixture: rows interpretable", v.snapshot.record.hasUninterpretable)
        assertTrue("fixture: metadata uninterpretable", v.snapshot.record.hasUninterpretableMetadata)
        rejected(confirm(run, requestTarget(), v), LifecycleConfirmationFailure.SNAPSHOT_UNINTERPRETABLE)
    }

    @Test fun N12b_namedEffectMismatch_recoverHold_fenceBack_or_krxMarkerKept() = runReleaseTest {
        val run = recoverHold()
        for (edit in listOf<androidx.datastore.preferences.core.MutablePreferences.() -> Unit>(
            { this[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH] = "k" },
            { this[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX] = true })) {
            val v = withSnapshot(run.confirmed, edit)
            assertFalse("fixture: still interpretable", v.snapshot.record.hasUninterpretable)
            rejected(confirm(run, guardTarget(), v), LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
        }
    }

    /** END keeps guard g's floor, so its FLOOR slot's bound is the existing floor: before landing it is a retained source. */
    @Test fun T05_retainedGuardFloor_endBeforeLanding_andChangedFloor() = runReleaseTest {
        val g = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
        val r = F.request(binding = 2, origin = LifetimeId("old"))
        val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
        suspend fun seeded(guardRow: ControlNode): Pair<ControlStoreTestStorage, CommandRef> {
            val s = open()
            controlTestTimeout("seed end held") { s.data.updateData { F.raw(guardRow, r, F.request(id = "dormant", owner = "B")) } }
            return s to s.control.prepareEndAuthBinding(g, listOf(r), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        }
        val (s, c) = seeded(g)
        val slot = holdDerivation(c).slot {
            it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.DEMAND && it.branch == LandingBranch.N
        }
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Guard("g", GuardPart.FLOOR), lockedRead(s)))
        assertEquals(payloadOf(g), payloadOf((b.observed as RetainedDestinationTuple.Guard).row))
        // The whole row through the payload locator: 6-4bA3 consensus r6 N12 (2) binds a lifecycle guard's floor slot to its
        // fixed AFTER row, and END changes the guard's AUTH while keeping its floor — the stored before row is not it.
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot, DestinationLocator.Payload(ControlKind.DEMAND, "g"), lockedRead(s)), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        val shorter = open()
        controlTestTimeout("seed shorter floor") {
            shorter.data.updateData { F.raw(F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 80000), r, F.request(id = "dormant", owner = "B")) }
        }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Guard("g", GuardPart.FLOOR), lockedRead(shorter)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    @Test fun T06_retainedExactSource_emptyGuard_andGuardWithFloor() = runReleaseTest {
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), F.guard(auth = null), null, F.binding, F.decision(),
            LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        val c = F.command(p)
        val slot = holdDerivation(c).slot { it.subject == ObligationSubject.ExactTarget(ControlKind.DEMAND, "g") && it.branch == LandingBranch.N }
        val exact = open(); controlTestTimeout("seed empty guard") { exact.data.updateData { F.raw(F.guard(auth = null)) } }
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.DEMAND, "g"), lockedRead(exact)))
        assertEquals(payloadOf(F.guard(auth = null)), payloadOf((b.observed as RetainedDestinationTuple.Payload).row))
        val floored = open(); controlTestTimeout("seed floored guard") { floored.data.updateData { F.raw(FloorGuardFixtures.guard()) } }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.DEMAND, "g"), lockedRead(floored)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    @Test fun U03b_subjectOrBoundMismatch_floorSubject_authOtherGuard_journalKey_journalSeal() = runReleaseTest {
        val journaled = H.before(H.input()).toMutablePreferences().apply { this[PURGE_JOURNAL] = "A||k|CAPABILITY" }.toPreferences()
        val (s, c) = heldStore(journaled)
        val d = holdDerivation(c)
        val read = lockedRead(s)
        val floor = d.slot { it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.HOLD && it.branch == LandingBranch.N }
        val fs = floor.key.subject as ObligationSubject.Floor
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(floor.copy(key = floor.key.copy(subject = fs.copy(origin = LifetimeId("other")))),
            DestinationLocator.Payload(ControlKind.HOLD, "h"), read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val journal = d.slot { it.component == ObligationComponent.JOURNAL && it.branch == LandingBranch.N }
        val js = journal.key.subject as ObligationSubject.Journal
        val key = JournalTargetV1("A", PurgeScope.CAPABILITY, "k")
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(journal.copy(key = journal.key.copy(subject = js.copy(key = key.copy(epoch = "k2")))),
            DestinationLocator.Journal(key), read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(journal.copy(key = journal.key.copy(subject = js.copy(sourceSealId = "other"))),
            DestinationLocator.Journal(key), read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        // AUTH: the record's only guard carries the very same AUTH under another id (one guard per record), so it is not
        // the slot's own guard g.
        val auth = d.slot { it.component == ObligationComponent.AUTH && it.branch == LandingBranch.N }
        val required = ((auth.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Auth).required
        val withOther = open()
        controlTestTimeout("seed other guard") {
            withOther.data.updateData { H.before(H.input()).toMutablePreferences().apply {
                this[ControlRecordKeys.payload(ControlKind.DEMAND)] = rows(listOf(F.guard(auth = required, id = "other-guard")))
            }.toPreferences() }
        }
        val otherRead = lockedRead(withOther)
        assertFalse("fixture: interpretable", otherRead.value.hasUninterpretable)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(auth, DestinationLocator.Guard("other-guard", GuardPart.AUTH), otherRead),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    @Test fun U05b_notARequiredSource_winsOverObservationMismatch() = runReleaseTest {
        val (s, c) = heldStore()
        val receiptSlot = holdDerivation(c).slot { it.component == ObligationComponent.RECEIPT && it.branch == LandingBranch.L }
        val read = lockedRead(s)
        val forged = RecordTransactionResult(read.value,
            read.snapshot.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "different" }.toPreferences(), read.evidence)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(receiptSlot, DestinationLocator.Payload(ControlKind.HOLD, "h"), forged),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
    }

    // ═══ review r4 additions (Codex 6-4bA3a_contract_review_codex.r4.md) ══════════════════════════════════════════════
    @Test fun U01b_mispairedHoldSlot_isNotARequiredSource() = runReleaseTest {
        val (s, c) = heldStore()
        val slot = holdDerivation(c).holdSource()
        val malformed = slot.copy(key = slot.key.copy(component = ObligationComponent.FLOOR))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(malformed, DestinationLocator.Payload(ControlKind.HOLD, "h"), lockedRead(s)),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
    }

    // ═══ review r5 addition: a copied slot whose lower bound no longer matches its fixed original source ═══════════════
    @Test fun U03c_changedLowerBound_isSubjectOrBoundMismatch() = runReleaseTest {
        val (s, c) = heldStore()
        val d = holdDerivation(c)
        val read = lockedRead(s)
        val holdSlot = d.holdSource()
        val holdReq = holdSlot.requirement as SlotRequirement.Required
        val holdBound = holdReq.lowerBound as RequiredLowerBound.Hold
        val changedHold = holdSlot.copy(requirement = holdReq.copy(lowerBound = holdBound.copy(source = holdBound.source.copy(
            floor = checkNotNull(holdBound.source.floor).copy(anchorBootId = "other-boot")))))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(changedHold, DestinationLocator.Payload(ControlKind.HOLD, "h"), read),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val floorSlot = d.slot {
            it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.HOLD && it.branch == LandingBranch.N
        }
        val floorReq = floorSlot.requirement as SlotRequirement.Required
        val floorBound = floorReq.lowerBound as RequiredLowerBound.Floor
        val changedFloor = floorSlot.copy(requirement = floorReq.copy(lowerBound = floorBound.copy(
            captured = floorBound.captured.copy(anchorBootId = "other-boot"))))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(changedFloor, DestinationLocator.Payload(ControlKind.HOLD, "h"), read),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    // ═══ measurement r3 addition: the guard-floor lower bound must match the fixed original guard (END keeps it) ══════
    @Test fun U03d_changedGuardFloorLowerBound_isSubjectOrBoundMismatch() = runReleaseTest {
        val g = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
        val r = F.request(binding = 2, origin = LifetimeId("old"))
        val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
        val s = open()
        controlTestTimeout("seed end held") { s.data.updateData { F.raw(g, r, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareEndAuthBinding(g, listOf(r), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        val slot = holdDerivation(c).slot {
            it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.DEMAND && it.branch == LandingBranch.N
        }
        val req = slot.requirement as SlotRequirement.Required
        val bound = req.lowerBound as RequiredLowerBound.Floor
        val changed = slot.copy(requirement = req.copy(lowerBound = bound.copy(captured = bound.captured.copy(anchorBootId = "other-boot"))))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(changed, DestinationLocator.Guard("g", GuardPart.FLOOR), lockedRead(s)),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    // ═══ review r7 additions (Codex 6-4bA3a_contract_review_codex.r7.md) ══════════════════════════════════════════════
    /** A copied ExactSource slot whose node is swapped for the floored guard that the record now holds. */
    @Test fun U03e_changedExactSourceNode_isSubjectOrBoundMismatch() = runReleaseTest {
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), F.guard(auth = null), null, F.binding, F.decision(),
            LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        val slot = holdDerivation(F.command(p)).slot { it.subject == ObligationSubject.ExactTarget(ControlKind.DEMAND, "g") && it.branch == LandingBranch.N }
        val req = slot.requirement as SlotRequirement.Required
        val bound = req.lowerBound as RequiredLowerBound.ExactSource
        val changed = slot.copy(requirement = req.copy(lowerBound = bound.copy(node = FloorGuardFixtures.guard())))
        val floored = open(); controlTestTimeout("seed floored guard") { floored.data.updateData { F.raw(FloorGuardFixtures.guard()) } }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(changed, DestinationLocator.Payload(ControlKind.DEMAND, "g"), lockedRead(floored)),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    /** After END lands the guard keeps its floor and carries the new AUTH: the whole row is the fixed AFTER row (r6 N12 (2)). */
    @Test fun T05b_endLanded_payloadRetained_isTheAfterRow() = runReleaseTest {
        val run = end()
        val slot = holdDerivation(run.c).slot {
            it.component == ObligationComponent.FLOOR && (it.subject as? ObligationSubject.Floor)?.sourceKind == ControlKind.DEMAND && it.branch == LandingBranch.N
        }
        assertTrue("fixture: the guard floor is kept, only AUTH changes",
            guard(demandRows(run.confirmed).single { (it.text("id") as? FieldRead.Present)?.value == "g" })?.floor ==
                ((slot.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Floor).captured)
        issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Guard("g", GuardPart.FLOOR), run.confirmed))
        val after = checkNotNull(run.fixed.targets.single { it.target.id == "g" }.after)
        val payload = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.DEMAND, "g"),
            run.confirmed))
        assertEquals(payloadOf(after), payloadOf((payload.observed as RetainedDestinationTuple.Payload).row))
    }
}
