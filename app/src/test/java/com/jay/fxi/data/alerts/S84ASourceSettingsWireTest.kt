package com.jay.fxi.data.alerts

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
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S8-4a (`R4c/S8/s8_4a_contract.agreed.r1.md` §3 "운영 배선"): the source owner over the real repository, client chain and use
 * checks, against a real HTTP server — an admitted foreground sends one `GET /api/source-notification-settings?asset=usdt-krw`
 * carrying the use check and adopts its answer; free, a closed release, a deletion in progress and no identity each send none,
 * through the access recheck, and none of them reaches the repository at all. The owner's wiring of
 * [AlertRepository.getSourceSettings] is the S11-locked production lambda.
 */
class S84ASourceSettingsWireTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grant = TopicGrantToken(11)
    private val row = """{"id":11,"user_id":"user-a","source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,""" +
        """"is_enabled":true,"triggered":false,"repeat_interval_sec":null,"created_at":"2026-09-01T09:00:00+09:00",""" +
        """"updated_at":"2026-09-02T09:00:00+09:00","triggered_at":null}"""

    private class Tokens : AuthTokenSource {
        override fun currentIdentity() = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "credential"
    }

    private inner class Rig(premiumAt: OwnedPremiumAccess, var identity: AuthIdentityFence?, release: Boolean, deleting: Boolean) {
        val server = MockWebServer().apply { start() }
        val useTagged = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        private val provider = AuthTokenProvider(Tokens(), orders = AccessOrderSequence())
        private val released = AtomicBoolean(release)
        private val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                useTagged += chain.request().tag(TopicUseTag::class.java) != null
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        private val wireJson = NetworkModule.provideWireJson()
        private val repository = AlertRepository(AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(http)
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { released.get() }), wireJson))
        private val deletions = DeletionAdmissionStore().also { if (deleting) it.begin(alice, "op") }
        private val premium = MutableStateFlow(premiumAt)
        private val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L
        )
        private val access = AlertSettingsAccess(
            { identity }, { TopicSessionFence(alice, "epoch-1", grant) }, SnapshotTopicUseAuthority { snapshot }, { premium.value },
            { released.get() }, GraphProtectedAdmission({ identity }, deletions)
        )
        /** The owner is Main-confined: one thread plays Main. */
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + main)
        /** Entries into the repository's settings read: the owner reached past its own admission. */
        val repositoryEntries = java.util.concurrent.atomic.AtomicInteger()
        val owner = SourceAlertSettingsOwner(
            { o, admitted ->
                repositoryEntries.incrementAndGet()
                repository.getSourceSettings(o, SourceAlertRowPolicy.SETTINGS_ASSET, admitted)
            },
            access, AlertRateLimitFloor { System.nanoTime() / 1_000_000 }, AlertRetryPolicy(), { System.nanoTime() / 1_000_000 }, { 0.0 },
            scope, { delay(it) }, premium, MutableStateFlow(0L), 1_000L
        )

        suspend fun foreground() = withContext(main) { owner.onForeground(false) }

        fun close() {
            scope.cancel()
            main.close()
            server.shutdown()
        }
    }

    private fun rig(premiumAt: OwnedPremiumAccess = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed),
                    identity: AuthIdentityFence? = alice, release: Boolean = true, deleting: Boolean = false, body: suspend Rig.() -> Unit) {
        val r = Rig(premiumAt, identity, release, deleting)
        try {
            runBlocking { r.body() }
        } finally {
            r.close()
        }
    }

    @Test fun `S8_4A_W01 an admitted foreground sends one use-checked settings GET for usdt-krw and adopts its answer`() = rig {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"settings":[$row],"total_count":1}"""))
        foreground()
        val sent = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("one GET reached the server", sent != null)
        assertEquals(listOf("GET", "/api/source-notification-settings?asset=usdt-krw"), listOf(sent!!.method, sent.path))
        var waited = 0
        while (owner.state.value.settings == null && waited < 5_000) { delay(20); waited += 20 }
        assertEquals("the answer is adopted for the identity", listOf(11), owner.state.value.settings?.map { it.id })
        assertEquals("for alice", alice, owner.state.value.identity)
        assertEquals("the send carried the use check", listOf(true), useTagged.toList())
        delay(1_500)
        assertEquals("and no second GET", 1, server.requestCount)
        assertEquals("one repository entry", 1, repositoryEntries.get())
    }

    @Test fun `S8_4A_W02 free, a closed release, a deletion and no identity each send no GET`() {
        val free = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed)
        for ((label, make) in listOf<Pair<String, (suspend Rig.() -> Unit) -> Unit>>(
            "free" to { b -> rig(premiumAt = free, body = b) },
            "release closed" to { b -> rig(release = false, body = b) },
            "deletion in progress" to { b -> rig(deleting = true, body = b) },
            "no identity" to { b -> rig(identity = null, body = b) }
        )) make {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"settings":[$row],"total_count":1}"""))
            foreground()
            delay(2_500)
            assertEquals("$label: no HTTP through two rechecks", 0, server.requestCount)
            // The owner's access refuses first: a transport gate that also stops the send does not stand in for it.
            assertEquals("$label: no repository entry", 0, repositoryEntries.get())
            assertEquals("$label: nothing adopted", null, owner.state.value.settings)
        }
    }
}
