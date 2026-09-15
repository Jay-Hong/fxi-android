package com.jay.fxi.service

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.di.NetworkModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import retrofit2.Retrofit

/**
 * The production adapter over the real protected client, with a fake Firebase source. The last
 * tests run it under the real coordinator: the credential's wait has to come before admission.
 */
class ApiPushDeviceServerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val owner = AuthIdentityFence("user-a", 1)
    private val source = Source()
    private var admitted = true
    private lateinit var web: MockWebServer
    private lateinit var server: ApiPushDeviceServer

    private class Source : AuthTokenSource {
        @Volatile var identity: AuthIdentity? = AuthIdentity("user-a", 1)
        @Volatile var fetching: CompletableDeferred<Unit>? = null
        @Volatile var release: CompletableDeferred<Unit>? = null
        @Volatile var atFetch: () -> Unit = {}
        @Volatile var fetches = 0

        override fun currentIdentity(): AuthIdentity? = identity

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches += 1
            fetching?.complete(Unit)
            release?.await()
            atFetch()
            return "id-token"
        }
    }

    /** The production transitions over memory. */
    private class Ledger : PushRegistrationLedger {
        var state = PushLedger.EMPTY
        var afterRecordMayExist: () -> Unit = {}

        override suspend fun entries() = state.entries
        override suspend fun recordMayExist(uid: String, token: String) =
            apply(PushLedgerTransitions.recordMayExist(state, uid, token)).also { afterRecordMayExist() }
        override suspend fun markOwed(uid: String) = apply(PushLedgerTransitions.markOwed(state, uid))
        override suspend fun recordOwed(uid: String, token: String) =
            apply(PushLedgerTransitions.recordOwed(state, uid, token))
        override suspend fun complete(entry: PushLedgerEntry) = apply(PushLedgerTransitions.complete(state, entry))

        private fun <T> apply(result: PushLedgerTransitions.Result<T>): T {
            state = result.ledger
            return result.value
        }
    }

    @Before
    fun setUp() {
        web = MockWebServer()
        web.start()
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(web.url("/"))
            .client(http)
            .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType()))
            .build()
        val api = AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { admitted }),
            NetworkModule.provideWireJson()
        )
        server = ApiPushDeviceServer(api)
    }

    @After
    fun tearDown() {
        web.shutdown()
    }

    private fun deleteAnswering(response: MockResponse): DeleteResult = runBlocking {
        web.enqueue(response)
        checkNotNull(server.session(owner)).unregister("t1")
    }

    private fun notFound(body: String) = MockResponse().setResponseCode(404).setBody(body)

    @Test
    fun aDeletedRowIsDeleted() {
        assertEquals(DeleteResult.DELETED, deleteAnswering(MockResponse().setResponseCode(200).setBody("""{"success":true}""")))

        val request = web.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", request.method)
        assertEquals("/api/register-device?device_token=t1", request.path)
    }

    @Test
    fun onlyTheEndpointsOwnNotFoundIsAbsence() {
        assertEquals(DeleteResult.ABSENT_FOR_UID, deleteAnswering(notFound("""{"detail":"Device not found"}""")))

        listOf("""{"detail":"Not Found"}""", "<html>404</html>", "", """{"error":"x","detail":"Device not found"}""")
            .forEach { body ->
                assertEquals("404 '$body' 를 부재로 봤다", DeleteResult.FAILED, deleteAnswering(notFound(body)))
            }
    }

    @Test
    fun otherStatusesAreFailures() {
        listOf(401, 403, 500, 503).forEach { code ->
            assertEquals("$code", DeleteResult.FAILED, deleteAnswering(MockResponse().setResponseCode(code).setBody("{}")))
        }
    }

    @Test
    fun aTransportFailureIsAFailure() {
        assertEquals(
            DeleteResult.FAILED,
            deleteAnswering(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        )
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFalse(runBlocking { checkNotNull(server.session(owner)).register("t1") })
    }

    @Test
    fun aRegistrationIsAcceptedOnlyOnSuccess() {
        web.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        assertTrue(runBlocking { checkNotNull(server.session(owner)).register("t1") })
        web.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertFalse(runBlocking { checkNotNull(server.session(owner)).register("t1") })

        val request = web.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"device_token\":\"t1\"") && body.contains("\"platform\":\"android\""))
    }

    /** Moved on while the request was out: the answer, a known 404 included, is refused, never read. */
    @Test
    fun anIdentityChangeIsNeverAnAnswerEvenAsAKnownNotFound() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                source.identity = AuthIdentity("user-a", 2)
                return notFound("""{"detail":"Device not found"}""")
            }
        }
        val session = runBlocking { checkNotNull(server.session(owner)) }

        assertThrows(AuthIdentityChangedException::class.java) { runBlocking { session.unregister("t1") } }
    }

    /** The provider fetches in its own scope; only a check before asking keeps a cancelled call from starting one. */
    @Test
    fun anAlreadyCancelledCallStartsNoCredentialFetch() = runBlocking {
        val call = launch {
            coroutineContext[Job]!!.cancel()
            server.session(owner)
        }
        call.join()

        assertEquals(0, source.fetches)
        assertEquals(0, web.requestCount)
    }

    @Test
    fun aClosedReleaseGetsNoCredentialAndSendsNothing() {
        admitted = false

        assertNull(runBlocking { server.session(owner) })
        assertEquals(0, web.requestCount)
    }

    private fun coordinator(ledger: PushRegistrationLedger) = PushRegistrationCoordinator(
        ledger = ledger,
        server = server,
        currentFence = { owner },
        eligible = { true },
        deviceToken = { "t1" },
        clearLegacyLocalState = {}
    )

    /** Were the adapter to fetch its credential inside the POST, admission would come before that wait. */
    @Test
    fun aSignOutWhileTheCredentialIsFetchedSendsNoPost() = runBlocking {
        val ledger = Ledger()
        val coordinator = coordinator(ledger)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("{}")
        }
        source.fetching = CompletableDeferred()
        source.release = CompletableDeferred()
        val registration = async { coordinator.register(owner) }
        source.fetching!!.await()
        val signOut = launch { coordinator.unregister(owner) }
        kotlinx.coroutines.yield()
        source.fetching = null

        source.release!!.complete(Unit)
        assertEquals(RegisterOutcome.HELD, registration.await())
        signOut.join()

        val methods = (0 until web.requestCount).map { web.takeRequest(5, TimeUnit.SECONDS)!!.method }
        assertFalse("hold 뒤에 POST 가 나갔다: $methods", "POST" in methods)
    }

    @Test
    fun aCallerCancelledAsItsRecordReturnsStartsNoCredentialFetch() = runBlocking {
        val ledger = Ledger()
        val coordinator = coordinator(ledger)
        lateinit var registration: Job
        ledger.afterRecordMayExist = { registration.cancel() }

        registration = launch { coordinator.register(owner) }
        registration.join()

        assertEquals(0, source.fetches)
        assertEquals(0, web.requestCount)
    }

    @Test
    fun aCallerCancelledAsItsCredentialArrivesSendsNothing() = runBlocking {
        val coordinator = coordinator(Ledger())
        lateinit var registration: Job
        source.atFetch = { registration.cancel() }

        registration = launch { coordinator.register(owner) }
        registration.join()

        assertTrue(registration.isCancelled)
        assertEquals(0, web.requestCount)
    }
}
