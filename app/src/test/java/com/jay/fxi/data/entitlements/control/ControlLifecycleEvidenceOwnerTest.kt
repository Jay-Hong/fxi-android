package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import java.io.File
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Seeded wire is an observation fixture, never a claim that a 5b–5e writer already exists. */
class ControlLifecycleEvidenceOwnerTest {
    @get:Rule val folder = TemporaryFolder()
    private var updates = 0
    private var failBeforeSnapshot = false
    private val storage by lazy { ControlStoreTestStorage(File(folder.root, "lifecycle.preferences_pb")) { delegate ->
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                updates++
                if (failBeforeSnapshot) { failBeforeSnapshot = false; throw IOException("no actual snapshot") }
                return delegate.updateData(transform)
            }
        }
    } }
    private val tracking get() = ControlCommandTracking.forOwner(storage.owner)
    @After fun close() = runReleaseTest { storage.close() }
    private suspend fun seed(raw: Preferences) = controlTestTimeout("lifecycle seed") { storage.data.updateData { raw } }
    private fun register(input: ControlLifecycleDescriptor = F.descriptor()): CommandRef = tracking.registerPrepared(F.command(input, tracking.lifetimeId))
    private fun history(c: CommandRef) = checkNotNull(tracking.findPrepared(c))
    private suspend fun execute(c: CommandRef) = controlTestTimeout("lifecycle execute") { storage.control.execute(c, F.context) }
    private fun noBarrier(raw: Preferences) = raw.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()

    private suspend fun opaqueThenLost(id: String, metadata: Boolean) {
        val c = register()
        val raw = F.raw(evidence = "[${F.wire(c)}${if (metadata) ",{}" else ""}]", hold = if (metadata) "[]" else "[{}]")
        val read = F.read(raw)
        assertNotNull(ControlAppliedEvidence.own(read, c))
        assertTrue(ControlAppliedEvidence.matches(c, history(c), checkNotNull(ControlAppliedEvidence.own(read, c))))
        seed(raw)
        val beforeWrites = storage.storage.writes
        val first = execute(c)
        assertTrue(first is ControlStoreResult.RecoveryRequired)
        val observed = history(c).observedApplied.get()
        assertEquals(beforeWrites, storage.storage.writes)
        seed(F.raw())
        val lost = execute(c)
        assertTrue(F.retry(id), observed && lost is ControlStoreResult.RecoveryRequired && lost.reason == RecoveryReason.CommandEvidenceLost)
        assertEquals(F.raw(), storage.raw())
    }
    @Test fun R04a_ownBeforeOpaqueObligation() = runReleaseTest { opaqueThenLost("R04a", false) }
    @Test fun R04b_ownBeforeOpaqueMetadata() = runReleaseTest { opaqueThenLost("R04b", true) }

    @Test fun R06c_opaqueOwnNeverObserved() = runReleaseTest {
        val c = register()
        seed(F.raw(evidence = "[${F.wire(c)},${F.wire(c)}]"))
        val result = execute(c)
        assertTrue(F.retry("R06c"), result is ControlStoreResult.RecoveryRequired && !history(c).observedApplied.get())
        assertEquals(LifecycleOwnEvidence.Uninterpretable, result.lifecycleDiagnostic!!.ownEvidence)
    }
    @Test fun R07_lostPrecedesConfirmedAndContinuity() = runReleaseTest {
        val c = register()
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        assertTrue(execute(c) is ControlStoreResult.Confirmed)
        seed(F.raw(schema = 1))
        execute(c)
        seed(F.raw())
        val result = execute(c)
        assertTrue(history(c).confirmed.get())
        assertTrue(tracking.evidenceDiscontinuityCount > history(c).firstConfirmDiscontinuityCount)
        assertTrue(F.retry("R07"), result is ControlStoreResult.RecoveryRequired && result.reason == RecoveryReason.CommandEvidenceLost)
    }
    private suspend fun discontinuity(id: String, broken: Preferences) {
        val c = register()
        // No writer in 5a: pin the previously requested boundary as a typed history fixture.
        history(c).bindFirstConfirm(BigInteger.ZERO)
        history(c).confirmationRequested.set(true)
        tracking.markUnresolved(c)
        seed(broken)
        execute(c)
        assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
        seed(F.raw(demand = "[${ControlObligationFixtures.emptyGuard}]"))
        val result = execute(c)
        assertTrue(F.retry(id), result is ControlStoreResult.RecoveryRequired && result.reason == RecoveryReason.CommandEvidenceContinuityLost)
        assertEquals(BigInteger.ZERO, history(c).firstConfirmDiscontinuityCount)
        assertEquals(setOf(c), result.localUnresolvedCommands)
    }
    @Test fun R08a_schemaOneDiscontinuity() = runReleaseTest { discontinuity("R08a", F.raw(schema = 1)) }
    @Test fun R08b_missingKeysDiscontinuity() = runReleaseTest { discontinuity("R08b", emptyPreferences()) }
    @Test fun R08c_unreadableDiscontinuity() = runReleaseTest { discontinuity("R08c", F.raw(evidence = "{")) }

    @Test fun R09_prepareAndNegativeDoNotBindFirstConfirm() = runReleaseTest {
        val c = register()
        assertNull(history(c).firstConfirmDiscontinuityCount)
        seed(F.raw(demand = "[${ControlObligationFixtures.emptyGuard}]"))
        assertTrue(execute(c) is ControlStoreResult.Rejected)
        assertNull(history(c).firstConfirmDiscontinuityCount)
        seed(F.raw(schema = 1))
        execute(c)
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        val result = execute(c)
        assertTrue(F.retry("R09"), result is ControlStoreResult.Confirmed && history(c).firstConfirmDiscontinuityCount == BigInteger.ONE)
    }
    @Test fun R10a_matchingOwnIgnoresDifferentCount() = runReleaseTest {
        val c = register()
        history(c).bindFirstConfirm(BigInteger.ZERO)
        seed(F.raw(schema = 1))
        execute(c)
        val landed = F.raw(evidence = " [ ${F.wire(c)} ] ")
        seed(landed)
        val result = execute(c)
        assertTrue(F.retry("R10a"), result is ControlStoreResult.Confirmed && result.effect == ConfirmedEffect.PostconditionConfirmed)
        assertEquals(noBarrier(landed), noBarrier(storage.raw()))
        assertEquals(BigInteger.ZERO, history(c).firstConfirmDiscontinuityCount)
    }
    @Test fun R10b_matchingOwnWithRestoredPreimageCannotReapply() = runReleaseTest {
        val c = register()
        val raw = F.raw(demand = "[${ControlObligationFixtures.emptyGuard}]", evidence = "[${F.wire(c)}]")
        seed(raw)
        val writes = storage.storage.writes
        val result = execute(c)
        assertTrue(F.retry("R10b"), result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetChanged)
        assertEquals(writes, storage.storage.writes)
        assertEquals(raw, storage.raw())
    }
    @Test fun R10c_createSuccessorAbsentCannotConfirm() = runReleaseTest {
        val c = register(F.descriptor(transition = LifecycleTransition.SETTLE_QUERY, targets = listOf(F.createRequest)))
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        tracking.markUnresolved(c)
        val writes = storage.storage.writes
        val result = execute(c)
        assertTrue(F.retry("R10c"), result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetMissing)
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertEquals(writes, storage.storage.writes)
        assertEquals(LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, result.lifecycleDiagnostic!!.classification)
    }
    private fun matchingConfirmationFixture(c: CommandRef, raw: Preferences,
        transition: LifecycleTransition, target: LifecycleTarget): ControlRecordRead.Supported {
        val input = (c.body as ControlCommandBody.Lifecycle).input
        assertEquals("lc", c.id)
        assertEquals("lc", input.operationId)
        assertEquals(transition, input.transition)
        assertEquals(listOf(target), input.targets.map { it.target })
        assertTrue(input.requiredUnchanged.isEmpty())
        assertNull(input.namespace)
        assertNull(input.executor)
        val read = F.read(raw)
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val own = (read.metadata as ControlMetadataRead.V2).evidence.entries.single() as ControlEvidenceEntryRead.Interpreted
        val row = own.value as AppliedEvidence.Lifecycle
        assertEquals("lc", row.commandId)
        assertEquals(tracking.lifetimeId.value, row.ownerTrackingLifetimeId)
        assertEquals(transition, row.transition)
        assertEquals(listOf(target), row.targets)
        assertNull(history(c).expectedApplied)
        assertFalse(history(c).observedApplied.get())
        assertFalse(history(c).confirmed.get())
        assertFalse(history(c).confirmationRequested.get())
        assertNull(history(c).firstConfirmDiscontinuityCount)
        assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
        return read
    }
    private fun changedRequestFixture(c: CommandRef, raw: Preferences,
        transition: LifecycleTransition, effect: LifecycleEffect) {
        val read = matchingConfirmationFixture(c, raw, transition, LifecycleTarget(ControlKind.DEMAND, "d", effect))
        assertEquals("A", raw[OWNER_UID])
        assertEquals("u", raw[USER_EPOCH])
        assertEquals("k", raw[KRX_EPOCH])
        assertNull(raw[TEARDOWN_OWED_FOR])
        assertNull(raw[PURGE_JOURNAL])
        assertNull(raw[MAY_CONTAIN_PREMIUM])
        assertNull(raw[MAY_CONTAIN_KRX])
        val fixed = (c.body as ControlCommandBody.Lifecycle).input.targets.single()
        assertEquals(LifecycleRole.REQUEST, fixed.role)
        val location = read.locations("d").single()
        assertEquals(ControlKind.DEMAND, location.first)
        val actual = (location.second as ControlEntryRead.Interpreted).original.toPayloadEntry().fields
        assertEquals(Json.parseToJsonElement(ControlObligationFixtures.request), actual)
        assertEquals(JsonPrimitive("IF_STALE"), actual["intent"])
        val after = checkNotNull(fixed.after).toPayloadEntry().fields
        assertEquals(actual - "intent", after - "intent")
        assertEquals(JsonPrimitive("FORCE_PREMIUM"), after["intent"])
        assertNotEquals(actual, after)
        if (effect == LifecycleEffect.CREATE) assertNull(fixed.before)
        else assertEquals(actual, checkNotNull(fixed.before).toPayloadEntry().fields)
    }
    @Test fun R10d_createSuccessorChangedCannotConfirm() = runReleaseTest {
        val c = register(F.descriptor(transition = LifecycleTransition.SETTLE_QUERY, targets = listOf(F.createRequest)))
        val raw = F.raw(demand = "[${ControlObligationFixtures.request}]", evidence = "[${F.wire(c)}]")
        changedRequestFixture(c, raw, LifecycleTransition.SETTLE_QUERY, LifecycleEffect.CREATE)
        seed(raw)
        tracking.markUnresolved(c)
        assertEquals(setOf(c), tracking.snapshot())
        val writes = storage.storage.writes
        val result = execute(c)
        assertFalse(F.retry("R10d"), result is ControlStoreResult.Confirmed)
        assertTrue(result is ControlStoreResult.Conflict)
        assertEquals(ConflictReason.TargetChanged, (result as ControlStoreResult.Conflict).reason)
        assertEquals(writes, storage.storage.writes)
        assertEquals(raw, storage.raw())
        assertFalse(history(c).confirmationRequested.get())
        assertFalse(history(c).confirmed.get())
        assertNull(history(c).firstConfirmDiscontinuityCount)
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertEquals(setOf(c), tracking.snapshot())
    }
    @Test fun R10e_replaceRestoredPreimageCannotConfirm() = runReleaseTest {
        val c = register(F.descriptor(transition = LifecycleTransition.REBIND_REQUESTS, targets = listOf(F.replaceRequest)))
        val raw = F.raw(demand = "[${ControlObligationFixtures.request}]", evidence = "[${F.wire(c)}]")
        changedRequestFixture(c, raw, LifecycleTransition.REBIND_REQUESTS, LifecycleEffect.REPLACE)
        seed(raw)
        tracking.markUnresolved(c)
        assertEquals(setOf(c), tracking.snapshot())
        val writes = storage.storage.writes
        val result = execute(c)
        assertFalse(F.retry("R10e"), result is ControlStoreResult.Confirmed)
        assertTrue(result is ControlStoreResult.Conflict)
        assertEquals(ConflictReason.TargetChanged, (result as ControlStoreResult.Conflict).reason)
        assertEquals(writes, storage.storage.writes)
        assertEquals(raw, storage.raw())
        assertFalse(history(c).confirmationRequested.get())
        assertFalse(history(c).confirmed.get())
        assertNull(history(c).firstConfirmDiscontinuityCount)
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertEquals(setOf(c), tracking.snapshot())
    }
    private suspend fun unreadableEpochCannotConfirm(id: String, rawChange: (MutablePreferences) -> Unit) {
        val c = register()
        val raw = F.raw(evidence = "[${F.wire(c)}]").toMutablePreferences().apply(rawChange).toPreferences()
        val read = matchingConfirmationFixture(c, raw, LifecycleTransition.REMOVE_EMPTY_GUARD,
            LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE))
        assertTrue(read.locations("g").isEmpty())
        assertEquals(LifecycleRole.GUARD, (c.body as ControlCommandBody.Lifecycle).input.targets.single().role)
        assertEquals(Json.parseToJsonElement(ControlObligationFixtures.emptyGuard), F.removeGuard.before!!.toPayloadEntry().fields)
        assertNull(F.removeGuard.after)
        seed(raw)
        tracking.markUnresolved(c)
        val writes = storage.storage.writes
        val result = execute(c)
        assertFalse(F.eligible(id), result is ControlStoreResult.Confirmed)
        assertTrue(result is ControlStoreResult.RecoveryRequired)
        assertEquals(RecoveryReason.UnreadableEpochState, (result as ControlStoreResult.RecoveryRequired).reason)
        assertEquals(writes, storage.storage.writes)
        assertEquals(raw, storage.raw())
        assertFalse(history(c).confirmationRequested.get())
        assertEquals(setOf(c), result.localUnresolvedCommands)
        assertEquals(setOf(c), tracking.snapshot())
    }
    @Test fun G01e_wrongEpochMarkerTypeCannotConfirm() = runReleaseTest {
        unreadableEpochCannotConfirm("G01e") { raw ->
            raw[stringPreferencesKey(MAY_CONTAIN_KRX.name)] = "false"
            assertEquals("false", raw.asMap().entries.single { it.key.name == MAY_CONTAIN_KRX.name }.value)
            assertEquals("A", raw[OWNER_UID])
            assertEquals("u", raw[USER_EPOCH])
            assertEquals("k", raw[KRX_EPOCH])
            assertNull(raw[TEARDOWN_OWED_FOR])
            assertNull(raw[PURGE_JOURNAL])
            assertNull(raw[MAY_CONTAIN_PREMIUM])
        }
    }
    @Test fun G01f_emptyEpochCannotConfirm() = runReleaseTest {
        unreadableEpochCannotConfirm("G01f") { raw ->
            raw[USER_EPOCH] = ""
            assertEquals("A", raw[OWNER_UID])
            assertEquals("", raw[USER_EPOCH])
            assertEquals("k", raw[KRX_EPOCH])
            assertNull(raw[TEARDOWN_OWED_FOR])
            assertNull(raw[PURGE_JOURNAL])
            assertNull(raw[MAY_CONTAIN_PREMIUM])
            assertNull(raw[MAY_CONTAIN_KRX])
        }
    }
    @Test fun R11a_candidateAndReturnedReadDoNotCount() = runReleaseTest {
        val c = register()
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        assertTrue(execute(c) is ControlStoreResult.Confirmed)
        assertEquals(F.retry("R11a"), BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
    }
    @Test fun R11b_ioWithoutSnapshotDoesNotCount() = runReleaseTest {
        val c = register()
        failBeforeSnapshot = true
        val result = execute(c)
        assertTrue(result is ControlStoreResult.Unconfirmed)
        assertNull(result.lifecycleDiagnostic)
        assertEquals(F.retry("R11b"), BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
        assertNull(history(c).firstConfirmDiscontinuityCount)
    }
    @Test fun R11c_normalUnrelatedEditDoesNotCount() = runReleaseTest {
        val c = register()
        seed(F.raw().toMutablePreferences().apply { this[stringPreferencesKey("unrelated")] = "changed" })
        execute(c)
        assertEquals(F.retry("R11c"), BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
    }
    @Test fun R13b_releaseRefusesConfirmedLifecycle() = runReleaseTest {
        val c = register()
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        assertTrue(execute(c) is ControlStoreResult.Confirmed)
        val before = storage.raw()
        val reads = updates
        val result = controlTestTimeout("lifecycle release") { storage.control.releaseAfterConsumption(c) }
        assertTrue(F.retry("R13b"), result is ControlCommandReleaseResult.Rejected && result.reason == ReleaseRejectionReason.UnsupportedCommandKind)
        assertEquals(reads, updates)
        assertEquals(before, storage.raw())
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNotNull(tracking.findPrepared(c))
    }
    private suspend fun terminal(id: String, released: Boolean, entry: Int) {
        val c = register()
        seed(F.raw())
        c.beginRelease()
        if (released) c.completeRelease()
        val reads = updates
        val result = controlTestTimeout("terminal lifecycle") {
            when (entry) {
                0 -> storage.control.execute(c)
                1 -> storage.control.execute(c, F.context)
                else -> storage.control.confirmPrevious(c)
            }
        }
        assertTrue(F.retry(id), updates == reads && result.localUnresolvedCommands.isEmpty() &&
            if (released) result is ControlStoreResult.Released else result is ControlStoreResult.ReleasePending)
        assertNull(c.lastLifecycleDiagnostic)
    }
    @Test fun R16a_pendingExecute() = runReleaseTest { terminal("R16a", false, 0) }
    @Test fun R16b_pendingContext() = runReleaseTest { terminal("R16b", false, 1) }
    @Test fun R16c_pendingPrevious() = runReleaseTest { terminal("R16c", false, 2) }
    @Test fun R16d_releasedExecute() = runReleaseTest { terminal("R16d", true, 0) }
    @Test fun R16e_releasedContext() = runReleaseTest { terminal("R16e", true, 1) }
    @Test fun R16f_releasedPrevious() = runReleaseTest { terminal("R16f", true, 2) }

    @Test fun previousRefUsesLifecycleConfirmationWithoutCheckpointOrRegistration() = runReleaseTest {
        val c = F.command()
        seed(F.raw(evidence = "[${F.wire(c)}]"))
        val result = controlTestTimeout("previous lifecycle") { storage.control.confirmPrevious(c) }
        assertTrue(F.retry("R12.common"), result is ControlStoreResult.Confirmed)
        assertNull(tracking.findPrepared(c))
        assertNotNull((result as ControlStoreResult.Confirmed).lifecycleReceipt)
    }
    @Test fun absenceAloneCannotConfirmPrevious() = runReleaseTest {
        val c = F.command()
        seed(F.raw())
        val result = controlTestTimeout("unproven previous lifecycle") { storage.control.confirmPrevious(c) }
        assertTrue(F.retry("R12.absent"), result is ControlStoreResult.Unconfirmed && result.reason == UnconfirmedReason.HistoryUnavailable)
    }
    @Test fun noBusinessWriterCanApplyPreparedDescriptor() = runReleaseTest {
        val c = register()
        val before = F.raw(demand = "[${ControlObligationFixtures.emptyGuard}]")
        seed(before)
        val result = execute(c)
        assertTrue(F.atomic("5a.writer"), result is ControlStoreResult.Rejected && storage.raw() == before)
        assertEquals("LifecycleWriterUnavailable", ((result as ControlStoreResult.Rejected).reason as RejectionReason.InvalidRequest).detail)
        assertFalse(history(c).confirmationRequested.get())
        assertTrue(result.localUnresolvedCommands.isEmpty())
    }
}
