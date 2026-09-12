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

    /** Slice 5: the landing is on the record, not only in the caller's memory of it. */
    @Test
    fun signOut_landing_givesUpTheOwner_whileTheJournalKeepsIt() {
        val before = bound()

        val after = AccessEpochTransitions.signOut(before, ids)

        assertNull("a landed sign-out leaves nobody bound", after.ownerUid)
        assertEquals("the journal still names whose namespace to purge", "user-a", after.pendingPurges.single().ownerUid)
        assertEquals(before.userAccessEpoch, after.pendingPurges.single().userAccessEpoch)
    }

    @Test
    fun beginSignOut_afterALandedSignOut_armsNothing() {
        val landed = AccessEpochTransitions.signOut(bound(), ids)

        assertEquals(landed, AccessEpochTransitions.beginSignOut(landed, "user-a"))
    }

    /** Retrying the end of a landed sign-out must not take down the namespace the first one minted. */
    @Test
    fun signOut_repeatedOnItsOwnResult_rotatesNothingMore() {
        val landed = AccessEpochTransitions.signOut(bound(), ids)

        val again = AccessEpochTransitions.signOut(landed, ids)

        assertEquals(landed, again)
        assertEquals(1, again.pendingPurges.size)
    }

    @Test
    fun settleOwedTeardown_thenTheSameUidBinds_rotatesOnce_andIsUnownedInBetween() {
        val owed = AccessEpochTransitions.beginSignOut(bound(), "user-a")

        val settled = AccessEpochTransitions.settleOwedTeardown(owed, ids)
        val rebound = AccessEpochTransitions.bindOwner(settled, "user-a", ids)

        assertNull("settling is a landing", settled.ownerUid)
        assertEquals("user-a", rebound.ownerUid)
        assertEquals("one teardown, one journal entry", 1, rebound.pendingPurges.size)
        assertNotEquals(owed.userAccessEpoch, rebound.userAccessEpoch)
    }

    /**
     * Nobody is bound, yet a marker says protected data may be here. The record does not establish
     * whose data it is, so it is retired rather than inherited — and journalled with no owner,
     * because the marker says nothing about who wrote it.
     */
    @Test
    fun bindOwner_unownedNamespaceWithAMarker_retiresItWithoutNamingTheArrivingUid() {
        val unowned = AccessEpochTransitions.markMayContainData(
            AccessEpochTransitions.ensureNamespace(AccessEpochRecord(), ids),
            premium = true,
            krx = false
        )

        val bound = AccessEpochTransitions.bindOwner(unowned, "user-b", ids)

        val entry = bound.pendingPurges.single()
        assertNull("the journal must not attribute unowned data to the arriving uid", entry.ownerUid)
        assertEquals(unowned.userAccessEpoch, entry.userAccessEpoch)
        assertEquals(unowned.krxCapabilityEpoch, entry.krxCapabilityEpoch)
        assertNotEquals(unowned.userAccessEpoch, bound.userAccessEpoch)
        assertFalse(bound.mayContainPremiumData)
        assertEquals("user-b", bound.ownerUid)
    }

    /** Either axis is enough: the KRX marker alone also says something is there to retire. */
    @Test
    fun bindOwner_unownedNamespaceWithOnlyTheKrxMarker_retiresItToo() {
        val unowned = AccessEpochTransitions.markMayContainData(
            AccessEpochTransitions.ensureNamespace(AccessEpochRecord(), ids),
            premium = false,
            krx = true
        )

        val bound = AccessEpochTransitions.bindOwner(unowned, "user-b", ids)

        val entry = bound.pendingPurges.single()
        assertNull(entry.ownerUid)
        assertEquals(unowned.krxCapabilityEpoch, entry.krxCapabilityEpoch)
        assertEquals(unowned.userAccessEpoch, entry.userAccessEpoch)
        assertFalse(bound.mayContainKrxData)
    }

    /** Control: a first install is unowned too, and allocating its namespace is not a teardown. */
    @Test
    fun bindOwner_unownedNamespaceWithoutMarkers_allocatesWithoutJournalling() {
        val landed = AccessEpochTransitions.signOut(bound(), ids)

        val bound = AccessEpochTransitions.bindOwner(landed, "user-b", ids)

        assertEquals("user-b", bound.ownerUid)
        assertEquals("the landing already retired it; binding adds nothing", 1, bound.pendingPurges.size)
        assertEquals(landed.userAccessEpoch, bound.userAccessEpoch)
        assertTrue(AccessEpochTransitions.bindOwner(AccessEpochRecord(), "user-a", ids).pendingPurges.isEmpty())
    }

    /**
     * Settling first changes nothing a binding does. This is the strongest form of "one teardown,
     * one rotation": since a landing gives up the owner, the owner change that would have rotated
     * again finds nobody there. It also says why a mutation that widens [AccessEpochTransitions]'s
     * settle branch to every owed marker is no longer observable — both orders end in one record.
     */
    @Test
    fun bindOwner_isTheSame_whetherAnOwedTeardownIsSettledFirstOrNot() {
        val owners = listOf(null, "user-a", "user-b")
        val owed = listOf(null, "user-a", "user-c")
        val markers = listOf(false to false, true to false, false to true, true to true)
        for (owner in owners) for (marker in markers) for (o in owed) for (uid in listOf("user-a", "user-b")) {
            val seeded = AccessEpochTransitions.markMayContainData(
                (owner?.let { bound(it) } ?: AccessEpochTransitions.ensureNamespace(AccessEpochRecord(), ids))
                    .copy(teardownOwedFor = o),
                premium = marker.first,
                krx = marker.second
            )
            val case = "owner=$owner owed=$o markers=$marker uid=$uid"

            // Two generators at the same point: equal records mean the same ids were minted in the
            // same order, not just the same shape.
            var directCounter = 0
            val direct = AccessEpochTransitions.bindOwner(seeded, uid, EpochIdGenerator { "x-${directCounter++}" })
            var settledCounter = 0
            val settledIds = EpochIdGenerator { "x-${settledCounter++}" }
            val settledFirst = AccessEpochTransitions.bindOwner(
                AccessEpochTransitions.settleOwedTeardown(seeded, settledIds),
                uid,
                settledIds
            )

            assertEquals(case, direct, settledFirst)
        }
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
