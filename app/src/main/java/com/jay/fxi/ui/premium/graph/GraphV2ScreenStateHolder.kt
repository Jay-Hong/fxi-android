package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.GraphRequestState
import com.jay.fxi.data.graph.GraphSelectionApplyResult
import com.jay.fxi.data.graph.GraphSelectionBinding
import com.jay.fxi.data.graph.GraphSelectionPublication
import com.jay.fxi.data.graph.GraphSelectionRestoreResult
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2Domain
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphPeriodSupport
import com.jay.fxi.domain.model.GraphSelectionChange
import com.jay.fxi.domain.model.GraphSelectionUniverse
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.GraphSeriesSelectionPolicy
import com.jay.fxi.time.AppClock
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyle
import com.jay.fxi.ui.graph.GraphSeriesStyles
import com.jay.fxi.ui.graph.PreparedGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

internal enum class GraphV2Surface { INLINE, FULLSCREEN }

internal enum class GraphV2SelectionStatus {
    UNBOUND, AWAITING_RESTORE, INITIALIZING, READY, CONFIRMING, UNREADABLE
}

internal enum class GraphV2Notice { SAVE_NOT_COMMITTED }

internal data class GraphV2SeriesToggle(
    val seriesId: String,
    val style: GraphSeriesStyle,
    val selected: Boolean,
    val enabled: Boolean,
    val axisGroup: String
)

@ConsistentCopyVisibility
internal data class GraphV2UiToken internal constructor(
    val owner: TopicDisplayOwner,
    val fence: TopicSessionFence,
    val lifetime: TopicUseLifetime,
    val binding: GraphSelectionBinding,
    val tab: String,
    val period: GraphPeriod,
    val screenGeneration: Long,
    val surface: GraphV2Surface,
    val surfaceGeneration: Long
)

internal data class GraphV2ScreenState(
    val tab: String,
    val activePeriod: GraphPeriod,
    val periods: List<GraphPeriod>,
    val content: GraphV2Content,
    val selectionStatus: GraphV2SelectionStatus,
    val selection: GraphSeriesSelection?,
    val toggles: List<GraphV2SeriesToggle>,
    val chart: GraphV2ChartModel?,
    val refreshing: Boolean,
    val requestFailure: Throwable?,
    val notice: GraphV2Notice?,
    val fullscreenOpen: Boolean,
    val inlineToken: GraphV2UiToken?,
    val fullscreenToken: GraphV2UiToken?
)

internal data class GraphV2PreparationKey(
    val contextGeneration: Long,
    val tab: String,
    val period: GraphPeriod,
    val graph: FreeGraph
)

/**
 * The host starts and owns the shared request coordinator.
 * Lifecycle, rendering and workers use the same serial dispatcher as the selection session and coordinator.
 * A renderer observes [state] for invalidation and reads [currentState] immediately before drawing.
 */
