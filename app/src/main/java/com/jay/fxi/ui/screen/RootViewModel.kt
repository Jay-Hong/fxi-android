package com.jay.fxi.ui.screen

import androidx.lifecycle.ViewModel
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.IdentityRecoveryState
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Root's half of the access decision.
 *
 * Deliberately thin, and deliberately *not* RevenueCat. `SubscriptionManager.isPremium` is a local
 * purchase signal; I7 makes the server's `premium_active` the only authority for runtime access, so
 * Root reads the state machine that owns that answer and nothing else. The two can disagree — a
 * restored purchase the server has not confirmed is exactly the case D23 exists for — and Root must
 * follow the server.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val coordinator: PremiumAccessCoordinator,
    private val authTokenProvider: AuthTokenProvider
) : ViewModel() {
    /**
     * The grant together with the uid and auth generation it was decided for.
     *
     * Root reads auth and access from two flows that move independently, so the owner has to travel
     * with the grant: between Firebase reporting a new user and the coordinator being told, the
     * previous user's `PremiumConfirmed` is otherwise what Root would branch on.
     */
    val access: StateFlow<OwnedPremiumAccess> = coordinator.state

    /**
     * What a surface may say about identity work that has not finished. See [IdentityRecoveryState].
     *
     * `internal` because the state type is — a public member of this public class could not expose it.
     */
    internal val identityRecovery: StateFlow<IdentityRecoveryState> = coordinator.identityRecovery

    /**
     * Records a re-check for the hold named by [holdId].
     *
     * The answer is whether a wake was recorded, never how the re-check went. `false` covers no hold,
     * a different hold and a wake already standing; a surface does not branch on it, because anything
     * that actually changed reaches it through [identityRecovery].
     *
     * Suspending, so the caller's scope owns it and a test can call and await it directly.
     */
    internal suspend fun requestRecheck(holdId: Long): Boolean = coordinator.retryPersistence(holdId)

    fun accessForSession(signedInUid: String?, ownedAccess: OwnedPremiumAccess): PremiumAccessState {
        // Root calls this on every recomposition driven by its existing auth/access StateFlows.
        // Read the non-suspend live fence here, without remember or another cached Flow: auth may
        // already have advanced while the collected grant still belongs to the previous session.
        val live = authTokenProvider.currentIdentityFence()
        return if (signedInUid != null && live != null &&
            live.uid == signedInUid && ownedAccess.uid == signedInUid &&
            ownedAccess.authGeneration == live.authGeneration
        ) {
            ownedAccess.state
        } else {
            PremiumAccessState.NoGrant
        }
    }
}
