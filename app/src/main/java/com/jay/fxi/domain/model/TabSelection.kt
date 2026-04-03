package com.jay.fxi.domain.model

/**
 * 탭 선택 상태 (통화 탭 + 뉴스 탭)
 *
 * 기존 SupportedCurrency 기반 탭 관리를 확장하여
 * 뉴스 탭을 4번째 페이지로 포함.
 */
sealed class TabSelection {
    data class Currency(val currency: SupportedCurrency) : TabSelection()
    data object News : TabSelection()

    val currencyValue: SupportedCurrency?
        get() = (this as? Currency)?.currency
}
