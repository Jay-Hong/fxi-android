package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason

/**
 * One refusal a session reported, numbered by the issuer at the moment it was reported (L-4e E4a).
 *
 * Immutable, and meaningful only to the ledger that made it: another issuer neither processes nor ends it. [order] is taken from
 * the same sequence as the issuer's query starts and demands, so "started before this refusal" can be compared.
 */
internal class TopicRejectionReservation internal constructor(
    internal val owner: TopicRejectionLedger,
    val order: Long,
    val grant: TopicGrantToken,
    val reasons: List<TopicRejectionReason>
) {
    /** Whether the refusal is about access at all; one that is not leaves no history. */
    val aboutAccess: Boolean get() = TopicRejection.of(reasons) != null

    /** Whether it refuses premium; one naming both reasons does. */
    val aboutPremium: Boolean get() = TopicRejection.of(reasons) == TopicRejection.PREMIUM_REQUIRED
}

/** What the ledger holds for one grant, read under one monitor hold (L-4e E4a §3.5). */
internal data class TopicRejectionView(
    /** Access refusals reported for the grant and not yet ended, earliest first. */
    val pendingOrders: List<Long>,
    /** The latest access refusal reported for the grant, delivered or not. Not evidence that it was valid. */
    val latestReported: Long?,
    /** The latest refusal the issuer actually took over for the grant: decided, preserved as a demand, or held as a candidate. */
    val latestCurrent: Long?
)

/** What the issuer re-verified for the answer asking to rotate: when its query started and the invalidations it started under. */
internal data class TopicReapprovalQuery(val order: Long, val userInvalidations: Long)

/**
 * One re-approval issued for a grant a session is still latched on, decided under one monitor hold (L-4e E4b §3.1).
 *
 * [refusalOrder] is the premium refusal it consumed, [queryOrder] and [userInvalidations] the answer that approved it.
 */
internal data class TopicReapprovalIssue(
    val from: TopicGrantToken,
    val token: TopicGrantToken,
    val refusalOrder: Long,
    val queryOrder: Long,
    val userInvalidations: Long
)

/** How an attempt to rotate a grant for a re-approval came out (L-4e E4b §3.3). */
internal sealed interface TopicReapprovalAttempt {
    /** Nothing is owed: no premium refusal was taken over for the grant and none is on its way. */
    data object NotOwed : TopicReapprovalAttempt

    /** A re-approval is owed, but this answer cannot issue it. The summary stays, and so must whatever asks again. */
    data object Blocked : TopicReapprovalAttempt

    data class Issued(val issue: TopicReapprovalIssue) : TopicReapprovalAttempt
}

/**
 * The issuer's record of the refusals sessions report: which are still on their way, and the order of the latest reported and
 * the latest taken over for the grant currently issued (L-4e E4a §3.5).
 *
 * One JVM monitor guards everything and nothing inside it suspends, performs I/O or calls out, so a session can reserve from its
 * serial scope without waiting on the issuer's lock. Lock order is issuer mutex, then this monitor. A reservation takes its number
 * and becomes visible under the same hold, so a reader holding the monitor never sees a number without its reservation.
 *
 * A reservation ends once: [complete] or [abandon], whichever comes first, and neither repeated nor crossed changes that. The
 * summary belongs to the grant last issued; issuing another — for a new context or a re-approval — retires it, and a late
 * reservation or ending for an older grant neither brings it back nor touches the new one's.
 */
internal class TopicRejectionLedger(
    /** The issuer's shared order sequence: the next number, taken inside this ledger's monitor. */
    private val nextOrder: () -> Long
) {
    private val lock = Any()
    private val open = LinkedHashSet<TopicRejectionReservation>()
    private var summaryGrant: TopicGrantToken? = null
    private var latestReported: Long? = null
    private var latestCurrent: Long? = null
    private var latestPremiumCurrent: Long? = null

    fun reserve(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation =
        synchronized(lock) {
            val reservation = TopicRejectionReservation(this, nextOrder(), grant, reasons.toList())
            open += reservation
            if (reservation.aboutAccess && grant == summaryGrant) latestReported = maxOf(latestReported ?: 0L, reservation.order)
            reservation
        }

    /** Ends [reservation] as processed; [current] when the issuer took it over for the grant it names. */
    fun complete(reservation: TopicRejectionReservation, current: Boolean) {
        synchronized(lock) {
            if (!open.remove(reservation)) return
            if (current && reservation.aboutAccess && reservation.grant == summaryGrant) {
                latestCurrent = maxOf(latestCurrent ?: 0L, reservation.order)
                if (reservation.aboutPremium) latestPremiumCurrent = maxOf(latestPremiumCurrent ?: 0L, reservation.order)
            }
        }
    }

    /** Ends [reservation] without processing it. The history of its having been reported stays. */
    fun abandon(reservation: TopicRejectionReservation) {
        synchronized(lock) { open.remove(reservation) }
    }

    /** The issuer issued [grant]: the previous grant's summary is retired. Callers hold the issuer's mutex. */
    fun grantIssued(grant: TopicGrantToken) {
        synchronized(lock) {
            if (grant == summaryGrant) return
            // A session can only report a grant it was given, so nothing is recorded for [grant] yet.
            retireSummaryLocked(grant)
        }
    }

    /**
     * Rotates [from] for a re-approval if one is owed and [query] may issue it (L-4e E4b §2 checks 3–5, §3.3).
     *
     * Owed: [from] is the summarised grant and a premium refusal was taken over for it or is still on its way — the second so
     * that an answer landing between a refusal's demand and its hand-over being recorded cannot settle what the refusal owes.
     * Issued only when a premium refusal was taken over, [query] started after every access refusal reported for [from], and none
     * is on its way. [nextToken] is called only then, inside this hold, and the summary moves to the token it returns. A null
     * [query] asks only whether something is owed: the issuer could not re-verify the answer, or the path does not issue.
     * Callers hold the issuer's mutex.
     */
    fun rotateForTopicReapproval(
        from: TopicGrantToken,
        query: TopicReapprovalQuery?,
        nextToken: () -> TopicGrantToken
    ): TopicReapprovalAttempt {
        synchronized(lock) {
            if (from != summaryGrant) return TopicReapprovalAttempt.NotOwed
            val pending = open.filter { it.grant == from && it.aboutAccess }
            val refusal = latestPremiumCurrent
            if (refusal == null && pending.none { it.aboutPremium }) return TopicReapprovalAttempt.NotOwed
            val reported = latestReported ?: 0L
            if (query == null || refusal == null || pending.isNotEmpty() || query.order <= reported) {
                return TopicReapprovalAttempt.Blocked
            }
            val token = nextToken()
            retireSummaryLocked(token)
            return TopicReapprovalAttempt.Issued(TopicReapprovalIssue(from, token, refusal, query.order, query.userInvalidations))
        }
    }

    private fun retireSummaryLocked(grant: TopicGrantToken) {
        summaryGrant = grant
        latestReported = null
        latestCurrent = null
        latestPremiumCurrent = null
    }

    fun owns(reservation: TopicRejectionReservation): Boolean = reservation.owner === this

    fun view(grant: TopicGrantToken): TopicRejectionView = synchronized(lock) {
        val summarised = grant == summaryGrant
        TopicRejectionView(
            pendingOrders = open.filter { it.grant == grant && it.aboutAccess }.map { it.order },
            latestReported = latestReported.takeIf { summarised },
            latestCurrent = latestCurrent.takeIf { summarised }
        )
    }
}
