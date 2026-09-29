package com.jay.fxi.data.remote.dto

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Graph V2 wire shapes (premium GET /api/v2/graph/catalog and /api/v2/graph/tab, read through the protected
 * transport; server app/graph_v2.py, graph_v2_intraday.py; iOS a36682f GraphV2Models.swift). A field has a default
 * only when the server omits it for some responses: point high/low (daily rows without OHLC) and per_point_metadata
 * fields, the tab's in_progress (1d only), a series' carry_in (not on 1d) and the metadata domain fields (attached at
 * serve time today; absent from an older server's response). Every other field is always sent and has no default.
 */

/** Static catalog: tabs, periods and series, valid for cache_ttl_seconds. */
@Serializable
data class GraphV2CatalogResponse(
    val tabs: List<GraphV2CatalogTab>,
    /** A date string such as "2026-05-27". */
    val version: String,
    @SerialName("supported_periods") val supportedPeriods: List<String>,
    @SerialName("cache_ttl_seconds") val cacheTtlSeconds: Int
)

@Serializable
data class GraphV2CatalogTab(
    val id: String,
    val label: String,
    @SerialName("axis_groups") val axisGroups: Map<String, GraphV2AxisGroup>,
    val periods: Map<String, GraphV2CatalogPeriod>
)

@Serializable
data class GraphV2AxisGroup(val unit: String, val decimals: Int, val side: String)

@Serializable
data class GraphV2CatalogPeriod(
    @SerialName("all_series") val allSeries: List<String>,
    @SerialName("default_visible_series") val defaultVisibleSeries: List<String>
)

/** Every series of one tab and period. */
@Serializable
data class GraphV2TabResponse(
    val tab: String,
    val period: String,
    val series: List<GraphV2Series>,
    val metadata: GraphV2Metadata,
    /** The current 10-minute bucket per series id (1d only). */
    @SerialName("in_progress") val inProgress: Map<String, GraphV2InProgressSeed>? = null
)

@Serializable
data class GraphV2InProgressSeed(
    @SerialName("bucket_start") @Serializable(with = InstantSerializer::class) val bucketStart: Instant,
    val high: Double,
    val low: Double,
    val close: Double,
    @SerialName("sampled_at") @Serializable(with = InstantSerializer::class) val sampledAt: Instant
)

/** The last real observation before the window (ADR-039 §5.2). */
@Serializable
data class GraphV2CarryIn(
    val rate: Double,
    @SerialName("observed_at") @Serializable(with = InstantSerializer::class) val observedAt: Instant
)

@Serializable
data class GraphV2Series(
    val id: String,
    val label: String,
    @SerialName("axis_group") val axisGroup: String,
    val unit: String,
    val decimals: Int,
    val data: List<GraphV2Point>,
    val provenance: GraphV2Provenance,
    @SerialName("carry_in") val carryIn: GraphV2CarryIn? = null
)

/** One bucket: rate is the close; high/low draw the band when present. */
@Serializable
data class GraphV2Point(
    @Serializable(with = InstantSerializer::class) val ts: Instant,
    val rate: Double,
    val source: String,
    val high: Double? = null,
    val low: Double? = null,
    @SerialName("close_basis") val closeBasis: String? = null,
    @SerialName("source_method") val sourceMethod: String? = null,
    @SerialName("contract_code") val contractCode: String? = null
)

@Serializable
data class GraphV2Provenance(
    @SerialName("insufficient_history") val insufficientHistory: Boolean,
    @SerialName("per_point_metadata") val perPointMetadata: List<String>
)

@Serializable
data class GraphV2Metadata(
    @SerialName("fetched_at") @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
    /** "10min" / "1h" / "1d". */
    @SerialName("bucket_size") val bucketSize: String,
    val range: GraphV2Range,
    @SerialName("domain_start_at") @Serializable(with = InstantSerializer::class) val domainStartAt: Instant? = null,
    @SerialName("domain_end_at") @Serializable(with = InstantSerializer::class) val domainEndAt: Instant? = null,
    /** "rolling" / "fixed_start". */
    @SerialName("live_domain_mode") val liveDomainMode: String? = null
)

/** Date-only bounds ("2026-03-28"), kept as text. */
@Serializable
data class GraphV2Range(val start: String, val end: String)
