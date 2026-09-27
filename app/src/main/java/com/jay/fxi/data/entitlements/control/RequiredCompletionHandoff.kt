package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import java.util.Collections

internal class CompletionHandoff(
    val command: ExactCommandBinding,
    val responsibilityOwner: ResponsibilityOwner,
    slots: List<SlotHandoff>
) {
    val slots: List<SlotHandoff> =
        Collections.unmodifiableList(slots.toList())
}

internal data class ResponsibilityOwner(
    val trackingLifetime: OwnerTrackingLifetimeId,
    val ownerKey: String
)

internal data class SlotHandoff(
    val key: RequiredObligationKey,
    val disposition: HandoffDisposition
)

internal sealed interface DestinationLocator {
    data class Payload(val kind: ControlKind, val id: String) : DestinationLocator
    data class Journal(val key: JournalTargetV1) : DestinationLocator
    data class Guard(val id: String, val part: GuardPart) : DestinationLocator
}

internal enum class GuardPart { FLOOR, AUTH }

internal sealed interface HandoffDisposition {
    class DurablyOwned(
        val destination: DestinationLocator,
        linkChain: List<NamedTransferLink>,
        val priorWrite: PriorStorageConfirmation?
    ) : HandoffDisposition {
        val linkChain: List<NamedTransferLink> =
            Collections.unmodifiableList(linkChain.toList())
    }

    class CompletedAndConsumed(
        val completion: ComponentCompletion,
        consumedReceipts: List<ReceiptIdentity>
    ) : HandoffDisposition {
        val consumedReceipts: List<ReceiptIdentity> =
            Collections.unmodifiableList(consumedReceipts.toList())
    }
}

internal data class ComponentCompletion(
    val sourceSubject: ObligationSubject
)

internal sealed interface ReceiptIdentity {
    data class Lifecycle(
        val commandId: String,
        val transition: LifecycleTransition,
        val queryId: String?,
        val target: LifecycleTarget?
    ) : ReceiptIdentity

    data class Rotation(val operationId: String) : ReceiptIdentity

    data class Settlement(
        val operationId: String,
        val transition: HandoverSettlementTransition
    ) : ReceiptIdentity
}

/* A3가 필드와 검증 발급 경로를 정한다. A2에는 생성 경로가 없다. */
internal class PriorStorageConfirmation private constructor()
internal class NamedTransferLink private constructor()

internal enum class G05Id(val wire: String) {
    REF("G05.ref"),
    REQUIREMENTS("G05.requirements"),
    DUPLICATE("G05.duplicate"),
    COVERAGE_L("G05.coverageL"),
    COVERAGE_N("G05.coverageN"),
    EXTRA("G05.extra"),
    SUBJECT("G05.subject"),
    DESTINATION("G05.destination"),
    CONFIRMATION("G05.confirmation"),
    LOWER_BOUND("G05.lowerBound"),
    COMPLETED_CONFLICT("G05.completedConflict"),
    OWNER("G05.owner")
}

internal sealed interface G05Location {
    data object Command : G05Location
    data object Closure : G05Location
    data class Fixed(val location: FixedInputLocation) : G05Location
    data class Submitted(val index: Int) : G05Location
    data class ActualPayload(val kind: ControlKind, val index: Int) : G05Location
    data class ActualJournal(val index: Int) : G05Location
    data object ActualMetadata : G05Location
}

internal data class G05Failure(
    val id: G05Id,
    val key: RequiredObligationKey?,
    val expectedAt: G05Location?,
    val submittedAt: G05Location?,
    val actualAt: G05Location?
)

internal sealed interface G05Result {
    data object Accepted : G05Result

    class Rejected(failures: List<G05Failure>) : G05Result {
        val failures: List<G05Failure> =
            Collections.unmodifiableList(failures.toList())
    }
}

