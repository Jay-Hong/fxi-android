package com.jay.fxi.data.alerts

import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.domain.model.ComparisonAlertSetting
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonAlertRowPolicyContractTest {
    private val policy = ComparisonAlertRowPolicy
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val fx = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private val references = listOf("investing", "kb", "hana")
    private val tabs = listOf("tether", "usd", "jpy", "eur")

    private data class Tuple(
        val tab: String, val kind: String,
        val leftSource: String, val leftAsset: String, val rightSource: String, val rightAsset: String
    ) {
        fun reversed() = copy(leftSource = rightSource, leftAsset = rightAsset, rightSource = leftSource, rightAsset = leftAsset)
    }

    private fun admits(t: Tuple) = policy.admits(t.tab, t.kind, t.leftSource to t.leftAsset, t.rightSource to t.rightAsset)

    @Test fun cp01ExactSet() {
        val expected = buildSet {
            for (tab in tabs) {
                val sources = if (tab == "tether") exchanges else fx
                val asset = if (tab == "tether") "usdt-krw" else "$tab-krw"
                for (left in sources) for (right in sources) if (left != right) {
                    add(Tuple(tab, "absolute", left, asset, right, asset))
                }
            }
            for (left in exchanges) for (right in references) {
                add(Tuple("tether", "signed", left, "usdt-krw", right, "usd-krw"))
            }
        }
        val sources = exchanges + fx + listOf("citi", "krx", "unknown-source")
        val assets = listOf("usdt-krw", "usd-krw", "jpy-krw", "eur-krw", "usd-krw-futures", "unknown-asset")
        val legs = sources.flatMap { source -> assets.map { source to it } }
        var examined = 0
        val actual = buildSet {
            for (tab in tabs + "unknown-tab") for (kind in listOf("absolute", "signed", "unknown-kind")) {
                for ((ls, la) in legs) for ((rs, ra) in legs) {
                    val candidate = Tuple(tab, kind, ls, la, rs, ra)
                    if (admits(candidate)) add(candidate)
                    examined++
                }
            }
        }
        assertEquals(156060, examined)
        assertEquals(251, expected.size)
        assertEquals(expected.size, actual.size)
        assertEquals(expected, actual)
    }

    private val signed = Tuple("tether", "signed", "upbit", "usdt-krw", "investing", "usd-krw")
    private val absolute = Tuple("usd", "absolute", "kb", "usd-krw", "hana", "usd-krw")

    @Test fun cp02SingleFieldChanges() {
        for (t in listOf(signed, absolute)) {
            assertTrue(admits(t))
            val fields: List<Pair<String, (String) -> Tuple>> = listOf(
                t.tab to { t.copy(tab = it) }, t.kind to { t.copy(kind = it) },
                t.leftSource to { t.copy(leftSource = it) }, t.leftAsset to { t.copy(leftAsset = it) },
                t.rightSource to { t.copy(rightSource = it) }, t.rightAsset to { t.copy(rightAsset = it) }
            )
            for ((value, change) in fields) for (invalid in listOf(value.uppercase(), " $value", "$value ", "unknown")) {
                assertFalse(change(invalid).toString(), admits(change(invalid)))
            }
            for (invalid in listOf(t.copy(tab = "jpy"), t.copy(leftSource = "krx"), t.copy(rightSource = "citi"),
                t.copy(leftAsset = "jpy-krw"), t.copy(rightAsset = "eur-krw"))) {
                assertFalse(invalid.toString(), admits(invalid))
            }
        }
        assertTrue(admits(signed.copy(leftSource = "korbit")))
        assertTrue(admits(absolute.copy(rightSource = "woori")))
    }

    @Test fun cp03DirectionAndIdenticalLegs() {
        assertFalse(admits(signed.reversed()))
        assertTrue(admits(absolute.reversed()))
        for (t in listOf(signed, absolute)) {
            assertFalse(admits(t.copy(rightSource = t.leftSource, rightAsset = t.leftAsset)))
        }
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource("/contracts/v2/$path.json")).readText()

    /** Extract each pair-bearing object directly from its settings, history, request or push envelope. */
    private fun goldenRows(): List<Pair<String, JsonObject>> {
        val paths = listOf("alerts/comparison-setting-response", "alerts/comparison-settings-list",
            "alerts/comparison-history-item", "alerts/comparison-history-list", "alerts/comparison-signed-create-request",
            "alerts/comparison-absolute-create-request", "fcm/comparison-alert-signed-repeat", "fcm/comparison-alert-absolute-once")
        return paths.flatMap { path ->
            val root = Json.parseToJsonElement(resource(path)).jsonObject
            val objects = when {
                path.endsWith("settings-list") -> root.getValue("alerts").jsonArray.map { it.jsonObject }
                path.endsWith("history-list") -> root.getValue("logs").jsonArray.map { it.jsonObject }
                path.startsWith("fcm/") -> listOf(root.getValue("data").jsonObject)
                else -> listOf(root)
            }
            check(objects.isNotEmpty()) { path }
            objects.map { path to it }
        }
    }

    private fun JsonObject.tuple(): Tuple {
        fun field(key: String) = getValue(key).jsonPrimitive.content
        return Tuple(field("tab"), field("diff_type"), field("left_source"), field("left_asset"),
            field("right_source"), field("right_asset"))
    }

    @Test fun cp04RejectOriginalGoldens() {
        for ((path, original) in goldenRows()) assertFalse(path, admits(original.tuple()))
    }

    @Test fun cp05DeriveAllowedPairsFromGoldens() {
        for ((path, original) in goldenRows()) {
            val changes = if (original.tuple().kind == "signed") {
                assertEquals(path, "krx" to "usd-krw-futures", original.tuple().rightSource to original.tuple().rightAsset)
                mapOf("right_source" to JsonPrimitive("investing"), "right_asset" to JsonPrimitive("usd-krw"))
            } else {
                assertEquals(path, "krx" to "usd-krw-futures", original.tuple().leftSource to original.tuple().leftAsset)
                mapOf("left_source" to JsonPrimitive("kb"), "left_asset" to JsonPrimitive("usd-krw"))
            }
            val derived = JsonObject(original + changes)
            assertEquals(path, original.keys, derived.keys)
            assertEquals(path, original - changes.keys, derived - changes.keys)
            assertEquals(path, changes.keys, original.keys.filter { original[it] != derived[it] }.toSet())
            assertTrue(path, admits(derived.tuple()))
        }
    }

    private val uid = "fixture-user"
    private val baseSetting = Json.decodeFromString<ComparisonAlertSetting>(resource("alerts/comparison-setting-response"))
    private val baseLog = Json.decodeFromString<ComparisonAlertHistoryItem>(resource("alerts/comparison-history-item"))
    private val sections = listOf(absolute, signed, absolute.copy(tab = "eur", leftAsset = "eur-krw", rightAsset = "eur-krw"),
        Tuple("tether", "absolute", "upbit", "usdt-krw", "gopax", "usdt-krw"),
        absolute.copy(tab = "jpy", leftAsset = "jpy-krw", rightAsset = "jpy-krw"))

    private fun row(id: Int, t: Tuple = absolute) = baseSetting.copy(id = id, tab = t.tab, diffType = t.kind,
        leftSource = t.leftSource, leftAsset = t.leftAsset, rightSource = t.rightSource, rightAsset = t.rightAsset)

    @Test fun cp06ExcludeEveryCollisionBeforeFiltering() {
        val first = row(92)
        val last = row(2, signed)
        val unique = listOf(first, row(51).copy(leftSource = "krx"), row(52).copy(rightSource = "krx"),
            row(53).copy(tab = "unknown"), row(54).copy(userId = "other"))
        val collisions = listOf(row(61), row(61, signed), row(62), row(62).copy(rightSource = "krx"),
            row(63), row(63).copy(userId = "other"))
        for (duplicates in listOf(collisions, collisions.reversed())) {
            assertEquals(listOf(first, last), policy.adopt(uid, unique + duplicates + last).rows)
        }
    }

    @Test fun cp07ProjectBothTabAndKind() {
        val first = sections.mapIndexed { index, t -> row(20 - index, t) }
        val second = sections.mapIndexed { index, t -> row(10 - index, t) }
        val adopted = policy.adopt(uid, first + second.reversed())
        for ((index, t) in sections.withIndex()) {
            assertEquals(listOf(first[index], second[index]), adopted.project(t.tab, t.kind))
        }
    }

    @Test fun cp08CountAcrossAllSectionsAndStates() {
        for (expected in listOf(29, 30, 31)) {
            val rows = (1..expected).map { id ->
                row(id, sections[id % sections.size]).copy(isEnabled = id % 2 == 0, triggered = id % 3 == 0)
            }
            val excluded = listOf(row(100).copy(userId = "other"), row(101).copy(leftSource = "citi"),
                row(102), row(102, signed))
            assertEquals(expected, policy.adopt(uid, rows + excluded).count)
        }
    }

    private fun history(id: Int, t: Tuple) = baseLog.copy(id = id, tab = t.tab, diffType = t.kind,
        leftSource = t.leftSource, leftAsset = t.leftAsset, rightSource = t.rightSource, rightAsset = t.rightAsset)

    @Test fun cp09FilterHistoryAndPreserveWholeRowsInServerOrder() {
        val early = Instant.parse("2026-09-01T00:00:00Z")
        val late = Instant.parse("2026-09-02T00:00:00Z")
        for (section in listOf(signed, sections[3], absolute)) {
            val first = history(82, section).copy(settingId = null, leftObservedAt = null, rightObservedAt = late)
            val last = history(2, section).copy(settingId = 17, leftObservedAt = early, rightObservedAt = null)
            val otherSections = sections.filter { it != section }.mapIndexed { index, t -> history(30 + index, t) }
            val input = listOf(first, first.copy(id = 10, leftSource = "krx")) + otherSections +
                listOf(last.copy(id = 11, rightSource = "krx"), last)
            assertEquals(listOf(first, last), policy.adoptHistory(section.tab, section.kind, input))
        }
    }
}
