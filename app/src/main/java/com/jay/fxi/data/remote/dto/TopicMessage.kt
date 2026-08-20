package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object TopicMessageType {
    const val SNAPSHOT = "snapshot"
    const val UPDATE = "update"
    const val SUBSCRIPTION_ACK = "subscription_ack"
    const val SUBSCRIPTION_ERROR = "subscription_error"
}

@Serializable
data class TopicEnvelope(
    val type: String,
    val version: Int? = null,
    val topic: String? = null
)

@Serializable
data class TopicSubscribeRequest(
    val type: String,
    @SerialName("request_id")
    val requestId: String,
    @SerialName("id_token")
    val idToken: String? = null,
    val topics: List<String>
) {
    companion object {
        fun subscribe(requestId: String, idToken: String, topics: List<String>) =
            TopicSubscribeRequest("subscribe", requestId, idToken, topics)

        fun unsubscribe(requestId: String, topics: List<String>) =
            TopicSubscribeRequest("unsubscribe", requestId, topics = topics)
    }
}

@Serializable
data class SubscriptionAckTopic(
    val topic: String,
    @SerialName("lease_id")
    val leaseId: String? = null,
    @SerialName("lease_duration_seconds")
    val leaseDurationSeconds: Long? = null
)

@Serializable
data class SubscriptionRejection(
    val topic: String,
    val error: String
)

@Serializable
data class SubscriptionAck(
    @SerialName("request_id")
    val requestId: String,
    val operation: String,
    @SerialName("accepted_topics")
    val acceptedTopics: List<SubscriptionAckTopic>,
    @SerialName("rejected_topics")
    val rejectedTopics: List<SubscriptionRejection>,
    @SerialName("removed_topics")
    val removedTopics: List<String>,
    @SerialName("active_subscriptions")
    val activeSubscriptions: List<SubscriptionAckTopic>
)

@Serializable
data class SubscriptionError(
    @SerialName("request_id")
    val requestId: String? = null,
    val error: String,
    @SerialName("retry_after_seconds")
    val retryAfterSeconds: Long? = null
) {
    val isTerminal: Boolean
        get() = error == "invalid_token" ||
            error == "invalid_request" ||
            error == "request_too_large"

    val isRetryable: Boolean
        get() = error == "temporarily_unavailable"

    fun wholeFailureOrNull(): TopicWholeRequestFailure? = when (error) {
        "temporarily_unavailable" -> retryAfterSeconds
            ?.takeIf { it >= 1 }
            ?.let { TopicWholeRequestFailure.TemporarilyUnavailable(it) }
        "invalid_token" -> TopicWholeRequestFailure.InvalidToken
        "invalid_request" -> TopicWholeRequestFailure.InvalidRequest
        "request_too_large" -> TopicWholeRequestFailure.RequestTooLarge
        else -> null
    }
}

fun SubscriptionRejection.reasonOrNull(): TopicRejectionReason? = when (error) {
    "topics_disabled" -> TopicRejectionReason.TOPICS_DISABLED
    "topic_unavailable" -> TopicRejectionReason.TOPIC_UNAVAILABLE
    "unknown_topic" -> TopicRejectionReason.UNKNOWN_TOPIC
    "premium_required" -> TopicRejectionReason.PREMIUM_REQUIRED
    "krx_entitlement_required" -> TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
    else -> null
}

data class TopicLease(
    val topic: String,
    val leaseId: String,
    val durationSeconds: Long
)

sealed interface TopicLeaseReadResult {
    data class Valid(val leases: List<TopicLease>) : TopicLeaseReadResult
    data object Malformed : TopicLeaseReadResult
}

fun readTopicLeases(items: List<SubscriptionAckTopic>): TopicLeaseReadResult {
    val leases = mutableListOf<TopicLease>()
    for (item in items) {
        val leaseId = item.leaseId
        val duration = item.leaseDurationSeconds
        when {
            leaseId == null && duration == null -> Unit
            leaseId.isNullOrEmpty() || duration == null || duration < 0 -> {
                return TopicLeaseReadResult.Malformed
            }
            else -> leases += TopicLease(item.topic, leaseId, duration)
        }
    }
    return TopicLeaseReadResult.Valid(leases)
}

@Serializable
data class TopicSourceEntry(
    val source: String,
    val asset: String,
    val rate: Double,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    @SerialName("rate_changed_at")
    @Serializable(with = InstantSerializer::class)
    val rateChangedAt: Instant? = null
) {
    val mergeAt: Instant get() = rateChangedAt ?: timestamp
}

@Serializable
data class TetherTopicMessage(
    val type: String,
    val version: Int,
    val topic: String,
    val data: TetherTopicData
)

@Serializable
data class TetherTopicData(
    @SerialName("usdt_krw")
    val usdtKrw: List<TopicSourceEntry>,
    @SerialName("usd_krw_banks")
    val usdKrwBanks: List<TopicSourceEntry>,
    @SerialName("usd_krw_reference")
    val usdKrwReference: TopicSourceEntry? = null,
    @SerialName("usd_krw_futures")
    val usdKrwFutures: TopicSourceEntry? = null
) {
    val allEntries: List<TopicSourceEntry>
        get() = buildList {
            addAll(usdtKrw)
            addAll(usdKrwBanks)
            usdKrwReference?.let(::add)
            usdKrwFutures?.let(::add)
        }
}

@Serializable
data class KrxTopicMessage(
    val type: String,
    val version: Int,
    val topic: String,
    val data: KrxTopicData
)

@Serializable
data class KrxTopicData(
    @SerialName("usd_krw_futures")
    val usdKrwFutures: TopicSourceEntry? = null
) {
    val allEntries: List<TopicSourceEntry>
        get() = listOfNotNull(usdKrwFutures)
}

@Serializable
data class FxTopicMessage(
    val type: String,
    val version: Int,
    val topic: String,
    val data: FxTopicData
)

@Serializable
data class FxTopicData(
    val banks: List<TopicSourceEntry>,
    val reference: TopicSourceEntry? = null
) {
    val allEntries: List<TopicSourceEntry>
        get() = banks + listOfNotNull(reference)
}
