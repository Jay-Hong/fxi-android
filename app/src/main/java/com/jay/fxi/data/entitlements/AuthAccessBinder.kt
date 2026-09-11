package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
    private sealed interface Item {
        class Observed(val fence: AuthIdentityFence?) : Item
        class PrepareSignOut(val fence: AuthIdentityFence, val reply: CompletableDeferred<SignOutStart>) : Item
    }

    /** Marks the consumer, so a request made from inside it fails instead of waiting on itself. */
    private class Consumer : AbstractCoroutineContextElement(Consumer) {
        companion object Key : CoroutineContext.Key<Consumer>
    }

    /**
     * Unbounded because dropping an auth transition is not a recoverable outcome, and the real
     * traffic is a handful of events per process.
     */
    private val inbox = Channel<Item>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { item ->
            // A cancelled receive can remove a request before the consumer gets to prepare it.
            if (item is Item.PrepareSignOut) {
                item.reply.completeExceptionally(IllegalStateException("identity consumer stopped"))
            }
        }
    )

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
        scope.launch(Consumer()) {
            try {
                coordinator.resumePendingPurges()
                for (item in inbox) {
                    when (item) {
                        // An open sign-out with an edit in flight holds identity events, in order,
                        // until that edit's outcome is known. The coordinator decides that under its
                        // own lock and hands a held event back, so the same event stays first.
                        is Item.Observed ->
                            while (!handle(item.fence)) coordinator.awaitIdentityEventsAdmitted()
                        is Item.PrepareSignOut -> prepare(item)
                    }
                }
            } finally {
                stopConsumer()
            }
        }.invokeOnCompletion { cause ->
            // Also covers cancellation that prevents the launch body from starting.
            stopConsumer(cause)
        }
        // AuthFenceStream replays the tracker's current fence when this subscriber registers.
        // The binder must not synthesize another initial observation.
        fenceStream.observe(::onFenceObserved)
    }

    private fun stopConsumer(cause: Throwable? = null) {
        val stopped = IllegalStateException("identity consumer stopped", cause)
        inbox.close(stopped)
        while (true) {
            val left = inbox.tryReceive().getOrNull() ?: break
            if (left is Item.PrepareSignOut) left.reply.completeExceptionally(stopped)
        }
    }

    /** Test seam. Production reaches this through the registered listener. */
    internal fun onFenceObserved(fence: AuthIdentityFence?) {
        inbox.trySend(Item.Observed(fence))
    }

    /**
     * Starts an app sign-out for [fence] after every identity event queued before this call, so the
     * coordinator judges it against the bindings those events produced.
     *
     * Must not be called from the identity consumer, which would wait on itself, nor while holding
     * the coordinator's lock. Cancelling the caller does not withdraw an enqueued request.
     * Execution still depends on the consumer reaching it; consumer termination fails pending replies.
     */
    internal suspend fun beginSignOut(fence: AuthIdentityFence): SignOutStart {
        check(currentCoroutineContext()[Consumer] == null) { "a sign-out requested from the identity consumer" }
        val reply = CompletableDeferred<SignOutStart>()
        inbox.send(Item.PrepareSignOut(fence, reply))
        return reply.await()
    }

    /** An unexpected failure still ends the consumer, as any other failure there does. */
    private suspend fun prepare(item: Item.PrepareSignOut) {
        try {
            item.reply.complete(coordinator.prepareSignOut(item.fence))
        } catch (failure: Throwable) {
            // Report the consumer's cancellation as a failure to an independently waiting caller.
            val reported = if (failure is CancellationException) {
                IllegalStateException("identity consumer stopped", failure)
            } else failure
            item.reply.completeExceptionally(reported)
            throw failure
        }
    }

    /** False when the coordinator held the event; nothing was applied and it must be retried. */
    private suspend fun handle(fence: AuthIdentityFence?): Boolean {
        when {
            fence != null && fence != boundFence -> {
                val boundDecisionGeneration = coordinator.onIdentityChanged(fence) ?: return false
                boundFence = fence
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
                // The session this null ends, so the coordinator rotates that uid's namespace and
                // not whoever the record happens to name.
                val ended = checkNotNull(boundFence)
                if (!coordinator.onSignedOut(ended)) return false
                boundFence = null
            }
        }
        return true
    }
}
