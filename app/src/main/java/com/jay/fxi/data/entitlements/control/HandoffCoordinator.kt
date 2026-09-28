package com.jay.fxi.data.entitlements.control

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class HandoffEntryKind {
    CALLBACK, QUERY, TOPIC, RETRY, RECEIPT, PREPARE, ADOPTION, CONFIRM
}

/** The synchronous boundary that closes a real entry source; false leaves that kind open. */
internal fun interface HandoffEntryCloser {
    fun close(kind: HandoffEntryKind): Boolean
}

/** Every event names the exact command (identity) and the responsibility owner it belongs to. */
internal data class HandoffEventBinding(
    val command: CommandRef,
    val responsibilityOwner: ResponsibilityOwner
)

internal enum class HandoffIssueRefusal {
    ENTRIES_OPEN,
    WORK_NOT_JOINED,
    GENERATION_CHANGED,
    REGISTRATION_CHANGED,
    TRANSFER_NOT_CONFIRMED,
    COMPLETION_NOT_CONSUMED,
    RECEIPTS_OPEN,
    OWNER_OR_COMMAND_MISMATCH,
    INCOMPLETE_OR_INVALID_SLOTS,
    REQUIREMENTS_UNAVAILABLE,
    ALREADY_ISSUED
}

internal sealed interface HandoffIssueResult {
    data class Issued(
        val closure: TerminationClosure,
        val handoff: CompletionHandoff
    ) : HandoffIssueResult

    data class Refused(val reason: HandoffIssueRefusal) : HandoffIssueResult
}

internal enum class RegistrationRefusal {
    ENTRIES_CLOSED,
    DUPLICATE_WORK,
    OWNER_OR_COMMAND_MISMATCH
}

internal sealed interface RegistrationResult {
    data object Registered : RegistrationResult
    data class Rejected(val reason: RegistrationRefusal) : RegistrationResult
}

internal enum class RecordRefusal {
    OWNER_OR_COMMAND_MISMATCH,
    UNKNOWN_WORK,
    ENTRIES_CLOSED,
    DUPLICATE_OR_CONFLICTING_EVENT,
    INVALID_CONFIRMATION,
    UNEXPECTED_RECEIPT,
    ALREADY_ISSUED
}

internal sealed interface RecordResult {
    data object Recorded : RecordResult
    data class Rejected(val reason: RecordRefusal) : RecordResult
}

