package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FloorStandaloneWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private var storage: ControlStoreTestStorage? = null
    @After fun close() = runReleaseTest { storage?.close(); Unit }
    private suspend fun preserved(id: String, wait: Long, now: BootReading, expected: Long) {
        val s = ControlStoreTestStorage(folder.newFile()).also { storage = it }
        val old = F.guard(F.floor(wait), true)
        val raw = F.raw(old, DemandAuthFixtures.request(owner = "departed"), hold = F.hold()).toMutablePreferences().apply {
            this[DataStoreAccessEpochStore.OWNER_UID] = "another-owner"
        }
        controlTestTimeout("floor seed") { s.data.updateData { raw } }
        val c = s.control.prepare(s.control.recordFloor(old, now, 0, F.origin))
        val result = controlTestTimeout("standalone floor") { s.control.execute(c) }
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        val after = s.raw().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }
        val g = (F.read(after).locations("g").single().second as ControlEntryRead.Interpreted).original
        assertEquals(F.atomic(id), FloorV1(now.bootId, now.elapsedMillis, expected, F.origin), guard(g)!!.floor)
        assertEquals(F.atomic(id), old.toPayloadEntry().fields["auth"].toString(), g.toPayloadEntry().fields["auth"].toString())
        assertEquals(raw[ControlRecordKeys.payload(ControlKind.HOLD)], after[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertArrayEquals(raw[byteArrayPreferencesKey("lifecycle-external")], after[byteArrayPreferencesKey("lifecycle-external")])
        assertEquals("another-owner", after[DataStoreAccessEpochStore.OWNER_UID])
        assertFalse(F.read(after).locations("r").isEmpty())
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (controlTestTimeout("floor fixed retry") { s.control.execute(c) } as ControlStoreResult.Confirmed).effect)
    }
    @Test fun F08a() = runReleaseTest { preserved("F08a_standalone", 30000, BootReading("boot", 40000), 0) }
    @Test fun F08b() = runReleaseTest { preserved("F08b_standalone", 30000, BootReading(null, 99999), 30000) }
    @Test fun F08c() = runReleaseTest { preserved("F08c_standalone", 30000, BootReading("other", 99999), 30000) }
    @Test fun F08d() = runReleaseTest { preserved("F08d_standalone", 30000, BootReading("boot", 9999), 30000) }
    @Test fun zero() = runReleaseTest { preserved("F08.zero", 0, BootReading(null, 99999), 0) }
    @Test fun one() = runReleaseTest { preserved("F08.one", 1, BootReading("boot", 10000), 1) }
    @Test fun max() = runReleaseTest { preserved("F08.max", Long.MAX_VALUE, BootReading(null, Long.MAX_VALUE), Long.MAX_VALUE) }
    @Test fun plannerPreservesAuthRawKeyOrderAndNumberLiteral() {
        val auth = """{"authStopAppliedOrder":-0,"authStateOrder":-0,"authStopped":false,"originLifetimeId":"auth-old","binding":-0,"authGeneration":-0,"ownerUid":"departed"}"""
        val before = F.node("""{"id":"g","kind":"SCHEDULE_GUARD","auth":$auth}""")
        assertNotNull(guard(before))
        val result = checkNotNull(HoldFloorPlan.prepare(F.input(old = before)))
        assertEquals(F.atomic("C08_raw"), auth, result.guardAfter!!.toPayloadEntry().fields["auth"].toString())
        assertEquals("new-life", guard(result.guardAfter)!!.floor!!.originLifetimeId.value)
        assertEquals("auth-old", guard(result.guardAfter)!!.auth!!.originLifetimeId.value)
    }
}
