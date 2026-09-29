package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** GET /api/source-notification-settings. */
@Serializable
data class SourceAlertSettingsResponse(
    val settings: List<SourceAlertSetting>,
    @SerialName("total_count") val totalCount: Int
)

/** POST /api/source-notification-settings. Null repeat (omitted) creates a once alert. */
@Serializable
data class SourceAlertSettingRequest(
    val source: String,
    val asset: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("repeat_interval_sec") val repeatIntervalSec: Int? = null
)

/**
 * PUT /api/source-notification-settings/{id}: only the non-null fields are sent. The repeat interval has three states,
 * built with [RepeatIntervalChange] (omitted / JSON null = once / seconds).
 */
@Serializable
data class SourceAlertSettingUpdateRequest(
    val source: String? = null,
    val asset: String? = null,
    val condition: AlertCondition? = null,
    val threshold: Double? = null,
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    @SerialName("repeat_interval_sec") val repeatIntervalSec: JsonElement? = null
)

/** One delivered source alert (GET /api/source-notification-logs; server SourceNotificationLogResponse, iOS SourceAlertHistoryItem). */
@Serializable
data class SourceAlertHistoryItem(
    val id: Int,
    @SerialName("setting_id") val settingId: Int?,
    val source: String,
    val asset: String,
    val condition: AlertCondition,
    val threshold: Double,
    /** The rate that fired it. */
    @SerialName("triggered_rate") val triggeredRate: Double,
    @SerialName("sent_at") @Serializable(with = InstantSerializer::class) val sentAt: Instant
)

/** Delivered source alerts, newest first; total_count is the page length. */
@Serializable
data class SourceAlertHistoryResponse(
    val logs: List<SourceAlertHistoryItem>,
    @SerialName("total_count") val totalCount: Int
)
