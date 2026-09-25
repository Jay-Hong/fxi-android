package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 5e-2 DEFENSE contract (measure plan v1 M1/M4; analysis r2 table 5; design §9.4 "builder effect
 * omission ↔ final validator bypass"), modelled on 5e-1 DemandAuthBacklogContract72Test:
 * writer · event → exact builder defect → actual refusal boundary → stored record unchanged.
 * Four RECOVER_INTENT events run through the real store (C0a CAPABILITY, C0a USER, C0c different target, C0d departed).
 * Per event:
 *  - `<e>_confirms` (KILL companion): the unaltered writer reaches Confirmed — a refused builder defect fails here.
 *  - three DEFENSE methods, vacuous when the store confirms and otherwise naming one boundary exactly:
 *    `<e>_refusedAtPreparation` — the plan's preparationProblem is non-null and is the returned result; unchanged;
 *    `<e>_refusedAtDescriptor`  — no preparation problem, Rejected InvalidRequest(InvalidLifecycleDescriptor); unchanged;
 *    `<e>_refusedAtValidator`   — no preparation problem, Rejected InvalidRequest(RequiredDecisionEffectMissing)
 *                                  (returned only at RecoverIntentTransition RI.candidateCall); unchanged.
 * Two over-limit events (the C12 builder paths, UTF-8 65,537 bytes) are never confirmed:
 *  - `<e>_refusedAtBuild` fixes the builder's own TooLarge reason — CLASSIFICATION_ONLY (the record stays unchanged either way);
 *  - `<e>_refusedBeforeStorage` is the DEFENSE: refused at the builder (TooLarge) or, when the builder ignores its
 *    failure, at the validator (RequiredDecisionEffectMissing); unchanged.
 * A measured claim is always a pair on the same mutant (companion KILL or CLASSIFICATION_ONLY + one named DEFENSE passing).
 * A DEFENSE method alone is vacuous and never counted. "Unchanged" compares stored Preferences (read barrier removed).
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentDefenseContractTest {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun cleanup() = runBlocking { controlTestTimeout("intent defense cleanup", 30000) { opened.forEach { it.close() } } }
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun strip(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val limit = ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String = "r", session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private val other = intent("A", "CAPABILITY", "k", id = "r3", session = "other")
    private val old = DemandAuthFixtures.request(id = "old", owner = "A", intent = RefreshIntent.IF_STALE)
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun raw(src: ControlNode, demand: String = payload(listOf(old)), evidence: String = "[]"): Preferences =
        ControlLifecycleEvidenceFixtures.raw(demand = demand, evidence = evidence).toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(listOf(src, other))
        }.toPreferences()
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "old-tracking", true, true)
    private fun input(src: ControlNode) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, closure(src))
    private fun context(src: ControlNode) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(src)))

    /** [seed] may adjust the record after prepare (the issued ids are then known). */
    private class Event(val source: ControlNode, val raw: Preferences, val seed: ((CommandRef, Preferences) -> Preferences)? = null)
    private class Result(val result: ControlStoreResult, val seeded: Preferences, val after: Preferences, val problem: HoldRecoveryProblem?)
    private suspend fun execute(e: Event): Result {
        val s = ControlStoreTestStorage(temp.newFile()).also { opened += it }
        val c = s.control.prepareRecoverIntent(input(e.source), LifecycleOrderSource(newLife, 21))
        val seeded = e.seed?.invoke(c, e.raw) ?: e.raw
        controlTestTimeout("defense seed") { s.data.updateData { seeded } }
        val problem = (c.body as ControlCommandBody.Lifecycle).input.recoverIntent?.preparationProblem
        val result = controlTestTimeout("defense execute") { s.control.execute(c, context(e.source)) }
        return Result(result, strip(seeded), strip(s.raw()), problem)
    }
    private fun rejected(r: ControlStoreResult, detail: String) = r is ControlStoreResult.Rejected && r.reason == RejectionReason.InvalidRequest(detail)
    private fun matchesProblem(r: ControlStoreResult, p: HoldRecoveryProblem?) = when (p) {
        null -> false
        is HoldRecoveryProblem.Rejected -> r is ControlStoreResult.Rejected && r.reason == p.reason
        is HoldRecoveryProblem.Conflict -> r is ControlStoreResult.Conflict && r.reason == p.reason
        is HoldRecoveryProblem.RecoveryRequired -> r is ControlStoreResult.RecoveryRequired && r.reason == p.reason
    }

    private fun capCurrent() = Event(intent("A", "CAPABILITY", "k"), raw(intent("A", "CAPABILITY", "k")))
    private fun userCurrent() = Event(intent("A", "USER", "u"), raw(intent("A", "USER", "u")))
    private fun differentTarget() = Event(intent("A", "CAPABILITY", "k0"), raw(intent("A", "CAPABILITY", "k0")))
    private fun departed() = Event(intent("B", "CAPABILITY", null), raw(intent("B", "CAPABILITY", null)))

    private fun confirms(id: String, e: Event) = runReleaseTest {
        assertTrue(atomic("RI.D.$id.confirms"), execute(e).result is ControlStoreResult.Confirmed)
    }
    private fun refusedAt(id: String, boundary: String, e: Event, named: (Result) -> Boolean) = runReleaseTest {
        val x = execute(e)
        if (x.result is ControlStoreResult.Confirmed) return@runReleaseTest
        assertTrue(atomic("RI.D.$id.$boundary"), named(x))
        assertEquals(atomic("RI.D.$id.$boundary.storedUnchanged"), x.seeded, x.after)
    }
    private fun preparation(id: String, e: Event) = refusedAt(id, "preparation", e) { matchesProblem(it.result, it.problem) }
    private fun descriptor(id: String, e: Event) = refusedAt(id, "descriptor", e) { it.problem == null && rejected(it.result, "InvalidLifecycleDescriptor") }
    private fun validator(id: String, e: Event) = refusedAt(id, "validator", e) { it.problem == null && rejected(it.result, "RequiredDecisionEffectMissing") }

    @Test fun capCurrent_confirms() = confirms("capCurrent", capCurrent())
    @Test fun capCurrent_refusedAtPreparation() = preparation("capCurrent", capCurrent())
    @Test fun capCurrent_refusedAtDescriptor() = descriptor("capCurrent", capCurrent())
    @Test fun capCurrent_refusedAtValidator() = validator("capCurrent", capCurrent())
    @Test fun userCurrent_confirms() = confirms("userCurrent", userCurrent())
    @Test fun userCurrent_refusedAtPreparation() = preparation("userCurrent", userCurrent())
    @Test fun userCurrent_refusedAtDescriptor() = descriptor("userCurrent", userCurrent())
    @Test fun userCurrent_refusedAtValidator() = validator("userCurrent", userCurrent())
    @Test fun differentTarget_confirms() = confirms("differentTarget", differentTarget())
    @Test fun differentTarget_refusedAtPreparation() = preparation("differentTarget", differentTarget())
    @Test fun differentTarget_refusedAtDescriptor() = descriptor("differentTarget", differentTarget())
    @Test fun differentTarget_refusedAtValidator() = validator("differentTarget", differentTarget())
    @Test fun departed_confirms() = confirms("departed", departed())
    @Test fun departed_refusedAtPreparation() = preparation("departed", departed())
    @Test fun departed_refusedAtDescriptor() = descriptor("departed", departed())
    @Test fun departed_refusedAtValidator() = validator("departed", departed())

    // ---- over-limit builder paths (C12): the builder refuses on its own; the validator is the defense behind it ----

    private fun successorFor(requestId: String) = DemandAuthFixtures.request(id = requestId, owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun appliedFor(c: CommandRef, requestId: String) = ControlLifecycleEvidenceFixtures.wire("RECOVER_INTENT",
        """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"$requestId","effect":"CREATE"}""", c.id, c.ownerTrackingLifetimeId.value)
    private fun requestId(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent).ids.requestId
    private val capSource = intent("A", "CAPABILITY", "k")
    /** A preserved REQUEST pads the appended DEMAND payload to limit + 1 bytes. */
    private fun demandOverLimit() = Event(capSource, raw(capSource)) { c, base ->
        val successor = successorFor(requestId(c))
        fun row(id: String) = DemandAuthFixtures.request(id = id, owner = "A", intent = RefreshIntent.IF_STALE)
        val pad = row("pad" + "x".repeat(limit + 1 - bytes(payload(listOf(row("pad"), successor)))))
        check(bytes(payload(listOf(pad, successor))) == limit + 1)
        base.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOf(pad)) }.toPreferences()
    }
    /** A preserved foreign Applied pads the appended evidence payload to limit + 1 bytes. */
    private fun evidenceOverLimit() = Event(capSource, raw(capSource)) { c, base ->
        val own = appliedFor(c, requestId(c))
        fun entry(cmd: String) = ControlLifecycleEvidenceFixtures.wire(command = cmd)
        val pad = entry("pad" + "x".repeat(limit + 1 - bytes("[" + entry("pad") + "," + own + "]")))
        check(bytes("[$pad,$own]") == limit + 1)
        base.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$pad]" }.toPreferences()
    }
    private fun tooLarge(r: ControlStoreResult, key: ControlPayloadKey) =
        r is ControlStoreResult.Rejected && r.reason == RejectionReason.TooLarge(key, limit + 1, limit)
    private fun atBuild(id: String, e: Event, key: ControlPayloadKey) = runReleaseTest {
        val x = execute(e)
        assertTrue(atomic("RI.D.$id.build"), x.problem == null && tooLarge(x.result, key)) // CLASSIFICATION_ONLY
    }
    private fun beforeStorage(id: String, e: Event, key: ControlPayloadKey) = runReleaseTest {
        val x = execute(e)
        assertTrue(atomic("RI.D.$id.beforeStorage"), x.problem == null && (tooLarge(x.result, key) || rejected(x.result, "RequiredDecisionEffectMissing")))
        assertEquals(atomic("RI.D.$id.beforeStorage.storedUnchanged"), x.seeded, x.after)
    }
    @Test fun demandOverLimit_refusedAtBuild() = atBuild("demandOverLimit", demandOverLimit(), ControlPayloadKey.forKind(ControlKind.DEMAND))
    @Test fun demandOverLimit_refusedBeforeStorage() = beforeStorage("demandOverLimit", demandOverLimit(), ControlPayloadKey.forKind(ControlKind.DEMAND))
    @Test fun evidenceOverLimit_refusedAtBuild() = atBuild("evidenceOverLimit", evidenceOverLimit(), ControlPayloadKey.COMMAND_EVIDENCE)
    @Test fun evidenceOverLimit_refusedBeforeStorage() = beforeStorage("evidenceOverLimit", evidenceOverLimit(), ControlPayloadKey.COMMAND_EVIDENCE)
}
