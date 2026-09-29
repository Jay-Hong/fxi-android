package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.local.TopicLastKnownStore
import kotlinx.coroutines.CancellationException

/**
 * S3-P1: the user-axis deletion of `datastore:fxi_topic_last_known` for one journal entry — every namespace of the entry's owner
 * but the live epoch. Answers for this target alone. Registered nowhere yet; the production purger stays the placeholder until P3.
 */
internal class TopicLastKnownPurgeAdapter(private val store: TopicLastKnownStore) : PurgeTargetAdapter {
    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        if (request.scope != PurgeScope.USER) return TargetOutcome.NothingToRemove
        val ownerUid = request.namespace.pending.ownerUid
            ?: return TargetOutcome.Failed("topic last-known purge requires an owner UID")
        val keepEpoch = request.namespace.currentUserAccessEpoch
        if (request.namespace.pending.userAccessEpoch == keepEpoch && keepEpoch != null) {
            return TargetOutcome.Failed("pending user epoch is the current user epoch")
        }
        return try {
            if (store.purgeUser(ownerUid, keepEpoch)) TargetOutcome.Removed else TargetOutcome.NothingToRemove
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            TargetOutcome.Failed(thrown.message ?: thrown::class.java.name, thrown)
        }
    }
}
