package com.jay.fxi.ui.rates.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.ui.rates.RateBarAnimation
import com.jay.fxi.ui.rates.RateCue
import com.jay.fxi.ui.rates.RateCueTracker
import com.jay.fxi.ui.rates.RateBarWidth
import com.jay.fxi.ui.rates.RateDirection
import com.jay.fxi.ui.rates.RateDisplay
import com.jay.fxi.ui.rates.RateRow
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.ReferenceBorder
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The filled bar, so a test can measure what the layout actually gave it. */
const val RATE_BAR_TAG = "rate-bar"

/** The column beside the bar — a difference, a cue, or the reference's own name. */
const val RATE_DIFF_TAG = "rate-diff"

/**
 * One rate as a bar: logo and figure inside, the difference beside it, the time on the right.
 *
 * A **copy** of the legacy row's skeleton, not a call into it. `RateBarView.kt` opens with
 * `rate.bankType ?: return`, which silently drops every exchange, and its five D19 breaches — four
 * `tween(1000)`, the pulse, the banded width table, no Reduce Motion, `>= 0.01` compared directly —
 * are all inside the function body with nowhere to inject. Changing its signature would mean
 * editing its only caller, which is a gated file. So it keeps a diff of zero and this owns the
 * decisions, over the pure contract in `com.jay.fxi.ui.rates`.
 *
 * This package is below that one on purpose: the trip-wire that keeps the presenter free of the
 * platform scans that directory and does not recurse, so Compose lives one level down where it
 * cannot quietly climb up.
 */
@Composable
fun RateBarRow(
    row: RateRow,
    domain: ClosedFloatingPointRange<Double>?,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    // First composition snaps: animating a row into existence draws every bar growing out of
    // nothing, which reads as a value climbing when nothing moved. Reduce Motion is not read here —
    // Compose's recomposer already scales every animation by the system setting, and reactively.
    var hasAppeared by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.id) { hasAppeared = true }

    // One fraction moves everything the row draws, and the interpolation is in `Double`.
    //
    // Animating the value as a `Float` — the obvious way — loses the number: 1399.00499 comes back
    // as 1399.01, and it stays wrong after the animation settles, so the figure printed in the bar
    // disagrees with the width and the difference beside it, which are computed from the `Double`.
    // Separate animations per quantity would also be free to drift apart mid-flight.
    //
    // The **domain** is in here too. It belongs to the whole scale, so another row moving changes
    // this row's length without this row's own numbers changing at all — measured, a middle row
    // goes 163.5dp → 141.75dp the instant a neighbour's quote rises. Left out of the transition,
    // that lands as a jump; and the row that did move would shorten first and then grow.
    val frame = RowFrame(
        value = row.value,
        difference = row.difference ?: 0.0,
        low = domain?.start ?: row.value,
        high = domain?.endInclusive ?: row.value
    )
    val roll = remember(row.id) { Animatable(1f) }
    var origin by remember(row.id) { mutableStateOf(frame) }
    var target by remember(row.id) { mutableStateOf(frame) }
    LaunchedEffect(row.id, frame) {
        if (frame == target) return@LaunchedEffect
        // Start from wherever the last roll had reached, so an interrupted one does not jump back.
        origin = lerp(origin, target, roll.value.toDouble())
        target = frame
        roll.snapTo(0f)
        if (RateBarAnimation.animates(hasAppeared)) {
            roll.animateTo(1f, tween(RateBarAnimation.DURATION_MILLIS, easing = LinearEasing))
        } else {
            roll.snapTo(1f)
        }
    }
    val shown = lerp(origin, target, roll.value.toDouble())

    // The cue's memory is this row's: scrolled off a long list and back, it starts over rather than
    // pointing at a move the viewer never had on screen.
    val cue = remember(row.id) { RateCueTracker() }
    var direction by remember(row.id) { mutableStateOf<RateDirection?>(null) }
    LaunchedEffect(row.id, row.value) {
        // Keyed on the value, so a newer move cancels this and restarts the clock — the cue is
        // latest-wins rather than a queue of overlapping timers.
        direction = cue.accept(row.value)
        if (direction != null) {
            delay(RateCue.VISIBLE_MILLIS)
            direction = null
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(metrics.barHeight)) {
        // What the bar may occupy: the row minus the timestamp column and both gaps. Leaving the
        // second gap out lets the longest bar eat into the difference column it is compared against.
        val available = maxWidth - metrics.timestampWidth - metrics.barTimestampSpacing - metrics.barToDiffSpacing
        val barWidth: Dp = RateBarWidth.of(
            value = shown.value,
            low = shown.low,
            high = shown.high,
            available = available.value,
            minBar = metrics.minBarWidth.value,
            diffMin = metrics.diffMinWidth.value
        ).dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .testTag(RATE_BAR_TAG)
                    .width(barWidth)
                    .height(metrics.barHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(row.brandColor)
                    .then(
                        if (row.isReference) {
                            Modifier.border(2.dp, ReferenceBorder, RoundedCornerShape(6.dp))
                        } else {
                            Modifier
                        }
                    )
                    .padding(start = metrics.barInnerStartPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RateRowGlyph(row.id, row.label, Modifier.size(metrics.bankIconSize))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = RateDisplay.format(shown.value),
                    color = Color.White,
                    fontSize = metrics.rateValueFontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false).padding(end = metrics.barInnerEndPadding),
                    textAlign = TextAlign.End,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1.5f), 2f)
                    )
                )
            }

            Spacer(Modifier.width(metrics.barToDiffSpacing))

            Box(
                modifier = Modifier.testTag(RATE_DIFF_TAG).weight(1f).widthIn(min = metrics.diffMinWidth),
                contentAlignment = Alignment.CenterStart
            ) {
                // One of the three is composed, never two stacked at zero alpha: a hidden layer
                // stays in the accessibility tree and the row gets read out twice.
                when {
                    row.isReference -> Text(
                        text = row.label,
                        color = SecondaryText,
                        fontSize = metrics.diffFontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    direction != null -> Text(
                        text = if (direction == RateDirection.UP) "▲" else "▼",
                        color = if (direction == RateDirection.UP) PositiveColor else NegativeColor,
                        fontSize = metrics.directionFontSize,
                        fontWeight = FontWeight.Bold
                    )
                    row.difference != null -> Text(
                        text = formatDifference(shown.difference),
                        color = differenceColor(row.difference),
                        fontSize = metrics.diffFontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(metrics.barTimestampSpacing))

            Timestamp(row, metrics, Modifier.width(metrics.timestampWidth))
        }
    }
}

