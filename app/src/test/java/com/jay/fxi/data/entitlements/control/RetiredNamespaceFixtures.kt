package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*

internal object RetiredNamespaceFixtures {
    val life = LifetimeId("r-origin")
    val before = FenceV1("A", "u2", "k2")
    val executor = SettlementExecutor("A", 9, life)
    val context = AttemptContext("A", 9, life, false, false)
    val request = SettlementDemand("A", 9, EventOrderV1(life, 23), RefreshIntent.FORCE_PREMIUM)
    val transition get() = RetiredNamespaceSettlementTransition(ControlPayloadCodec())
    val blob = byteArrayPreferencesKey("external-bytes")
    val extra = stringPreferencesKey("external-text")
    fun spec(target: ControlNode = node(NamespaceSettlementFixtures.user), fence: FenceV1 = before,
        exec: SettlementExecutor = executor, op: String = "r-operation", did: String? = "r-demand",
        demand: SettlementDemand? = request) = RetiredNamespaceSettlement(target, fence, exec, op, did, demand)
    fun departed() = spec(fence = before.copy(ownerUid = "B"), exec = executor.copy(ownerUid = "B"), did = null, demand = null)
    fun target(s: RetiredNamespaceSettlement) = (ControlObligations.read(ControlKind.SEAL, s.target) as ControlEntryRead.Interpreted).value as SealV1
    fun raw(s: RetiredNamespaceSettlement = spec()): Preferences = NamespaceSettlementFixtures.raw(
        seals = NamespaceSettlementFixtures.jsonArray(s.target)).toMutablePreferences().apply {
        if (s.before.ownerUid == null) remove(OWNER_UID) else this[OWNER_UID] = s.before.ownerUid
        if (s.before.userAccessEpoch == null) remove(USER_EPOCH) else this[USER_EPOCH] = s.before.userAccessEpoch
        if (s.before.krxCapabilityEpoch == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = s.before.krxCapabilityEpoch
        this[blob] = byteArrayOf(0, -1, 3, 127)
        this[extra] = "  keep me exactly  "
    }
    fun read(raw: Preferences) = ControlRecordReader().read(raw) as ControlRecordRead.Supported
    fun command(s: RetiredNamespaceSettlement = spec(), lifetime: OwnerTrackingLifetimeId = OwnerTrackingLifetimeId.issue()) =
        CommandRef(s.operationId, ControlCommandBody.SettleRetiredNamespace(s), lifetime)
    fun witness(s: RetiredNamespaceSettlement): SettlementEvidenceV1 {
        val seal = target(s)
        return SettlementEvidenceV1(s.operationId, s.executor.originLifetimeId, StoreOp.JOURNAL_RETIRED,
            s.before, s.before, JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch))
    }
    fun applied(c: CommandRef, s: RetiredNamespaceSettlement): String =
        """{"version":2,"commandId":${q(c.id)},"ownerTrackingLifetimeId":${q(c.ownerTrackingLifetimeId.value)},"kind":"SETTLEMENT","transition":"RETIRED_NAMESPACE","sealIds":[${q(target(s).id)}],"demandId":${q(s.demandId)}}"""
    fun demand(s: RetiredNamespaceSettlement): String {
        val d = s.demand!!
        return """{"id":${q(s.demandId)},"kind":"REQUEST","ownerUid":${q(d.ownerUid)},"binding":${d.binding},"originLifetimeId":${q(d.raisedAt.origin.value)},"raisedAt":${d.raisedAt.value},"intent":${q(d.intent.name)}}"""
    }
    fun landed(c: CommandRef, s: RetiredNamespaceSettlement, initial: Preferences = raw(s)): Preferences = initial.toMutablePreferences().apply {
        val seal = target(s)
        val nodes = read(initial).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*nodes.map {
            if ((ControlObligations.read(ControlKind.SEAL, it) as ControlEntryRead.Interpreted).value.id == seal.id)
                NamespaceSettlementFixtures.withWitness(it.toPayloadEntry().fields.toString(), witness(s)) else it
        }.toTypedArray())
        val line = if (seal.key.axis == PurgeScope.USER) "${seal.key.ownerUid.orEmpty()}|${seal.key.epoch}||USER" else "${seal.key.ownerUid.orEmpty()}||${seal.key.epoch}|CAPABILITY"
        if (line !in initial[PURGE_JOURNAL].orEmpty().split('\n')) this[PURGE_JOURNAL] = listOfNotNull(initial[PURGE_JOURNAL], line).joinToString("\n")
        if (s.demand != null) {
            val old = read(initial).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
            this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*(old + node(demand(s))).toTypedArray())
        }
        this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[${applied(c, s)}]"
    }
    fun decide(s: RetiredNamespaceSettlement = spec(), raw: Preferences = raw(s), context: AttemptContext? = RetiredNamespaceFixtures.context,
        only: Boolean = false, confirmed: Boolean = false, c: CommandRef = command(s)) =
        transition.decide(c, s, read(raw), context, only, confirmed)
    fun negative(decision: RecordTransactionDecision<ControlRecordStore.Outcome>, reason: Any) {
        assertTrue("no Confirm candidate", decision is RecordTransactionDecision.Observe)
        val result = ((decision as RecordTransactionDecision.Observe).value as ControlRecordStore.Outcome.Negative).result
        NamespaceSettlementFixtures.negative(result, reason)
    }
    fun q(v: String?) = JsonPrimitive(v).toString()
}

