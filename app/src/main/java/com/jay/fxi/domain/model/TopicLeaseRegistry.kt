package com.jay.fxi.domain.model

import kotlin.time.Duration

/**
 * One topic's permission to receive, and when it stops.
 *
 * [expiresAtMillis] is fixed at the acknowledgement that **granted** this [leaseId] and never moves
 * afterwards. A later acknowledgement that happens to carry the same id again — the server sends
 * `active_subscriptions` as a whole-connection snapshot, so any unrelated request re-reports it —
 * is a restatement, not a new grant.
 */
data class TopicLeaseGrant(val leaseId: String, val expiresAtMillis: Long)

/**
 * What one acknowledgement's leases changed, and what to do about it.
 *
 * @param renewAfter how long to wait before re-authenticating, or `null` when nothing is eligible
 *   or the draw was out of contract. **`null` does not mean the grants were rejected** — they are
 *   applied either way, and the hard expiry they set still stands. Only the renewal is skipped.
 * @param renewalScope the topics the renewal should ask for. **One signal, not two.** A
 *   `duration 0` whose id still has its immediate attempt is in here with a [renewAfter] of zero,
 *   which is the same launch — answering "renew in 0s" *and* "re-authenticate these now"
 *   separately invites two commands for one lease and breaks "once per connection and id". A zero
 *   whose attempt is already spent is out of both the set and the wait. Found by review.
 *
 * There is no "were new grants issued" flag. iOS keeps one — `leaseGrantGeneration`, so that a
 * second enforcement of the same set does not raise a second reconnection — and this side does not
 * need it: expiring a lease ends the connection, and the registry goes with it, so there is no
 * second enforcement to suppress.
 */
data class TopicLeaseUpdate(
    val renewAfter: Duration?,
    val renewalScope: Set<String>
)

/**
 * Every live lease on one connection, and the three lifetimes that are easy to confuse.
 *
 * The renewal wait is replaced by **every** valid acknowledgement. A grant's absolute expiry is
 * fixed by the acknowledgement that issued its id and survives every restatement of it. The record
 * of which ids have already spent their one immediate re-authentication is narrowed only by an id
 * leaving `active_subscriptions` — never by a new acknowledgement arriving, and never by the
 * renewal timer being replaced.
 *
 * Folding any two of those together breaks something specific. Clearing grants per acknowledgement
 * makes a restated id look new and pushes its expiry out. Clearing the zero record with the timer
 * re-fires the immediate re-authentication in exactly the race it exists for: while the first one
 * waits for a token, an unsubscribe — which needs none and so goes out at once — is acknowledged
 * first, and its whole-connection snapshot carries the same still-expired lease as `0` again.
 *
 * Pure on purpose. It holds no clock and starts no work: it is handed an instant and answers with
 * what is due, so every judgment here is testable without a scheduler.
 *
 * **There is no way to drop one topic's grant.** An expiry ends the connection, and this goes with
 * it, so the only narrowing that happens is by acknowledgement. A version that could drop one
 * would have to remember the deadline it had just dropped: review reproduced the alternative — the
 * same lease id, restated afterwards, reads as a new grant and revives the subscription it had
 * just lost.
 */
class TopicLeaseRegistry {
    private val grants = linkedMapOf<String, TopicLeaseGrant>()
    private val immediateAttempted = mutableSetOf<String>()

    /** A read-only view, for the caller that has to decide what expired. */
    val held: Map<String, TopicLeaseGrant> get() = grants.toMap()

