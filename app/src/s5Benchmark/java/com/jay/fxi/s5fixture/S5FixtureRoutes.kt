package com.jay.fxi.s5fixture

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.domain.model.GraphPeriod

/** What the S5 fixture server answers to one request. */
internal sealed interface S5Route {
    /** A fixed body from [payload]: an asset file name or [S5FixtureRoutes.ENTITLEMENTS]. */
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
            "/api/v2/topics/snapshot" ->
                if (query.keys == setOf("topic") && single("topic") in TopicCatalogue.DESIRED) S5Route.Expected404 else S5Route.Unexpected
            "/api/v2/free/snapshot" ->
                if (query.keys == setOf("tab", "period") && single("tab") in freeTabs && single("period") in periods) S5Route.Expected404
                else S5Route.Unexpected
            else -> S5Route.Unexpected
        }
    }
}
