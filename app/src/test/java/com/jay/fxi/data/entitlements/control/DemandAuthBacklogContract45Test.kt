package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-fifth file — the descriptor boundary ControlLifecycleConfirmation.validDescriptor
 * (ControlLifecycle.kt:230–275) with one false sub-condition per input, closing the gap units whose fixtures made two
 * conditions false together: C28 W_removeEmptyGuard_nonGuard (a non-guard input gives target id "" against row id "r" and
 * a DemandV1 where the GUARD role needs a ScheduleGuardV1 — id and role both false), RecoverHoldR3 LC_sameNewIds (id
 * uniqueness together with the RECOVER_HOLD shape) and C3 S_noop (empty targets: shape and, for a GUARD-first transition,
 * roles).
 *
 * Independent truth vector, computed here in production order (LC:231–274): opId (LC:231) · shape (LC:232, the wire shapes
 * of REBIND_REQUESTS · REMOVE_EMPTY_GUARD · UPDATE_AUTH written as literals) · unique ids over targets+requiredUnchanged
 * (LC:233–234) · per target, by effect (LC:246–253): the node read as the target kind is Interpreted ("present"), its id
 * equals the target id, and its value matches the role; REMOVE after null · CREATE before null · REPLACE before ≠ after ·
 * requiredUnchanged per entry: REPLACE · facts(after) · before = after (LC:254–255) · roles for the transition (LC:256–273).
 * The facts guard is mirrored: when the node is absent or not interpreted, id and role are vacuously true (production
 * returns there). These fixtures use only REQUEST/GUARD in targets and requiredUnchanged; the HOLD and RECOVERY_INTENT branches
 * (LC:241–242) are outside this fixture set. requiredUnchanged roles are not restricted by transition. Twin per method: the same
 * transition with a valid descriptor — vector all true and the literal validDescriptor result true — then the negative with
 * only the target false, and the role.
 *
 * Not isolable through the named REMOVE_EMPTY_GUARD factory: it derives the target id from the guard it is given
 * (RemoveEmptyGuardTransition.kt:9–11), so a guard input always has a matching id and a non-guard input makes id and role
 * false together (the existing C28 test). The single conditions are therefore closed at this direct boundary.
 */
