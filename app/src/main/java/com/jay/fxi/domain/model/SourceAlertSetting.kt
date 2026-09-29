package com.jay.fxi.domain.model

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A source price alert (tether exchanges and KRX USD futures; server SourceNotificationSettingResponse, iOS a36682f
 * SourceAlertSetting). The server's builder sets every field, so the nullable ones have no default.
 */
@Serializable
data class SourceAlertSetting(
    val id: Int,
    @SerialName("user_id") val userId: String,
    /** upbit / bithumb / coinone / korbit / gopax / krx. */
    val source: String,
    /** usdt-krw / usd-krw-futures. */
    val asset: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean,
    val triggered: Boolean,
    /** Seconds between repeated fires; null fires once. */
    @SerialName("repeat_interval_sec") val repeatIntervalSec: Int?,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @SerialName("updated_at") @Serializable(with = InstantSerializer::class) val updatedAt: Instant?,
    @SerialName("triggered_at") @Serializable(with = InstantSerializer::class) val triggeredAt: Instant?
)
