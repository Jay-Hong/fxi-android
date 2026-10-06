package com.jay.fxi.ui.premium.view

import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.premium.graph.GraphV2ScreenState
import com.jay.fxi.ui.premium.graph.GraphV2UiActions
import kotlinx.coroutines.flow.StateFlow

/**
 * The host owns the holder and its lifetime. Observation, fresh reads and actions must run on
 * the same Main dispatcher as the holder, selection session and request coordinator.
 */
internal data class PremiumFxGraphSlot(
    val owner: TopicDisplayOwner,
    val state: StateFlow<GraphV2ScreenState>,
    val currentState: () -> GraphV2ScreenState,
    val actions: GraphV2UiActions,
)

/** The mount owner is not an access grant; currentState() performs the holder's live checks. */
internal fun PremiumFxGraphSlot.acceptedState(owner: TopicDisplayOwner, tab: FreeTab): GraphV2ScreenState? {
    if (this.owner != owner || tab !in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) return null
    val fresh = currentState()
    if (fresh.tab != tab.serverTab) return null
    if (fresh.inlineToken?.let { it.owner != owner || it.tab != fresh.tab } == true ||
        fresh.fullscreenToken?.let { it.owner != owner || it.tab != fresh.tab } == true) return null
    return fresh
}
