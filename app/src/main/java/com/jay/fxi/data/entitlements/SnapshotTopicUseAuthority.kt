package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime

/**
 * A new protected use may start under [fence] only when the issued token is that fence's grant and still stands, and the user
 * axis is allowed (L-4e E2a design v4 §2.1). Reads this one snapshot and nothing else.
 *
 * The general use does not consult the capability axis, so a capability-only hold never stops the rest. With the contest off,
 * no KRX use is issued; with it on, the separate KRX use checks the capability epoch and invalidations as well.
 */
internal fun TopicAccessSnapshot.acquireUse(fence: TopicSessionFence): TopicUseLifetime? {
    val token = facts.token ?: return null
    if (token != fence.grant || !facts.tokenStanding || !facts.userAllowed) return null
    return TopicUseLifetime(token, userInvalidations)
}

/** A use started under [lifetime] may still happen: the same token still stands, the user axis is allowed, and nothing withdrew it in between. */
internal fun TopicAccessSnapshot.admitsUse(lifetime: TopicUseLifetime): Boolean =
    facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations

/**
 * The one OFF guard of every KRX consumer (S6-2b-1b design r5 §2, D-7): C-1 applies only while the contest has issued a permit.
 * Off, the issuer always publishes `krxPermitSeq = 0`, so each consumer keeps its HEAD answer. A consumer passes the permit of the
 * very read it decides from — the snapshot's, or the send marker's for a REST hand-over — and never writes `> 0` itself.
 */
internal fun krxConsumerGuard(permitSeq: Long): Boolean = permitSeq > 0

/**
 * The capability as a session send may carry it (design r5 §5A, E (a), D-9): the guard and `capabilityAllowed`, from this one
 * snapshot. Only the KRX use below decides with it: never the graph gate (its HEAD admits KRX on `capabilityAllowed` while off), and
 * the send read does not return it on its own (D-4).
 */
internal fun TopicAccessSnapshot.krxEffective(): Boolean = krxConsumerGuard(krxPermitSeq) && facts.capabilityAllowed

/**
 * A KRX use: the general [lifetime] it rides on, with the capability epoch and capability invalidations it was issued under
 * (design r5 §3, OD-2). Kept apart from [TopicUseLifetime], whose public shape does not change. Another record epoch, or a capability
 * hold that came and went in between, withdraws it.
 */
internal data class KrxUseLifetime(
    val lifetime: TopicUseLifetime,
    val krxCapabilityEpoch: String?,
    val capabilityInvalidations: Long
)

/**
 * The KRX use a send under [lifetime] carries on this snapshot: [lifetime] admitted, the capability effective, and a record epoch to
 * name it by (design r5 §5A.3, the issue half of `krxUse`).
 */
internal fun TopicAccessSnapshot.krxUseFor(lifetime: TopicUseLifetime): KrxUseLifetime? {
    if (!admitsUse(lifetime) || !krxEffective()) return null
    val epoch = facts.recordFence?.krxCapabilityEpoch ?: return null
    return KrxUseLifetime(lifetime, epoch, capabilityInvalidations)
}

/**
 * A new KRX use under [fence], from this snapshot alone: [acquireUse] with the capability effective and a record epoch (design r5
 * §4, SUA row).
 */
internal fun TopicAccessSnapshot.acquireKrxUse(fence: TopicSessionFence): KrxUseLifetime? =
    acquireUse(fence)?.let { krxUseFor(it) }

/**
 * Whether [use] may still happen now: its general lifetime admitted, the capability effective, and the record epoch and capability
 * invalidations it was issued under (design r5 §4, SUA row; §5A.3, the judgement half of `krxUse`).
 */
internal fun TopicAccessSnapshot.admitsKrxUse(use: KrxUseLifetime): Boolean =
    admitsUse(use.lifetime) && krxEffective() && use.krxCapabilityEpoch != null &&
        use.krxCapabilityEpoch == facts.recordFence?.krxCapabilityEpoch &&
        use.capabilityInvalidations == capabilityInvalidations

/**
 * What a KRX send carries: this snapshot's record epoch and permit, never null and with no permit guard (design r5 §2 ④, D-20).
 */
private fun TopicAccessSnapshot.krxSendMarker(): KrxSendMarker = KrxSendMarker(facts.recordFence?.krxCapabilityEpoch, krxPermitSeq)

/** What a WebSocket attempt carrying KRX reads at its send (design r5 §5C): three values from one snapshot read (D-4). */
internal data class KrxSendAdmission(
    /** The general judgement, exactly [TopicUseAuthority.admits]: it alone decides whether the attempt is sent. */
    val admitted: Boolean,
    val krxUse: KrxUseLifetime?,
    val marker: KrxSendMarker
)

/** What a REST bootstrap issue of KRX reads (design r5 §5B, at the issue): three values from one snapshot read (D-4). */
internal data class KrxSendAcquisition(
    /** The general use, exactly [TopicUseAuthority.acquire]: null stops the issue as it does at HEAD. */
    val lifetime: TopicUseLifetime?,
    val krxUse: KrxUseLifetime?,
    val marker: KrxSendMarker
)

/**
 * The issuer's published access as a KRX send asks it (design r5 §3, D-3 (i′)). Internal, so its members may name internal types; the
 * session receives it through its internal constructor, never by down-casting a [TopicUseAuthority].
 *
 * Each call reads the published snapshot once and answers from that read alone.
 */
internal interface KrxSendReadAuthority : TopicUseAuthority {
    /** A new KRX use under [fence], or null. */
    fun acquireKrx(fence: TopicSessionFence): KrxUseLifetime?

    /** Whether [use] may still happen now. */
    fun admitsKrx(use: KrxUseLifetime): Boolean

    /** A WebSocket attempt under [lifetime] that carries KRX, read at its send. Replaces [admits] for that attempt alone. */
    fun admitsWithKrx(lifetime: TopicUseLifetime): KrxSendAdmission

    /** A REST bootstrap issue of KRX under [fence]. Replaces [acquire] for that issue alone. */
    fun acquireWithKrx(fence: TopicSessionFence): KrxSendAcquisition
}

/** The issuer's published snapshot, read once per question. */
internal class SnapshotTopicUseAuthority(private val snapshot: () -> TopicAccessSnapshot) : KrxSendReadAuthority {
    override fun acquire(fence: TopicSessionFence): TopicUseLifetime? = snapshot().acquireUse(fence)
    override fun admits(lifetime: TopicUseLifetime): Boolean = snapshot().admitsUse(lifetime)
    override fun acquireKrx(fence: TopicSessionFence): KrxUseLifetime? = snapshot().acquireKrxUse(fence)
    override fun admitsKrx(use: KrxUseLifetime): Boolean = snapshot().admitsKrxUse(use)

    override fun admitsWithKrx(lifetime: TopicUseLifetime): KrxSendAdmission {
        val read = snapshot()
        return KrxSendAdmission(read.admitsUse(lifetime), read.krxUseFor(lifetime), read.krxSendMarker())
    }

    override fun acquireWithKrx(fence: TopicSessionFence): KrxSendAcquisition {
        val read = snapshot()
        return KrxSendAcquisition(read.acquireUse(fence), read.acquireKrxUse(fence), read.krxSendMarker())
    }
}
