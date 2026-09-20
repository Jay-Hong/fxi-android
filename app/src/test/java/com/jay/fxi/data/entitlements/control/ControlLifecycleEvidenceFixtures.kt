package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import kotlinx.serialization.json.*

/** Independent wire oracle; never calls the Applied encoder to construct the expected record. */
internal object ControlLifecycleEvidenceFixtures {
    val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    val origin = LifetimeId("life")
    val executor = SettlementExecutor("A", 3, origin)
    val context = AttemptContext("A", 3, origin, false, false)
    val fence = FenceV1("A", "u", "k")
    fun target(kind: String = "DEMAND", id: String = "g", effect: String = "REMOVE") =
        """{"kind":"$kind","id":${JsonPrimitive(id)},"effect":"$effect"}"""
    fun wire(transition: String = "REMOVE_EMPTY_GUARD", targets: String = target(),
        command: String = "lc", lifetime: String = ReclamationFixtures.oldLife) =
        """{"version":2,"commandId":${JsonPrimitive(command)},"ownerTrackingLifetimeId":"$lifetime","kind":"CONTROL_LIFECYCLE","transition":"$transition","targets":[$targets]}"""
    fun node(raw: String) = ControlNode.of(Json.parseToJsonElement(raw) as JsonObject)
    val guard = node(ControlObligationFixtures.emptyGuard)
    val request = node(ControlObligationFixtures.request)
    val stronger = node(ControlObligationFixtures.request.replace("IF_STALE", "FORCE_PREMIUM"))
    val removeGuard = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE), LifecycleRole.GUARD, guard, null)
    val replaceRequest = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, request, stronger)
    val createRequest = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "d", LifecycleEffect.CREATE), LifecycleRole.REQUEST, null, stronger)
    fun descriptor(id: String = "lc", transition: LifecycleTransition = LifecycleTransition.REMOVE_EMPTY_GUARD,
        targets: List<LifecycleFixedTarget> = listOf(removeGuard), executor: SettlementExecutor? = null,
        namespace: LifecycleNamespacePostcondition? = null) = ControlLifecycleDescriptor(id, transition, targets, executor, namespace)
    fun command(input: ControlLifecycleDescriptor = descriptor(), life: OwnerTrackingLifetimeId = OwnerTrackingLifetimeId.issue()) =
        CommandRef(input.operationId, ControlCommandBody.Lifecycle(input), life)
    fun row(c: CommandRef): AppliedEvidence.Lifecycle {
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return AppliedEvidence.Lifecycle(c.id, c.ownerTrackingLifetimeId.value, input.transition, input.targets.map { it.target })
    }
    fun wire(c: CommandRef): String {
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return wire(input.transition.name, input.targets.joinToString(",") { target(it.target.kind.name, it.target.id, it.target.effect.name) },
            c.id, c.ownerTrackingLifetimeId.value)
    }
    fun raw(demand: String = "[]", evidence: String = "[]", hold: String = "[]", schema: Int = 2): Preferences = mutablePreferencesOf().apply {
        this[intPreferencesKey(ControlRecordKeys.SCHEMA)] = schema
        ControlRecordKeys.required(schema).forEach { this[ControlRecordKeys.payload(it)] = "[]" }
        this[ControlRecordKeys.payload(ControlKind.DEMAND)] = demand
        this[ControlRecordKeys.payload(ControlKind.HOLD)] = hold
        if (schema == 2) this[evidenceKey] = evidence
        this[OWNER_UID] = "A"
        this[USER_EPOCH] = "u"
        this[KRX_EPOCH] = "k"
        this[byteArrayPreferencesKey("lifecycle-external")] = byteArrayOf(0, 1, -1)
    }.toPreferences()
    fun read(raw: Preferences = raw()) = ControlRecordReader().read(raw) as ControlRecordRead.Supported
    fun parse(wire: String): ControlEvidenceRead = ControlEvidenceReader.read(ControlPayloadCodec().decode("[$wire]") as PayloadRead.Parsed)
    fun eligible(id: String) = "D2B5/$id: ineligible transition reached eligible boundary"
    fun retry(id: String) = "D2B5/$id: retry contract violated"
    fun atomic(id: String) = "D2B5/$id: atomic candidate contract violated"
}
