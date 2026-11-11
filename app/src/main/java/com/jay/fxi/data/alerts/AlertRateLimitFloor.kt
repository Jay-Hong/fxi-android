package com.jay.fxi.data.alerts

import android.os.SystemClock
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import javax.inject.Inject
import javax.inject.Singleton

/** Evidence for one completed attempt; a non-empty exchange list replaces the fallback pair. */
internal class AlertRateLimitEvidence private constructor(val retryAfterSeconds: List<Double>) {
    fun floorSeconds(): Double? = retryAfterSeconds.maxOrNull()

    companion object {
        fun of(
            exchanges: List<HttpExchangeEvidence>,
            statusCode: Int?,
            retryAfter: String?
        ): AlertRateLimitEvidence {
            val seconds = if (exchanges.isNotEmpty()) {
                exchanges.mapNotNull { candidate(it.statusCode, it.retryAfter) }
            } else {
                listOfNotNull(candidate(statusCode, retryAfter))
            }
            return AlertRateLimitEvidence(seconds)
        }

        fun of(failure: AuthenticatedHttpFailure): AlertRateLimitEvidence =
            of(emptyList(), failure.statusCode, failure.retryAfter)

        fun of(response: AlertRateLimitResponseEvidence): AlertRateLimitEvidence =
            of(emptyList(), response.statusCode, response.retryAfter)

        private fun candidate(statusCode: Int?, header: String?): Double? =
            if (statusCode != null && AlertRetryPolicy.isRetryable(statusCode)) AlertRetryAfter.parseSeconds(header) else null
    }
}

/** Main-confined process memory. Account and owner transitions cannot shorten an endpoint's floor. */
@Singleton
internal class AlertRateLimitFloor internal constructor(private val nowMillis: () -> Long) {
    @Inject constructor() : this(SystemClock::elapsedRealtime)

    private val deadlines = mutableMapOf<AuthenticatedEndpoint, Long>()

    fun notBefore(endpoint: AuthenticatedEndpoint): Long = deadlines[endpoint] ?: Long.MIN_VALUE

    fun record(endpoint: AuthenticatedEndpoint, evidence: AlertRateLimitEvidence) {
        val seconds = evidence.floorSeconds() ?: return
        val delay = AlertRetryPolicy.secondsToMillis(seconds)
        val now = nowMillis()
        val deadline = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        deadlines[endpoint] = maxOf(notBefore(endpoint), deadline)
    }

    fun remainingMillis(endpoint: AuthenticatedEndpoint): Long {
        val deadline = deadlines[endpoint] ?: return 0
        val now = nowMillis()
        if (deadline <= now) return 0
        return if (now < 0 && deadline > Long.MAX_VALUE + now) Long.MAX_VALUE else deadline - now
    }
}
