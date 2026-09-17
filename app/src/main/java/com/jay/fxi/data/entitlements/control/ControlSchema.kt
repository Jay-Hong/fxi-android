package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import java.util.Collections
import kotlinx.serialization.json.JsonNull

/**
 * Complete v1 wire schemas for individual control obligations, including all nested objects.
 * These are durable evidence formats, not serializations of live coordinator types. Field lifetime
 * and restoration semantics belong to [ControlObligationV1] and its fact types; creation, mutation,
 * and array permissions belong to [ControlObligations].
 *
 * Every object below is closed: only its listed keys are allowed. All fields are required unless
 * marked `optional`; missing required keys, unknown keys/enums, wrong types, or violated constraints
 * at any depth make the entire obligation uninterpretable. [read] returns null in that case and
 * never produces a partially defaulted fact. The disposition of that result is defined by
 * [ControlObligations.read]. Enum spellings are case sensitive.
 *
 * Schema notation: `text` is a JSON string (including the empty string); `id` is nonempty text,
 * without a UUID syntax requirement. `long` is a JSON integer in `0..Long.MAX_VALUE`; decimal,
 * exponent, and quoted-number forms are invalid. `bool` is a JSON boolean. `T?` means a required
 * key containing T or explicit JSON null. `optional T` permits key absence, but not JSON null.
 * Thus owner text preserves the distinction between empty and null, while epoch/boot/lifetime/
 * session/operation/obligation identifiers use `id`. Lists retain duplicates for validation.
 * `Axis` is `USER | CAPABILITY`; `Intent` is `IF_STALE | FORCE_ENTITLEMENTS | FORCE_PREMIUM`.
 * All object references in the following shapes expand to the closed schemas defined here.
 *
 * Shared objects:
 * ```text
 * Fence    { ownerUid: text?, userAccessEpoch: id?, krxCapabilityEpoch: id? }
 * Identity { ownerUid: text, authGeneration: long }
 * Floor    { anchorBootId: id?, anchorElapsedMillis: long, waitMillis: long,
 *            originLifetimeId: id }
 * ```
 * Floor elapsed and wait values are milliseconds. Its arithmetic is defined by [FloorV1.remainingAt].
 *
 * `SEAL` / `seal_v1`:
 * ```text
 * Seal       { id: id, kind: NAMESPACE | NULL_NAMESPACE, ownerUid: text?, axis: Axis,
 *              epoch: id (NAMESPACE only), settlement: optional Settlement }
 * Settlement { operationId: id, originLifetimeId: id, operation: Operation,
 *              before: Fence, after: Fence, journal: JournalTarget }
 * JournalTarget { ownerUid: text?, axis: Axis, epoch: id? }
 * Operation = LOAD | BIND_OWNER | SIGN_OUT | RETIRE_UNVERIFIED_START | BEGIN_SIGN_OUT |
 *             BEGIN_ROTATION | COMPLETE_PURGES | JOURNAL_RETIRED | MARK_MAY_CONTAIN_DATA
 * ```
 * NAMESPACE requires `epoch`; NULL_NAMESPACE forbids that key, even with a null value.
 * When settlement is present, its journal axis must equal the seal axis. For NAMESPACE, the
 * after-fence epoch on that axis must differ from the seal epoch; journal owner must be null or
 * equal to the seal owner, and journal epoch must be null or equal to the seal epoch.
 *
 * For NULL_NAMESPACE, journal epoch must be null and before.ownerUid must equal the seal owner.
 * Exactly two operation cases are accepted:
 * - BEGIN_ROTATION: after.ownerUid equals the seal owner; after's target-axis epoch is non-null
 *   and differs from before's; journal owner is null or equals the seal owner.
 * - SIGN_OUT, BIND_OWNER, or RETIRE_UNVERIFIED_START: after.ownerUid differs from the seal owner
 *   and journal owner equals the seal owner exactly (a null wildcard is insufficient for a
 *   non-null seal owner).
 * Other operations cannot settle NULL_NAMESPACE; merely assigning an epoch is insufficient.
 * These are internal evidence-consistency checks; see [SettlementEvidenceV1] for their limits.
 *
 * `DEMAND` / `demand_v1` is the disjoint union of these two shapes:
 * ```text
 * Request { id: id, kind: REQUEST, ownerUid: text?, binding: long, originLifetimeId: id,
 *           intent: Intent, raisedAt: long }
 * Guard   { id: id, kind: SCHEDULE_GUARD, floor: optional Floor, auth: optional Auth }
 * Auth    { ownerUid: text, authGeneration: long, binding: long, originLifetimeId: id,
 *           authStopped: bool, authStateOrder: long, authStopAppliedOrder: long }
 * ```
 * Request-only keys are forbidden on Guard and vice versa. A Guard containing only id/kind is
 * valid. A stopped Auth snapshot requires `0 < authStateOrder < authStopAppliedOrder`. A resumed
 * snapshot imposes no relative ordering between those counters. Snapshot-to-snapshot transition
 * rules are defined only by [ControlObligations.editExisting].
 *
 * `HOLD` / `hold_v1`:
 * ```text
 * Hold    { id: id, originLifetimeId: id, binding: long, axes: list<Axis>, outcome: Outcome,
 *           provenance: Provenance, floor: optional Floor }
 * Outcome { kind: STABLE_ACTIVE | STABLE_INACTIVE | PENDING | PREMIUM_REQUIRED |
 *                 KRX_ENTITLEMENT_REQUIRED,
 *           krxVisible: bool (STABLE_ACTIVE, STABLE_INACTIVE, PENDING only),
 *           retryAfterSeconds: long? (PENDING only) }
 * ```
 * Conditional Outcome fields are required for the indicated kinds and forbidden otherwise,
 * even as JSON null. INDETERMINATE is outside this domain. STABLE_ACTIVE and PENDING require
 * krxVisible=false; STABLE_INACTIVE permits either boolean. Axes must be nonempty and contain
 * no duplicates or unknown values: exactly {USER, CAPABILITY} for STABLE_INACTIVE or
 * PREMIUM_REQUIRED, and exactly {CAPABILITY} for every other permitted outcome.
 *
 * A non-null PENDING retryAfterSeconds requires Floor with waitMillis=retryAfterSeconds*1000
 * and floor.originLifetimeId=hold.originLifetimeId. The seconds value must not exceed
 * Long.MAX_VALUE/1000; overflow makes the obligation uninterpretable. In every other case the
 * floor key is forbidden, including when PENDING's required retryAfterSeconds key is null.
 *
 * Provenance is the disjoint union of Query and Topic:
 * ```text
 * Query   { kind: QUERY, started: Started, answeredAs: Identity? }
 * Started { fence: Fence, generation: long, boundIdentity: Identity?, order: long,
 *           binding: long, intent: Intent, userInvalidations: long }
 * Topic   { kind: TOPIC, grant: long, context: Context }
 * Context { identity: Identity, access: Fence, generation: long }
 * ```
 * Query requires started.binding=hold.binding. If answeredAs is non-null, its owner must equal
 * started.fence.ownerUid; if started.boundIdentity is also non-null, the two identities must
 * be equal. Topic permits only PREMIUM_REQUIRED or KRX_ENTITLEMENT_REQUIRED outcomes and
 * requires context.identity.ownerUid=context.access.ownerUid. No current runtime state is
 * consulted by these checks; restoration authority is specified by [RestoredHold].
 *
 * `RECOVERY_INTENT` / `recovery_intent_v1`:
 * ```text
 * RecoveryIntent { id: id, sessionId: id, ownerUid: text?, axis: Axis, targetEpoch: id? }
 * ```
 * Each element describes one axis. In particular, targetEpoch is required even when null.
 */
