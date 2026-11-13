package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.confirmsPremiumFor

/** Null preserves the stored projection when the caller cannot reconstitute this session. */
internal fun reconciledPushIntent(
    capability: NotificationCapability,
    ownerIsCurrent: Boolean,
    premiumConfirmed: Boolean,
    held: Boolean
): Boolean? = when {
    capability != NotificationCapability.GRANTED -> false
    !ownerIsCurrent || held -> null
    else -> premiumConfirmed
}

internal class PushIntentReconciler(
    private val capability: () -> NotificationCapability,
    private val currentOwner: () -> AuthIdentityFence?,
    private val premium: () -> OwnedPremiumAccess,
    private val held: () -> Boolean,
    private val readIntent: () -> Boolean,
    private val writeIntent: (Boolean) -> Unit,
    private val register: suspend (AuthIdentityFence, String?) -> RegisterOutcome
) {
    suspend fun reconcile(owner: AuthIdentityFence?, knownToken: String? = null): RegisterOutcome? {
        val observedCapability = capability()
        val ownerIsCurrent = owner != null && currentOwner() == owner
        val premiumConfirmed = premium().confirmsPremiumFor(owner)
        val stored = readIntent()
        // Read the teardown hold after the other inputs, immediately before projecting.
        val projected = reconciledPushIntent(observedCapability, ownerIsCurrent, premiumConfirmed, held())
        if (projected != null && projected != stored) writeIntent(projected)
        if (owner == null || !ownerIsCurrent) return null
        return register(owner, knownToken)
    }
}
