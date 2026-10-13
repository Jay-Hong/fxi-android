package com.jay.fxi.s5fixture

import org.junit.Assert.assertEquals
import org.junit.Test

/** S5-4 slice 4b: the fixture answers exactly its request table; anything else is unexpected. */
class S5FixtureRoutesTest {

    private fun route(method: String, path: String, vararg query: Pair<String, String>, upgrade: Boolean = false) =
        S5FixtureRoutes.route(method, path, query.groupBy({ it.first }, { it.second }), upgrade)

    @Test
    fun `S5-4 4b the journey's requests have their fixed answers`() {
        assertEquals(S5Route.Ok("entitlements"), route("GET", "/api/entitlements"))
        assertEquals(S5Route.Ok("entitlements"), route("GET", "/api/entitlements", "fresh_premium" to "true"))
        assertEquals(S5Route.Ok("catalog.json"), route("GET", "/api/v2/graph/catalog"))
        assertEquals(S5Route.Ok("tether-1d.json"), route("GET", "/api/v2/graph/tab", "tab" to "tether", "period" to "1d"))
        assertEquals(S5Route.Ok("tether-1w.json"), route("GET", "/api/v2/graph/tab", "period" to "1w", "tab" to "tether"))
        assertEquals(S5Route.Ok("usd-1d.json"), route("GET", "/api/v2/graph/tab", "tab" to "usd", "period" to "1d"))
        for (topic in listOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot"))
            assertEquals(topic, S5Route.Expected404, route("GET", "/api/v2/topics/snapshot", "topic" to topic))
        for (tab in listOf("usd", "jpy", "eur", "tether")) for (period in listOf("1d", "1w", "3m", "1y"))
            assertEquals("$tab $period", S5Route.Expected404, route("GET", "/api/v2/free/snapshot", "tab" to tab, "period" to period))
        assertEquals(S5Route.WebSocket, route("GET", "/ws", upgrade = true))
    }

    @Test
    fun `S5-4 4b anything outside the table is unexpected`() {
        val cases = listOf(
            route("GET", "/api/entitlements", "fresh_premium" to "false"),
            route("GET", "/api/entitlements", "fresh_premium" to ""),
            route("GET", "/api/entitlements", "fresh_premium" to "true", "fresh_premium" to "true"),
            route("GET", "/api/entitlements", "fresh_premium" to "true", "x" to "1"),
            route("POST", "/api/entitlements"),
            route("GET", "/api/v2/graph/catalog", "tab" to "usd"),
            route("GET", "/api/v2/graph/tab", "tab" to "usd", "period" to "1w"),
            route("GET", "/api/v2/graph/tab", "tab" to "tether"),
            route("GET", "/api/v2/graph/tab", "tab" to "tether", "period" to "1d", "extra" to "1"),
            route("GET", "/api/v2/topics/snapshot", "topic" to "krx:usd-krw-futures"),
            route("GET", "/api/v2/topics/snapshot"),
            route("GET", "/api/v2/free/snapshot", "tab" to "krw", "period" to "1d"),
            route("GET", "/api/v2/free/snapshot", "tab" to "usd", "period" to "2d"),
            route("DELETE", "/api/user/me"),
            route("GET", "/api/news"),
            route("GET", "/ws"),
            route("POST", "/ws", upgrade = true),
            route("GET", "/ws", "x" to "1", upgrade = true)
        )
        cases.forEachIndexed { index, actual -> assertEquals("case $index", S5Route.Unexpected, actual) }
    }
}
