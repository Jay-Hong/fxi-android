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

internal object RetiredNullFixtures {
    val life = LifetimeId("l-origin")
    val before = FenceV1("B", "u2", "k2")
    val executor = SettlementExecutor("B", 9, life)
    val context = AttemptContext("B", 9, life, false, false)
    const val nullUser = """{"id":"s","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER"}"""
    const val nullKrx = """{"id":"c","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"CAPABILITY"}"""
    val transition get() = RetiredNullSettlementTransition(ControlPayloadCodec())
    val blob = byteArrayPreferencesKey("external-bytes")
    val extra = stringPreferencesKey("external-text")
    fun spec(target: ControlNode = node(nullUser), targets: List<ControlNode> = listOf(target),
        fence: FenceV1 = before, exec: SettlementExecutor = executor, op: String = "l-operation") =
        RetiredNullSettlement(targets, fence, exec, op)
    fun both() = spec(targets = listOf(node(nullKrx), node(nullUser)))
    fun target(s: RetiredNullSettlement) = (ControlObligations.read(ControlKind.SEAL, s.targets.first()) as ControlEntryRead.Interpreted).value as SealV1
    fun raw(s: RetiredNullSettlement = spec()): Preferences = NamespaceSettlementFixtures.raw(
        seals = NamespaceSettlementFixtures.jsonArray(*s.targets.toTypedArray())).toMutablePreferences().apply {
        if (s.before.ownerUid == null) remove(OWNER_UID) else this[OWNER_UID] = s.before.ownerUid
        if (s.before.userAccessEpoch == null) remove(USER_EPOCH) else this[USER_EPOCH] = s.before.userAccessEpoch
        if (s.before.krxCapabilityEpoch == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = s.before.krxCapabilityEpoch
        this[blob] = byteArrayOf(0, -1, 3, 127)
        this[extra] = "  keep me exactly  "
    }
    fun read(raw: Preferences) = ControlRecordReader().read(raw) as ControlRecordRead.Supported
    fun command(s: RetiredNullSettlement = spec(), lifetime: OwnerTrackingLifetimeId = OwnerTrackingLifetimeId.issue()) =
        CommandRef(s.operationId, ControlCommandBody.SettleRetiredNull(s), lifetime)
    // Independent literal oracle: no production witness/settle/candidate calls.
    fun ordered(s: RetiredNullSettlement) = s.targets.sortedBy {
        if (it.text("axis") == FieldRead.Present("USER")) 0 else 1
    }
    fun witness(s: RetiredNullSettlement, seal: SealV1 = target(s)) = RetiredNullSettlementEvidenceV2(
        s.operationId, s.executor.originLifetimeId, s.before, s.before,
        JournalTargetV1(seal.key.ownerUid, seal.key.axis, null))
    fun witnessJson(s: RetiredNullSettlement, seal: SealV1 = target(s)): String {
        val fence = """{"ownerUid":${q(s.before.ownerUid)},"userAccessEpoch":${q(s.before.userAccessEpoch)},"krxCapabilityEpoch":${q(s.before.krxCapabilityEpoch)}}"""
        return """{"version":2,"kind":"RETIRED_NULL","operationId":${q(s.operationId)},"originLifetimeId":${q(s.executor.originLifetimeId.value)},"before":$fence,"after":$fence,"journal":{"ownerUid":${q(seal.key.ownerUid)},"axis":${q(seal.key.axis.name)},"epoch":null}}"""
    }
    fun withWitness(s: RetiredNullSettlement, original: ControlNode = s.targets.first()): ControlNode {
        val seal = (ControlObligations.read(ControlKind.SEAL, original) as ControlEntryRead.Interpreted).value as SealV1
        return node(original.toPayloadEntry().fields.toString().dropLast(1) + ",\"settlement\":" + witnessJson(s, seal) + "}")
    }
    fun applied(c: CommandRef, s: RetiredNullSettlement): String =
        """{"version":2,"commandId":${q(c.id)},"ownerTrackingLifetimeId":${q(c.ownerTrackingLifetimeId.value)},"kind":"SETTLEMENT","transition":"RETIRED_NULL","sealIds":[${ordered(s).joinToString(",") { q((it.text("id") as FieldRead.Present).value) }}],"demandId":null}"""
    fun landed(c: CommandRef, s: RetiredNullSettlement, initial: Preferences = raw(s)): Preferences = initial.toMutablePreferences().apply {
        val targets = ordered(s).map { (it.text("id") as FieldRead.Present).value }.toSet()
        val nodes = read(initial).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*nodes.map {
            if ((it.text("id") as FieldRead.Present).value in targets) withWitness(s, it) else it
        }.toTypedArray())
        val lines = ordered(s).map { val seal = (ControlObligations.read(ControlKind.SEAL, it) as ControlEntryRead.Interpreted).value as SealV1
            "${seal.key.ownerUid.orEmpty()}|||${seal.key.axis.name}" }
        val missing = lines.filterNot { it in initial[PURGE_JOURNAL].orEmpty().split('\n') }
        if (missing.isNotEmpty()) this[PURGE_JOURNAL] = (listOfNotNull(initial[PURGE_JOURNAL]) + missing).joinToString("\n")
        val e = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        this[e] = initial[e]!!.dropLast(1) + (if (initial[e] == "[]") "" else ",") + applied(c, s) + "]"
    }
    fun decide(s: RetiredNullSettlement = spec(), raw: Preferences = raw(s), context: AttemptContext? = RetiredNullFixtures.context,
        only: Boolean = false, confirmed: Boolean = false, c: CommandRef = command(s)) =
        transition.decide(c, s, read(raw), context, only, confirmed)
    fun negative(decision: RecordTransactionDecision<ControlRecordStore.Outcome>, reason: Any) {
        assertTrue("no Confirm candidate", decision is RecordTransactionDecision.Observe)
        val result = ((decision as RecordTransactionDecision.Observe).value as ControlRecordStore.Outcome.Negative).result
        NamespaceSettlementFixtures.negative(result, reason)
    }
    fun q(v: String?) = JsonPrimitive(v).toString()
}

