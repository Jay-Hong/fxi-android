package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime

/**
 * A new protected use may start under [fence] only when the issued token is that fence's grant and still stands, and the user
 * axis is allowed (L-4e E2a design v4 §2.1). Reads this one snapshot and nothing else.
 *
 * The capability axis is not consulted: no topic this session consumes is a KRX one, and a capability-only hold must not stop
 * the rest (the S6 boundary will add it for KRX).
 */
internal fun TopicAccessSnapshot.acquireUse(fence: TopicSessionFence): TopicUseLifetime? {
    val token = facts.token ?: return null
    if (token != fence.grant || !facts.tokenStanding || !facts.userAllowed) return null
    return TopicUseLifetime(token, userInvalidations)
}

/** A use started under [lifetime] may still happen: the same token still stands, the user axis is allowed, and nothing withdrew it in between. */
internal fun TopicAccessSnapshot.admitsUse(lifetime: TopicUseLifetime): Boolean =
    facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations

/** The issuer's published snapshot, read once per question. */
internal class SnapshotTopicUseAuthority(private val snapshot: () -> TopicAccessSnapshot) : TopicUseAuthority {
    override fun acquire(fence: TopicSessionFence): TopicUseLifetime? = snapshot().acquireUse(fence)
    override fun admits(lifetime: TopicUseLifetime): Boolean = snapshot().admitsUse(lifetime)
}
