package com.jay.fxi.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRequest(
    @SerialName("device_token") val deviceToken: String,
    val platform: String
)
