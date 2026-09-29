package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** GET /api/comparison-alerts. */
@Serializable
data class ComparisonAlertsResponse(
    val alerts: List<ComparisonAlertSetting>,
    @SerialName("total_count") val totalCount: Int
)

/** POST /api/comparison-alerts. Null repeat (omitted) creates a once alert. */
@Serializable
data class ComparisonAlertRequest(
    val tab: String,
    @SerialName("left_source") val leftSource: String,
    @SerialName("left_asset") val leftAsset: String,
    @SerialName("right_source") val rightSource: String,
    @SerialName("right_asset") val rightAsset: String,
    @SerialName("diff_type") val diffType: String,
    val operator: String,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("repeat_interval_sec") val repeatIntervalSec: Int? = null
)

/**
 * PUT /api/comparison-alerts/{id}: enabled, repeat, threshold and operator only (the pair and diff_type change by
 * delete and recreate, ADR-037 A4). Only non-null fields are sent; the repeat interval uses [RepeatIntervalChange].
 */
@Serializable
data class ComparisonAlertUpdateRequest(
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    @SerialName("repeat_interval_sec") val repeatIntervalSec: JsonElement? = null,
    val threshold: Double? = null,
    val operator: String? = null
)

/**
 * One delivered comparison alert, a snapshot of the moment it fired (GET /api/comparison-notification-logs; server
 * ComparisonNotificationLogResponse, iOS a36682f ComparisonAlertHistoryItem). spread is the signed raw left - right.
 * Every field is always sent; setting_id and the observed times may be null.
 */
@Serializable
data class ComparisonAlertHistoryItem(
    val id: Int,
    @SerialName("setting_id") val settingId: Int?,
    val tab: String,
    @SerialName("left_source") val leftSource: String,
    @SerialName("left_asset") val leftAsset: String,
    @SerialName("right_source") val rightSource: String,
    @SerialName("right_asset") val rightAsset: String,
    @SerialName("diff_type") val diffType: String,
    val operator: String,
    val threshold: Double,
    @SerialName("left_rate") val leftRate: Double,
    @SerialName("right_rate") val rightRate: Double,
    val spread: Double,
    @SerialName("left_observed_at") @Serializable(with = InstantSerializer::class) val leftObservedAt: Instant?,
    @SerialName("right_observed_at") @Serializable(with = InstantSerializer::class) val rightObservedAt: Instant?,
    @SerialName("is_repeat") val isRepeat: Boolean,
    @SerialName("sent_at") @Serializable(with = InstantSerializer::class) val sentAt: Instant
)

/** Delivered comparison alerts, newest first; total_count is the page length. */
@Serializable
data class ComparisonAlertHistoryResponse(
    val logs: List<ComparisonAlertHistoryItem>,
    @SerialName("total_count") val totalCount: Int
)
