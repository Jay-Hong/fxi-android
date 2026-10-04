package com.jay.fxi.data.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.network.NetworkMonitor
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The process's foreground transitions: true when it comes to the foreground, false when it leaves. Main thread. */
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
    private val main: CoroutineScope
) {
    @Inject
    constructor(
        factory: TopicRuntimeFactory,
        networkMonitor: NetworkMonitor,
        authTokenProvider: AuthTokenProvider,
        fences: AuthFenceStream,
        rowPreferenceStore: RateRowPreferenceStore
    ) : this(
        factory,
        networkMonitor.isConnected,
        ProcessTopicForegroundStream(),
        fences,
        authTokenProvider::currentIdentityFence,
        rowPreferenceStore,
        CoroutineScope(Dispatchers.Main + SupervisorJob())
    )

    private var attempted = false
    private var processConsumer: PremiumTopicConsumer? = null
    // Written last on Main, read by the cache cutover on IO; also publishes the consumer reference.
    @Volatile private var ready = false

    /**
     * On the Main thread, once per process: creates and starts the runtime, creates and starts the consumer on [main],
     * installs network, foreground and identity forwarding, then records readiness. Online input stays false until the first
     * foreground, whose callback passes the current [online] value directly. Later calls do nothing. A throw leaves the owner
     * not ready.
     */
    fun start() {
        if (attempted) return
        attempted = true
        val runtime = factory.create()
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
        }
        // AuthFenceStream can deliver while a source/coordinator lock is held. Enqueue only;
        // the non-immediate Main dispatcher reads live identity after the callback has returned.
        fences.observe { main.launch { installed.onIdentityChanged() } }
        ready = true
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

/** Registered by start() on Main; the process lifecycle replays its current foreground state. */
private class ProcessTopicForegroundStream : TopicForegroundStream {
    override fun observe(onForeground: (Boolean) -> Unit) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onForeground(true)
                Lifecycle.Event.ON_STOP -> onForeground(false)
                else -> Unit
            }
        })
    }
}
