package com.jay.fxi.data.entitlements

/** S4 CUT-P2: the answer of [PremiumAccessCoordinator.markGraphData]. */
internal sealed interface GraphDataMarking {
    /**
     * The premium marker is confirmed persisted on [record], the store's record after the edit. KRX marking is conditional;
     * this result grants no write admission.
     */
    data class Marked(val record: AccessEpochRecord) : GraphDataMarking

    /** Nothing was marked for this request, or the store could not confirm it; [cause] is the store's failure, if any. */
    data class Refused(val reason: String, val cause: Throwable? = null) : GraphDataMarking
}
