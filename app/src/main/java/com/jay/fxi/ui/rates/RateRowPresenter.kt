package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.ScalePolicyCalculator
import kotlinx.datetime.Instant

/**
 * One quote, with nothing in it about how to draw one.
 *
 * [id] is the wire's source code — `kb`, `upbit` — which identifies a row across refreshes.
 * [asset] retains the source asset so a mixed tether group can validate each quote. The label is
 * resolved before drawing, including the fallback for an unknown source code.
 */
data class RateQuote(
    val id: String,
    val label: String,
    val value: Double,
    val observedAt: Instant,
    /** The asset this quote is for, when the projection knows it — the tether list mixes two (S1.5-b4a). */
    val asset: String? = null
)

/**
 * A heading's quotes and the assets it accepts. [asset] is the heading's primary asset; [accepts]
 * also allows USD/KRW in the tether list. The presenter checks each known quote asset against it.
 */
data class RateQuoteGroup(
    val title: String,
    val asset: String,
    val quotes: List<RateQuote>,
    val reference: RateReference = RateReference.FirstRow,
    /**
     * The roster this heading is, when it is one the user can rearrange.
     *
     * Carried through rather than matched on the title later: a heading and its editor have to be
     * the same thing, and two places spelling the same Korean string is not the same thing.
     */
    val list: RateRowList? = null,
    /** The assets this group may hold. One, except the tether list, which declares both it mixes (S1.5-b4a). */
    val accepts: Set<String> = setOf(asset)
)

/** What the group's differences are measured against. */
sealed interface RateReference {
    /**
     * The group's own first quote — the rule the rest of the app already uses, so a presenter
     * shared with the premium screen picks the same row it does.
     */
    data object FirstRow : RateReference

    /** A quote under another heading on this scale; it must also be among the drawn quotes. */
    data class External(val quote: RateQuote) : RateReference
}

/**
 * Groups drawn against one ruler.
 *
 * A section is a heading; a scale is what the bar lengths mean. Tether has one heading and one
 * scale for its mixed USD/KRW and USDT/KRW source list, measured from the first visible row.
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
    /** Null for a heading without an editable roster. */
    val list: RateRowList? = null,
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
     * The projection supplies the quote order after applying the roster. This presenter preserves
     * it while computing one display domain for the scale.
     */
    fun present(scale: RateScale): List<RateRowsView> {
        val groups = scale.groups
        require(groups.map { it.accepts }.distinct().size <= 1) {
            "one scale, one accepted asset set: ${groups.map { it.accepts }.distinct()}"
        }
        val drawn = groups.flatMap { it.quotes }
        groups.forEach { group ->
            require(group.asset in group.accepts) {
                "${group.title} declares ${group.asset} outside ${group.accepts}"
            }
            group.quotes.forEach { quote ->
                require(quote.asset == null || quote.asset in group.accepts) {
                    "${group.title} cannot hold ${quote.id} for ${quote.asset}"
                }
            }
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
            // Two sections can carry the same source code. Marking an external reference by code
            // alone would put its border on a namesake in the referring group.
            val ownsReference = group.reference is RateReference.FirstRow
            val reference = when (val r = group.reference) {
                is RateReference.External -> r.quote
                RateReference.FirstRow -> group.quotes.firstOrNull()
            }
            RateRowsView(
                title = group.title,
                asset = group.asset,
                list = group.list,
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