internal class GraphV2ScreenStateHolder(
    private val tab: String,
    private val coordinator: GraphV2RequestCoordinator,
    private val selectionSession: GraphSeriesSelectionSession,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val display: StateFlow<TopicDisplayState>,
    private val focus: StateFlow<OwnedTopicFocus?>,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val uses: TopicUseAuthority,
    private val gate: GraphV2AccessGate,
    private val accessRevisions: StateFlow<Long>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val live: StateFlow<Map<GraphObservationSeriesKey, GraphRecoverableState>> = MutableStateFlow(emptyMap()),
    private val clock: AppClock = SystemAppClock
) {
    init {
        require(tab in setOf("usd", "jpy", "eur")) { "Premium FX graph tab required" }
    }

    private data class Context(
        val owner: TopicDisplayOwner,
        val fence: TopicSessionFence,
        val lifetime: TopicUseLifetime,
        val binding: GraphSelectionBinding
    )

    private data class Activation(val context: Context, val key: GraphKey)

    private data class Exposure(
        val graph: FreeGraph,
        val universe: GraphSelectionUniverse,
        val krxVisible: Boolean
    )

    private data class InitializationKey(
        val token: GraphV2UiToken,
        val exposure: Exposure
    )

    private data class WriteOperation(
        val id: Long,
        val token: GraphV2UiToken,
        val contextGeneration: Long,
        val initialization: InitializationKey? = null
    )

    private data class RestoreOperation(val id: Long, val binding: GraphSelectionBinding, val confirming: Boolean)

    private data class LivePublication(
        val prepared: PreparedGraph,
        val now: Instant
    )

    private enum class Revision { RENDER, CONTEXT_CHANGED }

    // The session worker is a sibling, not a child of this Job: close must let its started I/O finish.
    private val holderJob = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    private val holderScope = CoroutineScope(scope.coroutineContext + holderJob + dispatcher)
    private val revisions = Channel<Revision>(Channel.UNLIMITED)
    private var started = false
    private var closed = false
    private val closeCompleted = CompletableDeferred<Unit>()
    private var boundOnce = false
    private var boundIdentity: AuthIdentityFence? = null
    private var binding: GraphSelectionBinding? = null
    private var activeOwner: TopicDisplayOwner? = null
    private var context: Context? = null
    private var activation: Activation? = null
    private var activePeriod = GraphPeriod.ONE_DAY
    private var screenGeneration = 0L
    private var contextGeneration = 0L
    private var fullscreenGeneration = 0L
    private var fullscreenOpen = false
    private var preparationKey: GraphV2PreparationKey? = null
    private var prepared: PreparedGraph? = null
    private var inlineVisible = false
    private var fullscreenVisible = false
    private var livePublication: LivePublication? = null
    private var livePublishJob: Job? = null
    private var initializationAttempt: InitializationKey? = null
    private var initializing: WriteOperation? = null
    private var restoring: RestoreOperation? = null
    private var operationId = 0L
    private var notice: GraphV2Notice? = null
    private var retiredRequestState: GraphRequestState? = null
    private var retiredContext: Context? = null
    private val mutableState = MutableStateFlow(emptyState(GraphV2Content.INACTIVE))
    val state: StateFlow<GraphV2ScreenState> = mutableState.asStateFlow()

    fun start() {
        if (started || closed) return
        started = true
        synchronizeBinding()
        holderScope.launch {
            for (revision in revisions) reconcile(notifyCoordinator = revision == Revision.CONTEXT_CHANGED)
        }
        observe(display, contextChanged = true)
        observe(focus, contextChanged = true)
        observe(accessRevisions, contextChanged = true)
        observe(coordinator.state)
        observe(selectionSession.publication)
        holderScope.launch {
            live.collect { scheduleLivePublication() }
        }
        signal()
    }

    private fun <T> observe(flow: StateFlow<T>, contextChanged: Boolean = false) {
        holderScope.launch { flow.collect { if (contextChanged) onContextChanged() else signal() } }
    }

    /** Auth callbacks may be delivered while identity is being read; enqueue without reading here. */
    fun onContextChanged() {
        revisions.trySend(Revision.CONTEXT_CHANGED)
    }

    fun currentState(): GraphV2ScreenState = currentState(publish = false)

    private fun currentState(publish: Boolean): GraphV2ScreenState {
        val capturedContext = context
        val capturedAccess = capturedContext?.let { gate.bindWithConfiguration(it.fence, it.lifetime) }
        val candidate = render(publish)
        // Re-read live sources after exposure and preparation, immediately before publication.
        val current = context
        val safe = if (current != null && !isCurrent(current)) {
            retireContext()
            emptyState(if (activeOwner == null || closed) GraphV2Content.INACTIVE else GraphV2Content.BLOCKED)
        } else if (current != null && gate.bindWithConfiguration(current.fence, current.lifetime) != capturedAccess) {
            clearPreparation()
            clearSurfaceReports()
            fullscreenOpen = false
            signal()
            emptyState(GraphV2Content.BLOCKED)
        } else candidate
        mutableState.value = safe
        return safe
    }

    fun onActivated(owner: TopicDisplayOwner) {
        if (!started || closed) return
        currentState()
        val next = acquireContext(owner) ?: return
        activeOwner = owner
        if (context != next) {
            retireContext()
            context = next
        }
        activateKey(next)
        currentState()
        signal()
    }

    fun onDeactivated() {
        if (closed) return
        activeOwner = null
        retireContext()
        releaseActivation()
        currentState()
    }

    fun selectPeriod(token: GraphV2UiToken, period: GraphPeriod) {
        if (!accepts(token)) return
        val current = checkNotNull(context)
        val catalog = requestState(current)?.catalog
        if (GraphV2Domain.support(catalog, tab, period) == GraphPeriodSupport.Unsupported || period == activePeriod) return
        activePeriod = period
        advanceScreen(closeFullscreen = false)
        activateKey(current)
        currentState()
        signal()
    }

    fun toggleSeries(token: GraphV2UiToken, seriesId: String) {
        if (!accepts(token)) return
        val now = currentState()
        if (now.selectionStatus != GraphV2SelectionStatus.READY || now.toggles.none { it.seriesId == seriesId && it.enabled }) return
        val operation = WriteOperation(nextOperationId(), token, contextGeneration)
        holderScope.launch {
            val result = selectionSession.apply(token.binding) { current ->
                // This executes in the session's queue, after any earlier save has finished.
                if (!accepts(token)) return@apply GraphSelectionChange.Rejected("Graph context retired")
                val exposed = expose(checkNotNull(context))
                    ?: return@apply GraphSelectionChange.Rejected("Graph is not exposed")
                if (current == null || GraphSeriesSelectionPolicy.initialize(current, tab, exposed.universe, exposed.krxVisible)
                    is GraphSelectionChange.Replace) return@apply GraphSelectionChange.Rejected("Selection is not initialized")
                GraphSeriesSelectionPolicy.toggle(
                    current, tab, seriesId, exposed.graph.series.map { it.seriesId }.toSet(), exposed.krxVisible
                )
            }
            completeWrite(operation, result)
        }
    }

    fun enterFullscreen(token: GraphV2UiToken) {
        if (!accepts(token) || fullscreenOpen) return
        fullscreenGeneration = Math.incrementExact(fullscreenGeneration)
        fullscreenOpen = true
        currentState()
    }

    fun exitFullscreen(token: GraphV2UiToken) {
        if (token.surface != GraphV2Surface.FULLSCREEN || !accepts(token)) return
        fullscreenGeneration = Math.incrementExact(fullscreenGeneration)
        fullscreenOpen = false
        fullscreenVisible = false
        if (!surfaceVisible()) dropLivePublication()
        currentState()
    }

    fun setSurfaceVisible(token: GraphV2UiToken, visible: Boolean) {
        if (!accepts(token)) return
        val wasVisible = surfaceVisible()
        when (token.surface) {
            GraphV2Surface.INLINE -> inlineVisible = visible
            GraphV2Surface.FULLSCREEN -> fullscreenVisible = visible
        }
        val isVisible = surfaceVisible()
        if (!wasVisible && isVisible) publishCurrentLive()
        else if (wasVisible && !isVisible) {
            dropLivePublication()
            currentState()
        }
    }

    fun onTimeEvent() {
        publishCurrentLive()
    }

    private fun surfaceVisible(): Boolean = inlineVisible || fullscreenVisible

    private fun clearSurfaceReports() {
        inlineVisible = false
        fullscreenVisible = false
    }

    private fun cancelLivePublication() {
        livePublishJob?.cancel()
        livePublishJob = null
    }

    private fun dropLivePublication() {
        cancelLivePublication()
        livePublication = null
    }

    private fun scheduleLivePublication() {
        val screen = currentState()
        if (!surfaceVisible() || screen.chart == null || livePublishJob != null) return
        livePublishJob = holderScope.launch {
            delay(350L)
            livePublishJob = null
            publishCurrentLive()
        }
    }

    private fun publishCurrentLive() {
        currentState(publish = true)
    }

    private fun publishLive(current: Context, key: GraphV2PreparationKey, rest: PreparedGraph) {
        cancelLivePublication()
        val now = clock.now()
        val scope = GraphDataScope(current.binding.identity.uid, checkNotNull(current.fence.userAccessEpoch))
        livePublication = LivePublication(
            projectGraphV2Live(key.graph, rest, key.period, scope, live.value, now), now
        )
    }

    fun retrySelection(token: GraphV2UiToken) {
        if (!accepts(token) || restoring != null || initializing != null) return
        initializationAttempt = null
        restore(token.binding, confirming = true)
        currentState()
    }

    suspend fun close() {
        if (closed) {
            closeCompleted.await()
            return
        }
        closed = true
        activeOwner = null
        retireContext()
        releaseActivation()
        currentState()
        try {
            selectionSession.close()
            closeCompleted.complete(Unit)
        } catch (failure: Throwable) {
            closeCompleted.completeExceptionally(failure)
            throw failure
        } finally {
            revisions.close()
            holderJob.cancel()
        }
    }

    private fun signal() {
        if (started && !closed) revisions.trySend(Revision.RENDER)
    }

    private fun reconcile(notifyCoordinator: Boolean) {
        if (closed) return
        val previous = context
        currentState() // Discard the old screen before any bind or coordinator command.
        val identityChanged = !boundOnce || boundIdentity != liveIdentity()
        synchronizeBinding()
        if (activeOwner != null && (notifyCoordinator || identityChanged || previous != context || context == null)) {
            coordinator.onContextChanged()
        }
        val owner = activeOwner
        if (owner != null && context == null) context = acquireContext(owner)
        val current = context
        if (current == null) releaseActivation() else activateKey(current)
        if (current != null) {
            val publication = selectionSession.publication.value as? GraphSelectionPublication.WriteUncertain
            // The binding can outlive its display owner; recover under the current context, not a retired callback.
            if (restoring == null && publication != null) {
                restore(current.binding, confirming = true)
            }
            initialize(current)
        }
        currentState()
    }

    private fun synchronizeBinding() {
        val identity = liveIdentity()
        if (boundOnce && identity == boundIdentity) return
        retireContext()
        boundOnce = true
        boundIdentity = identity
        restoring = null
        binding = selectionSession.bind(identity)?.also {
            require(it.key.audience == GraphSelectionAudience.PREMIUM && it.key.tab == tab) {
                "Selection session must belong to this premium FX tab"
            }
        }
        notice = null
        binding?.let { restore(it, confirming = false) }
    }

    private fun acquireContext(owner: TopicDisplayOwner): Context? {
        val current = context
        if (current != null && current.owner == owner && isCurrent(current)) {
            return current.takeIf { gate.bindWithConfiguration(it.fence, it.lifetime) != null }
        }
        val identity = liveIdentity() ?: return null
        val fence = currentAccessFence() ?: return null
        val binding = binding ?: return null
        if (owner != display.value.owner || owner.identity != identity || fence.identity != identity ||
            binding.identity != identity || publicationBinding() != binding ||
            focus.value?.let { it.identity == identity && it.tab.serverTab == tab } != true) return null
        val lifetime = uses.acquire(fence) ?: return null
        if (gate.bindWithConfiguration(fence, lifetime) == null) return null
        return Context(owner, fence, lifetime, binding).takeIf { isLive(it) }
    }

    private fun isLive(candidate: Context): Boolean =
        liveIdentity() == candidate.binding.identity && display.value.owner == candidate.owner &&
            currentAccessFence() == candidate.fence && focus.value?.let {
                it.identity == candidate.binding.identity && it.tab.serverTab == tab
            } == true && binding == candidate.binding && publicationBinding() == candidate.binding &&
            uses.admits(candidate.lifetime)

    private fun isCurrent(candidate: Context): Boolean =
        !closed && started && activeOwner == candidate.owner && context == candidate && isLive(candidate)

    private fun publicationBinding(): GraphSelectionBinding? = when (val publication = selectionSession.publication.value) {
        GraphSelectionPublication.Unbound -> null
        is GraphSelectionPublication.AwaitingRestore -> publication.binding
        is GraphSelectionPublication.Restored -> publication.binding
        is GraphSelectionPublication.WriteUncertain -> publication.binding
    }

    private fun retireContext() {
        clearSurfaceReports()
        val previous = context
        if (previous != null) {
            // Old failures/catalog must not be attributed to a new auth/fence/use before the request loop catches up.
            retiredRequestState = coordinator.state.value
            retiredContext = previous
            context = null
            activePeriod = GraphPeriod.ONE_DAY
            contextGeneration = Math.incrementExact(contextGeneration)
            advanceScreen()
        } else {
            clearPreparation()
            fullscreenOpen = false
        }
        signal()
    }

    private fun advanceScreen(closeFullscreen: Boolean = true) {
        screenGeneration = Math.incrementExact(screenGeneration)
        if (closeFullscreen) fullscreenOpen = false
        clearPreparation()
        initializationAttempt = null
        initializing = null
        notice = null
    }

    private fun clearPreparation() {
        dropLivePublication()
        preparationKey = null
        prepared = null
    }

    private fun activateKey(current: Context) {
        val next = Activation(current, GraphKey(tab, activePeriod))
        if (activation == next) return
        activation = next
        coordinator.onActivated(next.key)
    }

    private fun releaseActivation() {
        if (activation == null) return
        activation = null
        coordinator.onDeactivated()
    }

    private fun requestState(current: Context): GraphRequestState? {
        val snapshot = coordinator.state.value
        val epoch = current.fence.userAccessEpoch ?: return null
        if (snapshot.dataScope != GraphDataScope(current.binding.identity.uid, epoch)) return null
        if (snapshot === retiredRequestState &&
            (current.fence != retiredContext?.fence || current.lifetime != retiredContext?.lifetime)) return null
        return snapshot
    }

    private fun expose(current: Context): Exposure? {
        if (!isCurrent(current)) return null
        val access = gate.bindWithConfiguration(current.fence, current.lifetime) ?: return null
        val catalog = requestState(current)?.catalog
        if (GraphV2Domain.support(catalog, tab, activePeriod) == GraphPeriodSupport.Unsupported) return null
        val entry = coordinator.protectedEntry(GraphKey(tab, activePeriod)) ?: return null
        val registry = catalog?.tabs?.get(tab)?.periods?.get(activePeriod)
        val krxVisible = access.captured.krxCapabilityEpoch != null
        val allowed = registry?.allSeries?.toSet()
        val graph = entry.tab.graph.copy(series = entry.tab.graph.series.filter {
            (allowed == null || it.seriesId in allowed) && (krxVisible || !it.seriesId.startsWith("krx."))
        })
        val universe = when {
            catalog == null -> GraphSelectionUniverse.CatalogUnavailable(graph.series.map { it.seriesId }.toSet())
            registry != null -> GraphSelectionUniverse.Catalog(registry.allSeries.toSet(), registry.defaultVisible.toSet())
            else -> GraphSelectionUniverse.Unsupported
        }
        return Exposure(graph, universe, krxVisible).takeIf {
            isCurrent(current) && gate.bindWithConfiguration(current.fence, current.lifetime) == access
        }
    }

    private fun confirmedRead(): GraphSelectionReadResult? {
        val publication = selectionSession.publication.value as? GraphSelectionPublication.Restored ?: return null
        return publication.read.takeIf { publication.binding == binding }
    }

    private fun selection(read: GraphSelectionReadResult): GraphSeriesSelection? = when (read) {
        GraphSelectionReadResult.Absent -> null
        is GraphSelectionReadResult.Present -> read.record.selection
        is GraphSelectionReadResult.Unreadable -> null
    }

    private fun selectionStatus(exposure: Exposure? = null): GraphV2SelectionStatus {
        if (binding == null || publicationBinding() != binding) return GraphV2SelectionStatus.UNBOUND
        if (restoring?.binding == binding && restoring?.confirming == true ||
            selectionSession.publication.value is GraphSelectionPublication.WriteUncertain) return GraphV2SelectionStatus.CONFIRMING
        val read = confirmedRead() ?: return GraphV2SelectionStatus.AWAITING_RESTORE
        if (read is GraphSelectionReadResult.Unreadable) return GraphV2SelectionStatus.UNREADABLE
        if (initializing != null) return GraphV2SelectionStatus.INITIALIZING
        // A failed initialize leaves the confirmed selection uninitialized.
        if (exposure != null &&
            GraphSeriesSelectionPolicy.initialize(selection(read), tab, exposure.universe, exposure.krxVisible)
            is GraphSelectionChange.Replace) return GraphV2SelectionStatus.INITIALIZING
        return GraphV2SelectionStatus.READY
    }

    private fun initialize(current: Context) {
        if (!isCurrent(current) || initializing != null || restoring != null) return
        val read = confirmedRead() ?: return
        if (read is GraphSelectionReadResult.Unreadable) return
        val exposure = expose(current) ?: return
        val key = InitializationKey(token(current, GraphV2Surface.INLINE), exposure)
        if (initializationAttempt == key) return
        val change = GraphSeriesSelectionPolicy.initialize(selection(read), tab, exposure.universe, exposure.krxVisible)
        if (change !is GraphSelectionChange.Replace) return
        initializationAttempt = key
        val operation = WriteOperation(nextOperationId(), key.token, contextGeneration, key)
        initializing = operation
        holderScope.launch {
            val result = selectionSession.apply(current.binding) { selection ->
                if (!accepts(key.token)) return@apply GraphSelectionChange.Rejected("Graph context retired")
                val exposed = expose(current)
                if (exposed != key.exposure) return@apply GraphSelectionChange.Unchanged
                GraphSeriesSelectionPolicy.initialize(selection, tab, exposed.universe, exposed.krxVisible)
            }
            completeWrite(operation, result)
        }
    }

    private fun completeWrite(operation: WriteOperation, result: GraphSelectionApplyResult) {
        if (initializing == operation) initializing = null
        currentState()
        val current = context ?: return
        val token = operation.token
        // A started save belongs to the logical owner, even when its period or fullscreen callback has since expired.
        // Retired owner/use callbacks must not update a replacement context.
        if (!isCurrent(current) || operation.contextGeneration != contextGeneration ||
            token.owner != current.owner || token.fence != current.fence || token.lifetime != current.lifetime ||
            token.binding != current.binding || token.tab != tab) return
        when (result) {
            is GraphSelectionApplyResult.NotCommitted -> notice = GraphV2Notice.SAVE_NOT_COMMITTED
            GraphSelectionApplyResult.Committed -> notice = null
            else -> Unit
        }
        currentState()
        signal()
    }

    private fun restore(binding: GraphSelectionBinding, confirming: Boolean) {
        val operation = RestoreOperation(nextOperationId(), binding, confirming)
        restoring = operation
        holderScope.launch {
            val result = selectionSession.restore(binding)
            if (closed || this@GraphV2ScreenStateHolder.binding != binding || restoring != operation) return@launch
            restoring = null
            if (result is GraphSelectionRestoreResult.Applied && result.read !is GraphSelectionReadResult.Unreadable) {
                initializationAttempt = null
            }
            currentState()
            signal()
        }
    }

    private fun nextOperationId(): Long {
        operationId = Math.incrementExact(operationId)
        return operationId
    }

    private fun token(current: Context, surface: GraphV2Surface) = GraphV2UiToken(
        current.owner, current.fence, current.lifetime, current.binding, tab, activePeriod,
        screenGeneration, surface, if (surface == GraphV2Surface.INLINE) 0L else fullscreenGeneration
    )

    private fun accepts(candidate: GraphV2UiToken): Boolean {
        currentState()
        val current = context ?: return false
        if (!isCurrent(current) || gate.bindWithConfiguration(current.fence, current.lifetime) == null) return false
        if (candidate.surface == GraphV2Surface.FULLSCREEN && !fullscreenOpen) return false
        return candidate == token(current, candidate.surface)
    }

    private fun render(publish: Boolean): GraphV2ScreenState {
        if (!started || closed || activeOwner == null) return emptyState(GraphV2Content.INACTIVE)
        val current = context ?: return emptyState(GraphV2Content.BLOCKED)
        if (gate.bindWithConfiguration(current.fence, current.lifetime) == null) {
            clearPreparation()
            clearSurfaceReports()
            fullscreenOpen = false
            return emptyState(GraphV2Content.BLOCKED)
        }
        val snapshot = requestState(current)
        // Android intentionally offers only this tab's catalog periods; null alone falls back to all four.
        val periods = GraphV2Domain.periods(snapshot?.catalog, tab)
        val inline = token(current, GraphV2Surface.INLINE)
        val fullscreen = if (fullscreenOpen) token(current, GraphV2Surface.FULLSCREEN) else null
        val key = GraphKey(tab, activePeriod)
        val exposure = if (activePeriod in periods) expose(current) else null
        val status = selectionStatus(exposure)
        val confirmed = if (status == GraphV2SelectionStatus.READY) confirmedRead()?.let {
            selection(it) ?: GraphSeriesSelection(emptySet(), emptySet())
        } else null
        val inFlight = key in snapshot?.inFlight.orEmpty()
        val failure = snapshot?.failures?.get(key)
        val chart = if (exposure != null && confirmed != null) {
            val next = GraphV2PreparationKey(screenGeneration, tab, activePeriod, exposure.graph)
            val changed = preparationKey != next
            if (changed) {
                prepared = GraphPreparedBuilder.build(next.graph, next.period)
                preparationKey = next
            }
            if (surfaceVisible() && (changed || publish)) publishLive(current, next, checkNotNull(prepared))
            val published = livePublication
            val built = published?.prepared ?: checkNotNull(prepared)
            GraphV2ChartModel(built,
                GraphSeriesSelectionPolicy.renderedIds(confirmed, built.bySeries.keys, exposure.krxVisible), published?.now)
        } else {
            if (exposure == null) clearPreparation()
            null
        }
        val toggles = exposure?.graph?.series.orEmpty().map {
            GraphV2SeriesToggle(it.seriesId, GraphSeriesStyles.of(it.seriesId, it.label),
                confirmed?.visibleSeriesIds?.contains(it.seriesId) == true, status == GraphV2SelectionStatus.READY,
                requireNotNull(it.axisGroup))
        }
        val content = when {
            activePeriod !in periods -> GraphV2Content.UNSUPPORTED
            exposure == null -> if (!inFlight && failure != null) GraphV2Content.ERROR else GraphV2Content.LOADING
            chart == null -> GraphV2Content.SELECTION_PENDING
            else -> GraphV2ScreenPresenter.content(chart.prepared, chart.renderedIds)
        }
        // A failed attempt stays pending without continually scheduling the same blocked initialization.
        if (status == GraphV2SelectionStatus.INITIALIZING && initializing == null && exposure != null &&
            initializationAttempt != InitializationKey(inline, exposure)) signal()
        return GraphV2ScreenState(tab, activePeriod, periods, content, status, confirmed, toggles, chart,
            exposure != null && inFlight, failure, notice, fullscreenOpen, inline, fullscreen)
    }

    private fun emptyState(content: GraphV2Content) = GraphV2ScreenState(
        tab, activePeriod, emptyList(), content, selectionStatus(), null, emptyList(), null,
        false, null, notice, false, null, null
    )
}
