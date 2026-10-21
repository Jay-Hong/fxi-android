package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException

/** Pure decisions for one topic's bootstrap attempts; all changing inputs belong to the caller. */
internal object TopicBootstrapRetryPolicy {
    /** Total attempts, including the first. */
    const val MAX_ATTEMPTS: Int = 3

    /** Wait after each failure, reusing the last step beyond the table. */
    val BACKOFFS: List<Duration> = listOf(500.milliseconds, 1_500.milliseconds)

    /** Symmetric spread around each step. */
    const val JITTER_FRACTION: Double = 0.2

    val ATTEMPT_BUDGET_MILLIS: Long =
        TopicSnapshotBootstrapService.ATTEMPT_BUDGET.inWholeMilliseconds

    fun delayFor(failedAttempt: Int, jitterUnit: Double): Duration? {
        if (failedAttempt <= 0 || !validJitter(jitterUnit)) return null

        val base = BACKOFFS[minOf(failedAttempt - 1, BACKOFFS.lastIndex)]
            .inWholeNanoseconds.toDouble()
        val signed = jitterUnit * 2.0 - 1.0
        val spread = base * (1.0 + JITTER_FRACTION * signed)
        return floor(spread).toLong().nanoseconds
    }

    fun classify(outcome: TopicSnapshotOutcome, admissionOpen: Boolean): TopicBootstrapVerdict {
        if (!admissionOpen) {
            return TopicBootstrapVerdict.Terminal(TopicBootstrapStop.ADMISSION_CLOSED)
        }

        return when (outcome) {
            is TopicSnapshotOutcome.Delivered -> TopicBootstrapVerdict.Deliver
            TopicSnapshotOutcome.Dormant ->
                TopicBootstrapVerdict.Terminal(TopicBootstrapStop.TOPICS_DISABLED)
            is TopicSnapshotOutcome.Unsupported ->
                TopicBootstrapVerdict.Terminal(TopicBootstrapStop.UNKNOWN_TOPIC)
            TopicSnapshotOutcome.Degraded ->
                TopicBootstrapVerdict.Terminal(TopicBootstrapStop.TOPIC_UNAVAILABLE)
            TopicSnapshotOutcome.TemporarilyUnavailable ->
                TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.TEMPORARILY_UNAVAILABLE)
            is TopicSnapshotOutcome.Refused -> when (outcome.failure.statusCode) {
                401 -> TopicBootstrapVerdict.Terminal(TopicBootstrapStop.AUTHENTICATION)
                403 -> TopicBootstrapVerdict.Terminal(TopicBootstrapStop.FORBIDDEN)
                404 -> TopicBootstrapVerdict.Terminal(TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED)
                408 -> TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.HTTP_408)
                429 -> TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.HTTP_429)
                in 500..599 -> TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.HTTP_5XX)
                else -> TopicBootstrapVerdict.Terminal(TopicBootstrapStop.HTTP_NOT_RETRYABLE)
            }
            is TopicSnapshotOutcome.Malformed ->
                TopicBootstrapVerdict.Terminal(TopicBootstrapStop.MALFORMED)
            TopicSnapshotOutcome.TimedOut ->
                TopicBootstrapVerdict.Terminal(TopicBootstrapStop.TIMED_OUT)
            is TopicSnapshotOutcome.Unreachable -> when {
                containsCancellation(outcome.cause) ->
                    TopicBootstrapVerdict.Terminal(TopicBootstrapStop.CANCELLED)
                outcome.cause is AuthUnavailableException ->
                    TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.TOKEN)
                else -> TopicBootstrapVerdict.Retryable(TopicBootstrapRetryKind.NETWORK)
            }
        }
    }

    fun start(owner: AuthIdentityFence, startRevision: Long, nowMillis: Long): TopicBootstrapLadder =
        TopicBootstrapLadder(owner, startRevision, attempt = 0, issuedAtMillis = null, dueMillis = nowMillis)

    fun beforeAttempt(
        ladder: TopicBootstrapLadder,
        nowMillis: Long,
        liveOwner: AuthIdentityFence?,
        revision: Long,
        floorMillis: Long
    ): TopicBootstrapGo {
        val issuedAt = ladder.issuedAtMillis
        if (
            ladder.attempt !in 0 until MAX_ATTEMPTS ||
            (ladder.attempt == 0) != (issuedAt == null) ||
            nowMillis < 0L ||
            (issuedAt != null && (issuedAt < 0L || issuedAt > nowMillis)) ||
            revision < ladder.startRevision
        ) {
            return TopicBootstrapGo.Stop(TopicBootstrapStop.INVALID_INPUT)
        }
        if (liveOwner == null || liveOwner != ladder.owner) {
            return TopicBootstrapGo.Stop(TopicBootstrapStop.IDENTITY_MOVED)
        }
        if (revision > ladder.startRevision) {
            return TopicBootstrapGo.Stop(TopicBootstrapStop.SNAPSHOT_ALREADY_RECEIVED)
        }

        val effectiveDue = maxOf(ladder.dueMillis, floorMillis)
        if (nowMillis < effectiveDue) return TopicBootstrapGo.NotYet(effectiveDue)

        return TopicBootstrapGo.Issue(
            ladder.copy(attempt = ladder.attempt + 1, issuedAtMillis = nowMillis, dueMillis = nowMillis)
        )
    }

    fun afterAttempt(
        ladder: TopicBootstrapLadder,
        outcome: TopicSnapshotOutcome,
        nowMillis: Long,
        liveOwner: AuthIdentityFence?,
        revision: Long,
        floorMillis: Long,
        admissionOpen: Boolean,
        jitterUnit: Double
    ): TopicBootstrapNext {
        val issuedAt = ladder.issuedAtMillis
        if (
            ladder.attempt !in 1..MAX_ATTEMPTS ||
            issuedAt == null || issuedAt < 0L || nowMillis < issuedAt ||
            revision < ladder.startRevision || !validJitter(jitterUnit)
        ) {
            return TopicBootstrapNext.Stop(TopicBootstrapStop.INVALID_INPUT)
        }
        if (nowMillis - issuedAt > ATTEMPT_BUDGET_MILLIS) {
            return TopicBootstrapNext.Stop(TopicBootstrapStop.BUDGET_EXCEEDED)
        }
        if (liveOwner == null || liveOwner != ladder.owner) {
            return TopicBootstrapNext.Stop(TopicBootstrapStop.IDENTITY_MOVED)
        }

        when (val verdict = classify(outcome, admissionOpen)) {
            TopicBootstrapVerdict.Deliver -> return TopicBootstrapNext.Apply
            is TopicBootstrapVerdict.Terminal -> return TopicBootstrapNext.Stop(verdict.reason)
            is TopicBootstrapVerdict.Retryable -> Unit
        }
        if (ladder.attempt >= MAX_ATTEMPTS) {
            return TopicBootstrapNext.Stop(TopicBootstrapStop.ATTEMPTS_EXHAUSTED)
        }
        if (revision > ladder.startRevision) {
            return TopicBootstrapNext.Stop(TopicBootstrapStop.SNAPSHOT_ALREADY_RECEIVED)
        }

        val wait = delayFor(ladder.attempt, jitterUnit)
            ?: return TopicBootstrapNext.Stop(TopicBootstrapStop.INVALID_INPUT)
        val due = maxOf(saturatingAdd(nowMillis, ceilMillis(wait)), floorMillis)
        return TopicBootstrapNext.Retry(ladder.copy(dueMillis = due))
    }

    private fun validJitter(jitterUnit: Double): Boolean =
        jitterUnit.isFinite() && jitterUnit >= 0.0 && jitterUnit <= 1.0

    /** Identity comparison also terminates cause cycles whose nodes override equality. */
    private tailrec fun containsCancellation(cause: Throwable?, seen: List<Throwable> = emptyList()): Boolean =
        when {
            cause == null -> false
            cause is CancellationException -> true
            seen.any { it === cause } -> false
            else -> containsCancellation(cause.cause, seen + cause)
        }

    /** The bounded, positive wait is rounded upward onto the millisecond timeline. */
    private fun ceilMillis(wait: Duration): Long {
        val nanos = wait.inWholeNanoseconds
        return nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L
    }

    /** Both inputs are nonnegative; overflowing due times remain at the timeline's end. */
    private fun saturatingAdd(nowMillis: Long, waitMillis: Long): Long =
        if (nowMillis > Long.MAX_VALUE - waitMillis) Long.MAX_VALUE else nowMillis + waitMillis
}

