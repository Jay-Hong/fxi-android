package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger

/**
 * Claude-owned 5e-2 storage-scenario contract (plan v1 row 10): the RECOVER_INTENT writer through
 * the real FileStorage path for design §9.5 R03, R15a–c and R17a–c (R01 is C1 W8, R02/R12 are C8
 * P3/P4). Expected results follow the design rows and the RECOVER_HOLD precedent
 * (RecoverHoldWriterTest cancelled/successor/accumulation). Diagnostic classification checks are
 * CLASSIFICATION_ONLY and are labelled; re-application and resolve safety are the counted parts.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentStorageContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent storage cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun strip(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(id: String = "r", target: String? = "k") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private val source = intent()
    private fun closure(src: ControlNode = source) = HoldRecoveryClosure.AfterRestart(src, executor, "old-tracking", true, true)
    private fun input(src: ControlNode = source) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, closure(src))
    private fun context(src: ControlNode = source) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(src)))
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private fun record(intents: List<ControlNode> = listOf(source)): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = payload(intents)
    }.toPreferences()
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)

    // ---- T1: R03 — caller cancelled after landing; the same ref confirms and every effect stays together ----

    private suspend fun cancelled(id: String, observed: Boolean) = coroutineScope {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("$id seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        if (observed) {
            s.storage.afterScope = true
            assertTrue(retry("${id}_landedFirst"), controlTestTimeout("$id land") { s.control.execute(c, context()) } is ControlStoreResult.Unconfirmed)
        }
        val gate = ControlStoreTestStorage.Pause(); s.storage.pauseAfterScope = gate
        val caller = async { s.control.execute(c, context()) }
        try {
            controlTestTimeout("$id gate") { gate.reached.await() }
            caller.cancelAndJoinForTest()
            val tracker = ControlCommandTracking.forOwner(s.owner); val h = tracker.findPrepared(c)!!
            assertEquals(retry("${id}_unresolvedKept"), setOf(c), tracker.snapshot())
            assertTrue(retry("${id}_requestedKept"), h.confirmationRequested.get())
            assertNotNull(retry("${id}_expectedKept"), h.expectedApplied)
            assertEquals(retry("${id}_baselineKept"), BigInteger.ZERO, h.firstConfirmDiscontinuityCount)
            if (observed) assertTrue(retry("${id}_observedKept"), h.observedApplied.get())
            assertFalse(retry("${id}_notConfirmed"), h.confirmed.get())
        } finally { gate.release.complete(Unit); caller.cancelAndJoinForTest() }
        controlTestTimeout("$id close") { s.close() }
        val next = open(file); next.raw()
        val landed = strip(disk(file))
        val ids = plan(c).ids
        // Source removal, handover and Applied remain together (no partial landing).
        assertTrue(atomic("${id}_sourceRemoved"), read(landed).locations("r").isEmpty())
        assertEquals(atomic("${id}_rotated"), ids.epochs.capability, landed[KRX_EPOCH])
        assertEquals(atomic("${id}_journal"), "A||k|CAPABILITY", landed[PURGE_JOURNAL])
        assertEquals(atomic("${id}_request"), 1, read(landed).locations(ids.requestId).size)
        assertTrue(atomic("${id}_applied"), landed[ControlLifecycleEvidenceFixtures.evidenceKey]?.contains(c.id) == true)
        assertTrue(retry("${id}_confirmPrevious"), controlTestTimeout("$id previous") { next.control.confirmPrevious(c) } is ControlStoreResult.Confirmed)
        assertNull(retry("${id}_resolved"), ControlCommandTracking.forOwner(next.owner).findPrepared(c))
    }
    @Test fun T1_R03_cancelledBeforeObservation() = runReleaseTest { cancelled("T1a", observed = false) }
    @Test fun T1_R03_cancelledAfterObservation() = runReleaseTest { cancelled("T1b", observed = true) }

    // ---- T2/T3: R15a/b — a fresh query consumes the successor; the old recovery ref never re-creates it ----

    private suspend fun successor(id: String, failed: Boolean) {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("$id seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21)); s.storage.afterScope = failed
        val first = controlTestTimeout("$id first") { s.control.execute(c, context()) }
        assertTrue(retry("${id}_first"), if (failed) first is ControlStoreResult.Unconfirmed else first is ControlStoreResult.Confirmed)
        val landed = disk(file); val issued = plan(c).ids
        val r = read(landed).locations(issued.requestId).single().second.let { (it as ControlEntryRead.Interpreted).original }
        val fence = FenceV1("A", "u", issued.epochs.capability)
        val query = StartedQueryV1(fence, 5, IdentityV1("A", 2), EventOrderV1(newLife, 31), 3, RefreshIntent.FORCE_PREMIUM, 0)
        val decision = DemandAuthFixtures.decision(q = query, before = fence, after = fence, origin = newLife)
        val next = s.control.prepareSettleQuery(listOf(r), null, null, binding, decision, LifecycleOrderSource(newLife, 31))
        val runtime = DemandAuthFixtures.runtime(binding = binding, registrations = listOf(decision.registration))
        val consumed = controlTestTimeout("$id consume") { s.control.execute(next, DemandAuthFixtures.context(runtime)) }
        assertTrue(atomic("${id}_consumed"), consumed is ControlStoreResult.Confirmed)
        val after = strip(disk(file))
        assertTrue(read(after).locations(issued.requestId).isEmpty())
        val old = controlTestTimeout("$id old") { s.control.execute(c) }
        assertFalse(retry("${id}_oldNotConfirmed"), old is ControlStoreResult.Confirmed)
        assertEquals(retry("${id}_notRecreated"), after, strip(disk(file)))
        assertEquals(retry("${id}_unresolved"), if (failed) setOf(c) else emptySet<CommandRef>(), old.localUnresolvedCommands)
        // CLASSIFICATION_ONLY: the §3.4 diagnostic for a matching Applied whose postcondition is gone.
        assertEquals(retry("${id}_diagnostic"), LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, c.lastLifecycleDiagnostic?.classification)
    }
    @Test fun T2_R15a_confirmedThenConsumed() = runReleaseTest { successor("T2", failed = false) }
    @Test fun T3_R15b_landedFailedThenConsumed() = runReleaseTest { successor("T3", failed = true) }

    // ---- T4: R15c — the intent's own journal or epoch settles later; the old ref neither confirms nor re-runs ----

    private suspend fun laterEffect(id: String, change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("$id seed") { s.data.updateData { record() } }
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        s.storage.afterScope = true
        assertTrue(retry("${id}_first"), controlTestTimeout("$id first") { s.control.execute(c, context()) } is ControlStoreResult.Unconfirmed)
        val landed = strip(disk(file))
        assertTrue(atomic("${id}_landedSourceRemoved"), read(landed).locations("r").isEmpty())
        val expectedRequest = DemandAuthFixtures.request(id = ids.requestId, owner = "A", binding = 3, origin = newLife,
            intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
        val landedRequest = read(landed).locations(ids.requestId).single().second.let { (it as ControlEntryRead.Interpreted).original }
        assertEquals(atomic("${id}_landedRequest"), expectedRequest.toPayloadEntry(), landedRequest.toPayloadEntry())
        val applied = ControlAppliedEvidence.own(read(landed), c)
        assertTrue(atomic("${id}_landedAppliedKind"), applied is AppliedEvidence.Lifecycle)
        applied as AppliedEvidence.Lifecycle
        assertEquals(atomic("${id}_landedAppliedCommand"), c.id, applied.commandId)
        assertEquals(atomic("${id}_landedAppliedLifetime"), c.ownerTrackingLifetimeId.value, applied.ownerTrackingLifetimeId)
        assertEquals(atomic("${id}_landedAppliedTransition"), LifecycleTransition.RECOVER_INTENT, applied.transition)
        assertEquals(atomic("${id}_landedAppliedTargets"), listOf(LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE),
            LifecycleTarget(ControlKind.DEMAND, ids.requestId, LifecycleEffect.CREATE)), applied.targets)
        assertEquals(atomic("${id}_landedEpoch"), ids.epochs.capability, landed[KRX_EPOCH])
        assertEquals(atomic("${id}_landedMarker"), false, landed[MAY_CONTAIN_KRX])
        assertEquals(atomic("${id}_landedJournal"), "A||k|CAPABILITY", landed[PURGE_JOURNAL])
        controlTestTimeout("$id later") { s.data.updateData { cur -> cur.toMutablePreferences().apply(change).toPreferences() } }
        val settled = strip(disk(file))
        val old = controlTestTimeout("$id old") { s.control.execute(c, context()) }
        assertFalse(retry("${id}_notConfirmed"), old is ControlStoreResult.Confirmed)
        assertEquals(retry("${id}_notRerun"), settled, strip(disk(file)))
        assertEquals(retry("${id}_unresolvedKept"), setOf(c), old.localUnresolvedCommands)
    }
    @Test fun T4_R15c_journalOnly() = runReleaseTest { laterEffect("T4j") { it.remove(PURGE_JOURNAL) } }
    @Test fun T4_R15c_epochOnly() = runReleaseTest { laterEffect("T4e") { it[KRX_EPOCH] = "00000000-0000-0000-0000-000000000099" } }

    // ---- T5: R17a–c — repeated and distinct unlanded failures accumulate exactly; an unrelated command proceeds ----

    @Test fun T5_R17_accumulation() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("T5 seed") { s.data.updateData { record() } }
        val a = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        val b = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        val c = s.control.prepareRecoverIntent(input(), LifecycleOrderSource(newLife, 21))
        for (ref in listOf(a, a, b, c)) {
            s.storage.before = true
            assertTrue(retry("T5_unlanded"), controlTestTimeout("T5 failure") { s.control.execute(ref, context()) } is ControlStoreResult.Unconfirmed)
            if (ref === a) assertEquals(retry("R17a_noDuplicate"), setOf(a), ControlCommandTracking.forOwner(s.owner).snapshot())
        }
        assertEquals(retry("R17b_noEviction"), setOf(a, b, c), ControlCommandTracking.forOwner(s.owner).snapshot())
        assertTrue(retry("R17_relatedReleaseBlocked"), controlTestTimeout("T5 related release") { s.control.releaseAfterConsumption(a) } is ControlCommandReleaseResult.Rejected)
        val unrelated = s.control.prepare(s.control.addition(ControlKind.RECOVERY_INTENT) { uid ->
            set("id", ControlScalar.Text(uid)); set("sessionId", ControlScalar.Text("unrelated")); set("ownerUid", ControlScalar.Text("B"))
            set("axis", ControlScalar.Text("CAPABILITY")); set("targetEpoch", ControlScalar.Null)
        })
        val next = controlTestTimeout("T5 unrelated") { s.control.execute(unrelated) }
        assertTrue(retry("R17c_unrelatedProceeds"), next is ControlStoreResult.Confirmed)
        assertEquals(retry("R17c_othersKept"), setOf(a, b, c), next.localUnresolvedCommands)
        assertTrue(retry("R17c_unrelatedRelease"), controlTestTimeout("T5 unrelated release") { s.control.releaseAfterConsumption(unrelated) } is ControlCommandReleaseResult.Released)
        assertEquals(retry("R17c_stillKept"), setOf(a, b, c), ControlCommandTracking.forOwner(s.owner).snapshot())
        val result = controlTestTimeout("T5 one retry") { s.control.execute(a, context()) }
        assertTrue(retry("T5_oneApplies"), result is ControlStoreResult.Confirmed)
        assertEquals(retry("T5_othersRemain"), setOf(b, c), result.localUnresolvedCommands)
    }
}
