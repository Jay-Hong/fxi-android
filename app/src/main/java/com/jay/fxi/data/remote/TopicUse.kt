package com.jay.fxi.data.remote

/**
 * One use of a topic grant, from the logical start that acquired it (L-4e E2a).
 *
 * Opaque to the session, which only hands it back to the [TopicUseAuthority] that issued it. A short hold that came and went
 * leaves [invalidations] behind, so a use started before the hold is not admitted again even though the grant is the same.
 */
data class TopicUseLifetime(val grant: TopicGrantToken, val invalidations: Long)

/**
 * The issuer's published access, as the session asks it (L-4e E2a).
 *
 * Both calls are thread-safe and side-effect free, and each decides from one published snapshot alone: a network interceptor
 * asks from OkHttp's threads, the session from its own scope.
 */
interface TopicUseAuthority {
    /** A lifetime for a new protected use under [fence], or null when its grant may not start one now. */
    fun acquire(fence: TopicSessionFence): TopicUseLifetime?

    /** Whether a use started under [lifetime] may still happen now. A new [acquire] succeeding says nothing about this. */
    fun admits(lifetime: TopicUseLifetime): Boolean
}

/**
 * Who a deferred hand-over belongs to: attribution only, which authorises nothing by itself (L-4e E2a).
 *
 * A consumer that runs a protected side effect later hands it back to [TopicSessionCoordinator.runIfStillOwned], which checks it
 * against the session that issued it — its instance, grant epoch, fence, latches, live identity and the use's lifetime.
 */
class TopicUseAttribution internal constructor(
    internal val sessionKey: Any,
    val owner: TopicSessionFence,
    internal val grantEpoch: Long,
    internal val lifetime: TopicUseLifetime
)

/**
 * What a deliverer tells the session about the grant it may run under (L-4e E3).
 *
 * Both calls only post to the session's loop: neither suspends nor reads anything, so a deliverer can make them from wherever it
 * decided. [setAccess] carries a grant or its explicit end, [accessRevised] a new access snapshot under the context already held.
 * A [TopicGrantOrigin.Reapproval] carries the reconnection budget over when the session's continuation conditions hold;
 * an explicit end carries [TopicGrantOrigin.NewContext] (L-4e E5).
 */
interface TopicGrantSink {
    fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin)

    fun accessRevised()
}

/**
 * Why a grant handed to [TopicGrantSink.setAccess] was issued, as far as the session needs to know (L-4e E5).
 *
 * The session reads nothing into the grant token itself; this is the one fact about its issuance it acts on. An end is always
 * [NewContext].
 */
sealed interface TopicGrantOrigin {
    /** A grant for a context the session did not hold, or one the deliverer could not show continues it. */
    data object NewContext : TopicGrantOrigin

    /** The issuer re-approved [replaced] in the same context after a refusal; the session keeps its reconnection budget. */
    data class Reapproval(val replaced: TopicGrantToken) : TopicGrantOrigin
}