internal data class TopicBootstrapLadder(
    val owner: AuthIdentityFence,
    val startRevision: Long,
    val attempt: Int,
    val issuedAtMillis: Long?,
    val dueMillis: Long
)

internal sealed interface TopicBootstrapVerdict {
    data object Deliver : TopicBootstrapVerdict
    data class Retryable(val kind: TopicBootstrapRetryKind) : TopicBootstrapVerdict
    data class Terminal(val reason: TopicBootstrapStop) : TopicBootstrapVerdict
}

internal enum class TopicBootstrapRetryKind {
    NETWORK, TOKEN, HTTP_408, HTTP_429, HTTP_5XX, TEMPORARILY_UNAVAILABLE
}

internal enum class TopicBootstrapStop {
    INVALID_INPUT, BUDGET_EXCEEDED, TIMED_OUT, IDENTITY_MOVED, ADMISSION_CLOSED, CANCELLED,
    AUTHENTICATION, FORBIDDEN, TOPICS_DISABLED, UNKNOWN_TOPIC, TOPIC_UNAVAILABLE,
    NOT_FOUND_UNRECOGNIZED, HTTP_NOT_RETRYABLE, MALFORMED, ATTEMPTS_EXHAUSTED, SNAPSHOT_ALREADY_RECEIVED
}

internal sealed interface TopicBootstrapGo {
    data class Issue(val ladder: TopicBootstrapLadder) : TopicBootstrapGo
    data class NotYet(val dueMillis: Long) : TopicBootstrapGo
    data class Stop(val reason: TopicBootstrapStop) : TopicBootstrapGo
}

internal sealed interface TopicBootstrapNext {
    /** A policy decision only; the caller still owns checks immediately before application. */
    data object Apply : TopicBootstrapNext
    data class Retry(val ladder: TopicBootstrapLadder) : TopicBootstrapNext
    data class Stop(val reason: TopicBootstrapStop) : TopicBootstrapNext
}
