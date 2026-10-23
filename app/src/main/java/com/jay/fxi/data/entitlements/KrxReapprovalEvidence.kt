package com.jay.fxi.data.entitlements

/** R13‴ evidence for one binding. A suppression starts a new publication-order window. */
internal data class KrxReapprovalEvidence(
    val suppressedTrueOrder: Long?,
    val truthfulOrder: Long?,
    val debtFromRotationOnly: Boolean
) {
    fun onSuppressedTrue(startOrder: Long): KrxReapprovalEvidence = copy(
        suppressedTrueOrder = suppressedTrueOrder?.let { maxOf(it, startOrder) } ?: startOrder,
        truthfulOrder = null
    )

    fun onTruthfulStable(startOrder: Long): KrxReapprovalEvidence = copy(
        truthfulOrder = truthfulOrder?.let { maxOf(it, startOrder) } ?: startOrder
    )

    fun needed(): Boolean = suppressedTrueOrder?.let { suppressed ->
        truthfulOrder?.let { suppressed > it } ?: true
    } ?: false

    fun blocksOnCandidate(candidateStartOrder: Long): Boolean =
        suppressedTrueOrder?.let { candidateStartOrder > it } ?: false

    companion object {
        val EMPTY = KrxReapprovalEvidence(null, null, false)
    }
}

internal enum class KrxDemandMerge {
    REAPPROVAL_INTO_EMPTY, REAPPROVAL_INTO_REAPPROVAL_ONLY, REAPPROVAL_INTO_OTHER, OTHER_INDEPENDENT, RETRY
}

internal fun reapprovalOnlyAfter(previous: Boolean?, merge: KrxDemandMerge): Boolean = when (merge) {
    KrxDemandMerge.REAPPROVAL_INTO_EMPTY -> {
        require(previous == null)
        true
    }
    KrxDemandMerge.REAPPROVAL_INTO_REAPPROVAL_ONLY -> {
        require(previous == true)
        true
    }
    KrxDemandMerge.REAPPROVAL_INTO_OTHER -> {
        require(previous == false)
        false
    }
    KrxDemandMerge.OTHER_INDEPENDENT -> false
    KrxDemandMerge.RETRY -> requireNotNull(previous)
}
