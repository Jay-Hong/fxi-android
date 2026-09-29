package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    /** Three states (B2 ADR-036): null omits the field, JsonNull switches to once, a number sets the interval. Build it with [RepeatIntervalChange]. */
    @SerialName("repeat_interval_sec") val repeatIntervalSec: JsonElement? = null
)

/** A PUT's change to the repeat interval. */
sealed class RepeatIntervalChange {
    /** Leave it as it is: the field is omitted. */
    data object Keep : RepeatIntervalChange()
    /** Fire once: the field is sent as JSON null. */
    data object Once : RepeatIntervalChange()
    /** Repeat every [interval]. */
    data class Every(val interval: RepeatInterval) : RepeatIntervalChange()

    /** The value for [AlertSettingUpdateRequest.repeatIntervalSec]. */
    fun toJson(): JsonElement? = when (this) {
        Keep -> null
        Once -> JsonNull
        is Every -> JsonPrimitive(interval.seconds)
    }
}
