package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateSanity
import com.jay.fxi.domain.model.RateSourceSets
import kotlinx.datetime.Instant

internal fun comparisonPreviewBasis(
    tab: FreeTab, rate: FreeRate, asOf: Instant
): Pair<Map<Pair<String, String>, Double>, Instant?> {
    val quotes = linkedMapOf<Pair<String, String>, Double>()
    val asset = RateSourceSets.TAB_ASSETS[tab.serverTab] ?: return quotes to null
    fun collect(source: String, currency: String, price: Double) {
        if (RateSanity.isPlausible(price)) quotes.putIfAbsent(source to currency, price)
    }
    when (rate) {
        is FreeRate.Flat -> {
            if (tab == FreeTab.TETHER || rate.asset != asset) return quotes to null
            for (row in rate.entries) {
                if (row.bank in RateSourceSets.FX_GRAPH_SOURCES && row.currency == asset)
                    collect(row.bank, asset, row.rate)
            }
        }
        is FreeRate.Grouped -> {
            if (tab != FreeTab.TETHER || rate.primaryAsset != asset) return quotes to null
            for (row in rate.usdtKrw) {
                if (row.source in RateSourceSets.EXCHANGES && row.asset == asset)
                    collect(row.source, asset, row.rate)
            }
            for (row in rate.usdKrwBanks) {
                if (row.bank in RateSourceSets.TETHER_BANKS && row.currency == "usd-krw")
                    collect(row.bank, row.currency, row.rate)
            }
            rate.usdKrwReference?.let { row ->
                if (row.bank == RateSourceSets.USD_KRW_REFERENCE && row.currency == "usd-krw")
                    collect(row.bank, row.currency, row.rate)
            }
        }
    }
    return quotes to asOf
}
