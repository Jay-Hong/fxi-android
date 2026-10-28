package com.jay.fxi

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class S6Krx3b2OffContractTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun objectAt(path: String): Pair<ByteArray, JsonObject> {
        try {
            val bytes = javaClass.classLoader!!.getResourceAsStream(path)?.use { it.readBytes() }
                ?: throw AssertionError("contract(O01): missing independently captured clean-1fff resource $path")
            return bytes to (Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw AssertionError("contract(O01): resource is not a JSON object: $path"))
        } catch (failure: Throwable) {
            if (failure is AssertionError) throw failure
            throw AssertionError("contract(O01): invalid frozen resource $path", failure)
        }
    }
    @Test fun o01_realFrozenHeadMatchesCandidateOff() {
        val (bytes, root) = objectAt("krx/s63b2/o03_head_trace.json")
        val (_, provenance) = objectAt("krx/s63b2/o03_head_trace.provenance.json")
        val head = "1fff9b836a1d50e6a3a8b7d51913a1d670af1bad"
        assertEquals("contract(O01): actual HEAD identity", JsonPrimitive(head), root["head"])
        assertEquals("contract(O01): original projection schema", JsonPrimitive(1), root["schema"])
        assertEquals("contract(O01): provenance exact HEAD", JsonPrimitive(head), provenance["head_commit"])
        assertEquals("contract(O01): trace hash bound to execution", JsonPrimitive(sha(bytes)), provenance["output_sha256"])
        assertEquals("contract(O01): immutable after initial capture", JsonPrimitive(true), provenance["immutable_after_initial_capture"])
        assertEquals("contract(O01): clean archive run exited successfully", JsonPrimitive(0), provenance["exit_code"])
        for ((key, path) in mapOf(
            "probe_source_sha256" to "src/test/java/com/jay/fxi/S6Krx3bO03HeadProbe.kt",
            "wrapper_source_sha256" to "src/test/java/com/jay/fxi/S6Krx3b2O03CaptureTest.kt")) {
            assertTrue("premise: O01 source dependency exists: $path", File(path).isFile)
            assertEquals("contract(O01): $key binds identical candidate source", JsonPrimitive(sha(File(path).readBytes())), provenance[key])
        }
        assertTrue("contract(O01): independent execution command recorded", (provenance["command"] as? JsonArray)?.isNotEmpty() == true)
        for (key in listOf("head_tree", "archive_sha256", "junit_xml_sha256", "raw_output_sha256"))
            assertTrue("contract(O01): independent execution evidence $key", (provenance[key] as? JsonPrimitive)?.content?.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")) == true)
        val columns = root["columns"] as? JsonObject ?: throw AssertionError("contract(O01): frozen columns absent")
        assertEquals("contract(O01): exact existing17 columns", S6Krx3bO03HeadProbe.COLUMNS.toSet(), columns.keys)
        for (column in S6Krx3bO03HeadProbe.COLUMNS) {
            try {
                val sameProbe = S6Krx3bO03HeadProbe.trace(column)
                assertEquals("contract(O01/$column): frozen clean HEAD equals candidate same probe", columns[column], sameProbe)
                assertEquals("contract(O01/$column): candidate same probe equals connected OFF", sameProbe,
                    S6Krx3bO03HeadProbe.trace(column, ::connectedOffParts))
            } catch (failure: Throwable) {
                throw AssertionError("contract(O01/$column): triple comparison failed: ${failure.message}", failure)
            }
        }
        S6KrxOffWiringContractTest().o01_originalDormantGolden_isUnchanged()
        S6Krx3bOffWiringContractTest().o03_offGraphRecorderTraceMatchesHead()
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
            g.eq("OFF never issued a capture", false, (field(actual, "krxFence") as KrxGraphFence).hasIssuedCaptures)
            g.eq("OFF Main cleanup report stays absent", null, actual.krxMemoryPurgeState)
            val tsc = field(runtime, "session") as TopicSessionCoordinator
            g.eq("OFF TSC cleanup report stays absent", null, tsc.krxGraphLossPurgeState)
            for (owner in listOf(record, (field(tsc, "graphLossLedger") ?: throw AssertionError("premise: O01 SESSION loss owner exists")))) {
                val seal = field(owner, "purgeSeal") as KrxGraphPurgeSeal
                g.eq("OFF seal retains no proofs", emptyList<KrxGraphPurgePlan>(), field(seal, "proofs"))
            }
            g.yes("OFF has no original KRX sidecars", record.state.value.krxSeriesOwners.isEmpty() &&
                record.state.value.krxUntransferred.isEmpty() && record.state.value.pending.krxLost.isEmpty())
        }
        override suspend fun closeParts() = actual.close()
    }

    @Suppress("UNUSED_VARIABLE")
    private fun exactApiSignatures() {
        val snapshot: (TopicAccessSnapshot, KrxGraphCutoffSnapshot) -> KrxGraphPurgePlan = KrxGraphPurgePlan.Companion::forSnapshot
        val capability: (PurgeNamespace, TopicAccessSnapshot) -> KrxGraphPurgePlan? = KrxGraphPurgePlan.Companion::forCapability
        val cutoff: (KrxGraphTopicCutoff) -> KrxGraphPurgePlan? = KrxGraphPurgePlan.Companion::forCutoff
        val classify: (KrxGraphPurgePlan, KrxGraphStamp?, String?) -> KrxGraphPurgeDecision = KrxGraphPurgePlan::classify
        val merge: (KrxGraphPurgeSeal, KrxGraphPurgePlan) -> KrxGraphPurgeSeal = KrxGraphPurgeSeal::merge
        val sealClassify: (KrxGraphPurgeSeal, KrxGraphStamp?, String?) -> KrxGraphPurgeDecision = KrxGraphPurgeSeal::classify
        val projection: (TopicGraphInput, KrxGraphPurgePlan) -> KrxGraphInputProjection = ::projectKrxGraphInput
        val late: (TopicGraphInput, KrxGraphPurgeSeal) -> KrxGraphInputProjection = ::projectKrxGraphInput
        val recorder: (GraphRecorder, KrxGraphPurgePlan) -> KrxGraphPurgeOutcome = GraphRecorder::purgeKrxMemory
        val preflight: (GraphRecorder, PurgeNamespace) -> KrxGraphPurgePlan? = GraphRecorder::captureKrxMemoryPurgePlan
        val reducer: (GraphRecorderState, KrxGraphPurgePlan) -> Pair<GraphRecorderState, KrxGraphPurgeOutcome> = GraphRecorderReducer::purgeKrxMemory
        val pending: (GraphPendingInputs, KrxGraphPurgePlan) -> Pair<GraphPendingInputs, KrxGraphPurgeOutcome> = GraphPendingInputs::purgeKrxMemory
        val sink: (GraphRecorderTopicSink, KrxGraphPurgePlan) -> KrxGraphPurgeOutcome = GraphRecorderTopicSink::purgeKrxMemory
        val ledger: (TopicGraphLossLedger, KrxGraphPurgePlan) -> KrxGraphPurgeOutcome = TopicGraphLossLedger::purgeKrxMemory
        val runtime: (GraphV2RequestCoordinator, KrxGraphPurgePlan) -> KrxGraphPurgeOutcome = GraphV2RequestCoordinator::purgeKrxMemory
        val recovery: (GraphV2RequestCoordinator, KrxGraphPurgePlan) -> KrxGraphPurgeOutcome = GraphV2RequestCoordinator::purgeKrxRecoveryCaptures
        val now: (GraphRuntimeAssembly) -> KrxGraphPurgeOutcome = GraphRuntimeAssembly::purgeKrxMemoryNow
        val mainReport: (GraphRuntimeAssembly) -> KrxGraphPurgeState? = { it.krxMemoryPurgeState }
        val sessionReport: (TopicSessionCoordinator) -> KrxGraphPurgeState? = { it.krxGraphLossPurgeState }
        val cutoffs: (KrxGraphFence) -> KrxGraphCutoffSnapshot = KrxGraphFence::memoryCutoffs
        val issued: (KrxGraphFence) -> Boolean = { it.hasIssuedCaptures }
    }

    @Test fun o02_exactApisWiringAndSourceBoundariesHold() {
        exactApiSignatures()
        fun checkBoundary(body: () -> Unit) {
            try { body() } catch (failure: Throwable) {
                throw AssertionError("contract(O02): exact source/signature boundary failed: ${failure.message}", failure)
            }
        }
        checkBoundary {
            KrxS6DormancyContractTest().Z10_recorderHasNoKrx()
            S6Krx3bOffWiringContractTest().o08_sourceInventoriesRejectUnapprovedExpansion()
            S6Krx3bOffWiringContractTest().o07_internalApiPreservesOriginalAdmissionAndOffReads()
        }
        val base = "src/main/java/com/jay/fxi/"
        val code = S6Krx3bOffWiringContractTest.sourceInventory()
        fun source(path: String) = code[base + path] ?: throw AssertionError("contract(O02): missing API source $path")
        val memory = source("data/graph/KrxGraphMemoryPurge.kt")
        for (name in listOf("KrxGraphPurgePlan", "KrxGraphPurgeDecision", "KrxGraphPurgeOutcome", "KrxGraphPurgeSeal",
            "KrxGraphCutoffSnapshot", "KrxGraphInputProjection", "KrxGraphPurgeState"))
            assertTrue("contract(O02): exact shared type $name", Regex("\\b(?:class|enum class|data class)\\s+$name\\b").containsMatchIn(memory))
        for (name in listOf("forSnapshot", "forCapability", "forCutoff", "classify", "merge", "projectKrxGraphInput"))
            assertTrue("contract(O02): exact shared API $name", Regex("\\bfun\\s+$name\\s*\\(").containsMatchIn(memory))
        assertTrue("contract(O02): plan constructor stays private", memory.contains("KrxGraphPurgePlan private constructor"))
        val assembly = source("data/graph/GraphRuntimeAssembly.kt")
        assertTrue("contract(O02): Main cleanup is guarded by actual issuance", assembly.contains("hasIssuedCaptures"))
        assertTrue("contract(O02): Main serial cleanup invokes recorder", assembly.contains("recorder.purgeKrxMemory"))
        assertTrue("contract(O02): Main serial cleanup invokes sink", assembly.contains("sink.purgeKrxMemory"))
        assertTrue("contract(O02): Main serial cleanup invokes runtime", assembly.contains("coordinator.purgeKrxMemory"))
        val tsc = source("data/remote/TopicSessionCoordinator.kt")
        assertTrue("contract(O02): TSC owns its local loss cleanup", tsc.contains("purgeKrxGraphLossOnLoop"))
        val holder = source("ui/premium/graph/GraphV2ScreenStateHolder.kt")
        assertTrue("contract(O02): private epoch cache invalidation retained", holder.contains("preparedKrxCapabilityEpoch"))
        assertFalse("contract(O02): no new StateFlow for cleanup result", Regex("krxMemoryPurgeState\\s*:\\s*StateFlow").containsMatchIn(assembly))
        assertEquals("contract(O02): manifest both axes", setOf(PurgeScope.USER, PurgeScope.CAPABILITY),
            com.jay.fxi.data.entitlements.purge.PurgeManifest.byId("memory:graph_recorder")?.scopes)
        s63b2Test("O02", "immutable-proof") { p, _ ->
            val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!
            val cutoff = g.session.ingress.topicUnavailable(original.scope)
            val namespace = p.need(cutoff.namespace, "actual immutable namespace proof")
            val source = mutableMapOf(namespace to cutoff.throughIssueSequence)
            val snapshot = KrxGraphCutoffSnapshot(cutoff.revision, source)
            val plan = KrxGraphPurgePlan.forSnapshot(g.snapshot, snapshot)
            source.clear()
            p.eq("cutoff snapshot defensively owns its original map", mapOf(namespace to cutoff.throughIssueSequence), snapshot.cutoffs)
            p.eq("frozen plan selects old source after caller mutates map", KrxGraphPurgeDecision.REMOVE,
                plan.classify(original, original.scope.owner.identity.uid))
            val empty = KrxGraphPurgeSeal.EMPTY
            val merged = empty.merge(plan); val duplicate = merged.merge(plan)
            p.eq("old EMPTY seal remains independent", KrxGraphPurgeDecision.KEEP, empty.classify(original, original.scope.owner.identity.uid))
            p.eq("new seal keeps frozen proof", KrxGraphPurgeDecision.REMOVE, merged.classify(original, original.scope.owner.identity.uid))
            p.eq("duplicate proof changes no behavior", KrxGraphPurgeDecision.REMOVE, duplicate.classify(original, original.scope.owner.identity.uid))
            p.eq("same immutable proof is stored once", 1, (field(duplicate, "proofs") as Collection<*>).size)
            p.eq("EMPTY owns no proof", 0, (field(empty, "proofs") as Collection<*>).size)
            val future = p.need(g.session.fence.capture(original.scope), "actual future source after frozen proof")
            p.eq("old seal cannot capture future sequence", KrxGraphPurgeDecision.KEEP, merged.classify(future, future.scope.owner.identity.uid))
        }
    }
}
