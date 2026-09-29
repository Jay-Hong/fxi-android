package com.jay.fxi.budget

import java.io.IOException
import java.util.Collections
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The cold start budget gates' shared recorder and rule (S2b/rule_consensus_codex.r1.md): per zone, nginx's predicted excess never
 * above 10 (half of `burst=20`); `/api/` in flight at once never above 10; nothing the model would refuse; no 429. A prediction
 * from send starts, never a measurement through nginx.
 */
internal class SendRecorder(private val nowMillis: () -> Long) {

    /** One physical exchange. [end] is when the response headers arrived — an approximation of when it stopped counting. */
    class Exchange(val start: Long, val path: String, private val query: Map<String, String?>, val zone: String) {
        @Volatile var end: Long? = null
        @Volatile var status: Int? = null
        fun param(name: String): String? = query[name]
        /** The same key [MockWebServer] requests are described by in these gates. */
        val key: String get() = describe(path, query)
    }

    private val recorded: MutableList<Exchange> = Collections.synchronizedList(mutableListOf())

    /** On REST, the client's last network interceptor. On a WebSocket client, an application interceptor: OkHttp runs no network interceptor there. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        val exchange = Exchange(
            nowMillis(), path, url.queryParameterNames.associateWith { url.queryParameter(it) }, if (path == "/ws") "ws" else "api"
        )
        recorded += exchange
        try {
            chain.proceed(request).also { response ->
                exchange.status = response.code
                exchange.end = nowMillis()
            }
        } catch (failed: IOException) {
            exchange.end = nowMillis()
            throw failed
        }
    }

    fun all(): List<Exchange> = synchronized(recorded) { recorded.toList() }.sortedBy { it.start }

    fun timeline(): String = all().joinToString { "${it.start}ms ${it.zone} ${it.key} -> ${it.status}" }

    companion object {
        fun describe(path: String, query: Map<String, String?>): String =
            path + if (query.isEmpty()) "" else query.toSortedMap().entries.joinToString("&", "?") { "${it.key}=${it.value}" }
    }
}

internal object ColdStartBudget {
    const val MAX_EXCESS_MILLI = 10_000L
    const val MAX_API_IN_FLIGHT = 10

    data class Verdict(val apiExcessMilli: Long, val wsExcessMilli: Long, val apiInFlight: Int, val refused: List<Int>, val answered429: Int)

    fun judge(all: List<SendRecorder.Exchange>): Verdict {
        assertTrue("an exchange never ended", all.all { it.end != null })
        val api = all.filter { it.zone == "api" }.sortedBy { it.start }
        val ws = all.filter { it.zone == "ws" }.sortedBy { it.start }
        val apiVerdict = NginxLimitModel.limitReq(NginxLimitModel.API, api.map { it.start })
        val wsVerdict = NginxLimitModel.limitReq(NginxLimitModel.WS_HANDSHAKE, ws.map { it.start })
        return Verdict(
            apiVerdict.maxExcessMilli,
            wsVerdict.maxExcessMilli,
            NginxLimitModel.maxConcurrent(api.map { it.start until maxOf(it.start + 1, it.end!!) }),
            apiVerdict.rejected + wsVerdict.rejected,
            all.count { it.status == 429 }
        )
    }

    fun assertWithin(verdict: Verdict) {
        assertTrue("api excess ${verdict.apiExcessMilli} > $MAX_EXCESS_MILLI", verdict.apiExcessMilli <= MAX_EXCESS_MILLI)
        assertTrue("ws excess ${verdict.wsExcessMilli} > $MAX_EXCESS_MILLI", verdict.wsExcessMilli <= MAX_EXCESS_MILLI)
        assertEquals("the model refused a send", emptyList<Int>(), verdict.refused)
        assertTrue("api in flight ${verdict.apiInFlight} > $MAX_API_IN_FLIGHT", verdict.apiInFlight <= MAX_API_IN_FLIGHT)
        assertEquals("a 429 was answered", 0, verdict.answered429)
    }
}
