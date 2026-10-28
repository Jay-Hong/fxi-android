package com.jay.fxi

import com.jay.fxi.data.entitlements.PremiumAccessKrxDormantTraceTest
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxOffWiringContractTest {
    /** Each original test gets its own instance/job; original GOLDEN lists are neither read nor regenerated here. */
    @Test fun o01_originalDormantGolden_isUnchanged() {
        val originals: List<Pair<String, (PremiumAccessKrxDormantTraceTest) -> Unit>> = listOf(
            "D1" to { it.d1_rejectionsAndAnswersAlternating() },
            "D2" to { it.d2_undecidedRejections() },
            "D3" to { it.d3_rotationFailureSealAndRelease() },
            "D4" to { it.d4_intraStepChangeIsCaptured() },
            "D5" to { it.d5_queryCandidateDecisionAndStaleRelease() },
            "D6" to { it.d6_zeroDelayRecheckFiresDuringCandidateRelease() },
            "K17" to { it.k17_laterFalseAfterSuppressedTrue() },
            "K18" to { it.k18_completionInversion() },
            "D7" to { it.d7_markedRefusalsDuplicateAndMarkerless_offIgnoresMarkers() },
            "D8" to { it.d8_earlierPermitAndOldEpochMarkers_offIgnoresMarkers() }
        )
        assertEquals("premise: O01 all ten original frozen traces are named", 10, originals.size)
        for ((name, run) in originals) try { run(PremiumAccessKrxDormantTraceTest()) }
        catch (failed: AssertionError) { throw AssertionError("contract(O01): original GOLDEN $name changed", failed) }
    }

    @Test fun o02_offSessionAndBootstrapTrace_matchesHead() {
        val original = TopicSessionKrxOffTraceTest()
        original.o2_sessionTrace_matchesHead(); original.o2_bootstrapTrace_matchesHead()
        original.o2_f2AndD14Trace_matchesHead(); original.o2_authRecoveryTrace_matchesHead()
        original.o2_mixedFrameTrace_matchesHead(); original.o2_failedSendTrace_matchesHead()
        original.o2_typedBoundaryIsUnusedOff(); original.o2_metadataDoesNotChangeOffRevisions()
        // New SESSION and snapshot supplier are additionally present on the candidate side of the
        // same executable frozen HEAD comparison. Every side owns a separate scheduler and OFF PAC.
        for (column in listOf("session", "bootstrap-floor", "f2-tether")) {
            fun capture(head: Boolean): List<String> {
                var trace = emptyList<String>()
                runTest {
                    val s = if (head) null else S6KrxCleanupRig(this, "O02/$column", on = false, installRestFixture = false)
                    val r = s?.r ?: KrxSessionIntegrationRig(this, "O02/$column", on = false, frozenHead = true)
                    try {
                        assertEquals("contract(O02): both sides use the original integration bootstrap fake", null, r.bootstrapOverride)
                        if (column == "bootstrap-floor") r.httpFloor = 3_600_600
                        r.approve(); r.start(); r.open(); r.first(); r.ack()
                        assertEquals("premise: O02 real OFF has no permit", 0L, r.snapshot.krxPermitSeq)
                        when (column) {
                            "session" -> {
                                r.generalTick(); r.krxTick(); r.revise(); r.drain(); r.reconnect(); r.ack()
                                r.wire.deliver(TopicSessionKrxHarness.usdFrame(1331.0)); r.drain()
                            }
                            "bootstrap-floor" -> {
                                assertTrue("premise: O02 remains below original floor", r.clock.nowMillis() < r.httpFloor)
                                r.advance(r.httpFloor - r.clock.nowMillis()); r.advance(2_100)
                                assertEquals("contract(O02): all six legacy topics reach the same bootstrap fake", r.desired.size, r.calls.size)
                                for (call in r.calls.toList()) r.complete(call, TopicSnapshotOutcome.Unreachable(java.io.IOException("OFF fixture")))
                                r.advance(2_000)
                            }
                            else -> { r.krxTick(); r.generalTick(); r.advance(30_001) }
                        }
                        if (s != null) {
                            assertEquals("contract(O02): OFF new memory snapshot reads zero", 0, s.cleanupReads)
                            assertEquals("contract(O02): OFF retirement view empty", emptyMap<Any, Any>(), s.records)
                            assertEquals("contract(O02): OFF cleanup log empty", emptyList<Any>(), s.cleanup)
                            assertEquals("contract(O02): OFF unsubscribe wire zero", 0, s.unsubscribes.size)
                        }
                        trace = r.trace()
                    } finally { r.close() }
                }
                return trace
            }
            assertEquals("contract(O02): $column SESSION-installed OFF equals executable HEAD", capture(true), capture(false))
        }
    }

    @Test fun o05_runtimeSelectsRealSessionRetirerAndSnapshot() {
        runTest {
            val s = S6KrxCleanupRig(this, "O05/runtime")
            try {
                s.r.approve()
                var lastRead: com.jay.fxi.data.entitlements.TopicAccessSnapshot? = null
                s.memorySnapshot = { s.r.pac.accessSnapshot.also { lastRead = it } }
                val runtime = s.runtime()
                s.eq("actual RT.create selects SESSION", KrxSubscriptionRetirer.SESSION, field(s.session, "krxRetirer"))
                runtime.start(); runtime.setOnline(true)
                s.r.until(s.c("runtime connects")) { s.r.wires.isNotEmpty() }
                s.r.open(); s.r.first(); s.r.ack()
                s.yes("runtime accepted real KRX subscription", KRX in s.registry.held)
                // Runtime owns its own store; observe that actual registry and active capture directly.
                val tuple = s.tuple()
                s.hold()
                s.eq("RT SESSION performs actual wire effect", 1, s.unsubscribes.size)
                s.eq("RT SESSION performs actual registry effect", emptyMap<Any, Any>(), s.registry.held)
                s.eq("real SESSION result", KrxRetirementWire.ACCEPTED_UNCONFIRMED, s.record(tuple).wire)
                s.yes("raw memory snapshot supplier was invoked", s.cleanupReads > 0)
                assertSame(s.c("supplier is exactly this PAC publication"), s.r.pac.accessSnapshot, lastRead)
                s.eq("custom recording sink did not impersonate cleanup", 0, s.r.retirements.requests.size)
                runtime.stop()
            } finally { try { s.r.beta!!.check.assertHeld() } finally { s.close() } }
        }
        // NONE/custom are genuine injected alternatives; no implicit SESSION work may run for them.
        for (custom in listOf(false, true)) runTest {
            val s = S6KrxCleanupRig(this, "O05/${if (custom) "custom" else "NONE"}")
            try {
                setField(s.session, "krxRetirer", if (custom) s.r.retirements else KrxSubscriptionRetirer.NONE)
                s.live(); val tuple = s.tuple(); s.hold()
                s.eq("NONE/custom retains callback semantics", if (custom) 1 else 0, s.r.retirements.requests.size)
                s.eq("NONE/custom sends no real cleanup", 0, s.unsubscribes.size)
                s.yes("NONE/custom map records the tuple", tuple in s.records)
                s.eq("NONE/custom has nullable result", null, s.records[tuple])
            } finally { try { s.r.beta!!.check.assertHeld() } finally { s.close() } }
        }
        var directFailed = false
        try { KrxSubscriptionRetirer.SESSION.retire(KrxSubscriptionRetirement(1, "direct-misuse", null)) }
        catch (_: IllegalStateException) { directFailed = true }
        assertTrue("contract(O05): direct SESSION marker invocation fails fast", directFailed)
    }

    private val roots = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
    private val base = "src/main/java/com/jay/fxi/"
    private val allowed = setOf("${base}data/remote/TopicSessionCoordinator.kt", "${base}data/remote/TopicRuntime.kt", "${base}data/entitlements/purge/KrxMemoryCleanup.kt")
    private fun sources(): Map<String, String> = roots.flatMap { root -> File(root).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.map { it.invariantSeparatorsPath to KrxSessionSourceContract.strip(it.readText()) }.toList() }.toMap()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun o06_sourceBoundary_isExactAndStillOff() {
        val code = sources()
        assertTrue("premise: O06 all production roots are scanned", roots.all { File(it).isDirectory } && code.size > 100)
        assertTrue("contract(O06): exactly three allowed production files present", allowed.all { it in code })
        val tsc = code.getValue("${base}data/remote/TopicSessionCoordinator.kt")
        val rt = code.getValue("${base}data/remote/TopicRuntime.kt")
        val unsubscribe = Regex("""TopicSubscribeRequest\s*\.\s*unsubscribe\s*\(""")
        fun sites(map: Map<String, String>) = map.mapValues { unsubscribe.findAll(it.value).count() }.filterValues { it > 0 }
        assertEquals("contract(O06): one unsubscribe factory in the private session dispatcher", mapOf("${base}data/remote/TopicSessionCoordinator.kt" to 1), sites(code))
        assertTrue("contract(O06): private real cleanup exists", Regex("""private\s+fun\s+completeKrxRetirementOnLoop\s*\(""").containsMatchIn(tsc))
        assertEquals("contract(O06): private cleanup owns the only factory", 1,
            unsubscribe.findAll(KrxSessionSourceContract.function(tsc, "completeKrxRetirementOnLoop")).count())
        val bypass = code + ("${base}data/remote/Bypass.kt" to "fun bad() { TopicSubscribeRequest\n .unsubscribe(id, topics) }")
        assertEquals("contract(O06): same scanner detects a new bypass caller", 2, sites(bypass).values.sum())
        val selected = code.filterValues { Regex("""krxRetirer\s*=\s*KrxSubscriptionRetirer\.SESSION\b""").containsMatchIn(it) }.keys
        assertEquals("contract(O06): only RT selects SESSION", setOf("${base}data/remote/TopicRuntime.kt"), selected)
        assertEquals("contract(O06): no new runtime lateinit", 1, Regex("""\blateinit\b""").findAll(rt).count())
        assertFalse("contract(O06): no runtime session backlink", Regex("""lateinit\s+var\s+session\b""").containsMatchIn(rt))
        assertTrue("contract(O06): actual injected supplier is PAC read-only snapshot", Regex("""krxMemorySnapshot\s*=\s*\{\s*coordinator\.accessSnapshot\s*\}""").containsMatchIn(rt))
        // Lock raw source bytes outside the approved production set, including PAC/SUA/DI/disk.
        val inventory = code.keys.filter { it !in allowed }.sorted().joinToString("") { path -> "$path\u0000${sha(File(path).readBytes())}\n" }
        assertEquals("contract(O06): all other production Kotlin bytes remain HEAD", "5ef05c6a3d5e1eef388e3ceac8a78d6239bb3b8646457e6f992fca547e042172", sha(inventory.toByteArray(Charsets.UTF_8)))
        val pac = code.getValue("${base}data/entitlements/PremiumAccessCoordinator.kt")
        assertTrue("contract(O06): PAC default OFF", Regex("""krxContest\s*:\s*KrxContestSwitch\s*=\s*KrxContestSwitch\.OFF""").containsMatchIn(pac))
        assertEquals("contract(O06): production desired contains GENERAL only", setOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot"), TopicCatalogue.DESIRED)
        KrxS6DormancyContractTest().Z07_desiredHasNoKrx()
        KrxS6DormancyContractTest().Z09_sessionStillDropsKrxAndClassifiesOnlyTetherAsTetherDelivery()
        KrxS6DormancyContractTest().Z10_recorderHasNoKrx()
    }
}
