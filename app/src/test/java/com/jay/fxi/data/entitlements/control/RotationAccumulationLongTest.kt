package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
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

/** Explicit long task: actual prepare/execute/consumption owner paths in one tracker per run. */
class RotationAccumulationLongTest {
    @get:Rule val folder = TemporaryFolder()

    private enum class Profile(val sample: String) {
        ASCII("external-ascii"),
        KOREAN("외부식별자-한글"),
        EMOJI_ESCAPE("😀\"quote\\slash\tjson"),
        LONG_EXTERNAL_ID("external-" + "0123456789abcdef".repeat(32))
    }
    private enum class Axis { USER, CAPABILITY, BOTH }
    private enum class Backend { FILE, MEMORY }

    /** A single DataStore owner with serialized updateData and immutable snapshots. */
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
        val payloadBytes: Map<String, Int>, val evidence: Int, val activeSeals: Int, val settledSeals: Int,
        val commands: Int, val unresolved: Int, val pending: Int, val serializedBytes: Long
    )
    private data class RunResult(
        val profile: Profile, val axis: Axis, val backend: Backend, val cycles: Int,
        val elapsedMs: Double, val payloadMean: Map<String, Double>, val payloadMax: Map<String, Int>,
        val peak: Footprint, val final: Footprint, val writes: Int,
        val descriptorRefs: Int, val inputRefs: Int
    ) {
        fun line() = "backend=$backend profile=$profile axis=$axis cycles=$cycles elapsedMs=${"%.2f".format(elapsedMs)} " +
            "payloadUtf8Mean=$payloadMean payloadUtf8Max=$payloadMax " +
            "peak=$peak final=$final writes=$writes descriptorRefs=$descriptorRefs inputRefs=$inputRefs"
    }

    private inner class Fixture(val backend: Backend, val profile: Profile, val axis: Axis, val file: File) {
        private val fileStorage = if (backend == Backend.FILE) ControlStoreTestStorage(file) else null
        private val memory = if (backend == Backend.MEMORY) MemoryDataStore() else null
        private val data = fileStorage?.data ?: checkNotNull(memory)
        private val owner = fileStorage?.owner ?: DataStoreAccessEpochStore(data, EpochIdGenerator { UUID.randomUUID().toString() })
        private val control = ControlRecordStore(owner)
        private val tracker = ControlCommandTracking.forOwner(owner)
        private val commands get() = ControlReleaseFixtures.commands(tracker)
        private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
        private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
        // 6-2D: the profile string also rides on the owner uid, the origin lifetime and every new epoch (journal fields
        // exclude '|', newline and unpaired surrogates; every profile satisfies that). Operation/demand ids stay UUIDs.
        private val ownerUid = "${profile.sample}-owner"
        private val life = LifetimeId("${profile.sample}-life")
        private val context = AttemptContext(ownerUid, 3, life, false, false)
        private val demand = SettlementDemand(ownerUid, 3, EventOrderV1(life, 7), com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM)
        private fun seal(id: String, axisName: String, epoch: String) =
            """{"id":${JsonPrimitive(id)},"kind":"NAMESPACE","ownerUid":${JsonPrimitive(ownerUid)},"axis":"$axisName","epoch":${JsonPrimitive(epoch)}}"""
        /** Fixed-input registration (the test path for profile epochs); production issuance is not under test here. */
        private fun register(sealJson: List<String>, fence: FenceV1, tag: String): CommandRef {
            val axes = sealJson.map { Json.parseToJsonElement(it).jsonObject.getValue("axis").jsonPrimitive.content }
            val input = RotateAndSettleNamespaces(sealJson.map { node(it) }, fence, life, demand, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                if ("USER" in axes) "${profile.sample}-eu-$tag" else null, if ("CAPABILITY" in axes) "${profile.sample}-ek-$tag" else null)
            check(input.invalidInput() == null) { "profile input: ${input.invalidInput()}" }
            return tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), tracker.lifetimeId))
        }
        private lateinit var waiting: CommandRef
        private lateinit var unrelatedU: CommandRef
        private lateinit var unrelatedP: CommandRef
        private lateinit var waitingRow: String
        private lateinit var waitingSeal: String
        private lateinit var baselineDemand: String
        private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
        private lateinit var baselineCommands: Map<String, TrackedControlCommand>
        private lateinit var baselineWork: LocalRecoveryWork
        private val payloadTotal = mutableMapOf<String, Long>()
        private val payloadMax = mutableMapOf<String, Int>()
        private var peak = Footprint(emptyMap(), 0, 0, 0, 0, 0, 0, 0)
        private var maxDescriptorRefs = 0
        private var maxInputRefs = 0
        private var writesBeforeCycles = 0

        private suspend fun record(): Preferences = if (backend == Backend.FILE) {
            file.source().buffer().use { PreferencesSerializer.readFrom(it) }
        } else data.data.first()
        private fun array(p: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(checkNotNull(p[key])).jsonArray
        private fun operation(row: kotlinx.serialization.json.JsonElement) =
            row.jsonObject["settlement"]?.jsonObject?.get("operationId")?.jsonPrimitive?.content
        private fun rows(p: Preferences) = array(p, evidenceKey)
        private fun seals(p: Preferences) = array(p, sealKey)
        private suspend fun footprint(p: Preferences): Footprint {
            val all = ControlRecordKeys.allPayloads.associate { key ->
                key.wireName to checkNotNull(p[ControlRecordKeys.payload(key)]).toByteArray(Charsets.UTF_8).size
            }
            val buffer = Buffer()
            PreferencesSerializer.writeTo(p, buffer)
            val seals = seals(p)
            val work = tracker.recoverySnapshot()
            return Footprint(all, rows(p).size, seals.count { operation(it) == null },
                seals.count { operation(it) != null }, commands.size, work.unresolvedCommands.size,
                work.pendingReleases.size, buffer.size)
        }
        private suspend fun observe(p: Preferences): Footprint {
            val next = footprint(p)
            if (next.serializedBytes > peak.serializedBytes) peak = next
            next.payloadBytes.forEach { (key, bytes) ->
                payloadTotal[key] = payloadTotal.getOrDefault(key, 0L) + bytes
                payloadMax[key] = maxOf(payloadMax.getOrDefault(key, 0), bytes)
            }
            return next
        }
        private fun unrelated(): CommandRef = control.prepare(control.addition(ControlKind.RECOVERY_INTENT) { issued ->
            literal(ControlObligationFixtures.recovery)
            set("id", ControlScalar.Text(issued))
        })
        suspend fun seed() {
            val seedUser = "${profile.sample}-eu-seed"; val seedKrx = "${profile.sample}-ek-seed"
            data.updateData { NamespaceSettlementFixtures.raw("[${seal("${profile.sample}-s", "USER", seedUser)}]").toMutablePreferences().apply {
                this[DataStoreAccessEpochStore.OWNER_UID] = ownerUid
                this[DataStoreAccessEpochStore.USER_EPOCH] = seedUser
                this[DataStoreAccessEpochStore.KRX_EPOCH] = seedKrx
            }.toPreferences() }
            waiting = register(listOf(seal("${profile.sample}-s", "USER", seedUser)), FenceV1(ownerUid, seedUser, seedKrx), "waiting")
            assertTrue("waiting bundle apply", control.execute(waiting, context) is ControlStoreResult.Confirmed)
            val p = record()
            waitingRow = rows(p).single { it.jsonObject.getValue("commandId").jsonPrimitive.content == waiting.id }.toString()
            waitingSeal = seals(p).single { operation(it) == waiting.id }.toString()
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
            assertEquals("same owner/tracker command baseline", baselineCommands, commands.toMap())
            assertEquals("unrelated U preserved", baselineWork.unresolvedCommands,
                tracker.recoverySnapshot().unresolvedCommands)
            assertEquals("unrelated P preserved", baselineWork.pendingReleases,
                tracker.recoverySnapshot().pendingReleases)
            assertTrue(unrelatedU in tracker.recoverySnapshot().unresolvedCommands)
            assertTrue(unrelatedP in tracker.recoverySnapshot().pendingReleases)
            assertTrue(tracker.executing.isEmpty())
            assertEquals("waiting Applied unchanged", waitingRow,
                rows(p).single { it.jsonObject.getValue("commandId").jsonPrimitive.content == waiting.id }.toString())
            assertEquals("waiting seal unchanged", waitingSeal, seals(p).single { operation(it) == waiting.id }.toString())
            assertEquals("REQUEST payload at baseline", baselineDemand, p[demandKey])
            assertEquals("own Applied baseline", 1, rows(p).size)
            assertEquals("settled seal baseline", 1, seals(p).count { operation(it) != null })
            assertEquals("no active fixture seal after cycle", 0, seals(p).count { operation(it) == null })
        }
        private fun active(id: String, axisName: String, epoch: String): kotlinx.serialization.json.JsonElement =
            Json.parseToJsonElement(seal(id, axisName, epoch))
        suspend fun cycle(index: Int) {
            val before = record()
            val fence = FenceV1(ownerUid, before[DataStoreAccessEpochStore.USER_EPOCH], before[DataStoreAccessEpochStore.KRX_EPOCH])
            val targets = buildList {
                if (axis != Axis.CAPABILITY) add(active("${profile.sample}-u-$index", "USER", checkNotNull(fence.userAccessEpoch)))
                if (axis != Axis.USER) add(active("${profile.sample}-k-$index", "CAPABILITY", checkNotNull(fence.krxCapabilityEpoch)))
            }
            // The fixture supplies only the next active seal. Owner code performs both rotation and reclamation.
            data.edit { p -> p[sealKey] = JsonArray(seals(p) + targets).toString() }
            val command = register(targets.map { it.toString() }, fence, index.toString())
            val tracked = checkNotNull(tracker.findPrepared(command))
            val confirmed = control.execute(command, context)
            assertTrue("$profile/$axis/$index Confirmed: $confirmed", confirmed is ControlStoreResult.Confirmed)
            assertNotNull("confirmed receipt", (confirmed as ControlStoreResult.Confirmed).settlement)
            val applied = record()
            assertEquals("own Applied added", 1, rows(applied).count {
                it.jsonObject.getValue("commandId").jsonPrimitive.content == command.id
            })
            assertEquals("own settled seals added", targets.size, seals(applied).count { operation(it) == command.id })
            observe(applied)
            // The caller consumed the returned result and receipt, closed its entries and receipts, and owns follow-up.
            val demandId = (checkNotNull(command.captureStateAndBody().body) as ControlCommandBody.RotateAndSettle).input.demandId
            val completed = control.completeAfterConsumption(command, closure(command), declared)
            assertTrue("$profile/$axis/$index Completed: $completed", completed is ControlCompletionResult.Completed)
            assertEquals(CompletionMode.Consumed, (completed as ControlCompletionResult.Completed).mode)
            // 6-2D: the caller's declared follow-up (D2c's REQUEST consumption, not the store's job) is simulated here by
            // removing exactly this cycle's REQUEST; without it a long profile's REQUEST payload reaches the codec limit
            // within 256 cycles (6-2D_T9_request_overflow_probe.log, cycle 53 of LONG_EXTERNAL_ID).
            data.edit { p ->
                val requests = array(p, demandKey)
                val kept = requests.filter { it.jsonObject.getValue("id").jsonPrimitive.content != demandId }
                check(requests.size - kept.size == 1) { "exactly this cycle's REQUEST" }
                p[demandKey] = JsonArray(kept).toString()
            }
            val after = record()
            assertEquals("own Applied reclaimed", 0, rows(after).count {
                it.jsonObject.getValue("commandId").jsonPrimitive.content == command.id
            })
            assertEquals("own settled seals reclaimed", 0, seals(after).count { operation(it) == command.id })
            assertBaseline(after)
            assertNull(tracker.findPrepared(command))
            assertNull(command.captureStateAndBody().body)
            assertEquals(ControlCommandLifecycle.TERMINATED, command.lifecycleState)
            assertNotNull("completion fixed a descriptor", tracked.terminationDescriptor)
            maxDescriptorRefs = maxOf(maxDescriptorRefs, commands.values.count { it.terminationDescriptor != null })
            maxInputRefs = maxOf(maxInputRefs, if (command.captureStateAndBody().body == null) 0 else 1)
            observe(after)
        }
        suspend fun finish(cycles: Int, elapsedMs: Double): RunResult {
            val final = footprint(record())
            val writes = (fileStorage?.storage?.writes ?: checkNotNull(memory).writes) - writesBeforeCycles
            if (backend == Backend.FILE) assertEquals("serialized file bytes", final.serializedBytes, file.length())
            return RunResult(profile, axis, backend, cycles, elapsedMs,
                payloadTotal.mapValues { (_, sum) -> sum.toDouble() / (cycles * 2 + 1) },
                payloadMax.toMap(), peak, final, writes, maxDescriptorRefs, maxInputRefs)
        }
        suspend fun close() { fileStorage?.close() }
    }

    private suspend fun run(profile: Profile, axis: Axis, backend: Backend, cycles: Int, suffix: String): RunResult {
        val fixture = Fixture(backend, profile, axis, File(folder.root, "$suffix.preferences_pb"))
        try {
            fixture.seed()
            val start = System.nanoTime()
            repeat(cycles) { fixture.cycle(it) }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            return fixture.finish(cycles, elapsed)
        } finally { fixture.close() }
    }

    @Test fun T9_measureThenRunChosenBackend(): Unit = runBlocking {
        val report = mutableListOf("Rotation accumulation T9: 3 axes x 4 profiles x 256 = 3072 full cycles",
            "profileFields=sealIds,ownerUid(record/fence/context/demand/seals),originLifetimeId,newEpochs(USER/CAPABILITY); " +
                "operationId/demandId=UUID (code-issued kind, not replaced); each cycle's REQUEST removed by the fixture as the declared caller follow-up",
            "caveat: writes and elapsedMs include that fixture REQUEST deletion (one write per cycle); they are not store-only figures")
        val measured = Profile.entries.map { profile ->
            run(profile, Axis.USER, Backend.FILE, 16, "measure-${profile.name}").also { report += "measurement ${it.line()}" }
        }
        val estimateMs = 3.0 * 256 * measured.sumOf { it.elapsedMs / it.cycles }
        val backend = if (estimateMs <= 60_000.0) Backend.FILE else Backend.MEMORY
        report += "estimateMs=3*256*sum(profileElapsedMs/16)=${"%.2f".format(estimateMs)} thresholdMs=60000 chosen=$backend"
        val results = mutableListOf<RunResult>()
        for (profile in Profile.entries) for (axis in Axis.entries) {
            results += run(profile, axis, backend, 256, "full-${profile.name}-${axis.name}")
        }
        if (backend == Backend.MEMORY) {
            for (axis in Axis.entries) results += run(Profile.ASCII, axis, Backend.FILE, 256, "file-ascii-${axis.name}")
            for (profile in Profile.entries.filter { it != Profile.ASCII }) for (axis in Axis.entries) {
                results += run(profile, axis, Backend.FILE, 8, "file-sample-${profile.name}-${axis.name}")
            }
        }
        results.forEach { report += "result ${it.line()}" }
        report += "actualSelectedMs=${"%.2f".format(results.sumOf { it.elapsedMs })} " +
            "fullCycles=${results.filter { it.backend == backend }.sumOf { it.cycles }} " +
            "fileSupplementCycles=${if (backend == Backend.MEMORY) results.filter { it.backend == Backend.FILE }.sumOf { it.cycles } else 0}"
        val output = report.joinToString("\n", postfix = "\n")
        println(output)
        System.getProperty("fxi.rotation.report")?.let { path -> File(path).apply { parentFile.mkdirs(); writeText(output) } }
        Unit
    }
}
