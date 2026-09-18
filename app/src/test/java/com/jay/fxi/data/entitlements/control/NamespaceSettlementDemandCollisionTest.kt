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
class NamespaceSettlementDemandCollisionTest(private val kind: ControlKind) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = ControlKind.entries
    }
    @get:Rule val folder = TemporaryFolder()
    @Test fun interpretedDemandIdCollisionIsReportedBeforeCandidateValidationWithoutAnyWrite() = runBlocking {
        val o = ControlStoreTestStorage(File(folder.root, "collision.preferences_pb"))
        try {
            val original = when (kind) {
                ControlKind.SEAL -> user.replace("\"s\"", "\"$demandId\"")
                ControlKind.DEMAND -> ControlObligationFixtures.request.replace("\"d\"", "\"$demandId\"")
                ControlKind.HOLD -> ControlObligationFixtures.hold.replace("\"h\"", "\"$demandId\"")
                ControlKind.RECOVERY_INTENT -> ControlObligationFixtures.recovery.replace("\"r\"", "\"$demandId\"")
            }
            val before = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(kind)] = if (kind == ControlKind.SEAL) "[$user,$original]" else "[$original]" }
            o.data.updateData { before }
            val read = ControlRecordReader().read(before) as ControlRecordRead.Supported
            assertTrue(read.locations(demandId).single().second is ControlEntryRead.Interpreted)
            val writes = o.storage.writes; val c = command(o, input())
            val result = o.control.execute(c, context); negative(result, ConflictReason.IdCollision)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            assertTrue(result.localUnresolvedCommands.isEmpty()); assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
            // Removing the collision, while keeping the exact command, permits the first application.
            o.data.updateData { raw() }
            confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
            Unit
        } finally { o.close() }
    }
}
