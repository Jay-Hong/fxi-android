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
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.transition
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementOracle.witness
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

class NamespaceSettlementRevisionTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open() = ControlStoreTestStorage(File(folder.root, "revision-${opened.size}.preferences_pb")).also { opened += it }
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private fun ref(spec: RotateAndSettleNamespaces) = CommandRef(spec.operationId, ControlCommandBody.RotateAndSettle(spec))
    private fun decision(spec: RotateAndSettleNamespaces, source: Preferences = raw(), attempt: AttemptContext? = context,
        confirmOnly: Boolean = false) = transition.decide(ref(spec), spec,
        ControlRecordReader().read(source) as ControlRecordRead.Supported, attempt, confirmOnly, false)
    private fun refusal(spec: RotateAndSettleNamespaces, source: Preferences, reason: Any,
        attempt: AttemptContext? = context, confirmOnly: Boolean = false) {
        val result = decision(spec, source, attempt, confirmOnly)
        assertEquals(RecordTransactionDecision.Observe::class.java, result.javaClass)
        negative((result.value as ControlRecordStore.Outcome.Negative).result, reason)
    }
    private fun addition(o: ControlStoreTestStorage, json: String) = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
        literal(json); set("id", ControlScalar.Text(id))
    })

    @Test fun eachNewEpochSubconditionRejectsBothAxesBeforeConstruction() {
        for (axis in PurgeScope.entries) for (value in listOf(null, "", "u", "k", "bad|epoch", "bad\nepoch")) {
            val spec = if (axis == PurgeScope.USER) input(u = value) else input(targets = listOf(node(krx)), k = value)
            val reason = if (value?.contains('|') == true || value?.contains('\n') == true)
                "UnrepresentableJournalField" else "EpochNotFresh"
            assertEquals("$axis / $value", reason, spec.invalidInput())
            refusal(spec, raw(seals = if (axis == PurgeScope.USER) "[$user]" else "[$krx]"), RejectionReason.InvalidRequest(reason))
        }
    }

    @Test fun journalAndAllSealEpochsReserveBothAxesRegardlessOfOwnerOrSettlement() {
        val retiredUser = user.replace("\"s\"", "\"retired\"").replace("\"u\"", "\"retired-epoch\"")
        val retiredKrx = krx.replace("\"c\"", "\"retired\"").replace("\"k\"", "\"retired-epoch\"")
        val retiredWitness = witness().copy(operationId = "past", before = FenceV1("A", "retired-epoch", "k"),
            journal = JournalTargetV1("A", PurgeScope.USER, "retired-epoch"))
        val variants = listOf(
            raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "B|retired-epoch||USER" },
            raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "B||retired-epoch|CAPABILITY" },
            raw(seals = "[$user,$retiredUser]"), raw(seals = "[$user,$retiredKrx]"),
            raw(seals = jsonArray(node(user), withWitness(retiredUser, retiredWitness))),
            raw(seals = "[$user,{\"id\":\"opaque\",\"epoch\":\"retired-epoch\",\"future\":true}]")
        )
        for (source in variants) {
            refusal(input(u = "retired-epoch"), source, RejectionReason.InvalidRequest("EpochNotFresh"))
            // Same exclusions when CAPABILITY receives the old USER or CAPABILITY epoch.
            val capSource = source.toMutablePreferences().apply { this[SEAL] = this[SEAL]!!.replace(user, krx) }
            refusal(input(targets = listOf(node(krx)), k = "retired-epoch"), capSource, RejectionReason.InvalidRequest("EpochNotFresh"))
        }
    }

    @Test fun injectedUuidCannotRevivePendingEpochAndRefusalDoesNotWrite() = runBlocking {
        val o = open(); o.data.updateData { raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "B||$newUser|CAPABILITY" } }
        var next = 0L; val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++next) })
        val c = store.prepareRotation(listOf(node(user)), fence, life, demand)
        val before = o.raw(); val writes = o.storage.writes
        repeat(2) { negative(store.execute(c, context), RejectionReason.InvalidRequest("EpochNotFresh")) }
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes); assertEquals(3L, next)
        assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
    }

    @Test fun nonSealAndOpaqueTargetsCannotBeDroppedInEitherOrder() {
        for (bad in listOf(node(ControlObligationFixtures.request), node("{\"id\":\"opaque\",\"future\":true}"))) {
            for (targets in listOf(listOf(bad), listOf(node(user), bad), listOf(bad, node(user)))) {
                val spec = input(targets = targets)
                assertEquals("UnsupportedTargetKind", spec.invalidInput())
                refusal(spec, raw(), RejectionReason.InvalidRequest("UnsupportedTargetKind"))
            }
        }
    }

    @Test fun missingContextRejectsNewApplicationButCanReconfirmItsWitness() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = command(o, input())
        val before = o.raw(); val writes = o.storage.writes
        val rejected = o.control.execute(c)
        negative(rejected, RejectionReason.InvalidRequest("AttemptContextRequired"))
        assertTrue(rejected.localUnresolvedCommands.isEmpty()); assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
        // Existing unresolved membership must survive the same rejection.
        o.storage.before = true
        val failed = o.control.execute(c, context)
        assertEquals(ControlStoreResult.Unconfirmed::class.java, failed.javaClass)
        assertEquals(setOf(c), o.control.execute(c).localUnresolvedCommands)
        val first = confirmed(o.control.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
        val repeat = confirmed(o.control.execute(c), ConfirmedEffect.PostconditionConfirmed)
        val previous = confirmed(o.control.confirmPrevious(c), ConfirmedEffect.PostconditionConfirmed)
        for (result in listOf(first, repeat, previous)) {
            assertEquals(listOf("s", demandId), result.effectiveIds)
            val error = runCatching { (result.effectiveIds as MutableList<String>).add("bad") }.exceptionOrNull()
            assertEquals(UnsupportedOperationException::class.java, error?.javaClass); assertNull(error?.message)
        }
    }

    @Test fun confirmationOnlyCannotConstructMissingEffectEvenWithValidContext() {
        refusal(input(), raw(), ConflictReason.TargetMissing, context, confirmOnly = true)
    }

    @Test fun commandIdentityMustEqualPersistedOperationIdentity() {
        val error = runCatching { CommandRef("wrong", ControlCommandBody.RotateAndSettle(input())) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("rotation command id must equal operationId", error?.message)
        assertEquals(operation, ref(input()).id)
    }

    @Test fun capabilityAndBothAxisPreparationIssueFixedIndependentUuids() = runBlocking {
        for (both in listOf(false, true)) {
            val o = open(); val targets = if (both) listOf(node(user), node(krx)) else listOf(node(krx))
            o.data.updateData { raw(seals = jsonArray(*targets.toTypedArray())) }
            var next = 0L; val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, ++next) })
            val c = store.prepareRotation(targets, fence, life, demand)
            val spec = (c.body as ControlCommandBody.RotateAndSettle).input
            assertEquals(operation, c.id); assertEquals(demandId, spec.demandId)
            assertEquals(if (both) newUser else null, spec.newUserEpoch)
            assertEquals(if (both) newKrx else newUser, spec.newKrxEpoch)
            assertEquals(if (both) 4L else 3L, next)
            o.storage.before = true
            assertEquals(ControlStoreResult.Unconfirmed::class.java, store.execute(c, context).javaClass)
            val result = confirmed(store.execute(c, context), ConfirmedEffect.AppliedThisAttempt)
            assertEquals(if (both) newUser else "u", result.snapshot.record.original[USER_EPOCH])
            assertEquals(if (both) newKrx else newUser, result.snapshot.record.original[KRX_EPOCH])
            assertEquals(if (both) listOf("s", "c", demandId) else listOf("c", demandId), result.effectiveIds)
            confirmed(store.execute(c), ConfirmedEffect.PostconditionConfirmed)
            assertEquals(if (both) 4L else 3L, next)
        }
    }

    @Test fun capabilityAppendUsesCapabilityTypeAndCurrentEpoch() = runBlocking {
        for (value in listOf<Any>("retired", 1)) {
            val o = open(); o.data.updateData { raw(seals = "[]").toMutablePreferences().apply {
                if (value is String) this[KRX_EPOCH] = value else this[intPreferencesKey(KRX_EPOCH.name)] = value as Int
            } }
            val c = addition(o, krx); val before = o.raw(); val writes = o.storage.writes
            negative(o.control.execute(c), if (value is String) ConflictReason.TargetChanged else RecoveryReason.UnreadableEpochState)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        }
        val o = open(); o.data.updateData { raw(seals = "[]").toMutablePreferences().apply { this[intPreferencesKey(USER_EPOCH.name)] = 1 } }
        assertEquals(ConfirmedEffect.AppliedThisAttempt, (o.control.execute(addition(o, krx)) as ControlStoreResult.Confirmed).effect)
    }

    @Test fun landedOwnNamespaceAdditionConfirmsAfterFenceChangesWithoutAppendGuard() = runBlocking {
        for (previous in listOf(false, true)) for (json in listOf(user, krx)) {
            val o = open(); o.data.updateData { raw(seals = "[]") }; val c = addition(o, json)
            o.storage.afterScope = true
            val failed = o.control.execute(c) as ControlStoreResult.Unconfirmed
            assertEquals(IOException::class.java, failed.failure?.javaClass)
            assertEquals("after completed write scope", failed.failure?.message)
            val checkpoint = o.control.checkpoint(c)!!
            o.data.edit { it[OWNER_UID] = "B"; it[USER_EPOCH] = "later-user"; it[KRX_EPOCH] = "later-krx" }
            val before = o.raw(); val writes = o.storage.writes
            val result = (if (previous) o.control.confirmPrevious(c, checkpoint) else o.control.execute(c)) as ControlStoreResult.Confirmed
            assertEquals(ConfirmedEffect.PostconditionConfirmed, result.effect)
            assertTrue(result.localUnresolvedCommands.isEmpty())
            assertEquals(before.toMutablePreferences().apply { this[BARRIER] = 1L }, o.raw())
            assertEquals(writes + 1, o.storage.writes)
        }
    }

    @Test fun oldJournalFieldsRejectEmptyAndNewlineWhileNullOwnerRoundTrips() {
        for (owner in listOf("", "A\nB")) {
            val target = node(user.replace("\"A\"", kotlinx.serialization.json.JsonPrimitive(owner).toString()))
            val spec = input(targets = listOf(target), before = fence.copy(ownerUid = owner), request = demand.copy(ownerUid = owner))
            assertEquals("UnrepresentableJournalField", spec.invalidInput())
            refusal(spec, raw(seals = jsonArray(target)).toMutablePreferences().apply { this[OWNER_UID] = owner },
                RejectionReason.InvalidRequest("UnrepresentableJournalField"), context.copy(ownerUid = owner))
        }
        for (axis in PurgeScope.entries) {
            val target = node((if (axis == PurgeScope.USER) user else krx).replace(if (axis == PurgeScope.USER) "\"u\"" else "\"k\"", "\"old\\nline\""))
            val before = if (axis == PurgeScope.USER) fence.copy(userAccessEpoch = "old\nline") else fence.copy(krxCapabilityEpoch = "old\nline")
            val spec = input(targets = listOf(target), before = before)
            assertEquals("UnrepresentableJournalField", spec.invalidInput())
        }
        val nullTarget = node(user.replace("\"A\"", "null"))
        val spec = input(targets = listOf(nullTarget), before = fence.copy(ownerUid = null), request = demand.copy(ownerUid = null))
        val source = raw(seals = jsonArray(nullTarget)).toMutablePreferences().apply { remove(OWNER_UID) }
        val result = decision(spec, source, context.copy(ownerUid = null)) as RecordTransactionDecision.Confirm
        assertEquals("|u||USER", result.candidate[PURGE_JOURNAL])
    }

    @Test fun receiptReportsBothInterpretabilityPolaritiesAcrossAllArrays() {
        for (kind in listOf<ControlKind?>(null, ControlKind.HOLD, ControlKind.RECOVERY_INTENT, ControlKind.DEMAND)) {
            val source = raw().toMutablePreferences().apply { if (kind != null) this[ControlRecordKeys.payload(kind)] = "[{\"id\":\"opaque\",\"future\":true}]" }
            val result = decision(input(), source) as RecordTransactionDecision.Confirm
            assertEquals(kind != null, (result.value as ControlRecordStore.Outcome.Positive).settlement!!.hasUninterpretable)
        }
    }

    @Test fun nonCoveringJournalEntriesAreAbsentForEachIndependentDimension() {
        for (axis in PurgeScope.entries) {
            val spec = if (axis == PurgeScope.USER) input() else input(targets = listOf(node(krx)))
            val source = settled(spec, raw(seals = if (axis == PurgeScope.USER) "[$user]" else "[$krx]"))
            val lines = if (axis == PurgeScope.USER) listOf("B|u||USER", "A|u||CAPABILITY", "A|other||USER")
                else listOf("B||k|CAPABILITY", "A||k|USER", "A||other|CAPABILITY")
            for (line in lines) {
                val changed = source.toMutablePreferences().apply { this[PURGE_JOURNAL] = line }
                val result = decision(spec, changed, null) as RecordTransactionDecision.Confirm
                assertEquals(mapOf((if (axis == PurgeScope.USER) "s" else "c") to JournalObservation.Absent),
                    (result.value as ControlRecordStore.Outcome.Positive).settlement!!.journal)
            }
        }
    }

    @Test fun resolveEveryTargetBeforeInspectingAnyPartialWitness() {
        val spec = input(targets = listOf(node(user), node(krx)))
        val first = withWitness(user, witness(both = true))
        refusal(spec, raw(seals = jsonArray(first)), ConflictReason.TargetMissing)
        refusal(spec, raw(seals = jsonArray(first, node("{\"id\":\"c\",\"future\":true}"))), ConflictReason.UninterpretableTarget)
        refusal(spec, raw(seals = jsonArray(first), requests = "[{\"id\":\"c\",\"future\":true}]"), ConflictReason.IdCollision)
        refusal(spec, raw(seals = jsonArray(first, node(krx), node(krx))), ConflictReason.IdCollision)
        // Reverse order proves resolution does not depend on which target owns the witness.
        val reversed = input(targets = listOf(node(krx), node(user)))
        refusal(reversed, raw(seals = jsonArray(first)), ConflictReason.TargetMissing)
    }

    @Test fun candidatePreservesDemandSealHoldAndRecoveryBeforeSelfValidation() {
        val source = raw(requests = "[${ControlObligationFixtures.request}]").toMutablePreferences().apply {
            this[HOLD] = "[  ${ControlObligationFixtures.hold}  ]"
            this[RECOVERY] = "[ ${ControlObligationFixtures.recovery} ]"
        }
        val built = transition.buildCandidate(input(), ControlRecordReader().read(source) as ControlRecordRead.Supported)
            as NamespaceSettlementTransition.CandidateBuild.Built
        // Deliberately assert the unvalidated candidate; G30 cannot mask a missing obligation.
        assertEquals("retained settled seal", jsonArray(withWitness(user, witness())), built.candidate[SEAL])
        assertEquals("preserved and handed-over demand", "[${ControlObligationFixtures.request},{\"id\":\"$demandId\",\"kind\":\"REQUEST\",\"ownerUid\":\"A\",\"binding\":3,\"originLifetimeId\":\"life\",\"raisedAt\":7,\"intent\":\"FORCE_PREMIUM\"}]", built.candidate[DEMAND])
        assertEquals("verbatim HOLD", source[HOLD], built.candidate[HOLD])
        assertEquals("verbatim RECOVERY", source[RECOVERY], built.candidate[RECOVERY])
        assertEquals(source[SEAL], "[$user]")
    }

    @Test fun invalidOrderWinsOverAdmissionAndExistingWitness() {
        val bad = input(request = demand.copy(raisedAt = EventOrderV1(life, -1)))
        refusal(bad, raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }, RejectionReason.InvalidRequest("InvalidDemand"))
        refusal(bad, raw(seals = jsonArray(withWitness(user, witness()))), RejectionReason.InvalidRequest("InvalidDemand"))
    }
}
