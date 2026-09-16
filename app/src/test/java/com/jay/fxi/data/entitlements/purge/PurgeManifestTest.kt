package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest against the code it claims to describe.
 *
 * A surface that exists and is not listed is the failure this file exists to catch: the purger
 * reports on what the manifest knows, so an unlisted store is silently nobody's obligation. The
 * source of truth here is the app's own sources, walked at test time rather than copied into a
 * list that would drift.
 */
class PurgeManifestTest {

    private val mainSources = File("src/main/java/com/jay/fxi")

    private fun sources(): List<File> = mainSources.walkTopDown().filter { it.extension == "kt" }.toList()

    /** Every DataStore the app declares is classified. */
    @Test
    fun `every preferences datastore is in the manifest`() {
        val declared = Regex("""preferencesDataStore\(\s*(?:name\s*=\s*)?"?([A-Za-z0-9_]+)"?""")
            .findAll(sources().joinToString("\n") { it.readText() })
            .map { it.groupValues[1] }
            .toMutableSet()
        // Two stores name their file through a constant; resolve those the same way a reader would.
        val constants = Regex("""(?:private )?const val ([A-Z_]+)\s*=\s*"(fxi_[a-z_]+)"""")
            .findAll(sources().joinToString("\n") { it.readText() })
            .associate { it.groupValues[1] to it.groupValues[2] }
        val names = declared.map { constants[it] ?: it }.filter { it.startsWith("fxi_") }.toSet()

        // Positive control: the walk and the pattern must actually resolve the stores we know are
        // declared, or an empty result would let this test pass while seeing nothing.
        listOf(
            "fxi_access_epoch", "fxi_push_registration_ledger", "fxi_user_intent", "fxi_free_graph",
            "fxi_free_tab", "fxi_cache", "fxi_graph_preferences", "fxi_bank_preferences"
        ).forEach { assertTrue("선언을 못 찾았다: $it (정규식이 코드와 어긋났다)", it in names) }
        val listed = PurgeManifest.TARGETS.map { it.id }.toSet()
        names.forEach { name ->
            assertTrue(
                "manifest 에 없는 DataStore: $name",
                listed.any { it == "datastore:$name" || it.startsWith("datastore:$name#") }
            )
        }
    }

    /** Every SharedPreferences file the app opens is classified. */
    @Test
    fun `every shared preferences file is in the manifest`() {
        val names = Regex("""getSharedPreferences\(\s*"?([A-Za-z0-9_]+)"?""")
            .findAll(sources().joinToString("\n") { it.readText() })
            .map { it.groupValues[1] }
            .toSet()
        val constants = Regex("""(?:private )?const val PREFS\s*=\s*"([a-z_]+)"""")
            .findAll(sources().joinToString("\n") { it.readText() })
            .map { it.groupValues[1] }
            .toSet()
        val resolved = names.filter { !it.startsWith("PREFS") }.toSet() + constants

        listOf("push_prefs", "alert_prefs", "free_snapshot").forEach {
            assertTrue("선언을 못 찾았다: $it (정규식이 코드와 어긋났다)", it in resolved)
        }
        val listed = PurgeManifest.TARGETS.map { it.id }.toSet()
        resolved.forEach { name ->
            assertTrue("manifest 에 없는 SharedPreferences: $name", "prefs:$name" in listed)
        }
    }

    /** Ids are unique, and every target says who deletes it. */
    @Test
    fun `targets are unique and attributed`() {
        val ids = PurgeManifest.TARGETS.map { it.id }
        assertEquals("중복 id", ids.size, ids.toSet().size)
        PurgeManifest.TARGETS.forEach {
            assertTrue("소유자 없음: ${it.id}", it.owner.isNotBlank())
            assertTrue("설명 없음: ${it.id}", it.note.isNotBlank())
        }
    }

    /**
     * The control plane is out of every axis.
     *
     * `ANDROID_V2_PLAN.md` §7 S1 says the journal, the registration ledger and the deletion state
     * are what protect the work; a purge that could reach them would erase its own instructions.
     */
    @Test
    fun `control plane and device state belong to no axis`() {
        PurgeManifest.TARGETS.filter { it.classification == PurgeClassification.NOT_USER_DATA }
            .forEach { assertEquals("축이 붙은 control plane: ${it.id}", emptySet<PurgeScope>(), it.scopes) }

        listOf(
            "datastore:fxi_access_epoch",
            "datastore:fxi_push_registration_ledger",
            "prefs:alert_prefs",
            "prefs:free_snapshot"
        ).forEach {
            assertEquals(
                "보존 대상이 아님: $it",
                PurgeClassification.NOT_USER_DATA,
                PurgeManifest.byId(it)?.classification
            )
        }
    }

    /**
     * Nothing is classified as this purger's own work yet.
     *
     * The topic and graph runtimes that will write server-derived caches are not wired
     * (동결 후 13번), so a `DERIVED_HERE` entry appearing here means either a new runtime landed —
     * and this test should be updated with it — or a legacy surface was quietly reclassified.
     */
    @Test
    fun `no derived target exists before the runtimes land`() {
        assertEquals(
            emptyList<String>(),
            PurgeManifest.TARGETS.filter { it.classification == PurgeClassification.DERIVED_HERE }.map { it.id }
        )
    }

    /** The legacy surfaces keep the owners §9.1 gave them. */
    @Test
    fun `legacy surfaces keep their cutover owners`() {
        assertEquals("S3", PurgeManifest.byId("datastore:fxi_cache#rates")?.owner)
        assertEquals("S7", PurgeManifest.byId("datastore:fxi_cache#last_bank")?.owner)
        assertEquals("S4", PurgeManifest.byId("datastore:fxi_graph_preferences")?.owner)
        assertEquals("S4", PurgeManifest.byId("file:graph_cache")?.owner)
        assertEquals("S10", PurgeManifest.byId("file:news_cache")?.owner)
    }
}
