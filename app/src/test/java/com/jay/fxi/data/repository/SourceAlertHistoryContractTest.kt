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
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
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
 * Claude-owned source alert history data-layer contract (mirrors the bank history contract). Oracles: server
 * GET /api/source-notification-logs (main.py: asset filter, limit 1..200 default 100, inactive premium = empty list,
 * success rows only) with schemas SourceNotificationLogResponse (condition, threshold, triggered_rate always present) /
 * SourceNotificationLogsListResponse, and iOS a36682f SourceAlertHistoryItem / SourceNotificationSettingsService.
 * The request goes through the production protected transport and wire Json. No screen uses it yet.
 * The implementation thread reads but does not edit this file.
 */
class SourceAlertHistoryContractTest {
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
    private val fullRow = """{"id":7,"setting_id":3,"source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,
        "triggered_rate":1401.2,"sent_at":"2026-07-16T10:00:00+09:00"}"""
    private val oldRow = """{"id":6,"setting_id":null,"source":"krx","asset":"usd-krw-futures","condition":"below","threshold":1380.0,
        "triggered_rate":1379.9,"sent_at":"2026-07-01T09:30:00+09:00"}"""

    private suspend fun AlertRepository.history(asset: String? = null, limit: Int? = null): Result<List<SourceAlertHistoryItem>> {
        val owner = checkNotNull(captureOwnerOrNull())
        val r = if (limit == null) getSourceHistory(owner, asset) else getSourceHistory(owner, asset, limit)
        return r.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    @Test fun H01_defaultRequest_isAnAuthenticatedGetWithLimit100AndNoAsset() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/source-notification-logs", request.requestUrl!!.encodedPath)
        assertEquals("100", request.requestUrl!!.queryParameter("limit"))
        assertNull(request.requestUrl!!.queryParameter("asset"))
        assertNotNull("authenticated", request.getHeader("Authorization"))
    }

    @Test fun H02_assetAndLimit_areSent() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history("usd-krw-futures", 50)
        val url = server.takeRequest().requestUrl!!
        assertEquals("usd-krw-futures", url.queryParameter("asset"))
        assertEquals("50", url.queryParameter("limit"))
    }

    @Test fun H03_rowsDecodeInOrder_withKstTimestamps_andANullSettingId() = runTest {
        server.enqueue(ok("""{"logs":[$fullRow,$oldRow],"total_count":2}"""))
        assertEquals(listOf(
            SourceAlertHistoryItem(7, 3, "upbit", "usdt-krw", AlertCondition.ABOVE, 1400.5, 1401.2, Instant.parse("2026-07-16T01:00:00Z")),
            SourceAlertHistoryItem(6, null, "krx", "usd-krw-futures", AlertCondition.BELOW, 1380.0, 1379.9, Instant.parse("2026-07-01T00:30:00Z"))),
            repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H04_anInactivePremiumAnswer_isAnEmptyList() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        assertEquals(emptyList<SourceAlertHistoryItem>(), repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H05_aRowMissingAField_isRefused_withTheResponseKept() = runTest {
        val body = """{"logs":[{"id":6,"setting_id":null,"source":"krx","asset":"usd-krw-futures","condition":"below","threshold":1380.0,
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

    /** setting_id is nullable but always serialized (Optional = None), so a row without the key is refused too. */
    @Test fun H07_aRowMissingTheNullableSettingId_isRefused() = runTest {
        server.enqueue(ok("""{"logs":[${oldRow.replace("\"setting_id\":null,", "")}],"total_count":1}"""))
        val error = repository(backgroundScope).history().exceptionOrNull()
        assertTrue("refused, got $error", error is AlertRepositoryException)
    }
}
