package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F

import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.buffer
import okio.source
import java.io.File
import java.math.BigInteger
/** Independent fixtures; official execution is performed outside this implementation task. */
class RecoverHoldWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("hold cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun plan(c: CommandRef) = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverHold)
    private suspend fun exactWriter(id: String) {
        val file = folder.newFile(); val s = open(file); val input = F.input(); val before = F.before(input)
        F.assertFixture(input, before)
        controlTestTimeout("hold seed") { s.data.updateData { before } }
        val orders = LifecycleOrderSource(F.life, 21)
        val c = s.control.prepareRecoverHold(input, orders)
        val ids = plan(c).ids
        val result = controlTestTimeout("hold writer") { s.control.execute(c, F.context(input)) }
        val after = F.stripBarrier(disk(file))
        // Entire wire and values are literal fixture facts; only issuance identities are read from prepare.
        assertEquals(F.atomic(id), F.candidate(c, ids), after)
        assertTrue(F.atomic(id), result is ControlStoreResult.Confirmed)
        result as ControlStoreResult.Confirmed
        assertSame(F.atomic(id), c, result.command)
        assertEquals(F.atomic(id), ConfirmedEffect.AppliedThisAttempt, result.effect)
        assertEquals(F.atomic(id), listOf("h", ids.requestId, "g"), result.effectiveIds)
        assertEquals(F.atomic(id), listOf("h"), result.lifecycleReceipt?.removedIds)
        assertEquals(F.atomic(id), listOf(ids.requestId), result.lifecycleReceipt?.createdIds)
        assertEquals(F.atomic(id), listOf("g"), result.lifecycleReceipt?.replacedIds)
        assertEquals(F.atomic(id), after, F.stripBarrier(result.snapshot.record.original))
        assertEquals(F.atomic(id), 22L, orders.issue(1)?.previous)
    }
    private suspend fun storage(id: String, afterFailure: Boolean, restart: Boolean = false) {
        val file = folder.newFile(); val s = open(file); val input = F.input(); val before = F.before(input)
        F.assertFixture(input, before)
        controlTestTimeout("hold retry seed") { s.data.updateData { before } }
        val orders = LifecycleOrderSource(F.life, 21); val c = s.control.prepareRecoverHold(input, orders)
        val fixed = plan(c); val ids = fixed.ids; val order = fixed.requestOrder
        if (afterFailure) s.storage.afterScope = true else s.storage.before = true
        val failed = controlTestTimeout("hold failure") { s.control.execute(c, F.context(input)) }
        assertTrue(F.retry(id), failed is ControlStoreResult.Unconfirmed)
        assertSame(F.retry(id), c, failed.command)
        val tracker = ControlCommandTracking.forOwner(s.owner); val history = tracker.findPrepared(c)!!
        assertEquals(F.retry(id), BigInteger.ZERO, history.firstConfirmDiscontinuityCount)
        assertTrue(F.retry(id), history.confirmationRequested.get())
        assertEquals(F.retry(id), setOf(c), failed.localUnresolvedCommands)
        assertEquals(F.atomic(id), if (afterFailure) F.candidate(c, ids) else before, F.stripBarrier(disk(file)))
        if (restart) {
            controlTestTimeout("close previous owner") { s.close() }
            val next = open(file); next.raw()
            val result = controlTestTimeout("previous hold confirmation") { next.control.confirmPrevious(c) }
            assertTrue(F.retry(id), result is ControlStoreResult.Confirmed)
            assertNull(F.retry(id), ControlCommandTracking.forOwner(next.owner).findPrepared(c))
        } else {
            val result = controlTestTimeout("same ref retry") { s.control.execute(c, F.context(input)) }
            assertTrue(F.retry(id), result is ControlStoreResult.Confirmed)
            assertSame(F.retry(id), c, result.command)
            assertEquals(F.retry(id), ids, plan(c).ids); assertEquals(F.retry(id), order, plan(c).requestOrder)
        }
        assertEquals(F.retry(id), F.candidate(c, ids), F.stripBarrier(disk(file)))
        assertEquals(F.retry(id), 22L, orders.issue(1)?.previous)
    }
    private suspend fun cancelled(id: String, observed: Boolean = false) = coroutineScope {
        val file = folder.newFile(); val s = open(file); val input = F.input(); val before = F.before(input)
        F.assertFixture(input, before)
        controlTestTimeout("hold cancel seed") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(F.life, 21))
        if (observed) {
            s.storage.afterScope = true
            assertTrue(controlTestTimeout("hold land before cancel") { s.control.execute(c, F.context(input)) } is ControlStoreResult.Unconfirmed)
        }
        val gate = ControlStoreTestStorage.Pause(); s.storage.pauseAfterScope = gate
        val caller = async { s.control.execute(c, F.context(input)) }
        try {
            controlTestTimeout("hold cancel gate") { gate.reached.await() }
            caller.cancelAndJoinForTest()
            val tracker = ControlCommandTracking.forOwner(s.owner); val h = tracker.findPrepared(c)!!
            assertEquals(F.retry(id), setOf(c), tracker.snapshot())
            assertTrue(F.retry(id), h.confirmationRequested.get()); assertNotNull(F.retry(id), h.expectedApplied)
            assertEquals(F.retry(id), BigInteger.ZERO, h.firstConfirmDiscontinuityCount)
            if (observed) assertTrue(F.retry(id), h.observedApplied.get())
            assertFalse(F.retry(id), h.confirmed.get())
        } finally { gate.release.complete(Unit); caller.cancelAndJoinForTest() }
        controlTestTimeout("hold cancel close") { s.close() }
        val next = open(file); next.raw()
        assertEquals(F.atomic(id), F.candidate(c, plan(c).ids), F.stripBarrier(disk(file)))
        assertTrue(F.retry(id), controlTestTimeout("hold cancel previous") { next.control.confirmPrevious(c) } is ControlStoreResult.Confirmed)
        assertNull(ControlCommandTracking.forOwner(next.owner).findPrepared(c))
    }
    private suspend fun successor(id: String, failed: Boolean) {
        val file=folder.newFile(); val s=open(file)
        // No floor/AUTH is needed for the successor-consumption scenario.
        val i=F.input(F.hold(floor=false),null); val before=F.before(i)
        assertNull((ControlSchema.read(ControlKind.HOLD,i.source) as RestoredHold).floor)
        controlTestTimeout("successor seed") { s.data.updateData { before } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21)); s.storage.afterScope=failed
        val first=controlTestTimeout("successor first") { s.control.execute(c,F.context(i)) }
        assertTrue(if(failed) first is ControlStoreResult.Unconfirmed else first is ControlStoreResult.Confirmed)
        val landed=disk(file); val issued=plan(c).ids
        val r=F.row(landed,ControlKind.DEMAND,issued.requestId)
        assertEquals(22L,demand(r)!!.raisedAt.value)
        val fence=FenceV1("A","u",issued.epochs.capability)
        val query=StartedQueryV1(fence,5,IdentityV1("A",2),EventOrderV1(F.life,31),3,RefreshIntent.FORCE_PREMIUM,0)
        val decision=DemandAuthFixtures.decision(q=query,before=fence,after=fence,origin=F.life)
        val next=s.control.prepareSettleQuery(listOf(r),null,null,F.binding,decision,LifecycleOrderSource(F.life,31))
        val runtime=DemandAuthFixtures.runtime(binding=F.binding,registrations=listOf(decision.registration))
        val consumed=controlTestTimeout("fresh query consumes successor") { s.control.execute(next,DemandAuthFixtures.context(runtime)) }
        assertTrue(consumed is ControlStoreResult.Confirmed)
        val after=F.stripBarrier(disk(file))
        assertTrue(F.read(after).locations(issued.requestId).isEmpty())
        val old=controlTestTimeout("old recovery after consumption") { s.control.execute(c) }
        assertFalse(F.retry(id),old is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id),after,F.stripBarrier(disk(file)))
        assertEquals(F.retry(id),if(failed) setOf(c) else emptySet<CommandRef>(),old.localUnresolvedCommands)
        assertEquals(F.retry(id),LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE,c.lastLifecycleDiagnostic?.classification)
    }
    private suspend fun accumulation(id: String) {
        val file=folder.newFile(); val s=open(file); val i=F.input(); val before=F.before(i)
        F.assertFixture(i,before)
        controlTestTimeout("accumulation seed") { s.data.updateData { before } }
        val a=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val b=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        for(ref in listOf(a,a,b,c)) {
            s.storage.before=true
            assertTrue(controlTestTimeout("accumulation failure") { s.control.execute(ref,F.context(i)) } is ControlStoreResult.Unconfirmed)
            if(ref===a) assertEquals(F.retry(id),setOf(a),ControlCommandTracking.forOwner(s.owner).snapshot())
        }
        assertEquals(F.retry(id),setOf(a,b,c),ControlCommandTracking.forOwner(s.owner).snapshot())
        assertTrue(F.retry(id),controlTestTimeout("related release blocked") { s.control.releaseAfterConsumption(a) } is ControlCommandReleaseResult.Rejected)
        val unrelated=s.control.prepare(s.control.addition(ControlKind.RECOVERY_INTENT) { uid ->
            set("id",ControlScalar.Text(uid));set("sessionId",ControlScalar.Text("unrelated"));set("ownerUid",ControlScalar.Text("B"))
            set("axis",ControlScalar.Text("CAPABILITY"));set("targetEpoch",ControlScalar.Null)
        })
        val next=controlTestTimeout("unrelated next") { s.control.execute(unrelated) }
        assertTrue(F.retry(id),next is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id),setOf(a,b,c),next.localUnresolvedCommands)
        assertTrue(F.retry(id),controlTestTimeout("unrelated release") { s.control.releaseAfterConsumption(unrelated) } is ControlCommandReleaseResult.Released)
        assertEquals(F.retry(id),setOf(a,b,c),ControlCommandTracking.forOwner(s.owner).snapshot())
        assertEquals(F.retry(id),LifecycleClassification.NO_LANDING_PROOF,a.lastLifecycleDiagnostic?.classification)
        val result=controlTestTimeout("one fixed retry") { s.control.execute(a,F.context(i)) }
        assertTrue(F.retry(id),result is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id),setOf(b,c),result.localUnresolvedCommands)
    }
    private suspend fun diagnostic(id: String,lost: Boolean) {
        val file=folder.newFile(); val s=open(file); val i=F.input(); val before=F.before(i)
        F.assertFixture(i,before)
        controlTestTimeout("diagnostic seed") { s.data.updateData { before } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        if(lost) {
            assertTrue(controlTestTimeout("diagnostic first") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
            controlTestTimeout("diagnostic remove own") { s.data.updateData { it.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey]="[]" } } }
        }
        val r=controlTestTimeout("diagnostic observation") { if(lost) s.control.execute(c) else s.control.execute(c,F.context(i).copy(binding=999)) }
        assertTrue(if(lost) r is ControlStoreResult.RecoveryRequired else r is ControlStoreResult.Conflict)
        assertEquals(F.retry(id),if(lost) LifecycleClassification.EVIDENCE_UNAVAILABLE else LifecycleClassification.PRECONDITION_CHANGED_WITHOUT_LANDING_PROOF,c.lastLifecycleDiagnostic?.classification)
    }
    @Test fun RH_dispatch() {
        runReleaseTest {
        exactWriter("RH_dispatch")
        }
    }

    @Test fun RH_buildHoldRemove() {
        runReleaseTest {
        exactWriter("RH_buildHoldRemove")
        }
    }

    @Test fun RH_buildRequestCreate() {
        runReleaseTest {
        exactWriter("RH_buildRequestCreate")
        }
    }

    @Test fun RH_buildGuardReplace() {
        runReleaseTest {
        exactWriter("RH_buildGuardReplace")
        }
    }

    @Test fun RH_buildKrxEpoch() {
        runReleaseTest {
        exactWriter("RH_buildKrxEpoch")
        }
    }

    @Test fun RH_buildKrxMarker() {
        runReleaseTest {
        exactWriter("RH_buildKrxMarker")
        }
    }

    @Test fun RH_buildJournal() {
        runReleaseTest {
        exactWriter("RH_buildJournal")
        }
    }

    @Test fun RH_buildEvidenceCommand() {
        runReleaseTest {
        exactWriter("RH_buildEvidenceCommand")
        }
    }

    @Test fun RH_buildEvidenceLifetime() {
        runReleaseTest {
        exactWriter("RH_buildEvidenceLifetime")
        }
    }

    @Test fun RH_buildEvidenceTransition() {
        runReleaseTest {
        exactWriter("RH_buildEvidenceTransition")
        }
    }

    @Test fun RH_buildEvidenceOrder() {
        runReleaseTest {
        exactWriter("RH_buildEvidenceOrder")
        }
    }

    @Test fun RH_outputEffect() {
        runReleaseTest {
        exactWriter("RH_outputEffect")
        }
    }

    @Test fun RH_outputIds() {
        runReleaseTest {
        exactWriter("RH_outputIds")
        }
    }

    @Test fun RH_eligibilityCall() {
        runReleaseTest {
        val file = folder.newFile(); val s = open(file); val i = F.input(); val before = F.before(i)
        F.assertFixture(i, before)
        controlTestTimeout("closed gate seed") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(i, LifecycleOrderSource(F.life,21))
        val bad = F.context(i, F.runtime(i.closure, closed = false))
        val result = controlTestTimeout("closed gate writer") { s.control.execute(c, bad) }
        assertFalse(F.eligible("RH_eligibilityCall"), result is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_eligibilityCall"), before, F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_R01() {
        runReleaseTest {
        storage("RH_R01", false)
        }
    }

    @Test fun RH_R02() {
        runReleaseTest {
        storage("RH_R02", true)
        }
    }

    @Test fun RH_R12() {
        runReleaseTest {
        storage("RH_R12", true, true)
        }
    }

    @Test fun RH_R03() {
        runReleaseTest {
        cancelled("RH_R03")
        }
    }

    @Test fun RH_R03observed() {
        runReleaseTest {
        cancelled("RH_R03observed", true)
        }
    }

    @Test fun RH_R03expected() {
        runReleaseTest {
        cancelled("RH_R03expected")
        }
    }

    @Test fun RH_R03requested() {
        runReleaseTest {
        cancelled("RH_R03requested")
        }
    }

    @Test fun RH_R03baseline() {
        runReleaseTest {
        cancelled("RH_R03baseline")
        }
    }

    @Test fun RH_R01reset() {
        runReleaseTest {
        val file = folder.newFile(); val s = open(file); val i = F.input(); val before = F.before(i)
        F.assertFixture(i,before)
        controlTestTimeout("baseline seed") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val t = ControlCommandTracking.forOwner(s.owner); val h = t.findPrepared(c)!!
        s.storage.before = true
        assertTrue(controlTestTimeout("baseline first") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Unconfirmed)
        assertEquals(BigInteger.ZERO,h.firstConfirmDiscontinuityCount)
        assertTrue(controlTestTimeout("baseline land") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val landed = F.stripBarrier(disk(file))
        controlTestTimeout("baseline schema1") { s.data.updateData { ControlLifecycleEvidenceFixtures.raw(schema=1) } }
        assertTrue(controlTestTimeout("baseline observe") { s.control.execute(c,F.context(i)) } is ControlStoreResult.RecoveryRequired)
        assertEquals(BigInteger.ONE,t.evidenceDiscontinuityCount)
        controlTestTimeout("baseline restore") { s.data.updateData { landed } }
        assertTrue(controlTestTimeout("baseline confirm") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        assertEquals(F.retry("RH_R01reset"),BigInteger.ZERO,h.firstConfirmDiscontinuityCount)
        assertEquals(F.retry("RH_R01reset"),landed,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_R15a() {
        runReleaseTest {
        successor("RH_R15a",false)
        }
    }

    @Test fun RH_R15b() {
        runReleaseTest {
        successor("RH_R15b",true)
        }
    }

    @Test fun RH_R15post() {
        runReleaseTest {
        successor("RH_R15post",false)
        }
    }

    @Test fun RH_R17() {
        runReleaseTest {
        accumulation("RH_R17")
        }
    }

    @Test fun RH_R17eviction() {
        runReleaseTest {
        accumulation("RH_R17eviction")
        }
    }

    @Test fun RH_R17gate() {
        runReleaseTest {
        accumulation("RH_R17gate")
        }
    }

    @Test fun RH_R18a() {
        runReleaseTest {
        successor("RH_R18a",true)
        }
    }

    @Test fun RH_R18b() {
        runReleaseTest {
        accumulation("RH_R18b")
        }
    }

    @Test fun RH_R18c() {
        runReleaseTest {
        diagnostic("RH_R18c",true)
        }
    }

    @Test fun RH_R18d() {
        runReleaseTest {
        diagnostic("RH_R18d",false)
        }
    }

    @Test fun RH_wireKind() {
        runReleaseTest {
        exactWriter("RH_wireKind")
        }
    }

    @Test fun RH_wireTargetKind() {
        runReleaseTest {
        exactWriter("RH_wireTargetKind")
        }
    }

    @Test fun RH_wireTargetId() {
        runReleaseTest {
        exactWriter("RH_wireTargetId")
        }
    }

    @Test fun RH_wireTargetEffect() {
        runReleaseTest {
        exactWriter("RH_wireTargetEffect")
        }
    }

    @Test fun RH_journalExact() {
        runReleaseTest {
        val i=F.input();val raw=F.before(i).toMutablePreferences().apply { this[PURGE_JOURNAL]="A||k|CAPABILITY" }.toPreferences()
        F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("exact journal seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("exact journal writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_journalExact"),result is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_journalExact"),"A||k|CAPABILITY",after[PURGE_JOURNAL])
        assertTrue(F.atomic("RH_journalExact"),F.read(after).locations("h").isEmpty())
        assertNotNull(F.atomic("RH_journalExact"),demand(F.row(after,ControlKind.DEMAND,plan(c).ids.requestId)))
        }
    }

    @Test fun RH_journalBroad() {
        runReleaseTest {
        val i=F.input();val raw=F.before(i).toMutablePreferences().apply { this[PURGE_JOURNAL]="A|u|k|CAPABILITY,USER" }.toPreferences()
        F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("exact journal seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("exact journal writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_journalBroad"),result is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_journalBroad"),"A|u|k|CAPABILITY,USER\nA||k|CAPABILITY",after[PURGE_JOURNAL])
        assertTrue(F.atomic("RH_journalBroad"),F.read(after).locations("h").isEmpty())
        assertNotNull(F.atomic("RH_journalBroad"),demand(F.row(after,ControlKind.DEMAND,plan(c).ids.requestId)))
        }
    }

    @Test fun RH_R01epoch() {
        runReleaseTest {
        storage("RH_R01epoch",false)
        }
    }

    @Test fun RH_R01requestId() {
        runReleaseTest {
        storage("RH_R01requestId",false)
        }
    }

    @Test fun RH_R01order() {
        runReleaseTest {
        storage("RH_R01order",false)
        }
    }

    @Test fun RH_floorLeft() {
        runReleaseTest {
        val h=F.hold(); val b=F.binding; val i=F.input(h,F.guard(50000),F.beforeFence,b).copy(mergeNow=F.now)
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_floorLeft"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_floorLeft"),FloorV1("boot",F.now.elapsedMillis,49000L,F.life),g.floor)
        assertTrue(F.atomic("RH_floorLeft"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_floorLeft"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_floorUnknown() {
        runReleaseTest {
        val h=F.field(F.hold(),"floor",FloorGuardFixtures.floor(30000,null,10000,"life")); val b=F.binding; val i=F.input(h,null,F.beforeFence,b).copy(mergeNow=F.now)
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_floorUnknown"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_floorUnknown"),FloorV1("boot",F.now.elapsedMillis,30000L,F.life),g.floor)
        assertTrue(F.atomic("RH_floorUnknown"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_floorUnknown"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_floorDifferent() {
        runReleaseTest {
        val h=F.field(F.hold(),"floor",FloorGuardFixtures.floor(30000,"previous-boot",10000,"life")); val b=F.binding; val i=F.input(h,null,F.beforeFence,b).copy(mergeNow=F.now)
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_floorDifferent"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_floorDifferent"),FloorV1("boot",F.now.elapsedMillis,30000L,F.life),g.floor)
        assertTrue(F.atomic("RH_floorDifferent"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_floorDifferent"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_floorReverse() {
        runReleaseTest {
        val h=F.field(F.hold(),"floor",FloorGuardFixtures.floor(30000,"boot",12000,"life")); val b=F.binding; val i=F.input(h,null,F.beforeFence,b).copy(mergeNow=F.now)
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_floorReverse"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_floorReverse"),FloorV1("boot",F.now.elapsedMillis,30000L,F.life),g.floor)
        assertTrue(F.atomic("RH_floorReverse"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_floorReverse"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_floorZero() {
        runReleaseTest {
        val h=F.field(F.hold(),"floor",FloorGuardFixtures.floor(30000,"boot",0,"life")); val b=F.binding; val i=F.input(h,null,F.beforeFence,b).copy(mergeNow=BootReading("boot",30000))
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_floorZero"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_floorZero"),FloorV1("boot",BootReading("boot",30000).elapsedMillis,0L,F.life),g.floor)
        assertTrue(F.atomic("RH_floorZero"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_floorZero"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_nullOwner() {
        runReleaseTest {
        val h=F.hold(owner=null); val b=F.binding.copy(executor=F.executor.copy(ownerUid=null),identity=null); val i=F.input(h,null,FenceV1(null,"u","k"),b).copy(mergeNow=F.now)
        val source=ControlSchema.read(ControlKind.HOLD,h) as RestoredHold
        assertEquals(setOf(PurgeScope.CAPABILITY),source.axes); assertNotNull(source.floor)
        val file=folder.newFile(); val s=open(file); val raw=F.before(i)
        assertFalse(F.read(raw).hasUninterpretable)
        controlTestTimeout("floor matrix seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("floor matrix writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_nullOwner"),result is ControlStoreResult.Confirmed)
        val after=F.read(disk(file)); val guardId=i.guard?.let { guard(it)!!.id } ?: plan(c).ids.guardId
        val g=guard(F.row(after.original,ControlKind.DEMAND,guardId))!!
        assertEquals(F.atomic("RH_nullOwner"),FloorV1("boot",F.now.elapsedMillis,29000L,F.life),g.floor)
        assertTrue(F.atomic("RH_nullOwner"),after.locations("h").isEmpty())
        assertEquals(F.atomic("RH_nullOwner"),i.guard?.toPayloadEntry()?.fields?.get("auth"),F.row(after.original,ControlKind.DEMAND,guardId).toPayloadEntry().fields["auth"])
        }
    }

    @Test fun RH_noFloorPreservesGuard() {
        runReleaseTest {
        val i=F.input(F.hold(floor=false),F.guard(0)); val raw=F.before(i)
        assertNull((ControlSchema.read(ControlKind.HOLD,i.source) as RestoredHold).floor)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("no floor seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val result=controlTestTimeout("no floor writer") { s.control.execute(c,F.context(i)) }
        assertTrue(F.atomic("RH_noFloorPreservesGuard"),result is ControlStoreResult.Confirmed)
        assertEquals(F.atomic("RH_noFloorPreservesGuard"),i.guard!!.toPayloadEntry().fields.toString(),F.row(disk(file),ControlKind.DEMAND,"g").toPayloadEntry().fields.toString())
        assertEquals(F.atomic("RH_noFloorPreservesGuard"),listOf("h",plan(c).ids.requestId),(c.body as ControlCommandBody.Lifecycle).input.targets.map { it.target.id })
        }
    }

    @Test fun RH_orderExhaustion() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("exhaustion seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,Long.MAX_VALUE))
        assertNotNull(plan(c).preparationProblem)
        val r=controlTestTimeout("exhaustion attempt") { s.control.execute(c,F.context(i)) }
        assertFalse(F.atomic("RH_orderExhaustion"),r is ControlStoreResult.Confirmed)
        assertEquals(F.atomic("RH_orderExhaustion"),raw,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateOwner() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(ownerUid="B")) }
        assertFalse(F.eligible("RH_writerGateOwner"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateOwner"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateBinding() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(binding=4)) }
        assertFalse(F.eligible("RH_writerGateBinding"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateBinding"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateOrigin() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(originLifetimeId=LifetimeId("other"))) }
        assertFalse(F.eligible("RH_writerGateOrigin"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateOrigin"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateSignOut() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(signOutOpen=true)) }
        assertFalse(F.eligible("RH_writerGateSignOut"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateSignOut"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGatePending() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(identityPersistencePending=true)) }
        assertFalse(F.eligible("RH_writerGatePending"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGatePending"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateTeardown() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw.toMutablePreferences().apply { this[TEARDOWN_OWED_FOR]="A" }.toPreferences()
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i)) }
        assertFalse(F.eligible("RH_writerGateTeardown"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateTeardown"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateUserEpoch() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw.toMutablePreferences().apply { this[USER_EPOCH]="changed" }.toPreferences()
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i)) }
        assertFalse(F.eligible("RH_writerGateUserEpoch"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateUserEpoch"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateKrxEpoch() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw.toMutablePreferences().apply { this[KRX_EPOCH]="changed" }.toPreferences()
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i)) }
        assertFalse(F.eligible("RH_writerGateKrxEpoch"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateKrxEpoch"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_writerGateRuntimeMissing() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i); F.assertFixture(i,raw)
        val file=folder.newFile(); val s=open(file)
        val actual=raw
        controlTestTimeout("gate seed") { s.data.updateData { actual } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        val r=controlTestTimeout("gate attempt") { s.control.execute(c,F.context(i).copy(holdRecovery=null)) }
        assertFalse(F.eligible("RH_writerGateRuntimeMissing"),r is ControlStoreResult.Confirmed)
        assertEquals(F.eligible("RH_writerGateRuntimeMissing"),actual,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_sealsAndIntentPreserved() {
        runReleaseTest {
        val i=F.input(); val initial=F.before(i)
        val seals="["+ControlObligationFixtures.seal.replace("\"s\"","\"R\"")+","+ControlObligationFixtures.nullSeal.replace("\"s\"","\"N\"")+","+ControlObligationFixtures.settledSeal.replace("\"s\"","\"L\"")+"]"
        val intents="["+ControlObligationFixtures.recovery+"]"
        val raw=initial.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]=seals; this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents }.toPreferences()
        assertFalse(F.read(raw).hasUninterpretable)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("retained obligations seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(F.atomic("RH_sealsAndIntentPreserved"),controlTestTimeout("retained obligations recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_sealsAndIntentPreserved"),seals,after[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic("RH_sealsAndIntentPreserved"),intents,after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertTrue(F.atomic("RH_sealsAndIntentPreserved"),controlTestTimeout("lifecycle ordinary release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("retained obligations close") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("lifecycle 2c retention") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(F.atomic("RH_sealsAndIntentPreserved"),after[ControlLifecycleEvidenceFixtures.evidenceKey],disk(file)[ControlLifecycleEvidenceFixtures.evidenceKey])
        }
    }

    @Test fun RH_floorWrite() {
        runReleaseTest {
        exactWriter("RH_floorWrite")
        }
    }

    @Test fun RH_floorRight() {
        runReleaseTest {
        exactWriter("RH_floorRight")
        }
    }

    @Test fun RH_R15cJournal() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i);F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("later effect seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(controlTestTimeout("later effect first") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        controlTestTimeout("later business effect") { s.data.updateData { it.toMutablePreferences().apply { remove(PURGE_JOURNAL) }.toPreferences() } }
        val after=F.stripBarrier(disk(file))
        val r=controlTestTimeout("old ref after later effect") { s.control.execute(c) }
        assertFalse(F.retry("RH_R15cJournal"),r is ControlStoreResult.Confirmed)
        assertEquals(F.retry("RH_R15cJournal"),emptySet<CommandRef>(),r.localUnresolvedCommands)
        assertEquals(F.retry("RH_R15cJournal"),after,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_R15cEpoch() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i);F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("later effect seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(controlTestTimeout("later effect first") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        controlTestTimeout("later business effect") { s.data.updateData { it.toMutablePreferences().apply { this[KRX_EPOCH]="later-epoch" }.toPreferences() } }
        val after=F.stripBarrier(disk(file))
        val r=controlTestTimeout("old ref after later effect") { s.control.execute(c) }
        assertFalse(F.retry("RH_R15cEpoch"),r is ControlStoreResult.Confirmed)
        assertEquals(F.retry("RH_R15cEpoch"),emptySet<CommandRef>(),r.localUnresolvedCommands)
        assertEquals(F.retry("RH_R15cEpoch"),after,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_R15cGuard() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i);F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("later effect seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(controlTestTimeout("later effect first") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        controlTestTimeout("later business effect") { s.data.updateData { F.changeRow(it,ControlKind.DEMAND,"g") { g -> F.field(g,"floor",FloorGuardFixtures.floor(31000,"boot",11000,"new-life")) } } }
        val after=F.stripBarrier(disk(file))
        val r=controlTestTimeout("old ref after later effect") { s.control.execute(c) }
        assertFalse(F.retry("RH_R15cGuard"),r is ControlStoreResult.Confirmed)
        assertEquals(F.retry("RH_R15cGuard"),emptySet<CommandRef>(),r.localUnresolvedCommands)
        assertEquals(F.retry("RH_R15cGuard"),after,F.stripBarrier(disk(file)))
        }
    }

    @Test fun RH_R19release() {
        runReleaseTest {
        val i=F.input(); val raw=F.before(i);F.assertFixture(i,raw)
        val file=folder.newFile();val s=open(file)
        controlTestTimeout("release-kind seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(controlTestTimeout("release-kind apply") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val t=ControlCommandTracking.forOwner(s.owner);val h=t.findPrepared(c)!!
        assertTrue(h.confirmed.get());assertTrue(t.snapshot().isEmpty());assertEquals(ControlCommandLifecycle.RETAINED,c.lifecycleState)
        assertFalse(F.eligible("RH_R19release"),ControlCommandReleaseEligibility.decide(c,t.lifetimeId,h,false,false) is ControlCommandReleaseEligibility.Decision.Eligible)
        }
    }

    @Test fun RH_R19seals() {
        runReleaseTest {
        val i=F.input(); val initial=F.before(i)
        val seals="["+ControlObligationFixtures.seal.replace("\"s\"","\"R\"")+","+ControlObligationFixtures.nullSeal.replace("\"s\"","\"N\"")+","+ControlObligationFixtures.settledSeal.replace("\"s\"","\"L\"")+"]"
        val intents="["+ControlObligationFixtures.recovery+"]"
        val raw=initial.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]=seals; this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents }.toPreferences()
        assertFalse(F.read(raw).hasUninterpretable)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("retained obligations seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(F.atomic("RH_R19seals"),controlTestTimeout("retained obligations recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_R19seals"),seals,after[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic("RH_R19seals"),intents,after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertTrue(F.atomic("RH_R19seals"),controlTestTimeout("lifecycle ordinary release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("retained obligations close") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("lifecycle 2c retention") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(F.atomic("RH_R19seals"),after[ControlLifecycleEvidenceFixtures.evidenceKey],disk(file)[ControlLifecycleEvidenceFixtures.evidenceKey])
        }
    }

    @Test fun RH_R19otherIntent() {
        runReleaseTest {
        val i=F.input(); val initial=F.before(i)
        val seals="["+ControlObligationFixtures.seal.replace("\"s\"","\"R\"")+","+ControlObligationFixtures.nullSeal.replace("\"s\"","\"N\"")+","+ControlObligationFixtures.settledSeal.replace("\"s\"","\"L\"")+"]"
        val intents="["+ControlObligationFixtures.recovery+"]"
        val raw=initial.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]=seals; this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents }.toPreferences()
        assertFalse(F.read(raw).hasUninterpretable)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("retained obligations seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(F.atomic("RH_R19otherIntent"),controlTestTimeout("retained obligations recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_R19otherIntent"),seals,after[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic("RH_R19otherIntent"),intents,after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertTrue(F.atomic("RH_R19otherIntent"),controlTestTimeout("lifecycle ordinary release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("retained obligations close") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("lifecycle 2c retention") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(F.atomic("RH_R19otherIntent"),after[ControlLifecycleEvidenceFixtures.evidenceKey],disk(file)[ControlLifecycleEvidenceFixtures.evidenceKey])
        }
    }

    @Test fun RH_R19witness() {
        runReleaseTest {
        val i=F.input(); val initial=F.before(i)
        val seals="["+ControlObligationFixtures.seal.replace("\"s\"","\"R\"")+","+ControlObligationFixtures.nullSeal.replace("\"s\"","\"N\"")+","+ControlObligationFixtures.settledSeal.replace("\"s\"","\"L\"")+"]"
        val intents="["+ControlObligationFixtures.recovery+"]"
        val raw=initial.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]=seals; this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents }.toPreferences()
        assertFalse(F.read(raw).hasUninterpretable)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("retained obligations seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(F.atomic("RH_R19witness"),controlTestTimeout("retained obligations recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_R19witness"),seals,after[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic("RH_R19witness"),intents,after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertTrue(F.atomic("RH_R19witness"),controlTestTimeout("lifecycle ordinary release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("retained obligations close") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("lifecycle 2c retention") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(F.atomic("RH_R19witness"),after[ControlLifecycleEvidenceFixtures.evidenceKey],disk(file)[ControlLifecycleEvidenceFixtures.evidenceKey])
        }
    }

    @Test fun RH_R19otherRequest() {
        runReleaseTest {
        val i=F.input(); val initial=F.before(i)
        val seals="["+ControlObligationFixtures.seal.replace("\"s\"","\"R\"")+","+ControlObligationFixtures.nullSeal.replace("\"s\"","\"N\"")+","+ControlObligationFixtures.settledSeal.replace("\"s\"","\"L\"")+"]"
        val intents="["+ControlObligationFixtures.recovery+"]"
        val raw=initial.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)]=seals; this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents }.toPreferences()
        assertFalse(F.read(raw).hasUninterpretable)
        val file=folder.newFile(); val s=open(file)
        controlTestTimeout("retained obligations seed") { s.data.updateData { raw } }
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        assertTrue(F.atomic("RH_R19otherRequest"),controlTestTimeout("retained obligations recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val after=disk(file)
        assertEquals(F.atomic("RH_R19otherRequest"),seals,after[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic("RH_R19otherRequest"),intents,after[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertTrue(F.atomic("RH_R19otherRequest"),controlTestTimeout("lifecycle ordinary release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("retained obligations close") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("lifecycle 2c retention") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(F.atomic("RH_R19otherRequest"),after[ControlLifecycleEvidenceFixtures.evidenceKey],disk(file)[ControlLifecycleEvidenceFixtures.evidenceKey])
        }
    }
    @Test fun RH_userEpochWrite() {
        runReleaseTest { variantWriter("RH_userEpochWrite", both = true) }
    }

    @Test fun RH_userMarkerWrite() {
        runReleaseTest { variantWriter("RH_userMarkerWrite", both = true) }
    }

    @Test fun RH_guardCreateWrite() {
        runReleaseTest { variantWriter("RH_guardCreateWrite", both = false) }
    }

    @Test fun RH_evidenceOmit() {
        runReleaseTest { exactWriter("RH_evidenceOmit") }
    }

    @Test fun RH_newRequestOwner() {
        runReleaseTest { exactWriter("RH_newRequestOwner") }
    }

    @Test fun RH_newRequestBinding() {
        runReleaseTest { exactWriter("RH_newRequestBinding") }
    }

    @Test fun RH_newRequestOrigin() {
        runReleaseTest { exactWriter("RH_newRequestOrigin") }
    }

    @Test fun RH_newRequestIntent() {
        runReleaseTest { exactWriter("RH_newRequestIntent") }
    }

    @Test fun RH_newRequestOrder() {
        runReleaseTest { exactWriter("RH_newRequestOrder") }
    }
    /** Full-wire oracle for USER rotation and absent-guard CREATE. No plan output as expected data. */
    private suspend fun variantWriter(id: String, both: Boolean) {
        val i = F.input(h = F.hold(both = both, floor = !both), g = null)
        val before = F.before(i); val h = ControlSchema.read(ControlKind.HOLD,i.source) as RestoredHold
        assertEquals(if (both) setOf(PurgeScope.USER,PurgeScope.CAPABILITY) else setOf(PurgeScope.CAPABILITY),h.axes)
        assertEquals(FenceV1("A","u","k"),(h.provenance as HoldProvenanceV1.Query).started.fence)
        assertEquals(if (both) null else FloorV1("boot",10000,30000,LifetimeId("life")),h.floor)
        assertNull(i.guard); assertEquals(true,before[MAY_CONTAIN_PREMIUM]); assertEquals("u",before[USER_EPOCH])
        assertEquals(LifecycleBinding(SettlementExecutor("A",3,LifetimeId("new-life")),IdentityV1("A",2),1,"binding-start"),i.binding)
        assertFalse(F.read(before).hasUninterpretable)
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("variant seed") { s.data.updateData { before } }
        val c = s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21)); val ids = plan(c).ids
        val request = F.node("""{"id":${F.text(ids.requestId)},"kind":"REQUEST","ownerUid":"A","binding":3,"intent":"FORCE_PREMIUM","originLifetimeId":"new-life","raisedAt":22}""")
        val newGuard = F.node("""{"id":${F.text(ids.guardId)},"kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"boot","anchorElapsedMillis":11000,"waitMillis":29000,"originLifetimeId":"new-life"}}""")
        val targets = """{"kind":"HOLD","id":"h","effect":"REMOVE"},{"kind":"DEMAND","id":${F.text(ids.requestId)},"effect":"CREATE"}""" +
            if (both) "" else """,{"kind":"DEMAND","id":${F.text(ids.guardId)},"effect":"CREATE"}"""
        val expected = before.toMutablePreferences().apply {
            if (both) { this[USER_EPOCH]=ids.epochs.user!!; this[MAY_CONTAIN_PREMIUM]=false }
            this[KRX_EPOCH]=ids.epochs.capability!!; this[MAY_CONTAIN_KRX]=false
            this[PURGE_JOURNAL]=if (both) "A|u||USER\nA||k|CAPABILITY" else "A||k|CAPABILITY"
            this[ControlRecordKeys.payload(ControlKind.HOLD)]="[]"
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = F.payload(listOf(F.request(),F.request("other","B",RefreshIntent.FORCE_PREMIUM),request) + if (both) emptyList() else listOf(newGuard))
            this[ControlLifecycleEvidenceFixtures.evidenceKey]="["+ControlLifecycleEvidenceFixtures.wire("RECOVER_HOLD",targets,c.id,c.ownerTrackingLifetimeId.value)+"]"
        }.toPreferences()
        val result = controlTestTimeout("variant writer") { s.control.execute(c,F.context(i)) }
        assertEquals(F.atomic(id),expected,F.stripBarrier(disk(file)))
        assertTrue(F.atomic(id),result is ControlStoreResult.Confirmed)
    }

    @Test fun RH_R12before() = runReleaseTest {
        val id="RH_R12before"; val i=F.input(); val before=F.before(i); F.assertFixture(i,before)
        val file=folder.newFile(); val previous=open(file)
        controlTestTimeout("before failure seed") { previous.data.updateData { before } }
        val c=previous.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21))
        previous.storage.before=true
        // await and join the old caller before closing its storage scope.
        val caller=async { previous.control.execute(c,F.context(i)) }
        val failed=controlTestTimeout("before failure caller") { caller.await() }
        caller.cancelAndJoinForTest(); assertTrue(caller.isCompleted)
        assertTrue(F.retry(id),failed is ControlStoreResult.Unconfirmed)
        assertEquals(F.retry(id),before,F.stripBarrier(disk(file)))
        assertNull(F.retry(id),ControlAppliedEvidence.own(F.read(before),c))
        controlTestTimeout("before failure old scope end") { previous.close() }
        val next=open(file); next.raw(); val tracker=ControlCommandTracking.forOwner(next.owner)
        assertNotEquals(c.ownerTrackingLifetimeId,tracker.lifetimeId)
        val r=controlTestTimeout("before failure confirm previous") { next.control.confirmPrevious(c) }
        assertFalse(F.retry(id),r is ControlStoreResult.Confirmed)
        assertNull(F.retry(id),tracker.findPrepared(c))
        // run() marks this attempt unresolved. Its Unconfirmed result keeps the old ref in recovery work.
        // R12 forbids registering it as locally prepared; findPrepared(c) must still remain null.
        assertEquals(F.retry(id),setOf(c),tracker.snapshot())
        assertEquals(F.retry(id),before,F.stripBarrier(disk(file)))
        assertNull(F.retry(id),ControlAppliedEvidence.own(F.read(disk(file)),c))
        assertEquals(F.retry(id),i.source.toPayloadEntry(),F.row(disk(file),ControlKind.HOLD,"h").toPayloadEntry())
    }

    /** R and N create requests; L deliberately preserves them and issues no request of its own. */
    @Test fun RH_R19joinedSequence() = runReleaseTest {
        val id="RH_R19joinedSequence"; val file=folder.newFile(); val s=open(file)
        val h=F.hold(floor=false)
        val rSeal=F.node("""{"id":"R","kind":"NAMESPACE","ownerUid":"A","axis":"CAPABILITY","epoch":"retired-k"}""")
        val nSeal=F.node("""{"id":"N","kind":"NULL_NAMESPACE","ownerUid":"A","axis":"USER"}""")
        val lSeal=F.node("""{"id":"L","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"CAPABILITY"}""")
        val intents="[${ControlObligationFixtures.recovery}]"
        val before=F.before(F.input(h=h,g=null)).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)]=F.payload(listOf(rSeal,nSeal,lSeal))
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)]=intents
        }.toPreferences()
        assertFalse(F.read(before).hasUninterpretable); assertFalse(F.read(before).hasUninterpretableMetadata)
        assertEquals("A",before[OWNER_UID]); assertEquals("u",before[USER_EPOCH]); assertEquals("k",before[KRX_EPOCH])
        assertNull((ControlSchema.read(ControlKind.HOLD,h) as RestoredHold).floor)
        controlTestTimeout("joined seed") { s.data.updateData { before } }
        val executor=SettlementExecutor("A",3,LifetimeId("new-life"))
        val ctx=AttemptContext("A",3,LifetimeId("new-life"),false,false)
        val r=s.control.prepareRetiredNamespaceSettlement(rSeal,FenceV1("A","u","k"),executor,
            SettlementDemand("A",3,EventOrderV1(F.life,10),RefreshIntent.FORCE_PREMIUM))
        assertTrue(F.atomic(id),controlTestTimeout("actual R") { s.control.execute(r,ctx) } is ControlStoreResult.Confirmed)
        val rInput=(r.body as ControlCommandBody.SettleRetiredNamespace).input
        val rId=rInput.demandId!!; val rRequest=F.row(disk(file),ControlKind.DEMAND,rId)
        val rExpected=F.node("""{"id":${F.text(rId)},"kind":"REQUEST","ownerUid":"A","binding":3,"originLifetimeId":"new-life","raisedAt":10,"intent":"FORCE_PREMIUM"}""")
        assertEquals(F.atomic(id),rExpected.toPayloadEntry(),rRequest.toPayloadEntry())
        val n=s.control.prepareCurrentNullSettlement(listOf(nSeal),F.read(disk(file)),FenceV1("A","u","k"),executor,
            SettlementDemand("A",3,EventOrderV1(F.life,11),RefreshIntent.FORCE_PREMIUM))
        val nInput=(n.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertTrue(F.atomic(id),controlTestTimeout("actual N") { s.control.execute(n,ctx) } is ControlStoreResult.Confirmed)
        val nId=nInput.demandId; val newUser=nInput.newUserEpoch!!
        assertEquals(F.atomic(id),newUser,disk(file)[USER_EPOCH]); assertEquals(F.atomic(id),"k",disk(file)[KRX_EPOCH])
        val nExpected=F.node("""{"id":${F.text(nId)},"kind":"REQUEST","ownerUid":"A","binding":3,"originLifetimeId":"new-life","raisedAt":11,"intent":"FORCE_PREMIUM"}""")
        assertEquals(F.atomic(id),nExpected.toPayloadEntry(),F.row(disk(file),ControlKind.DEMAND,nId).toPayloadEntry())
        val beforeL=disk(file); val l=s.control.prepareRetiredNullSettlement(listOf(lSeal),FenceV1("A",newUser,"k"),executor)
        assertTrue(F.atomic(id),controlTestTimeout("actual L") { s.control.execute(l,ctx) } is ControlStoreResult.Confirmed)
        assertEquals(F.atomic(id),beforeL[ControlRecordKeys.payload(ControlKind.DEMAND)],disk(file)[ControlRecordKeys.payload(ControlKind.DEMAND)])
        val seals=disk(file)[ControlRecordKeys.payload(ControlKind.SEAL)]!!
        val settled=F.read(disk(file)).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals(listOf("R","N","L"),settled.map { it.id })
        assertEquals(listOf(r.id,n.id,l.id),settled.map { it.settlement?.operationId })
        val i=F.input(h=h,g=null,before=FenceV1("A",newUser,"k"))
        val c=s.control.prepareRecoverHold(i,LifecycleOrderSource(F.life,21)); val ids=plan(c).ids
        assertTrue(F.atomic(id),controlTestTimeout("joined HOLD recovery") { s.control.execute(c,F.context(i)) } is ControlStoreResult.Confirmed)
        val recovered=disk(file)
        assertEquals(F.atomic(id),seals,recovered[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic(id),intents,recovered[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertEquals(F.atomic(id),rExpected.toPayloadEntry(),F.row(recovered,ControlKind.DEMAND,rId).toPayloadEntry())
        assertEquals(F.atomic(id),nExpected.toPayloadEntry(),F.row(recovered,ControlKind.DEMAND,nId).toPayloadEntry())
        assertEquals(F.atomic(id),F.request("other","B",RefreshIntent.FORCE_PREMIUM).toPayloadEntry(),F.row(recovered,ControlKind.DEMAND,"other").toPayloadEntry())
        val recoveryRequest=F.row(recovered,ControlKind.DEMAND,ids.requestId)
        assertEquals(F.atomic(id),22L,demand(recoveryRequest)?.raisedAt?.value)
        assertEquals(F.atomic(id),RefreshIntent.FORCE_PREMIUM,demand(recoveryRequest)?.intent)
        assertEquals(F.atomic(id),"[]",recovered[ControlRecordKeys.payload(ControlKind.HOLD)])
        val fence=FenceV1("A",newUser,ids.epochs.capability)
        val q=StartedQueryV1(fence,5,IdentityV1("A",2),EventOrderV1(F.life,31),3,RefreshIntent.FORCE_PREMIUM,0)
        val decision=DemandAuthFixtures.decision(q=q,before=fence,after=fence,origin=F.life)
        val consume=s.control.prepareSettleQuery(listOf(rRequest,F.row(recovered,ControlKind.DEMAND,nId),recoveryRequest),null,null,F.binding,decision,LifecycleOrderSource(F.life,31))
        val runtime=DemandAuthFixtures.runtime(binding=F.binding,registrations=listOf(decision.registration))
        assertTrue(F.atomic(id),controlTestTimeout("joined fresh query") { s.control.execute(consume,DemandAuthFixtures.context(runtime)) } is ControlStoreResult.Confirmed)
        val consumed=F.stripBarrier(disk(file))
        for (requestId in listOf(rId,nId,ids.requestId)) assertTrue(F.atomic(id),F.read(consumed).locations(requestId).isEmpty())
        assertEquals(F.atomic(id),seals,consumed[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.atomic(id),intents,consumed[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
        assertEquals(F.atomic(id),F.request("other","B",RefreshIntent.FORCE_PREMIUM).toPayloadEntry(),F.row(consumed,ControlKind.DEMAND,"other").toPayloadEntry())
        assertFalse(F.retry(id),controlTestTimeout("joined old lifecycle") { s.control.execute(c) } is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id),LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE,c.lastLifecycleDiagnostic?.classification)
        assertEquals(F.retry(id),consumed,F.stripBarrier(disk(file)))
        // In contrast to lifecycle CREATE postconditions, the R/N/L seal witnesses still confirm.
        for (old in listOf(r,n,l)) assertTrue(F.retry(id),controlTestTimeout("joined old settlement") { s.control.execute(old) } is ControlStoreResult.Confirmed)
        assertTrue(F.retry(id),controlTestTimeout("joined lifecycle release denied") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("joined old scope closed") { s.close() }
        val next=open(file); next.raw()
        controlTestTimeout("joined 2c") { next.control.reclaimPreviousLifetimeEvidence() }
        val retained=F.read(disk(file))
        assertNotNull(F.retry(id),ControlAppliedEvidence.own(retained,c))
        assertNotNull(F.retry(id),ControlAppliedEvidence.own(retained,consume))
        assertEquals(F.retry(id),seals,disk(file)[ControlRecordKeys.payload(ControlKind.SEAL)])
        assertEquals(F.retry(id),intents,disk(file)[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)])
    }
}
