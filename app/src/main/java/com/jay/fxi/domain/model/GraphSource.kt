package com.jay.fxi.domain.model

/**
 * 그래프 소스 (investing, kb, hana 3개만)
 * colorHex는 UI 레이어에서 Color로 변환하여 사용
 */
enum class GraphSource(
    val code: String,
    val displayName: String,
    val colorHex: Long
) {
    INVESTING("investing", "인베스팅", 0xFF9DB6D8),
    KB("kb", "국민은행", 0xFFFFB200),
    HANA("hana", "하나은행", 0xFF00A7A0);

    companion object {
        fun fromCode(code: String): GraphSource? = entries.find { it.code == code }
    }
}
