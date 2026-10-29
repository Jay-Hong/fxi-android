package com.jay.fxi

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.di.EntitlementsModule
import com.jay.fxi.service.*
import java.io.File
import javax.inject.Provider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class S6Krx3cOffContractTest {
    private fun marked(row: String, body: () -> Unit) {
        try { body() } catch (failure: Throwable) {
            if (failure is AssertionError && failure.message.orEmpty().startsWith("contract(")) throw failure
            throw AssertionError("contract($row): ${failure.message}", failure)
        }
    }
    private fun bytes(path: String, row: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("krx/s63c/$path")?.use { it.readBytes() }
        ?: throw AssertionError("contract($row/resource): missing immutable clean0302 resource $path")
    private fun json(data: ByteArray, row: String): JsonObject = try { Json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject }
        catch (failure: Throwable) { throw AssertionError("contract($row/schema): malformed immutable resource", failure) }
    private fun provenance(path: String, output: ByteArray, row: String, probe: String, wrapper: String? = null): JsonObject {
        val p = json(bytes(path, row), row)
        assertEquals("contract($row/provenance): exact HEAD", S6PendingTap0302HeadProbe.HEAD, p["head_commit"]?.jsonPrimitive?.content)
        assertEquals("contract($row/provenance): exact0302 tree", S6Krx3cShellSourceContract.HEAD_TREE, p["tree"]?.jsonPrimitive?.content)
        assertEquals("contract($row/provenance): output SHA", S6Krx3cShellSourceContract.sha(output), p["output_sha256"]?.jsonPrimitive?.content)
        for (key in listOf("tree", "archive_sha256", "probe_source_sha256", "xml_sha256", "raw_output_sha256", "normalizer_sha256"))
            assertTrue("contract($row/provenance): $key recorded", p[key]?.jsonPrimitive?.content?.matches(Regex(if (key == "tree") "[0-9a-f]{40}" else "[0-9a-f]{64}")) == true)
        for (key in listOf("cwd", "jdk", "gradle", "sdk", "variant"))
            assertTrue("contract($row/provenance): $key recorded", p[key]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertEquals("contract($row/provenance): successful HEAD execution", 0, p["rc"]?.jsonPrimitive?.int)
        assertTrue("contract($row/provenance): execution command recorded", p["command"]?.jsonArray?.isNotEmpty() == true)
        assertEquals("contract($row/provenance): same immutable probe source", S6Krx3cShellSourceContract.sha(File(probe).readBytes()), p["probe_source_sha256"]?.jsonPrimitive?.content)
        if (wrapper != null) assertEquals("contract($row/provenance): same immutable wrapper source", S6Krx3cShellSourceContract.sha(File(wrapper).readBytes()), p["wrapper_source_sha256"]?.jsonPrimitive?.content)
        return p
    }

    @Test fun o04g_clean0302GraphTraceEqualsConnectedOff() = marked("O04g") {
        val output = bytes("o04_graph_head_trace.json", "O04g")
        provenance("o04_graph_head_trace.provenance.json", output, "O04g", "src/test/java/com/jay/fxi/S6Krx3bO03HeadProbe.kt", "src/test/java/com/jay/fxi/S6Krx3cO04CaptureTest.kt")
        val frozen = json(output, "O04g")
        assertEquals("contract(O04g/schema): schema", JsonPrimitive(1), frozen["schema"])
        assertEquals("contract(O04g/head): HEAD", JsonPrimitive(S6PendingTap0302HeadProbe.HEAD), frozen["head"])
        val columns = frozen.getValue("columns").jsonObject
        assertEquals("contract(O04g/columns): exact17 graph columns", S6Krx3bO03HeadProbe.COLUMNS.toSet(), columns.keys)
        for (column in S6Krx3bO03HeadProbe.COLUMNS) {
            val same = S6Krx3bO03HeadProbe.trace(column)
            assertEquals("contract(O04g/$column): frozen actual HEAD equals candidate same probe", columns[column], same)
            assertEquals("contract(O04g/$column): connected candidate OFF equals HEAD", same, S6Krx3bO03HeadProbe.trace(column, ::connectedOffParts))
        }
    }

    @Test fun o04t_clean0302JvmTapTraceEqualsConnectedOff() = marked("O04t") {
        val output = bytes("o04_tap_head_trace.json", "O04t")
        val frozen = json(output, "O04t")
        assertEquals("contract(O04t/schema): schema", JsonPrimitive(1), frozen["schema"])
        assertEquals("contract(O04t/head): HEAD", JsonPrimitive(S6PendingTap0302HeadProbe.HEAD), frozen["head"])
        val profiles = frozen.getValue("profiles").jsonObject
        assertEquals("contract(O04t/profiles): both real D24 profiles mandatory", setOf("closed", "admitted"), profiles.keys)
        val prov = json(bytes("o04_tap_head_trace.provenance.json", "O04t"), "O04t")
        assertEquals("contract(O04t/provenance): bundle SHA", S6Krx3cShellSourceContract.sha(output), prov["output_sha256"]?.jsonPrimitive?.content)
        for (profile in listOf("closed", "admitted")) {
            val raw = bytes("o04_tap_${profile}.json", "O04t")
            val p = provenance("o04_tap_${profile}.provenance.json", raw, "O04t/$profile", "src/test/java/com/jay/fxi/service/S6PendingTap0302HeadProbe.kt")
            assertEquals("contract(O04t/$profile): runtime profile from real variant", profile, json(raw, "O04t")["d24_profile"]?.jsonPrimitive?.content)
            assertEquals("contract(O04t/$profile): bundle retains full raw trace", json(raw, "O04t"), profiles[profile])
            assertEquals("contract(O04t/$profile): provenance profile", profile, p["d24_profile"]?.jsonPrimitive?.content)
        }
        val profile = if (ReleaseAdmission.isOpen) "admitted" else "closed"
        val same = S6PendingTap0302HeadProbe.trace()
        assertEquals("contract(O04t/$profile): frozen HEAD equals candidate same probe", profiles[profile], same)
        var pac = 0; var auth = 0; var fences = 0
        fun connected() = AlertEventBus(KrxContestSwitch.OFF,
            Provider<PremiumAccessCoordinator> { pac++; throw AssertionError("contract(O04t/OFF): PAC provider resolved") },
            Provider<AuthTokenProvider> { auth++; throw AssertionError("contract(O04t/OFF): auth provider resolved") },
            Provider<AuthFenceStream> { fences++; throw AssertionError("contract(O04t/OFF): fence provider resolved") })
        assertEquals("contract(O04t/$profile): connected production OFF public runtime", same, S6PendingTap0302HeadProbe.trace(::connected))
        val bus = connected()
        assertFalse("contract(O04t/OFF): injected shared OFF getter", bus.pendingTapEnabled)
        assertEquals("contract(O04t/OFF): report has no owner activity", PendingTapPurgeReport(), bus.pendingTapReport())
        assertEquals("contract(O04t/OFF): PAC/auth/fence observer/metadata/settings/token additional resolution zero", listOf(0, 0, 0), listOf(pac, auth, fences))
    }

    @Test fun o04s_androidOffFragmentsEqualFrozen0302Bytes() = marked("O04s") {
        val indexBytes = bytes("off_shells/index.json", "O04s")
        provenance("off_shells/provenance.json", indexBytes, "O04s", "src/test/java/com/jay/fxi/S6Krx3cShellSourceContract.kt", "src/test/java/com/jay/fxi/S6Krx3cO04CaptureTest.kt")
        val index = json(indexBytes, "O04s")
        assertEquals("contract(O04s/schema): schema", JsonPrimitive(1), index["schema"])
        assertEquals("contract(O04s/head): exact0302", JsonPrimitive(S6PendingTap0302HeadProbe.HEAD), index["head"])
        assertEquals("contract(O04s/kind): static evidence never native runtime", JsonPrimitive("STATIC_ORIGINAL"), index["evidence_kind"])
        assertEquals("contract(O04s/full-source): exact0302 full-source SHA map", S6Krx3cShellSourceContract.HEAD_SOURCE_SHA,
            index.getValue("sources").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val source = S6Krx3cShellSourceContract.load()
        val spans = S6Krx3cShellSourceContract.spans(source, true)
        assertEquals("contract(O04s/columns): four bodies plus five auxiliaries", spans.keys, index.getValue("spans").jsonObject.keys)
        for ((id, span) in spans) {
            val frozen = bytes("off_shells/$id.raw", "O04s/$id")
            val entry = index.getValue("spans").jsonObject.getValue(id).jsonObject
            assertEquals("contract(O04s/$id): source locator", span.source, entry["source"]?.jsonPrimitive?.content)
            assertEquals("contract(O04s/$id): raw length", frozen.size, entry["length"]?.jsonPrimitive?.int)
            assertEquals("contract(O04s/$id): immutable raw hash", S6Krx3cShellSourceContract.sha(frozen), entry["sha256"]?.jsonPrimitive?.content)
            assertTrue("contract(O04s/$id): original byte offset recorded", entry["offset"]?.jsonPrimitive?.int?.let { it >= 0 } == true)
            assertArrayEquals("contract(O04s/$id): exact bytes including indentation/comments/literals", frozen, span.raw.toByteArray(Charsets.UTF_8))
        }
        val tricky = "fun boundary() { val text = \"}\"; val raw = \"\"\"{ }\"\"\"; /* { /* } */ } */ val c = '}' }"
        val actual = S6Krx3cShellSourceContract.function(tricky, "boundary").raw
        assertTrue("contract(O04s/lexer): literals and nested comments preserved raw", actual.contains("/* { /* } */ } */"))
        assertFalse("contract(O04s/negative): same raw comparison rejects conditional consume", spans.getValue("Root").raw == spans.getValue("Root").raw.replace("alertEventBus?.emit(event)", "if (alertEventBus?.emit(event) == true)"))
    }

    @Test fun o05_realSharedSwitchAndShellWiringAreExact() = marked("O05") {
        val module = File("src/main/java/com/jay/fxi/di/EntitlementsModule.kt").readText()
        assertSame("contract(O05/DI): provider returns original OFF object", KrxContestSwitch.OFF, EntitlementsModule.provideKrxContestSwitch())
        val busSource = File("src/main/java/com/jay/fxi/service/AlertEventBus.kt").readText()
        val stripped = S6Krx3cShellSourceContract.mask(busSource, false)
        assertTrue("contract(O05/mode): getter reads only same injected switch", Regex("pendingTapEnabled\\s*:\\s*Boolean\\s+get\\(\\)\\s*=\\s*krxContestSwitch\\.enabled\\b").containsMatchIn(stripped))
        assertTrue("contract(O05/DI): explicit shared OFF PAC argument", Regex("krxContest\\s*=\\s*provideKrxContestSwitch\\(\\)").containsMatchIn(S6Krx3cShellSourceContract.mask(module)))
        fun independentSwitch(text: String) = Regex("(?<![A-Za-z_0-9])KrxContestSwitch\\s*\\(")
            .containsMatchIn(S6Krx3cShellSourceContract.mask(text))
        val providerControl = "fun provideKrxContestSwitch(): KrxContestSwitch = KrxContestSwitch.OFF\n" +
            "val pac = provideKrxContestSwitch()"
        assertFalse("contract(O05/DI-control): provider declaration/call is not a constructor", independentSwitch(providerControl))
        assertFalse("contract(O05/DI-control): larger identifier is not a constructor", independentSwitch("val x = otherKrxContestSwitch(" + "true)"))
        assertTrue("contract(O05/DI-control): same scan detects inserted standalone constructor",
            independentSwitch(providerControl + "\nval injected = KrxContestSwitch(" + "true)"))
        assertTrue("contract(O05/DI-control): same scan detects qualified constructor",
            independentSwitch("val injected = com.jay.fxi.data.entitlements.KrxContestSwitch(enabled = " + "true)"))
        assertFalse("contract(O05/DI): production no independent ON switch", independentSwitch(module))
        assertTrue("contract(O05/DI-control): actual module plus constructor is rejected",
            independentSwitch(module + "\nval injected = KrxContestSwitch(" + "true)"))
        val rootControl = S6Krx3cShellArgumentContract.ROOT_ARGUMENT_CONTROL
        val warmControl = S6Krx3cShellArgumentContract.WARM_ARGUMENT_CONTROL
        assertTrue("contract(O05/root-arguments): API-locked positive control", S6Krx3cShellArgumentContract.rootArguments(rootControl))
        for ((column, changed) in listOf(
            "wrong-event" to rootControl.replace("forwardRoot(event,", "forwardRoot(otherEvent,"),
            "foreign-custody" to rootControl.replace("alertEventBus::forwardPendingTap", "otherBus::forwardPendingTap"),
            "constant-custody" to rootControl.replace("alertEventBus::forwardPendingTap", "{ true }"),
            "stale-current" to rootControl.replace("pendingAlertEvent?.value", "pendingEvent"),
            "wrong-current-ticket" to rootControl.replace("?.value, expected", "?.value, otherTicket"),
            "unconditional-slot" to rootControl.replace("if (AlertTapEntry.clearSlot(pendingAlertEvent?.value, expected) == null)", ""),
            "callback-outside-gate" to rootControl + "\nonPendingAlertEventConsumed()",
            "fake-comment-only" to "/* $rootControl */"))
            assertFalse("contract(O05/root-$column): same argument parser rejects wrong binding", S6Krx3cShellArgumentContract.rootArguments(changed))
        assertTrue("contract(O05/warm-arguments): API-locked positive control", S6Krx3cShellArgumentContract.warmArguments(warmControl))
        for ((column, changed) in listOf(
            "wrong-extras" to warmControl.replace("Extras(intent)", "Extras(otherIntent)"),
            "wrong-bus" to warmControl.replace("alertEventBusProvider", "otherBusProvider"),
            "unbound-intake" to warmControl.replace("alertEventBusProvider.get().pendingTapIntake()", "otherIntake"),
            "swapped" to "AlertTapEntry.intakeWarm(alertEventBusProvider.get().pendingTapIntake(), AndroidAlertTapExtras(intent))"))
            assertFalse("contract(O05/warm-$column): same argument parser rejects wrong binding", S6Krx3cShellArgumentContract.warmArguments(changed))
        val sources = S6Krx3cShellSourceContract.load()
        val exactBindings = S6Krx3cShellArgumentContract.wiring(sources)
        val legacyWiring = S6Krx3cShellSourceContract.wiring(sources)
        // The stronger argument parser accepts either a bound method reference or its one-argument
        // lambda. It replaces only this legacy textual call-presence check, never another issue.
        val wiring = legacyWiring.filterNot { exactBindings && it == "Root uses custody dispatch and conditional slot operation" }
        assertEquals("contract(O05/shell): exact guards/calls/arguments/custody/order/bypass inventory", emptyList<String>(), wiring)
        assertTrue("contract(O05/shell-arguments): actual Root/warm bindings pass the same exact argument parser", exactBindings)
        val warmPath = "MainActivity.kt"
        val warm = sources.getValue(warmPath)
        val bypass = sources + (warmPath to warm.replace("AlertTapEntry.intakeWarm", "alertEventBusProvider.get().emit"))
        assertTrue("contract(O05/μ35): same validator rejects native warm bypass", S6Krx3cShellSourceContract.wiring(bypass).isNotEmpty())
        val cancel = sources + (warmPath to warm.replace("handleNotificationIntent(intent)", "handleNotificationIntent(intent)\n        notificationManager.cancelAll()"))
        assertTrue("contract(O05/μ34): same validator rejects untapped tray cancellation", S6Krx3cShellSourceContract.wiring(cancel).isNotEmpty())
        for (call in listOf("intakeNew", "intakeWarm", "forwardRoot", "copyForegroundClassification")) {
            val mutated = sources.mapValues { it.value.replace("AlertTapEntry.$call", "FakeEntry.$call") }
            assertTrue("contract(O05/$call): token-only fake caller rejected", S6Krx3cShellSourceContract.wiring(mutated).isNotEmpty())
        }
        val consumer = File("src/main/java/com/jay/fxi/ui/viewmodel/AlertViewModel.kt").readText()
        assertTrue("contract(O05/consumer): actual consumer registration/receipt/finally detach", S6Krx3cShellSourceContract.consumer(consumer))
        assertFalse("contract(O05/consumer): null bank action cannot ACK", S6Krx3cShellSourceContract.consumer(consumer + "\nfun bad() { when (x) { null -> AlertTapReceipt.HANDLED } }"))
        val adapter = File("src/main/java/com/jay/fxi/service/AndroidAlertTapExtras.kt").readText()
        assertTrue("contract(O05/adapter): exact typed native mapping", S6Krx3cShellSourceContract.adapter(adapter))
        assertFalse("contract(O05/adapter): wrong getter argument rejected", S6Krx3cShellSourceContract.adapter(adapter.replace("getIntExtra(key, default)", "getIntExtra(\"wrong\", default)")))
        for (path in listOf("service/AlertTapEntry.kt", "service/PendingAlertTapOwner.kt")) {
            val source = S6Krx3cShellSourceContract.mask(File("src/main/java/com/jay/fxi/$path").readText())
            assertFalse("contract(O05/$path): pure decisions no native/repository/network dependencies", Regex("\\b(?:android|retrofit2|okhttp3|repository)\\b").containsMatchIn(source))
        }
        // Public noarg shape must keep compiling, and carrier has no payload field or direct bank action.
        AlertEventBus()
        val carrier = AlertEvent.PendingTap(AlertTapTicket("foreign", 1))
        assertNull("contract(O05/carrier): bankAction cannot unwrap opaque ticket", AlertPushRouting.bankAction(carrier))
        fun storedFields(type: Class<*>) = type.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith('$') }.map { it.name }.sorted()
        fun onlyTicket(type: Class<*>) = storedFields(type) == listOf("ticket")
        class TicketControl(val ticket: AlertTapTicket)
        class RawLeakControl(val ticket: AlertTapTicket, val raw: AlertEvent)
        class MetadataLeakControl(val ticket: AlertTapTicket, val metadata: AlertTapMetadata)
        assertTrue("contract(O05/carrier-control): same field scan accepts ticket-only class", onlyTicket(TicketControl::class.java))
        assertEquals("contract(O05/carrier-control): real raw field remains visible", listOf("raw", "ticket"), storedFields(RawLeakControl::class.java))
        assertFalse("contract(O05/carrier-control): same scan rejects added raw payload", onlyTicket(RawLeakControl::class.java))
        assertEquals("contract(O05/carrier-control): real metadata remains visible", listOf("metadata", "ticket"), storedFields(MetadataLeakControl::class.java))
        assertFalse("contract(O05/carrier-control): same scan rejects added metadata", onlyTicket(MetadataLeakControl::class.java))
        assertEquals("contract(O05/carrier): only ticket is stored", listOf("ticket"), storedFields(carrier.javaClass))
        KrxContestWiringContractTest().KW02_productionNeverEnablesTheContest()
        KrxContestWiringContractTest().KW11_theSnapshotIsSuppliedOnlyByThePublishedValue()
        KrxSessionWiringContractTest().z01_alphaProductionIsOff_withoutKrxProducer()
    }

    @Test fun o06_exactSourceInventoryRejectsExpansion() = marked("O06") {
        val actual = S6Krx3bOffWiringContractTest.sourceInventory()
        fun fingerprint(map: Map<String, String>): String = S6Krx3cShellSourceContract.sha(map.keys.filter { it !in NORMAL }.sorted()
            .joinToString("") { "$it\u0000${S6Krx3cShellSourceContract.sha(map.getValue(it).toByteArray(Charsets.UTF_8))}\n" }.toByteArray(Charsets.UTF_8))
        val raw = actual.keys.associateWith { File(it).readText(Charsets.UTF_8) }
        assertTrue("contract(O06/normal): exact10 production files exist", NORMAL.all { it in actual })
        assertEquals("contract(O06/protected): exact352 unchanged production files", 352, raw.keys.count { it !in NORMAL })
        assertEquals("contract(O06/raw): protected HEAD fingerprint", PROTECTED, fingerprint(raw))
        val protected = raw.keys.first { it !in NORMAL }
        assertNotEquals("contract(O06/μ32): same fingerprint rejects one protected-file bypass", PROTECTED, fingerprint(raw + (protected to raw.getValue(protected) + "\nval bypass = true\n")))
        assertNotEquals("contract(O06/new-file): allowed directory is not permission to add files", PROTECTED, fingerprint(raw + ("src/main/java/com/jay/fxi/service/Bypass.kt" to "val bypass = true")))
        S6Krx3bOffWiringContractTest().o08_sourceInventoriesRejectUnapprovedExpansion()
        S6KrxOffWiringContractTest().o06_sourceBoundary_isExactAndStillOff()
    }

    companion object {
        internal val NORMAL = listOf("MainActivity.kt", "ui/screen/RootScreen.kt", "service/AlertEventBus.kt", "service/AlertPushRouting.kt",
            "service/FXiMessagingService.kt", "ui/viewmodel/AlertViewModel.kt", "di/EntitlementsModule.kt", "service/PendingAlertTapOwner.kt",
            "service/AlertTapEntry.kt", "service/AndroidAlertTapExtras.kt").map { "src/main/java/com/jay/fxi/$it" }.toSet()
        internal const val PROTECTED = "26665a32e040c61a87e6a26fceec79e9544836e4dd8a7576ae6e01e22606e2e4"
    }
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

}

/** O05-specific parser is separate from the immutable HEAD extractor/probe source. */
internal object S6Krx3cShellArgumentContract {
    private fun mask(source: String) = S6Krx3cShellSourceContract.mask(source)
    private fun close(source: String, open: Int, left: Char = '{', right: Char = '}') = S6Krx3cShellSourceContract.close(source, open, left, right)
    private fun compact(source: String) = S6Krx3cShellSourceContract.mask(source, false).replace(Regex("\\s+"), "")
    private fun calls(source: String, name: String) = Regex("\\b${Regex.escape(name)}\\s*\\(").findAll(mask(source)).count()
    /** Split only syntax boundaries; literal/comment text never supplies a call or argument. */
    private fun splitArguments(raw: String): List<String> {
        val code = mask(raw); val result = mutableListOf<String>(); var start = 0; var i = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> i = close(raw, i, '(', ')')
                '{' -> i = close(raw, i)
                '[' -> i = close(raw, i, '[', ']')
                ',' -> { result += raw.substring(start, i).trim(); start = i + 1 }
            }
            i++
        }
        val last = raw.substring(start).trim()
        if (last.isNotEmpty()) result += last
        return result
    }
    private fun arguments(body: String, callee: String, parameters: List<String>): Map<String, String>? {
        val code = mask(body)
        val found = Regex("(?<![A-Za-z_0-9.])${Regex.escape(callee)}\\s*\\(").findAll(code).toList()
        if (found.size != 1) return null
        val open = code.indexOf('(', found.single().range.first)
        val end = close(body, open, '(', ')')
        val supplied = splitArguments(body.substring(open + 1, end)).toMutableList()
        var trailing = end + 1
        while (trailing < code.length && code[trailing].isWhitespace()) trailing++
        if (code.getOrNull(trailing) == '{') supplied += parameters.last() + " = " + body.substring(trailing, close(body, trailing) + 1)
        val answer = linkedMapOf<String, String>(); var positional = 0
        for (arg in supplied) {
            val named = Regex("^([A-Za-z_][A-Za-z_0-9]*)\\s*=(?!=)").find(mask(arg))
            val name = if (named != null) named.groupValues[1] else parameters.getOrNull(positional++) ?: return null
            if (name !in parameters || name in answer) return null
            answer[name] = if (named == null) arg else arg.substring(named.range.last + 1).trim()
        }
        return answer.takeIf { it.keys == parameters.toSet() }
    }
    /** Resolve simple immutable local aliases only; mutable/duplicate/shadow bindings are rejected. */
    private fun resolve(expression: String, body: String, seen: Set<String> = emptySet()): String? {
        val expressionCode = compact(expression)
        if (!expressionCode.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) return expressionCode
        if (expressionCode in seen) return null
        val matches = Regex("\\b(?:val|var)\\s+${Regex.escape(expressionCode)}\\s*=").findAll(mask(body)).toList()
        if (matches.isEmpty()) return expressionCode
        if (matches.size != 1 || !matches.single().value.trimStart().startsWith("val ")) return null
        val begin = matches.single().range.last + 1; val code = mask(body); var end = begin
        while (end < code.length) {
            if (code[end] == '\n' || code[end] == ';') break
            when (code[end]) {
                '(' -> end = close(body, end, '(', ')')
                '{' -> end = close(body, end)
                '[' -> end = close(body, end, '[', ']')
            }
            end++
        }
        return resolve(body.substring(begin, end).trim(), body, seen + expressionCode)
    }
    private fun lambda(expression: String): Pair<String, String>? {
        val code = mask(expression).trim()
        if (!code.startsWith('{') || !code.endsWith('}')) return null
        val raw = expression.trim().substring(1, expression.trim().length - 1)
        val head = Regex("^\\s*([A-Za-z_][A-Za-z_0-9]*)\\s*->").find(mask(raw))
        return if (head == null) "it" to raw else head.groupValues[1] to raw.substring(head.range.last + 1)
    }
    fun warmArguments(on: String): Boolean = try {
        val args = arguments(on, "AlertTapEntry.intakeWarm", listOf("extras", "intake"))
        args != null && resolve(args.getValue("extras"), on) == "AndroidAlertTapExtras(intent)" &&
            resolve(args.getValue("intake"), on).let { resolved ->
                resolved == "alertEventBusProvider.get().pendingTapIntake()" ||
                    // One immutable bus alias must resolve to the same injected singleton provider.
                    Regex("([A-Za-z_][A-Za-z_0-9]*)\\.pendingTapIntake\\(\\)").matchEntire(resolved.orEmpty())?.groupValues?.get(1)
                        ?.let { resolve(it, on) == "alertEventBusProvider.get()" } == true
            }
    } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
    fun rootArguments(on: String): Boolean = try {
        val args = arguments(on, "AlertTapEntry.forwardRoot", listOf("event", "hasCustody", "consumeSlot"))
        if (args == null) false else {
            val event = resolve(args.getValue("event"), on)
            val custody = compact(args.getValue("hasCustody"))
            val custodyLambda = lambda(args.getValue("hasCustody"))
            val custodyExact = custody == "alertEventBus::forwardPendingTap" || custodyLambda?.let { (parameter, code) ->
                compact(code) == "alertEventBus.forwardPendingTap($parameter)"
            } == true
            val slot = lambda(args.getValue("consumeSlot"))
            val slotExact = slot?.let { (expected, code) ->
                val required = "if(AlertTapEntry.clearSlot(pendingAlertEvent?.value,$expected)==null)"
                val normalized = compact(code)
                normalized == required + "onPendingAlertEventConsumed()" || normalized == required + "{onPendingAlertEventConsumed()}"
            } == true
            (event == "pendingEvent" || event == "pendingEvent?:return@LaunchedEffect") && custodyExact && slotExact &&
                calls(on, "onPendingAlertEventConsumed") == 1 && calls(on, "clearSlot") == 1
        }
    } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
    internal val ROOT_ARGUMENT_CONTROL = """
        val event = pendingEvent ?: return@LaunchedEffect
        AlertTapEntry.forwardRoot(event, hasCustody = alertEventBus::forwardPendingTap, consumeSlot = { expected ->
            if (AlertTapEntry.clearSlot(pendingAlertEvent?.value, expected) == null) onPendingAlertEventConsumed()
        })
    """.trimIndent()
    internal val WARM_ARGUMENT_CONTROL = """
        AlertTapEntry.intakeWarm(AndroidAlertTapExtras(intent), alertEventBusProvider.get().pendingTapIntake())
    """.trimIndent()


    fun wiring(sources: Map<String, String>): Boolean = try {
        fun on(body: String, root: Boolean): String {
            val guard = if (root) Regex("""if\s*\(\s*alertEventBus\?\.pendingTapEnabled\s*==\s*true\s*\)""")
                else Regex("""if\s*\(\s*krxContestSwitch\.enabled\s*\)""")
            val code = mask(body)
            val found = guard.findAll(code).toList()
            require(found.size == 1) { "one actual mode branch" }
            val open = code.indexOf('{', found.single().range.last)
            return body.substring(open + 1, close(body, open))
        }
        val warm = S6Krx3cShellSourceContract.function(sources.getValue("MainActivity.kt"), "handleNotificationIntent").raw
        val root = sources.getValue("ui/screen/RootScreen.kt")
        val effect = Regex("""LaunchedEffect\s*\(\s*pendingEvent\s*\)""").find(mask(root)) ?: error("actual pending effect absent")
        val open = mask(root).indexOf('{', effect.range.last)
        warmArguments(on(warm, false)) && rootArguments(on(root.substring(open + 1, close(root, open)), true))
    } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
}
