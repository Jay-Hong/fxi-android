package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.EntitlementsOutcome

/**
 * Claude-owned 6-4aB fixtures (not production API): the shortest confirmed path of each of the seven Lifecycle writers on
 * one ControlStoreTestStorage — seed → prepare → execute → Confirmed, with tracked.confirmed and a Lifecycle expected row.
 * Sources (6-4aB_writer_recipes_codex.md): DemandAuthBacklogContract2Test.kt:338–362 (rebind/settle/update/end),
 * RemoveEmptyGuardWriterTest.kt:123, RecoverHoldWriterTest.kt:34, RecoverIntentWriterContractTest.kt:197.
 * The implementation thread reads but does not edit this file.
 */
internal object LifecycleWriters {
    class Writer(val transition: LifecycleTransition, val confirm: suspend (ControlStoreTestStorage) -> CommandRef)

    private fun confirmed(s: ControlStoreTestStorage, c: CommandRef, result: ControlStoreResult): CommandRef {
        check(result is ControlStoreResult.Confirmed) { "fixture ${c.id}: expected Confirmed: $result" }
        val tracked = checkNotNull(ControlCommandTracking.forOwner(s.owner).findPrepared(c))
        check(tracked.confirmed.get()) { "fixture: confirmed" }
        check(tracked.expectedApplied is AppliedEvidence.Lifecycle) { "fixture: Lifecycle expected row" }
        return c
    }

    suspend fun rebind(s: ControlStoreTestStorage): CommandRef {
        val f = DemandAuthFixtures
        val r = f.request(binding = 2)
        controlTestTimeout("rebind seed") { s.data.updateData { f.raw(r) } }
        val c = s.control.prepareRebindRequests(listOf(r), f.binding, LifecycleOrderSource(f.life, 21))
        return confirmed(s, c, controlTestTimeout("rebind execute") { s.control.execute(c, f.context(f.runtime())) })
    }

    suspend fun settle(s: ControlStoreTestStorage): CommandRef {
        val f = DemandAuthFixtures
        val g = f.guard(); val r = f.request()
        controlTestTimeout("settle seed") { s.data.updateData { f.raw(g, r) } }
        val c = s.control.prepareSettleQuery(listOf(r), g, null, f.binding, f.decision(), LifecycleOrderSource(f.life, 21))
        return confirmed(s, c, controlTestTimeout("settle execute") { s.control.execute(c, f.context(f.runtime())) })
    }

    suspend fun updateAuth(s: ControlStoreTestStorage): CommandRef {
        val f = DemandAuthFixtures
        val g = f.guard()
        controlTestTimeout("update seed") { s.data.updateData { f.raw(g) } }
        val event = LifecycleAuthEvent.Answer(f.decision(outcome = EntitlementsOutcome.Pending(false, 30)))
        val c = s.control.prepareUpdateAuth(g, null, f.binding, event, LifecycleOrderSource(f.life, 21))
        return confirmed(s, c, controlTestTimeout("update execute") { s.control.execute(c, f.context(f.runtime())) })
    }

    suspend fun endAuthBinding(s: ControlStoreTestStorage): CommandRef {
        val f = DemandAuthFixtures
        val old = AuthSnapshotV1("A", 2, 2, f.life, true, 10, 20)
        val g = f.guard(auth = old)
        val closure = LifecycleBindingClosure(old, true, setOf("w"), setOf("w"), 5)
        controlTestTimeout("end seed") { s.data.updateData { f.raw(g) } }
        val c = s.control.prepareEndAuthBinding(g, emptyList(), f.binding, closure, f.binding, LifecycleOrderSource(f.life, 21))
        return confirmed(s, c, controlTestTimeout("end execute") { s.control.execute(c, f.context(f.runtime(closure = closure))) })
    }

    suspend fun removeEmptyGuard(s: ControlStoreTestStorage): CommandRef {
        val f = FloorGuardFixtures
        controlTestTimeout("remove seed") { s.data.updateData { f.raw(f.empty) } }
        val c = s.control.prepareRemoveEmptyGuard(f.empty)
        return confirmed(s, c, controlTestTimeout("remove execute") { s.control.execute(c) })
    }

    suspend fun recoverHold(s: ControlStoreTestStorage): CommandRef {
        val f = HoldRecoveryFixtures
        val input = f.input()
        controlTestTimeout("hold seed") { s.data.updateData { f.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(f.life, 21))
        return confirmed(s, c, controlTestTimeout("hold execute") { s.control.execute(c, f.context(input)) })
    }

    suspend fun recoverIntent(s: ControlStoreTestStorage): CommandRef {
        val life = LifetimeId("new-life")
        val source = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k0"}""")
        val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")
        val closure = HoldRecoveryClosure.AfterRestart(source, binding.executor, "old-tracking", true, true)
        val before = FenceV1("A", "u", "k")
        val raw = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            this[MAY_CONTAIN_PREMIUM] = true
            this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[${source.toPayloadEntry().fields}]"
        }.toPreferences()
        controlTestTimeout("intent seed") { s.data.updateData { raw } }
        val input = RecoverIntentInput(source, before, binding, closure)
        val c = s.control.prepareRecoverIntent(input, LifecycleOrderSource(life, 21))
        val context = AttemptContext("A", 3, life, false, false,
            intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure))
        return confirmed(s, c, controlTestTimeout("intent execute") { s.control.execute(c, context) })
    }

    val all = listOf(
        Writer(LifecycleTransition.REBIND_REQUESTS, ::rebind),
        Writer(LifecycleTransition.SETTLE_QUERY, ::settle),
        Writer(LifecycleTransition.UPDATE_AUTH, ::updateAuth),
        Writer(LifecycleTransition.END_AUTH_BINDING, ::endAuthBinding),
        Writer(LifecycleTransition.REMOVE_EMPTY_GUARD, ::removeEmptyGuard),
        Writer(LifecycleTransition.RECOVER_HOLD, ::recoverHold),
        Writer(LifecycleTransition.RECOVER_INTENT, ::recoverIntent))
}
