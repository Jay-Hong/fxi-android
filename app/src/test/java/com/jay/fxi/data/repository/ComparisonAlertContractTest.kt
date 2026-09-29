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
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.remote.dto.ComparisonAlertRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertUpdateRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned comparison alert CRUD data-layer contract (ADR-037; release audit: Android had no comparison/kimchi alert
 * client). Oracles: server main.py /api/comparison-alerts (GET tab filter, inactive premium = empty list; POST; PUT
 * enabled/repeat/threshold/operator with repeat 3-state, 404 "Comparison alert not found"; DELETE idempotent 200) with
 * schemas ComparisonAlert*, builder build_comparison_alert_response (every field set), and iOS a36682f
 * ComparisonAlertSetting / ComparisonAlertService. Through the production protected transport and wire Json.
 * History (comparison-notification-logs) and screens come later. The implementation reads but does not edit this file.
 */
class ComparisonAlertContractTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private val json = NetworkModule.provideWireJson()

    private fun repository(scope: CoroutineScope): AlertRepository {
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("user-a", 1)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "credential"
        }, scope, AccessOrderSequence())
        val client = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor()).build()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        return AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), json))
    }
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun RecordedRequest.bodyJson(): JsonObject = json.parseToJsonElement(body.readUtf8()).jsonObject
    private suspend fun <T> AlertRepository.run(block: suspend AlertRepository.(com.jay.fxi.data.auth.AuthIdentityFence) -> AuthBoundResult<T>): Result<T> =
        block(checkNotNull(captureOwnerOrNull())).fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })

    private val kimchiRow = """{"id":21,"user_id":"user-a","tab":"tether","left_source":"upbit","left_asset":"usdt-krw",
        "right_source":"investing","right_asset":"usd-krw","diff_type":"signed","operator":"gte","threshold":-15.5,
        "is_enabled":true,"triggered":false,"repeat_interval_sec":null,"last_notified_spread":null,
        "created_at":"2026-09-01T09:00:00+09:00","updated_at":null}"""
    private val compareRow = """{"id":22,"user_id":"user-a","tab":"usd","left_source":"hana","left_asset":"usd-krw",
        "right_source":"kb","right_asset":"usd-krw","diff_type":"absolute","operator":"lte","threshold":3.0,
        "is_enabled":false,"triggered":true,"repeat_interval_sec":600,"last_notified_spread":2.5,
        "created_at":"2026-09-02T09:00:00+09:00","updated_at":"2026-09-03T09:00:00+09:00"}"""
    private val kimchi = ComparisonAlertSetting(21, "user-a", "tether", "upbit", "usdt-krw", "investing", "usd-krw", "signed", "gte", -15.5,
        true, false, null, null, Instant.parse("2026-09-01T00:00:00Z"), null)
    private val kimchiRequest = ComparisonAlertRequest("tether", "upbit", "usdt-krw", "investing", "usd-krw", "signed", "gte", -15.5, true)

    @Test fun C01_listIsAnAuthenticatedGet_withAnOptionalTabFilter_andDecodesEveryField() = runTest {
        server.enqueue(ok("""{"alerts":[$kimchiRow,$compareRow],"total_count":2}"""))
        server.enqueue(ok("""{"alerts":[],"total_count":0}"""))
        val repo = repository(backgroundScope)
        assertEquals(listOf(kimchi, ComparisonAlertSetting(22, "user-a", "usd", "hana", "usd-krw", "kb", "usd-krw", "absolute", "lte", 3.0,
            false, true, 600, 2.5, Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z"))),
            repo.run { getComparisonAlerts(it) }.getOrThrow())
        assertEquals(emptyList<ComparisonAlertSetting>(), repo.run { getComparisonAlerts(it, "usd") }.getOrThrow())
        val all = server.takeRequest(); val filtered = server.takeRequest()
        assertEquals("GET /api/comparison-alerts", "${all.method} ${all.requestUrl!!.encodedPath}")
        assertNull(all.requestUrl!!.queryParameter("tab"))
        assertEquals("usd", filtered.requestUrl!!.queryParameter("tab"))
        for (r in listOf(all, filtered)) assertEquals("Bearer credential", r.getHeader("Authorization"))
    }

    @Test fun C02_createPostsTheFields_omittingRepeatForOnce() = runTest {
        server.enqueue(ok(kimchiRow)); server.enqueue(ok(compareRow))
        val repo = repository(backgroundScope)
        assertEquals(kimchi, repo.run { createComparisonAlert(it, kimchiRequest) }.getOrThrow())
        repo.run { createComparisonAlert(it, ComparisonAlertRequest("usd", "hana", "usd-krw", "kb", "usd-krw", "absolute", "lte", 3.0, false, 600)) }.getOrThrow()
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("POST /api/comparison-alerts", "${first.method} ${first.requestUrl!!.encodedPath}")
        assertEquals(JsonObject(mapOf("tab" to JsonPrimitive("tether"), "left_source" to JsonPrimitive("upbit"), "left_asset" to JsonPrimitive("usdt-krw"),
            "right_source" to JsonPrimitive("investing"), "right_asset" to JsonPrimitive("usd-krw"), "diff_type" to JsonPrimitive("signed"),
            "operator" to JsonPrimitive("gte"), "threshold" to JsonPrimitive(-15.5), "is_enabled" to JsonPrimitive(true))), first.bodyJson())
        assertEquals(JsonPrimitive(600), second.bodyJson()["repeat_interval_sec"])
    }

    @Test fun C03_updatePutsOnlyTheChangedFields_withTheRepeatThreeStates() = runTest {
        repeat(3) { server.enqueue(ok(kimchiRow)) }
        val repo = repository(backgroundScope)
        repo.run { updateComparisonAlert(it, 21, ComparisonAlertUpdateRequest(isEnabled = false)) }.getOrThrow()
        repo.run { updateComparisonAlert(it, 21, ComparisonAlertUpdateRequest(threshold = -20.0, operator = "lte", repeatIntervalSec = RepeatIntervalChange.Once.toJson())) }.getOrThrow()
        repo.run { updateComparisonAlert(it, 21, ComparisonAlertUpdateRequest(repeatIntervalSec = RepeatIntervalChange.Every(RepeatInterval.ONE_DAY).toJson())) }.getOrThrow()
        val (a, b, c) = List(3) { server.takeRequest() }
        assertEquals("PUT /api/comparison-alerts/21", "${a.method} ${a.requestUrl!!.encodedPath}")
        assertEquals(JsonObject(mapOf("is_enabled" to JsonPrimitive(false))), a.bodyJson())
        assertEquals(JsonObject(mapOf("repeat_interval_sec" to JsonNull, "threshold" to JsonPrimitive(-20.0), "operator" to JsonPrimitive("lte"))), b.bodyJson())
        assertEquals(JsonObject(mapOf("repeat_interval_sec" to JsonPrimitive(86400))), c.bodyJson())
    }

    @Test fun C04_deleteIsIdempotent_bothServerAnswersSucceed() = runTest {
        server.enqueue(ok("""{"success":true,"message":"Comparison alert deleted"}"""))
        server.enqueue(ok("""{"success":true,"message":"Comparison alert already deleted"}"""))
        val repo = repository(backgroundScope)
        repeat(2) { assertEquals(Unit, repo.run { deleteComparisonAlert(it, 22) }.getOrThrow()) }
        val r = server.takeRequest()
        assertEquals("DELETE /api/comparison-alerts/22", "${r.method} ${r.requestUrl!!.encodedPath}")
    }

    @Test fun C05_anUpdateOfAMissingAlertIsKnownNotFound_andPremiumRequiredIsKnownAuthorization() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Comparison alert not found"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Setting not found"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        val repo = repository(backgroundScope)
        val missing = repo.run { updateComparisonAlert(it, 99, ComparisonAlertUpdateRequest(isEnabled = true)) }.exceptionOrNull()
        assertTrue("known not found, got $missing", missing is AlertNotFoundException)
        val otherText = repo.run { updateComparisonAlert(it, 99, ComparisonAlertUpdateRequest(isEnabled = true)) }.exceptionOrNull()
        assertEquals("another 404 text is not this endpoint's known answer", AuthenticatedFailureKind.UNKNOWN_NOT_FOUND,
            (otherText as? AlertRepositoryException)?.httpFailure?.kind)
        val refused = repo.run { createComparisonAlert(it, kimchiRequest) }.exceptionOrNull()
        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, (refused as? AlertRepositoryException)?.httpFailure?.kind)
    }

    @Test fun C06_anAlertMissingAKey_isRefused_includingTheAlwaysSentNullableOnes() = runTest {
        for (key in listOf(",\"repeat_interval_sec\":null", ",\"last_notified_spread\":null", ",\"updated_at\":null", ",\"operator\":\"gte\"")) {
            val row = kimchiRow.replace(key, "")
            check(row != kimchiRow) { "fixture edit for $key did not apply" }
            server.enqueue(ok("""{"alerts":[$row],"total_count":1}"""))
            val error = repository(backgroundScope).run { getComparisonAlerts(it) }.exceptionOrNull()
            assertTrue("$key: refused, got $error", error is AlertRepositoryException)
            assertEquals("$key", 200, (error as AlertRepositoryException).responseEvidence?.statusCode)
        }
    }

    @Test fun C07_aRefusedOrFailedDelete_isAFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val repo = repository(backgroundScope)
        val refused = repo.run { deleteComparisonAlert(it, 22) }.exceptionOrNull()
        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, (refused as? AlertRepositoryException)?.httpFailure?.kind)
        val failed = repo.run { deleteComparisonAlert(it, 22) }.exceptionOrNull()
        assertEquals(500, (failed as? AlertRepositoryException)?.httpFailure?.statusCode)
    }
}
