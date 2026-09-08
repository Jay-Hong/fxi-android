package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When to re-authenticate a subscription, before the server stops honouring it.
 *
 * A lease is the server saying "this subscription is good for N seconds". Letting one lapse does
 * not produce an error — the topic simply stops arriving — so the whole job is to be early, and to
 * be early about the *right* one.
 */
object TopicLeasePolicy {
    /** How far ahead of expiry to renew. iOS `leaseRenewalLeadSeconds`. */
    const val RENEWAL_LEAD_SECONDS = 180L

    /** The spread, which is subtracted and never added. iOS `leaseJitterUpperBound`. */
    const val JITTER_UPPER_BOUND = 60L

    /**
     * The longest lease that still counts exactly in signed `Long` nanoseconds.
     *
     * Not the limit of what a `Duration` can hold — it holds far more, in coarser units. The limit
     * is the conversion: past this, `inWholeNanoseconds` saturates at `Long.MAX_VALUE`, so every
     * larger lease reads as the same number and the original is gone. A duration that no longer
     * tells the two apart is out of contract, like a negative one.
     *
     * iOS draws the same line for the same reason and lands somewhere else, because it counts in
     * unsigned nanoseconds. The principle transfers; the number does not.
     */
    const val MAX_DURATION_SECONDS = Long.MAX_VALUE / 1_000_000_000

    fun isCountable(durationSeconds: Long): Boolean = durationSeconds in 0..MAX_DURATION_SECONDS

    /**
     * How long to wait before renewing, given every lease the server just acknowledged.
     *
     * **The shortest lease decides.** One timer covers the whole set, so pacing it by the longest
     * would let whichever topic expires first go quiet while the client waits, having renewed
     * nothing, for a deadline that belongs to something else.
     *
     * **The jitter only subtracts.** It exists to keep many clients from renewing at one instant,
     * and it must not be allowed to spend the lead time it spreads inside of. Adding it would not
     * push renewal past expiry — with a draw of at most 60 against a lead of 180 the margin only
     * shrinks to 120 — but the margin is the contract, not a target to bargain with, and a client
     * whose renewal is slow or whose request is retried is exactly the one that needed all of it.
     * A negative [jitter] is refused for the same reason and one worse: past −180 the subtraction
     * turns into an addition large enough to land after expiry, where the topic goes quiet.
     *
     * `null` for anything outside the contract — no leases, a duration that cannot be counted, a
     * draw out of range — because every alternative is a wait, and a wrong wait here is a topic
     * that stops arriving with nothing to show for it.
     */
    fun renewAfter(durations: List<Long>, jitter: Long): Duration? {
        if (durations.isEmpty() || durations.any { !isCountable(it) }) return null
        if (jitter < 0 || jitter > JITTER_UPPER_BOUND) return null
        val shortest = durations.min()
        return maxOf(0L, shortest - RENEWAL_LEAD_SECONDS - jitter).seconds
    }
}
