package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in T10. All seven real named writers share one tracker for each 1,024-cycle profile. */
class LifecycleAccumulationLongTest {
    @get:Rule val folder = TemporaryFolder()

    private enum class Profile(val sample: String) {
        ASCII("external-ascii"),
        KOREAN("외부식별자-한글"),
        EMOJI_ESCAPE("😀\"quote\\slash\tjson"),
        LONG_EXTERNAL_ID("external-" + "0123456789abcdef".repeat(32))
    }
    private enum class Backend { FILE, MEMORY }
    private val distribution = LifecycleTransition.entries.associateWith {
        if (it == LifecycleTransition.REBIND_REQUESTS || it == LifecycleTransition.SETTLE_QUERY) 147 else 146
    }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val holdKey = ControlRecordKeys.payload(ControlKind.HOLD)
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val declared = RotationConsumption(true, true)

    private class MemoryDataStore : DataStore<Preferences> {
        private val lock = Mutex()
        private val state = MutableStateFlow<Preferences>(PreferencesSerializer.defaultValue)
        var writes = 0
            private set
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            lock.lock()
            try {
                val next = transform(state.value).toMutablePreferences().toPreferences()
                writes++
                state.value = next
                return next
            } finally { lock.unlock() }
        }
    }

    private data class Footprint(val payloadBytes: Map<String, Int>, val payloadRows: Map<String, Int>,
        val commandRefs: Int, val unresolvedRefs: Int, val pendingRefs: Int, val descriptorRefs: Int,
        val inputRefs: Int, val serializedBytes: Long)
    private data class RunResult(val profile: Profile, val backend: Backend, val cycles: Int,
        val counts: Map<LifecycleTransition, Int>, val elapsedMs: Double, val payloadMean: Map<String, Double>,
        val payloadMax: Map<String, Int>, val rowMax: Map<String, Int>, val peak: Footprint, val final: Footprint,
        val writes: Int, val preparationWrites: Int, val followUpWrites: Int) {
        fun line() = "backend=$backend profile=$profile cycles=$cycles distribution=$counts " +
            "elapsedMs=${"%.2f".format(elapsedMs)} payloadUtf8Mean=$payloadMean payloadUtf8Max=$payloadMax rowMax=$rowMax " +
            "peak=$peak final=$final writes=$writes preparationWrites=$preparationWrites " +
            "followUpWrites=$followUpWrites storeWrites=${writes - preparationWrites - followUpWrites}"
    }

    private fun array(p: Preferences, key: Preferences.Key<String>): JsonArray =
        Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
    private fun id(row: JsonElement) = row.jsonObject.getValue("id").jsonPrimitive.content
    private fun commandId(row: JsonElement) = row.jsonObject.getValue("commandId").jsonPrimitive.content
    private fun withId(source: ControlNode, value: String): ControlNode = node(JsonObject(
        source.toPayloadEntry().fields + ("id" to JsonPrimitive(value))).toString())

    private inner class Fixture(val profile: Profile, val backend: Backend, val file: File) {
        private val fileStorage = if (backend == Backend.FILE) ControlStoreTestStorage(file) else null
        private val memory = if (backend == Backend.MEMORY) MemoryDataStore() else null
        private val data = fileStorage?.data ?: checkNotNull(memory)
        private val owner = fileStorage?.owner ?: DataStoreAccessEpochStore(data,
            EpochIdGenerator { UUID.randomUUID().toString() })
        private val control = fileStorage?.control ?: ControlRecordStore(owner)
        private val tracker = ControlCommandTracking.forOwner(owner)
        private val commands get() = ControlReleaseFixtures.commands(tracker)
        private lateinit var waiting: CommandRef
        private lateinit var waitingBody: ControlCommandBody.Lifecycle
        private lateinit var waitingRow: String
        private lateinit var baselineCommands: Map<String, TrackedControlCommand>
        private lateinit var baselineU: Set<CommandRef>
        private lateinit var baselineP: Set<CommandRef>
        private lateinit var baselinePayloads: Map<ControlKind, String>
        private var writesAtStart = 0
        private var preparationWrites = 0
        private var followUpWrites = 0
        private var observations = 0
        private val totals = mutableMapOf<String, Long>()
        private val maxima = mutableMapOf<String, Int>()
        private val rowMaxima = mutableMapOf<String, Int>()
        private var peak = Footprint(emptyMap(), emptyMap(), 0, 0, 0, 0, 0, 0)
        private val counts = mutableMapOf<LifecycleTransition, Int>()
        private val sample get() = profile.sample
        private fun writes() = fileStorage?.storage?.writes ?: checkNotNull(memory).writes
        private suspend fun record() = data.data.first()
        private fun payload(p: Preferences, kind: ControlKind) = checkNotNull(p[ControlRecordKeys.payload(kind)])
        private fun fields(rows: List<ControlNode>) = JsonArray(rows.map { it.toPayloadEntry().fields }).toString()
        private suspend fun footprint(p: Preferences): Footprint {
            val sizes = ControlRecordKeys.allPayloads.associate { key ->
                key.wireName to checkNotNull(p[ControlRecordKeys.payload(key)]).toByteArray(Charsets.UTF_8).size
            }
            val rows = ControlRecordKeys.allPayloads.associate { key ->
                key.wireName to array(p, ControlRecordKeys.payload(key)).size
            }
            val buffer = Buffer()
            PreferencesSerializer.writeTo(p, buffer)
            val work = tracker.recoverySnapshot()
            return Footprint(sizes, rows, commands.size,
                work.unresolvedCommands.size, work.pendingReleases.size,
                commands.values.count { it.terminationDescriptor != null },
                commands.values.count { it.command.captureStateAndBody().body != null }, buffer.size)
        }
        private suspend fun observe(p: Preferences) {
            val next = footprint(p)
            observations++
            if (next.serializedBytes > peak.serializedBytes) peak = next
            next.payloadBytes.forEach { (key, size) ->
                totals[key] = totals.getOrDefault(key, 0L) + size
                maxima[key] = maxOf(maxima.getOrDefault(key, 0), size)
            }
            next.payloadRows.forEach { (key, rows) -> rowMaxima[key] = maxOf(rowMaxima.getOrDefault(key, 0), rows) }
        }
        private fun unrelated(): CommandRef = control.prepare(control.addition(ControlKind.RECOVERY_INTENT) { issued ->
            literal(ControlObligationFixtures.recovery)
            set("id", ControlScalar.Text(issued))
        })
        suspend fun seed() {
            val empty = withId(FloorGuardFixtures.empty, "$sample-waiting-guard")
            data.updateData { ControlLifecycleEvidenceFixtures.raw(demand = fields(listOf(empty)))
                .toMutablePreferences().apply {
                    this[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM] = true
                    this[DataStoreAccessEpochStore.MAY_CONTAIN_KRX] = true
                }.toPreferences() }
            waiting = control.prepareRemoveEmptyGuard(empty)
            waitingBody = checkNotNull(waiting.captureStateAndBody().body) as ControlCommandBody.Lifecycle
            val result = control.execute(waiting)
            assertTrue("waiting Confirmed: $result", result is ControlStoreResult.Confirmed)
            val p = record()
            waitingRow = array(p, evidenceKey).single { commandId(it) == waiting.id }.toString()
            tracker.markUnresolved(unrelated())
            ControlReleaseFixtures.simulatePending(tracker, unrelated())
            baselineCommands = commands.toMap()
            val work = tracker.recoverySnapshot()
            baselineU = work.unresolvedCommands
            baselineP = work.pendingReleases
            assertEquals(1, baselineU.size)
            assertEquals(1, baselineP.size)
            baselinePayloads = listOf(ControlKind.DEMAND, ControlKind.HOLD, ControlKind.RECOVERY_INTENT)
                .associateWith { payload(p, it) }
            assertBaseline(p)
            observe(p)
            writesAtStart = writes()
        }
        private fun assertBaseline(p: Preferences) {
            assertEquals("same tracker commands", baselineCommands, commands.toMap())
            val work = tracker.recoverySnapshot()
            assertEquals("unrelated U preserved", baselineU, work.unresolvedCommands)
            assertEquals("unrelated P preserved", baselineP, work.pendingReleases)
            assertTrue("executing empty", tracker.executing.isEmpty())
            assertEquals("one waiting Lifecycle row", listOf(waitingRow), array(p, evidenceKey).map { it.toString() })
            baselinePayloads.forEach { (kind, raw) -> assertEquals("$kind baseline", raw, payload(p, kind)) }
            // 6-4aD r2 (Codex REJECT r1): the waiting bundle's own state, not just the map's object identities.
            val waitingTracked = tracker.findPrepared(waiting)
            assertNotNull("waiting still registered", waitingTracked)
            assertEquals("waiting retained", ControlCommandLifecycle.RETAINED, waiting.lifecycleState)
            assertTrue("waiting confirmed", checkNotNull(waitingTracked).confirmed.get())
            assertNull("waiting has no termination descriptor", waitingTracked.terminationDescriptor)
            val body = waiting.captureStateAndBody().body
            assertSame("waiting body is the seed reference", waitingBody, body)
            assertSame("waiting input is the seed reference", waitingBody.input, (body as ControlCommandBody.Lifecycle).input)
            assertFalse("waiting not unresolved", waiting in work.unresolvedCommands)
        }
        /** Every key except COMMAND_EVIDENCE and the read barrier. */
        private fun withoutEvidence(p: Preferences) = p.toMutablePreferences().apply {
            remove(evidenceKey); remove(DataStoreAccessEpochStore.READ_BARRIER)
        }.toPreferences()
        /** Adds only this cycle's source rows. Namespace, journal, markers and existing evidence survive. */
        private suspend fun append(kind: ControlKind, vararg rows: ControlNode) {
            data.edit { p ->
                val key = ControlRecordKeys.payload(kind)
                p[key] = JsonArray(array(p, key) + rows.map { it.toPayloadEntry().fields }).toString()
            }
        }
        private fun fence(p: Preferences) = FenceV1(p[DataStoreAccessEpochStore.OWNER_UID],
            p[DataStoreAccessEpochStore.USER_EPOCH], p[DataStoreAccessEpochStore.KRX_EPOCH])
        private suspend fun prepare(transition: LifecycleTransition, index: Int): Pair<CommandRef, AttemptContext?> {
            val tag = "$sample-${transition.name.lowercase()}-$index"
            val f = DemandAuthFixtures
            val h = HoldRecoveryFixtures
            return when (transition) {
                LifecycleTransition.REBIND_REQUESTS -> {
                    val request = f.request(id = "$tag-request", binding = 2)
                    append(ControlKind.DEMAND, request)
                    control.prepareRebindRequests(listOf(request), f.binding, LifecycleOrderSource(f.life, 21)) to f.context()
                }
                LifecycleTransition.SETTLE_QUERY -> {
                    val request = f.request(id = "$tag-request")
                    val guard = f.guard(id = "$tag-guard")
                    append(ControlKind.DEMAND, guard, request)
                    control.prepareSettleQuery(listOf(request), guard, null, f.binding, f.decision(),
                        LifecycleOrderSource(f.life, 21)) to f.context()
                }
                LifecycleTransition.UPDATE_AUTH -> {
                    val guard = f.guard(id = "$tag-guard")
                    append(ControlKind.DEMAND, guard)
                    val event = LifecycleAuthEvent.Answer(f.decision(outcome = EntitlementsOutcome.Pending(false, 30)))
                    control.prepareUpdateAuth(guard, null, f.binding, event, LifecycleOrderSource(f.life, 21)) to f.context()
                }
                LifecycleTransition.END_AUTH_BINDING -> {
                    val old = AuthSnapshotV1("A", 2, 2, f.life, true, 10, 20)
                    val guard = f.guard(auth = old, id = "$tag-guard")
                    val bindingClosure = LifecycleBindingClosure(old, true, setOf("w"), setOf("w"), 5)
                    append(ControlKind.DEMAND, guard)
                    control.prepareEndAuthBinding(guard, emptyList(), f.binding, bindingClosure, f.binding,
                        LifecycleOrderSource(f.life, 21)) to f.context(f.runtime(closure = bindingClosure))
                }
                LifecycleTransition.REMOVE_EMPTY_GUARD -> {
                    val guard = withId(FloorGuardFixtures.empty, "$tag-guard")
                    append(ControlKind.DEMAND, guard)
                    control.prepareRemoveEmptyGuard(guard) to null
                }
                LifecycleTransition.RECOVER_HOLD -> {
                    // Historical k0 differs from the current k: retirement records its journal without
                    // rotating the live fence that the registered query writers still require.
                    val source = h.hold(krx = "k0", id = "$tag-hold")
                    val guard = withId(h.guard(), "$tag-guard")
                    val input = h.input(h = source, g = guard, before = fence(record()))
                    append(ControlKind.HOLD, source)
                    append(ControlKind.DEMAND, guard)
                    control.prepareRecoverHold(input, LifecycleOrderSource(h.life, 21)) to h.context(input)
                }
                LifecycleTransition.RECOVER_INTENT -> {
                    val life = LifetimeId("new-life")
                    val source = node("""{"id":${JsonPrimitive("$tag-intent")},"sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k0"}""")
                    val binding = LifecycleBinding(SettlementExecutor("A", 3, life), IdentityV1("A", 2), 1, "binding-start")
                    val input = RecoverIntentInput(source, fence(record()), binding,
                        HoldRecoveryClosure.AfterRestart(source, binding.executor, "old-tracking", true, true))
                    append(ControlKind.RECOVERY_INTENT, source)
                    val context = AttemptContext("A", 3, life, false, false,
                        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), input.closure))
                    control.prepareRecoverIntent(input, LifecycleOrderSource(life, 21)) to context
                }
            }
        }
        /**
         * Simulates caller consumption of this cycle's surviving REQUEST/guard rows, never product GC. 6-4aD r3 (Codex REJECT
         * r2): before removing anything, the DEMAND payload must be exactly the fixed descriptor's result — each DEMAND target
         * with effect REMOVE absent, each CREATE/REPLACE target present with its fixed `after` text, each requiredUnchanged
         * DEMAND row present with its fixed text, and no other row. Only those expected rows are then consumed.
         */
        private suspend fun followUp(input: ControlLifecycleDescriptor) {
            val rows = array(record(), demandKey)
            val expected = input.targets.filter { it.target.kind == ControlKind.DEMAND && it.target.effect != LifecycleEffect.REMOVE }
                .map { it.target.id to checkNotNull(it.after) { "fixed ${it.target.id} after" } } +
                input.requiredUnchanged.filter { it.target.kind == ControlKind.DEMAND }
                    .map { it.target.id to checkNotNull(it.after ?: it.before) { "fixed ${it.target.id} unchanged" } }
            assertEquals("surviving DEMAND ids are the fixed result", expected.map { it.first }.toSet(), rows.map(::id).toSet())
            assertEquals("no duplicate surviving DEMAND row", rows.size, rows.map(::id).toSet().size)
            for ((rowId, fixed) in expected) {
                val row = rows.single { id(it) == rowId }
                assertEquals("surviving $rowId text is the fixed result", fixed.toPayloadEntry(), node(row.toString()).toPayloadEntry())
            }
            val consumed = expected.map { it.first }.toSet()
            if (rows.isNotEmpty()) data.edit { current ->
                val latest = array(current, demandKey)
                assertEquals("no concurrent DEMAND change", rows, latest)
                current[demandKey] = JsonArray(latest.filter { id(it) !in consumed }).toString()
            }
        }
        suspend fun cycle(transition: LifecycleTransition, index: Int) {
            val initial = record()
            val beforeWrites = writes()
            val (command, context) = prepare(transition, index)
            preparationWrites += writes() - beforeWrites
            assertTrue("same tracker lifetime", command.ownerTrackingLifetimeId === tracker.lifetimeId)
            val input = (command.body as ControlCommandBody.Lifecycle).input
            val tracked = checkNotNull(tracker.findPrepared(command))
            val result = if (context == null) control.execute(command) else control.execute(command, context)
            assertTrue("$profile/$transition/$index Confirmed: $result", result is ControlStoreResult.Confirmed)
            assertTrue("confirmed tracking", tracked.confirmed.get())
            assertTrue("Lifecycle expected", tracked.expectedApplied is AppliedEvidence.Lifecycle)
            val applied = record()
            assertEquals("own Applied", 1, array(applied, evidenceKey).count { commandId(it) == command.id })
            observe(applied)
            val completed = control.completeLifecycleAfterConsumption(command, closure(command), declared)
            assertTrue("$profile/$transition/$index Completed: $completed", completed is ControlCompletionResult.Completed)
            assertEquals(CompletionMode.Consumed, (completed as ControlCompletionResult.Completed).mode)
            // 6-4aD r2: before any fixture follow-up, the termination changed exactly the own Applied row.
            val consumed = record()
            assertEquals("termination kept every non-evidence key", withoutEvidence(applied), withoutEvidence(consumed))
            assertEquals("termination removed only the own Applied row",
                array(applied, evidenceKey).filter { commandId(it) != command.id }, array(consumed, evidenceKey).toList())
            val beforeFollowUp = writes()
            followUp(input)
            followUpWrites += writes() - beforeFollowUp
            val after = record()
            assertEquals("own evidence removed", 0, array(after, evidenceKey).count { commandId(it) == command.id })
            assertBaseline(after)
            assertNull(tracker.findPrepared(command))
            assertEquals(ControlCommandLifecycle.TERMINATED, command.lifecycleState)
            assertNull(command.captureStateAndBody().body)
            assertNotNull(tracked.terminationDescriptor)
            val journalKey = DataStoreAccessEpochStore.PURGE_JOURNAL
            assertTrue("prior journal entries retained",
                after[journalKey].orEmpty().lines().containsAll(initial[journalKey].orEmpty().lines().filter { it.isNotEmpty() }))
            assertEquals("live fence retained for these historical sources", fence(initial), fence(after))
            assertEquals(initial[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM],
                after[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM])
            assertEquals(initial[DataStoreAccessEpochStore.MAY_CONTAIN_KRX],
                after[DataStoreAccessEpochStore.MAY_CONTAIN_KRX])
            assertEquals("SEAL unchanged by Lifecycle cycle", initial[sealKey], after[sealKey])
            observe(after)
            counts[transition] = counts.getOrDefault(transition, 0) + 1
        }
        suspend fun finish(elapsedMs: Double): RunResult {
            val p = record()
            val final = footprint(p)
            if (backend == Backend.FILE) assertEquals("serialized file bytes", final.serializedBytes, file.length())
            return RunResult(profile, backend, counts.values.sum(), counts.toMap(), elapsedMs,
                totals.mapValues { (_, total) -> total.toDouble() / observations }, maxima.toMap(), rowMaxima.toMap(), peak, final,
                writes() - writesAtStart, preparationWrites, followUpWrites)
        }
        suspend fun close() { fileStorage?.close() }
    }

    private suspend fun run(profile: Profile, backend: Backend, sequence: List<LifecycleTransition>, suffix: String): RunResult {
        val fixture = Fixture(profile, backend, File(folder.root, "$suffix.preferences_pb"))
        try {
            fixture.seed()
            val started = System.nanoTime()
            sequence.forEachIndexed { index, writer -> fixture.cycle(writer, index) }
            return fixture.finish((System.nanoTime() - started) / 1_000_000.0)
        } finally { fixture.close() }
    }
    private fun sequence(): List<LifecycleTransition> {
        val remaining = distribution.toMutableMap()
        val result = mutableListOf<LifecycleTransition>()
        while (remaining.values.any { it > 0 }) for (writer in LifecycleTransition.entries) {
            val count = remaining.getValue(writer)
            if (count > 0) { result += writer; remaining[writer] = count - 1 }
        }
        check(result.size == 1024)
        return result
    }
    private suspend fun previousReopen(): List<String> {
        val lines = mutableListOf<String>()
        for (writer in LifecycleWriters.all) {
            val file = File(folder.root, "previous-${writer.transition}.preferences_pb")
            val old = ControlStoreTestStorage(file)
            val command: CommandRef
            try { command = writer.confirm(old) } finally { old.close() }
            val before = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
            val raw = array(before, evidenceKey).single { commandId(it) == command.id }
            val next = ControlStoreTestStorage(file)
            try {
                val lifetime = ControlCommandTracking.forOwner(next.owner).lifetimeId
                assertTrue("reopened lifetime differs", lifetime !== command.ownerTrackingLifetimeId)
                val selection = PreviousEvidenceSelection(listOf(PreviousEvidenceSelection.Item.Lifecycle(command.id, node(raw.toString()))))
                val closed = PreviousReclamationClosure(setOf(command.id), lifetime, "owner-1", 7L, 7L,
                    setOf("job-1"), setOf("job-1"), setOf("job-1"), true, true, true, true)
                val result = next.control.reclaimPreviousSettlementOrLifecycleEvidence(selection, closed)
                assertTrue("previous ${writer.transition}: $result", result is PreviousEvidenceReclamationResult.Reclaimed)
                assertEquals(PreviousReclamationDisposition.RemovedNow,
                    (result as PreviousEvidenceReclamationResult.Reclaimed).disposition)
                val after = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
                assertEquals("previous own row removed", 0, array(after, evidenceKey).count { commandId(it) == command.id })
                assertEquals("SEAL raw text unchanged", before[sealKey], after[sealKey])
                lines += "previousFileReopen writer=${writer.transition} result=Reclaimed/RemovedNow sealRawUnchanged=true"
            } finally { next.close() }
        }
        return lines
    }

    @Test fun T10_measureSelectAndAccumulate(): Unit = runBlocking {
        val report = mutableListOf(
            "Lifecycle accumulation T10: 4 x 1,024 = 4,096 completed cycles; per profile REBIND_REQUESTS=147 SETTLE_QUERY=147 UPDATE_AUTH=146 END_AUTH_BINDING=146 REMOVE_EMPTY_GUARD=146 RECOVER_HOLD=146 RECOVER_INTENT=146; caveat: writes and elapsedMs include preparation and fixture follow-up writes and are not store-only costs",
            "profileStrings=ASCII, Korean, emoji/escape, long external ID; applied to REQUEST/GUARD/HOLD/RECOVERY_INTENT row IDs; ownerUid=A and originLifetimeId=life/new-life fixed by writer facts; generated operation/request/epoch IDs use canonical UUIDs",
            "fixtureFollowUp=caller consumption of exact surviving REQUEST/guard rows; no fixture clearing of journal/fence/marker/SEAL; previous L is a separate FILE reopen group",
            "REBIND_REQUESTS: add binding-2 REQUEST, prepare/execute rebind, consume L, fixture consumes the rebound REQUEST",
            "SETTLE_QUERY: add REQUEST and accepted-decision guard, prepare/execute settle, consume L, fixture consumes any surviving guard/REQUEST",
            "UPDATE_AUTH: add auth guard and pending answer, prepare/execute update, consume L, fixture consumes resulting guard/REQUEST",
            "END_AUTH_BINDING: add old-auth guard with closed work and replacement, prepare/execute end, consume L, fixture consumes resulting guard/REQUEST",
            "REMOVE_EMPTY_GUARD: add eligible empty guard, prepare/execute removal, consume L; no fixture follow-up row remains",
            "RECOVER_HOLD: add historical HOLD and guard with current fence, prepare/execute recovery, consume L, fixture consumes generated REQUEST/guard",
            "RECOVER_INTENT: add historical intent with current fence, prepare/execute recovery, consume L, fixture consumes generated REQUEST",
            "This result does not mark the §11 Lifecycle/Mutations mixed handoff group (6-4b) complete."
        )
        val measured = mutableMapOf<Pair<Profile, LifecycleTransition>, RunResult>()
        for (profile in Profile.entries) for (writer in LifecycleTransition.entries) {
            val result = run(profile, Backend.FILE, List(4) { writer }, "measure-${profile.name}-${writer.name}")
            measured[profile to writer] = result
            report += "measurement ${result.line()}"
        }
        val estimateMs = Profile.entries.sumOf { profile -> LifecycleTransition.entries.sumOf { writer ->
            distribution.getValue(writer) * checkNotNull(measured[profile to writer]).elapsedMs / 4.0
        } }
        val chosen = if (estimateMs <= 60_000.0) Backend.FILE else Backend.MEMORY
        report += "estimateMs=sum(profile,writer, distribution[writer] * four-cycle FILE elapsedMs/4)=${"%.2f".format(estimateMs)} thresholdMs=60000 chosen=$chosen"
        val full = sequence()
        val results = Profile.entries.map { run(it, chosen, full, "full-${it.name}") }
        results.forEach { result ->
            assertEquals(1024, result.cycles)
            assertEquals(distribution, result.counts)
            report += "result ${result.line()}"
        }
        report += "fullCycles=${results.sumOf { it.cycles }} fileSupplementCycles=${if (chosen == Backend.MEMORY) measured.values.sumOf { it.cycles } else 0} actualSelectedMs=${"%.2f".format(results.sumOf { it.elapsedMs })}"
        report += previousReopen()
        val output = report.joinToString("\n", postfix = "\n")
        println(output)
        System.getProperty("fxi.lifecycle.report")?.let { path -> File(path).apply { parentFile.mkdirs(); writeText(output) } }
        Unit
    }
}
