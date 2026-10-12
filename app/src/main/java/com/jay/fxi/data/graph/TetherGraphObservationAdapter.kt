package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateSanity
import com.jay.fxi.domain.model.RateSourceSets
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

private const val TETHER_TAB = "tether"
private const val EXCHANGE_ASSET = "usdt-krw"
private const val REFERENCE_ASSET = "usd-krw"

/** The USD rows the tether topic carries (`usd_krw_banks` and `usd_krw_reference`). */
private val REFERENCE_SOURCES = RateSourceSets.TETHER_BANKS + RateSourceSets.USD_KRW_REFERENCE

/** `timestamp` is the five-second floor of the writer's tick, so a consistent change time stays below the next floor. */
private val CHANGE_TIME_CEILING = 5.seconds

/**
 * The tether topic's mapped series: the five exchanges' `<exchange>.usdt-krw` and the kb, hana and investing USD rows'
 * `<source>.usd` that the tether tab's 1d allSeries lists. The same set serves price mapping and continuity/loss routing.
 */
internal fun tetherGraphSeriesIds(topic: String, catalog: GraphCatalog): Set<String> {
    if (topic != TopicCatalogue.TETHER) return emptySet()
    val allSeries = catalog.tabs[TETHER_TAB]?.periods?.get(GraphPeriod.ONE_DAY)?.allSeries ?: return emptySet()
    return allSeries.filterTo(linkedSetOf()) { id ->
        val source = id.substringBefore('.')
        (source in RateSourceSets.EXCHANGES && id == "$source.$EXCHANGE_ASSET") ||
            (source in REFERENCE_SOURCES && id == "$source.usd")
    }
}

/** [anomalies] are the series whose exchange quote carried a change time inconsistent with its `timestamp`. */
internal class TetherGraphMapping(
    val observations: List<GraphObservation>,
    val anomalies: Set<GraphObservationSeriesKey>
)

/**
 * An exchange quote yields its `timestamp` observation and, when present and different, its `rateChangedAt` observation, the
 * pair in time order; a change time at or past `timestamp` + 5 s is dropped and reported instead. A reference row yields its
 * `timestamp` alone. Candidate order and original times are kept; D1 and D3 decide duplicates and time admission.
 */
internal fun tetherGraphMapping(
    scope: GraphDataScope,
    input: TopicGraphInput.Observations,
    catalog: GraphCatalog
): TetherGraphMapping {
    val seriesIds = tetherGraphSeriesIds(input.topic, catalog)
    if (seriesIds.isEmpty()) return TetherGraphMapping(emptyList(), emptySet())
    val observations = mutableListOf<GraphObservation>()
    val anomalies = linkedSetOf<GraphObservationSeriesKey>()
    for (candidate in input.candidates) {
        val quote = candidate as? TopicGraphCandidate.Quote ?: continue
        val seriesId = when (quote.asset) {
            EXCHANGE_ASSET -> "${quote.source}.$EXCHANGE_ASSET"
            REFERENCE_ASSET -> "${quote.source}.usd"
            else -> continue
        }
        // Membership in seriesIds already implies a source of the asset's kind.
        if (seriesId !in seriesIds || !RateSanity.isPlausible(quote.rate)) continue
        val key = GraphObservationSeriesKey(scope, seriesId)
        fun observe(at: Instant) {
            observations += GraphObservation(key, GraphObservationId(quote.source, quote.asset, at, quote.rate))
        }
        val changed = quote.rateChangedAt.takeIf { quote.asset == EXCHANGE_ASSET && it != quote.timestamp }
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
    return TetherGraphMapping(observations, anomalies)
}
