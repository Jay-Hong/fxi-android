package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Claude-owned R4-a contract (계획 개정 17 첫 단계 "R4 의 미배선 last-known·grant/runtime 경계", S3 DoD "offline seed 는 표시
 * 전용"; R4p/design_codex.r1.md). A last-known restore starts only under a grant the issuer lets start a new use now, for the
 * live identity the grant was fenced to, with a user epoch; a restore whose grant or identity changed while it read returns
 * nothing; a restored seed carries its fence and lifetime so the session re-checks them before applying it. Nothing is
 * applied, written or deleted here, and nothing in production calls it. The implementation reads but does not edit this file.
 */
class TopicLastKnownRestoreGateContractTest {
    private companion object {
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val ID = AuthIdentityFence("uid-a", 2L)
        val FENCE = TopicSessionFence(ID, "epoch-1", TopicGrantToken(7L))
        val LIFE = TopicUseLifetime(TopicGrantToken(7L), 3L)
    }

    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    @Volatile private var now = 0L
    private val disk: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope()) { File(folder.root, "last_known.preferences_pb") }
    }
    private val rates = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1400.0, T0)))

    /** Counts data reads and runs [duringRead] after the snapshot is taken, before it is handed over. */
    private class Watched(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var reads = 0
        @Volatile var duringRead: () -> Unit = {}
        override val data: Flow<Preferences> get() = flow { val s = real.data.first(); reads++; duringRead(); emit(s) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
    }
    private class Authority : TopicUseAuthority {
        @Volatile var grant: TopicUseLifetime? = LIFE
        @Volatile var admitted = true
        override fun acquire(fence: TopicSessionFence): TopicUseLifetime? = grant?.takeIf { it.grant == fence.grant }
        override fun admits(lifetime: TopicUseLifetime): Boolean = admitted && lifetime == grant
    }
    @Volatile private var live: AuthIdentityFence? = ID

    private suspend fun seeded(): Watched {
        val sc = scope()
        val writer = TopicLastKnownStore(disk, { now }, 300L, sc)
        writer.offer(TopicLastKnownOwner("uid-a", "epoch-1"), rates)
        now += 3_000
        withTimeout(10_000) { sc.coroutineContext[Job]!!.children.forEach { it.join() } }
        return Watched(disk)
    }
    private fun gate(w: Watched, a: Authority) = TopicLastKnownRestoreGate(TopicLastKnownStore(w, { now }, 300L, scope()), a) { live }

    @Test fun A1_anAdmittedGrantForTheLiveIdentity_restoresASeedWithItsFenceAndLifetime() = runBlocking {
        val w = seeded()
        assertEquals("A1", TopicLastKnownRestore.Seed(rates, FENCE, LIFE), gate(w, Authority()).restore(FENCE))
        assertEquals("A1: one read", 1, w.reads)
    }

    @Test fun A2_nothingIsReadWithoutAnAdmittedGrantAndItsLiveIdentityAndAnEpoch() = runBlocking {
        val w = seeded()
        val cases = listOf(
            "no grant" to suspend { gate(w, Authority().apply { grant = null }).restore(FENCE) },
            "another grant" to suspend { gate(w, Authority()).restore(FENCE.copy(grant = TopicGrantToken(8L))) },
            "no identity" to suspend { live = null; gate(w, Authority()).restore(FENCE).also { live = ID } },
            "another uid" to suspend { live = AuthIdentityFence("uid-b", 2L); gate(w, Authority()).restore(FENCE).also { live = ID } },
            "another generation" to suspend { live = AuthIdentityFence("uid-a", 3L); gate(w, Authority()).restore(FENCE).also { live = ID } },
            "no epoch" to suspend { gate(w, Authority()).restore(FENCE.copy(userAccessEpoch = null)) }
        )
        for ((name, run) in cases) assertEquals("A2 $name", TopicLastKnownRestore.NotAdmitted, run())
        assertEquals("A2: no read", 0, w.reads)
    }

    @Test fun A3_aGrantOrIdentityThatChangedDuringTheRead_returnsNothing() = runBlocking {
        val w = seeded(); val a = Authority()
        w.duringRead = { a.admitted = false }
        assertEquals("A3 withdrawn", TopicLastKnownRestore.Withdrawn, gate(w, a).restore(FENCE))
        a.admitted = true
        w.duringRead = { live = AuthIdentityFence("uid-a", 3L) }
        assertEquals("A3 identity", TopicLastKnownRestore.Withdrawn, gate(w, a).restore(FENCE))
    }

    @Test fun A4_anEmptyStore_restoresAnEmptySeed() = runBlocking {
        val w = Watched(disk)
        assertEquals("A4", TopicLastKnownRestore.Seed(TopicRates(), FENCE, LIFE), gate(w, Authority()).restore(FENCE))
    }

    @Test fun A5_lock_noProductionCaller() {
        val callers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TopicLastKnownRestoreGate.kt" }
            .filter { it.readText().contains("TopicLastKnownRestoreGate") }.map { it.name }.toList()
        // R4-b3 wires the gate only in the dormant runtime factory; app startup is deferred to R4-c.
        assertEquals("A5", listOf("TopicRuntime.kt"), callers)
    }
}
