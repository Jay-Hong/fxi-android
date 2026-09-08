package com.jay.fxi.ui.graph

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
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
 * The next finger down, or null if none arrives in time.
 *
 * An extension rather than an inline `withTimeoutOrNull`, because `AwaitPointerEventScope` is
 * `@RestrictsSuspension`: a coroutine builder opened against the lambda receiver cannot call back
 * into it, but one opened inside a function whose *extension* receiver it is can. Compose writes
 * its own `awaitSecondDown` this way for the same reason (`TapGestureDetector.kt`).
 */
private suspend fun AwaitPointerEventScope.awaitNextDownWithin(
    timeoutMillis: Long
): PointerInputChange? =
    withTimeoutOrNull(timeoutMillis) { awaitFirstDown(requireUnconsumed = false) }

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
    onZoom: (GraphZoomState) -> Unit,
    /**
     * What a lone tap means, or null if it means nothing.
     *
     * Fullscreen passes leaving; the inline card passes null, because a tap there is not an exit —
     * fullscreen opens from a button. iOS draws the same line by handing `onSingleTap` to the
     * gesture overlay only in fullscreen (`GraphV2Section.swift`), and the recogniser is not even
     * registered when it is nil (`GestureOverlayView.swift`).
     */
    onSingleTap: (() -> Unit)? = null
): Modifier {
    // The loop outlives any single value it reads. Keyed on `period` alone below, it must not close
    // over a stale plot — the data refreshes hourly and the toggles move under it.
    val currentPlot by rememberUpdatedState(plot)
    val currentZoom by rememberUpdatedState(zoom)
    val emit by rememberUpdatedState(onZoom)
    val dismiss by rememberUpdatedState(onSingleTap)

    if (!enabled) return Modifier

    return Modifier.pointerInput(period) {
        if (period != GraphPeriod.ONE_DAY) return@pointerInput

        // Tap memory has to outlive the gesture: the second tap arrives as a separate
        // `awaitEachGesture` pass, so nothing inside one pass could ever pair them up.
        // Null, not 0: `uptimeMillis` counts from boot and **can be 0** — Compose's own test
        // injector starts its clock there and advances from it (measured on this slice: first tap
        // at 0, second at 100), so the first tap of a run lands exactly on a sentinel that a real
        // value can equal, and is lost. Measured, not guessed: the first double tap written here
        // failed for exactly that reason.
        var previousTapUpAt: Long? = null
        var previousTapAt = Offset.Zero
        // A tap has landed and is still waiting to find out whether it was half of a pair. Only
        // meaningful when there is something for a lone tap to do.
        var tapAwaitingItsPartner = false
        // Compose puts **no** distance limit on the second tap — `TapGestureDetector.kt`'s
        // `awaitSecondDown` enforces the time window and nothing else — so the figure has to come
        // from the platform: `android.view.ViewConfiguration.DOUBLE_TAP_SLOP`, 100dp. `touchSlop * 2`
        // is the tempting guess and it is about 16dp, which rejects taps people mean as a pair.
        // Flat 100dp, not the platform's own figure: the framework multiplies it by 1.5 on XLARGE
        // layouts (`ViewConfiguration.java`), and this chart is the same size on every screen.
        val doubleTapSlop = 100.dp.toPx()

        awaitEachGesture {
            // Not required unconsumed: it may still be the pager's. A scrollable already in motion
            // consumes the down in the Initial pass, and requiring it unconsumed would mean never
            // returning for that touch at all.
            //
            // A lone tap has to outlive its own gesture before it can mean anything: it is a single
            // tap only once the double-tap window closes with no second finger. So the wait for the
            // next gesture *is* the wait, and when it times out the tap that was pending becomes
            // the answer. This is `withTimeoutOrNull` around `awaitFirstDown`, which is what
            // Compose's own double-tap detector does (`TapGestureDetector.kt`'s `awaitSecondDown`),
            // and it is the same arrangement iOS spells `singleTap.require(toFail: doubleTap)`.
            // Firing on the first tap instead is not a shortcut but a defect — iOS measured it on
            // iPadOS 17, where a fullscreen double tap meant to zoom left fullscreen instead.
            //
            // The `dismiss != null` half is a cost guard, not a correctness one, and a mutation
            // proved it: dropping it leaves behaviour identical, because the call it protects is a
            // no-op on null and clearing a stale tap after the window is right in either case. It
            // stays so the inline card, which can never leave, does not arm a 300ms wait after
            // every tap it takes.
            //
            // Two consequences are deliberate rather than overlooked.
            //
            // Leaving is slower here than on the other periods, by the length of the window. iOS
            // accepts the same asymmetry in the same place and says why: a quarter of a second is
            // below notice for an action that closes a screen.
            //
            // A gesture that is not a tap swallows the pending dismissal instead of completing it —
            // tap, then start a pan within the window, and nothing leaves. UIKit would leave, since
            // the double tap has failed by then, and this is the one place the arrangement is
            // deliberately not iOS: dismissing a screen out from under a finger that is mid-drag is
            // worse than making the user tap again.
            // Written out rather than `awaitNextDownWithin(...) ?: run { … }`, which does not
            // compile: `run`'s lambda is one more layer between the restricted scope and
            // `awaitFirstDown`, and the compiler refuses it. The shorter form is the one to reach
            // for and the one that fails, so it is worth saying here.
            var second: PointerInputChange? = null
            if (dismiss != null && tapAwaitingItsPartner) {
                tapAwaitingItsPartner = false
                second = awaitNextDownWithin(viewConfiguration.doubleTapTimeoutMillis)
                if (second == null) {
                    previousTapUpAt = null
                    dismiss?.invoke()
                }
            }
            val down = second ?: awaitFirstDown(requireUnconsumed = false)

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

            // What the tap test needs. `travel` is the distance from the down rather than a sum of
            // per-frame steps — a finger that wanders out and comes back is not a tap, and a sum
            // would also disqualify the jitter of a finger that never really left.
            var maxPointers = 1
            var travel = 0f
            var lastEventAt = down.uptimeMillis
            var terminalConsumed = false

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
            // The terminal gesture goes in a `finally` because the loop has ways out that are not
            // the `break`: an early return above, and `resetPointerInputHandler()` cancelling the
            // coroutine outright — a density change, a view-configuration change, or the `period`
            // key moving under it. Without it a `PinchBegan` with no `PinchEnded` leaves the whole
            // padded frame in `visible`, `isFollowing` false, and a baseline with no finger on it —
            // a gesture that never ended. Only part of that outlives the process: the saver keeps
            // the window and the follow flag and nothing else (`GraphZoomStateSaver`), so the
            // baseline dies with it while the window comes back.
            //
            // It is worth being exact about the damage, because an earlier version of this comment
            // was not: the screen does **not** break. `GraphChart` clamps the stored window before
            // anything reads it (`GraphZoomState.windowFor`), and a window covering the whole frame
            // clamps to null, so the chart still draws everything and a sideways swipe still turns
            // the page. What is wrong is that only that display-side clamp is keeping an unfinished
            // gesture invisible, and the reducer's contract is that a finished gesture leaves
            // nothing behind.
            //
            // Detaching the node is **not** one of those ways, though it looks like one: Compose
            // calls `detachedListener` before `onDetach` (`Modifier.kt`), the listener drops the
            // node from `HitPathTracker`, and that dispatches a synthetic cancel — which arrives
            // as an ordinary event with no pointers pressed and leaves by the `break`. So a card
            // that leaves the list mid-pinch is already handled; the cancellation path is the one
            // no event announces.
            try {
            do {
                event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                // Before the `break`, so the up that ends the touch is the one that gets recorded.
                event.changes.firstOrNull()?.let { lastEventAt = it.uptimeMillis }
                if (pressed > maxPointers) maxPointers = pressed
                // Followed by id rather than by "whichever is pressed": the finger's own up carries
                // the position it lifted from, and a flick whose only real movement is on the up
                // would otherwise measure zero travel and pass for a tap.
                event.changes.firstOrNull { it.id == down.id }?.let {
                    val moved = (it.position - down.position).getDistance()
                    if (moved > travel) travel = moved
                }
                if (pressed == 0) {
                    // A system cancellation arrives here too, and it is not an up: Compose
                    // synthesises `pressed = false` changes with `isInitiallyConsumed = true`
                    // (`SuspendingPointerInputFilter.onCancelPointerInput`). Without this the touch
                    // the system took away would be counted as a tap, and the next real tap would
                    // pair with a finger the user never lifted.
                    terminalConsumed = event.changes.any { it.isConsumed }
                    break
                }

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
                            // Zoomed, and the finger has actually gone somewhere. The slop is what
                            // makes the double tap reachable at all: iOS writes it as
                            // `pan.require(toFail: doubleTap)` (`GestureOverlayView.swift:88`,
                            // "줌된 상태에서 더블탭 reset이 1손가락 pan에 빨려가는 것 방지"), and a
                            // UIKit tap recogniser fails on movement, so waiting for movement is
                            // the same rule. Committing on the first frame instead would mean a
                            // stationary finger on a zoomed chart began a pan — which clears
                            // following — and the tap that was meant to reset the zoom never existed.
                            // `>=`, matching Foundation exactly: `TouchSlopDetector` crosses at
                            // `inDirection >= touchSlop` (`DragGestureDetector.kt`). With `>` the
                            // frame that lands *on* the slop is one the pager crosses and we do
                            // not — it consumes, we see it in Final, and the chart is locked out of
                            // its own pan for the rest of the touch.
                            if (current.isZoomed && travel >= viewConfiguration.touchSlop) {
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

            // Only on the normal way out. Cancellation skips this — a touch the system took away is
            // not a tap — and so do the two `return@awaitEachGesture` above, which are frames with
            // no pressed pointer to have tapped with.
            // Two questions, not one. Leaving is about the whole chart — the plain `clickable`
            // that every other period gets covers the gutters too, and a dismissal that ignored
            // them would make the same pixel behave differently depending on the period, which is
            // the one thing this arrangement exists to avoid. Zooming is about the plot: a double
            // tap on the rate labels has no instant to centre on.
            val tapped = mode == Mode.UNDETERMINED &&
                !terminalConsumed &&
                maxPointers == 1 &&
                // Strictly the complement of the pan gate above, so no travel is both.
                travel < viewConfiguration.touchSlop
            // Duration separates the two as well. `clickable` has no time limit, so a finger held
            // still and then lifted leaves on every other period; excluding it here would make the
            // hold behave differently on 1일 alone. A zoom pair is the other way round — holding is
            // not how anyone starts a double tap, and `UITapGestureRecognizer` rejects it too.
            val brief = lastEventAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
            val tappedThePlot = tapped && brief &&
                area.contains(down.position.x, down.position.y)
            val previousUp = previousTapUpAt
            val sinceLastTap = if (previousUp == null) null else down.uptimeMillis - previousUp
            when {
                // Anything that was not a tap breaks the chain, the way a drag between two taps
                // stops UIKit counting them as a pair.
                !tapped -> previousTapUpAt = null

                tappedThePlot &&
                    sinceLastTap != null &&
                    sinceLastTap >= viewConfiguration.doubleTapMinTimeMillis &&
                    sinceLastTap <= viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - previousTapAt).getDistance() <= doubleTapSlop -> {
                    send(GraphGesture.DoubleTap(down.position.x))
                    // A third tap starts a new pair rather than toggling again — `UITapGestureRecognizer`
                    // with `numberOfTapsRequired = 2` behaves the same way.
                    previousTapUpAt = null
                }

                else -> {
                    // Measured from the up, matched against the next down: the interval UIKit and
                    // Compose both use (`awaitSecondDown` takes `firstUp.uptimeMillis`). Only a tap
                    // on the plot can start a pair; one on a gutter still counts as a tap for the
                    // purpose of leaving, and clears any pair in progress.
                    previousTapUpAt = if (tappedThePlot) lastEventAt else null
                    previousTapAt = down.position
                    // Set only where it is read, so the inline card does not carry a flag forever
                    // that nothing will ever consume.
                    tapAwaitingItsPartner = dismiss != null
                }
            }
            } finally {
                if (pinchStarted) send(GraphGesture.PinchEnded)
                else if (mode == Mode.PAN) send(GraphGesture.PanEnded)
            }
        }
    }
}
