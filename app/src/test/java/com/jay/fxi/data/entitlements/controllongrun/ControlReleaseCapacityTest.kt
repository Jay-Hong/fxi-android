package com.jay.fxi.data.entitlements.controllongrun

import com.jay.fxi.data.entitlements.control.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual no-release accumulation; outside control.* because every mutant need not fill a file. */
class ControlReleaseCapacityTest {
    @get:Rule val folder = TemporaryFolder()

    private fun capacity(profile: ReleaseStringProfile) = runBlocking {
        controlTestTimeout("FileStorage measured capacity ${profile.name}", 120_000) {
            val started = System.nanoTime()
            val fixture = ReleaseAccumulationFixture(File(folder.root, "capacity.preferences_pb"), profile)
            try {
                fixture.seed(withControls = false)
                var successes = 0
                while (true) {
                    val last = fixture.disk()
                    val fileBytes = fixture.file.readBytes()
                    val writes = fixture.storage.storage.writes
                    val command = fixture.prepareFloor(last)
                    val result = fixture.execute(command)
                    if (result is ControlStoreResult.Confirmed) {
                        successes++
                        val current = fixture.disk()
                        assertNotEquals("real floor change", last[ControlStoreTestStorage.DEMAND], current[ControlStoreTestStorage.DEMAND])
                        assertEquals(successes, fixture.rows(current).size)
                        assertNotNull(fixture.own(current, command))
                        assertEquals(successes, ControlReleaseFixtures.commands(fixture.tracker).size)
                        assertTrue(fixture.tracker.executing.isEmpty())
                        assertTrue(fixture.tracker.recoverySnapshot().unresolvedCommands.isEmpty())
                        assertTrue(fixture.tracker.recoverySnapshot().pendingReleases.isEmpty())
                        fixture.footprint(current)
                        continue
                    }
                    assertTrue("first rejection must be TooLarge: $result", result is ControlStoreResult.Rejected)
                    val reason = (result as ControlStoreResult.Rejected).reason
                    assertTrue(reason is RejectionReason.TooLarge)
                    reason as RejectionReason.TooLarge
                    assertEquals(ControlPayloadKey.COMMAND_EVIDENCE, reason.payloadKey)
                    assertEquals(ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES, reason.limit)
                    assertTrue(reason.bytes > reason.limit)
                    assertTrue(successes > 0)
                    assertEquals("rejection preserves whole Preferences", last.toMutablePreferences(), fixture.disk().toMutablePreferences())
                    assertArrayEquals("rejection preserves exact file", fileBytes, fixture.file.readBytes())
                    assertEquals("no rejected partial write", writes, fixture.storage.storage.writes)
                    assertNull(fixture.own(last, command))
                    assertEquals(successes, fixture.rows(last).size)
                    val lastSize = fixture.footprint(last)
                    // Optional evidence export; ordinary Gradle/JUnit runs need no output directory.
                    System.getProperty("fxi.release.evidenceDir")?.let { directory ->
                        File(directory, "capacity-${profile.name}-last.preferences_pb").writeBytes(fileBytes)
                    }
                    // This is explicitly a synthetic snapshot from another tracker. It restores no
                    // command authority and does not claim to replay that file's creation history.
                    val synthetic = ReleaseAccumulationFixture(File(folder.root, "synthetic.preferences_pb"), profile)
                    try {
                        controlTestTimeout("seed measured last-allowed snapshot") { synthetic.storage.data.updateData { last } }
                        assertNotSame(fixture.tracker.lifetimeId, synthetic.tracker.lifetimeId)
                        assertTrue(ControlReleaseFixtures.commands(synthetic.tracker).isEmpty())
                        val copiedBytes = synthetic.file.readBytes()
                        val copiedWrites = synthetic.storage.storage.writes
                        val next = synthetic.prepareFloor(last, (successes + 1).toLong())
                        val rejected = synthetic.execute(next)
                        assertTrue(rejected is ControlStoreResult.Rejected)
                        assertEquals("same measured next candidate size", reason, (rejected as ControlStoreResult.Rejected).reason)
                        assertEquals(last.toMutablePreferences(), synthetic.disk().toMutablePreferences())
                        assertArrayEquals(copiedBytes, synthetic.file.readBytes())
                        assertEquals(copiedWrites, synthetic.storage.storage.writes)
                        assertNull(synthetic.own(synthetic.disk(), next))
                    } finally { synthetic.close() }
                    println("CAPACITY {\"profile\":\"${profile.name}\",\"lastSuccess\":$successes," +
                        "\"firstTooLarge\":${successes + 1},\"candidateBytes\":${reason.bytes},\"limit\":${reason.limit}," +
                        "\"lastSnapshot\":${lastSize.json()},\"elapsedNanos\":${System.nanoTime() - started}}")
                    break
                }
            } finally { fixture.close() }
        }
    }

    @Test fun shortAsciiMeasuredLimit() = capacity(ReleaseStringProfile.SHORT_ASCII)
    @Test fun longAsciiMeasuredLimit() = capacity(ReleaseStringProfile.LONG_ASCII)
    @Test fun koreanAndSupplementaryPlaneMeasuredLimit() = capacity(ReleaseStringProfile.UTF8)
    @Test fun quotesAndBackslashesMeasuredLimit() = capacity(ReleaseStringProfile.ESCAPED)
}
