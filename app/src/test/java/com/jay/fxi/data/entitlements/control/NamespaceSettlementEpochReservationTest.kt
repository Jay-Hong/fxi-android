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
class NamespaceSettlementEpochReservationTest(private val location: String, private val axis: PurgeScope, private val owner: String?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}/owner={2}") fun cases(): List<Array<Any?>> =
            listOf("seal.key", "seal.before.user", "seal.before.krx", "seal.after.user", "seal.after.krx",
                "seal.journal.epoch", "recovery.target", "hold.query.user", "hold.query.krx", "hold.topic.user", "hold.topic.krx",
                "opaque.seal", "opaque.demand", "opaque.hold", "opaque.recovery", "opaque.array",
                "opaque.epochObject", "opaque.epochArray")
                .flatMap { location -> PurgeScope.entries.flatMap { axis -> listOf("A", "B", null).map { owner -> arrayOf(location, axis, owner) } } }
    }
    @Test fun everyRetainedEpochSourceIsReservedAndUnrelatedFreshValuesRemainAllowed() {
        val reserved = "retained-epoch"
        val quotedOwner = owner?.let { JsonPrimitive(it).toString() } ?: "null"
        var kind = ControlKind.SEAL
        var expectedInterpreted = true
        val pastSeal = NamespaceSettlementR3Fixtures.ownerJson(user.replace("\"s\"", "\"past\"").replace("\"u\"", "\"older-key\""), owner)
        val evidence = witness().copy(operationId = "past-operation", before = fence.copy(ownerUid = owner),
            after = witness().after.copy(ownerUid = owner), journal = JournalTargetV1(owner, PurgeScope.USER, "older-key"))
        val extra: String = when (location) {
            "seal.key" -> pastSeal.replace("older-key", reserved)
            "seal.before.user", "seal.before.krx", "seal.after.user", "seal.after.krx" -> {
                val w = when (location) {
                    "seal.before.user" -> evidence.copy(before = evidence.before.copy(userAccessEpoch = reserved))
                    "seal.before.krx" -> evidence.copy(before = evidence.before.copy(krxCapabilityEpoch = reserved))
                    "seal.after.user" -> evidence.copy(after = evidence.after.copy(userAccessEpoch = reserved))
                    else -> evidence.copy(after = evidence.after.copy(krxCapabilityEpoch = reserved))
                }
                withWitness(pastSeal, w).toPayloadEntry().fields.toString()
            }
            "seal.journal.epoch" -> {
                // D1 ties interpreted journal.epoch to the seal key. An opaque row isolates this source.
                expectedInterpreted = false
                "{\"id\":\"past\",\"ownerUid\":$quotedOwner,\"settlement\":{\"journal\":{\"epoch\":\"$reserved\"}}}"
            }
            "recovery.target" -> { kind = ControlKind.RECOVERY_INTENT
                ControlObligationFixtures.recovery.replace("\"ownerUid\":null", "\"ownerUid\":$quotedOwner").replace("\"targetEpoch\":null", "\"targetEpoch\":\"$reserved\"") }
            "hold.query.user", "hold.query.krx", "hold.topic.user", "hold.topic.krx" -> {
                kind = ControlKind.HOLD
                // Null identities make these particular fixtures opaque; their epoch strings still reserve.
                expectedInterpreted = owner != null
                NamespaceSettlementR3Fixtures.ownerJson(if (location.contains("query")) ControlObligationFixtures.hold else ControlObligationFixtures.topicHold, owner)
                    .replace(if (location.endsWith("user")) "\"userAccessEpoch\":\"u\"" else "\"krxCapabilityEpoch\":\"k\"",
                        if (location.endsWith("user")) "\"userAccessEpoch\":\"$reserved\"" else "\"krxCapabilityEpoch\":\"$reserved\"")
            }
            else -> {
                expectedInterpreted = false
                kind = when (location) { "opaque.demand" -> ControlKind.DEMAND; "opaque.hold" -> ControlKind.HOLD; "opaque.recovery" -> ControlKind.RECOVERY_INTENT; else -> ControlKind.SEAL }
                when (location) {
                    "opaque.array" -> "[[{\"targetEpoch\":\"$reserved\"}]]"
                    "opaque.epochObject" -> "{\"id\":\"past\",\"ownerUid\":$quotedOwner,\"epoch\":{\"value\":\"$reserved\"}}"
                    "opaque.epochArray" -> "{\"id\":\"past\",\"ownerUid\":$quotedOwner,\"epoch\":[\"$reserved\"]}"
                    else -> "{\"id\":\"past\",\"ownerUid\":$quotedOwner,\"future\":[{\"wrapper\":{\"epoch\":\"$reserved\"}}]}"
                }
            }
        }
        val source = raw(seals = if (axis == PurgeScope.USER) "[$user]" else "[$krx]").toMutablePreferences().apply {
            this[ControlRecordKeys.payload(kind)] = if (kind == ControlKind.SEAL) "[${if (axis == PurgeScope.USER) user else krx},$extra]" else "[$extra]"
        }
        val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
        assertEquals("independent fixture classification", expectedInterpreted, read.arrays.getValue(kind).entries.last() is ControlEntryRead.Interpreted)
        fun spec(epoch: String) = if (axis == PurgeScope.USER) input(u = epoch) else input(targets = listOf(node(krx)), k = epoch)
        NamespaceSettlementR3Fixtures.refusal(spec(reserved), source, RejectionReason.InvalidRequest("EpochNotFresh"))
        val positive = NamespaceSettlementR3Fixtures.decide(spec("unrelated-fresh"), source)
        assertEquals(RecordTransactionDecision.Confirm::class.java, positive.javaClass)
        assertEquals(ConfirmedEffect.AppliedThisAttempt, (positive.value as ControlRecordStore.Outcome.Positive).effect)
    }
}