abstract class RetiredNullOwnerBase : ReleaseOwnerTestBase() {
    internal suspend fun seedL(s: RetiredNullSettlement = RetiredNullFixtures.spec(), raw: Preferences = RetiredNullFixtures.raw(s)) =
        controlTestTimeout("L seed") { o.data.updateData { raw } }
    internal fun registerL(s: RetiredNullSettlement): CommandRef = tracking.registerPrepared(RetiredNullFixtures.command(s, tracking.lifetimeId))
    internal suspend fun executeL(c: CommandRef, context: AttemptContext? = RetiredNullFixtures.context): ControlStoreResult =
        controlTestTimeout("L execute") { if (context == null) o.control.execute(c) else o.control.execute(c, context) }
    internal fun successL(result: ControlStoreResult, effect: ConfirmedEffect): ControlStoreResult.Confirmed {
        assertTrue("L confirmed: $result", result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertEquals(effect, result.effect)
        assertNotNull(result.handoverSettlement)
        assertNull(result.settlement)
        assertFalse(result.command in result.localUnresolvedCommands)
        assertFalse(result.command in tracking.executing)
        return result
    }
    internal suspend fun deniedL(s: RetiredNullSettlement = RetiredNullFixtures.spec(),
        source: Preferences = RetiredNullFixtures.raw(s), context: AttemptContext? = RetiredNullFixtures.context,
        reason: Any) {
        seedL(s, source)
        val c = registerL(s)
        tracking.markUnresolved(c)
        val before = disk(); val writes = o.storage.writes
        val attempt = runCatching { executeL(c, context) }
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
    internal suspend fun assertLanded(c: CommandRef, s: RetiredNullSettlement, initial: Preferences) {
        val expected = RetiredNullFixtures.landed(c, s, initial).toMutablePreferences().apply { remove(READ_BARRIER) }
        assertEquals("L landed record must equal the independent full snapshot", expected, disk().toMutablePreferences().apply { remove(READ_BARRIER) })
    }
}
