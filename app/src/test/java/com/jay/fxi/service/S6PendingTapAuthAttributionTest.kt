package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.source
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.sourceEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

/**
 * S6-3c-fix (`s6_3c_fix_contracts.r2.md`): a NON_KRX tap whose ingress saw a valid, non-contradictory identity but no
 * published USER epoch is attributed by the auth boundary (C02a), retired by epoch-free USER evidence (C10a) and not
 * counted as CONTEXT_UNKNOWN (C12a). KRX never receives a later grant (C13). Ingress context comes from the production
 * [AlertTapContext.capture] over [TopicAccessSnapshot.INITIAL].
 */
class S6PendingTapAuthAttributionTest {
    private fun bank() = AlertEvent.SettingTriggered(77)

    private fun S6PendingAlertTapRig.live(column: String): AuthIdentityFence = need(column, g.r.identity, "premise: rig has a live identity")

    /** The granted snapshot the rig reached; INITIAL-ingress rows rely on it carrying no USER/CAP end yet. */
    private fun S6PendingAlertTapRig.granted(column: String): TopicAccessSnapshot {
        val base = g.snapshot
        need(column, base.facts.recordFence?.userAccessEpoch?.takeIf { it.isNotBlank() }, "premise: granted snapshot has a USER epoch")
        eq(column, "premise: granted snapshot has no USER end", null, base.lastUserEnd)
        eq(column, "premise: granted snapshot has no CAPABILITY end", null, base.lastCapabilityEnd)
        yes(column, "premise: granted revision is past INITIAL ingress", base.revision > 1)
        return base
    }

    private fun coldContext(live: AuthIdentityFence?) = AlertTapContext.capture(TopicAccessSnapshot.INITIAL, live)

    private fun TopicAccessSnapshot.withUserEnd(sequence: Long, binding: EntitlementsIdentity?, ownerUid: String?, namespace: String?,
        reason: TopicAccessEndReason = TopicAccessEndReason.SEALED) =
        copy(revision = revision + 1, lastUserEnd = TopicAccessEnd(sequence, reason, binding, ownerUid, namespace))

    private fun S6PendingAlertTapRig.captureOn(owner: PendingAlertTapOwner, event: AlertEvent?, metadata: AlertTapMetadata,
        context: AlertTapContext, column: String, classify: Boolean = true): AlertTapTicket {
        val result = owner.capture(event, metadata, context)
        val t = need(column, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "capture transfers actual custody")
        val held = need(column, inventory(owner)[t], "target is positively owned inside the pending window")
        eq(column, "immutable raw event", event, held.event)
        eq(column, "immutable metadata", metadata, held.metadata)
        eq(column, "ingress context held unchanged", context, held.context)
        if (classify) owner.classify(t)
        return t
    }

    private fun S6PendingAlertTapRig.consumeAt(t: AlertTapTicket, snapshot: TopicAccessSnapshot, live: AuthIdentityFence?,
        owner: PendingAlertTapOwner = this.owner): AlertTapReceipt = owner.consume(t, snapshot, live) { delivered += it; AlertTapReceipt.HANDLED }

