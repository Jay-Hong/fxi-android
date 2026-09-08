package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.GraphSource

/** How one series is drawn and named. */
data class GraphSeriesStyle(val colorHex: Long, val label: String, val lineWidthDp: Float)

/**
 * Colour and label per series id, following iOS Graph V2's precedence chain.
 *
 * Three things make this more than a lookup table:
 *
 * - **A graph line is not a bar.** Several sources are drawn in a lighter colour here than in the
 *   rate list, because a 1dp line on a dark ground disappears at the brand colour. 업비트, 코인원,
 *   코빗 and 고팍스 each have a line-only override, and so does the futures index — which would
 *   otherwise be indistinguishable from the spot index beside it.
 * - **Some sources have two colours already.** `GraphSource.HANA` and `Bank.HANA` disagree, as do
 *   the two 인베스팅 entries. iOS resolves to the *graph* one, so the same source is the same
 *   colour whichever tab it is drawn on. Reaching for `Bank` first would silently change 하나 and
 *   인베스팅 on the free surface only.
 * - **Three FX banks were re-coloured for lines specifically** (우리·기업·SC) to break up a pile of
 *   blues; their bars are untouched.
 *
 * Draw order is not here: the server's own order is preserved in [PreparedGraph.order] and that is
 * what the chart follows. A second ordering would be a second source of truth.
 */
object GraphSeriesStyles {

    /** Lines only. The rate list and the icons keep the brand colours. */
    private val LINE_OVERRIDES = mapOf(
        "dxy_futures" to 0xFFFF6B6B,
        "upbit.usdt-krw" to 0xFF4691E8,
        "coinone.usdt-krw" to 0xFF7FD0FF,
        "korbit.usdt-krw" to 0xFFD6D6D6,
        "gopax.usdt-krw" to 0xFFFFC94D
    )

    /** 우리·기업·SC as lines. Their bars keep [Bank]'s colours. */
    private val FX_BANK_LINE_OVERRIDES = mapOf(
        "woori" to 0xFFE8590C,
        "ibk" to 0xFFE0218A,
        "sc" to 0xFFA0522D
    )

    private const val FALLBACK_COLOR = 0xFFE8EAED

    fun of(seriesId: String, serverLabel: String): GraphSeriesStyle = GraphSeriesStyle(
        colorHex = colorOf(seriesId),
        label = labelOf(seriesId, serverLabel),
        lineWidthDp = if (sourceOf(seriesId) == "investing") 1.3f else 1.0f
    )

    internal fun colorOf(seriesId: String): Long {
        LINE_OVERRIDES[seriesId]?.let { return it }
        if (seriesId == DXY) return GraphSource.DXY.colorHex
        val source = sourceOf(seriesId)
        FX_BANK_LINE_OVERRIDES[source]?.let { return it }
        // The graph's own colour wins over the bar's where a source has both.
        GraphSource.fromCode(source)?.let { return it.colorHex }
        EXCHANGE_COLORS[source]?.let { return it }
        Bank.fromCode(source)?.let { return it.colorHex }
        return FALLBACK_COLOR
    }

    internal fun labelOf(seriesId: String, serverLabel: String): String {
        if (seriesId == DXY_FUTURES) return "선물지수"
        if (seriesId == DXY) return GraphSource.DXY.displayName
        val source = sourceOf(seriesId)
        Exchange.fromCode(source)?.let { return it.displayName }
        // The app's own short bank names, so the graph's legend reads like the rate list rather
        // than like the wire ("국민은행", not "KB국민은행").
        Bank.fromCode(source)?.let { return it.displayName }
        return serverLabel
    }

    /** `investing.usd` and `bithumb.usdt-krw` both key on what precedes the first dot. */
    internal fun sourceOf(seriesId: String): String = seriesId.substringBefore('.')

    /**
     * The brand colours, owned by [Exchange] because a bar and a line are one identity.
     *
     * Only 빗썸 reaches this: the other four have line overrides above, because their brands are
     * too dark to read as a 1dp stroke on a dark chart. That is a drawing decision layered over the
     * identity, not a second identity.
     */
    private val EXCHANGE_COLORS: Map<String, Long> =
        Exchange.entries.associate { it.code to it.colorHex }

    private const val DXY = "dxy"
    private const val DXY_FUTURES = "dxy_futures"
}
