package com.jay.fxi.domain.model

import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.di.NetworkModule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Claude-owned repeat_interval data-layer contract (release audit: repeat_interval_sec was absent on Android while the
 * server and iOS support it). Oracles: the server contract in exchange-rate CLAUDE.md (allowed values; POST null = once;
 * PUT absent = unchanged / null = once / number = repeat) and iOS a36682f RepeatInterval and AlertSettingUpdateRequest.
 * The JSON is the production wire configuration (NetworkModule.provideWireJson). No screen uses it yet.
 * The implementation thread reads but does not edit this file.
 */
class RepeatIntervalContractTest {
    private val json = NetworkModule.provideWireJson()
    private fun encoded(request: AlertSettingUpdateRequest): JsonObject =
        json.parseToJsonElement(json.encodeToString(AlertSettingUpdateRequest.serializer(), request)).jsonObject
    private fun setting(extra: String) = json.decodeFromString(AlertSetting.serializer(), """{"id":1,"user_id":"u","bank":"kb",
        "currency":"usd-krw","condition":"above","threshold":1400.0,"is_enabled":true,"triggered":false,
        "created_at":"2026-09-29T00:00:00Z","updated_at":"2026-09-29T00:00:00Z","triggered_at":null$extra}""")

    @Test fun R01_theAcceptedIntervals_inOrder_withTheirLabels() {
        assertEquals(listOf(60, 300, 600, 1800, 3600, 7200, 14400, 21600, 43200, 86400), RepeatInterval.entries.map { it.seconds })
        assertEquals(listOf("1분", "5분", "10분", "30분", "1시간", "2시간", "4시간", "6시간", "12시간", "1일"), RepeatInterval.entries.map { it.label })
        assertEquals(RepeatInterval.entries.map { "${it.label}마다" }, RepeatInterval.entries.map { it.everyLabel })
    }

    @Test fun R02_fromSeconds_knownValuesOnly() {
        for (interval in RepeatInterval.entries) assertEquals(interval, RepeatInterval.from(interval.seconds))
        for (seconds in listOf(null, 0, -60, 59, 61, 120, 86401)) assertNull("$seconds", RepeatInterval.from(seconds))
    }

    /** The server always serializes it (NotificationSettingResponse, like triggered_at), so a missing field is a contract break, not "once" (JsonProfilesTest policy). */
    @Test fun R03_aSettingReadsItsInterval_nullIsOnce_aMissingFieldIsRefused() {
        assertEquals(300, setting(",\"repeat_interval_sec\":300").repeatIntervalSec)
        assertNull(setting(",\"repeat_interval_sec\":null").repeatIntervalSec)
        assertThrows(SerializationException::class.java) { setting("") }
    }

    @Test fun R04_createOmitsTheFieldForOnce_andSendsTheSeconds() {
        fun create(sec: Int?) = json.parseToJsonElement(json.encodeToString(AlertSettingRequest.serializer(),
            AlertSettingRequest("kb", "usd-krw", AlertCondition.ABOVE, 1400.0, true, sec))).jsonObject
        assertFalse("once omits it", "repeat_interval_sec" in create(null))
        assertEquals(JsonPrimitive(3600), create(3600)["repeat_interval_sec"])
    }

    @Test fun R05_updateHasThreeStates() {
        val keep = encoded(AlertSettingUpdateRequest(isEnabled = true, repeatIntervalSec = RepeatIntervalChange.Keep.toJson()))
        assertFalse("keep omits it", "repeat_interval_sec" in keep)
        val once = encoded(AlertSettingUpdateRequest(isEnabled = true, repeatIntervalSec = RepeatIntervalChange.Once.toJson()))
        assertEquals("once sends null", JsonNull, once["repeat_interval_sec"])
        for (interval in RepeatInterval.entries) assertEquals("$interval", JsonPrimitive(interval.seconds),
            encoded(AlertSettingUpdateRequest(repeatIntervalSec = RepeatIntervalChange.Every(interval).toJson()))["repeat_interval_sec"])
    }

    @Test fun R06_aPartialUpdateStillSendsOnlyWhatChanged() {
        assertEquals(setOf("is_enabled"), encoded(AlertSettingUpdateRequest(isEnabled = false)).keys)
        assertEquals(setOf("threshold", "repeat_interval_sec"),
            encoded(AlertSettingUpdateRequest(threshold = 1.0, repeatIntervalSec = RepeatIntervalChange.Once.toJson())).keys)
    }
}
