package com.jay.fxi.data.entitlements.control

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlReleaseLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private val o by lazy { ControlStoreTestStorage(File(folder.root, "lifecycle.preferences_pb")) }
    private val tracker get() = ControlCommandTracking.forOwner(o.owner)
    @After fun close() = runBlocking { o.close() }

    @Test fun M02_monotonicLifecycle() {
        val c = o.control.prepare()
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        ControlReleaseFixtures.pending(c); assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        ControlReleaseFixtures.released(c); assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
    }
    @Test fun M02_cannotSkipPending() {
        val c = o.control.prepare()
        assertEquals(IllegalStateException::class.java, runCatching { ControlReleaseFixtures.released(c) }.exceptionOrNull()?.javaClass)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    @Test fun M02_cannotBeginTwice() {
        val c = o.control.prepare(); ControlReleaseFixtures.pending(c)
        assertEquals(IllegalStateException::class.java, runCatching { ControlReleaseFixtures.pending(c) }.exceptionOrNull()?.javaClass)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
    }
    @Test fun M02_releasedCannotReturnToPending() {
        val c = o.control.prepare(); ControlReleaseFixtures.pending(c); ControlReleaseFixtures.released(c)
        assertEquals(IllegalStateException::class.java, runCatching { ControlReleaseFixtures.pending(c) }.exceptionOrNull()?.javaClass)
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
    }
    @Test fun M02_cannotCompleteTwice() {
        val c = o.control.prepare(); ControlReleaseFixtures.pending(c); ControlReleaseFixtures.released(c)
        assertEquals(IllegalStateException::class.java, runCatching { ControlReleaseFixtures.released(c) }.exceptionOrNull()?.javaClass)
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
    }
    @Test fun M03_descriptorCannotRebind() {
        val t = ControlReleaseFixtures.fixture(); val descriptor = ReleasePendingDescriptor.ExactMutations(ControlReleaseFixtures.row(t.command))
        ControlReleaseFixtures.bind(t, descriptor)
        assertEquals("release descriptor is already fixed", runCatching { ControlReleaseFixtures.bind(t, ReleasePendingDescriptor.ConfirmedWithoutApplied) }.exceptionOrNull()?.message)
        assertSame(descriptor, t.releaseDescriptor)
    }
    private fun collision(pending: Boolean, sameRef: Boolean) {
        val c = o.control.prepare(); val tracked = checkNotNull(tracker.findPrepared(c))
        if (pending) ControlReleaseFixtures.simulatePending(tracker, c)
        val attempt = if (sameRef) c else CommandRef(c.id, c.body, c.ownerTrackingLifetimeId)
        val failure = runCatching { tracker.registerPrepared(attempt) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertSame(tracked, tracker.findPrepared(c)); assertEquals(1, ControlReleaseFixtures.commands(tracker).size)
    }
    @Test fun M05_retainedDuplicateIdentity() = collision(false, true)
    @Test fun M05_retainedSameIdDifferentRef() = collision(false, false)
    @Test fun M05_pendingDuplicateIdentity() = collision(true, true)
    @Test fun M05_pendingSameIdDifferentRef() = collision(true, false)
    @Test fun M04_releasedRefCannotReregisterAfterCleanup() {
        val c = o.control.prepare(); ControlReleaseFixtures.simulatePending(tracker, c); ControlReleaseFixtures.simulateReleased(tracker, c)
        val failure = runCatching { tracker.registerPrepared(c) }.exceptionOrNull()
        assertEquals("closed command cannot be registered", failure?.message)
        assertNull(tracker.findPrepared(c)); assertTrue(ControlReleaseFixtures.commands(tracker).isEmpty())
    }
    @Test fun M08_completedIdIsNotReserved() {
        val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, 42) })
        val first = store.prepare(); ControlReleaseFixtures.simulatePending(tracker, first); ControlReleaseFixtures.simulateReleased(tracker, first)
        val next = store.prepare()
        assertEquals(first.id, next.id); assertNotSame(first, next)
        assertSame(next, tracker.findPrepared(next)?.command); assertNull(tracker.findPrepared(first))
        assertEquals(ControlCommandLifecycle.RELEASED, first.lifecycleState)
        assertEquals(ControlCommandLifecycle.RETAINED, next.lifecycleState)
    }
    @Test fun M05_concurrentPrepareHasOneWinnerAndNoReissue() {
        val entered = CountDownLatch(2); val go = CountDownLatch(1); val calls = AtomicInteger()
        val store = ControlRecordStore(o.owner, ControlIdGenerator {
            calls.incrementAndGet(); entered.countDown(); check(go.await(10, TimeUnit.SECONDS)); UUID(0, 77)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Result<CommandRef>> { runCatching { store.prepare() } }
            val second = executor.submit<Result<CommandRef>> { runCatching { store.prepare() } }
            assertTrue(entered.await(10, TimeUnit.SECONDS)); go.countDown()
            val a = first.get(10, TimeUnit.SECONDS); val b = second.get(10, TimeUnit.SECONDS)
            assertNotEquals("exactly one registration succeeds", a.isSuccess, b.isSuccess)
            val winner = a.getOrNull() ?: b.getOrThrow()
            assertSame(winner, tracker.findPrepared(winner)?.command)
            assertEquals(1, ControlReleaseFixtures.commands(tracker).size); assertEquals(2, calls.get())
        } finally { go.countDown(); executor.shutdownNow() }
    }
}
