package com.jay.fxi.data.auth

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-over's real behaviour, with real threads.
 *
 * This is why [SerialDeliveryPump] was lifted out of `FirebaseAuthTokenSource`: while it lived
 * there it could only be checked by reading the source, and a structural check is exactly what let
 * a mutation deleting the whole `synchronized` block pass.
 */
class SerialDeliveryPumpTest {

    /** A queue that behaves like the tracker's outbox: hands back a snapshot, then clears. */
    private class Outbox {
        private val pending = mutableListOf<AuthSessionGenerationTracker.Delivery>()

        @Synchronized
        fun enqueue(delivery: AuthSessionGenerationTracker.Delivery) {
            pending += delivery
        }

        @Synchronized
        fun drain(): List<AuthSessionGenerationTracker.Delivery> {
            if (pending.isEmpty()) return emptyList()
            val owed = pending.toList()
            pending.clear()
            return owed
        }
    }

    private fun delivery(onDeliver: () -> Unit) =
        AuthSessionGenerationTracker.Delivery({ onDeliver() }, null)

    /**
     * The defect the guard exists for, reproduced end to end.
     *
     * Two subscribers, and the first one's listener causes a further transition. Without the
     * guard the nested run drains and delivers the new generation immediately, so subscriber B
     * is handed `g2` before it has been handed `g1`.
     */
    @Test
    fun aNestedRunDoesNotOvertakeWhatTheOuterLoopStillHolds() {
        val outbox = Outbox()
        val toA = mutableListOf<String>()
        val toB = mutableListOf<String>()
        lateinit var pump: SerialDeliveryPump

        var caused = false
        outbox.enqueue(
            delivery {
                toA += "g1"
                if (!caused) {
                    caused = true
                    outbox.enqueue(delivery { toA += "g2" })
                    outbox.enqueue(delivery { toB += "g2" })
                    pump.run() // the listener touching the source again
                }
            }
        )
        outbox.enqueue(delivery { toB += "g1" })

        pump = SerialDeliveryPump { outbox.drain() }
        pump.run()

        assertEquals(listOf("g1", "g2"), toA)
        assertEquals("B 가 g1 보다 g2 를 먼저 받았다 — 중첩 배달이 순서를 앞질렀다", listOf("g1", "g2"), toB)
    }

    /** The outer loop keeps going until the outbox is actually empty. */
    @Test
    fun oneRunDrainsUntilNothingIsLeft() {
        val outbox = Outbox()
        val seen = mutableListOf<Int>()
        var next = 1
        fun enqueueOne() {
            val n = next++
            outbox.enqueue(
                delivery {
                    seen += n
                    if (n < 5) enqueueOne()
                }
            )
        }
        enqueueOne()

        SerialDeliveryPump { outbox.drain() }.run()

        assertEquals(listOf(1, 2, 3, 4, 5), seen)
    }

    /**
     * Two threads never deliver at the same time.
     *
     * The barrier makes both threads arrive at `run()` together; the counter fails the test if a
     * second delivery ever starts while one is still running.
     */
    @Test
    fun twoThreadsDoNotDeliverConcurrently() {
        val outbox = Outbox()
        val inFlight = AtomicInteger(0)
        val overlapped = AtomicBoolean(false)
        val delivered = Collections.synchronizedList(mutableListOf<Int>())

        repeat(40) { index ->
            outbox.enqueue(
                delivery {
                    if (inFlight.incrementAndGet() > 1) overlapped.set(true)
                    Thread.yield()
                    delivered += index
                    inFlight.decrementAndGet()
                }
            )
        }

        val pump = SerialDeliveryPump { outbox.drain() }
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val threads = (1..2).map {
            Thread {
                ready.countDown()
                go.await()
                pump.run()
            }
        }
        threads.forEach(Thread::start)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertFalse("두 스레드가 동시에 배달했다", overlapped.get())
        assertEquals("배달이 누락되거나 중복됐다", (0..39).toList(), delivered.sorted())
    }

