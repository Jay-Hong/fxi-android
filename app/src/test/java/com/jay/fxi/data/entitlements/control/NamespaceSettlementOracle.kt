package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.StoreOp

/** Independent expected facts: never call the production after/witness functions. */
internal object NamespaceSettlementOracle {
    fun witness(axis: PurgeScope = PurgeScope.USER, both: Boolean = false) = SettlementEvidenceV1(
        "00000000-0000-0000-0000-000000000001", LifetimeId("life"), StoreOp.BEGIN_ROTATION,
        FenceV1("A", "u", "k"),
        FenceV1("A", if (both || axis == PurgeScope.USER) "00000000-0000-0000-0000-000000000003" else "u",
            if (both || axis == PurgeScope.CAPABILITY) "00000000-0000-0000-0000-000000000004" else "k"),
        JournalTargetV1("A", axis, if (axis == PurgeScope.USER) "u" else "k"))
}
