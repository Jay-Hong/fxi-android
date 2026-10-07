package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.dto.GraphV2AxisGroup
import com.jay.fxi.data.remote.dto.GraphV2CarryIn
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned Graph V2 wire contract r2 (r1 declared the endpoints public; Codex REJECT: they are premium reads) (release audit: Graph V2 had no Android client; the premium graph still called v1).
 * Oracles: server app/graph_v2.py (period tab, carry_in always sent, domain attached at serve time, per_point_metadata
 * fields only when listed), app/graph_v2_intraday.py build_tab_1d_payload (1d: high/low per point, in_progress, no
 * carry_in, internal "_in_progress_start_ts"), main.py get_v2_graph_catalog/get_v2_graph_tab (Firebase + premium
 * via _resolve_graph_v2_krx_visible; 403 "Premium subscription required", 503 pending; serve-time domain on every
 * period), iOS a36682f GraphV2Models.swift and GraphV2Service.swift (authenticated transport). Decoded with the
 * production wire Json through the protected client. No screen uses it yet.
 */
class GraphV2WireContractTest {
    private val json = NetworkModule.provideWireJson()
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private fun tab(body: String) = json.decodeFromString(GraphV2TabResponse.serializer(), body)

    private val catalog = """{"version":"2026-05-27","supported_periods":["1w","3m","1y"],"cache_ttl_seconds":3600,
        "tabs":[{"id":"usd","label":"달러","axis_groups":{"krw":{"unit":"KRW","decimals":2,"side":"left"},
        "index":{"unit":"INDEX","decimals":2,"side":"right"}},"periods":{"3m":{"all_series":["investing.usd-krw","dxy"],
        "default_visible_series":["investing.usd-krw"]}}}]}"""
    private val periodTab = """{"tab":"usd","period":"3m","series":[{"id":"hana.usd-krw","label":"하나","axis_group":"krw",
        "unit":"KRW","decimals":2,"data":[{"ts":"2026-09-01T00:00:00+09:00","rate":1390.5,"source":"hana","high":1395.0,
        "low":1385.25,"close_basis":"hana_observed_eod","source_method":"observed_rollup"},
        {"ts":"2026-09-02T00:00:00+09:00","rate":1391.0,"source":"hana"}],
        "provenance":{"insufficient_history":false,"per_point_metadata":["close_basis","source_method"],"close_basis":"mixed"},
        "carry_in":{"rate":1389.0,"observed_at":"2026-06-30T00:00:00+09:00"}},
        {"id":"krx.usd-krw-futures","label":"KRX 미국달러선물","axis_group":"krw","unit":"KRW","decimals":1,
        "data":[{"ts":"2026-09-01T00:00:00+09:00","rate":1391.2,"source":"krx","contract_code":"A75609"}],
        "provenance":{"insufficient_history":true,"per_point_metadata":["contract_code"]},"carry_in":null}],
        "metadata":{"fetched_at":"2026-09-29T10:00:00+09:00","bucket_size":"1d","range":{"start":"2026-06-29","end":"2026-09-29"},
        "domain_start_at":"2026-06-29T00:00:00+09:00","domain_end_at":"2026-09-29T10:00:00+09:00","live_domain_mode":"fixed_start"}}"""
    private val oneDayTab = """{"tab":"tether","period":"1d","_in_progress_start_ts":1790000000,"series":[{"id":"bithumb.usdt-krw",
        "label":"빗썸","axis_group":"krw","unit":"KRW","decimals":0,"data":[{"ts":"2026-09-29T09:40:00+09:00","rate":1402.0,
        "source":"bithumb","high":1403.0,"low":1401.0}],"provenance":{"insufficient_history":false,"per_point_metadata":[]}}],
        "metadata":{"fetched_at":"2026-09-29T10:01:00+09:00","bucket_size":"10min","range":{"start":"2026-09-28","end":"2026-09-29"},
        "domain_start_at":"2026-09-28T10:01:00+09:00","domain_end_at":"2026-09-29T10:01:00+09:00","live_domain_mode":"rolling"},
        "in_progress":{"bithumb.usdt-krw":{"bucket_start":"2026-09-29T10:00:00+09:00","high":1404.0,"low":1402.0,"close":1403.0,
        "sampled_at":"2026-09-29T10:00:45+09:00"}}}"""

