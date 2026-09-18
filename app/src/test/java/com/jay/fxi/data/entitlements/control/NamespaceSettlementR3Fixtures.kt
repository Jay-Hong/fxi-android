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

internal object NamespaceSettlementR3Fixtures {
    fun ownerJson(json: String, owner: String?) = json.replace("\"A\"", owner?.let { JsonPrimitive(it).toString() } ?: "null")
    fun owned(owner: String?) = input(targets = listOf(node(ownerJson(user, owner))),
        before = fence.copy(ownerUid = owner), request = demand.copy(ownerUid = owner))
    fun source(owner: String?): Preferences = raw(seals = "[${ownerJson(user, owner)}]").toMutablePreferences().apply {
        if (owner == null) remove(OWNER_UID) else this[OWNER_UID] = owner
    }
    fun decide(spec: RotateAndSettleNamespaces, source: Preferences = raw(), attempt: AttemptContext? = context) =
        transition.decide(CommandRef(spec.operationId, ControlCommandBody.RotateAndSettle(spec)), spec,
            ControlRecordReader().read(source) as ControlRecordRead.Supported, attempt, false, false)
    fun refusal(spec: RotateAndSettleNamespaces, source: Preferences, reason: Any, attempt: AttemptContext? = context) {
        val result = decide(spec, source, attempt)
        assertEquals(RecordTransactionDecision.Observe::class.java, result.javaClass)
        negative((result.value as ControlRecordStore.Outcome.Negative).result, reason)
    }
    fun receipt(spec: RotateAndSettleNamespaces, source: Preferences): SettlementReceipt {
        val result = decide(spec, source, null)
        assertEquals(RecordTransactionDecision.Confirm::class.java, result.javaClass)
        val value = result.value as ControlRecordStore.Outcome.Positive
        assertEquals(ConfirmedEffect.PostconditionConfirmed, value.effect)
        return value.settlement!!
    }
    fun addition(o: ControlStoreTestStorage, json: String) = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
        literal(json); set("id", ControlScalar.Text(id))
    })
}
