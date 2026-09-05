package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record semantics behind the crash-safe teardown order: persist the new epoch and the journal
 * entry, purge that namespace, then drop that entry.
 *
 * The atomicity of each step is DataStore's. What needs locking down here is that the journal
 * names a namespace *and an owner* a resumed purge can find, that entries accumulate rather than
 * collapse, that a completion touches only what it purged, and that ids follow I4 — opaque, never
 * reused, never reset.
 */
class AccessEpochTransitionsTest {

    private var counter = 0
    private val ids = EpochIdGenerator { "epoch-${counter++}" }

    private fun bound(uid: String = "user-a"): AccessEpochRecord =
        AccessEpochTransitions.bindOwner(AccessEpochRecord(), uid, ids)

    @Test
    fun firstBind_allocatesOpaqueIds_withoutJournallingAPurge() {
        val record = bound()

        assertEquals("user-a", record.ownerUid)
        assertNotNull(record.userAccessEpoch)
        assertNotEquals(record.userAccessEpoch, record.krxCapabilityEpoch)
        assertTrue("first allocation is not a teardown", record.pendingPurges.isEmpty())
    }

    @Test
    fun rotate_mintsNewIds_andJournalsTheOldOnesWithTheirOwner() {
        val before = bound()

        val after = AccessEpochTransitions.rotate(before, rotateUser = true, rotateKrx = true, ids = ids)

        assertNotEquals(before.userAccessEpoch, after.userAccessEpoch)
        assertNotEquals(before.krxCapabilityEpoch, after.krxCapabilityEpoch)
        val entry = after.pendingPurges.single()
        assertEquals(before.userAccessEpoch, entry.userAccessEpoch)
        assertEquals(before.krxCapabilityEpoch, entry.krxCapabilityEpoch)
        assertEquals("user-a", entry.ownerUid)
        assertEquals(AccessEpochTransitions.ALL_SCOPES, entry.scopes)
    }

    @Test
    fun rotate_krxOnly_recordsOnlyThatAxis() {
        val before = bound()

        val after = AccessEpochTransitions.rotate(before, rotateUser = false, rotateKrx = true, ids = ids)

        assertEquals(before.userAccessEpoch, after.userAccessEpoch)
        val entry = after.pendingPurges.single()
        assertNull("the user axis was not rotated", entry.userAccessEpoch)
        assertEquals(before.krxCapabilityEpoch, entry.krxCapabilityEpoch)
        assertEquals(setOf(PurgeScope.CAPABILITY), entry.scopes)
    }

    @Test
    fun rotate_withNeitherFlag_isANoOp() {
        val before = bound()

        assertEquals(before, AccessEpochTransitions.rotate(before, false, false, ids))
    }

    @Test
    fun ids_areNeverReusedAcrossRotations() {
        var record = bound()
        val seen = mutableSetOf(record.userAccessEpoch, record.krxCapabilityEpoch)

        repeat(5) {
            record = AccessEpochTransitions.rotate(record, rotateUser = true, rotateKrx = true, ids = ids)
            assertTrue("user epoch reused", seen.add(record.userAccessEpoch))
            assertTrue("krx epoch reused", seen.add(record.krxCapabilityEpoch))
        }
    }

    // --- regression: a single journal entry loses intermediate owners --------------------------

    @Test
    fun anAccountSwitchChain_keepsEveryUnpurgedNamespace() {
        val a = bound("user-a")
        val b = AccessEpochTransitions.bindOwner(a, "user-b", ids)
        val c = AccessEpochTransitions.bindOwner(b, "user-c", ids)

        // Collapsing to one "oldest" entry would purge A, clear the journal, and strand B.
        assertEquals(2, c.pendingPurges.size)
        assertEquals(listOf("user-a", "user-b"), c.pendingPurges.map { it.ownerUid })
        assertEquals(a.userAccessEpoch, c.pendingPurges[0].userAccessEpoch)
        assertEquals(b.userAccessEpoch, c.pendingPurges[1].userAccessEpoch)
    }

    @Test
    fun completePurges_dropsOnlyTheEntriesThatFinished() {
        val a = bound("user-a")
        val b = AccessEpochTransitions.bindOwner(a, "user-b", ids)
        val c = AccessEpochTransitions.bindOwner(b, "user-c", ids)
        val firstEntry = c.pendingPurges.first()

        val after = AccessEpochTransitions.completePurges(c, listOf(firstEntry))

        assertEquals(1, after.pendingPurges.size)
        assertEquals("user-b", after.pendingPurges.single().ownerUid)
    }

