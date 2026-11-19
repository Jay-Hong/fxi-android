package com.jay.fxi.ui.free

import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.alert.BankAlertEditorInput
import com.jay.fxi.ui.free.FreeAlertPreviewSession.State
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5d gap hunt r1 G01–G03 (`e5d_gaphunt.claude.r1.md`): the preview session against a hand-written reference.
 *
 * - G01: a paywall signal already handed out dies with any change of owner or tab, even when they come back before delivery.
 * - G02: every phase × every event × current, stale and future token, compared as whole states with what the reference allows.
 * - G03: what a preview captured — quotes, as_of, draft — survives every allowed transition whole, whatever the caller's map does.
 */
class S7E5DPreviewSessionMatrixTest {

    private val asOf = Instant.parse("2026-09-06T02:30:00Z")
    private val laterAsOf = Instant.parse("2026-09-06T03:45:00Z")
    private val quotes: Map<String, Double> = mapOf("kb" to 1400.0, "hana" to 1500.0)
    private val laterQuotes: Map<String, Double> = mapOf("kb" to 1410.5, "shinhan" to 1420.0)
    private val firstDraft = BankAlertEditorInput(Bank.KB, AlertCondition.BELOW, "1400.00", RepeatIntervalChange.Once)
    private val laterDraft = BankAlertEditorInput(Bank.KB, AlertCondition.BELOW, "1410.50", RepeatIntervalChange.Once)
    private val edited = BankAlertEditorInput(Bank.HANA, AlertCondition.ABOVE, "1490", RepeatIntervalChange.Every(RepeatInterval.entries.first()))

    private fun bound() = FreeAlertPreviewSession().apply { invalidate("u1", FreeTab.USD) }

    private fun FreeAlertPreviewSession.signalled(): FreeAlertPreviewSession.PaywallSignal {
        val token = open("u1", FreeTab.USD, quotes, asOf)!!
        assertTrue(subscribe(token))
        val signal = dismissed(token)
        assertNotNull(signal)
        return signal!!
    }

    // ---- G01 -------------------------------------------------------------------------------------------------------------

    private val detours: List<Pair<String?, FreeTab?>> = listOf(
        "u2" to FreeTab.USD,
        "u1" to FreeTab.JPY,
        null to FreeTab.USD,
        "u1" to null,
        "u1" to FreeTab.NEWS,
        "u1" to FreeTab.TETHER
    )

    @Test
    fun anOwnerOrTabThatLeftAndCameBackStillEndsTheSignal() {
        for ((uid, tab) in detours) {
            val s = bound()
            val signal = s.signalled()
            assertTrue("premise $uid/$tab: 전달 가능했다", s.deliverable(signal))
            s.invalidate(uid, tab)
            s.invalidate("u1", FreeTab.USD)
            assertEquals("premise $uid/$tab: 아무것도 열리지 않았다", State.Closed, s.state)
            assertFalse("$uid/$tab 를 거쳐 돌아왔다", s.deliverable(signal))
        }
    }

    /** The host tells the session the binding again on every composition; telling it the same binding is not a change. */
    @Test
    fun theSameBindingToldAgainKeepsTheSignal() {
        val s = bound()
        val signal = s.signalled()
        repeat(3) { s.invalidate("u1", FreeTab.USD) }
        assertTrue(s.deliverable(signal))
    }

    /** A preview opened after the detour belongs to the new binding and still works start to end. */
    @Test
    fun aPreviewOpenedAfterTheDetourSignalsUnderTheNewBinding() {
        val s = bound()
        val old = s.signalled()
        s.invalidate("u2", FreeTab.USD)
        s.invalidate("u1", FreeTab.USD)
        val fresh = s.signalled()
        assertFalse(s.deliverable(old))
        assertTrue(s.deliverable(fresh))
    }

    // ---- G02 -------------------------------------------------------------------------------------------------------------

    private enum class Phase { CLOSED, OPEN, CLOSING_PLAIN, CLOSING_PAYWALL, DISPOSED }

    private sealed interface Event {
        data class Edit(val token: Long) : Event
        data class Close(val token: Long) : Event
        data class Subscribe(val token: Long) : Event
        data class Dismissed(val token: Long) : Event
        data object Open : Event
    }

    /** What an event hands back: the boolean of close/subscribe, the token of open, whether dismissed gave a signal. */
    private data class Outcome(val state: State, val returned: Any?)

    private class Rig(val session: FreeAlertPreviewSession, val current: Long, val stale: Long, val future: Long, val disposed: Boolean)

