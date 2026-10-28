package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.admitsKrxUse
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import java.util.Collections
import kotlinx.datetime.Instant

/** Immutable recorder data; the reducer alone constructs its private implementation. */
internal sealed class GraphRecorderState {
    abstract val scope: GraphDataScope?
    abstract val series: Map<GraphObservationSeriesKey, GraphRecoverableState>
    abstract val pending: GraphPendingInputs
    abstract val untransferredSeries: Set<String>
    abstract val krxSeriesOwners: Map<String, KrxGraphStamp>
    abstract val krxUntransferred: Map<String, Set<KrxGraphStamp>>
    abstract val seenUserEnd: Long
    abstract val seenRevision: Long
    abstract val nextVersion: Long
    abstract val versionFloor: Long
}

/** Access transitions and D3 delegation; GENERAL admission and original KRX cutoff admission come from the owner. */
internal object GraphRecorderReducer {
    private val fxOrder = GraphObservationOrder(emptyList())

    private class State(
        override val scope: GraphDataScope?,
        series: Map<GraphObservationSeriesKey, GraphRecoverableState>,
        override val pending: GraphPendingInputs,
        untransferredSeries: Set<String>,
        override val seenUserEnd: Long,
        override val seenRevision: Long,
        override val nextVersion: Long,
        override val versionFloor: Long,
        krxSeriesOwners: Map<String, KrxGraphStamp> = emptyMap(),
        krxUntransferred: Map<String, Set<KrxGraphStamp>> = emptyMap()
    ) : GraphRecorderState() {
        override val krxSeriesOwners: Map<String, KrxGraphStamp> = Collections.unmodifiableMap(krxSeriesOwners.toMap())
        override val krxUntransferred: Map<String, Set<KrxGraphStamp>> = copyKrxGraphStamps(krxUntransferred)
        override val series: Map<GraphObservationSeriesKey, GraphRecoverableState> =
            Collections.unmodifiableMap(series.toMap())
        override val untransferredSeries: Set<String> = Collections.unmodifiableSet(untransferredSeries.toSet())
    }

    fun empty(): GraphRecorderState = State(null, emptyMap(), GraphPendingInputs.EMPTY, emptySet(), 0L, 0L, 0L, 0L)

    private fun updated(
        state: GraphRecorderState,
        scope: GraphDataScope? = state.scope,
        series: Map<GraphObservationSeriesKey, GraphRecoverableState> = state.series,
        pending: GraphPendingInputs = state.pending,
        untransferredSeries: Set<String> = state.untransferredSeries,
        seenUserEnd: Long = state.seenUserEnd,
        seenRevision: Long = state.seenRevision,
        nextVersion: Long = state.nextVersion,
        versionFloor: Long = state.versionFloor,
        krxSeriesOwners: Map<String, KrxGraphStamp> = state.krxSeriesOwners,
        krxUntransferred: Map<String, Set<KrxGraphStamp>> = state.krxUntransferred
    ): GraphRecorderState = State(
        scope, series, pending, untransferredSeries, seenUserEnd, seenRevision, nextVersion, versionFloor,
        krxSeriesOwners, krxUntransferred
    )

    private fun scopeOf(fence: TopicSessionFence?): GraphDataScope? = fence?.let {
        it.userAccessEpoch?.let { epoch -> GraphDataScope(it.identity.uid, epoch) }
    }

    /**
     * Clears the selected held scope without synchronizing access. The seen values and nextVersion stay; versionFloor becomes
     * nextVersion, as on a discard.
     */
    fun purge(state: GraphRecorderState, selects: (GraphDataScope) -> Boolean): GraphRecorderState {
        val held = state.scope ?: return state
        if (!selects(held)) return state
        return updated(
            state,
            scope = null,
            series = emptyMap(),
            pending = GraphPendingInputs.EMPTY,
            untransferredSeries = emptySet(),
            krxSeriesOwners = emptyMap(),
            krxUntransferred = emptyMap(),
            versionFloor = state.nextVersion
        )
    }

    /** S4 RT05a: retention at [now] for every series, one common time; every other field is kept. */
    fun retain(state: GraphRecorderState, now: Instant): GraphRecorderState =
        updated(state, series = state.series.mapValues { (_, series) -> retainGraphRecoverable(series, now) })

