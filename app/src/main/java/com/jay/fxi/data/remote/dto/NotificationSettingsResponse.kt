package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertSetting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 알림 설정 목록 응답 DTO
 * 서버 응답: { "settings": [...], "total_count": N }
 */
@Serializable
data class NotificationSettingsResponse(
    val settings: List<AlertSetting>,
    @SerialName("total_count") val totalCount: Int
)
