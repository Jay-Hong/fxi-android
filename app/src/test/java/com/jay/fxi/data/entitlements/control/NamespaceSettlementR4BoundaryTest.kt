package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.command
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.confirmed
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.settled
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.transition
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NamespaceSettlementR4BoundaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun duplicateInterpretedSealsFailCandidateCardinalityAlone() {
        val target = node(user)
        val required = mapOf("s" to (ControlKind.SEAL to target))
        fun supported(count: Int): ControlRecordRead.Supported {
            val entries = List(count) { ControlEntryRead.Interpreted(target, input().seals.single()) }
            return ControlRecordRead.Supported(raw(seals = jsonArray(*Array(count) { target })),
                ControlKind.entries.associateWith { kind ->
                    ControlArrayRead.Parsed(if (kind == ControlKind.SEAL) entries else emptyList())
                })
        }
        val duplicate = supported(2)
        val matches = duplicate.locations("s")
        assertEquals(2, matches.size)
        assertFalse(duplicate.hasUninterpretable)
        matches.forEach { (kind, entry) ->
            assertEquals(ControlKind.SEAL, kind)
            assertTrue(entry is ControlEntryRead.Interpreted)
            assertEquals(target.toPayloadEntry(), entry.payload())
        }
        assertFalse("two interpreted matches must not select the first", transition.validCandidate(duplicate, required))
        assertTrue("one otherwise identical match is valid", transition.validCandidate(supported(1), required))
    }

    @Test fun matchingOpaqueExpectedPayloadFailsCandidateInterpretabilityAlone() {
        val target = node("""{"id":"s","kind":"FUTURE"}""")
        val read = ControlRecordReader().read(raw(seals = jsonArray(target))) as ControlRecordRead.Supported
        val matches = read.locations("s")
        assertEquals(1, matches.size)
        assertEquals(ControlKind.SEAL, matches.single().first)
        assertTrue(matches.single().second is ControlEntryRead.Uninterpretable)
        assertEquals(target.toPayloadEntry(), matches.single().second.payload())
        assertFalse("matching payload is insufficient without interpretation",
            transition.validCandidate(read, mapOf("s" to (ControlKind.SEAL to target))))
        assertTrue(transition.validCandidate(ControlRecordReader().read(raw()), mapOf("s" to (ControlKind.SEAL to node(user)))))
    }

    @Test fun sameArrayInterpretedDemandsRemainUninterpretableOnReconfirmation() = assertDuplicateDemands(false)

    @Test fun crossArrayInterpretedDemandsRemainUninterpretableOnReconfirmation() = assertDuplicateDemands(true)

    private fun assertDuplicateDemands(crossArray: Boolean) {
        val spec = input()
        val original = settled(spec)
        val base = ControlRecordReader().read(original) as ControlRecordRead.Supported
        val demand = base.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted
        fun receipt(read: ControlRecordRead.Supported): SettlementReceipt {
            val result = transition.decide(CommandRef(spec.operationId, ControlCommandBody.RotateAndSettle(spec)),
                spec, read, null, false, false)
            assertEquals(RecordTransactionDecision.Confirm::class.java, result.javaClass)
            assertEquals(read.original, (result as RecordTransactionDecision.Confirm).candidate)
            val outcome = result.value as ControlRecordStore.Outcome.Positive
            assertEquals(ConfirmedEffect.PostconditionConfirmed, outcome.effect)
            return outcome.settlement!!
        }
        assertEquals(DemandObservation.Present, receipt(base).demand)
        val source = original.toMutablePreferences()
        val arrays = base.arrays.toMutableMap()
        if (crossArray) {
            val hold = node(ControlObligationFixtures.hold.replace("\"h\"", "\"${spec.demandId}\""))
            val entry = ControlObligations.read(ControlKind.HOLD, hold)
            assertTrue(entry is ControlEntryRead.Interpreted)
            arrays[ControlKind.HOLD] = ControlArrayRead.Parsed(listOf(entry))
            source[ControlRecordKeys.payload(ControlKind.HOLD)] = jsonArray(hold)
        } else {
            val second = ControlEntryRead.Interpreted(demand.original, demand.value)
            arrays[ControlKind.DEMAND] = ControlArrayRead.Parsed(listOf(demand, second))
            source[ControlRecordKeys.payload(ControlKind.DEMAND)] = jsonArray(demand.original, second.original)
        }
        // Exercise the transition's internal input directly, without D1 demoting duplicate ids.
        val read = ControlRecordRead.Supported(source, arrays)
        val matches = read.locations(spec.demandId)
        assertEquals(2, matches.size)
        assertEquals(ControlKind.DEMAND, matches.first().first)
        assertTrue(matches.all { it.second is ControlEntryRead.Interpreted })
        assertFalse(read.hasUninterpretable)
        assertEquals(demand.original.toPayloadEntry(), matches.first().second.payload())
        assertEquals("crossArray=$crossArray", DemandObservation.Uninterpretable, receipt(read).demand)
    }

    private fun rotation(axis: PurgeScope) = input(
        targets = listOf(node(if (axis == PurgeScope.USER) user else krx)),
        u = "r4-new-user", k = "r4-new-krx")

    private fun assertOtherAdmissionConditions(spec: RotateAndSettleNamespaces, source: Preferences) {
        assertNull(spec.invalidInput())
        assertEquals(spec.before.ownerUid, source[OWNER_UID])
        assertEquals(spec.before.userAccessEpoch, source[USER_EPOCH])
        assertEquals(spec.before.krxCapabilityEpoch, source[KRX_EPOCH])
        val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
        assertFalse(read.hasUninterpretable)
        assertTrue(read.locations(spec.demandId).isEmpty())
        val journal = transition.canonicalJournal(source)
        assertNotNull(journal)
        assertTrue("W2 must not mask this guard", transition.epochsAreUnused(spec, read, journal!!))
        spec.newEpochs().filterNotNull().forEach { fresh ->
            assertFalse("fresh epoch is absent from every stored string",
                source.asMap().values.filterIsInstance<String>().any { fresh in it })
        }
    }

    private suspend fun disk(file: File): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    @Test fun foreignSettlementAloneBlocksApplicationWithUnusedEpochs() = runBlocking {
        for (axis in PurgeScope.entries) {
            val file = File(folder.root, "foreign-$axis.preferences_pb")
            val owner = ControlStoreTestStorage(file)
            try {
                val spec = rotation(axis)
                val target = if (axis == PurgeScope.USER) user else krx
                val foreign = spec.witness(spec.seals.single()).copy(operationId = "foreign-operation",
                    after = spec.before.copy(userAccessEpoch = "foreign-after-user", krxCapabilityEpoch = "foreign-after-krx"))
                val before = raw(seals = jsonArray(withWitness(target, foreign)))
                assertOtherAdmissionConditions(spec, before)
                owner.data.updateData { before }
                val c = command(owner, spec)
                val writes = owner.storage.writes
                val result = owner.control.execute(c, context)
                assertEquals("G16 effect=${(result as? ControlStoreResult.Confirmed)?.effect}, writes=${owner.storage.writes - writes}",
                    ControlStoreResult.Conflict::class.java, result.javaClass)
                negative(result, ConflictReason.TargetChanged)
                assertEquals(before, owner.raw()); assertEquals(before, disk(file))
                assertEquals(writes, owner.storage.writes)
                assertTrue(result.localUnresolvedCommands.isEmpty())
                assertTrue(ControlCommandTracking.forOwner(owner.owner).executing.isEmpty())
                owner.data.updateData { raw(seals = jsonArray(node(target))) }
                confirmed(owner.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
            } finally { owner.close() }
        }
    }

    @Test fun operationIdCollisionAloneBlocksApplicationWithUnusedEpochs() = runBlocking {
        for (axis in PurgeScope.entries) {
            val file = File(folder.root, "operation-$axis.preferences_pb")
            val owner = ControlStoreTestStorage(file)
            try {
                val spec = rotation(axis)
                val target = if (axis == PurgeScope.USER) user else krx
                val other = target.replace("\"${spec.seals.single().id}\"", "\"other\"")
                val collision = spec.witness(spec.seals.single()).copy(
                    after = spec.before.copy(userAccessEpoch = "foreign-after-user", krxCapabilityEpoch = "foreign-after-krx"))
                val before = raw(seals = jsonArray(node(target), withWitness(other, collision)))
                assertOtherAdmissionConditions(spec, before)
                owner.data.updateData { before }
                val c = command(owner, spec)
                val writes = owner.storage.writes
                val result = owner.control.execute(c, context)
                assertEquals("G20 effect=${(result as? ControlStoreResult.Confirmed)?.effect}, writes=${owner.storage.writes - writes}",
                    ControlStoreResult.Conflict::class.java, result.javaClass)
                negative(result, ConflictReason.OperationIdCollision)
                assertEquals(before, owner.raw()); assertEquals(before, disk(file))
                assertEquals(writes, owner.storage.writes)
                assertTrue(result.localUnresolvedCommands.isEmpty())
                assertTrue(ControlCommandTracking.forOwner(owner.owner).executing.isEmpty())
                val unrelated = withWitness(other, collision.copy(operationId = "unrelated-operation"))
                owner.data.updateData { raw(seals = jsonArray(node(target), unrelated)) }
                val applied = confirmed(owner.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
                assertEquals(unrelated.toPayloadEntry(), applied.snapshot.record.locations("other").single().second.payload())
            } finally { owner.close() }
        }
    }
}
