package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PushSnapshotCaptureTest {

    @Test
    fun ordinaryCredentialFailure_skipsOnlyBestEffortPushWork() = runTest {
        val owner = AuthIdentityFence("user-a", 4)
        val failure = IOException("transient token lookup failure")
        var reported: Exception? = null

        val snapshot = capturePushSnapshotOrNull(
            owner = owner,
            capture = { throw failure },
            onFailure = { reported = it }
        )

        assertNull(snapshot)
        assertSame(failure, reported)
    }

    @Test
    fun nonIoCredentialFailure_isAlsoContainedByTheBestEffortBoundary() = runTest {
        val owner = AuthIdentityFence("user-a", 4)
        val failure = IllegalStateException("credential adapter failed")
        var reported: Exception? = null

        val snapshot = capturePushSnapshotOrNull(
            owner = owner,
            capture = { throw failure },
            onFailure = { reported = it }
        )

        assertNull(snapshot)
        assertSame(failure, reported)
    }

    @Test
    fun identityChange_stillCancelsTheStaleOwnerFlow() {
        val owner = AuthIdentityFence("user-a", 4)

        assertThrows(AuthIdentityChangedException::class.java) {
            kotlinx.coroutines.test.runTest {
                capturePushSnapshotOrNull(owner, capture = {
                    throw AuthIdentityChangedException()
                })
            }
        }
    }

    @Test
    fun callerCancellation_isNeverDowngradedToBestEffortFailure() {
        val owner = AuthIdentityFence("user-a", 4)
        val cancellation = CancellationException("caller stopped")

        val thrown = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest {
                capturePushSnapshotOrNull(owner, capture = { throw cancellation })
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun currentOwner_returnsCapturedSnapshot() = runTest {
        val owner = AuthIdentityFence("user-a", 4)
        val expected = AuthSnapshot("user-a", 4, "credential")

        val actual = capturePushSnapshotOrNull(owner, capture = {
            assertEquals(owner, it)
            expected
        })

        assertSame(expected, actual)
    }

    @Test
    fun absentOwner_doesNotAttemptCredentialLookup() = runTest {
        var called = false

        val actual = capturePushSnapshotOrNull(owner = null, capture = {
            called = true
            AuthSnapshot("unexpected", 1, "unexpected")
        })

        assertNull(actual)
        assertFalse(called)
    }
}
