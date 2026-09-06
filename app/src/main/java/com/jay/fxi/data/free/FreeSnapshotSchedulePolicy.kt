package com.jay.fxi.data.free

import com.jay.fxi.domain.model.FreeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** How a snapshot's age reads to the user. `ANDROID_V2_PLAN.md` S2 freshness row. */
enum class FreeSnapshotFreshness { FRESH, DELAYED, UNAVAILABLE }

/**
 * When the next free-snapshot fetch may run, and how old a snapshot is allowed to look.
 *
 * Pure on purpose. The loop that owns the state must hold its lock across *decide and act*
 * together, so the deciding half lives here where it can be tested without any of that.
 */
object FreeSnapshotSchedulePolicy {

    /** Past this age nothing is rendered. At the normal cadence an `as_of` is at most ~1h old. */
    val MAX_AGE: Duration = 24.hours

    /** Past this age the value is still shown, but marked as delayed. */
    val DELAYED_AGE: Duration = 2.hours

    /** The server published nothing new yet: ask again soon, then less often. */
    private val STALE_BACKOFF = listOf(20.seconds, 40.seconds, 80.seconds)

    /** Ladder exhausted, no cache at all, or the fetch itself failed. */
    val RECOVERY_DELAY: Duration = 300.seconds

    /**
     * Fallback when the server sent no `refresh_not_before`.
     *
     * `as_of` is the `HH:30` basis, and the server's own hint is the *next* `HH:31:00` — one hour
     * on plus the sixty seconds it allows for the hourly job to finish and propagate. Using a bare
     * `+1h` would aim at `HH:30`, arriving before the job that produces the thing being asked for.
     */
    private val MISSING_RNB_FALLBACK: Duration = 1.hours + 60.seconds

    /** A target already in the past must still be a wait, not an immediate re-fetch. */
    private val MIN_DELAY: Duration = 5.seconds

    private val WHITESPACE_RUN = Regex("\\s+")

    private const val JITTER_MIN_SECONDS = 10
    private const val JITTER_SPAN_SECONDS = 21 // 10..30 inclusive

    /** The server publishes on the `HH:30` grid in Seoul time, and pins `as_of` to it. */
    private val BASIS_ZONE = TimeZone.of("Asia/Seoul")
    private const val BASIS_MINUTE = 30

    /**
     * The most recent publish slot at or before [instant].
     *
     * Mirrors the server's own `basis_as_of`: at or after `HH:30` the slot is this hour's, before
     * it the previous hour's.
     */
    fun basisOf(instant: Instant): Instant {
        val local = instant.toLocalDateTime(BASIS_ZONE)
        val thisHourSlot = local.date.atTime(local.hour, BASIS_MINUTE).toInstant(BASIS_ZONE)
        return if (instant >= thisHourSlot) thisHourSlot else thisHourSlot - 1.hours
    }

    /**
     * Is the answer we hold behind the slot the server should have published by now?
     *
     * **Not** the same question as "did this answer advance on the last one", and **not** the same
     * question as how the age reads to the user. All three can disagree:
     *  - the same slot returned at `10:45` is still current — one-shot at the hint, no ladder,
     *  - a first-ever success at `11:31` carrying `10:30` is behind — retry at 20s despite there
     *    being nothing to compare it against,
     *  - `09:30 -> 10:30` at `11:31` advanced and is *still* behind — restart the ladder at 20s.
     *
     * Each of those can read FRESH to the user, so [freshnessOf] cannot stand in for this either.
     */
    fun isQueryStale(asOf: Instant, now: Instant): Boolean = basisOf(asOf) < basisOf(now)

    /**
     * Did this answer move to a newer slot than the one already cached?
     *
     * Only used to decide whether the stale ladder restarts. A first answer counts as movement;
     * an equal or older `as_of` does not, so a late reply cannot rewind the cache.
     */
    fun hasAdvanced(previousAsOf: Instant?, asOf: Instant): Boolean =
        previousAsOf == null || asOf > previousAsOf

    /**
     * Is this snapshot too old to render?
     *
     * **Age only.** A device clock behind the server's makes a fresh `as_of` look like the future;
     * treating that as expiry would blank the screen for a correct snapshot. A genuinely future
     * `as_of` is the server's problem, and it already refuses to publish one.
     */
    fun isExpired(asOf: Instant, now: Instant): Boolean = now - asOf >= MAX_AGE

