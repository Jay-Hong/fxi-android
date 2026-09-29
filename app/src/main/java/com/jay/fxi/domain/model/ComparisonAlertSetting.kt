package com.jay.fxi.domain.model

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A comparison alert between two sources of one tab (ADR-037; server ComparisonAlertResponse, iOS a36682f
 * ComparisonAlertSetting). diff_type "signed" is kimchi premium (left - right, direction kept), "absolute" is a plain
 * comparison (|left - right|); operator "gte" / "lte"; threshold in KRW. Kept as text like iOS. The server's builder
 * sets every field, so the nullable ones have no default.
 */
@Serializable
data class ComparisonAlertSetting(
    val id: Int,
    @SerialName("user_id") val userId: String,
    val tab: String,
    @SerialName("left_source") val leftSource: String,
    @SerialName("left_asset") val leftAsset: String,
    @SerialName("right_source") val rightSource: String,
    @SerialName("right_asset") val rightAsset: String,
    @SerialName("diff_type") val diffType: String,
    val operator: String,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean,
    val triggered: Boolean,
    @SerialName("repeat_interval_sec") val repeatIntervalSec: Int?,
    @SerialName("last_notified_spread") val lastNotifiedSpread: Double?,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(with = InstantSerializer::class) val updatedAt: Instant?
)
