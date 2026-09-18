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

class NamespaceSettlementR3StorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(): Pair<ControlStoreTestStorage, File> {
        val file = File(folder.root, "r3-${opened.size}.preferences_pb")
        return ControlStoreTestStorage(file).also { opened += it } to file
    }
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun failed(result: ControlStoreResult) {
        assertEquals(ControlStoreResult.Unconfirmed::class.java, result.javaClass)
        result as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, result.reason)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, result.phase)
        assertEquals(IOException::class.java, result.failure?.javaClass)
        assertEquals("before write block", result.failure?.message)
        assertEquals(setOf(result.command), result.localUnresolvedCommands)
    }

    @Test fun unpairedNewEpochsRejectWithoutChangingEitherFileOrCache() = runBlocking {
        val invalid = listOf("\uD800", "\uDC00", "x\uD800y", "x\uDC00y", "\uDC00\uD800", "\uD800\uD800\uDC00", "\uD800\uDC00\uDC00")
        for (axis in PurgeScope.entries) for (value in invalid) {
            val (o, file) = open(); o.data.updateData { raw(seals = if (axis == PurgeScope.USER) "[$user]" else "[$krx]") }
            val spec = if (axis == PurgeScope.USER) input(u = value) else input(targets = listOf(node(krx)), k = value)
            val before = disk(file); val writes = o.storage.writes
            val result = o.control.execute(command(o, spec), context)
            negative(result, RejectionReason.InvalidRequest("UnrepresentableJournalField"))
            assertEquals(before, disk(file)); assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            assertTrue(result.localUnresolvedCommands.isEmpty()); assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        }
    }

    @Test fun pairedSurrogatesAndBmpTextRoundTripWithExactlyTheConfirmedFence() = runBlocking {
        for (axis in PurgeScope.entries) for (value in listOf("ok-한글", "x\uD83D\uDE80y")) {
            val (o, file) = open(); o.data.updateData { raw(seals = if (axis == PurgeScope.USER) "[$user]" else "[$krx]") }
            val spec = if (axis == PurgeScope.USER) input(u = value) else input(targets = listOf(node(krx)), k = value)
            val c = command(o, spec); val result = confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
            assertEquals(result.snapshot.record.original, disk(file))
            assertEquals(value, disk(file)[if (axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH])
            assertEquals(value, result.settlement!!.after.epoch(axis))
            confirmed(o.control.execute(c), ConfirmedEffect.PostconditionConfirmed)
        }
    }

    @Test fun protobufFileSubstitutesRawUnpairedSurrogateWhileJsonCodecPreservesIt() = runBlocking {
        for (value in listOf("\uD800", "\uDC00")) {
            val (o, file) = open()
            val target = node(user.replace("\"u\"", JsonPrimitive(value).toString()))
            o.data.updateData { raw(seals = jsonArray(target)).toMutablePreferences().apply { this[USER_EPOCH] = value } }
            assertEquals(value, o.raw()[USER_EPOCH]); assertEquals("?", disk(file)[USER_EPOCH])
            val stored = ControlRecordReader().read(disk(file)) as ControlRecordRead.Supported
            assertEquals(value, (stored.arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted).value.let { (it as SealV1).key.epoch })
        }
    }

    @Test fun legacyRetirementCannotMakeWitnessOnlyEpochReusableOrReviveAnOldAppend() = runBlocking {
        val (o, file) = open(); o.data.updateData { raw() }
        confirmed(o.control.execute(command(o, input()), context), ConfirmedEffect.AppliedThisAttempt)
        val pending = NamespaceSettlementR3Fixtures.addition(o, user.replace("\"u\"", "\"$newUser\""))
        o.storage.before = true; failed(o.control.execute(pending))
        o.owner.beginRotation(true, false); o.owner.completePurges(o.owner.load().pendingPurges)
        assertNull(disk(file)[PURGE_JOURNAL])
        val current = disk(file)[USER_EPOCH]!!
        val add = o.control.execute(NamespaceSettlementR3Fixtures.addition(o, user.replace("\"u\"", "\"$current\"")))
        assertEquals(ControlStoreResult.Confirmed::class.java, add.javaClass)
        add as ControlStoreResult.Confirmed
        val target = (add.snapshot.record.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            .single { (it.value as SealV1).settlement == null }).original
        val spec = input(targets = listOf(target), before = fence.copy(userAccessEpoch = current), op = "second", did = "second-demand", u = newUser)
        val before = disk(file); val writes = o.storage.writes
        negative(o.control.execute(command(o, spec), context), RejectionReason.InvalidRequest("EpochNotFresh"))
        val retry = o.control.execute(pending); negative(retry, ConflictReason.TargetChanged)
        assertEquals(setOf(pending), retry.localUnresolvedCommands)
        assertEquals(before, disk(file)); assertEquals(writes, o.storage.writes)
        assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
    }

    @Test fun nullIsAnExactAppendOwnerOnBothAxesAndInBothDirections() = runBlocking {
        for (axis in PurgeScope.entries) for (saved in listOf(null, "A")) for (requested in listOf(null, "A")) {
            val (o, file) = open(); o.data.updateData { raw(seals = "[]").toMutablePreferences().apply {
                if (saved == null) remove(OWNER_UID) else this[OWNER_UID] = saved
            } }
            val json = NamespaceSettlementR3Fixtures.ownerJson(if (axis == PurgeScope.USER) user else krx, requested)
            val c = NamespaceSettlementR3Fixtures.addition(o, json); val before = disk(file); val writes = o.storage.writes
            val result = o.control.execute(c)
            if (saved == requested) {
                assertEquals(ControlStoreResult.Confirmed::class.java, result.javaClass)
                assertEquals(ConfirmedEffect.AppliedThisAttempt, (result as ControlStoreResult.Confirmed).effect)
            } else {
                negative(result, ConflictReason.TargetChanged); assertEquals(before, disk(file)); assertEquals(writes, o.storage.writes)
            }
            assertTrue(result.localUnresolvedCommands.isEmpty()); assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        }
    }

    @Test fun typedJournalDamageCannotBlockReconfirmationAfterLandingFailure() = runBlocking {
        val (o, file) = open(); o.data.updateData { raw() }; val c = command(o, input())
        o.storage.afterScope = true
        val failed = o.control.execute(c, context)
        assertEquals(ControlStoreResult.Unconfirmed::class.java, failed.javaClass)
        failed as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, failed.reason); assertEquals(ControlAttemptPhase.ConfirmingStorage, failed.phase)
        assertEquals(IOException::class.java, failed.failure?.javaClass); assertEquals("after completed write scope", failed.failure?.message)
        o.data.edit { it[intPreferencesKey(PURGE_JOURNAL.name)] = 1 }
        for (previous in listOf(false, true)) {
            val r = confirmed(if (previous) o.control.confirmPrevious(c) else o.control.execute(c), ConfirmedEffect.PostconditionConfirmed)
            assertEquals(mapOf("s" to JournalObservation.Uninterpretable), r.settlement!!.journal)
            assertEquals(1, disk(file)[intPreferencesKey(PURGE_JOURNAL.name)])
            assertTrue(r.localUnresolvedCommands.isEmpty()); assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        }
    }

    @Test fun appendGuardsDoNotExpandToNullNamespaceJoinsOrUnselectedEpochTypes() = runBlocking {
        for (axis in PurgeScope.entries) {
            val (fresh, _) = open()
            fresh.data.updateData { raw(seals = "[]").toMutablePreferences().apply {
                this[intPreferencesKey(if (axis == PurgeScope.USER) KRX_EPOCH.name else USER_EPOCH.name)] = 1
            } }
            val freshResult = fresh.control.execute(NamespaceSettlementR3Fixtures.addition(fresh, if (axis == PurgeScope.USER) user else krx))
            assertEquals(ControlStoreResult.Confirmed::class.java, freshResult.javaClass)
            assertEquals(ConfirmedEffect.AppliedThisAttempt, (freshResult as ControlStoreResult.Confirmed).effect)
            val (o, _) = open(); o.data.updateData { raw(seals = "[]").toMutablePreferences().apply {
                this[intPreferencesKey(OWNER_UID.name)] = 1
                this[intPreferencesKey(USER_EPOCH.name)] = 1
                this[intPreferencesKey(KRX_EPOCH.name)] = 1
            } }
            val nullJson = ControlObligationFixtures.nullSeal.replace("USER", axis.name)
            val added = o.control.execute(NamespaceSettlementR3Fixtures.addition(o, nullJson))
            assertEquals(ControlStoreResult.Confirmed::class.java, added.javaClass)
            assertEquals(ConfirmedEffect.AppliedThisAttempt, (added as ControlStoreResult.Confirmed).effect)
            val (joiner, _) = open(); val json = if (axis == PurgeScope.USER) user else krx
            joiner.data.updateData { raw(seals = "[$json]").toMutablePreferences().apply {
                this[intPreferencesKey(OWNER_UID.name)] = 1
                this[intPreferencesKey(USER_EPOCH.name)] = 1
                this[intPreferencesKey(KRX_EPOCH.name)] = 1
            } }
            val joined = joiner.control.execute(NamespaceSettlementR3Fixtures.addition(joiner, json))
            assertEquals(ControlStoreResult.Confirmed::class.java, joined.javaClass)
            assertEquals(ConfirmedEffect.JoinedExisting, (joined as ControlStoreResult.Confirmed).effect)
        }
    }
}
