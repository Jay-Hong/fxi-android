package com.jay.fxi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C4 structure ledger (R4c/C4/design_codex.r3.md: LEGACY-ZERO's source half and START-ADMISSION's admission
 * half). It reads the whole main source. What it cannot see — a legacy frame on the real socket, the app's real start, traffic
 * through nginx — belongs to `TopicRuntimeOwnerContractTest` and the host device procedures. The implementation thread reads but
 * does not edit this file.
 */
class C4StructureLedgerTest {

    private val main = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun file(name: String) = main.single { it.name == name }.readText()

    private fun holders(pattern: Regex, except: String? = null) =
        main.filter { it.name != except && pattern.containsMatchIn(it.readText()) }.map { it.name }.sorted()

    @Test
    fun `C4-J-LEGACY-ZERO no legacy rate REST, view model, cache, socket callback or start path is left`() {
        val gone = setOf("ExchangeRateViewModel.kt", "MainScreen.kt", "PremiumUnavailableScreen.kt", "RatesResult.kt")
        assertEquals("C4-J-LEGACY-ZERO files", emptyList<String>(), main.map { it.name }.filter { it in gone })
        val paths = linkedMapOf(
            "legacy view model" to Regex("""\bExchangeRateViewModel\b(?!\.swift)"""),
            "legacy screen call" to Regex("""\bMainScreen\s*\("""),
            "premium shell" to Regex("""\bPremiumUnavailableScreen\b"""),
            "REST result" to Regex("""\bRatesResult\b"""),
            "REST endpoint" to Regex(""""/?api/rates"""),
            "REST call" to Regex("""\bgetRates\s*\("""),
            "socket rates callback" to Regex("""\bonRatesReceived\b"""),
            "socket indices callback" to Regex("""\bonIndicesReceived\b"""),
            "socket rates payload" to Regex("""\bRatesData\b"""),
            "cache writer" to Regex("""\bsaveRates\s*\("""),
            "cache reader" to Regex("""\b(loadCachedRates|cachedRatesTimestamp)\s*\("""),
            "cache keys" to Regex("""\bKEY_RATES(_TIMESTAMP)?\b"""),
            "legacy socket start" to Regex("""\bwebSocketService\s*\.\s*start\s*\(""")
        )
        for ((what, pattern) in paths) assertEquals("C4-J-LEGACY-ZERO $what", emptyList<String>(), holders(pattern))
    }

    @Test
    fun `C4-J-LEGACY-ZERO the owner builds the one consumer, and Root's premium branch draws it inside admission and the recovery host`() {
        assertEquals("consumer construction", listOf("TopicRuntimeOwner.kt"),
            holders(Regex("""\bPremiumTopicConsumer\s*\("""), except = "PremiumTopicConsumer.kt"))
        assertEquals("route call", listOf("RootScreen.kt"), holders(Regex("""\bPremiumTopicRoute\s*\("""), except = "PremiumTopicRoute.kt"))
        assertTrue("the owner is a singleton", "@Singleton" in file("TopicRuntimeOwner.kt"))

        val root = file("RootScreen.kt")
        val calls = Regex("""\bPremiumTopicRoute\s*\(""").findAll(root).map { it.range.first }.toList()
        assertEquals("one route call", 1, calls.size)
        val gate = root.indexOf("if (!ReleaseAdmission.isOpen)")
        val armed = root.indexOf("private fun ArmedRootScreen(")
        val host = root.indexOf("IdentityRecoveryHost(", armed)
        val branch = root.indexOf("RootDestination.Premium ->", host)
        assertTrue("order gate=$gate armed=$armed host=$host branch=$branch call=${calls.single()}",
            gate in 0 until armed && armed < host && host < branch && branch < calls.single())
        val premium = root.substring(branch)
        for (part in listOf("ownedAccess.uid", "ownedAccess.authGeneration", "isActive = true"))
            assertTrue("the premium branch lacks $part", part in premium)
        // S4 CUT-CC5-3: the branch hands the route the owner's graph screen host, collected as state.
        assertTrue("the premium branch collects the owner's graph host",
            Regex("""val graphHost by owner\.graphHost\.collectAsState\(\)""").containsMatchIn(premium.substring(0, calls.single() - branch)))
        val call = premium.substring(calls.single() - branch).substringBefore("\n                )")
        assertEquals("the one route call passes that host, once", 1, Regex("""\bgraphHost = graphHost\b""").findAll(call).count())
    }

