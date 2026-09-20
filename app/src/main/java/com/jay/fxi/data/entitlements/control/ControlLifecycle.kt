package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import java.util.Collections
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome

// 5a shares the existing five current-executor facts; event-specific inputs arrive with the writers.
internal typealias LifecycleAttemptContext = AttemptContext

internal enum class LifecycleRole { REQUEST, GUARD, HOLD, RECOVERY_INTENT }

/** Fixed in memory, never serialized into Applied. No generic writer accepts this descriptor. */
internal data class LifecycleFixedTarget(
    val target: LifecycleTarget,
    val role: LifecycleRole,
    val before: ControlNode?,
    val after: ControlNode?
)

internal class LifecycleNamespacePostcondition(
    val before: FenceV1,
    val after: FenceV1,
    journal: List<PendingPurge>,
    val userMayContain: Boolean? = null,
    val krxMayContain: Boolean? = null
) {
    val journal: List<PendingPurge> = Collections.unmodifiableList(journal.map {
        it.copy(scopes = Collections.unmodifiableSet(it.scopes.toSet()))
    })
}

internal class ControlLifecycleDescriptor(
    val operationId: String,
    val transition: LifecycleTransition,
    targets: List<LifecycleFixedTarget>,
    val executor: SettlementExecutor? = null,
    val namespace: LifecycleNamespacePostcondition? = null,
    requiredUnchanged: List<LifecycleFixedTarget> = emptyList()
) {
    val targets: List<LifecycleFixedTarget> = Collections.unmodifiableList(targets.toList())
    // Required effects already satisfied before the command are checked without fake wire targets.
    val requiredUnchanged: List<LifecycleFixedTarget> = Collections.unmodifiableList(requiredUnchanged.toList())
}

internal enum class LifecycleTargetObservation { PresentExact, Changed, Absent, Uninterpretable }
internal data class LifecycleObservedTarget(val target: LifecycleTarget, val observation: LifecycleTargetObservation)

/** Current storage observations only, without a grant or a historical tombstone. */
internal class ControlLifecycleReceipt(
    val transition: LifecycleTransition,
    val commandId: String,
    targets: List<LifecycleObservedTarget>,
    val before: FenceV1?,
    val after: FenceV1?,
    journal: Map<PendingPurge, JournalObservation>,
    val hasUninterpretable: Boolean,
    val hasUninterpretableMetadata: Boolean,
    requiredUnchanged: List<LifecycleObservedTarget> = emptyList()
) : ControlSettlementReceipt {
    val targets: List<LifecycleObservedTarget> = Collections.unmodifiableList(targets.toList())
    val requiredUnchanged: List<LifecycleObservedTarget> = Collections.unmodifiableList(requiredUnchanged.toList())
    val removedIds: List<String> = Collections.unmodifiableList(targets.filter { it.target.effect == LifecycleEffect.REMOVE }.map { it.target.id })
    val createdIds: List<String> = Collections.unmodifiableList(targets.filter { it.target.effect == LifecycleEffect.CREATE }.map { it.target.id })
    val replacedIds: List<String> = Collections.unmodifiableList(targets.filter { it.target.effect == LifecycleEffect.REPLACE }.map { it.target.id })
    val journal: Map<PendingPurge, JournalObservation> = Collections.unmodifiableMap(journal.toMap())
}

/** Common boundaries. Each predicate is independently testable before a business writer exists. */
internal object ControlLifecycleBoundary {
    fun recordProblem(read: ControlRecordRead.Supported): RecoveryReason? {
        if (read.schemaVersion != 2) return RecoveryReason.ControlSchemaMigrationRequired
        if (read.hasUninterpretable) return RecoveryReason.UninterpretableObligations
        if (read.hasUninterpretableMetadata) return RecoveryReason.UninterpretableMetadata
        return null
    }

