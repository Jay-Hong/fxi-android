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

/**
 * Claude-owned 5e-2 combination contract (plan v1 rows 11–12). Design §10 686 (R/N/L/HOLD/REQUEST/
 * AUTH combination, all targets' 2c/release preserved), §9.5 R13/R19 (+ order O: REQUEST confirmed
 * → a larger fresh query starts and consumes → later lifecycle confirmation), table J7 (generic
 * intent edit changes nothing), J10 (generic Add → intent removal → old Add re-confirmation never
 * re-creates the source), L445 / C9 (a stored confirmation is not purge completion, server denial,
 * seal settlement or protected admission). The R19 sequence mirrors RecoverHoldWriterTest
 * RH_R19joinedSequence with RECOVER_INTENT in place of RECOVER_HOLD. Protected admission itself is
 * outside this layer (UNWIRED); C9 here is limited to what the stored record and receipt show.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentCombinationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent combination cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun strip(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(id: String, owner: String?, target: String?, session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private val source = intent("src", "A", "k")
    private val retained = ControlObligationFixtures.node(ControlObligationFixtures.recovery) // id r, other session, null owner
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "old-tracking", true, true)
    private fun input(src: ControlNode, before: FenceV1) = RecoverIntentInput(src, before, binding, closure(src))
    private fun context(src: ControlNode) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(src)))
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private fun record(intents: List<ControlNode>): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = payload(intents)
    }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)
    private fun row(p: Preferences, id: String) = read(p).locations(id).single().second.let { (it as ControlEntryRead.Interpreted).original }
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)

    // ---- X1: R13/R19 release — a confirmed RECOVER_INTENT ref is retained, not generally releasable ----

    @Test fun X1_lifecycleRefNotGenerallyReleasable() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("X1 seed") { s.data.updateData { record(listOf(source, retained)) } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k")), LifecycleOrderSource(newLife, 21))
        assertTrue(atomic("X1_confirmed"), controlTestTimeout("X1 apply") { s.control.execute(c, context(source)) } is ControlStoreResult.Confirmed)
        val t = ControlCommandTracking.forOwner(s.owner); val h = t.findPrepared(c)!!
        assertTrue(h.confirmed.get()); assertTrue(t.snapshot().isEmpty())
        assertEquals(eligible("X1_retained"), ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertFalse(eligible("X1_notEligible"), ControlCommandReleaseEligibility.decide(c, t.lifetimeId, h, false, false) is ControlCommandReleaseEligibility.Decision.Eligible)
        assertTrue(eligible("X1_releaseRejected"), controlTestTimeout("X1 release") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("X1 close") { s.close() }
        val next = open(file); next.raw()
        controlTestTimeout("X1 2c") { next.control.reclaimPreviousLifetimeEvidence() }
        assertNotNull(retry("X1_2cRetainsApplied"), ControlAppliedEvidence.own(read(disk(file)), c))
    }

    // ---- X2: R19 + O — actual R, N, L settlements, then RECOVER_INTENT, then one larger fresh query consumes every REQUEST ----

    @Test fun X2_R19joinedSequence() = runReleaseTest {
        val id = "X2"; val file = folder.newFile(); val s = open(file)
        val rSeal = ControlObligationFixtures.node("""{"id":"R","kind":"NAMESPACE","ownerUid":"A","axis":"CAPABILITY","epoch":"retired-k"}""")
        val nSeal = ControlObligationFixtures.node("""{"id":"N","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER"}""")
        val lSeal = ControlObligationFixtures.node("""{"id":"L","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"CAPABILITY"}""")
        val other = DemandAuthFixtures.request(id = "other", owner = "B", intent = RefreshIntent.FORCE_PREMIUM)
        val seeded = record(listOf(source, retained)).toMutablePreferences().apply {
            this[sealKey] = payload(listOf(rSeal, nSeal, lSeal)); this[demandKey] = payload(listOf(other))
        }.toPreferences()
        assertFalse(read(seeded).hasUninterpretable); assertFalse(read(seeded).hasUninterpretableMetadata)
        controlTestTimeout("$id seed") { s.data.updateData { seeded } }
        val ctx = AttemptContext("A", 3, newLife, false, false)
        val r = s.control.prepareRetiredNamespaceSettlement(rSeal, FenceV1("A", "u", "k"), executor,
            SettlementDemand("A", 3, EventOrderV1(newLife, 10), RefreshIntent.FORCE_PREMIUM))
        assertTrue(atomic("${id}_R"), controlTestTimeout("$id R") { s.control.execute(r, ctx) } is ControlStoreResult.Confirmed)
        val rId = (r.body as ControlCommandBody.SettleRetiredNamespace).input.demandId!!
        val rRequest = row(disk(file), rId)
        val n = s.control.prepareCurrentNullSettlement(listOf(nSeal), read(disk(file)), FenceV1("A", "u", "k"), executor,
            SettlementDemand("A", 3, EventOrderV1(newLife, 11), RefreshIntent.FORCE_PREMIUM))
        val nInput = (n.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertTrue(atomic("${id}_N"), controlTestTimeout("$id N") { s.control.execute(n, ctx) } is ControlStoreResult.Confirmed)
        val nId = nInput.demandId; val newUser = nInput.newUserEpoch!!
        val nRequest = row(disk(file), nId)
        val l = s.control.prepareRetiredNullSettlement(listOf(lSeal), FenceV1("A", newUser, "k"), executor)
        assertTrue(atomic("${id}_L"), controlTestTimeout("$id L") { s.control.execute(l, ctx) } is ControlStoreResult.Confirmed)
        val seals = disk(file)[sealKey]!!
        // RECOVER_INTENT on the post-R/N/L namespace (C0a on CAPABILITY).
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", newUser, "k")), LifecycleOrderSource(newLife, 21))
        val ids = plan(c).ids
        assertTrue(atomic("${id}_intent"), controlTestTimeout("$id intent") { s.control.execute(c, context(source)) } is ControlStoreResult.Confirmed)
        val recovered = disk(file)
        assertEquals(atomic("${id}_sealsKept"), seals, recovered[sealKey])
        assertEquals(atomic("${id}_otherIntentKept"), payload(listOf(retained)), recovered[intentKey])
        assertEquals(atomic("${id}_rRequestKept"), rRequest.toPayloadEntry(), row(recovered, rId).toPayloadEntry())
        assertEquals(atomic("${id}_nRequestKept"), nRequest.toPayloadEntry(), row(recovered, nId).toPayloadEntry())
        assertEquals(atomic("${id}_otherOwnerKept"), other.toPayloadEntry(), row(recovered, "other").toPayloadEntry())
        val recoveryRequest = row(recovered, ids.requestId)
        assertEquals(atomic("${id}_requestOrder"), 22L, demand(recoveryRequest)?.raisedAt?.value)
        assertEquals(atomic("${id}_requestStrength"), RefreshIntent.FORCE_ENTITLEMENTS, demand(recoveryRequest)?.intent)
        // O: the REQUEST is confirmed before a larger fresh query (order 31) starts and consumes it.
        val fence = FenceV1("A", newUser, ids.epochs.capability)
        val query = StartedQueryV1(fence, 5, IdentityV1("A", 2), EventOrderV1(newLife, 31), 3, RefreshIntent.FORCE_PREMIUM, 0)
        val decision = DemandAuthFixtures.decision(q = query, before = fence, after = fence, origin = newLife)
        val consume = s.control.prepareSettleQuery(listOf(rRequest, nRequest, recoveryRequest), null, null, binding, decision, LifecycleOrderSource(newLife, 31))
        val runtime = DemandAuthFixtures.runtime(binding = binding, registrations = listOf(decision.registration))
        assertTrue(atomic("${id}_consume"), controlTestTimeout("$id consume") { s.control.execute(consume, DemandAuthFixtures.context(runtime)) } is ControlStoreResult.Confirmed)
        val consumed = strip(disk(file))
        for (requestId in listOf(rId, nId, ids.requestId)) assertTrue(atomic("${id}_consumed"), read(consumed).locations(requestId).isEmpty())
        assertEquals(atomic("${id}_sealsAfterConsume"), seals, consumed[sealKey])
        assertEquals(atomic("${id}_intentAfterConsume"), payload(listOf(retained)), consumed[intentKey])
        assertEquals(atomic("${id}_otherOwnerAfterConsume"), other.toPayloadEntry(), row(consumed, "other").toPayloadEntry())
        assertFalse(retry("${id}_oldLifecycle"), controlTestTimeout("$id old lifecycle") { s.control.execute(c) } is ControlStoreResult.Confirmed)
        assertEquals(retry("${id}_notRerun"), consumed, strip(disk(file)))
        for (old in listOf(r, n, l)) assertTrue(retry("${id}_settlementWitness"), controlTestTimeout("$id old settlement") { s.control.execute(old) } is ControlStoreResult.Confirmed)
        assertTrue(retry("${id}_releaseDenied"), controlTestTimeout("$id release") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("$id close") { s.close() }
        val next = open(file); next.raw()
        controlTestTimeout("$id 2c") { next.control.reclaimPreviousLifetimeEvidence() }
        val snapshot = disk(file)
        val kept = read(snapshot)
        assertNotNull(retry("${id}_2cIntent"), ControlAppliedEvidence.own(kept, c))
        assertNotNull(retry("${id}_2cConsume"), ControlAppliedEvidence.own(kept, consume))
        assertEquals(retry("${id}_2cSeals"), seals, snapshot[sealKey])
        assertEquals(retry("${id}_2cOtherIntent"), payload(listOf(retained)), snapshot[intentKey])
        assertEquals(retry("${id}_2cOtherOwner"), other.toPayloadEntry(), row(snapshot, "other").toPayloadEntry())
    }

    // ---- X3: J10 — generic Add → RECOVER_INTENT removes it → the old Add never re-creates the source ----

    @Test fun X3_J10_oldAddDoesNotRecreate() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("X3 seed") { s.data.updateData { record(listOf(retained)) } }
        val add = s.control.prepare(s.control.addition(ControlKind.RECOVERY_INTENT) { uid ->
            set("id", ControlScalar.Text(uid)); set("sessionId", ControlScalar.Text("session")); set("ownerUid", ControlScalar.Text("A"))
            set("axis", ControlScalar.Text("CAPABILITY")); set("targetEpoch", ControlScalar.Text("k"))
        })
        assertTrue(controlTestTimeout("X3 add") { s.control.execute(add) } is ControlStoreResult.Confirmed)
        val added = read(disk(file)).arrays.getValue(ControlKind.RECOVERY_INTENT).entries
            .filterIsInstance<ControlEntryRead.Interpreted>().single { it.value.id != "r" }.original
        val c = s.control.prepareRecoverIntent(input(added, FenceV1("A", "u", "k")), LifecycleOrderSource(newLife, 21))
        assertTrue(atomic("X3_recovered"), controlTestTimeout("X3 recover") { s.control.execute(c, context(added)) } is ControlStoreResult.Confirmed)
        val afterRecovery = strip(disk(file))
        val addedId = checkNotNull(ControlSchema.read(ControlKind.RECOVERY_INTENT, added)).id
        assertTrue(atomic("X3_removed"), read(afterRecovery).locations(addedId).isEmpty())
        controlTestTimeout("X3 old add") { s.control.execute(add) }
        assertEquals(retry("J10_notRecreated"), afterRecovery, strip(disk(file)))
    }

    // ---- X4: J7 — a generic edit cannot change an intent's original ----

    @Test fun X4_J7_genericEditRejected() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("X4 seed") { s.data.updateData { record(listOf(source)) } }
        val seeded = strip(disk(file))
        val edit = s.control.prepare(s.control.edit(ControlKind.RECOVERY_INTENT, source) { set("targetEpoch", ControlScalar.Text("k8")) })
        val result = controlTestTimeout("X4 edit") { s.control.execute(edit) }
        assertFalse(eligible("J7_editNotConfirmed"), result is ControlStoreResult.Confirmed)
        assertEquals(eligible("J7_unchanged"), seeded, strip(disk(file)))
    }

    // ---- X5: C9 — the stored confirmation is not purge completion, seal settlement or a settlement receipt (L445) ----

    @Test fun X5_C9_confirmationIsNotCompletion() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        val seals = "[" + ControlObligationFixtures.seal + "]"
        controlTestTimeout("X5 seed") { s.data.updateData { record(listOf(source)).toMutablePreferences().apply { this[sealKey] = seals }.toPreferences() } }
        val c = s.control.prepareRecoverIntent(input(source, FenceV1("A", "u", "k")), LifecycleOrderSource(newLife, 21))
        val result = controlTestTimeout("X5 apply") { s.control.execute(c, context(source)) }
        assertTrue(atomic("X5_confirmed"), result is ControlStoreResult.Confirmed)
        val confirmed = result as ControlStoreResult.Confirmed
        assertNull(atomic("C9_noSettlementReceipt"), confirmed.settlement)
        assertNull(atomic("C9_noHandoverReceipt"), confirmed.handoverSettlement)
        val after = disk(file)
        assertEquals(atomic("C9_purgePending"), "A||k|CAPABILITY", after[PURGE_JOURNAL]) // purge still owed
        assertEquals(atomic("C9_sealNotSettled"), seals, after[sealKey])
        assertEquals(atomic("C9_markerStillOwed"), false, after[MAY_CONTAIN_KRX])
    }
}
