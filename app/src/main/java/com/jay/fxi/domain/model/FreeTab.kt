package com.jay.fxi.domain.model

/**
 * The five tabs of the free surface, in display order.
 *
 * Order and opening selection are the plan's (`ANDROID_V2_PLAN.md:843`) and iOS's
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
    val currency: SupportedCurrency? = null,
    /**
     * What the graph shows before the user has chosen anything, matching iOS's
     * `FreeCurrencyConfig` and `FreeTetherConfig` exactly.
     *
     * An id the selected period does not carry simply does not appear — `upbit.usdt-krw` has no
     * long-period series, so the tether tab narrows to 빗썸+하나 there on its own. KRX is in none of
     * them: the free tier does not disclose that it exists.
     */
    val defaultVisibleSeriesIds: Set<String> = emptySet()
) {
    NEWS("뉴스", null),
    TETHER(
        "테더", "tether",
        defaultVisibleSeriesIds = setOf("upbit.usdt-krw", "bithumb.usdt-krw", "hana.usd")
    ),
    USD(
        SupportedCurrency.USD_KRW.tabTitle, "usd", SupportedCurrency.USD_KRW,
        setOf("investing.usd", "kb.usd", "hana.usd", "dxy")
    ),
    JPY(
        SupportedCurrency.JPY_KRW.tabTitle, "jpy", SupportedCurrency.JPY_KRW,
        setOf("investing.jpy", "hana.jpy")
    ),
    EUR(
        SupportedCurrency.EUR_KRW.tabTitle, "eur", SupportedCurrency.EUR_KRW,
        setOf("investing.eur", "hana.eur")
    );

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
