package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.RefreshIntent
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

/** Explicit opt-in T10: each run retains one tracker through all complete R/N/L cycles. */
class SettlementAccumulationLongTest {
    @get:Rule val folder = TemporaryFolder()

    private enum class Profile(val sample: String) {
        ASCII("external-ascii"),
        KOREAN("외부식별자-한글"),
        EMOJI_ESCAPE("😀\"quote\\slash\tjson"),
        LONG_EXTERNAL_ID("external-" + "0123456789abcdef".repeat(32))
    }
    private enum class Shape(val kind: String, val axes: List<String>, val request: Boolean) {
        R_DEMAND("R", listOf("USER"), true),
        R_DEPARTED("R", listOf("USER"), false),
        N_USER("N", listOf("USER"), true),
        N_CAPABILITY("N", listOf("CAPABILITY"), true),
        N_BOTH("N", listOf("USER", "CAPABILITY"), true),
        L_USER("L", listOf("USER"), false),
        L_CAPABILITY("L", listOf("CAPABILITY"), false),
        L_BOTH("L", listOf("USER", "CAPABILITY"), false)
    }
    private enum class Backend { FILE, MEMORY }

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

    private data class Footprint(
        val payloadBytes: Map<String, Int>, val rows: Int, val activeSeals: Int, val settledSeals: Int,
        val commands: Int, val unresolved: Int, val pending: Int, val journalLines: Int, val serializedBytes: Long
    )
    private data class RunResult(
        val profile: Profile, val shape: Shape, val backend: Backend, val cycles: Int,
        val elapsedMs: Double, val payloadMean: Map<String, Double>, val payloadMax: Map<String, Int>,
        val peak: Footprint, val final: Footprint, val writes: Int,
        val descriptorRefs: Int, val inputRefs: Int
    ) {
        fun line() = "backend=$backend profile=$profile shape=$shape cycles=$cycles elapsedMs=${"%.2f".format(elapsedMs)} " +
            "payloadUtf8Mean=$payloadMean payloadUtf8Max=$payloadMax peak=$peak final=$final " +
            "writes=$writes descriptorRefs=$descriptorRefs inputRefs=$inputRefs"
    }

