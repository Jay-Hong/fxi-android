package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure ID linkage, encoding and candidate validation; owns no history or storage. */
internal object ControlAppliedEvidence {
    fun own(read: ControlRecordRead.Supported, command: CommandRef): AppliedEvidence? =
        (read.metadata as? ControlMetadataRead.V2)?.evidence?.entries
            ?.filterIsInstance<ControlEvidenceEntryRead.Interpreted>()
            ?.singleOrNull { it.value.commandId == command.id }?.value

    fun hasOpaqueOwn(read: ControlRecordRead.Supported, command: CommandRef): Boolean =
        (read.metadata as? ControlMetadataRead.V2)?.evidence?.entries.orEmpty().any {
            val entry = (it as? ControlEvidenceEntryRead.Uninterpretable)?.original as? PayloadEntry.Obj
            (entry?.let { obj -> ControlNode.of(obj.fields).text("commandId") } as? FieldRead.Present)?.value == command.id
        }

    fun matches(command: CommandRef, tracked: TrackedControlCommand, row: AppliedEvidence): Boolean =
        matches(command, checkNotNull(command.captureStateAndBody().body), tracked, row)

    fun matches(command: CommandRef, body: ControlCommandBody, tracked: TrackedControlCommand?, row: AppliedEvidence): Boolean {
        if (row.ownerTrackingLifetimeId != command.ownerTrackingLifetimeId.value) return false
        if (row.commandId != command.id) return false
        when (body) {
            is ControlCommandBody.Lifecycle -> {
                if (row !is AppliedEvidence.Lifecycle) return false
                if (!ControlLifecycleEvidence.matches(body.input, row)) return false
            }
            is ControlCommandBody.RotateAndSettle -> {
                if (row !is AppliedEvidence.Rotation) return false
                if (row.sealIds != body.input.seals.map { it.id }) return false
                if (row.demandId != body.input.demandId) return false
            }
            is ControlCommandBody.Handover -> when (val input = body.input) {
                is RetiredNamespaceSettlement -> {
                    if (row !is AppliedEvidence.Settlement) return false
                    if (row.transition != HandoverSettlementTransition.RETIRED_NAMESPACE) return false
                    val seal = (ControlObligations.read(ControlKind.SEAL, input.target) as? ControlEntryRead.Interpreted)?.value as? SealV1
                        ?: return false
                    if (row.sealIds != listOf(seal.id)) return false
                    if (row.demandId != input.demandId) return false
                }
                is CurrentNullSettlement -> {
                    if (row !is AppliedEvidence.Settlement) return false
                    if (row.transition != HandoverSettlementTransition.CURRENT_NULL) return false
                    if (row.sealIds != input.targets.map { it.seal.id }) return false
                    if (row.demandId != input.demandId) return false
                }
                is RetiredNullSettlement -> {
                    if (row !is AppliedEvidence.Settlement) return false
                    if (row.transition != HandoverSettlementTransition.RETIRED_NULL) return false
                    if (row.sealIds != input.effectiveIds) return false
                    if (row.demandId != null) return false
                }
            }
            is ControlCommandBody.Mutations -> {
                if (row !is AppliedEvidence.Mutations) return false
                if (row.targets.size != body.actions.size) return false
                for ((index, action) in body.actions.withIndex()) {
                    val fixed = tracked?.targets?.get()?.get(index) ?: return false
                    val target = row.targets[index]
                    if (target.index != index) return false
                    if (target.kind != action.kind) return false
                    if (target.id != fixed.id) return false
                    if (target.joined != fixed.joined) return false
                    val canWrite = !fixed.joined && when (action) {
                        is ControlMutation.Add -> true
                        is ControlMutation.Edit -> (action.changed as? ControlWriteResult.Written)?.node
                            ?.toPayloadEntry() != action.before.toPayloadEntry()
                    }
                    if (target.written && !canWrite) return false
                }
            }
        }
        // A live attempt also retains the exact per-target changes it requested. Previous-lifetime
        // checkpoints prove fixed postconditions, not the historical write flags of an absent owner.
        return tracked?.expectedApplied?.let { node(it) == node(row) } ?: true
    }

    fun append(candidate: MutablePreferences, read: ControlRecordRead.Supported, row: AppliedEvidence,
        codec: ControlPayloadCodec): RejectionReason? {
        val metadata = read.metadata as? ControlMetadataRead.V2
            ?: return RejectionReason.InvalidRequest("schema 2 evidence required")
        val entries = metadata.evidence.entries.map {
            when (it) {
                is ControlEvidenceEntryRead.Interpreted -> it.original.toPayloadEntry()
                is ControlEvidenceEntryRead.Uninterpretable -> it.original
            }
        } + PayloadEntry.Obj(node(row))
        val encoded = try { codec.encode(entries) } catch (_: IllegalArgumentException) {
            return RejectionReason.InvalidRequest("evidence violates codec envelope constraints")
        }
        when (encoded) {
            is PayloadWrite.TooLarge -> return RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE, encoded.bytes, encoded.limit)
            is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = encoded.text
        }
        return null
    }

    fun node(row: AppliedEvidence): JsonObject {
        val fields = linkedMapOf(
            "version" to JsonPrimitive(2), "commandId" to JsonPrimitive(row.commandId),
            "ownerTrackingLifetimeId" to JsonPrimitive(row.ownerTrackingLifetimeId)
        )
        val extra = when (row) {
            is AppliedEvidence.Lifecycle -> mapOf(
                "kind" to JsonPrimitive("CONTROL_LIFECYCLE"), "transition" to JsonPrimitive(row.transition.name),
                "targets" to JsonArray(row.targets.map {
                    JsonObject(linkedMapOf("kind" to JsonPrimitive(it.kind.name), "id" to JsonPrimitive(it.id),
                        "effect" to JsonPrimitive(it.effect.name)))
                }))
            is AppliedEvidence.Mutations -> mapOf(
                "kind" to JsonPrimitive("MUTATIONS"), "targets" to JsonArray(row.targets.map {
                    JsonObject(linkedMapOf("index" to JsonPrimitive(it.index), "kind" to JsonPrimitive(it.kind.name),
                        "id" to JsonPrimitive(it.id), "joined" to JsonPrimitive(it.joined), "written" to JsonPrimitive(it.written)))
                }))
            is AppliedEvidence.Settlement -> mapOf("kind" to JsonPrimitive("SETTLEMENT"),
                "transition" to JsonPrimitive(row.transition.name),
                "sealIds" to JsonArray(row.sealIds.map(::JsonPrimitive)), "demandId" to JsonPrimitive(row.demandId))
            is AppliedEvidence.Rotation -> mapOf("kind" to JsonPrimitive("ROTATION"),
                "sealIds" to JsonArray(row.sealIds.map(::JsonPrimitive)), "demandId" to JsonPrimitive(row.demandId))
        }
        return JsonObject(fields + extra)
    }
}
