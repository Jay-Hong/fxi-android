package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp

enum class ControlKind { SEAL, DEMAND, HOLD, RECOVERY_INTENT }

/**
 * Durable identifier of the coordinator lifetime that captured a fact. This is a newly introduced
 * origin, distinct from runtime binding counters, recovery sessions, and boot identifiers. Archived
 * binding/generation/order/grant/userInvalidations counters belong to this original lifetime;
 * retaining their values never permits transplanting them into a new runtime lifetime.
 * Wire domains for this and the other fact types are defined only by [ControlSchema].
 */
data class LifetimeId(val value: String)

/**
 * An event counter [value] together with its original [origin]. Deliberately not Comparable:
 * archived orders cannot be compared across lifetimes or used as a new binding's settlement
 * threshold. See [DemandV1] for the fresh-order handover requirement.
 */
data class EventOrderV1(val origin: LifetimeId, val value: Long)

/**
 * A current boot observation: [bootId] is nonempty when known, or null when continuity cannot be
 * established; [elapsedMillis] is nonnegative elapsed realtime in that boot, in milliseconds.
 * Empty boot ids and negative elapsed values are invalid inputs, not unknown-continuity markers.
 * Supplying trustworthy Android boot observations is a caller responsibility outside this layer.
 */
data class BootReading(val bootId: String?, val elapsedMillis: Long)

/**
 * A captured server wait anchored in elapsed time, never wall time. Its lifetime is independent of
 * owner and binding changes. [remainingAt] owns restoration arithmetic; [ControlObligations.recordFloor]
 * owns guard recapture and persistence ordering. A hold's floor is the original answer's evidence.
 *
 * @property anchorBootId Boot at capture; null means its continuity cannot be established.
 * @property anchorElapsedMillis Elapsed realtime at capture, in that boot's milliseconds.
 * @property waitMillis The full wait required at capture, in milliseconds, not an absolute deadline.
 * @property originLifetimeId Lifetime that captured this floor, independent of a guard's AUTH origin.
 */
@ConsistentCopyVisibility
data class FloorV1 internal constructor(
    val anchorBootId: String?,
    val anchorElapsedMillis: Long,
    val waitMillis: Long,
    val originLifetimeId: LifetimeId
) {
    /**
     * If both boot ids are known and equal and now.elapsedMillis >= anchorElapsedMillis, returns
     * `waitMillis - min(waitMillis, now.elapsedMillis - anchorElapsedMillis)`. Otherwise returns
     * the full waitMillis: a different/unknown boot or backwards elapsed clock proves no progress.
     * Matching coordinator lifetimes do not prove boot continuity; restarting a process within a
     * proven same boot does not lose it. Without boot evidence, every restart may repeat the full
     * wait conservatively. No wall clock or `anchor + wait` addition is used, avoiding overflow.
     *
     * Returns null for an invalid [BootReading] or a floor outside [ControlSchema]'s field domains;
     * invalid input is never interpreted as an expired wait. This function does not persist or
     * recapture an anchor.
     */
    fun remainingAt(now: BootReading): Long? {
        if (anchorBootId == "" || anchorElapsedMillis < 0 || waitMillis < 0 ||
            originLifetimeId.value.isEmpty() || now.bootId == "" || now.elapsedMillis < 0
        ) return null
        return if (anchorBootId != null && anchorBootId == now.bootId && now.elapsedMillis >= anchorElapsedMillis) {
            waitMillis - minOf(waitMillis, now.elapsedMillis - anchorElapsedMillis)
        } else waitMillis
    }
}

/**
 * Captured namespace evidence: [ownerUid] and both axis epochs describe the fence at capture.
 * Null retains its original meaning and is not replaced with a current owner/epoch. This archived
 * type provides no conversion to live access authority.
 */
data class FenceV1(val ownerUid: String?, val userAccessEpoch: String?, val krxCapabilityEpoch: String?) {
    fun epoch(axis: PurgeScope): String? = when (axis) {
        PurgeScope.USER -> userAccessEpoch
        PurgeScope.CAPABILITY -> krxCapabilityEpoch
    }
}

/**
 * Original authentication evidence: [ownerUid] identifies that owner and [authGeneration] is its
 * generation in the enclosing fact's original lifetime. It provides no live identity conversion.
 */
data class IdentityV1(val ownerUid: String, val authGeneration: Long)

