package com.jay.fxi.data.entitlements.control

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Original trees accompany interpreted facts; preservation and failure disposition are defined by [ControlObligations.read]. */
sealed interface ControlEntryRead {
    class Interpreted internal constructor(val original: ControlNode, val value: ControlObligationV1) : ControlEntryRead

    class Uninterpretable internal constructor(original: PayloadEntry) : ControlEntryRead {
        private val saved = detached(original)
        val original: PayloadEntry get() = detached(saved)
    }
}

sealed interface ControlArrayRead {
    class Parsed internal constructor(entries: List<ControlEntryRead>) : ControlArrayRead {
        val entries: List<ControlEntryRead> = Collections.unmodifiableList(entries.toList())
        /** A local diagnosis, not a record-level admission or completion decision. */
        val hasUninterpretable: Boolean get() = entries.any { it is ControlEntryRead.Uninterpretable }
    }
    data class Unreadable(val payload: PayloadRead.Unreadable) : ControlArrayRead
}

sealed interface ControlArrayWriteResult {
    data class Written(val payload: PayloadRead.Parsed) : ControlArrayWriteResult
    data class Rejected(val why: ControlWriteFailure) : ControlArrayWriteResult
}

/**
 * Pure creation, interpretation, and constrained editing of control obligations. [ControlSchema]
 * defines complete field schemas; [ControlObligationV1] and its fact types define lifetime and
 * restoration meaning. Each entrypoint below owns its mutation or array contract.
 *
 * A Written result is a local tree result, not proof of envelope encoding limits (size/depth),
 * durable storage, settlement, or protected access admission. No result is a Supported record.
 * Whole-record schema versions, actual Preferences types, required keys, and classification of
 * legacy/missing/corrupt/unsupported records belong to the future storage layer. Absence of control
 * payloads alone proves no clean continuity; incomplete or unsupported records must not be silently
 * normalized into empty v1 controls. Storage integration must merge with the latest record in the
 * same atomic edit. Raw envelope APIs still exist; this layer does not close their bypass or
 * guarantee safe downgrade. None of those future transitions is activated here.
 */
object ControlObligations {
    /**
     * Interprets the entire obligation using [ControlSchema], retaining the original tree alongside
     * the facts. A failure at any depth returns Uninterpretable for the whole original obligation;
     * a field-level OUT_OF_DOMAIN diagnosis is not an obligation disposition. Preserve that raw
     * tree and its numeric literals rather than rebuilding it from partial facts (byte-for-byte
     * source formatting is not promised). An uninterpretable obligation cannot be edited, settled,
     * or removed through this layer, nor can a partly readable owner/axis narrow its blocking scope.
     * Independent healthy siblings may still be processed via [readArray]/[editArrayEntry].
     * This single-node check does not establish array validity.
     */
    fun read(kind: ControlKind, original: ControlNode): ControlEntryRead =
        ControlSchema.read(kind, original)?.let { ControlEntryRead.Interpreted(original, it) }
            ?: ControlEntryRead.Uninterpretable(original.toPayloadEntry())

    /**
     * Builds a new obligation from an empty [ControlBuilder], then validates the whole result with
     * [ControlSchema]. Any complete permitted initial shape can be created, including immutable
     * kinds; incomplete or invalid final schemas return INVALID_CHANGE. Existing-node mutation
     * permissions from [editExisting] do not restrict initial construction. Builder/editor thread,
     * nesting, re-entry, rejection, and close rules still apply; their failures are retained.
     * Object creation uses [ControlEditor.createChild]; it cannot replace an existing name or graft
     * an unchecked existing tree. Issuing the stable identity belongs to the caller; see
     * [ControlObligationV1]. This does not check array membership or store the result.
     */
    fun build(kind: ControlKind, block: ControlBuilder.() -> Unit): ControlWriteResult {
        val result = ControlNode.of(emptyMap()).edited { ControlBuilder(this).block() }
        return if (result is ControlWriteResult.Written && ControlSchema.read(kind, result.node) == null) invalid() else result
    }

