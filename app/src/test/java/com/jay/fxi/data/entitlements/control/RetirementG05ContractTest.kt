package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4bA4b contract (6-4bA3 consensus r7 N14, G05 half) on a real CURRENT_NULL settlement (USER NULL seal s +
 * old-epoch companion us, one shared null-epoch journal J0 = A|||USER, the n-demand REQUEST). The REQUEST is consumed; the journal
 * is NOT purged, so the four JOURNAL slots and the two NAMESPACE_RETIREMENT slots are handed over DurablyOwned(Journal(J0)) with an
 * empty linkChain and each slot's own retained confirmation (the retirements' is the N14 RetirementJournal token), and the SEAL and
 * REQUEST slots are declared completed.
 *  - DESTINATION: J0 present in the latest journal.
 *  - LOWER_BOUND (retirement only): the latest after fence, no opaque SEAL row, and each fixed same-axis seal either absent or the
 *    unique SEAL row with its fixed immutable fields and the exact expected witness (L and N alike — the N14 witness is exact; only
 *    N15 completion is lenient on the operation id). Failure position: the fence (ActualMetadata) or the offending SEAL row.
 *  - CONFIRMATION: the token's slot and locator are the submitted ones, and the latest continues the issued observation: the same
 *    journal present, the same after fence, every remaining issued seal row equal by payload entry (removal allowed), no opaque
 *    SEAL row. An unrelated interpretable SEAL row (another id) neither breaks continuity nor the lower bound.
 * A latest fence or seal defect is reported both as LOWER_BOUND and as CONFIRMATION (the existing retained Payload precedent);
 * a token defect is CONFIRMATION only. Fixed(first fixed source) is SETTLEMENT_INPUT/WHOLE for all twelve slots.
 * The implementation thread reads but does not edit this file.
 */
