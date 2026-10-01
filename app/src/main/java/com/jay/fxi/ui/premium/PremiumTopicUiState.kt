package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.ui.free.RateRowEditor
import com.jay.fxi.ui.rates.RateRowsView
import kotlinx.datetime.Instant

/** C3b's offline and cached-price status lines, using the iOS banner texts. */
internal enum class PremiumTopicBanner(val text: String) {
    OFFLINE("오프라인 모드"),
    REFRESHING_CACHED("저장된 환율 · 최신 데이터 확인 중")
}

/**
 * What the premium topic screen draws for one owner and one accepted tab (R4-c C3b). Pure data: the presenter fills it, the
 * screen only lays it out. [lastUpdated] is the latest observed time among what is shown, and only when a banner is shown.
 */
internal data class PremiumTopicUiState(
    val owner: TopicDisplayOwner?,
    val selectedTab: FreeTab?,
    val heading: String?,
    val rateSections: List<RateRowsView>,
    val rowEditors: List<RateRowEditor>,
    val dollarIndex: TopicDollarIndex?,
    val statusBanner: PremiumTopicBanner?,
    val lastUpdated: Instant?
) {
    /** This model when it still belongs to the current owner, grant turn and tab; [NONE] otherwise. */
    fun forCurrent(display: TopicDisplayState, focus: OwnedTopicFocus?, liveIdentity: AuthIdentityFence?): PremiumTopicUiState {
        val currentOwner = display.owner ?: return NONE
        val currentFocus = focus ?: return NONE
        return if (
            currentOwner.identity == currentFocus.identity &&
            currentOwner.identity == liveIdentity &&
            owner == currentOwner && selectedTab == currentFocus.tab
        ) this else NONE
    }

    companion object {
        val NONE = PremiumTopicUiState(null, null, null, emptyList(), emptyList(), null, null, null)
    }
}
