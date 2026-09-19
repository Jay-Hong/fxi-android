package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.*
import org.junit.Test

class ControlReleaseByteArrayTest : ReleaseOwnerTestBase() {
    private val blobKey = byteArrayPreferencesKey("future_blob")
    private val bytes = byteArrayOf(0, 1, 2, -1)

    private suspend fun otherRecoveryWork(): Pair<CommandRef, CommandRef> {
        val pendingPeer = confirmed(); pending(pendingPeer)
        val unresolvedPeer = add(); o.storage.before = true
        assertTrue(o.control.execute(unresolvedPeer) is ControlStoreResult.Unconfirmed)
        return pendingPeer to unresolvedPeer
    }

    private suspend fun putBlob() {
        o.data.edit {
            it[blobKey] = bytes
            it[ControlStoreTestStorage.EXTRA] = "unrelated value"
            it[ControlStoreTestStorage.HOLD] = " [ ] "
        }
    }

    @Test fun B22a_exactReleasePreservesExternalByteArray() = runReleaseTest<Unit> {
        o.seed(); val (p, u) = otherRecoveryWork()
        val peerHistory = ControlReleaseFixtures.commands(tracking).toMap(); val siblingRows = rows(disk())
        val c = confirmed(); assertNotNull(own(disk(), c)); putBlob()
        val before = disk(); assertArrayEquals(bytes, before[blobKey])
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        val attempt = runCatching { o.control.releaseAfterConsumption(c) }
        assertNull("valid ByteArray release must return normally", attempt.exceptionOrNull())
        val result = attempt.getOrThrow(); assertTrue(result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        val after = disk()
        assertArrayEquals(bytes, after[blobKey]); assertArrayEquals(bytes, result.snapshot.record.original[blobKey])
        assertEquals(before.toMutablePreferences().apply { remove(evidenceKey) },
            after.toMutablePreferences().apply { remove(evidenceKey) })
        assertEquals(after, result.snapshot.record.original); assertEquals(siblingRows, rows(after)); assertNull(own(after, c))
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState); assertNull(tracking.findPrepared(c))
        assertEquals(setOf(p), result.localPendingReleases); assertEquals(setOf(u), result.localUnresolvedCommands)
        assertEquals(setOf(p), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(u), tracking.recoverySnapshot().unresolvedCommands)
        assertEquals(peerHistory, ControlReleaseFixtures.commands(tracking).toMap()); assertFalse(c in tracking.executing)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, p.lifecycleState); assertEquals(ControlCommandLifecycle.RETAINED, u.lifecycleState)
    }

    @Test fun B22b_absenceConfirmationPreservesExternalByteArray() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]"); val (p, u) = otherRecoveryWork()
        val peerHistory = ControlReleaseFixtures.commands(tracking).toMap()
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {}))
        assertNull(history(c).expectedApplied); assertFalse(history(c).observedApplied.get()); putBlob()
        val before = disk(); val fileBefore = file.readBytes(); val writes = o.storage.writes
        assertArrayEquals(bytes, before[blobKey]); assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        val attempt = runCatching { o.control.releaseAfterConsumption(c) }
        assertNull("valid ByteArray absence confirmation must return normally", attempt.exceptionOrNull())
        val result = attempt.getOrThrow(); assertTrue(result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        val after = disk()
        assertArrayEquals(bytes, after[blobKey]); assertArrayEquals(bytes, result.snapshot.record.original[blobKey])
        assertEquals(before, after); assertEquals(before, result.snapshot.record.original)
        assertArrayEquals(fileBefore, file.readBytes()); assertEquals(writes, o.storage.writes); assertNull(own(after, c))
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState); assertNull(tracking.findPrepared(c))
        assertEquals(setOf(p), result.localPendingReleases); assertEquals(setOf(u), result.localUnresolvedCommands)
        assertEquals(setOf(p), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(u), tracking.recoverySnapshot().unresolvedCommands)
        assertEquals(peerHistory, ControlReleaseFixtures.commands(tracking).toMap()); assertFalse(c in tracking.executing)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, p.lifecycleState); assertEquals(ControlCommandLifecycle.RETAINED, u.lifecycleState)
    }

    @Test fun C08_byteArrayAddedWhilePendingDoesNotBlockRetry() = runReleaseTest<Unit> {
        o.seed(); val (p, u) = otherRecoveryWork()
        val peerHistory = ControlReleaseFixtures.commands(tracking).toMap(); val siblingRows = rows(disk())
        val c = confirmed(); val t = history(c); pending(c); val descriptor = t.releaseDescriptor
        putBlob(); val before = disk(); assertArrayEquals(bytes, before[blobKey]); assertNotNull(own(before, c))
        assertSame(t, history(c)); assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        assertEquals(setOf(p, c), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(u), tracking.recoverySnapshot().unresolvedCommands)
        val attempt = runCatching { o.control.releaseAfterConsumption(c) }
        assertNull("valid ByteArray pending retry must return normally", attempt.exceptionOrNull())
        val result = attempt.getOrThrow(); assertTrue(result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        val after = disk()
        assertArrayEquals(bytes, after[blobKey]); assertArrayEquals(bytes, result.snapshot.record.original[blobKey])
        assertEquals(before.toMutablePreferences().apply { remove(evidenceKey); remove(ControlStoreTestStorage.BARRIER) },
            after.toMutablePreferences().apply { remove(evidenceKey); remove(ControlStoreTestStorage.BARRIER) })
        assertEquals(after, result.snapshot.record.original); assertEquals(siblingRows, rows(after)); assertNull(own(after, c))
        assertSame(descriptor, t.releaseDescriptor); assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
        assertNull(tracking.findPrepared(c)); assertEquals(peerHistory, ControlReleaseFixtures.commands(tracking).toMap())
        assertEquals(setOf(p), result.localPendingReleases); assertEquals(setOf(u), result.localUnresolvedCommands)
        assertEquals(setOf(p), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(u), tracking.recoverySnapshot().unresolvedCommands)
        assertFalse(c in tracking.executing)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, p.lifecycleState); assertEquals(ControlCommandLifecycle.RETAINED, u.lifecycleState)
    }

    @Test fun B24c_byteArrayBarrierIsRejectedBeforePending() = runReleaseTest<Unit> {
        o.seed(); val (p, u) = otherRecoveryWork(); val c = confirmed(); val t = history(c)
        val wrongBarrier = byteArrayPreferencesKey(ControlStoreTestStorage.BARRIER.name)
        o.data.edit { it[wrongBarrier] = bytes }
        val before = disk(); val fileBefore = file.readBytes(); val writes = o.storage.writes
        val histories = ControlReleaseFixtures.commands(tracking).toMap()
        assertArrayEquals(bytes, before[wrongBarrier]); assertNull(t.releaseDescriptor); assertNotNull(own(before, c))
        val failure = runCatching { o.control.releaseAfterConsumption(c) }.exceptionOrNull()
        assertTrue("reserved ByteArray barrier must reject the confirmation", failure is IllegalArgumentException)
        assertEquals("read_barrier is owned by DataStoreAccessEpochStore", failure!!.message)
        assertEquals("rejection precedes pending publication", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull(t.releaseDescriptor); assertSame(t, tracking.findPrepared(c))
        assertEquals(setOf(p), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(u), tracking.recoverySnapshot().unresolvedCommands)
        assertEquals(histories, ControlReleaseFixtures.commands(tracking).toMap()); assertFalse(c in tracking.executing)
        assertEquals(before, disk()); assertArrayEquals(bytes, disk()[wrongBarrier])
        assertArrayEquals(fileBefore, file.readBytes()); assertEquals(writes, o.storage.writes)
    }
}
