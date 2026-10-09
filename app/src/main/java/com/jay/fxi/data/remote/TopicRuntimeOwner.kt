package com.jay.fxi.data.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.graph.AppProcessGraphBuilder
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.LateBound
import com.jay.fxi.data.graph.ProcessGraphBuilder
import com.jay.fxi.data.graph.ProcessGraphStarter
import com.jay.fxi.data.graph.reportGraphFailure
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.network.NetworkMonitor
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The process's foreground: on registration its current state, once, before observe() returns; after that each change, true
 * when it comes to the foreground and false when it leaves (S4 CUT-CC4b). Main thread.
 */
internal fun interface TopicForegroundStream {
    fun observe(onForeground: (Boolean) -> Unit)
}

/**
 * The process owner of one topic runtime and its Main consumer. Activity and route lifetimes do not stop either.
 */
@Singleton
internal class TopicRuntimeOwner internal constructor(
    private val factory: TopicRuntimeFactory,
    private val online: StateFlow<Boolean>,
    private val foreground: TopicForegroundStream,
    private val fences: AuthFenceStream,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val rowPreferenceStore: RateRowPreferenceStore,
    private val main: CoroutineScope,
    /** S4 CUT-CC4b: builds the process graph inside start()'s graph boundary; null builds none. */
    private val graphBuilder: ProcessGraphBuilder? = null,
    /** S4 CUT-CC4b: where a recoverable graph failure is reported, once. */
    private val reportGraph: (Throwable) -> Unit = {}
) {
    @Inject
    constructor(
        factory: TopicRuntimeFactory,
        networkMonitor: NetworkMonitor,
        authTokenProvider: AuthTokenProvider,
        fences: AuthFenceStream,
        rowPreferenceStore: RateRowPreferenceStore,
        graphBuilder: AppProcessGraphBuilder
    ) : this(
        factory,
        networkMonitor.isConnected,
        ProcessTopicForegroundStream(),
        fences,
        authTokenProvider::currentIdentityFence,
        rowPreferenceStore,
        CoroutineScope(Dispatchers.Main + SupervisorJob()),
        graphBuilder,
        ::reportGraphFailure
    )

    private var attempted = false
    private var processConsumer: PremiumTopicConsumer? = null
    // Written last on Main, read by the cache cutover on IO; also publishes the consumer reference.
    @Volatile private var ready = false

    /**
     * On the Main thread, once per process: builds the process graph and its starter, creates the runtime with the graph's
     * sink and bridge, binds the runtime to the starter, starts the runtime and the consumer on [main], installs network,
     * foreground and identity forwarding, starts the graph, then records readiness (S4 CUT-CC4b, `cut_cc4b_agreed.r1.md` §2).
     * Online input stays false until the first foreground, whose callback passes the current [online] value directly. A graph
     * failure is reported once and gives up the graph alone; the topic goes on. A topic failure first secures the graph's
     * cleanup, then rethrows and leaves the owner not ready. Later calls do nothing.
     */
    fun start() {
        if (attempted) return
        attempted = true
        val graph = ProcessGraphStartup(graphBuilder, main, reportGraph)
        graph.assemble()
        val runtime = try {
            factory.create(graph.sink, graph.grants)
        } catch (failure: Throwable) {
            graph.abandon(failure, graphFailure = false)
            throw failure
        }
        graph.bind(runtime)
        try {
            install(runtime, graph)
        } catch (failure: Throwable) {
            graph.abandon(failure, graphFailure = false)
            throw failure
        }
        graph.start()
        ready = true
    }

    private fun install(runtime: TopicRuntime, graph: ProcessGraphStartup) {
        runtime.start()
        val installed = PremiumTopicConsumer(
            display = runtime.display,
            focus = runtime.focus,
            liveIdentity = liveIdentity,
            rowPreferenceStore = rowPreferenceStore,
            selectTab = runtime::selectTab,
            retryConnection = runtime::retryConnection,
            retryTopics = runtime::retryTopics,
            scope = main
        )
        installed.start()
        processConsumer = installed
        // The collector and foreground callback run on Main. Once opened, this process gate stays open across backgrounding.
        var activated = false
        runtime.setOnline(false)
        main.launch { online.collect { runtime.setOnline(activated && it) } }
        foreground.observe { isForeground ->
            runtime.setForeground(isForeground)
            if (isForeground && !activated) {
                activated = true
                runtime.setOnline(online.value)
            }
            graph.onForeground(isForeground)
        }
        // AuthFenceStream can deliver while a source/coordinator lock is held. Enqueue only;
        // the non-immediate Main dispatcher reads live identity after the callback has returned.
        fences.observe { main.launch { installed.onIdentityChanged() } }
    }

    /** The rate cache cutover's check: returns only after [start] completed, and throws [IllegalStateException] otherwise. */
    fun requireReady() {
        check(ready) { "Topic runtime and consumer are not ready" }
    }

    /** The process consumer, the same instance on every read; throws [IllegalStateException] before [start] completed. */
    val consumer: PremiumTopicConsumer get() {
        requireReady()
        return checkNotNull(processConsumer)
    }
}

