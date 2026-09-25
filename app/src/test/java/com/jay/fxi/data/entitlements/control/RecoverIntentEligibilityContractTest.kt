package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claude-owned 5e-2 eligibility contract (plan v1 row 4): RecoverIntentTransition.eligibility as a
 * direct boundary. Each case breaks exactly one gate of an eligible base. Gate set: skeleton design
 * v2 §3 (intentRecovery only, no holdRecovery fallback; full binding; current gate; full fence ==
 * fixed before; exact source preimage; closure; fresh reservation; request id), design L436
 * steps 1–3, L449–451, §9.4 I02/I03.
 * E0 supplies a separate eligible control; JUnit execution order is not assumed.
 * These single-fault cases fix results at the direct eligibility boundary,
 * not precedence among simultaneous failures or facade gate order.
 * The raw-owner case is a redundant-guard behavioral control.
 * Operation-ID collision is covered at the common new-application boundary
 * in plan rows 9/10; detailed fresh reservations remain in row 5.
 * A mutant that only changes one rejection into another is CLASSIFICATION_ONLY, not a safety kill.
 * Exact problem values follow the RECOVER_HOLD precedent (RecoverHoldTransition.eligibility and
 * HoldRecoveryBoundary.closureProblem); that equality is an agreed interface contract for the new
 * writer. No raw-owner single-fault case: the fixed before owner equals the executor owner
 * (prepare), so a raw owner change also breaks the fence gate (E4_owner is a behavioral control).
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentEligibilityContractTest {
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private val writer = RecoverIntentTransition(ControlPayloadCodec())
    private val tracker = "current-tracker"

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String = "r", session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private val source = intent("A", "CAPABILITY", "k")
    private val sibling = intent("A", "USER", "u", id = "r2")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun raw(intents: List<ControlNode> = listOf(source, sibling), change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit = {}): Preferences =
        ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(intents)
            change(this)
        }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)

    private fun restart(src: ControlNode = source, exec: SettlementExecutor = executor) =
        HoldRecoveryClosure.AfterRestart(src, exec, "previous-tracker", true, true)
    private fun same(captured: Set<String> = setOf("w"), joined: Set<String> = setOf("w"), closed: Boolean = true) =
        HoldRecoveryClosure.SameProcess(source, executor, 5, closed, captured, joined)
    private fun input(closure: HoldRecoveryClosure = restart()) = RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure)
    private fun plan(i: RecoverIntentInput = input()) = RecoverIntentPlan.prepare(i, ids, LifecycleOrderSource(newLife, 21))
    private fun runtime(closure: HoldRecoveryClosure = restart(), b: LifecycleBinding = binding, closed: Boolean = true,
        work: Set<String> = emptySet(), generation: Long = 5) = HoldRecoveryRuntime(b, generation, closed, work, closure)
    private fun context(rt: HoldRecoveryRuntime? = runtime(), hold: HoldRecoveryRuntime? = null) =
        AttemptContext("A", 3, newLife, false, false, holdRecovery = hold, intentRecovery = rt)

    private fun rejected(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))
    private fun conflict(reason: ConflictReason) = HoldRecoveryProblem.Conflict(reason)

    @Test fun E0_eligibleBase() {
        assertNull(plan().preparationProblem)
        assertNull(eligible("E0_positive"), writer.eligibility(plan(), context(), read(raw()), tracker))
    }

    // ---- E1: the intent-only slot (skeleton v2 §3) ----

    @Test fun E1_intentSlotRequired() {
        assertEquals(eligible("E1_missing"), rejected("AttemptContextRequired"), writer.eligibility(plan(), context(rt = null), read(raw()), tracker))
        assertEquals(eligible("E1_holdSlotNoFallback"), rejected("AttemptContextRequired"),
            writer.eligibility(plan(), context(rt = null, hold = runtime()), read(raw()), tracker))
    }

    // ---- E2: full runtime binding equality, not sessionId or executor alone (skeleton v2 §3) ----

    @Test fun E2_fullBinding() {
        assertEquals(eligible("E2_startedOrder"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(runtime(b = binding.copy(startedOrder = 2))), read(raw()), tracker))
        assertEquals(eligible("E2_identity"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(runtime(b = binding.copy(identity = IdentityV1("A", 3)))), read(raw()), tracker))
        assertEquals(eligible("E2_startEvent"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(runtime(b = binding.copy(startEventId = "other-start"))), read(raw()), tracker))
        assertEquals(eligible("E2_acceptedAuthOrder"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(runtime(b = binding.copy(acceptedAuthOrder = 1))), read(raw()), tracker))
    }

    // ---- E3: common current gate (L436 step 2) ----

    @Test fun E3_currentGate() {
        val p = plan(); val r = read(raw())
        assertEquals(eligible("E3_signOut"), conflict(ConflictReason.IdentityTransitionPending),
            writer.eligibility(p, context().copy(signOutOpen = true), r, tracker))
        assertEquals(eligible("E3_identityPending"), conflict(ConflictReason.IdentityTransitionPending),
            writer.eligibility(p, context().copy(identityPersistencePending = true), r, tracker))
        assertEquals(eligible("E3_teardown"), conflict(ConflictReason.IdentityTransitionPending),
            writer.eligibility(p, context(), read(raw { it[TEARDOWN_OWED_FOR] = "A" }), tracker))
        assertEquals(eligible("E3_contextOrigin"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(p, context().copy(originLifetimeId = LifetimeId("other-life")), r, tracker))
        assertEquals(eligible("E3_contextOwner"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(p, context().copy(ownerUid = "B"), r, tracker))
        assertEquals(eligible("E3_contextBinding"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(p, context().copy(binding = 4), r, tracker))
    }

    // ---- E4: full fence equals the fixed before (L436 step 2) ----

    @Test fun E4_fence() {
        assertEquals(eligible("E4_krx"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(), read(raw { it[KRX_EPOCH] = "k9" }), tracker))
        assertEquals(eligible("E4_user"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(), read(raw { it[USER_EPOCH] = "u9" }), tracker))
        // Redundant-guard behavioral control, not a single-fault case (raw owner and fence both break).
        assertEquals(eligible("E4_owner"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(),
                read(raw { it[OWNER_UID] = "B" }), tracker))
    }

    // ---- E5: I03a/b — the one exact source preimage; the sibling is never a substitute (L436 step 1, L451) ----

    @Test fun E5_I03_preimage() {
        assertEquals(eligible("I03a_changed"), conflict(ConflictReason.TargetChanged),
            writer.eligibility(plan(), context(), read(raw(listOf(intent("A", "CAPABILITY", "k8"), sibling))), tracker))
        assertEquals(eligible("I03b_absent"), conflict(ConflictReason.TargetMissing),
            writer.eligibility(plan(), context(), read(raw(listOf(sibling))), tracker))
        // Direct boundary only: the facade blocks such a record earlier with RecoveryRequired(UninterpretableObligations).
        assertEquals(eligible("E5_uninterpretable"), conflict(ConflictReason.UninterpretableTarget),
            writer.eligibility(plan(), context(), read(raw(listOf(intent("A", "FUTURE", "k"), sibling))), tracker))
        assertEquals(eligible("E5_duplicate"), conflict(ConflictReason.IdCollision),
            writer.eligibility(plan(), context(), read(raw(listOf(source, source, sibling))), tracker))
    }

    // ---- E6: I02a/b — closure predicates; closure-pinned source and executor (L436 step 2, L449, §9.4 I02) ----

    @Test fun E6_I02_closure() {
        val r = read(raw())
        assertEquals(eligible("I02a_entriesOpen"), rejected("BindingNotClosed"),
            writer.eligibility(plan(), context(runtime(closed = false)), r, tracker))
        val sp = same(joined = emptySet())
        assertEquals(eligible("I02b_notJoined"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(input(sp)), context(runtime(sp, work = setOf("w"))), r, tracker))
        val ok = same()
        assertNull(eligible("E6_sameProcessPositive"), writer.eligibility(plan(input(ok)), context(runtime(ok, work = setOf("w"))), r, tracker))
        val otherPin = restart(src = sibling)
        assertEquals(eligible("E6_sourcePin"), rejected("BindingNotClosed"),
            writer.eligibility(plan(input(otherPin)), context(runtime(otherPin)), r, tracker))
        assertEquals(eligible("E6_previousSame"), rejected("BindingNotClosed"),
            writer.eligibility(plan(), context(), r, "previous-tracker"))
        val otherExec = restart(exec = executor.copy(binding = 4))
        assertEquals(eligible("E6_executorPin"), rejected("BindingNotClosed"),
            writer.eligibility(plan(input(otherExec)), context(runtime(otherExec)), r, tracker))
        val openCapture = same(closed = false)
        assertEquals(eligible("E6_captureOpen"), rejected("BindingNotClosed"),
            writer.eligibility(plan(input(openCapture)), context(runtime(openCapture, work = setOf("w"))), r, tracker))
        assertEquals(eligible("E6_registeredChanged"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(input(ok)), context(runtime(ok, work = emptySet())), r, tracker))
        assertEquals(eligible("E6_generationChanged"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(input(ok)), context(runtime(ok, work = setOf("w"), generation = 6)), r, tracker))
        assertEquals(eligible("E6_runtimeClosureMismatch"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(), context(runtime(restart().copy(previousCallerEnded = false))), r, tracker))
        val emptyPrevious = restart().copy(previousTrackingLifetimeId = "")
        assertEquals(eligible("E6_previousEmpty"), rejected("BindingNotClosed"),
            writer.eligibility(plan(input(emptyPrevious)), context(runtime(emptyPrevious)), r, tracker))
        val callerOpen = restart().copy(previousCallerEnded = false)
        assertEquals(eligible("E6_callerNotEnded"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(input(callerOpen)), context(runtime(callerOpen)), r, tracker))
        val storageOpen = restart().copy(previousStorageScopeEnded = false)
        assertEquals(eligible("E6_storageNotEnded"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(input(storageOpen)), context(runtime(storageOpen)), r, tracker))
        assertEquals(eligible("E6_restartWork"), rejected("RelatedWorkNotQuiescent"),
            writer.eligibility(plan(), context(runtime(work = setOf("w"))), r, tracker))
    }

    // ---- E7: fresh reservation at attempt time against the latest record (L437 step 3, skeleton v2 §2) ----

    @Test fun E7_freshReservation() {
        assertEquals(eligible("E7_journalHoldsFresh"), rejected("EpochNotFresh"),
            writer.eligibility(plan(), context(), read(raw { it[PURGE_JOURNAL] = "A|$krxFresh||USER" }), tracker))
    }

    // ---- E8: the new REQUEST id must be free at attempt time (skeleton v2 §2, RECOVER_HOLD requestIdCall) ----

    @Test fun E8_requestIdCollision() {
        val taken = raw { it[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOf(DemandAuthFixtures.request(id = "new-request"))) }
        assertEquals(eligible("E8_collision"), conflict(ConflictReason.IdCollision), writer.eligibility(plan(), context(), read(taken), tracker))
    }
}
