package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGrantSink
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.time.AppClock
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow

/**
 * An allowed call publishes exactly the fence the deliverer hands over; an end (allowed = false)
 * publishes null even though the deliverer passes the ended fence. Issuer state is neither consulted
 * nor synthesized, and [TopicGrantOrigin] is not interpreted. Every accepted call notifies
 * [onChanged] once, after publication; a revision retains the delivered fence. [current] is a
 * stable, volatile-read supplier safe for transport threads. Calls and [close] share a lock,
 * including notification, so after close returns no call can republish or notify. Closing clears
 * the fence without notification.
 */
internal class GraphAccessFenceBridge(private val onChanged: () -> Unit) : TopicGrantSink {
    private val lock = Any()
    @Volatile private var published: TopicSessionFence? = null
    private var closed = false

    val current: () -> TopicSessionFence? = { published }

    override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
        synchronized(lock) {
            if (closed) return
            published = if (allowed) fence else null
            onChanged()
        }
    }

    override fun accessRevised() {
        synchronized(lock) {
            if (closed) return
            onChanged()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            published = null
        }
    }
}

/**
 * Reads one adopted coordinator publication on the recorder's serial executor. Its catalog is
 * returned only if the delivered fence has an epoch and its (uid, userAccessEpoch) exactly matches
 * that publication's data scope, and the publication's source has that fence and a lifetime [uses]
 * still admits (S4 RT03b-0): a catalog adopted under an earlier use of the same fence is not
 * returned before the coordinator synchronizes. Missing fences, missing epochs, missing sources and
 * other scopes return null; there is no fallback to a catalog from a previous scope. [coordinator]
 * is late-bound because the recorder is constructed first.
 */
internal fun graphRecorderCatalog(
    coordinator: () -> GraphV2RequestCoordinator,
    fence: () -> TopicSessionFence?,
    uses: TopicUseAuthority
): () -> GraphCatalog? = supply@{
    val current = fence() ?: return@supply null
    val epoch = current.userAccessEpoch ?: return@supply null
    val publication = coordinator().state.value
    val source = publication.source ?: return@supply null
    publication.catalog.takeIf {
        publication.dataScope == GraphDataScope(current.identity.uid, epoch) && source.fence == current &&
            uses.admits(source.lifetime)
    }
}

/**
 * Dormant graph runtime; no factory, startup, route or grant-deliverer fan-out is installed here.
 * The recorder, coordinator and sink share one scope, SupervisorJob(parent) + [main]; the bridge and
 * gate launch nothing. The dispatcher must be the same always-dispatching Main for the coordinator
 * loop, recorder collector and calls, and sink worker. The caller also runs [start] and [close] on
 * that Main; neither method hops executors.
 * Snapshot, live identity and protected admission suppliers must support transport-thread reads.
 *
 * Construction creates the bridge, then gate -> recorder -> coordinator -> deferred sink. The
 * catalog supplier and bridge callback are late-bound to that coordinator. No collector, loop or
 * worker starts during construction. On construction failure, already-created components are
 * closed, the assembly job is cancelled, and the original exception is rethrown. [start] starts
 * coordinator -> recorder collector -> sink worker once; the bridge and sink accept inputs earlier.
 *
 * [close] first closes the bridge, then sink (which closes the recorder), then coordinator, before
 * cancelAndJoin on the assembly job. Explicit component cleanup works even before first dispatch;
 * cancelAndJoin is completion waiting, not a guarantee that a launch body or finally has run. A close
 * call, including one concurrent with a prior close's wait, returns normally only after all assembly
 * children have ended. The wait is cancellable: a caller that is already cancelled, or is itself an
 * assembly child, still runs a first call's synchronous cleanup and the cancellation request, but its
 * join throws CancellationException without waiting.
 *
 * Parent cancellation cancels the assembly job, which closes sink acceptance and ends its coroutines,
 * but runs no other component cleanup: the bridge keeps publishing and notifying, the recorder stays
 * open, and the coordinator is cleaned only if its loop body had started. [close] is still required.
 * Closing the assembly never cancels parent.
 */
internal class GraphRuntimeAssembly(
    accessSnapshot: () -> TopicAccessSnapshot,
    accessRevisions: StateFlow<Long>,
    liveIdentity: () -> AuthIdentityFence?,
    uses: TopicUseAuthority,
    protectedAdmission: () -> Boolean,
    fetcher: GraphV2Fetching,
    owners: GraphOwnerSource,
    cachePorts: (GraphV2AccessGate) -> GraphV2CachePorts?,
    main: CoroutineDispatcher,
    parent: Job,
    clock: AppClock,
    rateLimitJitter: (String) -> Duration,
    onEventFailure: (Throwable) -> Unit
) {
    private val assemblyJob = SupervisorJob(parent)
    private val scope = CoroutineScope(assemblyJob + main)
    private var started = false
    private var closed = false

    val fences: GraphAccessFenceBridge
    val gate: GraphV2AccessGate
    val recorder: GraphRecorder
    val coordinator: GraphV2RequestCoordinator
    val sink: GraphRecorderTopicSink

    init {
        lateinit var requests: GraphV2RequestCoordinator
        fences = GraphAccessFenceBridge { requests.onContextChanged() }
        var createdRecorder: GraphRecorder? = null
        var createdCoordinator: GraphV2RequestCoordinator? = null
        try {
            gate = GraphV2AccessGate(liveIdentity, fences.current, accessSnapshot, protectedAdmission)
            recorder = GraphRecorder(
                scope, accessRevisions, accessSnapshot, fences.current,
                graphRecorderCatalog({ requests }, fences.current, uses), gate, clock
            ).also { createdRecorder = it }
            coordinator = GraphV2RequestCoordinator(
                fetcher = fetcher,
                owners = owners,
                currentAccessFence = fences.current,
                uses = uses,
                protectedAdmission = protectedAdmission,
                accessSnapshot = accessSnapshot,
                scope = scope,
                clock = clock,
                rateLimitJitter = rateLimitJitter,
                onEventFailure = onEventFailure,
                cachePorts = cachePorts(gate),
                recorder = recorder
            ).also { requests = it; createdCoordinator = it }
            sink = GraphRecorderTopicSink(scope, recorder, startImmediately = false)
        } catch (failure: Throwable) {
            fences.close()
            // No component has started; each cleanup is synchronous and needs no completion wait.
            runCatching { createdRecorder?.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            runCatching { createdCoordinator?.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            assemblyJob.cancel()
            throw failure
        }
    }

    fun start() {
        if (closed || started || !assemblyJob.isActive) return
        started = true
        coordinator.start()
        recorder.start()
        sink.start()
    }

    suspend fun close() {
        try {
            if (!closed) {
                closed = true
                fences.close()
                sink.close()
                coordinator.close()
            }
        } finally {
            // Even an already-closed assembly must wait for a prior close's cancellation to finish.
            assemblyJob.cancelAndJoin()
        }
    }
}
