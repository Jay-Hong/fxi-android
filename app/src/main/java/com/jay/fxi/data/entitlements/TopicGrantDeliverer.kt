package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGrantSink
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the deliverer reads from the issuer, and where it hands a refusal back (L-4e E3, E4a). */
internal interface TopicGrantIssuer {
    val accessRevisions: StateFlow<Long>

    suspend fun topicGrantResult(): TopicGrantResult

    /** Numbers a refusal as it is reported. Never suspends, never waits on the issuer's lock and never throws. */
    fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation

    /** Ends a reservation that will not be handed over. Short and synchronous, like [reserveRejection]. */
    fun abandonRejection(reservation: TopicRejectionReservation)

    /** Processes a reservation; however this returns, the issuer has ended it. */
    suspend fun onTopicRejected(reservation: TopicRejectionReservation)
}

/** [TopicGrantIssuer] over the coordinator, whose own members keep their visibility (L-4e E3). */
internal class PremiumAccessTopicGrantIssuer(private val coordinator: PremiumAccessCoordinator) : TopicGrantIssuer {
    override val accessRevisions: StateFlow<Long> get() = coordinator.accessRevisions

    override suspend fun topicGrantResult(): TopicGrantResult = coordinator.topicGrantResult()

    override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) =
        coordinator.reserveRejection(grant, reasons)

    override fun abandonRejection(reservation: TopicRejectionReservation) = coordinator.abandonRejection(reservation)

    override suspend fun onTopicRejected(reservation: TopicRejectionReservation) = coordinator.onTopicRejected(reservation)
}

/**
 * Carries the issuer's topic grant to one session, and that session's refusals back (L-4e E3). Dormant: nothing creates one yet.
 *
 * **Latest state, not every revision.** A revision, an auth transition, a retry falling due and the start each raise one conflated
 * signal; the consumer then reads the grant and its snapshot once. A result the issuer has already moved past is dropped and read
 * again.
 *
 * **An end is what the issuer recorded, not what a block looks like.** A grant goes over as `setAccess(true, fence, origin)`. With
 * none, the delivered grant is ended — `setAccess(false, …, NewContext)` — only when the user axis's end sequence has advanced since it
 * was delivered; anything else goes over as [TopicGrantSink.accessRevised] alone, so a hold never makes the session plan again.
 *
 * **A re-approval is passed on only when it continues what was delivered** (L-4e E5). The issuer's cause names the last issue alone,
 * and reads coalesce: a context change can sit between the grant delivered and a re-approval read now. So a grant goes over as
 * [TopicGrantOrigin.Reapproval] only when its cause is a refusal re-approval and the context it was issued for is the one the delivered
 * grant was issued for; a first grant, one after an end, and any other goes over as [TopicGrantOrigin.NewContext].
 *
 * **Nothing unexplained is taken as settled.** A pull that fails, and a missing grant the published snapshot does not explain (the
 * user axis allowed — a live identity read that failed looks like this), leave what was delivered as it is and are read again on a
 * growing wait; neither is turned into a withdrawal. Anything else settles the read, resetting the wait.
 *
 * Refusals are numbered by the issuer the moment the session reports them, then handed over one at a time in that order (L-4e E4a).
 * A reservation that cannot be handed over — reported after the deliverer stopped, left in the buffer when the scope ends, or
 * received after it ended — is abandoned here; once handed over, the issuer ends it. One the issuer fails on is skipped.
 */
