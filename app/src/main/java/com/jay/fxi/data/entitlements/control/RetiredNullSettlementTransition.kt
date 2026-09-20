package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR
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

/** L: historical NULL handover for one departed owner; the executor namespace is preserved. */
internal class RetiredNullSettlementTransition(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: RetiredNullSettlement, read: ControlRecordRead.Supported,
        context: AttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> {
        fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, input.ordered.map { it.seal.id }), read))
        fun recovery(reason: RecoveryReason) = negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), reason, read))
        RetiredNamespaceSettlementTransition.recordProblem(read)?.let { return recovery(it) }
        invalidInput(input)?.let { return reject(it) }
        val own = ControlAppliedEvidence.own(read, command)
        if (own != null && !ControlAppliedEvidence.matches(command, TrackedControlCommand(command), own)) {
            return conflict(ConflictReason.CommandEvidenceMismatch)
        }
        val operationSeals = seals(read).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet()
        val located = mutableListOf<ControlEntryRead.Interpreted>()
        for (target in input.ordered) {
            val matches = read.locations(target.seal.id)
            if (matches.isEmpty()) return conflict(ConflictReason.TargetMissing)
            if (matches.size != 1 || matches.single().first != ControlKind.SEAL) return conflict(ConflictReason.IdCollision)
            val entry = matches.single().second as? ControlEntryRead.Interpreted ?: return conflict(ConflictReason.UninterpretableTarget)
            located += entry
        }
        val current = located.map { it.value as SealV1 }
        val targetIds = input.ordered.map { it.seal.id }.toSet()
        val ownWitnesses = current.count { it.settlement?.operationId == input.operationId }
        if (ownWitnesses != 0) {
            if (operationSeals != targetIds) return recovery(RecoveryReason.InconsistentSettlement)
            for (index in current.indices) {
                if (!witnessMatches(checkNotNull(current[index].settlement), witness(input, input.ordered[index].seal))) return recovery(RecoveryReason.InconsistentSettlement)
                if (!shared.immutableSealMatches(located[index].original, input.ordered[index].original)) return recovery(RecoveryReason.InconsistentSettlement)
            }
            return RecordTransactionDecision.Confirm(read.original,
                Outcome.Positive(ConfirmedEffect.PostconditionConfirmed, input.effectiveIds, receipt(input, read)))
        }
        if (current.any { it.settlement != null }) return conflict(ConflictReason.TargetChanged)
        for (index in current.indices) {
            if (located[index].original.toPayloadEntry() != input.ordered[index].original.toPayloadEntry()) return conflict(ConflictReason.TargetChanged)
        }
        if (confirmOnly || own != null || previouslyConfirmed) return conflict(ConflictReason.TargetChanged)
        if (operationSeals.isNotEmpty()) return conflict(ConflictReason.OperationIdCollision)
        if (input.ordered.any { target -> seals(read).count { it.settlement == null && it.key == target.seal.key } != 1 }) return conflict(ConflictReason.AmbiguousSealKey)

        val raw = read.original
        rawProblem(raw)?.let { return recovery(it) }
        if (raw[OWNER_UID] != input.before.ownerUid) return conflict(ConflictReason.TargetChanged)
        if (raw[USER_EPOCH] != input.before.userAccessEpoch) return conflict(ConflictReason.TargetChanged)
        if (raw[KRX_EPOCH] != input.before.krxCapabilityEpoch) return conflict(ConflictReason.TargetChanged)
        if (raw[TEARDOWN_OWED_FOR] != null) return conflict(ConflictReason.IdentityTransitionPending)
        if (context == null) return reject("AttemptContextRequired")
        if (context.signOutOpen) return conflict(ConflictReason.IdentityTransitionPending)
        if (context.identityPersistencePending) return conflict(ConflictReason.IdentityTransitionPending)
        if (context.ownerUid != input.executor.ownerUid) return conflict(ConflictReason.TargetChanged)
        if (context.binding != input.executor.binding) return conflict(ConflictReason.TargetChanged)
        if (context.originLifetimeId != input.executor.originLifetimeId) return conflict(ConflictReason.TargetChanged)
        val journal = shared.canonicalJournal(raw) ?: return recovery(RecoveryReason.JournalMigrationRequired)
        val candidate = when (val built = buildCandidate(command, input, read, journal)) {
            is Candidate.Rejected -> return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), built.reason, read))
            is Candidate.Built -> built.snapshot
        }
        if (!validCandidate(command, input, read, candidate)) return reject("InvalidCandidate")
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            input.effectiveIds, receipt(input, ControlRecordReader(codec).read(candidate) as ControlRecordRead.Supported)))
    }

    /** Raw storage domains are independent of fixed-input and candidate comparisons. */
    internal fun rawProblem(raw: Preferences): RecoveryReason? {
        if (!raw.validType<String>(OWNER_UID)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<String>(USER_EPOCH)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<String>(KRX_EPOCH)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<Boolean>(MAY_CONTAIN_PREMIUM)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<Boolean>(MAY_CONTAIN_KRX)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<String>(TEARDOWN_OWED_FOR)) return RecoveryReason.UnreadableEpochState
        if (!raw.validType<String>(PURGE_JOURNAL)) return RecoveryReason.UnreadableEpochState
        if (raw[USER_EPOCH] == "") return RecoveryReason.UnreadableEpochState
        if (raw[KRX_EPOCH] == "") return RecoveryReason.UnreadableEpochState
        return null
    }

    internal fun invalidInput(input: RetiredNullSettlement): String? {
        if (input.targets.isEmpty()) return "EmptyTargets"
        if (input.targets.size > 2) return "TooManyTargets"
        if (input.ordered.size != input.targets.size) return "UnsupportedTargetKind"
        if (input.ordered.any { it.seal.kind != SealTargetKind.NULL_NAMESPACE }) return "UnsupportedTargetKind"
        if (input.ordered.any { it.seal.settlement != null }) return "AlreadySettledInput"
        if (input.effectiveIds.distinct().size != input.ordered.size) return "DuplicateTargetId"
        if (input.ordered.map { it.seal.key.axis }.distinct().size != input.ordered.size) return "DuplicateAxis"
        if (input.ordered.map { it.seal.key.ownerUid }.distinct().size != 1) return "MixedSubjectOwners"
        if (input.ordered.first().seal.key.ownerUid == input.before.ownerUid) return "SubjectOwnerIsCurrent"
        if (input.operationId.isEmpty()) return "InvalidOperationId"
        if (input.executor.ownerUid != input.before.ownerUid) return "ExecutorFenceMismatch"
        if (input.executor.binding < 0) return "InvalidExecutor"
        if (input.executor.originLifetimeId.value.isEmpty()) return "InvalidExecutor"
        if (!RetiredNamespaceSettlementTransition(codec).journalField(input.ordered.first().seal.key.ownerUid)) return "UnrepresentableJournalField"
        return null
    }

    internal sealed interface Candidate {
        data class Built(val snapshot: Preferences) : Candidate
        data class Rejected(val reason: RejectionReason) : Candidate
    }

    internal fun buildCandidate(command: CommandRef, input: RetiredNullSettlement, read: ControlRecordRead.Supported,
        journal: List<PendingPurge>): Candidate {
        val settled = input.ordered.associate { target -> target.seal.id to
            (settle(target.original, witness(input, target.seal))
                ?: return Candidate.Rejected(RejectionReason.InvalidRequest("InvalidCandidate"))) }
        val candidate = read.original.toMutablePreferences()
        val sealRows = read.arrays.getValue(ControlKind.SEAL).entries.map {
            settled[(it as? ControlEntryRead.Interpreted)?.value?.id]?.toPayloadEntry() ?: it.payload()
        }
        when (val encoded = shared.encodeChanged(ControlKind.SEAL, sealRows)) {
            is NamespaceSettlementTransition.ChangedPayload.Rejected -> return Candidate.Rejected(encoded.reason)
            is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.SEAL)] = encoded.text
        }
        val missing = journals(input).filterNot { it in journal }
        if (missing.isNotEmpty()) candidate[PURGE_JOURNAL] = (listOfNotNull(read.original[PURGE_JOURNAL]) + missing.map { it.encode() }).joinToString(ENTRY_SEPARATOR)
        ControlAppliedEvidence.append(candidate, read, evidence(command, input), codec)?.let { return Candidate.Rejected(it) }
        return Candidate.Built(candidate)
    }

    internal fun validCandidate(command: CommandRef, input: RetiredNullSettlement,
        before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = ControlRecordReader(codec).read(candidate) as? ControlRecordRead.Supported ?: return false
        if (RetiredNamespaceSettlementTransition.recordProblem(after) != null) return false
        val targetIds = input.ordered.map { it.seal.id }.toSet()
        for (target in input.ordered) {
            val located = after.locations(target.seal.id).singleOrNull()?.takeIf { it.first == ControlKind.SEAL }?.second as? ControlEntryRead.Interpreted ?: return false
            val actual = located.value as SealV1
            if (!shared.immutableSealMatches(located.original, target.original)) return false
            if (actual.settlement == null || !witnessMatches(actual.settlement, witness(input, target.seal))) return false
        }
        if (seals(after).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet() != targetIds) return false
        val oldJournal = shared.canonicalJournal(before.original) ?: return false
        val missing = journals(input).filterNot { it in oldJournal }
        val expectedJournal = if (missing.isEmpty()) before.original[PURGE_JOURNAL] else
            (listOfNotNull(before.original[PURGE_JOURNAL]) + missing.map { it.encode() }).joinToString(ENTRY_SEPARATOR)
        if (candidate[PURGE_JOURNAL] != expectedJournal) return false
        // Exact full-array linkage also proves the single own Applied row and every field.
        val oldMetadata = before.metadata as ControlMetadataRead.V2
        val newMetadata = after.metadata as ControlMetadataRead.V2
        if (newMetadata.evidence.entries.map { it.payload() } != oldMetadata.evidence.entries.map { it.payload() } + PayloadEntry.Obj(ControlAppliedEvidence.node(evidence(command, input)))) return false
        if (after.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id in targetIds }.map { it.payload() } !=
            before.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id in targetIds }.map { it.payload() }) return false
        val changed = setOf(ControlRecordKeys.payload(ControlKind.SEAL),
            ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE), PURGE_JOURNAL)
        if (candidate.toMutablePreferences().apply { changed.forEach { remove(it) } } !=
            before.original.toMutablePreferences().apply { changed.forEach { remove(it) } }) return false
        return true
    }

    private fun ControlEvidenceEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEvidenceEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEvidenceEntryRead.Uninterpretable -> original
    }
    internal fun witness(input: RetiredNullSettlement, seal: SealV1) = RetiredNullSettlementEvidenceV2(
        input.operationId, input.executor.originLifetimeId, input.before, input.before,
        JournalTargetV1(seal.key.ownerUid, seal.key.axis, null))

    /** Exact fixed witness comparison, independent of the reader's static consistency checks. */
    internal fun witnessMatches(actual: SettlementEvidence, expected: RetiredNullSettlementEvidenceV2): Boolean {
        if (actual !is RetiredNullSettlementEvidenceV2) return false
        if (actual.operationId != expected.operationId) return false
        if (actual.originLifetimeId != expected.originLifetimeId) return false
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

    internal fun settle(original: ControlNode, evidence: RetiredNullSettlementEvidenceV2): ControlNode? =
        (ControlObligations.editExisting(ControlKind.SEAL, original) {
            createChild("settlement") {
                set("version", ControlScalar.Integer(2))
                set("kind", ControlScalar.Text("RETIRED_NULL"))
                set("operationId", ControlScalar.Text(evidence.operationId))
                set("originLifetimeId", ControlScalar.Text(evidence.originLifetimeId.value))
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
    private fun String?.scalar() = this?.let(ControlScalar::Text) ?: ControlScalar.Null
    private fun journals(input: RetiredNullSettlement) = input.ordered.map {
        PendingPurge(it.seal.key.ownerUid, null, null, setOf(it.seal.key.axis))
    }
    internal fun receipt(input: RetiredNullSettlement, read: ControlRecordRead.Supported): HandoverSettlementReceipt {
        val journal = shared.canonicalJournal(read.original)
        val observations = input.ordered.associate { target -> target.seal.id to when {
            journal == null -> JournalObservation.Uninterpretable
            PendingPurge(target.seal.key.ownerUid, null, null, setOf(target.seal.key.axis)) in journal -> JournalObservation.Present
            journal.any { target.seal.key.axis in it.scopes && (it.ownerUid == null || it.ownerUid == target.seal.key.ownerUid) &&
                (if (target.seal.key.axis == PurgeScope.USER) it.userAccessEpoch else it.krxCapabilityEpoch) == null } -> JournalObservation.Covered
            else -> JournalObservation.Absent
        } }
        return HandoverSettlementReceipt(HandoverSettlementTransition.RETIRED_NULL, input.operationId,
            input.executor.originLifetimeId, input.before, input.before,
            input.ordered.associate { it.seal.id to witness(input, it.seal) }, observations, null, HandoverDemandObservation.NotRequired,
            seals(read).filter { it.settlement == null }, read.hasUninterpretable, read.hasUninterpretableMetadata)
    }
    private fun evidence(command: CommandRef, input: RetiredNullSettlement) = AppliedEvidence.Settlement(
        command.id, command.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL,
        input.effectiveIds, null)
    private fun seals(read: ControlRecordRead.Supported) = read.arrays.getValue(ControlKind.SEAL).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().map { it.value as SealV1 }
}
