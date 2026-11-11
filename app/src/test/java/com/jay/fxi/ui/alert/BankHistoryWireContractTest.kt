package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthRequestTag
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.premium.PremiumTopicUiState
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned S7 step (c) wire contract (`R4c/S7/s7c_api_proposal.claude.r2.md` §4 S7C_W01·W02): the production read assembly
 * [productionBankHistoryRead] under the controller, over the production client chain built as `BankHistoryUseGuardContractTest`
 * builds it (that file stays unchanged). Every exchange of a sheet keeps its currency, limit 100 and no offset; a session that
 * ends while its token or its 401 refresh is fetched sends nothing more. The implementation thread reads but does not edit
 * this file.
 */
class BankHistoryWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: GatedTokenSource
    private lateinit var provider: AuthTokenProvider
    private val clientEntries = AtomicInteger()
    private val alice = AuthIdentityFence("user-a", 1)
    private val ownerA = TopicDisplayOwner(alice, 3)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        source = GatedTokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
    }

    @After fun tearDown() = server.shutdown()

    /** "credential" first and "fresh" on a forced refresh; a gate holds one kind of fetch until the test releases it. */
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

    private fun repository(): AlertRepository {
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
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        return AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
    }

    /** Admits every use it hands out: this file is about the wire, the access is the controller's own check of the screen. */
    private class Standing : BankHistoryAccess {
        private var tokens = 0L
        override fun acquire(owner: TopicDisplayOwner): BankHistoryUse {
            val token = TopicGrantToken(++tokens)
            return BankHistoryUse(owner, TopicSessionFence(owner.identity, "epoch", token), TopicUseLifetime(token, 0))
        }
        override fun admits(use: BankHistoryUse) = true
    }

    private fun rows(vararg currencies: String) = buildJsonObject {
        putJsonArray("logs") {
            currencies.forEachIndexed { i, currency ->
                addJsonObject {
                    put("id", 100 + i); put("setting_id", 100 + i); put("bank", "hana"); put("currency", currency)
                    put("condition", "above"); put("threshold", 1400.5); put("rate", 1401.25); put("sent_at", "2026-07-16T10:0$i:00+09:00")
                }
            }
        }
        put("total_count", currencies.size)
    }.toString()

    /**
     * One controller on this thread's event loop over the production read; [ended] completes as each read returns or throws,
     * and [returned] notes how it came back to the holder. [nonCooperative] runs the read past its job's cancellation, as a
     * transport that ignores it would: its late completion then does reach the holder.
     */
    private inner class Sheet(scope: CoroutineScope, tab: FreeTab, nonCooperative: Boolean = false) {
        val shown = MutableStateFlow<Pair<TopicDisplayOwner?, FreeTab?>>(ownerA to tab)
        val ended = mutableListOf<CompletableDeferred<Unit>>()
        val returned = mutableListOf<String>()
        private val listeners = mutableListOf<(PremiumTopicScreenState) -> Unit>()
        private val production = productionBankHistoryRead(repository())
        private fun screen() = PremiumTopicScreenState(PremiumTopicUiState.NONE.copy(owner = shown.value.first, selectedTab = shown.value.second), null, null)
        val controller = BankHistoryController(ownerA, tab, BankHistoryWiring(Standing(),
            { owner, currency, guard ->
                val done = CompletableDeferred<Unit>().also { ended += it }
                try {
                    val result = if (nonCooperative) withContext(NonCancellable) { production.read(owner, currency, guard) }
                    else production.read(owner, currency, guard)
                    result.also { r -> returned += r.fold({ "success" }, { "failure" }) }
                } catch (thrown: Throwable) {
                    returned += "threw ${thrown::class.simpleName}"
                    throw thrown
                } finally { done.complete(Unit) }
            }, MutableStateFlow(0L), MutableStateFlow(0L)), { shown.value }, { l ->
                listeners += l
                l(screen())
                val unregister: () -> Unit = { listeners.remove(l) }
                unregister
            }, scope)
        val body get() = controller.holder.currentState()?.body

        /** A consumer publication of another tab: the value moves, then every listener is told. */
        fun moveTo(next: FreeTab) {
            shown.value = ownerA to next
            listeners.toList().forEach { it(screen()) }
        }

        /** A consumer publication that lost the accepted focus — no owner, no tab: the value moves, then every listener is told. */
        fun loseFocus() {
            shown.value = null to null
            listeners.toList().forEach { it(screen()) }
        }

        fun end(how: String) = when (how) {
            "close" -> controller.close()
            "tab moved" -> moveTo(FreeTab.JPY)
            "focus lost" -> loseFocus()
            else -> error(how)
        }
    }

    private suspend fun until(label: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }.also { assertTrue(label, condition()) }

    /** S7C_W01: first read, retry and refresh of each currency's sheet all ask for that currency, 100 rows, no offset. */
    @Test fun S7C_W01_everyExchangeOfASheetKeepsItsCurrency_limit100_andNoOffset() = runBlocking {
        for ((tab, currency) in listOf(FreeTab.USD to "usd-krw", FreeTab.JPY to "jpy-krw", FreeTab.EUR to "eur-krw")) {
            val before = server.requestCount
            val sheet = Sheet(this, tab)
            server.enqueue(MockResponse().setResponseCode(500))
            sheet.controller.open()
            until("$currency: first read failed") { sheet.body is BankHistoryBody.Failed }
            server.enqueue(MockResponse().setResponseCode(200).setBody(rows(currency, "usd-krw".takeIf { it != currency } ?: "eur-krw")))
            sheet.controller.holder.retry()
            until("$currency: retried") { sheet.body is BankHistoryBody.Loaded }
            val loaded = sheet.body as BankHistoryBody.Loaded
            assertEquals("$currency: only its own rows of a mixed answer", listOf(currency), loaded.items.map { it.currency })
            server.enqueue(MockResponse().setResponseCode(200).setBody(rows(currency)))
            sheet.controller.holder.refresh()
            until("$currency: refreshed") { server.requestCount == before + 3 && (sheet.body as? BankHistoryBody.Loaded)?.refreshing == false }
            repeat(3) { n ->
                val url = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "$currency: exchange $n" }.requestUrl!!
                assertEquals("$currency #$n: path", "/api/notification-logs", url.encodedPath)
                assertEquals("$currency #$n: names, no offset", setOf("currency", "limit"), url.queryParameterNames)
                assertEquals("$currency #$n: currency", listOf(currency), url.queryParameterValues("currency"))
                assertEquals("$currency #$n: limit", listOf("100"), url.queryParameterValues("limit"))
            }
            sheet.controller.close()
        }
    }

    /**
     * S7C_W02: the session ends — closed, the screen moved to another tab, or the accepted focus lost — while the first token is
     * fetched (nothing enters the client) or while the refresh a 401 earned is fetched (no replay). The read comes back to the
     * holder (positive) and nothing is shown.
     */
    @Test fun S7C_W02_aSessionEndedDuringTheTokenOrThe401RefreshSendsNothingMore() = runBlocking {
        for (forced in listOf(false, true)) for (end in listOf("close", "tab moved", "focus lost")) {
            tearDown()
            setUp()
            clientEntries.set(0)
            val label = "${if (forced) "401 refresh" else "first token"}, $end"
            if (forced) server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(200).setBody(rows("usd-krw")))
            val gate = GatedTokenSource.Gate(forced).also { source.gate.set(it) }
            val sheet = Sheet(this, FreeTab.USD)
            sheet.controller.open()
            withTimeout(5_000) { gate.started.await() }
            sheet.end(end)
            gate.release.complete(Unit)
            withTimeout(5_000) { sheet.ended.single().await() }
            assertEquals("$label: positive control, the gated fetch ran", if (forced) 1 else 0, source.forcedFetches.get())
            assertEquals("$label: exchanges", if (forced) 1 else 0, server.requestCount)
            if (!forced) assertEquals("$label: nothing entered the client", 0, clientEntries.get())
            assertEquals("$label: the read came back to the holder once", 1, sheet.returned.size)
            assertEquals("$label: nothing shown", null, sheet.body)
            sheet.controller.close()
        }
    }

    /**
     * S7C_W02 (late completion): the session ends — closed, the tab moved or the focus lost — after the request reached the
     * server and before its answer; the read ignores the cancellation, so the answer — a success or a failure — comes back to the
     * holder (positive) after the end. Nothing is shown and nothing more is sent.
     */
    @Test fun S7C_W02_aLateAnswerAfterTheSessionEndedReachesTheHolderAndShowsNothing() = runBlocking {
        for (status in listOf(200, 500)) for (end in listOf("close", "tab moved", "focus lost")) {
            tearDown()
            setUp()
            val label = "$status, $end"
            val arrived = CompletableDeferred<Unit>()
            val answer = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    arrived.complete(Unit)
                    answer.await(5, TimeUnit.SECONDS)
                    return if (status == 200) MockResponse().setResponseCode(200).setBody(rows("usd-krw"))
                    else MockResponse().setResponseCode(500)
                }
            }
            val sheet = Sheet(this, FreeTab.USD, nonCooperative = true)
            sheet.controller.open()
            withTimeout(5_000) { arrived.await() }
            assertTrue("premise($label): loading", sheet.body is BankHistoryBody.Loading)
            sheet.end(end)
            answer.countDown()
            withTimeout(5_000) { sheet.ended.single().await() }
            assertEquals("$label: the late answer reached the holder", listOf(if (status == 200) "success" else "failure"), sheet.returned)
            assertEquals("$label: nothing shown", null, sheet.body)
            assertEquals("$label: one exchange only", 1, server.requestCount)
            sheet.controller.close()
        }
    }
}
