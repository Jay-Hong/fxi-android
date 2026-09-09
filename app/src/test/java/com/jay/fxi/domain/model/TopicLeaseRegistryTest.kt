package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three lifetimes a lease has, kept apart.
 *
 * Numbers are chosen to **discriminate**. The obvious test for "a restated lease does not move its
 * expiry" is a grant of 600 restated 100 later with 500 remaining — and 100 + 500 is 600, so an
 * implementation that recomputes the expiry from the restatement passes it. Every case here uses a
 * duration that makes the two answers different.
 */
class TopicLeaseRegistryTest {

    private companion object {
        const val TETHER = "usdt:krw"
        const val USD = "fx:usd-krw"
        const val JPY = "fx:jpy-krw"
    }

    private fun lease(topic: String, id: String, seconds: Long) = TopicLeaseInput(topic, id, seconds)

    /**
     * The same id, restated, keeps the instant it was granted at.
     *
     * `active_subscriptions` is the whole connection's state, so **any** request's acknowledgement
     * re-reports every live lease. Treating each restatement as a new grant would let an unrelated
     * unsubscribe extend a lease indefinitely.
     */
    @Test
    fun `a restated lease id keeps its original expiry`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 600)), jitter = 0)
        assertEquals(600_000L, registry.held.getValue(TETHER).expiresAtMillis)

        // 100_000 + 600_000 would be 700_000 — a different answer, which is the point.
        registry.apply(100_000, listOf(lease(TETHER, "L1", 600)), jitter = 0)
        assertEquals(
            "restate 가 만료를 뒤로 밀었다",
            600_000L, registry.held.getValue(TETHER).expiresAtMillis
        )
    }

    /** A different id on the same topic is a new grant, dated from the acknowledgement that made it. */
    @Test
    fun `a new lease id is dated from its own acknowledgement`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 600)), jitter = 0)
        registry.apply(100_000, listOf(lease(TETHER, "L2", 600)), jitter = 0)

        assertEquals(700_000L, registry.held.getValue(TETHER).expiresAtMillis)
        assertEquals("L2", registry.held.getValue(TETHER).leaseId)
    }

    /** A topic absent from the acknowledgement has no lease any more. */
    @Test
    fun `a topic missing from the acknowledgement loses its grant`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 600), lease(USD, "L2", 900)), jitter = 0)
        registry.apply(1_000, listOf(lease(TETHER, "L1", 600)), jitter = 0)

        assertEquals(setOf(TETHER), registry.held.keys)
    }

    /** The shortest lease paces the whole set, and the draw only ever subtracts. */
    @Test
    fun `the renewal wait comes from the shortest lease`() {
        val registry = TopicLeaseRegistry()
        val leases = listOf(lease(TETHER, "L1", 900), lease(USD, "L2", 600), lease(JPY, "L3", 780))

        val update = registry.apply(0, leases, jitter = 0)
        assertEquals(420.seconds, update.renewAfter)
        assertEquals(setOf(TETHER, USD, JPY), update.renewalScope)
        assertEquals(360.seconds, TopicLeaseRegistry().apply(0, leases, jitter = 60).renewAfter)
    }

    /**
     * A draw the policy refuses skips the renewal and nothing else.
     *
     * The grants are still applied and their absolute expiry still stands — a caller bug about
     * *when to renew* must not quietly become a connection with no expiry on it.
     */
    @Test
    fun `an out-of-range draw skips only the renewal`() {
        val registry = TopicLeaseRegistry()
        val update = registry.apply(0, listOf(lease(TETHER, "L1", 600)), jitter = 61)

        assertNull(update.renewAfter)
        assertEquals(600_000L, registry.held.getValue(TETHER).expiresAtMillis)
        assertEquals(600_000L, registry.earliestExpiryMillis())
    }

    /**
     * A `duration 0` is the server saying the lease is already over.
     *
     * So its expiry is the acknowledgement itself — not a grace period — and the immediate
     * re-authentication is a separate answer to the same fact.
     */
    @Test
    fun `a zero lease expires at the acknowledgement and asks for one immediate attempt`() {
        val registry = TopicLeaseRegistry()
        val update = registry.apply(5_000, listOf(lease(TETHER, "L1", 0)), jitter = 0)

        // One launch, not two: the topic is in the renewal's scope *and* the wait is zero.
        assertEquals(setOf(TETHER), update.renewalScope)
        assertEquals("이미 만료인데 갱신을 미뤘다", Duration.ZERO, update.renewAfter)
        assertEquals(5_000L, registry.held.getValue(TETHER).expiresAtMillis)
    }

    /**
     * The second restatement of the same zero does not ask again.
     *
     * The race is not a server fault: while the first attempt waits for a token, an unsubscribe —
     * which needs none, so it goes out at once — is acknowledged first, and its whole-connection
     * snapshot carries the same still-expired lease as `0` again.
     */
    @Test
    fun `a zero lease asks once per id`() {
        val registry = TopicLeaseRegistry()
        assertEquals(setOf(TETHER), registry.apply(0, listOf(lease(TETHER, "L1", 0)), jitter = 0).renewalScope)

        val again = registry.apply(1_000, listOf(lease(TETHER, "L1", 0)), jitter = 0)
        assertEquals("같은 id 의 0 이 두 번째 시도를 얻었다", emptySet<String>(), again.renewalScope)
        assertNull("물을 것이 없는데 갱신을 예약했다", again.renewAfter)
    }

    /**
     * A spent zero stops pacing the renewal; a fresh one still does.
     *
     * Left in, a spent zero pegs the shortest at zero and the renewal spins on every lap. Taken
     * out too eagerly — including the fresh one — and the answer to "the lease is over" is a wait.
     */
    @Test
    fun `a spent zero leaves the renewal to the other leases`() {
        val registry = TopicLeaseRegistry()
        val fresh = registry.apply(0, listOf(lease(TETHER, "L1", 0), lease(USD, "L2", 900)), jitter = 0)
        assertEquals("갓 도착한 0 이 최단에서 빠졌다", Duration.ZERO, fresh.renewAfter)
        assertEquals(setOf(TETHER, USD), fresh.renewalScope)

        val spent = registry.apply(1_000, listOf(lease(TETHER, "L1", 0), lease(USD, "L2", 900)), jitter = 0)
        assertEquals("이미 시도한 0 이 최단을 0 으로 눌렀다", 720.seconds, spent.renewAfter)
        assertEquals("이미 시도한 0 을 다시 물었다", setOf(USD), spent.renewalScope)
    }

    /**
     * The record is narrowed by an id leaving, and by nothing else.
     *
     * Not by a new acknowledgement arriving, and not by another id being granted — clearing it
     * wholesale re-fires the attempt in the race above. An id that genuinely left and came back is
     * a new question and gets its one attempt again.
     */
    @Test
    fun `the zero record follows the id, not the acknowledgement`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 0)), jitter = 0)

        // Another topic's grant arrives; L1 is still active and still spent.
        registry.apply(1_000, listOf(lease(TETHER, "L1", 0), lease(USD, "L9", 900)), jitter = 0).let {
            assertEquals(setOf(USD), it.renewalScope)
        }

        // L1 leaves…
        registry.apply(2_000, listOf(lease(USD, "L9", 900)), jitter = 0)
        // …and coming back is a new question.
        assertEquals(
            setOf(TETHER, USD),
            registry.apply(3_000, listOf(lease(TETHER, "L1", 0), lease(USD, "L9", 900)), jitter = 0)
                .renewalScope
        )
    }

    /** Two ids, one spent and one fresh, are judged apart. */
    @Test
    fun `a spent id does not spend a new one`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 0)), jitter = 0)

        val update = registry.apply(
            1_000, listOf(lease(TETHER, "L1", 0), lease(USD, "L2", 0)), jitter = 0
        )
        assertEquals("쓴 id 가 새 id 의 시도까지 썼다", setOf(USD), update.renewalScope)
    }

    /** The deadline is reached at the instant itself, not after it. */
    @Test
    fun `expiry is inclusive`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 600)), jitter = 0)

        assertEquals(emptySet<String>(), registry.expiredAt(599_999))
        assertEquals(setOf(TETHER), registry.expiredAt(600_000))
    }

    /** The soonest deadline is the one to watch. */
    @Test
    fun `the earliest deadline is reported`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 900), lease(USD, "L2", 600)), jitter = 0)

        assertEquals(600_000L, registry.earliestExpiryMillis())
        assertEquals(setOf(TETHER, USD), registry.expiredAt(900_000))
    }

    /** A connection ending takes both, because the next one is a different grant of everything. */
    @Test
    fun `clearing forgets the grants and the record`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 0)), jitter = 0)
        registry.clear()

        assertTrue(registry.held.isEmpty())
        assertNull(registry.earliestExpiryMillis())
        assertEquals(
            "새 연결인데 같은 문자열 id 가 시도를 못 받았다",
            setOf(TETHER),
            registry.apply(1_000, listOf(lease(TETHER, "L1", 0)), jitter = 0).renewalScope
        )
    }

    /** No leases at all is not a renewal of nothing — it is no renewal. */
    @Test
    fun `an acknowledgement with no leases holds nothing`() {
        val registry = TopicLeaseRegistry()
        registry.apply(0, listOf(lease(TETHER, "L1", 600)), jitter = 0)
        val update = registry.apply(1_000, emptyList(), jitter = 0)

        assertNull(update.renewAfter)
        assertEquals(emptySet<String>(), update.renewalScope)
        assertTrue(registry.held.isEmpty())
        assertNull(registry.earliestExpiryMillis())
    }

    /**
     * A deadline past the end of the clock saturates rather than wrapping into the past.
     *
     * Unreachable from a boot-relative clock and checked anyway: the clock is injected, and a
     * wrapped deadline would read as already expired — a topic dropped at the instant it was
     * granted.
     */
    @Test
    fun `a deadline past the ceiling saturates`() {
        val registry = TopicLeaseRegistry()
        registry.apply(Long.MAX_VALUE - 1_000, listOf(lease(TETHER, "L1", 600)), jitter = 0)

        assertEquals(Long.MAX_VALUE, registry.held.getValue(TETHER).expiresAtMillis)
        assertEquals(emptySet<String>(), registry.expiredAt(Long.MAX_VALUE - 1))
    }
}
