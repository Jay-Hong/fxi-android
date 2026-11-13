package com.jay.fxi.service

import android.app.NotificationManager
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S7 E5b2a contract (s7e5b2_api_agreed.r1.md §2.1–§2.2, R02): the OS capability decision, the D21 intent projection and
 * the reconciler every re-evaluation goes through. The capability is read fresh and ordered runtime → app → channel; a
 * channel not created yet blocks nothing. A denial projects false whoever asks; the current session outside a teardown
 * projects its server premium (true confirmed, false otherwise, gap D01); no session, another session or a teardown
 * leaves the stored value. Premium is judged for the owner asked about, read once (gap R10), and the hold is read after
 * the other inputs (gap G10). A null or no-longer-current owner is never
 * re-armed or handed to the coordinator; the current owner always is, so owed DELETEs settle and a failed POST is retried
 * on the next signal — the stored true is never taken as a registration record.
 */
class S7E5B2AReconcileContractTest {

    private companion object {
        val A1 = AuthIdentityFence("user-a", 1L)
        val A2 = AuthIdentityFence("user-a", 2L)
        val B1 = AuthIdentityFence("user-b", 1L)
        fun confirmed(owner: AuthIdentityFence) = OwnedPremiumAccess(owner.uid, owner.authGeneration, PremiumAccessState.PremiumConfirmed)
        val CAPS = NotificationCapability.entries.toList()
    }

    // ---- C: the capability decision ----

    @Test fun `S7E5B2A_C01 the runtime permission decides first from Android 13 and never below it`() {
        for (sdk in listOf(33, 34, 36)) {
            assertEquals("sdk $sdk without the runtime grant", NotificationCapability.RUNTIME_MISSING,
                notificationCapabilityOf(sdk, runtimeGranted = false, appEnabled = false, channelImportance = NotificationManager.IMPORTANCE_NONE))
        }
        for (sdk in listOf(26, 30, 32)) {
            assertEquals("sdk $sdk has no runtime permission to miss", NotificationCapability.GRANTED,
                notificationCapabilityOf(sdk, runtimeGranted = false, appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_HIGH))
        }
    }

    @Test fun `S7E5B2A_C02 app notifications come before the channel and the channel blocks only at importance none`() {
        for (sdk in listOf(26, 32, 33, 36)) {
            assertEquals(NotificationCapability.APP_DISABLED,
                notificationCapabilityOf(sdk, runtimeGranted = true, appEnabled = false, channelImportance = NotificationManager.IMPORTANCE_NONE))
            assertEquals(NotificationCapability.CHANNEL_DISABLED,
                notificationCapabilityOf(sdk, runtimeGranted = true, appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_NONE))
            for (importance in listOf(null, NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_LOW,
                    NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_HIGH)) {
                assertEquals("sdk $sdk importance $importance", NotificationCapability.GRANTED,
                    notificationCapabilityOf(sdk, runtimeGranted = true, appEnabled = true, channelImportance = importance))
            }
        }
    }

    // ---- P: the D21 projection ----

    @Test fun `S7E5B2A_P01 a denial projects false for every owner premium and hold`() {
        for (capability in CAPS - NotificationCapability.GRANTED) for (current in listOf(true, false))
            for (premium in listOf(true, false)) for (held in listOf(true, false)) {
                assertEquals("$capability current=$current premium=$premium held=$held", false,
                    reconciledPushIntent(capability, current, premium, held))
            }
    }

    @Test fun `S7E5B2A_P02 the current session outside a teardown projects its premium and the rest keep the stored value`() {
        for (current in listOf(true, false)) for (premium in listOf(true, false)) for (held in listOf(true, false)) {
            val expected = if (current && !held) premium else null
            assertEquals("current=$current premium=$premium held=$held", expected,
                reconciledPushIntent(NotificationCapability.GRANTED, current, premium, held))
        }
    }

    // ---- R: the reconciler ----

