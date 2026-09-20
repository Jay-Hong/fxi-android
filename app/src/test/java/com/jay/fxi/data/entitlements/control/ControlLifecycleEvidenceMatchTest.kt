package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import org.junit.Assert.*
import org.junit.Test

class ControlLifecycleEvidenceMatchTest {
    private val input = F.descriptor(transition = LifecycleTransition.REBIND_REQUESTS, targets = listOf(F.replaceRequest))
    private val command = F.command(input)
    private val tracked = TrackedControlCommand(command)
    private val correct = F.row(command)
    private fun mismatch(id: String, row: AppliedEvidence) {
        assertTrue(ControlAppliedEvidence.matches(command, tracked, correct))
        assertFalse(F.retry(id), ControlAppliedEvidence.matches(command, tracked, row))
    }
    @Test fun R05a_commandId() = mismatch("R05a", AppliedEvidence.Lifecycle("other", correct.ownerTrackingLifetimeId, correct.transition, correct.targets))
    @Test fun R05b_lifetime() = mismatch("R05b", AppliedEvidence.Lifecycle(correct.commandId, ReclamationFixtures.otherLife, correct.transition, correct.targets))
    @Test fun R05c_kind() = mismatch("R05c", AppliedEvidence.Mutations(correct.commandId, correct.ownerTrackingLifetimeId,
        listOf(AppliedTarget(0, ControlKind.DEMAND, "d", false, true))))
    @Test fun R05d_transition() = mismatch("R05d", AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId,
        LifecycleTransition.END_AUTH_BINDING, correct.targets))
    @Test fun R05e_targetKindTypedBoundary() = mismatch("R05e", AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId,
        correct.transition, listOf(correct.targets.single().copy(kind = ControlKind.HOLD))))
    @Test fun R05f_targetId() = mismatch("R05f", AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId,
        correct.transition, listOf(correct.targets.single().copy(id = "other"))))
    @Test fun R05g_effectTypedBoundary() = mismatch("R05g", AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId,
        correct.transition, listOf(correct.targets.single().copy(effect = LifecycleEffect.CREATE))))
    @Test fun R05h_count() = mismatch("R05h", AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId,
        correct.transition, correct.targets + correct.targets.single().copy(id = "other")))
    @Test fun R05i_order() {
        val second = F.replaceRequest.copy(target = F.replaceRequest.target.copy(id = "second"),
            before = F.node(ControlObligationFixtures.request.replace("\"d\"", "\"second\"")),
            after = F.node(ControlObligationFixtures.request.replace("\"d\"", "\"second\"").replace("IF_STALE", "FORCE_PREMIUM")))
        val c = F.command(F.descriptor(transition = LifecycleTransition.REBIND_REQUESTS, targets = listOf(F.replaceRequest, second)))
        val good = F.row(c)
        assertTrue(ControlAppliedEvidence.matches(c, TrackedControlCommand(c), good))
        assertFalse(F.retry("R05i"), ControlAppliedEvidence.matches(c, TrackedControlCommand(c),
            AppliedEvidence.Lifecycle(c.id, c.ownerTrackingLifetimeId.value, good.transition, good.targets.reversed())))
    }
    @Test fun R05j_retainedExpected() {
        tracked.expectedApplied = AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId, correct.transition,
            listOf(correct.targets.single().copy(id = "previous")))
        assertTrue(ControlLifecycleEvidence.matches(input, correct))
        assertFalse(F.retry("R05j"), ControlAppliedEvidence.matches(command, tracked, correct))
    }
    @Test fun wireAndDescriptorListsAreDetachedAndImmutable() {
        val targets = correct.targets.toMutableList()
        val copy = AppliedEvidence.Lifecycle(correct.commandId, correct.ownerTrackingLifetimeId, correct.transition, targets)
        targets.clear()
        assertEquals(correct.targets, copy.targets)
        assertTrue(runCatching { (copy.targets as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        val fixed = input.targets.toMutableList()
        val descriptor = F.descriptor(transition = input.transition, targets = fixed)
        fixed.clear()
        assertEquals(input.targets, descriptor.targets)
        assertTrue(runCatching { (descriptor.targets as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
}
