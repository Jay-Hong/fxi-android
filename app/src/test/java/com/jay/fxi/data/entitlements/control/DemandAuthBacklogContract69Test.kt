package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
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
 * 5e-1 contract, sixty-ninth file — units re-judgment r3 kept OPEN, each on the **original unit's fixture**:
 *  - A10b · A10c (DemandAuthGenericTest): Add.prepare of a guard with a stopped AUTH / an AUTH plus a floor is not built.
 *    Vector over the literal node (the builder's output asserted equal to the literal as a premise).
 *  - G05a · G05c (ControlLifecycleEvidenceBoundaryTest): commandIdAvailable with evidence command "lc"; createIdAvailable
 *    for "h" held by a HOLD row — twin = the empty record, with its full vector.
 *  - Q16b · Q16c writer (DemandAuthIntegrationTest): the store with the original records; full writer premises of the twin
 *    plan on the same record, the only other input is the exhausted source.
 *  - G.context · T.runtimeMissing.class (ContractTest / C21): the Answer plan F.plan(guard, retry) with no DemandAuth
 *    runtime; role as the original C21 (no false success: neither Confirm nor a Positive outcome); classification separate.
 *  - S.exactRow.interpreted (C2): the proof record holds two guards, so the proof row of g1 is read as uninterpretable.
 *  - N.auth (ContractTest): END on a guard without AUTH, replacement F.binding — writer vector names only `end.auth`.
 */
class DemandAuthBacklogContract69Test {
    // A10b · A10c — Add.prepare
    private fun withId(node: ControlNode, id: String) = JsonObject(node.toPayloadEntry().fields + ("id" to JsonPrimitive(id))).toString()
    private fun addVec(literalNode: ControlNode, issued: String): List<Pair<String, Boolean>> {
        val v = ControlSchema.read(ControlKind.DEMAND, literalNode)
        return listOf("parses" to (v != null), "idMatches" to (v == null || v.id == issued),
            "allowed" to (v == null || !(v is ScheduleGuardV1 && "auth" in literalNode.names)))
    }
    private fun addNo(id: String, node: ControlNode) {
        val issued = UUID.randomUUID(); val iss = issued.toString()
        val twinRaw = withId(F.guard(auth = null, wait = 1000), iss); val raw = withId(node, iss)
        for (r in listOf(twinRaw, raw)) assertEquals("premise: the builder writes the literal", F.node(r).toPayloadEntry(),
            (ControlObligations.build(ControlKind.DEMAND) { literal(r) } as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(addVec(F.node(twinRaw), iss)))
        assertTrue("positive twin: a guard without AUTH is built", ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(twinRaw) }.built is ControlWriteResult.Written)
        assertEquals("truth vector: only the guard's AUTH differs", setOf("allowed"), V.falses(addVec(F.node(raw), iss)))
        assertFalse(F.eligible(id), ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }.built is ControlWriteResult.Written)
    }
    @Test fun A10b_add() = addNo("Z.gen.A10b.add", F.guard())
    @Test fun A10c_add() = addNo("Z.gen.A10c.add", F.guard(wait = 60000))

    // G05a · G05c — direct, original records
    private val E = ControlLifecycleEvidenceFixtures
    private fun idVec(read: ControlRecordRead.Supported, id: String) = listOf(
        "evidenceFree" to (read.metadata as ControlMetadataRead.V2).evidence.entries.filterIsInstance<ControlEvidenceEntryRead.Interpreted>().none { it.value.commandId == id },
        "sealFree" to read.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>().none { (it.value as SealV1).settlement?.operationId == id })
    @Test fun G05a_lc() {
        val free = E.read(E.raw()); val used = E.read(E.raw(evidence = "[${E.wire(command = "lc")} ]"))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(idVec(free, "lc")))
        assertTrue("positive twin: an unused command id is available", ControlLifecycleBoundary.commandIdAvailable(free, "lc"))
        assertEquals("truth vector: only the evidence id differs", setOf("evidenceFree"), V.falses(idVec(used, "lc")))
        assertFalse(F.eligible("Z.lc.G05a"), ControlLifecycleBoundary.commandIdAvailable(used, "lc"))
    }
    @Test fun G05c_hold() {
        val free = E.read(E.raw()); val taken = E.read(E.raw(hold = "[${ControlObligationFixtures.hold}]"))
        fun vec(r: ControlRecordRead.Supported) = listOf("absent" to r.locations("h").isEmpty())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(vec(free)))
        assertTrue("positive twin: an absent id may be created", ControlLifecycleBoundary.createIdAvailable(free, "h"))
        assertEquals("fixture: the id is held by a HOLD row", ControlKind.HOLD, taken.locations("h").single().first)
        assertEquals("truth vector: only the HOLD row differs", setOf("absent"), V.falses(vec(taken)))
        assertFalse(F.eligible("Z.lc.G05c"), ControlLifecycleBoundary.createIdAvailable(taken, "h"))
    }

    // Q16b · Q16c writer — original records
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c69 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c69 cleanup", 30000) { opened.forEach { it.close() } } }
    private val max = Long.MAX_VALUE
    private fun writerFalses(c: CommandRef, raw: Preferences, ctx: AttemptContext): Set<String> {
        val p = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.demandAuth)
        assertNull("twin plan prepared", p.preparationFailure)
        return V.falses(V.decideGates(p, ctx, raw)) + V.falses(V.eligibility(p, ctx.demandAuth!!, raw)) + V.falses(V.commonPremises(p, raw, c.id))
    }
    private fun issueVec(last: Long, start: Long, after: Long) = listOf("notExhausted" to (maxOf(last, start, after) != max))
    @Test fun Q16b_storeOrig() = runReleaseTest {
        val r = F.request(binding = 2); val raw = F.raw(r); val ctx = F.context()
        val a = store(raw)
        val twin = a.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, 21))
        assertEquals("truth vector: twin writer premises on the stored record", emptySet<String>(), writerFalses(twin, raw, ctx))
        assertEquals("truth vector: twin source issuable", emptySet<String>(), V.falses(issueVec(21, 1, 4)))
        assertTrue("positive twin: a fresh source re-binds", a.control.execute(twin, ctx) is ControlStoreResult.Confirmed)
        val o = store(raw); val saved = o.raw()
        assertEquals("truth vector: only the source differs (exhausted)", setOf("notExhausted"), V.falses(issueVec(max, 1, 4)))
        val res = o.control.execute(o.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, max)), ctx)
        assertTrue(F.eligible("Z.st.Q16b.orig"), res !is ControlStoreResult.Confirmed && o.raw() == saved)
    }
    @Test fun Q16c_storeOrig() = runReleaseTest {
        val r = F.request(); val g = F.guard(); val raw = F.raw(r, g); val ctx = F.context()
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
        val a = store(raw)
        val twin = a.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, LifecycleOrderSource(F.life, 21))
        assertEquals("truth vector: twin writer premises on the stored record", emptySet<String>(), writerFalses(twin, raw, ctx))
        assertEquals("truth vector: twin source issuable", emptySet<String>(), V.falses(issueVec(21, 1, 21)))
        assertTrue("positive twin: a fresh source settles", a.control.execute(twin, ctx) is ControlStoreResult.Confirmed)
        val o = store(raw); val saved = o.raw()
        assertEquals("truth vector: only the source differs (exhausted)", setOf("notExhausted"), V.falses(issueVec(max, 1, 21)))
        val res = o.control.execute(o.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, LifecycleOrderSource(F.life, max)), ctx)
        assertTrue(F.eligible("Z.st.Q16c.orig"), res !is ControlStoreResult.Confirmed && o.raw() == saved)
    }

    // G.context · T.runtimeMissing — the Answer plan of the original units
    private fun decide(p: DemandAuthPlan, raw: Preferences, ctx: AttemptContext): RecordTransactionDecision<*> {
        val c = F.command(p)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), ctx, false, false)
    }
    private fun noFalseSuccess(d: RecordTransactionDecision<*>) = d is RecordTransactionDecision.Confirm || d.value is ControlRecordStore.Outcome.Positive
    private fun negative(d: RecordTransactionDecision<*>) = ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun falses(p: DemandAuthPlan, raw: Preferences, ctx: AttemptContext) = V.falses(V.decideGates(p, ctx, raw)) +
        V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command"))
    private val g = F.guard(); private val r = F.request()
    private fun answerPlan() = F.plan(guard = g, retry = r)
    private val noRuntime get() = F.context().copy(demandAuth = null)
    @Test fun T_runtimeMissing_answer() {
        val p = answerPlan(); val raw = F.raw(g, r); F.schema(raw)
        assertNull("prepared", p.preparationFailure)
        assertEquals("truth vector: twin", emptySet<String>(), falses(p, raw, F.context()))
        assertTrue("positive twin: the attempt runtime confirms", decide(p, raw, F.context()) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the runtime is missing", setOf("dt.runtime"), falses(p, raw, noRuntime))
        assertFalse(F.eligible("Z.dt.runtimeAnswer"), noFalseSuccess(decide(p, raw, noRuntime)))
    }
    @Test fun T_runtimeMissing_answer_classification() {
        val p = answerPlan(); val raw = F.raw(g, r)
        assertEquals("premise: only the runtime is missing", setOf("dt.runtime"), falses(p, raw, noRuntime))
        val result = negative(decide(p, raw, noRuntime))
        assertTrue("classification: a missing runtime is an invalid request", result is ControlStoreResult.Rejected &&
            result.reason == RejectionReason.InvalidRequest("AttemptContextRequired"))
    }

    // S.exactRow.interpreted — the proof row read as uninterpretable at record level (two guards)
    @Test fun S_exactRow_interpretedOrig() {
        val g1 = F.guard(auth = null, wait = 1000, id = "g1"); val g2 = F.guard(auth = null, wait = 2000, id = "g2")
        val current = F.read(F.raw(g1))
        fun vec(proof: ControlRecordRead.Supported): List<Pair<String, Boolean>> {
            val l = proof.locations("g1"); val single = l.size == 1
            return listOf("single" to single, "kind" to (!single || l[0].first == ControlKind.DEMAND),
                "interpreted" to (!single || l[0].second is ControlEntryRead.Interpreted),
                "payload" to (!single || l[0].second !is ControlEntryRead.Interpreted || (l[0].second as ControlEntryRead.Interpreted).original.toPayloadEntry() == g1.toPayloadEntry()),
                "currentExact" to V.exactRow(current, ControlKind.DEMAND, g1))
        }
        fun d(proof: ControlRecordRead.Supported) = F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, g1, ConfirmedControlSnapshot(proof))))
        val twinProof = F.read(F.raw(g1)); val badProof = F.read(F.raw(g1, g2))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(vec(twinProof)))
        assertTrue("positive twin: the interpretable proof row is durable", F.transition.durableEffects(d(twinProof), current))
        assertEquals("fixture: the uninterpretable proof row keeps the payload", g1.toPayloadEntry(), (badProof.locations("g1").single().second as ControlEntryRead.Uninterpretable).original)
        assertEquals("truth vector: only interpretation differs", setOf("interpreted"), V.falses(vec(badProof)))
        assertFalse(F.eligible("Z.de.exactRow.interpreted"), F.transition.durableEffects(d(badProof), current))
    }

    // N.auth — END on a guard without AUTH (original fixture)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private fun closure() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    private fun endPlan(guardNode: ControlNode) = DemandAuthPlan.end(guardNode, emptyList(), F.binding, closure(), F.binding, LifecycleOrderSource(F.life, 21))
    @Test fun N_auth_writer() {
        val c = closure(); val rt = F.runtime(closure = c); val ctx = F.context(rt)
        val gOld = F.guard(auth = oldAuth, wait = 30000); val twin = endPlan(gOld); val twinRaw = F.raw(gOld); F.schema(twinRaw)
        assertNull("twin prepared", twin.preparationFailure)
        fun vec(p: DemandAuthPlan, raw: Preferences) = V.falses(V.decideGates(p, ctx, raw)) + V.falses(V.eligibility(p, rt, raw)) + V.falses(V.commonPremises(p, raw, "command"))
        assertEquals("truth vector: twin", emptySet<String>(), vec(twin, twinRaw))
        assertTrue("positive twin: END on the closed old AUTH confirms", decide(twin, twinRaw, ctx) is RecordTransactionDecision.Confirm)
        val gNo = F.guard(auth = null, wait = 30000); val bad = endPlan(gNo); val badRaw = F.raw(gNo); F.schema(badRaw)
        assertNull("prepared", bad.preparationFailure)
        assertEquals("truth vector: only the guard's AUTH is missing", setOf("end.auth"), vec(bad, badRaw))
        assertFalse(F.eligible("Z.dt.endAuth"), decide(bad, badRaw, ctx) is RecordTransactionDecision.Confirm)
    }
}
