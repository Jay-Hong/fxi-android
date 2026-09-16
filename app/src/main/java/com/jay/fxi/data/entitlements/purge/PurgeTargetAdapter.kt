package com.jay.fxi.data.entitlements.purge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import java.io.File

/** One target's deletion, as asked for by one journal entry. */
data class PurgeRequest(
    val target: PurgeTarget,
    val scope: PurgeScope,
    val cause: PurgeCause,
    val namespace: PurgeNamespace
)

/**
 * What one target's deletion did.
 *
 * [NothingToRemove] and [Removed] are both success and are kept apart for the log: "there was
 * nothing there" and "it is gone now" answer different questions after a crash. [Failed] is the
 * one that must never be reported as done — `RetiredStores` learned this from `File.delete`
 * answering `false` without throwing.
 */
sealed interface TargetOutcome {
    data object Removed : TargetOutcome
    data object NothingToRemove : TargetOutcome
    data class Failed(val reason: String, val cause: Throwable? = null) : TargetOutcome
}

/**
 * Deletes one target.
 *
 * An adapter owns its surface and nothing else: it is handed the request and answers for that
 * target alone, so one refusal cannot be read as another target's success. Throwing is allowed —
 * the aggregator catches it as a failure — but answering [TargetOutcome.Failed] keeps the reason.
 */
fun interface PurgeTargetAdapter {
    suspend fun purge(request: PurgeRequest): TargetOutcome
}

/**
 * A preferences store whose whole content belongs to one owner.
 *
 * Use only where one stamp covers the whole surface. The comparison and the clearing share a
 * single store edit, because reading the stamp and then clearing in a second edit lets another
 * owner's write land in between and be deleted by it.
 *
 * A different non-null stamp is [TargetOutcome.NothingToRemove] — the retired namespace's data is
 * not here. A non-empty store with no stamp fails instead: nothing proves whose content that is.
 *
 * Comparing UIDs does not tell a later session of the same UID from the retired one. That
 * separation is the lifetime and writer-exclusion contract P3 brings, not something this adapter
 * provides. Nor does a concern's own stamp authorise clearing another concern's keys in a store
 * that holds more than one — `RateRowPreferenceStore` says as much where it declares
 * `rate_row_owner_uid`.
 *
 * [ownerKeyName] is the store's own owner key. `null` means the store has no stamp, and then an
 * owner-scoped purge cannot prove the content is the retired owner's — it refuses rather than
 * guesses, which is why no unstamped store is classified [PurgeClassification.DERIVED_HERE] today.
 */
class OwnerStampedPreferencesAdapter(
    private val store: DataStore<Preferences>,
    private val ownerKeyName: String?
) : PurgeTargetAdapter {

    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        val owner = request.namespace.pending.ownerUid
            ?: return TargetOutcome.Failed("purge of an unnarrowed owner cannot clear an owner-stamped store")
        val key = ownerKeyName?.let(::stringPreferencesKey)
            ?: return TargetOutcome.Failed("store has no owner stamp to compare")
        var outcome: TargetOutcome = TargetOutcome.NothingToRemove
        store.edit { current ->
            outcome = when {
                current.asMap().isEmpty() -> TargetOutcome.NothingToRemove
                current[key] == null ->
                    TargetOutcome.Failed("non-empty store has no owner stamp")
                current[key] != owner -> TargetOutcome.NothingToRemove
                else -> {
                    current.clear()
                    TargetOutcome.Removed
                }
            }
        }
        return outcome
    }
}

/**
 * Files named by a supplier, deleted honestly.
 *
 * `File.delete()` answers `false` rather than throwing, and a caller that ignores it reports a
 * sweep as done while the file is still readable on the phone (`RetiredStores` says so in as many
 * words). So a refusal is a failure — unless the file simply is not there, which is the ordinary
 * state on every start after the first.
 */
class FileTargetAdapter(private val files: () -> List<File>) : PurgeTargetAdapter {

    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        var removed = false
        val refused = mutableListOf<String>()
        files().forEach { file ->
            if (!file.exists()) return@forEach
            if (file.delete()) removed = true else refused += file.name
        }
        return when {
            refused.isNotEmpty() -> TargetOutcome.Failed("not deleted: ${refused.joinToString()}")
            removed -> TargetOutcome.Removed
            else -> TargetOutcome.NothingToRemove
        }
    }
}
