package com.jay.fxi.ui.free

import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.alert.BankAlertEditorInput
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5d (`s7e5d_api_agreed.r1.md`, 제안 r2 §2.2·§2.5·§3): the free preview's life as a plain state machine. It opens only on
 * this owner's active bank tab, freezes that moment's quotes and as_of, ignores every event that names another token, ends
 * only on a confirmed hide, hands back at most one paywall signal, and once disposed does nothing at all.
 */
class S7E5DPreviewSessionTest {

    private val asOf = Instant.parse("2026-09-06T02:30:00Z")
    private val quotes = mapOf("kb" to 1400.0, "hana" to 1500.0)

    private fun bound(uid: String? = "u1", tab: FreeTab? = FreeTab.USD) = FreeAlertPreviewSession().apply { invalidate(uid, tab) }
    private fun FreeAlertPreviewSession.openUsd(q: Map<String, Double> = quotes, at: Instant? = asOf) = open("u1", FreeTab.USD, q, at)!!
    private fun FreeAlertPreviewSession.openState() = state as FreeAlertPreviewSession.State.Open
    private val edited = BankAlertEditorInput(Bank.HANA, AlertCondition.ABOVE, "1490", RepeatIntervalChange.Every(RepeatInterval.entries.first()))

    @Test
    fun aPreviewOpensOnKbBelowOnceWithTheFrozenQuoteAndAsOf() {
        val s = bound()
        val token = s.openUsd()
        val open = s.openState()
        assertEquals(listOf<Any?>("u1", FreeTab.USD, token, quotes, asOf), listOf(open.uid, open.tab, open.token, open.quotes, open.asOf))
        assertEquals(Bank.KB, open.input.bank)
        assertEquals(AlertCondition.BELOW, open.input.condition)
        assertEquals("1400.00", open.input.thresholdText)
        assertEquals(RepeatIntervalChange.Once, open.input.repeat)
    }

    /** Empty or expired data is no reason to refuse: the preview opens on nothing, with no made-up price. */
    @Test
    fun anEmptyAnswerOpensOnNothing() {
        val s = bound()
        s.openUsd(emptyMap(), null)
        val open = s.openState()
        assertEquals("", open.input.thresholdText)
        assertEquals(emptyMap<String, Double>(), open.quotes)
        assertNull(open.asOf)
    }

    /** What the preview opened with is a copy: the caller's map changing, a new snapshot or expiry, touches nothing. */
    @Test
    fun theOpenedQuotesAreACopy() {
        val s = bound()
        val source = LinkedHashMap(quotes)
        s.open("u1", FreeTab.USD, source, asOf)
        source["kb"] = 9999.0
        source.remove("hana")
        s.invalidate("u1", FreeTab.USD)
        assertEquals(quotes, s.openState().quotes)
        assertEquals(asOf, s.openState().asOf)
        assertTrue("premise: 원본은 바뀌었다", source != quotes)
    }

    @Test
    fun itOpensOnlyForTheCurrentOwnerOnItsActiveBankTabAndOnlyWhenClosed() {
        assertNull("다른 owner", bound().open("u2", FreeTab.USD, quotes, asOf))
        assertNull("활성 아닌 탭(offscreen page)", bound().open("u1", FreeTab.JPY, quotes, asOf))
        assertNull("테더는 은행 preview 탭이 아니다", bound(tab = FreeTab.TETHER).open("u1", FreeTab.TETHER, quotes, asOf))
        assertNull("뉴스", bound(tab = FreeTab.NEWS).open("u1", FreeTab.NEWS, quotes, asOf))
        assertNull("owner 없음", bound(uid = null).open("u1", FreeTab.USD, quotes, asOf))
        assertNull("binding 전", FreeAlertPreviewSession().open("u1", FreeTab.USD, quotes, asOf))
        val s = bound()
        s.openUsd()
        assertNull("이미 열려 있다", s.open("u1", FreeTab.USD, quotes, asOf))
        for (tab in listOf(FreeTab.JPY, FreeTab.EUR)) assertNotNull("$tab", bound(tab = tab).open("u1", tab, quotes, asOf))
    }

    @Test
    fun everyEventNamingAnotherTokenChangesNothing() {
        val s = bound()
        val token = s.openUsd()
        val before = s.state
        s.edit(token + 1, edited)
        assertFalse(s.close(token + 1))
        assertFalse(s.subscribe(token + 1))
        assertNull(s.dismissed(token + 1))
        assertSame(before, s.state)
        s.edit(token, edited)
        assertEquals(edited, s.openState().input)
    }

    @Test
    fun aCloseEndsOnlyWhenTheHideIsConfirmed_andGivesNoPaywall() {
        val s = bound()
        val token = s.openUsd()
        assertTrue(s.close(token))
        assertTrue(s.state is FreeAlertPreviewSession.State.Closing)
        s.edit(token, edited)
        assertEquals("closing 중 입력을 받았다", false, (s.state as FreeAlertPreviewSession.State.Closing).input == edited)
        assertNull(s.dismissed(token))
        assertEquals(FreeAlertPreviewSession.State.Closed, s.state)
        val again = s.openUsd()
        assertTrue("새 preview 는 새 token", again > token)
        assertEquals("새로 열면 처음부터", Bank.KB, s.openState().input.bank)
    }

