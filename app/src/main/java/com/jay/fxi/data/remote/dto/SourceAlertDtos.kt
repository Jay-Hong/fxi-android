package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
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
