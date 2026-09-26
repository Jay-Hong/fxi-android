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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4aC contract: the Lifecycle branch of the single previous-lifetime S/L reclamation API (6-4a skeleton
 * consensus r2 §3, T7–T9; 6-4aC API consensus incl. Codex REVISE 1–2). No new API: the existing
 * reclaimPreviousSettlementOrLifecycleEvidence(selection, closure, retry) and PreviousSettlementEvidenceReclamation now accept
 * PreviousEvidenceSelection.Item.Lifecycle(commandId, rawApplied). PreviousReclamationRejectionReason.UnsupportedInThisUnit
 * is gone. Eligibility (entry, before storage → InvalidSelection; the pure decider's inline check → Rejected(InvalidRequest)):
 * the raw is an interpretable Lifecycle Applied row, its commandId equals the item's, its lifetime is not the current one.
 * Owner: the latest row with that commandId must be a Lifecycle row equal (payload entry) to the raw — else Conflict; old
 * body, confirmed memory and current business postconditions are never required. S and L items of one selection share one
 * candidate and one return check: selected S/L rows and selected S seals are removed; with no S item the SEAL payload's
 * original text is kept byte-identical (not re-encoded). Retry: the whole selection exactly present → RemovedNow, wholly
 * absent (also no selected S seal) → AlreadyAbsent, otherwise held. G11 protects each selected L AppliedRow (and the S atoms
 * as before) — an old ref of the same Lifecycle operation left in U projects its business rows, not the Applied row, so it
 * does not block (closure is where its open use is declared). The 2c path keeps previous Lifecycle rows. Records come from
 * the real writers under an old tracker, then the old scope is closed and the SAME file reopened. The implementation thread
 * reads but does not edit this file.
 */
class PreviousLifecycleReclamationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private var index = 0
    private var opened: ControlStoreTestStorage? = null
    private lateinit var file: File
    @After fun close() = runBlocking { opened?.close(); Unit }
    private fun newFile() { index++; file = File(folder.root, "previous-l-$index.preferences_pb") }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private fun tracker(o: ControlStoreTestStorage) = ControlCommandTracking.forOwner(o.owner)
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(BARRIER) }.toPreferences()
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val W = LifecycleWriters
    private fun writer(t: LifecycleTransition) = W.all.single { it.transition == t }

    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun cmd(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun obj(e: JsonElement, change: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(e.jsonObject.toMutableMap().apply(change))

    /** A fresh file where [w] confirmed under an old tracker, then the same file reopened. */
    private suspend fun previous(w: LifecycleWriters.Writer): Pair<ControlStoreTestStorage, CommandRef> {
        newFile(); val o = open()
        val c = w.confirm(o)
        val next = open()
        check(tracker(next).lifetimeId !== c.ownerTrackingLifetimeId) { "fixture: new lifetime" }
        return next to c
    }
    private suspend fun lItem(c: CommandRef, from: Preferences? = null) =
        PreviousEvidenceSelection.Item.Lifecycle(c.id, node(arr(from ?: disk(), evidenceKey).single { cmd(it) == c.id }.toString()))
    private fun selection(vararg items: PreviousEvidenceSelection.Item) = PreviousEvidenceSelection(items.toList())
    private fun closure(next: ControlStoreTestStorage, sel: PreviousEvidenceSelection) = PreviousReclamationClosure(
        sel.items.map { it.commandId }.toSet(), tracker(next).lifetimeId, "owner-1", 7L, 7L, setOf("job-1"), setOf("job-1"),
        setOf("job-1"), true, true, true, true)
    private suspend fun reclaim(next: ControlStoreTestStorage, sel: PreviousEvidenceSelection, retry: Boolean = false) =
        controlTestTimeout("previous reclaim") { next.control.reclaimPreviousSettlementOrLifecycleEvidence(sel, closure(next, sel), retry) }
    /** Independent oracle: [before] minus exactly the selected rows and the seals settled by the selected operations. */
    private fun oracle(before: Preferences, vararg ids: String): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { cmd(it) !in ids }).toString()
        val seals = arr(before, sealKey)
        if (seals.any { op(it) in ids }) this[sealKey] = JsonArray(seals.filter { op(it) !in ids }).toString()
        remove(BARRIER)
    }.toPreferences()
    private suspend fun assertReclaimed(id: String, next: ControlStoreTestStorage, r: PreviousEvidenceReclamationResult,
        expected: Preferences, ids: List<String>, disposition: PreviousReclamationDisposition) {
        assertTrue("D2B6/6-4aC.$id: reclaimed $r", r is PreviousEvidenceReclamationResult.Reclaimed)
        r as PreviousEvidenceReclamationResult.Reclaimed
        assertEquals("D2B6/6-4aC.$id: disposition", disposition, r.disposition)
        assertEquals("D2B6/6-4aC.$id: operations", ids, r.selectedOperationIds)
        val after = disk()
        assertEquals("D2B6/6-4aC.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-4aC.$id: exactDeletion", expected, withoutBarrier(after))
        val work = tracker(next).recoverySnapshot()
        assertEquals("D2B6/6-4aC.$id: U", work.unresolvedCommands, r.localUnresolvedCommands)
        assertEquals("D2B6/6-4aC.$id: P", work.pendingReleases, r.localPendingReleases)
    }
    private suspend fun kept(id: String, next: ControlStoreTestStorage, sel: PreviousEvidenceSelection, retry: Boolean = false,
        check: (PreviousEvidenceReclamationResult) -> Boolean) {
        val before = disk()
        val r = reclaim(next, sel, retry)
        assertTrue("D2B6/6-4aC.$id: $r", check(r))
        assertEquals("D2B6/6-4aC.$id: recordUntouched", withoutBarrier(before), withoutBarrier(disk()))
    }

    // ── T7.1: each writer's previous Lifecycle row is reclaimed after a real restart; SEAL text untouched ────────
    @Test fun PL_01_eachWritersPreviousLifecycleRowIsReclaimed() = runBlocking {
        for (w in W.all) {
            val (next, c) = previous(w); val before = disk()
            val r = reclaim(next, selection(lItem(c)))
            assertReclaimed("01 ${w.transition}", next, r, oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
            assertEquals("D2B6/6-4aC.01 ${w.transition}: sealTextByteIdentical", before[sealKey], disk()[sealKey])
            assertNull("D2B6/6-4aC.01 ${w.transition}: oldRefNotRegistered", tracker(next).findPrepared(c))
        }
    }
    @Test fun PL_02_withNoSettlementItemTheSealPayloadIsNotReencoded() = runBlocking {
        // A decodable SEAL payload whose text is not the codec's canonical form (spaces, an escaped slash).
        val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD))
        val spaced = """[ {"id":"free","kind":"NULL_NAMESPACE","ownerUid":"Q\/x","axis":"USER"} ]"""
        next.data.updateData { p -> p.toMutablePreferences().apply { this[sealKey] = spaced }.toPreferences() }
        val before = disk()
        assertReclaimed("02", next, reclaim(next, selection(lItem(c))), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        assertEquals("D2B6/6-4aC.02: sealTextKept", spaced, disk()[sealKey])
    }

    // ── T8: S and L in one atomic candidate; several L ─────────────────────────────────────────────────────────────
    /** A previous Lifecycle row confirmed by [w] in another file under another old tracker. */
    private suspend fun foreignLifecycleRow(w: LifecycleWriters.Writer): JsonElement {
        val holder = TemporaryFolder().also { it.create() }
        val other = ControlStoreTestStorage(File(holder.root, "l.preferences_pb"))
        try {
            val c = w.confirm(other)
            return arr(controlTestTimeout("foreign read") { other.raw() }, evidenceKey).single { cmd(it) == c.id }
        } finally { other.close(); holder.delete() }
    }
    private suspend fun withRows(next: ControlStoreTestStorage, vararg rows: JsonElement) =
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(p, evidenceKey) + rows).toString() }.toPreferences() }
    @Test fun PL_03_settlementAndLifecycleItemsShareOneCandidate() = runBlocking {
        // A previous RETIRED_NULL settlement bundle (real, old tracker) plus two previous Lifecycle rows.
        newFile(); val o = open()
        val L = RetiredNullFixtures; val s = L.spec()
        o.data.updateData { L.raw(s) }
        val sc = tracker(o).registerPrepared(CommandRef(s.operationId, ControlCommandBody.SettleRetiredNull(s), tracker(o).lifetimeId))
        check(controlTestTimeout("settle") { o.control.execute(sc, L.context) } is ControlStoreResult.Confirmed)
        val next = open()
        val l1 = foreignLifecycleRow(writer(LifecycleTransition.RECOVER_INTENT))
        val l2 = foreignLifecycleRow(writer(LifecycleTransition.SETTLE_QUERY))
        withRows(next, l1, l2)
        val before = disk()
        val sRow = arr(before, evidenceKey).single { cmd(it) == sc.id }
        val sSeals = arr(before, sealKey).filter { op(it) == sc.id }
        val sItem = PreviousEvidenceSelection.Item.Settlement(sc.id, node(sRow.toString()), sSeals.map { node(it.toString()) })
        val i1 = PreviousEvidenceSelection.Item.Lifecycle(cmd(l1), node(l1.toString()))
        val i2 = PreviousEvidenceSelection.Item.Lifecycle(cmd(l2), node(l2.toString()))
        // One stale L item refuses the whole call.
        // (a valid-shape change of the SETTLE_QUERY suffix effect REPLACE → CREATE, so the raw stays eligible)
        val stale = PreviousEvidenceSelection.Item.Lifecycle(cmd(l2), node(obj(l2) {
            this["targets"] = JsonArray(getValue("targets").jsonArray.map { t ->
                if (t.jsonObject.getValue("effect").jsonPrimitive.content == "REPLACE") obj(t) { this["effect"] = JsonPrimitive("CREATE") } else t })
        }.toString()).also { check(it.toPayloadEntry().fields != node(l2.toString()).toPayloadEntry().fields) { "fixture: stale differs" } })
        kept("03 staleWhole", next, selection(sItem, i1, stale)) { it is PreviousEvidenceReclamationResult.Conflict }
        assertReclaimed("03 mixed", next, reclaim(next, selection(sItem, i1, i2)), oracle(before, sc.id, cmd(l1), cmd(l2)),
            listOf(sc.id, cmd(l1), cmd(l2)), PreviousReclamationDisposition.RemovedNow)
    }

    // ── T7.2: eligibility, refused before storage ──────────────────────────────────────────────────────────────
    @Test fun PL_04_eachLifecycleEligibilityBranchAloneIsRefusedBeforeStorage() = runBlocking {
        val (next, c) = previous(writer(LifecycleTransition.RECOVER_HOLD)); val item = lItem(c)
        suspend fun invalid(id: String, sel: PreviousEvidenceSelection) {
            val before = disk(); val writes = next.storage.writes
            val r = reclaim(next, sel)
            assertTrue("D2B6/6-4aC.04 $id: $r", (r as? PreviousEvidenceReclamationResult.Rejected)?.reason is PreviousReclamationRejectionReason.InvalidSelection)
            assertNull("D2B6/6-4aC.04 $id: noObservation", (r as PreviousEvidenceReclamationResult.Rejected).observation)
            assertEquals("D2B6/6-4aC.04 $id: noWrite", writes, next.storage.writes)
            assertEquals("D2B6/6-4aC.04 $id: fileUnchanged", before, disk())
        }
        val raw = item.rawApplied.toPayloadEntry().fields
        invalid("uninterpretable", selection(PreviousEvidenceSelection.Item.Lifecycle(c.id, node("""{"version":2}"""))))
        invalid("wrongKind", selection(PreviousEvidenceSelection.Item.Lifecycle(c.id, node(
            """{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"x","joined":false,"written":true}]}"""))))
        invalid("commandId", selection(PreviousEvidenceSelection.Item.Lifecycle("other-command", item.rawApplied)))
        invalid("currentLifetime", selection(PreviousEvidenceSelection.Item.Lifecycle(c.id, node(obj(raw) {
            this["ownerTrackingLifetimeId"] = JsonPrimitive(tracker(next).lifetimeId.value) }.toString()))))
        invalid("duplicateCommand", selection(item, PreviousEvidenceSelection.Item.Lifecycle(c.id, item.rawApplied)))
    }

    // ── T7.3: owner decisions keep the record ────────────────────────────────────────────────────────────────────
    @Test fun PL_05_latestRowMustBeTheSelectedLifecycleRow() = runBlocking {
        run { // Absent on the first call.
            val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD)); val item = lItem(c)
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[evidenceKey] = JsonArray(arr(p, evidenceKey).filter { cmd(it) != c.id }).toString() }.toPreferences() }
            kept("05 absentFirst", next, selection(item)) { it is PreviousEvidenceReclamationResult.Conflict }
        }
        run { // The latest row changed after the selection (valid shape, effect only).
            val (next, c) = previous(writer(LifecycleTransition.SETTLE_QUERY)); val item = lItem(c)
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[evidenceKey] = JsonArray(arr(p, evidenceKey).map { if (cmd(it) == c.id) obj(it) {
                    this["targets"] = Json.parseToJsonElement("""[{"kind":"DEMAND","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"g","effect":"CREATE"}]""")
                } else it }).toString() }.toPreferences() }
            kept("05 latestChanged", next, selection(item)) {
                it is PreviousEvidenceReclamationResult.Conflict && it.reason == ConflictReason.CommandEvidenceMismatch }
        }
        run { // The latest row with that commandId is a Mutations row.
            val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD)); val item = lItem(c)
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[evidenceKey] = JsonArray(arr(p, evidenceKey).map { if (cmd(it) == c.id) obj(it) {
                    remove("transition"); this["kind"] = JsonPrimitive("MUTATIONS")
                    this["targets"] = Json.parseToJsonElement("""[{"index":0,"kind":"DEMAND","id":"g","joined":false,"written":true}]""")
                } else it }).toString() }.toPreferences() }
            kept("05 latestKind", next, selection(item)) { it is PreviousEvidenceReclamationResult.Conflict }
        }
    }

    // ── T9: faults and the same-selection retry ─────────────────────────────────────────────────────────────────
    @Test fun PL_06_faultsAndRetry() = runBlocking {
        run { // Not landed → retry removes.
            val (next, c) = previous(writer(LifecycleTransition.UPDATE_AUTH)); val before = disk(); val sel = selection(lItem(c))
            next.storage.before = true
            assertTrue("D2B6/6-4aC.06 notLanded", reclaim(next, sel) is PreviousEvidenceReclamationResult.Unconfirmed)
            next.storage.before = false
            assertReclaimed("06 retryRemoves", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        }
        run { // Landed then the return failed → retry confirms absence.
            val (next, c) = previous(writer(LifecycleTransition.END_AUTH_BINDING)); val before = disk(); val sel = selection(lItem(c))
            next.storage.afterScope = true
            assertTrue("D2B6/6-4aC.06 landed", reclaim(next, sel) is PreviousEvidenceReclamationResult.Unconfirmed)
            next.storage.afterScope = false
            assertEquals("D2B6/6-4aC.06 landedState", oracle(before, c.id), withoutBarrier(disk()))
            assertReclaimed("06 retryAbsent", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.AlreadyAbsent)
        }
        run { // Cancellation after landing propagates; the retry confirms absence.
            val (next, c) = previous(writer(LifecycleTransition.REBIND_REQUESTS)); val before = disk(); val sel = selection(lItem(c))
            val pause = ControlStoreTestStorage.Pause(); next.storage.pauseAfterScope = pause
            val caller = async { next.control.reclaimPreviousSettlementOrLifecycleEvidence(sel, closure(next, sel)) }
            try { withTimeout(10_000) { pause.reached.await() }; caller.cancelAndJoin(); assertTrue(caller.isCancelled) }
            finally { pause.release.complete(Unit); caller.cancelAndJoin() }
            next.storage.pauseAfterScope = null
            assertReclaimed("06 retryAfterCancel", next, reclaim(next, sel, retry = true), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.AlreadyAbsent)
        }
        run { // Retry: one of two selected rows still present → held.
            val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD))
            val other = foreignLifecycleRow(writer(LifecycleTransition.RECOVER_INTENT)); withRows(next, other)
            val sel = selection(lItem(c), PreviousEvidenceSelection.Item.Lifecycle(cmd(other), node(other.toString())))
            next.data.updateData { p -> p.toMutablePreferences().apply {
                this[evidenceKey] = JsonArray(arr(p, evidenceKey).filter { cmd(it) != c.id }).toString() }.toPreferences() }
            kept("06 retryPartial", next, sel, retry = true) {
                it is PreviousEvidenceReclamationResult.RecoveryRequired && it.reason == RecoveryReason.InconsistentReclamation }
        }
    }

    // ── T9: G11 over the selected Lifecycle Applied row ─────────────────────────────────────────────────────────
    @Test fun PL_07_onlyTheAppliedRowOrAGapBlocks() = runBlocking {
        run { // An old ref of the same Lifecycle operation left in U projects business rows only → passes.
            val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD)); tracker(next).markUnresolved(c)
            val before = disk()
            assertReclaimed("07 oldSameOperation", next, reclaim(next, selection(lItem(c))), oracle(before, c.id), listOf(c.id),
                PreviousReclamationDisposition.RemovedNow)
        }
        run { // A pending current ref missing its descriptor: a broad gap that may reach the Applied row.
            val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD))
            val m = next.control.prepare(next.control.addition(ControlKind.RECOVERY_INTENT) { id ->
                literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
            ControlReleaseFixtures.pending(m)
            val w = tracker(next).recoverySnapshot()
            ControlReleaseFixtures.replaceRecovery(tracker(next), LocalRecoveryWork(w.unresolvedCommands, w.pendingReleases + m))
            kept("07 gap", next, selection(lItem(c))) {
                val reason = (it as? PreviousEvidenceReclamationResult.Rejected)?.reason
                reason is PreviousReclamationRejectionReason.DependencyUnknown && reason.dependentCommandId == m.id }
        }
    }

    // ── T9: 2c keeps previous Lifecycle rows; this API takes them ───────────────────────────────────────────────
    @Test fun PL_08_theGenericPreviousReclamationKeepsLifecycleRows() = runBlocking {
        val (next, c) = previous(writer(LifecycleTransition.RECOVER_HOLD)); val before = disk()
        val r2c = controlTestTimeout("2c") { next.control.reclaimPreviousLifetimeEvidence() }
        assertTrue("D2B6/6-4aC.08 2c: $r2c", r2c is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("D2B6/6-4aC.08 2cKeepsLifecycle", withoutBarrier(before), withoutBarrier(disk()))
        assertReclaimed("08", next, reclaim(next, selection(lItem(c))), oracle(before, c.id), listOf(c.id), PreviousReclamationDisposition.RemovedNow)
        assertFalse("D2B6/6-4aC.08 rowGone", arr(disk(), evidenceKey).any { cmd(it) == c.id })
    }

    // ── 6-3C2 a extended: the pure decider's retry all-absent eligibility re-check covers Lifecycle items ─────────
    @Test fun PL_09_pureDeciderLifecycleEligibilityAndRetryAbsent() = runBlocking {
        val (next, c) = previous(writer(LifecycleTransition.REMOVE_EMPTY_GUARD)); val item = lItem(c)
        val current = tracker(next).lifetimeId
        val present = ControlRecordReader().read(disk()) as ControlRecordRead.Supported
        val absent = ControlRecordReader().read(disk().toMutablePreferences().apply {
            this[evidenceKey] = JsonArray(arr(disk(), evidenceKey).filter { cmd(it) != c.id }).toString() }.toPreferences()) as ControlRecordRead.Supported
        fun decide(read: ControlRecordRead.Supported, sel: PreviousEvidenceSelection, retry: Boolean) =
            PreviousSettlementEvidenceReclamation.decide(read, sel, current, retry, ControlPayloadCodec())
        assertEquals("D2B6/6-4aC.09 retryAbsent", PreviousSettlementEvidenceReclamation.Decision.Ready(absent.original), decide(absent, selection(item), true))
        val wrongKind = PreviousEvidenceSelection.Item.Lifecycle(c.id, node(
            """{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"x","joined":false,"written":true}]}"""))
        for ((name, read, retry) in listOf(Triple("inline", present, false), Triple("retryAbsent", absent, true))) {
            val d = decide(read, selection(wrongKind), retry)
            assertTrue("D2B6/6-4aC.09 wrongKind $name: $d", d is PreviousSettlementEvidenceReclamation.Decision.Rejected &&
                d.reason is RejectionReason.InvalidRequest)
        }
        val otherCommand = PreviousEvidenceSelection.Item.Lifecycle("other-command", item.rawApplied)
        val d = decide(absent, selection(otherCommand), true)
        assertTrue("D2B6/6-4aC.09 retryAbsent commandId: $d", d is PreviousSettlementEvidenceReclamation.Decision.Rejected &&
            d.reason is RejectionReason.InvalidRequest)
    }
}