    @Test fun G01_theCatalogDecodes() {
        val c = json.decodeFromString(GraphV2CatalogResponse.serializer(), catalog)
        assertEquals("2026-05-27", c.version)
        assertEquals(listOf("1w", "3m", "1y"), c.supportedPeriods)
        assertEquals(3600, c.cacheTtlSeconds)
        val usd = c.tabs.single()
        assertEquals("usd" to "달러", usd.id to usd.label)
        assertEquals(mapOf("krw" to GraphV2AxisGroup("KRW", 2, "left"), "index" to GraphV2AxisGroup("INDEX", 2, "right")), usd.axisGroups)
        assertEquals(mapOf("3m" to GraphV2CatalogPeriod(listOf("investing.usd-krw", "dxy"), listOf("investing.usd-krw"))), usd.periods)
    }

    @Test fun G02_aPeriodTabDecodes_withPerPointFields_carryIn_andTheServeTimeDomain() {
        val t = tab(periodTab)
        assertEquals("usd" to "3m", t.tab to t.period)
        assertNull("no in_progress outside 1d", t.inProgress)
        val (hana, krx) = t.series
        assertEquals(listOf(
            GraphV2Point(Instant.parse("2026-08-31T15:00:00Z"), 1390.5, "hana", 1395.0, 1385.25, "hana_observed_eod", "observed_rollup", null),
            GraphV2Point(Instant.parse("2026-09-01T15:00:00Z"), 1391.0, "hana")), hana.data)
        // Absent optional fields are null by the wire, not by whatever default the DTO happens to carry.
        for (p in listOf(hana.data[1], krx.data.single())) assertEquals("absent optionals of $p", listOf(null, null),
            listOf(p.high, p.low))
        assertEquals(listOf(null, null, null), hana.data[1].let { listOf(it.closeBasis, it.sourceMethod, it.contractCode) })
        assertEquals(listOf(null, null), krx.data.single().let { listOf(it.closeBasis, it.sourceMethod) })
        assertEquals(GraphV2Provenance(false, listOf("close_basis", "source_method")), hana.provenance)
        assertEquals(GraphV2CarryIn(1389.0, Instant.parse("2026-06-29T15:00:00Z")), hana.carryIn)
        assertEquals(listOf("hana.usd-krw", "krw", "KRW", 2), listOf(hana.id, hana.axisGroup, hana.unit, hana.decimals))
        assertEquals("A75609", krx.data.single().contractCode)
        assertEquals(true, krx.provenance.insufficientHistory)
        assertNull("carry_in null", krx.carryIn)
        val m = t.metadata
        assertEquals(Instant.parse("2026-09-29T01:00:00Z"), m.fetchedAt)
        assertEquals("1d" to ("2026-06-29" to "2026-09-29"), m.bucketSize to (m.range.start to m.range.end))
        assertEquals(Instant.parse("2026-06-28T15:00:00Z"), m.domainStartAt)
        assertEquals(Instant.parse("2026-09-29T01:00:00Z"), m.domainEndAt)
        assertEquals("fixed_start", m.liveDomainMode)
    }