    private inner class Fixture(val backend: Backend, val profile: Profile, val shape: Shape, val file: File) {
        private val fileStorage = if (backend == Backend.FILE) ControlStoreTestStorage(file) else null
        private val memory = if (backend == Backend.MEMORY) MemoryDataStore() else null
        private val data = fileStorage?.data ?: checkNotNull(memory)
        private val owner = fileStorage?.owner ?: DataStoreAccessEpochStore(data, EpochIdGenerator { UUID.randomUUID().toString() })
        private val control = ControlRecordStore(owner)
        private val tracker = ControlCommandTracking.forOwner(owner)
        private val commands get() = ControlReleaseFixtures.commands(tracker)
        private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
        private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
        private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
        private val ownerUid = "${profile.sample}-current"
        private val departedUid = "${profile.sample}-departed"
        private val life = LifetimeId("${profile.sample}-origin")
        private val context = AttemptContext(ownerUid, 3, life, false, false)
        private val executor = SettlementExecutor(ownerUid, 3, life)
        private val demand = SettlementDemand(ownerUid, 3, EventOrderV1(life, 7), RefreshIntent.FORCE_PREMIUM)
        private lateinit var waiting: CommandRef
        private lateinit var unrelatedU: CommandRef
        private lateinit var unrelatedP: CommandRef
        private lateinit var waitingRow: String
        private lateinit var waitingSeals: List<String>
        private lateinit var baselineDemand: String
        private lateinit var baselineCommands: Map<String, TrackedControlCommand>
        private lateinit var baselineWork: LocalRecoveryWork
        private var fixedJournal: String? = null
        private val payloadTotal = mutableMapOf<String, Long>()
        private val payloadMax = mutableMapOf<String, Int>()
        private var peak = Footprint(emptyMap(), 0, 0, 0, 0, 0, 0, 0, 0)
        private var maxDescriptorRefs = 0
        private var maxInputRefs = 0
        private var writesBeforeCycles = 0

        private fun epoch(axis: String, index: Int): String = UUID.nameUUIDFromBytes(
            "${profile.sample}|${shape.name}|$axis|$index".toByteArray(Charsets.UTF_8)
        ).toString()
        private fun initialEpoch(axis: String): String = if (shape.kind == "N") epoch(axis, -2)
            else "${profile.sample}-$axis-current"
        private fun seal(id: String, owner: String, axis: String, kind: String, epoch: String? = null) =
            """{"id":${JsonPrimitive(id)},"kind":${JsonPrimitive(kind)},"ownerUid":${JsonPrimitive(owner)},"axis":${JsonPrimitive(axis)}""" +
                (if (epoch == null) "}" else ",\"epoch\":${JsonPrimitive(epoch)}}")
        private fun fence(p: Preferences) = FenceV1(ownerUid, p[DataStoreAccessEpochStore.USER_EPOCH],
            p[DataStoreAccessEpochStore.KRX_EPOCH])
        private fun makeInput(index: Int, before: FenceV1): Pair<HandoverSettlementInput, List<String>> {
            val tag = if (index < 0) "waiting" else index.toString()
            val operationId = UUID.randomUUID().toString()
            val demandId = if (shape.request) UUID.randomUUID().toString() else null
            val active = when (shape.kind) {
                "R" -> listOf(seal("${profile.sample}-$tag-r", if (shape.request) ownerUid else departedUid,
                    "USER", "NAMESPACE", "${profile.sample}-${shape.name}-retired"))
                "N" -> shape.axes.flatMap { axis ->
                    listOf(seal("${profile.sample}-$tag-$axis-null", ownerUid, axis, "NULL_NAMESPACE"),
                        seal("${profile.sample}-$tag-$axis-companion", ownerUid, axis, "NAMESPACE",
                            if (axis == "USER") checkNotNull(before.userAccessEpoch) else checkNotNull(before.krxCapabilityEpoch)))
                }
                "L" -> shape.axes.map { axis -> seal("${profile.sample}-$tag-$axis-null", departedUid,
                    axis, "NULL_NAMESPACE") }
                else -> error("unknown shape")
            }
            val input: HandoverSettlementInput = when (shape.kind) {
                "R" -> RetiredNamespaceFixtures.spec(target = node(active.single()), fence = before, exec = executor,
                    op = operationId, did = demandId, demand = if (shape.request) demand else null)
                "N" -> CurrentNullFixtures.spec(
                    nulls = active.filter { Json.parseToJsonElement(it).jsonObject.getValue("kind").jsonPrimitive.content == "NULL_NAMESPACE" }.map(::node),
                    companions = active.filter { Json.parseToJsonElement(it).jsonObject.getValue("kind").jsonPrimitive.content == "NAMESPACE" }.map(::node),
                    fence = before, exec = executor, op = operationId, did = checkNotNull(demandId), demand = demand,
                    u = epoch("USER", index).takeIf { "USER" in shape.axes },
                    k = epoch("CAPABILITY", index).takeIf { "CAPABILITY" in shape.axes })
                "L" -> RetiredNullFixtures.spec(targets = active.map(::node), fence = before, exec = executor, op = operationId)
                else -> error("unknown shape")
            }
            val problem = when (input) {
                is RetiredNamespaceSettlement -> RetiredNamespaceFixtures.transition.invalidInput(input)
                is CurrentNullSettlement -> CurrentNullFixtures.transition.invalidInput(input)
                is RetiredNullSettlement -> RetiredNullFixtures.transition.invalidInput(input)
            }
            check(problem == null) { "${profile.name}/${shape.name}/$index fixed input: $problem" }
            return input to active
        }
        private fun register(input: HandoverSettlementInput): CommandRef {
            val body = when (input) {
                is RetiredNamespaceSettlement -> ControlCommandBody.SettleRetiredNamespace(input)
                is CurrentNullSettlement -> ControlCommandBody.RotateAndSettleCurrentNull(input)
                is RetiredNullSettlement -> ControlCommandBody.SettleRetiredNull(input)
            }
            return tracker.registerPrepared(CommandRef(input.operationId, body, tracker.lifetimeId))
        }
        private suspend fun record(): Preferences = if (backend == Backend.FILE) {
            file.source().buffer().use { PreferencesSerializer.readFrom(it) }
        } else data.data.first()
        private fun array(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
        private fun rows(p: Preferences) = array(p, evidenceKey)
        private fun seals(p: Preferences) = array(p, sealKey)
        private fun operation(row: JsonElement) = row.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
        private fun rowCommand(row: JsonElement) = row.jsonObject.getValue("commandId").jsonPrimitive.content
        private fun journal(p: Preferences) = p[DataStoreAccessEpochStore.PURGE_JOURNAL]
        private suspend fun footprint(p: Preferences): Footprint {
            val bytes = ControlRecordKeys.allPayloads.associate { key ->
                key.wireName to checkNotNull(p[ControlRecordKeys.payload(key)]).toByteArray(Charsets.UTF_8).size
            }
            val buffer = Buffer()
            PreferencesSerializer.writeTo(p, buffer)
            val seals = seals(p)
            val work = tracker.recoverySnapshot()
            return Footprint(bytes, rows(p).size, seals.count { operation(it) == null },
                seals.count { operation(it) != null }, commands.size, work.unresolvedCommands.size,
                work.pendingReleases.size, journal(p)?.lines()?.size ?: 0, buffer.size)
        }
        private suspend fun observe(p: Preferences) {
            val next = footprint(p)
            if (next.serializedBytes > peak.serializedBytes) peak = next
            next.payloadBytes.forEach { (key, bytes) ->
                payloadTotal[key] = payloadTotal.getOrDefault(key, 0L) + bytes
                payloadMax[key] = maxOf(payloadMax.getOrDefault(key, 0), bytes)
            }
            maxDescriptorRefs = maxOf(maxDescriptorRefs, commands.values.count { it.terminationDescriptor != null })
            maxInputRefs = maxOf(maxInputRefs, commands.values.count { it.command.captureStateAndBody().body != null })
        }
        private fun unrelated(): CommandRef = control.prepare(control.addition(ControlKind.RECOVERY_INTENT) { issued ->
            literal(ControlObligationFixtures.recovery)
            set("id", ControlScalar.Text(issued))
        })
        suspend fun seed() {
            data.updateData { NamespaceSettlementFixtures.raw("[]").toMutablePreferences().apply {
                this[DataStoreAccessEpochStore.OWNER_UID] = ownerUid
                this[DataStoreAccessEpochStore.USER_EPOCH] = initialEpoch("USER")
                this[DataStoreAccessEpochStore.KRX_EPOCH] = initialEpoch("CAPABILITY")
            }.toPreferences() }
            val (input, active) = makeInput(-1, fence(record()))
            data.edit { p -> p[sealKey] = JsonArray(seals(p) + active.map { Json.parseToJsonElement(it) }).toString() }
            waiting = register(input)
            val result = control.execute(waiting, context)
            assertTrue("waiting $profile/$shape Confirmed: $result", result is ControlStoreResult.Confirmed)
            assertNotNull((result as ControlStoreResult.Confirmed).handoverSettlement)
            val p = record()
            waitingRow = rows(p).single { rowCommand(it) == waiting.id }.toString()
            waitingSeals = seals(p).filter { operation(it) == waiting.id }.map { it.toString() }
            assertEquals(active.size, waitingSeals.size)
            unrelatedU = unrelated().also { tracker.markUnresolved(it) }
            unrelatedP = unrelated().also { ControlReleaseFixtures.simulatePending(tracker, it) }
            baselineDemand = checkNotNull(p[demandKey])
            baselineCommands = commands.toMap()
            baselineWork = tracker.recoverySnapshot()
            assertBaseline(p)
            observe(p)
            writesBeforeCycles = fileStorage?.storage?.writes ?: checkNotNull(memory).writes
        }
        private fun assertBaseline(p: Preferences) {
            assertEquals("same tracker commands", baselineCommands, commands.toMap())
            val work = tracker.recoverySnapshot()
            assertEquals("unrelated U preserved", baselineWork.unresolvedCommands, work.unresolvedCommands)
            assertEquals("unrelated P preserved", baselineWork.pendingReleases, work.pendingReleases)
            assertTrue(unrelatedU in work.unresolvedCommands)
            assertTrue(unrelatedP in work.pendingReleases)
            assertTrue(tracker.executing.isEmpty())
            assertEquals("waiting row original", waitingRow, rows(p).single { rowCommand(it) == waiting.id }.toString())
            assertEquals("waiting seals original", waitingSeals, seals(p).filter { operation(it) == waiting.id }.map { it.toString() })
            assertEquals("one waiting Applied", 1, rows(p).size)
            assertEquals("only waiting seals settled", waitingSeals.size, seals(p).count { operation(it) != null })
            assertEquals("no active fixture seal", 0, seals(p).count { operation(it) == null })
            assertEquals("REQUEST payload baseline", baselineDemand, p[demandKey])
            assertEquals("one shared journal key per axis", shape.axes.size, journal(p)?.lines()?.size ?: 0)
            if (fixedJournal != null) assertEquals("PURGE_JOURNAL fixed after first cycle", fixedJournal, journal(p))
        }
        suspend fun cycle(index: Int) {
            val before = record()
            val (input, active) = makeInput(index, fence(before))
            data.edit { p -> p[sealKey] = JsonArray(seals(p) + active.map { Json.parseToJsonElement(it) }).toString() }
            val command = register(input)
            val tracked = checkNotNull(tracker.findPrepared(command))
            val result = control.execute(command, context)
            assertTrue("$profile/$shape/$index Confirmed: $result", result is ControlStoreResult.Confirmed)
            assertNotNull("handover receipt", (result as ControlStoreResult.Confirmed).handoverSettlement)
            // T10.2: the caller re-confirms the settled witness before consuming the receipt.
            val reconfirmed = control.execute(command, context)
            assertTrue("$profile/$shape/$index reconfirmed: $reconfirmed", reconfirmed is ControlStoreResult.Confirmed)
            assertNotNull("reconfirmed handover receipt", (reconfirmed as ControlStoreResult.Confirmed).handoverSettlement)
            val applied = record()
            assertEquals("own Applied added", 1, rows(applied).count { rowCommand(it) == command.id })
            assertEquals("own settled seals added", active.size, seals(applied).count { operation(it) == command.id })
            assertEquals("epoch chain follows the recorded fence", if (input is CurrentNullSettlement) input.after else input.before,
                fence(applied))
            observe(applied)
            val completed = control.completeSettlementAfterConsumption(command, closure(command), declared)
            assertTrue("$profile/$shape/$index Completed: $completed", completed is ControlCompletionResult.Completed)
            assertEquals(CompletionMode.Consumed, (completed as ControlCompletionResult.Completed).mode)
            if (shape.request) {
                val did = when (input) {
                    is RetiredNamespaceSettlement -> checkNotNull(input.demandId)
                    is CurrentNullSettlement -> input.demandId
                    is RetiredNullSettlement -> error("L cannot issue REQUEST")
                }
                data.edit { p ->
                    val requests = array(p, demandKey)
                    val kept = requests.filter { it.jsonObject.getValue("id").jsonPrimitive.content != did }
                    check(requests.size - kept.size == 1) { "exactly this cycle's REQUEST" }
                    p[demandKey] = JsonArray(kept).toString()
                }
            }
            val after = record()
            assertEquals("own Applied reclaimed", 0, rows(after).count { rowCommand(it) == command.id })
            assertEquals("own settled seals reclaimed", 0, seals(after).count { operation(it) == command.id })
            if (index == 0) fixedJournal = checkNotNull(journal(after)) { "first cycle must leave a journal key" }
            assertBaseline(after)
            assertNull(tracker.findPrepared(command))
            assertNull(command.captureStateAndBody().body)
            assertEquals(ControlCommandLifecycle.TERMINATED, command.lifecycleState)
            assertNotNull("fixed termination descriptor", tracked.terminationDescriptor)
            observe(after)
        }
        suspend fun finish(cycles: Int, elapsedMs: Double): RunResult {
            val final = footprint(record())
            val writes = (fileStorage?.storage?.writes ?: checkNotNull(memory).writes) - writesBeforeCycles
            if (backend == Backend.FILE) assertEquals("serialized file bytes", final.serializedBytes, file.length())
            return RunResult(profile, shape, backend, cycles, elapsedMs,
                payloadTotal.mapValues { (_, sum) -> sum.toDouble() / (cycles * 2 + 1) },
                payloadMax.toMap(), peak, final, writes, maxDescriptorRefs, maxInputRefs)
        }
        suspend fun close() { fileStorage?.close() }
    }

    private suspend fun run(profile: Profile, shape: Shape, backend: Backend, cycles: Int, suffix: String): RunResult {
        val fixture = Fixture(backend, profile, shape, File(folder.root, "$suffix.preferences_pb"))
        try {
            fixture.seed()
            val start = System.nanoTime()
            repeat(cycles) { fixture.cycle(it) }
            return fixture.finish(cycles, (System.nanoTime() - start) / 1_000_000.0)
        } finally { fixture.close() }
    }

    @Test fun T10_measureThenRunChosenBackend(): Unit = runBlocking {
        val report = mutableListOf(
            "Settlement accumulation T10: ${Shape.entries.size} shapes x ${Profile.entries.size} profiles x 256 = ${Shape.entries.size * Profile.entries.size * 256} full cycles",
            "profileFields=sealIds,ownerUid(record/fence/context/demand/seals),originLifetimeId; N newEpochs=canonical name UUIDs derived from profile+shape+axis+cycle; operationId/demandId=UUID",
            "N companionEpoch=current record fence each cycle; journal keys reuse owner|axis|null. R reuses owner|USER|retiredEpoch and L reuses departedOwner|axis|null; journal fixed after cycle 1",
            "cycle: apply -> witness reconfirmation (second execute) -> receipt consumption -> reclamation",
            "caveat: writes and elapsedMs include fixture REQUEST deletion writes for R-demand and N; they are not store-only figures"
        )
        val representative = listOf(Shape.R_DEMAND, Shape.N_BOTH, Shape.L_BOTH)
        val measured = representative.flatMap { shape -> Profile.entries.map { profile ->
            run(profile, shape, Backend.FILE, 16, "measure-${shape.name}-${profile.name}").also { report += "measurement ${it.line()}" }
        } }
        val estimateMs = Shape.entries.sumOf { shape ->
            val representativeShape = when (shape.kind) { "R" -> Shape.R_DEMAND; "N" -> Shape.N_BOTH; else -> Shape.L_BOTH }
            256.0 * measured.filter { it.shape == representativeShape }.sumOf { it.elapsedMs / it.cycles }
        }
        val backend = if (estimateMs <= 60_000.0) Backend.FILE else Backend.MEMORY
        report += "estimateMs=256*sum(each-shape-kind representative four-profile elapsedMs/16, weighted by kind shape count)=${"%.2f".format(estimateMs)} thresholdMs=60000 chosen=$backend"
        val results = mutableListOf<RunResult>()
        for (profile in Profile.entries) for (shape in Shape.entries) {
            results += run(profile, shape, backend, 256, "full-${profile.name}-${shape.name}")
        }
        if (backend == Backend.MEMORY) {
            for (shape in Shape.entries) results += run(Profile.ASCII, shape, Backend.FILE, 256, "file-ascii-${shape.name}")
            for (profile in Profile.entries.filter { it != Profile.ASCII }) for (shape in Shape.entries) {
                results += run(profile, shape, Backend.FILE, 8, "file-sample-${profile.name}-${shape.name}")
            }
        }
        results.forEach { report += "result ${it.line()}" }
        report += "actualSelectedMs=${"%.2f".format(results.sumOf { it.elapsedMs })} " +
            "fullCycles=${results.filter { it.backend == backend }.sumOf { it.cycles }} " +
            "fileSupplementCycles=${if (backend == Backend.MEMORY) results.filter { it.backend == Backend.FILE }.sumOf { it.cycles } else 0}"
        val output = report.joinToString("\n", postfix = "\n")
        println(output)
        System.getProperty("fxi.settlement.report")?.let { path -> File(path).apply { parentFile.mkdirs(); writeText(output) } }
        Unit
    }
}
