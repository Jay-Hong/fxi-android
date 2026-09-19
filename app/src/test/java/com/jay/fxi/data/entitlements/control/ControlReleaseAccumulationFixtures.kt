package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.buffer
import okio.source
import org.junit.Assert.*

/** Shared by the short mutation oracles and the separately filtered FileStorage long runs. */
internal enum class ReleaseStringProfile(val text: String) {
    SHORT_ASCII("guard"),
    LONG_ASCII("abcdefgh".repeat(128)),
    UTF8("한글😀𐐀".repeat(64)),
    ESCAPED("quote\"slash\\".repeat(64));

    val guard: ControlNode get() = ControlNode.of(JsonObject(mapOf(
        "id" to JsonPrimitive(text), "kind" to JsonPrimitive("SCHEDULE_GUARD")
    )))
    val boot get() = "boot:$text"
    val origin get() = LifetimeId("origin:$text")
}

internal data class ReleaseFootprint(
    val payloadBytes: List<Int>, val serializedBytes: Long, val fileBytes: Long,
    val evidenceRows: Int, val commands: Int, val unresolved: Int, val pending: Int, val executing: Int
) {
    val payloadTotal get() = payloadBytes.sum()
    fun json(): String = """{"payloadBytes":$payloadBytes,"payloadTotal":$payloadTotal,"serializedBytes":$serializedBytes,"fileBytes":$fileBytes,"evidenceRows":$evidenceRows,"commands":$commands,"unresolved":$unresolved,"pending":$pending,"executing":$executing}"""
}

/** Only a bounded pair of numeric samples is retained; completed refs/results never accumulate. */
internal data class ReleaseCycleSample(val before: ReleaseFootprint, val after: ReleaseFootprint) {
    fun json() = """{"before":${before.json()},"after":${after.json()}}"""
}

internal class ReleaseAccumulationFixture(val file: File, val profile: ReleaseStringProfile) {
    val storage = ControlStoreTestStorage(file)
    val control get() = storage.control
    val tracker = ControlCommandTracking.forOwner(storage.owner)
    private val lifetime = tracker.lifetimeId
    private val commands get() = ControlReleaseFixtures.commands(tracker)
    private val reader = ControlRecordReader()
    private var tick = 0L
    lateinit var waiting: CommandRef
        private set
    lateinit var landed: CommandRef
        private set
    lateinit var unlanded: CommandRef
        private set
    lateinit var pending: CommandRef
        private set
    lateinit var prepared: CommandRef
        private set
    lateinit var rotation: CommandRef
        private set
    private lateinit var controls: Map<String, TrackedControlCommand>
    private lateinit var histories: List<RetainedHistory>
    private lateinit var baselineRows: List<PayloadEntry>
    private lateinit var baselineWork: LocalRecoveryWork
    private lateinit var stableValues: Preferences

    private class RetainedHistory(val tracked: TrackedControlCommand) {
        val targets = tracked.targets.get()
        val requested = tracked.confirmationRequested.get()
        val confirmed = tracked.confirmed.get()
        val observed = tracked.observedApplied.get()
        val first = tracked.firstConfirmDiscontinuityCount
        val expected = tracked.expectedApplied
        val descriptor = tracked.releaseDescriptor
        val state = tracked.command.lifecycleState
        fun assertUnchanged() {
            assertSame("control fixed targets", targets, tracked.targets.get())
            assertEquals(requested, tracked.confirmationRequested.get())
            assertEquals(confirmed, tracked.confirmed.get())
            assertEquals(observed, tracked.observedApplied.get())
            assertEquals("control first Confirm baseline", first, tracked.firstConfirmDiscontinuityCount)
            assertSame(expected, tracked.expectedApplied)
            assertSame("pending descriptor retained", descriptor, tracked.releaseDescriptor)
            assertEquals(state, tracked.command.lifecycleState)
        }
    }