/**
 * Registered by start() on Main. Each registration keeps its last delivered value and skips a repeat: ON_START and ON_STOP
 * deliver true and false, and right after addObserver the current state is delivered too, so the current value arrives once
 * before observe() returns even when the lifecycle's own replay is held back by a re-entrant registration (S4 CUT-CC4b).
 */
internal class ProcessTopicForegroundStream(
    private val lifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }
) : TopicForegroundStream {
    override fun observe(onForeground: (Boolean) -> Unit) {
        val observed = lifecycle()
        var last: Boolean? = null
        val deliver = { value: Boolean ->
            if (last != value) {
                last = value
                onForeground(value)
            }
        }
        observed.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> deliver(true)
                Lifecycle.Event.ON_STOP -> deliver(false)
                else -> Unit
            }
        })
        deliver(observed.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
}

/**
 * S4 CUT-CC4b: one owner start's graph, on Main (`cut_cc4b_agreed.r1.md` §2). [assemble] builds the parts and the starter
 * that owns the assembly's cleanup from then on, constructed right after the parts with nothing between them that can fail
 * (a builder that throws has cleaned up after itself). [bind], [onForeground] and [start]
 * forward to the starter. A recoverable failure (an Exception other than a cancellation) at any of them gives up the graph:
 * [abandon] marks it given up at once, so no later boundary reaches the starter, and secures one ATOMIC cleanup job on [main]
 * that, non-cancellably, first waits for the runtime's graph detachment whether or not the starter was bound, then abandons
 * the starter, which closes the assembly. A detach failure is kept suppressed on the cause, which is reported once when it
 * is a graph failure — an Exception other than a cancellation at a graph boundary; the starter reports
 * what it cleans up. A cancellation, or an Error, at a boundary secures the same cleanup and is rethrown. A topic failure the
 * owner rethrows secures the same cleanup and is not reported; with no graph built, it does nothing at all.
 */
private class ProcessGraphStartup(
    private val builder: ProcessGraphBuilder?,
    private val main: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    private val permit = LateBound<() -> TopicGraphRecoveryPermit?>("graph permit")
    private val seed = LateBound<String>("install seed")
    private var assembly: GraphRuntimeAssembly? = null
    private var starter: ProcessGraphStarter? = null
    private var runtime: TopicRuntime? = null
    private var abandoned = false

    val sink: TopicGraphSink get() = assembly?.sink ?: DormantTopicGraphSink
    val grants: TopicGrantSink? get() = assembly?.fences

    fun assemble() = guard {
        val built = builder?.build(permit, seed, { starter?.timeEventTarget?.invoke() }, checkNotNull(main.coroutineContext[Job]))
            ?: return@guard
        assembly = built.assembly
        starter = ProcessGraphStarter(built.assembly, built.seeds, seed, permit, main, report = report)
    }

    fun bind(runtime: TopicRuntime) {
        this.runtime = runtime
        guard {
            starter?.bindRuntime(runtime.graphRecoveryPermit, runtime.graphRecoveryPermitRevisions, runtime::detachGraph)
        }
    }

    /** After the graph is given up the starter, finished, forwards nothing. */
    fun onForeground(foreground: Boolean) {
        starter?.onForeground(foreground)
    }

    fun start() = guard { starter?.start() }

    /**
     * Gives up the graph; idempotent. The cleanup runs on [main] even if the scope is cancelled before it dispatches. Only a
     * [graphFailure] that is an Exception other than a cancellation is reported.
     */
    fun abandon(cause: Throwable, graphFailure: Boolean = true) {
        if (abandoned) return
        abandoned = true
        val runtime = runtime
        // The starter is constructed right after the parts with nothing in between that can fail, so an assembly always has one.
        val starter = starter ?: run {
            // Nothing of the graph was built: nothing to detach (no graph connection was installed) or close.
            if (graphFailure && cause is Exception && cause !is CancellationException) runCatching { report(cause) }
            return
        }
        main.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                runtime?.let { r -> runCatching { r.detachGraph() }.exceptionOrNull()?.let { cause.addSuppressed(it) } }
                starter.abandon(cause, report = graphFailure)
            }
        }
    }

    private inline fun guard(block: () -> Unit) {
        if (abandoned) return
        try {
            block()
        } catch (failure: Throwable) {
            abandon(failure)
            if (failure is CancellationException || failure !is Exception) throw failure
        }
    }
}