internal fun assessG05(
    exactCommand: CommandRef,
    derivation: RequirementDerivation,
    handoff: CompletionHandoff,
    closure: TerminationClosure,
    latest: ControlRecordRead,
    now: BootReading
): G05Result {
    val failures = mutableListOf<G05Failure>()
    val binding = (derivation as? RequirementDerivation.Available)?.commandBinding
    val currentBody = exactCommand.captureStateAndBody().body
    val refValid = handoff.command.ref === exactCommand &&
        handoff.command.body === currentBody &&
        (binding == null || binding == handoff.command)
    if (!refValid) {
        failures += G05Failure(G05Id.REF, null, G05Location.Command, null, null)
        return G05Result.Rejected(failures)
    }

    if (derivation is RequirementDerivation.Unavailable) {
        val id = if (derivation.reason == RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY)
            G05Id.DUPLICATE else G05Id.REQUIREMENTS
        failures += G05Failure(id, null, G05Location.Fixed(derivation.location), null, null)
    } else {
        val available = derivation as RequirementDerivation.Available
        val required = available.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val byKey = available.orderedSlots.associateBy { it.key }
        val submitted = handoff.slots.withIndex().groupBy { it.value.key }
        fun fixed(slot: RequiredSlot): G05Location.Fixed = G05Location.Fixed(
            (slot.requirement as SlotRequirement.Required).fixedSources.first().location)
        fun failure(id: G05Id, slot: RequiredSlot, index: Int?, actual: G05Location? = null) =
            G05Failure(id, slot.key, fixed(slot), index?.let(G05Location::Submitted), actual)

        // The existing coverage checker owns the structural policy. Granular failures below retain
        // the A1 and submission positions that its aggregate result intentionally does not expose.
        val coverage = checkSlotCoverage(available.orderedSlots.map { ExpectedSlot(it.key, it.necessity) },
            handoff.slots.map { SubmittedSlot(it.key, when (it.disposition) {
                is HandoffDisposition.DurablyOwned -> ObligationDisposition.DurablyOwned
                is HandoffDisposition.CompletedAndConsumed -> ObligationDisposition.CompletedAndConsumed
            }) })
        if (coverage is CoverageResult.Rejected) {
            val seen = mutableSetOf<RequiredObligationKey>()
            for ((index, item) in handoff.slots.withIndex()) {
                if (!seen.add(item.key)) {
                    val requirement = byKey[item.key]?.requirement as? SlotRequirement.Required
                    failures += G05Failure(G05Id.DUPLICATE, item.key,
                        requirement?.fixedSources?.firstOrNull()?.location?.let(G05Location::Fixed),
                        G05Location.Submitted(index), null)
                }
            }
            for (slot in required) {
                val matches = submitted[slot.key].orEmpty()
                if (matches.isEmpty()) failures += failure(
                    if (slot.key.branch == LandingBranch.L) G05Id.COVERAGE_L else G05Id.COVERAGE_N, slot, null)
            }
            for ((index, item) in handoff.slots.withIndex()) {
                if (byKey[item.key]?.requirement !is SlotRequirement.Required)
                    failures += G05Failure(G05Id.EXTRA, item.key, null, G05Location.Submitted(index), null)
            }
        }

        for (slot in required) {
            for (entry in submitted[slot.key].orEmpty()) {
                val item = entry.value
                val completion = item.disposition as? HandoffDisposition.CompletedAndConsumed ?: continue
                if (completion.completion.sourceSubject != slot.key.subject)
                    failures += failure(G05Id.SUBJECT, slot, entry.index)
            }
        }

        for (slot in required) {
            for (entry in submitted[slot.key].orEmpty()) {
                val owned = entry.value.disposition as? HandoffDisposition.DurablyOwned ?: continue
                val requirement = slot.requirement as SlotRequirement.Required
                val destination = inspectDestination(owned.destination, requirement, latest, now)
                if (!destination.exists)
                    failures += failure(G05Id.DESTINATION, slot, entry.index, destination.actualAt)
                // A2 has no issuer for PriorStorageConfirmation. A snapshot is not confirmation.
                failures += failure(G05Id.CONFIRMATION, slot, entry.index)
                if (destination.exists && destination.lowerBound != true)
                    failures += failure(G05Id.LOWER_BOUND, slot, entry.index, destination.actualAt)
            }
        }

        for (slot in required) {
            val requirement = slot.requirement as SlotRequirement.Required
            for (entry in submitted[slot.key].orEmpty()) {
                if (entry.value.disposition !is HandoffDisposition.CompletedAndConsumed) continue
                if (requirement.allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY)
                    failures += failure(G05Id.COMPLETED_CONFLICT, slot, entry.index)
                for (actual in completionConflicts(requirement.lowerBound, slot.key, latest, available.orderedSlots))
                    failures += failure(G05Id.COMPLETED_CONFLICT, slot, entry.index, actual)
            }
        }
    }

    if (handoff.responsibilityOwner.trackingLifetime !== exactCommand.ownerTrackingLifetimeId)
        failures += G05Failure(G05Id.OWNER, null, G05Location.Command, null, null)
    if (handoff.responsibilityOwner.ownerKey.isBlank())
        failures += G05Failure(G05Id.OWNER, null, null, null, null)
    if (closure.command !== exactCommand || closure.ownerTrackingLifetimeId !== exactCommand.ownerTrackingLifetimeId ||
        closure.owner != handoff.responsibilityOwner.ownerKey)
        failures += G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)

    if (failures.isEmpty()) return G05Result.Accepted
    val positions = (derivation as? RequirementDerivation.Available)?.orderedSlots
        ?.mapIndexed { index, slot -> slot.key to index }?.toMap().orEmpty()
    return G05Result.Rejected(failures.sortedWith(compareBy<G05Failure> { it.id.ordinal }
        .thenBy { positions[it.key] ?: Int.MAX_VALUE }
        .thenBy { (it.submittedAt as? G05Location.Submitted)?.index ?: Int.MAX_VALUE }
        .thenBy { if (it.id == G05Id.COMPLETED_CONFLICT && it.actualAt == null) 0 else 1 }))
}

