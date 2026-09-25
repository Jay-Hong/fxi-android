package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Claude-owned 5e-2 retirement-relation contract (plan v1 row 3): the §7.2 relations that C1
 * (RecoverIntentWriterContractTest) does not cover, through the real FileStorage path. Design
 * `d2b5_demand_auth_design_r3_codex.md` L412–419 (table rows and SEAL preservation), L421 (REQUEST
 * lower bound only when a new REQUEST is needed), L440 (departed owner: no new REQUEST). Expected
 * values are literals from those lines and the canonical 4-field journal encoding. Exact null owner
 * is exercised as a source value (null ≠ current A → departed), never as "any owner".
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentRetirementContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent retirement cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)

    private val newLife = LifetimeId("new-life")
    private fun executor(owner: String) = SettlementExecutor(owner, 3, newLife)
    private fun binding(owner: String) = LifecycleBinding(executor(owner), IdentityV1(owner, 2), 1, "binding-start")
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String = "r", session: String = "session"): ControlNode {
        val node = ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
        check(ControlSchema.read(ControlKind.RECOVERY_INTENT, node) is RecoveryIntentV1) { "fixture must be a schema-valid RECOVERY_INTENT" }
        return node
    }
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    /** A null epoch removes that key (current axis epoch absent). */
    private fun record(user: String?, krx: String?, intents: List<ControlNode>, vararg demand: ControlNode): Preferences =
        ControlLifecycleEvidenceFixtures.raw(demand = payload(demand.toList())).toMutablePreferences().apply {
            this[OWNER_UID] = "A"
            if (user == null) remove(USER_EPOCH) else this[USER_EPOCH] = user
            if (krx == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = krx
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(intents)
        }.toPreferences()
    private fun closure(source: ControlNode) = HoldRecoveryClosure.AfterRestart(source, executor("A"), "old-tracking", true, true)
    private fun context(source: ControlNode) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding("A"), 5, true, emptySet(), closure(source)))
    private fun input(source: ControlNode, before: FenceV1) = RecoverIntentInput(source, before, binding("A"), closure(source))
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
    private fun interpreted(p: Preferences, kind: ControlKind) =
        FloorGuardFixtures.read(p).arrays.getValue(kind).entries.filterIsInstance<ControlEntryRead.Interpreted>()
    private fun canonicalUuid(s: String?) = s != null && runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)

    /** Seeds, prepares with a fresh supplier and executes; returns (command, supplier, disk after). */
    private suspend fun run(tag: String, source: ControlNode, before: FenceV1, seeded: Preferences):
        Triple<CommandRef, LifecycleOrderSource, Pair<ControlStoreResult, Preferences>> {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("$tag seed") { s.data.updateData { seeded } }
        val orders = LifecycleOrderSource(newLife, 21)
        val c = s.control.prepareRecoverIntent(input(source, before), orders)
        val result = controlTestTimeout("$tag execute") { s.control.execute(c, context(source)) }
        return Triple(c, orders, result to disk(file))
    }

    // ---- R1: C0b with both target and current null (L413 "현재 epoch가 null이든 비null이든") ----

    @Test fun R1_nullTargetAndNullCurrentRotate() = runReleaseTest {
        val source = intent("A", "CAPABILITY", null)
        val (c, _, out) = run("R1", source, FenceV1("A", "u", null), record("u", null, listOf(source)))
        val (result, after) = out
        val ids = plan(c).ids
        assertTrue(atomic("R1_confirmed"), result is ControlStoreResult.Confirmed)
        assertTrue(atomic("R1_freshUuid"), canonicalUuid(ids.epochs.capability))
        assertEquals(atomic("R1_rotated"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(atomic("R1_marker"), false, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("R1_journal"), "A|||CAPABILITY", after[PURGE_JOURNAL])
        val fresh = interpreted(after, ControlKind.DEMAND).map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("R1_strength"), FORCE_ENTITLEMENTS, fresh.intent)
        assertTrue(atomic("R1_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    // ---- R2: C0c for a departed owner — preserve, exact source journal, no REQUEST (L414 "현재 owner일 때만", L440) ----

    @Test fun R2_departedDifferentTargetPreservesWithoutRequest() = runReleaseTest {
        val source = intent("B", "CAPABILITY", "k0")
        val kept = DemandAuthFixtures.request(id = "ra", owner = "A")
        val (c, orders, out) = run("R2", source, FenceV1("A", "u", "k"), record("u", "k", listOf(source), kept))
        val (result, after) = out
        assertTrue(atomic("R2_confirmed"), result is ControlStoreResult.Confirmed)
        assertNull(atomic("R2_noOrder"), plan(c).requestOrder)
        assertEquals(atomic("R2_supplierUntouched"), 21L, checkNotNull(orders.issue(bindingStart = 1)).previous)
        assertEquals(atomic("R2_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("R2_marker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("R2_journal"), "B||k0|CAPABILITY", after[PURGE_JOURNAL])
        val rows = interpreted(after, ControlKind.DEMAND)
        assertEquals(atomic("R2_rows"), 1, rows.size)
        assertEquals(atomic("R2_keep"), kept.toPayloadEntry(), rows.single().original.toPayloadEntry())
        assertTrue(atomic("R2_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    @Test fun R1u_userNullTargetRotatesForEitherCurrent() = runReleaseTest {
        for (current in listOf<String?>(null, "u")) {
            val source = intent("A", "USER", null)
            val (c, _, out) = run("R1u", source,
                FenceV1("A", current, "k"), record(current, "k", listOf(source)))
            val (result, after) = out
            val ids = plan(c).ids
            assertTrue(atomic("R1u_confirmed"), result is ControlStoreResult.Confirmed)
            assertTrue(atomic("R1u_freshUuid"), canonicalUuid(ids.epochs.user))
            assertEquals(atomic("R1u_rotated"), ids.epochs.user, after[USER_EPOCH])
            assertEquals(atomic("R1u_marker"), false, after[MAY_CONTAIN_PREMIUM])
            assertEquals(atomic("R1u_owner"), "A", after[OWNER_UID])
            assertEquals(atomic("R1u_krx"), "k", after[KRX_EPOCH])
            assertEquals(atomic("R1u_krxMarker"), true, after[MAY_CONTAIN_KRX])
            assertEquals(atomic("R1u_journal"), "A|||USER", after[PURGE_JOURNAL])
            val request = interpreted(after, ControlKind.DEMAND)
                .map { it.value }.filterIsInstance<DemandV1>()
                .single { it.id == ids.requestId }
            assertEquals(atomic("R1u_strength"), FORCE_PREMIUM, request.intent)
            assertTrue(atomic("R1u_sourceRemoved"),
                interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
        }
    }

    @Test fun R2u_userDepartedDifferentTargetPreserves() = runReleaseTest {
        val source = intent("B", "USER", "u0")
        val kept = DemandAuthFixtures.request(id = "ra", owner = "A")
        val (c, orders, out) = run("R2u", source,
            FenceV1("A", "u", "k"), record("u", "k", listOf(source), kept))
        val (result, after) = out
        assertTrue(atomic("R2u_confirmed"), result is ControlStoreResult.Confirmed)
        assertNull(atomic("R2u_noOrder"), plan(c).requestOrder)
        assertEquals(atomic("R2u_supplierUntouched"),
            21L, checkNotNull(orders.issue(bindingStart = 1)).previous)
        assertEquals(atomic("R2u_owner"), "A", after[OWNER_UID])
        assertEquals(atomic("R2u_user"), "u", after[USER_EPOCH])
        assertEquals(atomic("R2u_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("R2u_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("R2u_krxMarker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("R2u_journal"), "B|u0||USER", after[PURGE_JOURNAL])
        val rows = interpreted(after, ControlKind.DEMAND)
        assertEquals(atomic("R2u_rows"), 1, rows.size)
        assertEquals(atomic("R2u_keep"),
            kept.toPayloadEntry(), rows.single().original.toPayloadEntry())
        assertTrue(atomic("R2u_sourceRemoved"),
            interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    // ---- R3–R6: the USER-axis rows (L414–L417; USER lower bound FORCE_PREMIUM L421) ----

    @Test fun R3_userDifferentTargetCurrentOwner() = runReleaseTest {
        val source = intent("A", "USER", "u0")
        val (c, _, out) = run("R3", source, FenceV1("A", "u", "k"), record("u", "k", listOf(source)))
        val (result, after) = out
        val ids = plan(c).ids
        assertTrue(atomic("R3_confirmed"), result is ControlStoreResult.Confirmed)
        assertNull(atomic("R3_noFreshUser"), ids.epochs.user)
        assertEquals(atomic("R3_user"), "u", after[USER_EPOCH])
        assertEquals(atomic("R3_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("R3_journal"), "A|u0||USER", after[PURGE_JOURNAL])
        val fresh = interpreted(after, ControlKind.DEMAND).map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("R3_strength"), FORCE_PREMIUM, fresh.intent)
    }

    @Test fun R4_userDepartedNullTarget() = runReleaseTest {
        val source = intent("B", "USER", null)
        val (c, _, out) = run("R4", source, FenceV1("A", "u", "k"), record("u", "k", listOf(source)))
        val (result, after) = out
        assertTrue(atomic("R4_confirmed"), result is ControlStoreResult.Confirmed)
        assertEquals(atomic("R4_user"), "u", after[USER_EPOCH])
        assertEquals(atomic("R4_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("R4_journal"), "B|||USER", after[PURGE_JOURNAL])
        assertTrue(atomic("R4_noRequest"), interpreted(after, ControlKind.DEMAND).none { it.value.id == plan(c).ids.requestId })
    }

    @Test fun R5_userDepartedEqualTargetRejected() = runReleaseTest {
        val source = intent("B", "USER", "u")
        val seeded = record("u", "k", listOf(source))
        val (_, _, out) = run("R5", source, FenceV1("A", "u", "k"), seeded)
        val (result, after) = out
        assertTrue(eligible("R5_conflict"), result is ControlStoreResult.Conflict)
        assertEquals(eligible("R5_reason"), ConflictReason.TargetChanged, (result as ControlStoreResult.Conflict).reason)
        assertEquals(eligible("R5_sourceKept"), seeded[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)],
            after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertEquals(eligible("R5_user"), "u", after[USER_EPOCH])
    }

    @Test fun R6_userUnreadableCurrentEpoch() = runReleaseTest {
        val source = intent("A", "USER", "t")
        val seeded = record(null, "k", listOf(source))
        val (_, _, out) = run("R6", source, FenceV1("A", null, "k"), seeded)
        val (result, after) = out
        assertTrue(eligible("R6_recovery"), result is ControlStoreResult.RecoveryRequired)
        assertEquals(eligible("R6_reason"), RecoveryReason.UnreadableEpochState, (result as ControlStoreResult.RecoveryRequired).reason)
        assertEquals(eligible("R6_sourceKept"), seeded[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)],
            after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
    }

    // ---- R7: exact null owner — null is a value, not "any owner" (L436 step 5 "null owner도 exact 값") ----

    @Test fun R7_nullOwnerIsDepartedFromCurrentA() = runReleaseTest {
        val nullTarget = intent(null, "CAPABILITY", null)
        val (c, orders, out) = run("R7d", nullTarget, FenceV1("A", "u", "k"), record("u", "k", listOf(nullTarget)))
        val (result, after) = out
        assertTrue(atomic("R7_departedConfirmed"), result is ControlStoreResult.Confirmed)
        assertNull(atomic("R7_noOrder"), plan(c).requestOrder)
        assertEquals(atomic("R7_supplierUntouched"), 21L, checkNotNull(orders.issue(bindingStart = 1)).previous)
        assertEquals(atomic("R7_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("R7_journal"), "|||CAPABILITY", after[PURGE_JOURNAL])
        assertTrue(atomic("R7_noRequest"), interpreted(after, ControlKind.DEMAND).isEmpty())
        val equalTarget = intent(null, "CAPABILITY", "k")
        val seeded = record("u", "k", listOf(equalTarget))
        val (_, _, rejected) = run("R7e", equalTarget, FenceV1("A", "u", "k"), seeded)
        assertTrue(eligible("R7_equalConflict"), rejected.first is ControlStoreResult.Conflict)
        assertEquals(eligible("R7_equalKept"), seeded[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)],
            rejected.second[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
    }

    // ---- R8: C0g — active and settled SEALs, witnesses and a current NULL seal survive a rotation (L419) ----

    @Test fun R8_sealsAndWitnessesPreserved() = runReleaseTest {
        val source = intent("A", "USER", "u")
        val seals = "[" + ControlObligationFixtures.seal.replace("\"s\"", "\"R\"") + "," +
            ControlObligationFixtures.nullSeal.replace("\"s\"", "\"N\"") + "," +
            ControlObligationFixtures.settledSeal.replace("\"s\"", "\"L\"") + "]"
        val seeded = record("u", "k", listOf(source)).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = seals
        }.toPreferences()
        assertFalse(FloorGuardFixtures.read(seeded).hasUninterpretable)
        // The current owner's (A) NULL seal is on the rotated USER axis and remains unsettled (L419).
        assertTrue(ControlObligationFixtures.nullSeal.contains("\"ownerUid\":\"A\""))
        val (c, _, out) = run("R8", source, FenceV1("A", "u", "k"), seeded)
        val (result, after) = out
        assertTrue(atomic("R8_confirmed"), result is ControlStoreResult.Confirmed)
        assertEquals(atomic("R8_rotated"), plan(c).ids.epochs.user, after[USER_EPOCH])
        assertEquals(atomic("C0g_sealsVerbatim"), seals, after[ControlRecordKeys.payload(ControlKind.SEAL)])
    }
}
