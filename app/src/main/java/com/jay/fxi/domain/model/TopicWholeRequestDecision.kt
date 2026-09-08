package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What to do about a `subscription_error` that failed the whole request.
 *
 * `ANDROID_V2_PLAN.md:866-871`, the whole-request half of the error matrix. A request can fail
 * entirely, or it can succeed while some topics are refused, and those are different questions.
 * The per-topic half is only partly answered elsewhere — [TopicSubscriptionSnapshot.rejectionRetryTriggers]
 * says *when to try a refused topic again*, and is reused rather than restated. What a refusal
 * should *do* — the S1 Rejected transition, S6's hide and forced refresh — is not here and not
 * there yet.
 */
sealed interface TopicWholeRequestDecision {
    /**
     * Change nothing.
     *
     * An error this client does not recognise is isolated, not guessed at. **The caller must not
     * have consumed anything before asking** — the pending request, its deadlines and its attempt
     * all have to survive an answer of `Ignore`, which means correlating and classifying before
     * taking ownership rather than after.
     */
    data object Ignore : TopicWholeRequestDecision

    /** Force a token refresh, then send once more as [attempt]. */
    data class RefreshAndReplay(val attempt: Int) : TopicWholeRequestDecision

    /** Wait at least [delay] — the server's floor, not a guess — then send again as [attempt]. */
    data class RetryAfter(val delay: Duration, val attempt: Int) : TopicWholeRequestDecision

    /** Send no more, for [reason]. */
    data class Stop(val reason: Reason) : TopicWholeRequestDecision {
        enum class Reason {
            /** The request was malformed or too large: sending it again sends the same thing. */
            PROGRAMMING_ERROR,

            /** Three attempts are gone. */
            BUDGET_SPENT,

            /**
             * A second `invalid_token` for one command, and the contract allows one recovery.
             *
             * Not a diagnosis. A freshly minted token can be refused for reasons a refresh will
             * never fix — a wrong audience, a clock that disagrees — and this says nothing about
             * which. It says only that the one forced refresh this command was allowed is gone.
             */
            AUTH_REPLAY_SPENT,

            /**
             * `invalid_token` for a send that carried no token.
             *
             * `unsubscribe` goes out without one, so there is nothing to refresh and re-send: a
             * replay would present the same absence. iOS draws the same line — its recovery is
             * guarded on the request being a `subscribe` with a token snapshot behind it.
             */
            NO_TOKEN_TO_REPLAY,

            /** The server's `retry_after_seconds` is not a delay this client can wait. */
            UNUSABLE_RETRY_DELAY
        }
    }
}

/**
 * The whole-request error matrix, as arithmetic over what the caller already knows.
 *
 * Deliberately not given the socket, the clock, or the token provider: what it answers is *which*
 * of four things to do, and every one of them is the transport's to carry out. Refreshing a token,
 * waiting, and re-sending are ordered against connection and account changes that only the
 * transport can see.
 */
object TopicWholeRequestMatrix {

    /**
     * The largest `retry_after_seconds` that still converts exactly.
     *
     * The same bound and the same reason as a lease duration ([TopicLeasePolicy.MAX_DURATION_SECONDS]):
     * a count of seconds the server sent, which past this point saturates when it is converted to
     * nanoseconds, so every larger value reads as the same one. Writing the number a second time
     * would be writing one rule twice. It bounds the conversion and nothing further — whatever the
     * scheduler adds on top, jitter or an absolute wake time, is its own arithmetic to check.
     */
    private val MAX_RETRY_AFTER_SECONDS = TopicLeasePolicy.MAX_DURATION_SECONDS

    /**
     * @param failure what the error decoded to, or `null` when this client does not recognise it
     * @param attempt how many attempts this command has started — see [TopicRequestPolicy.nextAttempt]
     * @param authReplayUsed whether this command has already spent its one forced refresh. It
     *   belongs to the command, not the request: a replay goes out under a new `request_id`, and
     *   forgetting that would hand every retry a fresh licence to refresh.
     * @param sentWithToken whether the send that was refused carried a token at all. `subscribe`
     *   does and `unsubscribe` does not, and refreshing something that was never sent is not a
     *   recovery. Passed in rather than assumed: a silent precondition here would be violated by
     *   the first caller that reaches for the obvious-looking function.
     */
    fun decide(
        failure: TopicWholeRequestFailure?,
        attempt: Int,
        authReplayUsed: Boolean,
        sentWithToken: Boolean
    ): TopicWholeRequestDecision = when (failure) {
        null -> TopicWholeRequestDecision.Ignore

        TopicWholeRequestFailure.InvalidRequest,
        TopicWholeRequestFailure.RequestTooLarge ->
            TopicWholeRequestDecision.Stop(TopicWholeRequestDecision.Stop.Reason.PROGRAMMING_ERROR)

        TopicWholeRequestFailure.InvalidToken ->
            if (!sentWithToken) {
                TopicWholeRequestDecision.Stop(TopicWholeRequestDecision.Stop.Reason.NO_TOKEN_TO_REPLAY)
            } else if (authReplayUsed) {
                TopicWholeRequestDecision.Stop(TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT)
            } else {
                // "공유 예산 안 1회" — the replay is one of the three, not a fourth. iOS carries the
                // original attempt number into its replay and so allows a send past the ceiling;
                // the plan says otherwise and the plan wins.
                spend(attempt) { TopicWholeRequestDecision.RefreshAndReplay(it) }
            }

        is TopicWholeRequestFailure.TemporarilyUnavailable ->
            if (failure.retryAfterSeconds !in 1..MAX_RETRY_AFTER_SECONDS) {
                TopicWholeRequestDecision.Stop(
                    TopicWholeRequestDecision.Stop.Reason.UNUSABLE_RETRY_DELAY
                )
            } else {
                spend(attempt) {
                    TopicWholeRequestDecision.RetryAfter(failure.retryAfterSeconds.seconds, it)
                }
            }
    }

    private fun spend(
        attempt: Int,
        next: (Int) -> TopicWholeRequestDecision
    ): TopicWholeRequestDecision =
        TopicRequestPolicy.nextAttempt(attempt)
            ?.let(next)
            ?: TopicWholeRequestDecision.Stop(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT)
}
