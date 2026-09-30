package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P3-c2 contract (purger 설계 v3 final §7·§9 P3 "지연 완료·읽기 중 cache 승격 0"; P3c/design_codex.r1.md c2).
 * Once [TopicLastKnownStore.purgeUser] returns, no namespace it retired may be written again — including an epoch that
 * had nothing stored or pending at purge time — and a [TopicLastKnownStore.restore] whose read began before the purge
 * returns nothing for a namespace the purge retired. The kept epoch and other users are untouched. An in-flight flush
 * is already serialized with the purge by the store's write mutex. Real file DataStore; flushes are joined through the
 * store's own scope instead of waiting a fixed time. The implementation reads but does not edit this file.
 */
class TopicLastKnownPurgeFenceContractTest {
    private companion object {
        const val THROTTLE = 300L
        val A1 = TopicLastKnownOwner("uid-a", "epoch-1")
        val A2 = TopicLastKnownOwner("uid-a", "epoch-2")
        val A3 = TopicLastKnownOwner("uid-a", "epoch-3")
        val B = TopicLastKnownOwner("uid-b", "epoch-1")
        val T0: Instant = Instant.parse("2026-09-30T00:00:00Z")
    }

    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @Volatile private var now = 0L
    private val clock = TopicLastKnownClock { now }
    private val sample = TopicRates(dollarIndex = TopicDollarIndex(98.5, T0, "investing"))
        .merge(listOf(TopicQuote("kb", "usd-krw", 1400.0, T0), TopicQuote("upbit", "usdt-krw", 1420.0, T0)))

    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    private val disk: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope()) { File(folder.root, "last_known.preferences_pb") }
    }

    /** A store and the scope its flushes run in. */
    private class Opened(val store: TopicLastKnownStore, val scope: CoroutineScope)
    private fun open(data: DataStore<Preferences> = disk): Opened = scope().let { Opened(TopicLastKnownStore(data, clock, THROTTLE, it), it) }

    /** Moves the clock past every deadline and joins every scheduled flush. */
    private suspend fun flushed(o: Opened) {
        now += 10 * THROTTLE
        withTimeout(10_000) { o.scope.coroutineContext[Job]!!.children.forEach { it.join() } }
    }

    /** What is on disk, read by a fresh store object that holds no fence. */
    private suspend fun onDisk(owner: TopicLastKnownOwner): TopicRates = open().store.restore(owner)

    /** A DataStore whose next data read takes its snapshot, then waits before handing it over. */
    private class PausingRead(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var armed = false
        val taken = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val data: Flow<Preferences> get() = flow {
            val snapshot = real.data.first()
            if (armed) { armed = false; taken.complete(Unit); release.await() }
            emit(snapshot)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
    }

    @Test fun F01_anEpochWithNothingStored_isStillRetired_byThePurge() = runBlocking {
        val o = open()
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        o.store.offer(A1, sample); o.store.offer(A2, sample)
        flushed(o)
        assertEquals("F01: the kept epoch is written", sample, onDisk(A2))
        assertEquals("F01: the retired epoch never lands", TopicRates(), onDisk(A1))
        assertEquals("F01: the purging store restores nothing for it", TopicRates(), o.store.restore(A1))
    }

    @Test fun F02_purgingEveryEpochOfAUser_blocksThemAll_andLeavesOtherUsers() = runBlocking {
        val o = open()
        o.store.purgeUser("uid-a", keepEpoch = null)
        o.store.offer(A1, sample); o.store.offer(A2, sample); o.store.offer(B, sample)
        flushed(o)
        assertEquals("F02 A1", TopicRates(), onDisk(A1))
        assertEquals("F02 A2", TopicRates(), onDisk(A2))
        assertEquals("F02: another user is untouched", sample, onDisk(B))
    }

    @Test fun F03_aLaterPurge_retiresTheEpochThatWasKeptBefore() = runBlocking {
        val o = open()
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        o.store.purgeUser("uid-a", keepEpoch = "epoch-3")
        o.store.offer(A2, sample); o.store.offer(A3, sample)
        flushed(o)
        assertEquals("F03: the earlier kept epoch is now retired", TopicRates(), onDisk(A2))
        assertEquals("F03: the new kept epoch is written", sample, onDisk(A3))
    }

    // Battery r1 FM5: a late offer for a retired epoch must not even be queued, so the next purge finds nothing there.
    @Test fun F06_aLateOfferForARetiredEpoch_leavesNothingForTheNextPurge() = runBlocking {
        val o = open()
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        o.store.offer(A1, sample)
        assertEquals("F06", false, o.store.purgeUser("uid-a", keepEpoch = "epoch-2"))
    }

    /** A DataStore whose data read always fails. */
    private class FailingRead(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = flow { throw java.io.IOException("read failed") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
    }

    // Codex r2 REVISE on FM7: an already retired namespace answers empty without touching disk, so a failing read cannot escape.
    @Test fun F07_aRetiredNamespace_restoresNothing_evenWhenTheReadWouldFail() = runBlocking {
        val o = open(FailingRead(disk))
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        assertEquals("F07", TopicRates(), o.store.restore(A1))
    }

    @Test fun F04_aRestoreThatReadBeforeThePurge_returnsNothingForTheRetiredNamespace() = runBlocking {
        val seed = open(); seed.store.offer(A1, sample); flushed(seed)
        assertEquals("F04 fixture", sample, onDisk(A1))
        val pausing = PausingRead(disk)
        val o = open(pausing)
        pausing.armed = true
        val restore = async(start = CoroutineStart.UNDISPATCHED) { o.store.restore(A1) }
        withTimeout(10_000) { pausing.taken.await() }
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        pausing.release.complete(Unit)
        assertEquals("F04", TopicRates(), withTimeout(10_000) { restore.await() })
    }

    @Test fun F05_aRestoreOfTheKeptNamespace_isUnaffectedByTheSamePurge() = runBlocking {
        val seed = open(); seed.store.offer(A2, sample); flushed(seed)
        val pausing = PausingRead(disk)
        val o = open(pausing)
        pausing.armed = true
        val restore = async(start = CoroutineStart.UNDISPATCHED) { o.store.restore(A2) }
        withTimeout(10_000) { pausing.taken.await() }
        o.store.purgeUser("uid-a", keepEpoch = "epoch-2")
        pausing.release.complete(Unit)
        assertEquals("F05", sample, withTimeout(10_000) { restore.await() })
    }
}
