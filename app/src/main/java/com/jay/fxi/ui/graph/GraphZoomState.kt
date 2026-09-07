package com.jay.fxi.ui.graph

import androidx.compose.runtime.saveable.Saver
import com.jay.fxi.domain.model.GraphPeriod
import androidx.compose.runtime.saveable.listSaver
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

/**
 * Enough of the zoom to put it back: the window's two instants and whether it was following.
 *
 * The gesture baselines are deliberately absent — a saved state is by definition one no finger is
 * on. Epoch milliseconds rather than the objects themselves because `ClosedRange<Instant>` has no
 * saver of its own and only primitives survive the trip.
 */
val GraphZoomStateSaver: Saver<GraphZoomState, Any> = listSaver<GraphZoomState, Any>(
    save = { state ->
        val window = state.visible
        if (window == null) emptyList()
        else listOf(
            window.start.toEpochMilliseconds(),
            window.endInclusive.toEpochMilliseconds(),
            state.isFollowing
        )
    },
    restore = { saved ->
        if (saved.size < 3) GraphZoomState()
        else GraphZoomState(
            visible = Instant.fromEpochMilliseconds(saved[0] as Long)..
                Instant.fromEpochMilliseconds(saved[1] as Long),
            isFollowing = saved[2] as Boolean
        )
    }
)

/**
 * The window to draw for one frame, or null for the whole chart.
 *
 * Pure because the interesting case cannot be reached through a gesture: a window restored from
 * yesterday is dragged back into range by the reducer's own clamp the moment a finger touches it,
 * so an on-device test of that path passes whether this exists or not — measured, not assumed. What
 * it guards is the *untouched* chart, and that is a drawing property no pointer test can see.
 *
 * Two things happen here. Zoom belongs to 1일 alone, so any other period draws the whole frame
 * (iOS guards the same way at `effectiveXDomain`). And the window is clamped against the frame that
 * exists **now**: it survives process death but the day it framed does not, and a window left
 * entirely outside today would otherwise draw gridlines with no lines in them — `plot.isEmpty` is
 * false, because the lines come from the data frame, so not even the empty message appears.
 */
fun GraphZoomState.windowFor(frame: TimeFrame?, period: GraphPeriod): ClosedRange<Instant>? {
    if (period != GraphPeriod.ONE_DAY || frame == null) return null
    val requested = resolved(frame.end) ?: return null
    val rendered = GraphFrame.rendered(frame, period)
    return GraphZoomMath.clampVisibleDomain(
        proposed = requested,
        fullDomain = rendered.start..rendered.end,
        latestAnchor = frame.end,
        minLength = GraphZoomMath.minVisibleLength(period)
    )
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
                    // A second finger supersedes whatever one finger was doing. Left set, the pan's
                    // baseline outlives the gesture that made it — `PinchEnded` clears only pinch
                    // fields — and a finished gesture should leave nothing behind.
                    panBaseline = null,
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