    fun freshnessOf(asOf: Instant, now: Instant): FreeSnapshotFreshness {
        val age = now - asOf
        return when {
            age >= MAX_AGE -> FreeSnapshotFreshness.UNAVAILABLE
            age >= DELAYED_AGE -> FreeSnapshotFreshness.DELAYED
            else -> FreeSnapshotFreshness.FRESH
        }
    }

    /**
     * The next instant at which this snapshot's *appearance* changes on its own.
     *
     * The loop wakes here as well as for fetches. Without it a screen with no new data and no
     * user input would sit at FRESH straight through the two-hour boundary — nothing else in the
     * system emits merely because time passed.
     */
    fun nextFreshnessDeadline(asOf: Instant, now: Instant): Instant? = when {
        now - asOf < DELAYED_AGE -> asOf + DELAYED_AGE
        now - asOf < MAX_AGE -> asOf + MAX_AGE
        else -> null
    }

    /**
     * Deterministic per (install, tab) offset inside the jitter window.
     *
     * Deterministic so a reschedule for the same slot lands on the same instant — a fresh draw on
     * every recompute would let repeated activations walk the deadline earlier. Keyed without the
     * period because the server hands every client the same `refresh_not_before`, so the spread is
     * per client, and all four periods of one tab riding the same offset is the intent, not a bug.
     */
    fun jitterFor(installId: String, tab: String): Duration {
        val slot = Math.floorMod("$installId|$tab".hashCode(), JITTER_SPAN_SECONDS)
        return (JITTER_MIN_SECONDS + slot).seconds
    }

    /**
     * When the next fetch may run, after one has *completed*.
     *
     * Recomputing on activation instead would let a tab switch pull the deadline in, which is how
     * a spread becomes a herd and how a backoff silently resets.
     */
    fun nextEligibleAt(
        snapshot: FreeSnapshot,
        now: Instant,
        installId: String,
        staleAttempt: Int
    ): Instant {
        // A stale answer never consults `refresh_not_before`. The server computes that from its
        // own serve-time clock, so a stale canonical still carries the *next* hour's hint — obey
        // it here and the 20/40/80s ladder would silently become an hour's wait.
        if (staleAttempt > 0) return now + backoffFor(staleAttempt)

        val target = (snapshot.refreshNotBefore ?: (snapshot.asOf + MISSING_RNB_FALLBACK)) +
            jitterFor(installId, snapshot.tab)
        return maxOf(target, now + MIN_DELAY)
    }

