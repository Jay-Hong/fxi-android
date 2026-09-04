package com.jay.fxi.data.remote

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response as OkHttpResponse
import okio.BufferedSink
import retrofit2.Response

/** Retrofit request tag whose rendering never exposes the Firebase bearer token. */
internal class AuthRequestTag(val snapshot: AuthSnapshot) {
    override fun toString(): String =
        "AuthRequestTag(uid=<redacted>, authGeneration=${snapshot.authGeneration}, token=<redacted>)"
}

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
internal class StaleAuthRequestIOException : IOException(
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
            throw StaleAuthRequestIOException()
        }

        val authenticated = request.newBuilder()
            .header("Authorization", "Bearer ${tag.snapshot.token}")
            .tag(AuthRequestTag::class.java, null)
            .build()
        return chain.proceed(authenticated)
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
    suspend fun <T> executeRead(
        firstSnapshot: AuthSnapshot,
        call: suspend (AuthRequestTag) -> Response<T>
    ): Response<T> {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        val firstResponse = executeBound(firstSnapshot, call)
        if (firstResponse.code() != 401) return firstResponse

        val refreshed = try {
            tokenProvider.refreshAfterUnauthorized(firstSnapshot)
        } catch (error: Throwable) {
            firstResponse.errorBody()?.close()
            throw error
        }
        try {
            tokenProvider.requireCurrent(firstSnapshot)
        } catch (error: Throwable) {
            firstResponse.errorBody()?.close()
            throw error
        }
        if (refreshed == null || tokenProvider.isKnownRejected(refreshed)) return firstResponse

        firstResponse.errorBody()?.close()
        val replay = executeBound(refreshed, call)
        if (replay.code() == 401) {
            tokenProvider.recordRejected(refreshed)
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
        return executeBound(snapshot, call)
    }

    private suspend fun acquireSnapshotAfterAdmission(): AuthSnapshot {
        if (!admitted()) throw IOException("D24 release admission is OFF")
        return tokenProvider.currentSnapshot()
    }

    private suspend fun <T> executeBound(
        snapshot: AuthSnapshot,
        call: suspend (AuthRequestTag) -> Response<T>
    ): Response<T> {
        tokenProvider.requireCurrent(snapshot)
        val response = try {
            call(AuthRequestTag(snapshot))
        } catch (_: StaleAuthRequestIOException) {
            throw AuthIdentityChangedException()
        }
        if (!tokenProvider.isCurrent(snapshot)) {
            (response.body() as? Closeable)?.close()
            response.errorBody()?.close()
            throw AuthIdentityChangedException()
        }
        return response
    }
}
