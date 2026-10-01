package com.jay.fxi.ui.premium.view

import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList

/** Test and accessibility handles of the premium topic screen (R4-c C3c-2). */
internal object PremiumTopicTags {
    const val PAGER = "premium_pager"
    fun tab(tab: FreeTab) = "premium_tab_${tab.name}"
    const val HEADING = "premium_heading"
    const val BANNER = "premium_banner"
    const val BANNER_TIME = "premium_banner_time"
    const val BANNER_ACTION = "premium_banner_action"
    /** The selected tab's rate list in the page, as opposed to the rate fullscreen. */
    const val RATES = "premium_rates"
    fun row(id: String) = "premium_row_$id"
    const val DXY = "premium_dxy"
    fun customize(list: RateRowList) = "premium_customize_${list.name}"
    const val FULLSCREEN = "premium_fullscreen"
    const val FULLSCREEN_LAYER = "premium_fullscreen_layer"
    const val FULLSCREEN_CLOSE = "premium_fullscreen_close"
    const val SETTINGS = "premium_settings"
}
