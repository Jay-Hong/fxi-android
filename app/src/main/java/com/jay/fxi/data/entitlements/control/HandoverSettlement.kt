package com.jay.fxi.data.entitlements.control

import java.util.Collections

/** These are the only handover kinds supported by schema 2. */
enum class HandoverSettlementTransition { RETIRED_NAMESPACE, CURRENT_NULL, RETIRED_NULL }

/** Fixed executor identity, compared with a fresh context on each new application. */
internal data class SettlementExecutor(val ownerUid: String?, val binding: Long, val originLifetimeId: LifetimeId)

/** Fixed inputs only. Constructing one neither prepares a ref nor validates a storage candidate. */
internal sealed interface HandoverSettlementInput {
    val operationId: String
    val before: FenceV1
    val executor: SettlementExecutor
}

internal class RetiredNamespaceSettlement(
    val target: ControlNode,
    override val before: FenceV1,
    override val executor: SettlementExecutor,
    override val operationId: String,
    val demandId: String?,
    val demand: SettlementDemand?
) : HandoverSettlementInput {
    init {
        require((demandId == null) == (demand == null)) { "demandId and demand must be present together" }
    }
}

internal class CurrentNullSettlement(
    nullTargets: List<ControlNode>,
    companions: List<ControlNode>,
    override val before: FenceV1,
    override val executor: SettlementExecutor,
    override val operationId: String,
    val demandId: String,
    val demand: SettlementDemand,
    val newUserEpoch: String?,
    val newKrxEpoch: String?
) : HandoverSettlementInput {
    val nullTargets: List<ControlNode> = Collections.unmodifiableList(nullTargets.toList())
    val companions: List<ControlNode> = Collections.unmodifiableList(companions.toList())
}

internal class RetiredNullSettlement(
    targets: List<ControlNode>,
    override val before: FenceV1,
    override val executor: SettlementExecutor,
    override val operationId: String
) : HandoverSettlementInput {
    val targets: List<ControlNode> = Collections.unmodifiableList(targets.toList())
}

/** NotRequired says this command issued no demand; Absent says a required demand is now absent. */
internal enum class HandoverDemandObservation { NotRequired, Present, Changed, Absent, Uninterpretable }

/** Closed result tag; rotation and handover observations have different demand contracts. */
internal sealed interface ControlSettlementReceipt

/**
 * R/N/L receipt, distinct from the existing rotation receipt.
 * Journal/demand observations never prove consumption, freshness, or protected admission.
 */
internal class HandoverSettlementReceipt(
    val transition: HandoverSettlementTransition,
    val operationId: String,
    val originLifetimeId: LifetimeId,
    val before: FenceV1,
    val after: FenceV1,
    witnesses: Map<String, SettlementEvidence>,
    journal: Map<String, JournalObservation>,
    val demandId: String?,
    val demand: HandoverDemandObservation,
    remainingSeals: List<SealV1>,
    val hasUninterpretable: Boolean,
    val hasUninterpretableMetadata: Boolean
) : ControlSettlementReceipt {
    val blocksProtectedAdmission: Boolean get() = hasUninterpretable || hasUninterpretableMetadata
    val witnesses: Map<String, SettlementEvidence> = Collections.unmodifiableMap(witnesses.toMap())
    val journal: Map<String, JournalObservation> = Collections.unmodifiableMap(journal.toMap())
    val remainingSeals: List<SealV1> = Collections.unmodifiableList(remainingSeals.toList())
}
