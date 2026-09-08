package com.jay.fxi.domain.model

/**
 * The five USDT/KRW exchanges the tether tab shows.
 *
 * Names match iOS's `RateSource` entries so the two surfaces read identically. The codes are the
 * server's `source` values; an unknown one is rendered as its raw code rather than dropped, since
 * the sanitizer has already decided what is admissible.
 *
 * [colorHex] is the exchange's brand colour, the same role `Bank.colorHex` plays — what a rate
 * row's bar is filled with. It lives here rather than beside the graph's palette because a chart
 * line and a bar are two views of one identity, and two copies would drift. The graph keeps
 * separate *line* colours on top of these: several brands are too dark to read as a 1dp stroke on
 * a dark chart, which is a drawing problem and not a different identity.
 */
enum class Exchange(val code: String, val displayName: String, val colorHex: Long) {
    UPBIT("upbit", "업비트", 0xFF093687),
    BITHUMB("bithumb", "빗썸", 0xFFFF6D00),
    COINONE("coinone", "코인원", 0xFF004CE4),
    KORBIT("korbit", "코빗", 0xFF000000),
    GOPAX("gopax", "고팍스", 0xFF2C2B2A);

    companion object {
        fun fromCode(code: String): Exchange? = entries.firstOrNull { it.code == code }
    }
}