    /**
     * Gates edits in this order: interpret the whole original, run [change], interpret the whole
     * result, then check changes against the original. An uninterpretable original returns
     * UNINTERPRETABLE_OBLIGATION without invoking the callback. Editor failures propagate; an
     * invalid final schema or forbidden change returns INVALID_CHANGE and no replacement node.
     * A valid shape alone is insufficient permission to change identity or weaken an obligation.
     *
     * The complete generic mutation allowlist is:
     * - Seal: only add an initially absent settlement. Once present, all of it is immutable.
     * - Request: only intent and raisedAt may change. Intent follows
     *   IF_STALE < FORCE_ENTITLEMENTS < FORCE_PREMIUM and cannot weaken; raisedAt cannot decrease.
     *   Strengthening intent additionally requires a strictly increased raisedAt.
     * - Guard: only auth may change. An absent auth may be created with any schema-valid snapshot.
     *   An existing snapshot cannot be removed; its ownerUid, authGeneration, binding, and
     *   originLifetimeId cannot change. An unchanged snapshot is allowed. Any changed snapshot
     *   strictly increases authStateOrder. A resulting stopped state also strictly increases
     *   authStopAppliedOrder, even when already stopped; a resulting resumed state preserves
     *   authStopAppliedOrder exactly. Snapshot constraints still come from [ControlSchema].
     *   Floor creation/recapture is exclusively [recordFloor], not a generic guard edit.
     * - Hold and recovery intent: no fields may change, including nested evidence and hold floor.
     *
     * Every field outside that allowlist, including id and discriminators, must retain its raw
     * tree value. A no-op on any interpretable kind succeeds, including immutable kinds. Changes
     * use the editor's scalar operations and child scopes, without object replacement/removal.
     * This is a single-node operation; use [editArrayEntry] to enforce array constraints too.
     */
    fun editExisting(kind: ControlKind, original: ControlNode, change: ControlEditor.() -> Unit): ControlWriteResult {
        val before = ControlSchema.read(kind, original) ?: return uninterpretable()
        val result = original.edited(change)
        if (result !is ControlWriteResult.Written) return result
        val after = ControlSchema.read(kind, result.node) ?: return invalid()
        return if (permitted(original, result.node, before, after)) result else invalid()
    }

    /**
     * Explicitly creates or recaptures a guard's Floor tuple. First interprets the entire original
     * as DEMAND; failure returns UNINTERPRETABLE_OBLIGATION. An interpretable non-guard is invalid.
     * [now] must satisfy [BootReading], [waitMillis] must be nonnegative, and [origin] must satisfy
     * the Floor origin domain in [ControlSchema]; invalid arguments return INVALID_CHANGE.
     *
     * Computes old remaining via [FloorV1.remainingAt] (zero only when floor is absent), then writes
     * `max(oldRemaining, waitMillis)` with now's boot/elapsed and the supplied origin as one tuple.
     * Invalid old arithmetic is rejected, never replaced by zero. Every other raw field, including
     * AUTH and its origin, is preserved; the final whole guard is validated. It cannot recapture a
     * hold floor. The caller must persist and confirm Written before using the new anchor at
     * runtime; this method itself provides no durable confirmation.
     */
    fun recordFloor(original: ControlNode, now: BootReading, waitMillis: Long, origin: LifetimeId): ControlWriteResult {
        val before = ControlSchema.read(ControlKind.DEMAND, original) ?: return uninterpretable()
        if (before !is ScheduleGuardV1 || now.bootId == "" || now.elapsedMillis < 0 || waitMillis < 0 || origin.value.isEmpty()) return invalid()
        val remaining = if (before.floor == null) 0L else before.floor.remainingAt(now) ?: return invalid()
        val wait = maxOf(remaining, waitMillis)
        val result = original.edited {
            if (before.floor == null) {
                createChild("floor") {
                    set("anchorBootId", now.bootId.scalar())
                    set("anchorElapsedMillis", ControlScalar.Integer(now.elapsedMillis))
                    set("waitMillis", ControlScalar.Integer(wait))
                    set("originLifetimeId", ControlScalar.Text(origin.value))
                }
            } else {
                descend("floor") {
                    set("anchorBootId", now.bootId.scalar())
                    set("anchorElapsedMillis", ControlScalar.Integer(now.elapsedMillis))
                    set("waitMillis", ControlScalar.Integer(wait))
                    set("originLifetimeId", ControlScalar.Text(origin.value))
                }
            }
        }
        if (result !is ControlWriteResult.Written) return result
        val after = ControlSchema.read(ControlKind.DEMAND, result.node) as? ScheduleGuardV1 ?: return invalid()
        return if (validFloorResult(original, result.node, after, FloorV1(now.bootId, now.elapsedMillis, wait, origin))) result else invalid()
    }

