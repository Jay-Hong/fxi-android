package com.jay.fxi.ui.graph

import kotlin.time.Duration
import kotlinx.datetime.Instant

/**
 * What a gesture is allowed to know about the chart under it.
 *
 * Passed in rather than held, because every field of it changes as data arrives and a zoom state
 * that cached them would go on answering with the frame it was born in.
 */
data class GraphZoomContext(
    /** The padded frame — what "everything" means. */
    val fullDomain: ClosedRange<Instant>,
    /**
     * The unpadded right edge of the resolved data window — `GraphFrame.resolve(...)!!.end`, not
     * the newest point. See the note on [GraphZoomMath]. Null when the answer carries no data.
     */
    val latestAnchor: Instant?,
    val minLength: Duration,
    val doubleTapWindow: Duration,
    val followThreshold: Duration,
    val plotLeft: Float,
    val plotWidth: Float
)

/** Something a finger did. Positions are in the chart's own coordinates, as Canvas reports them. */
sealed interface GraphGesture {
    data class PinchBegan(val locationX: Float) : GraphGesture
    /** Cumulative since [PinchBegan], as a recogniser reports it: above 1 is zoom in. */
    data class PinchChanged(val scale: Float) : GraphGesture
    data object PinchEnded : GraphGesture
    data object PanBegan : GraphGesture
    /** Cumulative since [PanBegan], in pixels. */
    data class PanChanged(val translationX: Float) : GraphGesture
    data object PanEnded : GraphGesture
    /** Only sent for taps inside the plot; outside it, iOS does nothing at all. */
    data class DoubleTap(val locationX: Float) : GraphGesture
    /** Changing period, or leaving the chart. */
    data object Reset : GraphGesture
}

/**
 * The zoom, as a value.
 *
 * Compose owns none of this: slice 7 will hold one of these in a `remember` and hand each gesture
 * to [GraphZoomReducer], which is why the transitions can be tested at all. They are worth testing
 * — the awkward cases here are all transitions rather than arithmetic, and one of them is a defect
 * iOS names outright (see [GraphZoomReducer.reduce]'s note on an abandoned pinch).
 */
data class GraphZoomState(
    /** Null is the unzoomed chart. See `GraphZoomMath.clampVisibleDomain`. */
    val visible: ClosedRange<Instant>? = null,
    /** Whether the window rides the live edge. Frozen for the duration of a gesture. */
    val isFollowing: Boolean = true,
    val pinchBaseline: ClosedRange<Instant>? = null,
    val pinchAnchor: GraphZoomMath.Anchor? = null,
    val panBaseline: ClosedRange<Instant>? = null
) {
    val isZoomed: Boolean get() = visible != null

    /**
     * The window to draw: the stored one, moved to the live edge while following.
     *
     * Null while unzoomed, which is what `GraphProjection.plot` reads as "use the whole frame".
     */
    fun resolved(latestAnchor: Instant?): ClosedRange<Instant>? =
        visible?.let { GraphZoomMath.resolvedDomain(it, latestAnchor, isFollowing) }
}

object GraphZoomReducer {

