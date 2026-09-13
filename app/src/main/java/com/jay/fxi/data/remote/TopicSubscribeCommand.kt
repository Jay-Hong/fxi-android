package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionError
import com.jay.fxi.data.remote.dto.TopicLease
import com.jay.fxi.data.remote.dto.TopicLeaseReadResult
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.data.remote.dto.readTopicLeases
import com.jay.fxi.data.remote.dto.reasonOrNull
import com.jay.fxi.domain.model.TopicCommandRetryPolicy
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicRequestDeadlines
import com.jay.fxi.domain.model.TopicRequestPolicy
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import com.jay.fxi.domain.model.TopicWholeRequestDecision
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import com.jay.fxi.domain.model.TopicWholeRequestMatrix
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.jay.fxi.domain.model.TopicAuthRefreshTicket
import com.jay.fxi.domain.model.TopicRequestTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A monotonic clock and the waits taken against it, as one injectable thing.
 *
 * Both halves together because they have to agree about what "later" means, and because the
 * production pair deliberately **disagrees**: `elapsedRealtime` counts deep sleep and `delay` does
 * not, so a wait can return with far more of the deadline gone than it asked for. That is the
 * behaviour [TopicRequestDeadlines] describes, and it is why every wait here is re-checked against
 * [nowMillis] on waking rather than trusted to have slept the right amount.
 */
interface TopicCommandClock {
    /** Monotonic, in the sense `TopicRequestDeadlines` means: it must not go backwards. */
    fun nowMillis(): Long

    suspend fun sleep(duration: Duration)
}

/**
 * The credential half of a command, narrowed from `AuthTokenProvider`.
 *
 * Two calls rather than one `token(force)`, because a forced refresh is not a second fetch: the
 * provider needs the snapshot that was **refused** in order to share one refresh between callers
 * and to tell a genuinely new credential from the same one handed back. Passing a bare string
 * would throw away the UID and the auth generation the provider fences on.
 */
interface TopicCommandCredentials {
    suspend fun currentSnapshot(): AuthSnapshot

    /** The one forced refresh [rejected] is owed, or `null` when a replay would be unsafe. */
    suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot?
}

/**
 * What this command is for, which decides both what it waits for and what its silence means.
 *
 * A first delivery that never arrives is worth one quiet revalidation; a revalidation that never
 * arrives is degraded. Carried on the command rather than inferred later, because by the time the
 * deadline fires the store no longer says which of the two started it.
 */
enum class TopicCommandPurpose {
    /** The connection's opening subscribe. Silence afterwards is worth one quiet revalidation. */
    FIRST_DELIVERY,

    /** D14's quiet question. Silence afterwards is degraded. */
    REVALIDATION,

    /**
     * Re-authenticating a lease, which is answered by the acknowledgement and nothing else.
     *
     * **This one does not wait out the delivery deadline.** The other two ask "is anything
     * arriving"; this one asks "may we still receive", and the server answering yes *is* the
     * answer — prices continuing to arrive would not have made the lease valid, and prices not
     * arriving would not make it invalid. Waiting anyway would keep the command alive on its
     * connection for forty-five seconds after it had finished. Found by review, which also caught
     * that reusing
     * [FIRST_DELIVERY] here is not the same as iOS's ordinary command: iOS ends its arbiter at the
     * acknowledgement, and this one would not.
     */
    LEASE_RENEWAL;

    /** Whether the acknowledgement leaves anything still to be waited for. */
    val watchesDelivery: Boolean get() = this != LEASE_RENEWAL
}


/**
 * The acknowledgement, handed over the moment it is validated.
 *
 * Separate from [TopicCommandOutcome] because the two answer different questions at different
 * times. The outcome cannot arrive until the delivery deadline has passed, and a lease is often
 * shorter than that — a `lease_duration_seconds` of one, or of zero meaning *re-authenticate now*,
 * is unusable by a caller that hears about it forty-five seconds later. A refusal is the same: a
 * `premium_required` on one topic should not wait on another topic's silence. Found by review.
 */
data class TopicCommandAcknowledgement(
    /** On the same monotonic clock the deadlines use. */
    val acknowledgedAtMillis: Long,
    val accepted: Set<String>,
    val rejected: Map<String, TopicRejectionReason>,
    val leases: List<TopicLease>
)

