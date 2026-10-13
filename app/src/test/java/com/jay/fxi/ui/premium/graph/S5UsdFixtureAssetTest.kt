package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.di.NetworkModule
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-4 slice 4b: the S5 fixture's USD 1d answer, the premium USD screen the journeys start from. Generated once from the
 * contract usd-1d-krx-visible (11 series in the catalog's order, KRX visible) with every time moved so that its current bucket
 * is D = 2026-10-05T12:00+09:00, the bucket holding the bundle's now. The four earlier fixture files keep their own hashes
 * (TetherBenchmarkFixtureTest BF1).
 */
class S5UsdFixtureAssetTest {

    private val dir = File("src/benchmark/assets/s5_4")
    private val bytes = File(dir, "usd-1d.json").readBytes()
    private val tree = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    @Test
    fun `S5-4 4b the USD answer is the agreed bytes`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("9321ca91305504129167d8c2397cfe367925b367761338e8bfc2d51d27fbae61", sha)
    }

    @Test
    fun `S5-4 4b it parses as the app's tab answer with the catalog's usd 1d series in order`() {
        val wire = NetworkModule.provideWireJson()
        val answer = wire.decodeFromString<GraphV2TabResponse>(bytes.decodeToString())
        val catalog = wire.decodeFromString<GraphV2CatalogResponse>(File(dir, "catalog.json").readText())
        assertEquals("usd", answer.tab)
        assertEquals("1d", answer.period)
        val usd = catalog.tabs.single { it.id == "usd" }.periods.getValue("1d")
        assertEquals(usd.allSeries, answer.series.map { it.id })
        assertEquals(11, answer.series.size)
    }

    @Test
    fun `S5-4 4b every time sits on the bundle's day and bucket`() {
        val d = "2026-10-05T12:00:00+09:00"
        val meta = tree.getValue("metadata").jsonObject
        assertEquals(d, meta.getValue("domain_end_at").jsonPrimitive.content)
        assertEquals("2026-10-04T12:00:00+09:00", meta.getValue("domain_start_at").jsonPrimitive.content)
        assertEquals(d, meta.getValue("fetched_at").jsonPrimitive.content)
        val range = meta.getValue("range").jsonObject
        assertEquals("2026-10-04", range.getValue("start").jsonPrimitive.content)
        assertEquals("2026-10-05", range.getValue("end").jsonPrimitive.content)
        assertEquals(1791169200L, tree.getValue("_in_progress_start_ts").jsonPrimitive.long)
        for ((id, entry) in tree.getValue("in_progress").jsonObject) {
            val e = entry.jsonObject
            assertEquals("$id bucket", d, e.getValue("bucket_start").jsonPrimitive.content)
            val sampled = OffsetDateTime.parse(e.getValue("sampled_at").jsonPrimitive.content).toInstant()
            assertTrue("$id sampled at or before now", !sampled.isAfter(OffsetDateTime.parse("2026-10-05T12:01:00+09:00").toInstant()))
        }
        val points = listOf("11:00", "11:10", "11:20", "11:30", "11:40", "11:50").map { "2026-10-05T$it:00+09:00" }
        for (series in tree.getValue("series").jsonArray) {
            val id = series.jsonObject.getValue("id").jsonPrimitive.content
            val times = series.jsonObject.getValue("data").jsonArray.map { it.jsonObject.getValue("ts").jsonPrimitive.content }
            assertEquals("$id points: the six closed buckets before D", points, times)
        }
    }
}
