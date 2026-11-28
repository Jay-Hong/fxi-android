package com.jay.fxi.data.entitlements

import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeletionRecordStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = AuthIdentityFence("A", 1)
    private val other = AuthIdentityFence("B", 2)
    private val admission = DeletionAdmissionStore()
    private val file get() = File(temp.root, "account_deletion/v1.pb")
    private var fault: Fault? = null
    private val store by lazy {
        DeletionRecordStore({ temp.root }, admission) { file ->
            FaultStorage(FileStorage(DeletionFileSerializer) { file }, file) { fault }
        }
    }

    private suspend fun reopened() = DeletionRecordStore({ temp.root }, DeletionAdmissionStore()).read()
    private suspend fun activeAfterRestart(record: DeletionRecord) {
        assertEquals(DeletionSnapshot.Active(record), reopened())
    }

    @Test fun reopenPreservesFourPhasesAndOriginalOperation() = runBlocking {
        assertEquals(DeletionSnapshot.Closed, admission.deletionSnapshot.value)
        assertEquals(DeletionSnapshot.Empty, store.read())
        assertFalse(file.exists())
        val q = checkNotNull(store.begin(owner))
        activeAfterRestart(q)
        val sent = checkNotNull(store.markSending(q, owner))
        val s = checkNotNull(store.serverDeleted(sent, sent))
        activeAfterRestart(s)
        val f = checkNotNull(store.startSdk(s, owner))
        activeAfterRestart(f)
        val d = checkNotNull(store.sdkResult(f, f, true))
        activeAfterRestart(d)
        assertEquals(DeletionPhase.entries, listOf(q, s, f, d).map { it.phase })
        assertTrue(listOf(s, f, d).all { it.operationId == q.operationId && it.createdUtcMillis == q.createdUtcMillis })
        assertFalse(admission.blocks(owner.uid))
    }

    @Test fun duplicateBeginsAndRacingSendsHaveOneWinner() = runBlocking {
        store.read()
        val taps = List(8) { async(Dispatchers.IO) { store.begin(owner) } }.awaitAll()
        val q = checkNotNull(taps.distinct().single())
        assertNull(store.begin(other))
        assertFalse(store.cancelBeforeSend(q, other))
        val second = DeletionRecordStore({ temp.root }, DeletionAdmissionStore())
        assertEquals(DeletionSnapshot.Active(q), second.read())
        val attempts = List(8) { index ->
            async(Dispatchers.IO) { (if (index % 2 == 0) store else second).markSending(q, owner) }
        }.awaitAll().filterNotNull()
        assertTrue("S104_SINGLE_SEND", attempts.size == 1)
        val sent = attempts.single()
        assertFalse(store.cancelBeforeSend(sent, owner))
        val retry = checkNotNull(store.markSending(sent, AuthIdentityFence("A", 3)))
        assertNull(store.serverDeleted(retry, sent))
        val switched = checkNotNull(store.observe(retry, true, other.uid))
        assertNull(store.serverDeleted(retry, retry))
        assertNull(store.markSending(switched, other))
        val s = checkNotNull(store.serverDeleted(switched, retry))
        assertEquals(owner.uid, s.ownerUid)
        assertNull(store.startSdk(s, other))
    }

    @Test fun unsentReleaseCannotBeReplayedAgainstANewOperation() = runBlocking {
        store.read()
        val old = checkNotNull(store.begin(owner))
        assertTrue(store.cancelBeforeSend(old, owner))
        assertEquals(DeletionSnapshot.Empty, reopened())
        val next = checkNotNull(store.begin(owner))
        assertNotEquals(old.operationId, next.operationId)
        assertFalse(store.cancelBeforeSend(old, owner))
        assertNull(store.markSending(old, owner))
    }

    @Test fun explicitSignOutAndReplacementInvalidateOnlyTheOldSdkAttempt() = runBlocking {
        store.read()
        val q = checkNotNull(store.begin(owner))
        val sent = checkNotNull(store.markSending(q, owner))
        val s = checkNotNull(store.serverDeleted(sent, sent))
        val f = checkNotNull(store.startSdk(s, owner))
        val restoring = checkNotNull(store.observe(f, false, null))
        assertNull(store.confirmRestoredNull(restoring))
        val logout = checkNotNull(store.observe(restoring, true, null, externalSignOut = true))
        assertNull(store.confirmRestoredNull(logout))
        val b = checkNotNull(store.observe(logout, true, other.uid))
        val a = checkNotNull(store.observe(b, true, owner.uid))
        val absent = checkNotNull(store.observe(a, true, null))
        assertEquals(owner.uid, absent.lastAuthUid)
        assertNull(store.confirmRestoredNull(absent))
        val retry = checkNotNull(store.startSdk(absent, AuthIdentityFence("A", 3)))
        assertNull(store.sdkResult(retry, f, true))
        val failed = checkNotNull(store.sdkResult(retry, retry, false))
        assertNull(store.confirmRestoredNull(checkNotNull(store.observe(failed, true, null))))
        val current = (store.read() as DeletionSnapshot.Active).record
        val pending = checkNotNull(store.startSdk(current, owner))
        val missing = checkNotNull(store.observe(pending, true, null))
        assertEquals(DeletionPhase.FIREBASE_DELETED, store.confirmRestoredNull(missing)?.phase)
    }

    @Test fun interruptedWriteRequiresPhysicalRereadBeforeRetry() = runBlocking {
        store.read()
        fault = Fault.BEFORE_WRITE
        assertNull(store.begin(owner))
        assertEquals(DeletionSnapshot.IoFailure, admission.deletionSnapshot.value)
        assertNull(store.begin(owner))
        fault = null
        assertEquals(DeletionSnapshot.Empty, store.read())
        val q = checkNotNull(store.begin(owner))
        val original = file.readBytes()
        for (point in listOf(Fault.BEFORE_RENAME, Fault.RENAME_FAILURE, Fault.AFTER_RENAME)) {
            fault = point
            assertNull(store.markSending(q, owner))
            assertEquals(DeletionSnapshot.IoFailure, admission.deletionSnapshot.value)
            fault = null
            assertNull(store.markSending(q, owner))
            val recovered = (store.read() as DeletionSnapshot.Active).record
            if (point == Fault.AFTER_RENAME) {
                assertTrue("S104_RENAME_COMMITTED", recovered.serverAttempt == 1L)
                assertEquals(q.operationId, recovered.operationId)
            } else {
                assertEquals(q, recovered)
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    @Test fun fixedVersionAndDamageFixturesPreserveBytesUntilReread() = runBlocking {
        file.parentFile!!.mkdirs()
        val emptyV1 = hex("0a700a0864656c6574696f6e12642a62393261613663363430313631333764666439393134366538343537363564636466306235623562653766636263613032373766373136303137316236666464610a7b22736368656d6156657273696f6e223a312c227265636f7264223a6e756c6c7d")
        val older = hex("0a700a0864656c6574696f6e12642a62626136303031643232303934326637313832653938323866393838643734323138353763396131346135383062666338653437336564653561373761643034660a7b22736368656d6156657273696f6e223a302c227265636f7264223a6e756c6c7d")
        val future = hex("0a700a0864656c6574696f6e12642a62326534323362303362303664363636396338633734626135356239373033336239633362346636333630353238303762343535663036353430376337316235350a7b22736368656d6156657273696f6e223a322c227265636f7264223a6e756c6c7d")
        for (bytes in listOf(older, future, hex("0a7f00"), byteArrayOf())) {
            file.writeBytes(bytes)
            val failure = store.read()
            assertTrue("S104_CORRUPT_CLOSED", failure is DeletionSnapshot.Corrupt)
            if (bytes === older || bytes === future) assertTrue((failure as DeletionSnapshot.Corrupt).reason.contains("version"))
            assertNull(store.begin(owner))
            assertEquals(failure, reopened())
            assertArrayEquals(bytes, file.readBytes())
        }
        file.writeBytes(emptyV1)
        assertEquals(DeletionSnapshot.Empty, store.read())
        val q = checkNotNull(store.begin(owner))
        val good = file.readBytes()
        // Equal-length substitutions keep the protobuf framing intact.
        for ((field, bad) in listOf("REQUESTING_SERVER" to "FUTURE_PHASE_0000", "lastAuthUid" to "lostAuthUid")) {
            val text = good.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains(field))
            val jsonStart = text.indexOf("{\"schemaVersion\":")
            assertTrue(jsonStart > 65)
            val payload = text.substring(jsonStart).replace(field, bad)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val changed = (text.take(jsonStart - 65) + digest + "\n" + payload).toByteArray(Charsets.ISO_8859_1)
            file.writeBytes(changed)
            assertTrue(store.read() is DeletionSnapshot.Corrupt)
            assertArrayEquals(changed, file.readBytes())
        }
        // A still-parseable value with a damaged UID must not acquire that user's authority.
        file.writeBytes(good.toString(Charsets.ISO_8859_1).replace("\"ownerUid\":\"A\"", "\"ownerUid\":\"B\"")
            .toByteArray(Charsets.ISO_8859_1))
        assertTrue(store.read() is DeletionSnapshot.Corrupt)
        file.writeBytes(good)
        activeAfterRestart(q)
    }

    @Test fun inaccessibleDirectoryIsIoFailureAndCanBeRetried() = runBlocking {
        file.parentFile!!.writeText("not a directory")
        assertEquals(DeletionSnapshot.IoFailure, store.read())
        assertNull(store.begin(owner))
        Files.delete(file.parentFile!!.toPath())
        assertEquals(DeletionSnapshot.Empty, store.read())
    }

    @Test fun backupConfigurationCoversBothApiFamilies() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(File("src/main/res/xml/backup_rules.xml").readText().contains("<full-backup-content>"))
        val modern = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(modern.contains("<cloud-backup>") && modern.contains("<device-transfer>"))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private enum class Fault { BEFORE_WRITE, BEFORE_RENAME, RENAME_FAILURE, AFTER_RENAME }

    private class FaultStorage(private val delegate: Storage<DeletionFile>, private val file: File,
        private val fault: () -> Fault?) : Storage<DeletionFile> {
        override fun createConnection(): StorageConnection<DeletionFile> {
            val connection = delegate.createConnection()
            return object : StorageConnection<DeletionFile> by connection {
                override suspend fun writeScope(block: suspend WriteScope<DeletionFile>.() -> Unit) {
                    connection.writeScope {
                        if (fault() == Fault.BEFORE_WRITE) throw IOException("before write")
                        block()
                        when (fault()) {
                            Fault.BEFORE_RENAME -> throw IOException("before rename")
                            Fault.RENAME_FAILURE -> {
                                // A directory cannot replace the existing regular Q file by atomic rename.
                                val scratch = file.parentFile!!.listFiles()!!.single { it != file && it.name.endsWith(".tmp") }
                                Files.delete(scratch.toPath())
                                Files.createDirectory(scratch.toPath())
                            }
                            else -> Unit
                        }
                    }
                    if (fault() == Fault.AFTER_RENAME) throw IOException("after rename")
                }
            }
        }
    }
}
