package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CoroutineScope

/** β r2 §8.1: callers own every real dependency and fault; this factory only enables and checks PAC. */
internal data class KrxBetaPac(
    val coordinator: PremiumAccessCoordinator,
    val check: KrxOnPublicationCheck
) {
    private var installedSwitch: KrxContestSwitch? = null
    val sharedSwitch: KrxContestSwitch get() = checkNotNull(installedSwitch)
    constructor(coordinator: PremiumAccessCoordinator, check: KrxOnPublicationCheck, sharedSwitch: KrxContestSwitch) :
        this(coordinator, check) { installedSwitch = sharedSwitch }
}

internal fun krxBetaPac(
    source: EntitlementsSource,
    store: AccessEpochStore,
    userPurger: UserScopePurger,
    capabilityPurger: CapabilityScopePurger,
    scope: CoroutineScope,
    clock: RecheckClock,
    liveFence: () -> AuthIdentityFence?,
    orders: AccessOrderSequence,
    jitter: ProbeJitter = ProbeJitter.None,
    persistenceRetryDelayMillis: Long = 1_000L,
    beforeRecheckSettled: suspend (Long, Long) -> Unit = { _, _ -> },
    onLossReapprovalScheduled: (RefreshIntent, Long) -> Unit = { _, _ -> },
    beforeRejectionRecheckArmed: suspend () -> Unit = {}
): KrxBetaPac {
    val check = KrxOnPublicationCheck()
    val sharedSwitch = KrxContestSwitch(true)
    val coordinator = PremiumAccessCoordinator(
        source = source, store = store, userPurger = userPurger, capabilityPurger = capabilityPurger,
        scope = scope, clock = clock, liveFence = liveFence, orders = orders, jitter = jitter,
        persistenceRetryDelayMillis = persistenceRetryDelayMillis,
        beforeRecheckSettled = beforeRecheckSettled,
        onLossReapprovalScheduled = onLossReapprovalScheduled,
        beforeRejectionRecheckArmed = beforeRejectionRecheckArmed,
        krxContest = sharedSwitch
    )
    coordinator.onAccessPublishedForTest = check
    return KrxBetaPac(coordinator, check, sharedSwitch)
}
