package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F

import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.buffer
import okio.source
import java.io.File
import java.math.BigInteger
/** Independent fixtures; official execution is performed outside this implementation task. */
class RecoverHoldEligibilityTest {
    @Test fun RH_binding() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        assertNotNull(F.eligible("RH_binding"), F.writer.eligibility(p, F.context(i, F.runtime(i.closure, b = F.binding.copy(startedOrder = 2))), F.read(raw), "current-tracker"))
    }

    @Test fun RH_fenceCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        assertNotNull(F.eligible("RH_fenceCall"), F.writer.eligibility(p, ctx, F.read(raw.toMutablePreferences().apply { this[USER_EPOCH] = "other" }.toPreferences()), "current-tracker"))
    }

    @Test fun RH_currentCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        
        assertNotNull(F.eligible("RH_currentCall"), F.writer.eligibility(p, ctx.copy(signOutOpen = true), F.read(raw), "current-tracker"))
    }

    @Test fun RH_preimageCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        val bad = F.changeRow(raw, ControlKind.HOLD, "h") { F.field(it, "binding", JsonPrimitive(18)) }
        assertNotNull(F.eligible("RH_preimageCall"), F.writer.eligibility(p, ctx, F.read(raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]" }), "current-tracker"))
    }

    @Test fun RH_closureCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        
        assertNotNull(F.eligible("RH_closureCall"), F.writer.eligibility(p, F.context(i, F.runtime(i.closure, closed = false)), F.read(raw), "current-tracker"))
    }

    @Test fun RH_freshCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); val ctx = F.context(i)
        assertNull(p.preparationProblem)
        assertNull(F.writer.eligibility(p, ctx, F.read(raw), "current-tracker"))
        val bad = raw.toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|${F.krxEpoch}||USER" }.toPreferences()
        assertNotNull(F.eligible("RH_freshCall"), F.writer.eligibility(p, ctx, F.read(bad), "current-tracker"))
    }

    @Test fun HR_operationEmpty() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_operationEmpty"), RecoverHoldPlan.prepare(i, F.ids.copy(operationId = ""), LifecycleOrderSource(F.life, 21)).preparationProblem)
    }

    @Test fun HR_prepareOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_prepareOwner"), RecoverHoldPlan.prepare(i.copy(binding = F.binding.copy(executor = F.executor.copy(ownerUid = "B"))), F.ids, LifecycleOrderSource(F.life, 21)).preparationProblem)
    }

    @Test fun HR_prepareBinding() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_prepareBinding"), RecoverHoldPlan.prepare(i.copy(binding = F.binding.copy(executor = F.executor.copy(binding = -1))), F.ids, LifecycleOrderSource(F.life, 21)).preparationProblem)
    }

    @Test fun HR_prepareStart() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_prepareStart"), RecoverHoldPlan.prepare(i.copy(binding = F.binding.copy(startedOrder = -1)), F.ids, LifecycleOrderSource(F.life, 21)).preparationProblem)
    }

    @Test fun HR_requestId() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_requestId"), RecoverHoldPlan.prepare(i, F.ids.copy(requestId = ""), LifecycleOrderSource(F.life, 21)).preparationProblem)
    }

    @Test fun HR_supplierOrigin() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.plan(i).preparationProblem)
        assertNotNull(F.eligible("HR_supplierOrigin"), RecoverHoldPlan.prepare(i, F.ids, LifecycleOrderSource(LifetimeId("other"), 21)).preparationProblem)
    }
}
