package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.EXTRA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.HOLD
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.RECOVERY
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.life
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.fence
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demand
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.operation
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demandId
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.newUser
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.newKrx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.command
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.confirmed
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.settled
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.replace
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NamespaceSettlementIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "rotation.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun addition(o: ControlStoreTestStorage, json: String): CommandRef = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
        literal(json); set("id", ControlScalar.Text(id))
    })
    private fun failed(result: ControlStoreResult, command: CommandRef, message: String) {
        assertEquals(ControlStoreResult.Unconfirmed::class.java, result.javaClass)
        result as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, result.reason)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, result.phase)
        assertEquals(IOException::class.java, result.failure?.javaClass)
        assertEquals(message, result.failure?.message)
        assertEquals(setOf(command), result.localUnresolvedCommands)
    }

    @Test fun userRotationPreservesEveryIndependentObligationAndRawKey() = runBlocking { normal(setOf(PurgeScope.USER)) }
    @Test fun capabilityRotationPreservesEveryIndependentObligationAndRawKey() = runBlocking { normal(setOf(PurgeScope.CAPABILITY)) }
    @Test fun bothAxesRotateInOneWriteWithOneDemandAndTwoWitnesses() = runBlocking { normal(PurgeScope.entries.toSet()) }

    private suspend fun normal(axes: Set<PurgeScope>) {
        val targets = axes.map { node(if (it == PurgeScope.USER) user else krx) }
        val spec = input(targets = targets, request = demand.copy(intent = if (PurgeScope.USER in axes) RefreshIntent.FORCE_PREMIUM else RefreshIntent.FORCE_ENTITLEMENTS))
        val sibling = user.replace("\"s\"", "\"older\"").replace("\"u\"", "\"previous-u\"")
        val opaque = "{\"id\":\"future\",\"counter\":1e400,\"negativeZero\":-0}"
        val original = raw(seals = "[${targets.joinToString(",") { it.toPayloadEntry().fields.toString() }},$sibling,$opaque]",
            requests = "[${ControlObligationFixtures.guard},${ControlObligationFixtures.request}]").toMutablePreferences().apply {
            this[HOLD] = "[  ${ControlObligationFixtures.hold}  ]"
            this[RECOVERY] = "[ ${ControlObligationFixtures.recovery} ]"
            this[EXTRA] = "unchanged\n한글"
            this[PURGE_JOURNAL] = "B|previous||USER\n|||CAPABILITY,USER"
        }
        val o = open(); o.data.updateData { original }
        val c = command(o, spec); val writes = o.storage.writes
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(writes + 1, o.storage.writes)
        val suffix = when (axes) {
            setOf(PurgeScope.USER) -> "A|u||USER"
            setOf(PurgeScope.CAPABILITY) -> "A||k|CAPABILITY"
            else -> "A|u|k|CAPABILITY,USER"
        }
        val expected = original.toMutablePreferences().apply {
            if (PurgeScope.USER in axes) { this[USER_EPOCH] = newUser; this[MAY_CONTAIN_PREMIUM] = false }
            if (PurgeScope.CAPABILITY in axes) { this[KRX_EPOCH] = newKrx; this[MAY_CONTAIN_KRX] = false }
            this[PURGE_JOURNAL] = original[PURGE_JOURNAL] + "\n" + suffix
            this[SEAL] = "[${targets.indices.joinToString(",") { withWitness(targets[it].toPayloadEntry().fields.toString(), NamespaceSettlementOracle.witness(spec.seals[it].key.axis, axes.size == 2)).toPayloadEntry().fields.toString() }},$sibling,$opaque]"
            this[DEMAND] = "[${ControlObligationFixtures.guard},${ControlObligationFixtures.request},{\"id\":\"$demandId\",\"kind\":\"REQUEST\",\"ownerUid\":\"A\",\"binding\":3,\"originLifetimeId\":\"life\",\"raisedAt\":7,\"intent\":\"${spec.demand.intent}\"}]"
        }
        expected[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] =
            """[{"version":2,"commandId":"$operation","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"ROTATION","sealIds":[${spec.seals.joinToString(",") { "\"${it.id}\"" }}],"demandId":"$demandId"}]"""
        assertEquals(expected, result.snapshot.record.original)
        assertEquals(expected, disk())
        val receipt = result.settlement!!
        assertEquals(operation, receipt.operationId); assertEquals(life, receipt.originLifetimeId)
        assertEquals(fence, receipt.before); assertEquals(NamespaceSettlementOracle.witness(axes.first(), axes.size == 2).after, receipt.after)
        assertEquals(spec.seals.associate { it.id to NamespaceSettlementOracle.witness(it.key.axis, axes.size == 2) }, receipt.witnesses)
        assertEquals(spec.seals.associate { it.id to JournalObservation.Present }, receipt.journal)
        assertEquals(demandId, receipt.demandId); assertEquals(DemandObservation.Present, receipt.demand)
        assertEquals(listOf((ControlObligations.read(ControlKind.SEAL, node(sibling)) as ControlEntryRead.Interpreted).value), receipt.remainingSeals)
        assertTrue(receipt.hasUninterpretable)
        val repeat = confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(expected, repeat.snapshot.record.original); assertEquals(writes + 1, o.storage.writes)
        assertEquals(RecordTransactionEvidence.LockedFileRead, repeat.proof.storage)
        assertEquals(expected[PURGE_JOURNAL], o.raw()[PURGE_JOURNAL])
        assertEquals(3, o.owner.load().pendingPurges.size) // Existing wildcard did not absorb the new entry.
    }

    @Test fun preparationIssuesIndependentUuidsOnceAndUnlandedRetryKeepsThem() = runBlocking {
        val o = open(); o.data.updateData { raw() }
        var next = 0L
        val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++next) })
        val targets = mutableListOf(node(user))
        val c = store.prepareRotation(targets, fence, life, demand); targets.clear()
        val spec = (c.body as ControlCommandBody.RotateAndSettle).input
        assertEquals(3L, next); assertEquals(operation, spec.operationId); assertEquals(demandId, spec.demandId)
        assertEquals(newUser, spec.newUserEpoch); assertNull(spec.newKrxEpoch)
        assertEquals(1, spec.targets.size); assertNull(store.checkpoint(c))
        val before = disk()
        o.storage.before = true
        failed(store.execute(c, context), c, "before write block")
        assertEquals(before, disk()); assertFalse(ControlCommandTracking.forOwner(o.owner).executing.contains(c))
        val result = confirmed(store.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(3L, next); assertEquals(newUser, result.snapshot.record.original[USER_EPOCH])
        assertEquals(demandId, result.settlement!!.demandId); assertEquals(operation, result.settlement!!.operationId)
        assertEquals(1L, result.snapshot.record.original[BARRIER])
    }

    @Test fun completedWriteScopeFailureLeavesAllEffectsOnDiskAndRetryOnlyConfirms() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true
        failed(o.control.execute(c, context), c, "after completed write scope")
        val landed = disk(); assertEquals(newUser, landed[USER_EPOCH]); assertNotEquals("[]", landed[DEMAND])
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(landed.toMutablePreferences().apply { this[BARRIER] = 1L }, result.snapshot.record.original)
        assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
    }

    @Test fun cacheOnlyFailureCannotBeMistakenForLandedSettlement() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        val before = disk(); o.storage.after = true
        failed(o.control.execute(c, context), c, "after write block")
        assertEquals(newUser, o.raw()[USER_EPOCH]); assertEquals(before, disk())
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(newUser, disk()[USER_EPOCH]); assertEquals(1L, result.snapshot.record.original[BARRIER])
    }

    @Test fun cancellationAfterCompletedScopePropagatesAndRetryConfirmsLandedWitness() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        val pause = ControlStoreTestStorage.Pause(); o.storage.pauseAfterScope = pause
        val caught = CompletableDeferred<Throwable>()
        val job = launch {
            try { o.control.execute(c, context); caught.complete(AssertionError("returned after cancellation")) }
            catch (failure: CancellationException) { caught.complete(failure); throw failure }
        }
        withTimeout(10_000) { pause.reached.await() }
        try {
            assertEquals(newUser, disk()[USER_EPOCH]); assertFalse(job.isCompleted)
            job.cancel(CancellationException("cancel after completed write scope"))
        } finally { pause.release.complete(Unit) }
        job.join()
        val failure = caught.await()
        assertEquals(CancellationException::class.java, failure.javaClass)
        assertEquals("cancel after completed write scope", failure.message)
        val tracking = ControlCommandTracking.forOwner(o.owner)
        assertEquals(setOf(c), tracking.snapshot()); assertTrue(tracking.executing.isEmpty())
        confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        Unit
    }

    @Test fun journalCompletionAfterUnconfirmedLandingDoesNotInvalidateWitness() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true; failed(o.control.execute(c, context), c, "after completed write scope")
        o.owner.completePurges(o.owner.load().pendingPurges)
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        assertNull(result.snapshot.record.original[PURGE_JOURNAL])
        assertEquals(mapOf("s" to JournalObservation.Absent), result.settlement!!.journal)
    }

    @Test fun laterDemandRemovalDoesNotInvalidateWitnessOrClaimConsumption() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true; failed(o.control.execute(c, context), c, "after completed write scope")
        // Simulate the atomic saved settlement of a future demand consumer, deliberately unwired here.
        o.owner.transactRecord { RecordTransactionDecision.Confirm(it.toMutablePreferences().apply { this[DEMAND] = "[]" }, Unit) }
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        assertEquals("[]", result.snapshot.record.original[DEMAND]); assertEquals(DemandObservation.Absent, result.settlement!!.demand)
    }

    @Test fun laterRotationSignOutAndUnreadableCurrentHandoverDoNotBlockReconfirmation() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true; failed(o.control.execute(c, context), c, "after completed write scope")
        o.owner.beginRotation(true, true); o.owner.signOut()
        o.data.edit {
            it[PURGE_JOURNAL] = "new-format|not-readable"
            it[DEMAND] = "[{\"id\":\"$demandId\",\"future\":true}]"
            it[intPreferencesKey(TEARDOWN_OWED_FOR.name)] = 9
        }
        val before = o.raw()
        val result = confirmed(o.control.execute(c, context.copy(ownerUid = "B", binding = 99,
            originLifetimeId = LifetimeId("next-life"), signOutOpen = true, identityPersistencePending = true)), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(before.toMutablePreferences().apply { this[BARRIER] = (before[BARRIER] ?: 0L) + 1 }, result.snapshot.record.original)
        assertEquals(DemandObservation.Uninterpretable, result.settlement!!.demand)
        assertEquals(mapOf("s" to JournalObservation.Uninterpretable), result.settlement!!.journal)
    }

    @Test fun receiptReportsChangedDemandAndWildcardCoverageWithoutUsingEitherAsProof() = runBlocking {
        val o = open(); o.data.updateData { raw(seals = "[$krx]") }
        val spec = input(targets = listOf(node(krx)), request = demand.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        val c = command(o, spec); confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        o.data.edit {
            it[PURGE_JOURNAL] = "|||CAPABILITY"
            it[DEMAND] = it[DEMAND]!!.replace("FORCE_ENTITLEMENTS", "FORCE_PREMIUM").replace("\"raisedAt\":7", "\"raisedAt\":8")
        }
        val result = confirmed(o.control.execute(c, context), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(DemandObservation.Changed, result.settlement!!.demand)
        assertEquals(mapOf("c" to JournalObservation.Covered), result.settlement!!.journal)
    }

    @Test fun previousOwnerConfirmsWitnessWithoutCheckpointButCannotApplyMissingEffects() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true; failed(o.control.execute(c, context), c, "after completed write scope")
        val fresh = open(); val before = fresh.raw(); val writes = fresh.storage.writes
        val unknown = fresh.control.execute(c, context) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.HistoryUnavailable, unknown.reason); assertEquals(setOf(c), unknown.localUnresolvedCommands)
        val result = confirmed(fresh.control.confirmPrevious(c), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(before, result.snapshot.record.original); assertEquals(writes, fresh.storage.writes)
        fresh.data.edit { it[SEAL] = "[]" }
        val empty = fresh.raw(); val writes2 = fresh.storage.writes
        negative(fresh.control.confirmPrevious(c), ConflictReason.TargetMissing)
        assertEquals(empty, fresh.raw()); assertEquals(writes2, fresh.storage.writes)
        fresh.data.updateData { raw() }
        negative(fresh.control.confirmPrevious(c), ConflictReason.TargetMissing)
        assertEquals(raw(), fresh.raw())
    }

    @Test fun confirmedCommandNeverReappliesAfterCompletePreimageRestoration() = runBlocking {
        val o = open(); val before = raw(); o.data.updateData { before }; val c = command(o, input())
        confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        o.data.updateData { before }; val writes = o.storage.writes
        val result = o.control.execute(c, context)
        negative(result, RecoveryReason.CommandEvidenceLost); assertTrue(result.localUnresolvedCommands.isEmpty())
        assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
    }

    @Test fun differentOperationsCompeteForOneSealAndFacadeRecreationRetainsHistory() = runBlocking {
        val o = open(); o.data.updateData { raw() }
        val first = o.control.prepareRotation(listOf(node(user)), fence, life, demand)
        val second = o.control.prepareRotation(listOf(node(user)), fence, life, demand)
        val a = async { o.control.execute(first, context) }; val b = async { o.control.execute(second, context) }
        val results = listOf(a.await(), b.await())
        val won = results.filterIsInstance<ControlStoreResult.Confirmed>().single()
        assertEquals(ConfirmedEffect.AppliedThisAttempt, won.effect)
        val lost = results.filterIsInstance<ControlStoreResult.Conflict>().single()
        assertEquals(ConflictReason.TargetChanged, lost.reason); assertTrue(lost.localUnresolvedCommands.isEmpty())
        confirmed(ControlRecordStore(o.owner).execute(won.command, context), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(1, (ControlRecordReader().read(o.raw()) as ControlRecordRead.Supported).arrays.getValue(ControlKind.DEMAND).entries.size)
    }

    @Test fun overlappingSameRefIsRefusedAndFailureReleasesLeaseWithoutResolvingOtherCommands() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        val other = addition(o, user.replace("\"s\"", "\"other\""))
        // Use a request addition so an existing seal cannot turn this into a join.
        val otherRequest = o.control.prepare(o.control.addition(ControlKind.DEMAND) { id -> literal(ControlObligationFixtures.request); set("id", ControlScalar.Text(id)) })
        o.storage.before = true
        val failedOther = o.control.execute(otherRequest) as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, failedOther.reason)
        val pause = ControlStoreTestStorage.Pause(); o.storage.pause = pause
        val first = async { o.control.execute(c, context) }
        withTimeout(10_000) { pause.reached.await() }
        try {
            repeat(2) {
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { runCatching { o.control.execute(c, context) } }
                try {
                    assertTrue("duplicate lease refusal must complete before waiting for the owner lock", duplicate.isCompleted)
                    val failure = duplicate.await().exceptionOrNull()
                    assertEquals(IllegalStateException::class.java, failure?.javaClass)
                    assertEquals("the same command is already executing", failure?.message)
                    assertFalse(first.isCompleted)
                } finally { duplicate.cancelAndJoin() }
            }
            o.storage.before = false; o.storage.after = true
        } finally { pause.release.complete(Unit) }
        val result = first.await() as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, result.reason); assertEquals(setOf(c, otherRequest), result.localUnresolvedCommands)
        assertEquals("after write block", result.failure?.message)
        assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        val retry = confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(setOf(otherRequest), retry.localUnresolvedCommands)
        assertFalse(other in retry.localUnresolvedCommands)
    }

    @Test fun unlandedOldAppendAndLandedUnconfirmedAppendCannotResurrectAfterSettlement() = runBlocking {
        val o = open(); o.data.updateData { raw(seals = "[]") }
        val unlanded = addition(o, user); o.storage.before = true
        assertEquals(UnconfirmedReason.StorageFailure, (o.control.execute(unlanded) as ControlStoreResult.Unconfirmed).reason)
        val landed = addition(o, user); o.storage.afterScope = true
        assertEquals(UnconfirmedReason.StorageFailure, (o.control.execute(landed) as ControlStoreResult.Unconfirmed).reason)
        val read = ControlRecordReader().read(disk()) as ControlRecordRead.Supported
        val seal = (read.arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted).original
        val rotation = o.control.prepareRotation(listOf(seal), fence, life, demand)
        val settled = confirmed(o.control.execute(rotation, context), ConfirmedEffect.AppliedThisAttempt)
        assertEquals(setOf(unlanded, landed), settled.localUnresolvedCommands)
        val before = disk(); val writes = o.storage.writes
        for (c in listOf(unlanded, landed)) {
            val result = o.control.execute(c)
            negative(result, ConflictReason.TargetChanged); assertEquals(setOf(unlanded, landed), result.localUnresolvedCommands)
        }
        assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
        val current = before[USER_EPOCH]!!
        val fresh = o.control.execute(addition(o, user.replace("\"u\"", "\"$current\""))) as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.AppliedThisAttempt, fresh.effect)
        val nullTarget = o.control.execute(addition(o, ControlObligationFixtures.nullSeal)) as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.AppliedThisAttempt, nullTarget.effect)
    }

    @Test fun namespaceAppendChecksOnlyItsOwnerAndAxisAtTheActualAppendPoint() = runBlocking {
        val o = open()
        for ((field, value, reason) in listOf(
            Triple(OWNER_UID.name, "B", ConflictReason.TargetChanged),
            Triple(USER_EPOCH.name, "different", ConflictReason.TargetChanged),
            Triple(OWNER_UID.name, 1, RecoveryReason.UnreadableEpochState),
            Triple(USER_EPOCH.name, 1, RecoveryReason.UnreadableEpochState)
        )) {
            o.data.updateData { raw(seals = "[]").toMutablePreferences().apply {
                if (value is String) this[androidx.datastore.preferences.core.stringPreferencesKey(field)] = value
                else this[intPreferencesKey(field)] = value as Int
            } }
            val c = addition(o, user); val before = disk(); val writes = o.storage.writes
            repeat(2) { val result = o.control.execute(c); negative(result, reason); assertTrue(result.localUnresolvedCommands.isEmpty()) }
            assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
            assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        }
        o.data.updateData { raw(seals = "[]").toMutablePreferences().apply { this[intPreferencesKey(KRX_EPOCH.name)] = 1 } }
        val allowed = o.control.execute(addition(o, user)) as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.AppliedThisAttempt, allowed.effect)
        // Existing join is not a new append, even when the saved namespace has since retired.
        o.data.edit { it[USER_EPOCH] = "retired" }
        val joined = o.control.execute(addition(o, user)) as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.JoinedExisting, joined.effect)
    }

    @Test fun laterPayloadTooLargeRejectsWholeCandidateWithoutBarrierOrFileChange() = runBlocking {
        for (kind in listOf(ControlKind.SEAL, ControlKind.DEMAND)) {
            val o = open()
            val padding = "\"${"x".repeat(if (kind == ControlKind.SEAL) 65_150 else 65_400)}\""
            val source = if (kind == ControlKind.SEAL) raw(seals = "[$user,$padding]") else raw(requests = "[$padding]")
            o.data.updateData { source }; val spec = input(); val c = command(o, spec)
            val relaxed = NamespaceSettlementTransition(ControlPayloadCodec(maxPayloadBytes = 100_000))
            val wouldWrite = relaxed.decide(c, spec, ControlRecordReader().read(source) as ControlRecordRead.Supported, context, false, false)
                as RecordTransactionDecision.Confirm
            val bytes = wouldWrite.candidate[ControlRecordKeys.payload(kind)]!!.toByteArray(Charsets.UTF_8).size
            assertTrue(bytes > 65_536)
            val prime = runCatching { o.owner.transactRecord<Unit> { throw IOException("prime size rejection") } }.exceptionOrNull()
            assertEquals(IOException::class.java, prime?.javaClass); assertEquals("prime size rejection", prime?.message)
            val before = disk(); val writes = o.storage.writes
            val result = o.control.execute(c, context)
            negative(result, RejectionReason.TooLarge(ControlPayloadKey.forKind(kind), bytes, 65_536))
            assertTrue(result.localUnresolvedCommands.isEmpty()); assertEquals(before, disk()); assertEquals(before, o.raw())
            assertEquals(writes, o.storage.writes); assertNull(o.raw()[BARRIER])
            assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        }
    }
}
