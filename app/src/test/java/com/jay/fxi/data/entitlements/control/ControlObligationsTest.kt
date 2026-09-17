package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.auth
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.change
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.complete
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.fence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.fields
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.floor
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.identity
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.invalid
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.nullSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.objects
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.query
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.replace
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settledSeal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settlement
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.text
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.topic
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.topicHold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.written
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ControlObligationsTest {
    @Test fun `all kinds build and no-op edit successfully with exact literal content`() {
        (complete + (ControlKind.DEMAND to emptyGuard) + (ControlKind.SEAL to nullSeal)).forEach { (kind, json) ->
            val built = written(ControlObligations.build(kind) { literal(json) })
            assertEquals("[$json]", text(built))
            assertTrue(ControlObligations.read(kind, built) is ControlEntryRead.Interpreted)
            var called = false
            assertEquals(text(built), text(written(ControlObligations.editExisting(kind, built) { called = true })))
            assertTrue(called)
        }
    }

    @Test fun `empty builders never create any kind`() {
        ControlKind.entries.forEach { invalid(ControlObligations.build(it) {}) }
    }

    @Test fun `unknown fields at every nested object stop callback and preserve literal tree`() {
        complete.forEach { (kind, json) ->
            val raw = Json.parseToJsonElement(json) as JsonObject
            objects(raw).forEach { path ->
                val original = ControlNode.of(change(raw, path) { JsonObject(it + ("future" to Json.parseToJsonElement("1e-400"))) })
                val saved = text(original)
                assertUninterpretable(kind, original, "$kind $path")
                assertEquals(saved, text(original))
            }
        }
    }

    @Test fun `every required key at every depth is required before editing`() {
        complete.forEach { (kind, json) ->
            val raw = Json.parseToJsonElement(json) as JsonObject
            objects(raw).forEach { path ->
                var obj = raw
                path.forEach { obj = obj[it] as JsonObject }
                obj.keys.forEach { key ->
                    val optional = path.isEmpty() && ((kind == ControlKind.SEAL && key == "settlement") ||
                        (kind == ControlKind.DEMAND && key in setOf("floor", "auth")))
                    if (!optional) {
                        val modified = change(raw, path) { JsonObject(it - key) }
                        assertUninterpretable(kind, ControlNode.of(modified), "$kind $path/$key")
                    }
                }
            }
        }
    }

    @Test fun `wrong types at every field including optional children never default`() {
        complete.forEach { (kind, json) ->
            val raw = Json.parseToJsonElement(json) as JsonObject
            objects(raw).forEach { path ->
                var obj = raw
                path.forEach { obj = obj[it] as JsonObject }
                obj.keys.forEach { key ->
                    val wrong = if (key == "axes") JsonPrimitive(5) else JsonArray(emptyList())
                    assertUninterpretable(kind, replace(json, path, key, wrong), "$kind $path/$key")
                }
            }
        }
    }

    @Test fun `every newly built malformed obligation is rejected as an invalid change`() {
        complete.forEach { (kind, json) ->
            val raw = Json.parseToJsonElement(json) as JsonObject
            raw.keys.forEach { key ->
                if (key !in setOf("floor", "auth", "settlement")) {
                    invalid(ControlObligations.build(kind) { fields(JsonObject(raw - key)) })
                }
            }
            invalid(ControlObligations.build(kind) { literal(json); set("future", ControlScalar.Text("x")) })
        }
    }

    @Test fun `null owner differs from missing and empty owner in seal and recovery`() {
        for ((kind, json) in listOf(ControlKind.SEAL to seal, ControlKind.RECOVERY_INTENT to recovery)) {
            assertTrue(ControlObligations.read(kind, node(json)) is ControlEntryRead.Interpreted)
            assertTrue(ControlObligations.read(kind, replace(json, emptyList(), "ownerUid", JsonPrimitive(""))) is ControlEntryRead.Interpreted)
            assertUninterpretable(kind, ControlNode.of((Json.parseToJsonElement(json) as JsonObject) - "ownerUid"))
            invalid(ControlObligations.editExisting(kind, node(json)) { set("ownerUid", ControlScalar.Text("")) })
        }
    }

    @Test fun `recovery accepts null or nonempty epoch and never missing empty or wrong epoch type`() {
        assertTrue(ControlObligations.read(ControlKind.RECOVERY_INTENT, node(recovery)) is ControlEntryRead.Interpreted)
        written(ControlObligations.build(ControlKind.RECOVERY_INTENT) { literal(recovery); set("targetEpoch", ControlScalar.Text("epoch")) })
        assertUninterpretable(ControlKind.RECOVERY_INTENT, replace(recovery, emptyList(), "targetEpoch", JsonPrimitive("")))
    }

    @Test fun `valid immutable replacements are refused independently of final schema validity`() {
        complete.forEach { (kind, json) ->
            val original = node(json)
            val changedId = replace(json, emptyList(), "id", JsonPrimitive("other"))
            assertTrue(ControlObligations.read(kind, changedId) is ControlEntryRead.Interpreted)
            invalid(ControlObligations.editExisting(kind, original) { set("id", ControlScalar.Text("other")) })
            assertEquals("[$json]", text(original))
        }
        invalid(ControlObligations.editExisting(ControlKind.SEAL, node(seal)) { set("epoch", ControlScalar.Text("new")) })
        invalid(ControlObligations.editExisting(ControlKind.HOLD, node(hold)) { descend("provenance") { descend("started") { set("generation", ControlScalar.Integer(6)) } } })
        invalid(ControlObligations.editExisting(ControlKind.RECOVERY_INTENT, node(recovery)) { set("sessionId", ControlScalar.Text("other-session")) })
    }

    @Test fun `demand strengthens with newer event and independently replaces event preserving other fields`() {
        val original = node(request)
        val stronger = written(ControlObligations.editExisting(ControlKind.DEMAND, original) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM")); set("raisedAt", ControlScalar.Integer(9))
        })
        assertEquals("[${request.replace("IF_STALE", "FORCE_PREMIUM").replace("\"raisedAt\":4", "\"raisedAt\":9")}]", text(stronger))
        val newer = written(ControlObligations.editExisting(ControlKind.DEMAND, stronger) { set("raisedAt", ControlScalar.Integer(12)) })
        assertTrue(text(newer).contains("\"raisedAt\":12"))
        assertEquals("[$request]", text(original))
    }

    @Test fun `successful edit preserves untouched known integer literal rather than rebuilding facts`() {
        val literal = request.replace("\"binding\":3", "\"binding\":-0")
        val changed = written(ControlObligations.editExisting(ControlKind.DEMAND, node(literal)) {
            set("intent", ControlScalar.Text("FORCE_ENTITLEMENTS")); set("raisedAt", ControlScalar.Integer(5))
        })
        assertEquals("[${literal.replace("IF_STALE", "FORCE_ENTITLEMENTS").replace("\"raisedAt\":4", "\"raisedAt\":5")}]", text(changed))
    }

    @Test fun `ids and nested counters enforce their domain without exceptions or defaults`() {
        complete.forEach { (kind, json) ->
            assertUninterpretable(kind, replace(json, emptyList(), "id", JsonPrimitive("")))
        }
        for ((json, path, key) in listOf(
            Triple(hold, listOf("provenance", "started"), "generation"),
            Triple(hold, listOf("provenance", "started"), "userInvalidations"),
            Triple(hold, listOf("provenance", "started", "boundIdentity"), "authGeneration"),
            Triple(topicHold, listOf("provenance"), "grant"),
            Triple(topicHold, listOf("provenance", "context"), "generation"),
            Triple(hold, listOf("outcome"), "retryAfterSeconds"),
            Triple(hold, listOf("floor"), "anchorElapsedMillis")
        )) assertUninterpretable(ControlKind.HOLD, replace(json, path, key, JsonPrimitive(-1)))
    }

    @Test fun `demand rejects weakening decreasing event and strengthening without fresh event`() {
        val strong = node(request.replace("IF_STALE", "FORCE_PREMIUM"))
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, strong) { set("intent", ControlScalar.Text("IF_STALE")); set("raisedAt", ControlScalar.Integer(5)) })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, strong) { set("raisedAt", ControlScalar.Integer(3)) })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, node(request)) { set("intent", ControlScalar.Text("FORCE_ENTITLEMENTS")) })
    }

    @Test fun `seal can first append rotation evidence without changing target and cannot replace it`() {
        val first = written(ControlObligations.editExisting(ControlKind.SEAL, node(nullSeal)) { createChild("settlement") { literal(settlement) } })
        assertEquals("[$settledSeal]", text(first))
        invalid(ControlObligations.editExisting(ControlKind.SEAL, first) { descend("settlement") { set("operationId", ControlScalar.Text("different")) } })
        assertEquals("[$settledSeal]", text(first))
    }

    @Test fun `null seal identity retirement requires exact owner journal and changed owner`() {
        listOf("SIGN_OUT", "BIND_OWNER", "RETIRE_UNVERIFIED_START").forEach { op ->
            val valid = settledSeal.replace("BEGIN_ROTATION", op).replace("\"after\":$fence", "\"after\":${fence.replace("\"A\"", "\"B\"")}")
            written(ControlObligations.build(ControlKind.SEAL) { literal(valid) })
            assertUninterpretable(ControlKind.SEAL, replace(valid, listOf("settlement", "journal"), "ownerUid", JsonNull))
            assertUninterpretable(ControlKind.SEAL, replace(valid, listOf("settlement", "after"), "ownerUid", JsonPrimitive("A")))
        }
    }

    @Test fun `null seal cannot settle by allocation unrelated operation or mismatched journal`() {
        for (op in listOf("LOAD", "COMPLETE_PURGES", "MARK_MAY_CONTAIN_DATA", "BEGIN_SIGN_OUT", "JOURNAL_RETIRED")) {
            assertUninterpretable(ControlKind.SEAL, node(settledSeal.replace("BEGIN_ROTATION", op)))
        }
        assertUninterpretable(ControlKind.SEAL, replace(settledSeal, listOf("settlement", "journal"), "axis", JsonPrimitive("CAPABILITY")))
        assertUninterpretable(ControlKind.SEAL, replace(settledSeal, listOf("settlement", "journal"), "epoch", JsonPrimitive("other")))
        assertUninterpretable(ControlKind.SEAL, replace(settledSeal, listOf("settlement", "before"), "userAccessEpoch", JsonPrimitive("u")))
        assertUninterpretable(ControlKind.SEAL, replace(settledSeal, listOf("settlement", "after"), "userAccessEpoch", JsonNull))
    }

    @Test fun `namespace settlement needs retirement and covering cleanup while null target forbids epoch key`() {
        val valid = seal.dropLast(1) + ",\"settlement\":" + settlement.replace("\"journal\":{\"ownerUid\":\"A\"", "\"journal\":{\"ownerUid\":null") + "}"
        written(ControlObligations.build(ControlKind.SEAL) { literal(valid) })
        assertUninterpretable(ControlKind.SEAL, replace(valid, listOf("settlement", "after"), "userAccessEpoch", JsonPrimitive("old")))
        assertUninterpretable(ControlKind.SEAL, replace(valid, listOf("settlement", "journal"), "epoch", JsonPrimitive("wrong")))
        assertUninterpretable(ControlKind.SEAL, replace(nullSeal, emptyList(), "epoch", JsonNull))
    }

    @Test fun `auth is first created then restopped then resumed preserving floor and origin`() {
        val created = written(ControlObligations.editExisting(ControlKind.DEMAND, node(emptyGuard)) { createChild("auth") { literal(auth) } })
        assertEquals("[${emptyGuard.dropLast(1)},\"auth\":$auth}]", text(created))
        val original = node(guard)
        val restopped = written(ControlObligations.editExisting(ControlKind.DEMAND, original) { descend("auth") {
            set("authStateOrder", ControlScalar.Integer(11)); set("authStopAppliedOrder", ControlScalar.Integer(13))
        } })
        val resumed = written(ControlObligations.editExisting(ControlKind.DEMAND, restopped) { descend("auth") {
            set("authStopped", ControlScalar.Flag(false)); set("authStateOrder", ControlScalar.Integer(12))
        } })
        // A late query may resume with its start order below the last stop's application order.
        assertEquals("[${guard.replace("true", "false").replace("\"authStateOrder\":8", "\"authStateOrder\":12").replace("\"authStopAppliedOrder\":10", "\"authStopAppliedOrder\":13")}]", text(resumed))
        assertEquals("[$guard]", text(original))
    }

    @Test fun `auth cannot collapse orders reuse old event change identity or change stop order on resume`() {
        val base = node(guard)
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, base) { descend("auth") { set("authStopped", ControlScalar.Flag(false)) } })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, base) { descend("auth") { set("authStateOrder", ControlScalar.Integer(9)) } })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, base) { descend("auth") {
            set("authStopped", ControlScalar.Flag(false)); set("authStateOrder", ControlScalar.Integer(11)); set("authStopAppliedOrder", ControlScalar.Integer(12))
        } })
        for (name in listOf("ownerUid", "originLifetimeId")) invalid(ControlObligations.editExisting(ControlKind.DEMAND, base) { descend("auth") { set(name, ControlScalar.Text("other")) } })
        for (name in listOf("binding", "authGeneration")) invalid(ControlObligations.editExisting(ControlKind.DEMAND, base) { descend("auth") { set(name, ControlScalar.Integer(20)) } })
        assertUninterpretable(ControlKind.DEMAND, replace(guard, listOf("auth"), "ownerUid", JsonNull))
    }

    @Test fun `hold has five reachable outcomes and exact axes`() {
        val raw = Json.parseToJsonElement(hold) as JsonObject
        val outcomes = listOf(
            """{"kind":"STABLE_ACTIVE","krxVisible":false}""" to listOf("CAPABILITY"),
            """{"kind":"STABLE_INACTIVE","krxVisible":true}""" to listOf("USER", "CAPABILITY"),
            """{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":null}""" to listOf("CAPABILITY"),
            """{"kind":"PREMIUM_REQUIRED"}""" to listOf("USER", "CAPABILITY"),
            """{"kind":"KRX_ENTITLEMENT_REQUIRED"}""" to listOf("CAPABILITY")
        )
        outcomes.forEach { (outcome, axes) ->
            val valid = JsonObject((raw - "floor") + mapOf("outcome" to Json.parseToJsonElement(outcome), "axes" to JsonArray(axes.map(::JsonPrimitive))))
            written(ControlObligations.build(ControlKind.HOLD) { fields(valid) })
            assertUninterpretable(ControlKind.HOLD, ControlNode.of(valid + ("axes" to JsonArray(emptyList()))))
            assertUninterpretable(ControlKind.HOLD, ControlNode.of(valid + ("axes" to JsonArray(listOf(JsonPrimitive("USER"))))))
        }
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("outcome"), "kind", JsonPrimitive("INDETERMINATE")))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("outcome"), "krxVisible", JsonPrimitive(true)))
    }

    @Test fun `hold floor enforces seconds milliseconds overflow presence and capture origin`() {
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("floor"), "waitMillis", JsonPrimitive(30)))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("floor"), "originLifetimeId", JsonPrimitive("new-life")))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("outcome"), "retryAfterSeconds", JsonPrimitive(Long.MAX_VALUE / 1000 + 1)))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("outcome"), "retryAfterSeconds", JsonNull))
        val maxSeconds = Long.MAX_VALUE / 1000
        val maxHold = hold.replace("\"retryAfterSeconds\":30", "\"retryAfterSeconds\":$maxSeconds").replace("\"waitMillis\":30000", "\"waitMillis\":${maxSeconds * 1000}")
        written(ControlObligations.build(ControlKind.HOLD) { literal(maxHold) })
    }

    @Test fun `provenance is scoped evidence and cannot satisfy live outcome API`() {
        val restored = (ControlObligations.read(ControlKind.HOLD, node(hold)) as ControlEntryRead.Interpreted).value as RestoredHold
        assertEquals(LifetimeId("life"), restored.originLifetimeId)
        assertEquals(EventOrderV1(LifetimeId("life"), 7), (restored.provenance as HoldProvenanceV1.Query).started.order)
        assertFalse(com.jay.fxi.data.entitlements.EntitlementsOutcome::class.java.isAssignableFrom(restored.outcome.javaClass))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("provenance", "started"), "binding", JsonPrimitive(99)))
        assertUninterpretable(ControlKind.HOLD, replace(hold, listOf("provenance", "answeredAs"), "authGeneration", JsonPrimitive(99)))
        assertUninterpretable(ControlKind.HOLD, replace(topicHold, listOf("provenance", "context", "access"), "ownerUid", JsonPrimitive("B")))
    }

    @Test fun `nullable query identities retain null and topic cannot carry pending body`() {
        val nullable = hold.replace("\"boundIdentity\":$identity", "\"boundIdentity\":null").replace("\"answeredAs\":$identity", "\"answeredAs\":null")
        written(ControlObligations.build(ControlKind.HOLD) { literal(nullable) })
        assertUninterpretable(ControlKind.HOLD, node(hold.replace(query, topic)))
    }

    @Test fun `enum lists do not narrow or deduplicate and counters do not coerce`() {
        listOf("[]", "[\"CAPABILITY\",\"CAPABILITY\"]", "[\"CAPABILITY\",\"FUTURE\"]", "[\"CAPABILITY\",1]").forEach {
            assertUninterpretable(ControlKind.HOLD, replace(hold, emptyList(), "axes", Json.parseToJsonElement(it)))
        }
        listOf("-1", "1.0", "1e0", "\"1\"", "9223372036854775808", "null").forEach {
            assertUninterpretable(ControlKind.DEMAND, replace(request, emptyList(), "raisedAt", Json.parseToJsonElement(it)))
        }
        written(ControlObligations.build(ControlKind.DEMAND) { literal(request); set("raisedAt", ControlScalar.Integer(Long.MAX_VALUE)) })
    }

    @Test fun `discriminators forbid wrong variant fields and future names`() {
        assertUninterpretable(ControlKind.DEMAND, replace(request, emptyList(), "kind", JsonPrimitive("FUTURE")))
        assertUninterpretable(ControlKind.DEMAND, replace(request, emptyList(), "floor", Json.parseToJsonElement(floor)))
        assertUninterpretable(ControlKind.DEMAND, replace(emptyGuard, emptyList(), "intent", JsonPrimitive("IF_STALE")))
        assertUninterpretable(ControlKind.HOLD, replace(topicHold, listOf("outcome"), "retryAfterSeconds", JsonNull))
        assertUninterpretable(ControlKind.HOLD, replace(topicHold, listOf("provenance"), "answeredAs", JsonNull))
    }

    /**
     * An edit whose result this build can no longer read is refused, at the root and one level down.
     *
     * Every other edit test changes a mutable field to something the schema still accepts, so the
     * final re-read always succeeded and a build that skipped it published the same thing.
     */
    @Test fun `an edit that makes its own result unreadable is refused`() {
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, node(request)) {
            set("intent", ControlScalar.Text("NOT_AN_INTENT"))
        })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, node(guard)) {
            descend("auth") { set("authStopped", ControlScalar.Text("true")) }
        })
    }

    /**
     * Three boundaries the tests above reach past. Each was found by a surviving mutant, and each
     * needed an input the existing case could not produce.
     */
    @Test fun `overflowing seconds collapsed order and wildcard journal owner are each refused`() {
        // 초→밀리 오버플로. Long.MAX/1000+1 로는 가드를 재지 못한다 — 그 값은 감긴 곱이 음수라
        // waitMillis 대조에서 **다른 이유로** 걸린다. 18446744073709552 * 1000 은 384 로 감기므로
        // waitMillis 384 를 짝지으면 가드만이 이 레코드를 거절한다.
        assertUninterpretable(
            ControlKind.HOLD,
            node(hold.replace("\"retryAfterSeconds\":30", "\"retryAfterSeconds\":18446744073709552")
                .replace("\"waitMillis\":30000", "\"waitMillis\":384")),
            "wrapped seconds"
        )

        // 정지 중인데 적용 order 가 사건 order 를 앞서지 않는다. 위 auth 시험은 편집만 보고
        // 레코드 자체의 이 불변식은 보지 않았다.
        assertUninterpretable(ControlKind.DEMAND, replace(guard, listOf("auth"), "authStopAppliedOrder", JsonPrimitive(8)), "applied equals state")
        assertUninterpretable(ControlKind.DEMAND, replace(guard, listOf("auth"), "authStopAppliedOrder", JsonPrimitive(7)), "applied before state")
        assertUninterpretable(ControlKind.DEMAND, replace(guard, listOf("auth"), "authStateOrder", JsonPrimitive(0)), "no stopping event")

        // journal 의 owner 가 **키는 있고 값이 JSON null** 인 것은 모든 주인을 덮는 wildcard 다
        // (키 자체가 없으면 스키마가 거절한다). 기존 정산 시험은 target owner 가 null 인 seal 만
        // 써서 wildcard 와 등치 비교가 같은 답을 내는 자리에 있었다.
        val wildcard = seal.dropLast(1).replace("\"ownerUid\":null", "\"ownerUid\":\"A\"") +
            ",\"settlement\":" + settlement.replace("\"ownerUid\":\"A\",\"axis\":\"USER\"", "\"ownerUid\":null,\"axis\":\"USER\"") + "}"
        assertTrue(
            "journal 의 owner 부재가 이 주인을 덮어야 한다",
            ControlObligations.read(ControlKind.SEAL, node(wildcard)) is ControlEntryRead.Interpreted
        )
    }

    private fun assertUninterpretable(kind: ControlKind, original: ControlNode, label: String = "") {
        assertTrue(label, ControlObligations.read(kind, original) is ControlEntryRead.Uninterpretable)
        var calls = 0
        assertEquals(label, ControlWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION), ControlObligations.editExisting(kind, original) { calls++ })
        assertEquals(label, 0, calls)
    }
}
