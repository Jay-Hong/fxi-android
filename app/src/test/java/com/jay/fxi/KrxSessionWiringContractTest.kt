package com.jay.fxi

import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.KrxSessionSourceContract
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** α-0 r2 §5.5. Source claims have same-scan positive controls; source shape never replaces runtime assertions. */
class KrxSessionWiringContractTest {
    private val root = File("src/main/java/com/jay/fxi")
    private val tests = File("src/test/java/com/jay/fxi")
    private fun strip(s: String) = s.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
    private fun main(name: String) = strip(File(root, name).readText())
    private fun test(name: String) = strip(File(tests, name).readText())
    private fun body(source: String, name: String): String = KrxSessionSourceContract.function(source, name)
    private fun found(text: String, expression: String) = Regex(expression).containsMatchIn(text)
    private fun called(text: String, name: String) = found(text, """(?<!fun )\b$name\s*\(""")

    @Test fun z01_alphaProductionIsOff_withoutKrxProducer() {
        val pac = main("data/entitlements/PremiumAccessCoordinator.kt")
        val module = main("di/EntitlementsModule.kt")
        val session = main("data/remote/TopicSessionCoordinator.kt")
        assertTrue("premise: Z01 scan finds PAC constructor switch", found(pac, """krxContest\s*:\s*KrxContestSwitch"""))
        assertTrue("premise: Z01 module constructs real PAC", found(module, """PremiumAccessCoordinator\s*\("""))
        assertTrue("contract(Z01): PAC default OFF", found(pac, """krxContest\s*:\s*KrxContestSwitch\s*=\s*KrxContestSwitch\.OFF"""))
        assertFalse("contract(Z01): production provider passes no ON switch", found(module, """krxContest\s*="""))
        assertEquals("contract(Z01): production DESIRED remains the five GENERAL topics", setOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot"), TopicCatalogue.DESIRED)
        val all = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
            .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        assertTrue("premise: Z01 production roots are populated", all.size > 100)
        assertFalse("contract(Z01): no producer appends KRX to DESIRED", all.any {
            found(strip(it.readText()), """DESIRED\s*\+\s*(?:setOf\s*\()?TopicCatalogue\.KRX_FUTURES""")
        })
        assertTrue("premise: Z01 catalogue names KRX for positive control", session.contains("KRX_FUTURES"))
    }

    @Test fun z02_allEightPaths_useTheTypedBoundary() {
        val tsc = main("data/remote/TopicSessionCoordinator.kt")
        val cmd = main("data/remote/TopicSubscribeCommand.kt")
        val paths = linkedMapOf(
            "FIRST" to "subscribe", "RENEWAL" to "startRenewal", "RECOVERY" to "reopenAfterRecovery",
            "MANUAL" to "startPendingManual", "EXPLICIT" to "requestBootstrapOnLoop", "G1i" to "issueOwedBootstraps",
            "G1ii" to "startKrxBootstrap", "PERMIT" to "reevaluateKrxPermit"
        )
        assertEquals("premise: Z02 exact eight path inventory", 8, paths.size)
        assertTrue("premise: Z02 CMD declares original drive", found(cmd, """fun\s+drive\s*\("""))
        // Synthetic negative and positive scans use the same per-function extraction as the contract.
        val probe = "fun first() { prepareKrxAttempt(scope) }\nfun second() { Unit }"
        assertTrue("premise: Z02 positive call extraction", called(body(probe, "first"), "prepareKrxAttempt"))
        assertFalse("premise: Z02 neighbouring function is not an allowed call", called(body(probe, "second"), "prepareKrxAttempt"))
        KrxSessionSourceContract.assertClosed()
        val drive = body(cmd, "drive")
        assertTrue("contract(Z02): CMD drive calls typed prepare", called(drive, "prepareKrxAttempt"))
        assertTrue("contract(Z02): CMD drive checks before encode", called(drive, "checkKrxAttemptBeforeEncode"))
        assertTrue("contract(Z02): actual successful typed send notifies boundary", found(drive, """(?:krxAttemptBoundary|boundary)[?.]*sent\s*\("""))
        for ((path, function) in paths) {
            assertTrue("contract(Z02): $path has its exact source entry $function", found(tsc, """fun\s+$function\s*\("""))
        }
        assertTrue("contract(Z02): real session installs CMD boundary before launch", called(body(tsc, "startCommand"), "installKrxAttemptBoundary"))
        assertTrue("contract(Z02): prepare installs original capture", called(body(tsc, "prepareKrxAttempt"), "installKrxPending"))
        assertTrue("contract(Z02): successful callback records SENT", called(body(tsc, "onKrxAttemptSent"), "markKrxPendingSent"))
        assertFalse("contract(Z02): no encoded string KRX removal", found(cmd + tsc, """\.replace\s*\([^\n]*(?:krx:|KRX_FUTURES)"""))
    }

    /** α-2: the inventory is a committed test resource, independent of worktree-local evidence. */
    private fun inventoryResource(path: String): String =
        javaClass.classLoader.getResourceAsStream("krx/alpha/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw AssertionError("premise: Z03 committed inventory resource krx/alpha/$path exists")

    @Test fun z03_rejectionInventory_isCompleteAndMapped() {
        val data = Json.parseToJsonElement(inventoryResource("a1a/rejection_inventory.json")).jsonObject
        val baseline = data.getValue("baseline").jsonArray
        assertEquals("contract(Z03): original 27-file inventory preserved", 27, baseline.size)
        val needsPair = linkedSetOf<String>()
        var originalMethods = 0
        for (entry in baseline) {
            val item = entry.jsonObject
            val path = item.getValue("file").jsonPrimitive.content
            val file = File(tests, path)
            assertTrue("premise: Z03 inventory source $path exists", file.isFile)
            val code = file.readText()
            for (method in item.getValue("methods").jsonArray) {
                val name = method.jsonObject.getValue("name").jsonPrimitive.content
                val classification = method.jsonObject.getValue("classification").jsonPrimitive.content
                originalMethods++
                assertTrue("contract(Z03): $path.$name still exists", code.contains(name))
                assertTrue("contract(Z03): $path.$name has its classification", classification.isNotBlank())
                if (classification.contains("ON 짝 필요")) needsPair += "$path.$name"
            }
        }
        assertEquals("contract(Z03): original 178 methods preserved", 178, originalMethods)
        val rows = Json.parseToJsonElement(inventoryResource("a1a/row_index.json")).jsonObject
        val mapping = Json.parseToJsonElement(inventoryResource("a1a/inventory_mapping.json")).jsonArray
        assertEquals("contract(Z03): each original ON-pair method mapped exactly once", needsPair,
            mapping.map { it.jsonObject.getValue("original").jsonPrimitive.content }.toSet())
        assertEquals("contract(Z03): no duplicate original method mapping", needsPair.size, mapping.size)
        for (entry in mapping) {
            val item = entry.jsonObject
            val original = item.getValue("original").jsonPrimitive.content
            val owned = item.getValue("owned_rows").jsonArray.map { it.jsonPrimitive.content }
            val deferred = item.getValue("deferred_acceptance_rows").jsonArray.map { it.jsonPrimitive.content }
            val status = item.getValue("status").jsonPrimitive.content
            assertTrue("contract(Z03): $original has an owned row or explicit next-bundle acceptance", owned.isNotEmpty() || deferred.isNotEmpty())
            assertEquals("contract(Z03): $original status distinguishes authoring from next-bundle acceptance",
                if (owned.isEmpty()) "NEXT_BUNDLE_ACCEPTANCE_PER_BRIEF" else "MAPPED_A1A", status)
            assertTrue("contract(Z03): $original only defers the brief's K acceptance bundle", deferred.all { it.startsWith("K0") })
            for (row in owned) {
                assertTrue("contract(Z03): $original maps an existing owned row $row", rows.containsKey(row))
                for (method in rows.getValue(row).jsonArray) {
                    val fq = method.jsonPrimitive.content
                    val file = File("src/test/java", fq.substringBeforeLast('.').replace('.', '/') + ".kt")
                    assertTrue("contract(Z03): $row points to a real test method $fq", file.isFile &&
                        found(file.readText(), """\bfun\s+${Regex.escape(fq.substringAfterLast('.'))}\s*\("""))
                }
            }
        }
        val pattern = Regex("KrxEntitlementRequired|KRX_ENTITLEMENT_REQUIRED")
        val actual = tests.walkTopDown().filter { it.isFile && it.extension == "kt" && pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(tests).invariantSeparatorsPath }.toSet()
        // α-1b preserves the α-1a authoring inventory and freezes the expanded exact search separately.
        val current = Json.parseToJsonElement(inventoryResource("a1b/rejection_inventory.json")).jsonObject
        val locked = current.getValue("current_search_files").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("premise: Z03 search finds preserved fixture", actual.contains("data/entitlements/KrxContestHarness.kt"))
        assertEquals("contract(Z03): re-inventory covers every current hit", locked, actual)
        val amendments = baseline.sumOf { entry -> entry.jsonObject.getValue("methods").jsonArray.count {
            it.jsonObject.getValue("classification").jsonPrimitive.content.contains("명시 개정")
        } }
        assertEquals("contract(Z03): every explicitly amended method has old/new evidence", amendments, data.getValue("deferred_old_new").jsonArray.size)
        assertEquals("contract(Z03): current and original inventories agree on explicit amendments", amendments, current.getValue("deferred_old_new").jsonArray.size)
        val alpha2 = data.getValue("alpha2_revisions").jsonArray
        assertEquals("contract(Z03): eight additional explicit amendments recorded", 8, alpha2.size)
        assertEquals("contract(Z03): alpha2 old/new inventory has no duplicate method", alpha2.size, alpha2.map {
            it.jsonObject.getValue("file").jsonPrimitive.content + "." + it.jsonObject.getValue("method").jsonPrimitive.content
        }.toSet().size)
        for (entry in alpha2) {
            val amendment = entry.jsonObject
            val path = amendment.getValue("file").jsonPrimitive.content
            val method = amendment.getValue("method").jsonPrimitive.content
            val recorded = baseline.single { it.jsonObject.getValue("file").jsonPrimitive.content == path }.jsonObject
                .getValue("methods").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == method }.jsonObject
            assertTrue("contract(Z03): $path.$method is explicitly amended", recorded.getValue("classification").jsonPrimitive.content.contains("명시 개정"))
            assertTrue("contract(Z03): $path.$method retains its old premise", amendment.getValue("old").jsonPrimitive.content.isNotBlank())
            assertTrue("contract(Z03): $path.$method states its new observation", amendment.getValue("new").jsonPrimitive.content.isNotBlank())
        }
        assertEquals("contract(Z03): exact helper closure recorded", 27, data.getValue("helper_closure").jsonArray.size)
    }

    @Test fun z04_authorityAndCheckedOn_areActuallyWired() {
        val runtime = main("data/remote/TopicRuntime.kt")
        val module = main("di/GraphRuntimeModule.kt")
        val sua = main("data/entitlements/SnapshotTopicUseAuthority.kt")
        val tsc = main("data/remote/TopicSessionCoordinator.kt")
        val rig = test("data/remote/KrxSessionIntegrationRig.kt")
        val factory = test("data/entitlements/KrxBetaPacRig.kt")
        assertTrue("premise: Z04 provider reads actual PAC snapshot", module.contains("coordinator.accessSnapshot"))
        assertTrue("contract(Z04): one-argument SUA construction stays published snapshot", found(module, """SnapshotTopicUseAuthority\s*\{\s*coordinator\.accessSnapshot\s*\}"""))
        assertTrue("contract(Z04): GENERAL aliases the same reader instance", found(module, """provideTopicUseAuthority\s*\(\s*authority\s*:\s*KrxSendReadAuthority\s*\)\s*:\s*TopicUseAuthority\s*=\s*authority"""))
        assertTrue("contract(Z04): RT serial dispatcher", runtime.contains("limitedParallelism(1)"))
        assertTrue("contract(Z04): RT passes same authority to reader", runtime.contains("krxSendReader = authority"))
        assertTrue("contract(Z04): RT forwards revision into real session via deliverer", runtime.contains("sink = graphGrants?.let { FanOutTopicGrantSink(session, it) } ?: session"))
        assertTrue("contract(Z04): currentRevision is pure metadata", found(sua, """currentRevision\s*\(\s*\)\s*:\s*Long\s*=\s*snapshot\s*\(\s*\)\.revision"""))
        val assignment = Regex("""\bkrxAttemptBoundaryDecorator\s*(?::[^=\n]+)?\s*=(?!=)""")
        val production = listOf("src/main/java", "src/productionBindings/java", "src/s5Benchmark/java", "src/debug/java", "src/benchmark/java")
            .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .associate { it.invariantSeparatorsPath to strip(it.readText()) }
        val writes = production.mapValues { assignment.findAll(it.value).count() }.filterValues { it > 0 }
        assertEquals("contract(Z04): decorator has exactly one production write: its default declaration",
            mapOf("src/main/java/com/jay/fxi/data/remote/TopicSessionCoordinator.kt" to 1), writes)
        assertTrue("contract(Z04): D-B exact nullable internal seam defaults to null", found(tsc,
            """internal\s+var\s+krxAttemptBoundaryDecorator\s*:\s*\(\(KrxCommandAttemptBoundary\)\s*->\s*KrxCommandAttemptBoundary\)\?\s*=\s*null"""))
        val bypass = tsc + "\nfun bad() { krxAttemptBoundaryDecorator = fake }"
        assertEquals("premise: Z04 assignment mutation is detected", 2, assignment.findAll(bypass).count())
        // S6-3a: explicitly amend alpha NONE runtime inventory to SESSION plus the read-only PAC supplier.
        fun sessionCalls(source: String): List<String> = Regex("""\bTopicSessionCoordinator\s*\(""").findAll(source).mapNotNull { head ->
            if (Regex("""\bclass\s+$""").containsMatchIn(source.substring(0, head.range.first))) null else {
                var depth = 1
                var end = head.range.last + 1
                while (end < source.length && depth > 0) {
                    if (source[end] == '(') depth++
                    if (source[end] == ')') depth--
                    end++
                }
                check(depth == 0) { "contract(Z04): unclosed TSC constructor call" }
                source.substring(head.range.last + 1, end - 1)
            }
        }.toList()
        val retirerArgument = Regex("""\bkrxRetirer\s*=(?!=)""")
        fun runtimeRetirers(sources: Map<String, String>) = sources.mapValues { (_, source) ->
            sessionCalls(source).count { retirerArgument.containsMatchIn(it) }
        }.filterValues { it > 0 }
        assertEquals("premise: Z04 exact production TSC call inventory", mapOf("src/main/java/com/jay/fxi/data/remote/TopicRuntime.kt" to 1),
            production.mapValues { sessionCalls(it.value).size }.filterValues { it > 0 })
        assertEquals("contract(Z04): exactly RT passes the SESSION dependency", mapOf("src/main/java/com/jay/fxi/data/remote/TopicRuntime.kt" to 1), runtimeRetirers(production))
        assertTrue("contract(Z04): RT selects SESSION exactly once", Regex("""krxRetirer\s*=\s*KrxSubscriptionRetirer\.SESSION\b""").findAll(runtime).count() == 1)
        val snapshots = production.mapValues { (_, source) -> sessionCalls(source).count {
            Regex("""\bkrxMemorySnapshot\s*=(?!=)""").containsMatchIn(it)
        } }.filterValues { it > 0 }
        assertEquals("contract(Z04): exactly RT forwards the memory snapshot", mapOf("src/main/java/com/jay/fxi/data/remote/TopicRuntime.kt" to 1), snapshots)
        assertTrue("contract(Z04): injected RT supplier reads the same PAC snapshot", found(runtime,
            """krxMemorySnapshot\s*=\s*\{\s*coordinator\.accessSnapshot\s*\}"""))
        assertTrue("contract(Z04): RT forwards its supplier without evaluating it", found(body(runtime, "create"),
            """krxMemorySnapshot\s*=\s*krxMemorySnapshot\b"""))
        assertTrue("contract(Z04): SESSION dispatcher uses object identity", found(body(tsc, "retireKrxSubscription"),
            """krxRetirer\s*===\s*KrxSubscriptionRetirer\.SESSION"""))
        val runtimePath = "src/main/java/com/jay/fxi/data/remote/TopicRuntime.kt"
        val injectedRuntime = production.getValue(runtimePath) + "\nfun bypass() = TopicSessionCoordinator(krxRetirer = injected)"
        assertEquals("contract(Z04): same scan catches a second runtime retirer injection", mapOf(runtimePath to 2), runtimeRetirers(production + (runtimePath to injectedRuntime)))
        val elsewhere = production + ("src/main/java/com/jay/fxi/data/remote/Extra.kt" to "fun bad() = TopicSessionCoordinator(krxMemorySnapshot = injected)")
        assertEquals("contract(Z04): same constructor scan catches external snapshot injection", 2,
            elsewhere.values.sumOf { source -> sessionCalls(source).count { Regex("""\bkrxMemorySnapshot\s*=""").containsMatchIn(it) } })
        assertTrue("contract(Z04): internal retirer defaults to NONE", found(tsc,
            """krxRetirer\s*:\s*KrxSubscriptionRetirer\s*=\s*KrxSubscriptionRetirer\.NONE"""))
        assertTrue("contract(Z04): public-constructor backing dependency also starts at NONE", found(tsc,
            """private\s+var\s+krxRetirer\s*:\s*KrxSubscriptionRetirer\s*=\s*KrxSubscriptionRetirer\.NONE"""))
        assertTrue("contract(Z04): NONE is the literal no-op sink", found(tsc,
            """val\s+NONE\s*:\s*KrxSubscriptionRetirer\s*=\s*KrxSubscriptionRetirer\s*\{\s*\}"""))
        assertTrue("contract(Z04): internal constructor binds the injected dependency", found(tsc, """this\.krxRetirer\s*=\s*krxRetirer\b"""))
        assertTrue("contract(Z04): retirement forwards its actual argument to the sink", found(body(tsc, "retireKrxSubscription"), """krxRetirer\.retire\s*\(\s*retirement\s*\)"""))
        assertTrue("contract(Z04): rig injects the real recording dependency", found(rig, """krxRetirer\s*=\s*retirements\b"""))
        assertTrue("contract(Z04): actual beforeEncode uses metadata reader", body(tsc, "checkKrxAttemptBeforeEncode").contains("currentRevision"))
        assertTrue("contract(Z04): rig composes existing checked factory", found(rig, """\bkrxBetaPac\s*[(]"""))
        assertTrue("contract(Z04): factory installs checker", factory.contains("coordinator.onAccessPublishedForTest = check"))
        assertTrue("contract(Z04): failing body also checks publication in finally", found(rig, """finally\s*\{[\s\S]*\.check\.assertHeld\s*[(]"""))
        assertTrue("contract(Z04): rig uses one real SUA for both roles", rig.contains("authority = uses, krxSendReader = uses"))
        assertFalse("contract(Z04): production session has no CALLER FE", found(tsc + runtime, """\brefresh\s*[(][\s\S]*?FORCE_ENTITLEMENTS"""))
    }
}
