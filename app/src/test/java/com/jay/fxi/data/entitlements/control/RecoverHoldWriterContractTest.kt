package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM
import com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Claude-owned 5d writer contract (decision D2): the RECOVER_HOLD result as stored on disk through
 * the real prepare → execute → FileStorage path. Expected values come from the design text
 * (`d2b5_demand_auth_design_r3_codex.md`, lines in comments) and the existing canonical encodings,
 * never from the plan's builder outputs. Issued IDs/UUIDs/order are read only as issuance facts.
 * The descriptor's executor/namespace wiring is an agreed interface contract (the common gates run
 * only when those fields are present).
 * The implementation thread reads but does not edit this file.
 */
class RecoverHoldWriterContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("recover hold cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)

    private val newLife = LifetimeId("new-life")
    private val mergeNow = BootReading("boot", 11000)
    private val external = byteArrayPreferencesKey("lifecycle-external")
    private fun executor(owner: String) = SettlementExecutor(owner, 3, newLife)
    private fun binding(owner: String) = LifecycleBinding(executor(owner), IdentityV1(owner, 2), 1, "binding-start")

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    /** Query HOLD. CAPABILITY-only = PENDING (optionally with a 30 s floor); both axes = PREMIUM_REQUIRED. */
    private fun queryHold(owner: String, user: String?, krx: String?, intent: RefreshIntent,
                          bothAxes: Boolean = false, withFloor: Boolean = false): ControlNode {
        val identity = """{"ownerUid":"$owner","authGeneration":2}"""
        val fence = """{"ownerUid":"$owner","userAccessEpoch":${q(user)},"krxCapabilityEpoch":${q(krx)}}"""
        val provenance = """{"kind":"QUERY","started":{"fence":$fence,"generation":5,"boundIdentity":$identity,""" +
            """"order":9223372036854775807,"binding":17,"intent":"${intent.name}","userInvalidations":0},"answeredAs":$identity}"""
        val axes = if (bothAxes) """["USER","CAPABILITY"]""" else """["CAPABILITY"]"""
        val outcome = when {
            bothAxes -> """{"kind":"PREMIUM_REQUIRED"}"""
            withFloor -> """{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":30}"""
            else -> """{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":null}"""
        }
        val floor = if (withFloor) ""","floor":{"anchorBootId":"boot","anchorElapsedMillis":10000,"waitMillis":30000,"originLifetimeId":"life"}""" else ""
        val node = ControlObligationFixtures.node(
            """{"id":"h","originLifetimeId":"life","binding":17,"axes":$axes,"outcome":$outcome,"provenance":$provenance$floor}""")
        check(ControlSchema.read(ControlKind.HOLD, node) is RestoredHold) { "fixture must be a schema-valid HOLD" }
        return node
    }

    private fun record(owner: String, user: String, krx: String, hold: ControlNode, vararg demand: ControlNode): Preferences =
        ControlLifecycleEvidenceFixtures.raw(
            demand = demand.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() },
            hold = "[${hold.toPayloadEntry().fields}]"
        ).toMutablePreferences().apply {
            this[OWNER_UID] = owner; this[USER_EPOCH] = user; this[KRX_EPOCH] = krx
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        }.toPreferences()

    private fun closure(hold: ControlNode, owner: String) = HoldRecoveryClosure.AfterRestart(hold, executor(owner), "old-tracking", true, true)
    private fun runtime(hold: ControlNode, owner: String) = HoldRecoveryRuntime(binding(owner), 5, true, emptySet(), closure(hold, owner))
    private fun context(hold: ControlNode, owner: String, signOut: Boolean = false, pending: Boolean = false,
                        recovery: HoldRecoveryRuntime? = runtime(hold, owner)) =
        AttemptContext(owner, 3, newLife, signOut, pending, holdRecovery = recovery)
    private fun input(hold: ControlNode, before: FenceV1, owner: String, guard: ControlNode? = null) =
        RecoverHoldInput(hold, guard, before, binding(owner), closure(hold, owner), mergeNow)
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverHold)

    private fun interpreted(p: Preferences, kind: ControlKind) =
        FloorGuardFixtures.read(p).arrays.getValue(kind).entries.filterIsInstance<ControlEntryRead.Interpreted>()
    private fun canonicalUuid(s: String?) = s != null && runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)

    // ---- W1: current owner, CAPABILITY-only Pending, original FORCE_PREMIUM (L412, L425, L431, L439, L443) ----

    @Test fun W1_currentOwnerHandsOverWithOriginalStrength() = runReleaseTest {
        val hold = queryHold("A", "u", "k", FORCE_PREMIUM)
        val existing = DemandAuthFixtures.request(id = "old", owner = "A", intent = IF_STALE)
        val file = folder.newFile(); val s = open(file)
        val before = record("A", "u", "k", hold, existing)
        controlTestTimeout("W1 seed") { s.data.updateData { before } }
        // Non-target conditions: current owner, CAPABILITY target == current epoch, no journal, both markers true.
        assertEquals("A", before[OWNER_UID]); assertEquals("k", before[KRX_EPOCH]); assertNull(before[PURGE_JOURNAL])
        assertEquals(true, before[MAY_CONTAIN_PREMIUM]); assertEquals(true, before[MAY_CONTAIN_KRX])
        // L429, L309: the archived binding/order (17 / MAX in origin "life") must be neither transplanted nor compared.
        val archived = ControlSchema.read(ControlKind.HOLD, hold) as RestoredHold
        val started = (archived.provenance as HoldProvenanceV1.Query).started
        assertEquals(17L, archived.binding)
        assertEquals(17L, started.binding)
        assertEquals(Long.MAX_VALUE, started.order.value)
        assertEquals(LifetimeId("life"), started.order.origin)
        val fixedInput = input(hold, FenceV1("A", "u", "k"), "A")
        assertEquals(3L, fixedInput.binding.executor.binding)
        assertEquals(newLife, fixedInput.binding.executor.originLifetimeId)
        val c = s.control.prepareRecoverHold(fixedInput, LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        assertTrue(atomic("W1_orderIssued"), plan(c).requestOrder != null)
        val issuedOrder = checkNotNull(plan(c).requestOrder)
        val descriptor = (c.body as ControlCommandBody.Lifecycle).input
        assertEquals(atomic("W1_executorWired"), executor("A"), descriptor.executor)
        assertTrue(atomic("W1_namespaceWired"), descriptor.namespace != null)
        val namespace = checkNotNull(descriptor.namespace)
        assertEquals(atomic("W1_beforeFence"), FenceV1("A", "u", "k"), namespace.before)
        assertEquals(atomic("W1_afterFence"), FenceV1("A", "u", ids.epochs.capability), namespace.after)
        assertEquals(atomic("W1_namespaceMarker"), false, namespace.krxMayContain)
        assertEquals(atomic("W1_namespaceJournal"),
            listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), namespace.journal)
        val result = controlTestTimeout("W1 execute") { s.control.execute(c, context(hold, "A")) }
        assertTrue(atomic("W1_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertTrue(atomic("W1_holdRemoved"), interpreted(after, ControlKind.HOLD).isEmpty())
        val rows = interpreted(after, ControlKind.DEMAND)
        val fresh = rows.map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("H07e_writer"), FORCE_PREMIUM, fresh.intent)
        assertEquals(atomic("W1_requestOwner"), "A", fresh.ownerUid)
        assertEquals(atomic("W1_requestBinding"), 3L, fresh.binding)
        assertEquals(atomic("W1_requestOrigin"), newLife, fresh.raisedAt.origin) // L429: no transplant of the old origin
        assertTrue(atomic("W1_requestOrder"), fresh.raisedAt.value > 21)
        assertEquals(atomic("W1_issuedOrder"), issuedOrder.value, fresh.raisedAt.value)
        assertEquals(atomic("W1_existingVerbatim"), existing.toPayloadEntry(), rows.single { it.value.id == "old" }.original.toPayloadEntry())
        assertEquals(atomic("W1_rows"), 2, rows.size)
        // L412: rotate CAPABILITY to the fixed fresh UUID, marker false; exact journal; USER untouched.
        assertTrue(atomic("W1_freshUuid"), canonicalUuid(ids.epochs.capability))
        assertEquals(atomic("W1_rotated"), ids.epochs.capability, after[KRX_EPOCH])
        assertFalse(atomic("W1_rotated"), after[KRX_EPOCH] == "k")
        assertEquals(atomic("W1_marker"), false, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W1_journal"), "A||k|CAPABILITY", after[PURGE_JOURNAL])
        assertEquals(atomic("W1_userAxis"), "u", after[USER_EPOCH])
        assertEquals(atomic("W1_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("W1_owner"), "A", after[OWNER_UID])
        assertArrayEquals(atomic("W1_byteArray"), byteArrayOf(0, 1, -1), after[external]) // C11
        // L167: HOLD REMOVE → new REQUEST CREATE; no guard (no floor). Targets are written here, not taken from the descriptor.
        val expectedApplied = ControlLifecycleEvidenceFixtures.wire(
            transition = "RECOVER_HOLD",
            targets = """{"kind":"HOLD","id":"h","effect":"REMOVE"},{"kind":"DEMAND","id":"${ids.requestId}","effect":"CREATE"}""",
            command = c.id, lifetime = c.ownerTrackingLifetimeId.value)
        val actualApplied = after[ControlLifecycleEvidenceFixtures.evidenceKey]
        assertTrue(atomic("W1_applied"), actualApplied != null)
        assertEquals(atomic("W1_applied"), Json.parseToJsonElement("[$expectedApplied]"),
            Json.parseToJsonElement(checkNotNull(actualApplied)))
    }

    // ---- W2: departed owner — no new REQUEST, floor still handed over (L414, L440, L441, L394) ----

    @Test fun W2_departedOwnerKeepsRequestsAndStillHandsOverFloor() = runReleaseTest {
        val hold = queryHold("A", "u", "k0", FORCE_PREMIUM, withFloor = true)
        val a = DemandAuthFixtures.request(id = "ra", owner = "A")
        val b = DemandAuthFixtures.request(id = "rb", owner = "B")
        val file = folder.newFile(); val s = open(file)
        val before = record("B", "u", "k", hold, a, b)
        controlTestTimeout("W2 seed") { s.data.updateData { before } }
        // Non-target: raw owner B ≠ subject owner A; CAPABILITY target k0 and current k both nonnull, different (L414).
        assertEquals("B", before[OWNER_UID]); assertEquals("k", before[KRX_EPOCH])
        val c = s.control.prepareRecoverHold(input(hold, FenceV1("B", "u", "k"), "B"), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        val result = controlTestTimeout("W2 execute") { s.control.execute(c, context(hold, "B")) }
        assertTrue(atomic("W2_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertTrue(atomic("W2_holdRemoved"), interpreted(after, ControlKind.HOLD).isEmpty())
        val rows = interpreted(after, ControlKind.DEMAND)
        assertTrue(atomic("W2_noNewRequest"), rows.none { it.value.id == ids.requestId })
        assertEquals(atomic("W2_keepA"), a.toPayloadEntry(), rows.single { it.value.id == "ra" }.original.toPayloadEntry())
        assertEquals(atomic("W2_keepB"), b.toPayloadEntry(), rows.single { it.value.id == "rb" }.original.toPayloadEntry())
        // L394: max(existing 0, HOLD remaining) at mergeNow in the current origin: same boot, 1000 ms elapsed → 29000.
        val guard = rows.map { it.value }.filterIsInstance<ScheduleGuardV1>().single()
        assertEquals(atomic("W2_guardId"), ids.guardId, guard.id)
        assertEquals(atomic("W2_floor"), FloorV1("boot", 11000, 29000, newLife), guard.floor)
        assertNull(atomic("W2_noAuth"), guard.auth)
        assertEquals(atomic("W2_rows"), 3, rows.size)
        // L414: namespace preserved, exact source journal.
        assertEquals(atomic("W2_owner"), "B", after[OWNER_UID])
        assertEquals(atomic("W2_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("W2_marker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W2_journal"), "A||k0|CAPABILITY", after[PURGE_JOURNAL])
    }

    // ---- C09 / L394, L634: the floor lower bound is checked independently of the builder ----

    @Test fun C09_floorLowerBoundIsIndependentOfBuilder() {
        val hold = queryHold("A", "u", "k0", FORCE_PREMIUM, withFloor = true)
        val fixed = input(hold, FenceV1("B", "u", "k"), "B") // departed owner, CAPABILITY relation 3: no REQUEST
        val ids = RecoverHoldIds(
            "00000000-0000-0000-0000-000000000101",
            "00000000-0000-0000-0000-000000000102",
            "00000000-0000-0000-0000-000000000103",
            RecoveryFreshEpochs(null, null))
        val before = record("B", "u", "k", hold)
        val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
        val applied = ControlLifecycleEvidenceFixtures.wire(transition = "RECOVER_HOLD",
            targets = """{"kind":"HOLD","id":"h","effect":"REMOVE"},{"kind":"DEMAND","id":"${ids.guardId}","effect":"CREATE"}""",
            command = ids.operationId, lifetime = "00000000-0000-0000-0000-000000000104")
        fun candidate(wait: Long): Preferences = before.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[]"
            this[demandKey] = """[{"id":"${ids.guardId}","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"boot","anchorElapsedMillis":11000,"waitMillis":$wait,"originLifetimeId":"new-life"}}]"""
            this[PURGE_JOURNAL] = "A||k0|CAPABILITY"
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$applied]"
        }.toPreferences()
        val goodRaw = candidate(29000)
        val shortRaw = candidate(28999)
        val oldFloor = (ControlSchema.read(ControlKind.HOLD, hold) as RestoredHold).floor
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), oldFloor)
        assertEquals(BootReading("boot", 11000), fixed.mergeNow)
        assertEquals("B", before[OWNER_UID])
        assertEquals("k", before[KRX_EPOCH])
        assertEquals("[]", before[demandKey])
        val goodGuard = interpreted(goodRaw, ControlKind.DEMAND).single().value as ScheduleGuardV1
        val shortGuard = interpreted(shortRaw, ControlKind.DEMAND).single().value as ScheduleGuardV1
        assertEquals(FloorV1("boot", 11000, 29000, newLife), goodGuard.floor)
        assertEquals(FloorV1("boot", 11000, 28999, newLife), shortGuard.floor)
        assertEquals(goodGuard.copy(floor = shortGuard.floor), shortGuard)
        assertEquals(
            goodRaw.toMutablePreferences().apply { remove(demandKey) }.toPreferences(),
            shortRaw.toMutablePreferences().apply { remove(demandKey) }.toPreferences())
        val checker = RecoverHoldTransition(ControlPayloadCodec())
        val beforeRead = FloorGuardFixtures.read(before)
        assertTrue(atomic("C09_contract_positive"), checker.requiredEffects(
            fixed, ids, null, beforeRead, FloorGuardFixtures.read(goodRaw)))
        assertFalse(atomic("C09_contract_short"), checker.requiredEffects(
            fixed, ids, null, beforeRead, FloorGuardFixtures.read(shortRaw)))
    }

    // ---- W3: the writer really passes the common gates (L138, L436, G03/G04, L539) ----

    @Test fun W3_commonGatesAreWired() = runReleaseTest {
        val hold = queryHold("A", "u", "k", FORCE_PREMIUM)
        suspend fun blocked(id: String, context: AttemptContext, change: ((Preferences) -> Preferences)? = null) {
            val file = folder.newFile(); val s = open(file)
            val seeded = record("A", "u", "k", hold)
            controlTestTimeout("W3 seed") { s.data.updateData { seeded } }
            val c = s.control.prepareRecoverHold(input(hold, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
            if (change != null) controlTestTimeout("W3 change") { s.data.updateData { change(it) } }
            val expected = disk(file)
            val result = controlTestTimeout("W3 execute") { s.control.execute(c, context) }
            assertFalse(eligible(id), result is ControlStoreResult.Confirmed)
            assertEquals(eligible(id), expected, disk(file))
        }
        blocked("W3_signOut", context(hold, "A", signOut = true))
        blocked("W3_identityPending", context(hold, "A", pending = true))
        blocked("W3_noRuntime", context(hold, "A", recovery = null))
        blocked("W3_fenceChanged", context(hold, "A")) { it.toMutablePreferences().apply { this[KRX_EPOCH] = "k9" }.toPreferences() }
    }

    // ---- W4: R01 — landing fails before storage, the same ref retries once with the same UUIDs (L642) ----

    @Test fun W4_retryAfterUnlandedFailureAppliesOnce() = runReleaseTest {
        val hold = queryHold("A", "u", "k", FORCE_PREMIUM)
        val file = folder.newFile(); val s = open(file)
        val before = record("A", "u", "k", hold)
        controlTestTimeout("W4 seed") { s.data.updateData { before } }
        val orders = LifecycleOrderSource(newLife, 21)
        val c = s.control.prepareRecoverHold(input(hold, FenceV1("A", "u", "k"), "A"), orders)
        val ids = plan(c).ids
        assertTrue(retry("W4_orderIssued"), plan(c).requestOrder != null)
        val issuedOrder = checkNotNull(plan(c).requestOrder)
        assertTrue(retry("W4_issued"), issuedOrder.value > 21)
        s.storage.before = true
        val first = controlTestTimeout("W4 first") { s.control.execute(c, context(hold, "A")) }
        assertTrue(retry("W4_first"), first is ControlStoreResult.Unconfirmed)
        assertEquals(retry("W4_unlanded"), before, disk(file))
        val second = controlTestTimeout("W4 retry") { s.control.execute(c, context(hold, "A")) }
        assertTrue(retry("W4_retry"), second is ControlStoreResult.Confirmed)
        assertEquals(retry("W4_sameIds"), ids, plan(c).ids)
        val after = disk(file)
        assertEquals(retry("W4_uuidOnce"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(retry("W4_oneRequest"), 1, interpreted(after, ControlKind.DEMAND).count { it.value.id == ids.requestId })
        assertEquals(retry("W4_rows"), 1, interpreted(after, ControlKind.DEMAND).size)
        assertTrue(retry("W4_holdRemoved"), interpreted(after, ControlKind.HOLD).isEmpty())
        // L312: fixed at prepare, never reissued on retry — the supplier's next grant still follows the one issue.
        assertEquals(retry("W4_fixedOrder"), issuedOrder, plan(c).requestOrder)
        val request = interpreted(after, ControlKind.DEMAND).single().value as DemandV1
        assertEquals(retry("W4_orderOnce"), issuedOrder.value, request.raisedAt.value)
        val next = checkNotNull(orders.issue(bindingStart = 1))
        assertEquals(retry("W4_noReissue"), issuedOrder.value, next.previous)
    }

    // ---- W5: both axes in one candidate (L443, L685 "단축/양축") ----

    @Test fun W5_bothAxesRotateTogether() = runReleaseTest {
        val hold = queryHold("A", "u", "k", IF_STALE, bothAxes = true)
        val file = folder.newFile(); val s = open(file)
        val before = record("A", "u", "k", hold)
        controlTestTimeout("W5 seed") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(input(hold, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        val result = controlTestTimeout("W5 execute") { s.control.execute(c, context(hold, "A")) }
        assertTrue(atomic("W5_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertTrue(atomic("W5_uuids"), canonicalUuid(ids.epochs.user) && canonicalUuid(ids.epochs.capability))
        assertFalse(atomic("W5_distinct"), ids.epochs.user == ids.epochs.capability) // L437 b
        assertEquals(atomic("W5_user"), ids.epochs.user, after[USER_EPOCH])
        assertEquals(atomic("W5_krx"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(atomic("W5_userMarker"), false, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("W5_krxMarker"), false, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W5_journal"), setOf("A|u||USER", "A||k|CAPABILITY"), after[PURGE_JOURNAL]?.split("\n")?.toSet())
        val fresh = interpreted(after, ControlKind.DEMAND).map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("W5_userStrength"), FORCE_PREMIUM, fresh.intent) // L425: USER axis → FORCE_PREMIUM
        assertTrue(atomic("W5_holdRemoved"), interpreted(after, ControlKind.HOLD).isEmpty())
    }
}
