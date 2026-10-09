package com.jay.fxi.ui.settings

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.DeletionAdmissionPhase
import com.jay.fxi.data.entitlements.DeletionAdmissionRecord
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionServerStageTest {

    private val deletions = DeletionAdmissionStore()
    private var issued = 0
    private val nextOperation: () -> String = { "op-${++issued}" }

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
        assertEquals(listOf(record(owner, "op-1", DeletionAdmissionPhase.SERVER_DELETED)), deletions.records)
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
        // CC3-D06: the server's 204 is recorded even though the answer is not applied.
        assertEquals(listOf(record(owner, "op-1", DeletionAdmissionPhase.SERVER_DELETED)), deletions.records)
        assertTrue("CC3-D08 the old owner's UID stays blocked", deletions.blocks(owner.uid))
    }

    /** CC3-D01: a capture that fails publishes nothing. */
    @Test
    fun captureFailure_publishesNothing() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        var asked = false
        val stage = stage(current = { AuthIdentityFence("user-b", 1) }, responseGate = { asked = true })

        val failure = runCatching { stage.execute(owner) { Unit } }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertFalse("CC3-D01 fixture: no request", asked)
        assertEquals("CC3-D01 no record", emptyList<DeletionAdmissionRecord>(), deletions.records)
        assertEquals("CC3-D01 no operation id drawn", 0, issued)
    }

    /** CC3-D02/D03: the owner is blocked before the request leaves, and a 204 is recorded before the owner check. */
    @Test
    fun ownerIsBlockedBeforeTheRequest_andA204IsRecordedBeforeTheOwnerCheck() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        val atRequest = mutableListOf<List<DeletionAdmissionRecord>>()
        val atOwnerCheck = mutableListOf<List<DeletionAdmissionRecord>>()
        val stage = AccountDeletionServerStage(
            captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
            deleteUser = {
                atRequest += deletions.records
                response(204)
            },
            requireCurrent = { atOwnerCheck += deletions.records },
            deletions = deletions,
            newOperationId = nextOperation
        )

        stage.execute(owner) { Unit }

        assertEquals("CC3-D02 requesting when the request leaves",
            listOf(listOf(record(owner, "op-1", DeletionAdmissionPhase.REQUESTING_SERVER))), atRequest)
        assertEquals("CC3-D03 server-deleted by the owner check",
            listOf(listOf(record(owner, "op-1", DeletionAdmissionPhase.SERVER_DELETED))), atOwnerCheck)
    }

    /** CC3-D04: any status but 204 — every other code from 100 to 599 — keeps the request standing; the owner check still precedes the application. */
    @Test
    fun anyStatusButNoContent_keepsTheRequestStanding() = runTest {
        for (status in (100..599) - 204) {
            val store = DeletionAdmissionStore()
            val owner = AuthIdentityFence("user-$status", 1)
            val order = mutableListOf<String>()
            val stage = AccountDeletionServerStage(
                captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
                deleteUser = { response(status) },
                requireCurrent = { order += "requireCurrent" },
                deletions = store,
                newOperationId = { "op-$status" }
            )

            val applied = stage.execute(owner) { order += "apply"; it.statusCode }

            assertEquals("CC3-D04 $status reaches the caller", status, applied)
            assertEquals("CC3-D04 $status stays requesting",
                listOf(record(owner, "op-$status", DeletionAdmissionPhase.REQUESTING_SERVER)), store.records)
            assertEquals("CC3-D04 $status the owner check precedes the application", listOf("requireCurrent", "apply"), order)
        }
    }

    /**
     * CC3-D05: a request that fails — IO, timeout, plain cancellation, or a moved session with no status or another status —
     * keeps the request standing; a moved session that carried a 204 records the deletion. Every failure reaches the caller
     * unchanged, and nothing is applied or released.
     */
    @Test
    fun aFailedRequestKeepsStanding_andAMovedSessionWithA204RecordsTheDeletion() = runTest {
        val cases = listOf<Pair<Throwable, DeletionAdmissionPhase>>(
            IOException("io") to DeletionAdmissionPhase.REQUESTING_SERVER,
            SocketTimeoutException("timeout") to DeletionAdmissionPhase.REQUESTING_SERVER,
            CancellationException("cancelled") to DeletionAdmissionPhase.REQUESTING_SERVER,
            AuthIdentityChangedException() to DeletionAdmissionPhase.REQUESTING_SERVER,
            AuthIdentityChangedException(statusCode = 204) to DeletionAdmissionPhase.SERVER_DELETED
        ) + ((100..599) - 204).map { AuthIdentityChangedException(statusCode = it) to DeletionAdmissionPhase.REQUESTING_SERVER }
        for ((index, case) in cases.withIndex()) {
            val (thrown, phase) = case
            val store = DeletionAdmissionStore()
            val owner = AuthIdentityFence("user-$index", 1)
            var applied = false
            var checked = false
            val stage = AccountDeletionServerStage(
                captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
                deleteUser = { throw thrown },
                requireCurrent = { checked = true },
                deletions = store,
                newOperationId = { "op-$index" }
            )

            val failure = runCatching { stage.execute(owner) { applied = true } }.exceptionOrNull()

            assertSame("CC3-D05 case $index reaches the caller unchanged", thrown, failure)
            assertEquals("CC3-D05 case $index", listOf(record(owner, "op-$index", phase)), store.records)
            assertFalse("CC3-D05 case $index not applied", applied)
            assertFalse("CC3-D05 case $index no owner check after a failed request", checked)
        }
    }

    /** CC3-D07: each execute draws its own operation and a second one leaves the first record alone. */
    @Test
    fun eachExecuteHasItsOwnOperation() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        var status = 500
        val stage = AccountDeletionServerStage(
            captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
            deleteUser = { response(status) },
            requireCurrent = {},
            deletions = deletions,
            newOperationId = nextOperation
        )

        stage.execute(owner) { Unit }
        status = 204
        stage.execute(owner) { Unit }

        assertEquals(
            "CC3-D07 two operations, each with its own outcome",
            listOf(
                record(owner, "op-1", DeletionAdmissionPhase.REQUESTING_SERVER),
                record(owner, "op-2", DeletionAdmissionPhase.SERVER_DELETED)
            ),
            deletions.records
        )
    }

    /** CC3-D07b: an earlier server-deleted record does not stop a later request, which keeps its own outcome. */
    @Test
    fun anEarlierDeletionDoesNotStopALaterRequest() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        val statuses = ArrayDeque(listOf(204, 500))
        var requests = 0
        val stage = AccountDeletionServerStage(
            captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
            deleteUser = { requests += 1; response(statuses.removeFirst()) },
            requireCurrent = {},
            deletions = deletions,
            newOperationId = nextOperation
        )

        stage.execute(owner) { Unit }
        stage.execute(owner) { Unit }

        assertEquals("CC3-D07b the second request left", 2, requests)
        assertEquals(
            listOf(
                record(owner, "op-1", DeletionAdmissionPhase.SERVER_DELETED),
                record(owner, "op-2", DeletionAdmissionPhase.REQUESTING_SERVER)
            ),
            deletions.records
        )
    }

    /** CC3-D08: the record blocks its owner's UID and no other (generations are the store's own contract). */
    @Test
    fun theRecordBlocksItsOwnersUidAndNoOther() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        stage(current = { owner }, responseGate = {}).execute(owner) { Unit }

        assertTrue("CC3-D08 the owner's UID", deletions.blocks("user-a"))
        assertFalse("CC3-D08 another UID", deletions.blocks("user-b"))
    }

    /**
     * CC3-D10: two overlapping operations, the second finishing first, each advance only their own record — whichever of the
     * two gets the 204, by a response or by a moved session's refusal, and with both requests from the same identity.
     */
    @Test
    fun overlappingOperations_finishingInReverse_eachAdvanceOnlyTheirOwn() = runTest {
        val a3 = AuthIdentityFence("user-a", 3)
        val a4 = AuthIdentityFence("user-a", 4)
        val noContent: () -> AuthenticatedHttpResponse<Unit> = { response(204) }
        val failed: () -> AuthenticatedHttpResponse<Unit> = { response(500) }
        val lateNoContent: () -> AuthenticatedHttpResponse<Unit> = { throw AuthIdentityChangedException(statusCode = 204) }
        val sd = DeletionAdmissionPhase.SERVER_DELETED
        val rq = DeletionAdmissionPhase.REQUESTING_SERVER
        data class Case(val name: String, val owners: List<AuthIdentityFence>, val second: () -> AuthenticatedHttpResponse<Unit>,
                        val first: () -> AuthenticatedHttpResponse<Unit>, val phases: List<DeletionAdmissionPhase>)
        val cases = listOf(
            Case("second 204, first 500", listOf(a3, a4), noContent, failed, listOf(rq, sd)),
            Case("second 500, first 204", listOf(a3, a4), failed, noContent, listOf(sd, rq)),
            Case("second 500, first late 204", listOf(a3, a4), failed, lateNoContent, listOf(sd, rq)),
            Case("same identity: second 500, first 204", listOf(a3, a3), failed, noContent, listOf(sd, rq))
        )
        for (case in cases) {
            val store = DeletionAdmissionStore()
            var drawn = 0
            val gates = listOf(CompletableDeferred<() -> AuthenticatedHttpResponse<Unit>>(), CompletableDeferred())
            var calls = 0
            val stage = AccountDeletionServerStage(
                captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
                deleteUser = { gates[calls++].await().invoke() },
                requireCurrent = {},
                deletions = store,
                newOperationId = { "op-${++drawn}" }
            )
            val first = async { runCatching { stage.execute(case.owners[0]) { Unit } } }
            val second = async { runCatching { stage.execute(case.owners[1]) { Unit } } }
            testScheduler.runCurrent()
            assertEquals("CC3-D10 ${case.name} fixture: both requests left", 2, calls)
            gates[1].complete(case.second)
            second.await()
            gates[0].complete(case.first)
            first.await()
            assertEquals(
                "CC3-D10 ${case.name}: each answer advanced only its own operation",
                listOf(record(case.owners[0], "op-1", case.phases[0]), record(case.owners[1], "op-2", case.phases[1])),
                store.records
            )
        }
    }

    /** CC3-D11: an application that fails after a 204 leaves the deletion recorded. */
    @Test
    fun anApplicationThatFailsAfterA204_leavesTheDeletionRecorded() = runTest {
        val owner = AuthIdentityFence("user-a", 3)
        val boom = IllegalStateException("apply")
        val stage = stage(current = { owner }, responseGate = {})

        val failure = runCatching { stage.execute(owner) { throw boom } }.exceptionOrNull()

        assertSame("CC3-D11 the application's failure", boom, failure)
        assertEquals(listOf(record(owner, "op-1", DeletionAdmissionPhase.SERVER_DELETED)), deletions.records)
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
        },
        deletions = deletions,
        newOperationId = nextOperation
    )

    private fun record(owner: AuthIdentityFence, operation: String, phase: DeletionAdmissionPhase) =
        DeletionAdmissionRecord(owner, operation, phase)

    private fun response(status: Int) = AuthenticatedHttpResponse(
        statusCode = status,
        headers = Headers.headersOf(),
        body = Unit,
        failure = null,
        rawBody = byteArrayOf()
    )

    private fun successfulNoContentResponse() = response(204)
}
