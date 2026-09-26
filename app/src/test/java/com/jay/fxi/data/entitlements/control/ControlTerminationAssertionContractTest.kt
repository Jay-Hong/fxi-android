package com.jay.fxi.data.entitlements.control

import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-1A contract supplement (measurement ledger r1 review): internal entry points that must refuse the new
 * termination states when called directly — the release evidence matcher's compatibility overload, the release record
 * decision, and the release transaction's result wrapper. These are direct-call boundaries, not owner-path evidence.
 * The implementation thread reads but does not edit this file.
 */
class ControlTerminationAssertionContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val o by lazy { ControlStoreTestStorage(File(folder.root, "assert.preferences_pb")) }
    @After fun close() = runBlocking { o.close() }
    private fun invoke(target: Any, name: String) {
        try {
            target.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(target)
        } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    private fun closed(terminated: Boolean): TrackedControlCommand = ControlReleaseFixtures.fixture().also {
        invoke(it.command, "beginTermination"); if (terminated) invoke(it.command, "completeTermination")
    }
    private fun refused(id: String, block: () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("D2B6/TA.$id: closedStateRefused $failure", failure is IllegalStateException)
    }

    @Test fun TA_01_evidenceMatcherRefusesTerminationStates() {
        for ((id, terminated) in listOf("01a" to false, "01b" to true)) {
            val tracked = closed(terminated)
            val row = ControlReleaseFixtures.row(tracked.command)
            refused(id) { ControlReleaseEvidenceMatch.matches(tracked.command, row, row) }
        }
    }

    @Test fun TA_02_releaseRecordDecisionRefusesTerminationStates() {
        for ((id, terminated) in listOf("02a" to false, "02b" to true)) {
            val tracked = closed(terminated)
            refused(id) { ControlCommandReleaseDecision.decide(ControlReleaseFixtures.read(), tracked) }
        }
    }

    @Test fun TA_03_releaseTransactionDoesNotCarryAlreadyTerminated() {
        val c = ControlReleaseFixtures.fixture().command
        val method = ControlRecordStore::class.java.getDeclaredMethod("withReleaseRecoveryWork",
            ControlCommandReleaseResult::class.java, LocalRecoveryWork::class.java).apply { isAccessible = true }
        val failure = runCatching {
            method.invoke(o.control, ControlCommandReleaseResult.AlreadyTerminated(c, emptySet(), emptySet()),
                LocalRecoveryWork(emptySet(), emptySet()))
        }.exceptionOrNull()
        assertEquals("D2B6/TA.03: notADecisionResult", "release transaction carried a non-decision result",
            (failure as? InvocationTargetException)?.targetException?.message)
    }
}
