package com.jay.fxi.data.remote

/**
 * What the topic session published about automatic graph recovery under its current grant (S4 RT03b-3b, P7).
 *
 * The session builds one per change on its own loop and hands it to graph through a thread-safe supplier; that publisher is
 * wired at cutover, so no production code builds one yet. Graph never re-derives any field from fence comparisons.
 *
 * - [sessionKey] is the publishing session's opaque identity; [revision] increases within it whenever the permit's meaning
 *   changes.
 * - [fence] is the grant the session processed; graph admits only when it equals both the published fence and the
 *   request's fence. [grantEpoch] is carried for the session's own bookkeeping; graph does not read it.
 * - [reapproved] is the session's own Access decision for this grant.
 * - [connectionGeneration] and [connectionLifetime] describe a live Connection made under this grant's fence, or are both
 *   null when there is none or it ended.
 * - [automatic] is the session's verdict for automatic issue, already reflecting offline, withdrawal and session end; under a
 *   re-approval it needs a Connection of the current grant, never `Opened` or an ACK.
 *
 * A supplier with no fence to publish returns null. It must not throw, and [sessionKey] and [revision] must stay equal
 * under `equals` across reads of one publication: graph parks a waiting budget on them.
 */
internal data class TopicGraphRecoveryPermit(
    val sessionKey: Any,
    val revision: Long,
    val fence: TopicSessionFence,
    val grantEpoch: Long,
    val reapproved: Boolean,
    val connectionGeneration: Long?,
    val connectionLifetime: TopicUseLifetime?,
    val automatic: Boolean
)
