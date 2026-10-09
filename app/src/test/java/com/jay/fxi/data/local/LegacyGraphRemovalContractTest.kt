package com.jay.fxi.data.local

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.purge.PurgeClassification
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 G-a contract r1 (JVM, GA01): the unreachable v1 graph consumer chain is gone from production sources, while
 * the two §9.1 obligations and the account-deletion cleanup that still removes v1 graph files stay.
 *
 * Oracles: ANDROID_V2_PLAN.md :398 (I1, no legacy `/api/graph` consumption), :1358-1359 and §9.1 :1723-1724 (the v1 files are
 * deleted, and the preferences reset, by the S4 cutover - that deletion is F2's, not this unit's). Design: R4c/S4
 * g_design_codex.r1, g_review_claude.r1, agreed with its file list and rows in g_design_codex.r2.
 *
 * G-a removes code that no running path reaches (after C4 `CurrencyTabContent` has no caller, and `GraphViewModel` and
 * `GraphPreferenceManager` were reachable only through it). It is not the S4 cutover and not I1 completion: nothing is deleted on
 * a device here, and `CacheService.clearAllCache()` keeps deleting v1 graph files on account deletion (GA02, instrumented).
 *
 * Scope of the scan: Kotlin sources under app/src/main/java. Comments count: a sentence that names a removed class is a
 * dangling reference too.
 */
class LegacyGraphRemovalContractTest {

    private val main = File("src/main/java")