/**
 * Captured cleanup handover target. A null [ownerUid] covers all owners; null [epoch] covers the
 * historical namespace scope on [axis]. Whether this covers a particular seal is [ControlSchema]'s
 * cross-field decision, including its stricter NULL_NAMESPACE owner-departure case.
 */
data class JournalTargetV1(val ownerUid: String?, val axis: PurgeScope, val epoch: String?)

/** Closed witness union. Neither branch is runtime authority or proof of current journal presence. */
sealed interface SettlementEvidence {
    val operationId: String
    val originLifetimeId: LifetimeId
    val before: FenceV1
    val after: FenceV1
    val journal: JournalTargetV1
}

/** L-only historical NULL handover; version/kind are fixed by the type, not caller options. */
data class RetiredNullSettlementEvidenceV2(
    override val operationId: String,
    override val originLifetimeId: LifetimeId,
    override val before: FenceV1,
    override val after: FenceV1,
    override val journal: JournalTargetV1
) : SettlementEvidence

/**
 * Evidence retained through the seal's atomic settlement handover. [operationId] is a durable
 * operation identifier; [originLifetimeId] identifies the lifetime recording the evidence.
 * [operation] names the confirmed store operation, [before]/[after] its confirmed record fences,
 * and [journal] the cleanup obligation handed over by it. A schema-valid value checks internal
 * consistency only: the caller still owes actual durable confirmation and the settlement
 * transition. Constructing or reading this value grants no authority to delete a seal.
 */
data class SettlementEvidenceV1(
    override val operationId: String,
    override val originLifetimeId: LifetimeId,
    val operation: StoreOp,
    override val before: FenceV1,
    override val after: FenceV1,
    override val journal: JournalTargetV1
) : SettlementEvidence

/**
 * Durable obligation identity plus archived facts. [id] is a newly issued stable storage identity
 * (UUID issuance is recommended, but syntax is governed by [ControlSchema]). Restore preserves it
 * rather than minting another id. Keep its association through durable settlement or handover;
 * a new independent occurrence must not silently take over an old obligation's identity.
 * Issuance and settlement transitions are outside this pure interpretation/editing layer.
 */
sealed interface ControlObligationV1 { val id: String }

enum class SealTargetKind { NAMESPACE, NULL_NAMESPACE }
/** Captured loss target, distinct from the durable obligation id; null owner/epoch remain meaningful. */
data class SealKey(val ownerUid: String?, val axis: PurgeScope, val epoch: String?)

/**
 * [kind] and [key] retain the loss target until atomic retirement and journal/demand handover are
 * durably confirmed. A repeated loss for the same still-unsettled key must join the existing id in
 * the future transition layer; an independent loss after settlement has a new identity. Already
 * stored key collisions are handled by [ControlObligations.readArray], not merged here.
 * [settlement] retains the operation witness described by [SettlementEvidence].
 */
data class SealV1(
    override val id: String,
    val kind: SealTargetKind,
    val key: SealKey,
    val settlement: SettlementEvidence?
) : ControlObligationV1

/**
 * Refresh still owed for the captured [ownerUid]/[binding]. [raisedAt] carries the original
 * binding/order lifetime; [intent] is the minimum refresh strength owed through saved settlement.
 * Restoring this demand requires handover under the coordinator lock with a fresh settlement order
 * in the new binding. Only a sufficiently strong fresh query starting after that handover may
 * settle it; an older in-flight query cannot. Preserve the original id association and obligation
 * until settlement is durably saved. This type does not perform that future runtime transition.
 */
data class DemandV1(
    override val id: String,
    val ownerUid: String?,
    val binding: Long,
    val intent: RefreshIntent,
    val raisedAt: EventOrderV1
) : ControlObligationV1

/**
 * Original binding-local authentication state. [ownerUid], [authGeneration], [binding], and
 * [originLifetimeId] identify the captured scope; neither its state nor its counters can be
 * directly installed into another binding's runtime.
 *
 * [authStopped] records stop/resume state. [authStateOrder] is the event that moved that state
 * (possibly the start order of a query answered later); [authStopAppliedOrder] is the separately
 * issued order of an accepted stop application, including an already-stopped application.
 * Snapshot validity belongs to [ControlSchema]; legal updates to [ControlObligations.editExisting].
 */
