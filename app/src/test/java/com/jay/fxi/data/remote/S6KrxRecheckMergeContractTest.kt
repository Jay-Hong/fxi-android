package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Test

/** Real WS ACK and real REST Unsupported, then the existing TGD/PAC reservation and single schedule. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxRecheckMergeContractTest {
    private data class PairSources(val first: () -> Unit, val second: () -> Unit, val marker: KrxSendMarker)

    private suspend fun pair(s: S6KrxCleanupRig, restFirst: Boolean): PairSources {
        s.r.processRefusals = false
        s.live(ack = false, rates = false)
        val request = s.r.first(); val call = s.issue()
        val marker = KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, call.read.krxPermitSeq)
        s.eq("WS and REST started in the same p", s.r.snapshot.krxPermitSeq, call.read.krxPermitSeq)
        val gate = s.f.InputGate("BootstrapAnswered")
        try {
            // Produce the actual REST answer before WS withdrawal can cancel its body job.
            s.f.reply(call, KrxA1bFixture.unsupported)
            s.eq("original REST answer is queued", 1, gate.count)
            if (restFirst) {
                gate.release()
                s.eq("REST callback arrives first", 1, s.r.delayedRefusals.size)
                s.r.ack(request, reject = true)
            } else {
                s.r.ack(request, reject = true)
                s.eq("WS callback arrives first", 1, s.r.delayedRefusals.size)
                gate.release()
            }
            s.eq("two actual source callbacks, no fake PAC refusal", 2, s.r.delayedRefusals.size)
            val actions = s.r.delayedRefusals.toList(); s.r.delayedRefusals.clear()
            return PairSources(actions[0], actions[1], marker)
        } finally { gate.close() }
    }
    private suspend fun full(s: S6KrxCleanupRig): List<Any?> {
        val h = s.r.h; val schedule = h.recheckSchedule()
        return listOf(s.r.pac.krxContestView, h.recheckDemandSnapshot(), schedule.revision,
            schedule.readPrivate("consecutiveAttempts"), schedule.readPrivate("earliestAllowedAtMillis"),
            schedule.readPrivate("pendingIntent"), s.r.pac.heldLossCandidateCount(), h.store.rotationAttempts,
            h.store.record, h.source.calls.size, s.r.pac.recheckDiagnostics().owedIntent)
    }
    private suspend fun merge(s: S6KrxCleanupRig, restFirst: Boolean, fault: String?) {
        val sources = pair(s, restFirst); val epoch = s.epoch()
        val gate = s.waitQuery()
        when (fault) {
            "identity" -> s.r.h.source.identityNulls = 1
            "rotation" -> s.r.h.store.rotationFailures = 1
            "unreadable" -> s.r.h.store.loadFailures = 1
        }
        sources.first(); s.r.drain()
        s.eq("first real source hands p over", sources.marker.permitSeq, s.r.pac.krxContestView?.rejectedPermit)
        s.eq("AWAITING first handover window", KrxContestPhase.AWAITING, s.r.pac.krxContestView?.state?.phase)
        if (fault == "identity") s.eq("identity failure keeps original epoch", epoch, s.epoch())
        val before = full(s)
        sources.second(); s.r.drain()
        s.eq("duplicate source adds no demand, arm, floor, rotation or fetch", before, full(s))
        s.eq("each source retains its own original marker", listOf(sources.marker, sources.marker), s.r.issuer.reserved.map { it.marker })
        s.eq("first handover remains p", sources.marker.permitSeq, s.r.pac.krxContestView?.rejectedPermit)
        val fetches = s.r.h.source.calls.size
        if (fault != "rotation") {
            s.r.until(s.c("one initial FE reaches transport"), 60_000) { s.r.h.source.calls.size > fetches || s.r.h.source.gates.isEmpty() }
            // A fault may require later recovery/seal queries; only this held first handover is counted.
            s.yes("one held initial query, no duplicate physical fetch", !gate.isCompleted)
            val inFlight = s.r.h.source.calls.size
            s.r.advance(100)
            s.eq("same held query is not duplicated", inFlight, s.r.h.source.calls.size)
        }
    }

    @Test fun q01_wsThenRest_handsOverSamePermitOnce() = s63Test("Q01", "normal", "identity-held") { s, column ->
        merge(s, restFirst = false, fault = if (column == "identity-held") "identity" else null)
    }
    @Test fun q02_restThenWs_handsOverSamePermitOnce() = s63Test("Q02", "same-epoch", "rotated-epoch") { s, column ->
        // Same-epoch H1-prime makes the permit guard observable independently of an epoch mismatch.
        merge(s, restFirst = true, fault = if (column == "same-epoch") "identity" else null)
    }
    @Test fun q03_unreadableIdentityAndRotationFailure_keepOneHandover() = s63Test("Q03", "identity", "rotation", "unreadable", "rotated-old-epoch") { s, fault ->
        merge(s, restFirst = fault == "rotation", fault = if (fault == "rotated-old-epoch") null else fault)
        val marker = s.marker(); val prior = s.r.h.source.calls.size
        // An actual epoch rotation can legitimately require seal recovery; an old marker must never
        // be rebound to the new epoch. Reuse real source history, without imposing a lifetime fetch=1 cap.
        val reservations = s.r.issuer.reserved
        s.yes("both old source reservations preserved", reservations.size == 2)
        if (fault == "rotated-old-epoch") s.yes("old epoch rejection window actually exists",
            s.epoch() != reservations.first().marker?.krxCapabilityEpoch)
        if (s.epoch() != reservations.first().marker?.krxCapabilityEpoch) {
            s.yes("current marker identifies a different epoch", marker.krxCapabilityEpoch != reservations.first().marker?.krxCapabilityEpoch)
            s.eq("late old reservation produced no extra query", prior, s.r.h.source.calls.size)
        }
    }
    @Test fun q04_queryStartedBeforeDemand_cannotSettleIt() = s63Test("Q04", "complete-before", "complete-after", "floor-boundary") { s, column ->
        val sources = pair(s, restFirst = true)
        val old = s.waitQuery()
        val before = s.r.h.source.calls.size
        val query = s.r.h.parts.scope.launch { s.r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        s.r.drain()
        s.eq("query actually started before rejection demand", before + 1, s.r.h.source.calls.size)
        s.yes("old query remains in flight", !query.isCompleted)
        if (column == "complete-before") {
            old.complete(EntitlementsOutcome.StableActive(true)); s.r.drain()
            s.yes("old query completed before source demand", query.isCompleted)
        }
        val followup = s.waitQuery()
        s.r.h.source.identityNulls = 1
        sources.first(); s.r.drain()
        val demand = s.r.h.recheckDemandSnapshot()
        s.yes("new demand stands after old query start", demand != null)
        val schedule = s.r.h.recheckSchedule()
        val floor = schedule.readPrivate("earliestAllowedAtMillis") as? Long
        val intent = s.r.pac.recheckDiagnostics().owedIntent
        s.eq("demand keeps FE strength", RefreshIntent.FORCE_ENTITLEMENTS, intent)
        sources.second(); s.r.drain()
        s.eq("duplicate preserves demand order", demand, s.r.h.recheckDemandSnapshot())
        s.eq("duplicate preserves floor", floor, schedule.readPrivate("earliestAllowedAtMillis"))
        if (column != "complete-before") { old.complete(EntitlementsOutcome.StableActive(true)); s.r.drain() }
        s.yes("old query cannot answer a later demand", s.r.pac.recheckDiagnostics().owedIntent != null)
        if (column == "floor-boundary") s.yes("actual demand floor exists", floor != null)
        if (column == "floor-boundary" && floor != null && floor > s.r.clock.nowMillis()) {
            val calls = s.r.h.source.calls.size
            s.r.advance(floor - s.r.clock.nowMillis() - 1)
            s.eq("no follow-up before floor", calls, s.r.h.source.calls.size)
            s.r.advance(1)
        }
        s.r.until(s.c("legitimate later query is not lost"), 60_000) { s.r.h.source.gates.isEmpty() }
        s.yes("later query is held separately", !followup.isCompleted)
    }
}
