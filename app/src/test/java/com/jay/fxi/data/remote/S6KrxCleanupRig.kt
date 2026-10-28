package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*

/**
 * Reuses the unmodified checked alpha PAC/SUA/TGD/transport/REST fixture. SESSION is installed before
 * start, never simulated by the recording sink. Reflection changes only test assembly dependencies
 * and reads only the necessary fixture/ownership fields; it never invokes private production functions
 * or implements cleanup, send or record results. Missing observation fields fail with premise markers.
 * Boundary-input snapshots (scope/hold races) are explicitly separate from actual PAC publications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6KrxCleanupRig(
    val test: TestScope,
    val row: String,
    on: Boolean = true,
    installRestFixture: Boolean = true
) {
    val r = KrxSessionIntegrationRig(test, row, on = on)
    // KrxA1bFixture installs bootstrapOverride/httpEvidence at construction. O02 must keep
    // the original integration fake used by its frozen HEAD side; ON service tests keep this fixture.
    private val restFixture = if (installRestFixture) KrxA1bFixture(r) else null
    val f get() = need(restFixture, "REST service fixture was explicitly installed")
    val session get() = need(r.session, "candidate session")
    var cleanupReads = 0
    var memorySnapshot: () -> TopicAccessSnapshot = { r.snapshotSupply() }
    private val supplier: () -> TopicAccessSnapshot = { cleanupReads++; memorySnapshot() }
    val records get() = session.krxRetirementRecords
    val cleanup get() = session.krxMemoryCleanupLog
    val unsubscribes get() = r.sends.filter { it.request.type == "unsubscribe" }
    val unsubscribeRequests get() = r.requests.filter { it.type == "unsubscribe" }
    val liveRates get() = field(session, "liveRates") as TopicRates
    val live get() = need(field(session, "connection"), "current connection")
    val registry get() = field(live, "krxLeases") as TopicLeaseRegistry
    val generalRegistry get() = field(live, "leases") as TopicLeaseRegistry
    fun c(message: String) = "contract(${row.substringBefore('/')}): ${row.substringAfter('/', "main")}: $message"
    fun yes(message: String, condition: Boolean) = assertTrue(c(message), condition)
    fun eq(message: String, expected: Any?, actual: Any?) = assertEquals(c(message), expected, actual)
    fun <T : Any> need(value: T?, message: String): T = checkNotNull(value) { c(message) }
    init {
        setField(session, "krxRetirer", KrxSubscriptionRetirer.SESSION)
        setField(session, "krxMemorySnapshot", supplier)
    }
    suspend fun live(ack: Boolean = true, rates: Boolean = true, seconds: Long = 600, bootstrapDelayMillis: Long? = null) {
        r.approve()
        if (bootstrapDelayMillis != null) {
            assertTrue("premise: $row nonnegative bootstrap floor delay", bootstrapDelayMillis >= 0)
            r.httpFloor = r.clock.nowMillis() + bootstrapDelayMillis
        }
        r.start(); r.open()
        val first = r.first()
        yes("FIRST includes KRX on an open current connection", KRX in first.topics && field(live, "opened") == true)
        if (ack) {
            r.ack(first, seconds = seconds)
            yes("KRX acceptance established before retirement", r.store.snapshot.stateFor(KRX).confirmed)
            active()
            if (rates) {
                r.krxTick(); r.generalTick()
                yes("original scope contains accepted KRX and GENERAL", hasKrx(r.rates) && r.rates.quotes.keys.any { it.source != "krx" })
                yes("both memory surfaces contain KRX", hasKrx(liveRates))
            }
        }
    }
    fun issue(): KrxA1bFixture.Call {
        val active = f.krx.lastOrNull { !it.cancelled && it.outcome == null }
        if (active != null) return active
        val before = f.krx.size
        r.request(KRX)
        return f.next(before, budget = 2_600)
    }
    fun active(): Any = need(field(live, "krxActive"), "accepted KRX active capture")
    fun pending(): Any = need(field(live, "krxPending"), "pending KRX capture")
    fun lastKrxRequest(): TopicSubscribeRequest = need(r.sends.lastOrNull {
        it.accepted && it.request.type == "subscribe" && KRX in it.request.topics
    }?.request, "actual SENT KRX request")
    fun tuple(request: TopicSubscribeRequest = lastKrxRequest(), leaseId: String? = registry.held[KRX]?.leaseId) =
        KrxSubscriptionRetirement(field(live, "generation") as Long, request.requestId, leaseId)
    fun record(tuple: KrxSubscriptionRetirement): KrxRetirementRecord = need(records[tuple], "SESSION result for $tuple")
    fun hold() { f.holdRoundTrip(true); yes("inside held capability window", !r.snapshotSupply().facts.capabilityAllowed) }
    fun release() { f.holdRoundTrip(false); r.drain() }
    fun repeatHeldPublication(times: Int = 3) {
        assertTrue("premise: $row repeated publication bound", times in 1..8)
        repeat(times) { r.revise(); r.drain() }
    }
    fun ackKrxLease(request: TopicSubscribeRequest, leaseId: String, krxSeconds: Long = 600) {
        val original = "\"topic\":\"$KRX\",\"lease_id\":\"${request.requestId}-$KRX\",\"lease_duration_seconds\":600"
        val replacement = "\"topic\":\"$KRX\",\"lease_id\":\"$leaseId\",\"lease_duration_seconds\":$krxSeconds"
        val text = r.ackText(request, seconds = 600)
        assertTrue("premise: $row ACK fixture names the KRX lease being varied", original in text)
        r.wire.deliver(text.replace(original, replacement)); r.drain()
    }
    fun noKrx() {
        yes("display rates contain no KRX", !hasKrx(r.rates))
        yes("live rates contain no KRX", !hasKrx(liveRates))
    }
    fun generalWorks(rate: Double = 1447.0) {
        r.generalTick(rate)
        yes("GENERAL continues on the same session", r.rates.quotes.values.any { it.rate == rate })
    }
    fun unavailable(request: TopicSubscribeRequest) {
        r.wire.deliver(r.ackFor(request, mapOf(KRX to "topic_unavailable"))); r.drain()
    }
    fun renew(): TopicSubscribeRequest {
        val count = r.sends.size
        r.until(c("renewal is sent while old capture is active"), 1_200) { r.sends.size > count }
        return r.sends.last().request
    }
    fun acceptLatest(rate: Double) {
        r.until(c("new current connection exists"), 2_600) { field(session, "connection") != null }
        if (field(live, "opened") != true) r.open()
        r.until(c("new KRX subscription is pending"), 2_100) { field(live, "krxPending") != null }
        r.ack(lastKrxRequest())
        r.krxTick(rate)
        yes("new current KRX use accepts its own frame", r.rates.quotes.values.any { it.rate == rate })
    }
    fun nextKrx(after: Int): TopicSubscribeRequest {
        r.until(c("new KRX command after old retirement"), 2_100) { r.sends.drop(after).any { it.request.type == "subscribe" && KRX in it.request.topics } }
        return r.sends.drop(after).first { it.request.type == "subscribe" && KRX in it.request.topics }.request
    }
    fun immutableGeneral(): List<Any?> = listOf(
        generalRegistry.held, field(live, "controlOwner"), field(r.store, "currentTicket"),
        field(r.store, "currentAuthTicket"), r.store.snapshot.topics.filterKeys { it != KRX },
        r.store.snapshot.controlState, r.store.snapshot.authResolution, r.store.snapshot.wholeFailure)
    fun event(cause: KrxMemoryCause, result: KrxMemoryCleanupResult = KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED) {
        yes("memory event $cause/$result", cleanup.any { it.cause == cause && it.result == result })
    }
    fun waitQuery(): CompletableDeferred<EntitlementsOutcome> = CompletableDeferred<EntitlementsOutcome>().also { r.h.source.gates += it }
    fun epoch() = r.snapshot.facts.recordFence?.krxCapabilityEpoch
    fun marker() = KrxSendMarker(epoch(), r.snapshot.krxPermitSeq)
    fun close() = r.close()

    /** Actual RT.create assembly, with only desired broadened inside this test before start. */
    fun runtime(): TopicRuntime {
        val factory = TopicRuntimeFactory(
            webSocketFactory = { KrxWire { text, accepted ->
                r.requests.lastOrNull { "encoded-${it.requestId}" == text }?.let {
                    r.sends += KrxSessionIntegrationRig.WireSend(it, accepted, r.clock.nowMillis())
                }
            }.also { r.wires += it } },
            webSocketUrl = TopicSessionKrxHarness.URL,
            decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode,
            bootstrap = { _, _, _ -> CompletableDeferred<TopicSnapshotOutcome>().await() },
            issuer = r.issuer, fences = AuthFenceStream { it(r.identity) }, liveFence = { r.identity },
            recoveries = AuthCredentialRecoveryStream { },
            tabs = object : FreeTabStore {
                override suspend fun lastTab(uid: String) = FreeTab.USD
                override suspend fun remember(uid: String, tab: FreeTab) = Unit
            },
            credentials = field(session, "credentials") as TopicCommandCredentials,
            orders = r.h.parts.orders, authority = r.uses, clock = r.clock,
            newBootstrapFloor = { object : TopicBootstrapFloor {
                override fun record(statusCode: Int?, retryAfter: String?) = Unit
                override fun notBeforeMillis() = 0L
            } }, newScope = { r.h.parts.scope },
            encode = { r.requests += it; "encoded-${it.requestId}" },
            newRequestId = { "runtime-${r.requests.size}" }, jitter = { 0.0 }, krxSendReader = r.uses,
            krxMemorySnapshot = supplier)
        val runtime = factory.create()
        val assembled = field(runtime, "session") as TopicSessionCoordinator
        setField(assembled, "desired", r.desired)
        // The original fixture session and deliverer have never started. Reuse its observation helpers only.
        setField(r, "session", assembled)
        return runtime
    }
    companion object {
        fun hasKrx(rates: TopicRates) = rates.quotes.keys.any { it == TopicQuoteKey("krx", "usd-krw-futures") }
        private fun requiredField(target: Any, name: String): java.lang.reflect.Field =
            target.javaClass.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("premise: S6-3a observation field ${target.javaClass.simpleName}.$name exists")
        fun field(target: Any, name: String): Any? = try {
            requiredField(target, name).also { it.isAccessible = true }.get(target)
        } catch (failure: ReflectiveOperationException) {
            throw AssertionError("premise: S6-3a observation field ${target.javaClass.simpleName}.$name is readable", failure)
        }
        fun setField(target: Any, name: String, value: Any?) {
            try { requiredField(target, name).also { it.isAccessible = true }.set(target, value) }
            catch (failure: ReflectiveOperationException) {
                throw AssertionError("premise: S6-3a fixture field ${target.javaClass.simpleName}.$name is writable", failure)
            }
        }
    }
}

/** Every lower column gets its own real PAC, scheduler, transport and checked publication history. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun s63Test(row: String, vararg columns: String, body: suspend TestScope.(S6KrxCleanupRig, String) -> Unit) {
    for (column in columns.ifEmpty { arrayOf("main") }) runTest {
        val rig = S6KrxCleanupRig(this, "$row/$column")
        var primary: Throwable? = null
        try { body(rig, column) } catch (t: Throwable) { primary = t; throw t } finally {
            try { rig.r.beta!!.check.assertHeld() } catch (t: Throwable) {
                if (primary != null) primary.addSuppressed(t) else throw t
            } finally { rig.close() }
        }
    }
}
