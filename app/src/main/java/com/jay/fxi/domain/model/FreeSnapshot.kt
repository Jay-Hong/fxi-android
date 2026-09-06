package com.jay.fxi.domain.model

import kotlinx.datetime.Instant

/**
 * One hourly free snapshot, already sanitised.
 *
 * The free tier is a *fixed* hourly view, not a live one: the server serves only what its cron
 * built at the `HH:30` basis and never rebuilds from the database on read, so two clients asking
 * inside the same hour see the same numbers. Nothing here is a live tick.
 *
 * The fetching boundary returns this sanitized domain value; wire DTOs stay in the data layer.
 */
data class FreeSnapshot(
    val tab: String,
    val period: GraphPeriod,
    /** Hourly HH:30 basis marker the server pinned this view to. Cadence, not data recency. */
    val asOf: Instant,
    /** When the server finished assembling it. */
    val generatedAt: Instant,
    /**
     * Serve-time hint for the earliest sensible re-request (`next :31` plus the server's margin).
     *
     * Not a promise that new data exists then, and absent on older servers — the scheduler owns
     * what to do without it.
     */
    val refreshNotBefore: Instant?,
    val rate: FreeRate,
    val graph: FreeGraph
)

/**
 * The rate block, in one of two shapes.
 *
 * FX tabs send a flat list of bank rates; the tether tab sends three named groups. The server
 * discriminates on a `kind` field, but **nothing downstream may trust that field to decide what
 * to inspect** — see `FreeSnapshotSanitizer`.
 */
sealed interface FreeRate {
    /** usd / jpy / eur — `{asset, entries}`. */
    data class Flat(val asset: String, val entries: List<ExchangeRate>) : FreeRate

    /** tether — exchange rates plus the USD/KRW context shown beside them. */
    data class Grouped(
        val primaryAsset: String,
        val usdtKrw: List<SourceRate>,
        val usdKrwBanks: List<ExchangeRate>,
        /** Investing's USD/KRW, when present. Absent is normal, not an error. */
        val usdKrwReference: ExchangeRate?
    ) : FreeRate
}

/** One exchange's quote for an asset. The tether tab's unit, as distinct from a bank's. */
data class SourceRate(
    val source: String,
    val asset: String,
    val rate: Double,
    val timestamp: Instant
)

/** The graph block: whole series only — a series with any bad point is dropped, not repaired. */
data class FreeGraph(
    val bucketSize: String?,
    val series: List<FreeGraphSeries>,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val domainStartAt: Instant? = null,
    val domainEndAt: Instant? = null,
    val liveDomainMode: String? = null
)

data class FreeGraphSeries(
    val seriesId: String,
    val points: List<FreeGraphPoint>,
    val label: String = seriesId,
    val axisGroup: String? = null,
    val unit: String? = null,
    val decimals: Int? = null,
    val insufficientHistory: Boolean = false,
    val perPointMetadata: List<String> = emptyList(),
    val carryIn: FreeGraphCarryIn? = null
)

data class FreeGraphPoint(
    val timestamp: Instant,
    val rate: Double,
    val high: Double?,
    val low: Double?,
    val source: String? = null,
    val closeBasis: String? = null,
    val sourceMethod: String? = null
)

/** Last observation before the visible points; never a synthetic current quote. */
data class FreeGraphCarryIn(val rate: Double, val observedAt: Instant)
