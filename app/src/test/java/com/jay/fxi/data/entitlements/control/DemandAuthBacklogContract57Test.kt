package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * 5e-1 contract, fifty-seventh file — the last NEGATIVE groups of the STEP3ZK r8 OPEN list (group ⑦). Each: an independent
 * truth vector written here in production order (out-of-guard items vacuously true), a twin with the literal result, one
 * false condition, and the role at the unit's own layer.
 *  - Add.prepare valid, SEAL term (ControlCommand.kt:22, X.addApply): a generic SEAL add may not carry a settlement.
 *  - Edit.prepare nonSettlement (CC:38–39, X.editApply): a generic SEAL edit may not change the SEAL payload.
 *  - validDescriptor (ControlLifecycle.kt:229–262, S_noop): a SETTLE plan with no target is refused (LC:206).
 *  - DemandAuthTransition.decide size rejections (DT:38–40 encodeChanged · DT:42–43 evidence append; W.encode · V.envelopes ·
 *    T.evidenceReason): the writer truth vector holds and only the encoded size exceeds the limit.
 *  - confirmOnly namespace journal (LC:204–210, consumedJournalCannotReconfirmOldHandoff): at the store, a removed journal
 *    refuses the re-confirmation and writes nothing; twin: the journal present is Confirmed.
 *  - requiredEffects → callerRetry (DT:205, 215–224, A04.candidateRechecksBuilder): a candidate row agreeing with a patched
 *    builder row is still refused by the independent caller recheck; only callerRetry's intent term is false.
 */