internal class TopicGrantDeliverer(
    private val issuer: TopicGrantIssuer,
    private val sink: TopicGrantSink,
    private val fences: AuthFenceStream,
    private val scope: CoroutineScope,
    private val clock: TopicCommandClock,
    private val retryDelay: (attempt: Int) -> Duration = ::defaultRetryDelay,
    /** Must enqueue execution; Main.immediate and Unconfined are not supported here. */
    private val deliveryDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val started = AtomicBoolean(false)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val rejections = Channel<TopicRejectionReservation>(Channel.UNLIMITED) { issuer.abandonRejection(it) }

    init {
        // Tied to the scope, not to [start]: reports buffered by a deliverer that never started are abandoned when the scope ends.
        scope.coroutineContext[Job]?.invokeOnCompletion { rejections.cancel() }
    }

    /** Raised by an auth transition, and read once by the pull it causes. */
    private val fenceSignalled = AtomicBoolean(false)

    /** The retry that may still wake a pull, and the last one that fell due; a wait replaced or settled never matches. */
    private val retryTicket = AtomicLong(0L)
    private val retryFired = AtomicLong(-1L)

    // Confined to the consumer.
    private var pullOwed = true
    private var attemptedRevision: Long? = null
    private var attempt = 0
    private var retryTimer: Job? = null
    private var delivered: TopicSessionFence? = null

    /** The context [delivered] was issued for, read from the same result. */
    private var deliveredContext: TopicGrantContext? = null
    private var endSeen = 0L

    /** Starts once per instance; a stopped deliverer is not started again. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val run = scope.launch(deliveryDispatcher) {
            launch { issuer.accessRevisions.collect { signals.trySend(Unit) } }
            launch { forwardRejections() }
            // Captures the signal alone: the tracker keeps every listener, and must not keep this deliverer once its scope ends.
            val authSignal = fenceSignalled
            val wakeups = signals
            fences.observe {
                authSignal.set(true)
                wakeups.trySend(Unit)
            }
            signals.trySend(Unit)
            consumeGrants(this)
        }
        run.invokeOnCompletion {
            signals.close()
            rejections.cancel()
        }
    }

    /** Where the session's refusals go. Never blocks the session; a refusal after the deliverer stopped is numbered and abandoned. */
    fun forwardRejection(owner: TopicSessionFence, reasons: Map<String, TopicRejectionReason>) {
        val reservation = issuer.reserveRejection(owner.grant, reasons.values.toList())
        val sent = try {
            rejections.trySend(reservation).isSuccess
        } catch (failed: Throwable) {
            issuer.abandonRejection(reservation)
            throw failed
        }
        // A failed trySend does not call the channel's undelivered handler.
        if (!sent) issuer.abandonRejection(reservation)
    }

    private suspend fun consumeGrants(runScope: CoroutineScope) {
        try {
            for (signal in signals) {
                // A buffered item is handed over without suspending, so nothing else would notice the scope ended.
                currentCoroutineContext().ensureActive()
                val revision = issuer.accessRevisions.value
                val fenceMoved = fenceSignalled.getAndSet(false)
                val retryDue = retryFired.get() == retryTicket.get()
                if (!pullOwed && !fenceMoved && revision == attemptedRevision && !retryDue) continue
                pullOwed = false
                attemptedRevision = revision
                val result = try {
                    issuer.topicGrantResult()
                } catch (failed: Throwable) {
                    // The consumer's own cancellation ends it; anything else — a store's cancellation included — is a failed pull.
                    currentCoroutineContext().ensureActive()
                    scheduleRetry(runScope)
                    continue
                }
                currentCoroutineContext().ensureActive()
                if (result.snapshot.revision < issuer.accessRevisions.value) {
                    pullOwed = true
                    signals.trySend(Unit)
                    continue
                }
                deliver(result, runScope)
            }
        } finally {
            retryTimer?.cancel()
        }
    }

    private fun deliver(result: TopicGrantResult, runScope: CoroutineScope) {
        val snapshot = result.snapshot
        val ends = snapshot.lastUserEnd?.sequence ?: 0L
        val fence = result.fence
        if (fence != null) {
            val context = snapshot.facts.issuedFor
            val cause = result.cause
            val origin = if (cause is TopicGrantCause.RefusalReapproval && delivered != null && context != null &&
                context == deliveredContext
            ) {
                TopicGrantOrigin.Reapproval(cause.from)
            } else {
                TopicGrantOrigin.NewContext
            }
            sink.setAccess(true, fence, origin)
            sink.accessRevised()
            delivered = fence
            deliveredContext = context
            endSeen = ends
            settle()
            return
        }
        val ended = delivered
        if (ended != null && ends > endSeen) {
            sink.setAccess(false, ended, TopicGrantOrigin.NewContext)
            delivered = null
            deliveredContext = null
            endSeen = ends
        }
        sink.accessRevised()
        // A missing grant the published snapshot does not explain is not an answer yet.
        if (snapshot.facts.userAllowed) scheduleRetry(runScope) else settle()
    }

    private fun settle() {
        attempt = 0
        retryTimer?.cancel()
        retryTimer = null
        retryTicket.incrementAndGet()
    }

    private fun scheduleRetry(runScope: CoroutineScope) {
        attempt += 1
        retryTimer?.cancel()
        val ticket = retryTicket.incrementAndGet()
        val wait = retryDelay(attempt)
        retryTimer = runScope.launch {
            clock.sleep(wait)
            // A replaced timer already past its wait can still get here after the one that replaced it: never write an older ticket.
            retryFired.accumulateAndGet(ticket) { previous, fired -> maxOf(previous, fired) }
            signals.trySend(Unit)
        }
    }

    private suspend fun forwardRejections() {
        for (reservation in rejections) {
            if (!currentCoroutineContext().isActive) {
                issuer.abandonRejection(reservation)
                currentCoroutineContext().ensureActive()
            }
            try {
                issuer.onTopicRejected(reservation)
            } catch (failed: Throwable) {
                // The consumer's own cancellation ends it; a refusal the issuer failed on is not kept here (E4a).
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private companion object {
        fun defaultRetryDelay(attempt: Int): Duration =
            minOf(2.seconds * (1 shl (attempt - 1).coerceIn(0, 5)), 60.seconds)
    }
}
