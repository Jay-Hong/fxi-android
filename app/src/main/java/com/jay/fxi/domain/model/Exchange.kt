package com.jay.fxi.domain.model

/**
 * The five USDT/KRW exchanges the tether tab shows.
 *
 * Names match iOS's `RateSource` entries so the two surfaces read identically. The codes are the
 * server's `source` values; an unknown one is rendered as its raw code rather than dropped, since
 * the sanitizer has already decided what is admissible.
 */
enum class Exchange(val code: String, val displayName: String) {
    UPBIT("upbit", "업비트"),
    BITHUMB("bithumb", "빗썸"),
    COINONE("coinone", "코인원"),
    KORBIT("korbit", "코빗"),
    GOPAX("gopax", "고팍스");

    companion object {
        fun fromCode(code: String): Exchange? = entries.firstOrNull { it.code == code }
    }
}
