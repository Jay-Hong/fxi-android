package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM
import com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Claude-owned 5e-2 REQUEST contract (plan v1 row 7). Design `d2b5_demand_auth_design_r3_codex.md`
 * L421–L427 (only when a new REQUEST is needed; RECOVERY_INTENT uses the axis rule only: USER →
 * FORCE_PREMIUM, CAPABILITY → FORCE_ENTITLEMENTS; no strength guessed from sessionId or grant), L439
 * step 5 (current binding/origin, §4.5 fresh order, one new row, null owner is an exact value),
 * §9.4 I04a–c and H07g. requestValid is checked as a direct boundary against literal REQUEST nodes
 * (never requestNode output); the null=null current owner is checked through FileStorage.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentRequestContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent request cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)

    private val newLife = LifetimeId("new-life")
    private fun executor(owner: String?) = SettlementExecutor(owner, 3, newLife)
    private fun binding(owner: String?) = LifecycleBinding(executor(owner), owner?.let { IdentityV1(it, 2) }, 1, "binding-start")
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, null))
    private val grant = LifecycleOrderGrant(newLife, 21, 1, 0, 22)
    private val writer = RecoverIntentTransition(ControlPayloadCodec())
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?) =
        ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private fun closure(src: ControlNode, owner: String?) = HoldRecoveryClosure.AfterRestart(src, executor(owner), "previous-tracker", true, true)
    private fun input(src: ControlNode, owner: String? = "A", before: FenceV1 = FenceV1("A", "u", "k")) =
        RecoverIntentInput(src, before, binding(owner), closure(src, owner))
    /** Literal REQUEST node; defaults are the exact expected row for owner A, binding 3, origin new-life, order 22. */
    private fun request(id: String = "new-request", owner: String? = "A", binding: Long = 3, origin: String = "new-life",
                        intent: RefreshIntent, raisedAt: Long = 22) = ControlObligationFixtures.node(buildJsonObject {
        put("id", id); put("kind", "REQUEST"); put("ownerUid", owner?.let(::JsonPrimitive) ?: JsonNull)
        put("binding", binding); put("originLifetimeId", origin); put("intent", intent.name); put("raisedAt", raisedAt)
    }.toString())

    // C0c for the current owner (target k0 ≠ current k): a new REQUEST is needed, no rotation.
    private val capability = intent("A", "CAPABILITY", "k0")
    private val user = intent("A", "USER", "u0")

    // ---- Q1: exact positive rows and axis lower bounds (L421–L427, I04b/c, H07g) ----

    @Test fun Q1_axisLowerBound() {
        assertTrue(atomic("Q1_capabilityExact"), writer.requestValid(input(capability), ids, grant, request(intent = FORCE_ENTITLEMENTS)))
        assertTrue(atomic("Q1_capabilityStronger"), writer.requestValid(input(capability), ids, grant, request(intent = FORCE_PREMIUM)))
        assertFalse(atomic("I04c_capabilityIfStale"), writer.requestValid(input(capability), ids, grant, request(intent = IF_STALE)))
        assertTrue(atomic("Q1_userExact"), writer.requestValid(input(user), ids, grant, request(intent = FORCE_PREMIUM)))
        assertFalse(atomic("I04b_userEntitlements"), writer.requestValid(input(user), ids, grant, request(intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("I04b_userIfStale"), writer.requestValid(input(user), ids, grant, request(intent = IF_STALE)))
    }

    // ---- Q2: I04a and the row's link to the fixed current binding, origin and order (L439 step 5, §4.5) ----

    @Test fun Q2_requiredRowAndLinks() {
        val i = input(capability)
        assertFalse(atomic("I04a_missing"), writer.requestValid(i, ids, grant, null))
        assertFalse(atomic("Q2_id"), writer.requestValid(i, ids, grant, request(id = "other", intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_owner"), writer.requestValid(i, ids, grant, request(owner = "B", intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_binding"), writer.requestValid(i, ids, grant, request(binding = 4, intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_origin"), writer.requestValid(i, ids, grant, request(origin = "life", intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_orderMissing"), writer.requestValid(i, ids, null, request(intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_orderValue"), writer.requestValid(i, ids, grant, request(raisedAt = 23, intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_orderOrigin"), writer.requestValid(i, ids, grant.copy(origin = LifetimeId("life")), request(intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_orderNotAboveStart"), writer.requestValid(i, ids, LifecycleOrderGrant(newLife, 0, 0, 0, 1), request(raisedAt = 1, intent = FORCE_ENTITLEMENTS)))
        assertFalse(atomic("Q2_orderSequence"), writer.requestValid(
            i, ids, grant.copy(previous = 22),
            request(intent = FORCE_ENTITLEMENTS)
        ))
    }

    // ---- Q3: null == null is the current owner — a new REQUEST with ownerUid null (L439 step 5 "null owner도 exact 값") ----

    @Test fun Q3_nullOwnerCurrentGetsNullOwnerRequest() = runReleaseTest {
        val src = intent(null, "CAPABILITY", "k")
        val file = folder.newFile(); val s = open(file)
        val seeded: Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            remove(OWNER_UID); this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[${src.toPayloadEntry().fields}]"
        }.toPreferences()
        assertNull(seeded[OWNER_UID])
        controlTestTimeout("Q3 seed") { s.data.updateData { seeded } }
        val c = s.control.prepareRecoverIntent(input(src, owner = null, before = FenceV1(null, "u", "k")), LifecycleOrderSource(newLife, 21))
        val plan = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
        assertNull(atomic("Q3_prepared"), plan.preparationProblem)
        val result = controlTestTimeout("Q3 execute") {
            s.control.execute(c, AttemptContext(null, 3, newLife, false, false,
                intentRecovery = HoldRecoveryRuntime(binding(null), 5, true, emptySet(), closure(src, null))))
        }
        assertTrue(atomic("Q3_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertNull(atomic("Q3_ownerKept"), after[OWNER_UID])
        assertEquals(atomic("Q3_rotated"), plan.ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(atomic("Q3_journal"), "||k|CAPABILITY", after[PURGE_JOURNAL])
        val fresh = FloorGuardFixtures.read(after).arrays.getValue(ControlKind.DEMAND).entries
            .filterIsInstance<ControlEntryRead.Interpreted>().map { it.value }.filterIsInstance<DemandV1>().single { it.id == plan.ids.requestId }
        assertNull(atomic("Q3_requestNullOwner"), fresh.ownerUid)
        assertEquals(atomic("Q3_strength"), FORCE_ENTITLEMENTS, fresh.intent)
        assertEquals(atomic("Q3_origin"), newLife, fresh.raisedAt.origin)
    }
}
