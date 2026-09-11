package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
    private companion object {
        /** Barriers one recovery run may re-enqueue after RECAPTURE or NOT_READY, together. */
        const val MAX_BARRIER_REENTRIES = 8
    }

    private sealed interface Item {
        class Observed(val fence: AuthIdentityFence?) : Item

        /** A caller waits on it; failing it is how the consumer tells that caller it stopped. */
        sealed interface Request : Item {
            fun fail(failure: Throwable)
        }

        class PrepareSignOut(val fence: AuthIdentityFence, val reply: CompletableDeferred<SignOutStart>) : Request {
            override fun fail(failure: Throwable) {
                reply.completeExceptionally(failure)
            }
        }

        class RecoveryBarrier(
            val ticket: SignOutTicket,
            val candidate: AuthIdentityFence?,
            val reply: CompletableDeferred<BarrierOutcome>
        ) : Request {
            override fun fail(failure: Throwable) {
                reply.completeExceptionally(failure)
            }
        }
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
            // A cancelled receive can remove a request before the consumer gets to serve it.
            (item as? Item.Request)?.fail(IllegalStateException("identity consumer stopped"))
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
                        is Item.PrepareSignOut ->
                            serve(item) { item.reply.complete(coordinator.prepareSignOut(item.fence)) }
                        is Item.RecoveryBarrier -> serve(item) { runBarrier(item) }
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
            (left as? Item.Request)?.fail(stopped)
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

    /**
     * Carries recovery of attempt [ticket] as far as one run can, and says where it stopped.
     *
     * A run limits its own retries: it reads back at most one unresolved edit — its steps and its
     * barrier waits share that — and after [MAX_BARRIER_REENTRIES] RECAPTURE or NOT_READY results in
     * all it returns RETRY_LATER without re-entering. Whether a call completes still depends on the
     * store and the FIFO progressing, or on cancellation. Calling again is the caller's decision. Must
     * not be called from the identity consumer.
     * A barrier the run enqueued outlives it: returning or being cancelled does not withdraw it, the
     * consumer resumes it whenever admission reopens, and it fails only when the consumer stops.
     */
    internal suspend fun recoverSignOut(ticket: SignOutTicket): RecoveryOutcome {
        check(currentCoroutineContext()[Consumer] == null) { "a recovery run from the identity consumer" }
        val readBack = ReadBackBudget()
        var reentries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (coordinator.advanceRecovery(ticket, allowReadBack = readBack.left)) {
                RecoveryAdvance.RESOLVED -> readBack.spend()
                RecoveryAdvance.PROGRESSED -> Unit
                RecoveryAdvance.CLOSED -> return RecoveryOutcome.CLOSED
                RecoveryAdvance.DRIVER_OWNS -> return RecoveryOutcome.DRIVER_OWNS
                RecoveryAdvance.UNRESOLVED -> return RecoveryOutcome.UNRESOLVED
                RecoveryAdvance.INCONSISTENT -> return RecoveryOutcome.INCONSISTENT
                RecoveryAdvance.NEEDS_BARRIER -> {
                    // Captured before the barrier is enqueued, so whatever the capture published is queued
                    // ahead of it rather than adopted by it.
                    val candidate = coordinator.liveIdentity()
                    val reply = CompletableDeferred<BarrierOutcome>()
                    inbox.send(Item.RecoveryBarrier(ticket, candidate, reply))
                    val outcome = when (val waited = awaitBarrier(ticket, reply, readBack)) {
                        is Waited.Stopped -> return waited.outcome
                        is Waited.Replied -> waited.outcome
                    }
                    when (outcome.step) {
                        BarrierStep.RELEASED -> return RecoveryOutcome.FINISHED
                        BarrierStep.CLOSED -> return RecoveryOutcome.CLOSED
                        BarrierStep.DRIVER_OWNS -> return RecoveryOutcome.DRIVER_OWNS
                        BarrierStep.HOLD -> return RecoveryOutcome.HOLD
                        BarrierStep.CLEANUP_FAILED -> return RecoveryOutcome.CLEANUP_FAILED
                        BarrierStep.RECAPTURE, BarrierStep.NOT_READY ->
                            if (++reentries >= MAX_BARRIER_REENTRIES) return RecoveryOutcome.RETRY_LATER
                        BarrierStep.HELD -> error("the consumer judges a held barrier again before replying")
                    }
                }
            }
        }
    }

    private class ReadBackBudget {
        var left = true
            private set

        fun spend() {
            left = false
        }
    }

    private sealed interface Waited {
        class Replied(val outcome: BarrierOutcome) : Waited
        class Stopped(val outcome: RecoveryOutcome) : Waited
    }

    /**
     * Waits for the barrier's reply while watching the attempt, because an edit failing on the FIFO —
     * ahead of the barrier or in it — holds the consumer until someone outside reads it back.
     */
    private suspend fun awaitBarrier(
        ticket: SignOutTicket,
        reply: CompletableDeferred<BarrierOutcome>,
        readBack: ReadBackBudget
    ): Waited {
        var seen = -1L
        while (true) {
            val signal = coroutineScope {
                val watcher = async { coordinator.awaitAttemptHeldOrGone(ticket, seen) }
                try {
                    select<AttemptSignal?> {
                        reply.onAwait { null }
                        watcher.onAwait { it }
                    }
                } finally {
                    watcher.cancel()
                }
            } ?: return Waited.Replied(reply.await())
            seen = signal.revision
            // Gone, so this run is over. The barrier remains consumer-owned. It may reply RELEASED if it
            // finished the attempt itself, or CLOSED if the attempt was already gone when judged; waiting
            // for either could mean waiting on another attempt's held event ahead of it.
            if (signal.ticket != ticket) return Waited.Stopped(RecoveryOutcome.CLOSED)
            // The signal is a hint: whether an edit is still waiting is judged under the lock, even with
            // no read-back left.
            when (coordinator.resolvePendingEdit(ticket, allowReadBack = readBack.left)) {
                EditResolution.RESOLVED -> readBack.spend()
                EditResolution.NOT_PENDING -> Unit
                EditResolution.STILL_UNKNOWN -> return Waited.Stopped(RecoveryOutcome.UNRESOLVED)
                EditResolution.INCONSISTENT -> return Waited.Stopped(RecoveryOutcome.INCONSISTENT)
            }
        }
    }

    /** An unexpected failure still ends the consumer, as any other failure there does. */
    private suspend fun serve(item: Item.Request, work: suspend () -> Unit) {
        try {
            work()
        } catch (failure: Throwable) {
            // Report the consumer's cancellation as a failure to an independently waiting caller.
            val reported = if (failure is CancellationException) {
                IllegalStateException("identity consumer stopped", failure)
            } else failure
            item.fail(reported)
            throw failure
        }
    }

    /** Held at the FIFO head while an edit is unresolved, like an identity event, and judged again with the same candidate. */
    private suspend fun runBarrier(item: Item.RecoveryBarrier) {
        var outcome = coordinator.completeRecovery(item.ticket, item.candidate)
        while (outcome.step == BarrierStep.HELD) {
            coordinator.awaitIdentityEventsAdmitted()
            outcome = coordinator.completeRecovery(item.ticket, item.candidate)
        }
        // Adopted before the next item: a null observed next ends whatever this barrier left bound.
        boundFence = outcome.completed
        // Set only when the seal was released onto a bound candidate. Launched rather than awaited, and
        // pinned to that binding, for the reasons [handle] gives.
        outcome.releasedGeneration?.let { generation ->
            scope.launch {
                coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = generation)
            }
        }
        item.reply.complete(outcome)
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
