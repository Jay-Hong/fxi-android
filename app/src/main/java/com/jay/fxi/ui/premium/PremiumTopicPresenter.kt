package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
import com.jay.fxi.ui.free.RateRowEditEntry
import com.jay.fxi.ui.free.RateRowEditor
import com.jay.fxi.ui.free.heading
import com.jay.fxi.ui.rates.RateQuote
import com.jay.fxi.ui.rates.RateRowPresenter
import com.jay.fxi.ui.rates.displayFor
import com.jay.fxi.ui.rates.editableRostersFor

/**
 * R4-c C3b: turns the session's display state, the accepted tab and the live identity into what the premium screen draws.
 * Pure: no Compose, Android, clock, network or store. Prices are drawn through the free tab's own projection and presenter.
 */
internal object PremiumTopicPresenter {
    /** [preferences] must be the ones accepted for the current identity; the map carries no identity of its own. */
    fun present(
        display: TopicDisplayState,
        focus: OwnedTopicFocus?,
        liveIdentity: AuthIdentityFence?,
        preferences: Map<RateRowList, RateRowPreference> = emptyMap()
    ): PremiumTopicUiState {
        val owner = display.owner ?: return PremiumTopicUiState.NONE
        val acceptedFocus = focus ?: return PremiumTopicUiState.NONE
        if (owner.identity != acceptedFocus.identity || owner.identity != liveIdentity) {
            return PremiumTopicUiState.NONE
        }

        val tab = acceptedFocus.tab
        val projection = display.rates.displayFor(tab, preferences)
        val rowEditors = editors(display.rates.editableRostersFor(tab), preferences)
        val editableLists = rowEditors.map { it.list }.toSet()
        val sections = projection.scales.flatMap { RateRowPresenter.present(it) }
            .filter { it.rows.isNotEmpty() || it.list in editableLists }
        val dollarIndex = projection.dollarIndex.takeIf { tab == FreeTab.USD || tab == FreeTab.TETHER }
        val banner = when {
            display.connection == TopicConnectionDisplay.OFFLINE -> PremiumTopicBanner.OFFLINE
            display.containsSeed && !display.cachedRefreshResolved -> PremiumTopicBanner.REFRESHING_CACHED
            else -> null
        }
        val lastUpdated = if (banner == null) null else {
            (sections.flatMap { section -> section.rows.map { it.observedAt } } +
                listOfNotNull(dollarIndex?.at)).maxOrNull()
        }
        return PremiumTopicUiState(
            owner = owner,
            selectedTab = tab,
            heading = tab.heading,
            rateSections = sections,
            rowEditors = rowEditors,
            dollarIndex = dollarIndex,
            statusBanner = banner,
            lastUpdated = lastUpdated
        )
    }

    /** The next preference for [list] after the sheet answered with [order] and [hidden] over what it [seeded]. */
    fun nextRowPreference(
        list: RateRowList,
        stored: RateRowPreference?,
        seeded: List<String>,
        order: List<String>,
        hidden: Set<String>
    ): RateRowPreference {
        // Only the sheet's submitted codes are its scope; a later roster cannot change that scope.
        val arrived = order.toSet()
        val mergedOrder = stored?.order?.let { previous ->
            val pouring = ArrayDeque(order)
            previous.map { if (it in arrived) pouring.removeFirstOrNull() else it }
                .filterNotNull() + pouring
        } ?: order
        val mergedHidden = RateRowRoster.hidden(list, stored).filterNot { it in arrived }.toSet() + hidden
        return RateRowPreference(
            order = if (order == seeded) stored?.order else mergedOrder,
            hidden = mergedHidden
        )
    }

    /** Built from every arrived quote so a hidden row can still be turned back on. */
    private fun editors(
        rosters: Map<RateRowList, List<RateQuote>>,
        preferences: Map<RateRowList, RateRowPreference>
    ): List<RateRowEditor> = rosters.mapNotNull { (list, quotes) ->
        if (quotes.isEmpty()) return@mapNotNull null
        val preference = preferences[list]
        val codes = quotes.map { it.id }
        val hidden = RateRowRoster.hidden(list, preference)
        val projected = RateRowRoster.effective(list, codes, preference).projected
        val byCode = quotes.associateBy { it.id }
        val entries = RateRowRoster.arrangement(list, codes, preference).mapNotNull { code ->
            byCode[code]?.let { RateRowEditEntry(code, it.label, code !in hidden, code == projected) }
        }
        RateRowEditor(list, list.editorTitle, entries)
    }

    private val RateRowList.editorTitle: String
        get() = when (this) {
            RateRowList.FX_BANKS -> "은행 순서 설정"
            RateRowList.TETHER_EXCHANGES -> "소스 순서 설정"
        }
}
