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
class RecoverHoldCandidateTest {
    @Test fun RH_requestIdLink() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        val bad = F.field(request, "id", JsonPrimitive("different"))
        assertNotNull(demand(bad))
        assertFalse(F.atomic("RH_requestIdLink"), F.writer.requestValid(i, F.ids, F.grant, bad))
    }

    @Test fun RH_requestOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        val bad = F.field(request, "ownerUid", JsonPrimitive("B"))
        assertNotNull(demand(bad))
        assertFalse(F.atomic("RH_requestOwner"), F.writer.requestValid(i, F.ids, F.grant, bad))
    }

    @Test fun RH_requestBinding() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        val bad = F.field(request, "binding", JsonPrimitive(4))
        assertNotNull(demand(bad))
        assertFalse(F.atomic("RH_requestBinding"), F.writer.requestValid(i, F.ids, F.grant, bad))
    }

    @Test fun RH_requestOrigin() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        val bad = F.field(request, "originLifetimeId", JsonPrimitive("other-life"))
        assertNotNull(demand(bad))
        assertFalse(F.atomic("RH_requestOrigin"), F.writer.requestValid(i, F.ids, F.grant, bad))
    }

    @Test fun RH_requestIntentCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        val bad = F.field(request, "intent", JsonPrimitive("FORCE_ENTITLEMENTS"))
        assertNotNull(demand(bad))
        assertFalse(F.atomic("RH_requestIntentCall"), F.writer.requestValid(i, F.ids, F.grant, bad))
    }

    @Test fun RH_orderMissing() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        assertFalse(F.atomic("RH_orderMissing"), F.writer.requestValid(i, F.ids, null, request))
    }

    @Test fun RH_orderCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        assertFalse(F.atomic("RH_orderCall"), F.writer.requestValid(i, F.ids, F.grant.copy(previous = 20), request))
    }

    @Test fun RH_orderValueCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        assertFalse(F.atomic("RH_orderValueCall"), F.writer.requestValid(i, F.ids, F.grant.copy(previous = 22, value = 23), request))
    }

    @Test fun RH_C01() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = F.payload(listOf(i.source)) }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C01"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C07owner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[OWNER_UID] = "B" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C07owner"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C07teardown() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C07teardown"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C06epoch() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[KRX_EPOCH] = "k" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C06epoch"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C06marker() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = true }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C06marker"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C05exact() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C05exact"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C04call() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = F.changeRow(good, ControlKind.DEMAND, "new-request") { F.field(it, "intent", JsonPrimitive("FORCE_ENTITLEMENTS")) }
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C04call"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_C09call() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = F.changeRow(good, ControlKind.DEMAND, "g") { F.field(it, "floor", FloorGuardFixtures.floor(28999, "boot", 11000, "new-life")) }
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C09call"), F.writer.requiredEffects(i, F.ids, F.grant, F.read(raw), F.read(bad)))
    }

    @Test fun RH_effectsCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = F.payload(listOf(i.source)) }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_effectsCall"), F.writer.validCandidate(c, p, F.read(raw), bad))
    }

    @Test fun RH_C11() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[byteArrayPreferencesKey("lifecycle-external")] = byteArrayOf(9) }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C11"), F.writer.validCandidate(c, p, F.read(raw), bad))
    }

    @Test fun RH_C05preserve() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[PURGE_JOURNAL] = "A||k|CAPABILITY\nB|other||USER" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_C05preserve"), F.writer.validCandidate(c, p, F.read(raw), bad))
    }

    @Test fun RH_survivorsCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = F.changeRow(good, ControlKind.DEMAND, "old") { F.field(it, "intent", JsonPrimitive("FORCE_ENTITLEMENTS")) }
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_survivorsCall"), F.writer.validCandidate(c, p, F.read(raw), bad))
    }

    @Test fun RH_evidenceCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }.toPreferences()
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_evidenceCall"), F.writer.validCandidate(c, p, F.read(raw), bad))
    }

    @Test fun RH_C02survivors() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = F.changeRow(good, ControlKind.DEMAND, "old") { F.field(it, "intent", JsonPrimitive("FORCE_ENTITLEMENTS")) }
        val targets = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE), LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        assertFalse(F.atomic("RH_C02survivors"), F.writer.survivorsPreserved(F.read(raw), F.read(bad), targets))
    }

    @Test fun RH_C10() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = good.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }.toPreferences()
        val targets = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE), LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        assertFalse(F.atomic("RH_C10"), F.writer.evidencePreserved(c, F.read(raw), F.read(bad), targets))
    }

    @Test fun RH_namedPlan() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_namedPlan"),F.writer.validDescriptor(p,changed(named=F.plan(i))))
    }

    @Test fun RH_descriptorId() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorId"),F.writer.validDescriptor(p,changed(operation="different")))
    }

    @Test fun RH_descriptorTransition() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorTransition"),F.writer.validDescriptor(p,changed(transition=LifecycleTransition.SETTLE_QUERY)))
    }

    @Test fun RH_descriptorExecutor() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorExecutor"),F.writer.validDescriptor(p,changed(executor=F.executor.copy(binding=4))))
    }

    @Test fun RH_descriptorTargets() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorTargets"),F.writer.validDescriptor(p,changed(targets=descriptor.targets.filterNot { it.role==LifecycleRole.REQUEST })))
    }

    @Test fun RH_descriptorSource() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorSource"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.HOLD) { it.copy(before=F.hold(id="other-h")) })))
    }

    @Test fun RH_descriptorRemoved() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorRemoved"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.HOLD) { it.copy(after=i.source) })))
    }

    @Test fun RH_descriptorRequestBefore() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorRequestBefore"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.REQUEST) { it.copy(before=F.request()) })))
    }

    @Test fun RH_descriptorRequestCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorRequestCall"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.REQUEST) { it.copy(after=F.field(it.after!!,"intent",JsonPrimitive("FORCE_ENTITLEMENTS"))) })))
    }

    @Test fun RH_descriptorGuardBefore() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorGuardBefore"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.GUARD) { it.copy(before=F.guard(5)) })))
    }

    @Test fun RH_descriptorFloorCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorFloorCall"),F.writer.validDescriptor(p,changed(targets=rowChange(LifecycleRole.GUARD) { it.copy(after=F.field(it.after!!,"floor",FloorGuardFixtures.floor(28999,"boot",11000,"new-life"))) })))
    }

    @Test fun RH_descriptorUnchanged() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorUnchanged"),F.writer.validDescriptor(p,changed(unchanged=listOf(LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND,"extra",LifecycleEffect.REPLACE),LifecycleRole.REQUEST,F.request(),F.request())))))
    }

    @Test fun RH_namespacePlanCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_namespacePlanCall"),F.writer.validDescriptor(p,changed(namespace=LifecycleNamespacePostcondition(F.beforeFence,F.beforeFence,descriptor.namespace!!.journal,null,false))))
    }

    @Test fun RH_descriptorUserMarker() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorUserMarker"),F.writer.validDescriptor(p,changed(namespace=LifecycleNamespacePostcondition(F.beforeFence,FenceV1("A","u",F.krxEpoch),descriptor.namespace!!.journal,false,false))))
    }

    @Test fun RH_descriptorKrxMarker() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorKrxMarker"),F.writer.validDescriptor(p,changed(namespace=LifecycleNamespacePostcondition(F.beforeFence,FenceV1("A","u",F.krxEpoch),descriptor.namespace!!.journal,null,true))))
    }

    @Test fun RH_descriptorNamespace() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val descriptor=p.descriptor()
        fun changed(operation: String = descriptor.operationId, transition: LifecycleTransition = descriptor.transition,
            targets: List<LifecycleFixedTarget> = descriptor.targets, executor: SettlementExecutor? = descriptor.executor,
            namespace: LifecycleNamespacePostcondition? = descriptor.namespace, unchanged: List<LifecycleFixedTarget> = descriptor.requiredUnchanged,
            named: RecoverHoldPlan? = p) = ControlLifecycleDescriptor(operation,transition,targets,executor,namespace,unchanged,recoverHold=named)
        fun rowChange(role: LifecycleRole, change: (LifecycleFixedTarget) -> LifecycleFixedTarget) = descriptor.targets.map { if(it.role==role) change(it) else it }
        assertEquals(listOf("h","new-request","g"),descriptor.targets.map { it.target.id })
        assertEquals(F.executor,descriptor.executor)
        assertTrue(F.writer.validDescriptor(p,descriptor))
        assertFalse(F.atomic("RH_descriptorNamespace"),F.writer.validDescriptor(p,changed(namespace=null)))
    }

    @Test fun RH_createBeforeCollision() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val collision=F.request(id="new-request")
        assertEquals("new-request",demand(collision)!!.id); assertEquals(LifetimeId("old-life"),demand(collision)!!.raisedAt.origin)
        val before=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)]=F.payload(listOf(F.guard(),F.request(),F.request("other","B",RefreshIntent.FORCE_PREMIUM),collision)) }
        assertFalse(F.read(before).hasUninterpretable)
        assertTrue(F.writer.requiredEffects(i,F.ids,F.grant,F.read(before),F.read(good)))
        assertFalse(F.atomic("RH_createBeforeCollision"),F.writer.validCandidate(c,p,F.read(before),good))
    }

    @Test fun RH_recordCandidateCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val before=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]="[{\"future\":true}]" }
        val candidate=good.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]="[{\"future\":true}]" }
        assertTrue(F.read(before).hasUninterpretable); assertTrue(F.read(candidate).hasUninterpretable)
        assertFalse(F.atomic("RH_recordCandidateCall"),F.writer.validCandidate(c,p,F.read(before),candidate))
    }

    @Test fun RH_candidateRead() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=good.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)]="not-json" }
        assertTrue(ControlRecordReader().read(bad) is ControlRecordRead.Unreadable)
        assertFalse(F.atomic("RH_candidateRead"),F.writer.validCandidate(c,p,F.read(raw),bad))
    }

    @Test fun RH_C08ownerUid() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("ownerUid" to JsonPrimitive("B"))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08ownerUid"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08authGeneration() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("authGeneration" to JsonPrimitive(3))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08authGeneration"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08binding() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("binding" to JsonPrimitive(4))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08binding"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08originLifetimeId() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("originLifetimeId" to JsonPrimitive("other-life"))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08originLifetimeId"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08authStopped() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("authStopped" to JsonPrimitive(true))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08authStopped"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08authStateOrder() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("authStateOrder" to JsonPrimitive(11))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08authStateOrder"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C08authStopAppliedOrder() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=F.changeRow(good,ControlKind.DEMAND,"g") { g ->
            val auth=g.toPayloadEntry().fields["auth"] as JsonObject
            F.field(g,"auth",JsonObject(auth+("authStopAppliedOrder" to JsonPrimitive(21))))
        }
        assertFalse(F.read(bad).hasUninterpretable)
        assertEquals(guard(F.row(good,ControlKind.DEMAND,"g"))!!.floor,guard(F.row(bad,ControlKind.DEMAND,"g"))!!.floor)
        assertFalse(F.atomic("RH_C08authStopAppliedOrder"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C07userEpoch() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=good.toMutablePreferences().apply { this[USER_EPOCH]="different" }
        assertFalse(F.atomic("RH_C07userEpoch"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C07userMarker() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=good.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM]=false }
        assertFalse(F.atomic("RH_C07userMarker"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_C07seals() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val before=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]="["+ControlObligationFixtures.seal+"]" }
        assertFalse(F.read(before).hasUninterpretable)
        assertTrue(F.writer.requiredEffects(i,F.ids,F.grant,F.read(before),F.read(good)))
        assertFalse(F.atomic("RH_C07seals"),F.writer.validCandidate(c,p,F.read(before),good))
    }

    @Test fun RH_C10payload() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val prior = """{"version":2,"commandId":"prior","ownerTrackingLifetimeId":"11111111-1111-4111-8111-111111111111","kind":"MUTATIONS","targets":[{"index":-0,"kind":"DEMAND","id":"old-r","joined":false,"written":true}]}"""
        val targets = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE), LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        val normalized = prior.replace("\"index\":-0", "\"index\":0")
        assertNotEquals(prior, normalized)
        val before = raw.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$prior]" }
        val bad = good.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$normalized," + good[ControlLifecycleEvidenceFixtures.evidenceKey]!!.drop(1) }
        assertFalse(F.read(before).hasUninterpretableMetadata); assertFalse(F.read(bad).hasUninterpretableMetadata)
        assertFalse(F.atomic("RH_C10payload"), F.writer.evidencePreserved(c, F.read(before), F.read(bad), targets))
    }

    @Test fun RH_C10opaquePayload() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val prior = """{"version":2,"commandId":"prior","ownerTrackingLifetimeId":"11111111-1111-4111-8111-111111111111","kind":"MUTATIONS","targets":[{"index":-0,"kind":"DEMAND","id":"old-r","joined":false,"written":true}]}"""
        val targets = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE), LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE), LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        val opaque = JsonObject((Json.parseToJsonElement(prior) as JsonObject) + ("future" to JsonPrimitive(true)))
        val before = raw.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$opaque]" }
        val bad = good.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$prior," + good[ControlLifecycleEvidenceFixtures.evidenceKey]!!.drop(1) }
        assertTrue(F.read(before).hasUninterpretableMetadata); assertFalse(F.read(bad).hasUninterpretableMetadata)
        assertFalse(F.atomic("RH_C10opaquePayload"), F.writer.evidencePreserved(c, F.read(before), F.read(bad), targets))
    }

    @Test fun RH_C02payload() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad = F.changeRow(good, ControlKind.DEMAND,"other") { F.field(it,"intent",JsonPrimitive("IF_STALE")) }
        val expected = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE),LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        assertEquals(RefreshIntent.FORCE_PREMIUM, demand(F.row(raw,ControlKind.DEMAND,"other"))!!.intent)
        assertFalse(F.atomic("RH_C02payload"),F.writer.survivorsPreserved(F.read(raw),F.read(bad),expected))
    }

    @Test fun RH_C02opaquePayload() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val before = F.changeRow(raw,ControlKind.DEMAND,"other") { F.field(it,"future",JsonPrimitive(true)) }
        val expected = listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE),LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        assertTrue(F.read(before).hasUninterpretable); assertFalse(F.read(good).hasUninterpretable)
        assertFalse(F.atomic("RH_C02opaquePayload"),F.writer.survivorsPreserved(F.read(before),F.read(good),expected))
    }

    @Test fun RH_candidateCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p=F.plan(i); val c=F.command(p)
        val opaque=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]="[{\"future\":true}]" }.toPreferences()
        val read=F.read(opaque)
        assertTrue(read.hasUninterpretable); assertFalse(read.hasUninterpretableMetadata)
        assertEquals(setOf(ControlKind.SEAL),read.arrays.filterValues { it.hasUninterpretable }.keys)
        assertFalse(F.atomic("RH_candidateCall"),F.writer.decide(c,p.descriptor(),read,F.context(i)) is RecordTransactionDecision.Confirm)
    }

    @Test fun RH_encodeRejectedHOLD() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p=F.plan(i);val c=F.command(p)
        val extra=F.hold(krx="retired",id="s".repeat(1800),floor=false)
        val before=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)]=
            (this[ControlRecordKeys.payload(ControlKind.HOLD)]!!.dropLast(1)+","+extra.toPayloadEntry().fields+"]") }.toPreferences()
        assertFalse(F.read(before).hasUninterpretable);assertFalse(F.read(before).hasUninterpretableMetadata)
        val build=RecoverHoldTransition(ControlPayloadCodec(maxPayloadBytes=1400)).buildCandidate(c,p,F.read(before))
        assertTrue(F.atomic("RH_encodeRejectedHOLD"),build is RecoverHoldCandidateBuild.Failed)
        val problem=(build as RecoverHoldCandidateBuild.Failed).problem
        assertTrue(problem is HoldRecoveryProblem.Rejected && problem.reason is RejectionReason.TooLarge)
    }

    @Test fun RH_encodeRejectedDEMAND() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p=F.plan(i);val c=F.command(p)
        val extra=F.request(id="r".repeat(1800))
        val before=raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)]=
            (this[ControlRecordKeys.payload(ControlKind.DEMAND)]!!.dropLast(1)+","+extra.toPayloadEntry().fields+"]") }.toPreferences()
        assertFalse(F.read(before).hasUninterpretable);assertFalse(F.read(before).hasUninterpretableMetadata)
        val build=RecoverHoldTransition(ControlPayloadCodec(maxPayloadBytes=1400)).buildCandidate(c,p,F.read(before))
        assertTrue(F.atomic("RH_encodeRejectedDEMAND"),build is RecoverHoldCandidateBuild.Failed)
        val problem=(build as RecoverHoldCandidateBuild.Failed).problem
        assertTrue(problem is HoldRecoveryProblem.Rejected && problem.reason is RejectionReason.TooLarge)
    }

    @Test fun RH_appendRejected() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p=F.plan(i);val c=F.command(p)
        val prior=ControlLifecycleEvidenceFixtures.wire(command="x".repeat(900))
        val before=raw.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey]="[$prior]" }.toPreferences()
        assertTrue(before[ControlLifecycleEvidenceFixtures.evidenceKey]!!.toByteArray(Charsets.UTF_8).size < 1400)
        assertFalse(F.read(before).hasUninterpretableMetadata)
        val build=RecoverHoldTransition(ControlPayloadCodec(maxPayloadBytes=1400)).buildCandidate(c,p,F.read(before))
        assertTrue(F.atomic("RH_appendRejected"),build is RecoverHoldCandidateBuild.Failed)
        val problem=(build as RecoverHoldCandidateBuild.Failed).problem
        assertTrue(problem is HoldRecoveryProblem.Rejected && problem.reason is RejectionReason.TooLarge)
    }

    @Test fun RH_requestMissing() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val request = F.row(good, ControlKind.DEMAND, "new-request")
        val typed = demand(request)!!
        assertEquals("A", typed.ownerUid); assertEquals(3L, typed.binding)
        assertEquals(F.life, typed.raisedAt.origin); assertEquals(22L, typed.raisedAt.value)
        assertEquals(RefreshIntent.FORCE_PREMIUM, typed.intent)
        assertFalse(F.atomic("RH_requestMissing"),F.writer.requestValid(i,F.ids,F.grant,null))
    }

    @Test fun RH_candidateJournal() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        assertTrue(F.writer.validCandidate(c, p, F.read(raw), good))
        val bad=good.toMutablePreferences().apply { this[PURGE_JOURNAL]="A||k|CAPABILITY|future" }
        assertFalse(F.atomic("RH_candidateJournal"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }

    @Test fun RH_requiredRequestSelector() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val p = F.plan(i); assertNull(p.preparationProblem)
        val c = F.command(p)
        val good = F.candidate(c)
        assertEquals("A||k|CAPABILITY", good[PURGE_JOURNAL])
        assertEquals(F.krxEpoch, good[KRX_EPOCH]); assertEquals(false, good[MAY_CONTAIN_KRX])
        assertEquals("[]", good[ControlRecordKeys.payload(ControlKind.HOLD)])
        assertEquals(listOf("g", "old", "other", "new-request"), F.read(good).arrays.getValue(ControlKind.DEMAND).entries.map { (it as ControlEntryRead.Interpreted).value.id })
        val bad=F.changeRow(good,ControlKind.DEMAND,"new-request") { null }
        assertFalse(F.read(bad).hasUninterpretable)
        assertFalse(F.atomic("RH_requiredRequestSelector"),F.writer.requiredEffects(i,F.ids,F.grant,F.read(raw),F.read(bad)))
    }
}
