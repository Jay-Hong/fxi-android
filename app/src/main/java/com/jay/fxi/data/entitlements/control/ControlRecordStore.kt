package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Unwired D2a/D2b facade. Uses only the owner's record transaction API; never owns a DataStore, lock,
 * read-back flag or barrier. Inputs are prepared once, then all targets are resolved by id against
 * the latest D1 classification in the atomic update. Unchanged payload strings remain verbatim.
 *
 * The scope is additions, seal joins, constrained non-settlement edits, explicit guard floor
 * recapture, and the named namespace settlement transition.
 * Confirmation proves storage postconditions, never admission or fresh server approval.
 * There is deliberately no coordinator wiring or retry scheduler here.
 */
internal class ControlRecordStore(
    private val owner: DataStoreAccessEpochStore,
    private val ids: ControlIdGenerator = ControlIdGenerator(UUID::randomUUID),
    private val codec: ControlPayloadCodec = ControlPayloadCodec()
) {
    private val reader = ControlRecordReader(codec)
    private val tracking = ControlCommandTracking.forOwner(owner)

    fun newLifetimeId(): LifetimeId = LifetimeId(ids.next().toString())
    fun newSessionId(): String = ids.next().toString()

    /** The callback runs once, during preparation. Its id must remain the newly issued UUID. */
    fun addition(kind: ControlKind, build: ControlBuilder.(String) -> Unit): ControlMutation =
        ControlMutation.Add.prepare(kind, ids.next(), build)

    /** Exact preimage is fixed now; a stale preimage is a conflict, never an index-based overwrite. */
    fun edit(kind: ControlKind, expected: ControlNode, change: ControlEditor.() -> Unit): ControlMutation =
        ControlMutation.Edit.prepare(kind, expected, change)

    /** Fixed boot reading, wait and origin; retries never re-anchor against a newer clock. */
    fun recordFloor(expected: ControlNode, now: BootReading, waitMillis: Long, origin: LifetimeId): ControlMutation =
        ControlMutation.Edit.floor(expected, now, waitMillis, origin)

    fun prepare(vararg actions: ControlMutation): CommandRef {
        val command = CommandRef(ids.next().toString(), actions.toList(), tracking.lifetimeId)
        return tracking.registerPrepared(command)
    }

    /** Issue operation, demand and axis UUIDs independently, once. Context is supplied at execution. */
    fun prepareRotation(
        targets: List<ControlNode>, before: FenceV1, origin: LifetimeId, demand: SettlementDemand
    ): CommandRef {
        val operationId = ids.next().toString()
        val demandId = ids.next().toString()
        val axes = targets.mapNotNull {
            ((ControlObligations.read(ControlKind.SEAL, it) as? ControlEntryRead.Interpreted)?.value as? SealV1)?.key?.axis
        }.toSet()
        val input = RotateAndSettleNamespaces(targets, before, origin, demand, operationId, demandId,
            if (PurgeScope.USER in axes) ids.next().toString() else null,
            if (PurgeScope.CAPABILITY in axes) ids.next().toString() else null)
        val command = CommandRef(operationId, ControlCommandBody.RotateAndSettle(input), tracking.lifetimeId)
        return tracking.registerPrepared(command)
    }

    fun checkpoint(command: CommandRef): ControlCommandCheckpoint? = tracking.findPrepared(command)
        ?.takeIf { command.body is ControlCommandBody.Mutations }
        ?.let {
            // Targets only advance from null to fixed values, before the flag becomes true.
            // Read the flag first so a requested checkpoint cannot contain an older partial list.
            val requested = it.confirmationRequested.get()
            ControlCommandCheckpoint(command, it.targets.get(), requested)
        }

    /**
     * Missing own seals can be retried. A replacement same-key seal or a new NAMESPACE append whose
     * owner/axis epoch is no longer current yields TargetChanged; unreadable required epoch keys
     * yield UnreadableEpochState. Existing confirmations/joins do not recheck append currentness.
     * A rotation can reconfirm its witness here, but a new application is rejected with the stable
     * InvalidRequest detail "AttemptContextRequired". Supply fresh context for each new attempt.
     */
    suspend fun execute(command: CommandRef): ControlStoreResult = run(command, checkpoint = null, confirmOnly = false, context = null)

    /** Context admits a new rotation; generic mutations retain their existing context-free contract. */
    suspend fun execute(command: CommandRef, context: AttemptContext): ControlStoreResult =
        run(command, checkpoint = null, confirmOnly = false, context = context)

    /**
     * Previous-lifetime confirmation never applies a missing effect or adopts a current same-key seal.
     * A generic mutation reference without a complete checkpoint is HistoryUnavailable, unless D1 needs recovery.
     * Named rotation references instead confirm their durable witnesses without a checkpoint or AttemptContext.
     * A live command's local history is authoritative: a supplied checkpoint must agree with it.
     * Imported targets must also match the prepared operation; they never replace live tracking.
     * HistoryUnavailable marks even a confirmed live command unresolved; a later Conflict preserves that membership.
     * A missing fixed target is TargetMissing here: this path checks its presence, without attempting reapplication.
     * Use execute for a live command, and keep using confirmPrevious for a previous-lifetime reference.
     */
    suspend fun confirmPrevious(command: CommandRef, checkpoint: ControlCommandCheckpoint? = null): ControlStoreResult =
        run(command, checkpoint, confirmOnly = true, context = null)

    suspend fun upgradeControlSchemaV1ToV2(): ControlSchemaUpgradeResult {
        var observation: ControlRecordRead? = null
        return try {
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation = read
                UpgradeControlSchemaV1ToV2.decide(read)
            }
            val reason = transaction.value
            if (reason != null) ControlSchemaUpgradeResult.RecoveryRequired(reason, checkNotNull(observation))
            else ControlSchemaUpgradeResult.Confirmed(
                ConfirmedControlSnapshot(reader.read(transaction.snapshot) as ControlRecordRead.Supported),
                ConfirmationProof(transaction.evidence))
        } catch (failure: IOException) {
            ControlSchemaUpgradeResult.Unconfirmed(observation, failure)
        }
    }

    private suspend fun run(
        command: CommandRef,
        checkpoint: ControlCommandCheckpoint?,
        confirmOnly: Boolean,
        context: AttemptContext?
    ): ControlStoreResult {
        val known = tracking.findPrepared(command)
        val tracked = known ?: TrackedControlCommand(command)
        check(tracking.executing.add(command)) { "the same command is already executing" }
        val wasUnresolved = tracking.isUnresolved(command)
        tracking.markUnresolved(command)
        val observation = AtomicReference<ControlRecordRead?>(null)
        val phase = AtomicReference(ControlAttemptPhase.ReadingSnapshot)
        try {
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation.set(read)
                if (read !is ControlRecordRead.Supported) {
                    negative(ControlStoreResult.RecoveryRequired(command, emptySet(), when (read) {
                        is ControlRecordRead.MigrationOrRecoveryRequired -> RecoveryReason.MigrationOrRecovery
                        else -> RecoveryReason.UnreadableRecord
                    }, read))
                } else if (known == null && !confirmOnly) {
                    historyUnavailable(command, read)
                } else {
                    phase.set(ControlAttemptPhase.PreparingCandidate)
                    val rotation = command.body as? ControlCommandBody.RotateAndSettle
                    val own = ControlAppliedEvidence.own(read, command)
                    // Preserve actual observation even when checkpoint admission later refuses confirmation.
                    if (own != null) tracked.observedApplied.set(true)
                    if (confirmOnly && rotation == null) {
                        if (checkpoint?.command !== command || !checkpoint.confirmationRequested ||
                            checkpoint.targets.size != command.actions.size || checkpoint.targets.any { it == null } ||
                            (known != null && !matchesLocalHistory(tracked, checkpoint)) ||
                            !matchesPreparedActions(command, checkpoint)
                        ) return@transactRecord historyUnavailable(command, read)
                        if (known == null) {
                            tracked.targets.set(checkpoint.targets)
                            tracked.confirmationRequested.set(true)
                        }
                    }
                    if (ControlAppliedEvidence.hasOpaqueOwn(read, command)) return@transactRecord negative(
                        ControlStoreResult.RecoveryRequired(command, emptySet(), RecoveryReason.UninterpretableMetadata, read))
                    var onlyConfirm = confirmOnly
                    if (own != null) {
                        if (!ControlAppliedEvidence.matches(command, tracked, own)) return@transactRecord negative(
                            ControlStoreResult.Conflict(command, emptySet(), ConflictReason.CommandEvidenceMismatch,
                                TargetExpectation(command, tracked.targets.get().map { it?.id }), read))
                        onlyConfirm = true
                    } else if (tracked.observedApplied.get()) {
                        return@transactRecord negative(ControlStoreResult.RecoveryRequired(command, emptySet(),
                            RecoveryReason.CommandEvidenceLost, read))
                    } else if (tracked.confirmed.get()) {
                        onlyConfirm = true
                    } else if (!confirmOnly && tracked.firstConfirmDiscontinuityCount?.let {
                            it != tracking.evidenceDiscontinuityCount
                        } == true) {
                        return@transactRecord negative(ControlStoreResult.RecoveryRequired(command, emptySet(),
                            RecoveryReason.CommandEvidenceContinuityLost, read))
                    }
                    val decision = if (rotation != null) {
                        NamespaceSettlementTransition(codec).decide(command, rotation.input, read, context,
                            onlyConfirm, tracked.confirmed.get())
                    } else decide(command, tracked, read, onlyConfirm)
                    if (decision is RecordTransactionDecision.Confirm) {
                        // All candidate checks are complete. This precedes write-scope entry and cancellation.
                        if (known != null) tracked.bindFirstConfirm(tracking.evidenceDiscontinuityCount)
                        tracked.expectedApplied = ControlAppliedEvidence.own(
                            reader.read(decision.candidate) as ControlRecordRead.Supported, command)
                        tracked.confirmationRequested.set(true)
                        phase.set(ControlAttemptPhase.ConfirmingStorage)
                    }
                    decision
                }
            }
            return when (val outcome = transaction.value) {
                is Outcome.Positive -> {
                    val confirmedSnapshot = ConfirmedControlSnapshot(reader.read(transaction.snapshot) as ControlRecordRead.Supported)
                    if (ControlAppliedEvidence.own(confirmedSnapshot.record, command) != null) tracked.observedApplied.set(true)
                    tracked.confirmed.set(true)
                    tracking.resolve(command)
                    ControlStoreResult.Confirmed(
                        command, tracking.snapshot(), outcome.effect, outcome.ids,
                        confirmedSnapshot,
                        ConfirmationProof(transaction.evidence), outcome.settlement
                    )
                }
                is Outcome.Negative -> {
                    if (!wasUnresolved && outcome.result !is ControlStoreResult.Unconfirmed) tracking.resolve(command)
                    outcome.result.withUnresolved(tracking.snapshot())
                }
            }
        } catch (cancelled: CancellationException) {
            // Targets are published before requesting persistence, even if this caller never returns.
            throw cancelled
        } catch (failure: IOException) {
            return ControlStoreResult.Unconfirmed(command, tracking.snapshot(), UnconfirmedReason.StorageFailure,
                phase.get(), observation.get(), failure)
        } finally {
            // Unexpected programming exceptions propagate; their attempt remains conservatively unresolved.
            tracking.executing.remove(command)
        }
    }

    private fun decide(
        command: CommandRef,
        tracked: TrackedControlCommand,
        read: ControlRecordRead.Supported,
        confirmOnly: Boolean
    ): RecordTransactionDecision<Outcome> {
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(),
            RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), reason,
            TargetExpectation(command, Collections.unmodifiableList(tracked.targets.get().map { it?.id })), read))

        if (command.actions.isEmpty()) return reject("at least one operation is required")
        val arrays = read.arrays.mapValues { (_, array) -> array.entries.map { it.originalEntry() }.toMutableList() }
        val changedKinds = mutableSetOf<ControlKind>()
        val effectiveIds = mutableListOf<String>()
        val requestedSealKeys = mutableSetOf<SealKey>()
        val targets = tracked.targets.get().toMutableList()
        val writtenIndices = mutableSetOf<Int>()
        var joined = false
        val mayConfirmPostcondition = tracked.confirmationRequested.get()

        for ((actionIndex, action) in command.actions.withIndex()) {
            val array = arrays.getValue(action.kind)
            val previous = targets[actionIndex]
            val desired = when (action) {
                is ControlMutation.Add -> action.built
                is ControlMutation.Edit -> action.changed
            }
            if (desired !is ControlWriteResult.Written) return reject("invalid or forbidden obligation change")
            val desiredFacts = (ControlObligations.read(action.kind, desired.node) as? ControlEntryRead.Interpreted)?.value
                ?: return reject("uninterpretable candidate")
            if (action is ControlMutation.Add && desiredFacts is SealV1 && !requestedSealKeys.add(desiredFacts.key)) {
                return reject("at most one addition per SealKey is allowed in a command")
            }

            if (action is ControlMutation.Add && previous == null) {
                // Every readable top-level id participates, even on a damaged/future sibling.
                if (read.findId(action.proposedId).isNotEmpty()) return conflict(ConflictReason.IdCollision)
                if (desiredFacts is SealV1) {
                    val seals = read.arrays.getValue(ControlKind.SEAL).entries
                    // An opaque seal cannot be scoped by its partly readable owner/axis/key.
                    if (seals.any { it is ControlEntryRead.Uninterpretable }) return conflict(ConflictReason.UninterpretableTarget)
                    val matches = seals.filterIsInstance<ControlEntryRead.Interpreted>().filter {
                        val seal = it.value as SealV1
                        seal.settlement == null && seal.key == desiredFacts.key
                    }
                    if (matches.size > 1) return conflict(ConflictReason.AmbiguousSealKey)
                    if (matches.size == 1) {
                        val match = matches.single()
                        targets[actionIndex] = ControlCommandTarget(match.value.id, match.original, joined = true)
                    }
                }
            }
            val target = targets[actionIndex] ?: ControlCommandTarget(desiredFacts.id, desired.node, joined = false)
            targets[actionIndex] = target
            // Publish adoption before any rejection, encoding failure or storage cancellation below.
            tracked.targets.set(targets.toList())
            if (target.id in effectiveIds) return reject("operations must have distinct effective targets")
            effectiveIds += target.id

            val located = read.findId(target.id)
            if (located.size > 1) return conflict(ConflictReason.IdCollision)
            val location = located.singleOrNull()
            if (location != null && location.first != action.kind) return conflict(ConflictReason.IdCollision)
            val current = location?.second as? ControlEntryRead.Interpreted
            if (location != null && current == null) return conflict(ConflictReason.UninterpretableTarget)

            if (confirmOnly) {
                if (current == null) return conflict(ConflictReason.TargetMissing)
                if (!same(current.original, target.postcondition)) return conflict(ConflictReason.TargetChanged)
                continue
            }
            when (action) {
                is ControlMutation.Add -> {
                    if (target.joined) {
                        if (current == null) return conflict(ConflictReason.TargetMissing)
                        if (!same(current.original, target.postcondition)) return conflict(ConflictReason.TargetChanged)
                        joined = true
                    } else if (current == null) {
                        if (tracked.confirmed.get()) return conflict(ConflictReason.TargetMissing)
                        if (desiredFacts is SealV1) {
                            val seals = read.arrays.getValue(ControlKind.SEAL).entries
                            if (seals.any { it is ControlEntryRead.Uninterpretable }) return conflict(ConflictReason.UninterpretableTarget)
                            // A failed attempt already fixed its own target. Another command's later
                            // same-key seal must neither be duplicated nor silently adopted on retry.
                            if (seals.filterIsInstance<ControlEntryRead.Interpreted>().any {
                                val seal = it.value as SealV1
                                seal.settlement == null && seal.key == desiredFacts.key
                            }) return conflict(ConflictReason.TargetChanged)
                        }
                        if (desiredFacts is SealV1 && desiredFacts.kind == SealTargetKind.NAMESPACE) {
                            val epochKey = if (desiredFacts.key.axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH
                            if (!read.original.validType<String>(OWNER_UID)) return negative(ControlStoreResult.RecoveryRequired(
                                command, emptySet(), RecoveryReason.UnreadableEpochState, read))
                            if (!read.original.validType<String>(epochKey)) return negative(ControlStoreResult.RecoveryRequired(
                                command, emptySet(), RecoveryReason.UnreadableEpochState, read))
                            if (read.original[OWNER_UID] != desiredFacts.key.ownerUid) return conflict(ConflictReason.TargetChanged)
                            if (read.original[epochKey] != desiredFacts.key.epoch) return conflict(ConflictReason.TargetChanged)
                        }
                        array += desired.node.toPayloadEntry()
                        writtenIndices += actionIndex
                        changedKinds += action.kind
                    } else {
                        if (!mayConfirmPostcondition) return conflict(ConflictReason.IdCollision)
                        if (!same(current.original, target.postcondition)) return conflict(ConflictReason.TargetChanged)
                    }
                }
                is ControlMutation.Edit -> {
                    if (current == null) return conflict(ConflictReason.TargetMissing)
                    if (same(current.original, action.before)) {
                        if (!same(current.original, desired.node)) {
                            // A completed edit may confirm its postcondition, never replay after ABA.
                            if (tracked.confirmed.get()) return conflict(ConflictReason.TargetChanged)
                            val index = read.arrays.getValue(action.kind).entries.indexOf(current)
                            array[index] = desired.node.toPayloadEntry()
                            writtenIndices += actionIndex
                            changedKinds += action.kind
                        }
                    } else if (!mayConfirmPostcondition || !same(current.original, target.postcondition)) {
                        return conflict(ConflictReason.TargetChanged)
                    }
                }
            }
        }

        if (changedKinds.isNotEmpty() && read.schemaVersion != 2) return negative(
            ControlStoreResult.RecoveryRequired(command, emptySet(), RecoveryReason.ControlSchemaMigrationRequired, read))
        if (changedKinds.isNotEmpty() && read.hasUninterpretableMetadata) return negative(
            ControlStoreResult.RecoveryRequired(command, emptySet(), RecoveryReason.UninterpretableMetadata, read))
        val candidate = read.original.toMutablePreferences()
        for (kind in changedKinds) {
            // The codec's documented envelope precondition is an invalid candidate, not an I/O attempt.
            val encoded = try {
                codec.encode(arrays.getValue(kind))
            } catch (_: IllegalArgumentException) {
                return reject("candidate violates codec envelope constraints")
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return negative(ControlStoreResult.Rejected(command, emptySet(),
                    RejectionReason.TooLarge(ControlPayloadKey.forKind(kind), encoded.bytes, encoded.limit), read))
                is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(kind)] = encoded.text
            }
        }
        if (changedKinds.isNotEmpty()) {
            val evidence = AppliedEvidence.Mutations(command.id, command.ownerTrackingLifetimeId.value,
                targets.mapIndexed { index, target ->
                    AppliedTarget(index, command.actions[index].kind, checkNotNull(target).id,
                        target.joined, index in writtenIndices)
                })
            ControlAppliedEvidence.append(candidate, read, evidence, codec)?.let {
                return negative(ControlStoreResult.Rejected(command, emptySet(), it, read))
            }
        }
        val complete = reader.read(candidate) as? ControlRecordRead.Supported
            ?: return reject("the complete candidate must remain a supported record")
        candidateRejection(command, complete, effectiveIds, targets)?.let { return reject(it.detail) }
        if (changedKinds.isNotEmpty() && complete.hasUninterpretableMetadata) return reject("invalid candidate evidence")
        val effect = when {
            changedKinds.isNotEmpty() -> ConfirmedEffect.AppliedThisAttempt
            joined && !mayConfirmPostcondition -> ConfirmedEffect.JoinedExisting
            else -> ConfirmedEffect.PostconditionConfirmed
        }
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(effect,
            Collections.unmodifiableList(effectiveIds.toList())))
    }

    /** Pure complete-record validation boundary, after construction and before any storage request. */
    internal fun candidateRejection(
        command: CommandRef,
        complete: ControlRecordRead.Supported,
        effectiveIds: List<String>,
        targets: List<ControlCommandTarget?>
    ): RejectionReason.InvalidRequest? {
        // Validate each affected target against whole-record identity/cardinality, not just its node.
        for ((index, id) in effectiveIds.withIndex()) {
            val matches = complete.findId(id)
            val entry = matches.singleOrNull()?.second as? ControlEntryRead.Interpreted
                ?: return RejectionReason.InvalidRequest("candidate introduces an id or guard-cardinality collision")
            // Validate the completed arrays independently of input/checkpoint admission checks.
            if (matches.single().first != command.actions[index].kind ||
                !same(entry.original, checkNotNull(targets[index]).postcondition)
            ) return RejectionReason.InvalidRequest("candidate does not preserve the required postcondition")
        }
        return null
    }

    private fun matchesLocalHistory(tracked: TrackedControlCommand, checkpoint: ControlCommandCheckpoint): Boolean {
        if (tracked.confirmationRequested.get() != checkpoint.confirmationRequested) return false
        return tracked.targets.get().zip(checkpoint.targets).all { (local, supplied) ->
            local != null && supplied != null && local.id == supplied.id && local.joined == supplied.joined &&
                same(local.postcondition, supplied.postcondition)
        }
    }

    private fun matchesPreparedActions(command: CommandRef, checkpoint: ControlCommandCheckpoint): Boolean =
        command.actions.zip(checkpoint.targets).all { (action, supplied) ->
            val target = checkNotNull(supplied) // The checkpoint shape gate has already checked this.
            val desired = when (action) {
                is ControlMutation.Add -> action.built
                is ControlMutation.Edit -> action.changed
            } as? ControlWriteResult.Written ?: return@all false
            val facts = (ControlObligations.read(action.kind, desired.node) as? ControlEntryRead.Interpreted)?.value
                ?: return@all false
            if (target.joined) {
                val adopted = (ControlObligations.read(action.kind, target.postcondition) as? ControlEntryRead.Interpreted)?.value
                action is ControlMutation.Add && facts is SealV1 && adopted is SealV1 &&
                    target.id == adopted.id && adopted.key == facts.key && adopted.settlement == null
            } else target.id == facts.id && same(target.postcondition, desired.node)
        }

    private fun ControlRecordRead.Supported.findId(id: String): List<Pair<ControlKind, ControlEntryRead>> =
        arrays.flatMap { (kind, array) -> array.entries.mapNotNull { entry ->
            val obj = entry.originalEntry() as? PayloadEntry.Obj
            val found = obj?.let { ControlNode.of(it.fields).text("id") } as? FieldRead.Present
            if (found?.value == id) kind to entry else null
        } }

    internal sealed interface Outcome {
        data class Positive(val effect: ConfirmedEffect, val ids: List<String>, val settlement: SettlementReceipt? = null) : Outcome
        data class Negative(val result: ControlStoreResult) : Outcome
    }

    private fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
    private fun historyUnavailable(command: CommandRef, read: ControlRecordRead) = negative(
        ControlStoreResult.Unconfirmed(command, emptySet(), UnconfirmedReason.HistoryUnavailable,
            ControlAttemptPhase.PreparingCandidate, read))

    private fun ControlStoreResult.withUnresolved(commands: Set<CommandRef>): ControlStoreResult = when (this) {
        is ControlStoreResult.Rejected -> copy(localUnresolvedCommands = commands)
        is ControlStoreResult.Conflict -> copy(localUnresolvedCommands = commands)
        is ControlStoreResult.RecoveryRequired -> copy(localUnresolvedCommands = commands)
        is ControlStoreResult.Unconfirmed -> copy(localUnresolvedCommands = commands)
        is ControlStoreResult.Confirmed -> error("positive results are published after storage confirmation")
    }

    private fun ControlEntryRead.originalEntry(): PayloadEntry = when (this) {
        is ControlEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEntryRead.Uninterpretable -> original
    }

    private fun same(left: ControlNode, right: ControlNode): Boolean = left.toPayloadEntry() == right.toPayloadEntry()

}
