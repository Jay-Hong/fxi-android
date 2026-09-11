package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The signed-in uid, as a callback stream.
 *
 * A seam, not an abstraction for its own sake: a test needs to drive transitions without
 * Firebase. Mirrors how [RecheckClock] and [EpochIdGenerator] are already injected.
 *
 * The production implementation is **no longer its own `FirebaseAuth.AuthStateListener`** — it is
 * an adapter over [com.jay.fxi.data.auth.AuthFenceStream], which drops the generation. Two
 * listeners watching Firebase independently is what that change removed; see the provider.
 */
fun interface AuthUidStream {
    fun observe(onUid: (String?) -> Unit)
}

/**
 * The only thing that turns auth transitions into [PremiumAccessCoordinator] calls.
 *
 * ### Why a binder and not a call from an existing observer
 *
 * `AuthViewModel` already listens, but it is Activity-scoped, and its `viewModelScope` dies at
 * `onCleared`. `FirebaseAuthTokenSource` also listens, but the coordinator reaches it through
 * `EntitlementsSource -> AuthenticatedApiClient -> AuthTokenProvider -> AuthTokenSource`, so
 * calling back from there would close a Hilt dependency cycle. This binder now depends on the
 * auth layer's fence stream instead — the same singleton the token source is, so one tracker
 * answers "which generation is this" for both.
 *
 * ### Three properties that are load-bearing
 *
 * **Dedup lives here, and it is keyed on the fence.** [PremiumAccessCoordinator.onIdentityChanged]
 * is not idempotent — it bumps the decision generation, cancels the recheck schedule and resets the
 * published state to `NoGrant` before it even reaches the store. [boundFence] makes the consumer
 * skip a non-null fence equal to its current value, avoiding another reset on a repeated delivery.
 * Registration replays the current fence once; generation changes carry different fences.
 * Keying on the **fence** rather than the bare uid is what makes a same-uid generation
 * change visible: `authGeneration` also advances on the explicit invalidation the tracked sign-in
 * path runs, and that transition produces no uid change at all.
 *
 * **One consumer, in emit order.** Owner-binding and sign-out are not commutative. Dispatching
 * each callback in its own coroutine lets a sign-out/sign-in pair land inverted, leaving a
 * signed-in user with `signOut()` applied last. The coordinator's mutex serialises the calls but
 * cannot recover the order they were emitted in, so the order is preserved here instead.
 *
 * **A cold start never synthesises a sign-out.** [boundFence] starts unbound, so a first `null`
 * observation does nothing. Dispatching `onSignedOut()` there would rotate both epochs and
 * journal a purge whenever a previous process left an owner bound — destroying exactly the
 * same-uid cold-start continuity the plan preserves.
 */
class AuthAccessBinder(
    private val coordinator: PremiumAccessCoordinator,
    private val scope: CoroutineScope,
    private val fenceStream: AuthFenceStream
) {
    /**
     * Unbounded because dropping an auth transition is not a recoverable outcome, and the real
     * traffic is a handful of events per process.
     */
    private val inbox = Channel<AuthIdentityFence?>(Channel.UNLIMITED)

    /** Confined to the single consumer, so it needs no synchronisation. */
    private var boundFence: AuthIdentityFence? = null

    /**
     * Starts consuming, then registers the stream.
     *
     * The purge resume runs before the loop rather than relying on [onIdentityChanged], which
     * resumes purges itself: on a signed-out cold start no owner change ever fires, and a journal
     * a previous process left behind would otherwise never be retried. On a signed-in cold start
     * this costs one redundant resume, which is a no-op when the journal is empty.
     */
    fun start() {
        scope.launch {
            coordinator.resumePendingPurges()
            for (fence in inbox) handle(fence)
        }
        // AuthFenceStream replays the tracker's current fence when this subscriber registers.
        // The binder must not synthesize another initial observation.
        fenceStream.observe(::onFenceObserved)
    }

    /** Test seam. Production reaches this through the registered listener. */
    internal fun onFenceObserved(fence: AuthIdentityFence?) {
        inbox.trySend(fence)
    }

    private suspend fun handle(fence: AuthIdentityFence?) {
        when {
            fence != null && fence != boundFence -> {
                boundFence = fence
                val boundDecisionGeneration = coordinator.onIdentityChanged(fence)
                // `onIdentityChanged` binds the owner and resets to NoGrant; it asks the server
                // nothing. D23 also says a cold-start grant can only come from a `fresh_premium`
                // answer, so without a query here an existing subscriber who merely restores a
                // login sits on the free surface forever — no purchase button is involved, so
                // nothing else would ever ask. This is the one query, issued by the one funnel
                // that already owns identity, rather than a second binding mechanism in Root.
                // The default `CALLER` origin is right: `.forcePremium` already bypasses the
                // client debounce, and the one thing still able to defer this is the server's own
                // `Retry-After` floor — a device-wide rate limit that a new sign-in does not lift.
                //
                // Launched rather than awaited. This funnel is single-consumer, so awaiting a
                // network call here would park every later identity event behind it — a sign-out
                // queued behind a hanging query is the same ordering defect S2 step 1 was about.
                // A query that outlives its identity cannot land: the coordinator fences late
                // answers against its own generation.
                // Pinned to the binding above. `launch` orders nothing against a sign-out that
                // arrives while this is still queued, and an unpinned query would then start under
                // the *next* generation — applying cleanly and re-arming a recheck for a session
                // that has ended.
                scope.launch {
                    coordinator.refresh(
                        RefreshIntent.FORCE_PREMIUM,
                        requireDecisionGeneration = boundDecisionGeneration
                    )
                }
            }
            fence == null && boundFence != null -> {
                boundFence = null
                coordinator.onSignedOut()
            }
        }
    }
}
