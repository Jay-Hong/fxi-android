package com.jay.fxi

import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.local.LegacyStorePurge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the purge looks where DataStore actually puts things.
 *
 * The file logic is checked on the JVM against a temp directory, which proves the deleting but not
 * the address. A wrong directory is the failure this cannot catch on its own: `purge` would answer
 * "nothing to delete" every time and look exactly like a phone that had already been cleaned. So
 * this writes a real file at the real path and asks whether it survives.
 */
class LegacyStorePurgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun legacy() = File(context.filesDir, "datastore/fxi_bank_preferences.preferences_pb")

    @After fun cleanUp() = legacy().delete().let { }

    @Test
    fun theV1BankPreferenceFileIsDeletedWhereItReallyLives() = runTest {
        legacy().parentFile?.mkdirs()
        legacy().writeText("v1")
        assertTrue("사전 조건: 파일이 있어야 한다", legacy().exists())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        LegacyStorePurge(context, scope).start()

        // The purge is fire-and-forget by design, so this waits on the work rather than the call.
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertTrue("v1 은행 설정 파일이 남았다: ${legacy().absolutePath}", !legacy().exists())
    }
}