class DemandAuthBacklogContract45Test {
    private val confirmation = ControlLifecycleConfirmation(F.codec)
    private val empty = F.guard(auth = null)
    private val guardA get() = F.guard()
    private val guardB get() = F.guard(F.auth.copy(authStopped = false))
    private val oldReq = F.request(binding = 2, order = 50)
    private val newReq = F.request(order = 50)
    private fun t(id: String, effect: LifecycleEffect, role: LifecycleRole, before: ControlNode?, after: ControlNode?) =
        LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, id, effect), role, before, after)
    private fun d(transition: LifecycleTransition, vararg targets: LifecycleFixedTarget, op: String = "cmd",
        unchanged: List<LifecycleFixedTarget> = emptyList()) = ControlLifecycleDescriptor(op, transition, targets.toList(), requiredUnchanged = unchanged)

    private fun shape(tr: LifecycleTransition, ts: List<LifecycleTarget>): Boolean = ts.isNotEmpty() && when (tr) {
        LifecycleTransition.REBIND_REQUESTS -> ts.all { it.kind == ControlKind.DEMAND && it.effect == LifecycleEffect.REPLACE }
        LifecycleTransition.REMOVE_EMPTY_GUARD -> ts.size == 1 && ts[0].kind == ControlKind.DEMAND && ts[0].effect == LifecycleEffect.REMOVE
        LifecycleTransition.UPDATE_AUTH -> ts.size <= 2 && ts.all { it.kind == ControlKind.DEMAND && it.effect != LifecycleEffect.REMOVE }
        else -> error("not used here")
    }
    private fun roles(tr: LifecycleTransition, rs: List<LifecycleRole>): Boolean = when (tr) {
        LifecycleTransition.REBIND_REQUESTS -> rs.all { it == LifecycleRole.REQUEST }
        LifecycleTransition.REMOVE_EMPTY_GUARD -> rs == listOf(LifecycleRole.GUARD)
        LifecycleTransition.UPDATE_AUTH -> rs == listOf(LifecycleRole.GUARD) || rs == listOf(LifecycleRole.GUARD, LifecycleRole.REQUEST)
        else -> error("not used here")
    }
    private fun facts(tag: String, node: ControlNode?, t: LifecycleFixedTarget): List<Pair<String, Boolean>> {
        val v = node?.let { (ControlObligations.read(t.target.kind, it) as? ControlEntryRead.Interpreted)?.value }
        val role = when (t.role) { LifecycleRole.REQUEST -> v is DemandV1; LifecycleRole.GUARD -> v is ScheduleGuardV1
            LifecycleRole.HOLD -> v is RestoredHold; LifecycleRole.RECOVERY_INTENT -> v is RecoveryIntentV1 }
        return listOf("$tag.present" to (v != null), "$tag.id" to (v == null || v.id == t.target.id), "$tag.role" to (v == null || role))
    }
    private fun vec(input: ControlLifecycleDescriptor): List<Pair<String, Boolean>> {
        val all = input.targets + input.requiredUnchanged
        val per = input.targets.flatMapIndexed { i, x -> when (x.target.effect) {
            LifecycleEffect.REMOVE -> facts("t$i.before", x.before, x) + ("t$i.removeAfterNull" to (x.after == null))
            LifecycleEffect.CREATE -> listOf("t$i.createBeforeNull" to (x.before == null)) + facts("t$i.after", x.after, x)
            LifecycleEffect.REPLACE -> facts("t$i.before", x.before, x) + facts("t$i.after", x.after, x) +
                ("t$i.replaceChanged" to (x.before?.toPayloadEntry() != x.after?.toPayloadEntry()))
        } }
        return listOf("opId" to input.operationId.isNotEmpty(), "shape" to shape(input.transition, input.targets.map { it.target }),
            "unique" to (all.map { it.target.id }.toSet().size == all.size)) + per +
            input.requiredUnchanged.flatMapIndexed { i, x -> listOf("u$i.replace" to (x.target.effect == LifecycleEffect.REPLACE)) +
                facts("u$i.after", x.after, x) + ("u$i.same" to (x.before?.toPayloadEntry() == x.after?.toPayloadEntry())) } +
            listOf("roles" to roles(input.transition, input.targets.map { it.role }))
    }
    private fun no(id: String, twin: ControlLifecycleDescriptor, bad: ControlLifecycleDescriptor, only: String) {
        assertEquals("truth vector: normal descriptor", emptySet<String>(), V.falses(vec(twin)))
        assertTrue("positive twin: the valid descriptor passes", confirmation.validDescriptor(twin))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(bad)))
        assertFalse(F.eligible("Z.vd.$id"), confirmation.validDescriptor(bad))
    }

    // REMOVE_EMPTY_GUARD — twin = the named factory's descriptor for an empty guard
    private val reg get() = RemoveEmptyGuardPlan.prepare(empty).descriptor("cmd")
    @Test fun REG_opId() = no("REG.opId", reg, RemoveEmptyGuardPlan.prepare(empty).descriptor(""), "opId")
    @Test fun REG_factsId() = no("REG.factsId", reg,
        d(LifecycleTransition.REMOVE_EMPTY_GUARD, t("x", LifecycleEffect.REMOVE, LifecycleRole.GUARD, empty, null)), "t0.before.id")
    @Test fun REG_factsRole() = no("REG.factsRole", reg,
        d(LifecycleTransition.REMOVE_EMPTY_GUARD, t("r", LifecycleEffect.REMOVE, LifecycleRole.GUARD, F.request(), null)), "t0.before.role")
    @Test fun REG_removeAfterNull() = no("REG.removeAfterNull", reg,
        d(LifecycleTransition.REMOVE_EMPTY_GUARD, t("g", LifecycleEffect.REMOVE, LifecycleRole.GUARD, empty, empty)), "t0.removeAfterNull")
    @Test fun REG_present() = no("REG.present", reg,
        d(LifecycleTransition.REMOVE_EMPTY_GUARD, t("g", LifecycleEffect.REMOVE, LifecycleRole.GUARD, null, null)), "t0.before.present")

    // REBIND_REQUESTS — twin = one valid REPLACE (binding 2 → 3)
    private val reb get() = d(LifecycleTransition.REBIND_REQUESTS, t("r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, oldReq, newReq))
    @Test fun REB_emptyTargets() = no("REB.emptyTargets", reb, d(LifecycleTransition.REBIND_REQUESTS), "shape")
    @Test fun REB_duplicateId() = no("REB.duplicateId", reb, d(LifecycleTransition.REBIND_REQUESTS,
        t("r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, oldReq, newReq), t("r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, oldReq, newReq)), "unique")
    @Test fun REB_replaceUnchanged() = no("REB.replaceUnchanged", reb,
        d(LifecycleTransition.REBIND_REQUESTS, t("r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, oldReq, oldReq)), "t0.replaceChanged")

    // UPDATE_AUTH — twin = guard REPLACE + REQUEST CREATE
    private fun ua(createBefore: ControlNode?) = d(LifecycleTransition.UPDATE_AUTH,
        t("g", LifecycleEffect.REPLACE, LifecycleRole.GUARD, guardA, guardB), t("r", LifecycleEffect.CREATE, LifecycleRole.REQUEST, createBefore, newReq))
    @Test fun UA_createBeforeNull() = no("UA.createBeforeNull", ua(null), ua(oldReq), "t1.createBeforeNull")

    // requiredUnchanged — an effect already satisfied before the command (DemandAuthPlan passes `unchanged`); twin = REQUEST
    // target plus an unchanged guard entry (before = after)
    private fun rebU(entry: LifecycleFixedTarget) = d(LifecycleTransition.REBIND_REQUESTS,
        t("r", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, oldReq, newReq), unchanged = listOf(entry))
    private val keptGuard get() = t("g", LifecycleEffect.REPLACE, LifecycleRole.GUARD, guardA, guardA)
    @Test fun RU_changed() = no("RU.changed", rebU(keptGuard), rebU(t("g", LifecycleEffect.REPLACE, LifecycleRole.GUARD, guardA, guardB)), "u0.same")
    @Test fun RU_effect() = no("RU.effect", rebU(keptGuard), rebU(t("g", LifecycleEffect.CREATE, LifecycleRole.GUARD, guardA, guardA)), "u0.replace")
    @Test fun RU_facts() = no("RU.facts", rebU(keptGuard), rebU(t("g", LifecycleEffect.REPLACE, LifecycleRole.REQUEST, guardA, guardA)), "u0.after.role")
}
