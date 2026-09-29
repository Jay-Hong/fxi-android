package com.jay.fxi.budget

/**
 * ②b-1: predicts, from a timeline of send starts, what the server's nginx `limit_req … nodelay` and `limit_conn` would do
 * (exchange-rate `nginx/conf.d/default.conf:19-26, 89, 116-119`). A send start stands in for nginx's arrival time, so this is a
 * prediction, never a substitute for a request that went through nginx.
 */
internal data class LimitReqZone(val ratePerSecond: Int, val burst: Int)

/** [maxExcessMilli] is nginx's excess in thousandths of a request, the unit its own arithmetic uses; [rejected] are send indices. */
internal data class LimitReqVerdict(val maxExcessMilli: Long, val rejected: List<Int>)

internal object NginxLimitModel {
    /** `/api/`: 3r/s burst=20 nodelay. */
    val API = LimitReqZone(ratePerSecond = 3, burst = 20)

    /** `/ws` handshakes: 10r/s burst=20 nodelay. */
    val WS_HANDSHAKE = LimitReqZone(ratePerSecond = 10, burst = 20)

    /** `/api/` concurrent connections per address. */
    const val API_CONNECTIONS = 20

    /** Sends in non-decreasing start order, in milliseconds; sends at the same instant are taken in list order. */
    fun limitReq(zone: LimitReqZone, sendStartsMillis: List<Long>): LimitReqVerdict {
        var excess = 0L
        var maxExcess = 0L
        var lastAccepted: Long? = null
        var previousInput: Long? = null
        val rejected = mutableListOf<Int>()
        val burstMilli = zone.burst.toLong() * 1_000L

        sendStartsMillis.forEachIndexed { index, start ->
            previousInput?.let { require(start >= it) { "Send starts must be non-decreasing" } }
            previousInput = start

            val candidate = lastAccepted?.let { last ->
                maxOf(0L, excess - zone.ratePerSecond.toLong() * (start - last) + 1_000L)
            } ?: 0L

            if (candidate > burstMilli) {
                rejected.add(index)
            } else {
                excess = candidate
                maxExcess = maxOf(maxExcess, candidate)
                lastAccepted = start
            }
        }

        return LimitReqVerdict(maxExcess, rejected)
    }

    /** The most requests in flight at once; each is `[start, end)`, so one ending as another starts do not overlap. */
    fun maxConcurrent(inFlight: List<LongRange>): Int {
        val intervals = inFlight.filterNot { it.isEmpty() }
        val starts = intervals.map { it.first }.sorted()
        val ends = intervals.map { it.last }.sorted()
        var active = 0
        var maxActive = 0
        var endIndex = 0

        for (start in starts) {
            while (endIndex < ends.size && ends[endIndex] < start) {
                active--
                endIndex++
            }
            active++
            maxActive = maxOf(maxActive, active)
        }

        return maxActive
    }
}
