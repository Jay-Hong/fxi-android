package com.jay.fxi.s5fixture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D17-bridge v1 fixed sample protocol (generated only when Claude runs this test):
 * app/build/s5purity-bridge-r1/<sample>/device/{attempt.json,registry.jsonl,instance-<id>.jsonl}
 * app/build/s5purity-bridge-r1/<sample>/{summary.bin,exit-query.stdout.json,expected.json}
 * app/build/s5purity-bridge-r1/samples.json is the exact ordered sample-name list.
 * Names: zero,nonzero-1,nonzero-70,busy,poison,b1,prepare-only,crossed-pairs,
 * missing-context,invalid-context,max-boundary,admit-preapply-kill,admit-postapply-kill,fatal88-idle.
 * missing/invalid-context OMIT device/attempt.json; all attribution JSON fields are explicit null.
 * All JSON files end with ONE LF; summary.bin is exactly 64 raw bytes (no LF).
 * expected.json keys: schema,sample,localState,check,counter,producer. It is a test oracle,
 * NOT a device file or a purity proof. Python must assemble its own observations/commands/proof.
 * localState is a C1/C2/C4/C5/C6/C7 expectation assuming other evidence is independently valid.
 * max-boundary deliberately has p(MAX),c(MAX) after begin: a C5 negative plus codec MAX positive;
 * it is NOT a complete MAX-count proof, which would require all MAX pairs.
 * b1 is begin-only BUSY, allowed reason=10/status=0; no invented file error row hides the defect.
 * preapply-kill is the explicit allowed IDLE(0) boundary; fatal88-idle differs only by reason/status.
 * Encoders and getter serialization are actual Kotlin code. This is NOT an Android Binder assertion.
 * This L1 task never runs this producer and does not hand-write generated output in app/build.
 */
class S5PurityBridgeContractTest {
    private val root = File("build/s5purity-bridge-r1")
    private val names = listOf("zero", "nonzero-1", "nonzero-70", "busy", "poison", "b1", "prepare-only", "crossed-pairs",
        "missing-context", "invalid-context", "max-boundary", "admit-preapply-kill", "admit-postapply-kill", "fatal88-idle")

