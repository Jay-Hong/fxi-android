package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.buffer
import okio.source
import org.junit.After
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-3C2 contract: the owner entry of previous-lifetime Settlement reclamation across a real restart
 * (6-3 skeleton r3 §4, T8–T9; revision 06 §8.1–§8.2, §9; 6-3C API consensus + 6-3C2 detail consensus r2). API fixed:
 *   suspend fun ControlRecordStore.reclaimPreviousSettlementOrLifecycleEvidence(selection: PreviousEvidenceSelection,
 *     closure: PreviousReclamationClosure, retry: Boolean = false): PreviousEvidenceReclamationResult
 *   class PreviousReclamationClosure(commandIds: Set<String>, ownerTrackingLifetimeId: OwnerTrackingLifetimeId, owner: String,
 *     generationAtCapture: Long, currentGeneration: Long, captured: Set<String>, joined: Set<String>, registered: Set<String>,
 *     entriesClosed: Boolean, receiptsClosed: Boolean, oldScopeClosed: Boolean, recoveryOwned: Boolean)
 *     with fun violation(selection, currentLifetime): PreviousReclamationClosureViolation?
 *   enum PreviousReclamationClosureViolation { SelectionMismatch, LifetimeMismatch, GenerationMismatch, EntriesOpen,
 *     CapturedJoinedMismatch, RegisteredMismatch, ReceiptsOpen, OldScopeOpen, RecoveryUnowned, OwnerMissing }
 *   sealed PreviousEvidenceReclamationResult { Reclaimed(snapshot, proof, selectedOperationIds, disposition, U, P);
 *     Rejected(reason, observation?, U, P); Conflict(reason, observation, U, P); RecoveryRequired(reason, observation, U, P);
 *     Unconfirmed(observation?, failure, U, P) } — U/P are localUnresolvedCommands / localPendingReleases
 *   enum PreviousReclamationDisposition { RemovedNow, AlreadyAbsent }
 *   sealed PreviousReclamationRejectionReason { InvalidSelection(detail); UnsupportedInThisUnit; ClosureNotSatisfied(v);
 *     DependencyPresent(dependentCommandId, dependentLifetimeId, atom); DependencyUnknown(...); Encoding(reason) }
 * Entry order, all before storage (no write, file unchanged): any Lifecycle item → UnsupportedInThisUnit; selection.problem →
 * InvalidSelection; closure.violation → ClosureNotSatisfied. Then one owner transaction: read, observe, schema/opaque
 * (RecoveryRequired), the 6-3C1 decider (Conflict / RecoveryRequired / Encoding), G11 over every ref the current tracker holds
 * (no self is excluded — an old-lifetime ref of the same operation in U blocks), Confirm, then the return is validated.
 * Retry with the same selection after a failed return: wholly present → RemovedNow, wholly absent → AlreadyAbsent. The call
 * never registers or terminates an old ref and never recreates a seal. Records come from real transitions applied under an
 * old tracker, then the old DataStore scope is closed and the SAME file reopened. The implementation thread reads but does
 * not edit this file.
 */
