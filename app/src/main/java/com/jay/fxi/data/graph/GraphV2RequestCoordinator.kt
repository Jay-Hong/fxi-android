package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphPeriodSupport
import com.jay.fxi.domain.model.GraphTabAdmission
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal data class GraphKey(val tab: String, val period: GraphPeriod)

internal data class GraphDataScope(val uid: String, val userAccessEpoch: String)

/** Both live sources used by the send guard must be thread-safe, non-blocking reads. */
internal interface GraphOwnerSource {
    fun currentIdentity(): AuthIdentityFence?
    suspend fun capture(expected: AuthIdentityFence): AuthSnapshot
}

/** The transport must consult the guard at every actual send, including replays. */
internal interface GraphV2Fetching {
    suspend fun catalog(
        owner: AuthSnapshot,
        useAdmitted: () -> Boolean
    ): AuthenticatedHttpResponse<GraphV2CatalogResponse>

    suspend fun tab(
        owner: AuthSnapshot,
        key: GraphKey,
        useAdmitted: () -> Boolean
    ): AuthenticatedHttpResponse<GraphV2TabResponse>
}

internal data class GraphEntry(val tab: GraphV2Tab, val online200At: Instant?)

internal data class GraphRequestState(
    val dataScope: GraphDataScope? = null,
    val catalog: GraphCatalog? = null,
    val entries: Map<GraphKey, GraphEntry> = emptyMap(),
    val inFlight: Set<GraphKey> = emptySet(),
    val failures: Map<GraphKey, Throwable> = emptyMap()
)

