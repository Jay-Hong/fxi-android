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

    // ---- an owed sign-out (L-4b-2) ---------------------------------------------------------
    //
    // Sign-out writes its rotation and journal entry in one edit. When that edit does not land,
    // the record is indistinguishable from "signed in, sign-out never tried", so the intent has to
    // be written first and discharged later. These pin the pure half of that.

    @Test
    fun beginSignOut_recordsTheOwner_andTouchesNothingElse() {
        val before = bound()

        val after = AccessEpochTransitions.beginSignOut(before, "user-a")

        assertEquals("user-a", after.teardownOwedFor)
        assertEquals("nothing else may move", before, after.copy(teardownOwedFor = null))
    }

    @Test
    fun beginSignOut_onAnUnboundRecord_isANoOp() {
        val unbound = AccessEpochRecord()

        assertEquals(unbound, AccessEpochTransitions.beginSignOut(unbound, "user-a"))
    }

    @Test
    fun signOut_clearsTheOwedMarker_inTheSameRecordThatRotates() {
        val owed = AccessEpochTransitions.beginSignOut(bound(), "user-a")

        val after = AccessEpochTransitions.signOut(owed, ids)

        assertNull(after.teardownOwedFor)
        assertNotEquals(owed.userAccessEpoch, after.userAccessEpoch)
        assertEquals(owed.userAccessEpoch, after.pendingPurges.single().userAccessEpoch)
    }

    @Test
    fun signOut_withNoOwner_stillDropsAStaleMarker() {
        val stale = AccessEpochRecord(teardownOwedFor = "user-a")

        val after = AccessEpochTransitions.signOut(stale, ids)

        assertNull(after.teardownOwedFor)
        assertTrue("an unbound record has nothing to rotate", after.pendingPurges.isEmpty())
    }

    @Test
    fun settleOwedTeardown_forTheCurrentOwner_rotatesAndClears() {
        val owed = AccessEpochTransitions.beginSignOut(bound(), "user-a")

        val settled = AccessEpochTransitions.settleOwedTeardown(owed, ids)

        assertNull(settled.teardownOwedFor)
        assertNotEquals(owed.userAccessEpoch, settled.userAccessEpoch)
        assertNotEquals(owed.krxCapabilityEpoch, settled.krxCapabilityEpoch)
        val entry = settled.pendingPurges.single()
        assertEquals("user-a", entry.ownerUid)
        assertEquals(owed.userAccessEpoch, entry.userAccessEpoch)
        assertEquals(owed.krxCapabilityEpoch, entry.krxCapabilityEpoch)
    }

    @Test
    fun settleOwedTeardown_withNothingOwed_isANoOp() {
        val clean = bound()

        assertEquals(clean, AccessEpochTransitions.settleOwedTeardown(clean, ids))
    }

    /** A crash between settling and anything after it must not rotate a second time. */
    @Test
    fun settleOwedTeardown_isSafeToRepeat() {
        val once = AccessEpochTransitions.settleOwedTeardown(AccessEpochTransitions.beginSignOut(bound(), "user-a"), ids)

        val twice = AccessEpochTransitions.settleOwedTeardown(once, ids)

        assertEquals(once, twice)
        assertEquals(1, twice.pendingPurges.size)
    }

    @Test
    fun settleOwedTeardown_namingSomeoneOtherThanTheOwner_dropsTheMarkerWithoutRotating() {
        val stale = bound("user-b").copy(teardownOwedFor = "user-a")

        val after = AccessEpochTransitions.settleOwedTeardown(stale, ids)

        assertNull(after.teardownOwedFor)
        assertEquals("the owner's namespace is not the one owed", stale.userAccessEpoch, after.userAccessEpoch)
        assertTrue(after.pendingPurges.isEmpty())
    }

    /**
     * The failure the marker exists for: the same uid signs in again after a sign-out that never
     * landed. Without the settle, the same-owner branch hands it back the namespace it was leaving.
     */
    @Test
    fun bindOwner_sameUidAfterAnUnfinishedSignOut_getsANewNamespace() {
        val owed = AccessEpochTransitions.beginSignOut(bound(), "user-a")

        val rebound = AccessEpochTransitions.bindOwner(owed, "user-a", ids)

        assertNull(rebound.teardownOwedFor)
        assertNotEquals("the old namespace must not be inherited", owed.userAccessEpoch, rebound.userAccessEpoch)
        assertNotEquals(owed.krxCapabilityEpoch, rebound.krxCapabilityEpoch)
        assertEquals(owed.userAccessEpoch, rebound.pendingPurges.single().userAccessEpoch)
    }

    /** A different uid retires the namespace through the owner change — once, not twice. */
    @Test
    fun bindOwner_differentUidAfterAnUnfinishedSignOut_journalsThePreviousOwnerOnce() {
        val owed = AccessEpochTransitions.beginSignOut(bound("user-a"), "user-a")

        val switched = AccessEpochTransitions.bindOwner(owed, "user-b", ids)

        assertNull(switched.teardownOwedFor)
        assertEquals("user-b", switched.ownerUid)
        val entry = switched.pendingPurges.single()
        assertEquals("user-a", entry.ownerUid)
        assertEquals(owed.userAccessEpoch, entry.userAccessEpoch)
        assertFalse(
            "the new owner's namespace is never journalled",
            switched.pendingPurges.any { it.userAccessEpoch == switched.userAccessEpoch }
        )
    }

    /** No marker, no change: same-uid cold-start continuity is what the plan preserves. */
    @Test
    fun bindOwner_sameUidWithNothingOwed_keepsTheNamespace() {
        val clean = bound()

        assertEquals(clean, AccessEpochTransitions.bindOwner(clean, "user-a", ids))
    }

    /**
     * Why the marker holds the uid and not the epochs it saw.
     *
     * A KRX revoke rotates one axis on its own. Settling only when the recorded epochs still match
     * would skip here and leave the user axis — the one a returning sign-in would inherit — live.
     */
    @Test
    fun settleOwedTeardown_afterAKrxOnlyRotation_stillRetiresTheUserAxis() {
        val owed = AccessEpochTransitions.beginSignOut(bound(), "user-a")
        val drifted = AccessEpochTransitions.rotate(owed, rotateUser = false, rotateKrx = true, ids = ids)

        val settled = AccessEpochTransitions.settleOwedTeardown(drifted, ids)

        assertNull(settled.teardownOwedFor)
        assertNotEquals(owed.userAccessEpoch, settled.userAccessEpoch)
        assertTrue(
            "the user axis the marker was written against is journalled",
            settled.pendingPurges.any { it.userAccessEpoch == owed.userAccessEpoch }
        )
    }

    /** The captured account, not whoever the record happens to name while the binder lags. */
    @Test
    fun beginSignOut_forAUidThatDoesNotOwnTheRecord_armsNothing() {
        val ownedByB = bound("user-b")

        assertEquals(ownedByB, AccessEpochTransitions.beginSignOut(ownedByB, "user-a"))
    }

    @Test
    fun beginSignOut_twice_isTheSameAsOnce() {
        val once = AccessEpochTransitions.beginSignOut(bound(), "user-a")

        assertEquals(once, AccessEpochTransitions.beginSignOut(once, "user-a"))
    }

    /** The owner change retires the user axis the marker was written against, even after a KRX drift. */
    @Test
    fun bindOwner_differentUidAfterAKrxOnlyRotation_stillRetiresTheUserAxisOnce() {
        val owed = AccessEpochTransitions.beginSignOut(bound("user-a"), "user-a")
        val drifted = AccessEpochTransitions.rotate(owed, rotateUser = false, rotateKrx = true, ids = ids)

        val switched = AccessEpochTransitions.bindOwner(drifted, "user-b", ids)

        assertNull(switched.teardownOwedFor)
        assertEquals(
            "user axis journalled exactly once",
            1,
            switched.pendingPurges.count { it.userAccessEpoch == owed.userAccessEpoch }
        )
    }

    @Test
    fun teardownOwedFor_isNotAnAccessFact() {
        val clean = bound()
        val owed = AccessEpochTransitions.beginSignOut(clean, "user-a")

        assertEquals(clean.fence(), owed.fence())
        assertEquals(
            clean.toSnapshotFacts(PremiumAccessState.NoGrant, KrxCapabilityState.HIDDEN),
            owed.toSnapshotFacts(PremiumAccessState.NoGrant, KrxCapabilityState.HIDDEN)
        )
    }
}
