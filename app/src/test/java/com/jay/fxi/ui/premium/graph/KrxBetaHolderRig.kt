package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.a1Snap
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.premium.graph.TetherGraphData.B
import com.jay.fxi.ui.premium.graph.TetherGraphData.DAY0
import com.jay.fxi.ui.premium.graph.TetherGraphData.EXCHANGES
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder

/** β §8.3: S-1 inputs for five-exchange timing; the shared OFF fixture is unmodified. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaHolderRig(test: TestScope, folder: TemporaryFolder) {
    val f = TetherGraphFixture(test, folder, start = B + 1.minutes, recorderCollects = true)
    val inputs = mutableListOf<TopicAccessSnapshot>()
    init {
        Dispatchers.setMain(f.dispatcher)
        publish(input(100, "K1", user = 3, capability = 0, allowed = true))
    }
    val oneDay = GraphKey("tether", GraphPeriod.ONE_DAY)
    val a1 = B - 20.minutes
    val a2 = B - 10.minutes
    fun id(exchange: String) = "$exchange.usdt-krw"
    fun observedRate(exchange: String) = 1340.0 + EXCHANGES.indexOf(exchange)
    fun key(exchange: String) = GraphObservationSeriesKey(GraphDataScope("u1", "e1"), id(exchange))
    fun owed(exchange: String) = f.recorder.state.value.series.getValue(key(exchange)).pending.keys.filter { it < B }.toSet()
    fun tabSends() = f.sent.filter { it.key == oneDay }
    fun waiting() = tabSends().filter { !it.answer.isCompleted }

    private fun input(revision: Long, krx: String, user: Long, capability: Long, allowed: Boolean,
                      hold: Boolean = false): TopicAccessSnapshot = a1Snap(
        record = AccessFence("u1", "e1", krx), issuedRecord = AccessFence("u1", "e1", "K1"),
        userBlocks = if (hold) setOf(TopicAccessBlock.LOSS_CANDIDATE) else emptySet(),
        capabilityBlocks = if (allowed) emptySet() else setOf(TopicAccessBlock.NOT_GRANTED),
        projectKrx = true, userInvalidations = user, capabilityInvalidations = capability,
        krxPermitSeq = 1L, revision = revision
    ).copy(lastCapabilityEnd = if (krx == "K1") null else TopicAccessEnd(
        1L, TopicAccessEndReason.CAPABILITY_REVOKED, EntitlementsIdentity("u1", 1L), "u1", null))

    private fun publish(snapshot: TopicAccessSnapshot) { inputs += snapshot; f.snapshot = snapshot }
    fun notifyAccess() { f.accessRevisions.value += 1; f.run() }
    suspend fun rotate() {
        publish(input(101, "K2", user = 3, capability = 1, allowed = false))
        withContext(f.dispatcher) {
            assertEquals("contract(T01): retire K1 only", com.jay.fxi.data.graph.GraphRuntimeRetirement.REMOVED,
                f.port.retireCapabilities { it.krxCapabilityEpoch == "K1" })
        }
        notifyAccess()
    }
    /** A real USER-axis hold in the hand input, then a fresh U=4 lifetime. No USER end: the same-scope budget survives. */
    suspend fun userHoldAndFreshLifetime() {
        val old = f.lifetime
        publish(input(101, "K1", user = 4, capability = 0, allowed = true, hold = true))
        assertTrue("contract(T02): actual USER hold denies old lifetime", !f.uses.admits(old))
        publish(input(102, "K2", user = 4, capability = 1, allowed = false))
        withContext(f.dispatcher) { f.port.retireCapabilities { it.krxCapabilityEpoch == "K1" } }
        notifyAccess()
        f.show("tether")
        assertEquals("contract(T02): fresh lifetime U", 4L, f.lifetime.invalidations)
        assertTrue("contract(T02): old lifetime remains invalid", !f.uses.admits(old))
    }
    private fun point(at: Instant, rate: Double) = GraphV2Point(at, rate, "x", rate + 0.5, rate - 0.5)
    /** Non-exchange series keep the original response; selected closed exchange bars alone satisfy recovery. */
    fun response(base: Double? = null, closed: Set<Instant> = setOf(a1, a2)) = TetherGraphData.ok(GraphV2TabResponse(
        tab = "tether", period = "1d",
        series = TetherGraphData.TETHER_1D.map { sid ->
            val index = EXCHANGES.indexOf(sid.substringBefore(".usdt-krw"))
            TetherGraphData.series(sid, if (base != null && index >= 0) closed.sorted().map { point(it, base + index) }
                else TetherGraphData.dayPoints(sid))
        },
        metadata = GraphV2Metadata(B - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
            domainStartAt = DAY0, domainEndAt = DAY0 + 1440.minutes, liveDomainMode = "rolling")
    ))
    fun ready(row: String) {
        f.start(); f.collectPublications(); f.activate("tether")
        f.answer(oneDay, response()); f.show("tether"); f.events.onForeground(true)
        for (exchange in EXCHANGES) {
            f.exchange(exchange, observedRate(exchange), B + 30.seconds)
            f.lose(exchange, a1 + 30.seconds); f.lose(exchange, a2 + 30.seconds)
        }
        f.advance(400)
        val chart = checkNotNull(f.state("tether").chart) { "contract($row): initial ON chart" }
        assertTrue("contract($row): initial KRX toggle", f.state("tether").toggles.any { it.seriesId == TetherGraphData.KRX })
        assertTrue("contract($row): S-1 positive p and explicit U/C",
            f.snapshot.krxPermitSeq == 1L && f.snapshot.userInvalidations == 3L && f.snapshot.capabilityInvalidations == 0L)
        for (exchange in EXCHANGES) {
            assertEquals("premise: $exchange A1/A2 demand", setOf(a1, a2), owed(exchange))
            assertTrue("premise: $exchange observation", chart.prepared.bySeries.getValue(id(exchange)).linePoints.any {
                it.rate == observedRate(exchange)
            })
        }
    }
    /** Same failure/in-flight ordering as TetherEnd K: one retry budget and a second held old-use 200. */
    fun parkOld(): Pair<Long, TetherSent> {
        f.coordinator.onRefreshRequested(force = true); f.run()
        waiting().single().answer.complete(TetherGraphData.status(503)); f.run()
        val deadline = f.elapsedMs + 3_000
        f.coordinator.onRefreshRequested(force = true); f.run()
        return deadline to waiting().single()
    }
    fun close() { try { f.close() } finally { Dispatchers.resetMain() } }
}
