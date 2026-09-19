package com.jay.fxi.data.entitlements.control

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Schema 1 has no evidence envelope; it must not be mistaken for empty schema 2 evidence. */
sealed interface ControlMetadataRead {
    data object NotPresentV1 : ControlMetadataRead
    class V2(val evidence: ControlEvidenceRead, val scopeFence: ScopeFenceRead) : ControlMetadataRead {
        val hasUninterpretable: Boolean get() = evidence.hasUninterpretable || scopeFence.hasUninterpretable
    }
}

/** Reserved for a later format. Preserve every nonempty array without interpreting its entries. */
class ScopeFenceRead internal constructor(payload: PayloadRead.Parsed) {
    private val saved = payload.entries.map { ControlEntryRead.Uninterpretable(it) }
    val entries: List<PayloadEntry> get() = Collections.unmodifiableList(saved.map { it.original })
    val hasUninterpretable: Boolean get() = saved.isNotEmpty()
}

/** Applied records describe persisted ID connections, not inputs, causality or admission. */
sealed interface AppliedEvidence {
    val commandId: String
    val ownerTrackingLifetimeId: String

    class Mutations internal constructor(
        override val commandId: String,
        override val ownerTrackingLifetimeId: String,
        targets: List<AppliedTarget>
    ) : AppliedEvidence {
        val targets: List<AppliedTarget> = Collections.unmodifiableList(targets.toList())
    }

    class Rotation internal constructor(
        override val commandId: String,
        override val ownerTrackingLifetimeId: String,
        sealIds: List<String>,
        val demandId: String
    ) : AppliedEvidence {
        val sealIds: List<String> = Collections.unmodifiableList(sealIds.toList())
    }
}

data class AppliedTarget(val index: Int, val kind: ControlKind, val id: String, val joined: Boolean, val written: Boolean)

sealed interface ControlEvidenceEntryRead {
    class Interpreted internal constructor(val original: ControlNode, val value: AppliedEvidence) : ControlEvidenceEntryRead
    class Uninterpretable internal constructor(original: PayloadEntry) : ControlEvidenceEntryRead {
        private val saved = ControlEntryRead.Uninterpretable(original)
        val original: PayloadEntry get() = saved.original
    }
}

class ControlEvidenceRead internal constructor(entries: List<ControlEvidenceEntryRead>) {
    val entries: List<ControlEvidenceEntryRead> = Collections.unmodifiableList(entries.toList())
    val hasUninterpretable: Boolean get() = entries.any { it is ControlEvidenceEntryRead.Uninterpretable }
}

/** Pure closed-format parser. Duplicate readable command IDs invalidate every participating row. */
internal object ControlEvidenceReader {
    private val common = setOf("version", "commandId", "ownerTrackingLifetimeId", "kind")
    private val mutationNames = common + "targets"
    private val rotationNames = common + setOf("sealIds", "demandId")
    private val targetNames = setOf("index", "kind", "id", "joined", "written")
    private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun read(payload: PayloadRead.Parsed): ControlEvidenceRead {
        val nodes = payload.entries.map { (it as? PayloadEntry.Obj)?.let { entry -> ControlNode.of(entry.fields) } }
        val duplicates = nodes.mapNotNull { it?.textValue("commandId") }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return ControlEvidenceRead(payload.entries.mapIndexed { index, entry ->
            val node = nodes[index]
            val value = node?.let(::parse)
            if (value != null && value.commandId !in duplicates) {
                ControlEvidenceEntryRead.Interpreted(node, value)
            } else ControlEvidenceEntryRead.Uninterpretable(entry)
        })
    }

    private fun parse(node: ControlNode): AppliedEvidence? {
        if (node.integerValue("version") != 2L) return null
        val commandId = node.textValue("commandId") ?: return null
        if (commandId.isEmpty()) return null
        val owner = node.textValue("ownerTrackingLifetimeId") ?: return null
        if (!canonicalUuid.matches(owner)) return null
        return when (node.textValue("kind")) {
            "MUTATIONS" -> {
                if (node.hasNamesBeyond(mutationNames)) return null
                val raw = node.array("targets") ?: return null
                if (raw.isEmpty()) return null
                val targets = raw.mapIndexed { index, element ->
                    val obj = element as? JsonObject ?: return null
                    val target = ControlNode.of(obj)
                    if (target.hasNamesBeyond(targetNames)) return null
                    if (target.integerValue("index") != index.toLong()) return null
                    val kind = (target.enumName("kind", ControlKind.entries) as? FieldRead.Present)?.value ?: return null
                    val id = target.textValue("id") ?: return null
                    if (id.isEmpty()) return null
                    val joined = (target.flag("joined") as? FieldRead.Present)?.value ?: return null
                    val written = (target.flag("written") as? FieldRead.Present)?.value ?: return null
                    if (joined && written) return null
                    AppliedTarget(index, kind, id, joined, written)
                }
                if (targets.map { it.id }.toSet().size != targets.size) return null
                if (targets.none { it.written }) return null
                AppliedEvidence.Mutations(commandId, owner, targets)
            }
            "ROTATION" -> {
                if (node.hasNamesBeyond(rotationNames)) return null
                val raw = node.array("sealIds") ?: return null
                if (raw.size !in 1..2) return null
                val seals = raw.map { element ->
                    val id = element as? JsonPrimitive ?: return null
                    if (!id.isString) return null
                    if (id.content.isEmpty()) return null
                    id.content
                }
                if (seals.toSet().size != seals.size) return null
                val demand = node.textValue("demandId") ?: return null
                if (demand.isEmpty()) return null
                if (demand in seals) return null
                AppliedEvidence.Rotation(commandId, owner, seals, demand)
            }
            else -> null
        }
    }

    private fun ControlNode.textValue(name: String) = (text(name) as? FieldRead.Present)?.value
    private fun ControlNode.integerValue(name: String) = (integer(name) as? FieldRead.Present)?.value
    private fun ControlNode.array(name: String) = toPayloadEntry().fields[name] as? JsonArray
}
