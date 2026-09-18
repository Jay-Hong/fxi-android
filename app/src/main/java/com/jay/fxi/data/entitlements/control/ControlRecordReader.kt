package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import java.util.Collections

/** Structural failures of the control portion of a Preferences record; originals remain on the result. */
sealed interface ControlRecordProblem {
    data object MissingSchema : ControlRecordProblem
    data class UnsupportedSchema(val version: Int) : ControlRecordProblem
    /** The schema must actually be Int, and each payload must actually be String; no coercion. */
    data class WrongType(val key: String) : ControlRecordProblem
    data class MissingPayload(val kind: ControlKind) : ControlRecordProblem
    data class UnreadablePayload(val kind: ControlKind, val payload: PayloadRead.Unreadable) : ControlRecordProblem
}

/**
 * A read-only classification, never proof of durable storage, settlement, or protected admission.
 * [original] is a detached, frozen copy of the entire input Preferences, including unrelated keys
 * and the exact payload strings. It is evidence, not a replacement to write over a newer record.
 */
sealed class ControlRecordRead(val original: Preferences) {
    /**
     * All five known control keys are absent. Empty, first-install, missing-file, zero-byte, legacy,
     * read-barrier-only, and corruption-replacement snapshots cannot be distinguished here. Even
     * an existing owner/epoch/journal proves neither genuine legacy nor clean continuity. Route to
     * explicit migration/recovery with protected admission closed; do not fill in empty v1 controls.
     */
    class MigrationOrRecoveryRequired internal constructor(original: Preferences) : ControlRecordRead(original)

    /**
     * No business interpretation is published, even for healthy payloads in this record. Preserve
     * the snapshot and keep protected admission closed. Problems follow schema, then payload order
     * SEAL, DEMAND, HOLD, RECOVERY_INTENT; schema failures stop before payload interpretation.
     */
    class Unreadable internal constructor(original: Preferences, problems: List<ControlRecordProblem>) : ControlRecordRead(original) {
        val problems: List<ControlRecordProblem> = Collections.unmodifiableList(problems.toList())
    }

    /**
     * Schema 1 and all four required string envelopes passed together. Every array is present,
     * including explicit empty arrays. This validates only the control portion: epoch, journal,
     * marker, and other Preferences values are retained but not interpreted by this reader.
     *
     * [hasUninterpretable] blocks whole-record completion and protected admission without narrowing
     * by a partially readable owner/axis. Preserve those entries without editing, settling, or
     * deleting them; independent interpreted siblings remain available for separate processing.
     * Even false (including four empty arrays) does not authorize access: recovery, purge, fresh
     * approval, and durable confirmation are separate obligations. Control queries still follow
     * their own admission/AUTH/floor policy rather than this protected-access disposition.
     */
    class Supported internal constructor(
        original: Preferences,
        arrays: Map<ControlKind, ControlArrayRead.Parsed>
    ) : ControlRecordRead(original) {
        val arrays: Map<ControlKind, ControlArrayRead.Parsed> = Collections.unmodifiableMap(arrays.toMap())
        val hasUninterpretable: Boolean get() = arrays.values.any { it.hasUninterpretable }
    }
}