    suspend fun seed(withControls: Boolean = true) {
        controlTestTimeout("accumulation fixture seed") {
            storage.data.updateData {
                NamespaceSettlementFixtures.raw(requests = JsonArray(listOf(profile.guard.toPayloadEntry().fields)).toString())
                    .toMutablePreferences().apply {
                        this[ControlStoreTestStorage.HOLD] = "[${ControlObligationFixtures.hold}]"
                        this[ControlStoreTestStorage.RECOVERY] = "[${ControlObligationFixtures.recovery}]"
                        this[ControlStoreTestStorage.EXTRA] = profile.text
                        this[byteArrayPreferencesKey("future_bytes")] = profile.text.toByteArray(Charsets.UTF_8)
                    }
            }
        }
        assertReadable(disk())
        if (!withControls) return
        rotation = control.prepareRotation(listOf(ControlObligationFixtures.node(NamespaceSettlementFixtures.user)),
            NamespaceSettlementFixtures.fence, NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
        assertTrue(controlTestTimeout("rotation control") {
            control.execute(rotation, NamespaceSettlementFixtures.context)
        } is ControlStoreResult.Confirmed)
        waiting = add(); executeConfirmed(waiting)
        landed = add(); storage.storage.afterScope = true
        assertTrue(execute(landed) is ControlStoreResult.Unconfirmed)
        assertNotNull(own(disk(), landed))
        unlanded = add(); storage.storage.before = true
        assertTrue(execute(unlanded) is ControlStoreResult.Unconfirmed)
        assertNull(own(disk(), unlanded))
        pending = add(); executeConfirmed(pending); storage.storage.before = true
        assertTrue(release(pending) is ControlCommandReleaseResult.Unconfirmed)
        prepared = add()
        // Drain the owner's required read-back with an actual observation, without resolving controls.
        assertTrue(controlTestTimeout("current lifetime reclamation observation") {
            control.reclaimPreviousLifetimeEvidence()
        } is ControlEvidenceReclamationResult.Confirmed)
        assertEquals(setOf(landed, unlanded), tracker.recoverySnapshot().unresolvedCommands)
        assertEquals(setOf(pending), tracker.recoverySnapshot().pendingReleases)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, pending.lifecycleState)
        assertNotNull(checkNotNull(tracker.findPrepared(pending)).releaseDescriptor)
        for (command in listOf(landed, unlanded)) {
            val rejected = release(command)
            assertTrue(rejected is ControlCommandReleaseResult.Rejected)
            assertEquals(ReleaseRejectionReason.Unresolved, (rejected as ControlCommandReleaseResult.Rejected).reason)
        }
        val raw = disk()
        assertReadable(raw)
        controls = commands.toMap()
        assertEquals(6, controls.size)
        histories = controls.values.map(::RetainedHistory)
        baselineRows = rows(raw)
        assertEquals(4, baselineRows.size)
        baselineWork = tracker.recoverySnapshot()
        stableValues = without(raw, ControlStoreTestStorage.DEMAND, ControlStoreTestStorage.BARRIER)
        assertControls(raw)
    }

