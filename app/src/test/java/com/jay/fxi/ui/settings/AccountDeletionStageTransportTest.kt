package com.jay.fxi.ui.settings

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionPhase
import com.jay.fxi.data.entitlements.DeletionAdmissionRecord
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.util.UUID

/**
 * S4 CUT-CC3 D09: the account-deletion stage over the real authenticated transport. When the session moves while the
 * deletion request is on the wire and the server answers 204, the transport refuses the answer as a moved session — and the
 * stage still records that operation's deletion, applies nothing, and lets the refusal through.
 */
class AccountDeletionStageTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var source: FakeAuthTokenSource
    private lateinit var api: AuthenticatedApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType()))
            .build()
        api = AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aDeletionAnsweredAfterTheUidMoved_isRecordedAndNotApplied() = runTest {
        assertLateNoContentIsRecorded(moveTo = AuthIdentity("user-b", 2))
    }

    @Test
    fun aDeletionAnsweredAfterTheSameUidSignedInAgain_isRecordedAndNotApplied() = runTest {
        assertLateNoContentIsRecorded(moveTo = AuthIdentity("user-a", 2))
    }

    private suspend fun assertLateNoContentIsRecorded(moveTo: AuthIdentity) {
        val owner = AuthIdentityFence("user-a", 1)
        val deletions = DeletionAdmissionStore()
        val atServer = mutableListOf<List<DeletionAdmissionRecord>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                atServer += deletions.records
                // The session moves while the request is on the wire; the server has already deleted.
                source.identity = moveTo
                return MockResponse().setResponseCode(204)
            }
        }
        val stage = AccountDeletionServerStage(api, deletions)
        var applied = false

        val failure = runCatching { stage.execute(owner) { applied = true } }.exceptionOrNull()

        assertEquals("CC3-D09 fixture: the request reached the server", 1, server.requestCount)
        assertTrue("CC3-D09 the transport's refusal reaches the caller", failure is AuthIdentityChangedException)
        assertEquals("CC3-D09 fixture: it carried the 204", 204, (failure as AuthIdentityChangedException).statusCode)
        assertFalse("CC3-D09 nothing applied", applied)
        val record = deletions.records.single()
        assertEquals("CC3-D09 the original owner", owner, record.owner)
        assertEquals("CC3-D09 its operation is server-deleted", DeletionAdmissionPhase.SERVER_DELETED, record.phase)
        assertTrue("CC3-D09 fixture: a fresh operation id", record.operationId.isNotBlank())
        assertEquals("CC3-D02 already requesting when the request reached the server",
            listOf(listOf(DeletionAdmissionRecord(owner, record.operationId, DeletionAdmissionPhase.REQUESTING_SERVER))), atServer)
    }

    /**
     * CC3-D07 (production ids): the screen's stage draws a fresh UUID v4 per request — across requests of one stage and of a
     * second stage on the same store — so a 204 advances only its own request.
     */
    @Test
    fun eachProductionRequestDrawsAFreshUuid_andA204AdvancesOnlyItsOwn() = runTest {
        val statuses = ArrayDeque(listOf(500, 500, 204))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(statuses.removeFirst())
        }
        val owner = AuthIdentityFence("user-a", 1)
        val deletions = DeletionAdmissionStore()
        val first = AccountDeletionServerStage(api, deletions)
        first.execute(owner) { Unit }
        first.execute(owner) { Unit }
        AccountDeletionServerStage(api, deletions).execute(owner) { Unit }

        assertEquals("CC3-D07 fixture: three requests", 3, server.requestCount)
        val ids = deletions.records.map { it.operationId }
        assertEquals("CC3-D07 three distinct ids", 3, ids.toSet().size)
        for (id in ids) {
            val parsed = UUID.fromString(id)
            assertEquals("CC3-D07 $id is a UUID v4", 4, parsed.version())
            assertEquals("CC3-D07 $id in canonical form", id, parsed.toString())
        }
        assertEquals(
            "CC3-D07 only the 204 advanced",
            listOf(DeletionAdmissionPhase.REQUESTING_SERVER, DeletionAdmissionPhase.REQUESTING_SERVER, DeletionAdmissionPhase.SERVER_DELETED),
            deletions.records.map { it.phase }
        )
    }

    private class FakeAuthTokenSource : AuthTokenSource {
        /** Written on the server's thread, read on the client's. */
        @Volatile var identity = AuthIdentity("user-a", 1)

        override fun currentIdentity(): AuthIdentity = identity

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "token"
    }
}
