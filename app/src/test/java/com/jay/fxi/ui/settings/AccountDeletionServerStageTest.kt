package com.jay.fxi.ui.settings

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionServerStageTest {

    @Test
    fun uidChangeAfterServerResponse_neverAppliesOldOwnersResult() = runTest {
        assertStaleResponseIsNotApplied(
            owner = AuthIdentityFence("user-a", 3),
            replacement = AuthIdentityFence("user-b", 4)
        )
    }

    @Test
    fun sameUidNewGenerationAfterServerResponse_neverAppliesOldSessionResult() = runTest {
        assertStaleResponseIsNotApplied(
            owner = AuthIdentityFence("user-a", 3),
            replacement = AuthIdentityFence("user-a", 4)
        )
    }

    @Test
    fun currentOwner_appliesServerResultExactlyOnce() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        var current = owner
        var applications = 0
        val stage = stage(
            current = { current },
            responseGate = { Unit }
        )

        val status = stage.execute(owner) { response ->
            applications += 1
            response.statusCode
        }

        assertEquals(204, status)
        assertEquals(1, applications)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertStaleResponseIsNotApplied(
        owner: AuthIdentityFence,
        replacement: AuthIdentityFence
    ) {
        var current = owner
        var applied = false
        val responseReady = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val stage = stage(
            current = { current },
            responseGate = {
                responseReady.complete(Unit)
                releaseResponse.await()
            }
        )
        val operation = async {
            runCatching {
                stage.execute(owner) {
                    applied = true
                }
            }
        }

        responseReady.await()
        current = replacement
        releaseResponse.complete(Unit)

        assertTrue(operation.await().exceptionOrNull() is AuthIdentityChangedException)
        assertFalse(applied)
    }

    private fun stage(
        current: () -> AuthIdentityFence,
        responseGate: suspend () -> Unit
    ) = AccountDeletionServerStage(
        captureSnapshot = { owner ->
            if (current() != owner) throw AuthIdentityChangedException()
            AuthSnapshot(owner.uid, owner.authGeneration, "credential")
        },
        deleteUser = {
            responseGate()
            successfulNoContentResponse()
        },
        requireCurrent = { owner ->
            if (current() != owner) throw AuthIdentityChangedException()
        }
    )

    private fun successfulNoContentResponse() = AuthenticatedHttpResponse(
        statusCode = 204,
        headers = Headers.headersOf(),
        body = Unit,
        failure = null,
        rawBody = byteArrayOf()
    )
}
