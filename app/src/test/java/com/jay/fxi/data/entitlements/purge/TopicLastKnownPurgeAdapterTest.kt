package com.jay.fxi.data.entitlements.purge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.local.TopicLastKnownOwner
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S3-P1 contract (S3P1/decl_codex.r1.md): one journal entry's user-axis deletion of the topic last-known store —
 * every namespace of the entry's owner except the live epoch, on disk and pending — reported as the purger's contract requires:
 * [TargetOutcome.Removed] / [TargetOutcome.NothingToRemove] only when that is proven, [TargetOutcome.Failed] otherwise. The
 * implementation thread reads but does not edit this file.
 */
class TopicLastKnownPurgeAdapterTest {

    private companion object {
        const val THROTTLE = 300L
        val T0: Instant = Instant.parse("2026-09-29T00:00:00Z")
        val TARGET = checkNotNull(PurgeManifest.byId("datastore:fxi_topic_last_known"))
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private var now = 0L
    private val disk: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope()) { File(folder.root, "last_known.preferences_pb") }
    }
    private val store by lazy { TopicLastKnownStore(disk, { now }, THROTTLE, scope()) }
    private val adapter by lazy { TopicLastKnownPurgeAdapter(store) }

    private val rates = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1400.0, T0)))

    /** Writes [owner]'s seed to disk by moving the store's clock past the window. */
    private suspend fun seed(vararg owners: TopicLastKnownOwner) {
        owners.forEach { store.offer(it, rates) }
        now += THROTTLE
        withTimeout(5_000) { while (owners.any { store.restore(it) != rates }) delay(20) }
    }

    private suspend fun keys() = disk.data.first().asMap().keys.map { it.name }.toSet()

    private fun request(owner: String?, pendingEpoch: String?, currentEpoch: String?, scope: PurgeScope = PurgeScope.USER) =
        PurgeRequest(
            TARGET, scope, PurgeCause.IDENTITY_SWITCH,
            PurgeNamespace(owner, currentEpoch, null, PendingPurge(owner, pendingEpoch, null, setOf(scope)))
        )

    @Test
    fun P01_everyPastNamespaceOfTheOwner_goes_andOtherUsersStay() = runBlocking {
        seed(TopicLastKnownOwner("a", "e1"), TopicLastKnownOwner("a", "e2"), TopicLastKnownOwner("b", "e9"))
        assertEquals(TargetOutcome.Removed, adapter.purge(request("a", "e2", "e3")))
        assertEquals(TopicRates(), store.restore(TopicLastKnownOwner("a", "e1")))
        assertEquals(TopicRates(), store.restore(TopicLastKnownOwner("a", "e2")))
        assertEquals(rates, store.restore(TopicLastKnownOwner("b", "e9")))
    }

    @Test
    fun P02_theLiveEpochOfTheSameOwner_isKept() = runBlocking {
        seed(TopicLastKnownOwner("a", "e1"), TopicLastKnownOwner("a", "e2"))
        assertEquals(TargetOutcome.Removed, adapter.purge(request("a", null, "e2")))
        assertEquals(TopicRates(), store.restore(TopicLastKnownOwner("a", "e1")))
        assertEquals(rates, store.restore(TopicLastKnownOwner("a", "e2")))
    }

    @Test
    fun P03_aWriteStillPending_isRemoved_andNeverLands() = runBlocking {
        store.offer(TopicLastKnownOwner("a", "e1"), rates)          // the store's clock has not moved: pending only
        assertEquals(TargetOutcome.Removed, adapter.purge(request("a", "e1", "e2")))
        now += THROTTLE * 3
        delay(THROTTLE * 2)
        assertEquals(emptySet<String>(), keys())
    }

    @Test
    fun P04_nothingOfTheOwnerThere_isNothingToRemove_everyTime() = runBlocking {
        seed(TopicLastKnownOwner("b", "e9"))
        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("a", "e1", "e2")))
        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("a", "e1", "e2")))
        assertEquals(rates, store.restore(TopicLastKnownOwner("b", "e9")))
    }

    @Test
    fun P05_anUnnarrowedOwner_fails_andTouchesNothing() = runBlocking {
        seed(TopicLastKnownOwner("a", "e1"))
        val before = keys()
        assertTrue(adapter.purge(request(null, null, "e2")) is TargetOutcome.Failed)
        assertEquals(before, keys())
    }

    @Test
    fun P06_anObligationNamingTheLiveEpoch_fails_andTouchesNothing() = runBlocking {
        seed(TopicLastKnownOwner("a", "e2"))
        val before = keys()
        assertTrue(adapter.purge(request("a", "e2", "e2")) is TargetOutcome.Failed)
        assertEquals(before, keys())
    }

    @Test
    fun P07_theCapabilityAxis_deletesNothingHere() = runBlocking {
        seed(TopicLastKnownOwner("a", "e1"))
        val before = keys()
        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("a", "e1", "e2", PurgeScope.CAPABILITY)))
        assertEquals(before, keys())
    }

    @Test
    fun P08_aStoreThatCannotBeWritten_isAFailure_notASuccess() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = disk.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk")
        }
        seed(TopicLastKnownOwner("a", "e1"))
        val failing = TopicLastKnownPurgeAdapter(TopicLastKnownStore(broken, { now }, THROTTLE, scope()))
        assertTrue(failing.purge(request("a", "e1", "e2")) is TargetOutcome.Failed)
    }
}
