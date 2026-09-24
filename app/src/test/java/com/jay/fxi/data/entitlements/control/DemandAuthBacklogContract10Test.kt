package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, tenth file: the two positive directions inside `DemandAuthTransition.durableEffects`.
 *
 * Before C10, every mutant that narrows this boundary was observed only through the plain "positive twin"
 * premise of a negative test (`C2 S_effectsParse:165`, `C2 S_exactRow_absent:178`, `DemandAuthIntegrationTest:22`).
 * Design §9.1 545 forbids counting that premise failure as a kill, and the M4 rows (obligations 253-254, 326)
 * require the widening direction to be fixed to a candidate/result assertion of its own. These two tests are
 * that assertion, and they split the two independent gates of DT:154-168:
 *
 *   - DT:160-166 per-effect exact rows, exercised with equal fences so the namespace gate is skipped.
 *   - DT:155-159 namespace confirmation, exercised with differing fences and a proof whose raw fence is `after`.
 *
 * Expected values are built in the test. No production validator is called to produce them (§9.1 539).
 */
class DemandAuthBacklogContract10Test {

    private fun exactRowFacts(label: String, read: ControlRecordRead.Supported, node: ControlNode) {
        val location = read.locations("e").singleOrNull()
        assertNotNull("fixture: $label holds exactly one row for e", location)
        assertEquals("fixture: $label row is the DEMAND kind", ControlKind.DEMAND, checkNotNull(location).first)
        val entry = checkNotNull(location).second
        assertTrue("fixture: $label row is interpreted", entry is ControlEntryRead.Interpreted)
        assertEquals("fixture: $label row payload equals the effect node",
            node.toPayloadEntry(), (entry as ControlEntryRead.Interpreted).original.toPayloadEntry())
    }

    // RC.effects.exactPositive — DT:160-166. A well-formed effect whose proof and current rows are exact is durable.

    @Test fun RC_effects_exactPositive() {
        val e = F.request(id = "e")
        val current = F.read(F.raw(e))
        val proof = ConfirmedControlSnapshot(F.read(F.raw(e)))
        val d = F.decision(effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, proof)))
        assertEquals("fixture: the fences are equal so the namespace gate is skipped",
            d.acceptedBeforeFence, d.confirmedAfterFence)
        assertEquals("fixture: exactly one effect", 1, d.effects.size)
        assertSame("fixture: the effect carries the proof", proof, d.effects.single().confirmation)
        assertEquals("fixture: the effect node reads under the DEMAND schema with id e",
            "e", checkNotNull(ControlSchema.read(ControlKind.DEMAND, e)).id)
        exactRowFacts("the proof record", proof.record, e)
        exactRowFacts("the current read", current, e)
        assertTrue(F.atomic("RC.effects.exactPositive"), F.transition.durableEffects(d, current))
    }

    // RC.namespace.exactPositive — DT:155-159. When the fences differ, a confirmation whose raw fence is the
    // after fence satisfies the namespace gate; the effect gate stays satisfied by the same exact rows.

    @Test fun RC_namespace_exactPositive() {
        val e = F.request(id = "e")
        val current = F.read(F.raw(e))
        val proof = ConfirmedControlSnapshot(F.read(F.raw(e)))
        val movedBefore = FenceV1("A", "u-old", "k")
        val d = F.decision(q = F.query.copy(fence = movedBefore), before = movedBefore,
            effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, proof)), namespace = proof)
        assertEquals("fixture: accepted-before matches the started query", d.query.fence, d.acceptedBeforeFence)
        assertNotEquals("fixture: the before fence differs from the after fence",
            d.acceptedBeforeFence, d.confirmedAfterFence)
        assertEquals("fixture: the confirmed-after fence is A/u/k",
            listOf("A", "u", "k"),
            listOf(d.confirmedAfterFence.ownerUid, d.confirmedAfterFence.userAccessEpoch,
                d.confirmedAfterFence.krxCapabilityEpoch))
        val proofRaw = proof.record.original
        assertEquals("fixture: the namespace proof raw fence is A/u/k",
            listOf("A", "u", "k"), listOf(proofRaw[OWNER_UID], proofRaw[USER_EPOCH], proofRaw[KRX_EPOCH]))
        assertSame("fixture: the namespace confirmation is present", proof, d.namespaceConfirmation)
        assertEquals("fixture: exactly one effect", 1, d.effects.size)
        assertSame("fixture: the effect carries the proof", proof, d.effects.single().confirmation)
        assertEquals("fixture: the effect node reads under the DEMAND schema with id e",
            "e", checkNotNull(ControlSchema.read(ControlKind.DEMAND, e)).id)
        exactRowFacts("the proof record", proof.record, e)
        exactRowFacts("the current read", current, e)
        assertTrue(F.atomic("RC.namespace.exactPositive"), F.transition.durableEffects(d, current))
    }
}
