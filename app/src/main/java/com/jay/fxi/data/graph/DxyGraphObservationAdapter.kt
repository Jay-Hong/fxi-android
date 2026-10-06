package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateSanity

internal val DXY_GRAPH_OBSERVATION_ORDER = GraphObservationOrder(listOf("investing", "cnbc", "yahoo"))

/** The same exact DXY series set serves price mapping and the caller's continuity/loss routing. */
internal fun dxyGraphSeriesIds(topic: String, catalog: GraphCatalog): Set<String> {
    if (topic != TopicCatalogue.DXY) return emptySet()
    val allSeries = catalog.tabs["usd"]?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries ?: return emptySet()
    return if ("dxy" in allSeries) setOf("dxy") else emptySet()
}

/** Preserve candidate order and original timestamps; D1 and D3 decide duplicates and time admission. */
internal fun dxyGraphObservations(
    scope: GraphDataScope,
    input: TopicGraphInput.Observations,
    catalog: GraphCatalog
): List<GraphObservation> {
    if (dxyGraphSeriesIds(input.topic, catalog).isEmpty()) return emptyList()
    return input.candidates.mapNotNull { candidate ->
        val index = candidate as? TopicGraphCandidate.DollarIndex ?: return@mapNotNull null
        if (index.source !in DXY_GRAPH_OBSERVATION_ORDER.sourcePriority || !RateSanity.isPlausible(index.rate)) {
            return@mapNotNull null
        }
        GraphObservation(
            GraphObservationSeriesKey(scope, "dxy"),
            GraphObservationId(index.source, "dxy", index.timestamp, index.rate)
        )
    }
}
