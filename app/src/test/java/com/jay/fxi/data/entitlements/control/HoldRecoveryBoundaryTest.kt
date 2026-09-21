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
class HoldRecoveryBoundaryTest {
    @Test fun HR_subjectOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertFalse(F.eligible("HR_subjectOwner"), HoldRecoveryBoundary.subjectMatches(h, FenceV1("A", "u", "k").copy(ownerUid = "B"), setOf(PurgeScope.CAPABILITY)))
    }

    @Test fun HR_subjectUser() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertFalse(F.eligible("HR_subjectUser"), HoldRecoveryBoundary.subjectMatches(h, FenceV1("A", "u", "k").copy(userAccessEpoch = "u2"), setOf(PurgeScope.CAPABILITY)))
    }

    @Test fun HR_subjectKrx() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertFalse(F.eligible("HR_subjectKrx"), HoldRecoveryBoundary.subjectMatches(h, FenceV1("A", "u", "k").copy(krxCapabilityEpoch = "k2"), setOf(PurgeScope.CAPABILITY)))
    }

    @Test fun HR_subjectAxes() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertFalse(F.eligible("HR_subjectAxes"), HoldRecoveryBoundary.subjectMatches(h, FenceV1("A", "u", "k"), setOf(PurgeScope.USER)))
    }

    @Test fun HR_emptyAxes() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNull(F.atomic("HR_emptyAxes"), HoldRecoveryBoundary.minimumIntent(h.copy(axes = emptySet())))
    }

    @Test fun HR_intent() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(RefreshIntent.FORCE_PREMIUM, (h.provenance as HoldProvenanceV1.Query).started.intent)
        assertFalse(F.atomic("HR_intent"), HoldRecoveryBoundary.intentSatisfies(h, RefreshIntent.FORCE_ENTITLEMENTS))
    }

    @Test fun HR_queryMax() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(RefreshIntent.FORCE_PREMIUM, (h.provenance as HoldProvenanceV1.Query).started.intent)
        assertEquals(F.atomic("HR_queryMax"), RefreshIntent.FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(h))
    }

    @Test fun HR_intentLowerBound() {
        val source = RecoveryIntentV1("r", "session", "A", PurgeScope.USER, null)
        assertEquals(PurgeScope.USER, source.axis)
        assertFalse(F.atomic("HR_intentLowerBound"), RecoveryIntentLowerBound.intentSatisfies(source, RefreshIntent.FORCE_ENTITLEMENTS))
    }

    @Test fun HR_sourcePin() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_sourcePin"), HoldRecoveryBoundary.closureProblem(F.hold(id = "other-h"), c, rt, "current-tracker"))
    }

    @Test fun HR_executorPin() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_executorPin"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c, b = F.binding.copy(executor = F.executor.copy(binding = 4)), work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_entries() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_entries"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c, closed = false, work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_captureClosed() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_captureClosed"), HoldRecoveryBoundary.closureProblem(i.source, F.same(closed = false), F.runtime(F.same(closed = false), work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_joined() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_joined"), HoldRecoveryBoundary.closureProblem(i.source, F.same(joined = emptySet()), F.runtime(F.same(joined = emptySet()), work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_registered() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_registered"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c, work = setOf("w", "late")), "current-tracker"))
    }

    @Test fun HR_generation() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_generation"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c, generation = 6, work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_previousEmpty() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        val bad = c.copy(previousTrackingLifetimeId = "")
        assertNotNull(F.eligible("HR_previousEmpty"), HoldRecoveryBoundary.closureProblem(i.source, bad, F.runtime(bad), "current-tracker"))
    }

    @Test fun HR_previousSame() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        val bad = c.copy(previousTrackingLifetimeId = "current-tracker")
        assertNotNull(F.eligible("HR_previousSame"), HoldRecoveryBoundary.closureProblem(i.source, bad, F.runtime(bad), "current-tracker"))
    }

    @Test fun HR_callerEnded() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        val bad = c.copy(previousCallerEnded = false)
        assertNotNull(F.eligible("HR_callerEnded"), HoldRecoveryBoundary.closureProblem(i.source, bad, F.runtime(bad), "current-tracker"))
    }

    @Test fun HR_storageEnded() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        val bad = c.copy(previousStorageScopeEnded = false)
        assertNotNull(F.eligible("HR_storageEnded"), HoldRecoveryBoundary.closureProblem(i.source, bad, F.runtime(bad), "current-tracker"))
    }

    @Test fun HR_restartWork() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        assertNotNull(F.eligible("HR_restartWork"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c, work = setOf("late")), "current-tracker"))
    }

    @Test fun HR_actualSource() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualSource"), HoldRecoveryBoundary.closureMatches(c, F.same(F.hold(id = "other-h"))))
    }

    @Test fun HR_actualExecutor() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualExecutor"), HoldRecoveryBoundary.closureMatches(c, F.same(exec = F.executor.copy(binding = 4))))
    }

    @Test fun HR_actualGeneration() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualGeneration"), HoldRecoveryBoundary.closureMatches(c, F.same(generation = 6)))
    }

    @Test fun HR_actualClosed() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualClosed"), HoldRecoveryBoundary.closureMatches(c, F.same(closed = false)))
    }

    @Test fun HR_actualCaptured() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualCaptured"), HoldRecoveryBoundary.closureMatches(c, F.same(captured = emptySet())))
    }

    @Test fun HR_actualJoined() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualJoined"), HoldRecoveryBoundary.closureMatches(c, F.same(joined = emptySet())))
    }

    @Test fun HR_actualPrevious() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        assertFalse(F.eligible("HR_actualPrevious"), HoldRecoveryBoundary.closureMatches(c, c.copy(previousTrackingLifetimeId = "other-tracker")))
    }

    @Test fun HR_actualCaller() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        assertFalse(F.eligible("HR_actualCaller"), HoldRecoveryBoundary.closureMatches(c, c.copy(previousCallerEnded = false)))
    }

    @Test fun HR_actualStorage() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        assertFalse(F.eligible("HR_actualStorage"), HoldRecoveryBoundary.closureMatches(c, c.copy(previousStorageScopeEnded = false)))
    }

    @Test fun HR_actualSameKind() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertFalse(F.eligible("HR_actualSameKind"), HoldRecoveryBoundary.closureMatches(c, F.restart()))
    }

    @Test fun HR_actualRestartKind() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.restart(i.source)
        assertEquals("previous-tracker", c.previousTrackingLifetimeId)
        assertTrue(c.previousCallerEnded); assertTrue(c.previousStorageScopeEnded)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(c), "current-tracker"))
        assertFalse(F.eligible("HR_actualRestartKind"), HoldRecoveryBoundary.closureMatches(c, F.same()))
    }

    @Test fun HR_closureCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val c = F.same(i.source)
        assertEquals(setOf("w"), c.capturedWork); assertEquals(setOf("w"), c.joinedWork)
        assertTrue(c.entriesClosed); assertEquals(5L, c.generationAtCapture)
        val rt = F.runtime(c, work = setOf("w"))
        assertEquals(F.binding, rt.binding); assertEquals(setOf("w"), rt.registeredWork)
        assertNull(HoldRecoveryBoundary.closureProblem(i.source, c, rt, "current-tracker"))
        assertNotNull(F.eligible("HR_closureCall"), HoldRecoveryBoundary.closureProblem(i.source, c, F.runtime(F.same(generation = 6), work = setOf("w")), "current-tracker"))
    }

    @Test fun HR_axisMember() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_axisMember"), RecoveryRetirementBoundary.axisProblem(source, PurgeScope.USER, F.beforeFence))
    }

    @Test fun HR_emptyUser() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_emptyUser"), RecoveryRetirementBoundary.inputProblem(source, F.beforeFence.copy(userAccessEpoch = ""), F.ids.epochs))
    }

    @Test fun HR_emptyKrx() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_emptyKrx"), RecoveryRetirementBoundary.inputProblem(source, F.beforeFence.copy(krxCapabilityEpoch = ""), F.ids.epochs))
    }

    @Test fun HR_journalEmpty() {
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable(null))
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable("ordinary"))
        assertFalse(F.eligible("HR_journalEmpty"), RecoveryRetirementBoundary.journalFieldRepresentable(""))
    }

    @Test fun HR_journalDelimiter() {
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable(null))
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable("ordinary"))
        assertFalse(F.eligible("HR_journalDelimiter"), RecoveryRetirementBoundary.journalFieldRepresentable("a|b"))
    }

    @Test fun HR_journalNewline() {
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable(null))
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable("ordinary"))
        assertFalse(F.eligible("HR_journalNewline"), RecoveryRetirementBoundary.journalFieldRepresentable("a\nb"))
    }

    @Test fun HR_journalSurrogate() {
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable(null))
        assertTrue(RecoveryRetirementBoundary.journalFieldRepresentable("ordinary"))
        assertFalse(F.eligible("HR_journalSurrogate"), RecoveryRetirementBoundary.journalFieldRepresentable("a\uD800"))
    }

    @Test fun HR_uuid() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_uuid"), RecoveryRetirementBoundary.inputProblem(source, F.beforeFence, RecoveryFreshEpochs(null, "not-uuid")))
    }

    @Test fun HR_beforeUser() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_beforeUser"), RecoveryRetirementBoundary.inputProblem(source, F.beforeFence.copy(userAccessEpoch = F.userEpoch), RecoveryFreshEpochs(null, F.userEpoch)))
    }

    @Test fun HR_beforeKrx() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val changedSource = checkNotNull(HoldRecoverySource.from(F.hold(krx = F.krxEpoch)))
        assertNotNull(F.eligible("HR_beforeKrx"), RecoveryRetirementBoundary.inputProblem(changedSource, F.beforeFence.copy(krxCapabilityEpoch = F.krxEpoch), RecoveryFreshEpochs(null, F.krxEpoch)))
    }

    @Test fun HR_otherFresh() {
        val h = F.hold(both = true, floor = false)
        val source = checkNotNull(HoldRecoverySource.from(h))
        assertEquals(setOf(PurgeScope.USER, PurgeScope.CAPABILITY), source.axes)
        assertEquals(FenceV1("A", "u", "k"), source.subject)
        assertNotNull(F.eligible("HR_otherFresh"), RecoveryRetirementBoundary.inputProblem(source, F.beforeFence, RecoveryFreshEpochs(F.userEpoch, F.userEpoch)))
    }

    @Test fun HR_journalOwnerCall() {
        val s = checkNotNull(HoldRecoverySource.from(F.hold(owner = "A|B")))
        val b = FenceV1("A|B", "u", "k")
        assertEquals(s.subject, b)
        assertNotNull(F.eligible("HR_journalOwnerCall"), RecoveryRetirementBoundary.inputProblem(s, b, F.ids.epochs))
    }

    @Test fun HR_journalEpochCall() {
        val s = checkNotNull(HoldRecoverySource.from(F.hold(krx = "k|bad")))
        val b = FenceV1("A", "u", "k|bad")
        assertEquals(s.subject, b)
        assertNotNull(F.eligible("HR_journalEpochCall"), RecoveryRetirementBoundary.inputProblem(s, b, F.ids.epochs))
    }

    @Test fun HR_unreadableCurrent() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_unreadableCurrent"), RecoveryRetirementBoundary.axisProblem(source, PurgeScope.CAPABILITY, F.beforeFence.copy(krxCapabilityEpoch = null)))
    }

    @Test fun HR_nullTargetReadable() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val s = checkNotNull(HoldRecoverySource.from(F.hold(krx = null)))
        assertNull(F.eligible("HR_nullTargetReadable"), RecoveryRetirementBoundary.axisProblem(s, PurgeScope.CAPABILITY, F.beforeFence.copy(krxCapabilityEpoch = null)))
    }

    @Test fun HR_departedOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_departedOwner"), RecoveryRetirementBoundary.axisProblem(source, PurgeScope.CAPABILITY, F.beforeFence.copy(ownerUid = "B")))
    }

    @Test fun HR_departedNonNull() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val s = checkNotNull(HoldRecoverySource.from(F.hold(krx = null)))
        assertNull(F.eligible("HR_departedNonNull"), RecoveryRetirementBoundary.axisProblem(s, PurgeScope.CAPABILITY, FenceV1("B", "u", null)))
    }

    @Test fun HR_departedEqual() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertNotNull(F.eligible("HR_departedEqual"), RecoveryRetirementBoundary.axisProblem(source, PurgeScope.CAPABILITY, F.beforeFence.copy(ownerUid = "B")))
    }

    @Test fun HR_planBefore() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planBefore"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(before = F.beforeFence.copy(userAccessEpoch = "other"))))
    }

    @Test fun HR_planOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planOwner"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(after = FenceV1("B", "u", F.krxEpoch))))
    }

    @Test fun HR_planAxes() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planAxes"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(a = axes + RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.USER, "u"), RecoveryAxisAction.Preserve))))
    }

    @Test fun HR_planEpoch() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planEpoch"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(after = F.beforeFence)))
    }

    @Test fun HR_planTargetOwner() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planTargetOwner"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(a = listOf(axes.single().copy(target = axes.single().target.copy(ownerUid = "B"))))))
    }

    @Test fun HR_planTargetEpoch() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planTargetEpoch"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(a = listOf(axes.single().copy(target = axes.single().target.copy(epoch = "other"))))))
    }

    @Test fun HR_planAction() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planAction"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(a = listOf(axes.single().copy(action = RecoveryAxisAction.Preserve)))))
    }

    @Test fun HR_planJournal() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planJournal"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(j = emptyList())))
    }

    @Test fun HR_planRequest() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), RecoveryAxisAction.Rotate(F.krxEpoch)))
        val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
        fun p(before: FenceV1 = F.beforeFence, after: FenceV1 = FenceV1("A", "u", F.krxEpoch),
            a: List<RecoveryAxisRetirement> = axes, j: List<PendingPurge> = journal,
            req: RecoveryRequestRequirement = RecoveryRequestRequirement.RequiredCurrentOwner) = RecoveryRetirementPlan(before, after, a, j, req)
        assertTrue(RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p()))
        assertFalse(F.atomic("HR_planRequest"), RecoveryRetirementBoundary.validPlan(source, F.beforeFence, F.ids.epochs, p(req = RecoveryRequestRequirement.NotRequiredDepartedOwner)))
    }

    @Test fun HR_orderLast() {
        val s = LifecycleOrderSource(F.life, 21)
        assertEquals(F.atomic("HR_orderLast"), 22L, s.issue(1)?.value)
    }

    @Test fun HR_orderBinding() {
        val s = LifecycleOrderSource(F.life, 1)
        assertEquals(F.atomic("HR_orderBinding"), 23L, s.issue(22)?.value)
    }

    @Test fun HR_orderAfter() {
        val s = LifecycleOrderSource(F.life, 1)
        assertEquals(F.atomic("HR_orderAfter"), 24L, s.issue(2, 23)?.value)
    }

    @Test fun HR_orderAdvance() {
        val s = LifecycleOrderSource(F.life, 21)
        assertEquals(22L, s.issue(1)?.value)
        assertEquals(F.atomic("HR_orderAdvance"), 23L, s.issue(1)?.value)
    }

    @Test fun HR_orderLastValid() {
        val s = LifecycleOrderSource(F.life, Long.MAX_VALUE - 1)
        assertEquals(F.atomic("HR_orderLastValid"), Long.MAX_VALUE, s.issue(1)?.value)
        assertNull(s.issue(1))
    }

    @Test fun HR_queryUser() {
        val h = ControlSchema.read(ControlKind.HOLD, F.hold(topic = false, both = true, floor = false, intent = RefreshIntent.IF_STALE)) as RestoredHold
        assertEquals(setOf(PurgeScope.USER, PurgeScope.CAPABILITY), h.axes)
        assertEquals(F.atomic("HR_queryUser"), RefreshIntent.FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(h))
    }

    @Test fun HR_topicUser() {
        val h = ControlSchema.read(ControlKind.HOLD, F.hold(topic = true, both = true, floor = false, intent = RefreshIntent.IF_STALE)) as RestoredHold
        assertEquals(setOf(PurgeScope.USER, PurgeScope.CAPABILITY), h.axes)
        assertEquals(F.atomic("HR_topicUser"), RefreshIntent.FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(h))
    }

    @Test fun HR_queryCap() {
        val h = ControlSchema.read(ControlKind.HOLD, F.hold(topic = false, both = false, floor = false, intent = RefreshIntent.IF_STALE)) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY), h.axes)
        assertEquals(F.atomic("HR_queryCap"), RefreshIntent.FORCE_ENTITLEMENTS, HoldRecoveryBoundary.minimumIntent(h))
    }

    @Test fun HR_topicCap() {
        val h = ControlSchema.read(ControlKind.HOLD, F.hold(topic = true, both = false, floor = false, intent = RefreshIntent.FORCE_PREMIUM)) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY), h.axes)
        assertEquals(F.atomic("HR_topicCap"), RefreshIntent.FORCE_ENTITLEMENTS, HoldRecoveryBoundary.minimumIntent(h))
    }

    @Test fun HR_intentAxisUSER() {
        val source = RecoveryIntentV1("r", "session", "A", PurgeScope.USER, null)
        assertEquals(PurgeScope.USER, source.axis)
        assertEquals(F.atomic("HR_intentAxisUSER"), RefreshIntent.FORCE_PREMIUM, RecoveryIntentLowerBound.minimumIntent(source))
    }

    @Test fun HR_intentAxisCAPABILITY() {
        val source = RecoveryIntentV1("r", "session", "A", PurgeScope.CAPABILITY, null)
        assertEquals(PurgeScope.CAPABILITY, source.axis)
        assertEquals(F.atomic("HR_intentAxisCAPABILITY"), RefreshIntent.FORCE_ENTITLEMENTS, RecoveryIntentLowerBound.minimumIntent(source))
    }

    @Test fun HR_rotateOwner() {
        val s = checkNotNull(HoldRecoverySource.from(F.hold(krx = null)))
        assertEquals(setOf(PurgeScope.CAPABILITY), s.axes)
        assertEquals(F.atomic("HR_rotateOwner"), false, RecoveryRetirementBoundary.rotationRequired(s,PurgeScope.CAPABILITY,FenceV1("B","u","k")))
    }

    @Test fun HR_rotateNull() {
        val s = checkNotNull(HoldRecoverySource.from(F.hold(krx = null)))
        assertEquals(setOf(PurgeScope.CAPABILITY), s.axes)
        assertEquals(F.atomic("HR_rotateNull"), true, RecoveryRetirementBoundary.rotationRequired(s,PurgeScope.CAPABILITY,F.beforeFence))
    }

    @Test fun HR_rotateEqual() {
        val s = checkNotNull(HoldRecoverySource.from(F.hold()))
        assertEquals(setOf(PurgeScope.CAPABILITY), s.axes)
        assertEquals(F.atomic("HR_rotateEqual"), true, RecoveryRetirementBoundary.rotationRequired(s,PurgeScope.CAPABILITY,F.beforeFence))
    }

    @Test fun HR_planInputCall() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val bad = RecoveryFreshEpochs(null,"not-uuid")
        assertNotNull(RecoveryRetirementBoundary.inputProblem(source,F.beforeFence,bad))
        assertTrue(F.eligible("HR_planInputCall"), planRecoveryRetirement(source,F.beforeFence,bad) is RecoveryRetirementResult.Failed)
    }

    @Test fun HR_fixedCreate() {
        val before: ControlNode? = null
        val after: ControlNode? = F.guard()
        assertNotNull(after)
        assertEquals(F.atomic("HR_fixedCreate"),LifecycleEffect.CREATE,fixed("g",LifecycleRole.GUARD,before,after).target.effect)
    }

    @Test fun HR_fixedReplace() {
        val before: ControlNode? = F.guard()
        val after: ControlNode? = F.field(F.guard(),"floor",FloorGuardFixtures.floor(29000,"boot",11000,"new-life"))
        assertNotNull(after)
        assertEquals(F.atomic("HR_fixedReplace"),LifecycleEffect.REPLACE,fixed("g",LifecycleRole.GUARD,before,after).target.effect)
    }

    @Test fun HR_capturedDetached() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryClosure.SameProcess(F.hold(),F.executor,5,true,supplied,setOf("a","b"))
        assertEquals(setOf("a","b"),value.capturedWork)
        supplied.clear()
        assertEquals(F.atomic("HR_capturedDetached"),setOf("a","b"),value.capturedWork)
    }

    @Test fun HR_capturedImmutable() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryClosure.SameProcess(F.hold(),F.executor,5,true,supplied,setOf("a","b"))
        assertEquals(setOf("a","b"),value.capturedWork)
        try { (value.capturedWork as MutableSet<String>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_capturedImmutable"),setOf("a","b"),value.capturedWork)
    }

    @Test fun HR_joinedDetached() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryClosure.SameProcess(F.hold(),F.executor,5,true,setOf("a","b"),supplied)
        assertEquals(setOf("a","b"),value.joinedWork)
        supplied.clear()
        assertEquals(F.atomic("HR_joinedDetached"),setOf("a","b"),value.joinedWork)
    }

    @Test fun HR_joinedImmutable() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryClosure.SameProcess(F.hold(),F.executor,5,true,setOf("a","b"),supplied)
        assertEquals(setOf("a","b"),value.joinedWork)
        try { (value.joinedWork as MutableSet<String>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_joinedImmutable"),setOf("a","b"),value.joinedWork)
    }

    @Test fun HR_registeredDetached() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryRuntime(F.binding,5,true,supplied,F.same())
        assertEquals(setOf("a","b"),value.registeredWork)
        supplied.clear()
        assertEquals(F.atomic("HR_registeredDetached"),setOf("a","b"),value.registeredWork)
    }

    @Test fun HR_registeredImmutable() {
        val supplied=linkedSetOf("a","b")
        val value=HoldRecoveryRuntime(F.binding,5,true,supplied,F.same())
        assertEquals(setOf("a","b"),value.registeredWork)
        try { (value.registeredWork as MutableSet<String>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_registeredImmutable"),setOf("a","b"),value.registeredWork)
    }

    @Test fun HR_axesImmutable() {
        val h=F.hold(both=true,floor=false)
        val source=checkNotNull(HoldRecoverySource.from(h))
        assertEquals(setOf(PurgeScope.USER,PurgeScope.CAPABILITY),source.axes)
        try { (source.axes as MutableSet<PurgeScope>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_axesImmutable"),setOf(PurgeScope.USER,PurgeScope.CAPABILITY),source.axes)
    }

    @Test fun HR_grantOrigin() {
        val source=LifecycleOrderSource(LifetimeId("new-life"),21)
        assertEquals(F.atomic("HR_grantOrigin"),LifecycleOrderGrant(LifetimeId("new-life"),21,5,18,22),source.issue(5,18))
    }

    @Test fun HR_grantPrevious() {
        val source=LifecycleOrderSource(LifetimeId("new-life"),21)
        assertEquals(F.atomic("HR_grantPrevious"),LifecycleOrderGrant(LifetimeId("new-life"),21,5,18,22),source.issue(5,18))
    }

    @Test fun HR_grantBinding() {
        val source=LifecycleOrderSource(LifetimeId("new-life"),21)
        assertEquals(F.atomic("HR_grantBinding"),LifecycleOrderGrant(LifetimeId("new-life"),21,5,18,22),source.issue(5,18))
    }

    @Test fun HR_grantAfter() {
        val source=LifecycleOrderSource(LifetimeId("new-life"),21)
        assertEquals(F.atomic("HR_grantAfter"),LifecycleOrderGrant(LifetimeId("new-life"),21,5,18,22),source.issue(5,18))
    }

    @Test fun HR_grantValue() {
        val source=LifecycleOrderSource(LifetimeId("new-life"),21)
        assertEquals(F.atomic("HR_grantValue"),LifecycleOrderGrant(LifetimeId("new-life"),21,5,18,22),source.issue(5,18))
    }

    @Test fun HR_typedAxesImmutable() {
        val source=checkNotNull(HoldRecoverySource.from(F.hold(both=true,floor=false)))
        assertEquals(setOf(PurgeScope.USER,PurgeScope.CAPABILITY),source.hold.axes)
        try { (source.hold.axes as MutableSet<PurgeScope>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_typedAxesImmutable"),setOf(PurgeScope.USER,PurgeScope.CAPABILITY),source.hold.axes)
    }

    @Test fun HR_planAxesDetached() {
        val a=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.USER,"u"),RecoveryAxisAction.Rotate(F.userEpoch))
        val b=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.CAPABILITY,"k"),RecoveryAxisAction.Rotate(F.krxEpoch))
        val axes=mutableListOf(a,b)
        val scopes=linkedSetOf(PurgeScope.USER,PurgeScope.CAPABILITY)
        val journal=mutableListOf(PendingPurge("A","u","k",scopes),PendingPurge("B","u2",null,setOf(PurgeScope.USER)))
        val p=RecoveryRetirementPlan(F.beforeFence,FenceV1("A",F.userEpoch,F.krxEpoch),axes,journal,RecoveryRequestRequirement.RequiredCurrentOwner)
        // Direct collection constructor contract; broad journal is not claimed to be an eligible recovery plan.
        assertEquals(listOf(a,b),p.axes);assertEquals(2,p.journal.size)
        axes.clear()
        assertEquals(F.atomic("HR_planAxesDetached"),listOf(a,b),p.axes)
    }

    @Test fun HR_planAxesImmutable() {
        val a=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.USER,"u"),RecoveryAxisAction.Rotate(F.userEpoch))
        val b=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.CAPABILITY,"k"),RecoveryAxisAction.Rotate(F.krxEpoch))
        val axes=mutableListOf(a,b)
        val scopes=linkedSetOf(PurgeScope.USER,PurgeScope.CAPABILITY)
        val journal=mutableListOf(PendingPurge("A","u","k",scopes),PendingPurge("B","u2",null,setOf(PurgeScope.USER)))
        val p=RecoveryRetirementPlan(F.beforeFence,FenceV1("A",F.userEpoch,F.krxEpoch),axes,journal,RecoveryRequestRequirement.RequiredCurrentOwner)
        // Direct collection constructor contract; broad journal is not claimed to be an eligible recovery plan.
        assertEquals(listOf(a,b),p.axes);assertEquals(2,p.journal.size)
        try { (p.axes as MutableList<RecoveryAxisRetirement>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_planAxesImmutable"),listOf(a,b),p.axes)
    }

    @Test fun HR_planJournalImmutable() {
        val a=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.USER,"u"),RecoveryAxisAction.Rotate(F.userEpoch))
        val b=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.CAPABILITY,"k"),RecoveryAxisAction.Rotate(F.krxEpoch))
        val axes=mutableListOf(a,b)
        val scopes=linkedSetOf(PurgeScope.USER,PurgeScope.CAPABILITY)
        val journal=mutableListOf(PendingPurge("A","u","k",scopes),PendingPurge("B","u2",null,setOf(PurgeScope.USER)))
        val p=RecoveryRetirementPlan(F.beforeFence,FenceV1("A",F.userEpoch,F.krxEpoch),axes,journal,RecoveryRequestRequirement.RequiredCurrentOwner)
        // Direct collection constructor contract; broad journal is not claimed to be an eligible recovery plan.
        assertEquals(listOf(a,b),p.axes);assertEquals(2,p.journal.size)
        try { (p.journal as MutableList<PendingPurge>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_planJournalImmutable"),2,p.journal.size)
    }

    @Test fun HR_planScopesDetached() {
        val a=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.USER,"u"),RecoveryAxisAction.Rotate(F.userEpoch))
        val b=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.CAPABILITY,"k"),RecoveryAxisAction.Rotate(F.krxEpoch))
        val axes=mutableListOf(a,b)
        val scopes=linkedSetOf(PurgeScope.USER,PurgeScope.CAPABILITY)
        val journal=mutableListOf(PendingPurge("A","u","k",scopes),PendingPurge("B","u2",null,setOf(PurgeScope.USER)))
        val p=RecoveryRetirementPlan(F.beforeFence,FenceV1("A",F.userEpoch,F.krxEpoch),axes,journal,RecoveryRequestRequirement.RequiredCurrentOwner)
        // Direct collection constructor contract; broad journal is not claimed to be an eligible recovery plan.
        assertEquals(listOf(a,b),p.axes);assertEquals(2,p.journal.size)
        scopes.clear()
        assertEquals(F.atomic("HR_planScopesDetached"),setOf(PurgeScope.USER,PurgeScope.CAPABILITY),p.journal.first().scopes)
    }

    @Test fun HR_planScopesImmutable() {
        val a=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.USER,"u"),RecoveryAxisAction.Rotate(F.userEpoch))
        val b=RecoveryAxisRetirement(JournalTargetV1("A",PurgeScope.CAPABILITY,"k"),RecoveryAxisAction.Rotate(F.krxEpoch))
        val axes=mutableListOf(a,b)
        val scopes=linkedSetOf(PurgeScope.USER,PurgeScope.CAPABILITY)
        val journal=mutableListOf(PendingPurge("A","u","k",scopes),PendingPurge("B","u2",null,setOf(PurgeScope.USER)))
        val p=RecoveryRetirementPlan(F.beforeFence,FenceV1("A",F.userEpoch,F.krxEpoch),axes,journal,RecoveryRequestRequirement.RequiredCurrentOwner)
        // Direct collection constructor contract; broad journal is not claimed to be an eligible recovery plan.
        assertEquals(listOf(a,b),p.axes);assertEquals(2,p.journal.size)
        try { (p.journal.first().scopes as MutableSet<PurgeScope>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_planScopesImmutable"),setOf(PurgeScope.USER,PurgeScope.CAPABILITY),p.journal.first().scopes)
    }

    @Test fun HR_unknownFiveFields() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val bad=raw.toMutablePreferences().apply { this[PURGE_JOURNAL]="A||k|CAPABILITY|future" }
        val problem=RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(bad))
        assertNotNull(problem)
        assertEquals(F.eligible("HR_unknownFiveFields"),HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired),problem)
    }

    @Test fun HR_extractQueryOwner() {
        val node=F.hold(topic=false,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractQueryOwner"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractTopicOwner() {
        val node=F.hold(topic=true,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractTopicOwner"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractQueryUser() {
        val node=F.hold(topic=false,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractQueryUser"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractTopicUser() {
        val node=F.hold(topic=true,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractTopicUser"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractQueryKrx() {
        val node=F.hold(topic=false,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractQueryKrx"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractTopicKrx() {
        val node=F.hold(topic=true,floor=false)
        val h=ControlSchema.read(ControlKind.HOLD,node) as RestoredHold
        val archived=when(val p=h.provenance) { is HoldProvenanceV1.Query -> p.started.fence; is HoldProvenanceV1.Topic -> p.context.access }
        assertEquals(FenceV1("A","u","k"),archived)
        assertEquals(F.eligible("HR_extractTopicKrx"),FenceV1("A","u","k"),HoldRecoverySource.from(node)?.subject)
    }

    @Test fun HR_extractAxes() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        assertEquals(F.eligible("HR_extractAxes"),setOf(PurgeScope.CAPABILITY),source.axes)
    }

    @Test fun HR_reserveSeal() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val row=ControlObligationFixtures.seal.replace("old",F.krxEpoch)
        val candidate=raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)]= "["+row+"]"
        }.toPreferences()
        assertFalse(F.read(candidate).hasUninterpretable)
        assertNotNull(F.eligible("HR_reserveSeal"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_reserveWitness() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val row=ControlObligationFixtures.settledSeal.replace("\"u\"","\"${F.krxEpoch}\"")
        val candidate=raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)]= "["+row+"]"
        }.toPreferences()
        assertFalse(F.read(candidate).hasUninterpretable)
        assertNotNull(F.eligible("HR_reserveWitness"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_reserveQuery() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val row=F.hold(krx=F.krxEpoch,id="other-h").toPayloadEntry().fields.toString()
        val candidate=raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)]= "["+i.source.toPayloadEntry().fields+","+row+"]"
        }.toPreferences()
        assertFalse(F.read(candidate).hasUninterpretable)
        assertNotNull(F.eligible("HR_reserveQuery"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_reserveTopic() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val row=F.hold(krx=F.krxEpoch,id="other-h",topic=true,floor=false).toPayloadEntry().fields.toString()
        val candidate=raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)]= "["+i.source.toPayloadEntry().fields+","+row+"]"
        }.toPreferences()
        assertFalse(F.read(candidate).hasUninterpretable)
        assertNotNull(F.eligible("HR_reserveTopic"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_reserveIntent() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val row=ControlObligationFixtures.recovery.replace("\"targetEpoch\":null","\"targetEpoch\":\"${F.krxEpoch}\"")
        val candidate=raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]= "["+row+"]"
        }.toPreferences()
        assertFalse(F.read(candidate).hasUninterpretable)
        assertNotNull(F.eligible("HR_reserveIntent"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_reserveJournal() {
        val i = F.input(); val raw = F.before(i)
        F.assertFixture(i, raw)
        val h = ControlSchema.read(ControlKind.HOLD, i.source) as RestoredHold
        val source = checkNotNull(HoldRecoverySource.from(i.source))
        val candidate=raw.toMutablePreferences().apply { this[PURGE_JOURNAL]="B|${F.krxEpoch}||USER" }.toPreferences()
        assertNotNull(F.eligible("HR_reserveJournal"),RecoveryRetirementBoundary.freshEpochsProblem(source,F.beforeFence,F.ids.epochs,F.read(candidate)))
    }

    @Test fun HR_planOutputImmutable() {
        val p=F.plan()
        val expected=listOf(LifecycleTarget(ControlKind.HOLD,"h",LifecycleEffect.REMOVE),LifecycleTarget(ControlKind.DEMAND,"new-request",LifecycleEffect.CREATE),LifecycleTarget(ControlKind.DEMAND,"g",LifecycleEffect.REPLACE))
        assertEquals(expected,p.targets.map { it.target })
        try { (p.targets as MutableList<LifecycleFixedTarget>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_planOutputImmutable"),expected,p.targets.map { it.target })
        val unchanged=F.plan(F.input(F.hold(floor=false),F.guard()))
        assertEquals(listOf("g"),unchanged.requiredUnchanged.map { it.target.id })
        try { (unchanged.requiredUnchanged as MutableList<LifecycleFixedTarget>).clear() } catch(_:UnsupportedOperationException) { }
        assertEquals(F.atomic("HR_planOutputImmutable"),listOf("g"),unchanged.requiredUnchanged.map { it.target.id })
    }
}
