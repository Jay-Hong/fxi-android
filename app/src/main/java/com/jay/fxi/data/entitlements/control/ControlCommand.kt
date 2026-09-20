package com.jay.fxi.data.entitlements.control

import java.util.Collections
import java.util.UUID

/** UUID-typed issuance prevents a test issuer accidentally introducing a new legacy-shaped id. */
internal fun interface ControlIdGenerator { fun next(): UUID }

/** Closed operations: no removal, seal settlement, epoch or journal mutation is expressible here. */
internal sealed class ControlMutation private constructor(val kind: ControlKind) {
    internal class Add private constructor(
        kind: ControlKind,
        val proposedId: String,
        val built: ControlWriteResult
    ) : ControlMutation(kind) {
        companion object {
            fun prepare(kind: ControlKind, issued: UUID, build: ControlBuilder.(String) -> Unit): Add {
                val id = issued.toString()
                val built = ControlObligations.build(kind) { build(id) }
                val valid = (built as? ControlWriteResult.Written)?.let {
                    val value = (ControlObligations.read(kind, it.node) as ControlEntryRead.Interpreted).value
                    value.id == id && (value !is SealV1 || value.settlement == null) && genericAddAllowed(value, it.node)
                } ?: false
                return Add(kind, id, if (valid) built else ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE))
            }
        }
    }

    internal class Edit private constructor(
        kind: ControlKind,
        val before: ControlNode,
        val changed: ControlWriteResult
    ) : ControlMutation(kind) {
        companion object {
            fun prepare(kind: ControlKind, expected: ControlNode, change: ControlEditor.() -> Unit): Edit {
                val changed = ControlObligations.editExisting(kind, expected, change)
                // The pure editor permits settlement; this slice has no authority to persist it.
                val nonSettlement = kind != ControlKind.SEAL || (changed is ControlWriteResult.Written &&
                    expected.toPayloadEntry() == changed.node.toPayloadEntry())
                val authPreserved = changed !is ControlWriteResult.Written || genericAuthUnchanged(kind, expected, changed.node)
                return Edit(kind, expected, if (nonSettlement && authPreserved) changed else ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE))
            }

            fun floor(expected: ControlNode, now: BootReading, waitMillis: Long, origin: LifetimeId): Edit =
                Edit(ControlKind.DEMAND, expected, ControlObligations.recordFloor(expected, now, waitMillis, origin))
        }
    }
}

/**
 * Fixed target plus the complete safe postcondition, not an execution receipt. Export before losing
 * the owner; an incomplete checkpoint cannot recover a lost seal adoption by searching its key.
 * No disk format or cross-process exactly-once protocol is provided by this in-memory value.
 * Import checks consistency with the prepared operation and any live owner history. A previous
 * owner still cannot authenticate that the chosen same-key seal was historically adopted; a valid
 * checkpoint proves only the current safe postcondition, never historical execution or settlement.
 */
internal class ControlCommandCheckpoint internal constructor(
    val command: CommandRef,
    targets: List<ControlCommandTarget?>,
    internal val confirmationRequested: Boolean
) {
    internal val targets: List<ControlCommandTarget?> = Collections.unmodifiableList(targets.toList())
}

internal class ControlCommandTarget(
    val id: String,
    val postcondition: ControlNode,
    val joined: Boolean
)

/** Named composite transitions cannot be smuggled into the generic obligation mutation API. */
internal sealed interface ControlCommandBody {
    class Mutations(actions: List<ControlMutation>) : ControlCommandBody {
        val actions: List<ControlMutation> = Collections.unmodifiableList(actions.toList())
    }
    class Lifecycle(val input: ControlLifecycleDescriptor) : ControlCommandBody
    class RotateAndSettle(val input: RotateAndSettleNamespaces) : ControlCommandBody

    /** Type declarations only: preparation and execution arrive with each named transition. */
    sealed interface Handover : ControlCommandBody { val input: HandoverSettlementInput }
    class SettleRetiredNamespace(override val input: RetiredNamespaceSettlement) : Handover
    class RotateAndSettleCurrentNull(override val input: CurrentNullSettlement) : Handover
    class SettleRetiredNull(override val input: RetiredNullSettlement) : Handover
}

internal fun genericAddAllowed(value: ControlObligationV1, node: ControlNode): Boolean {
    if (value is ScheduleGuardV1 && "auth" in node.names) return false // A10
    return true
}
internal fun genericAuthUnchanged(kind: ControlKind, before: ControlNode, after: ControlNode): Boolean {
    if (kind == ControlKind.DEMAND && ControlSchema.read(kind, before) is ScheduleGuardV1 &&
        before.toPayloadEntry().fields["auth"]?.toString() != after.toPayloadEntry().fields["auth"]?.toString()) return false // A09
    return true
}
