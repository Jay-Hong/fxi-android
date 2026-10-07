package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphOffer
import com.jay.fxi.data.remote.TopicGraphSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Synchronous, non-suspending calls made on the sink's serial executor, including [close]. */
internal interface GraphTopicInputConsumer {
    fun observe(input: TopicGraphInput.Observations)
    fun observe(input: TopicGraphInput.Continuity)
    fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>)
    fun close()
}

/**
 * Hands whole inputs to [consumer] through a FIFO queue with a budget of 512 units. An observation
 * costs its candidate count; a continuity fact costs one. A batch that does not fit returns FULL
 * without evicting anything. [tryOffer] reads no suppliers and calls no consumer: it returns promptly
 * with ENQUEUED, FULL or FAILED and wakes the worker for all three. Its own Exceptions become FAILED;
 * Throwables outside Exception are not caught. Queue acceptance authorizes no deferred adoption.
 *
 * The supplied [scope] must use a serial executor that always dispatches the worker; Unconfined and
 * immediate execution are forbidden. The caller must run [close] and all other consumer use on that
 * same executor. Consumer methods are synchronous and non-suspending. One worker delivers the same
 * input objects in order, one at a time, releasing each input's units before calling the consumer.
 * After each input, and even with an empty queue, it swaps the loss ledger and calls [consumer]'s
 * loseTopics once per original scope. Losses recorded during those calls wait for the next turn.
 *
 * FULL and FAILED inputs record each topic's maximum original lifetime invalidation count under
 * the original (uid, epoch). Missing owner, epoch or lifetime means no ledger entry. The sink does
 * not filter by the recorder's current scope or invalidation floor. Ledger map and set operations
 * are assumed not to throw, including when recording an offer failure.
 *
 * [close] stops acceptance, drops the queue and ledger, cancels only this worker, and closes the
 * consumer once without cancelling [scope]. A consumer Exception ends the pipeline the same way,
 * with no retry or further delivery; CancellationException is rethrown. Worker completion also
 * stops acceptance and discards pending work, including cancellation before its body ever runs.
 * CLOSED is returned before reading an input. A conflated wake send needs no failure handling:
 * it succeeds while open, and a closed channel belongs to an already ended pipeline.
 */
internal class GraphRecorderTopicSink(
    scope: CoroutineScope,
    private val consumer: GraphTopicInputConsumer
) : TopicGraphSink {
    private class QueuedInput(val input: TopicGraphInput, val units: Int)

    private val lock = Any()
    private val queue = ArrayDeque<QueuedInput>()
    private var usedUnits = 0
    private var ledger = mutableMapOf<GraphDataScope, MutableMap<String, Long>>()
    private var closed = false
    private var consumerClosed = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job = scope.launch { drain() }.also { job ->
        // A finally in the worker body cannot cover cancellation before the first dispatch.
        job.invokeOnCompletion { synchronized(lock) { dropLocked() } }
    }

    override fun tryOffer(input: TopicGraphInput): TopicGraphOffer {
        return try {
            val result = synchronized(lock) {
                if (closed) return TopicGraphOffer.CLOSED
                val units = input.pendingUnits()
                if (units > MAX_UNITS - usedUnits) {
                    recordLocked(input)
                    TopicGraphOffer.FULL
                } else {
                    // Cache the accepted cost so dequeue never reads a batch's size again.
                    queue.addLast(QueuedInput(input, units))
                    usedUnits += units
                    TopicGraphOffer.ENQUEUED
                }
            }
            wake.trySend(Unit)
            result
        } catch (_: Exception) {
            synchronized(lock) { if (!closed) recordLocked(input) }
            wake.trySend(Unit)
            TopicGraphOffer.FAILED
        }
    }

    fun close() {
        val closeConsumer = synchronized(lock) {
            dropLocked()
            if (consumerClosed) false else true.also { consumerClosed = true }
        }
        worker.cancel()
        if (closeConsumer) {
            try {
                consumer.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The pipeline is already closed; a failed close is never retried.
            }
        }
    }

    private suspend fun drain() {
        for (signal in wake) {
            while (true) {
                val next = synchronized(lock) {
                    if (closed) return
                    queue.removeFirstOrNull()?.also { usedUnits -= it.units }
                }
                if (next != null && !deliver {
                        when (val input = next.input) {
                            is TopicGraphInput.Observations -> consumer.observe(input)
                            is TopicGraphInput.Continuity -> consumer.observe(input)
                        }
                    }
                ) return
                val losses = synchronized(lock) {
                    if (closed) return
                    ledger.also { ledger = mutableMapOf() }
                }
                for ((owner, topics) in losses) {
                    if (synchronized(lock) { closed }) return
                    if (!deliver { consumer.loseTopics(owner, topics) }) return
                }
                if (next == null && losses.isEmpty()) break
                yield()
            }
        }
    }

    private inline fun deliver(call: () -> Unit): Boolean = try {
        call()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        close()
        false
    }

    private fun dropLocked() {
        closed = true
        queue.clear()
        ledger = mutableMapOf()
        usedUnits = 0
        wake.close()
    }

    private fun recordLocked(input: TopicGraphInput) {
        val owner = when (input) {
            is TopicGraphInput.Observations -> input.attribution.owner
            is TopicGraphInput.Continuity -> input.authority.owner
        } ?: return
        val epoch = owner.userAccessEpoch ?: return
        val invalidations = when (input) {
            is TopicGraphInput.Observations -> input.attribution.lifetime.invalidations
            is TopicGraphInput.Continuity -> input.authority.lifetime?.invalidations
        } ?: return
        val topics = when (input) {
            is TopicGraphInput.Observations -> setOf(input.topic)
            is TopicGraphInput.Continuity -> input.topics
        }
        val byTopic = ledger.getOrPut(GraphDataScope(owner.identity.uid, epoch)) { mutableMapOf() }
        for (topic in topics) {
            byTopic[topic] = maxOf(byTopic[topic] ?: invalidations, invalidations)
        }
    }

    private companion object {
        const val MAX_UNITS = 512
    }
}
