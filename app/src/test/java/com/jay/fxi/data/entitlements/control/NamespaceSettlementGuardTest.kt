package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.life
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.fence
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.context
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demand
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.operation
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.demandId
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.newUser
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.user
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.krx
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.input
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.raw
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.command
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.confirmed
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.negative
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.jsonArray
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.replace
import com.jay.fxi.data.entitlements.control.NamespaceSettlementFixtures.withWitness
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Each row has a successful control, exact negative reason, full raw equality and lease checks. */
@RunWith(Parameterized::class)
class NamespaceSettlementGuardTest(private val guard: String) {
    @get:Rule val folder = TemporaryFolder()

    @Test fun refusesOnlyTheBrokenPrecondition() = runBlocking {
        // Passing contrast catches an always-reject implementation in every parameter row.
        val good = ControlStoreTestStorage(File(folder.root, "good.preferences_pb"))
        try {
            good.data.updateData { raw() }
            confirmed(good.control.execute(command(good, input()), context), ConfirmedEffect.AppliedThisAttempt)
            assertTrue(ControlCommandTracking.forOwner(good.owner).executing.isEmpty())
        } finally { good.close() }

        var request = input()
        var snapshot = raw().toMutablePreferences()
        var attempt = context
        var expected: Any = ConflictReason.TargetChanged
        fun invalid(code: String) { expected = RejectionReason.InvalidRequest(code) }
        fun rawSeal(value: String) { snapshot[ControlRecordKeys.payload(ControlKind.SEAL)] = value }
        when (guard) {
            "G01" -> { request = input(targets = emptyList()); invalid("EmptyTargets") }
            "G02" -> { request = input(targets = listOf(node(user), node(user))); invalid("DuplicateTargetId") }
            "G03" -> { request = input(targets = listOf(node(user), node(user.replace("\"s\"", "\"t\"")))); invalid("DuplicateAxis") }
            "G04" -> { request = input(targets = listOf(node(ControlObligationFixtures.nullSeal))); invalid("UnsupportedTargetKind") }
            "G05" -> { request = input(targets = listOf(withWitness(user, request.witness(request.seals.single())))); invalid("AlreadySettledInput") }
            "G06.owner" -> { request = input(before = fence.copy(ownerUid = "B"), request = demand.copy(ownerUid = "B")); invalid("TargetFenceMismatch") }
            "G06.epoch" -> { request = input(before = fence.copy(userAccessEpoch = "different")); invalid("TargetFenceMismatch") }
            "G07.oldEpoch" -> { request = input(u = "u"); invalid("EpochNotFresh") }
            "G07.otherNewEpoch" -> { request = input(targets = listOf(node(user), node(krx)), k = newUser); invalid("EpochNotFresh") }
            "G08.owner" -> { request = input(request = demand.copy(ownerUid = "B")); invalid("DemandScopeMismatch") }
            "G08.origin" -> { request = input(request = demand.copy(raisedAt = EventOrderV1(LifetimeId("other"), 7))); invalid("DemandScopeMismatch") }
            "G09.binding" -> { request = input(request = demand.copy(binding = -1)); invalid("InvalidDemand") }
            "G09.order" -> { request = input(request = demand.copy(raisedAt = EventOrderV1(life, -1))); invalid("InvalidDemand") }
            "G10.USER" -> { request = input(request = demand.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)); invalid("InsufficientIntent") }
            "G10.CAPABILITY" -> { request = input(targets = listOf(node(krx)), request = demand.copy(intent = RefreshIntent.IF_STALE)); invalid("InsufficientIntent") }
            "G11.owner" -> {
                request = input(targets = listOf(node(user.replace("\"A\"", "\"A|B\""))), before = fence.copy(ownerUid = "A|B"), request = demand.copy(ownerUid = "A|B"))
                invalid("UnrepresentableJournalField")
            }
            "G11.userEpoch" -> {
                request = input(targets = listOf(node(user.replace("\"u\"", "\"u|v\""))), before = fence.copy(userAccessEpoch = "u|v"))
                invalid("UnrepresentableJournalField")
            }
            "G11.krxEpoch" -> {
                request = input(targets = listOf(node(krx.replace("\"k\"", "\"k|v\""))), before = fence.copy(krxCapabilityEpoch = "k|v"))
                invalid("UnrepresentableJournalField")
            }
            "G12.legacy" -> {
                snapshot.remove(SCHEMA); ControlRecordKeys.allPayloads.forEach { snapshot.remove(ControlRecordKeys.payload(it)) }
                expected = RecoveryReason.MigrationOrRecovery
            }
            "G12.future" -> { snapshot[SCHEMA] = 3; expected = RecoveryReason.UnreadableRecord }
            "G12.missingV2" -> { snapshot.remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)); expected = RecoveryReason.UnreadableRecord }
            "G12.writerV1" -> { snapshot = raw(schema = 1).toMutablePreferences(); expected = RecoveryReason.ControlSchemaMigrationRequired }
            "G13.missing" -> { rawSeal("[]"); expected = ConflictReason.TargetMissing }
            "G13.duplicate" -> { rawSeal("[$user,$user]"); expected = ConflictReason.IdCollision }
            "G13.wrongArray" -> {
                rawSeal("[]"); snapshot[ControlRecordKeys.payload(ControlKind.HOLD)] = "[$user]"; expected = ConflictReason.IdCollision
            }
            "G13.opaque" -> { rawSeal("[{\"id\":\"s\",\"future\":true}]"); expected = ConflictReason.UninterpretableTarget }
            "G14.kind" -> rawSeal("[${ControlObligationFixtures.nullSeal}]")
            "G14.owner" -> rawSeal("[${user.replace("\"A\"", "\"B\"")}]")
            "G14.axis" -> rawSeal("[${user.replace("USER", "CAPABILITY")}]")
            "G14.epoch" -> rawSeal("[${user.replace("\"u\"", "\"v\"")}]")
            "G15" -> { rawSeal("[${user.replace("\"s\"", "\"replacement\"")}]"); expected = ConflictReason.TargetMissing }
            "G16" -> rawSeal(jsonArray(withWitness(user, request.witness(request.seals.single()).copy(operationId = "other-operation"))))
            "G17" -> {
                request = input(targets = listOf(node(user), node(krx)))
                rawSeal(jsonArray(withWitness(user, request.witness(request.seals.first())), node(krx)))
                expected = RecoveryReason.InconsistentSettlement
            }
            "G19" -> {
                val original = user.replace("\"A\"", "null")
                request = input(targets = listOf(node(original)), before = fence.copy(ownerUid = null), request = demand.copy(ownerUid = null))
                rawSeal(jsonArray(withWitness(user, request.witness(request.seals.single()))))
                expected = RecoveryReason.InconsistentSettlement
            }
            "G20" -> {
                val other = user.replace("\"s\"", "\"other\"")
                rawSeal(jsonArray(node(user), withWitness(other, request.witness(request.seals.single()))))
                expected = ConflictReason.OperationIdCollision
            }
            "G22.owner" -> snapshot[OWNER_UID] = "B"
            "G22.userEpoch" -> snapshot[USER_EPOCH] = "newer-u"
            "G22.krxEpoch" -> snapshot[KRX_EPOCH] = "newer-k"
            "G23" -> { snapshot[TEARDOWN_OWED_FOR] = "A"; expected = ConflictReason.IdentityTransitionPending }
            "G24.signOut" -> { attempt = context.copy(signOutOpen = true); expected = ConflictReason.IdentityTransitionPending }
            "G24.identityPending" -> { attempt = context.copy(identityPersistencePending = true); expected = ConflictReason.IdentityTransitionPending }
            "G25.owner" -> attempt = context.copy(ownerUid = "B")
            "G25.binding" -> attempt = context.copy(binding = 4)
            "G25.origin" -> attempt = context.copy(originLifetimeId = LifetimeId("other"))
            else -> when {
                guard.startsWith("G21.") -> {
                    val key = mapOf("owner" to OWNER_UID, "userEpoch" to USER_EPOCH, "krxEpoch" to KRX_EPOCH,
                        "premiumMarker" to MAY_CONTAIN_PREMIUM, "krxMarker" to MAY_CONTAIN_KRX,
                        "teardown" to TEARDOWN_OWED_FOR, "journal" to PURGE_JOURNAL).getValue(guard.substringAfter('.'))
                    snapshot[intPreferencesKey(key.name)] = 1
                    expected = RecoveryReason.UnreadableEpochState
                }
                guard.startsWith("G26.") -> {
                    snapshot[PURGE_JOURNAL] = mapOf("empty" to "", "malformed" to "A|u|USER", "order" to "A|u|k|USER,CAPABILITY",
                        "duplicate" to "A|u||USER,USER", "five" to "A|u||USER|UNKNOWN", "newline" to "A|u||USER\n").getValue(guard.substringAfter('.'))
                    expected = RecoveryReason.JournalMigrationRequired
                }
                guard.startsWith("G27.") -> {
                    val kind = ControlKind.valueOf(guard.substringAfter('.'))
                    val colliding = "{\"id\":\"$demandId\"}"
                    snapshot[ControlRecordKeys.payload(kind)] = if (kind == ControlKind.SEAL) "[$user,$colliding]" else "[$colliding]"
                    expected = ConflictReason.IdCollision
                }
                else -> error(guard)
            }
        }
        if (guard.substringBefore('.') in setOf("G01", "G02", "G03", "G04", "G05", "G06", "G07", "G08", "G09", "G10", "G11")) {
            assertNull(input().invalidInput())
            assertEquals((expected as RejectionReason.InvalidRequest).detail, request.invalidInput())
        }
        val o = ControlStoreTestStorage(File(folder.root, "broken.preferences_pb"))
        try {
            o.data.updateData { snapshot }
            val command = command(o, request)
            val before = o.raw(); val writes = o.storage.writes
            fun checkObservation(result: ControlStoreResult) {
                val observed = when (result) {
                    is ControlStoreResult.Conflict -> {
                        assertEquals(request.seals.map { it.id }, result.expected.effectiveIds)
                        result.observation
                    }
                    is ControlStoreResult.Rejected -> result.observation
                    is ControlStoreResult.RecoveryRequired -> result.observation
                    else -> error("unexpected result: $result")
                }
                assertEquals(before, observed?.original)
            }
            repeat(2) {
                val result = o.control.execute(command, attempt)
                negative(result, expected)
                checkObservation(result)
                assertSame(command, result.command)
                assertTrue(result.localUnresolvedCommands.isEmpty())
                assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
                assertTrue(ControlCommandTracking.forOwner(o.owner).executing.isEmpty())
            }
            // An already unresolved command and owner read-back requirement survive every rejection.
            val tracking = ControlCommandTracking.forOwner(o.owner)
            tracking.markUnresolved(command)
            val failure = runCatching { o.owner.transactRecord<Unit> { throw IOException("prime read-back") } }.exceptionOrNull()
            assertEquals(IOException::class.java, failure?.javaClass); assertEquals("prime read-back", failure?.message)
            val result = o.control.execute(command, attempt)
            negative(result, expected)
            checkObservation(result)
            assertEquals(setOf(command), result.localUnresolvedCommands)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            assertEquals(before[BARRIER], o.raw()[BARRIER])
            assertTrue(tracking.executing.isEmpty())
        } finally { o.close() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<String>> = (
            listOf("G01", "G02", "G03", "G04", "G05", "G06.owner", "G06.epoch", "G07.oldEpoch", "G07.otherNewEpoch",
                "G08.owner", "G08.origin", "G09.binding", "G09.order", "G10.USER", "G10.CAPABILITY",
                "G11.owner", "G11.userEpoch", "G11.krxEpoch", "G12.legacy", "G12.future", "G12.missingV2", "G12.writerV1",
                "G13.missing", "G13.duplicate", "G13.wrongArray", "G13.opaque", "G14.kind", "G14.owner", "G14.axis", "G14.epoch",
                "G15", "G16", "G17", "G19", "G20", "G22.owner", "G22.userEpoch", "G22.krxEpoch", "G23",
                "G24.signOut", "G24.identityPending", "G25.owner", "G25.binding", "G25.origin") +
                listOf("owner", "userEpoch", "krxEpoch", "premiumMarker", "krxMarker", "teardown", "journal").map { "G21.$it" } +
                listOf("empty", "malformed", "order", "duplicate", "five", "newline").map { "G26.$it" } +
                ControlKind.entries.map { "G27.${it.name}" }
            ).map { arrayOf(it) }
    }
}
