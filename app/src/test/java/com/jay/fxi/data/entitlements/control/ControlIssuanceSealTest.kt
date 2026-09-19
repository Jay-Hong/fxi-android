package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demand
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.fence
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.life
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.di.EntitlementsModule
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Behavioural boundaries for issuance; UUID shape/samples do not prove entropy or global uniqueness. */
class ControlIssuanceSealTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open() = ControlStoreTestStorage(File(folder.root, "seal-${opened.size}.preferences_pb"))
        .also { opened += it }
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private fun uuid(n: Long) = UUID(0, n).toString()
    private fun action(store: ControlRecordStore) = store.addition(ControlKind.DEMAND) { id ->
        literal(ControlObligationFixtures.request); set("id", ControlScalar.Text(id))
    }

    @Test fun assertionsAreEnabled() {
        assertTrue("java -ea required", javaClass.desiredAssertionStatus())
        val error = runCatching { assert(false) { "assertions active" } }.exceptionOrNull()
        assertEquals(AssertionError::class.java, error?.javaClass)
        assertEquals("assertions active", error?.message)
    }

    @Test fun prepareRegistersAnExecutableRefWithoutStorageWrites() = runBlocking {
        val o = open(); o.seed()
        var calls = 0L
        val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++calls) })
        val before = o.raw(); val writes = o.storage.writes
        val command = store.prepare(action(store))
        assertEquals(2L, calls); assertEquals(uuid(2), command.id)
        assertSame(command, ControlCommandTracking.forOwner(o.owner).findPrepared(command)?.command)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        val result = store.execute(command)
        assertEquals(ControlStoreResult.Confirmed::class.java, result.javaClass)
        result as ControlStoreResult.Confirmed
        assertSame(command, result.command)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(listOf(uuid(1)), result.effectiveIds)
        assertEquals(emptySet<CommandRef>(), result.localUnresolvedCommands)
        assertNull(result.settlement)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(2L, calls)
        val expected = ControlObligationFixtures.request.replace("\"d\"", "\"${uuid(1)}\"")
        assertEquals("[$expected]", o.raw()[ControlStoreTestStorage.DEMAND])
    }

    @Test fun duplicateSameRefAndDifferentRefPreserveTheFirstHistory() = runBlocking {
        val o = open(); o.seed()
        val command = o.control.prepare(action(o.control))
        val result = o.control.execute(command)
        assertEquals(ControlStoreResult.Confirmed::class.java, result.javaClass)
        val tracker = ControlCommandTracking.forOwner(o.owner)
        val first = tracker.findPrepared(command)!!
        val targets = first.targets.get()
        assertTrue(first.confirmationRequested.get()); assertTrue(first.confirmed.get())
        assertEquals(1, targets.size); assertNotNull(targets.single())
        tracker.markUnresolved(command)
        val before = o.raw(); val writes = o.storage.writes
        for (duplicate in listOf(command, CommandRef(command.id, command.body, command.ownerTrackingLifetimeId))) {
            val failure = runCatching { tracker.registerPrepared(duplicate) }.exceptionOrNull()
            // Check preservation before the error, so destructive put is killed by history loss.
            assertSame(first, tracker.findPrepared(command))
            assertSame(targets, first.targets.get())
            assertTrue(first.confirmationRequested.get()); assertTrue(first.confirmed.get())
            assertEquals(setOf(command), tracker.snapshot())
            collision(failure)
        }
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    // Concurrent smoke coverage; the putIfAbsent structure check, not a forced interleaving, pins atomicity.
    @Test fun concurrentDuplicateIdsHaveExactlyOneRegisteredWinner() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner)
        val refs = listOf(CommandRef(uuid(7), emptyList(), tracker.lifetimeId), CommandRef(uuid(7), emptyList(), tracker.lifetimeId))
        val ready = CountDownLatch(2); val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = refs.map { ref -> pool.submit(Callable {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                runCatching { tracker.registerPrepared(ref) }
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val winner = results.single { it.isSuccess }.getOrThrow()
            assertSame(winner, tracker.findPrepared(winner)?.command)
            assertNull(tracker.findPrepared(refs.single { it !== winner }))
            collision(results.single { it.isFailure }.exceptionOrNull())
        } finally { go.countDown(); pool.shutdownNow() }
    }

    @Test fun collidingPrepareDoesNotReissueOrReplaceItsFirstRef() = runBlocking {
        val o = open(); o.seed(); var calls = 0
        val store = ControlRecordStore(o.owner, ControlIdGenerator { calls++; UUID(0, if (calls <= 2) 8 else calls.toLong() + 6) })
        val mutation = action(o.control)
        val first = store.prepare(mutation)
        val tracked = ControlCommandTracking.forOwner(o.owner).findPrepared(first)
        val failure = runCatching { store.prepare(mutation) }.exceptionOrNull()
        assertEquals(2, calls)
        collision(failure)
        assertSame(tracked, ControlCommandTracking.forOwner(o.owner).findPrepared(first))
        assertEquals(ControlStoreResult.Confirmed::class.java, store.execute(first).javaClass)
        assertEquals(2, calls)
    }

    @Test fun unregisteredAndSameIdSameBodyRefsCannotExecuteOrCheckpoint() = runBlocking {
        val o = open(); o.seed()
        val prepared = o.control.prepare(action(o.control))
        assertNotNull(o.control.checkpoint(prepared))
        val refs = listOf(CommandRef(uuid(9), prepared.body, prepared.ownerTrackingLifetimeId), CommandRef(prepared.id, prepared.body, prepared.ownerTrackingLifetimeId))
        for (ref in refs) {
            val before = o.raw(); val writes = o.storage.writes
            assertNull(o.control.checkpoint(ref))
            historyUnavailable(o.control.execute(ref), ref)
            assertNull(ControlCommandTracking.forOwner(o.owner).findPrepared(ref))
            assertNull(o.control.checkpoint(ref))
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        }
        assertEquals(ControlStoreResult.Confirmed::class.java, o.control.execute(prepared).javaClass)
    }

    @Test fun previousConfirmationDoesNotRegisterOrEnableExecution() = runBlocking {
        val o = open(); o.seed()
        val command = o.control.prepare(action(o.control))
        assertEquals(ControlStoreResult.Confirmed::class.java, o.control.execute(command).javaClass)
        val checkpoint = o.control.checkpoint(command)!!
        val other = open(); other.data.updateData { o.raw() }
        val before = other.raw(); val writes = other.storage.writes
        val confirmed = other.control.confirmPrevious(command, checkpoint)
        assertEquals(ControlStoreResult.Confirmed::class.java, confirmed.javaClass)
        confirmed as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.PostconditionConfirmed, confirmed.effect)
        assertEquals(listOf(checkpoint.targets.single()!!.id), confirmed.effectiveIds)
        assertSame(command, confirmed.command); assertTrue(confirmed.localUnresolvedCommands.isEmpty())
        assertEquals(RecordTransactionEvidence.LockedFileRead, confirmed.proof.storage)
        assertNull(confirmed.settlement)
        assertNull(ControlCommandTracking.forOwner(other.owner).findPrepared(command))
        assertNull(other.control.checkpoint(command))
        historyUnavailable(other.control.execute(command), command)
        assertEquals(before, other.raw()); assertEquals(writes, other.storage.writes)
    }

    @Test fun matchingLiveCheckpointKeepsLocallyTrackedTargetObjects() = runBlocking {
        val o = open(); o.seed()
        val command = o.control.prepare(action(o.control))
        assertEquals(ControlStoreResult.Confirmed::class.java, o.control.execute(command).javaClass)
        val checkpoint = o.control.checkpoint(command)!!
        val local = checkpoint.targets.single()!!
        val suppliedTarget = ControlCommandTarget(local.id,
            ControlNode.of(local.postcondition.toPayloadEntry().fields), local.joined)
        assertNotSame(local, suppliedTarget)
        val supplied = ControlCommandCheckpoint(command, listOf(suppliedTarget), true)
        val before = o.raw(); val writes = o.storage.writes
        val result = o.control.confirmPrevious(command, supplied)
        assertEquals(ControlStoreResult.Confirmed::class.java, result.javaClass)
        result as ControlStoreResult.Confirmed
        assertSame(command, result.command)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
        assertEquals(listOf(local.id), result.effectiveIds)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.proof.storage)
        assertNull(result.settlement)
        assertEquals(before, result.snapshot.record.original)
        assertSame(local, o.control.checkpoint(command)!!.targets.single())
        assertTrue(o.control.checkpoint(command)!!.confirmationRequested)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
    }

    @Test fun prepareRotationIssuesOnlySelectedAxesAndRegistersWithoutStorageWrites() = runBlocking {
        for (targets in listOf(emptyList(), listOf(node(user)), listOf(node(krx)), listOf(node(user), node(krx)))) {
            val o = open(); val source = raw(seals = targets.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() })
            o.data.updateData { source }; var calls = 0L
            val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++calls) })
            val writes = o.storage.writes
            val command = store.prepareRotation(targets, fence, life, demand)
            val spec = (command.body as ControlCommandBody.RotateAndSettle).input
            assertEquals(uuid(1), command.id); assertEquals(uuid(1), spec.operationId)
            assertEquals(uuid(2), spec.demandId)
            assertEquals(if (targets.any { it.text("id") == FieldRead.Present("s") }) uuid(3) else null, spec.newUserEpoch)
            assertEquals(if (targets.any { it.text("id") == FieldRead.Present("c") }) uuid(if (targets.size == 2) 4 else 3) else null, spec.newKrxEpoch)
            assertEquals(2L + targets.size, calls)
            assertEquals(targets, spec.targets); assertEquals(fence, spec.before)
            assertEquals(life, spec.originLifetimeId); assertEquals(demand, spec.demand)
            assertEquals(source, o.raw()); assertEquals(writes, o.storage.writes)
            assertSame(command, ControlCommandTracking.forOwner(o.owner).findPrepared(command)?.command)
            assertNull(store.checkpoint(command))
            val result = store.execute(command, context)
            if (targets.isEmpty()) {
                assertEquals(ControlStoreResult.Rejected::class.java, result.javaClass)
                assertEquals(RejectionReason.InvalidRequest("EmptyTargets"), (result as ControlStoreResult.Rejected).reason)
                assertEquals(source, o.raw()); assertEquals(writes, o.storage.writes)
            } else {
                val confirmed = NamespaceSettlementFixtures.confirmed(result, ConfirmedEffect.AppliedThisAttempt)
                assertEquals(spec.effectiveIds, confirmed.effectiveIds)
                assertEquals(spec.operationId, confirmed.settlement!!.operationId)
                assertEquals(spec.after, confirmed.settlement.after)
                assertEquals(NamespaceSettlementFixtures.settled(spec, source, command.ownerTrackingLifetimeId), o.raw())
            }
            assertEquals(2L + targets.size, calls)
        }
    }

    @Test fun rotationRetriesKeepFixedInputsBeforeAndAfterLanding() = runBlocking {
        for (landed in listOf(false, true)) {
            val o = open(); val source = raw(seals = "[$user,$krx]"); o.data.updateData { source }
            var calls = 0L
            val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++calls) })
            val command = store.prepareRotation(listOf(node(user), node(krx)), fence, life, demand)
            val spec = (command.body as ControlCommandBody.RotateAndSettle).input
            val expected = NamespaceSettlementFixtures.settled(spec, source, command.ownerTrackingLifetimeId)
            if (landed) o.storage.afterScope = true else o.storage.before = true
            val failed = store.execute(command, context)
            assertEquals(ControlStoreResult.Unconfirmed::class.java, failed.javaClass)
            failed as ControlStoreResult.Unconfirmed
            assertSame(command, failed.command); assertEquals(setOf(command), failed.localUnresolvedCommands)
            assertEquals(UnconfirmedReason.StorageFailure, failed.reason)
            assertEquals(ControlAttemptPhase.ConfirmingStorage, failed.phase)
            assertEquals(IOException::class.java, failed.failure?.javaClass)
            assertEquals(if (landed) "after completed write scope" else "before write block", failed.failure?.message)
            assertEquals(4L, calls)
            assertEquals(if (landed) expected else source, o.raw())
            val retry = NamespaceSettlementFixtures.confirmed(store.execute(command, context),
                if (landed) ConfirmedEffect.PostconditionConfirmed else ConfirmedEffect.AppliedThisAttempt)
            assertEquals(spec.effectiveIds, retry.effectiveIds)
            assertEquals(spec.operationId, retry.settlement!!.operationId)
            assertEquals(spec.after, retry.settlement.after)
            assertEquals(expected, o.raw().toMutablePreferences().apply { remove(BARRIER) })
            assertEquals(4L, calls)
        }
    }

    @Test fun productionDIUsesTheRandomSingleton() {
        assertSame(EpochIdGenerator.Random, EntitlementsModule.provideEpochIdGenerator())
    }

    @Test fun randomEpochSamplesAreDistinctCanonicalVersionFourVariantTwoUuids() {
        assertUuidPair(EpochIdGenerator.Random.next(), EpochIdGenerator.Random.next())
    }

    @Test fun defaultCommandIdsAreDistinctCanonicalVersionFourVariantTwoUuids() {
        val o = open(); val store = ControlRecordStore(o.owner)
        assertUuidPair(store.prepare().id, store.prepare().id)
    }

    private fun assertUuidPair(first: String, second: String) {
        for (value in listOf(first, second)) {
            val parsed = UUID.fromString(value)
            assertEquals(value, parsed.toString())
            assertEquals(4, parsed.version()); assertEquals(2, parsed.variant())
        }
        assertNotEquals(first, second)
    }

    private fun collision(error: Throwable?) {
        assertEquals(IllegalStateException::class.java, error?.javaClass)
        assertEquals("command UUID collision; do not reissue an identity to hide it", error?.message)
    }

    private fun historyUnavailable(result: ControlStoreResult, command: CommandRef) {
        assertEquals(ControlStoreResult.Unconfirmed::class.java, result.javaClass)
        result as ControlStoreResult.Unconfirmed
        assertSame(command, result.command)
        assertEquals(UnconfirmedReason.HistoryUnavailable, result.reason)
        assertEquals(ControlAttemptPhase.PreparingCandidate, result.phase)
        assertEquals(ControlRecordRead.Supported::class.java, result.lastObservation?.javaClass)
        assertNull(result.failure)
        assertTrue(command in result.localUnresolvedCommands)
    }
}
