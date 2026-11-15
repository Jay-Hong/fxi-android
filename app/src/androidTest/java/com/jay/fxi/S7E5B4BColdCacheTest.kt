package com.jay.fxi

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.local.CacheService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * S7 E5b4b (`s7e5b4_api_agreed.r1.md` §2.6): the legacy deletion removes the `last_bank_` keys of `fxi_cache` and nothing else.
 *
 * `fxi_cache` has no writer for arbitrary keys, so its contents are prepared as file bytes before anything in the process opens
 * its DataStore — one method alone in an instrumentation invocation of its own, named by the `fxiColdStore` argument, as in
 * [UserIntentColdStoreTest]; anywhere else it is skipped. The debug build keeps D24 closed, so no app-owned service opens the
 * cache first.
 */
class S7E5B4BColdCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val datastore = File(context.filesDir, "datastore")

    /** Builds preferences bytes through a DataStore of its own over a scratch file, and reads one back the same way. */
    private fun <T> scratch(file: File, block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T {
        val job = SupervisorJob()
        try {
            return runBlocking { block(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })) }
        } finally {
            runBlocking { job.cancelAndJoin() }
        }
    }

    @Test
    fun deleteLastBankKeysRemovesThePrefixAndNothingElse() {
        val method = "deleteLastBankKeysRemovesThePrefixAndNothingElse"
        assumeTrue(InstrumentationRegistry.getArguments().getString("fxiColdStore") == method)
        assertFalse("premise: D24 OFF", shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE))
        val cache = File(datastore, "fxi_cache.preferences_pb")
        val original = if (cache.exists()) cache.readBytes() else null
        val seed = File.createTempFile("cache_seed", ".preferences_pb", context.cacheDir)
        val check = File.createTempFile("cache_check", ".preferences_pb", context.cacheDir)
        try {
            seed.delete()
            scratch(seed) { store ->
                store.edit { p ->
                    p[stringPreferencesKey("last_bank_usd-krw")] = "hana"
                    p[stringPreferencesKey("last_bank_jpy-krw")] = "kb"
                    p[stringPreferencesKey("last_bank_")] = "x"
                    p[stringPreferencesKey("last_bank_future_pair_9")] = "nh"
                    p[longPreferencesKey("last_bank_count")] = 3L
                    p[booleanPreferencesKey("last_bank_flag")] = true
                    p[stringPreferencesKey("last_bank")] = "kept: no underscore"
                    p[stringPreferencesKey("xlast_bank_usd-krw")] = "kept: not the prefix"
                    p[stringPreferencesKey("rates")] = "kept: S3's"
                    p[longPreferencesKey("rates_timestamp")] = 7L
                }
            }
            datastore.mkdirs()
            seed.copyTo(cache, overwrite = true)

            runBlocking { CacheService(context, Json).deleteLastBankKeys() }

            cache.copyTo(check, overwrite = true)
            val left = scratch(check) { it.data.first().asMap().mapKeys { (k, _) -> k.name } }
            val kept = mapOf<String, Any>("last_bank" to "kept: no underscore", "xlast_bank_usd-krw" to "kept: not the prefix",
                "rates" to "kept: S3's", "rates_timestamp" to 7L)
            assertEquals("남은 키·값", kept, left)
            assertEquals("남은 값의 타입", kept.mapValues { it.value.javaClass }, left.mapValues { it.value.javaClass })
        } finally {
            if (original == null) cache.delete() else cache.writeBytes(original)
            seed.delete()
            check.delete()
        }
    }
}
