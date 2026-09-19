package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*

internal object CurrentNullFixtures {
    val life = LifetimeId("n-origin")
    val before = FenceV1("A", "u2", "k2")
    val executor = SettlementExecutor("A", 9, life)
    val context = AttemptContext("A", 9, life, false, false)
    val request = SettlementDemand("A", 9, EventOrderV1(life, 23), RefreshIntent.FORCE_PREMIUM)
    const val newUser = "11111111-1111-4111-8111-111111111111"
    const val newKrx = "22222222-2222-4222-8222-222222222222"
    const val nullUser = """{"id":"s","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER"}"""
    const val nullKrx = """{"id":"c","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"CAPABILITY"}"""
    const val companionUser = """{"id":"us","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"u2"}"""
    const val companionKrx = """{"id":"ks","kind":"NAMESPACE","ownerUid":"A","axis":"CAPABILITY","epoch":"k2"}"""
    val transition get() = CurrentNullSettlementTransition(ControlPayloadCodec())
    val blob = byteArrayPreferencesKey("external-bytes")
    val extra = stringPreferencesKey("external-text")
    fun spec(target: ControlNode = node(nullUser), nulls: List<ControlNode> = listOf(target),
        companions: List<ControlNode> = emptyList(), fence: FenceV1 = before, exec: SettlementExecutor = executor,
        op: String = "n-operation", did: String = "n-demand", demand: SettlementDemand = request,
        u: String? = newUser.takeIf { nulls.any { it.text("axis") == FieldRead.Present("USER") } },
        k: String? = newKrx.takeIf { nulls.any { it.text("axis") == FieldRead.Present("CAPABILITY") } }) =
        CurrentNullSettlement(nulls, companions, fence, exec, op, did, demand, u, k)
    fun both() = spec(nulls = listOf(node(nullKrx), node(nullUser)), companions = listOf(node(companionKrx), node(companionUser)))
    fun target(s: CurrentNullSettlement) = (ControlObligations.read(ControlKind.SEAL, s.nullTargets.first()) as ControlEntryRead.Interpreted).value as SealV1
    fun raw(s: CurrentNullSettlement = spec()): Preferences = NamespaceSettlementFixtures.raw(
        seals = NamespaceSettlementFixtures.jsonArray(*(s.nullTargets + s.companions).toTypedArray())).toMutablePreferences().apply {
        if (s.before.ownerUid == null) remove(OWNER_UID) else this[OWNER_UID] = s.before.ownerUid
        if (s.before.userAccessEpoch == null) remove(USER_EPOCH) else this[USER_EPOCH] = s.before.userAccessEpoch
        if (s.before.krxCapabilityEpoch == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = s.before.krxCapabilityEpoch
        this[blob] = byteArrayOf(0, -1, 3, 127)
        this[extra] = "  keep me exactly  "
    }
    fun read(raw: Preferences) = ControlRecordReader().read(raw) as ControlRecordRead.Supported
    fun command(s: CurrentNullSettlement = spec(), lifetime: OwnerTrackingLifetimeId = OwnerTrackingLifetimeId.issue()) =
        CommandRef(s.operationId, ControlCommandBody.RotateAndSettleCurrentNull(s), lifetime)
    // Independent oracle: no production after/witness/buildCandidate calls.
    fun ordered(s: CurrentNullSettlement) = (s.nullTargets + s.companions).sortedWith(compareBy<ControlNode> {
        if (it.text("axis") == FieldRead.Present("USER")) 0 else 1
    }.thenBy { if (it.text("kind") == FieldRead.Present("NULL_NAMESPACE")) 0 else 1 })
    fun witness(s: CurrentNullSettlement, seal: SealV1 = target(s)): SettlementEvidenceV1 {
        val axes = s.nullTargets.map { (it.text("axis") as FieldRead.Present).value }
        val after = FenceV1(s.before.ownerUid, if ("USER" in axes) s.newUserEpoch else s.before.userAccessEpoch,
            if ("CAPABILITY" in axes) s.newKrxEpoch else s.before.krxCapabilityEpoch)
        return SettlementEvidenceV1(s.operationId, s.executor.originLifetimeId, StoreOp.BEGIN_ROTATION,
            s.before, after, JournalTargetV1(s.before.ownerUid, seal.key.axis, null))
    }
    fun applied(c: CommandRef, s: CurrentNullSettlement): String =
        """{"version":2,"commandId":${q(c.id)},"ownerTrackingLifetimeId":${q(c.ownerTrackingLifetimeId.value)},"kind":"SETTLEMENT","transition":"CURRENT_NULL","sealIds":[${ordered(s).joinToString(",") { q((it.text("id") as FieldRead.Present).value) }}],"demandId":${q(s.demandId)}}"""
    fun demand(s: CurrentNullSettlement): String {
        val d = s.demand
        return """{"id":${q(s.demandId)},"kind":"REQUEST","ownerUid":${q(d.ownerUid)},"binding":${d.binding},"originLifetimeId":${q(d.raisedAt.origin.value)},"raisedAt":${d.raisedAt.value},"intent":${q(d.intent.name)}}"""
    }
    fun landed(c: CommandRef, s: CurrentNullSettlement, initial: Preferences = raw(s)): Preferences = initial.toMutablePreferences().apply {
        val targets = ordered(s).map { (it.text("id") as FieldRead.Present).value }.toSet()
        val nodes = read(initial).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*nodes.map {
            val seal = (ControlObligations.read(ControlKind.SEAL, it) as ControlEntryRead.Interpreted).value as SealV1
            if (seal.id in targets) NamespaceSettlementFixtures.withWitness(it.toPayloadEntry().fields.toString(), witness(s, seal)) else it
        }.toTypedArray())
        val axes = s.nullTargets.map { (it.text("axis") as FieldRead.Present).value }.toSet()
        val lines = listOf("USER", "CAPABILITY").filter { it in axes }.map { "${s.before.ownerUid.orEmpty()}|||$it" }
        val missing = lines.filterNot { it in initial[PURGE_JOURNAL].orEmpty().split('\n') }
        if (missing.isNotEmpty()) this[PURGE_JOURNAL] = (listOfNotNull(initial[PURGE_JOURNAL]) + missing).joinToString("\n")
        val old = read(initial).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
        this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*(old + node(demand(s))).toTypedArray())
        val e = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        this[e] = initial[e]!!.dropLast(1) + (if (initial[e] == "[]") "" else ",") + applied(c, s) + "]"
        if ("USER" in axes) { this[USER_EPOCH] = s.newUserEpoch!!; this[MAY_CONTAIN_PREMIUM] = false }
        if ("CAPABILITY" in axes) { this[KRX_EPOCH] = s.newKrxEpoch!!; this[MAY_CONTAIN_KRX] = false }
    }
    fun decide(s: CurrentNullSettlement = spec(), raw: Preferences = raw(s), context: AttemptContext? = CurrentNullFixtures.context,
        only: Boolean = false, confirmed: Boolean = false, c: CommandRef = command(s)) =
        transition.decide(c, s, read(raw), context, only, confirmed)
    fun negative(decision: RecordTransactionDecision<ControlRecordStore.Outcome>, reason: Any) {
        assertTrue("no Confirm candidate", decision is RecordTransactionDecision.Observe)
        val result = ((decision as RecordTransactionDecision.Observe).value as ControlRecordStore.Outcome.Negative).result
        NamespaceSettlementFixtures.negative(result, reason)
    }
    fun q(v: String?) = JsonPrimitive(v).toString()
}