    fun syncAccess(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?
    ): GraphRecorderState {
        if (snapshot.revision < state.seenRevision) return state
        val currentScope = scopeOf(fence)
        val userEnd = snapshot.lastUserEnd?.sequence ?: 0L
        val discard = userEnd > state.seenUserEnd || (currentScope != null && currentScope != state.scope)
        return updated(
            state,
            scope = currentScope ?: state.scope,
            series = if (discard) emptyMap() else state.series,
            pending = if (discard) GraphPendingInputs.EMPTY else state.pending,
            untransferredSeries = if (discard) emptySet() else state.untransferredSeries,
            krxSeriesOwners = if (discard) emptyMap() else state.krxSeriesOwners,
            krxUntransferred = if (discard) emptyMap() else state.krxUntransferred,
            seenUserEnd = maxOf(state.seenUserEnd, userEnd),
            seenRevision = snapshot.revision,
            versionFloor = if (discard) state.nextVersion else state.versionFloor
        )
    }

    private fun scopeContinues(state: GraphRecorderState, fence: TopicSessionFence?): Boolean =
        state.scope != null && scopeOf(fence) == state.scope

    private fun usable(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean
    ): Boolean = snapshot.revision >= state.seenRevision &&
        (snapshot.lastUserEnd?.sequence ?: 0L) == state.seenUserEnd &&
        scopeContinues(state, fence) && snapshot.facts.userAllowed && admission

    private fun seriesIds(topics: Set<String>, catalog: GraphCatalog): Set<String> = topics.flatMapTo(linkedSetOf()) {
        fxGraphSeriesIds(it, catalog) + dxyGraphSeriesIds(it, catalog) + tetherGraphSeriesIds(it, catalog)
    }

    private fun isKrx(id: String): Boolean = id.startsWith("krx.")

    private fun admitsKrx(
        stamp: KrxGraphStamp?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        krxAdmission: (KrxGraphStamp) -> Boolean
    ): Boolean = stamp != null && stamp.scope.owner == fence && krxGraphNamespace(stamp.scope) != null &&
        snapshot.admitsKrxUse(stamp.scope.use) && krxAdmission(stamp)

    private fun sameKrxUse(a: KrxGraphStamp, b: KrxGraphStamp): Boolean =
        krxGraphNamespace(a.scope) == krxGraphNamespace(b.scope) && a.scope.use == b.scope.use

    /** Held inputs must finish before their time-free losses are handed over. */
    private fun transferPendingLoss(
        state: GraphRecorderState,
        catalog: GraphCatalog,
        now: Instant,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        krxAdmission: (KrxGraphStamp) -> Boolean
    ): GraphRecorderState {
        if (state.pending.inputs.isNotEmpty() || (state.pending.lostTopics.isEmpty() && state.pending.krxLost.isEmpty())) return state
        val scope = state.scope ?: return state
        val series = state.series.toMutableMap()
        val untransferred = state.untransferredSeries.toMutableSet()
        for (id in seriesIds(state.pending.lostTopics, catalog)) {
            val key = GraphObservationSeriesKey(scope, id)
            val previous = series[key]
            if (previous == null) {
                untransferred.add(id)
            } else {
                series[key] = requireGraphRecoveryWindow(previous, GraphRecoveryReason.HANDOVER_LOSS, now)
            }
        }
        val krxUntransferred = state.krxUntransferred.toMutableMap()
        for ((topic, originals) in state.pending.krxLost) {
            val valid = originals.filterTo(linkedSetOf()) { admitsKrx(it, snapshot, fence, krxAdmission) }
            if (valid.isEmpty()) continue
            for (id in krxGraphSeriesIds(topic, catalog)) {
                val key = GraphObservationSeriesKey(scope, id)
                val owner = state.krxSeriesOwners[id]
                val previous = series[key]
                if (owner != null && previous != null && admitsKrx(owner, snapshot, fence, krxAdmission) &&
                    valid.any { sameKrxUse(owner, it) }) {
                    series[key] = requireGraphRecoveryWindow(previous, GraphRecoveryReason.HANDOVER_LOSS, now)
                } else {
                    krxUntransferred[id] = krxUntransferred[id].orEmpty() + valid
                }
            }
        }
        return updated(state, series = series, pending = GraphPendingInputs.EMPTY, untransferredSeries = untransferred,
            krxUntransferred = krxUntransferred)
    }

