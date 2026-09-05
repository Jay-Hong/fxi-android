package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val onDue: suspend (RefreshIntent, QueryOrigin) -> Unit
) {
    private val mutex = Mutex()
    private var pending: Job? = null
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
    suspend fun schedule(request: RecheckRequest) = mutex.withLock {
        pending?.cancel()
        consecutiveAttempts += 1
        val now = clock.elapsedMillis()
        val proposed = now + maxOf(request.minDelayMillis, backoffMillis(consecutiveAttempts))
        val target = maxOf(proposed, earliestAllowedAtMillis ?: Long.MIN_VALUE)
        earliestAllowedAtMillis = target
        val intent = strongest(pendingIntent, request.intent)
        pendingIntent = intent
        val delayMillis = (target - now).coerceAtLeast(0L)
        pending = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            // Read at fire time, not launch time: a caller blocked by the floor in the meantime
            // may have upgraded the queued mode.
            onDue(mutex.withLock { pendingIntent } ?: intent, QueryOrigin.SCHEDULED)
        }
    }

    /**
     * Cancels the pending re-query and releases the floor.
     *
     * Called on every settled outcome and on every identity boundary: stable result, reset,
     * logout, UID change, typed rejection. Those are exactly the events after which an old
     * server-requested delay no longer describes anything.
     */
    suspend fun cancel() = mutex.withLock {
        pending?.cancel()
        pending = null
        pendingIntent = null
        consecutiveAttempts = 0
        earliestAllowedAtMillis = null
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
    fun shouldQuery(intent: RefreshIntent, origin: QueryOrigin): Boolean {
        val now = clock.elapsedMillis()
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