    private fun message(id: String, duty: String) = "contract(D17a):bridge-$id/$duty"
    private fun writeExact(file: File, bytes: ByteArray) {
        if (file.exists()) {
            assertTrue(message("samples", "existing-output-regular-file"), java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertArrayEquals(message("samples", "existing-output-must-match"), bytes, file.readBytes())
            return
        }
        assertTrue(message("samples", "exclusive-new-output"), file.createNewFile())
        file.writeBytes(bytes)
    }
    private fun jsonFile(file: File, value: String) = writeExact(file, (value + "\n").encodeToByteArray())

    @Test fun emitKotlinCodecSamplesForPython() {
        assertTrue(message("samples", "create-output-root"), root.isDirectory || root.mkdirs())
        // Refuse unknown/stale entries; never silently delete earlier evidence or unrelated build output.
        assertTrue(message("samples", "no-stale-sample-set"), root.listFiles().orEmpty().all { it.name in names || it.name == "samples.json" })
        for (name in names) emit(name)
        jsonFile(File(root, "samples.json"), buildJsonObject {
            put("schema", 1); put("samples", JsonArray(names.map(::JsonPrimitive)))
        }.toString())
    }

    private fun emit(name: String) {
        val sample = File(root, name)
        val device = File(sample, "device")
        assertTrue(message(name, "create-device-folder"), device.isDirectory || device.mkdirs())
        val context = when (name) {
            "missing-context" -> AttemptContext(AttemptState.MISSING, null, null, null)
            "invalid-context" -> AttemptContext(AttemptState.INVALID, null, null, null)
            else -> D17Inputs.context
        }
        val identity = D17Inputs.identity
        val expectedNames = setOf("registry.jsonl", "instance-${identity.instanceId}.jsonl") + if (context.attemptState == AttemptState.OK) setOf("attempt.json") else emptySet()
        assertTrue(message(name, "no-stale-device-file"), device.listFiles().orEmpty().all { it.name in expectedNames })
        if (context.attemptState == AttemptState.OK) writeExact(File(device, "attempt.json"), D17Inputs.contextBytes)
        val io = RecordingJournalIO(java.util.concurrent.CopyOnWriteArrayList())
        io.appendAndSync("registry.jsonl", JournalLineCodec.registration("register", identity, context))
        val instanceFile = "instance-${identity.instanceId}.jsonl"
        io.createExclusiveAndSync(instanceFile, JournalLineCodec.registration("begin", identity, context))
        fun prepare(seq: Int) = JournalLineCodec.transaction("prepare", identity, context, seq, "GET /bridge/$seq ")
        fun commit(seq: Int) = JournalLineCodec.transaction("commit", identity, context, seq)
        val count = when (name) { "nonzero-1" -> 1; "nonzero-70" -> 70; "max-boundary" -> Int.MAX_VALUE; else -> 0 }
        when (name) {
            "nonzero-1", "nonzero-70" -> repeat(count) { n ->
                io.appendAndSync(instanceFile, prepare(n + 1)); io.appendAndSync(instanceFile, commit(n + 1))
            }
            "prepare-only" -> io.appendAndSync(instanceFile, prepare(1))
            "crossed-pairs" -> {
                io.appendAndSync(instanceFile, prepare(1)); io.appendAndSync(instanceFile, prepare(2))
                io.appendAndSync(instanceFile, commit(1)); io.appendAndSync(instanceFile, commit(2))
            }
            "max-boundary" -> { io.appendAndSync(instanceFile, prepare(Int.MAX_VALUE)); io.appendAndSync(instanceFile, commit(Int.MAX_VALUE)) }
        }
        for ((file, bytes) in io.snapshot()) writeExact(File(device, file), bytes)
        val state = when (name) {
            "busy", "b1", "prepare-only", "admit-postapply-kill" -> SummaryState.BUSY
            "poison" -> SummaryState.POISON
            else -> SummaryState.IDLE
        }
        val summary = Summary64Codec.encode(D17Inputs.summary(state, count, context))
        writeExact(File(sample, "summary.bin"), summary)
        // The OS getter adapter receives the original byte array, not registry-derived hex or identity.
        val exit = ProbeExitRecord(identity.pid, identity.processName, identity.packageUid, identity.packageUid,
            identity.packageUid, identity.processStartWallMs + 1000, if (name == "fatal88-idle") 1 else 10,
            if (name == "fatal88-idle") 88 else 0, summary)
        val stdout = buildJsonObject {
            put("schema", 2); put("op", "exits"); put("attemptId", "attempt-001"); put("runnerNonce", D17Inputs.NONCE)
            put("commandNonce", "bridge-command-001"); put("collectorUid", 2000); put("userId", 0)
            put("payload", buildJsonObject { put("records", JsonArray(listOf(ProbeExitCodec.encode(exit)))) })
        }
        jsonFile(File(sample, "exit-query.stdout.json"), stdout.toString())
        val check = when (name) {
            "missing-context", "invalid-context" -> "C1"
            "prepare-only", "crossed-pairs", "max-boundary" -> "C5"
            "busy", "poison", "b1", "admit-postapply-kill" -> "C6"
            "fatal88-idle" -> "C7"
            else -> "none"
        }
        val expected = when { check != "none" -> "incomplete"; count > 0 -> "nonzero"; else -> "zero" }
        jsonFile(File(sample, "expected.json"), buildJsonObject {
            put("schema", 1); put("sample", name); put("localState", expected); put("check", check)
            put("counter", count); put("producer", "Kotlin-JournalLineCodec+Summary64Codec+ProbeExitCodec")
        }.toString())
        assertEquals(message(name, "fixed-summary-size"), 64, summary.size)
        assertEquals(message(name, "big-endian-counter"), count, ByteBuffer.wrap(summary, 60, 4).order(ByteOrder.BIG_ENDIAN).int)
        val hex = ProbeExitCodec.encode(exit).getValue("summaryHex").jsonPrimitive.content
        assertArrayEquals(message(name, "lossless-getter-bytes"), summary, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        assertEquals(message(name, "exact-device-files"), expectedNames, device.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun runtimeJournalMustMatchCodecWireFormat() {
        val id = "runtime-journal"
        try {
            D17Rig().use { r ->
                r.register(); r.journal.classifiedCallback { r.journal.recordUnexpected("GET /bridge/1 ") }
                assertArrayEquals(message(id, "runtime-register-equals-codec"), JournalLineCodec.registration("register", D17Inputs.identity, r.context), r.io.snapshot().getValue("registry.jsonl"))
                val expected = JournalLineCodec.registration("begin", D17Inputs.identity, r.context) +
                    JournalLineCodec.transaction("prepare", D17Inputs.identity, r.context, 1, "GET /bridge/1 ") +
                    JournalLineCodec.transaction("commit", D17Inputs.identity, r.context, 1)
                assertArrayEquals(message(id, "runtime-journal-equals-codec"), expected, r.io.snapshot().getValue("instance-${D17Inputs.INSTANCE}.jsonl"))
                assertArrayEquals(message(id, "runtime-summary-equals-codec"), Summary64Codec.encode(D17Inputs.summary(SummaryState.IDLE, 1)), r.summary.current)
            }
        } catch (stub: NotImplementedError) { throw AssertionError("precondition(D17a/bridge-$id): L1-stub-prerequisite", stub) }
    }

    @Test fun summaryModelMustRetainSuccessfulBusy() {
        val sink = RecordingSummary(java.util.concurrent.CopyOnWriteArrayList())
        sink.replace(Summary64Codec.encode(D17Inputs.summary(SummaryState.IDLE)))
        sink.replace(Summary64Codec.encode(D17Inputs.summary(SummaryState.BUSY)))
        assertEquals("contract(D17a):model-summary-success/normal-return-preserves-busy",
            SummaryState.BUSY, sink.decoded()?.state)
    }

    @Test fun summaryBytesAndGetterRejectOrPreserveExactValues() {
        for (state in SummaryState.entries) for (count in listOf(0, 1, 70, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val value = D17Inputs.summary(state, count)
            assertEquals(message("codec-${state.name.lowercase()}-$count", "round-trip"), value, Summary64Codec.decode(Summary64Codec.encode(value)))
        }
        val valid = Summary64Codec.encode(D17Inputs.summary(SummaryState.IDLE))
        val defects = linkedMapOf(
            "magic" to valid.copyOf().apply { this[0] = 0 },
            "version" to valid.copyOf().apply { this[8] = 2 },
            "reserved" to valid.copyOf().apply { this[10] = 1 },
            "state" to valid.copyOf().apply { this[9] = 3 },
            "short" to valid.copyOf(63), "long" to valid.copyOf(65),
            "unsigned-overflow" to valid.copyOf().apply { this[60] = 128.toByte() },
            "zero-lifetime" to valid.copyOf().apply { fill(0, 28, 44) },
            "zero-instance" to valid.copyOf().apply { fill(0, 44, 60) }
        )
        for ((id, bytes) in defects) {
            var rejected = false
            try { Summary64Codec.decode(bytes) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(message("codec-$id", "strict-decode"), rejected)
        }
        val nullRecord = ProbeExitRecord(1, "com.jay.fxi", 10123, 10123, 10123, 1, 10, 0, null)
        assertEquals(message("getter-null", "null-not-empty"), JsonNull, ProbeExitCodec.encode(nullRecord)["summaryHex"])
        // An unrelated identity must survive transport unchanged: collector may not replace it from the registry.
        val alien = valid.copyOf().apply { this[28] = 99 }
        val alienRecord = nullRecord.copy(summary = alien)
        assertEquals(message("getter-alien", "no-registry-identity-synthesis"), alien.losslessHex(), ProbeExitCodec.encode(alienRecord)["summaryHex"]!!.jsonPrimitive.content)
    }
}