    /** A second, scope-less bus over a fresh owner whose snapshot supplier starts at INITIAL (r2 D-3). */
    private class ColdBus(val p: S6PendingAlertTapRig, live: AuthIdentityFence) {
        val owner = PendingAlertTapOwner()
        var current: TopicAccessSnapshot = TopicAccessSnapshot.INITIAL
        var snapshotReads = 0
        val bus = AlertEventBus(owner, true, { true }, { snapshotReads++; current }, { live })
        var job: Job? = null
        var consumer: AutoCloseable? = null
        fun ingest(column: String): AlertTapTicket {
            val result = AlertTapEntry.intakeWarm(S6TypedTapExtras(mapOf("type" to "rate_alert", "setting_id" to "77")), bus.pendingTapIntake())
            return p.need(column, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "real entry captures the INITIAL-context tap")
        }
        fun startConsumer(test: TestScope) {
            job = test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                bus.events.collect { event -> bus.consumePendingTap(event, p::bankConsumer) }
            }
            test.runCurrent()
            consumer = bus.registerPendingTapConsumer()
            test.runCurrent()
        }
        fun close() { consumer?.close(); job?.cancel() }
    }

    private fun S6PendingAlertTapRig.assertColdCustody(cb: ColdBus, t: AlertTapTicket, column: String): AlertTapContext {
        val held = need(column, inventory(cb.owner)[t], "target is positively owned before the consumer exists")
        eq(column, "raw BANK event retained", bank(), held.event)
        eq(column, "raw BANK metadata retained", AlertTapMetadata(rawType = "rate_alert", rawSettingId = "77"), held.metadata)
        eq(column, "ingress revision was INITIAL", 0L, held.context.ingressRevision)
        eq(column, "ingress USER end sequence was INITIAL", 0L, held.context.userEndSequence)
        eq(column, "ingress CAP end sequence was INITIAL", 0L, held.context.capabilityEndSequence)
        eq(column, "ingress saw no USER epoch", null, held.context.userAccessEpoch)
        eq(column, "ingress saw no CAP epoch", null, held.context.krxCapabilityEpoch)
        eq(column, "ingress saw no KRX scope", null, held.context.scope)
        eq(column, "BANK is NON_KRX", AlertTapClassification.NON_KRX, held.classification)
        eq(column, "C12a: auth-attributed BANK has no CONTEXT_UNKNOWN reason", null, held.reason)
        eq(column, "nothing executed before a consumer exists", 0, delivered.size)
        return held.context
    }

    @Test fun f01_recordPublishedAfterRealIngressRunsExactlyOnce() = s63cTest("F01", "warm") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val cb = ColdBus(p, live)
        try {
            val t = cb.ingest(col)
            runCurrent()
            val ctx = p.assertColdCustody(cb, t, col)
            cb.current = base
            p.eq(col, "C12a: no unknown context after the record publication", 0, cb.owner.reconcile(base, live).unknownContext)
            p.eq(col, "ingress context is not filled from the later record", ctx, p.need(col, p.inventory(cb.owner)[t], "still owned").context)
            cb.startConsumer(this)
            p.eq(col, "BANK consumer ran exactly once", listOf<AlertEvent>(bank()), p.delivered)
            p.yes(col, "HANDLED tap leaves custody", t !in p.inventory(cb.owner))
            p.eq(col, "a late consume cannot run it again", AlertTapReceipt.REJECTED, p.consumeAt(t, base, live, cb.owner))
            p.eq(col, "still exactly once", 1, p.delivered.size)
        } finally { cb.close() }
    }

    @Test fun f02_ownerLevelRecordPublishedAfterIngressConsumesOnce() = s63cTest("F02", "main") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val cb = ColdBus(p, live)
        try {
            val t = cb.ingest(col)
            val ctx = p.assertColdCustody(cb, t, col)
            cb.current = base
            p.eq(col, "C12a: not counted as unknown context", 0, cb.owner.reconcile(base, live).unknownContext)
            p.eq(col, "C02a: dispatchable after the record publication", listOf(t), cb.owner.dispatchable(base, live))
            val held = p.need(col, p.inventory(cb.owner)[t], "still owned before consumption")
            p.eq(col, "ingress context never completed from the record", ctx, held.context)
            p.eq(col, "C12a: reason remains absent after publication", null, held.reason)
            p.eq(col, "consumed", AlertTapReceipt.HANDLED, p.consumeAt(t, base, live, cb.owner))
            p.yes(col, "HANDLED leaves custody", t !in p.inventory(cb.owner))
            p.eq(col, "second consume rejected", AlertTapReceipt.REJECTED, p.consumeAt(t, base, live, cb.owner))
            p.eq(col, "exactly one execution", listOf<AlertEvent>(bank()), p.delivered)
        } finally { cb.close() }
    }

    @Test fun f03_f04_identityTransitionsRetireAuthAttributedTap() = s63cTest("F03F04", "A-B", "A-null-A", "A-A") { p, col ->
        val live = p.live(col); p.granted(col)
        val ctx = coldContext(live)
        p.eq(col, "premise: no USER epoch at ingress", null, ctx.userAccessEpoch)
        val t = p.capture(bank(), AlertTapMetadata(), ctx, column = col)
        p.eq(col, "premise: auth-attributed before the transition", null, p.owned(t, col).reason)
        when (col) {
            "A-B" -> p.identity(AuthIdentityFence("user-b", live.authGeneration + 1))
            "A-null-A" -> { p.identity(null); p.identity(AuthIdentityFence(live.uid, live.authGeneration + 1)) }
            else -> p.identity(AuthIdentityFence(live.uid, live.authGeneration + 1))
        }
        val report = p.report()
        p.yes(col, "old tap removed by USER retirement", t !in p.inventory() && report.removedUser >= 1)
        p.eq(col, "never executed", AlertTapReceipt.REJECTED, p.consume(t))
        p.eq(col, "no execution", 0, p.delivered.size)
    }

    @Test fun f05_f06_userEndAfterIngressRetires() = s63cTest("F05F06",
        "unbound-same-uid", "bound-same-identity", "unbound-before-classify", "bound-before-classify",
        "unbound-blank-epoch", "unbound-whitespace-epoch") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val ctx = when (col) {
            "unbound-blank-epoch" -> coldContext(live).copy(userAccessEpoch = "")
            "unbound-whitespace-epoch" -> coldContext(live).copy(userAccessEpoch = "  ")
            else -> coldContext(live)
        }
        val beforeClassify = col.endsWith("before-classify")
        val t = p.capture(bank(), AlertTapMetadata(), ctx, classify = !beforeClassify, column = col)
        p.owned(t, col)
        val binding = if (col.startsWith("bound")) EntitlementsIdentity(live.uid, live.authGeneration) else null
        val ended = base.withUserEnd(1, binding, live.uid, "ns-ended",
            reason = if (binding != null) TopicAccessEndReason.SIGNED_OUT else TopicAccessEndReason.SEALED)
        p.yes(col, "premise: end revision is past ingress", ended.revision > ctx.ingressRevision)
        p.yes(col, "premise: end sequence is past ingress", 1L > ctx.userEndSequence)
        p.eq(col, "premise: live identity is the ingress generation", ctx.identity, live)
        p.owner.reconcile(ended, live)
        if (col == "unbound-before-classify") p.yes(col, "premise: unclassified tap is still inside the window", t in p.inventory())
        if (beforeClassify) p.owner.classify(t)
        p.yes(col, "C10a: retired by a USER end after ingress", t !in p.inventory())
        p.eq(col, "never executed", AlertTapReceipt.REJECTED, p.consumeAt(t, ended, live))
        p.eq(col, "no execution", 0, p.delivered.size)
    }

    @Test fun f07_f08_f09_unrelatedUserEndsKeepTap() = s63cTest("F07F08F09", "other-generation", "other-uid", "before-ingress") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val oldEnd = TopicAccessEnd(1, TopicAccessEndReason.SEALED, null, live.uid, "ns-old")
        val ctx = if (col == "before-ingress") {
            AlertTapContext.capture(TopicAccessSnapshot.INITIAL.copy(revision = 1, lastUserEnd = oldEnd), live)
        } else coldContext(live)
        if (col == "before-ingress") p.eq(col, "premise: ingress already saw end 1", 1L, ctx.userEndSequence)
        p.eq(col, "premise: no USER epoch at ingress", null, ctx.userAccessEpoch)
        val t = p.capture(bank(), AlertTapMetadata(), ctx, column = col)
        val later = when (col) {
            "other-generation" -> base.withUserEnd(1, EntitlementsIdentity(live.uid, live.authGeneration - 1), live.uid, "ns-other")
            "other-uid" -> base.withUserEnd(1, null, "uid-other", "ns-other")
            else -> base.copy(revision = base.revision + 1, lastUserEnd = oldEnd)
        }
        p.yes(col, "premise: later revision is past ingress", later.revision > ctx.ingressRevision)
        p.owner.reconcile(later, live)
        p.yes(col, "unrelated end keeps the tap", t in p.inventory())
        p.eq(col, "still consumable", AlertTapReceipt.HANDLED, p.consumeAt(t, later, live))
        p.eq(col, "exactly one execution", 1, p.delivered.size)
    }

    @Test fun f10_krxNeverReceivesLaterGrant() = s63cTest("F10", "main") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val t = p.capture(sourceEvent(), source(), coldContext(live), column = col)
        p.eq(col, "KRX classified", AlertTapClassification.KRX, p.owned(t, col).classification)
        val report = p.owner.reconcile(base, live)
        p.eq(col, "C13: KRX stays counted as unknown context", 1, report.unknownContext)
        p.eq(col, "C13: KRX keeps CONTEXT_UNKNOWN reason", AlertTapUnknownReason.CONTEXT_UNKNOWN, p.owned(t, col).reason)
        p.eq(col, "original scope is not filled", null, p.owned(t, col).context.scope)
        p.eq(col, "not consumable after a later grant", AlertTapReceipt.REJECTED, p.consumeAt(t, base, live))
        p.yes(col, "custody retained", t in p.inventory())
        p.eq(col, "no execution", 0, p.delivered.size)
    }

    @Test fun f11_missingIdentityStaysUnknown() = s63cTest("F11", "null") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val owner = PendingAlertTapOwner()
        val t = p.captureOn(owner, bank(), AlertTapMetadata(), coldContext(null), col)
        p.eq(col, "C02a requires an identity: CONTEXT_UNKNOWN at custody", AlertTapUnknownReason.CONTEXT_UNKNOWN, p.inventory(owner)[t]?.reason)
        val signedOut = TopicAccessSnapshot.INITIAL.copy(revision = 1)
        p.eq(col, "live-null stage: counted as unknown context", 1, owner.reconcile(signedOut, null).unknownContext)
        p.eq(col, "live-null stage: not consumable", AlertTapReceipt.REJECTED, p.consumeAt(t, signedOut, null, owner))
        p.yes(col, "live-null stage: custody retained", t in p.inventory(owner))
        // R-1: a later identity and its USER end neither attribute nor retire the identity-less tap.
        val ended = base.withUserEnd(1, null, live.uid, "ns-ended")
        p.eq(col, "later-identity stage: still unknown context", 1, owner.reconcile(ended, live).unknownContext)
        p.eq(col, "later-identity stage: reason unchanged", AlertTapUnknownReason.CONTEXT_UNKNOWN, p.inventory(owner)[t]?.reason)
        p.eq(col, "later-identity stage: not consumable", AlertTapReceipt.REJECTED, p.consumeAt(t, ended, live, owner))
        p.yes(col, "later-identity stage: custody retained", t in p.inventory(owner))
        p.eq(col, "no execution", 0, p.delivered.size)
    }

    @Test fun f12_invalidIdentityNeverBecomesAttributed() = s63cTest("F12", "empty-uid", "blank-uid", "negative-generation") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val owner = PendingAlertTapOwner()
        val bad = when (col) {
            "empty-uid" -> AuthIdentityFence("", live.authGeneration)
            "blank-uid" -> AuthIdentityFence("  ", live.authGeneration)
            else -> AuthIdentityFence(live.uid, -1)
        }
        val t = p.captureOn(owner, bank(), AlertTapMetadata(), coldContext(live).copy(identity = bad), col)
        p.eq(col, "C02a requires a valid identity: CONTEXT_UNKNOWN at custody", AlertTapUnknownReason.CONTEXT_UNKNOWN, p.inventory(owner)[t]?.reason)
        val report = owner.reconcile(base, live)
        p.yes(col, "the unchanged liveEnded rule retires the mismatched ingress identity", t !in p.inventory(owner))
        p.eq(col, "USER retirement is reported", 1, report.removedUser)
        p.eq(col, "not consumable", AlertTapReceipt.REJECTED, p.consumeAt(t, base, live, owner))
        p.eq(col, "no execution", 0, p.delivered.size)
    }

    @Test fun f13_nonKrxSourceIsPreservedUnhandledNotUnknown() = s63cTest("F13", "main") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val t = p.capture(sourceEvent(), source("upbit"), coldContext(live), column = col)
        p.eq(col, "non-KRX SOURCE", AlertTapClassification.NON_KRX, p.owned(t, col).classification)
        p.eq(col, "C12a: no CONTEXT_UNKNOWN reason", null, p.owned(t, col).reason)
        p.eq(col, "C12a: not unknown context", 0, p.owner.reconcile(base, live).unknownContext)
        p.eq(col, "no semantic consumer keeps it UNHANDLED", AlertTapReceipt.UNHANDLED, p.owner.consume(t, base, live, p::bankConsumer))
        p.yes(col, "preserved after UNHANDLED", t in p.inventory())
        p.eq(col, "UNHANDLED counted", 1, p.owner.reconcile(base, live).unhandled)
    }

    @Test fun f14_offControlRunsSameInputWithoutPac() = s63cTest("F14", "OFF") { p, col ->
        var snapshotReads = 0; var liveReads = 0
        val owner = PendingAlertTapOwner()
        val bus = AlertEventBus(owner, false, { true }, { snapshotReads++; TopicAccessSnapshot.INITIAL }, { liveReads++; null })
        val seen = mutableListOf<AlertEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { seen += it } }
        runCurrent()
        p.eq(col, "OFF emit accepted", true, bus.emit(bank()))
        runCurrent()
        p.eq(col, "OFF delivers the BANK event once", listOf<AlertEvent>(bank()), seen)
        p.yes(col, "OFF action is Trigger", AlertPushRouting.bankAction(seen.single()) is BankAlertAction.Trigger)
        p.eq(col, "OFF reads no access snapshot", 0, snapshotReads)
        p.eq(col, "OFF reads no live identity", 0, liveReads)
        p.eq(col, "OFF installs no owner custody", 0, p.inventory(owner).size)
        p.eq(col, "OFF reports no owner", PendingTapPurgeReport(), bus.pendingTapReport())
        job.cancel()
    }

    @Test fun f15_contradictoryContextStaysUnknown() = s63cTest("F15",
        "valid", "blank-user-epoch", "whitespace-user-epoch", "scope-match",
        "ingress-negative", "user-end-negative", "capability-end-negative", "scope-mismatch") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val owner = PendingAlertTapOwner()
        val cold = coldContext(live)
        val ctx = when (col) {
            "valid" -> cold
            "blank-user-epoch" -> cold.copy(userAccessEpoch = "")
            "whitespace-user-epoch" -> cold.copy(userAccessEpoch = "  ")
            "scope-match" -> AlertTapContext.capture(base, live).also {
                p.need(col, it.scope, "premise: production capture has a scope for the matching control")
            }
            "ingress-negative" -> cold.copy(ingressRevision = -1)
            "user-end-negative" -> cold.copy(userEndSequence = -1)
            "capability-end-negative" -> cold.copy(capabilityEndSequence = -1)
            else -> cold.copy(scope = p.need(col, p.context(base).scope, "premise: rig grants a KRX scope to mismatch"))
        }
        val positive = col in setOf("valid", "blank-user-epoch", "whitespace-user-epoch", "scope-match")
        if (positive) {
            p.yes(col, "positive control has non-negative fields", ctx.ingressRevision >= 0 &&
                ctx.userEndSequence >= 0 && ctx.capabilityEndSequence >= 0)
        }
        val t = p.captureOn(owner, bank(), AlertTapMetadata(), ctx, col)
        val report = owner.reconcile(base, live)
        p.yes(col, "custody retained under the live ingress identity", t in p.inventory(owner))
        p.eq(col, "original context retained", ctx, p.inventory(owner)[t]?.context)
        if (positive) {
            p.eq(col, "positive control is attributed", 0, report.unknownContext)
            p.eq(col, "positive control has no reason", null, p.inventory(owner)[t]?.reason)
            p.eq(col, "positive control consumable", AlertTapReceipt.HANDLED, p.consumeAt(t, base, live, owner))
            p.eq(col, "positive control executes once", listOf<AlertEvent>(bank()), p.delivered)
            p.yes(col, "positive control ACK removes custody", t !in p.inventory(owner))
        } else {
            p.eq(col, "contradiction stays unknown context", 1, report.unknownContext)
            p.eq(col, "contradiction keeps CONTEXT_UNKNOWN", AlertTapUnknownReason.CONTEXT_UNKNOWN, p.inventory(owner)[t]?.reason)
            p.eq(col, "contradiction not consumable", AlertTapReceipt.REJECTED, p.consumeAt(t, base, live, owner))
            p.yes(col, "custody retained after refusal", t in p.inventory(owner))
            val ended = base.withUserEnd(1, null, live.uid, "ns-ended")
            val afterEnd = owner.reconcile(ended, live)
            p.yes(col, "C10a does not newly retire a contradictory context", t in p.inventory(owner))
            p.eq(col, "contradiction remains reported after the end", 1, afterEnd.unknownContext)
            p.eq(col, "no new USER removal", 0, afterEnd.removedUser)
            p.eq(col, "still not consumable after the end", AlertTapReceipt.REJECTED, p.consumeAt(t, ended, live, owner))
            p.yes(col, "custody retained after the final refusal", t in p.inventory(owner))
            p.eq(col, "no execution", 0, p.delivered.size)
        }
    }

    @Test fun f16_consumedBeforeAnyRecordPublicationThroughRealBus() = s63cTest("F16", "warm") { p, col ->
        val live = p.live(col); p.granted(col)
        val cb = ColdBus(p, live)
        try {
            val t = cb.ingest(col)
            runCurrent()
            val ctx = p.assertColdCustody(cb, t, col)
            p.eq(col, "C12a: no unknown context with INITIAL still current", 0, cb.owner.reconcile(TopicAccessSnapshot.INITIAL, live).unknownContext)
            p.eq(col, "context epoch and scope stay null", ctx, p.need(col, p.inventory(cb.owner)[t], "still owned").context)
            cb.startConsumer(this)
            p.eq(col, "premise: supplier still INITIAL at consumption", TopicAccessSnapshot.INITIAL, cb.current)
            p.eq(col, "BANK consumer ran exactly once without a record", listOf<AlertEvent>(bank()), p.delivered)
            p.yes(col, "ACK removes the tap", t !in p.inventory(cb.owner))
        } finally { cb.close() }
    }

    @Test fun f17_existingRetirementScopePreserved() = s63cTest("F17",
        "epoch-nonkrx-other-namespace", "epochless-krx", "epochless-unknown", "capability-end-only") { p, col ->
        val live = p.live(col); val base = p.granted(col)
        val (event, metadata, ctx) = when (col) {
            "epoch-nonkrx-other-namespace" -> Triple<AlertEvent?, AlertTapMetadata, AlertTapContext>(bank(), AlertTapMetadata(), AlertTapContext.capture(base, live))
            "epochless-krx" -> Triple(sourceEvent(), source(), coldContext(live))
            "epochless-unknown" -> Triple(sourceEvent(), AlertTapMetadata(), coldContext(live))
            else -> Triple(bank(), AlertTapMetadata(), coldContext(live))
        }
        val epoch = ctx.userAccessEpoch
        if (col == "epoch-nonkrx-other-namespace") p.need(col, epoch, "premise: granted ingress captured a USER epoch")
        else p.eq(col, "premise: no USER epoch at ingress", null, epoch)
        val t = p.capture(event, metadata, ctx, column = col)
        val later = if (col == "capability-end-only") {
            base.copy(revision = base.revision + 1,
                lastCapabilityEnd = TopicAccessEnd(1, TopicAccessEndReason.CAPABILITY_REVOKED, null, live.uid, "cap-ended"))
        } else base.withUserEnd(1, null, live.uid, (epoch ?: "ns") + "-other")
        p.yes(col, "premise: later revision is past ingress", later.revision > ctx.ingressRevision)
        p.eq(col, "premise: live identity unchanged", ctx.identity, live)
        val report = p.owner.reconcile(later, live)
        p.yes(col, "kept by the unchanged retirement scope", t in p.inventory())
        when (col) {
            "epochless-krx", "epochless-unknown" -> {
                p.eq(col, "still counted as unknown context", 1, report.unknownContext)
                p.eq(col, "still not consumable", AlertTapReceipt.REJECTED, p.consumeAt(t, later, live))
                p.eq(col, "no execution", 0, p.delivered.size)
            }
            else -> {
                p.eq(col, "still consumable", AlertTapReceipt.HANDLED, p.consumeAt(t, later, live))
                p.eq(col, "exactly one execution", 1, p.delivered.size)
            }
        }
    }
}
