package com.jay.fxi.domain.model

import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before trying the socket again, and how many times.
 *
 * D3 says to copy iOS, and the reason iOS looks like this is a real failure it met: a server that
 * accepts the connection, answers the subscribe, and closes — a post-ACK close loop. A client that
 * treats "a frame arrived" as success reopens its retry budget on every lap and hammers the server
 * forever, and the attempt ceiling never fires because the counter never reaches it.
 *
 * So two things are deliberate. The budget is **five attempts**, and it reopens only after the
 * connection has stayed up for [STABILITY_RESET] — not when a frame arrives, and specifically not
 * when a legacy `rates` frame arrives, which the server sends to everyone the moment they connect
 * and which therefore proves nothing at all.
 *
 * The waiting and the counting live here, as a calculation with no clock in it. *When* thirty
 * seconds have passed, and on which connection, is the transport's to know — it holds the channel
 * this budget belongs to, and a reset that outlived its channel would reopen the budget of a
 * connection that is already gone.
 */
object TopicReconnectPolicy {
    /** `WebSocketConfig.maxReconnectAttempts` on iOS. */
    const val MAX_ATTEMPTS = 5

    /** `2s × attempt`, before jitter. */
    val BASE_DELAY: Duration = 2.seconds

    /** ±20%. */
    const val JITTER_FRACTION = 0.2

    /**
     * How long a connection must hold before the budget reopens.
     *
     * A connection that takes one frame and closes again is not a success. Surviving this window is
     * what separates "we are connected" from "we got as far as being answered".
     */
    val STABILITY_RESET: Duration = 30.seconds

    /**
     * The attempt number after this one, or `null` when the budget is spent.
     *
     * Spent is not failure-forever: a manual or lifecycle reconnect is a different trigger and
     * starts again from zero. It is the *automatic* ladder that stops.
     */
    fun nextAttempt(attempt: Int): Int? =
        if (attempt in 0 until MAX_ATTEMPTS) attempt + 1 else null

    /**
     * `2s × attempt`, spread ±20% by [jitterUnit].
     *
     * [jitterUnit] is a plain `0..1` the caller draws, mapped onto `-1..+1` here: 0 gives the
     * earliest allowed wait, 1 the latest, 0.5 the unjittered one. Passing it in rather than
     * drawing it is what makes the ladder reproducible in a test — the shape of the spread is the
     * contract, the draw is not.
     *
     * `null` for anything outside the contract, rather than a clamped guess: an attempt of zero or
     * a jitter of NaN is a caller bug, and a plausible-looking delay would hide it.
     *
     * Computed in nanoseconds and floored, like iOS's `reconnectDelayNanoseconds`. Same policy,
     * different order of operations — iOS scales seconds and converts, this scales nanoseconds —
     * so a few draws land one nanosecond apart (attempt 3 at the top of the spread is one). That
     * is a rounding difference in a jittered wait, not a policy difference, and chasing it would
     * mean copying an order of operations rather than a rule.
     */
    fun delayFor(attempt: Int, jitterUnit: Double): Duration? {
        if (attempt <= 0 || !jitterUnit.isFinite() || jitterUnit < 0.0 || jitterUnit > 1.0) {
            return null
        }
        val signed = jitterUnit * 2.0 - 1.0
        val base = BASE_DELAY.inWholeNanoseconds.toDouble() * attempt
        val spread = base * (1.0 + JITTER_FRACTION * signed)
        if (!spread.isFinite() || spread <= 0.0 || spread >= Long.MAX_VALUE.toDouble()) return null
        return floor(spread).toLong().nanoseconds
    }
}