    private fun add() = control.prepare(control.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    })

    suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    fun read(raw: Preferences) = reader.read(raw) as ControlRecordRead.Supported
    fun rows(raw: Preferences): List<PayloadEntry> = (read(raw).metadata as ControlMetadataRead.V2).evidence.entries
        .map { (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry() }
    fun own(raw: Preferences, command: CommandRef) = ControlAppliedEvidence.own(read(raw), command)
    fun guard(raw: Preferences): ControlNode = read(raw).arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().single { it.value.id == profile.text }.original
    private fun assertReadable(raw: Preferences) {
        val classified = read(raw)
        assertFalse("all profile obligations interpretable", classified.hasUninterpretable)
        assertFalse("all profile metadata interpretable", classified.hasUninterpretableMetadata)
        for (key in ControlRecordKeys.allPayloads) {
            val parsed = ControlPayloadCodec().decode(checkNotNull(raw[ControlRecordKeys.payload(key)]))
            assertTrue(parsed is PayloadRead.Parsed)
            assertTrue(ControlPayloadCodec().encode((parsed as PayloadRead.Parsed).entries) is PayloadWrite.Encoded)
        }
    }

    fun prepareFloor(raw: Preferences, elapsedMillis: Long = ++tick): CommandRef = control.prepare(control.recordFloor(
        guard(raw), BootReading(profile.boot, elapsedMillis), 1_000, profile.origin
    )).also {
        assertEquals(it.id, UUID.fromString(it.id).toString())
        assertEquals(it.ownerTrackingLifetimeId.value, UUID.fromString(it.ownerTrackingLifetimeId.value).toString())
    }
    suspend fun execute(command: CommandRef) = controlTestTimeout("accumulation execute") { control.execute(command) }
    suspend fun executeConfirmed(command: CommandRef): ControlStoreResult.Confirmed {
        val result = execute(command)
        assertTrue("business confirmation: $result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    suspend fun release(command: CommandRef) = controlTestTimeout("accumulation release") { control.releaseAfterConsumption(command) }

    suspend fun footprint(raw: Preferences): ReleaseFootprint {
        val bytes = ControlRecordKeys.allPayloads.map { checkNotNull(raw[ControlRecordKeys.payload(it)]).toByteArray(Charsets.UTF_8).size }
        val buffer = Buffer()
        PreferencesSerializer.writeTo(raw, buffer)
        assertEquals("actual serialized record/file size", buffer.size, file.length())
        assertEquals("file is authoritative", raw.toMutablePreferences(), disk().toMutablePreferences())
        return ReleaseFootprint(bytes, buffer.size, file.length(), rows(raw).size, commands.size,
            tracker.recoverySnapshot().unresolvedCommands.size, tracker.recoverySnapshot().pendingReleases.size, tracker.executing.size)
    }

    fun assertControls(raw: Preferences) {
        assertSame(lifetime, tracker.lifetimeId)
        assertSame(tracker, ControlCommandTracking.forOwner(storage.owner))
        assertEquals("only active controls strongly retained", controls, commands.toMap())
        assertEquals("business unresolved controls retained", baselineWork.unresolvedCommands, tracker.recoverySnapshot().unresolvedCommands)
        assertEquals("management pending control retained", baselineWork.pendingReleases, tracker.recoverySnapshot().pendingReleases)
        assertTrue("completed executing lease removed", tracker.executing.isEmpty())
        assertEquals("control evidence values and order", baselineRows, rows(raw))
        assertEquals("non-floor obligations, settled seals, fence and external values", stableValues,
            without(raw, ControlStoreTestStorage.DEMAND, ControlStoreTestStorage.BARRIER))
        histories.forEach { it.assertUnchanged() }
        assertNotNull(own(raw, waiting)); assertNotNull(own(raw, landed)); assertNull(own(raw, unlanded))
        assertNotNull(own(raw, pending)); assertNotNull(own(raw, rotation)); assertNull(own(raw, prepared))
    }

    suspend fun cycle(): ReleaseCycleSample {
        val initial = disk()
        val command = prepareFloor(initial)
        val confirmed = executeConfirmed(command)
        val tracked = checkNotNull(tracker.findPrepared(command))
        val first = tracked.firstConfirmDiscontinuityCount
        val expected = tracked.expectedApplied
        val before = disk()
        assertNotEquals("floor really changes", initial[ControlStoreTestStorage.DEMAND], before[ControlStoreTestStorage.DEMAND])
        val floor = (guard(before).child("floor") as FieldRead.Present).value
        assertEquals(FieldRead.Present(profile.boot), floor.text("anchorBootId"))
        assertEquals(FieldRead.Present(tick), floor.integer("anchorElapsedMillis"))
        assertEquals(FieldRead.Present(1_000L), floor.integer("waitMillis"))
        assertEquals(FieldRead.Present(profile.origin.value), floor.text("originLifetimeId"))
        fun demandSiblings(raw: Preferences) = read(raw).arrays.getValue(ControlKind.DEMAND).entries
            .filterIsInstance<ControlEntryRead.Interpreted>().filterNot { it.value.id == profile.text }
            .map { it.original.toPayloadEntry() }
        assertEquals("floor edit preserves sibling demands", demandSiblings(initial), demandSiblings(before))
        assertEquals("one new Applied", baselineRows.size + 1, rows(before).size)
        assertNotNull(own(before, command))
        assertEquals(controls.size + 1, commands.size)
        assertEquals(before, confirmed.snapshot.record.original)
        val beforeBytes = footprint(before)
        // This synchronous test caller consumes Confirmed here; execute has returned and there is
        // no remaining business worker. Only the numeric sample outlives this consumption/release.
        val result = release(command)
        assertTrue("owner confirmed release: $result", result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        val after = disk()
        assertEquals(result.snapshot.record.original, after)
        assertEquals("release preserves all non-evidence values, including the new floor",
            without(before, evidenceKey), without(after, evidenceKey))
        assertEquals(ControlCommandLifecycle.RELEASED, command.lifecycleState)
        assertNull("finished command removed", tracker.findPrepared(command))
        assertNull("own Applied removed", own(after, command))
        assertEquals(first, tracked.firstConfirmDiscontinuityCount)
        assertSame(expected, tracked.expectedApplied)
        assertTrue(tracked.observedApplied.get())
        assertEquals(baselineWork.unresolvedCommands, result.localUnresolvedCommands)
        assertEquals(baselineWork.pendingReleases, result.localPendingReleases)
        assertControls(after)
        val afterBytes = footprint(after)
        assertTrue("evidence reclamation shrinks UTF-8 payload", beforeBytes.payloadTotal > afterBytes.payloadTotal)
        assertTrue("evidence reclamation shrinks serialized record", beforeBytes.serializedBytes > afterBytes.serializedBytes)
        return ReleaseCycleSample(beforeBytes, afterBytes)
    }

    suspend fun close() = storage.close()

    companion object {
        val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        fun without(raw: Preferences, vararg keys: Preferences.Key<*>) = raw.toMutablePreferences().apply { keys.forEach { remove(it) } }
    }
}
