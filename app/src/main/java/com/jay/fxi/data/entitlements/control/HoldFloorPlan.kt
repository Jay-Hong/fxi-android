package com.jay.fxi.data.entitlements.control

/** Captured once at prepare. None of these facts is computed from the proposed guard. */
internal data class HoldFloorInput(val hold: ControlNode, val guard: ControlNode?, val mergeNow: BootReading,
    val origin: LifetimeId, val newGuardId: String)

/** Pure, immutable preparation; does not remove HOLD, save storage, or expose runtime authority.
 * 5d must recheck [preimageProblem], validate its whole atomic candidate, and confirm storage before
 * using this anchor at runtime. Retrying uses this same instance and its fixed mergeNow/newGuardId.
 */
internal class HoldFloorPlan private constructor(val input: HoldFloorInput, val guardAfter: ControlNode?) {
    fun preimageProblem(read: ControlRecordRead.Supported): ConflictReason? {
        val hold = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val source = LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, hold.id, LifecycleEffect.REMOVE),
            LifecycleRole.HOLD, input.hold, null)
        ControlLifecycleBoundary.preimage(read, source)?.let { return it }
        if (input.guard != null) {
            val before = guard(input.guard)!!
            val target = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, before.id, LifecycleEffect.REPLACE),
                LifecycleRole.GUARD, input.guard, guardAfter)
            ControlLifecycleBoundary.preimage(read, target)?.let { return it }
        } else if (hold.floor != null) {
            if (read.arrays.getValue(ControlKind.DEMAND).entries.any {
                (it as? ControlEntryRead.Interpreted)?.value is ScheduleGuardV1
            }) return ConflictReason.TargetChanged // HF.guardRace
            if (!ControlLifecycleBoundary.createIdAvailable(read, input.newGuardId)) return ConflictReason.IdCollision // HF.idRace
        }
        return null
    }

    companion object {
        fun prepare(input: HoldFloorInput): HoldFloorPlan? {
            val source = ControlSchema.read(ControlKind.HOLD, input.hold) as? RestoredHold ?: return null
            val before = input.guard?.let { guard(it) ?: return null }
            if (input.mergeNow.bootId == "") return null // HF.boot
            if (input.mergeNow.elapsedMillis < 0) return null // HF.elapsed
            if (input.origin.value.isEmpty()) return null // HF.origin
            if (source.floor == null) return HoldFloorPlan(input, input.guard)
            if (before == null && !validNewGuardId(input.newGuardId)) return null // HF.id
            val oldRemaining = before?.floor?.remainingAt(input.mergeNow) ?: 0
            val sourceRemaining = source.floor.remainingAt(input.mergeNow)!!
            val wait = maxOf(oldRemaining, sourceRemaining) // F07.max
            val floor = FloorV1(input.mergeNow.bootId, input.mergeNow.elapsedMillis, wait, input.origin)
            val after = guardNode(input.guard, before?.id ?: input.newGuardId, before?.auth, floor)
            if (!validCandidate(input, after)) return null // HF.candidate
            return HoldFloorPlan(input, after)
        }

        internal fun validNewGuardId(id: String): Boolean = id.isNotEmpty()

        /** Independent postcondition, including a schema-valid incomplete builder candidate.
         * Read HOLD and old guard again from fixed source facts; never trust guardAfter/max caches.
         */
        fun validCandidate(input: HoldFloorInput, candidate: ControlNode?): Boolean {
            val source = ControlSchema.read(ControlKind.HOLD, input.hold) as? RestoredHold ?: return false
            if (source.floor == null) return candidate?.toPayloadEntry()?.fields?.toString() == input.guard?.toPayloadEntry()?.fields?.toString() // HF.noFloor
            val before = input.guard?.let { guard(it) ?: return false }
            val after = candidate?.let(::guard) ?: return false // HF.guard
            if (after.id != (before?.id ?: input.newGuardId)) return false // HF.guardId
            val oldAuth = input.guard?.toPayloadEntry()?.fields?.get("auth")?.toString()
            if (candidate.toPayloadEntry().fields["auth"]?.toString() != oldAuth) return false // HF.auth
            val floor = after.floor ?: return false // HF.floor
            val left = before?.floor?.remainingAt(input.mergeNow) ?: 0
            val right = source.floor.remainingAt(input.mergeNow) ?: return false
            if (floor.waitMillis < left) return false // HF.oldWait
            if (floor.waitMillis < right) return false // HF.sourceWait
            if (floor.waitMillis > maxOf(left, right)) return false // HF.exactMax
            if (floor.anchorBootId != input.mergeNow.bootId) return false // HF.anchorBoot
            if (floor.anchorElapsedMillis != input.mergeNow.elapsedMillis) return false // HF.anchorElapsed
            if (floor.originLifetimeId != input.origin) return false // HF.floorOrigin
            return true
        }
    }
}
