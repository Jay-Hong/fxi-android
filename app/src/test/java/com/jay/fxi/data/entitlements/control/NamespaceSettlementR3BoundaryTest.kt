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

class NamespaceSettlementR3BoundaryTest {
    @Test fun beforeJournalFieldsUseTheSameUtf16RuleIncludingOwnerAndBothEpochs() {
        for (value in listOf("\uD800", "\uDC00")) {
            val target = node(NamespaceSettlementR3Fixtures.ownerJson(user, value))
            val ownerSpec = input(targets = listOf(target), before = fence.copy(ownerUid = value), request = demand.copy(ownerUid = value))
            val ownerSource = raw(seals = jsonArray(target)).toMutablePreferences().apply { this[OWNER_UID] = value }
            NamespaceSettlementR3Fixtures.refusal(ownerSpec, ownerSource, RejectionReason.InvalidRequest("UnrepresentableJournalField"), context.copy(ownerUid = value))
            for (axis in PurgeScope.entries) {
                val text = (if (axis == PurgeScope.USER) user else krx).replace(if (axis == PurgeScope.USER) "\"u\"" else "\"k\"", JsonPrimitive(value).toString())
                val before = if (axis == PurgeScope.USER) fence.copy(userAccessEpoch = value) else fence.copy(krxCapabilityEpoch = value)
                val spec = input(targets = listOf(node(text)), before = before)
                val source = raw(seals = jsonArray(node(text))).toMutablePreferences().apply { this[if (axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH] = value }
                NamespaceSettlementR3Fixtures.refusal(spec, source, RejectionReason.InvalidRequest("UnrepresentableJournalField"))
            }
        }
        val paired = "\uD83D\uDE80"
        assertNull(input(targets = listOf(node(NamespaceSettlementR3Fixtures.ownerJson(user, paired))), before = fence.copy(ownerUid = paired), request = demand.copy(ownerUid = paired)).invalidInput())
    }

    @Test fun emptyScopesAreNotCanonicalOnApplicationOrReceipt() {
        for (line in listOf("A|u||", "|||")) {
            val source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = line }
            assertNull(transition.canonicalJournal(source))
            NamespaceSettlementR3Fixtures.refusal(input(), source, RecoveryReason.JournalMigrationRequired)
            val landed = settled(input()).toMutablePreferences().apply { this[PURGE_JOURNAL] = line }
            assertEquals(mapOf("s" to JournalObservation.Uninterpretable), NamespaceSettlementR3Fixtures.receipt(input(), landed).journal)
        }
        assertEquals(1, transition.canonicalJournal(raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER" })!!.size)
    }

    @Test fun emptyOriginIsInvalidDemandBeforeAdmissionAndWitnessClassification() {
        val empty = LifetimeId(""); val spec = input(origin = empty, request = demand.copy(raisedAt = EventOrderV1(empty, 7)))
        for (attempt in listOf(null, context, context.copy(originLifetimeId = empty))) {
            for (source in listOf(raw(), raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" },
                raw(seals = jsonArray(withWitness(user, witness()))))) {
                NamespaceSettlementR3Fixtures.refusal(spec, source, RejectionReason.InvalidRequest("InvalidDemand"), attempt)
            }
        }
        assertNull(input().invalidInput())
    }

    @Test fun epochReservationDoesNotInterpretNumbersOrOrdinaryStringsAsEpochs() {
        for (extra in listOf("{\"id\":\"extra\",\"epoch\":123}", "{\"id\":\"123\",\"note\":\"123\"}")) {
            val result = NamespaceSettlementR3Fixtures.decide(input(u = "123"), raw(seals = "[$user,$extra]"))
            assertEquals(RecordTransactionDecision.Confirm::class.java, result.javaClass)
            assertEquals(ConfirmedEffect.AppliedThisAttempt, (result.value as ControlRecordStore.Outcome.Positive).effect)
        }
    }

    @Test fun candidateIdentityChecksCountKindAndInterpretabilitySeparately() {
        val required = mapOf("s" to (ControlKind.SEAL to node(user)))
        assertTrue(transition.validCandidate(ControlRecordReader().read(raw()), required))
        assertFalse("missing id", transition.validCandidate(ControlRecordReader().read(raw(seals = "[]")), required))
        assertFalse("duplicate id", transition.validCandidate(ControlRecordReader().read(raw(seals = "[$user,$user]")), required))
        val request = node(ControlObligationFixtures.request.replace("\"d\"", "\"s\""))
        val wrongArray = ControlRecordReader().read(raw(seals = "[]", requests = jsonArray(request)))
        assertFalse("interpreted but wrong array", transition.validCandidate(wrongArray, mapOf("s" to (ControlKind.SEAL to request))))
        assertFalse("right array but opaque", transition.validCandidate(ControlRecordReader().read(raw(seals = "[{\"id\":\"s\",\"kind\":\"FUTURE\"}]")), required))
    }
}
