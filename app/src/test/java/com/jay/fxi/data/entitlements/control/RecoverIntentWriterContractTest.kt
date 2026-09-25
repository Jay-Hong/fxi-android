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
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS
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
 * Claude-owned 5e-2 writer contract: the RECOVER_INTENT result as stored on disk through the real
 * prepareRecoverIntent → execute → FileStorage path. Expected values come from the design text
 * (`d2b5_demand_auth_design_r3_codex.md` §7.2 lines 412–445, §7.3 lines 449–451; line numbers in
 * comments) and the canonical journal encoding, never from the plan's builder outputs. Issued
 * IDs/UUIDs/order are read only as issuance facts. The Applied target order (source REMOVE, then
 * REQUEST CREATE) and the descriptor executor/namespace wiring are agreed interface contracts
 * (skeleton design v2 §2–§4). The implementation thread reads but does not edit this file.
 *
 * Against the skeleton (every writer boundary rejects) the positive tests here must FAIL; passing
 * negative tests against the skeleton are not evidence of conditional rejection.
 */
class RecoverIntentWriterContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("recover intent cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)

    private val newLife = LifetimeId("new-life")
    private val external = byteArrayPreferencesKey("lifecycle-external")
    private fun executor(owner: String) = SettlementExecutor(owner, 3, newLife)
    private fun binding(owner: String) = LifecycleBinding(executor(owner), IdentityV1(owner, 2), 1, "binding-start")

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(id: String, session: String, owner: String?, axis: String, target: String?): ControlNode {
        val node = ControlObligationFixtures.node(
            """{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
        check(ControlSchema.read(ControlKind.RECOVERY_INTENT, node) is RecoveryIntentV1) { "fixture must be a schema-valid RECOVERY_INTENT" }
        return node
    }
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }

    /** krx == null removes the KRX epoch key (current CAPABILITY epoch absent). */
    private fun record(owner: String, user: String, krx: String?, intents: List<ControlNode>, vararg demand: ControlNode): Preferences =
        ControlLifecycleEvidenceFixtures.raw(demand = payload(demand.toList())).toMutablePreferences().apply {
            this[OWNER_UID] = owner; this[USER_EPOCH] = user
            if (krx == null) remove(KRX_EPOCH) else this[KRX_EPOCH] = krx
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(intents)
        }.toPreferences()

    private fun closure(source: ControlNode, owner: String) = HoldRecoveryClosure.AfterRestart(source, executor(owner), "old-tracking", true, true)
    private fun runtime(source: ControlNode, owner: String) = HoldRecoveryRuntime(binding(owner), 5, true, emptySet(), closure(source, owner))
    private fun context(source: ControlNode, owner: String, signOut: Boolean = false, pending: Boolean = false,
                        recovery: HoldRecoveryRuntime? = runtime(source, owner), holdSlot: HoldRecoveryRuntime? = null) =
        AttemptContext(owner, 3, newLife, signOut, pending, holdRecovery = holdSlot, intentRecovery = recovery)
    private fun input(source: ControlNode, before: FenceV1, owner: String) =
        RecoverIntentInput(source, before, binding(owner), closure(source, owner))
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)

    private fun interpreted(p: Preferences, kind: ControlKind) =
        FloorGuardFixtures.read(p).arrays.getValue(kind).entries.filterIsInstance<ControlEntryRead.Interpreted>()
    private fun canonicalUuid(s: String?) = s != null && runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)
    private fun applied(c: CommandRef, targets: String) = Json.parseToJsonElement("[" + ControlLifecycleEvidenceFixtures.wire(
        transition = "RECOVER_INTENT", targets = targets, command = c.id, lifetime = c.ownerTrackingLifetimeId.value) + "]")

    // Same-session sibling and other-session intent: both must survive the removal of `r` (L451, A2/B4).
    private val sibling get() = intent("r2", "session", "A", "USER", "u")
    private val otherSession get() = intent("r3", "other", "A", "CAPABILITY", "k")

    // ---- W1: C0a — current owner, CAPABILITY target == current epoch (L412, L421, L439, L443, L451) ----

    @Test fun W1_currentOwnerEqualTargetRotatesAndHandsOver() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k")
        val existing = DemandAuthFixtures.request(id = "old", owner = "A", binding = 3, origin = newLife, intent = FORCE_PREMIUM) // stronger current-binding REQUEST, still preserved
        val foreign = DemandAuthFixtures.request(id = "rb", owner = "B", intent = IF_STALE)
        val file = folder.newFile(); val s = open(file)
        val before = record("A", "u", "k", listOf(source, sibling, otherSession), existing, foreign)
        controlTestTimeout("W1 seed") { s.data.updateData { before } }
        assertEquals("A", before[OWNER_UID]); assertEquals("k", before[KRX_EPOCH]); assertNull(before[PURGE_JOURNAL])
        assertEquals(true, before[MAY_CONTAIN_PREMIUM]); assertEquals(true, before[MAY_CONTAIN_KRX])
        val orders = LifecycleOrderSource(newLife, 21)
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), orders)
        val ids = plan(c).ids
        assertNull(atomic("W1_prepared"), plan(c).preparationProblem)
        assertTrue(atomic("W1_orderIssued"), plan(c).requestOrder != null)
        val issuedOrder = checkNotNull(plan(c).requestOrder)
        val descriptor = (c.body as ControlCommandBody.Lifecycle).input
        assertEquals(atomic("W1_transition"), LifecycleTransition.RECOVER_INTENT, descriptor.transition)
        assertEquals(atomic("W1_executorWired"), executor("A"), descriptor.executor)
        assertTrue(atomic("W1_namespaceWired"), descriptor.namespace != null)
        assertTrue(atomic("W1_noUnchanged"), descriptor.requiredUnchanged.isEmpty())
        val result = controlTestTimeout("W1 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W1_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        // L451: exactly the one source row removed; same-session sibling and other session verbatim.
        val intents = interpreted(after, ControlKind.RECOVERY_INTENT)
        assertTrue(atomic("W1_sourceRemoved"), intents.none { it.value.id == "r" })
        assertEquals(atomic("W1_siblingVerbatim"), sibling.toPayloadEntry(), intents.single { it.value.id == "r2" }.original.toPayloadEntry())
        assertEquals(atomic("W1_otherSessionVerbatim"), otherSession.toPayloadEntry(), intents.single { it.value.id == "r3" }.original.toPayloadEntry())
        assertEquals(atomic("W1_intentRows"), 2, intents.size)
        // L412: CAPABILITY rotated to the fixed fresh UUID, marker false; USER untouched; exact journal.
        assertTrue(atomic("W1_freshUuid"), canonicalUuid(ids.epochs.capability))
        assertNull(atomic("W1_userNotIssued"), ids.epochs.user)
        assertEquals(atomic("W1_rotated"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(atomic("W1_marker"), false, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W1_userAxis"), "u", after[USER_EPOCH])
        assertEquals(atomic("W1_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("W1_owner"), "A", after[OWNER_UID])
        assertEquals(atomic("W1_journal"), "A||k|CAPABILITY", after[PURGE_JOURNAL])
        // L421/L439 step 5: one new REQUEST with the axis lower bound; existing stronger REQUEST kept, not merged.
        val rows = interpreted(after, ControlKind.DEMAND)
        val fresh = rows.map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("I04_capabilityStrength"), FORCE_ENTITLEMENTS, fresh.intent)
        assertEquals(atomic("W1_requestOwner"), "A", fresh.ownerUid)
        assertEquals(atomic("W1_requestBinding"), 3L, fresh.binding)
        assertEquals(atomic("W1_requestOrigin"), newLife, fresh.raisedAt.origin)
        assertTrue(atomic("W1_requestOrder"), fresh.raisedAt.value > 21)
        assertEquals(atomic("W1_issuedOrder"), issuedOrder.value, fresh.raisedAt.value)
        assertEquals(atomic("W1_existingVerbatim"), existing.toPayloadEntry(), rows.single { it.value.id == "old" }.original.toPayloadEntry())
        assertEquals(atomic("W1_foreignVerbatim"), foreign.toPayloadEntry(), rows.single { it.value.id == "rb" }.original.toPayloadEntry())
        assertEquals(atomic("W1_rows"), 3, rows.size)
        assertArrayEquals(atomic("W1_byteArray"), byteArrayOf(0, 1, -1), after[external]) // C11
        assertEquals(atomic("W1_applied"), applied(c,
            """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"${ids.requestId}","effect":"CREATE"}"""),
            Json.parseToJsonElement(checkNotNull(after[ControlLifecycleEvidenceFixtures.evidenceKey])))
    }

    // ---- W1u: C0a on the USER axis (L412, L421: USER → FORCE_PREMIUM) ----

    @Test fun W1u_userAxisUsesPremiumLowerBound() = runReleaseTest {
        val source = intent("r", "session", "A", "USER", "u")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W1u seed") { s.data.updateData { record("A", "u", "k", listOf(source)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        val result = controlTestTimeout("W1u execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W1u_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertTrue(atomic("W1u_freshUuid"), canonicalUuid(ids.epochs.user))
        assertNull(atomic("W1u_krxNotIssued"), ids.epochs.capability)
        assertEquals(atomic("W1u_rotated"), ids.epochs.user, after[USER_EPOCH])
        assertEquals(atomic("W1u_marker"), false, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("W1u_krxAxis"), "k", after[KRX_EPOCH])
        assertEquals(atomic("W1u_krxMarker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W1u_journal"), "A|u||USER", after[PURGE_JOURNAL])
        val fresh = interpreted(after, ControlKind.DEMAND).map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("I04_userStrength"), FORCE_PREMIUM, fresh.intent)
        assertTrue(atomic("W1u_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    // ---- W2: C0b — current owner, target == null → rotate regardless of current (L413) ----

    @Test fun W2_currentOwnerNullTargetRotates() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", null)
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W2 seed") { s.data.updateData { record("A", "u", "k", listOf(source)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        val result = controlTestTimeout("W2 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W2_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertTrue(atomic("W2_freshUuid"), canonicalUuid(ids.epochs.capability))
        assertEquals(atomic("W2_rotated"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(atomic("W2_marker"), false, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W2_nullJournal"), "A|||CAPABILITY", after[PURGE_JOURNAL]) // exact null target, not the current "k"
        assertEquals(atomic("W2_newRequest"), 1, interpreted(after, ControlKind.DEMAND).count { it.value.id == ids.requestId })
        assertTrue(atomic("W2_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    // ---- W3: C0c — current owner, target ≠ current (both nonnull) → preserve, exact source journal, REQUEST (L414) ----

    @Test fun W3_differentTargetPreservesNamespace() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k0")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W3 seed") { s.data.updateData { record("A", "u", "k", listOf(source)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        assertNull(atomic("W3_noFreshCapability"), ids.epochs.capability)
        assertNull(atomic("W3_noFreshUser"), ids.epochs.user)
        val result = controlTestTimeout("W3 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W3_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertEquals(atomic("W3_owner"), "A", after[OWNER_UID])
        assertEquals(atomic("W3_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("W3_user"), "u", after[USER_EPOCH])
        assertEquals(atomic("W3_krxMarker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W3_userMarker"), true, after[MAY_CONTAIN_PREMIUM])
        assertEquals(atomic("W3_journal"), "A||k0|CAPABILITY", after[PURGE_JOURNAL])
        val fresh = interpreted(after, ControlKind.DEMAND).map { it.value }.filterIsInstance<DemandV1>().single { it.id == ids.requestId }
        assertEquals(atomic("W3_requestStrength"), FORCE_ENTITLEMENTS, fresh.intent)
        assertTrue(atomic("W3_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
    }

    // ---- W4: C0d — departed owner, target == null → preserve, historical null journal, no REQUEST (L415, L440) ----

    @Test fun W4_departedOwnerNullTargetKeepsRequests() = runReleaseTest {
        val source = intent("r", "session", "B", "CAPABILITY", null)
        val a = DemandAuthFixtures.request(id = "ra", owner = "A")
        val b = DemandAuthFixtures.request(id = "rb", owner = "B")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W4 seed") { s.data.updateData { record("A", "u", "k", listOf(source), a, b) } }
        val orders = LifecycleOrderSource(LifetimeId("unused-order-origin"), 21) // an origin check here would wrongly reject
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), orders)
        val ids = plan(c).ids
        // Skeleton v2 §2: NotRequiredDepartedOwner neither checks nor consumes the supplier.
        assertNull(atomic("W4_noOrder"), plan(c).requestOrder)
        assertNull(atomic("W4_noRequestAfter"), plan(c).requestAfter)
        assertEquals(atomic("W4_supplierUntouched"), 21L, checkNotNull(orders.issue(bindingStart = 1)).previous)
        val result = controlTestTimeout("W4 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W4_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        val rows = interpreted(after, ControlKind.DEMAND)
        assertTrue(atomic("W4_noNewRequest"), rows.none { it.value.id == ids.requestId })
        assertEquals(atomic("W4_keepA"), a.toPayloadEntry(), rows.single { it.value.id == "ra" }.original.toPayloadEntry())
        assertEquals(atomic("W4_keepB"), b.toPayloadEntry(), rows.single { it.value.id == "rb" }.original.toPayloadEntry())
        assertEquals(atomic("W4_rows"), 2, rows.size)
        assertEquals(atomic("W4_owner"), "A", after[OWNER_UID])
        assertEquals(atomic("W4_krx"), "k", after[KRX_EPOCH])
        assertEquals(atomic("W4_marker"), true, after[MAY_CONTAIN_KRX])
        assertEquals(atomic("W4_journal"), "B|||CAPABILITY", after[PURGE_JOURNAL])
        assertTrue(atomic("W4_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
        assertEquals(atomic("W4_applied"), applied(c, """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"}"""),
            Json.parseToJsonElement(checkNotNull(after[ControlLifecycleEvidenceFixtures.evidenceKey])))
    }

    // ---- W5: C0e — departed owner, nonnull target == current epoch → rejected, source preserved (L416) ----

    @Test fun W5_departedOwnerEqualTargetIsRejected() = runReleaseTest {
        val source = intent("r", "session", "B", "CAPABILITY", "k")
        val file = folder.newFile(); val s = open(file)
        val seeded = record("A", "u", "k", listOf(source))
        controlTestTimeout("W5 seed") { s.data.updateData { seeded } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
        val expected = disk(file)
        val result = controlTestTimeout("W5 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(eligible("W5_conflict"), result is ControlStoreResult.Conflict)
        assertEquals(eligible("W5_reason"), ConflictReason.TargetChanged, (result as ControlStoreResult.Conflict).reason)
        assertEquals(eligible("W5_unchanged"), expected, disk(file))
    }

    // ---- W6: C0f — nonnull target, current epoch null → RecoveryRequired(UnreadableEpochState) (L417) ----

    @Test fun W6_unreadableCurrentEpochRequiresRecovery() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "t")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W6 seed") { s.data.updateData { record("A", "u", null, listOf(source)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", null), "A"), LifecycleOrderSource(newLife, 21))
        val expected = disk(file)
        assertNull(expected[KRX_EPOCH])
        val result = controlTestTimeout("W6 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(eligible("W6_recovery"), result is ControlStoreResult.RecoveryRequired)
        assertEquals(eligible("W6_reason"), RecoveryReason.UnreadableEpochState, (result as ControlStoreResult.RecoveryRequired).reason)
        assertEquals(eligible("W6_unchanged"), expected, disk(file))
    }

    // ---- W7: common gates and the intent-only runtime slot are wired (L436 step 1–2, L449–451, skeleton v2 §3) ----

    @Test fun W7_commonGatesAndIntentSlotAreWired() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k")
        suspend fun blocked(id: String, context: AttemptContext, change: ((Preferences) -> Preferences)? = null) {
            val file = folder.newFile(); val s = open(file)
            controlTestTimeout("W7 seed") { s.data.updateData { record("A", "u", "k", listOf(source, sibling)) } }
            val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, 21))
            if (change != null) controlTestTimeout("W7 change") { s.data.updateData { change(it) } }
            val expected = disk(file)
            val result = controlTestTimeout("W7 execute") { s.control.execute(c, context) }
            assertFalse(eligible(id), result is ControlStoreResult.Confirmed)
            assertEquals(eligible(id), expected, disk(file))
        }
        val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
        blocked("W7_signOut", context(source, "A", signOut = true))
        blocked("W7_identityPending", context(source, "A", pending = true))
        blocked("W7_noIntentRuntime", context(source, "A", recovery = null))
        // Skeleton v2 §3: the holdRecovery slot is never a fallback for RECOVER_INTENT.
        blocked("W7_holdSlotNoFallback", context(source, "A", recovery = null, holdSlot = runtime(source, "A")))
        blocked("W7_fenceChanged", context(source, "A")) { it.toMutablePreferences().apply { this[KRX_EPOCH] = "k9" }.toPreferences() }
        // I03a: the fixed source row's original changed (same id, same session).
        blocked("I03a_sourceChanged", context(source, "A")) {
            it.toMutablePreferences().apply { this[intentKey] = payload(listOf(intent("r", "session", "A", "CAPABILITY", "k8"), sibling)) }.toPreferences()
        }
        // I03b: the fixed source row is absent; the same-session sibling alone is not a substitute.
        blocked("I03b_sourceAbsent", context(source, "A")) {
            it.toMutablePreferences().apply { this[intentKey] = payload(listOf(sibling)) }.toPreferences()
        }
    }

    // ---- W8: R01 — landing fails before storage, the same ref retries once with the same UUIDs and order (L437, L439) ----

    @Test fun W8_retryAfterUnlandedFailureAppliesOnce() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k")
        val file = folder.newFile(); val s = open(file)
        val before = record("A", "u", "k", listOf(source))
        controlTestTimeout("W8 seed") { s.data.updateData { before } }
        val orders = LifecycleOrderSource(newLife, 21)
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), orders)
        val ids = plan(c).ids
        val issuedOrder = checkNotNull(plan(c).requestOrder)
        s.storage.before = true
        val first = controlTestTimeout("W8 first") { s.control.execute(c, context(source, "A")) }
        assertTrue(retry("W8_first"), first is ControlStoreResult.Unconfirmed)
        assertEquals(retry("W8_unlanded"), before, disk(file))
        val second = controlTestTimeout("W8 retry") { s.control.execute(c, context(source, "A")) }
        assertTrue(retry("W8_retry"), second is ControlStoreResult.Confirmed)
        assertEquals(retry("W8_sameIds"), ids, plan(c).ids)
        val after = disk(file)
        assertEquals(retry("W8_uuidOnce"), ids.epochs.capability, after[KRX_EPOCH])
        assertEquals(retry("W8_oneRequest"), 1, interpreted(after, ControlKind.DEMAND).count { it.value.id == ids.requestId })
        assertTrue(retry("W8_sourceRemoved"), interpreted(after, ControlKind.RECOVERY_INTENT).isEmpty())
        assertEquals(retry("W8_fixedOrder"), issuedOrder, plan(c).requestOrder)
        val request = interpreted(after, ControlKind.DEMAND).single().value as DemandV1
        assertEquals(retry("W8_orderOnce"), issuedOrder.value, request.raisedAt.value)
        assertEquals(retry("W8_noReissue"), issuedOrder.value, checkNotNull(orders.issue(bindingStart = 1)).previous)
    }

    // ---- W9: C5 order boundary — supplier exhausted → no landing at all (L439 step 5, L306–312) ----

    @Test fun W9_orderExhaustedLandsNothing() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W9 seed") { s.data.updateData { record("A", "u", "k", listOf(source)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), LifecycleOrderSource(newLife, Long.MAX_VALUE))
        assertNull(atomic("W9_noOrder"), plan(c).requestOrder)
        val expected = disk(file)
        val result = controlTestTimeout("W9 execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W9_rejected"), result is ControlStoreResult.Rejected)
        assertEquals(atomic("W9_reason"), RejectionReason.InvalidRequest("OrderExhausted"), (result as ControlStoreResult.Rejected).reason)
        assertEquals(atomic("W9_unchanged"), expected, disk(file))
    }

    // ---- W9b: MAX-1 → MAX is a valid issue (the boundary itself is positive) ----

    @Test fun W9b_orderAtMaxBoundaryIsPositive() = runReleaseTest {
        val source = intent("r", "session", "A", "CAPABILITY", "k")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("W9b seed") { s.data.updateData { record("A", "u", "k", listOf(source)) } }
        val orders = LifecycleOrderSource(newLife, Long.MAX_VALUE - 1)
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k"), "A"), orders)
        assertEquals(atomic("W9b_maxIssued"), Long.MAX_VALUE, checkNotNull(plan(c).requestOrder).value)
        val result = controlTestTimeout("W9b execute") { s.control.execute(c, context(source, "A")) }
        assertTrue(atomic("W9b_confirmed"), result is ControlStoreResult.Confirmed)
        val request = interpreted(disk(file), ControlKind.DEMAND).single().value as DemandV1
        assertEquals(atomic("W9b_raisedAtMax"), Long.MAX_VALUE, request.raisedAt.value)
        assertNull(atomic("W9b_nextExhausted"), orders.issue(bindingStart = 1))
    }
}