/**
 * Whether an answer this command has correlated may be applied now (L-4c).
 *
 * Asked at the moment of application, not when the answer arrived: the connection, the grant and the account can all have
 * moved since. [Admitted.atMillis] is the application instant supplied by the admission callback. The session reads it
 * after checking the live identity; the standalone default only reads the injected clock. An admitted ACK uses this
 * instant as its timestamp. It is not the arrival time, and the ACK deadline is not judged again against it.
 */
sealed interface TopicAnswerAdmission {
    data class Admitted(val atMillis: Long) : TopicAnswerAdmission
    data class Denied(val reason: TopicAnswerDenial) : TopicAnswerAdmission
}

enum class TopicAnswerDenial {
    /** Defensive: a connection ended first cancels its commands, so a running command does not normally see this. */
    ENDED_CONNECTION,

    /** Defensive: both latches end their connection as they are set. */
    LATCHED,
    IDENTITY_LOST,
    LEASE_EXPIRED
}

/**
 * An answer refused at application. A cancellation, and deliberately not [com.jay.fxi.data.auth.AuthIdentityChangedException]:
 * a lease that ran out or a connection that ended is not the account moving, and a report of one must not read as the other.
 */
class TopicAnswerNotAdmittedException(val reason: TopicAnswerDenial) :
    CancellationException("answer not admitted: $reason")

sealed interface TopicCommandOutcome {
    /**
     * The server answered, and nothing more is being waited for.
     *
     * Usually that means the delivery deadline of the send it answered has passed — but not
     * always: an acknowledgement that accepted nothing, or one whose accepted topics had all
     * already spoken, has nothing left to wait on and returns at once.
     *
     * [accepted] empty means every topic was refused — the reasons are in the store, and in the
     * acknowledgement that was handed over when it arrived. [silent] is the accepted topics that
     * had still said nothing **and are still wanted** — and it is empty whenever [purpose] did not
     * watch delivery, because a set measured without waiting is not evidence of silence.
     */
    data class Acknowledged(
        val accepted: Set<String>,
        val silent: Set<String>,
        val leases: List<TopicLease>,
        val purpose: TopicCommandPurpose
    ) : TopicCommandOutcome

    /**
     * A revalidation whose topics started speaking again before it needed to ask.
     *
     * Only a revalidation can end this way. Its whole question is whether the topic is still
     * alive, and a frame answers that question better than an acknowledgement would — so asking
     * again would be a subscribe sent to confirm something already confirmed. A first delivery
     * cannot: no acknowledgement means no lease and no confirmed subscription, and frames arriving
     * say nothing about the control request that went unanswered.
     */
    data class AlreadyDelivering(val topics: Set<String>) : TopicCommandOutcome

    /** No more sends, for [reason]. */
    data class Stopped(val reason: TopicWholeRequestDecision.Stop.Reason) : TopicCommandOutcome

    /** Nobody wants these topics any more; nothing further was sent. */
    data object Superseded : TopicCommandOutcome
}

/**
 * One subscribe command: up to three attempts, and the delivery deadline of the one that was
 * answered.
 *
 * `ANDROID_V2_PLAN.md §7 S3`. What lives here is a single command's lifetime — attempts, request
 * correlation, the two deadlines, and the shared budget. What deliberately does not is the socket,
 * reconnection, scheduling lease renewals, and the persistent-silence policy: those are ordered
 * against connection and account changes that only the session above can see.
 *
 * **Threading.** [deliver] is safe from any thread — it only hands a frame to a channel. Everything
 * else, including every read and write of [store] and the [onAcknowledged] callback, happens on the
 * coroutine that called [run], and this class adds no synchronisation of its own because
 * [TopicSubscriptionStateStore] has none. A caller that runs two commands against one store
 * concurrently has to serialise them itself.
 *
 * **Cancellation.** `run` is an ordinary suspending function and cancelling it is how a command is
 * abandoned, and it tidies away exactly the state its own tickets name: the open request, and any
 * credential recovery it started. Nothing else — a command torn down beside its replacement must
 * leave the replacement's request and the replacement's recovery standing, and before the tickets
 * existed the only way to guarantee that was to clean up nothing at all.
 * `AuthIdentityChangedException` is *not* caught either — it is a
 * `CancellationException`, the account moved out from under this command, and swallowing it to
 * return a value would restart work on behalf of an identity that is gone.
 * [TopicAnswerNotAdmittedException] is the same shape for an answer refused at application (L-4c): it unwinds before the
 * answer touches the store or reaches [onAcknowledged], and `run`'s cleanup still runs.
 */