private data class DestinationFinding(
    val actualAt: G05Location?,
    val exists: Boolean,
    val lowerBound: Boolean? = null
)

private fun originalAuthGuardId(requirement: SlotRequirement.Required): String? {
    val bound = requirement.lowerBound as? RequiredLowerBound.Auth ?: return null
    return requirement.fixedSources.mapNotNull { (it.fact as? FixedSourceFact.Node)?.let { source ->
        if (source.kind != ControlKind.DEMAND) return@mapNotNull null
        val guard = (ControlObligations.read(source.kind, source.value) as? ControlEntryRead.Interpreted)
            ?.value as? ScheduleGuardV1 ?: return@mapNotNull null
        if (guard.auth?.let { compareAuth(bound.required, it) != TypedComparison.Mismatch(AuthField.SCOPE) } == true)
            guard.id else null
    } }.distinct().singleOrNull()
}

private fun inspectDestination(locator: DestinationLocator, requirement: SlotRequirement.Required,
    latest: ControlRecordRead, now: BootReading): DestinationFinding {
    val read = latest as? ControlRecordRead.Supported ?: return DestinationFinding(null, false)
    val bound = requirement.lowerBound
    return when (locator) {
        is DestinationLocator.Payload, is DestinationLocator.Guard -> {
            val id = when (locator) {
                is DestinationLocator.Payload -> locator.id
                is DestinationLocator.Guard -> locator.id
                else -> error("Unreachable destination locator")
            }
            val found = read.locations(id).singleOrNull() ?: return DestinationFinding(null, false)
            val (kind, entry) = found
            val index = read.arrays.getValue(kind).entries.indexOfFirst { it === entry }
            val at = G05Location.ActualPayload(kind, index)
            val value = (entry as? ControlEntryRead.Interpreted)?.value
            when (locator) {
                is DestinationLocator.Payload -> {
                    val expected = when (bound) {
                        is RequiredLowerBound.Request -> ControlKind.DEMAND to bound.requiredId
                        is RequiredLowerBound.Seal -> ControlKind.SEAL to bound.source.id
                        is RequiredLowerBound.Hold -> ControlKind.HOLD to bound.source.id
                        is RequiredLowerBound.Intent -> ControlKind.RECOVERY_INTENT to bound.source.id
                        is RequiredLowerBound.ExactSource -> bound.kind to bound.id
                        is RequiredLowerBound.Floor -> bound.sourceKind to bound.sourceId
                        is RequiredLowerBound.DecisionEffect -> {
                            val effect = bound.required
                            val source = (ControlObligations.read(effect.kind, effect.node) as? ControlEntryRead.Interpreted)?.value
                            source?.let { effect.kind to it.id }
                        }
                        else -> null
                    }
                    val exists = expected == (kind to id) && kind == locator.kind && value != null
                    val comparison = if (!exists) null else when (bound) {
                        is RequiredLowerBound.Seal -> compareSeal(
                            bound.source.copy(settlement = bound.requiredSettlement), value as SealV1) == TypedComparison.Matches
                        is RequiredLowerBound.Hold -> compareHold(bound.source, value as RestoredHold) == TypedComparison.Matches
                        is RequiredLowerBound.Intent -> compareRecoveryIntent(bound.source, value as RecoveryIntentV1) == TypedComparison.Matches
                        is RequiredLowerBound.Floor -> {
                            val floor = (value as? RestoredHold)?.floor
                            val minimum = bound.captured.remainingAt(now)
                            minimum != null && compareFloor(minimum, floor, now) == TypedComparison.Matches
                        }
                        is RequiredLowerBound.Request -> {
                            val source = bound.source ?: DemandV1(bound.requiredId, bound.ownerUid,
                                bound.binding, bound.minimumIntent, bound.minimumOrder)
                            compareRequest(RequestNeed(source, bound.minimumIntent, bound.minimumOrder), value as DemandV1) ==
                                TypedComparison.Matches
                        }
                        is RequiredLowerBound.ExactSource ->
                            (entry as ControlEntryRead.Interpreted).original.toPayloadEntry() == bound.node.toPayloadEntry()
                        is RequiredLowerBound.DecisionEffect ->
                            (entry as ControlEntryRead.Interpreted).original.toPayloadEntry() == bound.required.node.toPayloadEntry()
                        else -> false
                    }
                    DestinationFinding(at, exists, comparison)
                }
                is DestinationLocator.Guard -> {
                    val guard = value as? ScheduleGuardV1
                    val exists = kind == ControlKind.DEMAND && guard != null && when (locator.part) {
                        GuardPart.FLOOR -> bound is RequiredLowerBound.Floor && bound.sourceKind == ControlKind.DEMAND &&
                            locator.id == bound.sourceId && guard.floor != null
                        GuardPart.AUTH -> bound is RequiredLowerBound.Auth &&
                            originalAuthGuardId(requirement) == locator.id && guard.auth?.let {
                                compareAuth(bound.required, it) != TypedComparison.Mismatch(AuthField.SCOPE)
                            } == true
                    }
                    val comparison = if (!exists) null else when (bound) {
                        is RequiredLowerBound.Floor -> bound.captured.remainingAt(now)?.let {
                            compareFloor(it, guard?.floor, now) == TypedComparison.Matches
                        } ?: false
                        is RequiredLowerBound.Auth -> compareAuth(bound.required, checkNotNull(guard?.auth)) ==
                            TypedComparison.Matches
                        else -> false
                    }
                    DestinationFinding(at, exists, comparison)
                }
                else -> error("Unreachable destination locator")
            }
        }
        is DestinationLocator.Journal -> {
            val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                ?: return DestinationFinding(null, false)
            val index = canonical.indexOfFirst { compareJournal(locator.key, listOf(it)) == TypedComparison.Matches }
            if (index < 0) return DestinationFinding(null, false)
            val at = G05Location.ActualJournal(index)
            val expected = when (bound) {
                is RequiredLowerBound.Journal -> bound.key
                is RequiredLowerBound.NamespaceRetirement -> bound.scope.journalKey
                else -> null
            }
            val exists = expected == locator.key
            DestinationFinding(at, exists, if (exists) true else null)
        }
    }
}

