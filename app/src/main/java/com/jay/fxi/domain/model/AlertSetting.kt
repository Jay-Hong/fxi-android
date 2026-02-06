package com.jay.fxi.domain.model

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 알림 설정
 */
@Serializable
data class AlertSetting(
    val id: Int,
    @SerialName("user_id") val userId: String,
    val bank: String,
    val currency: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") var isEnabled: Boolean,
    var triggered: Boolean,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @SerialName("updated_at")
    @Serializable(with = InstantSerializer::class)
    var updatedAt: Instant,
    @SerialName("triggered_at")
    @Serializable(with = InstantSerializer::class)
    var triggeredAt: Instant? = null
) {
    val bankType: Bank? get() = Bank.fromCode(bank)
    val currencyType: SupportedCurrency? get() = SupportedCurrency.fromCode(currency)

    /**
     * 포맷된 임계값 (예: "1400.00")
     */
    val formattedThreshold: String
        get() = ExchangeRate.formatRate(threshold, currency)
            .replace("원", "")
            .replace(" (100엔)", "")

    /**
     * 조건 텍스트 (예: "1400.00원 이하")
     */
    val conditionText: String
        get() {
            val formatted = ExchangeRate.formatRate(threshold, currency)
                .replace("원", "")
                .replace(" (100엔)", "")
            val suffix = when (condition) {
                AlertCondition.ABOVE -> "이상"
                AlertCondition.BELOW -> "이하"
            }
            return "${formatted}원 $suffix"
        }
}

/**
 * 알림 조건
 */
@Serializable
enum class AlertCondition {
    @SerialName("above") ABOVE,  // 이상
    @SerialName("below") BELOW;  // 이하

    val displayText: String
        get() = when (this) {
            ABOVE -> "이상"
            BELOW -> "이하"
        }
}

/**
 * AlertSetting 리스트 확장 함수
 */
fun List<AlertSetting>.sortedByCreatedAt(): List<AlertSetting> =
    sortedByDescending { it.createdAt }

fun List<AlertSetting>.filterByCurrency(currency: String): List<AlertSetting> =
    filter { it.currency == currency }

fun List<AlertSetting>.filterByCurrency(currency: SupportedCurrency): List<AlertSetting> =
    filter { it.currency == currency.code }
