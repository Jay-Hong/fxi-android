package com.jay.fxi.data.remote

import android.os.SystemClock
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicGrantDeliverer
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.TopicLastKnownOwner
import com.jay.fxi.data.local.TopicLastKnownRestoreGate
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.di.WireJson
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import com.jay.fxi.util.ApiConfig
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.WebSocket

/**
 * Assembly boundary for one topic session. [create] builds its scope, store, retry floor, and connectors
 * without starting REST, WebSocket, or grant work.
 *
 * The runtime exposes the session's display state and passes network and foreground input on;
 * the undelivered-bootstrap policy belongs to a later slice.
 */
internal class TopicRuntimeFactory internal constructor(
    private val webSocketFactory: () -> WebSocket.Factory,
    private val webSocketUrl: String,
    private val decode: (String) -> DecodedTopicFrame,
    private val bootstrap: suspend (AuthIdentityFence, String, () -> Boolean) -> TopicSnapshotOutcome,
    private val issuer: TopicGrantIssuer,
    private val fences: AuthFenceStream,
    private val liveFence: () -> AuthIdentityFence?,
    private val recoveries: AuthCredentialRecoveryStream,
    private val tabs: FreeTabStore,
    private val credentials: TopicCommandCredentials,
    private val orders: AccessOrderSequence,
    private val authority: TopicUseAuthority,
    private val clock: TopicCommandClock,
    private val newBootstrapFloor: (TopicCommandClock) -> TopicBootstrapFloor,
    private val newScope: () -> CoroutineScope,
    private val encode: (TopicSubscribeRequest) -> String,
    private val newRequestId: () -> String,
    private val jitter: () -> Double,
    /** Optional store for display seeds and adopted live rates. */
    private val lastKnown: TopicLastKnownStore? = null
) {
    @Inject
    constructor(
        tokenProvider: AuthTokenProvider,
        orders: AccessOrderSequence,
        fences: AuthFenceStream,
        recoveries: AuthCredentialRecoveryStream,
        coordinator: PremiumAccessCoordinator,
        bootstrapService: TopicSnapshotBootstrapService,
        decoder: TopicFrameDecoder,
        tabs: FreeTabStore,
        @WireJson json: Json,
        lastKnown: TopicLastKnownStore
    ) : this(
        webSocketFactory = ::buildWebSocketClient,
        webSocketUrl = ApiConfig.WS_URL,
        decode = decoder::decode,
        bootstrap = { owner, topic, useAdmitted -> bootstrapService.bootstrap(owner, topic, useAdmitted) },
        issuer = PremiumAccessTopicGrantIssuer(coordinator),
        fences = fences,
        liveFence = tokenProvider::currentIdentityFence,
        recoveries = recoveries,
        tabs = tabs,
        credentials = AuthTokenTopicCommandCredentials(tokenProvider),
        orders = orders,
        authority = SnapshotTopicUseAuthority { coordinator.accessSnapshot },
        clock = ElapsedRealtimeTopicCommandClock(),
        newBootstrapFloor = { clock -> TopicBootstrapRetryFloor(clock) { Clock.System.now() } },
        newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)) },
        encode = { json.encodeToString(it) },
        newRequestId = { UUID.randomUUID().toString() },
        jitter = { Random.nextDouble() },
        lastKnown = lastKnown
    )

    fun create(): TopicRuntime {
        val scope = newScope()
        val floor = newBootstrapFloor(clock)
        val restoreGate = lastKnown?.let { TopicLastKnownRestoreGate(it, authority, liveFence) }
        val offerLive: ((String, String, TopicRates) -> Unit)? = lastKnown?.let { store ->
            { uid, epoch, rates -> store.offer(TopicLastKnownOwner(uid, epoch), rates) }
        }
        lateinit var deliverer: TopicGrantDeliverer
        val session = TopicSessionCoordinator(
            scope = scope,
            clock = clock,
            connect = {
                TopicTransport(webSocketFactory(), decode).also {
                    it.open(Request.Builder().url(webSocketUrl).build())
                }
            },
            credentials = credentials,
            orders = orders,
            authority = authority,
            bootstrap = bootstrap,
            store = TopicSubscriptionStateStore(),
            encode = encode,
            newRequestId = newRequestId,
            jitter = jitter,
            liveIdentity = liveFence,
            restoreSeed = restoreGate?.let { it::restore },
            offerLive = offerLive,
            onBootstrapHttpEvidence = floor::record,
            bootstrapNotBeforeMillis = floor::notBeforeMillis,
            onRejected = { owner, rejected -> deliverer.forwardRejection(owner, rejected) }
        )
        val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        deliverer = TopicGrantDeliverer(
            issuer = issuer,
            sink = session,
            fences = fences,
            scope = scope,
            clock = clock,
            deliveryDispatcher = dispatcher
        )
        val focus = TopicFocusProvider(scope, fences, liveFence, tabs, session::setFocus)
        return TopicRuntime(scope, session, deliverer, focus, recoveries)
    }
}

