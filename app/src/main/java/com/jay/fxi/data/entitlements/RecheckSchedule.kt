package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Monotonic time source, injected so tests do not depend on the wall clock. */
fun interface RecheckClock {
    fun elapsedMillis(): Long
}

/** Why a query is running. A scheduled retry is not a duplicate of an ordinary refresh. */
enum class QueryOrigin {
    /** A caller asked; the client debounce applies. */
    CALLER,

    /** This schedule fired. Already rate-limited by its own floor, so the debounce must not
     *  suppress it — doing so silently drops the retry the server asked for. */
    SCHEDULED
}

/**
 * The single owner of entitlement re-queries.
 *
 * `ANDROID_V2_PLAN.md` §5.1 DoD: pending and failure paths from *both* an active grant and a
 * no-grant state must merge into one owner, and a server `Retry-After` must never be shortened.
 *
 * The floor is an **absolute** instant, not a delay, and it gates *entry to a query* rather than
 * only the timer. Both matter: a relative delay would be recomputed from "now" on every reschedule
 * and silently move an existing deadline earlier, and a floor that only armed the timer would be
 * walked straight past by a direct `refresh(FORCE_PREMIUM)` call.
 */
class RecheckSchedule(
    private val scope: CoroutineScope,
    private val clock: RecheckClock,
    /**
     * Called once a re-query armed by [schedule] has finished — fired or not, normally, by throwing or cancelled — outside
     * this schedule's lock, with the binding and [revision] it was armed under. Not awaited by anyone who cancels it.
     */
    private val onSettled: suspend (bindingEpoch: Long, revision: Long) -> Unit = { _, _ -> },
    /** Test barrier after the delay and before taking the fire lock. Production leaves it empty. */
    private val beforeFire: suspend () -> Unit = {},
    private val onDue: suspend (intent: RefreshIntent, origin: QueryOrigin, bindingEpoch: Long) -> Unit
) {
    private val mutex = Mutex()
    private var pending: Job? = null

    /** Whether [pending] has passed its delay and handed its intent to [onDue]. */
    private var pendingFired = false

    /** Moves with every arming, replacement and cancellation, so a late completion can tell it is no longer current. */
    @Volatile
    var revision: Long = 0L
        private set

    private var pendingIntent: RefreshIntent? = null
    private var consecutiveAttempts = 0
    private var lastQueryAtMillis: Long? = null

    /** Absolute instant before which no re-query may run, however it is triggered. */
    private var earliestAllowedAtMillis: Long? = null

    /**
     * Replaces the single pending re-query, never moving an existing deadline earlier and never
     * weakening the mode it will run in.
     *
     * Mode strength matters on a join: a `.forcePremium` retry that a later ordinary request
     * replaced would come back as `IF_STALE` and, per the D23 table, be unable to grant at all.
     */
    suspend fun schedule(request: RecheckRequest, bindingEpoch: Long) = mutex.withLock {
        pending?.cancel()
        consecutiveAttempts += 1
        val now = clock.elapsedMillis()
        val proposed = now + maxOf(request.minDelayMillis, backoffMillis(consecutiveAttempts))
        val target = maxOf(proposed, earliestAllowedAtMillis ?: Long.MIN_VALUE)
        earliestAllowedAtMillis = target
        val intent = strongest(pendingIntent, request.intent)
        pendingIntent = intent
        val delayMillis = (target - now).coerceAtLeast(0L)
        val armed = ++revision
        pendingFired = false
        // ATOMIC: an arming replaced or cancelled before it was dispatched still runs its finally, so every arming settles.
        pending = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                if (delayMillis > 0) delay(delayMillis)
                beforeFire()
                // Read at fire time, not launch time: a caller blocked by the floor in the meantime
                // may have upgraded the queued mode. Marked fired under the same lock, so a fold after this
                // point is known not to reach the query.
                val due = mutex.withLock {
                    if (revision != armed) return@launch
                    pendingFired = true
                    pendingIntent
                } ?: intent
                // The epoch belongs to the binding that armed THIS callback. Never read a replacement
                // binding's epoch at fire time; the coordinator revalidates this captured value there.
                onDue(due, QueryOrigin.SCHEDULED, bindingEpoch)
            } finally {
                withContext(NonCancellable) {
                    // Only this arming's own reference is detached; intent, attempts and floor stay.
                    mutex.withLock {
                        if (revision == armed) {
                            pending = null
                            pendingFired = false
                        }
                    }
                    onSettled(bindingEpoch, armed)
                }
            }
        }
    }

    /**
     * Folds [intent] into a re-query that has not fired yet, as one step. False when there is none — nothing armed, or the
     * armed one has already handed its intent to the query — and then nothing changes. Deadline and attempts never move.
     */
    suspend fun foldIntoUnfired(intent: RefreshIntent): Boolean = mutex.withLock {
        if (pending == null || pendingFired) return false
        pendingIntent = strongest(pendingIntent, intent)
        true
    }

    /**
     * Records a later floor without arming anything: at least [minDelayMillis] from now, never earlier than the floor already
     * held. Whatever is armed is cancelled, fired or not, so it cannot run inside the new floor; intent and attempts stay for
     * whoever arms next.
     */
    suspend fun recordFloorWithoutArming(minDelayMillis: Long) = mutex.withLock {
        pending?.cancel()
        pending = null
        pendingFired = false
        revision += 1
        val floor = clock.elapsedMillis() + minDelayMillis
        earliestAllowedAtMillis = maxOf(floor, earliestAllowedAtMillis ?: Long.MIN_VALUE)
    }

    /**
     * Cancels the pending re-query and releases the floor.
     *
     * Called on every settled outcome and on every identity boundary: stable result, reset,
     * logout, UID change, typed rejection. Those are exactly the events after which an old
     * server-requested delay no longer describes anything.
     */
    suspend fun cancel(preserveServerFloor: Boolean = false) = mutex.withLock {
        pending?.cancel()
        pending = null
        pendingFired = false
        revision += 1
        pendingIntent = null
        consecutiveAttempts = 0
        // A settled outcome retires the floor with the request that earned it. An *identity*
        // boundary does not: a `Retry-After` is a rate limit on this device and endpoint, and
        // switching accounts is not something the server agreed to lift. Clearing it here let a
        // sign-in one second after a 30s floor issue its first query immediately.
        if (!preserveServerFloor) earliestAllowedAtMillis = null
    }

    /**
     * Whether a query may start now.
     *
     * Two independent gates:
     *  - the absolute floor, which applies to every origin and every intent. A `Retry-After` is a
     *    server rate limit; `.forcePremium` bypasses the client debounce and the server's premium
     *    cache, not this.
     *  - the ordinary-query debounce, which suppresses duplicate `IF_STALE` callers only. It is
     *    not a permission TTL, and a [QueryOrigin.SCHEDULED] retry is exempt because it already
     *    waited out its own floor.
     */
    fun shouldQuery(
        intent: RefreshIntent,
        origin: QueryOrigin,
        now: Long = clock.elapsedMillis()
    ): Boolean {
        if (origin == QueryOrigin.CALLER) {
            earliestAllowedAtMillis?.let { if (now < it) return false }
        }
        if (origin == QueryOrigin.SCHEDULED) return true
        if (intent.bypassesClientDebounce) return true
        val last = lastQueryAtMillis ?: return true
        return now - last >= IF_STALE_DEBOUNCE_MILLIS
    }

    /**
     * Folds a refused caller's mode into the queued re-query.
     *
     * A `.forcePremium` triggered by a purchase or restore while a `Retry-After` floor is still
     * running must not simply vanish: the floor is honoured, but the retry that eventually runs
     * has to be strong enough to grant.
     */
    suspend fun upgradePendingIntent(intent: RefreshIntent) = mutex.withLock {
        if (pending?.isActive != true) return
        pendingIntent = strongest(pendingIntent, intent)
    }

    /**
     * Holds a refused request until the floor lets it through.
     *
     * [upgradePendingIntent] alone only strengthens a retry that already exists. After an identity
     * boundary there is none — the boundary cancels the pending retry and keeps only the floor — so
     * the new binding's first query had nothing to fold into and was dropped, leaving that user on
     * `NoGrant` with nothing scheduled to ask again. Arming one here is the difference between
     * honouring the wait and losing the request.
     */
    suspend fun deferUntilFloor(intent: RefreshIntent, bindingEpoch: Long) {
        val floor = mutex.withLock {
            if (pending?.isActive == true) {
                // This is a fold, not a reschedule: deadline and consecutiveAttempts stay put.
                // With attempt 1 due at 30s, a fold at 29s still fires at 30s; schedule() would
                // increment to attempt 2 and move it to 29s + 5s = 34s.
                pendingIntent = strongest(pendingIntent, intent)
                return
            }
            earliestAllowedAtMillis
        } ?: return
        schedule(
            RecheckRequest(
                intent,
                minDelayMillis = (floor - clock.elapsedMillis()).coerceAtLeast(0L)
            ),
            bindingEpoch
        )
    }

    /** Whether a caller refused by [shouldQuery] was held back by the server floor, not only by the debounce. */
    fun floorBlocksNow(now: Long = clock.elapsedMillis()): Boolean {
        val floor = earliestAllowedAtMillis ?: return false
        return now < floor
    }

    fun recordQueryStarted() {
        lastQueryAtMillis = clock.elapsedMillis()
    }

    /** Test/diagnostic view: whether a re-query is currently armed. */
    val isArmed: Boolean
        get() = pending?.isActive == true

    private fun backoffMillis(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val shift = (attempt - 2).coerceAtMost(MAX_BACKOFF_SHIFT)
        return (BASE_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private fun strongest(a: RefreshIntent?, b: RefreshIntent): RefreshIntent =
        if (a == null || b.ordinal >= a.ordinal) b else a

    companion object {
        const val IF_STALE_DEBOUNCE_MILLIS: Long = 10_000L
        const val BASE_BACKOFF_MILLIS: Long = 5_000L
        const val MAX_BACKOFF_MILLIS: Long = 300_000L
        private const val MAX_BACKOFF_SHIFT = 6
    }
}
