package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Wire oracles do not call the reclamation or evidence writer. */
internal object ReclamationFixtures {
    const val oldLife = "00000000-0000-0000-0000-000000000010"
    const val otherLife = "00000000-0000-0000-0000-000000000011"
    val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    val fenceKey = ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)

    fun mutation(command: String = "m", lifetime: String = oldLife, id: String = "d", kind: String = "DEMAND") =
        """{"version":2,"commandId":"$command","ownerTrackingLifetimeId":"$lifetime","kind":"MUTATIONS","targets":[{"index":0,"kind":"$kind","id":"$id","joined":false,"written":true}]}"""

    fun rotation(command: String = "op", lifetime: String = oldLife, ids: List<String> = listOf("s")) =
        """{"version":2,"commandId":"$command","ownerTrackingLifetimeId":"$lifetime","kind":"ROTATION","sealIds":[${ids.joinToString(",") { "\"$it\"" }}],"demandId":"rotation-demand"}"""

    fun settled(id: String = "s", axis: PurgeScope = PurgeScope.USER, operation: String = "op"): String {
        val before = FenceV1("A", "u", "k")
        val after = FenceV1("A", "next-u", "next-k")
        val seal = """{"id":"$id","kind":"NAMESPACE","ownerUid":"A","axis":"${axis.name}","epoch":"${before.epoch(axis)}"}"""
        val witness = SettlementEvidenceV1(operation, LifetimeId("origin"), StoreOp.BEGIN_ROTATION,
            before, after, JournalTargetV1("A", axis, before.epoch(axis)))
        return NamespaceSettlementFixtures.withWitness(seal, witness).toPayloadEntry().fields.toString()
    }

    fun raw(seals: String = "[${settled()}]", evidence: String = "[${rotation()}]", schema: Int = 2): Preferences =
        NamespaceSettlementFixtures.raw(seals, "[${ControlObligationFixtures.request},${ControlObligationFixtures.guard}]", schema)
            .toMutablePreferences().apply {
                if (schema == 2) this[evidenceKey] = evidence
                this[ControlRecordKeys.payload(ControlKind.HOLD)] = " [ ${ControlObligationFixtures.hold} ] "
                this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = " [ ${ControlObligationFixtures.recovery} ] "
                this[ControlStoreTestStorage.EXTRA] = "unrelated-value"
            }.toPreferences()

    fun ids(raw: String, name: String = "id"): List<String> =
        (Json.parseToJsonElement(raw) as JsonArray).map { (it as JsonObject).getValue(name).jsonPrimitive.content }

    fun sealNodes(raw: String): List<ControlNode> = (Json.parseToJsonElement(raw) as JsonArray).map { node(it.toString()) }
}
