package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import kotlinx.serialization.json.*
import org.junit.Assert.*

internal object FloorGuardFixtures {
    val codec = ControlPayloadCodec()
    val transition = RemoveEmptyGuardTransition(codec)
    val empty = ControlLifecycleEvidenceFixtures.guard
    val now = BootReading("boot", 11000)
    val origin = LifetimeId("new-life")
    fun node(raw: String) = ControlLifecycleEvidenceFixtures.node(raw)
    fun field(node: ControlNode, key: String, value: JsonElement?) = ControlNode.of(JsonObject(
        node.toPayloadEntry().fields.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }))
    fun floor(wait: Long = 30000, boot: String? = "boot", elapsed: Long = 10000, origin: String = "life") = node(
        """{"anchorBootId":${boot?.let(::JsonPrimitive) ?: JsonNull},"anchorElapsedMillis":$elapsed,"waitMillis":$wait,"originLifetimeId":"$origin"}""").toPayloadEntry().fields
    fun guard(floor: JsonObject? = floor(), auth: Boolean = false): ControlNode = field(
        if (auth) DemandAuthFixtures.guard(DemandAuthFixtures.auth.copy(authStopped = false)) else empty, "floor", floor)
    fun hold(floor: JsonObject? = floor()): ControlNode = if (floor == null) node(ControlObligationFixtures.topicHold) else
        field(node(ControlObligationFixtures.hold), "floor", floor)
    fun input(old: ControlNode? = guard(), hold: ControlNode = hold(), now: BootReading = this.now,
        origin: LifetimeId = this.origin, id: String = "new-guard") = HoldFloorInput(hold, old, now, origin, id)
    fun raw(vararg nodes: ControlNode, hold: ControlNode? = null): Preferences = ControlLifecycleEvidenceFixtures.raw(
        demand = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() },
        hold = hold?.let { "[${it.toPayloadEntry().fields}]" } ?: "[]")
    fun read(raw: Preferences) = ControlLifecycleEvidenceFixtures.read(raw)
    fun descriptor(g: ControlNode = empty) = RemoveEmptyGuardPlan.prepare(g).descriptor("lc")
    fun command(g: ControlNode = empty) = ControlLifecycleEvidenceFixtures.command(descriptor(g))
    fun candidate(c: CommandRef, raw: Preferences): Preferences {
        val d = ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input,
            read(raw), null, false, false)
        assertTrue("eligible empty guard must reach Confirm: $d", d is RecordTransactionDecision.Confirm)
        return (d as RecordTransactionDecision.Confirm).candidate
    }
    fun rows(raw: Preferences, kind: ControlKind, rows: List<ControlNode>): Preferences = raw.toMutablePreferences().apply {
        this[ControlRecordKeys.payload(kind)] = rows.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    }
    fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)
}
