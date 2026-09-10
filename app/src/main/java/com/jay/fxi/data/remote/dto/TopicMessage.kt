package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.RateSanity
import com.jay.fxi.domain.model.TopicLeasePolicy
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frame types this client understands.
 *
 * `update` is deliberately absent (D9). The server is snapshot-only, and a declared `update` would
 * be a second data path nobody sends and nothing tests — an `update` frame now falls through to
 * the decoder's unsupported branch and is ignored, which is what the decision asks for.
 */
object TopicMessageType {
    const val SNAPSHOT = "snapshot"
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
            // `isCountable` rather than `>= 0`: a duration past the ceiling saturates when it is
            // converted to nanoseconds, so every larger lease reads as the same number and the one
            // the server sent is gone. Out of contract, like a negative one.
            leaseId.isNullOrEmpty() || duration == null || !TopicLeasePolicy.isCountable(duration) -> {
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

/**
 * The wire entry as the domain sees it — one price, one time — or nothing at all.
 *
 * Two jobs happen on this line, and I5 (`ANDROID_V2_PLAN.md`) is why they happen *here*.
 *
 * `rate_changed_at ?? timestamp` is decided once, so everything past this point holds a single
 * instant and no consumer has to remember which of the two the server meant.
 *
 * And the value is checked, because strict wire decoding does not check values: `-1` is a valid
 * JSON number and a valid `Double`. Past this line it would be a price — one that merges over a
 * real quote as soon as its clock is newer. An implausible entry becomes `null` and the caller
 * drops it rather than the frame: a topic snapshot is a list of independent quotes, and one bad
 * source is not a reason to throw away the others.
 */
fun TopicSourceEntry.toQuote(): TopicQuote? =
    if (RateSanity.isPlausible(rate)) TopicQuote(source, asset, rate, mergeAt) else null

/** The index has no `rate_changed_at`; its `timestamp` is already the moment it changed. */
fun DxySpotEntry.toDollarIndex(): TopicDollarIndex? =
    if (RateSanity.isPlausible(rate)) TopicDollarIndex(rate, timestamp, source) else null

@Serializable
data class TetherTopicMessage(
    val type: String,
    val version: Int,
    val topic: String,
    val data: TetherTopicData
)

/**
 * The tether topic's groups — **futures is not one of them** (D8).
 *
 * `usd_krw_futures` used to ride along here and ADR-038 D2 moved KRX to a topic of its own. The key
 * may still arrive from an older server, and declaring it is what would let it through: a field
 * that is not declared is absorbed by `ignoreUnknownKeys` (`NetworkModule.kt`), while a declared
 * one is parsed and, as it was, folded into [allEntries] — KRX in the tether domain, on a build
 * that has no entitlement check for it yet. Removing the declaration is the enforcement.
 */
@Serializable
data class TetherTopicData(
    @SerialName("usdt_krw")
    val usdtKrw: List<TopicSourceEntry>,
    @SerialName("usd_krw_banks")
    val usdKrwBanks: List<TopicSourceEntry>,
    @SerialName("usd_krw_reference")
    val usdKrwReference: TopicSourceEntry? = null
) {
    val allEntries: List<TopicSourceEntry>
        get() = buildList {
            addAll(usdtKrw)
            addAll(usdKrwBanks)
            usdKrwReference?.let(::add)
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

/**
 * The dollar index, which is not a currency pair.
 *
 * Every other topic carries `(source, asset)` entries; DXY has no asset, and the identifier is the
 * topic itself. `source` is whichever supplier answered — investing, cnbc or yahoo — so it says
 * where the number came from rather than which instrument it is, and it must not be keyed like the
 * others. The shape matches the legacy `rates.data.indices.dxy` block, but the type is its own: the
 * legacy DTO goes with the legacy consumer it belongs to.
 */
@Serializable
data class DxySpotEntry(
    val rate: Double,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val source: String
)

@Serializable
data class DxyTopicMessage(
    val type: String,
    val version: Int,
    val topic: String,
    val data: DxyTopicData
)

/**
 * Required, not optional.
 *
 * The publisher refuses to build a payload it cannot normalise (`app/dxy_topic_publisher.py`
 * raises rather than emitting an empty group), so a `dxy:spot` snapshot without the index is
 * malformed rather than empty. Declaring it nullable would turn that into a silently absent
 * reading; leaving it required makes the decoder fail and the transport isolate the frame, which
 * is what D11 asks for.
 */
@Serializable
data class DxyTopicData(
    val dxy: DxySpotEntry
)

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