    /** A dismiss the user made — Back, outside, drag — confirmed hidden while still open: the preview ends, no paywall. */
    @Test
    fun aUserDismissConfirmedWhileOpenEndsIt() {
        val s = bound()
        val token = s.openUsd()
        assertNull(s.dismissed(token))
        assertEquals(FreeAlertPreviewSession.State.Closed, s.state)
    }

    @Test
    fun aSubscribeGivesExactlyOnePaywallSignalAfterTheConfirmedHide() {
        val s = bound()
        val token = s.openUsd()
        assertTrue(s.subscribe(token))
        assertFalse("연타", s.subscribe(token))
        assertFalse("closing 중 닫기는 의도를 바꾸지 않는다", s.close(token))
        val signal = s.dismissed(token)
        assertNotNull(signal)
        assertEquals(listOf<Any?>("u1", FreeTab.USD, token), listOf(signal!!.uid, signal.tab, signal.token))
        assertTrue(s.deliverable(signal))
        assertNull("두 번째 완료는 신호가 없다", s.dismissed(token))
    }

    @Test
    fun anOwnerOrTabChangeBeforeTheHideEndsTheWishToo() {
        for ((uid, tab) in listOf("u2" to FreeTab.USD, "u1" to FreeTab.JPY, null to FreeTab.USD, "u1" to null, "u1" to FreeTab.NEWS)) {
            val s = bound()
            val token = s.openUsd()
            s.subscribe(token)
            s.invalidate(uid, tab)
            assertEquals("$uid/$tab", FreeAlertPreviewSession.State.Closed, s.state)
            assertNull("$uid/$tab", s.dismissed(token))
        }
    }

    @Test
    fun aSignalIsNotDeliverableOnceAnythingMoved() {
        // The tab moved between the hide and the delivery.
        bound().let { s ->
            val token = s.openUsd(); s.subscribe(token)
            val signal = s.dismissed(token)!!
            s.invalidate("u1", FreeTab.EUR)
            assertFalse("탭", s.deliverable(signal))
        }
        // A newer preview opened before the delivery.
        bound().let { s ->
            val token = s.openUsd(); s.subscribe(token)
            val signal = s.dismissed(token)!!
            s.openUsd()
            assertFalse("새 preview", s.deliverable(signal))
        }
        // A newer preview opened and closed again before the delivery: nothing is open, and the signal is still not its.
        bound().let { s ->
            val token = s.openUsd(); s.subscribe(token)
            val signal = s.dismissed(token)!!
            val newer = s.openUsd()
            assertTrue(s.close(newer))
            assertNull(s.dismissed(newer))
            assertEquals("premise: 다시 닫혔다", FreeAlertPreviewSession.State.Closed, s.state)
            assertFalse("새 preview 가 열렸다 닫힌 뒤", s.deliverable(signal))
        }
        // The host went away.
        bound().let { s ->
            val token = s.openUsd(); s.subscribe(token)
            val signal = s.dismissed(token)!!
            s.dispose()
            assertFalse("dispose", s.deliverable(signal))
        }
    }

    /** A → B → A, or a host built again: the new session may count from the same number, and the old signal still opens nothing. */
    @Test
    fun anOldSessionsSignalIsNeverAnotherSessionsEvenWithTheSameToken() {
        val first = bound()
        val token = first.openUsd(); first.subscribe(token)
        val signal = first.dismissed(token)!!
        first.dispose()
        val second = bound()
        val again = second.openUsd(); second.subscribe(again)
        assertEquals("premise: 같은 번호", token, again)
        second.dismissed(again)
        assertFalse("이전 session 의 신호", second.deliverable(signal))
    }

    @Test
    fun aDisposedSessionDoesNothing() {
        val s = bound()
        val token = s.openUsd()
        s.dispose()
        assertEquals(FreeAlertPreviewSession.State.Closed, s.state)
        s.invalidate("u1", FreeTab.USD)
        assertNull(s.open("u1", FreeTab.USD, quotes, asOf))
        s.edit(token, edited)
        assertFalse(s.close(token))
        assertFalse(s.subscribe(token))
        assertNull(s.dismissed(token))
        assertEquals(FreeAlertPreviewSession.State.Closed, s.state)
    }

    @Test
    fun closedIgnoresEditsSubscribesAndDismisses() {
        val s = bound()
        s.edit(1, edited)
        assertFalse(s.subscribe(1))
        assertFalse(s.close(1))
        assertNull(s.dismissed(1))
        assertEquals(FreeAlertPreviewSession.State.Closed, s.state)
    }

    /** J1b: the host's seams read the binding this session applied, and each session's identity is its own. */
    @Test fun theSeamsReadTheBindingTheSessionApplied_andEachSessionHasItsOwnIdentity() {
        val session = FreeAlertPreviewSession()
        assertEquals(0L, session.currentBinding)
        session.invalidate("u1", FreeTab.USD)
        assertEquals("owner 와 tab 이 정해졌다", 1L, session.currentBinding)
        session.invalidate("u1", FreeTab.USD)
        assertEquals("같은 owner·tab 은 새 binding 이 아니다", 1L, session.currentBinding)
        session.invalidate("u1", FreeTab.JPY)
        assertEquals(2L, session.currentBinding)
        session.invalidate("u2", FreeTab.JPY)
        assertEquals(3L, session.currentBinding)
        assertSame("한 session 의 identity 는 바뀌지 않는다", session.identity, session.identity)
        assertFalse("다른 session 의 identity 와 같지 않다", session.identity === FreeAlertPreviewSession().identity)
    }
}