    internal fun validFloorResult(original: ControlNode, candidate: ControlNode, after: ScheduleGuardV1, expected: FloorV1): Boolean {
        if (floorUnchangedValues(original) != floorUnchangedValues(candidate)) return false // A11a
        if (after.floor != expected) return false // A11b
        return true
    }

    // Compare each untouched subtree's serialization, including AUTH key order and number literals.
    private fun floorUnchangedValues(node: ControlNode): Map<String, String> = node.toPayloadEntry().fields
        .filterKeys { it != "floor" }.mapValues { (_, value) -> value.toString() }

    /**
     * Applies whole-array constraints in addition to [read]'s per-obligation schema. An unreadable
     * envelope retains its original [PayloadRead.Unreadable]; it is not turned into an empty array.
     * On a parsed payload, keeps every slot in input order, with original trees/literals intact:
     * no sorting, dropping, or deduplication, including for holds and uninterpretable siblings.
     *
     * All objects with the same readable string id are Uninterpretable, even if some participants
     * are otherwise malformed or contain future fields. In DEMAND, at most one guard is allowed:
     * if multiple objects have readable kind=SCHEDULE_GUARD, all those participants are
     * Uninterpretable, including otherwise malformed guards. Unaffected entries retain independent
     * interpretation. Nonobjects and objects failing [ControlSchema] are also Uninterpretable.
     *
     * Multiple REQUEST entries are allowed; a runtime single request slot is not disk cardinality.
     * Seals sharing a SealKey but having distinct ids coexist without merging. Checks are scoped
     * to this payload/kind and establish neither cross-payload identity nor whole-record validity.
     * Calling only [read] or [build] does not establish any of these array guarantees.
     */
    fun readArray(kind: ControlKind, payload: PayloadRead): ControlArrayRead {
        if (payload is PayloadRead.Unreadable) return ControlArrayRead.Unreadable(payload)
        payload as PayloadRead.Parsed
        val originals = payload.entries.map(::detached)
        val nodes = originals.map { (it as? PayloadEntry.Obj)?.let { obj -> ControlNode.of(obj.fields) } }
        // A damaged or future object still participates when its id/guard discriminator is readable.
        val ids = nodes.map { (it?.text("id") as? FieldRead.Present)?.value }
        val duplicates = ids.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val guards = if (kind == ControlKind.DEMAND) nodes.indices.filter {
            (nodes[it]?.text("kind") as? FieldRead.Present)?.value == "SCHEDULE_GUARD"
        }.toSet() else emptySet()
        return ControlArrayRead.Parsed(nodes.mapIndexed { index, node ->
            if (node == null || ids[index] in duplicates || (guards.size > 1 && index in guards)) {
                ControlEntryRead.Uninterpretable(originals[index])
            } else read(kind, node)
        })
    }

