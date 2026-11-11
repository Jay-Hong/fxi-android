package com.jay.fxi.data.alerts

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import javax.inject.Inject
import javax.inject.Singleton

internal class AlertSettingsUse internal constructor(
    val identity: AuthIdentityFence,
    internal val fence: TopicSessionFence,
    internal val lifetime: TopicUseLifetime
)

@Singleton
internal class AlertSettingsAccess internal constructor(
    private val liveIdentity: () -> AuthIdentityFence?,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val uses: TopicUseAuthority,
    private val premium: () -> OwnedPremiumAccess,
    private val releaseAdmission: () -> Boolean,
    private val protectedAdmission: () -> Boolean
) {
    @Inject constructor(
        tokens: AuthTokenProvider,
        topic: TopicRuntimeOwner,
        uses: TopicUseAuthority,
        coordinator: PremiumAccessCoordinator,
        deletions: DeletionAdmissionStore
    ) : this(
        tokens::currentIdentityFence,
        topic.currentAccessFence,
        uses,
        { coordinator.state.value },
        { ReleaseAdmission.isOpen },
        GraphProtectedAdmission(tokens::currentIdentityFence, deletions)
    )

    /** Main. The graph may publish its fence later, or give it up for this process. */
    fun acquire(): AlertSettingsUse? {
        val identity = liveIdentity() ?: return null
        val fence = currentAccessFence() ?: return null
        val lifetime = uses.acquire(fence) ?: return null
        return AlertSettingsUse(identity, fence, lifetime).takeIf(::admits)
    }

    /** Any thread, non-blocking. A fresh acquire cannot validate a previously captured lifetime. */
    fun admits(use: AlertSettingsUse): Boolean =
        releaseAdmission() && liveIdentity() == use.identity && use.fence.identity == use.identity &&
            currentAccessFence() == use.fence && uses.admits(use.lifetime) &&
            premium().confirmsPremiumFor(use.identity) && protectedAdmission()
}
