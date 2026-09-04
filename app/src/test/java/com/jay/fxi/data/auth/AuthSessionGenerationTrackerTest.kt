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
}
