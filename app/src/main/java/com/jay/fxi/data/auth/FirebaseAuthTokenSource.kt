package com.jay.fxi.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.util.await
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local Firebase identity tracker.
 *
 * Generation changes on every observed UID transition, including the null transition between a
 * logout and a same-UID login. Token refreshes do not rotate the auth generation.
 */
@Singleton
internal class FirebaseAuthTokenSource @Inject constructor(
    private val auth: FirebaseAuth
) : AuthTokenSource {
    private val identityLock = Any()
    private val generationTracker = auth.currentUser.let { initialUser ->
        AuthSessionGenerationTracker(initialUser?.uid, initialUser)
    }

    private val authStateListener = FirebaseAuth.AuthStateListener(::observeCurrentUser)

    init {
        auth.addAuthStateListener(authStateListener)
    }

    override fun currentIdentity(): AuthIdentity? {
        return synchronized(identityLock) {
            val user = auth.currentUser
            generationTracker.observe(user?.uid, user)
        }
    }

    override fun invalidateCurrentIdentity(): Boolean =
        synchronized(identityLock) {
            val user = auth.currentUser
            val current = generationTracker.observe(user?.uid, user) ?: return@synchronized false
            generationTracker.invalidate(current)
        }

    override fun invalidateCurrentSession(expected: AuthIdentity): Boolean =
        synchronized(identityLock) {
            val user = auth.currentUser
            generationTracker.observe(user?.uid, user)
            generationTracker.invalidate(expected)
        }

    override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
        val user = auth.currentUser ?: throw AuthUnavailableException("No authenticated user")
        if (user.uid != identity.uid || currentIdentity() != identity) {
            throw AuthIdentityChangedException()
        }

        val token = try {
            user.getIdToken(forceRefresh).await().token
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw AuthUnavailableException("Firebase ID token acquisition failed", error)
        }

        if (currentIdentity() != identity) {
            throw AuthIdentityChangedException()
        }
        return token?.takeIf(String::isNotBlank)
            ?: throw AuthUnavailableException("Firebase returned an empty ID token")
    }

    private fun observeCurrentUser(firebaseAuth: FirebaseAuth) {
        synchronized(identityLock) {
            val user = firebaseAuth.currentUser
            generationTracker.observe(user?.uid, user)
        }
    }
}

/**
 * Process-local auth epoch. Explicit invalidation is the correctness boundary for app-owned
 * sign-in and sign-out. The opaque marker is a secondary guard for SDK paths that replace the
 * FirebaseUser object; Firebase Auth 24.0.1 may instead reuse that object for a same-UID sign-in.
 */
internal class AuthSessionGenerationTracker(
    initialUid: String?,
    initialSessionMarker: Any?
) {
    private var observedUid: String? = initialUid
    private var observedSessionMarker: Any? = initialSessionMarker
    private var generation: Long = if (initialUid == null) 0L else 1L

    fun observe(uid: String?, sessionMarker: Any?): AuthIdentity? {
        if (uid != observedUid || sessionMarker !== observedSessionMarker) {
            observedUid = uid
            observedSessionMarker = sessionMarker
            advanceGeneration()
        }
        return uid?.let { AuthIdentity(it, generation) }
    }

    fun invalidate(expected: AuthIdentity): Boolean {
        val current = observedUid?.let { AuthIdentity(it, generation) }
        if (current != expected) return false
        advanceGeneration()
        return true
    }

    private fun advanceGeneration() {
        check(generation < Long.MAX_VALUE) { "Auth generation exhausted" }
        generation += 1L
    }
}
