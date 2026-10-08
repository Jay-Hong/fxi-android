package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseWithheldException
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
import java.util.concurrent.atomic.AtomicReference
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

internal enum class GraphRuntimeRetirement { REMOVED, NOTHING_TO_REMOVE, LIVE_SCOPE_SELECTED }

/** A KRX capability namespace; a null epoch names raw KRX the coordinator cannot attribute. */
internal data class GraphCapabilityScope(val uid: String, val krxCapabilityEpoch: String?)

/** S4 RT03b-0: the request context a scoped snapshot's catalog, failures and in-flight keys belong to. */
internal data class GraphRequestSource(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

/**
 * S4 RT03b-3a: an RT05 trigger that may resume or reopen 1d recovery. One producer per coordinator numbers all kinds with
 * one strictly increasing [sequence] from 1; it is valid only for the current fence and use it names.
 */
internal data class GraphRecoveryTrigger(
    val kind: Kind,
    val sequence: Long,
    val fence: TopicSessionFence,
    val lifetime: TopicUseLifetime
) {
    enum class Kind { FOREGROUND_RETURN, RECONNECT, BOUNDARY_600S }
}

/**
 * When a context synchronization builds a new snapshot, [source] is the request context it acquired, or null when it
 * acquired none (no fence or epoch, an identity mismatch, or a refused use), even with a non-null [dataScope]. Every
 * copy keeps it; the retirement's empty snapshot has none. Entries are exposed through
 * [GraphV2RequestCoordinator.protectedEntry] and the live gate, whatever the source.
 */
internal data class GraphRequestState(
    val dataScope: GraphDataScope? = null,
    val catalog: GraphCatalog? = null,
    val entries: Map<GraphKey, GraphEntry> = emptyMap(),
    val inFlight: Set<GraphKey> = emptySet(),
    val failures: Map<GraphKey, Throwable> = emptyMap(),
    val source: GraphRequestSource? = null
)

/**
 * Request ownership, cache application and retry deadlines are confined to one consumer.
 *
 * When [recorder] is supplied, the coordinator loop, every recorder method, the control collector
 * and the sink worker must use the same serial executor. RT01 must verify this wiring.
 *
 * [start], [close], [retireScopes] and [retireCapabilities] must run on the loop's serial executor
 * whether or not [recorder] is supplied, because they mutate loop-confined state (in the assembly,
 * its injected always-dispatching Main). Retirement runs between events, without re-entering a
 * loop operation from a supplier or callback. Write children launch on this same executor; store
 * I/O may run elsewhere and reads only atomic ownership flags, the coordinator scope's active state,
 * immutable captured values and the live gate.
 * [close] ends input and releases ownership synchronously, even before the loop's first dispatch.
 * The loop's finally uses the same idempotent cleanup; the supplied scope is not cancelled.
 *
 * @param protectedAdmission Live admission supplier consulted at each otherwise-admitted use check.
 * It is also invoked on transport threads and must be thread-safe, non-blocking and side-effect free.
 * @param accessSnapshot Lock-free, live read of the issuer's published topic access snapshot; use
 * the same supplier function object as the gate and recorder. A new USER end retires request,
 * write and seed ownership and clears entries, protected slots, catalog, failures and every 1d
 * recovery budget even within the same data scope. Duplicate ends do not discard again; context
 * changes without a new end retain same-scope entries, protected slots and recovery budgets.
 * @param recorder Optional, immutable owner that captures recovery for existing one-day requests,
 * applies their successful responses synchronously and replays held inputs after catalog publication.
 * With a recorder, a one-day key's cold ladder and independent flip check give way to a recovery
 * budget per (data scope, tab) (S4 RT03b-1b): it reads the recorder's demand on the loop, issues its
 * own forced rounds at most six times per cycle, 3/6/12/24/48 s after each completion (a valid RT05 trigger
 * resumes, reopens or opens a cycle, S4 RT03b-3a), and holds back unforced
 * requests of its key while it waits (not while parked on the S4 RT03b-3b permit for the same context). A round that needs a catalog waits for the one already out or asks
 * for its own once; its tab goes after an applicable completion of that catalog, or once its catalog slot
 * is spent and no catalog is out, unless an outside tab it joined settles it first (S4 RT03b-2b). It survives
 * Deactivate, key switches and a missing data scope; a USER end, another non-null data scope, a
 * selecting [retireScopes] and [close] discard it. A context change within the data scope of the last
 * context since the last USER end asks for no catalog (S4 RT03b-2a); another scope, a USER end and the
 * first context still do.
 * Other periods, and a coordinator without a recorder, keep the cold, flip and midnight owners.
 */
internal class GraphV2RequestCoordinator(
    private val fetcher: GraphV2Fetching,
    private val owners: GraphOwnerSource,
    /** Published access fence, read from the loop and from transport threads. */
    private val currentAccessFence: () -> TopicSessionFence?,
    private val uses: TopicUseAuthority,
    private val protectedAdmission: () -> Boolean,
    private val accessSnapshot: () -> TopicAccessSnapshot,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    /** Stable, finite and non-negative for each tab. */
    private val rateLimitJitter: (tab: String) -> Duration,
    private val onEventFailure: (Throwable) -> Unit,
    private val cachePorts: GraphV2CachePorts? = null,
    private val recorder: GraphRecorder? = null,
    /**
     * S4 RT03b-3b: the session's live P7 permit, read on the loop and on transport threads (thread-safe, non-blocking).
     * Null keeps every automatic recovery ungated, as before; an installed supplier that returns null closes it.
     */
    private val recoveryPermit: (() -> TopicGraphRecoveryPermit?)? = null
) {
    private data class RequestContext(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

    private data class ProtectedSlot(
        val entry: GraphEntry,
        val components: GraphV2DiskComponents,
        val supplementGeneration: Long? = null
    )

    private data class ProtectedPublication(
        val context: RequestContext?,
        val slots: Map<GraphKey, ProtectedSlot>
    )

    private data class SeedRegistration(
        val seedId: Long,
        val key: GraphKey,
        val context: RequestContext,
        val activityGeneration: Long,
        val catalog: GraphCatalog?,
        val configuration: GraphV2CapabilityConfiguration,
        val mode: GraphV2SeedMode
    )

    private class WriteTask(
        val writeId: Long,
        val ticket: GraphV2WriteTicket,
        val key: GraphKey,
        val captured: GraphV2AccessCapture,
        val wantsKrx: Boolean
    ) {
        // I/O threads read only these flags, the scope's active state, immutable captured values and the live gate.
        val enabled = AtomicBoolean(true)
        // Capability retirement ends only KRX ownership, which is never restored on this task.
        val krxOwned = AtomicBoolean(wantsKrx)
        var job: Job? = null // Loop-confined; independent of HTTP and screen activity.
    }

    private class Registration(
        val requestId: Long,
        val context: RequestContext,
        val key: GraphKey?,
        val originalTab: String,
        val activityGeneration: Long,
        /** Loop-confined; capability retirement replaces only its KRX epoch. */
        var accessCapture: GraphV2AccessCapture? = null,
        recoveryRequests: List<GraphRecoveryRequest> = emptyList(),
        /** S4 RT03b-3b: issued directly by a recovery round; immutable, so transport threads may read it. */
        val automaticRecovery: Boolean = false
    ) {
        // Request-owned mutable values touched outside the loop: [disposed] (read) and [permitRefusal] (set once by a
        // refusing check). Job completion is not disposal: the response still has to pass the loop's application boundary.
        val disposed = AtomicBoolean(false)
        /** S4 RT03b-3b: the first permit publication that refused this request, set by the refusing check on any thread. */
        val permitRefusal = AtomicReference<PermitWait?>(null)
        // Everything below is loop-confined, including the final capture-to-fetch checkpoint.
        var recoveryRequests: List<GraphRecoveryRequest> = recoveryRequests
        var capturedOwner: AuthSnapshot? = null
        var waitingForFloor = false
        var departed = false
        var coldGeneration: Long? = null
        var coldAttempt = false
        var midnightGeneration: Long? = null
        var midnightAttempt = false
        var flipGeneration: Long? = null
        var recoveryRound: RecoveryRound? = null
        var recoveryAttempt = false
        // Includes an enqueued completion until the loop consumes it, not just a running coroutine.
        var completionPending = false
        var handlingCompletion = false
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

    private class FlipOwner(
        val generation: Long,
        val activityGeneration: Long,
        val key: GraphKey,
        val context: RequestContext,
        var deadline: Instant?,
        var requestId: Long? = null
    )

    /** S4 RT03b-1b: the 1d recovery budget of one data scope and tab, kept only with a recorder. */
    private data class RecoveryKey(val scope: GraphDataScope, val tab: String)

    private class RecoveryRound(val roundId: Long, val budget: RecoveryBudget) {
        var tabRequestId: Long? = null
        var tabSent = false
        var catalogRequestId: Long? = null
        var catalogSent = false
        var counted = false
        /** S4 RT03b-2b: the catalog request this round waits for, its own or one it joined without binding it. */
        var awaitedCatalogId: Long? = null
        /**
         * True once the round's catalog check ended: an applicable completion of the awaited catalog, or its catalog slot
         * spent while no catalog is out.
         */
        var catalogChecked = false
    }

    private class RecoveryBudget(val key: RecoveryKey) {
        var rounds = 0
        var completionRung = 0
        var deadline: Instant? = null
        /** S4 RT03b-3a: why the budget stopped; null while it waits. */
        var stop: RecoveryStop? = null
        var round: RecoveryRound? = null
        var hold: RecoveryHold? = null
        /** S4 RT03b-3b: waiting for the permit, until its publication or the context changes. */
        var permitWait: PermitWait? = null
    }

    /** S4 RT03b-3b: the permit publication a budget waits on (null session and revision: no permit) and its context. */
    private data class PermitWait(val sessionKey: Any?, val revision: Long?, val context: RequestContext)

    private enum class RecoveryStop { EXHAUSTED, TERMINAL, PAUSED }

    /**
     * S4 RT03b-3a: a hold in one context and activation, with its cause. A valid trigger releases only an UNREADABLE hold of
     * a waiting budget; resuming a PAUSED budget clears either.
     */
    private data class RecoveryHold(val context: RequestContext, val activityGeneration: Long, val cause: Cause) {
        enum class Cause { UNREADABLE, WITHDRAWN }
    }

    private enum class RecoveryNeed { NONE, UNREADABLE, REMAINS }

    private data class DeferredDemand(
        val key: GraphKey,
        val context: RequestContext,
        val activityGeneration: Long,
        val force: Boolean,
        /**
         * S4 RT03b-2a: a same-scope context change's demand, resumed without a catalog. It is always deferred alone,
         * since its synchronization has just cleared the deferred demand; a later demand of the same context replaces it.
         */
        val catalogSilent: Boolean = false
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
        data class RecoveryTrigger(val trigger: GraphRecoveryTrigger) : Event
        data object RecoveryPermitChanged : Event
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
        data class WritePreparationFinished(
            val ticket: GraphV2WriteTicket,
            val captured: GraphV2AccessCapture,
            val result: Result<GraphV2WritePreparation>
        ) : Event
        data class DiskWriteFinished(
            val ticket: GraphV2WriteTicket,
            val result: Result<GraphV2WriteReport>
        ) : Event
    }

    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private var closed = false
    private var loop: Job? = null
    private val mutableState = MutableStateFlow(GraphRequestState())
    val state: StateFlow<GraphRequestState> = mutableState.asStateFlow()
    @Volatile private var protectedPublication = ProtectedPublication(null, emptyMap())

    // Loop-confined. The transport guard never reads these maps or the active key.
    private var seenUserEnd = accessSnapshot().lastUserEnd?.sequence ?: 0L
    private var snapshot = GraphRequestState()
    private var protectedSlots: Map<GraphKey, ProtectedSlot> = emptyMap()
    private var capabilityConfiguration: GraphV2CapabilityConfiguration? = null
    private var capabilityGeneration = 0L
    private var activeKey: GraphKey? = null
    private var context: RequestContext? = null
    private var catalogAt: Instant? = null
    private var catalogRequest: Registration? = null
    private val tabRequests = mutableMapOf<GraphKey, Registration>()
    private val pendingReleased = mutableMapOf<Long, Registration>()
    private var nextRequestId = 0L
    private var activityGeneration = 0L
    private var nextSeedId = 0L
    private var seedRegistration: SeedRegistration? = null
    private var attemptedSeedKey: GraphKey? = null
    private var attemptedSupplement: GraphV2SeedMode.SupplementOccupied? = null
    private var nextWriteId = 0L
    private val writeTasks = mutableMapOf<GraphV2WriteTicket, WriteTask>()
    private var writePreparationToStart: Job? = null
    private var nextColdGeneration = 0L
    private var coldOwner: ColdOwner? = null
    private var nextMidnightGeneration = 0L
    private var midnightOwner: MidnightOwner? = null
    private var nextFlipGeneration = 0L
    private var flipOwner: FlipOwner? = null
    private var deferredDemand: DeferredDemand? = null
    private val recoveryBudgets = mutableMapOf<RecoveryKey, RecoveryBudget>()
    /**
     * S4 RT03b-2a: the data scope of the last synchronization since the last USER end that obtained a context. One without
     * a context keeps it only for its own scope, and one without a scope keeps it. It classifies catalog issue only.
     */
    private var lastDataScope: GraphDataScope? = null
    /**
     * Written by every synchronization that changes the context: whether it obtained a context within [lastDataScope], with
     * a recorder. Only the ContextChanged handling of the same event reads it.
     */
    private var sameScopeChange = false
    private var nextRecoveryRound = 0L
    /** S4 RT03b-3a: the highest trigger sequence handled, valid or not. */
    private var lastTriggerSequence = 0L
    // Written by the loop, also read by the transport's replay guard. Context disposal and
    // deactivation never reset it; an in-flight answer still uses its separate ownership check.
    @Volatile private var sharedRetryFloor: Instant? = null
    private var timer: Job? = null
    private var timerDeadline: Instant? = null
    private var timerGeneration = 0L

    fun start() {
        if (closed || !started.compareAndSet(false, true)) return
        loop = scope.launch {
            try {
                for (event in inbox) {
                    try {
                        handle(event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        pauseRecovery()
                        // A broken completion must not leave its key permanently claimed.
                        releaseCompletion(event)
                        cancelCold()
                        cancelMidnight()
                        cancelFlip()
                        cancelTimer()
                        publish()
                        onEventFailure(failure)
                    }
                }
            } finally {
                close()
            }
        }
    }

    /**
     * Ends acceptance, discards queued events and recovery budgets, and releases request, write, seed
     * and timer ownership before returning. Calls and the loop's finally share this idempotent cleanup on the serial
     * executor. No supplier is read, no later event is handled, and [scope] itself stays alive.
     */
    fun close() {
        if (closed) return
        closed = true
        inbox.cancel()
        discardWrites()
        writePreparationToStart = null
        discardSeed()
        cancelTimer()
        cancelCold()
        cancelMidnight()
        cancelFlip()
        deferredDemand = null
        recoveryBudgets.clear()
        discardRequests()
        pendingReleased.values.forEach {
            it.completionPending = false
            dropCaptures(it)
        }
        pendingReleased.clear()
        publish()
        loop?.cancel()
    }

    fun onActivated(key: GraphKey) { inbox.trySend(Event.Activate(key)) }
    fun onDeactivated() { inbox.trySend(Event.Deactivate) }
    fun onContextChanged() { inbox.trySend(Event.ContextChanged) }
    fun onRefreshRequested(force: Boolean = false) { inbox.trySend(Event.Refresh(force)) }
    /**
     * S4 RT03b-3a: delivered by RT05; no production caller yet. It is judged against the context already synchronized, so
     * RT05 delivers a context change before a trigger built from it (otherwise that trigger is spent invalid).
     */
    fun onRecoveryTrigger(trigger: GraphRecoveryTrigger) { inbox.trySend(Event.RecoveryTrigger(trigger)) }
    /** S4 RT03b-3b: the permit publication changed; the timer is re-armed from a fresh read. No production caller yet. */
    fun onRecoveryPermitChanged() { inbox.trySend(Event.RecoveryPermitChanged) }

    /**
     * Drops selected recovery captures from registered tab requests, without suspension. It must run
     * on the coordinator's serial executor between loop events: it reads and writes loop-confined
     * state directly and does not dispatch. Only the selected captures go: nothing is released,
     * cancelled or published, and the coordinator handles each answer as before. A purge covers only
     * the captures registered now; a released registration still held by an in-flight request, and
     * the coordinator's entries, protected slots and recovery budgets, are runtime cleanup.
     */
    fun purgeRecoveryCaptures(selects: (GraphDataScope) -> Boolean): Boolean {
        var removed = false
        for (registration in tabRequests.values) {
            val before = registration.recoveryRequests
            val kept = before.filterNot { selects(it.seriesKey.scope) }
            if (kept.size != before.size) {
                registration.recoveryRequests = kept
                removed = true
            }
        }
        return removed
    }

    /**
     * Retires selected USER data scopes synchronously on the loop's serial executor, between events.
     * The caller must establish that every selected epoch really ended, never reuse it or publish
     * its fence again. A null or temporarily held fence alone does not establish retirement; same-
     * epoch USER ends continue through the existing [seenUserEnd] context synchronization path.
     *
     * The non-null scope of [currentAccessFence] at entry is the live scope, independent of local
     * context or credential admission. It joins the distinct scopes held by the snapshot, context,
     * registered and released pending requests (including recovery captures), protected GENERAL
     * slots, seed, writes and 1d recovery budgets. [selects] runs exactly once per candidate unless it
     * throws. All
     * selection and change planning precede mutation: a selector exception propagates unchanged;
     * selecting the live scope returns [GraphRuntimeRetirement.LIVE_SCOPE_SELECTED] without changes.
     * This port does not synchronize or acquire a context.
     *
     * Selected request contexts lose registration and capture references; other contexts lose only
     * selected recovery captures. Released requests remain tracked while capture/fetch work or an
     * unhandled completion remains, including after coroutine exit or enqueue. Handling the
     * completion ends tracking, and [close] ends all of it - also for a completion it leaves
     * undelivered or a request cancelled before its body, which arise only with [close] (directly,
     * or through the scope cancellation that ends the loop through it). A released floor waiter with
     * no work ends immediately. Completion lookup
     * uses requestId/object identity, never a key alone, and grants no renewed application authority.
     * Normal success retains release-before-applyRecovery, then clears references in completion
     * finally after recovery application and handling finish.
     *
     * A selected snapshot becomes an empty [GraphRequestState], including null dataScope. Selected
     * local context, catalog time, capability configuration and retry demand are discarded; selected
     * slots, seed attempts, writes and recovery budgets are removed; a budget held alone still counts
     * as a removal. Writes disable admission, cancel their ticket and
     * job, and release any pending preparation-start reference. Unselected scopes remain intact.
     * Changes to publication/inFlight are published before returning, without starting write
     * preparation, requests or seeds. [activeKey] survives for the next normal context change.
     * [sharedRetryFloor] survives too; late completions delivered to the open inbox still record
     * Retry-After before registration lookup or application rejection.
     *
     * [GraphRuntimeRetirement.REMOVED] means data, references or ownership were newly removed or
     * retired. An already disposed, capture-free request retained only for completion does not count;
     * repeated retirement returns [GraphRuntimeRetirement.NOTHING_TO_REMOVE].
     */
    fun retireScopes(selects: (GraphDataScope) -> Boolean): GraphRuntimeRetirement {
        val live = currentAccessFence()?.let { scopeOf(it) }
        val registrations = (listOfNotNull(catalogRequest) + tabRequests.values + pendingReleased.values).distinct()
        val candidates = linkedSetOf<GraphDataScope>()
        live?.let { candidates += it }
        snapshot.dataScope?.let { candidates += it }
        context?.let { scopeOf(it.fence) }?.let { candidates += it }
        for (registration in registrations) {
            scopeOf(registration.context.fence)?.let { candidates += it }
            registration.recoveryRequests.forEach { candidates += it.seriesKey.scope }
        }
        protectedSlots.values.forEach { candidates += scopeOf(it.components.general.key) }
        seedRegistration?.let { scopeOf(it.context.fence) }?.let { candidates += it }
        writeTasks.values.forEach { scopeOf(it.captured.fence)?.let { held -> candidates += held } }
        recoveryBudgets.keys.forEach { candidates += it.scope }

        val selected = candidates.filter(selects).toSet()
        val retiredRequests = registrations.filter { scopeOf(it.context.fence) in selected }
        val captureUpdates = registrations.filterNot { it in retiredRequests }.map { registration ->
            registration to registration.recoveryRequests.filterNot { it.seriesKey.scope in selected }
        }.filter { (registration, kept) -> kept.size != registration.recoveryRequests.size }
        val keptSlots = protectedSlots.filterValues { scopeOf(it.components.general.key) !in selected }
        val retireSnapshot = snapshot.dataScope?.let { it in selected } == true
        val retireContext = context?.let { scopeOf(it.fence) in selected } == true
        val retireSeed = seedRegistration?.let { scopeOf(it.context.fence) in selected } == true
        val retiredWrites = writeTasks.values.filter { scopeOf(it.captured.fence) in selected }
        val retiredBudgets = recoveryBudgets.keys.filter { it.scope in selected }
        val removed = retireSnapshot || retireContext || retireSeed || retiredWrites.isNotEmpty() || retiredBudgets.isNotEmpty() ||
            keptSlots.size != protectedSlots.size || captureUpdates.isNotEmpty() || retiredRequests.any {
                isRegistered(it) || !it.disposed.get() || it.recoveryRequests.isNotEmpty() || it.capturedOwner != null
            }
        if (live != null && live in selected) return GraphRuntimeRetirement.LIVE_SCOPE_SELECTED
        if (!removed) return GraphRuntimeRetirement.NOTHING_TO_REMOVE

        for (registration in retiredRequests) {
            releaseRegistration(registration)
            dropCaptures(registration)
        }
        captureUpdates.forEach { (registration, kept) -> registration.recoveryRequests = kept }
        retiredBudgets.forEach { recoveryBudgets.remove(it) }
        if (retireSnapshot || retireSeed) discardSeed()
        retiredWrites.forEach { discardWrite(it) }
        protectedSlots = keptSlots
        if (retireSnapshot) snapshot = GraphRequestState()
        if (retireSnapshot || retireContext) {
            context = null
            catalogAt = null
            capabilityConfiguration = null
            cancelCold()
            cancelMidnight()
            cancelFlip()
            cancelTimer()
            deferredDemand = null
        }
        publishState()
        return GraphRuntimeRetirement.REMOVED
    }

    /**
     * Retires selected KRX capability scopes synchronously on the loop's serial executor, between
     * events. It does not dispatch or suspend. The caller must establish that each selected named
     * epoch really ended, and must never reuse or republish it. The gate, [uses] and coordinator
     * must read the same actual issuer's live snapshot: its capability rotation invalidates the
     * old use lifetime. Changing K while keeping that lifetime admitted is outside this contract.
     * A capability hold alone does not establish retirement.
     *
     * Live is the non-null (ownerUid, krxCapabilityEpoch) pair from the recordFence in exactly one
     * [accessSnapshot] read. Both values come from that record, regardless of admission, standing
     * token, binding, session or local context. Live is always a candidate. Other distinct
     * candidates are protected-slot KRX keys, non-null KRX epochs in registered and pendingReleased
     * access captures, and allowed, non-null record epochs in seed registrations and supplement
     * attempt markers. Seeds use their context owner; markers use the snapshot data-scope owner.
     * Without cache ports, raw entries containing a krx. seriesId or inProgress key additionally
     * contribute (data-scope uid, null). This null means unattributed KRX, not a named epoch or the
     * null-epoch sweep request in a purge journal. Existing writes with wantsKrx && krxOwned contribute
     * their captured (uid, krxCapabilityEpoch). Configuration is not a candidate; writes whose KRX
     * ownership was already withdrawn do not contribute again.
     *
     * [selects] runs exactly once per distinct candidate unless it throws. All selection and change
     * planning precede mutation. A selector exception propagates unchanged with no state changes;
     * selecting live returns [GraphRuntimeRetirement.LIVE_SCOPE_SELECTED] with no state changes.
     * This port neither synchronizes nor acquires a context or a replacement capture.
     * All selected writes' store KRX candidates are withdrawn before any coordinator change is
     * applied or published. False store returns still end coordinator KRX ownership. Any store
     * exception, including CancellationException, propagates unchanged: coordinator state and KRX
     * ownership remain intact for retry, but earlier store withdrawals are not rolled back.
     *
     * (a) Selected slots lose only their KRX components. Their raw and protected entries are rebuilt
     * from the same GENERAL envelope, preserving responseId, stamp and supplement generation, across
     * every tab and period, including inactive keys.
     * (b) Selecting the unattributed candidate strips krx. series and current buckets from all raw entries.
     * (c) Selected request captures retain their USER fence and lifetime; only the KRX epoch becomes
     * null. Later admitted completions can adopt and reserve GENERAL only, using the existing
     * configuration confirmation rules. An invalidated lifetime still admits no response or recovery.
     * (d) A selected seed loses its registration and both attempt markers. Its late completion applies
     * neither GENERAL nor KRX. A selected supplement marker alone is cleared without discarding an
     * unselected seed. No seed starts here; normal seed paths may retry with a fresh binding when
     * live admission, context, support and occupancy permit.
     * (e) GENERAL data, capabilityConfiguration, capabilityGeneration, online200At, catalog, context,
     * active demand, retry owners, timer, floor, failures and recovery captures survive. Selected
     * writes keep their ticket, job, capture and GENERAL half; only their store KRX validity and
     * coordinator KRX ownership end. Preparation starts with effective KRX ownership; preparation
     * application reads it once for KRX validation/readiness, and commit admission reads it again.
     * Subsequent normal configuration synchronization retains its existing strip/generation/flip
     * behavior; this port invalidates no stamp and creates no flip, timer or request.
     * (f) Changes are published through [publishState] before returning, without starting preparation.
     *
     * [GraphRuntimeRetirement.REMOVED] means KRX data, a capture epoch or seed/supplement ownership
     * was newly removed, or a write's KRX ownership newly ended, regardless of the store's Boolean.
     * Unless live is selected, a call that newly removes nothing, including a
     * repeated call, returns [GraphRuntimeRetirement.NOTHING_TO_REMOVE].
     */
    fun retireCapabilities(selects: (GraphCapabilityScope) -> Boolean): GraphRuntimeRetirement {
        val record = accessSnapshot().facts.recordFence
        val live = record?.ownerUid?.let { uid ->
            record.krxCapabilityEpoch?.let { GraphCapabilityScope(uid, it) }
        }
        val registrations = (listOfNotNull(catalogRequest) + tabRequests.values + pendingReleased.values).distinct()
        fun captureScope(registration: Registration): GraphCapabilityScope? = registration.accessCapture?.let { captured ->
            captured.krxCapabilityEpoch?.let { GraphCapabilityScope(captured.fence.identity.uid, it) }
        }
        fun slotScope(slot: ProtectedSlot): GraphCapabilityScope? = slot.components.krx?.key?.let {
            GraphCapabilityScope(it.uid, it.krxCapabilityEpoch)
        }
        val seedScope = seedRegistration?.let { seed ->
            seed.configuration.recordEpoch?.takeIf { seed.configuration.allowed }?.let {
                GraphCapabilityScope(seed.context.fence.identity.uid, it)
            }
        }
        val supplementScope = attemptedSupplement?.configuration?.takeIf { it.allowed }?.recordEpoch?.let { epoch ->
            snapshot.dataScope?.uid?.let { GraphCapabilityScope(it, epoch) }
        }
        val unattributed = if (cachePorts != null) null else snapshot.dataScope?.uid?.takeIf {
            snapshot.entries.values.any { hasKrx(it.tab) }
        }?.let { GraphCapabilityScope(it, null) }

        val candidates = linkedSetOf<GraphCapabilityScope>()
        live?.let { candidates += it }
        protectedSlots.values.forEach { slot -> slotScope(slot)?.let { candidates += it } }
        registrations.forEach { registration -> captureScope(registration)?.let { candidates += it } }
        seedScope?.let { candidates += it }
        supplementScope?.let { candidates += it }
        unattributed?.let { candidates += it }
        writeTasks.values.forEach { task -> writeScope(task)?.let { candidates += it } }

        val selected = candidates.filter(selects).toSet()
        if (live != null && live in selected) return GraphRuntimeRetirement.LIVE_SCOPE_SELECTED
        val slotKeys = protectedSlots.filterValues { slotScope(it) in selected }.keys
        val captureUpdates = registrations.filter { captureScope(it) in selected }.map { registration ->
            registration to checkNotNull(registration.accessCapture).copy(krxCapabilityEpoch = null)
        }
        val retireSeed = seedScope != null && seedScope in selected
        val retireSupplement = supplementScope != null && supplementScope in selected
        val stripRaw = unattributed != null && unattributed in selected
        val withdrawnWrites = writeTasks.values.filter { writeScope(it) in selected }
        if (slotKeys.isEmpty() && captureUpdates.isEmpty() && !retireSeed && !retireSupplement && !stripRaw &&
            withdrawnWrites.isEmpty()) {
            return GraphRuntimeRetirement.NOTHING_TO_REMOVE
        }

        var entries = snapshot.entries
        var slots = protectedSlots
        for (key in slotKeys) {
            val slot = slots.getValue(key)
            val tab = joinGraphV2Components(slot.components.general, null).tab
            val entry = entries.getValue(key).copy(tab = tab)
            entries = entries + (key to entry)
            slots = slots + (key to slot.copy(entry = entry, components = slot.components.copy(krx = null)))
        }
        if (stripRaw) entries = entries.mapValues { (_, entry) -> entry.copy(tab = generalOnly(entry.tab)) }
        val nextSnapshot = snapshot.copy(entries = entries)

        // Store first: an exception preserves all coordinator state and ownership for retry.
        withdrawnWrites.forEach { checkNotNull(cachePorts).store.withdrawKrx(it.ticket) }
        withdrawnWrites.forEach { it.krxOwned.set(false) }
        captureUpdates.forEach { (registration, captured) -> registration.accessCapture = captured }
        if (retireSeed) discardSeed() else if (retireSupplement) attemptedSupplement = null
        protectedSlots = slots
        snapshot = nextSnapshot
        publishState()
        return GraphRuntimeRetirement.REMOVED
    }

    private fun writeScope(task: WriteTask): GraphCapabilityScope? =
        if (!task.wantsKrx || !task.krxOwned.get()) null
        else task.captured.krxCapabilityEpoch?.let { GraphCapabilityScope(task.captured.fence.identity.uid, it) }

    private fun hasKrx(tab: GraphV2Tab): Boolean =
        tab.graph.series.any { it.seriesId.startsWith("krx.") } || tab.inProgress.keys.any { it.startsWith("krx.") }

    private fun scopeOf(fence: TopicSessionFence): GraphDataScope? =
        fence.userAccessEpoch?.let { GraphDataScope(fence.identity.uid, it) }

    private fun scopeOf(key: GraphV2GeneralKey): GraphDataScope = GraphDataScope(key.uid, key.userAccessEpoch)

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
        // Store completions own no request state, clock stamp or retry deadline.
        when (event) {
            is Event.WritePreparationFinished -> {
                applyWritePreparation(event)
                return
            }
            is Event.DiskWriteFinished -> {
                applyDiskWrite(event)
                return
            }
            else -> Unit
        }
        val now = clock.now()
        var catalogAdopted = false
        when (event) {
            is Event.Activate -> {
                synchronizeContext()
                if (activeKey != event.key) cancelActiveDemand()
                activeKey = event.key
                synchronizeCapabilityConfiguration(now)
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
                synchronizeCapabilityConfiguration(now)
                startDiskSeed()
                if (changed) requestActive(now, force = false, catalogSilent = sameScopeChange)
            }
            is Event.Refresh -> {
                synchronizeContext()
                synchronizeCapabilityConfiguration(now)
                startDiskSeed()
                requestActive(now, event.force)
            }
            is Event.Captured -> onCaptured(event, now)
            is Event.Wake -> onWake(event, now)
            is Event.RecoveryTrigger -> onTrigger(event.trigger, now)
            Event.RecoveryPermitChanged -> Unit // The common re-arm below reads the permit afresh.
            is Event.CatalogFinished -> catalogAdopted = applyCatalog(event, now)
            is Event.TabFinished -> applyTab(event, now)
            is Event.DiskSeedFinished -> applyDiskSeed(event)
            is Event.WritePreparationFinished, is Event.DiskWriteFinished -> Unit // Handled above.
        }
        rearmTimer(now)
        publish()
        if (catalogAdopted) replayRecorder()
    }

    private fun replayRecorder() {
        val recorder = recorder ?: return
        try {
            recorder.replayPending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onEventFailure(failure)
        }
    }

    /** Independent of HTTP ownership and its Retry-After floor. */
    private fun startDiskSeed() {
        val ports = cachePorts ?: return
        val key = activeKey ?: return
        val current = context ?: return
        if (!scope.isActive || seedRegistration != null ||
            GraphV2Domain.support(snapshot.catalog, key.tab, key.period) == GraphPeriodSupport.Unsupported) return
        val slot = protectedSlots[key]
        val mode = if (snapshot.entries[key] == null) {
            if (attemptedSeedKey == key) return
            GraphV2SeedMode.FillEmpty
        } else {
            val occupied = slot ?: return
            if (occupied.supplementGeneration != capabilityGeneration) return
            val supplement = GraphV2SeedMode.SupplementOccupied(
                occupied.components.general.responseId, capabilityConfiguration ?: return, capabilityGeneration
            )
            if (attemptedSupplement == supplement) return
            supplement
        }
        // A failed bind spends no attempt in this generation, even though IDs are never reused.
        val seedId = Math.incrementExact(nextSeedId)
        nextSeedId = seedId
        val binding = try {
            ports.gate.bindWithConfiguration(current.fence, current.lifetime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            seedDiagnostic(seedId, key, GraphV2DiskComponent.GENERAL, "Seed access binding failed", failure)
            return
        } ?: return
        if (mode is GraphV2SeedMode.SupplementOccupied && binding.configuration != mode.configuration) return
        val captured = binding.captured
        val registration = SeedRegistration(
            seedId, key, current, activityGeneration, snapshot.catalog, binding.configuration, mode
        )
        seedRegistration = registration
        when (mode) {
            GraphV2SeedMode.FillEmpty -> attemptedSeedKey = key
            // Keep the need pending on failure, without looping; a new activation or flip can retry it.
            is GraphV2SeedMode.SupplementOccupied -> attemptedSupplement = mode
        }
        scope.launch {
            val result = readGraphV2DiskSeed(ports, key, captured, registration.catalog)
            inbox.trySend(Event.DiskSeedFinished(seedId, key, captured, result))
        }
    }

    /** Cache failures must not enter the event catch that cancels HTTP retry owners. */
    private fun applyDiskSeed(event: Event.DiskSeedFinished) {
        var released = false
        try {
            val ports = cachePorts ?: return
            val registration = seedRegistration
            if (registration == null || registration.seedId != event.seedId || !scope.isActive ||
                registration.key != event.key || registration.context != context ||
                registration.activityGeneration != activityGeneration || activeKey != event.key) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed ownership expired")
                return
            }
            // Only this owner is released; an older completion cannot remove a newer one.
            seedRegistration = null
            released = true
            diagnoseSeedRead(event, GraphV2DiskComponent.GENERAL, event.result.general)
            diagnoseSeedRead(event, GraphV2DiskComponent.KRX, event.result.krx)
            val general = when (val read = event.result.general) {
                is GraphV2DiskRead.Found -> read.envelope
                else -> return
            }
            // FillEmpty must reach the occupancy check even after a same-context capability flip.
            if (registration.mode == GraphV2SeedMode.FillEmpty && snapshot.entries[event.key] != null) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed slot is already occupied")
                return
            }
            if (general.key != event.captured.generalKey(event.key)) {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed GENERAL key does not match")
                return
            }
            val mode = registration.mode
            if (mode is GraphV2SeedMode.SupplementOccupied) {
                val binding = ports.gate.bindWithConfiguration(event.captured.fence, event.captured.lifetime)
                if (mode.configurationGeneration != capabilityGeneration ||
                    binding?.configuration != mode.configuration || capabilityConfiguration != mode.configuration) {
                    seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Supplement configuration expired")
                    return
                }
                if (general.responseId != mode.expectedGeneralResponseId ||
                    protectedSlots[event.key]?.components?.general?.responseId != mode.expectedGeneralResponseId) {
                    seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Supplement response ID does not match")
                    return
                }
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
            joined.ignoredKrxReason?.let {
                seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.KRX, it)
            }
            if (mode is GraphV2SeedMode.SupplementOccupied && !joined.krxJoined) return
            val stamp = if (mode is GraphV2SeedMode.SupplementOccupied) snapshot.entries[event.key]?.online200At else null
            val entry = GraphEntry(joined.tab, stamp)
            val pending = if (mode == GraphV2SeedMode.FillEmpty &&
                registration.configuration != capabilityConfiguration) capabilityGeneration else null
            snapshot = snapshot.copy(entries = snapshot.entries + (event.key to entry))
            protectedSlots = protectedSlots + (event.key to ProtectedSlot(
                entry, GraphV2DiskComponents(general, krx.takeIf { joined.krxJoined }), pending
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            seedDiagnostic(event.seedId, event.key, GraphV2DiskComponent.GENERAL, "Seed application failed", failure)
        } finally {
            // A pending flip waits for the one seed owner; completion never acquires a new use lifetime.
            if (released) startDiskSeed()
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
        attemptedSupplement = null
    }

    /** Clear ownership and protected context state together, before publishing the new scope. */
    private fun synchronizeContext(): Boolean {
        val fence = currentAccessFence()
        val identityMatches = fence != null && owners.currentIdentity() == fence.identity
        val dataScope = fence?.userAccessEpoch?.let { GraphDataScope(fence.identity.uid, it) }
        val next = if (identityMatches && dataScope != null) {
            uses.acquire(fence!!)?.let { RequestContext(fence, it) }
        } else null
        val userEnd = accessSnapshot().lastUserEnd?.sequence ?: 0L
        val ended = userEnd > seenUserEnd
        seenUserEnd = maxOf(seenUserEnd, userEnd)
        val scopeChanged = snapshot.dataScope != dataScope || ended
        if (ended) lastDataScope = null
        if (!scopeChanged && context == next) return false
        sameScopeChange = recorder != null && next != null && dataScope == lastDataScope
        if (dataScope != null) lastDataScope = if (next != null) dataScope else lastDataScope?.takeIf { it == dataScope }

        if (ended) recoveryBudgets.clear() else if (dataScope != null) recoveryBudgets.keys.retainAll { it.scope == dataScope }
        cancelActiveDemand()
        discardRequests()
        discardWrites()
        context = next
        catalogAt = null
        if (scopeChanged) {
            protectedSlots = emptyMap()
            capabilityConfiguration = null
        }
        snapshot = GraphRequestState(
            dataScope = dataScope,
            entries = if (scopeChanged) emptyMap() else snapshot.entries,
            source = next?.let { GraphRequestSource(it.fence, it.lifetime) }
        )
        return true
    }

    /** Admission failure leaves the last comparable configuration intact; it is not a capability revocation. */
    private fun synchronizeCapabilityConfiguration(now: Instant) {
        val ports = cachePorts ?: return
        val current = context ?: return
        val binding = try {
            ports.gate.bindWithConfiguration(current.fence, current.lifetime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            activeKey?.let {
                seedDiagnostic(nextSeedId, it, GraphV2DiskComponent.GENERAL, "Capability configuration binding failed", failure)
            }
            return
        } ?: return
        val previous = capabilityConfiguration
        val next = binding.configuration
        if (previous == next) return
        capabilityConfiguration = next
        if (previous == null) return

        capabilityGeneration = Math.incrementExact(capabilityGeneration)
        val entries = snapshot.entries.mapValues { (key, entry) ->
            val general = protectedSlots[key]?.components?.general
            val tab = if (general != null) joinGraphV2Components(general, null).tab else {
                // Conversion failures have no protected slot; still remove KRX from the raw fallback.
                generalOnly(entry.tab)
            }
            GraphEntry(tab, online200At = null)
        }
        protectedSlots = protectedSlots.mapValues { (key, slot) ->
            ProtectedSlot(entries.getValue(key), slot.components.copy(krx = null), capabilityGeneration)
        }
        snapshot = snapshot.copy(entries = entries)
        requireFlipConfirmation(now)
    }

    private fun requestActive(
        now: Instant,
        force: Boolean,
        coldAttempt: Boolean = false,
        midnightAttempt: Boolean = false,
        recovery: RecoveryRound? = null,
        catalogSilent: Boolean = false
    ) {
        val key = activeKey ?: return
        val current = context ?: return
        scheduleMidnight(now)

        // Outside a recovery round, a catalog refresh is independent of tab freshness and never blocks the tab request.
        val lastCatalogAt = catalogAt
        val needsCatalog = !catalogSilent && catalogRequest == null && (snapshot.catalog == null || lastCatalogAt == null ||
            now - lastCatalogAt >= GraphV2Domain.ttl(snapshot.catalog))
        val supported = GraphV2Domain.support(snapshot.catalog, key.tab, key.period) != GraphPeriodSupport.Unsupported
        val cold = coldOwner?.takeIf { it.key == key && it.context == current }
        val midnight = midnightOwner?.takeIf { it.key == key && it.context == current }
        val needsTab = supported && !tabRequests.containsKey(key) &&
            (force || (cold == null && midnight?.cycleStarted != true && !recoveryWaiting(key, current) &&
                !isFresh(snapshot.entries[key], now)))

        if (underFloor(now)) {
            if (needsCatalog || needsTab) {
                val previous = deferredDemand
                deferredDemand = DeferredDemand(key, current, activityGeneration, force || previous?.force == true, catalogSilent)
            }
            return
        }
        if (recovery != null && awaitRoundCatalog(now, current, key, recovery)) {
            // S4 RT03b-2b: the round's tab waits for its catalog; an outside tab already out is still joined.
            tabRequests[key]?.let {
                bindRecovery(it, recovery, attempt = false)
                connectFlip(it)
            }
            return
        }
        if (needsCatalog && recovery == null) startCatalog(current, key.tab)
        if (!supported) {
            if (coldAttempt) cancelCold()
            if (midnightAttempt) cancelMidnight()
            return
        }
        tabRequests[key]?.let {
            if (coldAttempt) connectCold(it, attempt = true)
            if (midnightAttempt) connectMidnight(it, attempt = true)
            if (recovery != null) bindRecovery(it, recovery, attempt = false)
            connectFlip(it)
            return
        }
        if (needsTab) startTab(current, key, coldAttempt, midnightAttempt, recovery)
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
            currentAccessFence() == captured.fence && uses.admits(captured.lifetime) && protectedAdmission()
    }

    private fun sendAdmitted(registration: Registration): Boolean {
        if (!useAdmitted(registration)) return false
        if (permitRefuses(registration)) return false
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

    private fun startCatalog(current: RequestContext, originalTab: String, recovery: RecoveryRound? = null) {
        val registration = Registration(
            ++nextRequestId, current, null, originalTab, activityGeneration, automaticRecovery = recovery != null
        )
        recovery?.let { bindRecovery(registration, it, attempt = true) }
        catalogRequest = registration
        startCapture(registration)
    }

    private fun startTab(
        current: RequestContext,
        key: GraphKey,
        coldAttempt: Boolean,
        midnightAttempt: Boolean,
        recovery: RecoveryRound? = null
    ) {
        // Registration is the logical start; changing the active key does not withdraw a sent request.
        val registration = Registration(
            ++nextRequestId, current, key, key.tab, activityGeneration, bindRequestAccess(current),
            captureRecovery(current, key), automaticRecovery = recovery != null
        )
        tabRequests[key] = registration
        recovery?.let { bindRecovery(registration, it, attempt = true) }
        connectCold(registration, coldAttempt)
        connectMidnight(registration, midnightAttempt)
        connectFlip(registration)
        startCapture(registration)
    }

    private fun captureRecovery(current: RequestContext, key: GraphKey): List<GraphRecoveryRequest> {
        val recorder = recorder ?: return emptyList()
        if (key.period != GraphPeriod.ONE_DAY) return emptyList()
        val ids = snapshot.catalog?.tabs?.get(key.tab)?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries
            ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val epoch = current.fence.userAccessEpoch ?: return emptyList()
        val dataScope = GraphDataScope(current.fence.identity.uid, epoch)
        return try {
            recorder.captureRequests(
                ids.mapTo(linkedSetOf()) { GraphObservationSeriesKey(dataScope, it) },
                current.fence, current.lifetime
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Recovery is optional; a failed capture leaves HTTP ownership and retries intact.
            onEventFailure(failure)
            emptyList()
        }
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
        launchRequestCompletion(registration) {
            val result = runCatching { captureOwner(registration) }
            Event.Captured(registration, result)
        }
    }

    /**
     * The completion stays pending until the loop handles it; coroutine exit or enqueue alone never
     * ends it. An undelivered completion or a request cancelled before its body arises only with
     * [close], which ends all pending tracking.
     */
    private fun launchRequestCompletion(
        registration: Registration,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        completion: suspend () -> Event
    ) {
        registration.completionPending = true
        scope.launch(start = start) {
            val event = completion()
            inbox.trySend(event)
            val error = when (event) {
                is Event.Captured -> event.result.exceptionOrNull()
                is Event.CatalogFinished -> event.result.exceptionOrNull()
                is Event.TabFinished -> event.result.exceptionOrNull()
                else -> error("Expected a graph request completion")
            }
            if (error is CancellationException) throw error
        }
    }

    private fun onCaptured(event: Event.Captured, now: Instant) {
        handleRequestCompletion(event.registration) { applyCaptured(event, now) }
    }

    private fun applyCaptured(event: Event.Captured, now: Instant) {
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
            endConnectedFlip(registration)
            return
        }
        val owner = registration.capturedOwner ?: return
        if (underFloor(now)) {
            // Deferred sends are demand too: a later key transition must not revive this one.
            if (registration.activityGeneration != activityGeneration || activeKey == null) {
                releaseRegistration(registration)
                endConnectedCold(registration)
                endConnectedMidnight(registration)
                endConnectedFlip(registration)
            } else {
                registration.waitingForFloor = true
            }
            return
        }
        registration.waitingForFloor = false
        if (permitRefuses(registration) || !departRecovery(registration)) {
            releaseRegistration(registration)
            endConnectedCold(registration)
            endConnectedMidnight(registration)
            endConnectedFlip(registration)
            return
        }
        registration.departed = true
        // Enter fetch immediately at the loop's final floor checkpoint. No queued child may
        // slip a new floor between this admission and entering the fetcher after capture.
        launchRequestCompletion(registration, CoroutineStart.UNDISPATCHED) {
            val key = registration.key
            if (key == null) {
                val result = runCatching { fetcher.catalog(owner) { sendAdmitted(registration) } }
                Event.CatalogFinished(registration.requestId, registration.originalTab, result)
            } else {
                val result = runCatching { fetcher.tab(owner, key) { sendAdmitted(registration) } }
                Event.TabFinished(registration.requestId, key, result)
            }
        }
    }

    private fun applyCatalog(event: Event.CatalogFinished, now: Instant): Boolean {
        recordRetryFloor(event.result, event.originalTab, now)
        val registration = completionRegistration(event) ?: return false
        return handleRequestCompletion(registration) {
            if (isRegistered(registration)) applyRegisteredCatalog(registration, event, now) else false
        }
    }

    private fun applyRegisteredCatalog(registration: Registration, event: Event.CatalogFinished, now: Instant): Boolean {
        if (event.result.exceptionOrNull() is CancellationException) {
            releaseCompletion(event)
            return false
        }
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            return false
        }
        // S4 RT03b-2b: going offline withdraws a tab, so it leaves a waiting round's catalog check open (released below);
        // an identity change already returned above as a cancellation.
        if (event.result.exceptionOrNull() !is AuthUnavailableException) endCatalogWaits(registration)
        releaseRegistration(registration)
        val response = event.result.getOrNull() ?: return false
        if (response.statusCode != 200) return false
        val body = response.body ?: return false
        snapshot = snapshot.copy(catalog = GraphV2Domain.catalog(body))
        catalogAt = now
        // No pump on completion: failure or absence alone must never start another request.
        return true
    }

    private fun applyTab(event: Event.TabFinished, now: Instant) {
        recordRetryFloor(event.result, event.key.tab, now)
        val registration = completionRegistration(event) ?: return
        handleRequestCompletion(registration) {
            if (isRegistered(registration)) applyRegisteredTab(registration, event, now)
        }
    }

    private fun applyRegisteredTab(registration: Registration, event: Event.TabFinished, now: Instant) {
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            endConnectedCold(registration)
            endConnectedMidnight(registration)
            endConnectedFlip(registration)
            return
        }
        val outcome = tabOutcome(event.result, event.key)
        val configuration = if (outcome is TabOutcome.Success) completionConfiguration(registration) else null
        // The live publisher can withdraw the original context during the completion's configuration read.
        if (!useAdmitted(registration)) {
            releaseRegistration(registration)
            endConnectedCold(registration)
            endConnectedMidnight(registration)
            endConnectedFlip(registration)
            return
        }
        releaseRegistration(registration)
        when (outcome) {
            is TabOutcome.Success -> {
                val confirmed = cachePorts == null || configuration?.let {
                    !it.allowed || (it.recordEpoch != null && it.recordEpoch == registration.accessCapture?.krxCapabilityEpoch)
                } == true
                val entry = GraphEntry(outcome.tab, now.takeIf { confirmed })
                snapshot = snapshot.copy(
                    entries = snapshot.entries + (event.key to entry),
                    failures = snapshot.failures - event.key
                )
                applyRecovery(registration, outcome.tab)
                val components = replaceOnlineSlot(registration, event.key, entry, configuration)
                reserveOnlineWrite(registration, event.key, components)
                settleRecovery(registration, null, now)
                if (snapshot.entries[event.key]?.online200At != null) {
                    endConnectedCold(registration)
                    endConnectedFlip(registration)
                } else {
                    onUnconfirmedSuccess(registration, configuration, now)
                }
                if (connectedMidnight(registration) != null) finishMidnight(now)
            }
            is TabOutcome.Failure -> {
                if (outcome.disposition != FailureDisposition.WITHDRAWN) {
                    snapshot = snapshot.copy(failures = snapshot.failures + (event.key to outcome.error))
                }
                settleRecovery(registration, outcome, now)
                onColdFailure(registration, outcome, now)
                onMidnightFailure(registration, outcome, now)
                endConnectedFlip(registration)
                if (outcome.disposition == FailureDisposition.DIAGNOSTIC) onEventFailure(outcome.error)
            }
        }
    }

    private fun applyRecovery(registration: Registration, tab: GraphV2Tab) {
        val recorder = recorder ?: return
        for (request in registration.recoveryRequests) {
            try {
                recorder.applyResponse(request, tab, registration.context.fence, registration.context.lifetime)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onEventFailure(failure)
            }
        }
    }

    /** Read the completion's configuration once, without acquiring or replacing the start capture. */
    private fun completionConfiguration(registration: Registration): GraphV2CapabilityConfiguration? = try {
        cachePorts?.gate?.bindWithConfiguration(registration.context.fence, registration.context.lifetime)?.configuration
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        seedDiagnostic(nextSeedId, checkNotNull(registration.key), GraphV2DiskComponent.GENERAL,
            "Completion configuration binding failed", failure)
        null
    }

    private fun onUnconfirmedSuccess(
        registration: Registration,
        configuration: GraphV2CapabilityConfiguration?,
        now: Instant
    ) {
        val flip = connectedFlip(registration)
        val requestActivity = flip?.activityGeneration ?: connectedMidnight(registration)?.activityGeneration
            ?: registration.activityGeneration
        connectedCold(registration)?.let { cold ->
            cold.requestId = null
            // An unconfirmed 200 consumes no failure rung and cannot satisfy the existing cold demand.
            if (cold.deadline == null) cold.deadline = coldDeadline(now, cold.rung, null, cold.key.tab)
        }
        endConnectedFlip(registration)
        // A completed delegated response starts a new wait; the earlier flip deadline no longer applies.
        // An unavailable configuration alone grants no new capability demand.
        if ((configuration != null || flip != null) && activeKey == registration.key &&
            context == registration.context && activityGeneration == requestActivity) requireFlipConfirmation(now)
    }

    private fun replaceOnlineSlot(
        registration: Registration,
        key: GraphKey,
        entry: GraphEntry,
        configuration: GraphV2CapabilityConfiguration?
    ): GraphV2DiskComponents? {
        // A failed conversion must never pair a new raw entry with the previous components.
        protectedSlots = protectedSlots - key
        if (cachePorts == null) return null
        // Until conversion succeeds, retain only an unconfirmed GENERAL fallback.
        snapshot = snapshot.copy(entries = snapshot.entries + (key to GraphEntry(generalOnly(entry.tab), null)))
        val fence = registration.context.fence
        val epoch = fence.userAccessEpoch ?: return null
        val components = try {
            when (val split = splitGraphV2ServerTab(
                entry.tab,
                GraphV2GeneralKey(fence.identity.uid, epoch, key.tab, key.period.code),
                registration.accessCapture?.krxCapabilityEpoch,
                UUID.randomUUID().toString(),
                snapshot.catalog
            )) {
                is GraphV2Validation.Valid -> split.value
                is GraphV2Validation.Invalid -> return null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The protected slot is absent and the raw GENERAL fallback remains unconfirmed.
            return null
        }
        // Memory follows the completion configuration; writes use the request capture's split (its start
        // epoch, or none once capability retirement cleared it).
        val memoryComponents = components.copy(krx = components.krx.takeIf {
            configuration?.allowed == true && configuration.recordEpoch != null &&
                configuration.recordEpoch == registration.accessCapture?.krxCapabilityEpoch
        })
        val adopted = entry.copy(tab = joinGraphV2Components(memoryComponents.general, memoryComponents.krx).tab)
        snapshot = snapshot.copy(entries = snapshot.entries + (key to adopted))
        protectedSlots = protectedSlots + (key to ProtectedSlot(adopted, memoryComponents))
        return components
    }

    private fun generalOnly(tab: GraphV2Tab): GraphV2Tab = tab.copy(
        graph = tab.graph.copy(series = tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }),
        inProgress = tab.inProgress.filterKeys { !it.startsWith("krx.") }
    )

    /** Reservation is synchronous: adoption order is the store's supersession order. */
    private fun reserveOnlineWrite(
        registration: Registration,
        key: GraphKey,
        components: GraphV2DiskComponents?
    ) {
        val ports = cachePorts ?: return
        val writes = ports.writePorts ?: return
        if (components == null) {
            // No newer reservation can supersede these candidates when slot conversion failed.
            writeTasks.values.filter { it.key == key }.forEach { discardWrite(it) }
            return
        }
        val writeId = Math.incrementExact(nextWriteId)
        nextWriteId = writeId
        val ticket = try {
            when (val reservation = ports.store.reserveWrite(components)) {
                is GraphV2WriteReservation.Reserved -> reservation.ticket
                is GraphV2WriteReservation.Rejected -> {
                    writeDiagnostic(writeId, key, GraphV2DiskComponent.GENERAL, reservation.reason)
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            writeDiagnostic(writeId, key, GraphV2DiskComponent.GENERAL, "Write reservation failed", failure)
            return
        }
        val captured = registration.accessCapture
        if (captured == null) {
            // Cancelling keeps the store's latest sequence; never bind a replacement capture.
            cancelWriteTicket(writeId, key, ticket)
            writeDiagnostic(writeId, key, GraphV2DiskComponent.GENERAL, "Missing request-start write capture")
            return
        }
        val task = WriteTask(writeId, ticket, key, captured, components.krx != null)
        writeTasks[ticket] = task
        val preparationJob = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatching {
                if (!task.enabled.get() || !scope.isActive ||
                    !ports.gate.admits(captured, GraphV2DiskComponent.GENERAL)
                ) throw CancellationException("Write preparation admission is closed")
                writes.prepareWrite.prepare(captured, task.wantsKrx && task.krxOwned.get())
            }
            val delivered = inbox.trySend(Event.WritePreparationFinished(ticket, captured, result)).isSuccess
            if (!delivered || result.exceptionOrNull() is CancellationException) abandonWrite(task)
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
        task.job = preparationJob
        writePreparationToStart = preparationJob
    }

    private fun applyWritePreparation(event: Event.WritePreparationFinished) {
        val task = writeTasks[event.ticket] ?: return
        var writing = false
        try {
            val ports = cachePorts ?: return
            if (!task.enabled.get() || !scope.isActive || event.captured != task.captured) return
            val owned = task.wantsKrx && task.krxOwned.get()
            event.result.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) return
                val blocked = GraphV2NamespacePreparation.Blocked("Write preparation failed", failure)
                preparationBlocked(task, GraphV2DiskComponent.GENERAL, blocked)
                if (owned) preparationBlocked(task, GraphV2DiskComponent.KRX, blocked)
                return
            }
            val result = event.result.getOrThrow()
            // Each axis describes its own preparation. GENERAL still controls the whole write.
            val generalBlock = preparationBlock(result.general, task.captured, GraphV2DiskComponent.GENERAL)
            val krxBlock = if (owned) {
                preparationBlock(result.krx, task.captured, GraphV2DiskComponent.KRX)
            } else null
            if (generalBlock != null) preparationBlocked(task, GraphV2DiskComponent.GENERAL, generalBlock)
            if (krxBlock != null) preparationBlocked(task, GraphV2DiskComponent.KRX, krxBlock)
            if (task.wantsKrx && !owned && result.krx != null) {
                writeDiagnostic(task.writeId, task.key, GraphV2DiskComponent.KRX, "KRX candidate withdrawn by capability retirement")
            }
            if (!task.wantsKrx && result.krx != null) {
                writeDiagnostic(task.writeId, task.key, GraphV2DiskComponent.KRX, "Ignoring preparation without a KRX candidate")
            }
            if (generalBlock != null) return

            val krxReady = owned && krxBlock == null
            val liveAdmission = ports.gate.ioAdmission(task.captured)
            val admission = GraphV2IoAdmission { component ->
                task.enabled.get() && scope.isActive &&
                    (component == GraphV2DiskComponent.GENERAL || (krxReady && task.krxOwned.get())) && liveAdmission.admits(component)
            }
            task.job = scope.launch {
                val written = runCatching { ports.store.write(task.ticket, admission) }
                val delivered = inbox.trySend(Event.DiskWriteFinished(task.ticket, written)).isSuccess
                if (!delivered || written.exceptionOrNull() is CancellationException) abandonWrite(task)
                (written.exceptionOrNull() as? CancellationException)?.let { throw it }
            }
            writing = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            writeDiagnostic(task.writeId, task.key, GraphV2DiskComponent.GENERAL, "Write preparation application failed", failure)
        } finally {
            if (!writing) discardWrite(task)
        }
    }

    private fun preparationBlock(
        preparation: GraphV2NamespacePreparation?,
        captured: GraphV2AccessCapture,
        component: GraphV2DiskComponent
    ): GraphV2NamespacePreparation.Blocked? {
        val record = when (preparation) {
            is GraphV2NamespacePreparation.Ready -> preparation.record
            is GraphV2NamespacePreparation.Blocked -> return preparation
            null -> return GraphV2NamespacePreparation.Blocked("Missing namespace preparation")
        }
        val reason = when {
            record.ownerUid != captured.fence.identity.uid -> "Prepared owner does not match the capture"
            captured.fence.userAccessEpoch == null || record.userAccessEpoch != captured.fence.userAccessEpoch ->
                "Prepared USER epoch does not match the capture"
            !record.mayContainPremiumData -> "Premium data marker is not prepared"
            component == GraphV2DiskComponent.KRX &&
                (captured.krxCapabilityEpoch == null || record.krxCapabilityEpoch != captured.krxCapabilityEpoch) ->
                "Prepared KRX epoch does not match the capture"
            component == GraphV2DiskComponent.KRX && !record.mayContainKrxData -> "KRX data marker is not prepared"
            else -> return null
        }
        return GraphV2NamespacePreparation.Blocked(reason)
    }

    private fun preparationBlocked(
        task: WriteTask,
        component: GraphV2DiskComponent,
        blocked: GraphV2NamespacePreparation.Blocked
    ) {
        val writes = cachePorts?.writePorts ?: return
        try {
            writes.onPreparationBlocked(
                GraphV2PreparationBlocked(task.writeId, task.key, task.captured, component, blocked.reason, blocked.cause)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Observing a block grants no authority and cannot change its preparation mask.
            return
        }
    }

    private fun applyDiskWrite(event: Event.DiskWriteFinished) {
        val task = writeTasks[event.ticket] ?: return
        try {
            event.result.exceptionOrNull()?.let { failure ->
                if (failure !is CancellationException) {
                    writeDiagnostic(task.writeId, task.key, GraphV2DiskComponent.GENERAL, "Disk write failed", failure)
                    if (task.wantsKrx) {
                        writeDiagnostic(task.writeId, task.key, GraphV2DiskComponent.KRX, "Disk write failed", failure)
                    }
                }
                return
            }
            val report = event.result.getOrThrow()
            diagnoseWriteOutcome(task, GraphV2DiskComponent.GENERAL, report.general)
            report.krx?.let { diagnoseWriteOutcome(task, GraphV2DiskComponent.KRX, it) }
        } finally {
            // Ticket ownership, never the key, determines which job this completion releases.
            discardWrite(task)
        }
    }

    private fun diagnoseWriteOutcome(
        task: WriteTask,
        component: GraphV2DiskComponent,
        outcome: GraphV2ComponentWriteOutcome
    ) {
        when (outcome) {
            GraphV2ComponentWriteOutcome.Replaced -> Unit
            is GraphV2ComponentWriteOutcome.Skipped -> writeDiagnostic(task.writeId, task.key, component, outcome.reason)
            is GraphV2ComponentWriteOutcome.Failed ->
                writeDiagnostic(task.writeId, task.key, component, "Disk component write failed", outcome.cause)
        }
    }

    private fun writeDiagnostic(
        writeId: Long,
        key: GraphKey,
        component: GraphV2DiskComponent,
        reason: String,
        cause: Throwable? = null
    ) {
        val writes = cachePorts?.writePorts ?: return
        try {
            writes.onWriteDiagnostic(GraphV2WriteDiagnostic(writeId, key, component, reason, cause))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Diagnostics must not reach A2's catch or alter HTTP retry owners.
            return
        }
    }

    private fun cancelWriteTicket(writeId: Long, key: GraphKey, ticket: GraphV2WriteTicket) {
        try {
            cachePorts?.store?.cancelWrite(ticket)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            writeDiagnostic(writeId, key, GraphV2DiskComponent.GENERAL, "Write ticket cancellation failed", failure)
        }
    }

    /** Safe on a child coroutine: no access to the loop-confined registry or job. */
    private fun abandonWrite(task: WriteTask) {
        task.enabled.set(false)
        cancelWriteTicket(task.writeId, task.key, task.ticket)
    }

    private fun discardWrite(task: WriteTask) {
        try {
            abandonWrite(task)
        } finally {
            if (writePreparationToStart === task.job) writePreparationToStart = null
            task.job?.cancel()
            if (writeTasks[task.ticket] === task) writeTasks.remove(task.ticket)
        }
    }

    private fun discardWrites() {
        writeTasks.values.toList().forEach { discardWrite(it) }
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
            is TopicUseWithheldException -> error.exchanges.forEach { recordRetryFloor(it.statusCode, it.retryAfter, originalTab, now) }
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
        if (absorbs(registration.key)) return
        val cold = connectedCold(registration)
        if (failure.disposition != FailureDisposition.RETRYABLE) {
            if (cold != null) cancelCold()
            return
        }
        val key = registration.key ?: return
        // Delegated requests may have been sent in an earlier activation; the current owner receives the handoff.
        val requestActivity = connectedFlip(registration)?.activityGeneration
            ?: connectedMidnight(registration)?.activityGeneration ?: registration.activityGeneration
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
        synchronizeCapabilityConfiguration(now)
        startDiskSeed()

        // Only saved demand is resumed. A floor with no demand never arms a timer or sends a GET.
        val waiting = listOfNotNull(catalogRequest) + tabRequests.values.toList()
        waiting.filter { it.waitingForFloor }.forEach { dispatchCaptured(it, now) }
        deferredDemand?.let { demand ->
            deferredDemand = null
            if (demand.key == activeKey && demand.context == context &&
                demand.activityGeneration == activityGeneration) {
                requestActive(now, demand.force, catalogSilent = demand.catalogSilent)
            }
        }

        wakeCold(now)
        wakeRecovery(now)
        wakeMidnight(now)
        wakeFlip(now)
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

    // --- S4 RT03b-1b: the 1d recovery budget ----------------------------------------------------------------------

    private fun absorbs(key: GraphKey?): Boolean = recorder != null && key?.period == GraphPeriod.ONE_DAY

    private fun recoveryKey(current: RequestContext, tab: String): RecoveryKey? =
        scopeOf(current.fence)?.let { RecoveryKey(it, tab) }

    /** The budget of the active absorbed key in the current context, whatever its state. */
    private fun activeRecovery(): RecoveryBudget? {
        val key = activeKey?.takeIf { absorbs(it) } ?: return null
        val current = context ?: return null
        return recoveryKey(current, key.tab)?.let { recoveryBudgets[it] }
    }

    private fun recoveryWaiting(key: GraphKey, current: RequestContext): Boolean =
        absorbs(key) && recoveryKey(current, key.tab)?.let { recoveryBudgets[it] }?.let {
            // S4 RT03b-3b: a budget parked on the permit may never fetch, so its key's unforced requests are not held back.
            it.stop == null && !permitHeld(it, current)
        } == true

    private fun recoveryNeed(key: GraphKey, current: RequestContext, now: Instant): RecoveryNeed {
        if (snapshot.entries[key]?.online200At == null) return RecoveryNeed.REMAINS
        val demand = try {
            checkNotNull(recorder).recoveryDemand(key.tab, current.fence, current.lifetime, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onEventFailure(failure)
            return RecoveryNeed.REMAINS
        }
        return when (demand) {
            GraphTabRecoveryDemand.None -> RecoveryNeed.NONE
            GraphTabRecoveryDemand.Unreadable -> RecoveryNeed.UNREADABLE
            else -> RecoveryNeed.REMAINS
        }
    }

    private fun bindRecovery(registration: Registration, round: RecoveryRound, attempt: Boolean) {
        registration.recoveryRound = round
        registration.recoveryAttempt = attempt
        if (registration.key == null) round.catalogRequestId = registration.requestId
        else round.tabRequestId = registration.requestId
    }

    private fun roundInFlight(round: RecoveryRound, key: GraphKey): Boolean =
        (round.tabRequestId != null && tabRequests[key]?.requestId == round.tabRequestId) ||
            (round.catalogRequestId != null && catalogRequest?.requestId == round.catalogRequestId) ||
            (round.awaitedCatalogId != null && catalogRequest?.requestId == round.awaitedCatalogId)

    /**
     * S4 RT03b-2b: true while [round] waits for a catalog. A round needing one (absent or past its TTL) joins the catalog
     * already registered, or issues its own once; its catalog check ends with an applicable completion of that catalog, or
     * with its catalog slot spent while no catalog is out, and its tab goes only after that. Known limitation: a check
     * ended by an adopted catalog is not reopened when a same-scope context change drops that catalog, so the round's tab
     * then goes without captures and spends the round; a next round, if the budget has one left, asks again.
     */
    private fun awaitRoundCatalog(now: Instant, current: RequestContext, key: GraphKey, round: RecoveryRound): Boolean {
        if (round.catalogChecked) return false
        round.awaitedCatalogId?.let { if (catalogRequest?.requestId == it) return true }
        round.awaitedCatalogId = null
        val lastCatalogAt = catalogAt
        if (snapshot.catalog != null && lastCatalogAt != null && now - lastCatalogAt < GraphV2Domain.ttl(snapshot.catalog)) {
            return false
        }
        catalogRequest?.let {
            round.awaitedCatalogId = it.requestId
            return true
        }
        if (round.catalogSent) {
            round.catalogChecked = true
            return false
        }
        startCatalog(current, key.tab, round)
        round.awaitedCatalogId = checkNotNull(catalogRequest).requestId
        return true
    }

    /** S4 RT03b-2b: an applicable completion of [catalog] ends the catalog check of every round waiting for it. */
    private fun endCatalogWaits(catalog: Registration) {
        recoveryBudgets.values.forEach { budget ->
            val round = budget.round ?: return@forEach
            if (round.awaitedCatalogId != catalog.requestId) return@forEach
            round.awaitedCatalogId = null
            round.catalogChecked = true
        }
    }

    /** False withdraws a directly issued recovery request before it is sent. */
    private fun departRecovery(registration: Registration): Boolean {
        if (!registration.recoveryAttempt) return true
        val round = registration.recoveryRound ?: return true
        val budget = round.budget
        // Since S4 RT03b-2b the count clause is defensive: a newer round waits for an older round's catalog, so nothing of an
        // uncounted round departs once six are counted.
        if (budget.stop != null || (!round.counted && budget.rounds >= MAX_RECOVERY_ROUNDS)) return false
        if (registration.key == null) round.catalogSent = true else round.tabSent = true
        if (!round.counted) {
            round.counted = true
            budget.rounds++
        }
        return true
    }

    private fun settleRecovery(registration: Registration, failure: TabOutcome.Failure?, now: Instant) {
        val key = registration.key ?: return
        if (!absorbs(key)) return
        val budgetKey = recoveryKey(registration.context, key.tab) ?: return
        val budget = recoveryBudgets[budgetKey]
        if (budget == null) {
            if (failure != null && failure.disposition != FailureDisposition.RETRYABLE) return
            if (key != activeKey || registration.context != context) return
            if (recoveryNeed(key, registration.context, now) == RecoveryNeed.NONE) return
            recoveryBudgets[budgetKey] = RecoveryBudget(budgetKey).also {
                it.rounds = if (registration.departed) 1 else 0
                it.completionRung = 1
                it.deadline = coldDeadline(now, 0, failure?.statusCode, key.tab)
            }
            return
        }
        val round = registration.recoveryRound
        if (round == null || budget.round !== round || round.tabRequestId != registration.requestId) return
        when (failure?.disposition) {
            FailureDisposition.WITHDRAWN -> return
            FailureDisposition.TERMINAL, FailureDisposition.DIAGNOSTIC -> {
                budget.stop = RecoveryStop.TERMINAL
                budget.round = null
                budget.hold = null
                budget.permitWait = null
                return
            }
            else -> Unit
        }
        budget.round = null
        budget.hold = null
        budget.permitWait = null
        if (recoveryNeed(key, registration.context, now) == RecoveryNeed.NONE) {
            recoveryBudgets.remove(budgetKey)
            return
        }
        if (registration.recoveryAttempt) budget.completionRung++
        if (budget.rounds >= MAX_RECOVERY_ROUNDS || budget.completionRung >= MAX_RECOVERY_ROUNDS) {
            budget.stop = RecoveryStop.EXHAUSTED
            budget.deadline = null
        } else {
            // A cycle opened by a trigger starts at rung 0: a joined first completion keeps it there and uses the first gap.
            budget.deadline = coldDeadline(now, maxOf(budget.completionRung - 1, 0), failure?.statusCode, key.tab)
        }
    }

    /**
     * S4 RT03b-3b: null when the live permit lets an automatic recovery request of [context] be issued or sent - it exists,
     * it is automatic, its fence is both [context]'s and the published fence, and under a re-approval its live Connection's
     * lifetime is [context]'s and still admitted - and otherwise the publication that refuses it. One read of the
     * supplier; without one nothing is refused. Safe on transport threads.
     */
    private fun permitRefusal(context: RequestContext): PermitWait? {
        val supplier = recoveryPermit ?: return null
        val permit = supplier()
        val admits = permit != null && permit.automatic && permit.fence == context.fence &&
            permit.fence == currentAccessFence() && (!permit.reapproved || permit.connectionGeneration != null &&
            permit.connectionLifetime == context.lifetime && uses.admits(context.lifetime))
        return if (admits) null else PermitWait(permit?.sessionKey, permit?.revision, context)
    }

    /** S4 RT03b-3b: a request the round issued itself, refused by the live permit; the refusal is kept for its release. */
    private fun permitRefuses(registration: Registration): Boolean {
        if (!registration.automaticRecovery) return false
        val refusal = permitRefusal(registration.context) ?: return false
        registration.permitRefusal.compareAndSet(null, refusal)
        return true
    }

    /** S4 RT03b-3b: a budget waiting on the permit stays parked while the publication and its context are unchanged. */
    private fun permitHeld(budget: RecoveryBudget, current: RequestContext): Boolean {
        val wait = budget.permitWait ?: return false
        val permit = recoveryPermit?.invoke()
        return wait == PermitWait(permit?.sessionKey, permit?.revision, current)
    }

    private fun recoveryHeld(budget: RecoveryBudget, current: RequestContext): Boolean =
        budget.hold?.let { it.context == current && it.activityGeneration == activityGeneration } == true

    private fun recoverySupported(key: GraphKey): Boolean =
        GraphV2Domain.support(snapshot.catalog, key.tab, key.period) != GraphPeriodSupport.Unsupported

    private fun recoveryDue(): Instant? {
        val budget = activeRecovery() ?: return null
        val key = checkNotNull(activeKey)
        val current = checkNotNull(context)
        if (budget.stop != null || recoveryHeld(budget, current) || permitHeld(budget, current) || !recoverySupported(key)) {
            return null
        }
        if (budget.round?.let { roundInFlight(it, key) } == true) return null
        return budget.deadline
    }

    private fun wakeRecovery(now: Instant) {
        val budget = activeRecovery() ?: return
        val key = checkNotNull(activeKey)
        val current = checkNotNull(context)
        if (budget.stop != null || recoveryHeld(budget, current) || permitHeld(budget, current) || !recoverySupported(key)) return
        val deadline = budget.deadline ?: return
        if (now < deadline || underFloor(now)) return
        budget.round?.let { open ->
            if (roundInFlight(open, key)) return
            // A round whose tab was sent and then lost is spent without a rung; one that sent only its
            // catalog stays open and uses its empty tab slot again.
            if (open.tabSent) budget.round = null
        }
        val round = budget.round ?: RecoveryRound(++nextRecoveryRound, budget).also { budget.round = it }
        if (!round.counted && (budget.rounds >= MAX_RECOVERY_ROUNDS || budget.completionRung >= MAX_RECOVERY_ROUNDS)) {
            budget.stop = RecoveryStop.EXHAUSTED
            budget.round = null
            return
        }
        when (recoveryNeed(key, current, now)) {
            RecoveryNeed.NONE -> recoveryBudgets.remove(budget.key)
            RecoveryNeed.UNREADABLE -> budget.hold = RecoveryHold(current, activityGeneration, RecoveryHold.Cause.UNREADABLE)
            RecoveryNeed.REMAINS -> {
                // S4 RT03b-3b: only a round with demand left is judged (a join included); None removes the budget and
                // Unreadable holds it above.
                budget.permitWait = permitRefusal(current)
                if (budget.permitWait == null) requestActive(now, force = true, recovery = round)
            }
        }
    }

    /**
     * S4 RT03b-3a: a valid trigger acts on every budget of the current data scope: it releases an UNREADABLE hold of a
     * waiting budget, resumes a PAUSED one with its counters and deadline, clearing its hold (or opens a new cycle once a
     * counter reached six), opens a new cycle in a new budget object for an EXHAUSTED one and leaves a TERMINAL one. With no
     * budget for the active absorbed key, a remaining demand opens one due now (an unreadable one opens it held); opening
     * the active key's budget cancels its flip. It sends nothing itself.
     */
    private fun onTrigger(trigger: GraphRecoveryTrigger, now: Instant) {
        val fresh = trigger.sequence > lastTriggerSequence
        lastTriggerSequence = maxOf(lastTriggerSequence, trigger.sequence)
        val current = context ?: return
        if (!fresh || trigger.fence != currentAccessFence() || trigger.fence != current.fence ||
            trigger.lifetime != current.lifetime || !uses.admits(trigger.lifetime)) return
        val dataScope = scopeOf(current.fence) ?: return
        recoveryBudgets.values.filter { it.key.scope == dataScope }.forEach { budget ->
            when (budget.stop) {
                null -> if (budget.hold?.cause == RecoveryHold.Cause.UNREADABLE) budget.hold = null
                RecoveryStop.PAUSED ->
                    if (budget.rounds < MAX_RECOVERY_ROUNDS && budget.completionRung < MAX_RECOVERY_ROUNDS) {
                        budget.stop = null
                        budget.hold = null
                    } else {
                        openCycle(budget.key, now)
                    }
                RecoveryStop.EXHAUSTED -> openCycle(budget.key, now)
                RecoveryStop.TERMINAL -> Unit
            }
        }
        val key = activeKey?.takeIf { absorbs(it) } ?: return
        val budgetKey = RecoveryKey(dataScope, key.tab)
        if (budgetKey in recoveryBudgets) return
        when (recoveryNeed(key, current, now)) {
            RecoveryNeed.NONE -> return
            RecoveryNeed.UNREADABLE -> openCycle(budgetKey, now).hold =
                RecoveryHold(current, activityGeneration, RecoveryHold.Cause.UNREADABLE)
            RecoveryNeed.REMAINS -> openCycle(budgetKey, now)
        }
        cancelFlip()
    }

    /**
     * S4 RT03b-3a: installs a new budget object due now. Registrations of a replaced cycle stay bound to its stopped object, so
     * none departs or counts in the new cycle; only a join rebinds an old sent tab to a new round (agreed r3 C).
     */
    private fun openCycle(key: RecoveryKey, now: Instant): RecoveryBudget =
        RecoveryBudget(key).also {
            it.deadline = now
            recoveryBudgets[key] = it
        }

    /**
     * An event failure pauses every waiting budget (a terminal or exhausted one keeps its cause) and withdraws the unsent
     * requests the budgets issued themselves.
     */
    private fun pauseRecovery() {
        recoveryBudgets.values.forEach {
            if (it.stop == null) it.stop = RecoveryStop.PAUSED
            it.round = null
        }
        (listOfNotNull(catalogRequest) + tabRequests.values.toList())
            .filter { it.recoveryAttempt && !it.departed }
            .forEach {
                releaseRegistration(it)
                endConnectedCold(it)
                endConnectedMidnight(it)
                endConnectedFlip(it)
            }
    }

    private fun cancelCold() { coldOwner = null }
    private fun cancelMidnight() { midnightOwner = null }
    private fun cancelFlip() { flipOwner = null }

    /**
     * A recovery budget of the active key, in any state, cancels the flip instead. Otherwise a flip adds
     * one active demand; registration, cold and timer are alternatives in that order.
     */
    private fun requireFlipConfirmation(now: Instant) {
        val key = activeKey ?: return
        val current = context ?: return
        if (activeRecovery() != null) {
            cancelFlip()
            return
        }
        if (!scope.isActive || snapshot.entries[key] == null || snapshot.entries[key]?.online200At != null ||
            GraphV2Domain.support(snapshot.catalog, key.tab, key.period) == GraphPeriodSupport.Unsupported) return
        val previous = flipOwner?.takeIf {
            it.key == key && it.context == current && it.activityGeneration == activityGeneration
        }
        val flip = previous ?: FlipOwner(++nextFlipGeneration, activityGeneration, key, current, now + FLIP_INTERVAL)
        flipOwner = flip
        tabRequests[key]?.takeIf { it.context == current && useAdmitted(it) }?.let {
            connectFlip(it)
            return
        }
        if (activeCold() != null) {
            cancelFlip()
            return
        }
        if (flip.deadline == null) flip.deadline = now + FLIP_INTERVAL
    }

    private fun activeCold(): ColdOwner? = coldOwner?.takeIf {
        it.key == activeKey && it.context == context && it.activityGeneration == activityGeneration
    }

    private fun connectFlip(registration: Registration) {
        val flip = flipOwner ?: return
        if (flip.key != activeKey || flip.context != context || flip.activityGeneration != activityGeneration ||
            registration.key != flip.key || registration.context != flip.context || !useAdmitted(registration)) return
        registration.flipGeneration = flip.generation
        flip.requestId = registration.requestId
        flip.deadline = null
    }

    private fun connectedFlip(registration: Registration): FlipOwner? = flipOwner?.takeIf {
        it.generation == registration.flipGeneration && it.requestId == registration.requestId &&
            it.key == registration.key && it.context == registration.context &&
            it.activityGeneration == activityGeneration
    }

    private fun endConnectedFlip(registration: Registration) {
        if (connectedFlip(registration) != null) cancelFlip()
    }

    private fun wakeFlip(now: Instant) {
        val flip = flipOwner ?: return
        if (flip.key != activeKey || flip.context != context || flip.activityGeneration != activityGeneration ||
            snapshot.entries[flip.key]?.online200At != null ||
            GraphV2Domain.support(snapshot.catalog, flip.key.tab, flip.key.period) == GraphPeriodSupport.Unsupported ||
            activeRecovery() != null) {
            cancelFlip()
            return
        }
        tabRequests[flip.key]?.takeIf { it.context == flip.context && useAdmitted(it) }?.let {
            connectFlip(it)
            return
        }
        if (activeCold() != null) {
            cancelFlip()
            return
        }
        val deadline = flip.deadline ?: return
        if (now >= deadline && !underFloor(now)) requestActive(now, force = true)
    }

    private fun cancelActiveDemand() {
        activityGeneration++
        discardSeed()
        cancelCold()
        cancelMidnight()
        cancelFlip()
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
        flipOwner?.takeIf { it.requestId == null }?.deadline?.let { candidates += afterFloor(it) }
        midnightOwner?.takeIf { it.requestId == null }?.let { midnight ->
            midnight.deadline?.let { deadline ->
                candidates += if (tabRequests.containsKey(midnight.key)) deadline else afterFloor(deadline)
            }
        }
        recoveryDue()?.let { candidates += afterFloor(it) }
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
        // S4 RT03b-3b: a request the permit refused waits on that publication; it reads no supplier here.
        val refusal = registration.permitRefusal.get()
        fun withdrawn(budget: RecoveryBudget) {
            if (refusal != null) {
                budget.permitWait = refusal
            } else {
                budget.hold = RecoveryHold(registration.context, registration.activityGeneration, RecoveryHold.Cause.WITHDRAWN)
            }
        }
        registration.recoveryRound?.let { round ->
            // An application clears this again; only a withdrawal of the current round's tab, or of a catalog a current
            // round awaits (below), keeps it.
            if (round.budget.round === round && round.tabRequestId == registration.requestId) withdrawn(round.budget)
        }
        // S4 RT03b-2b: a catalog withdrawn before an applicable completion leaves its waiting rounds' check open, held
        // like a withdrawn tab so that no past deadline re-arms the timer in the same context and activation.
        if (registration.key == null) recoveryBudgets.values.forEach { budget ->
            val round = budget.round ?: return@forEach
            if (round.awaitedCatalogId != registration.requestId) return@forEach
            round.awaitedCatalogId = null
            withdrawn(budget)
        }
        registration.disposed.set(true)
        if (registration.key == null) {
            if (catalogRequest === registration) catalogRequest = null
        } else if (tabRequests[registration.key] === registration) {
            tabRequests.remove(registration.key)
        }
        if (registration.completionPending || registration.handlingCompletion) {
            pendingReleased[registration.requestId] = registration
        } else {
            settleRegistration(registration)
        }
    }

    /** Resolving a released completion is only for cleanup; response application still requires registration. */
    private fun completionRegistration(event: Event): Registration? = when (event) {
        is Event.Captured -> event.registration
        is Event.CatalogFinished -> catalogRequest?.takeIf { it.requestId == event.requestId }
            ?: pendingReleased[event.requestId]
        is Event.TabFinished -> tabRequests[event.key]?.takeIf { it.requestId == event.requestId }
            ?: pendingReleased[event.requestId]
        else -> null
    }

    private inline fun <T> handleRequestCompletion(registration: Registration, apply: () -> T): T {
        val alreadyHandling = registration.handlingCompletion // Capture failures delegate to response handling.
        registration.completionPending = false
        registration.handlingCompletion = true
        try {
            return apply()
        } finally {
            registration.handlingCompletion = alreadyHandling
            settleRegistration(registration)
        }
    }

    private fun settleRegistration(registration: Registration) {
        if (registration.completionPending || registration.handlingCompletion) return
        if (pendingReleased[registration.requestId] === registration) pendingReleased.remove(registration.requestId)
        if (registration.disposed.get()) dropCaptures(registration)
    }

    private fun dropCaptures(registration: Registration) {
        registration.recoveryRequests = emptyList()
        registration.capturedOwner = null
        registration.recoveryRound = null
    }

    private fun releaseCompletion(event: Event) {
        completionRegistration(event)?.let {
            it.completionPending = false
            releaseRegistration(it)
            endConnectedCold(it)
            endConnectedMidnight(it)
            endConnectedFlip(it)
        }
    }

    private fun discardRequests() {
        (listOfNotNull(catalogRequest) + tabRequests.values.toList()).forEach { releaseRegistration(it) }
    }

    private fun publishState() {
        snapshot = snapshot.copy(inFlight = tabRequests.keys.toSet())
        protectedPublication = ProtectedPublication(context, protectedSlots)
        mutableState.value = snapshot
    }

    private fun publish() {
        publishState()
        // Even an immediate or multi-thread dispatcher must see the adopted publication first.
        val preparationJob = writePreparationToStart
        writePreparationToStart = null
        preparationJob?.start()
    }

    private companion object {
        val KST = TimeZone.of("Asia/Seoul")
        val FLIP_INTERVAL = 3.seconds
        val COLD_INTERVALS = listOf(3.seconds, 6.seconds, 12.seconds, 24.seconds, 48.seconds)
        val MIDNIGHT_INTERVALS = listOf(20.seconds, 40.seconds, 80.seconds)
        val MAX_RECOVERY_ROUNDS = COLD_INTERVALS.size + 1
    }
}
