package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRecorderPurge
import com.jay.fxi.data.graph.GraphRecorderTopicSink
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Deletes this manifest target for one owner, across every epoch except the namespace's current
 * one. The pending entry's epoch does not narrow the sweep. Invalid requests touch no holder.
 *
 * All three holders must come from one assembly using [serialDispatcher] for the coordinator
 * loop, recorder methods and control collector, and sink worker. This is a caller precondition,
 * verified by RT01 rather than checked here. Run outside sink consumer calls. [withContext]
 * enters that executor; when already there it does not dispatch again. In one block without
 * suspension, USER checks the recorder's live fence; CAPABILITY freezes a plan from the actual
 * confirmed record before any mutation. The sink and registered captures then use the same selection.
 * A live selected scope refuses before either later holder is
 * touched. Each answer is obtained independently before aggregation. CAPABILITY exceptions become
 * Failed; USER exceptions reach the manifest purger. Cancellation always propagates.
 *
 * Completion covers recorder state, the sink's queue and ledger, and registered recovery captures
 * at that moment. These remain runtime cleanup obligations: a released registration still held by
 * an in-flight request; the coordinator's entries, protected slots and 1d recovery budgets; screen
 * publications; and the topic session's graph loss record. This target answers for none of those or any disk target.
 */
internal class GraphRecorderPurgeAdapter(
    private val recorder: GraphRecorder,
    private val sink: GraphRecorderTopicSink,
    private val coordinator: GraphV2RequestCoordinator,
    private val serialDispatcher: CoroutineDispatcher
) : PurgeTargetAdapter {
    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        if (request.target != PurgeManifest.byId(TARGET_ID)) {
            return TargetOutcome.Failed("request does not name the graph recorder manifest target")
        }
        val namespace = request.namespace
        val pending = namespace.pending
        val owner = pending.ownerUid
        if (owner.isNullOrBlank() || namespace.ownerUid != owner) {
            return TargetOutcome.Failed("graph recorder purge requires a matching non-blank owner")
        }
        if (request.scope == PurgeScope.CAPABILITY) {
            if (PurgeScope.CAPABILITY !in pending.scopes) {
                return TargetOutcome.Failed("graph recorder purge requires the capability pending axis")
            }
            return try {
                withContext(serialDispatcher) {
                    val plan = recorder.captureKrxMemoryPurgePlan(namespace)
                        ?: return@withContext TargetOutcome.Failed("capability namespace is live or unconfirmed")
                    val outcome = recorder.purgeKrxMemory(plan) + sink.purgeKrxMemory(plan) +
                        coordinator.purgeKrxRecoveryCaptures(plan)
                    when {
                        outcome.unresolved -> TargetOutcome.Failed("graph recorder capability originals are unresolved")
                        outcome.removed -> TargetOutcome.Removed
                        else -> TargetOutcome.NothingToRemove
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TargetOutcome.Failed("graph recorder capability cleanup failed", failure)
            }
        }
        if (request.scope != PurgeScope.USER || PurgeScope.USER !in pending.scopes) {
            return TargetOutcome.Failed("graph recorder purge requires the user axis")
        }
        val keep = namespace.currentUserAccessEpoch
        if (keep != null && pending.userAccessEpoch == keep) {
            return TargetOutcome.Failed("the retired epoch is the current user epoch")
        }
        val selects: (GraphDataScope) -> Boolean = { it.uid == owner && it.userAccessEpoch != keep }
        return withContext(serialDispatcher) {
            val recorded = recorder.purge(selects)
            if (recorded == GraphRecorderPurge.LIVE_SCOPE_SELECTED) {
                return@withContext TargetOutcome.Failed("a selected graph recorder scope is still live")
            }
            val queued = sink.purge(selects)
            val captured = coordinator.purgeRecoveryCaptures(selects)
            if (recorded == GraphRecorderPurge.REMOVED || queued || captured) {
                TargetOutcome.Removed
            } else {
                TargetOutcome.NothingToRemove
            }
        }
    }

    companion object {
        const val TARGET_ID = "memory:graph_recorder"
    }
}
