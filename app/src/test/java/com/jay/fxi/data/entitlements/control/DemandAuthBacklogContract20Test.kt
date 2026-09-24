package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twentieth file: CLASSIFICATION_ONLY observations (§9.1 543–545). Each test asserts its boundary
 * (the rejection is kept) with an eligible role first, then compares the result kind and reason with labelled
 * "classification:" messages. A reason-only difference is never counted as a safety KILL.
 * Expected kinds and reasons are fixed constants: result types, failure enums, the preparation string, and the
 * envelope limit and byte count of the C15 W_encode fixture wire (1600 / 1664).
 */
class DemandAuthBacklogContract20Test {
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun decide(plan: DemandAuthPlan, before: Preferences, codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(plan)
        val read = ControlRecordReader(codec).read(before) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, read, F.context(), false, false)
    }
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun noFalseSuccess(d: RecordTransactionDecision<*>) =
        d is RecordTransactionDecision.Confirm || d.value is ControlRecordStore.Outcome.Positive

    // W.journal — DemandAuthTransition.kt:33–34. The C15 W_journal fixture: only the journal is malformed.

    @Test fun W_journal_class() {
        val g = F.guard(auth = null)
        val broken = F.raw(g).toMutablePreferences().apply { this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "unknown-format" }.toPreferences()
        F.schema(broken)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life), "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val d = decide(p, broken)
        assertFalse(F.eligible("W.journal.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: a malformed journal requires recovery", result is ControlStoreResult.RecoveryRequired)
        assertEquals("classification: journal recovery reason", RecoveryReason.JournalMigrationRequired,
            (result as ControlStoreResult.RecoveryRequired).reason)
    }

    // W.encode — DemandAuthTransition.kt:38–40. The C15 W_encode fixture: DEMAND crosses 1600 bytes only after the change.

    @Test fun W_encode_class() {
        val pads = (1..12).map { F.request(id = "pad-$it", owner = "B", binding = 1) }
        val before = F.raw(*pads.toTypedArray())
        F.schema(before)
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: original DEMAND bytes", 1480, before[demandKey]!!.toByteArray(Charsets.UTF_8).size)
        val d = decide(p, before, ControlPayloadCodec(maxPayloadBytes = 1600))
        assertFalse(F.eligible("W.encode.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: an oversized change is Rejected", result is ControlStoreResult.Rejected)
        assertEquals("classification: encode reason", RejectionReason.TooLarge(ControlPayloadKey.forKind(ControlKind.DEMAND), 1664, 1600),
            (result as ControlStoreResult.Rejected).reason)
    }

    // P.endRebind — DemandAuthPlan.kt:129. The C5 LC_prepPair fixture: END refuses to re-bind a current REQUEST.

    @Test fun P_endRebind_class() {
        val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
        val g = F.guard(auth = oldAuth); val current = F.request()
        val p = DemandAuthPlan.end(g, listOf(current), F.binding, LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5), F.binding, orders)
        assertNotNull(F.eligible("P.endRebind.class"), p.preparationFailure)
        assertEquals("classification: END re-bind failure", "InvalidRebind", p.preparationFailure)
    }

    // X.addApply — ControlCommand.kt:20–24. A generic SEAL add carrying a settlement is refused.

    @Test fun X_addApply_class() {
        val plain = ControlMutation.Add.prepare(ControlKind.SEAL, UUID(0, 1)) { id -> literal(ControlObligationFixtures.nullSeal); set("id", ControlScalar.Text(id)) }
        assertTrue("positive twin: a SEAL without settlement is written", plain.built is ControlWriteResult.Written)
        val settled = ControlMutation.Add.prepare(ControlKind.SEAL, UUID(0, 2)) { id -> literal(ControlObligationFixtures.settledSeal); set("id", ControlScalar.Text(id)) }
        assertFalse(F.eligible("X.addApply.class"), settled.built is ControlWriteResult.Written)
        assertEquals("classification: a refused add is an invalid change", ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), settled.built)
    }

    // X.editApply — ControlCommand.kt:38–41. A generic SEAL edit that adds a settlement is refused.

    @Test fun X_editApply_class() {
        val preimage = ControlObligationFixtures.node(ControlObligationFixtures.nullSeal)
        val pure = ControlObligations.editExisting(ControlKind.SEAL, preimage) { createChild("settlement") { literal(ControlObligationFixtures.settlement) } }
        assertTrue("positive twin: the pure editor writes the settlement", pure is ControlWriteResult.Written)
        val action = ControlMutation.Edit.prepare(ControlKind.SEAL, preimage) { createChild("settlement") { literal(ControlObligationFixtures.settlement) } }
        assertFalse(F.eligible("X.editApply.class"), action.changed is ControlWriteResult.Written)
        assertEquals("classification: a refused edit is an invalid change", ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), action.changed)
    }

    // X.floorApply — ControlObligations.kt:124–126. recordFloor on an uninterpretable DEMAND and on a non-guard.

    @Test fun X_floorApply_uninterpretable() {
        val future = ControlObligationFixtures.node(ControlObligationFixtures.guard.dropLast(1) + ",\"future\":1}")
        assertNull("fixture: the original is uninterpretable", ControlSchema.read(ControlKind.DEMAND, future))
        val result = ControlObligations.recordFloor(future, BootReading("boot", 20000), 1, F.life)
        assertFalse(F.eligible("X.floorApply.uninterpretable"), result is ControlWriteResult.Written)
        assertEquals("classification: an uninterpretable original", ControlWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION), result)
    }

    @Test fun X_floorApply_invalid() {
        val request = ControlObligationFixtures.node(ControlObligationFixtures.request)
        assertTrue("fixture: an interpretable non-guard", ControlSchema.read(ControlKind.DEMAND, request) is DemandV1)
        val result = ControlObligations.recordFloor(request, BootReading("boot", 20000), 1, F.life)
        assertFalse(F.eligible("X.floorApply.invalid"), result is ControlWriteResult.Written)
        assertEquals("classification: a non-guard is an invalid change", ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), result)
    }
}
