package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope

/** In-memory identities for the independently produced L and N obligations. */
internal enum class LandingBranch { L, N }

internal sealed interface ObligationRole {
    data class MutationAction(val index: Int) : ObligationRole
    data object Rotation : ObligationRole
    data class Settlement(val transition: HandoverSettlementTransition) : ObligationRole
    data class Lifecycle(val transition: LifecycleTransition, val role: LifecycleRole) : ObligationRole
}

internal sealed interface ObligationSubject {
    data class Request(val id: String, val ownerUid: String?, val binding: Long,
        val raisedAt: EventOrderV1) : ObligationSubject
    data class Seal(val id: String, val kind: SealTargetKind, val key: SealKey) : ObligationSubject
    data class Hold(val id: String, val origin: LifetimeId, val binding: Long,
        val provenance: HoldProvenanceV1, val axes: Set<PurgeScope>) : ObligationSubject
    data class Intent(val id: String, val sessionId: String, val ownerUid: String?,
        val axis: PurgeScope, val targetEpoch: String?) : ObligationSubject
    data class Journal(val sourceSealId: String, val key: JournalTargetV1) : ObligationSubject
    data class Floor(val sourceKind: ControlKind, val sourceId: String,
        val origin: LifetimeId) : ObligationSubject
    data class Auth(val ownerUid: String, val binding: Long,
        val origin: LifetimeId, val generation: Long) : ObligationSubject
    data class NamedEffect(val operationId: String, val effectIndex: Int) : ObligationSubject
}

internal enum class ObligationComponent {
    REQUEST, SOURCE, SEAL, JOURNAL, FLOOR, AUTH, BINDING,
    NAMESPACE_RETIREMENT, DURABLE_EFFECT, RECEIPT
}
internal data class RequiredObligationKey(
    val role: ObligationRole, val subject: ObligationSubject,
    val component: ObligationComponent, val branch: LandingBranch
)
internal sealed interface SlotNecessity {
    data object Required : SlotNecessity
    data object NotRequiredByContract : SlotNecessity
}
internal data class ExpectedSlot(val key: RequiredObligationKey, val necessity: SlotNecessity)
internal enum class ObligationDisposition { DurablyOwned, CompletedAndConsumed, NotRequiredByContract }
internal data class SubmittedSlot(val key: RequiredObligationKey, val disposition: ObligationDisposition)
internal sealed interface CoverageResult {
    data object Complete : CoverageResult
    data class Rejected(val problems: List<CoverageProblem>) : CoverageResult
}
internal enum class CoverageProblem {
    DuplicateExpected, DuplicateSubmitted, MissingL, MissingN, Extra,
    CallerNotRequired, CompletedForbidden
}

/** Structural coverage only; actual completion and destination evidence are owner checks. */
internal fun checkSlotCoverage(expected: List<ExpectedSlot>, submitted: List<SubmittedSlot>): CoverageResult {
    val problems = linkedSetOf<CoverageProblem>()
    // Count both original lists before any set conversion can erase a repeated slot.
    if (expected.groupingBy { it.key }.eachCount().any { it.value > 1 }) problems += CoverageProblem.DuplicateExpected
    if (submitted.groupingBy { it.key }.eachCount().any { it.value > 1 }) problems += CoverageProblem.DuplicateSubmitted
    val expectedByKey = expected.associateBy { it.key }
    val submittedKeys = submitted.map { it.key }.toSet()
    for (slot in expected) {
        if (slot.necessity == SlotNecessity.Required && slot.key !in submittedKeys) {
            problems += if (slot.key.branch == LandingBranch.L) CoverageProblem.MissingL else CoverageProblem.MissingN
        }
    }
    for (slot in submitted) {
        val requirement = expectedByKey[slot.key]
        if (requirement == null) problems += CoverageProblem.Extra
        if (slot.disposition == ObligationDisposition.NotRequiredByContract ||
            requirement?.necessity == SlotNecessity.NotRequiredByContract) problems += CoverageProblem.CallerNotRequired
        if (slot.key.component == ObligationComponent.FLOOR &&
            slot.disposition == ObligationDisposition.CompletedAndConsumed) problems += CoverageProblem.CompletedForbidden
    }
    return if (problems.isEmpty()) CoverageResult.Complete else CoverageResult.Rejected(problems.toList())
}
