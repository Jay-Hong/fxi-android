package com.jay.fxi.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionGenerationTrackerTest {

    @Test
    fun initialListenerReconciliation_doesNotRotateGeneration() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)

        val initial = tracker.observe("user-a", session)
        val reconciled = tracker.observe("user-a", session)

        assertEquals(AuthIdentity("user-a", 1), initial)
        assertEquals(initial, reconciled)
    }

    @Test
    fun explicitInvalidation_rotatesSameUidBeforeFirebaseSignOut() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val oldIdentity = tracker.observe("user-a", session)!!

        assertTrue(tracker.invalidate(oldIdentity))
        val invalidatedIdentity = tracker.observe("user-a", session)!!

        assertEquals(oldIdentity.authGeneration + 1L, invalidatedIdentity.authGeneration)
        assertFalse(tracker.invalidate(oldIdentity))
    }

    @Test
    fun replacedUserObject_rotatesEvenWhenUidAndNullTransitionAreUnchanged() {
        val oldSession = Any()
        val newSession = Any()
        val tracker = AuthSessionGenerationTracker("user-a", oldSession)
        val oldIdentity = tracker.observe("user-a", oldSession)

        val newIdentity = tracker.observe("user-a", newSession)

        assertNotEquals(oldIdentity, newIdentity)
        assertEquals("user-a", newIdentity?.uid)
        assertEquals(2L, newIdentity?.authGeneration)
    }

    @Test
    fun observedSignOutAndDifferentUidRemainMonotonic() {
        val firstSession = Any()
        val secondSession = Any()
        val tracker = AuthSessionGenerationTracker("user-a", firstSession)
        val first = tracker.observe("user-a", firstSession)!!

        assertEquals(null, tracker.observe(null, null))
        val second = tracker.observe("user-b", secondSession)!!

        assertTrue(second.authGeneration > first.authGeneration)
        assertEquals("user-b", second.uid)
    }

    // ---- publication (L-4b) ---------------------------------------------------------------------

    /**
     * Registers a list-appending listener and delivers one outbox snapshot on the calling thread.
     * This helper does not use the source's locked drain or SerialDeliveryPump's loop and guards.
     * Pump behaviour is tested separately in SerialDeliveryPumpTest.
     */
    private fun AuthSessionGenerationTracker.collect(into: MutableList<AuthIdentityFence?>) {
        subscribe { into += it }
        drainOutbox().forEach { it.deliver() }
    }

    private fun AuthSessionGenerationTracker.pump() = drainOutbox().forEach { it.deliver() }

    /**
     * A1 — a subscriber is handed the current fence, and that hand-over is not a transition.
     *
     * Without this, publishing only on change leaves a signed-in cold start with nothing: the
     * initial generation is set in the constructor, so the first `observe` of the same user does
     * not advance and therefore would not publish.
     */
    @Test
    fun subscribing_replaysTheCurrentFenceWithoutRotating() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val seen = mutableListOf<AuthIdentityFence?>()

        tracker.collect(seen)

        assertEquals(listOf(AuthIdentityFence("user-a", 1L)), seen)
        assertEquals("the replay moved the generation", 1L, tracker.observe("user-a", session)!!.authGeneration)
    }

    /** A1 — a late subscriber receives the current fence, not the transitions it missed. */
    @Test
    fun subscribingAfterATransition_replaysWhereTheSessionIsNow() {
        val tracker = AuthSessionGenerationTracker("user-a", Any())
        tracker.observe("user-b", Any())
        val late = mutableListOf<AuthIdentityFence?>()

        tracker.collect(late)

        assertEquals(listOf(AuthIdentityFence("user-b", 2L)), late)
    }

    /** A2 — a uid transition seen by the Firebase listener publishes. */
    @Test
    fun anObservedTransition_publishes() {
        val tracker = AuthSessionGenerationTracker("user-a", Any())
        val seen = mutableListOf<AuthIdentityFence?>()
        tracker.collect(seen)

        tracker.observe("user-b", Any())
        tracker.pump()

        assertEquals(
            listOf(AuthIdentityFence("user-a", 1L), AuthIdentityFence("user-b", 2L)),
            seen
        )
    }

    /**
     * A2·A4 — an explicit invalidation publishes, with no Firebase callback anywhere in it.
     *
     * This is the path the tracked sign-in runs before the SDK does anything. If publication hung
     * off the auth listener instead, an SDK failure after that point would leave the transition
     * unannounced and nothing would ever ask again.
     */
    @Test
    fun anExplicitInvalidation_publishesWithoutAnyFirebaseCallback() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val seen = mutableListOf<AuthIdentityFence?>()
        tracker.collect(seen)
        val current = tracker.observe("user-a", session)!!
        tracker.pump()

        assertTrue(tracker.invalidate(current))
        tracker.pump()

        assertEquals(
            listOf(AuthIdentityFence("user-a", 1L), AuthIdentityFence("user-a", 2L)),
            seen
        )
    }

    /** A3 — an observation that changes nothing is not a transition, so it publishes nothing. */
    @Test
    fun anUnchangedObservation_publishesNothing() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val seen = mutableListOf<AuthIdentityFence?>()
        tracker.collect(seen)
        val afterReplay = seen.size

        repeat(3) { tracker.observe("user-a", session) }
        tracker.pump()

        assertEquals("an unchanged observation was published as a transition", afterReplay, seen.size)
    }

    /** A5 — the null between a sign-out and a sign-in reaches every subscriber, in order. */
    @Test
    fun everySubscriberGetsEveryTransitionInOrder() {
        val tracker = AuthSessionGenerationTracker("user-a", Any())
        val first = mutableListOf<AuthIdentityFence?>()
        val second = mutableListOf<AuthIdentityFence?>()
        tracker.collect(first)
        tracker.collect(second)

        tracker.observe(null, null)
        tracker.observe("user-a", Any())
        tracker.pump()

        val expected = listOf(
            AuthIdentityFence("user-a", 1L),
            null,
            AuthIdentityFence("user-a", 3L)
        )
        assertEquals(expected, first)
        assertEquals("a second subscriber was handed a different history", expected, second)
    }

    /**
     * A6 — retirement publishes no intermediate fence, and the following sign-out publishes null.
     *
     * An intermediate same-uid fence can trigger a rebind and entitlement query in the binder.
     * This test checks the absence of that publication and the later null. Despite the method
     * name, it does not assert the generation after retirement: null carries no generation, so
     * these assertions cannot distinguish retirement that advances it from retirement that does not.
     */
    @Test
    fun retiring_movesTheGenerationWithoutAnnouncingIt() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val seen = mutableListOf<AuthIdentityFence?>()
        tracker.collect(seen)
        val current = tracker.observe("user-a", session)!!
        tracker.pump()
        val afterReplay = seen.size

        assertTrue(tracker.retire(current))
        tracker.pump()
        assertEquals("은퇴가 중간 세션을 알렸다", afterReplay, seen.size)

        // ...and the sign-out that follows is announced.
        tracker.observe(null, null)
        tracker.pump()
        assertEquals(listOf(AuthIdentityFence("user-a", 1L), null), seen)
    }

    /** A6 control — the sign-in side still announces, or the recovery this exists for is gone. */
    @Test
    fun invalidatingForSignIn_stillAnnounces() {
        val session = Any()
        val tracker = AuthSessionGenerationTracker("user-a", session)
        val seen = mutableListOf<AuthIdentityFence?>()
        tracker.collect(seen)
        val current = tracker.observe("user-a", session)!!
        tracker.pump()

        assertTrue(tracker.invalidate(current))
        tracker.pump()

        assertEquals(
            listOf(AuthIdentityFence("user-a", 1L), AuthIdentityFence("user-a", 2L)),
            seen
        )
    }
}
