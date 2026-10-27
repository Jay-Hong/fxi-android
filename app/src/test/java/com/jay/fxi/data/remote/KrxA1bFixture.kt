package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.local.TopicLastKnownRestore
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicLeaseRegistry
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.privateValue
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.setPrivate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import retrofit2.Response

/** Real service/transport/auth-provider, fake Retrofit endpoint. No socket, policy clone, or production hook.
 * A logical call and each physical 401 exchange have separate records. Token/body gates run under the real 10s service budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxA1bFixture(val r: KrxSessionIntegrationRig) {
    fun c(text: String) = "contract(${r.row}): $text"
    data class Reply(val status: Int, val body: String, val retryAfter: String? = null) {
        fun response(): Response<ResponseBody> {
            val bytes = body.toResponseBody("application/json".toMediaType())
            val headers = Headers.Builder().apply { retryAfter?.let { add("Retry-After", it) } }.build()
            return if (status in 200..299) Response.success(bytes, headers) else Response.error(bytes,
                okhttp3.Response.Builder().request(Request.Builder().url("https://a1b.invalid/").build())
                    .protocol(Protocol.HTTP_1_1).code(status).message("fixture").headers(headers).build())
        }
    }
    enum class Stage { TOKEN, BODY, REFRESH, RETURNED, CANCELLED }
    class Call(val topic: String, val at: Long, val read: TopicAccessSnapshot, val admitted: () -> Boolean, val job: Job?) {
        val token = CompletableDeferred<Unit>()
        val refresh = CompletableDeferred<Unit>()
        val replies = Channel<Reply>(Channel.UNLIMITED)
        val sends = mutableListOf<Long>()
        var stage = Stage.TOKEN
        var cancelled = false
        var cancellation: CancellationException? = null
        var throwOnBody: CancellationException? = null
        var outcome: TopicSnapshotOutcome? = null
    }
    val calls = mutableListOf<Call>()
    val krx get() = calls.filter { it.topic == KRX }
    var holdTokens = false
    var holdRefresh = false
    var floorEvidence = mutableListOf<Pair<Int?, String?>>()
    init {
        r.bootstrapOverride = ::bootstrap
        r.httpEvidence = { status, retry ->
            floorEvidence += status to retry
            retry?.toLongOrNull()?.let { r.httpFloor = maxOf(r.httpFloor, r.clock.nowMillis() + it * 1_000) }
        }
    }
    private suspend fun bootstrap(owner: AuthIdentityFence, topic: String, admitted: () -> Boolean): TopicSnapshotOutcome {
        val read = if (topic == KRX) r.readLog.lastOrNull { it.method == "acquireWithKrx" }?.snapshot ?: r.snapshot else r.snapshot
        val call = Call(topic, r.clock.nowMillis(), read, admitted, currentCoroutineContext()[Job]).also { calls += it }
        if (!holdTokens || topic != KRX) call.token.complete(Unit)
        if (!holdRefresh) call.refresh.complete(Unit)
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = r.identity?.identity
            override suspend fun fetchToken(identity: com.jay.fxi.data.auth.AuthIdentity, forceRefresh: Boolean): String {
                if (forceRefresh) { call.stage = Stage.REFRESH; call.refresh.await() } else call.token.await()
                return if (forceRefresh) "a1b-refreshed" else "a1b-initial"
            }
        }, processScope = r.h.parts.scope, orders = r.h.parts.orders)
        val endpoint: suspend (AuthRequestTag) -> Response<ResponseBody> = { tag ->
            if (tag.use != null && !tag.use.context.admitted()) throw TopicUseWithheldException(tag.use.context.observed())
            call.stage = Stage.BODY
            call.sends += r.clock.nowMillis()
            val reply = call.replies.receive()
            call.throwOnBody?.let { throw it }
            reply.response()
        }
        val api = java.lang.reflect.Proxy.newProxyInstance(AuthenticatedApiService::class.java.classLoader,
            arrayOf(AuthenticatedApiService::class.java)) { _, method, args ->
            check(method.name == "getTopicSnapshot") { c("unexpected Retrofit endpoint ${method.name}") }
            @Suppress("UNCHECKED_CAST")
            val continuation = args!!.last() as Continuation<Response<ResponseBody>>
            (suspend { endpoint(args[0] as AuthRequestTag) }).startCoroutineUninterceptedOrReturn(continuation)
        } as AuthenticatedApiService
        val json = Json { ignoreUnknownKeys = true }
        val service = TopicSnapshotBootstrapService(AuthenticatedApiClient(api, AuthenticatedTransport(provider) { true }, json), TopicFrameDecoder(json))
        try {
            return service.bootstrap(owner, topic, admitted).also { call.outcome = it; call.stage = Stage.RETURNED }
        } catch (cancelled: CancellationException) {
            call.cancelled = true; call.cancellation = cancelled; call.stage = Stage.CANCELLED
            throw cancelled
        }
    }
    suspend fun live(ack: Boolean = true, seed: Boolean = false) {
        r.approve()
        if (seed) {
            val fence = checkNotNull(r.issuer.topicGrantResult().fence)
            r.seed = TopicLastKnownRestore.Seed(TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1400.0,
                Instant.parse("2026-08-31T02:00:00Z")))), fence, checkNotNull(r.uses.acquire(fence)))
        }
        r.start(); r.open(); r.first()
        if (ack) r.ack()
    }
    fun issue(): Call {
        r.until(c("actual KRX issue reached transport"), 2_100) { krx.isNotEmpty() }
        return krx.last()
    }
    fun reply(call: Call, value: Reply, drain: Boolean = true) { assertTrue(c("reply gate remains open"), call.replies.trySend(value).isSuccess); if (drain) r.drain() }
    fun delivered(rate: Double = 1555.0, at: String = "2026-09-26T10:20:00+09:00") = Reply(200, krxFrame(rate, at))
    fun settleGeneral() {
        r.until("premise: ${r.row} original GENERAL plan reached endpoint", 2_100) { calls.count { it.topic != KRX } == r.desired.size - 1 }
        for (call in calls.filter { it.topic != KRX && it.outcome == null }) reply(call, Reply(404, """{"error":"topics_disabled"}"""))
    }
    fun next(after: Int, budget: Long = 2_100): Call {
        r.until(c("next actual KRX attempt ${after + 1}"), budget) { krx.size == after + 1 }
        return krx.last()
    }
    fun observation(): List<Any?> = listOf(r.rates, r.store.snapshot, r.display,
        field("tetherDelivered"), field("silenceArmedUntilMillis"), field("silenceHandledWindowMillis"), r.offers.toList(), r.graph.toList())
    fun evidence(): List<Any?> = listOf(field("tetherDelivered"), field("silenceArmedUntilMillis"), field("silenceHandledWindowMillis"), r.store.snapshot.stateFor(TopicCatalogue.TETHER).receiveGeneration)
    fun field(name: String) = privateValue(checkNotNull(r.session) { "premise: ${r.row} candidate session for $name" }, name)
    fun generalLeases() = (privateValue(checkNotNull(field("connection")) {
        "premise: ${r.row} GENERAL lease observation requires a current live connection"
    }, "leases") as TopicLeaseRegistry).held
    val retirements get() = r.retirements
    fun expectedRetirement(requestId: String, leaseId: String? = "$requestId-$KRX"): KrxSubscriptionRetirement =
        KrxSubscriptionRetirement(privateValue(checkNotNull(field("connection")) {
            "premise: ${r.row} retirement tuple capture requires the original connection"
        }, "generation") as Long, requestId, leaseId)
    fun resetF2() { setPrivate(checkNotNull(r.session), "resolvedLifetime", null); r.revise(); r.drain(); assertFalse("premise: ${r.row} new display wait is unresolved", r.display.cachedRefreshResolved) }
    fun has(rate: Double) = r.rates.quotes.values.any { it.rate == rate }
    suspend fun loss(cause: String) {
        when (cause) {
            "uid" -> { r.authFenceChanged(AuthIdentityFence("a1b-new-user", 2)); r.pac.onIdentityChanged(checkNotNull(r.identity)).applied(); r.drain() }
            "false" -> { r.h.source.next = { EntitlementsOutcome.StableActive(false) }; r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain() }
            "epoch" -> r.falseThenTrue()
            "premium" -> { r.h.source.next = { EntitlementsOutcome.StableInactive(false) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM); r.drain() }
            else -> error(c("unknown cause $cause"))
        }
    }
    /** boundary-input: 실 PAC 도달 미확인. Same-p/same-epoch C-1 round trip, explicitly separate from real PAC false→true. */
    fun holdRoundTrip(hidden: Boolean): TopicAccessSnapshot {
        val open = r.snapshot
        val held = open.copy(revision = open.revision + 1, capabilityInvalidations = open.capabilityInvalidations + 1,
            facts = open.facts.copy(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE)))
        r.snapshotSupply = { if (hidden) held else open.copy(revision = held.revision + 1, capabilityInvalidations = held.capabilityInvalidations) }
        r.revise(); r.drain()
        return held
    }
    /** Intercepts real produced inputs; no fabricated id, capture, outcome, or ownership. */
    inner class InputGate(private val kind: String) : AutoCloseable {
        private val target = checkNotNull(r.session)
        @Suppress("UNCHECKED_CAST") private val original = privateValue(target, "inputs") as Channel<Any>
        private val held = Channel<Any>(Channel.UNLIMITED)
        var count = 0; private set
        private var open = false
        init { setPrivate(target, "inputs", object : Channel<Any> by original {
            override fun trySend(element: Any): ChannelResult<Unit> = if (!open && element.javaClass.simpleName == kind) {
                count++; held.trySend(element)
            } else original.trySend(element)
        }) }
        fun release() { open = true; while (true) original.trySend(held.tryReceive().getOrNull() ?: break); r.drain() }
        override fun close() { release(); setPrivate(target, "inputs", original); held.close() }
    }
    companion object {
        fun krxFrame(rate: Double, at: String = "2026-09-27T10:20:00+09:00") = KrxSessionIntegrationRig.krxFrame(rate).replace("2026-09-26T10:20:00+09:00", at)
        val unavailable = Reply(503, """{"error":"temporarily_unavailable"}""")
        val unsupported = Reply(404, """{"error":"unknown_topic","supported_topics":[]}""")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun a1bTest(row: String, desired: Set<String> = TopicCatalogue.DESIRED + KRX, restore: Boolean = false, gap: Duration = 500.milliseconds,
    body: suspend TestScope.(KrxA1bFixture) -> Unit) = runTest {
    val r = KrxSessionIntegrationRig(this, row, desired = desired, withRestore = restore, gap = gap)
    val f = KrxA1bFixture(r)
    var failure: Throwable? = null
    try { body(f) } catch (t: Throwable) { failure = t; throw t } finally {
        try { r.beta!!.check.assertHeld() } catch (t: Throwable) {
            if (failure != null) failure.addSuppressed(t) else throw t
        } finally { r.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun a1bVariants(row: String, vararg labels: String, desired: Set<String> = TopicCatalogue.DESIRED + KRX,
    restore: Boolean = false, gap: Duration = 500.milliseconds, body: suspend TestScope.(KrxA1bFixture, String) -> Unit) {
    for (label in labels) a1bTest("$row/$label", desired, restore, gap) { f -> body(f, label) }
}
