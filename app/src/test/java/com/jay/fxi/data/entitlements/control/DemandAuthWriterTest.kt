package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DemandAuthWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runBlocking { controlTestTimeout("5b cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private enum class Writer { REBIND, SETTLE, AUTH, END, ZERO_PENDING, ZERO_BLOCKED }
    private class Case(val source: Preferences, val runtime: DemandAuthRuntime, val prepare: (ControlRecordStore) -> CommandRef)
    private fun case(w: Writer): Case {
        val orders = LifecycleOrderSource(F.life, 21)
        return when (w) {
            Writer.REBIND -> {
                val r = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
                Case(F.raw(r, F.request(id = "dormant", owner = "B")), F.runtime()) { it.prepareRebindRequests(listOf(r), F.binding, orders) }
            }
            Writer.SETTLE -> {
                val r = F.request(); val g = F.guard(F.auth.copy(authStopped = false), 90000)
                val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
                Case(F.raw(r, g, F.request(id = "dormant", owner = "B")), F.runtime()) { it.prepareSettleQuery(listOf(r), g, null, F.binding, d, orders) }
            }
            Writer.AUTH -> {
                val g = F.guard(); val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
                Case(F.raw(g), F.runtime()) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Answer(d), orders) }
            }
            Writer.END -> {
                val g = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
                val r = F.request(binding = 2, origin = LifetimeId("old"))
                val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
                Case(F.raw(g, r, F.request(id = "dormant", owner = "B")), F.runtime(closure = closed)) {
                    it.prepareEndAuthBinding(g, listOf(r), F.binding, closed, F.binding, orders)
                }
            }
            Writer.ZERO_PENDING, Writer.ZERO_BLOCKED -> {
                val g = F.guard(F.auth.copy(authStopped = false))
                val d = if (w == Writer.ZERO_PENDING) F.decision(outcome = EntitlementsOutcome.Pending(false, 60)) else
                    F.decision(outcome = EntitlementsOutcome.StableActive(true), reapproval = LifecycleReapproval.BLOCKED)
                Case(F.raw(g), F.runtime()) { it.prepareSettleQuery(emptyList(), g, null, F.binding, d, orders) }
            }
        }
    }
    private fun assertLanding(id: String, w: Writer, c: CommandRef, original: Preferences, actual: Preferences) {
        val read = F.read(actual)
        val demands = read.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
        val requests = demands.mapNotNull { it.value as? DemandV1 }
        val guard = demands.mapNotNull { it.value as? ScheduleGuardV1 }.singleOrNull()
        val own = ControlAppliedEvidence.own(read, c) as? AppliedEvidence.Lifecycle
        var valid = !read.hasUninterpretable && !read.hasUninterpretableMetadata && own != null
        valid = valid && when (w) {
            Writer.REBIND -> requests.single { it.id == "r" }.let { it.ownerUid == "A" && it.binding == 3L && it.raisedAt == EventOrderV1(F.life, 22) && it.intent == RefreshIntent.FORCE_PREMIUM }
            Writer.SETTLE -> requests.none { it.id == "r" } && requests.single { it.ownerUid == "A" }.let {
                it.binding == 3L && it.raisedAt == EventOrderV1(F.life, 22) && it.intent == RefreshIntent.FORCE_PREMIUM
            } && guard?.floor?.waitMillis == 90000L
            Writer.AUTH -> guard?.auth == F.auth.copy(authStopped = false, authStateOrder = 21) && guard.floor?.waitMillis == 30000L &&
                requests.singleOrNull()?.let { it.intent == RefreshIntent.FORCE_PREMIUM && it.raisedAt == EventOrderV1(F.life, 22) } == true
            Writer.END -> guard?.auth == F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0) && guard.floor?.waitMillis == 90000L &&
                requests.single { it.id == "r" }.raisedAt == EventOrderV1(F.life, 22)
            Writer.ZERO_PENDING, Writer.ZERO_BLOCKED -> guard?.auth == F.auth.copy(authStopped = false) &&
                guard.floor?.waitMillis == (if (w == Writer.ZERO_PENDING) 60000L else 5000L) &&
                requests.singleOrNull()?.let { it.intent == RefreshIntent.FORCE_PREMIUM && it.raisedAt == EventOrderV1(F.life, 22) } == true
        }
        val unchanged = original.toMutablePreferences().apply {
            remove(ControlRecordKeys.payload(ControlKind.DEMAND)); remove(ControlLifecycleEvidenceFixtures.evidenceKey); remove(DataStoreAccessEpochStore.READ_BARRIER)
        }
        val rest = actual.toMutablePreferences().apply {
            remove(ControlRecordKeys.payload(ControlKind.DEMAND)); remove(ControlLifecycleEvidenceFixtures.evidenceKey); remove(DataStoreAccessEpochStore.READ_BARRIER)
        }
        valid = valid && unchanged == rest
        val oldDormant = F.read(original).locations("dormant").singleOrNull()?.second as? ControlEntryRead.Interpreted
        if (oldDormant != null) valid = valid && oldDormant.original.toPayloadEntry() == (read.locations("dormant").singleOrNull()?.second as? ControlEntryRead.Interpreted)?.original?.toPayloadEntry()
        assertTrue(F.atomic(id), valid)
    }
    private suspend fun storage(w: Writer, mode: String, id: String, restart: Boolean = false) {
        val file = folder.newFile(); val store = open(file); val spec = case(w)
        controlTestTimeout("seed 5b") { store.data.updateData { spec.source } }
        val c = spec.prepare(store.control)
        val fixedTargets = (c.body as ControlCommandBody.Lifecycle).input.targets.toList()
        val tracking = ControlCommandTracking.forOwner(store.owner)
        if (mode == "before") store.storage.before = true
        if (mode == "after") store.storage.afterScope = true
        val result = controlTestTimeout("5b actual writer") { store.control.execute(c, F.context(spec.runtime)) }
        if (mode == "success") assertTrue(F.retry(id), result is ControlStoreResult.Confirmed) else {
            assertTrue(F.retry(id), result is ControlStoreResult.Unconfirmed)
            assertEquals(setOf(c), result.localUnresolvedCommands)
            assertNotNull(F.retry(id), tracking.findPrepared(c)!!.firstConfirmDiscontinuityCount)
            assertFalse(tracking.findPrepared(c)!!.confirmed.get())
        }
        if (mode == "before") assertEquals(F.atomic(id), spec.source, disk(file)) else if (!restart) assertLanding(id, w, c, spec.source, disk(file))
        if (restart) {
            store.close()
            val next = open(file)
            next.raw()
            assertLanding(id, w, c, spec.source, disk(file))
            val previous = controlTestTimeout("5b same-file restart") { next.control.confirmPrevious(c) }
            assertTrue(F.retry(id), previous is ControlStoreResult.Confirmed)
            assertNull(F.retry(id), ControlCommandTracking.forOwner(next.owner).findPrepared(c))
            assertLanding(id, w, c, spec.source, disk(file))
        } else {
            // A landed ref confirms even when the original runtime is no longer current.
            val retry = controlTestTimeout("5b fixed ref retry") {
                if (mode == "before") store.control.execute(c, F.context(spec.runtime)) else store.control.execute(c)
            }
            assertTrue(F.retry(id), retry is ControlStoreResult.Confirmed)
            assertEquals(F.retry(id), c.id, retry.command.id)
            assertEquals(F.retry(id), emptySet<CommandRef>(), retry.localUnresolvedCommands)
            assertTrue(F.retry(id), fixedTargets.all { ControlLifecycleBoundary.postcondition(F.read(disk(file)), it) == null })
            assertLanding(id, w, c, spec.source, disk(file))
        }
    }
    private suspend fun cancellation(w: Writer, id: String, priorFailure: Boolean = false) = kotlinx.coroutines.coroutineScope {
        val file = folder.newFile(); val store = open(file); val spec = case(w)
        controlTestTimeout("seed cancelled 5b") { store.data.updateData { spec.source } }
        val c = spec.prepare(store.control); val gate = ControlStoreTestStorage.Pause()
        if (priorFailure) {
            store.storage.afterScope = true
            assertTrue(store.control.execute(c, F.context(spec.runtime)) is ControlStoreResult.Unconfirmed)
        }
        store.storage.pauseAfterScope = gate
        val caller = async { store.control.execute(c, F.context(spec.runtime)) }
        try {
            controlTestTimeout("5b landed pause") { gate.reached.await() }
            caller.cancelAndJoinForTest()
            assertEquals(F.retry(id), setOf(c), ControlCommandTracking.forOwner(store.owner).snapshot())
            val tracked = ControlCommandTracking.forOwner(store.owner).findPrepared(c)!!
            assertTrue(F.retry(id), tracked.confirmationRequested.get() && tracked.expectedApplied != null && tracked.firstConfirmDiscontinuityCount != null)
            if (priorFailure) assertTrue(F.retry(id), tracked.observedApplied.get())
        } finally { gate.release.complete(Unit); caller.cancelAndJoinForTest() }
        store.close()
        val next = open(file)
        next.raw()
        assertLanding(id, w, c, spec.source, disk(file))
        val result = controlTestTimeout("5b cancelled same-file restart") { next.control.confirmPrevious(c) }
        assertTrue(F.retry(id), result is ControlStoreResult.Confirmed)
        assertLanding(id, w, c, spec.source, disk(file))
    }
    @Test fun rebind_R01() = runReleaseTest { storage(Writer.REBIND, "before", "REBIND_R01") }
    @Test fun rebind_R02() = runReleaseTest { storage(Writer.REBIND, "after", "REBIND_R02") }
    @Test fun rebind_R03() = runReleaseTest { cancellation(Writer.REBIND, "REBIND_R03") }
    @Test fun rebind_R12() = runReleaseTest { storage(Writer.REBIND, "after", "REBIND_R12", true) }
    @Test fun settle_R01() = runReleaseTest { storage(Writer.SETTLE, "before", "SETTLE_R01") }
    @Test fun settle_R02() = runReleaseTest { storage(Writer.SETTLE, "after", "SETTLE_R02") }
    @Test fun settle_R03() = runReleaseTest { cancellation(Writer.SETTLE, "SETTLE_R03") }
    @Test fun settle_R12() = runReleaseTest { storage(Writer.SETTLE, "after", "SETTLE_R12", true) }
    @Test fun auth_R01() = runReleaseTest { storage(Writer.AUTH, "before", "AUTH_R01") }
    @Test fun auth_R02() = runReleaseTest { storage(Writer.AUTH, "after", "AUTH_R02") }
    @Test fun auth_R03() = runReleaseTest { cancellation(Writer.AUTH, "AUTH_R03") }
    @Test fun auth_R12() = runReleaseTest { storage(Writer.AUTH, "after", "AUTH_R12", true) }
    @Test fun end_R01() = runReleaseTest { storage(Writer.END, "before", "END_R01") }
    @Test fun end_R02() = runReleaseTest { storage(Writer.END, "after", "END_R02") }
    @Test fun end_R03() = runReleaseTest { cancellation(Writer.END, "END_R03") }
    @Test fun rebind_R03observed() = runReleaseTest { cancellation(Writer.REBIND, "REBIND_R03observed", true) }
    @Test fun settle_R03observed() = runReleaseTest { cancellation(Writer.SETTLE, "SETTLE_R03observed", true) }
    @Test fun auth_R03observed() = runReleaseTest { cancellation(Writer.AUTH, "AUTH_R03observed", true) }
    @Test fun end_R03observed() = runReleaseTest { cancellation(Writer.END, "END_R03observed", true) }
    @Test fun end_R12() = runReleaseTest { storage(Writer.END, "after", "END_R12", true) }
    @Test fun Q17c() = runReleaseTest { storage(Writer.ZERO_PENDING, "after", "Q17c", true) }
    @Test fun Q17c_cancel() = runReleaseTest { cancellation(Writer.ZERO_PENDING, "Q17c_cancel") }
    @Test fun Q17c_blocked() = runReleaseTest { storage(Writer.ZERO_BLOCKED, "after", "Q17c_blocked", true) }
    @Test fun Q17c_blockedCancel() = runReleaseTest { cancellation(Writer.ZERO_BLOCKED, "Q17c_blockedCancel") }
    @Test fun Q17_pending_R01() = runReleaseTest { storage(Writer.ZERO_PENDING, "before", "ZERO_PENDING_R01") }
    @Test fun Q17_blocked_R01() = runReleaseTest { storage(Writer.ZERO_BLOCKED, "before", "ZERO_BLOCKED_R01") }
    @Test fun A15c() = runReleaseTest { storage(Writer.AUTH, "after", "A15c", true) }
    private suspend fun accumulation(w: Writer, id: String) {
        val file = folder.newFile(); val store = open(file); val spec = case(w)
        controlTestTimeout("accumulation seed") { store.data.updateData { spec.source } }
        val a = spec.prepare(store.control); val b = spec.prepare(store.control); val c = spec.prepare(store.control)
        store.storage.before = true
        assertTrue(store.control.execute(a, F.context(spec.runtime)) is ControlStoreResult.Unconfirmed)
        store.storage.before = true
        assertTrue(store.control.execute(a, F.context(spec.runtime)) is ControlStoreResult.Unconfirmed)
        assertEquals(F.retry(id), setOf(a), ControlCommandTracking.forOwner(store.owner).snapshot())
        store.storage.before = true
        assertTrue(store.control.execute(b, F.context(spec.runtime)) is ControlStoreResult.Unconfirmed)
        store.storage.before = true
        assertTrue(store.control.execute(c, F.context(spec.runtime)) is ControlStoreResult.Unconfirmed)
        assertEquals(F.retry(id), setOf(a, b, c), ControlCommandTracking.forOwner(store.owner).snapshot())
        assertTrue(F.retry(id), store.control.releaseAfterConsumption(a) is ControlCommandReleaseResult.Rejected)
        assertEquals(F.retry(id), setOf(a, b, c), ControlCommandTracking.forOwner(store.owner).snapshot())
        val unrelated = store.control.prepare(store.control.addition(ControlKind.RECOVERY_INTENT) { uid ->
            set("id", ControlScalar.Text(uid)); set("sessionId", ControlScalar.Text("unrelated")); set("ownerUid", ControlScalar.Text("B"))
            set("axis", ControlScalar.Text("CAPABILITY")); set("targetEpoch", ControlScalar.Null)
        })
        val next = store.control.execute(unrelated)
        assertTrue(F.retry(id), next is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id), setOf(a, b, c), next.localUnresolvedCommands)
        assertTrue(store.control.releaseAfterConsumption(unrelated) is ControlCommandReleaseResult.Released)
        assertEquals(F.retry(id), setOf(a, b, c), ControlCommandTracking.forOwner(store.owner).snapshot())
        assertEquals(F.retry(id), LifecycleClassification.NO_LANDING_PROOF, a.lastLifecycleDiagnostic!!.classification)
        assertTrue(a.lastLifecycleDiagnostic!!.confirmationRequested)
        assertFalse(a.lastLifecycleDiagnostic!!.previouslyConfirmed)
        val success = store.control.execute(a, F.context(spec.runtime))
        assertTrue(F.retry(id), success is ControlStoreResult.Confirmed)
        assertEquals(F.retry(id), setOf(b, c), success.localUnresolvedCommands)
    }
    @Test fun rebind_R17_R18() = runReleaseTest { accumulation(Writer.REBIND, "REBIND_R17") }
    @Test fun settle_R17_R18() = runReleaseTest { accumulation(Writer.SETTLE, "SETTLE_R17") }
    @Test fun auth_R17_R18() = runReleaseTest { accumulation(Writer.AUTH, "AUTH_R17") }
    @Test fun end_R17_R18() = runReleaseTest { accumulation(Writer.END, "END_R17") }
    @Test fun rebind_R18b() = runReleaseTest { accumulation(Writer.REBIND, "REBIND_R18b") }
    @Test fun settle_R18b() = runReleaseTest { accumulation(Writer.SETTLE, "SETTLE_R18b") }
    @Test fun auth_R18b() = runReleaseTest { accumulation(Writer.AUTH, "AUTH_R18b") }
    @Test fun end_R18b() = runReleaseTest { accumulation(Writer.END, "END_R18b") }

    private suspend fun successor(w: Writer, failed: Boolean, id: String) {
        val file = folder.newFile(); val store = open(file); val spec = case(w)
        controlTestTimeout("successor seed") { store.data.updateData { spec.source } }
        val c = spec.prepare(store.control); store.storage.afterScope = failed
        val first = store.control.execute(c, F.context(spec.runtime))
        assertTrue(if (failed) first is ControlStoreResult.Unconfirmed else first is ControlStoreResult.Confirmed)
        val landed = disk(file)
        val read = F.read(landed)
        val current = read.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
        val orders = LifecycleOrderSource(F.life, 30)
        val next: CommandRef
        val ctx: AttemptContext
        if (w == Writer.REBIND || w == Writer.SETTLE) {
            val r = current.single { (it.value as? DemandV1)?.ownerUid == "A" }.original
            val g = current.singleOrNull { it.value is ScheduleGuardV1 }?.original
            val d = F.decision(q = F.query.copy(order = EventOrderV1(F.life, 31)))
            next = store.control.prepareSettleQuery(listOf(r), g, null, F.binding, d, orders)
            ctx = F.context(F.runtime(registrations = listOf(d.registration)))
        } else {
            val g = current.single { it.value is ScheduleGuardV1 }.original
            val r = current.singleOrNull { (it.value as? DemandV1)?.ownerUid == "A" }?.original
            val caller = LifecycleCaller("later", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now)
            next = store.control.prepareUpdateAuth(g, r, F.binding, LifecycleAuthEvent.Caller(caller), orders)
            ctx = F.context(F.runtime(caller = caller))
        }
        val nextResult = store.control.execute(next, ctx)
        assertTrue(nextResult is ControlStoreResult.Confirmed)
        val after = disk(file)
        val old = store.control.execute(c)
        assertTrue(F.retry(id), old !is ControlStoreResult.Confirmed && disk(file) == after)
        assertEquals(F.retry(id), if (failed) setOf(c) else emptySet<CommandRef>(), old.localUnresolvedCommands)
        assertEquals(F.retry(id), LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, c.lastLifecycleDiagnostic!!.classification)
    }
    @Test fun rebind_R14_R15a() = runReleaseTest { successor(Writer.REBIND, false, "REBIND_R15a") }
    @Test fun rebind_R14_R15b() = runReleaseTest { successor(Writer.REBIND, true, "REBIND_R15b") }
    @Test fun settle_R14_R15a() = runReleaseTest { successor(Writer.SETTLE, false, "SETTLE_R15a") }
    @Test fun settle_R14_R15b() = runReleaseTest { successor(Writer.SETTLE, true, "SETTLE_R15b") }
    @Test fun auth_R14_R15a() = runReleaseTest { successor(Writer.AUTH, false, "AUTH_R15a") }
    @Test fun auth_R14_R15b() = runReleaseTest { successor(Writer.AUTH, true, "AUTH_R15b") }
    @Test fun end_R14_R15a() = runReleaseTest { successor(Writer.END, false, "END_R15a") }
    @Test fun end_R14_R15b() = runReleaseTest { successor(Writer.END, true, "END_R15b") }
    @Test fun rebind_R18a() = runReleaseTest { successor(Writer.REBIND, true, "REBIND_R18a") }
    @Test fun settle_R18a() = runReleaseTest { successor(Writer.SETTLE, true, "SETTLE_R18a") }
    @Test fun auth_R18a() = runReleaseTest { successor(Writer.AUTH, true, "AUTH_R18a") }
    @Test fun end_R18a() = runReleaseTest { successor(Writer.END, true, "END_R18a") }

    private suspend fun diagnostic(w: Writer, lost: Boolean, id: String) {
        val file = folder.newFile(); val store = open(file); val spec = case(w)
        store.data.updateData { spec.source }
        val c = spec.prepare(store.control)
        if (lost) {
            assertTrue(store.control.execute(c, F.context(spec.runtime)) is ControlStoreResult.Confirmed)
            store.data.updateData { it.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" } }
        }
        val result = if (lost) store.control.execute(c) else store.control.execute(c, F.context(spec.runtime).copy(binding = 999))
        val d = c.lastLifecycleDiagnostic!!
        assertTrue(if (lost) result is ControlStoreResult.RecoveryRequired else result is ControlStoreResult.Conflict)
        assertEquals(F.retry(id), if (lost) LifecycleClassification.EVIDENCE_UNAVAILABLE else LifecycleClassification.PRECONDITION_CHANGED_WITHOUT_LANDING_PROOF, d.classification)
        assertEquals(lost, d.confirmationRequested); assertEquals(lost, d.previouslyConfirmed)
    }
    @Test fun rebind_R18c() = runReleaseTest { diagnostic(Writer.REBIND, true, "REBIND_R18c") }
    @Test fun settle_R18c() = runReleaseTest { diagnostic(Writer.SETTLE, true, "SETTLE_R18c") }
    @Test fun auth_R18c() = runReleaseTest { diagnostic(Writer.AUTH, true, "AUTH_R18c") }
    @Test fun end_R18c() = runReleaseTest { diagnostic(Writer.END, true, "END_R18c") }
    @Test fun rebind_R18d() = runReleaseTest { diagnostic(Writer.REBIND, false, "REBIND_R18d") }
    @Test fun settle_R18d() = runReleaseTest { diagnostic(Writer.SETTLE, false, "SETTLE_R18d") }
    @Test fun auth_R18d() = runReleaseTest { diagnostic(Writer.AUTH, false, "AUTH_R18d") }
    @Test fun end_R18d() = runReleaseTest { diagnostic(Writer.END, false, "END_R18d") }

    @Test fun rebind_R01id() = runReleaseTest { storage(Writer.REBIND, "before", "REBIND_R01id") }
    @Test fun rebind_R01order() = runReleaseTest { storage(Writer.REBIND, "before", "REBIND_R01order") }
    @Test fun rebind_R03expected() = runReleaseTest { cancellation(Writer.REBIND, "REBIND_R03expected") }
    @Test fun rebind_R03requested() = runReleaseTest { cancellation(Writer.REBIND, "REBIND_R03requested") }
    @Test fun rebind_R03baseline() = runReleaseTest { cancellation(Writer.REBIND, "REBIND_R03baseline") }
    @Test fun rebind_R17eviction() = runReleaseTest { accumulation(Writer.REBIND, "REBIND_R17eviction") }
    @Test fun rebind_R17gate() = runReleaseTest { accumulation(Writer.REBIND, "REBIND_R17gate") }
    @Test fun rebind_R15post() = runReleaseTest { successor(Writer.REBIND, false, "REBIND_R15post") }
    @Test fun settle_R01id() = runReleaseTest { storage(Writer.SETTLE, "before", "SETTLE_R01id") }
    @Test fun settle_R01order() = runReleaseTest { storage(Writer.SETTLE, "before", "SETTLE_R01order") }
    @Test fun settle_R03expected() = runReleaseTest { cancellation(Writer.SETTLE, "SETTLE_R03expected") }
    @Test fun settle_R03requested() = runReleaseTest { cancellation(Writer.SETTLE, "SETTLE_R03requested") }
    @Test fun settle_R03baseline() = runReleaseTest { cancellation(Writer.SETTLE, "SETTLE_R03baseline") }
    @Test fun settle_R17eviction() = runReleaseTest { accumulation(Writer.SETTLE, "SETTLE_R17eviction") }
    @Test fun settle_R17gate() = runReleaseTest { accumulation(Writer.SETTLE, "SETTLE_R17gate") }
    @Test fun settle_R15post() = runReleaseTest { successor(Writer.SETTLE, false, "SETTLE_R15post") }
    @Test fun auth_R01id() = runReleaseTest { storage(Writer.AUTH, "before", "AUTH_R01id") }
    @Test fun auth_R01order() = runReleaseTest { storage(Writer.AUTH, "before", "AUTH_R01order") }
    @Test fun auth_R03expected() = runReleaseTest { cancellation(Writer.AUTH, "AUTH_R03expected") }
    @Test fun auth_R03requested() = runReleaseTest { cancellation(Writer.AUTH, "AUTH_R03requested") }
    @Test fun auth_R03baseline() = runReleaseTest { cancellation(Writer.AUTH, "AUTH_R03baseline") }
    @Test fun auth_R17eviction() = runReleaseTest { accumulation(Writer.AUTH, "AUTH_R17eviction") }
    @Test fun auth_R17gate() = runReleaseTest { accumulation(Writer.AUTH, "AUTH_R17gate") }
    @Test fun auth_R15post() = runReleaseTest { successor(Writer.AUTH, false, "AUTH_R15post") }
    @Test fun end_R01id() = runReleaseTest { storage(Writer.END, "before", "END_R01id") }
    @Test fun end_R01order() = runReleaseTest { storage(Writer.END, "before", "END_R01order") }
    @Test fun end_R03expected() = runReleaseTest { cancellation(Writer.END, "END_R03expected") }
    @Test fun end_R03requested() = runReleaseTest { cancellation(Writer.END, "END_R03requested") }
    @Test fun end_R03baseline() = runReleaseTest { cancellation(Writer.END, "END_R03baseline") }
    @Test fun end_R17eviction() = runReleaseTest { accumulation(Writer.END, "END_R17eviction") }
    @Test fun end_R17gate() = runReleaseTest { accumulation(Writer.END, "END_R17gate") }
    @Test fun end_R15post() = runReleaseTest { successor(Writer.END, false, "END_R15post") }

}
