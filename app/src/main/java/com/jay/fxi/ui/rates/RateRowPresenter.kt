package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.ScalePolicyCalculator
import kotlinx.datetime.Instant

/**
 * One quote, with nothing in it about how to draw one.
 *
 * [id] is the wire's source code — `kb`, `upbit` — which is what the row resolves a logo and a
 * brand colour from, and what makes a row identifiable across a refresh. The label is resolved
 * here rather than at the Canvas because an unknown code has to fall back to *something*, and the
 * projection is the only place that still knows what the server actually sent.
 */
data class RateQuote(
    val id: String,
    val label: String,
    val value: Double,
    val observedAt: Instant
)

/**
 * Quotes that mean the same thing.
 *
 * **[asset] is on the group, never on a quote.** The tether tab shows 업비트's USDT/KRW next to
 * 하나은행's USD/KRW, and subtracting one from the other would be a kimchi premium — a number this
 * screen does not claim to compute. One level up is where the asset can be checked once and then
 * relied on; a quote that carried its own would have to be re-checked at every subtraction.
 *
 * Putting it here does not by itself make a mixed group impossible — the projections are what
 * verify that the quotes they build really are the asset they declare, because they are the last
 * place that still knows.
 */
data class RateQuoteGroup(
    val title: String,
    val asset: String,
    val quotes: List<RateQuote>,
    val reference: RateReference = RateReference.FirstRow
)

/** What the group's differences are measured against. */
sealed interface RateReference {
    /**
     * The group's own first quote — the rule the rest of the app already uses, so a presenter
     * shared with the premium screen picks the same row it does.
     */
    data object FirstRow : RateReference

    /**
     * A quote drawn under a different heading **on the same ruler**.
     *
     * The tether tab's "기준 USD/KRW" is its own section, and the bank section beside it is
     * measured against that row rather than against its own first bank. Without this the section
     * headed "기준" would be measuring nothing and the banks would be measuring each other.
     *
     * It has to be one of the scale's own rows: that is what makes its value part of the shared
     * domain, and what lets the reference marker live in exactly one place.
     */
    data class External(val quote: RateQuote) : RateReference
}

/**
 * Groups drawn against one ruler.
 *
 * A section is a heading; a scale is what the bar lengths mean. The tether tab has three headings
 * and two scales — the exchanges are USDT/KRW and cannot share a domain with the two USD/KRW
 * sections, which must share one with each other or the same 1400원 is drawn at two lengths on one
 * screen.
 */
data class RateScale(val groups: List<RateQuoteGroup>)

/** A row as it will be drawn: still no pixels, but every decision made. */
data class RateRow(
    val id: String,
    val label: String,
    val value: Double,
    val observedAt: Instant,
    val isReference: Boolean,
    /** Null for the reference row itself, and when the group has nothing to measure against. */
    val difference: Double?
)

data class RateRowsView(
    val title: String,
    val asset: String,
    val rows: List<RateRow>,
    /**
     * What the bars are drawn against — `ScalePolicy`'s display range, so one outlier cannot
     * squash the rest. Null when there is nothing to draw.
     */
    val domain: ClosedFloatingPointRange<Double>?
)

/**
 * Turns quotes into rows. No Compose, no Android, no clock, no network.
 *
 * Free and premium reach this through their own projections and get the same answers, which is
 * what S1.5's "same presenter contract test" asks for. A source scan in the tests keeps the
 * dependency claim honest rather than leaving it as a comment.
 */
object RateRowPresenter {

    /**
     * Every group in [scale] measured against one domain.
     *
     * Order is the server's throughout — which bank comes first, and which section. Reordering
     * and hiding belong to the preference layer, and putting them here would mean two owners
     * disagreeing the day that layer arrives.
     */
    fun present(scale: RateScale): List<RateRowsView> {
        val groups = scale.groups
        require(groups.map { it.asset }.distinct().size <= 1) {
            "one scale, one asset: ${groups.map { it.asset }.distinct()}"
        }
        val drawn = groups.flatMap { it.quotes }
        groups.forEach { group ->
            (group.reference as? RateReference.External)?.let { external ->
                require(external.quote in drawn) {
                    "external reference ${external.quote.id} is not drawn on this scale"
                }
            }
        }

        val domain = ScalePolicyCalculator
            .computeValues(drawn.map { it.value })
            .displayRange
            ?.let { (low, high) -> low..high }

        return groups.map { group ->
            // The marker belongs to the group that holds the reference row, and only that group.
            // Two sections can legitimately carry the same source code — the tether tab's banks and
            // its reference section both quote USD/KRW — and marking by code alone would put the
            // border on a row that is merely a namesake, and rob it of the difference it should show.
            val ownsReference = group.reference is RateReference.FirstRow
            val reference = when (val r = group.reference) {
                is RateReference.External -> r.quote
                RateReference.FirstRow -> group.quotes.firstOrNull()
            }
            RateRowsView(
                title = group.title,
                asset = group.asset,
                rows = group.quotes.map { quote -> quote.row(reference, ownsReference) },
                domain = domain
            )
        }
    }

    /**
     * The difference is taken between the values as printed, not as received.
     *
     * A row showing 1400.00 beside one showing 1399.00 has to say +1.00. Subtracting first and
     * rounding afterwards puts a third number on the screen that neither of the other two implies.
     */
    private fun RateQuote.row(reference: RateQuote?, ownsReference: Boolean): RateRow {
        val isReference = ownsReference && reference != null && reference.id == id
        val difference = if (reference == null || isReference) {
            null
        } else {
            RateDisplay.quantized(value) - RateDisplay.quantized(reference.value)
        }
        return RateRow(
            id = id,
            label = label,
            value = value,
            observedAt = observedAt,
            isReference = isReference,
            difference = difference
        )
    }
}