class PreviousEvidenceReclamationOwnerContractTest {
    @get:Rule val folder = TemporaryFolder()
    private var index = 0
    private var opened: ControlStoreTestStorage? = null
    private lateinit var file: File
    @After fun close() = runBlocking { opened?.close(); Unit }
    private fun newFile() { index++; file = File(folder.root, "previous-$index.preferences_pb") }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close() // cancels and joins the old scope before the same file is reopened
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private fun tracker(o: ControlStoreTestStorage) = ControlCommandTracking.forOwner(o.owner)
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(BARRIER) }.toPreferences()
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val R = RetiredNamespaceFixtures; private val NN = CurrentNullFixtures; private val L = RetiredNullFixtures

    private class Kind(val name: String, val raw: Preferences, val operationId: String, val body: ControlCommandBody.Handover, val context: AttemptContext)
    private fun rn(s: RetiredNamespaceSettlement = R.spec(), name: String = "RETIRED_NAMESPACE") = Kind(name, R.raw(s), s.operationId, ControlCommandBody.SettleRetiredNamespace(s), R.context)
    private fun cn(s: CurrentNullSettlement = NN.spec(), name: String = "CURRENT_NULL") = Kind(name, NN.raw(s), s.operationId, ControlCommandBody.RotateAndSettleCurrentNull(s), NN.context)
    private fun rl(s: RetiredNullSettlement = L.spec(), name: String = "RETIRED_NULL") = Kind(name, L.raw(s), s.operationId, ControlCommandBody.SettleRetiredNull(s), L.context)
    private fun kinds() = listOf(rn(), cn(), cn(NN.both(), "CURRENT_NULL-both"), rl(), rl(L.both(), "RETIRED_NULL-both"))

    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun cmd(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))

    /** Applies [k] under [o]'s tracker with the real transition; returns the (old) command. */
    private suspend fun applyOld(o: ControlStoreTestStorage, k: Kind): CommandRef {
        val c = tracker(o).registerPrepared(CommandRef(k.operationId, k.body, tracker(o).lifetimeId))
        val r = controlTestTimeout("old execute") { o.control.execute(c, k.context) }
        check(r is ControlStoreResult.Confirmed) { "fixture ${k.name}: $r" }
        return c
    }
    /** A fresh file with [k] applied by an old tracker, then reopened: returns the new storage and the old command. */
    private suspend fun previous(k: Kind): Pair<ControlStoreTestStorage, CommandRef> {
        newFile(); val o = open(); o.data.updateData { k.raw }
        val c = applyOld(o, k)
        val next = open()
        check(tracker(next).lifetimeId !== c.ownerTrackingLifetimeId) { "fixture: new lifetime" }
        return next to c
    }
    private suspend fun itemFor(c: CommandRef, from: Preferences? = null): PreviousEvidenceSelection.Item.Settlement {
        val p = from ?: disk()
        val row = arr(p, evidenceKey).single { cmd(it) == c.id }
        val seals = row.jsonObject.getValue("sealIds").jsonArray.map { sid -> arr(p, sealKey).single { id(it) == sid.jsonPrimitive.content } }
        return PreviousEvidenceSelection.Item.Settlement(c.id, node(row.toString()), seals.map { node(it.toString()) })
    }
    private fun selection(vararg items: PreviousEvidenceSelection.Item) = PreviousEvidenceSelection(items.toList())
    private fun closure(next: ControlStoreTestStorage, sel: PreviousEvidenceSelection,
        ids: Set<String> = sel.items.map { it.commandId }.toSet(), lifetime: OwnerTrackingLifetimeId = tracker(next).lifetimeId,
        owner: String = "owner-1", capture: Long = 7L, current: Long = 7L, captured: Set<String> = setOf("job-1"),
        joined: Set<String> = setOf("job-1"), registered: Set<String> = setOf("job-1"), entriesClosed: Boolean = true,
        receiptsClosed: Boolean = true, oldScopeClosed: Boolean = true, recoveryOwned: Boolean = true) =
        PreviousReclamationClosure(ids, lifetime, owner, capture, current, captured, joined, registered, entriesClosed,
            receiptsClosed, oldScopeClosed, recoveryOwned)
    private suspend fun reclaim(next: ControlStoreTestStorage, sel: PreviousEvidenceSelection,
        k: PreviousReclamationClosure = closure(next, sel), retry: Boolean = false) =
        controlTestTimeout("previous reclaim") { next.control.reclaimPreviousSettlementOrLifecycleEvidence(sel, k, retry) }
    /** Independent oracle: [before] minus exactly each selected row and every seal settled by those operations. */
    private fun oracle(before: Preferences, vararg ops: String): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { cmd(it) !in ops }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) !in ops }).toString()
        remove(BARRIER)
    }.toPreferences()

    private suspend fun assertReclaimed(id: String, next: ControlStoreTestStorage, r: PreviousEvidenceReclamationResult,
        expected: Preferences, ops: List<String>, disposition: PreviousReclamationDisposition) {
        assertTrue("D2B6/6-3C2.$id: reclaimed $r", r is PreviousEvidenceReclamationResult.Reclaimed)
        r as PreviousEvidenceReclamationResult.Reclaimed
        assertEquals("D2B6/6-3C2.$id: disposition", disposition, r.disposition)
        assertEquals("D2B6/6-3C2.$id: operations", ops, r.selectedOperationIds)
        val after = disk()
        assertEquals("D2B6/6-3C2.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-3C2.$id: exactDeletion", expected, withoutBarrier(after))
        val work = tracker(next).recoverySnapshot()
        assertEquals("D2B6/6-3C2.$id: U", work.unresolvedCommands, r.localUnresolvedCommands)
        assertEquals("D2B6/6-3C2.$id: P", work.pendingReleases, r.localPendingReleases)
    }
    /** Refused at the entry: no owner transaction, nothing written, file unchanged. */
    private suspend fun refusedBeforeStorage(id: String, next: ControlStoreTestStorage, sel: PreviousEvidenceSelection,
        expected: PreviousReclamationRejectionReason, k: PreviousReclamationClosure = closure(next, sel)) {
        val before = disk(); val writes = next.storage.writes
        val r = reclaim(next, sel, k)
        assertEquals("D2B6/6-3C2.$id: $expected", expected, (r as? PreviousEvidenceReclamationResult.Rejected)?.reason)
        assertNull("D2B6/6-3C2.$id: noObservation", (r as PreviousEvidenceReclamationResult.Rejected).observation)
        assertEquals("D2B6/6-3C2.$id: noWrite", writes, next.storage.writes)
        assertEquals("D2B6/6-3C2.$id: fileUnchanged", before, disk())
    }

    // ── T9.1 / T8.1: each transition's previous bundle is reclaimed after a real restart ─────────────────────────
    @Test fun C2_01_eachPreviousTransitionIsReclaimedAfterARealRestart() = runBlocking {
        for (k in kinds()) {
            val (next, c) = previous(k); val before = disk(); val sel = selection(itemFor(c))
            assertReclaimed("01 ${k.name}", next, reclaim(next, sel), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
            assertNull("D2B6/6-3C2.01 ${k.name}: oldRefNotRegistered", tracker(next).findPrepared(c))
        }
    }

    // ── entry refusals, all before storage ───────────────────────────────────────────────────────────────────────
    @Test fun C2_02_lifecycleAndInvalidSelectionsAreRefusedBeforeStorage() = runBlocking {
        val (next, c) = previous(cn(NN.both(), "CURRENT_NULL-both")); val item = itemFor(c)
        refusedBeforeStorage("02 lifecycleOnly", next, selection(PreviousEvidenceSelection.Item.Lifecycle(c.id, item.rawApplied)),
            PreviousReclamationRejectionReason.UnsupportedInThisUnit)
        refusedBeforeStorage("02 mixed", next, selection(item, PreviousEvidenceSelection.Item.Lifecycle("lc-other", item.rawApplied)),
            PreviousReclamationRejectionReason.UnsupportedInThisUnit)
        // Selected raw row claims the CURRENT tracker lifetime: not a previous-lifetime selection.
        val currentRow = obj(item.rawApplied.toPayloadEntry().fields) {
            this["ownerTrackingLifetimeId"] = JsonPrimitive(tracker(next).lifetimeId.value) }
        val currentItem = PreviousEvidenceSelection.Item.Settlement(c.id, node(currentRow.toString()), item.orderedRawSeals)
        val r1 = reclaim(next, selection(currentItem))
        assertTrue("D2B6/6-3C2.02 currentLifetime: $r1", (r1 as? PreviousEvidenceReclamationResult.Rejected)?.reason is PreviousReclamationRejectionReason.InvalidSelection)
        // Selected seals out of the row's order.
        val reordered = PreviousEvidenceSelection.Item.Settlement(c.id, item.rawApplied, item.orderedRawSeals.reversed())
        val before = disk(); val writes = next.storage.writes
        val r2 = reclaim(next, selection(reordered))
        assertTrue("D2B6/6-3C2.02 order: $r2", (r2 as? PreviousEvidenceReclamationResult.Rejected)?.reason is PreviousReclamationRejectionReason.InvalidSelection)
        assertEquals("D2B6/6-3C2.02 order noWrite", writes, next.storage.writes); assertEquals("D2B6/6-3C2.02 order fileUnchanged", before, disk())
    }
    @Test fun C2_03_eachClosureFieldAloneIsRefusedBeforeStorage() = runBlocking {
        val (next, c) = previous(rn()); val sel = selection(itemFor(c))
        val cases = listOf(
            PreviousReclamationClosureViolation.SelectionMismatch to closure(next, sel, ids = setOf(c.id, "extra")),
            PreviousReclamationClosureViolation.LifetimeMismatch to closure(next, sel, lifetime = c.ownerTrackingLifetimeId),
            PreviousReclamationClosureViolation.GenerationMismatch to closure(next, sel, capture = 6L),
            PreviousReclamationClosureViolation.EntriesOpen to closure(next, sel, entriesClosed = false),
            PreviousReclamationClosureViolation.CapturedJoinedMismatch to closure(next, sel, joined = setOf("job-2")),
            PreviousReclamationClosureViolation.RegisteredMismatch to closure(next, sel, registered = setOf("job-2")),
            PreviousReclamationClosureViolation.ReceiptsOpen to closure(next, sel, receiptsClosed = false),
            PreviousReclamationClosureViolation.OldScopeOpen to closure(next, sel, oldScopeClosed = false),
            PreviousReclamationClosureViolation.RecoveryUnowned to closure(next, sel, recoveryOwned = false),
            PreviousReclamationClosureViolation.OwnerMissing to closure(next, sel, owner = " "))
        for ((v, k) in cases) refusedBeforeStorage("03 $v", next, sel, PreviousReclamationRejectionReason.ClosureNotSatisfied(v), k)
        val before = disk()
        assertReclaimed("03r", next, reclaim(next, sel), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
    }

    // ── owner decisions keep the record ──────────────────────────────────────────────────────────────────────────
    private suspend fun ownerKept(id: String, next: ControlStoreTestStorage, sel: PreviousEvidenceSelection, check: (PreviousEvidenceReclamationResult) -> Boolean) {
        val before = disk()
        val r = reclaim(next, sel)
        assertTrue("D2B6/6-3C2.$id: $r", check(r))
        assertEquals("D2B6/6-3C2.$id: recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
    }
    @Test fun C2_04_latestChangedPartialBundleAndOpaqueRecordKeepTheRecord() = runBlocking {
        run { // The latest seal changed after the selection was taken.
            val (next, c) = previous(rn()); val sel = selection(itemFor(c))
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[sealKey] = JsonArray(arr(p, sealKey).map { if (op(it) == c.id) obj(it) { this["settlement"] = obj(getValue("settlement")) {
                    this["originLifetimeId"] = JsonPrimitive("changed-origin") } } else it }).toString() }.toPreferences() }
            ownerKept("04 latestChanged", next, sel) {
                it is PreviousEvidenceReclamationResult.Conflict && it.reason == ConflictReason.CommandEvidenceMismatch }
        }
        run { // One seal of a two-NULL bundle removed from the record, selection unchanged.
            val (next, c) = previous(rl(L.both(), "RETIRED_NULL-both")); val item = itemFor(c)
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[sealKey] = JsonArray(arr(p, sealKey).drop(1)).toString() }.toPreferences() }
            ownerKept("04 partialBundle", next, selection(item)) {
                it is PreviousEvidenceReclamationResult.RecoveryRequired && it.reason == RecoveryReason.InconsistentReclamation }
        }
        run { // An uninterpretable obligation anywhere.
            val (next, c) = previous(cn()); val sel = selection(itemFor(c))
            next.data.updateData { p -> p.toMutablePreferences().apply { this[demandKey] = "[17]" }.toPreferences() }
            ownerKept("04 opaque", next, sel) {
                it is PreviousEvidenceReclamationResult.RecoveryRequired && it.reason == RecoveryReason.UninterpretableObligations }
        }
    }
    @Test fun C2_05_aMultiSelectionIsAllOrNothing() = runBlocking {
        // Two previous RETIRED_NULL settlements over different seals of the same record, applied by one old tracker.
        newFile(); val o = open()
        val first = rl(L.spec(target = node(L.nullUser), op = "l-first"), "RETIRED_NULL-first")
        val second = rl(L.spec(target = node(L.nullKrx), op = "l-second"), "RETIRED_NULL-second")
        o.data.updateData { L.raw(L.both()) }
        val a = applyOld(o, first); val b = applyOld(o, second)
        val next = open(); val before = disk()
        val itemA = itemFor(a); val itemB = itemFor(b)
        // A stale item (its seal changed after selection) refuses the whole call: nothing is removed.
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[sealKey] = JsonArray(arr(p, sealKey).map { if (op(it) == b.id) obj(it) { this["settlement"] = obj(getValue("settlement")) {
                this["originLifetimeId"] = JsonPrimitive("changed-origin") } } else it }).toString() }.toPreferences() }
        val changed = disk()
        val stale = reclaim(next, selection(itemA, itemB))
        assertTrue("D2B6/6-3C2.05 staleConflict: $stale", stale is PreviousEvidenceReclamationResult.Conflict)
        assertEquals("D2B6/6-3C2.05 nothingRemoved", withoutBarrier(changed), withoutBarrier(disk()))
        next.data.updateData { before }
        assertReclaimed("05 both", next, reclaim(next, selection(itemA, itemB)), oracle(before, a.id, b.id), listOf(a.id, b.id),
            PreviousReclamationDisposition.RemovedNow)
    }

    // ── G11 over the current tracker's refs (no self to exclude) ────────────────────────────────────────────────
    @Test fun C2_06_currentRefsDependingOnTheSelectedBundleBlock() = runBlocking {
        run { // A current Edit of the selected seal.
            val (next, c) = previous(rn()); val item = itemFor(c)
            val seal = item.orderedRawSeals.single(); val sid = (seal.text("id") as FieldRead.Present).value
            val m = next.control.prepare(ControlMutation.Edit.prepare(ControlKind.SEAL, seal) {})
            val before = disk()
            val r = reclaim(next, selection(item))
            assertEquals("D2B6/6-3C2.06 edit", PreviousReclamationRejectionReason.DependencyPresent(m.id, m.ownerTrackingLifetimeId.value,
                DependencyAtom.ControlRow(ControlKind.SEAL, sid)), (r as? PreviousEvidenceReclamationResult.Rejected)?.reason)
            assertEquals("D2B6/6-3C2.06 edit recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
        }
        run { // The old ref of the same operation left in the current U (e.g. by confirmPrevious): 6-4b closes it first.
            val (next, c) = previous(cn()); tracker(next).markUnresolved(c)
            val before = disk()
            val r = reclaim(next, selection(itemFor(c)))
            val reason = (r as? PreviousEvidenceReclamationResult.Rejected)?.reason
            assertTrue("D2B6/6-3C2.06 oldSameOperation: $r", reason is PreviousReclamationRejectionReason.DependencyPresent &&
                reason.dependentCommandId == c.id && reason.dependentLifetimeId == c.ownerTrackingLifetimeId.value)
            assertEquals("D2B6/6-3C2.06 old recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
        }
        run { // Unrelated current refs do not block.
            val (next, c) = previous(rl()); val before = disk()
            next.control.prepare(next.control.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
            assertReclaimed("06 unrelated", next, reclaim(next, selection(itemFor(c))), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        }
    }

    // ── T9.4: management faults and the same-selection retry ─────────────────────────────────────────────────────
    @Test fun C2_07_faultsAndRetryWithTheSameSelection() = runBlocking {
        run { // Not landed → retry removes.
            val (next, c) = previous(rn()); val before = disk(); val sel = selection(itemFor(c))
            next.storage.before = true
            assertTrue("D2B6/6-3C2.07 notLanded", reclaim(next, sel) is PreviousEvidenceReclamationResult.Unconfirmed)
            next.storage.before = false
            assertEquals("D2B6/6-3C2.07 nothingLanded", withoutBarrier(before), withoutBarrier(disk()))
            assertReclaimed("07 retryRemoves", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        }
        run { // Landed then the return failed → retry confirms absence.
            val (next, c) = previous(cn(NN.both(), "CURRENT_NULL-both")); val before = disk(); val sel = selection(itemFor(c))
            next.storage.afterScope = true
            val landed = reclaim(next, sel)
            assertTrue("D2B6/6-3C2.07 landedReturnFailed: $landed", landed is PreviousEvidenceReclamationResult.Unconfirmed &&
                landed.observation is ControlRecordRead.Supported) // the owner's actual read is kept
            next.storage.afterScope = false
            assertEquals("D2B6/6-3C2.07 landed", oracle(before, c.id), withoutBarrier(disk()))
            assertReclaimed("07 retryAbsent", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.AlreadyAbsent)
        }
        run { // Cancellation after landing propagates; the retry confirms absence.
            val (next, c) = previous(rl()); val before = disk(); val sel = selection(itemFor(c))
            val pause = ControlStoreTestStorage.Pause(); next.storage.pauseAfterScope = pause
            val caller = async { next.control.reclaimPreviousSettlementOrLifecycleEvidence(sel, closure(next, sel)) }
            try {
                withTimeout(10_000) { pause.reached.await() }
                caller.cancelAndJoin(); assertTrue("D2B6/6-3C2.07 cancelled", caller.isCancelled)
            } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
            next.storage.pauseAfterScope = null
            assertReclaimed("07 retryAfterCancel", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.AlreadyAbsent)
        }
        run { // Retry finds only part of the bundle → held.
            val (next, c) = previous(rl(L.both(), "RETIRED_NULL-both")); val sel = selection(itemFor(c)); val landedFrom = disk()
            next.storage.afterScope = true
            check(reclaim(next, sel) is PreviousEvidenceReclamationResult.Unconfirmed)
            next.storage.afterScope = false
            val oneSeal = arr(landedFrom, sealKey).first { op(it) == c.id }
            next.data.updateData { p -> p.toMutablePreferences().apply { this[sealKey] = JsonArray(arr(p, sealKey) + oneSeal).toString() }.toPreferences() }
            ownerKeptRetry("07 retryPartial", next, sel)
        }
    }
    private suspend fun ownerKeptRetry(id: String, next: ControlStoreTestStorage, sel: PreviousEvidenceSelection) {
        val before = disk()
        val r = reclaim(next, sel, retry = true)
        assertTrue("D2B6/6-3C2.$id: $r", r is PreviousEvidenceReclamationResult.RecoveryRequired && r.reason == RecoveryReason.InconsistentReclamation)
        assertEquals("D2B6/6-3C2.$id: recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
    }
    @Test fun C2_08_anotherRealRestartSelectsAgainFromTheLatestRecord() = runBlocking {
        val (next, c) = previous(rn()); val sel = selection(itemFor(c))
        next.storage.before = true
        check(reclaim(next, sel) is PreviousEvidenceReclamationResult.Unconfirmed)
        val again = open() // a second real restart: nothing landed, select again from the latest record
        val before = disk()
        assertReclaimed("08", again, reclaim(again, selection(itemFor(c))), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
    }

    // ── T8.2: current Settlement and unrelated seals are preserved ───────────────────────────────────────────────
    @Test fun C2_09_aCurrentLifetimeSettlementIsNotTouched() = runBlocking {
        val (next, c) = previous(rn())
        // The new tracker applies its own RETIRED_NAMESPACE over the CAPABILITY seal "c".
        val current = rn(R.spec(target = node(NamespaceSettlementFixtures.krx), op = "r-current", did = "r-current-demand"), "current")
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[sealKey] = JsonArray(arr(p, sealKey) + Json.parseToJsonElement(NamespaceSettlementFixtures.krx)).toString() }.toPreferences() }
        val cur = applyOld(next, current)
        val before = disk()
        check(arr(before, evidenceKey).any { cmd(it) == cur.id }) { "fixture: current row" }
        assertReclaimed("09", next, reclaim(next, selection(itemFor(c))), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        assertTrue("D2B6/6-3C2.09 currentKept", arr(disk(), evidenceKey).any { cmd(it) == cur.id } && arr(disk(), sealKey).any { op(it) == cur.id })
    }

    // ── T8.3–T8.6: X13 — previous Add + RETIRED_NULL witness, 2c first, then this API; no reapplication ─────────
    @Test fun C2_10_x13PreviousAddCannotReapplyAfterTheSettlementIsReclaimed() = runBlocking {
        newFile(); val o = open()
        o.data.updateData { L.raw(L.spec()).toMutablePreferences().apply { this[sealKey] = "[]" }.toPreferences() }
        val a = o.control.prepare(o.control.addition(ControlKind.SEAL) { sid -> literal(L.nullUser); set("id", ControlScalar.Text(sid)) })
        o.storage.afterScope = true
        check(controlTestTimeout("A execute") { o.control.execute(a) } is ControlStoreResult.Unconfirmed) { "fixture: A return fault" }
        val checkpoint = checkNotNull(o.control.checkpoint(a)) { "fixture: A checkpoint" }
        val stored = arr(disk(), sealKey).single(); val x = id(stored)
        val s = applyOld(o, rl(L.spec(target = node(stored.toString())), "RETIRED_NULL-x"))
        val next = open()
        // 2c takes the previous Mutations row (A) only; the Settlement row and its seal stay.
        val r2c = controlTestTimeout("2c") { next.control.reclaimPreviousLifetimeEvidence() }
        assertTrue("D2B6/6-3C2.10 2c: $r2c", r2c is ControlEvidenceReclamationResult.Confirmed)
        val after2c = disk()
        assertEquals("D2B6/6-3C2.10 2cKeepsSettlementRow", listOf(s.id), arr(after2c, evidenceKey).map { cmd(it) })
        assertTrue("D2B6/6-3C2.10 2cKeepsSeal", arr(after2c, sealKey).any { id(it) == x })
        // This API reclaims the Settlement bundle.
        assertReclaimed("10 settlement", next, reclaim(next, selection(itemFor(s))), oracle(after2c, s.id), listOf(s.id),
            PreviousReclamationDisposition.RemovedNow)
        val reclaimed = disk()
        // A's previous-lifetime confirmation stays negative and never recreates x.
        NamespaceSettlementFixtures.negative(controlTestTimeout("confirmPrevious") { next.control.confirmPrevious(a, checkpoint) }, ConflictReason.TargetMissing)
        assertEquals("D2B6/6-3C2.10 noSealRecreated", withoutBarrier(reclaimed), withoutBarrier(disk()))
        val insufficient = controlTestTimeout("confirmPrevious invalid") {
            next.control.confirmPrevious(a, ControlCommandCheckpoint(a, emptyList(), confirmationRequested = true)) }
        assertTrue("D2B6/6-3C2.10 historyUnavailable: $insufficient", insufficient is ControlStoreResult.Unconfirmed &&
            insufficient.reason == UnconfirmedReason.HistoryUnavailable)
        assertEquals("D2B6/6-3C2.10 stillNoSeal", withoutBarrier(reclaimed), withoutBarrier(disk()))
        // The old ref is never registered in the new tracker.
        val registration = runCatching { tracker(next).registerPrepared(a) }.exceptionOrNull()
        assertEquals("D2B6/6-3C2.10 lifetimeGate", IllegalStateException::class.java, registration?.javaClass)
        assertNull("D2B6/6-3C2.10 notRegistered", tracker(next).findPrepared(a))
    }

    // ── 6-3C2 a: the pure decider re-checks selection eligibility just before a retry all-absent Ready ─────────
    @Test fun C2_11_retryAllAbsentStillRefusesAnIneligibleSelection() = runBlocking {
        val (next, c) = previous(cn(NN.both(), "CURRENT_NULL-both")); val item = itemFor(c)
        val empty = disk().toMutablePreferences().apply { this[evidenceKey] = "[]"; this[sealKey] = "[]" }.toPreferences()
        val read = ControlRecordReader().read(empty) as ControlRecordRead.Supported
        val current = tracker(next).lifetimeId
        val eligible = PreviousSettlementEvidenceReclamation.decide(read, selection(item), current, true, ControlPayloadCodec())
        assertEquals("D2B6/6-3C2.11 eligibleAbsent", PreviousSettlementEvidenceReclamation.Decision.Ready(read.original), eligible)
        val reordered = PreviousEvidenceSelection.Item.Settlement(c.id, item.rawApplied, item.orderedRawSeals.reversed())
        val wrongCommand = PreviousEvidenceSelection.Item.Settlement("other-command", item.rawApplied, item.orderedRawSeals)
        for ((name, bad) in listOf("wrongCommand" to wrongCommand) + (if (item.orderedRawSeals.size > 1) listOf("reordered" to reordered) else emptyList())) {
            val d = PreviousSettlementEvidenceReclamation.decide(read, selection(bad), current, true, ControlPayloadCodec())
            assertTrue("D2B6/6-3C2.11 $name: $d", d is PreviousSettlementEvidenceReclamation.Decision.Rejected &&
                d.reason is RejectionReason.InvalidRequest)
        }
    }

    // ── r2 (measurement gaps): each selection-eligibility branch alone, refused at the entry ───────────────────
    private fun settledTo(seal: ControlNode, operation: String): ControlNode = node(obj(Json.parseToJsonElement(
        seal.toPayloadEntry().fields.toString())) { this["settlement"] = obj(getValue("settlement")) {
        this["operationId"] = JsonPrimitive(operation) } }.toString())
    private fun rowWith(item: PreviousEvidenceSelection.Item.Settlement, change: MutableMap<String, JsonElement>.() -> Unit) =
        node(obj(item.rawApplied.toPayloadEntry().fields, change).toString())
    @Test fun C2_12_eachEligibilityBranchAloneIsRefusedBeforeStorage() = runBlocking {
        val (next, c) = previous(rn()); val item = itemFor(c); val seal = item.orderedRawSeals.single()
        suspend fun invalid(id: String, sel: PreviousEvidenceSelection, k: PreviousReclamationClosure = closure(next, sel)) {
            val before = disk(); val writes = next.storage.writes
            val r = reclaim(next, sel, k)
            assertTrue("D2B6/6-3C2.12 $id: $r", (r as? PreviousEvidenceReclamationResult.Rejected)?.reason is PreviousReclamationRejectionReason.InvalidSelection)
            assertNull("D2B6/6-3C2.12 $id: noObservation", (r as PreviousEvidenceReclamationResult.Rejected).observation)
            assertEquals("D2B6/6-3C2.12 $id: noWrite", writes, next.storage.writes)
            assertEquals("D2B6/6-3C2.12 $id: fileUnchanged", before, disk())
        }
        invalid("empty", selection())
        // Same command twice with otherwise distinct, individually eligible evidence (seal "z").
        val zSeal = node(obj(Json.parseToJsonElement(seal.toPayloadEntry().fields.toString())) { this["id"] = JsonPrimitive("z") }.toString())
        val zRow = rowWith(item) { this["sealIds"] = JsonArray(listOf(JsonPrimitive("z"))) }
        invalid("duplicateCommand", selection(item, PreviousEvidenceSelection.Item.Settlement(c.id, zRow, listOf(zSeal))))
        invalid("uninterpretableRow", selection(PreviousEvidenceSelection.Item.Settlement(c.id, node("""{"version":2}"""), item.orderedRawSeals)))
        // The row names c, the item and its seals name another operation.
        invalid("rowCommand", selection(PreviousEvidenceSelection.Item.Settlement("x-other", item.rawApplied, listOf(settledTo(seal, "x-other")))))
        invalid("uninterpretableSeal", selection(PreviousEvidenceSelection.Item.Settlement(c.id, item.rawApplied, listOf(node("""{"id":"s"}""")))))
        invalid("sealOperation", selection(PreviousEvidenceSelection.Item.Settlement(c.id, item.rawApplied, listOf(settledTo(seal, "x-other")))))
        // Two distinct commands whose eligible evidence names the same seal id.
        val bRow = rowWith(item) { this["commandId"] = JsonPrimitive("b-command") }
        invalid("duplicateSeal", selection(item, PreviousEvidenceSelection.Item.Settlement("b-command", bRow, listOf(settledTo(seal, "b-command")))))
        // Order: an invalid selection is reported before a violated closure.
        val bad = selection(PreviousEvidenceSelection.Item.Settlement(c.id, item.rawApplied, listOf(settledTo(seal, "x-other"))))
        invalid("selectionBeforeClosure", bad, closure(next, bad, oldScopeClosed = false))
    }

    // ── r2: owner classification before the decider, and the tracker's observation ────────────────────────────
    @Test fun C2_13_unsupportedRecordsAreClassifiedAndObserved() = runBlocking {
        val cases = listOf<Triple<String, (androidx.datastore.preferences.core.MutablePreferences) -> Unit, Pair<RecoveryReason, Int>>>(
            Triple("schema1", { p -> p[ControlStoreTestStorage.SCHEMA] = 1; p.remove(evidenceKey)
                p.remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }, RecoveryReason.ControlSchemaMigrationRequired to 1),
            Triple("absent", { p -> p.clear(); p[ControlStoreTestStorage.EXTRA] = "legacy" }, RecoveryReason.MigrationOrRecovery to 1),
            Triple("missingSchema", { p -> p.remove(ControlStoreTestStorage.SCHEMA) }, RecoveryReason.UnreadableRecord to 1),
            Triple("metadata", { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).map { obj(it) {
                this["transition"] = JsonPrimitive("RETIRED_NULL") } }).toString() }, RecoveryReason.UninterpretableMetadata to 0))
        for ((name, change, expected) in cases) {
            val (next, c) = previous(rn()); val sel = selection(itemFor(c))
            next.data.updateData { it.toMutablePreferences().apply(change).toPreferences() }
            val count = tracker(next).evidenceDiscontinuityCount
            ownerKept("13 $name", next, sel) { it is PreviousEvidenceReclamationResult.RecoveryRequired && it.reason == expected.first }
            assertEquals("D2B6/6-3C2.13 $name: observed", count.add(BigInteger.valueOf(expected.second.toLong())), tracker(next).evidenceDiscontinuityCount)
        }
    }

    // ── r2: every result carries the tracker's current U/P ────────────────────────────────────────────────────
    @Test fun C2_14_everyResultCarriesTheCurrentUnresolvedAndPendingSets() = runBlocking {
        fun unrelated(next: ControlStoreTestStorage) = next.control.prepare(next.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        fun populate(next: ControlStoreTestStorage) {
            val u = unrelated(next); val p = unrelated(next); tracker(next).markUnresolved(u)
            val w = tracker(next).recoverySnapshot()
            ControlReleaseFixtures.replaceRecovery(tracker(next), LocalRecoveryWork(w.unresolvedCommands, w.pendingReleases + p))
        }
        fun carries(id: String, next: ControlStoreTestStorage, r: PreviousEvidenceReclamationResult) {
            val work = tracker(next).recoverySnapshot()
            check(work.unresolvedCommands.isNotEmpty() && work.pendingReleases.isNotEmpty()) { "fixture: populated" }
            assertEquals("D2B6/6-3C2.14 $id: U", work.unresolvedCommands, r.localUnresolvedCommands)
            assertEquals("D2B6/6-3C2.14 $id: P", work.pendingReleases, r.localPendingReleases)
        }
        run {
            val (next, c) = previous(rn()); populate(next); val before = disk()
            val r = reclaim(next, selection(itemFor(c)))
            carries("reclaimed", next, r)
            assertReclaimed("14", next, r, oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        }
        run {
            val (next, c) = previous(rn()); populate(next); val sel = selection(itemFor(c))
            next.data.updateData { p -> p.toMutablePreferences().apply { this[demandKey] = "[17]" }.toPreferences() }
            carries("owner", next, reclaim(next, sel))
        }
        run {
            val (next, c) = previous(rn()); populate(next)
            carries("entry", next, reclaim(next, selection(PreviousEvidenceSelection.Item.Lifecycle(c.id, itemFor(c).rawApplied))))
        }
        run {
            val (next, c) = previous(rn()); populate(next); next.storage.before = true
            carries("unconfirmed", next, reclaim(next, selection(itemFor(c))))
        }
    }

    // ── r2: a return that does not match the confirmed candidate is an invariant failure ───────────────────────
    @Test fun C2_15_aReturnThatDoesNotMatchTheCandidateIsAnInvariantFailure() = runBlocking {
        for ((name, change) in listOf<Pair<String, (Preferences) -> Preferences>>(
            "unrelatedKey" to { p -> p.toMutablePreferences().apply { this[demandKey] = "[]" }.toPreferences() },
            "rowBack" to { p -> p.toMutablePreferences().apply { this[evidenceKey] = checkNotNull(sourceEvidence) }.toPreferences() })) {
            val (_, c) = previous(rn()); val item = itemFor(c); sourceEvidence = disk()[evidenceKey]
            opened?.close()
            lateinit var boundary: ReleaseBoundaryData
            val wrapped = ControlStoreTestStorage(file) { ReleaseBoundaryData(it).also { b -> boundary = b } }.also { opened = it }
            boundary.after = change
            val sel = selection(item)
            val failure = runCatching { controlTestTimeout("previous reclaim") {
                wrapped.control.reclaimPreviousSettlementOrLifecycleEvidence(sel, closure(wrapped, sel)) } }.exceptionOrNull()
            assertEquals("D2B6/6-3C2.15 $name: invariantPropagates", IllegalStateException::class.java, failure?.javaClass)
        }
    }
    private var sourceEvidence: String? = null

    // ── r2: an unknown projection that may reach the selected bundle blocks ─────────────────────────────────────
    @Test fun C2_16_anUnknownDependencyThatMayReachTheBundleBlocks() = runBlocking {
        val (next, c) = previous(rn()); val before = disk()
        val m = next.control.prepare(ControlMutation.Add.prepare(ControlKind.DEMAND, java.util.UUID(0, 97)) { id ->
            literal(ControlObligationFixtures.request); set("id", ControlScalar.Text(id)) },
            ControlMutation.Add.prepare(ControlKind.SEAL, java.util.UUID(0, 98)) { id -> literal(NamespaceSettlementFixtures.user); set("id", ControlScalar.Text(id)) })
        checkNotNull(tracker(next).findPrepared(m)).targets.set(listOf(ControlCommandTarget("", node(ControlObligationFixtures.request), false), null))
        val r = reclaim(next, selection(itemFor(c)))
        assertEquals("D2B6/6-3C2.16", PreviousReclamationRejectionReason.DependencyUnknown(m.id, m.ownerTrackingLifetimeId.value,
            DependencyGapSource.Adoption(1)), (r as? PreviousEvidenceReclamationResult.Rejected)?.reason)
        assertEquals("D2B6/6-3C2.16: recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
    }

    // ── 6-3E (completion gaps): T8.2 previous Mutations/Rotation/Lifecycle rows and an unrelated seal survive ────
    @Test fun C2_17_previousMutationsRotationLifecycleRowsAndAnUnrelatedSealSurvive() = runBlocking {
        val (next, c) = previous(rn()); val item = itemFor(c)
        // A real previous Rotation bundle over seal "c", applied in another file by another (previous) tracker.
        val holder = TemporaryFolder().also { it.create() }
        val other = ControlStoreTestStorage(File(holder.root, "rotation.preferences_pb"))
        val rotation = try {
            other.data.updateData { NamespaceSettlementFixtures.raw("[${NamespaceSettlementFixtures.krx}]") }
            val r = other.control.prepareRotation(listOf(node(NamespaceSettlementFixtures.krx)), NamespaceSettlementFixtures.fence,
                NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
            check(controlTestTimeout("rotation execute") { other.control.execute(r, NamespaceSettlementFixtures.context) } is ControlStoreResult.Confirmed)
            val p = controlTestTimeout("rotation read") { other.raw() }
            Triple(r.id, arr(p, evidenceKey).single { cmd(it) == r.id }, arr(p, sealKey).single { op(it) == r.id })
        } finally { other.close(); holder.delete() }
        val old = c.ownerTrackingLifetimeId.value
        val mutations = Json.parseToJsonElement("""{"version":2,"commandId":"m-old","ownerTrackingLifetimeId":"$old","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"x-demand","joined":false,"written":true}]}""")
        val lifecycle = Json.parseToJsonElement(ControlLifecycleEvidenceFixtures.wire(command = "lc-old", lifetime = old))
        val unrelated = Json.parseToJsonElement("""{"id":"free","kind":"NULL_NAMESPACE","ownerUid":"Q","axis":"USER"}""")
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(p, evidenceKey) + rotation.second + mutations + lifecycle).toString()
            this[sealKey] = JsonArray(arr(p, sealKey) + rotation.third + unrelated).toString() }.toPreferences() }
        val before = disk()
        check(arr(before, evidenceKey).size == 4 && arr(before, sealKey).size == 3) { "fixture: merged record" }
        assertReclaimed("17", next, reclaim(next, selection(item)), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        val after = disk()
        assertEquals("D2B6/6-3C2.17 previousRowsKept", listOf(rotation.first, "m-old", "lc-old"), arr(after, evidenceKey).map { cmd(it) })
        assertEquals("D2B6/6-3C2.17 otherSealsKept", listOf(rotation.third, unrelated), arr(after, sealKey).toList())
    }

    // ── 6-3E: T8.6 a Settlement ref closed in the old lifetime is refused by its own terminal gate after restart ─
    @Test fun C2_18_aClosedOldSettlementRefIsRefusedByItsTerminalGate() = runBlocking {
        for (k in listOf(rn(), cn(), rl())) {
            newFile(); val o = open(); o.data.updateData { k.raw }
            val c = applyOld(o, k)
            val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
            val done = controlTestTimeout("old consume") { o.control.completeSettlementAfterConsumption(c, TerminationClosures.of(c), declared) }
            check(done is ControlCompletionResult.Completed && c.lifecycleState == ControlCommandLifecycle.TERMINATED) { "fixture ${k.name}: $done" }
            val next = open(); val before = disk(); val writes = next.storage.writes
            val r = controlTestTimeout("old execute") { next.control.execute(c, k.context) }
            assertTrue("D2B6/6-3C2.18 ${k.name}: terminated $r", r is ControlStoreResult.Terminated)
            assertEquals("D2B6/6-3C2.18 ${k.name}: noWrite", writes, next.storage.writes)
            assertEquals("D2B6/6-3C2.18 ${k.name}: fileUnchanged", before, disk())
            assertEquals("D2B6/6-3C2.18 ${k.name}: lifetimeGate", IllegalStateException::class.java,
                runCatching { tracker(next).registerPrepared(c) }.exceptionOrNull()?.javaClass)
        }
    }
}