    fun preimage(read: ControlRecordRead.Supported, fixed: LifecycleFixedTarget): ConflictReason? {
        val locations = read.locations(fixed.target.id)
        if (!targetExists(locations)) return ConflictReason.TargetMissing
        if (locations.size != 1) return ConflictReason.IdCollision
        val (kind, entry) = locations.first()
        if (!targetKindMatches(kind, fixed.target.kind)) return ConflictReason.IdCollision
        if (entry !is ControlEntryRead.Interpreted) return ConflictReason.UninterpretableTarget
        if (!targetPreimageMatches(entry.original, fixed.before)) return ConflictReason.TargetChanged
        return null
    }

    internal fun targetExists(locations: List<Pair<ControlKind, ControlEntryRead>>): Boolean = locations.isNotEmpty()
    internal fun targetKindMatches(actual: ControlKind, expected: ControlKind): Boolean = actual == expected
    internal fun targetPreimageMatches(actual: ControlNode, expected: ControlNode?): Boolean =
        actual.toPayloadEntry() == expected?.toPayloadEntry()

    fun current(executor: SettlementExecutor, context: LifecycleAttemptContext, raw: Preferences): ConflictReason? {
        if (context.ownerUid != executor.ownerUid) return ConflictReason.TargetChanged
        if (context.binding != executor.binding) return ConflictReason.TargetChanged
        if (context.originLifetimeId != executor.originLifetimeId) return ConflictReason.TargetChanged
        if (context.signOutOpen) return ConflictReason.IdentityTransitionPending
        if (context.identityPersistencePending) return ConflictReason.IdentityTransitionPending
        if (raw[TEARDOWN_OWED_FOR] != null) return ConflictReason.IdentityTransitionPending
        return null
    }

    fun fence(raw: Preferences, fixed: FenceV1): Boolean {
        if (raw[OWNER_UID] != fixed.ownerUid) return false
        if (raw[USER_EPOCH] != fixed.userAccessEpoch) return false
        if (raw[KRX_EPOCH] != fixed.krxCapabilityEpoch) return false
        return true
    }

    fun rawProblem(raw: Preferences): RecoveryReason? {
        if (!raw.validType<String>(OWNER_UID) || !raw.validType<String>(USER_EPOCH) ||
            !raw.validType<String>(KRX_EPOCH) || !raw.validType<String>(TEARDOWN_OWED_FOR) ||
            !raw.validType<String>(PURGE_JOURNAL) || !raw.validType<Boolean>(MAY_CONTAIN_PREMIUM) ||
            !raw.validType<Boolean>(MAY_CONTAIN_KRX)) return RecoveryReason.UnreadableEpochState
        if (raw[USER_EPOCH] == "" || raw[KRX_EPOCH] == "") return RecoveryReason.UnreadableEpochState
        return null
    }

    fun commandIdAvailable(read: ControlRecordRead.Supported, id: String): Boolean {
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
        if (evidence.filterIsInstance<ControlEvidenceEntryRead.Interpreted>().any { it.value.commandId == id }) return false
        val seals = read.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
        if (seals.any { (it.value as SealV1).settlement?.operationId == id }) return false
        return true
    }

    fun createIdAvailable(read: ControlRecordRead.Supported, id: String): Boolean {
        if (read.locations(id).isNotEmpty()) return false
        return true
    }

    fun observe(read: ControlRecordRead.Supported, fixed: LifecycleFixedTarget): LifecycleObservedTarget {
        val locations = read.locations(fixed.target.id)
        val state = when {
            locations.isEmpty() -> LifecycleTargetObservation.Absent
            locations.size != 1 -> LifecycleTargetObservation.Uninterpretable
            locations.single().second !is ControlEntryRead.Interpreted -> LifecycleTargetObservation.Uninterpretable
            locations.single().first != fixed.target.kind -> LifecycleTargetObservation.Changed
            (locations.single().second as ControlEntryRead.Interpreted).original.toPayloadEntry() == fixed.after?.toPayloadEntry() ->
                LifecycleTargetObservation.PresentExact
            else -> LifecycleTargetObservation.Changed
        }
        return LifecycleObservedTarget(fixed.target, state)
    }

