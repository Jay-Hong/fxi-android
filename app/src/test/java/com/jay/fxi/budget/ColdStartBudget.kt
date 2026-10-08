package com.jay.fxi.budget

import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The cold start budget gates' shared recorder and rule (S2b/rule_consensus_codex.r1.md): per zone, nginx's predicted excess never
 * above 10 (half of `burst=20`); `/api/` in flight at once never above 10; nothing the model would refuse; no 429. A prediction
 * from send starts, never a measurement through nginx.
 */
internal class SendRecorder(private val nowMillis: () -> Long) {

    /**
     * One physical exchange. [end] is when the response headers arrived — an approximation of when it stopped counting.
     * [bodyEnd] (S4 INT-b) is when the client saw a REST response body end: the first of a normal EOF, a normal return from the
     * underlying close, or a failure reading or closing it; a failure before a response, an absent or empty body, and a WebSocket
     * upgrade end at [end]. Entering close or cancelling a call does not end it.
     */
    class Exchange(val start: Long, val path: String, private val query: Map<String, String?>, val zone: String) {
        @Volatile var end: Long? = null
        @Volatile var status: Int? = null
        @Volatile var bodyEnd: Long? = null
            private set
        private val bodyEnded = AtomicBoolean(false)
        internal fun endBody(at: Long) {
            if (bodyEnded.compareAndSet(false, true)) bodyEnd = at
        }
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
        val response = try {
            chain.proceed(request)
        } catch (failed: IOException) {
            val at = nowMillis()
            exchange.end = at
            exchange.endBody(at)
            throw failed
        }
        exchange.status = response.code
        val at = nowMillis()
        exchange.end = at
        val body = response.body
        if (exchange.zone != "api" || body == null || body.contentLength() == 0L) {
            exchange.endBody(at)
            response
        } else {
            response.newBuilder().body(TrackedBody(body) { exchange.endBody(nowMillis()) }).build()
        }
    }

    fun all(): List<Exchange> = synchronized(recorded) { recorded.toList() }.sortedBy { it.start }

    fun timeline(): String = all().joinToString { "${it.start}ms ${it.zone} ${it.key} -> ${it.status}" + (it.bodyEnd?.let { b -> " body@${b}ms" } ?: "") }

    /** Passes every byte and failure through unchanged; reports the first end it sees. */
    private class TrackedBody(private val delegate: ResponseBody, private val ended: () -> Unit) : ResponseBody() {
        private val tracked: BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = try {
                    super.read(sink, byteCount)
                } catch (failed: IOException) {
                    ended()
                    throw failed
                }
                if (read == -1L) ended()
                return read
            }

            override fun close() {
                try {
                    super.close()
                } catch (failed: IOException) {
                    ended()
                    throw failed
                }
                ended()
            }
        }.buffer()

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = tracked
    }

    companion object {
        fun describe(path: String, query: Map<String, String?>): String =
            path + if (query.isEmpty()) "" else query.toSortedMap().entries.joinToString("&", "?") { "${it.key}=${it.value}" }
    }
}

internal object ColdStartBudget {
    const val MAX_EXCESS_MILLI = 10_000L
    const val MAX_API_IN_FLIGHT = 10

    /**
     * [apiInFlight] is the gated value: header-based by default, body-based when judged with `bodyInFlight` (S4 INT-b), which
     * also reports both in [apiInFlightHeaders] and [apiInFlightBodies].
     */
    data class Verdict(
        val apiExcessMilli: Long,
        val wsExcessMilli: Long,
        val apiInFlight: Int,
        val refused: List<Int>,
        val answered429: Int,
        val apiInFlightHeaders: Int = apiInFlight,
        val apiInFlightBodies: Int? = null
    )

    /**
     * Without [bodyInFlight], exactly the original rule: every exchange ended, in flight from start to headers. With it (S4 INT-b),
     * every REST body must also have ended, and in flight runs from start to the body's end.
     */
    fun judge(all: List<SendRecorder.Exchange>, bodyInFlight: Boolean = false): Verdict {
        assertTrue("an exchange never ended", all.all { it.end != null })
        val api = all.filter { it.zone == "api" }.sortedBy { it.start }
        val ws = all.filter { it.zone == "ws" }.sortedBy { it.start }
        val apiVerdict = NginxLimitModel.limitReq(NginxLimitModel.API, api.map { it.start })
        val wsVerdict = NginxLimitModel.limitReq(NginxLimitModel.WS_HANDSHAKE, ws.map { it.start })
        val headers = NginxLimitModel.maxConcurrent(api.map { it.start until maxOf(it.start + 1, it.end!!) })
        val bodies = if (bodyInFlight) {
            assertTrue("a REST body never ended", api.all { it.bodyEnd != null })
            NginxLimitModel.maxConcurrent(api.map { it.start until maxOf(it.start + 1, it.bodyEnd!!) })
        } else null
        return Verdict(
            apiVerdict.maxExcessMilli,
            wsVerdict.maxExcessMilli,
            bodies ?: headers,
            apiVerdict.rejected + wsVerdict.rejected,
            all.count { it.status == 429 },
            headers,
            bodies
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
