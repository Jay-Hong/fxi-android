package com.jay.fxi.ui.graph

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.jay.fxi.domain.model.GraphPeriod

/**
 * Which gesture the fingers are making, for the length of one touch.
 *
 * The states exist to answer one question the reducer cannot: **does the pager get this event?**
 * Everything about *where the window goes* belongs to [GraphZoomReducer]; everything here is about
 * who the touch belongs to.
 */
private enum class Mode {
    /** Nothing decided yet. One finger could still become either a pan or a page swipe. */
    UNDETERMINED,
    PINCH,
    PAN,
    /**
     * An ancestor owns this touch, for the rest of it.
     *
     * Entered on one condition — seeing a change already consumed in the Final pass — checked in
     * two places, because the first `down` is the one event the loop never reaches. Never left.
     * Distinct from [DISCARDED] even though both do nothing now: only this one stops a later
     * pinch, because only this one means the touch was someone else's first.
     */
    PASS_THROUGH,
    /**
     * What is left after a pinch when a finger lifts.
     *
     * Releasing to one finger must not slide into a pan — the remaining finger has usually moved a
     * long way during the pinch, and treating that as a drag throws the window across the chart.
     */
    DISCARDED
}

/** The midpoint of the fingers currently down; the middle of the plot if somehow none are. */
private fun PointerEvent.midpointOfPressedX(area: GraphPlotArea): Float {
    var sum = 0f
    var count = 0
    changes.forEach { if (it.pressed) { sum += it.position.x; count++ } }
    return if (count == 0) area.left + area.width / 2f else sum / count
}

/** The one finger that is down, ignoring any that is merely hovering. */
private fun PointerEvent.pressedPosition(): Offset? = changes.firstOrNull { it.pressed }?.position

/**
 * Pointer handling for the free graph's zoom, on 1일 only.
 *
 * The arbitration rule is the whole point, and it is iOS's:
 * a single finger belongs to the **pager** until something is zoomed
 * (`GraphV2Section.swift:1182` enables the pan recogniser only when `isZoomedOrPanned`).
 * Swallowing it earlier would trap the user on one tab, because the chart fills the page.
 *
 * A hand-rolled loop rather than `detectTransformGestures` + `detectTapGestures`, for the reason
 * the paid graph records at `RateGraphView.kt:452`: once the pan branch consumes an event, a
 * chained tap detector never sees it. Everything has to be decided in one pass.
 */
