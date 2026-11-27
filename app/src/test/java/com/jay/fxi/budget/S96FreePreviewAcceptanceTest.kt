package com.jay.fxi.budget

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.free.AuthenticatedFreeSnapshotService
import com.jay.fxi.data.free.FreeSnapshotSanitizer
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.FreeSnapshotFixtures
import com.jay.fxi.data.remote.dto.with
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.free.FreeComparisonAlertPreviewSession
import com.jay.fxi.ui.free.FreeSnapshotUiState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

class S96FreePreviewAcceptanceTest {

    private companion object {
        const val UID = "user-a"
        val PERIODS = GraphPeriod.entries.map { it.code }.toSet()

    }

    private val physical: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val premium = AtomicBoolean(false)

    private lateinit var server: MockWebServer
    private lateinit var provider: AuthTokenProvider
    private lateinit var api: AuthenticatedApiClient

    private class TokenSource : AuthTokenSource {
        override fun currentIdentity(): AuthIdentity = AuthIdentity(UID, 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "token"
    }

    private fun tetherSnapshot(period: String): JsonObject {
        val base = FreeSnapshotFixtures.snapshot("tether", period)
        val rate = (base.getValue("rate") as JsonObject).with("usdt_krw", JsonArray(listOf(
            FreeSnapshotFixtures.rate("upbit", "usdt-krw", 1432.5, false),
            FreeSnapshotFixtures.rate("gopax", "usdt-krw", 1430.0, false)
        )))
        return base.with("rate", rate)
    }

    private fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val key = SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                val period = url.queryParameter("period")
                val tab = url.queryParameter("tab")
                val response = when {
                    request.method != "GET" -> MockResponse().setResponseCode(405)
                    url.encodedPath == "/api/entitlements" -> MockResponse().setBody("""{"krx_visible":false,"premium_active":${premium.get()}}""")
                    url.encodedPath == "/api/v2/free/snapshot" && period in PERIODS && tab in setOf("usd","jpy","eur","tether") -> {
                        val body = if (tab == "tether") tetherSnapshot(period!!) else {
                            val fixture = FreeSnapshotFixtures.snapshot(tab!!,period!!)
                            fixture.with("rate",(fixture.getValue("rate") as JsonObject).with("entries", JsonArray(listOf(
                                FreeSnapshotFixtures.rate("investing","$tab-krw"), FreeSnapshotFixtures.rate("kb","$tab-krw",1399.0)))))
                        }
                        MockResponse().setBody(body.toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
                received += "${request.method} $key ${response.status.split(' ')[1]}"
                return response
            }
        }
        server.start()
        provider = AuthTokenProvider(TokenSource(), orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                val key = SendRecorder.describe(url.encodedPath,url.queryParameterNames.associateWith { url.queryParameter(it) })
                chain.proceed(request).also { physical += "${request.method} $key ${it.code}" }
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType()))
            .build()
        api = AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    private fun tearDown() = server.shutdown()

    private val service get() = AuthenticatedFreeSnapshotService(api, FreeSnapshotSanitizer())

    @Test fun A01() = runBlocking {
        for (tab in FreeTab.entries.filter { it.isData }) {
            setUp()
            received.clear()
            physical.clear()
            premium.set(false)
            val start = physical.size
            val code = requireNotNull(tab.serverTab)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            val scheduler = FreeSnapshotScheduler(fetcher = service, uidStream = AuthUidStream { it(UID) },
                authFence = provider::currentIdentityFence, scope = scope, clock = { Instant.parse("2026-09-06T10:31:00+09:00") },
                installId = { "install-1" })
            try {
                scheduler.start()
                scheduler.onActivated(code, GraphPeriod.ONE_DAY)
                val deadline = System.nanoTime() + 20_000_000_000L
                while (scheduler.readState.value.entries.size < PERIODS.size && System.nanoTime() < deadline) delay(20)
                assertEquals("premise: 모든 기간", PERIODS, scheduler.readState.value.entries.keys.map { it.period.code }.toSet())
                val expected = PERIODS.map { "GET /api/v2/free/snapshot?period=$it&tab=$code 200" }.sorted()
                fun sent() = synchronized(physical) { physical.drop(start) }
                fun replies() = synchronized(received) { received.drop(start) }
                delay(250)
                val initialSent = sent()
                val initialReplies = replies()
                assertEquals("premise: 초기 물리 송신", expected, initialSent.sorted())
                assertEquals("premise: 초기 서버 수신", expected, initialReplies.sorted())
                suspend fun silent() {
                    delay(250)
                    val observed = sent() to replies()
                    if (observed != (initialSent to initialReplies))
                        throw AssertionError("무료 체험의 추가 송신\nsent=${observed.first} replies=${observed.second}")
                }
                val hidden = if (tab == FreeTab.TETHER) "gopax" else "kb"
                val roster = if (tab == FreeTab.TETHER) com.jay.fxi.domain.model.RateRowList.TETHER_EXCHANGES else com.jay.fxi.domain.model.RateRowList.FX_BANKS
                val state = FreeSnapshotUiState.from(UID,tab,GraphPeriod.ONE_DAY,scheduler.readState.value,emptySet(),
                    mapOf(roster to com.jay.fxi.domain.model.RateRowPreference(hidden = setOf(hidden))))
                assertTrue("premise: 숨긴 시세", state.rateSections.flatMap { it.rows }.none { it.id == hidden } &&
                    state.comparisonPreviewQuotes.keys.any { it.first == hidden })
                assertEquals(Instant.parse(FreeSnapshotFixtures.BASIS),state.comparisonPreviewAsOf)
                val session = FreeComparisonAlertPreviewSession()
                session.invalidate(UID,tab)
                for (kind in (if (tab == FreeTab.TETHER) listOf("absolute","signed") else listOf("absolute"))) {
                    fun open() = session.open(UID,tab,kind,session.currentBinding,state.comparisonPreviewQuotes,state.comparisonPreviewAsOf)
                        ?: throw AssertionError("premise: 열린 token")
                    val first = open()
                    silent()
                    session.edit(first) { fields, _ -> fields.step(1) }
                    silent()
                    assertTrue(session.close(first))
                    assertEquals(null,session.dismissed(first))
                    silent()
                    val second = open()
                    silent()
                    assertTrue(session.subscribe(second))
                    silent()
                    val signal = session.dismissed(second) ?: throw AssertionError("premise: 구독 신호")
                    silent()
                    assertTrue(session.consume(signal))
                    silent()
                }
                premium.set(true)
                session.dispose()
                assertEquals(EntitlementsResult.Answered(EntitlementsIdentity(UID, 1L), EntitlementsOutcome.StableActive(krxVisible = false)),
                    AuthenticatedEntitlementsSource(api).fetch(true))
                val purchase = "GET /api/entitlements?fresh_premium=true 200"
                delay(250)
                assertEquals(initialSent + purchase, sent())
                assertEquals(initialReplies + purchase, replies())
                service.fetch(code,GraphPeriod.ONE_DAY)
                val manual = "GET /api/v2/free/snapshot?period=1d&tab=$code 200"
                delay(250)
                assertEquals(initialSent + purchase + manual, sent())
                assertEquals(initialReplies + purchase + manual, replies())
            } finally { scope.cancel(); tearDown() }
        }
    }

    @Test fun A02() = runBlocking {
        setUp()
        try {
            service.fetch("usd",GraphPeriod.ONE_DAY)
        } finally { tearDown() }
        val expected = listOf("GET /api/v2/free/snapshot?period=1d&tab=usd 200")
        assertEquals(expected,physical.toList())
        assertEquals(expected,received.toList())
    }
}
