package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.evidenceKey
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P3-i-1b contract (purger 설계 v3 final 개정 01 "물리 제거·압축은 과거 명령의 재적용 방지와 필요한 확인 증거
 * 보존을 보장하는 별도 계약을 따른다"; P3i1b/design_codex.r1.md). After a real restart, the automatic reclamation of a
 * previous lifetime's evidence refuses the whole candidate — nothing written, no partial removal — while a command of the
 * current tracker depends, or may depend, on a row or seal it would remove, as the explicit R/N/L reclamation already does.
 * An unrelated current command does not block it. The dependency check never creates a removal the witness check refused.
 * The implementation reads but does not edit this file.
 */
class ReclaimDependencyContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "reclaim-dependency.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(BARRIER) }.toPreferences()
    private fun first(raw: String?) = node((Json.parseToJsonElement(checkNotNull(raw)) as JsonArray).first().toString())

    /** A previous lifetime's confirmed rotation bundle (Applied row + settled seal), reopened with a new tracker. */
    private suspend fun restartedWithBundle(): ControlStoreTestStorage {
        val o = open()
        o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = o.control.prepareRotation(listOf(node(NamespaceSettlementFixtures.user)), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
        check(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        check(disk()[evidenceKey] != "[]" && disk()[SEAL]?.contains("\"settlement\"") == true) { "fixture: applied bundle" }
        return open()
    }
    private fun blocked(id: String, r: ControlEvidenceReclamationResult, present: Boolean) =
        assertTrue("$id: DependencyBlocked(present=$present), got $r",
            r is ControlEvidenceReclamationResult.DependencyBlocked && r.present == present)

    @Test fun B01_withNoDependentCommand_theBundleIsReclaimed() = runBlocking {
        val next = restartedWithBundle()
        assertTrue("B01", next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("B01: seal gone", "[]", disk()[SEAL])
        assertEquals("B01: row gone", "[]", disk()[evidenceKey])
    }

    @Test fun B02_aCurrentCommandOnTheSettledSeal_blocksTheWholeReclamation() = runBlocking {
        val next = restartedWithBundle(); val before = withoutBarrier(disk())
        next.control.prepare(next.control.edit(ControlKind.SEAL, first(disk()[SEAL])) {})
        blocked("B02", next.control.reclaimPreviousLifetimeEvidence(), present = true)
        assertEquals("B02: nothing written", before, withoutBarrier(disk()))
        blocked("B02 again", next.control.reclaimPreviousLifetimeEvidence(), present = true)
    }

    @Test fun B03_aCurrentRotationWhoseTargetsAreUnknown_blocksIt() = runBlocking {
        val next = restartedWithBundle(); val before = withoutBarrier(disk())
        next.control.prepareRotation(emptyList(), NamespaceSettlementFixtures.fence, NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
        blocked("B03", next.control.reclaimPreviousLifetimeEvidence(), present = false)
        assertEquals("B03: nothing written", before, withoutBarrier(disk()))
    }

    @Test fun B04_anUnrelatedCurrentCommand_doesNotBlockIt() = runBlocking {
        val next = restartedWithBundle()
        next.control.prepare(next.control.edit(ControlKind.DEMAND, first(disk()[DEMAND])) {})
        assertTrue("B04", next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("B04: seal gone", "[]", disk()[SEAL])
    }

    @Test fun B05_aBlockedRotation_keepsThePreviousMutationRowToo() = runBlocking {
        val next = restartedWithBundle()
        val rotationRow = disk()[evidenceKey]!!.trim().removePrefix("[").removeSuffix("]")
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[evidenceKey] = "[$rotationRow,${ReclamationFixtures.mutation()}]" }.toPreferences() }
        val before = withoutBarrier(disk())
        next.control.prepare(next.control.edit(ControlKind.SEAL, first(disk()[SEAL])) {})
        blocked("B05", next.control.reclaimPreviousLifetimeEvidence(), present = true)
        assertEquals("B05: no partial removal", before, withoutBarrier(disk()))
    }

    @Test fun B06_theDependencyCheckDoesNotRescueAContradictoryWitness() = runBlocking {
        val next = restartedWithBundle()
        next.data.updateData { p -> p.toMutablePreferences().apply {
            this[SEAL] = p[SEAL]!!.replace("\"BEGIN_ROTATION\"", "\"SIGN_OUT\"") }.toPreferences() }
        val r = next.control.reclaimPreviousLifetimeEvidence()
        assertTrue("B06: not reclaimed, got $r", r !is ControlEvidenceReclamationResult.Confirmed || disk()[SEAL] != "[]")
        assertTrue("B06: seal kept", disk()[SEAL]!!.contains("\"settlement\""))
    }
}
