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
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SourceAlertSetting
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
 * Claude-owned source alert CRUD data-layer contract (release audit: Android had no source alert client; the server
 * and iOS support tether-exchange and KRX price alerts). Oracles: server main.py /api/source-notification-settings
 * (GET asset filter, inactive premium = empty list; POST; PUT partial with repeat 3-state, 404 "Setting not found";
 * DELETE idempotent 200) with schemas SourceNotificationSetting*, builder build_source_notification_setting_response,
 * and iOS a36682f SourceAlertSetting / SourceNotificationSettingsService. Through the production protected transport
 * and wire Json. History (source-notification-logs) and screens come later. The implementation reads but does not edit.
 */
class SourceAlertContractTest {
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

    private val onceRow = """{"id":11,"user_id":"user-a","source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,
        "is_enabled":true,"triggered":false,"repeat_interval_sec":null,"created_at":"2026-09-01T09:00:00+09:00",
        "updated_at":"2026-09-02T09:00:00+09:00","triggered_at":null}"""
    private val repeatRow = """{"id":12,"user_id":"user-a","source":"krx","asset":"usd-krw-futures","condition":"below","threshold":1380.0,
        "is_enabled":false,"triggered":true,"repeat_interval_sec":300,"created_at":"2026-09-03T09:00:00+09:00",
        "updated_at":null,"triggered_at":"2026-09-04T10:30:00+09:00"}"""
    private val once = SourceAlertSetting(11, "user-a", "upbit", "usdt-krw", AlertCondition.ABOVE, 1400.5, true, false, null,
        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), null)

    @Test fun S01_listIsAnAuthenticatedGet_withAnOptionalAssetFilter_andDecodesEveryField() = runTest {
        server.enqueue(ok("""{"settings":[$onceRow,$repeatRow],"total_count":2}"""))
        server.enqueue(ok("""{"settings":[],"total_count":0}"""))
        val repo = repository(backgroundScope)
        assertEquals(listOf(once, SourceAlertSetting(12, "user-a", "krx", "usd-krw-futures", AlertCondition.BELOW, 1380.0, false, true, 300,
            Instant.parse("2026-09-03T00:00:00Z"), null, Instant.parse("2026-09-04T01:30:00Z"))), repo.run { getSourceSettings(it) }.getOrThrow())
        assertEquals(emptyList<SourceAlertSetting>(), repo.run { getSourceSettings(it, "usd-krw-futures") }.getOrThrow())
        val all = server.takeRequest(); val filtered = server.takeRequest()
        assertEquals("GET /api/source-notification-settings", "${all.method} ${all.requestUrl!!.encodedPath}")
        assertNull(all.requestUrl!!.queryParameter("asset"))
        assertEquals("usd-krw-futures", filtered.requestUrl!!.queryParameter("asset"))
        for (r in listOf(all, filtered)) assertEquals("Bearer credential", r.getHeader("Authorization"))
    }

    @Test fun S02_createPostsTheFields_omittingRepeatForOnce() = runTest {
        server.enqueue(ok(onceRow)); server.enqueue(ok(repeatRow))
        val repo = repository(backgroundScope)
        assertEquals(once, repo.run { createSourceSetting(it, SourceAlertSettingRequest("upbit", "usdt-krw", AlertCondition.ABOVE, 1400.5, true)) }.getOrThrow())
        repo.run { createSourceSetting(it, SourceAlertSettingRequest("krx", "usd-krw-futures", AlertCondition.BELOW, 1380.0, false, 300)) }.getOrThrow()
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("POST /api/source-notification-settings", "${first.method} ${first.requestUrl!!.encodedPath}")
        assertEquals(JsonObject(mapOf("source" to JsonPrimitive("upbit"), "asset" to JsonPrimitive("usdt-krw"), "condition" to JsonPrimitive("above"),
            "threshold" to JsonPrimitive(1400.5), "is_enabled" to JsonPrimitive(true))), first.bodyJson())
        assertEquals(JsonPrimitive(300), second.bodyJson()["repeat_interval_sec"])
    }

    @Test fun S03_updatePutsOnlyTheChangedFields_withTheRepeatThreeStates() = runTest {
        repeat(3) { server.enqueue(ok(onceRow)) }
        val repo = repository(backgroundScope)
        repo.run { updateSourceSetting(it, 11, SourceAlertSettingUpdateRequest(isEnabled = false)) }.getOrThrow()
        repo.run { updateSourceSetting(it, 11, SourceAlertSettingUpdateRequest(threshold = 1410.0, repeatIntervalSec = RepeatIntervalChange.Once.toJson())) }.getOrThrow()
        repo.run { updateSourceSetting(it, 11, SourceAlertSettingUpdateRequest(repeatIntervalSec = RepeatIntervalChange.Every(RepeatInterval.ONE_HOUR).toJson())) }.getOrThrow()
        val (a, b, c) = List(3) { server.takeRequest() }
        assertEquals("PUT /api/source-notification-settings/11", "${a.method} ${a.requestUrl!!.encodedPath}")
        assertEquals(JsonObject(mapOf("is_enabled" to JsonPrimitive(false))), a.bodyJson())
        assertEquals(JsonObject(mapOf("threshold" to JsonPrimitive(1410.0), "repeat_interval_sec" to JsonNull)), b.bodyJson())
        assertEquals(JsonObject(mapOf("repeat_interval_sec" to JsonPrimitive(3600))), c.bodyJson())
    }

    @Test fun S04_deleteIsIdempotent_bothServerAnswersSucceed() = runTest {
        server.enqueue(ok("""{"success":true,"message":"Setting deleted"}"""))
        server.enqueue(ok("""{"success":true,"message":"Setting already deleted"}"""))
        val repo = repository(backgroundScope)
        repeat(2) { assertEquals(Unit, repo.run { deleteSourceSetting(it, 12) }.getOrThrow()) }
        val r = server.takeRequest()
        assertEquals("DELETE /api/source-notification-settings/12", "${r.method} ${r.requestUrl!!.encodedPath}")
    }

    @Test fun S05_anUpdateOfAMissingSettingIsKnownNotFound_andPremiumRequiredIsKnownAuthorization() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Setting not found"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        val repo = repository(backgroundScope)
        val missing = repo.run { updateSourceSetting(it, 99, SourceAlertSettingUpdateRequest(isEnabled = true)) }.exceptionOrNull()
        assertTrue("known not found, got $missing", missing is AlertNotFoundException)
        val refused = repo.run { createSourceSetting(it, SourceAlertSettingRequest("upbit", "usdt-krw", AlertCondition.ABOVE, 1400.5, true)) }.exceptionOrNull()
        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, (refused as? AlertRepositoryException)?.httpFailure?.kind)
    }

    @Test fun S06_aSettingMissingAField_isRefused_withTheResponseKept() = runTest {
        server.enqueue(ok("""{"settings":[${onceRow.replace("\"repeat_interval_sec\":null,", "")}],"total_count":1}"""))
        val error = repository(backgroundScope).run { getSourceSettings(it) }.exceptionOrNull()
        assertTrue("refused, got $error", error is AlertRepositoryException)
        assertEquals(200, (error as AlertRepositoryException).responseEvidence?.statusCode)
    }

    /** A refused or failed delete is a failure, never a silent success (delete requires premium; allow_empty=False). */
    @Test fun S07_aRefusedOrFailedDelete_isAFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val repo = repository(backgroundScope)
        val refused = repo.run { deleteSourceSetting(it, 12) }.exceptionOrNull()
        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, (refused as? AlertRepositoryException)?.httpFailure?.kind)
        val failed = repo.run { deleteSourceSetting(it, 12) }.exceptionOrNull()
        assertEquals(500, (failed as? AlertRepositoryException)?.httpFailure?.statusCode)
    }
}
