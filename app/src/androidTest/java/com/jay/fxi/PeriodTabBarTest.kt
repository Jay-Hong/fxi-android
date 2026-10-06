package com.jay.fxi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.components.PeriodTabBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C1-2a contract r1: the shared period bar, after it learns to take a period list and an enabled flag.
 *
 * Oracles: R4c/S4 c12a_api_codex.r1 §2 and §4 (each item is tagged `period_tab:{code}`, a selectable Tab; the new
 * `periods` defaults to all four and `enabled` to true, so the free screen's existing calls are unchanged; an empty list draws
 * nothing; an active period outside the list selects nothing and picks nothing; disabled items keep their selected state and
 * call nothing; the active item stays enabled and a click on it asks nothing, as before).
 *
 * The implementation thread reads but does not edit this file.
 */
class PeriodTabBarTest {

    @get:Rule val rule = createComposeRule()

    private val tabMatcher = SemanticsMatcher("a period tab") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("period_tab:") == true
    }

    private fun tags() = rule.onAllNodes(tabMatcher, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag] }

    private fun tab(period: GraphPeriod) = rule.onNodeWithTag("period_tab:${period.code}", useUnmergedTree = true)

    /** P1: the call the free screen makes - no new arguments - draws the four periods, all enabled, only the active one selected. */
    @Test fun P1_theFreeScreensCallIsUnchanged() {
        rule.setContent { PeriodTabBar(GraphPeriod.ONE_WEEK, {}) }
        assertEquals(GraphPeriod.entries.map { "period_tab:${it.code}" }, tags())
        GraphPeriod.entries.forEach { tab(it).assertIsEnabled() }
        tab(GraphPeriod.ONE_WEEK).assertIsSelected()
        (GraphPeriod.entries - GraphPeriod.ONE_WEEK).forEach { tab(it).assertIsNotSelected() }
    }

    /** P2: a click on the active period asks nothing; a click on another asks for exactly that period once. */
    @Test fun P2_onlyAnotherPeriodIsAskedFor() {
        val asked = mutableListOf<GraphPeriod>()
        rule.setContent { PeriodTabBar(GraphPeriod.ONE_DAY, { asked += it }) }
        tab(GraphPeriod.ONE_DAY).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<GraphPeriod>(), asked)
        tab(GraphPeriod.THREE_MONTHS).performClick()
        rule.waitForIdle()
        assertEquals(listOf(GraphPeriod.THREE_MONTHS), asked)
    }

    /** P3: a period list draws exactly those periods in its order; an empty list draws none. */
    @Test fun P3_theListDecidesWhatIsDrawn() {
        var periods by mutableStateOf(listOf(GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_DAY))
        rule.setContent { PeriodTabBar(GraphPeriod.ONE_DAY, {}, periods = periods) }
        assertEquals(listOf("period_tab:3m", "period_tab:1d"), tags())
        periods = emptyList()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), tags())
    }

    /** P4: an active period outside the list selects nothing and asks for nothing on its own. */
    @Test fun P4_anActivePeriodOutsideTheListSelectsNothing() {
        val asked = mutableListOf<GraphPeriod>()
        rule.setContent { PeriodTabBar(GraphPeriod.THREE_MONTHS, { asked += it }, periods = listOf(GraphPeriod.ONE_DAY)) }
        rule.waitForIdle()
        assertEquals(listOf("period_tab:1d"), tags())
        tab(GraphPeriod.ONE_DAY).assertIsNotSelected()
        assertEquals(emptyList<GraphPeriod>(), asked)
    }

    /** P5: disabled items keep their selected state and ask for nothing. */
    @Test fun P5_disabledItemsAskForNothing() {
        val asked = mutableListOf<GraphPeriod>()
        rule.setContent { PeriodTabBar(GraphPeriod.ONE_DAY, { asked += it }, enabled = false) }
        GraphPeriod.entries.forEach { tab(it).assertIsNotEnabled() }
        tab(GraphPeriod.ONE_DAY).assertIsSelected()
        tab(GraphPeriod.THREE_MONTHS).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<GraphPeriod>(), asked)
    }
}
