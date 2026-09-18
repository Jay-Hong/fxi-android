package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import kotlinx.serialization.json.Json
import org.junit.Assert.*

internal object NamespaceSettlementFixtures {
    val life = LifetimeId("life")
    val fence = FenceV1("A", "u", "k")
    val context = AttemptContext("A", 3, life, false, false)
    val demand = SettlementDemand("A", 3, EventOrderV1(life, 7), RefreshIntent.FORCE_PREMIUM)
    const val operation = "00000000-0000-0000-0000-000000000001"
    const val demandId = "00000000-0000-0000-0000-000000000002"
    const val newUser = "00000000-0000-0000-0000-000000000003"
    const val newKrx = "00000000-0000-0000-0000-000000000004"
    const val user = """{"id":"s","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"u"}"""
    const val krx = """{"id":"c","kind":"NAMESPACE","ownerUid":"A","axis":"CAPABILITY","epoch":"k"}"""
    val transition get() = NamespaceSettlementTransition(ControlPayloadCodec())

    fun input(
        targets: List<ControlNode> = listOf(node(user)), before: FenceV1 = fence,
        origin: LifetimeId = life, request: SettlementDemand = demand,
        op: String = operation, did: String = demandId, u: String? = newUser, k: String? = newKrx
    ) = RotateAndSettleNamespaces(targets, before, origin, request, op, did, u, k)

    fun raw(seals: String = "[$user]", requests: String = "[]"): Preferences = mutablePreferencesOf().apply {
        this[SCHEMA] = 1
        this[ControlRecordKeys.payload(ControlKind.SEAL)] = seals
        this[ControlRecordKeys.payload(ControlKind.DEMAND)] = requests
        this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
        this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[]"
        this[OWNER_UID] = "A"
        this[USER_EPOCH] = "u"
        this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true
        this[MAY_CONTAIN_KRX] = true
    }

    fun command(o: ControlStoreTestStorage, input: RotateAndSettleNamespaces): CommandRef =
        CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input)).also {
            check(ControlCommandTracking.forOwner(o.owner).commands.putIfAbsent(it.id, TrackedControlCommand(it)) == null)
        }

    fun confirmed(result: ControlStoreResult, effect: ConfirmedEffect): ControlStoreResult.Confirmed {
        assertEquals(ControlStoreResult.Confirmed::class.java, result.javaClass)
        result as ControlStoreResult.Confirmed
        assertEquals(effect, result.effect)
        assertNotNull(result.settlement)
        assertFalse(result.localUnresolvedCommands.contains(result.command))
        return result
    }

    fun negative(result: ControlStoreResult, reason: Any) {
        when (reason) {
            is ConflictReason -> {
                assertEquals(ControlStoreResult.Conflict::class.java, result.javaClass)
                assertEquals(reason, (result as ControlStoreResult.Conflict).reason)
                assertSame(result.command, result.expected.command)
            }
            is RecoveryReason -> {
                assertEquals(ControlStoreResult.RecoveryRequired::class.java, result.javaClass)
                assertEquals(reason, (result as ControlStoreResult.RecoveryRequired).reason)
            }
            is RejectionReason -> {
                assertEquals(ControlStoreResult.Rejected::class.java, result.javaClass)
                assertEquals(reason, (result as ControlStoreResult.Rejected).reason)
            }
            else -> error("missing expected reason: $reason")
        }
    }

    fun settled(input: RotateAndSettleNamespaces, source: Preferences = raw()): Preferences {
        val command = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input))
        val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
        val result = transition.decide(command, input, read, context, false, false)
        return (result as com.jay.fxi.data.entitlements.RecordTransactionDecision.Confirm).candidate
    }

    fun jsonArray(vararg nodes: ControlNode) =
        (ControlPayloadCodec().encode(nodes.map { it.toPayloadEntry() }) as PayloadWrite.Encoded).text

    fun replace(json: String, old: String, new: String): ControlNode = node(json.replace(old, new))

    fun withWitness(original: String, evidence: SettlementEvidenceV1): ControlNode {
        fun q(value: String?) = value?.let { Json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"
        fun f(f: FenceV1) = """{"ownerUid":${q(f.ownerUid)},"userAccessEpoch":${q(f.userAccessEpoch)},"krxCapabilityEpoch":${q(f.krxCapabilityEpoch)}}"""
        val w = """{"operationId":${q(evidence.operationId)},"originLifetimeId":${q(evidence.originLifetimeId.value)},"operation":${q(evidence.operation.name)},"before":${f(evidence.before)},"after":${f(evidence.after)},"journal":{"ownerUid":${q(evidence.journal.ownerUid)},"axis":${q(evidence.journal.axis.name)},"epoch":${q(evidence.journal.epoch)}}}"""
        return node(original.dropLast(1) + ",\"settlement\":" + w + "}")
    }
}
