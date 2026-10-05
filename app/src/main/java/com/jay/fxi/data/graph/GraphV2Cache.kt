package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.GraphCatalog
import kotlinx.coroutines.CancellationException

internal data class GraphV2CachePorts(
    val store: GraphV2DiskStore,
    val gate: GraphV2AccessGate,
    /** Non-blocking diagnostic observer; failures are isolated from request ownership. */
    val onSeedDiagnostic: (GraphV2SeedDiagnostic) -> Unit
)

internal data class GraphV2SeedDiagnostic(
    val seedId: Long,
    val key: GraphKey,
    val component: GraphV2DiskComponent,
    val reason: String,
    val cause: Throwable? = null
)

internal data class GraphV2DiskSeedResult(
    val general: GraphV2DiskRead<GraphV2GeneralEnvelope>,
    /** Null means not attempted, distinct from a missing KRX file. */
    val krx: GraphV2DiskRead<GraphV2KrxEnvelope>?
)

internal fun GraphV2AccessCapture.generalKey(key: GraphKey): GraphV2GeneralKey? =
    fence.userAccessEpoch?.let { GraphV2GeneralKey(fence.identity.uid, it, key.tab, key.period.code) }

/** Read the general half first; a failed or withdrawn KRX half never discards it. */
internal suspend fun readGraphV2DiskSeed(
    ports: GraphV2CachePorts,
    key: GraphKey,
    captured: GraphV2AccessCapture,
    catalog: GraphCatalog?
): GraphV2DiskSeedResult {
    val generalKey = captured.generalKey(key)
        ?: return GraphV2DiskSeedResult(GraphV2DiskRead.Rejected("Missing USER access epoch"), null)
    val general = diskSeedRead {
        if (!ports.gate.admits(captured, GraphV2DiskComponent.GENERAL)) {
            GraphV2DiskRead.Rejected("GENERAL seed admission is closed")
        } else {
            ports.store.readGeneral(generalKey, catalog, ports.gate.ioAdmission(captured))
        }
    }
    if (general !is GraphV2DiskRead.Found) return GraphV2DiskSeedResult(general, null)

    val krx = try {
        val epoch = captured.krxCapabilityEpoch
        if (epoch == null || !ports.gate.admits(captured, GraphV2DiskComponent.KRX)) null else {
            val krxKey = GraphV2KrxKey(generalKey.uid, generalKey.userAccessEpoch, epoch, key.tab, key.period.code)
            ports.store.readKrx(krxKey, catalog, ports.gate.ioAdmission(captured))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        GraphV2DiskRead.Failed(failure)
    }
    return GraphV2DiskSeedResult(general, krx)
}

/** Store adapters can throw too; only cancellation crosses the cache boundary. */
private suspend fun <T> diskSeedRead(read: suspend () -> GraphV2DiskRead<T>): GraphV2DiskRead<T> = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    GraphV2DiskRead.Failed(failure)
}
