package com.jay.fxi.domain.model

/**
 * What counts as a number a price can be.
 *
 * `ANDROID_V2_PLAN.md:129` (I5) splits two jobs that look alike: the wire decoder fails when the
 * shape breaks the contract, and **value sanity is checked where wire becomes domain**. A rate of
 * `-1` is valid JSON and a valid `Double`; nothing before this point has any reason to object, and
 * everything after it — bar widths, differences against a reference, the strictly-newer merge —
 * assumes it never sees one.
 *
 * One rule, one place: the free snapshot sanitizer applied exactly this test and the topic boundary
 * needs the same one. Two copies would be two things to keep in step, and the first drift would be
 * a value one surface refuses and the other draws.
 */
object RateSanity {
    /**
     * A loose ceiling, not an instrument range.
     *
     * Every price this app shows is KRW per unit — a few hundred to a few thousand — or an index
     * near a hundred. The bound is nowhere near any of them on purpose: its job is to catch a
     * decimal-shifted or garbage number, not to encode what each instrument is worth today, which
     * would turn a market move into an outage.
     */
    const val UPPER_BOUND = 1e9

    fun isPlausible(rate: Double): Boolean = rate.isFinite() && rate > 0 && rate < UPPER_BOUND
}
