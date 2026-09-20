package com.jay.fxi.data.entitlements.controllongrun

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/** Capacity cases stay outside control.* discovery. Each profile is an independent JUnit method. */
class DemandAuthEnvelopeLongRunTest {
    private val codec = ControlPayloadCodec()
    private val lax = ControlPayloadCodec(maxPayloadBytes = 100000, maxDepth = 70)
    private fun demand(bytes: Int, token: String, id: String) {
        val short = "[${F.request(id = token).toPayloadEntry().fields}]"
        val raw = "[${F.request(id = token + "x".repeat(bytes - short.toByteArray(Charsets.UTF_8).size)).toPayloadEntry().fields}]"
        assertEquals(bytes, raw.toByteArray(Charsets.UTF_8).size)
        val rows = (lax.decode(raw) as PayloadRead.Parsed).entries
        assertNotNull(ControlSchema.read(ControlKind.DEMAND, ControlNode.of((rows.single() as PayloadEntry.Obj).fields)))
        val result = NamespaceSettlementTransition(codec).encodeChanged(ControlKind.DEMAND, rows)
        assertTrue(F.atomic(id), if (bytes == 65536) result is NamespaceSettlementTransition.ChangedPayload.Encoded
            else result is NamespaceSettlementTransition.ChangedPayload.Rejected && result.reason is RejectionReason.TooLarge)
    }
    private fun row(command: String) = AppliedEvidence.Lifecycle(command, "11111111-1111-4111-8111-111111111111",
        LifecycleTransition.REBIND_REQUESTS, listOf(LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)))
    private fun evidence(bytes: Int, token: String, id: String) {
        val short = "[${ControlAppliedEvidence.node(row(token))}]"
        val row = row(token + "x".repeat(bytes - short.toByteArray(Charsets.UTF_8).size))
        val text = "[${ControlAppliedEvidence.node(row)}]"
        assertEquals(bytes, text.toByteArray(Charsets.UTF_8).size)
        assertFalse(ControlEvidenceReader.read(lax.decode(text) as PayloadRead.Parsed).hasUninterpretable)
        val before = F.read(F.raw()); val candidate = before.original.toMutablePreferences()
        val rejection = ControlAppliedEvidence.append(candidate, before, row, codec)
        assertTrue(F.atomic(id), if (bytes == 65536) rejection == null else rejection is RejectionReason.TooLarge && candidate == before.original)
    }
    private fun deep(depth: Int) = "[" + "[".repeat(depth - 2) + "0" + "]".repeat(depth - 2) + "]"
    private fun demandDepth(depth: Int, id: String) {
        val entries = (lax.decode(deep(depth)) as PayloadRead.Parsed).entries
        // Direct envelope boundary: these opaque nested values cannot enter a named writer as valid REQUESTs.
        val result = NamespaceSettlementTransition(codec).encodeChanged(ControlKind.DEMAND, entries)
        assertTrue(F.atomic(id), if (depth == 64) result is NamespaceSettlementTransition.ChangedPayload.Encoded
            else result is NamespaceSettlementTransition.ChangedPayload.Rejected)
    }
    private fun evidenceDepth(depth: Int, id: String) {
        val raw = F.raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = deep(depth) }
        val read = ControlRecordReader(lax).read(raw) as ControlRecordRead.Supported
        assertTrue(read.hasUninterpretableMetadata)
        val candidate = raw.toMutablePreferences()
        val rejection = ControlAppliedEvidence.append(candidate, read, row("new"), codec)
        assertTrue(F.atomic(id), if (depth == 64) rejection == null else rejection is RejectionReason.InvalidRequest && candidate == raw)
    }
    private fun writerCapacity(writer: String, bytes: Int) {
        val r = F.request(binding = if (writer == "REBIND" || writer == "END") 2 else 3)
        val g = F.guard(if (writer == "END") F.auth.copy(binding = 2, authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0) else F.auth)
        val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, emptySet(), emptySet(), 5)
        val orders = LifecycleOrderSource(F.life, 21)
        val plan = when (writer) {
            "REBIND" -> DemandAuthPlan.rebind(listOf(r), F.binding, orders)
            "SETTLE" -> DemandAuthPlan.settle(listOf(r), g, null, F.binding,
                F.decision(followUp = RefreshIntent.FORCE_PREMIUM, minDelay = 1000), orders, "g", "successor")
            "AUTH" -> DemandAuthPlan.auth(g, null, F.binding,
                LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30))), orders, "g", "retry")
            else -> DemandAuthPlan.end(g, listOf(r), F.binding, closed, F.binding, orders)
        }
        val nodes = when (writer) { "REBIND" -> listOf(r); "AUTH" -> listOf(g); else -> listOf(r, g) }
        val runtime = F.runtime(closure = closed)
        val filler = F.request(id = "padding", owner = "B")
        val (_, small) = F.apply(plan, F.raw(*(nodes + filler).toTypedArray()), runtime)
        val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
        val size = small[demandKey]!!.toByteArray(Charsets.UTF_8).size
        val padded = F.request(id = "padding" + "x".repeat(bytes - size), owner = "B")
        val source = F.raw(*(nodes + padded).toTypedArray())
        F.schema(source)
        val command = F.command(plan)
        val input = (command.body as ControlCommandBody.Lifecycle).input
        val decision = ControlLifecycleConfirmation(codec).decide(command, input, F.read(source), F.context(runtime), false, false)
        if (bytes == 65536) {
            assertTrue(decision is RecordTransactionDecision.Confirm)
            assertEquals(bytes, (decision as RecordTransactionDecision.Confirm).candidate[demandKey]!!.toByteArray(Charsets.UTF_8).size)
        } else {
            assertTrue(decision is RecordTransactionDecision.Observe)
            val result = (decision.value as ControlRecordStore.Outcome.Negative).result
            assertTrue(result is ControlStoreResult.Rejected && result.reason is RejectionReason.TooLarge)
            assertEquals("[]", source[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)])
        }
    }
    @Test fun rebindAtLimit() = writerCapacity("REBIND", 65536)
    @Test fun rebindOverLimit() = writerCapacity("REBIND", 65537)
    @Test fun settleAtLimit() = writerCapacity("SETTLE", 65536)
    @Test fun settleOverLimit() = writerCapacity("SETTLE", 65537)
    @Test fun authAtLimit() = writerCapacity("AUTH", 65536)
    @Test fun authOverLimit() = writerCapacity("AUTH", 65537)
    @Test fun endAtLimit() = writerCapacity("END", 65536)
    @Test fun endOverLimit() = writerCapacity("END", 65537)
    @Test fun C12_demand_asciiAt() = demand(65536, "ascii", "C12.demand.asciiAt")
    @Test fun C12_demand_asciiOver() = demand(65537, "ascii", "C12.demand.asciiOver")
    @Test fun C12_demand_unicodeAt() = demand(65536, "한글😀", "C12.demand.unicodeAt")
    @Test fun C12_demand_unicodeOver() = demand(65537, "한글😀", "C12.demand.unicodeOver")
    @Test fun C12_demand_escapeAt() = demand(65536, "\"\\\n", "C12.demand.escapeAt")
    @Test fun C12_demand_escapeOver() = demand(65537, "\"\\\n", "C12.demand.escapeOver")
    @Test fun C12_demand_longIdAt() = demand(65536, "long-id".repeat(1000), "C12.demand.longIdAt")
    @Test fun C12_demand_longIdOver() = demand(65537, "long-id".repeat(1000), "C12.demand.longIdOver")
    @Test fun C12_evidence_asciiAt() = evidence(65536, "ascii", "C12.evidence.asciiAt")
    @Test fun C12_evidence_asciiOver() = evidence(65537, "ascii", "C12.evidence.asciiOver")
    @Test fun C12_evidence_unicodeAt() = evidence(65536, "한글😀", "C12.evidence.unicodeAt")
    @Test fun C12_evidence_unicodeOver() = evidence(65537, "한글😀", "C12.evidence.unicodeOver")
    @Test fun C12_evidence_escapeAt() = evidence(65536, "\"\\\n", "C12.evidence.escapeAt")
    @Test fun C12_evidence_escapeOver() = evidence(65537, "\"\\\n", "C12.evidence.escapeOver")
    @Test fun C12_evidence_longIdAt() = evidence(65536, "long-id".repeat(1000), "C12.evidence.longIdAt")
    @Test fun C12_evidence_longIdOver() = evidence(65537, "long-id".repeat(1000), "C12.evidence.longIdOver")
    @Test fun C12_demand_depth64() = demandDepth(64, "C12.demand.depth64")
    @Test fun C12_demand_depth65() = demandDepth(65, "C12.demand.depth65")
    @Test fun C12_evidence_depth64() = evidenceDepth(64, "C12.evidence.depth64")
    @Test fun C12_evidence_depth65() = evidenceDepth(65, "C12.evidence.depth65")
}
