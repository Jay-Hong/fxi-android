package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 알림 설정 부분 업데이트용 DTO
 *
 * 모든 필드가 nullable이며, null이 아닌 필드만 JSON에 포함됩니다.
 * encodeDefaults=false 환경에서 변경된 필드만 전송하기 위해 사용합니다.
 *
 * Note: currency는 서버에서 업데이트를 지원하지 않아 제외됨
 */
@Serializable
data class AlertSettingUpdateRequest(
    val bank: String? = null,
    val condition: AlertCondition? = null,
    val threshold: Double? = null,
    @SerialName("is_enabled") val isEnabled: Boolean? = null
)