    /**
     * Folds one acknowledgement's leases in and says what is due.
     *
     * @param leases the leases from `active_subscriptions` — the connection's whole final state,
     *   not this request's accepted set. A topic missing from it has no lease any more.
     * @param jitter the caller's draw, `0..60`, subtracted from the lead. Out of range leaves
     *   [TopicLeaseUpdate.renewAfter] null while everything else still applies.
     */
    fun apply(
        acknowledgedAtMillis: Long,
        leases: List<TopicLeaseInput>,
        jitter: Long
    ): TopicLeaseUpdate {
        val activeIds = leases.mapTo(mutableSetOf()) { it.leaseId }
        // Narrowed to what is still active, and only here. An id that left `active_subscriptions`
        // and came back is a new question and gets its attempt again; an id that stayed is still
        // spent, whatever else this acknowledgement changed.
        immediateAttempted.retainAll(activeIds)

        val next = linkedMapOf<String, TopicLeaseGrant>()
        leases.forEach { lease ->
            val held = grants[lease.topic]
            next[lease.topic] = if (held != null && held.leaseId == lease.leaseId) {
                held
            } else {
                TopicLeaseGrant(lease.leaseId, expiryOf(acknowledgedAtMillis, lease.durationSeconds))
            }
        }
        grants.clear()
        grants.putAll(next)

        // Spent before anything asynchronous happens with it. The attempt is used up by *deciding*
        // to make it, not by its send arriving — the token wait between the two is the window this
        // record exists to cover.
        val freshZeros = leases
            .filter { it.durationSeconds == 0L && immediateAttempted.add(it.leaseId) }
            .mapTo(mutableSetOf()) { it.topic }

        // A zero whose attempt is **already spent** drops out of the renewal entirely: left in, it
        // pegs the shortest at zero so the renewal spins on every lap, and it would be asked for a
        // second time. A zero whose attempt is being made now stays in, and that is what makes the
        // immediate re-authentication and the renewal one launch rather than two.
        val eligible = leases.filterNot { it.durationSeconds == 0L && it.topic !in freshZeros }
        return TopicLeaseUpdate(
            renewAfter = eligible.takeIf { it.isNotEmpty() }
                ?.let { due -> TopicLeasePolicy.renewAfter(due.map { it.durationSeconds }, jitter) },
            renewalScope = eligible.mapTo(mutableSetOf()) { it.topic }
        )
    }

    /**
     * The topics whose absolute deadline has arrived.
     *
     * `>=`, not `>`: a lease that expires at this instant has expired. Nothing a renewal is doing —
     * a token being fetched, a send in flight, a delivery being waited on — buys the old grant any
     * more time, which is the whole point of it being absolute.
     */
    fun expiredAt(nowMillis: Long): Set<String> =
        grants.filterValues { nowMillis >= it.expiresAtMillis }.keys.toSet()

    /** The soonest deadline to watch, or `null` when nothing is held. */
    fun earliestExpiryMillis(): Long? = grants.values.minOfOrNull { it.expiresAtMillis }

    /** Everything, for a connection that has ended. */
    fun clear() {
        grants.clear()
        immediateAttempted.clear()
    }

    private companion object {
        /**
         * The instant this lease stops being honoured.
         *
         * A `duration 0` expires at the acknowledgement itself — it is the server saying the lease
         * is already over, and the immediate re-authentication is a separate answer to that, not a
         * grace period. Saturating rather than wrapping: a duration past the countable range is
         * already refused upstream, and an injected clock near the ceiling should not produce a
         * deadline in the past.
         */
        fun expiryOf(acknowledgedAtMillis: Long, durationSeconds: Long): Long {
            val millis = durationSeconds * 1_000
            return if (acknowledgedAtMillis > Long.MAX_VALUE - millis) Long.MAX_VALUE
            else acknowledgedAtMillis + millis
        }
    }
}

/**
 * One lease as the registry needs it: which topic, which id, how long.
 *
 * The same three fields as `TopicLease` in the wire layer, and deliberately a separate type: that
 * one lives beside the DTOs and this one is what the domain reasons about, so the dependency runs
 * one way only. The caller maps across the boundary, which is one line and the place where a wire
 * change would have to be noticed.
 */
data class TopicLeaseInput(
    val topic: String,
    val leaseId: String,
    val durationSeconds: Long
)
