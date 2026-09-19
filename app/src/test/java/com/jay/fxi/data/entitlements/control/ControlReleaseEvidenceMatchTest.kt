package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.fixture
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.row
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.wire
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.read
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.raw
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.bind
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.pending
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Each wire-compatible mismatch reaches the exact comparison; index uses a pure synthetic row. */
class ControlReleaseEvidenceMatchTest {
    private val tracked = fixture()
    private val command = tracked.command
    private val expected = row(command)
    private fun mismatch(actual: AppliedEvidence, fixed: AppliedEvidence? = expected) {
        assertTrue("baseline exact row", ControlReleaseEvidenceMatch.matches(command, expected, expected))
        assertFalse("release exact linkage must reject", ControlReleaseEvidenceMatch.matches(command, fixed, actual))
    }

    @Test fun B17_nullExpectedIsNotWildcard() { mismatch(expected, null) }
    @Test fun B09_expectedRotationIsNotMutations() { mismatch(expected, AppliedEvidence.Rotation(command.id, command.ownerTrackingLifetimeId.value, listOf("s"), "d")) }
    @Test fun B08_expectedMustBelongToCommandLifetime() { mismatch(row(command, lifetime = ReclamationFixtures.oldLife), row(command, lifetime = ReclamationFixtures.oldLife)) }
    @Test fun B08_expectedMustBelongToCommandId() { mismatch(row(command, id = "other"), row(command, id = "other")) }
    @Test fun B09_actualRotationIsNotMutations() { mismatch(AppliedEvidence.Rotation(command.id, command.ownerTrackingLifetimeId.value, listOf("s"), "d")) }
    @Test fun B08_actualLifetimeDiffers() { mismatch(row(command, lifetime = ReclamationFixtures.oldLife)) }
    @Test fun B08_actualCommandIdDiffers() { mismatch(row(command, id = "other")) }
    @Test fun B10_targetCountDiffers() { mismatch(row(command, targets = expected.targets + AppliedTarget(2, ControlKind.HOLD, "h", false, false))) }
    @Test fun B15_indexDiffers() { mismatch(row(command, targets = listOf(expected.targets[0].copy(index = 1), expected.targets[1]))) }
    @Test fun B11_kindDiffers() { mismatch(row(command, targets = listOf(expected.targets[0].copy(kind = ControlKind.HOLD), expected.targets[1]))) }
    @Test fun B12_idDiffers() { mismatch(row(command, targets = listOf(expected.targets[0].copy(id = "other"), expected.targets[1]))) }
    @Test fun B13_joinedDiffers() { mismatch(row(command, targets = listOf(expected.targets[0], expected.targets[1].copy(joined = true)))) }
    @Test fun B14_writtenDiffers() { mismatch(row(command, targets = listOf(expected.targets[0], expected.targets[1].copy(written = true)))) }
    @Test fun B10_emptyTargetsDoNotMatch() { mismatch(row(command, targets = emptyList())) }
    @Test fun A09_rotationBodyCannotMatchMutations() {
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val rotation = CommandRef("op", ControlCommandBody.RotateAndSettle(input), command.ownerTrackingLifetimeId)
        val matchingIds = row(rotation)
        assertFalse("body must be mutations", ControlReleaseEvidenceMatch.matches(rotation, matchingIds, matchingIds))
    }
    @Test fun B18_exactRowsMatchByValue() {
        val copy = row(command, targets = expected.targets.map { it.copy() })
        assertNotSame(expected, copy)
        assertTrue(ControlReleaseEvidenceMatch.matches(command, expected, copy))
    }
}
