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
