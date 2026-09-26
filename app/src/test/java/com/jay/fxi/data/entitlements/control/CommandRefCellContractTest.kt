package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.fixture
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.pending
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.released
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-1 T1 contract (skeleton v6 §1; revision 06 §3.1/§3.6). One immutable cell owns the lifecycle state and
 * the executable body: five states, body kept through RETAINED and both pendings, RELEASED keeps its Mutations input
 * (revision 03), and TERMINATED publishes body=null together with the state. Terminal body/actions access fails with a
 * defined IllegalStateException. A wrong-state termination transition fails like the existing release CAS (check) and
 * leaves the cell unchanged. Sequential tests cannot kill a double cell read; the single-read view is pinned by the
 * structure test. Transitions are reached reflectively because structure tripwires pin their only production
 * caller to the owner path. The implementation thread reads but does not edit this file.
 */
class CommandRefCellContractTest {
    private val message = "terminated command has no executable body"
    private fun beginTermination(c: CommandRef) = invoke(c, "beginTermination")
    private fun completeTermination(c: CommandRef) = invoke(c, "completeTermination")
    private fun invoke(target: Any, name: String) {
        try {
            target.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(target)
        } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    private fun terminated(): CommandRef = fixture().command.also { beginTermination(it); completeTermination(it) }
    private fun closedAccess(id: String, block: () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("D2B6/T1.$id: definedClosedBodyAccess", failure is IllegalStateException)
        assertEquals("D2B6/T1.$id: definedClosedBodyAccess", message, failure?.message)
    }
    private fun rejectedUnchanged(id: String, c: CommandRef, transition: (CommandRef) -> Unit) {
        val before = c.captureStateAndBody()
        val failure = runCatching { transition(c) }.exceptionOrNull()
        assertTrue("D2B6/T1.$id: wrongStateTransitionFails", failure is IllegalStateException)
        val after = c.captureStateAndBody()
        assertEquals("D2B6/T1.$id: cellUnchanged", before.state, after.state)
        assertSame("D2B6/T1.$id: cellUnchanged", before.body, after.body)
    }

    @Test fun T1_01_fiveStates() {
        assertEquals("D2B6/T1.01: fiveStates",
            listOf("RETAINED", "RELEASE_PENDING", "RELEASED", "TERMINATION_PENDING", "TERMINATED"),
            ControlCommandLifecycle.values().map { it.name })
    }

    @Test fun T1_02_retainedViewCarriesTheConstructorBody() {
        val c = fixture().command
        val body = c.body
        val v = c.captureStateAndBody()
        assertEquals("D2B6/T1.02: retainedView", ControlCommandLifecycle.RETAINED, v.state)
        assertSame("D2B6/T1.02: retainedView", body, v.body)
        assertEquals("D2B6/T1.02: retainedActions", (body as ControlCommandBody.Mutations).actions, c.actions)
    }

    @Test fun T1_03_terminationPendingKeepsTheSameBody() {
        val c = fixture().command
        val body = c.body
        beginTermination(c)
        val v = c.captureStateAndBody()
        assertEquals("D2B6/T1.03: pendingState", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertEquals("D2B6/T1.03: pendingState", ControlCommandLifecycle.TERMINATION_PENDING, v.state)
        assertSame("D2B6/T1.03: pendingBodyIdentity", body, c.body)
        assertSame("D2B6/T1.03: pendingBodyIdentity", body, v.body)
        assertEquals("D2B6/T1.03: pendingActions", (body as ControlCommandBody.Mutations).actions, c.actions)
    }

    @Test fun T1_04_terminatedPublishesNullBodyWithTheState() {
        val c = terminated()
        val v = c.captureStateAndBody()
        assertEquals("D2B6/T1.04: terminalState", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertEquals("D2B6/T1.04: terminalView", ControlCommandLifecycle.TERMINATED, v.state)
        assertNull("D2B6/T1.04: terminalView", v.body)
        closedAccess("04body") { c.body }
        closedAccess("04actions") { c.actions }
    }

    @Test fun T1_05_releasePendingAndReleasedKeepMutationsInput() {
        val c = fixture().command
        val body = c.body
        pending(c)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        assertSame("D2B6/T1.05: releasePendingInputKept", body, c.captureStateAndBody().body)
        released(c)
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
        assertSame("D2B6/T1.05: releasedInputKept", body, c.body)
        assertSame("D2B6/T1.05: releasedInputKept", body, c.captureStateAndBody().body)
        assertEquals("D2B6/T1.05: releasedActions", (body as ControlCommandBody.Mutations).actions, c.actions)
    }

    @Test fun T1_06_noCrossingBetweenReleaseAndTermination() {
        rejectedUnchanged("06a", fixture().command.also { pending(it) }) { beginTermination(it) }
        rejectedUnchanged("06b", fixture().command.also { pending(it) }) { completeTermination(it) }
        rejectedUnchanged("06c", fixture().command.also { pending(it); released(it) }) { beginTermination(it) }
        rejectedUnchanged("06g", fixture().command.also { pending(it); released(it) }) { completeTermination(it) }
        rejectedUnchanged("06d", fixture().command.also { beginTermination(it) }) { pending(it) }
        rejectedUnchanged("06e", fixture().command.also { beginTermination(it) }) { released(it) }
        rejectedUnchanged("06f", fixture().command) { completeTermination(it) }
    }

    @Test fun T1_07_terminalIsMonotonic() {
        rejectedUnchanged("07a", terminated()) { beginTermination(it) }
        rejectedUnchanged("07b", terminated()) { completeTermination(it) }
        rejectedUnchanged("07c", terminated()) { pending(it) }
        rejectedUnchanged("07d", terminated()) { released(it) }
        rejectedUnchanged("07e", fixture().command.also { beginTermination(it) }) { beginTermination(it) }
    }

    @Test fun T1_08_identityAndLifetimeSurviveTermination() {
        val c = fixture().command
        val id = c.id
        val life = c.ownerTrackingLifetimeId
        beginTermination(c); completeTermination(c)
        assertEquals("D2B6/T1.08: identityKept", id, c.id)
        assertSame("D2B6/T1.08: lifetimeKept", life, c.ownerTrackingLifetimeId)
    }

    @Test fun T1_09_capturedViewIsAnImmutableSnapshot() {
        val c = fixture().command
        val body = c.body
        beginTermination(c)
        val pendingView = c.captureStateAndBody()
        completeTermination(c)
        assertEquals("D2B6/T1.09: viewDoesNotFollowCell", ControlCommandLifecycle.TERMINATION_PENDING, pendingView.state)
        assertSame("D2B6/T1.09: viewDoesNotFollowCell", body, pendingView.body)
        assertNull("D2B6/T1.09: cellMovedOn", c.captureStateAndBody().body)
    }
}
