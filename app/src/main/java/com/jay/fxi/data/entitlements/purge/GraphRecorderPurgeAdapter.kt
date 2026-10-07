package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRecorderPurge
import com.jay.fxi.data.graph.GraphRecorderTopicSink
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
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
 * suspension, the recorder checks the live fence at execution time, then the sink and coordinator
 * are purged with the same selector. A live selected scope refuses before either later holder is
 * touched. Each answer is obtained independently before aggregation; exceptions propagate to the
 * manifest purger, which reports failures and rethrows cancellation.
 *
 * Completion covers recorder state, the sink's queue and ledger, and registered recovery captures
 * at that moment. These remain runtime cleanup obligations: a released registration still held by
 * an in-flight request; the coordinator's entries and protected slots; screen publications; and the
 * topic session's graph loss record. This target answers for none of those or any disk target.
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
        if (request.scope != PurgeScope.USER || PurgeScope.USER !in pending.scopes) {
            return TargetOutcome.Failed("graph recorder purge requires the user axis")
        }
        val owner = pending.ownerUid
        if (owner.isNullOrBlank() || namespace.ownerUid != owner) {
            return TargetOutcome.Failed("graph recorder purge requires a matching non-blank owner")
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