    /**
     * Runs [readArray] before editing only the selected slot through [editExisting]. An unreadable
     * envelope or uninterpretable target returns UNINTERPRETABLE_OBLIGATION without invoking
     * [change]; an out-of-range index returns INVALID_CHANGE. Edit rejections retain their reason.
     * Success replaces exactly that index, keeping sibling originals, order, and count, including
     * uninterpretable siblings. There is no append/delete operation here. Identity/discriminator
     * immutability from [editExisting] keeps this replacement from introducing an array collision.
     * Result limits and storage responsibilities remain those on [ControlObligations].
     */
    fun editArrayEntry(
        kind: ControlKind,
        payload: PayloadRead,
        index: Int,
        change: ControlEditor.() -> Unit
    ): ControlArrayWriteResult {
        val array = readArray(kind, payload) as? ControlArrayRead.Parsed
            ?: return ControlArrayWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION)
        if (index !in array.entries.indices) return ControlArrayWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE)
        val entry = array.entries[index] as? ControlEntryRead.Interpreted
            ?: return ControlArrayWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION)
        return when (val edit = editExisting(kind, entry.original, change)) {
            is ControlWriteResult.Rejected -> ControlArrayWriteResult.Rejected(edit.why)
            is ControlWriteResult.Written -> ControlArrayWriteResult.Written(PayloadRead.Parsed(array.entries.mapIndexed { i, sibling ->
                if (i == index) edit.node.toPayloadEntry() else when (sibling) {
                    is ControlEntryRead.Interpreted -> sibling.original.toPayloadEntry()
                    is ControlEntryRead.Uninterpretable -> sibling.original
                }
            }))
        }
    }

    private fun permitted(original: ControlNode, result: ControlNode, before: ControlObligationV1, after: ControlObligationV1): Boolean =
        when (before) {
            is SealV1 -> after is SealV1 && unchangedExcept(original, result, setOf("settlement")) &&
                (before.settlement == null || unchangedExcept(original, result, emptySet()))
            is DemandV1 -> after is DemandV1 && unchangedExcept(original, result, setOf("intent", "raisedAt")) &&
                after.intent >= before.intent && after.raisedAt.value >= before.raisedAt.value &&
                (after.intent == before.intent || after.raisedAt.value > before.raisedAt.value)
            is ScheduleGuardV1 -> after is ScheduleGuardV1 && unchangedExcept(original, result, setOf("auth")) &&
                authChange(before.auth, after.auth)
            is RestoredHold, is RecoveryIntentV1 -> unchangedExcept(original, result, emptySet())
        }

    private fun authChange(before: AuthSnapshotV1?, after: AuthSnapshotV1?): Boolean {
        if (before == null) return true // Any newly created snapshot already passed the full schema.
        if (after == null) return false
        if (before == after) return true
        if (before.ownerUid != after.ownerUid || before.authGeneration != after.authGeneration ||
            before.binding != after.binding || before.originLifetimeId != after.originLifetimeId ||
            after.authStateOrder <= before.authStateOrder
        ) return false
        return if (after.authStopped) after.authStopAppliedOrder > before.authStopAppliedOrder
        else after.authStopAppliedOrder == before.authStopAppliedOrder
    }

    /** Raw equality keeps an untouched -0, key, null or nested field from being silently rebuilt. */
    private fun unchangedExcept(before: ControlNode, after: ControlNode, mutable: Set<String>): Boolean =
        before.toPayloadEntry().fields.filterKeys { it !in mutable } == after.toPayloadEntry().fields.filterKeys { it !in mutable }

    private fun String?.scalar(): ControlScalar = if (this == null) ControlScalar.Null else ControlScalar.Text(this)
    private fun invalid() = ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE)
    private fun uninterpretable() = ControlWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION)
}

private fun detached(entry: PayloadEntry): PayloadEntry = when (entry) {
    is PayloadEntry.Obj -> ControlNode.of(entry.fields).toPayloadEntry()
    is PayloadEntry.Uninterpretable -> PayloadEntry.Uninterpretable(detached(entry.raw))
}

private fun detached(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { detached(it.value) })
    is JsonArray -> JsonArray(value.map(::detached))
    is JsonPrimitive -> value
}