abstract class RetiredNamespaceOwnerBase : ReleaseOwnerTestBase() {
    internal suspend fun seedR(s: RetiredNamespaceSettlement = RetiredNamespaceFixtures.spec(), raw: Preferences = RetiredNamespaceFixtures.raw(s)) =
        controlTestTimeout("R seed") { o.data.updateData { raw } }
    internal fun registerR(s: RetiredNamespaceSettlement): CommandRef = tracking.registerPrepared(RetiredNamespaceFixtures.command(s, tracking.lifetimeId))
    internal suspend fun executeR(c: CommandRef, context: AttemptContext? = RetiredNamespaceFixtures.context): ControlStoreResult =
        controlTestTimeout("R execute") { if (context == null) o.control.execute(c) else o.control.execute(c, context) }
    internal fun successR(result: ControlStoreResult, effect: ConfirmedEffect): ControlStoreResult.Confirmed {
        assertTrue("R confirmed: $result", result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertEquals(effect, result.effect)
        assertNotNull(result.handoverSettlement)
        assertNull(result.settlement)
        assertFalse(result.command in result.localUnresolvedCommands)
        assertFalse(result.command in tracking.executing)
        return result
    }
    internal suspend fun deniedR(s: RetiredNamespaceSettlement = RetiredNamespaceFixtures.spec(),
        source: Preferences = RetiredNamespaceFixtures.raw(s), context: AttemptContext? = RetiredNamespaceFixtures.context,
        reason: Any) {
        seedR(s, source)
        val c = registerR(s)
        tracking.markUnresolved(c)
        val before = disk(); val writes = o.storage.writes
        val result = executeR(c, context)
        NamespaceSettlementFixtures.negative(result, reason)
        assertEquals(before, disk()); assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertTrue(result.localPendingReleases.isEmpty())
        assertFalse(c in tracking.executing)
        assertNull(history(c).firstConfirmDiscontinuityCount)
        assertFalse(history(c).confirmationRequested.get())
    }
    internal suspend fun assertLanded(c: CommandRef, s: RetiredNamespaceSettlement, initial: Preferences) {
        val expected = RetiredNamespaceFixtures.landed(c, s, initial).toMutablePreferences().apply { remove(READ_BARRIER) }
        assertEquals(expected, disk().toMutablePreferences().apply { remove(READ_BARRIER) })
    }
}
