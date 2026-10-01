package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecovery
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.entitlements.TopicGrantResult
import com.jay.fxi.data.entitlements.TopicRejectionLedger
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.TopicLastKnownOwner
import com.jay.fxi.data.local.TopicLastKnownStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned ②a contract for the production assembly (S2/decl_2a_codex.r1.md): only that the parts are **connected** — which
 * grant, focus, choice, refusal, recovery and floor reach which part, and that nothing happens before [TopicRuntime.start] or after
 * [TopicRuntime.stop]. The session's own behaviour (tab order, retries, fences) is TopicSessionCoordinatorTest's and is not repeated.
 * The runtime's scope must run everything it launches — the grant deliverer included — on the dispatcher [TopicRuntimeFactory]'s
 * scope supplier gives it; here that is the test scheduler. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicRuntimeFactoryTest {

    private companion object {
        const val URL = "http://localhost/ws"
        val U1 = AuthIdentityFence("u1", 1L)
        val F1 = TopicSessionFence(U1, "epoch-1", TopicGrantToken(1L))
        const val TETHER = TopicCatalogue.TETHER
        const val USD = "fx:usd-krw"
        const val JPY = "fx:jpy-krw"
    }

    private class Wire : WebSocket.Factory {
        val requests = mutableListOf<Request>()
        var listener: WebSocketListener? = null
        /** Every way the socket was let go of: a close or a cancel. */
        var endings = 0
        private val socket = object : WebSocket {
            override fun cancel() { endings += 1 }
            override fun close(code: Int, reason: String?): Boolean { endings += 1; return true }
            override fun queueSize() = 0L
            override fun request() = Request.Builder().url(URL).build()
            override fun send(text: String): Boolean {
                if (text == "ping") listener?.onMessage(this, """{"type":"pong"}""")
                return true
            }
            override fun send(bytes: okio.ByteString) = true
        }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requests += request
            this.listener = listener
            return socket
        }
        fun open() = listener!!.onOpen(
            socket,
            Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("").build()
        )
        fun drop() = listener!!.onFailure(socket, java.io.IOException("dropped"), null)
        fun deliver(text: String) = listener!!.onMessage(socket, text)
    }

    private class Issuer : TopicGrantIssuer {
        val revisions = MutableStateFlow(0L)
        override val accessRevisions: StateFlow<Long> get() = revisions
        var grant: TopicSessionFence? = F1
        var pulls = 0
        val rejected = mutableListOf<Pair<TopicGrantToken, List<TopicRejectionReason>>>()
        private val ledger = TopicRejectionLedger(AtomicLong(0L)::incrementAndGet)
        override suspend fun topicGrantResult(): TopicGrantResult {
            pulls += 1
            val fence = grant
            return TopicGrantResult(
                fence,
                TopicAccessSnapshot.INITIAL.copy(
                    revision = revisions.value,
                    facts = TopicAccessFacts.NONE.copy(token = fence?.grant, tokenStanding = fence != null, userBlocks = emptySet())
                )
            )
        }
        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) = ledger.reserve(grant, reasons)
        override fun abandonRejection(reservation: TopicRejectionReservation) = ledger.abandon(reservation)
        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) {
            rejected += reservation.grant to reservation.reasons.toList()
            ledger.complete(reservation, true)
        }
    }

    private class Tabs : FreeTabStore {
        val stored = mutableMapOf<String, FreeTab>()
        var gate: CompletableDeferred<Unit>? = null
        val reads = mutableListOf<String>()
        val remembered = mutableListOf<Pair<String, FreeTab>>()
        override suspend fun lastTab(uid: String): FreeTab {
            reads += uid
            gate?.await()
            return stored[uid] ?: FreeTab.USD
        }
        override suspend fun remember(uid: String, tab: FreeTab) {
            remembered += uid to tab
            stored[uid] = tab
        }
    }

    private class Floor : TopicBootstrapFloor {
        val recorded = mutableListOf<Pair<Int?, String?>>()
        /** Every record and read, in order. */
        val events = mutableListOf<String>()
        override fun record(statusCode: Int?, retryAfter: String?) {
            recorded += statusCode to retryAfter
            events += "record:$statusCode:$retryAfter"
        }
        override fun notBeforeMillis(): Long { events += "read"; return 0L }
    }

    /** R4-b3: an in-memory Preferences store that counts reads, so the virtual clock drives the last-known store. */
    private class MemoryPrefs : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        var reads = 0
        override val data: Flow<Preferences> get() = flow { reads += 1; emit(state.value) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Harness(test: TestScope, withLastKnown: Boolean = false) {
        val scheduler = test.testScheduler
        private val parent = test.backgroundScope.coroutineContext[Job]
        val wire = Wire()
        val issuer = Issuer()
        val tabs = Tabs()
        val floors = mutableListOf<Floor>()
        val scopes = mutableListOf<CoroutineScope>()
        var fenceObservers = 0
        var recoveryObservers = 0
        var recoveryCallback: ((AuthCredentialRecovery) -> Unit)? = null
        val orders = AccessOrderSequence()
        /** The next this many credential reads fail as unavailable; every read is counted. */
        var credentialFailures = 0
        var credentialReads = 0
        /** Run once, on the session's own loop, inside the next live identity read. */
        var onLiveRead: (() -> Unit)? = null
        val subscribes = mutableListOf<TopicSubscribeRequest>()
        val bootstrapCalls = mutableListOf<Triple<AuthIdentityFence, String, Boolean>>()
        var bootstrapOutcome: (String) -> TopicSnapshotOutcome = { TopicSnapshotOutcome.Degraded }
        private var ids = 0
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = scheduler.currentTime
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val memory = MemoryPrefs()
        fun lastKnownOver(prefs: DataStore<Preferences>) = TopicLastKnownStore(prefs, { scheduler.currentTime }, 300L,
            CoroutineScope(SupervisorJob(parent) + StandardTestDispatcher(scheduler)).also { scopes += it })
        val factory = TopicRuntimeFactory(
            webSocketFactory = { wire },
            webSocketUrl = URL,
            decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode,
            bootstrap = { owner, topic, useAdmitted ->
                bootstrapCalls += Triple(owner, topic, useAdmitted())
                bootstrapOutcome(topic)
            },
            issuer = issuer,
            fences = AuthFenceStream { onFence -> fenceObservers += 1; onFence(U1) },
            liveFence = { onLiveRead?.also { onLiveRead = null }?.invoke(); U1 },
            recoveries = AuthCredentialRecoveryStream { onRecovery -> recoveryObservers += 1; recoveryCallback = onRecovery },
            tabs = tabs,
            credentials = object : TopicCommandCredentials {
                override suspend fun currentSnapshot(): AuthSnapshot {
                    credentialReads += 1
                    if (credentialFailures > 0) {
                        credentialFailures -= 1
                        throw AuthUnavailableException("no credential")
                    }
                    return AuthSnapshot("u1", 1L, "token-1")
                }
                override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
                override suspend fun recordRejected(credential: AuthSnapshot) = Unit
                override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
            },
            orders = orders,
            authority = object : TopicUseAuthority {
                override fun acquire(fence: TopicSessionFence) = TopicUseLifetime(fence.grant, 0L)
                override fun admits(lifetime: TopicUseLifetime) = true
            },
            clock = clock,
            newBootstrapFloor = { Floor().also { floors += it } },
            newScope = { CoroutineScope(SupervisorJob(parent) + StandardTestDispatcher(scheduler)).also { scopes += it } },
            encode = { request -> subscribes += request; Json.encodeToString(TopicSubscribeRequest.serializer(), request) },
            newRequestId = { "r${++ids}" },
            jitter = { 0.0 },
            lastKnown = if (withLastKnown) lastKnownOver(memory) else null
        )

        fun asked() = bootstrapCalls.map { it.second }

        fun ack(requestId: String, active: List<String>, rejections: Map<String, String>) =
            Json.encodeToString(
                SubscriptionAck.serializer(),
                SubscriptionAck(
                    requestId = requestId,
                    operation = "subscribe",
                    acceptedTopics = active.map { SubscriptionAckTopic(it) },
                    rejectedTopics = rejections.map { SubscriptionRejection(it.key, it.value) },
                    removedTopics = emptyList(),
                    activeSubscriptions = active.map { SubscriptionAckTopic(it) }
                )
            ).replace("""{"request_id""", """{"type":"subscription_ack","request_id""")
    }

    /** start + online, with the issuer granting F1 and U1's stored tab set to [tab]. */
    private fun TestScope.running(tab: FreeTab = FreeTab.TETHER): Pair<Harness, TopicRuntime> {
        val h = Harness(this)
        h.tabs.stored["u1"] = tab
        val runtime = h.factory.create()
        runtime.start()
        runtime.setOnline(true)
        return h to runtime
    }

    @Test
    fun A01_creatingARuntime_startsNothing() = runTest {
        val h = Harness(this)
        h.factory.create()
        advanceTimeBy(10_000)
        assertEquals("a socket was opened", emptyList<Request>(), h.wire.requests)
        assertEquals("the grant was pulled", 0, h.issuer.pulls)
        assertEquals("a bootstrap was asked", emptyList<Triple<AuthIdentityFence, String, Boolean>>(), h.bootstrapCalls)
        assertEquals("the last tab was read", emptyList<String>(), h.tabs.reads)
        assertEquals("auth fences were observed", 0, h.fenceObservers)
        assertEquals("recoveries were observed", 0, h.recoveryObservers)
    }

    @Test
    fun A02_theGrant_andTheRestoredTab_reachTheSession_whichConnectsAndBootstrapsForThatOwner() = runTest {
        val (h, _) = running(FreeTab.TETHER)
        advanceTimeBy(5_000)
        assertTrue("the grant was never pulled", h.issuer.pulls >= 1)
        assertEquals(listOf(URL), h.wire.requests.map { it.url.toString() })
        assertEquals("the restored tab's topic was not asked first, for its owner, under an admitted use", Triple(U1, TETHER, true), h.bootstrapCalls.first())
        val pulls = h.issuer.pulls
        h.issuer.revisions.value += 1
        advanceTimeBy(100)
        assertTrue("a revision did not reach the deliverer", h.issuer.pulls > pulls)
    }

    @Test
    fun A03_aConfirmedChoice_reachesTheSession_andIsRemembered() = runTest {
        val h = Harness(this)
        h.tabs.gate = CompletableDeferred()
        val runtime = h.factory.create()
        runtime.start(); runtime.setOnline(true)
        advanceTimeBy(5_000)
        runtime.selectTab(U1, FreeTab.JPY)
        advanceTimeBy(5_000)
        assertEquals(JPY, h.asked().first())
        assertEquals(listOf("u1" to FreeTab.JPY), h.tabs.remembered)
    }

    @Test
    fun A04_aRefusal_goesBackToTheIssuer_withTheGrantItsConnectionWasOpenedUnder() = runTest {
        val (h, _) = running()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val subscribe = h.subscribes.first()
        h.wire.deliver(h.ack(subscribe.requestId, active = listOf(TETHER), rejections = mapOf(USD to "premium_required")))
        advanceTimeBy(100)
        assertEquals(listOf(F1.grant to listOf(TopicRejectionReason.PREMIUM_REQUIRED)), h.issuer.rejected)
    }

    @Test
    fun A05_aCredentialRecovery_reachesTheSession_andReopensAnAuthenticationEnding() = runTest {
        val h = Harness(this)
        h.tabs.stored["u1"] = FreeTab.TETHER
        h.credentialFailures = 3
        val runtime = h.factory.create()
        runtime.start(); runtime.setOnline(true)
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(11_000)
        assertEquals("fixture: the first delivery did not end on three failed credential reads", 3, h.credentialReads)
        assertEquals(1, h.recoveryObservers)
        val started = h.orders.next()
        checkNotNull(h.recoveryCallback)(AuthCredentialRecovery(U1, 1L, started, h.orders.next()))
        advanceTimeBy(1_000)
        assertTrue("the recovery never reached the session", h.credentialReads > 3)
    }

    @Test
    fun A06_bootstrapHttpEvidence_isRecordedOnce_inTheRuntimesOwnFloor_whichIsReadBeforeIssuing() = runTest {
        val (h, _) = running()
        val failure = AuthenticatedHttpFailure(
            429, Headers.headersOf("Retry-After", "5"), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP
        )
        h.bootstrapOutcome = { topic -> if (topic == TETHER) TopicSnapshotOutcome.Refused(failure) else TopicSnapshotOutcome.Degraded }
        advanceTimeBy(5_000)
        val floor = h.floors.single()
        assertEquals(listOf<Pair<Int?, String?>>(429 to "5"), floor.recorded)
        val recordedAt = floor.events.indexOf("record:429:5")
        assertTrue("the same floor was not read after the evidence was recorded: ${floor.events}", floor.events.drop(recordedAt + 1).contains("read"))
    }

    @Test
    fun A07_afterStop_nothingMoves() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(5_000)
        runtime.stop()
        advanceTimeBy(100)
        val pulls = h.issuer.pulls
        val asked = h.bootstrapCalls.size
        runtime.selectTab(U1, FreeTab.EUR)
        h.issuer.revisions.value += 1
        advanceTimeBy(10_000)
        assertFalse("the runtime's scope is still active", h.scopes.single().isActive)
        assertEquals(pulls, h.issuer.pulls)
        assertEquals(asked, h.bootstrapCalls.size)
        assertEquals(emptyList<Pair<String, FreeTab>>(), h.tabs.remembered)
    }

    @Test
    fun A08_eachRuntime_ownsItsScopeAndFloor() = runTest {
        val h = Harness(this)
        val first = h.factory.create()
        h.factory.create()
        assertEquals(2, h.scopes.size)
        assertEquals(2, h.floors.size)
        assertTrue(h.floors[0] !== h.floors[1])
        first.stop()
        advanceTimeBy(100)
        assertFalse("stopping the first runtime left its scope running", h.scopes[0].isActive)
        assertTrue("stopping the first runtime ended the second's scope", h.scopes[1].isActive)
    }

    @Test
    fun A09_stop_letsGoOfTheOpenSocket() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1_000)
        assertEquals("fixture: the socket ended before stop", 0, h.wire.endings)
        runtime.stop()
        advanceTimeBy(1_000)
        assertTrue("stop left the socket open", h.wire.endings >= 1)
    }

    @Test
    fun A10_startIsOnce_andAStoppedRuntimeDoesNotStart() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(100)
        val fenceObservers = h.fenceObservers
        runtime.start()
        advanceTimeBy(100)
        assertEquals("a second start observed recoveries again", 1, h.recoveryObservers)
        assertEquals("a second start observed fences again", fenceObservers, h.fenceObservers)

        val g = Harness(this)
        val stopped = g.factory.create()
        stopped.stop()
        stopped.start()
        stopped.setOnline(true)
        advanceTimeBy(5_000)
        assertEquals("a stopped runtime pulled the grant", 0, g.issuer.pulls)
        assertEquals("a stopped runtime observed recoveries", 0, g.recoveryObservers)
        assertEquals("a stopped runtime opened a socket", emptyList<Request>(), g.wire.requests)
    }

    @Test
    fun A11_inputsQueuedBehindStop_whileTheSessionIsBusy_openNoFurtherSocket() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1_000)
        assertEquals("fixture: one socket before stop", 1, h.wire.requests.size)
        // Stop while the loop is inside a handler; what is queued after it must not reach the session.
        h.onLiveRead = {
            runtime.stop()
            runtime.setOnline(false)
            runtime.setOnline(true)
        }
        h.issuer.revisions.value += 1
        advanceTimeBy(5_000)
        assertEquals("fixture: the hook never ran on the loop", null, h.onLiveRead)
        assertEquals("an input queued behind stop opened another socket", 1, h.wire.requests.size)
    }

    // ---- R4-b3 (Claude-owned contract, 계획 개정 17 R4 준비; R4b/design_codex.r1.md): a factory given the last-known store
    // hands the session its restore gate and save offer; creating a runtime reads nothing, a started one restores for the grant
    // and saves the prices it adopted under the grant's owner. Nothing in the app creates or starts a runtime yet. The
    // implementation reads but does not edit these rows. ----

    private val tetherJson = """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[
        {"source":"upbit","asset":"usdt-krw","rate":1390.0,"timestamp":"2026-08-31T10:20:00+09:00"}],"usd_krw_banks":[]}}"""

    @Test
    fun D1_creatingARuntimeWithTheStore_readsNothing() = runTest {
        val h = Harness(this, withLastKnown = true)
        h.factory.create()
        advanceTimeBy(10_000)
        assertEquals("D1", 0, h.memory.reads)
    }

    @Test
    fun D2_aStartedRuntime_restoresForTheGrant() = runTest {
        val h = Harness(this, withLastKnown = true)
        h.tabs.stored["u1"] = FreeTab.TETHER
        val runtime = h.factory.create()
        advanceTimeBy(1_000)
        assertEquals("D2: nothing before start", 0, h.memory.reads)
        runtime.start()
        runtime.setOnline(true)
        advanceTimeBy(5_000)
        assertTrue("D2: the grant restored its seed", h.memory.reads >= 1)
    }

    @Test
    fun D3_anAdoptedPriceIsSavedUnderTheGrantsOwner() = runTest {
        val h = Harness(this, withLastKnown = true)
        h.bootstrapOutcome = { topic ->
            if (topic == TETHER) TopicSnapshotOutcome.Delivered(TopicFrameDecoder(Json { ignoreUnknownKeys = true }).decode(tetherJson))
            else TopicSnapshotOutcome.Degraded
        }
        h.tabs.stored["u1"] = FreeTab.TETHER
        val runtime = h.factory.create()
        runtime.start()
        runtime.setOnline(true)
        advanceTimeBy(10_000)
        val saved = h.lastKnownOver(h.memory).restore(TopicLastKnownOwner("u1", "epoch-1"))
        assertEquals("D3", 1390.0, saved.quotes.values.single { it.source == "upbit" }.rate, 0.0)
    }

    @Test
    fun D4_lock_onlyTheFactoryUsesTheGate_andNothingCreatesTheFactory() {
        val main = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals("D4 gate", listOf("TopicRuntime.kt"), main.filter { it.name != "TopicLastKnownRestoreGate.kt" &&
            it.readText().contains("TopicLastKnownRestoreGate") }.map { it.name })
        assertEquals("D4 factory", emptyList<String>(), main.filter { it.name != "TopicRuntime.kt" &&
            it.readText().contains("TopicRuntimeFactory") }.map { it.name })
    }

    // R4-c C2-06 (Claude-owned contract; R4c/C2/design_codex.r2.md): the runtime exposes its session's display state and passes a
    // foreground return on — observed as the coming-back reconnection that skips the waiting rung.
    @Test
    fun `C2-06 the runtime shows its session's display and passes the foreground on`() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals("C2-06 display owner", U1, runtime.display.value.owner?.identity)
        assertEquals("C2-06 display connection", TopicConnectionDisplay.OPEN, runtime.display.value.connection)
        h.wire.drop()
        advanceTimeBy(100)
        assertEquals("C2-06 fixture: the reconnection waits on its rung", 1, h.wire.requests.size)
        runtime.setForeground(true)
        advanceTimeBy(1)
        assertEquals("C2-06 coming back reconnects at once", 2, h.wire.requests.size)
        runtime.stop()
    }

    // Battery r1 (PM13·PM14): a runtime stop posts Stop and cancels the scope at once, so the loop can end before handling it; the
    // display must still be cleared.
    @Test
    fun `C2-07c a runtime stop clears the display even when its scope ends before the Stop input`() = runTest {
        val (h, runtime) = running()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        check(runtime.display.value.owner != null) { "fixture: an owner is shown" }
        runtime.stop()
        advanceTimeBy(1)
        val d = runtime.display.value
        assertEquals("C2-07c no owner", null, d.owner)
        assertTrue("C2-07c no prices", d.rates.quotes.isEmpty())
        assertTrue("C2-07c not open", d.connection != TopicConnectionDisplay.OPEN)
    }
}
