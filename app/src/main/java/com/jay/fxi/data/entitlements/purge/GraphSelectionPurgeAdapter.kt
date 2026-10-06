package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.local.BackupableUserIntentStore
import com.jay.fxi.data.local.GraphSelectionDeleteResult
import kotlinx.coroutines.CancellationException

/** B2 deletion boundary; durable authorization and writer drain are supplied by the later runtime. */
class GraphSelectionPurgeAdapter(
    private val store: BackupableUserIntentStore,
    private val authorizationFor: (PurgeNamespace) -> DeletionAuthorization? = { null }
) : PurgeTargetAdapter {
    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        return try {
            val canonical = PurgeManifest.byId(TARGET_ID)
                ?: return TargetOutcome.Failed("Graph selection target is missing from the manifest")
            if (request.target != canonical || PurgeScope.USER !in request.namespace.pending.scopes) {
                return TargetOutcome.Failed("Graph selection deletion requires its canonical target and a USER entry")
            }
            val owner = request.namespace.pending.ownerUid
            if (owner.isNullOrBlank() || request.namespace.ownerUid != owner) {
                return TargetOutcome.Failed("Graph selection deletion requires matching nonblank owners")
            }
            // The axis, the cause and the authorisation's owner and cleanup phase are PurgeDecision's rules, not restated here.
            val authorization = authorizationFor(request.namespace)
            if (authorization?.operationId.isNullOrBlank() ||
                PurgeDecision.disposition(canonical, request.scope, request.cause, request.namespace, authorization) !=
                TargetDisposition.DELETE_NOW
            ) {
                return TargetOutcome.Failed("Graph selection deletion requires covering authorization")
            }
            when (val result = store.deleteGraphSelections(owner)) {
                is GraphSelectionDeleteResult.Committed ->
                    if (result.removedRecords > 0) TargetOutcome.Removed else TargetOutcome.NothingToRemove
                is GraphSelectionDeleteResult.NotCommitted -> TargetOutcome.Failed("Graph selection deletion was not committed", result.cause)
                is GraphSelectionDeleteResult.Uncertain -> TargetOutcome.Failed("Graph selection deletion is uncertain", result.cause)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TargetOutcome.Failed("Graph selection deletion failed", failure)
        }
    }

    companion object {
        const val TARGET_ID = "datastore:fxi_backupable_user_intent"
    }
}
