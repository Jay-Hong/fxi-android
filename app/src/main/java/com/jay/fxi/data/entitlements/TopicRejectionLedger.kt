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

/**
 * The issuer's record of the refusals sessions report: which are still on their way, and the order of the latest reported and
 * the latest taken over for the grant currently issued (L-4e E4a §3.5).
 *
 * One JVM monitor guards everything and nothing inside it suspends, performs I/O or calls out, so a session can reserve from its
 * serial scope without waiting on the issuer's lock. Lock order is issuer mutex, then this monitor. A reservation takes its number
 * and becomes visible under the same hold, so a reader holding the monitor never sees a number without its reservation.
 *
 * A reservation ends once: [complete] or [abandon], whichever comes first, and neither repeated nor crossed changes that. The
 * summary belongs to the grant last issued; issuing another retires it, and a late reservation or ending for an older grant
 * neither brings it back nor touches the new one's.
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
            summaryGrant = grant
            latestReported = null
            latestCurrent = null
        }
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
