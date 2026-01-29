package com.jay.fxi.data.remote.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRequestSerializationTest {

    @Test
    fun serializesPlatformField() {
        val json = Json { encodeDefaults = false }
        val payload = json.encodeToString(
            DeviceRequest(deviceToken = "test-token", platform = "android")
        )

        assertTrue(payload.contains("\"device_token\":\"test-token\""))
        assertTrue(payload.contains("\"platform\":\"android\""))
    }
}
