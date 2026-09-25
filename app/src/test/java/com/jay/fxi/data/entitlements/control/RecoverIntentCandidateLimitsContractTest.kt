package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Claude-owned 5e-2 candidate size/depth contract (plan v1 row 8, §9.4 C12; design §9.6 size rules;
 * C12 design v1 `5e2_contract_c12_design_v1.md`). Changed payloads: DEMAND (the new REQUEST is
 * appended), COMMAND_EVIDENCE (own Applied is appended) and RECOVERY_INTENT (one row removed). UTF-8
 * 65,536 is accepted and 65,537 rejected, for ASCII / Korean+emoji / escape-heavy / long-id
 * distributions, computed by the real encoding (fixtures use the codec's JsonArray text form).
 * Direct validator cases pass the raw candidate Preferences; builder cases run the real
 * FileStorage path and require no landing. The honest builder never grows RECOVERY_INTENT, so its
 * boundary is a direct-validator case only (JSON whitespace outside strings keeps rows, order and
 * values). Codec 64/65 depth itself is fixed by ControlLifecycleEvidenceEnvelopeTest E09/E10; here
 * only the named writer's refusal on each array is checked, and a depth-64 opaque refusal is a
 * schema/metadata defence, not a depth-only detection. The implementation thread reads but does
 * not edit this file.
 */
class RecoverIntentCandidateLimitsContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent limits cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun strip(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val limit = ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES

    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private val grant = LifecycleOrderGrant(newLife, 21, 1, 0, 22)
    private fun intent(id: String, axis: String, target: String) =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"session","ownerUid":"A","axis":"$axis","targetEpoch":"$target"}""")
    private val source = intent("r", "CAPABILITY", "k")
    private val sibling = intent("r2", "USER", "u")
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private val distributions = listOf("ascii" to "ascii", "unicode" to "한글😀".repeat(100),
        "escapes" to "\"\\\n".repeat(100), "longId" to "command".repeat(100))

    private fun closure() = HoldRecoveryClosure.AfterRestart(source, executor, "old-tracking", true, true)
    private fun input() = RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure())
    private fun context() = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure()))
    private fun before(demand: String = "[]", evidence: String = "[]", intents: String = payload(listOf(source, sibling))): Preferences =
        ControlLifecycleEvidenceFixtures.raw(demand = demand, evidence = evidence).toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[intentKey] = intents
        }.toPreferences()
    private fun applied(commandId: String, lifetime: String, requestId: String = "new-request") = ControlLifecycleEvidenceFixtures.wire("RECOVER_INTENT",
        """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"$requestId","effect":"CREATE"}""", commandId, lifetime)
    /** The expected successor for an issued request id (A / 3 / new-life / FORCE_ENTITLEMENTS / 22), independent of the builder. */
    private fun successorFor(requestId: String) = DemandAuthFixtures.request(id = requestId, owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    /** The literal good candidate (C7 shape) on top of [raw]; [evidencePrefix] is the preserved evidence entries. */
    private fun good(raw: Preferences, c: CommandRef, demandRows: List<ControlNode>, evidencePrefix: List<String> = emptyList(),
        intents: String = payload(listOf(sibling))): Preferences = raw.toMutablePreferences().apply {
        this[KRX_EPOCH] = krxFresh; this[MAY_CONTAIN_KRX] = false
        this[PURGE_JOURNAL] = "A||k|CAPABILITY"
        this[intentKey] = intents
        this[demandKey] = payload(demandRows + newRequest)
        this[evidenceKey] = (evidencePrefix + applied(c.id, c.ownerTrackingLifetimeId.value)).joinToString(",", "[", "]")
    }.toPreferences()
    /** A preserved REQUEST whose id pads the appended DEMAND payload to exactly [target] bytes. */
    private fun demandPad(token: String, target: Int, successor: ControlNode = newRequest): ControlNode {
        fun row(id: String) = DemandAuthFixtures.request(id = id, owner = "A", intent = RefreshIntent.IF_STALE)
        val base = bytes(payload(listOf(row(token), successor)))
        require(base <= target)
        return row(token + "x".repeat(target - base))
    }
    /** A preserved foreign Applied entry padding the appended evidence payload to exactly [target] bytes. */
    private fun evidencePad(token: String, target: Int, own: String): String {
        fun entry(cmd: String) = ControlLifecycleEvidenceFixtures.wire(command = cmd)
        val base = bytes("[" + entry(token) + "," + own + "]")
        require(base <= target)
        return entry(token + "x".repeat(target - base))
    }

    // ---- L1: DEMAND grows by the new REQUEST — direct validator at 65,536 / 65,537, four distributions ----

    @Test fun L1_demandDirectBoundary() {
        val p = RecoverIntentPlan.prepare(input(), ids, LifecycleOrderSource(newLife, 21))
        val c = ControlLifecycleEvidenceFixtures.command(p.descriptor())
        assertEquals(grant, p.requestOrder)
        for ((name, token) in distributions) {
            for (target in listOf(limit, limit + 1)) {
                val pad = demandPad(token, target)
                val raw = before(demand = payload(listOf(pad)))
                val cand = good(raw, c, listOf(pad))
                assertEquals(target, bytes(checkNotNull(cand[demandKey])))
                val ok = writer.validCandidate(c, p, ControlLifecycleEvidenceFixtures.read(raw), cand)
                if (target == limit) assertTrue(atomic("L1_${name}_atLimit"), ok) else assertFalse(atomic("L1_${name}_overLimit"), ok)
            }
        }
    }

    // ---- L2: DEMAND through the builder — at limit lands, over limit lands nothing (FileStorage) ----

    @Test fun L2_demandBuilderBoundary() = runReleaseTest {
        for ((name, token) in distributions) {
            for (target in listOf(limit, limit + 1)) {
                val file = folder.newFile(); val s = open(file)
                // prepare first: the issued REQUEST id (a UUID) is part of the appended payload.
                val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
                val successor = successorFor(checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent).ids.requestId)
                val pad = demandPad(token, target, successor)
                assertEquals(target, bytes(payload(listOf(pad, successor)))) // expected appended payload, before execution
                controlTestTimeout("L2 seed") { s.data.updateData { before(demand = payload(listOf(pad))) } }
                val seeded = strip(disk(file))
                val result = controlTestTimeout("L2 execute") { s.control.execute(c, context()) }
                if (target == limit) {
                    assertTrue(atomic("L2_${name}_atLimitConfirmed"), result is ControlStoreResult.Confirmed)
                    assertEquals(atomic("L2_${name}_atLimitSize"), limit, bytes(checkNotNull(strip(disk(file))[demandKey])))
                } else {
                    assertFalse(atomic("L2_${name}_overLimitNotConfirmed"), result is ControlStoreResult.Confirmed)
                    assertEquals(atomic("L2_${name}_overLimitUnchanged"), seeded, strip(disk(file)))
                }
            }
        }
    }

    // ---- L3: COMMAND_EVIDENCE grows by own Applied; source removal frees space elsewhere but the whole candidate fails ----

    @Test fun L3_evidenceDirectBoundary() {
        val p = RecoverIntentPlan.prepare(input(), ids, LifecycleOrderSource(newLife, 21))
        val c = ControlLifecycleEvidenceFixtures.command(p.descriptor())
        val own = applied(c.id, c.ownerTrackingLifetimeId.value)
        for ((name, token) in distributions) {
            for (target in listOf(limit, limit + 1)) {
                val pad = evidencePad(token, target, own)
                val raw = before(evidence = "[$pad]")
                val cand = good(raw, c, emptyList(), listOf(pad))
                assertEquals(target, bytes(checkNotNull(cand[evidenceKey])))
                assertTrue(bytes(checkNotNull(cand[intentKey])) < bytes(checkNotNull(raw[intentKey]))) // space freed elsewhere
                val ok = writer.validCandidate(c, p, ControlLifecycleEvidenceFixtures.read(raw), cand)
                if (target == limit) assertTrue(atomic("L3_${name}_atLimit"), ok) else assertFalse(atomic("L3_${name}_overLimit"), ok)
            }
        }
    }

    @Test fun L4_evidenceBuilderBoundary() = runReleaseTest {
        for ((name, token) in distributions) {
            for (target in listOf(limit, limit + 1)) {
                val file = folder.newFile(); val s = open(file)
                controlTestTimeout("L4 seed") { s.data.updateData { before() } }
                val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
                val requestId = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent).ids.requestId
                val own = applied(c.id, c.ownerTrackingLifetimeId.value, requestId)
                val pad = evidencePad(token, target, own)
                assertEquals(target, bytes("[$pad,$own]")) // expected appended payload, before execution
                controlTestTimeout("L4 pad") { s.data.updateData { cur -> cur.toMutablePreferences().apply { this[evidenceKey] = "[$pad]" }.toPreferences() } }
                val seeded = strip(disk(file))
                val result = controlTestTimeout("L4 execute") { s.control.execute(c, context()) }
                if (target == limit) {
                    assertTrue(atomic("L4_${name}_atLimitConfirmed"), result is ControlStoreResult.Confirmed)
                    assertEquals(atomic("L4_${name}_atLimitSize"), limit, bytes(checkNotNull(strip(disk(file))[evidenceKey])))
                } else {
                    assertFalse(atomic("L4_${name}_overLimitNotConfirmed"), result is ControlStoreResult.Confirmed)
                    assertEquals(atomic("L4_${name}_overLimitUnchanged"), seeded, strip(disk(file)))
                }
            }
        }
    }

    // ---- L5: RECOVERY_INTENT — direct validator only; whitespace outside strings keeps rows, order and values ----

    @Test fun L5_intentDirectBoundary() {
        val p = RecoverIntentPlan.prepare(input(), ids, LifecycleOrderSource(newLife, 21))
        val c = ControlLifecycleEvidenceFixtures.command(p.descriptor())
        val raw = before()
        val compact = payload(listOf(sibling))
        for (target in listOf(limit, limit + 1)) {
            val spaced = "[" + " ".repeat(target - bytes(compact)) + compact.drop(1)
            assertEquals(target, bytes(spaced))
            val cand = good(raw, c, emptyList(), intents = spaced)
            val ok = writer.validCandidate(c, p, ControlLifecycleEvidenceFixtures.read(raw), cand)
            if (target == limit) assertTrue(atomic("L5_atLimit"), ok) else assertFalse(atomic("L5_overLimit"), ok)
        }
    }

    // ---- L6: depth — the named writer refuses a record whose array is past the codec depth (each array) ----

    @Test fun L6_depthLinkEachArray() = runReleaseTest {
        fun deep(first: String, depth: Int) = "[" + first + "," + "[".repeat(depth - 2) + "0" + "]".repeat(depth - 2) + "]"
        val cases = listOf(
            "intent" to { d: Int -> before(intents = deep(source.toPayloadEntry().fields.toString(), d)) },
            "demand" to { d: Int -> before(demand = deep(DemandAuthFixtures.request(id = "old").toPayloadEntry().fields.toString(), d)) },
            "evidence" to { d: Int -> before(evidence = deep(ControlLifecycleEvidenceFixtures.wire(), d)) })
        for ((name, make) in cases) {
            for (depth in listOf(65, 64)) {
                val file = folder.newFile(); val s = open(file)
                val seeded = make(depth)
                controlTestTimeout("L6 seed") { s.data.updateData { seeded } }
                val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
                val expected = strip(disk(file))
                val result = controlTestTimeout("L6 execute") { s.control.execute(c, context()) }
                // Depth 65: unreadable record. Depth 64: opaque row/metadata — refused by schema/metadata guards, not by depth alone.
                if (depth == 65) assertTrue(atomic("L6_${name}_65recovery"), result is ControlStoreResult.RecoveryRequired)
                assertFalse(atomic("L6_${name}_${depth}notConfirmed"), result is ControlStoreResult.Confirmed)
                assertEquals(atomic("L6_${name}_${depth}unchanged"), expected, strip(disk(file)))
            }
        }
    }
}
