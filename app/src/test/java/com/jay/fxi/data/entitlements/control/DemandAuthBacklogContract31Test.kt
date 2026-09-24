package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-first file: Z.fixturePremises for the Q16b exhaustion boundary with a positive twin that does not
 * read the stored initial order (design §9.1 r3:539–545, r3:683 "each negative fixture first asserts the non-target
 * conditions"). C30's MAX−1→MAX twin depends on the stored initial, so a mutant that replaces it fails the twin first;
 * here the twin starts from 1, where the original and such a mutant both issue 2, and only the target condition — the
 * stored last order being MAX — differs from the role fixture.
 */
class DemandAuthBacklogContract31Test {
    @Test fun Z_fx_Q16b_exhaustedAtMax_lowTwin() {
        assertTrue("fixture: the fixture origin is not empty", F.life.value.isNotEmpty())
        assertEquals("positive twin: the same origin and bindingStart from order 1 issue 2",
            2L, LifecycleOrderSource(F.life, 1).issue(1)?.value)
        assertNull(F.eligible("Z.fx.Q16b.lowTwin"), LifecycleOrderSource(F.life, Long.MAX_VALUE).issue(1))
    }
}