    @Test
    fun `C4-J-LEGACY-ZERO one migration per process deletes through the single fxi_cache and writes no topic seed`() {
        assertEquals("migration construction", listOf("RatesCacheCutover.kt"),
            holders(Regex("""\bRatesCacheMigration\s*\("""), except = "RatesCacheMigration.kt"))
        assertTrue("the cutover is a singleton", "@Singleton" in file("RatesCacheCutover.kt"))
        // The store-taking deletion is defined in RatesCacheMigration.kt and handed a store only by CacheService's single fxi_cache.
        assertEquals("rate key deletion over a store", listOf("CacheService.kt", "RatesCacheMigration.kt"),
            holders(Regex("""\bdeleteLegacyRateKeys\s*\(\s*[^)\s]""")))
        for (name in listOf("RatesCacheCutover.kt", "CacheService.kt")) {
            val text = file(name)
            assertFalse("$name touches the topic seed", "TopicLastKnown" in text)
            assertFalse("$name decodes v1 rates", Regex("""\bExchangeRate\b""").containsMatchIn(text))
        }
        assertFalse("the cutover clears the whole cache", "clearAllCache" in file("RatesCacheCutover.kt"))
    }

    @Test
    fun `C4-J-START-ADMISSION the owner and the migration are resolved only after admission and Firebase`() {
        val app = file("FXiApplication.kt")
        val closed = app.indexOf("if (!appOwnedServicesOpen)")
        val firebase = app.indexOf("FirebaseApp.initializeApp(this)")
        assertTrue("fixture: admission before Firebase", closed in 0 until firebase)
        for (type in listOf("TopicRuntimeOwner", "RatesCacheCutover")) {
            val field = checkNotNull(Regex("""lateinit var (\w+): Provider<$type>""").find(app)) { "$type is not a Provider field" }
                .groupValues[1]
            val reads = Regex("""\b$field\.get\(\)""").findAll(app).map { it.range.first }.toList()
            assertTrue("$type is never resolved", reads.isNotEmpty())
            assertTrue("$type is resolved before admission and Firebase: $reads", reads.all { it > firebase })
        }
    }

    /**
     * S4 CUT-CC5-4: the graph cache migration is no Application service: the owner takes it as a Provider and resolves it on
     * its host install, after the host and its time events and before the host's publication, which comes before the
     * readiness is marked and the migration launched.
     */
    @Test
    fun `C4-J-START-ADMISSION the graph migration is resolved by the owner after its host, never by the Application`() {
        assertFalse("the Application names it", "AppGraphCacheCutover" in file("FXiApplication.kt"))
        assertTrue("it is a singleton", "@Singleton" in file("AppGraphCacheCutover.kt"))
        val owner = file("TopicRuntimeOwner.kt")
        // The injected constructor names the Provider twice — its parameter and the bare method reference it hands over —
        // and does nothing else with it.
        val injected = owner.substring(owner.indexOf("    @Inject\n    constructor("), owner.indexOf("    private var attempted"))
        assertTrue("the owner's injected constructor takes the Provider", "        graphCutover: Provider<AppGraphCacheCutover>\n" in injected)
        assertTrue("and hands it over unresolved", "        graphCutover = graphCutover::get\n" in injected)
        assertEquals("and touches it nowhere else", 3, Regex("""\bgraphCutover\b""").findAll(injected).count())
        // The install function exactly: from its signature to the closing brace at its own indent, comment lines dropped.
        val start = owner.indexOf("    private suspend fun install(")
        val install = owner.substring(start, owner.indexOf("\n    }\n", start) + 6).lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val order = listOf("val host = newGraphHost(", "starter.timeEventTarget = host::onTimeEvent",
            "graphCutover?.invoke().also { graphHost.value = host }", "cutover?.markReadyAndLaunch()").map { install.indexOf(it) }
        assertTrue("host, time events, resolution then publication, then mark and launch: $order", order.all { it >= 0 } && order == order.sorted())
        assertEquals("one mark and launch", 1, Regex("""markReadyAndLaunch\(""").findAll(install).count())
        val catchStart = install.indexOf("        } catch (failure: Throwable) {")
        val catchEnd = install.indexOf("\n        }\n", catchStart)
        assertTrue("the try and its catch end before the mark and launch: $catchStart $catchEnd ${order.last()}",
            install.indexOf("val cutover = try {") in 0 until catchStart && catchEnd in catchStart until order.last())
        assertTrue("a failed install returns", Regex("""\n\s*return\n""").containsMatchIn(install.substring(catchStart, catchEnd + 1)))
    }
}
