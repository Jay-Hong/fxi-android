package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.domain.model.GraphSelectionChange
import com.jay.fxi.domain.model.GraphSeriesSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@ConsistentCopyVisibility
data class GraphSelectionBinding internal constructor(
    val identity: AuthIdentityFence,
    val key: GraphSelectionKey,
    val bindingGeneration: Long
)

sealed interface GraphSelectionPublication {
    data object Unbound : GraphSelectionPublication
    data class AwaitingRestore(val binding: GraphSelectionBinding) : GraphSelectionPublication
    data class Restored(
        val binding: GraphSelectionBinding,
        val read: GraphSelectionReadResult
    ) : GraphSelectionPublication
    data class WriteUncertain(
        val binding: GraphSelectionBinding,
        val cause: Throwable
    ) : GraphSelectionPublication
}

sealed interface GraphSelectionRestoreResult {
    data class Applied(val read: GraphSelectionReadResult) : GraphSelectionRestoreResult
    data object StaleBinding : GraphSelectionRestoreResult
}

sealed interface GraphSelectionApplyResult {
    data object Committed : GraphSelectionApplyResult
    data object Unchanged : GraphSelectionApplyResult
    data class Rejected(val reason: String) : GraphSelectionApplyResult
    data object StaleBinding : GraphSelectionApplyResult
    data object NotReady : GraphSelectionApplyResult
    data class NotCommitted(val cause: Throwable) : GraphSelectionApplyResult
    data class Uncertain(val cause: Throwable) : GraphSelectionApplyResult
}

