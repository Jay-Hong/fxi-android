package com.jay.fxi.data.entitlements.control

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source/manifest tripwires for the single-owner assumption; not a multi-owner runtime guarantee. */
class ControlOwnerStructureTest {
    private val root get() = SealSourceTripwire.sourceRoot(File(checkNotNull(System.getProperty("user.dir"))), System.getProperty("fxi.seal.sourceRoot"))

    @Test fun ownerReferencesAndDelegateAreFixedAsWholeLines() {
        val actual = SealSourceTripwire.read(root).mapValues { (_, text) ->
            text.lines().map(String::trim).filter { Regex("\\b(DataStoreAccessEpochStore|accessEpochDataStore)\\b").containsMatchIn(it) }
        }.filterValues { it.isNotEmpty() }
        assertEquals(expectedReferences, actual)
        val source = File(root, "main/java/com/jay/fxi/data/entitlements/DataStoreAccessEpochStore.kt").readText()
        val lines = source.lines().map(String::trim)
        assertTrue(lines.windowed(2).contains(listOf("@Singleton", "class DataStoreAccessEpochStore internal constructor(")))
        assertTrue(lines.windowed(5).contains(listOf("@Inject", "constructor(", "@ApplicationContext context: Context,",
            "ids: EpochIdGenerator", ") : this(context.accessEpochDataStore, ids)")))
        val di = File(root, "main/java/com/jay/fxi/di/EntitlementsModule.kt").readLines().map(String::trim)
        assertTrue(di.windowed(3).contains(listOf("@Provides", "@Singleton",
            "fun provideAccessEpochStore(store: DataStoreAccessEpochStore): AccessEpochStore = store")))
    }

    @Test fun manifestProducerTasksAndTestInputsArePinned() {
        val file = File(root.parentFile, "build.gradle.kts")
        assertTrue("app build script missing: $file", file.isFile)
        val expected = """
            tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
                dependsOn("processDebugManifest", "processBenchmarkManifest", "processCiMinifiedManifest")
                inputs.files(
                    layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
                    layout.buildDirectory.file("intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml"),
                    layout.buildDirectory.file("intermediates/merged_manifests/ciMinified/processCiMinifiedManifest/AndroidManifest.xml")
                ).withPropertyName("controlOwnerMergedManifests")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            }
        """.trimIndent().lines().map(String::trim)
        val actual = file.readLines().map(String::trim)
        assertEquals("manifest producers and inputs must be configured together", 1,
            actual.windowed(expected.size).count { it == expected })
    }

    @Test fun sourceAndAllThreeMergedManifestsHaveNoSeparateProcess() {
        val sourceManifests = root.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
        assertTrue(sourceManifests.isNotEmpty())
        for (file in sourceManifests) assertFalse(file.path, Regex("android:process\\s*=").containsMatchIn(file.readText()))
        val outputs = listOf("debug/processDebugManifest", "benchmark/processBenchmarkManifest", "ciMinified/processCiMinifiedManifest")
        for (output in outputs) {
            val file = File(root.parentFile, "build/intermediates/merged_manifests/$output/AndroidManifest.xml")
            assertTrue("merged manifest missing: $file", file.isFile)
            val text = file.readText()
            assertTrue(file.path, Regex("android:name\\s*=").findAll(text).count() > 0)
            assertFalse(file.path, Regex("android:process\\s*=").containsMatchIn(text))
        }
    }

    companion object {
        private val expectedReferences = mapOf(
            "main/java/com/jay/fxi/data/entitlements/DataStoreAccessEpochStore.kt" to listOf(
                "private val Context.accessEpochDataStore: DataStore<Preferences> by preferencesDataStore(",
                "DataStoreAccessEpochStore.recoveryPreferences(ids)",
                "class DataStoreAccessEpochStore internal constructor(",
                ") : this(context.accessEpochDataStore, ids)",
                "\"read_barrier is owned by DataStoreAccessEpochStore\"",
            ),
            "main/java/com/jay/fxi/data/entitlements/control/ControlCommandTracking.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore",
                "private val collected = ReferenceQueue<DataStoreAccessEpochStore>()",
                "fun forOwner(owner: DataStoreAccessEpochStore): ControlCommandTracking {",
                "private class OwnerKey(owner: DataStoreAccessEpochStore) :",
                "WeakReference<DataStoreAccessEpochStore>(owner, collected) {",
            ),
            "main/java/com/jay/fxi/data/entitlements/control/ControlRecordStore.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH",
                "private val owner: DataStoreAccessEpochStore,",
                "val candidateBarrier = DataStoreAccessEpochStore.READ_BARRIER",
                "\"read_barrier is owned by DataStoreAccessEpochStore\"",
                "val barrier = DataStoreAccessEpochStore.READ_BARRIER",
            ),
            "main/java/com/jay/fxi/data/entitlements/control/NamespaceSettlementTransition.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.FIELD_SEPARATOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.SCOPE_SEPARATOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode",
            ),
            "main/java/com/jay/fxi/data/entitlements/control/RetiredNamespaceSettlementTransition.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode",
            ),
            "main/java/com/jay/fxi/data/entitlements/control/CurrentNullSettlementTransition.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.ENTRY_SEPARATOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH",
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode",
            ),
            "main/java/com/jay/fxi/data/entitlements/purge/PurgeJournalCodec.kt" to listOf(
                "* `DataStoreAccessEpochStore` keeps its own four-field decoder, which turns any line that is not",
                "* unreadable line, which widens, and not an empty journal. `DataStoreAccessEpochStore` draws",
            ),
            "main/java/com/jay/fxi/di/EntitlementsModule.kt" to listOf(
                "import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore",
                "fun provideAccessEpochStore(store: DataStoreAccessEpochStore): AccessEpochStore = store",
            ),
        )
    }
}
