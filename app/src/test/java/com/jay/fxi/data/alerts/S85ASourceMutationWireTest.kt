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
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseContext
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.data.local.SourceSelectionSnapshot
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.alert.SourceAlertEditor
import com.jay.fxi.ui.alert.SourceAlertSectionHolder
import com.jay.fxi.ui.alert.SourceAlertSectionSource
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S8-5a (`R4c/S8/s8_5a_contract.agreed.r1.md` §3 "wire"): the source owner's four writes through the S11-locked production
 * delegation, the real repository, the authenticated client chain and its use checks, against a real HTTP server. Each entry
 * sends one request of its verb, path and body, carrying its own request's use check, admitted at its send; a refusal at the
 * entry fetches no credential and sends nothing; a use withdrawn before the last send check sends nothing, while a request
 * accepted under the reissued use still sends; a 401 is one send with no refresh and no replay; 408, 421, 503 with
 * Retry-After 0 and a dropped connection are never resent; each verb's evidence lands on its own floor key.
 */
class S85ASourceMutationWireTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grant = TopicGrantToken(11)
    private fun rowJson(id: Int, source: String = "upbit", threshold: Double = 1400.5, enabled: Boolean = true) =
        """{"id":$id,"user_id":"user-a","source":"$source","asset":"usdt-krw","condition":"above","threshold":$threshold,""" +
            """"is_enabled":$enabled,"triggered":false,"repeat_interval_sec":null,"created_at":"2026-09-01T09:00:00+09:00",""" +
            """"updated_at":"2026-09-02T09:00:00+09:00","triggered_at":null}"""

    private class Tokens : AuthTokenSource {
        val fetches = AtomicInteger()
        val forced = AtomicInteger()
        /** When set, a fetch signals [entered] and suspends until the test opens the gate. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered: CompletableDeferred<Unit>? = null
        override fun currentIdentity() = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            if (forceRefresh) forced.incrementAndGet()
            gate?.let {
                entered?.complete(Unit)
                it.await()
            }
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    private inner class Rig(premiumAt: PremiumAccessState = PremiumAccessState.PremiumConfirmed, readTimeoutMillis: Long = 5_000) {
        val server = MockWebServer().apply { start() }
        val tokens = Tokens()
        private val provider = AuthTokenProvider(tokens, orders = AccessOrderSequence())
        val useTagged: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        /** At each tagged send, whether every use context seen so far admitted. */
        val contexts = CopyOnWriteArrayList<TopicUseContext>()
        val admittedAtSend = CopyOnWriteArrayList<List<Boolean>>()
        @Volatile var beforeUseCheck: (Int) -> Unit = {}
        /** Set to withdraw the use once, at the next tagged send's network interceptor, before its last check. */
        @Volatile var withdrawAtNextSend = false
        /** Each write's own use check, as the production delegation hands it to the repository. */
        val guards = CopyOnWriteArrayList<() -> Boolean>()
        private val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                val tag = chain.request().tag(TopicUseTag::class.java)
                useTagged += tag != null
                tag?.let { contexts += it.context; admittedAtSend.add(contexts.map { c -> c.admitted() }) }
                beforeUseCheck(useTagged.size)
                if (tag != null && withdrawAtNextSend) {
                    withdrawAtNextSend = false
                    invalidations++
                }
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        private val wireJson = NetworkModule.provideWireJson()
        private val repository = AlertRepository(AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(http)
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
        val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, premiumAt))
        @Volatile var invalidations = 0L
        private val access = AlertSettingsAccess(
            { alice }, { TopicSessionFence(alice, "epoch-1", grant) },
            SnapshotTopicUseAuthority {
                TopicAccessSnapshot.INITIAL.copy(revision = 1L, userInvalidations = invalidations,
                    facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()))
            },
            { premium.value }, { true }, GraphProtectedAdmission({ alice }, DeletionAdmissionStore())
        )
        val floor = AlertRateLimitFloor { System.nanoTime() / 1_000_000 }
        val writeExits = AtomicInteger()
        /** The owner's Main; S8-5b's holder runs on it too. */
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + main)
        // The S11-locked production delegation functions of the three writes, the very ones `@Inject` passes; each exit is counted.
        private val create = productionSourceCreate(repository)
        private val edit = productionSourceEdit(repository)
        private val remove = productionSourceDelete(repository)
        val owner = SourceAlertSettingsOwner(
            { o, admitted -> repository.getSourceSettings(o, SourceAlertRowPolicy.SETTINGS_ASSET, admitted) },
            access, floor, AlertRetryPolicy(), { System.nanoTime() / 1_000_000 }, { 0.0 }, scope, { delay(it) }, premium, MutableStateFlow(0L),
            { owner, request, admitted -> guards += admitted; try { create(owner, request, admitted) } finally { writeExits.incrementAndGet() } },
            { owner, id, request, admitted -> guards += admitted; try { edit(owner, id, request, admitted) } finally { writeExits.incrementAndGet() } },
            { owner, id, admitted -> guards += admitted; try { remove(owner, id, admitted) } finally { writeExits.incrementAndGet() } },
            1_000L
        )

        suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }
        suspend fun until(label: String, condition: suspend () -> Boolean) {
            try {
                withTimeout(5_000) { while (!condition()) delay(10) }
            } catch (e: Exception) {
                throw AssertionError("timed out: $label", e)
            }
        }
        /** Foreground, and let the first GET adopt [rows] (each a row JSON). */
        suspend fun ready(vararg rows: String) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"settings":[${rows.joinToString(",")}],"total_count":${rows.size}}"""))
            onMain { owner.onForeground(false) }
            until("the first GET is adopted") { onMain { owner.state.value.settings } != null }
            server.takeRequest(5, TimeUnit.SECONDS)
            // The GET carries its own use check: the write observations start after it.
            useTagged.clear()
            contexts.clear()
            admittedAtSend.clear()
        }
        /** Every request the server received from now within [millis], in order. */
        fun drain(millis: Long = 300): List<okhttp3.mockwebserver.RecordedRequest> =
            generateSequence { server.takeRequest(millis, TimeUnit.MILLISECONDS) }.toList()
        fun next() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "a request reached the server" }
        fun close() {
            scope.cancel()
            main.close()
            server.shutdown()
        }
    }

    private fun rig(premiumAt: PremiumAccessState = PremiumAccessState.PremiumConfirmed, readTimeoutMillis: Long = 5_000,
                    body: suspend Rig.() -> Unit) {
        val r = Rig(premiumAt, readTimeoutMillis)
        try {
            // Invoked as `body(r)`, never `r.body()`, so that no member of Rig can be called in its place.
            runBlocking { body(r) }
        } finally {
            r.close()
        }
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    /** A response per method: GET gets the list, POST and PUT their row, DELETE 204. */
    private fun methods(list: String, post: String = "", put: String = "") = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.method) {
            "GET" -> MockResponse().setResponseCode(200).setBody(list)
            "POST" -> MockResponse().setResponseCode(200).setBody(post)
            "PUT" -> MockResponse().setResponseCode(200).setBody(put)
            else -> MockResponse().setResponseCode(204)
        }
    }

    @Test fun `S8_5A_W01 the four entries send one request each of their verb, path and body under their own use check`() = rig {
        ready(rowJson(7), rowJson(8))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rowJson(9, threshold = 1500.0)))
        val add = onMain { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) }
        until("the create ended") { add.outcome.value != SourceAlertSaveOutcome.Pending }
        server.enqueue(MockResponse().setResponseCode(200).setBody(rowJson(7, threshold = 1600.0)))
        val edit = onMain { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) }
        until("the edit ended") { edit.outcome.value != SourceAlertSaveOutcome.Pending }
        server.enqueue(MockResponse().setResponseCode(200).setBody(rowJson(8, enabled = false)))
        onMain { owner.setEnabled(8, false) }
        until("the toggle ended") { writeExits.get() == 3 }
        server.enqueue(MockResponse().setResponseCode(204))
        onMain { owner.delete(8) }
        until("the delete ended") { writeExits.get() == 4 }
        val sent = (1..4).map { next() }
        assertEquals("verbs and paths", listOf("POST /api/source-notification-settings", "PUT /api/source-notification-settings/7",
            "PUT /api/source-notification-settings/8", "DELETE /api/source-notification-settings/8"), sent.map { "${it.method} ${it.requestUrl?.encodedPath}" })
        assertEquals("the create's body", mapOf("source" to "\"upbit\"", "asset" to "\"usdt-krw\"", "condition" to "\"above\"", "threshold" to "1500.0", "is_enabled" to "true"),
            json(sent[0].body.readUtf8()).mapValues { it.value.toString() })
        assertEquals("the edit sends its one field", mapOf("threshold" to "1600.0"), json(sent[1].body.readUtf8()).mapValues { it.value.toString() })
        assertEquals("the toggle sends its one field", mapOf("is_enabled" to "false"), json(sent[2].body.readUtf8()).mapValues { it.value.toString() })
        assertEquals("each send carries a use check", listOf(true, true, true, true), useTagged.toList())
        assertEquals("each send's own use check admits at its send", listOf(true, true, true, true), admittedAtSend.map { it.last() })
        assertEquals("and each send has its own use context", 4, contexts.toSet().size)
        assertTrue("the create and the edit are saved", add.outcome.value is SourceAlertSaveOutcome.Saved && edit.outcome.value is SourceAlertSaveOutcome.Saved)
    }

    @Test fun `S8_5A_W02 a refusal at the entry fetches no credential and sends nothing`() = rig {
        ready(rowJson(7))
        val fetched = tokens.fetches.get()
        premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed)
        val add = onMain { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) }
        val edit = onMain { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) }
        onMain { owner.setEnabled(7, false); owner.delete(7) }
        delay(300)
        assertEquals("both handles refused", listOf(true, true), listOf(add, edit).map { it.outcome.value is SourceAlertSaveOutcome.NotSent })
        assertEquals("no credential fetched", fetched, tokens.fetches.get())
        assertEquals("no write reached the repository", 0, writeExits.get())
        assertEquals("only the first GET reached the server", 1, server.requestCount)
    }

    @Test fun `S8_5A_W03 a use withdrawn before the last send check sends nothing, a request under the reissued use still sends`() = rig {
        ready(rowJson(7), rowJson(8))
        server.dispatcher = methods(
            list = """{"settings":[${rowJson(7)},${rowJson(8)}],"total_count":2}""",
            put = rowJson(8, threshold = 1777.0, enabled = false))
        withdrawAtNextSend = true
        onMain { owner.setEnabled(7, false) }
        until("the first toggle's row is shown as approved again") { onMain { owner.state.value.settings?.firstOrNull { it.id == 7 }?.isEnabled } == true }
        assertEquals("the withdrawn toggle reached nobody", 0, drain().count { it.method == "PUT" })
        onMain { owner.setEnabled(8, false) }
        until("the second toggle's answer is adopted") { onMain { owner.state.value.settings?.firstOrNull { it.id == 8 }?.threshold } == 1777.0 }
        assertEquals("the request accepted under the reissued use is sent once", listOf("/api/source-notification-settings/8"),
            drain().filter { it.method == "PUT" }.map { it.requestUrl?.encodedPath.orEmpty() })
        assertEquals("at its send the old context no longer admits and its own does", listOf(false, true), admittedAtSend.last())
    }

    /**
     * S8-5a impl review r2 B (W03): for each verb, a use withdrawn at the request's last interceptor leaves it unsent, and a
     * new request under the reissued use is sent once; at its send the withdrawn request's context is false and its own true.
     */
    @Test fun `S8_5A_W09 each verb withdrawn at its last check sends nothing, and a new request then sends once`() = rig {
        ready(rowJson(7), rowJson(8))
        server.dispatcher = methods(
            list = """{"settings":[${rowJson(7)},${rowJson(8)}],"total_count":2}""",
            post = rowJson(9, threshold = 1777.0), put = rowJson(7, threshold = 1777.0))
        for ((verb, action, done) in listOf<Triple<String, () -> SourceAlertSaveRequest?, () -> Boolean>>(
            Triple("POST", { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) },
                { owner.state.value.settings?.any { it.id == 9 } == true }),
            Triple("PUT", { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) },
                { owner.state.value.settings?.firstOrNull { it.id == 7 }?.threshold == 1777.0 }),
            Triple("DELETE", { owner.delete(8); null }, { owner.state.value.settings?.none { it.id == 8 } == true }))) {
            contexts.clear()
            admittedAtSend.clear()
            withdrawAtNextSend = true
            val exits = writeExits.get()
            val first = onMain(action)
            until("$verb: the first remote ended") { writeExits.get() == exits + 1 }
            if (first != null) {
                until("$verb: the first handle ended") { onMain { first.outcome.value } != SourceAlertSaveOutcome.Pending }
                assertEquals("$verb: it is not sent", SourceAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED), onMain { first.outcome.value })
            } else {
                until("$verb: its row is shown again") { onMain { owner.state.value.settings?.any { it.id == 8 } } == true }
            }
            assertEquals("$verb: nothing of it reached the server", 0, drain().count { it.method == verb })
            onMain(action)
            until("$verb: the new request's answer is adopted") { onMain(done) }
            assertEquals("$verb: sent once", 1, drain().count { it.method == verb })
            assertEquals("$verb: at its send the withdrawn context is false and its own true", listOf(false, true), admittedAtSend.last())
        }
        // The DELETE's 204 was taken: its reservation is gone, so a create answered with the deleted id is adopted, with no GET.
        val exits = writeExits.get()
        server.dispatcher = methods(list = """{"settings":[]}""", post = rowJson(8, threshold = 1999.0))
        val add = onMain { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1999.0, true, null)) }
        until("the create ended") { onMain { add.outcome.value } != SourceAlertSaveOutcome.Pending }
        assertTrue("a create answered with the deleted id is saved", onMain { add.outcome.value } is SourceAlertSaveOutcome.Saved)
        assertEquals("one remote", exits + 1, writeExits.get())
        assertEquals("and no GET", 0, drain().count { it.method == "GET" })
    }

    @Test fun `S8_5A_W04 a 401 is one send with no refresh and no replay for each verb`() = rig {
        ready(rowJson(7), rowJson(8))
        val forced = tokens.forced.get()
        // Every write is answered 401 and every canonical GET a failure may owe gets the list, whatever their order; the list's
        // row 8 carries a threshold only it has, so its adoption is told apart from the local rollback.
        val list = """{"settings":[${rowJson(7)},${rowJson(8, threshold = 1888.0)}],"total_count":2}"""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.method == "GET") MockResponse().setResponseCode(200).setBody(list) else MockResponse().setResponseCode(401)
        }
        for ((verb, action) in listOf<Pair<String, () -> SourceAlertSaveRequest?>>(
            "POST" to { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) },
            "PUT" to { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) },
            "DELETE" to { owner.delete(8); null })) {
            val handle = onMain(action)
            if (handle != null) {
                until("$verb: the owner ended it") { onMain { handle.outcome.value } != SourceAlertSaveOutcome.Pending }
                val failure = (onMain { handle.outcome.value } as SourceAlertSaveOutcome.Failed).failure
                assertEquals("$verb: the typed 401", 401, (failure as com.jay.fxi.data.repository.AlertRepositoryException).httpFailure?.statusCode)
            } else {
                until("$verb: the row is back and the canonical list adopted") {
                    onMain { owner.state.value.settings?.firstOrNull { it.id == 8 }?.threshold } == 1888.0
                }
            }
            assertEquals("$verb: one send", 1, drain().count { it.method == verb })
        }
        assertEquals("no forced credential refresh", forced, tokens.forced.get())
    }

    @Test fun `S8_5A_W05 408, 421, 503 with Retry-After 0 and a dropped connection are never resent`() = rig {
        ready(rowJson(7))
        for ((label, response) in listOf(
            "408" to MockResponse().setResponseCode(408),
            "421" to MockResponse().setResponseCode(421),
            "503/0" to MockResponse().setResponseCode(503).setHeader("Retry-After", "0"),
            "dropped" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))) {
            val exits = writeExits.get()
            // The failure owes one canonical GET; it gets the list, and only the PUT gets the response under test.
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.method == "GET") MockResponse().setResponseCode(200).setBody("""{"settings":[${rowJson(7)}],"total_count":1}""")
                    else response
            }
            val edit = onMain { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) }
            until("$label: the owner ended the edit") { onMain { edit.outcome.value } != SourceAlertSaveOutcome.Pending }
            assertTrue("$label: an unknown result", onMain { edit.outcome.value } is SourceAlertSaveOutcome.Failed)
            val received = drain()
            assertEquals("$label: one PUT, never resent", 1, received.count { it.method == "PUT" })
            assertEquals("$label: one exit", exits + 1, writeExits.get())
        }
    }

    @Test fun `S8_5A_W05b a read timeout and a connection dropped after the request are one received PUT, never resent`() = rig(readTimeoutMillis = 500) {
        ready(rowJson(7))
        // Each settlement's list carries a threshold only it has, so its adoption is told apart from the first list.
        var settle = 1800.0
        val list = { """{"settings":[${rowJson(7, threshold = settle)}],"total_count":1}""" }
        for ((label, policy) in listOf("read timeout" to SocketPolicy.NO_RESPONSE, "dropped after the request" to SocketPolicy.DISCONNECT_AFTER_REQUEST)) {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.method == "GET") MockResponse().setResponseCode(200).setBody(list()) else MockResponse().setSocketPolicy(policy)
            }
            for ((verb, action) in listOf<Pair<String, () -> SourceAlertSaveRequest?>>(
                "POST" to { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) },
                "PUT" to { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) },
                "DELETE" to { owner.delete(7); null })) {
                val exits = writeExits.get()
                settle += 1.0
                val expected = settle
                val handle = onMain(action)
                until("$label $verb: the remote ended") { writeExits.get() == exits + 1 }
                if (handle != null) {
                    until("$label $verb: the owner ended it") { onMain { handle.outcome.value } != SourceAlertSaveOutcome.Pending }
                    assertTrue("$label $verb: an unknown result", onMain { handle.outcome.value } is SourceAlertSaveOutcome.Failed)
                }
                val received = drain(1_500)
                assertEquals("$label $verb: the server received it once", 1, received.count { it.method == verb })
                // The unknown delete keeps the row hidden until the canonical list, which the list answer then settles.
                until("$label $verb: settled by its own canonical list") {
                    onMain { owner.state.value.settings?.firstOrNull { it.id == 7 }?.threshold } == expected
                }
            }
        }
    }

    @Test fun `S8_5A_W06 each verb's response evidence lands on its own floor key`() = rig {
        ready(rowJson(7), rowJson(8))
        for ((label, action, key) in listOf(
            Triple("POST", { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)); Unit }, AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS),
            Triple("PUT", { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)); Unit }, AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING),
            Triple("DELETE", { owner.delete(8) }, AuthenticatedEndpoint.DELETE_SOURCE_NOTIFICATION_SETTING))) {
            val exits = writeExits.get()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            onMain(action)
            until("$label: the owner recorded its floor") { onMain { floor.remainingMillis(key) } > 0 }
            assertEquals("$label: one exit", exits + 1, writeExits.get())
        }
        assertEquals("and the logs key holds nothing", 0L, onMain { floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS) })
    }

    /**
     * S8-5a impl review r1 B (W03): for each verb, a use withdrawn while the request's credential is still being fetched leaves
     * that request unsent — its own context no longer admits at the last check — and a new request accepted under the reissued
     * use, with its own new context, is sent once.
     */
    @Test fun `S8_5A_W07 a use withdrawn while the credential is fetched sends nothing, a new request then sends once`() = rig {
        ready(rowJson(7), rowJson(8))
        val list = """{"settings":[${rowJson(7)},${rowJson(8)}],"total_count":2}"""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.method) {
                "GET" -> MockResponse().setResponseCode(200).setBody(list)
                "POST" -> MockResponse().setResponseCode(200).setBody(rowJson(9, threshold = 1500.0))
                "PUT" -> MockResponse().setResponseCode(200).setBody(rowJson(7, threshold = 1600.0))
                else -> MockResponse().setResponseCode(204)
            }
        }
        for ((verb, action) in listOf<Pair<String, () -> SourceAlertSaveRequest?>>(
            "POST" to { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)) },
            "PUT" to { owner.editAlert(7, SourceAlertEdit(threshold = 1600.0)) },
            "DELETE" to { owner.delete(8); null })) {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            tokens.entered = entered
            tokens.gate = gate
            val exits = writeExits.get()
            val tagged = useTagged.size
            val guardIndex = guards.size
            val first = onMain(action)
            withTimeout(5_000) { entered.await() }
            invalidations++
            tokens.gate = null
            gate.complete(Unit)
            until("$verb: the first remote ended") { writeExits.get() == exits + 1 }
            if (first != null) {
                until("$verb: the first handle ended") { onMain { first.outcome.value } != SourceAlertSaveOutcome.Pending }
                assertEquals("$verb: withdrawn before the wire, it is not sent", SourceAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED),
                    onMain { first.outcome.value })
            }
            assertEquals("$verb: nothing of it reached the server", 0, drain().count { it.method == verb })
            assertEquals("$verb: nor the network interceptors", tagged, useTagged.size)
            assertEquals("$verb: its own use check no longer admits", false, guards[guardIndex]())
            onMain(action)
            until("$verb: the second remote ended") { writeExits.get() == exits + 2 }
            assertEquals("$verb: the request under the reissued use is sent once", 1, drain().count { it.method == verb })
            assertEquals("$verb: its own use check admits", true, guards[guardIndex + 1]())
            assertEquals("$verb: and it reached the wire tagged", tagged + 1, useTagged.size)
        }
    }

    @Test fun `S8_5A_W08 a repeat change goes to the wire as JSON null for once and seconds for every`() = rig {
        ready(rowJson(7))
        for ((label, change, expected) in listOf(
            Triple("once", RepeatIntervalChange.Once, "null"),
            Triple("every five minutes", RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES), "300"))) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(rowJson(7)))
            val edit = onMain { owner.editAlert(7, SourceAlertEdit(repeat = change)) }
            until("$label: the edit ended") { onMain { edit.outcome.value } != SourceAlertSaveOutcome.Pending }
            assertEquals("$label: the PUT body", mapOf("repeat_interval_sec" to expected), json(next().body.readUtf8()).mapValues { it.value.toString() })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(rowJson(9)))
        val add = onMain { owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, RepeatInterval.FIVE_MINUTES)) }
        until("the create ended") { onMain { add.outcome.value } != SourceAlertSaveOutcome.Pending }
        assertEquals("the create's repeat", "300", json(next().body.readUtf8())["repeat_interval_sec"].toString())
    }

    /**
     * S8-5b J3 (`R4c/S8/s8_5b_contract.agreed.r1.md` §3): the tether holder over the real owner and wire. An allowed draft's
     * POST answered 400 keeps the editor and its draft with the rejection's copy; an edit's PUT answered 404 closes the editor
     * with the removed notice and drops the row. Each is one request; neither is resent nor remembered.
     */
    @Test fun `S8_5B_J3g through the real owner a POST 400 keeps the draft and an edit PUT 404 removes the row`() = rig {
        val list = java.util.concurrent.atomic.AtomicReference("""{"settings":[],"total_count":0}""")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.method) {
                "POST" -> MockResponse().setResponseCode(400).setBody("""{"detail":"rejected"}""")
                "PUT" -> MockResponse().setResponseCode(404).setBody("""{"detail":"gone"}""")
                else -> MockResponse().setResponseCode(200).setBody(list.get())
            }
        }
        onMain { owner.onForeground(false) }
        until("the first GET is adopted") { onMain { owner.state.value.settings } != null }
        val holderScope = CoroutineScope(SupervisorJob() + main)
        val remembered = CopyOnWriteArrayList<String>()
        val seam = object : SourceAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override val selection = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.Ready(alice, 1, null))
            override suspend fun prepareSelection(identity: AuthIdentityFence) = Unit
            override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) { remembered += sourceCode }
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
            override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)
            override fun editAlert(id: Int, edit: SourceAlertEdit) = owner.editAlert(id, edit)
            override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)
            override fun delete(id: Int) = owner.delete(id)
        }
        try {
            val holder = onMain {
                SourceAlertSectionHolder(seam, alice, SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET), holderScope,
                    isShown = { true }, liveQuotes = { mapOf("upbit" to 1500.0) }, permission = { it() }, enableNotifications = {})
            }
            val add = onMain {
                holder.start()
                holder.openAdd()
                checkNotNull(holder.editor).also {
                    holder.input(it, it.input.copy(thresholdText = "1600", condition = AlertCondition.ABOVE))
                    holder.submit(it)
                }
            }
            until("the POST was answered") { onMain { add.error } != null }
            assertEquals(SourceAlertEditor.Copy.REJECTED, onMain { add.error })
            assertTrue("the editor stays", onMain { holder.editor === add })
            assertEquals("its draft too", "1600", onMain { add.input.thresholdText })
            onMain { holder.dismiss(add) }
            list.set("""{"settings":[${rowJson(7, threshold = 1400.0)}],"total_count":1}""")
            onMain { owner.authoritative() }
            until("row 7 is adopted") { onMain { owner.state.value.settings?.any { it.id == 7 } } == true }
            // Deleted elsewhere: the next read answers no row, the PUT 404.
            list.set("""{"settings":[],"total_count":0}""")
            val edit = onMain {
                holder.openEdit(owner.state.value.settings!!.single { it.id == 7 })
                checkNotNull(holder.editor).also {
                    holder.input(it, it.input.copy(thresholdText = "1450"))
                    holder.submit(it)
                }
            }
            until("the PUT was answered") { onMain { holder.editor } == null }
            assertEquals(SourceAlertEditor.Copy.REMOVED, onMain { holder.notice })
            assertTrue("the row is gone", onMain { owner.state.value.settings.orEmpty().none { it.id == 7 } })
            assertEquals(SourceAlertSaveOutcome.Removed, onMain { edit.handle?.outcome?.value })
            val sent = drain(500).filter { it.method != "GET" }
            assertEquals("one POST and one PUT, never resent", listOf("POST /api/source-notification-settings", "PUT /api/source-notification-settings/7"),
                sent.map { "${it.method} ${it.requestUrl?.encodedPath}" })
            assertEquals("the POST's body", mapOf("source" to "\"upbit\"", "asset" to "\"usdt-krw\"", "condition" to "\"above\"", "threshold" to "1600.0", "is_enabled" to "true"),
                json(sent[0].body.readUtf8()).mapValues { it.value.toString() })
            assertEquals("the PUT's body", mapOf("threshold" to "1450.0"), json(sent[1].body.readUtf8()).mapValues { it.value.toString() })
            assertEquals("nothing remembered", emptyList<String>(), remembered.toList())
            // The UI sees 29 adopted rows; the owner also holds a create or a delete reservation it has not settled. A new add
            // opens (the UI's count) and its save is refused with the cap's copy by the owner, the draft kept, nothing sent.
            for (hiddenDelete in listOf(false, true)) {
                val n = if (hiddenDelete) 30 else 29
                val fixture = (1..n).joinToString(",") { rowJson(it, threshold = 1100.0 + it) }
                list.set("""{"settings":[$fixture],"total_count":$n}""")
                onMain { owner.authoritative() }
                until("the cap fixture was adopted") { onMain { owner.state.value.settings?.size } == n }
                drain(300)
                val exits = writeExits.get()
                onMain {
                    if (hiddenDelete) owner.delete(n)
                    else owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1600.0, true, null))
                    assertEquals("hiddenDelete=$hiddenDelete: the UI sees 29", 29, owner.state.value.settings!!.size)
                    holder.openAdd()
                    val blocked = checkNotNull(holder.editor) { "hiddenDelete=$hiddenDelete: the add opens at 29" }
                    holder.submit(blocked)
                    assertTrue("the sheet stays", holder.editor === blocked)
                    assertEquals("its draft too", "1500", blocked.input.thresholdText)
                    assertEquals("the owner's cap copy", "알림은 최대 30개까지 설정할 수 있습니다.", blocked.error)
                    holder.dismiss(blocked)
                }
                until("only the reservation's own write ended") { writeExits.get() == exits + 1 }
                assertEquals("hiddenDelete=$hiddenDelete: only the reservation's own write reached the wire",
                    listOf(if (hiddenDelete) "DELETE" else "POST"), drain(500).map { it.method }.filter { it != "GET" })
            }
        } finally {
            holderScope.cancel()
        }
    }

    /**
     * S8-5b J1 (`R4c/S8/s8_5b_contract.agreed.r1.md` §2 "입력, seed와 최소 patch"): what the editor builds reaches the wire as
     * agreed through the real owner. An add once omits the repeat, every five minutes sends 300; an edit that keeps an
     * unregistered interval and changes the price sends the price only, a switch to once sends JSON null, and saving a disabled
     * row unchanged sends the re-enable only. The asset is never in a PUT.
     */
    @Test fun `S8_5B_J1j the editor's drafts and patches reach the wire as agreed`() = rig {
        val rows = java.util.concurrent.atomic.AtomicReference(listOf(
            rowJson(7, threshold = 1400.0).replace("\"repeat_interval_sec\":null", "\"repeat_interval_sec\":450"),
            rowJson(8, threshold = 1300.0, enabled = false)))
        val nextId = AtomicInteger(20)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                return when (request.method) {
                    "POST" -> {
                        // The adopted row is the request's fields over a fresh row, as the server answers.
                        val row = kotlinx.serialization.json.JsonObject(json(rowJson(nextId.getAndIncrement())) + json(body)).toString()
                        rows.updateAndGet { it + row }
                        MockResponse().setResponseCode(200).setBody(row)
                    }
                    "PUT" -> {
                        val id = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
                        val old = json(rows.get().single { json(it).getValue("id").toString().toInt() == id })
                        val merged = kotlinx.serialization.json.JsonObject(old + json(body)).toString()
                        rows.updateAndGet { list -> list.map { if (json(it).getValue("id").toString().toInt() == id) merged else it } }
                        MockResponse().setResponseCode(200).setBody(merged)
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"settings":[${rows.get().joinToString(",")}],"total_count":${rows.get().size}}""")
                }
            }
        }
        onMain { owner.onForeground(false) }
        until("the first GET is adopted") { onMain { owner.state.value.settings }?.size == 2 }
        val holderScope = CoroutineScope(SupervisorJob() + main)
        val seam = object : SourceAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override val selection = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.Ready(alice, 1, null))
            override suspend fun prepareSelection(identity: AuthIdentityFence) = Unit
            override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) = Unit
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
            override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)
            override fun editAlert(id: Int, edit: SourceAlertEdit) = owner.editAlert(id, edit)
            override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)
            override fun delete(id: Int) = owner.delete(id)
        }
        try {
            val holder = onMain {
                SourceAlertSectionHolder(seam, alice, SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET), holderScope,
                    isShown = { true }, liveQuotes = { mapOf("upbit" to 1500.0, "bithumb" to 1490.0) }, permission = { it() }, enableNotifications = {})
            }
            onMain { holder.start() }
            suspend fun save(build: (com.jay.fxi.ui.alert.SourceAlertOpenEditor) -> Unit) {
                val open = onMain { checkNotNull(holder.editor).also { build(it); holder.submit(it) } }
                until("the editor closed on its answer") { onMain { holder.editor !== open } }
            }
            onMain { holder.openAdd() }
            save { holder.input(it, it.input.copy(thresholdText = "1550")) }
            onMain { holder.openAdd() }
            save {
                holder.pick(it, "bithumb")
                holder.input(it, it.input.copy(repeat = RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES)))
            }
            onMain { holder.openEdit(owner.state.value.settings!!.single { it.id == 7 }) }
            save { holder.input(it, it.input.copy(thresholdText = "1410")) }
            onMain { holder.openEdit(owner.state.value.settings!!.single { it.id == 7 }) }
            save { holder.input(it, it.input.copy(repeat = RepeatIntervalChange.Once)) }
            onMain { holder.openEdit(owner.state.value.settings!!.single { it.id == 8 }) }
            save { }
            val sent = drain(500).filter { it.method != "GET" }
            assertEquals(listOf("POST", "POST", "PUT /api/source-notification-settings/7", "PUT /api/source-notification-settings/7",
                "PUT /api/source-notification-settings/8"), sent.map { if (it.method == "PUT") "PUT ${it.requestUrl?.encodedPath}" else it.method })
            val bodies = sent.map { json(it.body.readUtf8()).mapValues { e -> e.value.toString() } }
            assertEquals("an add once omits the repeat", mapOf("source" to "\"upbit\"", "asset" to "\"usdt-krw\"", "condition" to "\"below\"",
                "threshold" to "1550.0", "is_enabled" to "true"), bodies[0])
            assertEquals("every five minutes", "300", bodies[1]["repeat_interval_sec"])
            assertEquals("and the picked exchange", "\"bithumb\"", bodies[1]["source"])
            assertEquals("a kept unregistered interval sends the price only", mapOf("threshold" to "1410.0"), bodies[2])
            assertEquals("once is JSON null", mapOf("repeat_interval_sec" to "null"), bodies[3])
            assertEquals("a disabled row saved unchanged is re-enabled only", mapOf("is_enabled" to "true"), bodies[4])
        } finally {
            holderScope.cancel()
        }
    }
}
