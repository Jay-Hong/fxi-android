package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.KrxBetaGraphData.KEY
import com.jay.fxi.data.graph.KrxBetaGraphData.ok
import com.jay.fxi.data.graph.KrxBetaGraphData.onlineDto
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** β-1d additions; the β-1b rig is preserved byte for byte. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaGraphRigExtras(val folder: TemporaryFolder) {
    var row = ""
    private val graphs = mutableListOf<KrxBetaGraphRig>()
    private val pacs = mutableListOf<KrxBetaGraphPac>()
    fun c(message: String) = "contract($row): $message"
    fun trial(id: String, body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        row = id
        var failure: Throwable? = null
        try {
            body()
            graphs.forEach { assertEquals(c("no graph event failures"), emptyList<Throwable>(), it.failures) }
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            graphs.forEach { it.close() }
            var endFailure = failure
            for (p in pacs) try { p.finish() } catch (t: Throwable) {
                if (endFailure == null) endFailure = t else endFailure.addSuppressed(t)
            }
            if (failure == null) endFailure?.let { throw it }
        }
    }
    suspend fun pac(test: TestScope): KrxBetaGraphPac = KrxBetaGraphPac(test, row).also { pacs += it; it.open() }
    fun graph(test: TestScope, p: KrxBetaGraphPac? = null, writes: GraphV2WritePorts? = null,
              input: TopicAccessSnapshot = a1Snap(userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1)) =
        KrxBetaGraphRig(test, folder.newFolder(), p?.snapshot ?: input, p?.fence ?: KrxHandSnapshots.FENCE7, row,
            realSnapshot = p?.let { { it.snapshot } }, writePorts = writes).also { graphs += it }
}

internal fun b1dEvidenceRoot() = File(if (File("app/src/test").isDirectory)
    ".beta_evidence/b1d/tmp" else "../.beta_evidence/b1d/tmp").apply { mkdirs() }

/** A genuine issuer USER hold: a mismatched stored owner, observed by topicGrantResult. */
internal suspend fun KrxBetaGraphPac.userHold(): AccessEpochRecord {
    val before = snapshot
    val owned = store.record
    store.record = owned.copy(ownerUid = "b1d-other-owner")
    assertNull("contract($row): real USER hold withholds grant", c.topicGrantResult().fence)
    assertFalse("contract($row): real USER hold closes GENERAL", snapshot.facts.userAllowed)
    assertTrue("contract($row): USER hold increases U", snapshot.userInvalidations > before.userInvalidations)
    assertEquals("contract($row): hold keeps confirmed USER epoch", before.facts.recordFence?.userAccessEpoch,
        snapshot.facts.recordFence?.userAccessEpoch)
    return owned
}

internal suspend fun KrxBetaGraphPac.releaseUser(owned: AccessEpochRecord) {
    store.record = owned
    val released = c.topicGrantResult()
    assertNotNull("contract($row): restored record releases USER hold", released.fence)
    assertTrue("contract($row): released USER allowed", snapshot.facts.userAllowed)
    fence = checkNotNull(released.fence)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.b1dFresh(g: KrxBetaGraphRig, rate: Double = 1700.0) {
    val count = g.sent.size
    g.coordinator.onRefreshRequested(force = true); runCurrent()
    assertEquals("contract(${g.row}): fresh request issued", count + 1, g.sent.size)
    val sent = g.sent.last()
    assertTrue("premise: ${g.row} fresh response outstanding", sent.key == KEY && !sent.tab.isCompleted)
    sent.tab.complete(ok(onlineDto(rate))); runCurrent()
    assertEquals("contract(${g.row}): fresh capture exposes KRX", listOf(KrxBetaGraphData.ONLINE, KrxBetaGraphData.KRX_SERIES), g.exposedIds())
    assertEquals("contract(${g.row}): fresh GENERAL adopted", rate, g.rate())
    assertEquals("contract(${g.row}): fresh KRX belongs to this response", rate + 5,
        g.exposed()!!.tab.graph.series.single { it.seriesId == KrxBetaGraphData.KRX_SERIES }.points.first().rate, 0.0)
}
