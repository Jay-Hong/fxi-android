package com.jay.fxi.domain.model

/**
 * Which sources each rate list accepts and their registry order. The free snapshot sanitizer and
 * premium topic display share these sets so the two tiers cannot drift apart.
 */
internal object RateSourceSets {
    val TAB_ASSETS = mapOf("usd" to "usd-krw", "jpy" to "jpy-krw", "eur" to "eur-krw", "tether" to "usdt-krw")
    val FX_GRAPH_SOURCES = setOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")

    /** Citi is valid in flat rate payloads, but is not in the free graph catalog. */
    val FX_RATE_SOURCES = FX_GRAPH_SOURCES + "citi"

    /** The premium FX list: no Citi, even on request — iOS a36682f `ExchangeRateViewModel.swift:617` drops it there. */
    val PREMIUM_FX_SOURCES = FX_GRAPH_SOURCES
    val EXCHANGES = setOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    val TETHER_BANKS = setOf("kb", "hana")
    const val USD_KRW_REFERENCE = "investing"
    /** The tether editor and display share this registry order, including the default-hidden sources. */
    val TETHER_SOURCES = listOf(USD_KRW_REFERENCE) + TETHER_BANKS + EXCHANGES
}
