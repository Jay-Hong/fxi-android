package com.jay.fxi.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * What one REST bootstrap attempt ended as.
 *
 * Total on purpose: every ending a caller can act on has a case here, so the caller branches rather
 * than catching. Two things still leave by exception, and both mean the attempt stopped belonging
 * to anybody — the signed-in identity moved (`AuthIdentityChangedException`), or the caller's own
 * scope was cancelled. Neither is an answer about the topic.
 *
 * **The topic verdicts are read from the body's `error` string, never from the status alone.** This
 * one route answers 404 with three different meanings and 503 with three more, so a status-keyed
 * reading would file a subscription still being decided as a topic outage. The shared classifier
 * cannot help: its known-404 branch requires `error == null` plus an exact `detail` literal, which
 * is the *inverse* of this endpoint's shape, and widening it would change what unrelated endpoints
 * see — `free-tab-404` carries an `error` key too. So nothing here adds an
 * [AuthenticatedFailureKind]; at this endpoint the shared kinds stay deliberately coarse.
 */
sealed interface TopicSnapshotOutcome {

    /** A snapshot, and one for the topic that was actually asked for. */
    data class Delivered(val frame: DecodedTopicFrame) : TopicSnapshotOutcome

    /**
     * 404 `topics_disabled` — the whole endpoint is dormant.
     *
     * Endpoint-wide, and answered **before authentication**, so it says nothing about this user or
     * this topic. Filing it as a per-topic verdict would let a pre-release server look like a
     * revoked grant.
     */
    data object Dormant : TopicSnapshotOutcome

    /**
     * 404 `unknown_topic` — the server will not serve this topic to this user.
     *
     * Deliberately indistinguishable from a topic that does not exist: that is how a capability the
     * user lacks is hidden rather than advertised.
     *
     * [supportedTopics] is re-read from the raw body because the shared transport parses only
     * `error` and `detail`, and widening that parse would touch every endpoint. Empty when the body
     * carried no usable list — never a reason to throw.
     */
    data class Unsupported(val supportedTopics: List<String>) : TopicSnapshotOutcome

    /** 404 `topic_unavailable` — a supported topic with nothing to serve at the moment. */
    data object Degraded : TopicSnapshotOutcome

    /**
     * 503 `temporarily_unavailable` — and it carries **no retry input at all**.
     *
     * Neither `Retry-After` nor `retry_after_seconds`, on purpose: the WS twin does send
     * `retry_after_seconds`, and copying that floor onto this one would answer a failover measured
     * in minutes with a five-second storm. Anything that invents a delay here invents it alone.
     */
    data object TemporarilyUnavailable : TopicSnapshotOutcome

    /**
     * Every other HTTP answer, with the evidence intact.
     *
     * 401, a premium 403, a premium-pending 503 (`Retry-After: 5`), an auth-infrastructure 503, a
     * 500, and the proxy's 429 HTML page all land here — they are answers about the caller or the
     * transport, not verdicts about the topic. Read `failure.statusCode`, `failure.error` and
     * `failure.retryAfter`; do not give them a topic meaning the server did not send.
     */
    data class Refused(val failure: AuthenticatedHttpFailure) : TopicSnapshotOutcome

    /**
     * A 2xx whose body is not a usable snapshot for the topic that was asked for.
     *
     * On the socket an unreadable frame is survivable — there is a next one — and an unrecognised
     * frame is something to ignore. Neither holds here: this response *is* the answer, so a
     * subscription acknowledgement, a pong, the legacy `rates` payload, an unknown envelope, and a
     * snapshot for a different topic are all failures rather than quiet successes.
     */
    data class Malformed(val reason: String) : TopicSnapshotOutcome

    /** The attempt's own budget ran out. Not a withdrawal by the caller. */
    data object TimedOut : TopicSnapshotOutcome

    /** The attempt never reached an answer at all. */
    data class Unreachable(val cause: Throwable) : TopicSnapshotOutcome
}

/** Topic verdicts the server states in the body. Nothing else in the matrix is topic-scoped. */
private const val TOPICS_DISABLED = "topics_disabled"
private const val UNKNOWN_TOPIC = "unknown_topic"
private const val TOPIC_UNAVAILABLE = "topic_unavailable"
private const val TEMPORARILY_UNAVAILABLE = "temporarily_unavailable"

/**
 * Status **and** `error` together, so a code the server moves to another status does not follow it.
 *
 * Everything unmatched is [TopicSnapshotOutcome.Refused] rather than a guess. That is what keeps a
 * premium-pending 503 — same status as an outage, but `error == null` and a `Retry-After` the
 * server does mean — from being spent as a topic verdict.
 */
internal fun AuthenticatedHttpFailure.toTopicSnapshotOutcome(): TopicSnapshotOutcome = when {
    statusCode == 404 && error == TOPICS_DISABLED -> TopicSnapshotOutcome.Dormant
    statusCode == 404 && error == UNKNOWN_TOPIC ->
        TopicSnapshotOutcome.Unsupported(readSupportedTopics())
    statusCode == 404 && error == TOPIC_UNAVAILABLE -> TopicSnapshotOutcome.Degraded
    statusCode == 503 && error == TEMPORARILY_UNAVAILABLE ->
        TopicSnapshotOutcome.TemporarilyUnavailable
    else -> TopicSnapshotOutcome.Refused(this)
}

/**
 * The list the server echoes back with `unknown_topic`, or empty.
 *
 * Re-parsed from the preserved bytes rather than read off the failure: only `error` and `detail`
 * are parsed there. Never throws — a body that is not the shape this expects is simply no list.
 */
private fun AuthenticatedHttpFailure.readSupportedTopics(): List<String> = runCatching {
    val parsed = Json.parseToJsonElement(rawBodyText) as? JsonObject ?: return emptyList()
    parsed[SUPPORTED_TOPICS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
}.getOrElse { emptyList() }

private const val SUPPORTED_TOPICS = "supported_topics"