internal object ControlSchema {
    fun read(kind: ControlKind, n: ControlNode): ControlObligationV1? = when (kind) {
        ControlKind.SEAL -> seal(n)
        ControlKind.DEMAND -> demand(n)
        ControlKind.HOLD -> hold(n)
        ControlKind.RECOVERY_INTENT -> recovery(n)
    }

    private fun seal(n: ControlNode): SealV1? {
        val kind = n.enum<SealTargetKind>("kind") ?: return null
        if (!n.only("id", "kind", "ownerUid", "axis", "settlement", *when (kind) {
                SealTargetKind.NAMESPACE -> arrayOf("epoch")
                SealTargetKind.NULL_NAMESPACE -> emptyArray()
            })
        ) return null
        val id = n.id("id") ?: return null
        val owner = n.nullableText("ownerUid").present() ?: return null
        val axis = n.enum<PurgeScope>("axis") ?: return null
        val epoch = if (kind == SealTargetKind.NAMESPACE) n.id("epoch") ?: return null else null
        val settlement = if ("settlement" in n.names) settlement(n.obj("settlement") ?: return null) ?: return null else null
        val seal = SealV1(id, kind, SealKey(owner.value, axis, epoch), settlement)
        return seal.takeIf { settlement == null || settlementFits(it, settlement) }
    }

