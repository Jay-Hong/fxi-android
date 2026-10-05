package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphAxisGroup
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphPeriodSupport
import com.jay.fxi.domain.model.GraphTabAdmission
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.domain.model.RateSanity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Graph V2 wire-to-domain boundary; access, caching and rendering are handled downstream. */
object GraphV2Domain {
    fun catalog(dto: GraphV2CatalogResponse): GraphCatalog = GraphCatalog(
        ttl = dto.cacheTtlSeconds.seconds,
        tabs = dto.tabs.associate { tab ->
            val periods = tab.periods.mapNotNull { (code, period) ->
                GraphPeriod.fromCode(code)?.let {
                    it to GraphCatalogPeriod(
                        allSeries = period.allSeries,
                        defaultVisible = period.defaultVisibleSeries.filter { id -> id in period.allSeries }
                    )
                }
            }.toMap()
            tab.id to GraphCatalogTab(
                id = tab.id,
                label = tab.label,
                axisGroups = tab.axisGroups.mapValues { (_, axis) ->
                    GraphAxisGroup(axis.unit, axis.decimals, axis.side)
                },
                periods = periods
            )
        }
    )

    fun ttl(catalog: GraphCatalog?): Duration = catalog?.ttl ?: 3600.seconds

    fun periods(catalog: GraphCatalog?, tab: String): List<GraphPeriod> =
        GraphPeriod.entries.filter { support(catalog, tab, it) == GraphPeriodSupport.Supported }

    fun support(catalog: GraphCatalog?, tab: String, period: GraphPeriod): GraphPeriodSupport =
        if (catalog == null || catalog.tabs[tab]?.periods?.containsKey(period) == true) {
            GraphPeriodSupport.Supported
        } else {
            GraphPeriodSupport.Unsupported
        }

    fun admit(
        dto: GraphV2TabResponse,
        requestTab: String,
        requestPeriod: GraphPeriod,
        catalog: GraphCatalog?
    ): GraphTabAdmission {
        if (dto.tab != requestTab || dto.period != requestPeriod.code) {
            return GraphTabAdmission.Rejected("Graph response tab or period does not match the request")
        }

        // An absent catalog permits dynamic ids; a present catalog with no registry permits none.
        val allowedIds = if (catalog == null) null else {
            catalog.tabs[requestTab]?.periods?.get(requestPeriod)?.allSeries.orEmpty().toSet()
        }
        val seen = mutableSetOf<String>()
        val series = dto.series.mapNotNull { raw ->
            if (!seen.add(raw.id) || (allowedIds != null && raw.id !in allowedIds) || !validSeries(raw)) {
                null
            } else {
                toDomain(raw)
            }
        }
        val acceptedIds = series.map { it.seriesId }.toSet()
        val inProgress = dto.inProgress.orEmpty()
            .filter { (id, seed) -> id in acceptedIds && validSeed(seed) }
            .mapValues { (_, seed) ->
                GraphV2InProgress(seed.bucketStart, seed.high, seed.low, seed.close, seed.sampledAt)
            }
        val metadata = dto.metadata
        return GraphTabAdmission.Accepted(
            GraphV2Tab(
                tab = dto.tab,
                period = requestPeriod,
                bucketSize = metadata.bucketSize,
                fetchedAt = metadata.fetchedAt,
                graph = FreeGraph(
                    bucketSize = metadata.bucketSize,
                    series = series,
                    rangeStart = metadata.range.start,
                    rangeEnd = metadata.range.end,
                    // Preserve even an invalid domain; the shared builder alone decides validity.
                    domainStartAt = metadata.domainStartAt,
                    domainEndAt = metadata.domainEndAt,
                    liveDomainMode = metadata.liveDomainMode
                ),
                inProgress = inProgress
            )
        )
    }

    private fun validSeries(series: GraphV2Series): Boolean {
        if (series.decimals !in 0..8) return false
        if (series.data.any { point ->
                !RateSanity.isPlausible(point.rate) ||
                    (point.high != null && (!RateSanity.isPlausible(point.high) || point.high < point.rate)) ||
                    (point.low != null && (!RateSanity.isPlausible(point.low) || point.low > point.rate))
            }) return false
        series.carryIn?.let { carry ->
            if (!RateSanity.isPlausible(carry.rate)) return false
            val earliest = series.data.minOfOrNull { it.ts }
            if (earliest != null && carry.observedAt >= earliest) return false
        }
        return true
    }

    private fun validSeed(seed: GraphV2InProgressSeed): Boolean =
        RateSanity.isPlausible(seed.high) && RateSanity.isPlausible(seed.low) &&
            RateSanity.isPlausible(seed.close) && seed.low <= seed.close && seed.close <= seed.high

    private fun toDomain(series: GraphV2Series): FreeGraphSeries = FreeGraphSeries(
        seriesId = series.id,
        points = series.data.map { point ->
            FreeGraphPoint(
                timestamp = point.ts,
                rate = point.rate,
                high = point.high,
                low = point.low,
                source = point.source,
                closeBasis = point.closeBasis,
                sourceMethod = point.sourceMethod,
                contractCode = point.contractCode
            )
        },
        label = series.label,
        axisGroup = series.axisGroup,
        unit = series.unit,
        decimals = series.decimals,
        insufficientHistory = series.provenance.insufficientHistory,
        perPointMetadata = series.provenance.perPointMetadata,
        carryIn = series.carryIn?.let { FreeGraphCarryIn(it.rate, it.observedAt) }
    )
}
