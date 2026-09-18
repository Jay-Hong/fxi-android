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
class NamespaceSettlementDemandObservationTest(private val change: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf("ownerUid", "binding", "originLifetimeId", "intent", "raisedAt", "kind",
            "wrong.SEAL", "wrong.HOLD", "wrong.RECOVERY_INTENT", "duplicate.sameArray", "duplicate.crossArray")
    }
    @Test fun receiptDistinguishesEachFieldAndAmbiguousDemandIdentity() {
        val spec = input(); val original = settled(spec); val saved = Json.parseToJsonElement(original[DEMAND]!!) as JsonArray
        val request = saved.single() as JsonObject
        val source = original.toMutablePreferences()
        var expected = DemandObservation.Changed
        when {
            change.startsWith("wrong.") -> {
                val kind = ControlKind.valueOf(change.substringAfter('.'))
                val replacement = when (kind) {
                    ControlKind.SEAL -> user.replace("\"s\"", "\"$demandId\"")
                    ControlKind.HOLD -> ControlObligationFixtures.hold.replace("\"h\"", "\"$demandId\"")
                    else -> ControlObligationFixtures.recovery.replace("\"r\"", "\"$demandId\"")
                }
                source[DEMAND] = "[]"
                source[ControlRecordKeys.payload(kind)] = if (kind == ControlKind.SEAL) original[SEAL]!!.dropLast(1) + ",$replacement]" else "[$replacement]"
                expected = DemandObservation.Uninterpretable
            }
            change.startsWith("duplicate.") -> {
                if (change == "duplicate.sameArray") source[DEMAND] = "[$request,$request]"
                else source[HOLD] = "[${ControlObligationFixtures.hold.replace("\"h\"", "\"$demandId\"")}]"
                expected = DemandObservation.Uninterpretable
            }
            change == "kind" -> source[DEMAND] = "[{\"id\":\"$demandId\",\"kind\":\"SCHEDULE_GUARD\"}]"
            else -> {
                val value = when (change) {
                    "ownerUid" -> JsonNull
                    "binding" -> JsonPrimitive(4)
                    "originLifetimeId" -> JsonPrimitive("other")
                    "intent" -> JsonPrimitive("IF_STALE")
                    "raisedAt" -> JsonPrimitive(8)
                    else -> error(change)
                }
                source[DEMAND] = JsonArray(listOf(JsonObject(request + (change to value)))).toString()
            }
        }
        assertEquals(expected, NamespaceSettlementR3Fixtures.receipt(spec, source).demand)
        assertEquals(DemandObservation.Present, NamespaceSettlementR3Fixtures.receipt(spec, original).demand)
    }
}
