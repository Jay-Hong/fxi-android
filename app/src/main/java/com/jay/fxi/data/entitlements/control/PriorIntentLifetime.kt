package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The prior-intent lifetime boundary that [RecoveryIntentV1]'s KDoc leaves to "the future lifecycle layer" (purger 설계
 * v3 final §7·§9 P2; P2-K, design agreed with Codex in P2K/design_codex.r1.md). One session, one axis, one target:
 * the intent must be stored and confirmed before a request, observation or protected lifetime may open, and it is
 * released only by a confirmed [RecoverIntent] handover after the gate is closed and the work is joined.
 *
 * **Test model only.** Nothing in production calls it, and [PriorIntentGate.ReadyForTest] is a test observation, not an
 * admission, request or access token. It adds no storage transition: it only drives the existing intent addition and
 * the existing RecoverIntent command. A changed target needs its own new intent; a restart does not restore this
 * object (the remaining intent is settled by the AfterRestart path).
 */
internal class PriorIntentLifetime private constructor(
    private val store: ControlRecordStore,
    val intent: RecoveryIntentV1,
    val source: ControlNode,
    val addition: CommandRef
) {
    companion object {
        fun prepare(store: ControlRecordStore, sessionId: String, ownerUid: String?, axis: PurgeScope, targetEpoch: String?): PriorIntentLifetime {
            require(sessionId.isNotEmpty())
            require(targetEpoch != "")
            val mutation = store.addition(ControlKind.RECOVERY_INTENT) { id ->
                set("id", ControlScalar.Text(id))
                set("sessionId", ControlScalar.Text(sessionId))
                set("ownerUid", ownerUid?.let(ControlScalar::Text) ?: ControlScalar.Null)
                set("axis", ControlScalar.Text(axis.name))
                set("targetEpoch", targetEpoch?.let(ControlScalar::Text) ?: ControlScalar.Null)
            } as ControlMutation.Add
            val source = (mutation.built as? ControlWriteResult.Written)?.node
                ?: throw IllegalArgumentException("invalid recovery intent")
            val intent = (ControlObligations.read(ControlKind.RECOVERY_INTENT, source) as ControlEntryRead.Interpreted)
                .value as RecoveryIntentV1
            return PriorIntentLifetime(store, intent, source, store.prepare(mutation))
        }
    }

    @Volatile private var confirmation: ControlStoreResult.Confirmed? = null
    @Volatile private var closedReason: PriorIntentClosedReason? = null
    @Volatile private var handover: CommandRef? = null
    @Volatile private var settledConfirmation: ControlStoreResult.Confirmed? = null
    private val lock = Any()
    private var activeWork = 0
    private var workDrained = CompletableDeferred<Unit>().apply { complete(Unit) }

    /** The gate as it stands, without storage I/O; an observed target change closes it for good. */
    fun gate(current: FenceV1): PriorIntentGate = synchronized(lock) {
        closedReason?.let { return PriorIntentGate.Closed(it) }
        if (current.ownerUid != intent.ownerUid || current.epoch(intent.axis) != intent.targetEpoch) {
            closedReason = PriorIntentClosedReason.TargetChanged
            confirmation = null
            return PriorIntentGate.Closed(PriorIntentClosedReason.TargetChanged)
        }
        val saved = confirmation ?: return PriorIntentGate.Closed(PriorIntentClosedReason.AwaitingConfirmation)
        return PriorIntentGate.ReadyForTest(intent, saved)
    }

    /** (Re)confirms the same addition command; opens only on a confirmed snapshot that holds the exact intent for [current]. */
    suspend fun confirmPrior(current: FenceV1): PriorIntentGate {
        val before = gate(current)
        if (before is PriorIntentGate.Closed && before.reason in setOf(
                PriorIntentClosedReason.TargetChanged, PriorIntentClosedReason.Closing, PriorIntentClosedReason.Settled)) return before
        confirmation = null
        val result = store.execute(addition)
        return synchronized(lock) {
            closedReason?.let { return@synchronized PriorIntentGate.Closed(it, result) }
            if (current.ownerUid != intent.ownerUid || current.epoch(intent.axis) != intent.targetEpoch) {
                closedReason = PriorIntentClosedReason.TargetChanged
                return@synchronized PriorIntentGate.Closed(PriorIntentClosedReason.TargetChanged, result)
            }
            if (result !is ControlStoreResult.Confirmed) {
                val reason = if (result is ControlStoreResult.RecoveryRequired) PriorIntentClosedReason.UnsafeRecord
                    else PriorIntentClosedReason.StorageUnconfirmed
                return@synchronized PriorIntentGate.Closed(reason, result)
            }
            if (!safeConfirmation(result, current)) return@synchronized PriorIntentGate.Closed(PriorIntentClosedReason.UnsafeRecord, result)
            confirmation = result
            gate(current)
        }
    }

    private fun safeConfirmation(result: ControlStoreResult.Confirmed, current: FenceV1): Boolean {
        val record = result.snapshot.record
        if (record.schemaVersion != 2 || record.blocksProtectedAdmission) return false
        if (ControlLifecycleBoundary.rawProblem(record.original) != null) return false
        // The shared fence check keeps this file off the access-epoch store's keys (ControlOwnerStructureTest).
        if (!ControlLifecycleBoundary.fence(record.original, FenceV1(intent.ownerUid, current.userAccessEpoch, current.krxCapabilityEpoch))) return false
        val matches = record.arrays.getValue(ControlKind.RECOVERY_INTENT).entries
            .filterIsInstance<ControlEntryRead.Interpreted>()
            .filter { it.value.id == intent.id }
        return matches.size == 1 && matches.single().value == intent &&
            matches.single().original.toPayloadEntry() == source.toPayloadEntry()
    }

    /** Closes the gate synchronously and for good, before any settlement. */
    fun closeLocalGate() = synchronized(lock) {
        if (closedReason != PriorIntentClosedReason.Settled) closedReason = PriorIntentClosedReason.Closing
        confirmation = null
    }

    /** Begins work only while this lifetime's confirmed test gate is open. */
    fun begin(current: FenceV1): PriorIntentWork? = synchronized(lock) {
        if (gate(current) !is PriorIntentGate.ReadyForTest) return@synchronized null
        if (activeWork == 0) workDrained = CompletableDeferred()
        activeWork++
        PriorIntentWork {
            synchronized(lock) {
                activeWork--
                if (activeWork == 0) workDrained.complete(Unit)
            }
        }
    }

    /** Publishes closure before suspending until all begun work finishes. */
    suspend fun closeAndDrain() {
        val pending = synchronized(lock) {
            closeLocalGate()
            if (activeWork == 0) null else workDrained
        }
        pending?.await()
    }

    /** Hands the intent over with the existing RecoverIntent command once closure is proved; retries reuse the first command. */
    suspend fun settle(input: RecoverIntentInput, orders: LifecycleOrderSource, context: AttemptContext): PriorIntentSettlement {
        settledConfirmation?.let { return PriorIntentSettlement.Settled(it) }
        synchronized(lock) {
            if (closedReason != PriorIntentClosedReason.Closing && closedReason != PriorIntentClosedReason.TargetChanged)
                return PriorIntentSettlement.Retained(PriorIntentRetainedReason.GateStillOpen)
            if (activeWork != 0) return PriorIntentSettlement.Retained(PriorIntentRetainedReason.WorkNotJoined)
        }
        if (input.source.toPayloadEntry() != source.toPayloadEntry())
            return PriorIntentSettlement.Retained(PriorIntentRetainedReason.SourceMismatch)
        val fixed = (handover?.body as? ControlCommandBody.Lifecycle)?.input?.recoverIntent?.input ?: input
        val runtime = context.intentRecovery
        if (fixed.closure !is HoldRecoveryClosure.SameProcess || runtime == null || runtime.binding != fixed.binding ||
            HoldRecoveryBoundary.closureProblem(source, fixed.closure, runtime, addition.ownerTrackingLifetimeId.value) != null)
            return PriorIntentSettlement.Retained(PriorIntentRetainedReason.ClosureUnproved)
        val command = handover ?: store.prepareRecoverIntent(input, orders).also { handover = it }
        val result = store.execute(command, context)
        if (result !is ControlStoreResult.Confirmed)
            return PriorIntentSettlement.Retained(PriorIntentRetainedReason.StorageUnconfirmed, result)
        closedReason = PriorIntentClosedReason.Settled
        settledConfirmation = result
        return PriorIntentSettlement.Settled(result)
    }
}

internal sealed interface PriorIntentGate {
    data class Closed(val reason: PriorIntentClosedReason, val storage: ControlStoreResult? = null) : PriorIntentGate

    /** A test observation only — not a production request, observation or access grant. */
    data class ReadyForTest(val intent: RecoveryIntentV1, val confirmation: ControlStoreResult.Confirmed) : PriorIntentGate
}

internal enum class PriorIntentClosedReason { AwaitingConfirmation, StorageUnconfirmed, UnsafeRecord, TargetChanged, Closing, Settled }

internal sealed interface PriorIntentSettlement {
    data class Retained(val reason: PriorIntentRetainedReason, val storage: ControlStoreResult? = null) : PriorIntentSettlement
    data class Settled(val confirmation: ControlStoreResult.Confirmed) : PriorIntentSettlement
}

internal enum class PriorIntentRetainedReason { GateStillOpen, ClosureUnproved, SourceMismatch, StorageUnconfirmed, WorkNotJoined }

/** A single work lease bound to its creating lifetime. */
internal class PriorIntentWork internal constructor(private val onFinish: () -> Unit) {
    private val finished = AtomicBoolean(false)

    fun finish() {
        if (finished.compareAndSet(false, true)) onFinish()
    }
}