    /**
     * Work that arrives while a hand-over is in flight waits for it, rather than being dropped.
     *
     * This is what the lock buys that the flag alone does not, and it is the only test here that
     * can tell them apart: with the flag alone a second thread sees `delivering` and **returns**,
     * so an item enqueued after the first thread's last drain is never handed to anyone. Replacing
     * `synchronized` with `run` leaves [twoThreadsDoNotDeliverConcurrently] green — measured.
     * That fixture also gives all preloaded deliveries to one drain; no later work can create
     * a second concurrent batch. Its result does not prove that the flag provides exclusion.
     *
     * The window is narrow and deliberate: the second drain has already returned its (empty)
     * snapshot before the new item is enqueued, so the first thread's loop cannot pick it up.
     */
    @Test
    fun aHandOverArrivingMidFlightWaitsInsteadOfBeingDropped() {
        val outbox = Outbox()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val atSecondDrain = CountDownLatch(1)
        val secondRunReturned = CountDownLatch(1)
        var drains = 0

        val pump = SerialDeliveryPump {
            val owed = outbox.drain()
            if (++drains == 2) {
                atSecondDrain.countDown()
                // Bounded, not a fixed sleep: the lock lets this time out (the second thread is
                // still blocked, which is the point), while a non-blocking guard fires it at once.
                secondRunReturned.await(1, TimeUnit.SECONDS)
            }
            owed
        }

        outbox.enqueue(delivery { seen += "first" })
        val holder = Thread { pump.run() }
        holder.start()

        assertTrue(atSecondDrain.await(5, TimeUnit.SECONDS))
        outbox.enqueue(delivery { seen += "arrived-mid-flight" })
        val latecomer = Thread {
            pump.run()
            secondRunReturned.countDown()
        }
        latecomer.start()

        holder.join(5_000)
        latecomer.join(5_000)

        assertEquals(
            "진행 중에 도착한 인계가 버려졌다 — 둘째 호출이 기다리지 않고 반환했다",
            listOf("first", "arrived-mid-flight"),
            seen.toList()
        )
    }

    /**
     * A throwing listener releases the guard.
     *
     * Without `finally` the flag stays raised and every later hand-over is refused for the life of
     * the process — the failure would be silent and permanent.
     */
    @Test
    fun aThrowingListenerDoesNotWedgeThePump() {
        val outbox = Outbox()
        val seen = mutableListOf<String>()
        val pump = SerialDeliveryPump { outbox.drain() }

        outbox.enqueue(delivery { throw IllegalStateException("listener misbehaved") })
        runCatching { pump.run() }

        outbox.enqueue(delivery { seen += "after" })
        pump.run()

        assertEquals(listOf("after"), seen)
    }

    /**
     * A throwing listener costs the rest of its batch, and this pins that rather than hiding it.
     *
     * The drain already cleared, so the deliveries queued behind the throwing one are gone. If
     * that is ever made recoverable, this test is the one that has to change — deliberately.
     */
    @Test
    fun aThrowingListenerLosesTheRestOfItsBatch() {
        val outbox = Outbox()
        val seen = mutableListOf<String>()
        val pump = SerialDeliveryPump { outbox.drain() }

        outbox.enqueue(delivery { seen += "first" })
        outbox.enqueue(delivery { throw IllegalStateException("listener misbehaved") })
        outbox.enqueue(delivery { seen += "third" })

        runCatching { pump.run() }
        pump.run() // A later run must not replay the remainder of the failed batch.

        assertEquals(listOf("first"), seen)
    }

    /** The exception reaches the caller rather than being swallowed. */
    @Test
    fun aThrowingListenerPropagates() {
        val outbox = Outbox()
        outbox.enqueue(delivery { throw IllegalStateException("listener misbehaved") })

        val thrown = runCatching { SerialDeliveryPump { outbox.drain() }.run() }.exceptionOrNull()

        assertTrue("리스너 예외가 삼켜졌다", thrown is IllegalStateException)
    }
}
