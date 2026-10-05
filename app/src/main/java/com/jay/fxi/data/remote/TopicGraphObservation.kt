package com.jay.fxi.data.remote

import kotlinx.datetime.Instant

enum class TopicGraphPath { WS, REST_BOOTSTRAP }

enum class TopicGraphEventKind {
    INITIAL, ACCESS_RESUMED, DELIVERY_RESUMED, AUTHORITY_ENDED, TOPIC_PURGED, HANDOVER_ENDED,
    DELIVERY_INTERRUPTED, CONNECTION_CREATION_FAILED
}

enum class TopicGraphEventReason {
    GRANT_WITHDRAWN, GRANT_REPLACED, IDENTITY_RETIRED, PREMIUM_REFUSED, USE_WITHHELD,
    CANONICAL_TOPIC_STATE,
    STOPPED, SCOPE_CANCELLED,
    WS_ENDED, LEASE_EXPIRED, DELIVERY_SUSPECT, DELIVERY_DEGRADED, REST_UNDELIVERED
}

enum class TopicGraphRestResultKind {
    DORMANT, UNSUPPORTED, DEGRADED, TEMPORARILY_UNAVAILABLE, REFUSED, MALFORMED, TIMED_OUT, UNREACHABLE
}

sealed interface TopicGraphEventPayload {
    data class WsEnded(val cause: TopicDisconnectCause) : TopicGraphEventPayload
    data class RestUndelivered(val resultKind: TopicGraphRestResultKind) : TopicGraphEventPayload
}

/** Captured continuity ownership; owner and lifetime are null when the session ends without an open authority. */
class TopicGraphAuthority internal constructor(
    internal val sessionKey: Any,
    val owner: TopicSessionFence?,
    internal val grantEpoch: Long,
    internal val lifetime: TopicUseLifetime?
)

/** Value-comparable original ownership of a lost hand-over. */
data class TopicGraphAuthorityKey(val owner: TopicSessionFence?, val grantEpoch: Long)

/** Validated wire observations, before the display chooses which prices to adopt. */
sealed interface TopicGraphCandidate {
    data class Quote(
        val source: String,
        val asset: String,
        val rate: Double,
        val timestamp: Instant,
        val rateChangedAt: Instant?
    ) : TopicGraphCandidate

    data class DollarIndex(
        val rate: Double,
        val timestamp: Instant,
        val source: String
    ) : TopicGraphCandidate
}

sealed interface TopicGraphInput {
    val sequence: Long

    /** Attribution identifies the original use; enqueueing authorises no deferred protected side effect. */
    class Observations(
        override val sequence: Long,
        val topic: String,
        val path: TopicGraphPath,
        val attribution: TopicUseAttribution,
        val connectionGeneration: Long?,
        val candidates: List<TopicGraphCandidate>
    ) : TopicGraphInput

    /** A confirmed continuity fact; attribution alone authorises no deferred protected side effect. */
    class Continuity(
        override val sequence: Long,
        val kind: TopicGraphEventKind,
        val reason: TopicGraphEventReason?,
        val topics: Set<String>,
        val paths: Set<TopicGraphPath>,
        val authority: TopicGraphAuthority,
        val connectionGeneration: Long?,
        val occurredAtEpochMillis: Long,
        val payload: TopicGraphEventPayload? = null
    ) : TopicGraphInput
}

enum class TopicGraphOffer { ENQUEUED, DORMANT, FULL, CLOSED, FAILED }

/**
 * Offers a whole batch synchronously: all accepted or all lost. Must return promptly, without I/O,
 * reducing observations, querying authority or re-entering the coordinator.
 * [TopicGraphOffer.ENQUEUED] means queue acceptance only, never adoption or recording.
 */
fun interface TopicGraphSink {
    fun tryOffer(input: TopicGraphInput): TopicGraphOffer
}

object DormantTopicGraphSink : TopicGraphSink {
    override fun tryOffer(input: TopicGraphInput): TopicGraphOffer = TopicGraphOffer.DORMANT
}

/** Cumulative batch losses, readable independently of the sink's queue and retained after successful offers. */
data class TopicGraphLoss(
    val revision: Long,
    val count: Long,
    val firstSequence: Long,
    val lastSequence: Long,
    val reasons: Set<TopicGraphOffer>,
    val topics: Set<String>,
    val paths: Set<TopicGraphPath>,
    val firstOccurredAtEpochMillis: Long,
    val lastOccurredAtEpochMillis: Long,
    val authorities: Set<TopicGraphAuthorityKey>
)
