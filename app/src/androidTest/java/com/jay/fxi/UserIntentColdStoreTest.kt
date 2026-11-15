package com.jay.fxi

import android.os.Process
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.local.DataStoreLastSelectedBankStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bank store in a process where nothing has opened its files yet (S7 E5b4a, G09 and G10).
 *
 * The rest of the device suite shares one process and one DataStore with its cache, which hides
 * two things: whether process-start readiness really reads the file, and whether the store touches
 * files it has no business with. Each method here runs alone, in an instrumentation invocation of
 * its own, filtered to that one method and named by the `fxiColdStore` argument; anywhere else it
 * is skipped. The debug build keeps D24 closed, so `FXiApplication` returns before any app-owned
 * service opens a store, and nothing here opens the user-intent file, the cache or the journal
 * before the method does.
 *
 * Neither method uses the shared accessor's clear: that would open the very DataStore whose first
 * opening is under test.
 */
class UserIntentColdStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val datastore = File(context.filesDir, "datastore")

    private fun only(method: String) {
        assumeTrue(
            "별도 invocation 전용 — fxiColdStore=$method 일 때만 돈다",
            InstrumentationRegistry.getArguments().getString("fxiColdStore") == method
        )
        assertFalse(
            "premise: D24 OFF 라 앱 소유 작업이 store 를 열지 않는다",
            shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)
        )
        // The run record ties the method to its own process (s7e5b4a_gaphunt_plan.codex.r1.md §3.1).
        Log.i("E5b4aCold", "pid=${Process.myPid()} method=$method")
    }

    /**
     * A file the serializer cannot parse makes readiness fail, and readiness leaves it as it was.
     *
     * The control reads a byte copy through a DataStore of its own, so the bytes are known to be
     * unreadable before the store under test sees them.
     */
    @Test
    fun verifyReadablePropagatesAnUnreadableColdStore() = runBlocking {
        only("verifyReadablePropagatesAnUnreadableColdStore")
        val target = File(datastore, "fxi_user_intent.preferences_pb")
        var primary: Throwable? = null
        try {
            datastore.mkdirs()
            target.writeBytes(UNREADABLE)

            val copy = File.createTempFile("unreadable_copy", ".preferences_pb", context.cacheDir)
            UNREADABLE.let(copy::writeBytes)
            val job = SupervisorJob()
            try {
                val control = runCatching {
                    PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { copy }).data.first()
                }
                assertEquals("premise: 이 bytes 는 serializer 가 거부한다", CorruptionException::class.java, control.exceptionOrNull()?.javaClass)
            } finally {
                job.cancelAndJoin()
                copy.delete()
            }

            val outcome = runCatching { DataStoreLastSelectedBankStore(context).verifyReadable() }
            assertEquals(
                "읽을 수 없는 파일에서 verifyReadable 이 ${if (outcome.isSuccess) "성공했다" else "다른 실패를 냈다: ${outcome.exceptionOrNull()}"}",
                CorruptionException::class.java, outcome.exceptionOrNull()?.javaClass
            )
            assertArrayEquals("verifyReadable 이 파일을 바꿨다", UNREADABLE, target.readBytes())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            cleanUp(primary, listOf(target.name to { removeFile(target) }))
        }
    }

    /**
     * Creating and using the store leaves the legacy cache and the migration journal byte for byte.
     *
     * Sentinels stand in for both files, written as opaque bytes rather than through their own
     * stores — the store under test must not open them at all — and the originals are put back.
     */
    @Test
    fun creatingAndUsingTheDormantStoreLeavesLegacyAndJournalUntouched() = runBlocking {
        only("creatingAndUsingTheDormantStoreLeavesLegacyAndJournalUntouched")
        val guarded = listOf("fxi_cache.preferences_pb", "fxi_migration_journal.preferences_pb").map { File(datastore, it) }
        val originals = guarded.associateWith { if (it.exists()) it.readBytes() else null }
        val userIntent = File(datastore, "fxi_user_intent.preferences_pb")
        var primary: Throwable? = null
        try {
            datastore.mkdirs()
            userIntent.delete()
            guarded.forEachIndexed { i, file -> file.writeBytes(SENTINELS[i]) }
            fun untouched(step: String) = guarded.forEachIndexed { i, file ->
                assertArrayEquals("$step 뒤 ${file.name} 가 바뀌었다", SENTINELS[i], if (file.exists()) file.readBytes() else null)
            }

            val store = DataStoreLastSelectedBankStore(context)
            untouched("생성")
            store.remember("u1", "hana")
            untouched("remember")
            assertEquals("hana", store.read("u1"))
            untouched("read")
            assertNull(store.read("u2"))
            untouched("타 UID read")
            store.verifyReadable()
            untouched("verifyReadable")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            cleanUp(primary, guarded.map { file -> file.name to { restoreFile(file, originals.getValue(file)) } } +
                (userIntent.name to { removeFile(userIntent) }))
        }
    }

    /** Puts [file] back to its bytes from before the test, or removes it if it did not exist; the result is checked. */
    private fun restoreFile(file: File, original: ByteArray?) {
        if (original == null) {
            removeFile(file)
        } else {
            file.writeBytes(original)
            check(file.readBytes().contentEquals(original)) { "${file.name} 의 복원 bytes 가 원래와 다르다" }
        }
    }

    private fun removeFile(file: File) {
        if (file.exists()) check(file.delete() && !file.exists()) { "${file.name} 를 지우지 못했다" }
    }

    /**
     * Runs every cleanup step even when an earlier one fails: one file's failed cleanup does not skip the rest
     * (`e5b4a_gaphunt.claude.r3.md`, codex plan r2 §3). The failures are reported together. When the test itself failed,
     * they are added to that failure as suppressed instead of hiding it (codex commit r1 T1).
     */
    private fun cleanUp(primary: Throwable?, steps: List<Pair<String, () -> Unit>>) {
        val failures = steps.mapNotNull { (name, step) -> runCatching(step).exceptionOrNull()?.let { AssertionError("정리 실패: $name", it) } }
        if (failures.isEmpty()) return
        val cleanup = AssertionError("cold 정리 실패 ${failures.size}건: " + failures.joinToString { it.message.orEmpty() })
        failures.forEach(cleanup::addSuppressed)
        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
    }

    private companion object {
        /** A varint cut short: the preferences proto cannot be parsed from it. */
        val UNREADABLE = byteArrayOf(0x80.toByte())
        val SENTINELS = listOf("fxi-e5b4a-cache-sentinel".toByteArray(), "fxi-e5b4a-journal-sentinel".toByteArray())
    }
}
