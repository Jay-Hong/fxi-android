package com.jay.fxi.domain.model

import com.jay.fxi.domain.model.TopicWholeRequestDecision.Stop.Reason
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Four answers to a request the server refused outright.
 *
 * Each one exists because the alternative costs something real: guessing at an error nobody taught
 * this client about, re-sending a request the server called malformed, asking for a third token
 * after the one allowed recovery is spent, or scheduling a delay from a number that no longer
 * converts to the one the server sent.
 */
class TopicWholeRequestMatrixTest {

    private fun decide(
        failure: TopicWholeRequestFailure?,
        attempt: Int = 0,
        authReplayUsed: Boolean = false,
        sentWithToken: Boolean = true
    ) = TopicWholeRequestMatrix.decide(failure, attempt, authReplayUsed, sentWithToken)

    /**
     * An error this client cannot name changes nothing.
     *
     * `null` is what the decoder answers for an error string it has no case for. Treating it as any
     * of the other three would either spend an attempt or abandon a request over a word the server
     * may have added last week.
     */
    @Test
    fun anUnrecognisedErrorIsIgnored() {
        assertEquals(TopicWholeRequestDecision.Ignore, decide(null))
        assertEquals(TopicWholeRequestDecision.Ignore, decide(null, attempt = 2))
    }

    /** A request the server called malformed is the same request next time. */
    @Test
    fun aMalformedRequestIsNotSentAgain() {
        listOf(
            TopicWholeRequestFailure.InvalidRequest,
            TopicWholeRequestFailure.RequestTooLarge
        ).forEach {
            assertEquals("$it", TopicWholeRequestDecision.Stop(Reason.PROGRAMMING_ERROR), decide(it))
        }
    }

    /**
     * A refused token buys one forced refresh and one more send — out of the same three.
     *
     * iOS carries the original attempt number into its replay, so a first `invalid_token` on the
     * third attempt lets a fourth request go out. The plan says the replay shares the budget, so
     * here the third attempt has nothing left to replay with.
     */
    @Test
    fun aRefusedTokenSpendsFromTheSharedBudget() {
        assertEquals(
            TopicWholeRequestDecision.RefreshAndReplay(1),
            decide(TopicWholeRequestFailure.InvalidToken, attempt = 0)
        )
        assertEquals(
            "첫 오류의 정상 경로 — 한 번 보낸 뒤",
            TopicWholeRequestDecision.RefreshAndReplay(2),
            decide(TopicWholeRequestFailure.InvalidToken, attempt = 1)
        )
        assertEquals(
            TopicWholeRequestDecision.RefreshAndReplay(3),
            decide(TopicWholeRequestFailure.InvalidToken, attempt = 2)
        )
        assertEquals(
            "세 시도를 다 쓴 뒤의 invalid_token 은 네 번째 송신을 만들지 않는다",
            TopicWholeRequestDecision.Stop(Reason.BUDGET_SPENT),
            decide(TopicWholeRequestFailure.InvalidToken, attempt = 3)
        )
    }

    /**
     * The second refusal of a fresh token is the end of it.
     *
     * The flag belongs to the command rather than the request: a replay goes out under a new
     * `request_id`, and losing it there would hand every retry another licence to refresh.
     */
    @Test
    fun aSecondRefusedTokenStops() {
        assertEquals(
            TopicWholeRequestDecision.Stop(Reason.AUTH_REPLAY_SPENT),
            decide(TopicWholeRequestFailure.InvalidToken, attempt = 0, authReplayUsed = true)
        )
    }

    /**
     * A send that carried no token has nothing to refresh.
     *
     * `unsubscribe` goes out without one. A replay would present the same absence, so this is not
     * a recovery — iOS guards its own on the request being a `subscribe` with a token behind it.
     */
    @Test
    fun aRefusalOfASendThatCarriedNoTokenStops() {
        assertEquals(
            TopicWholeRequestDecision.Stop(Reason.NO_TOKEN_TO_REPLAY),
            decide(TopicWholeRequestFailure.InvalidToken, sentWithToken = false)
        )
        // …and the absence outranks both of the other two answers this could otherwise have given:
        // a spent replay and a spent budget.
        assertEquals(
            TopicWholeRequestDecision.Stop(Reason.NO_TOKEN_TO_REPLAY),
            decide(
                TopicWholeRequestFailure.InvalidToken,
                attempt = 3,
                authReplayUsed = true,
                sentWithToken = false
            )
        )
    }

    /** The server's floor is the wait, and it costs an attempt like any other send. */
    @Test
    fun temporarilyUnavailableWaitsTheServersFloor() {
        assertEquals(
            TopicWholeRequestDecision.RetryAfter(5.seconds, 1),
            decide(TopicWholeRequestFailure.TemporarilyUnavailable(5), attempt = 0)
        )
        assertEquals(
            TopicWholeRequestDecision.Stop(Reason.BUDGET_SPENT),
            decide(TopicWholeRequestFailure.TemporarilyUnavailable(5), attempt = 3)
        )
    }

    /**
     * A floor that does not convert exactly is refused.
     *
     * Two different paths reach this. On the wire, a missing, zero or negative
     * `retry_after_seconds` never becomes a `TemporarilyUnavailable` at all — `wholeFailureOrNull`
     * answers `null` and the decision is `Ignore`. What survives decoding and still cannot be used
     * is a number past the point where converting to nanoseconds saturates, so every larger value
     * reads as the same one. The values below are built as domain objects to reach that branch;
     * the small ones stand for a decoder that stopped enforcing its own floor.
     */
    @Test
    fun aFloorThatDoesNotConvertExactlyIsRefused() {
        listOf(0L, -1L, Long.MAX_VALUE, TopicLeasePolicy.MAX_DURATION_SECONDS + 1).forEach {
            assertEquals(
                "$it",
                TopicWholeRequestDecision.Stop(Reason.UNUSABLE_RETRY_DELAY),
                decide(TopicWholeRequestFailure.TemporarilyUnavailable(it))
            )
        }
        // The largest one that still converts exactly.
        assertEquals(
            TopicWholeRequestDecision.RetryAfter(TopicLeasePolicy.MAX_DURATION_SECONDS.seconds, 1),
            decide(TopicWholeRequestFailure.TemporarilyUnavailable(TopicLeasePolicy.MAX_DURATION_SECONDS))
        )
    }
}
