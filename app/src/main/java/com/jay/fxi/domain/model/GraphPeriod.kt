package com.jay.fxi.domain.model

/**
 * 그래프 기간 구분
 */
enum class GraphPeriod(
    val code: String,
    val displayName: String
) {
    ONE_DAY("1d", "1일"),
    ONE_WEEK("1w", "1주"),
    THREE_MONTHS("3m", "3달"),
    ONE_YEAR("1y", "1년");

    val isRealtime: Boolean
        get() = this == ONE_DAY

    companion object {
        fun fromCode(code: String?): GraphPeriod? = entries.find { it.code == code }
    }
}
