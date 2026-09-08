package com.jay.fxi.ui.rates

import kotlin.math.abs

/** Which way a rate moved, when it moved far enough to be worth pointing at. */
enum class RateDirection { UP, DOWN }

/**
 * When a rate row animates, and for how long.
 *
 * One owner for both numbers because the bar's width and the digits inside it move together; when
 * these were literals at the call sites, iOS ended up with eight copies and changing one of them
 * split two screens apart with nothing to notice it.
 */
object RateBarAnimation {
    /** Chosen by the user over the previous 1 second (2026-08-25). */
    const val DURATION_MILLIS = 500

    /**
     * Only the first composition is ours to suppress — animating a row into existence draws every
     * bar growing out of nothing, which reads as a value climbing when nothing moved.
     *
     * D19's other half, Reduce Motion, is the platform's. Compose's window recomposer installs a
     * `MotionDurationScale` that watches `Settings.Global.ANIMATOR_DURATION_SCALE` through a
     * `ContentObserver` (`WindowRecomposer.android.kt`), so "Remove animations" makes every
     * animation on the recomposer's clock finish instantly — and makes them move again the moment
     * it is switched back. Reading the setting here as well was both redundant and worse: a value
     * remembered per row never learns that the user changed their mind.
     */
    fun animates(hasAppeared: Boolean): Boolean = hasAppeared
}

/**
 * The ▲▼ cue's memory: what this row last showed, so the next value can be called up or down.
 *
 * **Does not know about Reduce Motion.** iOS returns before updating its baseline while the setting
 * is on (`SourceRateBarView.swift:169`), so once it is turned off the first cue is measured against
 * a value of unknown age. There is no path through [accept] that skips the update. (A caller can
 * still decline to call it at all — that is the wiring's job to get right, and the wiring's tests.)
 *
 * **[THRESHOLD] is D19's 0.01, with only representation error taken out.** Comparing
 * `abs(delta) >= 0.01` directly loses to floating point at the magnitudes this app shows: stepping
 * one unit at a time from 1390.00 to 1410.00, only **80 of 2000** moves clear it, because a
 * difference the decimal system calls 0.01 arrives as `0.009999999999990905` more often than not.
 * [SLACK] is smaller than any real move and larger than that error, so a one-unit move always
 * counts and a smaller one — 0.002, say — still does not.
 */
class RateCueTracker(private val threshold: Double = RateCue.THRESHOLD) {
    private var baseline: Double? = null

    /**
     * Record [value] and say whether to point at it. The first value only seeds — there is nothing
     * yet for it to have moved from.
     */
    fun accept(value: Double): RateDirection? {
        val previous = baseline
        baseline = value
        if (previous == null) return null
        val delta = value - previous
        if (abs(delta) < threshold - RateCue.SLACK) return null
        return if (delta > 0) RateDirection.UP else RateDirection.DOWN
    }
}

object RateCue {
    /** D19. One unit of the second decimal — the smallest move a row can print. */
    const val THRESHOLD = 0.01

    /**
     * Room for the error in `b - a`, and nothing more.
     *
     * At won magnitudes that error is around 1e-13; a thousandth of a unit is the smallest move
     * anyone would call real. Anything between the two works, and this is far from both edges.
     */
    const val SLACK = 1e-9

    /** How long a cue stays up. A newer cue replaces it rather than queueing behind it. */
    const val VISIBLE_MILLIS = 1000L
}
