package com.jay.fxi.ui.premium.graph

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * S4 CUT-P3b: the process-owned host of the premium FX graph holders. Main-confined: every call, and every coroutine it
 * starts, runs on [scope]'s dispatcher, which must be a dispatching Main (never Main.immediate).
 * The scope is process-owned and supervised; mount closure never cancels it. Calls are non-reentrant.
 * [create] returns a fresh, unstarted, unclosed holder and a fresh PREMIUM selection session for the requested tab.
 * They share the runtime's Main dispatcher and dependencies required by GraphRuntimeRetirementPort.
 * If [create] throws before returning, it owns cleanup of resources it already allocated.
 * [onFailure] must return normally and must not reenter the host.
 * Scope cancellation is terminal for this host; open() rejects an inactive scope by returning an already-closed mount.
 * Its holder map stays empty and close() is a no-op; no holder creation is scheduled and cancellation is not reported to [onFailure].
 *
 * A mount holds one holder per FX tab (usd, jpy, eur), shared by its inline and fullscreen surfaces. A holder for a tab is
 * created and started only after the previous holder for that tab has finished closing, so two holders never hold the same
 * selection key at once. "Finished" requires normal return from both holder.close() and [closeHolder].
 * A failed, cancelled or unexecuted close never permits a new holder for that tab. At most one mount is open: opening one closes the open one first.
 * [holders] lists the open mount's started holders, never a closing one; it feeds the time-event fan-out and the retirement
 * port. The topic owner constructs the process's one host (S4 CUT-CC5-2).
 */
internal class GraphScreenHost(
    private val scope: CoroutineScope,
    private val create: (tab: String) -> GraphV2ScreenStateHolder,
    private val onFailure: (Throwable) -> Unit,
    /**
     * Test seam used by every host close path, including cleanup after start() fails.
     * Calls holder.close() exactly once in the calling coroutine and does not suppress its failure or cancellation.
     * Returns normally only after holder.close() returns normally; tests may throw afterwards.
     * Production keeps the default.
     */
    private val closeHolder: suspend (GraphV2ScreenStateHolder) -> Unit = { it.close() },
    /** Test seam: how [onTimeEvent] reaches one holder; calls holder.onTimeEvent() once. Production keeps the default. */
    private val forwardTimeEvent: (GraphV2ScreenStateHolder) -> Unit = { it.onTimeEvent() }
) {
    /** Per tab, the last holder's close: true only once [closeHolder] returned normally. No entry: nothing to wait for. */
    private val closes = HashMap<String, CompletableDeferred<Boolean>>()
    private val mounted = ArrayList<GraphV2ScreenStateHolder>()
    private var opened: GraphScreenMount? = null

    fun open(): GraphScreenMount {
        opened?.close()
        val mount = GraphScreenMount(this)
        if (!scope.isActive) {
            mount.close()
            return mount
        }
        opened = mount
        for (tab in TABS) {
            val previous = closes[tab]
            scope.launch { mountTab(mount, tab, previous) }
        }
        return mount
    }

    fun holders(): List<GraphV2ScreenStateHolder> = mounted.toList()

    fun onTimeEvent() {
        holders().forEach(forwardTimeEvent)
    }

    private suspend fun mountTab(mount: GraphScreenMount, tab: String, previous: CompletableDeferred<Boolean>?) {
        if (previous != null && !previous.await()) return
        if (mount.closed) return
        val holder = try {
            create(tab)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
            return
        }
        try {
            holder.start()
        } catch (failure: Throwable) {
            retire(tab, holder)
            if (failure is CancellationException || failure !is Exception) throw failure
            onFailure(failure)
            return
        }
        mounted += holder
        mount.register(tab, holder)
    }

    internal fun closed(mount: GraphScreenMount, holders: Map<String, GraphV2ScreenStateHolder>) {
        if (opened === mount) opened = null
        for ((tab, holder) in holders) {
            mounted.remove(holder)
            retire(tab, holder)
        }
    }

    private fun retire(tab: String, holder: GraphV2ScreenStateHolder) {
        val result = CompletableDeferred<Boolean>()
        closes[tab] = result
        scope.launch {
            try {
                closeHolder(holder)
                result.complete(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }.invokeOnCompletion { result.complete(false) }
    }

    private companion object {
        val TABS = listOf("usd", "jpy", "eur")
    }
}

/** One screen mount of [GraphScreenHost]: Main-confined like its host. */
internal class GraphScreenMount internal constructor(private val host: GraphScreenHost) {
    private val mutableHolders = MutableStateFlow<Map<String, GraphV2ScreenStateHolder>>(emptyMap())

    /** Tab → started holder, filled per tab once that tab's previous holder has closed; empty once closed. */
    val holders: StateFlow<Map<String, GraphV2ScreenStateHolder>> = mutableHolders.asStateFlow()

    internal var closed = false
        private set

    internal fun register(tab: String, holder: GraphV2ScreenStateHolder) {
        mutableHolders.value = mutableHolders.value + (tab to holder)
    }

    /** Idempotent. Takes this mount's holders out of the host's list at once, then closes each on the host scope. */
    fun close() {
        if (closed) return
        closed = true
        val holders = mutableHolders.value
        mutableHolders.value = emptyMap()
        host.closed(this, holders)
    }
}