    @Test fun G03_aOneDayTabDecodes_withBands_theInProgressSeed_aRollingDomain_andNoCarryIn() {
        val t = tab(oneDayTab)
        val s = t.series.single()
        assertEquals(GraphV2Point(Instant.parse("2026-09-29T00:40:00Z"), 1402.0, "bithumb", 1403.0, 1401.0), s.data.single())
        assertEquals(listOf(null, null, null), s.data.single().let { listOf(it.closeBasis, it.sourceMethod, it.contractCode) })
        assertNull(s.carryIn)
        assertEquals("10min", t.metadata.bucketSize)
        assertEquals(Instant.parse("2026-09-28T01:01:00Z"), t.metadata.domainStartAt)
        assertEquals(Instant.parse("2026-09-29T01:01:00Z"), t.metadata.domainEndAt)
        assertEquals("rolling", t.metadata.liveDomainMode)
        assertEquals(mapOf("bithumb.usdt-krw" to GraphV2InProgressSeed(Instant.parse("2026-09-29T01:00:00Z"), 1404.0, 1402.0, 1403.0,
            Instant.parse("2026-09-29T01:00:45Z"))), t.inProgress)
    }

    @Test fun G04_alwaysSentFieldsAreRequired() {
        for ((name, broken) in listOf(
            "series unit" to periodTab.replace("\"unit\":\"KRW\",\"decimals\":2,\"data\"", "\"decimals\":2,\"data\""),
            "point source" to oneDayTab.replace("\"source\":\"bithumb\",", ""),
            "provenance per_point_metadata" to oneDayTab.replace(",\"per_point_metadata\":[]", ""),
            "metadata bucket_size" to oneDayTab.replace("\"bucket_size\":\"10min\",", ""),
        )) {
            check(broken != periodTab && broken != oneDayTab) { "fixture edit for $name did not apply" }
            assertThrows(name, SerializationException::class.java) { tab(broken) }
        }
        assertThrows("catalog version", SerializationException::class.java) {
            json.decodeFromString(GraphV2CatalogResponse.serializer(), catalog.replace("\"version\":\"2026-05-27\",", ""))
        }
    }

    /** An older server without the serve-time domain still decodes; the client then derives its own frame. */
    @Test fun G06_aResponseWithoutTheDomain_decodesWithNullDomainFields() {
        val old = oneDayTab.replace(""",
        "domain_start_at":"2026-09-28T10:01:00+09:00","domain_end_at":"2026-09-29T10:01:00+09:00","live_domain_mode":"rolling"}""", "}")
        check(old != oneDayTab) { "fixture edit did not apply" }
        val m = tab(old).metadata
        assertEquals(listOf(null, null, null), listOf(m.domainStartAt, m.domainEndAt, m.liveDomainMode))
    }

    private fun client(): AuthenticatedApiClient {
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("user-a", 1)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "credential"
        }, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider)).addInterceptor(MutationOneShotInterceptor()).build()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        return AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java), AuthenticatedTransport(provider, admitted = { true }), json)
    }

    @Test fun G05_theEndpointsAreAuthenticatedGets_premiumRefusalIsKnown_pendingIsAnotherHttpFailure() = runTest {
        server.enqueue(MockResponse().setBody(catalog)); server.enqueue(MockResponse().setBody(periodTab))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Premium subscription required"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Subscription status pending. Retry later."}"""))
        val c = client()
        assertEquals("2026-05-27", c.getGraphV2Catalog(c.captureSnapshot()) { true }.requireBody("catalog").version)
        assertEquals("usd", c.getGraphV2Tab(c.captureSnapshot(), "usd", "1y") { true }.requireBody("tab").tab)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("GET /api/v2/graph/catalog", "${first.method} ${first.requestUrl!!.encodedPath}")
        assertEquals("GET /api/v2/graph/tab", "${second.method} ${second.requestUrl!!.encodedPath}")
        assertEquals("usd" to "1y", second.requestUrl!!.queryParameter("tab") to second.requestUrl!!.queryParameter("period"))
        for (r in listOf(first, second)) assertEquals("Bearer credential", r.getHeader("Authorization"))
        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, c.getGraphV2Tab(c.captureSnapshot(), "usd", "1w") { true }.failure?.kind)
        assertEquals(AuthenticatedFailureKind.OTHER_HTTP, c.getGraphV2Catalog(c.captureSnapshot()) { true }.failure?.kind)
    }
}
