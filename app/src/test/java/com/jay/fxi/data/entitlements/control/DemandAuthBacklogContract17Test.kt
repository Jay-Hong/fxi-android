package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, seventeenth file: a rejected, conflicted or envelope-rejected DemandAuth command is reported for the
 * command that was submitted. The store returns the result of execute(); its command, and a conflict's expected
 * command, must be the submitted CommandRef (retry observation — the caller matches results to its own commands).
 * Every fixture reaches the named DemandAuthTransition rejection through ControlRecordStore.execute with commands
 * prepared by the facade. Envelope limits come from the fixture's original payload size, never from a candidate.
 */
class DemandAuthBacklogContract17Test {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { controlTestTimeout("5e-1 cleanup", 30000) { opened.reversed().forEach { it.close() } } }

    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private class Ids { var issued = 0; val generator = ControlIdGenerator { UUID(0, (++issued).toLong()) } }
    private fun facade(source: Preferences, codec: ControlPayloadCodec = F.codec): ControlRecordStore = runBlocking {
        val store = ControlStoreTestStorage(folder.newFile()).also { opened += it }
        controlTestTimeout("seed 5e-1") { store.data.updateData { source } }
        ControlRecordStore(store.owner, Ids().generator, codec)
    }
    private fun execute(facade: ControlRecordStore, c: CommandRef, rt: DemandAuthRuntime): ControlStoreResult =
        runBlocking { controlTestTimeout("5e-1 facade") { facade.execute(c, F.context(rt)) } }
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.demandAuth)
    private fun twin(c: CommandRef, source: Preferences) = ControlLifecycleConfirmation(F.codec).decide(
        c, (c.body as ControlCommandBody.Lifecycle).input, F.read(source), F.context(), false, false)
    private fun bytes(raw: Preferences, key: Preferences.Key<String>) = raw[key]!!.toByteArray(Charsets.UTF_8).size

    // R.rejected — DemandAuthTransition.kt:18–19 via DT:35. The query registration is absent from the runtime, so
    // among eligibility facts only q.registered is false; writer gates and common premises hold.

    @Test fun R_rejected_command() {
        val g = F.guard(); val r = F.request(); val source = F.raw(g, r)
        F.schema(source)
        val facade = facade(source)
        val c = facade.prepareUpdateAuth(g, r, F.binding, LifecycleAuthEvent.Answer(F.decision()), orders)
        val p = plan(c)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val rt = F.runtime(registrations = emptyList())
        assertEquals("fixture: writer gates", emptySet<String>(), V.falses(V.decideGates(p, F.context(rt), source)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, source, c.id)))
        assertEquals("fixture: only the registration is missing", setOf("q.registered"), V.falses(V.eligibility(p, rt, source)))
        assertTrue("positive twin: the registered query confirms", twin(c, source) is RecordTransactionDecision.Confirm)
        val result = execute(facade, c, rt)
        assertEquals(F.retry("R.rejected.command"), c, result.command)
    }

    // R.conflict — DemandAuthTransition.kt:20–22 via DT:27. The C7 RC_conflict_expectedIds fixture through the store:
    // the attempt context matches the executor and only the runtime binding differs among the writer gates.

    @Test fun R_conflict_expectedCommand() {
        val g = F.guard(); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS); val source = F.raw(g, weak)
        F.schema(source)
        val facade = facade(source)
        val c = facade.prepareUpdateAuth(g, weak, F.binding,
            LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30))), orders)
        val p = plan(c)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertTrue("positive twin: the current binding confirms", twin(c, source) is RecordTransactionDecision.Confirm)
        val moved = F.runtime(binding = F.binding.copy(identity = IdentityV1("A", 3)))
        assertEquals("fixture: the attempt context still matches the executor", F.context().ownerUid, F.context(moved).ownerUid)
        assertEquals("fixture: only the runtime binding differs among the writer gates", setOf("dt.runtimeBinding"),
            V.falses(V.decideGates(p, F.context(moved), source)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, source, c.id)))
        val result = execute(facade, c, moved)
        assertEquals(F.retry("R.conflict.expectedCommand"), c, (result as? ControlStoreResult.Conflict)?.expected?.command)
    }

    // R.encode — DemandAuthTransition.kt:38–40. Initialization adds one guard row to a padded DEMAND payload; the store's
    // limit is the original DEMAND size plus one byte, so the change crosses it before evidence is appended (DT:43).

    @Test fun R_encode_command() {
        val pads = (1..12).map { F.request(id = "pad-$it", owner = "B", binding = 1) }
        val source = F.raw(*pads.toTypedArray())
        F.schema(source)
        val limit = bytes(source, demandKey) + 1
        val facade = facade(source, ControlPayloadCodec(maxPayloadBytes = limit))
        val c = facade.prepareUpdateAuth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders)
        val p = plan(c)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: the change creates one guard row", listOf(LifecycleRole.GUARD to LifecycleEffect.CREATE),
            p.targets.map { it.role to it.target.effect })
        assertTrue("fixture: the original payloads fit the limit", source.asMap().values.filterIsInstance<String>().all { it.toByteArray(Charsets.UTF_8).size <= limit })
        assertEquals("fixture: writer gates", emptySet<String>(), V.falses(V.decideGates(p, F.context(), source)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, source, c.id)))
        assertEquals("fixture: eligibility", emptySet<String>(), V.falses(V.eligibility(p, F.runtime(), source)))
        assertTrue("positive twin: the default envelope confirms", twin(c, source) is RecordTransactionDecision.Confirm)
        val result = execute(facade, c, F.runtime())
        assertEquals(F.retry("R.encode.command"), c, result.command)
    }

    // R.evidence — DemandAuthTransition.kt:43–44. The C2 V_envelopes_evidence rows fill the evidence payload; the
    // store's limit equals its original size, so appending the self Applied row crosses it while DEMAND stays small.

    @Test fun R_evidence_command() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val source = F.raw().toMutablePreferences().apply { this[evidenceKey] = JsonArray(rows).toString() }.toPreferences()
        F.schema(source)
        val limit = bytes(source, evidenceKey)
        val facade = facade(source, ControlPayloadCodec(maxPayloadBytes = limit))
        val c = facade.prepareUpdateAuth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders)
        val p = plan(c)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertTrue("fixture: the original payloads fit the limit", source.asMap().values.filterIsInstance<String>().all { it.toByteArray(Charsets.UTF_8).size <= limit })
        val reference = twin(c, source)
        assertTrue("positive twin: the default envelope confirms", reference is RecordTransactionDecision.Confirm)
        assertTrue("fixture: the changed DEMAND payload stays within the limit",
            bytes((reference as RecordTransactionDecision.Confirm<*>).candidate, demandKey) <= limit)
        val result = execute(facade, c, F.runtime())
        assertEquals(F.retry("R.evidence.command"), c, result.command)
    }
}
