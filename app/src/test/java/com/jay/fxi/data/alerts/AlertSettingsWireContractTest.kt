package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.remote.AuthRequestTag
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the bank alert settings GET over the production client chain
 * (MockWebServer, built as `BankHistoryUseGuardContractTest` builds it, with `AuthenticatedTransport(admitted = { true })`).
 * It locks the wire side of N1-B (a use refused before the capture fetches no token and sends nothing, one withdrawn after the
 * capture sends nothing, one withdrawn between the first send and its 401 replay sends no replay), N1-L (a 401 and its
 * transport replay are one logical owner attempt over two exchanges; the repository types every failure by kind —
 * HTTP, DECODE, NETWORK, AUTH_UNAVAILABLE — with the pre-N1 message and cause left as they were), N1-K / N1-I (a failed HTTP
 * answer hands its status and Retry-After out before the session guard, nothing else does; a real BankAlertSettingsOwner holds
 * its next GET under a 429's Retry-After on its injected clock) and the regression of getHistory and createSetting over the
 * shared safeApiCall. The implementation thread reads but does not edit this file.
 */
class AlertSettingsWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: GatedTokenSource
    private lateinit var provider: AuthTokenProvider

    /** Requests that entered the HTTP client at all: only this tells the repository's own refusals from the wire's. */
    private val clientEntries = AtomicInteger()

    /** Every exchange that reached the network layer, recorded right before the use-checking network interceptor. */
    private val wire = CopyOnWriteArrayList<Exchange>()

    /** Runs on each use-tagged exchange right before the use-checking network interceptor; given the exchange's ordinal. */
    @Volatile private var beforeUseCheck: (Int) -> Unit = {}

    /** The owner's monotonic clock: only its injected sleep moves it. */
    private val clock = AtomicLong(START)

    private val alice = AuthIdentityFence("user-a", 1)

    private data class Exchange(
        val method: String,
        val path: String,
        val authorization: String?,
        val useChecked: Boolean,
        val clock: Long
    )

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        source = GatedTokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        clientEntries.set(0)
        wire.clear()
        beforeUseCheck = {}
        clock.set(START)
    }

    @After fun tearDown() = server.shutdown()

    /** A new server, credential source and provider, for a case that must not inherit the previous one's state. */
    private fun fresh() {
        tearDown()
        setUp()
    }

    /**
     * "credential" first and "fresh" on a forced refresh, so a refreshed credential is always replayable. A gate holds one kind
     * of fetch until the test releases it; [unavailable] makes a non-forced fetch fail as Firebase does without a credential.
     */
    private class GatedTokenSource : AuthTokenSource {
        val current = AtomicReference<AuthIdentity?>(AuthIdentity("user-a", 1))
        val fetches = AtomicInteger()
        val forcedFetches = AtomicInteger()
        val unavailable = AtomicBoolean(false)
        val gate = AtomicReference<Gate?>(null)

        class Gate(val forced: Boolean) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        override fun currentIdentity(): AuthIdentity? = current.get()

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            if (forceRefresh) forcedFetches.incrementAndGet()
            gate.get()?.takeIf { it.forced == forceRefresh }?.let { held ->
                held.started.complete(Unit)
                held.release.await()
            }
            if (!forceRefresh && unavailable.get()) throw AuthUnavailableException("no credential")
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    /** The production chain: the application auth interceptor, the mutation one-shot and, last, the use-checking interceptor. */
    private fun repository(): AlertRepository {
        val useChecked = AtomicInteger()
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
                val request = chain.request()
                val use = request.tag(TopicUseTag::class.java)
                wire += Exchange(request.method, request.url.encodedPath, request.header("Authorization"), use != null, clock.get())
                if (use != null) beforeUseCheck(useChecked.incrementAndGet())
                chain.proceed(request)
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        return AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
    }

    // --- fixtures, built by the serializer rather than by hand --------------------------------------------------------------

    private fun row(
        id: Int,
        currency: String,
        condition: String,
        threshold: Double,
        enabled: Boolean,
        triggered: Boolean,
        triggeredAt: String?,
        repeatSeconds: Int?
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("user_id", "user-a")
        put("bank", "hana")
        put("currency", currency)
        put("condition", condition)
        put("threshold", threshold)
        put("is_enabled", enabled)
        put("triggered", triggered)
        put("created_at", "2026-09-01T09:00:00+09:00")
        put("updated_at", "2026-09-02T09:00:00+09:00")
        put("triggered_at", triggeredAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("repeat_interval_sec", repeatSeconds?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private val rowA = row(11, "usd-krw", "above", 1400.5, enabled = true, triggered = false, triggeredAt = null, repeatSeconds = null)
    private val rowB = row(12, "jpy-krw", "below", 905.25, enabled = false, triggered = true,
        triggeredAt = "2026-09-03T10:30:00+09:00", repeatSeconds = 300)
    private val created = Instant.parse("2026-09-01T00:00:00Z")
    private val updated = Instant.parse("2026-09-02T00:00:00Z")
    private val settingA = AlertSetting(11, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.5, true, false,
        created, updated, null, null)
    private val settingB = AlertSetting(12, "user-a", "hana", "jpy-krw", AlertCondition.BELOW, 905.25, false, true,
        created, updated, Instant.parse("2026-09-03T01:30:00Z"), 300)

    private fun settingsBody(vararg rows: JsonObject): String = buildJsonObject {
        put("settings", JsonArray(rows.toList()))
        put("total_count", rows.size)
    }.toString()

    private fun historyBody(): String = buildJsonObject {
        put("logs", JsonArray(listOf(buildJsonObject {
            put("id", 100)
            put("setting_id", 7)
            put("bank", "hana")
            put("currency", "usd-krw")
            put("condition", "above")
            put("threshold", 1400.5)
            put("rate", 1401.25)
            put("sent_at", "2026-07-16T10:00:00+09:00")
        })))
        put("total_count", 1)
    }.toString()

    private val historyItem = AlertHistoryItem(100, 7, "hana", "usd-krw", AlertCondition.ABOVE, 1400.5, 1401.25,
        Instant.parse("2026-07-16T01:00:00Z"))

    private val premiumRequired = buildJsonObject { put("detail", "Premium subscription required") }.toString()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun answer(code: Int, retryAfter: String? = null) =
        MockResponse().setResponseCode(code).also { if (retryAfter != null) it.setHeader("Retry-After", retryAfter) }

    private fun dropped() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request" }

    /** The pre-N1 call (no use check) and the N1 one: every mapping below must hold for both. */
    private val useChecks: List<Pair<String, (() -> Boolean)?>> = listOf("no use check" to null, "an admitted use" to { true })

    // --- calls and outcome readers ------------------------------------------------------------------------------------------

    /** The call, its outcome captured whole: a refusal is a cancellation, so it must not end the test's coroutine. */
    private suspend fun <T> AlertRepository.call(
        block: suspend AlertRepository.(AuthIdentityFence) -> AuthBoundResult<T>
    ): Result<AuthBoundResult<T>> {
        val repository = this
        return runCatching { repository.block(checkNotNull(repository.captureOwnerOrNull()) { "owner" }) }
    }

    private suspend fun AlertRepository.settings(use: (() -> Boolean)?): Result<AuthBoundResult<List<AlertSetting>>> =
        call { owner -> if (use == null) getSettings(owner) else getSettings(owner, use) }

    private suspend fun AlertRepository.history(): Result<AuthBoundResult<List<AlertHistoryItem>>> =
        call { owner -> getHistory(owner, "usd-krw") }

    private suspend fun AlertRepository.create(): Result<AuthBoundResult<AlertSetting>> =
        call { owner -> createSetting(owner, AlertSettingRequest("hana", "usd-krw", AlertCondition.ABOVE, 1400.5, true)) }

    private fun <T> Result<AuthBoundResult<T>>.returned(label: String): AuthBoundResult<T> {
        assertTrue("$label: returned as a result, not thrown (${exceptionOrNull()})", isSuccess)
        return getOrThrow()
    }

    private fun Result<*>.withheld(label: String): TopicUseWithheldException {
        val thrown = exceptionOrNull()
        assertTrue("$label: thrown, not returned (got ${getOrNull()})", isFailure)
        assertFalse("$label: not a repository failure: $thrown", thrown is AlertRepositoryException)
        assertTrue("$label: the use was withheld, got $thrown", thrown is TopicUseWithheldException)
        return thrown as TopicUseWithheldException
    }

    private fun <T> AuthBoundResult<T>.value(label: String): T =
        fold(onSuccess = { it }, onFailure = { throw AssertionError("$label: expected a success, got $it") })

    private fun <T> AuthBoundResult<T>.failure(label: String): Throwable =
        fold(onSuccess = { throw AssertionError("$label: expected a failure, got $it") }, onFailure = { it })

    private fun Throwable.repositoryFailure(label: String, message: String, kind: AlertRepositoryFailureKind): AlertRepositoryException {
        assertTrue("$label: a repository failure, got $this", this is AlertRepositoryException)
        val failure = this as AlertRepositoryException
        assertEquals("$label: the base type, no subtype", AlertRepositoryException::class.java, failure.javaClass)
        assertEquals("$label: the pre-N1 message, unchanged", message, failure.message)
        assertEquals("$label: the typed kind", kind, failure.failureKind)
        return failure
    }

    private suspend fun until(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(5_000) {
            while (!condition()) delay(5)
            true
        } ?: false
        assertTrue("$label (not within 5 s)", met)
    }

    // --- a real owner over the real repository ------------------------------------------------------------------------------

    private class Rig(
        val owner: BankAlertSettingsOwner,
        val access: AlertSettingsAccess,
        val floor: AlertRateLimitFloor,
        val sleeps: List<Long>
    )

    /** Admits every use for alice: this file is about the wire; the access conditions are AlertSettingsAccessContractTest's. */
    private fun access(): AlertSettingsAccess {
        val published = TopicSessionFence(alice, "epoch-1", TopicGrantToken(7))
        return AlertSettingsAccess(
            liveIdentity = { provider.currentIdentityFence() },
            currentAccessFence = { published },
            uses = object : TopicUseAuthority {
                override fun acquire(fence: TopicSessionFence): TopicUseLifetime? = TopicUseLifetime(fence.grant, 0)
                override fun admits(lifetime: TopicUseLifetime): Boolean = true
            },
            premium = { OwnedPremiumAccess("user-a", 1, PremiumAccessState.PremiumConfirmed) },
            releaseAdmission = { true },
            protectedAdmission = { true }
        )
    }

    /**
     * One BankAlertSettingsOwner through its primary constructor, on this thread's event loop (its Main), with the injected
     * clock: the sleep advances [clock] at once, so time passes only as the owner says it waits. The owner's jobs end with the
     * block.
     */
    private suspend fun withOwner(
        jitterValue: Double,
        getSettings: suspend (AuthIdentityFence, () -> Boolean) -> AuthBoundResult<List<AlertSetting>>,
        body: suspend (Rig) -> Unit
    ): Unit = coroutineScope {
        val ownerJob = SupervisorJob(coroutineContext[Job])
        try {
            val access = access()
            val floor = AlertRateLimitFloor(nowMillis = { clock.get() })
            val sleeps = CopyOnWriteArrayList<Long>()
            val owner = BankAlertSettingsOwner(
                getSettings = getSettings,
                access = access,
                floor = floor,
                policy = AlertRetryPolicy(),
                nowMillis = { clock.get() },
                jitter = { jitterValue },
                scope = CoroutineScope(coroutineContext + ownerJob),
                sleep = { millis ->
                    sleeps += millis
                    clock.addAndGet(millis)
                },
                premium = MutableStateFlow(OwnedPremiumAccess("user-a", 1, PremiumAccessState.PremiumConfirmed)),
                accessRevisions = MutableStateFlow(0L),
                graphHostReady = MutableStateFlow(true),
                setEnabledRemote = { _, _, _, _ -> error("S7E5A2: no toggle in this test") },
                deleteRemote = { _, _, _ -> error("S7E5A2: no delete in this test") },
                createRemote = { _, _, _ -> error("S7E5B1: no create in this test") },
                editRemote = { _, _, _, _ -> error("S7E5B1: no edit in this test") }
            )
            // Let the owner take its first signal values, as it has by the time a registry tells it the foreground.
            yield()
            body(Rig(owner, access, floor, sleeps))
        } finally {
            ownerJob.cancel()
        }
    }

    // --- N1-B: the use check on the wire -------------------------------------------------------------------------------------

    /** S7N1_B01 (positive premise of N1-B): admitted, one GET carries the use check and its list is decoded; no evidence. */
    @Test fun `S7N1_B01 an admitted settings read is one use-checked GET and its list is decoded`() = runBlocking<Unit> {
        for ((useLabel, use) in useChecks) {
            fresh()
            val label = "B01 $useLabel"
            server.enqueue(ok(settingsBody(rowA, rowB)))
            val result = repository().settings(use).returned(label)
            assertEquals("$label: every field decoded, in order", listOf(settingA, settingB), result.value(label))
            assertNull("$label: no rate-limit evidence on a success", result.rateLimitResponseEvidence)
            val request = take()
            assertEquals("$label: the bank settings GET", "GET /api/notification-settings",
                "${request.method} ${request.requestUrl!!.encodedPath}")
            assertEquals("$label: no query", emptySet<String>(), request.requestUrl!!.queryParameterNames)
            assertEquals("$label: the captured credential", "Bearer credential", request.getHeader("Authorization"))
            assertEquals("$label: one exchange", 1, server.requestCount)
            assertEquals("$label: the use check rides the send only when given", listOf(use != null), wire.map { it.useChecked })
        }
    }

    /** S7N1_B02 (N1-B wire): refused before the capture, no credential is fetched and nothing enters the client. */
    @Test fun `S7N1_B02 a use refused before the capture fetches no token and sends nothing`() = runBlocking<Unit> {
        server.enqueue(ok(settingsBody(rowA)))
        val refused = repository().settings({ false }).withheld("B02 refused before the capture")
        assertEquals("B02: nothing observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("B02: no credential fetched", 0, source.fetches.get())
        assertEquals("B02: never entered the client", 0, clientEntries.get())
        assertEquals("B02: nothing sent", 0, server.requestCount)
        // Positive control on the same fixture: an admitted use reads, so the zeros above are the refusal's.
        assertEquals("B02 control: read once admitted", listOf(settingA), repository().settings({ true }).returned("B02 control").value("B02 control"))
        assertEquals("B02 control: one credential fetch", 1, source.fetches.get())
        assertEquals("B02 control: one exchange", 1, server.requestCount)
    }

    /** S7N1_B03 (N1-B wire): withdrawn while the credential is fetched, the read is refused right after the capture. */
    @Test fun `S7N1_B03 a use withdrawn during the credential fetch never enters the client`() = runBlocking<Unit> {
        server.enqueue(ok(settingsBody(rowA)))
        val gate = GatedTokenSource.Gate(forced = false).also { source.gate.set(it) }
        val admitted = AtomicBoolean(true)
        val repository = repository()
        var outcome: Result<AuthBoundResult<List<AlertSetting>>>? = null
        val read = launch { outcome = repository.settings({ admitted.get() }) }
        withTimeout(5_000) { gate.started.await() }
        assertEquals("B03: premise, nothing entered the client while the credential is fetched", 0, clientEntries.get())
        admitted.set(false)
        gate.release.complete(Unit)
        read.join()
        val refused = checkNotNull(outcome).withheld("B03 withdrawn during the fetch")
        assertEquals("B03: nothing observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("B03: positive premise, the credential was fetched once", 1, source.fetches.get())
        assertEquals("B03: refused before the client, not only at the wire", 0, clientEntries.get())
        assertEquals("B03: nothing sent", 0, server.requestCount)
    }

    /** S7N1_B04 (N1-B wire): past both repository checks, a use withdrawn right before the first send sends nothing. */
    @Test fun `S7N1_B04 a use withdrawn after the capture is refused at the wire before the first send`() = runBlocking<Unit> {
        server.enqueue(ok(settingsBody(rowA)))
        val admitted = AtomicBoolean(true)
        beforeUseCheck = { exchange -> if (exchange == 1) admitted.set(false) }
        val refused = repository().settings({ admitted.get() }).withheld("B04 at the wire")
        assertEquals("B04: nothing observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
        assertEquals("B04: positive premise, the credential was captured", 1, source.fetches.get())
        assertEquals("B04: positive premise, it entered the client", 1, clientEntries.get())
        assertEquals("B04: positive premise, the exchange reached the use check", 1, wire.size)
        assertEquals("B04: nothing sent", 0, server.requestCount)
    }

    /** S7N1_B05 (N1-B wire): withdrawn during the refresh a 401 earned, no replay; the 401 is handed over. Control replays. */
    @Test fun `S7N1_B05 a use withdrawn between the first send and the 401 replay sends no replay`() = runBlocking<Unit> {
        for (withdraw in listOf(true, false)) {
            fresh()
            val label = if (withdraw) "B05 withdrawn" else "B05 control"
            server.enqueue(answer(401, "5"))
            server.enqueue(ok(settingsBody(rowA)))
            val gate = GatedTokenSource.Gate(forced = true).also { source.gate.set(it) }
            val admitted = AtomicBoolean(true)
            val repository = repository()
            var outcome: Result<AuthBoundResult<List<AlertSetting>>>? = null
            val read = launch { outcome = repository.settings({ admitted.get() }) }
            withTimeout(5_000) { gate.started.await() }
            assertEquals("$label: premise, the first send reached the server", 1, server.requestCount)
            if (withdraw) admitted.set(false)
            gate.release.complete(Unit)
            read.join()
            assertEquals("$label: positive premise, one forced refresh", 1, source.forcedFetches.get())
            if (withdraw) {
                val refused = checkNotNull(outcome).withheld(label)
                assertEquals("$label: the first 401 is handed over", listOf(HttpExchangeEvidence(1, 1, 401, "5")), refused.exchanges)
                assertEquals("$label: no replay sent", 1, server.requestCount)
            } else {
                assertEquals("$label: the replay read", listOf(settingA), checkNotNull(outcome).returned(label).value(label))
                assertEquals("$label: the replay was sent", 2, server.requestCount)
                assertEquals("$label: the replay carries the refreshed credential",
                    listOf("Bearer credential", "Bearer fresh"), wire.map { it.authorization })
                assertEquals("$label: both sends use-checked", listOf(true, true), wire.map { it.useChecked })
            }
        }
    }

    // --- N1-L: one logical owner attempt, and the typed failure kinds --------------------------------------------------------

    /** S7N1_L01 (N1-L wire): the transport's 401 replay is not an owner retry: one getSettings call, two exchanges, Ready. */
    @Test fun `S7N1_L01 a 401 replayed into a success is one owner attempt over two exchanges`() = runBlocking<Unit> {
        server.enqueue(answer(401))
        server.enqueue(ok(settingsBody(rowA)))
        val repository = repository()
        val attempts = AtomicInteger()
        withOwner(0.0, { owner, admitted -> attempts.incrementAndGet(); repository.getSettings(owner, admitted) }) { rig ->
            assertNotNull("L01: premise, the fixture access admits a use", rig.access.acquire())
            rig.owner.onForeground()
            until("L01: the settings arrived") { rig.owner.state.value.phase == AlertLoadPhase.Ready }
            delay(QUIET_MILLIS) // A bounded negative window: no further attempt or exchange may follow.
            assertEquals("L01: the list, Ready, for the identity it was read as",
                BankAlertSettingsState(AlertLoadPhase.Ready, listOf(settingA), alice), rig.owner.state.value)
            assertEquals("L01: one logical owner attempt", 1, attempts.get())
            assertEquals("L01: two exchanges, the first and its replay", 2, server.requestCount)
            assertEquals("L01: the second exchange is the transport replay on the refreshed credential",
                listOf("Bearer credential", "Bearer fresh"), wire.map { it.authorization })
            assertEquals("L01: one forced refresh", 1, source.forcedFetches.get())
            assertEquals("L01: both sends carried the owner's use check", listOf(true, true), wire.map { it.useChecked })
        }
    }

    /** S7N1_L02 (N1-L wire): a replay that is refused again is one owner failure, terminal at once; nothing more is sent. */
    @Test fun `S7N1_L02 a 401 replayed into a 401 is one owner failure over two exchanges`() = runBlocking<Unit> {
        server.enqueue(answer(401))
        server.enqueue(answer(401))
        val repository = repository()
        val attempts = AtomicInteger()
        withOwner(0.0, { owner, admitted -> attempts.incrementAndGet(); repository.getSettings(owner, admitted) }) { rig ->
            assertNotNull("L02: premise, the fixture access admits a use", rig.access.acquire())
            rig.owner.onForeground()
            until("L02: the load ended") { rig.owner.state.value.phase is AlertLoadPhase.TerminalError }
            delay(QUIET_MILLIS) // A bounded negative window: no further attempt or exchange may follow.
            val state = rig.owner.state.value
            assertEquals("L02: 401 ends at once as AuthRequired",
                AlertLoadPhase.TerminalError(AlertLoadFailure.AuthRequired), state.phase)
            assertNull("L02: no snapshot", state.settings)
            assertEquals("L02: one logical owner attempt", 1, attempts.get())
            assertEquals("L02: two exchanges, the first and its replay", 2, server.requestCount)
            assertEquals("L02: the second exchange is the transport replay on the refreshed credential",
                listOf("Bearer credential", "Bearer fresh"), wire.map { it.authorization })
            assertEquals("L02: one forced refresh", 1, source.forcedFetches.get())
        }
    }

    private class HttpCase(
        val label: String,
        val answers: () -> List<MockResponse>,
        val status: Int,
        val retryAfter: String?,
        val message: String,
        val transportKind: AuthenticatedFailureKind
    )

    /** The pre-N1 HTTP mapping (mapAlertHttpFailure), unchanged; the last answer is the one the repository maps. */
    private fun httpCases(): List<HttpCase> = listOf(
        HttpCase("500", { listOf(answer(500)) }, 500, null, "서버 오류 (500)", AuthenticatedFailureKind.OTHER_HTTP),
        HttpCase("502 with Retry-After 1.5", { listOf(answer(502, "1.5")) }, 502, "1.5", "서버 오류 (502)",
            AuthenticatedFailureKind.OTHER_HTTP),
        HttpCase("503 with Retry-After 3", { listOf(answer(503, "3")) }, 503, "3", "구독 확인 중", AuthenticatedFailureKind.OTHER_HTTP),
        HttpCase("429 with Retry-After 7", { listOf(answer(429, "7")) }, 429, "7", "요청이 너무 많습니다",
            AuthenticatedFailureKind.OTHER_HTTP),
        HttpCase("429 without Retry-After", { listOf(answer(429)) }, 429, null, "요청이 너무 많습니다", AuthenticatedFailureKind.OTHER_HTTP),
        HttpCase("403 premium required", { listOf(answer(403).setBody(premiumRequired)) }, 403, null, "인증 오류",
            AuthenticatedFailureKind.KNOWN_AUTHORIZATION),
        HttpCase("403 untyped with Retry-After 4", { listOf(answer(403, "4")) }, 403, "4", "알 수 없는 권한 응답",
            AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION),
        HttpCase("404", { listOf(answer(404)) }, 404, null, "알 수 없는 404 응답", AuthenticatedFailureKind.UNKNOWN_NOT_FOUND),
        HttpCase("401 replayed into 401 with Retry-After 9", { listOf(answer(401), answer(401, "9")) }, 401, "9", "인증 오류",
            AuthenticatedFailureKind.AUTHENTICATION)
    )

    /** S7N1_L03 (N1-L): an HTTP failure is HTTP-typed, keeps its HTTP evidence, its pre-N1 message and its null cause. */
    @Test fun `S7N1_L03 an HTTP failure is typed HTTP with its pre-N1 message and no cause`() = runBlocking<Unit> {
        for (case in httpCases()) for ((useLabel, use) in useChecks) {
            fresh()
            val label = "L03 ${case.label}, $useLabel"
            case.answers().forEach(server::enqueue)
            val failure = repository().settings(use).returned(label).failure(label)
                .repositoryFailure(label, case.message, AlertRepositoryFailureKind.HTTP)
            val http = checkNotNull(failure.httpFailure) { "$label: the HTTP failure is kept" }
            assertEquals("$label: status", case.status, http.statusCode)
            assertEquals("$label: Retry-After verbatim", case.retryAfter, http.retryAfter)
            assertEquals("$label: transport kind", case.transportKind, http.kind)
            assertNull("$label: no cause, as before", failure.cause)
            assertNull("$label: no decode evidence", failure.responseEvidence)
            assertEquals("$label: exchanges", case.answers().size, server.requestCount)
        }
    }

    /** The settings row without a field the server always sends, which has no default. */
    private fun missingFieldBody(): String {
        val body = settingsBody(JsonObject(rowA - "repeat_interval_sec"))
        check(body != settingsBody(rowA)) { "the fixture edit did not apply" }
        return body
    }

    /** S7N1_L04 (N1-L): a 200 whose body fails the schema is DECODE, keeping its response and the decoding cause. */
    @Test fun `S7N1_L04 a body that fails the schema is DECODE with its response kept`() = runBlocking<Unit> {
        for ((bodyLabel, body) in listOf("malformed JSON" to MALFORMED, "a setting without repeat_interval_sec" to missingFieldBody())) {
            for ((useLabel, use) in useChecks) {
                fresh()
                val label = "L04 $bodyLabel, $useLabel"
                server.enqueue(ok(body).setHeader("Retry-After", "13"))
                val failure = repository().settings(use).returned(label).failure(label)
                    .repositoryFailure(label, "서버 응답 형식 오류", AlertRepositoryFailureKind.DECODE)
                val evidence = checkNotNull(failure.responseEvidence) { "$label: the response is kept" }
                assertEquals("$label: its status", 200, evidence.statusCode)
                assertEquals("$label: its Retry-After", "13", evidence.retryAfter)
                assertEquals("$label: its exact body", body, evidence.rawBodyText)
                assertTrue("$label: the decoding exception is the cause, as before, got ${failure.cause}",
                    failure.cause is AuthenticatedBodyDecodingException)
                assertNull("$label: not an HTTP failure", failure.httpFailure)
            }
        }
    }

    /** S7N1_L05 (N1-L): a transport IOException is NETWORK with the pre-N1 message and, as before, no cause. */
    @Test fun `S7N1_L05 a dropped connection is NETWORK with its pre-N1 message and no cause`() = runBlocking<Unit> {
        for ((useLabel, use) in useChecks) {
            fresh()
            val label = "L05 $useLabel"
            server.enqueue(dropped())
            val failure = repository().settings(use).returned(label).failure(label)
                .repositoryFailure(label, "네트워크 오류", AlertRepositoryFailureKind.NETWORK)
            assertNull("$label: no cause, as before", failure.cause)
            assertNull("$label: no HTTP failure", failure.httpFailure)
            assertNull("$label: no decode evidence", failure.responseEvidence)
            assertEquals("$label: premise, the request reached the server", 1, server.requestCount)
        }
    }

    /** S7N1_L06 (N1-L): a credential that cannot be had at the capture is AUTH_UNAVAILABLE, message unchanged, nothing sent. */
    @Test fun `S7N1_L06 a credential unavailable at the capture is AUTH_UNAVAILABLE with its pre-N1 message`() = runBlocking<Unit> {
        for ((useLabel, use) in useChecks) {
            fresh()
            val label = "L06 $useLabel"
            source.unavailable.set(true)
            server.enqueue(ok(settingsBody(rowA)))
            val failure = repository().settings(use).returned(label).failure(label)
                .repositoryFailure(label, "인증 오류", AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
            assertNull("$label: no cause, as before", failure.cause)
            assertNull("$label: no HTTP failure", failure.httpFailure)
            assertNull("$label: no decode evidence", failure.responseEvidence)
            assertEquals("$label: positive premise, the credential fetch was tried", 1, source.fetches.get())
            assertEquals("$label: nothing entered the client", 0, clientEntries.get())
            assertEquals("$label: nothing sent", 0, server.requestCount)
        }
    }

    // --- N1-K / N1-I: the rate-limit evidence and the owner's floor ----------------------------------------------------------

    /** S7N1_K01 (N1-K): the status and verbatim Retry-After of the failed answer, still readable once the session moved. */
    @Test fun `S7N1_K01 a failed HTTP answer hands its status and Retry-After readable before the session guard`() = runBlocking<Unit> {
        for (case in httpCases()) for ((useLabel, use) in useChecks) {
            fresh()
            val label = "K01 ${case.label}, $useLabel"
            case.answers().forEach(server::enqueue)
            val result = repository().settings(use).returned(label)
            val expected = AlertRateLimitResponseEvidence(case.status, case.retryAfter)
            assertEquals("$label: the answer's status and header, nothing else", expected, result.rateLimitResponseEvidence)
            source.current.set(AuthIdentity("user-b", 1))
            val guarded = runCatching { result.fold({ "applied" }, { "failure applied" }) }.exceptionOrNull()
            assertTrue("$label: premise, the session moved and fold refuses, got $guarded", guarded is AuthIdentityChangedException)
            assertEquals("$label: still readable past the closed guard", expected, result.rateLimitResponseEvidence)
        }
    }

    /** S7N1_K02 (N1-K): a success, a decode failure, a network failure and an unavailable credential hand no evidence. */
    @Test fun `S7N1_K02 there is no rate-limit evidence without a failed HTTP answer`() = runBlocking<Unit> {
        for ((useLabel, use) in useChecks) {
            fresh()
            val success = "K02 success with Retry-After 11, $useLabel"
            server.enqueue(ok(settingsBody(rowA)).setHeader("Retry-After", "11"))
            val read = repository().settings(use).returned(success)
            assertEquals("$success: premise, a success", listOf(settingA), read.value(success))
            assertNull("$success: no evidence", read.rateLimitResponseEvidence)

            fresh()
            val decode = "K02 decode failure with Retry-After 13, $useLabel"
            server.enqueue(ok(MALFORMED).setHeader("Retry-After", "13"))
            val decoded = repository().settings(use).returned(decode)
            val decodeFailure = decoded.failure(decode).repositoryFailure(decode, "서버 응답 형식 오류", AlertRepositoryFailureKind.DECODE)
            assertEquals("$decode: premise, the header is in the decode evidence", "13", decodeFailure.responseEvidence?.retryAfter)
            assertNull("$decode: no evidence", decoded.rateLimitResponseEvidence)

            fresh()
            val network = "K02 network failure, $useLabel"
            server.enqueue(dropped())
            val disconnected = repository().settings(use).returned(network)
            disconnected.failure(network).repositoryFailure(network, "네트워크 오류", AlertRepositoryFailureKind.NETWORK)
            assertNull("$network: no evidence", disconnected.rateLimitResponseEvidence)

            fresh()
            val unavailable = "K02 credential unavailable, $useLabel"
            source.unavailable.set(true)
            val refused = repository().settings(use).returned(unavailable)
            refused.failure(unavailable).repositoryFailure(unavailable, "인증 오류", AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
            assertNull("$unavailable: no evidence", refused.rateLimitResponseEvidence)
        }
    }

    /**
     * S7N1_I01 (N1-I with N1-K, a real owner): a 429 with Retry-After 2 sets the process floor from the moment it arrived and
     * the owner's next GET goes no sooner than 2 s later on its injected clock; then the settings arrive. Jitter -0.2 makes the
     * backoff alone 1.6 s, so an owner that ignored the header would send early; 0.19 makes it 2.38 s, and the header — not
     * backoff on top of it — is the delay (API r1 §2 Retry(retryAfter, byRetryAfter = true), §5 step 6).
     */
    @Test fun `S7N1_I01 a 429 with Retry-After 2 holds the next owner GET for 2 s of its clock and the settings arrive`() = runBlocking<Unit> {
        for (jitter in listOf(-0.2, 0.19)) {
            fresh()
            val label = "I01 jitter $jitter"
            server.enqueue(answer(429, "2"))
            server.enqueue(ok(settingsBody(rowA, rowB)))
            val repository = repository()
            withOwner(jitter, { o, a -> repository.getSettings(o, a) }) { rig ->
                assertNotNull("$label: premise, the fixture access admits a use", rig.access.acquire())
                assertEquals("$label: premise, no floor yet", Long.MIN_VALUE,
                    rig.floor.notBefore(AuthenticatedEndpoint.NOTIFICATION_SETTINGS))
                rig.owner.onForeground()
                until("$label: the settings arrived") { rig.owner.state.value.phase == AlertLoadPhase.Ready }
                delay(QUIET_MILLIS) // A bounded negative window: no further exchange may follow.
                assertEquals("$label: the list, Ready, for the identity it was read as",
                    BankAlertSettingsState(AlertLoadPhase.Ready, listOf(settingA, settingB), alice), rig.owner.state.value)
                assertEquals("$label: two exchanges, no more", 2, server.requestCount)
                assertEquals("$label: both are the settings GET", List(2) { "GET /api/notification-settings" },
                    wire.map { "${it.method} ${it.path}" })
                val sentAt = wire.map { it.clock }
                assertEquals("$label: premise, the first GET went at the start", START, sentAt[0])
                assertEquals("$label: the floor is the 429's 2 s from when it arrived", START + 2_000,
                    rig.floor.notBefore(AuthenticatedEndpoint.NOTIFICATION_SETTINGS))
                assertTrue("$label: the second GET not before 2 s (sent at +${sentAt[1] - sentAt[0]} ms, sleeps ${rig.sleeps})",
                    sentAt[1] - sentAt[0] >= 2_000)
                assertEquals("$label: the header is the delay, no backoff on top (sleeps ${rig.sleeps})", 2_000L,
                    sentAt[1] - sentAt[0])
            }
        }
    }

    // --- regression of the shared safeApiCall ----------------------------------------------------------------------------------

    private class FailureCase(
        val label: String,
        val answer: () -> MockResponse,
        val message: String,
        val kind: AlertRepositoryFailureKind,
        val status: Int?,
        val retryAfter: String?,
        val decode: Boolean
    )

    /** The pre-N1 strings of the shared safeApiCall and mapAlertHttpFailure; HTTP is the old default kind. */
    private fun regressionCases(): List<FailureCase> = listOf(
        FailureCase("429 with Retry-After 4", { answer(429, "4") }, "요청이 너무 많습니다", AlertRepositoryFailureKind.HTTP, 429, "4", false),
        FailureCase("500", { answer(500) }, "서버 오류 (500)", AlertRepositoryFailureKind.HTTP, 500, null, false),
        FailureCase("403 premium required", { answer(403).setBody(premiumRequired) }, "인증 오류", AlertRepositoryFailureKind.HTTP,
            403, null, false),
        FailureCase("malformed 200", { ok(MALFORMED) }, "서버 응답 형식 오류", AlertRepositoryFailureKind.DECODE, null, null, true),
        FailureCase("dropped connection", { dropped() }, "네트워크 오류", AlertRepositoryFailureKind.NETWORK, null, null, false)
    )

    private fun Throwable.mapsAsBefore(label: String, case: FailureCase) {
        val failure = repositoryFailure(label, case.message, case.kind)
        when {
            case.status != null -> {
                assertEquals("$label: status", case.status, failure.httpFailure?.statusCode)
                assertEquals("$label: Retry-After", case.retryAfter, failure.httpFailure?.retryAfter)
                assertNull("$label: no cause", failure.cause)
                assertNull("$label: no decode evidence", failure.responseEvidence)
            }
            case.decode -> {
                assertTrue("$label: the decoding exception is the cause", failure.cause is AuthenticatedBodyDecodingException)
                assertEquals("$label: the response is kept", 200, failure.responseEvidence?.statusCode)
                assertNull("$label: not an HTTP failure", failure.httpFailure)
            }
            else -> {
                assertNull("$label: no cause", failure.cause)
                assertNull("$label: no HTTP failure", failure.httpFailure)
                assertNull("$label: no decode evidence", failure.responseEvidence)
            }
        }
    }

    /** S7N1_L07 (regression): the bank history read over the same safeApiCall maps its answers as it did before N1. */
    @Test fun `S7N1_L07 regression getHistory maps its answers exactly as before`() = runBlocking<Unit> {
        server.enqueue(ok(historyBody()))
        assertEquals("L07 success: decoded", listOf(historyItem), repository().history().returned("L07 success").value("L07 success"))
        val request = take()
        assertEquals("L07: the history GET", "GET /api/notification-logs", "${request.method} ${request.requestUrl!!.encodedPath}")
        assertEquals("L07: currency", listOf("usd-krw"), request.requestUrl!!.queryParameterValues("currency"))
        assertEquals("L07: limit", listOf("100"), request.requestUrl!!.queryParameterValues("limit"))
        assertEquals("L07: no use check without one", listOf(false), wire.map { it.useChecked })
        for (case in regressionCases()) {
            fresh()
            val label = "L07 ${case.label}"
            server.enqueue(case.answer())
            repository().history().returned(label).failure(label).mapsAsBefore(label, case)
        }
    }

    /** S7N1_L08 (regression): a CRUD call over the same safeApiCall maps its answers as it did before N1. */
    @Test fun `S7N1_L08 regression createSetting maps its answers exactly as before`() = runBlocking<Unit> {
        server.enqueue(ok(rowA.toString()))
        assertEquals("L08 success: decoded", settingA, repository().create().returned("L08 success").value("L08 success"))
        val request = take()
        assertEquals("L08: the create POST", "POST /api/notification-settings", "${request.method} ${request.requestUrl!!.encodedPath}")
        assertEquals("L08: the captured credential", "Bearer credential", request.getHeader("Authorization"))
        assertEquals("L08: a mutation carries no use check", listOf(false), wire.map { it.useChecked })
        for (case in regressionCases()) {
            fresh()
            val label = "L08 ${case.label}"
            server.enqueue(case.answer())
            repository().create().returned(label).failure(label).mapsAsBefore(label, case)
        }
    }

    private companion object {
        const val START = 1_000_000L

        /** A bounded negative window after a settled owner: long enough for a localhost round trip, never a pass condition. */
        const val QUIET_MILLIS = 200L

        /** Not JSON on purpose, so no serializer can build it. */
        const val MALFORMED = """{ "settings": [ }"""
    }
}
