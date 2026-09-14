package com.jay.fxi.data.remote

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.HttpExchangeEvidence
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response as OkHttpResponse
import okio.BufferedSink
import retrofit2.Response

/**
 * Retrofit request tag whose rendering never exposes the Firebase bearer token.
 *
 * [use] is carried through to the request by [AuthSnapshotInterceptor] for a read whose every send must still be admitted
 * by the access it serves (L-4e E2a); null for everything else.
 */
internal class AuthRequestTag(val snapshot: AuthSnapshot, val use: TopicUseTag? = null) {
    override fun toString(): String =
        "AuthRequestTag(uid=<redacted>, authGeneration=${snapshot.authGeneration}, token=<redacted>)"
}

/**
 * One send of a use-checked read, as the network interceptor sees it.
 *
 * Left on the request rather than removed with [AuthRequestTag], so a repeat OkHttp makes below the application interceptors
 * (`503 + Retry-After: 0`, a misdirected 421) still carries it: a tag is copied with the request.
 */
internal class TopicUseTag(val snapshot: AuthSnapshot, val send: Int, val context: TopicUseContext) {
    override fun toString(): String = "TopicUseTag(send=$send)"
}

/**
 * Shared by every send of one logical read: the use check, and every response those sends observed.
 *
 * Written from OkHttp's threads and read from the caller's coroutine, so everything goes through [lock]. Responses are
 * appended and never replaced, because one send can see several before it is refused.
 */
internal class TopicUseContext(private val useAdmitted: () -> Boolean) {
    private val lock = Any()
    private val observed = mutableListOf<HttpExchangeEvidence>()
    private val exchangesPerSend = mutableMapOf<Int, Int>()

    /** Reads the issuer's published access; side-effect free and safe on any thread. */
    fun admitted(): Boolean = useAdmitted()

    fun nextExchange(send: Int): Int = synchronized(lock) {
        val next = (exchangesPerSend[send] ?: 0) + 1
        exchangesPerSend[send] = next
        next
    }

    fun record(evidence: HttpExchangeEvidence) {
        synchronized(lock) { observed += evidence }
    }

    /**
     * Records a response the caller holds for [send], unless the network interceptor already recorded one for it.
     *
     * The interceptor sees every exchange; this is for a client built without it, so a refusal never loses the response
     * it follows. Never a second copy of the same response.
     */
    fun recordIfUnseen(send: Int, statusCode: Int, retryAfter: String?) {
        synchronized(lock) {
            if (observed.none { it.send == send }) {
                val exchange = (exchangesPerSend[send] ?: 0) + 1
                exchangesPerSend[send] = exchange
                observed += HttpExchangeEvidence(send, exchange, statusCode, retryAfter)
            }
        }
    }

    /** An immutable copy, oldest first. */
    fun observed(): List<HttpExchangeEvidence> = synchronized(lock) { observed.toList() }
}

/** The access a use-checked read serves no longer admits a send. Converted to [TopicUseWithheldException] on the caller's coroutine. */
internal class WithheldUseIOException(val exchanges: List<HttpExchangeEvidence>) : IOException(
    "Protected request's access use is no longer admitted"
)

/**
 * A use-checked read was refused a send because the access it serves no longer admits it (L-4e E2a).
 *
 * Nothing was applied. [exchanges] are the responses its sends had already observed, oldest first, for the retry-floor
 * owner: the refusal is the session's, a rate limit the server levied is not.
 */
internal class TopicUseWithheldException(val exchanges: List<HttpExchangeEvidence>) : CancellationException(
    "Access withheld while using a topic grant"
)

/** Protected clients must never send a request whose credential owner was not explicit. */
internal class MissingAuthRequestTagException : IOException(
    "Protected request is missing an explicit auth snapshot"
)

/**
 * OkHttp only delivers [IOException] failures through its async callback contract.
 *
 * The transport converts this marker back to [AuthIdentityChangedException] on the caller's
 * coroutine. Throwing CancellationException directly from an interceptor would escape the
 * dispatcher thread instead of behaving like a normal Retrofit call cancellation.
 */
internal class StaleAuthRequestIOException(
    /** The responses a use-checked read had observed before this refused a send; empty for any other request. */
    val exchanges: List<HttpExchangeEvidence> = emptyList()
) : IOException(
    "Protected request auth snapshot is no longer current"
)

