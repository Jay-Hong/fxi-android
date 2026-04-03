package com.jay.fxi.domain.model

/**
 * 뉴스 콘텐츠 타입 (API v2: 2종만)
 */
enum class NewsContentType(val code: String) {
    EXTERNAL_LINK("external_link"),
    REPORT_PDF("report_pdf");

    companion object {
        fun fromCode(code: String): NewsContentType? = entries.find { it.code == code }
    }
}
