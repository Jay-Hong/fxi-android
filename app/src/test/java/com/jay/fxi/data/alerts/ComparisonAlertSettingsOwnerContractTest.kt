package com.jay.fxi.data.alerts

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.S6Krx3bOffWiringContractTest
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.ComparisonAlertSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

private typealias RowsResult = AuthBoundResult<List<ComparisonAlertSetting>>

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonAlertSettingsOwnerContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 2)
    private val grant = TopicGrantToken(11)
    private val json = NetworkModule.provideWireJson()
    private val base = json.decodeFromString<ComparisonAlertSetting>(
        checkNotNull(javaClass.getResource("/contracts/v2/alerts/comparison-setting-response.json")).readText()
    )

    private fun row(id: Int, tab: String = "usd", kind: String = "absolute"): ComparisonAlertSetting {
        val asset = if (tab == "tether") "usdt-krw" else "$tab-krw"
        return base.copy(id = id, userId = alice.uid, tab = tab, diffType = kind,
            leftAsset = asset, leftSource = if (tab == "tether") "upbit" else "kb",
            rightAsset = if (kind == "signed") "usd-krw" else asset,
            rightSource = if (tab == "tether" && kind == "absolute") "bithumb" else "hana")
    }

    private fun ok(vararg rows: ComparisonAlertSetting): RowsResult = AuthBoundResult({}, Result.success(rows.toList()))
    private fun http(code: Int, seconds: String? = null): RowsResult {
        val headers = if (seconds == null) headersOf() else headersOf("Retry-After", seconds)
        val failure = AuthenticatedHttpFailure(code, headers, ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
        return AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure)))
    }
    private fun failed(kind: AlertRepositoryFailureKind): RowsResult =
        AuthBoundResult({}, Result.failure(AlertRepositoryException("test", failureKind = kind)))

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fence(id: AuthIdentityFence) = TopicSessionFence(id, "epoch", grant)

    private class Call(val use: AlertSettingsUse, val guard: () -> Boolean, val job: Job) {
        val reply = CompletableDeferred<() -> RowsResult>()
    }

    private inner class Rig(val test: TestScope) {
        var identity: AuthIdentityFence? = alice
        var graph: TopicSessionFence? = fence(alice)
        var snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()))
        val premium = MutableStateFlow(confirmed(alice))
        val revisions = MutableStateFlow(0L)
        var released = true
        val deletions = DeletionAdmissionStore()
        var graphReads = 0
        val access = AlertSettingsAccess({ identity }, { graphReads++; graph }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { released }, GraphProtectedAdmission({ identity }, deletions))
        val calls = mutableListOf<Call>()
        val sleeps = mutableListOf<Long>()
        var sleeping = 0
        var heldSleep: CompletableDeferred<Unit>? = null
        var fetching = 0
        var maxFetching = 0
        val states = mutableListOf<ComparisonAlertSettingsState>()
        val parent = SupervisorJob()
        val writerCalls = IntArray(3)
        val owner = ComparisonAlertSettingsOwner(
            { use, guard ->
                val call = Call(use, guard, checkNotNull(currentCoroutineContext()[Job]))
                calls += call
                fetching++
                maxFetching = maxOf(maxFetching, fetching)
                try {
                    withContext(NonCancellable) { call.reply.await() }.invoke()
                } finally {
                    fetching--
                }
            }, access, { identity?.takeIf { released && !deletions.blocks(it.uid) } }, premium, revisions,
            CoroutineScope(parent + StandardTestDispatcher(test.testScheduler)),
            { ms ->
                sleeping++
                sleeps += ms
                try {
                    val held = heldSleep
                    if (held == null) delay(ms) else withContext(NonCancellable) { held.await() }
                } finally {
                    sleeping--
                }
            }, { 0.0 },
            post = { _, _, _ -> writerCalls[0]++; error("POST") },
            put = { _, _, _, _ -> writerCalls[1]++; error("PUT") },
            remove = { _, _, _ -> writerCalls[2]++; error("DELETE") }
        )
        init {
            CoroutineScope(parent + UnconfinedTestDispatcher(test.testScheduler)).launch {
                owner.state.collect { states += it }
            }
        }
        val state get() = owner.state.value
        fun drain() = test.runCurrent()
        fun advance(ms: Long) { test.advanceTimeBy(ms); drain() }
        fun front(force: Boolean = false) { owner.onForeground(force); drain() }
        fun reply(result: RowsResult, index: Int = calls.lastIndex) = deliver(index) { result }
        fun deliver(index: Int, body: () -> RowsResult) {
            assertTrue("expected GET #${index + 1}, saw ${calls.size}", index in calls.indices)
            assertTrue(calls[index].reply.complete(body))
            drain()
        }
        fun count(n: Int) = assertEquals("logical GETs", n, calls.size)
        fun close() {
            parent.cancel()
            heldSleep?.complete(Unit)
            calls.forEach { it.reply.cancel() }
            drain()
            assertEquals(listOf(0, 0, 0), writerCalls.toList())
        }
    }

    private fun TestScope.rig(body: Rig.() -> Unit) {
        val rig = Rig(this)
        try { rig.body() } finally { rig.close() }
    }

    @Test fun A01_fullGet() = runTest {
        val tokens = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(alice.uid, alice.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "test"
        }
        val provider = AuthTokenProvider(tokens, orders = AccessOrderSequence())
        val tagged = java.util.concurrent.atomic.AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider))
            .addNetworkInterceptor { chain ->
                tagged.set(chain.request().tag(TopicUseTag::class.java) != null)
                chain.proceed(chain.request())
            }.addNetworkInterceptor(TopicUseNetworkInterceptor(provider)).build()
        MockWebServer().use { server ->
            val rows = listOf(row(1), row(2, "tether", "signed"))
            server.enqueue(MockResponse().setBody("""{"alerts":${json.encodeToString(rows)},"total_count":2}"""))
            val service = Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java)
            val repository = AlertRepository(AuthenticatedApiClient(service, AuthenticatedTransport(provider, admitted = { true }), json))
            val read = comparisonSettingsGet(repository)
            rig {
                front()
                repeat(4) { owner.ensure() }
                drain(); count(1)
                val call = calls.single()
                assertEquals(alice, call.use.identity)
                assertTrue(call.guard())
                deliver(0) { runBlocking { read(call.use, call.guard) } }
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/comparison-alerts", request.path)
                assertTrue(tagged.get())
                assertEquals(rows, state.adopted?.rows)
                assertEquals(alice, state.identity)
                assertEquals(1, states.count { it.phase == AlertLoadPhase.Ready })
                assertEquals(1, maxFetching)
                count(1)
            }
        }
    }

    @Test fun A02_snapshotLifetime() = runTest {
        rig { advance(86_400_000); front(); count(1) }
        for (rows in listOf(emptyList(), listOf(row(1)))) rig {
            front(); count(1); reply(ok(*rows.toTypedArray()))
            val saved = state
            assertEquals(AlertLoadPhase.Ready, saved.phase)
            assertEquals(rows.size, saved.adopted?.count)
            for (step in listOf(29_999L, 1L, 86_370_000L)) {
                owner.onBackground(); advance(step); front()
                owner.ensure(); drain(); count(1)
                assertSame(saved, state)
            }
            graph = null
            val graphReadsBefore = graphReads
            val observers = parent.children.toSet()
            owner.ensure()
            assertEquals(observers, parent.children.toSet())
            advance(3_000); count(1)
            assertEquals(graphReadsBefore, graphReads)
            assertTrue(sleeps.isEmpty())
            assertSame(saved, state)

            owner.authoritative(); drain()
            assertEquals(1, (parent.children.toSet() - observers).size)
            count(1); assertEquals(listOf(1_000L), sleeps)
            graph = fence(alice); revisions.value++; drain()
            count(2); assertEquals(0, sleeping)
            reply(ok(row(2))); count(2)
            assertEquals(listOf(row(2)), state.adopted?.rows)
            front(true); count(3)
        }
    }

    @Test fun A03_trailing() = runTest {
        for (success in listOf(true, false)) rig {
            front()
            repeat(3) { owner.authoritative() }
            owner.ensure(); drain(); count(1)
            reply(if (success) ok(row(1)) else http(403))
            if (success) {
                count(2); assertEquals(listOf(row(1)), state.adopted?.rows)
                reply(ok(row(2))); count(2)
                assertEquals(listOf(row(2)), state.adopted?.rows)
            } else {
                count(1); assertEquals(AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden), state.phase)
            }
            assertEquals(1, maxFetching); assertTrue(sleeps.isEmpty())
        }
    }

    @Test fun A04_retryAbsorption() = runTest {
        for (duringFetch in listOf(false, true)) rig {
            front(); owner.onSync(); reply(http(503, "7"))
            repeat(3) { owner.ensure(); owner.onSync(); owner.manual() }
            drain(); assertEquals(1, sleeping)
            assertEquals(listOf(7_000L), sleeps)
            advance(6_999); count(1)
            advance(1); count(2); assertEquals(0, sleeping)
            if (duringFetch) owner.onSync()
            reply(ok(row(2))); count(if (duringFetch) 3 else 2)
            if (duringFetch) reply(ok(row(3)))
            assertEquals(1, maxFetching)
        }
    }

    @Test fun A05_retryBudget() = runTest {
        val kind = AlertRepositoryFailureKind.NETWORK
        val backoff = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        for ((result, delays, terminal) in listOf(
            Triple(http(503), backoff, AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 503)),
            Triple(failed(kind), backoff, AlertLoadFailure.Failed(kind, null)),
            Triple(failed(AlertRepositoryFailureKind.AUTH_UNAVAILABLE), backoff.take(2), AlertLoadFailure.AuthRequired),
            Triple(http(401), emptyList(), AlertLoadFailure.AuthRequired),
            Triple(http(403), emptyList(), AlertLoadFailure.Forbidden),
            Triple(failed(AlertRepositoryFailureKind.DECODE), emptyList(), AlertLoadFailure.Failed(AlertRepositoryFailureKind.DECODE, null))
        )) rig {
            front(); count(1)
            for ((index, ms) in delays.withIndex()) {
                reply(result); advance(ms - 1); count(index + 1); advance(1); count(index + 2)
            }
            reply(result)
            assertEquals(delays, sleeps)
            assertEquals(AlertLoadPhase.TerminalError(terminal), state.phase)
            owner.manual(); drain(); count(delays.size + 2)
        }
        // A success on the final retry serves the dirty fetch and gives its trail a full budget.
        rig {
            front(); count(1)
            for (ms in backoff) { reply(failed(kind)); advance(ms) }
            owner.onSync(); reply(ok(row(1))); count(7)
            for (ms in backoff) { reply(failed(kind)); advance(ms) }
            count(12); reply(ok(row(2))); assertEquals(listOf(row(2)), state.adopted?.rows)
        }
        for (error in listOf(TopicUseWithheldException(emptyList()), AuthIdentityChangedException(), CancellationException("ordinary"))) rig {
            front(); count(1); owner.onSync()
            deliver(0) { throw error }
            assertEquals(error.message == "ordinary", calls.single().job.isCancelled)
            assertEquals(ComparisonAlertSettingsState(), state)
            assertFalse(states.any { it.phase is AlertLoadPhase.TerminalError })
            advance(90_000); count(1); assertTrue(sleeps.isEmpty())
        }
        rig {
            front(); reply(ok(row(1)))
            val saved = state
            assertEquals(AlertLoadPhase.Ready, saved.phase)
            assertEquals(alice, saved.identity); assertEquals(listOf(row(1)), saved.adopted?.rows)
            owner.authoritative(); drain(); count(2)
            deliver(1) { throw CancellationException("ordinary") }
            assertTrue(calls.last().job.isCancelled); assertSame(saved, state)
            advance(90_000); count(2); assertTrue(sleeps.isEmpty())
        }
        rig {
            front(); reply(http(503))
            assertEquals(AlertLoadPhase.Retrying, state.phase)
            calls.single().job.cancel(); drain()
            assertEquals(ComparisonAlertSettingsState(), state)
            assertEquals(0, sleeping); advance(2_000); count(1)
        }
    }

    /**
     * S11-inject fixes the production constructor and A13 excludes AlertRateLimitFloor from the owner.
     * This case checks the observable 429 delay and immediate manual refresh across chains.
     */
    @Test fun A06_noSharedDeadlineOrCooldown() = runTest {
        rig {
            front(); count(1); reply(http(429, "60"))
            assertEquals(listOf(60_000L), sleeps)
            owner.onBackground(); front(); count(2)
            reply(ok()); owner.manual(); drain(); count(3)
            reply(http(403)); owner.manual(); drain(); count(4); reply(ok())
            assertEquals(0, sleeping)
            advance(60_000); count(4)
            assertEquals(listOf(60_000L), sleeps)
        }
    }

    @Test fun A07_background() = runTest {
        for (stage in listOf("fetch", "access", "retry", "empty", "rows", "refresh", "terminal")) rig {
            if (stage == "access") graph = null
            front()
            when (stage) {
                "fetch" -> owner.onSync()
                "retry" -> reply(http(503))
                "empty" -> reply(ok())
                "rows" -> reply(ok(row(1)))
                "refresh" -> { reply(ok(row(1))); owner.authoritative(); drain(); owner.onSync() }
                "terminal" -> reply(http(403))
            }
            val before = state
            val n = calls.size
            val gen = owner.generation
            if (stage in listOf("retry", "access")) assertEquals(1, sleeping)
            owner.onBackground(); drain()
            assertTrue(owner.generation > gen)
            assertSame(before, state)
            assertEquals(0, sleeping)
            assertTrue(calls.none { it.guard() || it.job.isActive })
            owner.ensure(); owner.onSync(); owner.manual()
            graph = null; revisions.value++; drain()
            advance(70_000); count(n); assertSame(before, state)
            graph = fence(alice); front()
            count(if (stage in listOf("rows", "empty", "refresh")) n else n + 1)
        }
    }

    @Test fun A08_lateCompletion() = runTest {
        for (background in listOf(false, true)) for (old in listOf("success", "503", "sleep")) rig {
            front(); count(1); owner.onSync()
            if (old == "sleep") {
                heldSleep = CompletableDeferred()
                reply(http(503)); assertEquals(1, sleeping)
            }
            if (background) owner.onBackground() else owner.reset()
            front(); count(2)
            val kept = states.toList()
            assertFalse(calls.first().guard()); assertTrue(calls.last().guard())
            if (old == "sleep") {
                heldSleep!!.complete(Unit); drain()
            } else {
                reply(if (old == "success") ok(row(9)) else http(503), 0)
            }
            assertEquals(kept, states); assertEquals(0, sleeping)
            owner.ensure(); drain(); count(2)
            assertTrue(calls.last().guard())
            reply(ok(row(2)), 1)
            assertEquals(listOf(row(2)), state.adopted?.rows)
            advance(70_000); count(2)
        }
    }

    @Test fun A09_authorityChanges() = runTest {
        for (background in listOf(false, true)) for (change in listOf("free", "logout", "account", "generation")) rig {
            front(); count(1); reply(ok(row(1)))
            owner.manual(); drain(); count(2)
            val old = calls.last()
            if (background) owner.onBackground()
            val next = when (change) {
                "account" -> bob
                "generation" -> AuthIdentityFence(alice.uid, alice.authGeneration + 1)
                "logout" -> null
                else -> alice
            }
            identity = next
            graph = next?.let(::fence)
            premium.value = when {
                next == null -> OwnedPremiumAccess()
                change == "free" -> confirmed(alice).copy(state = PremiumAccessState.FreeConfirmed)
                else -> confirmed(next)
            }
            val start = states.size
            drain()
            assertTrue(states.drop(start).contains(ComparisonAlertSettingsState()))
            assertNull(state.adopted); assertNull(state.identity); assertFalse(old.guard())
            if (background || change in listOf("free", "logout")) {
                assertEquals(AlertLoadPhase.Idle, state.phase); count(2)
            } else count(3)
            val saved = states.toList()
            reply(ok(row(9)), 1); assertEquals(saved, states)
            val target = next ?: alice
            identity = target; graph = fence(target); premium.value = confirmed(target)
            drain(); front(); count(3)
            assertEquals(target, calls.last().use.identity)
            val newRow = row(2).copy(userId = target.uid)
            reply(ok(newRow)); assertEquals(listOf(newRow), state.adopted?.rows)
            assertEquals(target, state.identity)
            if (target == bob) {
                identity = alice; graph = fence(alice); premium.value = confirmed(alice); drain()
                assertNull(state.adopted); count(4); reply(ok(row(3)))
                assertEquals(alice, state.identity)
            }
        }
        // A temporary user hold ends the captured use even when the same account stays premium.
        for (observedBy in listOf("idle", "revision", "ensure", "return", "fold")) rig {
            front(); reply(ok(row(1)))
            val saved = state
            val emitted = states.size
            if (observedBy != "idle") { owner.authoritative(); drain(); count(2) }
            val old = calls.last()
            fun hold() {
                snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1,
                    facts = snapshot.facts.copy(userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD)))
            }
            if (observedBy == "fold") {
                reply(AuthBoundResult({
                    hold()
                    if (!old.guard()) throw TopicUseWithheldException(emptyList())
                }, Result.success(listOf(row(9)))))
            } else {
                hold()
                when (observedBy) {
                    "idle", "revision" -> { revisions.value++; drain() }
                    "ensure" -> { owner.ensure(); drain() }
                }
                if (observedBy in listOf("revision", "ensure")) assertTrue(old.job.isCancelled)
                if (observedBy != "idle") reply(ok(row(9)))
            }
            assertNull(access.acquire()); assertFalse(old.guard())
            assertSame(saved, state); assertEquals(emitted, states.size)
            val before = if (observedBy == "idle") 1 else 2
            owner.ensure(); advance(3_000); count(before); assertTrue(sleeps.isEmpty())
            snapshot = snapshot.copy(facts = snapshot.facts.copy(userBlocks = emptySet()))
            revisions.value++; drain(); count(before)
            assertNotNull(access.acquire()); assertFalse(old.guard())
            owner.authoritative(); drain(); count(before + 1)
            reply(ok(row(2))); assertEquals(listOf(row(2)), state.adopted?.rows)
        }
        // The repository's application check can itself detect withdrawal after the owner's precheck.
        for (success in listOf(false, true)) rig {
            front(); count(1); owner.onSync()
            var folds = 0
            val raw = if (success) Result.success(listOf(row(8))) else Result.failure(java.io.IOException())
            reply(AuthBoundResult({
                folds++
                snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
                if (!calls.single().guard()) throw TopicUseWithheldException(emptyList())
            }, raw))
            assertEquals(1, folds)
            assertEquals(ComparisonAlertSettingsState(), state)
            assertFalse(states.any { it.adopted != null || it.phase is AlertLoadPhase.TerminalError })
            assertEquals(0, sleeping); count(1)
        }
        rig {
            front(); count(1); reply(http(503, "7")); assertEquals(1, sleeping)
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            assertNotNull(access.acquire()); assertFalse(calls.single().guard())
            revisions.value++; drain(); assertEquals(0, sleeping)
            assertEquals(ComparisonAlertSettingsState(), state)
            advance(7_000); count(1)
            owner.ensure(); drain(); count(2); reply(ok(row(2)))
            assertEquals(listOf(row(2)), state.adopted?.rows)
        }
        for (success in listOf(false, true)) rig {
            front(); count(1)
            val old = calls.single()
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            assertNotNull(access.acquire()); assertFalse(old.guard())
            var folded = false
            val result = if (success) Result.success(listOf(row(9))) else Result.failure(java.io.IOException())
            reply(AuthBoundResult({ folded = true }, result))
            assertFalse(folded)
            assertEquals(ComparisonAlertSettingsState(), state)
            advance(70_000); count(1)
            owner.ensure(); drain(); count(2); assertTrue(calls.last().guard())
            reply(ok(row(2))); assertEquals(listOf(row(2)), state.adopted?.rows)
        }
    }

    @Test fun A10_accessWake() = runTest {
        for (block in listOf<Rig.() -> Unit>({ released = false }, { deletions.begin(alice, "op") },
            { premium.value = confirmed(alice).copy(state = PremiumAccessState.FreeConfirmed) }, { identity = null })) rig {
            block(); front(); owner.onSync(); advance(10_000); count(0)
            assertEquals(listOf(ComparisonAlertSettingsState()), states)
        }
        for (hint in listOf(false, true)) rig {
            graph = null; front(); owner.ensure(); owner.onSync()
            advance(3_000); count(0); assertEquals(1, sleeping)
            graph = fence(alice)
            if (hint) { revisions.value++; drain(); count(1) }
            advance(999); count(if (hint) 1 else 0)
            advance(1); count(1); assertEquals(0, sleeping)
            for (ms in listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)) {
                reply(failed(AlertRepositoryFailureKind.NETWORK)); advance(ms)
            }
            count(6); reply(failed(AlertRepositoryFailureKind.NETWORK))
            assertEquals(AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)), state.phase)
            assertEquals(0, sleeping)
        }
    }

    @Test fun A11_adoptFullList() = runTest {
        for (size in 29..31) for (reverse in listOf(false, true)) rig {
            val usd = row(1).copy(isEnabled = false, triggered = true)
            val allowed = listOf(usd, row(2, "jpy"), row(3, "eur"), row(4, "tether"), row(5, "tether", "signed")) +
                (6..size).map { row(it) }
            val rejected = listOf(row(50).copy(userId = "other"), row(51).copy(leftAsset = "wrong"),
                row(52), row(52).copy(userId = "other"), row(53), row(53).copy(rightAsset = "wrong"), row(54), row(54))
            val raw = rejected.take(4) + allowed + rejected.drop(4)
            fun <T> ordered(rows: List<T>) = if (reverse) rows.reversed() else rows
            front(); count(1); reply(ok(*ordered(raw).toTypedArray()))
            val adopted = checkNotNull(state.adopted)
            assertEquals(alice, state.identity)
            assertEquals(size, adopted.count); assertEquals(ordered(allowed), adopted.rows)
            for ((tab, kind, ids) in listOf(
                Triple("usd", "absolute", listOf(1) + (6..size)), Triple("jpy", "absolute", listOf(2)),
                Triple("eur", "absolute", listOf(3)), Triple("tether", "absolute", listOf(4)),
                Triple("tether", "signed", listOf(5))
            )) assertEquals(ordered(ids), adopted.project(tab, kind).map { it.id })
            count(1)
        }
    }

    @Test fun A12_phases() = runTest {
        val terminal = AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden)
        rig {
            front(); count(1); reply(http(503))
            assertEquals(listOf(AlertLoadPhase.Idle, AlertLoadPhase.Loading, AlertLoadPhase.Retrying), states.map { it.phase })
            advance(2_000); reply(http(403))
            assertEquals(terminal, state.phase); assertEquals(1, states.count { it.phase is AlertLoadPhase.TerminalError })
        }
        for (rows in listOf(emptyList(), listOf(row(1)))) rig {
            front(); reply(ok(*rows.toTypedArray()))
            val saved = state
            assertEquals(AlertLoadPhase.Ready, saved.phase); assertEquals(alice, saved.identity)
            assertEquals(rows, saved.adopted?.rows)
            assertEquals(emptyList<Int>(), saved.adopted?.project("jpy", "absolute")?.map { it.id })
            val emitted = states.size
            owner.manual(); drain(); reply(http(503))
            advance(2_000); reply(http(403)); count(3)
            assertSame(saved, state); assertEquals(emitted, states.size)
        }
    }

    // Start from the same comment-free Kotlin/Java source inventory as the N1 locks.
    @Test fun A13_productionReach() {
        val base = S6Krx3bOffWiringContractTest.BASE
        val owner = base + "data/alerts/ComparisonAlertSettingsOwner.kt"
        val session = base + "ui/alert/ComparisonAlertHistorySession.kt"
        val section = base + "ui/alert/ComparisonAlertSection.kt"
        val route = base + "ui/premium/view/PremiumTopicRoute.kt"
        val registry = base + "data/alerts/AlertFamilyRefresherRegistry.kt"
        val repository = base + "data/repository/AlertRepository.kt"
        val service = base + "data/remote/AuthenticatedApiService.kt"
        val other = base + "data/graph/GraphProtectedAdmission.kt"
        val real = AlertN1StructureTest().productionSources
        fun violations(tree: Map<String, String>): Set<String> {
            fun uses(name: String): Map<String, Int> {
                val word = Regex("""\b$name\b""")
                return tree.mapValues { (_, source) -> word.findAll(source).count() }.filterValues { it > 0 }
            }
            val src = tree.getValue(owner)
            val flat = src.filterNot(Char::isWhitespace)
            return buildSet {
                if (uses("ComparisonAlertSettingsOwner").keys != setOf(owner, registry, section)) add("resolve")
                val ui = tree.getValue(section).filterNot(Char::isWhitespace)
                if (!ui.contains("funcomparisonAlertSettingsOwner():ComparisonAlertSettingsOwner") ||
                    !ui.contains("valentry=EntryPointAccessors.fromApplication(context,ComparisonAlertSectionEntryPoint::class.java)returnOwnerComparisonSectionSource(entry.comparisonAlertSettingsOwner())}") ||
                    !ui.contains("overridevalstate=owner.state") ||
                    !ui.contains("overridefunensure()=settingsOwner.ensure()") ||
                    !ui.contains("overridefunauthoritative()=settingsOwner.authoritative()") ||
                    Regex("""\b(?:catch|runCatching)\b""").containsMatchIn(tree.getValue(section))) add("factory")
                if (!ui.contains("returnComparisonSectionBody.Loaded(adopted.project(tab,kind))") ||
                    Regex("""\.(?:take|distinct|sorted)\w*\b""").containsMatchIn(tree.getValue(section))) add("project")
                val sectionSource = tree.getValue(section)
                val editor = base + "ui/alert/ComparisonAlertEditor.kt"
                val fields = base + "ui/alert/ComparisonAlertDraftRules.kt"
                val sheet = base + "ui/alert/ComparisonAlertEditorSheet.kt"
                val screens = listOf(section, editor, fields, sheet)
                val forbidden = Regex("""\b(?:readNotificationCapability|NotificationCapability\w*|NotificationPermission(?:Os|Lane|Lanes|Environment|EntryPoint|Dialog\w*)|ActivityResult\w*|rememberLauncherForActivityResult|POST_NOTIFICATIONS|ACTION_\w*SETTINGS|startActivit(?:y|ies)\w*|getSystemService|AlertRepository|onBackground)\b""")
                if (screens.any { forbidden.containsMatchIn(tree.getValue(it)) ||
                        Regex("""\b(?:rememberSaveable|Saver|combinedClickable|toggleable|onBankPush|onSourceDelivery|reset)\b""")
                            .containsMatchIn(tree.getValue(it)) }) add("surface")
                if (listOf(editor, fields, sheet).any { "EntryPointAccessors" in tree.getValue(it) } ||
                    listOf("comparisonAlertSettingsOwner", "ComparisonAlertSectionEntryPoint").any { uses(it) != mapOf(section to 2) })
                    add("factory")
                if (listOf(fields, sheet).any { Regex("""\b(?:ComparisonAlertSectionSource|ComparisonAlertHandle)\b""")
                        .containsMatchIn(tree.getValue(it)) }) add("surface")
                if (listOf("IconButton" to 4, "TextButton" to 4, "Switch" to 1, "clickable" to 1,
                        "rememberNotificationPermissionAction" to 1).any { (name, count) ->
                        Regex("""\b$name\s*[({]""").findAll(sectionSource).count() != count
                    } || listOf(
                        ".testTag(\"comparison-edit-\${row.id}\").clickable(onClickLabel=\"\${text.description}알림수정\"){holder.edit(row)}",
                        "Switch(checked=row.isEnabled,onCheckedChange={enabled->holder.toggle(row,enabled)}",
                        "IconButton({holder.askDelete(row)}", "if(addAvailable){TextButton(onClick=holder::add,",
                        "confirmButton={TextButton(onClick={holder.delete(token)})", "dismissButton={TextButton(onClick={holder.dismiss(token)})",
                        "permission={granted->action.run{granted()}}"
                    ).any { it !in ui }) add("surface")
                if (screens.drop(1).any { Regex("""\brememberNotificationPermissionAction\b""").containsMatchIn(tree.getValue(it)) } ||
                    Regex("""\bhistoryWiring\s*\.\s*create\s*\(""").findAll(sectionSource).count() != 1 ||
                    Regex("""\bfresh\s*\.\s*open\s*\(""").findAll(sectionSource).count() != 1 ||
                    !ui.contains("historyWiring.create(context),scope,requests,") ||
                    Regex("""(?<![.\w])open\s*\(""").containsMatchIn(sectionSource) ||
                    !ui.contains("session=freshfresh.open(listen)")) add("surface")
                val modal = tree.getValue(sheet).filterNot(Char::isWhitespace)
                if (listOf("TextButton(onClick=dismiss)", "TextButton(onClick=save,enabled=canSave,").any {
                        Regex(Regex.escape(it)).findAll(modal).count() != 1 }) add("surface")
                for ((name, args) in listOf("add" to "identity,draft", "edit" to "identity,oldId,originPair,draft",
                        "setEnabled" to "identity,id,enabled", "delete" to "identity,id")) {
                    if (!ui.contains("=settingsOwner.$name($args)") ||
                        Regex("""\bsettingsOwner\s*\.\s*$name\s*\(""").findAll(sectionSource).count() != 1) add("surface")
                }
                val sessionText = tree.getValue(session)
                val factoryBody = sessionText.substringAfter("internal object ProductionComparisonAlertHistoryWiring")
                    .substringBefore("internal sealed interface ComparisonHistoryBody")
                if (Regex("""\b(?:DeletionAdmissionStore|GraphProtectedAdmission)\b""").containsMatchIn(sessionText) ||
                    Regex("""\b(?:catch|runCatching)\b""").containsMatchIn(factoryBody) ||
                    !sessionText.filterNot(Char::isWhitespace).contains("wiring.read.read(owner,tab,diffType){requests.active===request&&scope.isActive&&wiring.access.admits(request.use)}"))
                    add("history-wiring")
                val sectionText = tree.getValue(section)
                val historyNames = Regex("""\b\w*History\w*\b""").findAll(sectionText).map { it.value }.toList()
                if (historyNames.groupingBy { it }.eachCount() != mapOf("ComparisonAlertHistoryWiringFactory" to 2,
                        "ProductionComparisonAlertHistoryWiring" to 1, "ComparisonAlertHistorySession" to 2,
                        "ComparisonAlertHistorySheet" to 1, "History" to 2) ||
                    !ui.contains("historyWiring:ComparisonAlertHistoryWiringFactory=ProductionComparisonAlertHistoryWiring,listen:") ||
                    !ui.contains("importandroidx.compose.material.icons.filled.History") ||
                    !ui.contains("Icon(Icons.Default.History,") ||
                    !ui.contains("valopen={mount.runShown{if(holder.modalFree){valfresh=ComparisonAlertHistorySession(") ||
                    !ui.contains("session?.let{opened->valobservedbyopened.state.collectAsState()observed.let{opened.currentState()}?.let{sheet->ComparisonAlertHistorySheet(") ||
                    ui.indexOf("key(owner,tab,kind,sourceFactory,epoch){") !in 0 until ui.indexOf("varsessionbyremember{") ||
                    !ui.contains("historyWiring.create(context)") || Regex("""\bAlertRepository\b""").containsMatchIn(sectionText)) add("surface")
                val actions = listOf("history", "refresh", "expand").map { tag ->
                    ui.indexOf("testTag(\"comparison-\$kind-$tag\")")
                }
                if (actions.first() < 0 || actions.zipWithNext().any { (a, b) -> a >= b }) add("surface")
                if (Regex("""@Singleton\s+internal\s+class\s+ComparisonAlertSettingsOwner\b""").findAll(src).count() != 1 ||
                    Regex("""@Inject\s+constructor\b""").findAll(src).count() != 1) add("singleton")
                if (uses("onComparisonDelivery") != mapOf(owner to 1, registry to 1)) add("delivery-caller")
                if (uses("getComparisonAlerts") != mapOf(owner to 1, repository to 2, service to 3)) add("caller")
                for ((name, args) in listOf("Create" to "request,", "Update" to "id,request,", "Delete" to "id,")) {
                    val write = name.lowercase() + "ComparisonAlert"
                    val delegate = "comparison$name"
                    val function = flat.substringAfter("internalfun$delegate(", "").substringBefore("internalfun")
                    if (uses(write) != mapOf(owner to 1, repository to 2, service to 3) ||
                        uses(delegate) != mapOf(owner to 2) || !flat.contains("$delegate(repository)") ||
                        !function.contains("repository.$write(use.identity,${args}useAdmitted=guard)")) add("surface")
                }
                if (uses("getComparisonHistory") != mapOf(repository to 1, session to 1) ||
                    !tree.getValue(session).filterNot(Char::isWhitespace).contains("repository.getComparisonHistory(owner.identity,tab,diffType,100,useAdmitted)") ||
                    uses("getComparisonNotificationLogs") != mapOf(repository to 1, service to 3)) add("surface")
                if (uses("comparisonSettingsGet") != mapOf(owner to 2) ||
                    !flat.contains("):this(comparisonSettingsGet(repository),access,") ||
                    !flat.contains("{use,guard->repository.getComparisonAlerts(use.identity,tab=null,useAdmitted=guard)}") ||
                    !flat.contains("valguard={!chain.retired&&access.admits(use)}") ||
                    !flat.contains("valsend={guard()&&access.admits(requestUse)}") ||
                    !flat.contains("valbound=fetch(requestUse,send)") || !flat.contains("getSettings(use,guard)")) add("delegation")
                if (Regex("""\bgetSettings\s*(?:\.\s*invoke\s*)?\(""").findAll(src).count() != 1) add("get-start")
                if (Regex("""\bAlertLoadPhase\s*\.\s*Ready\b""").findAll(src).count() != 1 ||
                    !flat.contains("publishState(ComparisonAlertSettingsState(AlertLoadPhase.Ready,identity,adopted))") ||
                    !flat.contains("ComparisonAlertRowPolicy.adopt(use.identity.uid,rows)commitRows(use.identity,adopted)") ||
                    !flat.contains("commitRows(use.identity,ComparisonAlertRowPolicy.Adopted(next))"))
                    add("ready-publication")
                if (!tree.getValue(registry).filterNot(Char::isWhitespace)
                        .contains(":this(listOf({bank.get()},{source.get()},{comparison.get()}),")) add("families")
                if (Regex("""\b(?:addObserver|ProcessLifecycleOwner|onBankPush|onSourceDelivery|getComparisonHistory|AlertRateLimitFloor)\b""")
                        .containsMatchIn(src)) add("surface")
            }
        }
        assertEquals(emptySet<String>(), violations(real))
        fun replace(tree: Map<String, String>, path: String, old: String, new: String): Map<String, String> {
            val src = tree.getValue(path)
            assertEquals("unique input: $path $old", 1, Regex(Regex.escape(old)).findAll(src).count())
            return tree + (path to src.replace(old, new))
        }
        for (extra in listOf("val store = DeletionAdmissionStore()", "val admission = GraphProtectedAdmission(tokens, store)")) {
            assertEquals(setOf("history-wiring"), violations(real + (session to (real.getValue(session) + "\n" + extra))))
        }
        val registered = "listOf({ bank.get() }, { source.get() }, { comparison.get() })"
        for (wrong in listOf("listOf({ bank.get() }, { source.get() })",
            "listOf({ bank.get() }, { source.get() }, { comparison.get() }, { comparison.get() })")) {
            assertEquals(setOf("families"), violations(replace(real, registry, registered, wrong)))
        }
        assertEquals(setOf("resolve"), violations(real + (route to (real.getValue(route) + "\nval bypass: ComparisonAlertSettingsOwner? = null"))))
        val separate = replace(real, section, "return OwnerComparisonSectionSource(entry.comparisonAlertSettingsOwner())",
            "return OwnerComparisonSectionSource(ComparisonAlertSettingsOwner(repository, access, tokens, deletions, coordinator))")
        assertEquals(setOf("factory"), violations(separate))
        val fallback = replace(real, section, "return OwnerComparisonSectionSource(entry.comparisonAlertSettingsOwner())",
            "return runCatching { OwnerComparisonSectionSource(entry.comparisonAlertSettingsOwner()) }.getOrElse { emptySource }")
        assertEquals(setOf("factory"), violations(fallback))
        val clickableRow = replace(real, section, "Row(Modifier.fillMaxWidth().testTag", "Row(Modifier.clickable { edit() }.fillMaxWidth().testTag")
        assertEquals(setOf("surface"), violations(clickableRow))
        for ((old, wrong) in listOf("Icons.Default.History" to "Icons.Default.OtherHistory",
            "historyWiring.create(context)" to "ProductionComparisonAlertHistoryWiring.create(context)",
            "comparison-\$kind-history" to "comparison-\$kind-extra")) {
            assertEquals(setOf("surface"), violations(replace(real, section, old, wrong)))
        }
        val interactive = replace(real, section, "onCheckedChange = { enabled -> holder.toggle(row, enabled) }", "onCheckedChange = {}")
        assertEquals(setOf("surface"), violations(interactive))
        val addButton = replace(real, section, "if (expanded) when (body)", "TextButton({}) { Text(\"추가\") }; if (expanded) when (body)")
        assertEquals(setOf("surface"), violations(addButton))
        val repositoryType = real + (section to (real.getValue(section) + "\nprivate val bypass: com.jay.fxi.data.repository.AlertRepository? = null"))
        assertEquals(setOf("surface"), violations(repositoryType))
        for (api in listOf("getComparisonHistory", "createComparisonAlert", "updateComparisonAlert", "deleteComparisonAlert")) {
            val bypassSection = real + (section to (real.getValue(section) + "\nprivate val bypass = AlertRepository::$api"))
            assertEquals(setOf("surface"), violations(bypassSection))
        }
        val resetting = replace(real, section, "mount.dispose()", "mount.dispose(); settingsOwner.reset()")
        assertEquals(setOf("surface"), violations(resetting))
        val trimmed = replace(real, section, "adopted.project(tab, kind)", "adopted.project(tab, kind).distinctBy { it.id }")
        assertEquals(setOf("project"), violations(trimmed))
        val resolved = replace(real, other, "internal class GraphProtectedAdmission(",
            "private fun resolve(p: javax.inject.Provider<com.jay.fxi.data.alerts.ComparisonAlertSettingsOwner>) = p.get()\n\ninternal class GraphProtectedAdmission(")
        assertEquals(setOf("resolve"), violations(resolved))
        val bypass = real + (other to (real.getValue(other) + "\nprivate val bypass = com.jay.fxi.data.repository.AlertRepository::getComparisonAlerts"))
        assertEquals(setOf("caller"), violations(bypass))
        val constant = replace(real, owner, "tab = null, useAdmitted = guard", "tab = null, useAdmitted = { true }")
        assertEquals(setOf("delegation"), violations(constant))
        val bound = "val bound = fetch(requestUse, send)"
        assertEquals(setOf("get-start", "delegation"), violations(replace(real, owner, bound,
            "val bound = if (attempt == 0) fetch(requestUse, send) else getSettings(requestUse, send)")))
        assertEquals(setOf("get-start"), violations(replace(real, owner, bound, "if (attempt > 0) getSettings(requestUse, send)\n$bound")))
        assertEquals(setOf("delegation"), violations(replace(real, owner, bound, "val bound = fetch(requestUse, { true })")))
        assertEquals(setOf("delivery-caller"), violations(real + (section to (real.getValue(section) + "\nowner.onComparisonDelivery()"))))
        val extraReady = replace(real, owner, "if (!chain.dirty) return",
            "if (!chain.dirty) { mutableState.value = state.value.copy(phase = AlertLoadPhase.Ready); return }")
        assertEquals(setOf("ready-publication"), violations(extraReady))
    }
}
