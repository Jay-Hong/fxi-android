package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AlertSettingRequest(
    val bank: String,
    val currency: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean
)