    /**
     * Syncs access before handling an observation attributed to the held scope. An admitted input is
     * adopted or held until the catalog arrives. A refused input, including one dropped for a missing
     * current scope, records HANDOVER_LOSS over its mapped span on existing series, or a topic loss
     * without times when the catalog is absent. It creates no series or deferred series marker.
     * A refused KRX original is ignored entirely; it cannot create recovery for a successor.
     *
     * Losses below the snapshot's user-end invalidation floor are discarded; equality passes. Price
     * adoption relies on the original capture's admission. Pending losses transfer only while the
     * held scope is current, so a missing scope leaves earlier losses waiting.
     */
    fun observe(
        state: GraphRecorderState,
        input: TopicGraphInput.Observations,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision) return synced
        val scope = synced.scope ?: return synced
        // Attribution precedes admission and loss handling: a foreign input belongs to neither path here.
        if (scopeOf(input.attribution.owner) != scope) return synced
        val krx = isKrxGraphTopic(input.topic)
        // Refused KRX is never converted into a new recovery demand or rebound to a current use.
        val inputStamp = input.krxGraphStamp
        if (krx && (!admission || inputStamp == null || inputStamp.scope.owner != input.attribution.owner ||
                inputStamp.scope.use.lifetime != input.attribution.lifetime ||
                !admitsKrx(inputStamp, snapshot, fence, krxAdmission))) return synced
        val current = scopeContinues(synced, fence)
        val allowed = current && usable(synced, snapshot, fence, admission)
        if (!allowed && input.attribution.lifetime.invalidations < snapshot.userEndInvalidationsFloor) return synced
        if (catalog == null) {
            val pending = if (allowed) offerGraphPendingInput(synced.pending, input)
            else markGraphPendingLoss(synced.pending, setOf(input.topic))
            return updated(synced, pending = pending)
        }

