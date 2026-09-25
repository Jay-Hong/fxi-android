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
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
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
import kotlinx.serialization.json.JsonPrimitive

/**
 * Claude-owned 5e-2 descriptor and confirmation-only contract (plan v1 row 9). Skeleton design v2
 * §4: the common validDescriptor requires transition=RECOVER_INTENT and no other writer plan for a
 * named intent plan and links the writer's own validDescriptor, which re-derives operationId,
 * executor, source original, full targets/roles, REQUEST fields/order, empty requiredUnchanged and
 * namespace before/after/journal/markers from fixed inputs (not a plan.descriptor() comparison).
 * Confirmation-only: own Applied, observation loss and prior-confirmation rules, then source
 * absent, required REQUEST, namespace after/markers and exact journal; no new-application runtime
 * is demanded again and effects are not re-run; a broken postcondition is a confirmation failure.
 * Operation-ID collision (C4 review, rows 9/10) is checked through the common new-application
 * boundary. The plan-less RECOVER_INTENT confirmation contract (ControlLifecycleEvidenceDispatchTest)
 * is unchanged and not repeated here. The implementation thread reads but does not edit this file.
 */
class RecoverIntentDescriptorContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent descriptor cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)

    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, target: String?, id: String = "r") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"session","ownerUid":${q(owner)},"axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private val source = intent("A", "k")
    private fun closure(src: ControlNode = source) = HoldRecoveryClosure.AfterRestart(src, executor, "old-tracking", true, true)
    private fun input(src: ControlNode = source) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, closure(src))
    private fun context(src: ControlNode = source) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(src)))
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private fun record(change: (MutablePreferences) -> Unit = {}): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = payload(listOf(source)); change(this)
    }.toPreferences()
    private fun interpreted(p: Preferences, kind: ControlKind) =
        FloorGuardFixtures.read(p).arrays.getValue(kind).entries.filterIsInstance<ControlEntryRead.Interpreted>()

    // ---- literal expected descriptor pieces (L412, L421, L443; skeleton v2 §2–§4) ----
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun sourceTarget(before: ControlNode = source, id: String = "r", role: LifecycleRole = LifecycleRole.RECOVERY_INTENT) =
        LifecycleFixedTarget(LifecycleTarget(ControlKind.RECOVERY_INTENT, id, LifecycleEffect.REMOVE), role, before, null)
    private fun requestTarget(after: ControlNode = newRequest, role: LifecycleRole = LifecycleRole.REQUEST) =
        LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "new-request", LifecycleEffect.CREATE), role, null, after)
    private val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
    private fun namespace(after: FenceV1 = FenceV1("A", "u", krxFresh), j: List<PendingPurge> = journal,
        user: Boolean? = null, krx: Boolean? = false, before: FenceV1 = FenceV1("A", "u", "k")) =
        LifecycleNamespacePostcondition(before, after, j, user, krx)
    private fun descriptor(p: RecoverIntentPlan, id: String = ids.operationId, transition: LifecycleTransition = LifecycleTransition.RECOVER_INTENT,
        targets: List<LifecycleFixedTarget> = listOf(sourceTarget(), requestTarget()), exec: SettlementExecutor? = executor,
        ns: LifecycleNamespacePostcondition? = namespace(), unchanged: List<LifecycleFixedTarget> = emptyList(),
        hold: RecoverHoldPlan? = null) =
        ControlLifecycleDescriptor(id, transition, targets, exec, ns, unchanged, recoverHold = hold, recoverIntent = p)
    private fun plan() = RecoverIntentPlan.prepare(input(), ids, LifecycleOrderSource(newLife, 21))

    // ---- P1: writer validDescriptor — literal descriptor accepted, each field tampered once rejected ----

    @Test fun P1_writerValidDescriptor() {
        val p = plan()
        assertEquals(LifecycleOrderGrant(newLife, 21, 1, 0, 22), p.requestOrder) // issuance fact only
        assertTrue(atomic("P1_literalAccepted"), writer.validDescriptor(p, descriptor(p)))
        fun bad(id: String, d: ControlLifecycleDescriptor) = assertFalse(atomic(id), writer.validDescriptor(p, d))
        bad("P1_operationId", descriptor(p, id = "00000000-0000-0000-0000-000000000999"))
        bad("P1_executorOwner", descriptor(p, exec = executor.copy(ownerUid = "B")))
        bad("P1_executorMissing", descriptor(p, exec = null))
        bad("P1_requestTargetMissing", descriptor(p, targets = listOf(sourceTarget())))
        bad("P1_sourceBefore", descriptor(p, targets = listOf(sourceTarget(before = intent("A", "k8")), requestTarget())))
        bad("P1_sourceRole", descriptor(p, targets = listOf(sourceTarget(role = LifecycleRole.HOLD), requestTarget())))
        bad("P1_requestAfter", descriptor(p, targets = listOf(sourceTarget(), requestTarget(after = DemandAuthFixtures.request(id = "new-request",
            owner = "A", binding = 3, origin = newLife, intent = RefreshIntent.IF_STALE, order = 22)))))
        bad("P1_requestRole", descriptor(p, targets = listOf(sourceTarget(), requestTarget(role = LifecycleRole.GUARD))))
        bad("P1_unchangedNotEmpty", descriptor(p, unchanged = listOf(LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "g",
            LifecycleEffect.REPLACE), LifecycleRole.GUARD, ControlLifecycleEvidenceFixtures.guard, ControlLifecycleEvidenceFixtures.guard))))
        bad("P1_namespaceMissing", descriptor(p, ns = null))
        bad("P1_namespaceBefore", descriptor(p, ns = namespace(before = FenceV1("A", "u", "k9"))))
        bad("P1_namespaceAfter", descriptor(p, ns = namespace(after = FenceV1("A", "u", "k"))))
        bad("P1_namespaceJournal", descriptor(p, ns = namespace(j = emptyList())))
        bad("P1_namespaceKrxMarker", descriptor(p, ns = namespace(krx = null)))
        bad("P1_namespaceUserMarker", descriptor(p, ns = namespace(user = false)))
    }

    // ---- P2: common validDescriptor — a named intent plan fixes the transition and excludes other writer plans ----

    @Test fun P2_commonValidDescriptor() {
        val p = plan()
        val common = ControlLifecycleConfirmation(codec)
        assertTrue(atomic("P2_literalAccepted"), common.validDescriptor(descriptor(p)))
        assertFalse(atomic("P2_transition"), common.validDescriptor(descriptor(p, transition = LifecycleTransition.RECOVER_HOLD)))
        val holdPlan = HoldRecoveryFixtures.plan()
        assertFalse(atomic("P2_otherWriterPlan"), common.validDescriptor(descriptor(p, hold = holdPlan)))
    }

    // ---- P3: R02 — landed, then the return fails; the same ref only confirms (no second effect, no reissue) ----

    @Test fun P3_landedThenFailedRetryConfirms() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("P3 seed") { s.data.updateData { record() } }
        val orders = LifecycleOrderSource(newLife, 21)
        val c = s.control.prepareRecoverIntent(input(), orders)
        val p = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
        s.storage.afterScope = true
        val failed = controlTestTimeout("P3 failure") { s.control.execute(c, context()) }
        assertTrue(retry("P3_unconfirmed"), failed is ControlStoreResult.Unconfirmed)
        val landed = disk(file)
        assertTrue(atomic("P3_landedSourceRemoved"), interpreted(landed, ControlKind.RECOVERY_INTENT).isEmpty())
        assertEquals(atomic("P3_landedRequest"), 1, interpreted(landed, ControlKind.DEMAND).count { it.value.id == p.ids.requestId })
        val confirmed = controlTestTimeout("P3 retry") { s.control.execute(c, context()) }
        assertTrue(retry("P3_confirmed"), confirmed is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertEquals(retry("P3_noSecondEffect"), landed[demandKey], after[demandKey])
        assertEquals(retry("P3_journalOnce"), "A||k|CAPABILITY", after[PURGE_JOURNAL])
        assertEquals(retry("P3_noReissue"), 22L, checkNotNull(orders.issue(bindingStart = 1)).previous)
    }

    // ---- P4: R02 across restart — confirmPrevious confirms without the new-application runtime ----

    @Test fun P4_restartConfirmPrevious() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("P4 seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        s.storage.afterScope = true
        val failed = controlTestTimeout("P4 failure") { s.control.execute(c, context()) }
        assertTrue(retry("P4_unconfirmed"), failed is ControlStoreResult.Unconfirmed)
        val landed = disk(file)
        controlTestTimeout("P4 close") { s.close() }
        val next = open(file); next.raw()
        val result = controlTestTimeout("P4 confirm previous") { next.control.confirmPrevious(c) }
        assertTrue(retry("P4_confirmed"), result is ControlStoreResult.Confirmed)
        assertEquals(retry("P4_unchanged"), landed[intentKey], disk(file)[intentKey])
    }

    // ---- P5: a later change breaks the postcondition — confirmation fails and nothing is re-run (source not recreated) ----

    @Test fun P5_brokenPostconditionIsConfirmationFailure() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("P5 seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        val p = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
        s.storage.afterScope = true
        val failed = controlTestTimeout("P5 failure") { s.control.execute(c, context()) }
        assertTrue(retry("P5_unconfirmed"), failed is ControlStoreResult.Unconfirmed)
        val landed = disk(file)
        assertTrue(atomic("P5_landedSourceRemoved"), interpreted(landed, ControlKind.RECOVERY_INTENT).isEmpty())
        assertEquals(atomic("P5_landedRequest"), 1, interpreted(landed, ControlKind.DEMAND).count { it.value.id == p.ids.requestId })
        assertTrue(atomic("P5_landedApplied"), landed[ControlLifecycleEvidenceFixtures.evidenceKey]?.contains(c.id) == true)
        // The successor REQUEST is consumed before the confirmation retry.
        controlTestTimeout("P5 consume") { s.data.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[demandKey] = payload(interpreted(cur, ControlKind.DEMAND).filter { it.value.id != p.ids.requestId }.map { it.original })
            }.toPreferences()
        } }
        val consumed = disk(file)
        val result = controlTestTimeout("P5 retry") { s.control.execute(c, context()) }
        assertTrue(atomic("P5_conflict"), result is ControlStoreResult.Conflict)
        assertEquals(
            atomic("P5_reason"),
            ConflictReason.TargetMissing,
            (result as ControlStoreResult.Conflict).reason
        )
        assertEquals(atomic("P5_unchanged"), consumed, disk(file))
    }

    // ---- P6: operation-ID collision at the common new-application boundary (C4 review 5, rows 9/10) ----

    @Test fun P6_operationIdCollision() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("P6 seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        val settled = ControlObligationFixtures.replace(ControlObligationFixtures.settledSeal, listOf("settlement"), "operationId", JsonPrimitive(c.id))
        controlTestTimeout("P6 collide") { s.data.updateData { cur ->
            cur.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = payload(listOf(settled)) }.toPreferences()
        } }
        val expected = disk(file)
        assertFalse(FloorGuardFixtures.read(expected).hasUninterpretable)
        val result = controlTestTimeout("P6 execute") { s.control.execute(c, context()) }
        assertTrue(atomic("P6_conflict"), result is ControlStoreResult.Conflict)
        assertEquals(atomic("P6_reason"), ConflictReason.OperationIdCollision, (result as ControlStoreResult.Conflict).reason)
        assertEquals(atomic("P6_unchanged"), expected, disk(file))
    }
}