    private fun settlement(n: ControlNode): SettlementEvidenceV1? {
        if (!n.only("operationId", "originLifetimeId", "operation", "before", "after", "journal")) return null
        val operationId = n.id("operationId") ?: return null
        val origin = n.origin() ?: return null
        val operation = n.enum<StoreOp>("operation") ?: return null
        val before = fence(n.obj("before") ?: return null) ?: return null
        val after = fence(n.obj("after") ?: return null) ?: return null
        val journalNode = n.obj("journal") ?: return null
        if (!journalNode.only("ownerUid", "axis", "epoch")) return null
        val owner = journalNode.nullableText("ownerUid").present() ?: return null
        val axis = journalNode.enum<PurgeScope>("axis") ?: return null
        val epoch = journalNode.nullableId("epoch") ?: return null
        return SettlementEvidenceV1(operationId, origin, operation, before, after, JournalTargetV1(owner.value, axis, epoch.value))
    }

    private fun settlementFits(seal: SealV1, evidence: SettlementEvidenceV1): Boolean {
        val target = seal.key
        val journal = evidence.journal
        if (journal.axis != target.axis) return false
        if (seal.kind == SealTargetKind.NAMESPACE) {
            return evidence.after.epoch(target.axis) != target.epoch &&
                (journal.ownerUid == null || journal.ownerUid == target.ownerUid) &&
                (journal.epoch == null || journal.epoch == target.epoch)
        }
        if (journal.epoch != null || evidence.before.ownerUid != target.ownerUid) return false
        return when (evidence.operation) {
            StoreOp.BEGIN_ROTATION -> evidence.after.ownerUid == target.ownerUid &&
                evidence.after.epoch(target.axis) != null &&
                evidence.after.epoch(target.axis) != evidence.before.epoch(target.axis) &&
                (journal.ownerUid == null || journal.ownerUid == target.ownerUid)
            StoreOp.SIGN_OUT, StoreOp.BIND_OWNER, StoreOp.RETIRE_UNVERIFIED_START ->
                evidence.after.ownerUid != target.ownerUid && journal.ownerUid == target.ownerUid
            else -> false
        }
    }

    private fun demand(n: ControlNode): ControlObligationV1? = when (n.text("kind").present()?.value) {
        "REQUEST" -> request(n)
        "SCHEDULE_GUARD" -> guard(n)
        else -> null
    }

    private fun request(n: ControlNode): DemandV1? {
        if (!n.only("id", "kind", "ownerUid", "binding", "originLifetimeId", "intent", "raisedAt")) return null
        val id = n.id("id") ?: return null
        val owner = n.nullableText("ownerUid").present() ?: return null
        val binding = n.count("binding") ?: return null
        val origin = n.origin() ?: return null
        val intent = n.enum<RefreshIntent>("intent") ?: return null
        val order = n.count("raisedAt") ?: return null
        return DemandV1(id, owner.value, binding, intent, EventOrderV1(origin, order))
    }

    private fun guard(n: ControlNode): ScheduleGuardV1? {
        if (!n.only("id", "kind", "floor", "auth")) return null
        val id = n.id("id") ?: return null
        val floor = if ("floor" in n.names) floor(n.obj("floor") ?: return null) ?: return null else null
        val auth = if ("auth" in n.names) auth(n.obj("auth") ?: return null) ?: return null else null
        return ScheduleGuardV1(id, floor, auth)
    }

    private fun floor(n: ControlNode): FloorV1? {
        if (!n.only("anchorBootId", "anchorElapsedMillis", "waitMillis", "originLifetimeId")) return null
        val boot = n.nullableId("anchorBootId") ?: return null
        val anchor = n.count("anchorElapsedMillis") ?: return null
        val wait = n.count("waitMillis") ?: return null
        val origin = n.origin() ?: return null
        return FloorV1(boot.value, anchor, wait, origin)
    }

