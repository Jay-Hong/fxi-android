package com.jay.fxi.contract

import com.jay.fxi.data.remote.DecodedTopicFrame
import com.jay.fxi.data.remote.TopicFrameDecoder
import kotlin.math.abs
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractPayloadTest {
    private val corpus by lazy {
        ContractCorpusVerifier.verify(ContractResourceLoader.load())
    }
    private val wireJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private val decoder = TopicFrameDecoder(wireJson)

    @Test
    fun `current topic decoder consumes every currently supported golden`() {
        val ackIds = topicIds("topic-ack-")
        assertEquals(setOf(
            "topic-ack-enforce-all-granted",
            "topic-ack-krx-denied",
            "topic-ack-lease-boundary-900",
            "topic-ack-premium-denied",
            "topic-ack-topics-disabled"
        ), ackIds.toSet())
        ackIds.forEach { id ->
            assertTrue(id, decoder.decode(corpus.text(id)) is DecodedTopicFrame.Acknowledgement)
        }
        val errorIds = topicIds("topic-error-")
        assertEquals(setOf(
            "topic-error-invalid-request",
            "topic-error-invalid-token",
            "topic-error-request-too-large",
            "topic-error-temporarily-unavailable"
        ), errorIds.toSet())
        errorIds.forEach { id ->
            assertTrue(id, decoder.decode(corpus.text(id)) is DecodedTopicFrame.RequestFailure)
        }
        val fxIds = topicIds("topic-snapshot-fx-")
        assertEquals(setOf(
            "topic-snapshot-fx-usd-krw",
            "topic-snapshot-fx-jpy-krw",
            "topic-snapshot-fx-eur-krw"
        ), fxIds.toSet())
        fxIds.forEach { id ->
            assertTrue(id, decoder.decode(corpus.text(id)) is DecodedTopicFrame.Fx)
        }
        assertTrue(
            decoder.decode(corpus.text("topic-snapshot-tether")) is DecodedTopicFrame.Tether
        )
        assertTrue(
            decoder.decode(corpus.text("topic-snapshot-krx")) is DecodedTopicFrame.Krx
        )
        assertEquals(
            DecodedTopicFrame.Unsupported("snapshot", "dxy:spot"),
            decoder.decode(corpus.text("topic-snapshot-dxy"))
        )
        assertEquals(
            DecodedTopicFrame.NotTopic,
            decoder.decode(corpus.text("topic-control-pong"))
        )
    }

    @Test
    fun `lease and whole request boundaries remain explicit`() {
        val frame = decoder.decode(corpus.text("topic-ack-lease-boundary-900"))
            as DecodedTopicFrame.Acknowledgement
        assertEquals(900L, frame.value.acceptedTopics.single().leaseDurationSeconds)

        val tooLarge = corpus.entry("topic-error-request-too-large")
        assertEquals(ContractReachability.VOCABULARY_ONLY, tooLarge.runtimeReachability)
        val error = decoder.decode(corpus.text(tooLarge.id)) as DecodedTopicFrame.RequestFailure
        assertTrue(error.value.isTerminal)
    }

    @Test
    fun `free route corpus is the complete four by four KRX-free matrix`() {
        val entries = corpus.manifest.fixtures.filter {
            it.family == ContractFamily.FREE_SNAPSHOT
        }
        val combinations = entries.map { entry ->
            val payload = json(entry.id)
            payload.string("tab") to payload.string("period")
        }.toSet()
        val expected = setOf("usd", "jpy", "eur", "tether").flatMap { tab ->
            setOf("1d", "1w", "3m", "1y").map { period -> tab to period }
        }.toSet()

        assertEquals(expected, combinations)
        entries.forEach { entry ->
            assertEquals(ContractOrigin.ROUTE, entry.origin)
            val payload = json(entry.id)
            val graph = payload["graph"]!!.jsonObject
            assertTrue(entry.id, graph["series"]!!.jsonArray.any {
                it.jsonObject["data"]!!.jsonArray.isNotEmpty()
            })
            assertFalse(entry.id, "in_progress" in graph)
            assertTrue(entry.id, payload.string("refresh_not_before").isNotBlank())
            val raw = corpus.text(entry.id)
            assertFalse(entry.id, raw.contains("krx."))
            assertFalse(entry.id, raw.contains("usd-krw-futures"))
            assertFalse(entry.id, raw.contains("\"source\": \"krx\""))
        }
    }

    @Test
    fun `adversarial fixtures each carry exactly their declared mutation`() {
        val expectedDiffs = mapOf(
            "adversarial-allowed-id-krx-provenance" to
                setOf("/graph/series/0/data/0/source"),
            "adversarial-cross-tab-series" to setOf("/graph/series/3"),
            "adversarial-direct-krx-series" to setOf("/graph/series/3"),
            "adversarial-futures-rate-group" to setOf("/rate/usd_krw_futures"),
            "adversarial-free-in-progress" to setOf("/graph/in_progress")
        )
        expectedDiffs.forEach { (id, expected) ->
            val entry = corpus.entry(id)
            val baseId = requireNotNull(entry.derivedFrom)
            assertEquals(id, expected, diffPaths(json(baseId), json(id)))
        }

        val allowedId = json("adversarial-allowed-id-krx-provenance")
        val point = allowedId["graph"]!!.jsonObject["series"]!!.jsonArray
            .first().jsonObject["data"]!!.jsonArray.first().jsonObject
        assertEquals("krx", point.string("source"))

        val crossTab = json("adversarial-cross-tab-series")
        assertTrue(crossTab.seriesIds().contains("bithumb.usdt-krw"))
        assertEquals(
            json("free-tether-3m").series("bithumb.usdt-krw"),
            crossTab.series("bithumb.usdt-krw")
        )
        val direct = json("adversarial-direct-krx-series")
        assertTrue(direct.seriesIds().contains("krx.usd-krw-futures"))
        assertEquals(
            json("graph-usd-3m-krx-visible").series("krx.usd-krw-futures"),
            direct.series("krx.usd-krw-futures")
        )

        val futures = json("adversarial-futures-rate-group")
        val futuresGroup = futures["rate"]!!.jsonObject["usd_krw_futures"]!!.jsonObject
        assertEquals(
            setOf("source", "asset", "rate", "timestamp", "rate_changed_at"),
            futuresGroup.keys
        )
        val asOf = Instant.parse(futures.string("as_of"))
        assertTrue(Instant.parse(futuresGroup.string("timestamp")) <= asOf)
        assertTrue(Instant.parse(futuresGroup.string("rate_changed_at")) <= asOf)
        val inProgress = json("adversarial-free-in-progress")
        val live = inProgress["graph"]!!.jsonObject["in_progress"]!!.jsonObject
            .getValue("investing.usd").jsonObject
        assertEquals(
            setOf("bucket_start", "sampled_at", "high", "low", "close"),
            live.keys
        )
        val liveAsOf = Instant.parse(inProgress.string("as_of"))
        assertTrue(Instant.parse(live.string("bucket_start")) <= liveAsOf)
        assertTrue(Instant.parse(live.string("sampled_at")) <= liveAsOf)

        val duplicateEntry = corpus.entry("adversarial-duplicate-key-first-wins")
        val duplicateBase = corpus.text(requireNotNull(duplicateEntry.derivedFrom))
        val requestIdLine = "  \"request_id\": \"enforce-all\",\n"
        assertEquals(1, Regex(Regex.escape(requestIdLine)).findAll(duplicateBase).count())
        val expectedDuplicate = duplicateBase.replaceFirst(
            requestIdLine,
            requestIdLine + "  \"request_id\": \"attacker-value\",\n"
        )
        val duplicate = corpus.text("adversarial-duplicate-key-first-wins")
        assertEquals(expectedDuplicate, duplicate)
        assertEquals(2, Regex("\\\"request_id\\\"").findAll(duplicate).count())
    }

    @Test
    fun `alert update corpus preserves omitted null and repeat as three states`() {
        for (family in listOf("bank", "source", "comparison")) {
            assertEquals(emptySet<String>(), json("alert-$family-update-omitted").keys)
            assertEquals(
                JsonNull,
                json("alert-$family-update-once-null")["repeat_interval_sec"]
            )
            assertEquals(
                300,
                json("alert-$family-update-repeat")["repeat_interval_sec"]!!.jsonPrimitive.int
            )
        }
    }

    @Test
    fun `comparison history golden represents a triggered delivery`() {
        val item = json("alert-comparison-history-item")
        val listed = json("alert-comparison-history-list")["logs"]!!
            .jsonArray.single().jsonObject
        assertEquals(item, listed)

        val leftRate = item["left_rate"]!!.jsonPrimitive.double
        val rightRate = item["right_rate"]!!.jsonPrimitive.double
        val spread = item["spread"]!!.jsonPrimitive.double
        val threshold = item["threshold"]!!.jsonPrimitive.double
        assertEquals(leftRate - rightRate, spread, 0.0)

        val comparisonValue = when (item.string("diff_type")) {
            "absolute" -> abs(spread)
            "signed" -> spread
            else -> error("unsupported comparison diff type")
        }
        val matched = when (item.string("operator")) {
            "gte" -> comparisonValue >= threshold
            "lte" -> comparisonValue <= threshold
            else -> false
        }
        assertTrue("history must satisfy its recorded trigger", matched)
    }

    @Test
    fun `application owned HTTP errors are real route captures`() {
        val expected = setOf(
            "http-auth-401",
            "http-auth-infrastructure-503",
            "http-free-period-400",
            "http-free-snapshot-503",
            "http-free-tab-404",
            "http-graph-period-400",
            "http-graph-tab-404",
            "http-krx-distribution-disabled-403",
            "http-krx-entitlement-required-403",
            "http-premium-inactive-403",
            "http-premium-pending-503",
            "http-validation-422"
        )
        val captured = corpus.manifest.fixtures.filter { it.id in expected }
        assertEquals(expected, captured.mapTo(mutableSetOf()) { it.id })
        captured.forEach { entry ->
            assertEquals(entry.id, ContractOrigin.ROUTE, entry.origin)
            assertTrue(entry.id, entry.source["routeTraversed"]!!.jsonPrimitive.boolean)
        }
        assertEquals(
            ContractOrigin.EXTERNAL,
            corpus.entry("http-proxy-429-html").origin
        )
    }

    @Test
    fun `graph corpus locks catalog carry in and KRX filtered live tail`() {
        val hiddenCatalog = corpus.text("graph-catalog-krx-hidden")
        val visibleCatalog = corpus.text("graph-catalog-krx-visible")
        assertFalse(hiddenCatalog.contains("krx.usd-krw-futures"))
        assertTrue(visibleCatalog.contains("krx.usd-krw-futures"))

        val history = json("graph-usd-3m-krx-hidden")
        assertTrue(history["series"]!!.jsonArray.all { series ->
            series.jsonObject["carry_in"] !is JsonNull
        })

        val hidden = json("graph-usd-1d-krx-hidden")
        val visible = json("graph-usd-1d-krx-visible")
        assertFalse(hidden.seriesIds().contains("krx.usd-krw-futures"))
        assertFalse("krx.usd-krw-futures" in hidden["in_progress"]!!.jsonObject)
        assertTrue(visible.seriesIds().contains("krx.usd-krw-futures"))
        assertTrue("krx.usd-krw-futures" in visible["in_progress"]!!.jsonObject)

        val freeUsd1d = json("free-usd-1d")
        assertTrue(freeUsd1d.series("dxy")["data"]!!.jsonArray.isNotEmpty())
        val freeTether1d = json("free-tether-1d")
        for (indexSeries in listOf("dxy", "dxy_futures")) {
            assertTrue(
                indexSeries,
                freeTether1d.series(indexSeries)["data"]!!.jsonArray.isNotEmpty()
            )
        }
        assertTrue(visible.series("dxy")["data"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `visible FCM data is string normalized while sync remains data only`() {
        val fcmEntries = corpus.manifest.fixtures.filter { it.family == ContractFamily.FCM }
        assertEquals(7, fcmEntries.size)
        fcmEntries.filterNot { it.id == "fcm-sync-alerts" }.forEach { entry ->
            val payload = json(entry.id)
            assertTrue(entry.id, payload["notification"] is JsonObject)
            assertTrue(
                entry.id,
                entry.source["clientVisibleProjection"]!!.jsonPrimitive.boolean
            )
            assertFalse(
                entry.id,
                entry.source["firebaseSdkTransportCaptured"]!!.jsonPrimitive.boolean
            )
            payload["data"]!!.jsonObject.values.forEach { value ->
                assertTrue(entry.id, value.jsonPrimitive.isString)
            }
            assertTrue(entry.id, payload["data"]!!.jsonObject.keys.containsAll(
                setOf("type", "title", "body", "setting_id", "is_repeat")
            ))
        }
        val sync = json("fcm-sync-alerts")
        assertFalse("notification" in sync)
        assertTrue(
            corpus.entry("fcm-sync-alerts").source["dataOnly"]!!.jsonPrimitive.boolean
        )
        assertEquals(setOf("type"), sync["data"]!!.jsonObject.keys)
        assertEquals("sync_alerts", sync["data"]!!.jsonObject.string("type"))
    }

    @Test
    fun `server registries export the identifiers consumed by v2`() {
        val registry = json("registry-allowed-identifiers")
        val registryEntry = corpus.entry("registry-allowed-identifiers")
        val topics = registry["topicIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val seriesByTabPeriod = registry["graphSeriesIdsByTabPeriod"]!!.jsonObject
        val sourcePairs = registry["sources"]!!.jsonArray.map { value ->
            val source = value.jsonObject
            source.string("source") to source.string("asset")
        }.toSet()

        assertTrue(topics.containsAll(setOf(
            "fx:usd-krw", "usdt:krw", "krx:usd-krw-futures", "dxy:spot"
        )))
        assertFalse("graphSeriesIds" in registry)
        assertTrue(sourcePairs.containsAll(setOf(
            "upbit" to "usdt-krw", "krx" to "usd-krw-futures"
        )))
        assertEquals(ContractPremium.ACTIVE, registryEntry.config.premium)
        assertEquals(ContractAuthStage.NOT_APPLICABLE, registryEntry.config.authStage)
        assertEquals(ContractToggle.ON, registryEntry.config.g1)
        assertEquals(ContractToggle.ON, registryEntry.config.g2)
        assertEquals(ContractToggle.ON, registryEntry.config.g3)
        assertEquals(
            "entitled-superset",
            registryEntry.source["authorizationScope"]!!.jsonPrimitive.content
        )
        assertTrue(
            registryEntry.source["builderArguments"]!!.jsonObject
                .getValue("krx_visible").jsonPrimitive.boolean
        )
        assertFalse(
            registryEntry.source["entitlementEvaluationTraversed"]!!.jsonPrimitive.boolean
        )
        assertTrue(
            registryEntry.source["krxCapabilityCheckRequired"]!!.jsonPrimitive.boolean
        )
        assertEquals(
            corpus.entry("graph-catalog-krx-visible").config,
            registryEntry.config
        )

        seriesByTabPeriod.forEach { (tab, rawPeriods) ->
            val tabPeriods = rawPeriods.jsonObject
            tabPeriods.forEach { (period, rawSeries) ->
                val ids = rawSeries.jsonArray.map { it.jsonPrimitive.content }
                assertEquals("$tab/$period", ids.size, ids.toSet().size)
            }
        }

        val visibleCatalog = json("graph-catalog-krx-visible")
        assertEquals(
            visibleCatalog["tabs"]!!.jsonArray
                .mapTo(mutableSetOf()) { it.jsonObject.string("id") },
            seriesByTabPeriod.keys
        )
        visibleCatalog["tabs"]!!.jsonArray.forEach { rawTab ->
            val tab = rawTab.jsonObject
            val tabId = tab.string("id")
            assertEquals(tab["periods"]!!.jsonObject.keys, seriesByTabPeriod
                .getValue(tabId).jsonObject.keys)
            tab["periods"]!!.jsonObject.forEach { (period, rawContract) ->
                val catalogSeries = rawContract.jsonObject["all_series"]!!.jsonArray
                    .map { it.jsonPrimitive.content }
                val registrySeries = seriesByTabPeriod.getValue(tabId).jsonObject
                    .getValue(period).jsonArray.map { it.jsonPrimitive.content }
                assertEquals("$tabId/$period", catalogSeries, registrySeries)
            }
        }
        val tether1d = seriesByTabPeriod.ids("tether", "1d")
        assertTrue(tether1d.containsAll(setOf("upbit.usdt-krw", "dxy_futures")))
        val usd1d = seriesByTabPeriod.ids("usd", "1d")
        assertTrue(usd1d.containsAll(setOf("kb.usd", "shinhan.usd")))
        assertFalse("upbit.usdt-krw" in usd1d)
        assertFalse("dxy_futures" in usd1d)
    }

    @Test
    fun `entitlement metadata marks unread KRX gates not applicable`() {
        val pending = corpus.entry("entitlements-pending").config
        assertEquals(ContractPremium.PENDING, pending.premium)
        assertEquals(ContractToggle.NOT_APPLICABLE, pending.g1)
        assertEquals(ContractToggle.NOT_APPLICABLE, pending.g2)
        assertEquals(ContractToggle.NOT_APPLICABLE, pending.g3)

        val inactive = corpus.entry("entitlements-inactive").config
        assertEquals(ContractPremium.INACTIVE, inactive.premium)
        assertEquals(ContractToggle.NOT_APPLICABLE, inactive.g1)
        assertEquals(ContractToggle.NOT_APPLICABLE, inactive.g2)
        assertEquals(ContractToggle.NOT_APPLICABLE, inactive.g3)
    }

    private fun topicIds(prefix: String): List<String> =
        corpus.entriesById.keys.filter { it.startsWith(prefix) }

    private fun json(id: String): JsonObject =
        Json.parseToJsonElement(corpus.text(id)).jsonObject

    private fun JsonObject.string(key: String): String =
        requireNotNull(this[key]).jsonPrimitive.content

    private fun JsonObject.seriesIds(): Set<String> {
        val container = this["graph"]?.jsonObject ?: this
        return container["series"]!!.jsonArray
            .mapTo(mutableSetOf()) { it.jsonObject.string("id") }
    }

    private fun JsonObject.series(id: String): JsonObject {
        val container = this["graph"]?.jsonObject ?: this
        return container["series"]!!.jsonArray
            .map { it.jsonObject }
            .single { it.string("id") == id }
    }

    private fun JsonObject.ids(tab: String, period: String): Set<String> =
        getValue(tab).jsonObject.getValue(period).jsonArray
            .mapTo(mutableSetOf()) { it.jsonPrimitive.content }

    private fun diffPaths(
        expected: JsonElement,
        actual: JsonElement,
        path: String = ""
    ): Set<String> = when {
        expected is JsonObject && actual is JsonObject ->
            (expected.keys + actual.keys).flatMapTo(linkedSetOf()) { key ->
                val childPath = "$path/$key"
                val left = expected[key]
                val right = actual[key]
                if (left == null || right == null) {
                    listOf(childPath)
                } else {
                    diffPaths(left, right, childPath)
                }
            }
        expected is JsonArray && actual is JsonArray -> {
            val result = linkedSetOf<String>()
            val common = minOf(expected.size, actual.size)
            repeat(common) { index ->
                result += diffPaths(expected[index], actual[index], "$path/$index")
            }
            for (index in common until maxOf(expected.size, actual.size)) {
                result += "$path/$index"
            }
            result
        }
        expected != actual -> setOf(path)
        else -> emptySet()
    }
}
