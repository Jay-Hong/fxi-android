package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Literal wire fixtures; candidate() deliberately does not call a production plan/builder. */
internal object HoldRecoveryFixtures {
    val life = LifetimeId("new-life")
    val beforeFence = FenceV1("A", "u", "k")
    val executor = SettlementExecutor("A", 3, life)
    val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    val now = BootReading("boot", 11000)
    val userEpoch = "00000000-0000-0000-0000-000000000011"
    val krxEpoch = "00000000-0000-0000-0000-000000000012"
    val ids = RecoverHoldIds("00000000-0000-0000-0000-000000000101", "new-request", "new-guard", RecoveryFreshEpochs(null, krxEpoch))
    val grant = LifecycleOrderGrant(life, 21, 1, 0, 22)
    val codec = ControlPayloadCodec()
    val writer = RecoverHoldTransition(codec)
    fun node(raw: String) = ControlLifecycleEvidenceFixtures.node(raw)
    fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
    fun field(node: ControlNode, name: String, value: JsonElement?) = FloorGuardFixtures.field(node, name, value)
    fun hold(owner: String? = "A", user: String? = "u", krx: String? = "k", both: Boolean = false,
        topic: Boolean = false, floor: Boolean = true, intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM,
        id: String = "h"): ControlNode {
        val identity = owner?.let { """{"ownerUid":${text(it)},"authGeneration":2}""" } ?: "null"
        val fence = """{"ownerUid":${text(owner)},"userAccessEpoch":${text(user)},"krxCapabilityEpoch":${text(krx)}}"""
        val provenance = if (topic) """{"kind":"TOPIC","grant":9,"context":{"identity":$identity,"access":$fence,"generation":5}}"""
            else """{"kind":"QUERY","started":{"fence":$fence,"generation":5,"boundIdentity":$identity,"order":9223372036854775807,"binding":17,"intent":"${intent.name}","userInvalidations":0},"answeredAs":$identity}"""
        val outcome = if (both) """{"kind":"PREMIUM_REQUIRED"}""" else if (topic) """{"kind":"KRX_ENTITLEMENT_REQUIRED"}"""
            else """{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":${if (floor) "30" else "null"}}"""
        val wait = if (floor) ""","floor":{"anchorBootId":"boot","anchorElapsedMillis":10000,"waitMillis":30000,"originLifetimeId":"life"}""" else ""
        return node("""{"id":${text(id)},"originLifetimeId":"life","binding":17,"axes":${if(both) "[\"USER\",\"CAPABILITY\"]" else "[\"CAPABILITY\"]"},"outcome":$outcome,"provenance":$provenance$wait}""")
    }
    fun guard(wait: Long = 10000): ControlNode = FloorGuardFixtures.guard(FloorGuardFixtures.floor(wait), auth = true)
    fun request(id: String = "old", owner: String? = "A", intent: RefreshIntent = RefreshIntent.IF_STALE): ControlNode = node(
        """{"id":${text(id)},"kind":"REQUEST","ownerUid":${text(owner)},"binding":3,"originLifetimeId":"old-life","raisedAt":7,"intent":"${intent.name}"}""")
    fun restart(h: ControlNode = hold(), exec: SettlementExecutor = executor) = HoldRecoveryClosure.AfterRestart(h, exec, "previous-tracker", true, true)
    fun same(h: ControlNode = hold(), exec: SettlementExecutor = executor, generation: Long = 5, closed: Boolean = true,
        captured: Set<String> = setOf("w"), joined: Set<String> = setOf("w")) = HoldRecoveryClosure.SameProcess(h, exec, generation, closed, captured, joined)
    fun runtime(closure: HoldRecoveryClosure = restart(), b: LifecycleBinding = binding, generation: Long = 5,
        closed: Boolean = true, work: Set<String> = emptySet()) = HoldRecoveryRuntime(b, generation, closed, work, closure)
    fun input(h: ControlNode = hold(), g: ControlNode? = guard(), before: FenceV1 = beforeFence,
        b: LifecycleBinding = binding, closure: HoldRecoveryClosure = restart(h, b.executor)) = RecoverHoldInput(h, g, before, b, closure, now)
    fun context(input: RecoverHoldInput = input(), rt: HoldRecoveryRuntime = runtime(input.closure, input.binding)) =
        AttemptContext(input.binding.executor.ownerUid, input.binding.executor.binding, input.binding.executor.originLifetimeId, false, false, holdRecovery = rt)
    fun before(input: RecoverHoldInput = input(), siblings: Boolean = true): Preferences =
        ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            val f = input.before
            if (f.ownerUid == null) remove(OWNER_UID) else this[OWNER_UID] = f.ownerUid
            if (f.userAccessEpoch == null) remove(USER_EPOCH) else this[USER_EPOCH] = f.userAccessEpoch
            if (f.krxCapabilityEpoch == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = f.krxCapabilityEpoch
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = payload(listOf(input.source))
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOfNotNull(input.guard) + if (siblings) listOf(request(), request("other", "B", RefreshIntent.FORCE_PREMIUM)) else emptyList())
        }.toPreferences()
    fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    fun read(raw: Preferences) = ControlLifecycleEvidenceFixtures.read(raw)
    fun plan(input: RecoverHoldInput = input(), ids: RecoverHoldIds = this.ids, orders: LifecycleOrderSource = LifecycleOrderSource(life, 21)) =
        RecoverHoldPlan.prepare(input, ids, orders)
    fun command(plan: RecoverHoldPlan = plan()) = ControlLifecycleEvidenceFixtures.command(plan.descriptor())
    fun stripBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    fun assertFixture(input: RecoverHoldInput, raw: Preferences) {
        val h = ControlSchema.read(ControlKind.HOLD, input.source) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY), h.axes)
        assertEquals(FenceV1("A", "u", "k"), (h.provenance as HoldProvenanceV1.Query).started.fence)
        assertEquals(17L, h.binding); assertEquals(LifetimeId("life"), h.originLifetimeId)
        assertEquals(FloorV1("boot",10000,30000,LifetimeId("life")), h.floor)
        assertEquals(BootReading("boot",11000), input.mergeNow)
        val r = read(raw); assertFalse(r.hasUninterpretable); assertFalse(r.hasUninterpretableMetadata)
        assertEquals(2, r.schemaVersion); assertEquals("A", raw[OWNER_UID]); assertEquals("k", raw[KRX_EPOCH])
    }
    fun candidate(c: CommandRef, ids: RecoverHoldIds = this.ids): Preferences {
        val input = input(); val raw = before(input)
        val newRequest = node("""{"id":${text(ids.requestId)},"kind":"REQUEST","ownerUid":"A","binding":3,"intent":"FORCE_PREMIUM","originLifetimeId":"new-life","raisedAt":22}""")
        val newGuard = field(guard(), "floor", FloorGuardFixtures.floor(29000, "boot", 11000, "new-life"))
        return raw.toMutablePreferences().apply {
            this[KRX_EPOCH] = checkNotNull(ids.epochs.capability); this[MAY_CONTAIN_KRX] = false
            this[PURGE_JOURNAL] = "A||k|CAPABILITY"
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOf(newGuard, request(), request("other", "B", RefreshIntent.FORCE_PREMIUM), newRequest))
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[" + ControlLifecycleEvidenceFixtures.wire("RECOVER_HOLD",
                """{"kind":"HOLD","id":"h","effect":"REMOVE"},{"kind":"DEMAND","id":${text(ids.requestId)},"effect":"CREATE"},{"kind":"DEMAND","id":"g","effect":"REPLACE"}""",
                c.id, c.ownerTrackingLifetimeId.value) + "]"
        }.toPreferences()
    }
    fun row(raw: Preferences, kind: ControlKind, id: String) = read(raw).locations(id).single().second.let { (it as ControlEntryRead.Interpreted).original }
    fun changeRow(raw: Preferences, kind: ControlKind, id: String, change: (ControlNode) -> ControlNode?): Preferences {
        val rows = read(raw).arrays.getValue(kind).entries.mapNotNull {
            val e = it as ControlEntryRead.Interpreted
            if (e.value.id == id) change(e.original) else e.original
        }
        return raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(kind)] = payload(rows) }.toPreferences()
    }
    fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)
}
