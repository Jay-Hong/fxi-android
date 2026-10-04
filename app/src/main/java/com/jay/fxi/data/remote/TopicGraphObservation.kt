package com.jay.fxi.data.remote

import kotlinx.datetime.Instant

enum class TopicGraphPath { WS, REST_BOOTSTRAP }

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
    val paths: Set<TopicGraphPath>
)
