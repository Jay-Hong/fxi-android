package com.jay.fxi.data.entitlements.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement (measure ledger r1 review, OPEN 3 / J4): ControlLifecycleEvidence.validShape's RECOVER_INTENT
 * branch — the two single conditions ControlLifecycleEvidenceFormatTest (P31 kind, P32 size, P33 CREATE) does not isolate:
 * the first target's effect must be REMOVE, and an optional second target must be a DEMAND row.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementShapeContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val source = LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE)
    private val request = LifecycleTarget(ControlKind.DEMAND, "new-request", LifecycleEffect.CREATE)

    @Test fun V0_positive() {
        assertTrue(atomic("V0_sourceOnly"), ControlLifecycleEvidence.validShape(LifecycleTransition.RECOVER_INTENT, listOf(source)))
        assertTrue(atomic("V0_sourceAndRequest"), ControlLifecycleEvidence.validShape(LifecycleTransition.RECOVER_INTENT, listOf(source, request)))
    }
    @Test fun V1_sourceMustBeRemoved() = assertFalse(atomic("V1_shapeEffect"),
        ControlLifecycleEvidence.validShape(LifecycleTransition.RECOVER_INTENT, listOf(source.copy(effect = LifecycleEffect.REPLACE), request)))
    @Test fun V2_secondMustBeDemand() = assertFalse(atomic("V2_shapeDemand"),
        ControlLifecycleEvidence.validShape(LifecycleTransition.RECOVER_INTENT, listOf(source, LifecycleTarget(ControlKind.HOLD, "h", LifecycleEffect.CREATE))))
}
