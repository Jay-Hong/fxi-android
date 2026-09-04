package com.jay.fxi.di

import com.jay.fxi.data.remote.dto.GraphResponse
import com.jay.fxi.data.remote.dto.IndicesPayload
import com.jay.fxi.data.remote.dto.NotificationSettingsResponse
import com.jay.fxi.data.remote.dto.RatesData
import com.jay.fxi.data.remote.dto.WebSocketGraphBucket
import com.jay.fxi.data.remote.dto.WebSocketRatesMessage
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.NewsMetadata
import com.jay.fxi.domain.model.NewsResponse
import com.jay.fxi.domain.model.RatesMetadata
import com.jay.fxi.domain.model.RatesResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private enum class JsonProfileProbeValue {
    KNOWN
}

@Serializable
private data class JsonProfileProbe(
    val value: JsonProfileProbeValue = JsonProfileProbeValue.KNOWN
)

@OptIn(ExperimentalSerializationApi::class)
class JsonProfilesTest {

    @Test
    fun wireJson_matchesFrozenStrictContract() {
        val wireJson = NetworkModule.provideWireJson()

        assertTrue(wireJson.configuration.ignoreUnknownKeys)
        assertFalse(wireJson.configuration.coerceInputValues)
        assertFalse(wireJson.configuration.isLenient)
        assertTrue(wireJson.configuration.explicitNulls)
    }

    @Test
    fun storageJson_preservesLegacyDecodeCompatibility() {
        val storageJson = NetworkModule.provideStorageJson()

        assertTrue(storageJson.configuration.ignoreUnknownKeys)
        assertTrue(storageJson.configuration.coerceInputValues)
        assertTrue(storageJson.configuration.isLenient)
        assertTrue(storageJson.configuration.explicitNulls)
    }

    @Test
    fun wireAndStorageJson_areSeparateInstances() {
        assertNotSame(
            NetworkModule.provideWireJson(),
            NetworkModule.provideStorageJson()
        )
    }

    @Test
    fun wireJson_rejectsUnknownEnumInsteadOfCoercingToDefault() {
        val wireJson = NetworkModule.provideWireJson()

        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString<JsonProfileProbe>("""{"value":"FUTURE"}""")
        }
    }

    @Test
    fun storageJson_keepsLegacyUnknownEnumCoercion() {
        val storageJson = NetworkModule.provideStorageJson()

        assertEquals(
            JsonProfileProbe(),
            storageJson.decodeFromString<JsonProfileProbe>("""{"value":"FUTURE"}""")
        )
    }

    @Test
    fun wireJson_rejectsMissingRequiredGraphFields() {
        val wireJson = NetworkModule.provideWireJson()

        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString<GraphResponse>(
                """{"pair":"usd-krw","sources":{}}"""
            )
        }
    }

    @Test
    fun wireJson_rejectsMissingRequiredNullableAlertField() {
        val wireJson = NetworkModule.provideWireJson()

        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString<AlertSetting>(
                """{
                    "id":1,"user_id":"user","bank":"hana","currency":"usd-krw",
                    "condition":"above","threshold":1400.0,"is_enabled":true,
                    "triggered":false,"created_at":"2026-09-04T00:00:00Z",
                    "updated_at":"2026-09-04T00:00:00Z"
                }""".trimIndent()
            )
        }
    }

    @Test
    fun currentWireResponseDefaults_areLimitedToProtocolOptionalFields() {
        assertOnlyOptional(GraphResponse.serializer().descriptor, setOf("as_of"))
        assertOnlyOptional(AlertSetting.serializer().descriptor, emptySet())
        assertOnlyOptional(NotificationSettingsResponse.serializer().descriptor, emptySet())
        assertOnlyOptional(RatesResult.serializer().descriptor, emptySet())
        assertOnlyOptional(RatesMetadata.serializer().descriptor, emptySet())
        assertOnlyOptional(ExchangeRate.serializer().descriptor, emptySet())
        assertOnlyOptional(GraphBucket.serializer().descriptor, emptySet())
        assertOnlyOptional(NewsResponse.serializer().descriptor, emptySet())
        assertOnlyOptional(NewsMetadata.serializer().descriptor, emptySet())
        assertOnlyOptional(NewsItem.serializer().descriptor, setOf("link"))
        assertOnlyOptional(
            WebSocketRatesMessage.serializer().descriptor,
            setOf("data", "graph_buckets")
        )
        assertOnlyOptional(RatesData.serializer().descriptor, setOf("indices"))
        assertOnlyOptional(IndicesPayload.serializer().descriptor, setOf("dxy"))
        assertOnlyOptional(WebSocketGraphBucket.serializer().descriptor, emptySet())
    }

    private fun assertOnlyOptional(descriptor: SerialDescriptor, expected: Set<String>) {
        val actual = (0 until descriptor.elementsCount)
            .filter(descriptor::isElementOptional)
            .map(descriptor::getElementName)
            .toSet()
        assertEquals("unexpected defaults in ${descriptor.serialName}", expected, actual)
    }
}
