package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.AlertCondition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AlertSettingRequest 직렬화 테스트
 *
 * 재발 방지: encodeDefaults=false 환경에서 is_enabled가 true/false 모두 JSON에 포함되는지 검증
 * - 이전 버그: isEnabled의 기본값이 true여서 true 전송 시 JSON에서 생략됨
 * - 서버는 is_enabled가 없으면 토글을 변경하지 않아 "켜짐"이 동작하지 않았음
 */
class AlertSettingRequestSerializationTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `isEnabled true should be included in JSON`() {
        val request = AlertSettingRequest(
            bank = "kb",
            currency = "usd-krw",
            condition = AlertCondition.BELOW,
            threshold = 1400.0,
            isEnabled = true
        )
        val payload = json.encodeToString(request)

        assertTrue("is_enabled:true should be in JSON", payload.contains("\"is_enabled\":true"))
    }

    @Test
    fun `isEnabled false should be included in JSON`() {
        val request = AlertSettingRequest(
            bank = "kb",
            currency = "usd-krw",
            condition = AlertCondition.BELOW,
            threshold = 1400.0,
            isEnabled = false
        )
        val payload = json.encodeToString(request)

        assertTrue("is_enabled:false should be in JSON", payload.contains("\"is_enabled\":false"))
    }

    @Test
    fun `all fields should be serialized correctly`() {
        val request = AlertSettingRequest(
            bank = "hana",
            currency = "jpy-krw",
            condition = AlertCondition.ABOVE,
            threshold = 950.5,
            isEnabled = true
        )
        val payload = json.encodeToString(request)

        assertTrue(payload.contains("\"bank\":\"hana\""))
        assertTrue(payload.contains("\"currency\":\"jpy-krw\""))
        assertTrue(payload.contains("\"condition\":\"above\""))
        assertTrue(payload.contains("\"threshold\":950.5"))
        assertTrue(payload.contains("\"is_enabled\":true"))
    }
}
