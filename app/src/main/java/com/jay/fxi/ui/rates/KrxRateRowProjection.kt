package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
import com.jay.fxi.domain.model.TopicQuoteKey
import com.jay.fxi.domain.model.TopicRates

internal data class KrxRowGate(val visible: Boolean, val tetherReceived: Boolean)

internal const val KRX_ROW_LABEL = "달러선물"
private const val KRX_SOURCE = "krx"
private const val KRX_ASSET = "usd-krw-futures"

/** Projects an already decided gate without changing the caller's stored preferences. */
internal fun TopicRates.premiumDisplayFor(
    tab: FreeTab,
    preferences: Map<RateRowList, RateRowPreference>? = null,
    krx: KrxRowGate
): TopicRateDisplay {
    if (!krx.visible || (tab != FreeTab.USD && tab != FreeTab.TETHER)) return displayFor(tab, preferences)
    val list = if (tab == FreeTab.USD) RateRowList.FX_BANKS else RateRowList.TETHER_EXCHANGES
    // Select from the unhidden roster, with KRX already inserted, before building the group's quotes.
    val present = premiumEditableRostersFor(tab, krx).getValue(list)
    val shown = present.premiumShownBy(list, preferences?.get(list))
    val hasKrx = present.any { it.id == KRX_SOURCE }
    val base = displayFor(tab, preferences)
    return base.copy(scales = base.scales.map { scale ->
        scale.copy(groups = scale.groups.map { group ->
            if (group.list != list) group else group.copy(
                quotes = shown,
                accepts = if (hasKrx) group.accepts + KRX_ASSET else group.accepts
            )
        })
    })
}

/** The editor keeps all arrived rows, including those a stored preference hides. */
internal fun TopicRates.premiumEditableRostersFor(
    tab: FreeTab,
    krx: KrxRowGate
): Map<RateRowList, List<RateQuote>> {
    val base = editableRostersFor(tab)
    if (!krx.visible || (tab != FreeTab.USD && tab != FreeTab.TETHER)) return base
    if (tab == FreeTab.TETHER && !krx.tetherReceived) return base
    val quote = quotes[TopicQuoteKey(KRX_SOURCE, KRX_ASSET)] ?: return base
    val row = RateQuote(KRX_SOURCE, KRX_ROW_LABEL, quote.rate, quote.at, KRX_ASSET)
    val list = if (tab == FreeTab.USD) RateRowList.FX_BANKS else RateRowList.TETHER_EXCHANGES
    return base + (list to (listOf(row) + base.getValue(list)))
}

/** The gate's hana default also applies to rescue; an explicit hidden opinion keeps the ordinary defaults for rescue. */
private fun List<RateQuote>.premiumShownBy(
    list: RateRowList,
    preference: RateRowPreference?
): List<RateQuote> {
    val codes = map { it.id }
    require(codes.distinct().size == codes.size) { "duplicate codes in one premium list: $codes" }
    val order = preference?.order
    // A stored position wins. A newly offered KRX row goes first without editing that stored order.
    val effectivePreference = if (preference != null && KRX_SOURCE in codes && order != null && KRX_SOURCE !in order) {
        preference.copy(order = listOf(KRX_SOURCE) + order)
    } else {
        preference
    }
    val defaults = RateRowRoster.hiddenByDefault(list).let { hidden ->
        if (list == RateRowList.TETHER_EXCHANGES && preference?.hidden == null) hidden + "hana" else hidden
    }
    val hidden = preference?.hidden ?: defaults
    val ordered = RateRowRoster.arrangement(list, codes, effectivePreference)
    val eligible = ordered.filterNot { it in hidden }
    val selected = if (eligible.isNotEmpty()) eligible else listOfNotNull(ordered.firstOrNull { it !in defaults })
    val byCode = associateBy { it.id }
    return selected.map { byCode.getValue(it) }
}
