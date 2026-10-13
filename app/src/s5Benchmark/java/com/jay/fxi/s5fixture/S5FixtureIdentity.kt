package com.jay.fxi.s5fixture

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
 * S5-4 slice 4b: the S5 fixture's one identity source, the user observation, the token source and the fence stream at once,
 * so the three never disagree on the user or the generation. It starts signed out (generation 0) and [signInAfter] moves it,
 * once, to the fixture user (generation 1) only when the selection seed was written and read back. [signOut] is the transition
 * back to null, which the app's own sign-out cannot reach because it signs out of FirebaseAuth.
 * Generations come from the production tracker and its delivery pump, so replay, invalidation and retirement follow the
 * production rules. User notices go through [post] (the main looper in the app): one right after registration, signed out
 * included, then one at each sign-in or sign-out. Each registration is its own entry, so closing one suppresses its queued
 * notices that have not started running, even when the same callback is registered again; a notice already running is not
 * interrupted.
 */
internal class S5FixtureIdentity(private val post: (Runnable) -> Unit) : AuthUserObservation, AuthTokenSource, AuthFenceStream {
    private val lock = Any()
    private val tracker = AuthSessionGenerationTracker(null, null)
    private val pump = SerialDeliveryPump { synchronized(lock) { tracker.drainOutbox() } }
    private val session = Any()
    private var signedIn = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Signs the fixture user in when [seed] succeeds; otherwise the identity stays as it was. Returns whether it signed in. */
    suspend fun signInAfter(seed: suspend () -> Boolean): Boolean {
        if (!seed()) return false
        signIn()
        return true
    }

    fun signIn() = transition(to = true)

    fun signOut() = transition(to = false)

    private fun transition(to: Boolean) {
        synchronized(lock) {
            if (signedIn == to) return
            signedIn = to
            observedIdentity()
        }
        pump.run()
        for (listener in listeners) post(Runnable { if (listener in listeners) listener() })
    }

    /** Guarded by [lock]. */
    private fun observedIdentity(): AuthIdentity? = if (signedIn) tracker.observe(UID, session) else tracker.observe(null, null)

    override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
        synchronized(lock) { tracker.subscribe(onFence) }
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
        const val UID = "s5-fixture"
        const val DISPLAY_NAME = "S5 fixture"
        /** The fixture server checks no token; the value only has to be non-blank. */
        const val TOKEN = "s5-fixture-token"
    }
}
