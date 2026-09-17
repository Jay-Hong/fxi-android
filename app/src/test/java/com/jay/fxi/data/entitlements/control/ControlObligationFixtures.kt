package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal object ControlObligationFixtures {
    const val seal = """{"id":"s","kind":"NAMESPACE","ownerUid":null,"axis":"USER","epoch":"old"}"""
    const val nullSeal = """{"id":"s","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER"}"""
    const val request = """{"id":"d","kind":"REQUEST","ownerUid":"A","binding":3,"originLifetimeId":"life","intent":"IF_STALE","raisedAt":4}"""
    const val emptyGuard = """{"id":"g","kind":"SCHEDULE_GUARD"}"""
    const val floor = """{"anchorBootId":"boot","anchorElapsedMillis":10000,"waitMillis":30000,"originLifetimeId":"life"}"""
    const val auth = """{"ownerUid":"A","authGeneration":2,"binding":3,"originLifetimeId":"life","authStopped":true,"authStateOrder":8,"authStopAppliedOrder":10}"""
    val guard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":$floor,"auth":$auth}"""
    const val fence = """{"ownerUid":"A","userAccessEpoch":"u","krxCapabilityEpoch":"k"}"""
    const val identity = """{"ownerUid":"A","authGeneration":2}"""
    val query = """{"kind":"QUERY","started":{"fence":$fence,"generation":5,"boundIdentity":$identity,"order":7,"binding":3,"intent":"FORCE_PREMIUM","userInvalidations":0},"answeredAs":$identity}"""
    val topic = """{"kind":"TOPIC","grant":9,"context":{"identity":$identity,"access":$fence,"generation":5}}"""
    val hold = """{"id":"h","originLifetimeId":"life","binding":3,"axes":["CAPABILITY"],"outcome":{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":30},"provenance":$query,"floor":$floor}"""
    val topicHold = """{"id":"t","originLifetimeId":"life","binding":3,"axes":["USER","CAPABILITY"],"outcome":{"kind":"PREMIUM_REQUIRED"},"provenance":$topic}"""
    const val recovery = """{"id":"r","sessionId":"session","ownerUid":null,"axis":"CAPABILITY","targetEpoch":null}"""
    val settlement = """{"operationId":"op","originLifetimeId":"life","operation":"BEGIN_ROTATION","before":{"ownerUid":"A","userAccessEpoch":null,"krxCapabilityEpoch":"k"},"after":$fence,"journal":{"ownerUid":"A","axis":"USER","epoch":null}}"""
    val settledSeal = nullSeal.dropLast(1) + ",\"settlement\":" + settlement + "}"

    val complete = listOf(
        ControlKind.SEAL to seal,
        ControlKind.SEAL to settledSeal,
        ControlKind.DEMAND to request,
        ControlKind.DEMAND to guard,
        ControlKind.HOLD to hold,
        ControlKind.HOLD to topicHold,
        ControlKind.RECOVERY_INTENT to recovery
    )

    fun node(json: String): ControlNode = ControlNode.of(Json.parseToJsonElement(json) as JsonObject)
    fun text(node: ControlNode): String = (ControlPayloadCodec().encode(listOf(node.toPayloadEntry())) as PayloadWrite.Encoded).text
    fun written(result: ControlWriteResult): ControlNode {
        assertTrue("$result", result is ControlWriteResult.Written)
        return (result as ControlWriteResult.Written).node
    }
    fun invalid(result: ControlWriteResult) = assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), result)

    /** Test-only literal input, not a production serialization or edit path. */
    fun ControlBuilder.literal(json: String) = fields(Json.parseToJsonElement(json) as JsonObject)
    fun ControlBuilder.fields(json: JsonObject) {
        json.forEach { (name, value) -> when (value) {
            is JsonObject -> objectField(name) { fields(value) }
            is JsonArray -> set(name, ControlScalar.Names(value.map { (it as JsonPrimitive).content }))
            JsonNull -> set(name, ControlScalar.Null)
            is JsonPrimitive -> set(name, when {
                value.isString -> ControlScalar.Text(value.content)
                value.content == "true" || value.content == "false" -> ControlScalar.Flag(value.content.toBoolean())
                else -> ControlScalar.Integer(value.content.toLong())
            })
        } }
    }

    fun objects(root: JsonObject, path: List<String> = emptyList()): List<List<String>> =
        listOf(path) + root.flatMap { (name, value) -> if (value is JsonObject) objects(value, path + name) else emptyList() }

    fun change(root: JsonObject, path: List<String>, edit: (JsonObject) -> JsonObject): JsonObject =
        if (path.isEmpty()) edit(root) else JsonObject(root + (path.first() to change(root[path.first()] as JsonObject, path.drop(1), edit)))

    fun replace(json: String, path: List<String>, name: String, value: JsonElement): ControlNode =
        ControlNode.of(change(Json.parseToJsonElement(json) as JsonObject, path) { JsonObject(it + (name to value)) })
}
