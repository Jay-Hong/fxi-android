package com.jay.fxi.data.auth

/** SDK boundary kept small so auth-generation and single-flight rules are JVM-testable. */
internal interface AuthTokenSource {
    fun currentIdentity(): AuthIdentity?

    /** Atomically invalidates whichever authenticated identity is current, if any. */
    fun invalidateCurrentIdentity(): Boolean =
        currentIdentity()?.let(::invalidateCurrentSession) ?: false

    /** Invalidates [expected] before an app-owned sign-out changes Firebase's current user. */
    fun invalidateCurrentSession(expected: AuthIdentity): Boolean = false

    suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String
}
