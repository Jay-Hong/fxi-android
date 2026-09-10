package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a v2 install must not keep, and the files that make them up.
 *
 * `ANDROID_V2_PLAN.md §7 S1.5` is explicit about `fxi_bank_preferences`: its UID ownership and its
 * backup-restore provenance cannot be established, so it is **not converted** — the v2 UID
 * defaults stand in for it and the legacy store is deleted. The defaults arrived with
 * `RateRowPreferenceStore`; deleting the file is the other half.
 *
 * Not attributing it to another UID is the point (`ANDROID_V2_PLAN.md` §7 S1.5). Excluding it from backup keeps it
 * from arriving on a second device, and deleting it keeps the copy that is already on this one
 * from outliving the surface that wrote it. Neither alone is enough: an upgrading phone was never
 * restored onto, so an exclusion says nothing about the file it already has.
 */
internal object RetiredStores {

    /**
     * Retired preference stores, by DataStore name.
     *
     * A name is safe to list here only once nothing v2 reads it. `fxi_bank_preferences` is reached
     * through `BankPreferenceManager`, whose only consumer chain ends at `MainScreen`, which has no
     * caller — so nothing can write it back after a purge. The legacy files themselves belong to
     * the slices that own their surfaces (`ANDROID_V2_PLAN.md §7 S3`), and are left alone here.
     */
    val NAMES = listOf("fxi_bank_preferences")

    /**
     * Deletes every file backing a retired store, and answers with what went and what would not.
     *
     * A store is more than its `.preferences_pb`: DataStore writes through a `.tmp` sibling, and
     * leaving one behind would leave a readable copy of what was just deleted. So the base name
     * goes and so does anything suffixed onto it. The suffix must start with a dot, which is what
     * separates `…preferences_pb.tmp` from a `…preferences_pb2` that would be somebody else's
     * file — a store with a longer *name*, `fxi_bank_preferences_v2`, never matches either way,
     * because the base being matched already ends in `.preferences_pb`.
     *
     * **Not deleting is not an exception.** `File.delete` answers `false`; nothing is thrown, so a
     * caller wrapping this in `runCatching` would be told the sweep succeeded while the file it
     * exists to remove is still there. That is the one outcome this must not report as done, so
     * refusals come back named in [RetiredStoreSweep.failed]. Absence does not: this runs on every
     * start and has nothing to do on all but the first, and a phone with no `datastore` directory
     * yet has nothing wrong with it.
     */
    fun purge(datastoreDir: File): RetiredStoreSweep {
        if (!datastoreDir.exists()) return RetiredStoreSweep()
        // Distinct from the above: the path is there but will not enumerate — not a directory, or
        // the filesystem said no. Nothing was swept, and saying so is the only way to find out.
        val present = datastoreDir.listFiles()
            ?: return RetiredStoreSweep(unreadableDir = datastoreDir.path)
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        NAMES.forEach { name ->
            val base = "$name.preferences_pb"
            present.filter { it.name == base || it.name.startsWith("$base.") }
                .forEach { (if (it.delete()) deleted else failed) += it.name }
        }
        return RetiredStoreSweep(deleted, failed)
    }
}

/**
 * What one sweep did.
 *
 * [failed] and [unreadableDir] are separate from an empty [deleted] on purpose: having nothing to
 * delete and being unable to delete look identical from the outside, and only one of them means
 * the v1 file is still readable on the phone.
 */
internal data class RetiredStoreSweep(
    val deleted: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val unreadableDir: String? = null
)

/**
 * Runs [RetiredStores.purge] once per process start, off the main thread.
 *
 * At start rather than when the v2 store is first read: a user who never opens the screen that
 * reads row preferences would otherwise keep the v1 file forever, and the whole point is that it
 * stops existing. Nothing v2 reads it in the meantime, so nothing waits on this.
 *
 * A failed delete is logged and left for the next start. Taking the app down over a stale
 * preference file would turn a tidying job into an outage.
 */
@Singleton
class LegacyStorePurge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            runCatching { RetiredStores.purge(File(context.filesDir, DATASTORE_DIR)) }
                .onSuccess { sweep ->
                    if (sweep.deleted.isNotEmpty()) Log.i(TAG, "retired store 삭제: ${sweep.deleted}")
                    // Both of these mean the v1 file may still be on the phone, so neither may
                    // pass quietly just because no exception was thrown.
                    if (sweep.failed.isNotEmpty()) {
                        Log.w(TAG, "retired store 삭제 거부됨 — 다음 시작에서 재시도: ${sweep.failed}")
                    }
                    sweep.unreadableDir?.let { Log.w(TAG, "datastore 디렉터리를 읽지 못했다: $it") }
                }
                .onFailure { Log.w(TAG, "retired store 삭제 실패 — 다음 시작에서 재시도", it) }
        }
    }

    private companion object {
        /** Where `preferencesDataStore(name = …)` puts its file, relative to `filesDir`. */
        const val DATASTORE_DIR = "datastore"
        const val TAG = "LegacyStorePurge"
    }
}