    /**
     * The three date forms RFC 9110 §5.6.7 obliges a recipient to accept.
     *
     * IMF-fixdate is what anything modern sends; the other two are here because refusing a legal
     * header means re-requesting early, which is the outcome `Retry-After` exists to prevent.
     */
    private fun parseHttpDate(raw: String, now: Instant): Instant? {
        runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME) }
            .getOrNull()?.let { return Instant.fromEpochSeconds(it.toEpochSecond()) }

        // RFC 850 carries two digits, and the rule is about the reconstructed *instant*, not a year
        // range: a reading more than 50 years ahead means the most recent past year with the same
        // digits. Both century readings are parsed rather than one, for two reasons. A single
        // `currentYear - 50` window is wrong by up to a year at the edge — in 2026 it refuses
        // 2076-01, only 49 years away. And the weekday is cross-checked during parsing, so fixing
        // the century first makes a correct date fail before any correction can run: 76 resolves to
        // 1976, whose 1 January is a Thursday, and the header says Wednesday.
        val nowUtc = java.time.Instant.ofEpochSecond(now.epochSeconds).atZone(ZoneOffset.UTC)
        val horizon = nowUtc.plusYears(50)
        val readings = listOf(nowUtc.year - 50, nowUtc.year + 50).mapNotNull { base ->
            runCatching {
                ZonedDateTime.parse(
                    raw,
                    DateTimeFormatterBuilder()
                        .appendPattern("EEEE, dd-MMM-")
                        .appendValueReduced(ChronoField.YEAR, 2, 2, base)
                        .appendPattern(" HH:mm:ss zzz")
                        .toFormatter(Locale.US)
                )
            }.getOrNull()
        }
        if (readings.isNotEmpty()) {
            // The latest reading still inside the horizon; if every reading is past it, the rule
            // sends us back a century to the most recent year with those digits.
            val chosen = readings.filter { !it.isAfter(horizon) }.maxOrNull()
                ?: readings.min().minusYears(100)
            return Instant.fromEpochSeconds(chosen.toEpochSecond())
        }

        // asctime pads a single-digit day with a space and carries no zone at all. Collapsing the
        // run of spaces is steadier than a padded pattern, and UTC is the only reading available.
        return runCatching {
            LocalDateTime
                .parse(
                    raw.replace(WHITESPACE_RUN, " "),
                    DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)
                )
                .toInstant(ZoneOffset.UTC)
        }.getOrNull()?.let { Instant.fromEpochSeconds(it.epochSecond) }
    }

    /**
     * After a fetch *failed*, or when there is no cache at all. Not the stale ladder, and not the
     * server's floor either — that one is above every key, so [retryFloorAfter] owns it.
     *
     * [jitter] is D12's other clause. Without it every client that failed inside the same second —
     * an nginx reload, a deploy — retries in lockstep at `+300s`, `+600s`, `+900s`, re-forming the
     * herd that [jitterFor] exists to break up on the success path.
     */
    fun afterFailure(now: Instant, jitter: Duration = Duration.ZERO): Instant =
        now + RECOVERY_DELAY + jitter
    // Jitter sits on *top* of whichever bound won, never instead of it. A rate limiter hands every
    // client the same `Retry-After`, so honouring it exactly would release the whole cohort on the
    // same second — the herd the header was meant to break up. Landing later than the floor still
    // respects the floor.

    /**
     * The instant the server asked us to wait until, or null when it asked for nothing credible.
     *
     * Deliberately not folded into any per-slot deadline. A rate limit is levied on the transport by
     * address, so it belongs to the *client*, not to the tab and period that happened to trip it:
     * held in a slot, a sibling period walks straight through it and a sign-out erases it along with
     * the cache. The scheduler holds it instead, above every key.
     *
     * **Uncapped, deliberately.** Two ceilings were tried and both were the same mistake: whether
     * the limit is one publish cycle or [MAX_AGE], any value above it is a *valid* wait the client
     * silently shortens, and shortening a floor is precisely what D12 forbids. The tempting
     * justification — that past [MAX_AGE] the snapshot already reads UNAVAILABLE so nobody can tell
     * — confuses two independent things: what the *screen* shows is `now - as_of`, while this
     * governs whether a *request* goes out. The server can tell, and the server is who the floor
     * protects. An implausible header therefore costs a tab that stops re-requesting until the
     * process restarts, which is the safe direction to fail; shortening costs requests against a
     * server that explicitly asked for none. Only an unparseable or non-positive value is refused,
     * and D12 says an absent floor is not terminal — it leaves [RECOVERY_DELAY] standing.
     */
    fun retryFloorAfter(now: Instant, retryAfterHeader: String?): Instant? {
        val raw = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // `delay-seconds` is an arbitrarily long non-negative integer (RFC 9110 §10.2.3), so a
        // perfectly valid header can exceed `Long`. Falling through to "no floor" there is the one
        // outcome the header exists to prevent — it re-requests *early*, against a server that just
        // asked for the opposite. A value too large to represent still means "wait a very long
        // time", so it saturates instead. Malformed syntax is a different thing and does fall
        // through: D12 says an absent floor is not terminal, it leaves RECOVERY_DELAY standing.
        if (raw.all { it in '0'..'9' }) {
            val seconds = raw.toLongOrNull() ?: return Instant.DISTANT_FUTURE
            return if (seconds <= 0L) null else now + seconds.seconds
        }

        // The other legal form. RFC 9110 §5.6.7 requires a recipient to accept all three, and the
        // obsolete two are not ambiguous — RFC 850's two-digit year has a defined 50-year rule — so
        // refusing them would drop a floor that is perfectly well specified.
        return parseHttpDate(raw, now)?.takeIf { it > now }
    }

    /**
     * After a fetch was *withdrawn* rather than answered.
     *
     * The plan is explicit that a cancellation earns neither the ladder nor the recovery delay, so
     * this is not a backoff — it is only a floor against a cancel storm. The work still has to be
     * picked up again, and nothing else will pick it up: `AuthIdentityChangedException` is a
     * `CancellationException`, and a credential-generation change carries **no identity event**
     * because the uid did not move.
     */
    fun afterCancellation(now: Instant): Instant = now + MIN_DELAY

    /**
     * Delay for the nth consecutive stale answer, counting **from 1** for the first one.
     *
     * Failures do not use this ladder at all — see [afterFailure].
     */
    fun backoffFor(staleAttempt: Int): Duration = when {
        staleAttempt <= 0 -> Duration.ZERO
        staleAttempt <= STALE_BACKOFF.size -> STALE_BACKOFF[staleAttempt - 1]
        else -> RECOVERY_DELAY
    }

}
