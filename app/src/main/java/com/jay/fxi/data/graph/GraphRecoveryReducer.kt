package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.domain.model.RateSanity
import kotlinx.datetime.Instant

private const val RECOVERY_BUCKET_SECONDS = 600L
private const val RECOVERY_DATA_SECONDS = 25 * 60 * 60L
private const val RECOVERY_DEMAND_SECONDS = 24 * 60 * 60L
private const val RECOVERY_SLOT_LIMIT = 152
private const val RECOVERY_IDENTITY_LIMIT = 4096

private fun Instant.offsetSeconds(seconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds + seconds)

/** Mutable scratch space for one pure transition; every demand it touches shares one new generation. */
private class RecoveryTransition(private val previous: GraphRecoverableState, now: Instant) {
    val current = graphObservationBucketStart(now)
    val lower = current.offsetSeconds(-RECOVERY_DATA_SECONDS)
    val demandLower = current.offsetSeconds(-RECOVERY_DEMAND_SECONDS)
    val upper = current.offsetSeconds(RECOVERY_BUCKET_SECONDS)
    var data = previous.data
    var version = previous.lastAppliedVersion
    val pending = previous.pending.toMutableMap()
    val history = previous.gapHistory.toMutableSet()
    private var demanded = false

    fun demand(start: Instant, reason: GraphRecoveryReason) {
        demanded = true
        pending[start] = GraphRecoveryDemand(previous.generation + 1, pending[start]?.reasons.orEmpty() + reason)
        if (reason == GraphRecoveryReason.RECEIVE_GAP || reason == GraphRecoveryReason.HANDOVER_LOSS) {
            history.add(start)
        }
    }

    fun demandSpan(first: Instant, last: Instant, reason: GraphRecoveryReason) {
        var start = maxOf(first, demandLower)
        val end = minOf(last, upper)
        while (start <= end) {
            demand(start, reason)
            if (start == end) break
            start = start.offsetSeconds(RECOVERY_BUCKET_SECONDS)
        }
    }

    /** Apply retention only after the event, including a response's normal releases. */
    fun finish(): GraphRecoverableState {
        previous.lastCutoff?.let { cutoff ->
            if (lower < cutoff) {
                demandSpan(lower, cutoff.offsetSeconds(-RECOVERY_BUCKET_SECONDS), GraphRecoveryReason.TIME_ANOMALY)
            }
        }

        val app = data.app
        val buckets = app.buckets.filterKeys { it >= lower }.toMutableMap()
        val server = data.serverBuckets.filterKeys { it >= lower }.toMutableMap()
        history.removeAll { it < lower }
        pending.keys.removeAll { it < demandLower }

        val slots = (buckets.keys + server.keys + pending.keys + history).toSortedSet()
        val evicted = mutableSetOf<Instant>()
        while (slots.size > RECOVERY_SLOT_LIMIT) {
            val start = slots.last()
            val lostEvidence = start in buckets || start in pending
            buckets.remove(start)
            server.remove(start)
            pending.remove(start)
            history.remove(start)
            evicted.add(start)
            slots.remove(start)
            if (lostEvidence) {
                demand(current, GraphRecoveryReason.TIME_ANOMALY)
                slots.add(current)
            }
        }

        val tip = app.tip?.let { old ->
            if (graphObservationBucketStart(old.observedAt) in buckets) old
            else buckets.values.maxByOrNull { it.close.observedAt }?.close
        }
        val identities = app.observations.filterTo(linkedSetOf()) {
            val start = graphObservationBucketStart(it.observedAt)
            start >= lower && start !in evicted
        }
        if (identities.size > RECOVERY_IDENTITY_LIMIT) {
            // Stable sorting preserves first insertion order for equal observation times.
            val oldest = identities.sortedBy { it.observedAt }.take(identities.size - RECOVERY_IDENTITY_LIMIT)
            identities.removeAll(oldest.toSet())
        }
        val retainedApp = GraphObservationState.updated(app, buckets, tip, identities)
        return GraphRecoverableState.updated(
            GraphSeededObservationState.updated(retainedApp, server), pending, history,
            previous.generation + if (demanded) 1 else 0, version, lower
        )
    }
}

internal fun requireGraphRecovery(
    state: GraphRecoverableState,
    from: Instant,
    to: Instant,
    reason: GraphRecoveryReason,
    now: Instant
): GraphRecoverableState {
    val transition = RecoveryTransition(state, now)
    transition.demandSpan(
        graphObservationBucketStart(minOf(from, to)), graphObservationBucketStart(maxOf(from, to)), reason
    )
    return transition.finish()
}

internal fun captureGraphRecoveryRequest(state: GraphRecoverableState, applicationVersion: Long): GraphRecoveryRequest =
    GraphRecoveryRequest(state.data.app.seriesKey, applicationVersion, state.generation)

internal fun applyGraphRecoveryResponse(
    state: GraphRecoverableState,
    scope: GraphDataScope,
    request: GraphRecoveryRequest,
    tab: GraphV2Tab,
    now: Instant
): GraphRecoverableState {
    val transition = RecoveryTransition(state, now)
    val closedStarts = graphServerClosedStarts(state.data, scope, tab)
    val lastVersion = state.lastAppliedVersion
    if (request.seriesKey == state.data.app.seriesKey && closedStarts != null &&
        (lastVersion == null || request.applicationVersion > lastVersion)
    ) {
        transition.data = applyGraphServerTab(state.data, scope, tab, now)
        for (start in closedStarts) {
            val demand = transition.pending[start]
            if (demand != null && demand.generation <= request.capturedGeneration) transition.pending.remove(start)
        }
        transition.version = request.applicationVersion
    }
    return transition.finish()
}

internal fun observeRecoverable(
    state: GraphRecoverableState,
    observations: List<GraphObservation>,
    now: Instant
): GraphRecoverableReduction {
    val transition = RecoveryTransition(state, now)
    val outcomes = MutableList(observations.size) { GraphObservationOutcome.REJECTED }
    val admitted = mutableListOf<GraphObservation>()
    val indices = mutableListOf<Int>()
    for ((index, observation) in observations.withIndex()) {
        if (observation.seriesKey != state.data.app.seriesKey || !RateSanity.isPlausible(observation.id.rate)) continue
        val start = graphObservationBucketStart(observation.id.observedAt)
        when {
            start < transition.lower -> Unit
            start > transition.upper -> transition.demand(transition.current, GraphRecoveryReason.TIME_ANOMALY)
            else -> {
                admitted.add(observation)
                indices.add(index)
            }
        }
    }
    val reduction = reduceSeededGraphObservations(state.data, admitted)
    transition.data = reduction.state
    for ((offset, index) in indices.withIndex()) outcomes[index] = reduction.outcomes[offset]
    return GraphRecoverableReduction(transition.finish(), outcomes)
}

internal fun retainGraphRecoverable(state: GraphRecoverableState, now: Instant): GraphRecoverableState =
    RecoveryTransition(state, now).finish()