class TopicSubscribeCommand(
    private val purpose: TopicCommandPurpose,
    private val store: TopicSubscriptionStateStore,
    private val credentials: TopicCommandCredentials,
    private val clock: TopicCommandClock,
    private val encode: (TopicSubscribeRequest) -> String,
    private val send: (String) -> Boolean,
    private val newRequestId: () -> String,
    private val jitter: () -> Double,
    /**
     * The topics still wanted, asked again before every send — including after the token wait.
     *
     * A function rather than a constructor set: acquiring a credential suspends and a retry can
     * wait seconds, and in that time the session above may have dropped one of these topics or the
     * whole intent. An empty answer ends the command as [TopicCommandOutcome.Superseded].
     */
    private val scope: () -> Set<String>,
    /** Called once, on an admitted acknowledgement, before the delivery deadline is waited out. */
    private val onAcknowledged: (TopicCommandAcknowledgement) -> Unit = {},
    /**
     * Asked immediately before each answer is applied to [store]: an acknowledgement, a classified request failure, and the
     * result of a credential refresh. [refusesPremium] is whether an acknowledgement refuses a topic this request sent with
     * `premium_required` — the one answer settled ahead of an expired lease. The default admits everything; a session must
     * supply its own.
     */
    private val admitAnswer: (refusesPremium: Boolean) -> TopicAnswerAdmission =
        { TopicAnswerAdmission.Admitted(clock.nowMillis()) }
) {
    private class Pending(
        val requestId: String,
        val topics: Set<String>,
        val baseline: Map<String, Long>,
        val deadlines: TopicRequestDeadlines,
        val credential: AuthSnapshot
    )

    private sealed interface Answer {
        data class Acked(val ack: SubscriptionAck, val leases: List<TopicLease>) : Answer
        data class Failed(val error: SubscriptionError) : Answer
        data object AckOverdue : Answer
    }

    /** What the scope says to do next, asked again at every point where sending is possible. */
    private sealed interface Sendable {
        data class Topics(val values: Set<String>) : Sendable
        data class Recovered(val values: Set<String>) : Sendable
        data object Nothing : Sendable
    }

    private enum class WaitResult { PROCEED, SUPERSEDED, REFUSED }

    /**
     * Control frames the session routes here, from any thread.
     *
     * Unbounded and buffered, which is what makes an answer that arrives *inside* [send] safe: the
     * request is registered before the send, so a re-entrant acknowledgement is already correlated
     * and simply waits in the channel until the send returns.
     */
    private val inbox = Channel<DecodedTopicFrame>(Channel.UNLIMITED)

    /** The open request this command owns, if it has one. Only it may end that request. */
    private var ticket: TopicRequestTicket? = null

    /** The credential recovery this command started, if it has one and it is still running. */
    private var authTicket: TopicAuthRefreshTicket? = null

    fun deliver(frame: DecodedTopicFrame) {
        inbox.trySend(frame)
    }

    /**
     * The admission for one application, or an unwind. Callers apply what it admits before suspending again: nothing may run
     * between this and the write it allows. A command already cancelled — by a teardown that ran first — never asks.
     */
    private suspend fun admit(refusesPremium: Boolean): Long {
        currentCoroutineContext().ensureActive()
        return when (val admission = admitAnswer(refusesPremium)) {
            is TopicAnswerAdmission.Admitted -> admission.atMillis
            is TopicAnswerAdmission.Denied -> throw TopicAnswerNotAdmittedException(admission.reason)
        }
    }

    suspend fun run(): TopicCommandOutcome =
        try {
            close(drive())
        } finally {
            // Reached on cancellation too, which is safe now that the tickets say whose these are:
            // a command torn down beside its replacement ends its own and leaves the replacement's
            // alone. Without them this had to be skipped, and a cancelled command left `PENDING`
            // standing with nothing behind it — and, cancelled mid-refresh, `REFRESHING` too.
            // Found by review, both of them.
            ticket?.let(store::releaseRequest)
            authTicket?.let(store::abandonAuthRefresh)
        }

    /**
     * Leaves no request reading as in flight once the only thing that could answer it has stopped.
     *
     * An acknowledged command needs nothing — `applyAck` already recorded the verdict — and both
     * calls are guarded on `PENDING`, so a verdict that did arrive is never written over.
     */
    private fun close(outcome: TopicCommandOutcome): TopicCommandOutcome {
        when (outcome) {
            is TopicCommandOutcome.Acknowledged -> Unit
            is TopicCommandOutcome.Stopped -> ticket?.let(store::giveUpRequest)
            is TopicCommandOutcome.AlreadyDelivering,
            TopicCommandOutcome.Superseded -> ticket?.let(store::releaseRequest)
        }
        return outcome
    }

    private suspend fun drive(): TopicCommandOutcome {
        var attempt = TopicRequestPolicy.nextAttempt(0) ?: return budgetSpent()
        var authReplayUsed = false
        var replayCredential: AuthSnapshot? = null

        // What each topic's generation was when this command began, as opposed to when its current
        // attempt was sent. A revalidation is judged against this one: a frame that arrived during
        // an earlier attempt's cooldown answered its question, and a per-send baseline would
        // absorb that evidence into the next attempt and lose it. Found by review.
        val entry = scope().associateWith { store.snapshot.stateFor(it).receiveGeneration }

        while (true) {
            when (val before = sendable(entry)) {
                Sendable.Nothing -> return TopicCommandOutcome.Superseded
                is Sendable.Recovered -> return TopicCommandOutcome.AlreadyDelivering(before.values)
                is Sendable.Topics -> Unit
            }

            val credential = replayCredential ?: try {
                credentials.currentSnapshot()
            } catch (unavailable: AuthUnavailableException) {
                // A preparation failure spends an attempt of its own — three of these leave no
                // send behind and no budget left, which is what the plan asks for.
                attempt = TopicRequestPolicy.nextAttempt(attempt) ?: return budgetSpent()
                when (waitBeforeRetry(TopicCommandRetryPolicy.SILENT_RETRY_COOLDOWN)) {
                    WaitResult.PROCEED -> continue
                    WaitResult.SUPERSEDED -> return TopicCommandOutcome.Superseded
                    WaitResult.REFUSED -> return programmingError()
                }
            }
            replayCredential = null

            // Asked again after the wait, not only before it: acquiring a credential suspends, and
            // a command that checked only on the way in would send to a scope that emptied while
            // it was blocked. Found by review.
            val wanted = when (val now = sendable(entry)) {
                Sendable.Nothing -> return TopicCommandOutcome.Superseded
                is Sendable.Recovered -> return TopicCommandOutcome.AlreadyDelivering(now.values)
                is Sendable.Topics -> now.values
            }

            val pending = register(wanted, credential) ?: return programmingError()
            // Before the store is touched, not after. Nothing goes on the wire on behalf of a
            // cancelled command — and a cancelled command must not take the replacement's request
            // with it either, which is what `beginRequest` here would do by claiming the ticket
            // and then handing it to the `finally` above. The check is explicit because the path
            // from the last suspension point to here has none of its own. Found by review.
            currentCoroutineContext().ensureActive()
            ticket = store.beginRequest()
            if (!send(encode(subscribe(pending, wanted)))) {
                attempt = TopicRequestPolicy.nextAttempt(attempt) ?: return budgetSpent()
                when (waitBeforeRetry(TopicCommandRetryPolicy.SILENT_RETRY_COOLDOWN)) {
                    WaitResult.PROCEED -> continue
                    WaitResult.SUPERSEDED -> return TopicCommandOutcome.Superseded
                    WaitResult.REFUSED -> return programmingError()
                }
            }

            // The inner loop is the same request being waited on again, and only the outer one is
            // a new attempt. An error this client cannot classify has to come back *here* — a
            // `continue` that reached the outer loop would answer "change nothing" by sending
            // another subscribe and spending an attempt on it. Found by the tests below.
            waiting@ while (true) {
                when (val answer = awaitAnswer(pending)) {
                    is Answer.Acked -> return settle(pending, answer)

                    Answer.AckOverdue -> {
                        attempt = TopicRequestPolicy.nextAttempt(attempt) ?: return budgetSpent()
                        when (waitBeforeRetry(TopicCommandRetryPolicy.SILENT_RETRY_COOLDOWN)) {
                            WaitResult.PROCEED -> break@waiting
                            WaitResult.SUPERSEDED -> return TopicCommandOutcome.Superseded
                            WaitResult.REFUSED -> return programmingError()
                        }
                    }

                    is Answer.Failed -> {
                        val failure = answer.error.wholeFailureOrNull()
                        // An unclassified error writes nothing and is not asked about. A classified one is admitted first, and the
                        // decision and `beginAuthRefresh` below run on that admission: nothing suspends before them.
                        if (failure != null) {
                            admit(refusesPremium = false)
                            store.applyWholeFailure(failure)
                        }
                        val decision = TopicWholeRequestMatrix.decide(
                            failure = failure,
                            attempt = attempt,
                            authReplayUsed = authReplayUsed,
                            sentWithToken = true
                        )
                        when (decision) {
                            // Nothing was consumed before asking, so nothing is consumed now: the
                            // request, its deadlines and its attempt all survive an `Ignore`.
                            TopicWholeRequestDecision.Ignore -> continue@waiting

                            is TopicWholeRequestDecision.Stop ->
                                return TopicCommandOutcome.Stopped(decision.reason)

                            is TopicWholeRequestDecision.RefreshAndReplay -> {
                                authReplayUsed = true
                                // Refreshing is a state of its own. `applyWholeFailure` has just
                                // recorded FAILED, and leaving it there through the replay would
                                // tell every reader the credential is finished while the one
                                // recovery it is owed is still running.
                                val recovery = store.beginAuthRefresh()
                                authTicket = recovery
                                val refreshed =
                                    credentials.refreshAfterUnauthorized(pending.credential)
                                // The refresh suspended, so its result is a new answer to admit. The ticket stays held until
                                // the refresh is settled: a denial here unwinds, and `run`'s finally is what abandons it.
                                admit(refusesPremium = false)
                                if (refreshed == null) {
                                    // Back to FAILED, and by the same route it got there.
                                    store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
                                    return TopicCommandOutcome.Stopped(
                                        TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT
                                    )
                                }
                                // The refresh is over the moment it produces a credential;
                                // leaving REFRESHING for the replay to clear would strand it
                                // there whenever the replay is never answered.
                                store.endAuthRefresh(recovery)
                                authTicket = null
                                replayCredential = refreshed
                                // The matrix already spent the attempt and named the next number;
                                // spending it again here would skip one of the three.
                                attempt = decision.attempt
                                break@waiting
                            }

                            is TopicWholeRequestDecision.RetryAfter -> {
                                attempt = decision.attempt
                                when (waitBeforeRetry(decision.delay)) {
                                    WaitResult.PROCEED -> break@waiting
                                    WaitResult.SUPERSEDED -> return TopicCommandOutcome.Superseded
                                    WaitResult.REFUSED -> return programmingError()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What is left to ask for, with the topics that have already answered taken out.
     *
     * Only a revalidation takes them out. Its question is whether a topic is still alive and a
     * frame settles that, so re-asking would confirm something already confirmed — while a first
     * delivery has an unanswered control request either way, and frames say nothing about that.
     */
    private fun sendable(entry: Map<String, Long>): Sendable {
        val wanted = scope()
        if (wanted.isEmpty()) return Sendable.Nothing
        if (purpose != TopicCommandPurpose.REVALIDATION) return Sendable.Topics(wanted)
        val (spoke, silent) = wanted.partition { spokeSince(entry, it) }
        return if (silent.isEmpty()) Sendable.Recovered(spoke.toSet()) else Sendable.Topics(silent.toSet())
    }

    /**
     * Everything the answer will be judged against, fixed **before** the send.
     *
     * The generation baseline especially. Taken at the ACK instead, a frame that overtook the
     * acknowledgement would be counted as "already there before we asked" and the topic would look
     * silent for the rest of the deadline.
     */
    private fun register(wanted: Set<String>, credential: AuthSnapshot): Pending? {
        val sentAt = clock.nowMillis()
        val deadlines = TopicRequestPolicy.deadlinesFor(sentAt) ?: return null
        return Pending(
            requestId = newRequestId(),
            topics = wanted,
            baseline = wanted.associateWith { store.snapshot.stateFor(it).receiveGeneration },
            deadlines = deadlines,
            credential = credential
        )
    }

    private fun subscribe(pending: Pending, wanted: Set<String>) =
        TopicSubscribeRequest.subscribe(pending.requestId, pending.credential.token, wanted.toList())

    /**
     * Waits for this request's answer, and for nothing else.
     *
     * A frame carrying another `request_id` belongs to an attempt this command has already
     * discarded, or to another command; either way it is dropped rather than answered, and the
     * wait continues. An error with **no** `request_id` is dropped for the same reason — it cannot
     * be attributed, and claiming it would let one command consume another's failure. The cost is
     * that such an error is only felt as the ACK deadline.
     *
     * **The deadline is checked again after the frame arrives, not only before the wait.** The
     * wait is measured on the coroutine's clock and the deadline on the monotonic one, and those
     * two disagree across sleep: a wait that slept through its own deadline returns with a frame
     * in hand, and accepting it would let an answer that is half a minute late count as on time.
     * Found by review.
     */
    private suspend fun awaitAnswer(pending: Pending): Answer {
        while (true) {
            if (pending.deadlines.ackOverdue(clock.nowMillis())) return Answer.AckOverdue
            val remaining = pending.deadlines.ackByMillis - clock.nowMillis()
            val frame = withTimeoutOrNull(remaining.milliseconds) { inbox.receive() } ?: continue
            if (pending.deadlines.ackOverdue(clock.nowMillis())) return Answer.AckOverdue
            when (frame) {
                is DecodedTopicFrame.Acknowledgement -> {
                    if (frame.value.requestId != pending.requestId) continue
                    // A malformed lease fails the whole control frame closed: the store keeps what
                    // it had, the request stays pending, and the ACK deadline decides. Consuming
                    // half of an acknowledgement is worse than having missed it.
                    val leases = readTopicLeases(frame.value.activeSubscriptions)
                    if (leases !is TopicLeaseReadResult.Valid) continue
                    return Answer.Acked(frame.value, leases.leases)
                }

                is DecodedTopicFrame.RequestFailure -> {
                    if (frame.value.requestId != pending.requestId) continue
                    return Answer.Failed(frame.value)
                }

                else -> continue
            }
        }
    }

    /** Applies the acknowledgement, hands it over, then waits out that send's delivery deadline. */
    private suspend fun settle(pending: Pending, answer: Answer.Acked): TopicCommandOutcome {
        val active = answer.ack.activeSubscriptions.mapTo(mutableSetOf()) { it.topic }
        val accepted = pending.topics intersect active
        // Narrowed to what this request actually asked for. `active_subscriptions` is the whole
        // connection's final state and is meant to be read that way, but `rejected_topics` is an
        // answer to a question — and a refusal for a topic this request never sent is somebody
        // else's answer. The store filters by `sentTopics` already; the callback did not, so a
        // stray `premium_required` reached it as though this request had been refused. Found by
        // review.
        val rejected = answer.ack.rejectedTopics
            .filter { it.topic in pending.topics }
            .mapNotNull { rejection -> rejection.reasonOrNull()?.let { rejection.topic to it } }
            .toMap()

        // The same map the session reads for its refusal latch, so the premium exception covers exactly those refusals.
        val admittedAt = admit(refusesPremium = rejected.values.any { it == TopicRejectionReason.PREMIUM_REQUIRED })
        store.applyAck(
            activeTopics = active,
            rejections = rejected,
            sentTopics = pending.topics,
            authResolved = provesAuthentication(accepted, rejected)
        )
        onAcknowledged(
            TopicCommandAcknowledgement(
                acknowledgedAtMillis = admittedAt,
                accepted = accepted,
                rejected = rejected,
                leases = answer.leases
            )
        )
        // The callback is where a session tears itself down on a refusal, and the branch below it
        // can return without suspending again — so a command cancelled by its own callback would
        // otherwise hand back a perfectly ordinary answer. Found by review.
        currentCoroutineContext().ensureActive()

        // The ACK narrows the watch and takes nothing off the clock: a topic that has already
        // spoken is done, the rest are judged at the deadline this send set. A renewal watches
        // nothing — see [TopicCommandPurpose.LEASE_RENEWAL].
        if (purpose.watchesDelivery && silentOf(pending, accepted).isNotEmpty()) {
            awaitUntil(pending.deadlines.deliverByMillis)
        }
        // Narrowed by the scope as it is *now*: a topic dropped while the deadline ran is not
        // something to revalidate or degrade, and the caller would act on it if it were reported.
        val stillWanted = scope()
        return TopicCommandOutcome.Acknowledged(
            accepted = accepted,
            // A purpose that did not watch has nothing to report. The set would otherwise be a
            // reading taken at the acknowledgement — every accepted topic that had not spoken in
            // the few milliseconds since the send — and a caller treating it like a watchdog
            // result would revalidate or degrade a topic that was never given its window. The
            // caller branches on [purpose] too; this is the half that cannot be forgotten.
            // Found by review.
            silent = if (purpose.watchesDelivery) silentOf(pending, accepted) intersect stillWanted
            else emptySet(),
            leases = answer.leases,
            purpose = purpose
        )
    }

    /**
     * Whether this acknowledgement is evidence the credential was accepted.
     *
     * A subscription made under the token obviously is. So is every refusal the server can only
     * reach **after** identity: `app/topic_dispatcher.py:715` authorises before topics are
     * resolved, and `unknown_topic`, `topic_unavailable`, `premium_required` and
     * `krx_entitlement_required` are all decided past that line. `topics_disabled` is the one
     * that is not — the flag is checked at `:600` with a comment saying so in as many words, and
     * `:623` refuses every topic there — which is why
     * `pre-auth topics-disabled ack cannot resolve failed authentication` exists.
     *
     * Reading only `accepted` was too narrow: a premium refusal would then leave a recovered
     * credential looking unproven. Found by review.
     */
    private fun provesAuthentication(
        accepted: Set<String>,
        rejected: Map<String, TopicRejectionReason>
    ): Boolean =
        accepted.isNotEmpty() ||
            rejected.values.any { it != TopicRejectionReason.TOPICS_DISABLED }

    /**
     * Delivery is proven by the topic's own receive generation moving, and by nothing else.
     *
     * The generation counts validated frames off the socket. A REST bootstrap, a restored disk
     * seed and the acknowledgement itself must not move it, or a topic that has never sent
     * anything would satisfy its own watchdog.
     */
    private fun silentOf(pending: Pending, accepted: Set<String>): Set<String> =
        accepted.filterTo(mutableSetOf()) {
            store.snapshot.stateFor(it).receiveGeneration == pending.baseline[it]
        }

    /**
     * This topic has moved past where it was when the command started.
     *
     * A topic with no entry reading joined the scope after the command did and has nothing to have
     * moved past, so it counts as not having spoken and the command still asks.
     */
    private fun spokeSince(entry: Map<String, Long>, topic: String): Boolean {
        val before = entry[topic] ?: return false
        return store.snapshot.stateFor(topic).receiveGeneration > before
    }

    private suspend fun awaitUntil(deadlineMillis: Long) {
        while (true) {
            val remaining = deadlineMillis - clock.nowMillis()
            if (remaining <= 0) return
            clock.sleep(remaining.milliseconds)
        }
    }

    /**
     * Waits, then asks whether sending is still the right thing to do.
     *
     * A wait the policy refuses is [WaitResult.REFUSED] and not a substituted base: the policy
     * returns `null` for a draw or a base outside its contract, which is a caller bug, and waiting
     * the unjittered interval anyway would hide it behind a ladder that looks like policy. Found
     * by review.
     */
    private suspend fun waitBeforeRetry(base: Duration): WaitResult {
        val wait = TopicCommandRetryPolicy.waitFor(base, jitter()) ?: return WaitResult.REFUSED
        clock.sleep(wait)
        return if (scope().isEmpty()) WaitResult.SUPERSEDED else WaitResult.PROCEED
    }

    private fun budgetSpent() =
        TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT)

    /**
     * A local contract was broken — an injected clock outside the deadline range, or a jitter draw
     * the retry policy refuses. Not a server verdict, and not a spent budget: sending the same
     * thing again would break the same contract again.
     */
    private fun programmingError() =
        TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.PROGRAMMING_ERROR)
}
