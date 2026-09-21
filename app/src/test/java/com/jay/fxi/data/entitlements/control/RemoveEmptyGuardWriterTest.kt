package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.buffer
import okio.source
import java.io.File
import java.math.BigInteger
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive

class RemoveEmptyGuardWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("empty guard cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private val request = DemandAuthFixtures.request()
    private fun unchanged(before: Preferences, after: Preferences): Boolean {
        fun remainder(p: Preferences) = p.toMutablePreferences().apply {
            remove(ControlRecordKeys.payload(ControlKind.DEMAND)); remove(ControlLifecycleEvidenceFixtures.evidenceKey); remove(ControlStoreTestStorage.BARRIER)
        }
        return remainder(before) == remainder(after)
    }
    private fun landing(id: String, c: CommandRef, before: Preferences, after: Preferences) {
        val read = F.read(after)
        assertFalse(F.atomic(id), read.hasUninterpretable)
        assertEquals(F.atomic(id), listOf("r"), read.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>().map { it.value.id })
        assertEquals(F.atomic(id), "[${ControlLifecycleEvidenceFixtures.wire(c)}]", after[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertTrue(F.atomic(id), unchanged(before, after))
    }
    private suspend fun storage(id: String, mode: String, restart: Boolean = false) {
        val file = folder.newFile(); val s = open(file); val before = F.raw(F.empty, request, hold = F.hold())
        controlTestTimeout("empty guard seed") { s.data.updateData { before } }
        val c = s.control.prepareRemoveEmptyGuard(F.empty)
        val fixed = (c.body as ControlCommandBody.Lifecycle).input.targets
        if (mode == "before") s.storage.before = true
        if (mode == "after") s.storage.afterScope = true
        val first = controlTestTimeout("empty guard first attempt") { s.control.execute(c) }
        assertEquals(F.retry(id), c.id, first.command.id)
        if (mode == "success") assertTrue(F.retry(id), first is ControlStoreResult.Confirmed) else {
            assertTrue(F.retry(id), first is ControlStoreResult.Unconfirmed)
            val tracked = ControlCommandTracking.forOwner(s.owner).findPrepared(c)!!
            assertNotNull(F.retry(id), tracked.firstConfirmDiscontinuityCount)
            assertTrue(F.retry(id), tracked.confirmationRequested.get())
            assertEquals(F.retry(id), setOf(c), first.localUnresolvedCommands)
        }
        if (mode == "before") assertEquals(F.atomic(id), before, disk(file)) else landing(id, c, before, disk(file))
        if (restart) {
            s.close(); val next = open(file); next.raw()
            val result = controlTestTimeout("empty guard same-file restart") { next.control.confirmPrevious(c) }
            assertTrue(F.retry(id), result is ControlStoreResult.Confirmed)
            assertNull(F.retry(id), ControlCommandTracking.forOwner(next.owner).findPrepared(c))
        } else {
            val retry = controlTestTimeout("empty guard fixed retry") { s.control.execute(c) }
            assertTrue(F.retry(id), retry is ControlStoreResult.Confirmed)
            assertEquals(F.retry(id), c.id, retry.command.id)
            assertEquals(F.retry(id), fixed, (c.body as ControlCommandBody.Lifecycle).input.targets)
        }
        landing(id, c, before, disk(file))
    }
    private suspend fun cancellation(id: String, observed: Boolean = false) = coroutineScope {
        val file = folder.newFile(); val s = open(file); val before = F.raw(F.empty, request, hold = F.hold())
        controlTestTimeout("cancel seed") { s.data.updateData { before } }
        val c = s.control.prepareRemoveEmptyGuard(F.empty)
        if (observed) {
            s.storage.afterScope = true
            assertTrue(controlTestTimeout("land before observation") { s.control.execute(c) } is ControlStoreResult.Unconfirmed)
        }
        val gate = ControlStoreTestStorage.Pause(); s.storage.pauseAfterScope = gate
        val caller = async { s.control.execute(c) }
        try {
            controlTestTimeout("empty guard cancellation gate") { gate.reached.await() }
            caller.cancelAndJoinForTest()
            val t = ControlCommandTracking.forOwner(s.owner)
            val h = t.findPrepared(c)!!
            assertEquals(F.retry(id), setOf(c), t.snapshot())
            assertTrue(F.retry(id), h.confirmationRequested.get())
            assertNotNull(F.retry(id), h.expectedApplied)
            assertNotNull(F.retry(id), h.firstConfirmDiscontinuityCount)
            if (observed) assertTrue(F.retry(id), h.observedApplied.get())
            assertFalse(F.retry(id), h.confirmed.get())
        } finally { gate.release.complete(Unit); caller.cancelAndJoinForTest() }
        s.close(); val next = open(file); next.raw()
        landing(id, c, before, disk(file))
        assertTrue(F.retry(id), controlTestTimeout("cancel restart") { next.control.confirmPrevious(c) } is ControlStoreResult.Confirmed)
        assertNull(ControlCommandTracking.forOwner(next.owner).findPrepared(c))
    }
    @Test fun success() = runReleaseTest { storage("EG_success", "success") }
    @Test fun R01() = runReleaseTest { storage("EG_R01", "before") }
    @Test fun R01id() = runReleaseTest { storage("EG_R01id", "before") }
    @Test fun R02() = runReleaseTest { storage("EG_R02", "after") }
    @Test fun R12() = runReleaseTest { storage("EG_R12", "after", true) }
    @Test fun R03() = runReleaseTest { cancellation("EG_R03") }
    @Test fun R03expected() = runReleaseTest { cancellation("EG_R03expected") }
    @Test fun R03requested() = runReleaseTest { cancellation("EG_R03requested") }
    @Test fun R03baseline() = runReleaseTest { cancellation("EG_R03baseline") }
    @Test fun R03observed() = runReleaseTest { cancellation("EG_R03observed", true) }
    private suspend fun notEmpty(id: String, g: ControlNode, requests: Boolean = true) {
        val s = open(folder.newFile()); val raw = if (requests) F.raw(g, request) else F.raw(g)
        assertNotNull(ControlSchema.read(ControlKind.DEMAND, g))
        val keys = g.toPayloadEntry().fields.keys
        assertEquals(1, listOf("floor", "auth").count { it in keys })
        controlTestTimeout("nonempty seed") { s.data.updateData { raw } }
        val result = controlTestTimeout("nonempty attempt") { s.control.execute(s.control.prepareRemoveEmptyGuard(g)) }
        assertTrue(F.eligible(id), result is ControlStoreResult.Rejected)
        assertEquals(F.eligible(id), raw, s.raw().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) })
    }
    @Test fun F06a_zero() = runReleaseTest { notEmpty("F06a_zero", F.guard(F.floor(0))) }
    @Test fun F06a_positive() = runReleaseTest { notEmpty("F06a_positive", F.guard()) }
    @Test fun F06a_noRequests() = runReleaseTest { notEmpty("F06a_noRequests", F.guard(F.floor(0)), false) }
    @Test fun F06b_resumed() = runReleaseTest { notEmpty("F06b_resumed", F.guard(null, true)) }
    @Test fun F06b_noRequests() = runReleaseTest { notEmpty("F06b_noRequests", F.guard(null, true), false) }
    @Test fun admission() = runReleaseTest { notEmpty("EG_admission", F.guard()) }
    @Test fun noRequestsCanRemoveEmptyGuard() = runReleaseTest {
        val s = open(folder.newFile()); controlTestTimeout("no requests seed") { s.data.updateData { F.raw(F.empty) } }
        assertTrue(controlTestTimeout("no requests cleanup") { s.control.execute(s.control.prepareRemoveEmptyGuard(F.empty)) } is ControlStoreResult.Confirmed)
    }
    @Test fun missingIsNotFreshSuccess() = runReleaseTest {
        val s = open(folder.newFile()); val before = F.raw(request)
        controlTestTimeout("missing seed") { s.data.updateData { before } }
        val result = controlTestTimeout("missing cleanup") { s.control.execute(s.control.prepareRemoveEmptyGuard(F.empty)) }
        assertTrue(F.eligible("EG_missing"), result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetMissing)
        assertEquals(before, s.raw().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) })
    }
    @Test fun guardReplacedAfterPrepare() = runReleaseTest {
        val s = open(folder.newFile()); val c = s.control.prepareRemoveEmptyGuard(F.empty)
        val before = F.raw(F.guard(F.floor(0)), request)
        controlTestTimeout("guard race seed") { s.data.updateData { before } }
        val result = controlTestTimeout("guard race attempt") { s.control.execute(c) }
        assertTrue(F.eligible("EG_race"), result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetChanged)
        assertEquals(F.eligible("EG_race"), before, s.raw().toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) })
    }
    @Test fun R04_observationBeforeOpaqueReturn() = runReleaseTest {
        val s = open(folder.newFile()); val c = s.control.prepareRemoveEmptyGuard(F.empty)
        controlTestTimeout("own and opaque seed") { s.data.updateData { ControlLifecycleEvidenceFixtures.raw(
            evidence = "[${ControlLifecycleEvidenceFixtures.wire(c)}]", hold = "[{\"future\":true}]") } }
        assertTrue(controlTestTimeout("opaque rejection") { s.control.execute(c) } is ControlStoreResult.RecoveryRequired)
        assertTrue(F.retry("EG_R04"), ControlCommandTracking.forOwner(s.owner).findPrepared(c)!!.observedApplied.get())
        controlTestTimeout("remove opaque and evidence") { s.data.updateData { F.raw(F.empty) } }
        val result = controlTestTimeout("lost after opaque") { s.control.execute(c) }
        assertTrue(F.retry("EG_R04"), result is ControlStoreResult.RecoveryRequired && result.reason == RecoveryReason.CommandEvidenceLost)
    }
    private suspend fun builder(id: String) {
        val file = folder.newFile(); val store = open(file); val before = F.raw(F.empty, request, hold = F.hold())
        controlTestTimeout("builder seed") { store.data.updateData { before } }
        val c = store.control.prepareRemoveEmptyGuard(F.empty)
        val result = controlTestTimeout("builder candidate") { store.control.execute(c) }
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        landing(id, c, before, disk(file))
    }
    @Test fun builder_C01() = runReleaseTest { builder("EG_C01builder") }
    @Test fun builder_C02() = runReleaseTest { builder("EG_C02builder") }
    @Test fun builder_C10() = runReleaseTest { builder("EG_C10builder") }
    @Test fun builder_C11() = runReleaseTest { builder("EG_C11builder") }

    @Test fun C02_payloadWriter() = runReleaseTest {
        val id = "EG_C02payloadWriter"
        val file = folder.newFile(); val store = open(file)
        val strong = F.field(request, "intent", kotlinx.serialization.json.JsonPrimitive("FORCE_PREMIUM"))
        val before = F.raw(F.empty, strong, hold = F.hold())
        assertFalse(F.read(before).hasUninterpretable)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals(setOf("id", "kind"), F.empty.toPayloadEntry().fields.keys)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("FORCE_PREMIUM"), strong.toPayloadEntry().fields["intent"])
        controlTestTimeout("payload preservation seed") { store.data.updateData { before } }
        val c = store.control.prepareRemoveEmptyGuard(F.empty)
        val result = controlTestTimeout("payload preservation writer") { store.control.execute(c) }
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        // Compare the entire fixed raw REQUEST, not just its ID or a plan-derived expectation.
        assertEquals(F.atomic(id), "[${strong.toPayloadEntry().fields}]", after[ControlRecordKeys.payload(ControlKind.DEMAND)])
        assertEquals(F.atomic(id), "[${ControlLifecycleEvidenceFixtures.wire(command = c.id, lifetime = c.ownerTrackingLifetimeId.value)}]",
            after[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertTrue(F.atomic(id), unchanged(before, after))
    }

    private fun withoutBarrier(raw: Preferences): Preferences = raw.toMutablePreferences().apply {
        remove(ControlStoreTestStorage.BARRIER)
    }

    @Test fun R12_noFreshApply() = runReleaseTest {
        val id = "EG_R12apply"
        val file = folder.newFile(); val firstOwner = open(file)
        val before = F.raw(F.empty, request, hold = F.hold())
        assertEquals(2, F.read(before).schemaVersion)
        assertFalse(F.read(before).hasUninterpretable)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals(setOf("id", "kind"), F.empty.toPayloadEntry().fields.keys)
        assertEquals("[]", before[ControlLifecycleEvidenceFixtures.evidenceKey])
        controlTestTimeout("r7 R12 seed") { firstOwner.data.updateData { before } }
        val c = firstOwner.control.prepareRemoveEmptyGuard(F.empty)
        firstOwner.storage.before = true // No Applied may land: afterScope would mask this mutant.
        val failed = controlTestTimeout("r7 R12 unlanded attempt") { firstOwner.control.execute(c) }
        assertTrue(failed is ControlStoreResult.Unconfirmed)
        assertEquals(before, disk(file))
        assertEquals(BigInteger.ZERO, ControlCommandTracking.forOwner(firstOwner.owner).findPrepared(c)!!.firstConfirmDiscontinuityCount)
        // execute has returned; close its owner scope before reopening the same file.
        controlTestTimeout("r7 R12 old owner close") { firstOwner.close() }
        val next = open(file)
        assertEquals(before, next.raw())
        val tracker = ControlCommandTracking.forOwner(next.owner)
        assertNotSame(c.ownerTrackingLifetimeId, tracker.lifetimeId)
        assertNull(tracker.findPrepared(c))
        val result = controlTestTimeout("r7 R12 previous only") { next.control.confirmPrevious(c) }
        assertTrue(F.retry(id), result is ControlStoreResult.Unconfirmed)
        assertEquals(F.retry(id), before, withoutBarrier(disk(file)))
        assertNull(F.retry(id), tracker.findPrepared(c))
        assertEquals(UnconfirmedReason.HistoryUnavailable, (result as ControlStoreResult.Unconfirmed).reason)
    }

    @Test fun R01_baselineNotRebound() = runReleaseTest {
        val id = "EG_R01reset"
        val file = folder.newFile(); val s = open(file)
        val before = F.raw(F.empty, request, hold = F.hold())
        assertFalse(F.read(before).hasUninterpretable)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals(setOf("id", "kind"), F.empty.toPayloadEntry().fields.keys)
        controlTestTimeout("r7 R01 seed") { s.data.updateData { before } }
        val c = s.control.prepareRemoveEmptyGuard(F.empty)
        val tracker = ControlCommandTracking.forOwner(s.owner)
        val history = tracker.findPrepared(c)!!
        assertEquals(BigInteger.ZERO, tracker.evidenceDiscontinuityCount)
        assertNull(history.firstConfirmDiscontinuityCount)
        s.storage.before = true
        assertTrue(controlTestTimeout("r7 R01 unlanded attempt") { s.control.execute(c) } is ControlStoreResult.Unconfirmed)
        assertEquals(before, disk(file))
        assertEquals(BigInteger.ZERO, history.firstConfirmDiscontinuityCount)
        assertTrue(controlTestTimeout("r7 R01 fixed retry lands") { s.control.execute(c) } is ControlStoreResult.Confirmed)
        val landed = withoutBarrier(disk(file))
        val expectedWire = "[${ControlLifecycleEvidenceFixtures.wire(command = c.id, lifetime = c.ownerTrackingLifetimeId.value)}]"
        assertEquals(expectedWire, landed[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertEquals("[${request.toPayloadEntry().fields}]", landed[ControlRecordKeys.payload(ControlKind.DEMAND)])
        assertEquals(BigInteger.ZERO, history.firstConfirmDiscontinuityCount)
        controlTestTimeout("r7 R01 schema one seed") { s.data.updateData { ControlLifecycleEvidenceFixtures.raw(schema = 1) } }
        // Count changes only through an actual owner snapshot, not a typed tracking shortcut.
        assertTrue(controlTestTimeout("r7 R01 observe discontinuity") { s.control.execute(c) } is ControlStoreResult.RecoveryRequired)
        assertEquals(BigInteger.ONE, tracker.evidenceDiscontinuityCount)
        assertEquals(BigInteger.ZERO, history.firstConfirmDiscontinuityCount)
        controlTestTimeout("r7 R01 restore exact landing") { s.data.updateData { landed } }
        assertEquals(landed, s.raw())
        val confirmed = controlTestTimeout("r7 R01 confirm with different count") { s.control.execute(c) }
        assertTrue(F.retry(id), confirmed is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id), BigInteger.ZERO, history.firstConfirmDiscontinuityCount)
        assertEquals(F.retry(id), BigInteger.ONE, tracker.evidenceDiscontinuityCount)
        assertEquals(F.retry(id), landed, withoutBarrier(disk(file)))
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (confirmed as ControlStoreResult.Confirmed).effect)
    }

    /** All expected fields come from fixed fixture facts, never the descriptor or evidence builder. */
    private suspend fun exactNamedWriter(id: String) {
        val file = folder.newFile(); val s = open(file)
        val g = F.field(F.empty, "id", JsonPrimitive("r7-guard"))
        val before = F.raw(g, request, hold = F.hold())
        assertEquals(setOf("id", "kind"), g.toPayloadEntry().fields.keys)
        assertEquals(2, F.read(before).schemaVersion)
        assertFalse(F.read(before).hasUninterpretable)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals("[]", before[ControlLifecycleEvidenceFixtures.evidenceKey])
        controlTestTimeout("r7 exact writer seed") { s.data.updateData { before } }
        val c = s.control.prepareRemoveEmptyGuard(g)
        val expectedWire = "[${ControlLifecycleEvidenceFixtures.wire(
            targets = ControlLifecycleEvidenceFixtures.target(id = "r7-guard"),
            command = c.id, lifetime = c.ownerTrackingLifetimeId.value)}]"
        val result = controlTestTimeout("r7 exact named writer") { s.control.execute(c) }
        val after = withoutBarrier(disk(file))
        // Wire comparison precedes result classification: shared helper corruption may land.
        assertEquals(F.atomic(id), expectedWire, after[ControlLifecycleEvidenceFixtures.evidenceKey])
        assertEquals(F.atomic(id), "[${request.toPayloadEntry().fields}]", after[ControlRecordKeys.payload(ControlKind.DEMAND)])
        assertTrue(F.atomic(id), unchanged(before, after))
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertSame(F.atomic(id), c, result.command)
        assertEquals(F.atomic(id), ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(F.atomic(id), listOf("r7-guard"), result.effectiveIds)
        assertEquals(F.atomic(id), after, withoutBarrier(result.snapshot.record.original))
        val receipt = result.lifecycleReceipt
        assertNotNull(F.atomic(id), receipt)
        receipt!!
        assertEquals(F.atomic(id), LifecycleTransition.REMOVE_EMPTY_GUARD, receipt.transition)
        assertEquals(F.atomic(id), c.id, receipt.commandId)
        assertEquals(F.atomic(id), listOf(LifecycleObservedTarget(
            LifecycleTarget(ControlKind.DEMAND, "r7-guard", LifecycleEffect.REMOVE), LifecycleTargetObservation.Absent)), receipt.targets)
        assertEquals(F.atomic(id), listOf("r7-guard"), receipt.removedIds)
        assertTrue(F.atomic(id), receipt.createdIds.isEmpty())
        assertTrue(F.atomic(id), receipt.replacedIds.isEmpty())
        assertNull(F.atomic(id), receipt.before)
        assertNull(F.atomic(id), receipt.after)
        assertTrue(F.atomic(id), receipt.journal.isEmpty())
        assertTrue(F.atomic(id), receipt.requiredUnchanged.isEmpty())
        assertFalse(F.atomic(id), receipt.hasUninterpretable)
        assertFalse(F.atomic(id), receipt.hasUninterpretableMetadata)
    }
    @Test fun dispatch() = runReleaseTest { exactNamedWriter("EG_dispatch") }
    @Test fun C10_transitionBuilder() = runReleaseTest { exactNamedWriter("EG_C10transitionBuilder") }
    @Test fun C10_commandBuilder() = runReleaseTest { exactNamedWriter("EG_C10commandBuilder") }
    @Test fun C10_lifetimeBuilder() = runReleaseTest { exactNamedWriter("EG_C10lifetimeBuilder") }
    @Test fun C10_kindBuilder() = runReleaseTest { exactNamedWriter("EG_C10kindBuilder") }
    @Test fun C10_idBuilder() = runReleaseTest { exactNamedWriter("EG_C10idBuilder") }
    @Test fun C10_effectBuilder() = runReleaseTest { exactNamedWriter("EG_C10effectBuilder") }
    @Test fun C10_targetsBuilder() = runReleaseTest { exactNamedWriter("EG_C10targetsBuilder") }
    @Test fun output_effect() = runReleaseTest { exactNamedWriter("EG_outputEffect") }
    @Test fun output_ids() = runReleaseTest { exactNamedWriter("EG_outputIds") }
    @Test fun output_receipt() = runReleaseTest { exactNamedWriter("EG_outputReceipt") }
    @Test fun output_receiptSnapshot() = runReleaseTest { exactNamedWriter("EG_outputReceiptSnapshot") }

    private suspend fun descriptorFacts(id: String) {
        val s = open(folder.newFile())
        val expected = F.field(F.empty, "id", JsonPrimitive("r7-descriptor-guard"))
        assertEquals(setOf("id", "kind"), expected.toPayloadEntry().fields.keys)
        assertNotNull(guard(expected))
        val issued = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val message = F.atomic(id)
        // CommandRef also requires matching IDs. Lock the descriptor boundary first so its
        // corruption fails the designated assertion, not the constructor's require().
        val direct = RemoveEmptyGuardPlan.prepare(expected).descriptor(issued.toString())
        assertEquals(message, issued.toString(), direct.operationId)
        var calls = 0
        val store = ControlRecordStore(s.owner, ControlIdGenerator { calls++; issued })
        val c = store.prepareRemoveEmptyGuard(expected)
        assertEquals(message, 1, calls)
        assertEquals(message, issued.toString(), c.id)
        assertSame(message, ControlCommandTracking.forOwner(s.owner).lifetimeId, c.ownerTrackingLifetimeId)
        assertNotNull(message, ControlCommandTracking.forOwner(s.owner).findPrepared(c))
        val input = (c.body as ControlCommandBody.Lifecycle).input
        assertEquals(message, issued.toString(), input.operationId)
        assertEquals(message, LifecycleTransition.REMOVE_EMPTY_GUARD, input.transition)
        assertEquals(message, 1, input.targets.size)
        val fixed = input.targets.single()
        assertEquals(message, LifecycleTarget(ControlKind.DEMAND, "r7-descriptor-guard", LifecycleEffect.REMOVE), fixed.target)
        assertEquals(message, LifecycleRole.GUARD, fixed.role)
        assertEquals(message, expected.toPayloadEntry(), fixed.before?.toPayloadEntry())
        assertNull(message, fixed.after)
        assertNotNull(message, input.removeEmptyGuard)
        assertNull(message, input.demandAuth)
        assertNull(message, input.executor)
        assertNull(message, input.namespace)
        assertTrue(message, input.requiredUnchanged.isEmpty())
    }
    @Test fun descriptor_command() = runReleaseTest { descriptorFacts("EG_descriptorCommand") }
    @Test fun descriptor_transition() = runReleaseTest { descriptorFacts("EG_descriptorTransition") }
    @Test fun descriptor_kind() = runReleaseTest { descriptorFacts("EG_descriptorKind") }
    @Test fun descriptor_id() = runReleaseTest { descriptorFacts("EG_descriptorId") }
    @Test fun descriptor_effect() = runReleaseTest { descriptorFacts("EG_descriptorEffect") }
    @Test fun descriptor_role() = runReleaseTest { descriptorFacts("EG_descriptorRole") }
    @Test fun descriptor_before() = runReleaseTest { descriptorFacts("EG_descriptorBefore") }
    @Test fun descriptor_after() = runReleaseTest { descriptorFacts("EG_descriptorAfter") }
    @Test fun descriptor_capability() = runReleaseTest { descriptorFacts("EG_descriptorCapability") }

    private suspend fun holdPreparationFacts(id: String, existingGuard: Boolean = true) {
        val s = open(folder.newFile())
        // HOLD floor must also match its Pending seconds and source origin in the schema.
        val hold = F.hold()
        val old = if (existingGuard) F.guard(F.floor(90000, elapsed = 10000), auth = true) else null
        val now = BootReading("boot", 12000)
        val origin = LifetimeId("r7-origin")
        val source = ControlSchema.read(ControlKind.HOLD, hold) as RestoredHold
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        if (old != null) {
            assertEquals(FloorV1("boot", 10000, 90000, LifetimeId("life")), guard(old)!!.floor)
            assertNotNull(guard(old)!!.auth)
        }
        val before = if (old == null) F.raw(request, hold = hold) else F.raw(old, request, hold = hold)
        controlTestTimeout("r7 pure prepare seed") { s.data.updateData { before } }
        val writes = s.storage.writes
        val issued = UUID.fromString("33333333-3333-4333-8333-333333333333")
        var calls = 0
        val store = ControlRecordStore(s.owner, ControlIdGenerator { calls++; issued })
        val plan = store.prepareHoldFloor(hold, old, now, origin)
        val message = F.atomic(id)
        assertNotNull(message, plan)
        plan!!
        assertEquals(message, 1, calls)
        assertEquals(message, hold.toPayloadEntry(), plan.input.hold.toPayloadEntry())
        assertEquals(message, old?.toPayloadEntry(), plan.input.guard?.toPayloadEntry())
        assertEquals(message, BootReading("boot", 12000), plan.input.mergeNow)
        assertEquals(message, LifetimeId("r7-origin"), plan.input.origin)
        assertEquals(message, issued.toString(), plan.input.newGuardId)
        val result = guard(plan.guardAfter)
        assertNotNull(message, result)
        assertEquals(message, if (existingGuard) "g" else issued.toString(), result!!.id)
        assertEquals(message, FloorV1("boot", 12000, if (existingGuard) 88000L else 28000L, LifetimeId("r7-origin")), result.floor)
        assertEquals(message, old?.toPayloadEntry()?.fields?.get("auth")?.toString(), plan.guardAfter?.toPayloadEntry()?.fields?.get("auth")?.toString())
        repeat(2) { assertNull(message, plan.preimageProblem(F.read(before))) }
        assertEquals(message, 1, calls)
        assertEquals(message, writes, s.storage.writes)
        assertEquals(message, before, s.raw())
    }
    @Test fun hold_prepareSource() = runReleaseTest { holdPreparationFacts("HF_storeSource") }
    @Test fun hold_prepareGuard() = runReleaseTest { holdPreparationFacts("HF_storeGuard") }
    @Test fun hold_prepareNow() = runReleaseTest { holdPreparationFacts("HF_storeNow") }
    @Test fun hold_prepareOrigin() = runReleaseTest { holdPreparationFacts("HF_storeOrigin") }
    @Test fun hold_prepareId() = runReleaseTest { holdPreparationFacts("HF_storeId", false) }
    @Test fun hold_prepareIssueOnce() = runReleaseTest { holdPreparationFacts("HF_storeIssueOnce", false) }
}
