package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-2B contract: current-lifetime Rotation consumption termination (6-2 skeleton r3 §2–§3; revision 06
 * §5.1·§5.2·§3). API fixed by this contract:
 *   suspend fun ControlRecordStore.completeAfterConsumption(command, closure, consumption: RotationConsumption)
 *   class RotationConsumption(resultConsumed: Boolean, followUpCompletedOrDurablyOwned: Boolean)
 *   TerminationEntry.ConsumedRotation, TerminationPendingDescriptor.ExactEvidenceAndSeals(mode, entry, closureBinding,
 *   expectedRotation, operationId, orderedSealIds), CompletionRejectionReason.NotConfirmed / Unresolved /
 *   ConsumptionNotDeclared, RecoveryReason.ExpectedRotationEvidenceUnavailable. retryTermination dispatches on the
 *   fixed descriptor kind and takes no new consumption declaration.
 *
 * First entry: lifetime → terminal/other path → lease → exact registration → one body view (non-Rotation body is
 * UnsupportedInThisUnit) → confirmed, not unresolved, both declarations true, closure — all before storage. The owner
 * then needs a non-null Rotation expectedApplied (null = ExpectedRotationEvidenceUnavailable, the settled-witness confirm
 * path makes it reachable), an own Applied row that exists (else CommandEvidenceLost) and equals expected exactly
 * (else Conflict), and the five §5.1 checks: (1) wholly interpretable schema 2 — a duplicate global id makes entries
 * uninterpretable, so it is RecoveryRequired(UninterpretableObligations); (2) the row's sealIds in the fixed order;
 * (3) each id a settled NAMESPACE of this operation; (4) the record's whole set of this operation's seals equals the
 * row's ids; (5) each seal's immutable text and witness equal the fixed input. (2)–(5) fail as
 * RecoveryRequired(InconsistentReclamation). Every refusal keeps the record (no Confirm), RETAINED on first entry.
 * The candidate removes exactly the own row and this operation's seals; REQUEST/journal/epochs/other rows and every
 * other key survive in order. Current epoch/REQUEST postconditions are NOT required. Pending retry accepts only the
 * whole bundle exactly present (same deletion) or wholly absent (absence Confirm). Records come from the real
 * rotation execute path. Dependency (G11), accumulation and restart are 6-2C. The implementation thread reads but does
 * not edit this file.
 */
class ControlRotationConsumptionContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    private val openedBoundary = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() }; openedBoundary.forEach { it.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val N = NamespaceSettlementFixtures
    private val both = N.input(targets = listOf(node(N.user), node(N.krx)))
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)

    private fun register(f: TerminationFixture, input: RotateAndSettleNamespaces) =
        f.tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
    /** A real confirmed, not-unresolved rotation with a non-null Rotation expectedApplied. */
    private suspend fun confirmed(f: TerminationFixture, input: RotateAndSettleNamespaces = N.input(), seals: String = "[${N.user}]"): CommandRef {
        f.edit { it.clear(); it += N.raw(seals) }
        val c = register(f, input)
        val r = controlTestTimeout("rotation execute") { f.store.execute(c, N.context) }
        check(r is ControlStoreResult.Confirmed) { "fixture: $r" }
        val t = f.history(c)
        check(t.confirmed.get() && t.expectedApplied is AppliedEvidence.Rotation && !f.tracker.isUnresolved(c)) { "fixture state" }
        return c
    }
    private suspend fun consume(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c), d: RotationConsumption = declared) =
        controlTestTimeout("consume") { f.store.completeAfterConsumption(c, k, d) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)) =
        controlTestTimeout("retry") { f.store.retryTermination(c, k) }

    private fun arr(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(e: JsonElement) = e.jsonObject.getValue("id").jsonPrimitive.content
    private fun op(e: JsonElement) = e.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
    private fun rowCommand(e: JsonElement) = e.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun replaceSeal(seals: JsonArray, target: String, change: (String) -> String): String =
        JsonArray(seals.map { if (id(it) == target) Json.parseToJsonElement(change(it.toString())).also { n -> check(n != it) { "fixture edit" } } else it }).toString()
    /** Independent oracle: the before record minus exactly the own row and this operation's seals; every other key equal. */
    private fun expectedAfterDeletion(before: Preferences, c: CommandRef): Preferences = before.toMutablePreferences().apply {
        this[evidenceKey] = JsonArray(arr(before, evidenceKey).filter { rowCommand(it) != c.id }).toString()
        this[sealKey] = JsonArray(arr(before, sealKey).filter { op(it) != c.id }).toString()
        remove(ControlStoreTestStorage.BARRIER)
    }.toPreferences()

    private suspend fun assertConsumed(id: String, f: TerminationFixture, c: CommandRef, r: ControlCompletionResult, expected: Preferences) {
        assertTrue("D2B6/6-2B.$id: completed $r", r is ControlCompletionResult.Completed)
        r as ControlCompletionResult.Completed
        assertEquals("D2B6/6-2B.$id: consumed", CompletionMode.Consumed, r.mode)
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("D2B6/6-2B.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-2B.$id: exactDeletion", expected, withoutBarrier(after))
        assertEquals("D2B6/6-2B.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-2B.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/6-2B.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        val work = f.tracker.recoverySnapshot()
        assertFalse("D2B6/6-2B.$id: notInU", c in work.unresolvedCommands)
        assertFalse("D2B6/6-2B.$id: notInP", c in work.pendingReleases)
        assertEquals("D2B6/6-2B.$id: resultSets", work.unresolvedCommands, r.localUnresolvedCommands)
        assertEquals("D2B6/6-2B.$id: resultSets", work.pendingReleases, r.localPendingReleases)
        assertTrue("D2B6/6-2B.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun refusedBeforeStorage(id: String, f: TerminationFixture, c: CommandRef, reason: CompletionRejectionReason,
        k: TerminationClosure = closure(c), d: RotationConsumption = declared, expectedExecuting: Set<CommandRef> = emptySet()) {
        val before = f.storage.raw(); val access = f.boundary.accesses; val work = f.tracker.recoverySnapshot()
        val r = consume(f, c, k, d)
        assertEquals("D2B6/6-2B.$id: $reason", reason, (r as? ControlCompletionResult.Rejected)?.reason)
        assertEquals("D2B6/6-2B.$id: noStorageAccess", 0, f.boundary.accesses - access)
        assertEquals("D2B6/6-2B.$id: recordUntouched", before, f.storage.raw())
        assertEquals("D2B6/6-2B.$id: setsKept", work, f.tracker.recoverySnapshot())
        if (c.lifecycleState == ControlCommandLifecycle.RETAINED) assertNull("D2B6/6-2B.$id: noDescriptor", f.tracker.findPrepared(c)?.terminationDescriptor)
        assertEquals("D2B6/6-2B.$id: leaseState", expectedExecuting, f.tracker.executing.toSet())
    }
    /** Owner refusal on first entry: RETAINED, no descriptor/P, record unchanged even with the read-back armed (Confirm 0). */
    private suspend fun ownerRefused(id: String, f: TerminationFixture, c: CommandRef, expected: Any) {
        f.armReadBack()
        val before = f.storage.raw(); val work = f.tracker.recoverySnapshot()
        val r = consume(f, c)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-2B.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            ConflictReason::class -> assertTrue("D2B6/6-2B.$id: conflict $r", r is ControlCompletionResult.Conflict)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-2B.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-2B.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-2B.$id: setsKept", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/6-2B.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-2B.$id: leaseReleased", f.tracker.executing.isEmpty())
    }

    // ── T1 eligibility, all before storage ───────────────────────────────────────────────────────────────────────
    @Test fun T1_01_consumedRotationIsTerminatedWithExactDeletion() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk()
        check(arr(before, evidenceKey).any { rowCommand(it) == c.id } && op(arr(before, sealKey).single()) == c.id)
        val r = consume(f, c)
        assertConsumed("01", f, c, r, expectedAfterDeletion(before, c))
        assertEquals("D2B6/6-2B.01: proof", RecordTransactionEvidence.CompletedWriteScope, (r as ControlCompletionResult.Completed).proof.storage)
        assertEquals("D2B6/6-2B.01: requestKept", before[demandKey], f.disk()[demandKey])
    }
    @Test fun T1_02_notConfirmed() = runBlocking {
        val f = fixture(); f.edit { it.clear(); it += N.raw() }; val c = register(f, N.input())
        refusedBeforeStorage("02", f, c, CompletionRejectionReason.NotConfirmed)
    }
    @Test fun T1_03_unresolved() = runBlocking {
        val f = fixture(); val c = confirmed(f); f.addUnresolved(c)
        refusedBeforeStorage("03", f, c, CompletionRejectionReason.Unresolved)
    }
    @Test fun T1_04_eachDeclarationMustBeTrue() = runBlocking {
        for ((name, d) in listOf("result" to RotationConsumption(false, true), "followUp" to RotationConsumption(true, false))) {
            val f = fixture(); val c = confirmed(f)
            refusedBeforeStorage("04 $name", f, c, CompletionRejectionReason.ConsumptionNotDeclared, d = d)
        }
    }
    @Test fun T1_05_closureViolation() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        refusedBeforeStorage("05", f, c, CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.EntriesOpen),
            k = closure(c, entriesClosed = false))
    }
    @Test fun T1_06_nonRotationBodyIsUnsupported() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations()
        refusedBeforeStorage("06", f, c, CompletionRejectionReason.UnsupportedInThisUnit)
    }
    @Test fun T1_07_identityAndOrderingRefusals() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        val other = fixture(); val foreign = confirmed(other)
        refusedBeforeStorage("07a", f, foreign, CompletionRejectionReason.WrongTrackerLifetime)
        val clone = CommandRef(c.id, c.body, c.ownerTrackingLifetimeId)
        refusedBeforeStorage("07b", f, clone, CompletionRejectionReason.NotRegisteredIdentity)
        check(f.tracker.executing.add(c))
        try { refusedBeforeStorage("07c", f, c, CompletionRejectionReason.InFlight, expectedExecuting = setOf(c)) } finally { f.tracker.executing.remove(c) }
    }
    @Test fun T1_08_terminalAndPendingRefs() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        assertTrue(consume(f, c) is ControlCompletionResult.Completed)
        val access = f.boundary.accesses
        assertTrue("D2B6/6-2B.08: alreadyTerminated", consume(f, c) is ControlCompletionResult.AlreadyTerminated)
        assertEquals("D2B6/6-2B.08: noStorageAccess", 0, f.boundary.accesses - access)
        val g = fixture(); val p = confirmed(g); pendingByWriteFault(g, p)
        refusedBeforeStorage("08b", g, p, CompletionRejectionReason.OtherManagementPath) // first entry again → retry is the path
    }

    // ── T2 expected / own Applied ────────────────────────────────────────────────────────────────────────────────
    @Test fun T2_10_nullExpectedFromTheWitnessConfirmPathIsUnavailable() = runBlocking {
        // Settled own witnesses without an own row: execute confirms read.original, so expectedApplied is null.
        val f = fixture(); val witnessed = N.settled(N.input(), N.raw(), f.tracker.lifetimeId).toMutablePreferences()
            .apply { this[evidenceKey] = "[]" }.toPreferences()
        f.edit { it.clear(); it += witnessed }
        val c = register(f, N.input())
        check(controlTestTimeout("witness execute") { f.store.execute(c, N.context) } is ControlStoreResult.Confirmed)
        check(f.history(c).confirmed.get() && f.history(c).expectedApplied == null) { "fixture: expected must be null" }
        ownerRefused("10", f, c, RecoveryReason.ExpectedRotationEvidenceUnavailable)
    }
    @Test fun T2_11_ownRowLostAfterConfirmation() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        f.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString() }
        ownerRefused("11", f, c, RecoveryReason.CommandEvidenceLost)
    }
    @Test fun T2_12_ownRowDisagreeingWithExpectedIsConflict() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        f.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"demandId\":\"${N.demandId}\"", "\"demandId\":\"00000000-0000-0000-0000-000000000077\"")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        ownerRefused("12", f, c, ConflictReason::class)
    }

    // ── T3 the five §5.1 checks, each first failing with every earlier check passing ─────────────────────────────
    @Test fun T3_20_uninterpretableRecord() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        f.edit { p -> p[demandKey] = "[17]" }
        ownerRefused("20a", f, c, RecoveryReason.UninterpretableObligations)
        val g = fixture(); val d = confirmed(g)
        // A second entry carrying an existing global id makes both uninterpretable (ControlRecordReader).
        g.edit { p -> p[demandKey] = JsonArray(arr(p.toPreferences(), demandKey) + Json.parseToJsonElement(
            ControlObligationFixtures.request.replace("\"id\":\"d\"", "\"id\":\"s\""))).toString() }
        ownerRefused("20b", g, d, RecoveryReason.UninterpretableObligations)
    }
    // Skeleton r3 §3.0 row 2: the fixed-order check (2) runs before the exact expected comparison, so a parsable order-only
    // change first fails at (2) — InconsistentReclamation, not the later Conflict.
    @Test fun T3_21_rowSealOrder() = runBlocking {
        val f = fixture(); val c = confirmed(f, both, "[${N.user},${N.krx}]")
        f.edit { p -> p[evidenceKey] = checkNotNull(p[evidenceKey]).replace("\"sealIds\":[\"s\",\"c\"]", "\"sealIds\":[\"c\",\"s\"]")
            .also { check(it != p[evidenceKey]) { "fixture edit" } } }
        ownerRefused("21", f, c, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T3_22_eachSealMustBeASettledNamespaceOfThisOperation() = runBlocking {
        val rotated = mutableListOf<Pair<String, (JsonArray) -> String>>(
            "missing" to { s -> JsonArray(s.filter { id(it) != "c" }).toString() },
            "unsettled" to { s -> replaceSeal(s, "c") { N.krx } },
            "otherOperation" to { s -> replaceSeal(s, "c") { it.replace(N.operation, "00000000-0000-0000-0000-000000000099") } },
            "nullNamespace" to { s -> replaceSeal(s, "c") { _ ->
                """{"id":"c","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"CAPABILITY","settlement":{"operationId":"${N.operation}","originLifetimeId":"life","operation":"BEGIN_ROTATION","before":{"ownerUid":"A","userAccessEpoch":"u","krxCapabilityEpoch":null},"after":{"ownerUid":"A","userAccessEpoch":"u","krxCapabilityEpoch":"k9"},"journal":{"ownerUid":"A","axis":"CAPABILITY","epoch":null}}}""" } })
        for ((name, change) in rotated) {
            val f = fixture(); val c = confirmed(f, both, "[${N.user},${N.krx}]")
            f.edit { p -> p[sealKey] = change(arr(p.toPreferences(), sealKey)) }
            check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable }) { "fixture $name: record must stay interpretable" }
            ownerRefused("22 $name", f, c, RecoveryReason.InconsistentReclamation)
        }
    }
    @Test fun T3_23_extraSealOfThisOperation() = runBlocking {
        // Target "s" only; a CAPABILITY seal "c" settled by the same operation is outside the Applied row.
        val f = fixture(); val c = confirmed(f)
        val extra = arr(N.settled(N.input(targets = listOf(node(N.krx))), N.raw("[${N.krx}]"), f.tracker.lifetimeId), sealKey).single()
        check(op(extra) == c.id)
        f.edit { p -> p[sealKey] = JsonArray(arr(p.toPreferences(), sealKey) + extra).toString() }
        check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable })
        ownerRefused("23", f, c, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T3_24_immutableTextAndWitnessMustEqualTheFixedInput() = runBlocking {
        val changes = listOf<Pair<String, (String) -> String>>(
            // Seal epoch and journal epoch together keep the NAMESPACE settlement schema-valid.
            "sealEpoch" to { s -> s.replace("\"epoch\":\"u\"", "\"epoch\":\"u7\"") },
            "witnessOrigin" to { s -> s.replace("\"originLifetimeId\":\"life\"", "\"originLifetimeId\":\"life2\"") },
            "witnessBefore" to { s -> s.replace("\"userAccessEpoch\":\"u\"", "\"userAccessEpoch\":\"u8\"") },
            "witnessJournalOwner" to { s -> s.replace("\"journal\":{\"ownerUid\":\"A\"", "\"journal\":{\"ownerUid\":null") })
        for ((name, change) in changes) {
            val f = fixture(); val c = confirmed(f)
            f.edit { p -> p[sealKey] = replaceSeal(arr(p.toPreferences(), sealKey), "s", change) }
            check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable }) { "fixture $name: must parse" }
            check(op(arr(f.storage.raw(), sealKey).single()) == c.id) { "fixture $name: still this operation" }
            ownerRefused("24 $name", f, c, RecoveryReason.InconsistentReclamation)
        }
    }
    @Test fun T3_24b_immutableOwnerChangeWithANullJournalOwnerIsCaughtByTheTextCheck() = runBlocking {
        // r4 (Codex counterexample): a null-owner rotation's fixed witness journal owner is null, and NAMESPACE allows a
        // null journal owner — so the stored seal's owner can change to "B" while the witness still matches. Only the
        // immutable-text comparison sees it.
        val f = fixture()
        val userNull = N.user.replace("\"ownerUid\":\"A\"", "\"ownerUid\":null").also { check(it != N.user) }
        val input = N.input(targets = listOf(node(userNull)), before = FenceV1(null, "u", "k"), request = N.demand.copy(ownerUid = null))
        f.edit { p -> p.clear(); p += N.raw("[$userNull]"); p.remove(com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.OWNER_UID) }
        val c = register(f, input)
        val r = controlTestTimeout("null-owner execute") { f.store.execute(c, N.context.copy(ownerUid = null)) }
        check(r is ControlStoreResult.Confirmed && f.history(c).expectedApplied is AppliedEvidence.Rotation) { "fixture: $r" }
        f.edit { p -> p[sealKey] = replaceSeal(arr(p.toPreferences(), sealKey), "s") {
            it.replace("\"kind\":\"NAMESPACE\",\"ownerUid\":null", "\"kind\":\"NAMESPACE\",\"ownerUid\":\"B\"") } }
        check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable }) { "fixture: must parse" }
        ownerRefused("24b", f, c, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T3_25_consumedRequestAndMovedEpochsAreNotRequired() = runBlocking {
        // Business postconditions may already be consumed/changed; reclamation neither requires nor restores them.
        val f = fixture(); val c = confirmed(f)
        f.edit { p -> p[demandKey] = "[]" }
        val before = f.disk()
        assertConsumed("25", f, c, consume(f, c), expectedAfterDeletion(before, c))
    }

    // ── T4 candidate keeps everything else ───────────────────────────────────────────────────────────────────────
    @Test fun T4_30_otherOperationsSealsAndRowsSurviveInOrder() = runBlocking {
        val f = fixture(); val c = confirmed(f)
        val other = N.input(targets = listOf(node(N.krx)), op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        val foreignRecord = N.settled(other, N.raw("[${N.krx}]"), N.trackerLife)
        f.edit { p ->
            p[sealKey] = JsonArray(arr(foreignRecord, sealKey) + arr(p.toPreferences(), sealKey)).toString()   // other first
            p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey) + arr(foreignRecord, evidenceKey)).toString() // other last
        }
        check(ControlRecordReader().read(f.storage.raw()).let { it is ControlRecordRead.Supported && !it.hasUninterpretable })
        val before = f.disk()
        assertConsumed("30", f, c, consume(f, c), expectedAfterDeletion(before, c))
        assertEquals("D2B6/6-2B.30: otherSealKept", listOf("c"), arr(f.disk(), sealKey).map { id(it) })
        assertEquals("D2B6/6-2B.30: otherRowKept", listOf(other.operationId), arr(f.disk(), evidenceKey).map { rowCommand(it) })
    }
    private suspend fun badReturn(id: String, change: (Preferences, CommandRef) -> Preferences) {
        lateinit var boundary: ReleaseBoundaryData
        val s = ControlStoreTestStorage(File(folder.root, "return-$id.preferences_pb")) { ReleaseBoundaryData(it).also { b -> boundary = b } }
            .also { openedBoundary += it }
        s.data.updateData { N.raw() }
        val tracker = ControlCommandTracking.forOwner(s.owner)
        val c = tracker.registerPrepared(CommandRef(N.operation, ControlCommandBody.RotateAndSettle(N.input()), tracker.lifetimeId))
        check(controlTestTimeout("rotation execute") { s.control.execute(c, N.context) } is ControlStoreResult.Confirmed)
        boundary.after = { change(it, c) }
        val failure = runCatching { controlTestTimeout("consume") { s.control.completeAfterConsumption(c, closure(c), declared) } }.exceptionOrNull()
        assertEquals("D2B6/6-2B.$id: invariantPropagates", IllegalStateException::class.java, failure?.javaClass)
        assertEquals("D2B6/6-2B.$id: pendingKept", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertNotNull("D2B6/6-2B.$id: descriptorKept", tracker.findPrepared(c)?.terminationDescriptor)
        assertTrue("D2B6/6-2B.$id: pKept", c in tracker.recoverySnapshot().pendingReleases)
        assertTrue("D2B6/6-2B.$id: leaseReleased", tracker.executing.isEmpty())
    }
    @Test fun T4_31_returnWithOwnRowStillPresentIsAnInvariantFailure() = runBlocking {
        badReturn("31a") { returned, c -> returned.toMutablePreferences().apply {
            this[evidenceKey] = """[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"ROTATION","sealIds":["s"],"demandId":"${N.demandId}"}]"""
        }.toPreferences() }
    }
    @Test fun T4_32_returnWithAnUnrelatedKeyChangedIsAnInvariantFailure() = runBlocking {
        badReturn("32") { returned, _ -> returned.toMutablePreferences().apply { this[demandKey] = "[]" }.toPreferences() }
    }

    // ── T5 pending boundary and retry ────────────────────────────────────────────────────────────────────────────
    /** Management Confirm write fails before its block: TERMINATION_PENDING with the fixed ExactEvidenceAndSeals. */
    private suspend fun pendingByWriteFault(f: TerminationFixture, c: CommandRef, k: TerminationClosure = closure(c)): TerminationPendingDescriptor {
        f.storage.storage.before = true
        val r = consume(f, c, k)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
        return checkNotNull(f.history(c).terminationDescriptor)
    }
    @Test fun T5_40_readFaultKeepsRetained() = runBlocking {
        val f = fixture(); val c = confirmed(f); val work = f.tracker.recoverySnapshot(); val before = f.disk()
        f.boundary.failNextBeforeSnapshot = true
        val r = consume(f, c)
        assertTrue("D2B6/6-2B.40: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-2B.40: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-2B.40: noDescriptor", f.history(c).terminationDescriptor)
        assertEquals("D2B6/6-2B.40: setsKept", work, f.tracker.recoverySnapshot())
        assertConsumed("40r", f, c, consume(f, c), expectedAfterDeletion(before, c))
    }
    @Test fun T5_41_writeFaultFixesTheExactDescriptorThenRetryDeletes() = runBlocking {
        val f = fixture(); val c = confirmed(f); val k = closure(c); val before = f.disk()
        val expected = f.history(c).expectedApplied
        val d = pendingByWriteFault(f, c, k)
        assertTrue("D2B6/6-2B.41: exactDescriptor $d", d is TerminationPendingDescriptor.ExactEvidenceAndSeals)
        d as TerminationPendingDescriptor.ExactEvidenceAndSeals
        assertEquals(CompletionMode.Consumed, d.mode)
        assertEquals(TerminationEntry.ConsumedRotation, d.entry)
        // TerminationClosureBinding has no value equality: compare its fields (identities by reference).
        val b = d.closureBinding
        assertSame(c, b.command); assertSame(c.ownerTrackingLifetimeId, b.ownerTrackingLifetimeId)
        assertEquals(listOf(k.relatedScope, k.currentGeneration, k.entriesClosed, k.captured, k.joined, k.registered, k.receiptsClosed, k.owner),
            listOf(b.relatedScope, b.generation, b.entriesClosed, b.captured, b.joined, b.registered, b.receiptsClosed, b.owner))
        assertSame("D2B6/6-2B.41: expectedRotation", expected, d.expectedRotation)
        assertEquals(c.id, d.operationId)
        assertEquals(listOf("s"), d.orderedSealIds)
        assertTrue("D2B6/6-2B.41: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-2B.41: nothingLanded", before, f.disk())
        assertConsumed("41r", f, c, retry(f, c, k), expectedAfterDeletion(before, c))
    }
    @Test fun T5_42_landedThenReturnFailedRetryConfirmsAbsence() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk()
        f.storage.storage.afterScope = true
        val r = consume(f, c)
        assertTrue("D2B6/6-2B.42: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-2B.42: pending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        val landed = f.disk()
        assertEquals("D2B6/6-2B.42: landed", expectedAfterDeletion(before, c), withoutBarrier(landed))
        assertConsumed("42r", f, c, retry(f, c), withoutBarrier(landed)) // all-absent → absence Confirm, nothing more removed
    }
    @Test fun T5_43_blockRanButScopeFailedRetryDeletes() = runBlocking {
        val f = fixture(); val c = confirmed(f); val before = f.disk()
        f.storage.storage.after = true
        val r = consume(f, c)
        assertTrue("D2B6/6-2B.43: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-2B.43: nothingLanded", withoutBarrier(before), withoutBarrier(f.disk()))
        assertConsumed("43r", f, c, retry(f, c), expectedAfterDeletion(before, c))
    }
    private suspend fun retryHeld(id: String, f: TerminationFixture, c: CommandRef, d: TerminationPendingDescriptor, expected: Any,
        k: TerminationClosure = closure(c)) {
        f.armReadBack()
        val before = f.storage.raw()
        val r = retry(f, c, k)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/6-2B.$id: recovery $r", expected, (r as? ControlCompletionResult.RecoveryRequired)?.reason)
            is CompletionRejectionReason -> assertEquals("D2B6/6-2B.$id: rejected $r", expected, (r as? ControlCompletionResult.Rejected)?.reason)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/6-2B.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("D2B6/6-2B.$id: descriptorKept", d, f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-2B.$id: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-2B.$id: noConfirmRequested", before, f.storage.raw())
        assertTrue("D2B6/6-2B.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    @Test fun T5_44_retryPartialBundleIsHeld() = runBlocking {
        // Seal gone, own row still present: neither exactly present nor wholly absent.
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        f.edit { p -> p[sealKey] = "[]" }
        retryHeld("44a", f, c, d, RecoveryReason.InconsistentReclamation)
        // Own row gone, seal still present.
        val g = fixture(); val e = confirmed(g); val h = pendingByWriteFault(g, e)
        g.edit { p -> p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != e.id }).toString() }
        retryHeld("44b", g, e, h, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T5_45_retryReplacementIsHeld() = runBlocking {
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        f.edit { p -> p[sealKey] = replaceSeal(arr(p.toPreferences(), sealKey), "s") { it.replace("\"originLifetimeId\":\"life\"", "\"originLifetimeId\":\"life2\"") } }
        retryHeld("45", f, c, d, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T5_46_retryClosureChangeIsHeld() = runBlocking {
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        retryHeld("46", f, c, d, CompletionRejectionReason.ClosureNotSatisfied(ClosureViolation.EntriesOpen), k = closure(c, entriesClosed = false))
    }

    // ── r3 (measurement 6-2B r1): rows the r2 inputs walked past ────────────────────────────────────────────────
    @Test fun T2_13_ownRowOfAnotherKindIsConflict() = runBlocking {
        // A parsable own row (same commandId/lifetime) that is not a Rotation row contradicts the expected Rotation.
        val f = fixture(); val c = confirmed(f)
        f.edit { p -> p[evidenceKey] = """[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}]""" }
        check(ControlAppliedEvidence.own(ControlRecordReader().read(f.storage.raw()) as ControlRecordRead.Supported, c) != null) { "fixture: own row must parse" }
        ownerRefused("13", f, c, ConflictReason::class)
    }
    @Test fun T2_14_nullExpectedIsNotReplacedByALaterOwnRow() = runBlocking {
        // The deletion authority is the fixed expected Rotation, never the latest row: no fallback when expected is null.
        val f = fixture(); val full = N.settled(N.input(), N.raw(), f.tracker.lifetimeId)
        f.edit { it.clear(); it += full.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences() }
        val c = register(f, N.input())
        check(controlTestTimeout("witness execute") { f.store.execute(c, N.context) } is ControlStoreResult.Confirmed)
        check(f.history(c).expectedApplied == null) { "fixture: expected must be null" }
        f.edit { p -> p[evidenceKey] = checkNotNull(full[evidenceKey]) }
        check(arr(f.storage.raw(), evidenceKey).any { rowCommand(it) == c.id })
        ownerRefused("14", f, c, RecoveryReason.ExpectedRotationEvidenceUnavailable)
    }
    @Test fun T5_47_retryWithAnotherSealOfThisOperationIsNotAbsence() = runBlocking {
        // Own row and the fixed seal are gone, but a non-target seal still carries this operation's witness.
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        val extra = arr(N.settled(N.input(targets = listOf(node(N.krx))), N.raw("[${N.krx}]"), f.tracker.lifetimeId), sealKey).single()
        check(op(extra) == c.id && id(extra) == "c")
        f.edit { p ->
            p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
            p[sealKey] = JsonArray(listOf(extra)).toString()
        }
        retryHeld("47", f, c, d, RecoveryReason.InconsistentReclamation)
    }
    @Test fun T5_48_retryWithAReplacementAtTheFixedIdIsNotAbsence() = runBlocking {
        // Own row gone and no seal of this operation remains, but the fixed id "s" is occupied by an unsettled replacement.
        val f = fixture(); val c = confirmed(f); val d = pendingByWriteFault(f, c)
        f.edit { p ->
            p[evidenceKey] = JsonArray(arr(p.toPreferences(), evidenceKey).filter { rowCommand(it) != c.id }).toString()
            p[sealKey] = "[${N.user}]"
        }
        check(arr(f.storage.raw(), sealKey).none { op(it) == c.id })
        retryHeld("48", f, c, d, RecoveryReason.InconsistentReclamation)
    }
}
