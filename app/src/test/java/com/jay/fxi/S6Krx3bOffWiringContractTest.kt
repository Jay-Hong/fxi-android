package com.jay.fxi

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.domain.model.*
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import com.jay.fxi.ui.premium.graph.GraphV2ScreenState
import kotlinx.datetime.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6Krx3bOffWiringContractTest {
    private fun marked(row: String, body: () -> Unit) {
        try { body() } catch (failure: AssertionError) {
            throw AssertionError("contract($row): delegated frozen/regression boundary: ${failure.message}", failure)
        }
    }

    @Test fun o03_offGraphRecorderTraceMatchesHead() {
        marked("O03") {
            val off = S6KrxOffWiringContractTest()
            off.o01_originalDormantGolden_isUnchanged()
            off.o02_offSessionAndBootstrapTrace_matchesHead()
            // Existing PAC/SUA regressions accompany the independent actual-HEAD graph comparison.
            val consumers = KrxConsumerOffContrastTest()
            consumers.r11_1_suaOff_generalIsHeadsAndNoKrxUseIsIssuedOrAdmitted()
            consumers.r11_2_gateOff_everyAnswerIsHeads()
            consumers.r11_3_seedOff_theSeedReadIsHeads()
            consumers.r11_4_sendReadOff_generalIsHeadsNoKrxUseAndTheMarkerIsEpochZero()
            consumers.r11_5_restOff_unknownTopicIsNeverHandedOver()
        }
        val resource = "krx/s63b1/o03_head_trace.json"
        val frozen = try {
            val stream = javaClass.classLoader!!.getResourceAsStream(resource)
                ?: throw AssertionError("contract(O03): missing immutable actual-HEAD trace resource $resource")
            val bytes = stream.use { it.readBytes() }
            val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val provenancePath = "krx/s63b1/o03_head_trace.provenance.json"
            val provenanceBytes = javaClass.classLoader!!.getResourceAsStream(provenancePath)?.use { it.readBytes() }
                ?: throw AssertionError("contract(O03): missing actual-HEAD trace provenance")
            val provenance = kotlinx.serialization.json.Json.parseToJsonElement(provenanceBytes.toString(Charsets.UTF_8))
                as? kotlinx.serialization.json.JsonObject
                ?: throw AssertionError("contract(O03): invalid actual-HEAD trace provenance")
            assertEquals("contract(O03): provenance HEAD", kotlinx.serialization.json.JsonPrimitive(S6Krx3bO03HeadProbe.HEAD), provenance["head_commit"])
            assertEquals("contract(O03): immutable trace SHA matches provenance", kotlinx.serialization.json.JsonPrimitive(actualSha), provenance["output_sha256"])
            assertTrue("contract(O03): provenance records the probe source SHA",
                (provenance["probe_source_sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.matches(Regex("[0-9a-f]{64}")) == true)
            assertTrue("contract(O03): provenance records the HEAD execution command",
                (provenance["command"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true)
            val root = kotlinx.serialization.json.Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                as? kotlinx.serialization.json.JsonObject
                ?: throw AssertionError("contract(O03): actual-HEAD trace root must be a JSON object")
            assertEquals("contract(O03): frozen schema", kotlinx.serialization.json.JsonPrimitive(1), root["schema"])
            assertEquals("contract(O03): frozen HEAD", kotlinx.serialization.json.JsonPrimitive(S6Krx3bO03HeadProbe.HEAD), root["head"])
            (root["columns"] as? kotlinx.serialization.json.JsonObject)
                ?: throw AssertionError("contract(O03): frozen columns must be a JSON object")
        } catch (failure: Throwable) {
            if (failure is AssertionError && failure.message.orEmpty().startsWith("contract(")) throw failure
            throw AssertionError("contract(O03): missing or invalid immutable actual-HEAD trace resource", failure)
        }
        assertEquals("contract(O03): frozen resource has exactly the17 columns", OFF_COLUMNS.toSet(), frozen.keys)
        for (column in OFF_COLUMNS) {
            val headWiring = S6Krx3bO03HeadProbe.trace(column)
            assertEquals("contract(O03/$column): actual HEAD resource equals this build's HEAD wiring", frozen[column], headWiring)
            val connected = S6Krx3bO03HeadProbe.trace(column, ::connectedOffParts)
            assertEquals("contract(O03/$column): HEAD wiring equals connected candidate OFF", headWiring, connected)
        }
    }

    /** Current implementation's real assembly/ingress, driven by exactly the HEAD-compatible probe scenarios. */
    private fun connectedOffParts(g: O03HeadWorld): O03HeadParts = object : O03HeadParts {
        private val actual = g.assembly(g::graphSnapshot)
        private var captures = 0
        private var cutoffs = 0
        private val ingress = object : KrxGraphIngress {
            override fun capture(scope: com.jay.fxi.data.entitlements.purge.KrxMemoryScope) =
                actual.krxGraphIngress.capture(scope).also { captures++ }
            override fun topicUnavailable(original: com.jay.fxi.data.entitlements.purge.KrxMemoryScope?) =
                actual.krxGraphIngress.topicUnavailable(original).also { cutoffs++ }
        }
        override val fences get() = actual.fences
        override val accessGate get() = actual.gate
        override val record get() = actual.recorder
        override val requests get() = actual.coordinator
        override val inputSink get() = actual.sink
        private val input = DetachableTopicGraphSink(inputSink, ingress)
        override lateinit var runtime: TopicRuntime
        override fun startGraph(deferSink: Boolean) {
            if (!deferSink) actual.start()
            else { requests.start(); record.start() }
        }
        override fun startSession(leaseSeconds: Long) {
            runtime = g.session.runtimeFactory().create(input, fences, ingress)
            g.startSession(this, runtime, leaseSeconds)
        }
        override fun seededRequests() = g.seededRequests(this)
        override fun verifyOff() {
            g.eq("OFF source capture calls zero", 0, captures)
            g.eq("OFF D1 ingress calls zero", 0, cutoffs)
            g.eq("OFF gate cutoff revision zero", 0L, accessGate.krxGraphRevisions.value)
            g.yes("OFF has no original KRX sidecars", record.state.value.krxSeriesOwners.isEmpty() &&
                record.state.value.krxUntransferred.isEmpty() && record.state.value.pending.krxLost.isEmpty())
        }
        override suspend fun closeParts() = actual.close()
    }

    @Test fun o05b_realIngressAndPublicationFenceAreConnected() {
        s63b1Test("O05b", "assembly") { g, _ ->
            g.start(); val old = g.adopt()
            val assembly = g.assembly()
            val stamp = g.need(assembly.krxGraphIngress.capture(old.krxGraphStamp!!.scope), "real assembly ingress issues source")
            val before = assembly.gate.krxGraphRevisions.value
            val result = assembly.krxGraphIngress.topicUnavailable(stamp.scope)
            g.eq("same assembly cutoff installed", KrxGraphTopicResult.INSTALLED, result.result)
            g.yes("same gate observes shared revision", assembly.gate.krxGraphRevisions.value > before)
            g.eq("default-null gate cannot bypass original", null, assembly.gate.bindKrxGraph(old.attribution.owner, old.attribution.lifetime, stamp))
            val target = com.jay.fxi.data.entitlements.purge.PurgeManifest.TARGETS.single { it.id == "memory:graph_recorder" }
            g.eq("memory target supports USER and CAPABILITY only for its held surfaces", setOf(PurgeScope.USER, PurgeScope.CAPABILITY), target.scopes)
        }
        marked("O05b") {
            S6KrxRecorderContractTest().g08_exposureRechecksAfterPreparation()
            S6KrxRecorderContractTest().g26_realAssemblyWiresIngressAndDetaches()
        }
    }

    @Test fun o07_internalApiPreservesOriginalAdmissionAndOffReads() = s63b1Test("O07") { g, _ ->
        g.start()
        val fence = g.need(g.owner, "GENERAL fence"); val lifetime = g.use
        // API note r1: both old trailing-SAM and new explicit two-argument construction must compile unchanged.
        val legacySink = DetachableTopicGraphSink { TopicGraphOffer.DORMANT }
        val ingressSink = DetachableTopicGraphSink(TopicGraphSink { TopicGraphOffer.DORMANT }, KrxGraphIngress.NONE)
        g.eq("legacy trailing SAM constructor remains source-compatible", KrxGraphTopicResult.NO_EFFECT, legacySink.topicUnavailable(null).result)
        g.eq("secondary ingress constructor accepts the neutral port", KrxGraphTopicResult.NO_EFFECT, ingressSink.topicUnavailable(null).result)
        val first = g.gate.bindWithConfiguration(fence, lifetime)
        g.yes("legacy/default read binding positive", first != null)
        val issues = g.session.issued.size
        repeat(3) { g.eq("unchanged read binding equality", first, g.gate.bindWithConfiguration(fence, lifetime)) }
        g.eq("read bind has no source issue", issues, g.session.issued.size)
        val reads = g.gateReads
        val original = g.need(g.gate.captureKrxGraph(fence, lifetime), "actual graph source admission")
        g.eq("source admission reads exactly one snapshot", reads + 1, g.gateReads)
        val stamp = g.need(original.captured.krxGraphStamp, "actual source carries original stamp")
        val second = g.need(g.gate.captureKrxGraph(fence, lifetime)?.captured?.krxGraphStamp, "second logical source")
        g.eq("one new sequence per logical source", stamp.issueSequence + 1, second.issueSequence)
        g.eq("source carries original GENERAL lifetime", lifetime, stamp.scope.use.lifetime)
        val capturedReads = g.gateReads
        g.yes("consumer admits original", g.gate.bindKrxGraph(fence, lifetime, stamp) != null)
        g.eq("consumer uses one fresh snapshot", capturedReads + 1, g.gateReads)
        repeat(3) { g.eq("source issuance leaves read equality stable", first, g.gate.bindWithConfiguration(fence, lifetime)) }
        val next = g.need(g.gate.captureKrxGraph(fence, lifetime)?.captured?.krxGraphStamp, "source after reads")
        g.eq("reads never consume hidden sequence", second.issueSequence + 1, next.issueSequence)
        val legacy = GraphV2AccessGate({ g.r.identity }, { fence }, { g.snapshot }, { true })
        g.yes("standalone default-null gate preserves HEAD server admission", legacy.bind(fence, lifetime) != null)
        g.eq("standalone revision fixed zero", 0L, legacy.krxGraphRevisions.value)
        g.eq("NONE has no source", null, KrxGraphIngress.NONE.capture(stamp.scope))
        g.eq("NONE D1 no effect", KrxGraphTopicResult.NO_EFFECT, KrxGraphIngress.NONE.topicUnavailable(stamp.scope).result)
    }

    @Test fun o08_sourceInventoriesRejectUnapprovedExpansion() {
        val sources = sourceInventory()
        assertTrue("premise: O08 scans every production root", ROOTS.all { File(it).isDirectory } && sources.size > 300)
        assertTrue("contract(O08): exactly the 13 approved paths exist", ALLOWED.all { it in sources })
        val raw = sources.keys.filter { it !in ALLOWED }.sorted()
        assertEquals("contract(O08): protected HEAD file count", 346, raw.size)
        val bytes = raw.joinToString("") { "$it\u0000${sha(File(it).readBytes())}\n" }
        assertEquals("contract(O08): protected HEAD raw bytes", BASELINE, sha(bytes.toByteArray(Charsets.UTF_8)))
        val ingress = setOf("data/graph/KrxGraphFence.kt", "data/remote/TopicSessionCoordinator.kt", "data/remote/TopicRuntime.kt",
            "data/remote/TopicRuntimeOwner.kt", "data/remote/DetachableGraphSinks.kt", "data/graph/GraphRuntimeAssembly.kt").map { BASE + it }.toSet()
        val fence = setOf("data/graph/KrxGraphFence.kt", "data/graph/GraphV2AccessGate.kt", "data/graph/GraphRuntimeAssembly.kt").map { BASE + it }.toSet()
        fun sites(token: String, map: Map<String, String>) = map.filterValues { Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(it) }.keys
        fun exact(map: Map<String, String>) = sites("KrxGraphIngress", map) == ingress && sites("KrxGraphFence", map) == fence
        assertTrue("contract(O08): exact ingress/fence reference file sets", exact(sources))
        for (token in listOf("KrxGraphIngress", "KrxGraphFence")) {
            val bypass = sources + (BASE + "data/graph/Bypass.kt" to "val bypass: $token? = null")
            assertFalse("contract(O08): same validator rejects new $token file inside an allowed package", exact(bypass))
            val approvedFileBypass = sources + (BASE + "data/graph/GraphRecorder.kt" to sources.getValue(BASE + "data/graph/GraphRecorder.kt") + "\nval bypass: $token? = null")
            assertFalse("contract(O08): approved file is not a prefix permission for $token", exact(approvedFileBypass))
        }
        marked("O08") {
            val dormant = KrxS6DormancyContractTest()
            dormant.Z02_adapterHasNoCaller(); dormant.Z09_sessionStillDropsKrxAndClassifiesOnlyTetherAsTetherDelivery(); dormant.Z10_recorderHasNoKrx()
            val wiring = KrxContestWiringContractTest()
            wiring.KW22_theInternalSessionConstructorIsThePublicOnePlusTwo()
            KrxSessionWiringContractTest().z01_alphaProductionIsOff_withoutKrxProducer()
            KrxSessionWiringContractTest().z03_rejectionInventory_isCompleteAndMapped()
            KrxSessionWiringContractTest().z04_authorityAndCheckedOn_areActuallyWired()
            S6KrxOffWiringContractTest().o06_sourceBoundary_isExactAndStillOff()
        }
    }

    companion object {
        internal val OFF_COLUMNS = listOf("GENERAL-WS-REST", "reverse-two-times", "catalog-wait", "continuity-FULL",
            "continuity-FAILED", "continuity-CLOSED", "overflow", "drain", "REST-high", "REST-low",
            "USER-end", "CAP-end", "D1-hold", "server-seed-HTTP", "USD-tether", "assembly-failure", "detach-close")
        internal val ROOTS = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
        internal const val BASE = "src/main/java/com/jay/fxi/"
        internal val ALLOWED = setOf(
            "data/graph/KrxGraphMemoryPurge.kt",
            "data/graph/GraphRecorder.kt",
            "data/graph/GraphRecorderReducer.kt",
            "data/graph/GraphPendingInputs.kt",
            "data/graph/GraphRecorderTopicSink.kt",
            "data/graph/GraphV2RequestCoordinator.kt",
            "data/graph/GraphRuntimeAssembly.kt",
            "data/graph/KrxGraphFence.kt",
            "data/remote/TopicSessionCoordinator.kt",
            "data/remote/TopicGraphLossLedger.kt",
            "ui/premium/graph/GraphV2ScreenStateHolder.kt",
            "data/entitlements/purge/PurgeManifest.kt",
            "data/entitlements/purge/GraphRecorderPurgeAdapter.kt").map { BASE + it }.toSet()
        internal const val BASELINE = "8b2e9746a70ddbb31a7e155130a04f3a08d6f1cada97959376cf2c016664dd9f"
        internal fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun sourceInventory() = ROOTS.flatMap { root -> File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.map { it.invariantSeparatorsPath to KrxSessionSourceContract.strip(it.readText()) }.toList() }.toMap()
    }
}
