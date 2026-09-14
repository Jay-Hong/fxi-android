package com.jay.fxi.data.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * A Firebase credential bound to the auth session that produced it.
 *
 * The token is deliberately excluded from [toString]. Retrofit keeps method arguments in an
 * Invocation request tag, so a normal exception or diagnostic must not render the bearer value.
 */
data class AuthSnapshot(
    val uid: String,
    val authGeneration: Long,
    val token: String
) {
    init {
        require(uid.isNotBlank()) { "uid must not be blank" }
        require(authGeneration >= 0L) { "authGeneration must not be negative" }
        require(token.isNotBlank()) { "token must not be blank" }
    }

    internal val identity: AuthIdentity
        get() = AuthIdentity(uid, authGeneration)

    val fence: AuthIdentityFence
        get() = AuthIdentityFence(uid, authGeneration)

    override fun toString(): String =
        "AuthSnapshot(uid=<redacted>, authGeneration=$authGeneration, token=<redacted>)"
}

internal data class AuthIdentity(
    val uid: String,
    val authGeneration: Long
)

/** Token-free identity captured at the start of a UI or coordinator intent. */
@ConsistentCopyVisibility
data class AuthIdentityFence internal constructor(
    val uid: String,
    val authGeneration: Long
) {
    internal val identity: AuthIdentity
        get() = AuthIdentity(uid, authGeneration)

    override fun toString(): String =
        "AuthIdentityFence(uid=<redacted>, authGeneration=$authGeneration)"
}

open class AuthUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class AuthIdentityChangedException(
    /**
     * Status of a response that had already arrived when the session moved, if one had.
     *
     * The body is still refused — it was authorised as somebody else. A rate limit is not: it is
     * levied on the transport by address and endpoint, it outlives the credential that happened to
     * carry it, and dropping it makes the next request arrive inside a window the server explicitly
     * closed.
     */
    val statusCode: Int? = null,
    /** `Retry-After` from that same response, verbatim and unparsed. */
    val retryAfter: String? = null,
    /**
     * Every response a use-checked request observed on the wire before this ended it, oldest first (L-4e E2a).
     *
     * Empty for any other request. When not empty it is the evidence to hand over, one call per response.
     * When [statusCode] is present, it and [retryAfter] repeat the last entry; a send refused before returning a response
     * may leave both null. Never hand these fields over separately from a non-empty list.
     */
    val exchanges: List<HttpExchangeEvidence> = emptyList()
) : CancellationException("Authentication changed while acquiring or using a credential")

/**
 * One response a protected read received on the wire: which send of the logical call it answered, which exchange of that
 * send, and what the server said about retrying. [retryAfter] is verbatim and unparsed.
 *
 * Several can belong to one send, because OkHttp repeats a request below the application interceptors on
 * `503 + Retry-After: 0` and on a misdirected 421 — each is a response of its own, and neither replaces the one before.
 */
data class HttpExchangeEvidence(
    val send: Int,
    val exchange: Int,
    val statusCode: Int,
    val retryAfter: String?
)
