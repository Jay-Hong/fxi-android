package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, sixty-sixth file — the vector reinforcements STEP3ZK r9 required:
 *  - Q16b/Q16c at the store (C54 Q16b_store/Q16c_store): the full writer premises (V.decideGates · V.eligibility ·
 *    V.commonPremises) of the twin plan hold on the stored record, and the negative stores the same record — the only
 *    differing input is the order source (exhaustion, written here: lower = max(last, start, after) == MAX). Role: the store
 *    refuses and the stored record is unchanged.
 *  - A09 Edit.prepare (C55 A09_edit): the generic Edit.prepare vector (pure edit written · SEAL non-settlement · AUTH
 *    literal preserved) over literal nodes; the pure editor's outputs are asserted equal to those literals as premises.
 */
class DemandAuthBacklogContract66Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c66 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c66 cleanup", 30000) { opened.forEach { it.close() } } }
    private val max = Long.MAX_VALUE
    private fun writerFalses(c: CommandRef, raw: Preferences, ctx: AttemptContext): Set<String> {
        val p = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.demandAuth)
        assertNull("twin plan prepared", p.preparationFailure)
        return V.falses(V.decideGates(p, ctx, raw)) + V.falses(V.eligibility(p, ctx.demandAuth!!, raw)) + V.falses(V.commonPremises(p, raw, c.id))
    }
    private fun issueVec(last: Long, start: Long, after: Long) = listOf("notExhausted" to (maxOf(last, start, after) != max))

    @Test fun Q16b_storeVector() = runReleaseTest {
        val old = F.request(binding = 2, order = 50); val raw = F.raw(old); val ctx = F.context(F.runtime())
        val a = store(raw)
        val twin = a.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, 21))
        assertEquals("truth vector: twin writer premises on the stored record", emptySet<String>(), writerFalses(twin, raw, ctx))
        assertEquals("truth vector: twin source issuable", emptySet<String>(), V.falses(issueVec(21, 1, 50)))
        val ok = a.control.execute(twin, ctx)
        assertTrue("positive twin: the fresh source re-binds through the store, got $ok", ok is ControlStoreResult.Confirmed)
        val o = store(raw); val saved = o.raw()
        assertEquals("fixture: the negative stores the same record", raw, saved.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences())
        assertEquals("truth vector: only the source differs (exhausted)", setOf("notExhausted"), V.falses(issueVec(max, 1, 50)))
        val r = o.control.execute(o.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, max)), ctx)
        assertTrue(F.eligible("Z.st.Q16b.vector"), r !is ControlStoreResult.Confirmed && o.raw() == saved)
    }

    @Test fun Q16c_storeVector() = runReleaseTest {
        val g = F.guard(F.auth.copy(authStopped = false)); val req = F.request(); val raw = F.raw(g, req); val ctx = F.context()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        val a = store(raw)
        val twin = a.control.prepareSettleQuery(listOf(req), g, null, F.binding, d, LifecycleOrderSource(F.life, 21))
        assertEquals("truth vector: twin writer premises on the stored record", emptySet<String>(), writerFalses(twin, raw, ctx))
        assertEquals("truth vector: twin source issuable", emptySet<String>(), V.falses(issueVec(21, 1, 21)))
        val ok = a.control.execute(twin, ctx)
        assertTrue("positive twin: the settle with a fresh source is Confirmed, got $ok", ok is ControlStoreResult.Confirmed)
        val o = store(raw); val saved = o.raw()
        assertEquals("fixture: the negative stores the same record", raw, saved.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences())
        assertEquals("truth vector: only the source differs (exhausted)", setOf("notExhausted"), V.falses(issueVec(max, 1, 21)))
        val r = o.control.execute(o.control.prepareSettleQuery(listOf(req), g, null, F.binding, d, LifecycleOrderSource(F.life, max)), ctx)
        assertTrue(F.eligible("Z.st.Q16c.vector"), r !is ControlStoreResult.Confirmed && o.raw() == saved)
    }

    // A09 Edit.prepare over literal nodes
    private fun editVec(kind: ControlKind, before: ControlNode, after: ControlNode?) = listOf(
        "written" to (after != null),
        "nonSettlement" to (kind != ControlKind.SEAL || (after != null && before.toPayloadEntry() == after.toPayloadEntry())),
        "authPreserved" to (after == null || kind != ControlKind.DEMAND || ControlSchema.read(kind, before) !is ScheduleGuardV1 ||
            before.toPayloadEntry().fields["auth"]?.toString() == after.toPayloadEntry().fields["auth"]?.toString()))
    @Test fun A09_editVector() {
        val g = F.guard()
        val emptyEdit: ControlEditor.() -> Unit = {}
        val change: ControlEditor.() -> Unit = { descend("auth") { set("authStopped", ControlScalar.Flag(false)); set("authStateOrder", ControlScalar.Integer(21)) } }
        val changedLiteral = F.guard(F.auth.copy(authStopped = false, authStateOrder = 21))
        assertEquals("premise: the pure empty edit writes the preimage", g.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.DEMAND, g, emptyEdit) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("premise: the pure change writes the resumed AUTH literal", changedLiteral.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.DEMAND, g, change) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(editVec(ControlKind.DEMAND, g, g)))
        assertTrue("positive twin: the AUTH-preserving generic edit is written", ControlMutation.Edit.prepare(ControlKind.DEMAND, g, emptyEdit).changed is ControlWriteResult.Written)
        assertEquals("truth vector: only the AUTH literal differs", setOf("authPreserved"), V.falses(editVec(ControlKind.DEMAND, g, changedLiteral)))
        assertFalse(F.eligible("Z.gen.A09.editVector"), ControlMutation.Edit.prepare(ControlKind.DEMAND, g, change).changed is ControlWriteResult.Written)
    }
}