    private fun auth(n: ControlNode): AuthSnapshotV1? {
        if (!n.only("ownerUid", "authGeneration", "binding", "originLifetimeId", "authStopped", "authStateOrder", "authStopAppliedOrder")) return null
        val owner = n.text("ownerUid").present()?.value ?: return null
        val generation = n.count("authGeneration") ?: return null
        val binding = n.count("binding") ?: return null
        val origin = n.origin() ?: return null
        val stopped = n.flag("authStopped").present()?.value ?: return null
        val state = n.count("authStateOrder") ?: return null
        val applied = n.count("authStopAppliedOrder") ?: return null
        if (stopped && (state == 0L || applied <= state)) return null
        return AuthSnapshotV1(owner, generation, binding, origin, stopped, state, applied)
    }

    private fun hold(n: ControlNode): RestoredHold? {
        if (!n.only("id", "originLifetimeId", "binding", "axes", "outcome", "provenance", "floor")) return null
        val id = n.id("id") ?: return null
        val origin = n.origin() ?: return null
        val binding = n.count("binding") ?: return null
        val axes = n.enumNames("axes", PurgeScope.entries).present()?.value ?: return null
        val outcome = outcome(n.obj("outcome") ?: return null) ?: return null
        val expectedAxes = when (outcome.kind) {
            HoldOutcomeKind.STABLE_INACTIVE, HoldOutcomeKind.PREMIUM_REQUIRED -> setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
            else -> setOf(PurgeScope.CAPABILITY)
        }
        if (axes != expectedAxes) return null
        val provenance = provenance(n.obj("provenance") ?: return null, origin) ?: return null
        when (provenance) {
            is HoldProvenanceV1.Query -> {
                val started = provenance.started
                if (started.binding != binding) return null
                if (provenance.answeredAs != null && (provenance.answeredAs.ownerUid != started.fence.ownerUid ||
                    (started.boundIdentity != null && provenance.answeredAs != started.boundIdentity))
                ) return null
            }
            is HoldProvenanceV1.Topic -> if (outcome.kind != HoldOutcomeKind.PREMIUM_REQUIRED &&
                outcome.kind != HoldOutcomeKind.KRX_ENTITLEMENT_REQUIRED
            ) return null
        }
        val floor = if ("floor" in n.names) floor(n.obj("floor") ?: return null) ?: return null else null
        val seconds = outcome.retryAfterSeconds
        if (seconds == null) {
            if (floor != null) return null
        } else {
            if (seconds > Long.MAX_VALUE / 1_000L || floor == null || floor.waitMillis != seconds * 1_000L ||
                floor.originLifetimeId != origin
            ) return null
        }
        return RestoredHold(id, origin, binding, Collections.unmodifiableSet(axes.toSet()), outcome, provenance, floor)
    }

    private fun outcome(n: ControlNode): HoldOutcomeV1? {
        val kind = n.enum<HoldOutcomeKind>("kind") ?: return null
        val hasVisibility = kind == HoldOutcomeKind.STABLE_ACTIVE || kind == HoldOutcomeKind.STABLE_INACTIVE || kind == HoldOutcomeKind.PENDING
        val allowed = mutableSetOf("kind")
        if (hasVisibility) allowed += "krxVisible"
        if (kind == HoldOutcomeKind.PENDING) allowed += "retryAfterSeconds"
        if (n.hasNamesBeyond(allowed)) return null
        val visibility = if (hasVisibility) n.flag("krxVisible").present()?.value ?: return null else null
        if ((kind == HoldOutcomeKind.STABLE_ACTIVE || kind == HoldOutcomeKind.PENDING) && visibility != false) return null
        val retry = if (kind == HoldOutcomeKind.PENDING) n.nullableCount("retryAfterSeconds") ?: return null else null
        return HoldOutcomeV1(kind, visibility, retry?.value)
    }

    private fun provenance(n: ControlNode, origin: LifetimeId): HoldProvenanceV1? = when (n.text("kind").present()?.value) {
        "QUERY" -> query(n, origin)
        "TOPIC" -> topic(n)
        else -> null
    }

