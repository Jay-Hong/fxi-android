package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
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

/** Request ownership, cache application and retry deadlines are confined to one consumer. */
internal class GraphV2RequestCoordinator(
    private val fetcher: GraphV2Fetching,
    private val owners: GraphOwnerSource,
    /** Published access fence, read from the loop and from transport threads. */
    private val currentAccessFence: () -> TopicSessionFence?,
    private val uses: TopicUseAuthority,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    /** Stable, finite and non-negative for each tab. */
    private val rateLimitJitter: (tab: String) -> Duration,
    private val onEventFailure: (Throwable) -> Unit,
    private val cachePorts: GraphV2CachePorts? = null
) {
    private data class RequestContext(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

    private data class ProtectedSlot(val entry: GraphEntry, val components: GraphV2DiskComponents)

    private data class ProtectedPublication(
        val context: RequestContext?,
        val slots: Map<GraphKey, ProtectedSlot>
    )

    private data class SeedRegistration(
        val seedId: Long,
        val catalog: GraphCatalog?
    )

    private class Registration(
        val requestId: Long,
        val context: RequestContext,
        val key: GraphKey?,
        val originalTab: String,
        val activityGeneration: Long,
        val accessCapture: GraphV2AccessCapture? = null
    ) {
        // The only request-owned mutable value consulted outside the loop. Job completion is
        // not disposal: the response still has to pass the loop's application boundary.
        val disposed = AtomicBoolean(false)
        // Everything below is loop-confined, including the final capture-to-fetch checkpoint.
        var capturedOwner: AuthSnapshot? = null
        var waitingForFloor = false
        var departed = false
        var coldGeneration: Long? = null
        var coldAttempt = false
        var midnightGeneration: Long? = null
        var midnightAttempt = false
    }

    private class ColdOwner(
        val generation: Long,
        val activityGeneration: Long,
        val key: GraphKey,
        val context: RequestContext,
        var deadline: Instant?,
        var rung: Int = 0,
        var requestId: Long? = null
    )

    private class MidnightOwner(
        val generation: Long,
        val activityGeneration: Long,
        val key: GraphKey,
        val context: RequestContext,
        var deadline: Instant?,
        var cycleStarted: Boolean = false,
        var rung: Int = 0,
        var requestId: Long? = null
    )

    private data class DeferredDemand(
        val key: GraphKey,
        val context: RequestContext,
        val activityGeneration: Long,
        val force: Boolean
    )

    private enum class FailureDisposition { RETRYABLE, TERMINAL, WITHDRAWN, DIAGNOSTIC }

    private sealed interface TabOutcome {
        data class Success(val tab: GraphV2Tab) : TabOutcome
        data class Failure(
            val error: Throwable,
            val disposition: FailureDisposition,
            val statusCode: Int? = null
        ) : TabOutcome
    }

    private sealed interface Event {
        data class Activate(val key: GraphKey) : Event
        data object Deactivate : Event
        data object ContextChanged : Event
        data class Refresh(val force: Boolean) : Event
        data class Captured(val registration: Registration, val result: Result<AuthSnapshot>) : Event
        data class Wake(val generation: Long) : Event
        data class CatalogFinished(
            val requestId: Long,
            val originalTab: String,
            val result: Result<AuthenticatedHttpResponse<GraphV2CatalogResponse>>
        ) : Event
        data class TabFinished(
            val requestId: Long,
            val key: GraphKey,
            val result: Result<AuthenticatedHttpResponse<GraphV2TabResponse>>
        ) : Event
        data class DiskSeedFinished(
            val seedId: Long,
            val key: GraphKey,
            val captured: GraphV2AccessCapture,
            val result: GraphV2DiskSeedResult
        ) : Event
    }

    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(GraphRequestState())
    val state: StateFlow<GraphRequestState> = mutableState.asStateFlow()
    @Volatile private var protectedPublication = ProtectedPublication(null, emptyMap())

    // Loop-confined. The transport guard never reads these maps or the active key.
    private var snapshot = GraphRequestState()
    private var protectedSlots: Map<GraphKey, ProtectedSlot> = emptyMap()
    private var activeKey: GraphKey? = null
    private var context: RequestContext? = null
    private var catalogAt: Instant? = null
    private var catalogRequest: Registration? = null
    private val tabRequests = mutableMapOf<GraphKey, Registration>()
    private var nextRequestId = 0L
    private var activityGeneration = 0L
    private var nextSeedId = 0L
    private var seedRegistration: SeedRegistration? = null
    private var attemptedSeedKey: GraphKey? = null
    private var nextColdGeneration = 0L
    private var coldOwner: ColdOwner? = null
    private var nextMidnightGeneration = 0L
    private var midnightOwner: MidnightOwner? = null
    private var deferredDemand: DeferredDemand? = null
    // Written by the loop, also read by the transport's replay guard. Context disposal and
    // deactivation never reset it; an in-flight answer still uses its separate ownership check.
    @Volatile private var sharedRetryFloor: Instant? = null
    private var timer: Job? = null
    private var timerDeadline: Instant? = null
    private var timerGeneration = 0L

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
                        cancelCold()
                        cancelMidnight()
                        cancelTimer()
                        publish()
                        onEventFailure(failure)
                    }
                }
            } finally {
                discardSeed()
                cancelTimer()
                cancelCold()
                cancelMidnight()
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

    /** A single publication pairs each entry with its components and the current use context. */
    fun protectedEntry(key: GraphKey): GraphEntry? {
        val ports = cachePorts ?: return null
        if (!scope.isActive) return null
        val publication = protectedPublication
        val current = publication.context ?: return null
        val slot = publication.slots[key] ?: return null
        val captured = ports.gate.bind(current.fence, current.lifetime) ?: return null
        val joined = ports.gate.joinForExposure(captured, slot.components) ?: return null
        return GraphEntry(joined.tab, slot.entry.online200At)
    }

    private fun handle(event: Event) {
        val now = clock.now()
        when (event) {
            is Event.Activate -> {
                synchronizeContext()
                if (activeKey != event.key) cancelActiveDemand()
                activeKey = event.key
                startDiskSeed()
                requestActive(now, force = false)
            }
            Event.Deactivate -> {
                synchronizeContext()
                cancelActiveDemand()
                activeKey = null
            }
            Event.ContextChanged -> {
                val changed = synchronizeContext()
                startDiskSeed()
                if (changed) requestActive(now, force = false)
            }
            is Event.Refresh -> {
                synchronizeContext()
                startDiskSeed()
                requestActive(now, event.force)
            }
            is Event.Captured -> onCaptured(event, now)
            is Event.Wake -> onWake(event, now)
            is Event.CatalogFinished -> applyCatalog(event, now)
            is Event.TabFinished -> applyTab(event, now)
            is Event.DiskSeedFinished -> applyDiskSeed(event)
        }
        rearmTimer(now)
        publish()
    }

    /** Independent of HTTP ownership and its Retry-After floor. */
    private fun startDiskSeed() {
        val ports = cachePorts ?: return
        val key = activeKey ?: return
        val current = context ?: return
        if (GraphV2Domain.support(snapshot.catalog, key.tab, key.period) == GraphPeriodSupport.Unsupported ||
            snapshot.entries[key] != null || attemptedSeedKey == key
        ) return
        // A failed bind spends no attempt in this generation, even though IDs are never reused.
        val seedId = Math.incrementExact(nextSeedId)
        nextSeedId = seedId
        val captured = try {
            ports.gate.bind(current.fence, current.lifetime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            seedDiagnostic(seedId, key, GraphV2DiskComponent.GENERAL, "Seed access binding failed", failure)
            return
        } ?: return
        val registration = SeedRegistration(seedId, snapshot.catalog)
        seedRegistration = registration
        attemptedSeedKey = key
        scope.launch {
            val result = readGraphV2DiskSeed(ports, key, captured, registration.catalog)
            inbox.trySend(Event.DiskSeedFinished(seedId, key, captured, result))
        }
    }

    /** Cache failures must not enter the event catch that cancels HTTP retry owners. */
    private fun applyDiskSeed(event: Event.DiskSeedFinished) {
        try {
            val ports = cachePorts ?: return
            val registration = seedRegistration
            if (registration == null || registration.seedId != event.seedId || !scope.isActive) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed ownership expired")
                return
            }
            // Only this owner is released; an older completion cannot remove a newer one.
            seedRegistration = null
            diagnoseSeedRead(event, GraphV2DiskComponent.GENERAL, event.result.general)
            diagnoseSeedRead(event, GraphV2DiskComponent.KRX, event.result.krx)
            val general = when (val read = event.result.general) {
                is GraphV2DiskRead.Found -> read.envelope
                else -> return
            }
            if (snapshot.entries[event.key] != null) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed slot is already occupied")
                return
            }
            val krx = when (val read = event.result.krx) {
                is GraphV2DiskRead.Found -> read.envelope
                else -> null
            }
            val joined = ports.gate.joinForExposure(event.captured, GraphV2DiskComponents(general, krx))
            if (joined == null) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed exposure admission is closed")
                return
            }
            val entry = GraphEntry(joined.tab, online200At = null)
            snapshot = snapshot.copy(entries = snapshot.entries + (event.key to entry))
            protectedSlots = protectedSlots + (event.key to ProtectedSlot(entry, GraphV2DiskComponents(general, krx)))
            joined.ignoredKrxReason?.let {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.KRX, it)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed application failed", failure)
        }
    }

    private fun diagnoseSeedRead(
        event: Event.DiskSeedFinished,
        component: GraphV2DiskComponent,
        read: GraphV2DiskRead<*>?
    ) {
        when (read) {
            is GraphV2DiskRead.Rejected -> seedDiagnostic(event.seedId, event.key, component, read.reason)
            is GraphV2DiskRead.Failed -> seedDiagnostic(event.seedId, event.key, component, "Seed read failed", read.cause)
            else -> Unit // Absence and an unattempted KRX read are normal cache misses.
        }
    }

    private fun seedDiagnostic(
        seedId: Long,
        key: GraphKey,
        component: GraphV2DiskComponent,
        reason: String,
        cause: Throwable? = null
    ) {
        val ports = cachePorts ?: return
        try {
            ports.onSeedDiagnostic(GraphV2SeedDiagnostic(seedId, key, component, reason, cause))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // This observer grants no authority; its failure cannot cancel or change HTTP demand.
            return
        }
    }

    private fun discardSeed() {
        seedRegistration = null
        attemptedSeedKey = null
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

        cancelActiveDemand()
        discardRequests()
        context = next
        catalogAt = null
        if (scopeChanged) protectedSlots = emptyMap()
        snapshot = GraphRequestState(
            dataScope = dataScope,
            entries = if (scopeChanged) emptyMap() else snapshot.entries
        )
        return true
    }

    private fun requestActive(
        now: Instant,
        force: Boolean,
        coldAttempt: Boolean = false,
        midnightAttempt: Boolean = false
    ) {
        val key = activeKey ?: return
        val current = context ?: return
        scheduleMidnight(now)

        // Catalog refresh is independent of tab freshness and never blocks the tab request.
        val lastCatalogAt = catalogAt
        val needsCatalog = catalogRequest == null && (snapshot.catalog == null || lastCatalogAt == null ||
            now - lastCatalogAt >= GraphV2Domain.ttl(snapshot.catalog))
        val supported = GraphV2Domain.support(snapshot.catalog, key.tab, key.period) != GraphPeriodSupport.Unsupported
        val cold = coldOwner?.takeIf { it.key == key && it.context == current }
        val midnight = midnightOwner?.takeIf { it.key == key && it.context == current }
        val needsTab = supported && !tabRequests.containsKey(key) &&
            (force || (cold == null && midnight?.cycleStarted != true && !isFresh(snapshot.entries[key], now)))

        if (underFloor(now)) {
            if (needsCatalog || needsTab) {
                val previous = deferredDemand
                deferredDemand = DeferredDemand(key, current, activityGeneration, force || previous?.force == true)
            }
            return
        }
        if (needsCatalog) startCatalog(current, key.tab)
        if (!supported) {
            if (coldAttempt) cancelCold()
            if (midnightAttempt) cancelMidnight()
            return
        }
        tabRequests[key]?.let {
            if (coldAttempt) connectCold(it, attempt = true)
            if (midnightAttempt) connectMidnight(it, attempt = true)
            return
        }
        if (needsTab) startTab(current, key, coldAttempt, midnightAttempt)
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

    private fun sendAdmitted(registration: Registration): Boolean {
        if (!useAdmitted(registration)) return false
        val floor = sharedRetryFloor ?: return true
        // This is the transport's live send checkpoint, separate from the event's captured now.
        return clock.now() >= floor
    }

    private suspend fun captureOwner(registration: Registration): AuthSnapshot {
        if (!useAdmitted(registration)) throw CancellationException("Graph request use withdrawn")
        val owner = owners.capture(registration.context.fence.identity)
        if (owner.fence != registration.context.fence.identity) throw AuthIdentityChangedException()
        if (!useAdmitted(registration)) throw CancellationException("Graph request use withdrawn")
        return owner
    }

    private fun startCatalog(current: RequestContext, originalTab: String) {
        val registration = Registration(++nextRequestId, current, null, originalTab, activityGeneration)
        catalogRequest = registration
        startCapture(registration)
    }

    private fun startTab(current: RequestContext, key: GraphKey, coldAttempt: Boolean, midnightAttempt: Boolean) {
        // Registration is the logical start; changing the active key does not withdraw a sent request.
        val registration = Registration(
            ++nextRequestId, current, key, key.tab, activityGeneration, bindRequestAccess(current)
        )
        tabRequests[key] = registration
        connectCold(registration, coldAttempt)
        connectMidnight(registration, midnightAttempt)
        startCapture(registration)
    }

    private fun bindRequestAccess(current: RequestContext): GraphV2AccessCapture? = try {
        cachePorts?.gate?.bind(current.fence, current.lifetime)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A failed access input admits no KRX; the GENERAL-only fallback leaves HTTP ownership intact.
        null
    }

    private fun startCapture(registration: Registration) {
        scope.launch {
            val result = runCatching { captureOwner(registration) }
            inbox.trySend(Event.Captured(registration, result))
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    }

    private fun onCaptured(event: Event.Captured, now: Instant) {
        val registration = event.registration
        val error = event.result.exceptionOrNull()
        if (error != null) {
            // A failed capture can itself carry HTTP evidence after an identity change.
            if (registration.key == null) {
                applyCatalog(Event.CatalogFinished(registration.requestId, registration.originalTab, Result.failure(error)), now)
            } else {
                applyTab(Event.TabFinished(registration.requestId, registration.key, Result.failure(error)), now)
            }
            return
        }
        if (!isRegistered(registration)) return
        registration.capturedOwner = event.result.getOrThrow()
        dispatchCaptured(registration, now)
    }

    private fun isRegistered(registration: Registration): Boolean =
        if (registration.key == null) catalogRequest === registration
        else tabRequests[registration.key] === registration

    private fun dispatchCaptured(registration: Registration, now: Instant) {
        if (!isRegistered(registration) || registration.departed) return
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            endConnectedCold(registration)
            endConnectedMidnight(registration)
            return
        }
        val owner = registration.capturedOwner ?: return
        if (underFloor(now)) {
            // Deferred sends are demand too: a later key transition must not revive this one.
            if (registration.activityGeneration != activityGeneration || activeKey == null) {
                releaseRegistration(registration)
                endConnectedCold(registration)
                endConnectedMidnight(registration)
            } else {
                registration.waitingForFloor = true
            }
            return
        }
        registration.waitingForFloor = false
        registration.departed = true
        // Enter fetch immediately at the loop's final floor checkpoint. No queued child may
        // slip a new floor between this admission and entering the fetcher after capture.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val key = registration.key
            if (key == null) {
                val result = runCatching { fetcher.catalog(owner) { sendAdmitted(registration) } }
                inbox.trySend(Event.CatalogFinished(registration.requestId, registration.originalTab, result))
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            } else {
                val result = runCatching { fetcher.tab(owner, key) { sendAdmitted(registration) } }
                inbox.trySend(Event.TabFinished(registration.requestId, key, result))
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            }
        }
    }

    private fun applyCatalog(event: Event.CatalogFinished, now: Instant) {
        recordRetryFloor(event.result, event.originalTab, now)
        val registration = catalogRequest?.takeIf { it.requestId == event.requestId } ?: return
        if (event.result.exceptionOrNull() is CancellationException) {
            releaseCompletion(event)
            return
        }
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            return
        }
        releaseRegistration(registration)
        val response = event.result.getOrNull() ?: return
        if (response.statusCode != 200) return
        val body = response.body ?: return
        snapshot = snapshot.copy(catalog = GraphV2Domain.catalog(body))
        catalogAt = now
        // No pump on completion: failure or absence alone must never start another request.
    }

    private fun applyTab(event: Event.TabFinished, now: Instant) {
        recordRetryFloor(event.result, event.key.tab, now)
        val registration = tabRequests[event.key]?.takeIf { it.requestId == event.requestId } ?: return
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            endConnectedCold(registration)
            endConnectedMidnight(registration)
            return
        }
        releaseRegistration(registration)
        when (val outcome = tabOutcome(event.result, event.key)) {
            is TabOutcome.Success -> {
                val entry = GraphEntry(outcome.tab, now)
                snapshot = snapshot.copy(
                    entries = snapshot.entries + (event.key to entry),
                    failures = snapshot.failures - event.key
                )
                replaceOnlineSlot(registration, event.key, entry)
                endConnectedCold(registration)
                if (connectedMidnight(registration) != null) finishMidnight(now)
            }
            is TabOutcome.Failure -> {
                if (outcome.disposition != FailureDisposition.WITHDRAWN) {
                    snapshot = snapshot.copy(failures = snapshot.failures + (event.key to outcome.error))
                }
                onColdFailure(registration, outcome, now)
                onMidnightFailure(registration, outcome, now)
                if (outcome.disposition == FailureDisposition.DIAGNOSTIC) onEventFailure(outcome.error)
            }
        }
    }

    private fun replaceOnlineSlot(registration: Registration, key: GraphKey, entry: GraphEntry) {
        // A failed conversion must never pair a new raw entry with the previous components.
        protectedSlots = protectedSlots - key
        if (cachePorts == null) return
        val fence = registration.context.fence
        val epoch = fence.userAccessEpoch ?: return
        val components = try {
            when (val split = splitGraphV2ServerTab(
                entry.tab,
                GraphV2GeneralKey(fence.identity.uid, epoch, key.tab, key.period.code),
                registration.accessCapture?.krxCapabilityEpoch,
                UUID.randomUUID().toString(),
                snapshot.catalog
            )) {
                is GraphV2Validation.Valid -> split.value
                is GraphV2Validation.Invalid -> return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The slot is already absent: fail closed without changing the adopted entry or retry owners.
            return
        }
        protectedSlots = protectedSlots + (key to ProtectedSlot(entry, components))
    }

    /** Classification belongs to the transport/decode/admission boundary, never to cached errors. */
    private fun tabOutcome(result: Result<AuthenticatedHttpResponse<GraphV2TabResponse>>, key: GraphKey): TabOutcome {
        result.exceptionOrNull()?.let { error ->
            return when (error) {
                is AuthIdentityChangedException, is CancellationException, is AuthUnavailableException ->
                    TabOutcome.Failure(error, FailureDisposition.WITHDRAWN)
                is AuthenticatedApiException ->
                    TabOutcome.Failure(error, httpDisposition(error.failure.statusCode), error.failure.statusCode)
                is AuthenticatedBodyDecodingException -> {
                    val response = error.response
                    TabOutcome.Failure(error, decodeDisposition(response), response.statusCode)
                }
                is IOException -> TabOutcome.Failure(error, FailureDisposition.RETRYABLE)
                else -> TabOutcome.Failure(error, FailureDisposition.DIAGNOSTIC)
            }
        }
        val response = result.getOrThrow()
        if (response.statusCode != 200) {
            val error = response.failure?.let { AuthenticatedApiException(it) }
                ?: IOException("Graph tab returned HTTP ${response.statusCode}; expected 200")
            return TabOutcome.Failure(error, httpDisposition(response.statusCode), response.statusCode)
        }
        val body = response.body ?: return TabOutcome.Failure(
            IOException("Graph tab returned an empty 200 body"), decodeDisposition(response), 200
        )
        return try {
            when (val admission = GraphV2Domain.admit(body, key.tab, key.period, snapshot.catalog)) {
                is GraphTabAdmission.Accepted -> TabOutcome.Success(admission.tab)
                is GraphTabAdmission.Rejected -> TabOutcome.Failure(IOException(admission.reason), FailureDisposition.TERMINAL)
            }
        } catch (error: Throwable) {
            TabOutcome.Failure(error, FailureDisposition.DIAGNOSTIC)
        }
    }

    private fun httpDisposition(statusCode: Int): FailureDisposition =
        if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) FailureDisposition.RETRYABLE
        else FailureDisposition.TERMINAL

    private fun decodeDisposition(response: AuthenticatedHttpResponse<*>): FailureDisposition =
        if (response.statusCode != 200) httpDisposition(response.statusCode)
        else if (response.rawBodyBytes().isEmpty()) FailureDisposition.RETRYABLE
        else FailureDisposition.TERMINAL

    private fun recordRetryFloor(result: Result<AuthenticatedHttpResponse<*>>, originalTab: String, now: Instant) {
        val response = result.getOrNull()
        if (response != null) {
            recordRetryFloor(response.statusCode, response.retryAfter, originalTab, now)
            return
        }
        when (val error = result.exceptionOrNull()) {
            is AuthenticatedApiException -> recordRetryFloor(error.failure.statusCode, error.failure.retryAfter, originalTab, now)
            is AuthenticatedBodyDecodingException -> recordRetryFloor(error.response.statusCode, error.response.retryAfter, originalTab, now)
            is AuthIdentityChangedException -> {
                if (error.exchanges.isEmpty()) {
                    recordRetryFloor(error.statusCode, error.retryAfter, originalTab, now)
                } else {
                    // The scalar fields repeat the last exchange; do not count that response twice.
                    error.exchanges.forEach { recordRetryFloor(it.statusCode, it.retryAfter, originalTab, now) }
                }
            }
        }
    }

    private fun recordRetryFloor(statusCode: Int?, header: String?, originalTab: String, now: Instant) {
        // Pass the original header unchanged: all parsing, including HTTP-date, belongs to this policy.
        val stated = FreeSnapshotSchedulePolicy.retryFloorAfter(now, header)
            ?: if (statusCode == 429) now + COLD_INTERVALS.first() else return
        // Instant.plus saturates at its representable bound, including when adding finite jitter.
        val floor = stated + jitterFor(originalTab)
        sharedRetryFloor = maxOf(sharedRetryFloor ?: floor, floor)
        if (underFloor(now)) {
            // Captures not yet completed must also remember that their demand was deferred.
            // Otherwise a key switch before capture finishes could revive them after expiry.
            val pending = listOfNotNull(catalogRequest) + tabRequests.values.toList()
            pending.filter { !it.departed }.forEach { it.waitingForFloor = true }
        }
    }

    private fun jitterFor(tab: String): Duration = rateLimitJitter(tab).also {
        require(it.isFinite() && it >= Duration.ZERO) { "Graph rate-limit jitter must be finite and non-negative" }
    }

    private fun underFloor(now: Instant): Boolean = sharedRetryFloor?.let { now < it } == true

    private fun connectCold(registration: Registration, attempt: Boolean) {
        val cold = coldOwner ?: return
        if (registration.key != cold.key || registration.context != cold.context ||
            registration.activityGeneration != cold.activityGeneration) return
        registration.coldGeneration = cold.generation
        registration.coldAttempt = attempt
        cold.requestId = registration.requestId
        if (attempt) cold.deadline = null
    }

    private fun connectedCold(registration: Registration): ColdOwner? = coldOwner?.takeIf {
        it.generation == registration.coldGeneration && it.requestId == registration.requestId &&
            it.key == registration.key && it.context == registration.context
    }

    private fun endConnectedCold(registration: Registration) {
        if (connectedCold(registration) != null) cancelCold()
    }

    private fun onColdFailure(registration: Registration, failure: TabOutcome.Failure, now: Instant) {
        val cold = connectedCold(registration)
        if (failure.disposition != FailureDisposition.RETRYABLE) {
            if (cold != null) cancelCold()
            return
        }
        val key = registration.key ?: return
        // A midnight due may join a still-admitted request sent before a key switch away
        // and back. Its cold handoff belongs to the current midnight activation.
        val requestActivity = connectedMidnight(registration)?.activityGeneration ?: registration.activityGeneration
        if (activeKey != key || context != registration.context ||
            activityGeneration != requestActivity || snapshot.entries[key]?.online200At != null) {
            if (cold != null) cancelCold()
            return
        }
        if (cold == null) {
            // A superseded or unrelated request cannot reset an existing owner.
            if (coldOwner != null) return
            coldOwner = ColdOwner(
                ++nextColdGeneration, activityGeneration, key, registration.context,
                coldDeadline(now, 0, failure.statusCode, key.tab)
            )
        } else {
            cold.requestId = null
            // Outside force failures preserve the original rung and deadline.
            if (!registration.coldAttempt) return
            cold.rung++
            if (cold.rung == COLD_INTERVALS.size) {
                cancelCold()
            } else {
                cold.deadline = coldDeadline(now, cold.rung, failure.statusCode, key.tab)
            }
        }
    }

    private fun coldDeadline(now: Instant, rung: Int, statusCode: Int?, tab: String): Instant =
        now + COLD_INTERVALS[rung] + if (statusCode == 429) jitterFor(tab) else Duration.ZERO

    private fun nextMidnight(now: Instant): Instant {
        val today = now.toLocalDateTime(KST).date.atTime(0, 2).toInstant(KST)
        return if (now < today) today else today + 1.days
    }

    private fun scheduleMidnight(now: Instant) {
        if (midnightOwner != null) return
        val key = activeKey ?: return
        val current = context ?: return
        if (key.period == GraphPeriod.ONE_DAY ||
            GraphV2Domain.support(snapshot.catalog, key.tab, key.period) == GraphPeriodSupport.Unsupported) return
        midnightOwner = MidnightOwner(
            ++nextMidnightGeneration, activityGeneration, key, current, nextMidnight(now)
        )
    }

    private fun finishMidnight(now: Instant) {
        cancelMidnight()
        scheduleMidnight(now)
    }

    private fun connectMidnight(registration: Registration, attempt: Boolean) {
        val midnight = midnightOwner ?: return
        if ((!attempt && !midnight.cycleStarted) || registration.key != midnight.key ||
            registration.context != midnight.context || midnight.activityGeneration != activityGeneration) return
        // A still-valid request from an earlier activation may be joined, too. Ownership of
        // today's requirement is the current midnight generation, not the send's activation.
        registration.midnightGeneration = midnight.generation
        registration.midnightAttempt = attempt
        midnight.requestId = registration.requestId
        if (attempt) {
            midnight.cycleStarted = true
            midnight.deadline = null
        }
    }

    private fun connectedMidnight(registration: Registration): MidnightOwner? = midnightOwner?.takeIf {
        it.generation == registration.midnightGeneration && it.requestId == registration.requestId &&
            it.key == registration.key && it.context == registration.context &&
            it.activityGeneration == activityGeneration
    }

    private fun endConnectedMidnight(registration: Registration) {
        if (connectedMidnight(registration) != null) cancelMidnight()
    }

    private fun onMidnightFailure(registration: Registration, failure: TabOutcome.Failure, now: Instant) {
        val midnight = connectedMidnight(registration) ?: return
        if (failure.disposition == FailureDisposition.WITHDRAWN || failure.disposition == FailureDisposition.DIAGNOSTIC) {
            cancelMidnight()
            return
        }
        if (snapshot.entries[midnight.key]?.online200At == null) {
            // onColdFailure owns a retryable unconfirmed result; no midnight ladder overlaps it.
            finishMidnight(now)
            return
        }
        midnight.requestId = null
        // An outside force failure leaves the pending midnight interval and budget intact.
        if (!registration.midnightAttempt) return
        if (midnight.rung == MIDNIGHT_INTERVALS.size) {
            finishMidnight(now)
        } else {
            midnight.deadline = now + MIDNIGHT_INTERVALS[midnight.rung++] +
                if (failure.statusCode == 429) jitterFor(midnight.key.tab) else Duration.ZERO
        }
    }

    private fun onWake(event: Event.Wake, now: Instant) {
        if (event.generation != timerGeneration) return
        timer = null
        timerDeadline = null
        synchronizeContext()

        // Only saved demand is resumed. A floor with no demand never arms a timer or sends a GET.
        val waiting = listOfNotNull(catalogRequest) + tabRequests.values.toList()
        waiting.filter { it.waitingForFloor }.forEach { dispatchCaptured(it, now) }
        deferredDemand?.let { demand ->
            deferredDemand = null
            if (demand.key == activeKey && demand.context == context &&
                demand.activityGeneration == activityGeneration) requestActive(now, demand.force)
        }

        wakeCold(now)
        wakeMidnight(now)
    }

    private fun wakeCold(now: Instant) {
        val cold = coldOwner ?: return
        if (cold.key != activeKey || cold.context != context || cold.activityGeneration != activityGeneration ||
            snapshot.entries[cold.key]?.online200At != null) {
            cancelCold()
            return
        }
        val deadline = cold.deadline ?: return
        if (now < deadline || underFloor(now)) return
        // An outside force request can be joined when due; it is never duplicated.
        requestActive(now, force = true, coldAttempt = true)
    }

    private fun wakeMidnight(now: Instant) {
        val midnight = midnightOwner ?: return
        if (midnight.key != activeKey || midnight.context != context ||
            midnight.activityGeneration != activityGeneration) {
            cancelMidnight()
            return
        }
        val deadline = midnight.deadline ?: return
        if (now < deadline) return
        tabRequests[midnight.key]?.let {
            // Joining does not send, so even a shared floor cannot postpone the due's join.
            connectMidnight(it, attempt = true)
            return
        }
        if (!underFloor(now)) requestActive(now, force = true, midnightAttempt = true)
    }

    private fun cancelCold() { coldOwner = null }
    private fun cancelMidnight() { midnightOwner = null }

    private fun cancelActiveDemand() {
        activityGeneration++
        discardSeed()
        cancelCold()
        cancelMidnight()
        deferredDemand = null
        // Already sent requests retain A2a ownership of their own cache entry. Unsent floor
        // demand belongs to the old activation and must be disposed on a real transition.
        val waiting = listOfNotNull(catalogRequest) + tabRequests.values.toList()
        waiting.filter { it.waitingForFloor }.forEach { releaseRegistration(it) }
    }

    private fun rearmTimer(now: Instant) {
        val candidates = mutableListOf<Instant>()
        fun afterFloor(deadline: Instant): Instant = maxOf(deadline, sharedRetryFloor ?: deadline)
        coldOwner?.takeIf { it.requestId == null }?.deadline?.let { candidates += afterFloor(it) }
        midnightOwner?.takeIf { it.requestId == null }?.let { midnight ->
            midnight.deadline?.let { deadline ->
                candidates += if (tabRequests.containsKey(midnight.key)) deadline else afterFloor(deadline)
            }
        }
        if (deferredDemand != null) sharedRetryFloor?.let { candidates += it }
        fun readyAtFloor(registration: Registration): Boolean =
            registration.waitingForFloor && registration.capturedOwner != null
        if (catalogRequest?.let { readyAtFloor(it) } == true || tabRequests.values.any { readyAtFloor(it) }) {
            sharedRetryFloor?.let { candidates += it }
        }
        val next = candidates.minOrNull()
        if (next == timerDeadline && timer?.isActive == true) return
        cancelTimer()
        if (next == null) return
        timerDeadline = next
        val generation = timerGeneration
        timer = scope.launch {
            delay(maxOf(next - now, Duration.ZERO))
            inbox.trySend(Event.Wake(generation))
        }
    }

    private fun cancelTimer() {
        timerGeneration++
        timer?.cancel()
        timer = null
        timerDeadline = null
    }

    private fun releaseRegistration(registration: Registration) {
        registration.disposed.set(true)
        if (registration.key == null) {
            if (catalogRequest === registration) catalogRequest = null
        } else if (tabRequests[registration.key] === registration) {
            tabRequests.remove(registration.key)
        }
    }

    private fun releaseCompletion(event: Event) {
        when (event) {
            is Event.CatalogFinished -> catalogRequest?.takeIf { it.requestId == event.requestId }?.let {
                releaseRegistration(it)
            }
            is Event.TabFinished -> tabRequests[event.key]?.takeIf { it.requestId == event.requestId }?.let {
                releaseRegistration(it)
                endConnectedCold(it)
                endConnectedMidnight(it)
            }
            is Event.Captured -> if (isRegistered(event.registration)) releaseRegistration(event.registration)
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
        protectedPublication = ProtectedPublication(context, protectedSlots)
        mutableState.value = snapshot
    }

    private companion object {
        val KST = TimeZone.of("Asia/Seoul")
        val COLD_INTERVALS = listOf(3.seconds, 6.seconds, 12.seconds, 24.seconds, 48.seconds)
        val MIDNIGHT_INTERVALS = listOf(20.seconds, 40.seconds, 80.seconds)
    }
}