/**
 * The source's mark: its logo when the app has one, its initials when it does not.
 *
 * Ten banks ship logos; the five exchanges ship none, and this slice cannot draw them. A monogram
 * on the brand colour is the same fallback `BankIconFallback` already uses for a bank whose image
 * fails, so the shape is one the app has already chosen rather than a new one invented here. It is
 * owned by the row instead of borrowed from `BankIcon`, which is four lines of the same thing with
 * no tests and two paid callers — one more consumer is exactly the coupling the graph gate is about.
 * The editing sheet is `internal` to this package and shares this one rather than growing a second
 * copy: the mark a row wears and the mark its entry in the sheet wears have to be the same mark.
 *
 * **The mark carries the name.** Only the reference row prints its source, so without a description
 * here every other row reads out as a number with no owner — worse than the plain two-column list
 * it replaces, which at least said "하나은행". A `contentDescription` on the mark is read in place of
 * the monogram's two letters while leaving the monogram itself on screen.
 */
@Composable
internal fun RateRowGlyph(code: String, label: String, modifier: Modifier = Modifier) {
    val bank = Bank.fromCode(code)
    if (bank != null) {
        Image(
            painter = painterResource(id = bank.iconRes),
            contentDescription = label,
            modifier = modifier.clip(RoundedCornerShape(6.dp))
        )
        return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = monogram(label),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

/** Two characters of the display name — 업비트 → 업비, and an unknown code reads as itself. */
private fun monogram(label: String): String = label.take(2)

@Composable
private fun Timestamp(row: RateRow, metrics: RateLayoutMetrics, modifier: Modifier = Modifier) {
    val (date, time) = remember(row.observedAt) {
        val kst = row.observedAt.toLocalDateTime(TimeZone.of("Asia/Seoul"))
        "%02d-%02d".format(kst.monthNumber, kst.dayOfMonth) to "%02d:%02d".format(kst.hour, kst.minute)
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        Text(date, color = SecondaryText, fontSize = metrics.timestampFontSize, fontWeight = FontWeight.Medium)
        Text(time, color = SecondaryText, fontSize = metrics.timestampFontSize)
    }
}

/** The bar's fill: the source's own brand colour, or a neutral one for a source we do not know. */
private val RateRow.brandColor: Color
    get() = Bank.fromCode(id)?.let { Color(it.colorHex) }
        ?: Exchange.fromCode(id)?.let { Color(it.colorHex) }
        ?: Color(0xFF4A4A4A)

/** Everything the row draws that can move. One fraction carries all of it. */
private data class RowFrame(
    val value: Double,
    val difference: Double,
    val low: Double,
    val high: Double
)

/**
 * Straight-line interpolation, in `Double`, with the ends returned exactly.
 *
 * `from + (to - from) * 1.0` is not `to`: measured, 3000.0 → 921.615 comes out as
 * `921.6149999999998`, which prints `921.61` for a row whose value is `921.62`. A fraction that has
 * finished is not an approximation of the target, so it does not go through the arithmetic at all.
 */
private fun lerp(from: RowFrame, to: RowFrame, fraction: Double): RowFrame = when {
    fraction >= 1.0 -> to
    fraction <= 0.0 -> from
    else -> RowFrame(
        value = from.value + (to.value - from.value) * fraction,
        difference = from.difference + (to.difference - from.difference) * fraction,
        low = from.low + (to.low - from.low) * fraction,
        high = from.high + (to.high - from.high) * fraction
    )
}

private fun formatDifference(difference: Double): String {
    val sign = if (difference >= 0) "+" else ""
    return "$sign${RateDisplay.format(difference)}"
}

private fun differenceColor(difference: Double): Color = when {
    difference > 0.005 -> PositiveColor
    difference < -0.005 -> NegativeColor
    else -> SecondaryText
}
