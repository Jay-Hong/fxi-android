package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.acquireKrxUse
import com.jay.fxi.data.entitlements.admitsKrxUse
import com.jay.fxi.data.entitlements.purge.KrxMemoryCause
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import com.jay.fxi.data.entitlements.purge.krxMemorySelections
import com.jay.fxi.data.remote.TopicSessionFence
import java.util.Collections
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class AlertTapClassification { KRX, NON_KRX, UNKNOWN }
internal enum class AlertTapUnknownReason { MISSING, EMPTY, UNREGISTERED, MALFORMED, CONFLICT, CONTEXT_UNKNOWN }
internal enum class AlertTapReceipt { HANDLED, UNHANDLED, REJECTED }

/** Wire values; the owner defensively freezes both sets on capture. */
internal data class AlertTapMetadata(
    val source: String? = null,
    val asset: String? = null,
    val tab: String? = null,
    val leftSource: String? = null,
    val leftAsset: String? = null,
    val rightSource: String? = null,
    val rightAsset: String? = null,
    val wrongTypeKeys: Set<String> = emptySet(),
    val typeConflict: Boolean = false,
    val presentKeys: Set<String> = emptySet(),
    val rawType: String? = null,
    val rawCustomType: String? = null,
    val rawSettingId: String? = null
)

/** Captured before decoding. Missing provenance is never completed from a later publication. */
internal data class AlertTapContext(
    val identity: AuthIdentityFence?,
    val ingressRevision: Long,
    val userEndSequence: Long,
    val capabilityEndSequence: Long,
    val userAccessEpoch: String?,
    val krxCapabilityEpoch: String?,
    val scope: KrxMemoryScope?
) {
    companion object {
        fun capture(snapshot: TopicAccessSnapshot, live: AuthIdentityFence?): AlertTapContext {
            val facts = snapshot.facts
            val record = facts.recordFence?.takeIf {
                live != null && it.ownerUid == live.uid && !facts.recordUnconfirmed &&
                    facts.binding?.ownerUid == live.uid && facts.binding.authGeneration == live.authGeneration
            }
            val user = record?.userAccessEpoch?.takeUnless { facts.userContextUncertain || it.isBlank() }
            val capability = record?.krxCapabilityEpoch?.takeUnless { facts.capabilityContextUncertain || it.isBlank() }
            val token = facts.token
            val binding = facts.binding
            val issued = facts.issuedFor
            val fence = if (live != null && user != null && token != null &&
                binding?.ownerUid == live.uid && binding.authGeneration == live.authGeneration &&
                issued?.identity == binding && issued.access.ownerUid == live.uid &&
                issued.access.userAccessEpoch == user && facts.tokenStanding) {
                TopicSessionFence(live, user, token)
            } else null
            val scope = fence?.let { owner -> snapshot.acquireKrxUse(owner)?.let { use ->
                KrxMemoryScope(owner, use, snapshot.revision,
                    snapshot.lastUserEnd?.sequence ?: 0L, snapshot.lastCapabilityEnd?.sequence ?: 0L)
            } }
            return AlertTapContext(live, snapshot.revision, snapshot.lastUserEnd?.sequence ?: 0L,
                snapshot.lastCapabilityEnd?.sequence ?: 0L, user, capability, scope)
        }
    }
}

internal data class AlertTapTicket(val ownerNonce: String, val sequence: Long)

internal sealed interface AlertTapCaptureResult {
    data class CAPTURED(val ticket: AlertTapTicket) : AlertTapCaptureResult
    data object NOT_ALERT : AlertTapCaptureResult
    data object REJECTED : AlertTapCaptureResult
}

internal data class PendingTapPurgeReport(
    val removedKrx: Int = 0,
    val removedUser: Int = 0,
    val retainedNonKrx: Int = 0,
    val unknownClassification: Int = 0,
    val unknownContext: Int = 0,
    val unhandled: Int = 0,
    val failure: Int = 0,
    val retainedKrx: Int = 0,
    val withheldKrx: Int = 0
) {
    /** Tap uncertainty only; retained intent and disk/journal settlement are separate. */
    val memoryComplete: Boolean get() = unknownClassification == 0 && unknownContext == 0 && failure == 0
}

