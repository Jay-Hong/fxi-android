package com.jay.fxi

import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.local.CacheService
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Claude-owned S4 G-a contract r1 (instrumented, GA02): after the v1 graph reader and writer are removed, account deletion's
 * `CacheService.clearAllCache()` still deletes every v1 graph file name, clears `fxi_cache`, and touches nothing else.
 *
 * Why on a device: the method takes the app's Context and its `fxi_cache` DataStore; the JVM tests have no Android runtime.
 * Oracles: SettingsViewModel's two account-deletion calls (current behaviour, kept by G-a) and the v1.2.2 naming rule
 * (`6cea639:app/src/main/java/com/jay/fxi/data/local/CacheService.kt` :53-55 constants, :350-354 versioned, :359-363 legacy):
 * 1d has no period suffix, the other periods add `_<code>`. The names below are written out here, not taken from the production
 * helper, so a naming defect there cannot pass by agreeing with itself. Design: R4c/S4 g_design_codex.r2 (GA02).
 *
 * Runs against the app under test's own storage (emulator only): every file it places is removed afterwards, and the files and
 * keys it can disturb (news cache, a graph preference file, last-bank selections) are restored.
 */
class LegacyGraphCleanupContractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File get() = context.filesDir
    private val cache = CacheService(context, Json)

    private val currencies = listOf("usd-krw", "jpy-krw", "eur-krw")
    private val periods = listOf("1d", "1w", "3m", "1y")

    private val v1Names: List<String> = currencies.flatMap { c ->
        periods.flatMap { p ->
            val tail = if (p == "1d") c else "${c}_$p"
            listOf("graph_cache_v1_$tail.json", "graph_cache_$tail.json")
        }
    }

    /** Near misses and other stores this method must not touch. */
    private val controls = listOf(
        "graph_cache_v1_usd-krw_1d.json",
        "graph_cache_v1_krw-usd.json",
        "graph_cache_v1_usd-krw.json.tmp",
        "xgraph_cache_v1_usd-krw.json",
        "graph_cache_v2_usd-krw.json",
        "news_cache.json",
        "datastore/fxi_graph_preferences.preferences_pb"
    )

    private val saved = mutableMapOf<String, ByteArray?>()
    private val savedBanks = mutableMapOf<String, String?>()

    private fun file(name: String) = File(dir, name)
    private fun bytesOf(name: String) = "not json: $name".toByteArray()

    @Before fun setUp() = runBlocking {
        assertEquals("premise: 24 distinct v1 names", 24, v1Names.toSet().size)
        assertTrue("premise: no control is a v1 name", controls.none { it in v1Names })
        for (name in v1Names + controls) saved[name] = file(name).takeIf { it.exists() }?.readBytes()
        for (c in currencies) savedBanks[c] = cache.loadLastSelectedBank(c)
    }

    @After fun tearDown() = runBlocking {
        for ((name, bytes) in saved) {
            if (bytes == null) file(name).delete() else file(name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        for ((c, bank) in savedBanks) bank?.let { cache.saveLastSelectedBank(c, it) }
    }

    private fun place(names: List<String>) = names.forEach { file(it).apply { parentFile?.mkdirs() }.writeBytes(bytesOf(it)) }

    private fun assertControlsUntouched() = controls.forEach { name ->
        assertTrue("control was removed: $name", file(name).exists())
        assertArrayEquals("control was changed: $name", bytesOf(name), file(name).readBytes())
    }

    /** GA02a: every v1 name, with bytes that are not a cache, is deleted; fxi_cache is cleared; the controls stay as written. */
    @Test fun GA02a_everyV1NameIsDeletedAndNothingElse() = runBlocking {
        place(v1Names + controls)
        cache.saveLastSelectedBank("usd-krw", "ga02-bank")
        assertEquals("premise", "ga02-bank", cache.loadLastSelectedBank("usd-krw"))

        cache.clearAllCache()

        assertEquals("v1 graph files left", emptyList<String>(), v1Names.filter { file(it).exists() })
        assertNull("fxi_cache was not cleared", cache.loadLastSelectedBank("usd-krw"))
        assertControlsUntouched()
    }

    /** GA02b: with only some v1 files present, those are deleted and none of the absent ones is created. */
    @Test fun GA02b_aPartialSetIsDeletedWithoutCreatingTheRest() = runBlocking {
        v1Names.forEach { file(it).delete() }
        val present = v1Names.filterIndexed { i, _ -> i % 3 == 0 }
        place(present + controls)

        cache.clearAllCache()

        assertEquals(emptyList<String>(), v1Names.filter { file(it).exists() })
        assertControlsUntouched()
    }

    /** GA02c: with no v1 file at all, repeated calls succeed and create no graph file. */
    @Test fun GA02c_nothingToDeleteIsNotAnErrorAndCreatesNothing() = runBlocking {
        v1Names.forEach { file(it).delete() }
        val before = dir.list()?.filter { it.startsWith("graph_cache_") }.orEmpty().toSet()

        cache.clearAllCache()
        cache.clearAllCache()

        assertEquals(before, dir.list()?.filter { it.startsWith("graph_cache_") }.orEmpty().toSet())
        assertFalse(v1Names.any { file(it).exists() })
    }
}