    /**
     * Each row has a finished preview behind it, so a stale token is one this session really issued: token 1 opened and closed,
     * then token 2 is the row's own (closed again for CLOSED).
     */
    private fun rig(phase: Phase): Rig {
        val s = bound()
        val first = s.open("u1", FreeTab.USD, quotes, asOf)!!
        assertTrue(s.close(first))
        assertNull(s.dismissed(first))
        val second = s.open("u1", FreeTab.USD, quotes, asOf)!!
        assertEquals("premise: 두 번째 token", first + 1, second)
        when (phase) {
            Phase.CLOSED -> { assertTrue(s.close(second)); assertNull(s.dismissed(second)) }
            Phase.OPEN -> Unit
            Phase.CLOSING_PLAIN -> assertTrue(s.close(second))
            Phase.CLOSING_PAYWALL -> assertTrue(s.subscribe(second))
            Phase.DISPOSED -> s.dispose()
        }
        return Rig(s, current = second, stale = first, future = second + 1, disposed = phase == Phase.DISPOSED)
    }

    private fun open(token: Long, input: BankAlertEditorInput = firstDraft) = State.Open("u1", FreeTab.USD, token, quotes, asOf, input)
    private fun closing(token: Long, paywall: Boolean) = State.Closing("u1", FreeTab.USD, token, quotes, asOf, firstDraft, paywall)

    private fun startState(phase: Phase, current: Long): State = when (phase) {
        Phase.CLOSED, Phase.DISPOSED -> State.Closed
        Phase.OPEN -> open(current)
        Phase.CLOSING_PLAIN -> closing(current, paywall = false)
        Phase.CLOSING_PAYWALL -> closing(current, paywall = true)
    }

    /** The reference: what the agreement allows, written without looking at the session's code. */
    private fun expected(phase: Phase, rig: Rig, event: Event): Outcome {
        val start = startState(phase, rig.current)
        val unchanged: Any? = when (event) {
            is Event.Close, is Event.Subscribe -> false
            else -> null
        }
        if (phase == Phase.DISPOSED) return Outcome(start, unchanged)
        return when (phase) {
            Phase.CLOSED -> when (event) {
                Event.Open -> Outcome(State.Open("u1", FreeTab.USD, rig.future, laterQuotes, laterAsOf, laterDraft), rig.future)
                else -> Outcome(start, unchanged)
            }
            Phase.OPEN -> when (event) {
                is Event.Edit -> if (event.token == rig.current) Outcome(open(rig.current, edited), null) else Outcome(start, null)
                is Event.Close -> if (event.token == rig.current) Outcome(closing(rig.current, false), true) else Outcome(start, false)
                is Event.Subscribe -> if (event.token == rig.current) Outcome(closing(rig.current, true), true) else Outcome(start, false)
                is Event.Dismissed -> if (event.token == rig.current) Outcome(State.Closed, null) else Outcome(start, null)
                Event.Open -> Outcome(start, null)
            }
            Phase.CLOSING_PLAIN, Phase.CLOSING_PAYWALL -> when (event) {
                is Event.Dismissed -> if (event.token == rig.current) {
                    Outcome(State.Closed, if (phase == Phase.CLOSING_PAYWALL) "signal" else null)
                } else Outcome(start, null)
                else -> Outcome(start, unchanged)
            }
            Phase.DISPOSED -> error("handled above")
        }
    }

    private fun apply(rig: Rig, event: Event): Outcome {
        val s = rig.session
        val returned: Any? = when (event) {
            is Event.Edit -> { s.edit(event.token, edited); null }
            is Event.Close -> s.close(event.token)
            is Event.Subscribe -> s.subscribe(event.token)
            is Event.Dismissed -> s.dismissed(event.token)?.let { signal ->
                assertEquals(listOf<Any?>("u1", FreeTab.USD, rig.current), listOf(signal.uid, signal.tab, signal.token))
                "signal"
            }
            Event.Open -> s.open("u1", FreeTab.USD, laterQuotes, laterAsOf)
        }
        return Outcome(s.state, returned)
    }

