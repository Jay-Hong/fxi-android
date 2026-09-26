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
 * recapture, namespace settlement, and explicit schema/evidence management transitions.
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

    fun prepareRebindRequests(targets: List<ControlNode>, binding: LifecycleBinding, orders: LifecycleOrderSource,
        intents: Map<String, com.jay.fxi.data.entitlements.RefreshIntent> = emptyMap()): CommandRef =
        registerDemandAuth(DemandAuthPlan.rebind(targets, binding, orders, intents))

    fun prepareSettleQuery(requests: List<ControlNode>, guard: ControlNode?, retry: ControlNode?,
        binding: LifecycleBinding, decision: AcceptedQueryDecision, orders: LifecycleOrderSource): CommandRef =
        registerDemandAuth(DemandAuthPlan.settle(requests, guard, retry, binding, decision, orders,
            ids.next().toString(), ids.next().toString()))

    fun prepareUpdateAuth(guard: ControlNode?, retry: ControlNode?, binding: LifecycleBinding,
        event: LifecycleAuthEvent, orders: LifecycleOrderSource): CommandRef =
        registerDemandAuth(DemandAuthPlan.auth(guard, retry, binding, event, orders, ids.next().toString(), ids.next().toString()))

    fun prepareEndAuthBinding(guard: ControlNode, requests: List<ControlNode>, binding: LifecycleBinding,
        closure: LifecycleBindingClosure, replacement: LifecycleBinding?, orders: LifecycleOrderSource): CommandRef =
        registerDemandAuth(DemandAuthPlan.end(guard, requests, binding, closure, replacement, orders))

    private fun registerDemandAuth(plan: DemandAuthPlan): CommandRef {
        val id = ids.next().toString()
        return tracking.registerPrepared(CommandRef(id, ControlCommandBody.Lifecycle(plan.descriptor(id)), tracking.lifetimeId))
    }

    /** Global guard cleanup: neither REQUEST count nor current AUTH authority proves emptiness. */
    fun prepareRemoveEmptyGuard(expected: ControlNode): CommandRef {
        val id = ids.next().toString()
        val input = RemoveEmptyGuardPlan.prepare(expected).descriptor(id)
        return tracking.registerPrepared(CommandRef(id, ControlCommandBody.Lifecycle(input), tracking.lifetimeId))
    }

    /** Preparation only. 5d must join this fixed floor plan to source removal in one transaction. */
    fun prepareHoldFloor(hold: ControlNode, guard: ControlNode?, mergeNow: BootReading,
        origin: LifetimeId): HoldFloorPlan? = HoldFloorPlan.prepare(
        HoldFloorInput(hold, guard, mergeNow, origin, ids.next().toString()))

    /** Fixed source, UUIDs, order and floor anchor; execution never retains or reuses the suppliers. */
    fun prepareRecoverHold(input: RecoverHoldInput, orders: LifecycleOrderSource): CommandRef {
        val source = HoldRecoverySource.from(input.source)
        val rotating = source?.axes.orEmpty().filter {
            RecoveryRetirementBoundary.rotationRequired(checkNotNull(source), it, input.before)
        }
        val issued = RecoverHoldIds(ids.next().toString(), ids.next().toString(), ids.next().toString(),
            RecoveryFreshEpochs(if (PurgeScope.USER in rotating) ids.next().toString() else null,
                if (PurgeScope.CAPABILITY in rotating) ids.next().toString() else null))
        val plan = RecoverHoldPlan.prepare(input, issued, orders)
        return tracking.registerPrepared(CommandRef(issued.operationId,
            ControlCommandBody.Lifecycle(plan.descriptor()), tracking.lifetimeId))
    }

    /** Fixed intent source, IDs and rotating-axis UUIDs; no supplier is retained for retries. */
    fun prepareRecoverIntent(input: RecoverIntentInput, orders: LifecycleOrderSource): CommandRef {
        val source = IntentRecoverySource.from(input.source)
        val rotating = source?.axes.orEmpty().filter {
            RecoveryRetirementBoundary.rotationRequired(checkNotNull(source), it, input.before)
        }
        val issued = RecoverIntentIds(ids.next().toString(), ids.next().toString(),
            RecoveryFreshEpochs(if (PurgeScope.USER in rotating) ids.next().toString() else null,
                if (PurgeScope.CAPABILITY in rotating) ids.next().toString() else null))
        val plan = RecoverIntentPlan.prepare(input, issued, orders)
        return tracking.registerPrepared(CommandRef(issued.operationId,
            ControlCommandBody.Lifecycle(plan.descriptor()), tracking.lifetimeId))
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

    fun checkpoint(command: CommandRef): ControlCommandCheckpoint? {
        if (command.ownerTrackingLifetimeId !== tracking.lifetimeId) return null
        val tracked = tracking.findPrepared(command) ?: return null
        val view = command.captureStateAndBody()
        if (view.state != ControlCommandLifecycle.RETAINED) return null
        val body = checkNotNull(view.body) { "retained command has no executable body" }
        if (body !is ControlCommandBody.Mutations) return null
        // Targets only advance from null to fixed values, before the flag becomes true.
        val requested = tracked.confirmationRequested.get()
        return ControlCommandCheckpoint(command, tracked.targets.get(), requested)
    }

    /** Capture the actual fence and current executor under the caller's coordinator lock.
     * A current-owner target requires a fresh demand; omission never opts out of that obligation.
     * All supplied values and issued IDs remain fixed across retries.
     */
    fun prepareRetiredNamespaceSettlement(
        target: ControlNode, before: FenceV1, executor: SettlementExecutor, demand: SettlementDemand?
    ): CommandRef {
        val operationId = ids.next().toString()
        val input = RetiredNamespaceSettlement(target, before, executor, operationId,
            demand?.let { ids.next().toString() }, demand)
        return tracking.registerPrepared(CommandRef(operationId, ControlCommandBody.SettleRetiredNamespace(input), tracking.lifetimeId))
    }

    /** Fix companions from the caller's observed record, captured with fence/executor under its lock.
     * Execution rechecks the complete latest companion set; preparation grants no storage authority.
     */
    fun prepareCurrentNullSettlement(
        nullTargets: List<ControlNode>, observed: ControlRecordRead.Supported, before: FenceV1,
        executor: SettlementExecutor, demand: SettlementDemand
    ): CommandRef {
        val axes = nullTargets.mapNotNull {
            ((ControlObligations.read(ControlKind.SEAL, it) as? ControlEntryRead.Interpreted)?.value as? SealV1)
                ?.takeIf { seal -> seal.kind == SealTargetKind.NULL_NAMESPACE }?.key?.axis
        }.toSet()
        val companions = CurrentNullSettlementTransition.companions(observed, before, axes).map { it.original }
        val operationId = ids.next().toString()
        val input = CurrentNullSettlement(nullTargets, companions, before, executor, operationId, ids.next().toString(), demand,
            if (PurgeScope.USER in axes) ids.next().toString() else null,
            if (PurgeScope.CAPABILITY in axes) ids.next().toString() else null)
        return tracking.registerPrepared(CommandRef(operationId, ControlCommandBody.RotateAndSettleCurrentNull(input), tracking.lifetimeId))
    }

    /** Historical NULL handover. Issue only an operation ID; current namespace and demands stay intact. */
    fun prepareRetiredNullSettlement(
        targets: List<ControlNode>, before: FenceV1, executor: SettlementExecutor
    ): CommandRef {
        val input = RetiredNullSettlement(targets, before, executor, ids.next().toString())
        return tracking.registerPrepared(CommandRef(input.operationId, ControlCommandBody.SettleRetiredNull(input), tracking.lifetimeId))
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

    /**
     * The single consumer declares durable handoff, consumption and business-worker join complete.
     * It must retain this ref for management retries after cancellation or an unconfirmed removal.
     * This method neither checks that declaration nor issues business/admission authority.
     */
    suspend fun releaseAfterConsumption(command: CommandRef): ControlCommandReleaseResult {
        if (command.ownerTrackingLifetimeId !== tracking.lifetimeId) {
            return releaseRejected(command, ReleaseRejectionReason.WrongTrackerLifetime)
        }
        if (command.lifecycleState == ControlCommandLifecycle.RELEASED) return alreadyReleased(command)
        if (command.lifecycleState == ControlCommandLifecycle.TERMINATED) return alreadyTerminated(command)
        if (command.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING)
            return releaseRejected(command, ReleaseRejectionReason.OtherManagementPath)
        if (!tracking.executing.add(command)) return releaseRejected(command, ReleaseRejectionReason.InFlight)
        try {
            // A concurrent release can complete between the precheck and lease acquisition.
            if (command.lifecycleState == ControlCommandLifecycle.RELEASED) return alreadyReleased(command)
            if (command.lifecycleState == ControlCommandLifecycle.TERMINATED) return alreadyTerminated(command)
            if (command.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING)
                return releaseRejected(command, ReleaseRejectionReason.OtherManagementPath)
            val tracked = tracking.findPrepared(command)
            when (val admission = ControlCommandReleaseEligibility.decide(
                command, tracking.lifetimeId, tracked, inFlight = false, unresolved = tracking.isUnresolved(command)
            )) {
                is ControlCommandReleaseEligibility.Decision.Rejected -> return releaseRejected(command, admission.reason)
                ControlCommandReleaseEligibility.Decision.AlreadyReleased -> return alreadyReleased(command)
                ControlCommandReleaseEligibility.Decision.AlreadyTerminated -> return alreadyTerminated(command)
                ControlCommandReleaseEligibility.Decision.Eligible -> Unit
            }
            return releaseAttempt(command, checkNotNull(tracked))
        } finally {
            // Failure and cancellation preserve the lifecycle and both recovery memberships.
            tracking.executing.remove(command)
        }
    }

    private suspend fun releaseAttempt(command: CommandRef, tracked: TrackedControlCommand): ControlCommandReleaseResult {
        val view = command.captureStateAndBody()
        check(view.state == ControlCommandLifecycle.RETAINED || view.state == ControlCommandLifecycle.RELEASE_PENDING)
        check(view.body is ControlCommandBody.Mutations)
        val observation = AtomicReference<ControlRecordRead?>(null)
        val phase = AtomicReference(ControlAttemptPhase.ReadingSnapshot)
        var confirmedCandidate: Preferences? = null
        try {
            val transaction = owner.transactRecord<ControlCommandReleaseResult?> { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation.set(read)
                if (read is ControlRecordRead.Supported && ControlAppliedEvidence.own(read, command) != null) {
                    tracked.observedApplied.set(true)
                }
                phase.set(ControlAttemptPhase.PreparingCandidate)
                val decision = ControlCommandReleaseDecision.decide(read, tracked, view)
                val descriptor = when (decision) {
                    is ControlCommandReleaseDecision.Decision.Conflict -> return@transactRecord RecordTransactionDecision.Observe(
                        ControlCommandReleaseResult.Conflict(command, emptySet(), emptySet(), decision.reason,
                            command.lifecycleState, read as ControlRecordRead.Supported))
                    is ControlCommandReleaseDecision.Decision.RecoveryRequired -> return@transactRecord RecordTransactionDecision.Observe(
                        ControlCommandReleaseResult.RecoveryRequired(command, emptySet(), emptySet(), decision.reason, command.lifecycleState, read))
                    is ControlCommandReleaseDecision.Decision.Ready -> decision.descriptor
                }
                val candidate = when (val built = ControlReleaseCandidate.build(read as ControlRecordRead.Supported, command, codec)) {
                    is ControlReleaseCandidate.Result.Rejected -> return@transactRecord RecordTransactionDecision.Observe(
                        ControlCommandReleaseResult.Rejected(command, emptySet(), emptySet(), ReleaseRejectionReason.Encoding(built.reason),
                            command.lifecycleState, read))
                    ControlReleaseCandidate.Result.Inconsistent -> return@transactRecord RecordTransactionDecision.Observe(
                        ControlCommandReleaseResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.InconsistentReclamation,
                            command.lifecycleState, read))
                    is ControlReleaseCandidate.Result.Ready -> built.snapshot
                }
                val candidateBarrier = DataStoreAccessEpochStore.READ_BARRIER
                require(candidate.asMap()[candidateBarrier] == snapshot.asMap()[candidateBarrier]) {
                    "read_barrier is owned by DataStoreAccessEpochStore"
                }
                confirmedCandidate = candidate
                if (command.lifecycleState == ControlCommandLifecycle.RETAINED) {
                    // No suspension: descriptor, membership, then closure before requesting storage.
                    tracked.bindReleaseDescriptor(descriptor)
                    tracking.publishPendingRelease(command)
                    command.beginRelease()
                }
                phase.set(ControlAttemptPhase.ConfirmingStorage)
                RecordTransactionDecision.Confirm(candidate, null)
            }
            transaction.value?.let { return it.withReleaseRecoveryWork(tracking.recoverySnapshot()) }
            val returned = reader.read(transaction.snapshot)
            check(ControlReleaseCandidate.hasAbsencePostcondition(returned, command)) { "release postcondition was not confirmed" }
            val barrier = DataStoreAccessEpochStore.READ_BARRIER
            check(transaction.snapshot.toMutablePreferences().apply { remove(barrier) } ==
                checkNotNull(confirmedCandidate).toMutablePreferences().apply { remove(barrier) }) { "release confirmation changed unrelated values" }
            // No suspension between owner confirmation, closure and exact-history cleanup.
            command.completeRelease()
            tracking.finishRelease(tracked)
            val work = tracking.recoverySnapshot()
            return ControlCommandReleaseResult.Released(command, work.unresolvedCommands, work.pendingReleases,
                ConfirmedControlSnapshot(returned as ControlRecordRead.Supported), ConfirmationProof(transaction.evidence))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            val work = tracking.recoverySnapshot()
            return ControlCommandReleaseResult.Unconfirmed(command, work.unresolvedCommands, work.pendingReleases,
                command.lifecycleState, phase.get(), observation.get(), failure)
        }
    }

    private fun releaseRejected(command: CommandRef, reason: ReleaseRejectionReason): ControlCommandReleaseResult.Rejected {
        val work = tracking.recoverySnapshot()
        return ControlCommandReleaseResult.Rejected(command, work.unresolvedCommands, work.pendingReleases, reason, command.lifecycleState, null)
    }

    private fun alreadyReleased(command: CommandRef): ControlCommandReleaseResult.AlreadyReleased {
        val work = tracking.recoverySnapshot()
        return ControlCommandReleaseResult.AlreadyReleased(command, work.unresolvedCommands, work.pendingReleases)
    }

    private fun alreadyTerminated(command: CommandRef): ControlCommandReleaseResult.AlreadyTerminated {
        val work = tracking.recoverySnapshot()
        return ControlCommandReleaseResult.AlreadyTerminated(command, work.unresolvedCommands, work.pendingReleases)
    }

    private fun ControlCommandReleaseResult.withReleaseRecoveryWork(work: LocalRecoveryWork): ControlCommandReleaseResult = when (this) {
        is ControlCommandReleaseResult.Rejected -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlCommandReleaseResult.Conflict -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlCommandReleaseResult.RecoveryRequired -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlCommandReleaseResult.Unconfirmed, is ControlCommandReleaseResult.Released,
        is ControlCommandReleaseResult.AlreadyReleased, is ControlCommandReleaseResult.AlreadyTerminated ->
            error("release transaction carried a non-decision result")
    }

    /** Declare that no business Confirm was submitted and all related work has joined. */
    suspend fun abandonBeforeFirstConfirm(command: CommandRef, closure: TerminationClosure): ControlCompletionResult {
        completionPrecheck(command, firstEntry = true)?.let { return it }
        if (!tracking.executing.add(command)) return completionRejected(command, CompletionRejectionReason.InFlight)
        try {
            completionPrecheck(command, firstEntry = true)?.let { return it }
            val tracked = tracking.findPrepared(command)
                ?: return completionRejected(command, CompletionRejectionReason.NotRegisteredIdentity)
            val body = command.captureStateAndBody().body
            if (body == null || body is ControlCommandBody.Handover)
                return completionRejected(command, CompletionRejectionReason.UnsupportedInThisUnit)
            terminationClosureViolation(command, closure, null)?.let {
                return completionRejected(command, CompletionRejectionReason.ClosureNotSatisfied(it))
            }
            neverConfirmViolation(tracked)?.let {
                return completionRejected(command, CompletionRejectionReason.NotNeverConfirm(it))
            }
            return terminationAttempt(command, tracked, closure, body, TerminationRequest.FirstNeverConfirm)
        } finally {
            tracking.executing.remove(command)
        }
    }

    /** Consume only a confirmed current Rotation with a complete caller declaration. */
    suspend fun completeAfterConsumption(command: CommandRef, closure: TerminationClosure,
        consumption: RotationConsumption): ControlCompletionResult {
        completionPrecheck(command, firstEntry = true)?.let { return it }
        if (!tracking.executing.add(command)) return completionRejected(command, CompletionRejectionReason.InFlight)
        try {
            completionPrecheck(command, firstEntry = true)?.let { return it }
            val tracked = tracking.findPrepared(command)
                ?: return completionRejected(command, CompletionRejectionReason.NotRegisteredIdentity)
            val view = command.captureStateAndBody()
            val body = view.body as? ControlCommandBody.RotateAndSettle
                ?: return completionRejected(command, CompletionRejectionReason.UnsupportedInThisUnit)
            check(view.state == ControlCommandLifecycle.RETAINED)
            if (!tracked.confirmed.get()) return completionRejected(command, CompletionRejectionReason.NotConfirmed)
            if (tracking.isUnresolved(command)) return completionRejected(command, CompletionRejectionReason.Unresolved)
            if (!consumption.resultConsumed || !consumption.followUpCompletedOrDurablyOwned)
                return completionRejected(command, CompletionRejectionReason.ConsumptionNotDeclared)
            terminationClosureViolation(command, closure, null)?.let {
                return completionRejected(command, CompletionRejectionReason.ClosureNotSatisfied(it))
            }
            check(body.input.operationId == command.id) { "rotation body must match the command" }
            return terminationAttempt(command, tracked, closure, body, TerminationRequest.FirstConsumed)
        } finally {
            tracking.executing.remove(command)
        }
    }

    /** Retry only the fixed descriptor authority; the caller supplies a current closure declaration. */
    suspend fun retryTermination(command: CommandRef, closure: TerminationClosure): ControlCompletionResult {
        completionPrecheck(command, firstEntry = false)?.let { return it }
        if (!tracking.executing.add(command)) return completionRejected(command, CompletionRejectionReason.InFlight)
        try {
            completionPrecheck(command, firstEntry = false)?.let { return it }
            val tracked = tracking.findPrepared(command)
                ?: return completionRejected(command, CompletionRejectionReason.NotRegisteredIdentity)
            val body = command.captureStateAndBody().body
            if (body == null || body is ControlCommandBody.Handover)
                return completionRejected(command, CompletionRejectionReason.UnsupportedInThisUnit)
            val descriptor = tracked.terminationDescriptor
                ?: return completionRejected(command, CompletionRejectionReason.NotTerminationPending)
            val binding = fixedTerminationBinding(command, tracked, body, descriptor)
                ?: return completionRejected(command, CompletionRejectionReason.UnsupportedInThisUnit)
            terminationClosureViolation(command, closure, binding)?.let {
                return completionRejected(command, CompletionRejectionReason.ClosureNotSatisfied(it))
            }
            return terminationAttempt(command, tracked, closure, body, TerminationRequest.Retry(descriptor))
        } finally {
            tracking.executing.remove(command)
        }
    }

    /** The fixed descriptor authority; null means the descriptor's body kind is not supported in this unit. */
    private fun fixedTerminationBinding(command: CommandRef, tracked: TrackedControlCommand, body: ControlCommandBody,
        descriptor: TerminationPendingDescriptor): TerminationClosureBinding? = when (descriptor) {
        is TerminationPendingDescriptor.EvidenceAbsent -> {
            check(descriptor.mode == CompletionMode.NeverSubmitted &&
                descriptor.entry == TerminationEntry.AbandonBeforeFirstConfirm)
            descriptor.closureBinding
        }
        is TerminationPendingDescriptor.ExactEvidenceAndSeals -> {
            check(descriptor.mode == CompletionMode.Consumed &&
                descriptor.entry == TerminationEntry.ConsumedRotation)
            (body as? ControlCommandBody.RotateAndSettle)?.let { rotation ->
                check(descriptor.operationId == command.id && rotation.input.operationId == command.id &&
                    descriptor.orderedSealIds == rotation.input.seals.map { it.id } &&
                    tracked.expectedApplied === descriptor.expectedRotation) {
                    "fixed rotation termination authority changed"
                }
                descriptor.closureBinding
            }
        }
    }

    /** One closure condition for the entry fast path and the owner decision. */
    private fun terminationClosureViolation(command: CommandRef, closure: TerminationClosure,
        binding: TerminationClosureBinding?): ClosureViolation? =
        closure.violation(command, tracking.lifetimeId, binding)

    private fun completionPrecheck(command: CommandRef, firstEntry: Boolean): ControlCompletionResult? {
        if (command.ownerTrackingLifetimeId !== tracking.lifetimeId)
            return completionRejected(command, CompletionRejectionReason.WrongTrackerLifetime)
        return when (command.lifecycleState) {
            ControlCommandLifecycle.TERMINATED -> completionAlreadyTerminated(command)
            ControlCommandLifecycle.RETAINED -> if (firstEntry) null
                else completionRejected(command, CompletionRejectionReason.NotTerminationPending)
            ControlCommandLifecycle.TERMINATION_PENDING -> if (firstEntry)
                completionRejected(command, CompletionRejectionReason.OtherManagementPath) else null
            ControlCommandLifecycle.RELEASE_PENDING, ControlCommandLifecycle.RELEASED ->
                completionRejected(command, CompletionRejectionReason.OtherManagementPath)
        }
    }

    private fun neverConfirmViolation(tracked: TrackedControlCommand): NeverConfirmViolation? = when {
        tracked.confirmationRequested.get() -> NeverConfirmViolation.ConfirmationRequested
        tracked.firstConfirmDiscontinuityCount != null -> NeverConfirmViolation.FirstConfirmBound
        tracked.confirmed.get() -> NeverConfirmViolation.Confirmed
        tracked.expectedApplied != null -> NeverConfirmViolation.ExpectedApplied
        tracked.observedApplied.get() -> NeverConfirmViolation.ObservedApplied
        tracked.terminationDescriptor != null -> NeverConfirmViolation.TerminationDescriptorBound
        else -> null
    }

    private sealed interface TerminationRequest {
        data object FirstNeverConfirm : TerminationRequest
        data object FirstConsumed : TerminationRequest
        data class Retry(val descriptor: TerminationPendingDescriptor) : TerminationRequest
    }

    private suspend fun terminationAttempt(command: CommandRef, tracked: TrackedControlCommand,
        closure: TerminationClosure, body: ControlCommandBody, request: TerminationRequest): ControlCompletionResult {
        val observation = AtomicReference<ControlRecordRead?>(null)
        val phase = AtomicReference(ControlAttemptPhase.ReadingSnapshot)
        var confirmedCandidate: Preferences? = null
        var confirmedPlan: TerminationPendingDescriptor? = null
        try {
            val transaction = owner.transactRecord<ControlCompletionResult?> { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation.set(read)
                if (read is ControlRecordRead.Supported && ControlAppliedEvidence.own(read, command) != null)
                    tracked.observedApplied.set(true)
                phase.set(ControlAttemptPhase.PreparingCandidate)
                fun recovery(reason: RecoveryReason) = RecordTransactionDecision.Observe<ControlCompletionResult?>(
                    ControlCompletionResult.RecoveryRequired(command, emptySet(), emptySet(), reason, command.lifecycleState, read))
                if (read !is ControlRecordRead.Supported) return@transactRecord recovery(
                    if (read is ControlRecordRead.MigrationOrRecoveryRequired) RecoveryReason.MigrationOrRecovery
                    else RecoveryReason.UnreadableRecord)
                // Final eligibility is decided here; the entry checks are only the fast path.
                val recheckBinding = when (request) {
                    is TerminationRequest.Retry -> fixedTerminationBinding(command, tracked, body, request.descriptor)
                        ?: return@transactRecord RecordTransactionDecision.Observe(ControlCompletionResult.Rejected(command,
                            emptySet(), emptySet(), CompletionRejectionReason.UnsupportedInThisUnit, command.lifecycleState, read))
                    TerminationRequest.FirstNeverConfirm, TerminationRequest.FirstConsumed -> null
                }
                terminationClosureViolation(command, closure, recheckBinding)?.let {
                    return@transactRecord RecordTransactionDecision.Observe(ControlCompletionResult.Rejected(command,
                        emptySet(), emptySet(), CompletionRejectionReason.ClosureNotSatisfied(it), command.lifecycleState, read))
                }
                if (read.schemaVersion != 2) return@transactRecord recovery(RecoveryReason.ControlSchemaMigrationRequired)
                if (read.hasUninterpretableMetadata) return@transactRecord recovery(RecoveryReason.UninterpretableMetadata)
                if (read.hasUninterpretable) return@transactRecord recovery(RecoveryReason.UninterpretableObligations)
                val plan: TerminationPendingDescriptor
                val candidate: Preferences
                if (request is TerminationRequest.FirstNeverConfirm ||
                    (request is TerminationRequest.Retry && request.descriptor is TerminationPendingDescriptor.EvidenceAbsent)) {
                    // The entry gates Handover; the remaining non-Mutations/Lifecycle body is Rotation.
                    if (ControlAppliedEvidence.own(read, command) != null ||
                        (body !is ControlCommandBody.Mutations && body !is ControlCommandBody.Lifecycle &&
                            read.arrays.getValue(ControlKind.SEAL).entries.any {
                                ((it as ControlEntryRead.Interpreted).value as SealV1).settlement?.operationId == command.id
                            })
                    ) return@transactRecord RecordTransactionDecision.Observe(
                        ControlCompletionResult.Conflict(command, emptySet(), emptySet(), ConflictReason.CommandEvidenceMismatch,
                            command.lifecycleState, read))
                    candidate = read.original
                    plan = if (request is TerminationRequest.Retry) request.descriptor else
                        TerminationPendingDescriptor.EvidenceAbsent(CompletionMode.NeverSubmitted,
                            TerminationEntry.AbandonBeforeFirstConfirm, closure.binding())
                } else {
                    val input = (body as ControlCommandBody.RotateAndSettle).input
                    val expected = when (request) {
                        is TerminationRequest.Retry ->
                            (request.descriptor as TerminationPendingDescriptor.ExactEvidenceAndSeals).expectedRotation
                        TerminationRequest.FirstConsumed -> tracked.expectedApplied?.let {
                            check(it is AppliedEvidence.Rotation) { "expected evidence must be Rotation" }
                            it
                        }
                        TerminationRequest.FirstNeverConfirm -> error("unexpected never-confirm request")
                    }
                    when (val decision = ControlRotationConsumption.decide(read, command, input, expected,
                        request is TerminationRequest.Retry, codec)) {
                        is ControlRotationConsumption.Decision.Recovery -> return@transactRecord recovery(decision.reason)
                        ControlRotationConsumption.Decision.Conflict -> return@transactRecord RecordTransactionDecision.Observe(
                            ControlCompletionResult.Conflict(command, emptySet(), emptySet(), ConflictReason.CommandEvidenceMismatch,
                                command.lifecycleState, read))
                        is ControlRotationConsumption.Decision.Rejected -> return@transactRecord RecordTransactionDecision.Observe(
                            ControlCompletionResult.Rejected(command, emptySet(), emptySet(),
                                CompletionRejectionReason.Encoding(decision.reason), command.lifecycleState, read))
                        is ControlRotationConsumption.Decision.Ready -> candidate = decision.candidate
                    }
                    plan = if (request is TerminationRequest.Retry) request.descriptor else
                        TerminationPendingDescriptor.ExactEvidenceAndSeals(CompletionMode.Consumed,
                            TerminationEntry.ConsumedRotation, closure.binding(), checkNotNull(expected), command.id,
                            input.seals.map { it.id })
                }
                val dependencyReason = if (plan is TerminationPendingDescriptor.ExactEvidenceAndSeals)
                    rotationDependencyViolation(command, plan) else null
                if (dependencyReason != null) {
                    RecordTransactionDecision.Observe(ControlCompletionResult.Rejected(command, emptySet(), emptySet(),
                        dependencyReason, command.lifecycleState, read))
                } else {
                    val barrier = DataStoreAccessEpochStore.READ_BARRIER
                    check(candidate.asMap()[barrier] == snapshot.asMap()[barrier]) {
                        "read_barrier is owned by DataStoreAccessEpochStore"
                    }
                    confirmedCandidate = candidate
                    confirmedPlan = plan
                    if (request !is TerminationRequest.Retry) {
                        tracked.bindTerminationDescriptor(plan)
                        tracking.publishPendingTermination(command)
                        command.beginTermination()
                    }
                    phase.set(ControlAttemptPhase.ConfirmingStorage)
                    RecordTransactionDecision.Confirm(candidate, null)
                }
            }
            transaction.value?.let { return it.withCompletionRecoveryWork(tracking.recoverySnapshot()) }
            val returned = reader.read(transaction.snapshot)
            validateTerminationReturn(checkNotNull(confirmedPlan), returned, checkNotNull(confirmedCandidate), command)
            command.completeTermination()
            tracking.finishTermination(tracked)
            val work = tracking.recoverySnapshot()
            return ControlCompletionResult.Completed(command, work.unresolvedCommands, work.pendingReleases,
                if (confirmedPlan is TerminationPendingDescriptor.ExactEvidenceAndSeals) CompletionMode.Consumed
                else CompletionMode.NeverSubmitted, ConfirmedControlSnapshot(returned as ControlRecordRead.Supported),
                ConfirmationProof(transaction.evidence))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            val work = tracking.recoverySnapshot()
            return ControlCompletionResult.Unconfirmed(command, work.unresolvedCommands, work.pendingReleases,
                command.lifecycleState, phase.get(), observation.get(), failure)
        }
    }

    private fun rotationDependencyViolation(command: CommandRef,
        plan: TerminationPendingDescriptor.ExactEvidenceAndSeals): CompletionRejectionReason? {
        val protectedAtoms = buildSet<DependencyAtom> {
            add(DependencyAtom.AppliedRow(command.id, command.ownerTrackingLifetimeId.value))
            for (sealId in plan.orderedSealIds) {
                add(DependencyAtom.ControlRow(ControlKind.SEAL, sealId))
                add(DependencyAtom.SealWitness(sealId, plan.operationId))
            }
        }
        for (dependent in tracking.dependencyCandidatesExcluding(command)) {
            val view = dependent.captureStateAndBody()
            val tracked = if (view.state == ControlCommandLifecycle.RELEASED ||
                view.state == ControlCommandLifecycle.TERMINATED) null else tracking.findPrepared(dependent)
            val projection = projectDependency(DependencyProjectionInput(dependent.id,
                dependent.ownerTrackingLifetimeId.value, view, tracked?.targets?.get(),
                tracked?.releaseDescriptor, tracked?.terminationDescriptor))
            when (val intersection = classifyDependency(projection, protectedAtoms)) {
                is DependencyIntersection.Present -> return CompletionRejectionReason.DependencyPresent(
                    dependent.id, dependent.ownerTrackingLifetimeId.value, intersection.atom)
                is DependencyIntersection.Unknown -> return CompletionRejectionReason.DependencyUnknown(
                    dependent.id, dependent.ownerTrackingLifetimeId.value, intersection.gap?.source)
                DependencyIntersection.Clear -> Unit
            }
        }
        return null
    }

    private fun validateTerminationReturn(plan: TerminationPendingDescriptor, returned: ControlRecordRead,
        candidate: Preferences, command: CommandRef) {
        when (plan) {
            is TerminationPendingDescriptor.EvidenceAbsent -> {
                check(ControlReleaseCandidate.hasAbsencePostcondition(returned, command)) {
                    "termination absence postcondition was not confirmed"
                }
                val barrier = DataStoreAccessEpochStore.READ_BARRIER
                check(returned.original.toMutablePreferences().apply { remove(barrier) } ==
                    candidate.toMutablePreferences().apply { remove(barrier) }) {
                    "termination confirmation changed unrelated values"
                }
            }
            is TerminationPendingDescriptor.ExactEvidenceAndSeals ->
                check(ControlRotationConsumption.validateReturn(returned, command, candidate)) {
                    "termination confirmation changed unrelated values"
                }
        }
    }

    private fun completionRejected(command: CommandRef, reason: CompletionRejectionReason): ControlCompletionResult.Rejected {
        val work = tracking.recoverySnapshot()
        return ControlCompletionResult.Rejected(command, work.unresolvedCommands, work.pendingReleases,
            reason, command.lifecycleState, null)
    }

    private fun completionAlreadyTerminated(command: CommandRef): ControlCompletionResult.AlreadyTerminated {
        val work = tracking.recoverySnapshot()
        return ControlCompletionResult.AlreadyTerminated(command, work.unresolvedCommands, work.pendingReleases)
    }

    private fun ControlCompletionResult.withCompletionRecoveryWork(work: LocalRecoveryWork): ControlCompletionResult = when (this) {
        is ControlCompletionResult.Rejected -> copy(localUnresolvedCommands = work.unresolvedCommands,
            localPendingReleases = work.pendingReleases)
        is ControlCompletionResult.Conflict -> copy(localUnresolvedCommands = work.unresolvedCommands,
            localPendingReleases = work.pendingReleases)
        is ControlCompletionResult.RecoveryRequired -> copy(localUnresolvedCommands = work.unresolvedCommands,
            localPendingReleases = work.pendingReleases)
        is ControlCompletionResult.Unconfirmed, is ControlCompletionResult.Completed,
        is ControlCompletionResult.AlreadyTerminated -> error("termination transaction carried a non-decision result")
    }

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

    /** Reclaims earlier tracker evidence atomically; current-lifetime rows are always retained. */
    suspend fun reclaimPreviousLifetimeEvidence(): ControlEvidenceReclamationResult {
        var observation: ControlRecordRead? = null
        return try {
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation = read
                ReclaimPreviousLifetimeEvidence.decide(read, tracking.lifetimeId, codec)
            }
            transaction.value ?: ControlEvidenceReclamationResult.Confirmed(
                ConfirmedControlSnapshot(reader.read(transaction.snapshot) as ControlRecordRead.Supported),
                ConfirmationProof(transaction.evidence))
        } catch (failure: IOException) {
            ControlEvidenceReclamationResult.Unconfirmed(observation, failure)
        }
    }

    private suspend fun run(
        command: CommandRef,
        checkpoint: ControlCommandCheckpoint?,
        confirmOnly: Boolean,
        context: AttemptContext?
    ): ControlStoreResult {
        terminalResult(command)?.let { return it }
        check(tracking.executing.add(command)) { "the same command is already executing" }
        try {
            // Re-read after acquiring the lease, before even temporarily adding unresolved work.
            terminalResult(command)?.let { return it }
            val view = command.captureStateAndBody()
            if (view.state != ControlCommandLifecycle.RETAINED) return checkNotNull(terminalResult(command))
            val body = checkNotNull(view.body) { "retained command has no executable body" }
            val actions = (body as? ControlCommandBody.Mutations)?.actions.orEmpty()
            val known = tracking.findPrepared(command)
            val tracked = known ?: TrackedControlCommand(command, actions)
            val wasUnresolved = tracking.isUnresolved(command)
            tracking.markUnresolved(command)
            return attempt(command, body, actions, checkpoint, confirmOnly, context, known, tracked, wasUnresolved)
        } finally {
            // Unexpected programming exceptions propagate; their attempt remains conservatively unresolved.
            tracking.executing.remove(command)
        }
    }

    private suspend fun attempt(
        command: CommandRef,
        body: ControlCommandBody,
        actions: List<ControlMutation>,
        checkpoint: ControlCommandCheckpoint?,
        confirmOnly: Boolean,
        context: AttemptContext?,
        known: TrackedControlCommand?,
        tracked: TrackedControlCommand,
        wasUnresolved: Boolean
    ): ControlStoreResult {
        val observation = AtomicReference<ControlRecordRead?>(null)
        val phase = AtomicReference(ControlAttemptPhase.ReadingSnapshot)
        try {
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation.set(read)
                val own = (read as? ControlRecordRead.Supported)?.let { ControlAppliedEvidence.own(it, command) }
                // Actual observation precedes registration, schema and opaque-sibling rejection.
                if (own != null) tracked.observedApplied.set(true)
                val decision = run decision@ {
                    if (read !is ControlRecordRead.Supported) {
                        negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), when (read) {
                            is ControlRecordRead.MigrationOrRecoveryRequired -> RecoveryReason.MigrationOrRecovery
                            else -> RecoveryReason.UnreadableRecord
                        }, read))
                    } else if (known == null && !confirmOnly) {
                        historyUnavailable(command, read)
                    } else {
                        phase.set(ControlAttemptPhase.PreparingCandidate)
                        val handover = (body as? ControlCommandBody.Handover)?.input
                        val lifecycle = (body as? ControlCommandBody.Lifecycle)?.input
                        if (lifecycle != null) {
                            ControlLifecycleBoundary.recordProblem(read)?.let { reason ->
                                return@decision negative(ControlStoreResult.RecoveryRequired(
                                    command, emptySet(), emptySet(), reason, read))
                            }
                        }
                        if (handover != null) {
                            RetiredNamespaceSettlementTransition.recordProblem(read)?.let { reason ->
                                return@decision negative(ControlStoreResult.RecoveryRequired(
                                    command, emptySet(), emptySet(), reason, read))
                            }
                        }
                        val rotation = body as? ControlCommandBody.RotateAndSettle
                        if (confirmOnly && rotation == null && handover == null && lifecycle == null) {
                            if (checkpoint?.command !== command || !checkpoint.confirmationRequested ||
                                checkpoint.targets.size != actions.size || checkpoint.targets.any { it == null } ||
                                (known != null && !matchesLocalHistory(tracked, checkpoint)) ||
                                !matchesPreparedActions(actions, checkpoint)
                            ) return@decision historyUnavailable(command, read)
                            if (known == null) {
                                tracked.targets.set(checkpoint.targets)
                                tracked.confirmationRequested.set(true)
                            }
                        }
                        if (ControlAppliedEvidence.hasOpaqueOwn(read, command)) return@decision negative(
                            ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.UninterpretableMetadata, read))
                        var onlyConfirm = confirmOnly
                        if (own != null) {
                            if (!ControlAppliedEvidence.matches(command, body, tracked, own)) return@decision negative(
                                ControlStoreResult.Conflict(command, emptySet(), emptySet(), ConflictReason.CommandEvidenceMismatch,
                                    TargetExpectation(command, tracked.targets.get().map { it?.id }), read))
                            onlyConfirm = true
                        } else if (tracked.observedApplied.get()) {
                            return@decision negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(),
                                RecoveryReason.CommandEvidenceLost, read))
                        } else if (tracked.confirmed.get()) {
                            onlyConfirm = true
                        } else if (!confirmOnly && tracked.firstConfirmDiscontinuityCount?.let {
                                it != tracking.evidenceDiscontinuityCount
                            } == true) {
                            return@decision negative(ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(),
                                RecoveryReason.CommandEvidenceContinuityLost, read))
                        }
                        val decision = if (lifecycle != null) {
                            ControlLifecycleConfirmation(codec).decide(command, lifecycle, read, context,
                                onlyConfirm, tracked.confirmed.get())
                        } else if (handover is RetiredNamespaceSettlement) {
                            RetiredNamespaceSettlementTransition(codec).decide(command, body, handover, read, context,
                                onlyConfirm, tracked.confirmed.get())
                        } else if (handover is CurrentNullSettlement) {
                            CurrentNullSettlementTransition(codec).decide(command, body, handover, read, context,
                                onlyConfirm, tracked.confirmed.get())
                        } else if (handover is RetiredNullSettlement) {
                            RetiredNullSettlementTransition(codec).decide(command, body, handover, read, context,
                                onlyConfirm, tracked.confirmed.get())
                        } else if (rotation != null) {
                            NamespaceSettlementTransition(codec).decide(command, body, rotation.input, read, context,
                                onlyConfirm, tracked.confirmed.get())
                        } else decide(command, actions, tracked, read, onlyConfirm)
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
                val diagnostic = ControlLifecycleDiagnostics.observe(command, body, tracked, read,
                    (decision.value as? Outcome.Negative)?.result)
                if (diagnostic != null) command.observeLifecycleDiagnostic(diagnostic)
                when (val outcome = decision.value) {
                    is Outcome.Negative -> negative(ControlLifecycleDiagnostics.attach(outcome.result, diagnostic))
                    is Outcome.Positive -> decision
                }
            }
            return when (val outcome = transaction.value) {
                is Outcome.Positive -> {
                    val confirmedSnapshot = ConfirmedControlSnapshot(reader.read(transaction.snapshot) as ControlRecordRead.Supported)
                    if (ControlAppliedEvidence.own(confirmedSnapshot.record, command) != null) tracked.observedApplied.set(true)
                    val diagnostic = ControlLifecycleDiagnostics.observe(command, body, tracked, confirmedSnapshot.record,
                        result = null, storageConfirmed = true)
                    if (diagnostic != null) command.observeLifecycleDiagnostic(diagnostic)
                    tracked.confirmed.set(true)
                    tracking.resolve(command)
                    val work = tracking.recoverySnapshot()
                    ControlStoreResult.Confirmed(
                        command, work.unresolvedCommands, work.pendingReleases, outcome.effect, outcome.ids,
                        confirmedSnapshot,
                        ConfirmationProof(transaction.evidence), outcome.receipt, diagnostic
                    )
                }
                is Outcome.Negative -> {
                    if (!wasUnresolved && outcome.result !is ControlStoreResult.Unconfirmed) tracking.resolve(command)
                    outcome.result.withRecoveryWork(tracking.recoverySnapshot())
                }
            }
        } catch (cancelled: CancellationException) {
            // Targets are published before requesting persistence, even if this caller never returns.
            throw cancelled
        } catch (failure: IOException) {
            val work = tracking.recoverySnapshot()
            val result = ControlStoreResult.Unconfirmed(command, work.unresolvedCommands, work.pendingReleases, UnconfirmedReason.StorageFailure,
                phase.get(), observation.get(), failure)
            // No invented snapshot on I/O failure; retain only the last actual observation.
            return ControlLifecycleDiagnostics.attach(result, command.lastLifecycleDiagnostic)
        }
    }

    private fun terminalResult(command: CommandRef): ControlStoreResult? {
        val state = command.lifecycleState
        if (state == ControlCommandLifecycle.RETAINED) return null
        val work = tracking.recoverySnapshot()
        return when (state) {
            ControlCommandLifecycle.RELEASE_PENDING -> ControlStoreResult.ReleasePending(
                command, work.unresolvedCommands, work.pendingReleases)
            ControlCommandLifecycle.RELEASED -> ControlStoreResult.Released(
                command, work.unresolvedCommands, work.pendingReleases)
            ControlCommandLifecycle.TERMINATION_PENDING -> ControlStoreResult.TerminationPending(
                command, work.unresolvedCommands, work.pendingReleases)
            ControlCommandLifecycle.TERMINATED -> ControlStoreResult.Terminated(
                command, work.unresolvedCommands, work.pendingReleases)
            ControlCommandLifecycle.RETAINED -> error("retained command passed terminal gate")
        }
    }

    private fun decide(
        command: CommandRef,
        actions: List<ControlMutation>,
        tracked: TrackedControlCommand,
        read: ControlRecordRead.Supported,
        confirmOnly: Boolean
    ): RecordTransactionDecision<Outcome> {
        fun reject(detail: String) = negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(),
            RejectionReason.InvalidRequest(detail), read))
        fun conflict(reason: ConflictReason) = negative(ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason,
            TargetExpectation(command, Collections.unmodifiableList(tracked.targets.get().map { it?.id })), read))

        if (actions.isEmpty()) return reject("at least one operation is required")
        val arrays = read.arrays.mapValues { (_, array) -> array.entries.map { it.originalEntry() }.toMutableList() }
        val changedKinds = mutableSetOf<ControlKind>()
        val effectiveIds = mutableListOf<String>()
        val requestedSealKeys = mutableSetOf<SealKey>()
        val targets = tracked.targets.get().toMutableList()
        val writtenIndices = mutableSetOf<Int>()
        var joined = false
        val mayConfirmPostcondition = tracked.confirmationRequested.get()

        for ((actionIndex, action) in actions.withIndex()) {
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
                                command, emptySet(), emptySet(), RecoveryReason.UnreadableEpochState, read))
                            if (!read.original.validType<String>(epochKey)) return negative(ControlStoreResult.RecoveryRequired(
                                command, emptySet(), emptySet(), RecoveryReason.UnreadableEpochState, read))
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
            ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.ControlSchemaMigrationRequired, read))
        if (changedKinds.isNotEmpty() && read.hasUninterpretableMetadata) return negative(
            ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.UninterpretableMetadata, read))
        val candidate = read.original.toMutablePreferences()
        for (kind in changedKinds) {
            // The codec's documented envelope precondition is an invalid candidate, not an I/O attempt.
            val encoded = try {
                codec.encode(arrays.getValue(kind))
            } catch (_: IllegalArgumentException) {
                return reject("candidate violates codec envelope constraints")
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(),
                    RejectionReason.TooLarge(ControlPayloadKey.forKind(kind), encoded.bytes, encoded.limit), read))
                is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(kind)] = encoded.text
            }
        }
        if (changedKinds.isNotEmpty()) {
            val evidence = AppliedEvidence.Mutations(command.id, command.ownerTrackingLifetimeId.value,
                targets.mapIndexed { index, target ->
                    AppliedTarget(index, actions[index].kind, checkNotNull(target).id,
                        target.joined, index in writtenIndices)
                })
            ControlAppliedEvidence.append(candidate, read, evidence, codec)?.let {
                return negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), it, read))
            }
        }
        val complete = reader.read(candidate) as? ControlRecordRead.Supported
            ?: return reject("the complete candidate must remain a supported record")
        candidateRejection(actions, complete, effectiveIds, targets)?.let { return reject(it.detail) }
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
    ): RejectionReason.InvalidRequest? = candidateRejection(command.actions, complete, effectiveIds, targets)

    private fun candidateRejection(
        actions: List<ControlMutation>,
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
            if (matches.single().first != actions[index].kind ||
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

    private fun matchesPreparedActions(actions: List<ControlMutation>, checkpoint: ControlCommandCheckpoint): Boolean =
        actions.zip(checkpoint.targets).all { (action, supplied) ->
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
        data class Positive(val effect: ConfirmedEffect, val ids: List<String>, val receipt: ControlSettlementReceipt? = null) : Outcome {
            val settlement: SettlementReceipt? = receipt as? SettlementReceipt
            val handoverSettlement: HandoverSettlementReceipt? = receipt as? HandoverSettlementReceipt
        }
        data class Negative(val result: ControlStoreResult) : Outcome
    }

    private fun negative(result: ControlStoreResult) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(result))
    private fun historyUnavailable(command: CommandRef, read: ControlRecordRead) = negative(
        ControlStoreResult.Unconfirmed(command, emptySet(), emptySet(), UnconfirmedReason.HistoryUnavailable,
            ControlAttemptPhase.PreparingCandidate, read))

    private fun ControlStoreResult.withRecoveryWork(work: LocalRecoveryWork): ControlStoreResult = when (this) {
        is ControlStoreResult.Rejected -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlStoreResult.Conflict -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlStoreResult.RecoveryRequired -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlStoreResult.Unconfirmed -> copy(localUnresolvedCommands = work.unresolvedCommands, localPendingReleases = work.pendingReleases)
        is ControlStoreResult.Confirmed -> error("positive results are published after storage confirmation")
        is ControlStoreResult.ReleasePending, is ControlStoreResult.Released,
        is ControlStoreResult.TerminationPending, is ControlStoreResult.Terminated ->
            error("terminal results cannot be storage outcomes")
    }

    private fun ControlEntryRead.originalEntry(): PayloadEntry = when (this) {
        is ControlEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEntryRead.Uninterpretable -> original
    }

    private fun same(left: ControlNode, right: ControlNode): Boolean = left.toPayloadEntry() == right.toPayloadEntry()

}
