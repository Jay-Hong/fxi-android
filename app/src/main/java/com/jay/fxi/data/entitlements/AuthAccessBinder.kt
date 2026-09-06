package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The signed-in uid, as a callback stream.
 *
 * A seam, not an abstraction for its own sake: the production implementation is one
 * `FirebaseAuth.AuthStateListener`, and a test needs to drive transitions without Firebase.
 * Mirrors how [RecheckClock] and [EpochIdGenerator] are already injected.
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
 * calling back from there would close a Hilt dependency cycle. This binder depends on
 * `FirebaseAuth` only, which has no dependencies of its own.
 *
 * ### Three properties that are load-bearing
 *
 * **Dedup lives here.** [PremiumAccessCoordinator.onOwnerChanged] is not same-uid idempotent — it
 * bumps the decision generation, cancels the recheck schedule and resets the published state to
 * `NoGrant` before it even reaches the store. Firebase re-delivers the current user on every
 * listener registration, so without [boundUid] a live grant would be thrown away routinely.
 *
 * **One consumer, in emit order.** Owner-binding and sign-out are not commutative. Dispatching
 * each callback in its own coroutine lets a sign-out/sign-in pair land inverted, leaving a
 * signed-in user with `signOut()` applied last. The coordinator's mutex serialises the calls but
 * cannot recover the order they were emitted in, so the order is preserved here instead.
 *
 * **A cold start never synthesises a sign-out.** [boundUid] starts unbound, so a first `null`
 * observation does nothing. Dispatching `onSignedOut()` there would rotate both epochs and
 * journal a purge whenever a previous process left an owner bound — destroying exactly the
 * same-uid cold-start continuity the plan preserves.
 */
class AuthAccessBinder(
    private val coordinator: PremiumAccessCoordinator,
    private val scope: CoroutineScope,
    private val uidStream: AuthUidStream
) {
    /**
     * Unbounded because dropping an auth transition is not a recoverable outcome, and the real
     * traffic is a handful of events per process.
     */
    private val inbox = Channel<String?>(Channel.UNLIMITED)

    /** Confined to the single consumer, so it needs no synchronisation. */
    private var boundUid: String? = null

    /**
     * Starts consuming, then registers the stream.
     *
     * The purge resume runs before the loop rather than relying on [onOwnerChanged], which
     * resumes purges itself: on a signed-out cold start no owner change ever fires, and a journal
     * a previous process left behind would otherwise never be retried. On a signed-in cold start
     * this costs one redundant resume, which is a no-op when the journal is empty.
     */
    fun start() {
        scope.launch {
            coordinator.resumePendingPurges()
            for (uid in inbox) handle(uid)
        }
        // Firebase 24.0.1 posts the current user to a newly registered listener, so the initial
        // observation arrives from here — emitting one synthetically would only duplicate it.
        uidStream.observe(::onUidObserved)
    }

    /** Test seam. Production reaches this through the registered listener. */
    internal fun onUidObserved(uid: String?) {
        inbox.trySend(uid)
    }

    private suspend fun handle(uid: String?) {
        when {
            uid != null && uid != boundUid -> {
                boundUid = uid
                val boundGeneration = coordinator.onOwnerChanged(uid)
                // `onOwnerChanged` binds the owner and resets to NoGrant; it asks the server
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
                        requireGeneration = boundGeneration
                    )
                }
            }
            uid == null && boundUid != null -> {
                boundUid = null
                coordinator.onSignedOut()
            }
        }
    }
}
