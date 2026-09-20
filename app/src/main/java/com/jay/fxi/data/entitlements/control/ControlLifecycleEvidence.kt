package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Closed schema-2 vocabulary. Deferred floor/session transitions have no reserved wire values. */
enum class LifecycleTransition {
    REBIND_REQUESTS, SETTLE_QUERY, UPDATE_AUTH, END_AUTH_BINDING,
    REMOVE_EMPTY_GUARD, RECOVER_HOLD, RECOVER_INTENT
}

enum class LifecycleEffect { REMOVE, REPLACE, CREATE }

/** Position comes from the containing list; unlike MUTATIONS there is no wire index. */
data class LifecycleTarget(val kind: ControlKind, val id: String, val effect: LifecycleEffect)

internal object ControlLifecycleEvidence {
    private val names = setOf("version", "commandId", "ownerTrackingLifetimeId", "kind", "transition", "targets")
    private val targetNames = setOf("kind", "id", "effect")

    // Common version, ID and tracker UUID checks are performed by ControlEvidenceReader.
    fun parse(node: ControlNode, commandId: String, lifetime: String): AppliedEvidence.Lifecycle? {
        if (node.hasNamesBeyond(names)) return null
        val transition = (node.enumName("transition", LifecycleTransition.entries) as? FieldRead.Present)?.value ?: return null
        val raw = node.toPayloadEntry().fields["targets"] as? JsonArray ?: return null
        val targets = raw.map { element ->
            val fields = element as? JsonObject ?: return null
            val target = ControlNode.of(fields)
            if (target.hasNamesBeyond(targetNames)) return null
            val kind = (target.enumName("kind", ControlKind.entries) as? FieldRead.Present)?.value ?: return null
            val id = (target.text("id") as? FieldRead.Present)?.value ?: return null
            if (id.isEmpty()) return null
            val effect = (target.enumName("effect", LifecycleEffect.entries) as? FieldRead.Present)?.value ?: return null
            LifecycleTarget(kind, id, effect)
        }
        if (targets.map { it.id }.toSet().size != targets.size) return null
        if (!validShape(transition, targets)) return null
        return AppliedEvidence.Lifecycle(commandId, lifetime, transition, targets)
    }

    /** Wire structure only. REQUEST/guard roles belong to the fixed descriptor and named writers. */
    fun validShape(transition: LifecycleTransition, targets: List<LifecycleTarget>): Boolean {
        if (targets.isEmpty()) return false
        fun LifecycleTarget.demand() = kind == ControlKind.DEMAND
        fun LifecycleTarget.changedDemand() = demand() && effect != LifecycleEffect.REMOVE
        return when (transition) {
            LifecycleTransition.REBIND_REQUESTS -> targets.all { it.demand() && it.effect == LifecycleEffect.REPLACE }
            LifecycleTransition.SETTLE_QUERY -> {
                if (!targets.all { it.demand() }) return false
                val suffix = targets.dropWhile { it.effect == LifecycleEffect.REMOVE }
                if (suffix.size > 2) return false
                if (suffix.any { it.effect == LifecycleEffect.REMOVE }) return false
                true
            }
            LifecycleTransition.UPDATE_AUTH -> targets.size <= 2 && targets.all { it.changedDemand() }
            LifecycleTransition.END_AUTH_BINDING -> targets.all { it.demand() && it.effect == LifecycleEffect.REPLACE }
            LifecycleTransition.REMOVE_EMPTY_GUARD -> targets.size == 1 && targets.first().demand() &&
                targets.first().effect == LifecycleEffect.REMOVE
            LifecycleTransition.RECOVER_HOLD -> {
                if (targets.first().kind != ControlKind.HOLD || targets.first().effect != LifecycleEffect.REMOVE) return false
                if (targets.size > 3) return false
                if (!targets.drop(1).all { it.changedDemand() }) return false
                // With two optional rows the first can only be the new REQUEST.
                if (targets.size == 3 && targets[1].effect != LifecycleEffect.CREATE) return false
                true
            }
            LifecycleTransition.RECOVER_INTENT -> {
                if (targets.first().kind != ControlKind.RECOVERY_INTENT || targets.first().effect != LifecycleEffect.REMOVE) return false
                if (targets.size > 2) return false
                targets.drop(1).all { it.demand() && it.effect == LifecycleEffect.CREATE }
            }
        }
    }

    fun matches(input: ControlLifecycleDescriptor, row: AppliedEvidence.Lifecycle): Boolean {
        if (row.transition != input.transition) return false
        if (row.targets.size != input.targets.size) return false
        for ((index, fixed) in input.targets.withIndex()) {
            val actual = row.targets[index]
            if (actual.kind != fixed.target.kind) return false
            if (actual.id != fixed.target.id) return false
            if (actual.effect != fixed.target.effect) return false
        }
        return true
    }
}
