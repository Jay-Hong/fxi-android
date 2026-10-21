package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateSanity
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

private const val KRX_SOURCE = "krx"
private const val KRX_ASSET = "usd-krw-futures"
private const val KRX_SERIES_ID = "$KRX_SOURCE.$KRX_ASSET"
private val CHANGE_TIME_CEILING = 5.seconds

/** Either tab's one-day catalog may admit the same recorder series, once. */
internal fun krxGraphSeriesIds(topic: String, catalog: GraphCatalog): Set<String> {
    if (topic != TopicCatalogue.KRX_FUTURES) return emptySet()
    val admitted = listOf("tether", "usd").any { tab ->
        catalog.tabs[tab]?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries?.contains(KRX_SERIES_ID) == true
    }
    return if (admitted) setOf(KRX_SERIES_ID) else emptySet()
}

/** Anomalies identify only quotes whose change time reaches or exceeds the next five-second floor. */
internal class KrxGraphMapping(
    val observations: List<GraphObservation>,
    val anomalies: Set<GraphObservationSeriesKey>
)

/**
 * Keeps both original times in time order within each quote, and keeps candidate order across quotes.
 * A missing or equal change time yields one observation; an inconsistent change time leaves the timestamp and an anomaly.
 */
internal fun krxGraphMapping(
    scope: GraphDataScope,
    input: TopicGraphInput.Observations,
    catalog: GraphCatalog
): KrxGraphMapping {
    val seriesIds = krxGraphSeriesIds(input.topic, catalog)
    if (seriesIds.isEmpty()) return KrxGraphMapping(emptyList(), emptySet())
    val observations = mutableListOf<GraphObservation>()
    val anomalies = linkedSetOf<GraphObservationSeriesKey>()
    for (candidate in input.candidates) {
        val quote = candidate as? TopicGraphCandidate.Quote ?: continue
        if (quote.source != KRX_SOURCE || quote.asset != KRX_ASSET || !RateSanity.isPlausible(quote.rate)) continue
        val key = GraphObservationSeriesKey(scope, KRX_SERIES_ID)
        fun observe(at: Instant) {
            observations += GraphObservation(key, GraphObservationId(quote.source, quote.asset, at, quote.rate))
        }
        val changed = quote.rateChangedAt.takeIf { it != quote.timestamp }
        when {
            changed == null -> observe(quote.timestamp)
            changed >= quote.timestamp + CHANGE_TIME_CEILING -> {
                observe(quote.timestamp)
                anomalies += key
            }
            changed < quote.timestamp -> {
                observe(changed)
                observe(quote.timestamp)
            }
            else -> {
                observe(quote.timestamp)
                observe(changed)
            }
        }
    }
    return KrxGraphMapping(observations, anomalies)
}
