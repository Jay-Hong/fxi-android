package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.AuthRequestTag
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned S7 step (a) contract: the bank alert history read carries the premium USER use check to the wire
 * (`R4c/S7/s7a_api_proposal.claude.r2.md` §2.2–§2.3 and §3 S7A_10–18; scope H04 in part and the H07 send side — not the H08
 * screen lifetime, application or render). The client is the production chain, built as TopicUseHttpBoundaryTest.api builds
 * it: the application auth interceptor, the mutation one-shot and, last, the use-checking network interceptor. The existing
 * AlertHistoryContractTest stays unchanged and is S7A_15's regression. The implementation thread reads but does not edit this
 * file.
 */
class BankHistoryUseGuardContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: GatedTokenSource
    private lateinit var provider: AuthTokenProvider

    /** Requests that entered the HTTP client at all: only this tells the repository's own refusals from the wire's. */
    private val clientEntries = AtomicInteger()

    /** For every exchange that reached the network, whether it carried a use tag. */
    private val wireTags = CopyOnWriteArrayList<Boolean>()

    /** Runs on each use-tagged exchange right before the use-checking network interceptor; given the exchange's ordinal. */
    @Volatile private var beforeUseCheck: (Int) -> Unit = {}

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        source = GatedTokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
    }

    @After fun tearDown() = server.shutdown()

    /**
     * Hands out "credential" first and "fresh" on a forced refresh, so a refreshed credential is always replayable. A gate,
     * when set for a kind of fetch, holds that fetch after announcing it until the test releases it.
     */
    private class GatedTokenSource : AuthTokenSource {
        val fetches = AtomicInteger()
        val forcedFetches = AtomicInteger()
        val gate = AtomicReference<Gate?>(null)

        class Gate(val forced: Boolean) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        override fun currentIdentity() = AuthIdentity("user-a", 1)

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            if (forceRefresh) forcedFetches.incrementAndGet()
            gate.get()?.takeIf { it.forced == forceRefresh }?.let { held ->
                held.started.complete(Unit)
                held.release.await()
            }
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    private fun client(): AuthenticatedApiClient {
        val tagged = AtomicInteger()
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor { chain ->
                if (chain.request().tag(AuthRequestTag::class.java) != null) clientEntries.incrementAndGet()
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                val tag = chain.request().tag(TopicUseTag::class.java)
                wireTags += tag != null
                if (tag != null) beforeUseCheck(tagged.incrementAndGet())
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        return AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson)
    }

    private fun repository() = AlertRepository(client())

    /** A use check backed by one flag; safe to read from transport threads. */
    private class Use(admitted: Boolean) {
        val admitted = AtomicBoolean(admitted)
        val check: () -> Boolean = { this.admitted.get() }
    }

    private val empty = """{"logs":[],"total_count":0}"""
    private fun ok(body: String = empty) = MockResponse().setResponseCode(200).setBody(body)
    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request" }

    /** The repository call, its outcome captured whole: a refusal is a cancellation, so it must not end the test's coroutine. */
    private suspend fun AlertRepository.outcome(
        use: (() -> Boolean)?,
        currency: String? = null,
        limit: Int? = null
    ): Result<AuthBoundResult<List<AlertHistoryItem>>> = runCatching {
        val owner = checkNotNull(captureOwnerOrNull()) { "owner" }
        if (limit == null) getHistory(owner, currency, useAdmitted = use) else getHistory(owner, currency, limit, use)
    }

    private fun Result<AuthBoundResult<List<AlertHistoryItem>>>.withheld(label: String): TopicUseWithheldException {
        val thrown = exceptionOrNull()
        assertTrue("$label: thrown, not returned (got ${getOrNull()})", isFailure)
        assertFalse("$label: not a repository failure: $thrown", thrown is AlertRepositoryException)
        assertTrue("$label: withheld, got $thrown", thrown is TopicUseWithheldException)
        return thrown as TopicUseWithheldException
    }

    private fun Result<AuthBoundResult<List<AlertHistoryItem>>>.items(): List<AlertHistoryItem> =
        getOrThrow().fold(onSuccess = { it }, onFailure = { throw it })

    /** The server's wire form: KST wall time with an explicit +09:00 offset and whole seconds. */
    private fun kst(at: Instant): String = at.toLocalDateTime(TimeZone.of("Asia/Seoul")).let {
        "%04d-%02d-%02dT%02d:%02d:%02d+09:00".format(it.year, it.monthNumber, it.dayOfMonth, it.hour, it.minute, it.second)
    }

    /** 200 distinct rows, newest first, built by the serializer rather than by hand. */
    private fun twoHundredRows(): Pair<String, List<Pair<Int, Instant>>> {
        val start = Instant.parse("2026-07-16T01:00:00Z")
        val expected = (0 until 200).map { i -> (1000 - i) to Instant.fromEpochSeconds(start.epochSeconds - i * 60L) }
        val body = buildJsonObject {
            putJsonArray("logs") {
                for ((id, sentAt) in expected) addJsonObject {
                    put("id", id)
                    put("setting_id", if (id % 2 == 0) JsonNull else JsonPrimitive(id))
                    put("bank", "hana")
                    put("currency", "usd-krw")
                    if (id % 2 == 0) put("condition", JsonNull) else put("condition", "above")
                    if (id % 2 == 0) put("threshold", JsonNull) else put("threshold", 1400.5)
                    put("rate", 1401.25)
                    put("sent_at", kst(sentAt))
                }
            }
            put("total_count", expected.size)
        }.toString()
        return body to expected
    }

    /** S7A_10 (H04 in part): one GET with exactly the documented parameters; a full 200-row page is consumed whole, in order. */
    @Test fun S7A_10_anAdmittedReadSendsTheDocumentedParameters_andConsumesAFullPageInOrder() = runBlocking {
        val cases = listOf(
            Triple<String?, Int?, Map<String, List<String>>>(null, null, mapOf("limit" to listOf("100"))),
            Triple("usd-krw", null, mapOf("currency" to listOf("usd-krw"), "limit" to listOf("100"))),
            Triple("eur-krw", 200, mapOf("currency" to listOf("eur-krw"), "limit" to listOf("200")))
        )
        val repository = repository()
        for ((currency, limit, parameters) in cases) {
            server.enqueue(ok())
            assertEquals("$parameters: answered", emptyList<AlertHistoryItem>(),
                repository.outcome(Use(true).check, currency, limit).items())
            val request = take()
            assertEquals("$parameters: method", "GET", request.method)
            val url = request.requestUrl!!
            assertEquals("$parameters: path", "/api/notification-logs", url.encodedPath)
            assertEquals("$parameters: names, nothing else", parameters.keys, url.queryParameterNames)
            for ((name, values) in parameters) assertEquals("$parameters: $name", values, url.queryParameterValues(name))
        }
        val (body, expected) = twoHundredRows()
        server.enqueue(ok(body))
        val rows = repository.outcome(Use(true).check, "usd-krw", 200).items()
        take()
        assertEquals("every row, in the server's order", expected, rows.map { it.id to it.sentAt })
        assertEquals("one request per read", cases.size + 1, server.requestCount)
        assertEquals("every send carried the use check", List(cases.size + 1) { true }, wireTags.toList())
    }

    /** S7A_11 (H07): refused from the start, no credential is fetched, nothing enters the client, nothing is returned. */
    @Test fun S7A_11_aRefusedUseFetchesNoTokenAndSendsNothing() = runBlocking {
        server.enqueue(ok())
        val refused = repository().outcome(Use(false).check, "usd-krw").withheld("refused from the start")
        assertEquals("nothing observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("no credential fetched", 0, source.fetches.get())
        assertEquals("no forced refresh", 0, source.forcedFetches.get())
        assertEquals("never entered the client", 0, clientEntries.get())
        assertEquals("nothing sent", 0, server.requestCount)
    }

    /** S7A_12 (H07 "token 대기 중 철회"): withdrawn while the credential is fetched, the read is refused before the client. */
    @Test fun S7A_12_aUseWithdrawnWhileTheTokenIsFetchedNeverEntersTheClient() = runBlocking {
        server.enqueue(ok())
        val gate = GatedTokenSource.Gate(forced = false).also { source.gate.set(it) }
        val use = Use(true)
        val repository = repository()
        var outcome: Result<AuthBoundResult<List<AlertHistoryItem>>>? = null
        val read = launch { outcome = repository.outcome(use.check, "jpy-krw") }
        gate.started.await()
        use.admitted.set(false)
        gate.release.complete(Unit)
        read.join()
        val refused = checkNotNull(outcome).withheld("withdrawn during the fetch")
        assertEquals("nothing observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("positive control: one credential fetch", 1, source.fetches.get())
        assertEquals("no forced refresh", 0, source.forcedFetches.get())
        assertEquals("refused before the client, not only at the wire", 0, clientEntries.get())
        assertEquals("nothing sent", 0, server.requestCount)
    }

    /** S7A_13 (H07 "401 뒤 replay 전 철회"): withdrawn during the refresh a 401 earned, no replay; the 401 is handed over. */
    @Test fun S7A_13_aReplayIsNotSentOnceTheUseIsWithdrawnDuringTheRefresh() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "5"))
        server.enqueue(ok())
        val gate = GatedTokenSource.Gate(forced = true).also { source.gate.set(it) }
        val use = Use(true)
        val repository = repository()
        var outcome: Result<AuthBoundResult<List<AlertHistoryItem>>>? = null
        val read = launch { outcome = repository.outcome(use.check, "usd-krw") }
        gate.started.await()
        use.admitted.set(false)
        gate.release.complete(Unit)
        read.join()
        val refused = checkNotNull(outcome).withheld("withdrawn during the refresh")
        assertEquals("the first 401 is handed over", listOf(HttpExchangeEvidence(1, 1, 401, "5")), refused.exchanges)
        assertEquals("positive control: one forced refresh", 1, source.forcedFetches.get())
        assertEquals("only the first send reached the server", 1, server.requestCount)
    }

    /** S7A_14: a refusal at each place leaves getHistory as the withheld cancellation, with that place's evidence. */
    @Test fun S7A_14_aRefusalAtEachPlaceIsThrownWithItsEvidence_neverMappedToAFailure() = runBlocking {
        // Before the capture.
        assertEquals(emptyList<HttpExchangeEvidence>(),
            repository().outcome(Use(false).check).withheld("before capture").exchanges)
        // At the wire, before the first send.
        val atWire = Use(true)
        beforeUseCheck = { atWire.admitted.set(false) }
        server.enqueue(ok())
        assertEquals(emptyList<HttpExchangeEvidence>(), repository().outcome(atWire.check).withheld("at the wire").exchanges)
        // Before the replay: covered with its 401 in S7A_13; here only the type and the server's silence matter.
        assertEquals("no send got through", 0, server.requestCount)
    }

    /** S7A_15: without a use check the read is as before — one send, no use tag at the wire, the same answer. */
    @Test fun S7A_15_aReadWithoutAUseCheckIsUnchanged() = runBlocking {
        server.enqueue(ok())
        assertEquals(emptyList<AlertHistoryItem>(), repository().outcome(use = null, currency = "usd-krw").items())
        val url = take().requestUrl!!
        assertEquals(setOf("currency", "limit"), url.queryParameterNames)
        assertEquals("no use tag", listOf(false), wireTags.toList())
        assertNull("no further request", server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    /** S7A_16: past both repository checks, a use withdrawn right before the wire check sends nothing. */
    @Test fun S7A_16_aUseWithdrawnRightBeforeTheWireCheckSendsNothing() = runBlocking {
        server.enqueue(ok())
        val use = Use(true)
        beforeUseCheck = { use.admitted.set(false) }
        val refused = repository().outcome(use.check, "usd-krw").withheld("at the wire")
        assertEquals(emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("positive control: it entered the client", 1, clientEntries.get())
        assertEquals("nothing sent", 0, server.requestCount)
    }

    /** S7A_17: OkHttp's immediate repeat of a 503 is not sent once the use is withdrawn before that exchange. */
    @Test fun S7A_17_anImmediateRepeatOfA503IsNotSentOnceTheUseIsWithdrawn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok())
        val use = Use(true)
        beforeUseCheck = { exchange -> if (exchange == 2) use.admitted.set(false) }
        val refused = repository().outcome(use.check, "usd-krw").withheld("before the repeat")
        assertEquals(listOf(HttpExchangeEvidence(1, 1, 503, "0")), refused.exchanges)
        assertEquals("only the first exchange reached the server", 1, server.requestCount)
    }

    // --- S7A_18: the production authority and the adapter, through the same read -------------------------------------------

    private val alice = AuthIdentityFence("user-a", 1)
    private val token = TopicGrantToken(11)

    private fun snapshot(grant: TopicGrantToken?, userBlocks: Set<TopicAccessBlock> = emptySet(),
                         capabilityBlocks: Set<TopicAccessBlock> = emptySet(), invalidations: Long = 0L) =
        TopicAccessSnapshot.INITIAL.copy(
            revision = 1L,
            facts = TopicAccessFacts.NONE.copy(
                token = grant, tokenStanding = grant != null, userBlocks = userBlocks, capabilityBlocks = capabilityBlocks
            ),
            userInvalidations = invalidations
        )

    /** S7A_18: the issuer's withdrawals reach the read through the adapter; capability-only blocks do not. */
    @Test fun S7A_18_theAdaptersUseCheckGuardsTheRead() = runBlocking {
        val published = AtomicReference(snapshot(token))
        val fence = AtomicReference(TopicSessionFence(alice, "epoch-1", token))
        val owner = TopicDisplayOwner(alice, 3)
        val access = IssuerBankHistoryAccess(
            liveIdentity = { provider.currentIdentityFence() },
            currentAccessFence = { fence.get() },
            displayOwner = { owner },
            uses = SnapshotTopicUseAuthority { published.get() },
            releaseAdmission = { true },
            protectedAdmission = { true }
        )
        check(provider.currentIdentityFence() == alice) { "the fixture's live identity is the fence's" }
        val repository = repository()

        // Each USER withdrawal, and the token's end, refuses an acquired use before any credential or send.
        val withdrawals = TopicAccessBlock.entries.map { "user block $it" to snapshot(token, userBlocks = setOf(it)) } +
            listOf("token ended" to snapshot(null))
        for ((label, withdrawn) in withdrawals) {
            published.set(snapshot(token))
            val use = checkNotNull(access.acquire(owner)) { "$label: positive control" }
            published.set(withdrawn)
            repository.outcome({ access.admits(use) }, "usd-krw").withheld(label)
        }
        assertEquals("no credential fetched", 0, source.fetches.get())
        assertEquals("nothing sent", 0, server.requestCount)

        // A hold that came and went: the old use stays refused; only a fresh acquire reads.
        published.set(snapshot(token))
        val old = checkNotNull(access.acquire(owner)) { "before the hold" }
        published.set(snapshot(token, userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD), invalidations = 1))
        published.set(snapshot(token, invalidations = 1))
        repository.outcome({ access.admits(old) }, "usd-krw").withheld("old use after the hold")
        assertEquals("still nothing sent", 0, server.requestCount)
        val fresh = checkNotNull(access.acquire(owner)) { "fresh use after the hold" }
        server.enqueue(ok())
        assertEquals(emptyList<AlertHistoryItem>(), repository.outcome({ access.admits(fresh) }, "usd-krw").items())
        assertEquals("the fresh use read once", 1, server.requestCount)

        // Capability-only blocks: a new bank use reads.
        published.set(snapshot(token, capabilityBlocks = TopicAccessBlock.entries.toSet(), invalidations = 1))
        val capabilityOnly = checkNotNull(access.acquire(owner)) { "capability-only: acquired" }
        server.enqueue(ok())
        repository.outcome({ access.admits(capabilityOnly) }, "jpy-krw").items()
        assertEquals("the capability-only use read", 2, server.requestCount)
    }
}