/** Published immutable inventory values; delivery bookkeeping stays private to the owner. */
internal data class PendingAlertTap(
    val event: AlertEvent?,
    val metadata: AlertTapMetadata,
    val context: AlertTapContext,
    val classification: AlertTapClassification = AlertTapClassification.UNKNOWN,
    val reason: AlertTapUnknownReason? = null
)

private data class TapClassification(val value: AlertTapClassification, val reason: AlertTapUnknownReason? = null)

/** Exact protocol tuples, intentionally independent of display names and of settings repositories. */
private object AlertTapClassifier {
    private val exchanges = setOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val references = setOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private val krx = "krx" to "usd-krw-futures"

    fun classify(event: AlertEvent?, raw: AlertTapMetadata): TapClassification {
        if (raw.typeConflict) return unknown(AlertTapUnknownReason.CONFLICT)
        if (raw.wrongTypeKeys.any { it == "notification_type" || it == "type" }) {
            return unknown(AlertTapUnknownReason.MALFORMED)
        }
        val family = when (event) {
            is AlertEvent.SettingTriggered -> event.family
            is AlertEvent.FamilyRefreshNeeded -> event.family
            AlertEvent.RefreshNeeded -> AlertFamily.BANK
            else -> return unknown(AlertTapUnknownReason.UNREGISTERED)
        }
        if (family == AlertFamily.BANK) return TapClassification(AlertTapClassification.NON_KRX)
        val fields = if (family == AlertFamily.SOURCE) {
            linkedMapOf("source" to raw.source, "asset" to raw.asset)
        } else {
            linkedMapOf("tab" to raw.tab, "left_source" to raw.leftSource, "left_asset" to raw.leftAsset,
                "right_source" to raw.rightSource, "right_asset" to raw.rightAsset)
        }
        if (fields.keys.any { it in raw.wrongTypeKeys }) return unknown(AlertTapUnknownReason.MALFORMED)
        if (fields.values.any { it == null }) return unknown(AlertTapUnknownReason.MISSING)
        if (fields.values.any { it!!.isEmpty() }) return unknown(AlertTapUnknownReason.EMPTY)
        if (family == AlertFamily.SOURCE) {
            val tuple = raw.source to raw.asset
            return when {
                tuple == krx -> TapClassification(AlertTapClassification.KRX)
                raw.source in exchanges && raw.asset == "usdt-krw" -> TapClassification(AlertTapClassification.NON_KRX)
                else -> unknown(AlertTapUnknownReason.UNREGISTERED)
            }
        }
        val left = raw.leftSource to raw.leftAsset
        val right = raw.rightSource to raw.rightAsset
        fun exchange(pair: Pair<String?, String?>) = pair.first in exchanges && pair.second == "usdt-krw"
        fun reference(pair: Pair<String?, String?>, asset: String) = pair.first in references && pair.second == asset
        val valid = left != right && when (raw.tab) {
            "usd" -> (left == krx || reference(left, "usd-krw")) && (right == krx || reference(right, "usd-krw"))
            "jpy" -> reference(left, "jpy-krw") && reference(right, "jpy-krw")
            "eur" -> reference(left, "eur-krw") && reference(right, "eur-krw")
            "tether" -> exchange(left) && (exchange(right) || right == krx ||
                right.first in setOf("hana", "kb", "investing") && right.second == "usd-krw")
            else -> false
        }
        if (!valid) return unknown(AlertTapUnknownReason.UNREGISTERED)
        return TapClassification(if (left == krx || right == krx) AlertTapClassification.KRX else AlertTapClassification.NON_KRX)
    }

    private fun unknown(reason: AlertTapUnknownReason) = TapClassification(AlertTapClassification.UNKNOWN, reason)
}

/**
 * Process-memory custody. Every lock body is pure: no PAC reads, suppliers or consumer callbacks.
 * A claim is the linearization point. Later revocation cannot undo an already accepted callback.
 */
internal class PendingAlertTapOwner(private val lock: ReentrantLock = ReentrantLock()) {
    private enum class Delivery { READY, QUEUED, RESERVED, ORDER_WAITING, CLAIMED, UNHANDLED }
    private class Entry(
        val ticket: AlertTapTicket,
        val event: AlertEvent?,
        val metadata: AlertTapMetadata,
        val context: AlertTapContext,
        var classification: TapClassification = TapClassification(AlertTapClassification.UNKNOWN, AlertTapUnknownReason.MISSING),
        var classified: Boolean = false,
        var delivery: Delivery = Delivery.READY,
        var claim: Long = 0L,
        var reservation: Long = 0L,
        var version: Long = 0L,
        var executing: Boolean = false,
        var failureReason: String? = null
    )

