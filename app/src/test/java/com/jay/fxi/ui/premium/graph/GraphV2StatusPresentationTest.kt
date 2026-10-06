package com.jay.fxi.ui.premium.graph

import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphSeriesStyle
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Claude-owned S4 C1-2a contract r1 (JVM half): what the premium graph surface says for a holder state, and how its toggles
 * are laid out. r3 (implementation review): SELECTION_PENDING with READY is no longer a thrown breach - the holder reads the
 * session publication twice in one render, so a publication change between the reads can publish it; it is shown as the
 * transient selection check (S02).
 *
 * Oracles: iOS a36682f GraphV2Section.swift :510 ("표시할 그래프 데이터가 없습니다"), :522-525 (loading is a spinner with no
 * text), :527 ("그래프를 불러오지 못했습니다"), :318-332 (references ordered by the exact ids krx.usd-krw-futures,
 * investing.usd, kb.usd, hana.usd; the rest after them in input order). Design: R4c/S4 c12_design_codex.r1 §2,
 * c12a_api_codex.r1 §1 (resolveGraphV2Status follows the already decided content - it never re-derives it; NO_SELECTION,
 * UNSUPPORTED, BLOCKED and the selection states are Android's own text; SELECTION_PENDING with READY is shown as the check; the
 * secondary area shows the save notice in every non-INACTIVE content, and the request failure and the refresh spinner only
 * where an exposed entry is in use - READY, NO_DATA, NO_SELECTION, SELECTION_PENDING - never doubling an ERROR; a throwable's
 * message is never shown; an INITIALIZING selection with the save notice is the failed initialize of the C1-1b r2 holder and
 * says so in the centre with the retry. arrangeGraphV2Toggles puts axisGroup "index" in the index column in input order and every other
 * toggle in the reference flow by exact-id priority then input order, keeping each toggle object as it came).
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2StatusPresentationTest {

    private companion object {
        const val LOADING_FAILED = "그래프를 불러오지 못했습니다"
        const val NO_DATA = "표시할 그래프 데이터가 없습니다"
        const val NO_SELECTION = "표시할 항목을 선택해 주세요."
        const val UNSUPPORTED = "이 기간은 지원하지 않습니다."
        const val BLOCKED = "그래프를 표시할 수 없습니다."
        const val CHECKING = "그래프 표시 설정을 확인하고 있습니다."
        const val RECHECKING = "그래프 표시 설정을 다시 확인하고 있습니다."
        const val UNREADABLE = "그래프 표시 설정을 확인할 수 없습니다."
        const val NOT_SAVED = "그래프 표시 설정을 저장하지 못했습니다."

        fun state(
            content: GraphV2Content,
            selectionStatus: GraphV2SelectionStatus = GraphV2SelectionStatus.READY,
            notice: GraphV2Notice? = null,
            requestFailure: Throwable? = null,
            refreshing: Boolean = false
        ) = GraphV2ScreenState(
            tab = "usd", activePeriod = GraphPeriod.ONE_DAY, periods = GraphPeriod.entries.toList(), content = content,
            selectionStatus = selectionStatus, selection = null, toggles = emptyList(), chart = null, refreshing = refreshing,
            requestFailure = requestFailure, notice = notice, fullscreenOpen = false, inlineToken = null, fullscreenToken = null
        )

        fun center(kind: GraphV2CenterKind, message: String? = null, spinner: Boolean = false, retry: Boolean = false) =
            GraphV2CenterPresentation(kind, message, spinner, retry)

        fun toggle(id: String, axis: String = "krw", selected: Boolean = false, enabled: Boolean = true) =
            GraphV2SeriesToggle(id, GraphSeriesStyle(0xFF000000 + id.hashCode().toLong().and(0xFFFFFF), "label:$id", 1.5f),
                selected, enabled, axis)
    }

    // --- the centre -------------------------------------------------------------------------------------------------

    /** Every content but SELECTION_PENDING maps to one fixed centre; LOADING is a spinner with no text, as on iOS. */
    @Test fun S01_eachContentHasItsCentre() {
        val expected = mapOf(
            GraphV2Content.INACTIVE to center(GraphV2CenterKind.NONE),
            GraphV2Content.READY to center(GraphV2CenterKind.CHART),
            GraphV2Content.LOADING to center(GraphV2CenterKind.STATUS, spinner = true),
            GraphV2Content.ERROR to center(GraphV2CenterKind.STATUS, LOADING_FAILED),
            GraphV2Content.NO_DATA to center(GraphV2CenterKind.STATUS, NO_DATA),
            GraphV2Content.NO_SELECTION to center(GraphV2CenterKind.STATUS, NO_SELECTION),
            GraphV2Content.UNSUPPORTED to center(GraphV2CenterKind.STATUS, UNSUPPORTED),
            GraphV2Content.BLOCKED to center(GraphV2CenterKind.STATUS, BLOCKED)
        )
        assertEquals("every content but SELECTION_PENDING is listed",
            GraphV2Content.entries.toSet() - GraphV2Content.SELECTION_PENDING, expected.keys)
        expected.forEach { (content, centre) -> assertEquals("$content", centre, resolveGraphV2Status(state(content)).center) }
    }

    /**
     * SELECTION_PENDING speaks for the selection status. With READY (a publication change between the holder's two reads in
     * one render) it is the transient check with its spinner - never a crash and never a retry.
     */
    @Test fun S02_aPendingSelectionSaysWhereItStands() {
        val expected = mapOf(
            GraphV2SelectionStatus.UNBOUND to center(GraphV2CenterKind.STATUS, CHECKING),
            GraphV2SelectionStatus.AWAITING_RESTORE to center(GraphV2CenterKind.STATUS, CHECKING, spinner = true),
            GraphV2SelectionStatus.INITIALIZING to center(GraphV2CenterKind.STATUS, CHECKING, spinner = true),
            GraphV2SelectionStatus.CONFIRMING to center(GraphV2CenterKind.STATUS, RECHECKING, spinner = true),
            GraphV2SelectionStatus.UNREADABLE to center(GraphV2CenterKind.STATUS, UNREADABLE, retry = true)
        )
        assertEquals(GraphV2SelectionStatus.entries.toSet() - GraphV2SelectionStatus.READY, expected.keys)
        expected.forEach { (status, centre) ->
            assertEquals("$status", centre, resolveGraphV2Status(state(GraphV2Content.SELECTION_PENDING, status)).center)
        }
        val raced = resolveGraphV2Status(state(GraphV2Content.SELECTION_PENDING, GraphV2SelectionStatus.READY,
            GraphV2Notice.SAVE_NOT_COMMITTED))
        assertEquals("READY under SELECTION_PENDING", center(GraphV2CenterKind.STATUS, CHECKING, spinner = true), raced.center)
        assertEquals("the notice stays beside it", NOT_SAVED, raced.noticeText)
    }

    /** The decided content wins: a LOADING screen whose selection is being re-checked is still a bare spinner. */
    @Test fun S03_theContentIsNotReDecided() {
        assertEquals(center(GraphV2CenterKind.STATUS, spinner = true),
            resolveGraphV2Status(state(GraphV2Content.LOADING, GraphV2SelectionStatus.CONFIRMING)).center)
        assertEquals(center(GraphV2CenterKind.STATUS, UNSUPPORTED),
            resolveGraphV2Status(state(GraphV2Content.UNSUPPORTED, GraphV2SelectionStatus.UNREADABLE)).center)
    }

    // --- the secondary area -----------------------------------------------------------------------------------------

    /**
     * The save notice shows in every content but INACTIVE; the request failure and the refresh spinner only where an exposed
     * entry is in use, never doubling an ERROR centre; nothing at all for INACTIVE; the throwable's own message is never text.
     */
    @Test fun S04_theSecondaryAreaShowsWhatTheCentreDoesNot() {
        val failure = IOException("HTTP 500 from graph v2")
        val usesEntry = setOf(GraphV2Content.READY, GraphV2Content.NO_DATA, GraphV2Content.NO_SELECTION, GraphV2Content.SELECTION_PENDING)
        GraphV2Content.entries.forEach { content ->
            val status = if (content == GraphV2Content.SELECTION_PENDING) GraphV2SelectionStatus.CONFIRMING else GraphV2SelectionStatus.READY
            val shown = resolveGraphV2Status(state(content, status, GraphV2Notice.SAVE_NOT_COMMITTED, failure, refreshing = true))
            val inactive = content == GraphV2Content.INACTIVE
            assertEquals("$content notice", if (inactive) null else NOT_SAVED, shown.noticeText)
            assertEquals("$content failure", if (content in usesEntry) LOADING_FAILED else null, shown.requestFailureText)
            assertEquals("$content refreshing", content in usesEntry, shown.showRefreshingSpinner)
        }
        val quiet = resolveGraphV2Status(state(GraphV2Content.READY))
        assertEquals(null, quiet.noticeText)
        assertEquals(null, quiet.requestFailureText)
        assertEquals(false, quiet.showRefreshingSpinner)
        val both = resolveGraphV2Status(state(GraphV2Content.READY, requestFailure = failure, refreshing = true))
        assertEquals("failure and spinner together", LOADING_FAILED, both.requestFailureText)
        assertEquals(true, both.showRefreshingSpinner)
    }

    /**
     * An initialize that did not commit (SELECTION_PENDING, INITIALIZING, the save notice) says the save failed in the centre
     * and offers the retry; the notice is not repeated beside it. Other pending states keep the notice beside their centre.
     */
    @Test fun S06_aFailedInitializeSaysSoAndOffersTheRetry() {
        val failed = resolveGraphV2Status(state(GraphV2Content.SELECTION_PENDING, GraphV2SelectionStatus.INITIALIZING,
            GraphV2Notice.SAVE_NOT_COMMITTED))
        assertEquals(center(GraphV2CenterKind.STATUS, NOT_SAVED, retry = true), failed.center)
        assertEquals(null, failed.noticeText)
        val confirming = resolveGraphV2Status(state(GraphV2Content.SELECTION_PENDING, GraphV2SelectionStatus.CONFIRMING,
            GraphV2Notice.SAVE_NOT_COMMITTED))
        assertEquals(center(GraphV2CenterKind.STATUS, RECHECKING, spinner = true), confirming.center)
        assertEquals(NOT_SAVED, confirming.noticeText)
    }

    // --- the toggle layout ------------------------------------------------------------------------------------------

    /**
     * Index toggles go to the index column in input order; the rest go to the reference flow by the four exact ids, then the
     * others in input order - no prefix rule, no whitelist; each toggle object is kept as it came; an empty input is empty.
     */
    @Test fun S05_togglesAreArrangedByAxisAndExactIdPriority() {
        val dxy = toggle("dxy", "index", selected = true)
        val newIndex = toggle("vix", "index")
        val krx = toggle("krx.usd-krw-futures", enabled = false)
        val investing = toggle("investing.usd")
        val kb = toggle("kb.usd", selected = true)
        val hana = toggle("hana.usd")
        val newBank = toggle("newbank.usd")
        val shinhan = toggle("shinhan.usd")
        val input = listOf(newBank, hana, newIndex, kb, dxy, shinhan, krx, investing)
        val layout = arrangeGraphV2Toggles(input)
        assertEquals(listOf(newIndex, dxy), layout.indexColumn)
        assertEquals(listOf(krx, investing, kb, hana, newBank, shinhan), layout.referenceFlow)
        layout.referenceFlow.zip(listOf(krx, investing, kb, hana, newBank, shinhan)).forEach { (out, inp) -> assertSame(inp, out) }
        assertEquals("the input list is untouched", listOf(newBank, hana, newIndex, kb, dxy, shinhan, krx, investing), input)

        val jpy = listOf(toggle("investing.jpy"), toggle("kb.jpy"), toggle("hana.jpy")).reversed()
        assertEquals("other tabs keep their input order", jpy, arrangeGraphV2Toggles(jpy).referenceFlow)

        assertEquals(GraphV2ToggleLayout(emptyList(), emptyList()), arrangeGraphV2Toggles(emptyList()))
    }
}
