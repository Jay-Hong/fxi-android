package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Constructor/exhaustiveness contracts only; fabricated receipts here are not facade successes. */
class ControlReleaseResultContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val o by lazy { ControlStoreTestStorage(File(folder.root, "results.preferences_pb")) }
    @After fun close() = runBlocking { o.close() }
    private val c = ControlReleaseFixtures.fixture().command
    private val a = ControlReleaseFixtures.fixture().command
    private val b = ControlReleaseFixtures.fixture().command
    private val work = LocalRecoveryWork(setOf(a), setOf(b))
    private val observation = ControlReleaseFixtures.read() as ControlRecordRead.Supported
    private val state = ControlCommandLifecycle.RELEASE_PENDING
    private fun assertContract(result: ControlCommandReleaseResult) {
        // Force every result branch through a compile-time exhaustive use site.
        val actual = when (result) {
            is ControlCommandReleaseResult.Released -> result
            is ControlCommandReleaseResult.AlreadyReleased -> result
            is ControlCommandReleaseResult.AlreadyTerminated -> result
            is ControlCommandReleaseResult.Rejected -> result
            is ControlCommandReleaseResult.Conflict -> result
            is ControlCommandReleaseResult.RecoveryRequired -> result
            is ControlCommandReleaseResult.Unconfirmed -> result
        }
        assertSame(c, actual.command)
        assertSame(work.unresolvedCommands, actual.localUnresolvedCommands)
        assertSame(work.pendingReleases, actual.localPendingReleases)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    @Test fun A17_releasedContract() = assertContract(ControlCommandReleaseResult.Released(c,
        work.unresolvedCommands, work.pendingReleases, ConfirmedControlSnapshot(observation), ConfirmationProof(RecordTransactionEvidence.LockedFileRead)))
    @Test fun A17_alreadyReleasedContract() = assertContract(ControlCommandReleaseResult.AlreadyReleased(c,
        work.unresolvedCommands, work.pendingReleases))
    @Test fun D2B6_alreadyTerminatedContract() = assertContract(ControlCommandReleaseResult.AlreadyTerminated(c,
        work.unresolvedCommands, work.pendingReleases))
    @Test fun A17_rejectedContract() = assertContract(ControlCommandReleaseResult.Rejected(c,
        work.unresolvedCommands, work.pendingReleases, ReleaseRejectionReason.InFlight, state, null))
    @Test fun A17_conflictContract() = assertContract(ControlCommandReleaseResult.Conflict(c,
        work.unresolvedCommands, work.pendingReleases, ConflictReason.CommandEvidenceMismatch, state, observation))
    @Test fun A17_recoveryContract() = assertContract(ControlCommandReleaseResult.RecoveryRequired(c,
        work.unresolvedCommands, work.pendingReleases, RecoveryReason.UninterpretableMetadata, state, observation))
    @Test fun A17_unconfirmedContract() = assertContract(ControlCommandReleaseResult.Unconfirmed(c,
        work.unresolvedCommands, work.pendingReleases, state, ControlAttemptPhase.ConfirmingStorage, observation, IOException("synthetic")))

    private fun cannotWrap(result: ControlStoreResult, message: String) {
        val method = ControlRecordStore::class.java.getDeclaredMethod("withRecoveryWork", ControlStoreResult::class.java, LocalRecoveryWork::class.java)
            .apply { isAccessible = true }
        val failure = runCatching { method.invoke(o.control, result, work) }.exceptionOrNull()
        assertEquals(message, (failure as? InvocationTargetException)?.targetException?.message)
    }
    @Test fun A17_pendingCannotBeStorageOutcome() = cannotWrap(ControlStoreResult.ReleasePending(c, emptySet(), emptySet()),
        "terminal results cannot be storage outcomes")
    @Test fun A17_releasedCannotBeStorageOutcome() = cannotWrap(ControlStoreResult.Released(c, emptySet(), emptySet()),
        "terminal results cannot be storage outcomes")
    @Test fun A17_positiveCannotBeNegativeStorageOutcome() = cannotWrap(ControlStoreResult.Confirmed(c, emptySet(), emptySet(),
        ConfirmedEffect.PostconditionConfirmed, emptyList(), ConfirmedControlSnapshot(observation), ConfirmationProof(RecordTransactionEvidence.LockedFileRead)),
        "positive results are published after storage confirmation")
}
