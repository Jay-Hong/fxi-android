package com.jay.fxi.data.entitlements

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.buffer
import okio.sink

@Serializable
internal data class DeletionFile(val schemaVersion: Int, val record: DeletionRecord?)

internal object DeletionFileSerializer : Serializer<DeletionFile> {
    private val entry = stringPreferencesKey("deletion")
    override val defaultValue = DeletionFile(1, null)

    private fun checked(payload: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) } + "\n" + payload
    }

    override suspend fun readFrom(input: InputStream): DeletionFile {
        val bytes = input.readBytes()
        val prefs = PreferencesSerializer.readFrom(Buffer().write(bytes))
        if (prefs.asMap().keys != setOf(entry)) throw CorruptionException("missing or extra deletion fields")
        val value = prefs.asMap()[entry] as? String ?: throw CorruptionException("invalid deletion field")
        val payload = value.substringAfter('\n', "")
        if (value != checked(payload)) throw CorruptionException("deletion checksum mismatch")
        try {
            val version = Json.parseToJsonElement(payload).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (version != 1) throw CorruptionException("unsupported deletion version: $version")
            val file = Json.decodeFromString<DeletionFile>(payload)
            val canonical = Buffer()
            PreferencesSerializer.writeTo(preferencesOf(entry to checked(Json.encodeToString(file))), canonical)
            if (!bytes.contentEquals(canonical.readByteArray())) throw CorruptionException("noncanonical deletion file")
            return file
        } catch (bad: IllegalArgumentException) {
            throw CorruptionException("invalid deletion record", bad)
        } catch (bad: IllegalStateException) {
            throw CorruptionException("invalid deletion fields", bad)
        }
    }

    override suspend fun writeTo(t: DeletionFile, output: OutputStream) {
        val sink = output.sink().buffer()
        PreferencesSerializer.writeTo(preferencesOf(entry to checked(Json.encodeToString(t))), sink)
        sink.flush()
    }
}

private fun fileStorage(file: File): Storage<DeletionFile> = FileStorage(DeletionFileSerializer) { file }

