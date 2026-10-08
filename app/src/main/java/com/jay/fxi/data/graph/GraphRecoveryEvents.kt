package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.time.AppClock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * S4 RT05: the sole recovery-trigger producer for the entire lifetime of one coordinator, and the producer of the time events
 * that move retention and line use. Closing it does not allow a replacement producer to restart sequence at 1 on that
 * coordinator. It creates no observation, sends nothing and owns no budget; the coordinator owns every send (RT03b).
 * Every method and port runs on the coordinator's serial executor, and no port or supplier may call back into this producer.
 * Dormant: no production code constructs it yet.
 *
 * RT05a, the time path: while the process is in the foreground a rollover tick every 10 s finds a change of the 600 s
 * bucket - forward or back, however many buckets apart - and then retains, issues BOUNDARY_600S for the coordinator's
 * published context and emits a time event; a freshness tick every 30 s emits a time event only. The two ticks are
 * independent, so both may emit at the same instant. A return from the background retains once, issues BOUNDARY_600S once if
 * the bucket changed and emits one time event before the ticks start again; integration must complete it before a holder's
 * re-show flush. Without a published context no BOUNDARY_600S is issued and no sequence is spent.
 *
 * RT05b, the resync path: a return after at least 60 s in the background issues FOREGROUND_RETURN after any BOUNDARY_600S
 * and before the time event. A new live Connection in the session's permit - the first one this producer sees is only the
 * baseline - issues RECONNECT for the permit's fence and the Connection's lifetime, after a context change delivered first
 * and whether or not the coordinator has published a context; one seen in the background or before the first foreground
 * state moves the baseline only and is not replayed on the return. The two share a 30 s cooldown that only an issued
 * trigger spends: a short background, a return without a context, a failed cooldown and BOUNDARY_600S spend nothing. A
 * negative wall-time difference meets neither the 60 s nor the cooldown. A missed resync leaves the demand to the next
 * valid trigger.
 *
 * @param scope the coordinator's serial executor (in the assembly, its always-dispatching Main); the ticks run there.
 * @param clock the same AppClock as the coordinator, the recorder and the holders.
 * @param context the coordinator's published request context, `coordinator.state.value.source`.
 * @param trigger `coordinator::onRecoveryTrigger`.
 * @param retain `recorder::retain`.
 * @param timeEvent the holders' `onTimeEvent`, fanned out at integration.
 * @param permit the session's permit publication, the same supplier the coordinator reads.
 * @param contextChanged `coordinator::onContextChanged`.
 */
internal class GraphRecoveryEvents(
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val context: () -> GraphRequestSource?,
    private val trigger: (GraphRecoveryTrigger) -> Unit,
    private val retain: () -> Unit,
    private val timeEvent: () -> Unit,
    private val permit: () -> TopicGraphRecoveryPermit?,
    private val contextChanged: () -> Unit
) {
    private data class Connection(val sessionKey: Any, val generation: Long)

    private var closed = false
    private var foreground: Boolean? = null
    private var bucket: Instant? = null
    private var backgroundAt: Instant? = null
    private var lastResync: Instant? = null
    private var connection: Connection? = null
    private var sequence = 0L
    private val ticks = mutableListOf<Job>()

    /**
     * The process foreground state; only a change acts. A first true starts the ticks and a first false only sets the bucket
     * baseline and the background start; any later true is a return.
     */
    fun onForeground(foreground: Boolean) {
        if (closed || this.foreground == foreground) return
        val first = this.foreground == null
        this.foreground = foreground
        val now = clock.now()
        if (!foreground) {
            if (first) bucket = graphObservationBucketStart(now)
            backgroundAt = now
            stopTicks()
            return
        }
        if (first) {
            bucket = graphObservationBucketStart(now)
            startTicks()
            return
        }
        val crossed = moveBucket(now)
        retain()
        if (crossed) boundary()
        if (now - checkNotNull(backgroundAt) >= RETURN_MIN && cooled(now)) {
            context()?.let { source ->
                lastResync = now
                trigger(
                    GraphRecoveryTrigger(GraphRecoveryTrigger.Kind.FOREGROUND_RETURN, ++sequence, source.fence, source.lifetime)
                )
            }
        }
        timeEvent()
        startTicks()
    }

    /**
     * The session's permit publication changed. Only a publication with both Connection fields names a Connection; a new
     * one after the baseline replaces it and, in the foreground once the cooldown has passed, issues RECONNECT.
     */
    fun onPermitChanged() {
        if (closed) return
        val published = permit() ?: return
        val generation = published.connectionGeneration ?: return
        val lifetime = published.connectionLifetime ?: return
        val seen = connection
        if (seen != null && seen.sessionKey == published.sessionKey && generation <= seen.generation) return
        connection = Connection(published.sessionKey, generation)
        if (seen == null || foreground != true) return
        val now = clock.now()
        if (!cooled(now)) return
        lastResync = now
        contextChanged()
        trigger(GraphRecoveryTrigger(GraphRecoveryTrigger.Kind.RECONNECT, ++sequence, published.fence, lifetime))
    }

    /** Ends the ticks; later calls do nothing. The coordinator and the recorder stay open. */
    fun close() {
        if (closed) return
        closed = true
        stopTicks()
    }

    private fun rollover() {
        if (!moveBucket(clock.now())) return
        retain()
        boundary()
        timeEvent()
    }

    private fun moveBucket(now: Instant): Boolean {
        val next = graphObservationBucketStart(now)
        if (next == bucket) return false
        bucket = next
        return true
    }

    private fun boundary() {
        val source = context() ?: return
        trigger(GraphRecoveryTrigger(GraphRecoveryTrigger.Kind.BOUNDARY_600S, ++sequence, source.fence, source.lifetime))
    }

    private fun cooled(now: Instant): Boolean {
        val last = lastResync ?: return true
        return now - last >= RESYNC_COOLDOWN
    }

    private fun startTicks() {
        ticks += scope.launch {
            while (true) {
                delay(ROLLOVER_TICK)
                rollover()
            }
        }
        ticks += scope.launch {
            while (true) {
                delay(FRESHNESS_TICK)
                timeEvent()
            }
        }
    }

    private fun stopTicks() {
        ticks.forEach { it.cancel() }
        ticks.clear()
    }

    private companion object {
        val ROLLOVER_TICK = 10.seconds
        val FRESHNESS_TICK = 30.seconds
        val RETURN_MIN = 60.seconds
        val RESYNC_COOLDOWN = 30.seconds
    }
}
