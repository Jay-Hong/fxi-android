package com.jay.fxi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.jay.fxi.ui.rates.RateBarAnimation
import com.jay.fxi.ui.rates.RateQuote
import com.jay.fxi.ui.rates.RateQuoteGroup
import com.jay.fxi.ui.rates.RateRowPresenter
import com.jay.fxi.ui.rates.RateRowsView
import com.jay.fxi.ui.rates.RateScale
import com.jay.fxi.ui.rates.view.RATE_BAR_TAG
import com.jay.fxi.ui.rates.view.RATE_DIFF_TAG
import com.jay.fxi.ui.rates.view.RateBarRow
import com.jay.fxi.ui.theme.RateLayoutMetrics
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the rate row actually lays out, measured on a device.
 *
 * The width arithmetic is already locked on the JVM; what cannot be checked there is whether the
 * `available` the composable passes in matches the space the layout really leaves. Those two drift
 * apart silently — the bar simply grows over the column it is supposed to be compared against — so
 * the row is measured here rather than reasoned about.
 */
class RateBarRowTest {

    @get:Rule val rule = createComposeRule()

    private val at = Instant.parse("2026-09-08T04:30:00Z")
    private val metrics = RateLayoutMetrics.Phone

    /** A narrow row on purpose: this is where `available − diffMin` becomes the binding limit. */
    private val rowWidth = 320.dp

    private fun quote(id: String, label: String, value: Double) = RateQuote(id, label, value, at)

    private fun banks(vararg quotes: RateQuote): RateRowsView = RateRowPresenter
        .present(RateScale(listOf(RateQuoteGroup("은행별 환율", "usd-krw", quotes.toList()))))
        .single()

    private fun show(view: RateRowsView) {
        rule.setContent {
            Column(Modifier.requiredWidth(rowWidth)) {
                view.rows.forEach { RateBarRow(it, view.domain, metrics = metrics) }
            }
        }
    }

    private fun barWidth(index: Int) =
        rule.onAllNodesWithTag(RATE_BAR_TAG)[index].getUnclippedBoundsInRoot().width

    /**
     * The bar stops before the difference column, even at the top of the scale.
     *
     * `available` has to subtract the timestamp column *and both gaps*; leaving one out is invisible
     * on a wide screen and eats the difference on a narrow one.
     */
    @Test
    fun theLongestBarStillLeavesTheDifferenceColumnItsMinimum() {
        show(
            banks(
                quote("investing", "인베스팅", 1398.0),
                quote("kb", "국민은행", 1399.0),
                quote("bs", "부산은행", 1410.0)
            )
        )
        val bar = rule.onAllNodesWithTag(RATE_BAR_TAG)[2].getUnclippedBoundsInRoot()
        val diff = rule.onAllNodesWithTag(RATE_DIFF_TAG)[2].getUnclippedBoundsInRoot()
        assertTrue("막대가 차잇값 칸을 침범했다: bar=${bar.right}, diff=${diff.left}", bar.right <= diff.left)
        assertTrue(
            "차잇값 칸이 최소 폭 ${metrics.diffMinWidth} 미만이다: ${diff.width}",
            diff.width >= metrics.diffMinWidth
        )
        assertTrue("행이 가로로 넘쳤다: ${diff.right}", diff.right <= rowWidth)
    }

    /** The timestamp is on the row, in Seoul time — 04:30Z is 13:30 there. */
    @Test
    fun theRowCarriesItsObservationTime() {
        show(banks(quote("kb", "국민은행", 1399.0), quote("hana", "하나은행", 1410.0)))
        rule.onAllNodesWithText("09-08").assertCountEquals(2)
        rule.onAllNodesWithText("13:30").assertCountEquals(2)
    }

    /** A higher quote is a longer bar — the scale reaches the screen, not just the calculator. */
    @Test
    fun aHigherQuoteIsDrawnLonger() {
        show(
            banks(
                quote("investing", "인베스팅", 1390.0),
                quote("kb", "국민은행", 1400.0),
                quote("hana", "하나은행", 1410.0)
            )
        )
        assertTrue("낮은 호가가 더 길다", barWidth(0) < barWidth(1))
        assertTrue("높은 호가가 더 길지 않다", barWidth(1) < barWidth(2))
    }

    /**
     * The shortest bar is still wide enough to hold what is inside it.
     *
     * The floor is not decoration: at the bottom of a scale a purely proportional bar is a sliver,
     * and the logo and the figure it carries have nowhere to be. Locking the length alone would let
     * the floor go without any test noticing until a row went blank.
     */
    @Test
    fun theShortestBarStillHoldsItsLogoAndFigure() {
        show(
            banks(
                quote("investing", "인베스팅", 1390.0),
                quote("kb", "국민은행", 1400.0),
                quote("hana", "하나은행", 1410.0)
            )
        )
        assertTrue("가장 짧은 막대가 최소 폭보다 좁다: ${barWidth(0)}", barWidth(0) >= metrics.minBarWidth)
        rule.onNodeWithText("1390.00").assertIsDisplayed()
    }