    /**
     * Apply one gesture.
     *
     * Every branch that narrows the window goes through `clampVisibleDomain`, so there is exactly
     * one place where a window can become illegal — or become null and mean "show everything".
     */
    fun reduce(state: GraphZoomState, gesture: GraphGesture, context: GraphZoomContext): GraphZoomState =
        when (gesture) {
            is GraphGesture.PinchBegan -> {
                val baseline = state.baseline(context)
                state.copy(
                    visible = baseline,
                    isFollowing = false,
                    pinchBaseline = baseline,
                    pinchAnchor = GraphZoomMath.locationToInstant(
                        gesture.locationX, context.plotLeft, context.plotWidth, baseline
                    )
                )
            }

            is GraphGesture.PinchChanged -> {
                val baseline = state.pinchBaseline
                val anchor = state.pinchAnchor
                if (baseline == null || anchor == null) state
                else state.copy(visible = context.clamp(GraphZoomMath.pinched(baseline, anchor, gesture.scale)))
            }

            // A pinch that began and never changed — a second finger that touched down and lifted —
            // would otherwise leave `visible` set to the whole frame: non-null, so the chart counts
            // as zoomed and stops following, and the next double tap reads as a reset rather than a
            // zoom in. Re-clamping turns that back into null, because the whole frame is null.
            // A real zoom is unaffected: clamping a legal window returns it unchanged.
            GraphGesture.PinchEnded -> state
                .copy(pinchBaseline = null, pinchAnchor = null)
                .reclampAndReevaluate(context)

            // Pan only bites once something is zoomed — iOS gates the recogniser the same way
            // (`GraphV2Section.swift:1182`), and for the same reason: on the unzoomed chart a
            // horizontal drag belongs to the pager, and a chart that swallowed it would trap the
            // user on one tab. It is also why the end of a pan needs no re-clamp the way the end of
            // a pinch does — there is no "began on the whole frame" case left to collapse.
            GraphGesture.PanBegan ->
                if (!state.isZoomed) state
                else state.baseline(context).let { state.copy(visible = it, isFollowing = false, panBaseline = it) }

            is GraphGesture.PanChanged -> {
                val baseline = state.panBaseline
                // A plot that has not been measured cannot say how far a drag went. Writing the
                // baseline back would throw away where this same gesture had already moved to.
                if (baseline == null || context.plotWidth <= 0f) state
                else {
                    val moved = context.clamp(
                        GraphZoomMath.panTranslated(baseline, gesture.translationX, context.plotWidth)
                    )
                    // Losing the window mid-drag ends the drag. iOS disables the pan recogniser the
                    // moment the chart stops being zoomed, and disabling a recogniser that is
                    // already tracking cancels it — the baseline goes with it. Keeping the baseline
                    // here would let a later change resurrect a window from a gesture that, on iOS,
                    // no longer exists. The pinch has no such rule on purpose: its recogniser is
                    // never disabled, so a pinch may pass out through the whole frame and back in
                    // within one gesture.
                    if (moved == null) GraphZoomState() else state.copy(visible = moved)
                }
            }

            GraphGesture.PanEnded -> state.copy(panBaseline = null).reevaluateFollow(context)

            is GraphGesture.DoubleTap ->
                if (state.isZoomed) {
                    GraphZoomState()
                } else {
                    val baseline = state.baseline(context)
                    val tap = GraphZoomMath.locationToInstant(
                        gesture.locationX, context.plotLeft, context.plotWidth, baseline
                    ).instant
                    val half = context.doubleTapWindow / 2
                    state
                        .copy(visible = context.clamp((tap - half)..(tap + half)))
                        .reevaluateFollow(context)
                }

            GraphGesture.Reset -> GraphZoomState()
        }

    /** What a gesture starts from: where the chart appears to be right now. */
    private fun GraphZoomState.baseline(context: GraphZoomContext): ClosedRange<Instant> =
        resolved(context.latestAnchor) ?: visible ?: context.fullDomain

    private fun GraphZoomState.reclampAndReevaluate(context: GraphZoomContext): GraphZoomState =
        copy(visible = visible?.let { context.clamp(it) }).reevaluateFollow(context)

    /**
     * iOS leaves the flag untouched when there is no window (`reevaluateFollowMode` returns early);
     * this restores it instead, so "not zoomed" always reads as "following". Nothing observes the
     * flag while the window is null — `resolved` returns null either way — so the two agree on
     * screen, and here an abandoned gesture lands back on exactly `GraphZoomState()`.
     */
    private fun GraphZoomState.reevaluateFollow(context: GraphZoomContext): GraphZoomState {
        val window = visible ?: return copy(isFollowing = true)
        return copy(
            isFollowing = GraphZoomMath.shouldFollow(
                window.endInclusive, context.latestAnchor, context.followThreshold
            )
        )
    }

    private fun GraphZoomContext.clamp(proposed: ClosedRange<Instant>): ClosedRange<Instant>? =
        GraphZoomMath.clampVisibleDomain(proposed, fullDomain, latestAnchor, minLength)
}
