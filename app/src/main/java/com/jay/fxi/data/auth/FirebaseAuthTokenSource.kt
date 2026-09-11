package com.jay.fxi.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.util.await
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in identity **with its generation**, as a callback stream.
 *
 * Separate from [AuthTokenSource] rather than a method on it: that interface has ten test
 * implementations and none of them has an opinion about publishing. The production binding is the
 * same singleton, so both surfaces read one tracker — two Firebase listeners deciding
 * independently is the defect this exists to remove.
 *
 * **Listeners must not block and must not call back into the source.** The production source
 * invokes callbacks on the thread running its delivery pump, which need not be the thread that
 * enqueued them. A live identity read can run that pump before returning; for example,
 * PremiumAccessCoordinator reads live identity while holding its mutex. Enqueue and return.
 */
fun interface AuthFenceStream {
    fun observe(onFence: (AuthIdentityFence?) -> Unit)
}

/**
 * Process-local Firebase identity tracker.
 *
 * Generation changes on every observed UID transition, including the null transition between a
 * logout and a same-UID login. Token refreshes do not rotate the auth generation.
 */
@Singleton
internal class FirebaseAuthTokenSource @Inject constructor(
    private val auth: FirebaseAuth
) : AuthTokenSource, AuthFenceStream {
    private val identityLock = Any()

    private val generationTracker = auth.currentUser.let { initialUser ->
        AuthSessionGenerationTracker(initialUser?.uid, initialUser)
    }

    /**
     * Hands the tracker's owed deliveries over, **outside** [identityLock].
     *
     * Enqueue under the lock fixes the order. Listener callbacks run after the drain releases
     * identityLock, so callback work does not hold up identity updates on other threads.
     * Same-thread monitor reentry alone would not deadlock.
     */
    private val pump = SerialDeliveryPump { synchronized(identityLock) { generationTracker.drainOutbox() } }

    private val authStateListener = FirebaseAuth.AuthStateListener(::observeCurrentUser)

    init {
        auth.addAuthStateListener(authStateListener)
    }

    override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
        synchronized(identityLock) { generationTracker.subscribe(onFence) }
        deliverPending()
    }

    /**
     * Called after every section that can advance the generation — the live reads included,
     * because those advance too.
     */
    private fun deliverPending() = pump.run()

    override fun currentIdentity(): AuthIdentity? {
        val identity = synchronized(identityLock) {
            val user = auth.currentUser
            generationTracker.observe(user?.uid, user)
        }
        deliverPending()
        return identity
    }

    override fun invalidateCurrentIdentity(): Boolean {
        val invalidated = synchronized(identityLock) {
            val user = auth.currentUser
            val current = generationTracker.observe(user?.uid, user) ?: return@synchronized false
            generationTracker.invalidate(current)
        }
        deliverPending()
        return invalidated
    }

    override fun invalidateCurrentSession(expected: AuthIdentity): Boolean {
        val invalidated = synchronized(identityLock) {
            val user = auth.currentUser
            generationTracker.observe(user?.uid, user)
            generationTracker.retire(expected)
        }
        // Still drained: the `observe` above can itself be a transition worth announcing, and that
        // one is nobody's teardown.
        deliverPending()
        return invalidated
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
        deliverPending()
    }
}

/**
 * Hands deliveries over: one at a time, and never nested inside itself.
 *
 * Extracted from its one caller so the two properties that matter can actually be measured.
 * The current JVM test setup has no fixture that constructs [FirebaseAuthTokenSource]. While this
 * lived inside it, a mutation deleting the whole `synchronized` block passed the suite, and the
 * trip-wire that looked like it covered that had a hole of its own.
 *
 * **Serialised**, so two threads that each drained half cannot hand one subscriber its transitions
 * out of order — enqueue order under the producer's lock only fixes the order things were *minted*
 * in. **Non-reentrant**, because [drain] hands back a snapshot and clears: a nested call would take
 * newer items and deliver them past the older ones the outer loop is still holding. Review
 * reproduced `[g1, g2, g3]` to one subscriber and `[g1, g3, g2]` to the other before the guard.
 * Nothing is lost by refusing — the outer `while` picks up whatever was left, in order.
 *
 * **A listener that throws costs the rest of its batch.** [drain] has already cleared, so the
 * deliveries queued behind the throwing one are gone; the `finally` only makes the pump usable
 * again. That is the current contract — pinned by test rather than left to be discovered.
 */
