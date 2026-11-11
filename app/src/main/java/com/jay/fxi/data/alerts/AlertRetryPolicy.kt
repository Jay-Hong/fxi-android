package com.jay.fxi.data.alerts

import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import kotlin.math.ceil

internal sealed interface AlertFetchFailure {
    data class Http(val statusCode: Int, val retryAfterSeconds: Double?) : AlertFetchFailure
    data class Repository(val kind: AlertRepositoryFailureKind) : AlertFetchFailure
    data object Withdrawn : AlertFetchFailure
}

internal sealed interface AlertRetryDecision {
    data object Silent : AlertRetryDecision
    data class Terminal(val failure: AlertLoadFailure) : AlertRetryDecision
    data class Retry(val delayMillis: Long, val byRetryAfter: Boolean) : AlertRetryDecision
}

internal data class AlertRetryPolicy(
    val maxRetries: Int = 5,
    val tokenMaxRetries: Int = 2,
    val baseDelayMillis: Long = 2_000,
    val maxBackoffMillis: Long = 60_000
) {
    /** The first failure is attempt zero; transport credential replay is part of that same attempt. */
    fun decide(failure: AlertFetchFailure, attempt: Int, jitter: () -> Double): AlertRetryDecision =
        when (failure) {
            AlertFetchFailure.Withdrawn -> AlertRetryDecision.Silent
            is AlertFetchFailure.Http -> when {
                failure.statusCode == 401 -> AlertRetryDecision.Terminal(AlertLoadFailure.AuthRequired)
                failure.statusCode == 403 -> AlertRetryDecision.Terminal(AlertLoadFailure.Forbidden)
                isRetryable(failure.statusCode) && attempt < maxRetries -> {
                    val seconds = failure.retryAfterSeconds
                    if (seconds != null && seconds.isFinite() && seconds > 0.0) {
                        AlertRetryDecision.Retry(secondsToMillis(seconds), byRetryAfter = true)
                    } else {
                        AlertRetryDecision.Retry(backoffMillis(attempt, jitter), byRetryAfter = false)
                    }
                }
                else -> AlertRetryDecision.Terminal(
                    AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, failure.statusCode)
                )
            }
            is AlertFetchFailure.Repository -> when (failure.kind) {
                AlertRepositoryFailureKind.AUTH_UNAVAILABLE ->
                    if (attempt < tokenMaxRetries) {
                        AlertRetryDecision.Retry(backoffMillis(attempt, jitter), byRetryAfter = false)
                    } else {
                        AlertRetryDecision.Terminal(AlertLoadFailure.AuthRequired)
                    }
                AlertRepositoryFailureKind.NETWORK ->
                    if (attempt < maxRetries) {
                        AlertRetryDecision.Retry(backoffMillis(attempt, jitter), byRetryAfter = false)
                    } else {
                        AlertRetryDecision.Terminal(AlertLoadFailure.Failed(failure.kind, null))
                    }
                else -> AlertRetryDecision.Terminal(AlertLoadFailure.Failed(failure.kind, null))
            }
        }

    fun backoffMillis(attempt: Int, jitter: () -> Double): Long {
        // Stop doubling at the cap, including for an arbitrarily large attempt.
        var base = baseDelayMillis.coerceIn(0, maxBackoffMillis)
        var remaining = attempt.coerceAtLeast(0)
        while (remaining > 0 && base < maxBackoffMillis && base > 0) {
            base = if (base > maxBackoffMillis / 2) maxBackoffMillis else base * 2
            remaining--
        }
        val delta = jitter().takeIf { it.isFinite() }?.coerceIn(-0.2, 0.2) ?: 0.0
        return (base.toDouble() + base.toDouble() * delta)
            .coerceIn(1_000.0, maxBackoffMillis.toDouble()).toLong()
    }

    companion object {
        fun isRetryable(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode in 500..599

        fun secondsToMillis(seconds: Double): Long {
            if (seconds.isNaN() || seconds <= 0.0) return 0
            val millis = ceil(seconds * 1_000.0)
            return if (millis >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else millis.toLong().coerceAtLeast(1)
        }
    }
}

internal object AlertRetryAfter {
    private val grammar = Regex("[0-9]+(?:\\.[0-9]+)?")

    fun parseSeconds(value: String?): Double? {
        val text = value?.trim() ?: return null
        if (!grammar.matches(text)) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }
}
