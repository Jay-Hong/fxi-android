package com.jay.fxi.data.remote.dto

import com.jay.fxi.di.NetworkModule
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeSnapshotResponseTest {
    @Test
    fun flatAndGroupedDiscriminators_decodeAndKeepEmptyReference() {
        // Kills always-flat/always-grouped dispatch and reuse of the non-empty topic singleton decoder.
        val flat = FreeSnapshotFixtures.decode()
        val grouped = FreeSnapshotFixtures.decode(FreeSnapshotFixtures.snapshot("tether"))
        assertEquals("usd-krw", (flat.rate["asset"] as JsonPrimitive).content)
        assertEquals(emptyMap<String, JsonElement>(), grouped.rate["usd_krw_reference"])
        assertEquals("upbit", (grouped.rate["usdt_krw"] as JsonArray).single().let { it as JsonObject }["source"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun unknownNullAndHybridDiscriminators_failWithValidControls() {
        // Kills unknown-kind fallback and either direction of hybrid-shape acceptance.
        val flat = FreeSnapshotFixtures.snapshot()
        val grouped = FreeSnapshotFixtures.snapshot("tether")
        assertEquals("usd-krw", (FreeSnapshotFixtures.decode(flat).rate["asset"] as JsonPrimitive).content)
        assertEquals("source_grouped", (FreeSnapshotFixtures.decode(grouped).rate["kind"] as JsonPrimitive).content)
        val badRates = listOf(
            (flat.getValue("rate") as JsonObject).with("kind", JsonPrimitive("future_kind")),
            (flat.getValue("rate") as JsonObject).with("kind", kotlinx.serialization.json.JsonNull),
            (flat.getValue("rate") as JsonObject).with("usdt_krw", JsonArray(emptyList())),
            (grouped.getValue("rate") as JsonObject).with("entries", JsonArray(emptyList()))
        )
        badRates.forEach { rate ->
            assertThrows(SerializationException::class.java) { FreeSnapshotFixtures.decode(flat.with("rate", rate)) }
        }
    }

    @Test
    fun timestampsAndOptionalServeFields_preserveExactInstants() {
        // Kills as_of/generated_at/rnb aliasing and loss of additive graph-domain fields.
        val response = FreeSnapshotFixtures.decode()
        assertEquals(Instant.parse("2026-09-06T01:30:00Z"), response.asOf)
        assertEquals(Instant.parse("2026-09-06T01:30:19Z"), response.generatedAt)
        assertEquals(Instant.parse("2026-09-06T02:31:00Z"), response.refreshNotBefore)
        assertEquals(Instant.parse("2026-09-05T01:30:00Z"), response.graph.domainStartAt)
        assertEquals(response.asOf, response.graph.domainEndAt)
        val legacy = JsonObject(FreeSnapshotFixtures.snapshot() - "refresh_not_before")
        assertNull(FreeSnapshotFixtures.decode(legacy).refreshNotBefore)
    }

    @Test
    fun malformedOrMissingRequiredEnvelope_isNotAnEmptySuccess() {
        // Kills defaults/coercion that turn broken required envelopes into usable cache values.
        val valid = FreeSnapshotFixtures.snapshot()
        assertEquals("usd", FreeSnapshotFixtures.decode(valid).tab)
        listOf("tab", "period", "as_of", "generated_at", "rate", "graph").forEach { key ->
            assertThrows(SerializationException::class.java) { FreeSnapshotFixtures.decode(JsonObject(valid - key)) }
        }
        listOf("as_of", "generated_at", "refresh_not_before").forEach { key ->
            listOf("banana", "2026-09-06T10:30:00").forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    FreeSnapshotFixtures.decode(valid.with(key, JsonPrimitive(invalid)))
                }
            }
        }
    }

    @Test
    fun rateSchemaForbidsEveryExtraAndRequiresCorrectContainerTypes() {
        // Kills a hybrid-only blacklist (instead of extra=forbid), omitted required keys and coercion.
        listOf("usd", "tether").forEach { tab ->
            val good = FreeSnapshotFixtures.snapshot(tab)
            val rate = good.getValue("rate") as JsonObject
            assertEquals(rate, FreeSnapshotFixtures.decode(good).rate)
            rate.keys.forEach { key ->
                assertThrows(SerializationException::class.java) {
                    FreeSnapshotFixtures.decode(good.with("rate", JsonObject(rate - key)))
                }
                assertThrows(SerializationException::class.java) {
                    FreeSnapshotFixtures.decode(good.with("rate", rate.with(key, JsonPrimitive(17))))
                }
            }
            listOf("unknown", "usd_krw_futures", "asset", "entries", "primary_asset",
                "usdt_krw", "usd_krw_banks", "usd_krw_reference").filter { it !in rate }.forEach { extra ->
                // The marker is deliberately innocuous: this must fail on schema, independently of KRX.
                assertThrows(SerializationException::class.java) {
                    FreeSnapshotFixtures.decode(good.with("rate", rate.with(extra, JsonArray(emptyList()))))
                }
            }
        }
    }

    @Test
    fun unknownEntryMetadataIsPreservedForSanitizerInspection() {
        // Kills eagerly decoding to a narrow rate/point DTO and silently losing hidden provenance.
        val valid = FreeSnapshotFixtures.snapshot()
        val entry = FreeSnapshotFixtures.rate().with("nested", FreeSnapshotFixtures.obj("""{"source":"krx"}"""))
        val rate = (valid.getValue("rate") as JsonObject).with("entries", JsonArray(listOf(entry)))
        assertEquals(entry, (FreeSnapshotFixtures.decode(valid.with("rate", rate)).rate["entries"] as JsonArray).single())
        assertEquals(FreeSnapshotFixtures.rate(), (FreeSnapshotFixtures.decode(valid).rate["entries"] as JsonArray).single())
    }
}

/** Shared realistic wire fixtures; tests mutate one field while retaining an accepted control. */
internal object FreeSnapshotFixtures {
    val json = NetworkModule.provideWireJson()
    const val BASIS = "2026-09-06T10:30:00+09:00"

    fun decode(value: JsonObject = snapshot()): FreeSnapshotResponse = json.decodeFromString(value.toString())
    fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject
    fun rate(source: String = "investing", asset: String = "usd-krw", value: Double = 1400.0, flat: Boolean = true): JsonObject =
        obj("""{"${if (flat) "bank" else "source"}":"$source","${if (flat) "currency" else "asset"}":"$asset","rate":$value,"timestamp":"$BASIS"}""")

    fun point(source: String = "investing"): JsonObject = obj(
        """{"ts":"$BASIS","rate":1400.0,"source":"$source","high":1401.0,"low":1399.0,"close_basis":"market_close","source_method":"observed"}"""
    )

    fun series(id: String = "investing.usd", points: List<JsonElement> = listOf(point())): JsonObject = obj(
        """{"id":"$id","label":"Reference","axis_group":"krw","unit":"KRW","decimals":2,
        "data":[],"provenance":{"insufficient_history":false,"per_point_metadata":["close_basis","source_method"],"default_close_basis":"market_close"},
        "carry_in":{"rate":1398.0,"observed_at":"2026-09-05T10:20:00+09:00"}}"""
    ).with("data", JsonArray(points))

    fun snapshot(tab: String = "usd", period: String = "1d"): JsonObject {
        val asset = if (tab == "tether") "usdt-krw" else "$tab-krw"
        val rate = if (tab == "tether") obj(
            """{"kind":"source_grouped","primary_asset":"usdt-krw","usdt_krw":[${rate("upbit", "usdt-krw", flat = false)}],"usd_krw_banks":[],"usd_krw_reference":{}}"""
        ) else obj("""{"asset":"$asset","entries":[${rate(asset = asset)}]}""")
        return obj("""{"tab":"$tab","period":"$period","as_of":"$BASIS","generated_at":"2026-09-06T10:30:19+09:00",
          "refresh_not_before":"2026-09-06T11:31:00+09:00","rate":$rate,
          "graph":{"series":[${series(if (tab == "tether") { if (period == "1d") "upbit.usdt-krw" else "bithumb.usdt-krw" } else "investing.$tab")}],"bucket_size":"10min",
          "range":{"start":"2026-09-05","end":"2026-09-06"},"domain_start_at":"2026-09-05T10:30:00+09:00",
          "domain_end_at":"$BASIS","live_domain_mode":"rolling"}}""")
    }
}

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