/** Owns one session and its inputs for a single start-to-stop lifetime. */
internal class TopicRuntime internal constructor(
    private val scope: CoroutineScope,
    private val session: TopicSessionCoordinator,
    private val deliverer: TopicGrantDeliverer,
    private val focusProvider: TopicFocusProvider,
    private val recoveries: AuthCredentialRecoveryStream
) {
    private var started = false
    private var stopped = false

    @Synchronized
    fun start() {
        if (started || stopped) return
        started = true
        session.start()
        deliverer.start()
        focusProvider.start()
        recoveries.observe(session::onCredentialRecovered)
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        session.stop()
        scope.cancel()
    }

    /** The screen's read-only view of this session. */
    val display: StateFlow<TopicDisplayState> = session.display

    fun setOnline(value: Boolean) = session.setOnline(value)

    fun setForeground(value: Boolean) = session.setForeground(value)

    fun retryConnection(owner: TopicDisplayOwner) = session.retryConnection(owner)

    /** The tab the focus provider accepted for the live identity; see [OwnedTopicFocus]. */
    val focus: StateFlow<OwnedTopicFocus?> = focusProvider.focus

    fun selectTab(owner: AuthIdentityFence, tab: FreeTab) = focusProvider.onTabSelected(owner, tab)
}

/** Replaceable boundary for one session's bootstrap HTTP retry floor. */
internal interface TopicBootstrapFloor {
    fun record(statusCode: Int?, retryAfter: String?)

    fun notBeforeMillis(): Long
}

/**
 * Keeps the server's bootstrap retry floor on the same elapsed-time timeline as [clock].
 * The shared policy parses Retry-After against wall time; each result is translated once when recorded.
 */
internal class TopicBootstrapRetryFloor(
    private val clock: TopicCommandClock,
    private val wallNow: () -> Instant
) : TopicBootstrapFloor {
    private var floorMillis = 0L

    @Synchronized
    override fun record(statusCode: Int?, retryAfter: String?) {
        val floor = FreeSnapshotSchedulePolicy.retryFloorAfter(wallNow(), retryAfter) ?: return
        val remaining = floor - wallNow()
        if (remaining <= Duration.ZERO) return
        val wholeMillis = remaining.inWholeMilliseconds
        val delayMillis = if (wholeMillis < Long.MAX_VALUE && remaining > wholeMillis.milliseconds) {
            wholeMillis + 1
        } else {
            wholeMillis
        }
        val now = clock.nowMillis()
        val candidate = if (floor == Instant.DISTANT_FUTURE || delayMillis >= Long.MAX_VALUE - now) {
            Long.MAX_VALUE
        } else {
            now + delayMillis
        }
        floorMillis = maxOf(floorMillis, candidate)
    }

    @Synchronized
    override fun notBeforeMillis(): Long = floorMillis
}

/** Production clock backed by [SystemClock.elapsedRealtime] and coroutine delay. */
internal class ElapsedRealtimeTopicCommandClock : TopicCommandClock {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()

    override suspend fun sleep(duration: Duration) = delay(duration)
}

/** Adapts the token provider to command credentials. */
internal class AuthTokenTopicCommandCredentials(
    private val provider: AuthTokenProvider
) : TopicCommandCredentials {
    override suspend fun currentSnapshot(): AuthSnapshot = provider.currentSnapshot()

    override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? =
        provider.refreshAfterUnauthorized(rejected)

    override suspend fun recordRejected(credential: AuthSnapshot) = provider.recordRejected(credential)

    override suspend fun recordRejectionEvidence(credential: AuthSnapshot) =
        provider.recordRejectionEvidence(credential)
}