class DemandAuthBacklogContract57Test {
    // Add.prepare — SEAL settlement term
    private fun addVec(built: ControlWriteResult, id: String): List<Pair<String, Boolean>> {
        val v = (built as? ControlWriteResult.Written)?.let { (ControlObligations.read(ControlKind.SEAL, it.node) as ControlEntryRead.Interpreted).value }
        return listOf("written" to (v != null), "idMatches" to (v == null || v.id == id),
            "sealNoSettlement" to (v == null || v !is SealV1 || v.settlement == null),
            "genericAllowed" to (v == null || !(v is ScheduleGuardV1 && "auth" in (built as ControlWriteResult.Written).node.names)))
    }
    @Test fun X_addApply_seal() {
        val plainId = UUID(0, 1).toString(); val settledId = UUID(0, 2).toString()
        val plain = ControlMutation.Add.prepare(ControlKind.SEAL, UUID(0, 1)) { id -> literal(ControlObligationFixtures.nullSeal); set("id", ControlScalar.Text(id)) }
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(addVec(ControlObligations.build(ControlKind.SEAL) {
            literal(ControlObligationFixtures.nullSeal); set("id", ControlScalar.Text(plainId)) }, plainId)))
        assertTrue("positive twin: a SEAL without settlement is written", plain.built is ControlWriteResult.Written)
        assertEquals("truth vector: only the settlement differs", setOf("sealNoSettlement"), V.falses(addVec(ControlObligations.build(ControlKind.SEAL) {
            literal(ControlObligationFixtures.settledSeal); set("id", ControlScalar.Text(settledId)) }, settledId)))
        val settled = ControlMutation.Add.prepare(ControlKind.SEAL, UUID(0, 2)) { id -> literal(ControlObligationFixtures.settledSeal); set("id", ControlScalar.Text(id)) }
        assertFalse(F.eligible("Z.gen.addSeal"), settled.built is ControlWriteResult.Written)
    }

    // Edit.prepare — SEAL nonSettlement
    private fun editVec(kind: ControlKind, before: ControlNode, changed: ControlWriteResult) = listOf(
        "nonSettlement" to (kind != ControlKind.SEAL || (changed is ControlWriteResult.Written && before.toPayloadEntry() == changed.node.toPayloadEntry())),
        "authPreserved" to (changed !is ControlWriteResult.Written || kind != ControlKind.DEMAND || ControlSchema.read(kind, before) !is ScheduleGuardV1 ||
            before.toPayloadEntry().fields["auth"]?.toString() == changed.node.toPayloadEntry().fields["auth"]?.toString()))
    @Test fun X_editApply_seal() {
        val pre = ControlObligationFixtures.node(ControlObligationFixtures.nullSeal)
        val settle: ControlEditor.() -> Unit = { createChild("settlement") { literal(ControlObligationFixtures.settlement) } }
        assertEquals("truth vector: twin (empty edit)", emptySet<String>(), V.falses(editVec(ControlKind.SEAL, pre, ControlObligations.editExisting(ControlKind.SEAL, pre) {})))
        assertTrue("positive twin: the payload-preserving SEAL edit is written", ControlMutation.Edit.prepare(ControlKind.SEAL, pre) {}.changed is ControlWriteResult.Written)
        val pure = ControlObligations.editExisting(ControlKind.SEAL, pre, settle)
        assertTrue("fixture: the pure editor writes the settlement", pure is ControlWriteResult.Written)
        assertEquals("truth vector: only the SEAL payload differs", setOf("nonSettlement"), V.falses(editVec(ControlKind.SEAL, pre, pure)))
        assertFalse(F.eligible("Z.gen.editSeal"), ControlMutation.Edit.prepare(ControlKind.SEAL, pre, settle).changed is ControlWriteResult.Written)
    }

    // validDescriptor — S_noop
    private fun lcDecide(p: DemandAuthPlan, raw: Preferences, codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(p)
        val read = ControlRecordReader(codec).read(raw) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, read, F.context(), false, false)
    }
    private fun facts(node: ControlNode?, t: LifecycleFixedTarget): Boolean {
        val v = node?.let { (ControlObligations.read(t.target.kind, it) as? ControlEntryRead.Interpreted)?.value } ?: return false
        return v.id == t.target.id && when (t.role) {
            LifecycleRole.REQUEST -> v is DemandV1; LifecycleRole.GUARD -> v is ScheduleGuardV1
            LifecycleRole.HOLD -> v is RestoredHold; LifecycleRole.RECOVERY_INTENT -> v is RecoveryIntentV1
        }
    }
    private fun descVec(p: DemandAuthPlan): List<Pair<String, Boolean>> {
        val d = p.descriptor("command"); val ts = d.targets; val all = ts + d.requiredUnchanged
        assertEquals("fixture: a SETTLE descriptor", LifecycleTransition.SETTLE_QUERY, d.transition)
        val suffix = ts.dropWhile { it.target.effect == LifecycleEffect.REMOVE }
        val removed = ts.takeWhile { it.target.effect == LifecycleEffect.REMOVE }; val changed = ts.drop(removed.size)
        return listOf("opId" to d.operationId.isNotEmpty(), "nonEmpty" to ts.isNotEmpty(),
            "shape" to (ts.all { it.target.kind == ControlKind.DEMAND } && suffix.size <= 2 && suffix.none { it.target.effect == LifecycleEffect.REMOVE }),
            "unique" to (all.map { it.target.id }.toSet().size == all.size),
            "targetFacts" to ts.all { when (it.target.effect) {
                LifecycleEffect.REMOVE -> facts(it.before, it) && it.after == null
                LifecycleEffect.CREATE -> it.before == null && facts(it.after, it)
                LifecycleEffect.REPLACE -> facts(it.before, it) && facts(it.after, it) && it.before?.toPayloadEntry() != it.after?.toPayloadEntry() } },
            "unchangedFacts" to d.requiredUnchanged.all { it.target.effect == LifecycleEffect.REPLACE && facts(it.after, it) && it.before?.toPayloadEntry() == it.after?.toPayloadEntry() },
            "roles" to (removed.all { it.role == LifecycleRole.REQUEST } && changed.map { it.role } in listOf(emptyList(), listOf(LifecycleRole.REQUEST),
                listOf(LifecycleRole.GUARD), listOf(LifecycleRole.REQUEST, LifecycleRole.GUARD)) &&
                (removed.isEmpty() || changed.none { it.role == LifecycleRole.REQUEST && it.target.effect != LifecycleEffect.CREATE })))
    }
    @Test fun S_noop_descriptor() {
        val g = F.guard(F.auth.copy(authStopped = false)); val early = F.request(order = 4)
        val twin = DemandAuthPlan.settle(listOf(early), g, null, F.binding, F.decision(), orders, "g-new", "r-new") // C53 Q03 twin
        assertNull("twin prepared", twin.preparationFailure)
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(descVec(twin)))
        assertTrue("positive twin: the consuming SETTLE reaches Confirm", lcDecide(twin, F.raw(g, early)) is RecordTransactionDecision.Confirm)
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        val noop = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 60)), F.guard(auth = resumed, wait = 90000), F.request(), settle = true)
        assertNull("fixture: the no-change plan is prepared", noop.preparationFailure)
        assertEquals("truth vector: only the empty target list differs", setOf("nonEmpty"), V.falses(descVec(noop)))
        assertFalse(F.eligible("Z.lc.noop"), lcDecide(noop, F.raw(noop.guardBefore!!, noop.retryBefore!!)) is RecordTransactionDecision.Confirm)
    }

    // DemandAuthTransition.decide size rejections — the writer vector holds; only the encoded size differs
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun initPlan() = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
    private fun writer(p: DemandAuthPlan, raw: Preferences) = V.falses(V.decideGates(p, F.context(), raw)) +
        V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command"))
    private fun bytes(raw: Preferences, key: Preferences.Key<String>) = raw[key]!!.toByteArray(Charsets.UTF_8).size
    private fun sizeNo(id: String, only: String, before: Preferences, limit: Int) {
        val p = initPlan(); F.schema(before)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("truth vector: writer gates (limit-independent)", emptySet<String>(), writer(p, before))
        val twin = lcDecide(p, before)
        assertTrue("positive twin: the default codec reaches Confirm", twin is RecordTransactionDecision.Confirm)
        val candidate = (twin as RecordTransactionDecision.Confirm).candidate
        val sizes = listOf("demandFits" to (bytes(candidate, ControlRecordKeys.payload(ControlKind.DEMAND)) <= limit),
            "evidenceFits" to (bytes(candidate, ControlLifecycleEvidenceFixtures.evidenceKey) <= limit))
        assertEquals("truth vector: only the target size exceeds the limit", setOf(only), V.falses(sizes))
        assertFalse(F.eligible(id), lcDecide(p, before, ControlPayloadCodec(maxPayloadBytes = limit)) is RecordTransactionDecision.Confirm)
    }
    @Test fun W_encode_size() = sizeNo("Z.dt.encode", "demandFits",
        F.raw(*(1..12).map { F.request(id = "pad-$it", owner = "B", binding = 1) }.toTypedArray()), 1600)
    @Test fun W_evidence_size() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        sizeNo("Z.dt.evidence", "evidenceFits", F.raw().toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = kotlinx.serialization.json.JsonArray(rows).toString() }.toPreferences(), 2300)
    }

    // confirmOnly namespace journal — store level
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun cleanup() = runBlocking { controlTestTimeout("c57 cleanup", 30000) { opened.forEach { it.close() } } }
    private val namespace = LifecycleNamespacePostcondition(ControlLifecycleEvidenceFixtures.fence, ControlLifecycleEvidenceFixtures.fence,
        listOf(PendingPurge("A", "old", null, setOf(PurgeScope.USER))))
    // the namespace journal written as its stored line (PendingPurge("A", "old", null, {USER}) — owner|epoch|krx|scopes)
    private val journalLine = "A|old||USER"
    private fun journalEntries(raw: Preferences): Set<String> = raw[PURGE_JOURNAL]?.split("\n")?.toSet() ?: emptySet()
    private fun confirmVec(raw: Preferences) = listOf(
        "fenceOwner" to (raw[OWNER_UID] == namespace.after.ownerUid), "fenceUser" to (raw[USER_EPOCH] == namespace.after.userAccessEpoch),
        "fenceKrx" to (raw[KRX_EPOCH] == namespace.after.krxCapabilityEpoch),
        "journalContains" to (journalLine in journalEntries(raw)))
    private suspend fun confirmOnce(journal: String?): Pair<ControlStoreResult, Boolean> {
        val s = ControlStoreTestStorage(temp.newFile()).also { opened += it }
        val tracking = ControlCommandTracking.forOwner(s.owner)
        val c = tracking.registerPrepared(ControlLifecycleEvidenceFixtures.command(ControlLifecycleEvidenceFixtures.descriptor(namespace = namespace), tracking.lifetimeId))
        val raw = ControlLifecycleEvidenceFixtures.raw(evidence = "[${ControlLifecycleEvidenceFixtures.wire(c)}]").toMutablePreferences().apply {
            if (journal != null) this[PURGE_JOURNAL] = journal else remove(PURGE_JOURNAL) }.toPreferences()
        assertEquals("truth vector", if (journal != null) emptySet() else setOf("journalContains"), V.falses(confirmVec(raw)))
        controlTestTimeout("c57 seed") { s.data.updateData { raw } }
        val writes = s.storage.writes
        val result = controlTestTimeout("c57 confirm") { s.control.execute(c) }
        return result to (s.storage.writes == writes)
    }
    @Test fun CO_journalConsumed() = runReleaseTest {
        val (ok, _) = confirmOnce(journalLine)
        assertTrue("positive twin: the journal present is re-confirmed, got $ok", ok is ControlStoreResult.Confirmed)
        val (r, noWrite) = confirmOnce(null)
        assertTrue(F.eligible("Z.st.confirmOnly.journal"), r !is ControlStoreResult.Confirmed && noWrite)
    }

    // requiredEffects → callerRetry (A04.candidateRechecksBuilder), intent term
    private fun row(raw: Preferences, id: String) = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original
    private fun recheckVec(p: DemandAuthPlan, raw: Preferences, caller: LifecycleCaller): List<Pair<String, Boolean>> {
        val request = demand(row(raw, "r"))!!; val expected = demand(p.retryAfter)!!; val before = demand(p.retryBefore)
        val grant = p.grants[request.id]; val x = p.binding.executor
        return listOf("present" to true, "A14b" to (request.intent >= caller.intent), "owner" to (request.ownerUid == x.ownerUid),
            "binding" to (request.binding == x.binding), "origin" to (request.raisedAt.origin == x.originLifetimeId),
            "order" to (request.raisedAt.value == expected.raisedAt.value), "grant" to (grant != null),
            "bindingStart" to (request.raisedAt.value > p.binding.startedOrder), "previous" to (grant == null || request.raisedAt.value > grant.previous),
            "event" to (request.raisedAt.value > caller.order.value), "raisedAt" to (before == null || request.raisedAt.value > before.raisedAt.value),
            "intent" to (before == null || request.intent >= before.intent))
    }
    @Test fun A04_recheckIntent() {
        val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("caller-22", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(F.binding.startedOrder)!!,
            RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        val g = F.guard(F.auth.copy(authStopped = false), 60000); val r = F.request(intent = RefreshIntent.FORCE_PREMIUM, order = 4)
        val p = DemandAuthPlan.auth(g, r, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "unused-new-id")
        assertNull("fixture: plan prepared", p.preparationFailure)
        assertEquals("fixture: the stronger REQUEST is reused", RefreshIntent.FORCE_PREMIUM, demand(p.retryAfter)!!.intent)
        val (_, full) = F.apply(p, F.raw(g, r), F.runtime(caller = caller))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(recheckVec(p, full, caller)))
        assertTrue("positive twin: the candidate satisfies requiredEffects", F.transition.requiredEffects(p, F.read(full)))
        val weak = F.patch(full, "r") { JsonObject(it + ("intent" to JsonPrimitive(RefreshIntent.FORCE_ENTITLEMENTS.name))) }
        F.schema(weak)
        DemandAuthPlan::class.java.getDeclaredField("retryAfter").apply { isAccessible = true }.set(p, row(weak, "r"))
        assertEquals("fixture: the builder row agrees with the candidate", row(weak, "r").toPayloadEntry(), p.retryAfter!!.toPayloadEntry())
        assertEquals("fixture: the guard row is unchanged", row(full, "g").toPayloadEntry(), row(weak, "g").toPayloadEntry())
        assertEquals("truth vector: only callerRetry's intent differs", setOf("intent"), V.falses(recheckVec(p, weak, caller)))
        assertFalse(F.atomic("Z.cr.A04.recheck"), F.transition.requiredEffects(p, F.read(weak)))
    }
}
