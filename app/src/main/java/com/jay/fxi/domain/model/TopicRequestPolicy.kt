package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The two deadlines one send is judged by, both anchored at that send.
 *
 * Built from a single [sentAtMillis] on purpose: there is no way to give the two of them different
 * origins, and no way to move either one afterwards. That is what `ANDROID_V2_PLAN.md §7 S3`
 * means by the delivery deadline being **absolute** — an ACK proves the command was heard, and
 * moves the question on to whether anything arrives, but it does not buy more time to arrive in.
 *
 * Monotonic milliseconds, the axis `RecheckSchedule` already uses — a wall clock would let a
 * deadline be skipped or doubled by the phone correcting its own time.
 *
 * **Not the same as iOS's clock**, and deliberately so. `elapsedRealtime` counts deep sleep, so
 * sleep is spent out of the deadline's budget. iOS's `DispatchTime` is `mach_absolute_time` and
 * does not advance while the device sleeps — it keeps the same deadline and recomputes what is
 * left of it, so the budget survives the sleep rather than being reset by it. Five seconds awake
 * then thirty asleep is thirty-five here and five there.
 *
 * Android has a clock that skips sleep too (`uptimeMillis`), so this is a choice rather than a
 * constraint: sleep is spent from the waiting budget, and a wait ends when its deadline is
 * reached — a second of sleep five seconds in is six seconds gone and nothing expired. What an
 * expiry is not is proof that the server said nothing — the app may simply not have been there to hear it — which is why the
 * transport re-checks expiry on this same clock when it wakes instead of trusting a timer that
 * slept alongside it.
 *
 * **What this cannot enforce**: a caller replacing the whole object on ACK, or building a retry's
 * deadlines from the previous send. Those are call-site mistakes and the transport's tests own
 * them — `t=0` send with an ACK at `t=19` must still deliver by `t=45`, and a retry sent at `t=25`
 * must deliver by `t=70`.
 */
class TopicRequestDeadlines private constructor(val sentAtMillis: Long) {
    val ackByMillis: Long = sentAtMillis + TopicRequestPolicy.ACK_TIMEOUT.inWholeMilliseconds
    val deliverByMillis: Long = sentAtMillis + TopicRequestPolicy.DELIVERY_TIMEOUT.inWholeMilliseconds

    init {
        // A trip-wire on the constants rather than on the caller: the arithmetic above cannot
        // produce a delivery deadline before the ACK one unless someone changes 20 and 45 to
        // disagree, which is the day this needs to fail. iOS asserts the same thing at the same
        // point (`WebSocketService.swift:183`).
        require(ackByMillis < deliverByMillis) { "ACK deadline must precede delivery deadline" }
    }

    fun ackOverdue(nowMillis: Long): Boolean = nowMillis >= ackByMillis

    fun deliveryOverdue(nowMillis: Long): Boolean = nowMillis >= deliverByMillis

    companion object {
        /**
         * The latest send instant whose deadlines still fit in a `Long`.
         *
         * Unreachable from a boot-relative clock, and checked anyway: the clock is injected, and an
         * injected clock is a place a test — or a later refactor — can put a number the platform
         * never would. Without the check the addition wraps, both deadlines land in the past, and
         * the `require` above is still satisfied because they wrap in order.
         */
        private val LATEST_SEND_MILLIS =
            Long.MAX_VALUE - TopicRequestPolicy.DELIVERY_TIMEOUT.inWholeMilliseconds

        /**
         * The only way to build one, so the range check cannot be walked around.
         *
         * `private constructor` rather than `internal`: internal is the whole app module, and a
         * constructor reachable from there is a second entrance with no check on it — the sort of
         * thing that is fine until the day something calls it. Found by review.
         */
        fun of(sentAtMillis: Long): TopicRequestDeadlines? =
            if (sentAtMillis < 0 || sentAtMillis > LATEST_SEND_MILLIS) {
                null
            } else {
                TopicRequestDeadlines(sentAtMillis)
            }
    }
}

/**
 * How long one subscribe attempt is given, and how many attempts there are.
 *
 * `ANDROID_V2_PLAN.md §7 S3`. Each attempt that reaches an actual send anchors its own pair of
 * deadlines at that send; a retry discards the previous pair rather than inheriting it. What the
 * attempts share is the count, not a clock.
 */
object TopicRequestPolicy {
    /** iOS `topicCommandTimeoutSeconds`. Long enough that a slow answer is not a lost one. */
    val ACK_TIMEOUT: Duration = 20.seconds

    /** iOS `topicDeliveryTimeoutSeconds`. */
    val DELIVERY_TIMEOUT: Duration = 45.seconds

    /**
     * Attempts per command, **the first one included**.
     *
     * iOS `maxTopicRetryAttempts`. One budget covers every retryable failure — a send that never
     * left, an ACK that never came, and a `temporarily_unavailable` — because separate budgets
     * multiply: three of each is nine sends for one subscribe.
     */
    const val MAX_ATTEMPTS = 3

    /**
     * The attempt number after [attempt], or `null` when the budget is spent.
     *
     * Zero means *no attempt has been started*, which is not the same as no send having happened.
     * An attempt is spent at `nextAttempt(0) = 1`, **before** the token is acquired, so a failure
     * in the preparation that precedes a send has already cost one and does not return the count
     * to zero. Three token failures in a row leave zero sends behind and no budget left.
     */
    fun nextAttempt(attempt: Int): Int? =
        if (attempt in 0 until MAX_ATTEMPTS) attempt + 1 else null

    /** `null` for a send instant outside the contract, rather than a deadline in the past. */
    fun deadlinesFor(sentAtMillis: Long): TopicRequestDeadlines? =
        TopicRequestDeadlines.of(sentAtMillis)
}
