package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Unwired D2a-1 facade. Uses only the owner's record transaction API; never owns a DataStore, lock,
 * read-back flag or barrier. Inputs are prepared once, then all targets are resolved by id against
 * the latest D1 classification in the atomic update. Unchanged payload strings remain verbatim.
 *
 * Additions, seal joins, constrained non-settlement edits and explicit guard floor recapture are
 * the complete scope. Confirmation proves the documented postcondition, not historical causation.
 * There is deliberately no coordinator, admission, retry scheduler or settlement path here.
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
        val command = CommandRef(ids.next().toString(), actions.toList())
        check(tracking.commands.putIfAbsent(command.id, TrackedControlCommand(command)) == null) {
            "command UUID collision; do not reissue an identity to hide it"
        }
        return command
    }

    fun checkpoint(command: CommandRef): ControlCommandCheckpoint? = tracking.commands[command.id]
        ?.takeIf { it.command === command }
        ?.let {
            // Targets only advance from null to fixed values, before the flag becomes true.
            // Read the flag first so a requested checkpoint cannot contain an older partial list.
            val requested = it.confirmationRequested.get()
            ControlCommandCheckpoint(command, it.targets.get(), requested)
        }

    /** An unconfirmed missing own seal can be retried; a replacement same-key seal blocks it as TargetChanged. */
    suspend fun execute(command: CommandRef): ControlStoreResult = run(command, checkpoint = null, confirmOnly = false)

    /**
     * Previous-lifetime confirmation never applies a missing effect or adopts a current same-key seal.
     * A reference without a complete checkpoint is HistoryUnavailable, unless D1 first needs recovery.
     * A live command's local history is authoritative: a supplied checkpoint must agree with it.
     * Imported targets must also match the prepared operation; they never replace live tracking.
     * HistoryUnavailable marks even a confirmed live command unresolved; a later Conflict preserves that membership.
     * A missing fixed target is TargetMissing here: this path checks its presence, without attempting reapplication.
     * Use execute for a live command, and keep using confirmPrevious for a previous-lifetime reference.
     */
    suspend fun confirmPrevious(command: CommandRef, checkpoint: ControlCommandCheckpoint? = null): ControlStoreResult =
        run(command, checkpoint, confirmOnly = true)

    private suspend fun run(
        command: CommandRef,
        checkpoint: ControlCommandCheckpoint?,
        confirmOnly: Boolean
    ): ControlStoreResult {
        val known = tracking.commands[command.id]
        val tracked = if (known?.command === command) known else TrackedControlCommand(command)
        check(tracking.executing.add(command)) { "the same command is already executing" }
        val wasUnresolved = tracking.isUnresolved(command)
        tracking.markUnresolved(command)
        val observation = AtomicReference<ControlRecordRead?>(null)
        val phase = AtomicReference(ControlAttemptPhase.ReadingSnapshot)
        try {
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                observation.set(read)
                if (read !is ControlRecordRead.Supported) {
                    negative(ControlStoreResult.RecoveryRequired(command, emptySet(), when (read) {
                        is ControlRecordRead.MigrationOrRecoveryRequired -> RecoveryReason.MigrationOrRecovery
                        else -> RecoveryReason.UnreadableRecord
                    }, read))
                } else if (known?.command !== command && !confirmOnly) {
                    historyUnavailable(command, read)
                } else {
                    phase.set(ControlAttemptPhase.PreparingCandidate)
                    if (confirmOnly) {
                        if (checkpoint?.command !== command || !checkpoint.confirmationRequested ||
                            checkpoint.targets.size != command.actions.size || checkpoint.targets.any { it == null }
                        ) {
                            historyUnavailable(command, read)
                        } else if ((known?.command === command && !matchesLocalHistory(tracked, checkpoint)) ||
                            !matchesPreparedActions(command, checkpoint)
                        ) {
                            historyUnavailable(command, read)
                        } else {
                            if (known?.command !== command) {
                                // Only the temporary previous-lifetime tracker imports caller evidence.
                                tracked.targets.set(checkpoint.targets)
                                tracked.confirmationRequested.set(true)
                            }
                            decide(command, tracked, read, confirmOnly = true, phase)
                        }
                    } else decide(command, tracked, read, confirmOnly = false, phase)
                }
            }
            return when (val outcome = transaction.value) {
                is Outcome.Positive -> {
                    val confirmedSnapshot = ConfirmedControlSnapshot(reader.read(transaction.snapshot) as ControlRecordRead.Supported)
                    tracked.confirmed.set(true)
                    tracking.resolve(command)
                    ControlStoreResult.Confirmed(
                        command, tracking.snapshot(), outcome.effect, outcome.ids,
                        confirmedSnapshot,
                        ConfirmationProof(transaction.evidence)
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
        confirmOnly: Boolean,
        phase: AtomicReference<ControlAttemptPhase>
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
                        array += desired.node.toPayloadEntry()
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
                            changedKinds += action.kind
                        }
                    } else if (!mayConfirmPostcondition || !same(current.original, target.postcondition)) {
                        return conflict(ConflictReason.TargetChanged)
                    }
                }
            }
        }

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
                    RejectionReason.TooLarge(kind, encoded.bytes, encoded.limit), read))
                is PayloadWrite.Encoded -> candidate[payloadKey(kind)] = encoded.text
            }
        }
        val complete = reader.read(candidate) as? ControlRecordRead.Supported
            ?: return reject("the complete candidate must remain a supported record")
        candidateRejection(command, complete, effectiveIds, targets)?.let { return reject(it.detail) }
        tracked.confirmationRequested.set(true)
        phase.set(ControlAttemptPhase.ConfirmingStorage)
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

    private sealed interface Outcome {
        data class Positive(val effect: ConfirmedEffect, val ids: List<String>) : Outcome
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

    private fun payloadKey(kind: ControlKind) = stringPreferencesKey(when (kind) {
        ControlKind.SEAL -> "seal_v1"
        ControlKind.DEMAND -> "demand_v1"
        ControlKind.HOLD -> "hold_v1"
        ControlKind.RECOVERY_INTENT -> "recovery_intent_v1"
    })
}
