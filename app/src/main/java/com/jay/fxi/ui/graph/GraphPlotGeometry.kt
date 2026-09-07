package com.jay.fxi.ui.graph

/**
 * The rectangle the lines are drawn in, once the axis labels have taken their margins.
 *
 * Trivial arithmetic, deliberately given a name and a home. It is about to have two callers that
 * must agree exactly — the Canvas that draws, and the gesture handler that turns a finger position
 * into an instant. If they computed it separately the anchor of a pinch would sit a few pixels off
 * the finger, and the drift would be invisible in every test that only looked at one of them.
 *
 * The index gutter is the part that is easy to get wrong: it is there only when an index series is
 * on screen, so the plot's left edge moves as the user toggles 달러지수.
 */
data class GraphPlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /**
     * Whether a finger landed on the lines rather than on a gutter.
     *
     * A tap in the value gutter is a tap on the axis labels, and iOS ignores it: the overlay that
     * carries the recognisers is laid over the plot alone.
     *
     * The [isEmpty] guard is not redundant. Inverted bounds fall out for free — `left..right` is an
     * empty range — but a chart laid out at *exactly* its own margins has `left == right`, and that
     * is a singleton range that contains a point. Nothing is drawn on that line, so nothing should
     * be tappable on it either.
     */
    fun contains(x: Float, y: Float): Boolean =
        !isEmpty && x in left..right && y in top..bottom
}

object GraphPlotGeometry {

    /** Room for the rate labels, which sit outside the plot on the right. */
    const val VALUE_GUTTER_DP = 44f

    /** Room for the index labels on the left — claimed only when an index is visible. */
    const val INDEX_GUTTER_DP = 36f

    /** Room for the time labels under the plot. */
    const val BOTTOM_GUTTER_DP = 16f

    /** So the topmost gridline label is not clipped by the edge. */
    const val TOP_INSET_DP = 6f

    fun area(widthPx: Float, heightPx: Float, hasIndex: Boolean, density: Float): GraphPlotArea =
        GraphPlotArea(
            left = if (hasIndex) INDEX_GUTTER_DP * density else 0f,
            top = TOP_INSET_DP * density,
            right = widthPx - VALUE_GUTTER_DP * density,
            bottom = heightPx - BOTTOM_GUTTER_DP * density
        )
}