    @Test
    fun completePurges_withNothingCompleted_keepsTheJournal() {
        val rotated = AccessEpochTransitions.rotate(bound(), rotateUser = true, rotateKrx = true, ids = ids)

        assertEquals(rotated, AccessEpochTransitions.completePurges(rotated, emptyList()))
    }

    // --- regression: a completion must not clear markers written after the rotation ------------

    @Test
    fun rotation_absorbsTheMarkers_soLaterDataSurvivesAnEarlierPurge() {
        // A writes protected data, then a teardown rotates while the purge is still owed.
        val withData = AccessEpochTransitions.markMayContainData(bound(), premium = true, krx = true)
        val rotated = AccessEpochTransitions.rotate(withData, rotateUser = true, rotateKrx = true, ids = ids)

        // The live markers describe the *new* namespace, which is empty.
        assertFalse(rotated.mayContainPremiumData)
        assertFalse(rotated.mayContainKrxData)

        // New data is written into the new namespace while the old purge is still outstanding.
        val reused = AccessEpochTransitions.markMayContainData(rotated, premium = true, krx = false)

        val afterPurge = AccessEpochTransitions.completePurges(reused, reused.pendingPurges)

        assertTrue(
            "completing the old namespace must not clear the new namespace's marker",
            afterPurge.mayContainPremiumData
        )
        assertTrue(afterPurge.pendingPurges.isEmpty())
    }

    @Test
    fun partialRotation_absorbsOnlyThatAxisMarker() {
        val withData = AccessEpochTransitions.markMayContainData(bound(), premium = true, krx = true)

        val after = AccessEpochTransitions.rotate(withData, rotateUser = false, rotateKrx = true, ids = ids)

        assertTrue("the user namespace was untouched", after.mayContainPremiumData)
        assertFalse(after.mayContainKrxData)
    }

    // --- owner binding and sign-out -------------------------------------------------------------

    @Test
    fun bindOwner_differentOwner_rotatesBoth_andJournalsThePreviousUid() {
        val before = AccessEpochTransitions.markMayContainData(bound("user-a"), premium = true, krx = true)

        val after = AccessEpochTransitions.bindOwner(before, "user-b", ids)

        assertEquals("user-b", after.ownerUid)
        assertNotEquals(before.userAccessEpoch, after.userAccessEpoch)
        assertEquals("user-a", after.pendingPurges.single().ownerUid)
        assertFalse(after.mayContainPremiumData)
    }

    @Test
    fun bindOwner_sameOwner_keepsTheNamespace() {
        val before = AccessEpochTransitions.markMayContainData(bound(), premium = true, krx = false)

        val after = AccessEpochTransitions.bindOwner(before, "user-a", ids)

        assertEquals(before.userAccessEpoch, after.userAccessEpoch)
        assertTrue(after.pendingPurges.isEmpty())
        assertTrue(after.mayContainPremiumData)
    }

    @Test
    fun signOut_rotatesAndJournals_soTheNextSignInCannotInheritTheNamespace() {
        val before = AccessEpochTransitions.markMayContainData(bound(), premium = true, krx = true)

        val afterSignOut = AccessEpochTransitions.signOut(before, ids)

        assertNotEquals(before.userAccessEpoch, afterSignOut.userAccessEpoch)
        assertEquals("user-a", afterSignOut.pendingPurges.single().ownerUid)
        assertFalse(afterSignOut.mayContainPremiumData)

        val reSignedIn = AccessEpochTransitions.bindOwner(afterSignOut, "user-a", ids)
        assertNotEquals(before.userAccessEpoch, reSignedIn.userAccessEpoch)
        assertEquals(1, reSignedIn.pendingPurges.size)
    }

    @Test
    fun signOut_withNoOwner_isANoOp() {
        val empty = AccessEpochRecord()

        assertEquals(empty, AccessEpochTransitions.signOut(empty, ids))
    }

    @Test
    fun snapshotFacts_reportPendingPurgesPerAxis() {
        val krxOnly = AccessEpochTransitions.rotate(bound(), rotateUser = false, rotateKrx = true, ids = ids)

        val facts = krxOnly.toSnapshotFacts(PremiumAccessState.NoGrant, KrxCapabilityState.HIDDEN)

        assertFalse(facts.pendingUserPurge)
        assertTrue(facts.pendingCapabilityPurge)
    }

    @Test
    fun fence_tracksTheCurrentNamespace() {
        val before = bound()
        val after = AccessEpochTransitions.rotate(before, rotateUser = true, rotateKrx = false, ids = ids)

        assertNotEquals(before.fence(), after.fence())
    }
}