internal class HandoffCoordinator(
    private val command: CommandRef,
    private val owner: ResponsibilityOwner,
    private val entryCloser: HandoffEntryCloser,
    private val joinTimeoutMillis: Long,
    private val evidenceTimeoutMillis: Long = joinTimeoutMillis
) {
    init {
        require(joinTimeoutMillis > 0)
        require(evidenceTimeoutMillis > 0)
    }

    private val lock = Any()
    private val work = linkedMapOf<String, Job>()
    private val startedTransfers = mutableSetOf<RequiredObligationKey>()
    private val confirmedTransfers = linkedMapOf<RequiredObligationKey, HandoffDisposition.DurablyOwned>()
    private val completions = linkedMapOf<RequiredObligationKey, ComponentCompletion>()
    private val consumedCompletions = mutableSetOf<RequiredObligationKey>()
    private val expectedReceipts = linkedMapOf<ReceiptIdentity, RequiredObligationKey?>()
    private val consumedReceipts = mutableSetOf<ReceiptIdentity>()
    private val changes = MutableStateFlow(0L)
    private var generation = 0L
    private var registrationClosed = false
    private var entryClosureComplete = false
    private var firstCapture: Capture? = null
    private var issued = false

    private fun matches(binding: HandoffEventBinding): Boolean =
        binding.command === command && binding.responsibilityOwner == owner &&
            owner.trackingLifetime === command.ownerTrackingLifetimeId && owner.ownerKey.isNotBlank()

    private fun changed() { changes.value = changes.value + 1 }

    private fun record(binding: HandoffEventBinding, sourceWorkId: String? = null,
        action: () -> RecordResult): RecordResult = synchronized(lock) {
        when {
            !matches(binding) -> RecordResult.Rejected(RecordRefusal.OWNER_OR_COMMAND_MISMATCH)
            issued -> RecordResult.Rejected(RecordRefusal.ALREADY_ISSUED)
            sourceWorkId != null && sourceWorkId !in work -> RecordResult.Rejected(RecordRefusal.UNKNOWN_WORK)
            else -> action()
        }
    }

    fun registerWork(binding: HandoffEventBinding, kind: HandoffEntryKind, id: String, job: Job): RegistrationResult =
        synchronized(lock) {
            when {
                !matches(binding) -> RegistrationResult.Rejected(RegistrationRefusal.OWNER_OR_COMMAND_MISMATCH)
                registrationClosed -> RegistrationResult.Rejected(RegistrationRefusal.ENTRIES_CLOSED)
                id.isBlank() || id in work -> RegistrationResult.Rejected(RegistrationRefusal.DUPLICATE_WORK)
                else -> {
                    // All kinds share the same closure and capture boundary.
                    work[id] = job
                    changed()
                    RegistrationResult.Registered
                }
            }
        }

    fun unregisterWork(binding: HandoffEventBinding, id: String): RecordResult = record(binding) {
        if (work.remove(id) == null) RecordResult.Rejected(RecordRefusal.UNKNOWN_WORK)
        else { changed(); RecordResult.Recorded }
    }

    fun advanceGeneration(binding: HandoffEventBinding): RecordResult = record(binding) {
        generation++
        changed()
        RecordResult.Recorded
    }

    fun recordTransferStarted(binding: HandoffEventBinding, slot: RequiredObligationKey,
        sourceWorkId: String? = null): RecordResult = record(binding, sourceWorkId) {
        if (slot in startedTransfers || slot in confirmedTransfers || slot in completions)
            RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        else {
            startedTransfers += slot
            changed()
            RecordResult.Recorded
        }
    }

    fun recordConfirmedTransfer(binding: HandoffEventBinding, slot: RequiredObligationKey,
        disposition: HandoffDisposition.DurablyOwned, sourceWorkId: String? = null): RecordResult =
        record(binding, sourceWorkId) {
            val confirmation = disposition.priorWrite?.binding
            when {
                slot in confirmedTransfers || slot in completions ->
                    RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
                confirmation == null || when (confirmation) {
                    is ConfirmationBinding.RetainedSource ->
                        confirmation.slot.key != slot || confirmation.observed.locator != disposition.destination
                    is ConfirmationBinding.LifecycleOutput -> confirmation.command !== command
                    is ConfirmationBinding.MutationFloorOutput -> true
                } -> RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION)
                disposition.linkChain.isNotEmpty() &&
                    (disposition.linkChain.last().confirmation !== disposition.priorWrite ||
                        disposition.linkChain.last().destination.locator != disposition.destination) ->
                    RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION)
                else -> {
                    confirmedTransfers[slot] = disposition
                    changed()
                    RecordResult.Recorded
                }
            }
        }

    /** An old Mutations FLOOR slot transferred through a HoldFloor or GuardFloor first link and a GuardFloor destination. */
    fun recordConfirmedMutationGuardTransfer(binding: HandoffEventBinding, slot: RequiredSlot,
        disposition: HandoffDisposition.DurablyOwned, sourceWorkId: String? = null): RecordResult =
        record(binding, sourceWorkId) {
            when {
                slot.key in confirmedTransfers || slot.key in completions ->
                    RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
                command.captureStateAndBody().body !is ControlCommandBody.Mutations ||
                    !mutationGuardChainValid(slot, disposition, command, owner) ->
                    RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION)
                else -> {
                    confirmedTransfers[slot.key] = disposition
                    changed()
                    RecordResult.Recorded
                }
            }
        }

    fun recordComponentCompleted(binding: HandoffEventBinding, slot: RequiredObligationKey,
        completion: ComponentCompletion, sourceWorkId: String? = null): RecordResult = record(binding, sourceWorkId) {
        when {
            slot in startedTransfers || slot in confirmedTransfers || slot in completions ->
                RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
            completion.sourceSubject != slot.subject -> RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION)
            else -> {
                completions[slot] = completion
                changed()
                RecordResult.Recorded
            }
        }
    }

    fun recordCompletionResultConsumed(binding: HandoffEventBinding, slot: RequiredObligationKey,
        sourceWorkId: String? = null): RecordResult = record(binding, sourceWorkId) {
        if (slot !in completions || slot in consumedCompletions)
            RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
        else {
            consumedCompletions += slot
            changed()
            RecordResult.Recorded
        }
    }

    fun expectReceipt(binding: HandoffEventBinding, receipt: ReceiptIdentity, forSlot: RequiredObligationKey? = null,
        sourceWorkId: String? = null): RecordResult = record(binding, sourceWorkId) {
        val receiptCommand = when (receipt) {
            is ReceiptIdentity.Lifecycle -> receipt.commandId
            is ReceiptIdentity.Rotation -> receipt.operationId
            is ReceiptIdentity.Settlement -> receipt.operationId
        }
        when {
            receiptCommand != command.id -> RecordResult.Rejected(RecordRefusal.UNEXPECTED_RECEIPT)
            receipt in expectedReceipts -> RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
            else -> {
                expectedReceipts[receipt] = forSlot
                changed()
                RecordResult.Recorded
            }
        }
    }

    fun recordReceiptConsumed(binding: HandoffEventBinding, receipt: ReceiptIdentity,
        sourceWorkId: String? = null): RecordResult = record(binding, sourceWorkId) {
        when {
            receipt !in expectedReceipts -> RecordResult.Rejected(RecordRefusal.UNEXPECTED_RECEIPT)
            receipt in consumedReceipts -> RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT)
            else -> {
                consumedReceipts += receipt
                changed()
                RecordResult.Recorded
            }
        }
    }

    private data class Capture(val generation: Long, val work: Map<String, Job>, val entriesClosed: Boolean)

    private fun closeAndCapture(): Capture = synchronized(lock) {
        if (!registrationClosed) {
            registrationClosed = true
            var allClosed = true
            for (kind in HandoffEntryKind.entries) {
                if (!entryCloser.close(kind)) allClosed = false
            }
            entryClosureComplete = allClosed
            firstCapture = Capture(generation, work.toMap(), entryClosureComplete)
        }
        checkNotNull(firstCapture)
    }

    private fun captureRefusal(capture: Capture): HandoffIssueRefusal? = synchronized(lock) {
        when {
            generation != capture.generation -> HandoffIssueRefusal.GENERATION_CHANGED
            work.keys != capture.work.keys -> HandoffIssueRefusal.REGISTRATION_CHANGED
            else -> null
        }
    }

    private suspend fun awaitStartedTransfers(keys: Set<RequiredObligationKey>) {
        withTimeoutOrNull(evidenceTimeoutMillis) {
            var pending = true
            while (pending) {
                val version = synchronized(lock) {
                    pending = startedTransfers.any { it in keys && it !in confirmedTransfers }
                    changes.value
                }
                if (pending) changes.first { it != version }
            }
        }
    }

    suspend fun closeJoinAndIssueHandoff(requirementInput: RequirementInput): HandoffIssueResult {
        val capture = closeAndCapture()
        val joined = withTimeoutOrNull(joinTimeoutMillis) {
            capture.work.values.forEach { it.cancel() }
            capture.work.values.forEach { it.join() }
            true
        } == true
        if (!capture.entriesClosed) return HandoffIssueResult.Refused(HandoffIssueRefusal.ENTRIES_OPEN)
        if (!joined) return HandoffIssueResult.Refused(HandoffIssueRefusal.WORK_NOT_JOINED)
        captureRefusal(capture)?.let { return HandoffIssueResult.Refused(it) }

        if (requirementInput.exactCommand !== command ||
            owner.trackingLifetime !== command.ownerTrackingLifetimeId || owner.ownerKey.isBlank())
            return HandoffIssueResult.Refused(HandoffIssueRefusal.OWNER_OR_COMMAND_MISMATCH)
        val available = deriveRequiredObligations(requirementInput) as? RequirementDerivation.Available
            ?: return HandoffIssueResult.Refused(HandoffIssueRefusal.REQUIREMENTS_UNAVAILABLE)
        val required = available.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val requiredKeys = required.map { it.key }.toSet()
        if (synchronized(lock) { startedTransfers.any { it in requiredKeys && it !in confirmedTransfers } })
            awaitStartedTransfers(requiredKeys)

        return synchronized(lock) {
            fun refuse(reason: HandoffIssueRefusal) = HandoffIssueResult.Refused(reason)
            if (issued) return@synchronized refuse(HandoffIssueRefusal.ALREADY_ISSUED)
            captureRefusal(capture)?.let { return@synchronized refuse(it) }
            if ((startedTransfers + confirmedTransfers.keys + completions.keys).any { it !in requiredKeys })
                return@synchronized refuse(HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)

            val slots = mutableListOf<SlotHandoff>()
            for (slot in required) {
                val key = slot.key
                val rule = slot.requirement as SlotRequirement.Required
                val owned = confirmedTransfers[key]
                val completion = completions[key]
                when {
                    owned != null -> {
                        if (rule.allowed == AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY)
                            return@synchronized refuse(HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
                        val proof = owned.priorWrite?.binding
                        if (proof == null || when (proof) {
                            is ConfirmationBinding.RetainedSource -> proof.slot != slot ||
                                proof.observed.locator != owned.destination
                            is ConfirmationBinding.LifecycleOutput ->
                                if (proof.command === command) false
                                else !mutationGuardChainValid(slot, owned, command, owner)
                            is ConfirmationBinding.MutationFloorOutput ->
                                !mutationGuardChainValid(slot, owned, command, owner)
                        }) return@synchronized refuse(HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
                        slots += SlotHandoff(key, owned)
                    }
                    completion != null -> {
                        if (rule.allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY ||
                            completion.sourceSubject != key.subject)
                            return@synchronized refuse(HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
                        if (key !in consumedCompletions)
                            return@synchronized refuse(HandoffIssueRefusal.COMPLETION_NOT_CONSUMED)
                        val receipts = expectedReceipts.filterValues { it == key }.keys.toList()
                        slots += SlotHandoff(key, HandoffDisposition.CompletedAndConsumed(completion, receipts))
                    }
                    key in startedTransfers -> return@synchronized refuse(HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
                    else -> return@synchronized refuse(HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
                }
            }
            if (expectedReceipts.values.any { it != null && it !in completions })
                return@synchronized refuse(HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
            if (expectedReceipts.keys != consumedReceipts)
                return@synchronized refuse(HandoffIssueRefusal.RECEIPTS_OPEN)

            val captured = capture.work.keys
            val closure = TerminationClosure(command, command.ownerTrackingLifetimeId, command.id,
                capture.generation, generation, true, captured, captured, work.keys,
                true, owner.ownerKey)
            issued = true
            HandoffIssueResult.Issued(closure, CompletionHandoff(available.commandBinding, owner, slots))
        }
    }
}