class RetirementG05ContractTest : CurrentNullOwnerBase() {
    private val N0 = CurrentNullFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)
    private val j0 = JournalTargetV1("A", PurgeScope.USER, null)
    private val journal = DestinationLocator.Journal(j0)

    private class Prepared(val c: CommandRef, val a1: RequirementDerivation.Available, val tokens: Map<RequiredObligationKey, PriorStorageConfirmation>) {
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val owned = setOf(ObligationComponent.JOURNAL, ObligationComponent.NAMESPACE_RETIREMENT)
        fun k(i: Int) = required[i].key
    }

    /** Settle, issue the six retained confirmations from the Confirmed snapshot, then consume the REQUEST (no purge). */
    private suspend fun prepare(): Prepared {
        val s = N0.spec(companions = listOf(node(N0.companionUser)))
        seedN(s); val c = registerN(s)
        val confirmed = successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        val a1 = deriveRequiredObligations(RequirementInput.Settlement(c, c.body as ControlCommandBody.Handover)) as RequirementDerivation.Available
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        assertEquals("fixture: slot order", listOf(
            ObligationComponent.SEAL to L, ObligationComponent.SEAL to N, ObligationComponent.JOURNAL to L, ObligationComponent.JOURNAL to N,
            ObligationComponent.SEAL to L, ObligationComponent.SEAL to N, ObligationComponent.JOURNAL to L, ObligationComponent.JOURNAL to N,
            ObligationComponent.NAMESPACE_RETIREMENT to L, ObligationComponent.NAMESPACE_RETIREMENT to N,
            ObligationComponent.REQUEST to L, ObligationComponent.REQUEST to N), required.map { it.key.component to it.key.branch })
        val tokens = required.filter { it.key.component == ObligationComponent.JOURNAL || it.key.component == ObligationComponent.NAMESPACE_RETIREMENT }
            .associate { slot ->
                val r = PriorStorageConfirmation.confirmRetainedSource(slot, journal, confirmed)
                assertTrue("fixture: issued for ${slot.key}, got $r", r is RetainedSourceConfirmationResult.Issued)
                slot.key to (r as RetainedSourceConfirmationResult.Issued).value
            }
        controlTestTimeout("consume request") {
            o.data.updateData { raw ->
                val rows = N0.read(raw).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).original }
                val kept = rows.filterNot { (it.text("id") as? FieldRead.Present)?.value == s.demandId }
                assertEquals("fixture: exactly one REQUEST consumed", rows.size - 1, kept.size)
                raw.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = NamespaceSettlementFixtures.jsonArray(*kept.toTypedArray()) }.toPreferences()
            }
        }
        return Prepared(c, a1, tokens)
    }
    private suspend fun latest(edit: androidx.datastore.preferences.core.MutablePreferences.() -> Unit = {}) =
        N0.read(o.raw().toMutablePreferences().apply(edit).toPreferences())
    private fun sealRows(p: androidx.datastore.preferences.core.Preferences) =
        N0.read(p).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
    private fun androidx.datastore.preferences.core.MutablePreferences.seals(rows: List<ControlNode>) {
        this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*rows.toTypedArray())
    }
    private fun id(n: ControlNode) = (n.text("id") as FieldRead.Present).value

    private fun handoff(p: Prepared, override: Map<Int, HandoffDisposition> = emptyMap(), omit: Set<Int> = emptySet()) =
        CompletionHandoff(p.a1.commandBinding, ResponsibilityOwner(p.c.ownerTrackingLifetimeId, "owner-1"),
            p.required.withIndex().filter { it.index !in omit }.map { (i, slot) ->
                SlotHandoff(slot.key, override[i] ?: if (slot.key.component in p.owned)
                    HandoffDisposition.DurablyOwned(journal, emptyList(), p.tokens.getValue(slot.key))
                else HandoffDisposition.CompletedAndConsumed(ComponentCompletion(slot.key.subject), emptyList()))
            })
    private fun assess(p: Prepared, latest: ControlRecordRead, override: Map<Int, HandoffDisposition> = emptyMap(), omit: Set<Int> = emptySet()) =
        assessG05(p.c, p.a1, handoff(p, override, omit), TerminationClosures.of(p.c), latest, now)
    private val fixed = G05Location.Fixed(FixedInputLocation(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE))
    private fun f(p: Prepared, id: G05Id, i: Int, actual: G05Location?) = G05Failure(id, p.k(i), fixed, G05Location.Submitted(i), actual)
    private fun fs(p: Prepared, id: G05Id, indices: List<Int>, actual: G05Location?) = indices.map { f(p, id, it, actual) }
    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(expected, (r as G05Result.Rejected).failures)
    }
    private val jSlots = listOf(2, 3, 6, 7)
    private val retirements = listOf(8, 9)
    private val S0 = G05Location.ActualPayload(ControlKind.SEAL, 0)
    private val S1 = G05Location.ActualPayload(ControlKind.SEAL, 1)
    private val S2 = G05Location.ActualPayload(ControlKind.SEAL, 2)

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P01_liveJournal_retirementsOwnedWithN14Tokens_accepted() = runReleaseTest {
        val p = prepare()
        assertEquals(G05Result.Accepted, assess(p, latest()))
    }

    /** Removal of an issued seal after issuance is allowed: s gone, us gone, both gone. */
    @Test fun P02_issuedSealsRemovedAfterIssuance_accepted() = runReleaseTest {
        val p = prepare()
        val rows = sealRows(o.raw())
        for (gone in listOf(setOf("s"), setOf("us"), setOf("s", "us"))) {
            val l = latest { seals(rows.filterNot { id(it) in gone }) }
            assertEquals("fixture: removed $gone", rows.size - gone.size, l.arrays.getValue(ControlKind.SEAL).entries.size)
            assertEquals("gone $gone", G05Result.Accepted, assess(p, l))
        }
    }

    /** An unrelated interpretable SEAL row (another id and owner) does not break continuity or the lower bound. */
    @Test fun P03_unrelatedInterpretableSealRow_accepted() = runReleaseTest {
        val p = prepare()
        val l = latest { seals(sealRows(this.toPreferences()) + node("""{"id":"zz","kind":"NAMESPACE","ownerUid":"B","axis":"USER","epoch":"e1"}""")) }
        assertTrue("fixture: still interpretable", !l.hasUninterpretable)
        assertEquals(G05Result.Accepted, assess(p, l))
    }

    /** Another valid journal line beside J0: destination is J0's presence, not the whole journal's identity. */
    @Test fun P04_otherJournalLineBesideJ0_accepted() = runReleaseTest {
        val p = prepare()
        val l = latest { this[PURGE_JOURNAL] = checkNotNull(this[PURGE_JOURNAL]) + "\nB|x||USER" }
        assertEquals("fixture: two journal lines", 2, checkNotNull(NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(l.original)).size)
        assertEquals(G05Result.Accepted, assess(p, l))
    }

    /** A new same-axis interpretable seal under another id: the lower bound covers only the fixed original ids. */
    @Test fun P05_newSameAxisSealUnderAnotherId_accepted() = runReleaseTest {
        val p = prepare()
        val l = latest { seals(sealRows(this.toPreferences()) + node("""{"id":"s2","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"u7"}""")) }
        assertTrue("fixture: still interpretable", !l.hasUninterpretable)
        assertEquals(G05Result.Accepted, assess(p, l))
    }

    // ═══ negative ══════════════════════════════════════════════════════════════════════════════════════════════════════
    /** (a) Only an old non-null journal remains: all six journal-owned slots lose destination and confirmation. */
    @Test fun N01_onlyOldNonNullJournal_destinationAndConfirmationOnSixSlots() = runReleaseTest {
        val p = prepare()
        val owned = jSlots + retirements
        rejected(assess(p, latest { this[PURGE_JOURNAL] = "A|u2||USER" }),
            fs(p, G05Id.DESTINATION, owned.sorted(), null) + fs(p, G05Id.CONFIRMATION, owned.sorted(), null))
    }

    /** (b) The companion's J slots omitted: coverage only. */
    @Test fun N02_companionJournalSlotsOmitted_coverageLAndN() = runReleaseTest {
        val p = prepare()
        rejected(assess(p, latest(), omit = setOf(6, 7)), listOf(
            G05Failure(G05Id.COVERAGE_L, p.k(6), fixed, null, null), G05Failure(G05Id.COVERAGE_N, p.k(7), fixed, null, null)))
    }

    @Test fun N03_afterFenceChanged_retirementsConfirmationAndLowerBoundAtMetadata() = runReleaseTest {
        val p = prepare()
        rejected(assess(p, latest { this[USER_EPOCH] = "u3" }),
            fs(p, G05Id.CONFIRMATION, retirements, G05Location.ActualMetadata) + fs(p, G05Id.LOWER_BOUND, retirements, G05Location.ActualMetadata))
    }

    @Test fun N04_companionSealActiveAgain_retirementsAtItsRow_andItsSealCompletionsConflict() = runReleaseTest {
        val p = prepare()
        val l = latest { seals(sealRows(this.toPreferences()).map { if (id(it) == "us") node(N0.companionUser) else it }) }
        rejected(assess(p, l), fs(p, G05Id.CONFIRMATION, retirements, S1) + fs(p, G05Id.LOWER_BOUND, retirements, S1) +
            fs(p, G05Id.COMPLETED_CONFLICT, listOf(4, 5), S1))
    }

    /** Another operation id on s: the retirement N is exact here (N14), unlike the SEAL N completion. */
    @Test fun N05_nullSealWitnessOtherOperationId_bothRetirementsExact_sealLCompletionConflicts() = runReleaseTest {
        val p = prepare()
        val l = latest { seals(sealRows(this.toPreferences()).map {
            if (id(it) == "s") NamespaceSettlementFixtures.withWitness(N0.nullUser, N0.witness(N0.spec(companions = listOf(node(N0.companionUser))))
                .copy(operationId = "other-op")) else it }) }
        assertTrue("fixture: still interpretable", !l.hasUninterpretable)
        rejected(assess(p, l), fs(p, G05Id.CONFIRMATION, retirements, S0) + fs(p, G05Id.LOWER_BOUND, retirements, S0) +
            fs(p, G05Id.COMPLETED_CONFLICT, listOf(0), S0))
    }

    @Test fun N06_idlessOpaqueSealRow_retirementsAtIt_andSealCompletionsConflict() = runReleaseTest {
        val p = prepare()
        val l = latest { seals(sealRows(this.toPreferences()) + node("""{"kind":"NAMESPACE"}""")) }
        assertTrue("fixture: the extra row is opaque", l.hasUninterpretable)
        rejected(assess(p, l), fs(p, G05Id.CONFIRMATION, retirements, S2) + fs(p, G05Id.LOWER_BOUND, retirements, S2) +
            fs(p, G05Id.COMPLETED_CONFLICT, listOf(0, 1, 4, 5), S2))
    }

    /** The retirement L submitted with the retirement N's token: confirmation only. */
    @Test fun N07_retirementLWithTheOtherSlotsToken_confirmationOnly() = runReleaseTest {
        val p = prepare()
        rejected(assess(p, latest(), override = mapOf(8 to HandoffDisposition.DurablyOwned(journal, emptyList(), p.tokens.getValue(p.k(9))))),
            listOf(f(p, G05Id.CONFIRMATION, 8, null)))
    }

    /** The retirement L destination names another journal while the token stays J0's. */
    @Test fun N08_retirementLDestinationOtherJournal_destinationAndConfirmation() = runReleaseTest {
        val p = prepare()
        val other = DestinationLocator.Journal(JournalTargetV1("A", PurgeScope.USER, "u2"))
        rejected(assess(p, latest(), override = mapOf(8 to HandoffDisposition.DurablyOwned(other, emptyList(), p.tokens.getValue(p.k(8))))),
            listOf(f(p, G05Id.DESTINATION, 8, null), f(p, G05Id.CONFIRMATION, 8, null)))
    }

    /** The settled companion's key changed in place while its witness stays exact: the lower bound checks the immutable fields too. */
    @Test fun N09_companionKeyChangedWithExactWitness_retirementsAtItsRow_andItsSealCompletionsConflict() = runReleaseTest {
        val p = prepare()
        val s = N0.spec(companions = listOf(node(N0.companionUser)))
        val l = latest { seals(sealRows(this.toPreferences()).map { if (id(it) == "us")
            NamespaceSettlementFixtures.withWitness(N0.companionUser.replace("\"u2\"", "\"u9\""), N0.witness(s,
                (ControlObligations.read(ControlKind.SEAL, node(N0.companionUser)) as ControlEntryRead.Interpreted).value as SealV1)) else it }) }
        assertTrue("fixture: still interpretable", !l.hasUninterpretable)
        rejected(assess(p, l), fs(p, G05Id.CONFIRMATION, retirements, S1) + fs(p, G05Id.LOWER_BOUND, retirements, S1) +
            fs(p, G05Id.COMPLETED_CONFLICT, listOf(4, 5), S1))
    }
}
