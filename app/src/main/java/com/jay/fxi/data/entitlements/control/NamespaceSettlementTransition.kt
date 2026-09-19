package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.FIELD_SEPARATOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.SCOPE_SEPARATOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure candidate construction inside the existing owner's transaction. Settled seals are retained.
 * P3 is blocked on safe GC/compaction, old-command termination/confirmation, reserved payload space,
 * and recovery tests past the retention budget. TTL/count/local tracking emptiness are not GC proof.
 */
internal class NamespaceSettlementTransition(private val codec: ControlPayloadCodec) {
    fun decide(
        command: CommandRef,
        input: RotateAndSettleNamespaces,
        read: ControlRecordRead.Supported,
        context: AttemptContext?,
        confirmOnly: Boolean,
        previouslyConfirmed: Boolean
    ): RecordTransactionDecision<Outcome> {
        fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, input.seals.map { it.id }), read))
        fun recovery(reason: RecoveryReason) = negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), reason, read))

        val snapshotSchema = read.original.asMap().entries
            .singleOrNull { it.key.name == ControlRecordKeys.SCHEMA }?.value
        if (snapshotSchema !is Int || snapshotSchema != read.schemaVersion || read.schemaVersion !in 1..2) {
            return recovery(RecoveryReason.UnreadableRecord)
        }
        val own = ControlAppliedEvidence.own(read, command)
        if (ControlAppliedEvidence.hasOpaqueOwn(read, command)) return recovery(RecoveryReason.UninterpretableMetadata)
        if (own != null && !ControlAppliedEvidence.matches(command, TrackedControlCommand(command), own)) {
            return conflict(ConflictReason.CommandEvidenceMismatch)
        }
        input.invalidInput()?.let { return reject(it) }
        val located = mutableListOf<ControlEntryRead.Interpreted>()
        for (seal in input.seals) {
            val matches = read.locations(seal.id)
            if (matches.isEmpty()) return conflict(ConflictReason.TargetMissing)
            if (matches.size != 1) return conflict(ConflictReason.IdCollision)
            val (kind, entry) = matches.single()
            if (kind != ControlKind.SEAL) return conflict(ConflictReason.IdCollision)
            if (entry !is ControlEntryRead.Interpreted) return conflict(ConflictReason.UninterpretableTarget)
            located += entry
        }
        val current = located.map { it.value as SealV1 }
        val ownWitnesses = current.count { it.settlement?.operationId == input.operationId }
        if (ownWitnesses != 0) {
            if (ownWitnesses != current.size) return recovery(RecoveryReason.InconsistentSettlement)
            for (index in current.indices) {
                if (!witnessMatches(checkNotNull(current[index].settlement), input.witness(input.seals[index]))) {
                    return recovery(RecoveryReason.InconsistentSettlement)
                }
                if (!immutableSealMatches(located[index].original, input.sealTargets[index].original)) {
                    return recovery(RecoveryReason.InconsistentSettlement)
                }
            }
            return RecordTransactionDecision.Confirm(read.original,
                Outcome.Positive(ConfirmedEffect.PostconditionConfirmed, input.effectiveIds, receipt(input, read)))
        }
        if (current.any { it.settlement != null }) return conflict(ConflictReason.TargetChanged)
        for (index in current.indices) {
            if (!unsettledTargetMatches(current[index], input.seals[index])) return conflict(ConflictReason.TargetChanged)
        }
        // Matching preimages are insufficient proof in either confirmation-only or completed history.
        if (confirmOnly || own != null) return conflict(if (own != null)
            ConflictReason.TargetChanged else ConflictReason.TargetMissing)
        if (previouslyConfirmed) return conflict(ConflictReason.TargetChanged)
        if (read.schemaVersion != 2) return recovery(RecoveryReason.ControlSchemaMigrationRequired)
        if (read.hasUninterpretableMetadata) return recovery(RecoveryReason.UninterpretableMetadata)
        val targetIds = input.seals.map { it.id }.toSet()
        if (read.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>().any {
                val seal = it.value as SealV1
                seal.id !in targetIds && seal.settlement?.operationId == input.operationId
            }) return conflict(ConflictReason.OperationIdCollision)

        val raw = read.original
        if (!raw.validType<String>(OWNER_UID)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(USER_EPOCH)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(KRX_EPOCH)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<Boolean>(MAY_CONTAIN_PREMIUM)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<Boolean>(MAY_CONTAIN_KRX)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(TEARDOWN_OWED_FOR)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(PURGE_JOURNAL)) return recovery(RecoveryReason.UnreadableEpochState)
        if (raw[OWNER_UID] != input.before.ownerUid) return conflict(ConflictReason.TargetChanged)
        if (raw[USER_EPOCH] != input.before.userAccessEpoch) return conflict(ConflictReason.TargetChanged)
        if (raw[KRX_EPOCH] != input.before.krxCapabilityEpoch) return conflict(ConflictReason.TargetChanged)
        if (raw[TEARDOWN_OWED_FOR] != null) return conflict(ConflictReason.IdentityTransitionPending)
        if (context == null) return reject("AttemptContextRequired")
        if (context.signOutOpen) return conflict(ConflictReason.IdentityTransitionPending)
        if (context.identityPersistencePending) return conflict(ConflictReason.IdentityTransitionPending)
        if (context.ownerUid != input.before.ownerUid) return conflict(ConflictReason.TargetChanged)
        if (context.binding != input.demand.binding) return conflict(ConflictReason.TargetChanged)
        if (context.originLifetimeId != input.originLifetimeId) return conflict(ConflictReason.TargetChanged)
        val journal = canonicalJournal(raw) ?: return recovery(RecoveryReason.JournalMigrationRequired)
        if (!epochsAreUnused(input, read, journal)) return reject("EpochNotFresh")
        if (read.locations(input.demandId).isNotEmpty()) return conflict(ConflictReason.IdCollision)

        val built = when (val result = buildCandidate(input, read)) {
            is CandidateBuild.Rejected -> return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), result.reason, read))
            is CandidateBuild.Built -> result
        }
        val candidate = built.candidate.toMutablePreferences()
        val evidence = AppliedEvidence.Rotation(command.id, command.ownerTrackingLifetimeId.value,
            input.seals.map { it.id }, input.demandId)
        ControlAppliedEvidence.append(candidate, read, evidence, codec)?.let {
            return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), it, read))
        }
        val complete = ControlRecordReader(codec).read(candidate)
        if (!validCandidate(complete, built.expected) || (complete as? ControlRecordRead.Supported)?.hasUninterpretableMetadata != false) return reject("InvalidCandidate")
        return RecordTransactionDecision.Confirm(candidate,
            Outcome.Positive(ConfirmedEffect.AppliedThisAttempt, input.effectiveIds,
                receipt(input, complete as ControlRecordRead.Supported)))
    }

    /** Admission has succeeded. Construct locally; the caller must validate before requesting storage. */
    internal fun buildCandidate(input: RotateAndSettleNamespaces, read: ControlRecordRead.Supported): CandidateBuild {
        val raw = read.original
        val arrays = read.arrays.mapValues { (_, array) -> array.entries.map { it.payload() }.toMutableList() }
        val expected = linkedMapOf<String, Pair<ControlKind, ControlNode>>()
        for (target in input.sealTargets) {
            val settled = settle(target.original, input.witness(target.seal))
                ?: return CandidateBuild.Rejected(RejectionReason.InvalidRequest("InvalidCandidate"))
            val position = read.arrays.getValue(ControlKind.SEAL).entries.indexOfFirst { (it as? ControlEntryRead.Interpreted)?.value?.id == target.seal.id }
            arrays.getValue(ControlKind.SEAL)[position] = settled.toPayloadEntry()
            expected[target.seal.id] = ControlKind.SEAL to settled
        }
        val demand = demandNode(input) ?: return CandidateBuild.Rejected(RejectionReason.InvalidRequest("InvalidDemand"))
        arrays.getValue(ControlKind.DEMAND) += demand.toPayloadEntry()
        expected[input.demandId] = ControlKind.DEMAND to demand
        val candidate = raw.toMutablePreferences()
        if (PurgeScope.USER in input.axes) {
            candidate[USER_EPOCH] = checkNotNull(input.newUserEpoch)
            candidate[MAY_CONTAIN_PREMIUM] = false
        }
        if (PurgeScope.CAPABILITY in input.axes) {
            candidate[KRX_EPOCH] = checkNotNull(input.newKrxEpoch)
            candidate[MAY_CONTAIN_KRX] = false
        }
        candidate[PURGE_JOURNAL] = listOfNotNull(raw[PURGE_JOURNAL], input.journal.encode()).joinToString(ENTRY_SEPARATOR)
        for (kind in listOf(ControlKind.SEAL, ControlKind.DEMAND)) {
            when (val encoded = encodeChanged(kind, arrays.getValue(kind))) {
                is ChangedPayload.Rejected -> return CandidateBuild.Rejected(encoded.reason)
                is ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(kind)] = encoded.text
            }
        }
        return CandidateBuild.Built(candidate, expected)
    }

    internal sealed interface CandidateBuild {
        data class Built(val candidate: Preferences, val expected: Map<String, Pair<ControlKind, ControlNode>>) : CandidateBuild
        data class Rejected(val reason: RejectionReason) : CandidateBuild
    }

    /** Required rejection guard for retained journal epochs and nested epoch fields in every array.
     * This is not a history of all issued epochs: erased UUIDs cannot be detected here. Production
     * issuance relies on random UUIDs fixed during preparation and retained across retries.
     * Opaque rows are scanned too; excluding reuse grants no interpretation or authority.
     * Run only for a new application: its own persisted epoch is expected during reconfirmation.
     */
    internal fun epochsAreUnused(input: RotateAndSettleNamespaces, read: ControlRecordRead.Supported,
        journal: List<PendingPurge>): Boolean {
        return epochsAreUnused(input.newEpochs().filterNotNull(), read, journal)
    }

    internal fun epochsAreUnused(fresh: List<String>, read: ControlRecordRead.Supported,
        journal: List<PendingPurge>): Boolean {
        if (journal.any { it.userAccessEpoch in fresh }) return false
        if (journal.any { it.krxCapabilityEpoch in fresh }) return false
        for ((_, array) in read.arrays) {
            if (array.entries.any { entry ->
                val tree = when (val payload = entry.payload()) {
                    is PayloadEntry.Obj -> payload.fields
                    is PayloadEntry.Uninterpretable -> payload.raw
                }
                tree.containsEpoch(fresh)
            }) return false
        }
        return true
    }

    /** Schema epoch names, at any depth (including future wrappers and arrays in opaque rows). */
    private fun JsonElement.containsEpoch(fresh: List<String>, epochField: Boolean = false): Boolean = when (this) {
        is JsonObject -> any { (name, value) -> value.containsEpoch(fresh,
            epochField || name in setOf("epoch", "userAccessEpoch", "krxCapabilityEpoch", "targetEpoch")) }
        is JsonArray -> any { it.containsEpoch(fresh, epochField) }
        is JsonPrimitive -> epochField && isString && content in fresh
    }

    /** Exact preimage comparison, independently testable from D1's kind/epoch domain constraints. */
    internal fun unsettledTargetMatches(actual: SealV1, expected: SealV1): Boolean {
        if (actual.kind != expected.kind) return false
        if (actual.key.ownerUid != expected.key.ownerUid) return false
        if (actual.key.axis != expected.key.axis) return false
        if (actual.key.epoch != expected.key.epoch) return false
        return true
    }

    /** Independent boundary: D1 may reject malformed evidence before this exact comparison. */
    internal fun witnessMatches(actual: SettlementEvidence, expected: SettlementEvidenceV1): Boolean {
        if (actual !is SettlementEvidenceV1) return false
        if (actual.operationId != expected.operationId) return false
        if (actual.originLifetimeId != expected.originLifetimeId) return false
        if (actual.operation != expected.operation) return false
        if (actual.before.ownerUid != expected.before.ownerUid) return false
        if (actual.before.userAccessEpoch != expected.before.userAccessEpoch) return false
        if (actual.before.krxCapabilityEpoch != expected.before.krxCapabilityEpoch) return false
        if (actual.after.ownerUid != expected.after.ownerUid) return false
        if (actual.after.userAccessEpoch != expected.after.userAccessEpoch) return false
        if (actual.after.krxCapabilityEpoch != expected.after.krxCapabilityEpoch) return false
        if (actual.journal.ownerUid != expected.journal.ownerUid) return false
        if (actual.journal.axis != expected.journal.axis) return false
        if (actual.journal.epoch != expected.journal.epoch) return false
        return true
    }

    internal fun immutableSealMatches(actual: ControlNode, expected: ControlNode): Boolean =
        actual.toPayloadEntry().fields.filterKeys { it != "settlement" } == expected.toPayloadEntry().fields

    internal sealed interface ChangedPayload {
        data class Encoded(val text: String) : ChangedPayload
        data class Rejected(val reason: RejectionReason) : ChangedPayload
    }

    internal fun encodeChanged(kind: ControlKind, entries: List<PayloadEntry>): ChangedPayload {
        val encoded = try {
            codec.encode(entries)
        } catch (_: IllegalArgumentException) {
            return ChangedPayload.Rejected(RejectionReason.InvalidRequest("InvalidEnvelope"))
        }
        return when (encoded) {
            is PayloadWrite.TooLarge -> ChangedPayload.Rejected(RejectionReason.TooLarge(ControlPayloadKey.forKind(kind), encoded.bytes, encoded.limit))
            is PayloadWrite.Encoded -> ChangedPayload.Encoded(encoded.text)
        }
    }

    internal fun validCandidate(read: ControlRecordRead, expected: Map<String, Pair<ControlKind, ControlNode>>): Boolean {
        if (read !is ControlRecordRead.Supported) return false
        for ((id, required) in expected) {
            val matches = read.locations(id)
            if (matches.size != 1 || matches.single().first != required.first) return false
            val entry = matches.single().second as? ControlEntryRead.Interpreted ?: return false
            if (entry.original.toPayloadEntry() != required.second.toPayloadEntry()) return false
        }
        return true
    }

    internal fun settle(original: ControlNode, evidence: SettlementEvidenceV1): ControlNode? =
        (ControlObligations.editExisting(ControlKind.SEAL, original) {
            createChild("settlement") {
                set("operationId", ControlScalar.Text(evidence.operationId))
                set("originLifetimeId", ControlScalar.Text(evidence.originLifetimeId.value))
                set("operation", ControlScalar.Text(evidence.operation.name))
                objectField("before") { fence(evidence.before) }
                objectField("after") { fence(evidence.after) }
                objectField("journal") {
                    set("ownerUid", evidence.journal.ownerUid.scalar())
                    set("axis", ControlScalar.Text(evidence.journal.axis.name))
                    set("epoch", evidence.journal.epoch.scalar())
                }
            }
        } as? ControlWriteResult.Written)?.node

    private fun ControlBuilder.fence(fence: FenceV1) {
        set("ownerUid", fence.ownerUid.scalar())
        set("userAccessEpoch", fence.userAccessEpoch.scalar())
        set("krxCapabilityEpoch", fence.krxCapabilityEpoch.scalar())
    }

    internal fun demandNode(input: RotateAndSettleNamespaces): ControlNode? =
        (ControlObligations.build(ControlKind.DEMAND) {
            set("id", ControlScalar.Text(input.demandId))
            set("kind", ControlScalar.Text("REQUEST"))
            set("ownerUid", input.demand.ownerUid.scalar())
            set("binding", ControlScalar.Integer(input.demand.binding))
            set("originLifetimeId", ControlScalar.Text(input.demand.raisedAt.origin.value))
            set("raisedAt", ControlScalar.Integer(input.demand.raisedAt.value))
            set("intent", ControlScalar.Text(input.demand.intent.name))
        } as? ControlWriteResult.Written)?.node

    private fun receipt(input: RotateAndSettleNamespaces, read: ControlRecordRead.Supported): SettlementReceipt {
        val journal = canonicalJournal(read.original)
        val demand = read.locations(input.demandId)
        val demandEntry = demand.singleOrNull()?.takeIf { it.first == ControlKind.DEMAND }?.second as? ControlEntryRead.Interpreted
        val demandObservation = when {
            demand.isEmpty() -> DemandObservation.Absent
            demandEntry == null -> DemandObservation.Uninterpretable
            demandEntry.original.toPayloadEntry() == demandNode(input)?.toPayloadEntry() -> DemandObservation.Present
            else -> DemandObservation.Changed
        }
        val observations = input.seals.associate { seal -> seal.id to when {
            journal == null -> JournalObservation.Uninterpretable
            input.journal in journal -> JournalObservation.Present
            journal.any { it.covers(seal.key) } -> JournalObservation.Covered
            else -> JournalObservation.Absent
        } }
        return SettlementReceipt(input.operationId, input.originLifetimeId, input.before, input.after,
            input.seals.associate { it.id to input.witness(it) }, observations, input.demandId, demandObservation,
            read.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
                .map { it.value as SealV1 }.filter { it.settlement == null }, read.hasUninterpretable, read.hasUninterpretableMetadata)
    }

    private fun PendingPurge.covers(key: SealKey): Boolean = key.axis in scopes &&
        (ownerUid == null || ownerUid == key.ownerUid) &&
        (if (key.axis == PurgeScope.USER) userAccessEpoch else krxCapabilityEpoch).let { it == null || it == key.epoch }

    /** Accept only byte-for-byte canonical four-field legacy lines. Null key is the empty journal. */
    internal fun canonicalJournal(raw: Preferences): List<PendingPurge>? {
        if (!raw.validType<String>(PURGE_JOURNAL)) return null
        val value = raw[PURGE_JOURNAL] ?: return emptyList()
        return value.split(ENTRY_SEPARATOR).map { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size != 4) return null
            val names = parts[3].split(SCOPE_SEPARATOR)
            val scopes = names.map { name -> PurgeScope.entries.find { it.name == name } ?: return null }.toSet()
            val entry = PendingPurge(parts[0].ifEmpty { null }, parts[1].ifEmpty { null }, parts[2].ifEmpty { null }, scopes)
            if (entry.encode() != line) return null
            entry
        }
    }
}

internal inline fun <reified T> Preferences.validType(key: Preferences.Key<*>): Boolean =
    asMap().entries.find { it.key.name == key.name }?.value.let { it == null || it is T }

internal fun ControlRecordRead.Supported.locations(id: String): List<Pair<ControlKind, ControlEntryRead>> =
    arrays.flatMap { (kind, array) -> array.entries.mapNotNull { entry ->
        val node = when (entry) {
            is ControlEntryRead.Interpreted -> entry.original
            is ControlEntryRead.Uninterpretable -> (entry.original as? PayloadEntry.Obj)?.let { ControlNode.of(it.fields) }
        }
        if ((node?.text("id") as? FieldRead.Present)?.value == id) kind to entry else null
    } }

internal fun ControlEntryRead.payload(): PayloadEntry = when (this) {
    is ControlEntryRead.Interpreted -> original.toPayloadEntry()
    is ControlEntryRead.Uninterpretable -> original
}

private fun String?.scalar(): ControlScalar = if (this == null) ControlScalar.Null else ControlScalar.Text(this)
