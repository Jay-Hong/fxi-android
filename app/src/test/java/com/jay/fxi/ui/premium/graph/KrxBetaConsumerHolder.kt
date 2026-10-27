package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Additional holder assembly for β-1d; no changes to the existing holder rigs. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaConsumerHolder(test: TestScope, folder: TemporaryFolder, p: KrxBetaGraphPac?) {
    val f = TetherGraphFixture(test, folder)
    private val h get() = f.holders.getValue("usd")
    private val day = GraphKey("usd", GraphPeriod.ONE_DAY)
    private val quarter = GraphKey("usd", GraphPeriod.THREE_MONTHS)
    private val row = p?.row ?: "G12"
    private fun c(message: String) = "contract($row): $message"
    private val owner get() = checkNotNull(f.display.value.owner)
    private fun state() = f.state("usd")
    private fun token() = f.token("usd")
    private val krx = TetherGraphData.KRX
    init {
        Dispatchers.setMain(f.dispatcher)
        val identity = p?.fence?.identity ?: KrxHandSnapshots.IDENTITY
        f.identity = identity
        f.fence = p?.fence ?: KrxHandSnapshots.FENCE7
        f.snapshot = p?.snapshot ?: a1Snap(userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1)
        p?.pac?.check?.observe { f.snapshot = it }
        f.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(identity, 1))
        f.focus.value = OwnedTopicFocus(identity, FreeTab.USD)
        f.catalogDto = TetherGraphData.catalog().let { dto -> dto.copy(tabs = dto.tabs.map { tab ->
            if (tab.id != "usd") tab else tab.copy(periods = tab.periods.mapValues { (_, period) ->
                period.copy(allSeries = (period.allSeries + krx).distinct(), defaultVisibleSeries = (period.defaultVisibleSeries + krx).distinct())
            })
        }) }
    }
    private fun ready() {
        f.start(); h.onActivated(owner); f.run()
        f.answer(day, TetherGraphData.day("usd", TetherGraphData.USD_1D + krx))
        assertEquals(c("initial ON READY"), GraphV2Content.READY, state().content)
        assertTrue(c("initial KRX drawn"), krx in checkNotNull(state().chart).renderedIds)
        assertEquals("premise: holder catalog distinguishes fallback periods", listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), state().periods)
    }
    private fun hidden(s: GraphV2ScreenState) {
        assertEquals(c("GENERAL READY"), GraphV2Content.READY, s.content)
        assertNotNull(c("GENERAL chart retained"), s.chart)
        assertFalse(c("KRX rendering zero"), krx in checkNotNull(s.chart).renderedIds)
        assertFalse(c("KRX toggle zero"), s.toggles.any { it.seriesId == krx })
    }
    private suspend fun retire(epoch: String) = withContext(f.dispatcher) {
        f.port.retireCapabilities { it.krxCapabilityEpoch == epoch }
        h.state.value
    }
    suspend fun rotation(p: KrxBetaGraphPac) {
        ready()
        h.selectPeriod(token(), GraphPeriod.THREE_MONTHS); f.run()
        f.answer(quarter, TetherGraphData.long("usd", GraphPeriod.THREE_MONTHS, TetherGraphData.LONG_USD))
        h.enterFullscreen(token()); f.run()
        val before = state(); val source = f.coordinator.state.value.source
        val catalog = f.coordinator.state.value.catalog
        val selections = f.selections.committed.toMap()
        val k1 = checkNotNull(p.store.record.krxCapabilityEpoch)
        val old = p.snapshot; p.rotate(); p.sameGeneral(old)
        val kept = retire(k1)
        hidden(kept)
        assertEquals(c("period retained"), before.activePeriod, kept.activePeriod)
        assertTrue(c("fullscreen retained"), kept.fullscreenOpen)
        assertEquals(c("owner retained"), before.inlineToken?.binding, kept.inlineToken?.binding)
        assertEquals(c("catalog periods retained"), before.periods, kept.periods)
        assertEquals(c("source retained"), source, f.coordinator.state.value.source)
        assertEquals(c("catalog retained"), catalog, f.coordinator.state.value.catalog)
        assertEquals(c("GENERAL prepared series retained"), before.chart!!.prepared.bySeries.filterKeys { it != krx }, kept.chart!!.prepared.bySeries)
        assertEquals(c("raw selection preserved"), selections, f.selections.committed.toMap())
        p.reopen(); f.accessRevisions.value++; f.coordinator.onContextChanged(); f.run()
        hidden(state())
        assertEquals(c("catalog retained after configuration notification"), catalog, f.coordinator.state.value.catalog)
        assertEquals(c("catalog periods retained after notification"), before.periods, state().periods)
        assertEquals(c("reapproval alone cannot resurrect old KRX"), selections, f.selections.committed.toMap())
        f.coordinator.onRefreshRequested(force = true); f.run()
        val fresh = f.sent.last { it.key == quarter && !it.answer.isCompleted }
        val response = TetherGraphData.long("usd", GraphPeriod.THREE_MONTHS, TetherGraphData.LONG_USD)
        fresh.answer.complete(TetherGraphData.ok(response.body!!.let { body -> body.copy(series = body.series.map { series ->
            series.copy(data = series.data.map { it.copy(rate = it.rate + 100, high = it.high?.plus(100), low = it.low?.plus(100)) })
        }) })); f.run()
        assertTrue(c("fresh K2 response restores KRX"), krx in checkNotNull(state().chart).renderedIds)
        assertEquals(c("restored KRX has unique fresh-response values"),
            before.chart!!.prepared.bySeries.getValue(krx).linePoints.first().rate + 100,
            state().chart!!.prepared.bySeries.getValue(krx).linePoints.first().rate, 0.0)
    }
    suspend fun blocked(p: KrxBetaGraphPac) {
        ready(); val k1 = checkNotNull(p.store.record.krxCapabilityEpoch)
        p.rotate(); p.userHold()
        val blocked = retire(k1)
        assertEquals(c("USER hold BLOCKED at port return"), GraphV2Content.BLOCKED, blocked.content)
        assertNull(c("USER hold chart null at port return"), blocked.chart)
    }
    suspend fun freshUserWindow(p: KrxBetaGraphPac) {
        ready(); f.coordinator.onRefreshRequested(force = true); f.run(); f.fail(day, 500)
        assertNotNull("premise: G08 old failure visible", state().requestFailure)
        val source = f.coordinator.state.value.source
        val catalog = f.coordinator.state.value.catalog
        val oldUse = token().lifetime
        val k1 = checkNotNull(p.store.record.krxCapabilityEpoch)
        p.rotate()
        val owned = p.userHold()
        assertNull(c("lazy retirement on real USER hold"), state().chart)
        p.releaseUser(owned); f.fence = p.fence
        h.onActivated(owner)
        assertNotEquals(c("new U lifetime"), oldUse, token().lifetime)
        assertEquals(c("coordinator publishes retirement copy before new-use sync"), GraphRuntimeRetirement.REMOVED,
            f.coordinator.retireCapabilities { it.krxCapabilityEpoch == k1 })
        assertNotNull("premise: G08 copy still carries old failure", f.coordinator.state.value.failures[day])
        assertEquals(c("coordinator not notified yet"), source, f.coordinator.state.value.source)
        assertEquals(c("old catalog physically still present"), catalog, f.coordinator.state.value.catalog)
        val window = state()
        assertEquals(c("fresh lifetime ignores old catalog"), GraphPeriod.entries.toList(), window.periods)
        assertNull(c("fresh lifetime ignores old failure"), window.requestFailure)
        h.selectPeriod(token(), GraphPeriod.ONE_WEEK)
        assertEquals(c("fallback period outside old catalog selectable"), GraphPeriod.ONE_WEEK, state().activePeriod)
        f.run()
        assertNotEquals(c("coordinator synchronized new source"), source, f.coordinator.state.value.source)
        assertEquals(c("new catalog read after synchronization"), listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), state().periods)
    }
    fun changedFence() {
        ready(); val source = checkNotNull(f.coordinator.state.value.source)
        h.onDeactivated(); f.run()
        val identity = AuthIdentityFence("u1", 2)
        val fence = TopicSessionFence(identity, "U1", KrxHandSnapshots.FENCE7.grant)
        f.identity = identity; f.fence = fence
        f.snapshot = a1Snap(binding = EntitlementsIdentity("u1", 2), userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1)
        f.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(identity, 1))
        f.focus.value = OwnedTopicFocus(identity, FreeTab.USD); f.run()
        assertEquals("premise: G12 coordinator not notified", source, f.coordinator.state.value.source)
        h.onActivated(owner)
        assertEquals(c("same lifetime"), source.lifetime, token().lifetime)
        assertEquals(c("different fence acquired"), fence, token().fence)
        assertEquals(c("old catalog not read under another fence"), GraphPeriod.entries.toList(), state().periods)
        f.run()
        assertEquals(c("new catalog read after notice"), listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), state().periods)
    }
    fun close() { try { f.close() } finally { Dispatchers.resetMain() } }
}
