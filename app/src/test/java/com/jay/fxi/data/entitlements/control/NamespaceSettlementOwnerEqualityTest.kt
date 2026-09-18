package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.HOLD
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.RECOVERY
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.fence
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.life
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demand
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demandId
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.newUser
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.newKrx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.command
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.confirmed
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.settled
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.transition
import com.jay.fxi.data.entitlements.control.NamespaceSettlementOracle.witness
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import okio.buffer
import okio.source

@RunWith(Parameterized::class)
class NamespaceSettlementOwnerEqualityTest(private val guard: String, private val actualOwner: String?, private val expectedOwner: String?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/actual={1}/expected={2}") fun cases(): List<Array<Any?>> =
            listOf("G06", "G08", "G14", "G22", "G25", "G18.before", "G18.after", "G18.journal", "G19")
                .flatMap { guard -> listOf(arrayOf(guard, null, "A"), arrayOf(guard, "A", null)) }
    }
    @Test fun ownerEqualityNeverTreatsEitherNullOperandAsWildcard() {
        val spec = NamespaceSettlementR3Fixtures.owned(expectedOwner)
        val source = NamespaceSettlementR3Fixtures.source(expectedOwner)
        val attempt = context.copy(ownerUid = expectedOwner)
        val target = NamespaceSettlementR3Fixtures.ownerJson(user, expectedOwner)
        val expectedWitness = witness().copy(before = fence.copy(ownerUid = expectedOwner), after = witness().after.copy(ownerUid = expectedOwner),
            journal = JournalTargetV1(expectedOwner, PurgeScope.USER, "u"))
        when (guard) {
            "G06" -> {
                val bad = input(targets = listOf(node(NamespaceSettlementR3Fixtures.ownerJson(user, actualOwner))), before = spec.before, request = spec.demand)
                assertEquals("TargetFenceMismatch", bad.invalidInput())
            }
            "G08" -> {
                val bad = input(targets = spec.targets, before = spec.before, request = spec.demand.copy(ownerUid = actualOwner))
                assertEquals("DemandScopeMismatch", bad.invalidInput())
            }
            "G14" -> NamespaceSettlementR3Fixtures.refusal(spec, source.toMutablePreferences().apply {
                this[SEAL] = "[${NamespaceSettlementR3Fixtures.ownerJson(user, actualOwner)}]"
            }, ConflictReason.TargetChanged, attempt)
            "G22" -> NamespaceSettlementR3Fixtures.refusal(spec, source.toMutablePreferences().apply {
                if (actualOwner == null) remove(OWNER_UID) else this[OWNER_UID] = actualOwner
            }, ConflictReason.TargetChanged, attempt)
            "G25" -> NamespaceSettlementR3Fixtures.refusal(spec, source, ConflictReason.TargetChanged, attempt.copy(ownerUid = actualOwner))
            "G18.before", "G18.after", "G18.journal" -> {
                val changed = when (guard) {
                    "G18.before" -> expectedWitness.copy(before = expectedWitness.before.copy(ownerUid = actualOwner))
                    "G18.after" -> expectedWitness.copy(after = expectedWitness.after.copy(ownerUid = actualOwner))
                    else -> expectedWitness.copy(journal = expectedWitness.journal.copy(ownerUid = actualOwner))
                }
                // Journal owner A on a null-owner seal cannot pass D1; test that comparison directly.
                if (guard == "G18.journal" && expectedOwner == null) assertFalse(transition.witnessMatches(changed, expectedWitness))
                else NamespaceSettlementR3Fixtures.refusal(spec, source.toMutablePreferences().apply {
                    this[SEAL] = jsonArray(withWitness(target, changed))
                }, RecoveryReason.InconsistentSettlement, attempt)
                assertTrue(transition.witnessMatches(expectedWitness.copy(), expectedWitness))
            }
            "G19" -> {
                assertFalse(transition.immutableSealMatches(node(NamespaceSettlementR3Fixtures.ownerJson(user, actualOwner)), node(target)))
                assertTrue(transition.immutableSealMatches(node(target), node(target)))
            }
        }
        assertNull(spec.invalidInput())
        val positive = NamespaceSettlementR3Fixtures.decide(spec, source, attempt)
        assertEquals(RecordTransactionDecision.Confirm::class.java, positive.javaClass)
    }
}
