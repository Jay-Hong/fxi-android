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
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.di.NetworkModule
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
 * Claude-owned comparison alert history data-layer contract (mirrors the source history contract). Oracles: server
 * GET /api/comparison-notification-logs (main.py: tab and diff_type filters, limit 1..200 default 100, inactive premium =
 * empty list, success rows only; every field set, setting_id / left_observed_at / right_observed_at nullable) with schemas
 * ComparisonNotificationLog*, and iOS a36682f ComparisonAlertHistoryItem / ComparisonAlertService.
 * Through the production protected transport and wire Json. No screen uses it yet. The implementation reads but does not edit.
 */
class ComparisonAlertHistoryContractTest {
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
    private val fullRow = """{"id":7,"setting_id":3,"tab":"tether","left_source":"upbit","left_asset":"usdt-krw","right_source":"investing",
        "right_asset":"usd-krw","diff_type":"signed","operator":"gte","threshold":-15.5,"left_rate":1390.0,"right_rate":1401.5,"spread":-11.5,
        "left_observed_at":"2026-07-16T09:59:58+09:00","right_observed_at":"2026-07-16T09:59:50+09:00","is_repeat":true,
        "sent_at":"2026-07-16T10:00:00+09:00"}"""
    private val oldRow = """{"id":6,"setting_id":null,"tab":"usd","left_source":"hana","left_asset":"usd-krw","right_source":"kb",
        "right_asset":"usd-krw","diff_type":"absolute","operator":"lte","threshold":3.0,"left_rate":1400.0,"right_rate":1398.0,"spread":2.0,
        "left_observed_at":null,"right_observed_at":null,"is_repeat":false,"sent_at":"2026-07-01T09:30:00+09:00"}"""

    private suspend fun AlertRepository.history(tab: String? = null, diffType: String? = null, limit: Int? = null): Result<List<ComparisonAlertHistoryItem>> {
        val owner = checkNotNull(captureOwnerOrNull())
        val r = if (limit == null) getComparisonHistory(owner, tab, diffType) else getComparisonHistory(owner, tab, diffType, limit)
        return r.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    @Test fun H01_defaultRequest_isAnAuthenticatedGetWithLimit100AndNoFilter() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/comparison-notification-logs", request.requestUrl!!.encodedPath)
        assertEquals("100", request.requestUrl!!.queryParameter("limit"))
        assertNull(request.requestUrl!!.queryParameter("tab"))
        assertNull(request.requestUrl!!.queryParameter("diff_type"))
        assertNotNull("authenticated", request.getHeader("Authorization"))
    }

    @Test fun H02_tabDiffTypeAndLimit_areSent() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        repository(backgroundScope).history("tether", "signed", 50)
        val url = server.takeRequest().requestUrl!!
        assertEquals("tether" to "signed", url.queryParameter("tab") to url.queryParameter("diff_type"))
        assertEquals("50", url.queryParameter("limit"))
    }

    @Test fun H03_rowsDecodeInOrder_withKstTimestamps_andNullSettingIdAndObservedTimes() = runTest {
        server.enqueue(ok("""{"logs":[$fullRow,$oldRow],"total_count":2}"""))
        assertEquals(listOf(
            ComparisonAlertHistoryItem(7, 3, "tether", "upbit", "usdt-krw", "investing", "usd-krw", "signed", "gte", -15.5, 1390.0, 1401.5, -11.5,
                Instant.parse("2026-07-16T00:59:58Z"), Instant.parse("2026-07-16T00:59:50Z"), true, Instant.parse("2026-07-16T01:00:00Z")),
            ComparisonAlertHistoryItem(6, null, "usd", "hana", "usd-krw", "kb", "usd-krw", "absolute", "lte", 3.0, 1400.0, 1398.0, 2.0,
                null, null, false, Instant.parse("2026-07-01T00:30:00Z"))),
            repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H04_anInactivePremiumAnswer_isAnEmptyList() = runTest {
        server.enqueue(ok("""{"logs":[],"total_count":0}"""))
        assertEquals(emptyList<ComparisonAlertHistoryItem>(), repository(backgroundScope).history().getOrThrow())
    }

    @Test fun H05_aRowMissingAField_isRefused_withTheResponseKept() = runTest {
        val body = """{"logs":[${oldRow.replace("\"spread\":2.0,", "")}],"total_count":1}"""
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

    /** setting_id and the observed times are nullable but always serialized, so a row without any of those keys is refused. */
    @Test fun H07_aRowMissingANullableKey_isRefused() = runTest {
        for (key in listOf("\"setting_id\":null,", "\n        \"left_observed_at\":null,", "\"right_observed_at\":null,")) {
            val row = oldRow.replace(key, "")
            check(row != oldRow) { "fixture edit for $key did not apply" }
            server.enqueue(ok("""{"logs":[$row],"total_count":1}"""))
            val error = repository(backgroundScope).history().exceptionOrNull()
            assertTrue("$key: refused, got $error", error is AlertRepositoryException)
        }
    }
}