    private class Rig(
        var capability: NotificationCapability = NotificationCapability.GRANTED,
        var current: AuthIdentityFence? = A1,
        var access: OwnedPremiumAccess = confirmed(A1),
        var held: Boolean = false,
        var intent: Boolean = false,
        var outcome: RegisterOutcome = RegisterOutcome.REGISTERED
    ) {
        val writes = mutableListOf<Boolean>()
        val registers = mutableListOf<Pair<AuthIdentityFence, String?>>()
        val intentAtRegister = mutableListOf<Boolean>()
        val reconciler = PushIntentReconciler(
            capability = { capability },
            currentOwner = { current },
            premium = { access },
            held = { held },
            readIntent = { intent },
            writeIntent = { writes += it; intent = it },
            register = { owner, token -> registers += owner to token; intentAtRegister += intent; outcome }
        )
    }

    @Test fun `S7E5B2A_R01 the current confirmed session is re-armed before the coordinator is asked`() = runTest {
        val rig = Rig(intent = false)
        assertEquals(RegisterOutcome.REGISTERED, rig.reconciler.reconcile(A1))
        assertEquals(listOf(true), rig.writes)
        assertEquals(listOf(A1 to null), rig.registers)
        assertEquals("the coordinator's eligibility reads the re-armed intent", listOf(true), rig.intentAtRegister)
    }

    @Test fun `S7E5B2A_R02 a denial clears a stale true and still asks the coordinator for the current owner`() = runTest {
        for (capability in CAPS - NotificationCapability.GRANTED) {
            val rig = Rig(capability = capability, intent = true, outcome = RegisterOutcome.NOT_ELIGIBLE)
            assertEquals(RegisterOutcome.NOT_ELIGIBLE, rig.reconciler.reconcile(A1))
            assertEquals("$capability", listOf(false), rig.writes)
            assertEquals("$capability: owed DELETEs still settle", listOf(A1 to null), rig.registers)
            assertEquals(listOf(false), rig.intentAtRegister)
        }
    }

    @Test fun `S7E5B2A_R03 an unconfirmed premium clears the current owner's intent and a teardown leaves it, both still asking for the owner`() = runTest {
        for ((label, rig) in listOf(
            "free" to Rig(access = OwnedPremiumAccess("user-a", 1L, PremiumAccessState.FreeConfirmed)),
            "no grant" to Rig(access = OwnedPremiumAccess()),
            "pending" to Rig(access = OwnedPremiumAccess("user-a", 1L, PremiumAccessState.Pending(true))),
            "rejected" to Rig(access = OwnedPremiumAccess("user-a", 1L, PremiumAccessState.Rejected)),
            "another generation's grant" to Rig(access = confirmed(A2))
        )) {
            for (stored in listOf(false, true)) {
                rig.intent = stored
                rig.writes.clear(); rig.registers.clear()
                rig.reconciler.reconcile(A1)
                assertEquals("$label stored=$stored: projected false (D01)", if (stored) listOf(false) else emptyList(), rig.writes)
                assertEquals("$label stored=$stored", listOf(A1 to null), rig.registers)
            }
        }
        val held = Rig(held = true)
        for (stored in listOf(false, true)) {
            held.intent = stored
            held.writes.clear(); held.registers.clear()
            held.reconciler.reconcile(A1)
            assertEquals("held stored=$stored: neither re-armed nor cleared", emptyList<Boolean>(), held.writes)
            assertEquals("held stored=$stored", listOf(A1 to null), held.registers)
        }
    }

    @Test fun `S7E5B2A_R04 a null or no-longer-current owner is never re-armed or registered`() = runTest {
        for ((owner, current) in listOf(null to A1, A1 to B1, A1 to A2, A1 to null)) {
            val rig = Rig(current = current, access = confirmed(A1), intent = false)
            assertNull("$owner over $current", rig.reconciler.reconcile(owner))
            assertEquals("$owner over $current", emptyList<Boolean>(), rig.writes)
            assertEquals("$owner over $current", emptyList<Pair<AuthIdentityFence, String?>>(), rig.registers)
        }
        val denied = Rig(capability = NotificationCapability.APP_DISABLED, current = B1, intent = true)
        assertNull(denied.reconciler.reconcile(A1))
        assertEquals("a stale owner's denial still clears the device intent", listOf(false), denied.writes)
        assertEquals(emptyList<Pair<AuthIdentityFence, String?>>(), denied.registers)
    }

