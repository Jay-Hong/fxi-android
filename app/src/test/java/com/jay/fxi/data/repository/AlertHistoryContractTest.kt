package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned bank alert history data-layer contract (release audit: notification-logs had no Android client).
 * Oracles: server GET /api/notification-logs (exchange-rate main.py get_notification_logs: currency filter, limit 1..200
 * default 100, inactive premium = empty list, success rows only) with schemas NotificationLogResponse /
 * NotificationLogsListResponse, and iOS a36682f AlertHistoryItem / NotificationSettingsService.fetchHistory.
 * The request goes through the production protected transport and wire Json. No screen uses it yet.
 * The implementation thread reads but does not edit this file.
 */
class AlertHistoryContractTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private fun repository(scope: CoroutineScope): AlertRepository {
        val source = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("user-a", 1)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "credential"
        }
        val provider = AuthTokenProvider(source, scope, AccessOrderSequence())
        val client = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor()).build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        return AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
    }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private val fullRow = """{"id":7,"setting_id":3,"bank":"hana","currency":"usd-krw","condition":"above","threshold":1400.5,
        "rate":1401.2,"sent_at":"2026-07-16T10:00:00+09:00"}"""
    private val oldRow = """{"id":6,"setting_id":null,"bank":"kb","currency":"jpy-krw","condition":null,"threshold":null,
        "rate":930.1,"sent_at":"2026-07-01T09:30:00+09:00"}"""

    private suspend fun AlertRepository.history(currency: String? = null, limit: Int? = null): Result<List<AlertHistoryItem>> {
        val owner = checkNotNull(captureOwnerOrNull())
        val r = if (limit == null) getHistory(owner, currency) else getHistory(owner, currency, limit)
        return r.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    @Test fun H01_defaultRequest_isAnAuthenticatedGetWithLimit100AndNoCurrency() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/notification-logs", request.requestUrl!!.encodedPath)
        assertEquals("100", request.requestUrl!!.queryParameter("limit"))
        assertNull(request.requestUrl!!.queryParameter("currency"))
        assertNotNull("authenticated", request.getHeader("Authorization"))
    }

    @Test fun H02_currencyAndLimit_areSent() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history("usd-krw", 50)
        val url = server.takeRequest().requestUrl!!
        assertEquals("usd-krw", url.queryParameter("currency"))
        assertEquals("50", url.queryParameter("limit"))
    }

    @Test fun H03_fullAndOldRows_decodeInOrder_withKstTimestamps() = runTest {
        server.enqueue(ok("""{"logs":[$fullRow,$oldRow],"total_count":2}"""))
        assertEquals(listOf(
            AlertHistoryItem(7, 3, "hana", "usd-krw", AlertCondition.ABOVE, 1400.5, 1401.2, Instant.parse("2026-07-16T01:00:00Z")),
            AlertHistoryItem(6, null, "kb", "jpy-krw", null, null, 930.1, Instant.parse("2026-07-01T00:30:00Z"))),
            repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H04_anInactivePremiumAnswer_isAnEmptyList() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        assertEquals(emptyList<AlertHistoryItem>(), repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H05_aRowMissingAField_isRefused_withTheResponseKept() = runTest {
        val body = """{"logs":[{"id":6,"setting_id":null,"bank":"kb","currency":"jpy-krw","threshold":null,"rate":930.1,
            "sent_at":"2026-07-01T09:30:00+09:00"}],"total_count":1}"""
        server.enqueue(ok(body))
        val error = repository(backgroundScope).history().exceptionOrNull()
        assertTrue("refused, got $error", error is AlertRepositoryException)
        assertEquals(200, (error as AlertRepositoryException).responseEvidence?.statusCode)
    }

    @Test fun H06_premiumRequiredIsKnownAuthorization_andAPendingAnswerIsAnotherHttpFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"pending"}"""))
        val repo = repository(backgroundScope)
        for (expected in listOf(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, AuthenticatedFailureKind.OTHER_HTTP)) {
            val error = repo.history().exceptionOrNull()
            assertTrue("$expected: repository failure, got $error", error is AlertRepositoryException)
            assertEquals("$expected", expected, (error as AlertRepositoryException).httpFailure?.kind)
        }
        if (server.requestCount != 2) fail("one request per call, got ${server.requestCount}")
    }

    /** The nullable fields are always serialized (Optional = None), so a row missing any of those keys is refused too. */
    @Test fun H07_aRowMissingANullableKey_isRefused() = runTest {
        for (key in listOf("\"setting_id\":null,", "\"condition\":null,", "\"threshold\":null,")) {
            val row = oldRow.replace(key, "")
            check(row != oldRow) { "fixture edit for $key did not apply" }
            server.enqueue(ok("""{"logs":[$row],"total_count":1}"""))
            val error = repository(backgroundScope).history().exceptionOrNull()
            assertTrue("$key: refused, got $error", error is AlertRepositoryException)
        }
    }
}
