package com.jay.fxi.data.alerts

import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.RateSourceSets

internal object ComparisonAlertRowPolicy {
    private val signedReferences = RateSourceSets.TETHER_BANKS + RateSourceSets.USD_KRW_REFERENCE

    fun admits(
        tab: String,
        diffType: String,
        left: Pair<String, String>,
        right: Pair<String, String>
    ): Boolean {
        if (left == right) return false
        val asset = RateSourceSets.TAB_ASSETS[tab] ?: return false
        return when (diffType) {
            "absolute" -> {
                val sources = if (tab == "tether") RateSourceSets.EXCHANGES else RateSourceSets.FX_GRAPH_SOURCES
                left.second == asset && right.second == asset && left.first in sources && right.first in sources
            }
            "signed" -> tab == "tether" && left.second == asset && left.first in RateSourceSets.EXCHANGES &&
                right.second == "usd-krw" && right.first in signedReferences
            else -> false
        }
    }

    class Adopted internal constructor(val rows: List<ComparisonAlertSetting>) {
        val count: Int get() = rows.size

        fun project(tab: String, diffType: String): List<ComparisonAlertSetting> =
            rows.filter { it.tab == tab && it.diffType == diffType }
    }

    fun adopt(uid: String, rows: List<ComparisonAlertSetting>): Adopted {
        val counts = rows.groupingBy { it.id }.eachCount()
        return Adopted(rows.filter {
            counts[it.id] == 1 && it.userId == uid &&
                admits(it.tab, it.diffType, it.leftSource to it.leftAsset, it.rightSource to it.rightAsset)
        })
    }

    fun adoptHistory(
        tab: String,
        diffType: String,
        items: List<ComparisonAlertHistoryItem>
    ): List<ComparisonAlertHistoryItem> = items.filter {
        it.tab == tab && it.diffType == diffType &&
            admits(it.tab, it.diffType, it.leftSource to it.leftAsset, it.rightSource to it.rightAsset)
    }
}
