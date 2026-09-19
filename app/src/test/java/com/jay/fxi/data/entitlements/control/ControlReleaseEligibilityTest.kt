package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.fixture
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.sameLifetimeText
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.pending
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.released
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.bind
import org.junit.Assert.*
import org.junit.Test

class ControlReleaseEligibilityTest {
    private val tracked = fixture()
    private val command = tracked.command
    private fun decide(ref: CommandRef = command, life: OwnerTrackingLifetimeId = command.ownerTrackingLifetimeId,
        registered: TrackedControlCommand? = tracked, busy: Boolean = false, unresolved: Boolean = false) =
        ControlCommandReleaseEligibility.decide(ref, life, registered, busy, unresolved)
    private fun rejected(expected: ReleaseRejectionReason, actual: ControlCommandReleaseEligibility.Decision) {
        assertEquals(expected, (actual as? ControlCommandReleaseEligibility.Decision.Rejected)?.reason)
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
        assertNull(tracked.releaseDescriptor)
    }

    @Test fun A01_inFlight() { rejected(ReleaseRejectionReason.InFlight, decide(busy = true)) }
    @Test fun A02_unresolvedDespiteConfirmed() { rejected(ReleaseRejectionReason.Unresolved, decide(unresolved = true)) }
    @Test fun A03_unattemptedNotConfirmed() {
        tracked.confirmed.set(false); tracked.confirmationRequested.set(false)
        rejected(ReleaseRejectionReason.NotConfirmed, decide())
    }
    @Test fun A06_unregisteredCloneHasNoIdentity() {
        rejected(ReleaseRejectionReason.NotRegisteredIdentity,
            decide(ref = CommandRef(command.id, command.body, command.ownerTrackingLifetimeId)))
    }
    @Test fun A06_absentRegistration() { rejected(ReleaseRejectionReason.NotRegisteredIdentity, decide(registered = null)) }
    @Test fun A07_wrongLifetime() { rejected(ReleaseRejectionReason.WrongTrackerLifetime, decide(life = OwnerTrackingLifetimeId.issue())) }
    @Test fun A08_sameUuidDifferentLifetimeObject() {
        rejected(ReleaseRejectionReason.WrongTrackerLifetime, decide(life = sameLifetimeText(command.ownerTrackingLifetimeId)))
    }
    @Test fun A09_rotationUnsupported() {
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val ref = CommandRef("op", ControlCommandBody.RotateAndSettle(input), command.ownerTrackingLifetimeId)
        val rotation = TrackedControlCommand(ref).apply { confirmed.set(true) }
        rejected(ReleaseRejectionReason.UnsupportedCommandKind, decide(ref = ref, registered = rotation))
    }
    @Test fun A10_confirmRequestedIsNotConfirmed() {
        tracked.confirmed.set(false)
        rejected(ReleaseRejectionReason.NotConfirmed, decide())
    }
    @Test fun A10_unresolvedTakesPriorityOverNotConfirmed() {
        tracked.confirmed.set(false)
        rejected(ReleaseRejectionReason.Unresolved, decide(unresolved = true))
    }
    @Test fun A13_releasedBeforeRegistrationAndLeaseChecks() {
        pending(command); released(command)
        assertEquals(ControlCommandReleaseEligibility.Decision.AlreadyReleased, decide(registered = null, busy = true, unresolved = true))
    }
    @Test fun C17_oldReleasedStillWrongLifetime() {
        pending(command); released(command)
        assertEquals(ReleaseRejectionReason.WrongTrackerLifetime,
            (decide(life = OwnerTrackingLifetimeId.issue(), registered = null) as? ControlCommandReleaseEligibility.Decision.Rejected)?.reason)
    }
    @Test fun C18_oldPendingStillWrongLifetime() {
        pending(command)
        assertEquals(ReleaseRejectionReason.WrongTrackerLifetime,
            (decide(life = OwnerTrackingLifetimeId.issue(), registered = null) as? ControlCommandReleaseEligibility.Decision.Rejected)?.reason)
    }
    @Test fun A12_pendingRequiresDescriptor() {
        pending(command)
        val failure = runCatching { decide() }.exceptionOrNull()
        assertEquals("pending release has no descriptor", failure?.message)
    }
    @Test fun A16_pendingCannotAlsoBeUnresolved() {
        bind(tracked, ReleasePendingDescriptor.ConfirmedWithoutApplied); pending(command)
        val failure = runCatching { decide(unresolved = true) }.exceptionOrNull()
        assertEquals("pending release is also business unresolved", failure?.message)
    }
    @Test fun A12_pendingEligibleWithFixedDescriptor() {
        bind(tracked, ReleasePendingDescriptor.ConfirmedWithoutApplied); pending(command)
        assertEquals(ControlCommandReleaseEligibility.Decision.Eligible, decide())
        assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, tracked.releaseDescriptor)
    }
    @Test fun A03_confirmedRetainedEligible() {
        assertEquals(ControlCommandReleaseEligibility.Decision.Eligible, decide())
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
        assertNull(tracked.releaseDescriptor)
    }
}
