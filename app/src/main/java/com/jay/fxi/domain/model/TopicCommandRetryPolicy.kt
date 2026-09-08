package com.jay.fxi.domain.model

import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before sending a subscribe command again.
 *
 * Separate from [TopicReconnectPolicy], which is about reopening a socket: this is about resending
 * a command over a socket that is still up. The two ladders are different shapes — that one grows
 * with the attempt and jitters symmetrically, this one is flat and jitters upward only — so
 * folding them together would mean writing one rule and using it for two policies.
 *
 * Two waits come through here and they differ only in where the base comes from. A silent server
 * gives no `retry_after_seconds`, because it said nothing at all, so [SILENT_RETRY_COOLDOWN] is a
 * number this client chose. A `temporarily_unavailable` carries the server's own floor, and that
 * floor is used instead. Both are then spread the same way, and neither applies to a first send.
 */
object TopicCommandRetryPolicy {
    /**
     * The wait after a send that was never answered — iOS `topicCommandTimeoutRetryDelaySeconds`.
     *
     * Far enough back not to pile a duplicate onto an original request that is merely slow. It is
     * a policy value, not a measured one, on both platforms.
     */
    val SILENT_RETRY_COOLDOWN: Duration = 5.seconds

    /**
     * Jitter is **additive and upward**: `base + U(0, base)` — iOS `topicRetryJitterFractionUpperBound`.
     *
     * Upward only because the base is a floor in the case that matters. When the server sends
     * `retry_after_seconds` it is saying *not before this*, and a symmetric ±20% spread would put
     * half the draws before the time the server asked for.
     */
    const val JITTER_UPPER_BOUND = 1.0

    /**
     * [base] spread by [jitterUnit], a plain `0..1` the caller draws — 0 is the base itself and 1
     * is twice it.
     *
     * Drawn by the caller rather than here, as in [TopicReconnectPolicy]: the shape of the spread
     * is the contract and the draw is not, so a test can pin the ladder without pinning the
     * randomness.
     *
     * `null` for anything outside the contract rather than a clamped guess — a non-positive base
     * or a NaN draw is a caller bug, and a plausible-looking wait would bury it. `null` too for a
     * base past [TopicLeasePolicy.MAX_DURATION_SECONDS], where the nanosecond count saturates and
     * the arithmetic stops being about the base at all, and for a jitter whose own count will not
     * fit. Both are waits this cannot express, and the honest answer is that it cannot.
     */
    fun waitFor(base: Duration, jitterUnit: Double): Duration? {
        if (base <= Duration.ZERO || !base.isFinite()) return null
        if (!jitterUnit.isFinite() || jitterUnit < 0.0 || jitterUnit > 1.0) return null
        // Refused *before* the multiplication, because `inWholeNanoseconds` saturates rather than
        // failing: past this line every base reads as `Long.MAX_VALUE`, and half of a saturated
        // count is a plausible-looking number that has nothing to do with the base it came from.
        // The check has to be on the base itself. Same ceiling and same reason as a lease
        // duration ([TopicLeasePolicy.MAX_DURATION_SECONDS]) — one rule, written once.
        // Found by review, with a base of 1_000_000 days and a draw of 0.5.
        if (base > TopicLeasePolicy.MAX_DURATION_SECONDS.seconds) return null
        // The base is carried through untouched and the jitter is *added* to it. Scaling the whole
        // thing as a `Double` and converting back looked equivalent and was not: past 2^53
        // nanoseconds a `Double` cannot hold every integer, so `base × 1.0` came back a
        // millisecond **short** — a wait earlier than the floor the server asked for, which is the
        // one direction this policy exists to rule out. Found by review, with a base of
        // 4_700_000_001 seconds, inside the allowed range.
        val extra = floor(base.inWholeNanoseconds.toDouble() * JITTER_UPPER_BOUND * jitterUnit)
        if (!extra.isFinite() || extra < 0.0 || extra >= Long.MAX_VALUE.toDouble()) return null
        val wait = base + extra.toLong().nanoseconds
        return wait.takeIf { it.isFinite() }
    }

    /** The wait after a send nobody answered, which has no server floor to honour. */
    fun silentRetryWait(jitterUnit: Double): Duration? =
        waitFor(SILENT_RETRY_COOLDOWN, jitterUnit)
}
