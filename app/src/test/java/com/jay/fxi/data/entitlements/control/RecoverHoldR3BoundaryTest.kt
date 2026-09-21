package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F
import org.junit.Assert.*
import org.junit.Test

/** Direct boundaries: no writer getter/fence may mask the predicate under test. */
class RecoverHoldR3BoundaryTest {
    private fun rawType(id: String, key: Preferences.Key<*>, marker: Boolean) {
        // All seven actual-type axes are populated. Teardown's value is type-only fixture data;
        // this direct rawProblem boundary does not perform business admission or currentness.
        val normal = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[TEARDOWN_OWED_FOR] = "B"; this[PURGE_JOURNAL] = "A|u||USER"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = false
        }.toPreferences()
        val expected: Map<String, Any> = mapOf(
            OWNER_UID.name to "A", USER_EPOCH.name to "u", KRX_EPOCH.name to "k",
            TEARDOWN_OWED_FOR.name to "B", PURGE_JOURNAL.name to "A|u||USER",
            MAY_CONTAIN_PREMIUM.name to true, MAY_CONTAIN_KRX.name to false
        )
        val normalMap = normal.asMap().mapKeys { it.key.name }
        assertEquals(expected, normalMap.filterKeys { it in expected })
        assertNull(ControlLifecycleBoundary.rawProblem(normal))
        val bad = normal.toMutablePreferences().apply {
            if (marker) this[stringPreferencesKey(key.name)] = "not-a-boolean"
            else this[longPreferencesKey(key.name)] = 404L
        }.toPreferences()
        val actual = bad.asMap().mapKeys { it.key.name }
        if (marker) {
            assertTrue(actual[key.name] is String)
            assertEquals("not-a-boolean", actual[key.name])
        } else {
            assertTrue(actual[key.name] is Long)
            assertEquals(404L, actual[key.name])
        }
        // Literal expectations establish all six non-target actual types and values first.
        assertEquals(expected.filterKeys { it != key.name }, actual.filterKeys { it in expected && it != key.name })
        assertNotNull(F.eligible(id), ControlLifecycleBoundary.rawProblem(bad))
    }

    @Test fun LC_rawOwnerType() {
        rawType("LC_rawOwnerType", OWNER_UID, marker = false)
    }

    @Test fun LC_rawUserEpochType() {
        rawType("LC_rawUserEpochType", USER_EPOCH, marker = false)
    }

    @Test fun LC_rawKrxEpochType() {
        rawType("LC_rawKrxEpochType", KRX_EPOCH, marker = false)
    }

    @Test fun LC_rawTeardownType() {
        rawType("LC_rawTeardownType", TEARDOWN_OWED_FOR, marker = false)
    }

    @Test fun LC_rawJournalType() {
        rawType("LC_rawJournalType", PURGE_JOURNAL, marker = false)
    }

    @Test fun LC_rawPremiumMarkerType() {
        rawType("LC_rawPremiumMarkerType", MAY_CONTAIN_PREMIUM, marker = true)
    }

    @Test fun LC_sameNewIds() {
        val i = F.input(g = null); val before = F.before(i)
        F.assertFixture(i, before); assertNull(i.guard)
        assertTrue(F.read(before).locations("shared").isEmpty())
        val ids = F.ids.copy(requestId = "shared", guardId = "shared")
        val p = F.plan(i, ids); assertNull(p.preparationProblem)
        val d = p.descriptor()
        assertEquals("00000000-0000-0000-0000-000000000101", d.operationId)
        assertEquals(LifecycleTransition.RECOVER_HOLD, d.transition)
        assertEquals(SettlementExecutor("A", 3, LifetimeId("new-life")), d.executor)
        assertEquals(emptyList<LifecycleFixedTarget>(), d.requiredUnchanged)
        assertEquals(listOf(LifecycleRole.HOLD, LifecycleRole.REQUEST, LifecycleRole.GUARD), d.targets.map { it.role })
        assertEquals(listOf(
            LifecycleTarget(ControlKind.HOLD, "h", LifecycleEffect.REMOVE),
            LifecycleTarget(ControlKind.DEMAND, "shared", LifecycleEffect.CREATE),
            LifecycleTarget(ControlKind.DEMAND, "shared", LifecycleEffect.CREATE)
        ), d.targets.map { it.target })
        assertEquals(i.source.toPayloadEntry(), d.targets[0].before?.toPayloadEntry()); assertNull(d.targets[0].after)
        assertNull(d.targets[1].before); assertNull(d.targets[2].before)
        val request = d.targets[1].after!!; val scheduleGuard = d.targets[2].after!!
        val expectedRequest = F.node("""{"id":"shared","kind":"REQUEST","ownerUid":"A","binding":3,"intent":"FORCE_PREMIUM","originLifetimeId":"new-life","raisedAt":22}""")
        val expectedGuard = F.node("""{"id":"shared","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"boot","anchorElapsedMillis":11000,"waitMillis":29000,"originLifetimeId":"new-life"}}""")
        assertEquals(expectedRequest.toPayloadEntry(), request.toPayloadEntry())
        assertEquals(expectedGuard.toPayloadEntry(), scheduleGuard.toPayloadEntry())
        assertTrue(ControlSchema.read(ControlKind.DEMAND, request) is DemandV1)
        assertTrue(ControlSchema.read(ControlKind.DEMAND, scheduleGuard) is ScheduleGuardV1)
        assertEquals(FenceV1("A", "u", "k"), d.namespace?.before)
        assertEquals(FenceV1("A", "u", "00000000-0000-0000-0000-000000000012"), d.namespace?.after)
        assertEquals(listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), d.namespace?.journal)
        assertNull(d.namespace?.userMayContain); assertEquals(false, d.namespace?.krxMayContain)
        assertTrue(ControlLifecycleEvidence.validShape(d.transition, d.targets.map { it.target }))
        assertFalse(F.eligible("LC_sameNewIds"), ControlLifecycleConfirmation(F.codec).validDescriptor(d))
    }
}