internal class DeletionRecordStore(
    private val noBackupRoot: () -> File,
    private val admission: DeletionProjection,
    private val storage: (File) -> Storage<DeletionFile> = ::fileStorage
) {
    companion object {
        private val mutex = Mutex()
        private var processOwner: DeletionRecordStore? = null

        @Synchronized
        fun forInstallation(context: Context, admission: DeletionProjection): DeletionRecordStore {
            val current = processOwner
            if (current != null) {
                check(current.admission === admission)
                return current
            }
            val app = context.applicationContext
            return openInstallation(app, admission).also { processOwner = it }
        }

        internal fun openInstallation(
            context: Context,
            admission: DeletionProjection,
            storage: (File) -> Storage<DeletionFile> = ::fileStorage
        ) = DeletionRecordStore({ context.noBackupFilesDir }, admission, storage)
    }

    private fun file() = File(noBackupRoot(), "account_deletion/v1.pb")

    /** Enumerating the accessible parent distinguishes confirmed absence from failed metadata access. */
    private suspend fun disk(file: File): DeletionFile {
        val dir = file.parentFile!!.toPath()
        val root = dir.parent
        fun contains(parent: java.nio.file.Path, name: java.nio.file.Path) =
            Files.newDirectoryStream(parent).use { entries -> entries.any { it.fileName == name } }
        if (!contains(root, dir.fileName)) return DeletionFileSerializer.defaultValue
        val attrs = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isDirectory) throw IOException("invalid deletion directory")
        val path = file.toPath()
        if (!contains(dir, path.fileName)) return DeletionFileSerializer.defaultValue
        if (!Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile) {
            throw IOException("invalid deletion file")
        }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { DeletionFileSerializer.readFrom(it) }
    }

    private fun publish(file: DeletionFile) {
        admission.publishConfirmed(file.record?.let { DeletionSnapshot.Active(it) } ?: DeletionSnapshot.Empty)
    }

    private suspend fun <T> serialized(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (bad: CorruptionException) {
                admission.publishConfirmed(DeletionSnapshot.Corrupt(bad.message ?: "invalid deletion file"))
                null
            } catch (failure: IOException) {
                admission.publishConfirmed(DeletionSnapshot.IoFailure)
                null
            } catch (failure: java.nio.file.DirectoryIteratorException) {
                admission.publishConfirmed(DeletionSnapshot.IoFailure)
                null
            } catch (failure: SecurityException) {
                admission.publishConfirmed(DeletionSnapshot.IoFailure)
                null
            } catch (cancelled: CancellationException) {
                admission.publishConfirmed(DeletionSnapshot.IoFailure)
                throw cancelled
            } catch (failure: RuntimeException) {
                admission.publishConfirmed(DeletionSnapshot.IoFailure)
                throw failure
            }
        }
    }

    suspend fun read(): DeletionSnapshot {
        serialized { publish(disk(file())) }
        return admission.deletionSnapshot.value
    }

    private fun confirmed() = when (admission.deletionSnapshot.value) {
        DeletionSnapshot.Empty, is DeletionSnapshot.Active -> true
        else -> false
    }

    /** Open a fresh FileStorage connection under the single writer, including after uncertain commits. */
    private suspend fun <T> update(change: (DeletionRecord?) -> Pair<DeletionRecord?, T?>): T? = serialized {
        if (!confirmed()) return@serialized null
        val path = file()
        val before = disk(path)
        val job = SupervisorJob()
        try {
            val data = DataStoreFactory.create(storage = storage(path), scope = CoroutineScope(Dispatchers.IO + job))
            var result: T? = null
            val committed = data.updateData { latest ->
                if (latest != before) throw IOException("deletion file changed during update")
                val (next, answer) = change(latest.record)
                result = answer
                DeletionFile(1, next)
            }
            val reopened = disk(path)
            if (committed != reopened) throw IOException("deletion commit unconfirmed")
            publish(reopened)
            result
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    suspend fun begin(owner: AuthIdentityFence): DeletionRecord? = update { active ->
        if (active != null) active to active.takeIf { it.ownerUid == owner.uid }
        else {
            val now = System.currentTimeMillis()
            val record = DeletionRecord(owner.uid, owner.authGeneration, UUID.randomUUID().toString(),
                DeletionPhase.REQUESTING_SERVER, 1, now, now, owner.uid, AuthObservation.OWNER, false, null,
                0, owner.authGeneration, 0, false, null, null)
            record to record
        }
    }

    private suspend fun advance(expected: DeletionRecord, change: (DeletionRecord) -> DeletionRecord?): DeletionRecord? =
        update { current ->
            val next = if (current == expected) change(expected.copy(revision = Math.addExact(expected.revision, 1L),
                updatedUtcMillis = System.currentTimeMillis())) else null
            (next ?: current) to next
        }

    suspend fun markSending(expected: DeletionRecord, fence: AuthIdentityFence) = advance(expected) {
        if (it.phase != DeletionPhase.REQUESTING_SERVER || fence.uid != it.ownerUid) null
        else it.copy(serverAttempt = Math.addExact(it.serverAttempt, 1L), attemptGeneration = fence.authGeneration)
    }

    suspend fun cancelBeforeSend(expected: DeletionRecord, fence: AuthIdentityFence): Boolean = update { current ->
        val release = current == expected && expected.phase == DeletionPhase.REQUESTING_SERVER &&
            expected.ownerUid == fence.uid && expected.serverAttempt == 0L
        (if (release) null else current) to release
    } == true

    /** A 204 belongs to the captured attempt, even if another UID is now on screen. */
    suspend fun serverDeleted(expected: DeletionRecord, sent: DeletionRecord) = advance(expected) {
        if (it.phase == DeletionPhase.REQUESTING_SERVER && sent.phase == it.phase &&
            sent.ownerUid == it.ownerUid && sent.operationId == it.operationId && sent.serverAttempt > 0 &&
            sent.serverAttempt == it.serverAttempt && sent.attemptGeneration == it.attemptGeneration) {
            it.copy(phase = DeletionPhase.SERVER_DELETED)
        } else null
    }

    /** Called inside the auth owner's serial section, before an external sign-out or replacement. */
    suspend fun observe(expected: DeletionRecord, restored: Boolean, uid: String?, externalSignOut: Boolean = false) =
        advance(expected) {
            val kind = when {
                !restored -> AuthObservation.RESTORING
                uid == null -> AuthObservation.RESTORED_NULL
                uid == it.ownerUid -> AuthObservation.OWNER
                else -> AuthObservation.OTHER
            }
            val changed = externalSignOut || kind == AuthObservation.OTHER
            it.copy(observation = kind, lastAuthUid = uid?.takeIf { restored } ?: it.lastAuthUid,
                externalIdentityChange = it.externalIdentityChange || changed,
                lastExternalChangeRevision = if (changed) it.revision else it.lastExternalChangeRevision)
        }

    suspend fun startSdk(expected: DeletionRecord, fence: AuthIdentityFence) = advance(expected) {
        if (it.phase !in setOf(DeletionPhase.SERVER_DELETED, DeletionPhase.FIREBASE_DELETE_IN_FLIGHT) ||
            fence.uid != it.ownerUid) null
        else it.copy(phase = DeletionPhase.FIREBASE_DELETE_IN_FLIGHT, sdkAttempt = Math.addExact(it.sdkAttempt, 1L),
            attemptGeneration = fence.authGeneration, sdkPending = true, externalIdentityChange = false,
            observation = AuthObservation.OWNER, lastAuthUid = fence.uid)
    }

    suspend fun sdkResult(expected: DeletionRecord, attempt: DeletionRecord, deleted: Boolean) = advance(expected) {
        if (!it.sdkPending || !attempt.sdkPending || attempt.operationId != it.operationId ||
            attempt.ownerUid != it.ownerUid || attempt.sdkAttempt != it.sdkAttempt ||
            attempt.attemptGeneration != it.attemptGeneration) null
        else it.copy(sdkPending = false, phase = if (deleted) DeletionPhase.FIREBASE_DELETED else it.phase)
    }

    suspend fun confirmRestoredNull(expected: DeletionRecord) = advance(expected) {
        if (!it.sdkPending || it.observation != AuthObservation.RESTORED_NULL ||
            it.lastAuthUid != it.ownerUid || it.externalIdentityChange) null
        else it.copy(sdkPending = false, phase = DeletionPhase.FIREBASE_DELETED)
    }

    /** The access owner supplies its observations inside its own serial section (access lock, then deletion lock). */
    suspend fun reserve(expected: DeletionRecord, plan: DeletionRetirement, before: RetirementObservation) = advance(expected) {
        if (it.phase != DeletionPhase.SERVER_DELETED || it.retirement != null ||
            !before.canReserve(it.ownerUid, plan)) null
        else it.copy(retirement = plan)
    }

    suspend fun confirmRotation(expected: DeletionRecord, after: RetirementObservation) = advance(expected) {
        val plan = it.retirement
        if (plan == null || it.journalReference != null) null
        else after.confirmedRow(it.ownerUid, plan)?.let { row -> it.copy(journalReference = journalReference(row)) }
    }

    internal suspend fun withCurrent(expected: DeletionRecord, use: suspend () -> Boolean): Boolean = serialized {
        if (!confirmed()) return@serialized false
        val actual = disk(file())
        publish(actual)
        actual.record == expected && use()
    } == true
}
