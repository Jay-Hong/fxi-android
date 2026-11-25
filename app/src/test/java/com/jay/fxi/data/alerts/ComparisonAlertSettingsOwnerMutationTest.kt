package com.jay.fxi.data.alerts

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.preserve
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.jay.fxi.data.remote.TopicUseAuthority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonAlertSettingsOwnerMutationTest {
    private val id = AuthIdentityFence("user-a", 1)
    private val wireJson = NetworkModule.provideWireJson()
    private val signed = ComparisonAlertPair("tether", "signed", "upbit" to "usdt-krw", "kb" to "usd-krw")
    private val fx = ComparisonAlertPair("usd", "absolute", "kb" to "usd-krw", "hana" to "usd-krw")
    private val changed = signed.copy(right = "hana" to "usd-krw")
    private val seed = wireJson.decodeFromString<ComparisonAlertSetting>(
        requireNotNull(javaClass.getResource("/contracts/v2/alerts/comparison-setting-response.json")).readText())
    private fun draft(pair: ComparisonAlertPair = signed) = ComparisonAlertDraft(pair, "lte", -5.0, null)
        .let { if (pair.diffType == "absolute") it.copy(threshold = 5.0) else it }
    private fun row(number: Int, pair: ComparisonAlertPair = signed) = seed.copy(id = number, userId = id.uid,
        tab = pair.tab, diffType = pair.diffType, leftSource = pair.left.first, leftAsset = pair.left.second,
        rightSource = pair.right.first, rightAsset = pair.right.second, operator = "lte",
        threshold = draft(pair).threshold, isEnabled = true, triggered = false)
    private fun listing(rows: List<ComparisonAlertSetting>) =
        """{"alerts":${wireJson.encodeToString(rows)},"total_count":999}"""

    private val collection = "/api/comparison-alerts"
    private class Gate(val key: String, val response: MockResponse) {
        val entered = AtomicBoolean()
        val arrived = AtomicBoolean()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var beforeRepository: () -> Unit = {}
        var lateRow: ComparisonAlertSetting? = null
        var lateRows: List<ComparisonAlertSetting>? = null
        var lateFailure: Throwable? = null
        val folds = AtomicInteger()
        var use: AlertSettingsUse? = null
        var guard: (() -> Boolean)? = null
    }

    private inner class Rig(val test: TestScope) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val gates = CopyOnWriteArrayList<Gate>()
        val entries = mutableListOf<String>()
        val pool = Executors.newCachedThreadPool()
        val server = MockWebServer()
        val forced = AtomicInteger()
        val captures = AtomicInteger()
        val sleeps = mutableListOf<Long>()
        @Volatile var live: AuthIdentityFence? = id
        val premium = MutableStateFlow(OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed))
        val revisions = MutableStateFlow(0L)
        val deletions = DeletionAdmissionStore()
        val grant = TopicGrantToken(11)
        @Volatile var snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()))
        @Volatile var graph: TopicSessionFence? = TopicSessionFence(id, "epoch", grant)
        @Volatile var atToken: () -> Unit = {}
        @Volatile var beforeSend: () -> Unit = {}
        val acquisitions = AtomicInteger()
        private val authority = SnapshotTopicUseAuthority { snapshot }
        private val counted = object : TopicUseAuthority by authority {
            override fun acquire(fence: TopicSessionFence) = authority.acquire(fence).also { acquisitions.incrementAndGet() }
        }
        val access = AlertSettingsAccess({ live }, { graph }, counted, { premium.value },
            { true }, GraphProtectedAdmission({ live }, deletions))
        val owner: ComparisonAlertSettingsOwner
        val client: OkHttpClient
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = "${request.method} ${request.path}"
                    val gate = gates.firstOrNull { it.key == key && it.arrived.compareAndSet(false, true) }
                        ?: return MockResponse().setResponseCode(400)
                    gate.release.await()
                    return gate.response
                }
            }
            server.start()
            val tokens = AuthTokenProvider(object : AuthTokenSource {
                override fun currentIdentity() = live?.let { AuthIdentity(it.uid, it.authGeneration) }
                override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
                    captures.incrementAndGet()
                    if (forceRefresh) forced.incrementAndGet()
                    atToken()
                    return if (forceRefresh) "refreshed" else "credential"
                }
            }, orders = AccessOrderSequence())
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .addInterceptor(AuthSnapshotInterceptor(tokens)).addInterceptor(MutationOneShotInterceptor())
                .addNetworkInterceptor { chain -> beforeSend(); chain.proceed(chain.request()) }
                .addNetworkInterceptor(TopicUseNetworkInterceptor(tokens)).build()
            val service = Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java)
            val repository = AlertRepository(AuthenticatedApiClient(service, AuthenticatedTransport(tokens, admitted = { true }), wireJson))
            val read = comparisonSettingsGet(repository)
            val post = comparisonCreate(repository)
            val put = comparisonUpdate(repository)
            val delete = comparisonDelete(repository)
            owner = ComparisonAlertSettingsOwner({ use, guard ->
                call("GET $collection", use, guard, { gate ->
                    gate.lateRows?.let { lateBound(gate, it) }
                }) { read(use, guard) }
            }, access, { live?.takeUnless { deletions.blocks(it.uid) } }, premium, revisions, scope,
                { ms -> sleeps += ms; delay(ms) }, { 0.0 },
                post = { use, request, guard -> call("POST $collection", use, guard) { post(use, request, guard) } },
                put = { use, n, request, guard -> call("PUT $collection/$n", use, guard,
                    { gate -> gate.lateRow?.let { lateBound(gate, it) } }) { put(use, n, request, guard) } },
                remove = { use, n, guard -> call("DELETE $collection/$n", use, guard) { delete(use, n, guard) } })
        }

        private fun <T> lateBound(gate: Gate, value: T) = AuthBoundResult({ gate.folds.incrementAndGet() },
            gate.lateFailure?.let { Result.failure<T>(it) } ?: Result.success(value))

        private suspend fun <T> call(key: String, use: AlertSettingsUse, guard: () -> Boolean,
            late: (Gate) -> AuthBoundResult<T>? = { null }, send: suspend () -> AuthBoundResult<T>): AuthBoundResult<T> {
            entries += key
            val gate = gates.firstOrNull { it.key == key && it.entered.compareAndSet(false, true) }
            gate?.use = use
            gate?.guard = guard
            val result = CompletableDeferred<Result<AuthBoundResult<T>>>()
            pool.execute {
                val answer = runCatching { gate?.beforeRepository?.invoke(); runBlocking { send() } }
                result.complete(answer)
                gate?.finished?.countDown()
            }
            val answer = withContext(NonCancellable) { result.await() }
            return gate?.let(late) ?: answer.getOrThrow()
        }

        fun plan(method: String, n: Int? = null, code: Int = 200, body: String = "",
            retry: String? = null, disconnect: Boolean = false): Gate {
            val response = MockResponse().setResponseCode(code)
            if (body.isNotEmpty()) response.setBody(body)
            if (retry != null) response.addHeader("Retry-After", retry)
            if (disconnect) response.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            return Gate("$method $collection" + (n?.let { "/$it" } ?: ""), response).also { gates += it }
        }
        fun post(row: ComparisonAlertSetting) = plan("POST", body = wireJson.encodeToString(row))
        fun put(row: ComparisonAlertSetting, n: Int = row.id) = plan("PUT", n, body = wireJson.encodeToString(row))
        fun get(rows: List<ComparisonAlertSetting>) = plan("GET", body = listing(rows))
        fun tick() = test.runCurrent()
        fun advance(ms: Long) { test.advanceTimeBy(ms); tick() }
        fun reached(gate: Gate, expected: String? = null) {
            tick()
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull("expected ${gate.key}, entries=$entries", request)
            request!!
            assertEquals(gate.key, "${request.method} ${request.requestUrl?.encodedPath}")
            assertNull(request.requestUrl?.query)
            assertEquals("Bearer credential", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            if (expected != null) assertEquals(wireJson.parseToJsonElement(expected), runCatching { wireJson.parseToJsonElement(body) }.getOrNull())
            else if (request.method in setOf("GET", "DELETE")) assertEquals("", body)
        }
        fun settle(gate: Gate) {
            gate.release.countDown()
            assertTrue("delegate completion ${gate.key}", gate.finished.await(3, TimeUnit.SECONDS))
            tick()
        }
        fun quiet() {
            tick()
            val count = server.requestCount
            assertNull("unexpected exchange after $entries", server.takeRequest(75, TimeUnit.MILLISECONDS))
            assertEquals(count, server.requestCount)
        }
        val rows get() = owner.state.value.adopted?.rows.orEmpty()
        fun ids(vararg expected: Int) = assertEquals(expected.toList(), rows.map { it.id })
        fun start(rows: List<ComparisonAlertSetting>) {
            val gate = get(rows)
            owner.onForeground(false)
            reached(gate)
            settle(gate)
            assertEquals(ComparisonAlertRowPolicy.adopt(id.uid, rows).rows, this.rows)
        }
        override fun close() {
            gates.forEach { it.release.countDown() }
            scope.cancel()
            client.dispatcher.cancelAll()
            pool.shutdown()
            pool.awaitTermination(4, TimeUnit.SECONDS)
            tick()
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun TestScope.rig(vararg rows: ComparisonAlertSetting, block: Rig.() -> Unit) {
        Rig(this).use { r -> r.start(rows.toList()); r.block() }
    }
    private val ComparisonAlertHandle.value get() = outcome.value
    private fun failed(h: ComparisonAlertHandle, kind: AlertRepositoryFailureKind, code: Int? = null) {
        assertTrue("expected failure, got ${h.value}", h.value is ComparisonAlertOutcome.Failed)
        val failure = (h.value as ComparisonAlertOutcome.Failed).failure
        assertTrue("expected repository evidence, got $failure", failure is AlertRepositoryException)
        assertEquals(kind, (failure as AlertRepositoryException).failureKind)
        assertEquals(code, failure.httpFailure?.statusCode)
    }
    private fun rejected(h: ComparisonAlertHandle, reason: ComparisonAlertNotSentReason) =
        assertEquals(ComparisonAlertOutcome.NotSent(reason), h.value)
    private fun body(pair: ComparisonAlertPair, threshold: Int, repeat: Int? = null): String {
        val interval = repeat?.let { ""","repeat_interval_sec":$it""" }.orEmpty()
        return """{"tab":"${pair.tab}","diff_type":"${pair.diffType}","left_source":"${pair.left.first}","left_asset":"${pair.left.second}","right_source":"${pair.right.first}","right_asset":"${pair.right.second}","operator":"lte","threshold":$threshold.0,"is_enabled":true$interval}"""
    }

    @Test fun W01_createAndDeduplicate() = runTest {
        rig {
            val first = row(41)
            val gate = post(first)
            val handle = owner.add(id, draft())
            reached(gate, body(signed, -5)); settle(gate)
            assertEquals(ComparisonAlertOutcome.Saved(first), handle.value)
            val second = row(83, fx).copy(repeatIntervalSec = 600)
            val reversed = fx.copy(left = fx.right, right = fx.left)
            val next = post(second)
            val saved = owner.add(id, draft(reversed).copy(repeat = RepeatInterval.TEN_MINUTES))
            reached(next, body(reversed, 5, 600)); settle(next)
            assertEquals(ComparisonAlertOutcome.Saved(second), saved.value)
            ids(83, 41); quiet()
        }
        rig(row(10), row(41).copy(isEnabled = false), row(12)) {
            val returned = row(41).copy(triggered = true)
            val gate = post(returned)
            val h = owner.add(id, draft())
            reached(gate); settle(gate)
            assertEquals(ComparisonAlertOutcome.Saved(returned), h.value)
            ids(10, 41, 12)
            assertEquals(returned, rows[1]); quiet()
        }
    }

    @Test fun W02_editAndToggle() = runTest {
        for (pair in listOf(fx, signed)) rig(row(41, pair).copy(triggered = true, isEnabled = false)) {
            val selected = if (pair == fx) fx.copy(left = fx.right, right = fx.left) else pair
            for (interval in listOf(RepeatInterval.TEN_MINUTES, null)) {
                val updated = row(41, pair).copy(operator = "gte", threshold = 9.0, repeatIntervalSec = interval?.seconds)
                val gate = put(updated)
                val h = owner.edit(id, 41, pair, draft(selected).copy(operator = "gte", threshold = 9.0, repeat = interval))
                reached(gate, """{"is_enabled":true,"operator":"gte","threshold":9.0,"repeat_interval_sec":${interval?.seconds}}""")
                settle(gate); assertEquals(ComparisonAlertOutcome.Saved(updated), h.value)
                assertEquals(listOf(updated), rows)
            }
            for (enabled in listOf(true, false)) {
                val updated = rows.single().copy(isEnabled = enabled)
                val gate = put(updated)
                val h = owner.setEnabled(id, 41, enabled)
                assertEquals(listOf(updated), rows)
                reached(gate, """{"is_enabled":$enabled}"""); settle(gate)
                assertEquals(ComparisonAlertOutcome.Saved(updated), h.value)
            }
            for ((detail, kind) in listOf("Comparison alert not found" to AuthenticatedFailureKind.KNOWN_NOT_FOUND,
                "Not Found" to AuthenticatedFailureKind.UNKNOWN_NOT_FOUND)) {
                val saved = rows.toList()
                val gate = plan("PUT", 41, 404, """{"detail":"$detail"}""")
                val h = owner.setEnabled(id, 41, true)
                reached(gate); settle(gate)
                failed(h, AlertRepositoryFailureKind.HTTP, 404)
                assertEquals(kind, ((h.value as ComparisonAlertOutcome.Failed).failure as AlertRepositoryException).httpFailure?.kind)
                assertEquals(saved, rows)
            }
            quiet()
        }
    }

    @Test fun W03_delete() = runTest {
        for ((code, text) in listOf(200 to "deleted", 200 to "already deleted", 204 to "",
            404 to "Comparison alert not found", 404 to "Not Found", 500 to "error", 0 to "")) rig(row(40), row(41), row(42)) {
            val payload = when {
                code == 200 -> """{"success":true,"message":"Comparison alert $text"}"""
                text.isEmpty() -> ""
                else -> """{"detail":"$text"}"""
            }
            if (code == 404) {
                val response = retrofit2.Response.error<okhttp3.ResponseBody>(404, payload.toResponseBody())
                assertEquals(AuthenticatedFailureKind.UNKNOWN_NOT_FOUND, response.preserve(AuthenticatedEndpoint.DELETE_COMPARISON_ALERT).failure?.kind)
            }
            val gate = plan("DELETE", 41, if (code == 0) 200 else code, payload, disconnect = code == 0)
            val h = owner.delete(id, 41)
            ids(40, 42); reached(gate); settle(gate)
            if (code in 200..404) {
                assertEquals(ComparisonAlertOutcome.Deleted(41), h.value); ids(40, 42)
            } else {
                failed(h, if (code == 0) AlertRepositoryFailureKind.NETWORK else AlertRepositoryFailureKind.HTTP, code.takeIf { it > 0 })
                ids(40, 41, 42)
            }
            quiet()
        }
    }

    @Test fun W04_replace() = runTest {
        for ((origin, target) in listOf(signed to changed, fx to fx.copy(left = "woori" to "usd-krw")))
            for (status in listOf(200, 404)) rig(row(41, origin)) {
                val fresh = row(83, target)
                val create = post(fresh)
                val remove = plan("DELETE", 41, status)
                val h = owner.edit(id, 41, origin, draft(target))
                reached(create, body(target, if (origin == fx) 5 else -5)); ids(41); quiet()
                settle(create); ids(83, 41); reached(remove)
                assertSame(create.use, remove.use)
                assertEquals(2, acquisitions.get())
                assertEquals(ComparisonAlertOutcome.Pending, h.value)
                settle(remove); ids(83); assertEquals(ComparisonAlertOutcome.Saved(fresh), h.value); quiet()
            }
    }

    @Test fun W05_partialFailure() = runTest {
        for (code in listOf(403, 404, 0)) rig(row(41)) {
            val gate = plan("POST", code = if (code == 0) 200 else code, disconnect = code == 0)
            val h = owner.edit(id, 41, signed, draft(changed))
            reached(gate); settle(gate)
            failed(h, if (code == 0) AlertRepositoryFailureKind.NETWORK else AlertRepositoryFailureKind.HTTP, code.takeIf { it > 0 })
            ids(41); quiet()
        }
        for (manual in listOf(false, true)) for (code in listOf(500, 429, 0)) rig(row(41)) {
            val fresh = row(83, changed)
            val create = post(fresh)
            val remove = plan("DELETE", 41, if (code == 0) 200 else code, retry = "60", disconnect = code == 0)
            val h = owner.edit(id, 41, signed, draft(changed))
            reached(create); settle(create); reached(remove); settle(remove); ids(83, 41)
            assertTrue("old deletion failed: ${h.value}", h.value is ComparisonAlertOutcome.OldNotDeleted)
            val outcome = h.value as ComparisonAlertOutcome.OldNotDeleted
            assertEquals(41, outcome.oldId); assertEquals(fresh, outcome.newRow)
            val failure = outcome.failure as? AlertRepositoryException
            assertEquals(if (code == 0) AlertRepositoryFailureKind.NETWORK else AlertRepositoryFailureKind.HTTP, failure?.failureKind)
            assertEquals(code.takeIf { it > 0 }, failure?.httpFailure?.statusCode)
            if (manual) {
                val next = plan("DELETE", 41)
                val retry = owner.delete(id, 41)
                reached(next); settle(next); ids(83); assertEquals(ComparisonAlertOutcome.Deleted(41), retry.value)
            } else advance(61_000)
            quiet(); assertEquals(if (manual) 4 else 3, server.requestCount)
        }
    }

    @Test fun W06_validateInputAndResponse() = runTest {
        rig(row(41), row(42, fx)) {
            for (pair in listOf(signed.copy(tab = "usd"), signed.copy(diffType = "other"), signed.copy(left = signed.right),
                signed.copy(right = signed.left), signed.copy(left = signed.right, right = signed.left),
                signed.copy(right = "kb" to "eur-krw"), signed.copy(left = "other" to "usdt-krw"),
                signed.copy(right = "other" to "usd-krw"), fx.copy(diffType = "signed"))) {
                rejected(owner.add(id, draft(pair)), ComparisonAlertNotSentReason.UNSUPPORTED)
            }
            for (d in listOf(draft().copy(operator = "="), draft().copy(threshold = Double.NaN),
                draft().copy(threshold = Double.POSITIVE_INFINITY), draft(fx).copy(threshold = -1.0))) {
                rejected(owner.add(id, d), ComparisonAlertNotSentReason.UNSUPPORTED)
            }
            rejected(owner.edit(id, 41, signed, draft(fx)), ComparisonAlertNotSentReason.UNSUPPORTED)
            assertEquals(0L, owner.writeRevision); quiet()
            val valid = row(83, changed)
            for (response in listOf(valid.copy(userId = "b"), valid.copy(id = 41), valid.copy(id = 42),
                valid.copy(rightSource = "kb"), valid.copy(leftSource = "coinone"), valid.copy(diffType = "absolute"),
                valid.copy(operator = "gte"), valid.copy(threshold = -4.0), null)) {
                val gate = plan("POST", body = response?.let { wireJson.encodeToString(it) } ?: "{}")
                val h = owner.edit(id, 41, signed, draft(changed))
                reached(gate); settle(gate); failed(h, AlertRepositoryFailureKind.DECODE)
                assertEquals(listOf(row(41), row(42, fx)), rows); quiet()
            }
            val wrongId = put(row(42), 41)
            val h = owner.setEnabled(id, 41, false)
            reached(wrongId); settle(wrongId); failed(h, AlertRepositoryFailureKind.DECODE)
            assertEquals(listOf(row(41), row(42, fx)), rows); quiet()
        }
        rig(row(41), row(42)) {
            val wrongId = put(row(42), 41)
            val h = owner.setEnabled(id, 41, false)
            reached(wrongId); settle(wrongId); failed(h, AlertRepositoryFailureKind.DECODE)
            assertEquals(listOf(row(41), row(42)), rows); quiet()
        }
    }
    @Test fun W07_staleReadHandoff() = runTest {
        for (code in listOf(200, 503, 403)) for (early in listOf(false, true)) for (extra in listOf(false, true))
            rig(row(41)) {
                val old = plan("GET", code = code, body = listing(listOf(row(13))))
                old.lateRows = listOf(row(13))
                if (code != 200) old.lateFailure = java.io.IOException("late failure")
                owner.manual(); reached(old)
                val update = put(row(41))
                val h = owner.setEnabled(id, 41, true)
                assertEquals(1L, owner.writeRevision)
                reached(update)
                if (extra) { owner.onSync(); owner.manual() }
                val count = owner.publications
                if (early) { settle(old); assertEquals(count, owner.publications) }
                quiet()
                val latest = get(listOf(row(91)))
                settle(update); assertEquals(ComparisonAlertOutcome.Saved(row(41)), h.value)
                reached(latest); settle(latest); ids(91)
                val published = owner.publications
                if (!early) settle(old)
                assertEquals(published, owner.publications); ids(91)
                assertEquals(0, old.folds.get()); assertTrue(sleeps.isEmpty()); quiet()
            }
        rig(row(41)) {
            val old = get(listOf(row(13)))
            owner.manual(); reached(old)
            val update = put(row(41))
            val h = owner.setEnabled(id, 41, true)
            reached(update)
            val next = get(listOf(row(82)))
            scope.launch {
                h.outcome.first { it != ComparisonAlertOutcome.Pending }
                assertFalse("fresh GET has not entered its delegate", next.entered.get())
                owner.manual()
            }
            tick(); settle(update); reached(next)
            owner.manual()
            val trailing = get(listOf(row(91)))
            settle(next); reached(trailing); settle(trailing); settle(old); ids(91); quiet()
        }
    }

    @Test fun W08_forceWaitsForLastWrite() = runTest {
        for (initial in listOf("none", "dirty", "retry")) rig(row(41), row(42)) {
            var stale: Gate? = null
            if (initial != "none") {
                val g = plan("GET", code = if (initial == "retry") 503 else 200, body = listing(listOf(row(14))))
                owner.manual(); reached(g)
                if (initial == "dirty") { owner.manual(); stale = g } else {
                    settle(g); assertEquals(1, sleeps.size)
                }
            }
            val post = post(row(83, changed))
            val del = plan("DELETE", 41)
            val put = plan("PUT", 42, 500)
            val edit = owner.edit(id, 41, signed, draft(changed))
            val toggle = owner.setEnabled(id, 42, false)
            fun demands() { owner.onSync(); owner.manual(); owner.onForeground(true); owner.ensure(); quiet() }
            reached(post); demands(); advance(61_000); quiet()
            settle(post); reached(del); demands(); assertFalse(put.entered.get())
            settle(del); assertEquals(ComparisonAlertOutcome.Saved(row(83, changed)), edit.value)
            reached(put); demands()
            val fresh = get(listOf(row(80)))
            settle(put); failed(toggle, AlertRepositoryFailureKind.HTTP, 500)
            reached(fresh); settle(fresh); ids(80)
            stale?.let { settle(it) }; ids(80); quiet()
        }
        for (retry in listOf(false, true)) rig(row(41)) {
            val prior = plan("GET", code = if (retry) 503 else 200, body = listing(listOf(row(13))))
            owner.onSync(); reached(prior)
            if (retry) { settle(prior); assertEquals(1, sleeps.size) } else owner.manual()
            val put = put(row(41))
            owner.setEnabled(id, 41, true)
            reached(put); owner.ensure(); advance(61_000); quiet()
            val fresh = get(listOf(row(91)))
            settle(put); reached(fresh); settle(fresh)
            if (!retry) settle(prior)
            ids(91); quiet()
        }
        rig(row(41), row(42)) {
            val first = put(row(41))
            val second = put(row(42))
            owner.setEnabled(id, 41, true); owner.setEnabled(id, 42, true)
            reached(first); owner.onSync(); quiet()
            settle(first); reached(second); quiet()
            val fresh = get(listOf(row(93)))
            settle(second); reached(fresh); settle(fresh); ids(93); quiet()
        }
    }

    private fun population(size: Int): Array<ComparisonAlertSetting> {
        val pairs = listOf(signed, fx, signed.copy(diffType = "absolute", right = "korbit" to "usdt-krw"),
            fx.copy(tab = "jpy", left = "kb" to "jpy-krw", right = "hana" to "jpy-krw"),
            fx.copy(tab = "eur", left = "kb" to "eur-krw", right = "hana" to "eur-krw"))
        return ((1..size).map { row(it, pairs[(it - 1) % 5]).copy(isEnabled = it % 2 == 0, triggered = it % 2 > 0) } +
            listOf(row(61).copy(userId = "other"), row(62).copy(rightSource = "bad"), row(63), row(63))).toTypedArray()
    }

    @Test fun W09_capacity() = runTest {
        for (sameTick in listOf(false, true)) for (code in listOf(200, 400, 403, 0, 201, 202)) rig(*population(29)) {
            val responseId = if (code == 202) 1 else 72
            val gate = plan("POST", code = if (code == 0) 200 else code,
                body = if (code == 201) "{}" else wireJson.encodeToString(row(responseId)), disconnect = code == 0)
            val h = owner.add(id, draft())
            if (!sameTick) reached(gate)
            rejected(owner.add(id, draft()), ComparisonAlertNotSentReason.CAP)
            if (sameTick) reached(gate)
            settle(gate)
            if (code == 200) {
                assertEquals(30, rows.size); rejected(owner.add(id, draft()), ComparisonAlertNotSentReason.CAP)
            } else {
                when (code) {
                    202 -> assertEquals(ComparisonAlertOutcome.Saved(row(1)), h.value)
                    201 -> failed(h, AlertRepositoryFailureKind.DECODE)
                    0 -> failed(h, AlertRepositoryFailureKind.NETWORK)
                    else -> failed(h, AlertRepositoryFailureKind.HTTP, code)
                }
                assertEquals(29, rows.size)
                val next = post(row(71))
                val add = owner.add(id, draft())
                reached(next); settle(next); assertEquals(ComparisonAlertOutcome.Saved(row(71)), add.value)
            }
            quiet()
        }
        rig(*population(28)) {
            val first = post(row(71)); val second = post(row(72))
            owner.add(id, draft()); owner.add(id, draft())
            assertEquals(2L, owner.writeRevision); reached(first); quiet()
            settle(first); reached(second); settle(second); assertEquals(30, rows.size)
        }
        for (size in listOf(30, 31)) rig(*population(size)) {
            assertEquals(size, rows.size)
            rejected(owner.add(id, draft()), ComparisonAlertNotSentReason.CAP)
            val edited = put(row(1))
            val edit = owner.edit(id, 1, signed, draft())
            reached(edited); settle(edited); assertEquals(ComparisonAlertOutcome.Saved(row(1)), edit.value)
            val toggle = put(row(1).copy(isEnabled = false))
            owner.setEnabled(id, 1, false); reached(toggle); settle(toggle)
            val create = post(row(83, changed)); val old = plan("DELETE", 1, 500)
            val h = owner.edit(id, 1, signed, draft(changed))
            reached(create); settle(create); reached(old); settle(old)
            assertTrue(h.value is ComparisonAlertOutcome.OldNotDeleted)
            assertEquals(size + 1, rows.size)
            val gate = plan("DELETE", 1)
            owner.delete(id, 1); reached(gate); rejected(owner.add(id, draft()), ComparisonAlertNotSentReason.CAP)
            settle(gate); assertEquals(size, rows.size); quiet()
        }
        rig(*population(30)) {
            val remove = plan("DELETE", 1)
            owner.delete(id, 1); reached(remove); assertEquals(29, rows.size)
            rejected(owner.add(id, draft()), ComparisonAlertNotSentReason.CAP)
            settle(remove)
            val next = post(row(72)); owner.add(id, draft()); reached(next); settle(next); quiet()
        }
    }

    @Test fun W10_fifoBusyAndWithdrawal() = runTest {
        for (sameTick in listOf(false, true)) rig(row(41), row(42)) {
            val del = plan("DELETE", 41, 500); val put = put(row(42).copy(isEnabled = false))
            val first = owner.delete(id, 41)
            if (!sameTick) reached(del)
            for (h in listOf(owner.setEnabled(id, 41, false), owner.delete(id, 41), owner.edit(id, 41, signed, draft())))
                rejected(h, ComparisonAlertNotSentReason.BUSY)
            assertEquals(1L, owner.writeRevision)
            val next = owner.setEnabled(id, 42, false)
            assertEquals(2L, owner.writeRevision)
            if (sameTick) reached(del)
            quiet(); assertFalse(put.entered.get()); ids(42)
            settle(del); reached(put)
            failed(first, AlertRepositoryFailureKind.HTTP, 500)
            assertEquals(listOf(row(41), row(42).copy(isEnabled = false)), rows)
            settle(put); assertEquals(ComparisonAlertOutcome.Saved(row(42).copy(isEnabled = false)), next.value); quiet()
        }
        for (code in listOf(200, 500)) rig(row(41), row(42)) {
            val old = plan("PUT", 41, code, wireJson.encodeToString(row(41)))
            old.lateRow = row(41)
            if (code == 500) old.lateFailure = java.io.IOException("late write failure")
            val h = owner.setEnabled(id, 41, false)
            reached(old)
            val queued = owner.delete(id, 42)
            owner.onSync(); owner.onBackground()
            for (handle in listOf(h, queued)) assertEquals(ComparisonAlertOutcome.Withdrawn, handle.value)
            ids(41, 42)
            owner.onForeground(false); quiet()
            val next = put(row(41)); val active = owner.setEnabled(id, 41, true)
            reached(next)
            val publications = owner.publications
            settle(old)
            assertEquals(0, old.folds.get())
            assertEquals(publications, owner.publications)
            rejected(owner.delete(id, 41), ComparisonAlertNotSentReason.BUSY)
            assertEquals(ComparisonAlertOutcome.Pending, active.value)
            assertEquals(ComparisonAlertOutcome.Withdrawn, h.value)
            assertEquals(listOf(1, 1), listOf(h.completions, queued.completions))
            settle(next); assertEquals(ComparisonAlertOutcome.Saved(row(41)), active.value)
            val fresh = get(listOf(row(90)))
            owner.onBackground(); owner.onForeground(true); reached(fresh); settle(fresh); ids(90); quiet()
        }
    }

    @Test fun W11_writesAreOneShot() = runTest {
        for (verb in listOf("POST", "PUT", "DELETE")) for (code in listOf(401, 408, 409, 421, 503, 429, 0))
            for (manual in listOf(false, true)) rig(row(41)) {
                val n = 41.takeUnless { verb == "POST" }
                val header = if (code == 429) "60" else "0"
                val gate = plan(verb, n, if (code == 0) 200 else code, retry = header, disconnect = code == 0)
                fun send() = when (verb) {
                    "POST" -> owner.add(id, draft())
                    "PUT" -> owner.setEnabled(id, 41, false)
                    else -> owner.delete(id, 41)
                }
                val h = send(); reached(gate); settle(gate)
                assertEquals("one logical write", 2, entries.size)
                quiet(); assertEquals(2, server.requestCount); assertEquals(0, forced.get())
                failed(h, if (code == 0) AlertRepositoryFailureKind.NETWORK else AlertRepositoryFailureKind.HTTP, code.takeIf { it > 0 })
                val failure = (h.value as ComparisonAlertOutcome.Failed).failure as AlertRepositoryException
                if (code != 0) assertEquals(header, failure.httpFailure?.retryAfter)
                if (manual) {
                    val retry = plan(verb, n, body = if (verb == "DELETE") "" else wireJson.encodeToString(row(n ?: 83)))
                    val second = send(); reached(retry); settle(retry)
                    assertTrue(second.value is ComparisonAlertOutcome.Deleted || second.value is ComparisonAlertOutcome.Saved)
                } else advance(61_000)
                quiet(); assertEquals(0, forced.get())
                assertEquals(if (manual) 3 else 2, server.requestCount); assertTrue(sleeps.isEmpty())
            }
    }

    @Test fun W12_originalUseAtEachBoundary() = runTest {
        rig(row(41)) {
            val before = captures.get()
            rejected(owner.delete(id.copy(authGeneration = 2), 41), ComparisonAlertNotSentReason.UNADMITTED)
            graph = null
            rejected(owner.delete(id, 41), ComparisonAlertNotSentReason.UNADMITTED)
            assertEquals(0L, owner.writeRevision); assertEquals(before, captures.get()); quiet()
        }
        for (stage in listOf("entry", "repository", "credential", "network", "fold", "second network")) rig(row(41)) {
            val revoke = { snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1) }
            val gate = post(row(83, changed))
            val h = owner.edit(id, 41, signed, draft(changed))
            val before = captures.get()
            when (stage) {
                "entry" -> revoke()
                "repository" -> gate.beforeRepository = revoke
                "credential" -> atToken = revoke
                "network" -> beforeSend = revoke
            }
            if (stage == "fold" || stage == "second network") {
                reached(gate)
                if (stage == "fold") revoke() else {
                    val old = plan("DELETE", 41)
                    old.release.countDown()
                    beforeSend = revoke
                    settle(gate)
                    assertTrue("old delegate finished", old.finished.await(3, TimeUnit.SECONDS))
                    assertSame(gate.use, old.use)
                }
            }
            tick()
            if (stage != "entry") settle(gate)
            assertEquals(ComparisonAlertOutcome.Withdrawn, h.value)
            assertEquals(1, h.completions)
            assertEquals(2, acquisitions.get())
            assertEquals(before + when (stage) {
                "entry", "repository" -> 0
                "second network" -> 2
                else -> 1
            }, captures.get())
            if (stage == "second network") ids(83, 41) else ids(41)
            quiet()
            beforeSend = {}; atToken = {}
            val next = put(row(41))
            val positive = owner.setEnabled(id, 41, true)
            reached(next); settle(next); assertEquals(ComparisonAlertOutcome.Saved(row(41)), positive.value)
            gate.guard?.let { assertFalse(it()) }
        }
        for (boundary in listOf("repository", "network", "old DELETE", "fold")) {
            for (loss in listOf("reset", "background", "logout", "A-B-A", "generation", "premium", "use", "deleting", "deleted")) rig(row(41), row(42)) {
                val arrived = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val hold = { arrived.countDown(); resume.await() }
                val first = post(row(83, changed))
                val old = plan("DELETE", 41)
                when (boundary) {
                    "repository" -> first.beforeRepository = hold
                    "network" -> beforeSend = hold
                    "old DELETE" -> old.beforeRepository = hold
                }
                val h = owner.edit(id, 41, signed, draft(changed))
                val queued = owner.delete(id, 42)
                try {
                    if (boundary == "fold" || boundary == "old DELETE") {
                        reached(first)
                        if (boundary == "old DELETE") settle(first)
                    }
                    tick()
                    if (boundary != "fold") assertTrue("arrived at $boundary", arrived.await(3, TimeUnit.SECONDS))
                    when (loss) {
                        "reset" -> owner.reset()
                        "background" -> owner.onBackground()
                        "logout" -> live = null
                        "A-B-A" -> live = id.copy(uid = "b")
                        "generation" -> live = id.copy(authGeneration = 2)
                        "premium" -> premium.value = premium.value.copy(state = PremiumAccessState.FreeConfirmed)
                        "use" -> snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
                        else -> {
                            deletions.begin(id, "op")
                            if (loss == "deleted") deletions.serverDeleted("op")
                        }
                    }
                    revisions.value++; tick()
                    if (loss == "A-B-A") live = id
                    for (handle in listOf(h, queued)) {
                        assertEquals(ComparisonAlertOutcome.Withdrawn, handle.value)
                        assertEquals(1, handle.completions)
                    }
                    val published = owner.publications
                    resume.countDown()
                    settle(if (boundary == "old DELETE") old else first)
                    assertEquals(published, owner.publications)
                    assertEquals(listOf(1, 1), listOf(h.completions, queued.completions))
                    if (loss in setOf("background", "use")) {
                        if (boundary == "old DELETE") ids(83, 41, 42) else ids(41, 42)
                    } else assertNull(owner.state.value.adopted)
                    quiet()
                } finally {
                    resume.countDown()
                }
            }
        }
        for (loss in listOf("reset", "logout", "generation", "A-B-A", "premium", "deleting", "deleted", "other")) rig(row(41)) {
            val old = put(row(41)); val h = owner.setEnabled(id, 41, false)
            reached(old)
            when (loss) {
                "reset" -> owner.reset()
                "logout" -> live = null
                "generation" -> live = id.copy(authGeneration = 2)
                "A-B-A" -> { live = id.copy(uid = "b"); revisions.value++; tick(); live = id }
                "premium" -> premium.value = premium.value.copy(state = PremiumAccessState.FreeConfirmed)
                "deleting", "deleted", "other" -> {
                    deletions.begin(if (loss == "other") id.copy(uid = "b") else id, "op")
                    if (loss == "deleted") deletions.serverDeleted("op")
                }
            }
            if (loss != "other" && loss != "A-B-A" && loss != "deleting" && loss != "deleted") { revisions.value++; tick() }
            settle(old)
            if (loss == "other") assertEquals(ComparisonAlertOutcome.Saved(row(41)), h.value) else {
                assertEquals(ComparisonAlertOutcome.Withdrawn, h.value)
                assertNull(owner.state.value.adopted)
            }
            quiet()
        }
    }
}
