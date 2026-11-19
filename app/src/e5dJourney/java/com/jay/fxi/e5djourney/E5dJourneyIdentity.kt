package com.jay.fxi.e5djourney

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSessionGenerationTracker
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.auth.ObservedAuthUser
import com.jay.fxi.data.auth.ObservedAuthUserHandle
import com.jay.fxi.data.auth.SerialDeliveryPump
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` §2, R8): the journey's one owner, behind the three auth bindings, outside Firebase. It
 * keeps the same generation contract as the real sources — one tracker for identity, fences and their order — and starts signed
 * in, as a returning user after a process death would be. [signOut] and [signIn] are the logout case's transitions; they go
 * through the same tracker and notices every auth consumer already reads, so Root and Route see an ordinary auth change.
 */
internal class E5dJourneyIdentity(private val post: (Runnable) -> Unit) : AuthUserObservation, AuthTokenSource, AuthFenceStream {
    private val lock = Any()
    private val session = Any()

    // Seeded with the signed-in owner, as the real source seeds its tracker with Firebase's current user: a returning user's
    // cold start begins at generation 1 and never hands a subscriber a signed-out fence first.
    private val tracker = AuthSessionGenerationTracker(UID, session)
    private val pump = SerialDeliveryPump { synchronized(lock) { tracker.drainOutbox() } }
    private var signedIn = true
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun signIn() = transition(to = true)

    fun signOut() = transition(to = false)

    fun isSignedIn(): Boolean = synchronized(lock) { signedIn }

    private fun transition(to: Boolean) {
        synchronized(lock) {
            if (signedIn == to) return
            signedIn = to
            observedIdentity()
        }
        pump.run()
        E5dJourneyRecord.add("auth", if (to) "signed-in" else "signed-out")
        for (listener in listeners) post(Runnable { if (listener in listeners) listener() })
    }

    private fun observedIdentity(): AuthIdentity? = if (signedIn) tracker.observe(UID, session) else tracker.observe(null, null)

    /** Each fence a production subscriber is actually given is recorded as it is given, with its owner and generation. */
    override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
        synchronized(lock) {
            tracker.subscribe { fence ->
                E5dJourneyRecord.add("auth", if (fence == null) "fence none" else "fence ${fence.uid} gen=${fence.authGeneration}")
                onFence(fence)
            }
        }
        pump.run()
    }

    override fun currentIdentity(): AuthIdentity? {
        val identity = synchronized(lock) { observedIdentity() }
        pump.run()
        return identity
    }

    override fun invalidateCurrentIdentity(): Boolean {
        val invalidated = synchronized(lock) {
            val current = observedIdentity() ?: return@synchronized false
            tracker.invalidate(current)
        }
        pump.run()
        return invalidated
    }

    override fun invalidateCurrentSession(expected: AuthIdentity): Boolean {
        val retired = synchronized(lock) {
            observedIdentity()
            tracker.retire(expected)
        }
        pump.run()
        return retired
    }

    override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
        val current = currentIdentity() ?: throw AuthUnavailableException("No authenticated user")
        if (current != identity) throw AuthIdentityChangedException()
        return TOKEN
    }

    override fun currentUser(): ObservedAuthUserHandle? = if (synchronized(lock) { signedIn }) handle else null

    override fun register(onChange: () -> Unit): AutoCloseable {
        val listener: () -> Unit = { onChange() }
        listeners += listener
        post(Runnable { if (listener in listeners) listener() })
        return AutoCloseable { listeners -= listener }
    }

    private val handle = object : ObservedAuthUserHandle {
        override val uid: String get() = UID
        override fun readProfile(): ObservedAuthUser = ObservedAuthUser(UID, email = null, displayName = DISPLAY_NAME, photoUrl = null, providerId = null)
    }

    internal companion object {
        const val UID = "u1"
        const val DISPLAY_NAME = "E5d journey"
        const val TOKEN = "e5d-journey-token"
    }
}
