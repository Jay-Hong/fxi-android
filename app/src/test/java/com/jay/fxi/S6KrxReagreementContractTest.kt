package com.jay.fxi

import com.jay.fxi.data.remote.*
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.local.*
import com.jay.fxi.di.ServerEndpoints
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/** Source expectations are frozen HEAD inventory plus contracts.r2 §4.1/§5, never candidate-learned. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxReagreementContractTest {
    @Test fun d20_sourceAndPublicApiBoundariesAreExact() {
        val cases = listOf("source","protected337","protected349","TSC-installer","Factory-installer","bootstrap-callers","new-file-bypass","allowed-file-bypass","provider-OFF","typed-sync","shared-switch-identity","Factory-reject-replacement","compile-constructors/trailing-lambda")
        for (sub in s64Cases("D20",cases)) {
            val inventory = resource("source_inventory.json").jsonObject
            val expected = inventory.getValue("source_files").jsonObject
            val roots = inventory.getValue("roots").jsonArray.map { it.jsonPrimitive.content }
            val actual = roots.flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }.associate { it.invariantSeparatorsPath to it.readBytes() }
            assertEquals("precondition(D20/$sub): five populated archive roots",listOf(350,3,7,1,1),inventory.getValue("root_counts").jsonArray.map { it.jsonPrimitive.int })
            val code = actual.mapValues { KrxSessionSourceContract.strip(it.value.toString(Charsets.UTF_8)) }
            val base = "src/main/java/com/jay/fxi/"; val rtPath = base + "data/remote/TopicRuntime.kt"
            val rt = code.getValue(rtPath); val tsc = code.getValue(base+"data/remote/TopicSessionCoordinator.kt"); val tbo = code.getValue(base+"data/remote/TopicBootstrapOrder.kt")
            fun count(pattern: String,text: String) = Regex(pattern).findAll(text).count()
            // Receiver-aware: declarations are excluded; unqualified/this calls are Factory calls only in RT.
            fun calls(sources: Map<String,String>): Map<String,Int> {
                val result = linkedMapOf<String,Int>()
                val pattern = Regex("""(?:(\b[A-Za-z_]\w*)\s*\.)?\binstallKrxDesiredProducer\s*\(""")
                for ((path,text) in sources) for (m in pattern.findAll(text)) {
                    if (Regex("""\bfun\s*$""").containsMatchIn(text.substring(0,m.range.first))) continue
                    val receiver = m.groupValues[1].ifEmpty { "<implicit>" }
                    val key = "$path::$receiver"; result[key] = (result[key] ?: 0) + 1
                }
                return result
            }
            when (sub) {
                "source" -> assertEquals("contract(D20): source/exact 362 paths",expected.keys,actual.keys)
                "protected337", "protected349" -> for ((path,hash) in inventory.getValue(sub).jsonObject.getValue("protected").jsonObject)
                    assertEquals("contract(D20): $sub/$path/raw HEAD bytes",hash.jsonPrimitive.content,actual[path]?.let(::sha))
                "TSC-installer" -> {
                    assertEquals("contract(D20): TSC-installer/receiver sites",mapOf("$rtPath::session" to 1),calls(code).filterKeys { it.endsWith("::session") })
                    assertEquals("contract(D20): TSC-installer/create call once",1,count("""session\.installKrxDesiredProducer\s*\(""",KrxSessionSourceContract.function(rt,"create")))
                    assertTrue("contract(D20): TSC-installer/exact signature",Regex("""internal\s+fun\s+installKrxDesiredProducer\s*\(\s*switch\s*:\s*KrxContestSwitch\s*\)""").containsMatchIn(tsc))
                }
                "Factory-installer" -> {
                    assertEquals("contract(D20): Factory-installer/receiver sites",mapOf("$rtPath::session" to 1,"$rtPath::<implicit>" to 1),calls(code))
                    val di = Regex("""@Inject\s+constructor\s*\([^)]*sharedSwitch\s*:\s*KrxContestSwitch\s*,?\s*\)\s*:\s*this\s*\([^)]*\)\s*\{\s*installKrxDesiredProducer\(sharedSwitch\)\s*}""")
                    assertTrue("contract(D20): Factory-installer/new DI sole settings caller",di.containsMatchIn(rt))
                    assertTrue("contract(D20): Factory-installer/exact signature",Regex("""internal\s+fun\s+installKrxDesiredProducer\s*\(\s*switch\s*:\s*KrxContestSwitch\s*\)""").containsMatchIn(rt))
                }
                "shared-switch-identity" -> {
                    assertFalse("contract(D20): shared-switch-identity/new switch instances forbidden",Regex("""\bKrxContestSwitch\s*\(""").containsMatchIn(rt+tsc))
                    assertTrue("contract(D20): shared-switch-identity/DI passes exact object",Regex("""\binstallKrxDesiredProducer\s*\(\s*sharedSwitch\s*\)""").containsMatchIn(rt))
                }
                "Factory-reject-replacement" -> runTest {
                    var failure: Throwable? = null; var invoked = false
                    val w = S64HeadWorld(this,"D20/Factory-reject-replacement",configure = { _,factory ->
                        factory.installKrxDesiredProducer(KrxContestSwitch.OFF)
                        failure = runCatching { factory.installKrxDesiredProducer(KrxContestSwitch(false)) }.exceptionOrNull()
                        invoked = true
                    })
                    try {
                        assertTrue("precondition(D20/Factory-reject-replacement): real pre-create settings invoked",invoked)
                        assertTrue("contract(D20): Factory-reject-replacement/equal-value different object rejected",failure is IllegalStateException || failure is IllegalArgumentException)
                    } finally { w.close() }
                }
                "typed-sync" -> assertTrue("contract(D20): typed-sync/private same-read signature",Regex("""private\s+fun\s+syncRuntimeKrxDesired\s*\(\s*stamp\s*:\s*KrxSessionStamp\?\s*,\s*use\s*:\s*KrxUseLifetime\?\s*\)""").containsMatchIn(tsc))
                "bootstrap-callers" -> {
                    assertEquals("contract(D20): bootstrap-callers/exact total",2,count("""TopicBootstrapOrder\.bootstrapShownBy\s*\(""",tsc))
                    for (name in listOf("retryTopicsOnLoop","issueOwedBootstraps")) assertEquals("contract(D20): bootstrap-callers/$name",1,count("""TopicBootstrapOrder\.bootstrapShownBy\s*\(""",KrxSessionSourceContract.function(tsc,name)))
                    assertEquals("contract(D20): bootstrap-callers/TBO K once",1,count("""\bKRX_FUTURES\b""",tbo))
                }
                "new-file-bypass" -> {
                    val altered = code + (base+"data/remote/Bypass.kt" to "fun x() { factory.installKrxDesiredProducer(other) }")
                    assertNotEquals("contract(D20): new-file-bypass/receiver scan detects",calls(code),calls(altered))
                    assertNotEquals("contract(D20): new-file-bypass/path scan detects",expected.keys,actual.keys+"src/main/java/com/jay/fxi/data/remote/Bypass.kt")
                }
                "allowed-file-bypass" -> assertNotEquals("contract(D20): allowed-file-bypass/receiver scan detects",calls(code),calls(code + (rtPath to rt+"\nfun bad() { session.installKrxDesiredProducer(other) }")))
                "provider-OFF" -> assertTrue("contract(D20): provider-OFF/singleton",Regex("""fun\s+provideKrxContestSwitch\(\)\s*:\s*KrxContestSwitch\s*=\s*KrxContestSwitch\.OFF""").containsMatchIn(code.getValue(base+"di/EntitlementsModule.kt")))
                else -> {
                    for ((name,headParameters) in inventory.getValue("head_constructor_parameters").jsonObject) {
                        val text = code.getValue(base+"data/remote/$name")
                        val actualParameters = constructorParameters(text).filterNot { it.contains("sharedSwitch:KrxContestSwitch") }
                        assertEquals("contract(D20): compile-constructors/trailing-lambda/$name exact preserved parameters and defaults",headParameters.jsonArray.map { it.jsonPrimitive.content },actualParameters)
                    }
                }
            }
        }
    }
    private fun constructorParameters(text: String): List<String> = Regex("""\bconstructor\s*\(|\bclass\s+TopicSessionCoordinator\s*\(""").findAll(text).map { head ->
        var depth = 1; var end = head.range.last + 1; val start = end
        while (depth > 0 && end < text.length) { when (text[end++]) { '(' -> depth++; ')' -> depth-- } }
        check(depth == 0) { "precondition(D20/compile-constructors): constructor closes" }
        text.substring(start,end-1).replace(Regex("""\s+"""),"").trimEnd(',')
    }.toList()
    @Suppress("unused")
    private fun compileFactoryConstructors(tokenProvider: AuthTokenProvider, orders: AccessOrderSequence,
        fences: AuthFenceStream, recoveries: AuthCredentialRecoveryStream, coordinator: PremiumAccessCoordinator,
        bootstrapService: TopicSnapshotBootstrapService, decoder: TopicFrameDecoder, tabs: FreeTabStore,
        json: Json, lastKnown: TopicLastKnownStore, authority: KrxSendReadAuthority, endpoints: ServerEndpoints,
        sharedSwitch: KrxContestSwitch): Pair<TopicRuntimeFactory, TopicRuntimeFactory> =
        TopicRuntimeFactory(tokenProvider, orders, fences, recoveries, coordinator, bootstrapService, decoder, tabs, json,
            lastKnown, authority, endpoints) to
        TopicRuntimeFactory(tokenProvider, orders, fences, recoveries, coordinator, bootstrapService, decoder, tabs, json,
            lastKnown, authority, endpoints, sharedSwitch)

    @Suppress("unused")
    private fun compileTscTrailingLambda(scope: kotlinx.coroutines.CoroutineScope, clock: TopicCommandClock,
        connect: (Long) -> TopicTransport, credentials: TopicCommandCredentials, orders: AccessOrderSequence,
        authority: TopicUseAuthority, bootstrap: suspend (AuthIdentityFence, String, () -> Boolean) -> TopicSnapshotOutcome,
        store: com.jay.fxi.domain.model.TopicSubscriptionStateStore,
        encode: (com.jay.fxi.data.remote.dto.TopicSubscribeRequest) -> String, newId: () -> String,
        jitter: () -> Double, liveIdentity: () -> AuthIdentityFence?): TopicSessionCoordinator =
        TopicSessionCoordinator(scope, clock, connect, credentials, orders, authority, bootstrap, store, encode, newId, jitter, liveIdentity) { 1_000_000L }

    companion object {
        internal fun resource(name: String): JsonElement {
            val stream = S6KrxReagreementContractTest::class.java.classLoader.getResourceAsStream("krx/s64a/$name")
            assertNotNull("precondition(D20/resource): krx/s64a/$name exists", stream)
            return stream!!.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
        }
        internal fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
