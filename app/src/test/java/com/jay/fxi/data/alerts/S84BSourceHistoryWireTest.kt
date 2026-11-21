package com.jay.fxi.data.alerts

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseContext
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.ui.alert.SettingsSourceHistoryAccess
import com.jay.fxi.ui.alert.SourceHistoryBody
import com.jay.fxi.ui.alert.SourceHistoryFloor
import com.jay.fxi.ui.alert.SourceHistoryRead
import com.jay.fxi.ui.alert.SourceHistoryStateHolder
import com.jay.fxi.ui.alert.productionSourceHistoryRead
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S8-4b (`R4c/S8/s8_4b_contract.agreed.r1.md` §3 "JVM wire"): the tether history holder over [productionSourceHistoryRead], the
 * real repository, client chain and use checks, against a real HTTP server. Open, a normal failure, retry, a success and a pull
 * are one GET each of `/api/source-notification-logs` with the one asset `usdt-krw` and the one limit `100`, each carrying the
 * guard of its own live request (at each send only the current request's guard admits; all refuse once done). The original use
 * withdrawn before the last send check or while the credential is fetched sends nothing; withdrawn during the refresh after a
 * first 401, it sends that first request and no replay. Those three are observed without rendering: the raw state and the
 * read's own exit, so no observation can retire the session and cancel the call before the transport's check. Free, a closed
 * release, a deletion in progress and no identity each reach neither the repository nor the server.
 */
class S84BSourceHistoryWireTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grant = TopicGrantToken(11)
    private val log = """{"id":7,"setting_id":null,"source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,""" +
        """"triggered_rate":1401.2,"sent_at":"2026-09-03T10:00:00+09:00"}"""
    private val foreign = """{"id":8,"setting_id":12,"source":"krx","asset":"usd-krw-futures","condition":"above","threshold":1400.5,""" +
        """"triggered_rate":1401.2,"sent_at":"2026-09-03T10:00:00+09:00"}"""

    private class Tokens : AuthTokenSource {
        val started = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var forcedGate: CompletableDeferred<Unit>? = null
        val forcedStarted = CompletableDeferred<Unit>()
        override fun currentIdentity() = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            started.incrementAndGet()
            gate?.await()
            if (forceRefresh) {
                forcedStarted.complete(Unit)
                forcedGate?.await()
            }
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    private inner class Rig(premiumAt: PremiumAccessState = PremiumAccessState.PremiumConfirmed, var identity: AuthIdentityFence? = alice,
                            release: Boolean = true, deleting: Boolean = false) {
        val server = MockWebServer().apply { start() }
        val tokens = Tokens()
        private val provider = AuthTokenProvider(tokens, orders = AccessOrderSequence())
        val useTagged: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        /** Each tagged send's use context, and at each send whether every context so far admitted. */
        val contexts = CopyOnWriteArrayList<TopicUseContext>()
        val admittedAtSend = CopyOnWriteArrayList<List<Boolean>>()
        @Volatile var beforeUseCheck: (Int) -> Unit = {}
        private val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                val tag = chain.request().tag(TopicUseTag::class.java)
                useTagged += tag != null
                tag?.let { contexts += it.context; admittedAtSend.add(contexts.map { context -> context.admitted() }) }
                beforeUseCheck(useTagged.size)
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        private val wireJson = NetworkModule.provideWireJson()
        private val repository = AlertRepository(AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(http)
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
        val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, premiumAt))
        private val released = AtomicBoolean(release)
        private val deletions = DeletionAdmissionStore().also { if (deleting) it.begin(alice, "op") }
        private val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L
        )
        // The transport's release gate is held open: the access's own release check is the one under test.
        private val access = AlertSettingsAccess({ identity }, { TopicSessionFence(alice, "epoch-1", grant) }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { released.get() }, GraphProtectedAdmission({ identity }, deletions))
        val shown = TopicDisplayOwner(alice, 1L)
        /** Entries into the production read: the holder reached past its access. */
        val repositoryEntries = AtomicInteger()
        /** Exits from the production read, by return or by throw: the read ended without anyone observing the session. */
        val readExits = AtomicInteger()
        private val production = productionSourceHistoryRead(repository)
        private val floor = AlertRateLimitFloor { System.nanoTime() / 1_000_000 }
        /** The holder is Main-confined: one thread plays Main. */
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + main)
        val holder = SourceHistoryStateHolder(
            SettingsSourceHistoryAccess(access) { shown },
            SourceHistoryRead { o, a, g ->
                repositoryEntries.incrementAndGet()
                try {
                    production.read(o, a, g)
                } finally {
                    readExits.incrementAndGet()
                }
            },
            object : SourceHistoryFloor {
                override fun remainingMillis() = floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS)
                override fun record(evidence: AlertRateLimitEvidence) = floor.record(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS, evidence)
            },
            scope
        )

        suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }
        suspend fun body() = onMain { holder.currentState()?.body }
        suspend fun until(label: String, condition: suspend () -> Boolean) {
            try {
                withTimeout(5_000) { while (!condition()) delay(10) }
            } catch (e: Exception) {
                throw AssertionError("timed out: $label", e)
            }
        }
        fun revoke() { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed) }

        fun close() {
            scope.cancel()
            main.close()
            server.shutdown()
        }
    }

    private fun rig(premiumAt: PremiumAccessState = PremiumAccessState.PremiumConfirmed, identity: AuthIdentityFence? = alice,
                    release: Boolean = true, deleting: Boolean = false, body: suspend Rig.() -> Unit) {
        val r = Rig(premiumAt, identity, release, deleting)
        try {
            // Invoked as `body(r)`, never `r.body()`: Rig has its own member body(), which that call would resolve to.
            runBlocking { body(r) }
        } finally {
            r.close()
        }
    }

    private fun MockWebServer.next() = checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "a request reached the server" }

    @Test fun `S8_4B_W01 open, a failure, retry, a success and a pull are one checked GET each for the one asset and limit`() = rig {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log,$foreign],"total_count":2}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log],"total_count":1}"""))
        onMain { holder.open(shown) }
        until("the failure is shown") { body() is SourceHistoryBody.Failed }
        onMain { holder.retry() }
        until("the rows are shown") { body() is SourceHistoryBody.Loaded }
        assertEquals("only the tether row of a known exchange", listOf(7), (body() as SourceHistoryBody.Loaded).items.map { it.id })
        onMain { holder.refresh() }
        until("the pull ended") { (body() as? SourceHistoryBody.Loaded)?.refreshing == false && server.requestCount == 3 }
        for (n in 1..3) {
            val sent = server.next()
            assertEquals("request $n: GET of the logs path", listOf("GET", "/api/source-notification-logs"), listOf(sent.method, sent.requestUrl?.encodedPath))
            assertEquals("request $n: one asset", listOf("usdt-krw"), sent.requestUrl?.queryParameterValues("asset"))
            assertEquals("request $n: one limit", listOf("100"), sent.requestUrl?.queryParameterValues("limit"))
        }
        assertEquals("three sends, each carrying the use check", listOf(true, true, true), useTagged.toList())
        assertEquals("three repository entries", 3, repositoryEntries.get())
        assertEquals("only the current request admits at each send", listOf(listOf(true), listOf(false, true), listOf(false, false, true)),
            admittedAtSend.toList())
        assertEquals("every finished request's guard now refuses", listOf(false, false, false), onMain { contexts.map { it.admitted() } })
    }

    @Test fun `S8_4B_W02 the original use withdrawn before the last send check or while the credential is fetched sends nothing`() {
        rig {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log],"total_count":1}"""))
            beforeUseCheck = { n -> if (n == 1) revoke() }
            onMain { holder.open(shown) }
            until("the read ended and the session with it") { readExits.get() == 1 && onMain { holder.state.value } == null }
            delay(300)
            assertEquals("before the last check: nothing reached the server", 0, server.requestCount)
            assertNull("and nothing to draw", onMain { holder.state.value })
        }
        rig {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log],"total_count":1}"""))
            val gate = CompletableDeferred<Unit>().also { tokens.gate = it }
            onMain { holder.open(shown) }
            until("the credential fetch started") { tokens.started.get() == 1 }
            revoke()
            gate.complete(Unit)
            until("the read ended and the session with it") { readExits.get() == 1 && onMain { holder.state.value } == null }
            delay(300)
            assertEquals("while fetching the credential: nothing reached the server", 0, server.requestCount)
            assertEquals("and nothing reached the wire", 0, useTagged.size)
        }
    }

    @Test fun `S8_4B_W03 withdrawn during the refresh after a first 401, the first request is sent and no replay`() = rig {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log],"total_count":1}"""))
        val held = CompletableDeferred<Unit>().also { tokens.forcedGate = it }
        onMain { holder.open(shown) }
        withTimeout(5_000) { tokens.forcedStarted.await() }
        assertEquals("premise: the first send reached the server", 1, server.requestCount)
        revoke()
        held.complete(Unit)
        until("the read ended and the session with it") { readExits.get() == 1 && onMain { holder.state.value } == null }
        delay(300)
        assertEquals("no replay", 1, server.requestCount)
        assertEquals("the one send carried the use check", listOf(true), useTagged.toList())
    }

    @Test fun `S8_4B_W04 free, a closed release, a deletion and no identity each reach neither the repository nor the server`() {
        for ((label, make) in listOf<Pair<String, (suspend Rig.() -> Unit) -> Unit>>(
            "free" to { b -> rig(premiumAt = PremiumAccessState.FreeConfirmed, body = b) },
            "release closed" to { b -> rig(release = false, body = b) },
            "deletion in progress" to { b -> rig(deleting = true, body = b) },
            "no identity" to { b -> rig(identity = null, body = b) }
        )) make {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"logs":[$log],"total_count":1}"""))
            onMain { holder.open(shown) }
            delay(500)
            assertEquals("$label: no repository entry", 0, repositoryEntries.get())
            assertEquals("$label: no HTTP", 0, server.requestCount)
            assertNull("$label: nothing to draw", onMain { holder.currentState() })
            assertTrue("$label: the session ended", !onMain { holder.live })
        }
    }
}
