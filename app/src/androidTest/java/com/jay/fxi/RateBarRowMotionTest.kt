package com.jay.fxi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.rates.RateQuote
import com.jay.fxi.ui.rates.RateQuoteGroup
import com.jay.fxi.ui.rates.RateRowPresenter
import com.jay.fxi.ui.rates.RateScale
import com.jay.fxi.ui.rates.view.RateBarRow
import com.jay.fxi.ui.theme.RateLayoutMetrics
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test

/**
 * Reduce Motion, verified where it is actually decided.
 *
 * The row reads no accessibility setting of its own. Compose's recomposer installs a
 * [MotionDurationScale] that watches `Settings.Global.ANIMATOR_DURATION_SCALE` through a
 * `ContentObserver`, so a scale of zero finishes every animation instantly — and a later change
 * back is picked up, which a value remembered per row could never do. That is the claim, and it is
 * only worth making if something checks it: the test rule can supply the same scale the system
 * would, so it is checked here rather than asserted in a comment.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class RateBarRowMotionTest {

    private val instant = object : MotionDurationScale {
        override val scaleFactor = 0f
    }

    @get:Rule val rule = createComposeRule(effectContext = instant)

    private val at = Instant.parse("2026-09-08T04:30:00Z")

    /**
     * With motion switched off the new figure is there on the very next frame.
     *
     * The clock is held still so the assertion is about the first frame after the change, not about
     * where an animation happens to have got to. Animated, 1399 → 1450 would still be reading 1399
     * at that point.
     */
    @Test
    fun withMotionOffTheNewFigureIsThereImmediately() {
        rule.mainClock.autoAdvance = false
        var value by mutableDoubleStateOf(1399.0)
        rule.setContent {
            val view = remember(value) {
                RateRowPresenter.present(
                    RateScale(
                        listOf(
                            RateQuoteGroup(
                                "은행별 환율", "usd-krw",
                                listOf(
                                    RateQuote("investing", "인베스팅", 1390.0, at),
                                    RateQuote("kb", "국민은행", value, at)
                                )
                            )
                        )
                    )
                ).single()
            }
            Column(Modifier.requiredWidth(320.dp)) {
                view.rows.forEach { RateBarRow(it, view.domain, metrics = RateLayoutMetrics.Phone) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("1399.00").assertExists()

        value = 1450.0
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("1450.00").assertExists()
    }

    /**
     * A value that arrives later settles on itself, not on an approximation of itself.
     *
     * `from + (to - from) * 1.0` is not `to`: 3000.0 → 921.615 lands on `921.6149999999998`, which
     * prints `921.61` for a row whose value is `921.62`. Only a change *after* the first
     * composition goes through the interpolation at all, so the first frame cannot show this.
     */
    @Test
    fun aLaterValueSettlesOnItselfExactly() {
        rule.mainClock.autoAdvance = false
        var value by mutableDoubleStateOf(3000.0)
        rule.setContent {
            val view = remember(value) { oneScale(other = 3000.0, subject = value) }
            Column(Modifier.requiredWidth(320.dp)) {
                view.rows.forEach { RateBarRow(it, view.domain, metrics = RateLayoutMetrics.Phone) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        value = 921.615
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("921.62").assertExists()
    }


    private fun oneScale(other: Double, subject: Double) = RateRowPresenter.present(
        RateScale(
            listOf(
                RateQuoteGroup(
                    "은행별 환율", "usd-krw",
                    listOf(
                        RateQuote("investing", "인베스팅", other, at),
                        RateQuote("kb", "국민은행", subject, at)
                    )
                )
            )
        )
    ).single()
}