    /**
     * The difference column holds one thing at a time.
     *
     * Stacking the name and the number at zero alpha leaves the hidden one in the accessibility
     * tree and the row gets read out twice. The reference row shows its name; the others show a
     * number, and neither shows the other.
     */
    @Test
    fun theDifferenceColumnHoldsOneThingAtATime() {
        show(banks(quote("kb", "국민은행", 1399.0), quote("hana", "하나은행", 1400.5)))
        // The reference names itself and reports no difference to itself.
        rule.onNodeWithText("국민은행").assertIsDisplayed()
        rule.onAllNodesWithText("+0.00").assertCountEquals(0)
        // The other row reports the difference and does not also print its own name.
        rule.onNodeWithText("+1.50").assertIsDisplayed()
        rule.onAllNodesWithText("하나은행").assertCountEquals(0)
    }

    /**
     * The figure in the bar is the value, not a `Float` that resembles it.
     *
     * Animating the value through `animateFloatAsState` is the obvious way and it loses the number:
     * `1399.00499` prints as `1399.01` once it has been through a `Float`, and it stays wrong after
     * the animation settles — so the figure disagrees with the bar length and the difference beside
     * it, which are computed from the `Double`. Found by review, and this is the value that shows it.
     */
    @Test
    fun theFigureSurvivesTheAnimation() {
        show(banks(quote("kb", "국민은행", 1399.00499), quote("hana", "하나은행", 1410.0)))
        rule.onNodeWithText("1399.00").assertIsDisplayed()
        rule.onAllNodesWithText("1399.01").assertCountEquals(0)
    }

    /**
     * Every row says whose it is, whether or not it prints a name.
     *
     * Only the reference row shows its source in the difference column; the rest show a number. With
     * nothing on the mark, a screen reader gets a figure with no owner — worse than the plain
     * two-column list this replaces, which at least said "하나은행". Found by review.
     */
    @Test
    fun everyRowNamesItsSourceToAScreenReader() {
        show(banks(quote("kb", "국민은행", 1399.0), quote("hana", "하나은행", 1400.5)))
        rule.onNodeWithContentDescription("국민은행").assertExists()
        rule.onNodeWithContentDescription("하나은행").assertExists()
    }

    /**
     * A neighbour's move carries this row's bar with it, rather than jumping it.
     *
     * The domain belongs to the whole scale, so a row's length changes when someone *else*'s quote
     * moves — measured, the middle of 1390/1400/1410 goes 163.5dp → 141.75dp the instant the top
     * one rises to 1430, with its own value and difference untouched. Animating only the row's own
     * numbers leaves that as a jump. Found by review.
     */
    @Test
    fun aNeighboursMoveMovesThisBarSmoothly() {
        rule.mainClock.autoAdvance = false
        var top by mutableDoubleStateOf(1410.0)
        rule.setContent {
            val view = remember(top) {
                RateRowPresenter.present(
                    RateScale(
                        listOf(
                            RateQuoteGroup(
                                "은행별 환율", "usd-krw",
                                listOf(
                                    quote("investing", "인베스팅", 1390.0),
                                    quote("kb", "국민은행", 1400.0),
                                    quote("hana", "하나은행", top)
                                )
                            )
                        )
                    )
                ).single()
            }
            Column(Modifier.requiredWidth(rowWidth)) {
                view.rows.forEach { RateBarRow(it, view.domain, metrics = metrics) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        val before = barWidth(1)

        top = 1430.0
        rule.mainClock.advanceTimeByFrame()
        val justAfter = barWidth(1)
        assertTrue(
            "이웃이 움직이자 가운데 막대가 즉시 튀었다: $before → $justAfter",
            kotlin.math.abs((justAfter - before).value) < 2f
        )

        rule.mainClock.advanceTimeBy(RateBarAnimation.DURATION_MILLIS.toLong() + 100)
        val settled = barWidth(1)
        assertTrue("전환이 끝나고도 그대로다: $before → $settled", settled < before)
    }

    /**
     * An exchange has no logo in this app, so it wears its initials.
     *
     * Ten banks ship images and the five exchanges ship none. Falling through to nothing would give
     * the tether tab five bars with a blank square where the identity should be.
     */
    @Test
    fun anExchangeWithNoLogoWearsItsInitials() {
        val exchanges = RateRowPresenter.present(
            RateScale(
                listOf(
                    RateQuoteGroup(
                        "거래소 USDT/KRW", "usdt-krw",
                        listOf(quote("upbit", "업비트", 1402.0), quote("bithumb", "빗썸", 1403.5))
                    )
                )
            )
        ).single()
        show(exchanges)
        // The monogram is two characters, so it is distinguishable from the full name beside it.
        rule.onNodeWithText("업비").assertIsDisplayed()
        rule.onNodeWithText("업비트").assertIsDisplayed()   // …the reference row naming itself
        rule.onNodeWithText("1402.00").assertIsDisplayed()
        // The two letters are for the eye. A screen reader gets the whole name — 빗썸's row prints
        // a difference rather than its name, so the mark is the only place left to say whose it is.
        rule.onNodeWithContentDescription("빗썸").assertExists()
    }
}
