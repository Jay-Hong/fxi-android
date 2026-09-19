package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import okio.buffer
import okio.source

/** Every release in these suites uses the real facade, owner and FileStorage unless stated otherwise. */
abstract class ReleaseOwnerTestBase {
    @get:Rule val folder = TemporaryFolder()
    internal val opened = mutableListOf<ControlStoreTestStorage>()
    internal val file by lazy { File(folder.root, "release.preferences_pb") }
    internal val o by lazy { open(file) }
    internal val tracking get() = ControlCommandTracking.forOwner(o.owner)
    internal val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    internal fun open(path: File = File(folder.root, "other-${opened.size}.preferences_pb")) =
        ControlStoreTestStorage(path).also { opened += it }
    @After fun closeReleaseStores() = runBlocking { opened.forEach { it.close() } }
    internal suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    internal fun add(store: ControlRecordStore = o.control): CommandRef = store.prepare(store.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    })
    internal suspend fun confirmed(c: CommandRef = add()): CommandRef {
        assertTrue("business confirmation", controlTestTimeout("confirmed execute") { o.control.execute(c) } is ControlStoreResult.Confirmed)
        return c
    }
    internal fun history(c: CommandRef) = checkNotNull(tracking.findPrepared(c))
    internal fun read(p: Preferences) = ControlRecordReader().read(p) as ControlRecordRead.Supported
    internal fun rows(p: Preferences) = ((read(p).metadata as ControlMetadataRead.V2).evidence.entries)
        .map { (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry() }
    internal fun own(p: Preferences, c: CommandRef) = ControlAppliedEvidence.own(read(p), c)
    internal fun assertPreserved(before: Preferences, after: Preferences, c: CommandRef, barrierAllowed: Boolean = false) {
        val ignored = setOfNotNull(evidenceKey, ControlStoreTestStorage.BARRIER.takeIf { barrierAllowed })
        assertEquals("all unrelated raw values preserved",
            before.toMutablePreferences().apply { ignored.forEach { remove(it) } },
            after.toMutablePreferences().apply { ignored.forEach { remove(it) } })
        val expected = (read(before).metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }.filterNot { it.value.commandId == c.id }
            .map { it.original.toPayloadEntry() }
        assertEquals("sibling values and order preserved", expected, rows(after))
        assertNull("only own evidence absent", own(after, c))
    }
    internal suspend fun released(c: CommandRef, barrierAllowed: Boolean = false): ControlCommandReleaseResult.Released {
        val before = disk(); val t = history(c); val count = t.firstConfirmDiscontinuityCount
        val expected = t.expectedApplied; val observed = t.observedApplied.get()
        val attempt = runCatching { controlTestTimeout("released release") { o.control.releaseAfterConsumption(c) } }
        assertNull("valid owner release must return normally", attempt.exceptionOrNull())
        val result = attempt.getOrThrow()
        assertTrue("owner-confirmed release: $result", result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        assertSame(c, result.command); assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
        assertNull("strong history cleaned", tracking.findPrepared(c))
        assertFalse(c in tracking.recoverySnapshot().pendingReleases)
        assertFalse(c in result.localPendingReleases); assertFalse(c in tracking.executing)
        assertEquals(tracking.recoverySnapshot().unresolvedCommands, result.localUnresolvedCommands)
        assertEquals(tracking.recoverySnapshot().pendingReleases, result.localPendingReleases)
        assertEquals(count, t.firstConfirmDiscontinuityCount); assertSame(expected, t.expectedApplied)
        if (observed) assertTrue(t.observedApplied.get())
        assertEquals(result.snapshot.record.original, disk())
        assertPreserved(before, disk(), c, barrierAllowed)
        return result
    }
    internal suspend fun pending(c: CommandRef): ControlCommandReleaseResult.Unconfirmed {
        val before = disk(); val t = history(c); o.storage.before = true
        val result = controlTestTimeout("pending release") { o.control.releaseAfterConsumption(c) }
        assertTrue("pending write failure", result is ControlCommandReleaseResult.Unconfirmed)
        result as ControlCommandReleaseResult.Unconfirmed
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, result.state)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, result.phase)
        assertSame(t, tracking.findPrepared(c)); assertNotNull(t.releaseDescriptor)
        assertTrue(c in result.localPendingReleases); assertFalse(c in result.localUnresolvedCommands)
        assertEquals(before, disk()); assertFalse(c in tracking.executing)
        return result
    }
    internal suspend fun rejected(c: CommandRef, reason: ReleaseRejectionReason) {
        val before = disk(); val writes = o.storage.writes; val t = tracking.findPrepared(c)
        val state = c.lifecycleState; val work = tracking.recoverySnapshot()
        val result = controlTestTimeout("rejected release") { o.control.releaseAfterConsumption(c) }
        assertTrue("release rejection: $result", result is ControlCommandReleaseResult.Rejected)
        result as ControlCommandReleaseResult.Rejected
        assertEquals(reason, result.reason); assertSame(c, result.command); assertEquals(state, result.state)
        assertEquals(work.unresolvedCommands, result.localUnresolvedCommands); assertEquals(work.pendingReleases, result.localPendingReleases)
        assertSame(t, tracking.findPrepared(c)); assertEquals(state, c.lifecycleState)
        assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
    }
}
