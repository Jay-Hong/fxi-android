package com.jay.fxi.data.free

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/** Identifies a cache slot. Each tab keeps its own periods alive across switches. */
data class FreeSnapshotKey(val tab: String, val period: GraphPeriod)

/** One slot as the UI sees it. */
data class FreeSnapshotEntry(
    val snapshot: FreeSnapshot,
    val freshness: FreeSnapshotFreshness
)

/** Owner and cache travel together so a new destination cannot read the previous user's cache. */
data class FreeSnapshotReadState(
    val uid: String? = null,
    val entries: Map<FreeSnapshotKey, FreeSnapshotEntry> = emptyMap()
)

/**
 * Owns every free-snapshot refresh, as a single-consumer loop.
 *
 * ### Why an actor and not a lock
 *
 * The S2 step 1 slice lost four review rounds to one shape of defect: a decision taken under a lock
 * and acted on after releasing it, with a sign-out landing in the gap. Here **all mutable state is
 * confined to the loop coroutine**, so deciding and acting are the same step by construction and
 * there is no lock boundary left to get wrong.
 *
 * The price is that the loop must never block on I/O. A fetch runs as a child job and reports back
 * as another event; if the loop awaited it, an expiry, a sign-out or a tab switch would sit
 * unhandled for the length of an HTTP call.
 */