    @Test fun `S7E5B2A_R05 a stored true is no registration record so every signal asks again`() = runTest {
        val rig = Rig(intent = true, outcome = RegisterOutcome.POST_FAILED)
        repeat(3) { rig.reconciler.reconcile(A1) }
        assertEquals("an unchanged value is not rewritten", emptyList<Boolean>(), rig.writes)
        assertEquals(List(3) { A1 to null }, rig.registers)
    }

    @Test fun `S7E5B2A_R06 a rotated token is handed through with the owner`() = runTest {
        val rig = Rig()
        rig.reconciler.reconcile(A1, knownToken = "token-1")
        assertEquals(listOf(A1 to "token-1"), rig.registers)
    }

    // ---- gap hunt r1 (s7e5b2a_gaphunt.codex.r1.md) ----

    @Test fun `S7E5B2A_GAP01 every combination of sdk runtime app and channel follows the agreed order`() {
        val importances = listOf(null, NotificationManager.IMPORTANCE_NONE, NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_HIGH)
        for (sdk in listOf(26, 32, 33, 36)) for (runtime in listOf(false, true)) for (app in listOf(false, true)) for (importance in importances) {
            val expected = when {
                sdk >= 33 && !runtime -> NotificationCapability.RUNTIME_MISSING
                !app -> NotificationCapability.APP_DISABLED
                importance == NotificationManager.IMPORTANCE_NONE -> NotificationCapability.CHANNEL_DISABLED
                else -> NotificationCapability.GRANTED
            }
            assertEquals("sdk=$sdk runtime=$runtime app=$app importance=$importance", expected, notificationCapabilityOf(sdk, runtime, app, importance))
        }
        assertEquals(NotificationCapability.RUNTIME_MISSING, notificationCapabilityOf(33, runtimeGranted = false, appEnabled = true, channelImportance = null))
        assertEquals(NotificationCapability.APP_DISABLED, notificationCapabilityOf(32, runtimeGranted = true, appEnabled = false, channelImportance = null))
        assertEquals(NotificationCapability.APP_DISABLED, notificationCapabilityOf(33, runtimeGranted = true, appEnabled = false, channelImportance = NotificationManager.IMPORTANCE_HIGH))
    }

    @Test fun `S7E5B2A_GAP10 a hold raised while the inputs are read prevents the re-arm`() = runTest {
        lateinit var rig: Rig
        var raised = false
        rig = Rig(intent = false)
        val reconciler = PushIntentReconciler(
            capability = { if (!raised) { raised = true; rig.held = true }; rig.capability },
            currentOwner = { rig.current },
            premium = { rig.access },
            held = { rig.held },
            readIntent = { rig.intent },
            writeIntent = { rig.writes += it; rig.intent = it },
            register = { owner, token -> rig.registers += owner to token; rig.outcome }
        )
        reconciler.reconcile(A1)
        assertEquals("no re-arm once the hold stands", emptyList<Boolean>(), rig.writes)
        assertEquals(false, rig.intent)
        assertEquals("the current owner is still handed on", listOf(A1 to null), rig.registers)
    }

    @Test fun `S7E5B2A_GAP12R10 the projection never borrows a newer owner's premium`() = runTest {
        var owner: AuthIdentityFence? = A1
        var access = confirmed(A1)
        val writes = mutableListOf<Boolean>()
        var intent = false
        val reconciler = PushIntentReconciler(
            capability = { NotificationCapability.GRANTED },
            currentOwner = { owner },
            premium = {
                // The session moves on between the reads.
                owner = B1
                access = confirmed(B1)
                access
            },
            held = { false },
            readIntent = { intent },
            writeIntent = { writes += it; intent = it },
            register = { _, _ -> RegisterOutcome.STALE }
        )
        reconciler.reconcile(A1)
        assertEquals("A1's signal took no true from B1's grant", false, writes.contains(true))
    }
}
