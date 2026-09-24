package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, seventy-fourth file — the independent output evidence STEP3ZK r18 판정 ④ asked for: in a fixture where a
 * builder that drops the source REQUEST REMOVE (settle, DemandAuthPlan.kt:46–48) still reaches Confirmed, first confirm
 * the store reached Confirmed as a premise, then assert the result of the removal on its own: the source REQUEST is gone
 * from the confirmed snapshot and from the re-read stored record.
 * Fixture: the C72 `settle` event (guard with a not-stopped AUTH, source REQUEST `r`, an unrelated sibling of owner B,
 * a consuming decision with a 30 s minimum delay and a FORCE_PREMIUM follow-up) — the plan keeps a successor REQUEST and
 * the guard REPLACE, so the target list is not empty when the REMOVE is missing (unlike the C29 fixtures, where the
 * target list empties and LC:190 refuses).
 */
class DemandAuthBacklogContract74Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c74 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c74 cleanup", 30000) { opened.forEach { it.close() } } }

    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val sibling = F.request(id = "sibling", owner = "B")

    @Test fun settle_sourceRemoved() = runReleaseTest {
        val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        val o = store(F.raw(g, r, sibling))
        assertEquals("fixture: the source REQUEST is stored once", 1, F.read(o.raw()).locations("r").size)
        val result = o.control.execute(o.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, orders), F.context(F.runtime()))
        assertTrue("fixture: the settle reaches Confirmed, got $result", result is ControlStoreResult.Confirmed)
        val snapshot = (result as ControlStoreResult.Confirmed).snapshot.record
        assertTrue(F.atomic("Z.Ce.settle.sourceRemoved"), snapshot.locations("r").isEmpty() && F.read(o.raw()).locations("r").isEmpty())
    }
}
