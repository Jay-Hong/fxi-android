package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rejection ledger's own contract, apart from the issuer (L-4e E4a §3.5). */
class TopicRejectionLedgerTest {
    private val g1 = TopicGrantToken(1L)
    private val g2 = TopicGrantToken(2L)
    private val premium = listOf(TopicRejectionReason.PREMIUM_REQUIRED)
    private val unrelated = listOf(TopicRejectionReason.TOPICS_DISABLED)

    private fun ledger(orders: AtomicLong = AtomicLong(0L)) = TopicRejectionLedger(orders::incrementAndGet)

    @Test
    fun aReservation_takesTheSharedOrder_andIsPendingUntilItEnds() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        l.grantIssued(g1)
        orders.incrementAndGet() // a query started in between
        val r = l.reserve(g1, premium)
        assertEquals(2L, r.order)
        assertEquals(TopicRejectionView(listOf(2L), 2L, null), l.view(g1))
        l.complete(r, current = true)
        assertEquals(TopicRejectionView(emptyList(), 2L, 2L), l.view(g1))
    }

    @Test
    fun theFirstEndingWins_andNeitherRepeatingNorCrossingChangesIt() {
        val l = ledger()
        l.grantIssued(g1)
        val abandoned = l.reserve(g1, premium)
        l.abandon(abandoned)
        l.complete(abandoned, current = true)
        l.abandon(abandoned)
        assertEquals("버린 예약의 뒤늦은 완료가 인계로 기록됐다", TopicRejectionView(emptyList(), abandoned.order, null), l.view(g1))

        val completed = l.reserve(g1, premium)
        l.complete(completed, current = true)
        l.abandon(completed)
        l.complete(completed, current = false)
        assertEquals(TopicRejectionView(emptyList(), completed.order, completed.order), l.view(g1))
    }

    @Test
    fun theLatestOrders_neverGoBack_andOnlyAccessRefusalsCount() {
        val l = ledger()
        l.grantIssued(g1)
        val early = l.reserve(g1, premium)
        val late = l.reserve(g1, premium)
        val other = l.reserve(g1, unrelated)
        l.complete(late, current = true)
        l.complete(early, current = true)
        l.complete(other, current = true)
        assertEquals("이른 순서가 늦게 끝나 최신 순서를 낮췄다", TopicRejectionView(emptyList(), late.order, late.order), l.view(g1))
        val stale = l.reserve(g1, premium)
        l.complete(stale, current = false)
        assertEquals(TopicRejectionView(emptyList(), stale.order, late.order), l.view(g1))
    }

    @Test
    fun anotherGrantIssued_retiresTheSummary_andLateOnesForTheOldGrantTouchNothing() {
        val l = ledger()
        l.grantIssued(g1)
        val old = l.reserve(g1, premium)
        l.complete(l.reserve(g1, premium), current = true)
        l.grantIssued(g2)
        assertEquals(TopicRejectionView(listOf(old.order), null, null), l.view(g1))
        assertEquals(TopicRejectionView(emptyList(), null, null), l.view(g2))

        val fresh = l.reserve(g2, premium)
        val lateOld = l.reserve(g1, premium)
        l.complete(old, current = true)
        l.complete(lateOld, current = true)
        assertEquals("퇴역한 요약이 되살아났다", TopicRejectionView(emptyList(), null, null), l.view(g1))
        assertEquals(TopicRejectionView(listOf(fresh.order), fresh.order, null), l.view(g2))
        l.grantIssued(g2)
        assertEquals("같은 grant 재통지가 요약을 지웠다", TopicRejectionView(listOf(fresh.order), fresh.order, null), l.view(g2))
    }

    @Test
    fun aLedger_endsOnlyItsOwnReservations() {
        val mine = ledger()
        val theirs = ledger()
        mine.grantIssued(g1)
        theirs.grantIssued(g1)
        val r = theirs.reserve(g1, premium)
        assertTrue(theirs.owns(r))
        assertTrue(!mine.owns(r))
    }

    private val krx = listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
    private val both = listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, TopicRejectionReason.PREMIUM_REQUIRED)

    private fun query(order: Long, invalidations: Long = 7L) = TopicReapprovalQuery(order, invalidations)

    /** A token source that fails the test when asked outside an issue. */
    private class Tokens(private var next: Long = 100L) {
        var asked = 0
        fun take(): TopicGrantToken {
            asked += 1
            return TopicGrantToken(next++)
        }
    }

    @Test
    fun nothingIsOwed_withoutAPremiumRefusalTakenOverOrOnItsWay() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        val tokens = Tokens()
        l.grantIssued(g1)
        assertEquals(TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take))

        l.complete(l.reserve(g1, krx), current = true)
        l.complete(l.reserve(g1, premium), current = false)
        l.abandon(l.reserve(g1, premium))
        l.complete(l.reserve(g1, unrelated), current = true)
        val stillOnItsWay = l.reserve(g1, krx)
        assertEquals(
            "KRX 거부·current=false·버린 premium 이 요구를 만들었다",
            TopicReapprovalAttempt.NotOwed,
            l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take)
        )
        l.abandon(stillOnItsWay)
        assertEquals(0, tokens.asked)
    }

    @Test
    fun aPremiumRefusalStillOnItsWay_isOwedButBlocks_untilItEnds() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        val tokens = Tokens()
        l.grantIssued(g1)
        val onItsWay = l.reserve(g1, premium)
        assertEquals(TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take))
        assertEquals(TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, null, tokens::take))
        l.abandon(onItsWay)
        assertEquals(TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take))

        val mixed = l.reserve(g1, both)
        assertEquals("두 사유를 함께 단 거부가 premium 으로 세지지 않았다", TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, null, tokens::take))
        l.complete(mixed, current = true)
        val attempt = l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take)
        assertEquals(mixed.order, (attempt as TopicReapprovalAttempt.Issued).issue.refusalOrder)
    }

    @Test
    fun anOwedReapproval_isIssuedOnce_withTheOrdersItConsumed_andMovesTheSummary() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        val tokens = Tokens()
        l.grantIssued(g1)
        val late = l.reserve(g1, premium)
        val early = l.reserve(g1, premium)
        l.complete(early, current = true)
        l.complete(late, current = true)
        l.complete(l.reserve(g1, krx), current = true)
        val q = orders.incrementAndGet()

        val attempt = l.rotateForTopicReapproval(g1, query(q, invalidations = 3L), tokens::take)
        val issue = (attempt as TopicReapprovalAttempt.Issued).issue
        assertEquals(TopicReapprovalIssue(g1, TopicGrantToken(100L), maxOf(early.order, late.order), q, 3L), issue)
        assertEquals(1, tokens.asked)
        assertEquals("옛 grant 의 요약이 남았다", TopicRejectionView(emptyList(), null, null), l.view(g1))
        assertEquals(TopicRejectionView(emptyList(), null, null), l.view(issue.token))

        assertEquals("같은 증거로 다시 회전했다", TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(g1, query(q), tokens::take))
        assertEquals(TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(issue.token, query(q), tokens::take))
        val lateOld = l.reserve(g1, premium)
        l.complete(lateOld, current = true)
        assertEquals("옛 grant 의 늦은 인계가 요구를 되살렸다", TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(issue.token, query(orders.incrementAndGet()), tokens::take))

        l.complete(l.reserve(issue.token, premium), current = true)
        assertEquals(
            "새 거부 앞의 같은 조회로 다시 회전했다",
            TopicReapprovalAttempt.Blocked,
            l.rotateForTopicReapproval(issue.token, query(q), tokens::take)
        )
        assertTrue(l.rotateForTopicReapproval(issue.token, query(orders.incrementAndGet()), tokens::take) is TopicReapprovalAttempt.Issued)
        assertEquals(2, tokens.asked)
    }

    @Test
    fun aQuery_mustHaveStartedAfterEveryAccessRefusalReported_andNoneMayBeOnItsWay() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        val tokens = Tokens()
        l.grantIssued(g1)
        val taken = l.reserve(g1, premium)
        l.complete(taken, current = true)
        val before = orders.incrementAndGet()
        l.abandon(l.reserve(g1, krx))
        assertEquals("버린 KRX 보고보다 먼저 시작한 조회가 회전했다", TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, query(before), tokens::take))
        // Reported before the query started and still on its way when it answers: only the pending check can block it.
        val onItsWay = l.reserve(g1, krx)
        val after = orders.incrementAndGet()
        assertEquals("처리 중인 거부가 있는데 회전했다", TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, query(after), tokens::take))
        assertEquals(TopicReapprovalAttempt.Blocked, l.rotateForTopicReapproval(g1, null, tokens::take))
        assertEquals(0, tokens.asked)
        assertEquals("막힌 시도가 요약을 바꿨다", TopicRejectionView(listOf(onItsWay.order), onItsWay.order, taken.order), l.view(g1))
        l.complete(onItsWay, current = false)
        assertTrue("끝난 거부 뒤의 같은 조회가 회전하지 않았다", l.rotateForTopicReapproval(g1, query(after), tokens::take) is TopicReapprovalAttempt.Issued)
    }

    @Test
    fun anotherGrant_isNotOwed_andANewContextRetiresThePremiumHistory() {
        val orders = AtomicLong(0L)
        val l = ledger(orders)
        val tokens = Tokens()
        l.grantIssued(g1)
        l.complete(l.reserve(g1, premium), current = true)
        assertEquals(TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(g2, query(orders.incrementAndGet()), tokens::take))
        l.grantIssued(g2)
        l.grantIssued(g1)
        assertEquals("새 context 발급 뒤 premium 인계가 남았다", TopicReapprovalAttempt.NotOwed, l.rotateForTopicReapproval(g1, query(orders.incrementAndGet()), tokens::take))
        assertEquals(0, tokens.asked)
    }

    /** A rotation holding the monitor never misses a reservation that has its order but is not yet visible: it waits for it. */
    @Test
    fun aRotation_waitsForAReservationThatHasItsOrderButIsNotYetVisible() {
        val numbered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val orders = AtomicLong(0L)
        var gate = false
        val l = TopicRejectionLedger {
            if (gate) {
                numbered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "release never came" }
            }
            orders.incrementAndGet()
        }
        l.grantIssued(g1)
        l.complete(l.reserve(g1, premium), current = true)
        val q = orders.incrementAndGet()
        gate = true
        val reserving = Thread { l.reserve(g1, krx) }.apply { start() }
        assertTrue(numbered.await(10, TimeUnit.SECONDS))
        var seen: TopicReapprovalAttempt? = null
        val rotating = Thread { seen = l.rotateForTopicReapproval(g1, query(q)) { TopicGrantToken(100L) } }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (rotating.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("회전이 예약 도중에 끼어들었다", Thread.State.BLOCKED, rotating.state)
        release.countDown()
        reserving.join(10_000)
        rotating.join(10_000)
        assertEquals(TopicReapprovalAttempt.Blocked, seen)
    }

    /** A reader holding the monitor never sees an order without its reservation: it waits for the reservation to finish. */
    @Test
    fun aReader_waitsForAReservationThatHasItsOrderButIsNotYetVisible() {
        val numbered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0L
        val l = TopicRejectionLedger {
            calls += 1
            numbered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "release never came" }
            calls
        }
        l.grantIssued(g1)
        val reserving = Thread { l.reserve(g1, premium) }.apply { start() }
        assertTrue(numbered.await(10, TimeUnit.SECONDS))
        var seen: TopicRejectionView? = null
        val reader = Thread { seen = l.view(g1) }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("읽기가 예약 도중에 끼어들었다", Thread.State.BLOCKED, reader.state)
        release.countDown()
        reserving.join(10_000)
        reader.join(10_000)
        assertEquals(TopicRejectionView(listOf(1L), 1L, null), seen)
        assertNotEquals(null, seen)
    }
}
