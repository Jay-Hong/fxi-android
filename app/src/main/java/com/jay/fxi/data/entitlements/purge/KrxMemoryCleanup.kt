package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.KrxUseLifetime
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.admitsKrxUse
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.TopicQuoteKey

internal enum class KrxMemoryCause { CAPABILITY_END, TOPIC_UNAVAILABLE, WITHHELD, USER_END }
internal enum class KrxMemoryCleanupResult { SESSION_MEMORY_CLEANED, NOTHING_SELECTED, UNRESOLVED_SCOPE }
internal data class KrxMemoryCleanupEvent(val cause: KrxMemoryCause, val result: KrxMemoryCleanupResult)

internal val KRX_MEMORY_KEY = TopicQuoteKey("krx", "usd-krw-futures")

/** The provenance of an adopted value, never filled in from a later publication. */
internal data class KrxMemoryScope(
    val owner: TopicSessionFence,
    val use: KrxUseLifetime,
    val acquiredRevision: Long,
    val userEndSequence: Long,
    val capabilityEndSequence: Long
)

internal data class KrxMemorySelection(
    val cause: KrxMemoryCause,
    val scopes: Set<KrxMemoryScope> = emptySet(),
    val unresolved: Boolean = false
)

/** Pure selection only: no store, callbacks, authority acquisition, jobs or whole-purger completion. */
internal fun krxMemorySelections(
    snapshot: TopicAccessSnapshot,
    scopes: Set<KrxMemoryScope>,
    previousUserEnd: Long,
    previousCapabilityEnd: Long,
    hasUnscopedData: Boolean
): List<KrxMemorySelection> = buildList {
    fun selectEnd(end: TopicAccessEnd, cause: KrxMemoryCause): KrxMemorySelection {
        val user = cause == KrxMemoryCause.USER_END
        if (end.ownerUid.isNullOrBlank() || end.namespace.isNullOrBlank() ||
            end.binding?.let { it.ownerUid != end.ownerUid } == true) {
            return KrxMemorySelection(cause, unresolved = true)
        }
        var unresolved = hasUnscopedData || scopes.any { it.owner.identity.uid.isBlank() }
        val selected = scopes.filterTo(linkedSetOf()) { scope ->
            val namespace = if (user) scope.owner.userAccessEpoch else scope.use.krxCapabilityEpoch
            if (namespace.isNullOrBlank()) {
                if (scope.owner.identity.uid == end.ownerUid) unresolved = true
                return@filterTo false
            }
            if (scope.owner.identity.uid != end.ownerUid || namespace != end.namespace) return@filterTo false
            val seen = if (user) scope.userEndSequence else scope.capabilityEndSequence
            if (end.sequence <= seen || snapshot.revision <= scope.acquiredRevision) return@filterTo false
            // An old end in a newer publication cannot delete a freshly valid K2 in the same namespace.
            if (snapshot.admitsKrxUse(scope.use)) return@filterTo false
            if (user && scope.use.lifetime.invalidations >= snapshot.userEndInvalidationsFloor) return@filterTo false
            true
        }
        return KrxMemorySelection(cause, selected, unresolved)
    }

    snapshot.lastUserEnd?.takeIf { it.sequence > previousUserEnd }?.let {
        add(selectEnd(it, KrxMemoryCause.USER_END))
    }
    snapshot.lastCapabilityEnd?.takeIf { it.sequence > previousCapabilityEnd }?.let {
        add(selectEnd(it, KrxMemoryCause.CAPABILITY_END))
    }

    // A confirmed rotation proves retirement even when intermediate end publications were coalesced.
    // Missing/unconfirmed namespaces and another owner's record are never broad deletion authority.
    val facts = snapshot.facts
    val record = facts.recordFence
    if (record != null && !record.ownerUid.isNullOrBlank() && !facts.recordUnconfirmed) {
        val userRetired = scopes.filterTo(linkedSetOf()) { scope ->
            !facts.userContextUncertain && record.ownerUid == scope.owner.identity.uid &&
                !scope.owner.userAccessEpoch.isNullOrBlank() && !record.userAccessEpoch.isNullOrBlank() &&
                record.userAccessEpoch != scope.owner.userAccessEpoch
        }
        val capabilityRetired = scopes.filterTo(linkedSetOf()) { scope ->
            !facts.capabilityContextUncertain && record.ownerUid == scope.owner.identity.uid &&
                !scope.use.krxCapabilityEpoch.isNullOrBlank() && !record.krxCapabilityEpoch.isNullOrBlank() &&
                record.krxCapabilityEpoch != scope.use.krxCapabilityEpoch
        }
        fun addRotation(cause: KrxMemoryCause, retired: Set<KrxMemoryScope>) {
            val user = cause == KrxMemoryCause.USER_END
            val currentNamespace = if (user) record.userAccessEpoch else record.krxCapabilityEpoch
            val uncertain = if (user) facts.userContextUncertain else facts.capabilityContextUncertain
            val unresolved = !uncertain && !currentNamespace.isNullOrBlank() &&
                (hasUnscopedData || scopes.any { scope ->
                    val originalNamespace = if (user) scope.owner.userAccessEpoch else scope.use.krxCapabilityEpoch
                    scope.owner.identity.uid.isBlank() ||
                        scope.owner.identity.uid == record.ownerUid && originalNamespace.isNullOrBlank()
                })
            if (retired.isEmpty() && !unresolved) return
            val existing = indexOfFirst { it.cause == cause }
            if (existing < 0) add(KrxMemorySelection(cause, retired, unresolved))
            else set(existing, get(existing).copy(scopes = get(existing).scopes + retired,
                unresolved = get(existing).unresolved || unresolved))
        }
        addRotation(KrxMemoryCause.USER_END, userRetired)
        addRotation(KrxMemoryCause.CAPABILITY_END, capabilityRetired)
    }
    if (isEmpty() && (!facts.userAllowed || !facts.capabilityAllowed)) {
        add(KrxMemorySelection(KrxMemoryCause.WITHHELD))
    }
}
