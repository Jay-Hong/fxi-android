package com.jay.fxi.data.graph

import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S4 CUT-CC4a: a value an early-built graph runtime reads only once it has been set — the install seed for the rate-limit
 * jitter, the session's permit supplier. Set once; [get] is null before, [require] throws. Thread-safe reads.
 */
internal class LateBound<T : Any>(private val name: String) {
    @Volatile private var value: T? = null

    @Synchronized
    fun set(bound: T) {
        check(value == null) { "$name is already bound" }
        value = bound
    }

    fun get(): T? = value

    fun require(): T = checkNotNull(value) { "$name is not bound" }
}

/**
 * S4 CUT-CC4a: starts the process graph runtime that the topic owner assembled early, once the install seed is in memory,
 * without blocking Main (`cut_cc4_agreed.r1.md`). Main only.
 *
 * It owns [assembly]'s explicit cleanup from construction. [bindRuntime] binds the runtime's permit supplier into [permit] —
 * the slot the assembly's coordinator and events read — and hands over its permit revisions and graph detachment; it must
 * precede [start]. [timeEventTarget] receives the recovery events' time events, nothing until set. [start] makes one attempt:
 * seed, a cancellation check, the assembly's start, then on Main without a
 * suspension the initial hand-over — the last process foreground to the recovery events, the permit to both consumers, a
 * collector notifying both on every observed publication — and only then publishes [runtimeReady]. Any failure from the
 * seed to that publication, or an [abandon] before it, detaches the graph from the runtime, ends the collector and waits for
 * it, closes the assembly and waits for it, all non-cancellably on Main, then reports an Exception once; [runtimeReady] stays
 * null for good. A cancellation runs the same cleanup, is not reported and is rethrown, even one before the attempt's first
 * dispatch with the seed already in memory; an Error is cleaned up after, not reported and rethrown (S4 CUT-CC4b). A failed
 * report does not escape. An [abandon] while that cleanup runs returns only once it is done, even when its caller is
 * cancelled. Migration readiness is not marked here (CC5).
 */
internal class ProcessGraphStarter(
    private val assembly: GraphRuntimeAssembly,
    private val seeds: InstallSeedSource,
    private val seed: LateBound<String>,
    private val permit: LateBound<() -> TopicGraphRecoveryPermit?>,
    private val scope: CoroutineScope,
    /** How the assembly is closed in the cleanup; its close() unless a test injects a failure (S4 CUT-CC4b). */
    private val closeAssembly: suspend (GraphRuntimeAssembly) -> Unit = { it.close() },
    private val report: (Throwable) -> Unit
) {
    private val ready = MutableStateFlow<GraphRuntimeAssembly?>(null)

    /** The started assembly once the initial hand-over is done; null before and, after a failure, for good. */
    val runtimeReady: StateFlow<GraphRuntimeAssembly?> = ready.asStateFlow()

    /** The recovery events' time events go here; a no-op until the screen host sets it. */
    @Volatile var timeEventTarget: () -> Unit = {}

    private val cleaned = CompletableDeferred<Unit>()
    private var revisions: StateFlow<Long>? = null
    private var detach: (suspend () -> Unit)? = null
    private var attempt: Job? = null
    private var collector: Job? = null
    private var handedOver = false
    private var finished = false
    private var foreground: Boolean? = null

    /** The runtime's permit supplier, its permit revisions and its graph detachment; once, before [start]. */
    fun bindRuntime(permit: () -> TopicGraphRecoveryPermit?, revisions: StateFlow<Long>, detach: suspend () -> Unit) {
        this.permit.set(permit) // Once: a second binding throws here, before anything else is bound.
        this.revisions = revisions
        this.detach = detach
    }

    /** The process foreground, synchronously from the owner's callback: kept until the hand-over, forwarded after it. */
    fun onForeground(foreground: Boolean) {
        this.foreground = foreground
        if (handedOver && !finished) assembly.events.onForeground(foreground)
    }

    /** One attempt per process, on [scope]; the attempt's job runs even if cancelled before its first dispatch. */
    fun start() {
        check(attempt == null) { "The graph start was already attempted" }
        val bound = checkNotNull(revisions) { "The runtime is not bound" }
        attempt = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                seed.set(seeds.current ?: seeds.get())
                // A seed already in memory suspends nothing: the one check that a cancellation before the first dispatch meets.
                currentCoroutineContext().ensureActive()
                check(assembly.start()) { "The graph assembly did not start" }
                foreground?.let { assembly.events.onForeground(it) }
                handedOver = true
                notifyPermit()
                collector = scope.launch { bound.collect { notifyPermit() } }
                ready.value = assembly
            } catch (failure: Throwable) {
                cleanUp(failure)
                // Cleaned up either way; only an Exception other than a cancellation is a recoverable graph failure.
                if (failure is CancellationException || failure !is Exception) throw failure
            }
        }
    }

    /**
     * A failure on the owner's side before [start] ran its course: the same cleanup, and the report once unless [report] is
     * false — a topic failure the owner rethrows is not a graph failure (S4 CUT-CC4b).
     */
    suspend fun abandon(cause: Throwable, report: Boolean = true) {
        if (ready.value != null) return
        if (finished) {
            // Not even the caller's cancellation lets it return before the cleanup under way has closed and reported.
            withContext(NonCancellable) { cleaned.await() }
            return
        }
        attempt?.cancel()
        cleanUp(cause, report)
    }

    private fun notifyPermit() {
        assembly.events.onPermitChanged()
        assembly.coordinator.onRecoveryPermitChanged()
    }

    private suspend fun cleanUp(failure: Throwable, reportFailure: Boolean = true) {
        if (finished) return
        finished = true
        try {
            withContext(NonCancellable) {
                detach?.let { cut -> runCatching { cut() }.exceptionOrNull()?.let { failure.addSuppressed(it) } }
                collector?.cancelAndJoin()
                runCatching { closeAssembly(assembly) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            }
            if (reportFailure && failure is Exception && failure !is CancellationException) runCatching { report(failure) }
        } finally {
            cleaned.complete(Unit)
        }
    }
}