/** Binds an explicitly captured credential; it never consults Firebase on the OkHttp thread. */
internal class AuthSnapshotInterceptor @Inject constructor(
    private val tokenProvider: AuthTokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val request = chain.request()
        val tag = request.tag(AuthRequestTag::class.java)
            ?: throw MissingAuthRequestTagException()
        try {
            tokenProvider.requireCurrent(tag.snapshot)
        } catch (_: AuthIdentityChangedException) {
            throw StaleAuthRequestIOException(tag.use?.context?.observed().orEmpty())
        }

        val authenticated = request.newBuilder()
            .header("Authorization", "Bearer ${tag.snapshot.token}")
            .tag(AuthRequestTag::class.java, null)
            .tag(TopicUseTag::class.java, tag.use)
            .build()
        return chain.proceed(authenticated)
    }
}

/**
 * The last check before a use-checked read goes out, once per exchange (L-4e E2a).
 *
 * A network interceptor, and the last of them, because OkHttp repeats a request below the application interceptors on
 * `503 + Retry-After: 0` and on a misdirected 421, and each repeat is a send of its own. The identity first, as the
 * application interceptor does; then the access the read serves. It does not keep DNS or a connection attempt from
 * happening before it runs. A request without a [TopicUseTag] passes untouched.
 *
 * Every response is recorded after it arrives, so a refusal further down the same call carries what was already seen.
 */
internal class TopicUseNetworkInterceptor @Inject constructor(
    private val tokenProvider: AuthTokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val request = chain.request()
        val use = request.tag(TopicUseTag::class.java) ?: return chain.proceed(request)
        try {
            tokenProvider.requireCurrent(use.snapshot)
        } catch (_: AuthIdentityChangedException) {
            throw StaleAuthRequestIOException(use.context.observed())
        }
        if (!use.context.admitted()) throw WithheldUseIOException(use.context.observed())
        val exchange = use.context.nextExchange(use.send)
        val response = chain.proceed(request)
        use.context.record(HttpExchangeEvidence(use.send, exchange, response.code, response.header("Retry-After")))
        return response
    }
}

/**
 * Marks every non-GET/HEAD request body as one-shot.
 *
 * OkHttp 4.12 may otherwise replay a mutation for 408, 421, an auth challenge, or
 * `503 + Retry-After: 0`. Redirects and connection recovery are also disabled on the client.
 */
internal class MutationOneShotInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val original = chain.request()
        if (original.method == "GET" || original.method == "HEAD") {
            return chain.proceed(original)
        }

        val body = original.body ?: EMPTY_BODY
        val guardedBody = if (body.isOneShot()) body else OneShotRequestBody(body)
        val guarded = original.newBuilder()
            .method(original.method, guardedBody)
            .build()
        val response = chain.proceed(guarded)

        // Keep Retrofit/caller diagnostics faithful to the request it created rather than expose
        // the synthetic zero-byte DELETE body or the one-shot wrapper.
        return response.newBuilder().request(original).build()
    }

    private class OneShotRequestBody(
        private val delegate: RequestBody
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun isDuplex(): Boolean = delegate.isDuplex()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    private companion object {
        val EMPTY_BODY = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = 0L
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) = Unit
        }
    }
}

/**
 * Coroutine owner for explicit auth binding and the single allowed safe-read replay.
 *
 * The admission check precedes Firebase token acquisition, preserving D24's network-zero gate.
 */