    private var contextVersion = 0L
    private var detachSequence = 0L
    private var reservationSequence = 0L
    private val nonce = UUID.randomUUID().toString()
    private var sequence = 0L
    private var claimSequence = 0L
    private val entries = linkedMapOf<AlertTapTicket, Entry>()
    @Volatile private var pending: Map<AlertTapTicket, PendingAlertTap> = emptyMap()

    /** All mutation still shares one owner lock; unrelated tickets do not invalidate each other. */
    private inline fun <T> stateTurn(action: () -> T): T = lock.withLock {
        try { action() } finally { publishPending() }
    }

    private fun advance(entry: Entry) {
        check(entry.version != Long.MAX_VALUE)
        entry.version++
    }

    private fun advanceContext() {
        check(contextVersion != Long.MAX_VALUE)
        contextVersion++
    }

    private fun publishPending() {
        pending = Collections.unmodifiableMap(entries.mapValuesTo(linkedMapOf()) { (_, entry) ->
            PendingAlertTap(entry.event, entry.metadata, entry.context, entry.classification.value,
                entry.classification.reason ?: if (contextUnknown(entry)) AlertTapUnknownReason.CONTEXT_UNKNOWN else null)
        })
    }
    // Keep distinct retirement publications for late capture/classification. No event bytes live here.
    private val proofs = mutableListOf<TopicAccessSnapshot>()
    private var latest: TopicAccessSnapshot? = null
    private var latestLive: AuthIdentityFence? = null
    private var observedIdentity: AuthIdentityFence? = null
    private val retiredIdentities = mutableSetOf<AuthIdentityFence>()
    private var removedKrx = 0
    private var removedUser = 0
    private var failures = 0

    fun capture(event: AlertEvent?, metadata: AlertTapMetadata, context: AlertTapContext): AlertTapCaptureResult = stateTurn {
        if (event is AlertEvent.PendingTap) return@stateTurn AlertTapCaptureResult.REJECTED
        if (event == null && metadata.rawType == null && metadata.rawCustomType == null &&
            !metadata.typeConflict && metadata.wrongTypeKeys.none { it == "type" || it == "notification_type" }) {
            return@stateTurn AlertTapCaptureResult.NOT_ALERT
        }
        if (sequence == Long.MAX_VALUE) return@stateTurn AlertTapCaptureResult.REJECTED
        val ticket = AlertTapTicket(nonce, ++sequence)
        val frozen = metadata.copy(
            wrongTypeKeys = Collections.unmodifiableSet(metadata.wrongTypeKeys.toSet()),
            presentKeys = Collections.unmodifiableSet(metadata.presentKeys.toSet())
        )
        entries[ticket] = Entry(ticket, event, frozen, context)
        applyProofs()
        AlertTapCaptureResult.CAPTURED(ticket)
    }

    fun classify(ticket: AlertTapTicket): AlertTapClassification = stateTurn {
        val entry = entries[ticket] ?: return@stateTurn AlertTapClassification.UNKNOWN
        if (!entry.classified) {
            entry.classification = AlertTapClassifier.classify(entry.event, entry.metadata)
            entry.classified = true
            advance(entry)
        }
        val result = entry.classification.value
        applyProofs()
        result
    }

    /** Called by the bridge's queued auth observer, never from the auth callback itself. */
    fun identityChanged(identity: AuthIdentityFence?) = stateTurn {
        if (observedIdentity != identity) {
            observedIdentity?.let { retiredIdentities.add(it) }
            observedIdentity = identity
            advanceContext()
        }
        applyProofs()
    }

    fun reconcile(snapshot: TopicAccessSnapshot, live: AuthIdentityFence?): PendingTapPurgeReport = stateTurn {
        observe(snapshot, live)
        applyProofs()
        reportLocked()
    }

