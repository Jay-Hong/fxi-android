package com.jay.fxi

import com.jay.fxi.data.entitlements.KrxContestSwitch
import com.jay.fxi.data.remote.s64Cases
import com.jay.fxi.data.remote.TopicGraphInput
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class S6Krx4OffContractTest {
    @Test fun o41_realHeadColumnsEqualCandidateOff() {
        val envelope = frozen("O41","off_head_trace.json").jsonObject.getValue("variants").jsonObject.getValue(BuildConfig.BUILD_TYPE).jsonObject
        assertEquals("precondition(O41/frozen): schema",S6Krx4HeadProbe.SCHEMA,envelope.getValue("schema").jsonPrimitive.content)
        assertEquals("precondition(O41/frozen): archive HEAD",S6Krx4HeadProbe.HEAD,envelope.getValue("head").jsonPrimitive.content)
        assertEquals("precondition(O41/frozen): variant",BuildConfig.BUILD_TYPE,envelope.getValue("variant").jsonPrimitive.content)
        val raw = envelope.getValue("raw_columns").jsonObject; val columns = envelope.getValue("columns").jsonObject
        assertEquals("precondition(O41/frozen): all raw columns",S6Krx4HeadProbe.COLUMNS.toSet(),raw.keys)
        assertEquals("precondition(O41/frozen): all normalized columns",raw.keys,columns.keys)
        for (column in s64Cases("O41",S6Krx4HeadProbe.COLUMNS)) {
            val head = columns.getValue(column).jsonObject
            assertEquals("precondition(O41/$column): raw-to-alias binding",S6Krx4HeadProbe.normalize(raw.getValue(column)),head)
            lateinit var observed: S64HeadWorld
            val candidate = S6Krx4HeadProbe.normalize(S6Krx4HeadProbe.trace(column) { w,factory ->
                observed = w; factory.installKrxDesiredProducer(KrxContestSwitch.OFF)
            }).jsonObject
            assertEquals("precondition(O41/$column): common observation shape",head.keys,candidate.keys)
            for (name in head.keys) assertEquals("contract(O41): $column/$name",head.getValue(name),candidate.getValue(name))
            assertFalse("contract(O41): $column/OFF K memory zero",observed.runtime.display.value.rates.quotes.values.any { it.source == "krx" })
            assertFalse("contract(O41): $column/OFF K graph ingress zero",observed.graph.any { input -> when (input) {
                is TopicGraphInput.Observations -> input.topic == S64HeadWorld.K
                is TopicGraphInput.Continuity -> S64HeadWorld.K in input.topics
            } })
        }
    }

    @Test fun o42_probeProvenanceAndOldOffEvidenceStayIntact() {
        val cases = listOf("A1a-alias","TBO-rename","legacy-bytes","HEAD-API-only","provenance-binding","tamper-head","tamper-trace-hash","tamper-probe-hash","tamper-exit","candidate-API","trace-column","HEAD-tree","execution-tree","execution-manifest","XML")
        for (sub in s64Cases("O42",cases)) {
            val r = File("src/test/resources/krx/s64a"); val probe = File("src/test/java/com/jay/fxi/S6Krx4HeadProbe.kt").readBytes()
            when (sub) {
                "A1a-alias" -> {
                    val a1a = File("src/test/java/com/jay/fxi/data/remote/A1aHeadTopicSessionCoordinator.kt").readText()
                    val alias = "\nimport com.jay.fxi.data.remote.A1aHeadTopicBootstrapOrder as TopicBootstrapOrder\n"
                    assertEquals("precondition(O42/A1a-alias): alias exactly once",1,Regex(Regex.escape(alias)).findAll(a1a).count())
                    assertEquals("contract(O42): A1a-alias/only permitted delta",File(r,"a1a_head_source.kt.txt").readText(),a1a.replaceFirst(alias,""))
                }
                "TBO-rename" -> {
                    val original = File(r,"a1a_head_bootstrap_order.kt.txt").readBytes()
                    assertEquals("contract(O42): TBO-rename/original SHA","03690d9e7a709d4da010495c5a8b6dce7e16970cc98ef173fa04c860bc042638",sha(original))
                    assertEquals("contract(O42): TBO-rename/object rename only",original.toString(Charsets.UTF_8),File("src/test/java/com/jay/fxi/data/remote/A1aHeadTopicBootstrapOrder.kt").readText().replace("object A1aHeadTopicBootstrapOrder {","object TopicBootstrapOrder {"))
                }
                "legacy-bytes" -> for ((path,hash) in inventory().getValue("legacy_evidence").jsonObject)
                    assertEquals("contract(O42): legacy-bytes/$path",hash.jsonPrimitive.content,sha(File(path).readBytes()))
                "HEAD-API-only" -> assertFalse("contract(O42): HEAD-API-only/no candidate APIs or reflection",forbiddenProbe(probe))
                else -> {
                    val wrapper = frozen("O42","off_head_trace.json").jsonObject.getValue("variants").jsonObject.getValue(BuildConfig.BUILD_TYPE)
                    val p = frozen("O42","off_head_trace.provenance.json").jsonObject.getValue("variants").jsonObject.getValue(BuildConfig.BUILD_TYPE).jsonObject
                    val trace = try { Base64.getDecoder().decode(p.getValue("trace_base64").jsonPrimitive.content) } catch (failure: Exception) {
                        throw AssertionError("precondition(O42/$sub): actual raw trace base64",failure)
                    }
                    assertEquals("precondition(O42/$sub): wrapper is exact raw envelope",wrapper,Json.parseToJsonElement(trace.toString(Charsets.UTF_8)))
                    assertTrue("precondition(O42/$sub): untampered HEAD provenance accepted",valid(p,probe,trace))
                    when (sub) {
                        "provenance-binding" -> assertTrue("contract(O42): provenance-binding/exact archive overlay raw XML trace",valid(p,probe,trace))
                        "candidate-API" -> {
                            val bad = probe + "\nfun candidateOnly() = installKrxDesiredProducer(other)".toByteArray()
                            val manifest = expectedManifest(bad)
                            assertFalse("contract(O42): candidate-API/rejected with refreshed hash",valid(JsonObject(p + mapOf(
                                "probe_sha256" to JsonPrimitive(sha(bad)),"execution_manifest" to manifest,
                                "execution_tree" to JsonPrimitive(sha(canonical(manifest).toString().toByteArray())))),bad,trace))
                        }
                        "trace-column" -> {
                            val e = wrapper.jsonObject; val columns = e.getValue("columns").jsonObject
                            val changed = JsonObject(e + ("columns" to JsonObject(columns - "USD"))).toString().toByteArray()
                            assertFalse("contract(O42): trace-column/rejected even with refreshed raw hash",valid(JsonObject(p + mapOf("trace_sha256" to JsonPrimitive(sha(changed)),"trace_base64" to JsonPrimitive(Base64.getEncoder().encodeToString(changed)))),probe,changed))
                        }
                        "execution-manifest" -> {
                            val manifest = p.getValue("execution_manifest").jsonObject
                            val files = manifest.getValue("files").jsonObject
                            val altered = JsonObject(manifest + ("files" to JsonObject(files + ("ANDROID_V2_PLAN.md" to JsonPrimitive("candidate-tree")))))
                            assertFalse("contract(O42): execution-manifest/candidate overlay rejected with refreshed hash",valid(JsonObject(p+mapOf("execution_manifest" to altered,"execution_tree" to JsonPrimitive(sha(canonical(altered).toString().toByteArray())))),probe,trace))
                        }
                        "XML" -> {
                            val xml = Base64.getDecoder().decode(p.getValue("xml_base64").jsonPrimitive.content).toString(Charsets.UTF_8)
                            val bad = xml.replace("failures=\"0\"","failures=\"1\"").toByteArray()
                            assertNotEquals("precondition(O42/XML): actual failure attribute changed",xml,bad.toString(Charsets.UTF_8))
                            assertFalse("contract(O42): XML/failed suite rejected with refreshed hash",valid(JsonObject(p + mapOf("xml_sha256" to JsonPrimitive(sha(bad)),"xml_base64" to JsonPrimitive(Base64.getEncoder().encodeToString(bad)))),probe,trace))
                        }
                        else -> {
                            val key = when (sub) { "tamper-head" -> "head_sha"; "tamper-trace-hash" -> "trace_sha256"; "tamper-probe-hash" -> "probe_sha256"; "tamper-exit" -> "exit"; "HEAD-tree" -> "head_tree"; else -> "execution_tree" }
                            assertFalse("contract(O42): $sub/rejected",valid(JsonObject(p + (key to JsonPrimitive("tampered"))),probe,trace))
                        }
                    }
                }
            }
        }
    }

    /** Canonical manifest hash binds every original tracked blob and the sole probe overlay. */
    private fun expectedManifest(probe: ByteArray): JsonObject = buildJsonObject {
        put("head",S6Krx4HeadProbe.HEAD); put("head_tree",inventory().getValue("head_tree"))
        put("variant",BuildConfig.BUILD_TYPE); put("schema",S6Krx4HeadProbe.SCHEMA)
        put("files",JsonObject(inventory().getValue("head_execution_files").jsonObject +
            ("app/src/test/java/com/jay/fxi/S6Krx4HeadProbe.kt" to JsonPrimitive(sha(probe)))))
    }
    private fun canonical(j: JsonElement): JsonElement = when (j) {
        is JsonObject -> JsonObject(j.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(j.map(::canonical))
        else -> j
    }
    private fun forbiddenProbe(probe: ByteArray): Boolean = listOf("installKrxDesiredProducer","syncRuntimeKrxDesired","bootstrapShownBy","getDeclaredField","setField").any(probe.toString(Charsets.UTF_8)::contains)
    private fun valid(p: JsonObject,probe: ByteArray,trace: ByteArray): Boolean {
        val required = setOf("head_sha","head_tree","probe_sha256","variant","input_schema","command","exit","xml_sha256","trace_sha256","archive_source_sha256","execution_tree","xml_base64","trace_base64")
        if (!required.all { p[it] is JsonPrimitive && p[it]?.jsonPrimitive?.content?.isNotBlank() == true } || forbiddenProbe(probe)) return false
        val command = p.getValue("command").jsonPrimitive.content
        val task = mapOf("debug" to ":app:testDebugUnitTest","ciMinified" to ":app:testCiMinifiedUnitTest","benchmark" to ":app:testBenchmarkUnitTest","s5Benchmark" to ":app:testS5BenchmarkUnitTest")[BuildConfig.BUILD_TYPE] ?: return false
        val environment = p["environment"] as? JsonObject ?: return false
        val destination = environment["S6_4A_HEAD_TRACE_OUT"]?.jsonPrimitive?.content ?: return false
        if (!destination.startsWith("/") || !command.contains(task) || !command.contains("com.jay.fxi.S6Krx4HeadProbe.writeHeadTraceOnlyWhenRequested") || !command.contains(destination)) return false
        val manifest = p["execution_manifest"] as? JsonObject ?: return false
        val expected = expectedManifest(probe)
        if (manifest != expected || p.getValue("execution_tree").jsonPrimitive.content != sha(canonical(expected).toString().toByteArray())) return false
        val envelope = try { Json.parseToJsonElement(trace.toString(Charsets.UTF_8)).jsonObject } catch (_: Exception) { return false }
        if (envelope.keys != setOf("schema","head","variant","raw_columns","columns")) return false
        if (envelope["schema"]?.jsonPrimitive?.content != S6Krx4HeadProbe.SCHEMA || envelope["head"]?.jsonPrimitive?.content != S6Krx4HeadProbe.HEAD || envelope["variant"]?.jsonPrimitive?.content != BuildConfig.BUILD_TYPE) return false
        val raw = envelope["raw_columns"] as? JsonObject ?: return false
        val normalized = envelope["columns"] as? JsonObject ?: return false
        if (raw.keys != S6Krx4HeadProbe.COLUMNS.toSet() || normalized.keys != raw.keys || raw.any { (key,value) -> S6Krx4HeadProbe.normalize(value) != normalized[key] }) return false
        val xml = try { Base64.getDecoder().decode(p.getValue("xml_base64").jsonPrimitive.content) } catch (_: IllegalArgumentException) { return false }
        if (p.getValue("xml_sha256").jsonPrimitive.content != sha(xml)) return false
        val document = try {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
                .newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml))
        } catch (_: Exception) { return false }
        val suite = document.documentElement
        if (suite.getAttribute("failures") != "0" || suite.getAttribute("errors") != "0" || suite.getAttribute("skipped") != "0") return false
        val cases = document.getElementsByTagName("testcase")
        if (cases.length != 1 || (cases.item(0) as org.w3c.dom.Element).let { it.getAttribute("classname") != "com.jay.fxi.S6Krx4HeadProbe" || it.getAttribute("name") != "writeHeadTraceOnlyWhenRequested" }) return false
        return p.getValue("head_sha").jsonPrimitive.content == S6Krx4HeadProbe.HEAD && p.getValue("head_tree") == inventory().getValue("head_tree") &&
            p.getValue("exit").jsonPrimitive.content == "0" && p.getValue("variant").jsonPrimitive.content == BuildConfig.BUILD_TYPE &&
            p.getValue("input_schema").jsonPrimitive.content == S6Krx4HeadProbe.SCHEMA && p.getValue("archive_source_sha256") == inventory().getValue("all_source_fingerprint") &&
            p.getValue("probe_sha256").jsonPrimitive.content == sha(probe) && p.getValue("trace_sha256").jsonPrimitive.content == sha(trace) &&
            p.getValue("trace_base64").jsonPrimitive.content == Base64.getEncoder().encodeToString(trace)
    }
    private fun inventory() = S6KrxReagreementContractTest.resource("source_inventory.json").jsonObject
    private fun sha(bytes: ByteArray) = S6KrxReagreementContractTest.sha(bytes)
    private fun frozen(id: String,name: String): JsonElement {
        val stream = javaClass.classLoader.getResourceAsStream("krx/s64a/$name")
        assertNotNull("precondition($id/archive): Claude-generated $name exists",stream)
        return stream!!.bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    }
}
