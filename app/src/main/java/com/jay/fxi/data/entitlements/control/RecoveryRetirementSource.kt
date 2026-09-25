package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import java.util.Collections

/** Verified archived source only (HOLD or RECOVERY_INTENT). No arbitrary owner/axis/epoch tuple. */
internal sealed interface RecoveryRetirementSource {
    val original: ControlNode
    val ownerUid: String?
    val axes: Set<PurgeScope>
    /** Defined only for axis in [axes]; null is an exact source fact, never an absent axis. */
    fun targetEpoch(axis: PurgeScope): String?
}

/** The archived intent's single axis is never padded into a synthetic full fence. */
internal class IntentRecoverySource private constructor(
    override val original: ControlNode,
    val intent: RecoveryIntentV1
) : RecoveryRetirementSource {
    override val ownerUid: String? get() = intent.ownerUid
    override val axes: Set<PurgeScope> = Collections.singleton(intent.axis)
    override fun targetEpoch(axis: PurgeScope): String? {
        require(axis in axes)
        return intent.targetEpoch
    }

    companion object {
        fun from(original: ControlNode): IntentRecoverySource? {
            val intent = ControlSchema.read(ControlKind.RECOVERY_INTENT, original) as? RecoveryIntentV1 ?: return null
            return IntentRecoverySource(original, intent)
        }
    }
}
