package com.jay.fxi.data.graph

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
 * Every method and port runs on the coordinator's serial executor. Dormant: no production code constructs it yet.
 *
 * RT05a, the time path: while the process is in the foreground a rollover tick every 10 s finds a change of the 600 s
 * bucket - forward or back, however many buckets apart - and then retains, issues BOUNDARY_600S for the coordinator's
 * published context and emits a time event; a freshness tick every 30 s emits a time event only. The two ticks are
 * independent, so both may emit at the same instant. A return from the background retains once, issues BOUNDARY_600S once if
 * the bucket changed and emits one time event before the ticks start again; integration must complete it before a holder's
 * re-show flush. No context, no trigger and no sequence spent.
 *
 * @param scope the coordinator's serial executor (in the assembly, its always-dispatching Main); the ticks run there.
 * @param clock the same AppClock as the coordinator, the recorder and the holders.
 * @param context the coordinator's published request context, `coordinator.state.value.source`.
 * @param trigger `coordinator::onRecoveryTrigger`.
 * @param retain `recorder::retain`.
 * @param timeEvent the holders' `onTimeEvent`, fanned out at integration.
 */
internal class GraphRecoveryEvents(
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val context: () -> GraphRequestSource?,
    private val trigger: (GraphRecoveryTrigger) -> Unit,
    private val retain: () -> Unit,
    private val timeEvent: () -> Unit
) {
    private var closed = false
    private var foreground: Boolean? = null
    private var bucket: Instant? = null
    private var sequence = 0L
    private val ticks = mutableListOf<Job>()

    /**
     * The process foreground state; only a change acts. A first true starts the ticks and a first false only sets the
     * baseline; any later true is a return.
     */
    fun onForeground(foreground: Boolean) {
        if (closed || this.foreground == foreground) return
        val first = this.foreground == null
        this.foreground = foreground
        val now = clock.now()
        if (!foreground) {
            if (first) bucket = graphObservationBucketStart(now)
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
        timeEvent()
        startTicks()
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
    }
}