    @Test
    fun everyPhaseAgainstEveryEventAndToken() {
        var cells = 0
        for (phase in Phase.entries) {
            val probe = rig(phase)
            val tokens = listOf("current" to probe.current, "stale" to probe.stale, "future" to probe.future)
            val events: List<Pair<String, Event>> = tokens.flatMap { (name, t) ->
                listOf(
                    "edit/$name" to Event.Edit(t),
                    "close/$name" to Event.Close(t),
                    "subscribe/$name" to Event.Subscribe(t),
                    "dismissed/$name" to Event.Dismissed(t)
                )
            } + ("open" to Event.Open)
            for ((label, event) in events) {
                val rig = rig(phase)
                assertEquals("premise $phase: 시작 상태", startState(phase, rig.current), rig.session.state)
                assertEquals("$phase × $label", expected(phase, rig, event), apply(rig, event))
                cells++
            }
        }
        assertEquals("premise: 5 phase × 13 사건", 65, cells)
    }

    @Test
    fun aCloseThenSubscribeGivesNoPaywall() {
        val s = bound()
        val token = s.open("u1", FreeTab.USD, quotes, asOf)!!
        assertTrue(s.close(token))
        assertFalse("닫는 중의 구독은 의도를 바꾸지 않는다", s.subscribe(token))
        assertEquals(closing(token, paywall = false), s.state)
        assertNull(s.dismissed(token))
        assertEquals(State.Closed, s.state)
    }

    @Test
    fun aSubscribeThenCloseStillGivesOnePaywall() {
        val s = bound()
        val token = s.open("u1", FreeTab.USD, quotes, asOf)!!
        assertTrue(s.subscribe(token))
        assertFalse(s.close(token))
        assertEquals(closing(token, paywall = true), s.state)
        val signal = s.dismissed(token)
        assertNotNull(signal)
        assertTrue(s.deliverable(signal!!))
        assertNull("두 번째 완료", s.dismissed(token))
    }

    /** A stale or future token's completion changes nothing, and the current one still ends the preview afterwards. */
    @Test
    fun aWrongTokensCompletionLeavesTheCurrentOneWorking() {
        for (paywall in listOf(false, true)) {
            val rig = rig(if (paywall) Phase.CLOSING_PAYWALL else Phase.CLOSING_PLAIN)
            val s = rig.session
            assertNull(s.dismissed(rig.stale))
            assertNull(s.dismissed(rig.future))
            assertEquals("paywall=$paywall", closing(rig.current, paywall), s.state)
            val signal = s.dismissed(rig.current)
            assertEquals("paywall=$paywall", paywall, signal != null)
            assertEquals(State.Closed, s.state)
        }
    }

    // ---- G03 -------------------------------------------------------------------------------------------------------------

    @Test
    fun whatAPreviewCapturedSurvivesEveryAllowedTransitionWhole() {
        for (paywall in listOf(false, true)) {
            val source = LinkedHashMap(quotes)
            val s = bound()
            val token = s.open("u1", FreeTab.USD, source, asOf)!!
            source["kb"] = 1.0
            source["woori"] = 2.0
            s.edit(token, edited)
            assertEquals("paywall=$paywall: 편집 뒤", State.Open("u1", FreeTab.USD, token, quotes, asOf, edited), s.state)
            assertTrue(if (paywall) s.subscribe(token) else s.close(token))
            val closingState = State.Closing("u1", FreeTab.USD, token, quotes, asOf, edited, paywall)
            assertEquals("paywall=$paywall: 닫는 중", closingState, s.state)
            source.clear()
            source["kb"] = 3.0
            s.invalidate("u1", FreeTab.USD)
            assertEquals("paywall=$paywall: 닫는 중 원본이 바뀌어도", closingState, s.state)
            assertEquals("paywall=$paywall", paywall, s.dismissed(token) != null)
            assertEquals("paywall=$paywall: draft 는 남지 않는다", State.Closed, s.state)
            val again = s.open("u1", FreeTab.USD, laterQuotes, laterAsOf)!!
            assertEquals("paywall=$paywall: 재open", State.Open("u1", FreeTab.USD, again, laterQuotes, laterAsOf, laterDraft), s.state)
        }
    }

    /** An edit carries only the draft: the captured quotes and as_of stay as they were, including an empty, unknown capture. */
    @Test
    fun anEditTouchesOnlyTheDraft() {
        val s = bound()
        val token = s.open("u1", FreeTab.USD, emptyMap(), null)!!
        val emptyDraft = BankAlertEditorInput(Bank.KB, AlertCondition.BELOW, "", RepeatIntervalChange.Once)
        assertEquals(State.Open("u1", FreeTab.USD, token, emptyMap(), null, emptyDraft), s.state)
        s.edit(token, edited)
        assertEquals(State.Open("u1", FreeTab.USD, token, emptyMap(), null, edited), s.state)
    }
}