abstract class CurrentNullOwnerBase : ReleaseOwnerTestBase() {
    internal suspend fun seedN(s: CurrentNullSettlement = CurrentNullFixtures.spec(), raw: Preferences = CurrentNullFixtures.raw(s)) =
        controlTestTimeout("N seed") { o.data.updateData { raw } }
    internal fun registerN(s: CurrentNullSettlement): CommandRef = tracking.registerPrepared(CurrentNullFixtures.command(s, tracking.lifetimeId))
    internal suspend fun executeN(c: CommandRef, context: AttemptContext? = CurrentNullFixtures.context): ControlStoreResult =
        controlTestTimeout("N execute") { if (context == null) o.control.execute(c) else o.control.execute(c, context) }
    internal fun successN(result: ControlStoreResult, effect: ConfirmedEffect): ControlStoreResult.Confirmed {
        assertTrue("N confirmed: $result", result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertEquals(effect, result.effect)
        assertNotNull(result.handoverSettlement)
        assertNull(result.settlement)
        assertFalse(result.command in result.localUnresolvedCommands)
        assertFalse(result.command in tracking.executing)
        return result
    }
    internal suspend fun deniedN(s: CurrentNullSettlement = CurrentNullFixtures.spec(),
        source: Preferences = CurrentNullFixtures.raw(s), context: AttemptContext? = CurrentNullFixtures.context,
        reason: Any) {
        seedN(s, source)
        val c = registerN(s)
        tracking.markUnresolved(c)
        val before = disk(); val writes = o.storage.writes
        val attempt = runCatching { executeN(c, context) }
        assertNull("rejection must return without throwing", attempt.exceptionOrNull())
        val result = attempt.getOrThrow()
        NamespaceSettlementFixtures.negative(result, reason)
        assertEquals(before, disk()); assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertTrue(result.localPendingReleases.isEmpty())
        assertFalse(c in tracking.executing)
        assertNull(history(c).firstConfirmDiscontinuityCount)
        assertFalse(history(c).confirmationRequested.get())
    }
    internal suspend fun assertLanded(c: CommandRef, s: CurrentNullSettlement, initial: Preferences) {
        val expected = CurrentNullFixtures.landed(c, s, initial).toMutablePreferences().apply { remove(READ_BARRIER) }
        assertEquals("N landed record must equal the independent full snapshot", expected, disk().toMutablePreferences().apply { remove(READ_BARRIER) })
    }
}