    private fun observe(snapshot: TopicAccessSnapshot, live: AuthIdentityFence?) {
        var changed = false
        val current = latest
        if (current == null || snapshot.revision >= current.revision) {
            changed = current != snapshot || latestLive != live
            latest = snapshot
            latestLive = live
        }
        val last = proofs.lastOrNull()
        val record = snapshot.facts.recordFence
        if ((snapshot.lastUserEnd != null || snapshot.lastCapabilityEnd != null || record != null) &&
            (last == null || last.lastUserEnd != snapshot.lastUserEnd || last.lastCapabilityEnd != snapshot.lastCapabilityEnd ||
                last.facts.recordFence != record || last.facts.recordUnconfirmed != snapshot.facts.recordUnconfirmed ||
                last.facts.userContextUncertain != snapshot.facts.userContextUncertain ||
                last.facts.capabilityContextUncertain != snapshot.facts.capabilityContextUncertain)) {
            changed = true
            val facts = snapshot.facts
            proofs.add(snapshot.copy(facts = facts.copy(
                userBlocks = Collections.unmodifiableSet(facts.userBlocks.toSet()),
                capabilityBlocks = Collections.unmodifiableSet(facts.capabilityBlocks.toSet())
            )))
        }
        if (changed) advanceContext()
    }

    private fun userRetired(context: AlertTapContext, snapshot: TopicAccessSnapshot): Boolean {
        if (snapshot.revision <= context.ingressRevision) return false
        val identity = context.identity ?: return false
        val end = snapshot.lastUserEnd
        if (end != null && end.sequence > context.userEndSequence && end.ownerUid == identity.uid) {
            val binding = end.binding
            if (binding?.ownerUid == identity.uid && binding.authGeneration == identity.authGeneration) return true
            if (binding == null && !context.userAccessEpoch.isNullOrBlank() && end.namespace == context.userAccessEpoch) return true
        }
        val facts = snapshot.facts
        val record = facts.recordFence ?: return false
        return !facts.recordUnconfirmed && !facts.userContextUncertain && record.ownerUid == identity.uid &&
            !context.userAccessEpoch.isNullOrBlank() && !record.userAccessEpoch.isNullOrBlank() &&
            record.userAccessEpoch != context.userAccessEpoch
    }

    private fun capabilityRetired(context: AlertTapContext, snapshot: TopicAccessSnapshot): Boolean {
        if (snapshot.revision <= context.ingressRevision) return false
        val scope = context.scope
        if (scope != null && !scopeMatches(context)) return false
        if (scope != null) return krxMemorySelections(snapshot, setOf(scope), 0L, 0L, false).any {
            it.cause == KrxMemoryCause.CAPABILITY_END && scope in it.scopes
        }
        // p=0: a confirmed ingress namespace can be selected, but a later grant is never attached.
        val identity = context.identity ?: return false
        val epoch = context.krxCapabilityEpoch?.takeIf { it.isNotBlank() } ?: return false
        val end = snapshot.lastCapabilityEnd
        if (end != null && end.sequence > context.capabilityEndSequence && end.ownerUid == identity.uid && end.namespace == epoch &&
            (end.binding == null || end.binding.ownerUid == identity.uid && end.binding.authGeneration == identity.authGeneration)) return true
        val facts = snapshot.facts
        val record = facts.recordFence ?: return false
        return !facts.recordUnconfirmed && !facts.capabilityContextUncertain && record.ownerUid == identity.uid &&
            !record.krxCapabilityEpoch.isNullOrBlank() && record.krxCapabilityEpoch != epoch
    }

