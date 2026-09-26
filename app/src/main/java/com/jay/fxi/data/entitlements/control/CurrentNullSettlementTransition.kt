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
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome

/** N: current NULL obligations and the complete fixed set of current namespace companions. */
internal class CurrentNullSettlementTransition(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: CurrentNullSettlement, read: ControlRecordRead.Supported,
        context: AttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> =
        decide(command, checkNotNull(command.captureStateAndBody().body),
            input, read, context, confirmOnly, previouslyConfirmed)

    fun decide(command: CommandRef, body: ControlCommandBody,
        input: CurrentNullSettlement, read: ControlRecordRead.Supported,
        context: AttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> {
        fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, input.targets.map { it.seal.id }), read))
        fun recovery(reason: RecoveryReason) = negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), reason, read))
        RetiredNamespaceSettlementTransition.recordProblem(read)?.let { return recovery(it) }
        invalidInput(input)?.let { return reject(it) }
        val own = ControlAppliedEvidence.own(read, command)
        // Null matches the former temporary tracked command: expectedApplied is not compared.
        if (own != null && !ControlAppliedEvidence.matches(command, body, null, own)) {
            return conflict(ConflictReason.CommandEvidenceMismatch)
        }
        val operationSeals = seals(read).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet()
        val located = mutableListOf<ControlEntryRead.Interpreted>()
        for (target in input.targets) {
            val matches = read.locations(target.seal.id)
            if (matches.isEmpty()) return conflict(if (target in input.accompanying && !confirmOnly &&
                !previouslyConfirmed && own == null && operationSeals.isEmpty()) ConflictReason.TargetChanged else ConflictReason.TargetMissing)
            if (matches.size != 1 || matches.single().first != ControlKind.SEAL) return conflict(ConflictReason.IdCollision)
            val entry = matches.single().second as? ControlEntryRead.Interpreted ?: return conflict(ConflictReason.UninterpretableTarget)
            located += entry
        }
        val current = located.map { it.value as SealV1 }
        val targetIds = input.targets.map { it.seal.id }.toSet()
        val ownWitnesses = current.count { it.settlement?.operationId == input.operationId }
        if (ownWitnesses != 0) {
            if (operationSeals != targetIds) return recovery(RecoveryReason.InconsistentSettlement)
            for (index in current.indices) {
                if (!shared.witnessMatches(checkNotNull(current[index].settlement), witness(input, input.targets[index].seal))) return recovery(RecoveryReason.InconsistentSettlement)
                if (!shared.immutableSealMatches(located[index].original, input.targets[index].original)) return recovery(RecoveryReason.InconsistentSettlement)
            }
            return RecordTransactionDecision.Confirm(read.original,
                Outcome.Positive(ConfirmedEffect.PostconditionConfirmed, input.effectiveIds, receipt(input, read)))
        }
        if (current.any { it.settlement != null }) return conflict(ConflictReason.TargetChanged)
        for (index in current.indices) {
            if (located[index].original.toPayloadEntry() != input.targets[index].original.toPayloadEntry()) return conflict(ConflictReason.TargetChanged)
        }
        if (confirmOnly || own != null || previouslyConfirmed) return conflict(ConflictReason.TargetChanged)
        if (operationSeals.isNotEmpty()) return conflict(ConflictReason.OperationIdCollision)
        if (input.targets.any { target -> seals(read).count { it.settlement == null && it.key == target.seal.key } != 1 }) return conflict(ConflictReason.AmbiguousSealKey)

        val raw = read.original
        if (!raw.validType<String>(OWNER_UID)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(USER_EPOCH)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(KRX_EPOCH)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<Boolean>(MAY_CONTAIN_PREMIUM)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<Boolean>(MAY_CONTAIN_KRX)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(TEARDOWN_OWED_FOR)) return recovery(RecoveryReason.UnreadableEpochState)
        if (!raw.validType<String>(PURGE_JOURNAL)) return recovery(RecoveryReason.UnreadableEpochState)
        if (raw[USER_EPOCH] == "") return recovery(RecoveryReason.UnreadableEpochState)
        if (raw[KRX_EPOCH] == "") return recovery(RecoveryReason.UnreadableEpochState)
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
        val latestCompanions = companions(read, input.before, input.axes)
        if (latestCompanions.groupBy { (it.value as SealV1).key }.any { it.value.size > 1 }) return conflict(ConflictReason.AmbiguousSealKey)
        if (latestCompanions.associate { it.value.id to it.original.toPayloadEntry() } !=
            input.accompanying.associate { it.seal.id to it.original.toPayloadEntry() }) return conflict(ConflictReason.TargetChanged)
        val journal = shared.canonicalJournal(raw) ?: return recovery(RecoveryReason.JournalMigrationRequired)
        if (!shared.epochsAreUnused(input.newEpochs().filterNotNull(), read, journal)) return reject("EpochNotFresh")
        if (read.locations(input.demandId).isNotEmpty()) return conflict(ConflictReason.IdCollision)
        val candidate = when (val built = buildCandidate(command, input, read, journal)) {
            is Candidate.Rejected -> return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), built.reason, read))
            is Candidate.Built -> built.snapshot
        }
        if (!validCandidate(command, input, read, candidate)) return reject("InvalidCandidate")
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            input.effectiveIds, receipt(input, ControlRecordReader(codec).read(candidate) as ControlRecordRead.Supported)))
    }

    internal fun invalidInput(input: CurrentNullSettlement): String? {
        if (input.nullTargets.isEmpty()) return "EmptyTargets"
        if (input.nulls.size != input.nullTargets.size || input.accompanying.size != input.companions.size) return "UnsupportedTargetKind"
        if (input.nulls.any { it.seal.kind != SealTargetKind.NULL_NAMESPACE }) return "UnsupportedTargetKind"
        if (input.accompanying.any { it.seal.kind != SealTargetKind.NAMESPACE }) return "UnsupportedCompanionKind"
        if (input.targets.any { it.seal.settlement != null }) return "AlreadySettledInput"
        if (input.targets.map { it.seal.id }.distinct().size != input.targets.size) return "DuplicateTargetId"
        if (input.axes.size != input.nulls.size) return "DuplicateAxis"
        if (input.accompanying.map { it.seal.key.axis }.distinct().size != input.accompanying.size) return "DuplicateCompanionAxis"
        if (input.targets.any { it.seal.key.ownerUid != input.before.ownerUid }) return "TargetFenceMismatch"
        if (input.accompanying.any { it.seal.key.axis !in input.axes }) return "CompanionAxisMismatch"
        if (input.accompanying.any { it.seal.key.epoch != input.before.epoch(it.seal.key.axis) }) return "CompanionEpochMismatch"
        if (input.operationId.isEmpty()) return "InvalidOperationId"
        if (input.executor.ownerUid != input.before.ownerUid) return "ExecutorFenceMismatch"
        if (input.executor.binding < 0) return "InvalidExecutor"
        if (input.executor.originLifetimeId.value.isEmpty()) return "InvalidExecutor"
        val fresh = input.newEpochs()
        if (fresh.any { it == null }) return "EpochNotFresh"
        if (fresh.any { it == "" }) return "EpochNotFresh"
        if (fresh.any { it == input.before.userAccessEpoch }) return "EpochNotFresh"
        if (fresh.any { it == input.before.krxCapabilityEpoch }) return "EpochNotFresh"
        if (fresh.distinct().size != fresh.size) return "EpochNotFresh"
        if (fresh.filterNotNull().filter { it.isNotEmpty() }.any { !isCanonicalUuid(it) }) return "InvalidEpochUuid"
        if (PurgeScope.USER !in input.axes && input.newUserEpoch != null) return "UnexpectedEpoch"
        if (PurgeScope.CAPABILITY !in input.axes && input.newKrxEpoch != null) return "UnexpectedEpoch"
        if (input.demandId.isEmpty()) return "InvalidDemand"
        if (input.demandId in input.targets.map { it.seal.id }) return "InvalidDemand"
        val demand = input.demand
        if (demand.ownerUid != input.executor.ownerUid) return "DemandScopeMismatch"
        if (demand.binding != input.executor.binding) return "DemandScopeMismatch"
        if (demand.raisedAt.origin != input.executor.originLifetimeId) return "DemandScopeMismatch"
        if (demand.raisedAt.value < 0) return "InvalidDemand"
        if (PurgeScope.USER in input.axes && demand.intent != RefreshIntent.FORCE_PREMIUM) return "InsufficientIntent"
        if (PurgeScope.CAPABILITY in input.axes && demand.intent == RefreshIntent.IF_STALE) return "InsufficientIntent"
        if (!RetiredNamespaceSettlementTransition(codec).journalField(input.before.ownerUid)) return "UnrepresentableJournalField"
        return null
    }

    internal sealed interface Candidate {
        data class Built(val snapshot: Preferences) : Candidate
        data class Rejected(val reason: RejectionReason) : Candidate
    }

    internal fun buildCandidate(command: CommandRef, input: CurrentNullSettlement, read: ControlRecordRead.Supported,
        journal: List<PendingPurge>): Candidate {
        val settled = input.targets.associate { target -> target.seal.id to
            (shared.settle(target.original, witness(input, target.seal))
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
        val node = demandNode(input) ?: return Candidate.Rejected(RejectionReason.InvalidRequest("InvalidDemand"))
        val rows = read.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } + node.toPayloadEntry()
        when (val encoded = shared.encodeChanged(ControlKind.DEMAND, rows)) {
            is NamespaceSettlementTransition.ChangedPayload.Rejected -> return Candidate.Rejected(encoded.reason)
            is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.DEMAND)] = encoded.text
        }
        if (PurgeScope.USER in input.axes) {
            candidate[USER_EPOCH] = checkNotNull(input.newUserEpoch)
            candidate[MAY_CONTAIN_PREMIUM] = false
        }
        if (PurgeScope.CAPABILITY in input.axes) {
            candidate[KRX_EPOCH] = checkNotNull(input.newKrxEpoch)
            candidate[MAY_CONTAIN_KRX] = false
        }
        ControlAppliedEvidence.append(candidate, read, evidence(command, input), codec)?.let { return Candidate.Rejected(it) }
        return Candidate.Built(candidate)
    }

    internal fun validCandidate(command: CommandRef, input: CurrentNullSettlement,
        before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = ControlRecordReader(codec).read(candidate) as? ControlRecordRead.Supported ?: return false
        if (RetiredNamespaceSettlementTransition.recordProblem(after) != null) return false
        val targetIds = input.targets.map { it.seal.id }.toSet()
        for (target in input.targets) {
            val located = after.locations(target.seal.id).singleOrNull()?.takeIf { it.first == ControlKind.SEAL }?.second as? ControlEntryRead.Interpreted ?: return false
            val actual = located.value as SealV1
            if (!shared.immutableSealMatches(located.original, target.original)) return false
            if (actual.settlement == null || !shared.witnessMatches(actual.settlement, witness(input, target.seal))) return false
        }
        if (seals(after).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet() != targetIds) return false
        val oldJournal = shared.canonicalJournal(before.original) ?: return false
        val missing = journals(input).filterNot { it in oldJournal }
        val expectedJournal = if (missing.isEmpty()) before.original[PURGE_JOURNAL] else
            (listOfNotNull(before.original[PURGE_JOURNAL]) + missing.map { it.encode() }).joinToString(ENTRY_SEPARATOR)
        if (candidate[PURGE_JOURNAL] != expectedJournal) return false
        val expectedDemands = before.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } + checkNotNull(demandNode(input)).toPayloadEntry()
        if (after.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } != expectedDemands) return false
        // Exact full-array linkage also proves the single own Applied row and every field.
        val oldMetadata = before.metadata as ControlMetadataRead.V2
        val newMetadata = after.metadata as ControlMetadataRead.V2
        if (newMetadata.evidence.entries.map { it.payload() } != oldMetadata.evidence.entries.map { it.payload() } + PayloadEntry.Obj(ControlAppliedEvidence.node(evidence(command, input)))) return false
        if (after.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id in targetIds }.map { it.payload() } !=
            before.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id in targetIds }.map { it.payload() }) return false
        if (candidate[USER_EPOCH] != input.after.userAccessEpoch) return false
        if (candidate[KRX_EPOCH] != input.after.krxCapabilityEpoch) return false
        if (PurgeScope.USER in input.axes && candidate[MAY_CONTAIN_PREMIUM] != false) return false
        if (PurgeScope.CAPABILITY in input.axes && candidate[MAY_CONTAIN_KRX] != false) return false
        val changed = setOf(ControlRecordKeys.payload(ControlKind.SEAL), ControlRecordKeys.payload(ControlKind.DEMAND),
            ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE), PURGE_JOURNAL) +
            (if (PurgeScope.USER in input.axes) setOf(USER_EPOCH, MAY_CONTAIN_PREMIUM) else emptySet()) +
            (if (PurgeScope.CAPABILITY in input.axes) setOf(KRX_EPOCH, MAY_CONTAIN_KRX) else emptySet())
        if (candidate.toMutablePreferences().apply { changed.forEach { remove(it) } } !=
            before.original.toMutablePreferences().apply { changed.forEach { remove(it) } }) return false
        return true
    }

    private fun ControlEvidenceEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEvidenceEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEvidenceEntryRead.Uninterpretable -> original
    }
    internal fun witness(input: CurrentNullSettlement, seal: SealV1) = SettlementEvidenceV1(
        input.operationId, input.executor.originLifetimeId, StoreOp.BEGIN_ROTATION, input.before, input.after,
        JournalTargetV1(input.before.ownerUid, seal.key.axis, null))
    private fun journals(input: CurrentNullSettlement) = listOf(PurgeScope.USER, PurgeScope.CAPABILITY)
        .filter { it in input.axes }.map { PendingPurge(input.before.ownerUid, null, null, setOf(it)) }
    internal fun demandNode(input: CurrentNullSettlement): ControlNode? {
        val d = input.demand
        return (ControlObligations.build(ControlKind.DEMAND) {
            set("id", ControlScalar.Text(input.demandId))
            set("kind", ControlScalar.Text("REQUEST"))
            set("ownerUid", d.ownerUid?.let(ControlScalar::Text) ?: ControlScalar.Null)
            set("binding", ControlScalar.Integer(d.binding))
            set("originLifetimeId", ControlScalar.Text(d.raisedAt.origin.value))
            set("raisedAt", ControlScalar.Integer(d.raisedAt.value))
            set("intent", ControlScalar.Text(d.intent.name))
        } as? ControlWriteResult.Written)?.node
    }
    internal fun receipt(input: CurrentNullSettlement, read: ControlRecordRead.Supported): HandoverSettlementReceipt {
        val journal = shared.canonicalJournal(read.original)
        val observations = input.targets.associate { target -> target.seal.id to when {
            journal == null -> JournalObservation.Uninterpretable
            PendingPurge(input.before.ownerUid, null, null, setOf(target.seal.key.axis)) in journal -> JournalObservation.Present
            journal.any { target.seal.key.axis in it.scopes && (it.ownerUid == null || it.ownerUid == input.before.ownerUid) &&
                (if (target.seal.key.axis == PurgeScope.USER) it.userAccessEpoch else it.krxCapabilityEpoch) == null } -> JournalObservation.Covered
            else -> JournalObservation.Absent
        } }
        val demands = read.locations(input.demandId)
        val demand = demands.singleOrNull()?.takeIf { it.first == ControlKind.DEMAND }?.second as? ControlEntryRead.Interpreted
        val observedDemand = when {
            demands.isEmpty() -> HandoverDemandObservation.Absent
            demand == null -> HandoverDemandObservation.Uninterpretable
            demand.original.toPayloadEntry() == demandNode(input)?.toPayloadEntry() -> HandoverDemandObservation.Present
            else -> HandoverDemandObservation.Changed
        }
        return HandoverSettlementReceipt(HandoverSettlementTransition.CURRENT_NULL, input.operationId,
            input.executor.originLifetimeId, input.before, input.after,
            input.targets.associate { it.seal.id to witness(input, it.seal) }, observations, input.demandId, observedDemand,
            seals(read).filter { it.settlement == null }, read.hasUninterpretable, read.hasUninterpretableMetadata)
    }
    private fun evidence(command: CommandRef, input: CurrentNullSettlement) = AppliedEvidence.Settlement(
        command.id, command.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL,
        input.targets.map { it.seal.id }, input.demandId)
    private fun seals(read: ControlRecordRead.Supported) = read.arrays.getValue(ControlKind.SEAL).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().map { it.value as SealV1 }
    private fun isCanonicalUuid(value: String): Boolean = try { java.util.UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }

    companion object {
        internal fun companions(read: ControlRecordRead.Supported, before: FenceV1, axes: Set<PurgeScope>) =
            read.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>().filter {
                val seal = it.value as SealV1
                seal.settlement == null && seal.kind == SealTargetKind.NAMESPACE &&
                    seal.key.ownerUid == before.ownerUid && seal.key.axis in axes &&
                    seal.key.epoch == before.epoch(seal.key.axis)
            }
    }
}
