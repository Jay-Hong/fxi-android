package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.spec
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.both
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.raw
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.before
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.executor
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.context
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.request
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.life
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.target
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.transition
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.command
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.witness
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.read
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.landed
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.decide
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.negative
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.blob
import org.junit.Assert.*
import org.junit.Test

import java.math.BigInteger

class CurrentNullRetryTest : CurrentNullOwnerBase() {
    private suspend fun setup(s: CurrentNullSettlement = spec()): CommandRef {
        seedN(s); val c = registerN(s)
        successN(executeN(c, context.copy(ownerUid = s.executor.ownerUid)), ConfirmedEffect.AppliedThisAttempt)
        return c
    }
    private suspend fun observeChanged(change: MutablePreferences.() -> Unit, journal: JournalObservation = JournalObservation.Present,
        demand: HandoverDemandObservation = HandoverDemandObservation.Present, s: CurrentNullSettlement = spec()) {
        val c = setup(s)
        controlTestTimeout("post-settlement external change") { o.data.edit(change) }
        val saved = disk(); val writes = o.storage.writes
        val result = successN(executeN(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(mapOf("s" to journal), result.handoverSettlement!!.journal)
        assertEquals(demand, result.handoverSettlement!!.demand)
        assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
        assertEquals(s.before, result.handoverSettlement!!.before)
    }
    @Test fun A04_journalAbsent() = runReleaseTest { observeChanged({ remove(PURGE_JOURNAL) }, journal = JournalObservation.Absent) }
    @Test fun A04_journalCovered() = runReleaseTest { observeChanged({ this[PURGE_JOURNAL] = "|||USER" }, journal = JournalObservation.Covered) }
    @Test fun A04_journalUninterpretable() = runReleaseTest { observeChanged({ this[PURGE_JOURNAL] = "future|format" }, journal = JournalObservation.Uninterpretable) }
    @Test fun A04_journalWrongType() = runReleaseTest { observeChanged({ this[intPreferencesKey(PURGE_JOURNAL.name)] = 4 }, journal = JournalObservation.Uninterpretable) }
    @Test fun A04_requestAbsent() = runReleaseTest { observeChanged({ this[ControlStoreTestStorage.DEMAND] = "[]" }, demand = HandoverDemandObservation.Absent) }
    @Test fun A04_requestChanged() = runReleaseTest { observeChanged({ this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("23", "24") }, demand = HandoverDemandObservation.Changed) }
    @Test fun A04_requestStrengthened() = runReleaseTest {
        val s = spec(target = node(CurrentNullFixtures.nullKrx.replace("\"c\"", "\"s\"")), demand = request.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        observeChanged({ this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("FORCE_ENTITLEMENTS", "FORCE_PREMIUM") }, demand = HandoverDemandObservation.Changed, s = s)
    }
    @Test fun A04_currentFenceCanAdvance() = runReleaseTest { observeChanged({ this[OWNER_UID] = "B"; this[USER_EPOCH] = "u3"; this[KRX_EPOCH] = "k3" }) }
    private suspend fun noSuccess(change: MutablePreferences.() -> Unit, reason: Any) {
        val c = setup(); controlTestTimeout("corrupt saved linkage") { o.data.edit(change) }
        tracking.markUnresolved(c); val saved = disk(); val writes = o.storage.writes
        val result = executeN(c, null); NamespaceSettlementFixtures.negative(result, reason)
        assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
        assertEquals(setOf(c), result.localUnresolvedCommands); assertFalse(c in tracking.executing)
    }
    @Test fun A05_appliedWithoutSealCannotConfirm() = runReleaseTest { noSuccess({ this[ControlStoreTestStorage.SEAL] = "[]" }, ConflictReason.TargetMissing) }
    @Test fun A05_appliedWithPreimageCannotReapply() = runReleaseTest { noSuccess({ this[ControlStoreTestStorage.SEAL] = "[${CurrentNullFixtures.nullUser}]" }, ConflictReason.TargetChanged) }
    @Test fun A06_observedAppliedLostBeforeConfirmedGate() = runReleaseTest { noSuccess({ this[evidenceKey] = "[]" }, RecoveryReason.CommandEvidenceLost) }
    @Test fun A01_ownerMismatchCannotConfirm() = runReleaseTest {
        val c = setup(); history(c).expectedApplied = null
        controlTestTimeout("evidence mismatch") { o.data.edit { it[evidenceKey] = it[evidenceKey]!!.replace("n-demand", "other") } }
        val saved = disk(); val writes = o.storage.writes
        NamespaceSettlementFixtures.negative(executeN(c), ConflictReason.CommandEvidenceMismatch)
        assertTrue(history(c).observedApplied.get()); assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
    }
    @Test fun A02_extraOwnOperationSeal() = runReleaseTest {
        noSuccess({ this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.dropLast(1) + "," +
            this[ControlStoreTestStorage.SEAL]!!.drop(1).replace("\"s\"", "\"extra\"") }, RecoveryReason.InconsistentSettlement)
    }
    @Test fun A02_otherOperationIsTargetChanged() = runReleaseTest { noSuccess({ this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("n-operation", "other") }, ConflictReason.TargetChanged) }
    @Test fun A13_confirmationAlsoRequiresAllObligations() = runReleaseTest { noSuccess({ this[ControlStoreTestStorage.HOLD] = "[{}]" }, RecoveryReason.UninterpretableObligations) }
    @Test fun A13_confirmationAlsoRequiresEmptyFence() = runReleaseTest { noSuccess({ this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }, RecoveryReason.UninterpretableMetadata) }
    private suspend fun discontinuity(replacement: Preferences, attempted: Boolean) {
        seedN(); val c = registerN(spec()); val source = disk()
        if (attempted) {
            o.storage.before = true
            assertTrue(executeN(c) is ControlStoreResult.Unconfirmed)
            assertEquals(BigInteger.ZERO, history(c).firstConfirmDiscontinuityCount)
        } else {
            NamespaceSettlementFixtures.negative(executeN(c, context.copy(signOutOpen = true)), ConflictReason.IdentityTransitionPending)
            assertNull(history(c).firstConfirmDiscontinuityCount)
        }
        controlTestTimeout("replace record") { o.data.updateData { replacement } }
        assertTrue(executeN(c) is ControlStoreResult.RecoveryRequired)
        assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
        controlTestTimeout("restore record") { o.data.updateData { source } }
        val writes = o.storage.writes
        if (attempted) {
            val result = executeN(c); NamespaceSettlementFixtures.negative(result, RecoveryReason.CommandEvidenceContinuityLost)
            assertEquals(setOf(c), result.localUnresolvedCommands); assertEquals(writes, o.storage.writes); assertEquals(source, disk())
        } else {
            successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
            assertEquals(BigInteger.ONE, history(c).firstConfirmDiscontinuityCount)
        }
        assertFalse(c in tracking.executing)
    }
    private fun schema1() = raw().toMutablePreferences().apply {
        this[ControlStoreTestStorage.SCHEMA] = 1; remove(evidenceKey); remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE))
    }
    @Test fun A07_schema1BreakAfterConfirm() = runReleaseTest { discontinuity(schema1(), true) }
    @Test fun A07_missingBreakAfterConfirm() = runReleaseTest { discontinuity(mutablePreferencesOf(), true) }
    @Test fun A07_unreadableBreakAfterConfirm() = runReleaseTest { discontinuity(raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "broken" }, true) }
    @Test fun A08_noConfirmBeforeBreakStillEligible() = runReleaseTest { discontinuity(mutablePreferencesOf(), false) }
    @Test fun A08_prepareAloneDoesNotLatchBreak() = runReleaseTest {
        seedN(); val c = registerN(spec()); tracking.observe(ControlRecordReader().read(mutablePreferencesOf()))
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(BigInteger.ONE, history(c).firstConfirmDiscontinuityCount)
    }
    @Test fun A09_matchingAppliedPrecedesContinuity() = runReleaseTest {
        seedN(); val c = registerN(spec()); o.storage.afterScope = true
        assertTrue(executeN(c) is ControlStoreResult.Unconfirmed)
        tracking.observe(ControlRecordReader().read(mutablePreferencesOf()))
        successN(executeN(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(BigInteger.ZERO, history(c).firstConfirmDiscontinuityCount)
    }
    @Test fun A09_confirmedWithoutObservedAppliedOnlyConfirmsWitness() = runReleaseTest {
        val c = setup(); history(c).observedApplied.set(false)
        controlTestTimeout("external evidence removal") { o.data.edit { it[evidenceKey] = "[]" } }
        tracking.observe(ControlRecordReader().read(mutablePreferencesOf()))
        val writes = o.storage.writes
        successN(executeN(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(writes, o.storage.writes); assertEquals("[]", disk()[evidenceKey])
    }
    @Test fun A10_facadeReusesTracker() = runReleaseTest {
        seedN(); val c = registerN(spec()); o.storage.before = true
        assertTrue(executeN(c) is ControlStoreResult.Unconfirmed)
        val t = history(c); val next = ControlRecordStore(o.owner)
        successN(controlTestTimeout("facade retry") { next.execute(c, context) }, ConfirmedEffect.AppliedThisAttempt)
        assertSame(t, history(c)); assertSame(tracking.lifetimeId, c.ownerTrackingLifetimeId)
    }
    @Test fun A17_otherWorkPreservedOnSuccess() = runReleaseTest { otherWork("success") }
    @Test fun A17_otherWorkPreservedOnRejection() = runReleaseTest { otherWork("rejection") }
    @Test fun A17_otherWorkPreservedOnFailure() = runReleaseTest { otherWork("failure") }
    private suspend fun otherWork(mode: String) {
        seedN(); val unresolved = add(); val pending = add()
        tracking.markUnresolved(unresolved); history(pending).confirmed.set(true)
        ControlReleaseFixtures.simulatePending(tracking, pending)
        val c = registerN(spec())
        if (mode == "failure") o.storage.before = true
        val result = executeN(c, if (mode == "rejection") context.copy(signOutOpen = true) else context)
        if (mode == "success") successN(result, ConfirmedEffect.AppliedThisAttempt)
        else if (mode == "rejection") NamespaceSettlementFixtures.negative(result, ConflictReason.IdentityTransitionPending)
        else assertTrue(result is ControlStoreResult.Unconfirmed)
        assertEquals("A17: other pending release must be retained", setOf(pending), result.localPendingReleases)
        assertEquals("A17: other unresolved command must be retained",
            if (mode == "failure") setOf(unresolved, c) else setOf(unresolved), result.localUnresolvedCommands)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, pending.lifecycleState)
        assertSame(pending, history(pending).command); assertFalse(c in tracking.executing)
    }
}