    private fun applyProofs() {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.delivery == Delivery.CLAIMED) continue
            val context = entry.context
            val identity = context.identity
            val liveEnded = identity != null && latest?.let {
                it.revision >= context.ingressRevision && latestLive != identity
            } == true
            when {
                identity in retiredIdentities || liveEnded || proofs.any { userRetired(context, it) } -> {
                    iterator.remove()
                    removedUser++
                }
                entry.classified && entry.classification.value == AlertTapClassification.KRX &&
                    proofs.any { capabilityRetired(context, it) } -> {
                    iterator.remove()
                    removedKrx++
                }
            }
        }
    }

    private fun scopeMatches(context: AlertTapContext): Boolean {
        val scope = context.scope ?: return true
        return scope.owner.identity == context.identity && scope.owner.userAccessEpoch == context.userAccessEpoch &&
            scope.use.krxCapabilityEpoch == context.krxCapabilityEpoch && scope.use.lifetime.grant == scope.owner.grant &&
            scope.acquiredRevision == context.ingressRevision && scope.userEndSequence == context.userEndSequence &&
            scope.capabilityEndSequence == context.capabilityEndSequence
    }

    private fun contextUnknown(entry: Entry): Boolean {
        val context = entry.context
        val identity = context.identity
        if (identity == null || identity.uid.isBlank() || identity.authGeneration < 0L ||
            context.userAccessEpoch.isNullOrBlank()) return true
        if (context.ingressRevision < 0L || context.userEndSequence < 0L || context.capabilityEndSequence < 0L ||
            !scopeMatches(context)) return true
        return entry.classification.value == AlertTapClassification.KRX && context.scope == null
    }

    private fun eligible(entry: Entry, snapshot: TopicAccessSnapshot, live: AuthIdentityFence?): Boolean {
        if (!entry.classified || entry.classification.value == AlertTapClassification.UNKNOWN || contextUnknown(entry) ||
            entry.context.identity != live || entry.event == null) return false
        return entry.classification.value != AlertTapClassification.KRX ||
            entry.context.scope?.let { snapshot.admitsKrxUse(it.use) } == true
    }

    /** Ticket order belongs to custody, including carriers already queued before a retry/detach. */
    private fun hasPendingPredecessor(entry: Entry, snapshot: TopicAccessSnapshot, live: AuthIdentityFence?): Boolean =
        entries.values.any { earlier ->
            earlier.ticket.sequence < entry.ticket.sequence && earlier.delivery != Delivery.UNHANDLED &&
                eligible(earlier, snapshot, live) && !userRetired(earlier.context, snapshot) &&
                (earlier.classification.value != AlertTapClassification.KRX || !capabilityRetired(earlier.context, snapshot))
        }

    fun dispatchable(snapshot: TopicAccessSnapshot, live: AuthIdentityFence?): List<AlertTapTicket> = stateTurn {
        observe(snapshot, live)
        applyProofs()
        val current = checkNotNull(latest)
        // A yielded carrier is not pumped repeatedly while its earlier known intent is pending.
        // UNKNOWN, UNHANDLED, unknown context and temporarily withheld KRX are not barriers.
        entries.values.filter { it.delivery == Delivery.ORDER_WAITING && eligible(it, current, latestLive) &&
            !hasPendingPredecessor(it, current, latestLive) }.forEach {
            it.delivery = Delivery.READY
            advance(it)
        }
        entries.values.filter { it.delivery == Delivery.READY && eligible(it, current, latestLive) }.map { it.ticket }
    }

    fun markQueued(ticket: AlertTapTicket): Boolean = stateTurn {
        val entry = entries[ticket] ?: return@stateTurn false
        if (entry.delivery != Delivery.READY) return@stateTurn false
        entry.delivery = Delivery.QUEUED
        advance(entry)
        true
    }

    fun releaseQueued(ticket: AlertTapTicket) = stateTurn {
        entries[ticket]?.takeIf { it.delivery == Delivery.QUEUED }?.let {
            it.delivery = Delivery.READY
            advance(it)
        }
    }

    /** Collector cancellation releases transit state; custody and unhandled dispositions survive. */
    fun detach() = stateTurn {
        check(detachSequence != Long.MAX_VALUE)
        detachSequence++
        entries.values.filter { it.delivery == Delivery.QUEUED || it.delivery == Delivery.RESERVED }.forEach {
            it.delivery = Delivery.READY
            it.claim = 0L
            it.reservation = 0L
            advance(it)
        }
        applyProofs()
    }

    private data class Reservation(
        val ticket: AlertTapTicket, val id: Long, val version: Long,
        val contextVersion: Long, val detachSequence: Long,
        val entry: Entry, val classified: Boolean, val classification: AlertTapClassification,
        val contextUnknown: Boolean, val snapshot: TopicAccessSnapshot?
    )
    private data class ClaimDecision(val userEnded: Boolean, val capabilityEnded: Boolean, val eligible: Boolean)
    private data class Claim(val entry: Entry, val id: Long, val event: AlertEvent)
    private sealed interface ClaimAttempt {
        data class Accepted(val claim: Claim) : ClaimAttempt
        data object RETRY : ClaimAttempt
        data object CANCELLED : ClaimAttempt
        data object REJECTED : ClaimAttempt
    }

    fun consume(
        ticket: AlertTapTicket,
        snapshot: TopicAccessSnapshot,
        live: AuthIdentityFence?,
        action: (AlertEvent) -> AlertTapReceipt
    ): AlertTapReceipt = consumeCurrent(ticket, { snapshot to live }, action)

    /**
     * One invocation keeps its original detach generation across at most two attempts. Final
     * acquisition never waits on an external sample. Unrelated ticket turns cannot invalidate it;
     * continuing context movement yields REJECTED with custody retained for a later ready signal.
     */
    internal fun consumeCurrent(
        ticket: AlertTapTicket,
        readAccess: () -> Pair<TopicAccessSnapshot, AuthIdentityFence?>,
        action: (AlertEvent) -> AlertTapReceipt
    ): AlertTapReceipt {
        val lifetime = lock.withLock { detachSequence }
        repeat(2) {
            val reservation = reserve(ticket, lifetime) ?: return AlertTapReceipt.REJECTED
            val attempt = try {
                val first = readAccess()
                val decision = decide(reservation, first.first, first.second)
                // No owner work or blocking acquisition is allowed after this last sample.
                val final = readAccess()
                if (first.first.revision != final.first.revision || first.second != final.second) {
                    ClaimAttempt.RETRY
                } else {
                    tryClaim(reservation, final.first, final.second, decision)
                }
            } catch (error: Throwable) {
                releaseReservation(reservation)
                recordFailure()
                throw error
            }
            when (attempt) {
                ClaimAttempt.RETRY -> if (!releaseReservation(reservation)) return AlertTapReceipt.REJECTED
                ClaimAttempt.CANCELLED -> {
                    releaseReservation(reservation)
                    return AlertTapReceipt.REJECTED
                }
                ClaimAttempt.REJECTED -> return AlertTapReceipt.REJECTED
                is ClaimAttempt.Accepted -> return execute(attempt.claim, action)
            }
        }
        return AlertTapReceipt.REJECTED
    }

    private fun reserve(ticket: AlertTapTicket, lifetime: Long): Reservation? = stateTurn {
        if (lifetime != detachSequence) return@stateTurn null
        applyProofs()
        val entry = entries[ticket] ?: return@stateTurn null
        if (entry.delivery == Delivery.RESERVED || entry.delivery == Delivery.CLAIMED ||
            entry.delivery == Delivery.UNHANDLED) return@stateTurn null
        if (reservationSequence == Long.MAX_VALUE) return@stateTurn null
        entry.reservation = ++reservationSequence
        entry.delivery = Delivery.RESERVED
        advance(entry)
        Reservation(ticket, entry.reservation, entry.version, contextVersion, lifetime, entry,
            entry.classified, entry.classification.value, contextUnknown(entry), latest)
    }

    /** False ends this invocation: detach must never be crossed by taking a fresh reservation. */
    private fun releaseReservation(reservation: Reservation): Boolean = stateTurn {
        entries[reservation.ticket]?.takeIf {
            it.delivery == Delivery.RESERVED && it.reservation == reservation.id
        }?.let {
            it.reservation = 0L
            it.delivery = Delivery.READY
            advance(it)
        }
        applyProofs()
        reservation.detachSequence == detachSequence
    }

    private fun decide(
        reservation: Reservation,
        read: TopicAccessSnapshot,
        live: AuthIdentityFence?
    ): ClaimDecision {
        val previous = reservation.snapshot
        val snapshot = if (previous != null && previous.revision > read.revision) previous else read
        val identity = live
        val entry = reservation.entry
        val context = entry.context
        val userEnded = context.identity != null && snapshot.revision >= context.ingressRevision &&
            context.identity != identity || userRetired(context, snapshot)
        val capabilityEnded = reservation.classified && reservation.classification == AlertTapClassification.KRX &&
            capabilityRetired(context, snapshot)
        val eligible = reservation.classified && reservation.classification != AlertTapClassification.UNKNOWN &&
            !reservation.contextUnknown && context.identity == identity && entry.event != null &&
            (reservation.classification != AlertTapClassification.KRX || context.scope?.let {
                snapshot.admitsKrxUse(it.use)
            } == true)
        return ClaimDecision(userEnded, capabilityEnded, eligible)
    }

    private fun tryClaim(
        reservation: Reservation,
        snapshot: TopicAccessSnapshot,
        live: AuthIdentityFence?,
        decision: ClaimDecision
    ): ClaimAttempt {
        // No owner-lock wait is permitted after the final external read.
        if (!lock.tryLock()) return ClaimAttempt.RETRY
        try {
            if (detachSequence != reservation.detachSequence) return ClaimAttempt.CANCELLED
            val entry = entries[reservation.ticket] ?: return ClaimAttempt.REJECTED
            if (entry !== reservation.entry || entry.delivery != Delivery.RESERVED || entry.reservation != reservation.id) {
                return ClaimAttempt.CANCELLED
            }
            if (entry.version != reservation.version || contextVersion != reservation.contextVersion) return ClaimAttempt.RETRY
            advance(entry)
            entry.reservation = 0L
            if (decision.userEnded || decision.capabilityEnded) {
                entries.remove(reservation.ticket)
                if (decision.userEnded) removedUser++ else removedKrx++
                observe(snapshot, live)
                return ClaimAttempt.REJECTED
            }
            if (!decision.eligible || claimSequence == Long.MAX_VALUE) {
                observe(snapshot, live)
                entry.delivery = Delivery.READY
                return ClaimAttempt.REJECTED
            }
            val current = latest?.takeIf { it.revision > snapshot.revision } ?: snapshot
            if (hasPendingPredecessor(entry, current, live)) {
                observe(snapshot, live)
                entry.delivery = Delivery.ORDER_WAITING
                return ClaimAttempt.REJECTED
            }
            entry.claim = ++claimSequence
            entry.delivery = Delivery.CLAIMED
            entry.executing = true
            // Remember this publication after final claim; later retirement cannot undo it.
            observe(snapshot, live)
            return ClaimAttempt.Accepted(Claim(entry, entry.claim, checkNotNull(entry.event)))
        } finally {
            publishPending()
            lock.unlock()
        }
    }

    private fun execute(claim: Claim, action: (AlertEvent) -> AlertTapReceipt): AlertTapReceipt {
        // No owner or bridge lock is held; no suspension occurs between claim and callback.
        val receipt = try {
            action(claim.event)
        } catch (error: Throwable) {
            stateTurn {
                failures++
                claim.entry.failureReason = error.javaClass.name
                releaseClaim(claim)
                applyProofs()
            }
            throw error
        }
        return stateTurn {
            val entry = entries[claim.entry.ticket]
            if (entry !== claim.entry || entry.claim != claim.id || entry.delivery != Delivery.CLAIMED) {
                return@stateTurn AlertTapReceipt.REJECTED
            }
            when (receipt) {
                AlertTapReceipt.HANDLED -> entries.remove(entry.ticket)
                AlertTapReceipt.UNHANDLED -> entry.delivery = Delivery.UNHANDLED
                AlertTapReceipt.REJECTED -> entry.delivery = Delivery.READY
            }
            entry.claim = 0L
            entry.executing = false
            advance(entry)
            applyProofs()
            receipt
        }
    }

    fun recordFailure() = stateTurn { failures++ }

    private fun releaseClaim(claim: Claim) {
        val entry = claim.entry
        if (entries[entry.ticket] === entry && entry.claim == claim.id) {
            entry.delivery = Delivery.READY
            entry.claim = 0L
            entry.executing = false
            advance(entry)
        }
    }

    /** Issued-and-absent is terminal, so a delayed Activity marker can safely be cleared. */
    fun hasCustody(ticket: AlertTapTicket): Boolean = lock.withLock {
        ticket.ownerNonce == nonce && ticket.sequence > 0L && ticket.sequence <= sequence
    }

    fun report(): PendingTapPurgeReport = lock.withLock { reportLocked() }

    private fun reportLocked() = PendingTapPurgeReport(
        removedKrx = removedKrx, removedUser = removedUser,
        retainedNonKrx = entries.values.count { it.classified && it.classification.value == AlertTapClassification.NON_KRX },
        unknownClassification = entries.values.count { !it.classified || it.classification.value == AlertTapClassification.UNKNOWN },
        unknownContext = entries.values.count { contextUnknown(it) },
        unhandled = entries.values.count { it.delivery == Delivery.UNHANDLED }, failure = failures,
        retainedKrx = entries.values.count { it.classified && it.classification.value == AlertTapClassification.KRX },
        withheldKrx = entries.values.count { entry ->
            entry.classified && entry.classification.value == AlertTapClassification.KRX && !contextUnknown(entry) &&
                latest?.let { !eligible(entry, it, latestLive) } == true
        }
    )
}
