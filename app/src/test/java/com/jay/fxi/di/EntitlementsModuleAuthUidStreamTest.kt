package com.jay.fxi.di

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The uid view of the fence stream.
 *
 * This adapter replaced a second `FirebaseAuth.AuthStateListener`, so the question it has to keep
 * answering is not "does it map" but **"does the consumer downstream see what it used to"**. The
 * consumer is `FreeSnapshotScheduler`, and a repeated uid is not free there: it skips only the
 * cache wipe and still runs its pump, freshness, publish and timer re-arm.
 *
 * What that scheduler then does with the events it receives is unchanged and stays locked by its
 * own suite; what changed, and what is pinned here, is which events reach it.
 */
class EntitlementsModuleAuthUidStreamTest {

    /** Drives fences by hand so registration order cannot make a case pass for the wrong reason. */
    private class Fences : AuthFenceStream {
        private val listeners = mutableListOf<(AuthIdentityFence?) -> Unit>()
        override fun observe(onFence: (AuthIdentityFence?) -> Unit) { listeners += onFence }
        fun emit(fence: AuthIdentityFence?) = listeners.forEach { it(fence) }
    }

    private fun uidsFrom(fences: Fences): List<String?> {
        val seen = mutableListOf<String?>()
        EntitlementsModule.provideAuthUidStream(fences).observe { seen += it }
        return seen
    }

    /**
     * D1 — the first value arrives even when it is `null`.
     *
     * A subscriber has to be able to tell "nobody is signed in" from "nothing has arrived yet". A
     * dedup that compares against an initial `null` swallows the first of those.
     */
    @Test
    fun theFirstValueIsDeliveredEvenWhenItIsNull() {
        val fences = Fences()
        val seen = uidsFrom(fences)

        fences.emit(null)

        assertEquals(listOf<String?>(null), seen)
    }

    /**
     * D2 — a generation-only transition carries the same uid and is suppressed.
     *
     * Passing it on would spend a pump·publish·timer cycle in the free scheduler for a transition
     * that says nothing about the uid it consumes.
     */
    @Test
    fun aGenerationOnlyTransitionIsSuppressed() {
        val fences = Fences()
        val seen = uidsFrom(fences)

        fences.emit(AuthIdentityFence("user-a", 1L))
        fences.emit(AuthIdentityFence("user-a", 2L))
        fences.emit(AuthIdentityFence("user-a", 3L))

        assertEquals(listOf<String?>("user-a"), seen)
    }

    /**
     * D2 control — a real uid change is not suppressed.
     *
     * Without this the case above would pass on an adapter that emits once and then stops.
     */
    @Test
    fun arealUidChangeIsDelivered() {
        val fences = Fences()
        val seen = uidsFrom(fences)

        fences.emit(AuthIdentityFence("user-a", 1L))
        fences.emit(AuthIdentityFence("user-b", 2L))

        assertEquals(listOf<String?>("user-a", "user-b"), seen)
    }

    /**
     * D3 — `A → null → A` survives.
     *
     * Value-keyed dedup that only remembered the last **non-null** uid would collapse the sign-out
     * in the middle and leave the consumer believing the session never ended.
     */
    @Test
    fun aSignOutBetweenTwoSessionsOfTheSameUserIsPreserved() {
        val fences = Fences()
        val seen = uidsFrom(fences)

        fences.emit(AuthIdentityFence("user-a", 1L))
        fences.emit(null)
        fences.emit(AuthIdentityFence("user-a", 3L))

        assertEquals(listOf<String?>("user-a", null, "user-a"), seen)
    }

    /** Each subscriber owns its own first value and its own history. */
    @Test
    fun subscribersDoNotShareDedupState() {
        val fences = Fences()
        val first = uidsFrom(fences)
        fences.emit(AuthIdentityFence("user-a", 1L))
        val second = uidsFrom(fences)

        fences.emit(AuthIdentityFence("user-a", 2L))

        assertEquals(listOf<String?>("user-a"), first)
        assertEquals("a late subscriber inherited another's dedup state", listOf<String?>("user-a"), second)
    }
}