@Composable
internal fun rememberGraphZoomGestures(
    enabled: Boolean,
    period: GraphPeriod,
    plot: GraphPlot,
    zoom: GraphZoomState,
    onZoom: (GraphZoomState) -> Unit
): Modifier {
    // The loop outlives any single value it reads. Keyed on `period` alone below, it must not close
    // over a stale plot — the data refreshes hourly and the toggles move under it.
    val currentPlot by rememberUpdatedState(plot)
    val currentZoom by rememberUpdatedState(zoom)
    val emit by rememberUpdatedState(onZoom)

    if (!enabled) return Modifier

    return Modifier.pointerInput(period) {
        if (period != GraphPeriod.ONE_DAY) return@pointerInput

        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)   // not consumed: it may still be the pager's

            // Measured once per gesture. `hasIndex` is read off the plot the chart actually drew,
            // never off the toggle set — a series can be switched on and still have no range, and
            // then the two would disagree about where the plot starts.
            val area = GraphPlotGeometry.area(
                widthPx = size.width.toFloat(),
                heightPx = size.height.toFloat(),
                hasIndex = currentPlot.indexRange != null,
                density = density
            )

            fun context() = GraphZoomContext(
                fullDomain = currentPlot.rendered.start..currentPlot.rendered.end,
                latestAnchor = currentPlot.data.end,
                minLength = GraphZoomMath.minVisibleLength(period),
                doubleTapWindow = GraphZoomMath.doubleTapWindow(period),
                followThreshold = GraphZoomMath.followThreshold(period),
                plotLeft = area.left,
                plotWidth = area.width
            )

            // Driven locally for the length of the gesture. Reducing against `currentZoom` instead
            // would feed the machine a state one recomposition behind: the baseline that
            // `PinchBegan` just established would not be visible to the `PinchChanged` that follows
            // it in the same frame, and every pinch would come out as no change at all.
            var current = currentZoom

            fun send(gesture: GraphGesture) {
                current = GraphZoomReducer.reduce(current, gesture, context())
                emit(current)
            }

            var mode = Mode.UNDETERMINED
            // Tracked apart from `mode`, which becomes DISCARDED when a finger lifts: the pinch
            // still has to be closed out or its baseline is never cleared.
            var pinchStarted = false
            var panReference = Offset.Zero
            // `calculateZoom` reports the change since the *last frame*. Multiplied up here because
            // `GraphZoomMath.pinched` takes the ratio against where the gesture began — feeding it
            // per-frame values would leave the window circling its starting length.
            var cumulativeZoom = 1f

            // The first `down` needs the same check as everything after it, and it is the one event
            // the loop below never sees: `awaitFirstDown` returns after the Main pass and the loop
            // starts by waiting for the *next* event. A scrollable that is already in motion
            // consumes the down immediately (`startDragImmediately`), so without this a pinch
            // started on a coasting pager — two fingers down, no movement in between — would still
            // be taken from it.
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                mode = Mode.PASS_THROUGH
            }

            var event: PointerEvent
            // The terminal gesture goes in a `finally` because the loop has two ways out that are
            // not the `break`: an early return above, and `resetPointerInputHandler()` cancelling
            // the coroutine (a density change, a key change, or this node being detached — which
            // happens if the snapshot expires mid-pinch and the card leaves the list). Without it a
            // `PinchBegan` with no `PinchEnded` leaves the whole padded frame in `visible`, which
            // reads as "zoomed", and the next sideways swipe is eaten as a pan instead of turning
            // the page.
            try {
            do {
                event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break

                if (pressed >= 2 && mode != Mode.PASS_THROUGH) {
                    if (mode != Mode.PINCH) {
                        // The midpoint of the fingers, worked out here rather than with
                        // `calculateCentroid`: that one counts only pointers which were *already*
                        // down last frame (`TransformGestureDetector.kt`), so on the very frame the
                        // second finger lands it returns the first finger's position — the anchor
                        // would sit under one finger instead of between them, unlike iOS's
                        // `recognizer.location(in:)`. It also returns `Offset.Unspecified` when no
                        // pointer qualifies, and that `x` is NaN — no reachable path to it was
                        // found in this loop, but computing the midpoint directly costs nothing and
                        // removes the question.
                        send(GraphGesture.PinchBegan(event.midpointOfPressedX(area)))
                        cumulativeZoom = 1f
                        pinchStarted = true
                        mode = Mode.PINCH
                    }
                    val step = event.calculateZoom()
                    if (step != 1f) {
                        cumulativeZoom = (cumulativeZoom * step).coerceIn(0.01f, 100f)
                        send(GraphGesture.PinchChanged(cumulativeZoom))
                    }
                    event.changes.forEach { it.consume() }
                } else {
                    when (mode) {
                        Mode.PINCH -> mode = Mode.DISCARDED
                        Mode.DISCARDED, Mode.PASS_THROUGH -> Unit
                        Mode.UNDETERMINED ->
                            if (current.isZoomed) {
                                send(GraphGesture.PanBegan)
                                panReference = event.pressedPosition() ?: return@awaitEachGesture
                                mode = Mode.PAN
                                // Consumed from the first frame, but no movement is reported yet:
                                // the reference point is this frame, so the drag starts from zero.
                                event.changes.forEach { it.consume() }
                            }
                            // One finger on an unzoomed chart: take nothing, and stay undecided.
                            // Committing to PASS_THROUGH here — which an earlier version did — reads
                            // "one finger" as "the pager's", but the pager has not necessarily
                            // claimed anything yet, and a finger that wobbles a pixel before its
                            // partner arrives is the ordinary way a pinch starts. Blocking that
                            // made pinch fail for everyone who does not land both fingers on the
                            // same frame; only the Final pass below knows who owns this touch.
                        Mode.PAN -> {
                            val here = event.pressedPosition() ?: return@awaitEachGesture
                            send(GraphGesture.PanChanged(here.x - panReference.x))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }

                // The same check the first `down` gets above, for every event after it. Compose
                // gives the Main pass to us before the parent, so the pager's consumption shows up
                // one pass later — and while we have not taken the touch ourselves, a consumed
                // change means the pager (or the surrounding list) is now dragging.
                //
                // It matters because consuming *after* that point is not a polite decline: the
                // parent's drag ends with `onDragStopped(Velocity.Zero)`, and a pager already half
                // a page across settles onto the *next* page. Putting a second finger down
                // mid-swipe would turn the page with both fingers still down.
                if (mode == Mode.UNDETERMINED || mode == Mode.PASS_THROUGH) {
                    val settled = awaitPointerEvent(PointerEventPass.Final)
                    if (settled.changes.any { it.isConsumed }) mode = Mode.PASS_THROUGH
                    if (settled.changes.none { it.pressed }) break
                }
                // Every branch above falls through to here, and the only way out is a `break` —
                // the copied-from loop had `continue`s that made a `while` condition meaningful,
                // and they are gone.
            } while (true)
            } finally {
                if (pinchStarted) send(GraphGesture.PinchEnded)
                else if (mode == Mode.PAN) send(GraphGesture.PanEnded)
            }
        }
    }
}
