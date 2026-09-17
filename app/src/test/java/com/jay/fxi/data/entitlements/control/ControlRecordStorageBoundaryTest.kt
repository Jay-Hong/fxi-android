package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ControlRecordStorageBoundaryTest {
    private val reader = ControlRecordReader()

    private fun readFile(file: File): Preferences = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).data.first()
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("d1-record").toFile()
        try { block(File(dir, "control.preferences_pb")) } finally { dir.deleteRecursively() }
    }

    @Test fun `missing file and existing zero byte file both require recovery and reading does not initialize controls`() = withFile { file ->
        val missing = readFile(file)
        assertTrue(reader.read(missing) is ControlRecordRead.MigrationOrRecoveryRequired)
        assertFalse(file.exists())
        file.writeBytes(byteArrayOf())
        val zeroByte = readFile(file)
        assertEquals(missing, zeroByte)
        assertTrue(reader.read(zeroByte) is ControlRecordRead.MigrationOrRecoveryRequired)
        assertTrue(file.exists())
        assertEquals(0L, file.length())
    }

    @Test fun `reopened supported record preserves ids opaque siblings and file bytes on repeated classification`() = withFile { file ->
        val raw = " [${ControlObligationFixtures.seal},{\"id\":\"future\",\"n\":1e-400}]\n"
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).edit {
                    it[intPreferencesKey("control_schema")] = 1
                    it[stringPreferencesKey("seal_v1")] = raw
                    it[stringPreferencesKey("demand_v1")] = "[]"
                    it[stringPreferencesKey("hold_v1")] = "[]"
                    it[stringPreferencesKey("recovery_intent_v1")] = "[]"
                    it[longPreferencesKey("read_barrier")] = 27L
                }
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }
        val before = file.readBytes()
        val reopened = readFile(file)
        repeat(3) {
            val result = reader.read(reopened) as ControlRecordRead.Supported
            assertTrue(result.hasUninterpretable)
            val entries = result.arrays.getValue(ControlKind.SEAL).entries
            assertEquals("s", (entries[0] as ControlEntryRead.Interpreted).value.id)
            assertTrue(entries[1] is ControlEntryRead.Uninterpretable)
            assertEquals(raw, result.original[stringPreferencesKey("seal_v1")])
            assertEquals(27L, result.original[longPreferencesKey("read_barrier")])
            assertEquals(reopened, result.original)
        }
        assertArrayEquals(before, file.readBytes())
        assertEquals(reopened, readFile(file))
    }

    @Test fun `wrong schema type from a real file is retained without a typed getter exception or rewrite`() = withFile { file ->
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).edit {
                    it[longPreferencesKey("control_schema")] = 1L
                    it[stringPreferencesKey("seal_v1")] = "[]"
                }
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }
        val before = file.readBytes()
        val prefs = readFile(file)
        val result = reader.read(prefs) as ControlRecordRead.Unreadable
        assertEquals(listOf(ControlRecordProblem.WrongType("control_schema")), result.problems)
        assertEquals(1L, result.original[longPreferencesKey("control_schema")])
        assertArrayEquals(before, file.readBytes())
    }
}
