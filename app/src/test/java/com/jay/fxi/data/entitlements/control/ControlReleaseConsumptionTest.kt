package com.jay.fxi.data.entitlements.control

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** A mock single consumer tests the caller contract; no production coordinator is wired. */
internal class ReleaseConsumerProbe(private val store: ControlRecordStore) {
    val calls = mutableListOf<CommandRef>()
    val pendingRetries = mutableSetOf<CommandRef>()
    val businessRetries = mutableSetOf<CommandRef>()
    suspend fun consume(receipt: ControlStoreResult?, consumed: Boolean, handoffStored: Boolean, joined: Boolean,
        cancelledBeforeRelease: Boolean = false): ControlCommandReleaseResult? {
        if (receipt !is ControlStoreResult.Confirmed) return null
        if (!consumed) return null
        if (!handoffStored) return null
        if (!joined) return null
        if (cancelledBeforeRelease) return null
        return release(receipt.command)
    }
    suspend fun release(command: CommandRef): ControlCommandReleaseResult {
        calls += command
        try {
            val result = controlTestTimeout("consumer release", 30_000) { store.releaseAfterConsumption(command) }
            if (result is ControlCommandReleaseResult.Released || result is ControlCommandReleaseResult.AlreadyReleased) pendingRetries -= command
            else if (command.lifecycleState == ControlCommandLifecycle.RELEASE_PENDING) pendingRetries += command
            return result
        } catch (cancelled: CancellationException) {
            if (command.lifecycleState == ControlCommandLifecycle.RELEASE_PENDING) pendingRetries += command
            else businessRetries += command
            throw cancelled
        }
    }
}

class ControlReleaseConsumptionTest : ReleaseOwnerTestBase() {
    private suspend fun held(consumed: Boolean = true, handoff: Boolean = true, joined: Boolean = true, cancelled: Boolean = false) {
        o.seed(); val c = add(); val receipt = o.control.execute(c) as ControlStoreResult.Confirmed
        val probe = ReleaseConsumerProbe(o.control); val before = disk(); val t = history(c)
        assertNull(probe.consume(receipt, consumed, handoff, joined, cancelled))
        assertTrue(probe.calls.isEmpty()); assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertSame(t, history(c)); assertEquals(before, disk()); assertNotNull(own(disk(), c))
    }
    @Test fun D01_consumptionWaitRetainsReceiptAndEvidence() = runReleaseTest<Unit> { held(consumed = false) }
    @Test fun D02a_handoffNotStoredMakesNoReleaseCall() = runReleaseTest<Unit> { held(handoff = false) }
    @Test fun D02b_unjoinedConsumerMakesNoReleaseCall() = runReleaseTest<Unit> { held(joined = false) }
    @Test fun D02c_precedingCancellationMakesNoReleaseCall() = runReleaseTest<Unit> { held(cancelled = true) }
    @Test fun D03_confirmRequestWithoutReceivedConfirmedCannotRelease() = runReleaseTest<Unit> {
        o.seed(); val c = add(); o.storage.afterScope = true
        val result = o.control.execute(c); assertTrue(result is ControlStoreResult.Unconfirmed); assertNotNull(own(disk(), c))
        val probe = ReleaseConsumerProbe(o.control)
        assertNull(probe.consume(result, true, true, true)); assertTrue(probe.calls.isEmpty())
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertNotNull(history(c))
    }
    @Test fun D03_confirmedConsumedHandedOffAndJoinedCallsSameRefOnce() = runReleaseTest<Unit> {
        o.seed(); val c = add(); val receipt = o.control.execute(c) as ControlStoreResult.Confirmed
        val probe = ReleaseConsumerProbe(o.control)
        assertTrue(probe.consume(receipt, true, true, true) is ControlCommandReleaseResult.Released)
        assertEquals(listOf(c), probe.calls); assertTrue(probe.pendingRetries.isEmpty()); assertTrue(probe.businessRetries.isEmpty())
    }
    @Test fun D04_releaseCancellationRoutesSameRefToManagementRetryOnly() = runReleaseTest<Unit> {
        o.seed(); val c = add(); val receipt = o.control.execute(c) as ControlStoreResult.Confirmed
        val probe = ReleaseConsumerProbe(o.control); val pause = ControlStoreTestStorage.Pause(); o.storage.pauseAfterScope = pause
        val caller = async { probe.consume(receipt, true, true, true) }
        try {
            withTimeout(10_000) { pause.reached.await() }; caller.cancelAndJoinForTest(); assertTrue(caller.isCancelled)
            assertEquals(setOf(c), probe.pendingRetries); assertTrue(probe.businessRetries.isEmpty())
        } finally { pause.release.complete(Unit); caller.cancelAndJoinForTest() }
        assertTrue(probe.release(c) is ControlCommandReleaseResult.Released)
        assertEquals(listOf(c, c), probe.calls); assertTrue(probe.pendingRetries.isEmpty()); assertTrue(probe.businessRetries.isEmpty())
    }
}
