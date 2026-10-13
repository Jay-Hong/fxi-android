package com.jay.fxi.s5fixture

import com.jay.fxi.data.remote.TopicCatalogue
import java.io.File
import kotlinx.serialization.json.Json
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
        assertEquals(S5Route.Ok(S5FixtureRoutes.TETHER_SNAPSHOT), route("GET", "/api/v2/topics/snapshot", "topic" to "usdt:krw"))
        for (topic in listOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "dxy:spot"))
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
            route("GET", "/api/v2/topics/snapshot", "topic" to "usdt:krw", "topic" to "usdt:krw"),
            route("GET", "/api/v2/topics/snapshot", "topic" to "usdt:krw", "x" to "1"),
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

    @Test
    fun `S5-4 4d the topic snapshots are the contract's JSON, one for each topic the session asks for`() {
        val contract = mapOf(
            "usdt:krw" to "topic/snapshot-tether.json",
            "dxy:spot" to "topic/snapshot-dxy.json",
            "fx:usd-krw" to "topic/snapshot-fx-usd-krw.json",
            "fx:jpy-krw" to "topic/snapshot-fx-jpy-krw.json",
            "fx:eur-krw" to "topic/snapshot-fx-eur-krw.json"
        )
        fun json(path: String) = Json.parseToJsonElement(File("src/test/resources/contracts/v2/$path").readText())
        assertEquals(TopicCatalogue.DESIRED, S5FixtureRoutes.TOPIC_SNAPSHOTS.keys)
        for ((topic, path) in contract) assertEquals(topic, json(path), Json.parseToJsonElement(S5FixtureRoutes.TOPIC_SNAPSHOTS.getValue(topic)))
        assertEquals(json("http/topic-snapshot-tether-200.json"), Json.parseToJsonElement(S5FixtureRoutes.TOPIC_SNAPSHOTS.getValue("usdt:krw")))
    }
}