private fun completionConflicts(
    bound: RequiredLowerBound,
    key: RequiredObligationKey,
    latest: ControlRecordRead,
    allSlots: List<RequiredSlot>
): List<G05Location> {
    if (latest !is ControlRecordRead.Supported) return listOf(G05Location.ActualMetadata)
    val result = linkedSetOf<G05Location>()

    fun rows(kind: ControlKind, matching: (ControlObligationV1) -> Boolean) {
        latest.arrays.getValue(kind).entries.forEachIndexed { index, entry ->
            val at = G05Location.ActualPayload(kind, index)
            when (entry) {
                is ControlEntryRead.Interpreted -> if (matching(entry.value)) result += at
                is ControlEntryRead.Uninterpretable -> result += at // Its id or scope may be unreadable.
            }
        }
    }
    fun sameId(kind: ControlKind, id: String) = rows(kind) { it.id == id }
    fun journal(target: JournalTargetV1) {
        val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(latest.original)
        if (canonical == null) {
            result += G05Location.ActualMetadata
        } else {
            canonical.forEachIndexed { index, entry ->
                if (compareJournal(target, listOf(entry)) != TypedComparison.Mismatch(JournalField.ABSENT))
                    result += G05Location.ActualJournal(index)
            }
        }
    }
    fun fence(before: FenceV1, after: FenceV1) {
        val raw = latest.original
        if (!raw.validType<String>(OWNER_UID) || !raw.validType<String>(USER_EPOCH) ||
            !raw.validType<String>(KRX_EPOCH)) {
            result += G05Location.ActualMetadata
            return
        }
        val current = FenceV1(raw[OWNER_UID], raw[USER_EPOCH], raw[KRX_EPOCH])
        if ((before.ownerUid != after.ownerUid && current.ownerUid == before.ownerUid) ||
            (before.userAccessEpoch != after.userAccessEpoch && current.userAccessEpoch == before.userAccessEpoch) ||
            (before.krxCapabilityEpoch != after.krxCapabilityEpoch && current.krxCapabilityEpoch == before.krxCapabilityEpoch))
            result += G05Location.ActualMetadata
    }
    fun seal(source: SealV1, expected: SettlementEvidence?) {
        rows(ControlKind.SEAL) { value ->
            val actual = value as SealV1
            actual.id == source.id && (actual.kind != source.kind || actual.key != source.key ||
                actual.settlement == null || (expected != null && actual.settlement != expected))
        }
    }
    fun guard(id: String) {
        rows(ControlKind.DEMAND) { value ->
            value.id == id && (value !is ScheduleGuardV1 || value.floor != null || value.auth != null)
        }
    }
    fun auth(required: AuthSnapshotV1) {
        rows(ControlKind.DEMAND) { value ->
            val actual = (value as? ScheduleGuardV1)?.auth
            actual != null && actual.ownerUid == required.ownerUid && actual.binding == required.binding &&
                actual.originLifetimeId == required.originLifetimeId && actual.authGeneration == required.authGeneration
        }
    }

    when (bound) {
        is RequiredLowerBound.Request -> sameId(ControlKind.DEMAND, bound.requiredId)
        is RequiredLowerBound.Journal -> journal(bound.key)
        is RequiredLowerBound.Hold -> sameId(ControlKind.HOLD, bound.source.id)
        is RequiredLowerBound.Intent -> sameId(ControlKind.RECOVERY_INTENT, bound.source.id)
        is RequiredLowerBound.Seal -> seal(bound.source, bound.requiredSettlement)
        is RequiredLowerBound.ExactSource -> {
            require(bound.kind == ControlKind.DEMAND)
            guard(bound.id)
        }
        is RequiredLowerBound.Floor -> rows(bound.sourceKind) { false }
        is RequiredLowerBound.Auth -> auth(bound.required)
        is RequiredLowerBound.DecisionEffect -> {
            val effect = bound.required
            when (val value = (ControlObligations.read(effect.kind, effect.node) as? ControlEntryRead.Interpreted)?.value) {
                is DemandV1 -> sameId(ControlKind.DEMAND, value.id)
                is ScheduleGuardV1 -> guard(value.id)
                is RestoredHold -> sameId(ControlKind.HOLD, value.id)
                is RecoveryIntentV1 -> sameId(ControlKind.RECOVERY_INTENT, value.id)
                is SealV1 -> seal(value, null)
                else -> rows(effect.kind) { false }
            }
        }
        is RequiredLowerBound.DecisionNamespace -> fence(bound.before, bound.after)
        is RequiredLowerBound.NamespaceRetirement -> {
            val scope = bound.scope
            if (scope.sourceId != null) {
                val sourceKind = allSlots.asSequence().mapNotNull { (it.requirement as? SlotRequirement.Required)?.lowerBound }
                    .firstNotNullOfOrNull { candidate -> when (candidate) {
                        is RequiredLowerBound.Hold -> ControlKind.HOLD.takeIf { candidate.source.id == scope.sourceId }
                        is RequiredLowerBound.Intent -> ControlKind.RECOVERY_INTENT.takeIf { candidate.source.id == scope.sourceId }
                        is RequiredLowerBound.Seal -> ControlKind.SEAL.takeIf { candidate.source.id == scope.sourceId }
                        else -> null
                    } }
                sameId(checkNotNull(sourceKind), scope.sourceId)
            }
            journal(scope.journalKey)
            fence(scope.before, scope.after)
        }
        is RequiredLowerBound.Query, is RequiredLowerBound.Binding, is RequiredLowerBound.Receipt -> Unit
    }
    return result.toList()
}