data class AuthSnapshotV1(
    val ownerUid: String,
    val authGeneration: Long,
    val binding: Long,
    val originLifetimeId: LifetimeId,
    val authStopped: Boolean,
    val authStateOrder: Long,
    val authStopAppliedOrder: Long
)

/**
 * Durable scheduler guard even when no request is pending. [floor] survives owner/binding changes;
 * [auth] belongs only to its original binding, and its origin need not equal the floor's origin.
 * Ending a binding requires a future named atomic transition to remove/replace that AUTH snapshot;
 * generic editing does not implement binding-end restoration or lifecycle management.
 */
data class ScheduleGuardV1(
    override val id: String,
    val floor: FloorV1?,
    val auth: AuthSnapshotV1?
) : ControlObligationV1

enum class HoldOutcomeKind { STABLE_ACTIVE, STABLE_INACTIVE, PENDING, PREMIUM_REQUIRED, KRX_ENTITLEMENT_REQUIRED }

/**
 * Old answer evidence retained through the enclosing hold's settlement: [kind], [krxVisible], and
 * the nullable seconds-valued [retryAfterSeconds] belong to that answer. This is not a live outcome
 * a reducer may apply. Its conditional wire fields are specified by [ControlSchema].
 */
data class HoldOutcomeV1(val kind: HoldOutcomeKind, val krxVisible: Boolean?, val retryAfterSeconds: Long?)

sealed interface HoldProvenanceV1 {
    /** [started] captures query start; [answeredAs] captures the answer's transport identity, if any. */
    data class Query(val started: StartedQueryV1, val answeredAs: IdentityV1?) : HoldProvenanceV1
    /** [grant] is the original issuance counter, not a live token; [context] is its captured scope. */
    data class Topic(val grant: Long, val context: TopicContextV1) : HoldProvenanceV1
}

/**
 * Query-start snapshot retained with its hold: [fence] is the full original target, [boundIdentity]
 * the identity bound at start, and [intent] the actual query strength. [generation], [order],
 * [binding], and [userInvalidations] are the original decision/event/binding/invalidation counters,
 * scoped by the enclosing hold's [LifetimeId], never current runtime counters.
 */
data class StartedQueryV1(
    val fence: FenceV1,
    val generation: Long,
    val boundIdentity: IdentityV1?,
    val order: EventOrderV1,
    val binding: Long,
    val intent: RefreshIntent,
    val userInvalidations: Long
)

/**
 * Original grant context: authentication [identity], full target [access], and decision [generation].
 * All are retained as hold evidence in the original lifetime rather than reissued as a live grant.
 */
data class TopicContextV1(val identity: IdentityV1, val access: FenceV1, val generation: Long)

/**
 * Loss evidence retained until cleanup/fresh-demand handover settlement is durably saved.
 * [originLifetimeId] scopes [binding] and every nested process-local counter; [axes] preserves the
 * held scope, [outcome] the old answer, [provenance] its origin, and [floor] its original wait capture.
 * Never inserts into the coordinator's live candidate list or reapplies the answer, even when the
 * lifetime still matches. It has no live outcome/identity/fence/grant conversion. Current-runtime
 * staleness alone cannot discard the archived obligation; recovery needs a fresh handover.
 */
data class RestoredHold(
    override val id: String,
    val originLifetimeId: LifetimeId,
    val binding: Long,
    val axes: Set<PurgeScope>,
    val outcome: HoldOutcomeV1,
    val provenance: HoldProvenanceV1,
    val floor: FloorV1?
) : ControlObligationV1

/**
 * Durable prior intent for the captured [ownerUid]/[axis]/[targetEpoch]; a null target remains a
 * meaningful target, not a request to substitute the current epoch. [sessionId] identifies a prior-
 * intent session, distinct from coordinator lifetime: multiple sessions in one lifetime stay
 * separate. Current epoch mismatch alone cannot discard it.
 *
 * The future lifecycle layer must persist and confirm intent before opening its request,
 * observation, or protected lifetime. Release follows related in-flight completion, closed access,
 * and saved control settlement. A changed target needs its own prior durable intent before use;
 * the old obligation survives until settlement or durable handover is confirmed. Session issuance
 * and these transitions are not implemented by this fact or its generic editor.
 */
data class RecoveryIntentV1(
    override val id: String,
    val sessionId: String,
    val ownerUid: String?,
    val axis: PurgeScope,
    val targetEpoch: String?
) : ControlObligationV1