    private fun sources(): Map<String, String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).invariantSeparatorsPath to it.readText() }

    private val cachePath = "com/jay/fxi/data/local/CacheService.kt"
    private val journalPath = "com/jay/fxi/data/local/LocalMigrationJournal.kt"
    private val manifestPath = "com/jay/fxi/data/entitlements/purge/PurgeManifest.kt"
    private val migrationPath = "com/jay/fxi/data/local/GraphCacheMigration.kt"

    /** GA01a: no v1 endpoint, repository, view model, DTO, preference store, cache model, cache reader/writer or promotion. */
    @Test fun GA01a_noV1GraphConsumerRemains() {
        val all = sources()
        assertTrue("premise: the scan sees the production sources", cachePath in all && all.size > 100)
        val forbidden = listOf(
            Regex("""["/]api/graph/\{"""),
            Regex("""\bgetGraph\s*\("""),
            Regex("""\bGraphViewModel\b"""),
            Regex("""\bGraphPreferenceManager\b"""),
            Regex("""\bExchangeRateRepository(Impl)?\b"""),
            Regex("""\bGraphResponse\b"""),
            Regex("""\bGraphDataResult\b"""),
            Regex("""\bPeriodGraphCacheEntry\b"""),
            Regex("""\b(Mutable)?(Period)?GraphCache\b"""),
            Regex("""\b(Mutable)?GraphSourceData\b"""),
            Regex("""\b(save|savePeriod|saveAll|load|loadPeriod|loadAll)GraphData\b"""),
            Regex("""\b(hasValidCache|lastBucketTimestamp|promoteLegacyCacheIfNeeded|resolveReadableCacheFile)\b"""),
            Regex("""preferencesDataStore\(\s*name\s*=\s*"fxi_graph_preferences"""")
        )
        val hits = all.flatMap { (path, text) ->
            forbidden.filter { it.containsMatchIn(text) }.map { "$path: ${it.pattern}" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    /**
     * GA01b: v1 graph file names survive only where they are deleted (CacheService and, since S4 RT07, the GraphCacheMigration
     * sweep, which S4 CUT-CC5-4 launches) or named as obligations (the migration journal and the purge manifest). In CacheService every
     * use of the two name helpers deletes the file it names.
     */
    @Test fun GA01b_v1GraphFileNamesSurviveOnlyForDeletionAndObligations() {
        val all = sources()
        assertEquals(
            setOf(cachePath, journalPath, manifestPath, migrationPath),
            all.filterValues { it.contains("graph_cache_") }.keys
        )
        val cache = all.getValue(cachePath)
        val uses = Regex("""(?<!fun )\b(graphCacheFile|legacyGraphCacheFile)\(([^()]*)\)(\.\w+\(\))?""").findAll(cache).toList()
        assertEquals("premise: both helpers are still used by the account-deletion cleanup",
            setOf("graphCacheFile", "legacyGraphCacheFile"), uses.map { it.groupValues[1] }.toSet())
        assertEquals("every use deletes", uses.map { ".delete()" }, uses.map { it.groupValues[3] })
    }

    /** GA01c: the two §9.1 obligations stay as they were, and the manifest no longer claims a read path that promotes. */
    @Test fun GA01c_theTwoLegacyObligationsStay() {
        val journal = LegacyMigrationTarget.entries.associate { it.name to it.key }
        assertEquals("graph_cache_files", journal["GRAPH_CACHE_FILES"])
        assertEquals("graph_preferences", journal["GRAPH_PREFERENCES"])
        for (id in listOf("file:graph_cache", "datastore:fxi_graph_preferences")) {
            val target = PurgeManifest.TARGETS.single { it.id == id }
            assertEquals(id, PurgeClassification.CUTOVER_OWNED, target.classification)
            assertEquals(id, setOf(PurgeScope.USER), target.scopes)
            assertEquals(id, "S4", target.owner)
        }
        assertEquals(
            "`graph_cache_v1_*.json` and the legacy names; no reader or writer remains — the S4 cutover migration deletes " +
                "them once the graph runtime and screen host are ready (§9.1); account-deletion cleanup also removes them",
            PurgeManifest.TARGETS.single { it.id == "file:graph_cache" }.note
        )
        assertEquals(
            "v1 series ids; reset by the Graph V2 cutover (§9.1)",
            PurgeManifest.TARGETS.single { it.id == "datastore:fxi_graph_preferences" }.note
        )
    }

    /**
     * GA01d (S4 D8, `cut_d8_agreed.r1.md`): the legacy socket service, its frame types, the v1 graph view and toggles and the
     * v1 bucket model are gone from every source set's Kotlin and Java, and so is the legacy bucket field name. Three exceptions
     * only, never a whole file: the four comments citing the iOS service by its `.swift` file, the two legacy frames the topic
     * decoder test feeds in to show they are refused, and this row's own quoted data. The removed files are absent and the
     * socket message file declares the message type alone. A static check is a regression guard; later boundary changes are
     * for diff review.
     */
    @Test fun GA01d_theLegacySocketPathAndTheV1GraphViewAreGone() {
        val names = listOf(
            "WebSocketService", "WebSocketPing", "WebSocketRatesMessage", "IndicesPayload", "DxyLiveTick", "WebSocketGraphBucket",
            "WebSocketGraphBuckets", "RateGraphView", "DxyToggleButton", "SourceToggleRow", "GraphBucket", "GraphPoint", "toGraphPoint"
        )
        val field = "graph" + "_buckets"
        val root = File("src")
        val sets = listOf("main", "debug", "test", "androidTest", "benchmark")
        val files = sets.flatMap { set ->
            File(root, set).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val self = "test/java/com/jay/fxi/data/local/LegacyGraphRemovalContractTest.kt"
        val decoderTest = "test/java/com/jay/fxi/data/remote/TopicFrameDecoderTest.kt"
        assertTrue("premise: the scan sees every source set holding code",
            self in files && decoderTest in files && listOf("main/", "test/", "androidTest/", "benchmark/").all { p -> files.keys.any { it.startsWith(p) } })
        val swiftCitations = mapOf(
            "main/java/com/jay/fxi/data/remote/TopicSessionCoordinator.kt" to 3,
            "main/java/com/jay/fxi/domain/model/TopicRequestPolicy.kt" to 1
        )
        val legacyFrame = "\"" + field + "\":{}"
        val swift = names[0] + ".swift"
        assertEquals("premise: the decoder test refuses two legacy frames", 2, Regex(Regex.escape(legacyFrame)).findAll(files.getValue(decoderTest)).count())
        val hits = files.flatMap { (path, original) ->
            var text = original
            swiftCitations[path]?.let { n ->
                assertEquals("$path: the iOS citations", n, Regex(Regex.escape(swift)).findAll(text).count())
                text = text.replace(swift, "")
            }
            if (path == decoderTest) text = text.replace(legacyFrame, "")
            if (path == self) names.forEach {
                assertEquals("this row names $it once, as data", 1, Regex(Regex.escape("\"$it\"")).findAll(text).count())
                text = text.replace("\"$it\"", "")
            }
            // ASCII boundaries: a name followed by Korean text in a comment still counts.
            names.filter { Regex("""(?<![A-Za-z0-9_])$it(?![A-Za-z0-9_])""").containsMatchIn(text) }.map { "$path: $it" } +
                listOfNotNull("$path: $field".takeIf { field in text })
        }
        assertEquals(emptyList<String>(), hits)
        // No file of any removed name, nor the old service admission test, comes back in any source set, in Kotlin or Java.
        val removedNames = names.toSet() + (names.first() + "AdmissionTest")
        assertEquals("the removed files are absent", emptyList<String>(),
            files.keys.filter { File(it).nameWithoutExtension in removedNames })
        // Column-0 lines of the socket message file, past its package line, imports, comments and closing braces.
        val message = files.getValue("main/java/com/jay/fxi/data/remote/dto/WebSocketMessage.kt")
        val topLevel = message.lines().filter { line ->
            line.isNotBlank() && !line[0].isWhitespace() && !line.startsWith("package ") && !line.startsWith("import ") &&
                !line.startsWith("/") && !line.startsWith("*") && line != "}"
        }
        assertEquals("the socket message file declares the message type alone", listOf("object WebSocketMessageType {"), topLevel)
    }
}
