package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.SourceRate

/**
 * How each domain reaches [RateRowPresenter].
 *
 * Public and here rather than private inside a screen, because S1.5's contract test has to run the
 * same battery over the free and premium shapes — and a projection the test cannot call is a
 * projection the test ends up reimplementing by hand, which is the fixture problem it exists to
 * avoid.
 */

/**
 * A bank list — free FX tabs and the premium rate list are the same shape.
 *
 * [asset] is checked, not taken on faith. This is the last place that still knows what each quote
 * came in as: past here a `RateQuote` has only a value, so a JPY row folded into a USD group would
 * be drawn on the won scale and reported as roughly 450원 below it, with nothing left to catch it.
 * The free sanitizer already refuses a mismatch (`FreeSnapshotSanitizer.kt:134`) — this keeps the
 * guarantee for callers that do not come through it.
 */
fun List<ExchangeRate>.asRateScale(title: String, asset: String): RateScale =
    RateScale(listOf(rateGroup(title, asset)))

private fun List<ExchangeRate>.rateGroup(
    title: String,
    asset: String,
    reference: RateReference = RateReference.FirstRow
): RateQuoteGroup {
    require(all { it.currency == asset }) {
        "$title declares $asset but holds ${map { it.currency }.distinct()}"
    }
    return RateQuoteGroup(title, asset, map { it.quote() }, reference)
}

private fun List<SourceRate>.sourceGroup(title: String, asset: String): RateQuoteGroup {
    require(all { it.asset == asset }) {
        "$title declares $asset but holds ${map { it.asset }.distinct()}"
    }
    return RateQuoteGroup(title, asset, map { it.quote() })
}

/**
 * A free snapshot's rate block, split into the scales it draws.
 *
 * The tether tab returns two: the exchanges are USDT/KRW, and the two USD/KRW headings share one
 * ruler with the reference quote supplying the banks' differences. An absent reference is normal,
 * and then the banks fall back to measuring against their own first row.
 */
fun FreeRate.asRateScales(): List<RateScale> = when (this) {
    is FreeRate.Flat -> listOf(entries.asRateScale("은행별 환율", asset))

    is FreeRate.Grouped -> {
        val referenceQuote = usdKrwReference?.quote()
        val exchanges = usdtKrw.sourceGroup("거래소 USDT/KRW", primaryAsset)
        val banks = usdKrwBanks.rateGroup(
            title = "은행 USD/KRW",
            asset = USD_KRW,
            reference = referenceQuote?.let { RateReference.External(it) } ?: RateReference.FirstRow
        )
        val reference = listOfNotNull(usdKrwReference).rateGroup(USD_KRW_REFERENCE_TITLE, USD_KRW)
        listOf(RateScale(listOf(exchanges)), RateScale(listOf(banks, reference)))
    }
}

/**
 * An unknown code keeps its own name.
 *
 * The sanitizer has already decided what may be shown, so a code this app has no display name for
 * is a source it has not been taught about yet — not a row to drop. Dropping it would take a real
 * quote off the screen and leave nothing to notice it by.
 */
private fun ExchangeRate.quote() = RateQuote(
    id = bank,
    label = Bank.fromCode(bank)?.displayName ?: bank,
    value = rate,
    observedAt = timestamp
)

private fun SourceRate.quote() = RateQuote(
    id = source,
    label = Exchange.fromCode(source)?.displayName ?: source,
    value = rate,
    observedAt = timestamp
)

private const val USD_KRW = "usd-krw"
private const val USD_KRW_REFERENCE_TITLE = "기준 USD/KRW"
