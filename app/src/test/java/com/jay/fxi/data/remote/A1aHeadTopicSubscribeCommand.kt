package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AccessOrderSequence
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

/** Frozen executable OFF baseline from 8501b6d (production bytes = f79d334).
 * Only coordinator/command/private input identifiers and class visibility are changed.
 * Do not refresh from a candidate. O2 compares identical inputs against this baseline. */
internal class A1aHeadTopicSubscribeCommand(
    private val purpose: TopicCommandPurpose,
    private val store: TopicSubscriptionStateStore,
    private val credentials: TopicCommandCredentials,
    /** The token provider's order (S1 recovery signal §3), for [TopicAuthEnd.endedOrder]. No default: one of its own compares with nothing. */
    private val orders: AccessOrderSequence,
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
     * result of a credential refresh. [rejected] is what an acknowledgement refuses among the topics this request sent — empty for
     * a failure or a refresh. A `premium_required` among them is the one answer settled ahead of an expired lease, and every
     * refusal is still handed over when the use is withheld (L-4e E2a). The default admits everything; a session must supply its own.
     */
    private val admitAnswer: (rejected: Map<String, TopicRejectionReason>) -> TopicAnswerAdmission =
        { TopicAnswerAdmission.Admitted(clock.nowMillis()) },
    /**
     * Each revalidated topic's receive generation, read by whoever registered this command (L-4e E6b). A revalidation is judged
     * against it and does not read its own when it starts; `null` reads one then, as a standalone command always did. Required
     * for [TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION].
     */
    private val revalidationEntry: Map<String, Long>? = null,
    /**
     * [TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION] only: the topics whose revalidation this command still owes, asked
     * at the same points as [scope] — which, for that purpose, is the renewal's topics. Empty for every other purpose.
     */
    private val revalidationScope: () -> Set<String> = { emptySet() },
    /** Once per admitted classified server failure, after synchronous state transitions and before the next suspension. */
    private val onClassifiedFailure: () -> Unit = {},
    /** Once per attempt, after opening its request and before sending or waiting; not evidence of an answer. */
    private val onRequestStarted: () -> Unit = {},
    /** A manual obligation's original registration entries, independent of the automatic purpose. */
    private val manualEntry: Map<String, Long>? = null,
    /** Manual targets still owned: they owe a subscribe ACK even when their delivery has already arrived. */
    private val manualScope: () -> Set<String> = { emptySet() },
    /** Immediately after an admitted refresh result applies InvalidToken because it produced no usable credential. */
    private val onAuthRefreshUnusable: () -> Unit = {}
) {
    init {
        require(purpose != TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION || revalidationEntry != null) {
            "a combined command is judged against the entry its registration read"
        }
    }

    private class Pending(
        val requestId: String,
        val topics: Set<String>,
        /** The automatic delivery scope plus manual targets that have not received since their original entry. */
        val watched: Set<String>,
        val manualTopics: Set<String>,
        val baseline: Map<String, Long>,
        val deadlines: TopicRequestDeadlines,
        val credential: AuthSnapshot,
        /** Sent as the replay of this command's forced refresh, not read: which of the two hand-overs a final refusal takes. */
        val replayOfRefresh: Boolean
    )

    private sealed interface Answer {
        data class Acked(val ack: SubscriptionAck, val leases: List<TopicLease>) : Answer
        data class Failed(val error: SubscriptionError) : Answer
        data object AckOverdue : Answer
    }

    /** What the scope says to do next, asked again at every point where sending is possible. */
    private sealed interface Sendable {
        data class Topics(val values: Set<String>, val watched: Set<String>, val manualTopics: Set<String> = emptySet()) : Sendable
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
    private suspend fun admit(rejected: Map<String, TopicRejectionReason> = emptyMap()): Long {
        currentCoroutineContext().ensureActive()
        return when (val admission = admitAnswer(rejected)) {
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
        // A registration that read it already is not read again (L-4e E6b): a frame between that reading and this coroutine
        // starting answered the question, and the session judges this command's ending against the same number.
        val entry = revalidationEntry ?: scope().associateWith { store.snapshot.stateFor(it).receiveGeneration }

        while (true) {
            when (val before = sendable(entry)) {
                Sendable.Nothing -> return TopicCommandOutcome.Superseded
                is Sendable.Recovered -> return TopicCommandOutcome.AlreadyDelivering(before.values)
                is Sendable.Topics -> Unit
            }

            val replayOfRefresh = replayCredential != null
            val credential = replayCredential ?: try {
                credentials.currentSnapshot()
            } catch (unavailable: AuthUnavailableException) {
                // A preparation failure spends an attempt of its own — three of these leave no
                // send behind and no budget left, which is what the plan asks for.
                attempt = TopicRequestPolicy.nextAttempt(attempt)
                    ?: return authEnded(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT, TopicAuthEnd.Kind.CREDENTIAL_UNAVAILABLE)
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
            val now = when (val next = sendable(entry)) {
                Sendable.Nothing -> return TopicCommandOutcome.Superseded
                is Sendable.Recovered -> return TopicCommandOutcome.AlreadyDelivering(next.values)
                is Sendable.Topics -> next
            }
            val wanted = now.values

            val pending = register(wanted, now.watched, now.manualTopics, credential, replayOfRefresh) ?: return programmingError()
            // Before the store is touched, not after. Nothing goes on the wire on behalf of a
            // cancelled command — and a cancelled command must not take the replacement's request
            // with it either, which is what `beginRequest` here would do by claiming the ticket
            // and then handing it to the `finally` above. The check is explicit because the path
            // from the last suspension point to here has none of its own. Found by review.
            currentCoroutineContext().ensureActive()
            ticket = store.beginRequest()
            onRequestStarted()
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
                            admit()
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

                            is TopicWholeRequestDecision.Stop -> {
                                onClassifiedFailure()
                                if (failure != TopicWholeRequestFailure.InvalidToken) {
                                    return TopicCommandOutcome.Stopped(decision.reason)
                                }
                                val kind = when (decision.reason) {
                                    TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT -> TopicAuthEnd.Kind.REPLAY_REJECTED
                                    TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT -> TopicAuthEnd.Kind.REJECTED_WITHOUT_BUDGET
                                    else -> return TopicCommandOutcome.Stopped(decision.reason)
                                }
                                // The provider hears the refusal before anything else settles: which call follows where this
                                // request's credential came from, never the value it happens to share with a replay.
                                if (pending.replayOfRefresh) {
                                    credentials.recordRejected(pending.credential)
                                } else {
                                    credentials.recordRejectionEvidence(pending.credential)
                                }
                                // The hand-over suspended. What the provider took stands even if this ending is then refused.
                                return authEnded(decision.reason, kind)
                            }

                            is TopicWholeRequestDecision.RefreshAndReplay -> {
                                authReplayUsed = true
                                // Refreshing is a state of its own. `applyWholeFailure` has just
                                // recorded FAILED, and leaving it there through the replay would
                                // tell every reader the credential is finished while the one
                                // recovery it is owed is still running.
                                val recovery = store.beginAuthRefresh()
                                authTicket = recovery
                                onClassifiedFailure()
                                val refreshed =
                                    credentials.refreshAfterUnauthorized(pending.credential)
                                // The refresh suspended, so its result is a new answer to admit. The ticket stays held until
                                // the refresh is settled: a denial here unwinds, and `run`'s finally is what abandons it.
                                admit()
                                if (refreshed == null) {
                                    // Back to FAILED, and by the same route it got there.
                                    store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
                                    onAuthRefreshUnusable()
                                    return TopicCommandOutcome.Stopped(
                                        TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT,
                                        // Admitted just above, and nothing has suspended since.
                                        TopicAuthEnd(TopicAuthEnd.Kind.REFRESH_UNUSABLE, orders.next())
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
                                onClassifiedFailure()
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
     *
     * A combined command (L-4e E6b) asks for its renewal's topics and for the revalidation topics that have not spoken, and
     * watches only the second. A renewal topic stays in however much it has spoken: a frame does not re-authenticate a lease.
     * With nothing to send, a revalidation that spoke answered it; one whose topics are no longer owed at all did not.
     */
    private fun sendable(entry: Map<String, Long>): Sendable {
        val automatic = automaticSendable(entry)
        val manual = manualScope()
        if (manual.isEmpty()) return automatic
        val manualBaseline = checkNotNull(manualEntry) { "manual targets require their registration entries" }
        val manualDelivery = manual.filterTo(mutableSetOf()) { !spokeSince(manualBaseline, it) }
        val base = automatic as? Sendable.Topics
        return Sendable.Topics(base?.values.orEmpty() + manual, base?.watched.orEmpty() + manualDelivery, manual)
    }

    private fun automaticSendable(entry: Map<String, Long>): Sendable {
        if (purpose == TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION) {
            val (spoke, silent) = revalidationScope().partition { spokeSince(entry, it) }
            val renewal = scope()
            return when {
                renewal.isNotEmpty() || silent.isNotEmpty() -> Sendable.Topics(renewal + silent, silent.toSet())
                spoke.isNotEmpty() -> Sendable.Recovered(spoke.toSet())
                else -> Sendable.Nothing
            }
        }
        val wanted = scope()
        if (wanted.isEmpty()) return Sendable.Nothing
        if (purpose != TopicCommandPurpose.REVALIDATION) {
            return Sendable.Topics(wanted, if (purpose.watchesDelivery) wanted else emptySet())
        }
        val (spoke, silent) = wanted.partition { spokeSince(entry, it) }
        return if (silent.isEmpty()) Sendable.Recovered(spoke.toSet()) else Sendable.Topics(silent.toSet(), silent.toSet())
    }

    /**
     * Everything the answer will be judged against, fixed **before** the send.
     *
     * The generation baseline especially. Taken at the ACK instead, a frame that overtook the
     * acknowledgement would be counted as "already there before we asked" and the topic would look
     * silent for the rest of the deadline.
     */
    private fun register(
        wanted: Set<String>,
        watched: Set<String>,
        manualTopics: Set<String>,
        credential: AuthSnapshot,
        replayOfRefresh: Boolean
    ): Pending? {
        val sentAt = clock.nowMillis()
        val deadlines = TopicRequestPolicy.deadlinesFor(sentAt) ?: return null
        return Pending(
            requestId = newRequestId(),
            topics = wanted,
            watched = watched,
            manualTopics = manualTopics,
            // A manual A-only target may share a topic with a later automatic delivery question. Only a still-owed manual D
            // supplies its old baseline; otherwise the automatic question keeps its own reading.
            baseline = wanted.associateWith { topic ->
                val manual = manualEntry?.get(topic)
                if (topic in manualTopics && manual != null && store.snapshot.stateFor(topic).receiveGeneration <= manual) {
                    manual
                } else store.snapshot.stateFor(topic).receiveGeneration
            },
            deadlines = deadlines,
            credential = credential,
            replayOfRefresh = replayOfRefresh
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

        // The same map the session reads for its refusal latch and hands over, so the premium exception and the refusals delivered
        // while a use is withheld are exactly these.
        val admittedAt = admit(rejected)
        val authenticationProven = provesAuthentication(accepted, rejected)
        store.applyAck(
            activeTopics = active,
            rejections = rejected,
            sentTopics = pending.topics,
            authResolved = authenticationProven
        )
        onAcknowledged(
            TopicCommandAcknowledgement(
                acknowledgedAtMillis = admittedAt,
                accepted = accepted,
                rejected = rejected,
                leases = answer.leases,
                provesAuthentication = authenticationProven,
                missing = pending.manualTopics - active - rejected.keys
            )
        )
        // The callback is where a session tears itself down on a refusal, and the branch below it
        // can return without suspending again — so a command cancelled by its own callback would
        // otherwise hand back a perfectly ordinary answer. Found by review.
        currentCoroutineContext().ensureActive()

        // The ACK narrows the watch and takes nothing off the clock: a topic that has already
        // spoken is done, the rest are judged at the deadline this send set. A renewal watches
        // nothing — see [TopicCommandPurpose.LEASE_RENEWAL] — and a combined command watches only
        // the revalidation topics it sent (L-4e E6b).
        val observed = accepted intersect pending.watched
        if (silentOf(pending, observed).isNotEmpty()) {
            awaitUntil(pending.deadlines.deliverByMillis)
        }
        // Narrowed by the scope as it is *now*: a topic dropped while the deadline ran is not
        // something to revalidate or degrade, and the caller would act on it if it were reported.
        // A combined command's automatic silence is its revalidation's; manual targets remain independently owned.
        val stillWanted =
            (if (purpose == TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION) revalidationScope() else scope()) + manualScope()
        return TopicCommandOutcome.Acknowledged(
            accepted = accepted,
            // A send that did not watch has nothing to report. The set would otherwise be a
            // reading taken at the acknowledgement — every accepted topic that had not spoken in
            // the few milliseconds since the send — and a caller treating it like a watchdog
            // result would revalidate or degrade a topic that was never given its window. The
            // caller branches on [purpose] too; this is the half that cannot be forgotten.
            // Found by review.
            silent = silentOf(pending, observed) intersect stillWanted,
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
        accepted.filterTo(mutableSetOf()) { !spokeSince(pending.baseline, it) }

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
        // A combined command whose renewal emptied still owes its revalidation, which the next send check settles (L-4e E6b).
        return if (scope().isEmpty() && revalidationScope().isEmpty() && manualScope().isEmpty()) {
            WaitResult.SUPERSEDED
        } else WaitResult.PROCEED
    }

    private fun budgetSpent() =
        TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT)

    /**
     * Settles an authentication ending (L-4e E6a): admitted again first — the credential read or the hand-over before it may have
     * suspended past a teardown — and then its order taken, with nothing between the two. A refusal unwinds instead, and leaves no
     * ending behind.
     */
    private suspend fun authEnded(reason: TopicWholeRequestDecision.Stop.Reason, kind: TopicAuthEnd.Kind): TopicCommandOutcome.Stopped {
        admit()
        return TopicCommandOutcome.Stopped(reason, TopicAuthEnd(kind, orders.next()))
    }

    /**
     * A local contract was broken — an injected clock outside the deadline range, or a jitter draw
     * the retry policy refuses. Not a server verdict, and not a spent budget: sending the same
     * thing again would break the same contract again.
     */
    private fun programmingError() =
        TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.PROGRAMMING_ERROR)
}
