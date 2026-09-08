package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
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
fun List<ExchangeRate>.asRateScale(
    title: String,
    asset: String,
    preferences: Map<RateRowList, RateRowPreference>? = null
): RateScale = RateScale(listOf(rateGroup(title, asset, roster = RateRowList.FX_BANKS, preferences = preferences)))

private fun List<ExchangeRate>.rateGroup(
    title: String,
    asset: String,
    reference: RateReference = RateReference.FirstRow,
    roster: RateRowList? = null,
    preferences: Map<RateRowList, RateRowPreference>? = null
): RateQuoteGroup {
    require(all { it.currency == asset }) {
        "$title declares $asset but holds ${map { it.currency }.distinct()}"
    }
    return RateQuoteGroup(
        title, asset, shownBy(roster, preferences) { it.bank }.map { it.quote() }, reference, roster
    )
}

private fun List<SourceRate>.sourceGroup(
    title: String,
    asset: String,
    roster: RateRowList? = null,
    preferences: Map<RateRowList, RateRowPreference>? = null
): RateQuoteGroup {
    require(all { it.asset == asset }) {
        "$title declares $asset but holds ${map { it.asset }.distinct()}"
    }
    return RateQuoteGroup(
        title, asset, shownBy(roster, preferences) { it.source }.map { it.quote() }, list = roster
    )
}

/**
 * The roster's answer, applied before the group exists rather than after.
 *
 * Filtering a built group would leave an `External` reference pointing at a quote that is no longer
 * on the scale, which `RateRowPresenter` refuses outright. `FirstRow` would survive it — it is a
 * policy the presenter resolves over whatever list it is handed, not a hold on one quote — but
 * there is no reason to let the two halves of "what is the reference" be decided at two different
 * moments. Filtering first means both are decided over what remains.
 *
 * A list with no roster keeps every row: the tether tab's two USD/KRW headings hold two banks and a
 * reference quote, and nothing about them is a preference yet.
 */
private inline fun <T> List<T>.shownBy(
    roster: RateRowList?,
    preferences: Map<RateRowList, RateRowPreference>?,
    code: (T) -> String
): List<T> {
    if (roster == null) return this
    val codes = map(code)
    // A repeated code has no answer here: the roster speaks about codes, so two rows sharing one
    // would both be kept or both dropped, and any attempt to map the roster's answer back onto rows
    // silently picks one of them for both. The sanitizer guarantees they are distinct — it claims
    // the first `(source, asset)` and refuses the rest (`FreeSnapshotSanitizer.kt:136`) — so this
    // says so out loud rather than letting a caller that skipped it corrupt two rows into one.
    require(codes.distinct().size == codes.size) {
        "duplicate codes in one list: ${codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
    }
    // Mapped rather than filtered: the roster answers with an *order*, not just a set, and a filter
    // would keep the payload's order while claiming to honour the user's. The duplicate guard above
    // is what makes this lookup safe — two rows sharing a code would both resolve to one of them.
    val shown = RateRowRoster.effective(roster, codes, preferences?.get(roster)).codes
    val byCode = mapIndexed { index, item -> codes[index] to item }.toMap()
    return shown.mapNotNull { byCode[it] }
}

/**
 * A free snapshot's rate block, split into the scales it draws.
 *
 * The tether tab returns two: the exchanges are USDT/KRW, and the two USD/KRW headings share one
 * ruler with the reference quote supplying the banks' differences. An absent reference is normal,
 * and then the banks fall back to measuring against their own first row.
 */
fun FreeRate.asRateScales(
    preferences: Map<RateRowList, RateRowPreference>? = null
): List<RateScale> = when (this) {
    is FreeRate.Flat -> listOf(entries.asRateScale("은행별 환율", asset, preferences))

    is FreeRate.Grouped -> {
        val referenceQuote = usdKrwReference?.quote()
        val exchanges = usdtKrw.sourceGroup(
            "거래소 USDT/KRW", primaryAsset, RateRowList.TETHER_EXCHANGES, preferences
        )
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
 * The lists in this payload a user can edit, with every quote that arrived — hidden ones included.
 *
 * The sheet cannot be built from what is on screen: a hidden row is not on screen, and it is
 * exactly the row somebody opens the sheet to turn back on. The tether tab's two USD/KRW headings
 * are absent here because they hold a fixed trio the server picks, not a list anyone arranges.
 */
fun FreeRate.editableRosters(): Map<RateRowList, List<RateQuote>> = when (this) {
    is FreeRate.Flat -> mapOf(RateRowList.FX_BANKS to entries.map { it.quote() })
    is FreeRate.Grouped -> mapOf(RateRowList.TETHER_EXCHANGES to usdtKrw.map { it.quote() })
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
