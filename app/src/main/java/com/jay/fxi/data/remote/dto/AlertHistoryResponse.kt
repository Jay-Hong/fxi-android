package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One delivered bank alert (GET /api/notification-logs; server NotificationLogResponse, iOS a36682f AlertHistoryItem).
 * condition and threshold are null on rows sent before the history was completed (2026-07-15); the server always
 * serializes every field, so none has a default.
 */
@Serializable
data class AlertHistoryItem(
    val id: Int,
    @SerialName("setting_id") val settingId: Int?,
    val bank: String,
    val currency: String,
    val condition: AlertCondition?,
    val threshold: Double?,
    /** The rate that fired it. */
    val rate: Double,
    @SerialName("sent_at")
    @Serializable(with = InstantSerializer::class)
    val sentAt: Instant
)

/** Delivered bank alerts, newest first; total_count is the page length. */
@Serializable
data class AlertHistoryResponse(
    val logs: List<AlertHistoryItem>,
    @SerialName("total_count") val totalCount: Int
)
