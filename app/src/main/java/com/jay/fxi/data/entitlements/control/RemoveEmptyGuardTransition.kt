package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome

/** Only named preparation opts into application; an evidence-only descriptor still cannot write. */
internal class RemoveEmptyGuardPlan private constructor(private val expected: ControlNode) {
    fun descriptor(id: String) = ControlLifecycleDescriptor(id, LifecycleTransition.REMOVE_EMPTY_GUARD,
        listOf(LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, guard(expected)?.id.orEmpty(),
            LifecycleEffect.REMOVE), LifecycleRole.GUARD, expected, null)), removeEmptyGuard = this)
    companion object {
        fun prepare(expected: ControlNode) = RemoveEmptyGuardPlan(expected)
    }
}

/** Runs after common exact-preimage admission. No expiry, AUTH clearing or runtime publication. */
internal class RemoveEmptyGuardTransition(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    internal fun empty(node: ControlNode): Boolean {
        val fields = node.toPayloadEntry().fields
        if (fields.containsKey("floor")) return false // F06a
        if (fields.containsKey("auth")) return false // F06b
        return true
    }

    fun decide(command: CommandRef, input: ControlLifecycleDescriptor, read: ControlRecordRead.Supported): RecordTransactionDecision<Outcome> {
        fun reject(reason: RejectionReason) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(
            ControlStoreResult.Rejected(command, emptySet(), emptySet(), reason, read)))
        val target = input.targets.single()
        if (!empty(checkNotNull(target.before))) return reject(RejectionReason.InvalidRequest("GuardNotEmpty"))
        val candidate = read.original.toMutablePreferences()
        val rows = read.arrays.getValue(ControlKind.DEMAND).entries.filterNot {
            (it as? ControlEntryRead.Interpreted)?.value?.id == target.target.id
        }.map { it.payload() }
        when (val encoded = shared.encodeChanged(ControlKind.DEMAND, rows)) {
            is NamespaceSettlementTransition.ChangedPayload.Rejected -> return reject(encoded.reason)
            is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.DEMAND)] = encoded.text
        }
        ControlAppliedEvidence.append(candidate, read, evidence(command, target.target.id), codec)?.let { return reject(it) }
        if (!validCandidate(command, target.target.id, read, candidate)) return reject(RejectionReason.InvalidRequest("RequiredDecisionEffectMissing")) // EG.candidate
        val after = ControlRecordReader(codec).read(candidate) as ControlRecordRead.Supported
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            listOf(target.target.id), ControlLifecycleConfirmation(codec).receipt(input, after)))
    }

    /** Derive removal and evidence from the command identity and before record, never proposed rows. */
    internal fun validCandidate(command: CommandRef, removedId: String, before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = ControlRecordReader(codec).read(candidate) as? ControlRecordRead.Supported ?: return false
        if (ControlLifecycleBoundary.recordProblem(after) != null) return false // EG.C12schema
        if (after.locations(removedId).isNotEmpty()) return false // EG.C01
        val survivors = before.arrays.getValue(ControlKind.DEMAND).entries.filterNot {
            (it as? ControlEntryRead.Interpreted)?.value?.id == removedId
        }.map { it.payload().toString() }
        if (after.arrays.getValue(ControlKind.DEMAND).entries.filterNot {
            (it as? ControlEntryRead.Interpreted)?.value?.id == removedId
        }.map { it.payload().toString() } != survivors) return false // EG.C02
        val allowed = before.original.toMutablePreferences()
        allowed[ControlRecordKeys.payload(ControlKind.DEMAND)] = candidate[ControlRecordKeys.payload(ControlKind.DEMAND)]!!
        val key = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        allowed[key] = candidate[key]!!
        if (allowed != candidate) return false // EG.C11
        val previous = (before.metadata as ControlMetadataRead.V2).evidence.entries.map { it.payload().toString() }
        val actual = (after.metadata as ControlMetadataRead.V2).evidence.entries.map { it.payload().toString() }
        if (!validEvidence(command, removedId, previous, actual)) return false // EG.C10
        return true
    }

    internal fun validEvidence(command: CommandRef, removedId: String, previous: List<String>, actual: List<String>): Boolean =
        actual == previous + PayloadEntry.Obj(ControlAppliedEvidence.node(evidence(command, removedId))).toString()

    private fun evidence(command: CommandRef, id: String) = AppliedEvidence.Lifecycle(command.id,
        command.ownerTrackingLifetimeId.value, LifecycleTransition.REMOVE_EMPTY_GUARD,
        listOf(LifecycleTarget(ControlKind.DEMAND, id, LifecycleEffect.REMOVE)))
    private fun ControlEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEntryRead.Uninterpretable -> original
    }
    private fun ControlEvidenceEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEvidenceEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEvidenceEntryRead.Uninterpretable -> original
    }
}
