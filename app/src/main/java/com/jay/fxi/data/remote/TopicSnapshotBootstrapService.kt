package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One REST answer for one topic, or one reason there is none.
 *
 * The REST twin of a subscription's snapshot. It exists because the socket can be slow to produce a
 * first frame and a screen should not be empty while it is — but it is **not** a subscription, and
 * this slice deliberately stops at the answer: what to do with it, and when to ask, belong to the
 * session that owns the socket.
 *
 * **One logical attempt, and no retry of its own.** The shared transport still replays a read once
 * after a 401, so a single attempt can be two HTTP sends; that replay is the credential being
 * corrected, not the request being retried, and it happens *inside* the budget below.
 */
@Singleton
class TopicSnapshotBootstrapService internal constructor(
    private val api: AuthenticatedApiClient,
    private val decoder: TopicFrameDecoder,
    /**
     * Injectable only so a test can straddle it.
     *
     * The production value is ten seconds, which no unit test can wait out and none should try to
     * fake: the budget is real elapsed time, and a virtual clock would fire it the moment the call
     * suspended on a socket. A test that needs to cross the deadline shortens it instead.
     */
    private val attemptBudget: Duration
) {
    @Inject
    constructor(
        api: AuthenticatedApiClient,
        decoder: TopicFrameDecoder
    ) : this(api, decoder, ATTEMPT_BUDGET)


    /**
     * Asks for [topic] once.
     *
     * The budget spans the **whole attempt**, credential acquisition included, because that is what
     * the caller is waiting on — and because a second window anywhere inside would let the 401
     * replay start the clock again. The token is fetched from a shared, process-scoped request, so
     * a slow one that somebody else started does spend this budget: that is the attempt honestly
     * taking that long, not an accounting error.
     *
     * **Only this attempt's own deadline becomes an outcome.** `withTimeoutOrNull` compares the
     * timeout's owner, which catching `TimeoutCancellationException` by class does not: a caller
     * that wraps this in a deadline of its own would otherwise get a perfectly ordinary outcome
     * returned into a coroutine that is already cancelled, and whatever it does next would run.
     * `AuthIdentityChangedException` is a `CancellationException` too and keeps travelling for the
     * same reason — the answer was authorised for somebody who is no longer signed in.
     *
     * **The deadline is absolute, and checked again before the answer is taken.** The cancellation
     * is a task on a dispatcher, so a busy one can hand back a response that arrived first and let
     * an answer well past the budget through; reproduced by review at 1.7s against a 500ms budget.
     * The mark is taken once, before anything else, so neither the transport's 401 replay nor
     * decoding can push it back.
     */
    suspend fun bootstrap(owner: AuthIdentityFence, topic: String): TopicSnapshotOutcome {
        val started = TimeSource.Monotonic.markNow()
        val answered = try {
            withTimeoutOrNull(attemptBudget) { attempt(owner, topic) }
        } catch (unreachable: IOException) {
            // Not an answer that arrived late — an answer that never arrived. The cause is the
            // evidence, so it is kept rather than flattened into the deadline.
            return TopicSnapshotOutcome.Unreachable(unreachable)
        }
        if (answered == null || started.elapsedNow() > attemptBudget) {
            return TopicSnapshotOutcome.TimedOut
        }
        return answered
    }

    private suspend fun attempt(owner: AuthIdentityFence, topic: String): TopicSnapshotOutcome {
        // Bound to the account the caller issued under, not to whoever happens to be signed in
        // when this job starts. The transport binds **identity only** — it re-checks uid and
        // authGeneration before the token is read and again once the response is in hand — and
        // knows nothing of `userAccessEpoch`. An epoch-only move still sends; refusing that
        // answer is the caller's job.
        val credential = api.captureSnapshot(owner)
        // An answer authorised for an account that has since moved never arrives here: the shared
        // transport re-checks after the response and refuses it, closing the bodies and carrying the
        // status and `Retry-After` out with the identity change. Re-checking again below would be a
        // second guard that no test can tell from the first — measured, not assumed.
        val response = api.getTopicSnapshot(credential, topic)

        response.failure?.let { return it.toTopicSnapshotOutcome() }
        val body = response.body
            ?: return TopicSnapshotOutcome.Malformed("successful response carried no body")

        // Nothing suspends inside, so nothing here can be a cancellation wearing an exception.
        val frame = try {
            decoder.decode(body.decodeToString(throwOnInvalidSequence = true))
        } catch (invalid: Exception) {
            return TopicSnapshotOutcome.Malformed("body did not decode: ${invalid.message}")
        }

        val answered = frame.topicOrNull()
            ?: return TopicSnapshotOutcome.Malformed("body is not a topic snapshot")
        if (answered != topic) {
            return TopicSnapshotOutcome.Malformed("asked for $topic and was answered $answered")
        }
        return TopicSnapshotOutcome.Delivered(frame)
    }

    internal companion object {
        /**
         * Per attempt, on the caller's clock.
         *
         * Wider than the server's own snapshot phase, so a healthy-but-slow answer still fits, and
         * narrower than every layer beneath it — the socket timeouts, the proxy, and the server's
         * request deadline are all longer, so this is the layer that gives up first and the only
         * one whose giving up the caller can see.
         */
        val ATTEMPT_BUDGET: Duration = 10.seconds
    }
}

/**
 * The topic a decoded frame is *about*, or `null` when it is not about one.
 *
 * The socket decoder answers with the frame it found, which on that side includes several perfectly
 * ordinary non-answers — an acknowledgement, a pong, the legacy `rates` payload, an envelope it does
 * not recognise. Over one REST response each of those is a failure, and this is where they lose
 * their `null` and become one.
 */
private fun DecodedTopicFrame.topicOrNull(): String? = when (this) {
    is DecodedTopicFrame.Tether -> value.topic
    is DecodedTopicFrame.Krx -> value.topic
    is DecodedTopicFrame.Fx -> value.topic
    is DecodedTopicFrame.Dxy -> value.topic
    is DecodedTopicFrame.Acknowledgement,
    is DecodedTopicFrame.RequestFailure,
    is DecodedTopicFrame.Unsupported,
    DecodedTopicFrame.Pong,
    DecodedTopicFrame.NotTopic -> null
}