/** One UID-bound selection session for an audience/tab, shared by its inline and fullscreen views. */
class GraphSeriesSelectionSession(
    private val store: GraphSelectionStore,
    private val audience: GraphSelectionAudience,
    private val tab: String,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher
) {
    init {
        require(tab.isNotBlank()) { "tab must not be blank" }
    }

    // bind is synchronous. This lock orders acceptance, binding changes and publication without holding it across I/O.
    private val lock = Any()
    private var generation = 0L
    private var binding: GraphSelectionBinding? = null
    private var closed = false
    private val mutablePublication = MutableStateFlow<GraphSelectionPublication>(GraphSelectionPublication.Unbound)
    val publication: StateFlow<GraphSelectionPublication> = mutablePublication.asStateFlow()
    private val queue = Channel<Work<*>>(Channel.UNLIMITED)

    private class Work<T>(val action: suspend () -> T) {
        // No caller Job is attached: cancelling await does not cancel an accepted command.
        val result = CompletableDeferred<T>()

        suspend fun run() {
            try {
                result.complete(action())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
                // A command failure belongs to its waiter; cancellation of the owner still stops the worker.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private val worker = scope.launch(dispatcher) {
        for (work in queue) work.run()
    }.also { job ->
        job.invokeOnCompletion { cause ->
            synchronized(lock) {
                closed = true
                binding = null
                mutablePublication.value = GraphSelectionPublication.Unbound
                queue.close()
                // Also release waiters if the owner cancels before the worker ever starts.
                val failure = cause ?: CancellationException("Graph selection session closed")
                while (true) {
                    val pending = queue.tryReceive().getOrNull() ?: break
                    pending.result.completeExceptionally(failure)
                }
            }
        }
    }

    fun bind(identity: AuthIdentityFence?): GraphSelectionBinding? = synchronized(lock) {
        generation = Math.incrementExact(generation)
        if (closed) return@synchronized null
        binding = identity?.let {
            GraphSelectionBinding(it, GraphSelectionKey(it.uid, audience, tab), generation)
        }
        mutablePublication.value = binding?.let { GraphSelectionPublication.AwaitingRestore(it) }
            ?: GraphSelectionPublication.Unbound
        binding
    }

    suspend fun restore(binding: GraphSelectionBinding): GraphSelectionRestoreResult = submit(
        binding, GraphSelectionRestoreResult.StaleBinding
    ) {
        synchronized(lock) {
            if (!isCurrent(binding)) return@submit GraphSelectionRestoreResult.StaleBinding
        }
        val read = store.confirmGraphSelection(binding.key)
        synchronized(lock) {
            if (!isCurrent(binding)) return@synchronized GraphSelectionRestoreResult.StaleBinding
            mutablePublication.value = GraphSelectionPublication.Restored(binding, read)
            GraphSelectionRestoreResult.Applied(read)
        }
    }

    suspend fun apply(
        binding: GraphSelectionBinding,
        change: (GraphSeriesSelection?) -> GraphSelectionChange
    ): GraphSelectionApplyResult = submit(binding, GraphSelectionApplyResult.StaleBinding) {
        applyCurrent(binding, change)
    }

    private suspend fun applyCurrent(
        binding: GraphSelectionBinding,
        change: (GraphSeriesSelection?) -> GraphSelectionChange
    ): GraphSelectionApplyResult {
        val replacement = synchronized(lock) {
            if (!isCurrent(binding)) return GraphSelectionApplyResult.StaleBinding
            val restored = mutablePublication.value as? GraphSelectionPublication.Restored
                ?: return GraphSelectionApplyResult.NotReady
            val current = when (val read = restored.read) {
                GraphSelectionReadResult.Absent -> null
                is GraphSelectionReadResult.Present -> read.record.selection
                is GraphSelectionReadResult.Unreadable -> return GraphSelectionApplyResult.NotReady
            }
            // Compute from the last completed command, never from the caller's enqueue-time state.
            when (val next = change(current)) {
                GraphSelectionChange.Unchanged -> return GraphSelectionApplyResult.Unchanged
                is GraphSelectionChange.Rejected -> return GraphSelectionApplyResult.Rejected(next.reason)
                is GraphSelectionChange.Replace -> {
                    if (next.selection == current) return GraphSelectionApplyResult.Unchanged
                    GraphSeriesSelection(
                        next.selection.visibleSeriesIds.toSet(),
                        next.selection.initializedSeries.toSet()
                    )
                }
            }
        }
        val written = store.writeGraphSelection(binding.key, replacement)
        return synchronized(lock) {
            if (!isCurrent(binding)) return@synchronized GraphSelectionApplyResult.StaleBinding
            when (written) {
                GraphSelectionWriteResult.Committed -> {
                    val key = binding.key
                    mutablePublication.value = GraphSelectionPublication.Restored(
                        binding,
                        GraphSelectionReadResult.Present(
                            GraphSelectionRecord(
                                1, key.uid, key.audience, key.tab,
                                replacement.visibleSeriesIds, replacement.initializedSeries
                            )
                        )
                    )
                    GraphSelectionApplyResult.Committed
                }
                is GraphSelectionWriteResult.NotCommitted -> GraphSelectionApplyResult.NotCommitted(written.cause)
                is GraphSelectionWriteResult.Uncertain -> {
                    mutablePublication.value = GraphSelectionPublication.WriteUncertain(binding, written.cause)
                    GraphSelectionApplyResult.Uncertain(written.cause)
                }
            }
        }
    }

    /** Wait for commands accepted before this barrier; it does not assert that their writes committed. */
    suspend fun drain() {
        val barrier = synchronized(lock) {
            if (closed) null else Work { Unit }.also { check(queue.trySend(it).isSuccess) }
        }
        if (barrier == null) worker.join() else barrier.result.await()
    }

    /** Retire bindings and acceptance first; the worker finishes started I/O and drops retired queued commands. */
    suspend fun close() {
        synchronized(lock) {
            closed = true
            binding = null
            mutablePublication.value = GraphSelectionPublication.Unbound
            queue.close()
        }
        worker.join()
    }

    // Called only under lock. Equality includes the identity fence, captured key and binding generation. close() and the
    // worker's completion clear [binding], so a closed session has no current binding.
    private fun isCurrent(candidate: GraphSelectionBinding): Boolean = binding == candidate

    private suspend fun <T> submit(
        binding: GraphSelectionBinding,
        stale: T,
        action: suspend () -> T
    ): T {
        val work = synchronized(lock) {
            if (!isCurrent(binding)) return stale
            Work(action).also { check(queue.trySend(it).isSuccess) }
        }
        return work.result.await()
    }
}
