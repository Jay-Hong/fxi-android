package com.jay.fxi.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c C4 host artifact tool (R4c/C4/design_codex.r3.md; spec R4c/C4/contract.r1/host_spec.md). It writes the files
 * the host device procedures place, under `app/build/c4-host/`, through DataStore's own serializer, and never rewrites the C0
 * resource or its manifest. When `app/build/c4-host/readback/` holds files pulled from the device, it decodes each into a JSON
 * file beside it. These are test artifacts, not C0 evidence. The implementation thread reads but does not edit this file.
 */
class C4HostArtifactsTest {

    private val out = File("build/c4-host")
    private val raw: ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/v122/fxi_cache.v122.preferences_pb")).readBytes()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Writes [file] through a DataStore that starts from [seed] (or empty) and applies [edit], then closes it. A placeholder is
     * written and removed first, so the file is serialized even when [edit] alone would leave the data unchanged.
     */
    private fun write(file: File, seed: ByteArray?, edit: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) = runBlocking {
        file.delete()
        if (seed != null) file.writeBytes(seed)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val placeholder = stringPreferencesKey("c4_host_placeholder")
        store.edit { it[placeholder] = "x" }
        store.edit { it.remove(placeholder); edit(it) }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    /** Reads [file] through a DataStore over a copy, so the pulled file itself is never opened for writing. */
    private fun read(file: File): Map<String, Any> = runBlocking {
        val copy = File.createTempFile("c4-read", ".preferences_pb").also { file.copyTo(it, overwrite = true) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            PreferenceDataStoreFactory.create(scope = scope) { copy }.data.first().asMap().mapKeys { it.key.name }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            copy.delete()
        }
    }

    private fun describe(values: Map<String, Any>) = JsonObject(values.toSortedMap().mapValues { (_, v) ->
        JsonObject(mapOf(
            "type" to JsonPrimitive(v::class.simpleName),
            "value" to when (v) {
                is Set<*> -> JsonArray(v.map { it.toString() }.sorted().map { JsonPrimitive(it) })
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        ))
    })

    @Test
    fun `C4-D artifacts are written through DataStore and read back as specified`() {
        out.mkdirs()
        val journalKey = stringPreferencesKey(LegacyMigrationTarget.RATES_CACHE.key)

        val cache = File(out, "cache_c0.preferences_pb").also { it.writeBytes(raw) }
        assertEquals("the C0 copy is the C0 input", "d91a05a9f16aaf63f35b8474859315cd03d6443e7aeaa77b8bb20ebc5ed78bb2", sha(cache.readBytes()))

        val deleted = File(out, "cache_c0_rate_keys_deleted.preferences_pb")
        write(deleted, raw) { it.remove(LegacyRateCacheKeys.RATES); it.remove(LegacyRateCacheKeys.RATES_TIMESTAMP) }
        assertEquals("the derived cache", mapOf<String, Any>("last_bank_usd-krw" to "kb", "last_bank_jpy-krw" to "hana"), read(deleted))

        val empty = File(out, "journal_without_rates_cache.preferences_pb")
        write(empty, null) { it.remove(journalKey) }
        assertTrue("the journal without rates_cache", read(empty).isEmpty())

        val cutover = File(out, "journal_consumer_cutover.preferences_pb")
        write(cutover, null) { it[journalKey] = LegacyMigrationStage.CONSUMER_CUTOVER.name }
        assertEquals("the journal at CONSUMER_CUTOVER", mapOf<String, Any>("rates_cache" to "CONSUMER_CUTOVER"), read(cutover))

        val files = listOf(cache, deleted, empty, cutover)
        File(out, "manifest.json").writeText(JsonObject(files.associate { f ->
            f.name to JsonObject(mapOf("sha256" to JsonPrimitive(sha(f.readBytes())), "content" to describe(read(f))))
        }).toString())

        val readback = File(out, "readback")
        readback.listFiles { f -> f.name.endsWith(".preferences_pb") }.orEmpty().forEach { pulled ->
            File(readback, pulled.name.removeSuffix(".preferences_pb") + ".json").writeText(JsonObject(mapOf(
                "sha256" to JsonPrimitive(sha(pulled.readBytes())),
                "content" to describe(read(pulled))
            )).toString())
        }
    }
}
