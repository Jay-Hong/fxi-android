package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateSanity

/** The same exact FX series set serves price mapping and the caller's continuity/loss routing. */
internal fun fxGraphSeriesIds(topic: String, catalog: GraphCatalog): Set<String> {
    if (topic !in TopicCatalogue.FX) return emptySet()
    val currency = topic.removePrefix("fx:").removeSuffix("-krw")
    val allSeries = catalog.tabs[currency]?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries ?: return emptySet()
    return allSeries.filterTo(linkedSetOf()) { id ->
        val source = id.substringBefore('.')
        Bank.fromCode(source) != null && id == "$source.$currency"
    }
}

/** Preserve candidate order and original timestamps; D1 and D3 decide duplicates and time admission. */
internal fun fxGraphObservations(
    scope: GraphDataScope,
    input: TopicGraphInput.Observations,
    catalog: GraphCatalog
): List<GraphObservation> {
    val seriesIds = fxGraphSeriesIds(input.topic, catalog)
    if (seriesIds.isEmpty()) return emptyList()
    val asset = input.topic.removePrefix("fx:")
    val currency = asset.removeSuffix("-krw")
    return input.candidates.mapNotNull { candidate ->
        val quote = candidate as? TopicGraphCandidate.Quote ?: return@mapNotNull null
        // Membership in seriesIds already implies a registered source, so it is not checked again here.
        val seriesId = "${quote.source}.$currency"
        if (quote.asset != asset || !RateSanity.isPlausible(quote.rate) || seriesId !in seriesIds) return@mapNotNull null
        GraphObservation(
            GraphObservationSeriesKey(scope, seriesId),
            GraphObservationId(quote.source, quote.asset, quote.timestamp, quote.rate)
        )
    }
}
