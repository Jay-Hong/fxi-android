package com.jay.fxi.ui.premium.graph

import com.jay.fxi.ui.premium.graph.TetherGraphData.EXCHANGES
import com.jay.fxi.ui.premium.graph.TetherGraphData.KRX
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** β §3 T01/T02, S-1 primary input (§8.3). No claim that the T timing windows are real PAC publications. */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherGraphKrxOnContractTest {
    private val evidenceRoot = File(if (File("app/src/test").isDirectory) ".beta_evidence/b1c/tmp" else "../.beta_evidence/b1c/tmp")
        .apply { mkdirs() }
    @get:Rule val folder = TemporaryFolder(evidenceRoot)
    private suspend fun TestScope.holderTest(body: suspend (KrxBetaHolderRig) -> Unit) {
        val r = KrxBetaHolderRig(this, folder)
        try { body(r) } finally { r.close() }
    }
    private fun assertHidden(row: String, r: KrxBetaHolderRig) {
        val state = r.f.state("tether")
        assertNotNull("contract($row): GENERAL chart", state.chart)
        assertFalse("contract($row): KRX chart zero", KRX in checkNotNull(state.chart).renderedIds)
        assertFalse("contract($row): KRX toggle zero", state.toggles.any { it.seriesId == KRX })
    }

    @Test fun T01_rotationAdoptsOldGeneralAndKeepsOnlyUnfilledDemandOnOriginalDeadline() = runTest {
        for (full in listOf(false, true)) holderTest { r ->
            val label = "contract(T01): full=$full"
            r.ready("T01")
            val f = r.f
            val h = f.holders.getValue("tether")
            val (deadline, old) = r.parkOld()
            // Distinguish keeping the original budget from silently creating another 3-second budget now.
            f.advance(600)
            val before = f.snapshot
            val oldToken = f.token("tether")
            val use = f.lifetime
            val source = f.coordinator.state.value.source
            val catalog = f.coordinator.state.value.catalog
            val display = f.display.value
            val focus = f.focus.value
            val selections = f.selections.committed.toMap()
            assertFalse("premise: old GENERAL 200 still held", old.answer.isCompleted)
            r.rotate()
            assertEquals("$label token", before.facts.token, f.snapshot.facts.token)
            assertEquals("$label U", before.userInvalidations, f.snapshot.userInvalidations)
            assertEquals("$label C+1", before.capabilityInvalidations + 1, f.snapshot.capabilityInvalidations)
            assertEquals("$label p", before.krxPermitSeq, f.snapshot.krxPermitSeq)
            assertEquals("$label GENERAL use", use, f.lifetime)
            assertEquals("$label source", source, f.coordinator.state.value.source)
            assertEquals("$label catalog", catalog, f.coordinator.state.value.catalog)
            assertEquals("$label owner", display, f.display.value)
            assertEquals("$label focus", focus, f.focus.value)
            assertEquals("$label selections retained", selections, f.selections.committed.toMap())
            assertHidden("T01", r)
            for (e in EXCHANGES) {
                assertEquals("$label $e demand retained", setOf(r.a1, r.a2), r.owed(e))
                assertTrue("$label $e observation retained", f.state("tether").chart!!.prepared.bySeries.getValue(r.id(e))
                    .linePoints.any { it.rate == r.observedRate(e) })
            }
            assertFalse("premise: no new answer adopted; old 200 still in its window", old.answer.isCompleted)
            old.answer.complete(r.response(1500.0, if (full) setOf(r.a1, r.a2) else setOf(r.a1))); f.run()
            val expected = if (full) emptySet() else setOf(r.a2)
            for (e in EXCHANGES) {
                assertEquals("$label $e only supplied bars release demand", expected, r.owed(e))
                assertEquals("$label $e old GENERAL 200 adopted", 1500.0 + EXCHANGES.indexOf(e),
                    f.state("tether").chart!!.prepared.bySeries.getValue(r.id(e)).linePoints.first { it.ts == r.a1 }.rate, 0.0)
            }
            // The old owner/focus/token is still valid; its GENERAL action must persist.
            val wasVisible = r.id(EXCHANGES.last()) in checkNotNull(f.state("tether").selection).visibleSeriesIds
            h.toggleSeries(oldToken, r.id(EXCHANGES.last())); f.run()
            assertEquals("$label old action toggles GENERAL", !wasVisible,
                r.id(EXCHANGES.last()) in checkNotNull(f.state("tether").selection).visibleSeriesIds)
            assertNotEquals("$label old action persisted", selections, f.selections.committed.toMap())
            assertHidden("T01", r)
            val sends = r.tabSends().size
            assertTrue("premise: before original retry deadline", f.elapsedMs < deadline)
            f.advance(deadline - 1 - f.elapsedMs)
            assertEquals("$label no early retry", sends, r.tabSends().size)
            f.advance(1)
            assertEquals("$label original budget issues remaining round only", sends + if (full) 0 else 1, r.tabSends().size)
            if (!full) {
                assertEquals("$label original deadline", deadline, r.tabSends().last().atMs)
                r.waiting().single().answer.complete(r.response(1600.0)); f.run()
                for (e in EXCHANGES) {
                    assertEquals("$label $e remaining demand released", emptySet<kotlinx.datetime.Instant>(), r.owed(e))
                    assertEquals("$label $e remaining A2 adopted", 1600.0 + EXCHANGES.indexOf(e),
                        f.state("tether").chart!!.prepared.bySeries.getValue(r.id(e)).linePoints.first { it.ts == r.a2 }.rate, 0.0)
                }
            }
            f.advance(10_000)
            assertEquals("$label no unnecessary retry after all demand filled", sends + if (full) 0 else 1, r.tabSends().size)
            assertHidden("T01", r)
        }
    }

    @Test fun T02_userHoldFreshLifetimeRejectsOldCompletionAndActionButKeepsDeadlineRetry() = runTest {
        holderTest { r ->
            r.ready("T02")
            val f = r.f
            val (deadline, old) = r.parkOld()
            f.advance(600)
            val token = f.token("tether")
            val oldUse = f.lifetime
            assertFalse("premise: old completion held", old.answer.isCompleted)
            r.userHoldAndFreshLifetime()
            assertNotEquals("contract(T02): real USER-axis lifetime change", oldUse, f.lifetime)
            assertEquals("contract(T02): same token is insufficient to revive old use", token.fence.grant, f.fence!!.grant)
            assertHidden("T02", r)
            val entry = f.coordinator.state.value.entries[r.oneDay]
            val chart = f.state("tether").chart
            val selections = f.selections.committed.toMap()
            assertFalse("premise: old response still held under fresh lifetime", old.answer.isCompleted)
            old.answer.complete(r.response(1500.0)); f.run()
            assertEquals("contract(T02): old completion does not replace entry", entry, f.coordinator.state.value.entries[r.oneDay])
            assertEquals("contract(T02): old completion does not replace chart", chart, f.state("tether").chart)
            f.holders.getValue("tether").toggleSeries(token, r.id(EXCHANGES.last())); f.run()
            assertEquals("contract(T02): old action writes zero", selections, f.selections.committed.toMap())
            for (e in EXCHANGES) assertEquals("contract(T02): $e old 200 releases zero", setOf(r.a1, r.a2), r.owed(e))
            val sends = r.tabSends().size
            assertTrue("premise: still before original deadline", f.elapsedMs < deadline)
            f.advance(deadline - 1 - f.elapsedMs)
            assertEquals("contract(T02): no early retry", sends, r.tabSends().size)
            f.advance(1)
            assertEquals("contract(T02): one retry under fresh use", sends + 1, r.tabSends().size)
            assertEquals("contract(T02): original deadline", deadline, r.tabSends().last().atMs)
            r.waiting().single().answer.complete(r.response(1600.0)); f.run()
            for (e in EXCHANGES) {
                assertEquals("contract(T02): $e fresh 200 releases demand", emptySet<kotlinx.datetime.Instant>(), r.owed(e))
                assertEquals("contract(T02): $e fresh A1 adopted", 1600.0 + EXCHANGES.indexOf(e),
                    f.state("tether").chart!!.prepared.bySeries.getValue(r.id(e)).linePoints.first { it.ts == r.a1 }.rate, 0.0)
            }
            assertHidden("T02", r)
        }
    }
}
