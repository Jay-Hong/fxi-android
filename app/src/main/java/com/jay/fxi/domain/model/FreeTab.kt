package com.jay.fxi.domain.model

/**
 * The five tabs of the free surface, in display order.
 *
 * Order and opening selection are the plan's (`ANDROID_V2_PLAN.md:830`) and iOS's
 * `TabSelection.ordered`: 뉴스 → 테더 → 달러 → 엔화 → 유로, opening on 달러. The three currency
 * titles are read off [SupportedCurrency] rather than repeated here — iOS keeps one ordered list
 * for exactly this reason, and a title that drifts on one surface alone is a change no test would
 * notice.
 *
 * [serverTab] is the free snapshot's tab key. 뉴스 has none: it is not snapshot data, and the plan
 * requires the scheduler to be idle for as long as it is the selection ("뉴스=0").
 */
enum class FreeTab(
    val title: String,
    val serverTab: String?,
    val currency: SupportedCurrency? = null
) {
    NEWS("뉴스", null),
    TETHER("테더", "tether"),
    USD(SupportedCurrency.USD_KRW.tabTitle, "usd", SupportedCurrency.USD_KRW),
    JPY(SupportedCurrency.JPY_KRW.tabTitle, "jpy", SupportedCurrency.JPY_KRW),
    EUR(SupportedCurrency.EUR_KRW.tabTitle, "eur", SupportedCurrency.EUR_KRW);

    /** Whether the snapshot scheduler can be activated for this tab at all. */
    val isData: Boolean get() = serverTab != null

    /** Survives reordering and retitling, which neither an ordinal nor a title would. */
    val storageValue: String get() = name

    companion object {
        /** 뉴스 leads the row, but 달러 is what opens — the plan states both separately. */
        val INITIAL = USD

        /** Anything unrecognised — an older build's value, a corrupt file — opens on 달러. */
        fun fromStorageValue(value: String?): FreeTab =
            entries.firstOrNull { it.storageValue == value } ?: INITIAL

        fun forServerTab(serverTab: String?): FreeTab? =
            serverTab?.let { code -> entries.firstOrNull { it.serverTab == code } }
    }
}
