package com.jay.fxi.s5fixture

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.domain.model.GraphPeriod

/** What the S5 fixture server answers to one request. */
internal sealed interface S5Route {
    /** A fixed body from [payload]: an asset file name, [S5FixtureRoutes.ENTITLEMENTS] or [S5FixtureRoutes.TETHER_SNAPSHOT]. */
    data class Ok(val payload: String) : S5Route

    /** A request the journey is known to send that the fixture answers with a fixed 404. */
    data object Expected404 : S5Route

    data object WebSocket : S5Route

    /** Anything else: answered 404 and counted, so a run that sent it is not a valid run. */
    data object Unexpected : S5Route
}

/**
 * S5-4 slice 4b: the fixture's request table, exact on method, path and query. The table is the journey's known start-up and
 * tether requests; S5-4 4d's run observation confirms it, and anything outside it is unexpected.
 */
internal object S5FixtureRoutes {
    const val ENTITLEMENTS = "entitlements"
    const val ENTITLEMENTS_BODY = """{"krx_visible":true,"premium_active":true}"""

    /** The REST payload name of the tether topic snapshot; its body is [TOPIC_SNAPSHOTS]' tether entry. */
    const val TETHER_SNAPSHOT = "topic-snapshot-tether"

    /**
     * S5-4 slice 4d: one snapshot per subscribed topic, each the same JSON as `contracts/v2/topic/snapshot-*.json` (the tether one
     * is also `contracts/v2/http/topic-snapshot-tether-200.json`). The tether entry answers the REST bootstrap, so the tether rate
     * rows are drawn and the page can scroll; after a subscribe acknowledgement the socket sends each accepted topic's entry once,
     * so every subscription's first delivery arrives and no topic is later shown as delayed. Never resent: no live frame.
     */
    val TOPIC_SNAPSHOTS: Map<String, String> = mapOf(
        TopicCatalogue.TETHER to """{"data":{"usd_krw_banks":[{"asset":"usd-krw","rate":1388.3,"source":"kb","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.4,"source":"hana","timestamp":"2026-08-31T10:20:00+09:00"}],"usd_krw_reference":{"asset":"usd-krw","rate":1388.1,"source":"investing","timestamp":"2026-08-31T10:20:00+09:00"},"usdt_krw":[{"asset":"usdt-krw","rate":1392.0,"rate_changed_at":"2026-08-31T10:20:00+09:00","source":"upbit","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usdt-krw","rate":1392.1,"rate_changed_at":"2026-08-31T10:20:00.001000+09:00","source":"bithumb","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usdt-krw","rate":1392.2,"rate_changed_at":"2026-08-31T10:20:00.002000+09:00","source":"coinone","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usdt-krw","rate":1392.3,"rate_changed_at":"2026-08-31T10:20:00.003000+09:00","source":"korbit","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usdt-krw","rate":1392.4,"rate_changed_at":"2026-08-31T10:20:00.004000+09:00","source":"gopax","timestamp":"2026-08-31T10:20:00+09:00"}]},"topic":"usdt:krw","type":"snapshot","version":1}""",
        TopicCatalogue.DXY to """{"data":{"dxy":{"rate":103.4,"source":"investing","timestamp":"2026-08-31T10:20:00+09:00"}},"topic":"dxy:spot","type":"snapshot","version":1}""",
        "fx:usd-krw" to """{"data":{"banks":[{"asset":"usd-krw","rate":1388.1,"source":"kb","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.1999999999998,"source":"hana","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.3,"source":"shinhan","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.3999999999999,"source":"woori","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.5,"source":"ibk","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.6,"source":"nh","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.6999999999998,"source":"sc","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"usd-krw","rate":1388.8,"source":"bs","timestamp":"2026-08-31T10:20:00+09:00"}],"reference":{"asset":"usd-krw","rate":1388.1,"source":"investing","timestamp":"2026-08-31T10:20:00+09:00"}},"topic":"fx:usd-krw","type":"snapshot","version":1}""",
        "fx:jpy-krw" to """{"data":{"banks":[{"asset":"jpy-krw","rate":9.45,"source":"kb","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":9.549999999999999,"source":"hana","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":9.649999999999999,"source":"shinhan","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":9.75,"source":"woori","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":9.85,"source":"ibk","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":9.95,"source":"nh","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":10.049999999999999,"source":"sc","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"jpy-krw","rate":10.149999999999999,"source":"bs","timestamp":"2026-08-31T10:20:00+09:00"}],"reference":{"asset":"jpy-krw","rate":9.45,"source":"investing","timestamp":"2026-08-31T10:20:00+09:00"}},"topic":"fx:jpy-krw","type":"snapshot","version":1}""",
        "fx:eur-krw" to """{"data":{"banks":[{"asset":"eur-krw","rate":10.31,"source":"kb","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.41,"source":"hana","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.51,"source":"shinhan","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.610000000000001,"source":"woori","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.71,"source":"ibk","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.81,"source":"nh","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":10.91,"source":"sc","timestamp":"2026-08-31T10:20:00+09:00"},{"asset":"eur-krw","rate":11.01,"source":"bs","timestamp":"2026-08-31T10:20:00+09:00"}],"reference":{"asset":"eur-krw","rate":10.31,"source":"investing","timestamp":"2026-08-31T10:20:00+09:00"}},"topic":"fx:eur-krw","type":"snapshot","version":1}"""
    )

    private val graphTabs = mapOf(
        ("tether" to "1d") to "tether-1d.json",
        ("tether" to "1w") to "tether-1w.json",
        ("usd" to "1d") to "usd-1d.json"
    )
    private val freeTabs = setOf("usd", "jpy", "eur", "tether")
    private val periods = GraphPeriod.entries.map { it.code }.toSet()

    fun route(method: String, path: String, query: Map<String, List<String?>>, webSocketUpgrade: Boolean): S5Route {
        if (path == "/ws") return if (method == "GET" && query.isEmpty() && webSocketUpgrade) S5Route.WebSocket else S5Route.Unexpected
        if (method != "GET") return S5Route.Unexpected
        fun single(name: String): String? = query[name]?.singleOrNull()
        return when (path) {
            "/api/entitlements" ->
                if (query.isEmpty() || query == mapOf("fresh_premium" to listOf("true"))) S5Route.Ok(ENTITLEMENTS) else S5Route.Unexpected
            "/api/v2/graph/catalog" -> if (query.isEmpty()) S5Route.Ok("catalog.json") else S5Route.Unexpected
            "/api/v2/graph/tab" -> {
                val file = if (query.keys == setOf("tab", "period")) graphTabs[single("tab") to single("period")] else null
                if (file != null) S5Route.Ok(file) else S5Route.Unexpected
            }
            "/api/v2/topics/snapshot" -> when {
                query.keys != setOf("topic") -> S5Route.Unexpected
                single("topic") == TopicCatalogue.TETHER -> S5Route.Ok(TETHER_SNAPSHOT)
                single("topic") in TopicCatalogue.DESIRED -> S5Route.Expected404
                else -> S5Route.Unexpected
            }
            "/api/v2/free/snapshot" ->
                if (query.keys == setOf("tab", "period") && single("tab") in freeTabs && single("period") in periods) S5Route.Expected404
                else S5Route.Unexpected
            else -> S5Route.Unexpected
        }
    }
}
