package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateSourceSets
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuoteKey
import com.jay.fxi.domain.model.TopicRates

/** What a premium tab draws from the topics: the same scales the free tab draws from its snapshot, and the dollar index beside them. */
data class TopicRateDisplay(val scales: List<RateScale>, val dollarIndex: TopicDollarIndex?)

/**
 * S3-R1: the merged topic quotes, ordered by the source lists [tab] shows — [com.jay.fxi.domain.model.RateSourceSets.PREMIUM_FX_SOURCES]
 * for the FX tabs, the free tab's tether sources for tether — drawn through the free tab's own projection
 * ([com.jay.fxi.domain.model.FreeRate.asRateScales]). Pure: no network,
 * clock or store. Which quote is newer was settled by [TopicRates.merge] and is not decided again here.
 */
fun TopicRates.displayFor(
    tab: FreeTab,
    preferences: Map<RateRowList, RateRowPreference>? = null
): TopicRateDisplay {
    val scales = when (tab) {
        FreeTab.USD, FreeTab.JPY, FreeTab.EUR -> {
            val asset = RateSourceSets.TAB_ASSETS.getValue(requireNotNull(tab.serverTab))
            FreeRate.Flat(
                asset,
                RateSourceSets.PREMIUM_FX_SOURCES.mapNotNull { quotes[TopicQuoteKey(it, asset)] }
                    .map { ExchangeRate(asset, it.source, it.rate, it.at) }
            ).asRateScales(preferences)
        }

        FreeTab.TETHER -> {
            val tetherAsset = RateSourceSets.TAB_ASSETS.getValue(requireNotNull(tab.serverTab))
            val usdAsset = RateSourceSets.TAB_ASSETS.getValue("usd")
            FreeRate.Grouped(
                primaryAsset = tetherAsset,
                usdtKrw = RateSourceSets.EXCHANGES.mapNotNull { quotes[TopicQuoteKey(it, tetherAsset)] }
                    .map { SourceRate(it.source, it.asset, it.rate, it.at) },
                usdKrwBanks = RateSourceSets.TETHER_BANKS.mapNotNull { quotes[TopicQuoteKey(it, usdAsset)] }
                    .map { ExchangeRate(it.asset, it.source, it.rate, it.at) },
                usdKrwReference = quotes[TopicQuoteKey(RateSourceSets.USD_KRW_REFERENCE, usdAsset)]
                    ?.let { ExchangeRate(it.asset, it.source, it.rate, it.at) }
            ).asRateScales(preferences)
        }

        FreeTab.NEWS -> emptyList()
    }
    return TopicRateDisplay(scales, dollarIndex)
}
