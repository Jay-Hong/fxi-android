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

/** R only. Pure decisions run within the existing owner's atomic transaction and tracking lease. */
internal class RetiredNamespaceSettlementTransition(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: RetiredNamespaceSettlement, read: ControlRecordRead.Supported,
        context: AttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> =
        decide(command, checkNotNull(command.captureStateAndBody().body),
            input, read, context, confirmOnly, previouslyConfirmed)

    fun decide(command: CommandRef, body: ControlCommandBody,
        input: RetiredNamespaceSettlement, read: ControlRecordRead.Supported,
        context: AttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> {
        fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, listOf(target(input)?.id)), read))
        fun recovery(reason: RecoveryReason) = negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), reason, read))
        recordProblem(read)?.let { return recovery(it) }
        invalidInput(input)?.let { return reject(it) }
        val seal = checkNotNull(target(input))
        val own = ControlAppliedEvidence.own(read, command)
        // Null matches the former temporary tracked command: expectedApplied is not compared.
        if (own != null && !ControlAppliedEvidence.matches(command, body, null, own)) {
            return conflict(ConflictReason.CommandEvidenceMismatch)
        }
        val located = read.locations(seal.id)
        if (located.isEmpty()) return conflict(ConflictReason.TargetMissing)
        if (located.size != 1 || located.single().first != ControlKind.SEAL) return conflict(ConflictReason.IdCollision)
        val entry = located.single().second as? ControlEntryRead.Interpreted ?: return conflict(ConflictReason.UninterpretableTarget)
        val current = entry.value as SealV1
        val operationSeals = seals(read).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet()
        if (current.settlement?.operationId == input.operationId) {
            if (operationSeals != setOf(seal.id)) return recovery(RecoveryReason.InconsistentSettlement)
            if (!shared.witnessMatches(checkNotNull(current.settlement), witness(input))) return recovery(RecoveryReason.InconsistentSettlement)
            if (!shared.immutableSealMatches(entry.original, input.target)) return recovery(RecoveryReason.InconsistentSettlement)
            return RecordTransactionDecision.Confirm(read.original,
                Outcome.Positive(ConfirmedEffect.PostconditionConfirmed, effectiveIds(input), receipt(input, read)))
        }
        if (current.settlement != null) return conflict(ConflictReason.TargetChanged)
        if (!targetMatches(current, seal)) return conflict(ConflictReason.TargetChanged)
        if (entry.original.toPayloadEntry() != input.target.toPayloadEntry()) return conflict(ConflictReason.TargetChanged)
        if (confirmOnly || own != null || previouslyConfirmed) return conflict(ConflictReason.TargetChanged)
        if (operationSeals.isNotEmpty()) return conflict(ConflictReason.OperationIdCollision)
        if (seals(read).count { it.settlement == null && it.key == seal.key } != 1) return conflict(ConflictReason.AmbiguousSealKey)

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
        val epoch = input.before.epoch(seal.key.axis)
        if (epoch == null) return recovery(RecoveryReason.UnreadableEpochState)
        if (epoch == seal.key.epoch) return conflict(ConflictReason.TargetChanged)
        val journal = shared.canonicalJournal(raw) ?: return recovery(RecoveryReason.JournalMigrationRequired)
        if (input.demandId != null && read.locations(input.demandId).isNotEmpty()) return conflict(ConflictReason.IdCollision)

        val candidate = when (val built = buildCandidate(command, input, read, journal)) {
            is Candidate.Rejected -> return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), built.reason, read))
            is Candidate.Built -> built.snapshot
        }
        if (!validCandidate(command, input, read, candidate)) return reject("InvalidCandidate")
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            effectiveIds(input), receipt(input, ControlRecordReader(codec).read(candidate) as ControlRecordRead.Supported)))
    }

    internal fun invalidInput(input: RetiredNamespaceSettlement): String? {
        val seal = target(input) ?: return "UnsupportedTargetKind"
        if (seal.kind != SealTargetKind.NAMESPACE) return "UnsupportedTargetKind"
        if (seal.settlement != null) return "AlreadySettledInput"
        if (input.operationId.isEmpty()) return "InvalidOperationId"
        if (input.executor.ownerUid != input.before.ownerUid) return "ExecutorFenceMismatch"
        if (input.executor.binding < 0) return "InvalidExecutor"
        if (input.executor.originLifetimeId.value.isEmpty()) return "InvalidExecutor"
        val required = seal.key.ownerUid == input.before.ownerUid
        if (required != (input.demand != null)) return "DemandRequirementMismatch"
        val demand = input.demand
        if (demand != null) {
            if (input.demandId.isNullOrEmpty()) return "InvalidDemand"
            if (input.demandId == seal.id) return "InvalidDemand"
            if (demand.ownerUid != input.executor.ownerUid) return "DemandScopeMismatch"
            if (demand.binding != input.executor.binding) return "DemandScopeMismatch"
            if (demand.raisedAt.origin != input.executor.originLifetimeId) return "DemandScopeMismatch"
            if (demand.raisedAt.value < 0) return "InvalidDemand"
            if (seal.key.axis == PurgeScope.USER && demand.intent != RefreshIntent.FORCE_PREMIUM) return "InsufficientIntent"
            if (seal.key.axis == PurgeScope.CAPABILITY && demand.intent == RefreshIntent.IF_STALE) return "InsufficientIntent"
        }
        if (!journalField(seal.key.ownerUid)) return "UnrepresentableJournalField"
        if (!journalField(seal.key.epoch)) return "UnrepresentableJournalField"
        return null
    }

    /** Direct boundary tests separate exact comparisons from the reader's domain rejection. */
    internal fun targetMatches(actual: SealV1, expected: SealV1): Boolean {
        if (actual.id != expected.id) return false
        if (actual.kind != expected.kind) return false
        if (actual.key.ownerUid != expected.key.ownerUid) return false
        if (actual.key.axis != expected.key.axis) return false
        if (actual.key.epoch != expected.key.epoch) return false
        return true
    }

    internal sealed interface Candidate {
        data class Built(val snapshot: Preferences) : Candidate
        data class Rejected(val reason: RejectionReason) : Candidate
    }

    internal fun buildCandidate(command: CommandRef, input: RetiredNamespaceSettlement, read: ControlRecordRead.Supported,
        journal: List<PendingPurge>): Candidate {
        val seal = checkNotNull(target(input))
        val settled = shared.settle(input.target, witness(input))
            ?: return Candidate.Rejected(RejectionReason.InvalidRequest("InvalidCandidate"))
        val candidate = read.original.toMutablePreferences()
        val sealRows = read.arrays.getValue(ControlKind.SEAL).entries.map {
            if ((it as? ControlEntryRead.Interpreted)?.value?.id == seal.id) settled.toPayloadEntry() else it.payload()
        }
        when (val encoded = shared.encodeChanged(ControlKind.SEAL, sealRows)) {
            is NamespaceSettlementTransition.ChangedPayload.Rejected -> return Candidate.Rejected(encoded.reason)
            is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.SEAL)] = encoded.text
        }
        val exact = journal(input)
        if (exact !in journal) candidate[PURGE_JOURNAL] = listOfNotNull(read.original[PURGE_JOURNAL], exact.encode()).joinToString(ENTRY_SEPARATOR)
        if (input.demand != null) {
            val node = demandNode(input) ?: return Candidate.Rejected(RejectionReason.InvalidRequest("InvalidDemand"))
            val rows = read.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } + node.toPayloadEntry()
            when (val encoded = shared.encodeChanged(ControlKind.DEMAND, rows)) {
                is NamespaceSettlementTransition.ChangedPayload.Rejected -> return Candidate.Rejected(encoded.reason)
                is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.DEMAND)] = encoded.text
            }
        }
        ControlAppliedEvidence.append(candidate, read, evidence(command, input), codec)?.let { return Candidate.Rejected(it) }
        return Candidate.Built(candidate)
    }

    /** Validate the whole candidate, including unrelated Preferences with content-based equality. */
    internal fun validCandidate(command: CommandRef, input: RetiredNamespaceSettlement,
        before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = ControlRecordReader(codec).read(candidate) as? ControlRecordRead.Supported ?: return false
        if (recordProblem(after) != null) return false
        val seal = checkNotNull(target(input))
        val located = after.locations(seal.id).singleOrNull()?.takeIf { it.first == ControlKind.SEAL }?.second as? ControlEntryRead.Interpreted ?: return false
        val actual = located.value as SealV1
        if (!shared.immutableSealMatches(located.original, input.target)) return false
        if (actual.settlement == null || !shared.witnessMatches(actual.settlement, witness(input))) return false
        if (seals(after).filter { it.settlement?.operationId == input.operationId }.map { it.id }.toSet() != setOf(seal.id)) return false
        val oldJournal = shared.canonicalJournal(before.original) ?: return false
        val expectedJournal = if (journal(input) in oldJournal) before.original[PURGE_JOURNAL] else
            listOfNotNull(before.original[PURGE_JOURNAL], journal(input).encode()).joinToString(ENTRY_SEPARATOR)
        if (candidate[PURGE_JOURNAL] != expectedJournal) return false
        val expectedDemands = before.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } + listOfNotNull(demandNode(input)?.toPayloadEntry())
        if (after.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } != expectedDemands) return false
        if (input.demand != null) {
            val request = after.locations(checkNotNull(input.demandId)).singleOrNull()?.second as? ControlEntryRead.Interpreted ?: return false
            if (!demandMatches(request.value, input)) return false
        }
        val applied = ControlAppliedEvidence.own(after, command) ?: return false
        if (ControlAppliedEvidence.node(applied) != ControlAppliedEvidence.node(evidence(command, input))) return false
        val oldMetadata = before.metadata as ControlMetadataRead.V2
        val newMetadata = after.metadata as ControlMetadataRead.V2
        if (newMetadata.evidence.entries.map { it.payload() } != oldMetadata.evidence.entries.map { it.payload() } + PayloadEntry.Obj(ControlAppliedEvidence.node(evidence(command, input)))) return false
        if (after.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id == seal.id }.map { it.payload() } !=
            before.arrays.getValue(ControlKind.SEAL).entries.filterNot { (it as? ControlEntryRead.Interpreted)?.value?.id == seal.id }.map { it.payload() }) return false
        val changed = setOf(ControlRecordKeys.payload(ControlKind.SEAL), ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE), PURGE_JOURNAL) +
            listOfNotNull(ControlRecordKeys.payload(ControlKind.DEMAND).takeIf { input.demand != null })
        if (candidate.toMutablePreferences().apply { changed.forEach { remove(it) } } !=
            before.original.toMutablePreferences().apply { changed.forEach { remove(it) } }) return false
        return true
    }

    private fun ControlEvidenceEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEvidenceEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEvidenceEntryRead.Uninterpretable -> original
    }

    internal fun witness(input: RetiredNamespaceSettlement): SettlementEvidenceV1 {
        val key = checkNotNull(target(input)).key
        return SettlementEvidenceV1(input.operationId, input.executor.originLifetimeId, StoreOp.JOURNAL_RETIRED,
            input.before, input.before, JournalTargetV1(key.ownerUid, key.axis, key.epoch))
    }
    private fun journal(input: RetiredNamespaceSettlement): PendingPurge {
        val key = checkNotNull(target(input)).key
        return PendingPurge(key.ownerUid, key.epoch.takeIf { key.axis == PurgeScope.USER },
            key.epoch.takeIf { key.axis == PurgeScope.CAPABILITY }, setOf(key.axis))
    }
    internal fun demandNode(input: RetiredNamespaceSettlement): ControlNode? {
        val d = input.demand ?: return null
        return (ControlObligations.build(ControlKind.DEMAND) {
            set("id", ControlScalar.Text(checkNotNull(input.demandId)))
            set("kind", ControlScalar.Text("REQUEST"))
            set("ownerUid", d.ownerUid?.let(ControlScalar::Text) ?: ControlScalar.Null)
            set("binding", ControlScalar.Integer(d.binding))
            set("originLifetimeId", ControlScalar.Text(d.raisedAt.origin.value))
            set("raisedAt", ControlScalar.Integer(d.raisedAt.value))
            set("intent", ControlScalar.Text(d.intent.name))
        } as? ControlWriteResult.Written)?.node
    }
    internal fun demandMatches(actual: ControlObligationV1, input: RetiredNamespaceSettlement): Boolean {
        val expected = input.demand ?: return false
        if (actual !is DemandV1) return false
        if (actual.id != input.demandId) return false
        if (actual.ownerUid != expected.ownerUid) return false
        if (actual.binding != expected.binding) return false
        if (actual.raisedAt.origin != expected.raisedAt.origin) return false
        if (actual.raisedAt.value != expected.raisedAt.value) return false
        if (actual.intent != expected.intent) return false
        return true
    }
    internal fun receipt(input: RetiredNamespaceSettlement, read: ControlRecordRead.Supported): HandoverSettlementReceipt {
        val seal = checkNotNull(target(input))
        val journal = shared.canonicalJournal(read.original)
        val observedJournal = when {
            journal == null -> JournalObservation.Uninterpretable
            journal(input) in journal -> JournalObservation.Present
            journal.any { seal.key.axis in it.scopes && (it.ownerUid == null || it.ownerUid == seal.key.ownerUid) &&
                (if (seal.key.axis == PurgeScope.USER) it.userAccessEpoch else it.krxCapabilityEpoch).let { e -> e == null || e == seal.key.epoch } } -> JournalObservation.Covered
            else -> JournalObservation.Absent
        }
        val demands = input.demandId?.let { read.locations(it) }.orEmpty()
        val demand = demands.singleOrNull()?.takeIf { it.first == ControlKind.DEMAND }?.second as? ControlEntryRead.Interpreted
        val observedDemand = when {
            input.demandId == null -> HandoverDemandObservation.NotRequired
            demands.isEmpty() -> HandoverDemandObservation.Absent
            demand == null -> HandoverDemandObservation.Uninterpretable
            demand.original.toPayloadEntry() == demandNode(input)?.toPayloadEntry() -> HandoverDemandObservation.Present
            else -> HandoverDemandObservation.Changed
        }
        return HandoverSettlementReceipt(HandoverSettlementTransition.RETIRED_NAMESPACE, input.operationId,
            input.executor.originLifetimeId, input.before, input.before, mapOf(seal.id to witness(input)),
            mapOf(seal.id to observedJournal), input.demandId, observedDemand,
            seals(read).filter { it.settlement == null }, read.hasUninterpretable, read.hasUninterpretableMetadata)
    }

    private fun evidence(command: CommandRef, input: RetiredNamespaceSettlement) = AppliedEvidence.Settlement(
        command.id, command.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE,
        listOf(checkNotNull(target(input)).id), input.demandId)
    private fun effectiveIds(input: RetiredNamespaceSettlement) = listOfNotNull(checkNotNull(target(input)).id, input.demandId)
    private fun seals(read: ControlRecordRead.Supported) = read.arrays.getValue(ControlKind.SEAL).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().map { it.value as SealV1 }
    private fun target(input: RetiredNamespaceSettlement) =
        (ControlObligations.read(ControlKind.SEAL, input.target) as? ControlEntryRead.Interpreted)?.value as? SealV1
    internal fun journalField(value: String?): Boolean = value == null ||
        (value.isNotEmpty() && '|' !in value && '\n' !in value && !value.hasUnpairedSurrogate())

    companion object {
        internal fun recordProblem(read: ControlRecordRead.Supported): RecoveryReason? = when {
            read.schemaVersion != 2 -> RecoveryReason.ControlSchemaMigrationRequired
            read.hasUninterpretable -> RecoveryReason.UninterpretableObligations
            read.hasUninterpretableMetadata -> RecoveryReason.UninterpretableMetadata
            else -> null
        }
    }
}
