package com.jay.fxi.data.entitlements

import android.content.Context
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionStoreIsolationTest {
    @Test fun recordScratchAndRestoredOperationStayInstallationLocal() = runBlocking {
        // Exercise the target installation's Android storage domains. Require a fresh fixture before any write.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = File(context.noBackupFilesDir, "account_deletion")
        val backupParent = File(context.filesDir, "account_deletion")
        check(!parent.exists() && !backupParent.exists()) { "target fixture already has deletion state" }
        val file = File(parent, "v1.pb")
        val scratch = mutableListOf<File>()
        val copiesDuringWrite = mutableSetOf<File>()
        val admission = DeletionAdmissionStore()
        val writer = DeletionRecordStore.openInstallation(context, admission) { destination ->
            FileStorage(object : Serializer<DeletionFile> by DeletionFileSerializer {
                override suspend fun writeTo(t: DeletionFile, output: OutputStream) {
                    DeletionFileSerializer.writeTo(t, output)
                    scratch += destination.parentFile!!.listFiles()!!.filter { it.isFile && it != destination }
                    val operation = checkNotNull(t.record).operationId
                    copiesDuringWrite += backupCopies(context, operation)
                }
            }) { destination }
        }
        try {
            assertEquals(DeletionSnapshot.Empty, writer.read())
            val record = checkNotNull(writer.begin(AuthIdentityFence("fixture-owner", 1)))
            assertTrue("S104_STORE_BACKUP_ROOT", file.isFile)
            val reader = DeletionRecordStore.openInstallation(context, DeletionAdmissionStore())
            val active = (reader.read() as DeletionSnapshot.Active).record
            assertTrue("S104_REOPEN_OPERATION", active.operationId == record.operationId)
            assertEquals(record, active)
            assertTrue("actual scratch observed", scratch.any { it.name.endsWith(".tmp") })
            assertTrue(scratch.all { it.canonicalFile.parentFile == parent.canonicalFile })
            assertTrue(copiesDuringWrite.isEmpty())
            assertTrue(backupCopies(context, record.operationId).isEmpty())
            // The positive control sits under a nested no_backup folder that is NOT the app's no-backup root: only each data
            // root's direct children are excluded, so the scan must still find it.
            val controlDir = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "copy-control").toFile()
            val control = File(File(controlDir, "no_backup").also { check(it.mkdir()) }, "copy.bin")
            try {
                file.copyTo(control, overwrite = true)
                assertTrue(backupCopies(context, record.operationId).any { it.canonicalFile == control.canonicalFile })
            } finally {
                controlDir.deleteRecursively()
            }
            val damage = byteArrayOf(10, 127, 0)
            file.writeBytes(damage)
            repeat(2) {
                assertTrue(reader.read() is DeletionSnapshot.Corrupt)
                assertNull(reader.begin(AuthIdentityFence("fixture-owner", 1)))
                assertArrayEquals(damage, file.readBytes())
            }
        } finally {
            parent.deleteRecursively()
            backupParent.deleteRecursively()
        }
    }

    /** Battery control: the on-device serializer round trip touches neither the storage root nor the operation id. */
    @Test fun emptyDeletionFileRoundTripsOnDevice() = runBlocking {
        val out = java.io.ByteArrayOutputStream()
        DeletionFileSerializer.writeTo(DeletionFileSerializer.defaultValue, out)
        assertEquals(DeletionFileSerializer.defaultValue, DeletionFileSerializer.readFrom(out.toByteArray().inputStream()))
    }

    /**
     * Every regular file under the app's data roots and external files dirs that is a deletion record copy. Only the direct
     * children no_backup, cache, code_cache and lib of each internal data root are skipped. Other components of the app (the
     * analytics preferences backup, for one) can delete a file between enumeration and read: a confirmed absence is skipped,
     * any other failure (access denied included) fails the scan.
     */
    private fun backupCopies(context: Context, operation: String): List<File> {
        val internalRoots = listOf(
            context.dataDir,
            context.createDeviceProtectedStorageContext().dataDir
        ).map { it.toPath() }.toSet()
        val externalRoots = context.getExternalFilesDirs(null).filterNotNull().map { it.toPath() }
        val excluded = internalRoots.flatMap { root ->
            listOf("no_backup", "cache", "code_cache", "lib").map { root.resolve(it) }
        }.toSet()
        val copies = mutableListOf<File>()

        for (root in internalRoots + externalRoots) {
            java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    dir: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ) = if (dir in excluded) java.nio.file.FileVisitResult.SKIP_SUBTREE
                    else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(
                    path: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) {
                        val name = path.fileName.toString()
                        if (name == "v1.pb" || name.startsWith("v1.pb.") ||
                            bytesIfPresent(path)?.toString(Charsets.ISO_8859_1)?.contains(operation) == true
                        ) copies.add(path.toFile())
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: java.nio.file.Path, error: java.io.IOException)
                    : java.nio.file.FileVisitResult {
                    if (error is java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE
                    throw error
                }
            })
        }
        return copies
    }

    private fun bytesIfPresent(path: java.nio.file.Path): ByteArray? = try {
        java.nio.file.Files.readAllBytes(path)
    } catch (missing: java.nio.file.NoSuchFileException) {
        null
    }
}
