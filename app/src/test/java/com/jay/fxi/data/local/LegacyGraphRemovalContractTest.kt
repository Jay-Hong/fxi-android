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
     * GA01b: v1 graph file names survive only where they are deleted (CacheService) or named as obligations (the migration
     * journal and the purge manifest). In CacheService every use of the two name helpers deletes the file it names.
     */
    @Test fun GA01b_v1GraphFileNamesSurviveOnlyForDeletionAndObligations() {
        val all = sources()
        assertEquals(setOf(cachePath, journalPath, manifestPath), all.filterValues { it.contains("graph_cache_") }.keys)
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
            "`graph_cache_v1_*.json` and the legacy names; no reader or writer remains — only account-deletion cleanup removes " +
                "them until the S4 cutover migration (§9.1)",
            PurgeManifest.TARGETS.single { it.id == "file:graph_cache" }.note
        )
        assertEquals(
            "v1 series ids; reset by the Graph V2 cutover (§9.1)",
            PurgeManifest.TARGETS.single { it.id == "datastore:fxi_graph_preferences" }.note
        )
    }
}
