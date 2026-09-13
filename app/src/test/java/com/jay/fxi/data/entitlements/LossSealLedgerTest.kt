package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LossSealLedgerTest {

    private val live = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0", krxCapabilityEpoch = "k0")
    private val userTarget = LossTarget.of(live, PurgeScope.USER)
    private val krxTarget = LossTarget.of(live, PurgeScope.CAPABILITY)

    private fun entry(owner: String?, user: String?, krx: String?, vararg scopes: PurgeScope) =
        PendingPurge(owner, user, krx, scopes.toSet())

    private fun ledgerOn(record: AccessEpochRecord) = LossSealLedger().apply { observe(StoreOp.LOAD, null, record) }

    @Test
    fun targets_nameTheDecisionRecordsOwnerAxisAndEpoch_orItsMissingEpoch() {
        assertEquals(LossTarget.Namespace(LossObligation("A", PurgeScope.USER, "u0")), userTarget)
        assertEquals(LossTarget.Namespace(LossObligation("A", PurgeScope.CAPABILITY, "k0")), krxTarget)
        val incomplete = live.copy(krxCapabilityEpoch = null)
        assertEquals(LossTarget.NullNamespace(NullTargetSeal("A", PurgeScope.CAPABILITY)), LossTarget.of(incomplete, PurgeScope.CAPABILITY))
    }

    @Test
    fun anOpenObligationSealsItsAxisForItsOwnerOnly() {
        val ledger = ledgerOn(live)
        ledger.open(listOf(krxTarget))
        assertEquals(setOf(PurgeScope.CAPABILITY), ledger.sealedAxes("A"))
        assertEquals(emptySet<PurgeScope>(), ledger.sealedAxes("B"))
        assertFalse(ledger.isEmpty)
    }

    @Test
    fun observingARetirementDropsTheObligation_andALaterPurgeDoesNotReopenIt() {
        val ledger = ledgerOn(live)
        ledger.open(listOf(userTarget, krxTarget))
        val rotated = AccessEpochTransitions.rotate(live, rotateUser = true, rotateKrx = true, ids = sequenceIds())
        ledger.observe(StoreOp.BEGIN_ROTATION, live, rotated)
        assertTrue(ledger.isEmpty)
        assertEquals(emptySet<PurgeScope>(), ledger.sealedAxes("A"))

        val purged = AccessEpochTransitions.completePurges(rotated, rotated.pendingPurges)
        ledger.observe(StoreOp.COMPLETE_PURGES, rotated, purged)
        assertTrue(ledger.isEmpty)
    }

    @Test
    fun anEpochThatMovedWithoutAnEntryKeepsTheSeal() {
        val ledger = ledgerOn(live)
        ledger.open(listOf(userTarget))
        val emptied = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u9", krxCapabilityEpoch = "k9")
        ledger.observe(StoreOp.LOAD, null, emptied)
        assertEquals(ObligationStatus.UNKNOWN, ledger.status(LossObligation("A", PurgeScope.USER, "u0")))
        assertEquals(setOf(PurgeScope.USER), ledger.sealedAxes("A"))
    }

    @Test
    fun openingAnAlreadyRetiredObligationKeepsNothing() {
        val retired = live.copy(userAccessEpoch = "u1", pendingPurges = listOf(entry("A", "u0", null, PurgeScope.USER)))
        val ledger = ledgerOn(retired)
        ledger.open(listOf(userTarget))
        assertTrue(ledger.isEmpty)
    }

    @Test
    fun joins_onlyTheSameStillCurrentObligationOrTheSameNullSeal() {
        val ledger = ledgerOn(live)
        assertFalse(ledger.joins(userTarget))
        ledger.open(listOf(userTarget))
        assertTrue(ledger.joins(userTarget))
        assertFalse(ledger.joins(krxTarget))
        // The same obligation once its epoch has left the record is not joined: a new target is decided on a new record.
        ledger.observe(StoreOp.LOAD, null, live.copy(userAccessEpoch = "u9"))
        assertFalse(ledger.joins(userTarget))

        val nullSeal = LossTarget.NullNamespace(NullTargetSeal("A", PurgeScope.CAPABILITY))
        assertFalse(ledger.joins(nullSeal))
        ledger.open(listOf(nullSeal))
        assertTrue(ledger.joins(nullSeal))
    }

    @Test
    fun sealedAxes_derivesAMissingEpochOfTheSameOwner_withoutStoringIt() {
        val ledger = ledgerOn(live.copy(userAccessEpoch = null))
        assertEquals(setOf(PurgeScope.USER), ledger.sealedAxes("A"))
        assertEquals(emptySet<PurgeScope>(), ledger.sealedAxes("B"))
        assertTrue(ledger.isEmpty)
        ledger.observe(StoreOp.BIND_OWNER, null, live)
        assertEquals(emptySet<PurgeScope>(), ledger.sealedAxes("A"))
        // No owner, no derived seal: nobody is granted anything on an unowned record.
        assertEquals(emptySet<PurgeScope>(), ledgerOn(AccessEpochRecord()).sealedAxes(null))
    }

    @Test
    fun aNullTargetSealIsNotReleasedByAllocationOrByAnEntryThatWasAlreadyThere() {
        val incomplete = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0",
            pendingPurges = listOf(entry(null, null, null, PurgeScope.USER, PurgeScope.CAPABILITY)))
        val seal = NullTargetSeal("A", PurgeScope.CAPABILITY)
        val ledger = ledgerOn(incomplete)
        ledger.open(listOf(LossTarget.NullNamespace(seal)))

        val allocated = AccessEpochTransitions.bindOwner(incomplete, "A", sequenceIds())
        ledger.observe(StoreOp.BIND_OWNER, incomplete, allocated)
        assertEquals(setOf(seal), ledger.nullTargets())
        assertEquals(setOf(PurgeScope.CAPABILITY), ledger.sealedAxes("A"))

        ledger.observe(StoreOp.LOAD, null, allocated)
        assertEquals(setOf(seal), ledger.nullTargets())
    }

    @Test
    fun aNullTargetSealIsReleasedByAnIdentityRetirementThatLanded() {
        val incomplete = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0")
        val seal = NullTargetSeal("A", PurgeScope.CAPABILITY)
        for (retire in listOf<(AccessEpochRecord) -> Pair<StoreOp, AccessEpochRecord>>(
            { StoreOp.SIGN_OUT to AccessEpochTransitions.signOut(it, sequenceIds()) },
            { StoreOp.BIND_OWNER to AccessEpochTransitions.bindOwner(it, "B", sequenceIds()) },
            { StoreOp.RETIRE_UNVERIFIED_START to AccessEpochTransitions.retireUnverifiedStart(it, sequenceIds()) }
        )) {
            val ledger = ledgerOn(incomplete)
            ledger.open(listOf(LossTarget.NullNamespace(seal)))
            val (op, after) = retire(incomplete)
            ledger.observe(op, incomplete, after)
            assertEquals("$op", emptySet<NullTargetSeal>(), ledger.nullTargets())
        }
        // A load showing the same record is no evidence of the operation.
        val ledger = ledgerOn(incomplete)
        ledger.open(listOf(LossTarget.NullNamespace(seal)))
        ledger.observe(StoreOp.LOAD, incomplete, AccessEpochTransitions.signOut(incomplete, sequenceIds()))
        assertEquals(setOf(seal), ledger.nullTargets())
    }

    @Test
    fun releaseByRotation_needsTheOwnerBothSidesANewEpochAndAnUnknownEntry() {
        val seal = NullTargetSeal("A", PurgeScope.CAPABILITY)
        val before = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0")
        val after = AccessEpochTransitions.rotate(before, rotateUser = false, rotateKrx = true, ids = sequenceIds())

        fun attempt(b: AccessEpochRecord, a: AccessEpochRecord): Boolean =
            ledgerOn(b).apply { open(listOf(LossTarget.NullNamespace(seal))) }.releaseByRotation(seal, b, a)

        assertTrue(attempt(before, after))
        assertFalse("another owner before", attempt(before.copy(ownerUid = "B"), after))
        assertFalse("another owner after", attempt(before, after.copy(ownerUid = "B")))
        assertFalse("no new epoch", attempt(before, after.copy(krxCapabilityEpoch = null)))
        assertFalse("same epoch", attempt(before.copy(krxCapabilityEpoch = "k5"), after.copy(krxCapabilityEpoch = "k5")))
        assertFalse("no unknown entry", attempt(before, after.copy(pendingPurges = listOf(entry("A", null, "k0", PurgeScope.CAPABILITY)))))
        assertFalse("wrong axis entry", attempt(before, after.copy(pendingPurges = listOf(entry("A", null, null, PurgeScope.USER)))))
        assertFalse("other owner entry", attempt(before, after.copy(pendingPurges = listOf(entry("B", null, null, PurgeScope.CAPABILITY)))))
        assertTrue("unknown owner entry", attempt(before, after.copy(pendingPurges = listOf(entry(null, null, null, PurgeScope.CAPABILITY)))))
        assertFalse("not sealed", ledgerOn(before).releaseByRotation(seal, before, after))
    }

    @Test
    fun reapproval_isOwedOnlyOnceTheRegisteredAxesAreUnsealed_andOnlyOnce() {
        val ledger = ledgerOn(live)
        ledger.registerReapproval(binding = 1L, ownerUid = "A")
        assertNull("nothing sealed, nothing registered", ledger.takeReapproval(1L, "A"))

        ledger.open(listOf(krxTarget))
        ledger.registerReapproval(binding = 1L, ownerUid = "A")
        assertNull("still sealed", ledger.takeReapproval(1L, "A"))

        ledger.observe(StoreOp.BEGIN_ROTATION, live, AccessEpochTransitions.rotate(live, rotateUser = false, rotateKrx = true, ids = sequenceIds()))
        assertEquals(setOf(PurgeScope.CAPABILITY), ledger.takeReapproval(1L, "A"))
        assertNull("taken once", ledger.takeReapproval(1L, "A"))
    }

    @Test
    fun reapproval_ofAnEndedBindingIsForgotten_notHandedToTheNext() {
        val ledger = ledgerOn(live)
        ledger.open(listOf(userTarget))
        ledger.registerReapproval(binding = 1L, ownerUid = "A")
        ledger.observe(StoreOp.BEGIN_ROTATION, live, AccessEpochTransitions.rotate(live, rotateUser = true, rotateKrx = false, ids = sequenceIds()))

        assertNull("binding 2 was never owed", ledger.takeReapproval(2L, "A"))
        assertNull("binding 1 was forgotten when binding 2 was current", ledger.takeReapproval(1L, "A"))
    }

    @Test
    fun reapproval_derivedEpochSealOwesNothing() {
        val ledger = ledgerOn(live.copy(userAccessEpoch = null))
        ledger.registerReapproval(binding = 1L, ownerUid = "A")
        assertNull(ledger.takeReapproval(1L, "A"))
    }

    private var counter = 0
    private fun sequenceIds() = EpochIdGenerator { "id-${counter++}" }
}