    private fun query(n: ControlNode, origin: LifetimeId): HoldProvenanceV1.Query? {
        if (!n.only("kind", "started", "answeredAs")) return null
        val started = n.obj("started") ?: return null
        if (!started.only("fence", "generation", "boundIdentity", "order", "binding", "intent", "userInvalidations")) return null
        val fence = fence(started.obj("fence") ?: return null) ?: return null
        val generation = started.count("generation") ?: return null
        val bound = nullableIdentity(started, "boundIdentity") ?: return null
        val order = started.count("order") ?: return null
        val binding = started.count("binding") ?: return null
        val intent = started.enum<RefreshIntent>("intent") ?: return null
        val invalidations = started.count("userInvalidations") ?: return null
        val answered = nullableIdentity(n, "answeredAs") ?: return null
        return HoldProvenanceV1.Query(
            StartedQueryV1(fence, generation, bound.value, EventOrderV1(origin, order), binding, intent, invalidations), answered.value
        )
    }

    private fun topic(n: ControlNode): HoldProvenanceV1.Topic? {
        if (!n.only("kind", "grant", "context")) return null
        val grant = n.count("grant") ?: return null
        val context = n.obj("context") ?: return null
        if (!context.only("identity", "access", "generation")) return null
        val identity = identity(context.obj("identity") ?: return null) ?: return null
        val access = fence(context.obj("access") ?: return null) ?: return null
        val generation = context.count("generation") ?: return null
        if (identity.ownerUid != access.ownerUid) return null
        return HoldProvenanceV1.Topic(grant, TopicContextV1(identity, access, generation))
    }

    private fun fence(n: ControlNode): FenceV1? {
        if (!n.only("ownerUid", "userAccessEpoch", "krxCapabilityEpoch")) return null
        val owner = n.nullableText("ownerUid").present() ?: return null
        val user = n.nullableId("userAccessEpoch") ?: return null
        val krx = n.nullableId("krxCapabilityEpoch") ?: return null
        return FenceV1(owner.value, user.value, krx.value)
    }

    private fun identity(n: ControlNode): IdentityV1? {
        if (!n.only("ownerUid", "authGeneration")) return null
        val owner = n.text("ownerUid").present()?.value ?: return null
        val generation = n.count("authGeneration") ?: return null
        return IdentityV1(owner, generation)
    }

    private fun nullableIdentity(n: ControlNode, name: String): FieldRead.Present<IdentityV1?>? {
        val child = n.child(name)
        if (child is FieldRead.Unreadable && child.found == JsonNull) return FieldRead.Present(null)
        return FieldRead.Present(identity(child.present()?.value ?: return null) ?: return null)
    }

    private fun recovery(n: ControlNode): RecoveryIntentV1? {
        if (!n.only("id", "sessionId", "ownerUid", "axis", "targetEpoch")) return null
        val id = n.id("id") ?: return null
        val session = n.id("sessionId") ?: return null
        val owner = n.nullableText("ownerUid").present() ?: return null
        val axis = n.enum<PurgeScope>("axis") ?: return null
        val epoch = n.nullableId("targetEpoch") ?: return null
        return RecoveryIntentV1(id, session, owner.value, axis, epoch.value)
    }

    private fun ControlNode.only(vararg names: String): Boolean = !hasNamesBeyond(names.toSet())
    private fun ControlNode.id(name: String): String? = text(name).present()?.value?.takeIf { it.isNotEmpty() }
    private fun ControlNode.origin(): LifetimeId? = id("originLifetimeId")?.let(::LifetimeId)
    private fun ControlNode.count(name: String): Long? = integer(name).present()?.value?.takeIf { it >= 0 }
    private fun ControlNode.obj(name: String): ControlNode? = child(name).present()?.value
    private inline fun <reified E : Enum<E>> ControlNode.enum(name: String): E? = enumName(name, enumValues<E>().toList()).present()?.value
    private fun ControlNode.nullableId(name: String): FieldRead.Present<String?>? =
        nullableText(name).present()?.takeIf { it.value != "" }

    private fun ControlNode.nullableCount(name: String): FieldRead.Present<Long?>? {
        val value = integer(name)
        if (value is FieldRead.Unreadable && value.found == JsonNull) return FieldRead.Present(null)
        return FieldRead.Present(value.present()?.value?.takeIf { it >= 0 } ?: return null)
    }

    private fun <T> FieldRead<T>.present(): FieldRead.Present<T>? = this as? FieldRead.Present<T>
}
