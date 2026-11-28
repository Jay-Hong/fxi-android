package com.jay.fxi.data.entitlements

import com.jay.fxi.data.entitlements.purge.JournalEntry
import com.jay.fxi.data.entitlements.purge.PurgeCause
import java.util.UUID
import kotlinx.serialization.Serializable

internal enum class DeletionPhase {
    REQUESTING_SERVER, SERVER_DELETED, FIREBASE_DELETE_IN_FLIGHT, FIREBASE_DELETED
}

internal enum class AuthObservation { RESTORING, RESTORED_NULL, OWNER, OTHER }

@Serializable
internal data class DeletionEpochs(val user: String, val capability: String) {
    init { require(user.isNotBlank() && capability.isNotBlank()) }
}

/** Persisted before the access owner rotates. Both axes and the deletion cause are fixed in v1. */
@Serializable
internal data class DeletionRetirement(val before: DeletionEpochs, val after: DeletionEpochs) {
    init {
        require(after.user != after.capability)
        require(after.user !in listOf(before.user, before.capability))
        require(after.capability !in listOf(before.user, before.capability))
    }

    fun obligation(owner: String) = JournalEntry.Owed(
        PendingPurge(owner, before.user, before.capability, PurgeScope.entries.toSet()),
        PurgeCause.ACCOUNT_DELETION
    )
}

/** A single immutable record. Every serialized field is required, including nullable evidence. */
@Serializable
internal data class DeletionRecord(
    val ownerUid: String,
    val ownerGeneration: Long,
    val operationId: String,
    val phase: DeletionPhase,
    val revision: Long,
    val createdUtcMillis: Long,
    val updatedUtcMillis: Long,
    val lastAuthUid: String,
    val observation: AuthObservation,
    val externalIdentityChange: Boolean,
    val lastExternalChangeRevision: Long?,
    val serverAttempt: Long,
    val attemptGeneration: Long,
    val sdkAttempt: Long,
    val sdkPending: Boolean,
    val retirement: DeletionRetirement?,
    val journalReference: String?
) {
    init {
        require(ownerUid.isNotBlank() && lastAuthUid.isNotBlank())
        require(UUID.fromString(operationId).toString() == operationId)
        require(revision > 0 && serverAttempt >= 0 && sdkAttempt >= 0)
        require(createdUtcMillis >= 0 && updatedUtcMillis >= 0)
        require(observation != AuthObservation.OWNER || lastAuthUid == ownerUid)
        require(observation != AuthObservation.OTHER || (lastAuthUid != ownerUid && externalIdentityChange))
        require(lastExternalChangeRevision == null || lastExternalChangeRevision in 1L..revision)
        require(!externalIdentityChange || lastExternalChangeRevision != null)
        require(phase == DeletionPhase.REQUESTING_SERVER || serverAttempt > 0)
        require(phase != DeletionPhase.REQUESTING_SERVER || retirement == null)
        require((sdkAttempt > 0) == (phase == DeletionPhase.FIREBASE_DELETE_IN_FLIGHT || phase == DeletionPhase.FIREBASE_DELETED))
        require(!sdkPending || phase == DeletionPhase.FIREBASE_DELETE_IN_FLIGHT)
        require(journalReference == null || (retirement != null &&
            journalReference.matches(Regex("[0-9a-f]{64}"))))
    }
}

internal sealed interface DeletionSnapshot {
    data object Closed : DeletionSnapshot
    data object Empty : DeletionSnapshot
    data class Active(val record: DeletionRecord) : DeletionSnapshot
    data class Corrupt(val reason: String) : DeletionSnapshot
    data object IoFailure : DeletionSnapshot
}

/** The durable writer can publish observations but has no access to legacy admission commands. */
internal sealed interface DeletionProjection {
    val deletionSnapshot: kotlinx.coroutines.flow.StateFlow<DeletionSnapshot>
    fun publishConfirmed(snapshot: DeletionSnapshot)
}
