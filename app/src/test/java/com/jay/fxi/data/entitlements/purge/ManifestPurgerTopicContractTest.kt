package com.jay.fxi.data.entitlements.purge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.local.TopicLastKnownOwner
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P3-e contract (purger 설계 v3 final §3.2 ②③, §9 P3; P3e/design_codex.r1.md): the real topic last-known adapter
 * inside [ManifestScopePurger] over the whole manifest (Codex r1 REVISE: E01 names what stays owed, E03 reopens the file). It deletes only the owner's retired epoch, yet the whole-obligation
 * answer stays [PurgeResult.Deferred] while cutover and dedicated-path targets remain; only a manifest narrowed to the topic
 * target alone may answer [PurgeResult.Completed]. Characterization over production code that is not changed here; nothing in
 * production registers the adapter (the DI keeps the placeholder). The implementation reads but does not edit this file.
 */
class ManifestPurgerTopicContractTest {
    private companion object {
        const val THROTTLE = 300L
        val T0: Instant = Instant.parse("2026-09-30T00:00:00Z")
        val TARGET = checkNotNull(PurgeManifest.byId("datastore:fxi_topic_last_known"))
        val A1 = TopicLastKnownOwner("uid-a", "epoch-1")
        val A2 = TopicLastKnownOwner("uid-a", "epoch-2")
        val B = TopicLastKnownOwner("uid-b", "epoch-1")
    }

    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @Volatile private var now = 0L
    private val rates = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1400.0, T0)))
    private val namespace = PurgeNamespace("uid-a", "epoch-2", null, PendingPurge("uid-a", "epoch-1", null, setOf(PurgeScope.USER)))

    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    private var diskScope: CoroutineScope? = null
    private var opened: DataStore<Preferences>? = null
    private val disk: DataStore<Preferences> get() = opened ?: scope().let { sc ->
        diskScope = sc
        PreferenceDataStoreFactory.create(scope = sc) { File(folder.root, "last_known.preferences_pb") }.also { opened = it }
    }
    /** Closes the DataStore; the next [disk] opens the same file as a new instance. */
    private suspend fun reopen() { diskScope?.coroutineContext?.get(Job)?.cancelAndJoin(); opened = null; diskScope = null }
    private fun store(data: DataStore<Preferences> = disk) = scope().let { TopicLastKnownStore(data, { now }, THROTTLE, it) to it }
    private fun purger(store: TopicLastKnownStore, manifest: List<PurgeTarget> = PurgeManifest.TARGETS) =
        ManifestScopePurger(mapOf(TARGET.id to TopicLastKnownPurgeAdapter(store)), manifest)

    private suspend fun seed(vararg owners: TopicLastKnownOwner) {
        val (s, sc) = store()
        owners.forEach { s.offer(it, rates) }
        now += 10 * THROTTLE
        withTimeout(10_000) { sc.coroutineContext[Job]!!.children.forEach { it.join() } }
    }
    private suspend fun onDisk(owner: TopicLastKnownOwner): TopicRates = store().first.restore(owner)

    @Test fun E01_theWholeManifest_deletesOnlyTheRetiredEpoch_andStaysDeferred() = runBlocking {
        seed(A1, A2, B)
        val result = purger(store().first).purgeUserScope(namespace)
        assertTrue("E01: Deferred, got $result", result is PurgeResult.Deferred)
        val reason = (result as PurgeResult.Deferred).reason
        for (owed in listOf("datastore:fxi_cache#rates(S3)", "file:graph_cache(S4)", "datastore:fxi_cache#last_bank(S7)"))
            assertTrue("E01: still owes $owed, got $reason", owed in reason)
        assertTrue("E01: the topic target is not owed, got $reason", TARGET.id !in reason)
        assertEquals("E01: retired epoch gone", TopicRates(), onDisk(A1))
        assertEquals("E01: current epoch kept", rates, onDisk(A2))
        assertEquals("E01: other user kept", rates, onDisk(B))
    }

    @Test fun E02_onlyAManifestNarrowedToTheTopicTarget_completes() = runBlocking {
        seed(A1, A2)
        assertEquals("E02", PurgeResult.Completed, purger(store().first, listOf(TARGET)).purgeUserScope(namespace))
        assertEquals("E02: retired epoch gone", TopicRates(), onDisk(A1))
        assertEquals("E02: current epoch kept", rates, onDisk(A2))
        assertEquals("E02: nothing left still completes", PurgeResult.Completed, purger(store().first, listOf(TARGET)).purgeUserScope(namespace))
    }

    @Test fun E03_rerunningOnTheSameOrAFreshStore_isIdempotent_andStillDeferred() = runBlocking {
        seed(A1, A2)
        val s = store().first
        assertTrue("E03 first", purger(s).purgeUserScope(namespace) is PurgeResult.Deferred)
        assertTrue("E03 again", purger(s).purgeUserScope(namespace) is PurgeResult.Deferred)
        assertTrue("E03 fresh store object", purger(store().first).purgeUserScope(namespace) is PurgeResult.Deferred)
        reopen()
        assertTrue("E03 reopened file", purger(store().first).purgeUserScope(namespace) is PurgeResult.Deferred)
        assertEquals("E03: current epoch kept", rates, onDisk(A2))
        assertEquals("E03: retired epoch still gone", TopicRates(), onDisk(A1))
    }

    /** A DataStore whose edits fail with [failure]; reads pass through. */
    private class FailingEdit(private val real: DataStore<Preferences>, private val failure: () -> Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure()
    }

    @Test fun E04_aStorageFailureFailsTheWholeObligation_andACancellationPropagates() = runBlocking {
        seed(A1, A2)
        val failed = purger(store(FailingEdit(disk) { IOException("disk full") }).first).purgeUserScope(namespace)
        assertTrue("E04: Failed, got $failed", failed is PurgeResult.Failed)
        assertTrue("E04: names the topic target", ((failed as PurgeResult.Failed).cause as PurgeTargetsFailedException).targets.any { it.startsWith(TARGET.id) })
        assertEquals("E04: nothing deleted", rates, onDisk(A1))
        try {
            purger(store(FailingEdit(disk) { CancellationException("going away") }).first).purgeUserScope(namespace)
            fail("E04: cancellation must propagate")
        } catch (_: CancellationException) {}
        assertTrue("E04: a clean retry is Deferred", purger(store().first).purgeUserScope(namespace) is PurgeResult.Deferred)
        assertEquals("E04: retired epoch gone after the retry", TopicRates(), onDisk(A1))
    }
}
