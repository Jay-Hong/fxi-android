package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import java.util.concurrent.atomic.AtomicReference

/** Both phases block the owner's UID, including later auth generations of that UID. */
internal enum class DeletionAdmissionPhase {
    /** Published before the server deletion request leaves; uncertain outcomes keep this phase. */
    REQUESTING_SERVER,

    /** The server confirmed deletion with 204; this store cannot clear it. */
    SERVER_DELETED
}

/** Immutable attribution to the original identity and a caller-assigned, unique deletion operation. */
internal data class DeletionAdmissionRecord(
    val owner: AuthIdentityFence,
    val operationId: String,
    val phase: DeletionAdmissionPhase
)

/**
 * Process-owned, memory-only deletion admission (S4 RT01-A2). The process runtime must share one
 * instance between deletion publication and graph admission when the critical cutover wires them.
 * This dormant type does not register itself or publish from the account deletion stage.
 *
 * A request blocks its UID before server deletion is invoked. A 204 advances only that operation;
 * timeout, cancellation, 5xx, VM teardown, Firebase failure and token changes do not release it.
 * Only a request proven not to have left may be released, and only while requesting. Terminal
 * clear belongs to S10's verified finalizer; there is deliberately no clear API here.
 *
 * Each write atomically publishes a replacement list and never mutates a published list or record.
 * Concurrent writes retry through [AtomicReference]; reads are lock-free and see one complete
 * publication. Diagnostic snapshots are detached from the internal list.
 */
internal class DeletionAdmissionStore {
    private val published = AtomicReference<List<DeletionAdmissionRecord>>(emptyList())

    /** A read-only diagnostic copy of one publication; later writes do not change it. */
    val records: List<DeletionAdmissionRecord>
        get() = published.get().toList()

    /** Before calling deleteUser, add a requesting record using a new, unique [operationId]. */
    fun begin(owner: AuthIdentityFence, operationId: String) {
        published.updateAndGet { records ->
            records + DeletionAdmissionRecord(owner, operationId, DeletionAdmissionPhase.REQUESTING_SERVER)
        }
    }

    /** On this operation's 204, advance its requesting record; unknown or confirmed operations stay unchanged. */
    fun serverDeleted(operationId: String) {
        published.updateAndGet { records ->
            records.map { record ->
                if (record.operationId == operationId && record.phase == DeletionAdmissionPhase.REQUESTING_SERVER) {
                    record.copy(phase = DeletionAdmissionPhase.SERVER_DELETED)
                } else {
                    record
                }
            }
        }
    }

    /**
     * The caller must prove this operation's request never left before calling. An uncertain send
     * is insufficient. Removes only its requesting record, never a server-confirmed deletion.
     */
    fun releaseUnsent(operationId: String) {
        published.updateAndGet { records ->
            records.filterNot { record ->
                record.operationId == operationId && record.phase == DeletionAdmissionPhase.REQUESTING_SERVER
            }
        }
    }

    /** One lock-free read: any phase blocks [uid] across auth generations; null names no user. */
    fun blocks(uid: String?): Boolean = uid != null && published.get().any { it.owner.uid == uid }
}
