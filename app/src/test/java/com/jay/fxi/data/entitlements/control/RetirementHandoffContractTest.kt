package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4bA4a contract (6-4bA3 consensus r7 N14, N15) on a real CURRENT_NULL settlement (USER NULL seal s + old-epoch
 * companion us, CurrentNullFixtures): one shared null-epoch journal A|||USER, two settled seals, the n-demand REQUEST.
 *  - N14: a seal-backed NAMESPACE_RETIREMENT L/N slot gets its own retained confirmation for Journal(scope.journalKey). The token
 *    is a RetirementJournal carrying the locator, the scope, the observed after fence and the settled rows of every same-axis
 *    fixed seal in fixed order. Reason order: NOT_A_REQUIRED_SOURCE (not a seal-backed retirement, e.g. RECOVER_HOLD's) →
 *    OBSERVATION_RECORD_MISMATCH → SUBJECT_OR_BOUND_MISMATCH (slot, scope or locator not the fixed one) → NO_EXACT_RETAINED_ROW
 *    (journal absent, fence not the after, a seal not settled with the exact expected witness).
 *  - N15: declaring a seal-backed retirement complete does not conflict with its same-axis seals remaining settled: L requires
 *    the exact expected witness; N rejects a wrong shape, operation, before, after or journal but not another operation id.
 *    An active seal still conflicts; a remaining journal still conflicts.
 * G05's use of the N14 token (destination / lower bound / confirmation) is A4b; here G05 is judged only through completions.
 * The implementation thread reads but does not edit this file.
 */
class RetirementHandoffContractTest : CurrentNullOwnerBase() {
    private val N0 = CurrentNullFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)
    private val journalKey = JournalTargetV1("A", PurgeScope.USER, null)
    private val after = FenceV1("A", N0.newUser, "k2")

    // ── the settlement ─────────────────────────────────────────────────────────────────────────────────────────────────
    private class Settled(val s: CurrentNullSettlement, val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val a1 = deriveRequiredObligations(RequirementInput.Settlement(c, c.body as ControlCommandBody.Handover)) as RequirementDerivation.Available
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun slot(k: RequiredObligationKey) = a1.orderedSlots.single { it.key == k }
        fun at(k: RequiredObligationKey) = required.indexOfFirst { it.key == k }.also { check(it >= 0) }
        fun retirement(branch: LandingBranch) = required.single {
            it.key.component == ObligationComponent.NAMESPACE_RETIREMENT && it.key.branch == branch }
    }
    private suspend fun settle(s: CurrentNullSettlement = N0.spec(companions = listOf(node(N0.companionUser)))): Settled {
        seedN(s); val c = registerN(s)
        return Settled(s, c, successN(executeN(c), ConfirmedEffect.AppliedThisAttempt))
    }
    private suspend fun lockedRead(): RecordTransactionResult<ControlRecordRead.Supported> {
        val r = controlTestTimeout("owner locked read") {
            o.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        assertEquals(RecordTransactionEvidence.LockedFileRead, r.evidence)
        return r
    }
    private fun seal(json: String) = (ControlObligations.read(ControlKind.SEAL, node(json)) as ControlEntryRead.Interpreted).value as SealV1
    /** Independent expected settled rows: the fixture oracle witness on the original seal text. */
    private fun settledRow(s: CurrentNullSettlement, json: String) = NamespaceSettlementFixtures.withWitness(json, N0.witness(s, seal(json)))
    private fun withSnapshot(confirmed: ControlStoreResult.Confirmed, edit: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) =
        confirmed.copy(snapshot = ConfirmedControlSnapshot(ControlRecordReader().read(
            confirmed.snapshot.record.original.toMutablePreferences().apply(edit).toPreferences()) as ControlRecordRead.Supported))
    private fun sealRows(p: Preferences, change: (SealV1, ControlNode) -> ControlNode): String =
        NamespaceSettlementFixtures.jsonArray(*N0.read(p).arrays.getValue(ControlKind.SEAL).entries.map {
            val e = it as ControlEntryRead.Interpreted; change(e.value as SealV1, e.original) }.toTypedArray())
    private fun issued(r: RetainedSourceConfirmationResult): RetainedDestinationTuple.RetirementJournal {
        assertTrue("expected Issued, got $r", r is RetainedSourceConfirmationResult.Issued)
        val observed = ((r as RetainedSourceConfirmationResult.Issued).value.binding as ConfirmationBinding.RetainedSource).observed
        assertTrue("expected RetirementJournal, got $observed", observed is RetainedDestinationTuple.RetirementJournal)
        return observed as RetainedDestinationTuple.RetirementJournal
    }
    private fun rejected(r: RetainedSourceConfirmationResult, reason: RetainedConfirmationFailure) =
        assertEquals(RetainedSourceConfirmationResult.Rejected(reason), r)
    private val journal = DestinationLocator.Journal(journalKey)

    // ═══ N14: issuance ═════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun I01_retirementLN_issuedFromConfirmedAndLockedRead_withScopeAfterAndBothSettledSeals() = runReleaseTest {
        val st = settle()
        for (branch in listOf(L, N)) {
            val slot = st.retirement(branch)
            val scope = ((slot.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.NamespaceRetirement).scope
            assertEquals("fixture: retirement scope", RetirementScope("s", N0.before, after, journalKey), scope)
            for (t in listOf(issued(PriorStorageConfirmation.confirmRetainedSource(slot, journal, st.confirmed)),
                issued(PriorStorageConfirmation.confirmRetainedSource(slot, journal, lockedRead())))) {
                assertEquals(journal, t.locator); assertEquals(scope, t.scope); assertEquals(after, t.observedAfter)
                assertEquals(listOf(settledRow(st.s, N0.nullUser), settledRow(st.s, N0.companionUser)).map { it.toPayloadEntry() },
                    t.settledSeals.map { it.toPayloadEntry() })
            }
        }
    }

    @Test fun I02_recoverHoldRetirement_isNotARequiredSource() = runReleaseTest {
        val H = HoldRecoveryFixtures
        val c = H.command()
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        val slot = a1.orderedSlots.first { it.key.component == ObligationComponent.NAMESPACE_RETIREMENT }
        val key = ((slot.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.NamespaceRetirement).scope.journalKey
        settle()
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Journal(key), lockedRead()),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
    }

    @Test fun I03_lockedReadValueAndSnapshotDiffer_isObservationRecordMismatch() = runReleaseTest {
        val st = settle(); val read = lockedRead()
        val forged = RecordTransactionResult(read.value,
            read.snapshot.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "different" }.toPreferences(), read.evidence)
        rejected(PriorStorageConfirmation.confirmRetainedSource(st.retirement(L), journal, forged), RetainedConfirmationFailure.OBSERVATION_RECORD_MISMATCH)
    }

    @Test fun I04_otherJournalLocator_orChangedScope_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(N)
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.USER, "u2")),
            st.confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val req = slot.requirement as SlotRequirement.Required
        val bound = req.lowerBound as RequiredLowerBound.NamespaceRetirement
        val otherScope = bound.scope.copy(after = FenceV1("A", "other-user", "k2"))
        val changed = slot.copy(key = slot.key.copy(subject = otherScope), requirement = req.copy(lowerBound = bound.copy(scope = otherScope)))
        rejected(PriorStorageConfirmation.confirmRetainedSource(changed, journal, st.confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        // Only the bound's after changed.
        val boundOnly = slot.copy(requirement = req.copy(lowerBound = bound.copy(scope = otherScope)))
        rejected(PriorStorageConfirmation.confirmRetainedSource(boundOnly, journal, st.confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        // Subject and bound re-pointed at the companion us, a seal that is in the fixed list but not the retirement's source.
        val toCompanion = bound.scope.copy(sourceId = "us")
        val repointed = slot.copy(key = slot.key.copy(subject = toCompanion), requirement = req.copy(lowerBound = bound.copy(scope = toCompanion)))
        rejected(PriorStorageConfirmation.confirmRetainedSource(repointed, journal, st.confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        // A fixed SOURCE moved to another target index.
        val moved = slot.copy(requirement = req.copy(fixedSources = req.fixedSources.map {
            if (it.location.root == FixedInputRoot.SETTLEMENT_TARGET && it.location.index == 0 && it.location.facet == FixedInputFacet.SOURCE)
                it.copy(location = it.location.copy(index = 5)) else it }))
        rejected(PriorStorageConfirmation.confirmRetainedSource(moved, journal, st.confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    /** The observed record, one fact broken at a time: journal gone, fence not the after, a seal active, a witness different. */
    @Test fun I05_observedRecordFacts_areNoExactRetainedRow() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val variants = listOf<androidx.datastore.preferences.core.MutablePreferences.() -> Unit>(
            { remove(com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL) },
            { this[USER_EPOCH] = "u3" },
            { this[ControlStoreTestStorage.SEAL] = sealRows(this.toPreferences()) { v, n -> if (v.id == "us") node(N0.companionUser) else n } },
            { this[ControlStoreTestStorage.SEAL] = sealRows(this.toPreferences()) { v, n ->
                if (v.id == "s") NamespaceSettlementFixtures.withWitness(N0.nullUser, N0.witness(st.s, v).copy(operationId = "other-op")) else n } })
        for (edit in variants) {
            val v = withSnapshot(st.confirmed, edit)
            assertTrue("fixture: still interpretable", !v.snapshot.record.hasUninterpretable)
            rejected(PriorStorageConfirmation.confirmRetainedSource(slot, journal, v), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        }
    }

    /** Two axes: each retirement's token carries only its own axis's two seals and journal. */
    @Test fun I06_twoAxes_eachRetirementTokenCarriesOnlyItsOwnAxisSeals() = runReleaseTest {
        val st = settle(N0.both())
        val krxKey = JournalTargetV1("A", PurgeScope.CAPABILITY, null)
        val retirements = st.required.filter { it.key.component == ObligationComponent.NAMESPACE_RETIREMENT && it.key.branch == L }
        assertEquals("fixture: USER and CAPABILITY retirements", 2, retirements.size)
        val byKey = retirements.associateBy {
            ((it.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.NamespaceRetirement).scope.journalKey }
        val user = issued(PriorStorageConfirmation.confirmRetainedSource(byKey.getValue(journalKey), journal, st.confirmed))
        val krx = issued(PriorStorageConfirmation.confirmRetainedSource(byKey.getValue(krxKey), DestinationLocator.Journal(krxKey), st.confirmed))
        val bothAfter = FenceV1("A", N0.newUser, N0.newKrx)
        for ((t, key, rows) in listOf(Triple(user, journalKey, listOf(N0.nullUser, N0.companionUser)),
            Triple(krx, krxKey, listOf(N0.nullKrx, N0.companionKrx)))) {
            assertEquals(DestinationLocator.Journal(key), t.locator)
            val expectedScope = when (key) {
                journalKey -> RetirementScope("s", N0.before, bothAfter, journalKey)
                krxKey -> RetirementScope("c", N0.before, bothAfter, krxKey)
                else -> error("unexpected journal key: $key")
            }
            assertEquals(expectedScope, t.scope)
            assertEquals(bothAfter, t.observedAfter)
            assertEquals(rows.map { settledRow(st.s, it).toPayloadEntry() }, t.settledSeals.map { it.toPayloadEntry() })
        }
    }

    // ═══ N15: completion with settled seals ════════════════════════════════════════════════════════════════════════════
    /** The caller's REQUEST consumption: exactly the n-demand row removed. */
    private suspend fun consumeRequest(st: Settled) = controlTestTimeout("consume request") {
        o.data.updateData { raw ->
            val rows = N0.read(raw).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
            val kept = rows.filterNot { (it.text("id") as? FieldRead.Present)?.value == st.s.demandId }
            assertEquals("fixture: exactly one REQUEST consumed", rows.size - 1, kept.size)
            raw.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*kept.toTypedArray()) }.toPreferences()
        }
    }
    private suspend fun purged(): ControlRecordRead.Supported {
        val pending = controlTestTimeout("load") { o.owner.load() }.pendingPurges
        assertEquals("fixture: the one shared null-epoch purge", 1, pending.size)
        controlTestTimeout("complete purge") { o.owner.completePurges(pending) }
        return N0.read(o.raw())
    }
    private fun completedAll(st: Settled, latest: ControlRecordRead) = assessG05(st.c, st.a1, CompletionHandoff(st.a1.commandBinding,
        ResponsibilityOwner(st.c.ownerTrackingLifetimeId, "owner-1"), st.required.map {
            SlotHandoff(it.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(it.key.subject), emptyList())) }),
        TerminationClosures.of(st.c), latest, now)
    private fun conflict(st: Settled, k: RequiredObligationKey, actual: G05Location) = G05Failure(G05Id.COMPLETED_CONFLICT, k,
        G05Location.Fixed((st.slot(k).requirement as SlotRequirement.Required).fixedSources.first().location), G05Location.Submitted(st.at(k)), actual)
    private fun sealAt(latest: ControlRecordRead.Supported, id: String): G05Location.ActualPayload {
        val (kind, entry) = latest.locations(id).single()
        return G05Location.ActualPayload(kind, latest.arrays.getValue(kind).entries.indexOfFirst { it === entry })
    }
    private fun key(st: Settled, component: ObligationComponent, branch: LandingBranch, sealId: String? = null) = st.required.single {
        it.key.component == component && it.key.branch == branch && (sealId == null || when (val subject = it.key.subject) {
            is ObligationSubject.Seal -> subject.id == sealId
            is ObligationSubject.Journal -> subject.sourceSealId == sealId
            else -> false
        }) }.key
    private fun rewritten(latest: ControlRecordRead.Supported, change: (SealV1, ControlNode) -> ControlNode) =
        N0.read(latest.original.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = sealRows(latest.original, change) }.toPreferences())

    @Test fun C01_afterRequestConsumedAndPurge_allTwelveSlotsComplete_accepted() = runReleaseTest {
        val st = settle(); assertEquals("fixture: twelve slots", 12, st.required.size)
        consumeRequest(st)
        assertEquals(G05Result.Accepted, completedAll(st, purged()))
    }

    @Test fun C02_companionSealActive_conflictsOnItsSealSlotsAndBothRetirements() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = rewritten(purged()) { v, n -> if (v.id == "us") node(N0.companionUser) else n }
        val a = sealAt(latest, "us")
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "us"), a),
            conflict(st, key(st, ObligationComponent.SEAL, N, "us"), a),
            conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    /** L demands the exact witness; N accepts another operation id with the right shape, operation, fences and journal. */
    @Test fun C03_otherOperationIdOnTheNullSeal_conflictsOnlyOnTheLSlots() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = rewritten(purged()) { v, n ->
            if (v.id == "s") NamespaceSettlementFixtures.withWitness(N0.nullUser, N0.witness(st.s, v).copy(operationId = "other-op")) else n }
        val a = sealAt(latest, "s")
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "s"), a),
            conflict(st, st.retirement(L).key, a)))
    }

    @Test fun C04_journalStillPending_conflictsOnJournalAndRetirementSlots_notOnSettledSeals() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = N0.read(o.raw())
        val j = G05Location.ActualJournal(0)
        rejected(completedAll(st, latest), listOf(
            conflict(st, key(st, ObligationComponent.JOURNAL, L, "s"), j), conflict(st, key(st, ObligationComponent.JOURNAL, N, "s"), j),
            conflict(st, key(st, ObligationComponent.JOURNAL, L, "us"), j), conflict(st, key(st, ObligationComponent.JOURNAL, N, "us"), j),
            conflict(st, st.retirement(L).key, j), conflict(st, st.retirement(N).key, j)))
    }

    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(expected, (r as G05Result.Rejected).failures)
    }

    // ═══ N15: contract review r1 additions ════════════════════════════════════════════════════════════════════════════
    /** The companion's key changed in place: its seal slots and both retirements conflict at its row. */
    @Test fun C05_companionKeyChanged_conflictsOnItsSealSlotsAndBothRetirements() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = rewritten(purged()) { v, n ->
            if (v.id == "us") NamespaceSettlementFixtures.withWitness(N0.companionUser.replace("\"u2\"", "\"u9\""), N0.witness(st.s, v)) else n }
        val a = sealAt(latest, "us")
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "us"), a),
            conflict(st, key(st, ObligationComponent.SEAL, N, "us"), a), conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    /** An opaque SEAL row conflicts with every slot that scans the SEAL array, at its position. */
    @Test fun C06_opaqueCompanionRow_conflictsOnEverySealScanningSlot() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = rewritten(purged()) { v, n -> if (v.id == "us") node("""{"id":"us","kind":"NAMESPACE"}""") else n }
        assertTrue("fixture: the companion row is opaque", latest.hasUninterpretable)
        val a = G05Location.ActualPayload(ControlKind.SEAL, 1)
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "s"), a),
            conflict(st, key(st, ObligationComponent.SEAL, N, "s"), a), conflict(st, key(st, ObligationComponent.SEAL, L, "us"), a),
            conflict(st, key(st, ObligationComponent.SEAL, N, "us"), a), conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    /** The settled companion stored twice: duplicate ids are opaque to the reader, so every SEAL-scanning slot conflicts at both. */
    @Test fun C07_duplicateCompanionRows_opaque_conflictOnEverySealScanningSlotAtEachCopy() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val base = purged()
        val rows = base.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        val us = rows.single { (it.text("id") as FieldRead.Present).value == "us" }
        val latest = N0.read(base.original.toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*(rows + us).toTypedArray()) }.toPreferences())
        assertTrue("fixture: duplicate ids are opaque", latest.hasUninterpretable)
        val first = G05Location.ActualPayload(ControlKind.SEAL, 1); val second = G05Location.ActualPayload(ControlKind.SEAL, 2)
        rejected(completedAll(st, latest), listOf(key(st, ObligationComponent.SEAL, L, "s"), key(st, ObligationComponent.SEAL, N, "s"),
            key(st, ObligationComponent.SEAL, L, "us"), key(st, ObligationComponent.SEAL, N, "us"), st.retirement(L).key, st.retirement(N).key)
            .flatMap { listOf(conflict(st, it, first), conflict(st, it, second)) })
    }

    /** The companion's id now names a REQUEST row: the SEAL slots see no seal; the retirement conflicts at that row. */
    @Test fun C08_companionIdInAnotherPayloadKind_conflictsOnBothRetirementsAtThatRow() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val base = purged()
        val seals = base.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
            .filterNot { (it.text("id") as FieldRead.Present).value == "us" }
        val demands = base.arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original } +
            node("""{"id":"us","kind":"REQUEST","ownerUid":"A","binding":9,"originLifetimeId":"n-origin","raisedAt":1,"intent":"IF_STALE"}""")
        val latest = N0.read(base.original.toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*seals.toTypedArray())
            this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*demands.toTypedArray()) }.toPreferences())
        val a = sealAt(latest, "us")
        assertEquals("fixture: us is a DEMAND row now", ControlKind.DEMAND, a.kind)
        rejected(completedAll(st, latest), listOf(conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    /** A witness whose before, after or journal disagrees with the scope: L and N retirements both conflict (N is not lenient here). */
    @Test fun C09_nullSealWitnessWithWrongFenceOrJournal_conflictsOnSealLAndBothRetirements() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val base = purged()
        val w = N0.witness(st.s)
        for (bad in listOf(w.copy(before = FenceV1("A", "u0", "k2")), w.copy(after = FenceV1("A", "other-user", "k2")),
            w.copy(journal = JournalTargetV1(null, PurgeScope.USER, null)))) {
            val latest = rewritten(base) { v, n -> if (v.id == "s") NamespaceSettlementFixtures.withWitness(N0.nullUser, bad) else n }
            assertTrue("fixture: the changed witness is interpretable", !latest.hasUninterpretable)
            val a = sealAt(latest, "s")
            rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "s"), a),
                conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
        }
    }

    /** A witness of the wrong shape (an operation a settlement never records) makes the seal row opaque. */
    @Test fun C10_nullSealWitnessOfTheWrongShape_opaque_conflictOnEverySealScanningSlot() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val bad = N0.witness(st.s).copy(operation = com.jay.fxi.data.entitlements.StoreOp.LOAD)
        val latest = rewritten(purged()) { v, n -> if (v.id == "s") NamespaceSettlementFixtures.withWitness(N0.nullUser, bad) else n }
        assertTrue("fixture: the null seal row is opaque", latest.hasUninterpretable)
        val a = G05Location.ActualPayload(ControlKind.SEAL, 0)
        rejected(completedAll(st, latest), listOf(key(st, ObligationComponent.SEAL, L, "s"), key(st, ObligationComponent.SEAL, N, "s"),
            key(st, ObligationComponent.SEAL, L, "us"), key(st, ObligationComponent.SEAL, N, "us"), st.retirement(L).key, st.retirement(N).key)
            .map { conflict(st, it, a) })
    }

    // ═══ contract r4: battery r1 additions (tampered slots and records) ═════════════════════════════════════════════════
    private fun req(slot: RequiredSlot) = slot.requirement as SlotRequirement.Required
    private fun bound(slot: RequiredSlot) = req(slot).lowerBound as RequiredLowerBound.NamespaceRetirement
    private fun withSources(slot: RequiredSlot, change: (List<FixedSourceEvidence>) -> List<FixedSourceEvidence>) =
        slot.copy(requirement = req(slot).copy(fixedSources = change(req(slot).fixedSources)))
    private fun withScope(slot: RequiredSlot, scope: RetirementScope) =
        slot.copy(key = slot.key.copy(subject = scope), requirement = req(slot).copy(lowerBound = bound(slot).copy(scope = scope)))
    private fun targetSource(f: FixedSourceEvidence, index: Int) = f.location ==
        FixedInputLocation(FixedInputRoot.SETTLEMENT_TARGET, index, FixedInputFacet.SOURCE)
    private fun withInput(slot: RequiredSlot, input: CurrentNullSettlement) = withSources(slot) { sources -> sources.map {
        if (it.fact is FixedSourceFact.SettlementInput) it.copy(fact = FixedSourceFact.SettlementInput(input)) else it } }
    private fun subjectMismatch(slot: RequiredSlot, confirmed: ControlStoreResult.Confirmed, locator: DestinationLocator.Journal = journal) =
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, locator, confirmed), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)

    /** R01: the fixed writer input must sit at SETTLEMENT_INPUT/WHOLE. */
    @Test fun R01_settlementInputAtAnotherFacet_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        subjectMismatch(withSources(slot) { s -> s.map { if (it.fact is FixedSourceFact.SettlementInput)
            it.copy(location = FixedInputLocation(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.BEFORE)) else it } }, st.confirmed)
    }

    /** R02: subject and bound agree with each other but carry a before that is not the input's before. */
    @Test fun R02_scopeBeforeNotTheInputBefore_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        subjectMismatch(withScope(slot, bound(slot).scope.copy(before = FenceV1("A", "u0", "k2"))), st.confirmed)
    }

    /** R03: a CAPABILITY companion whose axis no NULL target rotates; slot, scope, locator and SOURCE all point at it. */
    @Test fun R03_axisNotRotatedByAnyNullTarget_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val input = N0.spec(companions = listOf(node(N0.companionKrx)))
        assertEquals("fixture: only USER rotates", setOf(PurgeScope.USER), input.axes)
        val krxKey = JournalTargetV1("A", PurgeScope.CAPABILITY, null)
        val ksAt = input.targets.indexOfFirst { it.seal.id == "ks" }
        val tampered = withSources(withScope(withInput(slot, input), RetirementScope("ks", N0.before, input.after, krxKey))) { s ->
            s.filterNot { it.location.root == FixedInputRoot.SETTLEMENT_TARGET } +
                FixedSourceEvidence(FixedInputLocation(FixedInputRoot.SETTLEMENT_TARGET, ksAt, FixedInputFacet.SOURCE),
                    FixedSourceFact.Node(ControlKind.SEAL, node(N0.companionKrx))) }
        subjectMismatch(tampered, st.confirmed, DestinationLocator.Journal(krxKey))
    }

    /** R04: subject, bound and locator consistently name a journal epoch u2 that a CURRENT_NULL retirement never carries. */
    @Test fun R04_consistentJournalEpoch_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val key = journalKey.copy(epoch = "u2")
        subjectMismatch(withScope(slot, bound(slot).scope.copy(journalKey = key)), st.confirmed, DestinationLocator.Journal(key))
    }

    /** R05: the NULL target listed twice in the input, with a SOURCE for each copy. */
    @Test fun R05_duplicateTargetIdInTheInput_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val input = N0.spec(nulls = listOf(node(N0.nullUser), node(N0.nullUser)))
        subjectMismatch(withSources(withInput(slot, input)) { s ->
            s.filterNot { it.location.root == FixedInputRoot.SETTLEMENT_TARGET } + (0..1).map {
                FixedSourceEvidence(FixedInputLocation(FixedInputRoot.SETTLEMENT_TARGET, it, FixedInputFacet.SOURCE),
                    FixedSourceFact.Node(ControlKind.SEAL, node(N0.nullUser))) } }, st.confirmed)
    }

    /** R06: the input's companion (and its SOURCE) already carries a settlement: not an unsettled writer input. */
    @Test fun R06_alreadySettledSealInTheInput_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val settledUs = settledRow(st.s, N0.companionUser)
        val input = N0.spec(companions = listOf(settledUs))
        val usAt = input.targets.indexOfFirst { it.seal.id == "us" }
        subjectMismatch(withSources(withInput(slot, input)) { s -> s.map {
            if (targetSource(it, usAt)) it.copy(fact = FixedSourceFact.Node(ControlKind.SEAL, settledUs)) else it } }, st.confirmed)
    }

    /** R07-R10: the SOURCE rows of the fixed input tampered one way at a time. */
    @Test fun R07_sourceRowsTampered_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val usAt = st.s.targets.indexOfFirst { it.seal.id == "us" }
        val variants = listOf<(List<FixedSourceEvidence>) -> List<FixedSourceEvidence>>(
            // R07 a SOURCE under the other writer root
            { s -> s + FixedSourceEvidence(FixedInputLocation(FixedInputRoot.ROTATION_TARGET, 0, FixedInputFacet.SOURCE),
                FixedSourceFact.Node(ControlKind.SEAL, node(N0.nullUser))) },
            // R08 one SOURCE more than the axis has seals
            { s -> s + FixedSourceEvidence(FixedInputLocation(FixedInputRoot.SETTLEMENT_TARGET, 2, FixedInputFacet.SOURCE),
                FixedSourceFact.Node(ControlKind.SEAL, node(N0.companionUser))) },
            // R09 the companion's SOURCE recorded under another payload kind
            { s -> s.map { if (targetSource(it, usAt)) it.copy(fact = FixedSourceFact.Node(ControlKind.DEMAND, node(N0.companionUser))) else it } },
            // R10 the companion's SOURCE text differs (epoch u9, same id)
            { s -> s.map { if (targetSource(it, usAt))
                it.copy(fact = FixedSourceFact.Node(ControlKind.SEAL, node(N0.companionUser.replace("\"u2\"", "\"u9\"")))) else it } })
        for (v in variants) subjectMismatch(withSources(slot, v), st.confirmed)
    }

    /** R11: only the key's subject differs from the bound scope. */
    @Test fun R11_subjectOnlyChanged_isSubjectOrBoundMismatch() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        subjectMismatch(slot.copy(key = slot.key.copy(subject = bound(slot).scope.copy(after = FenceV1("A", "other-user", "k2")))), st.confirmed)
    }

    /** R12: an ill-typed fence value in the observed record: no exact row, not a crash. */
    @Test fun R12_illTypedFenceValue_isNoExactRetainedRow() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        for (name in listOf(com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID.name,
            USER_EPOCH.name, com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH.name)) {
            val v = withSnapshot(st.confirmed) {
                remove(androidx.datastore.preferences.core.stringPreferencesKey(name))
                this[androidx.datastore.preferences.core.intPreferencesKey(name)] = 7 }
            rejected(PriorStorageConfirmation.confirmRetainedSource(slot, journal, v), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        }
    }

    /** R13: an unrelated opaque SEAL row (no readable id) beside the two settled seals: no exact row. */
    @Test fun R13_unrelatedOpaqueSealRow_isNoExactRetainedRow() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val v = withSnapshot(st.confirmed) {
            val rows = N0.read(this.toPreferences()).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*(rows + node("""{"kind":"NAMESPACE"}""")).toTypedArray()) }
        assertTrue("fixture: the extra row is opaque", v.snapshot.record.hasUninterpretable)
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, journal, v), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** R14: the settled companion's key changed in place while its witness stays exact: no exact row. */
    @Test fun R14_settledCompanionKeyChangedWithExactWitness_isNoExactRetainedRow() = runReleaseTest {
        val st = settle(); val slot = st.retirement(L)
        val v = withSnapshot(st.confirmed) { this[ControlStoreTestStorage.SEAL] = sealRows(this.toPreferences()) { s, n ->
            if (s.id == "us") NamespaceSettlementFixtures.withWitness(N0.companionUser.replace("\"u2\"", "\"u9\""), N0.witness(st.s, s)) else n } }
        assertTrue("fixture: still interpretable", !v.snapshot.record.hasUninterpretable)
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, journal, v), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** C11: an opaque SEAL row without a readable id conflicts with every SEAL-scanning slot at its position. */
    @Test fun C11_idlessOpaqueSealRow_conflictsOnEverySealScanningSlot() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val base = purged()
        val rows = base.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        val latest = N0.read(base.original.toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*(rows + node("""{"kind":"NAMESPACE"}""")).toTypedArray()) }.toPreferences())
        assertTrue("fixture: the extra row is opaque", latest.hasUninterpretable)
        val a = G05Location.ActualPayload(ControlKind.SEAL, 2)
        rejected(completedAll(st, latest), listOf(key(st, ObligationComponent.SEAL, L, "s"), key(st, ObligationComponent.SEAL, N, "s"),
            key(st, ObligationComponent.SEAL, L, "us"), key(st, ObligationComponent.SEAL, N, "us"), st.retirement(L).key, st.retirement(N).key)
            .map { conflict(st, it, a) })
    }

    /** C12: the submitted retirement L slot lost its companion SOURCE: its axis cannot be rebuilt (ActualMetadata). */
    @Test fun C12_retirementSlotWithoutRebuildableSources_conflictsAtActualMetadata() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = purged()
        val rl = st.retirement(L)
        val usAt = st.s.targets.indexOfFirst { it.seal.id == "us" }
        val tampered = withSources(rl) { s -> s.filterNot { targetSource(it, usAt) } }
        val a1 = st.a1.copy(orderedSlots = st.a1.orderedSlots.map { if (it.key == rl.key) tampered else it })
        val r = assessG05(st.c, a1, CompletionHandoff(a1.commandBinding, ResponsibilityOwner(st.c.ownerTrackingLifetimeId, "owner-1"),
            st.required.map { SlotHandoff(it.key, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(it.key.subject), emptyList())) }),
            TerminationClosures.of(st.c), latest, now)
        rejected(r, listOf(conflict(st, rl.key, G05Location.ActualMetadata)))
    }

    /** C13: the companion's witness records another operation: N checks the operation (unlike the operation id). */
    @Test fun C13_companionWitnessOtherOperation_conflictsOnItsSealLAndBothRetirements() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val latest = rewritten(purged()) { v, n -> if (v.id == "us")
            NamespaceSettlementFixtures.withWitness(N0.companionUser, N0.witness(st.s, v).copy(operation = com.jay.fxi.data.entitlements.StoreOp.LOAD)) else n }
        assertTrue("fixture: the companion witness is interpretable", !latest.hasUninterpretable)
        val a = sealAt(latest, "us")
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "us"), a),
            conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    /** C14: the NULL seal settled by a RETIRED_NULL (V2) witness instead: the retirement N rejects a witness of another version. */
    @Test fun C14_nullSealWithRetiredNullWitness_conflictsOnSealLAndBothRetirements() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val f = """{"ownerUid":"B","userAccessEpoch":"x","krxCapabilityEpoch":"y"}"""
        val v2 = node(N0.nullUser.dropLast(1) + ""","settlement":{"version":2,"kind":"RETIRED_NULL","operationId":"n-operation",""" +
            """"originLifetimeId":"n-origin","before":$f,"after":$f,"journal":{"ownerUid":"A","axis":"USER","epoch":null}}}""")
        val latest = rewritten(purged()) { v, n -> if (v.id == "s") v2 else n }
        assertTrue("fixture: the V2 witness is interpretable", !latest.hasUninterpretable)
        assertTrue("fixture: a RETIRED_NULL witness", (latest.locations("s").single().second as ControlEntryRead.Interpreted)
            .value.let { (it as SealV1).settlement is RetiredNullSettlementEvidenceV2 })
        val a = sealAt(latest, "s")
        rejected(completedAll(st, latest), listOf(conflict(st, key(st, ObligationComponent.SEAL, L, "s"), a),
            conflict(st, st.retirement(L).key, a), conflict(st, st.retirement(N).key, a)))
    }

    // ═══ contract r6: consensus r7 boundaries (contract review r5) ═════════════════════════════════════════════════════
    /** I07: a RECOVER_INTENT retirement (source is the intent, not a seal) is not a required source for this path either. */
    @Test fun I07_recoverIntentRetirement_isNotARequiredSource() = runReleaseTest {
        val H = HoldRecoveryFixtures
        val source = node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
        val op = "00000000-0000-0000-0000-000000000201"
        val plan = RecoverIntentPlan.prepare(RecoverIntentInput(source, FenceV1("A", "u", "k"), H.binding,
            HoldRecoveryClosure.AfterRestart(source, H.executor, "old-tracking", true, true)),
            RecoverIntentIds(op, "intent-request", RecoveryFreshEpochs(null, H.krxEpoch)), LifecycleOrderSource(LifetimeId("new-life"), 21))
        assertEquals("fixture: recipe prepares", null, plan.preparationProblem)
        val c = CommandRef(op, ControlCommandBody.Lifecycle(plan.descriptor()), OwnerTrackingLifetimeId.issue())
        val a1 = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)) as RequirementDerivation.Available
        settle()
        val retirements = a1.orderedSlots.filter { it.key.component == ObligationComponent.NAMESPACE_RETIREMENT }
        assertEquals("fixture: RECOVER_INTENT retirement L and N", listOf(L, N), retirements.map { it.key.branch })
        for (slot in retirements) {
            val key = bound(slot).scope.journalKey
            rejected(PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Journal(key), lockedRead()),
                RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
        }
    }

    /** I08: each issued confirmation is bound to the exact slot it was issued for. */
    @Test fun I08_issuedConfirmation_isBoundToItsOwnSlot() = runReleaseTest {
        val st = settle()
        for (branch in listOf(L, N)) {
            val slot = st.retirement(branch)
            val r = PriorStorageConfirmation.confirmRetainedSource(slot, journal, st.confirmed)
            issued(r)
            val binding = (r as RetainedSourceConfirmationResult.Issued).value.binding as ConfirmationBinding.RetainedSource
            assertEquals(slot, binding.slot)
        }
    }

    /** I09: the token keeps its own immutable copy of the settled rows. */
    @Test fun I09_retirementJournalToken_copiesSettledRowsImmutably() {
        val rows = mutableListOf(node(N0.nullUser), node(N0.companionUser))
        val t = RetainedDestinationTuple.RetirementJournal(journal, RetirementScope("s", N0.before, after, journalKey), after, rows)
        rows.clear()
        assertEquals(listOf(node(N0.nullUser), node(N0.companionUser)).map { it.toPayloadEntry() }, t.settledSeals.map { it.toPayloadEntry() })
        val refused = try { @Suppress("UNCHECKED_CAST") (t.settledSeals as MutableList<ControlNode>).add(node(N0.nullUser)); false }
            catch (e: UnsupportedOperationException) { true }
        assertTrue("token list must refuse changes", refused)
        assertEquals(2, t.settledSeals.size)
    }

    /** C15: after REQUEST consumption and purge, s absent, us absent, or both absent: every slot completes. */
    @Test fun C15_settledSealsAbsent_allTwelveSlotsComplete_accepted() = runReleaseTest {
        val st = settle(); consumeRequest(st)
        val base = purged()
        val rows = base.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
        for (gone in listOf(setOf("s"), setOf("us"), setOf("s", "us"))) {
            val kept = rows.filterNot { (it.text("id") as FieldRead.Present).value in gone }
            assertEquals("fixture: removed exactly $gone", rows.size - gone.size, kept.size)
            val latest = N0.read(base.original.toMutablePreferences().apply {
                this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*kept.toTypedArray()) }.toPreferences())
            assertEquals("absent $gone", G05Result.Accepted, completedAll(st, latest))
        }
    }
}
