package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okio.buffer
import okio.source

/**
 * Claude-owned 6-1B contract fixtures (not production API). [TerminationBoundary] wraps the real DataStore from
 * construction: it counts every data collection and updateData, can fail or suspend the next updateData before a
 * snapshot is read, and can transform the value an updateData returns. The implementation thread reads but does not
 * edit this file.
 */
internal class TerminationBoundary(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    @Volatile var accesses = 0
    @Volatile var failNextBeforeSnapshot = false
    /** When set, the next updateData signals [reached] and waits for [release] before reading the snapshot. */
    @Volatile var gate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    /** When set, transforms the snapshot returned by the next updateData (the stored value is not changed). */
    @Volatile var afterReturn: ((Preferences) -> Preferences)? = null
    override val data: Flow<Preferences> get() = flow { accesses++; emitAll(delegate.data) }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        accesses++
        gate?.let { (reached, release) -> gate = null; reached.complete(Unit); release.await() }
        if (failNextBeforeSnapshot) { failNextBeforeSnapshot = false; throw IOException("no actual snapshot") }
        val result = delegate.updateData(transform)
        return afterReturn?.let { afterReturn = null; it(result) } ?: result
    }
}

internal class TerminationFixture(folder: File, index: Int, val file: File = File(folder, "termination-$index.preferences_pb")) {
    private var wrapped: TerminationBoundary? = null
    val storage = ControlStoreTestStorage(file) { TerminationBoundary(it).also { b -> wrapped = b } }
    val boundary get() = checkNotNull(wrapped)
    val store get() = storage.control
    val tracker get() = ControlCommandTracking.forOwner(storage.owner)
    fun mutations(): CommandRef = store.prepare(store.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    })
    fun history(c: CommandRef) = checkNotNull(tracker.findPrepared(c))
    /** The file itself, not the DataStore cache: after a failed write the two can differ (ControlReleaseFailureTest). */
    suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    suspend fun edit(change: (MutablePreferences) -> Unit) = controlTestTimeout("record edit") { storage.data.edit { change(it) } }
    /**
     * A non-normal owner transaction leaves a read-back obligation, so the next Confirm writes the read barrier even
     * when its candidate equals the snapshot — the only way a Confirm(original) reaches the write scope and its faults.
     */
    suspend fun armReadBack() {
        boundary.failNextBeforeSnapshot = true
        val failure = runCatching { storage.owner.transactRecord { RecordTransactionDecision.Observe(Unit) } }.exceptionOrNull()
        check(failure is IOException) { "fixture: read-back arming did not fail: $failure" }
    }
    fun addUnresolved(c: CommandRef) = tracker.markUnresolved(c)
    fun addPending(c: CommandRef) {
        val w = tracker.recoverySnapshot()
        ControlReleaseFixtures.replaceRecovery(tracker, LocalRecoveryWork(w.unresolvedCommands, w.pendingReleases + c))
    }
}

internal object TerminationClosures {
    val base = setOf("job-1")
    fun of(c: CommandRef, command: CommandRef = c, lifetime: OwnerTrackingLifetimeId = c.ownerTrackingLifetimeId,
        scope: String = c.id, capture: Long = 7L, current: Long = 7L, entriesClosed: Boolean = true,
        captured: Set<String> = base, joined: Set<String> = base, registered: Set<String> = base,
        receiptsClosed: Boolean = true, owner: String = "owner-1") =
        TerminationClosure(command = command, ownerTrackingLifetimeId = lifetime, relatedScope = scope,
            generationAtCapture = capture, currentGeneration = current, entriesClosed = entriesClosed,
            captured = captured, joined = joined, registered = registered, receiptsClosed = receiptsClosed, owner = owner)
}
