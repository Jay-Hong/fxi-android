package com.jay.fxi.data.remote

import com.jay.fxi.domain.model.FreeTab
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The order a grant's REST bootstraps go out in, and how far apart (L-4f, `ANDROID_V2_PLAN.md` 동결 후 11번).
 *
 * The tab being shown first, then every other price topic, one at a time. Still **all** of [TopicCatalogue.DESIRED] per
 * grant, as 동결 후 5번 has it: laziness belongs to a tab's graph queries, not to these five. The order is a function of the
 * tab and the desired set only — never of what the account holds, which would make the shape of the traffic a statement about
 * its entitlements.
 *
 * The tab is [FreeTab] for its values, order and opening choice (D1), which are the same on the premium surface. Its
 * free-only display properties play no part here.
 */
internal object TopicBootstrapOrder {
    /**
     * The least time between two logical calls after the shown tab's first batch.
     *
     * A starting interval and nothing more. The call waits for a token after it starts and the transport may replay it once
     * on a 401, so this does not bound the real HTTP rate or concurrency against nginx's `3r/s burst=20` — that is measured
     * separately, before runtime wiring.
     */
    val ISSUE_GAP: Duration = 500.milliseconds

    /**
     * The topics a tab can show: its price rows and the live inputs of its graph.
     *
     * `dxy:spot` belongs to both 달러 and 테더 — the frozen iOS feeds `dxyLive` to any graph series named `dxy`, and the tether
     * graph carries one. 뉴스 shows none.
     */
    fun shownBy(tab: FreeTab): List<String> = when (tab) {
        FreeTab.NEWS -> emptyList()
        FreeTab.TETHER -> listOf(TopicCatalogue.TETHER, TopicCatalogue.DXY)
        FreeTab.USD -> listOf(USD, TopicCatalogue.DXY)
        FreeTab.JPY -> listOf(JPY)
        FreeTab.EUR -> listOf(EUR)
    }

    /**
     * Every desired topic once: the shown tab's first, then the tabs in D1 order, then — sorted, so the order is stable —
     * anything desired that no tab claims, which this build has none of but a partial or future set must not silently drop.
     */
    fun plan(tab: FreeTab, desired: Set<String>): List<String> =
        (shownBy(tab) + FreeTab.entries.flatMap(::shownBy) + desired.sorted())
            .filter { it in desired }
            .distinct()

    private val USD = TopicCatalogue.FX[0]
    private val JPY = TopicCatalogue.FX[1]
    private val EUR = TopicCatalogue.FX[2]
}
