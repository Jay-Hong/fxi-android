package com.jay.fxi.data.remote.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Untrusted wire envelope. Entry/series JSON stays private to the pre-cache sanitizer boundary. */
@Serializable
data class FreeSnapshotResponse(
    val tab: String,
    val period: String,
    @SerialName("as_of") val asOf: Instant,
    @SerialName("generated_at") val generatedAt: Instant,
    @SerialName("refresh_not_before") val refreshNotBefore: Instant? = null,
    @Serializable(with = FreeSnapshotRateSerializer::class) val rate: JsonObject,
    val graph: FreeSnapshotGraphResponse
)

/**
 * Preserve the raw rate object until sanitization: a discriminator must never hide another
 * container. The strict rate schema is enforced even though the application's Json ignores extras.
 */
object FreeSnapshotRateSerializer : KSerializer<JsonObject> {
    override val descriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonObject =
        JsonObject.serializer().deserialize(decoder).also(::validateShape)

    override fun serialize(encoder: Encoder, value: JsonObject) {
        validateShape(value)
        JsonObject.serializer().serialize(encoder, value)
    }

    internal fun validateShape(block: JsonObject) {
        val grouped = block["kind"] == JsonPrimitive("source_grouped")
        val keys = if (grouped) GROUPED_KEYS else FLAT_KEYS
        if (block.keys != keys) throw SerializationException("Invalid free rate shape or extra fields")
        fun requireString(key: String) {
            val value = block[key] as? JsonPrimitive
            if (value?.isString != true || value.content.isBlank()) {
                throw SerializationException("Invalid free rate string: $key")
            }
        }
        if (grouped) {
            requireString("primary_asset")
            if (block["usdt_krw"] !is JsonArray || block["usd_krw_banks"] !is JsonArray ||
                block["usd_krw_reference"] !is JsonObject) {
                throw SerializationException("Invalid grouped free rate containers")
            }
        } else {
            requireString("asset")
            if (block["entries"] !is JsonArray) throw SerializationException("Invalid flat free entries")
        }
    }

    private val FLAT_KEYS = setOf("asset", "entries")
    private val GROUPED_KEYS = setOf("kind", "primary_asset", "usdt_krw", "usd_krw_banks", "usd_krw_reference")
}

@Serializable
data class FreeSnapshotGraphResponse(
    val series: List<JsonElement>,
    @SerialName("bucket_size") val bucketSize: String,
    val range: FreeSnapshotRangeResponse,
    @SerialName("domain_start_at") val domainStartAt: Instant? = null,
    @SerialName("domain_end_at") val domainEndAt: Instant? = null,
    @SerialName("live_domain_mode") val liveDomainMode: String? = null
)

@Serializable
data class FreeSnapshotRangeResponse(val start: String, val end: String)