internal class AuthenticatedTransport(
    private val tokenProvider: AuthTokenProvider,
    private val admitted: () -> Boolean = { ReleaseAdmission.isOpen }
) {
    /**
     * A read, with the single replay a stale credential is owed.
     *
     * With [useAdmitted], every send is also refused once the access the read serves no longer admits it (L-4e E2a): the
     * network interceptor checks each exchange, and the replay is checked again here before it is sent. Such a refusal
     * throws [TopicUseWithheldException] carrying every response the read had already observed — the first 401 included —
     * and an identity refusal of one of its sends carries them the same way. A read returned without a refusal is
     * classified as before, and the responses it saw on the way are not handed over.
     *
     * A response handed back is the caller's to close; one this ends with an exception is closed here.
     */
    suspend fun <T> executeRead(
        firstSnapshot: AuthSnapshot,
        useAdmitted: (() -> Boolean)? = null,
        call: suspend (AuthRequestTag) -> Response<T>
    ): Response<T> {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        val context = useAdmitted?.let(::TopicUseContext)
        val firstResponse = executeBound(firstSnapshot, context?.let { TopicUseTag(firstSnapshot, FIRST_SEND, it) }, call)
        if (firstResponse.code() != 401) return firstResponse
        context?.recordIfUnseen(FIRST_SEND, firstResponse.code(), firstResponse.headers()["Retry-After"])

        val replayable = try {
            val refreshed = tokenProvider.refreshAfterUnauthorized(firstSnapshot)
            tokenProvider.requireCurrent(firstSnapshot)
            refreshed?.takeUnless { tokenProvider.isKnownRejected(it) }
        } catch (error: Throwable) {
            firstResponse.errorBody()?.close()
            throw error
        }
        replayable ?: return firstResponse

        firstResponse.errorBody()?.close()
        // After the refresh and the rejection lookup, both of which suspend: the access can have been withheld while they ran.
        if (context != null && !context.admitted()) throw TopicUseWithheldException(context.observed())
        val replay = executeBound(replayable, context?.let { TopicUseTag(replayable, REPLAY_SEND, it) }, call)
        if (replay.code() == 401) {
            try {
                tokenProvider.recordRejected(replayable)
            } catch (error: Throwable) {
                replay.closeBodies()
                throw error
            }
        }
        return replay
    }

    suspend fun captureSnapshot(): AuthSnapshot = acquireSnapshotAfterAdmission()

    suspend fun captureSnapshot(expected: AuthIdentityFence): AuthSnapshot {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        tokenProvider.requireCurrent(expected)
        val snapshot = tokenProvider.currentSnapshot()
        if (snapshot.fence != expected) throw AuthIdentityChangedException()
        return snapshot
    }

    fun captureIdentityFence(): AuthIdentityFence {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        return tokenProvider.currentIdentityFence()
            ?: throw com.jay.fxi.data.auth.AuthUnavailableException("No authenticated user")
    }

    fun requireCurrent(snapshot: AuthSnapshot) {
        tokenProvider.requireCurrent(snapshot)
    }

    fun requireCurrent(fence: AuthIdentityFence) {
        tokenProvider.requireCurrent(fence)
    }

    suspend fun <T> executeMutation(
        snapshot: AuthSnapshot,
        call: suspend (AuthRequestTag) -> Response<T>
    ): Response<T> {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        return executeBound(snapshot, use = null, call)
    }

    private suspend fun acquireSnapshotAfterAdmission(): AuthSnapshot {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        return tokenProvider.currentSnapshot()
    }

    private suspend fun <T> executeBound(
        snapshot: AuthSnapshot,
        use: TopicUseTag?,
        call: suspend (AuthRequestTag) -> Response<T>
    ): Response<T> {
        try {
            tokenProvider.requireCurrent(snapshot)
        } catch (moved: AuthIdentityChangedException) {
            if (use == null) throw moved
            throw AuthIdentityChangedException(exchanges = use.context.observed())
        }
        val response = try {
            call(AuthRequestTag(snapshot, use))
        } catch (stale: StaleAuthRequestIOException) {
            throw AuthIdentityChangedException(exchanges = stale.exchanges)
        } catch (withheld: WithheldUseIOException) {
            throw TopicUseWithheldException(withheld.exchanges)
        }
        if (!tokenProvider.isCurrent(snapshot)) {
            use?.let { it.context.recordIfUnseen(it.send, response.code(), response.headers()["Retry-After"]) }
            response.closeBodies()
            // Refuse the answer, keep the rate limit. Closing the bodies is the refusal; the status
            // and `Retry-After` describe the transport rather than the session, so carrying them
            // out is what stops the caller re-requesting inside a window the server just closed.
            throw AuthIdentityChangedException(
                statusCode = response.code(),
                retryAfter = response.headers()["Retry-After"],
                exchanges = use?.context?.observed().orEmpty()
            )
        }
        return response
    }

    private fun Response<*>.closeBodies() {
        (body() as? Closeable)?.close()
        errorBody()?.close()
    }

    private companion object {
        const val FIRST_SEND = 1
        const val REPLAY_SEND = 2
    }
}
