package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LossObligationsTest {

    private val userObligation = LossObligation(ownerUid = "A", axis = PurgeScope.USER, epoch = "u0")
    private val krxObligation = LossObligation(ownerUid = "A", axis = PurgeScope.CAPABILITY, epoch = "k0")

    private fun entry(owner: String?, user: String?, krx: String?, vararg scopes: PurgeScope) =
        PendingPurge(ownerUid = owner, userAccessEpoch = user, krxCapabilityEpoch = krx, scopes = scopes.toSet())

    /** Every owner × epoch × scope combination for both axes, against one table. */
    @Test
    fun covers_everyCombination() {
        val owners = listOf(null, "A", "B")
        val epochs = listOf(null, "match", "other")
        val scopeSets = listOf(emptySet(), setOf(PurgeScope.USER), setOf(PurgeScope.CAPABILITY), AccessEpochTransitions.ALL_SCOPES)
        for (obligation in listOf(userObligation, krxObligation)) {
            for (owner in owners) for (epoch in epochs) for (scopes in scopeSets) {
                val entryEpoch = when (epoch) { null -> null; "match" -> obligation.epoch; else -> "zz" }
                val candidate = PendingPurge(
                    ownerUid = owner,
                    userAccessEpoch = if (obligation.axis == PurgeScope.USER) entryEpoch else "x",
                    krxCapabilityEpoch = if (obligation.axis == PurgeScope.CAPABILITY) entryEpoch else "x",
                    scopes = scopes
                )
                val expected = obligation.axis in scopes && (owner == null || owner == "A") && (epoch == null || epoch == "match")
                assertEquals("$obligation vs $candidate", expected, LossObligations.covers(candidate, obligation))
            }
        }
    }

    @Test
    fun judge_isStillCurrentWhileTheEpochIsLive_evenWithACoveringEntry() {
        val record = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0", krxCapabilityEpoch = "k0",
            pendingPurges = listOf(entry(null, null, null, PurgeScope.USER, PurgeScope.CAPABILITY)))
        assertEquals(ObligationStatus.STILL_CURRENT, LossObligations.judge(userObligation, record))
        assertEquals(ObligationStatus.STILL_CURRENT, LossObligations.judge(krxObligation, record))
    }

    @Test
    fun judge_isRetiredOnlyWhenTheCleanupWasHandedOn() {
        val moved = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u1", krxCapabilityEpoch = "k1")
        assertEquals(ObligationStatus.UNKNOWN, LossObligations.judge(userObligation, moved))

        val journalled = moved.copy(pendingPurges = listOf(entry("A", "u0", null, PurgeScope.USER)))
        assertEquals(ObligationStatus.RETIRED, LossObligations.judge(userObligation, journalled))
        // The KRX axis was not journalled by that entry.
        assertEquals(ObligationStatus.UNKNOWN, LossObligations.judge(krxObligation, journalled))
    }

    @Test
    fun judge_anEmptiedRecordDoesNotRetire() {
        val lost = AccessEpochRecord(ownerUid = "A")
        assertEquals(ObligationStatus.UNKNOWN, LossObligations.judge(userObligation, lost))
        val reallocated = lost.copy(userAccessEpoch = "u9", krxCapabilityEpoch = "k9")
        assertEquals(ObligationStatus.UNKNOWN, LossObligations.judge(userObligation, reallocated))
    }

    @Test
    fun judge_anotherOwnersEntryForTheSameEpochDoesNotRetire() {
        val record = AccessEpochRecord(ownerUid = "B", userAccessEpoch = "u1",
            pendingPurges = listOf(entry("B", "u0", null, PurgeScope.USER)))
        assertEquals(ObligationStatus.UNKNOWN, LossObligations.judge(userObligation, record))
    }

    @Test
    fun journalRetired_leavesALiveNamespaceAlone() {
        val live = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u0", krxCapabilityEpoch = "k1")
        assertSame(live, AccessEpochTransitions.journalRetired(live, userObligation))
    }

    @Test
    fun journalRetired_addsNothingWhenAnEntryAlreadyCovers() {
        val covered = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u1",
            pendingPurges = listOf(entry(null, null, null, PurgeScope.USER)))
        assertSame(covered, AccessEpochTransitions.journalRetired(covered, userObligation))
    }

    @Test
    fun journalRetired_appendsOneEntryForTheRememberedTargetOnly() {
        val lost = AccessEpochRecord(ownerUid = "A", userAccessEpoch = "u9", krxCapabilityEpoch = "k9",
            pendingPurges = listOf(entry("B", "b0", "b1", PurgeScope.USER, PurgeScope.CAPABILITY)))

        val user = AccessEpochTransitions.journalRetired(lost, userObligation)
        assertEquals(lost.copy(pendingPurges = lost.pendingPurges + entry("A", "u0", null, PurgeScope.USER)), user)
        assertEquals(ObligationStatus.RETIRED, LossObligations.judge(userObligation, user))

        val krx = AccessEpochTransitions.journalRetired(lost, krxObligation)
        assertEquals(lost.copy(pendingPurges = lost.pendingPurges + entry("A", null, "k0", PurgeScope.CAPABILITY)), krx)
        assertEquals(ObligationStatus.RETIRED, LossObligations.judge(krxObligation, krx))
    }
}