/**
 * Pure D1 reader for a raw Preferences snapshot, before any typed-key conversion. Only
 * `control_schema` (Int 1) and `seal_v1`, `demand_v1`, `hold_v1`, `recovery_intent_v1` (String JSON
 * arrays) are interpreted. Inspecting [Preferences.asMap] by name distinguishes absence from an
 * actual type mismatch without a typed getter throwing. Other keys are preserved, not validated.
 * The caller must provide a stable snapshot (no concurrent mutation while this call copies it).
 *
 * Order: copy the snapshot; classify schema presence/type/version; validate all required payload
 * keys/types and envelopes; only then interpret arrays and check identities across the record.
 * A future, zero, or negative schema is unsupported, never downgraded or normalized to v1.
 * One failed envelope makes the entire record Unreadable, unlike one uninterpretable array entry.
 * Envelope limits and literal preservation remain those of [ControlPayloadCodec].
 *
 * Obligation ids occupy one namespace across all four arrays, including demand guards. Every
 * object with the same readable string id participates in collision detection, even a malformed
 * or future object. All participants are Uninterpretable; none wins, is merged, or receives a new
 * id. Non-string/missing ids remain uninterpretable through [ControlObligations.readArray] and
 * are never coerced into an identity. Nested session/lifetime/operation ids are not obligation ids.
 * Preserve every slot, order, original tree/literal, and stored id. Different ids with the same
 * SealKey coexist. Per-array guard cardinality remains [ControlObligations.readArray]'s contract.
 * Array-local editing alone does not enforce these cross-array collisions; a future record editor
 * must gate on this record result and validate the entire resulting record in the atomic edit.
 *
 * This class owns no DataStore, id generator, clock, writer, read barrier, or runtime admission.
 * It neither upgrades legacy input nor issues ids nor persists anything. No runtime path is wired
 * here. Future transitions must re-read and merge the latest record in the same atomic edit and
 * confirm storage; a successful classification does not provide that confirmation. The upgrade
 * boundary must validate/store schema, all four payloads, and the journal transition together.
 * Safe downgrade to older binaries is not guaranteed: they do not enforce these control keys.
 */
class ControlRecordReader(private val codec: ControlPayloadCodec = ControlPayloadCodec()) {
    fun read(preferences: Preferences): ControlRecordRead {
        val original = preferences.toPreferences()
        val values = original.asMap().entries.associate { it.key.name to it.value }
        if (ControlRecordKeys.SCHEMA !in values) {
            return if (ControlRecordKeys.payloads.values.none { it in values }) {
                ControlRecordRead.MigrationOrRecoveryRequired(original)
            } else {
                ControlRecordRead.Unreadable(original, listOf(ControlRecordProblem.MissingSchema))
            }
        }
        val schema = values[ControlRecordKeys.SCHEMA]
        if (schema !is Int) {
            return ControlRecordRead.Unreadable(original, listOf(ControlRecordProblem.WrongType(ControlRecordKeys.SCHEMA)))
        }
        if (schema != 1) {
            return ControlRecordRead.Unreadable(original, listOf(ControlRecordProblem.UnsupportedSchema(schema)))
        }

        val problems = mutableListOf<ControlRecordProblem>()
        val payloads = mutableMapOf<ControlKind, PayloadRead.Parsed>()
        for ((kind, key) in ControlRecordKeys.payloads) {
            if (key !in values) {
                problems += ControlRecordProblem.MissingPayload(kind)
                continue
            }
            val raw = values[key]
            if (raw !is String) {
                problems += ControlRecordProblem.WrongType(key)
                continue
            }
            when (val payload = codec.decode(raw)) {
                is PayloadRead.Unreadable -> problems += ControlRecordProblem.UnreadablePayload(kind, payload)
                is PayloadRead.Parsed -> payloads[kind] = payload
            }
        }
        if (problems.isNotEmpty()) return ControlRecordRead.Unreadable(original, problems)

        val duplicateIds = payloads.values.flatMap { it.entries }.mapNotNull { entry ->
            val node = (entry as? PayloadEntry.Obj)?.let { ControlNode.of(it.fields) }
            (node?.text("id") as? FieldRead.Present)?.value
        }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val arrays = payloads.mapValues { (kind, payload) ->
            val array = ControlObligations.readArray(kind, payload) as ControlArrayRead.Parsed
            ControlArrayRead.Parsed(array.entries.map { entry ->
                if (entry is ControlEntryRead.Interpreted && entry.value.id in duplicateIds) {
                    ControlEntryRead.Uninterpretable(entry.original.toPayloadEntry())
                } else entry
            })
        }
        return ControlRecordRead.Supported(original, arrays)
    }

}
