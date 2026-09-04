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

class AuthIdentityChangedException :
    CancellationException("Authentication changed while acquiring or using a credential")