internal class SerialDeliveryPump(
    private val drain: () -> List<AuthSessionGenerationTracker.Delivery>
) {
    private val lock = Any()

    /** Guarded by [lock]. The monitor is reentrant, so this is what refuses a nested run. */
    private var delivering = false

    fun run() {
        synchronized(lock) {
            if (delivering) return
            delivering = true
            try {
                while (true) {
                    val owed = drain()
                    if (owed.isEmpty()) return
                    owed.forEach { it.deliver() }
                }
            } finally {
                delivering = false
            }
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

    private val subscribers = mutableListOf<(AuthIdentityFence?) -> Unit>()
    private val outbox = mutableListOf<Delivery>()

    /** One listener and the fence it is owed, in the order the transition happened. */
    internal class Delivery(
        private val listener: (AuthIdentityFence?) -> Unit,
        private val fence: AuthIdentityFence?
    ) {
        fun deliver() = listener(fence)
    }

    /**
     * Registers [listener] and replays the current fence to it, exactly once.
     *
     * **The replay is not optional.** The initial generation is set in the constructor rather than
     * by [advanceGeneration], so a signed-in cold start whose first `observe` sees the same user
     * never advances — publishing only on change would leave a subscriber that started late with
     * no identity at all. The replay does not advance the generation: it reports, it is not a
     * transition.
     *
     * Registration and the replay are enqueued inside the caller's lock together with every
     * transition, so a change racing a subscription cannot be reordered around it.
     */
    fun subscribe(listener: (AuthIdentityFence?) -> Unit) {
        subscribers += listener
        outbox += Delivery(listener, currentFence())
    }

    /**
     * Everything owed since the last drain, in order. The caller runs these **outside** its lock.
     *
     * Listeners must not block or call back into the source. The production source takes this
     * snapshot under identityLock, then delivers it through SerialDeliveryPump after releasing
     * identityLock. Delivery can occur during a live identity read while the caller holds its own
     * lock; it need not run on the thread that enqueued the snapshot's items.
     */
    fun drainOutbox(): List<Delivery> {
        if (outbox.isEmpty()) return emptyList()
        val owed = outbox.toList()
        outbox.clear()
        return owed
    }

    private fun currentFence(): AuthIdentityFence? =
        observedUid?.let { AuthIdentityFence(it, generation) }

    fun observe(uid: String?, sessionMarker: Any?): AuthIdentity? {
        if (uid != observedUid || sessionMarker !== observedSessionMarker) {
            observedUid = uid
            observedSessionMarker = sessionMarker
            advanceGeneration()
        }
        return uid?.let { AuthIdentity(it, generation) }
    }

    /**
     * Moves past [expected], and says so.
     *
     * This is the sign-in side: the tracked path invalidates *before* Firebase does anything, and
     * the uid it lands on may be the one already held. Nothing else would ever announce that.
     */
    fun invalidate(expected: AuthIdentity): Boolean = invalidate(expected, announce = true)

    /**
     * Moves past [expected] **without** announcing the session it lands on.
     *
     * The sign-out path uses this to retire [expected] without enqueueing the intermediate fence.
     * A subsequent observed sign-out publishes `null` separately. Publishing the intermediate
     * same-uid fence can make AuthAccessBinder rebind, write the owner to disk and schedule an
     * entitlement query during teardown. The uid adapter suppresses this generation-only change
     * if it has already delivered that uid.
     */
    fun retire(expected: AuthIdentity): Boolean = invalidate(expected, announce = false)

    private fun invalidate(expected: AuthIdentity, announce: Boolean): Boolean {
        val current = observedUid?.let { AuthIdentity(it, generation) }
        if (current != expected) return false
        advanceGeneration(announce)
        return true
    }

    /**
     * The one place a change is minted, and therefore the one place a change is published.
     *
     * Every path that can move the generation — `observe` from the Firebase listener, `observe`
     * from a live read, and both explicit invalidations — arrives here. Publishing here rather
     * than at each of those four means a fifth path publishes by construction. What keeps the
     * *hand-over* honest is separate and structural: `FirebaseAuthTokenSourceWiringTest` refuses a
     * lock-holder that does not drain, because none of that wiring can be reached from a unit
     * test — deleting all five `deliverPending()` calls once left the whole suite green.
     */
    private fun advanceGeneration(announce: Boolean = true) {
        check(generation < Long.MAX_VALUE) { "Auth generation exhausted" }
        generation += 1L
        if (!announce || subscribers.isEmpty()) return
        val fence = currentFence()
        subscribers.forEach { outbox += Delivery(it, fence) }
    }
}