/** Request ownership and cache application are confined to one consumer; A2a has no timers. */
internal class GraphV2RequestCoordinator(
    private val fetcher: GraphV2Fetching,
    private val owners: GraphOwnerSource,
    /** Published access fence, read from the loop and from transport threads. */
    private val currentAccessFence: () -> TopicSessionFence?,
    private val uses: TopicUseAuthority,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val onEventFailure: (Throwable) -> Unit
) {
    private data class RequestContext(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

    private class Registration(val requestId: Long, val context: RequestContext) {
        // The only request-owned mutable value consulted outside the loop. Job completion is
        // not disposal: the response still has to pass the loop's application boundary.
        val disposed = AtomicBoolean(false)
    }

    private sealed interface Event {
        data class Activate(val key: GraphKey) : Event
        data object Deactivate : Event
        data object ContextChanged : Event
        data class Refresh(val force: Boolean) : Event
        data class CatalogFinished(
            val requestId: Long,
            val result: Result<AuthenticatedHttpResponse<GraphV2CatalogResponse>>
        ) : Event
        data class TabFinished(
            val requestId: Long,
            val key: GraphKey,
            val result: Result<AuthenticatedHttpResponse<GraphV2TabResponse>>
        ) : Event
    }

    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(GraphRequestState())
    val state: StateFlow<GraphRequestState> = mutableState.asStateFlow()

    // Loop-confined. The transport guard never reads these maps or the active key.
    private var snapshot = GraphRequestState()
    private var activeKey: GraphKey? = null
    private var context: RequestContext? = null
    private var catalogAt: Instant? = null
    private var catalogRequest: Registration? = null
    private val tabRequests = mutableMapOf<GraphKey, Registration>()
    private var nextRequestId = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                for (event in inbox) {
                    try {
                        handle(event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        // A broken completion must not leave its key permanently claimed.
                        releaseCompletion(event)
                        publish()
                        onEventFailure(failure)
                    }
                }
            } finally {
                discardRequests()
                inbox.close()
                publish()
            }
        }
    }

    fun onActivated(key: GraphKey) { inbox.trySend(Event.Activate(key)) }
    fun onDeactivated() { inbox.trySend(Event.Deactivate) }
    fun onContextChanged() { inbox.trySend(Event.ContextChanged) }
    fun onRefreshRequested(force: Boolean = false) { inbox.trySend(Event.Refresh(force)) }

    private fun handle(event: Event) {
        val now = clock.now()
        when (event) {
            is Event.Activate -> {
                synchronizeContext()
                activeKey = event.key
                requestActive(now, force = false)
            }
            Event.Deactivate -> {
                synchronizeContext()
                activeKey = null
            }
            Event.ContextChanged -> if (synchronizeContext()) requestActive(now, force = false)
            is Event.Refresh -> {
                synchronizeContext()
                requestActive(now, event.force)
            }
            is Event.CatalogFinished -> applyCatalog(event, now)
            is Event.TabFinished -> applyTab(event, now)
        }
        publish()
    }

    /** Clear ownership and protected context state together, before publishing the new scope. */
    private fun synchronizeContext(): Boolean {
        val fence = currentAccessFence()
        val identityMatches = fence != null && owners.currentIdentity() == fence.identity
        val dataScope = fence?.userAccessEpoch?.let { GraphDataScope(fence.identity.uid, it) }
        val next = if (identityMatches && dataScope != null) {
            uses.acquire(fence!!)?.let { RequestContext(fence, it) }
        } else null
        val scopeChanged = snapshot.dataScope != dataScope
        if (!scopeChanged && context == next) return false

        discardRequests()
        context = next
        catalogAt = null
        snapshot = GraphRequestState(
            dataScope = dataScope,
            entries = if (scopeChanged) emptyMap() else snapshot.entries
        )
        return true
    }

    private fun requestActive(now: Instant, force: Boolean) {
        val key = activeKey ?: return
        val current = context ?: return

        // Catalog refresh is independent of tab freshness and never blocks the tab request.
        val lastCatalogAt = catalogAt
        if (catalogRequest == null && (snapshot.catalog == null || lastCatalogAt == null ||
                now - lastCatalogAt >= GraphV2Domain.ttl(snapshot.catalog))) {
            startCatalog(current)
        }
        if (GraphV2Domain.support(snapshot.catalog, key.tab, key.period) == GraphPeriodSupport.Unsupported) return
        if (tabRequests.containsKey(key)) return
        if (!force && isFresh(snapshot.entries[key], now)) return
        startTab(current, key)
    }

    private fun isFresh(entry: GraphEntry?, now: Instant): Boolean {
        val stamped = entry?.online200At ?: return false
        val age = now - stamped
        return age >= Duration.ZERO && age < GraphV2Domain.ttl(snapshot.catalog) &&
            now.toLocalDateTime(KST).date == stamped.toLocalDateTime(KST).date
    }

    /** Safe on a transport thread: only immutable captures, atomic disposal and live sources. */
    private fun useAdmitted(registration: Registration): Boolean {
        val captured = registration.context
        return !registration.disposed.get() && scope.isActive &&
            owners.currentIdentity() == captured.fence.identity &&
            currentAccessFence() == captured.fence && uses.admits(captured.lifetime)
    }

    private suspend fun captureOwner(registration: Registration): AuthSnapshot {
        if (!useAdmitted(registration)) throw CancellationException("Graph request use withdrawn")
        val owner = owners.capture(registration.context.fence.identity)
        if (owner.fence != registration.context.fence.identity) throw AuthIdentityChangedException()
        if (!useAdmitted(registration)) throw CancellationException("Graph request use withdrawn")
        return owner
    }

    private fun startCatalog(current: RequestContext) {
        val registration = Registration(++nextRequestId, current)
        catalogRequest = registration
        scope.launch {
            val result = runCatching {
                val owner = captureOwner(registration)
                fetcher.catalog(owner) { useAdmitted(registration) }
            }
            inbox.trySend(Event.CatalogFinished(registration.requestId, result))
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    }

    private fun startTab(current: RequestContext, key: GraphKey) {
        // Registration is the logical start; changing the active key does not withdraw it.
        val registration = Registration(++nextRequestId, current)
        tabRequests[key] = registration
        scope.launch {
            val result = runCatching {
                val owner = captureOwner(registration)
                fetcher.tab(owner, key) { useAdmitted(registration) }
            }
            inbox.trySend(Event.TabFinished(registration.requestId, key, result))
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    }

    private fun applyCatalog(event: Event.CatalogFinished, now: Instant) {
        val registration = catalogRequest?.takeIf { it.requestId == event.requestId } ?: return
        if (event.result.exceptionOrNull() is CancellationException) {
            releaseCompletion(event)
            return
        }
        if (!useAdmitted(registration)) return
        catalogRequest = null
        registration.disposed.set(true)
        val response = event.result.getOrNull() ?: return
        if (response.statusCode != 200) return
        val body = response.body ?: return
        snapshot = snapshot.copy(catalog = GraphV2Domain.catalog(body))
        catalogAt = now
        // No pump on completion: failure or absence alone must never start another request.
    }

    private fun applyTab(event: Event.TabFinished, now: Instant) {
        val registration = tabRequests[event.key]?.takeIf { it.requestId == event.requestId } ?: return
        if (event.result.exceptionOrNull() is CancellationException) {
            releaseCompletion(event)
            return
        }
        if (!useAdmitted(registration)) return
        tabRequests.remove(event.key)
        registration.disposed.set(true)

        val admitted = event.result.mapCatching { response ->
            if (response.statusCode != 200) {
                throw response.failure?.let { AuthenticatedApiException(it) }
                    ?: IOException("Graph tab returned HTTP ${response.statusCode}; expected 200")
            }
            val body = response.body ?: throw IOException("Graph tab returned an empty 200 body")
            when (val admission = GraphV2Domain.admit(body, event.key.tab, event.key.period, snapshot.catalog)) {
                is GraphTabAdmission.Accepted -> admission.tab
                is GraphTabAdmission.Rejected -> throw IOException(admission.reason)
            }
        }
        val failure = admitted.exceptionOrNull()
        if (failure == null) {
            snapshot = snapshot.copy(
                entries = snapshot.entries + (event.key to GraphEntry(admitted.getOrThrow(), now)),
                failures = snapshot.failures - event.key
            )
        } else if (failure !is CancellationException) {
            snapshot = snapshot.copy(failures = snapshot.failures + (event.key to failure))
        }
    }

    private fun releaseCompletion(event: Event) {
        when (event) {
            is Event.CatalogFinished -> catalogRequest?.takeIf { it.requestId == event.requestId }?.let {
                it.disposed.set(true)
                catalogRequest = null
            }
            is Event.TabFinished -> tabRequests[event.key]?.takeIf { it.requestId == event.requestId }?.let {
                it.disposed.set(true)
                tabRequests.remove(event.key)
            }
            else -> Unit
        }
    }

    private fun discardRequests() {
        catalogRequest?.disposed?.set(true)
        catalogRequest = null
        tabRequests.values.forEach { it.disposed.set(true) }
        tabRequests.clear()
    }

    private fun publish() {
        snapshot = snapshot.copy(inFlight = tabRequests.keys.toSet())
        mutableState.value = snapshot
    }

    private companion object {
        val KST = TimeZone.of("Asia/Seoul")
    }
}
