package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlinx.datetime.Instant

/** Catalog values independent of the wire DTOs. */
data class GraphAxisGroup(val unit: String, val decimals: Int, val side: String)

data class GraphCatalog(val ttl: Duration, val tabs: Map<String, GraphCatalogTab>)

data class GraphCatalogTab(
    val id: String,
    val label: String,
    val axisGroups: Map<String, GraphAxisGroup>,
    val periods: Map<GraphPeriod, GraphCatalogPeriod>
)

data class GraphCatalogPeriod(val allSeries: List<String>, val defaultVisible: List<String>)

/** Validated server seed; current-bucket admission and folding belong to the live reducer. */
data class GraphV2InProgress(
    val bucketStart: Instant,
    val high: Double,
    val low: Double,
    val close: Double,
    val sampledAt: Instant
)

data class GraphV2Tab(
    val tab: String,
    val period: GraphPeriod,
    val bucketSize: String,
    val fetchedAt: Instant,
    val graph: FreeGraph,
    val inProgress: Map<String, GraphV2InProgress>
)

sealed interface GraphPeriodSupport {
    data object Supported : GraphPeriodSupport
    data object Unsupported : GraphPeriodSupport
}

sealed interface GraphTabAdmission {
    data class Accepted(val tab: GraphV2Tab) : GraphTabAdmission
    data class Rejected(val reason: String) : GraphTabAdmission
}