        val ready = if (current) transferPendingLoss(synced, catalog, now, snapshot, fence, krxAdmission) else synced
        val tether = tetherGraphMapping(scope, input, catalog)
        val krxMapping: KrxGraphMapping = if (krx) krxGraphMapping(scope, input, catalog)
            else KrxGraphMapping(emptyList(), emptySet())
        val observations = fxGraphObservations(scope, input, catalog) + dxyGraphObservations(scope, input, catalog) +
            tether.observations + krxMapping.observations
        val series = ready.series.toMutableMap()
        val untransferred = ready.untransferredSeries.toMutableSet()
        val owners = ready.krxSeriesOwners.toMutableMap()
        val krxUntransferred = ready.krxUntransferred.toMutableMap()
        for ((key, batch) in observations.groupBy { it.seriesKey }) {
            val original = input.krxGraphStamp.takeIf { isKrx(key.seriesId) }
            val anchor = owners[key.seriesId]
            val continues = original == null || anchor != null &&
                admitsKrx(anchor, snapshot, fence, krxAdmission) && sameKrxUse(anchor, original)
            // Only a newly valid source replaces an unusable old anchor, with fresh buckets and demands.
            val previous = series[key].takeIf { continues }
            if (original != null && !continues) owners[key.seriesId] = original
            if (allowed) {
                val initial = previous ?: requireGraphRecovery(
                    GraphRecoverableState.empty(key, if (key.seriesId == "dxy") DXY_GRAPH_OBSERVATION_ORDER else fxOrder),
                    now, now, GraphRecoveryReason.INITIAL_SYNC, now
                )
                val observed = observeRecoverable(initial, batch, now).state.let {
                    // An evidence time dropped before D3 still leaves its recovery demand (plan :1345).
                    if (key in tether.anomalies || key in krxMapping.anomalies) requireGraphRecovery(it, now, now, GraphRecoveryReason.TIME_ANOMALY, now) else it
                }
                val krxLoss = if (original != null) krxUntransferred.remove(key.seriesId).orEmpty().any {
                    admitsKrx(it, snapshot, fence, krxAdmission) && sameKrxUse(it, original)
                } else false
                series[key] = if (previous == null && (untransferred.remove(key.seriesId) || krxLoss)) {
                    requireGraphRecoveryWindow(observed, GraphRecoveryReason.HANDOVER_LOSS, now)
                } else observed
            } else if (previous != null) {
                // One loss span per series gives all affected buckets the same new D3 generation.
                series[key] = requireGraphRecovery(
                    previous, batch.minOf { it.id.observedAt }, batch.maxOf { it.id.observedAt },
                    GraphRecoveryReason.HANDOVER_LOSS, now
                )
            }
        }
        return updated(ready, series = series, untransferredSeries = untransferred,
            krxSeriesOwners = owners, krxUntransferred = krxUntransferred)
    }

    /**
     * Syncs access and transfers eligible pending losses before handling a continuity fact. Only a
     * DELIVERY_RESUMED from the held scope with an original lifetime at or above the snapshot's
     * user-end invalidation floor is applied or held. GENERAL needs no admission and creates no series;
     * the KRX part also checks the fact's original source and the existing series anchor.
     * Resumes wait in order while the catalog or current scope is absent, or earlier inputs are held.
     */
    fun continuity(
        state: GraphRecorderState,
        input: TopicGraphInput.Continuity,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision) return synced
        val ready = if (catalog != null && scopeContinues(synced, fence)) transferPendingLoss(synced, catalog, now, snapshot, fence, krxAdmission)
        else synced
        val scope = ready.scope ?: return ready
        if (input.kind != TopicGraphEventKind.DELIVERY_RESUMED || scopeOf(input.authority.owner) != scope) return ready
        val lifetime = input.authority.lifetime ?: return ready
        if (lifetime.invalidations < snapshot.userEndInvalidationsFloor) return ready
        if (catalog == null || !scopeContinues(ready, fence) || ready.pending.inputs.isNotEmpty()) {
            return updated(ready, pending = offerGraphPendingInput(ready.pending, input))
        }

        val series = ready.series.toMutableMap()
        val original = input.authority.krxGraphStamp?.takeIf {
            it.scope.owner == input.authority.owner && it.scope.use.lifetime == lifetime
        }
        val krxIds = if (admitsKrx(original, snapshot, fence, krxAdmission))
            input.topics.flatMapTo(linkedSetOf()) { krxGraphSeriesIds(it, catalog) } else emptySet()
        for (id in seriesIds(input.topics, catalog) + krxIds) {
            if (isKrx(id)) {
                val anchor = ready.krxSeriesOwners[id] ?: continue
                if (!admitsKrx(anchor, snapshot, fence, krxAdmission) || !sameKrxUse(anchor, checkNotNull(original))) continue
            }
            val key = GraphObservationSeriesKey(scope, id)
            val previous = series[key] ?: continue
            series[key] = requireGraphRecoveryWindow(previous, GraphRecoveryReason.RECEIVE_GAP, now)
        }
        return updated(ready, series = series)
    }

    /**
     * Syncs access before accepting topic losses from [ownerScope]. Each topic's maximum original
     * lifetime invalidation count must meet the snapshot's user-end floor; equality passes. A stale
     * snapshot or a foreign scope contributes no loss. Accepted topics carry no observation times.
     *
     * With a catalog, a current held scope and no held inputs, losses transfer over the recovery
     * window to existing mapped series and wait in [GraphRecorderState.untransferredSeries] for
     * absent ones. Otherwise they remain pending until replay can hand them over.
     */
    fun loseTopics(
        state: GraphRecorderState,
        ownerScope: GraphDataScope,
        maxInvalidationsByTopic: Map<String, Long>,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || synced.scope != ownerScope) return synced
        val topics = maxInvalidationsByTopic.filterValues { it >= snapshot.userEndInvalidationsFloor }.keys
        if (topics.isEmpty()) return synced
        val marked = updated(synced, pending = markGraphPendingLoss(synced.pending, topics))
        return if (catalog != null && scopeContinues(marked, fence)) transferPendingLoss(marked, catalog, now, snapshot, fence, krxAdmission) else marked
    }

    fun loseKrxTopics(
        state: GraphRecorderState,
        stampsByTopic: Map<String, Set<KrxGraphStamp>>,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision) return synced
        val valid = stampsByTopic.filterKeys(::isKrxGraphTopic).mapValues { (_, originals) ->
            originals.filterTo(linkedSetOf()) { admitsKrx(it, snapshot, fence, krxAdmission) }
        }.filterValues { it.isNotEmpty() }
        if (valid.isEmpty()) return synced
        val marked = updated(synced, pending = markGraphPendingKrxLoss(synced.pending, valid))
        return if (catalog != null && scopeContinues(marked, fence))
            transferPendingLoss(marked, catalog, now, snapshot, fence, krxAdmission) else marked
    }

    /**
     * Replays held inputs in order under the same snapshot, applying its user-end floor through
     * [observe] and [continuity], then transfers their time-free losses. Admissions correspond only
     * to the Observations subsequence of the original holding. A missing current scope waits.
     */
    fun replayPending(
        state: GraphRecorderState,
        catalog: GraphCatalog,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        observationAdmissions: List<Boolean>,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || !scopeContinues(synced, fence)) return synced
        if (synced.pending.inputs.isEmpty() && synced.pending.lostTopics.isEmpty() && synced.pending.krxLost.isEmpty()) return synced
        val inputs = synced.pending.inputs
        require(observationAdmissions.size == inputs.count { it is TopicGraphInput.Observations })
        val lostTopics = synced.pending.lostTopics
        val krxLost = synced.pending.krxLost
        var next = updated(synced, pending = GraphPendingInputs.EMPTY)
        var observationIndex = 0
        for (input in inputs) {
            next = when (input) {
                is TopicGraphInput.Observations -> observe(
                    next, input, catalog, snapshot, fence, observationAdmissions[observationIndex++], now, krxAdmission
                )
                is TopicGraphInput.Continuity -> continuity(next, input, catalog, snapshot, fence, now, krxAdmission)
            }
        }
        val pending = markGraphPendingKrxLoss(markGraphPendingLoss(next.pending, lostTopics), krxLost)
        return transferPendingLoss(updated(next, pending = pending), catalog, now, snapshot, fence, krxAdmission)
    }

    fun offerPending(
        state: GraphRecorderState,
        input: TopicGraphInput,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || !scopeContinues(synced, fence)) return synced
        val original = when (input) {
            is TopicGraphInput.Observations -> input.krxGraphStamp
            is TopicGraphInput.Continuity -> input.authority.krxGraphStamp
        }
        if (input is TopicGraphInput.Observations && isKrxGraphTopic(input.topic) &&
            (!admission || original == null || original.scope.owner != input.attribution.owner ||
                original.scope.use.lifetime != input.attribution.lifetime ||
                !admitsKrx(original, snapshot, fence, krxAdmission))) return synced
        val pending = if (usable(synced, snapshot, fence, admission)) offerGraphPendingInput(synced.pending, input)
        else {
            val topics = when (input) {
                is TopicGraphInput.Observations -> setOf(input.topic)
                is TopicGraphInput.Continuity -> input.topics
            }
            val krxLoss = input.krxGraphLoss()
            markGraphPendingKrxLoss(markGraphPendingLoss(synced.pending, topics - krxLoss.keys), krxLoss)
        }
        return updated(synced, pending = pending)
    }

    fun captureRequests(
        state: GraphRecorderState,
        keys: Set<GraphObservationSeriesKey>,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): Pair<GraphRecorderState, List<GraphRecoveryRequest>> {
        val synced = syncAccess(state, snapshot, fence)
        if (!usable(synced, snapshot, fence, admission)) {
            return synced to emptyList()
        }
        var nextVersion = synced.nextVersion
        val requests = mutableListOf<GraphRecoveryRequest>()
        for (key in keys) {
            val original = synced.krxSeriesOwners[key.seriesId]
            if (isKrx(key.seriesId) && !admitsKrx(original, snapshot, fence, krxAdmission)) continue
            val series = synced.series[key] ?: continue
            if (series.pending.isEmpty()) continue
            nextVersion = Math.incrementExact(nextVersion)
            requests.add(captureGraphRecoveryRequest(series, nextVersion).copy(krxGraphStamp = original))
        }
        return updated(synced, nextVersion = nextVersion) to Collections.unmodifiableList(requests)
    }

    fun applyResponse(
        state: GraphRecorderState,
        request: GraphRecoveryRequest,
        tab: GraphV2Tab,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (!usable(synced, snapshot, fence, admission) || request.applicationVersion <= synced.versionFloor) return synced
        val scope = synced.scope ?: return synced
        if (isKrx(request.seriesKey.seriesId)) {
            val original = request.krxGraphStamp ?: return synced
            val anchor = synced.krxSeriesOwners[request.seriesKey.seriesId] ?: return synced
            if (!admitsKrx(original, snapshot, fence, krxAdmission) ||
                !admitsKrx(anchor, snapshot, fence, krxAdmission) || !sameKrxUse(anchor, original)) return synced
        }
        val previous = synced.series[request.seriesKey] ?: return synced
        val applied = applyGraphRecoveryResponse(previous, scope, request, tab, now)
        return updated(synced, series = synced.series + (request.seriesKey to applied))
    }

    /**
     * S4 RT03b-1a: the closed-bucket demands and mapping or handover waits [state] holds for [tab]'s 1d series, computed on a
     * discarded access synchronization and never stored. Access that is not usable is [GraphTabRecoveryDemand.Unreadable];
     * a catalog that does not resolve the tab's 1d series is [GraphTabRecoveryDemand.CatalogRequired], whatever is held.
     * Otherwise closed demands of the retained window and mapping or handover waits that map to those series decide
     * [GraphTabRecoveryDemand.Pending].
     */
    fun recoveryDemand(
        state: GraphRecorderState,
        tab: String,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        now: Instant,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): GraphTabRecoveryDemand {
        val synced = syncAccess(state, snapshot, fence)
        if (!usable(synced, snapshot, fence, admission)) return GraphTabRecoveryDemand.Unreadable
        val ids = catalog?.tabs?.get(tab)?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries?.toSet()
            ?: return GraphTabRecoveryDemand.CatalogRequired
        val scope = checkNotNull(synced.scope) { "usable access has a scope" }
        val visibleIds = ids.filterTo(linkedSetOf()) { id ->
            !isKrx(id) || admitsKrx(synced.krxSeriesOwners[id], snapshot, fence, krxAdmission)
        }
        val closed = visibleIds.any { id ->
            synced.series[GraphObservationSeriesKey(scope, id)]?.let { hasClosedGraphRecoveryDemand(it, now) } == true
        }
        val heldTopics = synced.pending.inputs.flatMapTo(linkedSetOf()) {
            when (it) {
                is TopicGraphInput.Observations -> setOf(it.topic).filterNot(::isKrxGraphTopic).toSet()
                is TopicGraphInput.Continuity -> it.topics.filterNot(::isKrxGraphTopic).toSet()
            }
        } + synced.pending.lostTopics
        val krxWaits = synced.pending.inputs.flatMapTo(linkedSetOf()) { input ->
            input.krxGraphLoss().filterValues { originals -> originals.any { admitsKrx(it, snapshot, fence, krxAdmission) } }.keys
        } + synced.pending.krxLost.filterValues { originals -> originals.any { admitsKrx(it, snapshot, fence, krxAdmission) } }.keys
        val mappingWait = seriesIds(heldTopics, catalog).any { it in ids } || synced.untransferredSeries.any { it in ids } ||
            krxWaits.any { topic -> krxGraphSeriesIds(topic, catalog).any { it in ids } } ||
            synced.krxUntransferred.any { (id, originals) -> id in ids && originals.any { admitsKrx(it, snapshot, fence, krxAdmission) } }
        return if (closed || mappingWait) GraphTabRecoveryDemand.Pending(closed, mappingWait) else GraphTabRecoveryDemand.None
    }

    fun exposed(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        krxAdmission: (KrxGraphStamp) -> Boolean = { false }
    ): Map<GraphObservationSeriesKey, GraphRecoverableState> =
        if (usable(state, snapshot, fence, admission)) state.series.filterKeys {
            !isKrx(it.seriesId) || admitsKrx(state.krxSeriesOwners[it.seriesId], snapshot, fence, krxAdmission)
        } else emptyMap()
}