    fun postcondition(read: ControlRecordRead.Supported, fixed: LifecycleFixedTarget): ConflictReason? {
        val observed = observe(read, fixed).observation
        if (fixed.target.effect == LifecycleEffect.REMOVE) {
            if (observed != LifecycleTargetObservation.Absent) return ConflictReason.TargetChanged
        } else {
            if (observed == LifecycleTargetObservation.Absent) return ConflictReason.TargetMissing
            if (observed != LifecycleTargetObservation.PresentExact) return ConflictReason.TargetChanged
        }
        return null
    }
}

/** 5a provides confirmation/dispatch only. New effects require the named 5b–5e writers. */
internal class ControlLifecycleConfirmation(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: ControlLifecycleDescriptor, read: ControlRecordRead.Supported,
        context: LifecycleAttemptContext?, confirmOnly: Boolean, previouslyConfirmed: Boolean): RecordTransactionDecision<Outcome> {
        fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, input.targets.map { it.target.id }), read))
        fun recovery(reason: RecoveryReason) = negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), reason, read))
        ControlLifecycleBoundary.recordProblem(read)?.let { return recovery(it) }
        if (!validDescriptor(input)) return reject("InvalidLifecycleDescriptor")
        ControlLifecycleBoundary.rawProblem(read.original)?.let { return recovery(it) }
        val own = ControlAppliedEvidence.own(read, command)
        if (confirmOnly) {
            if (own == null && !previouslyConfirmed) return negative(ControlStoreResult.Unconfirmed(command, emptySet(), emptySet(),
                UnconfirmedReason.HistoryUnavailable, ControlAttemptPhase.PreparingCandidate, read))
            for (target in input.targets + input.requiredUnchanged) {
                ControlLifecycleBoundary.postcondition(read, target)?.let { return conflict(it) }
            }
            val namespace = input.namespace
            if (namespace != null) {
                if (!ControlLifecycleBoundary.fence(read.original, namespace.after)) return conflict(ConflictReason.TargetChanged)
                if (namespace.userMayContain != null && read.original[MAY_CONTAIN_PREMIUM] != namespace.userMayContain) return conflict(ConflictReason.TargetChanged)
                if (namespace.krxMayContain != null && read.original[MAY_CONTAIN_KRX] != namespace.krxMayContain) return conflict(ConflictReason.TargetChanged)
                val journal = shared.canonicalJournal(read.original) ?: return recovery(RecoveryReason.JournalMigrationRequired)
                if (!journal.containsAll(namespace.journal)) return conflict(ConflictReason.TargetMissing)
            }
            return RecordTransactionDecision.Confirm(read.original, Outcome.Positive(
                ConfirmedEffect.PostconditionConfirmed, input.targets.map { it.target.id }, receipt(input, read)))
        }
        // These pure common gates will precede event-specific admission in each named writer.
        if (!ControlLifecycleBoundary.commandIdAvailable(read, command.id)) return conflict(ConflictReason.OperationIdCollision)
        for (target in input.targets) {
            if (target.target.effect == LifecycleEffect.CREATE) {
                if (!ControlLifecycleBoundary.createIdAvailable(read, target.target.id)) return conflict(ConflictReason.IdCollision)
            } else ControlLifecycleBoundary.preimage(read, target)?.let { return conflict(it) }
        }
        input.namespace?.let {
            if (!ControlLifecycleBoundary.fence(read.original, it.before)) return conflict(ConflictReason.TargetChanged)
        }
        input.executor?.let {
            if (context == null) return reject("AttemptContextRequired")
            ControlLifecycleBoundary.current(it, context, read.original)?.let { reason -> return conflict(reason) }
        }
        return reject("LifecycleWriterUnavailable")
    }

    internal fun validDescriptor(input: ControlLifecycleDescriptor): Boolean {
        if (input.operationId.isEmpty()) return false
        if (!ControlLifecycleEvidence.validShape(input.transition, input.targets.map { it.target })) return false
        val all = input.targets + input.requiredUnchanged
        if (all.map { it.target.id }.toSet().size != all.size) return false
        fun facts(node: ControlNode?, target: LifecycleFixedTarget): Boolean {
            val value = node?.let { (ControlObligations.read(target.target.kind, it) as? ControlEntryRead.Interpreted)?.value } ?: return false
            if (value.id != target.target.id) return false
            return when (target.role) {
                LifecycleRole.REQUEST -> value is DemandV1
                LifecycleRole.GUARD -> value is ScheduleGuardV1
                LifecycleRole.HOLD -> value is RestoredHold
                LifecycleRole.RECOVERY_INTENT -> value is RecoveryIntentV1
            }
        }
        for (fixed in input.targets) {
            when (fixed.target.effect) {
                LifecycleEffect.REMOVE -> if (!facts(fixed.before, fixed) || fixed.after != null) return false
                LifecycleEffect.CREATE -> if (fixed.before != null || !facts(fixed.after, fixed)) return false
                LifecycleEffect.REPLACE -> if (!facts(fixed.before, fixed) || !facts(fixed.after, fixed) ||
                    fixed.before?.toPayloadEntry() == fixed.after?.toPayloadEntry()) return false
            }
        }
        if (input.requiredUnchanged.any { it.target.effect != LifecycleEffect.REPLACE || !facts(it.after, it) ||
                it.before?.toPayloadEntry() != it.after?.toPayloadEntry() }) return false
        val roles = input.targets.map { it.role }
        return when (input.transition) {
            LifecycleTransition.REBIND_REQUESTS -> roles.all { it == LifecycleRole.REQUEST }
            LifecycleTransition.SETTLE_QUERY -> {
                val removed = input.targets.takeWhile { it.target.effect == LifecycleEffect.REMOVE }
                val changed = input.targets.drop(removed.size)
                removed.all { it.role == LifecycleRole.REQUEST } &&
                    changed.map { it.role } in listOf(emptyList(), listOf(LifecycleRole.REQUEST), listOf(LifecycleRole.GUARD),
                        listOf(LifecycleRole.REQUEST, LifecycleRole.GUARD)) &&
                    (removed.isEmpty() || changed.none { it.role == LifecycleRole.REQUEST && it.target.effect != LifecycleEffect.CREATE })
            }
            LifecycleTransition.UPDATE_AUTH -> roles == listOf(LifecycleRole.GUARD) || roles == listOf(LifecycleRole.GUARD, LifecycleRole.REQUEST)
            LifecycleTransition.END_AUTH_BINDING -> roles.first() == LifecycleRole.GUARD && roles.drop(1).all { it == LifecycleRole.REQUEST }
            LifecycleTransition.REMOVE_EMPTY_GUARD -> roles == listOf(LifecycleRole.GUARD)
            LifecycleTransition.RECOVER_HOLD -> roles.first() == LifecycleRole.HOLD && roles.drop(1) in listOf(
                emptyList(), listOf(LifecycleRole.REQUEST), listOf(LifecycleRole.GUARD), listOf(LifecycleRole.REQUEST, LifecycleRole.GUARD)) &&
                input.targets.drop(1).none { it.role == LifecycleRole.REQUEST && it.target.effect != LifecycleEffect.CREATE }
            LifecycleTransition.RECOVER_INTENT -> roles == listOf(LifecycleRole.RECOVERY_INTENT) ||
                roles == listOf(LifecycleRole.RECOVERY_INTENT, LifecycleRole.REQUEST)
        }
    }

    private fun receipt(input: ControlLifecycleDescriptor, read: ControlRecordRead.Supported): ControlLifecycleReceipt {
        val journal = shared.canonicalJournal(read.original)
        return ControlLifecycleReceipt(input.transition, input.operationId,
            input.targets.map { ControlLifecycleBoundary.observe(read, it) }, input.namespace?.before, input.namespace?.after,
            input.namespace?.journal.orEmpty().associateWith {
                if (journal == null) JournalObservation.Uninterpretable else if (it in journal) JournalObservation.Present else JournalObservation.Absent
            }, read.hasUninterpretable, read.hasUninterpretableMetadata,
            input.requiredUnchanged.map { ControlLifecycleBoundary.observe(read, it) })
    }
}
