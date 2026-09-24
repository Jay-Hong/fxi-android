package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fifty-sixth file — common lifecycle gates left OPEN (group ⑤):
 *  - direct ControlLifecycleBoundary (ControlLifecycle.kt): fence (:115–119) owner · user epoch · capability epoch;
 *    commandIdAvailable (:131–137) evidence command id; createIdAvailable (:139–142); preimage (:89–98) exists · single ·
 *    kind · interpreted · preimage — truth vector written here, twin with the literal result, one false, role.
 *  - writer connections (ControlLifecycleConfirmation.decide → DemandAuthTransition.decide): the TV writer gates ·
 *    eligibility · common premises name exactly the target item (command id free · CREATE target absent · target preimage ·
 *    required-unchanged preimage · runtime present · journal absent · guard creation), twin reaching Confirm.
 */
class DemandAuthBacklogContract56Test {
    private fun raw(vararg nodes: ControlNode) = F.raw(*nodes)
    private fun with(p: Preferences, block: androidx.datastore.preferences.core.MutablePreferences.() -> Unit): Preferences = p.toMutablePreferences().apply(block).toPreferences()

    // fence (direct)
    private fun fenceVec(r: Preferences, f: FenceV1) = listOf("owner" to (r[OWNER_UID] == f.ownerUid), "user" to (r[USER_EPOCH] == f.userAccessEpoch), "krx" to (r[KRX_EPOCH] == f.krxCapabilityEpoch))
    private fun fenceNo(id: String, only: String, f: FenceV1) {
        val r = raw()
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(fenceVec(r, F.fence)))
        assertTrue("positive twin: the raw fence matches", ControlLifecycleBoundary.fence(r, F.fence))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(fenceVec(r, f)))
        assertFalse(F.eligible(id), ControlLifecycleBoundary.fence(r, f))
    }
    @Test fun G04a_owner() = fenceNo("Z.lc.fence.owner", "owner", F.fence.copy(ownerUid = "B"))
    @Test fun G04b_user() = fenceNo("Z.lc.fence.user", "user", F.fence.copy(userAccessEpoch = "u2"))
    @Test fun G04c_capability() = fenceNo("Z.lc.fence.krx", "krx", F.fence.copy(krxCapabilityEpoch = "k2"))

    // commandIdAvailable / createIdAvailable (direct)
    private val prior = ControlLifecycleEvidenceFixtures.wire(command = "cmd-x")
    @Test fun G05a_commandId() {
        val free = F.read(raw()); val used = F.read(with(raw()) { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$prior]" })
        assertTrue("positive twin: an unused command id is available", ControlLifecycleBoundary.commandIdAvailable(free, "cmd-x"))
        assertEquals("truth vector: only the evidence id differs", listOf(false, true), listOf(
            (used.metadata as ControlMetadataRead.V2).evidence.entries.filterIsInstance<ControlEvidenceEntryRead.Interpreted>().none { it.value.commandId == "cmd-x" },
            used.arrays.getValue(ControlKind.SEAL).entries.isEmpty()))
        assertFalse(F.eligible("Z.lc.commandId"), ControlLifecycleBoundary.commandIdAvailable(used, "cmd-x"))
    }
    @Test fun G05c_createId() {
        assertTrue("positive twin: an absent id may be created", ControlLifecycleBoundary.createIdAvailable(F.read(raw()), "n"))
        val taken = F.read(raw(F.request(id = "n")))
        assertEquals("truth vector: the id is present", 1, taken.locations("n").size)
        assertFalse(F.eligible("Z.lc.createId"), ControlLifecycleBoundary.createIdAvailable(taken, "n"))
    }

    // preimage (direct)
    private val r = F.request()
    private val fixedR = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, r, F.request(order = 5))
    private fun preVec(read: ControlRecordRead.Supported): List<Pair<String, Boolean>> {
        val l = read.locations("r"); val single = l.size == 1
        val interp = single && l[0].second is ControlEntryRead.Interpreted
        return listOf("exists" to l.isNotEmpty(), "single" to (l.isEmpty() || single), "kind" to (!single || l[0].first == ControlKind.DEMAND),
            "interpreted" to (!single || l[0].first != ControlKind.DEMAND || interp),
            "preimage" to (!interp || l[0].first != ControlKind.DEMAND || (l[0].second as ControlEntryRead.Interpreted).original.toPayloadEntry() == r.toPayloadEntry()))
    }
    private fun preNo(id: String, only: String, read: ControlRecordRead.Supported) {
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(preVec(F.read(raw(r)))))
        assertNull("positive twin: the exact preimage is present", ControlLifecycleBoundary.preimage(F.read(raw(r)), fixedR))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(preVec(read)))
        assertNotNull(F.eligible(id), ControlLifecycleBoundary.preimage(read, fixedR))
    }
    private val opaqueR get() = F.node(buildJsonObject { put("id", "r"); put("kind", "REQUEST"); put("future", true) }.toString())
    private val holdR get() = ControlLifecycleEvidenceFixtures.raw(hold = "[" + ControlObligationFixtures.hold.replace("\"id\":\"h\"", "\"id\":\"r\"") + "]")
    @Test fun P_missing() = preNo("Z.lc.pre.missing", "exists", F.read(raw()))
    @Test fun P_collision() = preNo("Z.lc.pre.collision", "single", F.read(raw(r, F.request(order = 6))))
    @Test fun P_kind() = preNo("Z.lc.pre.kind", "kind", F.read(holdR))
    @Test fun P_uninterpreted() = preNo("Z.lc.pre.uninterpreted", "interpreted", F.read(raw(opaqueR)))
    @Test fun P_changed() = preNo("Z.lc.pre.changed", "preimage", F.read(raw(F.request(order = 6))))

    // writer connections — UPDATE_AUTH Answer (twin) unless stated
    private fun decide(plan: DemandAuthPlan, before: Preferences, ctx: AttemptContext): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), ctx, false, false)
    }
    private class Case(val plan: DemandAuthPlan, val raw: Preferences, val ctx: AttemptContext = F.context())
    private fun falses(k: Case) = V.falses(V.decideGates(k.plan, k.ctx, k.raw)) +
        (k.ctx.demandAuth?.let { V.falses(V.eligibility(k.plan, it, k.raw)) } ?: emptySet()) + V.falses(V.commonPremises(k.plan, k.raw, "command"))
    private fun check(id: String, only: String, twin: Case, bad: Case) {
        F.schema(twin.raw); assertNull("twin prepared", twin.plan.preparationFailure)
        assertEquals("truth vector: twin", emptySet<String>(), falses(twin))
        assertTrue("positive twin: reaches Confirm", decide(twin.plan, twin.raw, twin.ctx) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the target differs", setOf(only), falses(bad))
        assertFalse(F.eligible(id), decide(bad.plan, bad.raw, bad.ctx) is RecordTransactionDecision.Confirm)
    }
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private fun answer(raw: Preferences = raw(F.guard()), ctx: AttemptContext = F.context(), retry: ControlNode? = null) =
        Case(F.plan(pending, F.guard(), retry), raw, ctx)
    @Test fun W_runtimeMissing() = check("Z.lc.runtime", "dt.runtime", answer(), answer(ctx = F.context().copy(demandAuth = null)))
    @Test fun W_journal() = check("Z.lc.journal", "dt.journalAbsent", answer(), answer(raw = with(raw(F.guard())) { this[PURGE_JOURNAL] = "unknown-format" }))
    @Test fun W_commandIdUsed() = check("Z.lc.commandIdUsed", "lc.commandIdFree", answer(), answer(raw = with(raw(F.guard())) {
        this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[" + ControlLifecycleEvidenceFixtures.wire(command = "command") + "]" }))
    @Test fun W_createIdTaken() = check("Z.lc.createIdTaken", "target[r-new].absent", answer(), answer(raw = raw(F.guard(), F.request(id = "r-new", owner = "B"))))
    @Test fun W_targetPreimage() = check("Z.lc.targetPreimage", "target[g].preimage", answer(), answer(raw = raw(F.guard(F.auth.copy(authStateOrder = 11)))))
    @Test fun W_unchangedPreimage() = check("Z.lc.unchangedPreimage", "unchanged[r].preimage",
        answer(raw = raw(F.guard(), F.request()), retry = F.request()), answer(raw = raw(F.guard(), F.request(order = 6)), retry = F.request()))
    // guard creation: UPDATE_AUTH Initialize creates a guard while another guard exists
    private fun init(extra: List<ControlNode>) = Case(DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new"), raw(*extra.toTypedArray()))
    @Test fun W_guardCreate() = check("Z.lc.guardCreate", "dt.guardCreate", init(emptyList()), init(listOf(F.guard(auth = null, id = "x"))))
}
