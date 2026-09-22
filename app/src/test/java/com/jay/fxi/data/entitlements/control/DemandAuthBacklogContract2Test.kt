package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, second file: obligations added by review r1 (`5e1_contract_review_codex.log`). Expected values come
 * from the design sentences cited on each test, never from the builder/validator calculations under test.
 * Premise assertions use plain messages; their failure is never a target's kill.
 *
 * P_grantNamespace is expected to FAIL on a1e79961: it reproduces a defect (probe-grant-namespace/). The
 * implementation step must make it pass without changing this file.
 */
class DemandAuthBacklogContract2Test {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { controlTestTimeout("5e-1 cleanup", 30000) { opened.reversed().forEach { it.close() } } }

    private val other = LifetimeId("other")
    private val external = byteArrayPreferencesKey("lifecycle-external")
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private val orders get() = LifecycleOrderSource(F.life, 21)

    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime = F.runtime(),
        codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        val read = ControlRecordReader(codec).read(before) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, input, read, F.context(runtime), false, false)
    }
    private fun candidate(d: RecordTransactionDecision<*>): Preferences = (d as RecordTransactionDecision.Confirm<*>).candidate
    private fun requests(raw: Preferences) = F.read(raw).arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().mapNotNull { it.value as? DemandV1 }.associateBy { it.id }
    private fun guardAuth(raw: Preferences) = F.read(raw).arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().mapNotNull { it.value as? ScheduleGuardV1 }.singleOrNull()?.auth
    private fun initializePlan() =
        DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")

    // P.grantNamespace — §3.1 126–127 (each fixed fact once), §4.5 306–308, §5.1 322–323.

    /**
     * The AUTH stop grant and a strengthened REQUEST's grant are distinct fixed facts. Ids are any nonempty text
     * (ControlSchema.kt:21–22), so a REQUEST named "auth" must behave like any other id.
     */
    private fun grantNamespace(testId: String, requestId: String) {
        val d = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION),
            followUp = RefreshIntent.FORCE_PREMIUM)
        val g = F.guard(); val r = F.request(id = requestId, intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val p = F.plan(d, g, r)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val result = decide(p, F.raw(g, r))
        assertTrue(F.atomic(testId), result is RecordTransactionDecision.Confirm)
        val landed = candidate(result)
        val auth = guardAuth(landed)
        val request = requests(landed)[requestId]
        // §5.1 322: stopped, state = query start, stopApplied > max(old stopApplied 20, start 21).
        assertTrue(F.atomic(testId), auth != null && auth.authStopped && auth.authStateOrder == 21L && auth.authStopAppliedOrder > 21)
        // §4.3 282 / §4.5 306: the REQUEST is strengthened with its own fresh order after the query start.
        assertTrue(F.atomic(testId), request != null && request.intent == RefreshIntent.FORCE_PREMIUM && request.raisedAt.value > 21)
        // Two issuances from one sequence are two values.
        assertNotEquals(F.atomic(testId), auth?.authStopAppliedOrder, request?.raisedAt?.value)
    }
    /** Control: an ordinary REQUEST id. Passes on a1e79961. */
    @Test fun P_grantNamespace_control() = grantNamespace("P.grantNamespace.control", "r")
    /** The defect: a REQUEST named "auth". Fails on a1e79961; must pass after the implementation step. */
    @Test fun P_grantNamespace() = grantNamespace("P.grantNamespace", "auth")

    // G.guardCreate widening (M4) — §3.1 135 forbids a competing CREATE, not the replacement of the one guard.

    @Test fun G_guardCreate_replace() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        assertNotNull("fixture: the plan replaces an existing guard", p.guardBefore)
        assertTrue(F.atomic("G.guardCreate.replace"), decide(p, F.raw(g, r)) is RecordTransactionDecision.Confirm)
    }

    // H — §4.4 290–302, §4.5 304–312.

    /** §4.4 292, §3.2 158: several REQUESTs each keep id, owner and at least their intent, in the fixed input order. */
    @Test fun H_multiple() {
        val r1 = F.request(id = "r1", binding = 2)
        val r2 = F.request(id = "r2", origin = LifetimeId("old"), intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 50)
        val dormant = F.request(id = "dormant", owner = "B")
        val p = DemandAuthPlan.rebind(listOf(r1, r2), F.binding, orders)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals(F.atomic("H.multiple"), listOf("r1", "r2"), p.targets.map { it.target.id })
        val result = decide(p, F.raw(r1, dormant, r2))
        assertTrue(F.atomic("H.multiple"), result is RecordTransactionDecision.Confirm)
        val landed = requests(candidate(result))
        assertEquals(F.atomic("H.multiple"), setOf("r1", "r2", "dormant"), landed.keys)
        assertEquals(F.atomic("H.multiple"), demand(dormant), landed["dormant"])
        for (old in listOf(demand(r1)!!, demand(r2)!!)) {
            val now = landed.getValue(old.id)
            assertEquals(F.atomic("H.multiple"), old.ownerUid, now.ownerUid)
            assertEquals(F.atomic("H.multiple"), EventOrderV1(F.life, now.raisedAt.value), now.raisedAt)
            assertEquals(F.atomic("H.multiple"), 3L, now.binding)
            assertTrue(F.atomic("H.multiple"), now.intent >= old.intent)
        }
        // §4.5 306: each fresh order follows the previous issue of the same sequence.
        assertTrue(F.atomic("H.multiple"), landed.getValue("r2").raisedAt.value > landed.getValue("r1").raisedAt.value)
    }

    /** §4.5 306: a same-origin handover issues after the old REQUEST's order. */
    @Test fun H_planLower_sameOrigin() {
        val p = DemandAuthPlan.rebind(listOf(F.request(binding = 2, order = 50)), F.binding, orders)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertTrue(F.atomic("H.planLower.sameOrigin"), p.grants.getValue("r").value > 50)
    }

    /** §4.4 297, §4.5 307: another origin's old number is not compared (source last 21 < 50). */
    @Test fun H_planLower_otherOrigin() {
        val p = DemandAuthPlan.rebind(listOf(F.request(binding = 2, origin = LifetimeId("old"), order = 50)), F.binding, orders)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertTrue(F.atomic("H.planLower.otherOrigin"), p.grants.getValue("r").value < 50)
    }

    /** Widening of DT:105–106 (H.lower PLAN(+)): the writer must also not compare another origin's number. */
    @Test fun H_lower_otherOrigin() {
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = 50)
        val p = DemandAuthPlan.rebind(listOf(r), F.binding, orders)
        assertTrue(F.atomic("H.lower.otherOrigin"), decide(p, F.raw(r)) is RecordTransactionDecision.Confirm)
    }

    // S — §4.1 237, §4.2 247 (sources that are not a fresh registered query).

    private fun acceptance(d: AcceptedQueryDecision, rt: DemandAuthRuntime) = listOf(
        "source" to (d.source == LifecycleQuerySource.REGISTERED_QUERY),
        "registered" to (d.registration in rt.registrations),
        "answered" to (d.answeredAs != null),
        "beforeGeneration" to (d.acceptedBeforeGeneration == d.query.generation),
        "beforeFence" to (d.acceptedBeforeFence == d.query.fence),
        "answeredOwner" to (d.answeredAs?.ownerUid == d.query.fence.ownerUid),
        "answeredLive" to (d.answeredAs == rt.liveIdentity),
        "answeredBound" to (d.answeredAs == d.query.boundIdentity))
    private fun sourceNo(id: String, source: LifecycleQuerySource) {
        val rt = F.runtime()
        assertEquals("positive twin truth vector", emptySet<String>(), V.falses(acceptance(F.decision(), rt)))
        assertTrue("positive twin must be accepted", DemandAuthBoundary.acceptance(F.decision(), rt))
        val d = F.decision(source = source)
        assertEquals("truth vector", setOf("source"), V.falses(acceptance(d, rt)))
        assertFalse(F.eligible(id), DemandAuthBoundary.acceptance(d, rt))
    }
    @Test fun S_sourceKinds_credential() = sourceNo("S.sourceKinds.credential", LifecycleQuerySource.CREDENTIAL)
    @Test fun S_sourceKinds_timer() = sourceNo("S.sourceKinds.timer", LifecycleQuerySource.TIMER)

    /** §4.2 253: an effect whose node cannot be parsed cannot be exactly linked; the effect is not skipped. */
    @Test fun S_effectsParse() {
        val e = F.request(id = "e")
        val current = F.read(F.raw(e))
        val proof = ConfirmedControlSnapshot(F.read(F.raw(e)))
        assertTrue("positive twin", F.transition.durableEffects(
            F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, proof))), current))
        val unparsable = F.node(e.toPayloadEntry().fields.toString().replace("\"intent\":\"FORCE_PREMIUM\"", "\"intent\":\"SOMETIMES\""))
        val d = F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, unparsable, proof)))
        F.onlyFalse(ControlSchema.read(ControlKind.DEMAND, unparsable) != null, d.effects.single().confirmation != null,
            d.confirmedAfterFence == d.acceptedBeforeFence)
        assertFalse(F.eligible("S.effectsParse"), F.transition.durableEffects(d, current))
    }

    /** §4.2 253: the proof must contain the row; an absent row is not "exactly linked". */
    @Test fun S_exactRow_absent() {
        val e = F.request(id = "e")
        val current = F.read(F.raw(e))
        assertTrue("positive twin", F.transition.durableEffects(
            F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, ConfirmedControlSnapshot(F.read(F.raw(e)))))), current))
        val emptyProof = F.read(F.raw())
        val d = F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, ConfirmedControlSnapshot(emptyProof))))
        F.onlyFalse(emptyProof.locations("e").isNotEmpty(), V.exactRow(current, ControlKind.DEMAND, e),
            ControlSchema.read(ControlKind.DEMAND, e) != null, d.confirmedAfterFence == d.acceptedBeforeFence)
        assertFalse(F.eligible("S.exactRow.absent"), F.transition.durableEffects(d, F.read(F.raw(e))))
    }

    /**
     * §4.2 253: a proof row made uninterpretable by its record (two guards, ControlObligations.kt:186–191) is not an
     * exact link even though its id, kind and payload match. The proof record has no LC:181 gate.
     */
    @Test fun S_exactRow_interpreted() {
        val g1 = F.guard(auth = null, wait = 1000, id = "g1"); val g2 = F.guard(auth = null, wait = 2000, id = "g2")
        val proof = F.read(F.raw(g1, g2))
        val hit = proof.locations("g1").single()
        F.onlyFalse(hit.second is ControlEntryRead.Interpreted, hit.first == ControlKind.DEMAND,
            (hit.second as? ControlEntryRead.Uninterpretable)?.original == g1.toPayloadEntry(), V.exactRow(F.read(F.raw(g1)), ControlKind.DEMAND, g1))
        val d = F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, g1, ConfirmedControlSnapshot(proof))))
        assertFalse(F.eligible("S.exactRow.interpreted"), F.transition.durableEffects(d, F.read(F.raw(g1))))
    }

    // F.mergeInputs — §5.3 373–374, §6.1 386: one tuple = max(existing remaining, stated remaining, decision minDelay).

    private fun anchoredGuard(wait: Long, boot: String = "boot", elapsed: Long = 10000) = F.node(
        F.guard(wait = wait).toPayloadEntry().fields.toString()
            .replace("\"anchorBootId\":\"boot\"", "\"anchorBootId\":\"$boot\"")
            .replace("\"anchorElapsedMillis\":10000", "\"anchorElapsedMillis\":$elapsed"))
    private fun mergedFloor(guard: ControlNode, minDelay: Long = 0): FloorV1? {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), minDelay = minDelay), guard, F.request())
        assertNull("fixture plan must be prepared", p.preparationFailure)
        return guard(p.guardAfter)?.floor
    }
    private val fresh get() = FloorV1("boot", 10000, 30000, F.life)

    @Test fun F_mergeInputs_agedStated() {
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30),
            capture = BootReading("boot", 5000), merge = BootReading("boot", 10000))
        val p = F.plan(d, F.guard(), F.request())
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals(F.atomic("F.mergeInputs.agedStated"),
            FloorV1("boot", 10000, 25000, F.life), guard(p.guardAfter)?.floor)
    }

    @Test fun F_mergeInputs_agedExisting() {
        val g = anchoredGuard(30000, elapsed = 5000)
        assertEquals(F.atomic("F.mergeInputs.agedExisting"), fresh, mergedFloor(g))
    }

    @Test fun F_mergeInputs_existingLarger() {
        val g = anchoredGuard(60000)
        assertEquals(F.atomic("F.mergeInputs.existingLarger"), guard(g)!!.floor, mergedFloor(g))
    }
    @Test fun F_mergeInputs_statedLarger() =
        assertEquals(F.atomic("F.mergeInputs.statedLarger"), fresh, mergedFloor(anchoredGuard(10000)))
    @Test fun F_mergeInputs_delayLarger() =
        assertEquals(F.atomic("F.mergeInputs.delayLarger"), fresh.copy(waitMillis = 90000), mergedFloor(anchoredGuard(10000), minDelay = 90000))
    /** §6.1 386: a different boot counts the full wait, so a long enough existing floor is preserved. */
    @Test fun F_mergeInputs_otherBootPreserved() {
        val g = anchoredGuard(60000, boot = "old-boot")
        assertEquals(F.atomic("F.mergeInputs.otherBootPreserved"), guard(g)!!.floor, mergedFloor(g))
    }
    /** §6.1 386: a regressed elapsed reading counts the full wait. */
    @Test fun F_mergeInputs_regressedPreserved() {
        val g = anchoredGuard(60000, elapsed = 20000)
        assertEquals(F.atomic("F.mergeInputs.regressedPreserved"), guard(g)!!.floor, mergedFloor(g))
    }
    /** §6.1 386 with §5.3 374: the full wait of another boot still loses to a larger requirement. */
    @Test fun F_mergeInputs_otherBootShort() =
        assertEquals(F.atomic("F.mergeInputs.otherBootShort"), fresh, mergedFloor(anchoredGuard(10000, boot = "old-boot")))

    // V — §3.1 139 latest snapshot, §9.4 C12 envelopes, and the validator's re-read.

    /** §3.1 139: the candidate is built from the latest snapshot; an unrelated row and an external key keep their latest values. */
    @Test fun V_latest() {
        val g = F.guard(); val r = F.request()
        val p = F.plan(F.decision(), g, retry = null, settle = true, removes = listOf(r))
        val xPrepared = F.request(id = "x", owner = "B", binding = 1)
        val xLatest = F.request(id = "x", owner = "B", binding = 1, intent = RefreshIntent.IF_STALE)
        F.schema(F.raw(g, r, xPrepared))
        val latest = F.raw(g, r, xLatest).toMutablePreferences().apply { this[external] = byteArrayOf(7, 7, 7) }.toPreferences()
        assertNotEquals("fixture: the unrelated row changed after preparation", xPrepared.toPayloadEntry(), xLatest.toPayloadEntry())
        val result = decide(p, latest)
        assertTrue(F.atomic("V.latest"), result is RecordTransactionDecision.Confirm)
        val after = candidate(result)
        assertArrayEquals(F.atomic("V.latest"), byteArrayOf(7, 7, 7), after[external])
        val x = F.read(after).locations("x").single().second as ControlEntryRead.Interpreted
        assertEquals(F.atomic("V.latest"), xLatest.toPayloadEntry(), x.original.toPayloadEntry())
    }

    /** §3.1 139: a candidate the reader cannot read back as Supported is not valid. */
    @Test fun V_reread() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val c = F.command(p); val input = (c.body as ControlCommandBody.Lifecycle).input
        val before = F.raw(g, r)
        val positive = ControlLifecycleConfirmation(F.codec).decide(
            c, input, F.read(before), F.context(), false, false)
        assertTrue("positive twin must reach Confirm", positive is RecordTransactionDecision.Confirm)
        val full = candidate(positive)
        assertTrue("positive twin must be a valid candidate",
            F.transition.validCandidate(c, input, F.read(before), full))
        val broken = full.toMutablePreferences().apply { this[demandKey] = "[" }.toPreferences()
        assertFalse("fixture: the candidate cannot be read back", ControlRecordReader().read(broken) is ControlRecordRead.Supported)
        assertFalse(F.atomic("V.reread"), F.transition.validCandidate(c, input, F.read(before), broken))
    }

    private fun bytes(raw: Preferences, key: Preferences.Key<String>) = raw[key]!!.toByteArray(Charsets.UTF_8).size

    /** §9.4 C12: the changed DEMAND payload is checked against the limit on its own. */
    @Test fun V_envelopes_demand() {
        val pads = (1..12).map { F.request(id = "pad-$it", owner = "B", binding = 1) }
        val before = F.raw(*pads.toTypedArray())
        val p = initializePlan()
        val reference = candidate(decide(p, before))
        val limit = (bytes(before, demandKey) + bytes(reference, demandKey)) / 2
        assertTrue("fixture: DEMAND crosses the limit only after the change", bytes(before, demandKey) <= limit && bytes(reference, demandKey) > limit)
        assertTrue("fixture: the evidence payload stays within the limit", bytes(reference, evidenceKey) <= limit)
        assertFalse(F.atomic("V.envelopes.demand"), decide(p, before, codec = ControlPayloadCodec(maxPayloadBytes = limit)) is RecordTransactionDecision.Confirm)
    }

    /** §9.4 C12: the self Applied row is checked against the evidence payload's own limit. */
    @Test fun V_envelopes_evidence() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val before = F.raw().toMutablePreferences().apply { this[evidenceKey] = JsonArray(rows).toString() }.toPreferences()
        F.schema(before)
        val p = initializePlan()
        val reference = candidate(decide(p, before))
        val limit = (bytes(before, evidenceKey) + bytes(reference, evidenceKey)) / 2
        assertTrue("fixture: evidence crosses the limit only after the change", bytes(before, evidenceKey) <= limit && bytes(reference, evidenceKey) > limit)
        assertTrue("fixture: the DEMAND payload stays within the limit", bytes(reference, demandKey) <= limit)
        assertFalse(F.atomic("V.envelopes.evidence"), decide(p, before, codec = ControlPayloadCodec(maxPayloadBytes = limit)) is RecordTransactionDecision.Confirm)
    }

    // F.callerRetry — §5.1 325 at the requiredEffects boundary (validCandidate as a whole would also fail DT:251).

    @Test fun F_callerRetry() {
        val g = F.guard(wait = 30000); val old = F.request()
        val c = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21),
            RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        val p = DemandAuthPlan.auth(g, old, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
        val (_, landed) = F.apply(p, F.raw(g, old), F.runtime(caller = c))
        assertTrue("positive twin", F.transition.requiredEffects(p, F.read(landed)))
        val tampered = F.patch(landed, "r") { JsonObject(it + ("intent" to JsonPrimitive(RefreshIntent.FORCE_ENTITLEMENTS.name))) }
        val after = F.read(tampered)
        val req = (after.locations("r").single().second as ControlEntryRead.Interpreted).value as DemandV1
        val expected = demand(p.retryAfter)!!; val before = demand(old)!!; val grant = p.grants.getValue("r"); val x = F.binding.executor
        F.onlyFalse(req.intent >= c.intent, req.ownerUid == x.ownerUid, req.binding == x.binding,
            req.raisedAt.origin == x.originLifetimeId, req.raisedAt.value == expected.raisedAt.value,
            req.raisedAt.value > F.binding.startedOrder, req.raisedAt.value > grant.previous, req.raisedAt.value > c.order.value,
            req.raisedAt.value > before.raisedAt.value, req.intent >= before.intent,
            guardAuth(tampered) == F.auth.copy(authStopped = false, authStateOrder = 21))
        assertFalse(F.atomic("F.callerRetry"), F.transition.requiredEffects(p, after))
    }

    // Z.bytes — design 683 "외부 ByteArray": each named writer preserves an existing external ByteArray on disk.

    private fun externalKept(id: String, source: Preferences, runtime: DemandAuthRuntime, prepare: (ControlRecordStore) -> CommandRef) = runBlocking {
        assertArrayEquals("fixture: seeded external bytes", byteArrayOf(0, 1, -1), source[external])
        val file = folder.newFile(); val store = ControlStoreTestStorage(file).also { opened += it }
        controlTestTimeout("seed 5e-1") { store.data.updateData { source } }
        val c = prepare(store.control)
        val result = controlTestTimeout("5e-1 writer") { store.control.execute(c, F.context(runtime)) }
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        val disk = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
        assertArrayEquals(F.atomic(id), byteArrayOf(0, 1, -1), disk[external])
    }
    @Test fun Z_bytes_rebind() {
        val r = F.request(binding = 2)
        externalKept("Z.bytes.rebind", F.raw(r), F.runtime()) { it.prepareRebindRequests(listOf(r), F.binding, orders) }
    }
    @Test fun Z_bytes_settle() {
        val g = F.guard(); val r = F.request()
        externalKept("Z.bytes.settle", F.raw(g, r), F.runtime()) { it.prepareSettleQuery(listOf(r), g, null, F.binding, F.decision(), orders) }
    }
    @Test fun Z_bytes_auth() {
        val g = F.guard()
        externalKept("Z.bytes.auth", F.raw(g), F.runtime()) {
            it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30))), orders)
        }
    }
    @Test fun Z_bytes_end() {
        val old = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
        val g = F.guard(auth = old); val c = LifecycleBindingClosure(old, true, setOf("w"), setOf("w"), 5)
        externalKept("Z.bytes.end", F.raw(g), F.runtime(closure = c)) { it.prepareEndAuthBinding(g, emptyList(), F.binding, c, F.binding, orders) }
    }
}