class FreeSnapshotScheduler(
    private val fetcher: FreeSnapshotFetching,
    private val uidStream: AuthUidStream,
    /**
     * The credential session the transport would use *right now*, or null when signed out.
     *
     * Read, never awaited. Bound to `AuthTokenProvider.currentIdentityFence()` and deliberately not
     * to `AuthenticatedApiClient.captureIdentityFence()`, which is gated on `ReleaseAdmission` and
     * throws when signed out — a guard must not vanish when a release flag is off.
     */
    private val authFence: () -> AuthIdentityFence?,
    /**
     * Reports an event the loop could not process. Wired to Crashlytics in DI.
     *
     * Containment without a report is the silent-failure pattern: the loop survives, the user sees
     * a tab that quietly stopped updating, and nothing anywhere says why.
     */
    private val onEventFailure: (Throwable) -> Unit = {},
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    /**
     * Resolved lazily, on the loop coroutine. The seed is read from disk, and the only caller that
     * could reach it eagerly is `Application.onCreate` on the main thread.
     */
    private val installId: () -> String
) {
    private sealed interface Event {
        /** The user is looking at this tab and period. */
        data class Activate(val key: FreeSnapshotKey) : Event

        /** Nothing free-tier is on screen. Deadlines stay; fetches do not start. */
        data object Deactivate : Event

        /** The signed-in identity changed. Everything cached or in flight belonged to the old one. */
        data class IdentityChanged(val identity: String?) : Event

        data class FetchFinished(
            val requestId: Long,
            val key: FreeSnapshotKey,
            /**
             * The credential session this request was minted under.
             *
             * Non-null by construction: `null == null` is not a match, it is two unrelated absences,
             * and the monotonic-generation argument that makes this comparison sound does not hold
             * across them. A request is not started without a session — see [startFetch].
             */
            val fence: AuthIdentityFence,
            val result: Result<FreeSnapshot>
        ) : Event

        /** A deadline fired. *Which* one is recomputed, never remembered. */
        data object Tick : Event
    }

    private class Slot {
        var entry: FreeSnapshotEntry? = null
        var nextEligibleAt: Instant? = null
        var staleAttempt: Int = 0
    }

    private val inbox = Channel<Event>(Channel.UNLIMITED)

    // --- loop-confined; nothing outside the loop coroutine reads or writes these -----------------
    private val slots = mutableMapOf<FreeSnapshotKey, Slot>()
    private val inFlight = mutableMapOf<FreeSnapshotKey, Long>()
    private var activeKey: FreeSnapshotKey? = null
    private var identity: String? = null
    private var nextRequestId: Long = 0L

    /**
     * The earliest the *client* may talk to this endpoint again, whatever the key.
     *
     * Scheduler-wide and outside [slots] on purpose. nginx limits by address, so a `Retry-After`
     * earned on one tab and period binds every other one — held per slot, a sibling warm-up walks
     * straight through it. And it survives an identity change: the limit was levied on this device,
     * not on the session, so `slots.clear()` must not take it with the cache.
     */
    private var sharedRetryFloor: Instant? = null
    private var timer: Job? = null

    private val started = AtomicBoolean(false)

    private val _readState = MutableStateFlow(FreeSnapshotReadState())
    val readState: StateFlow<FreeSnapshotReadState> = _readState.asStateFlow()

    /**
     * Begin consuming events, and bind to the signed-in identity.
     *
     * [AuthUidStream] is shared with the access-state funnel rather than reimplemented: the caches
     * here are per-user, so the scheduler has to learn about sign-in and sign-out from the same
     * place. It replays the current user on subscribe, which arrives as an identity event like any
     * other — [handle] treats a repeat of the identity it already has as a no-op.
     */
    fun start() {
        // Single-consumer confinement is the whole basis of the design, so a second consumer would
        // not be a duplicate — it would be two coroutines mutating plain maps.
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Per-event isolation. This is a *single-consumer* loop, so an escaping throw does not
            // fail one operation — it ends the consumer, and the free tier stays dark for the life
            // of the process with the channel still accepting sends.
            for (event in inbox) {
                try {
                    handle(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation           // the scope is going away; do not swallow it
                } catch (failure: Throwable) {
                    // A completion that dies part-way is still the only thing that could have
                    // released its registration, and `inFlight` is what makes a key eligible at
                    // all — leave it claimed and that period never fetches again, not even on
                    // re-activation. Ownership is released here on exactly the same terms
                    // `onFetchFinished` would have used.
                    if (event is Event.FetchFinished && inFlight[event.key] == event.requestId) {
                        inFlight.remove(event.key)
                    }
                    onEventFailure(failure)
                    recoverWake()
                }
            }
        }
        uidStream.observe { uid -> inbox.trySend(Event.IdentityChanged(uid)) }
    }

    fun onActivated(tab: String, period: GraphPeriod) {
        inbox.trySend(Event.Activate(FreeSnapshotKey(tab, period)))
    }

    fun onDeactivated() {
        inbox.trySend(Event.Deactivate)
    }

    fun onIdentityChanged(identity: String?) {
        inbox.trySend(Event.IdentityChanged(identity))
    }

    // --- the loop --------------------------------------------------------------------------------

    /**
     * One event, one reading of the clock.
     *
     * Every step below judges the same instant. Reading it per step allowed a deadline to fall
     * *between* two readings: the fetch check saw it as still future and declined, then the re-arm
     * saw it as past, dropped it from the candidates and cancelled the timer that would have fired
     * it — leaving a slot with no cache, no request and no wake at all.
     */
    private fun handle(event: Event) {
        val now = clock()
        when (event) {
            is Event.Activate -> {
                activeKey = event.key
                slots.getOrPut(event.key) { Slot() }
            }

            Event.Deactivate -> activeKey = null

            is Event.IdentityChanged -> {
                // Firebase replays the current user on subscribe, so the same uid arrives again on
                // every cold start. Wiping the cache for that would throw away a good snapshot.
                if (event.identity != identity) {
                    identity = event.identity
                    // Nothing survives a real change: the caches are per-user and the in-flight
                    // requests were authorised as somebody else. Those requests still report back,
                    // and find their registration gone — see onFetchFinished.
                    slots.clear()
                    inFlight.clear()
                    activeKey?.let { slots[it] = Slot() }
                }
            }

            is Event.FetchFinished -> onFetchFinished(event, now)

            // Which deadline fired is never remembered, only recomputed. The pump below is the
            // whole handling.
            Event.Tick -> Unit
        }
        pump(now)
        refreshFreshness(now)
        publish()
        rearmTimer(now)
    }

    /**
     * Start whatever is due.
     *
     * `ANDROID_V2_PLAN.md:833` — only the selected data tab is active, its other three periods are
     * warmed **sequentially**, and a warm-up earns a cooldown only on a real failure. Warming is
     * what makes a period switch a cache swap instead of a spinner.
     *
     * Nothing here recomputes a deadline. A tab round-trip that recomputed would pull the deadline
     * in, which is how a spread becomes a herd and how a backoff silently resets.
     */
    private fun pump(now: Instant) {
        val active = activeKey ?: return
        if (identity == null) return                  // the endpoint is authenticated

        // The selected period is never queued behind a warm-up: it is the one on screen.
        startFetch(active, now)

        // One warm-up at a time **per tab**, in the order the next period is most likely to be
        // reached for. Scoped to the tab, because the tab is the unit the plan gives a cache and a
        // schedule to ("data 탭별 VM/cache 생존"), and iOS gives each tab its own view model with its
        // own `preloadTask` — there is no cross-tab limit there to mirror. A global limit made a
        // request left behind by a tab switch — a warm-up, or the refresh that had been on screen —
        // hold the warming of the tab now in front of the user, for as long as that request took;
        // the client sets no `callTimeout`, so nobody owns that duration.
        if (inFlight.keys.any { it != active && it.tab == active.tab }) return
        for (period in GraphPeriod.entries) {
            val key = FreeSnapshotKey(active.tab, period)
            if (key == active) continue
            // A warm-up fills a gap and never refreshes one: only the selected period is on a
            // schedule. iOS `preloadOtherPeriods` skips a cached period the same way.
            if (slots[key]?.entry != null) continue
            if (startFetch(key, now)) return
        }
    }

    private fun startFetch(key: FreeSnapshotKey, now: Instant): Boolean {
        if (inFlight.containsKey(key)) return false   // at most one request per key
        val slot = slots.getOrPut(key) { Slot() }
        sharedRetryFloor?.let { if (now < it) return false }
        slot.nextEligibleAt?.let { if (now < it) return false }

        // The session has to be the one the loop believes in, not merely *a* session. `authFence()`
        // is live while `identity` lags behind its callback, so during that gap the transport can
        // already be somebody else — and a fence minted then would match itself on the way back
        // while belonging to a user this loop has never heard of. Absent is the same problem
        // written differently: two unrelated absences are not a match, and the monotonic-generation
        // argument says nothing across them.
        val fence = authFence()?.takeIf { it.uid == identity } ?: run {
            slot.nextEligibleAt = FreeSnapshotSchedulePolicy.afterCancellation(now)
            return false
        }
        val requestId = ++nextRequestId
        inFlight[key] = requestId
        scope.launch {
            // The OkHttp client sets connect/read/write timeouts but no `callTimeout`, so a server
            // that dribbles one byte inside every read window holds the call open indefinitely —
            // and `rearmTimer` excludes an in-flight key by design, so the selected period would
            // never be re-armed. Nothing else owns this duration.
            val result = runCatching { withTimeout(FETCH_BUDGET) { fetcher.fetch(key.tab, key.period) } }
            // Report before rethrowing: this registration is *this* request's to release, and
            // leaving it behind would wedge the key for the life of the process.
            inbox.trySend(Event.FetchFinished(requestId, key, fence, result))
            // Our own deadline is not external cancellation, so it must not tear down the child.
            (result.exceptionOrNull() as? CancellationException)
                ?.takeUnless { it is TimeoutCancellationException }
                ?.let { throw it }
        }
        return true
    }

    private fun onFetchFinished(event: Event.FetchFinished, now: Instant) {
        // The rate limit is recorded first, ahead of every ownership question below, because it is
        // the one thing here that does not belong to this request. It was levied on the transport
        // by address: not on the credential session that carried it, not on the tab and period that
        // tripped it, and not on whether this completion is still the registered one. A superseded
        // answer is refused a few lines down, and recording afterwards would take the server's
        // instruction down with it.
        val retryAfterHeader = when (val error = event.result.exceptionOrNull()) {
            is AuthenticatedApiException -> error.failure.retryAfter
            // A session change discards the answer but the transport carries the limit out with it
            // — see AuthenticatedTransport.executeBound.
            is AuthIdentityChangedException -> error.retryAfter
            else -> null
        }
        val failureJitter = FreeSnapshotSchedulePolicy.jitterFor(installId(), event.key.tab)
        FreeSnapshotSchedulePolicy.retryFloorAfter(now, retryAfterHeader)?.let { stated ->
            // Jitter on top: a limiter hands every client the same value, so obeying it exactly
            // would release the whole cohort on one second.
            sharedRetryFloor = maxOf(sharedRetryFloor ?: Instant.DISTANT_PAST, stated + failureJitter)
        }

        // Holding the registration is what authorises a completion to act, and releasing it is one
        // of the things it authorises — so this is a single check, not a release followed by a
        // separate decision. Splitting them is the defect shape that cost S2 step 1 four rounds:
        // an identity round-trip (A -> signed out -> A) clears the registry while the old request
        // is still running, a replacement starts, and the old answer then carried a *matching* uid
        // — enough to land in the recreated slot and overwrite the live request's deadline.
        if (inFlight[event.key] != event.requestId) return
        inFlight.remove(event.key)

        val slot = slots[event.key] ?: return

        // The registration above catches a *uid* change, but only once its identity event has been
        // dequeued — and a same-uid credential rotation emits no event at all, because
        // `AuthUidStream` carries a bare uid and the loop dedups equal ones. The service's own
        // `requireCurrent` is the last check on the *far* side of a channel send; this is the last
        // one before `publish()`. Generations only ever increase, so if the session now equals the
        // one this request was minted under, it also equalled it when the transport captured — the
        // comparison can reject a live answer, but never admit a superseded one.
        if (authFence() != event.fence) {
            slot.nextEligibleAt = FreeSnapshotSchedulePolicy.afterCancellation(now)
            return
        }

        val snapshot = event.result.getOrElse { error ->
            // A timeout is *our* deadline expiring, not the work being withdrawn, so it earns the
            // recovery delay rather than the 5s cancel floor — otherwise a slow server would be
            // re-hit every five seconds for as long as it stayed slow.
            val withdrawn = error is CancellationException && error !is TimeoutCancellationException
            slot.nextEligibleAt = if (withdrawn) {
                FreeSnapshotSchedulePolicy.afterCancellation(now)
            } else {
                FreeSnapshotSchedulePolicy.afterFailure(now, failureJitter)
            }
            return
        }

        // Read the previous slot *before* touching the cache. Comparing afterwards would make
        // every answer look identical to the one already held.
        val previousAsOf = slot.entry?.snapshot?.asOf
        val advanced = FreeSnapshotSchedulePolicy.hasAdvanced(previousAsOf, snapshot.asOf)

        // Three outcomes, not two. A slot re-served at the same `as_of` still carries a *new*
        // `refresh_not_before` — the server derives it from its own serve-time clock — so the
        // response is replaced even though the data is identical (`ANDROID_V2_PLAN.md:836`).
        // Only a genuinely older answer is dropped, so a late reply cannot rewind the screen.
        val applied = advanced || snapshot.asOf == previousAsOf
        if (applied) {
            slot.entry = FreeSnapshotEntry(
                snapshot = snapshot,
                freshness = FreeSnapshotSchedulePolicy.freshnessOf(snapshot.asOf, now)
            )
        }

        slot.staleAttempt = when {
            // Asked, and got back nothing we could use. Scheduling from the snapshot we kept would
            // reach for a `refresh_not_before` that is already behind us, and the policy's positive
            // minimum would then turn that into a five-second re-request for as long as the server
            // keeps answering with the past. An unusable answer is no progress: back off.
            !applied -> slot.staleAttempt + 1
            // Current: the one-shot path, at the server's own hint.
            !FreeSnapshotSchedulePolicy.isQueryStale(snapshot.asOf, now) -> 0
            advanced -> 1                    // moved on but still behind: restart the ladder
            else -> slot.staleAttempt + 1    // stuck on the same slot: climb
        }
        // `snapshot` is safe to schedule from: the only branch that reads it is `staleAttempt == 0`,
        // which is reachable only when the answer was applied and is therefore what we now hold.
        slot.nextEligibleAt = FreeSnapshotSchedulePolicy.nextEligibleAt(
            snapshot = snapshot,
            now = now,
            installId = installId(),
            staleAttempt = slot.staleAttempt
        )
    }

    private fun refreshFreshness(now: Instant) {
        slots.values.forEach { slot ->
            val entry = slot.entry ?: return@forEach
            val current = FreeSnapshotSchedulePolicy.freshnessOf(entry.snapshot.asOf, now)
            if (current != entry.freshness) slot.entry = entry.copy(freshness = current)
        }
    }

    private fun publish() {
        _readState.value = FreeSnapshotReadState(
            uid = identity,
            entries = slots.mapNotNull { (key, slot) -> slot.entry?.let { key to it } }.toMap()
        )
    }

    /**
     * Wake at the earliest thing that can change on its own.
     *
     * Candidates must be strictly in the future. A deadline already past would otherwise re-arm at
     * zero and spin — which is also what excludes the key currently fetching, whose `nextEligibleAt`
     * is behind `now` by definition (it is only ever written when a request *completes*, and a
     * request only starts once it is due). Freshness deadlines always count, and are read from what
     * is displayed: nothing else in the app emits merely because time passed.
     *
     * A warm-up on cooldown gets **no** timer of its own. It is re-evaluated by [pump] on the next
     * event — an activation, a period switch, or the selected period's own refresh — so a failed
     * warm-up waits at most one refresh cycle and never less than its cooldown. iOS retries a
     * failed preload on activation alone; re-evaluating on completion too is strictly more
     * responsive and still bounded by the same 300s.
     */
    private companion object {
        /**
         * The whole-call deadline the HTTP client does not impose. Comfortably past the transport's
         * own 15s connect/read/write budgets, so it bounds a hung call without pre-empting a slow
         * but progressing one.
         */
        private val FETCH_BUDGET = 60.seconds
    }

    /**
     * Put a wake back after an event failed part-way through.
     *
     * [handle] arms the timer on its way out, so an event that throws before that point leaves the
     * loop alive with **nothing scheduled** — the consumer survives and the schedule does not, which
     * looks to the user exactly like a tab that stopped updating. Re-arming needs the clock, and the
     * clock is one of the things that can throw, so the last resort is a fixed delay that needs
     * nothing at all.
     */
    private fun recoverWake() {
        // Returning normally is not the same as having scheduled something: `rearmTimer` also
        // returns normally when it finds no candidate at all, which is exactly the state a failed
        // event tends to leave behind — no cache means no freshness deadline, and a deadline that
        // has already passed is not a candidate either. Treating that as success is how the
        // consumer stays alive with nothing to wake it.
        if (runCatching { rearmTimer(clock()) }.getOrDefault(false)) return
        timer?.cancel()
        timer = scope.launch {
            delay(FreeSnapshotSchedulePolicy.RECOVERY_DELAY)
            inbox.trySend(Event.Tick)
        }
    }

    private fun rearmTimer(now: Instant): Boolean {
        val candidates = mutableListOf<Instant>()
        // Without this the client sits past the limit with nothing to notice it lapsed.
        if (activeKey != null && identity != null) {
            sharedRetryFloor?.let { if (it > now) candidates += it }
        }
        slots.forEach { (key, slot) ->
            if (key == activeKey && identity != null) {
                slot.nextEligibleAt?.let { if (it > now) candidates += it }
            }
            slot.entry?.let { entry ->
                FreeSnapshotSchedulePolicy.nextFreshnessDeadline(entry.snapshot.asOf, now)
                    ?.let { if (it > now) candidates += it }
            }
        }
        timer?.cancel()
        val next = candidates.minOrNull() ?: return false
        timer = scope.launch {
            delay(next - now)
            inbox.trySend(Event.Tick)
        }
        return true
    }
}
