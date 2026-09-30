package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.evidenceKey
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P3-i-1d contract (purger 설계 v3 final 개정 01 P3 활성화 차단 조건: 한도 근처 복구 진행, 추정 수용량 초과
 * 반복 회전·미확정 명령·재시작 결합; P3i1d/design_codex.r1.md, narrowed: the reclamation's store-failure rows are the
 * existing ControlReclamationLifecycleTest). Real FileStorage and the real owner transaction. Pass criteria are the
 * stored payloads' actual UTF-8 bytes and the transaction results, not the revision's estimates (84/83/41 are compact
 * JSON arithmetic): C01 goes past 41 both-axis rotations, not past the single-axis 84/83. A Confirmed result is storage
 * evidence only, never admission. Characterization over production code that is not changed here.
 */
class ControlCapacityCombinedContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private fun open(file: File): ControlStoreTestStorage = ControlStoreTestStorage(file).also { opened += it }
    private suspend fun reopen(old: ControlStoreTestStorage, file: File): ControlStoreTestStorage {
        old.close(); opened.remove(old)
        return ControlStoreTestStorage(file).also { opened += it }
    }
    private suspend fun read(file: File): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(BARRIER) }.toPreferences()
    private fun bytes(p: Preferences, key: Preferences.Key<String>) = checkNotNull(p[key]).toByteArray(Charsets.UTF_8).size
    private fun rows(p: Preferences) = Json.parseToJsonElement(checkNotNull(p[evidenceKey])).jsonArray
    private fun seals(p: Preferences) = Json.parseToJsonElement(checkNotNull(p[SEAL])).jsonArray
    private fun settled(p: Preferences) = seals(p).count { it.jsonObject.containsKey("settlement") }
    private fun seal(id: String, axis: String, epoch: String) =
        """{"id":"$id","kind":"NAMESPACE","ownerUid":"A","axis":"$axis","epoch":"$epoch"}"""
    private fun fence(p: Preferences) = FenceV1("A", p[DataStoreAccessEpochStore.USER_EPOCH], p[DataStoreAccessEpochStore.KRX_EPOCH])
    private val life = NamespaceSettlementFixtures.life
    private val demand = NamespaceSettlementFixtures.demand
    private val context = NamespaceSettlementFixtures.context
    private val limit = 65_536

    private suspend fun seed(o: ControlStoreTestStorage, seals: String = "[]") = o.data.updateData {
        NamespaceSettlementFixtures.raw(seals).toMutablePreferences().apply {
            this[DataStoreAccessEpochStore.OWNER_UID] = "A"
            this[DataStoreAccessEpochStore.USER_EPOCH] = "u"
            this[DataStoreAccessEpochStore.KRX_EPOCH] = "k"
        }.toPreferences()
    }
    /** One confirmed both-axis rotation of the current epochs, left unconsumed. */
    private suspend fun rotateBoth(o: ControlStoreTestStorage, file: File, index: Int) {
        val f = fence(read(file))
        val targets = listOf(seal("u-$index", "USER", f.userAccessEpoch!!), seal("k-$index", "CAPABILITY", f.krxCapabilityEpoch!!))
        o.data.edit { p -> p[SEAL] = JsonArray(seals(p.toPreferences()) + targets.map { Json.parseToJsonElement(it) }).toString() }
        val c = o.control.prepareRotation(targets.map { node(it) }, f, life, demand)
        val r = o.control.execute(c, context)
        assertTrue("rotation $index Confirmed: $r", r is ControlStoreResult.Confirmed)
    }
    private fun tooLarge(id: String, r: ControlStoreResult, expectedBytes: Int? = null) {
        val reason = (r as? ControlStoreResult.Rejected)?.reason as? RejectionReason.TooLarge
        assertTrue("$id: Rejected(TooLarge(seal_v1, limit $limit)), got $r",
            reason != null && reason.payloadKey.wireName == "seal_v1" && reason.limit == limit)
        if (expectedBytes != null) assertEquals("$id: bytes", expectedBytes, reason!!.bytes)
    }

    @Test fun C01_pastFortyOneBothAxisRotations_nearTheLimit_acrossARestart_reclaimsOnlyThePreviousBundles() = runBlocking {
        val file = File(folder.root, "c01.preferences_pb")
        var o = open(file); seed(o)
        repeat(42) { rotateBoth(o, file, it) }
        val accumulated = read(file)
        assertEquals("C01: 42 applied rows", 42, rows(accumulated).size)
        assertEquals("C01: 84 settled seals", 84, settled(accumulated))
        for (key in listOf(SEAL, DEMAND, evidenceKey)) assertTrue("C01: $key under the limit", bytes(accumulated, key) < limit)

        val live = fence(accumulated).userAccessEpoch!!
        val base = bytes(o.data.edit { p -> p[SEAL] = JsonArray(seals(p.toPreferences()) + Json.parseToJsonElement(seal("s", "USER", live))).toString() }, SEAL)
        val pad = 65_300 - base
        assertTrue("C01: room to pad ($base)", pad > 0)
        val liveSeal = seal("s" + "x".repeat(pad), "USER", live)
        o.data.edit { p -> p[SEAL] = JsonArray(seals(accumulated) + Json.parseToJsonElement(liveSeal)).toString() }
        assertEquals("C01: seal payload at 65,300 B", 65_300, bytes(read(file), SEAL))

        o = reopen(o, file)
        val intent = o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { issued ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(issued))
        })
        o.storage.afterScope = true
        assertTrue("C01: the intent landed but was not confirmed", o.control.execute(intent) is ControlStoreResult.Unconfirmed)

        val rotation = o.control.prepareRotation(listOf(node(liveSeal)), fence(read(file)), life, demand)
        val before = withoutBarrier(read(file))
        tooLarge("C01 first", o.control.execute(rotation, context))
        assertEquals("C01: nothing written by the refused rotation", before, withoutBarrier(read(file)))

        assertTrue("C01: reclaimed", o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        val reclaimed = read(file)
        assertEquals("C01: only the live seal remains", listOf(liveSeal), seals(reclaimed).map { it.toString() })
        assertEquals("C01: only the current intent row remains", listOf(intent.id), rows(reclaimed).map { it.jsonObject.getValue("commandId").jsonPrimitive.content })
        assertEquals("C01: requests kept", before[DEMAND], reclaimed[DEMAND])
        assertEquals("C01: journal kept", before[DataStoreAccessEpochStore.PURGE_JOURNAL], reclaimed[DataStoreAccessEpochStore.PURGE_JOURNAL])

        assertTrue("C01: the same prepared rotation now fits", o.control.execute(rotation, context) is ControlStoreResult.Confirmed)
        val done = o.control.completeAfterConsumption(rotation, TerminationClosures.of(rotation), RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true))
        assertTrue("C01: consumed $done", done is ControlCompletionResult.Completed)
        assertEquals("C01: its own seal gone", "[]", read(file)[SEAL])
        assertEquals("C01: the intent row still there", listOf(intent.id), rows(read(file)).map { it.jsonObject.getValue("commandId").jsonPrimitive.content })
    }

    @Test fun C02_withNothingEligibleToReclaim_aTooLargeRotationStaysRefused() = runBlocking {
        val file = File(folder.root, "c02.preferences_pb")
        val o = open(file); seed(o, "[${seal("first", "USER", "u")}]")
        val small = ControlRecordStore(o.owner, codec = ControlPayloadCodec(4_096))
        val first = small.prepareRotation(listOf(node(seal("first", "USER", "u"))), fence(read(file)), life, demand)
        assertTrue("C02 fixture: a current-lifetime rotation", small.execute(first, context) is ControlStoreResult.Confirmed)
        val live = fence(read(file)).userAccessEpoch!!
        val base = bytes(o.data.edit { p -> p[SEAL] = JsonArray(seals(p.toPreferences()) + Json.parseToJsonElement(seal("s", "USER", live))).toString() }, SEAL)
        val liveSeal = seal("s" + "x".repeat(3_800 - base), "USER", live)
        o.data.edit { p -> p[SEAL] = JsonArray(seals(p.toPreferences()).dropLast(1) + Json.parseToJsonElement(liveSeal)).toString() }
        assertEquals("C02 fixture: 3,800 B", 3_800, bytes(read(file), SEAL))
        val rotation = small.prepareRotation(listOf(node(liveSeal)), fence(read(file)), life, demand)
        val before = withoutBarrier(read(file)); val writes = o.storage.writes
        val r1 = small.execute(rotation, context)
        assertTrue("C02 first: TooLarge, got $r1", ((r1 as? ControlStoreResult.Rejected)?.reason as? RejectionReason.TooLarge)?.limit == 4_096)
        assertTrue("C02: nothing to reclaim", small.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        val r2 = small.execute(rotation, context)
        assertTrue("C02 retry: still TooLarge, got $r2", ((r2 as? ControlStoreResult.Rejected)?.reason as? RejectionReason.TooLarge)?.limit == 4_096)
        assertEquals("C02: file unchanged", before, withoutBarrier(read(file)))
        assertEquals("C02: no write", writes, o.storage.writes)
    }

    @Test fun C03_theDefaultLimit_admitsExactly65536Bytes_andRefuses65537() = runBlocking {
        suspend fun attempt(name: String, id: String): Pair<ControlStoreResult, File> {
            val file = File(folder.root, "$name.preferences_pb")
            val o = open(file); seed(o, "[${seal(id, "USER", "u")}]")
            val c = o.control.prepareRotation(listOf(node(seal(id, "USER", "u"))), fence(read(file)), life, demand)
            return o.control.execute(c, context) to file
        }
        val (probe, probeFile) = attempt("c03-probe", "s")
        assertTrue("C03 probe: Confirmed, got $probe", probe is ControlStoreResult.Confirmed)
        val b = bytes(read(probeFile), SEAL)

        val (fits, fitsFile) = attempt("c03-fits", "s" + "x".repeat(limit - b))
        assertTrue("C03: 65,536 B Confirmed, got $fits", fits is ControlStoreResult.Confirmed)
        assertEquals("C03: exactly the limit on disk", limit, bytes(read(fitsFile), SEAL))

        val overFile = File(folder.root, "c03-over.preferences_pb")
        val o = open(overFile); val overId = "s" + "x".repeat(limit + 1 - b); seed(o, "[${seal(overId, "USER", "u")}]")
        val before = withoutBarrier(read(overFile))
        val c = o.control.prepareRotation(listOf(node(seal(overId, "USER", "u"))), fence(before), life, demand)
        tooLarge("C03 over", o.control.execute(c, context), expectedBytes = limit + 1)
        assertEquals("C03: nothing written", before, withoutBarrier(read(overFile)))
    }
}
